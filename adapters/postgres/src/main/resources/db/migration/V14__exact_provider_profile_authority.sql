-- V14 adds an assert-only, exact provider/pricing profile authority.
--
-- This migration deliberately does not extend the frozen V13 transcript,
-- create a V14 attestation, or authorize the sequence-13 -> sequence-14
-- transition.  The assertion function is read-only: it reads an ACTIVE
-- profile, verifies a closed bounded statement, and returns only hashes and
-- an exact pico-USD cost.  Historical V1-V13 rows are not backfilled or
-- updated.

CREATE FUNCTION agent_graph_framed_sha256_v14(
    hash_domain VARCHAR,
    hash_fields TEXT[]
)
RETURNS CHAR(64)
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL UNSAFE
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    framed BYTEA;
    field_value TEXT;
    field_bytes BYTEA;
BEGIN
    IF hash_domain !~ '^emergeos\.[a-z0-9][a-z0-9.-]{0,126}\.v[1-9][0-9]*$'
       OR pg_catalog.cardinality(hash_fields) NOT BETWEEN 1 AND 64 THEN
        RAISE EXCEPTION 'V14 canonical hash domain is invalid'
            USING ERRCODE = '22023';
    END IF;
    framed := pg_catalog.convert_to(hash_domain, 'UTF8')
        || pg_catalog.decode('00', 'hex');
    FOREACH field_value IN ARRAY hash_fields LOOP
        IF field_value IS NULL THEN
            RAISE EXCEPTION 'V14 canonical hash field is missing'
                USING ERRCODE = '22023';
        END IF;
        field_bytes := pg_catalog.convert_to(field_value, 'UTF8');
        IF pg_catalog.octet_length(field_bytes) > 4096 THEN
            RAISE EXCEPTION 'V14 canonical hash field is oversized'
                USING ERRCODE = '22023';
        END IF;
        framed := framed
            || pg_catalog.int4send(pg_catalog.octet_length(field_bytes))
            || field_bytes;
    END LOOP;
    RETURN pg_catalog.encode(pg_catalog.sha256(framed), 'hex');
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_framed_sha256_v14(
    VARCHAR, TEXT[]) FROM PUBLIC;

