ALTER TABLE audit_events ADD COLUMN rolled_back INTEGER NOT NULL DEFAULT 0 CHECK (rolled_back IN (0, 1));
