CREATE TABLE action_attempts (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id VARCHAR(200) NOT NULL,
    connector VARCHAR(200) NOT NULL,
    account_ref VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_version INTEGER NOT NULL,
    capability_used_calls INTEGER NOT NULL,
    capability_max_calls INTEGER NOT NULL,
    plan_id VARCHAR(200) NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    action_type VARCHAR(200) NOT NULL,
    target_ref VARCHAR(200) NOT NULL,
    artifact_id VARCHAR(200) NOT NULL,
    artifact_version INTEGER NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    risk VARCHAR(32) NOT NULL,
    policy_version VARCHAR(200) NOT NULL,
    plan_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    approval_id VARCHAR(200) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    capability_id VARCHAR(200) NOT NULL,
    capability_subject VARCHAR(200) NOT NULL,
    capability_connector VARCHAR(200) NOT NULL,
    capability_audience VARCHAR(200) NOT NULL,
    capability_account_ref VARCHAR(200) NOT NULL,
    capability_plan_id VARCHAR(200) NOT NULL,
    capability_plan_hash CHAR(64) NOT NULL,
    capability_artifact_hash CHAR(64) NOT NULL,
    capability_idempotency_key VARCHAR(200) NOT NULL,
    capability_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT action_attempts_pk PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT action_attempts_connector_account_key_uq
        UNIQUE (connector, account_ref, idempotency_key),
    CONSTRAINT action_attempts_state_identity_uq
        UNIQUE (principal_id, attempt_id, state_version, status),
    CONSTRAINT action_attempts_terminal_identity_uq
        UNIQUE (principal_id, attempt_id, status),
    CONSTRAINT action_attempts_artifact_version_fk
        FOREIGN KEY (principal_id, artifact_id, artifact_version, artifact_hash)
        REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash),
    CONSTRAINT action_attempts_status_valid
        CHECK (status IN (
            'PLANNED', 'DISPATCHING', 'UNKNOWN',
            'RECONCILING', 'SUCCEEDED', 'FAILED'
        )),
    CONSTRAINT action_attempts_state_version_valid CHECK (state_version >= 1),
    CONSTRAINT action_attempts_budget_valid
        CHECK (
            capability_max_calls >= 1
            AND capability_used_calls BETWEEN 0 AND capability_max_calls
            AND (
                (status = 'PLANNED' AND capability_used_calls = 0)
                OR (status <> 'PLANNED' AND capability_used_calls >= 1)
            )
        ),
    CONSTRAINT action_attempts_hashes_valid
        CHECK (
            plan_hash ~ '^[0-9a-f]{64}$'
            AND artifact_hash ~ '^[0-9a-f]{64}$'
            AND capability_plan_hash ~ '^[0-9a-f]{64}$'
            AND capability_artifact_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT action_attempts_artifact_version_valid CHECK (artifact_version >= 1),
    CONSTRAINT action_attempts_risk_valid
        CHECK (risk IN ('READ_ONLY', 'REVERSIBLE', 'EXTERNAL', 'IRREVERSIBLE')),
    CONSTRAINT action_attempts_exact_capability_binding
        CHECK (
            capability_subject = principal_id
            AND capability_connector = connector
            AND capability_account_ref = account_ref
            AND capability_plan_id = plan_id
            AND capability_plan_hash = plan_hash
            AND capability_artifact_hash = artifact_hash
            AND capability_idempotency_key = idempotency_key
            AND capability_expires_at = plan_expires_at
        ),
    CONSTRAINT action_attempts_approval_binding
        CHECK (char_length(btrim(approval_id)) >= 1 AND approved_at <= plan_expires_at),
    CONSTRAINT action_attempts_timestamps_valid CHECK (created_at <= updated_at)
);

CREATE TABLE action_attempt_transitions (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id VARCHAR(200) NOT NULL,
    sequence INTEGER NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    capability_use_delta INTEGER NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT action_attempt_transitions_pk
        PRIMARY KEY (principal_id, attempt_id, sequence),
    CONSTRAINT action_attempt_transitions_state_uq
        UNIQUE (principal_id, attempt_id, sequence, to_status),
    CONSTRAINT action_attempt_transitions_attempt_fk
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES action_attempts (principal_id, attempt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT action_attempt_transitions_state_valid
        CHECK (
            (
                sequence = 1
                AND from_status IS NULL
                AND to_status = 'PLANNED'
                AND capability_use_delta = 0
            )
            OR
            (
                sequence > 1
                AND from_status IS NOT NULL
                AND (
                    (from_status = 'PLANNED' AND to_status = 'DISPATCHING')
                    OR
                    (
                        from_status = 'DISPATCHING'
                        AND to_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                    )
                    OR (from_status = 'UNKNOWN' AND to_status = 'RECONCILING')
                    OR
                    (
                        from_status = 'RECONCILING'
                        AND to_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                    )
                )
                AND (
                    (
                        to_status IN ('DISPATCHING', 'RECONCILING')
                        AND capability_use_delta = 1
                    )
                    OR
                    (
                        to_status NOT IN ('DISPATCHING', 'RECONCILING')
                        AND capability_use_delta = 0
                    )
                )
            )
        )
);

ALTER TABLE action_attempts
    ADD CONSTRAINT action_attempts_current_transition_fk
    FOREIGN KEY (principal_id, attempt_id, state_version, status)
    REFERENCES action_attempt_transitions (principal_id, attempt_id, sequence, to_status)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE action_receipts (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id VARCHAR(200) NOT NULL,
    receipt_id VARCHAR(200) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    external_id VARCHAR(1000),
    reason_code VARCHAR(1000),
    provider_request_id VARCHAR(1000) NOT NULL,
    raw_response_ref VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    simulated BOOLEAN NOT NULL,
    CONSTRAINT action_receipts_pk PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT action_receipts_receipt_id_uq UNIQUE (receipt_id),
    CONSTRAINT action_receipts_terminal_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, outcome)
        REFERENCES action_attempts (principal_id, attempt_id, status)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT action_receipts_outcome_valid
        CHECK (
            (
                outcome = 'SUCCEEDED'
                AND external_id IS NOT NULL
                AND char_length(btrim(external_id)) >= 1
                AND reason_code IS NULL
            )
            OR
            (
                outcome = 'FAILED'
                AND external_id IS NULL
                AND reason_code IS NOT NULL
                AND char_length(btrim(reason_code)) >= 1
            )
        ),
    CONSTRAINT action_receipts_simulated_only CHECK (simulated)
);