CREATE TABLE agent_graph_provider_profiles_v14 (
    profile_id VARCHAR(200) NOT NULL,
    provider_id VARCHAR(128) NOT NULL,
    provider_protocol VARCHAR(128) NOT NULL,
    transport_profile_id VARCHAR(200) NOT NULL,
    transport_profile_hash CHAR(64) NOT NULL,
    parser_profile_id VARCHAR(200) NOT NULL,
    parser_profile_hash CHAR(64) NOT NULL,
    schema_profile_id VARCHAR(200) NOT NULL,
    schema_profile_hash CHAR(64) NOT NULL,
    model_profile_id VARCHAR(200) NOT NULL,
    model_profile_hash CHAR(64) NOT NULL,
    model_requested VARCHAR(512) NOT NULL,
    model_resolution_profile_hash CHAR(64) NOT NULL,
    pricing_profile_id VARCHAR(200) NOT NULL,
    pricing_provider_id VARCHAR(128) NOT NULL,
    pricing_profile_fingerprint CHAR(64) NOT NULL,
    pricing_source_hash CHAR(64) NOT NULL,
    pricing_effective_from_epoch_micros BIGINT NOT NULL,
    pricing_effective_until_epoch_micros BIGINT NOT NULL,
    rate_unit VARCHAR(24) NOT NULL,
    uncached_input_pico_usd_per_token BIGINT NOT NULL,
    cached_input_pico_usd_per_token BIGINT NOT NULL,
    output_pico_usd_per_token BIGINT NOT NULL,
    reasoning_policy VARCHAR(16) NOT NULL,
    profile_hash CHAR(64) GENERATED ALWAYS AS (
        public.agent_graph_framed_sha256_v14(
            'emergeos.provider-profile.v14',
            ARRAY[
                profile_id::TEXT,
                provider_id::TEXT,
                provider_protocol::TEXT,
                transport_profile_id::TEXT,
                transport_profile_hash::TEXT,
                parser_profile_id::TEXT,
                parser_profile_hash::TEXT,
                schema_profile_id::TEXT,
                schema_profile_hash::TEXT,
                model_profile_id::TEXT,
                model_profile_hash::TEXT,
                model_requested::TEXT,
                model_resolution_profile_hash::TEXT,
                pricing_profile_id::TEXT,
                pricing_provider_id::TEXT,
                pricing_profile_fingerprint::TEXT,
                pricing_source_hash::TEXT,
                pricing_effective_from_epoch_micros::TEXT,
                pricing_effective_until_epoch_micros::TEXT,
                rate_unit::TEXT,
                uncached_input_pico_usd_per_token::TEXT,
                cached_input_pico_usd_per_token::TEXT,
                output_pico_usd_per_token::TEXT,
                reasoning_policy::TEXT
            ])) STORED,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT graph_provider_profiles_pk_v14
        PRIMARY KEY (profile_id),
    CONSTRAINT graph_provider_profiles_hash_uq_v14
        UNIQUE (profile_hash),
    CONSTRAINT graph_provider_profiles_shape_v14 CHECK (
        profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND provider_id ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND provider_protocol ~ '^[a-z][a-z0-9._-]{0,127}$'
        AND transport_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND transport_profile_hash ~ '^[0-9a-f]{64}$'
        AND parser_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND parser_profile_hash ~ '^[0-9a-f]{64}$'
        AND schema_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND schema_profile_hash ~ '^[0-9a-f]{64}$'
        AND model_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND model_profile_hash ~ '^[0-9a-f]{64}$'
        AND model_requested ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        AND model_resolution_profile_hash ~ '^[0-9a-f]{64}$'
        AND pricing_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND pricing_provider_id = provider_id
        AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND pricing_source_hash ~ '^[0-9a-f]{64}$'
        AND pricing_effective_from_epoch_micros >= 0
        AND pricing_effective_until_epoch_micros
              > pricing_effective_from_epoch_micros
        AND rate_unit = 'PICO_USD_PER_TOKEN'
        AND uncached_input_pico_usd_per_token BETWEEN 1
              AND 9007199254740991
        AND cached_input_pico_usd_per_token BETWEEN 0
              AND uncached_input_pico_usd_per_token
        AND output_pico_usd_per_token BETWEEN 1
              AND 9007199254740991
        AND reasoning_policy = 'NONE'
        AND status IN ('ACTIVE', 'REVOKED')
    )
);

