/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal;

import io.github.andriyko69.chronicle.codec.EncodedAuditEvent;
import java.time.Instant;

public record JournalRecord(JournalPosition position, long ordinal, Instant journaledAt, EncodedAuditEvent event) {}
