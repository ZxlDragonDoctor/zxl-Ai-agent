CREATE TABLE legacy_import_receipt (
    import_id varchar(150) PRIMARY KEY,
    tenant_id varchar(150) NOT NULL,
    subject_id varchar(150) NOT NULL,
    manifest_sha256 char(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    entry_count integer NOT NULL CHECK (entry_count BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE legacy_import_entry (
    import_id varchar(150) NOT NULL REFERENCES legacy_import_receipt ON DELETE CASCADE,
    conversation_id varchar(150) NOT NULL,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 8388608),
    PRIMARY KEY (import_id, conversation_id)
);
