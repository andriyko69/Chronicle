/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.diagnostics.StorageTotals;
import java.time.Instant;
import java.util.List;

public interface StorageReader extends AutoCloseable {
    List<AuditEvent> recent(int limit) throws StorageFailure;
    default LookupPage lookup(LookupSpec spec, int page) throws StorageFailure { throw new UnsupportedOperationException("Lookup not supported by storage backend"); }
    /** At most {@code limit} rows matching {@code spec} in the given rollback state, in the order they must be applied. */
    default List<StoredEvent> candidates(LookupSpec spec, boolean rolledBack, int limit) throws StorageFailure { throw new UnsupportedOperationException("Rollback not supported by storage backend"); }
    default List<StoredEvent> position(String worldId, int x, int y, int z, Instant from, int limit, int offset) throws StorageFailure { throw new UnsupportedOperationException("Rollback not supported by storage backend"); }
    default StorageTotals totals() throws StorageFailure { throw new UnsupportedOperationException("Totals not supported by storage backend"); }
    /** Audit rows (new and legacy) a purge with this cutoff would delete. */
    default long purgeable(long cutoffSec) throws StorageFailure { throw new UnsupportedOperationException("Purge not supported by storage backend"); }
    @Override void close() throws StorageFailure;
}
