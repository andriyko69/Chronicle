/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.migration.MigrationManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DomainPersistenceTest {
    @TempDir Path root;
    private static byte[] uuid(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private void baseline(Path database, AuditEvent legacy) throws Exception {
        byte[] sql;
        try (var input = MigrationManager.class.getResourceAsStream("V001__infrastructure.sql")) { sql = input.readAllBytes(); }
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TEXT NOT NULL) STRICT");
            for (String command : new String(sql, StandardCharsets.UTF_8).split(";")) if (!command.isBlank()) statement.execute(command);
            try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (1, 'V001__infrastructure.sql', ?, 'baseline')")) {
                insert.setString(1, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql))); insert.executeUpdate();
            }
            statement.execute("PRAGMA application_id=1128813135"); statement.execute("PRAGMA user_version=1");
            try (var insert = connection.prepareStatement("INSERT INTO audit_events VALUES (?,?,?,?,?)")) {
                insert.setString(1, legacy.id().toString()); insert.setLong(2, legacy.occurredAt().getEpochSecond()); insert.setInt(3, legacy.occurredAt().getNano());
                insert.setString(4, legacy.value()); insert.setBytes(5, new ChronicleEventCodec().encode(legacy).bytes()); insert.executeUpdate();
            }
        }
    }
    @Test void upgradeFromActualV001PreservesRowsAndReplaysPendingLegacyAndDomain() throws Exception {
        var config = InfrastructureConfig.defaults(root); var legacy = PersistenceTest.event(1); var pending = PersistenceTest.event(2); var domain = BlockDeltaCodecTest.event(7);
        baseline(config.database(), legacy);
        try (var journal = new ChronicleQueueJournal(config.journal(), ChronicleClock.system())) {
            var codec = new ChronicleEventCodec(); journal.append(codec.encode(pending)); journal.append(codec.encode(domain)); journal.sync();
        }
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 2);
            assertEquals(Set.of(legacy, pending, domain), new HashSet<>(runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS)));
        }
        assertEquals(6, PersistenceTest.scalar(config.database(), "PRAGMA user_version"));
        assertEquals(6, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM schema_migrations"));
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events"));
        assertEquals(3, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM sqlite_master WHERE name IN ('idx_events_spatial_time','idx_events_actor_time','idx_events_target_time')"));
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM sqlite_master WHERE name IN ('idx_events_world_time','idx_events_time')"));
        try (var connection = PersistenceTest.connection(config.database())) { MigrationManager.migrate(connection, Instant.now()); }
    }
    @Test void badV001ChecksumRejectsBeforeRenamingAnyTable() throws Exception {
        Path database = root.resolve("old.db"); baseline(database, PersistenceTest.event(1));
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) { statement.execute("UPDATE schema_migrations SET checksum='tampered'"); }
        try (var connection = PersistenceTest.connection(database)) { assertThrows(SQLException.class, () -> MigrationManager.migrate(connection, Instant.now())); }
        assertEquals(1, PersistenceTest.scalar(database, "PRAGMA user_version"));
        assertEquals(1, PersistenceTest.scalar(database, "SELECT count(*) FROM audit_events"));
        assertEquals(0, PersistenceTest.scalar(database, "SELECT count(*) FROM sqlite_master WHERE name='legacy_audit_events'"));
    }
    @Test void relationalEnvelopePayloadAndIdempotentDuplicatesMatchThroughJournalAndRestart() throws Exception {
        var config = InfrastructureConfig.defaults(root); var domain = BlockDeltaCodecTest.event(Integer.MIN_VALUE);
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.submit(runtime, domain); PersistenceTest.submit(runtime, domain);
            PersistenceTest.await(() -> runtime.diagnostics().backlog() == 0 && runtime.diagnostics().committedPosition() != -1);
            assertEquals(List.of(domain), runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
        try (var connection = PersistenceTest.connection(config.database()); var query = connection.prepareStatement("SELECT * FROM audit_events WHERE event_id=?")) {
            query.setBytes(1, uuid(domain.id().value()));
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); var envelope = domain.envelope();
                assertEquals(envelope.worldId(), rows.getString("world_id")); assertEquals(envelope.x(), rows.getInt("x"));
                assertEquals(envelope.y(), rows.getInt("y")); assertEquals(envelope.z(), rows.getInt("z"));
                assertEquals(envelope.actorType(), rows.getString("actor_type")); assertEquals(envelope.actorId(), rows.getString("actor_id"));
                assertArrayEquals(uuid(envelope.actorUuid()), rows.getBytes("actor_uuid")); assertNull(rows.getBytes("root_actor_uuid"));
                assertEquals(envelope.targetId(), rows.getString("target_id")); assertEquals(987654321, rows.getInt("occurred_at_nanos"));
                assertArrayEquals(domain.payload(), rows.getBytes("payload")); assertFalse(rows.next());
            }
        }
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(domain), runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }
    @Test void domainPoisonAndLegacyShareAtomicTerminalBatch() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) { runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        try (var connection = PersistenceTest.connection(config.database()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events ADD COLUMN injected INTEGER CHECK(z <> 2)");
        }
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.submit(runtime, BlockDeltaCodecTest.event(1)); PersistenceTest.submit(runtime, BlockDeltaCodecTest.event(2)); PersistenceTest.submit(runtime, PersistenceTest.event(3));
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 2 && runtime.diagnostics().deadLetters() == 1);
        }
        assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events"));
        assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
        assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM dead_letters"));
        assertEquals(3, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
    }
    @Test void conflictingIdentityAcrossDomainAndLegacyHaltsWithoutAdvancingCheckpoint() throws Exception {
        for (boolean legacyConflict : List.of(false, true)) {
            var config = InfrastructureConfig.defaults(root.resolve(Boolean.toString(legacyConflict)));
            var original = BlockDeltaCodecTest.event(1);
            var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test");
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            try {
                PersistenceTest.submit(runtime, original);
                PersistenceTest.await(() -> runtime.diagnostics().persisted() == 1);
                var conflict = legacyConflict ? new AuditEvent(original.id(), original.occurredAt(), "conflicting legacy")
                        : new AuditEvent(original.id(), original.occurredAt().plusNanos(1), original.envelope(), original.payloadVersion(), original.payload());
                PersistenceTest.submit(runtime, conflict);
                PersistenceTest.await(() -> runtime.health() == ChronicleHealth.FATAL_STORAGE);
            } finally { assertThrows(IllegalStateException.class, runtime::close); }
            assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events"));
            assertEquals(0, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM legacy_audit_events"));
            assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        }
    }
    @Test void unsupportedDomainPayloadIsDeadLetteredAndStoredCorruptionIsFatal() throws Exception {
        var config = InfrastructureConfig.defaults(root); var codec = new ChronicleEventCodec(); var domain = BlockDeltaCodecTest.event(1);
        try (var journal = new ChronicleQueueJournal(config.journal(), ChronicleClock.system())) {
            journal.append(codec.encode(domain));
            var unsupported = BlockDeltaCodecTest.event(2);
            var bad = codec.encode(unsupported); byte[] bytes = bad.bytes();
            int payloadLength = unsupported.payload().length;
            ByteBuffer.wrap(bytes).putInt(bytes.length - payloadLength - 8, 99);
            journal.append(new EncodedAuditEvent(bad.id(), bytes)); journal.sync();
        }
        try (var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            PersistenceTest.await(() -> runtime.diagnostics().persisted() == 1 && runtime.diagnostics().deadLetters() == 1);
        }
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        try (var connection = PersistenceTest.connection(config.database()); var statement = connection.createStatement()) { statement.execute("UPDATE audit_events SET payload=X'00'"); }
        var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test");
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(ChronicleHealth.FATAL_STORAGE, runtime.health());
        } finally { assertThrows(IllegalStateException.class, runtime::close); }
    }
}
