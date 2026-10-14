/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class LookupCommandGameTests {
    private static final class Output implements CommandSource {
        final MinecraftServer server;
        final List<String> lines = new ArrayList<>();
        final List<Component> components = new ArrayList<>();
        Output(MinecraftServer server) { this.server = server; }
        public void sendSystemMessage(Component message) {
            if (!server.isSameThread()) throw new IllegalStateException("Command output off server thread");
            lines.add(ChatEvidence.text(message));
            components.add(message);
        }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
        long rows() { return lines.stream().filter(line -> line.contains("Event ID: ")).count(); }
    }
    private static void execute(CommandSourceStack source, String command) {
        try { source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException failure) { throw new IllegalStateException(failure); }
    }
    // Console lookups share one saved session; isolate pagination from other command tests.
    @GameTest(template = "empty", timeoutTicks = 600, batch = "lookup_pagination")
    public static void productionDispatcherLookupPagesAndStatus(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var page = new Output(server); var status = new Output(server);
        var query = new Object() { Output pending; Output successful; };
        var profile = new GameProfile(UUID.randomUUID(), "LookupReal");
        var fakeProfile = new GameProfile(UUID.randomUUID(), "LookupFake");
        var real = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
        var fake = new FakePlayer(helper.getLevel(), fakeProfile);
        var orePos = helper.absolutePos(new BlockPos(1, 1, 1));
        var chestPos = helper.absolutePos(new BlockPos(2, 1, 2));
        var base = helper.absolutePos(new BlockPos(3, 0, 3));
        var realPlacePos = helper.absolutePos(new BlockPos(4, 1, 4));
        var source = server.createCommandSourceStack().withPermission(2);
        helper.startSequence().thenExecute(() -> {
            helper.getLevel().setBlockAndUpdate(orePos, Blocks.DIAMOND_ORE.defaultBlockState());
            for (int i = 0; i < 10; i++) NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(helper.getLevel(), orePos, Blocks.DIAMOND_ORE.defaultBlockState(), real));
            helper.getLevel().setBlockAndUpdate(realPlacePos, Blocks.AIR.defaultBlockState());
            var replaced = BlockSnapshot.create(helper.getLevel().dimension(), helper.getLevel(), realPlacePos);
            helper.getLevel().setBlockAndUpdate(realPlacePos, Blocks.EMERALD_BLOCK.defaultBlockState());
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(replaced, Blocks.STONE.defaultBlockState(), real));
            helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity)helper.getLevel().getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND));
            fake.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            helper.assertTrue(fake.gameMode.destroyBlock(chestPos), "Actual FakePlayer break");
            helper.getLevel().setBlockAndUpdate(base, Blocks.STONE.defaultBlockState());
            fake.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
            fake.getMainHandItem().useOn(new UseOnContext(fake, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false)));
            helper.assertTrue(helper.getLevel().getBlockState(base.above()).is(Blocks.GOLD_BLOCK), "Actual FakePlayer place");
            var denied = new Output(server);
            try {
                server.getCommands().getDispatcher().execute("ch status", source.withSource(denied).withPermission(1));
                helper.fail("Permission level 1 accessed Chronicle");
            } catch (CommandSyntaxException expected) { }
        }).thenWaitUntil(() -> {
            // Freeze one complete response; delayed attempts must never append to the chosen page buffer.
            if (query.pending == null) {
                query.pending = new Output(server);
                execute(source.withSource(query.pending), "chronicle lookup r:global t:1h u:" + profile.getId() + "," + fakeProfile.getId());
            }
            helper.assertTrue(!query.pending.lines.isEmpty(), "Waiting for complete lookup response");
            String text = String.join("\n", query.pending.lines);
            boolean drained = query.pending.rows() == 8 && text.contains("LookupFake") && text.contains("minecraft:gold_block") && text.contains("[NBT]");
            if (drained) query.successful = query.pending;
            query.pending = null;
            helper.assertTrue(drained, "Waiting for lookup engine events");
        }).thenExecute(() -> {
            execute(source.withSource(page), "ch p 2");
            execute(source.withSource(status), "ch status");
        }).thenWaitUntil(() -> {
            helper.assertTrue(page.rows() == 5, "Second page has five remaining rows");
            var all = new ArrayList<>(query.successful.lines); all.addAll(page.lines);
            String text = String.join("\n", all);
            String oreCoords = "(" + orePos.getX() + ", " + orePos.getY() + ", " + orePos.getZ() + ")";
            helper.assertTrue(text.contains("Actor UUID: " + profile.getId()) && text.contains("Actor UUID: " + fakeProfile.getId()) && text.contains("Actor type: FAKE_PLAYER"), "Exact actor identities");
            helper.assertTrue(text.contains(oreCoords) && text.contains("Old state: minecraft:diamond_ore\nNew state: minecraft:air"), "Exact coordinates and break states");
            helper.assertTrue(text.contains("Old state: minecraft:air\nNew state: minecraft:gold_block") && text.contains("Old state: minecraft:chest[facing=north,type=single,waterlogged=false]\nNew state: minecraft:air"), "Place and chest break states");
            String realPlaceCoords = "(" + realPlacePos.getX() + ", " + realPlacePos.getY() + ", " + realPlacePos.getZ() + ")";
            helper.assertTrue(all.stream().anyMatch(line -> line.contains(realPlaceCoords) && line.contains(" placed ")
                    && line.contains("Actor UUID: " + profile.getId()) && line.contains("Old state: minecraft:air\nNew state: minecraft:emerald_block")), "Exact PLAYER placement actor, coordinates and states");
            helper.assertTrue(java.util.stream.Stream.concat(query.successful.components.stream(), page.components.stream()).map(ChatEvidence::eventId).filter(Objects::nonNull).distinct().count() == 13, "No duplicates between pages after drain");
            String health = String.join("\n", status.lines);
            for (String metric : List.of("HEALTHY", "schema=4", "queue=", "journal backlog=", "allocated bytes=", "persisted events=", "DLQ=", "retries=", "persistence failures=", "Last flush=", "last commit=", "Database totals:"))
                helper.assertTrue(health.contains(metric), "Status metric " + metric);
        }).thenExecute(() -> {
            var invalid = new Output(server);
            execute(source.withSource(invalid), "ch l r:-1");
            helper.assertTrue(String.join("\n", invalid.lines).contains("Radius must be"), "Helpful parser errors");
            page.lines.clear(); page.components.clear(); execute(source.withSource(page), "chronicle page 1");
        }).thenWaitUntil(() -> helper.assertTrue(page.rows() == 8, "Invalid lookup preserves saved session")).thenSucceed();
    }
}
