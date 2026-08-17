-- V16 completes only the exact-pico request-2 TX-A selected by V15.
--
-- The V8 sequence-13 head, the V13 REQUIRED validation policy and the V15
-- REQUIRED marker remain immutable.  V16 records a separate versioned
-- attribution/event/head overlay.  A staged validation is transaction-local:
-- its deferred guard permits commit only after all four V16 rows form one
-- consumed closure.

CREATE TABLE agent_graph_exact_provider_validations_v16 (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    database_name VARCHAR(63) NOT NULL,
    database_oid OID NOT NULL,
    schema_oid OID NOT NULL,
    attestor_role_oid OID NOT NULL,
    requirement_hash CHAR(64) NOT NULL,
    base_validation_policy_hash CHAR(64) NOT NULL,
    revision VARCHAR(2) NOT NULL,
    session_intent_hash CHAR(64) NOT NULL,
    session_expires_at_epoch_micros BIGINT NOT NULL,
    base_sequence INTEGER NOT NULL,
    base_head_hash CHAR(64) NOT NULL,
    execution_binding_hash CHAR(64) NOT NULL,
    overlay_sequence INTEGER NOT NULL,
    state_version BIGINT NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    provider_actor VARCHAR(200) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    provider_protocol VARCHAR(128) NOT NULL,
    provider_profile_id VARCHAR(200) NOT NULL,
    provider_profile_hash CHAR(64) NOT NULL,
    base_execution_pricing_fingerprint CHAR(64) NOT NULL,
    transport_profile_hash CHAR(64) NOT NULL,
    parser_profile_hash CHAR(64) NOT NULL,
    schema_profile_hash CHAR(64) NOT NULL,
    model_requested VARCHAR(512) NOT NULL,
    model_resolved_hash CHAR(64) NOT NULL,
    model_resolution_profile_hash CHAR(64) NOT NULL,
    pricing_profile_id VARCHAR(200) NOT NULL,
    pricing_provider_id VARCHAR(128) NOT NULL,
    pricing_profile_fingerprint CHAR(64) NOT NULL,
    pricing_source_hash CHAR(64) NOT NULL,
    rate_unit VARCHAR(24) NOT NULL,
    uncached_input_pico_usd_per_token BIGINT NOT NULL,
    cached_input_pico_usd_per_token BIGINT NOT NULL,
    output_pico_usd_per_token BIGINT NOT NULL,
    input_tokens BIGINT NOT NULL,
    cached_input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    reasoning_output_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    observed_cost_pico_usd NUMERIC(38, 0) NOT NULL,
    statement_hash CHAR(64) NOT NULL,
    decision_kind VARCHAR(32) NOT NULL,
    decision_hash CHAR(64) NOT NULL,
    failure_code VARCHAR(64),
    attribution_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    overlay_head_hash CHAR(64) NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    key_fingerprint CHAR(64) NOT NULL,
    validation_nonce UUID NOT NULL,
    challenge_hash CHAR(64) NOT NULL,
    transcript_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    signature BYTEA,
    signature_hash CHAR(64),
    validation_receipt_hash CHAR(64),
    issued_at_epoch_micros BIGINT NOT NULL,
    expires_at_epoch_micros BIGINT NOT NULL,
    validated_at_epoch_micros BIGINT,
    consumed_at_epoch_micros BIGINT,
    CONSTRAINT graph_exact_validation_pk_v16
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT graph_exact_validation_requirement_uq_v16
        UNIQUE (principal_id, requirement_hash),
    CONSTRAINT graph_exact_validation_nonce_uq_v16
        UNIQUE (validation_nonce),
    CONSTRAINT graph_exact_validation_transcript_uq_v16
        UNIQUE (transcript_hash),
    CONSTRAINT graph_exact_validation_receipt_uq_v16
        UNIQUE (validation_receipt_hash),
    CONSTRAINT graph_exact_validation_attempt_fk_v16
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id, attempt_id, manifest_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_base_event_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, base_sequence, base_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_requirement_attempt_fk_v16
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_exact_tx_a_requirements_v15 (
            principal_id, attempt_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_requirement_hash_fk_v16
        FOREIGN KEY (principal_id, requirement_hash)
        REFERENCES agent_graph_exact_tx_a_requirements_v15 (
            principal_id, requirement_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_base_policy_attempt_fk_v16
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_provider_validations (
            principal_id, attempt_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_base_policy_hash_fk_v16
        FOREIGN KEY (principal_id, base_validation_policy_hash)
        REFERENCES agent_graph_provider_validations (
            principal_id, policy_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_session_fk_v16
        FOREIGN KEY (principal_id, session_intent_hash)
        REFERENCES agent_graph_provider_session_intents (
            principal_id, intent_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_key_fk_v16
        FOREIGN KEY (key_id, key_fingerprint)
        REFERENCES agent_graph_provider_validation_keys (
            key_id, key_fingerprint
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_profile_id_fk_v16
        FOREIGN KEY (provider_profile_id)
        REFERENCES agent_graph_provider_profiles_v14 (profile_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_profile_hash_fk_v16
        FOREIGN KEY (provider_profile_hash)
        REFERENCES agent_graph_provider_profiles_v14 (profile_hash)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_validation_identity_v16 CHECK (
        char_length(btrim(principal_id)) BETWEEN 1 AND 200
        AND attempt_id ~ '^[0-9a-f]{64}$'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND protocol_version = 'PICO_OVERLAY_V1'
        AND char_length(database_name) BETWEEN 1 AND 63
        AND database_oid <> 0
        AND schema_oid <> 0
        AND attestor_role_oid <> 0
        AND requirement_hash ~ '^[0-9a-f]{64}$'
        AND base_validation_policy_hash ~ '^[0-9a-f]{64}$'
        AND revision IN ('r1', 'r2', 'r3')
        AND session_intent_hash ~ '^[0-9a-f]{64}$'
        AND session_expires_at_epoch_micros > 0
        AND base_sequence = 13
        AND base_head_hash ~ '^[0-9a-f]{64}$'
        AND execution_binding_hash = manifest_hash
        AND overlay_sequence = 14
        AND state_version = 1
        AND request_ordinal = 2
    ),
    CONSTRAINT graph_exact_validation_hashes_v16 CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND response_hash ~ '^[0-9a-f]{64}$'
        AND provider_profile_hash ~ '^[0-9a-f]{64}$'
        AND base_execution_pricing_fingerprint ~ '^[0-9a-f]{64}$'
        AND transport_profile_hash ~ '^[0-9a-f]{64}$'
        AND parser_profile_hash ~ '^[0-9a-f]{64}$'
        AND schema_profile_hash ~ '^[0-9a-f]{64}$'
        AND model_resolved_hash ~ '^[0-9a-f]{64}$'
        AND model_resolution_profile_hash ~ '^[0-9a-f]{64}$'
        AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND pricing_source_hash ~ '^[0-9a-f]{64}$'
        AND statement_hash ~ '^[0-9a-f]{64}$'
        AND decision_hash ~ '^[0-9a-f]{64}$'
        AND attribution_hash ~ '^[0-9a-f]{64}$'
        AND event_hash ~ '^[0-9a-f]{64}$'
        AND overlay_head_hash ~ '^[0-9a-f]{64}$'
        AND key_fingerprint ~ '^[0-9a-f]{64}$'
        AND challenge_hash ~ '^[0-9a-f]{64}$'
        AND transcript_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT graph_exact_validation_names_v16 CHECK (
        char_length(btrim(provider_actor)) BETWEEN 1 AND 200
        AND provider_id ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND provider_protocol ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND provider_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND model_requested ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        AND pricing_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND pricing_provider_id = provider_id
        AND key_id ~ '^[a-z][a-z0-9._-]{0,99}$'
    ),
    CONSTRAINT graph_exact_validation_usage_v16 CHECK (
        rate_unit = 'PICO_USD_PER_TOKEN'
        AND uncached_input_pico_usd_per_token BETWEEN 1
            AND 9007199254740991
        AND cached_input_pico_usd_per_token BETWEEN 0
            AND uncached_input_pico_usd_per_token
        AND output_pico_usd_per_token BETWEEN 1
            AND 9007199254740991
        AND input_tokens BETWEEN 0 AND 9007199254740991
        AND cached_input_tokens BETWEEN 0 AND input_tokens
        AND output_tokens BETWEEN 0 AND 9007199254740991
        AND reasoning_output_tokens BETWEEN 0 AND output_tokens
        AND total_tokens = input_tokens + output_tokens
        AND reasoning_output_tokens = 0
        AND observed_cost_pico_usd =
            (input_tokens - cached_input_tokens)::NUMERIC
                * uncached_input_pico_usd_per_token
            + cached_input_tokens::NUMERIC
                * cached_input_pico_usd_per_token
            + output_tokens::NUMERIC
                * output_pico_usd_per_token
    ),
    CONSTRAINT graph_exact_validation_decision_v16 CHECK (
        decision_kind = 'STRUCTURED_FINAL'
        AND failure_code IS NULL
    ),
    CONSTRAINT graph_exact_validation_time_v16 CHECK (
        issued_at_epoch_micros > 0
        AND expires_at_epoch_micros > issued_at_epoch_micros
        AND expires_at_epoch_micros < session_expires_at_epoch_micros
    ),
    CONSTRAINT graph_exact_validation_state_v16 CHECK (
        (state = 'STAGED'
         AND signature IS NULL
         AND signature_hash IS NULL
         AND validation_receipt_hash IS NULL
         AND validated_at_epoch_micros IS NULL
         AND consumed_at_epoch_micros IS NULL)
        OR
        (state = 'CONSUMED'
         AND signature IS NOT NULL
         AND octet_length(signature) = 64
         AND signature_hash IS NOT NULL
         AND signature_hash ~ '^[0-9a-f]{64}$'
         AND signature_hash = pg_catalog.encode(
             pg_catalog.sha256(signature), 'hex')
         AND validation_receipt_hash IS NOT NULL
         AND validation_receipt_hash ~ '^[0-9a-f]{64}$'
         AND validated_at_epoch_micros IS NOT NULL
         AND consumed_at_epoch_micros IS NOT NULL
         AND consumed_at_epoch_micros = validated_at_epoch_micros
         AND consumed_at_epoch_micros >= issued_at_epoch_micros
         AND consumed_at_epoch_micros < expires_at_epoch_micros)
    )
);

CREATE TABLE agent_graph_exact_provider_attributions_v16 (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    requirement_hash CHAR(64) NOT NULL,
    base_validation_policy_hash CHAR(64) NOT NULL,
    base_sequence INTEGER NOT NULL,
    base_head_hash CHAR(64) NOT NULL,
    overlay_sequence INTEGER NOT NULL,
    state_version BIGINT NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    provider_actor VARCHAR(200) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    provider_protocol VARCHAR(128) NOT NULL,
    provider_profile_id VARCHAR(200) NOT NULL,
    provider_profile_hash CHAR(64) NOT NULL,
    base_execution_pricing_fingerprint CHAR(64) NOT NULL,
    transport_profile_hash CHAR(64) NOT NULL,
    parser_profile_hash CHAR(64) NOT NULL,
    schema_profile_hash CHAR(64) NOT NULL,
    model_requested VARCHAR(512) NOT NULL,
    model_resolved_hash CHAR(64) NOT NULL,
    model_resolution_profile_hash CHAR(64) NOT NULL,
    pricing_profile_id VARCHAR(200) NOT NULL,
    pricing_provider_id VARCHAR(128) NOT NULL,
    pricing_profile_fingerprint CHAR(64) NOT NULL,
    pricing_source_hash CHAR(64) NOT NULL,
    rate_unit VARCHAR(24) NOT NULL,
    uncached_input_pico_usd_per_token BIGINT NOT NULL,
    cached_input_pico_usd_per_token BIGINT NOT NULL,
    output_pico_usd_per_token BIGINT NOT NULL,
    input_tokens BIGINT NOT NULL,
    cached_input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    reasoning_output_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    observed_cost_pico_usd NUMERIC(38, 0) NOT NULL,
    statement_hash CHAR(64) NOT NULL,
    decision_kind VARCHAR(32) NOT NULL,
    decision_hash CHAR(64) NOT NULL,
    failure_code VARCHAR(64),
    attribution_hash CHAR(64) NOT NULL,
    attributed_at_epoch_micros BIGINT NOT NULL,
    CONSTRAINT graph_exact_attribution_pk_v16
        PRIMARY KEY (principal_id, attempt_id, request_ordinal),
    CONSTRAINT graph_exact_attribution_hash_uq_v16
        UNIQUE (principal_id, attempt_id, request_ordinal,
                attribution_hash),
    CONSTRAINT graph_exact_attribution_response_uq_v16
        UNIQUE (principal_id, attempt_id, request_ordinal,
                response_hash),
    CONSTRAINT graph_exact_attribution_validation_fk_v16
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_exact_provider_validations_v16 (
            principal_id, attempt_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_attribution_attempt_fk_v16
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id, attempt_id, manifest_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_attribution_base_event_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, base_sequence, base_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_attribution_shape_v16 CHECK (
        protocol_version = 'PICO_OVERLAY_V1'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND requirement_hash ~ '^[0-9a-f]{64}$'
        AND base_validation_policy_hash ~ '^[0-9a-f]{64}$'
        AND base_sequence = 13
        AND base_head_hash ~ '^[0-9a-f]{64}$'
        AND overlay_sequence = 14
        AND state_version = 1
        AND request_ordinal = 2
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND response_hash ~ '^[0-9a-f]{64}$'
        AND provider_profile_hash ~ '^[0-9a-f]{64}$'
        AND base_execution_pricing_fingerprint ~ '^[0-9a-f]{64}$'
        AND transport_profile_hash ~ '^[0-9a-f]{64}$'
        AND parser_profile_hash ~ '^[0-9a-f]{64}$'
        AND schema_profile_hash ~ '^[0-9a-f]{64}$'
        AND model_resolved_hash ~ '^[0-9a-f]{64}$'
        AND model_resolution_profile_hash ~ '^[0-9a-f]{64}$'
        AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND pricing_source_hash ~ '^[0-9a-f]{64}$'
        AND statement_hash ~ '^[0-9a-f]{64}$'
        AND decision_hash ~ '^[0-9a-f]{64}$'
        AND attribution_hash ~ '^[0-9a-f]{64}$'
        AND decision_kind = 'STRUCTURED_FINAL'
        AND failure_code IS NULL
        AND rate_unit = 'PICO_USD_PER_TOKEN'
        AND total_tokens = input_tokens + output_tokens
        AND cached_input_tokens BETWEEN 0 AND input_tokens
        AND reasoning_output_tokens = 0
        AND observed_cost_pico_usd =
            (input_tokens - cached_input_tokens)::NUMERIC
                * uncached_input_pico_usd_per_token
            + cached_input_tokens::NUMERIC
                * cached_input_pico_usd_per_token
            + output_tokens::NUMERIC
                * output_pico_usd_per_token
    )
);

CREATE TABLE agent_graph_exact_attempt_events_v16 (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    requirement_hash CHAR(64) NOT NULL,
    base_sequence INTEGER NOT NULL,
    base_head_hash CHAR(64) NOT NULL,
    sequence INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    occurred_at_epoch_micros BIGINT NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    previous_head_hash CHAR(64) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    statement_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    current_head_hash CHAR(64) NOT NULL,
    CONSTRAINT graph_exact_event_pk_v16
        PRIMARY KEY (principal_id, attempt_id, sequence),
    CONSTRAINT graph_exact_event_hash_uq_v16
        UNIQUE (principal_id, attempt_id, sequence, event_hash),
    CONSTRAINT graph_exact_event_head_uq_v16
        UNIQUE (principal_id, attempt_id, sequence, current_head_hash),
    CONSTRAINT graph_exact_event_validation_fk_v16
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_exact_provider_validations_v16 (
            principal_id, attempt_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_event_attribution_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, request_ordinal, evidence_hash
        ) REFERENCES agent_graph_exact_provider_attributions_v16 (
            principal_id, attempt_id, request_ordinal, attribution_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_event_base_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, base_sequence, base_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_event_shape_v16 CHECK (
        protocol_version = 'PICO_OVERLAY_V1'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND requirement_hash ~ '^[0-9a-f]{64}$'
        AND base_sequence = 13
        AND base_head_hash ~ '^[0-9a-f]{64}$'
        AND sequence = 14
        AND event_type = 'EXACT_PROVIDER_ATTRIBUTED'
        AND occurred_at_epoch_micros > 0
        AND request_ordinal = 2
        AND previous_head_hash = base_head_hash
        AND evidence_hash ~ '^[0-9a-f]{64}$'
        AND statement_hash ~ '^[0-9a-f]{64}$'
        AND event_hash ~ '^[0-9a-f]{64}$'
        AND current_head_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE agent_graph_exact_attempt_heads_v16 (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    requirement_hash CHAR(64) NOT NULL,
    base_sequence INTEGER NOT NULL,
    base_head_hash CHAR(64) NOT NULL,
    state_version BIGINT NOT NULL,
    last_sequence INTEGER NOT NULL,
    phase VARCHAR(40) NOT NULL,
    attribution_status VARCHAR(16) NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    base_provider_attribution_count SMALLINT NOT NULL,
    overlay_provider_attribution_count SMALLINT NOT NULL,
    effective_provider_attribution_count SMALLINT NOT NULL,
    statement_hash CHAR(64) NOT NULL,
    attribution_hash CHAR(64) NOT NULL,
    last_event_hash CHAR(64) NOT NULL,
    head_hash CHAR(64) NOT NULL,
    updated_at_epoch_micros BIGINT NOT NULL,
    CONSTRAINT graph_exact_head_pk_v16
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT graph_exact_head_hash_uq_v16
        UNIQUE (principal_id, attempt_id, head_hash),
    CONSTRAINT graph_exact_head_validation_fk_v16
        FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_exact_provider_validations_v16 (
            principal_id, attempt_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_head_attribution_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, request_ordinal, attribution_hash
        ) REFERENCES agent_graph_exact_provider_attributions_v16 (
            principal_id, attempt_id, request_ordinal, attribution_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_head_event_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, last_sequence, head_hash
        ) REFERENCES agent_graph_exact_attempt_events_v16 (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_head_base_fk_v16
        FOREIGN KEY (
            principal_id, attempt_id, base_sequence, base_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_head_shape_v16 CHECK (
        protocol_version = 'PICO_OVERLAY_V1'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND requirement_hash ~ '^[0-9a-f]{64}$'
        AND base_sequence = 13
        AND base_head_hash ~ '^[0-9a-f]{64}$'
        AND state_version = 1
        AND last_sequence = 14
        AND phase = 'EXACT_PROVIDER_ATTRIBUTED'
        AND attribution_status = 'ATTRIBUTED'
        AND request_ordinal = 2
        AND base_provider_attribution_count = 1
        AND overlay_provider_attribution_count = 1
        AND effective_provider_attribution_count = 2
        AND statement_hash ~ '^[0-9a-f]{64}$'
        AND attribution_hash ~ '^[0-9a-f]{64}$'
        AND last_event_hash ~ '^[0-9a-f]{64}$'
        AND head_hash ~ '^[0-9a-f]{64}$'
        AND updated_at_epoch_micros > 0
    )
);

REVOKE ALL ON TABLE
    agent_graph_exact_provider_validations_v16,
    agent_graph_exact_provider_attributions_v16,
    agent_graph_exact_attempt_events_v16,
    agent_graph_exact_attempt_heads_v16
FROM PUBLIC;

CREATE FUNCTION agent_graph_stage_exact_tx_a_v16(payload JSONB)
RETURNS TABLE (
    protocol_version VARCHAR,
    database_name VARCHAR,
    database_oid OID,
    schema_oid OID,
    attestor_role_oid OID,
    principal_id VARCHAR,
    attempt_id CHAR(64),
    manifest_hash CHAR(64),
    requirement_hash CHAR(64),
    base_validation_policy_hash CHAR(64),
    revision VARCHAR,
    session_intent_hash CHAR(64),
    session_expires_at_epoch_micros BIGINT,
    base_sequence INTEGER,
    base_head_hash CHAR(64),
    overlay_sequence INTEGER,
    request_ordinal SMALLINT,
    provider_profile_id VARCHAR,
    provider_profile_hash CHAR(64),
    transport_profile_hash CHAR(64),
    parser_profile_hash CHAR(64),
    schema_profile_hash CHAR(64),
    key_id VARCHAR,
    key_fingerprint CHAR(64),
    validation_nonce UUID,
    issued_at_epoch_micros BIGINT,
    expires_at_epoch_micros BIGINT,
    statement_hash CHAR(64),
    attribution_hash CHAR(64),
    event_hash CHAR(64),
    overlay_head_hash CHAR(64),
    challenge_hash CHAR(64),
    transcript_hash CHAR(64),
    observed_cost_pico_usd NUMERIC(38, 0),
    public_key_der BYTEA,
    state_version BIGINT,
    provider_actor VARCHAR,
    provider_id VARCHAR,
    provider_protocol VARCHAR,
    base_execution_pricing_fingerprint CHAR(64),
    model_requested VARCHAR,
    model_resolved_hash CHAR(64),
    model_resolution_profile_hash CHAR(64),
    pricing_profile_id VARCHAR,
    pricing_provider_id VARCHAR,
    pricing_profile_fingerprint CHAR(64),
    pricing_source_hash CHAR(64),
    rate_unit VARCHAR,
    uncached_input_pico_usd_per_token BIGINT,
    cached_input_pico_usd_per_token BIGINT,
    output_pico_usd_per_token BIGINT,
    input_tokens BIGINT,
    cached_input_tokens BIGINT,
    output_tokens BIGINT,
    reasoning_output_tokens BIGINT,
    total_tokens BIGINT,
    decision_kind VARCHAR,
    decision_hash CHAR(64),
    failure_code VARCHAR,
    attributed_at_epoch_micros BIGINT,
    occurred_at_epoch_micros BIGINT,
    updated_at_epoch_micros BIGINT
)
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_head RECORD;
    requirement public.agent_graph_exact_tx_a_requirements_v15%ROWTYPE;
    base_validation public.agent_graph_provider_validations%ROWTYPE;
    session_intent public.agent_graph_provider_session_intents%ROWTYPE;
    profile public.agent_graph_provider_profiles_v14%ROWTYPE;
    request_intent RECORD;
    attempt_truth RECORD;
    validation_key RECORD;
    target_database_oid OID;
    target_schema_oid OID;
    target_role_oid OID;
    stage_time TIMESTAMPTZ;
    issued_micros BIGINT;
    expires_micros BIGINT;
    session_expires_micros BIGINT;
    key_not_before_micros BIGINT;
    key_not_after_micros BIGINT;
    statement_input BIGINT;
    statement_cached BIGINT;
    statement_output BIGINT;
    statement_reasoning BIGINT;
    statement_total BIGINT;
    computed_cost NUMERIC(38, 0);
    minted_nonce UUID;
    computed_statement_hash CHAR(64);
    computed_attribution_hash CHAR(64);
    computed_event_hash CHAR(64);
    computed_head_hash CHAR(64);
    computed_challenge_hash CHAR(64);
    computed_transcript_hash CHAR(64);
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor_v16' THEN
        RAISE EXCEPTION 'V16 exact TX-A stage authority rejected'
            USING ERRCODE = '42501';
    END IF;
    IF payload IS NULL
       OR pg_catalog.jsonb_typeof(payload) IS DISTINCT FROM 'object'
       OR pg_catalog.pg_column_size(payload) > 16384 THEN
        RAISE EXCEPTION 'V16 exact TX-A stage input is invalid'
            USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('expected_base_sequence'),
                ('expected_base_head_hash'), ('request_ordinal'),
                ('request_hash'), ('response_hash'),
                ('model_resolved_hash'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('decision_kind'), ('decision_hash'), ('failure_code'),
                ('key_id'), ('challenge_ttl_millis')) expected(key)
            EXCEPT
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key)
       OR EXISTS (
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
            EXCEPT
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('expected_base_sequence'),
                ('expected_base_head_hash'), ('request_ordinal'),
                ('request_hash'), ('response_hash'),
                ('model_resolved_hash'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('decision_kind'), ('decision_hash'), ('failure_code'),
                ('key_id'), ('challenge_ttl_millis')) expected(key))
       OR EXISTS (
            SELECT required_string.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('expected_base_head_hash'),
                ('request_hash'), ('response_hash'),
                ('model_resolved_hash'),
                ('decision_kind'), ('decision_hash'),
                ('key_id')) required_string(key)
            WHERE pg_catalog.jsonb_typeof(payload -> required_string.key)
                    IS DISTINCT FROM 'string')
       OR EXISTS (
            SELECT required_number.key
            FROM (VALUES
                ('expected_base_sequence'), ('request_ordinal'),
                ('input_tokens'), ('cached_input_tokens'),
                ('output_tokens'), ('reasoning_output_tokens'),
                ('total_tokens'),
                ('challenge_ttl_millis')) required_number(key)
            WHERE pg_catalog.jsonb_typeof(payload -> required_number.key)
                    IS DISTINCT FROM 'number')
       OR pg_catalog.jsonb_typeof(payload -> 'failure_code')
            IS DISTINCT FROM 'null'
       OR char_length(btrim(payload ->> 'principal_id'))
            NOT BETWEEN 1 AND 200
       OR payload ->> 'attempt_id' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'manifest_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'requirement_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'expected_base_sequence'
            !~ '^(0|[1-9][0-9]{0,8})$'
       OR payload ->> 'expected_base_head_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'request_ordinal'
            !~ '^(0|[1-9][0-9]{0,4})$'
       OR payload ->> 'request_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'response_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'model_resolved_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'input_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'cached_input_tokens'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'output_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'reasoning_output_tokens'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'total_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'decision_kind' IS NULL
       OR payload ->> 'decision_hash' !~ '^[0-9a-f]{64}$'
       OR payload -> 'failure_code' IS DISTINCT FROM 'null'::JSONB
       OR payload ->> 'key_id' !~ '^[a-z][a-z0-9._-]{0,99}$'
       OR payload ->> 'challenge_ttl_millis'
            !~ '^(0|[1-9][0-9]{0,8})$' THEN
        RAISE EXCEPTION 'V16 exact TX-A stage input is invalid'
            USING ERRCODE = '22023';
    END IF;

    statement_input := (payload ->> 'input_tokens')::BIGINT;
    statement_cached := (payload ->> 'cached_input_tokens')::BIGINT;
    statement_output := (payload ->> 'output_tokens')::BIGINT;
    statement_reasoning :=
        (payload ->> 'reasoning_output_tokens')::BIGINT;
    statement_total := (payload ->> 'total_tokens')::BIGINT;

    SELECT head.manifest_hash, head.state_version,
           head.last_sequence, head.head_hash, head.phase,
           head.billing_status, head.provider_intent_count,
           head.provider_attribution_count
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = payload ->> 'principal_id'
      AND head.attempt_id = payload ->> 'attempt_id'
    FOR UPDATE;

    SELECT durable.* INTO STRICT requirement
    FROM public.agent_graph_exact_tx_a_requirements_v15 durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id';
    SELECT durable.* INTO STRICT base_validation
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id';
    SELECT durable.* INTO STRICT session_intent
    FROM public.agent_graph_provider_session_intents durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id';
    SELECT durable.* INTO STRICT profile
    FROM public.agent_graph_provider_profiles_v14 durable
    WHERE durable.profile_id = requirement.provider_profile_id;
    SELECT event.manifest_hash, event.event_type,
           event.request_ordinal, event.request_hash,
           event.model_requested, event.current_head_hash
    INTO STRICT request_intent
    FROM public.agent_graph_attempt_events event
    WHERE event.principal_id = payload ->> 'principal_id'
      AND event.attempt_id = payload ->> 'attempt_id'
      AND event.sequence = 13;
    SELECT attempt.child_actor,
           attempt.pricing_profile_fingerprint
    INTO STRICT attempt_truth
    FROM public.agent_graph_attempts attempt
    WHERE attempt.principal_id = payload ->> 'principal_id'
      AND attempt.attempt_id = payload ->> 'attempt_id';
    SELECT key.public_key_der, key.key_fingerprint,
           key.status, key.not_before, key.not_after
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = base_validation.key_id
      AND key.key_fingerprint = base_validation.key_fingerprint;
    SELECT database.oid INTO STRICT target_database_oid
    FROM pg_catalog.pg_database database
    WHERE database.datname = current_database();
    SELECT namespace.oid INTO STRICT target_schema_oid
    FROM pg_catalog.pg_namespace namespace
    WHERE namespace.nspname = 'public';
    SELECT role.oid INTO STRICT target_role_oid
    FROM pg_catalog.pg_roles role
    WHERE role.rolname = session_user;

    stage_time := pg_catalog.clock_timestamp();
    issued_micros := pg_catalog.floor(
        EXTRACT(epoch FROM stage_time) * 1000000)::BIGINT;
    session_expires_micros := pg_catalog.floor(
        EXTRACT(epoch FROM base_validation.session_expires_at)
            * 1000000)::BIGINT;
    key_not_before_micros := pg_catalog.floor(
        EXTRACT(epoch FROM validation_key.not_before)
            * 1000000)::BIGINT;
    key_not_after_micros := pg_catalog.floor(
        EXTRACT(epoch FROM validation_key.not_after)
            * 1000000)::BIGINT;
    expires_micros := LEAST(
        issued_micros
            + (payload ->> 'challenge_ttl_millis')::BIGINT * 1000,
        session_expires_micros,
        key_not_after_micros,
        profile.pricing_effective_until_epoch_micros);

    IF requirement.requirement_hash
            IS DISTINCT FROM payload ->> 'requirement_hash'
       OR requirement.manifest_hash
            IS DISTINCT FROM payload ->> 'manifest_hash'
       OR requirement.protocol_version <> 'PICO_OVERLAY_V1'
       OR requirement.database_name <> current_database()
       OR requirement.database_oid <> target_database_oid
       OR requirement.schema_oid <> target_schema_oid
       OR requirement.attestor_role_oid IS DISTINCT FROM (
            SELECT role.oid FROM pg_catalog.pg_roles role
            WHERE role.rolname = 'emergeos_provider_attestor_v15')
       OR requirement.base_sequence <> 13
       OR requirement.base_head_hash
            IS DISTINCT FROM locked_head.head_hash
       OR requirement.execution_binding_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR requirement.request_ordinal <> 2
       OR requirement.provider_profile_hash
            IS DISTINCT FROM profile.profile_hash
       OR requirement.state <> 'REQUIRED'
       OR base_validation.state <> 'REQUIRED'
       OR base_validation.manifest_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR base_validation.protocol_version <> 'POSTGRES_ROLE_V1'
       OR base_validation.database_name <> current_database()
       OR base_validation.database_oid <> target_database_oid
       OR base_validation.schema_oid <> target_schema_oid
       OR base_validation.attestor_role_oid IS DISTINCT FROM (
            SELECT role.oid FROM pg_catalog.pg_roles role
            WHERE role.rolname = 'emergeos_provider_attestor')
       OR base_validation.policy_sequence <> 7
       OR base_validation.key_id IS DISTINCT FROM payload ->> 'key_id'
       OR base_validation.challenge_ttl_millis
            <> (payload ->> 'challenge_ttl_millis')::INTEGER
       OR session_intent.manifest_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR session_intent.revision IS DISTINCT FROM base_validation.revision
       OR session_intent.intent_hash
            IS DISTINCT FROM base_validation.session_intent_hash
       OR session_intent.expires_at
            IS DISTINCT FROM base_validation.session_expires_at
       OR locked_head.manifest_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR locked_head.state_version <> 13
       OR locked_head.last_sequence <> 13
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR (payload ->> 'expected_base_sequence')::INTEGER <> 13
       OR payload ->> 'expected_base_head_hash'
            IS DISTINCT FROM locked_head.head_hash
       OR (payload ->> 'request_ordinal')::INTEGER <> 2
       OR request_intent.manifest_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR request_intent.event_type <> 'PROVIDER_INTENT'
       OR request_intent.request_ordinal <> 2
       OR request_intent.request_hash
            IS DISTINCT FROM payload ->> 'request_hash'
       OR request_intent.current_head_hash
            IS DISTINCT FROM locked_head.head_hash
       OR request_intent.model_requested
            IS DISTINCT FROM profile.model_requested
       OR profile.status <> 'ACTIVE'
       OR issued_micros NOT BETWEEN
            profile.pricing_effective_from_epoch_micros
            AND profile.pricing_effective_until_epoch_micros
       OR profile.transport_profile_hash
            IS DISTINCT FROM base_validation.transport_profile_hash
       OR profile.parser_profile_hash
            IS DISTINCT FROM base_validation.parser_profile_hash
       OR profile.schema_profile_hash
            IS DISTINCT FROM base_validation.schema_profile_hash
       OR profile.pricing_provider_id IS DISTINCT FROM profile.provider_id
       OR profile.rate_unit <> 'PICO_USD_PER_TOKEN'
       OR profile.reasoning_policy <> 'NONE'
       OR validation_key.status <> 'ACTIVE'
       OR issued_micros NOT BETWEEN
            key_not_before_micros AND key_not_after_micros
       OR expires_micros <= issued_micros
       OR expires_micros >= session_expires_micros
       OR statement_input NOT BETWEEN 0 AND 9007199254740991
       OR statement_cached NOT BETWEEN 0 AND statement_input
       OR statement_output NOT BETWEEN 0 AND 9007199254740991
       OR statement_reasoning <> 0
       OR statement_total <> statement_input + statement_output
       OR payload ->> 'decision_kind' <> 'STRUCTURED_FINAL'
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_provider_attributions legacy
            WHERE legacy.principal_id = requirement.principal_id
              AND legacy.attempt_id = requirement.attempt_id
              AND legacy.request_ordinal = 2)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_events legacy
            WHERE legacy.principal_id = requirement.principal_id
              AND legacy.attempt_id = requirement.attempt_id
              AND legacy.sequence = 14)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_exact_provider_validations_v16 durable
            WHERE durable.principal_id = requirement.principal_id
              AND durable.attempt_id = requirement.attempt_id) THEN
        RAISE EXCEPTION 'V16 exact TX-A stage was fenced'
            USING ERRCODE = '55000';
    END IF;

    computed_cost :=
        (statement_input - statement_cached)::NUMERIC
            * profile.uncached_input_pico_usd_per_token
        + statement_cached::NUMERIC
            * profile.cached_input_pico_usd_per_token
        + statement_output::NUMERIC
            * profile.output_pico_usd_per_token;
    minted_nonce := pg_catalog.gen_random_uuid();
    computed_statement_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-statement.v16',
        ARRAY[
            requirement.requirement_hash::TEXT,
            base_validation.policy_hash::TEXT,
            requirement.manifest_hash::TEXT,
            '13', locked_head.head_hash::TEXT,
            requirement.execution_binding_hash::TEXT,
            '2', request_intent.request_hash::TEXT,
            payload ->> 'response_hash', profile.provider_id::TEXT,
            profile.provider_protocol::TEXT,
            profile.profile_id::TEXT, profile.profile_hash::TEXT,
            attempt_truth.pricing_profile_fingerprint::TEXT,
            profile.model_requested::TEXT,
            payload ->> 'model_resolved_hash',
            profile.pricing_profile_id::TEXT,
            profile.pricing_provider_id::TEXT,
            profile.pricing_profile_fingerprint::TEXT,
            profile.pricing_source_hash::TEXT,
            profile.rate_unit::TEXT,
            profile.uncached_input_pico_usd_per_token::TEXT,
            profile.cached_input_pico_usd_per_token::TEXT,
            profile.output_pico_usd_per_token::TEXT,
            statement_input::TEXT, statement_cached::TEXT,
            statement_output::TEXT, statement_reasoning::TEXT,
            statement_total::TEXT, computed_cost::TEXT,
            payload ->> 'decision_kind', payload ->> 'decision_hash', ''
        ]);
    computed_attribution_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-provider-attribution.v16',
        ARRAY[
            'PICO_OVERLAY_V1', requirement.principal_id::TEXT,
            requirement.attempt_id::TEXT,
            requirement.manifest_hash::TEXT,
            requirement.requirement_hash::TEXT,
            base_validation.policy_hash::TEXT,
            '13', locked_head.head_hash::TEXT, '14', '1', '2',
            request_intent.request_hash::TEXT,
            payload ->> 'response_hash',
            attempt_truth.child_actor::TEXT,
            profile.provider_id::TEXT, profile.provider_protocol::TEXT,
            profile.profile_id::TEXT, profile.profile_hash::TEXT,
            attempt_truth.pricing_profile_fingerprint::TEXT,
            profile.transport_profile_hash::TEXT,
            profile.parser_profile_hash::TEXT,
            profile.schema_profile_hash::TEXT,
            profile.model_requested::TEXT,
            payload ->> 'model_resolved_hash',
            profile.model_resolution_profile_hash::TEXT,
            profile.pricing_profile_id::TEXT,
            profile.pricing_provider_id::TEXT,
            profile.pricing_profile_fingerprint::TEXT,
            profile.pricing_source_hash::TEXT,
            profile.rate_unit::TEXT,
            profile.uncached_input_pico_usd_per_token::TEXT,
            profile.cached_input_pico_usd_per_token::TEXT,
            profile.output_pico_usd_per_token::TEXT,
            statement_input::TEXT, statement_cached::TEXT,
            statement_output::TEXT, statement_reasoning::TEXT,
            statement_total::TEXT, computed_cost::TEXT,
            computed_statement_hash::TEXT,
            payload ->> 'decision_kind', payload ->> 'decision_hash', '',
            issued_micros::TEXT
        ]);
    computed_event_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-tx-a-event.v16',
        ARRAY[
            'PICO_OVERLAY_V1', requirement.principal_id::TEXT,
            requirement.attempt_id::TEXT,
            requirement.manifest_hash::TEXT,
            requirement.requirement_hash::TEXT,
            '13', locked_head.head_hash::TEXT, '14',
            'EXACT_PROVIDER_ATTRIBUTED', issued_micros::TEXT,
            locked_head.head_hash::TEXT,
            computed_attribution_hash::TEXT,
            computed_statement_hash::TEXT
        ]);
    computed_head_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-tx-a-head.v16',
        ARRAY[
            'PICO_OVERLAY_V1', requirement.principal_id::TEXT,
            requirement.attempt_id::TEXT,
            requirement.manifest_hash::TEXT,
            requirement.requirement_hash::TEXT,
            '13', locked_head.head_hash::TEXT, '1', '14',
            'EXACT_PROVIDER_ATTRIBUTED', 'ATTRIBUTED', '2',
            '1', '1', '2', computed_statement_hash::TEXT,
            computed_attribution_hash::TEXT,
            computed_event_hash::TEXT, issued_micros::TEXT
        ]);
    computed_challenge_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-challenge.v16',
        ARRAY[
            'PICO_OVERLAY_V1'::TEXT, current_database()::TEXT,
            target_database_oid::TEXT, target_schema_oid::TEXT,
            target_role_oid::TEXT, requirement.principal_id::TEXT,
            requirement.attempt_id::TEXT,
            requirement.manifest_hash::TEXT,
            requirement.requirement_hash::TEXT,
            base_validation.policy_hash::TEXT,
            base_validation.key_id::TEXT,
            base_validation.key_fingerprint::TEXT,
            minted_nonce::TEXT, issued_micros::TEXT,
            expires_micros::TEXT
        ]);
    computed_transcript_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-transcript.v16',
        ARRAY[
            computed_challenge_hash::TEXT,
            computed_statement_hash::TEXT,
            computed_attribution_hash::TEXT,
            computed_event_hash::TEXT,
            computed_head_hash::TEXT,
            payload ->> 'decision_kind', payload ->> 'decision_hash', ''
        ]);

    INSERT INTO public.agent_graph_exact_provider_validations_v16 (
        principal_id, attempt_id, manifest_hash, protocol_version,
        database_name, database_oid, schema_oid, attestor_role_oid,
        requirement_hash, base_validation_policy_hash, revision,
        session_intent_hash, session_expires_at_epoch_micros,
        base_sequence, base_head_hash, execution_binding_hash,
        overlay_sequence, state_version, request_ordinal,
        request_hash, response_hash, provider_actor,
        provider_id, provider_protocol,
        provider_profile_id, provider_profile_hash,
        base_execution_pricing_fingerprint,
        transport_profile_hash, parser_profile_hash,
        schema_profile_hash, model_requested, model_resolved_hash,
        model_resolution_profile_hash,
        pricing_profile_id, pricing_provider_id,
        pricing_profile_fingerprint, pricing_source_hash, rate_unit,
        uncached_input_pico_usd_per_token,
        cached_input_pico_usd_per_token,
        output_pico_usd_per_token,
        input_tokens, cached_input_tokens, output_tokens,
        reasoning_output_tokens, total_tokens,
        observed_cost_pico_usd, statement_hash,
        decision_kind, decision_hash, failure_code,
        attribution_hash, event_hash, overlay_head_hash,
        key_id, key_fingerprint, validation_nonce,
        challenge_hash, transcript_hash, state,
        issued_at_epoch_micros, expires_at_epoch_micros
    ) VALUES (
        requirement.principal_id, requirement.attempt_id,
        requirement.manifest_hash, 'PICO_OVERLAY_V1',
        current_database(), target_database_oid,
        target_schema_oid, target_role_oid,
        requirement.requirement_hash, base_validation.policy_hash,
        base_validation.revision, base_validation.session_intent_hash,
        session_expires_micros,
        13, locked_head.head_hash, requirement.execution_binding_hash,
        14, 1, 2, request_intent.request_hash,
        payload ->> 'response_hash', attempt_truth.child_actor,
        profile.provider_id, profile.provider_protocol,
        profile.profile_id, profile.profile_hash,
        attempt_truth.pricing_profile_fingerprint,
        profile.transport_profile_hash, profile.parser_profile_hash,
        profile.schema_profile_hash, profile.model_requested,
        payload ->> 'model_resolved_hash',
        profile.model_resolution_profile_hash,
        profile.pricing_profile_id, profile.pricing_provider_id,
        profile.pricing_profile_fingerprint, profile.pricing_source_hash,
        profile.rate_unit,
        profile.uncached_input_pico_usd_per_token,
        profile.cached_input_pico_usd_per_token,
        profile.output_pico_usd_per_token,
        statement_input, statement_cached, statement_output,
        statement_reasoning, statement_total, computed_cost,
        computed_statement_hash, payload ->> 'decision_kind',
        payload ->> 'decision_hash', NULL,
        computed_attribution_hash, computed_event_hash,
        computed_head_hash, base_validation.key_id,
        base_validation.key_fingerprint, minted_nonce,
        computed_challenge_hash, computed_transcript_hash, 'STAGED',
        issued_micros, expires_micros
    );

    RETURN QUERY
    SELECT durable.protocol_version, durable.database_name,
           durable.database_oid, durable.schema_oid,
           durable.attestor_role_oid, durable.principal_id,
           durable.attempt_id, durable.manifest_hash,
           durable.requirement_hash,
           durable.base_validation_policy_hash, durable.revision,
           durable.session_intent_hash,
           durable.session_expires_at_epoch_micros,
           durable.base_sequence, durable.base_head_hash,
           durable.overlay_sequence, durable.request_ordinal,
           durable.provider_profile_id, durable.provider_profile_hash,
           durable.transport_profile_hash, durable.parser_profile_hash,
           durable.schema_profile_hash, durable.key_id,
           durable.key_fingerprint, durable.validation_nonce,
           durable.issued_at_epoch_micros,
           durable.expires_at_epoch_micros, durable.statement_hash,
           durable.attribution_hash, durable.event_hash,
           durable.overlay_head_hash, durable.challenge_hash,
           durable.transcript_hash, durable.observed_cost_pico_usd,
           validation_key.public_key_der,
           durable.state_version, durable.provider_actor,
           durable.provider_id, durable.provider_protocol,
           durable.base_execution_pricing_fingerprint,
           durable.model_requested, durable.model_resolved_hash,
           durable.model_resolution_profile_hash,
           durable.pricing_profile_id, durable.pricing_provider_id,
           durable.pricing_profile_fingerprint,
           durable.pricing_source_hash, durable.rate_unit,
           durable.uncached_input_pico_usd_per_token,
           durable.cached_input_pico_usd_per_token,
           durable.output_pico_usd_per_token,
           durable.input_tokens, durable.cached_input_tokens,
           durable.output_tokens, durable.reasoning_output_tokens,
           durable.total_tokens, durable.decision_kind,
           durable.decision_hash, durable.failure_code,
           durable.issued_at_epoch_micros,
           durable.issued_at_epoch_micros,
           durable.issued_at_epoch_micros
    FROM public.agent_graph_exact_provider_validations_v16 durable
    WHERE durable.principal_id = requirement.principal_id
      AND durable.attempt_id = requirement.attempt_id;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V16 exact TX-A stage was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_stage_exact_tx_a_v16(JSONB)
FROM PUBLIC;

CREATE FUNCTION agent_graph_commit_exact_tx_a_v16(payload JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_head RECORD;
    validation public.agent_graph_exact_provider_validations_v16%ROWTYPE;
    requirement public.agent_graph_exact_tx_a_requirements_v15%ROWTYPE;
    base_validation public.agent_graph_provider_validations%ROWTYPE;
    profile public.agent_graph_provider_profiles_v14%ROWTYPE;
    validation_key RECORD;
    commit_micros BIGINT;
    computed_signature BYTEA;
    computed_signature_hash CHAR(64);
    computed_receipt_hash CHAR(64);
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor_v16' THEN
        RAISE EXCEPTION 'V16 exact TX-A commit authority rejected'
            USING ERRCODE = '42501';
    END IF;
    IF payload IS NULL
       OR pg_catalog.jsonb_typeof(payload) IS DISTINCT FROM 'object'
       OR pg_catalog.pg_column_size(payload) > 4096 THEN
        RAISE EXCEPTION 'V16 exact TX-A commit input is invalid'
            USING ERRCODE = '22023';
    END IF;
    IF EXISTS (
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('transcript_hash'),
                ('signature_hex')) expected(key)
            EXCEPT
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key)
       OR EXISTS (
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
            EXCEPT
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('transcript_hash'),
                ('signature_hex')) expected(key))
       OR EXISTS (
            SELECT required_string.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('requirement_hash'), ('transcript_hash'),
                ('signature_hex')) required_string(key)
            WHERE pg_catalog.jsonb_typeof(payload -> required_string.key)
                    IS DISTINCT FROM 'string')
       OR char_length(btrim(payload ->> 'principal_id'))
            NOT BETWEEN 1 AND 200
       OR payload ->> 'attempt_id' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'manifest_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'requirement_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'transcript_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'signature_hex' !~ '^[0-9a-f]{128}$' THEN
        RAISE EXCEPTION 'V16 exact TX-A commit input is invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT head.manifest_hash, head.state_version,
           head.last_sequence, head.head_hash, head.phase,
           head.billing_status, head.provider_intent_count,
           head.provider_attribution_count
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = payload ->> 'principal_id'
      AND head.attempt_id = payload ->> 'attempt_id'
    FOR UPDATE;
    SELECT durable.* INTO STRICT validation
    FROM public.agent_graph_exact_provider_validations_v16 durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id'
    FOR UPDATE;
    SELECT durable.* INTO STRICT requirement
    FROM public.agent_graph_exact_tx_a_requirements_v15 durable
    WHERE durable.principal_id = validation.principal_id
      AND durable.attempt_id = validation.attempt_id;
    SELECT durable.* INTO STRICT base_validation
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = validation.principal_id
      AND durable.attempt_id = validation.attempt_id;
    SELECT durable.* INTO STRICT profile
    FROM public.agent_graph_provider_profiles_v14 durable
    WHERE durable.profile_id = validation.provider_profile_id;
    SELECT key.status,
           pg_catalog.floor(EXTRACT(epoch FROM key.not_before)
               * 1000000)::BIGINT AS not_before_micros,
           pg_catalog.floor(EXTRACT(epoch FROM key.not_after)
               * 1000000)::BIGINT AS not_after_micros,
           key.key_fingerprint
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = validation.key_id
      AND key.key_fingerprint = validation.key_fingerprint;

    commit_micros := pg_catalog.floor(
        EXTRACT(epoch FROM pg_catalog.clock_timestamp())
            * 1000000)::BIGINT;
    IF validation.state <> 'STAGED'
       OR validation.manifest_hash
            IS DISTINCT FROM payload ->> 'manifest_hash'
       OR validation.requirement_hash
            IS DISTINCT FROM payload ->> 'requirement_hash'
       OR validation.transcript_hash
            IS DISTINCT FROM payload ->> 'transcript_hash'
       OR validation.protocol_version <> 'PICO_OVERLAY_V1'
       OR validation.database_name <> current_database()
       OR validation.database_oid IS DISTINCT FROM (
            SELECT database.oid FROM pg_catalog.pg_database database
            WHERE database.datname = current_database())
       OR validation.schema_oid IS DISTINCT FROM (
            SELECT namespace.oid FROM pg_catalog.pg_namespace namespace
            WHERE namespace.nspname = 'public')
       OR validation.attestor_role_oid IS DISTINCT FROM (
            SELECT role.oid FROM pg_catalog.pg_roles role
            WHERE role.rolname = session_user)
       OR requirement.requirement_hash
            IS DISTINCT FROM validation.requirement_hash
       OR requirement.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR requirement.base_sequence <> validation.base_sequence
       OR requirement.base_head_hash
            IS DISTINCT FROM validation.base_head_hash
       OR requirement.provider_profile_id
            IS DISTINCT FROM validation.provider_profile_id
       OR requirement.provider_profile_hash
            IS DISTINCT FROM validation.provider_profile_hash
       OR requirement.state <> 'REQUIRED'
       OR base_validation.state <> 'REQUIRED'
       OR base_validation.policy_hash
            IS DISTINCT FROM validation.base_validation_policy_hash
       OR base_validation.session_intent_hash
            IS DISTINCT FROM validation.session_intent_hash
       OR base_validation.key_id IS DISTINCT FROM validation.key_id
       OR base_validation.key_fingerprint
            IS DISTINCT FROM validation.key_fingerprint
       OR profile.profile_hash
            IS DISTINCT FROM validation.provider_profile_hash
       OR profile.status <> 'ACTIVE'
       OR commit_micros NOT BETWEEN
            profile.pricing_effective_from_epoch_micros
            AND profile.pricing_effective_until_epoch_micros
       OR validation_key.status <> 'ACTIVE'
       OR validation_key.key_fingerprint
            IS DISTINCT FROM validation.key_fingerprint
       OR commit_micros NOT BETWEEN validation_key.not_before_micros
                                    AND validation_key.not_after_micros
       OR commit_micros >= validation.expires_at_epoch_micros
       OR commit_micros >= validation.session_expires_at_epoch_micros
       OR locked_head.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR locked_head.state_version <> 13
       OR locked_head.last_sequence <> 13
       OR locked_head.head_hash
            IS DISTINCT FROM validation.base_head_hash
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_provider_attributions legacy
            WHERE legacy.principal_id = validation.principal_id
              AND legacy.attempt_id = validation.attempt_id
              AND legacy.request_ordinal = 2)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_events legacy
            WHERE legacy.principal_id = validation.principal_id
              AND legacy.attempt_id = validation.attempt_id
              AND legacy.sequence = 14)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_exact_provider_attributions_v16 durable
            WHERE durable.principal_id = validation.principal_id
              AND durable.attempt_id = validation.attempt_id)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_exact_attempt_events_v16 durable
            WHERE durable.principal_id = validation.principal_id
              AND durable.attempt_id = validation.attempt_id)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_exact_attempt_heads_v16 durable
            WHERE durable.principal_id = validation.principal_id
              AND durable.attempt_id = validation.attempt_id) THEN
        RAISE EXCEPTION 'V16 exact TX-A commit was fenced'
            USING ERRCODE = '55000';
    END IF;

    computed_signature := pg_catalog.decode(
        payload ->> 'signature_hex', 'hex');
    computed_signature_hash := pg_catalog.encode(
        pg_catalog.sha256(computed_signature), 'hex');
    computed_receipt_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-receipt.v16',
        ARRAY[
            validation.protocol_version::TEXT,
            validation.principal_id::TEXT,
            validation.attempt_id::TEXT,
            validation.manifest_hash::TEXT,
            validation.requirement_hash::TEXT,
            validation.transcript_hash::TEXT,
            computed_signature_hash::TEXT,
            commit_micros::TEXT, commit_micros::TEXT,
            'CONSUMED', validation.statement_hash::TEXT,
            validation.attribution_hash::TEXT,
            validation.event_hash::TEXT,
            validation.overlay_head_hash::TEXT
        ]);

    INSERT INTO public.agent_graph_exact_provider_attributions_v16 (
        principal_id, attempt_id, manifest_hash, protocol_version,
        requirement_hash, base_validation_policy_hash,
        base_sequence, base_head_hash, overlay_sequence,
        state_version, request_ordinal, request_hash, response_hash,
        provider_actor, provider_id, provider_protocol,
        provider_profile_id, provider_profile_hash,
        base_execution_pricing_fingerprint,
        transport_profile_hash, parser_profile_hash,
        schema_profile_hash, model_requested, model_resolved_hash,
        model_resolution_profile_hash,
        pricing_profile_id, pricing_provider_id,
        pricing_profile_fingerprint, pricing_source_hash, rate_unit,
        uncached_input_pico_usd_per_token,
        cached_input_pico_usd_per_token,
        output_pico_usd_per_token,
        input_tokens, cached_input_tokens, output_tokens,
        reasoning_output_tokens, total_tokens,
        observed_cost_pico_usd, statement_hash,
        decision_kind, decision_hash, failure_code,
        attribution_hash, attributed_at_epoch_micros
    ) VALUES (
        validation.principal_id, validation.attempt_id,
        validation.manifest_hash, validation.protocol_version,
        validation.requirement_hash,
        validation.base_validation_policy_hash,
        validation.base_sequence, validation.base_head_hash,
        validation.overlay_sequence, validation.state_version,
        validation.request_ordinal, validation.request_hash,
        validation.response_hash, validation.provider_actor,
        validation.provider_id, validation.provider_protocol,
        validation.provider_profile_id,
        validation.provider_profile_hash,
        validation.base_execution_pricing_fingerprint,
        validation.transport_profile_hash,
        validation.parser_profile_hash,
        validation.schema_profile_hash,
        validation.model_requested, validation.model_resolved_hash,
        validation.model_resolution_profile_hash,
        validation.pricing_profile_id,
        validation.pricing_provider_id,
        validation.pricing_profile_fingerprint,
        validation.pricing_source_hash, validation.rate_unit,
        validation.uncached_input_pico_usd_per_token,
        validation.cached_input_pico_usd_per_token,
        validation.output_pico_usd_per_token,
        validation.input_tokens, validation.cached_input_tokens,
        validation.output_tokens,
        validation.reasoning_output_tokens,
        validation.total_tokens, validation.observed_cost_pico_usd,
        validation.statement_hash, validation.decision_kind,
        validation.decision_hash, validation.failure_code,
        validation.attribution_hash,
        validation.issued_at_epoch_micros
    );
    INSERT INTO public.agent_graph_exact_attempt_events_v16 (
        principal_id, attempt_id, manifest_hash, protocol_version,
        requirement_hash, base_sequence, base_head_hash,
        sequence, event_type, occurred_at_epoch_micros,
        request_ordinal, previous_head_hash,
        evidence_hash, statement_hash, event_hash, current_head_hash
    ) VALUES (
        validation.principal_id, validation.attempt_id,
        validation.manifest_hash, validation.protocol_version,
        validation.requirement_hash, validation.base_sequence,
        validation.base_head_hash, validation.overlay_sequence,
        'EXACT_PROVIDER_ATTRIBUTED',
        validation.issued_at_epoch_micros,
        validation.request_ordinal, validation.base_head_hash,
        validation.attribution_hash, validation.statement_hash,
        validation.event_hash, validation.overlay_head_hash
    );
    INSERT INTO public.agent_graph_exact_attempt_heads_v16 (
        principal_id, attempt_id, manifest_hash, protocol_version,
        requirement_hash, base_sequence, base_head_hash,
        state_version, last_sequence, phase, attribution_status,
        request_ordinal, base_provider_attribution_count,
        overlay_provider_attribution_count,
        effective_provider_attribution_count,
        statement_hash, attribution_hash, last_event_hash,
        head_hash, updated_at_epoch_micros
    ) VALUES (
        validation.principal_id, validation.attempt_id,
        validation.manifest_hash, validation.protocol_version,
        validation.requirement_hash, validation.base_sequence,
        validation.base_head_hash, validation.state_version,
        validation.overlay_sequence, 'EXACT_PROVIDER_ATTRIBUTED',
        'ATTRIBUTED', validation.request_ordinal, 1, 1, 2,
        validation.statement_hash, validation.attribution_hash,
        validation.event_hash, validation.overlay_head_hash,
        validation.issued_at_epoch_micros
    );
    UPDATE public.agent_graph_exact_provider_validations_v16 durable
    SET state = 'CONSUMED',
        signature = computed_signature,
        signature_hash = computed_signature_hash,
        validation_receipt_hash = computed_receipt_hash,
        validated_at_epoch_micros = commit_micros,
        consumed_at_epoch_micros = commit_micros
    WHERE durable.principal_id = validation.principal_id
      AND durable.attempt_id = validation.attempt_id
      AND durable.state = 'STAGED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V16 exact TX-A commit was fenced'
            USING ERRCODE = '55000';
    END IF;

    SET CONSTRAINTS ALL IMMEDIATE;
    RETURN pg_catalog.jsonb_build_object(
        'protocol_version', validation.protocol_version,
        'sequence', validation.overlay_sequence,
        'state_version', validation.state_version,
        'head_hash', validation.overlay_head_hash,
        'statement_hash', validation.statement_hash,
        'attribution_hash', validation.attribution_hash,
        'event_hash', validation.event_hash,
        'transcript_hash', validation.transcript_hash,
        'validation_receipt_hash', computed_receipt_hash,
        'validation_state', 'CONSUMED');
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V16 exact TX-A commit was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_commit_exact_tx_a_v16(JSONB)
FROM PUBLIC;

