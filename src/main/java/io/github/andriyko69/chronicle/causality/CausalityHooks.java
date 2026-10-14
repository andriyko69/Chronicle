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
 * chain-primed TNT, bucket fluid placement or pickup (by players and dispensers), dispensed projectiles, entities pressing plates, tripwire
 * and detector rails, armor stand destruction, and entity ignition post no NeoForge events. Every registered audit (the mod's, and
 * the GameTest mod's) observes each call.
 */
public final class CausalityHooks {
    private static final List<EnvironmentAudit> AUDITS = new CopyOnWriteArrayList<>();
    private CausalityHooks() {}

    private static volatile Thread suppressing;

    static void register(EnvironmentAudit audit) { AUDITS.add(audit); }

    /**
     * Runs Chronicle's own world mutation (rollback, restore) unobserved: vanilla callbacks it triggers, such as a
     * restored fire's onPlace, must neither emit audit rows nor seed or clear causal memory.
     */
    public static void suppressing(Runnable mutation) {
        var previous = suppressing;
        suppressing = Thread.currentThread();
        try { mutation.run(); } finally { suppressing = previous; }
    }
    private static boolean suppressed() { return suppressing == Thread.currentThread(); }

    public static void tickStarting(ServerLevel level, BlockPos pos) { if (suppressed()) return; for (var audit : AUDITS) audit.fireTickStarting(level, pos); }
    public static void tickFinished(ServerLevel level, BlockPos pos) { if (suppressed()) return; for (var audit : AUDITS) audit.fireTickFinished(level, pos); }
    public static void burning(Level level, BlockPos pos) { if (suppressed()) return; for (var audit : AUDITS) audit.burning(level, pos); }
    public static void burnChecked(Level level, BlockPos pos) { if (suppressed()) return; for (var audit : AUDITS) audit.burnChecked(level, pos); }
    public static void firePlaced(Level level, BlockPos pos, BlockState oldState) { if (suppressed()) return; for (var audit : AUDITS) audit.firePlaced(level, pos, oldState); }
    public static void chainPriming(Level level, Explosion explosion) { if (suppressed()) return; for (var audit : AUDITS) audit.chainPriming(level, explosion); }
    public static void chainPrimed(Level level, Explosion explosion) { if (suppressed()) return; for (var audit : AUDITS) audit.chainPrimed(level, explosion); }
    public static void bucketEmptied(Player player, BlockCapture before) { if (suppressed()) return; for (var audit : AUDITS) audit.bucketEmptied(player, before); }
    public static void bucketFilled(Player player, BlockCapture before) { if (suppressed()) return; for (var audit : AUDITS) audit.bucketFilled(player, before); }
    public static void dispenserTriggered(Level level, BlockPos pos) { if (suppressed()) return; for (var audit : AUDITS) audit.dispenserTriggered(level, pos); }
    public static void dispensing(Level level, BlockPos dispenser) { if (suppressed()) return; for (var audit : AUDITS) audit.dispensing(level, dispenser); }
    public static void launching(Level level, BlockPos dispenser) { if (suppressed()) return; for (var audit : AUDITS) audit.launching(level, dispenser); }
    public static void launched(Level level) { if (suppressed()) return; for (var audit : AUDITS) audit.launched(level); }
    public static void dispensed(BlockPos dispenser, BlockCapture before, boolean pickup) { if (suppressed()) return; for (var audit : AUDITS) audit.dispensed(dispenser, before, pickup); }
    public static void redstoneContacting(Level level, BlockPos pos, Entity entity) { if (suppressed()) return; for (var audit : AUDITS) audit.redstoneContacting(level, pos, entity); }
    public static void redstoneContacted(Level level, boolean pressed) { if (suppressed()) return; for (var audit : AUDITS) audit.redstoneContacted(level, pressed); }
    public static void igniting(Entity entity) { if (suppressed()) return; for (var audit : AUDITS) audit.igniting(entity); }
    public static void armorStandBreaking(ArmorStand stand, DamageSource source) { if (suppressed()) return; for (var audit : AUDITS) audit.armorStandBreaking(stand, source); }
}
