CREATE FUNCTION emerge_local_draft_undo_scope_hash_v1(
    p_principal_id TEXT,
    p_draft_id TEXT,
    p_creation_attempt_id TEXT,
    p_creation_receipt_id TEXT,
    p_artifact_id TEXT,
    p_artifact_version INTEGER,
    p_artifact_hash TEXT,
    p_undo_nonce TEXT
) RETURNS TEXT
LANGUAGE SQL
IMMUTABLE STRICT PARALLEL SAFE
AS $$
SELECT encode(
    sha256(
        convert_to('emergeos.local-draft-undo-scope.v1', 'UTF8') || decode('00', 'hex')
        || int4send(octet_length(convert_to('CONFIGURED_LOCAL_PRINCIPAL', 'UTF8'))) || convert_to('CONFIGURED_LOCAL_PRINCIPAL', 'UTF8')
        || int4send(octet_length(convert_to(p_principal_id, 'UTF8'))) || convert_to(p_principal_id, 'UTF8')
        || int4send(octet_length(convert_to('EXPLICIT_LOCAL_OWNER_INPUT', 'UTF8'))) || convert_to('EXPLICIT_LOCAL_OWNER_INPUT', 'UTF8')
        || int4send(octet_length(convert_to('LOCAL_DRAFTBOX_LOGICAL_UNDO_V1', 'UTF8'))) || convert_to('LOCAL_DRAFTBOX_LOGICAL_UNDO_V1', 'UTF8')
        || int4send(octet_length(convert_to('LOGICALLY_UNDO_LOCAL_DRAFT', 'UTF8'))) || convert_to('LOGICALLY_UNDO_LOCAL_DRAFT', 'UTF8')
        || int4send(octet_length(convert_to('local://drafts/' || p_draft_id, 'UTF8'))) || convert_to('local://drafts/' || p_draft_id, 'UTF8')
        || int4send(octet_length(convert_to(p_draft_id, 'UTF8'))) || convert_to(p_draft_id, 'UTF8')
        || int4send(octet_length(convert_to(p_creation_attempt_id, 'UTF8'))) || convert_to(p_creation_attempt_id, 'UTF8')
        || int4send(octet_length(convert_to(p_creation_receipt_id, 'UTF8'))) || convert_to(p_creation_receipt_id, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_id, 'UTF8'))) || convert_to(p_artifact_id, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_version::TEXT, 'UTF8'))) || convert_to(p_artifact_version::TEXT, 'UTF8')
        || int4send(octet_length(convert_to(p_artifact_hash, 'UTF8'))) || convert_to(p_artifact_hash, 'UTF8')
        || int4send(octet_length(convert_to('ACTIVE', 'UTF8'))) || convert_to('ACTIVE', 'UTF8')
        || int4send(octet_length(convert_to('CAPTURE_ARTIFACT_HISTORY_RETAINED', 'UTF8'))) || convert_to('CAPTURE_ARTIFACT_HISTORY_RETAINED', 'UTF8')
        || int4send(octet_length(convert_to('local-draft-undo-v1', 'UTF8'))) || convert_to('local-draft-undo-v1', 'UTF8')
        || int4send(octet_length(convert_to('emergeos.local-draftbox', 'UTF8'))) || convert_to('emergeos.local-draftbox', 'UTF8')
        || int4send(octet_length(convert_to('emergeos:local-draftbox', 'UTF8'))) || convert_to('emergeos:local-draftbox', 'UTF8')
        || int4send(octet_length(convert_to('local-draftbox:' || p_principal_id, 'UTF8'))) || convert_to('local-draftbox:' || p_principal_id, 'UTF8')
        || int4send(octet_length(convert_to(p_undo_nonce, 'UTF8'))) || convert_to(p_undo_nonce, 'UTF8')
        || int4send(octet_length(convert_to('1', 'UTF8'))) || convert_to('1', 'UTF8')
    ),
    'hex'
)
$$;

REVOKE ALL ON FUNCTION emerge_local_draft_undo_scope_hash_v1(
    TEXT, TEXT, TEXT, TEXT, TEXT, INTEGER, TEXT, TEXT
) FROM PUBLIC;

