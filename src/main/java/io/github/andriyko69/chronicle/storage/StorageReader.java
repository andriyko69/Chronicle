/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.diagnostics.StorageTotals;
import java.util.List;

public interface StorageReader extends AutoCloseable {
    List<AuditEvent> recent(int limit) throws StorageFailure;
    default LookupPage lookup(LookupSpec spec, int page) throws StorageFailure { throw new UnsupportedOperationException("Lookup not supported by storage backend"); }
    default StorageTotals totals() throws StorageFailure { throw new UnsupportedOperationException("Totals not supported by storage backend"); }
    @Override void close() throws StorageFailure;
}
