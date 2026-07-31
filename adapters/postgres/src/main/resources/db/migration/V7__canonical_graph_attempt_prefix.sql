-- Pack009 owns a PostgreSQL-canonical one-shot graph prefix. Historical
-- V1-V6 rows remain NULL in every new selector column; no profile or graph
-- lineage is guessed or backfilled.

CREATE TABLE agent_graph_attempts (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    execution_slot_id VARCHAR(200) NOT NULL,
    manifest_schema_version VARCHAR(16) NOT NULL,
    graph_protocol_version VARCHAR(200) NOT NULL,
    integrity_profile VARCHAR(200) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    pack_raw_sha256 CHAR(64) NOT NULL,
    environment_raw_sha256 CHAR(64) NOT NULL,
    capture_id VARCHAR(200) NOT NULL,
    capture_request_hash CHAR(64) NOT NULL,
    artifact_id VARCHAR(200) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    pricing_profile_fingerprint CHAR(64) NOT NULL,
    prompt_surface_fingerprint CHAR(64) NOT NULL,
    conductor_surface_fingerprint CHAR(64) NOT NULL,
    reservation_usd NUMERIC(20, 10) NOT NULL,
    maximum_provider_requests SMALLINT NOT NULL,
    parent_actor VARCHAR(200) NOT NULL,
    child_actor VARCHAR(200) NOT NULL,
    experiment_arm VARCHAR(200) NOT NULL,
    experiment_repetition INTEGER NOT NULL,
    manifest_json JSONB NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    initial_head_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agent_graph_attempts_pk
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT agent_graph_attempts_slot_uq
        UNIQUE (principal_id, execution_slot_id),
    CONSTRAINT agent_graph_attempts_manifest_uq
        UNIQUE (principal_id, attempt_id, manifest_hash),
    CONSTRAINT agent_graph_attempts_identity_valid CHECK (
        char_length(btrim(principal_id)) BETWEEN 1 AND 200
        AND execution_slot_id
            ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$'
        AND case_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$'
        AND capture_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$'
        AND artifact_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$'
    ),
    CONSTRAINT agent_graph_attempts_hashes_valid CHECK (
        attempt_id ~ '^[0-9a-f]{64}$'
        AND manifest_hash ~ '^[0-9a-f]{64}$'
        AND attempt_id = manifest_hash
        AND pack_raw_sha256 ~ '^[0-9a-f]{64}$'
        AND environment_raw_sha256 ~ '^[0-9a-f]{64}$'
        AND capture_request_hash ~ '^[0-9a-f]{64}$'
        AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND prompt_surface_fingerprint ~ '^[0-9a-f]{64}$'
        AND conductor_surface_fingerprint ~ '^[0-9a-f]{64}$'
        AND initial_head_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT agent_graph_attempts_protocol_valid CHECK (
        manifest_schema_version = '1.0'
        AND integrity_profile
            = 'emergeos-length-prefixed-sha256-v1'
        AND char_length(btrim(graph_protocol_version))
            BETWEEN 1 AND 200
    ),
    CONSTRAINT agent_graph_attempts_limits_valid CHECK (
        reservation_usd BETWEEN 0 AND 999999.999999
        AND reservation_usd = round(reservation_usd, 6)
        AND maximum_provider_requests BETWEEN 1 AND 128
        AND experiment_repetition BETWEEN 1 AND 1000000
    ),
    CONSTRAINT agent_graph_attempts_manifest_json_valid CHECK (
        (
            jsonb_typeof(manifest_json) = 'object'
            AND octet_length(manifest_json::text) <= 524288
            AND manifest_json ->> 'schemaVersion'
                = manifest_schema_version
            AND manifest_json ->> 'graphProtocolVersion'
                = graph_protocol_version
            AND manifest_json ->> 'principalId' = principal_id
            AND manifest_json ->> 'executionSlotId'
                = execution_slot_id
            AND manifest_json ->> 'caseId' = case_id
            AND manifest_json ->> 'packRawSha256'
                = pack_raw_sha256
            AND manifest_json ->> 'environmentRawSha256'
                = environment_raw_sha256
            AND manifest_json ->> 'captureId' = capture_id
            AND manifest_json ->> 'captureRequestHash'
                = capture_request_hash
            AND manifest_json ->> 'artifactId' = artifact_id
            AND (manifest_json ->> 'startedAt')::timestamptz
                = started_at
            AND manifest_json ->> 'pricingProfileFingerprint'
                = pricing_profile_fingerprint
            AND manifest_json ->> 'promptSurfaceFingerprint'
                = prompt_surface_fingerprint
            AND manifest_json ->> 'conductorSurfaceFingerprint'
                = conductor_surface_fingerprint
            AND (manifest_json ->> 'reservationUsd')::numeric
                = reservation_usd
            AND (manifest_json ->> 'maximumProviderRequests')::integer
                = maximum_provider_requests
            AND manifest_json ->> 'parentActor' = parent_actor
            AND manifest_json ->> 'childActor' = child_actor
            AND manifest_json -> 'experiment' ->> 'arm'
                = experiment_arm
            AND (
                manifest_json
                    -> 'experiment'
                    ->> 'repetition'
            )::integer = experiment_repetition
            AND manifest_json ->> 'integrityProfile'
                = integrity_profile
            AND manifest_json ->> 'manifestHash' = manifest_hash
        ) IS TRUE
    )
);

CREATE TABLE agent_graph_attempt_run_bindings (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    task_hash CHAR(64) NOT NULL,
    selector_hash CHAR(64) NOT NULL,
    execution_profile_id VARCHAR(200) NOT NULL,
    execution_profile_fingerprint CHAR(64) NOT NULL,
    worker_registry_version VARCHAR(200) NOT NULL,
    worker_profile_id VARCHAR(200) NOT NULL,
    worker_profile_fingerprint CHAR(64) NOT NULL,
    reserved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agent_graph_attempt_run_bindings_pk
        PRIMARY KEY (principal_id, attempt_id, role),
    CONSTRAINT agent_graph_attempt_run_bindings_run_uq
        UNIQUE (principal_id, run_id),
    CONSTRAINT agent_graph_attempt_run_bindings_task_uq
        UNIQUE (principal_id, task_id),
    CONSTRAINT agent_graph_attempt_run_bindings_event_uq
        UNIQUE (
            principal_id,
            attempt_id,
            manifest_hash,
            role,
            run_id,
            task_id
        ),
    CONSTRAINT agent_graph_attempt_run_bindings_selector_uq
        UNIQUE (
            principal_id,
            attempt_id,
            manifest_hash,
            role,
            run_id,
            task_id,
            selector_hash
        ),
    CONSTRAINT agent_graph_attempt_run_bindings_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id,
            attempt_id,
            manifest_hash
        )
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_graph_attempt_run_bindings_role_valid
        CHECK (role IN ('PARENT', 'CHILD')),
    CONSTRAINT agent_graph_attempt_run_bindings_identity_valid CHECK (
        run_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
        AND task_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
        AND task_hash ~ '^[0-9a-f]{64}$'
        AND selector_hash ~ '^[0-9a-f]{64}$'
        AND execution_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND worker_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND char_length(btrim(execution_profile_id))
            BETWEEN 1 AND 200
        AND char_length(btrim(worker_registry_version))
            BETWEEN 1 AND 200
        AND char_length(btrim(worker_profile_id))
            BETWEEN 1 AND 200
    )
);

