-- Pack010 extends the immutable Pack009 prefix into an attributed terminal
-- graph. This is a forward-only migration: V7 bytes and checksums stay
-- untouched, and existing prefix rows are never backfilled or updated.

ALTER TABLE agent_graph_attempts
    ADD CONSTRAINT graph_attempts_terminal_request_limit_v8 CHECK (
        graph_protocol_version <> 'postgres-graph-terminal-v1'
        OR maximum_provider_requests = 2
    );

ALTER TABLE agent_graph_attempt_events
    ADD COLUMN evidence_hash CHAR(64);

ALTER TABLE agent_graph_attempt_events
    DROP CONSTRAINT agent_graph_attempt_events_hashes_valid,
    DROP CONSTRAINT agent_graph_attempt_events_fixed_prefix,
    DROP CONSTRAINT agent_graph_attempt_events_run_shape,
    DROP CONSTRAINT agent_graph_attempt_events_request_shape;

ALTER TABLE agent_graph_attempt_events
    ADD CONSTRAINT graph_events_hashes_v8 CHECK (
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
        AND (
            evidence_hash IS NULL
            OR evidence_hash ~ '^[0-9a-f]{64}$'
        )
    ),
    ADD CONSTRAINT graph_events_prefix_v8 CHECK (
        sequence BETWEEN 1 AND 17
        AND event_type = CASE sequence
            WHEN 1 THEN 'ATTEMPT_CLAIMED'
            WHEN 2 THEN 'OPERATOR_APPROVED'
            WHEN 3 THEN 'PARENT_AUTHORIZED'
            WHEN 4 THEN 'PARENT_STARTED'
            WHEN 5 THEN 'CHILD_AUTHORIZED'
            WHEN 6 THEN 'CHILD_STARTED'
            WHEN 7 THEN 'CHILD_EGRESS_CONSUMED'
            WHEN 8 THEN 'CREDENTIAL_READ_STARTED'
            WHEN 9 THEN 'CLIENT_CREATED'
            WHEN 10 THEN 'MODEL_CREATED'
            WHEN 11 THEN 'PROVIDER_INTENT'
            WHEN 12 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 13 THEN 'PROVIDER_INTENT'
            WHEN 14 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 15 THEN 'CHILD_TERMINAL'
            WHEN 16 THEN 'PARENT_TERMINAL'
            WHEN 17 THEN 'TERMINAL_SEALED'
        END
        AND phase_from IS NOT DISTINCT FROM CASE sequence
            WHEN 1 THEN NULL
            WHEN 2 THEN 'MARKED'
            WHEN 3 THEN 'OPERATOR_APPROVED'
            WHEN 4 THEN 'PARENT_AUTHORIZED'
            WHEN 5 THEN 'PARENT_RUNNING'
            WHEN 6 THEN 'CHILD_AUTHORIZED'
            WHEN 7 THEN 'CHILD_RUNNING'
            WHEN 8 THEN 'EGRESS_CONSUMED'
            WHEN 9 THEN 'CREDENTIAL_READING'
            WHEN 10 THEN 'CLIENT_READY'
            WHEN 11 THEN 'MODEL_READY'
            WHEN 12 THEN 'PROVIDER_PENDING'
            WHEN 13 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 14 THEN 'PROVIDER_PENDING'
            WHEN 15 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 16 THEN 'CHILD_TERMINAL'
            WHEN 17 THEN 'PARENT_TERMINAL'
        END
        AND phase_to = CASE sequence
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
            WHEN 12 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 13 THEN 'PROVIDER_PENDING'
            WHEN 14 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 15 THEN 'CHILD_TERMINAL'
            WHEN 16 THEN 'PARENT_TERMINAL'
            WHEN 17 THEN 'TERMINAL'
        END
        AND role IS NOT DISTINCT FROM CASE
            WHEN sequence IN (3, 4, 16) THEN 'PARENT'
            WHEN sequence BETWEEN 5 AND 15 THEN 'CHILD'
            ELSE NULL
        END
    ),
    ADD CONSTRAINT graph_events_run_shape_v8 CHECK (
        (
            sequence IN (1, 2, 17)
            AND run_id IS NULL
            AND task_id IS NULL
        )
        OR (
            sequence BETWEEN 3 AND 16
            AND run_id IS NOT NULL
            AND task_id IS NOT NULL
        )
    ),
    ADD CONSTRAINT graph_events_request_shape_v8 CHECK (
        (
            sequence = 11
            AND request_ordinal = 1
            AND request_hash IS NOT NULL
            AND model_requested IS NOT NULL
            AND evidence_hash IS NULL
        )
        OR (
            sequence = 12
            AND request_ordinal = 1
            AND request_hash IS NULL
            AND model_requested IS NULL
            AND evidence_hash IS NOT NULL
        )
        OR (
            sequence = 13
            AND request_ordinal = 2
            AND request_hash IS NOT NULL
            AND model_requested IS NOT NULL
            AND evidence_hash IS NULL
        )
        OR (
            sequence = 14
            AND request_ordinal = 2
            AND request_hash IS NULL
            AND model_requested IS NULL
            AND evidence_hash IS NOT NULL
        )
        OR (
            sequence IN (15, 16, 17)
            AND request_ordinal IS NULL
            AND request_hash IS NULL
            AND model_requested IS NULL
            AND evidence_hash IS NOT NULL
        )
        OR (
            sequence BETWEEN 1 AND 10
            AND request_ordinal IS NULL
            AND request_hash IS NULL
            AND model_requested IS NULL
            AND evidence_hash IS NULL
        )
    ),
    ADD CONSTRAINT graph_events_evidence_uq_v8 UNIQUE (
        principal_id,
        attempt_id,
        sequence,
        evidence_hash
    );

ALTER TABLE agent_graph_attempt_heads
    ADD COLUMN provider_attribution_count SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE agent_graph_attempt_heads
    DROP CONSTRAINT agent_graph_attempt_heads_shape,
    DROP CONSTRAINT agent_graph_attempt_heads_phase_valid;

ALTER TABLE agent_graph_attempt_heads
    ADD CONSTRAINT graph_heads_shape_v8 CHECK (
        state_version = last_sequence
        AND last_sequence BETWEEN 1 AND 17
        AND head_hash ~ '^[0-9a-f]{64}$'
        AND billing_status = CASE
            WHEN last_sequence BETWEEN 1 AND 10
                THEN 'NOT_INVOKED'
            WHEN last_sequence IN (11, 13)
                THEN 'UNKNOWN'
            ELSE 'ATTRIBUTED'
        END
        AND provider_intent_count = CASE
            WHEN last_sequence BETWEEN 1 AND 10 THEN 0
            WHEN last_sequence BETWEEN 11 AND 12 THEN 1
            ELSE 2
        END
        AND provider_attribution_count = CASE
            WHEN last_sequence BETWEEN 1 AND 11 THEN 0
            WHEN last_sequence BETWEEN 12 AND 13 THEN 1
            ELSE 2
        END
    ),
    ADD CONSTRAINT graph_heads_phase_v8 CHECK (
        phase = CASE last_sequence
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
            WHEN 12 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 13 THEN 'PROVIDER_PENDING'
            WHEN 14 THEN 'PROVIDER_ATTRIBUTED'
            WHEN 15 THEN 'CHILD_TERMINAL'
            WHEN 16 THEN 'PARENT_TERMINAL'
            WHEN 17 THEN 'TERMINAL'
        END
    );

DROP TRIGGER agent_graph_head_transition_guard_v7
ON agent_graph_attempt_heads;

