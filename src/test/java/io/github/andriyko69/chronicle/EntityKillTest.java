/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EntityKillTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.now();
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final EntityKillPayloadCodec codec = new EntityKillPayloadCodec();

    static AuditEvent kill(int x, String actorType, String actorId, UUID actor, UUID rootUuid, String rootName, EntityKillPayload payload) {
        var envelope = new EventEnvelope("minecraft:overworld", x, 64, 0, "ENTITY_KILL", actorType, actorId, actor, rootUuid, rootName,
                payload.entityTypeId());
        return AuditEvent.kill(EventId.generate(), NOW.minusSeconds(x), envelope, payload);
    }
    private static LookupPage lookup(ChronicleRuntime runtime, String query) throws Exception {
        return runtime.lookup(new LookupParser(CLOCK).parse(query, LookupParser.Context.console()), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    private static Set<Integer> xs(LookupPage page) { return new HashSet<>(page.rows().stream().map(row -> row.envelope().x()).toList()); }

    @Test void payloadsRoundTripThroughCodecAndJournalWithEveryOptionalField() throws Exception {
        var full = new EntityKillPayload("minecraft:wolf", "Barnaby", BOB, "Bob", "collar: red");
        var bare = new EntityKillPayload("minecraft:cow", null, null, null, null);
        var offlineOwner = new EntityKillPayload("mod:pet", "Ünïcødé 🐺", ALICE, null, "librarian (Lv.5)");
        for (var payload : List.of(full, bare, offlineOwner)) {
            byte[] bytes = codec.encode(payload);
            assertEquals(EntityKillPayload.VERSION, ByteBuffer.wrap(bytes).getInt(0));
            assertEquals(payload, codec.decode(EntityKillPayload.VERSION, bytes));
            var event = kill(1, "ENVIRONMENT", "#creeper", null, ALICE, "Alice", payload);
            var journal = new ChronicleEventCodec();
            assertEquals(event, journal.decode(journal.encode(event)));
        }
        assertTrue(codec.encode(bare).length < 32, "An unnamed kill stays compact");
    }

    @Test void truncatedTrailingAndUnsupportedPayloadsAreRejected() {
        byte[] bytes = codec.encode(new EntityKillPayload("minecraft:wolf", "Barnaby", BOB, "Bob", "collar: red"));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(1, truncated), "Truncated at " + length);
        }
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, trailing));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(2, bytes));
        byte[] badPresence = codec.encode(new EntityKillPayload("minecraft:cow", null, null, null, null));
        badPresence[4 + 4 + "minecraft:cow".length()] = 2;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(1, badPresence));
        var envelope = new EventEnvelope("minecraft:overworld", 0, 64, 0, "ENTITY_KILL", "PLAYER", "Alice", ALICE, null, "minecraft:cow");
        assertThrows(IllegalArgumentException.class, () -> new AuditEvent(EventId.generate(), NOW, envelope, 1, trailing));
        assertThrows(IllegalArgumentException.class, () -> AuditEvent.kill(EventId.generate(), NOW,
                new EventEnvelope("minecraft:overworld", 0, 64, 0, "BLOCK_BREAK", "PLAYER", "Alice", ALICE, null, "minecraft:cow"),
                new EntityKillPayload("minecraft:cow", null, null, null, null)));
    }

    @Test void invalidFieldsAreRejectedButLiveTextIsClippedToBounds() {
        assertThrows(IllegalArgumentException.class, () -> new EntityKillPayload("Cow", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EntityKillPayload("minecraft:cow", "", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EntityKillPayload("minecraft:cow", "a\0b", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EntityKillPayload("minecraft:wolf", null, null, "Bob", null), "Name without UUID");
        assertThrows(IllegalArgumentException.class, () -> new EntityKillPayload("minecraft:cow", "x".repeat(EntityKillPayload.MAX_NAME_BYTES + 1), null, null, null));

        var clipped = EntityKillPayload.clipped("minecraft:cow", "é".repeat(EntityKillPayload.MAX_NAME_BYTES), ALICE, "Alice",
                "\0" + "d".repeat(EntityKillPayload.MAX_DETAIL_BYTES + 10));
        assertEquals(EntityKillPayload.MAX_NAME_BYTES, clipped.customName().getBytes(StandardCharsets.UTF_8).length, "Clipped on a code point boundary");
        assertEquals("é".repeat(EntityKillPayload.MAX_NAME_BYTES / 2), clipped.customName());
        assertEquals("d".repeat(EntityKillPayload.MAX_DETAIL_BYTES), clipped.detail(), "NUL dropped, then clipped");
        assertEquals("�ok", EntityKillPayload.clipped("minecraft:cow", "\uD800ok", null, null, null).customName(), "Lone surrogates replaced");
        assertNull(EntityKillPayload.clipped("minecraft:cow", "\0", null, null, null).customName(), "Nothing left means no name");
        assertEquals(clipped, codec.decode(1, codec.encode(clipped)));
    }

    @Test void killActionAliasesParseAndAreLookupDefaults() {
        var parser = new LookupParser(CLOCK);
        var context = new LookupParser.Context("minecraft:overworld", new LookupSpec.Center(0, 64, 0));
        for (String alias : List.of("kill", "killed", "entity_kill"))
            assertEquals(List.of("ENTITY_KILL"), parser.parse("a:" + alias, context).actions());
        assertTrue(parser.parse("", context).actions().contains("ENTITY_KILL"));
        assertTrue(LookupSpec.exact("minecraft:overworld", new LookupSpec.Center(0, 64, 0)).actions().contains("ENTITY_KILL"),
                "Inspecting the space where an animal died shows its kill");
        assertEquals(List.of("minecraft:cow", "minecraft:villager"), parser.parse("b:cow,minecraft:villager a:kill", context).targets());
        var thrown = assertThrows(IllegalArgumentException.class, () -> parser.parse("a:murder", context));
        assertTrue(thrown.getMessage().contains("kill"));
    }

    @Test void killRowsAreFoundByActionEntityAndRootActorAndDecodeTheirPayload() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var cow = new EntityKillPayload("minecraft:cow", null, null, null, null);
        var events = List.of(
                kill(1, "PLAYER", "Alice", ALICE, null, null, cow),
                kill(2, "ENVIRONMENT", "#creeper", null, ALICE, "Alice", new EntityKillPayload("minecraft:sheep", null, null, null, null)),
                kill(3, "ENVIRONMENT", "#wolf", null, BOB, "Bob", new EntityKillPayload("minecraft:villager", "Master Trader", null, null, "librarian (Lv.5)")),
                RollbackStorageTest.block(4, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(4), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE));
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (var event : events) PersistenceTest.submit(runtime, event);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == events.size());
            assertEquals(Set.of(1, 2, 3), xs(lookup(runtime, "r:global t:1h a:kill")));
            assertEquals(Set.of(1, 2), xs(lookup(runtime, "r:global t:1h u:Alice a:kill")));
            assertEquals(Set.of(1, 2, 4), xs(lookup(runtime, "r:global t:1h u:Alice")), "Kills join a user's default history");
            assertEquals(Set.of(1), xs(lookup(runtime, "r:global b:cow")));
            assertEquals(Set.of(3), xs(lookup(runtime, "r:global b:minecraft:villager a:killed")));
            var villager = lookup(runtime, "r:global b:villager").rows().getFirst();
            assertEquals("Master Trader", villager.kill().customName());
            assertEquals("librarian (Lv.5)", villager.kill().detail());
            assertNull(villager.item()); assertNull(villager.before());
            assertEquals(Set.of(1), xs(lookup(runtime, "at:1,64,0 dim:minecraft:overworld r:0")));
        }
    }

    @Test void rollbackAndRestoreNeverSelectKillsButStillPlanBlocks() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var killed = kill(1, "PLAYER", "Alice", ALICE, null, null, new EntityKillPayload("minecraft:cow", null, null, null, null));
        var broken = RollbackStorageTest.block(2, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(2), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.submit(runtime, killed); PersistenceTest.submit(runtime, broken);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 2);
            var parser = new LookupParser(CLOCK);
            var plan = runtime.planRollback(parser.parseRollback("r:global u:Alice", LookupParser.Context.console()).spec(), false)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(List.of(broken.id()), plan.steps().stream().map(step -> step.event().id()).toList());
            assertEquals(1, plan.blockChanges()); assertEquals(0, plan.itemChanges()); assertEquals(0, plan.quarantined());
            var onlyKills = runtime.planRollback(parser.parseRollback("r:global u:Alice a:kill", LookupParser.Context.console()).spec(), false)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(onlyKills.steps().isEmpty() && onlyKills.quarantined() == 0, "A kill-only rollback has nothing to do");
            runtime.setRolledBack(List.of(broken.id()), true).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var restore = runtime.planRollback(parser.parseRollback("r:global u:Alice", LookupParser.Context.console()).spec(), true)
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(List.of(broken.id()), restore.steps().stream().map(step -> step.event().id()).toList());
        }
        assertEquals(0, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events WHERE event_type='ENTITY_KILL' AND rolled_back=1"));
    }
}
