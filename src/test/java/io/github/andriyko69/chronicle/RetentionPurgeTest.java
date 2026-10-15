/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.config.ChronicleServerConfig;
import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.maintenance.RetentionPurge;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetentionPurgeTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ChronicleRuntime.PurgeResult DONE = new ChronicleRuntime.PurgeResult(5, 1, false, false, 3);

    private static final class FakeRuntime implements RetentionPurge.Target {
        ChronicleHealth health = ChronicleHealth.STARTING;
        long backlog;
        final List<Instant> cutoffs = new ArrayList<>();
        final List<Integer> batches = new ArrayList<>();
        final Deque<CompletableFuture<ChronicleRuntime.PurgeResult>> pending = new ArrayDeque<>();
        public ChronicleHealth health() { return health; }
        public long backlog() { return backlog; }
        public CompletionStage<ChronicleRuntime.PurgeResult> purge(Instant cutoff, int batchSize, boolean optimize) {
            assertFalse(optimize, "Startup retention never blocks the writer on a WAL checkpoint");
            cutoffs.add(cutoff); batches.add(batchSize);
            var future = new CompletableFuture<ChronicleRuntime.PurgeResult>();
            pending.add(future);
            return future;
        }
    }
    private static final class FakeSlot implements RetentionPurge.Slot {
        Object holder;
        boolean busy;
        public Object reserve() {
            if (busy || holder != null) return null;
            holder = new Object();
            return holder;
        }
        public void release(Object token) { if (token == holder) holder = null; }
    }

    private FakeRuntime runtime;
    private FakeSlot slot;
    private Object current;
    private RetentionPurge retention;

    @BeforeEach void setUp() {
        ChronicleServerConfig.install(new ChronicleServerConfig.Snapshot(200, 250, 8, 2_000, 30, Set.of()));
        runtime = new FakeRuntime(); slot = new FakeSlot(); current = runtime;
        retention = new RetentionPurge(() -> (RetentionPurge.Target) current, ChronicleClock.fixed(NOW));
        retention.started(runtime, slot, Runnable::run);
    }
    @AfterEach void restore() { ChronicleServerConfig.reset(); }

    private void ticks(int count) { for (int i = 0; i < count; i++) retention.tick(); }

    @Test void waitsForReadinessAndReplayThenPurgesOnce() {
        ticks(5);
        runtime.health = ChronicleHealth.STORAGE_UNAVAILABLE; ticks(5);
        runtime.health = ChronicleHealth.HEALTHY; runtime.backlog = 1_000; ticks(5);
        assertTrue(runtime.cutoffs.isEmpty(), "Nothing starts before readiness and replay");
        runtime.backlog = 0; ticks(1);
        assertEquals(List.of(NOW.minus(Duration.ofDays(30))), runtime.cutoffs);
        assertEquals(List.of(2_000), runtime.batches);
        assertNotNull(slot.holder, "The purge holds the shared slot");
        runtime.pending.removeFirst().complete(DONE);
        assertNull(slot.holder, "Released on completion");
        ticks(10_000);
        assertEquals(1, runtime.cutoffs.size(), "Once per start");
    }

    @Test void anUnsettledPurgeIsRetriedWithBackoffInsteadOfAbandoned() {
        runtime.health = ChronicleHealth.HEALTHY;
        ticks(1);
        runtime.pending.removeFirst().completeExceptionally(new StorageFailure(StorageFailure.Kind.TRANSIENT,
                "Recent events are not yet persisted; try again shortly", "NOT_SETTLED", null));
        assertNull(slot.holder);
        ticks(RetentionPurge.FIRST_RETRY_TICKS);
        assertEquals(1, runtime.cutoffs.size(), "Backs off before retrying");
        ticks(1);
        assertEquals(2, runtime.cutoffs.size(), "Retried after the first backoff");
        runtime.pending.removeFirst().completeExceptionally(new java.util.concurrent.CompletionException(
                new StorageFailure(StorageFailure.Kind.TRANSIENT, "busy", "SQLITE_BUSY", null)));
        ticks(2 * RetentionPurge.FIRST_RETRY_TICKS);
        assertEquals(2, runtime.cutoffs.size(), "The backoff doubles");
        ticks(1);
        assertEquals(3, runtime.cutoffs.size());
        runtime.pending.removeFirst().complete(DONE);
        ticks(20_000);
        assertEquals(3, runtime.cutoffs.size(), "Success ends the retries");
    }

    @Test void waitsForAFreeSlotAndStopsOnPermanentFailures() {
        runtime.health = ChronicleHealth.HEALTHY;
        slot.busy = true; ticks(50);
        assertTrue(runtime.cutoffs.isEmpty(), "A running rollback or purge holds the slot");
        slot.busy = false; ticks(1);
        assertEquals(1, runtime.cutoffs.size());
        runtime.pending.removeFirst().completeExceptionally(new RejectedExecutionException("Chronicle storage unavailable"));
        assertNull(slot.holder);
        ticks(20_000);
        assertEquals(1, runtime.cutoffs.size(), "Only an unsettled barrier or busy database is retried");
    }

    @Test void disabledUnhealthyOrReplacedRuntimesAreNeverPurged() {
        ChronicleServerConfig.install(new ChronicleServerConfig.Snapshot(200, 250, 8, 2_000, 0, Set.of()));
        runtime.health = ChronicleHealth.HEALTHY; ticks(5);
        ChronicleServerConfig.install(new ChronicleServerConfig.Snapshot(200, 250, 8, 2_000, 30, Set.of()));
        ticks(5);
        assertTrue(runtime.cutoffs.isEmpty(), "Disabled at readiness means not this start");

        retention.started(runtime, slot, Runnable::run);
        runtime.health = ChronicleHealth.FATAL_STORAGE; ticks(5);
        runtime.health = ChronicleHealth.HEALTHY; ticks(5);
        assertTrue(runtime.cutoffs.isEmpty(), "A failed runtime is not purged");

        retention.started(runtime, slot, Runnable::run);
        current = new FakeRuntime(); ticks(5);
        assertTrue(runtime.cutoffs.isEmpty(), "A replaced runtime is not purged");

        current = runtime;
        retention.started(runtime, slot, Runnable::run);
        retention.stop(); ticks(5);
        assertTrue(runtime.cutoffs.isEmpty(), "Stopped");
    }
}
