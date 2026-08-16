CREATE FUNCTION emerge_action_approval_scope_hash_v1(
    p_principal_basis TEXT,
    p_configured_principal_id TEXT,
    p_approval_origin TEXT,
    p_execution_route TEXT,
    p_action_type TEXT,
    p_target_ref TEXT,
    p_artifact_id TEXT,
    p_artifact_version INTEGER,
    p_artifact_hash TEXT,
    p_risk TEXT,
    p_policy_version TEXT,
    p_connector TEXT,
    p_audience TEXT,
    p_account_ref TEXT,
    p_capability_ttl_micros BIGINT,
    p_max_calls INTEGER
) RETURNS TEXT
LANGUAGE SQL
IMMUTABLE STRICT PARALLEL SAFE
AS $$
SELECT encode(
    sha256(
        convert_to('emergeos.action-approval-scope.v1', 'UTF8') || decode('00', 'hex')
        || int4send(octet_length(convert_to(p_principal_basis, 'UTF8'))) || convert_to(p_principal_basis, 'UTF8')
        || int4send(octet_length(convert_to(p_configured_principal_id, 'UTF8'))) || convert_to(p_configured_principal_id, 'UTF8')
        || int4send(octet_length(convert_to(p_approval_origin, 'UTF8'))) || convert_to(p_approval_origin, 'UTF8')
        || int4send(octet_length(convert_to(p_execution_route, 'UTF8'))) || convert_to(p_execution_route, 'UTF8')
        || int4send(octet_length(convert_to(p_action_type, 'UTF8'))) || convert_to(p_action_type, 'UTF8')
        || int4send(octet_length(convert_to(p_target_ref, 'UTF8'))) || convert_to(p_target_ref, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_id, 'UTF8'))) || convert_to(p_artifact_id, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_version::TEXT, 'UTF8'))) || convert_to(p_artifact_version::TEXT, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_hash, 'UTF8'))) || convert_to(p_artifact_hash, 'UTF8')
        || int4send(octet_length(convert_to(p_risk, 'UTF8'))) || convert_to(p_risk, 'UTF8')
        || int4send(octet_length(convert_to(p_policy_version, 'UTF8'))) || convert_to(p_policy_version, 'UTF8')
        || int4send(octet_length(convert_to(p_connector, 'UTF8'))) || convert_to(p_connector, 'UTF8')
        || int4send(octet_length(convert_to(p_audience, 'UTF8'))) || convert_to(p_audience, 'UTF8')
        || int4send(octet_length(convert_to(p_account_ref, 'UTF8'))) || convert_to(p_account_ref, 'UTF8')
        || int4send(octet_length(convert_to(p_capability_ttl_micros::TEXT, 'UTF8'))) || convert_to(p_capability_ttl_micros::TEXT, 'UTF8')
        || int4send(octet_length(convert_to(p_max_calls::TEXT, 'UTF8'))) || convert_to(p_max_calls::TEXT, 'UTF8')
    ),
    'hex'
)
$$;

ALTER TABLE action_attempts
    ADD COLUMN approval_principal_basis VARCHAR(64) NOT NULL
        DEFAULT 'CONFIGURED_LOCAL_PRINCIPAL',
    ADD COLUMN configured_principal_id VARCHAR(200),
    ADD COLUMN approval_origin VARCHAR(64) NOT NULL
        DEFAULT 'PRE_V17_UNPROVEN',
    ADD COLUMN execution_route VARCHAR(64) NOT NULL
        DEFAULT 'SIMULATED_PROVIDER_V1',
    ADD COLUMN scope_schema VARCHAR(200) NOT NULL
        DEFAULT 'emergeos.action-approval-scope.v1',
    ADD COLUMN scope_hash CHAR(64),
    ADD COLUMN scope_capability_ttl_micros BIGINT;

UPDATE action_attempts
SET configured_principal_id = principal_id,
    scope_capability_ttl_micros =
        (extract(epoch FROM (plan_expires_at - approved_at)) * 1000000)::BIGINT;

