/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.*;

/**
 * The GameTest world and its Chronicle database persist between runs, so exact-coordinate inspections may also
 * return older runs' rows. Assertions therefore key on each run's random actor UUID.
 */
@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class InspectorGameTests {
    private static final class Inspector {
        final ServerPlayer player;
        final List<Component> chat = new ArrayList<>();
        Inspector(ServerPlayer player) { this.player = player; }
        List<String> lines(int from) { return chat.subList(from, chat.size()).stream().map(ChatEvidence::text).toList(); }
        UUID id() { return player.getUUID(); }
    }
    private static final class Output implements CommandSource {
        final MinecraftServer server;
        final List<Component> components = new ArrayList<>();
        Output(MinecraftServer server) { this.server = server; }
        public void sendSystemMessage(Component message) {
            if (!server.isSameThread()) throw new IllegalStateException("Command output off server thread");
            components.add(message);
        }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
        List<String> lines() { return components.stream().map(ChatEvidence::text).toList(); }
    }
    private static final class Probe { Output pending; }

    private static Inspector join(GameTestHelper helper, String name) {
        var level = helper.getLevel(); var server = level.getServer();
        var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        var inspector = new Inspector(player);
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public boolean hasChannel(net.minecraft.resources.ResourceLocation channel) { return false; }
            @Override public void send(Packet<?> packet) { capture(packet); }
            @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) { capture(packet); }
            private void capture(Packet<?> packet) { if (packet instanceof ClientboundSystemChatPacket chat) inspector.chat.add(chat.content()); }
        };
        // GameTestServer grants operators level 0; an explicit entry gives the production permission path level 2.
        server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 2, false));
        // Result delivery requires PlayerList identity; a full login needs a network channel the test cannot provide.
        players(server).add(player); playersByUuid(server).put(player.getUUID(), player);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))));
        return inspector;
    }
    private static void leave(Inspector inspector) {
        var server = inspector.player.getServer();
        players(server).remove(inspector.player); playersByUuid(server).remove(inspector.id(), inspector.player);
        server.getPlayerList().getOps().remove(inspector.player.getGameProfile());
    }
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> players(MinecraftServer server) { return (List<ServerPlayer>)field(server.getPlayerList(), "players"); }
    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> playersByUuid(MinecraftServer server) { return (Map<UUID, ServerPlayer>)field(server.getPlayerList(), "playersByUUID"); }
    private static Object field(PlayerList list, String name) {
        try {
            var field = PlayerList.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(list);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void run(CommandSourceStack source, String command) {
        try { source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException failure) { throw new IllegalStateException(failure); }
    }
    private static void command(Inspector inspector, String command) { run(inspector.player.createCommandSourceStack(), command); }
    /** Re-issues a console lookup each attempt so one frozen response is judged, never a buffer mixing attempts. */
    private static void awaitConsole(GameTestHelper helper, Probe probe, String query, Predicate<List<String>> done, String waiting) {
        var server = helper.getLevel().getServer();
        if (probe.pending == null) {
            probe.pending = new Output(server);
            run(server.createCommandSourceStack().withPermission(2).withSource(probe.pending), query);
        }
        helper.assertTrue(!probe.pending.components.isEmpty(), "Waiting for console lookup reply");
        var lines = probe.pending.lines(); probe.pending = null;
        helper.assertTrue(done.test(lines), waiting);
    }
    private static long rows(List<String> lines, String fragment) {
        return lines.stream().filter(line -> line.contains("Event ID: ") && line.contains(fragment)).count();
    }
    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
    }
    private static InteractionResult rightClick(Inspector inspector, InteractionHand hand, BlockPos pos, Direction face) {
        var player = inspector.player;
        return player.gameMode.useItemOn(player, player.serverLevel(), player.getItemInHand(hand), hand, hit(pos, face));
    }
    private static void leftClick(Inspector inspector, BlockPos pos) {
        inspector.player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP,
                inspector.player.serverLevel().getMaxBuildHeight(), 1);
    }
    private static void ready(GameTestHelper helper) {
        helper.assertTrue(ChronicleTestMod.ready.isDone(), "Waiting for Chronicle startup"); ChronicleTestMod.ready.join();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void leftClickInspectsPlacedBlockWithoutBreakingOrAuditing(GameTestHelper helper) {
        var level = helper.getLevel(); var inspector = join(helper, "InspectLeft"); var player = inspector.player;
        var floor = helper.absolutePos(new BlockPos(1, 0, 1)); var target = floor.above();
        var sentinelFloor = helper.absolutePos(new BlockPos(3, 0, 3)); var sentinel = sentinelFloor.above();
        var probe = new Probe(); var mark = new Object() { int chat; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(sentinelFloor, Blocks.STONE.defaultBlockState());
            player.setGameMode(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD_BLOCK));
            helper.assertTrue(rightClick(inspector, InteractionHand.MAIN_HAND, floor, Direction.UP).consumesAction(), "Real placement");
            helper.assertTrue(level.getBlockState(target).is(Blocks.EMERALD_BLOCK), "Emerald placed");
        }).thenWaitUntil(() -> awaitConsole(helper, probe, "ch l r:global t:1h u:" + inspector.id(),
                lines -> rows(lines, " placed ") == 1, "Waiting for placement persistence"))
        .thenExecute(() -> {
            command(inspector, "ch i");
            helper.assertTrue(inspector.lines(0).getLast().contains("Chronicle inspector enabled"), "Enable feedback");
            mark.chat = inspector.chat.size();
            leftClick(inspector, target);
            // A creative START_DESTROY would remove the block immediately if the click were not canceled.
            helper.assertTrue(level.getBlockState(target).is(Blocks.EMERALD_BLOCK), "Inspected block survives");
            helper.assertTrue(!player.gameMode.destroyBlock(target) && level.getBlockState(target).is(Blocks.EMERALD_BLOCK), "Break guard");
        }).thenWaitUntil(() -> {
            var lines = inspector.lines(mark.chat);
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains("Inspecting " + coordinates(target))), "Waiting for inspector header");
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains("Actor UUID: " + inspector.id()) && line.contains(" placed ")
                    && line.contains("New state: minecraft:emerald_block")), "Prior placement shown");
            helper.assertTrue(rows(lines, "Actor UUID: " + inspector.id()) == 1, "Only the placement by this actor");
        }).thenExecute(() -> {
            command(inspector, "chronicle inspect off");
            helper.assertTrue(inspector.lines(0).getLast().contains("Chronicle inspector disabled."), "Disable feedback");
            // Journal order is FIFO: once this later placement is visible, any false break from the click would be too.
            helper.assertTrue(rightClick(inspector, InteractionHand.MAIN_HAND, sentinelFloor, Direction.UP).consumesAction(), "Inspector off places");
            helper.assertTrue(level.getBlockState(sentinel).is(Blocks.EMERALD_BLOCK), "Sentinel placed");
        }).thenWaitUntil(() -> awaitConsole(helper, probe, "ch l r:global t:1h u:" + inspector.id(),
                lines -> rows(lines, " placed ") == 2, "Waiting for sentinel persistence"))
        .thenExecute(() -> {
            probe.pending = new Output(level.getServer());
            run(level.getServer().createCommandSourceStack().withPermission(2).withSource(probe.pending), "ch l r:global t:1h u:" + inspector.id());
        }).thenWaitUntil(() -> {
            helper.assertTrue(!probe.pending.components.isEmpty(), "Waiting for final audit lookup");
            var lines = probe.pending.lines();
            helper.assertTrue(rows(lines, "Event ID: ") == 2 && rows(lines, " broke ") == 0, "Inspection recorded no audit events");
            leave(inspector);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void rightClickBothDoubleChestHalvesShowsCanonicalHistoryWithoutOpening(GameTestHelper helper) {
        var level = helper.getLevel(); var inspector = join(helper, "InspectChest"); var player = inspector.player;
        // The wall-clock cooldown cannot elapse inside an unthrottled GameTest, so a second admin clicks the other half.
        var second = join(helper, "InspectChest2");
        var left = helper.absolutePos(new BlockPos(1, 1, 1)); var right = left.east();
        var probe = new Probe(); var marks = new int[2];
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
            level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
            var chest = (Container)level.getBlockEntity(right);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 16));
            helper.assertTrue(player.openMenu(level.getBlockState(right).getMenuProvider(level, right)).isPresent(), "Menu opens before inspector");
            chest.setItem(0, ItemStack.EMPTY); chest.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));
            player.closeContainer();
        }).thenWaitUntil(() -> awaitConsole(helper, probe, "ch l r:global t:1h a:item u:" + inspector.id(),
                lines -> rows(lines, " took ") == 1 && rows(lines, " put ") == 1, "Waiting for container persistence"))
        .thenExecute(() -> {
            command(inspector, "ch i on"); command(second, "ch i on");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 5));
            second.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 5));
            marks[0] = inspector.chat.size(); marks[1] = second.chat.size();
            rightClick(inspector, InteractionHand.MAIN_HAND, right, Direction.UP);
            rightClick(second, InteractionHand.MAIN_HAND, left, Direction.UP);
        }).thenWaitUntil(() -> {
            for (int i = 0; i < 2; i++) {
                var lines = (i == 0 ? inspector : second).lines(marks[i]);
                helper.assertTrue(lines.stream().anyMatch(line -> line.contains("Inspecting " + coordinates(left))), "Waiting for canonical header " + i);
                var mine = lines.stream().filter(line -> line.contains("Event ID: ") && line.contains("Actor UUID: " + inspector.id())).toList();
                helper.assertTrue(mine.stream().anyMatch(line -> line.contains(" took ") && line.contains("Item: minecraft:diamond") && line.contains("Count: 16"))
                        && mine.stream().anyMatch(line -> line.contains(" put ") && line.contains("Item: minecraft:gold_ingot") && line.contains("Count: 3")),
                        "Container history from half " + i);
            }
            for (var admin : List.of(player, second.player)) {
                helper.assertTrue(admin.containerMenu == admin.inventoryMenu, "No container menu opened");
                helper.assertTrue(admin.getMainHandItem().is(Items.DIRT) && admin.getMainHandItem().getCount() == 5, "Held stack untouched");
            }
            helper.assertTrue(level.getBlockState(left.above()).isAir() && level.getBlockState(right.above()).isAir(), "Held block never placed");
            leave(inspector); leave(second);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void rightClickFaceInspectsAdjacentAirOffHandIsInertAndPagesWork(GameTestHelper helper) {
        var level = helper.getLevel(); var inspector = join(helper, "InspectAdjacent"); var player = inspector.player;
        var floor = helper.absolutePos(new BlockPos(2, 0, 2)); var air = floor.above();
        var probe = new Probe(); var mark = new Object() { int chat; int page; final Set<String> firstPage = new HashSet<>(); };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS, 16));
            for (int i = 0; i < 5; i++) {
                helper.assertTrue(rightClick(inspector, InteractionHand.MAIN_HAND, floor, Direction.UP).consumesAction(), "Real placement " + i);
                helper.assertTrue(player.gameMode.destroyBlock(air), "Real break " + i);
            }
            helper.assertTrue(level.getBlockState(air).isAir(), "Adjacent space empty");
        }).thenWaitUntil(() -> awaitConsole(helper, probe, "ch l r:global t:1h a:break u:" + inspector.id(),
                lines -> rows(lines, " broke ") == 5, "Waiting for the final break, and so every earlier event, to persist"))
        .thenExecute(() -> {
            command(inspector, "ch i");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIRT, 4));
            mark.chat = inspector.chat.size();
            // A real client sends the off-hand use only after its main-hand use passes, within the same tick.
            rightClick(inspector, InteractionHand.MAIN_HAND, floor, Direction.UP);
            helper.assertTrue(rightClick(inspector, InteractionHand.OFF_HAND, floor, Direction.UP) == InteractionResult.SUCCESS, "Off-hand canceled");
            helper.assertTrue(level.getBlockState(air).isAir() && player.getOffhandItem().getCount() == 4, "Off-hand block never placed");
        }).thenWaitUntil(() -> {
            var lines = inspector.lines(mark.chat);
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains("Inspecting " + coordinates(air))), "Waiting for adjacent header");
            helper.assertTrue(rows(lines, "Actor UUID: " + inspector.id()) == 8 && lines.stream().anyMatch(line -> line.contains("[Next ▶]")), "Full first page with next button");
        }).thenIdle(10).thenExecute(() -> {
            var lines = inspector.lines(mark.chat);
            helper.assertTrue(lines.stream().filter(line -> line.startsWith("Inspecting ")).count() == 1, "Off-hand did not duplicate the lookup");
            inspector.chat.subList(mark.chat, inspector.chat.size()).stream().map(ChatEvidence::eventId).filter(Objects::nonNull).forEach(mark.firstPage::add);
            mark.page = inspector.chat.size();
            command(inspector, "ch page 2");
        }).thenWaitUntil(() -> {
            var page = inspector.chat.subList(mark.page, inspector.chat.size());
            var mine = page.stream().filter(line -> ChatEvidence.text(line).contains("Actor UUID: " + inspector.id())).toList();
            helper.assertTrue(mine.size() >= 2, "Waiting for page 2 with remaining events");
            helper.assertTrue(page.stream().map(ChatEvidence::eventId).filter(Objects::nonNull).noneMatch(mark.firstPage::contains), "No overlap with page 1");
            String text = String.join("\n", page.stream().map(ChatEvidence::text).toList());
            helper.assertTrue(text.contains(" broke ") && text.contains(" placed ") && text.contains("(" + air.getX() + ", " + air.getY() + ", " + air.getZ() + ")"), "Exact adjacent history");
            leave(inspector);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void inspectCommandRejectsConsoleAndUnprivilegedAndRevalidatesPermission(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer(); var inspector = join(helper, "InspectPerm"); var player = inspector.player;
        var target = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.startSequence().thenExecute(() -> {
            var console = new Output(server);
            run(server.createCommandSourceStack().withPermission(4).withSource(console), "ch i");
            helper.assertTrue(console.lines().getLast().contains("requires an in-game player"), "Console rejected");
            try {
                run(player.createCommandSourceStack().withPermission(1), "chronicle inspect on");
                helper.fail("Permission level 1 enabled the inspector");
            } catch (IllegalStateException expected) { }
            command(inspector, "ch i");
            helper.assertTrue(inspector.lines(0).getLast().contains("enabled"), "Level 2 enables");
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            command(inspector, "ch i");
            helper.assertTrue(inspector.lines(0).getLast().contains("enabled"), "Logout removed state, so toggle enables again");
            level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            server.getPlayerList().getOps().remove(player.getGameProfile());
            helper.assertTrue(!player.hasPermissions(2), "Permission revoked");
            int mark = inspector.chat.size();
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.DIRT, 4));
            rightClick(inspector, InteractionHand.MAIN_HAND, target, Direction.UP);
            helper.assertTrue(inspector.lines(mark).stream().anyMatch(line -> line.contains("no longer have permission")), "Player notified");
            // The rest of the revoking gesture arrives as further packets; none may act on the world.
            helper.assertTrue(rightClick(inspector, InteractionHand.OFF_HAND, target, Direction.UP) == InteractionResult.SUCCESS
                    && level.getBlockState(target.above()).isAir() && player.getOffhandItem().getCount() == 4, "Off-hand follow-up suppressed");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            player.setXRot(90);
            helper.assertTrue(player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND) == InteractionResult.FAIL
                    && player.getMainHandItem().is(Items.WATER_BUCKET), "Bucket follow-up suppressed");
            player.setGameMode(GameType.CREATIVE);
            leftClick(inspector, target);
            helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "Left-click within the grace still canceled");
            helper.assertTrue(inspector.lines(mark).stream().filter(line -> line.contains("no longer have permission")).count() == 1, "Notified once");
            helper.assertTrue(inspector.lines(mark).stream().noneMatch(line -> line.contains("Inspecting ")), "Revoked player never looked up");
            try {
                command(inspector, "ch i");
                helper.fail("Revoked player re-enabled the inspector");
            } catch (IllegalStateException expected) { }
            leave(inspector);
        }).thenSucceed();
    }
}
