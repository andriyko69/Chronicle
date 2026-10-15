/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.inspect;

import io.github.andriyko69.chronicle.config.ChronicleServerConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class InspectorSessions {
    /**
     * One click can arrive as several packets (main hand, off hand, follow-up item use). A revoked session keeps
     * suppressing interactions briefly so the packets after the revoking one cannot act on the world.
     */
    public static final Duration REVOCATION_GRACE = Duration.ofSeconds(1);
    private static final class State { long lastLookup; boolean looked; boolean revoked; long revokedAt; }
    private final Map<UUID, State> states = new HashMap<>();
    private final LongSupplier nanoTime;
    private final LongSupplier cooldownNanos;
    private final long graceNanos;
    public InspectorSessions() { this(System::nanoTime, () -> ChronicleServerConfig.current().inspectorCooldownNanos(), REVOCATION_GRACE); }
    public InspectorSessions(LongSupplier nanoTime, Duration cooldown, Duration grace) {
        this(nanoTime, fixed(cooldown), grace);
    }
    /** {@code cooldownNanos} is read on every click, so a config reload applies without recreating sessions. */
    public InspectorSessions(LongSupplier nanoTime, LongSupplier cooldownNanos, Duration grace) {
        this.nanoTime = Objects.requireNonNull(nanoTime); this.cooldownNanos = Objects.requireNonNull(cooldownNanos); graceNanos = grace.toNanos();
        if (grace.isNegative()) throw new IllegalArgumentException("Negative duration");
    }
    private static LongSupplier fixed(Duration cooldown) {
        if (cooldown.isNegative()) throw new IllegalArgumentException("Negative duration");
        long nanos = cooldown.toNanos();
        return () -> nanos;
    }
    public synchronized boolean toggle(UUID player) { return set(player, !active(player)); }
    public synchronized boolean set(UUID player, boolean enabled) {
        if (!enabled) states.remove(player);
        else if (!active(Objects.requireNonNull(player))) states.put(player, new State());
        return enabled;
    }
    /** Whether the player may run inspector lookups. */
    public synchronized boolean active(UUID player) {
        var state = states.get(player);
        return state != null && !state.revoked;
    }
    /** Whether the player's world interactions must be canceled: active, or revoked within the grace period. */
    public synchronized boolean suppressing(UUID player) {
        var state = states.get(player);
        if (state == null) return false;
        if (state.revoked && nanoTime.getAsLong() - state.revokedAt >= graceNanos) { states.remove(player); return false; }
        return true;
    }
    /** Returns true only for the transition out of an active session, so the player is notified once. */
    public synchronized boolean revoke(UUID player) {
        var state = states.get(player);
        if (state == null || state.revoked) return false;
        state.revoked = true; state.revokedAt = nanoTime.getAsLong();
        return true;
    }
    /** Held mouse buttons repeat interactions every few ticks; only the first within the cooldown may query. */
    public synchronized boolean tryLookup(UUID player) {
        var state = states.get(player);
        if (state == null || state.revoked) return false;
        long now = nanoTime.getAsLong();
        if (state.looked && now - state.lastLookup < cooldownNanos.getAsLong()) return false;
        state.looked = true; state.lastLookup = now;
        return true;
    }
    public synchronized void remove(UUID player) { states.remove(player); }
    public synchronized void clear() { states.clear(); }
    public synchronized int size() { return states.size(); }
}
