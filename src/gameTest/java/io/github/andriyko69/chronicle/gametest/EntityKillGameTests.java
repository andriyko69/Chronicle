/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.codec.EntityKillPayloadCodec;
import io.github.andriyko69.chronicle.event.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Live ENTITY_KILL capture and attribution; the production database persists across runs, so actors are unique. */
@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class EntityKillGameTests {
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
    private static boolean inside(GameTestHelper helper, AuditEvent event) {
        var envelope = event.envelope();
        return helper.getBounds().inflate(2).contains(envelope.x() + .5, envelope.y() + .5, envelope.z() + .5);
    }
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
    private static Predicate<AuditEvent> kills(GameTestHelper helper) {
        return e -> e.envelope().eventType().equals("ENTITY_KILL") && inside(helper, e);
    }
    private static AuditEvent only(GameTestHelper helper, List<AuditEvent> events, String entity) {
        var matching = events.stream().filter(e -> e.envelope().targetId().equals(entity)).toList();
        helper.assertTrue(matching.size() == 1, "Expected one kill of " + entity + ", got " + matching.stream().map(AuditEvent::envelope).toList());
        return matching.getFirst();
    }
    private static EntityKillPayload payload(AuditEvent event) {
        return new EntityKillPayloadCodec().decode(event.payloadVersion(), event.payload());
    }
    private static void directly(GameTestHelper helper, AuditEvent event, ServerPlayer player) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorType().equals("PLAYER") && player.getUUID().equals(envelope.actorUuid()) && envelope.rootActorUuid() == null,
                "Expected direct player actor, got " + envelope);
    }
    private static void causedBy(GameTestHelper helper, AuditEvent event, String actor, ServerPlayer root) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorType().equals("ENVIRONMENT") && envelope.actorId().equals(actor) && envelope.actorUuid() == null,
                "Expected environmental actor " + actor + ", got " + envelope);
        helper.assertTrue(root.getUUID().equals(envelope.rootActorUuid()) && root.getGameProfile().getName().equals(envelope.rootActorId()),
                "Expected root " + root.getGameProfile().getName() + ", got " + envelope);
    }
    /** Unlike GameTestHelper.spawn, which makes every mob persistent and so always audited, this keeps natural despawn rules. */
    private static <T extends Mob> T spawn(GameTestHelper helper, EntityType<T> type, BlockPos relative, float health) {
        var entity = type.create(helper.getLevel());
        var at = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        entity.moveTo(at.x, at.y, at.z, 0, 0);
        entity.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Spawned " + type);
        entity.setHealth(health);
        return entity;
    }
    /**
     * The test players are never online and the GameTest server has no profile cache, so an owner's name is unknown:
     * the row keeps the owner's UUID, which {@code u:<uuid>} finds, and the name lookup works once they have any named row.
     */
    private static void causedByOffline(GameTestHelper helper, AuditEvent event, String actor, ServerPlayer root) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorType().equals("ENVIRONMENT") && envelope.actorId().equals(actor) && envelope.actorUuid() == null
                && root.getUUID().equals(envelope.rootActorUuid()) && envelope.rootActorId() == null,
                "Expected " + actor + " rooted at offline " + root.getUUID() + ", got " + envelope);
    }
    private static boolean domesticated(Entity entity) {
        return entity.getPersistentData().getBoolean(io.github.andriyko69.chronicle.causality.EntityKillCapture.DOMESTICATED);
    }
    /** A player feeds the animal; once its love mode ends, only Chronicle's mark shows it belongs to a farm. */
    private static <T extends Animal> T fed(GameTestHelper helper, ServerPlayer farmer, T animal, net.minecraft.world.item.Item food) {
        farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(food));
        helper.assertTrue(farmer.interactOn(animal, InteractionHand.MAIN_HAND).consumesAction() && animal.isInLove(), "Fed " + animal.getType());
        animal.resetLove();
        helper.assertTrue(domesticated(animal), "Feeding marked " + animal.getType() + " domesticated");
        return animal;
    }
    private static Wolf tamedBy(GameTestHelper helper, BlockPos relative, ServerPlayer owner) {
        var wolf = spawn(helper, EntityType.WOLF, relative, 1);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(owner.getUUID());
        return wolf;
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
    /** Production lookups read Chronicle's own database, which may persist after the test runtime; re-issued until settled. */
    private static Runnable lookup(GameTestHelper helper, Reply reply, String query, int rows) {
        return () -> {
            command(helper, reply, "chronicle lookup " + query, "Chronicle Lookup").run();
            if (rows(reply).size() != rows) {
                var seen = reply.output.lines;
                reply.output = null;
                helper.fail("Waiting for " + rows + " rows from " + query + ": " + seen);
            }
        };
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_direct")
    public static void directAndBowKillsAreRecordedWhileUnnamedHostilesAreNot(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("Killer"); var killer = player(helper, name); var farmer = player(helper, unique("Farmer"));
        var lookup = new Reply();
        var wildCow = helper.absolutePos(new BlockPos(1, 2, 7)); var squid = helper.absolutePos(new BlockPos(3, 2, 7));
        var calf = helper.absolutePos(new BlockPos(5, 2, 4));
        var slainGolem = helper.absolutePos(new BlockPos(1, 2, 4)); var farmGolem = helper.absolutePos(new BlockPos(5, 2, 7));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            var cow = fed(helper, farmer, spawn(helper, EntityType.COW, new BlockPos(1, 2, 1), 1), Items.WHEAT);
            var villager = spawn(helper, EntityType.VILLAGER, new BlockPos(3, 2, 1), 20);
            villager.setCustomName(Component.literal("Master Trader"));
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(5));
            var wild = spawn(helper, EntityType.ZOMBIE, new BlockPos(5, 2, 1), 1);
            var named = spawn(helper, EntityType.ZOMBIE, new BlockPos(7, 2, 1), 1);
            named.setCustomName(Component.literal("Farm Bait"));
            helper.assertTrue(!wild.isPersistenceRequired() && !wild.hasCustomName(), "The wild zombie may despawn");
            cow.hurt(level.damageSources().playerAttack(killer), 100);
            var arrow = new Arrow(level, killer, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            villager.hurt(level.damageSources().arrow(arrow, killer), 100);
            wild.hurt(level.damageSources().playerAttack(killer), 100);
            named.hurt(level.damageSources().playerAttack(killer), 100);
            helper.assertTrue(cow.isDeadOrDying() && villager.isDeadOrDying() && wild.isDeadOrDying() && named.isDeadOrDying(), "All four died");

            // Wildlife nobody touched is hunted in the wilderness: no rows.
            var untouched = List.of(spawn(helper, EntityType.COW, new BlockPos(1, 2, 7), 1), spawn(helper, EntityType.SQUID, new BlockPos(3, 2, 7), 1));
            for (var animal : untouched) animal.hurt(level.damageSources().playerAttack(killer), 100);
            // Breeding marks both parents and the calf they produce.
            var mother = spawn(helper, EntityType.COW, new BlockPos(5, 2, 4), 10); var father = spawn(helper, EntityType.COW, new BlockPos(7, 2, 4), 10);
            mother.spawnChildFromBreeding(level, father);
            var calves = level.getEntitiesOfClass(Cow.class, new AABB(calf), Cow::isBaby);
            helper.assertTrue(calves.size() == 1 && domesticated(calves.getFirst()) && domesticated(mother) && domesticated(father),
                    "Bred calf and both parents are domesticated");
            calves.getFirst().hurt(level.damageSources().playerAttack(killer), 100);
            // A natural (village or farm) iron golem is recorded when a player kills it, not when it falls into lava on its own.
            var golem = spawn(helper, EntityType.IRON_GOLEM, new BlockPos(1, 2, 4), 1);
            var farmed = spawn(helper, EntityType.IRON_GOLEM, new BlockPos(5, 2, 7), 1);
            helper.assertTrue(!golem.isPlayerCreated() && !farmed.isPlayerCreated(), "Both golems spawned naturally");
            golem.hurt(level.damageSources().playerAttack(killer), 1000);
            farmed.hurt(level.damageSources().lava(), 1000);
            helper.assertTrue(untouched.stream().allMatch(Mob::isDeadOrDying) && calves.getFirst().isDeadOrDying() && golem.isDeadOrDying()
                    && farmed.isDeadOrDying(), "Wildlife, calf and golems died");
        }).thenWaitUntil(persisted(helper, kills(helper), events -> {
            helper.assertTrue(events.stream().noneMatch(e -> e.envelope().targetId().equals("minecraft:squid")
                    || at(e).equals(wildCow) || at(e).equals(squid) || at(e).equals(farmGolem)),
                    "Untouched wildlife and the unattributed golem death left no rows: " + events.stream().map(AuditEvent::envelope).toList());
            directly(helper, killedAt(helper, events, calf), killer);
            directly(helper, killedAt(helper, events, slainGolem), killer);
            var cow = killedAt(helper, events, helper.absolutePos(new BlockPos(1, 2, 1)));
            directly(helper, cow, killer);
            helper.assertTrue(cow.envelope().equals(new EventEnvelope(level.dimension().location().toString(),
                    helper.absolutePos(new BlockPos(1, 2, 1)).getX(), helper.absolutePos(new BlockPos(1, 2, 1)).getY(),
                    helper.absolutePos(new BlockPos(1, 2, 1)).getZ(), "ENTITY_KILL", "PLAYER", name, killer.getUUID(), null, "minecraft:cow")),
                    "Cow kill recorded where it died: " + cow.envelope());
            var villager = only(helper, events, "minecraft:villager");
            directly(helper, villager, killer);
            helper.assertTrue(payload(villager).equals(new EntityKillPayload("minecraft:villager", "Master Trader", null, null, "librarian (Lv.5)")),
                    "Villager name and profession captured: " + payload(villager));
            var zombie = only(helper, events, "minecraft:zombie");
            helper.assertTrue("Farm Bait".equals(payload(zombie).customName()), "Only the named zombie was recorded");
        }))
        .thenWaitUntil(lookup(helper, lookup, "r:global t:1h a:kill u:" + name, 5))
        .thenExecute(() -> {
            var rows = rows(lookup);
            var villager = rows.stream().filter(row -> row.contains("killed villager \"Master Trader\"")).findFirst();
            helper.assertTrue(villager.isPresent() && villager.get().contains("Detail: librarian (Lv.5)") && villager.get().contains("Name: Master Trader"),
                    "Villager row shows its name and profession: " + rows);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("- " + name + " killed cow @ ")), "Cow row: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_creeper")
    public static void creeperBlastKillsAreRootedAtThePlayerItAggroedOn(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("Aggro"); var target = player(helper, name); var owner = player(helper, unique("Owner"));
        var lookup = new Reply();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            fed(helper, owner, spawn(helper, EntityType.SHEEP, new BlockPos(3, 2, 4), 1), Items.WHEAT);
            tamedBy(helper, new BlockPos(5, 2, 4), owner);
            var creeper = EntityType.CREEPER.create(level);
            creeper.setTarget(target);
            // A creeper that loses its target mid-fuse keeps swelling and still explodes.
            creeper.setSwellDir(1);
            creeper.setTarget(null);
            var center = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 4)));
            creeper.setPos(center);
            level.explode(creeper, center.x, center.y, center.z, 3.0F, Level.ExplosionInteraction.NONE);
            helper.assertTrue(level.getEntitiesOfClass(Animal.class, new AABB(helper.absolutePos(new BlockPos(4, 2, 4))).inflate(3), Animal::isAlive).isEmpty(),
                    "Blast killed the sheep and the wolf");
        }).thenWaitUntil(persisted(helper, kills(helper), events -> {
            causedBy(helper, only(helper, events, "minecraft:sheep"), "#creeper", target);
            var wolf = only(helper, events, "minecraft:wolf");
            causedBy(helper, wolf, "#creeper", target);
            var pet = payload(wolf);
            helper.assertTrue(owner.getUUID().equals(pet.ownerUuid()) && pet.ownerName() == null
                    && pet.detail() != null && pet.detail().startsWith("collar: "), "Wolf owner and collar captured: " + pet);
        }))
        .thenWaitUntil(lookup(helper, lookup, "r:global t:1h a:kill u:" + name, 2))
        .thenExecute(() -> {
            var rows = rows(lookup);
            helper.assertTrue(rows.stream().allMatch(row -> row.contains("#creeper (← " + name + ")"))
                    && rows.stream().anyMatch(row -> row.contains("killed sheep")) && rows.stream().anyMatch(row -> row.contains("killed wolf")),
                    "u:" + name + " a:kill lists both creeper kills: " + rows);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_retarget")
    public static void creeperThatRetargetsAMobNoLongerBlamesThePlayer(GameTestHelper helper) {
        var level = helper.getLevel(); var chased = player(helper, unique("Chased"));
        var sheep = helper.absolutePos(new BlockPos(3, 2, 4));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            fed(helper, player(helper, unique("Farmer")), spawn(helper, EntityType.SHEEP, new BlockPos(3, 2, 4), 1), Items.WHEAT);
            var creeper = EntityType.CREEPER.create(level);
            creeper.setTarget(chased);
            // Hit by a skeleton's arrow, it retaliates against the skeleton and blows up next to it.
            creeper.setTarget(EntityType.SKELETON.create(level));
            creeper.setSwellDir(1);
            var center = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 4)));
            creeper.setPos(center);
            level.explode(creeper, center.x, center.y, center.z, 3.0F, Level.ExplosionInteraction.NONE);
        }).thenWaitUntil(persisted(helper, kills(helper), events -> unrooted(helper, killedAt(helper, events, sheep), "#creeper")))
        .thenSucceed();
    }

    /** Lava kills are explained only by lava: an extinguished campfire beside it, placed by a player, claims nothing. */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_campfire")
    public static void campfireRootsOnlyItsOwnLitDamage(GameTestHelper helper) {
        var level = helper.getLevel(); var camper = player(helper, unique("Camper")); var farmer = player(helper, unique("Farmer"));
        var doused = helper.absolutePos(new BlockPos(2, 2, 2)); var lava = doused.east(); var lit = helper.absolutePos(new BlockPos(6, 2, 2));
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(doused.below().west(), lit.below().east()))
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            for (var campfire : List.of(doused, lit)) {
                camper.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CAMPFIRE));
                camper.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(camper, InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(campfire.below()).add(0, .5, 0), net.minecraft.core.Direction.UP,
                                campfire.below(), false)));
                helper.assertTrue(level.getBlockState(campfire).is(Blocks.CAMPFIRE), "Camper placed a lit campfire");
            }
            level.setBlockAndUpdate(doused, level.getBlockState(doused).setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            var straddling = fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(2, 2, 2), 20), Items.CARROT);
            straddling.moveTo(lava.getX(), lava.getY(), lava.getZ() + .5, 0, 0);
            var box = straddling.getBoundingBox();
            helper.assertTrue(box.minX < lava.getX() && box.maxX > lava.getX(), "Pig overlaps the doused campfire and the lava");
            straddling.hurt(level.damageSources().lava(), 100);
            fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(6, 2, 2), 20), Items.CARROT).hurt(level.damageSources().campfire(), 100);
            level.setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState());
        }).thenWaitUntil(persisted(helper, kills(helper), events -> {
            unrooted(helper, killedAt(helper, events, lava), "#lava");
            causedBy(helper, killedAt(helper, events, lit), "#campfire", camper);
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_lava")
    public static void pouredLavaAndTamedWolfKillsAreRootedAtTheirPlayer(GameTestHelper helper) {
        var level = helper.getLevel(); var griefer = player(helper, unique("Griefer"));
        var floor = helper.absolutePos(new BlockPos(2, 1, 2)); var lava = floor.above();
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            fed(helper, player(helper, unique("Farmer")), spawn(helper, EntityType.PIG, new BlockPos(2, 2, 2), 1), Items.CARROT);
            griefer.setPos(lava.getX() + .5, lava.getY() + 1, lava.getZ() + .5);
            griefer.setXRot(90);
            griefer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
            griefer.gameMode.useItem(griefer, level, griefer.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getFluidState(lava).is(net.minecraft.tags.FluidTags.LAVA), "Griefer poured lava onto the pig");
            var wolf = tamedBy(helper, new BlockPos(6, 2, 6), griefer);
            var sheep = fed(helper, player(helper, unique("Farmer")), spawn(helper, EntityType.SHEEP, new BlockPos(7, 2, 6), 1), Items.WHEAT);
            sheep.hurt(level.damageSources().mobAttack(wolf), 100);
        }).thenWaitUntil(persisted(helper, kills(helper), events -> {
            causedBy(helper, only(helper, events, "minecraft:pig"), "#lava", griefer);
            causedByOffline(helper, only(helper, events, "minecraft:sheep"), "#wolf", griefer);
        })).thenExecute(() -> level.setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState()))
        .thenSucceed();
    }

    private static BlockPos at(AuditEvent event) { return new BlockPos(event.envelope().x(), event.envelope().y(), event.envelope().z()); }
    private static AuditEvent killedAt(GameTestHelper helper, List<AuditEvent> events, BlockPos pos) {
        var matching = events.stream().filter(e -> new BlockPos(e.envelope().x(), e.envelope().y(), e.envelope().z()).equals(pos)).toList();
        helper.assertTrue(matching.size() == 1, "Expected one kill at " + pos.toShortString() + ", got " + matching.stream().map(AuditEvent::envelope).toList());
        return matching.getFirst();
    }
    private static void unrooted(GameTestHelper helper, AuditEvent event, String actor) {
        var envelope = event.envelope();
        helper.assertTrue(envelope.actorId().equals(actor) && envelope.rootActorUuid() == null, "Expected unrooted " + actor + ", got " + envelope);
    }
    private static void click(ServerPlayer player, BlockPos pos, ItemStack held) {
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        player.gameMode.useItemOn(player, player.serverLevel(), held, InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos).relative(net.minecraft.core.Direction.UP, .5),
                        net.minecraft.core.Direction.UP, pos, false));
    }

    /**
     * A burning episode keeps the root of the fire that lit it only until something else ignites the animal; flowing
     * lava takes the root of the sources that feed it, never of an attributed pool nearby that cannot reach it.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "chronicle_kill_burning")
    public static void burningAndFlowingLavaKeepOnlyTheirTrueRoot(GameTestHelper helper) {
        var level = helper.getLevel(); var griefer = player(helper, unique("Burner")); var archer = player(helper, unique("Archer"));
        var farmer = player(helper, unique("Farmer"));
        var relit = helper.absolutePos(new BlockPos(1, 2, 1)); var stillLit = helper.absolutePos(new BlockPos(1, 2, 4));
        var source = helper.absolutePos(new BlockPos(4, 2, 6)); var fed = source.east(); var wall = fed.south();
        var natural = helper.absolutePos(new BlockPos(5, 2, 8)); var naturalSource = natural.east(); var escaped = helper.absolutePos(new BlockPos(8, 2, 6));
        var pigs = new Object() { Mob relit, stillLit, fed, natural; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(8, 1, 9))))
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            pigs.relit = fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(1, 2, 1), 20), Items.CARROT);
            pigs.stillLit = fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(1, 2, 4), 20), Items.CARROT);
            for (var pig : List.of(pigs.relit, pigs.stillLit)) {
                click(griefer, pig.blockPosition().below(), new ItemStack(Items.FLINT_AND_STEEL));
                helper.assertTrue(level.getBlockState(pig.blockPosition()).is(Blocks.FIRE), "Griefer lit fire under the pig");
                pig.hurt(level.damageSources().inFire(), .5F);
                level.setBlockAndUpdate(pig.blockPosition(), Blocks.AIR.defaultBlockState());
            }
            // Walked into water: the griefer's fire no longer burns it.
            pigs.relit.clearFire();
        }).thenIdle(2).thenExecute(() -> {
            // A later Flame arrow starts a new burning episode; the griefer's earlier fire must not claim it.
            pigs.relit.igniteForSeconds(5);
            pigs.relit.hurt(level.damageSources().arrow(new Arrow(level, archer, new ItemStack(Items.ARROW), new ItemStack(Items.BOW)), archer), 2);
            pigs.relit.hurt(level.damageSources().onFire(), 100);
            pigs.stillLit.hurt(level.damageSources().onFire(), 100);

            griefer.setPos(source.getX() + .5, source.getY() + 1, source.getZ() + .5);
            griefer.setXRot(90);
            griefer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
            griefer.gameMode.useItem(griefer, level, griefer.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getFluidState(source).isSource(), "Griefer poured a lava source");
            var flowing = net.minecraft.world.level.material.Fluids.LAVA.getFlowing(6, false).createLegacyBlock();
            level.setBlockAndUpdate(fed, flowing);
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(naturalSource, Blocks.LAVA.defaultBlockState());
            level.setBlockAndUpdate(natural, flowing);
            pigs.fed = fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(5, 2, 6), 20), Items.CARROT);
            pigs.natural = fed(helper, farmer, spawn(helper, EntityType.PIG, new BlockPos(5, 2, 8), 20), Items.CARROT);
            // Touches the griefer's flowing lava, escapes it, and later burns to death.
            pigs.fed.hurt(level.damageSources().lava(), .5F);
            pigs.fed.teleportTo(escaped.getX() + .5, escaped.getY(), escaped.getZ() + .5);
            pigs.fed.hurt(level.damageSources().onFire(), 100);
            // Dies in natural flowing lava, two blocks and a wall from the griefer's source.
            pigs.natural.hurt(level.damageSources().lava(), 100);
            helper.assertTrue(List.of(pigs.relit, pigs.stillLit, pigs.fed, pigs.natural).stream().allMatch(Mob::isDeadOrDying), "All four pigs died");
            for (var pos : List.of(source, fed, natural, naturalSource)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }).thenWaitUntil(persisted(helper, kills(helper), events -> {
            var relitKill = killedAt(helper, events, relit);
            helper.assertTrue(relitKill.envelope().actorId().equals("#fire") && archer.getUUID().equals(relitKill.envelope().rootActorUuid()),
                    "Re-ignited pig credited to the archer, not the griefer: " + relitKill.envelope());
            causedBy(helper, killedAt(helper, events, stillLit), "#fire", griefer);
            causedBy(helper, killedAt(helper, events, escaped), "#fire", griefer);
            unrooted(helper, killedAt(helper, events, natural), "#lava");
        })).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "chronicle_kill_rollback")
    public static void rollbackRestoresBlocksAndIgnoresKills(GameTestHelper helper) {
        var level = helper.getLevel(); String name = unique("RbKill"); var player = player(helper, name);
        var dirt = helper.absolutePos(new BlockPos(1, 2, 1));
        Reply before = new Reply(), rollback = new Reply(), lookup = new Reply();
        String scope = " r:global t:1h u:" + name;
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            level.setBlockAndUpdate(dirt, Blocks.DIRT.defaultBlockState());
            helper.assertTrue(player.gameMode.destroyBlock(dirt), "Dirt broken");
            fed(helper, player(helper, unique("Farmer")), spawn(helper, EntityType.COW, new BlockPos(3, 2, 3), 1), Items.WHEAT).hurt(level.damageSources().playerAttack(player), 100);
        }).thenWaitUntil(persisted(helper, e -> name.equals(e.envelope().actorId()), events -> helper.assertTrue(
                events.stream().map(e -> e.envelope().eventType()).sorted().toList().equals(List.of("BLOCK_BREAK", "ENTITY_KILL")),
                "Break and kill persisted: " + events)))
        .thenWaitUntil(lookup(helper, before, scope.strip(), 2))
        .thenWaitUntil(command(helper, rollback, "chronicle rb" + scope, "Rollback complete"))
        .thenExecute(() -> {
            var lines = rollback.output.lines;
            helper.assertTrue(lines.stream().anyMatch(line -> line.contains("blocks=1")) && lines.stream().noneMatch(line -> line.contains("Skipped")),
                    "Only the block was rolled back, nothing skipped: " + lines);
            helper.assertTrue(level.getBlockState(dirt).is(Blocks.DIRT), "Dirt restored");
            helper.assertTrue(level.getEntitiesOfClass(Cow.class, helper.getBounds(), Cow::isAlive).isEmpty(), "The cow is not resurrected");
        })
        .thenWaitUntil(lookup(helper, lookup, scope.strip(), 2))
        .thenExecute(() -> {
            var rows = rows(lookup);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("broke dirt") && row.contains("[RB]")), "Break marked rolled back: " + rows);
            helper.assertTrue(rows.stream().anyMatch(row -> row.contains("killed cow") && !row.contains("[RB]")), "Kill left untouched: " + rows);
        }).thenSucceed();
    }
}
