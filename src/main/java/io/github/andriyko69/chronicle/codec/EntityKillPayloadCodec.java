/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.EntityKillPayload;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class EntityKillPayloadCodec {
    public static final int MAX_BYTES = 4096;
    public byte[] encode(EntityKillPayload payload) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(EntityKillPayload.VERSION);
            BinaryValues.text(output, payload.entityTypeId());
            optional(output, payload.customName());
            BinaryValues.uuid(output, payload.ownerUuid());
            optional(output, payload.ownerName());
            optional(output, payload.detail());
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Entity kill payload too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public EntityKillPayload decode(int version, byte[] bytes) {
        try {
            if (version != EntityKillPayload.VERSION || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Unsupported/oversized entity kill payload");
            var input = ByteBuffer.wrap(bytes);
            if (input.getInt() != version) throw new IllegalArgumentException("Entity kill payload version mismatch");
            String type = BinaryValues.text(input, 128);
            String name = optional(input, EntityKillPayload.MAX_NAME_BYTES);
            var ownerUuid = BinaryValues.uuid(input);
            String ownerName = optional(input, EntityKillPayload.MAX_OWNER_BYTES);
            String detail = optional(input, EntityKillPayload.MAX_DETAIL_BYTES);
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing entity kill payload bytes");
            return new EntityKillPayload(type, name, ownerUuid, ownerName, detail);
        } catch (Exception failure) { throw new IllegalArgumentException("Malformed entity kill payload", failure); }
    }
    private static void optional(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) BinaryValues.text(output, value);
    }
    private static String optional(ByteBuffer input, int maximumBytes) throws IOException {
        byte present = input.get();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("Invalid text presence");
        return BinaryValues.text(input, maximumBytes);
    }
}
