/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.TreeMap;

public final class BlockDeltaPayloadCodec {
    public static final int MAX_BYTES = 128 * 1024;
    public byte[] encode(BlockDeltaPayload payload) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(BlockDeltaPayload.VERSION);
            output.writeByte(payload.observation().ordinal()); output.writeByte(payload.snapshotStatus().ordinal());
            state(output, payload.before()); state(output, payload.after());
            byte[] nbt = payload.snapshotNbt();
            output.writeInt(nbt == null ? 0 : nbt.length);
            if (nbt != null) output.write(nbt);
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Block payload too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public BlockDeltaPayload decode(int version, byte[] bytes) {
        try {
            if (version != BlockDeltaPayload.VERSION || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Unsupported/oversized block payload");
            var input = ByteBuffer.wrap(bytes);
            if (input.getInt() != version) throw new IllegalArgumentException("Payload version mismatch");
            var observation = BlockDeltaPayload.Observation.values()[Byte.toUnsignedInt(input.get())];
            var status = BlockDeltaPayload.SnapshotStatus.values()[Byte.toUnsignedInt(input.get())];
            var before = state(input); var after = state(input);
            byte[] nbt = BinaryValues.bytes(input, BlockDeltaPayload.MAX_NBT_BYTES);
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing block payload bytes");
            return new BlockDeltaPayload(before, after, observation, status, nbt.length == 0 ? null : nbt);
        } catch (Exception failure) { throw new IllegalArgumentException("Malformed block payload", failure); }
    }
    private static void state(DataOutputStream output, BlockStateSnapshot state) throws IOException {
        BinaryValues.text(output, state.blockId()); output.writeInt(state.properties().size());
        for (var entry : state.properties().entrySet()) { BinaryValues.text(output, entry.getKey()); BinaryValues.text(output, entry.getValue()); }
    }
    private static BlockStateSnapshot state(ByteBuffer input) throws IOException {
        String id = BinaryValues.text(input, 512);
        int count = input.getInt();
        if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid property count");
        var properties = new TreeMap<String, String>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String name = BinaryValues.text(input, 256); String value = BinaryValues.text(input, 512);
            if (previous != null && previous.compareTo(name) >= 0) throw new IllegalArgumentException("Noncanonical properties");
            properties.put(name, value); previous = name;
        }
        return new BlockStateSnapshot(id, properties);
    }
}