CREATE FUNCTION agent_graph_head_transition_guard_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_phase VARCHAR(40);
    expected_billing VARCHAR(32);
    expected_intent_count SMALLINT;
    expected_attribution_count SMALLINT;
    protocol_version VARCHAR(200);
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'MARKED'
           OR NEW.state_version <> 1
           OR NEW.last_sequence <> 1
           OR NEW.billing_status <> 'NOT_INVOKED'
           OR NEW.provider_intent_count <> 0
           OR NEW.provider_attribution_count <> 0 THEN
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
       OR NEW.last_sequence > 17 THEN
        RAISE EXCEPTION
            'invalid graph head CAS transition'
            USING ERRCODE = '40001';
    END IF;

    SELECT graph_protocol_version
    INTO protocol_version
    FROM agent_graph_attempts
    WHERE principal_id = NEW.principal_id
      AND attempt_id = NEW.attempt_id;
    IF NEW.last_sequence > 11
       AND protocol_version
            IS DISTINCT FROM 'postgres-graph-terminal-v1' THEN
        RAISE EXCEPTION
            'legacy graph protocol cannot enter terminal prefix'
            USING ERRCODE = '23514';
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
        WHEN 12 THEN 'PROVIDER_ATTRIBUTED'
        WHEN 13 THEN 'PROVIDER_PENDING'
        WHEN 14 THEN 'PROVIDER_ATTRIBUTED'
        WHEN 15 THEN 'CHILD_TERMINAL'
        WHEN 16 THEN 'PARENT_TERMINAL'
        WHEN 17 THEN 'TERMINAL'
        ELSE NULL
    END;
    expected_billing := CASE
        WHEN NEW.last_sequence BETWEEN 1 AND 10
            THEN 'NOT_INVOKED'
        WHEN NEW.last_sequence IN (11, 13)
            THEN 'UNKNOWN'
        ELSE 'ATTRIBUTED'
    END;
    expected_intent_count := CASE
        WHEN NEW.last_sequence BETWEEN 1 AND 10 THEN 0
        WHEN NEW.last_sequence BETWEEN 11 AND 12 THEN 1
        ELSE 2
    END;
    expected_attribution_count := CASE
        WHEN NEW.last_sequence BETWEEN 1 AND 11 THEN 0
        WHEN NEW.last_sequence BETWEEN 12 AND 13 THEN 1
        ELSE 2
    END;
    IF expected_phase IS NULL
       OR NEW.phase <> expected_phase
       OR NEW.billing_status <> expected_billing
       OR NEW.provider_intent_count <> expected_intent_count
       OR NEW.provider_attribution_count
            <> expected_attribution_count THEN
        RAISE EXCEPTION
            'graph head skipped its frozen next phase'
            USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_graph_head_transition_guard_v8
BEFORE INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_heads
FOR EACH ROW EXECUTE FUNCTION agent_graph_head_transition_guard_v8();

ALTER TABLE agent_runs
    DROP CONSTRAINT agent_runs_graph_selector_shape;

ALTER TABLE agent_runs
    ADD CONSTRAINT agent_runs_graph_selector_shape_v8 CHECK (
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
        ) OR (
            graph_attempt_id IS NOT NULL
            AND graph_attempt_id ~ '^[0-9a-f]{64}$'
            AND graph_manifest_hash
                ~ '^[0-9a-f]{64}$'
            AND graph_role IN ('PARENT', 'CHILD')
            AND graph_task_hash ~ '^[0-9a-f]{64}$'
            AND graph_selector_hash ~ '^[0-9a-f]{64}$'
            AND graph_execution_profile_id IS NOT NULL
            AND graph_execution_profile_fingerprint
                ~ '^[0-9a-f]{64}$'
            AND graph_worker_registry_version IS NOT NULL
            AND graph_worker_profile_id IS NOT NULL
            AND graph_worker_profile_fingerprint
                ~ '^[0-9a-f]{64}$'
        )) IS TRUE
    );

CREATE FUNCTION agent_graph_authorize_terminal_v8(
    checked_principal VARCHAR,
    checked_attempt CHAR(64),
    checked_role VARCHAR,
    checked_run VARCHAR
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    expected_sequence INTEGER;
    expected_phase VARCHAR(40);
    locked_sequence INTEGER;
    locked_phase VARCHAR(40);
    locked_status VARCHAR(32);
BEGIN
    expected_sequence := CASE checked_role
        WHEN 'CHILD' THEN 14
        WHEN 'PARENT' THEN 15
        ELSE NULL
    END;
    expected_phase := CASE checked_role
        WHEN 'CHILD' THEN 'PROVIDER_ATTRIBUTED'
        WHEN 'PARENT' THEN 'CHILD_TERMINAL'
        ELSE NULL
    END;
    IF expected_sequence IS NOT NULL THEN
        SELECT head.last_sequence,
               head.phase,
               run.lifecycle_status
        INTO locked_sequence, locked_phase, locked_status
        FROM agent_graph_attempt_heads head
        JOIN agent_runs run
          ON run.principal_id = head.principal_id
         AND run.graph_attempt_id = head.attempt_id
         AND run.graph_manifest_hash = head.manifest_hash
        WHERE head.principal_id = checked_principal
          AND head.attempt_id = checked_attempt
          AND run.graph_role = checked_role
          AND run.run_id = checked_run
        FOR UPDATE OF head, run;
    END IF;
    IF expected_sequence IS NULL
       OR locked_sequence IS DISTINCT FROM expected_sequence
       OR locked_phase IS DISTINCT FROM expected_phase
       OR locked_status IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION
            'graph terminal permit does not match canonical head'
            USING ERRCODE = '55000';
    END IF;
    PERFORM set_config(
        'emergeos.graph_terminal_permit',
        checked_principal || E'\x1f'
            || checked_attempt || E'\x1f'
            || checked_role || E'\x1f'
            || checked_run,
        true
    );
END;
$$;

DROP TRIGGER agent_graph_run_selector_guard_v7
ON agent_runs;

CREATE FUNCTION agent_graph_run_selector_guard_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_permit TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.graph_attempt_id IS NOT NULL THEN
            RAISE EXCEPTION
                'graph-bound Run is immutable after reservation'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.graph_attempt_id IS NOT NULL THEN
        expected_permit :=
            OLD.principal_id || E'\x1f'
            || OLD.graph_attempt_id || E'\x1f'
            || OLD.graph_role || E'\x1f'
            || OLD.run_id;
        IF current_setting(
                'emergeos.graph_terminal_permit', true
            ) IS DISTINCT FROM expected_permit
           OR OLD.lifecycle_status <> 'RUNNING'
           OR NEW.lifecycle_status NOT IN (
                'SUCCEEDED', 'FAILED', 'NEEDS_INPUT',
                'BLOCKED', 'CANCELLED'
           )
           OR NEW.principal_id <> OLD.principal_id
           OR NEW.run_id <> OLD.run_id
           OR NEW.task_id <> OLD.task_id
           OR NEW.parent_run_id
                IS DISTINCT FROM OLD.parent_run_id
           OR NEW.parent_task_id
                IS DISTINCT FROM OLD.parent_task_id
           OR NEW.run_depth <> OLD.run_depth
           OR NEW.parent_run_depth
                IS DISTINCT FROM OLD.parent_run_depth
           OR NEW.task_envelope <> OLD.task_envelope
           OR NEW.tool_registry_version
                <> OLD.tool_registry_version
           OR NEW.policy_version <> OLD.policy_version
           OR NEW.state_version <> OLD.state_version
           OR NEW.context_policy_version
                <> OLD.context_policy_version
           OR NEW.model_provider
                IS DISTINCT FROM OLD.model_provider
           OR NEW.model_requested
                IS DISTINCT FROM OLD.model_requested
           OR NEW.pricing_profile
                IS DISTINCT FROM OLD.pricing_profile
           OR NEW.started_at <> OLD.started_at
           OR NEW.graph_attempt_id <> OLD.graph_attempt_id
           OR NEW.graph_manifest_hash
                <> OLD.graph_manifest_hash
           OR NEW.graph_role <> OLD.graph_role
           OR NEW.graph_task_hash <> OLD.graph_task_hash
           OR NEW.graph_selector_hash
                <> OLD.graph_selector_hash
           OR NEW.graph_execution_profile_id
                <> OLD.graph_execution_profile_id
           OR NEW.graph_execution_profile_fingerprint
                <> OLD.graph_execution_profile_fingerprint
           OR NEW.graph_worker_registry_version
                <> OLD.graph_worker_registry_version
           OR NEW.graph_worker_profile_id
                <> OLD.graph_worker_profile_id
           OR NEW.graph_worker_profile_fingerprint
                <> OLD.graph_worker_profile_fingerprint THEN
            RAISE EXCEPTION
                'graph-bound Run terminal mutation lacks exact permit'
                USING ERRCODE = '55000';
        END IF;
        PERFORM set_config(
            'emergeos.graph_terminal_permit',
            '',
            true
        );
        RETURN NEW;
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
              AND binding.selector_hash = NEW.graph_selector_hash
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

CREATE TRIGGER agent_graph_run_selector_guard_v8
BEFORE INSERT OR UPDATE OR DELETE ON agent_runs
FOR EACH ROW EXECUTE FUNCTION agent_graph_run_selector_guard_v8();

CREATE TABLE agent_graph_attempt_provider_attributions (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    request_ordinal SMALLINT NOT NULL,
    event_sequence INTEGER NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    provider_actor VARCHAR(200) NOT NULL,
    model_requested VARCHAR(512) NOT NULL,
    model_resolved VARCHAR(512) NOT NULL,
    pricing_profile_id VARCHAR(200) NOT NULL,
    pricing_provider VARCHAR(128) NOT NULL,
    pricing_profile_fingerprint CHAR(64) NOT NULL,
    uncached_input_nano_usd_per_token BIGINT NOT NULL,
    cached_input_nano_usd_per_token BIGINT NOT NULL,
    output_nano_usd_per_token BIGINT NOT NULL,
    input_tokens BIGINT NOT NULL,
    cached_input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    reasoning_output_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    observed_cost_usd NUMERIC(20, 10) NOT NULL,
    attribution_hash CHAR(64) NOT NULL,
    attributed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT graph_attr_pk_v8 PRIMARY KEY (
        principal_id,
        attempt_id,
        request_ordinal
    ),
    CONSTRAINT graph_attr_hash_uq_v8 UNIQUE (
        principal_id,
        attempt_id,
        attribution_hash
    ),
    CONSTRAINT graph_attr_response_uq_v8 UNIQUE (
        principal_id,
        attempt_id,
        request_ordinal,
        response_hash
    ),
    CONSTRAINT graph_attr_attempt_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        manifest_hash
    ) REFERENCES agent_graph_attempts (
        principal_id,
        attempt_id,
        manifest_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_attr_event_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        event_sequence,
        attribution_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id,
        attempt_id,
        sequence,
        evidence_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_attr_ordinal_v8 CHECK (
        (request_ordinal = 1 AND event_sequence = 12)
        OR (request_ordinal = 2 AND event_sequence = 14)
    ),
    CONSTRAINT graph_attr_hashes_v8 CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
        AND response_hash ~ '^[0-9a-f]{64}$'
        AND pricing_profile_fingerprint ~ '^[0-9a-f]{64}$'
        AND attribution_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT graph_attr_names_v8 CHECK (
        char_length(btrim(provider_actor)) BETWEEN 1 AND 200
        AND model_requested
            ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        AND char_length(model_requested) <= 512
        AND model_resolved
            ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        AND char_length(model_resolved) <= 512
        AND pricing_profile_id
            ~ '^[a-z][a-z0-9._-]{0,199}$'
        AND pricing_provider
            ~ '^[a-z][a-z0-9._-]{0,127}$'
    ),
    CONSTRAINT graph_attr_usage_v8 CHECK (
        input_tokens BETWEEN 0 AND 9007199254740991
        AND cached_input_tokens BETWEEN 0 AND input_tokens
        AND output_tokens BETWEEN 0 AND 9007199254740991
        AND reasoning_output_tokens BETWEEN 0 AND output_tokens
        AND total_tokens = input_tokens + output_tokens
    ),
    CONSTRAINT graph_attr_rates_v8 CHECK (
        uncached_input_nano_usd_per_token > 0
        AND cached_input_nano_usd_per_token >= 0
        AND cached_input_nano_usd_per_token
            <= uncached_input_nano_usd_per_token
        AND output_nano_usd_per_token > 0
    ),
    CONSTRAINT graph_attr_cost_v8 CHECK (
        observed_cost_usd BETWEEN 0 AND 999999.999999
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
            6
        )
    )
);

