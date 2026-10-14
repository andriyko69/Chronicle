/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import io.github.andriyko69.chronicle.container.ContainerSessionManager;
import io.github.andriyko69.chronicle.container.StorageAudit;
import io.github.andriyko69.chronicle.runtime.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;

@Mod("chronicle_test")
public final class ChronicleTestMod {
    static ChronicleRuntime runtime;
    static CompletableFuture<Void> ready;
    private final ContainerSessionManager containers;
    private final StorageAudit storage;
    public ChronicleTestMod() {
        containers = new ContainerSessionManager(() -> runtime, ChronicleClock.system());
        containers.register();
        storage = new StorageAudit(() -> runtime, ChronicleClock.system(), containers);
        storage.register();
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        new PlayerBlockListener(() -> runtime, ChronicleClock.system()).register();
    }
    private void starting(ServerAboutToStartEvent event) {
        var root = Path.of(System.getProperty("chronicle.gameTestRoot")).resolve(UUID.randomUUID().toString());
        runtime = new ChronicleRuntime(InfrastructureConfig.defaults(root), ChronicleClock.system(), "gametest");
        ready = runtime.start().toCompletableFuture();
    }
    private void stopping(ServerStoppingEvent event) { storage.stop(event.getServer()); containers.stop(event.getServer()); if (runtime != null) runtime.close(); }
}
