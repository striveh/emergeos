-- V15 selects an exact-pico TX-A authority without implementing TX-A.
--
-- A requirement is enrolled only against the immutable sequence-13 event
-- and the live sequence-13 V8 head.  Once enrolled, the historical V8/V13
-- request-2 attribution/event/head transition is fenced by a deferred
-- database guard.  No exact attribution, challenge, signature, transcript,
-- overlay event or overlay head is created by this migration.

CREATE TABLE agent_graph_exact_tx_a_requirements_v15 (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    protocol_version VARCHAR(32) NOT NULL,
    database_name VARCHAR(63) NOT NULL,
    database_oid OID NOT NULL,
    schema_oid OID NOT NULL,
    attestor_role_oid OID NOT NULL,
    base_sequence INTEGER NOT NULL,
    base_head_hash CHAR(64) NOT NULL,
    execution_binding_hash CHAR(64) NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    provider_profile_id VARCHAR(200) NOT NULL,
    provider_profile_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    requirement_hash CHAR(64) GENERATED ALWAYS AS (
        public.agent_graph_framed_sha256_v14(
            'emergeos.graph-exact-tx-a-requirement.v15',
            ARRAY[
                protocol_version::TEXT,
                database_name::TEXT,
                database_oid::TEXT,
                schema_oid::TEXT,
                attestor_role_oid::TEXT,
                principal_id::TEXT,
                attempt_id::TEXT,
                manifest_hash::TEXT,
                base_sequence::TEXT,
                base_head_hash::TEXT,
                execution_binding_hash::TEXT,
                request_ordinal::TEXT,
                provider_profile_id::TEXT,
                provider_profile_hash::TEXT,
                state::TEXT
            ])) STORED,
    required_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT graph_exact_tx_a_requirements_pk_v15
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT graph_exact_tx_a_requirements_hash_uq_v15
        UNIQUE (principal_id, requirement_hash),
    CONSTRAINT graph_exact_tx_a_requirements_attempt_fk_v15
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id, attempt_id, manifest_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_tx_a_requirements_event_fk_v15
        FOREIGN KEY (
            principal_id, attempt_id, base_sequence, base_head_hash
        ) REFERENCES agent_graph_attempt_events (
            principal_id, attempt_id, sequence, current_head_hash
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_tx_a_requirements_profile_id_fk_v15
        FOREIGN KEY (provider_profile_id)
        REFERENCES agent_graph_provider_profiles_v14 (profile_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_tx_a_requirements_profile_hash_fk_v15
        FOREIGN KEY (provider_profile_hash)
        REFERENCES agent_graph_provider_profiles_v14 (profile_hash)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_exact_tx_a_requirements_shape_v15 CHECK (
        char_length(btrim(principal_id)) BETWEEN 1 AND 200
        AND attempt_id ~ '^[0-9a-f]{64}$'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND protocol_version = 'PICO_OVERLAY_V1'
        AND char_length(database_name) BETWEEN 1 AND 63
        AND database_oid <> 0
        AND schema_oid <> 0
        AND attestor_role_oid <> 0
        AND base_sequence = 13
        AND base_head_hash ~ '^[0-9a-f]{64}$'
        AND execution_binding_hash = manifest_hash
        AND request_ordinal = 2
        AND provider_profile_id ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND provider_profile_hash ~ '^[0-9a-f]{64}$'
        AND state = 'REQUIRED'
        AND requirement_hash ~ '^[0-9a-f]{64}$'
    )
);

REVOKE ALL ON TABLE agent_graph_exact_tx_a_requirements_v15
FROM PUBLIC;

CREATE FUNCTION agent_graph_require_exact_tx_a_v15(
    checked_principal VARCHAR,
    checked_attempt CHAR,
    checked_manifest CHAR,
    checked_profile_id VARCHAR
)
RETURNS CHAR(64)
LANGUAGE plpgsql
VOLATILE
PARALLEL UNSAFE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE
    locked_head RECORD;
    request_intent RECORD;
    locked_profile RECORD;
    target_schema_oid OID;
    target_role_oid OID;
    target_database_oid OID;
    requirement_time TIMESTAMPTZ;
    now_epoch_micros BIGINT;
    computed_requirement_hash CHAR(64);
BEGIN
    IF session_user IS DISTINCT FROM 'emergeos_provider_attestor_v15' THEN
        RAISE EXCEPTION 'V15 exact TX-A requirement authority rejected'
            USING ERRCODE = '42501';
    END IF;
    IF checked_principal IS NULL
       OR checked_attempt IS NULL
       OR checked_manifest IS NULL
       OR checked_profile_id IS NULL
       OR char_length(btrim(checked_principal)) NOT BETWEEN 1 AND 200
       OR checked_attempt !~ '^[0-9a-f]{64}$'
       OR checked_manifest !~ '^[0-9a-f]{64}$'
       OR checked_profile_id !~ '^[a-z][a-z0-9._-]{0,199}$' THEN
        RAISE EXCEPTION 'V15 exact TX-A requirement input is invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT head.manifest_hash, head.state_version,
           head.last_sequence, head.head_hash,
           head.phase, head.billing_status,
           head.provider_intent_count,
           head.provider_attribution_count
    INTO STRICT locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = checked_principal
      AND head.attempt_id = checked_attempt
    FOR UPDATE;

    PERFORM public.agent_assert_graph_attempt_v8(
        checked_principal, checked_attempt);

    SELECT event.manifest_hash, event.event_type,
           event.request_ordinal, event.request_hash,
           event.model_requested, event.current_head_hash
    INTO STRICT request_intent
    FROM public.agent_graph_attempt_events event
    WHERE event.principal_id = checked_principal
      AND event.attempt_id = checked_attempt
      AND event.sequence = 13;

    SELECT profile.profile_id, profile.profile_hash,
           profile.status,
           profile.pricing_effective_from_epoch_micros,
           profile.pricing_effective_until_epoch_micros
    INTO STRICT locked_profile
    FROM public.agent_graph_provider_profiles_v14 profile
    WHERE profile.profile_id = checked_profile_id;

    SELECT namespace.oid
    INTO STRICT target_schema_oid
    FROM pg_catalog.pg_namespace namespace
    WHERE namespace.nspname = 'public';
    SELECT role.oid
    INTO STRICT target_role_oid
    FROM pg_catalog.pg_roles role
    WHERE role.rolname = session_user;
    SELECT database.oid
    INTO STRICT target_database_oid
    FROM pg_catalog.pg_database database
    WHERE database.datname = current_database();

    requirement_time := pg_catalog.clock_timestamp();
    now_epoch_micros := pg_catalog.floor(
        EXTRACT(epoch FROM requirement_time) * 1000000)::BIGINT;
    IF locked_head.manifest_hash IS DISTINCT FROM checked_manifest
       OR locked_head.state_version <> 13
       OR locked_head.last_sequence <> 13
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR request_intent.manifest_hash
            IS DISTINCT FROM checked_manifest
       OR request_intent.event_type <> 'PROVIDER_INTENT'
       OR request_intent.request_ordinal <> 2
       OR request_intent.request_hash IS NULL
       OR request_intent.model_requested IS NULL
       OR request_intent.current_head_hash
            IS DISTINCT FROM locked_head.head_hash
       OR locked_profile.status <> 'ACTIVE'
       OR now_epoch_micros NOT BETWEEN
            locked_profile.pricing_effective_from_epoch_micros
            AND locked_profile.pricing_effective_until_epoch_micros
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_provider_attributions
                attribution
            WHERE attribution.principal_id = checked_principal
              AND attribution.attempt_id = checked_attempt
              AND attribution.request_ordinal = 2)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_events event
            WHERE event.principal_id = checked_principal
              AND event.attempt_id = checked_attempt
              AND event.sequence = 14)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_exact_tx_a_requirements_v15
                requirement
            WHERE requirement.principal_id = checked_principal
              AND requirement.attempt_id = checked_attempt) THEN
        RAISE EXCEPTION 'V15 exact TX-A requirement was fenced'
            USING ERRCODE = '55000';
    END IF;

    INSERT INTO public.agent_graph_exact_tx_a_requirements_v15 (
        principal_id, attempt_id, manifest_hash,
        protocol_version, database_name, database_oid,
        schema_oid, attestor_role_oid,
        base_sequence, base_head_hash, execution_binding_hash,
        request_ordinal, provider_profile_id, provider_profile_hash,
        state, required_at
    ) VALUES (
        checked_principal, checked_attempt, checked_manifest,
        'PICO_OVERLAY_V1', current_database(), target_database_oid,
        target_schema_oid, target_role_oid,
        13, locked_head.head_hash, checked_manifest,
        2, locked_profile.profile_id, locked_profile.profile_hash,
        'REQUIRED', requirement_time
    ) RETURNING requirement_hash INTO computed_requirement_hash;

    SET CONSTRAINTS ALL IMMEDIATE;
    RETURN computed_requirement_hash;
