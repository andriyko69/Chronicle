/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import java.util.*;
import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Live handles belong exclusively to the owning server thread and never enter submitted events. */
public record WorldContainer(ServerLevel level, BlockPos position, String blockId, List<Slot> slots) {
    public WorldContainer { position = position.immutable(); slots = List.copyOf(slots); }
    public static List<WorldContainer> resolve(ServerPlayer player, AbstractContainerMenu menu) {
        if (!player.serverLevel().getServer().isSameThread()) throw new IllegalStateException("Container resolution off server thread");
        var byStorage = new IdentityHashMap<Container, List<Slot>>();
        for (var slot : menu.slots) {
            if (slot.container != player.getInventory()) byStorage.computeIfAbsent(slot.container, ignored -> new ArrayList<>()).add(slot);
        }
        var resolved = new ArrayList<WorldContainer>();
        for (var entry : byStorage.entrySet()) {
            var entities = new ArrayList<BlockEntity>();
            if (!backingEntities(entry.getKey(), entities) || entities.isEmpty()) continue;
            if (!(entities.getFirst().getLevel() instanceof ServerLevel level) || level != player.serverLevel()) continue;
            if (entities.stream().anyMatch(be -> be.getLevel() != level || be.isRemoved()
                    || level.getBlockEntity(be.getBlockPos()) != be)) continue;
            BlockPos position = canonical(entities);
            var unique = new LinkedHashMap<Integer, Slot>();
            for (Slot slot : entry.getValue()) unique.putIfAbsent(slot.getContainerSlot(), slot);
            resolved.add(new WorldContainer(level, position, BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString(),
                    new ArrayList<>(unique.values())));
        }
        resolved.sort(Comparator.comparingInt((WorldContainer c) -> c.position.getX()).thenComparingInt(c -> c.position.getY()).thenComparingInt(c -> c.position.getZ()));
        return List.copyOf(resolved);
    }
    /** Inspector lookups must address the same double chest half that container sessions record. */
    public static BlockPos canonicalPosition(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Container resolution off server thread");
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            // Ignore the blocked-lid check: a cat on a chest does not change which halves form the container.
            var container = ChestBlock.getContainer(chest, state, level, pos, true);
            var entities = new ArrayList<BlockEntity>();
            if (container != null && backingEntities(container, entities) && !entities.isEmpty()) return canonical(entities);
        }
        return pos.immutable();
    }
    // Lexicographic coordinates keep double chest envelopes stable from either opening half.
    private static BlockPos canonical(List<BlockEntity> entities) {
        return entities.stream().map(BlockEntity::getBlockPos).min(Comparator.<BlockPos>comparingInt(pos -> pos.getX())
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).orElseThrow().immutable();
    }
    private static boolean backingEntities(Container container, List<BlockEntity> entities) {
        if (container instanceof BlockEntity entity) { entities.add(entity); return true; }
        if (container instanceof CompoundContainer compound)
            return backingEntities(compound.container1, entities) && backingEntities(compound.container2, entities);
        return false;
    }
    public Map<ContainerNetDiff.Key, ItemTransactionPayload> snapshot() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Container snapshot off server thread");
        var items = new ArrayList<ContainerNetDiff.Stack>();
        for (var slot : slots) {
            var stack = slot.getItem();
            if (!stack.isEmpty()) items.add(ContainerItemCapture.capture(stack, blockId, level.registryAccess()));
        }
        return ContainerNetDiff.group(items);
    }
}
