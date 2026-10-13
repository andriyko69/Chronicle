/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.query.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookupSessionsTest {
    @Test void cacheIsBoundedUsesLeastRecentlyUsedEvictionAndExpiresAtDeadline() {
        var now = new AtomicReference<>(Instant.parse("2026-10-14T12:00:00Z"));
        ChronicleClock clock = now::get;
        var cache = new LookupSessions(clock, 2, Duration.ofMinutes(10));
        var spec = new LookupParser(clock).parse("r:global t:1h", LookupParser.Context.console());
        cache.put("a", spec); cache.put("b", spec); assertEquals(spec, cache.get("a").orElseThrow());
        cache.put(LookupSessions.CONSOLE, spec);
        assertTrue(cache.get("b").isEmpty()); assertEquals(2, cache.size());
        now.set(now.get().plusSeconds(599)); assertTrue(cache.get("a").isPresent());
        now.set(now.get().plusSeconds(1)); assertTrue(cache.get("a").isEmpty()); assertEquals(0, cache.size());
    }
    @Test void concurrentAccessNeverExceedsCapacityAndClearRemovesSessions() throws Exception {
        var clock = ChronicleClock.system(); var cache = new LookupSessions(clock);
        var spec = new LookupParser(clock).parse("r:global t:1h", LookupParser.Context.console());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int thread = 0; thread < 4; thread++) {
                int base = thread * 1000;
                jobs.add(pool.submit(() -> { for (int i = 0; i < 1000; i++) { cache.put("caller-" + (base + i), spec); cache.get("caller-" + i); assertTrue(cache.size() <= 128); } }));
            }
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertEquals(128, cache.size()); cache.clear(); assertEquals(0, cache.size());
        assertThrows(IllegalArgumentException.class, () -> LookupSpec.offset(0));
        assertThrows(IllegalArgumentException.class, () -> LookupSpec.offset(Integer.MAX_VALUE));
    }
}
