ALTER TABLE durable_agent_run ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp();

CREATE INDEX durable_agent_run_recent_idx
    ON durable_agent_run (tenant_id, updated_at DESC, run_id);
