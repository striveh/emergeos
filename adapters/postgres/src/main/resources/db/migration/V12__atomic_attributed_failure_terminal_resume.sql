-- Forward-only atomic terminal resume for Pack010 attributed pre-Candidate
-- failures. V1-V11 remain immutable history. The V11 outcome row keeps the
-- provider provenance and the child claim; this migration adds the durable
-- TX-B/TX-C consumption receipt and makes it a deferred graph invariant.

CREATE TABLE agent_graph_attributed_failure_terminal_resumes (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    provenance_hash CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    state_version BIGINT NOT NULL,
    child_claim_version BIGINT NOT NULL,
    child_claimant_id VARCHAR(200) NOT NULL,
    child_fence_token_hash CHAR(64) NOT NULL,
    child_claim_expires_at TIMESTAMPTZ NOT NULL,
    child_claimed_at TIMESTAMPTZ NOT NULL,
    child_sequence INTEGER NOT NULL,
    child_head_hash CHAR(64) NOT NULL,
    child_terminal_hash CHAR(64) NOT NULL,
    child_applied_at TIMESTAMPTZ NOT NULL,
    parent_claim_version BIGINT,
    claimant_id VARCHAR(200),
    fence_token_hash CHAR(64),
    claim_expires_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    parent_sequence INTEGER,
    parent_head_hash CHAR(64),
    parent_terminal_hash CHAR(64),
    terminal_sequence INTEGER,
    terminal_head_hash CHAR(64),
    seal_hash CHAR(64),
    terminal_applied_at TIMESTAMPTZ,
    CONSTRAINT graph_failure_terminal_resume_pk_v12 PRIMARY KEY (
        principal_id, attempt_id),
    CONSTRAINT graph_failure_terminal_resume_outcome_fk_v12 FOREIGN KEY (
        principal_id, attempt_id
    ) REFERENCES agent_graph_attributed_failure_outcomes (
        principal_id, attempt_id
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_attempt_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, manifest_hash
    ) REFERENCES agent_graph_attempts (
        principal_id, attempt_id, manifest_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_child_event_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, child_sequence, child_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id, attempt_id, sequence, current_head_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_child_binding_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, child_terminal_hash
    ) REFERENCES agent_graph_attempt_terminal_bindings (
        principal_id, attempt_id, terminal_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_parent_event_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, parent_sequence, parent_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id, attempt_id, sequence, current_head_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_parent_binding_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, parent_terminal_hash
    ) REFERENCES agent_graph_attempt_terminal_bindings (
        principal_id, attempt_id, terminal_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_final_event_fk_v12 FOREIGN KEY (
        principal_id, attempt_id, terminal_sequence, terminal_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id, attempt_id, sequence, current_head_hash
    ) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT graph_failure_terminal_resume_child_fence_uq_v12 UNIQUE (
        principal_id, child_fence_token_hash),
    CONSTRAINT graph_failure_terminal_resume_parent_fence_uq_v12 UNIQUE (
        principal_id, fence_token_hash),
    CONSTRAINT graph_failure_terminal_resume_hashes_v12 CHECK (
        manifest_hash ~ '^[0-9a-f]{64}$'
        AND provenance_hash ~ '^[0-9a-f]{64}$'
        AND child_fence_token_hash ~ '^[0-9a-f]{64}$'
        AND child_head_hash ~ '^[0-9a-f]{64}$'
        AND child_terminal_hash ~ '^[0-9a-f]{64}$'
        AND (fence_token_hash IS NULL
             OR fence_token_hash ~ '^[0-9a-f]{64}$')
        AND (parent_head_hash IS NULL
             OR parent_head_hash ~ '^[0-9a-f]{64}$')
        AND (parent_terminal_hash IS NULL
             OR parent_terminal_hash ~ '^[0-9a-f]{64}$')
        AND (terminal_head_hash IS NULL
             OR terminal_head_hash ~ '^[0-9a-f]{64}$')
        AND (seal_hash IS NULL OR seal_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT graph_failure_terminal_resume_child_v12 CHECK (
        child_sequence = 15
        AND child_claim_version BETWEEN 2 AND 9007199254740990
        AND state_version > child_claim_version
        AND char_length(btrim(child_claimant_id)) BETWEEN 1 AND 200
        AND child_claim_expires_at > child_claimed_at
        AND child_applied_at >= child_claimed_at
        AND child_applied_at < child_claim_expires_at),
    CONSTRAINT graph_failure_terminal_resume_state_v12 CHECK (
        state_version BETWEEN 3 AND 9007199254740991
        AND (
            (state = 'CHILD_CONSUMED'
             AND state_version = child_claim_version + 1
             AND parent_claim_version IS NULL
             AND claimant_id IS NULL
             AND fence_token_hash IS NULL
             AND claim_expires_at IS NULL
             AND claimed_at IS NULL
             AND parent_sequence IS NULL
             AND parent_head_hash IS NULL
             AND parent_terminal_hash IS NULL
             AND terminal_sequence IS NULL
             AND terminal_head_hash IS NULL
             AND seal_hash IS NULL
             AND terminal_applied_at IS NULL)
            OR
            (state = 'PARENT_CLAIMED'
             AND parent_claim_version = state_version
             AND parent_claim_version > child_claim_version + 1
             AND char_length(btrim(claimant_id)) BETWEEN 1 AND 200
             AND fence_token_hash ~ '^[0-9a-f]{64}$'
             AND fence_token_hash <> child_fence_token_hash
             AND claim_expires_at > claimed_at
             AND claimed_at >= child_applied_at
             AND parent_sequence IS NULL
             AND parent_head_hash IS NULL
             AND parent_terminal_hash IS NULL
             AND terminal_sequence IS NULL
             AND terminal_head_hash IS NULL
             AND seal_hash IS NULL
             AND terminal_applied_at IS NULL)
            OR
            (state = 'TERMINAL_CONSUMED'
             AND state_version = parent_claim_version + 1
             AND parent_claim_version > child_claim_version + 1
             AND char_length(btrim(claimant_id)) BETWEEN 1 AND 200
             AND fence_token_hash ~ '^[0-9a-f]{64}$'
             AND fence_token_hash <> child_fence_token_hash
             AND claim_expires_at > claimed_at
             AND claimed_at >= child_applied_at
             AND parent_sequence = 16
             AND terminal_sequence = 17
             AND parent_head_hash IS NOT NULL
             AND parent_terminal_hash IS NOT NULL
             AND terminal_head_hash IS NOT NULL
             AND seal_hash IS NOT NULL
             AND terminal_applied_at >= claimed_at
             AND terminal_applied_at < claim_expires_at)))
);

DO $migration$
DECLARE
    target_schema NAME := current_schema();
BEGIN
    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_require_failure_resumer_v12(
            expected_function REGPROCEDURE
        )
        RETURNS VOID
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            caller_oid OID;
            expected_owner_oid OID;
            expected_owner_name NAME;
        BEGIN
            SELECT role.oid
            INTO caller_oid
            FROM pg_catalog.pg_roles role
            WHERE role.rolname = session_user;
            SELECT procedure.proowner, owner.rolname
            INTO expected_owner_oid, expected_owner_name
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            JOIN pg_catalog.pg_roles owner
              ON owner.oid = procedure.proowner
            WHERE procedure.oid = expected_function
              AND namespace.nspname = %2$L
              AND (
                (procedure.proname =
                    'agent_graph_read_attributed_failure_resume_v12'
                 AND pg_catalog.pg_get_function_identity_arguments(
                       procedure.oid) =
                    'checked_principal character varying, checked_attempt character, checked_manifest character')
                OR
                (procedure.proname =
                    'agent_graph_claim_attributed_failure_resume_v12'
                 AND pg_catalog.pg_get_function_identity_arguments(
                       procedure.oid) =
                    'checked_principal character varying, checked_attempt character, checked_manifest character, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character, checked_lease_millis integer')
                OR
                (procedure.proname =
                    'agent_graph_complete_claimed_failure_child_v12'
                 AND pg_catalog.pg_get_function_identity_arguments(
                       procedure.oid) =
                    'payload jsonb, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character')
                OR
                (procedure.proname =
                    'agent_graph_complete_claimed_failure_parent_and_seal_v12'
                 AND pg_catalog.pg_get_function_identity_arguments(
                       procedure.oid) =
                    'payload jsonb, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character'))
              AND procedure.prosecdef
              AND procedure.proconfig =
                    ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
              AND owner.rolname = CASE
                    WHEN procedure.proname IN (
                      'agent_graph_complete_claimed_failure_child_v12',
                      'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                      THEN 'emergeos_terminal_owner'
                    ELSE 'emergeos_pack010_schema_owner'
                  END;

            IF caller_oid IS NULL
               OR expected_owner_oid IS NULL
               OR session_user IS DISTINCT FROM
                    'emergeos_failure_resumer'
               OR current_user::regrole::oid
                    IS DISTINCT FROM expected_owner_oid
               OR expected_owner_name IS NULL
               OR NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.oid = caller_oid
                      AND role.rolcanlogin
                      AND NOT role.rolinherit
                      AND NOT role.rolsuper
                      AND NOT role.rolcreatedb
                      AND NOT role.rolcreaterole
                      AND NOT role.rolreplication
                      AND NOT role.rolbypassrls)
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_auth_members membership
                    WHERE membership.member = caller_oid
                       OR membership.roleid = caller_oid)
               OR NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles owner
                    WHERE owner.oid = expected_owner_oid
                      AND NOT owner.rolcanlogin
                      AND NOT owner.rolinherit
                      AND NOT owner.rolsuper
                      AND NOT owner.rolcreatedb
                      AND NOT owner.rolcreaterole
                      AND NOT owner.rolreplication
                      AND NOT owner.rolbypassrls
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_auth_members membership
                        WHERE membership.member = owner.oid
                           OR membership.roleid = owner.oid))
               OR NOT pg_catalog.has_database_privilege(
                    session_user, current_database(), 'CONNECT')
               OR pg_catalog.has_database_privilege(
                    session_user, current_database(),
                    'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
               OR NOT pg_catalog.has_schema_privilege(
                    session_user, %2$L, 'USAGE')
               OR pg_catalog.has_schema_privilege(
                    session_user, %2$L,
                    'CREATE,USAGE WITH GRANT OPTION')
               OR (SELECT pg_catalog.count(*)
                   FROM pg_catalog.pg_proc procedure
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = procedure.pronamespace
                   WHERE namespace.nspname = %2$L
                     AND pg_catalog.has_function_privilege(
                       session_user, procedure.oid, 'EXECUTE')) <> 4
               OR (SELECT pg_catalog.count(*)
                   FROM pg_catalog.pg_proc procedure
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = procedure.pronamespace
                   JOIN pg_catalog.pg_language language
                     ON language.oid = procedure.prolang
                   WHERE namespace.nspname = %2$L
                     AND procedure.proname IN (
                       'agent_graph_read_attributed_failure_resume_v12',
                       'agent_graph_claim_attributed_failure_resume_v12',
                       'agent_graph_complete_claimed_failure_child_v12',
                       'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                     AND procedure.prosecdef
                     AND procedure.provolatile = 'v'
                     AND procedure.proparallel = 'u'
                     AND procedure.prokind = 'f'
                     AND NOT procedure.proisstrict
                     AND NOT procedure.proleakproof
                     AND procedure.proconfig = ARRAY[
                       'search_path=pg_catalog, pg_temp']::TEXT[]
                     AND language.lanname = 'plpgsql') <> 4
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        procedure.proacl,
                        pg_catalog.acldefault(
                          'f', procedure.proowner))) acl
                    WHERE namespace.nspname = %2$L
                      AND procedure.proname IN (
                        'agent_graph_read_attributed_failure_resume_v12',
                        'agent_graph_claim_attributed_failure_resume_v12',
                        'agent_graph_complete_claimed_failure_child_v12',
                        'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                      AND NOT (
                        acl.privilege_type = 'EXECUTE'
                        AND (
                          acl.grantee = procedure.proowner
                          OR (acl.grantee = caller_oid
                              AND NOT acl.is_grantable))))
               OR (SELECT pg_catalog.count(*)
                   FROM pg_catalog.pg_proc procedure
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = procedure.pronamespace
                   CROSS JOIN LATERAL pg_catalog.aclexplode(
                     COALESCE(
                       procedure.proacl,
                       pg_catalog.acldefault(
                         'f', procedure.proowner))) acl
                   WHERE namespace.nspname = %2$L
                     AND procedure.proname IN (
                       'agent_graph_read_attributed_failure_resume_v12',
                       'agent_graph_claim_attributed_failure_resume_v12',
                       'agent_graph_complete_claimed_failure_child_v12',
                       'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                     AND acl.privilege_type = 'EXECUTE'
                     AND acl.grantee = caller_oid
                     AND NOT acl.is_grantable) <> 4
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname <> %2$L
                      AND namespace.nspname <> 'pg_catalog'
                      AND namespace.nspname <> 'information_schema'
                      AND namespace.nspname !~ '^pg_toast'
                      AND namespace.nspname !~ '^pg_temp'
                      AND pg_catalog.has_schema_privilege(
                        session_user, namespace.oid, 'USAGE')
                      AND pg_catalog.has_function_privilege(
                        session_user, procedure.oid, 'EXECUTE'))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = %2$L
                      AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
                      AND (
                        relation.relowner = caller_oid
                        OR pg_catalog.has_table_privilege(
                          session_user, relation.oid,
                          'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                        OR pg_catalog.has_any_column_privilege(
                          session_user, relation.oid,
                          'SELECT,INSERT,UPDATE,REFERENCES')
                        OR (relation.relkind = 'S'
                            AND pg_catalog.has_sequence_privilege(
                              session_user, relation.oid,
                              'USAGE,SELECT,UPDATE')))) THEN
                RAISE EXCEPTION 'V12 failure resumer topology is not exact'
                    USING ERRCODE = '42501';
            END IF;
        END;
        $function$
    $definition$, target_schema, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_require_failure_resumer_v12(REGPROCEDURE) '
            || 'FROM PUBLIC',
        target_schema);

    -- V10 remains the canonical graph mutation implementation. This live
    -- helper replacement preserves the executor branch and additionally
    -- permits only a nested, exact V12 resumer call. The resumer still has no
    -- direct EXECUTE privilege on either V10 semantic function.
    EXECUTE pg_catalog.format($definition$
        CREATE OR REPLACE FUNCTION %1$I.agent_graph_require_executor_v10(
            expected_function REGPROCEDURE
        )
        RETURNS VOID
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            caller_oid OID;
            protected_owner_oid OID;
            explicit_caller_grants BIGINT;
            acl_violations BIGINT;
            executable_surface BIGINT;
            mapped_resume_function REGPROCEDURE;
        BEGIN
            SELECT role.oid
            INTO caller_oid
            FROM pg_catalog.pg_roles role
            WHERE role.rolname = session_user;
            SELECT procedure.proowner
            INTO protected_owner_oid
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            WHERE procedure.oid = expected_function
              AND namespace.nspname = %2$L
              AND procedure.proname IN (
                    'agent_graph_complete_child_v10',
                    'agent_graph_complete_parent_and_seal_v10')
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'payload jsonb'
              AND procedure.prosecdef
              AND procedure.proconfig =
                    ARRAY['search_path=pg_catalog, pg_temp'];

            IF session_user = 'emergeos_failure_resumer' THEN
                IF expected_function =
                       '%1$I.agent_graph_complete_child_v10(jsonb)'
                         ::regprocedure THEN
                    mapped_resume_function :=
                       '%1$I.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'
                         ::regprocedure;
                ELSIF expected_function =
                       '%1$I.agent_graph_complete_parent_and_seal_v10(jsonb)'
                         ::regprocedure THEN
                    mapped_resume_function :=
                       '%1$I.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)'
                         ::regprocedure;
                END IF;
                IF caller_oid IS NULL
                   OR protected_owner_oid IS NULL
                   OR mapped_resume_function IS NULL
                   OR current_user::regrole::oid
                        IS DISTINCT FROM protected_owner_oid
                   OR pg_catalog.has_function_privilege(
                        session_user, expected_function, 'EXECUTE') THEN
                    RAISE EXCEPTION 'V12 nested terminal authority rejected'
                        USING ERRCODE = '42501';
                END IF;
                PERFORM %1$I.agent_graph_require_failure_resumer_v12(
                    mapped_resume_function);
                RETURN;
            END IF;

            SELECT
                pg_catalog.count(*) FILTER (
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND acl.grantee = caller_oid
                      AND NOT acl.is_grantable),
                pg_catalog.count(*) FILTER (
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND (
                        acl.grantee = 0
                        OR acl.grantee NOT IN (
                            protected_owner_oid, caller_oid)
                        OR (acl.grantee = caller_oid
                            AND acl.is_grantable)))
            INTO explicit_caller_grants, acl_violations
            FROM pg_catalog.aclexplode(
                COALESCE(
                    (SELECT procedure.proacl
                     FROM pg_catalog.pg_proc procedure
                     WHERE procedure.oid = expected_function),
                    pg_catalog.acldefault(
                        'f', protected_owner_oid))) acl;

            SELECT pg_catalog.count(*)
            INTO executable_surface
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = %2$L
              AND pg_catalog.has_function_privilege(
                    session_user, procedure.oid, 'EXECUTE');

            IF caller_oid IS NULL
               OR protected_owner_oid IS NULL
               OR session_user IS DISTINCT FROM
                    'emergeos_graph_executor'
               OR current_user::regrole::oid
                    IS DISTINCT FROM protected_owner_oid
               OR explicit_caller_grants <> 1
               OR acl_violations <> 0
               OR executable_surface <> 2
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.oid = caller_oid
                      AND (
                        NOT role.rolcanlogin
                        OR role.rolinherit
                        OR role.rolsuper
                        OR role.rolcreatedb
                        OR role.rolcreaterole
                        OR role.rolreplication
                        OR role.rolbypassrls))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.oid = protected_owner_oid
                      AND (
                        role.rolcanlogin
                        OR role.rolinherit
                        OR role.rolsuper
                        OR role.rolcreatedb
                        OR role.rolcreaterole
                        OR role.rolreplication
                        OR role.rolbypassrls))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_auth_members membership
                    WHERE membership.member IN (
                            caller_oid, protected_owner_oid)
                       OR membership.roleid IN (
                            caller_oid, protected_owner_oid))
               OR NOT pg_catalog.has_database_privilege(
                    session_user, current_database(), 'CONNECT')
               OR pg_catalog.has_database_privilege(
                    session_user, current_database(), 'CREATE')
               OR NOT pg_catalog.has_schema_privilege(
                    session_user, %2$L, 'USAGE')
               OR pg_catalog.has_schema_privilege(
                    session_user, %2$L, 'CREATE')
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = %2$L
                      AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
                      AND (
                        relation.relowner = caller_oid
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid,
                            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                        OR pg_catalog.has_any_column_privilege(
                            session_user, relation.oid,
                            'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
                RAISE EXCEPTION 'V10 executor topology is not exact'
                    USING ERRCODE = '42501';
            END IF;
        END;
        $function$
    $definition$, target_schema, target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_read_attributed_failure_resume_v12(
            checked_principal VARCHAR,
            checked_attempt CHAR,
            checked_manifest CHAR
        )
        RETURNS TABLE (
            manifest_hash CHAR(64),
            revision VARCHAR(2),
            session_intent_hash CHAR(64),
            session_expires_at TIMESTAMPTZ,
            cursor_sequence INTEGER,
            cursor_head_hash CHAR(64),
            provider_attribution_1_hash CHAR(64),
            provider_attribution_2_hash CHAR(64),
            request_hash CHAR(64),
            response_hash CHAR(64),
            model_resolved VARCHAR(512),
            failure_code VARCHAR(64),
            provenance_hash CHAR(64),
            state VARCHAR(32),
            state_version BIGINT,
            claimant_id VARCHAR(200),
            fence_token_hash CHAR(64),
            claim_expires_at TIMESTAMPTZ,
            child_claim_version BIGINT,
            child_claimant_id VARCHAR(200),
            child_fence_token_hash CHAR(64),
            child_claim_expires_at TIMESTAMPTZ,
            child_sequence INTEGER,
            child_head_hash CHAR(64),
            child_terminal_hash CHAR(64),
            parent_claim_version BIGINT,
            parent_sequence INTEGER,
            parent_head_hash CHAR(64),
            parent_terminal_hash CHAR(64),
            terminal_sequence INTEGER,
            terminal_head_hash CHAR(64),
            seal_hash CHAR(64),
            live_sequence INTEGER,
            live_head_hash CHAR(64)
        )
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        BEGIN
            PERFORM %1$I.agent_graph_require_failure_resumer_v12(
                '%1$I.agent_graph_read_attributed_failure_resume_v12(character varying,character,character)'
                  ::regprocedure);
            RETURN QUERY
            SELECT outcome.manifest_hash,
                   outcome.revision,
                   outcome.session_intent_hash,
                   outcome.session_expires_at,
                   outcome.cursor_sequence,
                   outcome.cursor_head_hash,
                   outcome.provider_attribution_1_hash,
                   outcome.provider_attribution_2_hash,
                   outcome.request_hash,
                   outcome.response_hash,
                   outcome.model_resolved,
                   outcome.failure_code,
                   outcome.provenance_hash,
                   COALESCE(resume.state, outcome.state),
                   COALESCE(resume.state_version, outcome.state_version),
                   CASE WHEN resume.principal_id IS NULL
                        THEN outcome.claimant_id ELSE resume.claimant_id END,
                   CASE WHEN resume.principal_id IS NULL
                        THEN outcome.fence_token_hash ELSE resume.fence_token_hash END,
                   CASE WHEN resume.principal_id IS NULL
                        THEN outcome.claim_expires_at ELSE resume.claim_expires_at END,
                   resume.child_claim_version,
                   resume.child_claimant_id,
                   resume.child_fence_token_hash,
                   resume.child_claim_expires_at,
                   resume.child_sequence,
                   resume.child_head_hash,
                   resume.child_terminal_hash,
                   resume.parent_claim_version,
                   resume.parent_sequence,
                   resume.parent_head_hash,
                   resume.parent_terminal_hash,
                   resume.terminal_sequence,
                   resume.terminal_head_hash,
                   resume.seal_hash,
                   head.last_sequence,
                   head.head_hash
            FROM %1$I.agent_graph_attributed_failure_outcomes outcome
            JOIN %1$I.agent_graph_attempt_heads head
              ON head.principal_id = outcome.principal_id
             AND head.attempt_id = outcome.attempt_id
            LEFT JOIN %1$I.agent_graph_attributed_failure_terminal_resumes resume
              ON resume.principal_id = outcome.principal_id
             AND resume.attempt_id = outcome.attempt_id
            WHERE outcome.principal_id = checked_principal
              AND outcome.attempt_id = checked_attempt
              AND outcome.manifest_hash = checked_manifest;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_read_attributed_failure_resume_v12('
            || 'VARCHAR, CHAR, CHAR) FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_claim_attributed_failure_resume_v12(
            checked_principal VARCHAR,
            checked_attempt CHAR,
            checked_manifest CHAR,
            checked_provenance CHAR,
            checked_state_version BIGINT,
            checked_claimant VARCHAR,
            checked_fence_token_hash CHAR,
            checked_lease_millis INTEGER
        )
        RETURNS BIGINT
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            outcome RECORD;
            resume RECORD;
            head RECORD;
            now_at TIMESTAMPTZ;
            next_expiry TIMESTAMPTZ;
            next_version BIGINT;
            affected INTEGER;
        BEGIN
            PERFORM %1$I.agent_graph_require_failure_resumer_v12(
                '%1$I.agent_graph_claim_attributed_failure_resume_v12(character varying,character,character,character,bigint,character varying,character,integer)'
                  ::regprocedure);
            IF checked_lease_millis IS NULL
               OR checked_lease_millis < 100
               OR checked_lease_millis > 30000
               OR checked_state_version IS NULL
               OR checked_state_version < 1
               OR checked_state_version > 9007199254740989
               OR checked_claimant IS NULL
               OR char_length(btrim(checked_claimant)) NOT BETWEEN 1 AND 200
               OR checked_fence_token_hash IS NULL
               OR checked_fence_token_hash !~ '^[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'V12 failure claim is invalid'
                    USING ERRCODE = '22023';
            END IF;
            SELECT durable.*
            INTO STRICT outcome
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            SELECT durable.*
            INTO resume
            FROM %1$I.agent_graph_attributed_failure_terminal_resumes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            SELECT durable.last_sequence, durable.head_hash,
                   durable.manifest_hash
            INTO STRICT head
            FROM %1$I.agent_graph_attempt_heads durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            now_at := pg_catalog.clock_timestamp();
            IF outcome.manifest_hash IS DISTINCT FROM checked_manifest
               OR outcome.provenance_hash IS DISTINCT FROM checked_provenance
               OR head.manifest_hash IS DISTINCT FROM checked_manifest
               OR outcome.session_expires_at <= now_at THEN
                RAISE EXCEPTION 'V12 failure claim provenance is stale'
                    USING ERRCODE = '55000';
            END IF;
            next_expiry := now_at + pg_catalog.make_interval(
                secs => checked_lease_millis::DOUBLE PRECISION / 1000.0);
            IF next_expiry > outcome.session_expires_at THEN
                RAISE EXCEPTION 'V12 failure claim exceeds session expiry'
                    USING ERRCODE = '55000';
            END IF;

            IF resume.principal_id IS NULL THEN
                IF outcome.state_version <> checked_state_version
                   OR head.last_sequence <> 14
                   OR head.head_hash IS DISTINCT FROM outcome.cursor_head_hash
                   OR NOT (
                        outcome.state = 'READY'
                        OR (outcome.state = 'CLAIMED'
                            AND outcome.claim_expires_at <= now_at)) THEN
                    RAISE EXCEPTION 'V12 child claim fence rejected'
                        USING ERRCODE = '55000';
                END IF;
                next_version := checked_state_version + 1;
                UPDATE %1$I.agent_graph_attributed_failure_outcomes durable
                SET state = 'CLAIMED',
                    state_version = next_version,
                    claimant_id = checked_claimant,
                    fence_token_hash = checked_fence_token_hash,
                    claim_expires_at = next_expiry,
                    claimed_at = now_at
                WHERE durable.principal_id = checked_principal
                  AND durable.attempt_id = checked_attempt
                  AND durable.state_version = checked_state_version;
            ELSE
                IF resume.manifest_hash IS DISTINCT FROM checked_manifest
                   OR resume.provenance_hash IS DISTINCT FROM checked_provenance
                   OR resume.state_version <> checked_state_version
                   OR head.last_sequence <> 15
                   OR head.head_hash IS DISTINCT FROM resume.child_head_hash
                   OR NOT (
                        resume.state = 'CHILD_CONSUMED'
                        OR (resume.state = 'PARENT_CLAIMED'
                            AND resume.claim_expires_at <= now_at)) THEN
                    RAISE EXCEPTION 'V12 parent claim fence rejected'
                        USING ERRCODE = '55000';
                END IF;
                next_version := checked_state_version + 1;
                UPDATE %1$I.agent_graph_attributed_failure_terminal_resumes durable
                SET state = 'PARENT_CLAIMED',
                    state_version = next_version,
                    parent_claim_version = next_version,
                    claimant_id = checked_claimant,
                    fence_token_hash = checked_fence_token_hash,
                    claim_expires_at = next_expiry,
                    claimed_at = now_at
                WHERE durable.principal_id = checked_principal
                  AND durable.attempt_id = checked_attempt
                  AND durable.state_version = checked_state_version;
            END IF;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V12 failure claim CAS rejected'
                    USING ERRCODE = '55000';
            END IF;
            RETURN next_version;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_claim_attributed_failure_resume_v12('
            || 'VARCHAR, CHAR, CHAR, CHAR, BIGINT, VARCHAR, CHAR, INTEGER) '
            || 'FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_complete_claimed_failure_child_v12(
            payload JSONB,
            checked_provenance CHAR,
            checked_state_version BIGINT,
            checked_claimant VARCHAR,
            checked_fence_token_hash CHAR
        )
        RETURNS JSONB
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            checked_principal TEXT := payload ->> 'principal_id';
            checked_attempt TEXT := payload ->> 'attempt_id';
            outcome RECORD;
            head RECORD;
            now_at TIMESTAMPTZ;
            terminal_result JSONB;
        BEGIN
            PERFORM %1$I.agent_graph_require_failure_resumer_v12(
                '%1$I.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'
                  ::regprocedure);
            IF payload IS NULL
               OR checked_provenance IS NULL
               OR checked_state_version IS NULL
               OR checked_state_version < 2
               OR checked_state_version > 9007199254740990
               OR checked_claimant IS NULL
               OR char_length(btrim(checked_claimant)) NOT BETWEEN 1 AND 200
               OR checked_fence_token_hash IS NULL
               OR checked_fence_token_hash !~ '^[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'V12 child terminal input is invalid'
                    USING ERRCODE = '22023';
            END IF;
            SELECT durable.*
            INTO STRICT outcome
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            SELECT durable.*
            INTO STRICT head
            FROM %1$I.agent_graph_attempt_heads durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            now_at := pg_catalog.clock_timestamp();
            IF outcome.provenance_hash IS DISTINCT FROM checked_provenance
               OR outcome.state IS DISTINCT FROM 'CLAIMED'
               OR outcome.state_version <> checked_state_version
               OR outcome.claimant_id IS DISTINCT FROM checked_claimant
               OR outcome.fence_token_hash
                    IS DISTINCT FROM checked_fence_token_hash
               OR outcome.claim_expires_at <= now_at
               OR outcome.session_expires_at <= now_at
               OR head.manifest_hash IS DISTINCT FROM outcome.manifest_hash
               OR head.last_sequence <> 14
               OR head.head_hash IS DISTINCT FROM outcome.cursor_head_hash
               OR payload #>> '{event,previous_head_hash}'
                    IS DISTINCT FROM outcome.cursor_head_hash
               OR payload #>> '{event,sequence}' IS DISTINCT FROM '15'
               OR payload #>> '{event,event_type}'
                    IS DISTINCT FROM 'CHILD_TERMINAL'
               OR payload #>> '{terminal_run,lifecycle_status}'
                    IS DISTINCT FROM 'FAILED'
               OR payload #>> '{terminal_run,failure_attribution}'
                    IS DISTINCT FROM outcome.failure_code
               OR payload #>> '{terminal_run,resolved_model}'
                    IS DISTINCT FROM outcome.model_resolved
               OR payload -> 'candidate' <> 'null'::jsonb
               OR payload -> 'worker_result' <> 'null'::jsonb
               OR payload #>> '{terminal_binding,role}'
                    IS DISTINCT FROM 'CHILD'
               OR payload #>> '{terminal_binding,event_sequence}'
                    IS DISTINCT FROM '15'
               OR payload #>> '{terminal_binding,terminal_hash}'
                    !~ '^[0-9a-f]{64}$'
               OR payload #>> '{event,current_head_hash}'
                    !~ '^[0-9a-f]{64}$'
               OR EXISTS (
                    SELECT 1
                    FROM %1$I.agent_graph_attributed_failure_terminal_resumes durable
                    WHERE durable.principal_id = checked_principal
                      AND durable.attempt_id = checked_attempt) THEN
                RAISE EXCEPTION 'V12 child terminal claim was fenced'
                    USING ERRCODE = '55000';
            END IF;

            INSERT INTO %1$I.agent_graph_attributed_failure_terminal_resumes (
                principal_id, attempt_id, manifest_hash, provenance_hash,
                state, state_version,
                child_claim_version, child_claimant_id,
                child_fence_token_hash, child_claim_expires_at,
                child_claimed_at, child_sequence, child_head_hash,
                child_terminal_hash, child_applied_at
            ) VALUES (
                checked_principal, checked_attempt, outcome.manifest_hash,
                outcome.provenance_hash, 'CHILD_CONSUMED',
                checked_state_version + 1,
                checked_state_version, checked_claimant,
                checked_fence_token_hash, outcome.claim_expires_at,
                outcome.claimed_at, 15,
                payload #>> '{event,current_head_hash}',
                payload #>> '{terminal_binding,terminal_hash}', now_at
            );
            terminal_result :=
                %1$I.agent_graph_complete_child_v10(payload);
            IF terminal_result ->> 'sequence' IS DISTINCT FROM '15'
               OR terminal_result ->> 'head_hash' IS DISTINCT FROM
                    payload #>> '{event,current_head_hash}' THEN
                RAISE EXCEPTION 'V12 child terminal read-back drifted'
                    USING ERRCODE = '55000';
            END IF;
            RETURN terminal_result || pg_catalog.jsonb_build_object(
                'resume_state', 'CHILD_CONSUMED',
                'resume_version', checked_state_version + 1);
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_complete_claimed_failure_child_v12('
            || 'JSONB, CHAR, BIGINT, VARCHAR, CHAR) FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_complete_claimed_failure_parent_and_seal_v12(
            payload JSONB,
            checked_provenance CHAR,
            checked_state_version BIGINT,
            checked_claimant VARCHAR,
            checked_fence_token_hash CHAR
        )
        RETURNS JSONB
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            checked_principal TEXT := payload ->> 'principal_id';
            checked_attempt TEXT := payload ->> 'attempt_id';
            outcome RECORD;
            resume RECORD;
            head RECORD;
            now_at TIMESTAMPTZ;
            terminal_result JSONB;
            affected INTEGER;
        BEGIN
            PERFORM %1$I.agent_graph_require_failure_resumer_v12(
                '%1$I.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)'
                  ::regprocedure);
            IF payload IS NULL
               OR checked_provenance IS NULL
               OR checked_state_version IS NULL
               OR checked_state_version < 4
               OR checked_state_version > 9007199254740990
               OR checked_claimant IS NULL
               OR char_length(btrim(checked_claimant)) NOT BETWEEN 1 AND 200
               OR checked_fence_token_hash IS NULL
               OR checked_fence_token_hash !~ '^[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'V12 parent terminal input is invalid'
                    USING ERRCODE = '22023';
            END IF;
            SELECT durable.*
            INTO STRICT outcome
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            SELECT durable.*
            INTO STRICT resume
            FROM %1$I.agent_graph_attributed_failure_terminal_resumes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            SELECT durable.*
            INTO STRICT head
            FROM %1$I.agent_graph_attempt_heads durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            now_at := pg_catalog.clock_timestamp();
            IF outcome.provenance_hash IS DISTINCT FROM checked_provenance
               OR resume.provenance_hash IS DISTINCT FROM checked_provenance
               OR resume.state IS DISTINCT FROM 'PARENT_CLAIMED'
               OR resume.state_version <> checked_state_version
               OR resume.parent_claim_version <> checked_state_version
               OR resume.claimant_id IS DISTINCT FROM checked_claimant
               OR resume.fence_token_hash
                    IS DISTINCT FROM checked_fence_token_hash
               OR resume.claim_expires_at <= now_at
               OR outcome.session_expires_at <= now_at
               OR head.manifest_hash IS DISTINCT FROM outcome.manifest_hash
               OR head.last_sequence <> 15
               OR head.head_hash IS DISTINCT FROM resume.child_head_hash
               OR payload #>> '{parent_event,previous_head_hash}'
                    IS DISTINCT FROM resume.child_head_hash
               OR payload #>> '{parent_event,sequence}' IS DISTINCT FROM '16'
               OR payload #>> '{seal_event,sequence}' IS DISTINCT FROM '17'
               OR payload #>> '{terminal_run,lifecycle_status}'
                    IS DISTINCT FROM 'FAILED'
               OR payload #>> '{terminal_binding,role}'
                    IS DISTINCT FROM 'PARENT'
               OR payload #>> '{terminal_binding,event_sequence}'
                    IS DISTINCT FROM '16'
               OR payload #>> '{terminal_binding,terminal_hash}'
                    !~ '^[0-9a-f]{64}$'
               OR payload #>> '{parent_event,current_head_hash}'
                    !~ '^[0-9a-f]{64}$'
               OR payload #>> '{seal_event,current_head_hash}'
                    !~ '^[0-9a-f]{64}$'
               OR payload #>> '{seal,seal_hash}' !~ '^[0-9a-f]{64}$'
               OR payload #>> '{seal,child_terminal_hash}'
                    IS DISTINCT FROM resume.child_terminal_hash
               OR payload #>> '{seal,parent_terminal_hash}'
                    IS DISTINCT FROM
                       payload #>> '{terminal_binding,terminal_hash}' THEN
                RAISE EXCEPTION 'V12 parent terminal claim was fenced'
                    USING ERRCODE = '55000';
            END IF;

            UPDATE %1$I.agent_graph_attributed_failure_terminal_resumes durable
            SET state = 'TERMINAL_CONSUMED',
                state_version = checked_state_version + 1,
                parent_sequence = 16,
                parent_head_hash =
                    payload #>> '{parent_event,current_head_hash}',
                parent_terminal_hash =
                    payload #>> '{terminal_binding,terminal_hash}',
                terminal_sequence = 17,
                terminal_head_hash =
                    payload #>> '{seal_event,current_head_hash}',
                seal_hash = payload #>> '{seal,seal_hash}',
                terminal_applied_at = now_at
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
              AND durable.state_version = checked_state_version;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V12 parent terminal CAS rejected'
                    USING ERRCODE = '55000';
            END IF;
            terminal_result :=
                %1$I.agent_graph_complete_parent_and_seal_v10(payload);
            IF terminal_result ->> 'sequence' IS DISTINCT FROM '17'
               OR terminal_result ->> 'head_hash' IS DISTINCT FROM
                    payload #>> '{seal_event,current_head_hash}' THEN
                RAISE EXCEPTION 'V12 parent terminal read-back drifted'
                    USING ERRCODE = '55000';
            END IF;
            RETURN terminal_result || pg_catalog.jsonb_build_object(
                'resume_state', 'TERMINAL_CONSUMED',
                'resume_version', checked_state_version + 1);
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'JSONB, CHAR, BIGINT, VARCHAR, CHAR) FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_assert_failure_terminal_resume_v12()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            checked_principal VARCHAR(200);
            checked_attempt CHAR(64);
            outcome RECORD;
            resume RECORD;
            head RECORD;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                checked_principal := OLD.principal_id;
                checked_attempt := OLD.attempt_id;
            ELSE
                checked_principal := NEW.principal_id;
                checked_attempt := NEW.attempt_id;
            END IF;
            IF TG_TABLE_NAME =
                 'agent_graph_attributed_failure_terminal_resumes' THEN
                IF TG_OP = 'DELETE' THEN
                    RAISE EXCEPTION
                        'V12 terminal resume receipt cannot be deleted'
                        USING ERRCODE = '55000';
                ELSIF TG_OP = 'INSERT' THEN
                    IF NEW.state <> 'CHILD_CONSUMED'
                       OR NEW.state_version <>
                            NEW.child_claim_version + 1 THEN
                        RAISE EXCEPTION
                            'V12 terminal resume receipt must begin consumed'
                            USING ERRCODE = '55000';
                    END IF;
                ELSE
                    IF NEW.principal_id IS DISTINCT FROM OLD.principal_id
                       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
                       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
                       OR NEW.provenance_hash
                            IS DISTINCT FROM OLD.provenance_hash
                       OR NEW.child_claim_version
                            IS DISTINCT FROM OLD.child_claim_version
                       OR NEW.child_claimant_id
                            IS DISTINCT FROM OLD.child_claimant_id
                       OR NEW.child_fence_token_hash
                            IS DISTINCT FROM OLD.child_fence_token_hash
                       OR NEW.child_claim_expires_at
                            IS DISTINCT FROM OLD.child_claim_expires_at
                       OR NEW.child_claimed_at
                            IS DISTINCT FROM OLD.child_claimed_at
                       OR NEW.child_sequence
                            IS DISTINCT FROM OLD.child_sequence
                       OR NEW.child_head_hash
                            IS DISTINCT FROM OLD.child_head_hash
                       OR NEW.child_terminal_hash
                            IS DISTINCT FROM OLD.child_terminal_hash
                       OR NEW.child_applied_at
                            IS DISTINCT FROM OLD.child_applied_at
                       OR NEW.state_version <> OLD.state_version + 1
                       OR (
                            OLD.state = 'PARENT_CLAIMED'
                            AND NEW.state = 'TERMINAL_CONSUMED'
                            AND (
                                NEW.parent_claim_version
                                    IS DISTINCT FROM
                                        OLD.parent_claim_version
                                OR NEW.claimant_id
                                    IS DISTINCT FROM
                                        OLD.claimant_id
                                OR NEW.fence_token_hash
                                    IS DISTINCT FROM
                                        OLD.fence_token_hash
                                OR NEW.claim_expires_at
                                    IS DISTINCT FROM
                                        OLD.claim_expires_at
                                OR NEW.claimed_at
                                    IS DISTINCT FROM
                                        OLD.claimed_at))
                       OR NOT (
                            (OLD.state = 'CHILD_CONSUMED'
                             AND NEW.state = 'PARENT_CLAIMED')
                            OR
                            (OLD.state = 'PARENT_CLAIMED'
                             AND NEW.state = 'PARENT_CLAIMED')
                            OR
                            (OLD.state = 'PARENT_CLAIMED'
                             AND NEW.state = 'TERMINAL_CONSUMED')) THEN
                        RAISE EXCEPTION
                            'V12 terminal resume transition is invalid'
                            USING ERRCODE = '55000';
                    END IF;
                END IF;
            END IF;
            SELECT durable.*
            INTO STRICT head
            FROM %1$I.agent_graph_attempt_heads durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt;
            SELECT durable.*
            INTO outcome
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt;
            IF outcome.principal_id IS NULL THEN
                IF head.last_sequence IN (15, 17)
                   AND EXISTS (
                        SELECT 1
                        FROM %1$I.agent_graph_attempt_terminal_bindings terminal
                        JOIN %1$I.agent_runs run
                          ON run.principal_id = terminal.principal_id
                         AND run.run_id = terminal.run_id
                         AND run.task_id = terminal.task_id
                        WHERE terminal.principal_id = checked_principal
                          AND terminal.attempt_id = checked_attempt
                          AND terminal.role = 'CHILD'
                          AND terminal.status = 'FAILED'
                          AND run.failure_attribution IN (
                            'MODEL_RESPONSE_MALFORMED',
                            'MODEL_USAGE_LIMIT_EXCEEDED')) THEN
                    RAISE EXCEPTION
                        'V12 durable failure outcome is missing'
                        USING ERRCODE = '55000';
                END IF;
                RETURN NULL;
            END IF;
            SELECT durable.*
            INTO resume
            FROM %1$I.agent_graph_attributed_failure_terminal_resumes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt;

            IF head.last_sequence IN (15, 17)
               AND (resume.principal_id IS NULL
                    OR outcome.state <> 'CLAIMED'
                    OR resume.child_claim_version <> outcome.state_version
                    OR resume.child_claimant_id
                         IS DISTINCT FROM outcome.claimant_id
                    OR resume.child_fence_token_hash
                         IS DISTINCT FROM outcome.fence_token_hash
                    OR resume.child_claim_expires_at
                         IS DISTINCT FROM outcome.claim_expires_at
                    OR resume.child_claimed_at
                         IS DISTINCT FROM outcome.claimed_at) THEN
                RAISE EXCEPTION
                    'V12 durable failure claim receipt drifted'
                    USING ERRCODE = '55000';
            END IF;

            IF head.last_sequence IN (15, 17)
               AND NOT EXISTS (
                    SELECT 1
                    FROM %1$I.agent_graph_attempt_terminal_bindings terminal
                    JOIN %1$I.agent_runs run
                      ON run.principal_id = terminal.principal_id
                     AND run.run_id = terminal.run_id
                     AND run.task_id = terminal.task_id
                    WHERE terminal.principal_id = checked_principal
                      AND terminal.attempt_id = checked_attempt
                      AND terminal.role = 'CHILD'
                      AND terminal.status = 'FAILED'
                      AND run.failure_attribution = outcome.failure_code
                      AND run.resolved_model = outcome.model_resolved) THEN
                RAISE EXCEPTION
                    'V12 durable failure terminal truth drifted'
                    USING ERRCODE = '55000';
            END IF;

            IF head.last_sequence = 14 THEN
                IF resume.principal_id IS NOT NULL THEN
                    RAISE EXCEPTION 'V12 failure receipt leads graph head'
                        USING ERRCODE = '55000';
                END IF;
            ELSIF head.last_sequence = 15 THEN
                IF resume.principal_id IS NULL
                   OR resume.state NOT IN (
                        'CHILD_CONSUMED', 'PARENT_CLAIMED')
                   OR resume.manifest_hash IS DISTINCT FROM outcome.manifest_hash
                   OR resume.provenance_hash
                        IS DISTINCT FROM outcome.provenance_hash
                   OR resume.child_sequence <> 15
                   OR resume.child_head_hash IS DISTINCT FROM head.head_hash
                   OR NOT EXISTS (
                        SELECT 1
                        FROM %1$I.agent_graph_attempt_terminal_bindings terminal
                        JOIN %1$I.agent_runs run
                          ON run.principal_id = terminal.principal_id
                         AND run.run_id = terminal.run_id
                         AND run.task_id = terminal.task_id
                        WHERE terminal.principal_id = checked_principal
                          AND terminal.attempt_id = checked_attempt
                          AND terminal.role = 'CHILD'
                          AND terminal.status = 'FAILED'
                          AND terminal.terminal_hash =
                                resume.child_terminal_hash
                          AND run.failure_attribution = outcome.failure_code) THEN
                    RAISE EXCEPTION 'V12 child failure receipt is incomplete'
                        USING ERRCODE = '55000';
                END IF;
            ELSIF head.last_sequence = 17 THEN
                IF resume.principal_id IS NULL
                   OR resume.state <> 'TERMINAL_CONSUMED'
                   OR resume.manifest_hash IS DISTINCT FROM outcome.manifest_hash
                   OR resume.provenance_hash
                        IS DISTINCT FROM outcome.provenance_hash
                   OR resume.terminal_sequence <> 17
                   OR resume.terminal_head_hash IS DISTINCT FROM head.head_hash
                   OR NOT EXISTS (
                        SELECT 1
                        FROM %1$I.agent_graph_attempt_seals seal
                        WHERE seal.principal_id = checked_principal
                          AND seal.attempt_id = checked_attempt
                          AND seal.final_sequence = 17
                          AND seal.final_head_hash = resume.terminal_head_hash
                          AND seal.seal_hash = resume.seal_hash
                          AND seal.child_terminal_hash =
                                resume.child_terminal_hash
                          AND seal.parent_terminal_hash =
                                resume.parent_terminal_hash) THEN
                    RAISE EXCEPTION 'V12 terminal failure receipt is incomplete'
                        USING ERRCODE = '55000';
                END IF;
            ELSIF head.last_sequence > 14 THEN
                RAISE EXCEPTION 'V12 failure graph head is unsupported'
                    USING ERRCODE = '55000';
            END IF;
            RETURN NULL;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_assert_failure_terminal_resume_v12() '
            || 'FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format(
        'CREATE CONSTRAINT TRIGGER '
            || 'agent_graph_heads_failure_resume_assert_v12 '
            || 'AFTER INSERT OR UPDATE OR DELETE ON %1$I.agent_graph_attempt_heads '
            || 'DEFERRABLE INITIALLY DEFERRED FOR EACH ROW '
            || 'EXECUTE FUNCTION '
            || '%1$I.agent_graph_assert_failure_terminal_resume_v12()',
        target_schema);
    EXECUTE pg_catalog.format(
        'CREATE CONSTRAINT TRIGGER '
            || 'agent_graph_failure_resume_assert_v12 '
            || 'AFTER INSERT OR UPDATE OR DELETE ON '
            || '%1$I.agent_graph_attributed_failure_terminal_resumes '
            || 'DEFERRABLE INITIALLY DEFERRED FOR EACH ROW '
            || 'EXECUTE FUNCTION '
            || '%1$I.agent_graph_assert_failure_terminal_resume_v12()',
        target_schema);

    -- A V11 database that already let a durable attributed failure cross
    -- TX-B/TX-C has no trustworthy consumption receipt to backfill. Refuse
    -- that upgrade instead of blessing a historical raw-V10 bypass.
    IF EXISTS (
        SELECT 1
        FROM agent_graph_attributed_failure_outcomes outcome
        WHERE NOT EXISTS (
            SELECT 1
            FROM agent_graph_attempt_heads head
            WHERE head.principal_id = outcome.principal_id
              AND head.attempt_id = outcome.attempt_id
              AND head.last_sequence = 14
              AND head.head_hash = outcome.cursor_head_hash)
    ) OR EXISTS (
        SELECT 1
        FROM agent_graph_attempt_heads head
        JOIN agent_graph_attempt_terminal_bindings terminal
          ON terminal.principal_id = head.principal_id
         AND terminal.attempt_id = head.attempt_id
         AND terminal.role = 'CHILD'
         AND terminal.status = 'FAILED'
        JOIN agent_runs run
          ON run.principal_id = terminal.principal_id
         AND run.run_id = terminal.run_id
         AND run.task_id = terminal.task_id
        WHERE head.last_sequence > 14
          AND run.failure_attribution IN (
              'MODEL_RESPONSE_MALFORMED',
              'MODEL_USAGE_LIMIT_EXCEEDED')
          AND NOT EXISTS (
              SELECT 1
              FROM agent_graph_attributed_failure_outcomes outcome
              WHERE outcome.principal_id = head.principal_id
                AND outcome.attempt_id = head.attempt_id)
    ) THEN
        RAISE EXCEPTION
            'V12 cannot attest an already-consumed V11 failure'
            USING ERRCODE = '55000';
    END IF;
END;
$migration$;
