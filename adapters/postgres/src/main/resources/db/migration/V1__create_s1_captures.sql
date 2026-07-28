CREATE TABLE captures (
    principal_id VARCHAR(200) NOT NULL,
    capture_id VARCHAR(200) NOT NULL,
    client_nonce VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(2048) NOT NULL,
    data_class VARCHAR(32) NOT NULL,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT captures_pk PRIMARY KEY (principal_id, capture_id),
    CONSTRAINT captures_principal_nonce_uq UNIQUE (principal_id, client_nonce),
    CONSTRAINT captures_principal_id_valid
        CHECK (char_length(btrim(principal_id)) BETWEEN 1 AND 200),
    CONSTRAINT captures_client_nonce_valid
        CHECK (client_nonce ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT captures_request_hash_valid
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT captures_content_valid
        CHECK (char_length(btrim(content)) >= 1 AND char_length(content) <= 65536),
    CONSTRAINT captures_source_type_valid
        CHECK (source_type IN ('TEXT', 'LINK', 'VOICE_FILE')),
    CONSTRAINT captures_source_ref_valid
        CHECK (char_length(btrim(source_ref)) >= 1 AND char_length(source_ref) <= 2048),
    CONSTRAINT captures_data_class_valid
        CHECK (data_class IN ('PUBLIC', 'PERSONAL'))
);
