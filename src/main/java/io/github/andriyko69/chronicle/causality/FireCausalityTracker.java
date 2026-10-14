/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Root attribution for fire (and player-placed lava) positions, keyed by dimension and packed block position.
 *
 * <p>Every new fire block takes its root at placement: from the fire whose tick spread it, from a seed (a player
 * igniting it, attributed lava, an attributed explosion), or none. A contextless placement therefore clears a stale
 * root left by an earlier fire at that position, except one written in the same server tick, because NeoForge posts
 * the player's place event before vanilla's placement callback. Extinguished fires are not forgotten eagerly: their
 * entries simply age out, which keeps the parent of same-tick burnouts and multi-hop spreads resolvable.
 */
public final class FireCausalityTracker {
    public static final int CAPACITY_PER_DIMENSION = 4096;
    public static final Duration TTL = Duration.ofMinutes(15);
    private final ChronicleClock clock;
    private final int capacity;
    private final Duration ttl;
    private final Map<String, CausalMemory<Long>> dimensions = new HashMap<>();

    public FireCausalityTracker(ChronicleClock clock) { this(clock, CAPACITY_PER_DIMENSION, TTL); }
    public FireCausalityTracker(ChronicleClock clock, int capacity, Duration ttl) {
        this.clock = Objects.requireNonNull(clock); this.capacity = capacity; this.ttl = Objects.requireNonNull(ttl);
    }

    public void seed(String dimension, long position, CausalActor actor, long tick) {
        dimensions.computeIfAbsent(dimension, ignored -> new CausalMemory<>(clock, capacity, ttl)).put(position, actor, tick);
    }

    /** Records that a spreading fire, whose root was captured as {@code root} when its tick began, created fire at {@code to}. */
    public void spread(String dimension, CausalActor root, long to, long tick) {
        if (root != null) seed(dimension, to, root, tick);
        else unattributed(dimension, to, tick);
    }

    public void unattributed(String dimension, long position, long tick) {
        var memory = dimensions.get(dimension);
        if (memory != null) memory.forgetUnlessWrittenAt(position, tick);
    }

    public CausalActor rootAt(String dimension, long position) {
        var memory = dimensions.get(dimension);
        return memory == null ? null : memory.get(position);
    }

    public int size() { return dimensions.values().stream().mapToInt(CausalMemory::size).sum(); }
    public void clear() { dimensions.clear(); }
}
