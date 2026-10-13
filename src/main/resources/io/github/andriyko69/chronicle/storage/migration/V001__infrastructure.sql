CREATE TABLE journal_checkpoint (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    journal_id TEXT NOT NULL,
    committed_position INTEGER NOT NULL CHECK (committed_position >= -1),
    terminal_records INTEGER NOT NULL CHECK (terminal_records >= 0)
) STRICT;
CREATE TABLE audit_events (
    event_id TEXT PRIMARY KEY NOT NULL CHECK (length(event_id) = 36),
    occurred_seconds INTEGER NOT NULL,
    occurred_nanos INTEGER NOT NULL CHECK (occurred_nanos BETWEEN 0 AND 999999999),
    synthetic_value TEXT NOT NULL CHECK (length(CAST(synthetic_value AS BLOB)) <= 4096),
    encoded BLOB NOT NULL
) STRICT;
CREATE INDEX audit_events_time ON audit_events(occurred_seconds, occurred_nanos, event_id);
CREATE TABLE dead_letters (
    event_id TEXT PRIMARY KEY NOT NULL,
    journal_position INTEGER NOT NULL UNIQUE,
    first_failure TEXT NOT NULL,
    last_failure TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification = 'PERMANENT_EVENT'),
    message TEXT NOT NULL,
    backend_code TEXT,
    sql_state TEXT,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    chronicle_version TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    original_payload BLOB NOT NULL
) STRICT;
