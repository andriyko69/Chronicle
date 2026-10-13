/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.event.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventCodecTest {
    @Test void uuidV7IsRfcCompatibleMonotonicAndHandlesClockRollback() {
        var time = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
        var generator = new EventId.Generator(time::get);
        EventId previous = generator.next();
        assertEquals(time.get(), previous.timestamp());
        for (int i = 0; i < 10_000; i++) {
            EventId next = generator.next();
            assertEquals(7, next.value().version()); assertEquals(2, next.value().variant());
            assertTrue(next.compareTo(previous) > 0); previous = next;
        }
        time.set(time.get().minusSeconds(10));
        assertTrue(generator.next().compareTo(previous) > 0);
        var future = new EventId.Generator(ChronicleClock.fixed(Instant.ofEpochMilli(0x8000_0000_0000L))).next();
        assertTrue(future.compareTo(previous) > 0, "Unsigned UUID order survives sign bit");
    }
    @Test void concurrentUuidGenerationDoesNotDuplicate() throws Exception {
        var generator = new EventId.Generator(ChronicleClock.fixed(Instant.parse("2026-10-13T00:00:00Z")));
        var values = new HashSet<EventId>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<java.util.List<EventId>>>();
            for (int thread = 0; thread < 4; thread++) jobs.add(executor.submit(() -> {
                var batch = new java.util.ArrayList<EventId>();
                for (int i = 0; i < 5000; i++) batch.add(generator.next());
                return batch;
            }));
            for (var job : jobs) for (var value : job.get()) assertTrue(values.add(value));
        }
        assertEquals(20_000, values.size());
    }
    @Test void exactTimestampUnicodeAndImmutableBytesRoundTrip() throws Exception {
        var codec = new ChronicleEventCodec();
        var event = new AuditEvent(EventId.generate(), Instant.ofEpochSecond(-3, 987654321), "synthetic 🧪 Україна");
        var encoded = codec.encode(event);
        byte[] bytes = encoded.bytes(); bytes[0] = 0;
        assertEquals(event, codec.decode(encoded));
        var copied = new EncodedAuditEvent(encoded.id(), bytes); bytes[0] = 10;
        assertEquals(0, copied.bytes()[0]);
        assertThrows(ChronicleEventCodec.InvalidEventException.class, () -> codec.decode(copied));
        byte[] unsupported = encoded.bytes(); ByteBuffer.wrap(unsupported).putInt(4, 999);
        assertThrows(ChronicleEventCodec.InvalidEventException.class, () -> codec.decode(new EncodedAuditEvent(event.id(), unsupported)));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(event.id(), event.occurredAt(), "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(event.id(), event.occurredAt(), "x".repeat(4097)));
    }
}
