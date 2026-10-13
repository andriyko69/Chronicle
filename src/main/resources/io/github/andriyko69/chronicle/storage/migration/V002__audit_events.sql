ALTER TABLE audit_events RENAME TO legacy_audit_events;
CREATE TABLE audit_events (
    event_id BLOB PRIMARY KEY NOT NULL CHECK (length(event_id) = 16),
    occurred_at_sec INTEGER NOT NULL,
    occurred_at_nanos INTEGER NOT NULL CHECK (occurred_at_nanos BETWEEN 0 AND 999999999),
    world_id TEXT NOT NULL CHECK (length(world_id) BETWEEN 1 AND 128),
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    event_type TEXT NOT NULL CHECK (length(event_type) BETWEEN 1 AND 32),
    actor_type TEXT NOT NULL CHECK (length(actor_type) BETWEEN 1 AND 32),
    actor_id TEXT NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 128),
    actor_uuid BLOB CHECK (actor_uuid IS NULL OR length(actor_uuid) = 16),
    root_actor_uuid BLOB CHECK (root_actor_uuid IS NULL OR length(root_actor_uuid) = 16),
    target_id TEXT NOT NULL CHECK (length(target_id) BETWEEN 1 AND 128),
    payload_version INTEGER NOT NULL,
    payload BLOB NOT NULL
) STRICT;
CREATE INDEX idx_events_spatial_time ON audit_events(world_id, x, z, y, occurred_at_sec);
CREATE INDEX idx_events_actor_time ON audit_events(actor_id, occurred_at_sec);
CREATE INDEX idx_events_target_time ON audit_events(target_id, occurred_at_sec);
