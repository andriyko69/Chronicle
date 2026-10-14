/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.listener.PlayerActor;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
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
    private record Actor(String type, String name, UUID uuid, UUID root, ServerPlayer player) {
        static Actor of(Player player) {
            var actor = PlayerActor.capture(player);
            return new Actor(actor.type(), actor.name(), actor.uuid(), null, player instanceof ServerPlayer server ? server : null);
        }
        static Actor explosion(Entity direct, Entity indirect) {
            String name = "#explosion";
            if (direct != null) {
                var type = BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType());
                name = "#" + (type.getNamespace().equals("minecraft") ? type.getPath() : type.toString());
            }
            // The igniting player is the root cause, not the actor: the explosion, not the player, removed the storage.
            return new Actor("ENVIRONMENT", name, null, indirect instanceof Player player ? player.getUUID() : null, null);
        }
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
        final List<Watch> watches = new ArrayList<>();
        Observation(StorageHolder holder, Map<ContainerNetDiff.Key, ItemTransactionPayload> baseline) {
            this.holder = holder; this.baseline = baseline;
        }
        boolean idle() { return remover == null && clicker == null && watches.isEmpty(); }
    }
    private final Map<Object, Observation> observations = new IdentityHashMap<>();
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private final ContainerSessionManager containers;
    public StorageAudit(Supplier<ChronicleRuntime> runtime, ChronicleClock clock, ContainerSessionManager containers) {
        this.runtime = runtime; this.clock = clock; this.containers = containers;
        containers.beforeFlush(this::resolveRemovals);
    }
    public void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::breaking);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::detonating);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clickedBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::clickedEntity);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::attacked);
        // Packets are handled between ticks (settled at Pre); explosions and block ticks happen inside one (settled at Post).
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> settle(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> settle(event.getServer()));
    }

    public void breaking(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !owned(level)) return;
        StorageHolder.spillable(level, event.getPos()).ifPresent(holder -> capture(holder, Actor.of(event.getPlayer()), true));
    }
    public void detonating(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !owned(level)) return;
        var explosion = event.getExplosion();
        var actor = Actor.explosion(explosion.getDirectSourceEntity(), explosion.getIndirectSourceEntity());
        for (var pos : event.getAffectedBlocks()) StorageHolder.spillable(level, pos).ifPresent(holder -> capture(holder, actor, true));
        for (var entity : event.getAffectedEntities()) {
            if (entity instanceof ItemFrame frame && !frame.isRemoved()) capture(StorageHolder.frame(frame), actor, true);
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
    public void attacked(AttackEntityEvent event) {
        if (event.isCanceled() || !(event.getTarget() instanceof ItemFrame frame) || !(frame.level() instanceof ServerLevel level) || !owned(level)) return;
        capture(StorageHolder.frame(frame), Actor.of(event.getEntity()), false);
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
            var actor = observation.remover != null ? observation.remover : attributable(observation);
            if (actor != null) submit(holder, actor, ContainerNetDiff.between(observation.baseline, Map.of()));
            if (observation.sessionPosition != null) containers.checkpoint(holder.level(), observation.sessionPosition, false);
            return false;
        }
        // BreakEvent and Detonate precede removal; a break that was later refused or a non-destructive explosion spills nothing.
        observation.remover = null;
        observation.sessionPosition = null;
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
        ContainerSessionManager.submit(destination, holder.level(), holder.position(), actor.type(), actor.name(),
                actor.uuid(), actor.root(), deltas, clock.now());
    }

    public void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Storage audit shutdown off server thread");
        for (var observation : List.copyOf(observations.values())) {
            try { settle(observation); }
            catch (RuntimeException failure) { LOG.warn("Chronicle failed to settle {} storage at shutdown", observation.holder.storageId(), failure); }
        }
        observations.clear();
    }
    public int pendingObservations(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Storage audit accessed off server thread");
        return observations.size();
    }
}
