/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.listener.PlayerActor;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
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
    private void flush(UUID playerId) {
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
                var deltas = ContainerNetDiff.between(baseline.items, container.snapshot());
                var time = clock.now();
                for (var delta : deltas) {
                    var pos = container.position(); var actor = session.actor;
                    var envelope = new EventEnvelope(container.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                            delta.eventType(), actor.type(), actor.name(), actor.uuid(), null, delta.itemId());
                    var result = destination.submit(AuditEvent.item(EventId.generate(), time, envelope, delta.payload()));
                    if (result.status() != Submission.Status.ACCEPTED) LOG.warn("Chronicle container transaction was not admitted for {}: {}", playerId, result.status());
                }
            } catch (RuntimeException failure) { LOG.warn("Chronicle failed to flush container audit session for {}", playerId, failure); }
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
