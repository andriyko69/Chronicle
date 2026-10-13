/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.journal.openhft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Two checksummed slots preserve the previous synced frontier if an update tears during power loss. */
final class JournalFrontier implements AutoCloseable {
    private static final int SLOT_BYTES = 48;
    private static final int MAGIC = 0x43484631;
    private static final int CHECKSUM_OFFSET = 40;
    private final FileChannel channel;
    private final UUID identity;
    private long ordinal;
    private long position;
    private int activeSlot;

    JournalFrontier(Path directory, UUID identity, boolean queueExists) throws IOException {
        this.identity = identity;
        Path file = directory.resolve("chronicle.frontier");
        boolean exists = Files.exists(file);
        if (!exists && queueExists) throw new IOException("Existing queue has no durable append frontier");
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            if (!exists) { ordinal = 0; position = -1; writeSlot(0, 0, -1); writeSlot(1, 0, -1); channel.force(true); }
            long[] first = readSlot(0), second = readSlot(1);
            long[] selected = first == null ? second : second == null || first[0] >= second[0] ? first : second;
            if (selected == null) throw new IOException("Both durable frontier slots are corrupt");
            ordinal = selected[0]; position = selected[1];
            activeSlot = selected == first ? 0 : 1;
        } catch (IOException failure) { channel.close(); throw failure; }
    }
    long ordinal() { return ordinal; }
    long position() { return position; }
    void sync(long nextOrdinal, long nextPosition) throws IOException {
        if (nextOrdinal == ordinal && nextPosition == position) return;
        if (nextOrdinal < ordinal) throw new IOException("Journal frontier would move backwards");
        int slot = 1 - activeSlot;
        writeSlot(slot, nextOrdinal, nextPosition);
        channel.force(true);
        ordinal = nextOrdinal; position = nextPosition;
        activeSlot = slot;
    }
    private void writeSlot(int slot, long count, long index) throws IOException {
        var bytes = ByteBuffer.allocate(SLOT_BYTES).putInt(MAGIC).putInt(1)
                .putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits())
                .putLong(count).putLong(index);
        CRC32C crc = new CRC32C(); crc.update(bytes.array(), 0, CHECKSUM_OFFSET); bytes.putInt((int) crc.getValue()).putInt(0).flip();
        long offset = (long) slot * SLOT_BYTES;
        while (bytes.hasRemaining()) offset += channel.write(bytes, offset);
    }
    private long[] readSlot(int slot) throws IOException {
        var bytes = ByteBuffer.allocate(SLOT_BYTES);
        long offset = (long) slot * SLOT_BYTES;
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset);
            if (read < 0) return null;
            offset += read;
        }
        bytes.flip();
        if (bytes.getInt() != MAGIC || bytes.getInt() != 1 || bytes.getLong() != identity.getMostSignificantBits()
                || bytes.getLong() != identity.getLeastSignificantBits()) return null;
        long count = bytes.getLong(), index = bytes.getLong();
        CRC32C crc = new CRC32C(); crc.update(bytes.array(), 0, CHECKSUM_OFFSET);
        if (bytes.getInt() != (int) crc.getValue() || count < 0 || (count == 0 ? index != -1 : index < 0)) return null;
        return new long[]{count, index};
    }
    static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory.toAbsolutePath(), StandardOpenOption.READ)) { channel.force(true); }
    }
    @Override public void close() throws IOException { channel.close(); }
}
