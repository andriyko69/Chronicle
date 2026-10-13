CREATE INDEX idx_events_world_time ON audit_events(world_id, occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC, x, z, y);
CREATE INDEX idx_events_time ON audit_events(occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC);