CREATE TABLE agent_graph_attempt_events (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    sequence INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    phase_from VARCHAR(40),
    phase_to VARCHAR(40) NOT NULL,
    role VARCHAR(16),
    run_id VARCHAR(128),
    task_id VARCHAR(128),
    actor VARCHAR(200),
    challenge_hash CHAR(64),
    request_ordinal SMALLINT,
    request_hash CHAR(64),
    model_requested VARCHAR(512),
    previous_head_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    current_head_hash CHAR(64) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL
        DEFAULT transaction_timestamp(),
    CONSTRAINT agent_graph_attempt_events_pk
        PRIMARY KEY (principal_id, attempt_id, sequence),
    CONSTRAINT agent_graph_attempt_events_hash_uq
        UNIQUE (principal_id, attempt_id, event_hash),
    CONSTRAINT agent_graph_attempt_events_head_uq
        UNIQUE (
            principal_id,
            attempt_id,
            sequence,
            current_head_hash
        ),
    CONSTRAINT agent_graph_attempt_events_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id,
            attempt_id,
            manifest_hash
        )
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_graph_attempt_events_binding_fk
        FOREIGN KEY (
            principal_id,
            attempt_id,
            manifest_hash,
            role,
            run_id,
            task_id
        )
        REFERENCES agent_graph_attempt_run_bindings (
            principal_id,
            attempt_id,
            manifest_hash,
            role,
            run_id,
            task_id
        )
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_graph_attempt_events_hashes_valid CHECK (
        previous_head_hash ~ '^[0-9a-f]{64}$'
        AND event_hash ~ '^[0-9a-f]{64}$'
        AND current_head_hash ~ '^[0-9a-f]{64}$'
        AND (
            challenge_hash IS NULL
            OR challenge_hash ~ '^[0-9a-f]{64}$'
        )
        AND (
            request_hash IS NULL
            OR request_hash ~ '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT agent_graph_attempt_events_fixed_prefix CHECK (
        ((
            sequence = 1
            AND event_type = 'ATTEMPT_CLAIMED'
            AND phase_from IS NULL
            AND phase_to = 'MARKED'
            AND role IS NULL
        )
        OR (
            sequence = 2
            AND event_type = 'OPERATOR_APPROVED'
            AND phase_from = 'MARKED'
            AND phase_to = 'OPERATOR_APPROVED'
            AND role IS NULL
        )
        OR (
            sequence = 3
            AND event_type = 'PARENT_AUTHORIZED'
            AND phase_from = 'OPERATOR_APPROVED'
            AND phase_to = 'PARENT_AUTHORIZED'
            AND role = 'PARENT'
        )
        OR (
            sequence = 4
            AND event_type = 'PARENT_STARTED'
            AND phase_from = 'PARENT_AUTHORIZED'
            AND phase_to = 'PARENT_RUNNING'
            AND role = 'PARENT'
        )
        OR (
            sequence = 5
            AND event_type = 'CHILD_AUTHORIZED'
            AND phase_from = 'PARENT_RUNNING'
            AND phase_to = 'CHILD_AUTHORIZED'
            AND role = 'CHILD'
        )
        OR (
            sequence = 6
            AND event_type = 'CHILD_STARTED'
            AND phase_from = 'CHILD_AUTHORIZED'
            AND phase_to = 'CHILD_RUNNING'
            AND role = 'CHILD'
        )
        OR (
            sequence = 7
            AND event_type = 'CHILD_EGRESS_CONSUMED'
            AND phase_from = 'CHILD_RUNNING'
            AND phase_to = 'EGRESS_CONSUMED'
            AND role = 'CHILD'
        )
        OR (
            sequence = 8
            AND event_type = 'CREDENTIAL_READ_STARTED'
            AND phase_from = 'EGRESS_CONSUMED'
            AND phase_to = 'CREDENTIAL_READING'
            AND role = 'CHILD'
        )
        OR (
            sequence = 9
            AND event_type = 'CLIENT_CREATED'
            AND phase_from = 'CREDENTIAL_READING'
            AND phase_to = 'CLIENT_READY'
            AND role = 'CHILD'
        )
        OR (
            sequence = 10
            AND event_type = 'MODEL_CREATED'
            AND phase_from = 'CLIENT_READY'
            AND phase_to = 'MODEL_READY'
            AND role = 'CHILD'
        )
        OR (
            sequence = 11
            AND event_type = 'PROVIDER_INTENT'
            AND phase_from = 'MODEL_READY'
            AND phase_to = 'PROVIDER_PENDING'
            AND role = 'CHILD'
        )
        ) IS TRUE
    ),
    CONSTRAINT agent_graph_attempt_events_payload_shape CHECK (
        (
            sequence = 2
            AND actor IS NOT NULL
            AND actor = 'OWNER_TTY'
            AND char_length(btrim(actor)) BETWEEN 1 AND 200
            AND challenge_hash IS NOT NULL
        )
        OR (
            sequence <> 2
            AND actor IS NULL
            AND challenge_hash IS NULL
        )
    ),
    CONSTRAINT agent_graph_attempt_events_run_shape CHECK (
        (
            sequence IN (3, 4, 5, 6, 7, 8, 9, 10, 11)
            AND run_id IS NOT NULL
            AND task_id IS NOT NULL
        )
        OR (
            sequence IN (1, 2)
            AND run_id IS NULL
            AND task_id IS NULL
        )
    ),
    CONSTRAINT agent_graph_attempt_events_request_shape CHECK (
        (
            sequence = 11
            AND request_ordinal = 1
            AND request_hash IS NOT NULL
            AND model_requested IS NOT NULL
            AND char_length(btrim(model_requested))
                BETWEEN 1 AND 512
            AND model_requested
                ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        )
        OR (
            sequence <> 11
            AND request_ordinal IS NULL
            AND request_hash IS NULL
            AND model_requested IS NULL
        )
    )
);

