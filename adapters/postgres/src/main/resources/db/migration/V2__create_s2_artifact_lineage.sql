CREATE TABLE artifacts (
    principal_id VARCHAR(200) NOT NULL,
    artifact_id VARCHAR(200) NOT NULL,
    source_capture_id VARCHAR(200) NOT NULL,
    current_version INTEGER NOT NULL,
    current_hash CHAR(64) NOT NULL,
    CONSTRAINT artifacts_pk PRIMARY KEY (principal_id, artifact_id),
    CONSTRAINT artifacts_source_capture_fk
        FOREIGN KEY (principal_id, source_capture_id)
        REFERENCES captures (principal_id, capture_id),
    CONSTRAINT artifacts_principal_id_valid
        CHECK (char_length(btrim(principal_id)) BETWEEN 1 AND 200),
    CONSTRAINT artifacts_artifact_id_valid
        CHECK (char_length(btrim(artifact_id)) BETWEEN 1 AND 200),
    CONSTRAINT artifacts_source_capture_id_valid
        CHECK (char_length(btrim(source_capture_id)) BETWEEN 1 AND 200),
    CONSTRAINT artifacts_current_version_valid
        CHECK (current_version >= 1),
    CONSTRAINT artifacts_current_hash_valid
        CHECK (current_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE artifact_versions (
    principal_id VARCHAR(200) NOT NULL,
    artifact_id VARCHAR(200) NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    base_version INTEGER,
    base_hash CHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT artifact_versions_pk PRIMARY KEY (principal_id, artifact_id, version),
    CONSTRAINT artifact_versions_identity_hash_uq
        UNIQUE (principal_id, artifact_id, version, content_hash),
    CONSTRAINT artifact_versions_artifact_fk
        FOREIGN KEY (principal_id, artifact_id)
        REFERENCES artifacts (principal_id, artifact_id),
    CONSTRAINT artifact_versions_base_fk
        FOREIGN KEY (principal_id, artifact_id, base_version, base_hash)
        REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash),
    CONSTRAINT artifact_versions_version_valid
        CHECK (version >= 1),
    CONSTRAINT artifact_versions_content_valid
        CHECK (char_length(btrim(content)) >= 1 AND char_length(content) <= 65536),
    CONSTRAINT artifact_versions_content_hash_valid
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT artifact_versions_base_valid
        CHECK (
            (version = 1 AND base_version IS NULL AND base_hash IS NULL)
            OR
            (
                version > 1
                AND base_version IS NOT NULL
                AND base_hash IS NOT NULL
                AND base_version = version - 1
                AND base_hash ~ '^[0-9a-f]{64}$'
            )
        )
);

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_current_version_fk
    FOREIGN KEY (principal_id, artifact_id, current_version, current_hash)
    REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash)
    DEFERRABLE INITIALLY DEFERRED;
