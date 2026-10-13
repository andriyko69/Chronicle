/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal;

import io.github.andriyko69.chronicle.codec.EncodedAuditEvent;
import java.util.UUID;

/** Append/sync belong to one journal worker; each cursor belongs to its creating worker. */
public interface AuditJournal extends AutoCloseable {
    UUID identity();
    JournalPosition append(EncodedAuditEvent event);
    void sync();
    JournalPosition lastPosition();
    long backlog(long terminalRecords);
    long diskBytes();
    Cursor cursorAfter(JournalPosition committed, long terminalRecords);
    void retain(JournalPosition committed, int recentCycles);
    @Override void close();

    interface Cursor extends AutoCloseable {
        /** Null means no complete record available yet; it never acknowledges anything. */
        JournalRecord next();
        @Override void close();
    }
}