CREATE UNIQUE INDEX agent_graph_attempt_events_provider_ordinal_uq
    ON agent_graph_attempt_events (
        principal_id,
        attempt_id,
        request_ordinal
    )
    WHERE event_type = 'PROVIDER_INTENT';

CREATE TABLE agent_graph_attempt_heads (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    phase VARCHAR(40) NOT NULL,
    state_version BIGINT NOT NULL,
    last_sequence INTEGER NOT NULL,
    head_hash CHAR(64) NOT NULL,
    billing_status VARCHAR(16) NOT NULL,
    provider_intent_count SMALLINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agent_graph_attempt_heads_pk
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT agent_graph_attempt_heads_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id,
            attempt_id,
            manifest_hash
        )
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_graph_attempt_heads_event_fk
        FOREIGN KEY (
            principal_id,
            attempt_id,
            last_sequence,
            head_hash
        )
        REFERENCES agent_graph_attempt_events (
            principal_id,
            attempt_id,
            sequence,
            current_head_hash
        )
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT agent_graph_attempt_heads_shape CHECK (
        state_version = last_sequence
        AND last_sequence BETWEEN 1 AND 11
        AND head_hash ~ '^[0-9a-f]{64}$'
        AND (
            (
                last_sequence < 11
                AND billing_status = 'NOT_INVOKED'
                AND provider_intent_count = 0
            )
            OR (
                last_sequence = 11
                AND billing_status = 'UNKNOWN'
                AND provider_intent_count = 1
            )
        )
    ),
    CONSTRAINT agent_graph_attempt_heads_phase_valid CHECK (
        phase IN (
            'MARKED',
            'OPERATOR_APPROVED',
            'PARENT_AUTHORIZED',
            'PARENT_RUNNING',
            'CHILD_AUTHORIZED',
            'CHILD_RUNNING',
            'EGRESS_CONSUMED',
            'CREDENTIAL_READING',
            'CLIENT_READY',
            'MODEL_READY',
            'PROVIDER_PENDING'
        )
        AND phase = CASE last_sequence
            WHEN 1 THEN 'MARKED'
            WHEN 2 THEN 'OPERATOR_APPROVED'
            WHEN 3 THEN 'PARENT_AUTHORIZED'
            WHEN 4 THEN 'PARENT_RUNNING'
            WHEN 5 THEN 'CHILD_AUTHORIZED'
            WHEN 6 THEN 'CHILD_RUNNING'
            WHEN 7 THEN 'EGRESS_CONSUMED'
            WHEN 8 THEN 'CREDENTIAL_READING'
            WHEN 9 THEN 'CLIENT_READY'
            WHEN 10 THEN 'MODEL_READY'
            WHEN 11 THEN 'PROVIDER_PENDING'
        END
    )
);

