/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.codec;

import io.github.andriyko69.chronicle.event.BlockDeltaPayload;
import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;

public record SnapshotNbt(BlockDeltaPayload.SnapshotStatus status, byte[] bytes) {
    public SnapshotNbt {
        if ((status == BlockDeltaPayload.SnapshotStatus.PRESENT) != (bytes != null))
            throw new IllegalArgumentException("Snapshot status/bytes mismatch");
        if (bytes != null) {
            if (bytes.length < 1 || bytes.length > BlockDeltaPayload.MAX_NBT_BYTES) throw new IllegalArgumentException("Snapshot too large/empty");
            bytes = bytes.clone();
        }
    }
    @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    @FunctionalInterface public interface Writer { void write(DataOutput output) throws IOException; }
    private static final class CeilingExceeded extends IOException {}

    public static SnapshotNbt capture(Writer writer) {
        var bytes = new ByteArrayOutputStream();
        var bounded = new OutputStream() {
            private void reserve(int length) throws CeilingExceeded {
                if (length > BlockDeltaPayload.MAX_NBT_BYTES - bytes.size()) throw new CeilingExceeded();
            }
            @Override public void write(int value) throws IOException { reserve(1); bytes.write(value); }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                reserve(length); bytes.write(value, offset, length);
            }
        };
        try {
            writer.write(new DataOutputStream(bounded));
            return new SnapshotNbt(BlockDeltaPayload.SnapshotStatus.PRESENT, bytes.toByteArray());
        } catch (CeilingExceeded | UTFDataFormatException oversized) {
            return new SnapshotNbt(BlockDeltaPayload.SnapshotStatus.OVERSIZED, null);
        } catch (IOException | RuntimeException failure) {
            return new SnapshotNbt(BlockDeltaPayload.SnapshotStatus.FAILED, null);
        }
    }
    public static SnapshotNbt none() { return new SnapshotNbt(BlockDeltaPayload.SnapshotStatus.NONE, null); }
    public static SnapshotNbt failed() { return new SnapshotNbt(BlockDeltaPayload.SnapshotStatus.FAILED, null); }
}
