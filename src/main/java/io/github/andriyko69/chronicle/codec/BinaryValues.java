/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class BinaryValues {
    private BinaryValues() {}
    static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    static String text(ByteBuffer input, int maximumBytes) throws IOException {
        byte[] bytes = bytes(input, maximumBytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static byte[] bytes(ByteBuffer input, int maximum) {
        int size = input.getInt();
        if (size < 0 || size > maximum || size > input.remaining()) throw new IllegalArgumentException("Invalid byte length");
        byte[] bytes = new byte[size]; input.get(bytes); return bytes;
    }
    static void uuid(DataOutputStream output, UUID value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) { output.writeLong(value.getMostSignificantBits()); output.writeLong(value.getLeastSignificantBits()); }
    }
    static UUID uuid(ByteBuffer input) {
        byte present = input.get();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("Invalid UUID presence");
        return new UUID(input.getLong(), input.getLong());
    }
}
