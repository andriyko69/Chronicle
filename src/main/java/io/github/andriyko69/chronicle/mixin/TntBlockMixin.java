/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: brackets the one call in which an explosion primes a TNT block, so the new PrimedTnt inherits that
 * explosion's root actor and no other. Proximity and timing alone cannot prove which explosion primed a TNT.
 */
@Mixin(TntBlock.class)
abstract class TntBlockMixin {
    @Inject(method = "wasExploded", at = @At("HEAD"))
    private void chronicle$priming(Level level, BlockPos pos, Explosion explosion, CallbackInfo info) {
        CausalityHooks.chainPriming(level, explosion);
    }

    @Inject(method = "wasExploded", at = @At("RETURN"))
    private void chronicle$primed(Level level, BlockPos pos, Explosion explosion, CallbackInfo info) {
        CausalityHooks.chainPrimed(level, explosion);
    }
}
