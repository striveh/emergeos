ALTER TABLE action_attempts
    DROP CONSTRAINT action_attempts_execution_route_v17_valid,
    DROP CONSTRAINT action_attempts_provenance_route_v17_valid,
    ADD CONSTRAINT action_attempts_execution_route_v18_valid
        CHECK (execution_route IN (
            'SIMULATED_PROVIDER_V1',
            'LOCAL_DRAFTBOX_V1',
            'LOCAL_DRAFTBOX_V2'
        )),
    ADD CONSTRAINT action_attempts_provenance_route_v18_valid
        CHECK (
            (
                approval_origin IN ('PRE_V17_UNPROVEN', 'LEGACY_SERVER_IMPLICIT')
                AND execution_route = 'SIMULATED_PROVIDER_V1'
            )
            OR (
                approval_origin = 'EXPLICIT_LOCAL_OWNER_INPUT'
                AND execution_route IN ('LOCAL_DRAFTBOX_V1', 'LOCAL_DRAFTBOX_V2')
            )
        ),
    ADD CONSTRAINT action_attempts_local_draftbox_v2_authority_valid
        CHECK (
            execution_route <> 'LOCAL_DRAFTBOX_V2'
            OR (
                approval_principal_basis = 'CONFIGURED_LOCAL_PRINCIPAL'
                AND configured_principal_id = principal_id
                AND approval_origin = 'EXPLICIT_LOCAL_OWNER_INPUT'
                AND action_type = 'CREATE_LOCAL_DRAFT'
                AND target_ref = 'local://drafts'
                AND risk = 'REVERSIBLE'
                AND policy_version = 'local-action-v2'
                AND connector = 'emergeos.local-draftbox'
                AND capability_connector = 'emergeos.local-draftbox'
                AND capability_audience = 'emergeos:local-draftbox'
                AND account_ref = 'local-draftbox:' || principal_id
                AND capability_account_ref = account_ref
                AND capability_max_calls = 1
                AND (
                    (
                        status = 'PLANNED'
                        AND state_version = 1
                        AND capability_used_calls = 0
                    )
                    OR (
                        status = 'SUCCEEDED'
                        AND state_version = 2
                        AND capability_used_calls = 1
                    )
                )
            )
        ),
    ADD CONSTRAINT action_attempts_local_draftbox_v1_retired_v18_valid
        CHECK (
            execution_route <> 'LOCAL_DRAFTBOX_V1'
            OR (
                status = 'PLANNED'
                AND state_version = 1
                AND capability_used_calls = 0
            )
        );

ALTER TABLE action_attempt_transitions
    DROP CONSTRAINT action_attempt_transitions_state_valid,
    ADD CONSTRAINT action_attempt_transitions_state_v18_valid
        CHECK (
            (
                sequence = 1
                AND from_status IS NULL
                AND to_status = 'PLANNED'
                AND capability_use_delta = 0
            )
            OR (
                sequence > 1
                AND from_status IS NOT NULL
                AND (
                    (
                        from_status = 'PLANNED'
                        AND to_status = 'DISPATCHING'
                        AND capability_use_delta = 1
                    )
                    OR (
                        from_status = 'PLANNED'
                        AND to_status = 'SUCCEEDED'
                        AND capability_use_delta = 1
                    )
                    OR (
                        from_status = 'DISPATCHING'
                        AND to_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                        AND capability_use_delta = 0
                    )
                    OR (
                        from_status = 'UNKNOWN'
                        AND to_status = 'RECONCILING'
                        AND capability_use_delta = 1
                    )
                    OR (
                        from_status = 'RECONCILING'
                        AND to_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')
                        AND capability_use_delta = 0
                    )
                )
            )
        );

