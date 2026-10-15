/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.event.EventId;
import io.github.andriyko69.chronicle.journal.JournalPosition;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.util.List;
import java.util.UUID;

public interface StorageWriter extends AutoCloseable {
    record Checkpoint(JournalPosition position, long terminalRecords, int schemaVersion) {}
    record CommitResult(Checkpoint checkpoint, int insertedEvents, int insertedDeadLetters) {}
    Checkpoint checkpoint() throws StorageFailure;
    /** Commit the whole contiguous terminal prefix and checkpoint atomically, including replay. */
    CommitResult commit(Checkpoint expected, List<TerminalRecord> batch) throws StorageFailure;
    /** Atomically sets the rollback state of every listed audit event; fails without changes if any is missing. */
    default void setRolledBack(List<EventId> ids, boolean rolledBack) throws StorageFailure { throw new UnsupportedOperationException("Rollback not supported by storage backend"); }
    /** Keyset position in {@code actor_profiles}' primary-key order. */
    record ProfileKey(String actorId, UUID actorUuid) {}
    /** {@code next} is null once the last profile was examined. */
    record ProfilePage(int examined, int deleted, ProfileKey next) {}
    /** Deletes, in one transaction, at most {@code limit} audit rows (new and legacy) from seconds before {@code cutoffSec}. */
    default int purgeEvents(long cutoffSec, int limit) throws StorageFailure { throw new UnsupportedOperationException("Purge not supported by storage backend"); }
    /** Deletes the profiles no remaining row names as actor or root among at most {@code limit} after {@code after}. */
    default ProfilePage pruneProfiles(ProfileKey after, int limit) throws StorageFailure { throw new UnsupportedOperationException("Purge not supported by storage backend"); }
    /** Returns whether the WAL was fully checkpointed and truncated; readers holding old snapshots can prevent that. */
    default boolean optimize() throws StorageFailure { throw new UnsupportedOperationException("Purge not supported by storage backend"); }
    @Override void close() throws StorageFailure;
}
