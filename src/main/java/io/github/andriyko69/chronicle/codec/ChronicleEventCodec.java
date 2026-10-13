/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

/** Big-endian framing; version 1 remains byte-compatible with pending synthetic journals. */
public final class ChronicleEventCodec {
    private static final int MAGIC = 0x43485245;
    public EncodedAuditEvent encode(AuditEvent event) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(MAGIC); output.writeInt(event.legacy() ? 1 : 2);
            output.writeLong(event.id().value().getMostSignificantBits()); output.writeLong(event.id().value().getLeastSignificantBits());
            output.writeLong(event.occurredAt().getEpochSecond()); output.writeInt(event.occurredAt().getNano());
            if (!event.legacy()) {
                var envelope = event.envelope();
                BinaryValues.text(output, envelope.worldId());
                output.writeInt(envelope.x()); output.writeInt(envelope.y()); output.writeInt(envelope.z());
                BinaryValues.text(output, envelope.eventType()); BinaryValues.text(output, envelope.actorType());
                BinaryValues.text(output, envelope.actorId()); BinaryValues.uuid(output, envelope.actorUuid());
                BinaryValues.uuid(output, envelope.rootActorUuid()); BinaryValues.text(output, envelope.targetId());
                output.writeInt(event.payloadVersion());
            }
            byte[] payload = event.payload(); output.writeInt(payload.length); output.write(payload);
            return new EncodedAuditEvent(event.id(), bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public AuditEvent decode(EncodedAuditEvent encoded) throws InvalidEventException {
        try {
            var input = ByteBuffer.wrap(encoded.bytes());
            if (input.getInt() != MAGIC) throw new IllegalArgumentException("Invalid event magic");
            int version = input.getInt();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported event version " + version);
            EventId id = new EventId(new UUID(input.getLong(), input.getLong()));
            long seconds = input.getLong(); int nanos = input.getInt();
            if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException("Invalid nanos");
            Instant time = Instant.ofEpochSecond(seconds, nanos);
            AuditEvent event;
            if (version == 1) event = new AuditEvent(id, time, BinaryValues.text(input, AuditEvent.MAX_VALUE_BYTES));
            else {
                var envelope = new EventEnvelope(BinaryValues.text(input, 512), input.getInt(), input.getInt(), input.getInt(),
                        BinaryValues.text(input, 128), BinaryValues.text(input, 128), BinaryValues.text(input, 512),
                        BinaryValues.uuid(input), BinaryValues.uuid(input), BinaryValues.text(input, 512));
                int payloadVersion = input.getInt();
                event = new AuditEvent(id, time, envelope, payloadVersion, BinaryValues.bytes(input, BlockDeltaPayloadCodec.MAX_BYTES));
            }
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing event bytes");
            if (!id.equals(encoded.id())) throw new IllegalArgumentException("Envelope/event ID mismatch");
            return event;
        } catch (Exception failure) {
            throw new InvalidEventException("Malformed/unsupported event: " + failure.getMessage(), failure);
        }
    }
    public static final class InvalidEventException extends Exception {
        public InvalidEventException(String message, Throwable cause) { super(message, cause); }
    }
}
