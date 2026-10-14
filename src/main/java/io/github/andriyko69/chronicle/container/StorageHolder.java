/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;

/** Live handles belong exclusively to the owning server thread; only detached snapshots leave it. */
public sealed interface StorageHolder {
    enum Presence { PRESENT, DESTROYED, UNKNOWN }

    Object identity();
    ServerLevel level();
    BlockPos position();
    String storageId();
    Presence presence();
    List<ItemStack> items();

    /** Whether each slot is a distinct position the player chose, so rollback must target the same slot. */
    default boolean positional() { return false; }

    default Map<ContainerNetDiff.Key, ItemTransactionPayload> snapshot() {
        if (!level().getServer().isSameThread()) throw new IllegalStateException("Storage snapshot off server thread");
        var stacks = new ArrayList<ContainerNetDiff.Stack>();
        var items = items();
        for (int slot = 0; slot < items.size(); slot++) {
            var stack = items.get(slot);
            if (stack.isEmpty()) continue;
            var captured = ContainerItemCapture.capture(stack, storageId(), level().registryAccess());
            stacks.add(positional() ? new ContainerNetDiff.Stack(new ContainerNetDiff.Key(captured.key().itemId(), captured.key().componentKey(), slot),
                    captured.payload().withSlot(slot)) : captured);
        }
        return ContainerNetDiff.group(stacks);
    }

    /** Storage whose contents leave its position when the block is removed. */
    static Optional<StorageHolder> spillable(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        return holdsItems(entity) ? block(level, pos, entity) : Optional.empty();
    }

    /** Block entities whose item history Chronicle records at the block's own position. */
    static boolean holdsItems(BlockEntity entity) {
        return entity instanceof Container || entity instanceof LecternBlockEntity || entity instanceof CampfireBlockEntity;
    }

    /** Storage that players change by clicking the block itself rather than through a resolvable world menu. */
    static Optional<StorageHolder> menuless(ServerLevel level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        if (entity instanceof ChiseledBookShelfBlockEntity || entity instanceof DecoratedPotBlockEntity
                || entity instanceof JukeboxBlockEntity || entity instanceof LecternBlockEntity || entity instanceof CampfireBlockEntity)
            return block(level, pos, entity);
        return Optional.empty();
    }

    static StorageHolder frame(ItemFrame frame) {
        if (!(frame.level() instanceof ServerLevel level)) throw new IllegalArgumentException("Client item frame");
        return new Frame(level, frame.getPos().immutable(), BuiltInRegistries.ENTITY_TYPE.getKey(frame.getType()).toString(), frame);
    }

    static StorageHolder stand(ArmorStand stand) {
        if (!(stand.level() instanceof ServerLevel level)) throw new IllegalArgumentException("Client armor stand");
        return new Stand(level, stand.blockPosition().immutable(), BuiltInRegistries.ENTITY_TYPE.getKey(stand.getType()).toString(), stand);
    }

    private static Optional<StorageHolder> block(ServerLevel level, BlockPos pos, BlockEntity entity) {
        // Reading an unrolled loot container generates its loot; an audit must not mutate the world or invent items.
        if (entity instanceof RandomizableContainer loot && loot.getLootTable() != null) return Optional.empty();
        return Optional.of(new Block(level, pos.immutable(), BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(), entity));
    }

    record Block(ServerLevel level, BlockPos position, String storageId, BlockEntity entity) implements StorageHolder {
        @Override public Object identity() { return entity; }
        @Override public boolean positional() { return entity instanceof ChiseledBookShelfBlockEntity; }
        @Override public Presence presence() {
            if (!level.isLoaded(position)) return Presence.UNKNOWN;
            return entity.isRemoved() || level.getBlockEntity(position) != entity ? Presence.DESTROYED : Presence.PRESENT;
        }
        @Override public List<ItemStack> items() {
            return switch (entity) {
                case LecternBlockEntity lectern -> List.of(lectern.getBook());
                case CampfireBlockEntity campfire -> List.copyOf(campfire.getItems());
                case Container container -> IntStream.range(0, container.getContainerSize()).mapToObj(container::getItem).toList();
                default -> throw new IllegalStateException("Unsupported storage " + storageId);
            };
        }
    }

    record Frame(ServerLevel level, BlockPos position, String storageId, ItemFrame entity) implements StorageHolder {
        @Override public Object identity() { return entity; }
        @Override public Presence presence() { return entityPresence(entity); }
        @Override public List<ItemStack> items() { return List.of(entity.getItem()); }
    }

    record Stand(ServerLevel level, BlockPos position, String storageId, ArmorStand entity) implements StorageHolder {
        /** An armor stand has no body slot. */
        private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
                EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
        @Override public Object identity() { return entity; }
        @Override public Presence presence() { return entityPresence(entity); }
        @Override public List<ItemStack> items() { return SLOTS.stream().map(entity::getItemBySlot).toList(); }
    }

    /** An entity unloaded or moved to another dimension is not destroyed; its contents cannot be confirmed. */
    private static Presence entityPresence(Entity entity) {
        if (!entity.isRemoved()) return Presence.PRESENT;
        var reason = entity.getRemovalReason();
        return reason != null && reason.shouldDestroy() ? Presence.DESTROYED : Presence.UNKNOWN;
    }
}
