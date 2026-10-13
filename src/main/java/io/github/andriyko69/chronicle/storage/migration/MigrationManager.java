/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;

public final class MigrationManager {
    public static final int VERSION = 1;
    private static final int APPLICATION_ID = 0x4348524f;
    private static final String NAME = "V001__infrastructure.sql";
    private MigrationManager() {}

    public static void migrate(Connection connection, Instant now) throws SQLException {
        String sql;
        String checksum;
        try (var input = MigrationManager.class.getResourceAsStream(NAME)) {
            if (input == null) throw new IllegalStateException("Missing migration " + NAME);
            byte[] bytes = input.readAllBytes();
            sql = new String(bytes, StandardCharsets.UTF_8);
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) { throw new SQLException("Cannot read canonical migration", failure); }

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
                // V001 has only ordinary DDL statements, no triggers or SQL string semicolons.
                for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (1, ?, ?, ?)")) {
                    insert.setString(1, NAME); insert.setString(2, checksum); insert.setString(3, now.toString()); insert.executeUpdate();
                }
                statement.execute("PRAGMA application_id=" + APPLICATION_ID);
                statement.execute("PRAGMA user_version=" + VERSION);
            } else if (application != APPLICATION_ID || version != VERSION) {
                throw new SQLException("Incompatible Chronicle database application/version: " + application + "/" + version);
            }
            try (var rows = statement.executeQuery("SELECT version,name,checksum FROM schema_migrations ORDER BY version")) {
                if (!rows.next() || rows.getInt(1) != 1 || !NAME.equals(rows.getString(2))
                        || !checksum.equals(rows.getString(3)) || rows.next())
                    throw new SQLException("Corrupt or incompatible migration history/checksum");
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