CREATE TABLE local_drafts (
    principal_id VARCHAR(200) NOT NULL,
    draft_id VARCHAR(200) NOT NULL,
    attempt_id VARCHAR(200) NOT NULL,
    attempt_status VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    artifact_id VARCHAR(200) NOT NULL,
    artifact_version INTEGER NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT local_drafts_pk PRIMARY KEY (principal_id, draft_id),
    CONSTRAINT local_drafts_attempt_uq UNIQUE (principal_id, attempt_id),
    CONSTRAINT local_drafts_identity_uq
        UNIQUE (principal_id, draft_id, attempt_id),
    CONSTRAINT local_drafts_artifact_version_fk
        FOREIGN KEY (principal_id, artifact_id, artifact_version, artifact_hash)
        REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash),
    CONSTRAINT local_drafts_terminal_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, attempt_status)
        REFERENCES action_attempts (principal_id, attempt_id, status)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT local_drafts_status_valid CHECK (attempt_status = 'SUCCEEDED'),
    CONSTRAINT local_drafts_state_valid CHECK (state = 'ACTIVE'),
    CONSTRAINT local_drafts_artifact_version_valid CHECK (artifact_version >= 1),
    CONSTRAINT local_drafts_artifact_hash_valid
        CHECK (artifact_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE local_draft_creation_receipts (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id VARCHAR(200) NOT NULL,
    outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    receipt_id VARCHAR(200) NOT NULL,
    receipt_type VARCHAR(64) NOT NULL DEFAULT 'LOCAL_DRAFT_CREATED_V1',
    draft_id VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    simulated BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT local_draft_creation_receipts_pk
        PRIMARY KEY (principal_id, attempt_id),
    CONSTRAINT local_draft_creation_receipts_receipt_id_uq UNIQUE (receipt_id),
    CONSTRAINT local_draft_creation_receipts_draft_uq
        UNIQUE (principal_id, draft_id),
    CONSTRAINT local_draft_creation_receipts_draft_fk
        FOREIGN KEY (principal_id, draft_id, attempt_id)
        REFERENCES local_drafts (principal_id, draft_id, attempt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT local_draft_creation_receipts_terminal_attempt_fk
        FOREIGN KEY (principal_id, attempt_id, outcome)
        REFERENCES action_attempts (principal_id, attempt_id, status)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT local_draft_creation_receipts_truth_valid
        CHECK (
            outcome = 'SUCCEEDED'
            AND receipt_type = 'LOCAL_DRAFT_CREATED_V1'
            AND NOT simulated
        )
);

CREATE FUNCTION freeze_local_draftbox_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'local Draftbox creation truth is immutable'
        USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER local_drafts_immutable_v18
BEFORE UPDATE OR DELETE ON local_drafts
FOR EACH ROW
EXECUTE FUNCTION freeze_local_draftbox_v18();

CREATE TRIGGER local_draft_creation_receipts_immutable_v18
BEFORE UPDATE OR DELETE ON local_draft_creation_receipts
FOR EACH ROW
EXECUTE FUNCTION freeze_local_draftbox_v18();

CREATE FUNCTION freeze_retired_action_attempt_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (
        OLD.execution_route = 'LOCAL_DRAFTBOX_V1'
        OR OLD.approval_origin = 'PRE_V17_UNPROVEN'
    ) AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.state_version IS DISTINCT FROM OLD.state_version
        OR NEW.capability_used_calls IS DISTINCT FROM OLD.capability_used_calls
        OR NEW.updated_at IS DISTINCT FROM OLD.updated_at
    ) THEN
        RAISE EXCEPTION 'retired action authority cannot mutate after planning'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER action_attempts_retired_routes_frozen_v18
BEFORE UPDATE ON action_attempts
FOR EACH ROW
EXECUTE FUNCTION freeze_retired_action_attempt_v18();

CREATE FUNCTION enforce_local_draftbox_terminal_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (
        NEW.execution_route = 'LOCAL_DRAFTBOX_V2'
        AND NEW.status = 'SUCCEEDED'
    ) OR EXISTS (
        SELECT 1
        FROM action_attempt_transitions direct_transition
        WHERE direct_transition.principal_id = NEW.principal_id
          AND direct_transition.attempt_id = NEW.attempt_id
          AND direct_transition.sequence = NEW.state_version
          AND direct_transition.from_status = 'PLANNED'
          AND direct_transition.to_status = 'SUCCEEDED'
    ) THEN
        IF NEW.execution_route <> 'LOCAL_DRAFTBOX_V2' THEN
            RAISE EXCEPTION 'direct terminal success is reserved for local Draftbox V2'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.state_version <> 2 OR NEW.capability_used_calls <> 1 THEN
            RAISE EXCEPTION 'local Draftbox terminal attempt has invalid state or budget'
                USING ERRCODE = '23514';
        END IF;

        IF 2 <> (
            SELECT count(*)
            FROM action_attempt_transitions attempt_transition
            WHERE attempt_transition.principal_id = NEW.principal_id
              AND attempt_transition.attempt_id = NEW.attempt_id
        ) OR 2 <> (
            SELECT count(*)
            FROM action_attempt_transitions attempt_transition
            WHERE attempt_transition.principal_id = NEW.principal_id
              AND attempt_transition.attempt_id = NEW.attempt_id
              AND (
                  (
                      attempt_transition.sequence = 1
                      AND attempt_transition.from_status IS NULL
                      AND attempt_transition.to_status = 'PLANNED'
                      AND attempt_transition.capability_use_delta = 0
                      AND attempt_transition.occurred_at = NEW.created_at
                  )
                  OR (
                      attempt_transition.sequence = 2
                      AND attempt_transition.from_status = 'PLANNED'
                      AND attempt_transition.to_status = 'SUCCEEDED'
                      AND attempt_transition.capability_use_delta = 1
                      AND attempt_transition.occurred_at = NEW.updated_at
                  )
              )
        ) THEN
            RAISE EXCEPTION 'local Draftbox terminal attempt lacks its exact transition history'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM action_receipts legacy_receipt
            WHERE legacy_receipt.principal_id = NEW.principal_id
              AND legacy_receipt.attempt_id = NEW.attempt_id
        ) THEN
            RAISE EXCEPTION 'local Draftbox V2 cannot own a legacy provider Receipt'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM local_drafts draft
            JOIN local_draft_creation_receipts receipt
              ON receipt.principal_id = draft.principal_id
             AND receipt.attempt_id = draft.attempt_id
             AND receipt.draft_id = draft.draft_id
            WHERE draft.principal_id = NEW.principal_id
              AND draft.attempt_id = NEW.attempt_id
              AND draft.attempt_status = 'SUCCEEDED'
              AND draft.artifact_id = NEW.artifact_id
              AND draft.artifact_version = NEW.artifact_version
              AND draft.artifact_hash = NEW.artifact_hash
              AND draft.state = 'ACTIVE'
              AND draft.created_at = NEW.updated_at
              AND receipt.outcome = 'SUCCEEDED'
              AND receipt.receipt_type = 'LOCAL_DRAFT_CREATED_V1'
              AND receipt.occurred_at = NEW.updated_at
              AND NOT receipt.simulated
        ) THEN
            RAISE EXCEPTION 'local Draftbox terminal attempt lacks its draft and creation Receipt'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION enforce_local_draftbox_transition_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_route TEXT;
    parent_origin TEXT;
    parent_status TEXT;
    parent_state_version INTEGER;
    parent_used_calls INTEGER;
    parent_created_at TIMESTAMP WITH TIME ZONE;
    parent_updated_at TIMESTAMP WITH TIME ZONE;
