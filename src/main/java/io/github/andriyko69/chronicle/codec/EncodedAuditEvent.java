/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.EventId;
import java.util.Objects;

/** Envelope identity remains readable when the payload version is unsupported. */
public final class EncodedAuditEvent {
    public static final int MAX_BYTES = 8192;
    private final EventId id;
    private final byte[] bytes;
    public EncodedAuditEvent(EventId id, byte[] bytes) {
        this.id = Objects.requireNonNull(id);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Encoded event too large");
        this.bytes = bytes.clone();
    }
    public EventId id() { return id; }
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
}
