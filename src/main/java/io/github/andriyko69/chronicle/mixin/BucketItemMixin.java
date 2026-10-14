/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.andriyko69.chronicle.causality.BlockCapture;
import io.github.andriyko69.chronicle.causality.CausalityHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Observation only: NeoForge excludes buckets from placement snapshots, and pickup posts no break event. Each wrapper
 * captures the block before vanilla acts so the audit can confirm what actually changed.
 */
@Mixin(BucketItem.class)
abstract class BucketItemMixin {
    // Wraps each invocation: emptyContents recurses to the clicked face's neighbor, and each level needs its own capture.
    @WrapMethod(method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean chronicle$emptyContents(Player player, Level level, BlockPos pos, BlockHitResult result, ItemStack container,
            Operation<Boolean> original) {
        var before = BlockCapture.before(player, level, pos);
        boolean emptied = original.call(player, level, pos, result, container);
        if (emptied && before != null) CausalityHooks.bucketEmptied(player, before);
        return emptied;
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack chronicle$pickupBlock(BucketPickup pickup, Player player, LevelAccessor level, BlockPos pos, BlockState state,
            Operation<ItemStack> original) {
        var before = level instanceof Level world ? BlockCapture.before(player, world, pos) : null;
        var filled = original.call(pickup, player, level, pos, state);
        if (!filled.isEmpty() && before != null) CausalityHooks.bucketFilled(player, before);
        return filled;
    }
}
