-- V13 introduces an explicit, forward-only provider-validation policy.
-- Historical V1-V12 graph rows are not backfilled or updated. A request-2
-- transition is validation-required only after the dedicated attestor enrolls
-- that exact attempt. Raw response bytes and private keys remain outside
-- PostgreSQL; only a public anchor and bounded receipt fields are durable.

CREATE FUNCTION agent_graph_framed_sha256_v13(
    hash_domain VARCHAR,
    hash_fields TEXT[]
)
RETURNS CHAR(64)
LANGUAGE plpgsql
IMMUTABLE
STRICT
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    framed BYTEA;
    field_value TEXT;
    field_bytes BYTEA;
BEGIN
    IF hash_domain !~ '^emergeos\.[a-z0-9][a-z0-9.-]{0,126}\.v[1-9][0-9]*$'
       OR pg_catalog.cardinality(hash_fields) NOT BETWEEN 1 AND 64 THEN
        RAISE EXCEPTION 'V13 canonical hash domain is invalid'
            USING ERRCODE = '22023';
    END IF;
    framed := pg_catalog.convert_to(hash_domain, 'UTF8')
        || pg_catalog.decode('00', 'hex');
    FOREACH field_value IN ARRAY hash_fields LOOP
        IF field_value IS NULL THEN
            RAISE EXCEPTION 'V13 canonical hash field is missing'
                USING ERRCODE = '22023';
        END IF;
        field_bytes := pg_catalog.convert_to(field_value, 'UTF8');
        IF pg_catalog.octet_length(field_bytes) > 4096 THEN
            RAISE EXCEPTION 'V13 canonical hash field is oversized'
                USING ERRCODE = '22023';
        END IF;
        framed := framed
            || pg_catalog.int4send(pg_catalog.octet_length(field_bytes))
            || field_bytes;
    END LOOP;
    RETURN pg_catalog.encode(pg_catalog.sha256(framed), 'hex');
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_framed_sha256_v13(
    VARCHAR, TEXT[]) FROM PUBLIC;

CREATE TABLE agent_graph_provider_validation_keys (
    key_id VARCHAR(100) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    public_key_der BYTEA NOT NULL,
    key_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    not_before TIMESTAMPTZ NOT NULL,
    not_after TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT graph_provider_validation_keys_pk_v13
        PRIMARY KEY (key_id),
    CONSTRAINT graph_provider_validation_keys_fingerprint_uq_v13
        UNIQUE (key_fingerprint),
    CONSTRAINT graph_provider_validation_keys_binding_uq_v13
        UNIQUE (key_id, key_fingerprint),
    CONSTRAINT graph_provider_validation_keys_shape_v13 CHECK (
        key_id ~ '^[a-z][a-z0-9._-]{0,99}$'
        AND algorithm = 'ED25519'
        AND octet_length(public_key_der) BETWEEN 32 AND 128
        AND key_fingerprint ~ '^[0-9a-f]{64}$'
        AND key_fingerprint = pg_catalog.encode(
            pg_catalog.sha256(public_key_der), 'hex')
        AND status IN ('ACTIVE', 'REVOKED')
        AND not_after > not_before
        AND created_at <= not_after
    )
);

