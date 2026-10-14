/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.rollback;

import io.github.andriyko69.chronicle.causality.CausalityHooks;
import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import io.github.andriyko69.chronicle.container.ContainerItemCapture;
import io.github.andriyko69.chronicle.container.StorageAudit;
import io.github.andriyko69.chronicle.container.WorldContainer;
import io.github.andriyko69.chronicle.event.*;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies one detached audit row to the live world. Server thread only. Mutations post no NeoForge place/break events,
 * run with Chronicle's Mixin hooks suppressed, and bracket storage changes with viewer checkpoints, so they are never
 * audited themselves.
 */
final class WorldRollback {
    private static final Logger LOG = LoggerFactory.getLogger(WorldRollback.class);
    /** No neighbour block updates (no redstone, dispenser or TNT reactions) and no drops from the replaced block. */
    static final int BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Neighbour shapes still follow (a chest half re-pairs, water recedes), but anything they break drops nothing. */
    static final int SHAPE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
    /** Water spreads 7 blocks and falls; the recession follows a little further, and is bounded however the fluid spread. */
    static final int SWEEP_REACH = 12;
    static final int SWEEP_LIMIT = 512;
    /** Recession work charged to the engine's per-tick operation budget: one operation per this many examined blocks. */
    static final int RECEDE_PER_OPERATION = 4;
    /** Holds a chunk at full status, which loads its entities, without ticking it; expires on its own. */
    private static final TicketType<ChunkPos> ENTITY_TICKET = TicketType.create("chronicle_rollback", Comparator.comparingLong(ChunkPos::toLong), 300);
    /** Snapshots are at most 64 KiB on disk; the accounter bounds their in-memory expansion. */
    private static final long NBT_QUOTA = 8L * 1024 * 1024;

    enum Skip {
        UNKNOWN_DIMENSION("unknown dimension"), OUTSIDE_WORLD("outside the build height"), UNKNOWN_STATE("unknown block state"),
        SNAPSHOT_REJECTED("saved block data incompatible"), PARTIAL_SPILL("spill only partly restored; quarantined"),
        WOULD_IGNITE("TNT beside an active power source"),
        PARTNER_BLOCKED("other half of a two-block structure obstructed"), UNSUPPORTED_ITEM("item not reconstructable"),
        STORAGE_MISSING("storage missing"), NO_SPACE("not enough space"), ITEMS_MISSING("items no longer present"),
        PAIRED_BREAK_SKIPPED("container break skipped"), FAILED("failed (see server log)");
        final String label;
        Skip(String label) { this.label = label; }
    }
    /**
     * A mutation failed part-way and could not be undone: the world no longer matches either side of the row, so it must
     * be quarantined rather than retried.
     */
    static final class Uncertain extends RuntimeException {
        Uncertain(Throwable cause) { super(cause.getMessage(), cause); }
    }

    /**
     * {@code contentsRestored}: the block entity was recreated from the snapshot, which already holds the spilled items.
     * {@code discarded}: the replaced storage still held items, which were cleared rather than dropped. {@code work}: extra
     * per-tick budget the change consumed beyond itself (receding stranded fluid).
     */
    record Outcome(Skip skip, boolean contentsRestored, boolean discarded, int work) {
        static final Outcome APPLIED = new Outcome(null, false, false, 0);
        static Outcome skipped(Skip reason) { return new Outcome(reason, false, false, 0); }
        boolean applied() { return skip == null; }
    }

    private final MinecraftServer server;
    WorldRollback(MinecraftServer server) { this.server = server; }

    /** Whether applying {@code event} needs no chunk load; unknown dimensions are skipped without one. */
    boolean loaded(AuditEvent event) {
        var level = level(event.envelope().worldId());
        return level == null || level.isLoaded(position(event));
    }

