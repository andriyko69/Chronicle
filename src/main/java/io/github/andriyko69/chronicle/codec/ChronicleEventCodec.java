/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.AuditEvent;
import io.github.andriyko69.chronicle.event.EventId;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Canonical big-endian format: magic, version, UUID, seconds+nanos, UTF-8 length+bytes. */
public final class ChronicleEventCodec {
    private static final int MAGIC = 0x43485245;
    public EncodedAuditEvent encode(AuditEvent event) {
        byte[] value = event.value().getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(40 + value.length);
        buffer.putInt(MAGIC).putInt(1).putLong(event.id().value().getMostSignificantBits())
                .putLong(event.id().value().getLeastSignificantBits())
                .putLong(event.occurredAt().getEpochSecond()).putInt(event.occurredAt().getNano())
                .putInt(value.length).put(value);
        return new EncodedAuditEvent(event.id(), buffer.array());
    }

    public AuditEvent decode(EncodedAuditEvent encoded) throws InvalidEventException {
        try {
            var buffer = ByteBuffer.wrap(encoded.bytes());
            if (buffer.getInt() != MAGIC) throw new IllegalArgumentException("Invalid event magic");
            int version = buffer.getInt();
            if (version != 1) throw new IllegalArgumentException("Unsupported event version " + version);
            EventId id = new EventId(new UUID(buffer.getLong(), buffer.getLong()));
            long seconds = buffer.getLong();
            int nanos = buffer.getInt();
            if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException("Invalid nanos");
            Instant time = Instant.ofEpochSecond(seconds, nanos);
            int size = buffer.getInt();
            if (size < 0 || size > AuditEvent.MAX_VALUE_BYTES || size != buffer.remaining())
                throw new IllegalArgumentException("Invalid payload length");
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString();
            if (!id.equals(encoded.id())) throw new IllegalArgumentException("Envelope/event ID mismatch");
            return new AuditEvent(id, time, value);
        } catch (Exception failure) {
            throw new InvalidEventException("Malformed/unsupported synthetic event: " + failure.getMessage(), failure);
        }
    }

    public static final class InvalidEventException extends Exception {
        public InvalidEventException(String message, Throwable cause) { super(message, cause); }
    }
}
