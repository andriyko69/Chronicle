/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import io.github.andriyko69.chronicle.event.ItemTransactionPayload.ComponentSnapshotStatus;
import java.io.*;
import java.security.*;
import java.util.*;

/** Retains at most 16 KiB while hashing the complete encoding, so oversized patches stay distinct. */
public record ComponentSnapshot(ComponentSnapshotStatus status, byte[] bytes, String key) {
    public ComponentSnapshot {
        Objects.requireNonNull(status);
        if (status == ComponentSnapshotStatus.PRESENT && key == null) throw new IllegalArgumentException("Present snapshot needs identity");
        if ((status == ComponentSnapshotStatus.PRESENT) != (bytes != null)) throw new IllegalArgumentException("Status/bytes mismatch");
        if (bytes != null) {
            if (bytes.length == 0 || bytes.length > ItemTransactionPayload.MAX_COMPONENT_BYTES) throw new IllegalArgumentException("Invalid component bytes");
            bytes = bytes.clone();
        }
    }
    @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    @FunctionalInterface public interface Writer { void write(DataOutput output) throws IOException; }
    public static ComponentSnapshot none() { return new ComponentSnapshot(ComponentSnapshotStatus.NONE, null, "none"); }
    public static ComponentSnapshot failed() {
        return new ComponentSnapshot(ComponentSnapshotStatus.FAILED, null, null);
    }
    public static String fingerprint(Writer writer) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var output = new OutputStream() {
                @Override public void write(int value) { digest.update((byte)value); }
                @Override public void write(byte[] value, int offset, int length) { digest.update(value, offset, length); }
            };
            writer.write(new DataOutputStream(output));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static ComponentSnapshot capture(Writer writer) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var retained = new ByteArrayOutputStream();
            var stream = new OutputStream() {
                boolean oversized;
                @Override public void write(int value) {
                    digest.update((byte)value);
                    if (retained.size() < ItemTransactionPayload.MAX_COMPONENT_BYTES) retained.write(value);
                    else oversized = true;
                }
                @Override public void write(byte[] value, int offset, int length) {
                    digest.update(value, offset, length);
                    int available = ItemTransactionPayload.MAX_COMPONENT_BYTES - retained.size();
                    retained.write(value, offset, Math.min(available, length));
                    if (length > available) oversized = true;
                }
            };
            writer.write(new DataOutputStream(stream));
            if (retained.size() == 0) return failed();
            return new ComponentSnapshot(stream.oversized ? ComponentSnapshotStatus.OVERSIZED : ComponentSnapshotStatus.PRESENT,
                    stream.oversized ? null : retained.toByteArray(), HexFormat.of().formatHex(digest.digest()));
        } catch (UTFDataFormatException oversized) {
            return new ComponentSnapshot(ComponentSnapshotStatus.OVERSIZED, null, null);
        } catch (IOException | RuntimeException failure) { return failed(); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
