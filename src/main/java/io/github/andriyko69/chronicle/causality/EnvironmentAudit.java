/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.codec.SnapshotNbt;
import io.github.andriyko69.chronicle.container.ContainerNetDiff;
import io.github.andriyko69.chronicle.container.StorageAudit;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.listener.PlayerActor;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Environmental block destruction (explosions, fire burnout, projectiles) attributed to its direct source and, when one
 * can be traced, to the responsible player as root actor. All causal state lives on the server thread; only detached
 * envelopes and payloads are submitted.
 *
 * <p>Explosion and projectile removals are captured at their pre-action hooks and emitted only once the block at that
 * position is confirmed gone, so protected or surviving blocks are never logged. Vanilla finalizes each explosion and
 * projectile hit synchronously after its hook, so captures are confirmed at the next such hook (attributing each removal
 * to the action that performed it) or at the tick boundary. Burnouts are captured just before vanilla removes or
 * replaces the block and confirmed when {@code checkBurnOut} returns.
 */
public final class EnvironmentAudit {
    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentAudit.class);
    static final String FIRE = "#fire";
    static final String EXPLOSION = "#explosion";
    static final String DISPENSER = "#dispenser";
    private static final String WATER = BuiltInRegistries.BLOCK.getKey(Blocks.WATER).toString();
    static final int ENTITY_CAPACITY = 4096;
    static final Duration ENTITY_TTL = Duration.ofMinutes(15);
    /** Bed and anchor explosions have no source entity; the click that triggered them is within this distance. */
    private static final double CLICK_REACH = 2.0;
    /**
     * A player's redstone activation (click, placement, break, step or shot) is credited with a dispenser trigger this
     * far away within this many ticks, so repeaters, observers and other delays between them keep the attribution.
     */
    public static final double ACTIVATION_REACH = 32.0;
    public static final int ACTIVATION_TICKS = 100;
    static final int MAX_ACTIVATIONS = 256;

    private record Position(ServerLevel level, BlockPos pos) {}
    private record PendingBreak(ServerLevel level, BlockPos pos, BlockState state, BlockStateSnapshot before, SnapshotNbt nbt,
            AuditActor actor, Instant at) {}
    /** Root is captured when the tick starts: vanilla may remove the ticking fire itself and still spread or burn after. */
    private record FireTick(ServerLevel level, BlockPos pos, CausalActor root, FireTick outer) {}
    private record Click(ServerLevel level, BlockPos pos, CausalActor actor, long tick) {}
    /**
     * A click or shot only counts once the block it hit has changed from {@code before} (a lever toggled, a button or
     * target powered); placements, breaks and sensor presses are changes already and carry null.
     */
    private record Activation(ServerLevel level, BlockPos pos, CausalActor actor, long tick, BlockState before) {
        boolean effective() { return before == null || level.getBlockState(pos) != before; }
    }
    private record Priming(ServerLevel level, CausalActor root, Priming outer) {}

    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private final StorageAudit storage;
    private final FireCausalityTracker fire;
    private final CausalMemory<UUID> entities;
    /** The player who last placed each dispenser or loaded a bucket into it: the root of dispenses no recent activation explains. */
    private final CausalMemory<Position> dispenserOwners;
    /** Root of each dispenser's latest trigger; replaced or cleared by the next one, so it governs the dispense it scheduled. */
    private final CausalMemory<Position> dispenserTriggers;
    private final Map<Position, PendingBreak> pending = new LinkedHashMap<>();
    /** Air positions an attributed explosion may set alight while finalizing; valid only within that tick. */
    private final Map<Position, CausalActor> explosionFire = new HashMap<>();
    /** Roots of this tick's explosions, by identity, for the TNT blocks each one primes while finalizing. */
    private final Map<Explosion, CausalActor> explosionRoots = new IdentityHashMap<>();
    private FireTick fireTick;
    private PendingBreak burning;
    private Click click;
    /** Recent redstone-relevant player activations, oldest first; bounded in age and count. */
    private final ArrayDeque<Activation> activations = new ArrayDeque<>();
    /** A player's contact with a sensor while vanilla decides whether it presses; kept only if it does. */
    private Activation contact;
    private Priming priming;
    private long lastFailureWarning = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    public EnvironmentAudit(Supplier<ChronicleRuntime> runtime, ChronicleClock clock, StorageAudit storage) {
        this.runtime = runtime; this.clock = clock; this.storage = storage;
        fire = new FireCausalityTracker(clock);
        entities = new CausalMemory<>(clock, ENTITY_CAPACITY, ENTITY_TTL);
        dispenserOwners = new CausalMemory<>(clock, ENTITY_CAPACITY, ENTITY_TTL);
        dispenserTriggers = new CausalMemory<>(clock, ENTITY_CAPACITY, ENTITY_TTL);
    }

    public void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::detonating);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::placed);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::fluidPlaced);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::impact);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::interacted);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clicked);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::broken);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::joined);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> settle(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> settle(event.getServer()));
        storage.onRecorded(this::recorded);
        CausalityHooks.register(this);
    }

    public void detonating(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !owned(level)) return;
        try {
            var explosion = event.getExplosion();
            var direct = explosion.getDirectSourceEntity();
            var root = explosionRoot(level, explosion, direct);
            var actor = AuditActor.environment(direct == null ? EXPLOSION : AuditActor.entityId(direct), root);
            confirmFinished();
            if (root != null) explosionRoots.put(explosion, root);
            // KEEP explosions (mobGriefing off, wind charges) list blocks they will not remove; only entities are affected.
            var blocks = explosion.interactsWithBlocks() ? event.getAffectedBlocks() : List.<BlockPos>of();
            for (var pos : blocks) {
                var position = new Position(level, pos.immutable());
                if (root != null && explosion.fire) explosionFire.put(position, root);
                var state = level.getBlockState(pos);
                if (!state.isAir()) pending.put(position, capture(level, position.pos(), state, actor));
            }
            storage.exploding(level, blocks, event.getAffectedEntities(), actor);
        } catch (RuntimeException failure) { captureFailed(failure); }
    }

    private CausalActor explosionRoot(ServerLevel level, Explosion explosion, Entity direct) {
        var indirect = explosion.getIndirectSourceEntity();
        if (indirect instanceof Player player) return root(player);
        if (explosion.damageSource.getEntity() instanceof Player player) return root(player);
        if (direct != null) {
            var remembered = entities.get(direct.getUUID());
            if (remembered != null) return remembered;
        }
        // A hostile mob explodes because of the player it is targeting (creepers, ghast and wither projectiles).
        for (var entity : new Entity[]{direct, indirect}) {
            if (entity instanceof Mob mob && mob.getTarget() instanceof Player player) return root(player);
        }
        var trigger = click;
        if (direct == null && trigger != null && trigger.level() == level && trigger.tick() == tick(level)
                && trigger.pos().getCenter().distanceTo(explosion.center()) <= CLICK_REACH) return trigger.actor();
        return null;
    }

    public void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(event.getEntity() instanceof Player player)) return;
        var snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        for (var snapshot : snapshots) {
            var state = level.getBlockState(snapshot.getPos());
            if (state.getBlock() instanceof BaseFireBlock) fire.seed(dimension(level), snapshot.getPos().asLong(), root(player), tick(level));
            if (state.getBlock() instanceof DispenserBlock)
                dispenserOwners.put(new Position(level, snapshot.getPos().immutable()), root(player), tick(level));
            activated(level, snapshot.getPos(), state, player, false);
        }
    }

    public void broken(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        activated(level, event.getPos(), event.getState(), event.getPlayer(), false);
    }

    /** A player, a player's projectile, or a vehicle carrying a player touches a plate, tripwire or detector rail. */
    void redstoneContacting(Level level, BlockPos pos, Entity entity) {
        contact = null;
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return;
        Player player = entity instanceof Player direct ? direct
                : entity instanceof Projectile projectile && projectile.getOwner() instanceof Player owner ? owner
                : entity.getPassengers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
        if (player != null) contact = new Activation(server, pos.immutable(), root(player), tick(server), null);
    }

    void redstoneContacted(Level level, boolean pressed) {
        var touched = contact;
        contact = null;
        if (touched == null || touched.level() != level) return;
        if (pressed) record(touched);
    }

    /**
     * Only redstone components, and blocks touching a dispenser, can explain a later trigger; a bystander mining nearby
     * must not claim a clock-driven dispenser.
     */
    private void activated(ServerLevel level, BlockPos pos, BlockState state, Player player, boolean mustChange) {
        if (redstoneRelevant(level, pos, state))
            record(new Activation(level, pos.immutable(), root(player), tick(level), mustChange ? state : null));
    }

    private void record(Activation activation) {
        expireActivations(activation.tick());
        var latest = activations.peekLast();
        // Repeated clicks on one component keep a single entry per player and position.
        if (latest != null && latest.level() == activation.level() && latest.pos().equals(activation.pos())
                && latest.actor().uuid().equals(activation.actor().uuid()))
            activations.pollLast();
        activations.addLast(activation);
        while (activations.size() > MAX_ACTIVATIONS) activations.pollFirst();
    }

    private static boolean redstoneRelevant(ServerLevel level, BlockPos pos, BlockState state) {
        var block = state.getBlock();
        if (state.isSignalSource() || block instanceof RedStoneWireBlock || block instanceof DiodeBlock || block instanceof TripWireBlock
                || block instanceof TargetBlock)
            return true;
        if (block instanceof DispenserBlock) return false;
        for (var side : Direction.values()) {
            if (level.getBlockState(pos.relative(side)).getBlock() instanceof DispenserBlock) return true;
        }
        return false;
    }

    private void expireActivations(long now) {
        while (!activations.isEmpty() && now - activations.peekFirst().tick() > ACTIVATION_TICKS) activations.pollFirst();
    }

    /**
     * A player put a bucket into a dispenser, through a menu closed or checkpointed since; they own what it now pours.
     * A diff overlapping another viewer's session cannot show which of them loaded it, so it leaves the owner unchanged.
     */
    private void recorded(ServerLevel level, BlockPos pos, PlayerActor actor, List<ContainerNetDiff.Delta> deltas, boolean exclusive) {
        if (!exclusive || !level.getServer().isSameThread() || actor.uuid() == null) return;
        var entity = level.getBlockEntity(pos);
        if (!(entity instanceof DispenserBlockEntity) || entity instanceof DropperBlockEntity) return;
        boolean loadedBucket = deltas.stream().anyMatch(delta -> delta.eventType().equals("ITEM_PUT")
                && BuiltInRegistries.ITEM.get(ResourceLocation.parse(delta.itemId())) instanceof DispensibleContainerItem);
        if (loadedBucket) dispenserOwners.put(new Position(level, pos.immutable()), new CausalActor(actor.uuid(), actor.name(), clock.now()), tick(level));
    }

    public void fluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(event.getNewState().getBlock() instanceof BaseFireBlock)) return;
        var root = fire.rootAt(dimension(level), event.getLiquidPos().asLong());
        if (root != null) fire.seed(dimension(level), event.getPos().asLong(), root, tick(level));
    }

    void bucketEmptied(Player player, BlockCapture before) {
        var level = before.level();
        if (!level.getServer().isSameThread()) return;
        if (level.getFluidState(before.pos()).is(FluidTags.LAVA)) fire.seed(dimension(level), before.pos().asLong(), root(player), tick(level));
        bucketChanged(AuditActor.player(player), before, "BLOCK_PLACE");
    }

    void bucketFilled(Player player, BlockCapture before) { bucketChanged(AuditActor.player(player), before, "BLOCK_BREAK"); }

    /** A trigger no recent activation explains (a long-running clock) leaves the dispense to the dispenser's owner. */
    void dispenserTriggered(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return;
        var dispenser = new Position(server, pos.immutable());
        long now = tick(server);
        expireActivations(now);
        var center = pos.getCenter();
        var touching = contact;
        CausalActor trigger = touching != null && touching.level() == server && touching.pos().getCenter().distanceTo(center) <= ACTIVATION_REACH
                ? touching.actor() : null;
        for (var iterator = activations.descendingIterator(); iterator.hasNext() && trigger == null; ) {
            var activation = iterator.next();
            if (activation.level() == server && activation.pos().getCenter().distanceTo(center) <= ACTIVATION_REACH && activation.effective())
                trigger = activation.actor();
        }
        if (trigger != null) dispenserTriggers.put(dispenser, trigger, now);
        else dispenserTriggers.remove(dispenser);
    }

    /** Credits a viewer who loaded a bucket through a still-open menu before the dispenser acts. */
    void dispensing(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && server.getServer().isSameThread()) storage.automating(server, pos, true);
    }

    /** A dispenser's bucket poured or scooped at {@code before}; attributed to the player who triggered, loaded or placed it. */
    void dispensed(BlockPos pos, BlockCapture before, boolean pickup) {
        var level = before.level();
        if (!level.getServer().isSameThread()) return;
        storage.automating(level, pos, false);
        var dispenser = new Position(level, pos.immutable());
        var root = dispenserTriggers.get(dispenser);
        if (root == null) root = dispenserOwners.get(dispenser);
        if (!pickup && level.getBlockState(before.pos()) != before.state() && level.getFluidState(before.pos()).is(FluidTags.LAVA)) {
            if (root != null) fire.seed(dimension(level), before.pos().asLong(), root, tick(level));
            else fire.unattributed(dimension(level), before.pos().asLong(), tick(level));
        }
        bucketChanged(AuditActor.environment(DISPENSER, root), before, pickup ? "BLOCK_BREAK" : "BLOCK_PLACE");
    }

    /**
     * Vaporized, ultrawarm and already-source placements report success without changing the block; they log nothing.
     * Waterlogging or draining a block is recorded under water so fluid lookups find it; the payload keeps the host block.
     */
    private void bucketChanged(AuditActor actor, BlockCapture before, String type) {
        var level = before.level();
        if (!owned(level)) return;
        try {
            var after = level.getBlockState(before.pos());
            if (after == before.state()) return;
            var old = PlayerBlockListener.state(before.state());
            var current = PlayerBlockListener.state(after);
            String target = waterlogToggled(before.state(), after) ? WATER : type.equals("BLOCK_BREAK") ? old.blockId() : current.blockId();
            submit(level, before.pos(), type, actor, old, current, target, before.nbt(), clock.now());
        } catch (RuntimeException failure) { captureFailed(failure); }
    }

    private static boolean waterlogToggled(BlockState before, BlockState after) {
        return before.getBlock() == after.getBlock() && before.hasProperty(BlockStateProperties.WATERLOGGED)
                && before.getValue(BlockStateProperties.WATERLOGGED) != after.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Explosions claim their armor stands at Detonate; the damage source attributes every other destruction. A player's
     * projectile is the player's own action, as for decorated pots.
     */
    void armorStandBreaking(ArmorStand stand, DamageSource source) {
        if (!(stand.level() instanceof ServerLevel level) || !owned(level)) return;
        try { storage.armorStandBreaking(stand, damageActor(level, stand, source)); }
        catch (RuntimeException failure) { captureFailed(failure); }
    }

    private AuditActor damageActor(ServerLevel level, Entity victim, DamageSource source) {
        var direct = source.getDirectEntity();
        if (direct instanceof Projectile projectile) return projectileActor(projectile);
        if (source.getEntity() instanceof Player player)
            return direct == null || direct == player ? AuditActor.player(player) : AuditActor.environment(AuditActor.entityId(direct), root(player));
        if (direct != null) return AuditActor.environment(AuditActor.entityId(direct), entities.get(direct.getUUID()));
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return AuditActor.environment(EXPLOSION, null);
        if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IGNITES_ARMOR_STANDS) || source.is(DamageTypeTags.BURNS_ARMOR_STANDS))
            return AuditActor.environment(FIRE, fire.rootAt(dimension(level), victim.blockPosition().asLong()));
        return AuditActor.environment(source.typeHolder().unwrapKey().map(key -> "#" + key.location().getPath()).orElse("#unknown"), null);
    }

    public void impact(ProjectileImpactEvent event) {
        var projectile = event.getProjectile();
        if (event.isCanceled() || !(projectile.level() instanceof ServerLevel level) || !owned(level)) return;
        try {
            HitResult hit = event.getRayTraceResult();
            if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
                var pos = block.getBlockPos().immutable();
                var state = level.getBlockState(pos);
                if (projectile.getOwner() instanceof Player shooter) activated(level, pos, state, shooter, true);
                if (!(state.getBlock() instanceof DecoratedPotBlock)) return;
                var actor = projectileActor(projectile);
                confirmFinished();
                pending.put(new Position(level, pos), capture(level, pos, state, actor));
                storage.removing(level, pos, actor);
            } else if (hit instanceof EntityHitResult entity && entity.getEntity() instanceof ItemFrame frame) {
                storage.projectileHit(frame, projectileActor(projectile));
            }
        } catch (RuntimeException failure) { captureFailed(failure); }
    }

    private AuditActor projectileActor(Projectile projectile) {
        var owner = projectile.getOwner();
        if (owner instanceof Player player) return AuditActor.player(player);
        CausalActor root = owner == null ? null : entities.get(owner.getUUID());
        if (root == null && owner instanceof Mob mob && mob.getTarget() instanceof Player player) root = root(player);
        return AuditActor.environment(AuditActor.entityId(projectile), root);
    }

    public void interacted(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || !(event.getTarget() instanceof Creeper creeper) || !(creeper.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || !event.getItemStack().is(ItemTags.CREEPER_IGNITERS)) return;
        entities.put(creeper.getUUID(), root(event.getEntity()), tick(level));
    }

    public void clicked(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        var state = level.getBlockState(event.getPos());
        activated(level, event.getPos(), state, event.getEntity(), true);
        var block = state.getBlock();
        if (block instanceof BedBlock || block instanceof RespawnAnchorBlock)
            click = new Click(level, event.getPos().immutable(), root(event.getEntity()), tick(level));
    }

    /**
     * TNT primed by fire or by another explosion has no player owner; it inherits the root of what primed it. Both are
     * scoped to the vanilla call that spawns the TNT (checkBurnOut's onCaughtFire, TntBlock.wasExploded), never inferred
     * from proximity.
     */
    public void joined(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof PrimedTnt tnt) || tnt.getOwner() instanceof Player
                || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        CausalActor root = null;
        var burn = burning;
        var chain = priming;
        if (chain != null && chain.level() == level) root = chain.root();
        else if (burn != null && burn.level() == level && burn.pos().getCenter().distanceTo(tnt.position()) <= 1.5) root = rootOf(burn.actor());
        if (root != null) entities.put(tnt.getUUID(), root, tick(level));
    }

    void chainPriming(Level level, Explosion explosion) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return;
        priming = new Priming(server, explosionRoots.get(explosion), priming);
    }

    void chainPrimed(Level level, Explosion explosion) {
        var current = priming;
        if (current != null && current.level() == level) priming = current.outer();
    }

    void fireTickStarting(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) return;
        fireTick = new FireTick(level, pos.immutable(), fire.rootAt(dimension(level), pos.asLong()), fireTick);
    }

    void fireTickFinished(ServerLevel level, BlockPos pos) {
        var current = fireTick;
        if (current != null && current.level() == level && current.pos().equals(pos)) fireTick = current.outer();
    }

    void burning(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !owned(server)) return;
        try {
            var state = server.getBlockState(pos);
            if (state.isAir()) return;
            var context = fireTick;
            var actor = AuditActor.environment(FIRE, context != null && context.level() == server ? context.root() : null);
            burning = capture(server, pos.immutable(), state, actor);
            storage.removing(server, pos, actor);
            storage.supportRemoving(server, pos, actor);
        } catch (RuntimeException failure) { captureFailed(failure); }
    }

    void burnChecked(Level level, BlockPos pos) {
        var burn = burning;
        burning = null;
        if (burn != null && burn.level() == level && burn.pos().equals(pos)) confirm(burn);
    }

    void firePlaced(Level level, BlockPos pos, BlockState oldState) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread() || oldState.getBlock() instanceof BaseFireBlock) return;
        String dimension = dimension(server);
        long key = pos.asLong(), tick = tick(server);
        var context = fireTick;
        if (context != null && context.level() == server) { fire.spread(dimension, context.root(), key, tick); return; }
        var seeded = explosionFire.get(new Position(server, pos));
        if (seeded != null) fire.seed(dimension, key, seeded, tick);
        else fire.unattributed(dimension, key, tick);
    }

    private PendingBreak capture(ServerLevel level, BlockPos pos, BlockState state, AuditActor actor) {
        return new PendingBreak(level, pos, state, PlayerBlockListener.state(state), PlayerBlockListener.blockEntity(level, pos, state),
                actor, clock.now());
    }

    private void settle(MinecraftServer server) {
        if (!server.isSameThread()) return;
        // Tick-scoped context; a fire tick or burn that threw before its exit hook must not leak into later placements.
        fireTick = null; burning = null; click = null; priming = null; contact = null;
        explosionFire.clear();
        explosionRoots.clear();
        if (pending.isEmpty()) return;
        var batch = List.copyOf(pending.values());
        pending.clear();
        for (var removal : batch) confirm(removal);
    }

    /**
     * Records captures whose blocks earlier explosions or hits have already removed. Survivors stay pending until the tick
     * boundary, so a nested explosion inside another's finalization cannot discard the outer one's removals, and a later
     * capture at a survivor's position replaces it rather than letting the earlier actor claim a later removal.
     */
    private void confirmFinished() {
        pending.values().removeIf(this::confirm);
    }

    /** Returns whether the capture is resolved: recorded as removed, or impossible to confirm. */
    private boolean confirm(PendingBreak removal) {
        try {
            if (!removal.level().isLoaded(removal.pos())) {
                LOG.warn("Chronicle cannot confirm removal of {} at {} (unloaded); no row recorded", removal.before().blockId(), removal.pos());
                return true;
            }
            var current = removal.level().getBlockState(removal.pos());
            if (current.is(removal.state().getBlock())) return false;
            submit(removal.level(), removal.pos(), "BLOCK_BREAK", removal.actor(), removal.before(), PlayerBlockListener.state(current),
                    removal.nbt(), removal.at());
        } catch (RuntimeException failure) { captureFailed(failure); }
        return true;
    }

    private void submit(ServerLevel level, BlockPos pos, String type, AuditActor actor, BlockStateSnapshot before,
            BlockStateSnapshot after, SnapshotNbt nbt, Instant at) {
        submit(level, pos, type, actor, before, after, type.equals("BLOCK_BREAK") ? before.blockId() : after.blockId(), nbt, at);
    }

    private void submit(ServerLevel level, BlockPos pos, String type, AuditActor actor, BlockStateSnapshot before,
            BlockStateSnapshot after, String target, SnapshotNbt nbt, Instant at) {
        var destination = runtime.get();
        if (destination == null) return;
        var envelope = new EventEnvelope(dimension(level), pos.getX(), pos.getY(), pos.getZ(), type,
                actor.type(), actor.id(), actor.uuid(), actor.rootUuid(), actor.rootName(), target);
        var payload = new BlockDeltaPayload(before, after, BlockDeltaPayload.Observation.HOOK_OBSERVATION, nbt.status(), nbt.bytes());
        var result = destination.submit(AuditEvent.block(EventId.generate(), at, envelope, payload));
        if (result.status() != Submission.Status.ACCEPTED)
            LOG.warn("Chronicle {} {} at {} was not admitted: {}", actor.id(), type, pos, result.status());
    }

    public void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Environment audit shutdown off server thread");
        settle(server);
        fire.clear();
        entities.clear();
        dispenserOwners.clear();
        dispenserTriggers.clear();
        activations.clear();
    }

    public int trackedCauses(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Environment audit accessed off server thread");
        return fire.size() + entities.size() + dispenserOwners.size() + dispenserTriggers.size() + activations.size();
    }

    private CausalActor root(Player player) { return new CausalActor(player.getUUID(), player.getGameProfile().getName(), clock.now()); }
    private CausalActor rootOf(AuditActor actor) { return actor.asRoot(clock.now()); }
    private boolean owned(ServerLevel level) { return level.getServer().isSameThread() && runtime.get() != null; }
    private static long tick(ServerLevel level) { return level.getServer().getTickCount(); }
    private static String dimension(ServerLevel level) { return level.dimension().location().toString(); }

    private synchronized void captureFailed(RuntimeException failure) {
        long now = System.nanoTime();
        if (now - lastFailureWarning >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
            lastFailureWarning = now;
            LOG.warn("Chronicle failed to capture an environmental observation; event was not admitted", failure);
        }
    }
}
