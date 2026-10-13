/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.TreeMap;

public final class ItemTransactionPayloadCodec {
    public static final int MAX_BYTES = 64 * 1024;
    public byte[] encode(ItemTransactionPayload payload) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(ItemTransactionPayload.VERSION); output.writeInt(payload.count());
            BinaryValues.text(output, payload.containerBlockId());
            output.writeBoolean(payload.customName() != null);
            if (payload.customName() != null) BinaryValues.text(output, payload.customName());
            output.writeInt(payload.enchantments().size());
            for (var entry : payload.enchantments().entrySet()) {
                BinaryValues.text(output, entry.getKey()); output.writeInt(entry.getValue());
            }
            output.writeInt(payload.damage() == null ? 0 : payload.damage());
            output.writeByte(payload.componentSnapshotStatus().ordinal());
            byte[] components = payload.componentBytes();
            output.writeInt(components == null ? 0 : components.length);
            if (components != null) output.write(components);
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Item payload too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public ItemTransactionPayload decode(int version, byte[] bytes) {
        try {
            if (version != ItemTransactionPayload.VERSION || bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("Unsupported/oversized item payload");
            var input = ByteBuffer.wrap(bytes);
            if (input.getInt() != version) throw new IllegalArgumentException("Item payload version mismatch");
            int count = input.getInt();
            String container = BinaryValues.text(input, 128);
            byte present = input.get();
            if (present != 0 && present != 1) throw new IllegalArgumentException("Invalid name presence");
            String name = present == 1 ? BinaryValues.text(input, ItemTransactionPayload.MAX_NAME_BYTES) : null;
            int size = input.getInt();
            if (size < 0 || size > ItemTransactionPayload.MAX_ENCHANTMENTS) throw new IllegalArgumentException("Invalid enchantment count");
            var enchantments = new TreeMap<String, Integer>();
            String previous = null;
            for (int i = 0; i < size; i++) {
                String id = BinaryValues.text(input, 128);
                if (previous != null && previous.compareTo(id) >= 0) throw new IllegalArgumentException("Noncanonical enchantments");
                enchantments.put(id, input.getInt()); previous = id;
            }
            int damage = input.getInt();
            var status = ItemTransactionPayload.ComponentSnapshotStatus.values()[Byte.toUnsignedInt(input.get())];
            byte[] components = BinaryValues.bytes(input, ItemTransactionPayload.MAX_COMPONENT_BYTES);
            if (input.hasRemaining()) throw new IllegalArgumentException("Trailing item payload bytes");
            return new ItemTransactionPayload(count, container, name, enchantments, damage == 0 ? null : damage, status,
                    components.length == 0 ? null : components);
        } catch (Exception failure) { throw new IllegalArgumentException("Malformed item payload", failure); }
    }
}
