/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import io.github.andriyko69.chronicle.event.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class CausalityGameTests {
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
    /** The production lookup reads the gametest world's persistent database, so names must not repeat across runs. */
    private static String unique(String prefix) { return prefix + UUID.randomUUID().toString().substring(0, 8); }
    private static void ready(GameTestHelper helper) {
        helper.assertTrue(ChronicleTestMod.ready.isDone(), "Waiting for Chronicle startup"); ChronicleTestMod.ready.join();
    }
    private static void click(ServerPlayer player, BlockPos pos, ItemStack held, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, player.serverLevel(), held, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).relative(face, .5), face, pos, false));
    }
    /** Forces every random fire decision to ignite, burn and spread, so traversal is deterministic. */
    private static final class Ignite extends LegacyRandomSource {
        Ignite() { super(0); }
        @Override public int nextInt(int bound) { return 0; }
    }
    private static BlockPos at(AuditEvent event) { return new BlockPos(event.envelope().x(), event.envelope().y(), event.envelope().z()); }
    private static Runnable persisted(GameTestHelper helper, Predicate<AuditEvent> filter, Consumer<List<AuditEvent>> check) {
        var query = new Object() { CompletableFuture<List<AuditEvent>> pending; };
        return () -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            var events = query.pending.join().stream().filter(e -> !e.legacy() && filter.test(e)).toList();
            query.pending = null;
            check.accept(events);
        };
    }
    private static AuditEvent only(GameTestHelper helper, List<AuditEvent> events, String type, BlockPos pos) {
        var matching = events.stream().filter(e -> e.envelope().eventType().equals(type) && at(e).equals(pos)).toList();
        helper.assertTrue(matching.size() == 1, "Expected one " + type + " at " + pos.toShortString() + ", got " + matching.size());
        return matching.getFirst();
    }
    private static void causedBy(GameTestHelper helper, AuditEvent event, String actor, ServerPlayer root) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorType().equals("ENVIRONMENT") && envelope.actorId().equals(actor) && envelope.actorUuid() == null,
                "Expected environmental actor " + actor + ", got " + envelope);
        helper.assertTrue(root.getUUID().equals(envelope.rootActorUuid()) && root.getGameProfile().getName().equals(envelope.rootActorId()),
                "Expected root " + root.getGameProfile().getName() + ", got " + envelope);
    }
    private static void directly(GameTestHelper helper, AuditEvent event, ServerPlayer player) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorType().equals("PLAYER") && player.getUUID().equals(envelope.actorUuid()) && envelope.rootActorUuid() == null,
                "Expected direct player actor, got " + envelope);
    }
    private static ItemTransactionPayload item(AuditEvent event) {
        return new ItemTransactionPayloadCodec().decode(event.payloadVersion(), event.payload());
    }
    private static final class Output implements CommandSource {
        final MinecraftServer server;
        final List<String> lines = new ArrayList<>();
        Output(MinecraftServer server) { this.server = server; }
        public void sendSystemMessage(Component message) {
            if (!server.isSameThread()) throw new IllegalStateException("Output off server thread");
            lines.add(message.getString());
        }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    /** Production lookup rows, excluding the page header. */
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
        var result = output.lines.stream().filter(line -> line.contains(" ago ")).toList();
        if (result.size() != rows) { state[0] = null; helper.fail("Waiting for " + rows + " lookup rows, got " + output.lines); }
        return result;
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_tnt")
    public static void playerIgnitedTntAttributesBlocksAndSpillToRootPlayer(GameTestHelper helper) {
        var level = helper.getLevel(); var igniter = player(helper, unique("Tnt"));
        var tnt = helper.absolutePos(new BlockPos(3, 2, 3)); var stone = tnt.east(); var chest = tnt.north();
        var lookup = new Object[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            ((Container)level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 10));
            level.setBlockAndUpdate(tnt, Blocks.TNT.defaultBlockState());
            click(igniter, tnt, new ItemStack(Items.FLINT_AND_STEEL), Direction.UP);
            var primed = level.getEntitiesOfClass(PrimedTnt.class, new AABB(tnt).inflate(1));
            helper.assertTrue(primed.size() == 1 && primed.getFirst().getOwner() == igniter, "Flint and steel primed TNT owned by the player");
            primed.getFirst().setFuse(1);
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(stone).isAir() && level.getBlockState(chest).isAir(), "Waiting for explosion"))
        .thenWaitUntil(persisted(helper, e -> at(e).equals(stone) || at(e).equals(chest), events -> {
            causedBy(helper, only(helper, events, "BLOCK_BREAK", stone), "#tnt", igniter);
            var chestBreak = only(helper, events, "BLOCK_BREAK", chest);
            causedBy(helper, chestBreak, "#tnt", igniter);
            var block = new BlockDeltaPayloadCodec().decode(chestBreak.payloadVersion(), chestBreak.payload());
            helper.assertTrue(block.before().blockId().equals("minecraft:chest") && block.snapshotStatus() == BlockDeltaPayload.SnapshotStatus.PRESENT,
                    "Chest NBT captured before removal");
            var spill = only(helper, events, "ITEM_TAKE", chest);
            causedBy(helper, spill, "#tnt", igniter);
            helper.assertTrue(spill.envelope().targetId().equals("minecraft:diamond") && item(spill).count() == 10, "Spill of 10 diamonds");
        })).thenWaitUntil(() -> {
            String name = igniter.getGameProfile().getName();
            var rows = lookup(helper, lookup, "r:global t:1h u:" + name + " b:chest,diamond", 2);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("- #tnt (← " + name + ") broke chest [NBT]")), "Root shown on block row: " + rows);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("- #tnt (← " + name + ") took ×10 diamond from chest")), "Root shown on spill row: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_fire")
    public static void fireSpreadTraversalAttributesBurnoutToIgniter(GameTestHelper helper) {
        var level = helper.getLevel(); var starter = player(helper, unique("Fire"));
        var a = helper.absolutePos(new BlockPos(1, 2, 3)); var b = a.east(); var c = b.east();
        var rule = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean fireTick = rule.get();
        var lookup = new Object[1];
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            rule.set(true, level.getServer());
            for (var pos : List.of(a, b, c)) level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(b, Blocks.OAK_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(c, Blocks.OAK_PLANKS.defaultBlockState());
            click(starter, a.below(), new ItemStack(Items.FLINT_AND_STEEL), Direction.UP);
            helper.assertTrue(level.getBlockState(a).is(Blocks.FIRE), "Player lit fire at A");
            // A burns B and replaces it with fire (hop 1); that fire then burns C (hop 2).
            level.getBlockState(a).tick(level, a, new Ignite());
            helper.assertTrue(level.getBlockState(b).is(Blocks.FIRE), "Fire spread from A into burned B");
            level.getBlockState(b).tick(level, b, new Ignite());
            helper.assertTrue(level.getBlockState(c).is(Blocks.FIRE), "Fire spread from B into burned C");
            rule.set(fireTick, level.getServer());
            for (var pos : BlockPos.betweenClosed(a.offset(-1, -1, -1), c.offset(1, 4, 1)))
                if (level.getBlockState(pos).getBlock() instanceof BaseFireBlock) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }).thenWaitUntil(persisted(helper, e -> at(e).equals(b) || at(e).equals(c), events -> {
            for (var pos : List.of(b, c)) {
                var burned = only(helper, events, "BLOCK_BREAK", pos);
                causedBy(helper, burned, "#fire", starter);
                helper.assertTrue(burned.envelope().targetId().equals("minecraft:oak_planks"), "Burned planks recorded");
            }
        })).thenWaitUntil(() -> {
            String name = starter.getGameProfile().getName();
            var rows = lookup(helper, lookup, "r:global t:1h u:" + name + " b:oak_planks", 2);
            helper.assertTrue(rows.stream().allMatch(row -> row.contains("- #fire (← " + name + ") broke oak_planks")), "Root shown on burnouts: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_fire")
    public static void pouredLavaIgnitionAttributesBurnoutToPourer(GameTestHelper helper) {
        var level = helper.getLevel(); var pourer = player(helper, unique("Lava"));
        var floor = helper.absolutePos(new BlockPos(4, 1, 4)); var lava = floor.above();
        var planks = lava.offset(-1, 0, -1); var flame = planks.above();
        var rule = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean fireTick = rule.get();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            rule.set(true, level.getServer());
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(planks, Blocks.OAK_PLANKS.defaultBlockState());
            pourer.setPos(lava.getX() + .5, lava.getY() + 1, lava.getZ() + .5);
            pourer.setXRot(90);
            pourer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
            pourer.gameMode.useItem(pourer, level, pourer.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getFluidState(lava).is(net.minecraft.tags.FluidTags.LAVA), "Player poured lava");
            // With every roll forced to zero, lava ignites the air above the planks diagonally below-left of it.
            level.getFluidState(lava).randomTick(level, lava, new Ignite());
            helper.assertTrue(level.getBlockState(flame).is(Blocks.FIRE), "Lava ignited the air above the planks");
            level.getBlockState(flame).tick(level, flame, new Ignite());
            // Unsupported fire left in the planks' place may immediately update to air; either way the planks are gone.
            helper.assertTrue(!level.getBlockState(planks).is(Blocks.OAK_PLANKS), "Fire burned the planks");
            rule.set(fireTick, level.getServer());
            level.setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState());
            for (var pos : BlockPos.betweenClosed(planks.offset(-1, -1, -1), lava.offset(1, 4, 1)))
                if (level.getBlockState(pos).getBlock() instanceof BaseFireBlock) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }).thenWaitUntil(persisted(helper, e -> at(e).equals(planks), events -> {
            var burned = only(helper, events, "BLOCK_BREAK", planks);
            causedBy(helper, burned, "#fire", pourer);
        })).thenSucceed();
    }

    /**
     * A dispenser facing down into a walled pit, its lever on top, pours and scoops lava, water and powder snow. Each row
     * is the dispenser's, rooted at the player who flipped the lever, and the dispensed lava's fire keeps that root.
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "chronicle_causality_dispenser")
    public static void dispensedBucketsAreAttributedToTheTriggeringPlayer(GameTestHelper helper) {
        var level = helper.getLevel(); var trigger = player(helper, unique("Dispense"));
        var pit = helper.absolutePos(new BlockPos(2, 1, 2)); var dispenser = pit.above(); var lever = dispenser.above();
        var planks = pit.offset(-1, 0, -1); var flame = planks.above();
        var rule = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean fireTick = rule.get();
        Object[] lookup = new Object[1];
        Runnable flip = () -> click(trigger, lever, ItemStack.EMPTY, Direction.UP);
        Consumer<Item> load = item -> {
            var inventory = (net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(dispenser);
            inventory.clearContent();
            inventory.setItem(0, new ItemStack(item));
        };
        Runnable retrigger = () -> {
            flip.run(); flip.run();
            helper.assertTrue(level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Lever retriggered the dispenser");
        };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(pit.offset(-1, -1, -1), pit.offset(1, -1, 1))) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var side : Direction.Plane.HORIZONTAL) level.setBlockAndUpdate(pit.relative(side), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(planks, Blocks.OAK_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.DOWN));
            level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
            load.accept(Items.LAVA_BUCKET);
            flip.run();
            helper.assertTrue(level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Lever triggered the dispenser");
        }).thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA),
                "Waiting for dispensed lava"))
        .thenExecute(() -> {
            rule.set(true, level.getServer());
            // With every roll forced to zero, lava ignites the air above the planks diagonally below-left of it.
            level.getFluidState(pit).randomTick(level, pit, new Ignite());
            helper.assertTrue(level.getBlockState(flame).is(Blocks.FIRE), "Dispensed lava ignited the air above the planks");
            level.getBlockState(flame).tick(level, flame, new Ignite());
            helper.assertTrue(!level.getBlockState(planks).is(Blocks.OAK_PLANKS), "Fire burned the planks");
            rule.set(fireTick, level.getServer());
            for (var pos : BlockPos.betweenClosed(planks.offset(-1, -1, -1), pit.offset(1, 3, 1)))
                if (level.getBlockState(pos).getBlock() instanceof BaseFireBlock) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            // The pour left the empty bucket in the dispenser.
            retrigger.run();
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pit).isAir(), "Waiting for the dispenser to scoop the lava"))
        .thenExecute(() -> { load.accept(Items.WATER_BUCKET); retrigger.run(); })
        .thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER),
                "Waiting for dispensed water"))
        .thenExecute(() -> {
            level.setBlockAndUpdate(pit, Blocks.AIR.defaultBlockState());
            load.accept(Items.POWDER_SNOW_BUCKET);
            retrigger.run();
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pit).is(Blocks.POWDER_SNOW), "Waiting for dispensed powder snow"))
        .thenExecute(retrigger)
        .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pit).isAir()
                && ((net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(dispenser)).getItem(0).is(Items.POWDER_SNOW_BUCKET),
                "Waiting for the dispenser to scoop the powder snow"))
        .thenWaitUntil(persisted(helper, e -> at(e).equals(pit) || at(e).equals(planks), events -> {
            helper.assertTrue(events.size() == 6, "Expected 5 dispenser rows and 1 burnout, got " + events.size());
            for (var event : events.stream().filter(e -> at(e).equals(pit)).toList()) causedBy(helper, event, "#dispenser", trigger);
            var placed = events.stream().filter(e -> at(e).equals(pit) && e.envelope().eventType().equals("BLOCK_PLACE"))
                    .map(e -> e.envelope().targetId()).sorted().toList();
            var scooped = events.stream().filter(e -> at(e).equals(pit) && e.envelope().eventType().equals("BLOCK_BREAK"))
                    .map(e -> e.envelope().targetId()).sorted().toList();
            helper.assertTrue(placed.equals(List.of("minecraft:lava", "minecraft:powder_snow", "minecraft:water")), "Pours " + placed);
            helper.assertTrue(scooped.equals(List.of("minecraft:lava", "minecraft:powder_snow")), "Pickups " + scooped);
            causedBy(helper, only(helper, events, "BLOCK_BREAK", planks), "#fire", trigger);
        })).thenWaitUntil(() -> {
            String name = trigger.getGameProfile().getName();
            var rows = lookup(helper, lookup, "r:global t:1h b:lava u:" + name, 2);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("- #dispenser (← " + name + ") broke lava"))
                    && rows.stream().anyMatch(row -> row.contains("+ #dispenser (← " + name + ") placed lava")), "Lava rows: " + rows);
        }).thenWaitUntil(() -> lookup(helper, lookup, "r:global t:1h u:" + trigger.getGameProfile().getName(), 6))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void itemFrameDroppedByLostSupportIsAttributedToBreaker(GameTestHelper helper) {
        var level = helper.getLevel(); var breaker = player(helper, "SupportBreaker");
        var framePos = helper.absolutePos(new BlockPos(2, 2, 2)); var wall = framePos.south();
        var frames = new Object() { ItemFrame frame; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            frames.frame = new ItemFrame(level, framePos, Direction.NORTH);
            frames.frame.setItem(new ItemStack(Items.EMERALD, 1));
            helper.assertTrue(level.addFreshEntity(frames.frame) && frames.frame.survives(), "Frame hangs on the wall");
            helper.assertTrue(breaker.gameMode.destroyBlock(wall), "Player breaks the supporting wall");
        }).thenWaitUntil(() -> helper.assertTrue(frames.frame.isRemoved(), "Waiting for the frame's support check"))
        .thenWaitUntil(persisted(helper, e -> e.envelope().eventType().startsWith("ITEM_") && at(e).equals(framePos), events -> {
            var dropped = only(helper, events, "ITEM_TAKE", framePos);
            directly(helper, dropped, breaker);
            helper.assertTrue(dropped.envelope().targetId().equals("minecraft:emerald") && item(dropped).count() == 1
                    && item(dropped).containerBlockId().equals("minecraft:item_frame"), "Framed emerald recorded as taken");
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void arrowShatteredPotIsAttributedToShooter(GameTestHelper helper) {
        var level = helper.getLevel(); var shooter = player(helper, "PotShooter");
        var pot = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(pot, Blocks.DECORATED_POT.defaultBlockState());
            ((Container)level.getBlockEntity(pot)).setItem(0, new ItemStack(Items.GOLD_INGOT, 4));
            var arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);
            arrow.setPos(pot.getX() + .5, pot.getY() + 2.5, pot.getZ() + .5);
            arrow.setDeltaMovement(0, -1.5, 0);
            helper.assertTrue(level.addFreshEntity(arrow), "Arrow spawned");
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pot).isAir(), "Waiting for the arrow to shatter the pot"))
        .thenWaitUntil(persisted(helper, e -> at(e).equals(pot), events -> {
            var shattered = only(helper, events, "BLOCK_BREAK", pot);
            directly(helper, shattered, shooter);
            helper.assertTrue(shattered.envelope().targetId().equals("minecraft:decorated_pot"), "Pot break recorded");
            var spill = only(helper, events, "ITEM_TAKE", pot);
            directly(helper, spill, shooter);
            helper.assertTrue(spill.envelope().targetId().equals("minecraft:gold_ingot") && item(spill).count() == 4, "Pot contents recorded");
        })).thenSucceed();
    }

    /**
     * A places a dispenser over a walled pit; B loads it with lava through its menu and flips a lever that reaches it
     * through a slow repeater, while bystander C breaks dirt nearby. The delayed pour is B's. D's later flip scoops the
     * lava back as D's. Once no activation is recent, a redstone block's trigger falls back to the loader, B.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_dispenser_delay")
    public static void repeaterDelayedDispenserIsAttributedToTriggerThenLoader(GameTestHelper helper) {
        var level = helper.getLevel();
        var placer = player(helper, unique("Placer")); var loader = player(helper, unique("Loader"));
        var bystander = player(helper, unique("Bystander")); var flipper = player(helper, unique("Flipper"));
        var pit = helper.absolutePos(new BlockPos(2, 1, 2)); var dispenser = pit.above();
        var repeater = dispenser.east(); var lever = repeater.east(); var dirt = pit.offset(-1, 1, -2);
        var dispensers = new Object() { net.minecraft.world.level.block.entity.DispenserBlockEntity inventory; };
        Runnable flipOn = () -> click(flipper, lever, ItemStack.EMPTY, Direction.UP);
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(pit.offset(-1, -1, -1), pit.offset(2, -1, 1))) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var side : Direction.Plane.HORIZONTAL) level.setBlockAndUpdate(pit.relative(side), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pit.offset(2, 0, 0), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            // Looking up, the dispenser faces down; it is placed on a temporary block in the pit.
            level.setBlockAndUpdate(pit, Blocks.STONE.defaultBlockState());
            placer.setXRot(-90);
            click(placer, pit, new ItemStack(Items.DISPENSER), Direction.UP);
            helper.assertTrue(level.getBlockState(dispenser).is(Blocks.DISPENSER)
                    && level.getBlockState(dispenser).getValue(DispenserBlock.FACING) == Direction.DOWN, "Placer placed a downward dispenser");
            level.setBlockAndUpdate(pit, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(repeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST).setValue(RepeaterBlock.DELAY, 4));
            level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
            dispensers.inventory = (net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(dispenser);
            helper.assertTrue(loader.openMenu(level.getBlockState(dispenser).getMenuProvider(level, dispenser)).isPresent(), "Loader opens the dispenser");
            dispensers.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            loader.closeContainer();
            click(loader, lever, ItemStack.EMPTY, Direction.UP);
            helper.assertTrue(!level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "The repeater delays the trigger");
            helper.assertTrue(bystander.gameMode.destroyBlock(dirt), "Bystander breaks dirt nearby");
        }).thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA),
                "Waiting for the delayed pour"))
        .thenExecute(() -> click(flipper, lever, ItemStack.EMPTY, Direction.UP))
        .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Waiting for the repeater to turn off"))
        .thenExecute(() -> {
            flipOn.run();
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            helper.assertTrue(bystander.gameMode.destroyBlock(dirt), "Bystander breaks dirt again");
        })
        .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pit).isAir() && dispensers.inventory.getItem(0).is(Items.LAVA_BUCKET),
                "Waiting for the delayed scoop"))
        .thenExecute(() -> click(flipper, lever, ItemStack.EMPTY, Direction.UP))
        .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Waiting for the repeater to turn off"))
        .thenIdle(io.github.andriyko69.chronicle.causality.EnvironmentAudit.ACTIVATION_TICKS + 5)
        .thenExecute(() -> {
            level.setBlockAndUpdate(dispenser.north(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            helper.assertTrue(level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Redstone block triggered the dispenser");
        })
        .thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA),
                "Waiting for the clock-style pour"))
        .thenExecute(() -> {
            level.setBlockAndUpdate(dispenser.north(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pit, Blocks.AIR.defaultBlockState());
        })
        .thenWaitUntil(persisted(helper, e -> at(e).equals(pit), events -> {
            var placed = events.stream().filter(e -> e.envelope().eventType().equals("BLOCK_PLACE")).toList();
            helper.assertTrue(events.size() == 3 && placed.size() == 2, "Expected two pours and one scoop, got " + events);
            for (var pour : placed) {
                helper.assertTrue(pour.envelope().targetId().equals("minecraft:lava"), "Lava poured " + pour.envelope());
                causedBy(helper, pour, "#dispenser", loader);
            }
            var scoop = only(helper, events, "BLOCK_BREAK", pit);
            helper.assertTrue(scoop.envelope().targetId().equals("minecraft:lava"), "Lava scooped " + scoop.envelope());
            causedBy(helper, scoop, "#dispenser", flipper);
        })).thenSucceed();
    }

    /**
     * A clock-style trigger (a redstone block, no player activation) pours and scoops while a viewer keeps the menu open
     * and a walker crosses an empty detector rail beside it. Vanilla installs each remainder bucket after dispensing;
     * neither it nor the walk may become the viewer's deposit, the walker's activation, or a change of owner.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_dispenser_viewer")
    public static void openMenuViewerAndRailWalkerDoNotClaimClockDrivenDispenser(GameTestHelper helper) {
        var level = helper.getLevel();
        var loader = player(helper, unique("Loader")); var viewer = player(helper, unique("Viewer")); var walker = player(helper, unique("Walker"));
        var pit = helper.absolutePos(new BlockPos(2, 1, 2)); var dispenser = pit.above(); var power = dispenser.north();
        var rail = dispenser.west();
        var dispensers = new Object() { net.minecraft.world.level.block.entity.DispenserBlockEntity inventory; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(pit.offset(-1, -1, -1), pit.offset(1, -1, 1))) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var side : Direction.Plane.HORIZONTAL) level.setBlockAndUpdate(pit.relative(side), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.DOWN));
            level.setBlockAndUpdate(rail, Blocks.DETECTOR_RAIL.defaultBlockState());
            dispensers.inventory = (net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(dispenser);
            helper.assertTrue(loader.openMenu(level.getBlockState(dispenser).getMenuProvider(level, dispenser)).isPresent(), "Loader opens the dispenser");
            dispensers.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            loader.closeContainer();
            helper.assertTrue(viewer.openMenu(level.getBlockState(dispenser).getMenuProvider(level, dispenser)).isPresent(), "Viewer opens the dispenser");
            level.getBlockState(rail).entityInside(level, rail, walker);
            helper.assertTrue(!level.getBlockState(rail).getValue(DetectorRailBlock.POWERED), "A walker does not power an empty detector rail");
            level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        }).thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA)
                && dispensers.inventory.getItem(0).is(Items.BUCKET), "Waiting for the pour"))
        .thenExecute(() -> level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState()))
        .thenWaitUntil(() -> helper.assertTrue(!level.getBlockState(dispenser).getValue(DispenserBlock.TRIGGERED), "Waiting for the trigger to reset"))
        .thenExecute(() -> {
            level.getBlockState(rail).entityInside(level, rail, walker);
            level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        })
        .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(pit).isAir() && dispensers.inventory.getItem(0).is(Items.LAVA_BUCKET),
                "Waiting for the scoop"))
        .thenExecute(() -> {
            helper.assertTrue(viewer.containerMenu != viewer.inventoryMenu, "Viewer kept the menu open throughout");
            viewer.closeContainer();
            level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(rail, Blocks.AIR.defaultBlockState());
        })
        .thenWaitUntil(persisted(helper, e -> at(e).equals(pit) || at(e).equals(dispenser), events -> {
            var items = events.stream().filter(e -> at(e).equals(dispenser)).toList();
            helper.assertTrue(items.size() == 1 && items.getFirst().envelope().eventType().equals("ITEM_PUT")
                    && items.getFirst().envelope().targetId().equals("minecraft:lava_bucket"), "Only the loader's deposit, got " + items);
            directly(helper, items.getFirst(), loader);
            var place = only(helper, events, "BLOCK_PLACE", pit);
            var scoop = only(helper, events, "BLOCK_BREAK", pit);
            causedBy(helper, place, "#dispenser", loader);
            causedBy(helper, scoop, "#dispenser", loader);
        })).thenSucceed();
    }

    /**
     * Two viewers share a dispenser's menu while one loads lava: their net diffs cannot show who did, so neither becomes
     * its owner, even the idle one whose session is checkpointed at the dispense. A right-click that changes nothing on a
     * block touching the dispenser is no activation either; the clock-style pour has no root.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_dispenser_shared")
    public static void overlappingViewersAndInertClicksDoNotClaimDispenser(GameTestHelper helper) {
        var level = helper.getLevel();
        var depositor = player(helper, unique("Depositor")); var idle = player(helper, unique("Idle")); var clicker = player(helper, unique("Clicker"));
        var pit = helper.absolutePos(new BlockPos(2, 1, 2)); var dispenser = pit.above(); var power = dispenser.north();
        var touching = dispenser.east();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(pit.offset(-1, -1, -1), pit.offset(1, -1, 1))) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var side : Direction.Plane.HORIZONTAL) level.setBlockAndUpdate(pit.relative(side), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.DOWN));
            level.setBlockAndUpdate(touching, Blocks.STONE.defaultBlockState());
            var menu = level.getBlockState(dispenser).getMenuProvider(level, dispenser);
            helper.assertTrue(depositor.openMenu(menu).isPresent() && idle.openMenu(menu).isPresent(), "Both viewers open the dispenser");
            ((net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.LAVA_BUCKET));
            depositor.closeContainer();
            click(clicker, touching, ItemStack.EMPTY, Direction.UP);
            helper.assertTrue(level.getBlockState(touching).is(Blocks.STONE), "The click changed nothing");
            level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        }).thenWaitUntil(() -> helper.assertTrue(level.getFluidState(pit).isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA),
                "Waiting for the pour"))
        .thenExecute(() -> {
            idle.closeContainer();
            level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pit, Blocks.AIR.defaultBlockState());
        })
        .thenWaitUntil(persisted(helper, e -> at(e).equals(pit), events -> {
            var pour = only(helper, events, "BLOCK_PLACE", pit).envelope();
            helper.assertTrue(pour.actorId().equals("#dispenser") && pour.rootActorUuid() == null && pour.rootActorId() == null,
                    "Ambiguous load and inert click leave the pour unrooted, got " + pour);
        })).thenSucceed();
    }

    /** A creative player's arrow kills a stand outright, without dropping; its equipment is still recorded as taken. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void creativeArrowDestroyingArmorStandRecordsItsEquipment(GameTestHelper helper) {
        var level = helper.getLevel(); var shooter = player(helper, "StandShooter");
        var floor = helper.absolutePos(new BlockPos(3, 0, 3)); var standPos = floor.above();
        var stands = new Object() { net.minecraft.world.entity.decoration.ArmorStand stand; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            var stand = new net.minecraft.world.entity.decoration.ArmorStand(level, standPos.getX() + .5, standPos.getY(), standPos.getZ() + .5);
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            helper.assertTrue(level.addFreshEntity(stand), "Stand spawned");
            stands.stand = stand;
            shooter.setGameMode(GameType.CREATIVE);
            var arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), null);
            arrow.setPos(standPos.getX() + .5, standPos.getY() + 3.5, standPos.getZ() + .5);
            arrow.setDeltaMovement(0, -1.5, 0);
            helper.assertTrue(level.addFreshEntity(arrow), "Arrow spawned");
        }).thenWaitUntil(() -> helper.assertTrue(stands.stand.isRemoved(), "Waiting for the arrow to destroy the stand"))
        .thenExecute(() -> level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(standPos).inflate(4),
                entity -> entity instanceof Arrow || entity instanceof net.minecraft.world.entity.item.ItemEntity)
                .forEach(net.minecraft.world.entity.Entity::discard))
        .thenWaitUntil(persisted(helper, e -> at(e).equals(standPos), events -> {
            helper.assertTrue(events.size() == 2 && events.stream().allMatch(e -> e.envelope().eventType().equals("ITEM_TAKE")
                    && item(e).containerBlockId().equals("minecraft:armor_stand")), "Expected two armor stand takes, got " + events);
            for (var event : events) directly(helper, event, shooter);
            var taken = events.stream().map(e -> e.envelope().targetId()).sorted().toList();
            helper.assertTrue(taken.equals(List.of("minecraft:diamond_chestplate", "minecraft:iron_sword")), "Equipment recorded " + taken);
        })).thenSucceed();
    }

    private static boolean inside(GameTestHelper helper, AuditEvent event) {
        var origin = helper.absolutePos(BlockPos.ZERO);
        var pos = at(event);
        return pos.getX() >= origin.getX() - 4 && pos.getX() <= origin.getX() + 10 && pos.getZ() >= origin.getZ() - 4 && pos.getZ() <= origin.getZ() + 10;
    }
    private static net.minecraft.world.entity.monster.Creeper creeperTargeting(GameTestHelper helper, ServerPlayer target) {
        var creeper = EntityType.CREEPER.create(helper.getLevel());
        creeper.setTarget(target);
        return creeper;
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_keep")
    public static void survivingExplosionDoesNotClaimLaterDestruction(GameTestHelper helper) {
        var level = helper.getLevel(); var owner = player(helper, unique("Keep"));
        var dirt = helper.absolutePos(new BlockPos(3, 2, 3)); var center = Vec3.atCenterOf(dirt);
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            // As a creeper with mobGriefing off: the blast lists the block but leaves it in place.
            level.explode(null, center.x, center.y, center.z, 2.0F, Level.ExplosionInteraction.NONE);
            helper.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT), "Non-destructive blast left the dirt");
            level.explode(new PrimedTnt(level, center.x, center.y, center.z, owner), center.x, center.y, center.z, 2.0F, Level.ExplosionInteraction.TNT);
            helper.assertTrue(level.getBlockState(dirt).isAir(), "Player's TNT removed the dirt in the same tick");
        }).thenWaitUntil(persisted(helper, e -> at(e).equals(dirt), events -> causedBy(helper, only(helper, events, "BLOCK_BREAK", dirt), "#tnt", owner)))
        .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_chain")
    public static void explosionPrimedTntInheritsThatExplosionsRoot(GameTestHelper helper) {
        var level = helper.getLevel(); var target = player(helper, unique("Chain"));
        var tnt = helper.absolutePos(new BlockPos(3, 2, 3)); var dirt = tnt.east(2);
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(tnt, Blocks.TNT.defaultBlockState());
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            var center = Vec3.atCenterOf(tnt);
            level.explode(creeperTargeting(helper, target), center.x, center.y, center.z, 1.0F, Level.ExplosionInteraction.TNT);
            var primed = level.getEntitiesOfClass(PrimedTnt.class, new AABB(tnt).inflate(1));
            helper.assertTrue(primed.size() == 1 && !(primed.getFirst().getOwner() instanceof net.minecraft.world.entity.player.Player),
                    "Creeper blast chain-primed the TNT without a player owner");
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(dirt).isAir(), "Waiting for the chained TNT"))
        .thenWaitUntil(persisted(helper, e -> e.envelope().eventType().equals("BLOCK_BREAK") && inside(helper, e), events -> {
            causedBy(helper, only(helper, events, "BLOCK_BREAK", tnt), "#creeper", target);
            var chained = events.stream().filter(e -> e.envelope().actorId().equals("#tnt")).toList();
            helper.assertTrue(chained.stream().anyMatch(e -> at(e).equals(dirt)), "Chained TNT destroyed the dirt");
            for (var event : chained) causedBy(helper, event, "#tnt", target);
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_unrelated")
    public static void independentTntDoesNotInheritNearbyBlastRoot(GameTestHelper helper) {
        var level = helper.getLevel(); var target = player(helper, unique("Near"));
        var blast = helper.absolutePos(new BlockPos(2, 2, 3)); var tnt = blast.east(2); var dirt = tnt.east();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            var center = Vec3.atCenterOf(blast);
            level.explode(creeperTargeting(helper, target), center.x, center.y, center.z, 1.0F, Level.ExplosionInteraction.TNT);
            // Primed by something else in the same tick, two blocks from the attributed blast.
            var independent = new PrimedTnt(level, tnt.getX() + .5, tnt.getY(), tnt.getZ() + .5, null);
            independent.setFuse(1);
            helper.assertTrue(level.addFreshEntity(independent), "Independent TNT spawned");
        }).thenWaitUntil(() -> helper.assertTrue(level.getBlockState(dirt).isAir(), "Waiting for the independent TNT"))
        .thenWaitUntil(persisted(helper, e -> e.envelope().eventType().equals("BLOCK_BREAK") && inside(helper, e), events -> {
            var independent = events.stream().filter(e -> e.envelope().actorId().equals("#tnt")).toList();
            helper.assertTrue(independent.stream().anyMatch(e -> at(e).equals(dirt)), "Independent TNT destroyed the dirt");
            helper.assertTrue(independent.stream().allMatch(e -> e.envelope().rootActorUuid() == null),
                    "Independent TNT must not inherit the creeper target: " + independent.stream().map(AuditEvent::envelope).toList());
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_causality_lag")
    public static void frameDropOutlivesWallClockLag(GameTestHelper helper) {
        var level = helper.getLevel(); var breaker = player(helper, unique("Lag"));
        var framePos = helper.absolutePos(new BlockPos(2, 2, 2)); var wall = framePos.south();
        var frames = new Object() { ItemFrame frame; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            frames.frame = new ItemFrame(level, framePos, Direction.NORTH);
            frames.frame.setItem(new ItemStack(Items.AMETHYST_SHARD));
            helper.assertTrue(level.addFreshEntity(frames.frame), "Frame hangs on the wall");
            helper.assertTrue(breaker.gameMode.destroyBlock(wall), "Player breaks the supporting wall");
            // Minutes of wall-clock time pass before the frame's next support check, as at a few ticks per second.
            ChronicleTestMod.skew = java.time.Duration.ofMinutes(5);
        }).thenWaitUntil(() -> helper.assertTrue(frames.frame.isRemoved(), "Waiting for the frame's support check"))
        .thenExecute(() -> ChronicleTestMod.skew = java.time.Duration.ZERO)
        .thenWaitUntil(persisted(helper, e -> e.envelope().eventType().startsWith("ITEM_") && at(e).equals(framePos), events -> {
            var dropped = only(helper, events, "ITEM_TAKE", framePos);
            directly(helper, dropped, breaker);
            helper.assertTrue(item(dropped).count() == 1, "Framed shard recorded as taken");
        })).thenSucceed();
    }
}