ALTER TABLE local_draft_creation_receipts
    ADD CONSTRAINT local_draft_creation_receipts_exact_identity_v19_uq
        UNIQUE (principal_id, attempt_id, receipt_id, draft_id);

CREATE TABLE local_draft_undo_receipts (
    principal_id VARCHAR(200) NOT NULL,
    receipt_id VARCHAR(200) NOT NULL,
    creation_attempt_id VARCHAR(200) NOT NULL,
    draft_id VARCHAR(200) NOT NULL,
    creation_receipt_id VARCHAR(200) NOT NULL,
    artifact_id VARCHAR(200) NOT NULL,
    artifact_version INTEGER NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    scope_schema VARCHAR(200) NOT NULL
        DEFAULT 'emergeos.local-draft-undo-scope.v1',
    undo_nonce VARCHAR(200) NOT NULL,
    scope_hash CHAR(64) GENERATED ALWAYS AS (
        emerge_local_draft_undo_scope_hash_v1(
            principal_id,
            draft_id,
            creation_attempt_id,
            creation_receipt_id,
            artifact_id,
            artifact_version,
            artifact_hash,
            undo_nonce
        )
    ) STORED,
    receipt_type VARCHAR(64) NOT NULL
        DEFAULT 'LOCAL_DRAFT_LOGICALLY_UNDONE_V1',
    effect VARCHAR(64) NOT NULL DEFAULT 'LOGICALLY_UNDONE',
    retention VARCHAR(64) NOT NULL
        DEFAULT 'CAPTURE_ARTIFACT_HISTORY_RETAINED',
    outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    occurred_at TIMESTAMP WITH TIME ZONE,
    simulated BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT local_draft_undo_receipts_pk
        PRIMARY KEY (principal_id, creation_attempt_id),
    CONSTRAINT local_draft_undo_receipts_receipt_id_uq UNIQUE (receipt_id),
    CONSTRAINT local_draft_undo_receipts_draft_uq UNIQUE (principal_id, draft_id),
    CONSTRAINT local_draft_undo_receipts_nonce_uq UNIQUE (principal_id, undo_nonce),
    CONSTRAINT local_draft_undo_receipts_draft_fk
        FOREIGN KEY (principal_id, draft_id, creation_attempt_id)
        REFERENCES local_drafts (principal_id, draft_id, attempt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT local_draft_undo_receipts_creation_receipt_fk
        FOREIGN KEY (
            principal_id,
            creation_attempt_id,
            creation_receipt_id,
            draft_id
        ) REFERENCES local_draft_creation_receipts (
            principal_id,
            attempt_id,
            receipt_id,
            draft_id
        ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT local_draft_undo_receipts_artifact_version_fk
        FOREIGN KEY (principal_id, artifact_id, artifact_version, artifact_hash)
        REFERENCES artifact_versions (principal_id, artifact_id, version, content_hash),
    CONSTRAINT local_draft_undo_receipts_identifiers_valid CHECK (
        btrim(principal_id) <> ''
        AND btrim(receipt_id) <> ''
        AND btrim(creation_attempt_id) <> ''
        AND btrim(draft_id) <> ''
        AND btrim(creation_receipt_id) <> ''
        AND btrim(artifact_id) <> ''
        AND btrim(undo_nonce) <> ''
    ),
    CONSTRAINT local_draft_undo_receipts_artifact_version_valid
        CHECK (artifact_version >= 1),
    CONSTRAINT local_draft_undo_receipts_artifact_hash_valid
        CHECK (artifact_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT local_draft_undo_receipts_truth_valid CHECK (
        scope_schema = 'emergeos.local-draft-undo-scope.v1'
        AND receipt_type = 'LOCAL_DRAFT_LOGICALLY_UNDONE_V1'
        AND effect = 'LOGICALLY_UNDONE'
        AND retention = 'CAPTURE_ARTIFACT_HISTORY_RETAINED'
        AND outcome = 'SUCCEEDED'
        AND NOT simulated
    )
);

REVOKE ALL ON TYPE public.local_draft_undo_receipts FROM PUBLIC;

CREATE FUNCTION canonicalize_local_draft_undo_occurred_at_v19()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.occurred_at := pg_catalog.clock_timestamp();
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION canonicalize_local_draft_undo_occurred_at_v19()
    FROM PUBLIC;

CREATE TRIGGER local_draft_undo_receipts_db_clock_v19
BEFORE INSERT ON local_draft_undo_receipts
FOR EACH ROW
EXECUTE FUNCTION canonicalize_local_draft_undo_occurred_at_v19();

ALTER TABLE local_draft_undo_receipts
    ALTER COLUMN occurred_at SET NOT NULL;

CREATE FUNCTION freeze_local_draft_undo_receipt_v19()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'local logical Undo Receipt is immutable'
        USING ERRCODE = '23514';
END
$$;

REVOKE ALL ON FUNCTION freeze_local_draft_undo_receipt_v19()
    FROM PUBLIC;

CREATE TRIGGER local_draft_undo_receipts_immutable_v19
BEFORE UPDATE OR DELETE ON local_draft_undo_receipts
FOR EACH ROW
EXECUTE FUNCTION freeze_local_draft_undo_receipt_v19();

CREATE FUNCTION enforce_local_draft_undo_authority_v19()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM action_attempts attempt
        JOIN local_drafts draft
          ON draft.principal_id = attempt.principal_id
         AND draft.attempt_id = attempt.attempt_id
        JOIN local_draft_creation_receipts creation_receipt
          ON creation_receipt.principal_id = draft.principal_id
         AND creation_receipt.attempt_id = draft.attempt_id
         AND creation_receipt.draft_id = draft.draft_id
        WHERE attempt.principal_id = NEW.principal_id
          AND attempt.attempt_id = NEW.creation_attempt_id
          AND attempt.status = 'SUCCEEDED'
          AND attempt.state_version = 2
          AND attempt.capability_used_calls = 1
          AND attempt.approval_principal_basis = 'CONFIGURED_LOCAL_PRINCIPAL'
          AND attempt.configured_principal_id = NEW.principal_id
          AND attempt.approval_origin = 'EXPLICIT_LOCAL_OWNER_INPUT'
          AND attempt.execution_route = 'LOCAL_DRAFTBOX_V2'
          AND attempt.action_type = 'CREATE_LOCAL_DRAFT'
          AND attempt.target_ref = 'local://drafts'
          AND attempt.risk = 'REVERSIBLE'
          AND attempt.policy_version = 'local-action-v2'
          AND attempt.connector = 'emergeos.local-draftbox'
          AND attempt.account_ref = 'local-draftbox:' || NEW.principal_id
          AND attempt.capability_subject = NEW.principal_id
          AND attempt.capability_connector = 'emergeos.local-draftbox'
          AND attempt.capability_audience = 'emergeos:local-draftbox'
          AND attempt.capability_account_ref = attempt.account_ref
          AND attempt.capability_max_calls = 1
          AND attempt.artifact_id = NEW.artifact_id
          AND attempt.artifact_version = NEW.artifact_version
          AND attempt.artifact_hash = NEW.artifact_hash
          AND draft.draft_id = NEW.draft_id
          AND draft.attempt_status = 'SUCCEEDED'
          AND draft.artifact_id = NEW.artifact_id
          AND draft.artifact_version = NEW.artifact_version
          AND draft.artifact_hash = NEW.artifact_hash
          AND draft.state = 'ACTIVE'
          AND draft.created_at = attempt.updated_at
          AND creation_receipt.receipt_id = NEW.creation_receipt_id
          AND creation_receipt.receipt_type = 'LOCAL_DRAFT_CREATED_V1'
          AND creation_receipt.outcome = 'SUCCEEDED'
          AND creation_receipt.occurred_at = draft.created_at
          AND NOT creation_receipt.simulated
    ) THEN
        RAISE EXCEPTION 'local logical Undo lacks exact creation authority'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM action_receipts legacy_receipt
        WHERE legacy_receipt.principal_id = NEW.principal_id
          AND legacy_receipt.attempt_id = NEW.creation_attempt_id
    ) THEN
        RAISE EXCEPTION 'local logical Undo cannot bind a provider Receipt'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

REVOKE ALL ON FUNCTION enforce_local_draft_undo_authority_v19()
    FROM PUBLIC;

CREATE CONSTRAINT TRIGGER local_draft_undo_receipts_authority_v19
AFTER INSERT ON local_draft_undo_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_local_draft_undo_authority_v19();
