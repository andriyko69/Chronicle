/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.inspect.InspectorSessions;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InspectorTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test void toggleExplicitStateDisconnectAndShutdown() {
        var sessions = new InspectorSessions();
        assertTrue(sessions.toggle(ALICE)); assertTrue(sessions.active(ALICE));
        assertFalse(sessions.toggle(ALICE)); assertFalse(sessions.active(ALICE));
        assertTrue(sessions.set(ALICE, true)); assertTrue(sessions.set(ALICE, true)); assertTrue(sessions.active(ALICE));
        assertFalse(sessions.set(BOB, false)); assertFalse(sessions.active(BOB));
        sessions.set(BOB, true); assertEquals(2, sessions.size());
        sessions.remove(ALICE); assertFalse(sessions.active(ALICE)); assertTrue(sessions.active(BOB));
        sessions.clear(); assertEquals(0, sessions.size()); assertFalse(sessions.tryLookup(BOB));
    }

    @Test void cooldownDebouncesPerPlayerAndIsResetByReenable() {
        var nanos = new AtomicLong(Long.MAX_VALUE - 50_000_000);
        var sessions = new InspectorSessions(nanos::get, Duration.ofMillis(200), Duration.ofSeconds(1));
        assertFalse(sessions.tryLookup(ALICE), "Inactive players never query");
        sessions.set(ALICE, true); sessions.set(BOB, true);
        assertTrue(sessions.tryLookup(ALICE)); assertTrue(sessions.tryLookup(BOB));
        nanos.addAndGet(199_999_999); assertFalse(sessions.tryLookup(ALICE));
        // Monotonic arithmetic must survive System.nanoTime overflow.
        nanos.addAndGet(1); assertTrue(sessions.tryLookup(ALICE));
        assertFalse(sessions.tryLookup(ALICE));
        sessions.set(ALICE, true); assertFalse(sessions.tryLookup(ALICE), "Re-enabling an active session keeps its cooldown");
        sessions.set(ALICE, false); sessions.set(ALICE, true); assertTrue(sessions.tryLookup(ALICE));
    }

    @Test void revocationStopsLookupsButKeepsSuppressingThroughTheGrace() {
        var nanos = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        var sessions = new InspectorSessions(nanos::get, Duration.ofMillis(200), Duration.ofSeconds(1));
        assertFalse(sessions.revoke(ALICE), "Nothing to revoke"); assertFalse(sessions.suppressing(ALICE));
        sessions.set(ALICE, true); assertTrue(sessions.suppressing(ALICE));
        assertTrue(sessions.revoke(ALICE)); assertFalse(sessions.revoke(ALICE), "Notify only on the first revocation");
        assertFalse(sessions.active(ALICE)); assertFalse(sessions.tryLookup(ALICE));
        // Follow-up packets of the same gesture (off hand, bucket use) must still be canceled.
        nanos.addAndGet(999_999_999L); assertTrue(sessions.suppressing(ALICE));
        nanos.addAndGet(1); assertFalse(sessions.suppressing(ALICE)); assertEquals(0, sessions.size());
        sessions.set(BOB, true); sessions.revoke(BOB);
        assertTrue(sessions.toggle(BOB), "A revoked session toggles to a fresh active one");
        assertTrue(sessions.active(BOB) && sessions.tryLookup(BOB));
        sessions.revoke(BOB); sessions.remove(BOB); assertFalse(sessions.suppressing(BOB), "Logout ends suppression");
    }

    @Test void concurrentTogglesAndLookupsStayConsistent() throws Exception {
        var sessions = new InspectorSessions(() -> 0, Duration.ofMillis(200), Duration.ofSeconds(1));
        var players = new ArrayList<UUID>(); for (int i = 0; i < 64; i++) players.add(UUID.randomUUID());
        players.forEach(player -> sessions.set(player, true));
        var granted = new ConcurrentLinkedQueue<UUID>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 8; thread++)
                jobs.add(pool.submit(() -> players.forEach(player -> { if (sessions.tryLookup(player)) granted.add(player); })));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertEquals(64, granted.size()); assertEquals(Set.copyOf(players), Set.copyOf(granted));
    }

    @Test void exactSpecIsAllTimeRadiusZeroAllActions() {
        var spec = LookupSpec.exact("minecraft:overworld", new LookupSpec.Center(10, -64, Integer.MAX_VALUE));
        assertEquals(Instant.EPOCH, spec.since());
        assertEquals(new LookupSpec.Bounds(10, 10, -64, -64, Integer.MAX_VALUE, Integer.MAX_VALUE), spec.bounds());
        assertEquals(List.of("BLOCK_BREAK", "BLOCK_PLACE", "ITEM_TAKE", "ITEM_PUT"), spec.actions());
        assertTrue(spec.actors().isEmpty() && spec.targets().isEmpty());
        assertThrows(NullPointerException.class, () -> LookupSpec.exact(null, new LookupSpec.Center(0, 0, 0)));
        var sql = LookupSql.page(spec, 2);
        assertTrue(sql.sql().contains("world_id = ? AND x = ? AND y = ? AND z = ?") && !sql.sql().contains("BETWEEN"));
        assertEquals(List.of(0L, 0L, 0, "minecraft:overworld", 10, -64, Integer.MAX_VALUE,
                "BLOCK_BREAK", "BLOCK_PLACE", "ITEM_TAKE", "ITEM_PUT", LookupSpec.PAGE_SIZE + 1, LookupSpec.PAGE_SIZE), sql.parameters());
        var sessions = new LookupSessions(CLOCK);
        sessions.put(ALICE.toString(), spec);
        assertEquals(spec, sessions.get(ALICE.toString()).orElseThrow(), "Inspector lookups page like /ch lookup");
    }

    @Test void realStorageReturnsOnlyTheExactCoordinateAcrossAllTimeAndPages() throws Exception {
        var events = new ArrayList<AuditEvent>();
        for (int i = 0; i < 10; i++) events.add(block(i, "minecraft:overworld", 5, 70, -5, NOW.minus(Duration.ofDays(400 + i))));
        events.add(item("ITEM_PUT", 5, 70, -5, NOW.minusSeconds(1)));
        events.add(item("ITEM_TAKE", 5, 70, -5, NOW.minusSeconds(2)));
        var expected = events.stream().map(AuditEvent::id).collect(java.util.stream.Collectors.toSet());
        events.add(block(20, "minecraft:overworld", 6, 70, -5, NOW));
        events.add(block(21, "minecraft:overworld", 5, 71, -5, NOW));
        events.add(block(22, "minecraft:the_nether", 5, 70, -5, NOW));
        var spec = LookupSpec.exact("minecraft:overworld", new LookupSpec.Center(5, 70, -5));
        try (var runtime = new ChronicleRuntime(InfrastructureConfig.defaults(root), CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (var event : events) PersistenceTest.submit(runtime, event);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == events.size());
            var first = runtime.lookup(spec, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var second = runtime.lookup(spec, 2).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(8, first.rows().size()); assertTrue(first.hasMore());
            assertEquals(4, second.rows().size()); assertFalse(second.hasMore());
            var found = new HashSet<EventId>();
            first.rows().forEach(row -> found.add(row.id())); second.rows().forEach(row -> found.add(row.id()));
            assertEquals(expected, found);
            assertEquals("ITEM_PUT", first.rows().getFirst().envelope().eventType(), "Newest evidence first");
        }
    }

    @Test void exactCoordinateSeeksThePositionIndexNewestFirstOverALargeHistory() throws Exception {
        var database = root.resolve("plan.db");
        try (var connection = PersistenceTest.connection(database)) {
            io.github.andriyko69.chronicle.storage.migration.MigrationManager.migrate(connection, NOW);
            connection.setAutoCommit(false);
            var random = new Random(5);
            try (var insert = connection.prepareStatement("INSERT INTO audit_events VALUES (?,?,0,'minecraft:overworld',?,?,?,'BLOCK_BREAK','PLAYER','Alice',NULL,NULL,'minecraft:stone',1,X'00')")) {
                for (int i = 0; i < 200_000; i++) {
                    var id = new byte[16]; random.nextBytes(id);
                    insert.setBytes(1, id); insert.setLong(2, NOW.getEpochSecond() - i);
                    insert.setInt(3, random.nextInt(2000) - 1000); insert.setInt(4, random.nextInt(128)); insert.setInt(5, random.nextInt(2000) - 1000);
                    insert.addBatch();
                    if (i % 10_000 == 0) insert.executeBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            var sql = LookupSql.page(LookupSpec.exact("minecraft:overworld", new LookupSpec.Center(5000, 64, 5000)), 1);
            String plan = plan(connection, sql);
            assertTrue(plan.contains("idx_events_position_time (world_id=? AND x=? AND z=? AND y=?") && !plan.contains("TEMP B-TREE"), plan);
            try (var statement = connection.createStatement()) { statement.execute("ANALYZE"); }
            connection.commit();
            plan = plan(connection, sql);
            assertTrue(plan.contains("idx_events_position_time (world_id=? AND x=? AND z=? AND y=?") && !plan.contains("TEMP B-TREE"), "After ANALYZE: " + plan);
            try (var query = connection.prepareStatement(sql.sql())) {
                for (int i = 0; i < sql.parameters().size(); i++) query.setObject(i + 1, sql.parameters().get(i));
                try (var rows = query.executeQuery()) { assertFalse(rows.next(), "Empty coordinate"); }
            }
        }
    }

    private static String plan(java.sql.Connection connection, LookupSql sql) throws java.sql.SQLException {
        try (var explain = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql.sql())) {
            for (int i = 0; i < sql.parameters().size(); i++) explain.setObject(i + 1, sql.parameters().get(i));
            var detail = new StringBuilder();
            try (var rows = explain.executeQuery()) { while (rows.next()) detail.append(rows.getString("detail")).append('\n'); }
            return detail.toString();
        }
    }

    private static AuditEvent block(int i, String world, int x, int y, int z, Instant at) {
        var envelope = new EventEnvelope(world, x, y, z, i % 2 == 0 ? "BLOCK_BREAK" : "BLOCK_PLACE", "PLAYER", "Alice", ALICE, null, "minecraft:stone");
        var stone = new BlockStateSnapshot("minecraft:stone", Map.of());
        return AuditEvent.block(EventId.generate(), at, envelope, new BlockDeltaPayload(i % 2 == 0 ? stone : BlockStateSnapshot.AIR,
                i % 2 == 0 ? BlockStateSnapshot.AIR : stone, BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
    }

    private static AuditEvent item(String type, int x, int y, int z, Instant at) {
        var envelope = new EventEnvelope("minecraft:overworld", x, y, z, type, "PLAYER", "Alice", ALICE, null, "minecraft:diamond");
        return AuditEvent.item(EventId.generate(), at, envelope, new ItemTransactionPayload(16, "minecraft:chest", null, new TreeMap<>(), null,
                ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
    }
}
