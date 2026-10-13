/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public record AuditEvent(EventId id, Instant occurredAt, String value) {
    public static final int MAX_VALUE_BYTES = 4096;
    public AuditEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(value);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Synthetic value exceeds 4096 UTF-8 bytes");
        // Reject unpaired surrogates rather than silently encoding replacement characters.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException("Invalid Unicode value");
    }
}
