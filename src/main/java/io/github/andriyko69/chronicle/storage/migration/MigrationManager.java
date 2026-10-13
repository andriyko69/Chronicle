/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class MigrationManager {
    public static final int VERSION = 2;
    private static final int APPLICATION_ID = 0x4348524f;
    private static final List<String> NAMES = List.of("V001__infrastructure.sql", "V002__audit_events.sql");
    private record Migration(String name, String sql, String checksum) {}
    private MigrationManager() {}

    private static Migration read(String name) throws SQLException {
        try (var input = MigrationManager.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing migration " + name);
            byte[] bytes = input.readAllBytes();
            return new Migration(name, new String(bytes, StandardCharsets.UTF_8),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Exception failure) { throw new SQLException("Cannot read canonical migration " + name, failure); }
    }

    public static void migrate(Connection connection, Instant now) throws SQLException {
        var migrations = new java.util.ArrayList<Migration>();
        for (String name : NAMES) migrations.add(read(name));
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            int application;
            int version;
            try (var result = statement.executeQuery("PRAGMA application_id")) { result.next(); application = result.getInt(1); }
            try (var result = statement.executeQuery("PRAGMA user_version")) { result.next(); version = result.getInt(1); }
            if (version == 0 && application == 0) {
                try (var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
                    if (result.next()) throw new SQLException("Refusing to initialize a nonempty unversioned database");
                }
                statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TEXT NOT NULL) STRICT");
                statement.execute("PRAGMA application_id=" + APPLICATION_ID);
            } else if (application != APPLICATION_ID || version < 1 || version > VERSION) {
                throw new SQLException("Incompatible Chronicle database application/version: " + application + "/" + version);
            }
            // Validate the entire applied prefix before any upgrade DDL touches existing data.
            try (var rows = statement.executeQuery("SELECT version,name,checksum FROM schema_migrations ORDER BY version")) {
                for (int i = 0; i < version; i++) {
                    var migration = migrations.get(i);
                    if (!rows.next() || rows.getInt(1) != i + 1 || !migration.name().equals(rows.getString(2))
                            || !migration.checksum().equals(rows.getString(3)))
                        throw new SQLException("Corrupt or incompatible migration history/checksum");
                }
                if (rows.next()) throw new SQLException("Unexpected migration history beyond user_version");
            }
            for (int i = version; i < migrations.size(); i++) {
                var migration = migrations.get(i);
                // These canonical migrations contain ordinary DDL, no triggers or semicolons in literals.
                for (String command : migration.sql().split(";")) if (!command.isBlank()) statement.execute(command);
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?, ?, ?, ?)")) {
                    insert.setInt(1, i + 1); insert.setString(2, migration.name());
                    insert.setString(3, migration.checksum()); insert.setString(4, now.toString()); insert.executeUpdate();
                }
                statement.execute("PRAGMA user_version=" + (i + 1));
            }
            try (var check = statement.executeQuery("PRAGMA quick_check")) {
                if (!check.next() || !"ok".equals(check.getString(1)) || check.next()) throw new SQLException("SQLite quick_check failed");
            }
            connection.setAutoCommit(true);
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}