CREATE FUNCTION agent_graph_utf16_length_v8(input TEXT)
RETURNS INTEGER
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT COALESCE(
        sum(CASE WHEN ascii(character) > 65535 THEN 2 ELSE 1 END),
        0
    )::integer
    FROM regexp_split_to_table(input, '') AS character;
$$;

CREATE TABLE agent_graph_attempt_candidates (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    candidate_ref VARCHAR(256) NOT NULL,
    execution_slot_id VARCHAR(200) NOT NULL,
    repetition SMALLINT NOT NULL,
    child_role VARCHAR(16) NOT NULL DEFAULT 'CHILD',
    child_run_id VARCHAR(128) NOT NULL,
    child_task_id VARCHAR(128) NOT NULL,
    source_request_ordinal SMALLINT NOT NULL,
    source_response_hash CHAR(64) NOT NULL,
    trace_root_hash CHAR(64) NOT NULL,
    output_schema VARCHAR(2048) NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    required_evidence_ref VARCHAR(256) NOT NULL,
    required_evidence_available BOOLEAN NOT NULL,
    integrity_profile VARCHAR(200) NOT NULL,
    integrity_hash CHAR(64) NOT NULL,
    candidate_envelope JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT graph_candidate_pk_v8 PRIMARY KEY (
        principal_id,
        attempt_id
    ),
    CONSTRAINT graph_candidate_identity_uq_v8 UNIQUE (
        principal_id,
        attempt_id,
        candidate_ref,
        integrity_hash
    ),
    CONSTRAINT graph_candidate_attempt_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        manifest_hash
    ) REFERENCES agent_graph_attempts (
        principal_id,
        attempt_id,
        manifest_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_candidate_child_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        manifest_hash,
        child_role,
        child_run_id,
        child_task_id
    ) REFERENCES agent_graph_attempt_run_bindings (
        principal_id,
        attempt_id,
        manifest_hash,
        role,
        run_id,
        task_id
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_candidate_source_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        source_request_ordinal,
        source_response_hash
    ) REFERENCES agent_graph_attempt_provider_attributions (
        principal_id,
        attempt_id,
        request_ordinal,
        response_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_candidate_shape_v8 CHECK (
        schema_version = '1.0'
        AND child_role = 'CHILD'
        AND source_request_ordinal = 2
        AND repetition BETWEEN 1 AND 3
        AND candidate_ref = 'harness-candidate://' || child_run_id
        AND integrity_profile
            = 'emergeos-length-prefixed-sha256-v1'
        AND source_response_hash ~ '^[0-9a-f]{64}$'
        AND trace_root_hash ~ '^[0-9a-f]{64}$'
        AND content_hash ~ '^[0-9a-f]{64}$'
        AND integrity_hash ~ '^[0-9a-f]{64}$'
        AND required_evidence_ref
            ~ '^capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$'
        AND agent_graph_utf16_length_v8(content) BETWEEN 1 AND 65536
        AND octet_length(candidate_envelope::text) <= 1048576
    ),
    CONSTRAINT graph_candidate_json_v8 CHECK (
        (
            jsonb_typeof(candidate_envelope) = 'object'
            AND candidate_envelope ->> 'schemaVersion'
                = schema_version
            AND candidate_envelope ->> 'candidateRef'
                = candidate_ref
            AND candidate_envelope ->> 'attemptId' = attempt_id
            AND candidate_envelope ->> 'executionSlotId'
                = execution_slot_id
            AND (candidate_envelope ->> 'repetition')::integer
                = repetition
            AND candidate_envelope ->> 'childRunId' = child_run_id
            AND candidate_envelope ->> 'childTaskId' = child_task_id
            AND (
                candidate_envelope ->> 'sourceRequestOrdinal'
            )::integer = source_request_ordinal
            AND candidate_envelope ->> 'sourceResponseHash'
                = source_response_hash
            AND candidate_envelope ->> 'traceRootHash'
                = trace_root_hash
            AND candidate_envelope ->> 'outputSchema'
                = output_schema
            AND candidate_envelope ->> 'content' = content
            AND candidate_envelope ->> 'contentHash' = content_hash
            AND candidate_envelope ->> 'requiredEvidenceRef'
                = required_evidence_ref
            AND (
                candidate_envelope ->> 'requiredEvidenceAvailable'
            )::boolean = required_evidence_available
            AND candidate_envelope ->> 'integrityProfile'
                = integrity_profile
            AND candidate_envelope ->> 'integrityHash'
                = integrity_hash
            AND jsonb_typeof(candidate_envelope -> 'evidenceRefs')
                = 'array'
            AND jsonb_typeof(
                candidate_envelope -> 'obtainedEvidenceRefs'
            ) = 'array'
        ) IS TRUE
    )
);

