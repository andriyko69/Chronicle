ALTER TABLE audit_events ADD COLUMN root_actor_id TEXT CHECK (root_actor_id IS NULL OR (root_actor_uuid IS NOT NULL AND length(root_actor_id) BETWEEN 1 AND 128));
CREATE INDEX idx_events_root_actor_time ON audit_events(root_actor_uuid, occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC);
CREATE INDEX idx_events_actor_uuid_time ON audit_events(actor_uuid, occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC);
CREATE TABLE actor_profiles (
    actor_id TEXT NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 128),
    actor_uuid BLOB NOT NULL CHECK (length(actor_uuid) = 16),
    PRIMARY KEY (actor_id, actor_uuid)
) STRICT, WITHOUT ROWID;
INSERT OR IGNORE INTO actor_profiles (actor_id, actor_uuid)
    SELECT DISTINCT actor_id, actor_uuid FROM audit_events WHERE actor_uuid IS NOT NULL AND actor_type IN ('PLAYER', 'FAKE_PLAYER');
