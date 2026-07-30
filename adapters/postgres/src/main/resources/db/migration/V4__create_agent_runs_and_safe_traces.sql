ALTER TABLE captures
    ADD CONSTRAINT captures_principal_capture_hash_uq
    UNIQUE (principal_id, capture_id, request_hash);

CREATE TABLE agent_runs (
    principal_id VARCHAR(200) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL,
    task_envelope JSONB NOT NULL,
    result_envelope JSONB,
    bundle JSONB,
    bundle_hash CHAR(64),
    trace_root_hash CHAR(64),
    last_event_sequence INTEGER NOT NULL DEFAULT 0,
    resolved_model VARCHAR(512),
    agent_version VARCHAR(200),
    verifier_version VARCHAR(200),
    harness_version VARCHAR(200),
    tool_registry_version VARCHAR(200) NOT NULL,
    policy_version VARCHAR(200) NOT NULL,
    state_version VARCHAR(200) NOT NULL,
    context_policy_version VARCHAR(200) NOT NULL,
    cost_usd NUMERIC(20, 10) NOT NULL DEFAULT 0,
    token_count BIGINT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    failure_attribution VARCHAR(512),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT agent_runs_pk PRIMARY KEY (principal_id, run_id),
    CONSTRAINT agent_runs_identity_valid CHECK (
        char_length(btrim(principal_id)) BETWEEN 1 AND 200
        AND run_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
        AND task_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
    ),
    CONSTRAINT agent_runs_lifecycle_valid CHECK (
        lifecycle_status IN (
            'RUNNING', 'SUCCEEDED', 'FAILED', 'NEEDS_INPUT', 'BLOCKED', 'CANCELLED'
        )
    ),
    CONSTRAINT agent_runs_task_json_valid CHECK (
        jsonb_typeof(task_envelope) = 'object'
        AND octet_length(task_envelope::text) <= 131072
    ),
    CONSTRAINT agent_runs_result_json_valid CHECK (
        result_envelope IS NULL
        OR (
            jsonb_typeof(result_envelope) = 'object'
            AND octet_length(result_envelope::text) <= 131072
        )
    ),
    CONSTRAINT agent_runs_bundle_json_valid CHECK (
        bundle IS NULL
        OR (
            jsonb_typeof(bundle) = 'object'
            AND octet_length(bundle::text) <= 524288
        )
    ),
    CONSTRAINT agent_runs_hashes_valid CHECK (
        (bundle_hash IS NULL OR bundle_hash ~ '^[0-9a-f]{64}$')
        AND (trace_root_hash IS NULL OR trace_root_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT agent_runs_usage_valid CHECK (
        cost_usd >= 0
        AND cost_usd <= 999999.999999
        AND cost_usd = round(cost_usd, 6)
        AND token_count >= 0
        AND token_count <= 9007199254740991
        AND latency_ms >= 0
        AND latency_ms <= 86400000
        AND last_event_sequence BETWEEN 0 AND 128
    ),
    CONSTRAINT agent_runs_terminal_shape CHECK (
        (
            lifecycle_status = 'RUNNING'
            AND result_envelope IS NULL
            AND bundle IS NULL
            AND bundle_hash IS NULL
            AND trace_root_hash IS NULL
            AND last_event_sequence = 0
            AND completed_at IS NULL
        )
        OR
        (
            lifecycle_status <> 'RUNNING'
            AND result_envelope IS NOT NULL
            AND bundle IS NOT NULL
            AND bundle_hash IS NOT NULL
            AND trace_root_hash IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= started_at
        )
    )
);

CREATE INDEX agent_runs_principal_task_started_idx
    ON agent_runs (principal_id, task_id, started_at DESC);

CREATE INDEX agent_runs_principal_started_idx
    ON agent_runs (principal_id, started_at DESC, run_id);

CREATE TABLE agent_trace_events (
    principal_id VARCHAR(200) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    sequence INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    tool_name VARCHAR(200),
    status VARCHAR(100) NOT NULL,
    resource_ref VARCHAR(2048),
    previous_root_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    current_root_hash CHAR(64) NOT NULL,
    CONSTRAINT agent_trace_events_pk PRIMARY KEY (principal_id, run_id, sequence),
    CONSTRAINT agent_trace_events_hash_uq UNIQUE (principal_id, run_id, event_hash),
    CONSTRAINT agent_trace_events_run_fk
        FOREIGN KEY (principal_id, run_id)
        REFERENCES agent_runs (principal_id, run_id)
        ON DELETE CASCADE,
    CONSTRAINT agent_trace_events_sequence_valid CHECK (sequence BETWEEN 1 AND 128),
    CONSTRAINT agent_trace_events_type_valid CHECK (
        event_type IN (
            'MODEL_STEP', 'TOOL_REQUEST', 'TOOL_RESULT', 'TOOL_REJECTED',
            'STRUCTURED_FINAL', 'ARTIFACT_COMMITTED'
        )
    ),
    CONSTRAINT agent_trace_events_tool_shape CHECK (
        (
            event_type IN ('TOOL_REQUEST', 'TOOL_RESULT', 'TOOL_REJECTED')
            AND tool_name IS NOT NULL
            AND char_length(btrim(tool_name)) BETWEEN 1 AND 200
        )
        OR
        (
            event_type NOT IN ('TOOL_REQUEST', 'TOOL_RESULT', 'TOOL_REJECTED')
            AND tool_name IS NULL
        )
    ),
    CONSTRAINT agent_trace_events_status_valid CHECK (
        char_length(btrim(status)) BETWEEN 1 AND 100
    ),
    CONSTRAINT agent_trace_events_ref_valid CHECK (
        resource_ref IS NULL
        OR (
            char_length(btrim(resource_ref)) BETWEEN 1 AND 2048
        )
    ),
    CONSTRAINT agent_trace_events_hashes_valid CHECK (
        previous_root_hash ~ '^[0-9a-f]{64}$'
        AND event_hash ~ '^[0-9a-f]{64}$'
        AND current_root_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE agent_run_resource_bindings (
    principal_id VARCHAR(200) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    ordinal INTEGER NOT NULL,
    resource_ref VARCHAR(2048) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    capture_id VARCHAR(200),
    artifact_id VARCHAR(200),
    artifact_version INTEGER,
    CONSTRAINT agent_run_resource_bindings_pk
        PRIMARY KEY (principal_id, run_id, role, ordinal),
    CONSTRAINT agent_run_resource_bindings_ref_uq
        UNIQUE (principal_id, run_id, role, resource_ref),
    CONSTRAINT agent_run_resource_bindings_run_fk
        FOREIGN KEY (principal_id, run_id)
        REFERENCES agent_runs (principal_id, run_id)
        ON DELETE CASCADE,
    CONSTRAINT agent_run_resource_bindings_artifact_fk
        FOREIGN KEY (principal_id, artifact_id, artifact_version, content_hash)
        REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash),
    CONSTRAINT agent_run_resource_bindings_capture_fk
        FOREIGN KEY (principal_id, capture_id, content_hash)
        REFERENCES captures (principal_id, capture_id, request_hash),
    CONSTRAINT agent_run_resource_bindings_role_valid CHECK (
        role IN ('EVIDENCE', 'ARTIFACT', 'RECEIPT', 'VERIFICATION', 'CHECKPOINT', 'HANDOFF')
    ),
    CONSTRAINT agent_run_resource_bindings_ordinal_valid CHECK (
        ordinal BETWEEN 0 AND 127
    ),
    CONSTRAINT agent_run_resource_bindings_ref_valid CHECK (
        char_length(btrim(resource_ref)) BETWEEN 1 AND 2048
    ),
    CONSTRAINT agent_run_resource_bindings_hash_valid CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT agent_run_resource_bindings_typed_shape CHECK (
        (
            role = 'EVIDENCE'
            AND capture_id IS NOT NULL
            AND artifact_id IS NULL
            AND artifact_version IS NULL
            AND resource_ref = 'capture://' || capture_id
        )
        OR
        (
            role = 'ARTIFACT'
            AND capture_id IS NULL
            AND artifact_id IS NOT NULL
            AND artifact_version IS NOT NULL
            AND artifact_version >= 1
            AND resource_ref =
                'artifact-version://' || artifact_id || '/' || artifact_version::text
        )
        OR
        (
            role NOT IN ('EVIDENCE', 'ARTIFACT')
            AND capture_id IS NULL
            AND artifact_id IS NULL
            AND artifact_version IS NULL
        )
    )
);
