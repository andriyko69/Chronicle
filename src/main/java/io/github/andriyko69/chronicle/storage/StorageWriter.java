/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.journal.JournalPosition;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.util.List;

public interface StorageWriter extends AutoCloseable {
    record Checkpoint(JournalPosition position, long terminalRecords, int schemaVersion) {}
    record CommitResult(Checkpoint checkpoint, int insertedEvents, int insertedDeadLetters) {}
    Checkpoint checkpoint() throws StorageFailure;
    /** Commit the whole contiguous terminal prefix and checkpoint atomically, including replay. */
    CommitResult commit(Checkpoint expected, List<TerminalRecord> batch) throws StorageFailure;
    @Override void close() throws StorageFailure;
}
