-- Formal authenticated identities share the exact 150-character authority boundary.
-- Existing primary/foreign keys, scoped indexes, history JSON and all data are preserved.
SET search_path TO lesson12, public;
ALTER TABLE knowledge_document ALTER COLUMN tenant_id TYPE varchar(150), ALTER COLUMN document_id TYPE varchar(120);
ALTER TABLE knowledge_revision ALTER COLUMN tenant_id TYPE varchar(150), ALTER COLUMN document_id TYPE varchar(120);
ALTER TABLE knowledge_chunk ALTER COLUMN tenant_id TYPE varchar(150), ALTER COLUMN document_id TYPE varchar(120);
ALTER TABLE confirmed_memory ALTER COLUMN tenant_id TYPE varchar(150), ALTER COLUMN user_id TYPE varchar(150);
-- confirmed_memory_history has only memory_id + version + JSONB; no duplicated identity column to migrate.
