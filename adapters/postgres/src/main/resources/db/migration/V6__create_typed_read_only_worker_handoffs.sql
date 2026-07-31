-- V5 did not persist exact parent Run identity. Existing Worker/HANDOFF rows
-- therefore cannot be upgraded without guessing lineage. Fail atomically
-- instead of manufacturing provenance from Task IDs or timestamps.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM agent_run_resource_bindings
        WHERE role = 'HANDOFF'
    ) OR EXISTS (
        SELECT 1
        FROM agent_runs
        WHERE task_envelope ->> 'kind' = 'PROPOSE_ARTICLE_DRAFT'
           OR (
               task_envelope ? 'parentId'
               AND task_envelope -> 'parentId' <> 'null'::jsonb
           )
    ) THEN
        RAISE EXCEPTION
            'V6 cannot infer exact parent Run lineage for legacy Worker or HANDOFF truth';
    END IF;
END
$$;

ALTER TABLE agent_runs
    ADD COLUMN parent_run_id VARCHAR(128),
    ADD COLUMN parent_task_id VARCHAR(128),
    ADD COLUMN run_depth SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN parent_run_depth SMALLINT;

ALTER TABLE agent_runs
    ADD CONSTRAINT agent_runs_principal_run_task_uq
        UNIQUE (principal_id, run_id, task_id),
    ADD CONSTRAINT agent_runs_principal_run_task_depth_uq
        UNIQUE (principal_id, run_id, task_id, run_depth),
    ADD CONSTRAINT agent_runs_principal_run_lifecycle_uq
        UNIQUE (principal_id, run_id, lifecycle_status),
    ADD CONSTRAINT agent_runs_principal_run_task_lifecycle_uq
        UNIQUE (principal_id, run_id, task_id, lifecycle_status),
    ADD CONSTRAINT agent_runs_worker_terminal_lineage_uq
        UNIQUE (
            principal_id,
            parent_run_id,
            run_id,
            task_id,
            lifecycle_status
        ),
    ADD CONSTRAINT agent_runs_parent_child_bundle_uq
        UNIQUE (principal_id, parent_run_id, run_id, bundle_hash),
    ADD CONSTRAINT agent_runs_task_identity_v6
        CHECK (
            (
                jsonb_typeof(task_envelope -> 'id') = 'string'
                AND jsonb_typeof(task_envelope -> 'principalRef') = 'string'
                AND task_envelope ->> 'id' = task_id
                AND task_envelope ->> 'principalRef' = principal_id
            ) IS TRUE
        ),
    ADD CONSTRAINT agent_runs_parent_columns_shape
        CHECK (
            (
                (
                    parent_run_id IS NULL
                    AND parent_task_id IS NULL
                    AND parent_run_depth IS NULL
                    AND run_depth = 0
                )
                OR (
                    parent_run_id IS NOT NULL
                    AND parent_task_id IS NOT NULL
                    AND parent_run_depth = 0
                    AND run_depth = 1
                    AND parent_run_id <> run_id
                    AND parent_task_id <> task_id
                )
            ) IS TRUE
        ),
    ADD CONSTRAINT agent_runs_worker_lineage_shape
        CHECK (
            (
                jsonb_typeof(task_envelope -> 'kind') = 'string'
                AND
                (
                    (
                        task_envelope ->> 'kind'
                            = 'PROPOSE_ARTICLE_DRAFT'
                        AND run_depth = 1
                        AND parent_run_depth = 0
                        AND parent_run_id IS NOT NULL
                        AND task_envelope ->> 'parentId' = parent_task_id
                        AND task_envelope -> 'delegationChain'
                            = jsonb_build_array(parent_task_id)
                    )
                    OR
                    (
                        (task_envelope ->> 'kind')
                            IS DISTINCT FROM 'PROPOSE_ARTICLE_DRAFT'
                        AND run_depth = 0
                        AND parent_run_id IS NULL
                        AND parent_task_id IS NULL
                        AND parent_run_depth IS NULL
                        AND task_envelope -> 'parentId' = 'null'::jsonb
                        AND task_envelope -> 'delegationChain' = '[]'::jsonb
                    )
                )
            ) IS TRUE
        ),
    ADD CONSTRAINT agent_runs_parent_run_fk
        FOREIGN KEY (
            principal_id,
            parent_run_id,
            parent_task_id,
            parent_run_depth
        )
        REFERENCES agent_runs (
            principal_id,
            run_id,
            task_id,
            run_depth
        )
        DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX agent_runs_one_child_per_parent_uq
    ON agent_runs (principal_id, parent_run_id)
    WHERE parent_run_id IS NOT NULL;

