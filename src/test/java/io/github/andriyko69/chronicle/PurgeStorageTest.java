/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.LookupParser;
import io.github.andriyko69.chronicle.rollback.RollbackPlan;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.sqlite.SQLiteStorageBackend;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PurgeStorageTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.now();
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    /** A whole second, two hours back: rows at it or later survive, rows in earlier seconds are purged. */
    private static final Instant CUTOFF = Instant.ofEpochSecond(NOW.getEpochSecond() - 7200);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CAROL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DAVE = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static AuditEvent place(int x, String actor, UUID uuid, Instant at) {
        return RollbackStorageTest.block(x, "BLOCK_PLACE", actor, uuid, at, "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
    }
    /** An explosion row: the actor is the TNT, the root is the player who lit it. */
    private static AuditEvent rooted(int x, String rootName, UUID root, Instant at) {
        var stone = new BlockStateSnapshot("minecraft:stone", Map.of());
        return AuditEvent.block(EventId.generate(), at, new EventEnvelope("minecraft:overworld", x, 64, 0, "BLOCK_BREAK", "ENVIRONMENT", "#tnt",
                null, root, rootName, "minecraft:stone"), new BlockDeltaPayload(stone, BlockStateSnapshot.AIR,
                BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
    }
    private static List<AuditEvent> old(int count, String actor, UUID uuid) {
        var events = new ArrayList<AuditEvent>(count);
        for (int i = 0; i < count; i++) events.add(place(10_000 + i, actor, uuid, CUTOFF.minusSeconds(1 + i % 600).plusNanos(i)));
        return events;
    }
    private static ChronicleRuntime start(InfrastructureConfig config) throws Exception {
        var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test");
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return runtime;
    }
    private static void persist(ChronicleRuntime runtime, List<AuditEvent> events) throws Exception {
        long before = runtime.diagnostics().persisted();
        for (var event : events) PersistenceTest.submit(runtime, event);
        PersistenceTest.await(() -> runtime.diagnostics().persisted() == before + events.size());
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(60, TimeUnit.SECONDS); }
    private static long rows(InfrastructureConfig config) throws Exception { return PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events"); }
    private static Set<String> profiles(InfrastructureConfig config) throws Exception {
        var names = new TreeSet<String>();
        try (var connection = PersistenceTest.connection(config.database()); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT actor_id FROM actor_profiles")) {
            while (result.next()) names.add(result.getString(1));
        }
        return names;
    }
    private static RollbackPlan plan(ChronicleRuntime runtime, String query) throws Exception {
        var spec = new LookupParser(CLOCK).parseRollback(query, LookupParser.Context.console()).spec();
        return await(runtime.planRollback(spec, false));
    }

    @Test void writerDeletesInBoundedSlicesUntilNothingOlderRemains() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var recent = List.of(place(1, "Bob", BOB, CUTOFF), place(2, "Bob", BOB, NOW.minusSeconds(5)), place(3, "Bob", BOB, CUTOFF.plusNanos(1)));
        try (var runtime = start(config)) {
            var events = new ArrayList<>(old(11_000, "Alice", ALICE));
            events.addAll(recent);
            persist(runtime, events);
        }
        String journal;
        try (var connection = PersistenceTest.connection(config.database()); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT journal_id FROM journal_checkpoint")) {
            assertTrue(result.next()); journal = result.getString(1);
        }
        try (var writer = new SQLiteStorageBackend(config, CLOCK, "test").openWriter(UUID.fromString(journal))) {
            var slices = new ArrayList<Integer>();
            int deleted;
            do { deleted = writer.purgeEvents(CUTOFF.getEpochSecond(), 5_000); slices.add(deleted); } while (deleted > 0);
            assertEquals(List.of(5_000, 5_000, 1_000, 0), slices);
        }
        assertEquals(3, rows(config), "Rows in the cutoff second itself and later survive");
    }

    @Test void previewCountsWithoutDeletingAndPurgeRemovesOldRowsAndOrphanedProfiles() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var events = new ArrayList<>(old(250, "Alice", ALICE));
        events.addAll(old(30, "Bob", BOB));
        events.add(rooted(1, "Dave", DAVE, CUTOFF.minusSeconds(30)));
        var bobRecent = place(2, "Bob", BOB, NOW.minusSeconds(60));
        var carolRecent = rooted(3, "Carol", CAROL, NOW.minusSeconds(30));
        events.add(bobRecent); events.add(carolRecent);
        try (var runtime = start(config)) {
            persist(runtime, events);
            assertEquals(Set.of("Alice", "Bob", "Carol", "Dave"), profiles(config));
            assertEquals(281, await(runtime.purgeable(CUTOFF)));
            assertEquals(283, rows(config), "Preview deletes nothing");
            var result = await(runtime.purge(CUTOFF, 100, true));
            assertEquals(281, result.events());
            assertEquals(2, result.profiles(), "Alice and Dave are no longer named by any row");
            assertTrue(result.optimized());
            assertTrue(result.elapsedMillis() >= 0);
            assertEquals(2, rows(config));
            assertEquals(Set.of("Bob", "Carol"), profiles(config), "Profiles of surviving actors and roots stay resolvable");
            assertEquals(0, await(runtime.purgeable(CUTOFF)));
            assertEquals(Set.of(bobRecent.id(), carolRecent.id()), Set.copyOf(plan(runtime, "r:global t:1d").steps().stream()
                    .map(step -> step.event().id()).toList()), "Surviving rows still roll back");
            var again = await(runtime.purge(CUTOFF, 100, false));
            assertEquals(0, again.events()); assertEquals(0, again.profiles()); assertFalse(again.optimized());
            assertEquals(ChronicleHealth.HEALTHY, runtime.health());
        }
    }

    @Test void aBreakInTheCutoffSecondKeepsItsSpillRows() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var present = BlockDeltaPayload.SnapshotStatus.PRESENT;
        var boundaryBreak = RollbackStorageTest.block(1, "BLOCK_BREAK", "Alice", ALICE, CUTOFF, "minecraft:chest", present);
        var boundarySpills = List.of(
                RollbackStorageTest.item(1, "ITEM_TAKE", "Alice", ALICE, CUTOFF.plusMillis(50), "minecraft:diamond", "minecraft:chest"),
                RollbackStorageTest.item(1, "ITEM_TAKE", "Alice", ALICE, CUTOFF.plusMillis(50), "minecraft:emerald", "minecraft:chest"));
        var olderBreak = RollbackStorageTest.block(2, "BLOCK_BREAK", "Alice", ALICE, CUTOFF.minusSeconds(1), "minecraft:chest", present);
        var olderSpill = RollbackStorageTest.item(2, "ITEM_TAKE", "Alice", ALICE, CUTOFF.minusSeconds(1).plusMillis(50), "minecraft:gold_ingot", "minecraft:chest");
        try (var runtime = start(config)) {
            var events = new ArrayList<AuditEvent>(List.of(boundaryBreak, olderBreak, olderSpill));
            events.addAll(boundarySpills);
            persist(runtime, events);
            assertEquals(2, await(runtime.purge(CUTOFF, 100, false)).events(), "The older break and its spill go together");
            var steps = plan(runtime, "r:global t:1d").steps();
            assertEquals(List.of(boundaryBreak.id()), steps.stream().map(step -> step.event().id()).toList());
            assertEquals(Set.copyOf(boundarySpills.stream().map(AuditEvent::id).toList()),
                    Set.copyOf(steps.getFirst().companions().stream().map(AuditEvent::id).toList()), "The surviving break keeps both spills");
        }
    }

    @Test void eventsAdmittedBeforeThePurgeAreSettledFirst() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = start(config)) {
            var late = old(1_000, "Alice", ALICE);
            // Neither call waits for persistence: each must still see every row admitted before it.
            for (var event : late.subList(0, 500)) PersistenceTest.submit(runtime, event);
            assertEquals(500, await(runtime.purgeable(CUTOFF)));
            for (var event : late.subList(500, 1_000)) PersistenceTest.submit(runtime, event);
            assertEquals(1_000, await(runtime.purge(CUTOFF, 100, false)).events());
            assertEquals(0, rows(config));
        }
    }

    @Test void livePersistenceKeepsCommittingWhileAPurgeRuns() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = start(config)) {
            persist(runtime, old(8_000, "Alice", ALICE));
            long before = runtime.diagnostics().persisted();
            int live = 3_000;
            var submitted = new AtomicInteger();
            var pool = Executors.newSingleThreadExecutor();
            try {
                var producer = pool.submit(() -> {
                    for (int i = 0; i < live; i++) {
                        PersistenceTest.submit(runtime, place(i, "Bob", BOB, NOW.minusMillis(i)));
                        submitted.incrementAndGet();
                        if (i % 100 == 0) Thread.sleep(1);
                    }
                    return null;
                });
                PersistenceTest.await(() -> submitted.get() > 0);
                var result = await(runtime.purge(CUTOFF, 100, true));
                long liveDuringPurge = runtime.diagnostics().persisted() - before;
                producer.get(60, TimeUnit.SECONDS);
                assertEquals(8_000, result.events());
                assertTrue(liveDuringPurge > 0, "Live batches commit between purge steps");
            } finally { pool.shutdownNow(); }
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == before + live);
            var metrics = runtime.diagnostics();
            assertEquals(0, metrics.persistenceFailures(), "The writer never contends with itself: " + metrics);
            assertEquals(0, metrics.retries());
            assertEquals(ChronicleHealth.HEALTHY, metrics.health());
            assertEquals(live, rows(config));
        }
    }

    @Test void aPurgeQueuedAtShutdownFailsInsteadOfReportingSuccess() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var runtime = start(config);
        persist(runtime, old(20_000, "Alice", ALICE));
        var purge = runtime.purge(CUTOFF, 100, false).toCompletableFuture();
        runtime.close();
        try { assertEquals(20_000, purge.get(10, TimeUnit.SECONDS).events(), "Only a finished purge reports success"); }
        catch (ExecutionException stopped) { assertInstanceOf(CancellationException.class, stopped.getCause()); }
        assertTrue(runtime.purge(CUTOFF, 100, false).toCompletableFuture().isCompletedExceptionally(), "No purge is accepted after close");
    }
}
