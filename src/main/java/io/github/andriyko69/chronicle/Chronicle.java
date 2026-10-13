/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.world.level.storage.LevelResource;
import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import io.github.andriyko69.chronicle.runtime.InfrastructureConfig;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Chronicle.MOD_ID)
public final class Chronicle {
    public static final String MOD_ID = "chronicle";

    private static final Logger LOG = LoggerFactory.getLogger(Chronicle.class);
    private final String version;
    private ChronicleRuntime runtime;

    public Chronicle(ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        new PlayerBlockListener(() -> runtime, ChronicleClock.system()).register();
    }
    private void starting(ServerAboutToStartEvent event) {
        try {
            var root = event.getServer().getWorldPath(LevelResource.ROOT).resolve("chronicle");
            runtime = new ChronicleRuntime(InfrastructureConfig.load(root), ChronicleClock.system(), version);
            runtime.start().whenComplete((ignored, failure) -> {
                if (failure != null) LOG.error("Chronicle infrastructure startup failed", failure);
            });
        } catch (Exception failure) {
            LOG.error("Chronicle infrastructure configuration/startup failed; forensic durability unavailable", failure);
        }
    }
    private void stopping(ServerStoppingEvent event) {
        if (runtime != null) {
            try { runtime.close(); }
            catch (Exception failure) { LOG.error("Chronicle shutdown was not clean", failure); }
            finally { runtime = null; }
        }
    }
}
