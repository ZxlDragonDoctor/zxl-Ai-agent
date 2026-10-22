CREATE TABLE agent_run (
 id VARCHAR(36) PRIMARY KEY, goal VARCHAR(200) NOT NULL, status VARCHAR(24) NOT NULL,
 accepted_at BIGINT NOT NULL, deadline_at BIGINT NOT NULL, version BIGINT NOT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, error_code VARCHAR(80)
);
CREATE TABLE agent_task (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL UNIQUE REFERENCES agent_run(id),
 kind VARCHAR(32) NOT NULL, operation_id VARCHAR(128) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 title VARCHAR(200) NOT NULL, description VARCHAR(10000) NOT NULL,
 status VARCHAR(24) NOT NULL, version BIGINT NOT NULL, lease_owner VARCHAR(100),
 lease_until BIGINT, fence_token BIGINT NOT NULL
);
CREATE TABLE task_attempt (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL REFERENCES agent_task(id),
 attempt_no INTEGER NOT NULL, fence_token BIGINT NOT NULL, status VARCHAR(24) NOT NULL,
 started_at BIGINT NOT NULL, finished_at BIGINT, error_code VARCHAR(80), UNIQUE(task_id, attempt_no)
);
CREATE TABLE business_operation (
 scope VARCHAR(40) NOT NULL, operation_type VARCHAR(32) NOT NULL, operation_id VARCHAR(128) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, ticket_id VARCHAR(36), version BIGINT NOT NULL,
 PRIMARY KEY(scope, operation_type, operation_id)
);
CREATE TABLE ticket (
 id VARCHAR(36) PRIMARY KEY, operation_id VARCHAR(128) NOT NULL UNIQUE,
 title VARCHAR(200) NOT NULL, description VARCHAR(10000) NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE run_event (
 run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id), sequence BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL, payload_json VARCHAR(2000) NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(run_id, sequence)
);
CREATE INDEX task_recovery ON agent_task(status, lease_until);
CREATE INDEX run_deadline ON agent_run(status, deadline_at);