-- Deliberately present but impossible to populate in V7. A future terminal
-- migration must replace this skeleton with the full atomic seal contract.
CREATE TABLE agent_graph_attempt_seals (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    final_sequence INTEGER NOT NULL,
    final_head_hash CHAR(64) NOT NULL,
    graph_outcome VARCHAR(32) NOT NULL,
    billing_status VARCHAR(16) NOT NULL,
    seal_hash CHAR(64) NOT NULL,
    sealed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT agent_graph_attempt_seals_pk
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT agent_graph_attempt_seals_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id,
            attempt_id,
            manifest_hash
        ),
    CONSTRAINT agent_graph_attempt_seals_disabled_v7 CHECK (FALSE)
);

ALTER TABLE agent_runs
    ADD COLUMN graph_attempt_id CHAR(64),
    ADD COLUMN graph_manifest_hash CHAR(64),
    ADD COLUMN graph_role VARCHAR(16),
    ADD COLUMN graph_task_hash CHAR(64),
    ADD COLUMN graph_selector_hash CHAR(64),
    ADD COLUMN graph_execution_profile_id VARCHAR(200),
    ADD COLUMN graph_execution_profile_fingerprint CHAR(64),
    ADD COLUMN graph_worker_registry_version VARCHAR(200),
    ADD COLUMN graph_worker_profile_id VARCHAR(200),
    ADD COLUMN graph_worker_profile_fingerprint CHAR(64);

ALTER TABLE agent_runs
    ADD CONSTRAINT agent_runs_graph_selector_shape CHECK (
        ((
            graph_attempt_id IS NULL
            AND graph_manifest_hash IS NULL
            AND graph_role IS NULL
            AND graph_task_hash IS NULL
            AND graph_selector_hash IS NULL
            AND graph_execution_profile_id IS NULL
            AND graph_execution_profile_fingerprint IS NULL
            AND graph_worker_registry_version IS NULL
            AND graph_worker_profile_id IS NULL
            AND graph_worker_profile_fingerprint IS NULL
        )
        OR (
            graph_attempt_id IS NOT NULL
            AND graph_attempt_id ~ '^[0-9a-f]{64}$'
            AND graph_manifest_hash IS NOT NULL
            AND graph_manifest_hash ~ '^[0-9a-f]{64}$'
            AND graph_role IN ('PARENT', 'CHILD')
            AND graph_task_hash IS NOT NULL
            AND graph_task_hash ~ '^[0-9a-f]{64}$'
            AND graph_selector_hash IS NOT NULL
            AND graph_selector_hash ~ '^[0-9a-f]{64}$'
            AND graph_execution_profile_id IS NOT NULL
            AND graph_execution_profile_fingerprint IS NOT NULL
            AND graph_execution_profile_fingerprint
                ~ '^[0-9a-f]{64}$'
            AND graph_worker_registry_version IS NOT NULL
            AND graph_worker_profile_id IS NOT NULL
            AND graph_worker_profile_fingerprint IS NOT NULL
            AND graph_worker_profile_fingerprint
                ~ '^[0-9a-f]{64}$'
            AND lifecycle_status = 'RUNNING'
        )
        ) IS TRUE
    ),
    ADD CONSTRAINT agent_runs_graph_selector_fk
        FOREIGN KEY (
            principal_id,
            graph_attempt_id,
            graph_manifest_hash,
            graph_role,
            run_id,
            task_id,
            graph_selector_hash
        )
        REFERENCES agent_graph_attempt_run_bindings (
            principal_id,
            attempt_id,
            manifest_hash,
            role,
            run_id,
            task_id,
            selector_hash
        )
        DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION agent_graph_reject_immutable_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        '% is immutable after graph commit', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER agent_graph_attempts_immutable_v7
