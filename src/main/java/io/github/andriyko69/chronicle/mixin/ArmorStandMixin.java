/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observation only: {@code ArmorStand.hurt} bypasses NeoForge's damage events. Destruction that spills equipment
 * (player, projectile, explosion, fire) passes through {@code brokenByAnything}, which empties the slots; creative
 * players' hits and projectiles, and damage bypassing invulnerability, call {@code kill()} directly and destroy it.
 */
@Mixin(ArmorStand.class)
abstract class ArmorStandMixin {
    @Inject(method = "brokenByAnything", at = @At("HEAD"))
    private void chronicle$breaking(ServerLevel level, DamageSource source, CallbackInfo info) {
        CausalityHooks.armorStandBreaking((ArmorStand)(Object)this, source);
    }

    // After brokenByAnything the slots are empty and the destruction is already captured; the audit keeps that capture.
    @Inject(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/decoration/ArmorStand;kill()V"))
    private void chronicle$killing(DamageSource source, float amount, CallbackInfoReturnable<Boolean> info) {
        CausalityHooks.armorStandBreaking((ArmorStand)(Object)this, source);
    }
}
