/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.listener.PlayerActor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class BlockEventGameTests {
    private static BlockDeltaPayload delta(AuditEvent event) { return new BlockDeltaPayloadCodec().decode(event.payloadVersion(), event.payload()); }
    private static FakePlayer fake(GameTestHelper helper) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "ChronicleFake"));
    }
    private static void ready(GameTestHelper helper) {
        helper.assertTrue(ChronicleTestMod.ready.isDone(), "Waiting for Chronicle startup"); ChronicleTestMod.ready.join();
    }
    private static void verifyWhenPersisted(GameTestHelper helper, UUID actor, int count, Consumer<List<AuditEvent>> check) {
        var query = new Object() { CompletableFuture<List<AuditEvent>> result; };
        helper.startSequence().thenWaitUntil(() -> {
            if (query.result == null) query.result = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.result.isDone(), "Waiting for detached query");
            var events = query.result.join().stream().filter(e -> !e.legacy() && actor.equals(e.envelope().actorUuid())).toList();
            query.result = null;
            helper.assertTrue(events.size() == count, "Expected " + count + " actor events, got " + events.size());
            check.accept(events);
        }).thenSucceed();
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void playerAndFakePlayerAttribution(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var profile = new GameProfile(UUID.randomUUID(), "ChronicleReal");
            var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, ClientInformation.createDefault());
            var actor = PlayerActor.capture(player);
            helper.assertTrue(actor.type().equals("PLAYER") && actor.uuid().equals(profile.getId()) && actor.name().equals(profile.getName()), "Real player attribution");
            var fake = fake(helper); var fakeActor = PlayerActor.capture(fake);
            helper.assertTrue(fakeActor.type().equals("FAKE_PLAYER") && fakeActor.uuid().equals(fake.getUUID()), "FakePlayer attribution");
            var pos = helper.absolutePos(new BlockPos(1, 1, 1)); helper.getLevel().setBlockAndUpdate(pos, Blocks.DIAMOND_ORE.defaultBlockState());
            NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(helper.getLevel(), pos, helper.getLevel().getBlockState(pos), player));
            verifyWhenPersisted(helper, profile.getId(), 1, events -> {
                var event = events.getFirst();
                helper.assertTrue(event.envelope().actorType().equals("PLAYER") && event.envelope().actorId().equals(profile.getName()), "Persisted real actor");
                helper.assertTrue(event.envelope().x() == pos.getX() && event.envelope().y() == pos.getY() && event.envelope().z() == pos.getZ(), "Persisted coordinates");
                helper.assertTrue(delta(event).before().blockId().equals("minecraft:diamond_ore") && delta(event).after().equals(BlockStateSnapshot.AIR), "Break states");
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void actualBreakAndPlacementSnapshotBeforeDestruction(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var level = helper.getLevel(); var fake = fake(helper); fake.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            var chestPos = helper.absolutePos(new BlockPos(1, 1, 1)); level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            var chest = (ChestBlockEntity)level.getBlockEntity(chestPos); chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
            helper.assertTrue(fake.gameMode.destroyBlock(chestPos), "Engine break call");
            helper.assertTrue(level.getBlockState(chestPos).isAir(), "Chest actually removed");
            var base = helper.absolutePos(new BlockPos(3, 0, 3)); level.setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            fake.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
            var hit = new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
            fake.getMainHandItem().useOn(new UseOnContext(fake, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(level.getBlockState(base.above()).is(Blocks.GOLD_BLOCK), "Engine placement happened");
            verifyWhenPersisted(helper, fake.getUUID(), 3, events -> {
                var spilled = events.stream().filter(e -> e.envelope().eventType().equals("ITEM_TAKE")).findFirst().orElseThrow();
                helper.assertTrue(spilled.envelope().targetId().equals("minecraft:diamond") && spilled.envelope().x() == chestPos.getX()
                        && new io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec().decode(spilled.payloadVersion(), spilled.payload()).count() == 3,
                        "Broken chest contents recorded as a spill");
                var broken = events.stream().filter(e -> e.envelope().eventType().equals("BLOCK_BREAK")).findFirst().orElseThrow();
                var placed = events.stream().filter(e -> e.envelope().eventType().equals("BLOCK_PLACE")).findFirst().orElseThrow();
                helper.assertTrue(broken.envelope().actorType().equals("FAKE_PLAYER"), "Persisted FakePlayer classification");
                helper.assertTrue(delta(broken).snapshotStatus() == BlockDeltaPayload.SnapshotStatus.PRESENT && delta(broken).snapshotNbt().length > 20, "Pre-destruction NBT captured");
                try {
                    var tag = NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(delta(broken).snapshotNbt())), NbtAccounter.create(64 * 1024));
                    helper.assertTrue(tag.getList("Items", 10).getCompound(0).getString("id").equals("minecraft:diamond"), "Detached NBT retains chest inventory after destruction");
                } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                helper.assertTrue(delta(broken).before().properties().containsKey("facing"), "Block properties captured");
                helper.assertTrue(delta(placed).before().equals(BlockStateSnapshot.AIR) && delta(placed).after().blockId().equals("minecraft:gold_block"), "Actual placement states");
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void cancelledIgnoredAndMultiPlaceCoalescedWithOversizedSnapshot(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var level = helper.getLevel(); var fake = fake(helper);
            var first = helper.absolutePos(new BlockPos(1, 1, 1)); var second = helper.absolutePos(new BlockPos(2, 1, 1));
            level.setBlockAndUpdate(first, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity)level.getBlockEntity(first)).getPersistentData().putByteArray("large", new byte[70_000]);
            var original = BlockSnapshot.create(level.dimension(), level, first);
            var cancelled = new BlockEvent.BreakEvent(level, first, level.getBlockState(first), fake); cancelled.setCanceled(true); NeoForge.EVENT_BUS.post(cancelled);
            level.setBlockAndUpdate(first, Blocks.STONE.defaultBlockState()); var intermediate = BlockSnapshot.create(level.dimension(), level, first);
            var other = BlockSnapshot.create(level.dimension(), level, second);
            var cancelledPlace = new BlockEvent.EntityPlaceEvent(intermediate, Blocks.AIR.defaultBlockState(), fake); cancelledPlace.setCanceled(true); NeoForge.EVENT_BUS.post(cancelledPlace);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(intermediate, Blocks.AIR.defaultBlockState(), null));
            level.setBlockAndUpdate(first, Blocks.GOLD_BLOCK.defaultBlockState()); level.setBlockAndUpdate(second, Blocks.DIRT.defaultBlockState());
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityMultiPlaceEvent(List.of(original, intermediate, other), Blocks.AIR.defaultBlockState(), fake));
            verifyWhenPersisted(helper, fake.getUUID(), 2, events -> {
                var replaced = events.stream().filter(e -> e.envelope().x() == first.getX()).findFirst().orElseThrow();
                helper.assertTrue(delta(replaced).before().blockId().equals("minecraft:chest") && delta(replaced).after().blockId().equals("minecraft:gold_block"), "First old to final observed state");
                helper.assertTrue(delta(replaced).snapshotStatus() == BlockDeltaPayload.SnapshotStatus.OVERSIZED && delta(replaced).snapshotNbt() == null, "Oversized NBT explicitly omitted");
                helper.assertTrue(events.stream().allMatch(e -> e.envelope().eventType().equals("BLOCK_PLACE")), "Cancelled break ignored");
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oversizedNbtStringNeverSilentlyReplaced(GameTestHelper helper) {
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var level = helper.getLevel(); var fake = fake(helper); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
            level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            var chest = (ChestBlockEntity)level.getBlockEntity(pos); chest.getPersistentData().putString("large", "x".repeat(70_000));
            NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), fake));
            chest.getPersistentData().remove("large");
            verifyWhenPersisted(helper, fake.getUUID(), 1, events -> helper.assertTrue(
                    delta(events.getFirst()).snapshotStatus() == BlockDeltaPayload.SnapshotStatus.OVERSIZED
                            && delta(events.getFirst()).snapshotNbt() == null, "Oversized NBT string must never be stored as empty text"));
        });
    }
}
