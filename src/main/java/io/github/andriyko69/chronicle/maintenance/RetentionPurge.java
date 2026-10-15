/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.maintenance;

import io.github.andriyko69.chronicle.config.ChronicleServerConfig;
import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup retention purge configured by {@code autoPurgeRetentionDays}: once per runtime, after its journal replay
 * has drained, and only while no rollback, restore or manual purge holds the shared job slot. A purge that could not
 * settle in time is retried with backoff rather than abandoned for the session. All methods run on the server thread.
 */
public final class RetentionPurge {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionPurge.class);
    public static final int FIRST_RETRY_TICKS = 100;
    static final int MAX_RETRY_TICKS = 6000;

    /** The runtime operations retention needs. */
    public interface Target {
        ChronicleHealth health();
        /** Journal records not yet stored; nonzero while a restart replays its backlog. */
        long backlog();
        CompletionStage<ChronicleRuntime.PurgeResult> purge(Instant cutoff, int batchSize, boolean optimize);
    }
    /** The job slot shared with rollbacks and manual purges. */
    public interface Slot {
        /** Null while another job holds it. */
        Object reserve();
        void release(Object token);
    }

    private final Supplier<? extends Target> runtime;
    private final ChronicleClock clock;
    private Target owed;
    private Slot slot;
    private Executor owner;
    private int waitTicks;
    private int retryTicks = FIRST_RETRY_TICKS;

    public RetentionPurge(Supplier<? extends Target> runtime, ChronicleClock clock) { this.runtime = runtime; this.clock = clock; }

    /** {@code owner} must run completions back on the server thread. */
    public void started(Target started, Slot slot, Executor owner) {
        owed = started; this.slot = slot; this.owner = owner; waitTicks = 0; retryTicks = FIRST_RETRY_TICKS;
    }

    public void tick() {
        var active = owed;
        if (active == null) return;
        if (runtime.get() != active) { owed = null; return; }
        if (waitTicks > 0) { waitTicks--; return; }
        var health = active.health();
        if (health == ChronicleHealth.STARTING || health == ChronicleHealth.STORAGE_UNAVAILABLE) return;
        var config = ChronicleServerConfig.current();
        if (health != ChronicleHealth.HEALTHY || config.autoPurgeRetentionDays() == 0) { owed = null; return; }
        // Runtime readiness precedes replay; purging now would only wait on the barrier and could time out.
        if (active.backlog() > 0) return;
        Object token = slot.reserve();
        if (token == null) return;
        owed = null;
        var jobSlot = slot;
        var jobOwner = owner;
        int days = config.autoPurgeRetentionDays();
        LOG.info("Chronicle automatic retention purge of events older than {} days started", days);
        active.purge(clock.now().minus(Duration.ofDays(days)), config.purgeBatchSize(), false).whenComplete((result, failure) -> jobOwner.execute(() -> {
            jobSlot.release(token);
            if (failure == null) { LOG.info("Chronicle automatic retention purge complete: {}", result); return; }
            if (retryable(failure) && runtime.get() == active && slot == jobSlot) {
                owed = active;
                waitTicks = retryTicks;
                retryTicks = Math.min(retryTicks * 2, MAX_RETRY_TICKS);
                LOG.warn("Chronicle automatic retention purge deferred; retrying in {} ticks: {}", waitTicks, failure.getMessage());
            } else LOG.warn("Chronicle automatic retention purge stopped", failure);
        }));
    }

    public void stop() { owed = null; slot = null; owner = null; }

    private static boolean retryable(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof StorageFailure storage && storage.kind() == StorageFailure.Kind.TRANSIENT;
    }
}
