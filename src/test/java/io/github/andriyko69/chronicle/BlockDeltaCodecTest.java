/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.event.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockDeltaCodecTest {
    private final BlockDeltaPayloadCodec codec = new BlockDeltaPayloadCodec();
    static BlockDeltaPayload delta(byte[] nbt) {
        return new BlockDeltaPayload(new BlockStateSnapshot("minecraft:chest", Map.of("facing", "north", "custom", "Україна 🧪")),
                BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION,
                nbt == null ? BlockDeltaPayload.SnapshotStatus.NONE : BlockDeltaPayload.SnapshotStatus.PRESENT, nbt);
    }
    static AuditEvent event(int number) {
        return AuditEvent.block(EventId.generate(), Instant.ofEpochSecond(-3, 987654321),
                new EventEnvelope("minecraft:overworld", -123, -64, number, "BLOCK_BREAK", "FAKE_PLAYER", "Тест 🧪",
                        UUID.fromString("12345678-1234-5678-1234-567812345678"), null, "minecraft:chest"), delta(new byte[]{10, 0, 0, 0}));
    }
    @Test void propertiesUnicodeEmptyPropertyMapsAndNbtRoundTrip() {
        for (var payload : List.of(delta(null), delta(new byte[]{10, 0, 0, 0}), delta(new byte[BlockDeltaPayload.MAX_NBT_BYTES])))
            assertEquals(payload, codec.decode(1, codec.encode(payload)));
        var first = new BlockStateSnapshot("test:block", Map.of("z", "2", "a", "1"));
        var second = new BlockStateSnapshot("test:block", new LinkedHashMap<>(Map.of("a", "1", "z", "2")));
        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.properties().put("x", "y"));
        assertThrows(IllegalArgumentException.class, () -> new BlockStateSnapshot("", Map.of()));
    }
    @Test void eventEnvelopeRoundTripAndDefensiveCopies() throws Exception {
        var event = event(Integer.MAX_VALUE); var events = new ChronicleEventCodec();
        assertEquals(event, events.decode(events.encode(event)));
        byte[] nbt = {1, 2, 3}; var payload = delta(nbt); nbt[0] = 9;
        assertEquals(1, payload.snapshotNbt()[0]); payload.snapshotNbt()[0] = 8;
        assertEquals(1, payload.snapshotNbt()[0]);
        byte[] bytes = codec.encode(payload);
        var copied = new AuditEvent(event.id(), event.occurredAt(), event.envelope(), 1, bytes);
        bytes[0] = 4; copied.payload()[0] = 5;
        assertEquals(payload, codec.decode(1, copied.payload()));
        var noActor = AuditEvent.block(EventId.generate(), Instant.EPOCH,
                new EventEnvelope("test:world", 0, 0, 0, "BLOCK_PLACE", "PLAYER", "name", null, UUID.randomUUID(), "test:block"), delta(null));
        assertEquals(noActor, events.decode(events.encode(noActor)));
    }
    @Test void boundedRawSerializationKeepsExactCeilingAndOmitsOverflowOrFailure() {
        int maximum = BlockDeltaPayload.MAX_NBT_BYTES;
        var exact = SnapshotNbt.capture(output -> output.write(new byte[maximum]));
        assertEquals(BlockDeltaPayload.SnapshotStatus.PRESENT, exact.status()); assertEquals(maximum, exact.bytes().length);
        var excess = SnapshotNbt.capture(output -> { output.write(new byte[maximum]); output.writeByte(1); });
        assertEquals(BlockDeltaPayload.SnapshotStatus.OVERSIZED, excess.status()); assertNull(excess.bytes());
        var hugeWrite = SnapshotNbt.capture(output -> output.write(new byte[maximum + 1]));
        assertEquals(BlockDeltaPayload.SnapshotStatus.OVERSIZED, hugeWrite.status());
        assertEquals(BlockDeltaPayload.SnapshotStatus.OVERSIZED, SnapshotNbt.capture(output -> output.writeUTF("x".repeat(70_000))).status());
        assertEquals(BlockDeltaPayload.SnapshotStatus.FAILED, SnapshotNbt.capture(output -> { throw new IOException("injected"); }).status());
        assertThrows(IllegalArgumentException.class, () -> delta(new byte[maximum + 1]));
        var flagged = new BlockDeltaPayload(BlockStateSnapshot.AIR, BlockStateSnapshot.AIR,
                BlockDeltaPayload.Observation.HOOK_OBSERVATION, excess.status(), null);
        assertEquals(flagged, codec.decode(1, codec.encode(flagged)));
    }
    @Test void malformedUnsupportedAndNoncanonicalPayloadsFailBeforeUnboundedAllocation() {
        byte[] good = codec.encode(delta(null));
        for (int i = 0; i < good.length; i++) {
            byte[] truncated = Arrays.copyOf(good, i);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(1, truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(2, good));
        byte[] badEnum = good.clone(); badEnum[4] = 100;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, badEnum));
        byte[] badLength = good.clone(); ByteBuffer.wrap(badLength).putInt(6, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, badLength));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, Arrays.copyOf(good, good.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope("test:world", 0, 0, 0, "break", "PLAYER", "bad\ud800", null, null, "test:block"));
        assertThrows(IllegalArgumentException.class, () -> new BlockDeltaPayload(BlockStateSnapshot.AIR, BlockStateSnapshot.AIR,
                BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.OVERSIZED, new byte[]{1}));
    }
}
