/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.config.ChronicleServerConfig;
import io.github.andriyko69.chronicle.config.ChronicleServerConfig.Snapshot;
import io.github.andriyko69.chronicle.inspect.InspectorSessions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSnapshotTest {
    @AfterEach void restore() { ChronicleServerConfig.reset(); }

    @Test void defaultsApplyWithoutNeoForgeLoading() {
        var config = ChronicleServerConfig.current();
        assertSame(Snapshot.DEFAULTS, config);
        assertEquals(200, config.inspectorCooldownMillis());
        assertEquals(200_000_000L, config.inspectorCooldownNanos());
        assertEquals(250, config.rollbackMaxOpsPerTick());
        assertEquals(8, config.rollbackMaxChunkLoadsPerTick());
        assertEquals(5_000, config.purgeBatchSize());
        assertEquals(0, config.autoPurgeRetentionDays());
        assertTrue(config.entityKillIgnoredTypes().isEmpty());
        assertFalse(config.ignoresEntityKill("minecraft:chicken"));
    }

    @Test void outOfRangeValuesAreClampedToTheSpecRanges() {
        var low = new Snapshot(-5, 0, 0, 1, -1, Set.of());
        assertEquals(List.of(0, 10, 1, 100, 0), List.of(low.inspectorCooldownMillis(), low.rollbackMaxOpsPerTick(),
                low.rollbackMaxChunkLoadsPerTick(), low.purgeBatchSize(), low.autoPurgeRetentionDays()));
        var high = new Snapshot(Integer.MAX_VALUE, 1_000_000, 1000, 1_000_000, 100_000, Set.of());
        assertEquals(List.of(10_000, 5_000, 64, 50_000, 3650), List.of(high.inspectorCooldownMillis(), high.rollbackMaxOpsPerTick(),
                high.rollbackMaxChunkLoadsPerTick(), high.purgeBatchSize(), high.autoPurgeRetentionDays()));
        assertEquals(10_000_000_000L, high.inspectorCooldownNanos(), "Cooldown nanos must not overflow int arithmetic");
    }

    @Test void ignoredEntityTypesAreNormalisedAndInvalidOnesDropped() {
        var config = new Snapshot(200, 250, 8, 5_000, 0, Set.of("chicken", "minecraft:iron_golem", " Minecraft:Cow ",
                "mymod:farm/duck", "bad id", "a:b:c", ""));
        assertEquals(Set.of("minecraft:chicken", "minecraft:iron_golem", "minecraft:cow", "mymod:farm/duck"), config.entityKillIgnoredTypes());
        assertTrue(config.ignoresEntityKill("minecraft:cow"));
        assertFalse(config.ignoresEntityKill("minecraft:sheep"));
        assertThrows(UnsupportedOperationException.class, () -> config.entityKillIgnoredTypes().add("minecraft:pig"));
        assertNull(ChronicleServerConfig.entityType(null));
        assertNull(ChronicleServerConfig.entityType("x".repeat(130)));
    }

    @Test void installedSnapshotIsPublishedWholeAndResetRestoresDefaults() throws Exception {
        var custom = new Snapshot(50, 100, 4, 1_000, 30, Set.of("minecraft:cow"));
        var seen = new ArrayList<Snapshot>();
        var reader = new Thread(() -> seen.add(ChronicleServerConfig.current()));
        ChronicleServerConfig.install(custom);
        reader.start(); reader.join();
        assertSame(custom, seen.getFirst());
        assertThrows(NullPointerException.class, () -> ChronicleServerConfig.install(null));
        ChronicleServerConfig.reset();
        assertSame(Snapshot.DEFAULTS, ChronicleServerConfig.current());
    }

    @Test void inspectorCooldownFollowsTheCurrentSnapshot() {
        var nanos = new AtomicLong();
        var player = UUID.randomUUID();
        var sessions = new InspectorSessions(nanos::get, () -> ChronicleServerConfig.current().inspectorCooldownNanos(), Duration.ofSeconds(1));
        sessions.set(player, true);
        assertTrue(sessions.tryLookup(player));
        nanos.addAndGet(150_000_000);
        assertFalse(sessions.tryLookup(player), "Default 200 ms cooldown");
        ChronicleServerConfig.install(new Snapshot(100, 250, 8, 5_000, 0, Set.of()));
        assertTrue(sessions.tryLookup(player), "A reload applies to existing sessions");
        ChronicleServerConfig.install(new Snapshot(0, 250, 8, 5_000, 0, Set.of()));
        assertTrue(sessions.tryLookup(player), "Zero disables the cooldown");
    }
}
