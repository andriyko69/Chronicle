/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Bounded causal memory: least-recently-used eviction past {@code capacity} and expiry {@code ttl} after the last write,
 * so long-running servers cannot accumulate attribution state. Owned by the server thread; not synchronized.
 */
public final class CausalMemory<K> {
    private record Entry(CausalActor actor, Instant expires, long tick) {}
    private final ChronicleClock clock;
    private final int capacity;
    private final Duration ttl;
    private final LinkedHashMap<K, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public CausalMemory(ChronicleClock clock, int capacity, Duration ttl) {
        this.clock = Objects.requireNonNull(clock); this.ttl = Objects.requireNonNull(ttl); this.capacity = capacity;
        if (capacity < 1 || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Invalid causal memory bounds");
    }

    public void put(K key, CausalActor actor, long tick) {
        Objects.requireNonNull(key); Objects.requireNonNull(actor);
        expire();
        entries.put(key, new Entry(actor, clock.now().plus(ttl), tick));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    public CausalActor get(K key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        if (!clock.now().isBefore(entry.expires())) { entries.remove(key); return null; }
        return entry.actor();
    }

    /** Forgets an attribution unless it was written during {@code tick}, which a same-tick seed must survive. */
    public void forgetUnlessWrittenAt(K key, long tick) {
        var entry = entries.get(key);
        if (entry != null && entry.tick() != tick) entries.remove(key);
    }

    public void remove(K key) { entries.remove(key); }

    public int size() { expire(); return entries.size(); }
    public void clear() { entries.clear(); }

    private void expire() {
        Instant now = clock.now();
        entries.values().removeIf(entry -> !now.isBefore(entry.expires()));
    }
}
