/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import io.github.andriyko69.chronicle.event.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class StorageGameTests {
    private static ServerPlayer player(GameTestHelper helper, String name) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public boolean hasChannel(net.minecraft.resources.ResourceLocation channel) { return false; }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener listener) {}
        };
        return player;
    }
    private static void ready(GameTestHelper helper) {
        helper.assertTrue(ChronicleTestMod.ready.isDone(), "Waiting for Chronicle startup"); ChronicleTestMod.ready.join();
    }
    private static void click(ServerPlayer player, BlockPos pos, ItemStack held, Direction face, Vec3 hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, player.serverLevel(), held, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }
    private static void click(ServerPlayer player, BlockPos pos, ItemStack held) { click(player, pos, held, Direction.UP, Vec3.atCenterOf(pos)); }
    private record Row(String type, String item, int count, String storage, BlockPos pos, AuditEvent event) {}
    private static Runnable persisted(GameTestHelper helper, Predicate<AuditEvent> filter, int count, Consumer<List<Row>> check) {
        var query = new Object() { CompletableFuture<List<AuditEvent>> pending; };
        return () -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            var events = query.pending.join().stream().filter(e -> !e.legacy() && e.envelope().eventType().startsWith("ITEM_") && filter.test(e)).toList();
            query.pending = null;
            helper.assertTrue(events.size() == count, "Expected " + count + " item events, got " + events.size());
            var codec = new ItemTransactionPayloadCodec();
            check.accept(events.stream().map(e -> {
                var payload = codec.decode(e.payloadVersion(), e.payload()); var en = e.envelope();
                return new Row(en.eventType(), en.targetId(), payload.count(), payload.containerBlockId(), new BlockPos(en.x(), en.y(), en.z()), e);
            }).toList());
        };
    }
    private static void expect(GameTestHelper helper, List<Row> rows, String type, String item, int count, String storage, BlockPos pos) {
        long matching = rows.stream().filter(r -> r.type.equals(type) && r.item.equals(item) && r.count == count
                && r.storage.equals(storage) && r.pos.equals(pos)).count();
        helper.assertTrue(matching == 1, "Expected one " + type + " ×" + count + " " + item + " from " + storage + " at " + pos.toShortString() + " in " + rows);
    }
    private static final class Output implements CommandSource {
        final MinecraftServer server;
        final List<String> lines = new ArrayList<>();
        Output(MinecraftServer server) { this.server = server; }
        public void sendSystemMessage(Component message) {
            if (!server.isSameThread()) throw new IllegalStateException("Output off server thread");
            lines.add(ChatEvidence.text(message));
        }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    private static List<String> lookup(GameTestHelper helper, Object[] state, String query, int rows) {
        if (state[0] == null) {
            var output = new Output(helper.getLevel().getServer());
            state[0] = output;
            var source = helper.getLevel().getServer().createCommandSourceStack().withPermission(2).withSource(output);
            try { helper.getLevel().getServer().getCommands().getDispatcher().execute("chronicle lookup " + query, source); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
        }
        var output = (Output)state[0];
        helper.assertTrue(!output.lines.isEmpty(), "Waiting for production command reply");
        var result = output.lines.stream().filter(l -> l.contains("Event ID: ")).toList();
        if (result.size() != rows) { state[0] = null; helper.fail("Waiting for " + rows + " lookup rows, got " + result.size()); }
        return result;
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void playerChestBreakSpillsContentsWithoutBlamingViewer(GameTestHelper helper) {
        var level = helper.getLevel(); var breaker = player(helper, "SpillBreaker"); var viewer = player(helper, "SpillViewer");
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var lookup = new Object[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            var chest = (Container)level.getBlockEntity(pos);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 64)); chest.setItem(5, new ItemStack(Items.DIAMOND, 64));
            chest.setItem(9, new ItemStack(Items.DIAMOND, 3));
            var blade = new ItemStack(Items.DIAMOND_SWORD); blade.set(DataComponents.CUSTOM_NAME, Component.literal("Spilled Blade"));
            chest.setItem(20, blade);
            viewer.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            helper.assertTrue(viewer.openMenu(level.getBlockState(pos).getMenuProvider(level, pos)).isPresent(), "Viewer opens chest");
            chest.setItem(10, new ItemStack(Items.GOLD_INGOT, 5));
            helper.assertTrue(breaker.gameMode.destroyBlock(pos), "Player break succeeds");
            helper.assertTrue(level.getBlockState(pos).isAir(), "Chest removed");
            // Closing before the tick-boundary settlement must not credit the viewer with the spilled contents.
            viewer.closeContainer();
        }).thenWaitUntil(persisted(helper,
                e -> breaker.getUUID().equals(e.envelope().actorUuid()) || viewer.getUUID().equals(e.envelope().actorUuid()), 4, rows -> {
            var spill = rows.stream().filter(r -> r.event.envelope().actorUuid().equals(breaker.getUUID())).toList();
            helper.assertTrue(spill.size() == 3 && spill.stream().allMatch(r -> r.type.equals("ITEM_TAKE")), "Three spilled identities");
            expect(helper, spill, "ITEM_TAKE", "minecraft:diamond", 131, "minecraft:chest", pos);
            expect(helper, spill, "ITEM_TAKE", "minecraft:diamond_sword", 1, "minecraft:chest", pos);
            expect(helper, spill, "ITEM_TAKE", "minecraft:gold_ingot", 5, "minecraft:chest", pos);
            var viewed = rows.stream().filter(r -> r.event.envelope().actorUuid().equals(viewer.getUUID())).toList();
            helper.assertTrue(viewed.size() == 1, "Viewer credited only for the deposit before the break, not the spill");
            expect(helper, viewed, "ITEM_PUT", "minecraft:gold_ingot", 5, "minecraft:chest", pos);
        })).thenWaitUntil(() -> {
            var rows = lookup(helper, lookup, "r:global t:1h b:minecraft:diamond u:" + breaker.getUUID(), 1);
            String coordinates = "minecraft:overworld (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            for (String field : List.of("took ×131 diamond from chest", "Container: minecraft:chest", "Actor UUID: " + breaker.getUUID(), coordinates))
                helper.assertTrue(rows.getFirst().contains(field), "Lookup row field " + field);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void refusedBreakSpillsNothingAndExplosionSpillIsEnvironmental(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "SpillRefused");
        var refused = helper.absolutePos(new BlockPos(0, 1, 0)); var blasted = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(refused, Blocks.BARREL.defaultBlockState());
            ((Container)level.getBlockEntity(refused)).setItem(0, new ItemStack(Items.APPLE, 9));
            // A break hook that vanilla later abandons, as with blockActionRestricted or removeBlock failure.
            NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, refused, level.getBlockState(refused), player));
            level.setBlockAndUpdate(blasted, Blocks.BARREL.defaultBlockState());
            ((Container)level.getBlockEntity(blasted)).setItem(4, new ItemStack(Items.NAUTILUS_SHELL, 7));
            player.setPos(blasted.getX() + .5, blasted.getY(), blasted.getZ() + .5);
            helper.assertTrue(player.openMenu(level.getBlockState(blasted).getMenuProvider(level, blasted)).isPresent(), "Viewer opens barrel");
            level.explode(null, blasted.getX() + .5, blasted.getY() + .5, blasted.getZ() + .5, 2.0F, Level.ExplosionInteraction.TNT);
            // As ServerPlayer.tick would for the now-invalid menu, before the explosion is settled.
            player.closeContainer();
            helper.assertTrue(level.getBlockState(blasted).isAir(), "Explosion removed barrel");
            helper.assertTrue(level.getBlockState(refused).is(Blocks.BARREL), "Refused break left barrel");
        }).thenIdle(2).thenWaitUntil(persisted(helper, e -> {
            var en = e.envelope(); var at = new BlockPos(en.x(), en.y(), en.z());
            return player.getUUID().equals(en.actorUuid()) || at.equals(refused) || at.equals(blasted);
        }, 1, rows -> {
            expect(helper, rows, "ITEM_TAKE", "minecraft:nautilus_shell", 7, "minecraft:barrel", blasted);
            var envelope = rows.getFirst().event.envelope();
            helper.assertTrue(envelope.actorType().equals("ENVIRONMENT") && envelope.actorId().equals("#explosion")
                    && envelope.actorUuid() == null, "Explosion attributed to environment");
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void menulessStorageInteractionsAreSearchable(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "MenulessUser"); var bystander = player(helper, "MenulessBystander");
        var shelf = helper.absolutePos(new BlockPos(0, 1, 0)); var jukebox = helper.absolutePos(new BlockPos(2, 1, 0));
        var pot = helper.absolutePos(new BlockPos(4, 1, 0)); var campfire = helper.absolutePos(new BlockPos(6, 1, 0));
        var lectern = helper.absolutePos(new BlockPos(0, 1, 3)); var framePos = helper.absolutePos(new BlockPos(2, 1, 3));
        var glowPos = helper.absolutePos(new BlockPos(4, 1, 3));
        var frames = new Object() { ItemFrame frame; ItemFrame glow; };
        var lookup = new Object[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            player.setPos(shelf.getX() + .5, shelf.getY(), shelf.getZ() + .5);
            level.setBlockAndUpdate(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
            var front = new Vec3(shelf.getX() + .2, shelf.getY() + .8, shelf.getZ());
            click(player, shelf, new ItemStack(Items.BOOK), Direction.NORTH, front);
            helper.assertTrue(!((Container)level.getBlockEntity(shelf)).isEmpty(), "Book inserted");
            click(player, shelf, ItemStack.EMPTY, Direction.NORTH, front);
            helper.assertTrue(((Container)level.getBlockEntity(shelf)).isEmpty(), "Book extracted");

            level.setBlockAndUpdate(jukebox, Blocks.JUKEBOX.defaultBlockState());
            click(player, jukebox, new ItemStack(Items.MUSIC_DISC_CAT));
            helper.assertTrue(!((Container)level.getBlockEntity(jukebox)).isEmpty(), "Disc inserted");
            click(player, jukebox, ItemStack.EMPTY);
            helper.assertTrue(((Container)level.getBlockEntity(jukebox)).isEmpty(), "Disc ejected");

            level.setBlockAndUpdate(pot, Blocks.DECORATED_POT.defaultBlockState());
            click(player, pot, new ItemStack(Items.DIAMOND, 3));
            level.setBlockAndUpdate(campfire, Blocks.CAMPFIRE.defaultBlockState());
            click(player, campfire, new ItemStack(Items.BEEF, 2));

            level.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState());
            click(player, lectern, new ItemStack(Items.WRITABLE_BOOK));
            click(player, lectern, ItemStack.EMPTY);
            helper.assertTrue(player.containerMenu instanceof net.minecraft.world.inventory.LecternMenu, "Lectern menu opened");

            for (var pos : List.of(framePos, glowPos)) level.setBlockAndUpdate(pos.south(), Blocks.STONE.defaultBlockState());
            frames.frame = new ItemFrame(level, framePos, Direction.NORTH);
            frames.glow = new GlowItemFrame(level, glowPos, Direction.NORTH);
            level.addFreshEntity(frames.frame); level.addFreshEntity(frames.glow);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
            player.interactOn(frames.frame, InteractionHand.MAIN_HAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
            player.interactOn(frames.glow, InteractionHand.MAIN_HAND);
            // A click that changes nothing must not end the open lectern menu's watch.
            bystander.setShiftKeyDown(true);
            click(bystander, lectern, new ItemStack(Items.STICK));
            helper.assertTrue(!(bystander.containerMenu instanceof net.minecraft.world.inventory.LecternMenu), "Sneak-click opened no menu");
        }).thenIdle(1).thenExecute(() -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.interactOn(frames.frame, InteractionHand.MAIN_HAND);
            helper.assertTrue(frames.frame.getRotation() == 1, "Second click rotates framed item");
            player.attack(frames.frame); player.attack(frames.glow);
            helper.assertTrue(frames.frame.getItem().isEmpty() && frames.glow.getItem().isEmpty(), "Attacks removed framed items");
            player.containerMenu.clickMenuButton(player, 3);
        }).thenIdle(1).thenExecute(() -> {
            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.WRITABLE_BOOK)), "Lectern book taken through menu");
            player.closeContainer();
        }).thenWaitUntil(persisted(helper, e -> player.getUUID().equals(e.envelope().actorUuid()) || bystander.getUUID().equals(e.envelope().actorUuid()), 12, rows -> {
            expect(helper, rows, "ITEM_PUT", "minecraft:book", 1, "minecraft:chiseled_bookshelf", shelf);
            expect(helper, rows, "ITEM_TAKE", "minecraft:book", 1, "minecraft:chiseled_bookshelf", shelf);
            expect(helper, rows, "ITEM_PUT", "minecraft:music_disc_cat", 1, "minecraft:jukebox", jukebox);
            expect(helper, rows, "ITEM_TAKE", "minecraft:music_disc_cat", 1, "minecraft:jukebox", jukebox);
            expect(helper, rows, "ITEM_PUT", "minecraft:diamond", 1, "minecraft:decorated_pot", pot);
            expect(helper, rows, "ITEM_PUT", "minecraft:beef", 1, "minecraft:campfire", campfire);
            expect(helper, rows, "ITEM_PUT", "minecraft:writable_book", 1, "minecraft:lectern", lectern);
            expect(helper, rows, "ITEM_TAKE", "minecraft:writable_book", 1, "minecraft:lectern", lectern);
            expect(helper, rows, "ITEM_PUT", "minecraft:diamond", 1, "minecraft:item_frame", framePos);
            expect(helper, rows, "ITEM_TAKE", "minecraft:diamond", 1, "minecraft:item_frame", framePos);
            expect(helper, rows, "ITEM_PUT", "minecraft:emerald", 1, "minecraft:glow_item_frame", glowPos);
            expect(helper, rows, "ITEM_TAKE", "minecraft:emerald", 1, "minecraft:glow_item_frame", glowPos);
            helper.assertTrue(rows.stream().allMatch(r -> r.event.envelope().actorType().equals("PLAYER")), "Player actor");
        })).thenWaitUntil(() -> {
            var rows = lookup(helper, lookup, "r:global t:1h b:minecraft:book,minecraft:music_disc_cat u:" + player.getUUID(), 4);
            for (String fragment : List.of("put ×1 book in chiseled_bookshelf", "took ×1 book from chiseled_bookshelf",
                    "put ×1 music_disc_cat in jukebox", "took ×1 music_disc_cat from jukebox"))
                helper.assertTrue(rows.stream().filter(r -> r.contains(fragment)).count() == 1, "Lookup row " + fragment.strip());
            helper.assertTrue(rows.stream().filter(r -> r.contains("Container: minecraft:chiseled_bookshelf")).count() == 2, "Bookshelf named");
            helper.assertTrue(rows.stream().filter(r -> r.contains("Container: minecraft:jukebox")).count() == 2, "Jukebox named");
        }).thenSucceed();
    }
}
