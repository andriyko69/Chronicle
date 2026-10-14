/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.causality.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FireCausalityTrackerTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T12:00:00Z"));
    private final CausalActor alice = new CausalActor(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Alice", now.get());

    @Test void seedPropagatesAcrossMultipleSpreadHops() {
        var tracker = new FireCausalityTracker(now::get);
        tracker.seed(OVERWORLD, 1, alice, 10);
        tracker.spread(OVERWORLD, tracker.rootAt(OVERWORLD, 1), 2, 11);
        tracker.spread(OVERWORLD, tracker.rootAt(OVERWORLD, 2), 3, 12);
        assertEquals(alice, tracker.rootAt(OVERWORLD, 3));
        assertNull(tracker.rootAt("minecraft:the_nether", 3), "Dimensions are separate");
        tracker.spread(OVERWORLD, null, 3, 13);
        assertNull(tracker.rootAt(OVERWORLD, 3), "An unattributed fire replaces a stale root");
    }

    @Test void contextlessPlacementKeepsOnlySameTickSeeds() {
        var tracker = new FireCausalityTracker(now::get);
        tracker.seed(OVERWORLD, 5, alice, 20);
        tracker.unattributed(OVERWORLD, 5, 20);
        assertEquals(alice, tracker.rootAt(OVERWORLD, 5), "Place event precedes vanilla onPlace in the same tick");
        tracker.unattributed(OVERWORLD, 5, 21);
        assertNull(tracker.rootAt(OVERWORLD, 5), "A later fire at the same position does not inherit the old root");
    }

    @Test void entriesExpireAfterTtl() {
        var tracker = new FireCausalityTracker(now::get);
        tracker.seed(OVERWORLD, 1, alice, 1);
        now.set(now.get().plus(FireCausalityTracker.TTL).minusNanos(1));
        assertEquals(alice, tracker.rootAt(OVERWORLD, 1));
        now.set(now.get().plusNanos(1));
        assertNull(tracker.rootAt(OVERWORLD, 1));
        assertEquals(0, tracker.size());
    }

    @Test void leastRecentlyUsedEntriesAreEvictedPastCapacity() {
        var tracker = new FireCausalityTracker(now::get, 3, Duration.ofMinutes(1));
        for (long position = 1; position <= 3; position++) tracker.seed(OVERWORLD, position, alice, 1);
        assertNotNull(tracker.rootAt(OVERWORLD, 1), "Reading refreshes recency");
        tracker.seed(OVERWORLD, 4, alice, 2);
        assertEquals(3, tracker.size());
        assertNull(tracker.rootAt(OVERWORLD, 2), "Least recently used position evicted");
        assertNotNull(tracker.rootAt(OVERWORLD, 1));
        for (long position = 0; position < 10_000; position++) tracker.seed(OVERWORLD, position, alice, 3);
        assertEquals(3, tracker.size(), "Bounded regardless of fire size");
    }

    @Test void clearForgetsEverything() {
        var tracker = new FireCausalityTracker(now::get);
        tracker.seed(OVERWORLD, 1, alice, 1); tracker.seed("minecraft:the_nether", 1, alice, 1);
        tracker.clear();
        assertEquals(0, tracker.size());
        assertNull(tracker.rootAt(OVERWORLD, 1));
    }

    @Test void memoryRejectsUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new CausalMemory<String>(now::get, 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new CausalMemory<String>(now::get, 1, Duration.ZERO));
    }
}