BEFORE UPDATE OR DELETE ON agent_graph_attempts
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER agent_graph_bindings_immutable_v7
BEFORE UPDATE OR DELETE ON agent_graph_attempt_run_bindings
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER agent_graph_events_immutable_v7
BEFORE UPDATE OR DELETE ON agent_graph_attempt_events
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER agent_graph_seals_immutable_v7
BEFORE UPDATE OR DELETE ON agent_graph_attempt_seals
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE FUNCTION agent_graph_binding_reservation_guard_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            NEW.principal_id || E'\x1f' || NEW.run_id,
            0
        )
    );
    IF EXISTS (
        SELECT 1
        FROM agent_runs
        WHERE principal_id = NEW.principal_id
          AND run_id = NEW.run_id
    ) THEN
        RAISE EXCEPTION
            'graph run id was occupied before reservation'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_graph_binding_reservation_guard_v7
BEFORE INSERT ON agent_graph_attempt_run_bindings
FOR EACH ROW
EXECUTE FUNCTION agent_graph_binding_reservation_guard_v7();

CREATE FUNCTION agent_graph_run_selector_guard_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.graph_attempt_id IS NOT NULL THEN
            RAISE EXCEPTION
                'graph-bound Run is immutable in V7'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.graph_attempt_id IS NOT NULL THEN
        RAISE EXCEPTION
            'graph-bound Run is immutable in V7'
            USING ERRCODE = '55000';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            NEW.principal_id || E'\x1f' || NEW.run_id,
            0
        )
    );

    IF NEW.graph_attempt_id IS NULL THEN
        IF EXISTS (
            SELECT 1
            FROM agent_graph_attempt_run_bindings
            WHERE principal_id = NEW.principal_id
              AND run_id = NEW.run_id
        ) THEN
            RAISE EXCEPTION
                'reserved graph Run requires its exact selector'
                USING ERRCODE = '23503';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'graph-bound Run is immutable in V7'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.lifecycle_status <> 'RUNNING'
       OR NOT EXISTS (
            SELECT 1
            FROM agent_graph_attempt_run_bindings binding
            WHERE binding.principal_id = NEW.principal_id
              AND binding.attempt_id = NEW.graph_attempt_id
              AND binding.manifest_hash = NEW.graph_manifest_hash
              AND binding.role = NEW.graph_role
              AND binding.run_id = NEW.run_id
              AND binding.task_id = NEW.task_id
              AND binding.task_hash = NEW.graph_task_hash
              AND binding.selector_hash
                    = NEW.graph_selector_hash
              AND binding.execution_profile_id
                    = NEW.graph_execution_profile_id
              AND binding.execution_profile_fingerprint
                    = NEW.graph_execution_profile_fingerprint
              AND binding.worker_registry_version
                    = NEW.graph_worker_registry_version
              AND binding.worker_profile_id
                    = NEW.graph_worker_profile_id
              AND binding.worker_profile_fingerprint
                    = NEW.graph_worker_profile_fingerprint
       ) THEN
        RAISE EXCEPTION
            'graph Run selector does not match its reservation'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_graph_run_selector_guard_v7
BEFORE INSERT OR UPDATE OR DELETE ON agent_runs
FOR EACH ROW EXECUTE FUNCTION agent_graph_run_selector_guard_v7();

CREATE FUNCTION agent_graph_head_transition_guard_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_phase VARCHAR(40);
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'MARKED'
           OR NEW.state_version <> 1
           OR NEW.last_sequence <> 1
           OR NEW.billing_status <> 'NOT_INVOKED'
           OR NEW.provider_intent_count <> 0 THEN
            RAISE EXCEPTION
                'graph head must begin at the frozen claim state'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'graph head cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.principal_id <> OLD.principal_id
       OR NEW.attempt_id <> OLD.attempt_id
       OR NEW.manifest_hash <> OLD.manifest_hash
       OR NEW.created_at <> OLD.created_at
       OR NEW.state_version <> OLD.state_version + 1
       OR NEW.last_sequence <> OLD.last_sequence + 1
       OR NEW.updated_at < OLD.updated_at
       OR NEW.last_sequence > 11 THEN
        RAISE EXCEPTION
            'invalid graph head CAS transition'
            USING ERRCODE = '40001';
    END IF;
    expected_phase := CASE NEW.last_sequence
        WHEN 2 THEN 'OPERATOR_APPROVED'
        WHEN 3 THEN 'PARENT_AUTHORIZED'
        WHEN 4 THEN 'PARENT_RUNNING'
        WHEN 5 THEN 'CHILD_AUTHORIZED'
        WHEN 6 THEN 'CHILD_RUNNING'
        WHEN 7 THEN 'EGRESS_CONSUMED'
        WHEN 8 THEN 'CREDENTIAL_READING'
        WHEN 9 THEN 'CLIENT_READY'
        WHEN 10 THEN 'MODEL_READY'
        WHEN 11 THEN 'PROVIDER_PENDING'
        ELSE NULL
    END;
    IF expected_phase IS NULL OR NEW.phase <> expected_phase THEN
        RAISE EXCEPTION
            'graph head skipped its frozen next phase'
            USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_graph_head_transition_guard_v7
