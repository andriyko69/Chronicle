/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.config.ChronicleServerConfig;
import io.github.andriyko69.chronicle.config.ChronicleServerConfigSpec;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.ingest.Submission;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/**
 * {@code /ch purge} and {@code chronicle-server.toml} overrides. Purges run through {@link ChronicleTestMod#commands}
 * against the per-run test runtime: they delete rows, and the production database persists across runs.
 */
@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class MaintenanceGameTests {
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
        boolean has(String text) { return lines.stream().anyMatch(line -> line.contains(text)); }
    }
    private static Output execute(MinecraftServer server, String command) {
        var output = new Output(server);
        var source = server.createCommandSourceStack().withPermission(2).withSource(output);
        try { ChronicleTestMod.commands.execute(command, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
        return output;
    }
    private static final class Reply { Output output; }
    /** Runs a test-runtime command once, until a reply contains {@code until}; unsettled storage is retried. */
    private static Runnable command(GameTestHelper helper, Reply reply, String command, String until) {
        return () -> {
            if (reply.output == null) reply.output = execute(helper.getLevel().getServer(), command);
            if (reply.output.has("try again")) { var lines = reply.output.lines; reply.output = null; helper.fail("Retrying /" + command + ": " + lines); }
            helper.assertTrue(reply.output.has(until), "Waiting for '" + until + "' from /" + command + ": " + reply.output.lines);
        };
    }
    private static Runnable purgeable(GameTestHelper helper, Duration age, long expected) {
        var query = new Object() { CompletableFuture<Long> pending; };
        return () -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.purgeable(Instant.now().minus(age)).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            long found = query.pending.join();
            query.pending = null;
            helper.assertTrue(found == expected, "Expected " + expected + " purgeable rows, found " + found);
        };
    }
    /** A placement recorded long before any other row in this run's test database. */
    private static AuditEvent ancient(int x, String actor, UUID uuid) {
        var stone = new BlockStateSnapshot("minecraft:stone", Map.of());
        return AuditEvent.block(EventId.generate(), Instant.now().minus(Duration.ofDays(40)).plusMillis(x),
                new EventEnvelope("minecraft:overworld", x, -60, 0, "BLOCK_PLACE", "PLAYER", actor, uuid, null, null, "minecraft:stone"),
                new BlockDeltaPayload(BlockStateSnapshot.AIR, stone, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "chronicle_purge")
    public static void purgePreviewsThenDeletesOnlyOldRowsAndNeverOverlapsARollback(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer();
        String name = unique("Purger"); var breaker = player(helper, name);
        var target = helper.absolutePos(new BlockPos(2, 1, 2));
        Reply preview = new Reply(), rollback = new Reply();
        var running = new Object() { Output purge, rollbackDuringPurge; };
        var thirtyDays = Duration.ofDays(30);
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            String ancient = unique("Ancient"); var ancientId = UUID.randomUUID();
            for (int i = 0; i < 3; i++)
                helper.assertTrue(ChronicleTestMod.runtime.submit(ancient(i, ancient, ancientId)).status() == Submission.Status.ACCEPTED, "Seeded old row");
            level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
            helper.assertTrue(breaker.gameMode.destroyBlock(target), "Stone broken");

            helper.assertTrue(ChronicleTestMod.rollbacks.reserve(server), "Rollback slot free");
            var duringRollback = execute(server, "ch purge t:30d");
            ChronicleTestMod.rollbacks.release(server);
            helper.assertTrue(duringRollback.has("rollback or restore is already running; try again"), "Purge refused during a rollback: " + duringRollback.lines);
            var token = ChronicleTestMod.rollbacks.reservePurge(server);
            helper.assertTrue(token != null, "Slot free for a purge");
            var rollbackDuringPurge = execute(server, "ch rb r:global t:5m u:" + name);
            var restoreDuringPurge = execute(server, "ch rs r:global t:5m u:" + name);
            ChronicleTestMod.rollbacks.releasePurge(server, token);
            helper.assertTrue(rollbackDuringPurge.has("purge is already running; try again") && restoreDuringPurge.has("purge is already running; try again"),
                    "Rollback and restore refused during a purge: " + rollbackDuringPurge.lines + restoreDuringPurge.lines);
            helper.assertTrue(execute(server, "ch purge t:30s").has("at least 60s"), "Minimum age enforced");
            helper.assertTrue(execute(server, "ch purge --preview").has("explicit age"), "Age required");
            helper.assertTrue(execute(server, "ch purge t:30d r:global").has("Unsupported purge argument"), "Filters refused");
        })
        .thenWaitUntil(command(helper, preview, "ch purge t:30d --preview", "purge preview"))
        .thenExecute(() -> helper.assertTrue(preview.output.has("3 events older than 30d would be purged; nothing was deleted"), "Preview " + preview.output.lines))
        .thenWaitUntil(purgeable(helper, thirtyDays, 3))
        .thenExecute(() -> {
            running.purge = execute(server, "ch purge t:30d --optimize");
            running.rollbackDuringPurge = execute(server, "ch rb r:global t:5m u:" + name);
        })
        .thenWaitUntil(() -> helper.assertTrue(running.purge.has("purge complete") || running.purge.has("purge stopped"), "Waiting for purge: " + running.purge.lines))
        .thenExecute(() -> {
            helper.assertTrue(running.purge.has("Purging events older than 30d"), "Start line " + running.purge.lines);
            helper.assertTrue(running.purge.has("purge complete") && running.purge.has("events=3") && running.purge.has("Database optimized"),
                    "Purge report " + running.purge.lines);
            helper.assertTrue(running.rollbackDuringPurge.has("purge is already running"), "Live purge excluded a rollback: " + running.rollbackDuringPurge.lines);
        })
        .thenWaitUntil(purgeable(helper, thirtyDays, 0))
        .thenWaitUntil(command(helper, rollback, "ch rb r:global t:5m u:" + name, "Rollback complete"))
        .thenExecute(() -> helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "A surviving recent break still rolls back"))
        .thenSucceed();
    }

    /** Unlike GameTestHelper.spawn, which makes every mob persistent, this leaves only the feeding to mark the animal. */
    private static <T extends Animal> T fed(GameTestHelper helper, ServerPlayer farmer, EntityType<T> type, BlockPos relative) {
        var animal = type.create(helper.getLevel());
        var at = Vec3.atBottomCenterOf(helper.absolutePos(relative));
        animal.moveTo(at.x, at.y, at.z, 0, 0);
        animal.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(animal), "Spawned " + type);
        animal.setHealth(1);
        farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        helper.assertTrue(farmer.interactOn(animal, InteractionHand.MAIN_HAND).consumesAction(), "Fed " + type);
        return animal;
    }

    @GameTest(template = "empty", timeoutTicks = 2400, batch = "chronicle_config_kills")
    public static void ignoredEntityTypesAreNotAudited(GameTestHelper helper) {
        var level = helper.getLevel();
        var farmer = player(helper, unique("Farmer")); var killer = player(helper, unique("Butcher"));
        Predicate<AuditEvent> kills = e -> !e.legacy() && e.envelope().eventType().equals("ENTITY_KILL")
                && helper.getBounds().inflate(2).contains(e.envelope().x() + .5, e.envelope().y() + .5, e.envelope().z() + .5);
        var query = new Object() { CompletableFuture<List<AuditEvent>> pending; };
        helper.startSequence().thenWaitUntil(() -> ready(helper)).thenExecute(() -> {
            ChronicleServerConfigSpec.ENTITY_KILL_IGNORED_TYPES.set(List.of("minecraft:cow"));
            ChronicleServerConfigSpec.bake();
            try {
                helper.assertTrue(ChronicleServerConfig.current().ignoresEntityKill("minecraft:cow"), "Override applied");
                Mob cow = fed(helper, farmer, EntityType.COW, new BlockPos(1, 2, 1));
                Mob sheep = fed(helper, farmer, EntityType.SHEEP, new BlockPos(4, 2, 1));
                // Admission is FIFO: once the sheep's row is stored, a cow row would be stored too.
                cow.hurt(level.damageSources().playerAttack(killer), 100);
                sheep.hurt(level.damageSources().playerAttack(killer), 100);
                helper.assertTrue(cow.isDeadOrDying() && sheep.isDeadOrDying(), "Both died");
            } finally {
                ChronicleServerConfigSpec.ENTITY_KILL_IGNORED_TYPES.set(List.of());
                ChronicleServerConfigSpec.bake();
            }
            helper.assertTrue(ChronicleServerConfig.current().entityKillIgnoredTypes().isEmpty(), "Default restored");
        }).thenWaitUntil(() -> {
            if (query.pending == null) query.pending = ChronicleTestMod.runtime.recent(1000).toCompletableFuture();
            helper.assertTrue(query.pending.isDone(), "Waiting for query worker");
            var found = query.pending.join().stream().filter(kills).map(e -> e.envelope().targetId()).toList();
            query.pending = null;
            helper.assertTrue(found.contains("minecraft:sheep"), "Waiting for the sheep's row: " + found);
            helper.assertTrue(found.equals(List.of("minecraft:sheep")), "The ignored cow left no row: " + found);
        }).thenSucceed();
    }
}
