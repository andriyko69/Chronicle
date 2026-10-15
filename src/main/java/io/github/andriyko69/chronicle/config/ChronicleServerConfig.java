/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.config;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The baked {@code chronicle-server.toml} values. NeoForge loads and reloads configs on its own threads, so readers on
 * the server thread and on workers only ever see one complete, immutable {@link Snapshot} through a volatile reference,
 * never a half-applied reload or a live {@code ConfigValue} lookup. Free of NeoForge types so pure tests can use it.
 */
public final class ChronicleServerConfig {
    public static final int INSPECTOR_COOLDOWN_MIN = 0, INSPECTOR_COOLDOWN_MAX = 10_000;
    public static final int ROLLBACK_OPS_MIN = 10, ROLLBACK_OPS_MAX = 5_000;
    public static final int ROLLBACK_CHUNK_LOADS_MIN = 1, ROLLBACK_CHUNK_LOADS_MAX = 64;
    public static final int PURGE_BATCH_MIN = 100, PURGE_BATCH_MAX = 50_000;
    public static final int AUTO_PURGE_DAYS_MIN = 0, AUTO_PURGE_DAYS_MAX = 3650;
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public record Snapshot(int inspectorCooldownMillis, int rollbackMaxOpsPerTick, int rollbackMaxChunkLoadsPerTick,
            int purgeBatchSize, int autoPurgeRetentionDays, Set<String> entityKillIgnoredTypes) {
        public static final Snapshot DEFAULTS = new Snapshot(200, 250, 8, 5_000, 0, Set.of());
        public Snapshot {
            inspectorCooldownMillis = clamp(inspectorCooldownMillis, INSPECTOR_COOLDOWN_MIN, INSPECTOR_COOLDOWN_MAX);
            rollbackMaxOpsPerTick = clamp(rollbackMaxOpsPerTick, ROLLBACK_OPS_MIN, ROLLBACK_OPS_MAX);
            rollbackMaxChunkLoadsPerTick = clamp(rollbackMaxChunkLoadsPerTick, ROLLBACK_CHUNK_LOADS_MIN, ROLLBACK_CHUNK_LOADS_MAX);
            purgeBatchSize = clamp(purgeBatchSize, PURGE_BATCH_MIN, PURGE_BATCH_MAX);
            autoPurgeRetentionDays = clamp(autoPurgeRetentionDays, AUTO_PURGE_DAYS_MIN, AUTO_PURGE_DAYS_MAX);
            entityKillIgnoredTypes = entityTypes(Objects.requireNonNull(entityKillIgnoredTypes));
        }
        public long inspectorCooldownNanos() { return inspectorCooldownMillis * 1_000_000L; }
        public boolean ignoresEntityKill(String entityTypeId) { return entityKillIgnoredTypes.contains(entityTypeId); }
    }

    private static volatile Snapshot current = Snapshot.DEFAULTS;

    private ChronicleServerConfig() {}

    public static Snapshot current() { return current; }
    public static void install(Snapshot snapshot) { current = Objects.requireNonNull(snapshot); }
    public static void reset() { current = Snapshot.DEFAULTS; }

    /** A bare path means the minecraft namespace, as in commands; anything else invalid is dropped. */
    public static String entityType(String value) {
        if (value == null) return null;
        String id = value.strip().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.length() <= 128 && RESOURCE.matcher(id).matches() ? id : null;
    }
    static Set<String> entityTypes(Collection<String> values) {
        return values.stream().map(ChronicleServerConfig::entityType).filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
