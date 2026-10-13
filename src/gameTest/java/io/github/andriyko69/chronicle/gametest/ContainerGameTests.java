/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import io.github.andriyko69.chronicle.container.*;
import io.github.andriyko69.chronicle.event.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class ContainerGameTests {
    private static ServerPlayer player(GameTestHelper helper, String name) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        // FakePlayer suppresses openMenu; a packet sink with explicit channel support exercises the real menu lifecycle.
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
    private static void open(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        var level = helper.getLevel();
        helper.assertTrue(player.openMenu(level.getBlockState(pos).getMenuProvider(level, pos)).isPresent(), "Actual world menu opens");
    }
    private static void persisted(GameTestHelper helper, UUID actor, int count, Consumer<List<AuditEvent>> check) {
        var query = new Object() { CompletableFuture<List<AuditEvent>> pending; };
        helper.startSequence().thenWaitUntil(() -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            var events = query.pending.join().stream().filter(e -> !e.legacy() && actor.equals(e.envelope().actorUuid())).toList();
            query.pending = null;
            helper.assertTrue(events.size() == count, "Expected " + count + " events, got " + events.size());
            check.accept(events);
        }).thenSucceed();
    }
    private static ItemStack blade(ServerLevel level) {
        var item = new ItemStack(Items.DIAMOND_SWORD);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Evidence Blade"));
        item.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS), 3);
        item.setDamageValue(7);
        return item;
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
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void actualChestBarrelDoubleChestAndExactLookupOutput(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "ContainerReal");
        var positions = List.of(helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(3, 1, 1)), helper.absolutePos(new BlockPos(1, 1, 3)));
        var pending = new Object() { Output output; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(positions.get(0), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(positions.get(1), Blocks.BARREL.defaultBlockState());
            var first = positions.get(2); var second = first.east();
            level.setBlock(first, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
            level.setBlock(second, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
            for (int i = 0; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                var container = (Container)level.getBlockEntity(pos);
                container.setItem(0, new ItemStack(Items.DIAMOND, 16));
                open(helper, player, i == 2 ? second : pos);
                var resolved = WorldContainer.resolve(player, player.containerMenu);
                helper.assertTrue(resolved.size() == 1 && resolved.getFirst().position().equals(pos), "Canonical world position from either half");
                helper.assertTrue(resolved.getFirst().slots().size() == (i == 2 ? 54 : 27), "All world slots and no player slots");
                container.setItem(0, ItemStack.EMPTY);
                container.setItem(1, blade(level));
                player.getInventory().setItem(5, new ItemStack(Items.EMERALD, 64));
                player.closeContainer();
            }
        }).thenWaitUntil(() -> {
            if (pending.output == null) {
                pending.output = new Output(level.getServer());
                var source = level.getServer().createCommandSourceStack().withPermission(2).withSource(pending.output);
                try { level.getServer().getCommands().getDispatcher().execute("chronicle lookup r:global t:1h a:item u:" + player.getUUID(), source); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
            }
            helper.assertTrue(!pending.output.lines.isEmpty(), "Waiting for production command reply");
            var rows = pending.output.lines.stream().filter(l -> l.contains("Event ID: ")).toList();
            if (rows.size() != 6) { pending.output = null; helper.fail("Waiting for six persisted item rows"); }
            for (int i = 0; i < positions.size(); i++) {
                var pos = positions.get(i);
                String coordinates = "minecraft:overworld (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
                String container = i == 1 ? "minecraft:barrel" : "minecraft:chest";
                for (boolean put : List.of(false, true)) {
                    var matching = rows.stream().filter(row -> row.contains(coordinates) && row.contains(put ? " put " : " took ")).toList();
                    helper.assertTrue(matching.size() == 1, "One transaction per container and direction");
                    String row = matching.getFirst();
                    for (String field : List.of("Actor UUID: " + player.getUUID(), "Container: " + container,
                            put ? "Item: minecraft:diamond_sword" : "Item: minecraft:diamond", put ? "Count: 1" : "Count: 16",
                            put ? "×1 diamond_sword" : "×16 diamond")) helper.assertTrue(row.contains(field), "Transaction field " + field);
                    if (put) for (String field : List.of("Custom name: Evidence Blade", "minecraft:sharpness=3", "Damage: 7", "Components: PRESENT"))
                        helper.assertTrue(row.contains(field), "Rich item field " + field);
                }
            }
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void netZeroAndComponentIdentityAcrossSlotRearrangements(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "ContainerDiff"); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState()); var chest = (Container)level.getBlockEntity(pos);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 32)); open(helper, player, pos);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 8)); chest.setItem(4, new ItemStack(Items.DIAMOND, 24));
            player.closeContainer();
            chest.clearContent(); chest.setItem(0, new ItemStack(Items.DIAMOND_SWORD)); open(helper, player, pos);
            chest.setItem(0, blade(level)); player.closeContainer();
            persisted(helper, player.getUUID(), 2, events -> {
                helper.assertTrue(events.stream().allMatch(e -> e.envelope().targetId().equals("minecraft:diamond_sword")), "Rearranged diamonds emitted no events");
                var codec = new ItemTransactionPayloadCodec();
                var take = events.stream().filter(e -> e.envelope().eventType().equals("ITEM_TAKE")).findFirst().orElseThrow();
                var put = events.stream().filter(e -> e.envelope().eventType().equals("ITEM_PUT")).findFirst().orElseThrow();
                helper.assertTrue(codec.decode(take.payloadVersion(), take.payload()).componentSnapshotStatus() == ItemTransactionPayload.ComponentSnapshotStatus.NONE, "Plain item stores ID/count only");
                var payload = codec.decode(put.payloadVersion(), put.payload());
                helper.assertTrue(payload.customName().equals("Evidence Blade") && payload.damage() == 7 && payload.enchantments().get("minecraft:sharpness") == 3, "Summary captured");
                try {
                    var tag = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload.componentBytes())), net.minecraft.nbt.NbtAccounter.create(16 * 1024));
                    helper.assertTrue(tag.contains("minecraft:custom_name") && tag.contains("minecraft:enchantments"), "Valid detached component NBT");
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void lifecycleFallbacksRemoveSessionsExactlyOnce(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "ContainerLifecycle"); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var manager = new ContainerSessionManager(() -> ChronicleTestMod.runtime, ChronicleClock.system());
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState()); var container = (Container)level.getBlockEntity(pos);
            for (int i = 0; i < 5; i++) {
                container.clearContent();
                var menu = new ChestMenu(MenuType.GENERIC_9x3, 7, player.getInventory(), container, 3);
                manager.opened(new PlayerContainerEvent.Open(player, menu));
                helper.assertTrue(manager.activeSessions(level.getServer()) == 1, "One active session");
                container.setItem(0, new ItemStack(Items.IRON_INGOT, i + 1));
                switch (i) {
                    case 0 -> manager.loggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
                    case 1 -> manager.died(new LivingDeathEvent(player, level.damageSources().generic()));
                    case 2 -> manager.changedDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, Level.NETHER));
                    case 3 -> {
                        var replacement = new ChestMenu(MenuType.GENERIC_9x3, 7, player.getInventory(), container, 3);
                        manager.opened(new PlayerContainerEvent.Open(player, replacement));
                        manager.closed(new PlayerContainerEvent.Close(player, menu));
                        helper.assertTrue(manager.activeSessions(level.getServer()) == 1, "Stale close cannot consume reused menu ID");
                        manager.closed(new PlayerContainerEvent.Close(player, replacement));
                    }
                    case 4 -> manager.stop(level.getServer());
                    default -> throw new AssertionError();
                }
                manager.closed(new PlayerContainerEvent.Close(player, menu));
                helper.assertTrue(manager.activeSessions(level.getServer()) == 0, "No live menu retained after fallback");
            }
            persisted(helper, player.getUUID(), 5, events -> {
                var counts = events.stream().map(e -> new ItemTransactionPayloadCodec().decode(e.payloadVersion(), e.payload()).count()).sorted().toList();
                helper.assertTrue(counts.equals(List.of(1, 2, 3, 4, 5)), "Exactly one flush per lifecycle");
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void worldScopeCrafterExcludesPreviewAndVirtualMenus(GameTestHelper helper) {
        var level = helper.getLevel(); var player = player(helper, "ContainerScope"); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (Block block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX, Blocks.FURNACE, Blocks.BLAST_FURNACE,
                    Blocks.SMOKER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER, Blocks.BREWING_STAND, Blocks.CRAFTER)) {
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                open(helper, player, pos);
                var resolved = WorldContainer.resolve(player, player.containerMenu);
                helper.assertTrue(resolved.size() == 1, "World storage resolves: " + block);
                var container = (Container)level.getBlockEntity(pos);
                helper.assertTrue(resolved.getFirst().slots().size() == container.getContainerSize(), "Only persistent slots: " + block);
                player.closeContainer();
            }
            helper.assertTrue(WorldContainer.resolve(player, player.inventoryMenu).isEmpty(), "Player inventory ignored");
            helper.assertTrue(WorldContainer.resolve(player, ChestMenu.threeRows(10, player.getInventory(), new SimpleContainer(27))).isEmpty(), "Virtual chest ignored");
            helper.assertTrue(WorldContainer.resolve(player, ChestMenu.threeRows(11, player.getInventory(), player.getEnderChestInventory())).isEmpty(), "Ender inventory ignored");
            helper.assertTrue(WorldContainer.resolve(player, new CraftingMenu(12, player.getInventory())).isEmpty(), "Crafting table ignored");
            level.setBlockAndUpdate(pos, Blocks.CHISELED_BOOKSHELF.defaultBlockState());
            var bookshelf = (Container)level.getBlockEntity(pos);
            var worldMenu = new ChestMenu(MenuType.GENERIC_9x1, 13, player.getInventory(), new CompoundContainer(bookshelf, new SimpleContainer(3)), 1);
            helper.assertTrue(WorldContainer.resolve(player, worldMenu).isEmpty(), "Mixed virtual/world compound is unsupported");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void canonicalComponentsOversizeAndFakePlayer(GameTestHelper helper) {
        var level = helper.getLevel(); var fake = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ContainerFake"));
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState()); var container = (Container)level.getBlockEntity(pos);
            var first = new ItemStack(Items.PAPER); var second = new ItemStack(Items.PAPER);
            var data1 = new CompoundTag(); data1.putInt("Aa", 1); data1.putInt("BB", 2);
            var data2 = new CompoundTag(); data2.putInt("BB", 2); data2.putInt("Aa", 1);
            first.set(DataComponents.CUSTOM_DATA, CustomData.of(data1)); second.set(DataComponents.CUSTOM_DATA, CustomData.of(data2));
            var a = ContainerItemCapture.capture(first, "minecraft:barrel", level.registryAccess());
            var b = ContainerItemCapture.capture(second, "minecraft:barrel", level.registryAccess());
            helper.assertTrue(a.key().equals(b.key()) && Arrays.equals(a.payload().componentBytes(), b.payload().componentBytes()), "Canonical patch independent of NBT insertion order");
            var large = new CompoundTag(); large.putByteArray("large", new byte[20_000]);
            first.set(DataComponents.CUSTOM_DATA, CustomData.of(large)); container.setItem(0, first);
            var menu = ChestMenu.threeRows(17, fake.getInventory(), container);
            NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fake, menu));
            container.clearContent(); NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Close(fake, menu));
            var longString = new CompoundTag(); longString.putString("large", "x".repeat(70_000));
            second.set(DataComponents.CUSTOM_DATA, CustomData.of(longString)); container.setItem(0, second);
            var oversizedBefore = ContainerItemCapture.capture(second, "minecraft:barrel", level.registryAccess());
            var oversizedAfter = ContainerItemCapture.capture(second.copy(), "minecraft:barrel", level.registryAccess());
            helper.assertTrue(oversizedBefore.key().equals(oversizedAfter.key()), "NBT UTF overflow retains stable identity");
            helper.assertTrue(ContainerNetDiff.between(ContainerNetDiff.group(List.of(oversizedBefore)),
                    ContainerNetDiff.group(List.of(oversizedAfter))).isEmpty(), "Unchanged overlong string has zero net events");
            NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fake, menu));
            NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Close(fake, menu));
            NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(fake, menu));
            container.clearContent(); NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Close(fake, menu));
            persisted(helper, fake.getUUID(), 2, events -> {
                for (var event : events) {
                    helper.assertTrue(event.envelope().actorType().equals("FAKE_PLAYER") && event.envelope().eventType().equals("ITEM_TAKE"), "Fake player withdrawal; unchanged overflow emitted no pair");
                    var payload = new ItemTransactionPayloadCodec().decode(event.payloadVersion(), event.payload());
                    helper.assertTrue(payload.componentSnapshotStatus() == ItemTransactionPayload.ComponentSnapshotStatus.OVERSIZED && payload.componentBytes() == null, "Oversized patch explicitly omitted");
                }
            });
        });
    }
}