BEFORE INSERT OR UPDATE OR DELETE ON agent_graph_attempt_heads
FOR EACH ROW EXECUTE FUNCTION agent_graph_head_transition_guard_v7();

CREATE FUNCTION agent_assert_graph_attempt_v7(
    checked_principal VARCHAR,
    checked_attempt CHAR(64)
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    binding_count INTEGER;
    event_count INTEGER;
    parent_run_count INTEGER;
    child_run_count INTEGER;
    seal_count INTEGER;
    expected_parent_run_count INTEGER;
    expected_child_run_count INTEGER;
    head_record agent_graph_attempt_heads%ROWTYPE;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM agent_graph_attempts
        WHERE principal_id = checked_principal
          AND attempt_id = checked_attempt
    ) THEN
        RETURN;
    END IF;

    SELECT count(*)
    INTO binding_count
    FROM agent_graph_attempt_run_bindings
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    IF binding_count <> 2
       OR NOT EXISTS (
            SELECT 1
            FROM agent_graph_attempt_run_bindings
            WHERE principal_id = checked_principal
              AND attempt_id = checked_attempt
              AND role = 'PARENT'
       )
       OR NOT EXISTS (
            SELECT 1
            FROM agent_graph_attempt_run_bindings
            WHERE principal_id = checked_principal
              AND attempt_id = checked_attempt
              AND role = 'CHILD'
       )
       OR EXISTS (
            SELECT 1
            FROM agent_graph_attempt_run_bindings binding
            JOIN agent_graph_attempts attempt
              ON attempt.principal_id = binding.principal_id
             AND attempt.attempt_id = binding.attempt_id
            WHERE binding.principal_id = checked_principal
              AND binding.attempt_id = checked_attempt
              AND (
                (
                  binding.role = 'PARENT'
                  AND (
                    attempt.manifest_json
                      -> 'parentSelection' ->> 'role'
                        IS DISTINCT FROM binding.role
                    OR attempt.manifest_json
                      -> 'parentSelection' ->> 'runId'
                        IS DISTINCT FROM binding.run_id
                    OR attempt.manifest_json
                      -> 'parentSelection' ->> 'taskId'
                        IS DISTINCT FROM binding.task_id
                    OR attempt.manifest_json
                      -> 'parentSelection' ->> 'taskHash'
                        IS DISTINCT FROM binding.task_hash
                    OR attempt.manifest_json
                      -> 'parentSelection'
                      ->> 'executionProfileId'
                        IS DISTINCT FROM binding.execution_profile_id
                    OR attempt.manifest_json
                      -> 'parentSelection'
                      ->> 'executionProfileFingerprint'
                        IS DISTINCT FROM
                          binding.execution_profile_fingerprint
                    OR attempt.manifest_json
                      -> 'parentSelection'
                      ->> 'workerRegistryVersion'
                        IS DISTINCT FROM binding.worker_registry_version
                    OR attempt.manifest_json
                      -> 'parentSelection' ->> 'workerProfileId'
                        IS DISTINCT FROM binding.worker_profile_id
                    OR attempt.manifest_json
                      -> 'parentSelection'
                      ->> 'workerProfileFingerprint'
                        IS DISTINCT FROM
                          binding.worker_profile_fingerprint
                  )
                )
                OR (
                  binding.role = 'CHILD'
                  AND (
                    attempt.manifest_json
                      -> 'childSelection' ->> 'role'
                        IS DISTINCT FROM binding.role
                    OR attempt.manifest_json
                      -> 'childSelection' ->> 'runId'
                        IS DISTINCT FROM binding.run_id
                    OR attempt.manifest_json
                      -> 'childSelection' ->> 'taskId'
                        IS DISTINCT FROM binding.task_id
                    OR attempt.manifest_json
                      -> 'childSelection' ->> 'taskHash'
                        IS DISTINCT FROM binding.task_hash
                    OR attempt.manifest_json
                      -> 'childSelection'
                      ->> 'executionProfileId'
                        IS DISTINCT FROM binding.execution_profile_id
                    OR attempt.manifest_json
                      -> 'childSelection'
                      ->> 'executionProfileFingerprint'
                        IS DISTINCT FROM
                          binding.execution_profile_fingerprint
                    OR attempt.manifest_json
                      -> 'childSelection'
                      ->> 'workerRegistryVersion'
                        IS DISTINCT FROM binding.worker_registry_version
                    OR attempt.manifest_json
                      -> 'childSelection' ->> 'workerProfileId'
                        IS DISTINCT FROM binding.worker_profile_id
                    OR attempt.manifest_json
                      -> 'childSelection'
                      ->> 'workerProfileFingerprint'
                        IS DISTINCT FROM
                          binding.worker_profile_fingerprint
                  )
                )
              )
       ) THEN
        RAISE EXCEPTION
            'graph attempt requires exact parent/child reservations'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM agent_graph_attempt_heads
        WHERE principal_id = checked_principal
          AND attempt_id = checked_attempt
    ) THEN
        RAISE EXCEPTION
            'graph attempt requires one exact head'
            USING ERRCODE = '23514';
    END IF;

    SELECT *
    INTO head_record
    FROM agent_graph_attempt_heads
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;

    SELECT count(*)
    INTO event_count
    FROM agent_graph_attempt_events
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    IF event_count <> head_record.last_sequence
       OR EXISTS (
            SELECT 1
            FROM (
                SELECT
                    sequence,
                    row_number() OVER (
                        ORDER BY sequence
                    ) AS expected_sequence,
                    occurred_at,
                    previous_head_hash,
                    current_head_hash,
                    lag(current_head_hash)
                        OVER (ORDER BY sequence) AS prior_head,
                    lag(occurred_at)
                        OVER (ORDER BY sequence) AS prior_time
                FROM agent_graph_attempt_events
                WHERE principal_id = checked_principal
                  AND attempt_id = checked_attempt
            ) chain
            JOIN agent_graph_attempts attempt
              ON attempt.principal_id = checked_principal
             AND attempt.attempt_id = checked_attempt
            WHERE chain.sequence <> chain.expected_sequence
            OR (
                chain.sequence = 1
                AND chain.previous_head_hash
                    <> attempt.initial_head_hash
            ) OR (
                chain.sequence > 1
                AND chain.previous_head_hash <> chain.prior_head
            ) OR (
                chain.prior_time IS NOT NULL
                AND chain.occurred_at < chain.prior_time
            )
       ) THEN
        RAISE EXCEPTION
            'graph event prefix/head relation is invalid'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM agent_graph_attempt_events intent
        JOIN agent_graph_attempts attempt
          ON attempt.principal_id = intent.principal_id
         AND attempt.attempt_id = intent.attempt_id
        JOIN agent_graph_attempt_run_bindings child_binding
          ON child_binding.principal_id = intent.principal_id
         AND child_binding.attempt_id = intent.attempt_id
         AND child_binding.role = 'CHILD'
        LEFT JOIN agent_runs child_run
          ON child_run.principal_id = child_binding.principal_id
         AND child_run.run_id = child_binding.run_id
        WHERE intent.principal_id = checked_principal
          AND intent.attempt_id = checked_attempt
          AND intent.event_type = 'PROVIDER_INTENT'
          AND (
            intent.request_ordinal
                > attempt.maximum_provider_requests
            OR intent.model_requested
                IS DISTINCT FROM child_run.model_requested
          )
    ) THEN
        RAISE EXCEPTION
            'provider intent does not match the frozen child Run'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*)
    INTO parent_run_count
    FROM agent_runs run
    JOIN agent_graph_attempt_run_bindings binding
      ON binding.principal_id = run.principal_id
     AND binding.attempt_id = run.graph_attempt_id
     AND binding.role = run.graph_role
     AND binding.run_id = run.run_id
    WHERE binding.principal_id = checked_principal
      AND binding.attempt_id = checked_attempt
      AND binding.role = 'PARENT';

    SELECT count(*)
    INTO child_run_count
    FROM agent_runs run
    JOIN agent_graph_attempt_run_bindings binding
      ON binding.principal_id = run.principal_id
     AND binding.attempt_id = run.graph_attempt_id
     AND binding.role = run.graph_role
     AND binding.run_id = run.run_id
    WHERE binding.principal_id = checked_principal
      AND binding.attempt_id = checked_attempt
      AND binding.role = 'CHILD';

    SELECT count(*)
    INTO seal_count
    FROM agent_graph_attempt_seals
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;

    expected_parent_run_count := 0;
    IF head_record.last_sequence >= 4 THEN
        expected_parent_run_count := 1;
    END IF;
    expected_child_run_count := 0;
    IF head_record.last_sequence >= 6 THEN
        expected_child_run_count := 1;
    END IF;

    IF parent_run_count <> expected_parent_run_count
       OR child_run_count <> expected_child_run_count
       OR EXISTS (
            SELECT 1
            FROM agent_runs parent_run
            JOIN agent_graph_attempt_run_bindings parent_binding
              ON parent_binding.principal_id
                    = parent_run.principal_id
             AND parent_binding.attempt_id
                    = parent_run.graph_attempt_id
             AND parent_binding.role = 'PARENT'
             AND parent_binding.run_id = parent_run.run_id
            JOIN agent_graph_attempts attempt
              ON attempt.principal_id = parent_binding.principal_id
             AND attempt.attempt_id = parent_binding.attempt_id
            WHERE parent_binding.principal_id
                    = checked_principal
              AND parent_binding.attempt_id = checked_attempt
              AND (
                parent_run.parent_run_id IS NOT NULL
                OR parent_run.parent_task_id IS NOT NULL
                OR parent_run.run_depth <> 0
                OR parent_run.parent_run_depth IS NOT NULL
                OR parent_run.started_at
                    IS DISTINCT FROM attempt.started_at
              )
       )
       OR EXISTS (
            SELECT 1
            FROM agent_runs child_run
            JOIN agent_graph_attempt_run_bindings child_binding
              ON child_binding.principal_id
                    = child_run.principal_id
             AND child_binding.attempt_id
                    = child_run.graph_attempt_id
             AND child_binding.role = 'CHILD'
             AND child_binding.run_id = child_run.run_id
            JOIN agent_graph_attempt_run_bindings parent_binding
              ON parent_binding.principal_id
                    = child_binding.principal_id
             AND parent_binding.attempt_id
                    = child_binding.attempt_id
             AND parent_binding.role = 'PARENT'
            JOIN agent_graph_attempts attempt
              ON attempt.principal_id = child_binding.principal_id
             AND attempt.attempt_id = child_binding.attempt_id
            WHERE child_binding.principal_id
                    = checked_principal
              AND child_binding.attempt_id = checked_attempt
              AND (
                child_run.parent_run_id
                    IS DISTINCT FROM parent_binding.run_id
                OR child_run.parent_task_id
                    IS DISTINCT FROM parent_binding.task_id
                OR child_run.run_depth <> 1
                OR child_run.parent_run_depth IS DISTINCT FROM 0
                OR child_run.started_at
                    IS DISTINCT FROM attempt.started_at
              )
       )
       OR EXISTS (
            SELECT 1
            FROM agent_runs run
            JOIN agent_graph_attempt_run_bindings binding
              ON binding.principal_id = run.principal_id
             AND binding.attempt_id = run.graph_attempt_id
             AND binding.role = run.graph_role
             AND binding.run_id = run.run_id
            WHERE binding.principal_id = checked_principal
              AND binding.attempt_id = checked_attempt
              AND (
                run.last_event_sequence <> 0
                OR run.resolved_model IS NOT NULL
                OR run.agent_version IS NOT NULL
                OR run.verifier_version IS NOT NULL
                OR run.harness_version IS NOT NULL
                OR run.cost_usd <> 0
                OR run.token_count <> 0
                OR run.latency_ms <> 0
                OR run.failure_attribution IS NOT NULL
              )
       )
       OR seal_count <> 0 THEN
        RAISE EXCEPTION
            'graph Run/effect/seal relation is invalid for the V7 prefix'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION agent_graph_assert_row_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM agent_assert_graph_attempt_v7(
            OLD.principal_id, OLD.attempt_id);
        RETURN OLD;
    END IF;
    PERFORM agent_assert_graph_attempt_v7(
        NEW.principal_id, NEW.attempt_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION agent_graph_assert_run_row_v7()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.graph_attempt_id IS NOT NULL THEN
            PERFORM agent_assert_graph_attempt_v7(
                OLD.principal_id, OLD.graph_attempt_id);
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.graph_attempt_id IS NOT NULL THEN
        PERFORM agent_assert_graph_attempt_v7(
            OLD.principal_id, OLD.graph_attempt_id);
    END IF;

    IF NEW.graph_attempt_id IS NOT NULL
       AND (
            TG_OP <> 'UPDATE'
            OR OLD.principal_id IS DISTINCT FROM NEW.principal_id
            OR OLD.graph_attempt_id IS DISTINCT FROM NEW.graph_attempt_id
       ) THEN
        PERFORM agent_assert_graph_attempt_v7(
            NEW.principal_id, NEW.graph_attempt_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_graph_attempts_assert_v7
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v7();

CREATE CONSTRAINT TRIGGER agent_graph_bindings_assert_v7
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempt_run_bindings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v7();

CREATE CONSTRAINT TRIGGER agent_graph_events_assert_v7
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempt_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v7();

CREATE CONSTRAINT TRIGGER agent_graph_heads_assert_v7
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempt_heads
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v7();

CREATE CONSTRAINT TRIGGER agent_graph_runs_assert_v7
AFTER INSERT OR UPDATE OR DELETE ON agent_runs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_run_row_v7();
