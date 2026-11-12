CREATE TABLE durable_agent_run (
    tenant_id varchar(150) NOT NULL REFERENCES tenant ON DELETE CASCADE,
    run_id uuid NOT NULL,
    latest_sequence bigint NOT NULL CHECK (latest_sequence > 0),
    PRIMARY KEY (tenant_id, run_id)
);

CREATE TABLE durable_agent_run_event (
    tenant_id varchar(150) NOT NULL,
    run_id uuid NOT NULL,
    event_sequence bigint NOT NULL CHECK (event_sequence > 0),
    status varchar(16) NOT NULL CHECK (status IN
        ('queued', 'planning', 'running', 'synthesizing', 'completed', 'failed', 'cancelled', 'interrupted')),
    public_snapshot text NOT NULL CHECK (jsonb_typeof(public_snapshot::jsonb) = 'object'),
    PRIMARY KEY (tenant_id, run_id, event_sequence),
    FOREIGN KEY (tenant_id, run_id) REFERENCES durable_agent_run ON DELETE CASCADE
);

ALTER TABLE durable_agent_run ADD CONSTRAINT durable_agent_run_snapshot_fk
    FOREIGN KEY (tenant_id, run_id, latest_sequence)
    REFERENCES durable_agent_run_event (tenant_id, run_id, event_sequence)
    DEFERRABLE INITIALLY DEFERRED;
