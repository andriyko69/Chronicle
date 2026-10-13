/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.event.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContainerPayloadCodecTest {
    private final ItemTransactionPayloadCodec codec = new ItemTransactionPayloadCodec();
    static ItemTransactionPayload payload(int count, ItemTransactionPayload.ComponentSnapshotStatus status) {
        return new ItemTransactionPayload(count, "minecraft:barrel", "Blade", new TreeMap<>(Map.of("minecraft:sharpness", 5)), 12,
                status, status == ItemTransactionPayload.ComponentSnapshotStatus.PRESENT ? new byte[] {10, 0, 0, 0} : null);
    }
    static AuditEvent event(String action, String item, int count, Instant time) {
        var envelope = new EventEnvelope("minecraft:overworld", 2, 64, 3, action, "PLAYER", "Alice", UUID.fromString("00000000-0000-0000-0000-000000000004"), null, item);
        return AuditEvent.item(EventId.generate(), time, envelope, payload(count, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT));
    }
    @Test void allStatusesRoundTripAndDomainJournalSupportsBothActions() throws Exception {
        for (var status : ItemTransactionPayload.ComponentSnapshotStatus.values()) {
            var payload = payload(16, status);
            assertEquals(payload, codec.decode(1, codec.encode(payload)));
        }
        for (String action : List.of("ITEM_TAKE", "ITEM_PUT")) {
            var event = event(action, "minecraft:diamond_sword", 1, Instant.now());
            var journalCodec = new ChronicleEventCodec();
            assertEquals(event, journalCodec.decode(journalCodec.encode(event)));
        }
        var plain = new ItemTransactionPayload(64, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null);
        assertEquals(plain, codec.decode(1, codec.encode(plain)));
        assertTrue(codec.encode(plain).length < 64);
    }
    @Test void bytesAndEnchantmentsAreImmutableAndCanonical() {
        byte[] bytes = new byte[] {1, 2};
        var enchantments = new TreeMap<>(Map.of("mod:z", 2, "mod:a", 3));
        var payload = new ItemTransactionPayload(1, "minecraft:chest", null, enchantments, null, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT, bytes);
        byte[] encoded = codec.encode(payload);
        bytes[0] = 9; enchantments.clear(); payload.componentBytes()[0] = 8;
        assertArrayEquals(encoded, codec.encode(payload));
        assertEquals(List.of("mod:a", "mod:z"), new ArrayList<>(payload.enchantments().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> payload.enchantments().clear());
    }
    @Test void invalidTruncatedTrailingAndUnsupportedPayloadsReject() {
        byte[] valid = codec.encode(payload(4, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT));
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(1, truncated), "length=" + size);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(2, valid));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, Arrays.copyOf(valid, valid.length + 1)));
        byte[] bad = valid.clone(); ByteBuffer.wrap(bad).putInt(4, 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, bad));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, new byte[ItemTransactionPayloadCodec.MAX_BYTES + 1]));
        for (String id : List.of("chest", "Minecraft:chest", ":chest", "minecraft:", "minecraft:a:b", "a:" + "x".repeat(128)))
            assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, id, null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(0, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", "é".repeat(513), new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", "\uD800", new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
    }
    @Test void sixteenKiBCeilingAndStatusConsistencyAreStrict() {
        var exact = new ItemTransactionPayload(1, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT, new byte[16 * 1024]);
        assertEquals(exact, codec.decode(1, codec.encode(exact)));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT, new byte[16 * 1024 + 1]));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.FAILED, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT, null));
        assertThrows(IllegalArgumentException.class, () -> new ItemTransactionPayload(1, "minecraft:chest", null, new TreeMap<>(), -1, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
    }
}
