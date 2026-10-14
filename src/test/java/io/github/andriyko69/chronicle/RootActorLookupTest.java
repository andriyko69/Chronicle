/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.migration.MigrationManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RootActorLookupTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CAROL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final List<String> NAMES = List.of("V001__infrastructure.sql", "V002__audit_events.sql", "V003__lookup_indexes.sql",
            "V004__position_index.sql");

    private static AuditEvent event(int i, String actorType, String actorId, UUID actorUuid, UUID rootUuid, String rootName) {
        var envelope = new EventEnvelope("minecraft:overworld", i, 64, 0, "BLOCK_BREAK", actorType, actorId, actorUuid, rootUuid, rootName, "minecraft:stone");
        return AuditEvent.block(new EventId(new UUID(0x12347000, 0x8000000000000000L | i)), NOW.minusSeconds(i), envelope,
                new BlockDeltaPayload(new BlockStateSnapshot("minecraft:stone", Map.of()), BlockStateSnapshot.AIR,
                        BlockDeltaPayload.Observation.HOOK_OBSERVATION, BlockDeltaPayload.SnapshotStatus.NONE, null));
    }
    private static Set<Integer> xs(LookupPage page) { return new HashSet<>(page.rows().stream().map(row -> row.envelope().x()).toList()); }
    private static LookupPage lookup(ChronicleRuntime runtime, String query) throws Exception {
        return runtime.lookup(new LookupParser(CLOCK).parse(query, LookupParser.Context.console()), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test void userFilterMatchesDirectAndRootCausedRowsByNameAndUuid() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var events = List.of(
                event(1, "PLAYER", "Alice", ALICE, null, null),
                event(2, "ENVIRONMENT", "#tnt", null, ALICE, "Alice"),
                event(3, "ENVIRONMENT", "#fire", null, ALICE, "Alice"),
                event(4, "PLAYER", "Bob", BOB, null, null),
                event(5, "ENVIRONMENT", "#creeper", null, BOB, "Bob"),
                event(6, "ENVIRONMENT", "#explosion", null, null, null),
                // Carol never acted directly; her name is known only from the root actor envelope.
                event(7, "ENVIRONMENT", "#end_crystal", null, CAROL, "Carol"));
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (var event : events) PersistenceTest.submit(runtime, event);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == events.size());
            assertEquals(Set.of(1, 2, 3), xs(lookup(runtime, "r:global t:1h u:Alice")));
            assertEquals(Set.of(1, 2, 3), xs(lookup(runtime, "r:global t:1h u:" + ALICE)));
            assertEquals(Set.of(4, 5), xs(lookup(runtime, "r:global t:1h u:Bob")));
            assertEquals(Set.of(7), xs(lookup(runtime, "r:global t:1h u:Carol")));
            assertEquals(Set.of(1, 2, 3, 7), xs(lookup(runtime, "r:global t:1h u:Alice," + CAROL)));
            var tnt = lookup(runtime, "r:global t:1h u:Alice").rows().stream().filter(row -> row.envelope().x() == 2).findFirst().orElseThrow();
            assertEquals("#tnt", tnt.envelope().actorId());
            assertEquals(ALICE, tnt.envelope().rootActorUuid());
            assertEquals("Alice", tnt.envelope().rootActorId());
            assertTrue(lookup(runtime, "r:global t:1h u:Nobody").rows().isEmpty());
        }
        assertEquals(3, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM actor_profiles"));
        assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM actor_profiles WHERE actor_id='Carol'"));
        assertEquals(4, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events WHERE root_actor_uuid IS NOT NULL"));
    }

    @Test void rootActorFiltersAreIndexDrivenNotTableScans() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) { runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        for (String user : List.of("Alice", ALICE.toString())) {
            var sql = LookupSql.page(new LookupParser(CLOCK).parse("r:global t:1h u:" + user, LookupParser.Context.console()), 1);
            var plan = new ArrayList<String>();
            try (var connection = PersistenceTest.connection(config.database());
                    var explain = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql.sql())) {
                for (int i = 0; i < sql.parameters().size(); i++) {
                    Object value = sql.parameters().get(i);
                    if (value instanceof UUID id) explain.setBytes(i + 1, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
                    else explain.setObject(i + 1, value);
                }
                try (var rows = explain.executeQuery()) { while (rows.next()) plan.add(rows.getString("detail")); }
            }
            assertTrue(plan.stream().anyMatch(step -> step.contains("idx_events_root_actor_time")), user + " plan: " + plan);
            assertTrue(plan.stream().noneMatch(step -> step.startsWith("SCAN audit_events")), user + " plan: " + plan);
        }
    }

    @Test void v004UpgradesToV005BackfillingProfilesAndKeepingHistoricalChecksums() throws Exception {
        Path database = root.resolve("v004.db");
        var checksums = new ArrayList<String>();
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,name TEXT NOT NULL,checksum TEXT NOT NULL,applied_at TEXT NOT NULL) STRICT");
            for (int i = 0; i < NAMES.size(); i++) {
                byte[] bytes;
                try (var input = MigrationManager.class.getResourceAsStream(NAMES.get(i))) { bytes = input.readAllBytes(); }
                for (String sql : new String(bytes, StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) statement.execute(sql);
                checksums.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?,?,?,'baseline')")) {
                    insert.setInt(1, i + 1); insert.setString(2, NAMES.get(i)); insert.setString(3, checksums.getLast()); insert.executeUpdate();
                }
            }
            statement.execute("PRAGMA application_id=1128813135"); statement.execute("PRAGMA user_version=4");
            var payload = event(1, "PLAYER", "x", null, null, null).payload();
            String insert = "INSERT INTO audit_events VALUES (?,1,2,'minecraft:overworld',1,2,3,'BLOCK_BREAK',?,?,?,NULL,'minecraft:stone',1,?)";
            Object[][] rows = {{"PLAYER", "Alice", ALICE}, {"PLAYER", "Alice", ALICE}, {"FAKE_PLAYER", "[Bot]", BOB}, {"ENVIRONMENT", "#tnt", null}};
            for (int i = 0; i < rows.length; i++) {
                try (var statementRow = connection.prepareStatement(insert)) {
                    statementRow.setBytes(1, ByteBuffer.allocate(16).putLong(0x12347000).putLong(0x8000000000000000L | i).array());
                    statementRow.setString(2, (String)rows[i][0]); statementRow.setString(3, (String)rows[i][1]);
                    var uuid = (UUID)rows[i][2];
                    statementRow.setBytes(4, uuid == null ? null : ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
                    statementRow.setBytes(5, payload); statementRow.executeUpdate();
                }
            }
        }
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
        assertEquals(5, PersistenceTest.scalar(database, "PRAGMA user_version"));
        assertEquals(4, PersistenceTest.scalar(database, "SELECT count(*) FROM audit_events"));
        assertEquals(4, PersistenceTest.scalar(database, "SELECT count(*) FROM schema_migrations WHERE applied_at='baseline'"));
        try (var connection = PersistenceTest.connection(database); var rows = connection.createStatement().executeQuery("SELECT checksum FROM schema_migrations WHERE version <= 4 ORDER BY version")) {
            for (String checksum : checksums) { assertTrue(rows.next()); assertEquals(checksum, rows.getString(1)); }
        }
        assertEquals(2, PersistenceTest.scalar(database, "SELECT count(*) FROM actor_profiles"), "One profile per player identity, none for environment");
        assertEquals(2, PersistenceTest.scalar(database, "SELECT count(*) FROM sqlite_master WHERE name IN ('idx_events_root_actor_time','idx_events_actor_uuid_time')"));
        assertEquals(1, PersistenceTest.scalar(database, "SELECT count(*) FROM pragma_table_info('audit_events') WHERE name='root_actor_id'"));
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
        assertEquals(5, PersistenceTest.scalar(database, "SELECT count(*) FROM schema_migrations"));
    }
}