    Outcome block(AuditEvent event, boolean restore) {
        var level = level(event.envelope().worldId());
        if (level == null) return Outcome.skipped(Skip.UNKNOWN_DIMENSION);
        var payload = new BlockDeltaPayloadCodec().decode(event.payloadVersion(), event.payload());
        var target = state(restore ? payload.after() : payload.before());
        if (target == null) return Outcome.skipped(Skip.UNKNOWN_STATE);
        var pos = position(event);
        if (level.isOutsideBuildHeight(pos)) return Outcome.skipped(Skip.OUTSIDE_WORLD);
        var current = level.getBlockState(pos);
        // A property change (waterlogging) keeps the live block entity. A destroyed block's saved data is restored even
        // over a same-type replacement, whose own data is cleared and reported like any other replaced storage.
        boolean historical = !restore && !payload.before().blockId().equals(payload.after().blockId()) && target.hasBlockEntity()
                && payload.snapshotStatus() == BlockDeltaPayload.SnapshotStatus.PRESENT;
        boolean recreate = !current.is(target.getBlock()) || historical;
        var partner = partner(pos, target);
        var partnerState = partner == null ? null : partnerState(target);
        boolean placePartner = partner != null && level.getBlockState(partner) != partnerState;
        if (!recreate && current == target && !placePartner) return Outcome.APPLIED;
        // Placement callbacks still run: powered TNT would prime itself the moment it is restored.
        if (recreate && target.getBlock() instanceof TntBlock && level.hasNeighborSignal(pos)) return Outcome.skipped(Skip.WOULD_IGNITE);
        // Everything is validated before anything changes: a skipped row leaves the world as it was, and stays retryable.
        if (placePartner && (level.isOutsideBuildHeight(partner) || !level.getBlockState(partner).is(target.getBlock())
                && !level.getBlockState(partner).canBeReplaced())) return Outcome.skipped(Skip.PARTNER_BLOCKED);
        CompoundTag snapshot = null;
        if (historical) {
            snapshot = validatedSnapshot(level, pos, target, payload.snapshotNbt());
            if (snapshot == null) return Outcome.skipped(Skip.SNAPSHOT_REJECTED);
        }
        var removed = recreate && current.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        var previous = removed == null ? null : removed.saveWithFullMetadata(level.registryAccess());
        boolean discarded = removed != null && holdsItems(removed);
        // One bracket around the whole operation: a viewer of a surviving block entity is rebaselined only once its
        // final contents (the loaded snapshot, or the reverted original) are in place.
        var session = removed == null ? null : WorldContainer.canonicalPosition(level, pos);
        if (removed != null) StorageAudit.externalChange(level, session, removed, true);
        var fluid = current.getFluidState();
        int work = 0;
        // Draining a source (rolling back a pour, restoring a pickup, un-waterlogging) strands the flow it fed.
        boolean drains = fluid.isSource() && !(target.getFluidState().isSource() && target.getFluidState().getType().isSame(fluid.getType()));
        try {
            if (recreate || current != target) mutate(level, pos, removed, target);
            if (drains) work = recede(level, pos, fluid.getType()) / RECEDE_PER_OPERATION;
            // A door or bed half only survives beside its other half, which vanilla removed without an audit row of its own.
            if (placePartner) {
                var occupant = level.getBlockState(partner);
                var occupantEntity = occupant.hasBlockEntity() ? level.getBlockEntity(partner) : null;
                var occupantSession = occupantEntity == null ? null : WorldContainer.canonicalPosition(level, partner);
                if (occupantEntity != null) StorageAudit.externalChange(level, occupantSession, occupantEntity, true);
                try { mutate(level, partner, occupantEntity, partnerState); }
                finally { if (occupantEntity != null) StorageAudit.externalChange(level, occupantSession, occupantEntity, false); }
            }
            if (snapshot != null && !loadSnapshot(level, pos, target, snapshot)) {
                // The detached probe loaded but the live block entity did not: put back exactly what was there.
                mutate(level, pos, level.getBlockEntity(pos), current);
                var reverted = level.getBlockEntity(pos);
                if (previous != null && reverted != null) {
                    reverted.loadWithComponents(previous, level.registryAccess());
                    reverted.setChanged();
                }
                return Outcome.skipped(Skip.FAILED);
            }
        } catch (RuntimeException failure) {
            // Contents may already be cleared or the block half-replaced: neither side of the row holds any more.
            throw failure instanceof Uncertain uncertain ? uncertain : new Uncertain(failure);
        } finally {
            if (removed != null) StorageAudit.externalChange(level, session, removed, false);
        }
        return new Outcome(null, snapshot != null, discarded, work);
    }

