/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: vanilla fire spread and burnout post no NeoForge events. The scheduled tick brackets which fire is
 * spreading, {@code onCaughtFire} marks the one point where checkBurnOut has committed to replacing the block, and every
 * new fire block passes through onPlace. No injector alters control flow or vanilla state.
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void chronicle$tickStarting(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo info) {
        CausalityHooks.tickStarting(level, pos);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void chronicle$tickFinished(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo info) {
        CausalityHooks.tickFinished(level, pos);
    }

    @Inject(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onCaughtFire(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void chronicle$burning(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo info) {
        CausalityHooks.burning(level, pos);
    }

    @Inject(method = "checkBurnOut", at = @At("TAIL"))
    private void chronicle$burnChecked(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo info) {
        CausalityHooks.burnChecked(level, pos);
    }

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void chronicle$placed(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving, CallbackInfo info) {
        CausalityHooks.firePlaced(level, pos, oldState);
    }
}
