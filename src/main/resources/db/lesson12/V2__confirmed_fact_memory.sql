SET search_path TO lesson12, public;
CREATE TABLE confirmed_memory (
 memory_id varchar(64) PRIMARY KEY, tenant_id varchar(64) NOT NULL,user_id varchar(64) NOT NULL,
 fact_key varchar(40) NOT NULL,fact_value varchar(100) NOT NULL, source_text varchar(160) NOT NULL,
 session_id varchar(80) NOT NULL,message_id varchar(80) NOT NULL, extraction_version varchar(40) NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('PENDING','CONFIRMED','REJECTED','DELETED')),
 version bigint NOT NULL CHECK(version>0),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,deleted_at timestamptz,
 CHECK(expires_at>created_at),CHECK((state='DELETED')=(deleted_at IS NOT NULL)),
 UNIQUE(tenant_id,user_id,message_id)
);
CREATE INDEX confirmed_memory_scope ON confirmed_memory(tenant_id,user_id,state,expires_at);
CREATE UNIQUE INDEX confirmed_memory_live_key ON confirmed_memory(tenant_id,user_id,fact_key) WHERE state IN ('PENDING','CONFIRMED');
-- Immutable previous versions preserve the explicit source of every correction/lifecycle transition.
-- Soft deletion is business invisibility, not physical erasure of this audit trail.
CREATE TABLE confirmed_memory_history (
 memory_id varchar(64) NOT NULL, version bigint NOT NULL,
 previous_record jsonb NOT NULL, PRIMARY KEY(memory_id,version),
 FOREIGN KEY(memory_id) REFERENCES confirmed_memory(memory_id)
);
CREATE FUNCTION preserve_confirmed_memory_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO lesson12.confirmed_memory_history(memory_id,version,previous_record)
 VALUES(OLD.memory_id,OLD.version,to_jsonb(OLD));
 RETURN NEW;
END $$;
CREATE TRIGGER confirmed_memory_version_history BEFORE UPDATE ON confirmed_memory
 FOR EACH ROW EXECUTE FUNCTION preserve_confirmed_memory_version();