CREATE TABLE agent_worker_results (
    principal_id VARCHAR(200) NOT NULL,
    parent_run_id VARCHAR(128) NOT NULL,
    child_run_id VARCHAR(128) NOT NULL,
    child_task_id VARCHAR(128) NOT NULL,
    child_status VARCHAR(32) NOT NULL,
    worker_result_ref VARCHAR(256) NOT NULL,
    worker_result_envelope JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    integrity_hash CHAR(64) NOT NULL,
    CONSTRAINT agent_worker_results_pk
        PRIMARY KEY (principal_id, child_run_id),
    CONSTRAINT agent_worker_results_ref_uq
        UNIQUE (principal_id, worker_result_ref),
    CONSTRAINT agent_worker_results_integrity_uq
        UNIQUE (principal_id, child_run_id, integrity_hash),
    CONSTRAINT agent_worker_results_identity_valid
        CHECK (
            parent_run_id
                ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
            AND child_run_id
                ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
            AND child_task_id ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
            AND worker_result_ref = 'worker-result://' || child_run_id
        ),
    CONSTRAINT agent_worker_results_status_valid
        CHECK (child_status = 'SUCCEEDED'),
    CONSTRAINT agent_worker_results_hashes_valid
        CHECK (
            content_hash ~ '^[0-9a-f]{64}$'
            AND integrity_hash ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT agent_worker_results_json_valid
        CHECK (
            (
                jsonb_typeof(worker_result_envelope) = 'object'
                AND octet_length(worker_result_envelope::text) <= 524288
                AND worker_result_envelope ->> 'schemaVersion' = '1.0'
                AND worker_result_envelope ->> 'childRunId' = child_run_id
                AND worker_result_envelope ->> 'childTaskId' = child_task_id
                AND worker_result_envelope ->> 'workerResultRef'
                    = worker_result_ref
                AND worker_result_envelope ->> 'contentHash' = content_hash
                AND worker_result_envelope ->> 'integrityProfile'
                    = 'emergeos-length-prefixed-sha256-v1'
                AND worker_result_envelope ->> 'integrityHash'
                    = integrity_hash
                AND jsonb_typeof(worker_result_envelope -> 'outputSchema')
                    = 'string'
                AND char_length(
                    btrim(worker_result_envelope ->> 'outputSchema')
                ) BETWEEN 1 AND 2048
                AND jsonb_typeof(worker_result_envelope -> 'content')
                    = 'string'
                AND char_length(worker_result_envelope ->> 'content')
                    BETWEEN 1 AND 65536
                AND jsonb_typeof(worker_result_envelope -> 'evidenceRefs')
                    = 'array'
            ) IS TRUE
        ),
    CONSTRAINT agent_worker_results_child_task_fk
        FOREIGN KEY (
            principal_id,
            parent_run_id,
            child_run_id,
            child_task_id,
            child_status
        )
        REFERENCES agent_runs (
            principal_id,
            parent_run_id,
            run_id,
            task_id,
            lifecycle_status
        )
        DEFERRABLE INITIALLY DEFERRED
);

ALTER TABLE agent_trace_events
    DROP CONSTRAINT agent_trace_events_type_valid,
    ADD CONSTRAINT agent_trace_events_type_valid CHECK (
        event_type IN (
            'MODEL_STEP', 'TOOL_REQUEST', 'TOOL_RESULT', 'TOOL_REJECTED',
            'HANDOFF_REQUEST', 'HANDOFF_RESULT', 'HANDOFF_REJECTED',
            'STRUCTURED_FINAL', 'ARTIFACT_COMMITTED'
        )
    ),
    ADD CONSTRAINT agent_trace_events_handoff_shape CHECK (
        (
            event_type NOT IN (
                'HANDOFF_REQUEST', 'HANDOFF_RESULT', 'HANDOFF_REJECTED'
            )
            OR (
                tool_name IS NULL
                AND (
                    (
                        event_type = 'HANDOFF_REQUEST'
                        AND status = 'REQUESTED'
                        AND resource_ref IS NOT NULL
                        AND resource_ref
                            ~ '^agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
                    )
                    OR
                    (
                        event_type = 'HANDOFF_RESULT'
                        AND status = 'SUCCEEDED'
                        AND resource_ref IS NOT NULL
                        AND resource_ref
                            ~ '^agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
                    )
                    OR
                    (
                        event_type = 'HANDOFF_REJECTED'
                        AND status IN (
                            'BLOCKED',
                            'FAILED',
                            'CANCELLED_UNOBSERVED',
                            'DEADLINE_EXHAUSTED',
                            'DEADLINE_EXCEEDED_UNOBSERVED',
                            'DEADLINE_EXCEEDED_AFTER_CHILD',
                            'LIMIT_EXHAUSTED',
                            'CHILD_FAILED',
                            'CHILD_BLOCKED',
                            'CHILD_NEEDS_INPUT',
                            'CHILD_CANCELLED',
                            'MALFORMED_RESULT'
                        )
                        AND (
                            resource_ref IS NULL
                            OR resource_ref
                                ~ '^agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
                        )
                    )
                )
            )
        ) IS TRUE
    );

ALTER TABLE agent_run_resource_bindings
    ADD COLUMN handoff_child_run_id VARCHAR(128),
    ADD COLUMN worker_result_child_run_id VARCHAR(128),
    ADD COLUMN binding_owner_status VARCHAR(32);

UPDATE agent_run_resource_bindings AS binding
SET binding_owner_status = run.lifecycle_status
FROM agent_runs AS run
WHERE run.principal_id = binding.principal_id
  AND run.run_id = binding.run_id;

ALTER TABLE agent_run_resource_bindings
    ALTER COLUMN binding_owner_status SET NOT NULL,
    DROP CONSTRAINT agent_run_resource_bindings_role_valid,
    DROP CONSTRAINT agent_run_resource_bindings_typed_shape,
    ADD CONSTRAINT agent_run_resource_bindings_role_valid CHECK (
        role IN (
            'EVIDENCE', 'ARTIFACT', 'RECEIPT', 'VERIFICATION',
            'CHECKPOINT', 'HANDOFF', 'WORKER_RESULT'
        )
    ),
    ADD CONSTRAINT agent_run_resource_bindings_owner_status_valid CHECK (
        binding_owner_status IN (
            'RUNNING', 'SUCCEEDED', 'FAILED', 'NEEDS_INPUT',
            'BLOCKED', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT agent_run_resource_bindings_typed_shape CHECK (
        (
            (
                role = 'EVIDENCE'
                AND capture_id IS NOT NULL
                AND artifact_id IS NULL
                AND artifact_version IS NULL
                AND handoff_child_run_id IS NULL
                AND worker_result_child_run_id IS NULL
                AND resource_ref = 'capture://' || capture_id
            )
            OR
            (
                role = 'ARTIFACT'
                AND capture_id IS NULL
                AND artifact_id IS NOT NULL
                AND artifact_version IS NOT NULL
                AND artifact_version >= 1
                AND handoff_child_run_id IS NULL
                AND worker_result_child_run_id IS NULL
                AND resource_ref =
                    'artifact-version://' || artifact_id || '/'
                        || artifact_version::text
            )
            OR
            (
                role = 'HANDOFF'
                AND binding_owner_status <> 'RUNNING'
                AND ordinal = 0
                AND capture_id IS NULL
                AND artifact_id IS NULL
                AND artifact_version IS NULL
                AND handoff_child_run_id IS NOT NULL
                AND worker_result_child_run_id IS NULL
                AND resource_ref = 'agent-run://' || handoff_child_run_id
            )
            OR
            (
                role = 'WORKER_RESULT'
                AND binding_owner_status = 'SUCCEEDED'
                AND ordinal = 0
                AND capture_id IS NULL
                AND artifact_id IS NULL
                AND artifact_version IS NULL
                AND handoff_child_run_id IS NULL
                AND worker_result_child_run_id IS NOT NULL
                AND run_id = worker_result_child_run_id
                AND resource_ref =
                    'worker-result://' || worker_result_child_run_id
            )
            OR
            (
                role NOT IN (
                    'EVIDENCE', 'ARTIFACT', 'HANDOFF', 'WORKER_RESULT'
                )
                AND capture_id IS NULL
                AND artifact_id IS NULL
                AND artifact_version IS NULL
                AND handoff_child_run_id IS NULL
                AND worker_result_child_run_id IS NULL
            )
        ) IS TRUE
    ),
    ADD CONSTRAINT agent_run_resource_bindings_owner_terminal_fk
        FOREIGN KEY (principal_id, run_id, binding_owner_status)
        REFERENCES agent_runs (
            principal_id,
            run_id,
            lifecycle_status
        )
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT agent_run_resource_bindings_handoff_child_fk
        FOREIGN KEY (
            principal_id,
            run_id,
            handoff_child_run_id,
            content_hash
        )
        REFERENCES agent_runs (
            principal_id,
            parent_run_id,
            run_id,
            bundle_hash
        )
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT agent_run_resource_bindings_worker_result_fk
        FOREIGN KEY (
            principal_id,
            worker_result_child_run_id,
            content_hash
        )
        REFERENCES agent_worker_results (
            principal_id,
            child_run_id,
            integrity_hash
        )
        DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX agent_run_resource_bindings_child_consumed_uq
    ON agent_run_resource_bindings (
        principal_id,
        handoff_child_run_id
    )
    WHERE role = 'HANDOFF';

CREATE FUNCTION agent_assert_worker_graph_v6(
    checked_principal_id VARCHAR,
    checked_run_id VARCHAR
) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    checked_status VARCHAR(32);
    checked_kind TEXT;
    checked_parent_run_id VARCHAR(128);
    parent_status VARCHAR(32);
    worker_result_count BIGINT;
    worker_result_binding_count BIGINT;
    child_count BIGINT;
    handoff_count BIGINT;
BEGIN
    SELECT
        lifecycle_status,
        task_envelope ->> 'kind',
        parent_run_id
    INTO
        checked_status,
        checked_kind,
        checked_parent_run_id
    FROM agent_runs
    WHERE principal_id = checked_principal_id
      AND run_id = checked_run_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*)
    INTO worker_result_count
    FROM agent_worker_results
    WHERE principal_id = checked_principal_id
      AND child_run_id = checked_run_id;

    SELECT count(*)
    INTO worker_result_binding_count
    FROM agent_run_resource_bindings
    WHERE principal_id = checked_principal_id
      AND run_id = checked_run_id
      AND role = 'WORKER_RESULT';

    IF checked_kind = 'PROPOSE_ARTICLE_DRAFT' THEN
        IF checked_status = 'SUCCEEDED' THEN
            IF worker_result_count <> 1
                OR worker_result_binding_count <> 1 THEN
                RAISE EXCEPTION
                    'successful Worker requires exact Result and binding'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF worker_result_count <> 0
            OR worker_result_binding_count <> 0 THEN
            RAISE EXCEPTION
                'non-success Worker cannot retain a Result or binding'
                USING ERRCODE = '23514';
        END IF;

        SELECT lifecycle_status
        INTO parent_status
        FROM agent_runs
        WHERE principal_id = checked_principal_id
          AND run_id = checked_parent_run_id;
        IF parent_status <> 'RUNNING'
            AND (
                checked_status = 'RUNNING'
                OR NOT EXISTS (
                    SELECT 1
                    FROM agent_run_resource_bindings
                    WHERE principal_id = checked_principal_id
                      AND run_id = checked_parent_run_id
                      AND role = 'HANDOFF'
                      AND handoff_child_run_id = checked_run_id
                )
            ) THEN
            RAISE EXCEPTION
                'terminal parent must bind its exact terminal Worker child'
                USING ERRCODE = '23514';
        END IF;
    ELSIF worker_result_count <> 0
        OR worker_result_binding_count <> 0 THEN
        RAISE EXCEPTION
            'non-Worker Run cannot own Worker Result truth'
            USING ERRCODE = '23514';
    END IF;

    IF checked_parent_run_id IS NULL
        AND checked_status <> 'RUNNING' THEN
        SELECT count(*)
        INTO child_count
        FROM agent_runs
        WHERE principal_id = checked_principal_id
          AND parent_run_id = checked_run_id;

        SELECT count(*)
        INTO handoff_count
        FROM agent_run_resource_bindings
        WHERE principal_id = checked_principal_id
          AND run_id = checked_run_id
          AND role = 'HANDOFF';

        IF child_count <> handoff_count
            OR child_count > 1 THEN
            RAISE EXCEPTION
                'terminal parent must observe its exact Worker child'
                USING ERRCODE = '23514';
        END IF;
    END IF;
END
$$;

CREATE FUNCTION agent_worker_graph_guard_v6()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_principal_id VARCHAR(200);
    old_run_id VARCHAR(128);
    new_principal_id VARCHAR(200);
    new_run_id VARCHAR(128);
BEGIN
    IF TG_OP IN ('DELETE', 'UPDATE') THEN
        old_principal_id := OLD.principal_id;
        IF TG_TABLE_NAME = 'agent_worker_results' THEN
            old_run_id := OLD.child_run_id;
        ELSE
            old_run_id := OLD.run_id;
        END IF;

        PERFORM agent_assert_worker_graph_v6(
            old_principal_id,
            old_run_id
        );
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_principal_id := NEW.principal_id;
        IF TG_TABLE_NAME = 'agent_worker_results' THEN
            new_run_id := NEW.child_run_id;
        ELSE
            new_run_id := NEW.run_id;
        END IF;

        IF TG_OP = 'INSERT'
            OR old_principal_id IS DISTINCT FROM new_principal_id
            OR old_run_id IS DISTINCT FROM new_run_id THEN
            PERFORM agent_assert_worker_graph_v6(
                new_principal_id,
                new_run_id
            );
        END IF;
    END IF;

    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER agent_runs_worker_graph_guard_v6
AFTER INSERT OR UPDATE OR DELETE ON agent_runs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_worker_graph_guard_v6();

CREATE CONSTRAINT TRIGGER agent_worker_results_graph_guard_v6
AFTER INSERT OR UPDATE OR DELETE ON agent_worker_results
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_worker_graph_guard_v6();

CREATE CONSTRAINT TRIGGER agent_run_bindings_worker_graph_guard_v6
AFTER INSERT OR UPDATE OR DELETE ON agent_run_resource_bindings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION agent_worker_graph_guard_v6();

CREATE FUNCTION agent_worker_result_immutable_v6()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Worker Result truth is immutable'
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER agent_worker_results_immutable_v6
BEFORE UPDATE OR DELETE ON agent_worker_results
FOR EACH ROW
EXECUTE FUNCTION agent_worker_result_immutable_v6();
