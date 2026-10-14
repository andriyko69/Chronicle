/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Observation only: entities pressing plates, tripwire and detector rails post no NeoForge event. Each acts only while
 * unpowered, so a changed block state means this entity's contact pressed it; contact that powers nothing (a player
 * walking over an empty detector rail) is discarded. The contact is visible while vanilla notifies neighbors.
 */
@Mixin({BasePressurePlateBlock.class, TripWireBlock.class, DetectorRailBlock.class})
abstract class RedstoneTriggerMixin {
    @WrapMethod(method = "entityInside")
    private void chronicle$entered(BlockState state, Level level, BlockPos pos, Entity entity, Operation<Void> original) {
        var before = level.getBlockState(pos);
        CausalityHooks.redstoneContacting(level, pos, entity);
        boolean pressed = false;
        try {
            original.call(state, level, pos, entity);
            pressed = level.getBlockState(pos) != before;
        } finally { CausalityHooks.redstoneContacted(level, pressed); }
    }
}
