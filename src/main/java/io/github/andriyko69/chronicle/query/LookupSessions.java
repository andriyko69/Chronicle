/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class LookupSessions {
    public static final String CONSOLE = "console";
    private record Session(LookupSpec spec, Instant expires) {}
    private final ChronicleClock clock;
    private final int capacity;
    private final Duration ttl;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);
    public LookupSessions(ChronicleClock clock) { this(clock, 128, Duration.ofMinutes(10)); }
    public LookupSessions(ChronicleClock clock, int capacity, Duration ttl) {
        this.clock = Objects.requireNonNull(clock); this.ttl = Objects.requireNonNull(ttl); this.capacity = capacity;
        if (capacity < 1 || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Invalid session bounds");
    }
    public synchronized void put(String caller, LookupSpec spec) {
        expire();
        sessions.put(Objects.requireNonNull(caller), new Session(Objects.requireNonNull(spec), clock.now().plus(ttl)));
        while (sessions.size() > capacity) sessions.remove(sessions.keySet().iterator().next());
    }
    public synchronized Optional<LookupSpec> get(String caller) {
        expire();
        Session session = sessions.get(caller);
        return session == null ? Optional.empty() : Optional.of(session.spec());
    }
    public synchronized int size() { expire(); return sessions.size(); }
    public synchronized void clear() { sessions.clear(); }
    private void expire() {
        Instant now = clock.now();
        sessions.values().removeIf(session -> !now.isBefore(session.expires()));
    }
}
