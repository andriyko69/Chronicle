/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.runtime;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.diagnostics.*;
import io.github.andriyko69.chronicle.dlq.DeadLetterRecord;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import io.github.andriyko69.chronicle.journal.*;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.persistence.*;
import io.github.andriyko69.chronicle.storage.*;
import io.github.andriyko69.chronicle.storage.sqlite.SQLiteStorageBackend;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChronicleRuntime implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleRuntime.class);
    private record Pending(EncodedAuditEvent event, CompletableFuture<JournalPosition> acknowledgement) {}
    private record Appended(JournalPosition position, CompletableFuture<JournalPosition> acknowledgement) {}
    private final InfrastructureConfig config;
    private final ChronicleClock clock;
    private final StorageBackend backend;
    private final Supplier<AuditJournal> journalFactory;
    private final ChronicleEventCodec codec = new ChronicleEventCodec();
    private final ArrayBlockingQueue<Pending> ingress;
    private final Object admission = new Object();
    private final CompletableFuture<AuditJournal> journalReady = new CompletableFuture<>();
    private final CompletableFuture<Void> journalDrained = new CompletableFuture<>();
    private final CompletableFuture<Void> persistenceStopped = new CompletableFuture<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final ChronicleMetrics metrics = new ChronicleMetrics();
    private final Thread journalThread;
    private final Thread persistenceThread;
    private final ThreadPoolExecutor queries;
    private final Set<CompletableFuture<?>> queryResults = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean accepting;
    private volatile boolean closing;
    private volatile boolean closed;
    private volatile boolean storageUnavailable;
    private volatile long shutdownDeadline = Long.MAX_VALUE;
    private volatile Throwable journalFailure;
    private volatile Throwable fatalStorage;

    public ChronicleRuntime(InfrastructureConfig config, ChronicleClock clock, String version) {
        this(config, clock, new SQLiteStorageBackend(config, clock, version), () -> new ChronicleQueueJournal(config.journal(), clock));
    }
    /** Factories run on their owning workers, never the submitting thread. */
    public ChronicleRuntime(InfrastructureConfig config, ChronicleClock clock, StorageBackend backend, Supplier<AuditJournal> journalFactory) {
        this.config = config; this.clock = clock; this.backend = backend; this.journalFactory = journalFactory;
        ingress = new ArrayBlockingQueue<>(config.ingressCapacity());
        journalThread = new Thread(this::journalLoop, "chronicle-journal");
        persistenceThread = new Thread(this::persistenceLoop, "chronicle-persistence");
        queries = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "chronicle-query"), new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized CompletionStage<Void> start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Runtime already started");
        persistenceThread.start(); journalThread.start();
        return ready.minimalCompletionStage();
    }
    public Submission submit(AuditEvent event) {
        EncodedAuditEvent encoded = codec.encode(event);
        var acknowledgement = new CompletableFuture<JournalPosition>();
        Submission.Status status;
        synchronized (admission) {
            if (journalFailure != null || fatalStorage != null) status = Submission.Status.UNHEALTHY;
            else if (closing) status = Submission.Status.STOPPED;
            else if (!accepting) status = Submission.Status.NOT_READY;
            else status = ingress.offer(new Pending(encoded, acknowledgement)) ? Submission.Status.ACCEPTED : Submission.Status.QUEUE_FULL;
        }
        if (status != Submission.Status.ACCEPTED)
            acknowledgement.completeExceptionally(new RejectedExecutionException("Chronicle submission rejected: " + status));
        return new Submission(status, acknowledgement.minimalCompletionStage());
    }
    /** Results are detached. Callers touching Minecraft must dispatch them through server.execute. */
    public CompletionStage<List<AuditEvent>> recent(int limit) {
        var result = new CompletableFuture<List<AuditEvent>>();
        if (!ready.isDone() || ready.isCompletedExceptionally() || closing) {
            result.completeExceptionally(new RejectedExecutionException("Chronicle queries unavailable"));
            return result.minimalCompletionStage();
        }
        queryResults.add(result);
        try {
            queries.execute(() -> {
                try (var reader = backend.openReader()) { result.complete(reader.recent(limit)); }
                catch (StorageFailure failure) {
                    if (failure.kind() == StorageFailure.Kind.FATAL) {
                        fatalStorage = failure; stopAcceptance();
                        LOG.error("Chronicle fatal query/storage failure; journal preserved and new admission halted", failure);
                    }
                    result.completeExceptionally(failure);
                }
                catch (Throwable failure) { result.completeExceptionally(failure); }
                finally { queryResults.remove(result); }
            });
        } catch (RejectedExecutionException failure) { queryResults.remove(result); result.completeExceptionally(failure); }
        return result.minimalCompletionStage();
    }
    public ChronicleHealth health() {
        if (journalFailure != null) return ChronicleHealth.JOURNAL_UNAVAILABLE;
        if (fatalStorage != null) return ChronicleHealth.FATAL_STORAGE;
        if (closed) return ChronicleHealth.CLOSED;
        if (closing) return ChronicleHealth.SHUTTING_DOWN;
        if (storageUnavailable) return ChronicleHealth.STORAGE_UNAVAILABLE;
        return ready.isDone() ? ChronicleHealth.HEALTHY : ChronicleHealth.STARTING;
    }
    public ChronicleMetrics.Snapshot diagnostics() { return metrics.snapshot(health(), ingress.size(), clock); }

    private void journalLoop() {
        AuditJournal journal = null;
        var waiting = new ArrayList<Appended>();
        try {
            journal = journalFactory.get();
            synchronized (admission) { accepting = !closing && fatalStorage == null; }
            journalReady.complete(journal);
            long lastSync = System.nanoTime();
            long lastStats = lastSync;
            while (accepting || !ingress.isEmpty()) {
                Pending pending = ingress.poll();
                if (pending != null) {
                    try {
                        JournalPosition position = journal.append(pending.event());
                        waiting.add(new Appended(position, pending.acknowledgement()));
                        metrics.journalAppends.incrementAndGet();
                        if (metrics.oldestUncommitted == null) metrics.oldestUncommitted = clock.now();
                    } catch (Throwable failure) { pending.acknowledgement().completeExceptionally(failure); throw failure; }
                }
                long now = System.nanoTime();
                if (!waiting.isEmpty() && (now - lastSync >= config.syncInterval().toNanos() || waiting.size() >= 1024)) {
                    flush(journal, waiting); lastSync = System.nanoTime();
                }
                if (now - lastStats >= TimeUnit.SECONDS.toNanos(1)) {
                    metrics.journalDiskBytes = journal.diskBytes();
                    metrics.backlog = journal.backlog(metrics.terminalRecords);
                    lastStats = now;
                }
                if (pending == null) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            flush(journal, waiting);
        } catch (Throwable failure) {
            journalFailure = failure;
            stopAcceptance();
            journalReady.completeExceptionally(failure); ready.completeExceptionally(failure);
            LOG.error("Chronicle journal unavailable: forensic durability no longer guaranteed for new events", failure);
            for (var appended : waiting) appended.acknowledgement().completeExceptionally(failure);
            Pending pending;
            while ((pending = ingress.poll()) != null) pending.acknowledgement().completeExceptionally(failure);
        } finally {
            journalDrained.complete(null);
            // Persistence must release its cursor before the journal owner closes mapped files.
            persistenceStopped.join();
            if (journal != null) {
                try {
                    if (journalFailure == null) {
                        metrics.backlog = journal.backlog(metrics.terminalRecords);
                        metrics.journalDiskBytes = journal.diskBytes();
                    }
                } catch (Throwable failure) {
                    journalFailure = failure;
                    LOG.error("Chronicle final journal diagnostics failed; shutdown is not clean", failure);
                } finally {
                    try { journal.close(); } catch (Throwable failure) {
                        journalFailure = failure;
                        LOG.error("Chronicle journal flushing/closing failed; shutdown is not clean", failure);
                    }
                }
            }
        }
    }
    private void flush(AuditJournal journal, List<Appended> waiting) {
        journal.sync();
        metrics.lastFlush = clock.now();
        for (var appended : waiting) appended.acknowledgement().complete(appended.position());
        waiting.clear();
    }
    private void persistenceLoop() {
        StorageWriter writer = null;
        AuditJournal.Cursor cursor = null;
        var pending = new ArrayList<JournalRecord>(config.batchSize());
        List<TerminalRecord> terminal = null;
        StorageWriter.Checkpoint checkpoint = null;
        int failures = 0;
        var retry = new RetryPolicy(config.retryMin(), config.retryMax());
        try {
            AuditJournal journal = journalReady.join();
            while (!expired() && journalFailure == null) {
                try {
                    if (writer == null) writer = backend.openWriter(journal.identity());
                    if (checkpoint == null) {
                        checkpoint = writer.checkpoint();
                        metrics.committedPosition = checkpoint.position().value(); metrics.schemaVersion = checkpoint.schemaVersion();
                        metrics.terminalRecords = checkpoint.terminalRecords();
                        cursor = journal.cursorAfter(checkpoint.position(), checkpoint.terminalRecords());
                        metrics.backlog = journal.backlog(checkpoint.terminalRecords());
                        ready.complete(null);
                        LOG.info("Chronicle ready: schema={}, checkpoint={}, replay backlog={}", metrics.schemaVersion, metrics.committedPosition, metrics.backlog);
                    }
                    if (pending.isEmpty()) {
                        JournalRecord first = cursor.next();
                        if (first == null) {
                            metrics.backlog = journal.backlog(checkpoint.terminalRecords());
                            if (metrics.backlog == 0) metrics.oldestUncommitted = null;
                            if (journalDrained.isDone() && journal.lastPosition().equals(checkpoint.position())) break;
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1)); continue;
                        }
                        pending.add(first);
                        metrics.oldestUncommitted = first.journaledAt();
                        long batchBegan = System.nanoTime();
                        long deadline = batchBegan + config.batchMaxAge().toNanos();
                        while (pending.size() < config.batchSize() && System.nanoTime() < deadline && !expired()) {
                            var next = cursor.next();
                            if (next != null) pending.add(next);
                            else if (journalDrained.isDone()) break;
                            else LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        }
                        metrics.maxBatchWaitNanos.accumulateAndGet(System.nanoTime() - batchBegan, Math::max);
                        terminal = new ArrayList<>(pending.size());
                        for (var record : pending) {
                            try { terminal.add(new TerminalRecord(record, codec.decode(record.event()), null)); }
                            catch (ChronicleEventCodec.InvalidEventException failure) {
                                terminal.add(new TerminalRecord(record, null, DeadLetterRecord.malformed(clock.now(), failure.getMessage())));
                            }
                        }
                    }
                    metrics.currentBatchSize = pending.size();
                    metrics.maxBatchSize = Math.max(metrics.maxBatchSize, pending.size());
                    long began = System.nanoTime();
                    StorageWriter.CommitResult result = writer.commit(checkpoint, terminal);
                    long latency = System.nanoTime() - began;
                    metrics.transactionNanos.addAndGet(latency); metrics.maxTransactionNanos.accumulateAndGet(latency, Math::max);
                    metrics.transactions.incrementAndGet(); metrics.persisted.addAndGet(result.insertedEvents());
                    metrics.deadLetters.addAndGet(result.insertedDeadLetters()); metrics.lastCommit = clock.now();
                    checkpoint = result.checkpoint(); metrics.committedPosition = checkpoint.position().value();
                    metrics.terminalRecords = checkpoint.terminalRecords();
                    pending.clear(); terminal = null; metrics.currentBatchSize = 0;
                    storageUnavailable = false; failures = 0;
                    metrics.backlog = journal.backlog(checkpoint.terminalRecords());
                    try { journal.retain(checkpoint.position(), config.retainedCycles()); }
                    catch (RuntimeException retention) { LOG.warn("Chronicle retention failed; preserving journal files", retention); }
                } catch (StorageFailure failure) {
                    metrics.persistenceFailures.incrementAndGet();
                    if (failure.kind() != StorageFailure.Kind.TRANSIENT) throw failure;
                    storageUnavailable = true; metrics.retries.incrementAndGet(); failures++;
                    LOG.warn("Chronicle storage unavailable ({}); journal retained, retry {}", failure.backendCode(), failures);
                    if (writer != null) { try { writer.close(); } catch (StorageFailure close) { failure.addSuppressed(close); } writer = null; }
                    long until = System.nanoTime() + retry.delayNanos(failures);
                    while (System.nanoTime() < until && !expired() && journalFailure == null)
                        LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(10), until - System.nanoTime()));
                }
            }
        } catch (Throwable failure) {
            if (failure instanceof JournalException) {
                journalFailure = failure; ready.completeExceptionally(failure); stopAcceptance();
                LOG.error("Chronicle journal replay unavailable: forensic durability no longer guaranteed", failure);
            } else if (journalFailure == null) {
                fatalStorage = failure; ready.completeExceptionally(failure); stopAcceptance();
                LOG.error("Chronicle fatal persistence failure; progress halted and journal preserved", failure);
            }
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable failure) { journalFailure = failure; }
            if (writer != null) try { writer.close(); } catch (Throwable failure) { fatalStorage = failure; }
            persistenceStopped.complete(null);
        }
    }
    private boolean expired() { return closing && System.nanoTime() >= shutdownDeadline; }
    private void stopAcceptance() {
        synchronized (admission) {
            accepting = false;
            if (!closing) { closing = true; shutdownDeadline = System.nanoTime() + config.shutdownGrace().toNanos(); }
        }
    }
    @Override public synchronized void close() {
        if (!started.get()) throw new IllegalStateException("Runtime never started");
        if (closed && journalFailure == null && fatalStorage == null) return;
        stopAcceptance(); queries.shutdown();
        ready.completeExceptionally(new CancellationException("Chronicle stopped before becoming ready"));
        long deadline = System.nanoTime() + config.shutdownGrace().toNanos() + TimeUnit.SECONDS.toNanos(3);
        try {
            joinUntil(journalThread, deadline); joinUntil(persistenceThread, deadline);
            if (!queries.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                queries.shutdownNow();
                for (var result : queryResults) result.completeExceptionally(new CancellationException("Chronicle shutting down"));
                if (!queries.awaitTermination(1, TimeUnit.SECONDS)) throw new IllegalStateException("Query executor did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Chronicle shutdown interrupted", interrupted);
        }
        if (journalThread.isAlive() || persistenceThread.isAlive()) throw new IllegalStateException("Chronicle workers did not stop; resources remain owned");
        closed = true;
        if (journalFailure != null) throw new IllegalStateException("Chronicle journal shutdown was not clean", journalFailure);
        if (fatalStorage != null) throw new IllegalStateException("Chronicle storage shutdown was not clean", fatalStorage);
        LOG.info("Chronicle closed: {} (remaining database backlog is replayable)", diagnostics());
    }
    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) thread.join(Duration.ofNanos(remaining));
    }
}
