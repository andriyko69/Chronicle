/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage;

import io.github.andriyko69.chronicle.dlq.DeadLetterRecord;
import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.journal.JournalRecord;

public record TerminalRecord(JournalRecord journal, AuditEvent event, DeadLetterRecord deadLetter) {
    public TerminalRecord {
        if (journal == null || (event == null) == (deadLetter == null))
            throw new IllegalArgumentException("Exactly one terminal outcome is required");
        if (event != null && !java.util.Arrays.equals(new io.github.andriyko69.chronicle.codec.ChronicleEventCodec()
                .encode(event).bytes(), journal.event().bytes()))
            throw new IllegalArgumentException("Terminal domain value and canonical journal bytes disagree");
        if (deadLetter != null && deadLetter.classification() != io.github.andriyko69.chronicle.persistence.StorageFailure.Kind.PERMANENT_EVENT)
            throw new IllegalArgumentException("Only permanent event failures can be dead-lettered");
    }
}
