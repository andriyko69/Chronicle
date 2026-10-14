/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.storage.migration.MigrationManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LookupMigrationTest {
    @TempDir Path root;
    private Path v002() throws Exception {
        Path database = root.resolve("v002.db");
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,name TEXT NOT NULL,checksum TEXT NOT NULL,applied_at TEXT NOT NULL) STRICT");
            String[] names = {"V001__infrastructure.sql", "V002__audit_events.sql"};
            for (int i = 0; i < names.length; i++) {
                byte[] bytes;
                try (var input = MigrationManager.class.getResourceAsStream(names[i])) { bytes = input.readAllBytes(); }
                for (String sql : new String(bytes, StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) statement.execute(sql);
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?,?,?,'baseline')")) {
                    insert.setInt(1, i + 1); insert.setString(2, names[i]); insert.setString(3, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); insert.executeUpdate();
                }
            }
            statement.execute("PRAGMA application_id=1128813135"); statement.execute("PRAGMA user_version=2");
            var event = BlockDeltaCodecTest.event(1);
            try (var insert = connection.prepareStatement("INSERT INTO audit_events VALUES (X'00000000000070008000000000000001',1,2,'minecraft:overworld',1,2,3,'BLOCK_BREAK','PLAYER','before',NULL,NULL,'minecraft:stone',1,?)")) {
                insert.setBytes(1, event.payload()); insert.executeUpdate();
            }
        }
        return database;
    }
    @Test void actualV002UpgradesPreservingRowsAndHistoricalChecksums() throws Exception {
        Path database = v002();
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
        assertEquals(4, PersistenceTest.scalar(database, "PRAGMA user_version"));
        assertEquals(1, PersistenceTest.scalar(database, "SELECT count(*) FROM audit_events WHERE actor_id='before'"));
        assertEquals(2, PersistenceTest.scalar(database, "SELECT count(*) FROM schema_migrations WHERE applied_at='baseline'"));
        assertEquals(3, PersistenceTest.scalar(database, "SELECT count(*) FROM sqlite_master WHERE name IN ('idx_events_world_time','idx_events_time','idx_events_position_time')"));
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
    }
    @Test void tamperedV002FailsBeforeAddingLookupIndexes() throws Exception {
        Path database = v002();
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) { statement.execute("UPDATE schema_migrations SET checksum='tampered' WHERE version=2"); }
        try (var connection = PersistenceTest.connection(database)) { assertThrows(SQLException.class, () -> MigrationManager.migrate(connection, Instant.now())); }
        assertEquals(2, PersistenceTest.scalar(database, "PRAGMA user_version"));
        assertEquals(0, PersistenceTest.scalar(database, "SELECT count(*) FROM sqlite_master WHERE name='idx_events_world_time'"));
        assertEquals(1, PersistenceTest.scalar(database, "SELECT count(*) FROM audit_events"));
    }
}