BEGIN
    SELECT execution_route, approval_origin, status, state_version,
           capability_used_calls, created_at, updated_at
    INTO parent_route, parent_origin, parent_status, parent_state_version,
         parent_used_calls, parent_created_at, parent_updated_at
    FROM action_attempts
    WHERE principal_id = NEW.principal_id
      AND attempt_id = NEW.attempt_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'action transition has no parent attempt'
            USING ERRCODE = '23503';
    END IF;

    IF parent_route = 'LOCAL_DRAFTBOX_V1'
       OR parent_origin = 'PRE_V17_UNPROVEN' THEN
        IF NEW.sequence <> 1
           OR NEW.from_status IS NOT NULL
           OR NEW.to_status <> 'PLANNED'
           OR NEW.capability_use_delta <> 0
           OR NEW.occurred_at <> parent_created_at
           OR parent_status <> 'PLANNED'
           OR parent_state_version <> 1
           OR parent_used_calls <> 0 THEN
            RAISE EXCEPTION 'retired action authority allows only its initial planning transition'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.from_status = 'PLANNED' AND NEW.to_status = 'SUCCEEDED'
       AND parent_route <> 'LOCAL_DRAFTBOX_V2' THEN
        RAISE EXCEPTION 'direct terminal transition is reserved for local Draftbox V2'
            USING ERRCODE = '23514';
    END IF;

    IF parent_route = 'LOCAL_DRAFTBOX_V2' THEN
        IF NEW.sequence = 1 THEN
            IF NEW.from_status IS NOT NULL
               OR NEW.to_status <> 'PLANNED'
               OR NEW.capability_use_delta <> 0
               OR NEW.occurred_at <> parent_created_at THEN
                RAISE EXCEPTION 'local Draftbox V2 has an invalid planning transition'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.sequence = 2 THEN
            IF NEW.from_status <> 'PLANNED'
               OR NEW.to_status <> 'SUCCEEDED'
               OR NEW.capability_use_delta <> 1
               OR parent_status <> 'SUCCEEDED'
               OR parent_state_version <> 2
               OR parent_used_calls <> 1
               OR NEW.occurred_at <> parent_updated_at THEN
                RAISE EXCEPTION 'local Draftbox V2 has an invalid terminal transition'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'local Draftbox V2 transition history must contain exactly two rows'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END
