/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.journal.*;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.*;
import io.github.andriyko69.chronicle.storage.sqlite.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir Path root;
    private static final ChronicleClock CLOCK = ChronicleClock.system();
    private static final ChronicleEventCodec CODEC = new ChronicleEventCodec();
    static AuditEvent event(int number) {
        return new AuditEvent(EventId.generate(), Instant.ofEpochSecond(123456789, number % 1_000_000_000), "event-" + number);
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "Condition did not become true before deadline");
    }
    static Submission submit(ChronicleRuntime runtime, AuditEvent event) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            Submission submission = runtime.submit(event);
            if (submission.status() == Submission.Status.ACCEPTED) return submission;
            assertTrue(submission.status() == Submission.Status.QUEUE_FULL || submission.status() == Submission.Status.NOT_READY,
                    "Unexpected rejection " + submission.status() + ", " + runtime.diagnostics());
            if (System.nanoTime() >= deadline) fail("Submission deadline exceeded");
            Thread.sleep(1);
        }
    }
    private ChronicleRuntime open(InfrastructureConfig config) throws Exception {
        var runtime = new ChronicleRuntime(config, CLOCK, "test");
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return runtime;
    }
    static Connection connection(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }
    static long scalar(Path database, String sql) throws SQLException {
        try (var connection = connection(database); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static InfrastructureConfig shutdownConfig(Path root) {
        var defaults = InfrastructureConfig.defaults(root);
        return new InfrastructureConfig(defaults.database(), defaults.journal(), 128, 100, Duration.ofMillis(25),
                Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(300), 20, 2);
    }
    @Test void tenThousandEventsRoundTripExactlyOffSubmissionThreadAndRestart() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var base = new SQLiteStorageBackend(config, CLOCK, "test");
        Set<String> jdbcThreads = ConcurrentHashMap.newKeySet();
        var backend = new StorageBackend() {
            @Override public StorageWriter openWriter(UUID identity) throws StorageFailure {
                jdbcThreads.add(Thread.currentThread().getName());
                return base.openWriter(identity);
            }
            @Override public StorageReader openReader() throws StorageFailure {
                jdbcThreads.add(Thread.currentThread().getName());
                return base.openReader();
            }
        };
        var runtime = new ChronicleRuntime(config, CLOCK, backend, () -> new ChronicleQueueJournal(config.journal(), CLOCK));
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var expected = new HashMap<String, AuditEvent>();
        try (runtime) {
            for (int i = 0; i < 10_000; i++) {
                AuditEvent event = event(i); expected.put(event.id().toString(), event); submit(runtime, event);
            }
            await(() -> runtime.diagnostics().persisted() == 10_000);
            assertEquals(0, runtime.diagnostics().deadLetters());
            assertTrue(runtime.diagnostics().maxBatchSize() <= 100);
            var recent = runtime.recent(1000).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1000, recent.size());
            for (var event : recent) assertEquals(expected.get(event.id().toString()), event);
            assertEquals(Set.of("chronicle-persistence", "chronicle-query"), jdbcThreads);
        }
        try (var connection = connection(config.database()); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT event_id,occurred_seconds,occurred_nanos,encoded FROM legacy_audit_events")) {
            int count = 0;
            while (rows.next()) {
                var event = expected.remove(rows.getString(1)); assertNotNull(event);
                assertEquals(event.occurredAt().getEpochSecond(), rows.getLong(2));
                assertEquals(event.occurredAt().getNano(), rows.getInt(3));
                assertArrayEquals(CODEC.encode(event).bytes(), rows.getBytes(4)); count++;
            }
            assertEquals(10_000, count); assertTrue(expected.isEmpty());
        }
        long checkpoint = scalar(config.database(), "SELECT committed_position FROM journal_checkpoint");
        try (var restarted = open(config)) {
            assertEquals(checkpoint, restarted.diagnostics().committedPosition());
            assertEquals(0, restarted.diagnostics().backlog());
            submit(restarted, event(10001)).journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
            await(() -> restarted.diagnostics().persisted() == 1);
        }
        assertEquals(10_001, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
    }
    @Test void quietServerCommitsWithoutWaitingForFullBatch() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = open(config)) {
            long began = System.nanoTime();
            submit(runtime, event(1)).journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
            await(() -> runtime.diagnostics().persisted() == 1);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            System.out.println("QUIET_EVENT synced+committedMillis=" + millis + " maxBatchWaitMillis=" + runtime.diagnostics().maxBatchWaitMillis());
            assertTrue(millis < 1000, "Bounded integration tolerance, configured oldest-batch wait is 25ms");
            assertEquals(1, runtime.diagnostics().maxBatchSize());
            assertTrue(runtime.diagnostics().maxBatchWaitMillis() < 250, "25ms batching target with scheduler tolerance");
        }
    }
    @Test void realSqliteBusyRetriesWithoutDlqAndBacklogDrains() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = open(config); var locked = connection(config.database()); var statement = locked.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            Submission last = null;
            for (int i = 0; i < 1000; i++) last = submit(runtime, event(i));
            last.journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
            await(() -> runtime.diagnostics().retries() > 0);
            assertEquals(ChronicleHealth.STORAGE_UNAVAILABLE, runtime.health());
            assertEquals(0, runtime.diagnostics().deadLetters());
            assertEquals(0, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
            statement.execute("ROLLBACK");
            await(() -> runtime.diagnostics().persisted() == 1000);
            assertEquals(ChronicleHealth.HEALTHY, runtime.health());
        }
        assertEquals(1000, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(0, scalar(config.database(), "SELECT count(*) FROM dead_letters"));
    }
    @Test void commitSucceededButAcknowledgementLostRemainsIdempotent() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var base = new SQLiteStorageBackend(config, CLOCK, "test");
        var injected = new AtomicBoolean();
        var backend = new StorageBackend() {
            public StorageWriter openWriter(UUID identity) throws StorageFailure {
                var delegate = base.openWriter(identity);
                return new StorageWriter() {
                    public Checkpoint checkpoint() throws StorageFailure { return delegate.checkpoint(); }
                    public CommitResult commit(Checkpoint expected, List<TerminalRecord> batch) throws StorageFailure {
                        var result = delegate.commit(expected, batch);
                        if (injected.compareAndSet(false, true)) throw new StorageFailure(StorageFailure.Kind.TRANSIENT, "Commit acknowledgement deliberately lost", "TEST_ACK_LOST", null);
                        return result;
                    }
                    public void close() throws StorageFailure { delegate.close(); }
                };
            }
            public StorageReader openReader() throws StorageFailure { return base.openReader(); }
        };
        try (var runtime = new ChronicleRuntime(config, CLOCK, backend, () -> new ChronicleQueueJournal(config.journal(), CLOCK))) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var journaled = submit(runtime, event(1)).journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
            await(() -> runtime.diagnostics().retries() > 0 && runtime.diagnostics().committedPosition() == journaled.value());
            assertEquals(1, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
            assertEquals(1, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
            assertEquals(0, scalar(config.database(), "SELECT count(*) FROM dead_letters"));
        }
    }
    @Test void malformedEventIsTerminalDlqAndDoesNotLoopAfterRestart() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        JournalPosition last;
        try (var journal = new ChronicleQueueJournal(config.journal(), CLOCK)) {
            journal.append(CODEC.encode(event(1)));
            var invalid = CODEC.encode(event(2)); byte[] bytes = invalid.bytes(); ByteBuffer.wrap(bytes).putInt(4, 999);
            journal.append(new EncodedAuditEvent(invalid.id(), bytes));
            last = journal.append(CODEC.encode(event(3))); journal.sync();
        }
        try (var runtime = open(config)) {
            await(() -> runtime.diagnostics().committedPosition() == last.value());
            assertEquals(2, runtime.diagnostics().persisted()); assertEquals(1, runtime.diagnostics().deadLetters());
        }
        assertEquals(3, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        assertEquals(1, scalar(config.database(), "SELECT count(*) FROM dead_letters WHERE attempts=1 AND schema_version=6 AND classification='PERMANENT_EVENT' AND original_payload IS NOT NULL"));
        try (var runtime = open(config)) { assertEquals(last.value(), runtime.diagnostics().committedPosition()); }
        assertEquals(2, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
    }
    @Test void sqlCheckPoisonIsIsolatedWithSavepointsInSameTerminalTransaction() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = open(config)) { assertEquals(6, runtime.diagnostics().schemaVersion()); }
        try (var connection = connection(config.database()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE legacy_audit_events ADD COLUMN injected_check INTEGER CHECK(synthetic_value <> 'event-2')");
        }
        try (var runtime = open(config)) {
            for (int i = 1; i <= 3; i++) submit(runtime, event(i));
            await(() -> runtime.diagnostics().persisted() == 2 && runtime.diagnostics().deadLetters() == 1);
        }
        assertEquals(3, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        assertEquals(1, scalar(config.database(), "SELECT count(*) FROM dead_letters WHERE backend_code='SQLITE_CONSTRAINT_CHECK'"));
    }
    @Test void gracefulShutdownDuringOutageLeavesSyncedReplayableBacklog() throws Exception {
        var config = shutdownConfig(root);
        var runtime = open(config);
        try (var locked = connection(config.database()); var statement = locked.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            for (int i = 0; i < 100; i++) submit(runtime, event(i));
            runtime.close();
            assertEquals(ChronicleHealth.CLOSED, runtime.health());
            assertNotNull(runtime.diagnostics().lastFlush());
            assertEquals(100, runtime.diagnostics().journalAppends());
            assertEquals(0, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
            statement.execute("ROLLBACK");
        }
        try (var restarted = open(config)) { await(() -> restarted.diagnostics().persisted() == 100); }
        assertEquals(100, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
    }
    @Test void corruptMigrationAndChangedJournalIdentityFailExplicitly() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = open(config)) { }
        try (var connection = connection(config.database()); var statement = connection.createStatement()) {
            statement.execute("UPDATE schema_migrations SET checksum='tampered'");
        }
        var runtime = new ChronicleRuntime(config, CLOCK, "test");
        assertThrows(ExecutionException.class, () -> runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ChronicleHealth.FATAL_STORAGE, runtime.health()); assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(0, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        var clean = InfrastructureConfig.defaults(root.resolve("other"));
        try (var initialize = open(clean)) { }
        Files.writeString(clean.journal().resolve("chronicle.identity"), UUID.randomUUID().toString());
        var changed = new ChronicleRuntime(clean, CLOCK, "test");
        assertThrows(ExecutionException.class, () -> changed.start().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ChronicleHealth.JOURNAL_UNAVAILABLE, changed.health()); assertThrows(IllegalStateException.class, changed::close);
    }
    @Test void journalUnavailableIsLoudUnhealthyAndNotCleanShutdown() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        Files.createDirectories(root); Files.writeString(config.journal(), "not a directory");
        var runtime = new ChronicleRuntime(config, CLOCK, "test");
        assertThrows(ExecutionException.class, () -> runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(ChronicleHealth.JOURNAL_UNAVAILABLE, runtime.health());
        assertEquals(Submission.Status.UNHEALTHY, runtime.submit(event(1)).status());
        assertThrows(IllegalStateException.class, runtime::close);
    }
    @Test void abruptProcessReplaysJournaledUncommittedEventsExactlyOnce() throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(List.of("-cp", System.getProperty("chronicle.testClasspath"), CrashFixture.class.getName(), root.toString()));
        Path log = root.resolve("child.log");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly().waitFor(); fail("Child timed out: " + Files.readString(log)); }
        assertEquals(7, child.exitValue(), Files.readString(log));
        var config = InfrastructureConfig.defaults(root);
        assertEquals(0, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        try (var runtime = open(config)) { await(() -> runtime.diagnostics().persisted() == 1000); }
        try (var runtime = open(config)) { assertEquals(0, runtime.diagnostics().backlog()); }
        assertEquals(1000, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(1000, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
    }
    @Test @Tag("stress") void oneHundredThousandEventsStress() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        long start = System.nanoTime();
        long maxHeap = 0; int maxQueue = 0; long maxBacklog = 0;
        try (var runtime = open(config)) {
            for (int i = 0; i < 100_000; i++) {
                submit(runtime, event(i));
                if (i % 100 == 0) {
                    maxHeap = Math.max(maxHeap, java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    maxQueue = Math.max(maxQueue, runtime.diagnostics().ingressDepth());
                    maxBacklog = Math.max(maxBacklog, runtime.diagnostics().backlog());
                }
            }
            await(() -> runtime.diagnostics().persisted() == 100_000);
            var metrics = runtime.diagnostics();
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf(Locale.ROOT, "STRESS events=100000 seconds=%.3f eventsPerSecond=%.1f sampledMaxHeapMiB=%.1f sampledMaxIngress=%d sampledMaxBacklog=%d averageTxMillis=%.3f maxTxMillis=%.3f maxBatch=%d finalBacklog=%d%n",
                    seconds, 100_000 / seconds, maxHeap / 1048576.0, maxQueue, maxBacklog,
                    metrics.averageTransactionMillis(), metrics.maxTransactionMillis(), metrics.maxBatchSize(), metrics.backlog());
            assertTrue(maxQueue <= config.ingressCapacity()); assertTrue(metrics.maxBatchSize() <= 100);
            assertEquals(0, metrics.deadLetters());
        }
        assertEquals(100_000, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(100_000, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
    }
    @Test void exactDuplicateIsIdempotentButConflictingIdRollsBackAndHalts() throws Exception {
        var config = InfrastructureConfig.defaults(root); AuditEvent original = event(1);
        try (var journal = new ChronicleQueueJournal(config.journal(), CLOCK)) {
            journal.append(CODEC.encode(original)); journal.append(CODEC.encode(original));
            journal.append(CODEC.encode(event(2))); journal.sync();
        }
        try (var runtime = open(config)) { await(() -> runtime.diagnostics().backlog() == 0); }
        assertEquals(2, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(3, scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        long checkpoint = scalar(config.database(), "SELECT committed_position FROM journal_checkpoint");
        try (var journal = new ChronicleQueueJournal(config.journal(), CLOCK)) {
            journal.append(CODEC.encode(event(4)));
            journal.append(CODEC.encode(new AuditEvent(original.id(), original.occurredAt(), "conflicting-value")));
            journal.sync();
        }
        var runtime = open(config);
        await(() -> runtime.health() == ChronicleHealth.FATAL_STORAGE);
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(2, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(0, scalar(config.database(), "SELECT count(*) FROM dead_letters"));
        assertEquals(checkpoint, scalar(config.database(), "SELECT committed_position FROM journal_checkpoint"));
    }
    @Test void queryDetectingStoredCorruptionPromotesFatalHealth() throws Exception {
        var config = InfrastructureConfig.defaults(root); var runtime = open(config);
        submit(runtime, event(1)); await(() -> runtime.diagnostics().persisted() == 1);
        try (var connection = connection(config.database()); var statement = connection.createStatement()) {
            statement.execute("UPDATE legacy_audit_events SET encoded=X'00'");
        }
        assertThrows(ExecutionException.class, () -> runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(ChronicleHealth.FATAL_STORAGE, runtime.health());
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(1, scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
    }
}
