/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.listener;

import io.github.andriyko69.chronicle.codec.SnapshotNbt;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.runtime.ChronicleRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerBlockListener {
    private static final Logger LOG = LoggerFactory.getLogger(PlayerBlockListener.class);
    private final Supplier<ChronicleRuntime> runtime;
    private final ChronicleClock clock;
    private long lastFailureWarning = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    public PlayerBlockListener(Supplier<ChronicleRuntime> runtime, ChronicleClock clock) {
        this.runtime = runtime; this.clock = clock;
    }
    public void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::breaking);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, this::placing);
    }
    public void breaking(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        var destination = runtime.get();
        if (destination == null) return;
        try {
            submit(destination, level, event.getPos(), event.getPlayer(), "BLOCK_BREAK", state(event.getState()), BlockStateSnapshot.AIR,
                    blockEntity(level, event.getPos(), event.getState()));
        } catch (RuntimeException failure) { captureFailed(failure); }
    }
    public void placing(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(event.getEntity() instanceof Player player)) return;
        var destination = runtime.get();
        if (destination == null) return;
        try {
            List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                    ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
            // One item use may write the same position repeatedly; retain its first pre-state.
            var firstByPosition = new LinkedHashMap<BlockPos, BlockSnapshot>();
            for (var snapshot : snapshots) firstByPosition.putIfAbsent(snapshot.getPos().immutable(), snapshot);
            for (var snapshot : firstByPosition.values()) {
                SnapshotNbt nbt = snapshot.getTag() == null
                        ? (snapshot.getState().hasBlockEntity() ? SnapshotNbt.failed() : SnapshotNbt.none()) : snapshot(snapshot.getTag());
                submit(destination, level, snapshot.getPos(), player, "BLOCK_PLACE", state(snapshot.getState()),
                        state(level.getBlockState(snapshot.getPos())), nbt);
            }
        } catch (RuntimeException failure) { captureFailed(failure); }
    }
    /** Must run before removal: the snapshot is the only remaining copy of a destroyed block entity's data. */
    public static SnapshotNbt blockEntity(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) return SnapshotNbt.none();
        try {
            var entity = level.getBlockEntity(pos);
            return entity == null ? SnapshotNbt.failed() : snapshot(entity.saveWithFullMetadata(level.registryAccess()));
        } catch (RuntimeException failure) { return SnapshotNbt.failed(); }
    }
    private static SnapshotNbt snapshot(CompoundTag tag) {
        // NbtIo.write substitutes empty strings on UTF overflow, which would silently lose forensic data.
        return SnapshotNbt.capture(output -> NbtIo.writeUnnamedTag(tag, output));
    }
    private void submit(ChronicleRuntime destination, ServerLevel level, BlockPos position, Player player,
            String type, BlockStateSnapshot before, BlockStateSnapshot after, SnapshotNbt nbt) {
        var actor = PlayerActor.capture(player);
        var envelope = new EventEnvelope(level.dimension().location().toString(), position.getX(), position.getY(), position.getZ(),
                type, actor.type(), actor.name(), actor.uuid(), null, type.equals("BLOCK_BREAK") ? before.blockId() : after.blockId());
        // These hooks precede final engine success/cancellation; air is the intended break result.
        var payload = new BlockDeltaPayload(before, after, BlockDeltaPayload.Observation.HOOK_OBSERVATION, nbt.status(), nbt.bytes());
        destination.submit(AuditEvent.block(EventId.generate(), clock.now(), envelope, payload));
    }
    public static BlockStateSnapshot state(BlockState state) {
        var properties = new TreeMap<String, String>();
        for (var property : state.getProperties()) properties.put(property.getName(), propertyValue(state, property));
        return new BlockStateSnapshot(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private synchronized void captureFailed(RuntimeException failure) {
        long now = System.nanoTime();
        if (now - lastFailureWarning >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
            lastFailureWarning = now;
            LOG.warn("Chronicle failed to capture a block observation; event was not admitted", failure);
        }
    }
}