EXCEPTION
    WHEN no_data_found OR too_many_rows THEN
        RAISE EXCEPTION 'V15 exact TX-A requirement was fenced'
            USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_require_exact_tx_a_v15(
    VARCHAR, CHAR, CHAR, VARCHAR) FROM PUBLIC;

CREATE FUNCTION agent_graph_assert_exact_tx_a_requirement_v15()
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
    locked_head RECORD;
    head_exists BOOLEAN;
    requirement RECORD;
BEGIN
    IF TG_TABLE_NAME = 'agent_graph_exact_tx_a_requirements_v15'
       AND TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'V15 exact provider attribution is required'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        checked_principal := OLD.principal_id;
        checked_attempt := OLD.attempt_id;
    ELSE
        checked_principal := NEW.principal_id;
        checked_attempt := NEW.attempt_id;
    END IF;

    IF TG_OP = 'UPDATE'
       AND (NEW.principal_id IS DISTINCT FROM OLD.principal_id
            OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id)
       AND (
            EXISTS (
                SELECT 1
                FROM public.agent_graph_exact_tx_a_requirements_v15
                    durable
                WHERE durable.principal_id = OLD.principal_id
                  AND durable.attempt_id = OLD.attempt_id)
            OR EXISTS (
                SELECT 1
                FROM public.agent_graph_exact_tx_a_requirements_v15
                    durable
                WHERE durable.principal_id = NEW.principal_id
                  AND durable.attempt_id = NEW.attempt_id)) THEN
        RAISE EXCEPTION 'V15 exact provider attribution is required'
            USING ERRCODE = '55000';
    END IF;

    SELECT head.manifest_hash, head.state_version,
           head.last_sequence, head.head_hash,
           head.phase, head.billing_status,
           head.provider_intent_count,
           head.provider_attribution_count
    INTO locked_head
    FROM public.agent_graph_attempt_heads head
    WHERE head.principal_id = checked_principal
      AND head.attempt_id = checked_attempt
    FOR UPDATE;
    head_exists := FOUND;

    SELECT durable.manifest_hash, durable.protocol_version,
           durable.database_name, durable.database_oid,
           durable.schema_oid, durable.attestor_role_oid,
           durable.base_sequence,
           durable.base_head_hash, durable.execution_binding_hash,
           durable.request_ordinal,
           durable.provider_profile_id,
           durable.provider_profile_hash,
           durable.state
    INTO requirement
    FROM public.agent_graph_exact_tx_a_requirements_v15 durable
    WHERE durable.principal_id = checked_principal
      AND durable.attempt_id = checked_attempt;
    IF NOT FOUND THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;

    IF NOT head_exists
       OR locked_head.manifest_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR locked_head.state_version <> 13
       OR locked_head.last_sequence <> 13
       OR locked_head.head_hash
            IS DISTINCT FROM requirement.base_head_hash
       OR locked_head.phase <> 'PROVIDER_PENDING'
       OR locked_head.billing_status <> 'UNKNOWN'
       OR locked_head.provider_intent_count <> 2
       OR locked_head.provider_attribution_count <> 1
       OR requirement.protocol_version <> 'PICO_OVERLAY_V1'
       OR requirement.database_name <> current_database()
       OR requirement.database_oid IS DISTINCT FROM (
            SELECT database.oid
            FROM pg_catalog.pg_database database
            WHERE database.datname = current_database())
       OR requirement.schema_oid IS DISTINCT FROM (
            SELECT namespace.oid
            FROM pg_catalog.pg_namespace namespace
            WHERE namespace.nspname = 'public')
       OR requirement.attestor_role_oid IS DISTINCT FROM (
            SELECT role.oid
            FROM pg_catalog.pg_roles role
            WHERE role.rolname = 'emergeos_provider_attestor_v15')
       OR requirement.base_sequence <> 13
       OR requirement.execution_binding_hash
            IS DISTINCT FROM requirement.manifest_hash
       OR requirement.request_ordinal <> 2
       OR requirement.state <> 'REQUIRED'
       OR NOT EXISTS (
            SELECT 1
            FROM public.agent_graph_provider_profiles_v14 profile
            WHERE profile.profile_id
                    = requirement.provider_profile_id
              AND profile.profile_hash
                    = requirement.provider_profile_hash
              AND profile.status = 'ACTIVE')
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_provider_attributions
                attribution
            WHERE attribution.principal_id = checked_principal
              AND attribution.attempt_id = checked_attempt
              AND attribution.request_ordinal = 2)
       OR EXISTS (
            SELECT 1
            FROM public.agent_graph_attempt_events event
            WHERE event.principal_id = checked_principal
              AND event.attempt_id = checked_attempt
              AND event.sequence = 14) THEN
        RAISE EXCEPTION 'V15 exact provider attribution is required'
            USING ERRCODE = '55000';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$function$;

REVOKE ALL ON FUNCTION agent_graph_assert_exact_tx_a_requirement_v15()
FROM PUBLIC;

CREATE CONSTRAINT TRIGGER agent_graph_exact_tx_a_requirement_v15
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_exact_tx_a_requirements_v15
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_requirement_v15();

CREATE CONSTRAINT TRIGGER agent_graph_exact_tx_a_attribution_v15
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_provider_attributions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_requirement_v15();

CREATE CONSTRAINT TRIGGER agent_graph_exact_tx_a_event_v15
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_requirement_v15();

CREATE CONSTRAINT TRIGGER agent_graph_exact_tx_a_head_v15
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_heads
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_graph_assert_exact_tx_a_requirement_v15();
