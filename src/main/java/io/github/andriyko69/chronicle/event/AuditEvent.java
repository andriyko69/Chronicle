/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record AuditEvent(EventId id, Instant occurredAt, EventEnvelope envelope, int payloadVersion, byte[] payload) {
    public static final int MAX_VALUE_BYTES = 4096;
    public AuditEvent {
        Objects.requireNonNull(id); Objects.requireNonNull(occurredAt); Objects.requireNonNull(payload);
        payload = payload.clone();
        if (envelope == null) {
            if (payloadVersion != 1 || payload.length > MAX_VALUE_BYTES)
                throw new IllegalArgumentException("Invalid legacy payload");
        } else {
            switch (envelope.eventType()) {
                case "BLOCK_BREAK", "BLOCK_PLACE" -> new BlockDeltaPayloadCodec().decode(payloadVersion, payload);
                case "ITEM_TAKE", "ITEM_PUT" -> new ItemTransactionPayloadCodec().decode(payloadVersion, payload);
                default -> throw new IllegalArgumentException("Unsupported event type " + envelope.eventType());
            }
        }
    }
    public AuditEvent(EventId id, Instant occurredAt, String value) {
        this(id, occurredAt, null, 1, legacyBytes(value));
    }
    public static AuditEvent block(EventId id, Instant occurredAt, EventEnvelope envelope, BlockDeltaPayload delta) {
        return new AuditEvent(id, occurredAt, Objects.requireNonNull(envelope), BlockDeltaPayload.VERSION,
                new BlockDeltaPayloadCodec().encode(delta));
    }
    public static AuditEvent item(EventId id, Instant occurredAt, EventEnvelope envelope, ItemTransactionPayload transaction) {
        if (!envelope.eventType().equals("ITEM_TAKE") && !envelope.eventType().equals("ITEM_PUT"))
            throw new IllegalArgumentException("Expected item event type");
        return new AuditEvent(id, occurredAt, envelope, transaction.version(), new ItemTransactionPayloadCodec().encode(transaction));
    }
    private static byte[] legacyBytes(String value) {
        Objects.requireNonNull(value);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IllegalArgumentException("Invalid Unicode value");
        return value.getBytes(StandardCharsets.UTF_8);
    }
    public boolean legacy() { return envelope == null; }
    public String value() {
        if (!legacy()) throw new IllegalStateException("Domain event has no synthetic value");
        return new String(payload, StandardCharsets.UTF_8);
    }
    @Override public byte[] payload() { return payload.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof AuditEvent value && id.equals(value.id) && occurredAt.equals(value.occurredAt)
                && Objects.equals(envelope, value.envelope) && payloadVersion == value.payloadVersion
                && Arrays.equals(payload, value.payload);
    }
    @Override public int hashCode() { return 31 * Objects.hash(id, occurredAt, envelope, payloadVersion) + Arrays.hashCode(payload); }
}
