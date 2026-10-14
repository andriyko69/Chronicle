/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.inspect;

import io.github.andriyko69.chronicle.container.WorldContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Cancels inspector interactions at HIGHEST so the LOWEST-priority audit listeners, which skip canceled events,
 * never record an inspection as a break, placement or container session.
 */
public final class InspectorListener {
    @FunctionalInterface
    public interface Lookup { void inspect(ServerPlayer player, ServerLevel level, BlockPos target); }
    private final InspectorSessions sessions;
    private final Lookup lookup;
    public InspectorListener(InspectorSessions sessions, Lookup lookup) { this.sessions = sessions; this.lookup = lookup; }
    public void register() {
        // Receive canceled events too: a protection mod canceling first must not let the click skip inspection or our cancel.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::leftClick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::rightClick);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::useItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::breaking);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, this::placing);
        NeoForge.EVENT_BUS.addListener(this::loggedOut);
    }
    public void leftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(inspecting(event.getEntity()) instanceof ServerPlayer player)) return;
        event.setCanceled(true);
        // Survival mining sends START, then STOP when finished; querying only START yields one lookup per click.
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !(event.getLevel() instanceof ServerLevel level)) return;
        trigger(player, level, () -> WorldContainer.canonicalPosition(level, event.getPos()));
    }
    public void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(inspecting(event.getEntity()) instanceof ServerPlayer player)) return;
        // Both hands are canceled: the client sends a separate OFF_HAND packet when its main-hand use passes.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        resyncInventory(player);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos clicked = event.getPos();
        trigger(player, level, () -> level.getBlockEntity(clicked) instanceof Container
                ? WorldContainer.canonicalPosition(level, clicked)
                : event.getFace() == null ? clicked.immutable() : clicked.relative(event.getFace()));
    }
    /** Items such as buckets act through a follow-up use-item packet after the block click passes client-side. */
    public void useItem(PlayerInteractEvent.RightClickItem event) {
        if (!(inspecting(event.getEntity()) instanceof ServerPlayer player)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        resyncInventory(player);
    }
    public void breaking(BlockEvent.BreakEvent event) {
        if (inspecting(event.getPlayer()) != null) event.setCanceled(true);
    }
    public void placing(BlockEvent.EntityPlaceEvent event) {
        if (inspecting(event.getEntity()) != null) event.setCanceled(true);
    }
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { sessions.remove(event.getEntity().getUUID()); }
    private ServerPlayer inspecting(Object entity) {
        if (!(entity instanceof ServerPlayer player) || !player.serverLevel().getServer().isSameThread()
                || !sessions.suppressing(player.getUUID())) return null;
        // Revocation stops lookups but this interaction stays canceled, as do the rest of the gesture's packets.
        if (!player.hasPermissions(2) && sessions.revoke(player.getUUID()))
            player.sendSystemMessage(Component.literal("Chronicle inspector disabled: you no longer have permission.").withStyle(ChatFormatting.RED));
        return player;
    }
    private void trigger(ServerPlayer player, ServerLevel level, java.util.function.Supplier<BlockPos> target) {
        if (sessions.tryLookup(player.getUUID())) lookup.inspect(player, level, target.get());
    }
    // The client predicts item consumption locally; the server copy is unchanged, so normal slot diffing sends nothing.
    private static void resyncInventory(ServerPlayer player) { player.containerMenu.sendAllDataToRemote(); }
}
