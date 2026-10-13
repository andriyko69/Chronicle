/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** RFC 9562 UUIDv7. Ordering uses unsigned UUID halves, unlike UUID.compareTo. */
public record EventId(UUID value) implements Comparable<EventId> {
    private static final Generator SYSTEM = new Generator(ChronicleClock.system());

    public EventId {
        Objects.requireNonNull(value);
        if (value.version() != 7 || value.variant() != 2) throw new IllegalArgumentException("Expected UUIDv7");
    }

    public static EventId generate() { return SYSTEM.next(); }
    public Instant timestamp() { return Instant.ofEpochMilli(value.getMostSignificantBits() >>> 16); }
    @Override public String toString() { return value.toString(); }
    @Override public int compareTo(EventId other) {
        int high = Long.compareUnsigned(value.getMostSignificantBits(), other.value.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    /** Monotonic within this generator, including wall-clock rollback; not across JVM restarts. */
    public static final class Generator {
        private static final long LOW_MASK = 0x3fff_ffff_ffff_ffffL;
        private final ChronicleClock clock;
        private final SecureRandom random = new SecureRandom();
        private long millis = -1;
        private int high;
        private long low;

        public Generator(ChronicleClock clock) { this.clock = Objects.requireNonNull(clock); }

        public synchronized EventId next() {
            long requested = clock.now().toEpochMilli();
            if (requested < 0 || requested > 0xffff_ffff_ffffL)
                throw new IllegalArgumentException("UUIDv7 timestamp outside unsigned 48-bit range");
            if (requested > millis) {
                millis = requested;
                high = random.nextInt(4096);
                low = random.nextLong() & LOW_MASK;
            } else if (low != LOW_MASK) {
                low++;
            } else {
                low = 0;
                if (high != 4095) high++;
                else {
                    if (millis == 0xffff_ffff_ffffL) throw new IllegalStateException("UUIDv7 exhausted");
                    millis++;
                    high = 0;
                }
            }
            return new EventId(new UUID((millis << 16) | 0x7000 | high, 0x8000_0000_0000_0000L | low));
        }
    }
}