$$;

CREATE FUNCTION enforce_local_draft_authority_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM action_attempts attempt
        WHERE attempt.principal_id = NEW.principal_id
          AND attempt.attempt_id = NEW.attempt_id
          AND attempt.execution_route = 'LOCAL_DRAFTBOX_V2'
          AND attempt.status = 'SUCCEEDED'
          AND attempt.state_version = 2
          AND attempt.capability_used_calls = 1
          AND attempt.artifact_id = NEW.artifact_id
          AND attempt.artifact_version = NEW.artifact_version
          AND attempt.artifact_hash = NEW.artifact_hash
          AND attempt.updated_at = NEW.created_at
    ) THEN
        RAISE EXCEPTION 'local draft lacks its exact local Draftbox V2 authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION enforce_local_draft_receipt_authority_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM action_attempts attempt
        WHERE attempt.principal_id = NEW.principal_id
          AND attempt.attempt_id = NEW.attempt_id
          AND attempt.execution_route = 'LOCAL_DRAFTBOX_V2'
          AND attempt.status = 'SUCCEEDED'
          AND attempt.state_version = 2
          AND attempt.capability_used_calls = 1
          AND attempt.updated_at = NEW.occurred_at
    ) THEN
        RAISE EXCEPTION 'local draft creation Receipt lacks its exact local Draftbox V2 authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE FUNCTION reject_local_draftbox_legacy_receipt_v18()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM action_attempts attempt
        WHERE attempt.principal_id = NEW.principal_id
          AND attempt.attempt_id = NEW.attempt_id
          AND attempt.execution_route = 'LOCAL_DRAFTBOX_V2'
    ) THEN
        RAISE EXCEPTION 'local Draftbox V2 cannot own a legacy provider Receipt'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER action_attempts_local_draftbox_terminal_v18
AFTER INSERT OR UPDATE ON action_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_local_draftbox_terminal_v18();

CREATE CONSTRAINT TRIGGER action_attempt_transitions_local_draftbox_v18
AFTER INSERT ON action_attempt_transitions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_local_draftbox_transition_v18();

CREATE CONSTRAINT TRIGGER action_receipts_reject_local_draftbox_v18
AFTER INSERT OR UPDATE ON action_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION reject_local_draftbox_legacy_receipt_v18();

CREATE CONSTRAINT TRIGGER local_drafts_authority_v18
AFTER INSERT ON local_drafts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_local_draft_authority_v18();

CREATE CONSTRAINT TRIGGER local_draft_creation_receipts_authority_v18
AFTER INSERT ON local_draft_creation_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_local_draft_receipt_authority_v18();
