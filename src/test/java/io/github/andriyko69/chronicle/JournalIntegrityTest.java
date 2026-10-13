/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.ChronicleEventCodec;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.journal.*;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JournalIntegrityTest {
    @TempDir Path directory;
    private final ChronicleEventCodec codec = new ChronicleEventCodec();
    private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
    private final ChronicleClock clock = time::get;

    private List<Path> cycles() throws Exception {
        try (var paths = Files.list(directory)) { return paths.filter(path -> path.toString().endsWith(".cq4")).sorted().toList(); }
    }
    private JournalPosition seed(int count) {
        JournalPosition last = JournalPosition.NONE;
        try (var journal = new ChronicleQueueJournal(directory, clock)) {
            for (int i = 0; i < count; i++) {
                last = journal.append(codec.encode(PersistenceTest.event(i)));
                journal.sync(); time.set(time.get().plusSeconds(3600));
            }
        }
        return last;
    }
    @Test void missingMiddleCycleCannotBeSilentlySkipped() throws Exception {
        seed(3);
        Files.delete(cycles().get(1));
        var journal = new ChronicleQueueJournal(directory, clock);
        var cursor = journal.cursorAfter(JournalPosition.NONE, 0);
        assertEquals(1, cursor.next().ordinal());
        assertThrows(JournalException.class, cursor::next);
        assertThrows(JournalException.class, cursor::close);
        assertThrows(JournalException.class, journal::close);
    }
    @Test void missingSyncedTrailingCycleIsDetectedAtOpen() throws Exception {
        seed(3); Files.delete(cycles().get(2));
        assertThrows(JournalException.class, () -> new ChronicleQueueJournal(directory, clock));
    }
    @Test void retentionPreservesCheckpointUncommittedAndRecentCycles() throws Exception {
        JournalPosition last = seed(5);
        try (var journal = new ChronicleQueueJournal(directory, clock)) {
            var cursor = journal.cursorAfter(JournalPosition.NONE, 0);
            var first = cursor.next(); var second = cursor.next();
            cursor.close();
            journal.retain(second.position(), 0);
            assertEquals(4, cycles().size(), "Only first committed cycle is removable");
            try (var remaining = journal.cursorAfter(second.position(), 2)) {
                assertEquals(3, remaining.next().ordinal()); assertEquals(4, remaining.next().ordinal());
                assertEquals(5, remaining.next().ordinal()); assertNull(remaining.next());
            }
        }
        try (var journal = new ChronicleQueueJournal(directory, clock)) {
            journal.retain(last, 1);
            assertEquals(2, cycles().size());
            try (var cursor = journal.cursorAfter(last, 5)) { assertNull(cursor.next()); }
            journal.append(codec.encode(PersistenceTest.event(6))); journal.sync();
        }
        try (var journal = new ChronicleQueueJournal(directory, clock); var cursor = journal.cursorAfter(last, 5)) {
            assertEquals(6, cursor.next().ordinal()); assertNull(cursor.next());
        }
    }
    @Test void corruptEnvelopeIsFatalRatherThanDlq() throws Exception {
        seed(1);
        Path cycle = cycles().getFirst(); byte[] bytes = Files.readAllBytes(cycle);
        int position = -1;
        for (int i = 0; i < bytes.length - 56; i++) {
            if (bytes[i] == 0x43 && bytes[i + 1] == 0x48 && bytes[i + 2] == 0x4a && bytes[i + 3] == 0x31) { position = i; break; }
        }
        assertTrue(position >= 0); bytes[position + 52] ^= 1; Files.write(cycle, bytes);
        assertThrows(JournalException.class, () -> new ChronicleQueueJournal(directory, clock));
    }
    @Test void processLockAndCheckpointCountAreEnforced() {
        seed(1);
        var journal = new ChronicleQueueJournal(directory, clock);
        assertThrows(JournalException.class, () -> new ChronicleQueueJournal(directory, clock));
        assertThrows(JournalException.class, () -> journal.cursorAfter(journal.lastPosition(), 0));
        assertThrows(JournalException.class, journal::close);
    }
    @Test void nativeFlushFailureIsCapturedAndCannotBecomeSuccessfulClose() {
        var journal = new ChronicleQueueJournal(directory, clock);
        journal.append(codec.encode(PersistenceTest.event(1)));
        var original = net.openhft.posix.PosixAPI.posix();
        net.openhft.posix.internal.PosixAPIHolder.POSIX_API = (net.openhft.posix.PosixAPI) java.lang.reflect.Proxy.newProxyInstance(
                net.openhft.posix.PosixAPI.class.getClassLoader(), new Class<?>[]{net.openhft.posix.PosixAPI.class},
                (proxy, method, arguments) -> method.getName().equals("msync") ? -1 : method.invoke(original, arguments));
        try { assertThrows(JournalException.class, journal::sync); }
        finally { net.openhft.posix.internal.PosixAPIHolder.POSIX_API = original; }
        assertThrows(JournalException.class, journal::close);
    }
    @Test void nativeNoOpFallbackIsRefused() {
        var original = net.openhft.posix.PosixAPI.posix();
        net.openhft.posix.internal.PosixAPIHolder.POSIX_API = new net.openhft.posix.internal.noop.NoOpPosixAPI("test unavailable native library");
        try { assertThrows(JournalException.class, () -> new ChronicleQueueJournal(directory, clock)); }
        finally { net.openhft.posix.internal.PosixAPIHolder.POSIX_API = original; }
    }
    @Test void tornFrontierSlotFallsBackToPreviousSyncedSlot() throws Exception {
        seed(2);
        Path file = directory.resolve("chronicle.frontier"); byte[] bytes = Files.readAllBytes(file);
        bytes[40] ^= 1; Files.write(file, bytes); // Latest slot after two syncs; the other slot remains valid.
        try (var journal = new ChronicleQueueJournal(directory, clock); var cursor = journal.cursorAfter(JournalPosition.NONE, 0)) {
            assertEquals(1, cursor.next().ordinal()); assertEquals(2, cursor.next().ordinal()); assertNull(cursor.next());
        }
    }
}
