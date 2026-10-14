/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: every ignition (lava, fire, flame arrows, fire aspect, lightning, sunlight) passes through
 * {@code igniteForTicks} and posts no event. A fire contact from an earlier tick no longer explains the burning after it.
 */
@Mixin(Entity.class)
abstract class EntityIgnitionMixin {
    @Inject(method = "igniteForTicks", at = @At("HEAD"))
    private void chronicle$igniting(int ticks, CallbackInfo info) {
        CausalityHooks.igniting((Entity)(Object)this);
    }
}
