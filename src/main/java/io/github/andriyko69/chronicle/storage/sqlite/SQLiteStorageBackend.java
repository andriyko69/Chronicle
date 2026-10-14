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
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.diagnostics.StorageTotals;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Comparator;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.sqlite.SQLiteConfig;
import org.sqlite.ProgressHandler;

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
            settings.setBusyTimeout(reader ? Math.min(2000, config.busyTimeoutMillis()) : config.busyTimeoutMillis());
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
            byte[] found = null;
            try (var lookup = connection.prepareStatement("SELECT * FROM audit_events WHERE event_id=?")) {
                lookup.setBytes(1, uuidBytes(record.journal().event().id().value()));
                try (var rows = lookup.executeQuery()) {
                    if (rows.next()) found = new ChronicleEventCodec().encode(domainEvent(rows)).bytes();
                }
            }
            try (var lookup = connection.prepareStatement("SELECT encoded FROM legacy_audit_events WHERE event_id=? UNION ALL SELECT original_payload FROM dead_letters WHERE event_id=?")) {
                lookup.setString(1, record.journal().event().id().toString()); lookup.setString(2, record.journal().event().id().toString());
                try (var rows = lookup.executeQuery()) {
                    while (rows.next()) {
                        if (found != null) throw new SQLException("Event has conflicting terminal outcomes");
                        found = rows.getBytes(1);
                    }
                }
            }
            if (found != null && !Arrays.equals(found, record.journal().event().bytes()))
                throw new SQLException("Conflicting payloads for event ID " + record.journal().event().id());
            return found;
        }
        private void insertEvent(TerminalRecord record) throws SQLException {
            var event = record.event();
            if (event.legacy()) {
                try (var insert = connection.prepareStatement("INSERT INTO legacy_audit_events (event_id,occurred_seconds,occurred_nanos,synthetic_value,encoded) VALUES (?, ?, ?, ?, ?)")) {
                    insert.setString(1, event.id().toString()); insert.setLong(2, event.occurredAt().getEpochSecond());
                    insert.setInt(3, event.occurredAt().getNano()); insert.setString(4, event.value());
                    insert.setBytes(5, record.journal().event().bytes()); insert.executeUpdate();
                }
                return;
            }
            var envelope = event.envelope();
            try (var insert = connection.prepareStatement("INSERT INTO audit_events (event_id,occurred_at_sec,occurred_at_nanos,world_id,x,y,z,event_type,actor_type,actor_id,actor_uuid,root_actor_uuid,root_actor_id,target_id,payload_version,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setBytes(1, uuidBytes(event.id().value())); insert.setLong(2, event.occurredAt().getEpochSecond());
                insert.setInt(3, event.occurredAt().getNano()); insert.setString(4, envelope.worldId());
                insert.setInt(5, envelope.x()); insert.setInt(6, envelope.y()); insert.setInt(7, envelope.z());
                insert.setString(8, envelope.eventType()); insert.setString(9, envelope.actorType()); insert.setString(10, envelope.actorId());
                insert.setBytes(11, uuidBytes(envelope.actorUuid())); insert.setBytes(12, uuidBytes(envelope.rootActorUuid()));
                insert.setString(13, envelope.rootActorId()); insert.setString(14, envelope.targetId());
                insert.setInt(15, event.payloadVersion()); insert.setBytes(16, event.payload());
                insert.executeUpdate();
            }
            // Off-thread lookups resolve u:<name> against root actors through this mapping, never through live server state.
            if (envelope.actorUuid() != null && (envelope.actorType().equals("PLAYER") || envelope.actorType().equals("FAKE_PLAYER")))
                profile(envelope.actorId(), envelope.actorUuid());
            if (envelope.rootActorId() != null) profile(envelope.rootActorId(), envelope.rootActorUuid());
        }
        private void profile(String name, UUID uuid) throws SQLException {
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO actor_profiles (actor_id,actor_uuid) VALUES (?,?)")) {
                insert.setString(1, name); insert.setBytes(2, uuidBytes(uuid)); insert.executeUpdate();
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
                        insertEvent(record);
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
        private static final long QUERY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private long deadline;
        private boolean interrupted;
        Reader(Connection connection) throws StorageFailure {
            this.connection = connection;
            try {
                ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
                    @Override protected int progress() {
                        interrupted = Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0;
                        return interrupted ? 1 : 0;
                    }
                });
            } catch (SQLException failure) {
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw SQLiteFailureClassifier.classify(failure);
            }
        }
        private void beginQuery() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Reader used off its owning thread");
            deadline = System.nanoTime() + QUERY_NANOS;
            interrupted = false;
        }
        private StorageFailure queryFailure(SQLException failure) {
            // A deliberately interrupted expensive query does not imply corruption of the database.
            if (interrupted) return new StorageFailure(StorageFailure.Kind.TRANSIENT, "Lookup timed out or was interrupted; narrow the filters", "QUERY_TIMEOUT", failure);
            return SQLiteFailureClassifier.classify(failure);
        }
        @Override public LookupPage lookup(LookupSpec spec, int page) throws StorageFailure {
            beginQuery();
            var query = LookupSql.page(spec, page);
            var values = new ArrayList<LookupRow>();
            try (var select = connection.prepareStatement(query.sql())) {
                for (int i = 0; i < query.parameters().size(); i++) {
                    Object value = query.parameters().get(i);
                    if (value instanceof UUID id) select.setBytes(i + 1, uuidBytes(id));
                    else select.setObject(i + 1, value);
                }
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        var event = domainEvent(rows);
                        values.add(LookupRow.decode(event));
                    }
                }
                boolean more = values.size() > LookupSpec.PAGE_SIZE;
                return new LookupPage(page, values.subList(0, Math.min(values.size(), LookupSpec.PAGE_SIZE)), more);
            } catch (SQLException failure) { throw queryFailure(failure); }
            catch (RuntimeException failure) { throw fatal("Corrupt stored lookup payload", failure); }
        }
        @Override public StorageTotals totals() throws StorageFailure {
            beginQuery();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT (SELECT count(*) FROM audit_events) + (SELECT count(*) FROM legacy_audit_events), (SELECT count(*) FROM dead_letters)")) {
                if (!rows.next()) throw new SQLException("Missing storage totals");
                return new StorageTotals(rows.getLong(1), rows.getLong(2));
            } catch (SQLException failure) { throw queryFailure(failure); }
        }
        @Override public List<AuditEvent> recent(int limit) throws StorageFailure {
            beginQuery();
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Query limit must be 1..1000");
            try {
                var values = new ArrayList<AuditEvent>();
                try (var select = connection.prepareStatement("SELECT * FROM audit_events ORDER BY occurred_at_sec DESC,occurred_at_nanos DESC,event_id DESC LIMIT ?")) {
                    select.setInt(1, limit);
                    try (var rows = select.executeQuery()) { while (rows.next()) values.add(domainEvent(rows)); }
                }
                try (var select = connection.prepareStatement("SELECT event_id,encoded FROM legacy_audit_events ORDER BY occurred_seconds DESC,occurred_nanos DESC,event_id DESC LIMIT ?")) {
                    select.setInt(1, limit);
                    var codec = new ChronicleEventCodec();
                    try (var rows = select.executeQuery()) {
                        while (rows.next()) values.add(codec.decode(new EncodedAuditEvent(new EventId(UUID.fromString(rows.getString(1))), rows.getBytes(2))));
                    }
                }
                values.sort(Comparator.comparing(AuditEvent::occurredAt).thenComparing(AuditEvent::id).reversed());
                return List.copyOf(values.subList(0, Math.min(limit, values.size())));
            } catch (SQLException failure) { throw queryFailure(failure); }
            catch (Exception failure) { throw fatal("Corrupt stored canonical event", failure); }
        }
        @Override public void close() throws StorageFailure {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Reader closed off its owning thread");
            try { connection.close(); } catch (SQLException failure) { throw SQLiteFailureClassifier.classify(failure); }
        }
    }
    private static byte[] uuidBytes(UUID value) {
        return value == null ? null : ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length != 16) throw new IllegalArgumentException("Corrupt UUID length");
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static AuditEvent domainEvent(ResultSet rows) throws SQLException {
        try {
            int nanos = rows.getInt("occurred_at_nanos");
            if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException("Corrupt event nanos");
            var envelope = new EventEnvelope(rows.getString("world_id"), rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                    rows.getString("event_type"), rows.getString("actor_type"), rows.getString("actor_id"),
                    uuid(rows.getBytes("actor_uuid")), uuid(rows.getBytes("root_actor_uuid")), rows.getString("root_actor_id"),
                    rows.getString("target_id"));
            return new AuditEvent(new EventId(uuid(rows.getBytes("event_id"))), Instant.ofEpochSecond(rows.getLong("occurred_at_sec"), nanos),
                    envelope, rows.getInt("payload_version"), rows.getBytes("payload"));
        } catch (RuntimeException failure) { throw new SQLException("Corrupt stored domain event", failure); }
    }
    private static StorageFailure fatal(String message, Throwable cause) {
        return new StorageFailure(StorageFailure.Kind.FATAL, message, null, cause);
    }
}
