/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.causality.AuditActor;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audits storage that changes without a resolvable world menu: contents spilled by block removal and direct clicks on
 * menu-less storage. NeoForge has no post-action hooks for these paths, so a baseline is taken at the pre-action hook
 * and the net change is settled at the next server tick boundary, after vanilla has either applied or abandoned it.
 */
public final class StorageAudit {
    private static final Logger LOG = LoggerFactory.getLogger(StorageAudit.class);
    /**
     * Frames recheck their support on their own entity tick, every 101 ticks. The watch is measured in those ticks, not
     * wall-clock time, so a lagging server cannot outlast it; the wall-clock backstop only bounds frames that stop ticking.
     */
    static final int SUPPORT_CHECK_TICKS = 101 + 20;
    static final Duration SUPPORT_BACKSTOP = Duration.ofMinutes(10);
    static final int MAX_SUPPORT_WATCHES = 1024;
    private record Actor(AuditActor audit, ServerPlayer player) {
        static Actor of(Player player) { return new Actor(AuditActor.player(player), player instanceof ServerPlayer server ? server : null); }
        static Actor of(AuditActor actor) { return new Actor(actor, null); }
    }
    private record Watch(Actor actor, AbstractContainerMenu menu) {}
    /**
     * All observers of one storage share a single baseline, so every change is attributed exactly once: to the
     * remover if the storage is gone, else to the latest click, else to the earliest player whose open menu watches it.
     */
    private static final class Observation {
        final StorageHolder holder;
        Map<ContainerNetDiff.Key, ItemTransactionPayload> baseline;
        Actor remover;
        BlockPos sessionPosition;
        Actor clicker;
        AbstractContainerMenu menuBeforeClick;
        Actor supportLoss;
        int supportCheckedAt;
        Instant supportExpires;
        final List<Watch> watches = new ArrayList<>();
        Observation(StorageHolder holder, Map<ContainerNetDiff.Key, ItemTransactionPayload> baseline) {
            this.holder = holder; this.baseline = baseline;
        }
        boolean idle() { return remover == null && clicker == null && supportLoss == null && watches.isEmpty(); }
    }
    private final Map<Object, Observation> observations = new IdentityHashMap<>();
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private final ContainerSessionManager containers;
    private int supportWatches;
    private long lastSupportWarning = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    public StorageAudit(Supplier<ChronicleRuntime> runtime, ChronicleClock clock, ContainerSessionManager containers) {
        this.runtime = runtime; this.clock = clock; this.containers = containers;
        containers.beforeFlush(this::resolveRemovals);
    }
    public void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::breaking);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clickedBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clickedEntity);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clickedEntityAt);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::attacked);
        // Packets are handled between ticks (settled at Pre); explosions and block ticks happen inside one (settled at Post).
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> settle(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> settle(event.getServer()));
    }

    public void breaking(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !owned(level)) return;
        var actor = Actor.of(event.getPlayer());
        StorageHolder.spillable(level, event.getPos()).ifPresent(holder -> capture(holder, actor, true));
        watchSupport(level, event.getPos(), actor);
    }

    /** Contents of storage at {@code pos} spill if a removal that {@code actor} is about to perform is later confirmed. */
    public void removing(ServerLevel level, BlockPos pos, AuditActor actor) {
        if (!owned(level)) return;
        StorageHolder.spillable(level, pos).ifPresent(holder -> capture(holder, Actor.of(actor), true));
    }

    /** An explosion's affected frames and armor stands are destroyed by it; affected blocks spill and may drop frames they support. */
    public void exploding(ServerLevel level, List<BlockPos> blocks, List<Entity> entities, AuditActor actor) {
        if (!owned(level)) return;
        var attributed = Actor.of(actor);
        for (var pos : blocks) {
            StorageHolder.spillable(level, pos).ifPresent(holder -> capture(holder, attributed, true));
            if (!level.getBlockState(pos).isAir()) watchSupport(level, pos, attributed);
        }
        for (var entity : entities) {
            if (entity instanceof ItemFrame frame && !frame.isRemoved()) capture(StorageHolder.frame(frame), attributed, true);
            else if (entity instanceof ArmorStand stand && !stand.isRemoved()) capture(StorageHolder.stand(stand), attributed, true);
        }
    }

    /** A projectile about to hit a frame knocks its item out without a player interaction hook. */
    public void projectileHit(ItemFrame frame, AuditActor actor) {
        if (!(frame.level() instanceof ServerLevel level) || !owned(level) || frame.isRemoved()) return;
        capture(StorageHolder.frame(frame), Actor.of(actor), false);
    }

    /**
     * An armor stand is about to drop its equipment. A removal already claimed by an earlier hook for the same action
     * (an explosion's Detonate) keeps its attribution.
     */
    public void armorStandBreaking(ArmorStand stand, AuditActor actor) {
        if (!(stand.level() instanceof ServerLevel level) || !owned(level) || stand.isRemoved()) return;
        var holder = StorageHolder.stand(stand);
        var observation = observations.get(holder.identity());
        if (observation == null || observation.remover == null) capture(holder, Actor.of(actor), true);
    }

    /** The block at {@code support} is about to be removed by {@code actor}; frames hanging on it will drop later. */
    public void supportRemoving(ServerLevel level, BlockPos support, AuditActor actor) {
        if (owned(level)) watchSupport(level, support, Actor.of(actor));
    }

    private void watchSupport(ServerLevel level, BlockPos support, Actor actor) {
        try {
            var frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(support).inflate(1),
                    frame -> !frame.isRemoved() && frame.getPos().relative(frame.getDirection().getOpposite()).equals(support));
            for (var frame : frames) {
                var holder = StorageHolder.frame(frame);
                var observation = observations.get(holder.identity());
                if (observation == null) {
                    if (supportWatches >= MAX_SUPPORT_WATCHES) { supportWatchesExhausted(holder); continue; }
                    var baseline = holder.snapshot();
                    if (baseline.isEmpty()) continue;
                    observation = new Observation(holder, baseline);
                    observations.put(holder.identity(), observation);
                }
                if (observation.supportLoss == null) supportWatches++;
                // The frame drops on its own tick, long after this hook; whoever removed its support caused the drop.
                observation.supportLoss = actor;
                observation.supportCheckedAt = frame.tickCount + SUPPORT_CHECK_TICKS;
                observation.supportExpires = clock.now().plus(SUPPORT_BACKSTOP);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Chronicle failed to watch item frames supported by {}; drops will not be attributed", support, failure);
        }
    }

    private void supportWatchesExhausted(StorageHolder holder) {
        long now = System.nanoTime();
        if (now - lastSupportWarning >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
            lastSupportWarning = now;
            LOG.warn("Chronicle support-drop watch limit ({}) reached; frame at {} will not be attributed", MAX_SUPPORT_WATCHES, holder.position());
        }
    }
    public void clickedBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !owned(level)) return;
        StorageHolder.menuless(level, event.getPos()).ifPresent(holder -> capture(holder, Actor.of(event.getEntity()), false));
    }
    public void clickedEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.isCanceled() || !(event.getTarget() instanceof ItemFrame frame) || !(frame.level() instanceof ServerLevel level) || !owned(level)) return;
        capture(StorageHolder.frame(frame), Actor.of(event.getEntity()), false);
    }
    /** Armor stand equipment is put, taken or swapped in interactAt, which only this event precedes. */
    public void clickedEntityAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.isCanceled() || !(event.getTarget() instanceof ArmorStand stand) || !(stand.level() instanceof ServerLevel level) || !owned(level)) return;
        capture(StorageHolder.stand(stand), Actor.of(event.getEntity()), false);
    }
    /** A creative player's attack kills an armor stand without dropping (or passing through brokenByAnything). */
    public void attacked(AttackEntityEvent event) {
        if (event.isCanceled() || !(event.getTarget().level() instanceof ServerLevel level) || !owned(level)) return;
        if (event.getTarget() instanceof ItemFrame frame) capture(StorageHolder.frame(frame), Actor.of(event.getEntity()), false);
        else if (event.getTarget() instanceof ArmorStand stand && !stand.isRemoved()) capture(StorageHolder.stand(stand), Actor.of(event.getEntity()), false);
    }

    /** Item rows recorded for players' container sessions at a position are reported to {@code listener}. */
    public void onRecorded(ContainerSessionManager.DepositListener listener) { containers.onRecorded(listener); }

    /**
     * Automation is about to change the storage at {@code pos}: viewers are credited with their changes so far; once it
     * has acted ({@code record} false) they are rebaselined, so the automation's change is not attributed to them.
     */
    public void automating(ServerLevel level, BlockPos pos, boolean record) {
        if (owned(level)) containers.checkpoint(level, pos, record);
    }

    private boolean owned(ServerLevel level) { return level.getServer().isSameThread() && runtime.get() != null; }

    private void capture(StorageHolder holder, Actor actor, boolean removal) {
        try {
            var observation = observations.get(holder.identity());
            // Credit earlier observers with changes so far; the new action starts from the current state.
            if (observation != null && !settle(observation)) observation = null;
            BlockPos sessionPosition = null;
            if (removal && holder instanceof StorageHolder.Block block) {
                sessionPosition = WorldContainer.canonicalPosition(block.level(), block.position());
                containers.checkpoint(block.level(), sessionPosition, true);
            }
            if (observation == null) {
                observation = new Observation(holder, holder.snapshot());
                if (removal && observation.baseline.isEmpty()) return;
                observations.put(holder.identity(), observation);
            }
            if (removal) { observation.remover = actor; observation.sessionPosition = sessionPosition; }
            else {
                observation.clicker = actor;
                observation.menuBeforeClick = actor.player() == null ? null : actor.player().containerMenu;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Chronicle failed to capture {} storage at {}; no item rows recorded", holder.storageId(), holder.position(), failure);
        }
    }

    /** A viewer's session must not flush emptied contents that a confirmed removal is about to record as a spill. */
    private void resolveRemovals(ServerLevel level, BlockPos sessionPosition) {
        for (var observation : List.copyOf(observations.values())) {
            if (observation.remover == null || observation.holder.level() != level || !sessionPosition.equals(observation.sessionPosition)
                    || observation.holder.presence() != StorageHolder.Presence.DESTROYED) continue;
            settleOrDrop(observation);
        }
    }

    private void settle(MinecraftServer server) {
        if (observations.isEmpty() || !server.isSameThread()) return;
        for (var observation : List.copyOf(observations.values())) settleOrDrop(observation);
        supportWatches = (int)observations.values().stream().filter(observation -> observation.supportLoss != null).count();
    }

    private void settleOrDrop(Observation observation) {
        boolean keep;
        try { keep = settle(observation); }
        catch (RuntimeException failure) {
            LOG.warn("Chronicle failed to settle {} storage at {}", observation.holder.storageId(), observation.holder.position(), failure);
            keep = false;
        }
        if (!keep) observations.remove(observation.holder.identity(), observation);
    }

    /** Returns whether the observation stays open because a menu opened by an interaction still watches the storage. */
    private boolean settle(Observation observation) {
        var holder = observation.holder;
        var presence = holder.presence();
        if (presence == StorageHolder.Presence.UNKNOWN) {
            LOG.warn("Chronicle cannot confirm {} storage at {} (unloaded or relocated); no item rows recorded", holder.storageId(), holder.position());
            return false;
        }
        if (presence == StorageHolder.Presence.DESTROYED) {
            var actor = observation.remover != null ? observation.remover
                    : observation.supportLoss != null ? observation.supportLoss : attributable(observation);
            if (actor != null) submit(holder, actor, ContainerNetDiff.between(observation.baseline, Map.of()));
            if (observation.sessionPosition != null) containers.checkpoint(holder.level(), observation.sessionPosition, false);
            return false;
        }
        // BreakEvent and Detonate precede removal; a break that was later refused or a non-destructive explosion spills nothing.
        observation.remover = null;
        observation.sessionPosition = null;
        if (observation.supportLoss != null && holder instanceof StorageHolder.Frame frame) {
            // Past its support check the frame survived (support restored or it is fixed); only the backstop is a loss.
            boolean checked = frame.entity().tickCount >= observation.supportCheckedAt;
            if (checked || !clock.now().isBefore(observation.supportExpires)) {
                if (!checked && !frame.entity().survives())
                    LOG.warn("Chronicle stopped watching unsupported {} at {} after {} without a support check; a later drop will not be attributed",
                            holder.storageId(), holder.position(), SUPPORT_BACKSTOP);
                observation.supportLoss = null;
                observation.supportExpires = null;
            }
        }
        var current = holder.snapshot();
        var actor = attributable(observation);
        if (actor != null) submit(holder, actor, ContainerNetDiff.between(observation.baseline, current));
        observation.baseline = current;
        var clicker = observation.clicker;
        if (clicker != null && clicker.player() != null) {
            // Lectern books are taken through LecternMenu, whose container is not a world BlockEntity and has no take hook.
            var menu = clicker.player().containerMenu;
            if (menu != clicker.player().inventoryMenu && menu != observation.menuBeforeClick
                    && observation.watches.stream().noneMatch(watch -> watch.menu() == menu))
                observation.watches.add(new Watch(clicker, menu));
        }
        observation.clicker = null;
        observation.menuBeforeClick = null;
        observation.watches.removeIf(watch -> watch.actor().player().isRemoved() || watch.actor().player().containerMenu != watch.menu());
        return !observation.idle();
    }

    private static Actor attributable(Observation observation) {
        if (observation.clicker != null) return observation.clicker;
        return observation.watches.isEmpty() ? null : observation.watches.getFirst().actor();
    }

    private void submit(StorageHolder holder, Actor actor, List<ContainerNetDiff.Delta> deltas) {
        if (deltas.isEmpty()) return;
        var destination = runtime.get();
        if (destination == null) {
            LOG.warn("Chronicle dropped {} item rows at {}: runtime unavailable", deltas.size(), holder.position());
            return;
        }
        var audit = actor.audit();
        ContainerSessionManager.submit(destination, holder.level(), holder.position(), audit.type(), audit.id(),
                audit.uuid(), audit.rootUuid(), audit.rootName(), deltas, clock.now());
    }

    public void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Storage audit shutdown off server thread");
        for (var observation : List.copyOf(observations.values())) {
            try { settle(observation); }
            catch (RuntimeException failure) { LOG.warn("Chronicle failed to settle {} storage at shutdown", observation.holder.storageId(), failure); }
        }
        observations.clear();
        supportWatches = 0;
    }
    public int pendingObservations(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Storage audit accessed off server thread");
        return observations.size();
    }
}
