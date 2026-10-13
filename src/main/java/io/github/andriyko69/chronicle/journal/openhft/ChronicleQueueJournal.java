/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal.openhft;

import io.github.andriyko69.chronicle.codec.EncodedAuditEvent;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.event.EventId;
import io.github.andriyko69.chronicle.journal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.CRC32C;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.util.FileUtil;

public final class ChronicleQueueJournal implements AuditJournal {
    private static final int MAGIC = 0x43484a31;
    private static final int ENVELOPE_VERSION = 1;
    private static final int HEADER_BYTES = 52;
    private static final int CHECKSUM_OFFSET = 48;
    private final Path directory;
    private final ChronicleClock clock;
    private final UUID identity;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final SingleChronicleQueue queue;
    private final JournalFrontier frontier;
    private ExcerptAppender appender;
    private JournalPosition last;
    private long lastOrdinal;
    private int syncedCycle = Integer.MIN_VALUE;
    private boolean closed;
    private int retentionCycle = Integer.MIN_VALUE;

    public ChronicleQueueJournal(Path directory, ChronicleClock clock) {
        ModuleAccess.initialize();
        if (net.openhft.posix.PosixAPI.posix().getClass().getName().equals("net.openhft.posix.internal.noop.NoOpPosixAPI")) {
            Throwable cause = null;
            try { net.openhft.posix.PosixAPI.posix().getpid(); } catch (RuntimeException reason) { cause = reason; }
            throw new JournalException("Native POSIX implementation unavailable; refusing a no-op msync backend", cause);
        }
        if (!"false".equals(System.getProperty("background.releaser")))
            throw new JournalException("Require -Dbackground.releaser=false to observe release/sync failures", null);
        this.directory = directory;
        this.clock = clock;
        FileChannel channel = null;
        FileLock acquired = null;
        SingleChronicleQueue built = null;
        JournalFrontier openedFrontier = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("chronicle.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Journal is owned by another runtime");
            Path idFile = directory.resolve("chronicle.identity");
            if (!Files.exists(idFile)) {
                try (var files = Files.list(directory)) {
                    if (files.anyMatch(p -> p.toString().endsWith(".cq4") || p.toString().endsWith(".cq4t")))
                        throw new IOException("Existing journal has no Chronicle identity");
                }
                byte[] bytes = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                try (var idChannel = FileChannel.open(idFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) idChannel.write(buffer);
                    idChannel.force(true);
                }
            }
            identity = UUID.fromString(Files.readString(idFile));
            boolean queueExists;
            try (var files = Files.list(directory)) { queueExists = files.anyMatch(p -> p.toString().endsWith(".cq4t") || p.toString().endsWith(".cq4")); }
            openedFrontier = new JournalFrontier(directory, identity, queueExists);
            frontier = openedFrontier;
            // Make identity/frontier and their containing directories durable before acknowledgements.
            for (Path ancestor = directory.toAbsolutePath(); ancestor != null; ancestor = ancestor.getParent())
                JournalFrontier.syncDirectory(ancestor);
            built = guarded(() -> SingleChronicleQueueBuilder.binary(directory)
                    .rollCycle(RollCycles.FAST_HOURLY).blockSize(4 * 1024 * 1024)
                    .timeProvider(() -> clock.now().toEpochMilli())
                    .syncMode(SyncMode.SYNC).timeoutMS(1000).build());
            queue = built;
            last = new JournalPosition(guarded(queue::lastIndex));
            if (!last.equals(JournalPosition.NONE)) {
                try (var tailer = queue.createTailer()) {
                    if (!tailer.moveToIndex(last.value())) throw new IOException("Journal tail is unreadable");
                    try (var document = tailer.readingDocument()) { lastOrdinal = decode(document).ordinal(); }
                }
            }
            if (lastOrdinal < frontier.ordinal()) throw new IOException("Synced journal tail is missing");
            if (frontier.ordinal() > 0) {
                try (var tailer = queue.createTailer()) {
                    if (!tailer.moveToIndex(frontier.position())) throw new IOException("Synced append frontier is missing");
                    try (var document = tailer.readingDocument()) {
                        if (decode(document).ordinal() != frontier.ordinal()) throw new IOException("Append frontier does not match journal");
                    }
                }
            }
            lockChannel = channel;
            lock = acquired;
        } catch (Exception failure) {
            try { if (built != null) built.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
            try { if (openedFrontier != null) openedFrontier.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            try { if (acquired != null) acquired.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            try { if (channel != null) channel.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            throw new JournalException("Cannot open persistent journal " + directory, failure);
        }
    }

    @Override public UUID identity() { return identity; }
    @Override public synchronized JournalPosition lastPosition() { return last; }

    @Override public synchronized JournalPosition append(EncodedAuditEvent event) {
        return guarded(() -> {
            if (closed) throw new IllegalStateException("Journal closed");
            if (appender == null) appender = queue.createAppender();
            byte[] payload = event.bytes();
            CRC32C crc = new CRC32C();
            var frame = ByteBuffer.allocate(HEADER_BYTES + payload.length);
            Instant time = clock.now();
            frame.putInt(MAGIC).putInt(ENVELOPE_VERSION).putLong(event.id().value().getMostSignificantBits())
                    .putLong(event.id().value().getLeastSignificantBits()).putLong(time.getEpochSecond())
                    .putInt(time.getNano()).putLong(lastOrdinal + 1).putInt(payload.length).putInt(0).put(payload);
            crc.update(frame.array(), 0, CHECKSUM_OFFSET);
            crc.update(payload);
            frame.putInt(CHECKSUM_OFFSET, (int) crc.getValue());
            try (var document = appender.writingDocument()) { document.wire().bytes().write(frame.array()); }
            last = new JournalPosition(appender.lastIndexAppended());
            lastOrdinal++;
            return last;
        });
    }

    @Override public synchronized void sync() {
        guarded(() -> { if (appender != null) appender.sync(); return null; });
        try {
            int cycle = last.equals(JournalPosition.NONE) ? Integer.MIN_VALUE : RollCycles.FAST_HOURLY.toCycle(last.value());
            if (cycle != syncedCycle) JournalFrontier.syncDirectory(directory);
            frontier.sync(lastOrdinal, last.value());
            syncedCycle = cycle;
        } catch (IOException failure) {
            error.compareAndSet(null, failure);
            throw new JournalException("Cannot sync journal append frontier", failure);
        }
    }

    @Override public synchronized Cursor cursorAfter(JournalPosition committed, long terminalRecords) {
        return guarded(() -> {
            var tailer = queue.createTailer();
            try {
                if (!committed.equals(JournalPosition.NONE)) {
                    if (!tailer.moveToIndex(committed.value()))
                        throw new IllegalStateException("Committed checkpoint is missing from journal: " + committed);
                    try (var document = tailer.readingDocument()) {
                        if (!document.isPresent() || decode(document).ordinal() != terminalRecords)
                            throw new IllegalStateException("Checkpoint position/count disagree");
                    }
                } else if (terminalRecords != 0) throw new IllegalStateException("Invalid empty checkpoint count");
                return new QueueCursor(tailer, terminalRecords + 1);
            } catch (RuntimeException failure) { tailer.close(); throw failure; }
        });
    }

    @Override public synchronized long backlog(long terminalRecords) {
        if (terminalRecords > lastOrdinal) throw new JournalException("Database checkpoint exceeds journal tail", null);
        return lastOrdinal - terminalRecords;
    }

    @Override public long diskBytes() {
        try (var files = Files.list(directory)) {
            long size = 0;
            for (var file : files.filter(p -> p.toString().endsWith(".cq4")).toList()) size += Files.size(file);
            return size;
        } catch (IOException failure) { throw new JournalException("Cannot inspect journal disk usage", failure); }
    }

    @Override public synchronized void retain(JournalPosition committed, int recentCycles) {
        if (committed.equals(JournalPosition.NONE)) return;
        int cycleNow = RollCycles.FAST_HOURLY.toCycle(committed.value());
        if (cycleNow == retentionCycle) return;
        int frontierCycle = frontier.ordinal() == 0 ? Integer.MIN_VALUE : RollCycles.FAST_HOURLY.toCycle(frontier.position());
        long threshold = (long) Math.min(cycleNow, frontierCycle) - recentCycles;
        // FileUtil checks mapped resources; serialization prevents a new cursor mapping during deletion.
        try (var candidates = FileUtil.removableRollFileCandidates(directory.toFile())) {
            for (var candidate : candidates.toList()) {
                String name = candidate.getName();
                var date = LocalDateTime.parse(name.substring(0, name.length() - 4),
                        DateTimeFormatter.ofPattern(RollCycles.FAST_HOURLY.format()));
                long cycle = date.toEpochSecond(ZoneOffset.UTC) / 3600;
                if (cycle < threshold) Files.delete(candidate.toPath());
            }
        } catch (IOException failure) { throw new JournalException("Committed-cycle retention failed", failure); }
        retentionCycle = cycleNow;
    }

    private <T> T guarded(Supplier<T> action) {
        if (error.get() != null) throw new JournalException("Journal previously reported an unsafe error", error.get());
        return observed(action);
    }
    private <T> T observed(Supplier<T> action) {
        Jvm.setThreadLocalExceptionHandlers((logger, message, cause) -> {
            error.compareAndSet(null, new IOException(message, cause));
            Slf4jExceptionHandler.ERROR.on(logger, message, cause);
        }, Slf4jExceptionHandler.WARN, Slf4jExceptionHandler.DEBUG);
        try {
            T result = action.get();
            if (error.get() != null) throw new JournalException("Journal operation/flush failed", error.get());
            return result;
        } catch (JournalException failure) { throw failure; }
        catch (RuntimeException failure) {
            error.compareAndSet(null, failure);
            throw new JournalException("Journal operation/framing failed", failure);
        } finally { Jvm.setThreadLocalExceptionHandlers(null, null, null); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        Throwable failure = null;
        for (Runnable cleanup : new Runnable[]{this::sync,
                () -> { if (appender != null) observed(() -> { appender.close(); return null; }); },
                () -> observed(() -> { queue.close(); return null; })}) {
            try { cleanup.run(); } catch (Throwable closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
        }
        closed = true;
        try { frontier.close(); } catch (IOException close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
        try { lock.close(); } catch (IOException close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
        try { lockChannel.close(); } catch (IOException close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
        if (failure != null) throw new JournalException("Journal close/flush was not clean", failure);
    }

    private final class QueueCursor implements Cursor {
        private final ExcerptTailer tailer;
        private long expected;
        private QueueCursor(ExcerptTailer tailer, long expected) { this.tailer = tailer; this.expected = expected; }
        @Override public JournalRecord next() {
            synchronized (ChronicleQueueJournal.this) {
                return guarded(() -> {
                    try (var document = tailer.readingDocument()) {
                        if (!document.isPresent()) {
                            if (lastOrdinal >= expected) throw new IllegalStateException("Missing appended journal record " + expected);
                            return null;
                        }
                        var record = decode(document);
                        if (record.ordinal() != expected) throw new IllegalStateException("Missing/reordered journal record: expected " + expected + " found " + record.ordinal());
                        expected++;
                        return record;
                    }
                });
            }
        }
        @Override public void close() {
            synchronized (ChronicleQueueJournal.this) { observed(() -> { tailer.close(); return null; }); }
        }
    }

    private static JournalRecord decode(net.openhft.chronicle.wire.DocumentContext document) {
        if (!document.isPresent()) throw new IllegalStateException("Missing journal record");
        var source = document.wire().bytes();
        byte[] header = new byte[HEADER_BYTES];
        if (source.readRemaining() < HEADER_BYTES) throw new IllegalStateException("Truncated journal envelope");
        source.read(header);
        var buffer = ByteBuffer.wrap(header);
        if (buffer.getInt() != MAGIC || buffer.getInt() != ENVELOPE_VERSION) throw new IllegalStateException("Corrupt/unsupported journal envelope");
        var id = new EventId(new UUID(buffer.getLong(), buffer.getLong()));
        long seconds = buffer.getLong();
        int nanos = buffer.getInt();
        if (nanos < 0 || nanos > 999_999_999) throw new IllegalStateException("Corrupt journal time");
        long ordinal = buffer.getLong();
        int length = buffer.getInt();
        int expectedCrc = buffer.getInt();
        if (ordinal < 1 || length < 0 || length > EncodedAuditEvent.MAX_BYTES || length > source.readRemaining())
            throw new IllegalStateException("Corrupt journal ordinal/length");
        byte[] payload = new byte[length]; source.read(payload);
        CRC32C crc = new CRC32C(); crc.update(header, 0, CHECKSUM_OFFSET); crc.update(payload);
        if ((int) crc.getValue() != expectedCrc) throw new IllegalStateException("Corrupt journal checksum");
        return new JournalRecord(new JournalPosition(document.index()), ordinal, Instant.ofEpochSecond(seconds, nanos), new EncodedAuditEvent(id, payload));
    }
}
