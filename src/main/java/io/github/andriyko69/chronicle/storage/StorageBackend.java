/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.util.UUID;

/** Factories carry immutable settings, not shared live connections. All calls run on workers. */
public interface StorageBackend {
    StorageWriter openWriter(UUID journalIdentity) throws StorageFailure;
    StorageReader openReader() throws StorageFailure;
}
