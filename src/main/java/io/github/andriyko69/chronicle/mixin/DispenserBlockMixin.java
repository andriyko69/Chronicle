/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.github.andriyko69.chronicle.causality.BlockCapture;
import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: dispensed buckets pour and scoop with no player and post no NeoForge event. The trigger hook marks
 * the neighbor update that schedules a dispense, when a recent player redstone activation can still be traced; the
 * pre-dispense hook credits whoever loaded the bucket through a still-open menu.
 */
@Mixin(DispenserBlock.class)
abstract class DispenserBlockMixin {
    @Inject(method = "neighborChanged", at = @At("TAIL"))
    private void chronicle$neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving,
            CallbackInfo info) {
        if (!state.getValue(DispenserBlock.TRIGGERED) && level.getBlockState(pos).getOptionalValue(DispenserBlock.TRIGGERED).orElse(false))
            CausalityHooks.dispenserTriggered(level, pos);
    }

    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack chronicle$dispense(DispenseItemBehavior behavior, BlockSource source, ItemStack stack, Operation<ItemStack> original,
            @Share("before") LocalRef<BlockCapture> before, @Share("pickup") LocalBooleanRef pickup) {
        var item = stack.getItem();
        if (item instanceof DispensibleContainerItem)
            before.set(BlockCapture.at(source.level(), source.pos().relative(source.state().getValue(DispenserBlock.FACING))));
        pickup.set(item instanceof BucketItem bucket && bucket.content == Fluids.EMPTY);
        if (before.get() != null) CausalityHooks.dispensing(source.level(), source.pos());
        return original.call(behavior, source, stack);
    }

    // Vanilla installs the returned remainder (the filled or emptied bucket) only after dispense returns.
    @Inject(method = "dispenseFrom", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private void chronicle$dispensed(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo info,
            @Share("before") LocalRef<BlockCapture> before, @Share("pickup") LocalBooleanRef pickup) {
        if (before.get() != null) CausalityHooks.dispensed(pos, before.get(), pickup.get());
    }
}
