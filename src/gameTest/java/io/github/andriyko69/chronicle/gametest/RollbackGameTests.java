/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.listener.PlayerBlockListener;
import io.github.andriyko69.chronicle.query.LookupParser;
import io.github.andriyko69.chronicle.rollback.RollbackEngine;
import io.github.andriyko69.chronicle.rollback.RollbackPlan;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

/**
 * Drives the production {@code /chronicle rollback|restore} commands against live world changes. Each test has its own
 * batch: the engine runs one job at a time, and the production database persists across runs, so actors are unique.
 */
@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class RollbackGameTests {
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
    private static String unique(String prefix) { return prefix + UUID.randomUUID().toString().substring(0, 8); }
    private static void ready(GameTestHelper helper) {
        helper.assertTrue(ChronicleTestMod.ready.isDone(), "Waiting for Chronicle startup"); ChronicleTestMod.ready.join();
    }
    private static BlockPos at(AuditEvent event) { return new BlockPos(event.envelope().x(), event.envelope().y(), event.envelope().z()); }
    private static void click(ServerPlayer player, BlockPos pos, ItemStack held, Direction face, Vec3 hit) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, player.serverLevel(), held, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
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
    private static final class Reply { Output output; }
    /** Runs a production command once, until a reply contains {@code until}; a busy engine or unsettled storage is retried. */
    private static Runnable command(GameTestHelper helper, Reply reply, String command, String until) {
        return () -> {
            var server = helper.getLevel().getServer();
            if (reply.output == null) {
                reply.output = new Output(server);
                var source = server.createCommandSourceStack().withPermission(2).withSource(reply.output);
                try { server.getCommands().getDispatcher().execute(command, source); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
            }
            var lines = reply.output.lines;
            if (lines.stream().anyMatch(line -> line.contains("try again"))) { reply.output = null; helper.fail("Retrying /" + command + ": " + lines); }
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains(until)), "Waiting for '" + until + "' from /" + command + ": " + lines);
        };
    }
    private static List<String> rows(Reply reply) { return reply.output.lines.stream().filter(line -> line.contains("Event ID: ")).toList(); }
    /** Fails while Chronicle's test runtime holds rows matching {@code echo}: rollback mutations must never be audited. */
    private static Runnable noAudit(GameTestHelper helper, Predicate<AuditEvent> echo) {
        var query = new Object() { CompletableFuture<List<AuditEvent>> pending; };
        return () -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            var echoes = query.pending.join().stream().filter(e -> !e.legacy() && echo.test(e)).toList();
            query.pending = null;
            if (!echoes.isEmpty()) throw new IllegalStateException("Rollback or restore was audited: " + echoes);
        };
    }
    private static int count(Container container, ItemStack template) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) total += stack.getCount();
        }
        return total;
    }
    /** This test's own structure only: neighbouring tests' drops (a cooking campfire) must not count. */
    private static AABB area(GameTestHelper helper) { return helper.getBounds().inflate(2); }
    private static void discardDrops(GameTestHelper helper) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper)).forEach(Entity::discard);
    }
    private static void noDrops(GameTestHelper helper) {
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(helper));
        helper.assertTrue(drops.isEmpty(), "No items dropped into the world: " + drops.stream().map(ItemEntity::getItem).toList());
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_blocks")
    public static void blockAndFluidChangesRollBackAndRestoreWithoutAuditing(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbBlocks"); var builder = player(helper, name);
        var base = helper.absolutePos(new BlockPos(1, 1, 1)); var placed = base.above();
        var dirt = helper.absolutePos(new BlockPos(3, 1, 1));
        var signBase = helper.absolutePos(new BlockPos(5, 1, 1)); var sign = signBase.above();
        var slab = helper.absolutePos(new BlockPos(1, 1, 4));
        var positions = Set.of(placed, dirt, sign, slab);
        var mark = new Object() { Instant since; };
        Reply preview = new Reply(), rollback = new Reply(), lookup = new Reply(), again = new Reply(), restore = new Reply(), after = new Reply();
        String scope = " r:global t:1h u:" + name;
        Runnable original = () -> {
            helper.assertTrue(level.getBlockState(placed).is(Blocks.GOLD_BLOCK), "Placed gold present");
            helper.assertTrue(level.getBlockState(dirt).isAir() && level.getBlockState(sign).isAir(), "Broken blocks absent");
            helper.assertTrue(level.getBlockState(slab).getValue(SlabBlock.WATERLOGGED), "Slab waterlogged");
        };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            builder.setGameMode(GameType.CREATIVE);
            level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
            builder.getMainHandItem().useOn(new UseOnContext(builder, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, .5, 0), Direction.UP, base, false)));
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            helper.assertTrue(builder.gameMode.destroyBlock(dirt), "Dirt broken");
            level.setBlockAndUpdate(signBase, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState());
            var entity = (SignBlockEntity)level.getBlockEntity(sign);
            entity.setText(entity.getFrontText().setMessage(0, Component.literal("Evidence")), true);
            helper.assertTrue(builder.gameMode.destroyBlock(sign), "Sign broken");
            level.setBlockAndUpdate(slab, Blocks.OAK_SLAB.defaultBlockState());
            builder.setPos(slab.getX() + .5, slab.getY() + 1, slab.getZ() + .5);
            builder.setXRot(90);
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            builder.gameMode.useItem(builder, level, builder.getMainHandItem(), InteractionHand.MAIN_HAND);
            original.run();
        }).thenIdle(3)
        .thenWaitUntil(command(helper, preview, "chronicle rb" + scope + " --preview", "Rollback Preview"))
        .thenExecute(() -> {
            helper.assertTrue(preview.output.lines.stream().anyMatch(line -> line.contains("Would roll back 4 block changes and 0 item changes")),
                    "Preview counts " + preview.output.lines);
            original.run();
            mark.since = Instant.now();
        })
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("blocks=4")), "Four blocks rolled back " + rollback.output.lines);
            helper.assertTrue(level.getBlockState(placed).isAir(), "Placement undone");
            helper.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT), "Dirt restored");
            helper.assertTrue(level.getBlockState(sign).is(Blocks.OAK_SIGN)
                    && ((SignBlockEntity)level.getBlockEntity(sign)).getFrontText().getMessage(0, false).getString().equals("Evidence"), "Sign and its text restored");
            helper.assertTrue(level.getBlockState(slab).is(Blocks.OAK_SLAB) && !level.getBlockState(slab).getValue(SlabBlock.WATERLOGGED), "Waterlogging undone");
        }).thenIdle(20)
        .thenWaitUntil(noAudit(helper, e -> !e.occurredAt().isBefore(mark.since) && positions.contains(at(e))))
        .thenWaitUntil(command(helper, lookup, "chronicle lookup" + scope, "Event ID: "))
        .thenExecute(() -> {
            var rows = rows(lookup);
            helper.assertTrue(rows.size() == 4 && rows.stream().allMatch(row -> row.contains("[RB]") && row.contains("Status: Rolled back")),
                    "Lookup marks every row rolled back: " + rows);
        })
        .thenWaitUntil(command(helper, again, "chronicle rb" + scope, "no matching changes to roll back"))
        .thenWaitUntil(command(helper, restore, "chronicle rs" + scope, "Restore complete"))
        .thenExecute(() -> {
            original.run();
            helper.assertTrue(restore.output.lines.stream().anyMatch(line -> line.contains("blocks=4")), "Four blocks restored " + restore.output.lines);
        }).thenIdle(20)
        .thenWaitUntil(noAudit(helper, e -> !e.occurredAt().isBefore(mark.since) && positions.contains(at(e))))
        .thenWaitUntil(command(helper, after, "chronicle lookup" + scope, "Event ID: "))
        .thenExecute(() -> {
            var rows = rows(after);
            helper.assertTrue(rows.size() == 4 && rows.stream().noneMatch(row -> row.contains("Rolled back")), "Restore cleared the state: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_storage")
    public static void containerAndStorageItemsRollBackAndRestoreWithoutFramingViewer(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbOwner"); var owner = player(helper, name); var viewer = player(helper, unique("RbViewer"));
        var chest = helper.absolutePos(new BlockPos(1, 1, 1));
        var framePos = helper.absolutePos(new BlockPos(3, 1, 1));
        var standPos = helper.absolutePos(new BlockPos(5, 1, 4));
        var shelf = helper.absolutePos(new BlockPos(1, 1, 5));
        var positions = Set.of(chest, framePos, standPos, shelf);
        var world = new Object() { ItemFrame frame; ArmorStand stand; ItemStack blade; Instant since; };
        Reply rollback = new Reply(), restore = new Reply();
        String scope = " r:global t:1h u:" + name;
        var diamonds = new ItemStack(Items.DIAMOND);
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            owner.setGameMode(GameType.SURVIVAL);
            var blade = new ItemStack(Items.DIAMOND_SWORD);
            blade.set(DataComponents.CUSTOM_NAME, Component.literal("Rollback Blade"));
            blade.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS), 3);
            blade.setDamageValue(9);
            world.blade = blade.copy();
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            var container = (Container)level.getBlockEntity(chest);
            container.setItem(0, blade);
            owner.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
            helper.assertTrue(owner.openMenu(level.getBlockState(chest).getMenuProvider(level, chest)).isPresent(), "Owner opens chest");
            container.setItem(0, ItemStack.EMPTY);
            container.setItem(4, new ItemStack(Items.DIAMOND, 16));
            owner.closeContainer();

            level.setBlockAndUpdate(framePos.south(), Blocks.STONE.defaultBlockState());
            world.frame = new ItemFrame(level, framePos, Direction.NORTH);
            level.addFreshEntity(world.frame);
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
            owner.interactOn(world.frame, InteractionHand.MAIN_HAND);

            level.setBlockAndUpdate(standPos.below(), Blocks.STONE.defaultBlockState());
            world.stand = new ArmorStand(level, standPos.getX() + .5, standPos.getY(), standPos.getZ() + .5);
            helper.assertTrue(level.addFreshEntity(world.stand), "Stand spawned");
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_CHESTPLATE));
            var at = new Vec3(0, 1.2, 0);
            var hooked = net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt(owner, world.stand, at, InteractionHand.MAIN_HAND);
            if (hooked == null) world.stand.interactAt(owner, at, InteractionHand.MAIN_HAND);

            level.setBlockAndUpdate(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
            owner.setPos(shelf.getX() + .5, shelf.getY(), shelf.getZ() - 1);
            click(owner, shelf, new ItemStack(Items.BOOK), Direction.NORTH, new Vec3(shelf.getX() + .2, shelf.getY() + .8, shelf.getZ()));
            helper.assertTrue(world.frame.getItem().is(Items.EMERALD) && world.stand.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
                    && !((Container)level.getBlockEntity(shelf)).isEmpty(), "Owner stored items");
        }).thenIdle(3).thenExecute(() -> {
            // An open menu across the rollback must not credit its viewer with the items that change under it.
            viewer.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
            helper.assertTrue(viewer.openMenu(level.getBlockState(chest).getMenuProvider(level, chest)).isPresent(), "Viewer opens chest");
            world.since = Instant.now();
        })
        .thenWaitUntil(command(helper, rollback, "chronicle rollback" + scope, "Rollback complete"))
        .thenExecute(() -> {
            var container = (Container)level.getBlockEntity(chest);
            helper.assertTrue(count(container, world.blade) == 1, "Exact custom blade returned");
            helper.assertTrue(count(container, diamonds) == 0, "Deposited diamonds removed");
            helper.assertTrue(world.frame.getItem().isEmpty(), "Framed emerald removed");
            helper.assertTrue(world.stand.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "Chestplate removed");
            helper.assertTrue(((Container)level.getBlockEntity(shelf)).isEmpty(), "Shelved book removed");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("items=5")), "Five item rows " + rollback.output.lines);
        })
        .thenWaitUntil(command(helper, restore, "chronicle restore" + scope, "Restore complete"))
        .thenExecute(() -> {
            var container = (Container)level.getBlockEntity(chest);
            helper.assertTrue(count(container, world.blade) == 0 && count(container, diamonds) == 16, "Take and deposit re-applied");
            helper.assertTrue(world.frame.getItem().is(Items.EMERALD), "Framed emerald restored");
            helper.assertTrue(world.stand.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "Chestplate restored");
            helper.assertTrue(!((Container)level.getBlockEntity(shelf)).isEmpty(), "Book restored");
            viewer.closeContainer();
            discardDrops(helper);
        }).thenIdle(20)
        .thenWaitUntil(noAudit(helper, e -> viewer.getUUID().equals(e.envelope().actorUuid())
                || !e.occurredAt().isBefore(world.since) && positions.contains(at(e))))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_spill")
    public static void brokenChestContentsComeBackExactlyOnce(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbSpill"); var breaker = player(helper, name);
        var snapshotted = helper.absolutePos(new BlockPos(1, 1, 1)); var oversized = helper.absolutePos(new BlockPos(4, 1, 1));
        var paper = new ItemStack(Items.PAPER);
        var blob = new CompoundTag(); blob.putByteArray("evidence", new byte[14_000]);
        paper.set(DataComponents.CUSTOM_DATA, CustomData.of(blob));
        var diamond = new ItemStack(Items.DIAMOND);
        Reply rollback = new Reply(), spills = new Reply(), restore = new Reply(), again = new Reply();
        String scope = " r:global t:1h u:" + name;
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            breaker.setGameMode(GameType.CREATIVE);
            level.setBlockAndUpdate(snapshotted, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(snapshotted)).setItem(3, new ItemStack(Items.DIAMOND, 10));
            // Five 14 KB stacks exceed the 64 KiB block snapshot ceiling; only the spill rows can bring them back.
            level.setBlockAndUpdate(oversized, Blocks.CHEST.defaultBlockState());
            for (int slot = 0; slot < 5; slot++) ((Container)level.getBlockEntity(oversized)).setItem(slot, paper.copy());
            helper.assertTrue(breaker.gameMode.destroyBlock(snapshotted) && breaker.gameMode.destroyBlock(oversized), "Chests broken");
        }).thenIdle(3).thenExecute(() -> discardDrops(helper))
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(snapshotted).is(Blocks.CHEST) && level.getBlockState(oversized).is(Blocks.CHEST), "Chests restored");
            int restored = count((Container)level.getBlockEntity(snapshotted), diamond);
            helper.assertTrue(restored == 10, "Snapshot chest holds exactly 10 diamonds, got " + restored);
            int papers = count((Container)level.getBlockEntity(oversized), paper);
            helper.assertTrue(papers == 5, "Snapshot-less chest refilled from its spill row exactly once, got " + papers);
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("blocks=2 · items=1 · spill rows covered by snapshots=1")),
                    "One spill covered by the snapshot, one inserted " + rollback.output.lines);
            noDrops(helper);
        })
        .thenWaitUntil(command(helper, spills, "chronicle rb" + scope + " a:take", "no matching changes to roll back"))
        .thenWaitUntil(command(helper, restore, "chronicle rs" + scope, "Restore complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(snapshotted).isAir() && level.getBlockState(oversized).isAir(), "Chests removed again");
            helper.assertTrue(restore.output.lines.stream().anyMatch(line -> line.contains("spill rows covered by snapshots=2"))
                    && restore.output.lines.stream().noneMatch(line -> line.contains("Emptied")), "Spilled contents leave with their breaks " + restore.output.lines);
            noDrops(helper);
        })
        .thenWaitUntil(command(helper, again, "chronicle rs" + scope + " a:take", "no matching changes to restore"))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_open_menu")
    public static void depositInAnUnclosedMenuSurvivesRollbackAndRestore(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbDepositor"); var depositor = player(helper, name);
        var base = helper.absolutePos(new BlockPos(1, 1, 1)); var chest = base.above();
        Reply rollback = new Reply(), restore = new Reply();
        String scope = " r:global t:1h u:" + name;
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            depositor.setGameMode(GameType.SURVIVAL);
            level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            depositor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            depositor.getMainHandItem().useOn(new UseOnContext(depositor, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, .5, 0), Direction.UP, base, false)));
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "Depositor placed the chest");
            depositor.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
            helper.assertTrue(depositor.openMenu(level.getBlockState(chest).getMenuProvider(level, chest)).isPresent(), "Depositor opens chest");
            // The menu stays open: its deposit exists only as a pending session until the rollback is planned.
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 16));
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(chest).isAir(), "Placement rolled back");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("blocks=1 · items=1")),
                    "The open-menu deposit was planned and rolled back first " + rollback.output.lines);
            helper.assertTrue(rollback.output.lines.stream().noneMatch(line -> line.contains("Emptied")), "No contents discarded " + rollback.output.lines);
            noDrops(helper);
        })
        .thenWaitUntil(command(helper, restore, "chronicle rs" + scope, "Restore complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "Placement restored");
            int diamonds = count((Container)level.getBlockEntity(chest), new ItemStack(Items.DIAMOND));
            helper.assertTrue(diamonds == 16, "Deposit restored with its chest, got " + diamonds);
            depositor.closeContainer();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void lossySnapshotIsRebuiltFromItsSpillRows(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        String name = unique("RbLossy"); var actor = UUID.randomUUID();
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var state = new Object() { CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var snapshot = new CompoundTag();
            snapshot.putString("id", "minecraft:chest");
            snapshot.putInt("x", pos.getX()); snapshot.putInt("y", pos.getY()); snapshot.putInt("z", pos.getZ());
            var items = new ListTag();
            var diamonds = new CompoundTag(); diamonds.putByte("Slot", (byte)0); diamonds.putString("id", "minecraft:diamond"); diamonds.putInt("count", 10);
            // Vanilla silently skips a saved stack it cannot place, as it does an undecodable one: the load "succeeds".
            var lost = new CompoundTag(); lost.putByte("Slot", (byte)40); lost.putString("id", "minecraft:emerald"); lost.putInt("count", 4);
            items.add(diamonds); items.add(lost);
            snapshot.put("Items", items);
            var bytes = new java.io.ByteArrayOutputStream();
            try { NbtIo.writeUnnamedTag(snapshot, new java.io.DataOutputStream(bytes)); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            var now = Instant.now();
            var removed = PlayerBlockListener.state(Blocks.CHEST.defaultBlockState());
            var events = List.of(
                    AuditEvent.block(EventId.generate(), now, envelope(level, pos, "BLOCK_BREAK", name, actor, "minecraft:chest"),
                            new BlockDeltaPayload(removed, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION,
                                    BlockDeltaPayload.SnapshotStatus.PRESENT, bytes.toByteArray())),
                    AuditEvent.item(EventId.generate(), now.plusMillis(50), envelope(level, pos, "ITEM_TAKE", name, actor, "minecraft:diamond"), spill(10)),
                    AuditEvent.item(EventId.generate(), now.plusMillis(50), envelope(level, pos, "ITEM_TAKE", name, actor, "minecraft:emerald"), spill(4)));
            for (var event : events)
                helper.assertTrue(ChronicleTestMod.runtime.submit(event).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            var spec = new LookupParser(ChronicleClock.system()).parseRollback("r:global t:1h u:" + name, LookupParser.Context.console()).spec();
            state.plan = ChronicleTestMod.runtime.planRollback(spec, false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var plan = state.plan.join();
            helper.assertTrue(plan.steps().size() == 1 && plan.steps().getFirst().companions().size() == 2, "Break carries both spills: " + plan);
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, plan, report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            var report = state.report;
            helper.assertTrue(report.failure() == null && report.blocks() == 1 && report.items() == 1 && report.covered() == 1,
                    "The loaded spill is covered and only the lost one inserted: " + report);
            var container = (Container)level.getBlockEntity(pos);
            int diamonds = count(container, new ItemStack(Items.DIAMOND)), emeralds = count(container, new ItemStack(Items.EMERALD));
            helper.assertTrue(diamonds == 10 && emeralds == 4, "Each spilled item restored exactly once: " + diamonds + " diamonds, " + emeralds + " emeralds");
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_replaced")
    public static void chestReplacedBeforeItsSpillSettlesKeepsTheSpillWithItsBreak(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbReplace"); var builder = player(helper, name);
        var base = helper.absolutePos(new BlockPos(1, 1, 1)); var chest = base.above();
        Reply blocks = new Reply(), items = new Reply();
        String scope = " r:global t:1h u:" + name;
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            builder.setGameMode(GameType.CREATIVE);
            level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(builder.gameMode.destroyBlock(chest), "Chest broken");
            // Replaced within the same tick, before the spill would settle at the tick boundary.
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            builder.getMainHandItem().useOn(new UseOnContext(builder, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, .5, 0), Direction.UP, base, false)));
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST) && ((Container)level.getBlockEntity(chest)).isEmpty(), "Empty replacement placed");
        }).thenIdle(3).thenExecute(() -> discardDrops(helper))
        .thenWaitUntil(command(helper, blocks, "chronicle rb" + scope + " a:block", "Rollback complete"))
        .thenExecute(() -> {
            int diamonds = count((Container)level.getBlockEntity(chest), new ItemStack(Items.DIAMOND));
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST) && diamonds == 10, "Original chest restored with 10 diamonds, got " + diamonds);
            helper.assertTrue(blocks.output.lines.stream().anyMatch(line -> line.contains("spill rows covered by snapshots=1")), "Spill covered " + blocks.output.lines);
        })
        .thenWaitUntil(command(helper, items, "chronicle rb" + scope + " a:take", "no matching changes to roll back"))
        .thenExecute(() -> {
            int diamonds = count((Container)level.getBlockEntity(chest), new ItemStack(Items.DIAMOND));
            helper.assertTrue(diamonds == 10, "No second copy of the spill, got " + diamonds);
            noDrops(helper);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void rejectedSnapshotLeavesWorldAndRowRetryable(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(4, 1, 1));
        String name = unique("RbRejected"); var actor = UUID.randomUUID();
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var state = new Object() { LookupParser.Rollback request; CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            // A sign recorded with another block entity's data: loading it would fail or corrupt the sign.
            var snapshot = new CompoundTag();
            snapshot.putString("id", "minecraft:chest");
            snapshot.putInt("x", pos.getX()); snapshot.putInt("y", pos.getY()); snapshot.putInt("z", pos.getZ());
            var bytes = new java.io.ByteArrayOutputStream();
            try { NbtIo.writeUnnamedTag(snapshot, new java.io.DataOutputStream(bytes)); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            var removed = PlayerBlockListener.state(Blocks.OAK_SIGN.defaultBlockState());
            var event = AuditEvent.block(EventId.generate(), Instant.now(), envelope(level, pos, "BLOCK_BREAK", name, actor, "minecraft:oak_sign"),
                    new BlockDeltaPayload(removed, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION,
                            BlockDeltaPayload.SnapshotStatus.PRESENT, bytes.toByteArray()));
            helper.assertTrue(ChronicleTestMod.runtime.submit(event).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            state.request = new LookupParser(ChronicleClock.system()).parseRollback("r:global t:1h u:" + name, LookupParser.Context.console());
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, state.plan.join(), report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            var report = state.report;
            helper.assertTrue(report.blocks() == 0 && report.skipped().equals(Map.of("saved block data incompatible", 1)), "Explicitly skipped: " + report);
            helper.assertTrue(level.getBlockState(pos).isAir(), "World untouched");
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the second plan"))
        .thenExecute(() -> helper.assertTrue(state.plan.join().steps().size() == 1, "The skipped row stays eligible for a later rollback"))
        .thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_sign")
    public static void brokenSignRestoresItsOwnTextOverASameTypeReplacement(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbSign"); var builder = player(helper, name);
        var base = helper.absolutePos(new BlockPos(1, 1, 1)); var sign = base.above();
        var original = new Object() { net.minecraft.world.level.block.state.BlockState state; };
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            builder.setGameMode(GameType.CREATIVE);
            level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            original.state = Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 4);
            level.setBlockAndUpdate(sign, original.state);
            var entity = (SignBlockEntity)level.getBlockEntity(sign);
            entity.setText(entity.getFrontText().setMessage(0, Component.literal("Original")), true);
            helper.assertTrue(builder.gameMode.destroyBlock(sign), "Sign broken");
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_SIGN));
            builder.getMainHandItem().useOn(new UseOnContext(builder, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, .5, 0), Direction.UP, base, false)));
            helper.assertTrue(level.getBlockState(sign).is(Blocks.OAK_SIGN), "Replacement sign placed");
            var replacement = (SignBlockEntity)level.getBlockEntity(sign);
            replacement.setText(replacement.getFrontText().setMessage(0, Component.literal("Replacement")), true);
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h a:break u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            var entity = (SignBlockEntity)level.getBlockEntity(sign);
            helper.assertTrue(level.getBlockState(sign) == original.state && entity != null
                    && entity.getFrontText().getMessage(0, false).getString().equals("Original"), "The broken sign's own state and text are back");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("blocks=1")), "One break rolled back " + rollback.output.lines);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_structures")
    public static void brokenDoorsAndBedsComeBackWhole(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbHalves"); var breaker = player(helper, name);
        var door = helper.absolutePos(new BlockPos(1, 2, 1)); var bed = helper.absolutePos(new BlockPos(3, 2, 1));
        var blocked = helper.absolutePos(new BlockPos(1, 2, 4));
        var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        var foot = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            breaker.setGameMode(GameType.CREATIVE);
            for (var pos : List.of(door, bed, bed.east(), blocked)) level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            for (var pos : List.of(door, blocked)) {
                level.setBlock(pos, lower, Block.UPDATE_CLIENTS);
                level.setBlock(pos.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
            }
            level.setBlock(bed, foot, Block.UPDATE_CLIENTS);
            level.setBlock(bed.east(), foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), Block.UPDATE_CLIENTS);
            for (var pos : List.of(door, bed, blocked)) helper.assertTrue(breaker.gameMode.destroyBlock(pos), "Broke " + pos.toShortString());
            for (var pos : List.of(door, door.above(), bed, bed.east(), blocked, blocked.above()))
                helper.assertTrue(level.getBlockState(pos).isAir(), "Both halves gone at " + pos.toShortString());
            // Something now occupies where the obstructed door's upper half stood.
            level.setBlockAndUpdate(blocked.above(), Blocks.STONE.defaultBlockState());
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(door) == lower
                    && level.getBlockState(door.above()) == lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), "Door restored whole");
            helper.assertTrue(level.getBlockState(bed).is(Blocks.RED_BED) && level.getBlockState(bed).getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    && level.getBlockState(bed.east()).is(Blocks.RED_BED) && level.getBlockState(bed.east()).getValue(BlockStateProperties.BED_PART) == BedPart.HEAD,
                    "Bed restored whole");
            helper.assertTrue(level.getBlockState(blocked).isAir() && level.getBlockState(blocked.above()).is(Blocks.STONE), "Obstructed door left untouched");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("blocks=2"))
                    && rollback.output.lines.stream().anyMatch(line -> line.contains("Skipped 1 changes") && line.contains("obstructed: 1")),
                    "Two structures restored, the obstructed one skipped explicitly " + rollback.output.lines);
            noDrops(helper);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void appliedButUnrecordedChangesAreQuarantinedNotReplayed(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(7, 1, 1));
        String name = unique("RbQuarantine"); var actor = UUID.randomUUID();
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var stone = PlayerBlockListener.state(Blocks.STONE.defaultBlockState());
        var stored = AuditEvent.block(EventId.generate(), Instant.now(), envelope(level, pos, "BLOCK_BREAK", name, actor, "minecraft:stone"),
                new BlockDeltaPayload(stone, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
        // Never persisted, so recording the slice fails as a lost storage write would, after the world already changed.
        var unrecordable = AuditEvent.block(EventId.generate(), Instant.now(), envelope(level, pos.east(), "BLOCK_BREAK", name, actor, "minecraft:stone"),
                new BlockDeltaPayload(stone, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
        var state = new Object() { LookupParser.Rollback request; CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            helper.assertTrue(ChronicleTestMod.runtime.submit(stored).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            state.request = new LookupParser(ChronicleClock.system()).parseRollback("r:global t:1h u:" + name, LookupParser.Context.console());
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var steps = new ArrayList<>(state.plan.join().steps());
            helper.assertTrue(steps.size() == 1, "Stored break planned");
            steps.add(new RollbackPlan.Step(unrecordable, List.of()));
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, new RollbackPlan(false, steps), report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            var report = state.report;
            helper.assertTrue(report.failure() != null && report.failure().contains("quarantined"), "Failure reported with its quarantine: " + report);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE) && level.getBlockState(pos.east()).is(Blocks.STONE), "World changes were applied");
            String quarantine;
            try { quarantine = java.nio.file.Files.readString(ChronicleTestMod.runtime.quarantineFile()); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            helper.assertTrue(quarantine.contains(stored.id() + " 1") && quarantine.contains(unrecordable.id() + " 1"), "Both rows quarantined: " + quarantine);
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the second plan"))
        .thenExecute(() -> {
            var again = state.plan.join();
            helper.assertTrue(again.steps().isEmpty() && again.quarantined() == 1, "The applied break is never replayed: " + again);
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_replaced_viewer")
    public static void viewerOfASameTypeReplacementIsNotCreditedWithRestoredContents(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbBreaker"); var breaker = player(helper, name);
        var replacer = player(helper, unique("RbReplacer"));
        var base = helper.absolutePos(new BlockPos(1, 1, 1)); var chest = base.above();
        var mark = new Object() { Instant since; };
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            breaker.setGameMode(GameType.CREATIVE); replacer.setGameMode(GameType.SURVIVAL);
            level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(breaker.gameMode.destroyBlock(chest), "Chest broken");
        }).thenIdle(2).thenExecute(() -> {
            discardDrops(helper);
            replacer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
            replacer.getMainHandItem().useOn(new UseOnContext(replacer, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, .5, 0), Direction.UP, base, false)));
            replacer.setPos(chest.getX() + .5, chest.getY(), chest.getZ() + .5);
            helper.assertTrue(replacer.openMenu(level.getBlockState(chest).getMenuProvider(level, chest)).isPresent(), "Replacer views the replacement");
            mark.since = Instant.now();
        }).thenIdle(2)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h a:break u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            int diamonds = count((Container)level.getBlockEntity(chest), new ItemStack(Items.DIAMOND));
            helper.assertTrue(diamonds == 10, "Broken chest's contents restored once, got " + diamonds);
            replacer.closeContainer();
        }).thenIdle(20)
        .thenWaitUntil(noAudit(helper, e -> replacer.getUUID().equals(e.envelope().actorUuid()) && !e.occurredAt().isBefore(mark.since)))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void shutdownAfterAnUnobservedRecordFailureStillQuarantines(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(1, 1, 6));
        String name = unique("RbStopped");
        // Not registered: the test steps it, so the record fails with no tick in between to observe it.
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        var stone = PlayerBlockListener.state(Blocks.STONE.defaultBlockState());
        var unrecordable = AuditEvent.block(EventId.generate(), Instant.now(), envelope(level, pos, "BLOCK_BREAK", name, UUID.randomUUID(), "minecraft:stone"),
                new BlockDeltaPayload(stone, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
        var state = new Object() { RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, new RollbackPlan(false, List.of(new RollbackPlan.Step(unrecordable, List.of()))),
                    report -> state.report = report);
            engine.tick(server);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Change applied before its state was recorded");
        }).thenWaitUntil(() -> {
            String quarantine = quarantine();
            helper.assertTrue(!quarantine.contains(unrecordable.id().toString()), "Not quarantined before shutdown is observed");
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            engine.stop(level.getServer());
            helper.assertTrue(state.report != null && state.report.failure().contains("server stopping"), "Stopped: " + state.report);
            helper.assertTrue(quarantine().contains(unrecordable.id() + " 1"), "Failed record quarantined at shutdown");
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void storageFailingPartWayIsUndoneOrQuarantined(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbFaulty"); var actor = UUID.randomUUID();
        var appliedPos = helper.absolutePos(new BlockPos(1, 1, 8)); var undonePos = helper.absolutePos(new BlockPos(3, 1, 8));
        var stuckPos = helper.absolutePos(new BlockPos(5, 1, 8));
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var stone = PlayerBlockListener.state(Blocks.STONE.defaultBlockState());
        var applied = AuditEvent.block(EventId.generate(), Instant.now(), envelope(level, appliedPos, "BLOCK_BREAK", name, actor, "minecraft:stone"),
                new BlockDeltaPayload(stone, BlockStateSnapshot.AIR, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
        var hundred = new ItemTransactionPayload(100, "minecraft:barrel", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null);
        var undone = AuditEvent.item(EventId.generate(), Instant.now(), envelope(level, undonePos, "ITEM_TAKE", name, actor, "minecraft:diamond"), hundred);
        var stuck = AuditEvent.item(EventId.generate(), Instant.now(), envelope(level, stuckPos, "ITEM_TAKE", name, actor, "minecraft:diamond"), hundred);
        var state = new Object() { LookupParser.Rollback request; CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : List.of(undonePos, stuckPos)) level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
            // A modded storage that throws on its second write; the second one keeps throwing, so undo fails too.
            level.setBlockEntity(new net.minecraft.world.level.block.entity.BarrelBlockEntity(undonePos, level.getBlockState(undonePos)) {
                int writes;
                @Override public void setItem(int slot, ItemStack stack) { if (++writes == 2) throw new IllegalStateException("injected"); super.setItem(slot, stack); }
            });
            level.setBlockEntity(new net.minecraft.world.level.block.entity.BarrelBlockEntity(stuckPos, level.getBlockState(stuckPos)) {
                int writes;
                @Override public void setItem(int slot, ItemStack stack) { if (++writes >= 2) throw new IllegalStateException("injected"); super.setItem(slot, stack); }
            });
            helper.assertTrue(ChronicleTestMod.runtime.submit(applied).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            state.request = new LookupParser(ChronicleClock.system()).parseRollback("r:global t:1h u:" + name, LookupParser.Context.console());
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var steps = new ArrayList<>(state.plan.join().steps());
            steps.add(new RollbackPlan.Step(undone, List.of()));
            steps.add(new RollbackPlan.Step(stuck, List.of()));
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, new RollbackPlan(false, steps), report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            var report = state.report;
            helper.assertTrue(report.blocks() == 1 && report.failure() != null && report.failure().contains("failed part-way")
                    && report.failure().contains("quarantined") && report.skipped().equals(Map.of("failed (see server log)", 1)), "Report: " + report);
            helper.assertTrue(level.getBlockState(appliedPos).is(Blocks.STONE), "Earlier change applied");
            helper.assertTrue(((Container)level.getBlockEntity(undonePos)).isEmpty(), "A failed write was fully undone");
            String held = quarantine();
            helper.assertTrue(held.contains(stuck.id() + " 1") && !held.contains(undone.id().toString()) && !held.contains(applied.id().toString()),
                    "Only the change in an unknown state is quarantined: " + held);
            state.plan = ChronicleTestMod.runtime.planRollback(state.request.spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the second plan"))
        .thenExecute(() -> helper.assertTrue(state.plan.join().steps().isEmpty(), "The applied change before the failure was recorded"))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_lossy")
    public static void snapshotLossesAreFilledInWithoutDiscardingOrDoubleCountingItems(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(7, 1, 4));
        String name = unique("RbMixed"); var actor = UUID.randomUUID();
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var state = new Object() { CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; ItemStack paper; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            // Over 16 KiB of components: the block snapshot keeps them, the spill rows cannot.
            java.util.function.Function<String, ItemStack> oversized = variant -> {
                var stack = new ItemStack(variant.equals("paper") ? Items.PAPER : Items.DIAMOND_SWORD);
                var data = new CompoundTag(); data.putString("variant", variant); data.putByteArray("evidence", new byte[17_000]);
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                return stack;
            };
            state.paper = oversized.apply("paper");
            var items = new ListTag();
            java.util.function.BiConsumer<Integer, ItemStack> saved = (slot, stack) -> {
                var tag = (CompoundTag)stack.save(level.registryAccess()); tag.putByte("Slot", slot.byteValue()); items.add(tag);
            };
            saved.accept(0, state.paper);
            saved.accept(1, new ItemStack(Items.DIAMOND, 3));
            saved.accept(2, oversized.apply("a"));
            // Out of range for a chest: silently dropped on load, like an undecodable stack.
            saved.accept(40, new ItemStack(Items.DIAMOND, 5));
            saved.accept(41, oversized.apply("b"));
            var snapshot = new CompoundTag();
            snapshot.putString("id", "minecraft:chest");
            snapshot.putInt("x", pos.getX()); snapshot.putInt("y", pos.getY()); snapshot.putInt("z", pos.getZ());
            snapshot.put("Items", items);
            var bytes = new java.io.ByteArrayOutputStream();
            try { NbtIo.writeUnnamedTag(snapshot, new java.io.DataOutputStream(bytes)); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            helper.assertTrue(bytes.size() <= BlockDeltaPayload.MAX_NBT_BYTES, "Snapshot within the ceiling");
            var now = Instant.now();
            var spilled = now.plusMillis(50);
            java.util.function.BiFunction<String, Integer, AuditEvent> spill = (item, count) -> AuditEvent.item(EventId.generate(), spilled,
                    envelope(level, pos, "ITEM_TAKE", name, actor, item), new ItemTransactionPayload(count, "minecraft:chest", null, new TreeMap<>(), null,
                            item.equals("minecraft:diamond") ? ItemTransactionPayload.ComponentSnapshotStatus.NONE : ItemTransactionPayload.ComponentSnapshotStatus.OVERSIZED, null));
            var events = List.of(
                    AuditEvent.block(EventId.generate(), now, envelope(level, pos, "BLOCK_BREAK", name, actor, "minecraft:chest"),
                            new BlockDeltaPayload(PlayerBlockListener.state(Blocks.CHEST.defaultBlockState()), BlockStateSnapshot.AIR,
                                    BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.PRESENT, bytes.toByteArray())),
                    spill.apply("minecraft:paper", 1), spill.apply("minecraft:diamond", 8),
                    spill.apply("minecraft:diamond_sword", 1), spill.apply("minecraft:diamond_sword", 1));
            for (var event : events)
                helper.assertTrue(ChronicleTestMod.runtime.submit(event).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            state.plan = ChronicleTestMod.runtime.planRollback(new LookupParser(ChronicleClock.system())
                    .parseRollback("r:global t:1h u:" + name, LookupParser.Context.console()).spec(), false).toCompletableFuture();
        }).thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var plan = state.plan.join();
            helper.assertTrue(plan.steps().size() == 1 && plan.steps().getFirst().companions().size() == 4, "Break carries all four spills: " + plan);
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, plan, report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            var report = state.report;
            // Paper and one sword are covered; 5 lost diamonds are inserted; the lost sword variant cannot be rebuilt and is not claimed.
            helper.assertTrue(report.failure() == null && report.covered() == 2 && report.items() == 1
                    && report.skipped().equals(Map.of("item not reconstructable", 1)), "Report: " + report);
            var container = (Container)level.getBlockEntity(pos);
            helper.assertTrue(count(container, state.paper) == 1, "The oversized paper loaded from the snapshot was kept intact");
            helper.assertTrue(count(container, new ItemStack(Items.DIAMOND)) == 8, "Diamonds completed to the spilled 8");
            int swords = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).is(Items.DIAMOND_SWORD)) swords++;
            helper.assertTrue(swords == 1, "One sword survived; it covers only one of the two sword rows");
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_powered_tnt")
    public static void tntBesideActivePowerIsNotRestoredToIgnite(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbPowered"); var breaker = player(helper, name);
        var tnt = helper.absolutePos(new BlockPos(2, 1, 2)); var power = tnt.east();
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            breaker.setGameMode(GameType.CREATIVE);
            level.setBlock(tnt, Blocks.TNT.defaultBlockState(), Block.UPDATE_CLIENTS);
            // Powered without a neighbour update, so the TNT stays a block until something re-places it.
            level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.assertTrue(breaker.gameMode.destroyBlock(tnt), "TNT broken");
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(tnt).isAir(), "TNT not restored");
            helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, new AABB(tnt).inflate(4)).isEmpty(), "Nothing primed");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("TNT beside an active power source: 1")),
                    "Skipped explicitly " + rollback.output.lines);
            level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_restore_extra")
    public static void restoreReportsContentsAddedSinceTheRollback(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbExtra"); var breaker = player(helper, name);
        var chest = helper.absolutePos(new BlockPos(1, 1, 1));
        Reply rollback = new Reply(), restore = new Reply();
        String scope = " r:global t:1h u:" + name;
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            breaker.setGameMode(GameType.CREATIVE);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(breaker.gameMode.destroyBlock(chest), "Chest broken");
        }).thenIdle(3).thenExecute(() -> discardDrops(helper))
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> ((Container)level.getBlockEntity(chest)).setItem(5, new ItemStack(Items.EMERALD, 7)))
        .thenWaitUntil(command(helper, restore, "chronicle rs" + scope, "Restore complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(chest).isAir(), "Break re-applied");
            helper.assertTrue(restore.output.lines.stream().anyMatch(line -> line.contains("Emptied 1 replaced containers")),
                    "Deleting the later emeralds is reported " + restore.output.lines);
            noDrops(helper);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_bookshelf")
    public static void chiseledBookshelfBooksReturnToTheirExactSlots(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbShelf"); var reader = player(helper, name);
        var shelf = helper.absolutePos(new BlockPos(2, 1, 2));
        Reply rollback = new Reply(), restore = new Reply(), lookup = new Reply();
        String scope = " r:global t:1h u:" + name;
        // Facing north, the face's horizontal axis runs east to west: x' = 1 - dx picks the column, y picks the row.
        java.util.function.BiFunction<Double, Double, Vec3> face = (dx, dy) -> new Vec3(shelf.getX() + dx, shelf.getY() + dy, shelf.getZ());
        java.util.function.IntFunction<Boolean> occupied = slot -> !((Container)level.getBlockEntity(shelf)).getItem(slot).isEmpty();
        Runnable original = () -> {
            for (int slot = 0; slot < 6; slot++)
                helper.assertTrue(occupied.apply(slot) == (slot == 0 || slot == 1 || slot == 3), "Slot " + slot + " after the player's changes");
        };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            reader.setGameMode(GameType.SURVIVAL);
            level.setBlockAndUpdate(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
            var books = (Container)level.getBlockEntity(shelf);
            for (int slot : new int[] {0, 3, 4}) books.setItem(slot, new ItemStack(Items.BOOK));
            reader.setPos(shelf.getX() + .5, shelf.getY(), shelf.getZ() - 1);
            click(reader, shelf, ItemStack.EMPTY, Direction.NORTH, face.apply(.5, .25));
            helper.assertTrue(!occupied.apply(4), "Took the book from slot 4");
        }).thenIdle(1).thenExecute(() -> {
            click(reader, shelf, new ItemStack(Items.BOOK), Direction.NORTH, face.apply(.5, .75));
            original.run();
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> {
            // Pooled insertion would refill slot 1 and pooled removal would empty slot 3.
            for (int slot = 0; slot < 6; slot++)
                helper.assertTrue(occupied.apply(slot) == (slot == 0 || slot == 3 || slot == 4), "Slot " + slot + " after rollback");
            helper.assertTrue(level.getBlockState(shelf).getValue(BlockStateProperties.CHISELED_BOOKSHELF_SLOT_4_OCCUPIED)
                    && !level.getBlockState(shelf).getValue(BlockStateProperties.CHISELED_BOOKSHELF_SLOT_1_OCCUPIED), "Block state shows the same slots");
        })
        .thenWaitUntil(command(helper, restore, "chronicle rs" + scope, "Restore complete"))
        .thenExecute(original)
        .thenWaitUntil(command(helper, lookup, "chronicle lookup" + scope, "Event ID: "))
        .thenExecute(() -> {
            var rows = rows(lookup);
            helper.assertTrue(rows.size() == 2 && rows.stream().anyMatch(row -> row.contains("took ×1 book") && row.contains("Slot: 4"))
                    && rows.stream().anyMatch(row -> row.contains("put ×1 book") && row.contains("Slot: 1")), "Rows name their slots: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_bookshelf_conflict")
    public static void bookshelfRollbackNeverTakesAnotherSlotsBook(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbShelver"); var shelver = player(helper, name); var taker = player(helper, unique("RbTaker"));
        var shelf = helper.absolutePos(new BlockPos(2, 1, 2));
        var slotOne = new Vec3(shelf.getX() + .5, shelf.getY() + .75, shelf.getZ());
        Reply rollback = new Reply(), again = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var player : List.of(shelver, taker)) { player.setGameMode(GameType.SURVIVAL); player.setPos(shelf.getX() + .5, shelf.getY(), shelf.getZ() - 1); }
            level.setBlockAndUpdate(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
            ((Container)level.getBlockEntity(shelf)).setItem(4, new ItemStack(Items.BOOK));
            click(shelver, shelf, new ItemStack(Items.BOOK), Direction.NORTH, slotOne);
        }).thenIdle(1).thenExecute(() -> {
            click(taker, shelf, ItemStack.EMPTY, Direction.NORTH, slotOne);
            var books = (Container)level.getBlockEntity(shelf);
            helper.assertTrue(books.getItem(1).isEmpty() && !books.getItem(4).isEmpty(), "Slot 1 taken back by someone else; slot 4 untouched");
        }).thenIdle(3)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(!((Container)level.getBlockEntity(shelf)).getItem(4).isEmpty(), "The identical book in slot 4 is not a substitute");
            helper.assertTrue(rollback.output.lines.stream().anyMatch(line -> line.contains("items no longer present: 1")), "Retryable skip " + rollback.output.lines);
        })
        .thenWaitUntil(command(helper, again, "chronicle rb --preview r:global t:1h u:" + name, "Would roll back 0 block changes and 1 item changes"))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_fluid")
    public static void rolledBackPoursLeaveNoFlowingFluidBehind(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbPour"); var pourer = player(helper, name);
        var center = helper.absolutePos(new BlockPos(3, 2, 3));
        Reply water = new Reply(), lava = new Reply();
        String rollback = "chronicle rb r:global t:1h a:place u:" + name;
        java.util.function.Consumer<net.minecraft.world.item.Item> pour = bucket -> {
            pourer.setPos(center.getX() + .5, center.getY() + 1, center.getZ() + .5);
            pourer.setXRot(90);
            pourer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bucket));
            pourer.gameMode.useItem(pourer, level, pourer.getMainHandItem(), InteractionHand.MAIN_HAND);
        };
        java.util.function.Function<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>, Integer> fluid = tag -> {
            int found = 0;
            for (var pos : BlockPos.betweenClosed(center.offset(-10, -1, -10), center.offset(10, 1, 10)))
                if (level.getFluidState(pos).is(tag)) found++;
            return found;
        };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            pourer.setGameMode(GameType.CREATIVE);
            // A walled stone basin: water spreads 7 blocks and must not run off the edge.
            for (var pos : BlockPos.betweenClosed(center.offset(-9, -1, -9), center.offset(9, 0, 9))) {
                boolean wall = Math.abs(pos.getX() - center.getX()) == 9 || Math.abs(pos.getZ() - center.getZ()) == 9;
                level.setBlock(pos, pos.getY() < center.getY() || wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            pour.accept(Items.WATER_BUCKET);
            helper.assertTrue(level.getFluidState(center).isSource(), "Water poured");
        }).thenWaitUntil(() -> helper.assertTrue(fluid.apply(net.minecraft.tags.FluidTags.WATER) > 20, "Waiting for the water to spread"))
        .thenWaitUntil(command(helper, water, rollback, "Rollback complete"))
        .thenExecute(() -> helper.assertTrue(fluid.apply(net.minecraft.tags.FluidTags.WATER) == 0, "No flowing water left behind"))
        .thenExecute(() -> pour.accept(Items.LAVA_BUCKET))
        .thenWaitUntil(() -> helper.assertTrue(fluid.apply(net.minecraft.tags.FluidTags.LAVA) > 4, "Waiting for the lava to spread"))
        .thenWaitUntil(command(helper, lava, rollback, "Rollback complete"))
        .thenExecute(() -> helper.assertTrue(fluid.apply(net.minecraft.tags.FluidTags.LAVA) == 0, "No flowing lava left behind"))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_waterfall")
    public static void rolledBackPourKeepsAnIndependentWaterfall(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbFall"); var pourer = player(helper, name);
        var center = helper.absolutePos(new BlockPos(3, 2, 3));
        var spring = center.offset(4, 2, 0); var fall = center.offset(3, 0, 0); var farSide = center.offset(-6, 0, 0);
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            pourer.setGameMode(GameType.CREATIVE);
            for (var pos : BlockPos.betweenClosed(center.offset(-9, -1, -9), center.offset(9, 2, 9))) {
                boolean wall = Math.abs(pos.getX() - center.getX()) == 9 || Math.abs(pos.getZ() - center.getZ()) == 9;
                boolean floor = pos.getY() < center.getY();
                boolean pillar = pos.getX() == spring.getX() && pos.getZ() == spring.getZ() && pos.getY() < spring.getY();
                level.setBlock(pos, floor || pillar || wall && pos.getY() == center.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            // An unaudited spring on a pillar: its waterfall is not part of anything rolled back.
            level.setBlockAndUpdate(spring, Blocks.WATER.defaultBlockState());
            pourer.setPos(center.getX() + .5, center.getY() + 1, center.getZ() + .5);
            pourer.setXRot(90);
            pourer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            pourer.gameMode.useItem(pourer, level, pourer.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getFluidState(center).isSource(), "Water poured");
        }).thenWaitUntil(() -> helper.assertTrue(level.getFluidState(fall).is(net.minecraft.tags.FluidTags.WATER)
                && level.getFluidState(farSide).is(net.minecraft.tags.FluidTags.WATER), "Waiting for the pour and the waterfall to merge"))
        .thenIdle(40)
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h a:place u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(!level.getFluidState(center).isSource(), "Pour rolled back");
            helper.assertTrue(level.getFluidState(spring).isSource(), "Spring untouched");
            for (var pos : List.of(spring.west(), spring.west().below(), fall))
                helper.assertTrue(level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER), "Waterfall kept at " + pos.toShortString());
            helper.assertTrue(level.getFluidState(farSide).isEmpty(), "Flow only the pour fed has receded");
        }).thenIdle(60)
        .thenExecute(() -> {
            helper.assertTrue(level.getFluidState(fall).is(net.minecraft.tags.FluidTags.WATER), "The waterfall still runs under normal physics");
            level.setBlockAndUpdate(spring, Blocks.AIR.defaultBlockState());
        }).thenSucceed();
    }

    private static String quarantine() {
        try {
            var file = ChronicleTestMod.runtime.quarantineFile();
            return java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : "";
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_unloaded")
    public static void frameInAnUnloadedChunkIsLoadedBeforeItsRowIsApplied(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbDistant");
        var framePos = helper.absolutePos(new BlockPos(1, 1, 1)).offset(4096, 0, 0);
        var chunk = new net.minecraft.world.level.ChunkPos(framePos);
        var engine = new RollbackEngine(() -> ChronicleTestMod.runtime);
        engine.register();
        var put = AuditEvent.item(EventId.generate(), Instant.now(), envelope(level, framePos, "ITEM_PUT", name, UUID.randomUUID(), "minecraft:emerald"),
                new ItemTransactionPayload(1, "minecraft:item_frame", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
        var state = new Object() { CompletableFuture<RollbackPlan> plan; RollbackEngine.Report report; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setChunkForced(chunk.x, chunk.z, true);
            level.setBlockAndUpdate(framePos.south(), Blocks.STONE.defaultBlockState());
            var frame = new ItemFrame(level, framePos, Direction.NORTH);
            frame.setItem(new ItemStack(Items.EMERALD));
            helper.assertTrue(level.addFreshEntity(frame), "Distant frame spawned");
            helper.assertTrue(ChronicleTestMod.runtime.submit(put).status() == io.github.andriyko69.chronicle.ingest.Submission.Status.ACCEPTED, "Admitted");
            level.setChunkForced(chunk.x, chunk.z, false);
        }).thenWaitUntil(() -> helper.assertTrue(!level.areEntitiesLoaded(chunk.toLong()), "Waiting for the frame's chunk to unload"))
        .thenExecute(() -> state.plan = ChronicleTestMod.runtime.planRollback(
                new LookupParser(ChronicleClock.system()).parseRollback("r:global t:1h u:" + name, LookupParser.Context.console()).spec(), false).toCompletableFuture())
        .thenWaitUntil(() -> helper.assertTrue(state.plan.isDone(), "Waiting for the plan"))
        .thenExecute(() -> {
            var server = level.getServer();
            helper.assertTrue(engine.reserve(server), "Test engine slot");
            engine.start(server, ChronicleTestMod.runtime, state.plan.join(), report -> state.report = report);
        }).thenWaitUntil(() -> helper.assertTrue(state.report != null, "Waiting for the rollback"))
        .thenExecute(() -> {
            helper.assertTrue(state.report.items() == 1 && state.report.skippedTotal() == 0, "Frame found once its entities loaded: " + state.report);
            var frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(framePos).inflate(1));
            helper.assertTrue(frames.size() == 1 && frames.getFirst().getItem().isEmpty(), "Emerald removed from the distant frame");
            frames.forEach(Entity::discard);
        }).thenSucceed();
    }
    private static EventEnvelope envelope(ServerLevel level, BlockPos pos, String type, String name, UUID actor, String target) {
        return new EventEnvelope(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), type, "PLAYER", name, actor, null, target);
    }
    private static ItemTransactionPayload spill(int count) {
        return new ItemTransactionPayload(count, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null);
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_rollback_tnt")
    public static void userRollbackUndoesRootCausedExplosion(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbTnt"); var igniter = player(helper, name);
        var tnt = helper.absolutePos(new BlockPos(3, 2, 3)); var stone = tnt.east(); var chest = tnt.north();
        Reply rollback = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 10));
            level.setBlockAndUpdate(tnt, Blocks.TNT.defaultBlockState());
            igniter.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            igniter.gameMode.useItemOn(igniter, level, igniter.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(tnt).relative(Direction.UP, .5), Direction.UP, tnt, false));
            var primed = level.getEntitiesOfClass(PrimedTnt.class, new AABB(tnt).inflate(1));
            helper.assertTrue(primed.size() == 1 && primed.getFirst().getOwner() == igniter, "Player-owned primed TNT");
            primed.getFirst().setFuse(1);
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(stone).isAir() && level.getBlockState(chest).isAir(), "Waiting for explosion"))
        .thenIdle(3).thenExecute(() -> discardDrops(helper))
        .thenWaitUntil(command(helper, rollback, "chronicle rb r:global t:1h u:" + name, "Rollback complete"))
        .thenExecute(() -> {
            helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "Root-caused stone break rolled back");
            helper.assertTrue(level.getBlockState(chest).is(Blocks.CHEST), "Root-caused chest break rolled back");
            int diamonds = count((Container)level.getBlockEntity(chest), new ItemStack(Items.DIAMOND));
            helper.assertTrue(diamonds == 10, "Chest contents restored exactly once, got " + diamonds);
            noDrops(helper);
        }).thenSucceed();
    }
}