CREATE FUNCTION agent_graph_assert_exact_tx_a_overlay_v16()
RETURNS TRIGGER
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    checked_principal VARCHAR(200);
    checked_attempt CHAR(64);
    validation public.agent_graph_exact_provider_validations_v16%ROWTYPE;
    attribution public.agent_graph_exact_provider_attributions_v16%ROWTYPE;
    exact_event public.agent_graph_exact_attempt_events_v16%ROWTYPE;
    exact_head public.agent_graph_exact_attempt_heads_v16%ROWTYPE;
    locked_head RECORD;
    requirement public.agent_graph_exact_tx_a_requirements_v15%ROWTYPE;
    base_validation public.agent_graph_provider_validations%ROWTYPE;
    session_intent public.agent_graph_provider_session_intents%ROWTYPE;
    profile public.agent_graph_provider_profiles_v14%ROWTYPE;
    validation_key RECORD;
    expected_statement CHAR(64);
    expected_attribution CHAR(64);
    expected_event CHAR(64);
    expected_head CHAR(64);
    expected_challenge CHAR(64);
    expected_transcript CHAR(64);
    expected_receipt CHAR(64);
BEGIN
    IF TG_TABLE_NAME = 'agent_graph_exact_provider_validations_v16' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
                USING ERRCODE = '55000';
        ELSIF TG_OP = 'INSERT' AND NEW.state <> 'STAGED' THEN
            RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
                USING ERRCODE = '55000';
        ELSIF TG_OP = 'UPDATE'
              AND (OLD.state <> 'STAGED'
                   OR NEW.state <> 'CONSUMED'
                   OR (pg_catalog.to_jsonb(NEW) - ARRAY[
                         'state', 'signature', 'signature_hash',
                         'validation_receipt_hash',
                         'validated_at_epoch_micros',
                         'consumed_at_epoch_micros']::TEXT[])
                      IS DISTINCT FROM
                      (pg_catalog.to_jsonb(OLD) - ARRAY[
                         'state', 'signature', 'signature_hash',
                         'validation_receipt_hash',
                         'validated_at_epoch_micros',
                         'consumed_at_epoch_micros']::TEXT[])) THEN
            RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
                USING ERRCODE = '55000';
        END IF;
    ELSIF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
            USING ERRCODE = '55000';
    END IF;

    checked_principal := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.principal_id ELSE NEW.principal_id END;
    checked_attempt := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.attempt_id ELSE NEW.attempt_id END;

    SELECT durable.* INTO STRICT validation
    FROM public.agent_graph_exact_provider_validations_v16 durable
    WHERE durable.principal_id = checked_principal
      AND durable.attempt_id = checked_attempt;
    IF validation.state = 'STAGED' THEN
        IF EXISTS (
              SELECT 1
              FROM public.agent_graph_exact_provider_attributions_v16 row
              WHERE row.principal_id = checked_principal
                AND row.attempt_id = checked_attempt)
           OR EXISTS (
              SELECT 1
              FROM public.agent_graph_exact_attempt_events_v16 row
              WHERE row.principal_id = checked_principal
                AND row.attempt_id = checked_attempt)
           OR EXISTS (
              SELECT 1
              FROM public.agent_graph_exact_attempt_heads_v16 row
              WHERE row.principal_id = checked_principal
                AND row.attempt_id = checked_attempt) THEN
            RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
                USING ERRCODE = '55000';
        END IF;
        RAISE EXCEPTION 'V16 exact TX-A stage was not consumed'
            USING ERRCODE = '55000';
    ELSIF validation.state <> 'CONSUMED' THEN
        RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
            USING ERRCODE = '55000';
    END IF;

    SELECT row.* INTO STRICT attribution
    FROM public.agent_graph_exact_provider_attributions_v16 row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT row.* INTO STRICT exact_event
    FROM public.agent_graph_exact_attempt_events_v16 row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT row.* INTO STRICT exact_head
    FROM public.agent_graph_exact_attempt_heads_v16 row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT head.manifest_hash, head.state_version,
           head.last_sequence, head.head_hash, head.phase,
           head.billing_status, head.provider_intent_count,
           head.provider_attribution_count
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = checked_principal
      AND head.attempt_id = checked_attempt
    FOR UPDATE;
    SELECT row.* INTO STRICT requirement
    FROM public.agent_graph_exact_tx_a_requirements_v15 row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT row.* INTO STRICT base_validation
    FROM public.agent_graph_provider_validations row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT row.* INTO STRICT session_intent
    FROM public.agent_graph_provider_session_intents row
    WHERE row.principal_id = checked_principal
      AND row.attempt_id = checked_attempt;
    SELECT row.* INTO STRICT profile
    FROM public.agent_graph_provider_profiles_v14 row
    WHERE row.profile_id = validation.provider_profile_id;
    SELECT key.status,
           pg_catalog.floor(EXTRACT(epoch FROM key.not_before)
               * 1000000)::BIGINT AS not_before_micros,
           pg_catalog.floor(EXTRACT(epoch FROM key.not_after)
               * 1000000)::BIGINT AS not_after_micros,
           key.key_fingerprint
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = validation.key_id
      AND key.key_fingerprint = validation.key_fingerprint;

    IF locked_head.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR locked_head.state_version <> 13
       OR locked_head.last_sequence <> 13
       OR locked_head.head_hash
            IS DISTINCT FROM validation.base_head_hash
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR requirement.requirement_hash
            IS DISTINCT FROM validation.requirement_hash
       OR requirement.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR requirement.base_sequence <> validation.base_sequence
       OR requirement.base_head_hash
            IS DISTINCT FROM validation.base_head_hash
       OR requirement.provider_profile_id
            IS DISTINCT FROM validation.provider_profile_id
       OR requirement.provider_profile_hash
            IS DISTINCT FROM validation.provider_profile_hash
       OR requirement.state <> 'REQUIRED'
       OR base_validation.policy_hash
            IS DISTINCT FROM validation.base_validation_policy_hash
       OR base_validation.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR base_validation.state <> 'REQUIRED'
       OR base_validation.revision IS DISTINCT FROM validation.revision
       OR base_validation.session_intent_hash
            IS DISTINCT FROM validation.session_intent_hash
       OR base_validation.key_id IS DISTINCT FROM validation.key_id
       OR base_validation.key_fingerprint
            IS DISTINCT FROM validation.key_fingerprint
       OR session_intent.intent_hash
            IS DISTINCT FROM validation.session_intent_hash
       OR session_intent.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR session_intent.revision IS DISTINCT FROM validation.revision
       OR pg_catalog.floor(EXTRACT(epoch FROM session_intent.expires_at)
            * 1000000)::BIGINT
            <> validation.session_expires_at_epoch_micros
       OR profile.profile_hash
            IS DISTINCT FROM validation.provider_profile_hash
       OR profile.status <> 'ACTIVE'
       OR profile.provider_id IS DISTINCT FROM validation.provider_id
       OR profile.provider_protocol
            IS DISTINCT FROM validation.provider_protocol
       OR profile.transport_profile_hash
            IS DISTINCT FROM validation.transport_profile_hash
       OR profile.parser_profile_hash
            IS DISTINCT FROM validation.parser_profile_hash
       OR profile.schema_profile_hash
            IS DISTINCT FROM validation.schema_profile_hash
       OR profile.model_requested
            IS DISTINCT FROM validation.model_requested
       OR profile.model_resolution_profile_hash
            IS DISTINCT FROM validation.model_resolution_profile_hash
       OR profile.pricing_profile_id
            IS DISTINCT FROM validation.pricing_profile_id
       OR profile.pricing_provider_id
            IS DISTINCT FROM validation.pricing_provider_id
       OR profile.pricing_profile_fingerprint
            IS DISTINCT FROM validation.pricing_profile_fingerprint
       OR profile.pricing_source_hash
            IS DISTINCT FROM validation.pricing_source_hash
       OR profile.rate_unit IS DISTINCT FROM validation.rate_unit
       OR profile.uncached_input_pico_usd_per_token
            <> validation.uncached_input_pico_usd_per_token
       OR profile.cached_input_pico_usd_per_token
            <> validation.cached_input_pico_usd_per_token
       OR profile.output_pico_usd_per_token
            <> validation.output_pico_usd_per_token
       OR validation.validated_at_epoch_micros NOT BETWEEN
            profile.pricing_effective_from_epoch_micros
            AND profile.pricing_effective_until_epoch_micros
       OR validation_key.key_fingerprint
            IS DISTINCT FROM validation.key_fingerprint
       OR validation_key.status <> 'ACTIVE'
       OR validation.validated_at_epoch_micros NOT BETWEEN
            validation_key.not_before_micros
            AND validation_key.not_after_micros
       OR validation.validated_at_epoch_micros
            >= validation.session_expires_at_epoch_micros
       OR validation.database_name <> current_database()
       OR validation.database_oid IS DISTINCT FROM (
            SELECT database.oid FROM pg_catalog.pg_database database
            WHERE database.datname = current_database())
       OR validation.schema_oid IS DISTINCT FROM (
            SELECT namespace.oid FROM pg_catalog.pg_namespace namespace
            WHERE namespace.nspname = 'public')
       OR validation.attestor_role_oid IS DISTINCT FROM (
            SELECT role.oid FROM pg_catalog.pg_roles role
            WHERE role.rolname = 'emergeos_provider_attestor_v16')
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_provider_attributions legacy
            WHERE legacy.principal_id = checked_principal
              AND legacy.attempt_id = checked_attempt
              AND legacy.request_ordinal = 2)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_events legacy
            WHERE legacy.principal_id = checked_principal
              AND legacy.attempt_id = checked_attempt
              AND legacy.sequence = 14) THEN
        RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
            USING ERRCODE = '55000';
    END IF;

    expected_statement := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-statement.v16',
        ARRAY[
            validation.requirement_hash::TEXT,
            validation.base_validation_policy_hash::TEXT,
            validation.manifest_hash::TEXT,
            validation.base_sequence::TEXT,
            validation.base_head_hash::TEXT,
            validation.execution_binding_hash::TEXT,
            validation.request_ordinal::TEXT,
            validation.request_hash::TEXT,
            validation.response_hash::TEXT,
            validation.provider_id::TEXT,
            validation.provider_protocol::TEXT,
            validation.provider_profile_id::TEXT,
            validation.provider_profile_hash::TEXT,
            validation.base_execution_pricing_fingerprint::TEXT,
            validation.model_requested::TEXT,
            validation.model_resolved_hash::TEXT,
            validation.pricing_profile_id::TEXT,
            validation.pricing_provider_id::TEXT,
            validation.pricing_profile_fingerprint::TEXT,
            validation.pricing_source_hash::TEXT,
            validation.rate_unit::TEXT,
            validation.uncached_input_pico_usd_per_token::TEXT,
            validation.cached_input_pico_usd_per_token::TEXT,
            validation.output_pico_usd_per_token::TEXT,
            validation.input_tokens::TEXT,
            validation.cached_input_tokens::TEXT,
            validation.output_tokens::TEXT,
            validation.reasoning_output_tokens::TEXT,
            validation.total_tokens::TEXT,
            validation.observed_cost_pico_usd::TEXT,
            validation.decision_kind::TEXT,
            validation.decision_hash::TEXT,
            COALESCE(validation.failure_code, '')
        ]);
    expected_attribution := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-provider-attribution.v16',
        ARRAY[
            attribution.protocol_version::TEXT,
            attribution.principal_id::TEXT,
            attribution.attempt_id::TEXT,
            attribution.manifest_hash::TEXT,
            attribution.requirement_hash::TEXT,
            attribution.base_validation_policy_hash::TEXT,
            attribution.base_sequence::TEXT,
            attribution.base_head_hash::TEXT,
            attribution.overlay_sequence::TEXT,
            attribution.state_version::TEXT,
            attribution.request_ordinal::TEXT,
            attribution.request_hash::TEXT,
            attribution.response_hash::TEXT,
            attribution.provider_actor::TEXT,
            attribution.provider_id::TEXT,
            attribution.provider_protocol::TEXT,
            attribution.provider_profile_id::TEXT,
            attribution.provider_profile_hash::TEXT,
            attribution.base_execution_pricing_fingerprint::TEXT,
            attribution.transport_profile_hash::TEXT,
            attribution.parser_profile_hash::TEXT,
            attribution.schema_profile_hash::TEXT,
            attribution.model_requested::TEXT,
            attribution.model_resolved_hash::TEXT,
            attribution.model_resolution_profile_hash::TEXT,
            attribution.pricing_profile_id::TEXT,
            attribution.pricing_provider_id::TEXT,
            attribution.pricing_profile_fingerprint::TEXT,
            attribution.pricing_source_hash::TEXT,
            attribution.rate_unit::TEXT,
            attribution.uncached_input_pico_usd_per_token::TEXT,
            attribution.cached_input_pico_usd_per_token::TEXT,
            attribution.output_pico_usd_per_token::TEXT,
            attribution.input_tokens::TEXT,
            attribution.cached_input_tokens::TEXT,
            attribution.output_tokens::TEXT,
            attribution.reasoning_output_tokens::TEXT,
            attribution.total_tokens::TEXT,
            attribution.observed_cost_pico_usd::TEXT,
            attribution.statement_hash::TEXT,
            attribution.decision_kind::TEXT,
            attribution.decision_hash::TEXT,
            COALESCE(attribution.failure_code, ''),
            attribution.attributed_at_epoch_micros::TEXT
        ]);
    expected_event := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-tx-a-event.v16',
        ARRAY[
            exact_event.protocol_version::TEXT,
            exact_event.principal_id::TEXT,
            exact_event.attempt_id::TEXT,
            exact_event.manifest_hash::TEXT,
            exact_event.requirement_hash::TEXT,
            exact_event.base_sequence::TEXT,
            exact_event.base_head_hash::TEXT,
            exact_event.sequence::TEXT,
            exact_event.event_type::TEXT,
            exact_event.occurred_at_epoch_micros::TEXT,
            exact_event.previous_head_hash::TEXT,
            exact_event.evidence_hash::TEXT,
            exact_event.statement_hash::TEXT
        ]);
    expected_head := public.agent_graph_framed_sha256_v14(
        'emergeos.graph-exact-tx-a-head.v16',
        ARRAY[
            exact_head.protocol_version::TEXT,
            exact_head.principal_id::TEXT,
            exact_head.attempt_id::TEXT,
            exact_head.manifest_hash::TEXT,
            exact_head.requirement_hash::TEXT,
            exact_head.base_sequence::TEXT,
            exact_head.base_head_hash::TEXT,
            exact_head.state_version::TEXT,
            exact_head.last_sequence::TEXT,
            exact_head.phase::TEXT,
            exact_head.attribution_status::TEXT,
            exact_head.request_ordinal::TEXT,
            exact_head.base_provider_attribution_count::TEXT,
            exact_head.overlay_provider_attribution_count::TEXT,
            exact_head.effective_provider_attribution_count::TEXT,
            exact_head.statement_hash::TEXT,
            exact_head.attribution_hash::TEXT,
            exact_head.last_event_hash::TEXT,
            exact_head.updated_at_epoch_micros::TEXT
        ]);
    expected_challenge := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-challenge.v16',
        ARRAY[
            validation.protocol_version::TEXT,
            validation.database_name::TEXT,
            validation.database_oid::TEXT,
            validation.schema_oid::TEXT,
            validation.attestor_role_oid::TEXT,
            validation.principal_id::TEXT,
            validation.attempt_id::TEXT,
            validation.manifest_hash::TEXT,
            validation.requirement_hash::TEXT,
            validation.base_validation_policy_hash::TEXT,
            validation.key_id::TEXT,
            validation.key_fingerprint::TEXT,
            validation.validation_nonce::TEXT,
            validation.issued_at_epoch_micros::TEXT,
            validation.expires_at_epoch_micros::TEXT
        ]);
    expected_transcript := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-transcript.v16',
        ARRAY[
            validation.challenge_hash::TEXT,
            validation.statement_hash::TEXT,
            validation.attribution_hash::TEXT,
            validation.event_hash::TEXT,
            validation.overlay_head_hash::TEXT,
            validation.decision_kind::TEXT,
            validation.decision_hash::TEXT,
            COALESCE(validation.failure_code, '')
        ]);
    expected_receipt := public.agent_graph_framed_sha256_v14(
        'emergeos.exact-provider-validation-receipt.v16',
        ARRAY[
            validation.protocol_version::TEXT,
            validation.principal_id::TEXT,
            validation.attempt_id::TEXT,
            validation.manifest_hash::TEXT,
            validation.requirement_hash::TEXT,
            validation.transcript_hash::TEXT,
            validation.signature_hash::TEXT,
            validation.validated_at_epoch_micros::TEXT,
            validation.consumed_at_epoch_micros::TEXT,
            validation.state::TEXT,
            validation.statement_hash::TEXT,
            validation.attribution_hash::TEXT,
            validation.event_hash::TEXT,
            validation.overlay_head_hash::TEXT
        ]);

    IF expected_statement IS DISTINCT FROM validation.statement_hash
       OR expected_attribution
            IS DISTINCT FROM validation.attribution_hash
       OR expected_event IS DISTINCT FROM validation.event_hash
       OR expected_head IS DISTINCT FROM validation.overlay_head_hash
       OR expected_challenge IS DISTINCT FROM validation.challenge_hash
       OR expected_transcript IS DISTINCT FROM validation.transcript_hash
       OR expected_receipt
            IS DISTINCT FROM validation.validation_receipt_hash
       OR attribution.attribution_hash
            IS DISTINCT FROM validation.attribution_hash
       OR attribution.statement_hash
            IS DISTINCT FROM validation.statement_hash
       OR exact_event.event_hash IS DISTINCT FROM validation.event_hash
       OR exact_event.current_head_hash
            IS DISTINCT FROM validation.overlay_head_hash
       OR exact_event.evidence_hash
            IS DISTINCT FROM validation.attribution_hash
       OR exact_head.head_hash
            IS DISTINCT FROM validation.overlay_head_hash
       OR exact_head.last_event_hash
            IS DISTINCT FROM validation.event_hash
       OR exact_head.attribution_hash
            IS DISTINCT FROM validation.attribution_hash
       OR exact_head.statement_hash
            IS DISTINCT FROM validation.statement_hash
       OR attribution.attributed_at_epoch_micros
            <> validation.issued_at_epoch_micros
       OR exact_event.occurred_at_epoch_micros
            <> validation.issued_at_epoch_micros
       OR exact_head.updated_at_epoch_micros
            <> validation.issued_at_epoch_micros THEN
        RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
            USING ERRCODE = '55000';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V16 exact TX-A overlay was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_assert_exact_tx_a_overlay_v16()
FROM PUBLIC;

CREATE CONSTRAINT TRIGGER agent_graph_exact_provider_validation_v16
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_exact_provider_validations_v16
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_overlay_v16();

CREATE CONSTRAINT TRIGGER agent_graph_exact_provider_attribution_v16
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_exact_provider_attributions_v16
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_overlay_v16();

CREATE CONSTRAINT TRIGGER agent_graph_exact_attempt_event_v16
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_exact_attempt_events_v16
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_overlay_v16();

CREATE CONSTRAINT TRIGGER agent_graph_exact_attempt_head_v16
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_exact_attempt_heads_v16
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_overlay_v16();
