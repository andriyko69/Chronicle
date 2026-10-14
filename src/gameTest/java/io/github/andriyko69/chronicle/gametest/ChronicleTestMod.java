/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import io.github.andriyko69.chronicle.container.ContainerSessionManager;
import io.github.andriyko69.chronicle.container.StorageAudit;
import io.github.andriyko69.chronicle.causality.EnvironmentAudit;
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
    static EnvironmentAudit environment;
    /** Wall-clock offset for the causal audits, simulating ticks falling far behind real time on a lagging server. */
    static volatile java.time.Duration skew = java.time.Duration.ZERO;
    private static final ChronicleClock AUDIT_CLOCK = () -> java.time.Instant.now().plus(skew);
    public ChronicleTestMod() {
        containers = new ContainerSessionManager(() -> runtime, ChronicleClock.system());
        containers.register();
        storage = new StorageAudit(() -> runtime, AUDIT_CLOCK, containers);
        storage.register();
        environment = new EnvironmentAudit(() -> runtime, AUDIT_CLOCK, storage);
        environment.register();
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        new PlayerBlockListener(() -> runtime, ChronicleClock.system()).register();
    }
    private void starting(ServerAboutToStartEvent event) {
        var root = Path.of(System.getProperty("chronicle.gameTestRoot")).resolve(UUID.randomUUID().toString());
        runtime = new ChronicleRuntime(InfrastructureConfig.defaults(root), ChronicleClock.system(), "gametest");
        ready = runtime.start().toCompletableFuture();
    }
    private void stopping(ServerStoppingEvent event) { environment.stop(event.getServer()); storage.stop(event.getServer()); containers.stop(event.getServer()); if (runtime != null) runtime.close(); }
}
