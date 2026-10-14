/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.codec.SnapshotNbt;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** A block's state before a player or dispenser changes it outside NeoForge's place/break events; never leaves the server thread. */
public record BlockCapture(ServerLevel level, BlockPos pos, BlockState state, SnapshotNbt nbt) {
    /** Returns null for a null player: dispensers pass none and are captured by {@link #at} around the dispense instead. */
    public static BlockCapture before(Player player, Level level, BlockPos pos) {
        return player == null ? null : at(level, pos);
    }

    /** Returns null when not running on the server thread. */
    public static BlockCapture at(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return null;
        var state = server.getBlockState(pos);
        return new BlockCapture(server, pos.immutable(), state, PlayerBlockListener.blockEntity(server, pos, state));
    }
}
