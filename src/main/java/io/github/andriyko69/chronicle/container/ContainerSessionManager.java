/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.listener.PlayerActor;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContainerSessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(ContainerSessionManager.class);
    private record Baseline(WorldContainer container, Map<ContainerNetDiff.Key, ItemTransactionPayload> items) {}
    private record Session(AbstractContainerMenu menu, PlayerActor actor, List<Baseline> baselines) {}
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private java.util.function.BiConsumer<ServerLevel, BlockPos> beforeFlush = (level, position) -> {};
    public ContainerSessionManager(Supplier<ChronicleRuntime> runtime, ChronicleClock clock) {
        this.runtime = runtime; this.clock = clock;
    }
    public void register() {
        NeoForge.EVENT_BUS.addListener(this::opened);
        NeoForge.EVENT_BUS.addListener(this::closed);
        NeoForge.EVENT_BUS.addListener(this::loggedOut);
        NeoForge.EVENT_BUS.addListener(this::changedDimension);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::died);
    }
    public void opened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.serverLevel().getServer().isSameThread()) return;
        flush(player.getUUID());
        if (runtime.get() == null) return;
        try {
            var baselines = new ArrayList<Baseline>();
            for (var container : WorldContainer.resolve(player, event.getContainer())) baselines.add(new Baseline(container, container.snapshot()));
            if (!baselines.isEmpty()) sessions.put(player.getUUID(), new Session(event.getContainer(), PlayerActor.capture(player), List.copyOf(baselines)));
        } catch (RuntimeException failure) { LOG.warn("Chronicle failed to open container audit session for {}", player.getUUID(), failure); }
    }
    public void closed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.serverLevel().getServer().isSameThread()) return;
        var session = sessions.get(player.getUUID());
        // An old Close notification must not consume a replacement menu, even if its numeric ID is reused.
        if (session != null && session.menu == event.getContainer())
            flush(player.getUUID());
    }
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { finish(event.getEntity()); }
    public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { finish(event.getEntity()); }
    public void died(LivingDeathEvent event) { if (!event.isCanceled()) finish(event.getEntity()); }
    private void finish(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof ServerPlayer player && player.serverLevel().getServer().isSameThread()) flush(player.getUUID());
    }
    public void beforeFlush(java.util.function.BiConsumer<ServerLevel, BlockPos> resolver) { beforeFlush = Objects.requireNonNull(resolver); }
    private void flush(UUID playerId) {
        var pending = sessions.get(playerId);
        if (pending == null) return;
        // A viewer can close a menu (e.g. invalid after an explosion) before the removal is settled at the tick boundary.
        for (var baseline : pending.baselines) {
            try { beforeFlush.accept(baseline.container.level(), baseline.container.position()); }
            catch (RuntimeException failure) { LOG.warn("Chronicle failed to resolve storage removal before flushing {}", playerId, failure); }
        }
        var session = sessions.remove(playerId);
        if (session == null) return;
        var destination = runtime.get();
        if (destination == null) {
            LOG.warn("Chronicle could not flush container session for {}: runtime unavailable", playerId);
            return;
        }
        for (var baseline : session.baselines) {
            try {
                var container = baseline.container;
                var actor = session.actor;
                submit(destination, container.level(), container.position(), actor.type(), actor.name(), actor.uuid(), null, null,
                        ContainerNetDiff.between(baseline.items, container.snapshot()), clock.now());
            } catch (RuntimeException failure) { LOG.warn("Chronicle failed to flush container audit session for {}", playerId, failure); }
        }
    }
    static void submit(ChronicleRuntime destination, ServerLevel level, BlockPos pos, String actorType, String actorName,
            UUID actorUuid, UUID rootActorUuid, String rootActorId, List<ContainerNetDiff.Delta> deltas, Instant time) {
        for (var delta : deltas) {
            var envelope = new EventEnvelope(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                    delta.eventType(), actorType, actorName, actorUuid, rootActorUuid, rootActorId, delta.itemId());
            var result = destination.submit(AuditEvent.item(EventId.generate(), time, envelope, delta.payload()));
            if (result.status() != Submission.Status.ACCEPTED)
                LOG.warn("Chronicle item transaction at {} was not admitted for {}: {}", pos, actorName, result.status());
        }
    }
    /**
     * Removal audit owns contents that leave with a broken block. Before removal, viewers are credited with their changes
     * so far ({@code record}); after confirmed removal they are rebaselined without rows, so a viewer is not blamed for the spill.
     */
    public void checkpoint(ServerLevel level, BlockPos position, boolean record) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Container checkpoint off server thread");
        var destination = runtime.get();
        for (var entry : sessions.entrySet()) {
            var session = entry.getValue();
            var baselines = new ArrayList<Baseline>(session.baselines.size());
            boolean touched = false;
            for (var baseline : session.baselines) {
                var container = baseline.container;
                if (container.level() != level || !container.position().equals(position)) { baselines.add(baseline); continue; }
                touched = true;
                try {
                    var current = container.snapshot();
                    if (record && destination != null) submit(destination, level, position, session.actor.type(), session.actor.name(),
                            session.actor.uuid(), null, null, ContainerNetDiff.between(baseline.items, current), clock.now());
                    baselines.add(new Baseline(container, current));
                } catch (RuntimeException failure) {
                    LOG.warn("Chronicle failed to checkpoint container audit session for {}", entry.getKey(), failure);
                    baselines.add(baseline);
                }
            }
            if (touched) entry.setValue(new Session(session.menu, session.actor, List.copyOf(baselines)));
        }
    }
    public void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Container shutdown off server thread");
        for (UUID player : List.copyOf(sessions.keySet())) flush(player);
        sessions.clear();
    }
    public int activeSessions(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Container sessions accessed off server thread");
        return sessions.size();
    }
}
