/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.util.List;

public interface StorageReader extends AutoCloseable {
    List<AuditEvent> recent(int limit) throws StorageFailure;
    @Override void close() throws StorageFailure;
}