CREATE TABLE agent_graph_attempt_terminal_bindings (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    event_sequence INTEGER NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    bundle_hash CHAR(64) NOT NULL,
    trace_root_hash CHAR(64) NOT NULL,
    effect_ref VARCHAR(1024),
    effect_hash CHAR(64),
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    terminal_hash CHAR(64) NOT NULL,
    CONSTRAINT graph_terminal_binding_pk_v8 PRIMARY KEY (
        principal_id,
        attempt_id,
        role
    ),
    CONSTRAINT graph_terminal_hash_uq_v8 UNIQUE (
        principal_id,
        attempt_id,
        terminal_hash
    ),
    CONSTRAINT graph_terminal_reservation_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        manifest_hash,
        role,
        run_id,
        task_id
    ) REFERENCES agent_graph_attempt_run_bindings (
        principal_id,
        attempt_id,
        manifest_hash,
        role,
        run_id,
        task_id
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_terminal_run_fk_v8 FOREIGN KEY (
        principal_id,
        run_id,
        task_id,
        status
    ) REFERENCES agent_runs (
        principal_id,
        run_id,
        task_id,
        lifecycle_status
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_terminal_event_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        event_sequence,
        terminal_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id,
        attempt_id,
        sequence,
        evidence_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_terminal_role_v8 CHECK (
        (role = 'CHILD' AND event_sequence = 15)
        OR (role = 'PARENT' AND event_sequence = 16)
    ),
    CONSTRAINT graph_terminal_hashes_v8 CHECK (
        bundle_hash ~ '^[0-9a-f]{64}$'
        AND trace_root_hash ~ '^[0-9a-f]{64}$'
        AND terminal_hash ~ '^[0-9a-f]{64}$'
        AND (effect_hash IS NULL OR effect_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT graph_terminal_effect_v8 CHECK (
        (effect_ref IS NULL) = (effect_hash IS NULL)
        AND (
            (
                status = 'SUCCEEDED'
                AND effect_ref IS NOT NULL
            )
            OR (
                status IN (
                    'FAILED',
                    'NEEDS_INPUT',
                    'BLOCKED',
                    'CANCELLED'
                )
                AND effect_ref IS NULL
            )
        )
    )
);

ALTER TABLE agent_graph_attempt_seals
    DROP CONSTRAINT agent_graph_attempt_seals_disabled_v7,
    ADD COLUMN pre_seal_sequence INTEGER NOT NULL,
    ADD COLUMN pre_seal_head_hash CHAR(64) NOT NULL,
    ADD COLUMN provider_attribution_1_hash CHAR(64) NOT NULL,
    ADD COLUMN provider_attribution_2_hash CHAR(64) NOT NULL,
    ADD COLUMN candidate_ref VARCHAR(256),
    ADD COLUMN candidate_integrity_hash CHAR(64),
    ADD COLUMN child_terminal_hash CHAR(64) NOT NULL,
    ADD COLUMN parent_terminal_hash CHAR(64) NOT NULL;

ALTER TABLE agent_graph_attempt_seals
    ADD CONSTRAINT graph_seal_shape_v8 CHECK (
        pre_seal_sequence = 16
        AND final_sequence = 17
        AND graph_outcome IN ('SUCCEEDED', 'FAILED')
        AND billing_status = 'ATTRIBUTED'
        AND pre_seal_head_hash ~ '^[0-9a-f]{64}$'
        AND final_head_hash ~ '^[0-9a-f]{64}$'
        AND provider_attribution_1_hash ~ '^[0-9a-f]{64}$'
        AND provider_attribution_2_hash ~ '^[0-9a-f]{64}$'
        AND provider_attribution_1_hash
            <> provider_attribution_2_hash
        AND (candidate_ref IS NULL)
            = (candidate_integrity_hash IS NULL)
        AND (
            candidate_integrity_hash IS NULL
            OR candidate_integrity_hash ~ '^[0-9a-f]{64}$'
        )
        AND child_terminal_hash ~ '^[0-9a-f]{64}$'
        AND parent_terminal_hash ~ '^[0-9a-f]{64}$'
        AND seal_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT graph_seal_pre_event_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        pre_seal_sequence,
        pre_seal_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id,
        attempt_id,
        sequence,
        current_head_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_final_event_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        final_sequence,
        final_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id,
        attempt_id,
        sequence,
        current_head_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_attr_1_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        provider_attribution_1_hash
    ) REFERENCES agent_graph_attempt_provider_attributions (
        principal_id,
        attempt_id,
        attribution_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_attr_2_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        provider_attribution_2_hash
    ) REFERENCES agent_graph_attempt_provider_attributions (
        principal_id,
        attempt_id,
        attribution_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_candidate_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        candidate_ref,
        candidate_integrity_hash
    ) REFERENCES agent_graph_attempt_candidates (
        principal_id,
        attempt_id,
        candidate_ref,
        integrity_hash
    ) MATCH FULL DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_child_terminal_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        child_terminal_hash
    ) REFERENCES agent_graph_attempt_terminal_bindings (
        principal_id,
        attempt_id,
        terminal_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT graph_seal_parent_terminal_fk_v8 FOREIGN KEY (
        principal_id,
        attempt_id,
        parent_terminal_hash
    ) REFERENCES agent_graph_attempt_terminal_bindings (
        principal_id,
        attempt_id,
        terminal_hash
    ) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION agent_graph_terminal_run_valid_v8(
    checked_principal VARCHAR,
    checked_attempt CHAR(64),
    checked_role VARCHAR
)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM agent_runs run
        JOIN agent_graph_attempt_terminal_bindings terminal
          ON terminal.principal_id = run.principal_id
         AND terminal.attempt_id = run.graph_attempt_id
         AND terminal.role = run.graph_role
         AND terminal.run_id = run.run_id
         AND terminal.task_id = run.task_id
        WHERE run.principal_id = checked_principal
          AND run.graph_attempt_id = checked_attempt
          AND run.graph_role = checked_role
          AND run.lifecycle_status <> 'RUNNING'
          AND terminal.status = run.lifecycle_status
          AND terminal.bundle_hash = run.bundle_hash
          AND terminal.trace_root_hash = run.trace_root_hash
          AND terminal.completed_at = run.completed_at
          AND run.result_envelope ->> 'runId' = run.run_id
          AND run.result_envelope ->> 'taskId' = run.task_id
          AND run.result_envelope ->> 'status'
                = run.lifecycle_status
          AND run.bundle ->> 'runId' = run.run_id
          AND run.bundle ->> 'taskId' = run.task_id
          AND run.bundle ->> 'outcome' = run.lifecycle_status
          AND run.bundle ->> 'integrityHash' = run.bundle_hash
          AND run.bundle ->> 'traceRootHash'
                = run.trace_root_hash
          AND run.bundle -> 'task' = run.task_envelope
          AND run.bundle -> 'result' = run.result_envelope
          AND run.bundle ->> 'modelResolved'
                = run.resolved_model
          AND run.bundle ->> 'harnessVersion'
                = run.harness_version
          AND (run.bundle ->> 'costUsd')::numeric
                = run.cost_usd
          AND (run.bundle ->> 'tokenCount')::bigint
                = run.token_count
          AND (run.bundle ->> 'latencyMs')::bigint
                = run.latency_ms
          AND (run.bundle ->> 'failureAttribution')
                IS NOT DISTINCT FROM run.failure_attribution
          AND (
                SELECT count(*)
                FROM agent_trace_events trace
                WHERE trace.principal_id = run.principal_id
                  AND trace.run_id = run.run_id
              ) = run.last_event_sequence
          AND NOT EXISTS (
                SELECT 1
                FROM (
                    SELECT
                        trace.sequence,
                        row_number() OVER (
                            ORDER BY trace.sequence
                        ) AS expected_sequence,
                        trace.previous_root_hash,
                        trace.current_root_hash,
                        lag(trace.current_root_hash) OVER (
                            ORDER BY trace.sequence
                        ) AS prior_root
                    FROM agent_trace_events trace
                    WHERE trace.principal_id = run.principal_id
                      AND trace.run_id = run.run_id
                ) chain
                WHERE chain.sequence <> chain.expected_sequence
                   OR (
                        chain.sequence = 1
                        AND chain.previous_root_hash <>
                            '1e689944b93c8523a09a2fc92b76edfd58adfe5864d2a9b68c72e4043ab9b79f'
                   )
                   OR (
                        chain.sequence > 1
                        AND chain.previous_root_hash
                            <> chain.prior_root
                   )
          )
          AND (
                (
                    run.last_event_sequence = 0
                    AND run.trace_root_hash =
                        '1e689944b93c8523a09a2fc92b76edfd58adfe5864d2a9b68c72e4043ab9b79f'
                )
                OR run.trace_root_hash = (
                    SELECT trace.current_root_hash
                    FROM agent_trace_events trace
                    WHERE trace.principal_id = run.principal_id
                      AND trace.run_id = run.run_id
                    ORDER BY trace.sequence DESC
                    LIMIT 1
                )
          )
          AND NOT EXISTS (
                (
                    SELECT
                        binding.role,
                        binding.ordinal,
                        binding.ref,
                        binding."contentHash"
                    FROM jsonb_to_recordset(
                        COALESCE(
                            run.bundle -> 'resourceBindings',
                            '[]'::jsonb
                        )
                    ) AS binding(
                        role TEXT,
                        ordinal INTEGER,
                        ref TEXT,
                        "contentHash" TEXT
                    )
                    EXCEPT
                    SELECT durable.role,
                           durable.ordinal,
                           durable.resource_ref,
                           durable.content_hash
                    FROM agent_run_resource_bindings durable
                    WHERE durable.principal_id = run.principal_id
                      AND durable.run_id = run.run_id
                )
                UNION ALL
                (
                    SELECT durable.role,
                           durable.ordinal,
                           durable.resource_ref,
                           durable.content_hash
                    FROM agent_run_resource_bindings durable
                    WHERE durable.principal_id = run.principal_id
                      AND durable.run_id = run.run_id
                    EXCEPT
                    SELECT
                        binding.role,
                        binding.ordinal,
                        binding.ref,
                        binding."contentHash"
                    FROM jsonb_to_recordset(
                        COALESCE(
                            run.bundle -> 'resourceBindings',
                            '[]'::jsonb
                        )
                    ) AS binding(
                        role TEXT,
                        ordinal INTEGER,
                        ref TEXT,
                        "contentHash" TEXT
                    )
                )
          )
    );
$$;

CREATE FUNCTION agent_assert_graph_attempt_v8(
    checked_principal VARCHAR,
    checked_attempt CHAR(64)
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    attempt_record agent_graph_attempts%ROWTYPE;
    head_record agent_graph_attempt_heads%ROWTYPE;
    intent_count INTEGER;
    attributed_event_count INTEGER;
    attribution_count INTEGER;
    event_count INTEGER;
    child_terminal_count INTEGER;
    parent_terminal_count INTEGER;
    seal_count INTEGER;
BEGIN
    SELECT *
    INTO attempt_record
    FROM agent_graph_attempts
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT *
    INTO head_record
    FROM agent_graph_attempt_heads
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'graph attempt requires one exact V8 head'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'graph_attempt_provider_truth_v8';
    END IF;

    IF head_record.last_sequence > 11
       AND attempt_record.graph_protocol_version
            <> 'postgres-graph-terminal-v1' THEN
        RAISE EXCEPTION
            'legacy graph protocol cannot commit terminal truth'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'graph_attempt_provider_truth_v8';
    END IF;
    IF head_record.last_sequence = 16 THEN
        RAISE EXCEPTION
            'sequence 16 is transactional pre-seal state only'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'graph_attempt_final_state_v8';
    END IF;

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
            WHERE chain.sequence <> chain.expected_sequence
               OR (
                    chain.sequence = 1
                    AND chain.previous_head_hash
                        <> attempt_record.initial_head_hash
               )
               OR (
                    chain.sequence > 1
                    AND chain.previous_head_hash
                        <> chain.prior_head
               )
               OR (
                    chain.prior_time IS NOT NULL
                    AND chain.occurred_at < chain.prior_time
               )
       ) THEN
        RAISE EXCEPTION
            'graph V8 event chain does not match its head'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'graph_attempt_terminal_coherence_v8';
    END IF;

    SELECT count(*)
    INTO intent_count
    FROM agent_graph_attempt_events
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt
      AND event_type = 'PROVIDER_INTENT';

    SELECT count(*)
    INTO attributed_event_count
    FROM agent_graph_attempt_events
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt
      AND event_type = 'PROVIDER_ATTRIBUTED';

    SELECT count(*)
    INTO attribution_count
    FROM agent_graph_attempt_provider_attributions
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;

    IF intent_count <> head_record.provider_intent_count
       OR attributed_event_count
            <> head_record.provider_attribution_count
       OR attribution_count
            <> head_record.provider_attribution_count
       OR EXISTS (
            SELECT 1
            FROM agent_graph_attempt_provider_attributions attribution
            JOIN agent_graph_attempt_events attributed_event
              ON attributed_event.principal_id
                    = attribution.principal_id
             AND attributed_event.attempt_id
                    = attribution.attempt_id
             AND attributed_event.sequence
                    = attribution.event_sequence
            JOIN agent_graph_attempt_events intent
              ON intent.principal_id = attribution.principal_id
             AND intent.attempt_id = attribution.attempt_id
             AND intent.sequence = CASE
                 WHEN attribution.request_ordinal = 1 THEN 11
                 WHEN attribution.request_ordinal = 2 THEN 13
                 ELSE -1
             END
            WHERE attribution.principal_id = checked_principal
              AND attribution.attempt_id = checked_attempt
              AND (
                attributed_event.event_type
                    <> 'PROVIDER_ATTRIBUTED'
                OR attributed_event.request_ordinal
                    <> attribution.request_ordinal
                OR attributed_event.evidence_hash
                    <> attribution.attribution_hash
                OR attributed_event.occurred_at
                    <> attribution.attributed_at
                OR intent.event_type <> 'PROVIDER_INTENT'
                OR intent.request_ordinal
                    <> attribution.request_ordinal
                OR intent.request_hash
                    <> attribution.request_hash
                OR intent.model_requested
                    <> attribution.model_requested
                OR attribution.provider_actor
                    <> attempt_record.child_actor
                OR attribution.pricing_profile_fingerprint
                    <> attempt_record.pricing_profile_fingerprint
              )
       )
       OR (
            head_record.last_sequence <= 14
            AND (
                EXISTS (
                    SELECT 1
                    FROM agent_graph_attempt_candidates
                    WHERE principal_id = checked_principal
                      AND attempt_id = checked_attempt
                )
                OR EXISTS (
                    SELECT 1
                    FROM agent_graph_attempt_terminal_bindings
                    WHERE principal_id = checked_principal
                      AND attempt_id = checked_attempt
                )
            )
       ) THEN
        RAISE EXCEPTION
            'graph provider truth is incomplete or mismatched'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'graph_attempt_provider_truth_v8';
    END IF;

    SELECT count(*) FILTER (WHERE role = 'CHILD'),
           count(*) FILTER (WHERE role = 'PARENT')
    INTO child_terminal_count, parent_terminal_count
    FROM agent_graph_attempt_terminal_bindings
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    SELECT count(*)
    INTO seal_count
    FROM agent_graph_attempt_seals
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;

    IF head_record.last_sequence <= 14 THEN
        IF child_terminal_count <> 0
           OR parent_terminal_count <> 0
           OR seal_count <> 0 THEN
            RAISE EXCEPTION
                'terminal truth exists before sequence 15'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'graph_attempt_terminal_coherence_v8';
        END IF;
    ELSIF head_record.last_sequence = 15 THEN
        IF child_terminal_count <> 1
           OR parent_terminal_count <> 0
           OR seal_count <> 0
           OR NOT EXISTS (
                SELECT 1
                FROM agent_graph_attempt_terminal_bindings terminal
                JOIN agent_runs child
                  ON child.principal_id = terminal.principal_id
                 AND child.run_id = terminal.run_id
                WHERE terminal.principal_id = checked_principal
                  AND terminal.attempt_id = checked_attempt
                  AND terminal.role = 'CHILD'
                  AND child.graph_attempt_id = checked_attempt
                  AND child.graph_role = 'CHILD'
                  AND child.lifecycle_status = terminal.status
                  AND child.lifecycle_status <> 'RUNNING'
                  AND child.bundle_hash = terminal.bundle_hash
                  AND child.trace_root_hash
                        = terminal.trace_root_hash
                  AND child.completed_at = terminal.completed_at
           )
           OR NOT EXISTS (
                SELECT 1
                FROM agent_runs parent
                WHERE parent.principal_id = checked_principal
                  AND parent.graph_attempt_id = checked_attempt
                  AND parent.graph_role = 'PARENT'
                  AND parent.lifecycle_status = 'RUNNING'
           )
           OR NOT agent_graph_terminal_run_valid_v8(
                checked_principal, checked_attempt, 'CHILD'
           )
           OR EXISTS (
                SELECT 1
                FROM agent_runs child
                WHERE child.principal_id = checked_principal
                  AND child.graph_attempt_id = checked_attempt
                  AND child.graph_role = 'CHILD'
                  AND (
                    child.cost_usd <> (
                        SELECT COALESCE(
                            sum(observed_cost_usd), 0
                        )
                        FROM agent_graph_attempt_provider_attributions
                        WHERE principal_id = checked_principal
                          AND attempt_id = checked_attempt
                    )
                    OR child.token_count <> (
                        SELECT COALESCE(sum(total_tokens), 0)
                        FROM agent_graph_attempt_provider_attributions
                        WHERE principal_id = checked_principal
                          AND attempt_id = checked_attempt
                    )
                  )
           )
           OR EXISTS (
                SELECT 1
                FROM agent_runs child
                JOIN agent_graph_attempt_terminal_bindings terminal
                  ON terminal.principal_id = child.principal_id
                 AND terminal.attempt_id = child.graph_attempt_id
                 AND terminal.role = 'CHILD'
                WHERE child.principal_id = checked_principal
                  AND child.graph_attempt_id = checked_attempt
                  AND child.graph_role = 'CHILD'
                  AND child.lifecycle_status = 'SUCCEEDED'
                  AND (
                    terminal.effect_ref IS NULL
                    OR (
                        SELECT count(*)
                        FROM agent_graph_attempt_candidates
                        WHERE principal_id = checked_principal
                          AND attempt_id = checked_attempt
                    ) <> 1
                    OR (
                        SELECT count(*)
                        FROM agent_worker_results
                        WHERE principal_id = checked_principal
                          AND child_run_id = child.run_id
                    ) <> 1
                    OR (
                        SELECT count(*)
                        FROM agent_run_resource_bindings
                        WHERE principal_id = checked_principal
                          AND run_id = child.run_id
                          AND role = 'WORKER_RESULT'
                    ) <> 1
                    OR NOT EXISTS (
                        SELECT 1
                        FROM agent_graph_attempt_candidates candidate
                        JOIN agent_graph_attempt_provider_attributions source
                          ON source.principal_id = candidate.principal_id
                         AND source.attempt_id = candidate.attempt_id
                         AND source.request_ordinal = 2
                        JOIN agent_worker_results worker_result
                          ON worker_result.principal_id
                                = candidate.principal_id
                         AND worker_result.child_run_id
                                = candidate.child_run_id
                        JOIN agent_run_resource_bindings result_binding
                          ON result_binding.principal_id
                                = candidate.principal_id
                         AND result_binding.run_id
                                = candidate.child_run_id
                         AND result_binding.role = 'WORKER_RESULT'
                        WHERE candidate.principal_id
                                = checked_principal
                          AND candidate.attempt_id = checked_attempt
                          AND candidate.manifest_hash
                                = attempt_record.manifest_hash
                          AND candidate.execution_slot_id
                                = attempt_record.execution_slot_id
                          AND candidate.repetition
                                = attempt_record.experiment_repetition
                          AND candidate.source_response_hash
                                = source.response_hash
                          AND candidate.trace_root_hash
                                = child.trace_root_hash
                          AND candidate.output_schema
                                = child.task_envelope ->> 'outputSchema'
                          AND candidate.required_evidence_ref
                                = 'capture://'
                                    || attempt_record.capture_id
                          AND candidate.created_at
                                = terminal.completed_at
                          AND candidate.content
                                = worker_result.worker_result_envelope
                                    ->> 'content'
                          AND candidate.content_hash
                                = worker_result.content_hash
                          AND candidate.output_schema
                                = worker_result.worker_result_envelope
                                    ->> 'outputSchema'
                          AND candidate.candidate_envelope
                                    -> 'evidenceRefs'
                                = worker_result.worker_result_envelope
                                    -> 'evidenceRefs'
                          AND candidate.candidate_envelope
                                    -> 'obtainedEvidenceRefs'
                                = child.result_envelope
                                    -> 'evidenceRefs'
                          AND candidate.required_evidence_available
                                = (
                                    child.result_envelope
                                        -> 'evidenceRefs'
                                ) ? candidate.required_evidence_ref
                          AND terminal.effect_ref
                                = worker_result.worker_result_ref
                          AND terminal.effect_hash
                                = worker_result.integrity_hash
                          AND result_binding.resource_ref
                                = worker_result.worker_result_ref
                          AND result_binding.content_hash
                                = worker_result.integrity_hash
                          AND result_binding.worker_result_child_run_id
                                = child.run_id
                          AND EXISTS (
                                SELECT 1
                                FROM agent_trace_events final
                                WHERE final.principal_id
                                        = child.principal_id
                                  AND final.run_id = child.run_id
                                  AND final.event_type
                                        = 'STRUCTURED_FINAL'
                                  AND final.resource_ref
                                        = 'proposal://sha256:'
                                            || candidate.content_hash
                          )
                          AND (
                                SELECT count(*)
                                FROM agent_trace_events final
                                WHERE final.principal_id
                                        = child.principal_id
                                  AND final.run_id = child.run_id
                                  AND final.event_type
                                        = 'STRUCTURED_FINAL'
                          ) = 1
                    )
                  )
           )
           OR EXISTS (
                SELECT 1
                FROM agent_runs child
                JOIN agent_graph_attempt_terminal_bindings terminal
                  ON terminal.principal_id = child.principal_id
                 AND terminal.attempt_id = child.graph_attempt_id
                 AND terminal.role = 'CHILD'
                WHERE child.principal_id = checked_principal
                  AND child.graph_attempt_id = checked_attempt
                  AND child.graph_role = 'CHILD'
                  AND child.lifecycle_status <> 'SUCCEEDED'
                  AND (
                    terminal.effect_ref IS NOT NULL
                    OR EXISTS (
                        SELECT 1
                        FROM agent_worker_results
                        WHERE principal_id = checked_principal
                          AND child_run_id = child.run_id
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM agent_run_resource_bindings
                        WHERE principal_id = checked_principal
                          AND run_id = child.run_id
                          AND role = 'WORKER_RESULT'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM agent_graph_attempt_candidates candidate
                        WHERE candidate.principal_id
                                = checked_principal
                          AND candidate.attempt_id = checked_attempt
                          AND (
                            candidate.trace_root_hash
                                <> child.trace_root_hash
                            OR candidate.required_evidence_ref
                                <> 'capture://'
                                    || attempt_record.capture_id
                            OR candidate.created_at
                                <> terminal.completed_at
                            OR NOT EXISTS (
                                SELECT 1
                                FROM agent_trace_events final
                                WHERE final.principal_id
                                        = child.principal_id
                                  AND final.run_id = child.run_id
                                  AND final.event_type
                                        = 'STRUCTURED_FINAL'
                                  AND final.resource_ref
                                        = 'proposal://sha256:'
                                            || candidate.content_hash
                            )
                          )
                    )
                  )
           ) THEN
            RAISE EXCEPTION
                'sequence 15 requires exact child terminal truth'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'graph_attempt_terminal_coherence_v8';
        END IF;
    ELSIF head_record.last_sequence = 17 THEN
        IF child_terminal_count <> 1
           OR parent_terminal_count <> 1
           OR seal_count <> 1
           OR NOT agent_graph_terminal_run_valid_v8(
                checked_principal, checked_attempt, 'CHILD'
           )
           OR NOT agent_graph_terminal_run_valid_v8(
                checked_principal, checked_attempt, 'PARENT'
           )
           OR NOT EXISTS (
                SELECT 1
                FROM agent_runs parent
                JOIN agent_runs child
                  ON child.principal_id = parent.principal_id
                 AND child.parent_run_id = parent.run_id
                 AND child.graph_attempt_id = parent.graph_attempt_id
                 AND child.graph_role = 'CHILD'
                JOIN agent_graph_attempt_terminal_bindings parent_terminal
                  ON parent_terminal.principal_id = parent.principal_id
                 AND parent_terminal.attempt_id
                        = parent.graph_attempt_id
                 AND parent_terminal.role = 'PARENT'
                WHERE parent.principal_id = checked_principal
                  AND parent.graph_attempt_id = checked_attempt
                  AND parent.graph_role = 'PARENT'
                  AND parent.lifecycle_status <> 'RUNNING'
                  AND parent.cost_usd = child.cost_usd
                  AND parent.token_count = child.token_count
                  AND (
                        SELECT count(*)
                        FROM agent_run_resource_bindings handoff
                        WHERE handoff.principal_id
                                = parent.principal_id
                          AND handoff.run_id = parent.run_id
                          AND handoff.role = 'HANDOFF'
                          AND handoff.resource_ref
                                = 'agent-run://' || child.run_id
                          AND handoff.content_hash = child.bundle_hash
                          AND handoff.handoff_child_run_id
                                = child.run_id
                  ) = 1
                  AND (
                        SELECT count(*)
                        FROM agent_trace_events request
                        WHERE request.principal_id
                                = parent.principal_id
                          AND request.run_id = parent.run_id
                          AND request.event_type = 'HANDOFF_REQUEST'
                          AND request.resource_ref
                                = 'agent-run://' || child.run_id
                  ) = 1
                  AND (
                        SELECT count(*)
                        FROM agent_trace_events result
                        WHERE result.principal_id
                                = parent.principal_id
                          AND result.run_id = parent.run_id
                          AND result.event_type = CASE
                              WHEN child.lifecycle_status = 'SUCCEEDED'
                                  THEN 'HANDOFF_RESULT'
                              ELSE 'HANDOFF_REJECTED'
                          END
                          AND result.resource_ref
                                = 'agent-run://' || child.run_id
                  ) = 1
                  AND (
                    (
                        parent.lifecycle_status = 'SUCCEEDED'
                        AND child.lifecycle_status = 'SUCCEEDED'
                        AND parent_terminal.effect_ref IS NOT NULL
                        AND (
                            SELECT count(*)
                            FROM agent_run_resource_bindings artifact_binding
                            WHERE artifact_binding.principal_id
                                    = parent.principal_id
                              AND artifact_binding.run_id = parent.run_id
                              AND artifact_binding.role = 'ARTIFACT'
                        ) = 1
                        AND EXISTS (
                            SELECT 1
                            FROM artifacts artifact
                            JOIN agent_run_resource_bindings artifact_binding
                              ON artifact_binding.principal_id
                                    = parent.principal_id
                             AND artifact_binding.run_id = parent.run_id
                             AND artifact_binding.role = 'ARTIFACT'
                             AND artifact_binding.artifact_id
                                    = artifact.artifact_id
                            JOIN artifact_versions version
                              ON version.principal_id
                                    = artifact.principal_id
                             AND version.artifact_id
                                    = artifact.artifact_id
                             AND artifact_binding.artifact_version
                                    = version.version
                            JOIN agent_graph_attempt_candidates candidate
                              ON candidate.principal_id
                                    = parent.principal_id
                             AND candidate.attempt_id
                                    = parent.graph_attempt_id
                            JOIN agent_worker_results worker_result
                              ON worker_result.principal_id
                                    = parent.principal_id
                             AND worker_result.child_run_id = child.run_id
                            WHERE artifact.principal_id
                                    = parent.principal_id
                              AND artifact.artifact_id
                                    = attempt_record.artifact_id
                              AND artifact.source_capture_id
                                    = attempt_record.capture_id
                              AND version.content = candidate.content
                              AND version.content_hash
                                    = candidate.content_hash
                              AND worker_result.content_hash
                                    = version.content_hash
                              AND artifact_binding.resource_ref
                                    = 'artifact-version://'
                                        || artifact.artifact_id
                                        || '/'
                                        || version.version
                              AND artifact_binding.content_hash
                                    = version.content_hash
                              AND parent_terminal.effect_ref
                                    = artifact_binding.resource_ref
                              AND parent_terminal.effect_hash
                                    = artifact_binding.content_hash
                        )
                    )
                    OR (
                        parent.lifecycle_status <> 'SUCCEEDED'
                        AND parent_terminal.effect_ref IS NULL
                        AND NOT EXISTS (
                            SELECT 1
                            FROM agent_run_resource_bindings artifact_binding
                            WHERE artifact_binding.principal_id
                                    = parent.principal_id
                              AND artifact_binding.run_id = parent.run_id
                              AND artifact_binding.role = 'ARTIFACT'
                        )
                    )
                  )
           )
           OR NOT EXISTS (
                SELECT 1
                FROM agent_graph_attempt_seals seal
                JOIN agent_graph_attempt_events parent_event
                  ON parent_event.principal_id = seal.principal_id
                 AND parent_event.attempt_id = seal.attempt_id
                 AND parent_event.sequence = 16
                JOIN agent_graph_attempt_events seal_event
                  ON seal_event.principal_id = seal.principal_id
                 AND seal_event.attempt_id = seal.attempt_id
                 AND seal_event.sequence = 17
                JOIN agent_graph_attempt_provider_attributions first_attr
                  ON first_attr.principal_id = seal.principal_id
                 AND first_attr.attempt_id = seal.attempt_id
                 AND first_attr.request_ordinal = 1
                JOIN agent_graph_attempt_provider_attributions second_attr
                  ON second_attr.principal_id = seal.principal_id
                 AND second_attr.attempt_id = seal.attempt_id
                 AND second_attr.request_ordinal = 2
                JOIN agent_graph_attempt_terminal_bindings child_terminal
                  ON child_terminal.principal_id = seal.principal_id
                 AND child_terminal.attempt_id = seal.attempt_id
                 AND child_terminal.role = 'CHILD'
                JOIN agent_graph_attempt_terminal_bindings parent_terminal
                  ON parent_terminal.principal_id = seal.principal_id
                 AND parent_terminal.attempt_id = seal.attempt_id
                 AND parent_terminal.role = 'PARENT'
                JOIN agent_runs child
                  ON child.principal_id = seal.principal_id
                 AND child.graph_attempt_id = seal.attempt_id
                 AND child.graph_role = 'CHILD'
                JOIN agent_runs parent
                  ON parent.principal_id = seal.principal_id
                 AND parent.graph_attempt_id = seal.attempt_id
                 AND parent.graph_role = 'PARENT'
                WHERE seal.principal_id = checked_principal
                  AND seal.attempt_id = checked_attempt
                  AND seal.pre_seal_sequence = 16
                  AND seal.final_sequence = 17
                  AND seal.pre_seal_head_hash
                        = parent_event.current_head_hash
                  AND seal.final_head_hash
                        = seal_event.current_head_hash
                  AND parent_event.event_type = 'PARENT_TERMINAL'
                  AND parent_event.evidence_hash
                        = parent_terminal.terminal_hash
                  AND seal_event.event_type = 'TERMINAL_SEALED'
                  AND seal_event.previous_head_hash
                        = seal.pre_seal_head_hash
                  AND seal_event.evidence_hash = seal.seal_hash
                  AND seal_event.occurred_at = seal.sealed_at
                  AND seal.provider_attribution_1_hash
                        = first_attr.attribution_hash
                  AND seal.provider_attribution_2_hash
                        = second_attr.attribution_hash
                  AND seal.child_terminal_hash
                        = child_terminal.terminal_hash
                  AND seal.parent_terminal_hash
                        = parent_terminal.terminal_hash
                  AND seal.graph_outcome = CASE
                      WHEN child.lifecycle_status = 'SUCCEEDED'
                           AND parent.lifecycle_status = 'SUCCEEDED'
                          THEN 'SUCCEEDED'
                      ELSE 'FAILED'
                  END
                  AND (
                    (
                        seal.candidate_ref IS NULL
                        AND NOT EXISTS (
                            SELECT 1
                            FROM agent_graph_attempt_candidates candidate
                            WHERE candidate.principal_id = seal.principal_id
                              AND candidate.attempt_id = seal.attempt_id
                        )
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM agent_graph_attempt_candidates candidate
                        WHERE candidate.principal_id = seal.principal_id
                          AND candidate.attempt_id = seal.attempt_id
                          AND candidate.candidate_ref
                                = seal.candidate_ref
                          AND candidate.integrity_hash
                                = seal.candidate_integrity_hash
                    )
                  )
           ) THEN
            RAISE EXCEPTION
                'sequence 17 requires exact terminal seal truth'
                USING ERRCODE = '23514',
                      CONSTRAINT = 'graph_attempt_final_state_v8';
        END IF;
    END IF;
END;
$$;

ALTER FUNCTION agent_assert_graph_attempt_v7(VARCHAR, CHAR)
RENAME TO agent_assert_graph_attempt_prefix_v7;

CREATE FUNCTION agent_assert_graph_attempt_v7(
    checked_principal VARCHAR,
    checked_attempt CHAR(64)
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    committed_sequence INTEGER;
BEGIN
    SELECT last_sequence
    INTO committed_sequence
    FROM agent_graph_attempt_heads
    WHERE principal_id = checked_principal
      AND attempt_id = checked_attempt;
    IF NOT FOUND OR committed_sequence <= 14 THEN
        PERFORM agent_assert_graph_attempt_prefix_v7(
            checked_principal, checked_attempt);
    ELSE
        PERFORM agent_assert_graph_attempt_v8(
            checked_principal, checked_attempt);
    END IF;
END;
$$;

CREATE FUNCTION agent_graph_assert_row_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        PERFORM agent_assert_graph_attempt_v8(
            OLD.principal_id,
            OLD.attempt_id
        );
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE')
        AND (
            TG_OP = 'INSERT'
            OR OLD.principal_id IS DISTINCT FROM NEW.principal_id
            OR OLD.attempt_id IS DISTINCT FROM NEW.attempt_id
        ) THEN
        PERFORM agent_assert_graph_attempt_v8(
            NEW.principal_id,
            NEW.attempt_id
        );
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION agent_graph_assert_run_dependency_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_principal VARCHAR(200);
    old_run VARCHAR(128);
    old_attempt CHAR(64);
    new_principal VARCHAR(200);
    new_run VARCHAR(128);
    new_attempt CHAR(64);
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        old_principal := OLD.principal_id;
        IF TG_TABLE_NAME = 'agent_runs' THEN
            old_attempt := OLD.graph_attempt_id;
        ELSIF TG_TABLE_NAME = 'agent_worker_results' THEN
            old_run := OLD.child_run_id;
        ELSE
            old_run := OLD.run_id;
        END IF;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_principal := NEW.principal_id;
        IF TG_TABLE_NAME = 'agent_runs' THEN
            new_attempt := NEW.graph_attempt_id;
        ELSIF TG_TABLE_NAME = 'agent_worker_results' THEN
            new_run := NEW.child_run_id;
        ELSE
            new_run := NEW.run_id;
        END IF;
    END IF;

    IF old_attempt IS NULL AND old_run IS NOT NULL THEN
        SELECT graph_attempt_id
        INTO old_attempt
        FROM agent_runs
        WHERE principal_id = old_principal
          AND run_id = old_run;
    END IF;
    IF old_attempt IS NOT NULL THEN
        PERFORM agent_assert_graph_attempt_v8(
            old_principal, old_attempt
        );
    END IF;

    IF new_attempt IS NULL AND new_run IS NOT NULL THEN
        SELECT graph_attempt_id
        INTO new_attempt
        FROM agent_runs
        WHERE principal_id = new_principal
          AND run_id = new_run;
    END IF;
    IF new_attempt IS NOT NULL
        AND (
            TG_OP = 'INSERT'
            OR old_principal IS DISTINCT FROM new_principal
            OR old_attempt IS DISTINCT FROM new_attempt
            OR old_run IS DISTINCT FROM new_run
        ) THEN
        PERFORM agent_assert_graph_attempt_v8(
            new_principal, new_attempt
        );
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION agent_graph_terminal_detail_immutable_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM agent_runs run
        WHERE run.principal_id = OLD.principal_id
          AND run.run_id = OLD.run_id
          AND run.graph_attempt_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'graph terminal Trace and Resource bindings are immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$;

CREATE FUNCTION agent_graph_sealed_artifact_immutable_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM agent_run_resource_bindings binding
        JOIN agent_runs run
          ON run.principal_id = binding.principal_id
         AND run.run_id = binding.run_id
         AND run.graph_role = 'PARENT'
        JOIN agent_graph_attempt_seals seal
          ON seal.principal_id = run.principal_id
         AND seal.attempt_id = run.graph_attempt_id
        WHERE binding.principal_id = OLD.principal_id
          AND binding.role = 'ARTIFACT'
          AND binding.artifact_id = OLD.artifact_id
          AND binding.artifact_version = OLD.version
    ) THEN
        RAISE EXCEPTION
            'sealed graph Artifact version is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$;

CREATE FUNCTION agent_graph_sealed_artifact_identity_guard_v8()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM agent_run_resource_bindings binding
        JOIN agent_runs run
          ON run.principal_id = binding.principal_id
         AND run.run_id = binding.run_id
         AND run.graph_role = 'PARENT'
        JOIN agent_graph_attempt_seals seal
          ON seal.principal_id = run.principal_id
         AND seal.attempt_id = run.graph_attempt_id
        WHERE binding.principal_id = OLD.principal_id
          AND binding.role = 'ARTIFACT'
          AND binding.artifact_id = OLD.artifact_id
    ) AND (
        TG_OP = 'DELETE'
        OR NEW.principal_id IS DISTINCT FROM OLD.principal_id
        OR NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
        OR NEW.source_capture_id
             IS DISTINCT FROM OLD.source_capture_id
    ) THEN
        RAISE EXCEPTION
            'sealed graph Artifact identity is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE
        WHEN TG_OP = 'DELETE' THEN OLD
        ELSE NEW
    END;
END;
$$;

CREATE CONSTRAINT TRIGGER agent_graph_attempts_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_events_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempt_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_heads_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_graph_attempt_heads
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_attributions_assert_v8
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_provider_attributions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_candidates_assert_v8
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_candidates
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_terminal_bindings_assert_v8
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_terminal_bindings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_seals_assert_v8
AFTER INSERT OR UPDATE OR DELETE
ON agent_graph_attempt_seals
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_row_v8();

CREATE CONSTRAINT TRIGGER agent_graph_runs_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_runs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_run_dependency_v8();

CREATE CONSTRAINT TRIGGER agent_graph_traces_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_trace_events
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_run_dependency_v8();

CREATE CONSTRAINT TRIGGER agent_graph_resources_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_run_resource_bindings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_run_dependency_v8();

CREATE CONSTRAINT TRIGGER agent_graph_worker_results_assert_v8
AFTER INSERT OR UPDATE OR DELETE ON agent_worker_results
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION agent_graph_assert_run_dependency_v8();

CREATE TRIGGER graph_attr_immutable_v8
BEFORE UPDATE OR DELETE
ON agent_graph_attempt_provider_attributions
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER graph_candidate_immutable_v8
BEFORE UPDATE OR DELETE
ON agent_graph_attempt_candidates
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER graph_terminal_binding_immutable_v8
BEFORE UPDATE OR DELETE
ON agent_graph_attempt_terminal_bindings
FOR EACH ROW EXECUTE FUNCTION agent_graph_reject_immutable_v7();

CREATE TRIGGER graph_trace_immutable_v8
BEFORE UPDATE OR DELETE ON agent_trace_events
FOR EACH ROW EXECUTE FUNCTION agent_graph_terminal_detail_immutable_v8();

CREATE TRIGGER graph_resource_binding_immutable_v8
BEFORE UPDATE OR DELETE ON agent_run_resource_bindings
FOR EACH ROW EXECUTE FUNCTION agent_graph_terminal_detail_immutable_v8();

CREATE TRIGGER graph_sealed_artifact_version_immutable_v8
BEFORE UPDATE OR DELETE ON artifact_versions
FOR EACH ROW EXECUTE FUNCTION agent_graph_sealed_artifact_immutable_v8();

CREATE TRIGGER graph_sealed_artifact_identity_guard_v8
BEFORE UPDATE OR DELETE ON artifacts
FOR EACH ROW EXECUTE FUNCTION agent_graph_sealed_artifact_identity_guard_v8();
