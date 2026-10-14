/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import io.github.andriyko69.chronicle.causality.EntityKillCapture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observation only: {@code PlayerInteractEvent.EntityInteract} fires before the interaction, so whether a player fed,
 * leashed, sheared, milked or saddled an animal is known only from the result vanilla returns here.
 */
@Mixin(Player.class)
abstract class PlayerInteractMixin {
    @Inject(method = "interactOn", at = @At("RETURN"))
    private void chronicle$interacted(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> info) {
        EntityKillCapture.interacted((Player)(Object)this, entity, info.getReturnValue());
    }
}
