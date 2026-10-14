/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Static entry points for Chronicle's Mixins, which cannot hold instance references. Vanilla fire spread, burnout,
 * chain-primed TNT, bucket fluid placement or pickup (by players and dispensers), entities pressing plates, tripwire
 * and detector rails, and armor stand destruction post no NeoForge events. Every registered audit (the mod's, and
 * the GameTest mod's) observes each call.
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
    public static void bucketEmptied(Player player, BlockCapture before) { for (var audit : AUDITS) audit.bucketEmptied(player, before); }
    public static void bucketFilled(Player player, BlockCapture before) { for (var audit : AUDITS) audit.bucketFilled(player, before); }
    public static void dispenserTriggered(Level level, BlockPos pos) { for (var audit : AUDITS) audit.dispenserTriggered(level, pos); }
    public static void dispensing(Level level, BlockPos dispenser) { for (var audit : AUDITS) audit.dispensing(level, dispenser); }
    public static void dispensed(BlockPos dispenser, BlockCapture before, boolean pickup) { for (var audit : AUDITS) audit.dispensed(dispenser, before, pickup); }
    public static void redstoneContacting(Level level, BlockPos pos, Entity entity) { for (var audit : AUDITS) audit.redstoneContacting(level, pos, entity); }
    public static void redstoneContacted(Level level, boolean pressed) { for (var audit : AUDITS) audit.redstoneContacted(level, pressed); }
    public static void armorStandBreaking(ArmorStand stand, DamageSource source) { for (var audit : AUDITS) audit.armorStandBreaking(stand, source); }
}
