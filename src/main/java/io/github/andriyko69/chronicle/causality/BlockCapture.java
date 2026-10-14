/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.codec.SnapshotNbt;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** A block's state before a player changes it outside NeoForge's place/break events; never leaves the server thread. */
public record BlockCapture(ServerLevel level, BlockPos pos, BlockState state, SnapshotNbt nbt) {
    /** Returns null when the change has no player to attribute it to (dispensers) or does not run on the server thread. */
    public static BlockCapture before(Player player, Level level, BlockPos pos) {
        if (player == null || !(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return null;
        var state = server.getBlockState(pos);
        return new BlockCapture(server, pos.immutable(), state, PlayerBlockListener.blockEntity(server, pos, state));
    }
}
