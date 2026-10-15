/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.config;

import static io.github.andriyko69.chronicle.config.ChronicleServerConfig.*;

import java.util.List;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Defines {@code chronicle-server.toml} and bakes it into {@link ChronicleServerConfig} whenever NeoForge (re)loads it. */
public final class ChronicleServerConfigSpec {
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleServerConfigSpec.class);
    private static final ChronicleServerConfig.Snapshot D = ChronicleServerConfig.Snapshot.DEFAULTS;
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue INSPECTOR_COOLDOWN_MILLIS;
    public static final ModConfigSpec.IntValue ROLLBACK_MAX_OPS_PER_TICK;
    public static final ModConfigSpec.IntValue ROLLBACK_MAX_CHUNK_LOADS_PER_TICK;
    public static final ModConfigSpec.IntValue PURGE_BATCH_SIZE;
    public static final ModConfigSpec.IntValue AUTO_PURGE_RETENTION_DAYS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENTITY_KILL_IGNORED_TYPES;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("inspector");
        INSPECTOR_COOLDOWN_MILLIS = builder.comment("Minimum milliseconds between one player's inspector lookups.")
                .defineInRange("inspectorCooldownMillis", D.inspectorCooldownMillis(), INSPECTOR_COOLDOWN_MIN, INSPECTOR_COOLDOWN_MAX);
        builder.pop().push("rollback");
        ROLLBACK_MAX_OPS_PER_TICK = builder.comment("Maximum world changes a rollback or restore applies per server tick.")
                .defineInRange("rollbackMaxOpsPerTick", D.rollbackMaxOpsPerTick(), ROLLBACK_OPS_MIN, ROLLBACK_OPS_MAX);
        ROLLBACK_MAX_CHUNK_LOADS_PER_TICK = builder.comment("Maximum synchronous chunk loads a rollback or restore performs per server tick.")
                .defineInRange("rollbackMaxChunkLoadsPerTick", D.rollbackMaxChunkLoadsPerTick(), ROLLBACK_CHUNK_LOADS_MIN, ROLLBACK_CHUNK_LOADS_MAX);
        builder.pop().push("retention");
        PURGE_BATCH_SIZE = builder.comment("Maximum rows one purge step deletes before live event persistence resumes.")
                .defineInRange("purgeBatchSize", D.purgeBatchSize(), PURGE_BATCH_MIN, PURGE_BATCH_MAX);
        AUTO_PURGE_RETENTION_DAYS = builder.comment("If above 0, purge events older than this many days once after each server start.")
                .defineInRange("autoPurgeRetentionDays", D.autoPurgeRetentionDays(), AUTO_PURGE_DAYS_MIN, AUTO_PURGE_DAYS_MAX);
        builder.pop().push("audit");
        ENTITY_KILL_IGNORED_TYPES = builder.comment("Entity type IDs whose deaths are never recorded, e.g. [\"minecraft:chicken\", \"minecraft:iron_golem\"].")
                .defineListAllowEmpty("entityKillIgnoredTypes", List.of(), () -> "minecraft:",
                        value -> value instanceof String text && ChronicleServerConfig.entityType(text) != null);
        builder.pop();
        SPEC = builder.build();
    }

    private ChronicleServerConfigSpec() {}

    public static void register(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        modBus.addListener(ModConfigEvent.Loading.class, event -> loaded(event.getConfig()));
        modBus.addListener(ModConfigEvent.Reloading.class, event -> loaded(event.getConfig()));
        modBus.addListener(ModConfigEvent.Unloading.class, event -> {
            if (event.getConfig().getSpec() == SPEC) ChronicleServerConfig.reset();
        });
    }

    private static void loaded(ModConfig config) {
        if (config.getSpec() != SPEC) return;
        bake();
        LOG.info("Chronicle server config applied: {}", ChronicleServerConfig.current());
    }

    /** Reads the loaded values once and publishes them as one snapshot; {@code get()} is never used on hot paths. */
    public static void bake() {
        ChronicleServerConfig.install(new ChronicleServerConfig.Snapshot(INSPECTOR_COOLDOWN_MILLIS.getAsInt(),
                ROLLBACK_MAX_OPS_PER_TICK.getAsInt(), ROLLBACK_MAX_CHUNK_LOADS_PER_TICK.getAsInt(), PURGE_BATCH_SIZE.getAsInt(),
                AUTO_PURGE_RETENTION_DAYS.getAsInt(), Set.copyOf(ENTITY_KILL_IGNORED_TYPES.get())));
    }
}