    /**
     * Recedes the flowing (never source) blocks of {@code fluid} stranded by a drained source, immediately rather than
     * over many fluid ticks; lingering lava would keep igniting what the rollback just restored. Each connected flowing
     * block is recomputed with vanilla's own rule until nothing changes, and only ever lowered or removed: flow that
     * another source still feeds, such as a neighbouring waterfall, keeps exactly what that source sustains. The
     * remainder is then left to ordinary fluid ticks, as is any block whose neighbours are not loaded: recomputing it
     * would load chunks outside the engine's budget. Returns the blocks examined.
     */
    static int recede(ServerLevel level, BlockPos origin, Fluid fluid) {
        if (!(fluid instanceof FlowingFluid flowing)) return 0;
        var region = new LinkedHashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(origin);
        while (!queue.isEmpty() && region.size() < SWEEP_LIMIT) {
            var pos = queue.poll();
            for (var direction : Direction.values()) {
                var next = pos.relative(direction);
                if (next.distManhattan(origin) > SWEEP_REACH || region.contains(next) || !level.isLoaded(next)) continue;
                var state = level.getBlockState(next);
                var current = state.getFluidState();
                if (!(state.getBlock() instanceof LiquidBlock) || current.isSource() || !current.getType().isSame(fluid)) continue;
                region.add(next);
                queue.add(next);
            }
        }
        var work = new ArrayDeque<>(region);
        int changed = 0;
        // Levels only fall, at most eight steps per block, so this converges well within the bound.
        for (int steps = region.size() * 9; !work.isEmpty() && steps > 0; steps--) {
            var pos = work.poll();
            var state = level.getBlockState(pos);
            var current = state.getFluidState();
            if (!(state.getBlock() instanceof LiquidBlock) || current.isEmpty() || current.isSource() || !current.getType().isSame(fluid)) continue;
            if (Arrays.stream(Direction.values()).anyMatch(direction -> !level.isLoaded(pos.relative(direction)))) continue;
            var next = flowing.getNewLiquid(level, pos, state);
            if (!(next.isEmpty() || !next.isSource() && next.getType().isSame(fluid) && next.getAmount() < current.getAmount())) continue;
            CausalityHooks.suppressing(() -> level.setBlock(pos, next.isEmpty() ? Blocks.AIR.defaultBlockState() : next.createLegacyBlock(), BLOCK_FLAGS));
            changed++;
            for (var direction : Direction.values()) if (region.contains(pos.relative(direction))) work.add(pos.relative(direction));
        }
        for (var pos : region) {
            var remaining = level.getFluidState(pos);
            if (!remaining.isEmpty() && remaining.getType().isSame(fluid)) level.scheduleTick(pos, remaining.getType(), flowing.getTickDelay(level));
        }
        return region.size() + changed;
    }

    /** Callers bracket this with storage checkpoints. */
    private static void mutate(ServerLevel level, BlockPos pos, BlockEntity removed, BlockState target) {
        CausalityHooks.suppressing(() -> {
            // Emptied first: vanilla's onRemove would otherwise drop the contents into the world.
            if (removed != null) Clearable.tryClear(removed);
            level.setBlock(pos, target, BLOCK_FLAGS);
            target.updateNeighbourShapes(level, pos, SHAPE_FLAGS);
        });
    }

