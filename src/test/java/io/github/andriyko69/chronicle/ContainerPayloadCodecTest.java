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
    @Test void slotlessPayloadsStayVersionOneAndSlottedOnesRoundTripAsVersionTwo() throws Exception {
        var plain = payload(3, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT);
        byte[] v1 = codec.encode(plain);
        assertEquals(1, plain.version());
        assertEquals(1, ByteBuffer.wrap(v1).getInt(0), "Existing rows and journals keep their exact bytes");
        var slotted = plain.withSlot(4);
        byte[] v2 = codec.encode(slotted);
        assertEquals(2, slotted.version());
        assertArrayEquals(Arrays.copyOfRange(v1, 4, v1.length), Arrays.copyOfRange(v2, 4, v1.length), "v2 only appends the slot");
        assertEquals(slotted, codec.decode(2, v2));
        assertEquals(Integer.valueOf(4), codec.decode(2, v2).slot());
        assertEquals(slotted.withCount(1).slot(), Integer.valueOf(4), "Net-diff counts keep the slot");
        assertNotEquals(plain, slotted);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, v2), "Header and declared version must agree");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(2, v1));
        for (int size = v1.length; size < v2.length; size++) {
            byte[] truncated = Arrays.copyOf(v2, size);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(2, truncated), "length=" + size);
        }
        assertThrows(IllegalArgumentException.class, () -> plain.withSlot(-1));
        assertThrows(IllegalArgumentException.class, () -> plain.withSlot(ItemTransactionPayload.MAX_SLOT + 1));
        var event = AuditEvent.item(EventId.generate(), Instant.now(), event("ITEM_PUT", "minecraft:book", 1, Instant.now()).envelope(), slotted);
        assertEquals(2, event.payloadVersion());
        var journal = new ChronicleEventCodec();
        assertEquals(event, journal.decode(journal.encode(event)));
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
