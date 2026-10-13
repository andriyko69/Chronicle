/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.util.Arrays;
import java.util.Objects;

/** NBT is opaque version-sensitive data; persistence must never deserialize it. */
public record BlockDeltaPayload(BlockStateSnapshot before, BlockStateSnapshot after,
        Observation observation, SnapshotStatus snapshotStatus, byte[] snapshotNbt) {
    public static final int VERSION = 1;
    public static final int MAX_NBT_BYTES = 64 * 1024;
    public enum Observation { HOOK_OBSERVATION }
    public enum SnapshotStatus { NONE, PRESENT, OVERSIZED, FAILED }
    public BlockDeltaPayload {
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        Objects.requireNonNull(observation); Objects.requireNonNull(snapshotStatus);
        if ((snapshotStatus == SnapshotStatus.PRESENT) != (snapshotNbt != null))
            throw new IllegalArgumentException("Snapshot status/bytes mismatch");
        if (snapshotNbt != null) {
            if (snapshotNbt.length < 1 || snapshotNbt.length > MAX_NBT_BYTES)
                throw new IllegalArgumentException("NBT snapshot outside raw byte ceiling");
            snapshotNbt = snapshotNbt.clone();
        }
    }
    @Override public byte[] snapshotNbt() { return snapshotNbt == null ? null : snapshotNbt.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof BlockDeltaPayload value && before.equals(value.before) && after.equals(value.after)
                && observation == value.observation && snapshotStatus == value.snapshotStatus
                && Arrays.equals(snapshotNbt, value.snapshotNbt);
    }
    @Override public int hashCode() {
        return 31 * Objects.hash(before, after, observation, snapshotStatus) + Arrays.hashCode(snapshotNbt);
    }
}