CREATE TABLE agent_graph_provider_validations (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    database_name VARCHAR(63) NOT NULL,
    database_oid OID NOT NULL,
    schema_oid OID NOT NULL,
    attestor_role_oid OID NOT NULL,
    revision VARCHAR(2) NOT NULL,
    session_intent_hash CHAR(64) NOT NULL,
    session_expires_at TIMESTAMPTZ NOT NULL,
    policy_sequence INTEGER NOT NULL,
    policy_head_hash CHAR(64) NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    key_fingerprint CHAR(64) NOT NULL,
    transport_profile_hash CHAR(64) NOT NULL,
    parser_profile_hash CHAR(64) NOT NULL,
    schema_profile_hash CHAR(64) NOT NULL,
    challenge_ttl_millis INTEGER NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    expected_sequence INTEGER,
    expected_head_hash CHAR(64),
    execution_binding_hash CHAR(64),
    request_ordinal INTEGER,
    request_hash CHAR(64),
    response_hash CHAR(64),
    attribution_hash CHAR(64),
    provider_actor VARCHAR(200),
    model_requested VARCHAR(512),
    model_resolved VARCHAR(512),
    pricing_profile_id VARCHAR(200),
    pricing_provider VARCHAR(128),
    pricing_profile_fingerprint CHAR(64),
    uncached_input_nano_usd_per_token BIGINT,
    cached_input_nano_usd_per_token BIGINT,
    output_nano_usd_per_token BIGINT,
    input_tokens BIGINT,
    cached_input_tokens BIGINT,
    output_tokens BIGINT,
    reasoning_output_tokens BIGINT,
    total_tokens BIGINT,
    observed_cost_usd NUMERIC(20, 10),
    decision_kind VARCHAR(32),
    decision_hash CHAR(64),
    failure_code VARCHAR(64),
    validation_nonce UUID,
    challenge_hash CHAR(64),
    transcript_hash CHAR(64),
    signature BYTEA,
    issued_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    validated_at TIMESTAMPTZ,
    event_hash CHAR(64),
    cursor_sequence INTEGER,
    cursor_head_hash CHAR(64),
    failure_provenance_hash CHAR(64),
    policy_created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT graph_provider_validations_pk_v13
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT graph_provider_validations_policy_uq_v13
        UNIQUE (principal_id, policy_hash),
    CONSTRAINT graph_provider_validations_nonce_uq_v13
        UNIQUE (validation_nonce),
    CONSTRAINT graph_provider_validations_transcript_uq_v13
        UNIQUE (transcript_hash),
    CONSTRAINT graph_provider_validations_attempt_fk_v13
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id, attempt_id, manifest_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_provider_validations_session_fk_v13
        FOREIGN KEY (principal_id, session_intent_hash)
        REFERENCES agent_graph_provider_session_intents (
            principal_id, intent_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_provider_validations_policy_event_fk_v13
        FOREIGN KEY (
            principal_id, attempt_id, policy_sequence, policy_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_provider_validations_key_fk_v13
        FOREIGN KEY (key_id, key_fingerprint)
        REFERENCES agent_graph_provider_validation_keys (
            key_id, key_fingerprint
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_provider_validations_policy_shape_v13 CHECK (
        protocol_version = 'POSTGRES_ROLE_V1'
        AND database_name
            ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,62}$'
        AND revision IN ('r1', 'r2', 'r3')
        AND session_intent_hash ~ '^[0-9a-f]{64}$'
        AND session_expires_at > policy_created_at
        AND policy_sequence = 7
        AND policy_head_hash ~ '^[0-9a-f]{64}$'
        AND key_id ~ '^[a-z][a-z0-9._-]{0,99}$'
        AND key_fingerprint ~ '^[0-9a-f]{64}$'
        AND transport_profile_hash ~ '^[0-9a-f]{64}$'
        AND parser_profile_hash ~ '^[0-9a-f]{64}$'
        AND schema_profile_hash ~ '^[0-9a-f]{64}$'
        AND challenge_ttl_millis BETWEEN 100 AND 30000
        AND policy_hash ~ '^[0-9a-f]{64}$'
        AND state IN ('REQUIRED', 'STAGED', 'CONSUMED')
    ),
    CONSTRAINT graph_provider_validations_execution_binding_v13 CHECK (
        execution_binding_hash IS NULL
        OR execution_binding_hash = manifest_hash
    ),
    CONSTRAINT graph_provider_validations_state_shape_v13 CHECK (
        (
            state = 'REQUIRED'
            AND expected_sequence IS NULL
            AND expected_head_hash IS NULL
            AND execution_binding_hash IS NULL
            AND request_ordinal IS NULL
            AND request_hash IS NULL
            AND response_hash IS NULL
            AND attribution_hash IS NULL
            AND provider_actor IS NULL
            AND model_requested IS NULL
            AND model_resolved IS NULL
            AND pricing_profile_id IS NULL
            AND pricing_provider IS NULL
            AND pricing_profile_fingerprint IS NULL
            AND uncached_input_nano_usd_per_token IS NULL
            AND cached_input_nano_usd_per_token IS NULL
            AND output_nano_usd_per_token IS NULL
            AND input_tokens IS NULL
            AND cached_input_tokens IS NULL
            AND output_tokens IS NULL
            AND reasoning_output_tokens IS NULL
            AND total_tokens IS NULL
            AND observed_cost_usd IS NULL
            AND decision_kind IS NULL
            AND decision_hash IS NULL
            AND failure_code IS NULL
            AND validation_nonce IS NULL
            AND challenge_hash IS NULL
            AND transcript_hash IS NULL
            AND signature IS NULL
            AND issued_at IS NULL
            AND expires_at IS NULL
            AND validated_at IS NULL
            AND event_hash IS NULL
            AND cursor_sequence IS NULL
            AND cursor_head_hash IS NULL
            AND failure_provenance_hash IS NULL
            AND consumed_at IS NULL
        )
        OR (
            state IN ('STAGED', 'CONSUMED')
            AND ROW(
                expected_sequence, expected_head_hash,
                execution_binding_hash,
                request_ordinal, request_hash, response_hash,
                attribution_hash, provider_actor, model_requested,
                model_resolved, pricing_profile_id, pricing_provider,
                pricing_profile_fingerprint,
                uncached_input_nano_usd_per_token,
                cached_input_nano_usd_per_token,
                output_nano_usd_per_token, input_tokens,
                cached_input_tokens, output_tokens,
                reasoning_output_tokens, total_tokens,
                observed_cost_usd, decision_kind, decision_hash,
                validation_nonce, challenge_hash, transcript_hash,
                issued_at, expires_at) IS NOT NULL
            AND expected_sequence = 13
            AND expected_head_hash ~ '^[0-9a-f]{64}$'
            AND execution_binding_hash ~ '^[0-9a-f]{64}$'
            AND request_ordinal = 2
            AND request_hash ~ '^[0-9a-f]{64}$'
            AND response_hash ~ '^[0-9a-f]{64}$'
            AND attribution_hash ~ '^[0-9a-f]{64}$'
            AND char_length(btrim(provider_actor)) BETWEEN 1 AND 200
            AND model_requested
                ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
            AND model_resolved
                ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
            AND pricing_profile_id
                ~ '^[a-z][a-z0-9._-]{0,199}$'
            AND pricing_provider
                ~ '^[a-z][a-z0-9._-]{0,127}$'
            AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
            AND uncached_input_nano_usd_per_token > 0
            AND cached_input_nano_usd_per_token BETWEEN 0
                AND uncached_input_nano_usd_per_token
            AND output_nano_usd_per_token > 0
            AND input_tokens BETWEEN 0 AND 9007199254740991
            AND cached_input_tokens BETWEEN 0 AND input_tokens
            AND output_tokens BETWEEN 0 AND 9007199254740991
            AND reasoning_output_tokens BETWEEN 0 AND output_tokens
            AND total_tokens = input_tokens + output_tokens
            AND observed_cost_usd BETWEEN 0 AND 999999.999999
            AND observed_cost_usd = round(observed_cost_usd, 6)
            AND observed_cost_usd = round(
                (
                    (input_tokens - cached_input_tokens)::numeric
                        * uncached_input_nano_usd_per_token
                    + cached_input_tokens::numeric
                        * cached_input_nano_usd_per_token
                    + output_tokens::numeric
                        * output_nano_usd_per_token
                ) / 1000000000,
                6)
            AND decision_kind IN ('STRUCTURED_FINAL', 'FAILED')
            AND decision_hash ~ '^[0-9a-f]{64}$'
            AND (
                (decision_kind = 'STRUCTURED_FINAL'
                    AND failure_code IS NULL)
                OR (decision_kind = 'FAILED'
                    AND failure_code IS NOT NULL
                    AND failure_code IN (
                        'MODEL_RESPONSE_MALFORMED',
                        'MODEL_USAGE_LIMIT_EXCEEDED'))
            )
            AND validation_nonce IS NOT NULL
            AND challenge_hash ~ '^[0-9a-f]{64}$'
            AND transcript_hash ~ '^[0-9a-f]{64}$'
            AND expires_at > issued_at
            AND (
                (state = 'STAGED'
                    AND signature IS NULL
                    AND validated_at IS NULL
                    AND event_hash IS NULL
                    AND cursor_sequence IS NULL
                    AND cursor_head_hash IS NULL
                    AND failure_provenance_hash IS NULL
                    AND consumed_at IS NULL)
                OR (state = 'CONSUMED'
                    AND ROW(
                        signature, validated_at, event_hash,
                        cursor_sequence, cursor_head_hash,
                        consumed_at) IS NOT NULL
                    AND octet_length(signature) = 64
                    AND validated_at BETWEEN issued_at AND expires_at
                    AND event_hash ~ '^[0-9a-f]{64}$'
                    AND cursor_sequence = 14
                    AND cursor_head_hash ~ '^[0-9a-f]{64}$'
                    AND consumed_at >= validated_at
                    AND (
                        (decision_kind = 'STRUCTURED_FINAL'
                            AND failure_provenance_hash IS NULL)
                        OR (decision_kind = 'FAILED'
                            AND failure_provenance_hash IS NOT NULL
                            AND failure_provenance_hash
                                ~ '^[0-9a-f]{64}$')
                    ))
            )
        )
    )
);

CREATE FUNCTION agent_graph_require_provider_validation_v13(
    checked_principal VARCHAR,
    checked_attempt CHAR,
    checked_manifest CHAR,
    checked_key_id VARCHAR,
    checked_transport_profile CHAR,
    checked_parser_profile CHAR,
    checked_schema_profile CHAR,
    checked_challenge_ttl_millis INTEGER
)
RETURNS CHAR(64)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_head RECORD;
    session_intent RECORD;
    validation_key RECORD;
    computed_policy_hash CHAR(64);
    target_schema_oid OID;
    role_oid OID;
    database_oid OID;
    now_at TIMESTAMPTZ;
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor'
       OR checked_principal IS NULL
       OR checked_attempt !~ '^[0-9a-f]{64}$'
       OR checked_manifest !~ '^[0-9a-f]{64}$'
       OR checked_key_id !~ '^[a-z][a-z0-9._-]{0,99}$'
       OR checked_transport_profile !~ '^[0-9a-f]{64}$'
       OR checked_parser_profile !~ '^[0-9a-f]{64}$'
       OR checked_schema_profile !~ '^[0-9a-f]{64}$'
       OR checked_challenge_ttl_millis NOT BETWEEN 100 AND 30000 THEN
        RAISE EXCEPTION 'V13 provider validation policy authority rejected'
            USING ERRCODE = '42501';
    END IF;

    SELECT head.manifest_hash,
           head.last_sequence,
           head.head_hash
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = checked_principal
      AND head.attempt_id = checked_attempt
    FOR UPDATE;

    SELECT intent.revision,
           intent.intent_hash,
           intent.cursor_sequence,
           intent.cursor_head_hash,
           intent.expires_at
    INTO STRICT session_intent
    FROM public.agent_graph_provider_session_intents intent
    WHERE intent.principal_id = checked_principal
      AND intent.attempt_id = checked_attempt;

    SELECT key.key_id,
           key.key_fingerprint,
           key.not_before,
           key.not_after
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = checked_key_id
      AND key.algorithm = 'ED25519'
      AND key.status = 'ACTIVE';

    SELECT namespace.oid
    INTO STRICT target_schema_oid
    FROM pg_catalog.pg_namespace namespace
    WHERE namespace.nspname = 'public';
    SELECT role.oid
    INTO STRICT role_oid
    FROM pg_catalog.pg_roles role
    WHERE role.rolname = session_user;
    SELECT database.oid
    INTO STRICT database_oid
    FROM pg_catalog.pg_database database
    WHERE database.datname = current_database();

    now_at := pg_catalog.clock_timestamp();
    IF locked_head.manifest_hash IS DISTINCT FROM checked_manifest
       OR locked_head.last_sequence <> 7
       OR locked_head.head_hash
            IS DISTINCT FROM session_intent.cursor_head_hash
       OR session_intent.cursor_sequence <> 7
       OR session_intent.expires_at <= now_at
       OR now_at NOT BETWEEN validation_key.not_before
                         AND validation_key.not_after THEN
        RAISE EXCEPTION 'V13 provider validation policy was fenced'
            USING ERRCODE = '55000';
    END IF;

    computed_policy_hash := public.agent_graph_framed_sha256_v13(
        'emergeos.provider-validation-policy.v1',
        ARRAY[
            current_database()::VARCHAR(63)::TEXT,
            database_oid::TEXT,
            target_schema_oid::TEXT,
            role_oid::TEXT,
            checked_principal::VARCHAR(200)::TEXT,
            checked_attempt::CHAR(64)::TEXT,
            checked_manifest::CHAR(64)::TEXT,
            session_intent.revision::VARCHAR(2)::TEXT,
            session_intent.intent_hash::CHAR(64)::TEXT,
            ((EXTRACT(epoch FROM session_intent.expires_at)
                * 1000000)::BIGINT)::TEXT,
            '7',
            session_intent.cursor_head_hash::CHAR(64)::TEXT,
            validation_key.key_id::VARCHAR(100)::TEXT,
            validation_key.key_fingerprint::CHAR(64)::TEXT,
            checked_transport_profile::CHAR(64)::TEXT,
            checked_parser_profile::CHAR(64)::TEXT,
            checked_schema_profile::CHAR(64)::TEXT
        ]);

    INSERT INTO public.agent_graph_provider_validations (
        principal_id, attempt_id, manifest_hash,
        protocol_version, database_name, database_oid,
        schema_oid, attestor_role_oid,
        revision, session_intent_hash, session_expires_at,
        policy_sequence, policy_head_hash,
        key_id, key_fingerprint,
        transport_profile_hash, parser_profile_hash,
        schema_profile_hash, challenge_ttl_millis,
        policy_hash, state, policy_created_at
    ) VALUES (
        checked_principal, checked_attempt, checked_manifest,
        'POSTGRES_ROLE_V1', current_database(), database_oid,
        target_schema_oid, role_oid,
        session_intent.revision, session_intent.intent_hash,
        session_intent.expires_at,
        7, session_intent.cursor_head_hash,
        validation_key.key_id, validation_key.key_fingerprint,
        checked_transport_profile, checked_parser_profile,
        checked_schema_profile, checked_challenge_ttl_millis,
        computed_policy_hash, 'REQUIRED', now_at
    );
    RETURN computed_policy_hash;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V13 provider validation policy was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_require_provider_validation_v13(
    VARCHAR, CHAR, CHAR, VARCHAR, CHAR, CHAR, CHAR, INTEGER)
FROM PUBLIC;

CREATE FUNCTION agent_graph_stage_provider_validation_v13(
    payload JSONB
)
RETURNS TABLE (
    protocol_version VARCHAR,
    database_name VARCHAR,
    database_oid OID,
    schema_oid OID,
    attestor_role_oid OID,
    principal_id VARCHAR,
    attempt_id CHAR(64),
    manifest_hash CHAR(64),
    revision VARCHAR,
    session_intent_hash CHAR(64),
    session_expires_at TIMESTAMPTZ,
    policy_sequence INTEGER,
    policy_head_hash CHAR(64),
    key_id VARCHAR,
    key_fingerprint CHAR(64),
    transport_profile_hash CHAR(64),
    parser_profile_hash CHAR(64),
    schema_profile_hash CHAR(64),
    validation_nonce UUID,
    issued_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    policy_hash CHAR(64),
    challenge_hash CHAR(64),
    transcript_hash CHAR(64),
    public_key_der BYTEA
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_validation public.agent_graph_provider_validations%ROWTYPE;
    locked_head RECORD;
    request_intent RECORD;
    attempt_truth RECORD;
    validation_key RECORD;
    stage_now TIMESTAMPTZ;
    stage_expires TIMESTAMPTZ;
    minted_nonce UUID;
    computed_challenge_hash CHAR(64);
    computed_transcript_hash CHAR(64);
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor'
       OR payload IS NULL
       OR pg_catalog.jsonb_typeof(payload) <> 'object'
       OR EXISTS (
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('execution_binding_hash'),
                ('expected_sequence'),
                ('expected_head_hash'), ('request_ordinal'),
                ('request_hash'), ('response_hash'),
                ('attribution_hash'), ('provider_actor'),
                ('model_requested'), ('model_resolved'),
                ('pricing_profile_id'), ('pricing_provider'),
                ('pricing_profile_fingerprint'),
                ('uncached_input_nano_usd_per_token'),
                ('cached_input_nano_usd_per_token'),
                ('output_nano_usd_per_token'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('observed_cost_usd'), ('transport_profile_hash'),
                ('parser_profile_hash'), ('schema_profile_hash'),
                ('decision_kind'), ('decision_hash'), ('failure_code')
            ) expected(key)
            EXCEPT
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
       )
       OR EXISTS (
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
            EXCEPT
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('execution_binding_hash'),
                ('expected_sequence'),
                ('expected_head_hash'), ('request_ordinal'),
                ('request_hash'), ('response_hash'),
                ('attribution_hash'), ('provider_actor'),
                ('model_requested'), ('model_resolved'),
                ('pricing_profile_id'), ('pricing_provider'),
                ('pricing_profile_fingerprint'),
                ('uncached_input_nano_usd_per_token'),
                ('cached_input_nano_usd_per_token'),
                ('output_nano_usd_per_token'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('observed_cost_usd'), ('transport_profile_hash'),
                ('parser_profile_hash'), ('schema_profile_hash'),
                ('decision_kind'), ('decision_hash'), ('failure_code')
            ) expected(key)
       ) THEN
        RAISE EXCEPTION 'V13 provider validation stage input is invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT durable.*
    INTO STRICT locked_validation
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id'
    FOR UPDATE;

    SELECT head.manifest_hash, head.last_sequence, head.head_hash,
           head.phase, head.billing_status,
           head.provider_intent_count,
           head.provider_attribution_count
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = locked_validation.principal_id
      AND head.attempt_id = locked_validation.attempt_id
    FOR UPDATE;

    SELECT event.request_ordinal,
           event.request_hash,
           event.model_requested
    INTO STRICT request_intent
    FROM public.agent_graph_attempt_events event
    WHERE event.principal_id = locked_validation.principal_id
      AND event.attempt_id = locked_validation.attempt_id
      AND event.sequence = 13
      AND event.event_type = 'PROVIDER_INTENT';

    SELECT attempt.child_actor,
           attempt.pricing_profile_fingerprint
    INTO STRICT attempt_truth
    FROM public.agent_graph_attempts attempt
    WHERE attempt.principal_id = locked_validation.principal_id
      AND attempt.attempt_id = locked_validation.attempt_id;

    SELECT key.public_key_der, key.key_fingerprint,
           key.not_before, key.not_after, key.status
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = locked_validation.key_id
      AND key.key_fingerprint = locked_validation.key_fingerprint;

    stage_now := pg_catalog.clock_timestamp();
    stage_expires := LEAST(
        stage_now
            + locked_validation.challenge_ttl_millis
                * INTERVAL '1 millisecond',
        locked_validation.session_expires_at,
        validation_key.not_after);
    IF locked_validation.state <> 'REQUIRED'
       OR locked_validation.protocol_version <> 'POSTGRES_ROLE_V1'
       OR locked_validation.database_name <> current_database()
       OR locked_validation.database_oid <>
            (SELECT database.oid
             FROM pg_catalog.pg_database database
             WHERE database.datname = current_database())
       OR locked_validation.schema_oid <>
            (SELECT namespace.oid
             FROM pg_catalog.pg_namespace namespace
             WHERE namespace.nspname = 'public')
       OR locked_validation.attestor_role_oid <>
            (SELECT role.oid
             FROM pg_catalog.pg_roles role
             WHERE role.rolname = session_user)
       OR locked_validation.manifest_hash
            IS DISTINCT FROM payload ->> 'manifest_hash'
       OR locked_validation.manifest_hash
            IS DISTINCT FROM payload ->> 'execution_binding_hash'
       OR locked_head.manifest_hash
            IS DISTINCT FROM locked_validation.manifest_hash
       OR locked_head.last_sequence <> 13
       OR locked_head.head_hash
            IS DISTINCT FROM payload ->> 'expected_head_hash'
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR (payload ->> 'expected_sequence')::INTEGER <> 13
       OR (payload ->> 'request_ordinal')::INTEGER <> 2
       OR request_intent.request_ordinal <> 2
       OR request_intent.request_hash
            IS DISTINCT FROM payload ->> 'request_hash'
       OR request_intent.model_requested
            IS DISTINCT FROM payload ->> 'model_requested'
       OR attempt_truth.child_actor
            IS DISTINCT FROM payload ->> 'provider_actor'
       OR attempt_truth.pricing_profile_fingerprint
            IS DISTINCT FROM payload ->> 'pricing_profile_fingerprint'
       OR locked_validation.transport_profile_hash
            IS DISTINCT FROM payload ->> 'transport_profile_hash'
       OR locked_validation.parser_profile_hash
            IS DISTINCT FROM payload ->> 'parser_profile_hash'
       OR locked_validation.schema_profile_hash
            IS DISTINCT FROM payload ->> 'schema_profile_hash'
       OR validation_key.status <> 'ACTIVE'
       OR stage_now NOT BETWEEN validation_key.not_before
                            AND validation_key.not_after
       OR stage_expires <= stage_now
       OR payload ->> 'decision_kind'
            NOT IN ('STRUCTURED_FINAL', 'FAILED')
       OR (
            payload ->> 'decision_kind' = 'STRUCTURED_FINAL'
            AND payload ->> 'failure_code' IS NOT NULL)
       OR (
            payload ->> 'decision_kind' = 'FAILED'
            AND payload ->> 'failure_code' NOT IN (
                'MODEL_RESPONSE_MALFORMED',
                'MODEL_USAGE_LIMIT_EXCEEDED')) THEN
        RAISE EXCEPTION 'V13 provider validation stage was fenced'
            USING ERRCODE = '55000';
    END IF;

    minted_nonce := pg_catalog.gen_random_uuid();
    computed_challenge_hash := public.agent_graph_framed_sha256_v13(
        'emergeos.provider-validation-challenge.v1',
        ARRAY[
            locked_validation.policy_hash,
            minted_nonce::TEXT,
            ((EXTRACT(epoch FROM stage_now)
                * 1000000)::BIGINT)::TEXT,
            ((EXTRACT(epoch FROM stage_expires)
                * 1000000)::BIGINT)::TEXT
        ]);
    computed_transcript_hash := public.agent_graph_framed_sha256_v13(
        'emergeos.provider-validation-transcript.v1',
        ARRAY[
            computed_challenge_hash,
            '13',
            locked_head.head_hash,
            payload ->> 'execution_binding_hash',
            '2',
            payload ->> 'request_hash',
            payload ->> 'response_hash',
            payload ->> 'attribution_hash',
            locked_validation.transport_profile_hash,
            locked_validation.parser_profile_hash,
            locked_validation.schema_profile_hash,
            payload ->> 'decision_kind',
            payload ->> 'decision_hash',
            COALESCE(payload ->> 'failure_code', '')
        ]);

    UPDATE public.agent_graph_provider_validations durable
    SET state = 'STAGED',
        expected_sequence = 13,
        expected_head_hash = locked_head.head_hash,
        execution_binding_hash = payload ->> 'execution_binding_hash',
        request_ordinal = 2,
        request_hash = payload ->> 'request_hash',
        response_hash = payload ->> 'response_hash',
        attribution_hash = payload ->> 'attribution_hash',
        provider_actor = payload ->> 'provider_actor',
        model_requested = payload ->> 'model_requested',
        model_resolved = payload ->> 'model_resolved',
        pricing_profile_id = payload ->> 'pricing_profile_id',
        pricing_provider = payload ->> 'pricing_provider',
        pricing_profile_fingerprint =
            payload ->> 'pricing_profile_fingerprint',
        uncached_input_nano_usd_per_token =
            (payload ->> 'uncached_input_nano_usd_per_token')::BIGINT,
        cached_input_nano_usd_per_token =
            (payload ->> 'cached_input_nano_usd_per_token')::BIGINT,
        output_nano_usd_per_token =
            (payload ->> 'output_nano_usd_per_token')::BIGINT,
        input_tokens = (payload ->> 'input_tokens')::BIGINT,
        cached_input_tokens =
            (payload ->> 'cached_input_tokens')::BIGINT,
        output_tokens = (payload ->> 'output_tokens')::BIGINT,
        reasoning_output_tokens =
            (payload ->> 'reasoning_output_tokens')::BIGINT,
        total_tokens = (payload ->> 'total_tokens')::BIGINT,
        observed_cost_usd =
            (payload ->> 'observed_cost_usd')::NUMERIC,
        decision_kind = payload ->> 'decision_kind',
        decision_hash = payload ->> 'decision_hash',
        failure_code = payload ->> 'failure_code',
        validation_nonce = minted_nonce,
        challenge_hash = computed_challenge_hash,
        transcript_hash = computed_transcript_hash,
        issued_at = stage_now,
        expires_at = stage_expires
    WHERE durable.principal_id = locked_validation.principal_id
      AND durable.attempt_id = locked_validation.attempt_id;

    RETURN QUERY
    SELECT durable.protocol_version,
           durable.database_name,
           durable.database_oid,
           durable.schema_oid,
           durable.attestor_role_oid,
           durable.principal_id,
           durable.attempt_id,
           durable.manifest_hash,
           durable.revision,
           durable.session_intent_hash,
           durable.session_expires_at,
           durable.policy_sequence,
           durable.policy_head_hash,
           durable.key_id,
           durable.key_fingerprint,
           durable.transport_profile_hash,
           durable.parser_profile_hash,
           durable.schema_profile_hash,
           durable.validation_nonce,
           durable.issued_at,
           durable.expires_at,
           durable.policy_hash,
           durable.challenge_hash,
           durable.transcript_hash,
           validation_key.public_key_der
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = locked_validation.principal_id
      AND durable.attempt_id = locked_validation.attempt_id;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V13 provider validation stage was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_stage_provider_validation_v13(JSONB)
FROM PUBLIC;

CREATE FUNCTION agent_graph_commit_provider_validation_v13(
    payload JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    validation public.agent_graph_provider_validations%ROWTYPE;
    locked_head RECORD;
    child_binding RECORD;
    first_attribution RECORD;
    validation_key RECORD;
    commit_now TIMESTAMPTZ;
    computed_provenance CHAR(64);
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor'
       OR payload IS NULL
       OR pg_catalog.jsonb_typeof(payload) <> 'object'
       OR EXISTS (
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('policy_hash'), ('transcript_hash'), ('signature_hex'),
                ('event_hash'), ('cursor_head_hash')
            ) expected(key)
            EXCEPT
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
       )
       OR EXISTS (
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
            EXCEPT
            SELECT expected.key
            FROM (VALUES
                ('principal_id'), ('attempt_id'), ('manifest_hash'),
                ('policy_hash'), ('transcript_hash'), ('signature_hex'),
                ('event_hash'), ('cursor_head_hash')
            ) expected(key)
       )
       OR payload ->> 'signature_hex' IS NULL
       OR payload ->> 'signature_hex' !~ '^[0-9a-f]{128}$'
       OR payload ->> 'event_hash' IS NULL
       OR payload ->> 'event_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'cursor_head_hash' IS NULL
       OR payload ->> 'cursor_head_hash' !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'V13 provider validation commit input is invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT durable.*
    INTO STRICT validation
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = payload ->> 'principal_id'
      AND durable.attempt_id = payload ->> 'attempt_id'
    FOR UPDATE;

    SELECT head.*
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = validation.principal_id
      AND head.attempt_id = validation.attempt_id
    FOR UPDATE;

    SELECT binding.role, binding.run_id, binding.task_id
    INTO STRICT child_binding
    FROM public.agent_graph_attempt_run_bindings binding
    WHERE binding.principal_id = validation.principal_id
      AND binding.attempt_id = validation.attempt_id
      AND binding.role = 'CHILD';

    SELECT attribution.attribution_hash
    INTO STRICT first_attribution
    FROM public.agent_graph_attempt_provider_attributions attribution
    WHERE attribution.principal_id = validation.principal_id
      AND attribution.attempt_id = validation.attempt_id
      AND attribution.request_ordinal = 1;

    SELECT key.status, key.not_before, key.not_after,
           key.key_fingerprint
    INTO STRICT validation_key
    FROM public.agent_graph_provider_validation_keys key
    WHERE key.key_id = validation.key_id;

    commit_now := pg_catalog.clock_timestamp();
    IF validation.state <> 'STAGED'
       OR validation.manifest_hash
            IS DISTINCT FROM payload ->> 'manifest_hash'
       OR validation.policy_hash
            IS DISTINCT FROM payload ->> 'policy_hash'
       OR validation.transcript_hash
            IS DISTINCT FROM payload ->> 'transcript_hash'
       OR validation.database_name <> current_database()
       OR validation.database_oid <>
            (SELECT database.oid
             FROM pg_catalog.pg_database database
             WHERE database.datname = current_database())
       OR validation.schema_oid <>
            (SELECT namespace.oid
             FROM pg_catalog.pg_namespace namespace
             WHERE namespace.nspname = 'public')
       OR validation.attestor_role_oid <>
            (SELECT role.oid
             FROM pg_catalog.pg_roles role
             WHERE role.rolname = session_user)
       OR locked_head.manifest_hash
            IS DISTINCT FROM validation.manifest_hash
       OR locked_head.last_sequence <> 13
       OR locked_head.head_hash
            IS DISTINCT FROM validation.expected_head_hash
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR commit_now > validation.expires_at
       OR commit_now >= validation.session_expires_at
       OR validation_key.status <> 'ACTIVE'
       OR validation_key.key_fingerprint
            IS DISTINCT FROM validation.key_fingerprint
       OR commit_now NOT BETWEEN validation_key.not_before
                            AND validation_key.not_after THEN
        RAISE EXCEPTION 'V13 provider validation commit was fenced'
            USING ERRCODE = '55000';
    END IF;

    INSERT INTO public.agent_graph_attempt_provider_attributions (
        principal_id, attempt_id, manifest_hash,
        request_ordinal, event_sequence,
        request_hash, response_hash,
        provider_actor, model_requested, model_resolved,
        pricing_profile_id, pricing_provider,
        pricing_profile_fingerprint,
        uncached_input_nano_usd_per_token,
        cached_input_nano_usd_per_token,
        output_nano_usd_per_token,
        input_tokens, cached_input_tokens, output_tokens,
        reasoning_output_tokens, total_tokens,
        observed_cost_usd, attribution_hash, attributed_at
    ) VALUES (
        validation.principal_id, validation.attempt_id,
        validation.manifest_hash,
        2, 14,
        validation.request_hash, validation.response_hash,
        validation.provider_actor, validation.model_requested,
        validation.model_resolved,
        validation.pricing_profile_id, validation.pricing_provider,
        validation.pricing_profile_fingerprint,
        validation.uncached_input_nano_usd_per_token,
        validation.cached_input_nano_usd_per_token,
        validation.output_nano_usd_per_token,
        validation.input_tokens, validation.cached_input_tokens,
        validation.output_tokens,
        validation.reasoning_output_tokens, validation.total_tokens,
        validation.observed_cost_usd, validation.attribution_hash,
        validation.issued_at
    );

    INSERT INTO public.agent_graph_attempt_events (
        principal_id, attempt_id, manifest_hash,
        sequence, event_type, occurred_at,
        phase_from, phase_to, role, run_id, task_id,
        actor, challenge_hash, request_ordinal,
        request_hash, model_requested, evidence_hash,
        previous_head_hash, event_hash, current_head_hash
    ) VALUES (
        validation.principal_id, validation.attempt_id,
        validation.manifest_hash,
        14, 'PROVIDER_ATTRIBUTED', validation.issued_at,
        'PROVIDER_PENDING', 'PROVIDER_ATTRIBUTED',
        child_binding.role, child_binding.run_id, child_binding.task_id,
        NULL, NULL, 2, NULL, NULL, validation.attribution_hash,
        validation.expected_head_hash,
        payload ->> 'event_hash', payload ->> 'cursor_head_hash'
    );

    UPDATE public.agent_graph_attempt_heads head
    SET phase = 'PROVIDER_ATTRIBUTED',
        state_version = 14,
        last_sequence = 14,
        head_hash = payload ->> 'cursor_head_hash',
        billing_status = 'ATTRIBUTED',
        provider_intent_count = 2,
        provider_attribution_count = 2,
        updated_at = validation.issued_at
    WHERE head.principal_id = validation.principal_id
      AND head.attempt_id = validation.attempt_id
      AND head.manifest_hash = validation.manifest_hash
      AND head.state_version = 13
      AND head.last_sequence = 13
      AND head.head_hash = validation.expected_head_hash;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V13 provider validation commit was fenced'
            USING ERRCODE = '55000';
    END IF;

    computed_provenance := NULL;
    IF validation.decision_kind = 'FAILED' THEN
        computed_provenance := pg_catalog.encode(
            pg_catalog.sha256(pg_catalog.convert_to(
                'emergeos.graph-attributed-failure-outcome.v1|'
                || validation.manifest_hash || '|'
                || validation.revision || '|'
                || validation.session_intent_hash || '|'
                || validation.expected_head_hash || '|'
                || (payload ->> 'cursor_head_hash') || '|'
                || first_attribution.attribution_hash || '|'
                || validation.attribution_hash || '|'
                || validation.request_hash || '|'
                || validation.response_hash || '|'
                || validation.model_resolved || '|'
                || validation.failure_code,
                'UTF8')),
            'hex');
        INSERT INTO public.agent_graph_attributed_failure_outcomes (
            principal_id, attempt_id, manifest_hash,
            revision, session_intent_hash, session_expires_at,
            previous_sequence, previous_head_hash,
            cursor_sequence, cursor_head_hash,
            provider_attribution_1_hash,
            provider_attribution_2_hash,
            request_hash, response_hash, model_resolved,
            failure_code, provenance_hash,
            state, state_version, recorded_at
        ) VALUES (
            validation.principal_id, validation.attempt_id,
            validation.manifest_hash,
            validation.revision, validation.session_intent_hash,
            validation.session_expires_at,
            13, validation.expected_head_hash,
            14, payload ->> 'cursor_head_hash',
            first_attribution.attribution_hash,
            validation.attribution_hash,
            validation.request_hash, validation.response_hash,
            validation.model_resolved,
            validation.failure_code, computed_provenance,
            'READY', 1, validation.issued_at
        );
    END IF;

    UPDATE public.agent_graph_provider_validations durable
    SET state = 'CONSUMED',
        signature = pg_catalog.decode(payload ->> 'signature_hex', 'hex'),
        validated_at = commit_now,
        event_hash = payload ->> 'event_hash',
        cursor_sequence = 14,
        cursor_head_hash = payload ->> 'cursor_head_hash',
        failure_provenance_hash = computed_provenance,
        consumed_at = commit_now
    WHERE durable.principal_id = validation.principal_id
      AND durable.attempt_id = validation.attempt_id;

    PERFORM public.agent_assert_graph_attempt_v8(
        validation.principal_id, validation.attempt_id);
    SET CONSTRAINTS ALL IMMEDIATE;

    RETURN pg_catalog.jsonb_build_object(
        'sequence', 14,
        'head_hash', payload ->> 'cursor_head_hash',
        'transcript_hash', validation.transcript_hash,
        'decision_kind', validation.decision_kind,
        'validation_state', 'CONSUMED',
        'failure_provenance_hash', computed_provenance);
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V13 provider validation commit was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_commit_provider_validation_v13(JSONB)
FROM PUBLIC;

CREATE FUNCTION agent_graph_assert_provider_validation_v13()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    checked_principal VARCHAR(200);
    checked_attempt CHAR(64);
    validation RECORD;
    live_head RECORD;
    second_attribution RECORD;
    attributed_event RECORD;
    durable_failure RECORD;
    expected_policy_hash CHAR(64);
    expected_challenge_hash CHAR(64);
    expected_transcript_hash CHAR(64);
BEGIN
    checked_principal := CASE
        WHEN TG_OP = 'DELETE' THEN OLD.principal_id
        ELSE NEW.principal_id
    END;
    checked_attempt := CASE
        WHEN TG_OP = 'DELETE' THEN OLD.attempt_id
        ELSE NEW.attempt_id
    END;

    IF TG_TABLE_NAME = 'agent_graph_provider_validations'
       AND TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'V13 provider validation policy is immutable'
            USING ERRCODE = '55000';
    END IF;

    SELECT durable.*
    INTO validation
    FROM public.agent_graph_provider_validations durable
    WHERE durable.principal_id = checked_principal
      AND durable.attempt_id = checked_attempt;
    IF NOT FOUND THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_TABLE_NAME = 'agent_graph_provider_validations' THEN
        IF TG_OP = 'INSERT' AND NEW.state <> 'REQUIRED' THEN
            RAISE EXCEPTION 'V13 provider validation transition drifted'
                USING ERRCODE = '55000';
        ELSIF TG_OP = 'UPDATE' THEN
            IF ROW(
                    NEW.principal_id, NEW.attempt_id, NEW.manifest_hash,
                    NEW.protocol_version, NEW.database_name,
                    NEW.database_oid, NEW.schema_oid,
                    NEW.attestor_role_oid, NEW.revision,
                    NEW.session_intent_hash, NEW.session_expires_at,
                    NEW.policy_sequence, NEW.policy_head_hash,
                    NEW.key_id, NEW.key_fingerprint,
                    NEW.transport_profile_hash,
                    NEW.parser_profile_hash, NEW.schema_profile_hash,
                    NEW.challenge_ttl_millis, NEW.policy_hash,
                    NEW.policy_created_at)
                IS DISTINCT FROM ROW(
                    OLD.principal_id, OLD.attempt_id, OLD.manifest_hash,
                    OLD.protocol_version, OLD.database_name,
                    OLD.database_oid, OLD.schema_oid,
                    OLD.attestor_role_oid, OLD.revision,
                    OLD.session_intent_hash, OLD.session_expires_at,
                    OLD.policy_sequence, OLD.policy_head_hash,
                    OLD.key_id, OLD.key_fingerprint,
                    OLD.transport_profile_hash,
                    OLD.parser_profile_hash, OLD.schema_profile_hash,
                    OLD.challenge_ttl_millis, OLD.policy_hash,
                    OLD.policy_created_at)
               OR (OLD.state = 'REQUIRED' AND NEW.state <> 'STAGED')
               OR (OLD.state = 'STAGED' AND NEW.state <> 'CONSUMED')
               OR OLD.state = 'CONSUMED'
               OR OLD.state NOT IN ('REQUIRED', 'STAGED') THEN
                RAISE EXCEPTION 'V13 provider validation transition drifted'
                    USING ERRCODE = '55000';
            END IF;
            IF OLD.state = 'STAGED' AND ROW(
                    NEW.expected_sequence, NEW.expected_head_hash,
                    NEW.execution_binding_hash,
                    NEW.request_ordinal, NEW.request_hash,
                    NEW.response_hash, NEW.attribution_hash,
                    NEW.provider_actor, NEW.model_requested,
                    NEW.model_resolved, NEW.pricing_profile_id,
                    NEW.pricing_provider,
                    NEW.pricing_profile_fingerprint,
                    NEW.uncached_input_nano_usd_per_token,
                    NEW.cached_input_nano_usd_per_token,
                    NEW.output_nano_usd_per_token,
                    NEW.input_tokens, NEW.cached_input_tokens,
                    NEW.output_tokens, NEW.reasoning_output_tokens,
                    NEW.total_tokens, NEW.observed_cost_usd,
                    NEW.decision_kind, NEW.decision_hash,
                    NEW.failure_code, NEW.validation_nonce,
                    NEW.challenge_hash, NEW.transcript_hash,
                    NEW.issued_at, NEW.expires_at)
                IS DISTINCT FROM ROW(
                    OLD.expected_sequence, OLD.expected_head_hash,
                    OLD.execution_binding_hash,
                    OLD.request_ordinal, OLD.request_hash,
                    OLD.response_hash, OLD.attribution_hash,
                    OLD.provider_actor, OLD.model_requested,
                    OLD.model_resolved, OLD.pricing_profile_id,
                    OLD.pricing_provider,
                    OLD.pricing_profile_fingerprint,
                    OLD.uncached_input_nano_usd_per_token,
                    OLD.cached_input_nano_usd_per_token,
                    OLD.output_nano_usd_per_token,
                    OLD.input_tokens, OLD.cached_input_tokens,
                    OLD.output_tokens, OLD.reasoning_output_tokens,
                    OLD.total_tokens, OLD.observed_cost_usd,
                    OLD.decision_kind, OLD.decision_hash,
                    OLD.failure_code, OLD.validation_nonce,
                    OLD.challenge_hash, OLD.transcript_hash,
                    OLD.issued_at, OLD.expires_at) THEN
                RAISE EXCEPTION 'V13 provider validation transcript mutated'
                    USING ERRCODE = '55000';
            END IF;
        END IF;
    END IF;

    SELECT head.manifest_hash, head.last_sequence, head.head_hash
    INTO STRICT live_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = checked_principal
      AND head.attempt_id = checked_attempt;

    expected_policy_hash := public.agent_graph_framed_sha256_v13(
        'emergeos.provider-validation-policy.v1',
        ARRAY[
            validation.database_name,
            validation.database_oid::TEXT,
            validation.schema_oid::TEXT,
            validation.attestor_role_oid::TEXT,
            validation.principal_id,
            validation.attempt_id,
            validation.manifest_hash,
            validation.revision,
            validation.session_intent_hash,
            ((extract(epoch FROM validation.session_expires_at)
                * 1000000)::BIGINT)::TEXT,
            validation.policy_sequence::TEXT,
            validation.policy_head_hash,
            validation.key_id,
            validation.key_fingerprint,
            validation.transport_profile_hash,
            validation.parser_profile_hash,
            validation.schema_profile_hash
        ]);
    IF validation.policy_hash IS DISTINCT FROM expected_policy_hash THEN
        RAISE EXCEPTION 'V13 provider validation policy drifted'
            USING ERRCODE = '55000';
    END IF;

    IF validation.state IN ('STAGED', 'CONSUMED') THEN
        expected_challenge_hash := public.agent_graph_framed_sha256_v13(
            'emergeos.provider-validation-challenge.v1',
            ARRAY[
                validation.policy_hash,
                validation.validation_nonce::TEXT,
                ((extract(epoch FROM validation.issued_at)
                    * 1000000)::BIGINT)::TEXT,
                ((extract(epoch FROM validation.expires_at)
                    * 1000000)::BIGINT)::TEXT
            ]);
        expected_transcript_hash := public.agent_graph_framed_sha256_v13(
            'emergeos.provider-validation-transcript.v1',
            ARRAY[
                expected_challenge_hash,
                validation.expected_sequence::TEXT,
                validation.expected_head_hash,
                validation.execution_binding_hash,
                validation.request_ordinal::TEXT,
                validation.request_hash,
                validation.response_hash,
                validation.attribution_hash,
                validation.transport_profile_hash,
                validation.parser_profile_hash,
                validation.schema_profile_hash,
                validation.decision_kind,
                validation.decision_hash,
                COALESCE(validation.failure_code, '')
            ]);
        IF validation.challenge_hash
                IS DISTINCT FROM expected_challenge_hash
           OR validation.execution_binding_hash
                IS DISTINCT FROM validation.manifest_hash
           OR validation.transcript_hash
                IS DISTINCT FROM expected_transcript_hash THEN
            RAISE EXCEPTION 'V13 provider validation transcript drifted'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    SELECT attribution.*
    INTO second_attribution
    FROM public.agent_graph_attempt_provider_attributions attribution
    WHERE attribution.principal_id = checked_principal
      AND attribution.attempt_id = checked_attempt
      AND attribution.request_ordinal = 2;
    SELECT event.*
    INTO attributed_event
    FROM public.agent_graph_attempt_events event
    WHERE event.principal_id = checked_principal
      AND event.attempt_id = checked_attempt
      AND event.sequence = 14;

    IF live_head.last_sequence >= 14
       OR second_attribution.event_sequence IS NOT NULL
       OR attributed_event.sequence IS NOT NULL THEN
        IF validation.state IS DISTINCT FROM 'CONSUMED' THEN
            RAISE EXCEPTION
                'V13 request-2 validation attestation is missing'
                USING ERRCODE = '55000';
        END IF;
        IF live_head.manifest_hash
                IS DISTINCT FROM validation.manifest_hash
           OR live_head.last_sequence < 14
           OR second_attribution.manifest_hash
                IS DISTINCT FROM validation.manifest_hash
           OR second_attribution.request_hash
                IS DISTINCT FROM validation.request_hash
           OR second_attribution.response_hash
                IS DISTINCT FROM validation.response_hash
           OR second_attribution.attribution_hash
                IS DISTINCT FROM validation.attribution_hash
           OR second_attribution.provider_actor
                IS DISTINCT FROM validation.provider_actor
           OR second_attribution.model_requested
                IS DISTINCT FROM validation.model_requested
           OR second_attribution.model_resolved
                IS DISTINCT FROM validation.model_resolved
           OR second_attribution.pricing_profile_id
                IS DISTINCT FROM validation.pricing_profile_id
           OR second_attribution.pricing_provider
                IS DISTINCT FROM validation.pricing_provider
           OR second_attribution.pricing_profile_fingerprint
                IS DISTINCT FROM validation.pricing_profile_fingerprint
           OR second_attribution.uncached_input_nano_usd_per_token
                IS DISTINCT FROM
                    validation.uncached_input_nano_usd_per_token
           OR second_attribution.cached_input_nano_usd_per_token
                IS DISTINCT FROM
                    validation.cached_input_nano_usd_per_token
           OR second_attribution.output_nano_usd_per_token
                IS DISTINCT FROM validation.output_nano_usd_per_token
           OR second_attribution.input_tokens
                IS DISTINCT FROM validation.input_tokens
           OR second_attribution.cached_input_tokens
                IS DISTINCT FROM validation.cached_input_tokens
           OR second_attribution.output_tokens
                IS DISTINCT FROM validation.output_tokens
           OR second_attribution.reasoning_output_tokens
                IS DISTINCT FROM validation.reasoning_output_tokens
           OR second_attribution.total_tokens
                IS DISTINCT FROM validation.total_tokens
           OR second_attribution.observed_cost_usd
                IS DISTINCT FROM validation.observed_cost_usd
           OR second_attribution.event_sequence <> 14
           OR attributed_event.manifest_hash
                IS DISTINCT FROM validation.manifest_hash
           OR attributed_event.event_hash
                IS DISTINCT FROM validation.event_hash
           OR attributed_event.evidence_hash
                IS DISTINCT FROM validation.attribution_hash
           OR attributed_event.current_head_hash
                IS DISTINCT FROM validation.cursor_head_hash THEN
            RAISE EXCEPTION
                'V13 request-2 validation attestation drifted'
                USING ERRCODE = '55000';
        END IF;

        SELECT outcome.failure_code, outcome.provenance_hash
        INTO durable_failure
        FROM public.agent_graph_attributed_failure_outcomes outcome
        WHERE outcome.principal_id = checked_principal
          AND outcome.attempt_id = checked_attempt;
        IF validation.decision_kind = 'FAILED' THEN
            IF durable_failure.failure_code
                    IS DISTINCT FROM validation.failure_code
               OR durable_failure.provenance_hash
                    IS DISTINCT FROM validation.failure_provenance_hash THEN
                RAISE EXCEPTION
                    'V13 provider failure attestation drifted'
                    USING ERRCODE = '55000';
            END IF;
        ELSIF durable_failure.failure_code IS NOT NULL THEN
            RAISE EXCEPTION
                'V13 structured-final attestation has failure truth'
                USING ERRCODE = '55000';
        END IF;
    ELSIF validation.state <> 'REQUIRED' THEN
        RAISE EXCEPTION 'V13 staged validation cannot commit alone'
            USING ERRCODE = '55000';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V13 provider validation coherence is missing'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_assert_provider_validation_v13()
FROM PUBLIC;

CREATE CONSTRAINT TRIGGER agent_graph_provider_validation_state_v13
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_provider_validations
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_provider_validation_v13();

CREATE CONSTRAINT TRIGGER agent_graph_provider_validation_attribution_v13
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_provider_attributions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_provider_validation_v13();

CREATE CONSTRAINT TRIGGER agent_graph_provider_validation_event_v13
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_provider_validation_v13();

CREATE CONSTRAINT TRIGGER agent_graph_provider_validation_head_v13
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_heads
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_provider_validation_v13();

CREATE CONSTRAINT TRIGGER agent_graph_provider_validation_failure_v13
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attributed_failure_outcomes
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_provider_validation_v13();