    /** The other half of a two-block structure (door, tall plant, bed), or null for a single block. */
    static BlockPos partner(BlockPos pos, BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        return null;
    }
    private static BlockState partnerState(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        return state.setValue(BlockStateProperties.BED_PART, state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
    }

    private static boolean holdsItems(BlockEntity entity) {
        return switch (entity) {
            case Container container -> !container.isEmpty();
            case LecternBlockEntity lectern -> lectern.hasBook();
            case CampfireBlockEntity campfire -> campfire.getItems().stream().anyMatch(stack -> !stack.isEmpty());
            default -> false;
        };
    }

    /** Rollback of a take or restore of a put inserts the stack; the reverse removes it. Either is all-or-nothing. */
    Outcome item(AuditEvent event, boolean restore) {
        boolean insert = event.envelope().eventType().equals("ITEM_TAKE") != restore;
        var payload = new ItemTransactionPayloadCodec().decode(event.payloadVersion(), event.payload());
        return change(event, payload, insert, payload.count());
    }

    /** Inserts only the {@code count} items of a spill row that its restored snapshot did not bring back. */
    Outcome insertMissing(AuditEvent spill, int count) {
        return change(spill, new ItemTransactionPayloadCodec().decode(spill.payloadVersion(), spill.payload()), true, count);
    }

    private Outcome change(AuditEvent event, ItemTransactionPayload payload, boolean insert, int count) {
        var level = level(event.envelope().worldId());
        if (level == null) return Outcome.skipped(Skip.UNKNOWN_DIMENSION);
        var template = template(level, event.envelope().targetId(), payload);
        if (template == null) return Outcome.skipped(Skip.UNSUPPORTED_ITEM);
        var pos = position(event);
        if (level.isOutsideBuildHeight(pos)) return Outcome.skipped(Skip.OUTSIDE_WORLD);
        var storages = storages(level, pos, payload.containerBlockId());
        if (storages.isEmpty()) return Outcome.skipped(Skip.STORAGE_MISSING);
        for (var storage : storages) {
            StorageAudit.externalChange(level, storage.sessionPosition(), storage.identity(), true);
            boolean changed;
            try {
                var result = new boolean[1];
                CausalityHooks.suppressing(() -> result[0] = insert ? insert(storage.slots(), template, count, payload.slot())
                        : remove(storage.slots(), template, count, payload.slot()));
                changed = result[0];
            } finally {
                StorageAudit.externalChange(level, storage.sessionPosition(), storage.identity(), false);
            }
            if (changed) return Outcome.APPLIED;
        }
        return Outcome.skipped(insert ? Skip.NO_SPACE : Skip.ITEMS_MISSING);
    }

    /**
     * For each spill row of one restored break, how many of its items the restored block entity does not hold. Every
     * restored unit counts toward one row only, and exact identities claim units before rows whose components were not
     * retained (matched by item ID). Vanilla replaces an undecodable saved stack with nothing instead of failing, so a
     * successful snapshot load alone does not prove the spill came back.
     */
    int[] missing(List<AuditEvent> spills) { return spills.isEmpty() ? new int[0] : allocate(spills.getFirst(), spills).missing(); }

    /**
     * Whether the storage a restore is about to remove at {@code breakEvent}'s position holds anything its spill rows do
     * not account for: items added after the rollback, which the removal deletes along with the spilled contents.
     */
    boolean exceeds(AuditEvent breakEvent, List<AuditEvent> spills) {
        return allocate(breakEvent, spills).leftover().stream().anyMatch(stack -> !stack.isEmpty());
    }

    private record Allocation(int[] missing, List<ItemStack> leftover) {}
    private Allocation allocate(AuditEvent at, List<AuditEvent> spills) {
        var missing = new int[spills.size()];
        var codec = new ItemTransactionPayloadCodec();
        for (int i = 0; i < spills.size(); i++) missing[i] = codec.decode(spills.get(i).payloadVersion(), spills.get(i).payload()).count();
        var level = level(at.envelope().worldId());
        if (level == null) return new Allocation(missing, List.of());
        var pos = position(at);
        var entity = level.getBlockEntity(pos);
        var slots = entity == null ? null : blockSlots(level, pos, level.getBlockState(pos), entity, false);
        if (slots == null) return new Allocation(missing, List.of());
        var pool = new ArrayList<ItemStack>();
        for (int slot = 0; slot < slots.size(); slot++) if (!slots.get(slot).isEmpty()) pool.add(slots.get(slot).copy());
        var templates = new ItemStack[spills.size()];
        for (int i = 0; i < spills.size(); i++)
            templates[i] = template(level, spills.get(i).envelope().targetId(), codec.decode(spills.get(i).payloadVersion(), spills.get(i).payload()));
        for (boolean exact : new boolean[] {true, false}) {
            for (int i = 0; i < spills.size(); i++) {
                if ((templates[i] != null) != exact) continue;
                String itemId = spills.get(i).envelope().targetId();
                for (var stack : pool) {
                    if (missing[i] == 0) break;
                    boolean match = exact ? ItemStack.isSameItemSameComponents(stack, templates[i])
                            : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
                    if (!match || stack.isEmpty()) continue;
                    int take = Math.min(missing[i], stack.getCount());
                    stack.shrink(take);
                    missing[i] -= take;
                }
            }
        }
        return new Allocation(missing, pool);
    }

    /**
     * Whether an item row's entity storage (frame, armor stand) cannot be resolved yet: entities load asynchronously after
     * their chunk, so absence is only meaningful once they have. Requests the load while waiting.
     */
    boolean entitiesPending(AuditEvent event) {
        var type = event.envelope().eventType();
        if (!type.equals("ITEM_TAKE") && !type.equals("ITEM_PUT")) return false;
        var level = level(event.envelope().worldId());
        if (level == null) return false;
        var storage = ResourceLocation.tryParse(new ItemTransactionPayloadCodec().decode(event.payloadVersion(), event.payload()).containerBlockId());
        if (storage == null || BuiltInRegistries.BLOCK.containsKey(storage) || !BuiltInRegistries.ENTITY_TYPE.containsKey(storage)) return false;
        var chunk = new ChunkPos(position(event));
        if (level.areEntitiesLoaded(chunk.toLong())) return false;
        level.getChunkSource().addRegionTicket(ENTITY_TICKET, chunk, 0, chunk);
        return true;
    }

    private ServerLevel level(String worldId) {
        var location = ResourceLocation.tryParse(worldId);
        return location == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }
    private static BlockPos position(AuditEvent event) {
        return new BlockPos(event.envelope().x(), event.envelope().y(), event.envelope().z());
    }

    /** Every recorded property must still exist with that value; a changed block definition is skipped, never guessed. */
    static BlockState state(BlockStateSnapshot snapshot) {
        var location = ResourceLocation.tryParse(snapshot.blockId());
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) return null;
        var block = BuiltInRegistries.BLOCK.get(location);
        var state = block.defaultBlockState();
        for (var entry : snapshot.properties().entrySet()) {
            var property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) return null;
            state = with(state, property, entry.getValue());
            if (state == null) return null;
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(null);
    }

