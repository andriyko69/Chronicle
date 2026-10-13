/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.diagnostics;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Internal counters; taking a snapshot performs no journal or JDBC I/O. */
public final class ChronicleMetrics {
    public final AtomicLong journalAppends = new AtomicLong();
    public final AtomicLong persisted = new AtomicLong();
    public final AtomicLong retries = new AtomicLong();
    public final AtomicLong persistenceFailures = new AtomicLong();
    public final AtomicLong deadLetters = new AtomicLong();
    public final AtomicLong droppedEvents = new AtomicLong();
    public final AtomicLong queueFullDrops = new AtomicLong();
    public final AtomicLong transactions = new AtomicLong();
    public final AtomicLong transactionNanos = new AtomicLong();
    public final AtomicLong maxTransactionNanos = new AtomicLong();
    public final AtomicLong maxBatchWaitNanos = new AtomicLong();
    public volatile long committedPosition = -1;
    public volatile long terminalRecords;
    public volatile long backlog;
    public volatile long journalDiskBytes;
    public volatile int currentBatchSize;
    public volatile int maxBatchSize;
    public volatile int schemaVersion;
    public volatile Instant oldestUncommitted;
    public volatile Instant lastCommit;
    public volatile Instant lastFlush;
    private final long start = System.nanoTime();

    public record Snapshot(ChronicleHealth health, int ingressDepth, long journalAppends, double appendPerSecond,
            long backlog, long journalDiskBytes, Duration oldestUncommittedAge, long persisted, double persistedPerSecond,
            int currentBatchSize, int maxBatchSize, double maxBatchWaitMillis, double averageTransactionMillis, double maxTransactionMillis,
            long retries, long persistenceFailures, long deadLetters, long droppedEvents, long queueFullDrops, long committedPosition, int schemaVersion,
            Instant lastCommit, Instant lastFlush) {}

    public Snapshot snapshot(ChronicleHealth health, int ingressDepth, ChronicleClock clock) {
        double seconds = Math.max(0.001, (System.nanoTime() - start) / 1e9);
        Instant oldest = oldestUncommitted;
        Duration age = oldest == null ? Duration.ZERO : Duration.between(oldest, clock.now());
        if (age.isNegative()) age = Duration.ZERO;
        return new Snapshot(health, ingressDepth, journalAppends.get(), journalAppends.get() / seconds,
                backlog, journalDiskBytes, age, persisted.get(), persisted.get() / seconds,
                currentBatchSize, maxBatchSize, maxBatchWaitNanos.get() / 1e6, transactionNanos.get() / 1e6 / Math.max(1, transactions.get()),
                maxTransactionNanos.get() / 1e6, retries.get(), persistenceFailures.get(), deadLetters.get(), droppedEvents.get(), queueFullDrops.get(),
                committedPosition, schemaVersion, lastCommit, lastFlush);
    }
}
