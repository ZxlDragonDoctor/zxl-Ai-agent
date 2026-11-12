CREATE TABLE ticket_approval (
    tenant_id varchar(150) NOT NULL,
    proposal_id uuid NOT NULL,
    requester_id varchar(150) NOT NULL,
    requester_session varchar(80) NOT NULL,
    requester_auth_version bigint NOT NULL CHECK (requester_auth_version > 0),
    operation_id varchar(128) NOT NULL,
    title varchar(200) NOT NULL,
    description varchar(10000) NOT NULL,
    parameter_sha256 char(64) NOT NULL CHECK (parameter_sha256 ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    outcome varchar(10) NOT NULL CHECK (outcome IN ('PENDING', 'APPROVED', 'REJECTED')),
    version bigint NOT NULL,
    reviewer_id varchar(150),
    reviewer_session varchar(80),
    reviewer_auth_version bigint,
    PRIMARY KEY (tenant_id, proposal_id),
    UNIQUE (tenant_id, operation_id),
    UNIQUE (tenant_id, proposal_id, operation_id),
    FOREIGN KEY (tenant_id, requester_id) REFERENCES membership ON DELETE CASCADE,
    CHECK (
        (outcome = 'PENDING' AND version = 1 AND reviewer_id IS NULL
         AND reviewer_session IS NULL AND reviewer_auth_version IS NULL)
        OR
        (outcome <> 'PENDING' AND version = 2 AND reviewer_id IS NOT NULL
         AND reviewer_id <> requester_id AND reviewer_session IS NOT NULL
         AND reviewer_auth_version IS NOT NULL AND reviewer_auth_version > 0)
    )
);

CREATE TABLE approved_ticket (
    tenant_id varchar(150) NOT NULL,
    proposal_id uuid NOT NULL,
    operation_id varchar(128) NOT NULL,
    ticket_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, operation_id),
    UNIQUE (tenant_id, proposal_id),
    FOREIGN KEY (tenant_id, proposal_id, operation_id)
        REFERENCES ticket_approval (tenant_id, proposal_id, operation_id) ON DELETE CASCADE
);
