/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.storage.sqlite;

import io.github.andriyko69.chronicle.codec.ChronicleEventCodec;
import io.github.andriyko69.chronicle.codec.EncodedAuditEvent;
import io.github.andriyko69.chronicle.dlq.DeadLetterRecord;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.journal.JournalPosition;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.runtime.InfrastructureConfig;
import io.github.andriyko69.chronicle.storage.*;
import io.github.andriyko69.chronicle.storage.migration.MigrationManager;
import java.nio.file.Files;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.sqlite.SQLiteConfig;

public final class SQLiteStorageBackend implements StorageBackend {
    private final InfrastructureConfig config;
    private final ChronicleClock clock;
    private final String chronicleVersion;
    public SQLiteStorageBackend(InfrastructureConfig config, ChronicleClock clock, String chronicleVersion) {
        this.config = config; this.clock = clock; this.chronicleVersion = chronicleVersion;
    }
    private Connection connect(boolean reader) throws StorageFailure {
        try {
            Class.forName("org.sqlite.JDBC");
            if (!reader) Files.createDirectories(config.database().toAbsolutePath().getParent());
            var settings = new SQLiteConfig();
            settings.setBusyTimeout(config.busyTimeoutMillis());
            settings.enforceForeignKeys(true);
            settings.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            if (reader) settings.setReadOnly(true);
            else {
                settings.setJournalMode(SQLiteConfig.JournalMode.WAL);
                settings.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
            }
            return DriverManager.getConnection("jdbc:sqlite:" + config.database().toAbsolutePath(), settings.toProperties());
        } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
        catch (Exception failure) { throw fatal("Cannot initialize SQLite", failure); }
    }
    @Override public StorageWriter openWriter(UUID journalIdentity) throws StorageFailure {
        Connection connection = connect(false);
        try {
            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                    if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) throw new SQLException("WAL was not enabled");
                }
                try (var result = statement.executeQuery("PRAGMA synchronous")) {
                    if (!result.next() || result.getInt(1) != 2) throw new SQLException("FULL sync was not enabled");
                }
            }
            MigrationManager.migrate(connection, clock.now());
            try (var insert = connection.prepareStatement("INSERT INTO journal_checkpoint VALUES (1, ?, -1, 0) ON CONFLICT(singleton) DO NOTHING")) {
                insert.setString(1, journalIdentity.toString()); insert.executeUpdate();
            }
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT journal_id FROM journal_checkpoint WHERE singleton=1")) {
                if (!result.next() || !journalIdentity.toString().equals(result.getString(1)))
                    throw new SQLException("Database and journal identities do not match");
            }
            return new Writer(connection);
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw SQLiteFailureClassifier.classify(failure);
        }
    }
    @Override public StorageReader openReader() throws StorageFailure { return new Reader(connect(true)); }

    private final class Writer implements StorageWriter {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        Writer(Connection connection) { this.connection = connection; }
        private void owned() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("SQLite writer used off its owning thread");
        }
        @Override public Checkpoint checkpoint() throws StorageFailure {
            owned();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT committed_position,terminal_records FROM journal_checkpoint WHERE singleton=1")) {
                if (!rows.next()) throw new SQLException("Missing checkpoint");
                return new Checkpoint(new JournalPosition(rows.getLong(1)), rows.getLong(2), MigrationManager.VERSION);
            } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
        }
        private byte[] existing(TerminalRecord record) throws SQLException {
            try (var lookup = connection.prepareStatement("SELECT encoded FROM audit_events WHERE event_id=? UNION ALL SELECT original_payload FROM dead_letters WHERE event_id=?")) {
                lookup.setString(1, record.journal().event().id().toString()); lookup.setString(2, record.journal().event().id().toString());
                try (var rows = lookup.executeQuery()) {
                    if (!rows.next()) return null;
                    byte[] value = rows.getBytes(1);
                    if (rows.next()) throw new SQLException("Event has conflicting terminal outcomes");
                    if (!Arrays.equals(value, record.journal().event().bytes())) throw new SQLException("Conflicting payloads for event ID " + record.journal().event().id());
                    return value;
                }
            }
        }
        @Override public CommitResult commit(Checkpoint expected, List<TerminalRecord> batch) throws StorageFailure {
            owned();
            if (batch.isEmpty()) throw new IllegalArgumentException("Empty batch");
            long previous = expected.position().value();
            long ordinal = expected.terminalRecords();
            for (var record : batch) {
                if (record.journal().position().value() <= previous || record.journal().ordinal() != ++ordinal)
                    throw new IllegalArgumentException("Noncontiguous journal prefix");
                previous = record.journal().position().value();
            }
            JournalPosition last = batch.getLast().journal().position();
            try {
                connection.setAutoCommit(false);
                Checkpoint actual = checkpoint();
                if (!actual.equals(expected)) {
                    if (!actual.position().equals(last) || actual.terminalRecords() != expected.terminalRecords() + batch.size())
                        throw new SQLException("Unexpected concurrent checkpoint change");
                    for (var record : batch) if (existing(record) == null) throw new SQLException("Committed checkpoint skips missing terminal event");
                    connection.setAutoCommit(true);
                    return new CommitResult(actual, 0, 0);
                }
                int persisted = 0;
                int deadLetters = 0;
                for (var record : batch) {
                    if (existing(record) != null) continue;
                    if (record.deadLetter() != null) { insertDeadLetter(record, record.deadLetter()); deadLetters++; continue; }
                    Savepoint savepoint = connection.setSavepoint();
                    try {
                        try (var insert = connection.prepareStatement("INSERT INTO audit_events (event_id,occurred_seconds,occurred_nanos,synthetic_value,encoded) VALUES (?, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING")) {
                            insert.setString(1, record.event().id().toString()); insert.setLong(2, record.event().occurredAt().getEpochSecond());
                            insert.setInt(3, record.event().occurredAt().getNano()); insert.setString(4, record.event().value());
                            insert.setBytes(5, record.journal().event().bytes());
                            if (insert.executeUpdate() != 1) throw new SQLException("Unexpected duplicate after identity check");
                        }
                        persisted++;
                    } catch (SQLException failure) {
                        var classified = SQLiteFailureClassifier.classify(failure);
                        if (classified.kind() != StorageFailure.Kind.PERMANENT_EVENT) throw failure;
                        connection.rollback(savepoint);
                        var now = clock.now();
                        insertDeadLetter(record, new DeadLetterRecord(now, now, classified.kind(), classified.getMessage(),
                                classified.backendCode(), failure.getSQLState(), 1));
                        deadLetters++;
                    }
                    connection.releaseSavepoint(savepoint);
                }
                try (var update = connection.prepareStatement("UPDATE journal_checkpoint SET committed_position=?,terminal_records=? WHERE singleton=1 AND committed_position=?")) {
                    update.setLong(1, last.value()); update.setLong(2, expected.terminalRecords() + batch.size()); update.setLong(3, expected.position().value());
                    if (update.executeUpdate() != 1) throw new SQLException("Checkpoint update lost ownership");
                }
                // Xerial commit() immediately issues another BEGIN IMMEDIATE. Finish without that extra transaction.
                connection.setAutoCommit(true);
                return new CommitResult(new Checkpoint(last, expected.terminalRecords() + batch.size(), MigrationManager.VERSION), persisted, deadLetters);
            } catch (SQLException failure) {
                // A failed BEGIN may leave JDBC's autoCommit flag false with no SQLite transaction.
                // Closing rolls back any actual uncommitted work; never mask the original/ambiguous error.
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw SQLiteFailureClassifier.classify(failure);
            } catch (StorageFailure failure) {
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        private void insertDeadLetter(TerminalRecord record, DeadLetterRecord deadLetter) throws SQLException {
            try (var insert = connection.prepareStatement("INSERT INTO dead_letters VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, record.journal().event().id().toString()); insert.setLong(2, record.journal().position().value());
                insert.setString(3, deadLetter.firstFailure().toString()); insert.setString(4, deadLetter.lastFailure().toString());
                insert.setString(5, deadLetter.classification().name()); insert.setString(6, deadLetter.message());
                insert.setString(7, deadLetter.backendCode()); insert.setString(8, deadLetter.sqlState()); insert.setInt(9, deadLetter.attempts());
                insert.setString(10, chronicleVersion); insert.setInt(11, MigrationManager.VERSION);
                insert.setBytes(12, record.journal().event().bytes());
                insert.executeUpdate();
            }
        }
        @Override public void close() throws StorageFailure {
            owned();
            try { connection.close(); } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
        }
    }

    private static final class Reader implements StorageReader {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        Reader(Connection connection) { this.connection = connection; }
        @Override public List<AuditEvent> recent(int limit) throws StorageFailure {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Reader used off its owning thread");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Query limit must be 1..1000");
            try (var select = connection.prepareStatement("SELECT event_id,encoded FROM audit_events ORDER BY occurred_seconds DESC,occurred_nanos DESC,event_id DESC LIMIT ?")) {
                select.setInt(1, limit);
                var values = new ArrayList<AuditEvent>();
                var codec = new ChronicleEventCodec();
                try (var rows = select.executeQuery()) {
                    while (rows.next()) values.add(codec.decode(new EncodedAuditEvent(new EventId(UUID.fromString(rows.getString(1))), rows.getBytes(2))));
                }
                return List.copyOf(values);
            } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
            catch (Exception failure) { throw fatal("Corrupt stored canonical event", failure); }
        }
        @Override public void close() throws StorageFailure {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Reader closed off its owning thread");
            try { connection.close(); } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
        }
    }
    private static StorageFailure fatal(String message, Throwable cause) {
        return new StorageFailure(StorageFailure.Kind.FATAL, message, null, cause);
    }
}
