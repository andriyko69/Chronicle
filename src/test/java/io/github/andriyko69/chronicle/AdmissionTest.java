/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.EncodedAuditEvent;
import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.journal.*;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.sqlite.SQLiteStorageBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionTest {
    @TempDir Path root;
    private static class JournalWrapper implements AuditJournal {
        final AuditJournal delegate;
        JournalWrapper(AuditJournal delegate) { this.delegate = delegate; }
        public UUID identity() { return delegate.identity(); }
        public JournalPosition append(EncodedAuditEvent event) { return delegate.append(event); }
        public void sync() { delegate.sync(); }
        public JournalPosition lastPosition() { return delegate.lastPosition(); }
        public long backlog(long count) { return delegate.backlog(count); }
        public long diskBytes() { return delegate.diskBytes(); }
        public Cursor cursorAfter(JournalPosition committed, long count) { return delegate.cursorAfter(committed, count); }
        public void retain(JournalPosition committed, int cycles) { delegate.retain(committed, cycles); }
        public void close() { delegate.close(); }
    }
    @Test void fullIngressRejectsImmediatelyAndAdmissionIsSeparateFromSync() throws Exception {
        var defaults = InfrastructureConfig.defaults(root);
        var config = new InfrastructureConfig(defaults.database(), defaults.journal(), 1, 100, Duration.ofMillis(25),
                Duration.ofMillis(10), defaults.retryMin(), defaults.retryMax(), defaults.shutdownGrace(), 20, 2);
        var clock = ChronicleClock.system();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var runtime = new ChronicleRuntime(config, clock, new SQLiteStorageBackend(config, clock, "test"),
                () -> new JournalWrapper(new ChronicleQueueJournal(config.journal(), clock)) {
                    @Override public JournalPosition append(EncodedAuditEvent event) {
                        entered.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
                        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                        return super.append(event);
                    }
                });
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try (runtime) {
            var first = runtime.submit(PersistenceTest.event(1)); assertEquals(Submission.Status.ACCEPTED, first.status());
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(first.journaled().toCompletableFuture().isDone());
            var second = runtime.submit(PersistenceTest.event(2)); assertEquals(Submission.Status.ACCEPTED, second.status());
            long start = System.nanoTime(); var rejected = runtime.submit(PersistenceTest.event(3));
            assertEquals(Submission.Status.QUEUE_FULL, rejected.status());
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100));
            assertEquals(1, runtime.diagnostics().ingressDepth());
            assertEquals(1, runtime.diagnostics().droppedEvents());
            assertEquals(1, runtime.diagnostics().queueFullDrops());
            release.countDown();
            first.journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
            second.journaled().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(Submission.Status.STOPPED, runtime.submit(PersistenceTest.event(4)).status());
    }
    @Test void appendFailureFailsAcknowledgementsAndChangesHealth() throws Exception {
        var config = InfrastructureConfig.defaults(root); var clock = ChronicleClock.system();
        var runtime = new ChronicleRuntime(config, clock, new SQLiteStorageBackend(config, clock, "test"),
                () -> new JournalWrapper(new ChronicleQueueJournal(config.journal(), clock)) {
                    @Override public JournalPosition append(EncodedAuditEvent event) { throw new JournalException("Injected disk write failure", null); }
                });
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var submission = runtime.submit(PersistenceTest.event(1));
        assertEquals(Submission.Status.ACCEPTED, submission.status());
        assertThrows(ExecutionException.class, () -> submission.journaled().toCompletableFuture().get(5, TimeUnit.SECONDS));
        PersistenceTest.await(() -> runtime.health() == ChronicleHealth.JOURNAL_UNAVAILABLE);
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(0, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
    }
    @Test void finalDiagnosticsFailureStillClosesJournalAndCannotReportCleanShutdown() throws Exception {
        var config = InfrastructureConfig.defaults(root); var clock = ChronicleClock.system();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var runtime = new ChronicleRuntime(config, clock, new SQLiteStorageBackend(config, clock, "test"),
                () -> new JournalWrapper(new ChronicleQueueJournal(config.journal(), clock)) {
                    @Override public long diskBytes() { throw new JournalException("Injected final stats I/O failure", null); }
                    @Override public void close() { try { super.close(); } finally { closed.set(true); } }
                });
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, runtime::close);
        assertTrue(closed.get()); assertEquals(ChronicleHealth.JOURNAL_UNAVAILABLE, runtime.health());
        try (var reopened = new ChronicleQueueJournal(config.journal(), clock)) {
            assertEquals(JournalPosition.NONE, reopened.lastPosition());
        }
    }
    @Test void concurrentShutdownDrainsEveryAcceptedSubmission() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test");
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var acknowledgements = new ConcurrentLinkedQueue<CompletionStage<JournalPosition>>();
        var first = new CountDownLatch(1);
        try (var producers = Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int thread = 0; thread < 4; thread++) jobs.add(producers.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    var submission = runtime.submit(PersistenceTest.event(i));
                    if (submission.status() == Submission.Status.ACCEPTED) {
                        acknowledgements.add(submission.journaled()); first.countDown();
                    } else assertEquals(Submission.Status.STOPPED, submission.status());
                }
            }));
            assertTrue(first.await(5, TimeUnit.SECONDS)); runtime.close();
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        assertFalse(acknowledgements.isEmpty());
        for (var acknowledgement : acknowledgements) assertNotNull(acknowledgement.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(acknowledgements.size(), PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(ChronicleHealth.CLOSED, runtime.health());
    }
}