CREATE FUNCTION agent_graph_assert_provider_statement_v14(
    payload JSONB
)
RETURNS TABLE (
    profile_hash CHAR(64),
    observed_cost_pico_usd NUMERIC(38, 0),
    statement_hash CHAR(64)
)
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_profile public.agent_graph_provider_profiles_v14%ROWTYPE;
    statement_input_tokens BIGINT;
    statement_cached_input_tokens BIGINT;
    statement_output_tokens BIGINT;
    statement_reasoning_output_tokens BIGINT;
    statement_total_tokens BIGINT;
    statement_uncached_rate BIGINT;
    statement_cached_rate BIGINT;
    statement_output_rate BIGINT;
    claimed_cost NUMERIC(38, 0);
    computed_cost NUMERIC(38, 0);
    computed_statement_hash CHAR(64);
    now_epoch_micros BIGINT;
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor_v14' THEN
        RAISE EXCEPTION 'V14 provider statement authority rejected'
            USING ERRCODE = '42501';
    END IF;
    IF payload IS NULL
       OR pg_catalog.jsonb_typeof(payload) IS DISTINCT FROM 'object'
       OR pg_catalog.pg_column_size(payload) > 16384 THEN
        RAISE EXCEPTION 'V14 provider statement input is invalid'
            USING ERRCODE = '22023';
    END IF;

    IF EXISTS (
            SELECT expected.key
            FROM (VALUES
                ('profile_id'), ('profile_hash'), ('provider_id'),
                ('provider_protocol'), ('transport_profile_id'),
                ('transport_profile_hash'), ('parser_profile_id'),
                ('parser_profile_hash'), ('schema_profile_id'),
                ('schema_profile_hash'), ('model_profile_id'),
                ('model_profile_hash'), ('model_requested'),
                ('model_resolved_hash'),
                ('model_resolution_profile_hash'),
                ('pricing_profile_id'), ('pricing_provider_id'),
                ('pricing_profile_fingerprint'),
                ('pricing_source_hash'), ('rate_unit'),
                ('uncached_input_pico_usd_per_token'),
                ('cached_input_pico_usd_per_token'),
                ('output_pico_usd_per_token'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('reasoning_policy'), ('observed_cost_pico_usd'),
                ('execution_binding_hash'), ('request_hash'),
                ('response_hash'), ('decision_hash')
            ) expected(key)
            EXCEPT
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
       )
       OR EXISTS (
            SELECT key FROM pg_catalog.jsonb_object_keys(payload) key
            EXCEPT
            SELECT expected.key
            FROM (VALUES
                ('profile_id'), ('profile_hash'), ('provider_id'),
                ('provider_protocol'), ('transport_profile_id'),
                ('transport_profile_hash'), ('parser_profile_id'),
                ('parser_profile_hash'), ('schema_profile_id'),
                ('schema_profile_hash'), ('model_profile_id'),
                ('model_profile_hash'), ('model_requested'),
                ('model_resolved_hash'),
                ('model_resolution_profile_hash'),
                ('pricing_profile_id'), ('pricing_provider_id'),
                ('pricing_profile_fingerprint'),
                ('pricing_source_hash'), ('rate_unit'),
                ('uncached_input_pico_usd_per_token'),
                ('cached_input_pico_usd_per_token'),
                ('output_pico_usd_per_token'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('reasoning_policy'), ('observed_cost_pico_usd'),
                ('execution_binding_hash'), ('request_hash'),
                ('response_hash'), ('decision_hash')
            ) expected(key)
       )
       OR EXISTS (
            SELECT required.key
            FROM (VALUES
                ('profile_id'), ('profile_hash'), ('provider_id'),
                ('provider_protocol'), ('transport_profile_id'),
                ('transport_profile_hash'), ('parser_profile_id'),
                ('parser_profile_hash'), ('schema_profile_id'),
                ('schema_profile_hash'), ('model_profile_id'),
                ('model_profile_hash'), ('model_requested'),
                ('model_resolved_hash'),
                ('model_resolution_profile_hash'),
                ('pricing_profile_id'), ('pricing_provider_id'),
                ('pricing_profile_fingerprint'),
                ('pricing_source_hash'), ('rate_unit'),
                ('reasoning_policy'), ('execution_binding_hash'),
                ('request_hash'), ('response_hash'), ('decision_hash')
            ) required(key)
            WHERE pg_catalog.jsonb_typeof(payload -> required.key)
                    IS DISTINCT FROM 'string'
       )
       OR EXISTS (
            SELECT required.key
            FROM (VALUES
                ('uncached_input_pico_usd_per_token'),
                ('cached_input_pico_usd_per_token'),
                ('output_pico_usd_per_token'), ('input_tokens'),
                ('cached_input_tokens'), ('output_tokens'),
                ('reasoning_output_tokens'), ('total_tokens'),
                ('observed_cost_pico_usd')
            ) required(key)
            WHERE pg_catalog.jsonb_typeof(payload -> required.key)
                    IS DISTINCT FROM 'number'
       )
       OR payload ->> 'profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'profile_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'provider_id'
            !~ '^[a-z][a-z0-9._-]{0,127}$'
       OR payload ->> 'provider_protocol'
            !~ '^[a-z][a-z0-9._-]{0,127}$'
       OR payload ->> 'transport_profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'transport_profile_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'parser_profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'parser_profile_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'schema_profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'schema_profile_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'model_profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'model_profile_hash' !~ '^[0-9a-f]{64}$'
       OR pg_catalog.octet_length(pg_catalog.convert_to(
            payload ->> 'model_requested', 'UTF8')) > 512
       OR payload ->> 'model_requested'
            !~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
       OR payload ->> 'model_resolved_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'model_resolution_profile_hash'
            !~ '^[0-9a-f]{64}$'
       OR payload ->> 'pricing_profile_id'
            !~ '^[a-z][a-z0-9._-]{0,199}$'
       OR payload ->> 'pricing_provider_id'
            !~ '^[a-z][a-z0-9._-]{0,127}$'
       OR payload ->> 'pricing_profile_fingerprint'
            !~ '^[0-9a-f]{64}$'
       OR payload ->> 'pricing_source_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'rate_unit' <> 'PICO_USD_PER_TOKEN'
       OR payload ->> 'reasoning_policy' <> 'NONE'
       OR payload ->> 'execution_binding_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'request_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'response_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'decision_hash' !~ '^[0-9a-f]{64}$'
       OR payload ->> 'uncached_input_pico_usd_per_token'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'cached_input_pico_usd_per_token'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'output_pico_usd_per_token'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'input_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'cached_input_tokens'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'output_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'reasoning_output_tokens'
            !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'total_tokens' !~ '^(0|[1-9][0-9]{0,15})$'
       OR payload ->> 'observed_cost_pico_usd'
            !~ '^(0|[1-9][0-9]{0,37})$' THEN
        RAISE EXCEPTION 'V14 provider statement input is invalid'
            USING ERRCODE = '22023';
    END IF;

    statement_uncached_rate :=
        (payload ->> 'uncached_input_pico_usd_per_token')::BIGINT;
    statement_cached_rate :=
        (payload ->> 'cached_input_pico_usd_per_token')::BIGINT;
    statement_output_rate :=
        (payload ->> 'output_pico_usd_per_token')::BIGINT;
    statement_input_tokens := (payload ->> 'input_tokens')::BIGINT;
    statement_cached_input_tokens :=
        (payload ->> 'cached_input_tokens')::BIGINT;
    statement_output_tokens := (payload ->> 'output_tokens')::BIGINT;
    statement_reasoning_output_tokens :=
        (payload ->> 'reasoning_output_tokens')::BIGINT;
    statement_total_tokens := (payload ->> 'total_tokens')::BIGINT;
    claimed_cost :=
        (payload ->> 'observed_cost_pico_usd')::NUMERIC(38, 0);

    SELECT durable.*
    INTO STRICT locked_profile
    FROM public.agent_graph_provider_profiles_v14 durable
    WHERE durable.profile_id = payload ->> 'profile_id';

    now_epoch_micros := pg_catalog.floor(
        EXTRACT(epoch FROM pg_catalog.clock_timestamp())
            * 1000000)::BIGINT;
    computed_cost :=
        (statement_input_tokens - statement_cached_input_tokens)::NUMERIC
            * statement_uncached_rate
        + statement_cached_input_tokens::NUMERIC * statement_cached_rate
        + statement_output_tokens::NUMERIC * statement_output_rate;

    IF locked_profile.status IS DISTINCT FROM 'ACTIVE'
       OR now_epoch_micros NOT BETWEEN
            locked_profile.pricing_effective_from_epoch_micros
            AND locked_profile.pricing_effective_until_epoch_micros
       OR locked_profile.profile_hash
            IS DISTINCT FROM payload ->> 'profile_hash'
       OR locked_profile.provider_id
            IS DISTINCT FROM payload ->> 'provider_id'
       OR locked_profile.provider_protocol
            IS DISTINCT FROM payload ->> 'provider_protocol'
       OR locked_profile.transport_profile_id
            IS DISTINCT FROM payload ->> 'transport_profile_id'
       OR locked_profile.transport_profile_hash
            IS DISTINCT FROM payload ->> 'transport_profile_hash'
       OR locked_profile.parser_profile_id
            IS DISTINCT FROM payload ->> 'parser_profile_id'
       OR locked_profile.parser_profile_hash
            IS DISTINCT FROM payload ->> 'parser_profile_hash'
       OR locked_profile.schema_profile_id
            IS DISTINCT FROM payload ->> 'schema_profile_id'
       OR locked_profile.schema_profile_hash
            IS DISTINCT FROM payload ->> 'schema_profile_hash'
       OR locked_profile.model_profile_id
            IS DISTINCT FROM payload ->> 'model_profile_id'
       OR locked_profile.model_profile_hash
            IS DISTINCT FROM payload ->> 'model_profile_hash'
       OR locked_profile.model_requested
            IS DISTINCT FROM payload ->> 'model_requested'
       OR locked_profile.model_resolution_profile_hash
            IS DISTINCT FROM payload ->> 'model_resolution_profile_hash'
       OR locked_profile.pricing_profile_id
            IS DISTINCT FROM payload ->> 'pricing_profile_id'
       OR locked_profile.pricing_provider_id
            IS DISTINCT FROM payload ->> 'pricing_provider_id'
       OR locked_profile.pricing_profile_fingerprint
            IS DISTINCT FROM payload ->> 'pricing_profile_fingerprint'
       OR locked_profile.pricing_source_hash
            IS DISTINCT FROM payload ->> 'pricing_source_hash'
       OR locked_profile.rate_unit IS DISTINCT FROM payload ->> 'rate_unit'
       OR locked_profile.uncached_input_pico_usd_per_token
            IS DISTINCT FROM statement_uncached_rate
       OR locked_profile.cached_input_pico_usd_per_token
            IS DISTINCT FROM statement_cached_rate
       OR locked_profile.output_pico_usd_per_token
            IS DISTINCT FROM statement_output_rate
       OR locked_profile.reasoning_policy
            IS DISTINCT FROM payload ->> 'reasoning_policy'
       OR statement_input_tokens NOT BETWEEN 0 AND 9007199254740991
       OR statement_cached_input_tokens NOT BETWEEN 0
            AND statement_input_tokens
       OR statement_output_tokens NOT BETWEEN 0 AND 9007199254740991
       OR statement_reasoning_output_tokens NOT BETWEEN 0
            AND statement_output_tokens
       OR statement_total_tokens
            IS DISTINCT FROM statement_input_tokens + statement_output_tokens
       OR (locked_profile.reasoning_policy = 'NONE'
           AND statement_reasoning_output_tokens <> 0)
       OR computed_cost IS DISTINCT FROM claimed_cost THEN
        RAISE EXCEPTION 'V14 provider statement was fenced'
            USING ERRCODE = '55000';
    END IF;

    computed_statement_hash := public.agent_graph_framed_sha256_v14(
        'emergeos.provider-statement.v14',
        ARRAY[
            locked_profile.profile_hash::TEXT,
            payload ->> 'model_resolved_hash',
            statement_input_tokens::TEXT,
            statement_cached_input_tokens::TEXT,
            statement_output_tokens::TEXT,
            statement_reasoning_output_tokens::TEXT,
            statement_total_tokens::TEXT,
            computed_cost::TEXT,
            payload ->> 'execution_binding_hash',
            payload ->> 'request_hash',
            payload ->> 'response_hash',
            payload ->> 'decision_hash'
        ]);
    RETURN QUERY SELECT
        locked_profile.profile_hash,
        computed_cost,
        computed_statement_hash;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V14 provider statement was fenced'
            USING ERRCODE = '55000';
    WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RAISE EXCEPTION 'V14 provider statement input is invalid'
            USING ERRCODE = '22023';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_assert_provider_statement_v14(JSONB)
FROM PUBLIC;