    /** The snapshot, if it decodes into a detached block entity valid for {@code state}; vanilla's own load path is the probe. */
    private static CompoundTag validatedSnapshot(ServerLevel level, BlockPos pos, BlockState state, byte[] bytes) {
        try {
            var tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.create(NBT_QUOTA));
            var probe = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
            if (probe != null && probe.getType().isValid(state)) return tag;
            LOG.warn("Chronicle rollback rejected the {} snapshot at {} for {}", tag.getString("id"), pos, state);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Chronicle rollback could not decode the block entity snapshot at {}", pos, failure);
        }
        return null;
    }

    private static boolean loadSnapshot(ServerLevel level, BlockPos pos, BlockState state, CompoundTag snapshot) {
        var entity = level.getBlockEntity(pos);
        if (entity == null) return false;
        try {
            entity.loadWithComponents(snapshot, level.registryAccess());
            entity.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
            return true;
        } catch (RuntimeException failure) {
            LOG.warn("Chronicle rollback could not load the block entity snapshot at {}", pos, failure);
            return false;
        }
    }

    /**
     * The exact recorded stack, or null when its components were not retained (oversized or failed capture) or no longer
     * decode to the same canonical form; matching by item ID alone could remove or create the wrong items.
     */
    static ItemStack template(ServerLevel level, String itemId, ItemTransactionPayload payload) {
        var location = ResourceLocation.tryParse(itemId);
        if (location == null) return null;
        var item = BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, location)).orElse(null);
        if (item == null) return null;
        DataComponentPatch patch;
        switch (payload.componentSnapshotStatus()) {
            case NONE -> patch = DataComponentPatch.EMPTY;
            case PRESENT -> {
                try {
                    var tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(payload.componentBytes())), NbtAccounter.create(NBT_QUOTA));
                    patch = DataComponentPatch.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).result().orElse(null);
                } catch (IOException | RuntimeException failure) { patch = null; }
                if (patch == null) return null;
            }
            default -> { return null; }
        }
        var stack = new ItemStack(item, 1, patch);
        try {
            var recaptured = ContainerItemCapture.capture(stack, payload.containerBlockId(), level.registryAccess()).payload();
            if (recaptured.componentSnapshotStatus() != payload.componentSnapshotStatus()
                    || !Arrays.equals(recaptured.componentBytes(), payload.componentBytes())) return null;
        } catch (IllegalArgumentException unsupported) { return null; }
        return stack;
    }

    /** A live, server-thread view of one storage's slots. */
    private interface Slots {
        int size();
        ItemStack get(int slot);
        boolean accepts(int slot, ItemStack stack);
        int limit(int slot, ItemStack stack);
        void set(int slot, ItemStack stack);
    }
    private record Storage(Object identity, BlockPos sessionPosition, Slots slots) {}

    private static List<Storage> storages(ServerLevel level, BlockPos pos, String storageId) {
        var location = ResourceLocation.tryParse(storageId);
        if (location == null) return List.of();
        if (BuiltInRegistries.BLOCK.containsKey(location)) {
            var state = level.getBlockState(pos);
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(location)) return List.of();
            var entity = level.getBlockEntity(pos);
            var slots = blockSlots(level, pos, state, entity, true);
            return slots == null ? List.of() : List.of(new Storage(entity, WorldContainer.canonicalPosition(level, pos), slots));
        }
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(location)) return List.of();
        var storages = new ArrayList<Storage>();
        for (Entity entity : level.getEntities((Entity)null, new AABB(pos).inflate(1), candidate -> !candidate.isRemoved()
                && BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).equals(location))) {
            if (entity instanceof ItemFrame frame && frame.getPos().equals(pos)) storages.add(new Storage(frame, null, frame(frame)));
            else if (entity instanceof ArmorStand stand && stand.blockPosition().equals(pos)) storages.add(new Storage(stand, null, stand(stand)));
        }
        return storages;
    }

    /** {@code whole}: a chest half stands for its whole double chest, as session rows recorded it. */
    private static Slots blockSlots(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity, boolean whole) {
        return switch (entity) {
            case LecternBlockEntity lectern -> lectern(level, pos, lectern);
            case CampfireBlockEntity campfire -> campfire(level, pos, campfire);
            case Container own -> {
                // Session rows of a double chest are recorded at one half for the whole inventory.
                var container = whole && state.getBlock() instanceof ChestBlock chest ? ChestBlock.getContainer(chest, state, level, pos, true) : null;
                container = container == null ? own : container;
                // Touching an unrolled loot container would generate its loot: never invent items.
                yield own instanceof RandomizableContainer loot && loot.getLootTable() != null ? null : container(container);
            }
            case null, default -> null;
        };
    }

    private static Slots container(Container container) {
        return new Slots() {
            @Override public int size() { return container.getContainerSize(); }
            @Override public ItemStack get(int slot) { return container.getItem(slot); }
            @Override public boolean accepts(int slot, ItemStack stack) { return container.canPlaceItem(slot, stack); }
            @Override public int limit(int slot, ItemStack stack) { return container.getMaxStackSize(stack); }
            @Override public void set(int slot, ItemStack stack) { container.setItem(slot, stack); container.setChanged(); }
        };
    }

    private static Slots lectern(ServerLevel level, BlockPos pos, LecternBlockEntity lectern) {
        return new Slots() {
            @Override public int size() { return 1; }
            @Override public ItemStack get(int slot) { return lectern.getBook(); }
            @Override public boolean accepts(int slot, ItemStack stack) { return lectern.getBook().isEmpty() && stack.is(ItemTags.LECTERN_BOOKS); }
            @Override public int limit(int slot, ItemStack stack) { return 1; }
            @Override public void set(int slot, ItemStack stack) {
                lectern.setBook(stack);
                var state = level.getBlockState(pos);
                if (state.hasProperty(LecternBlock.HAS_BOOK))
                    level.setBlock(pos, state.setValue(LecternBlock.HAS_BOOK, !stack.isEmpty()).setValue(LecternBlock.POWERED, false), Block.UPDATE_CLIENTS);
            }
        };
    }

    private static Slots campfire(ServerLevel level, BlockPos pos, CampfireBlockEntity campfire) {
        return new Slots() {
            @Override public int size() { return campfire.getItems().size(); }
            @Override public ItemStack get(int slot) { return campfire.getItems().get(slot); }
            @Override public boolean accepts(int slot, ItemStack stack) {
                return campfire.getItems().get(slot).isEmpty() && campfire.getCookableRecipe(stack).isPresent();
            }
            @Override public int limit(int slot, ItemStack stack) { return 1; }
            @Override public void set(int slot, ItemStack stack) {
                if (stack.isEmpty()) {
                    campfire.getItems().set(slot, ItemStack.EMPTY);
                    campfire.setChanged();
                    level.sendBlockUpdated(pos, campfire.getBlockState(), campfire.getBlockState(), Block.UPDATE_ALL);
                } else {
                    // placeFood fills the first empty slot with its cook time; inserts plan slots in that same order.
                    var recipe = campfire.getCookableRecipe(stack).orElseThrow();
                    campfire.placeFood(null, stack.copy(), recipe.value().getCookingTime());
                }
            }
        };
    }

    private static Slots frame(ItemFrame frame) {
        return new Slots() {
            @Override public int size() { return 1; }
            @Override public ItemStack get(int slot) { return frame.getItem(); }
            @Override public boolean accepts(int slot, ItemStack stack) { return frame.getItem().isEmpty(); }
            @Override public int limit(int slot, ItemStack stack) { return 1; }
            @Override public void set(int slot, ItemStack stack) { frame.setItem(stack); }
        };
    }

    private static final List<EquipmentSlot> STAND_SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
    private static Slots stand(ArmorStand stand) {
        return new Slots() {
            @Override public int size() { return STAND_SLOTS.size(); }
            @Override public ItemStack get(int slot) { return stand.getItemBySlot(STAND_SLOTS.get(slot)); }
            @Override public boolean accepts(int slot, ItemStack stack) {
                return get(slot).isEmpty() && stand.getEquipmentSlotForItem(stack) == STAND_SLOTS.get(slot);
            }
            @Override public int limit(int slot, ItemStack stack) { return STAND_SLOTS.get(slot).isArmor() ? 1 : stack.getMaxStackSize(); }
            @Override public void set(int slot, ItemStack stack) { stand.setItemSlot(STAND_SLOTS.get(slot), stack); }
        };
    }

    /**
     * Plans the whole insertion before changing anything: the recorded slot of positional storage first (a chiseled
     * bookshelf book goes back where the player put it), then identical stacks, as vanilla merges, then empty slots.
     * A recorded slot taken over since falls back to the others.
     */
    private static boolean insert(Slots slots, ItemStack template, int count, Integer preferred) {
        var allotted = new int[slots.size()];
        int remaining = count;
        if (preferred != null && preferred < slots.size() && slots.get(preferred).isEmpty() && slots.accepts(preferred, template)) {
            allotted[preferred] = Math.min(remaining, slots.limit(preferred, template));
            remaining -= allotted[preferred];
        }
        for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
            var current = slots.get(slot);
            // A slot already holding the identical item takes it back even where vanilla forbids placement (furnace output).
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, template)) continue;
            int take = Math.min(remaining, Math.max(0, slots.limit(slot, template) - current.getCount()));
            allotted[slot] = take; remaining -= take;
        }
        for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
            if (allotted[slot] > 0 || !slots.get(slot).isEmpty() || !slots.accepts(slot, template)) continue;
            int take = Math.min(remaining, slots.limit(slot, template));
            allotted[slot] = take; remaining -= take;
        }
        if (remaining > 0) return false;
        var originals = new ItemStack[slots.size()];
        try {
            for (int slot = 0; slot < slots.size(); slot++) {
                if (allotted[slot] == 0) continue;
                var current = slots.get(slot);
                originals[slot] = current.copy();
                slots.set(slot, current.isEmpty() ? template.copyWithCount(allotted[slot]) : current.copyWithCount(current.getCount() + allotted[slot]));
            }
        } catch (RuntimeException failure) { undo(slots, originals, failure); }
        return true;
    }

    /**
     * Takes from the last matching slots. Positional storage takes only from its recorded slot: an identical book in
     * another slot is someone else's, so a mismatch is a retryable skip rather than a substitute.
     */
    private static boolean remove(Slots slots, ItemStack template, int count, Integer preferred) {
        if (preferred != null) {
            if (preferred >= slots.size()) return false;
            var recorded = slots.get(preferred);
            if (recorded.isEmpty() || !ItemStack.isSameItemSameComponents(recorded, template) || recorded.getCount() < count) return false;
        }
        int available = 0;
        for (int slot = 0; slot < slots.size(); slot++) {
            var current = slots.get(slot);
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, template)) available += current.getCount();
        }
        if (available < count) return false;
        int remaining = count;
        var originals = new ItemStack[slots.size()];
        try {
            var order = new ArrayList<Integer>();
            if (preferred != null) order.add(preferred);
            else for (int slot = slots.size() - 1; slot >= 0; slot--) order.add(slot);
            for (int slot : order) {
                if (remaining == 0) break;
                var current = slots.get(slot);
                if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, template)) continue;
                int take = Math.min(remaining, current.getCount());
                originals[slot] = current.copy();
                slots.set(slot, take == current.getCount() ? ItemStack.EMPTY : current.copyWithCount(current.getCount() - take));
                remaining -= take;
            }
        } catch (RuntimeException failure) { undo(slots, originals, failure); }
        return true;
    }

    /** A modded storage may throw part-way: the slots written so far are put back, or the row is uncertain. */
    private static void undo(Slots slots, ItemStack[] originals, RuntimeException failure) {
        try {
            for (int slot = 0; slot < originals.length; slot++) if (originals[slot] != null) slots.set(slot, originals[slot]);
        } catch (RuntimeException undo) {
            failure.addSuppressed(undo);
            throw new Uncertain(failure);
        }
        throw failure;
    }
}
