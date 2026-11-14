ALTER TABLE durable_agent_run ADD COLUMN publication_expires_at timestamptz;

CREATE INDEX durable_agent_run_expiry_idx
    ON durable_agent_run (publication_expires_at, tenant_id, run_id)
    WHERE publication_expires_at IS NOT NULL;
