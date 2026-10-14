CREATE INDEX idx_events_position_time ON audit_events(world_id, x, z, y, occurred_at_sec DESC, occurred_at_nanos DESC, event_id DESC);
