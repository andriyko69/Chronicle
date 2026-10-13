/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.dlq;

import io.github.andriyko69.chronicle.persistence.StorageFailure;
import java.time.Instant;

public record DeadLetterRecord(Instant firstFailure, Instant lastFailure, StorageFailure.Kind classification,
        String message, String backendCode, String sqlState, int attempts) {
    public static DeadLetterRecord malformed(Instant now, String message) {
        return new DeadLetterRecord(now, now, StorageFailure.Kind.PERMANENT_EVENT, message, null, null, 1);
    }
}
