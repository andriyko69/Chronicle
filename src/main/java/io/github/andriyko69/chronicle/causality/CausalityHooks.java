/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Static entry points for Chronicle's Mixins, which cannot hold instance references. Vanilla fire spread, burnout and
 * chain-primed TNT post no NeoForge events. Every registered audit (the mod's, and the GameTest mod's) observes each call.
 */
public final class CausalityHooks {
    private static final List<EnvironmentAudit> AUDITS = new CopyOnWriteArrayList<>();
    private CausalityHooks() {}

    static void register(EnvironmentAudit audit) { AUDITS.add(audit); }

    public static void tickStarting(ServerLevel level, BlockPos pos) { for (var audit : AUDITS) audit.fireTickStarting(level, pos); }
    public static void tickFinished(ServerLevel level, BlockPos pos) { for (var audit : AUDITS) audit.fireTickFinished(level, pos); }
    public static void burning(Level level, BlockPos pos) { for (var audit : AUDITS) audit.burning(level, pos); }
    public static void burnChecked(Level level, BlockPos pos) { for (var audit : AUDITS) audit.burnChecked(level, pos); }
    public static void firePlaced(Level level, BlockPos pos, BlockState oldState) { for (var audit : AUDITS) audit.firePlaced(level, pos, oldState); }
    public static void chainPriming(Level level, Explosion explosion) { for (var audit : AUDITS) audit.chainPriming(level, explosion); }
    public static void chainPrimed(Level level, Explosion explosion) { for (var audit : AUDITS) audit.chainPrimed(level, explosion); }
    public static void fluidEmptied(Player player, Level level, BlockPos pos) { for (var audit : AUDITS) audit.fluidEmptied(player, level, pos); }
}