UPDATE action_attempts
SET scope_hash = emerge_action_approval_scope_hash_v1(
    approval_principal_basis,
    configured_principal_id,
    approval_origin,
    execution_route,
    action_type,
    target_ref,
    artifact_id,
    artifact_version,
    artifact_hash,
    risk,
    policy_version,
    connector,
    capability_audience,
    account_ref,
    scope_capability_ttl_micros,
    capability_max_calls
);

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE action_attempts
    ALTER COLUMN configured_principal_id SET NOT NULL,
    ALTER COLUMN scope_hash SET NOT NULL,
    ALTER COLUMN scope_capability_ttl_micros SET NOT NULL,
    ADD CONSTRAINT action_attempts_approval_principal_basis_v17_valid
        CHECK (
            approval_principal_basis = 'CONFIGURED_LOCAL_PRINCIPAL'
            AND configured_principal_id = principal_id
        ),
    ADD CONSTRAINT action_attempts_approval_origin_v17_valid
        CHECK (approval_origin IN (
            'PRE_V17_UNPROVEN',
            'LEGACY_SERVER_IMPLICIT',
            'EXPLICIT_LOCAL_OWNER_INPUT'
        )),
    ADD CONSTRAINT action_attempts_execution_route_v17_valid
        CHECK (execution_route IN ('SIMULATED_PROVIDER_V1', 'LOCAL_DRAFTBOX_V1')),
    ADD CONSTRAINT action_attempts_provenance_route_v17_valid
        CHECK (
            (
                approval_origin IN ('PRE_V17_UNPROVEN', 'LEGACY_SERVER_IMPLICIT')
                AND execution_route = 'SIMULATED_PROVIDER_V1'
            )
            OR (
                approval_origin = 'EXPLICIT_LOCAL_OWNER_INPUT'
                AND execution_route = 'LOCAL_DRAFTBOX_V1'
            )
        ),
    ADD CONSTRAINT action_attempts_scope_schema_v17_valid
        CHECK (scope_schema = 'emergeos.action-approval-scope.v1'),
    ADD CONSTRAINT action_attempts_scope_ttl_v17_valid
        CHECK (
            (
                approval_origin = 'PRE_V17_UNPROVEN'
                AND scope_capability_ttl_micros >= 0
            )
            OR (
                approval_origin IN ('LEGACY_SERVER_IMPLICIT', 'EXPLICIT_LOCAL_OWNER_INPUT')
                AND scope_capability_ttl_micros > 0
            )
        ),
    ADD CONSTRAINT action_attempts_scope_hash_v17_valid
        CHECK (
            scope_hash ~ '^[0-9a-f]{64}$'
            AND scope_hash = emerge_action_approval_scope_hash_v1(
                approval_principal_basis,
                configured_principal_id,
                approval_origin,
                execution_route,
                action_type,
                target_ref,
                artifact_id,
                artifact_version,
                artifact_hash,
                risk,
                policy_version,
                connector,
                capability_audience,
                account_ref,
                scope_capability_ttl_micros,
                capability_max_calls
            )
        );

CREATE FUNCTION freeze_action_attempt_scope_v17()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'action attempt authority history cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.principal_id IS DISTINCT FROM OLD.principal_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.connector IS DISTINCT FROM OLD.connector
       OR NEW.account_ref IS DISTINCT FROM OLD.account_ref
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.capability_max_calls IS DISTINCT FROM OLD.capability_max_calls
       OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
       OR NEW.plan_hash IS DISTINCT FROM OLD.plan_hash
       OR NEW.action_type IS DISTINCT FROM OLD.action_type
       OR NEW.target_ref IS DISTINCT FROM OLD.target_ref
       OR NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
       OR NEW.artifact_version IS DISTINCT FROM OLD.artifact_version
       OR NEW.artifact_hash IS DISTINCT FROM OLD.artifact_hash
       OR NEW.risk IS DISTINCT FROM OLD.risk
       OR NEW.policy_version IS DISTINCT FROM OLD.policy_version
       OR NEW.plan_expires_at IS DISTINCT FROM OLD.plan_expires_at
       OR NEW.approval_id IS DISTINCT FROM OLD.approval_id
       OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
       OR NEW.capability_id IS DISTINCT FROM OLD.capability_id
       OR NEW.capability_subject IS DISTINCT FROM OLD.capability_subject
       OR NEW.capability_connector IS DISTINCT FROM OLD.capability_connector
       OR NEW.capability_audience IS DISTINCT FROM OLD.capability_audience
       OR NEW.capability_account_ref IS DISTINCT FROM OLD.capability_account_ref
       OR NEW.capability_plan_id IS DISTINCT FROM OLD.capability_plan_id
       OR NEW.capability_plan_hash IS DISTINCT FROM OLD.capability_plan_hash
       OR NEW.capability_artifact_hash IS DISTINCT FROM OLD.capability_artifact_hash
       OR NEW.capability_idempotency_key IS DISTINCT FROM OLD.capability_idempotency_key
       OR NEW.capability_expires_at IS DISTINCT FROM OLD.capability_expires_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.approval_principal_basis IS DISTINCT FROM OLD.approval_principal_basis
       OR NEW.configured_principal_id IS DISTINCT FROM OLD.configured_principal_id
       OR NEW.approval_origin IS DISTINCT FROM OLD.approval_origin
       OR NEW.execution_route IS DISTINCT FROM OLD.execution_route
       OR NEW.scope_schema IS DISTINCT FROM OLD.scope_schema
       OR NEW.scope_hash IS DISTINCT FROM OLD.scope_hash
       OR NEW.scope_capability_ttl_micros IS DISTINCT FROM OLD.scope_capability_ttl_micros THEN
        RAISE EXCEPTION 'action attempt authority identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER action_attempts_freeze_scope_v17
BEFORE UPDATE OR DELETE ON action_attempts
FOR EACH ROW
EXECUTE FUNCTION freeze_action_attempt_scope_v17();

CREATE FUNCTION freeze_action_attempt_transition_v17()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'action attempt transition history is append-only'
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER action_attempt_transitions_append_only_v17
BEFORE UPDATE OR DELETE ON action_attempt_transitions
FOR EACH ROW
EXECUTE FUNCTION freeze_action_attempt_transition_v17();
