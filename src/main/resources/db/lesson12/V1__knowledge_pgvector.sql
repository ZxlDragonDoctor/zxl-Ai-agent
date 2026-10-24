SET search_path TO lesson12, public;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE knowledge_document (
 tenant_id varchar(64) NOT NULL, document_id varchar(80) NOT NULL,
 source_name varchar(120) NOT NULL, status varchar(20) NOT NULL,
 active_revision varchar(64), version bigint NOT NULL DEFAULT 0,
 chunk_count integer NOT NULL DEFAULT 0, error_code varchar(80),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,document_id)
);
CREATE TABLE knowledge_revision (
 tenant_id varchar(64) NOT NULL, document_id varchar(80) NOT NULL, revision_id varchar(64) NOT NULL,
 raw_hash varchar(64) NOT NULL, normalized_hash varchar(64) NOT NULL, profile_hash varchar(64) NOT NULL,
 raw_text text NOT NULL, model_identity varchar(120) NOT NULL,
 state varchar(20) NOT NULL, expected_chunks integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,document_id,revision_id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES knowledge_document(tenant_id,document_id)
);
CREATE TABLE knowledge_chunk (
 tenant_id varchar(64) NOT NULL, document_id varchar(80) NOT NULL, revision_id varchar(64) NOT NULL,
 chunk_id varchar(64) NOT NULL, ordinal integer NOT NULL, text_content text NOT NULL,
 start_utf16 integer NOT NULL, end_utf16 integer NOT NULL, start_line integer NOT NULL, end_line integer NOT NULL,
 title_path text NOT NULL, model_identity varchar(120) NOT NULL, dimensions integer NOT NULL,
 embedding vector NOT NULL,
 PRIMARY KEY(tenant_id,chunk_id),
 UNIQUE(tenant_id,document_id,revision_id,ordinal),
 FOREIGN KEY(tenant_id,document_id,revision_id) REFERENCES knowledge_revision(tenant_id,document_id,revision_id) ON DELETE CASCADE,
 CHECK(start_utf16>=0 AND end_utf16>start_utf16),
 CHECK(dimensions BETWEEN 1 AND 4096 AND vector_dims(embedding)=dimensions)
);
CREATE INDEX knowledge_chunk_revision_idx ON knowledge_chunk(tenant_id,document_id,revision_id);
-- Exact cosine scan baseline: ANN index tuning belongs to lesson 13, not a fake performance claim.
