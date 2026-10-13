/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.command.ChronicleFormatter;
import io.github.andriyko69.chronicle.diagnostics.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.*;
import io.github.andriyko69.chronicle.storage.sqlite.SQLiteStorageBackend;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LookupIntegrationTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00.123456789Z");
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final LookupParser.Context CONSOLE = LookupParser.Context.console();
    private static AuditEvent event(int i) {
        var status = BlockDeltaPayload.SnapshotStatus.values()[i % 4];
        var envelope = new EventEnvelope(i % 3 == 0 ? "minecraft:the_nether" : "minecraft:overworld", i - 10, 64 + i % 3, -i,
                i % 2 == 0 ? "BLOCK_BREAK" : "BLOCK_PLACE", i % 4 == 0 ? "FAKE_PLAYER" : "PLAYER", i % 4 == 0 ? "Bot" : "Alice",
                i % 4 == 0 ? BOT : ALICE, null, i % 2 == 0 ? "minecraft:chest" : "minecraft:diamond_ore");
        var target = new BlockStateSnapshot(envelope.targetId(), Map.of("test", "value"));
        return AuditEvent.block(new EventId(new UUID(0x12347000, 0x8000000000000000L | i)),
                i < 18 ? NOW.minusSeconds(i % 5) : NOW.minusSeconds(10000), envelope,
                new BlockDeltaPayload(i % 2 == 0 ? target : BlockStateSnapshot.AIR, i % 2 == 0 ? BlockStateSnapshot.AIR : target,
                        BlockDeltaPayload.Observation.HOOK_OBSERVATION, status, status == BlockDeltaPayload.SnapshotStatus.PRESENT ? new byte[]{1,2,3} : null));
    }
    private static List<EventId> ids(LookupPage page) { return page.rows().stream().map(LookupRow::id).toList(); }
    private static LookupPage lookup(ChronicleRuntime runtime, String query, int page) throws Exception {
        return runtime.lookup(new LookupParser(CLOCK).parse(query, CONSOLE), page).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    @Test void realStorageFiltersOrderingPageBoundariesDetachedStatesAndLifetimeTotals() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var expected = new ArrayList<AuditEvent>(); for (int i = 0; i < 24; i++) expected.add(event(i));
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (var event : expected) PersistenceTest.submit(runtime, event);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 24);
            var sorted = expected.stream().filter(e -> !e.occurredAt().isBefore(NOW.minusSeconds(3600)))
                    .sorted(Comparator.comparing(AuditEvent::occurredAt).thenComparing(AuditEvent::id).reversed()).toList();
            var first = lookup(runtime, "r:global t:1h", 1); var second = lookup(runtime, "r:global t:1h", 2); var third = lookup(runtime, "r:global t:1h", 3);
            assertEquals(sorted.subList(0, 8).stream().map(AuditEvent::id).toList(), ids(first)); assertTrue(first.hasMore());
            assertEquals(sorted.subList(8, 16).stream().map(AuditEvent::id).toList(), ids(second)); assertTrue(second.hasMore());
            assertEquals(sorted.subList(16, 18).stream().map(AuditEvent::id).toList(), ids(third)); assertFalse(third.hasMore());
            assertTrue(lookup(runtime, "r:global t:1h", 4).rows().isEmpty());
            var statuses = new HashSet<BlockDeltaPayload.SnapshotStatus>();
            for (var row : first.rows()) { statuses.add(row.nbtStatus()); assertEquals("value", row.before().properties().getOrDefault("test", row.after().properties().get("test"))); }
            assertEquals(Set.of(BlockDeltaPayload.SnapshotStatus.values()), statuses);
            String output = String.join("\n", ChronicleFormatter.page(first, CLOCK));
            assertTrue(output.contains("[NBT: present]")); assertTrue(output.contains("[NBT: oversized]")); assertTrue(output.contains("[NBT: failed]"));
            assertTrue(output.contains("minecraft:air")); assertTrue(output.contains("[test=value]")); assertTrue(output.contains("0s ago"));
            var spatial = lookup(runtime, "dim:overworld at:0,64,-10 r:5 t:1h", 1);
            assertEquals(sorted.stream().filter(e -> e.envelope().worldId().equals("minecraft:overworld") && e.envelope().x() >= -5 && e.envelope().x() <= 5
                    && e.envelope().z() >= -15 && e.envelope().z() <= -5).map(AuditEvent::id).toList(), ids(spatial));
            assertTrue(lookup(runtime, "r:global t:1h u:#fakeplayer", 1).rows().stream().allMatch(e -> e.envelope().actorType().equals("FAKE_PLAYER")));
            assertEquals(ids(lookup(runtime, "r:global t:1h u:Bot", 1)), ids(lookup(runtime, "r:global t:1h u:" + BOT, 1)));
            assertEquals(ids(first), ids(lookup(runtime, "r:global t:1h u:Alice," + BOT + " b:chest,diamond_ore", 1)));
            assertEquals(4, lookup(runtime, "r:global t:1h a:-block u:Alice", 1).rows().size());
            assertTrue(lookup(runtime, "r:global t:1h a:+block b:diamond_ore", 1).rows().stream().allMatch(e -> e.envelope().eventType().equals("BLOCK_PLACE")));
            assertTrue(lookup(runtime, "r:global t:1h u:Missing", 1).rows().isEmpty());
            var spec = new LookupParser(CLOCK).parse("r:global t:1h", CONSOLE);
            var injection = new LookupSpec(spec.since(), null, null,
                    List.of(new LookupSpec.Actor("Alice' OR 1=1 --", null, false)), List.of(), spec.actions());
            assertTrue(runtime.lookup(injection, 1).toCompletableFuture().get(5, TimeUnit.SECONDS).rows().isEmpty());
            assertEquals(24, runtime.status().toCompletableFuture().get(5, TimeUnit.SECONDS).storage().persistedEvents());
        }
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var status = runtime.status().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(24, status.storage().persistedEvents()); assertEquals(0, status.runtime().persisted());
        }
    }
    @Test void lifetimeTotalsIncludeLegacyAndDeadLettersAcrossRestart() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) { runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        try (var c = PersistenceTest.connection(config.database()); var s = c.createStatement()) {
            s.execute("ALTER TABLE audit_events ADD COLUMN injected INTEGER CHECK(actor_id <> 'Alice')");
        }
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.submit(runtime, event(0)); PersistenceTest.submit(runtime, event(1)); PersistenceTest.submit(runtime, PersistenceTest.event(1));
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 2 && runtime.diagnostics().deadLetters() == 1);
            assertEquals(new StorageTotals(2, 1), runtime.status().toCompletableFuture().get(5, TimeUnit.SECONDS).storage());
        }
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var status = runtime.status().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(new StorageTotals(2, 1), status.storage()); assertEquals(0, status.runtime().persisted()); assertEquals(0, status.runtime().deadLetters());
        }
    }
    @Test void nanosecondCutoffIsInclusiveAndNewCommitsShiftLivePages() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var source = event(1);
            for (int i = 0; i < 10; i++) PersistenceTest.submit(runtime, new AuditEvent(EventId.generate(), NOW.minusSeconds(60).plusNanos(i - 1), source.envelope(), source.payloadVersion(), source.payload()));
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 10);
            var spec = new LookupParser(CLOCK).parse("r:global t:1m", CONSOLE);
            var first = runtime.lookup(spec, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(8, first.rows().size()); assertTrue(first.hasMore());
            assertEquals(1, runtime.lookup(spec, 2).toCompletableFuture().get(5, TimeUnit.SECONDS).rows().size());
            var newest = new AuditEvent(EventId.generate(), NOW, source.envelope(), source.payloadVersion(), source.payload());
            PersistenceTest.submit(runtime, newest); PersistenceTest.await(() -> runtime.diagnostics().persisted() == 11);
            assertEquals(newest.id(), runtime.lookup(spec, 1).toCompletableFuture().get(5, TimeUnit.SECONDS).rows().getFirst().id());
            assertEquals(2, runtime.lookup(spec, 2).toCompletableFuture().get(5, TimeUnit.SECONDS).rows().size());
        }
    }
    @Test void corruptQueriedPayloadHaltsAdmissionAndPromotesFatalStorage() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var runtime = new ChronicleRuntime(config, CLOCK, "test"); runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            PersistenceTest.submit(runtime, event(1)); PersistenceTest.await(() -> runtime.diagnostics().persisted() == 1);
            try (var c = PersistenceTest.connection(config.database()); var s = c.createStatement()) { s.execute("UPDATE audit_events SET payload=X'00'"); }
            assertThrows(ExecutionException.class, () -> lookup(runtime, "r:global t:1h", 1));
            assertEquals(ChronicleHealth.FATAL_STORAGE, runtime.health());
            assertEquals(io.github.andriyko69.chronicle.ingest.Submission.Status.UNHEALTHY, runtime.submit(event(2)).status());
        } finally { assertThrows(IllegalStateException.class, runtime::close); }
    }
    @Test void boundedExecutorRejectsImmediatelyAndAllReaderLifecycleCallsUseQueryWorker() throws Exception {
        var config = InfrastructureConfig.defaults(root); var base = new SQLiteStorageBackend(config, CLOCK, "test");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var gated = new AtomicBoolean();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        var backend = new StorageBackend() {
            public StorageWriter openWriter(UUID identity) throws StorageFailure { return base.openWriter(identity); }
            public StorageReader openReader() throws StorageFailure {
                threads.add(Thread.currentThread().getName()); var delegate = base.openReader();
                return new StorageReader() {
                    public List<AuditEvent> recent(int limit) throws StorageFailure { throw new UnsupportedOperationException(); }
                    public LookupPage lookup(LookupSpec spec, int page) throws StorageFailure {
                        threads.add(Thread.currentThread().getName());
                        if (gated.compareAndSet(false, true)) {
                            entered.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Gate timeout"); }
                            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        }
                        return delegate.lookup(spec, page);
                    }
                    public StorageTotals totals() throws StorageFailure { threads.add(Thread.currentThread().getName()); return delegate.totals(); }
                    public void close() throws StorageFailure { threads.add(Thread.currentThread().getName()); delegate.close(); }
                };
            }
        };
        try (var runtime = new ChronicleRuntime(config, CLOCK, backend, () -> new ChronicleQueueJournal(config.journal(), CLOCK))) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var spec = new LookupParser(CLOCK).parse("r:global t:1h", CONSOLE);
            var futures = new ArrayList<CompletableFuture<?>>(); futures.add(runtime.lookup(spec, 1).toCompletableFuture());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 64; i++) futures.add(runtime.lookup(spec, 1).toCompletableFuture());
                long start = System.nanoTime(); var rejected = runtime.lookup(spec, 1).toCompletableFuture();
                assertTrue(rejected.isCompletedExceptionally()); assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100));
                assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, rejected::join).getCause());
            } finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            runtime.status().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Set.of("chronicle-query"), threads);
        } finally { release.countDown(); }
    }
    @Test void readerCannotWriteDatabaseAndQueryTimeoutDoesNotPromoteFatalHealth() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            try (var c = PersistenceTest.connection(config.database()); var s = c.createStatement()) {
                // An intentionally pathological view makes the public totals query exercise its real execution deadline.
                s.execute("ALTER TABLE audit_events RENAME TO saved_events");
                s.execute("CREATE VIEW audit_events AS WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<1000000000) SELECT i FROM n");
            }
            long start = System.nanoTime();
            var failed = assertThrows(ExecutionException.class, () -> runtime.status().toCompletableFuture().get(6, TimeUnit.SECONDS));
            var cause = assertInstanceOf(StorageFailure.class, failed.getCause());
            assertEquals(StorageFailure.Kind.TRANSIENT, cause.kind()); assertEquals("QUERY_TIMEOUT", cause.backendCode());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)); assertEquals(ChronicleHealth.HEALTHY, runtime.health());
            try (var c = PersistenceTest.connection(config.database()); var s = c.createStatement()) { s.execute("DROP VIEW audit_events"); s.execute("ALTER TABLE saved_events RENAME TO audit_events"); }
        }
        var backend = new SQLiteStorageBackend(config, CLOCK, "test");
        try (var reader = backend.openReader()) {
            var field = reader.getClass().getDeclaredField("connection"); field.setAccessible(true);
            var connection = (java.sql.Connection)field.get(reader);
            try (var statement = connection.createStatement()) {
                assertThrows(java.sql.SQLException.class, () -> statement.execute("DELETE FROM audit_events"));
            }
        }
    }
}
