-- V9 removes caller-minted terminal permits. Runtime role provisioning is
-- deliberately external to Flyway; this migration only defines schema-local
-- semantic authority and its invariant boundary.

CREATE TABLE agent_graph_provider_session_intents (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    revision VARCHAR(2) NOT NULL,
    cursor_sequence INTEGER NOT NULL,
    cursor_head_hash CHAR(64) NOT NULL,
    request_ordinal INTEGER NOT NULL,
    request_hash CHAR(64) NOT NULL,
    model_requested VARCHAR(200) NOT NULL,
    intent_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_id, attempt_id),
    UNIQUE (principal_id, intent_hash),
    FOREIGN KEY (principal_id, attempt_id)
        REFERENCES agent_graph_attempts(principal_id, attempt_id),
    FOREIGN KEY (
        principal_id,
        attempt_id,
        cursor_sequence,
        cursor_head_hash
    ) REFERENCES agent_graph_attempt_events (
        principal_id,
        attempt_id,
        sequence,
        current_head_hash
    ),
    CHECK (revision IN ('r1', 'r2', 'r3')),
    CHECK (cursor_sequence = 7),
    CHECK (request_ordinal = 1),
    CHECK (expires_at > created_at)
);

DO $migration$
DECLARE
    target_schema NAME := current_schema();
    previous_function RECORD;
BEGIN
    -- Existing trigger/invariant functions contain unqualified references.
    -- Pin them to catalog + this owner-only schema before the V9 semantic
    -- functions force deferred checks while still running as their owner.
    FOR previous_function IN
        SELECT procedure.oid::regprocedure AS signature
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER FUNCTION %s SET search_path = pg_catalog, %I, pg_temp',
            previous_function.signature,
            target_schema
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON FUNCTION %s FROM PUBLIC',
            previous_function.signature
        );
    END LOOP;

    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION %I.agent_graph_authorize_terminal_v8('
            || 'VARCHAR, CHAR, VARCHAR, VARCHAR) FROM PUBLIC',
        target_schema
    );
    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_run_selector_guard_v9()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            protected_owner NAME;
            parent_function_owner NAME;
            relation_owner NAME;
        BEGIN
            SELECT role.rolname
            INTO protected_owner
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            JOIN pg_catalog.pg_roles role
              ON role.oid = procedure.proowner
            WHERE namespace.nspname = %2$L
              AND procedure.proname
                    = 'agent_graph_complete_child_v9'
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'payload jsonb';
            SELECT role.rolname
            INTO parent_function_owner
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            JOIN pg_catalog.pg_roles role
              ON role.oid = procedure.proowner
            WHERE namespace.nspname = %2$L
              AND procedure.proname
                    = 'agent_graph_complete_parent_and_seal_v9'
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'payload jsonb';
            SELECT role.rolname
            INTO relation_owner
            FROM pg_catalog.pg_class relation
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_roles role
              ON role.oid = relation.relowner
            WHERE namespace.nspname = %2$L
              AND relation.relname = 'agent_runs';

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
                IF NOT (
                    protected_owner = relation_owner
                    AND current_user = relation_owner
                    AND session_user = relation_owner
                ) THEN
                    PERFORM %1$I.agent_graph_require_executor_v9(
                        CASE OLD.graph_role
                            WHEN 'CHILD' THEN
                                '%1$I.agent_graph_complete_child_v9(jsonb)'
                                    ::regprocedure
                            WHEN 'PARENT' THEN
                                '%1$I.agent_graph_complete_parent_and_seal_v9(jsonb)'
                                    ::regprocedure
                            ELSE NULL
                        END
                    );
                END IF;
                IF protected_owner
                        IS DISTINCT FROM parent_function_owner
                   OR protected_owner IS NULL
                   OR relation_owner IS NULL
                   OR current_user IS DISTINCT FROM protected_owner
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
                        'graph-bound Run terminal mutation lacks V9 semantic authority'
                        USING ERRCODE = '55000';
                END IF;
                RETURN NEW;
            END IF;

            PERFORM pg_catalog.pg_advisory_xact_lock(
                pg_catalog.hashtextextended(
                    NEW.principal_id || E'\\x1f' || NEW.run_id,
                    0
                )
            );
            IF NEW.graph_attempt_id IS NULL THEN
                IF EXISTS (
                    SELECT 1
                    FROM %1$I.agent_graph_attempt_run_bindings binding
                    WHERE binding.principal_id = NEW.principal_id
                      AND binding.run_id = NEW.run_id
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
                    FROM %1$I.agent_graph_attempt_run_bindings binding
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
        $function$
    $definition$, target_schema, target_schema);

    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION %I.agent_graph_run_selector_guard_v9() '
            || 'FROM PUBLIC',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'CREATE TRIGGER agent_graph_run_selector_guard_v9 '
            || 'BEFORE INSERT OR UPDATE OR DELETE ON %I.agent_runs '
            || 'FOR EACH ROW EXECUTE FUNCTION '
            || '%I.agent_graph_run_selector_guard_v9()',
        target_schema,
        target_schema
    );

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_require_row_shape_v9(
            supplied JSONB,
            expected_relation REGCLASS,
            supplied_is_array BOOLEAN
        )
        RETURNS VOID
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            supplied_row JSONB;
        BEGIN
            IF supplied = 'null'::jsonb THEN
                RETURN;
            END IF;
            IF supplied_is_array THEN
                IF pg_catalog.jsonb_typeof(supplied) <> 'array' THEN
                    RAISE EXCEPTION 'V9 row set must be an array'
                        USING ERRCODE = '22023';
                END IF;
                FOR supplied_row IN
                    SELECT value
                    FROM pg_catalog.jsonb_array_elements(supplied)
                LOOP
                    IF pg_catalog.jsonb_typeof(supplied_row)
                            <> 'object'
                       OR EXISTS (
                            SELECT key
                            FROM pg_catalog.jsonb_object_keys(
                                supplied_row) key
                            EXCEPT
                            SELECT attribute.attname
                            FROM pg_catalog.pg_attribute attribute
                            WHERE attribute.attrelid
                                    = expected_relation
                              AND attribute.attnum > 0
                              AND NOT attribute.attisdropped
                       )
                       OR EXISTS (
                            SELECT attribute.attname
                            FROM pg_catalog.pg_attribute attribute
                            WHERE attribute.attrelid
                                    = expected_relation
                              AND attribute.attnum > 0
                              AND NOT attribute.attisdropped
                            EXCEPT
                            SELECT key
                            FROM pg_catalog.jsonb_object_keys(
                                supplied_row) key
                       ) THEN
                        RAISE EXCEPTION 'invalid V9 row shape'
                            USING ERRCODE = '22023';
                    END IF;
                END LOOP;
            ELSIF pg_catalog.jsonb_typeof(supplied) <> 'object'
               OR EXISTS (
                    SELECT key
                    FROM pg_catalog.jsonb_object_keys(supplied) key
                    EXCEPT
                    SELECT attribute.attname
                    FROM pg_catalog.pg_attribute attribute
                    WHERE attribute.attrelid = expected_relation
                      AND attribute.attnum > 0
                      AND NOT attribute.attisdropped
               )
               OR EXISTS (
                    SELECT attribute.attname
                    FROM pg_catalog.pg_attribute attribute
                    WHERE attribute.attrelid = expected_relation
                      AND attribute.attnum > 0
                      AND NOT attribute.attisdropped
                    EXCEPT
                    SELECT key
                    FROM pg_catalog.jsonb_object_keys(supplied) key
               ) THEN
                RAISE EXCEPTION 'invalid V9 row shape'
                    USING ERRCODE = '22023';
            END IF;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_require_row_shape_v9('
            || 'JSONB, REGCLASS, BOOLEAN) FROM PUBLIC',
        target_schema
    );

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_require_executor_v9(
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
            relation_owner_count BIGINT;
            relation_owner_safe BOOLEAN;
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
                    'agent_graph_complete_child_v9',
                    'agent_graph_complete_parent_and_seal_v9')
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'payload jsonb'
              AND procedure.prosecdef
              AND procedure.proconfig
                    = ARRAY['search_path=pg_catalog, pg_temp'];

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

            SELECT pg_catalog.count(DISTINCT relation.relowner),
                   pg_catalog.bool_and(
                       NOT owner.rolcanlogin
                       AND NOT owner.rolinherit
                       AND NOT owner.rolsuper
                       AND NOT owner.rolcreatedb
                       AND NOT owner.rolcreaterole
                       AND NOT owner.rolreplication
                       AND NOT owner.rolbypassrls
                       AND owner.oid <> caller_oid
                       AND owner.oid <> protected_owner_oid
                       AND NOT EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_auth_members membership
                            WHERE membership.member = owner.oid
                               OR membership.roleid = owner.oid))
            INTO relation_owner_count, relation_owner_safe
            FROM pg_catalog.pg_class relation
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_roles owner
              ON owner.oid = relation.relowner
            WHERE namespace.nspname = %2$L
              AND relation.relkind IN ('r', 'p', 'S');

            IF caller_oid IS NULL
               OR protected_owner_oid IS NULL
               OR current_user::regrole::oid
                    IS DISTINCT FROM protected_owner_oid
               OR explicit_caller_grants <> 1
               OR acl_violations <> 0
               OR executable_surface <> 2
               OR relation_owner_count <> 1
               OR NOT relation_owner_safe
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.oid IN (caller_oid, protected_owner_oid)
                      AND (
                        (role.oid = caller_oid
                         AND (NOT role.rolcanlogin OR role.rolinherit))
                        OR (role.oid = protected_owner_oid
                            AND (role.rolcanlogin OR role.rolinherit))
                        OR role.rolsuper
                        OR role.rolcreatedb
                        OR role.rolcreaterole
                        OR role.rolreplication
                        OR role.rolbypassrls))
               OR (SELECT pg_catalog.count(*)
                   FROM pg_catalog.pg_roles role
                   WHERE role.oid IN (
                        caller_oid, protected_owner_oid)) <> 2
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
               OR pg_catalog.has_database_privilege(
                    session_user, current_database(),
                    'CONNECT WITH GRANT OPTION')
               OR NOT pg_catalog.has_schema_privilege(
                    session_user, %2$L, 'USAGE')
               OR pg_catalog.has_schema_privilege(
                    session_user, %2$L, 'CREATE')
               OR pg_catalog.has_schema_privilege(
                    session_user, %2$L,
                    'USAGE WITH GRANT OPTION')
               OR (SELECT pg_catalog.count(*)
                   FROM pg_catalog.pg_trigger trigger
                   JOIN pg_catalog.pg_class relation
                     ON relation.oid = trigger.tgrelid
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = relation.relnamespace
                   JOIN pg_catalog.pg_proc guard
                     ON guard.oid = trigger.tgfoid
                   WHERE namespace.nspname = %2$L
                     AND relation.relname = 'agent_runs'
                     AND trigger.tgname
                           = 'agent_graph_run_selector_guard_v9'
                     AND NOT trigger.tgisinternal
                     AND trigger.tgenabled = 'O'
                     AND trigger.tgtype = 31
                     AND trigger.tgqual IS NULL
                     AND trigger.tgnargs = 0
                     AND pg_catalog.octet_length(trigger.tgargs) = 0
                     AND trigger.tgattr = ''::pg_catalog.int2vector
                     AND guard.proname
                           = 'agent_graph_run_selector_guard_v9'
                     AND NOT guard.prosecdef
                     AND guard.proconfig
                           = ARRAY['search_path=pg_catalog, pg_temp'])
                    <> 1
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = %2$L
                      AND relation.relkind IN ('r', 'p', 'v', 'm')
                      AND (
                        relation.relowner = caller_oid
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'SELECT')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'INSERT')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'UPDATE')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'DELETE')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'TRUNCATE')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'REFERENCES')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'TRIGGER')
                        OR pg_catalog.has_table_privilege(
                            session_user, relation.oid, 'MAINTAIN')
                        OR pg_catalog.has_any_column_privilege(
                            session_user, relation.oid, 'SELECT')
                        OR pg_catalog.has_any_column_privilege(
                            session_user, relation.oid, 'INSERT')
                        OR pg_catalog.has_any_column_privilege(
                            session_user, relation.oid, 'UPDATE')
                        OR pg_catalog.has_any_column_privilege(
                            session_user, relation.oid, 'REFERENCES'))
               ) THEN
                RAISE EXCEPTION
                    'V9 executor topology is not exact'
                    USING ERRCODE = '42501';
            END IF;
        END;
        $function$
    $definition$, target_schema, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_require_executor_v9(REGPROCEDURE) '
            || 'FROM PUBLIC',
        target_schema
    );

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_complete_child_v9(payload JSONB)
        RETURNS JSONB
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            checked_principal TEXT := payload ->> 'principal_id';
            checked_attempt TEXT := payload ->> 'attempt_id';
            affected INTEGER;
            locked_sequence INTEGER;
            locked_phase TEXT;
        BEGIN
            PERFORM %1$I.agent_graph_require_executor_v9(
                '%1$I.agent_graph_complete_child_v9(jsonb)'
                    ::regprocedure);
            IF pg_catalog.jsonb_typeof(payload) <> 'object'
               OR payload ->> 'protocol'
                    <> 'emergeos.graph-terminal.v9'
               OR pg_catalog.pg_column_size(payload) > 1048576
               OR NOT payload ?& ARRAY[
                    'protocol', 'principal_id', 'attempt_id',
                    'candidate', 'worker_result', 'trace_events',
                    'resource_bindings', 'terminal_run',
                    'terminal_binding', 'event'
               ]
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_object_keys(payload) key
                    WHERE key <> ALL (ARRAY[
                        'protocol', 'principal_id', 'attempt_id',
                        'candidate', 'worker_result', 'trace_events',
                        'resource_bindings', 'terminal_run',
                        'terminal_binding', 'event'
                    ])
               )
               OR pg_catalog.jsonb_typeof(payload -> 'trace_events')
                    <> 'array'
               OR pg_catalog.jsonb_typeof(
                    payload -> 'resource_bindings') <> 'array' THEN
                RAISE EXCEPTION 'invalid V9 child terminal payload'
                    USING ERRCODE = '22023';
            END IF;

            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'candidate',
                '%1$I.agent_graph_attempt_candidates'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'worker_result',
                '%1$I.agent_worker_results'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'trace_events',
                '%1$I.agent_trace_events'::regclass,
                true);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'resource_bindings',
                '%1$I.agent_run_resource_bindings'::regclass,
                true);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'terminal_run',
                '%1$I.agent_runs'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'terminal_binding',
                '%1$I.agent_graph_attempt_terminal_bindings'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                (payload -> 'event')
                    || pg_catalog.jsonb_build_object(
                        'committed_at',
                        pg_catalog.transaction_timestamp()),
                '%1$I.agent_graph_attempt_events'::regclass,
                false);
            IF payload #>> '{terminal_run,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{terminal_run,graph_attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{terminal_run,graph_role}'
                    IS DISTINCT FROM 'CHILD'
               OR (payload -> 'candidate' <> 'null'::jsonb AND (
                    payload #>> '{candidate,principal_id}'
                        IS DISTINCT FROM checked_principal
                    OR payload #>> '{candidate,attempt_id}'
                        IS DISTINCT FROM checked_attempt
                    OR payload #>> '{candidate,child_run_id}'
                        IS DISTINCT FROM
                            payload #>> '{terminal_run,run_id}'
               ))
               OR (payload -> 'worker_result' <> 'null'::jsonb AND (
                    payload #>> '{worker_result,principal_id}'
                        IS DISTINCT FROM checked_principal
                    OR payload #>> '{worker_result,child_run_id}'
                        IS DISTINCT FROM
                            payload #>> '{terminal_run,run_id}'
               ))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_array_elements(
                        payload -> 'trace_events') row_value
                    WHERE row_value ->> 'principal_id'
                            IS DISTINCT FROM checked_principal
                       OR row_value ->> 'run_id'
                            IS DISTINCT FROM
                                payload #>> '{terminal_run,run_id}'
               )
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_array_elements(
                        payload -> 'resource_bindings') row_value
                    WHERE row_value ->> 'principal_id'
                            IS DISTINCT FROM checked_principal
                       OR row_value ->> 'run_id'
                            IS DISTINCT FROM
                                payload #>> '{terminal_run,run_id}'
               )
               OR payload #>> '{terminal_binding,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{terminal_binding,attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{terminal_binding,role}'
                    IS DISTINCT FROM 'CHILD'
               OR payload #>> '{terminal_binding,run_id}'
                    IS DISTINCT FROM
                        payload #>> '{terminal_run,run_id}'
               OR payload #>> '{event,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{event,attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{event,role}'
                    IS DISTINCT FROM 'CHILD'
               OR payload #>> '{event,run_id}'
                    IS DISTINCT FROM
                        payload #>> '{terminal_run,run_id}' THEN
                RAISE EXCEPTION 'V9 child payload escapes TX-B aggregate'
                    USING ERRCODE = '22023';
            END IF;

            SELECT head.last_sequence, head.phase
            INTO locked_sequence, locked_phase
            FROM %1$I.agent_graph_attempt_heads head
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
            FOR UPDATE;
            IF locked_sequence IS DISTINCT FROM 14
               OR locked_phase IS DISTINCT FROM 'PROVIDER_ATTRIBUTED'
               OR NOT EXISTS (
                    SELECT 1
                    FROM %1$I.agent_runs run
                    WHERE run.principal_id = checked_principal
                      AND run.graph_attempt_id = checked_attempt
                      AND run.graph_role = 'CHILD'
                      AND run.lifecycle_status = 'RUNNING'
                    FOR UPDATE
               ) THEN
                RAISE EXCEPTION
                    'V9 child completion does not match canonical TX-B head'
                    USING ERRCODE = '55000';
            END IF;

            IF payload -> 'candidate' <> 'null'::jsonb THEN
                INSERT INTO %1$I.agent_graph_attempt_candidates
                SELECT supplied.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_graph_attempt_candidates,
                    payload -> 'candidate'
                ) supplied;
            END IF;
            IF payload -> 'worker_result' <> 'null'::jsonb THEN
                INSERT INTO %1$I.agent_worker_results
                SELECT supplied.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_worker_results,
                    payload -> 'worker_result'
                ) supplied;
            END IF;
            INSERT INTO %1$I.agent_trace_events
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_recordset(
                NULL::%1$I.agent_trace_events,
                payload -> 'trace_events'
            ) supplied;
            INSERT INTO %1$I.agent_run_resource_bindings
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_recordset(
                NULL::%1$I.agent_run_resource_bindings,
                payload -> 'resource_bindings'
            ) supplied;

            PERFORM %1$I.agent_graph_authorize_terminal_v8(
                checked_principal,
                checked_attempt::CHAR(64),
                'CHILD',
                payload #>> '{terminal_run,run_id}'
            );

            WITH supplied AS (
                SELECT replacement.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_runs,
                    payload -> 'terminal_run'
                ) replacement
            )
            UPDATE %1$I.agent_runs stored
            SET lifecycle_status = supplied.lifecycle_status,
                result_envelope = supplied.result_envelope,
                bundle = supplied.bundle,
                bundle_hash = supplied.bundle_hash,
                trace_root_hash = supplied.trace_root_hash,
                last_event_sequence = supplied.last_event_sequence,
                resolved_model = supplied.resolved_model,
                agent_version = supplied.agent_version,
                verifier_version = supplied.verifier_version,
                harness_version = supplied.harness_version,
                cost_usd = supplied.cost_usd,
                token_count = supplied.token_count,
                latency_ms = supplied.latency_ms,
                failure_attribution = supplied.failure_attribution,
                completed_at = supplied.completed_at
            FROM supplied
            WHERE stored.principal_id = checked_principal
              AND stored.run_id = supplied.run_id
              AND stored.principal_id = supplied.principal_id
              AND stored.task_id = supplied.task_id
              AND stored.lifecycle_status = 'RUNNING'
              AND stored.graph_attempt_id = checked_attempt
              AND stored.graph_attempt_id = supplied.graph_attempt_id
              AND stored.graph_manifest_hash
                    = supplied.graph_manifest_hash
              AND stored.graph_role = 'CHILD'
              AND supplied.graph_role = 'CHILD'
              AND stored.graph_task_hash = supplied.graph_task_hash
              AND stored.graph_selector_hash
                    = supplied.graph_selector_hash
              AND stored.graph_execution_profile_id
                    = supplied.graph_execution_profile_id
              AND stored.graph_execution_profile_fingerprint
                    = supplied.graph_execution_profile_fingerprint
              AND stored.graph_worker_registry_version
                    = supplied.graph_worker_registry_version
              AND stored.graph_worker_profile_id
                    = supplied.graph_worker_profile_id
              AND stored.graph_worker_profile_fingerprint
                    = supplied.graph_worker_profile_fingerprint;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V9 child Run CAS lost'
                    USING ERRCODE = '55000';
            END IF;

            INSERT INTO %1$I.agent_graph_attempt_terminal_bindings
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_terminal_bindings,
                payload -> 'terminal_binding'
            ) supplied;
            INSERT INTO %1$I.agent_graph_attempt_events (
                principal_id, attempt_id, manifest_hash, sequence,
                event_type, occurred_at, phase_from, phase_to,
                role, run_id, task_id, actor, challenge_hash,
                request_ordinal, request_hash, model_requested,
                previous_head_hash, event_hash, current_head_hash,
                evidence_hash
            )
            SELECT
                supplied.principal_id, supplied.attempt_id,
                supplied.manifest_hash, supplied.sequence,
                supplied.event_type, supplied.occurred_at,
                supplied.phase_from, supplied.phase_to,
                supplied.role, supplied.run_id, supplied.task_id,
                supplied.actor, supplied.challenge_hash,
                supplied.request_ordinal, supplied.request_hash,
                supplied.model_requested,
                supplied.previous_head_hash, supplied.event_hash,
                supplied.current_head_hash, supplied.evidence_hash
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_events,
                payload -> 'event'
            ) supplied;

            WITH supplied AS (
                SELECT event.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_graph_attempt_events,
                    payload -> 'event'
                ) event
            )
            UPDATE %1$I.agent_graph_attempt_heads head
            SET phase = supplied.phase_to,
                state_version = supplied.sequence,
                last_sequence = supplied.sequence,
                head_hash = supplied.current_head_hash,
                billing_status = 'ATTRIBUTED',
                provider_intent_count = 2,
                provider_attribution_count = 2,
                updated_at = supplied.occurred_at
            FROM supplied
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
              AND supplied.principal_id = checked_principal
              AND supplied.attempt_id = checked_attempt
              AND supplied.sequence = 15
              AND supplied.event_type = 'CHILD_TERMINAL'
              AND supplied.phase_from = 'PROVIDER_ATTRIBUTED'
              AND supplied.phase_to = 'CHILD_TERMINAL'
              AND head.last_sequence = supplied.sequence - 1
              AND head.state_version = supplied.sequence - 1
              AND head.phase = supplied.phase_from
              AND head.head_hash = supplied.previous_head_hash;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V9 child head CAS lost'
                    USING ERRCODE = '55000';
            END IF;

            PERFORM %1$I.agent_assert_graph_attempt_v8(
                checked_principal, checked_attempt::CHAR(64));
            SET CONSTRAINTS ALL IMMEDIATE;
            RETURN pg_catalog.jsonb_build_object(
                'sequence', 15,
                'phase', 'CHILD_TERMINAL',
                'head_hash', payload #>> '{event,current_head_hash}'
            );
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION %I.agent_graph_complete_child_v9(JSONB) '
            || 'FROM PUBLIC',
        target_schema
    );

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_complete_parent_and_seal_v9(
            payload JSONB
        )
        RETURNS JSONB
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            checked_principal TEXT := payload ->> 'principal_id';
            checked_attempt TEXT := payload ->> 'attempt_id';
            affected INTEGER;
            locked_sequence INTEGER;
            locked_phase TEXT;
        BEGIN
            PERFORM %1$I.agent_graph_require_executor_v9(
                '%1$I.agent_graph_complete_parent_and_seal_v9(jsonb)'
                    ::regprocedure);
            IF pg_catalog.jsonb_typeof(payload) <> 'object'
               OR payload ->> 'protocol'
                    <> 'emergeos.graph-terminal.v9'
               OR pg_catalog.pg_column_size(payload) > 1048576
               OR NOT payload ?& ARRAY[
                    'protocol', 'principal_id', 'attempt_id',
                    'artifact', 'artifact_version', 'trace_events',
                    'resource_bindings', 'terminal_run',
                    'terminal_binding', 'parent_event', 'seal',
                    'seal_event'
               ]
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_object_keys(payload) key
                    WHERE key <> ALL (ARRAY[
                        'protocol', 'principal_id', 'attempt_id',
                        'artifact', 'artifact_version', 'trace_events',
                        'resource_bindings', 'terminal_run',
                        'terminal_binding', 'parent_event', 'seal',
                        'seal_event'
                    ])
               )
               OR pg_catalog.jsonb_typeof(payload -> 'trace_events')
                    <> 'array'
               OR pg_catalog.jsonb_typeof(
                    payload -> 'resource_bindings') <> 'array'
               OR ((payload -> 'artifact' = 'null'::jsonb)
                    <> (payload -> 'artifact_version' = 'null'::jsonb)) THEN
                RAISE EXCEPTION 'invalid V9 parent terminal payload'
                    USING ERRCODE = '22023';
            END IF;

            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'artifact',
                '%1$I.artifacts'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'artifact_version',
                '%1$I.artifact_versions'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'trace_events',
                '%1$I.agent_trace_events'::regclass,
                true);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'resource_bindings',
                '%1$I.agent_run_resource_bindings'::regclass,
                true);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'terminal_run',
                '%1$I.agent_runs'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'terminal_binding',
                '%1$I.agent_graph_attempt_terminal_bindings'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                (payload -> 'parent_event')
                    || pg_catalog.jsonb_build_object(
                        'committed_at',
                        pg_catalog.transaction_timestamp()),
                '%1$I.agent_graph_attempt_events'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                payload -> 'seal',
                '%1$I.agent_graph_attempt_seals'::regclass,
                false);
            PERFORM %1$I.agent_graph_require_row_shape_v9(
                (payload -> 'seal_event')
                    || pg_catalog.jsonb_build_object(
                        'committed_at',
                        pg_catalog.transaction_timestamp()),
                '%1$I.agent_graph_attempt_events'::regclass,
                false);
            IF payload #>> '{terminal_run,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{terminal_run,graph_attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{terminal_run,graph_role}'
                    IS DISTINCT FROM 'PARENT'
               OR (payload -> 'artifact' <> 'null'::jsonb AND (
                    payload #>> '{artifact,principal_id}'
                        IS DISTINCT FROM checked_principal
                    OR payload #>> '{artifact,artifact_id}'
                        IS DISTINCT FROM
                            payload #>> '{artifact_version,artifact_id}'
                    OR payload #>> '{artifact_version,principal_id}'
                        IS DISTINCT FROM checked_principal
                    OR payload #>> '{artifact,current_version}'
                        IS DISTINCT FROM
                            payload #>> '{artifact_version,version}'
                    OR payload #>> '{artifact,current_hash}'
                        IS DISTINCT FROM
                            payload #>> '{artifact_version,content_hash}'
               ))
               OR (
                    payload #>> '{terminal_run,lifecycle_status}'
                        = 'SUCCEEDED'
                    AND (
                        payload -> 'artifact' = 'null'::jsonb
                        OR NOT EXISTS (
                            SELECT 1
                            FROM %1$I.agent_graph_attempts attempt
                            WHERE attempt.principal_id
                                    = checked_principal
                              AND attempt.attempt_id
                                    = checked_attempt
                              AND attempt.artifact_id
                                    = payload #>>
                                        '{artifact,artifact_id}'
                              AND attempt.capture_id
                                    = payload #>>
                                        '{artifact,source_capture_id}')
                        OR (SELECT pg_catalog.count(*)
                            FROM pg_catalog.jsonb_array_elements(
                                payload -> 'resource_bindings') row_value
                            WHERE row_value ->> 'role' = 'ARTIFACT'
                              AND row_value ->> 'artifact_id'
                                    = payload #>>
                                        '{artifact,artifact_id}'
                              AND row_value ->> 'artifact_version'
                                    = payload #>>
                                        '{artifact_version,version}'
                              AND row_value ->> 'content_hash'
                                    = payload #>>
                                        '{artifact_version,content_hash}'
                              AND row_value ->> 'resource_ref'
                                    = payload #>>
                                        '{terminal_binding,effect_ref}'
                              AND row_value ->> 'content_hash'
                                    = payload #>>
                                        '{terminal_binding,effect_hash}')
                            <> 1
                    )
               )
               OR (
                    payload #>> '{terminal_run,lifecycle_status}'
                        <> 'SUCCEEDED'
                    AND (
                        payload -> 'artifact' <> 'null'::jsonb
                        OR EXISTS (
                            SELECT 1
                            FROM pg_catalog.jsonb_array_elements(
                                payload -> 'resource_bindings') row_value
                            WHERE row_value ->> 'role' = 'ARTIFACT')
                    )
               )
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_array_elements(
                        payload -> 'trace_events') row_value
                    WHERE row_value ->> 'principal_id'
                            IS DISTINCT FROM checked_principal
                       OR row_value ->> 'run_id'
                            IS DISTINCT FROM
                                payload #>> '{terminal_run,run_id}'
               )
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.jsonb_array_elements(
                        payload -> 'resource_bindings') row_value
                    WHERE row_value ->> 'principal_id'
                            IS DISTINCT FROM checked_principal
                       OR row_value ->> 'run_id'
                            IS DISTINCT FROM
                                payload #>> '{terminal_run,run_id}'
               )
               OR payload #>> '{terminal_binding,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{terminal_binding,attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{terminal_binding,role}'
                    IS DISTINCT FROM 'PARENT'
               OR payload #>> '{terminal_binding,run_id}'
                    IS DISTINCT FROM
                        payload #>> '{terminal_run,run_id}'
               OR payload #>> '{parent_event,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{parent_event,attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{parent_event,role}'
                    IS DISTINCT FROM 'PARENT'
               OR payload #>> '{parent_event,run_id}'
                    IS DISTINCT FROM
                        payload #>> '{terminal_run,run_id}'
               OR payload #>> '{seal,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{seal,attempt_id}'
                    IS DISTINCT FROM checked_attempt
               OR payload #>> '{seal_event,principal_id}'
                    IS DISTINCT FROM checked_principal
               OR payload #>> '{seal_event,attempt_id}'
                    IS DISTINCT FROM checked_attempt THEN
                RAISE EXCEPTION 'V9 parent payload escapes TX-C aggregate'
                    USING ERRCODE = '22023';
            END IF;

            SELECT head.last_sequence, head.phase
            INTO locked_sequence, locked_phase
            FROM %1$I.agent_graph_attempt_heads head
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
            FOR UPDATE;
            IF locked_sequence IS DISTINCT FROM 15
               OR locked_phase IS DISTINCT FROM 'CHILD_TERMINAL'
               OR NOT EXISTS (
                    SELECT 1
                    FROM %1$I.agent_runs run
                    WHERE run.principal_id = checked_principal
                      AND run.graph_attempt_id = checked_attempt
                      AND run.graph_role = 'PARENT'
                      AND run.lifecycle_status = 'RUNNING'
                    FOR UPDATE
               ) THEN
                RAISE EXCEPTION
                    'V9 parent completion does not match canonical TX-C head'
                    USING ERRCODE = '55000';
            END IF;

            IF payload -> 'artifact' <> 'null'::jsonb THEN
                INSERT INTO %1$I.artifacts
                SELECT supplied.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.artifacts,
                    payload -> 'artifact'
                ) supplied;
                INSERT INTO %1$I.artifact_versions
                SELECT supplied.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.artifact_versions,
                    payload -> 'artifact_version'
                ) supplied;
            END IF;
            INSERT INTO %1$I.agent_trace_events
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_recordset(
                NULL::%1$I.agent_trace_events,
                payload -> 'trace_events'
            ) supplied;
            INSERT INTO %1$I.agent_run_resource_bindings
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_recordset(
                NULL::%1$I.agent_run_resource_bindings,
                payload -> 'resource_bindings'
            ) supplied;

            PERFORM %1$I.agent_graph_authorize_terminal_v8(
                checked_principal,
                checked_attempt::CHAR(64),
                'PARENT',
                payload #>> '{terminal_run,run_id}'
            );

            WITH supplied AS (
                SELECT replacement.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_runs,
                    payload -> 'terminal_run'
                ) replacement
            )
            UPDATE %1$I.agent_runs stored
            SET lifecycle_status = supplied.lifecycle_status,
                result_envelope = supplied.result_envelope,
                bundle = supplied.bundle,
                bundle_hash = supplied.bundle_hash,
                trace_root_hash = supplied.trace_root_hash,
                last_event_sequence = supplied.last_event_sequence,
                resolved_model = supplied.resolved_model,
                agent_version = supplied.agent_version,
                verifier_version = supplied.verifier_version,
                harness_version = supplied.harness_version,
                cost_usd = supplied.cost_usd,
                token_count = supplied.token_count,
                latency_ms = supplied.latency_ms,
                failure_attribution = supplied.failure_attribution,
                completed_at = supplied.completed_at
            FROM supplied
            WHERE stored.principal_id = checked_principal
              AND stored.run_id = supplied.run_id
              AND stored.principal_id = supplied.principal_id
              AND stored.task_id = supplied.task_id
              AND stored.lifecycle_status = 'RUNNING'
              AND stored.graph_attempt_id = checked_attempt
              AND stored.graph_attempt_id = supplied.graph_attempt_id
              AND stored.graph_manifest_hash
                    = supplied.graph_manifest_hash
              AND stored.graph_role = 'PARENT'
              AND supplied.graph_role = 'PARENT'
              AND stored.graph_task_hash = supplied.graph_task_hash
              AND stored.graph_selector_hash
                    = supplied.graph_selector_hash
              AND stored.graph_execution_profile_id
                    = supplied.graph_execution_profile_id
              AND stored.graph_execution_profile_fingerprint
                    = supplied.graph_execution_profile_fingerprint
              AND stored.graph_worker_registry_version
                    = supplied.graph_worker_registry_version
              AND stored.graph_worker_profile_id
                    = supplied.graph_worker_profile_id
              AND stored.graph_worker_profile_fingerprint
                    = supplied.graph_worker_profile_fingerprint;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V9 parent Run CAS lost'
                    USING ERRCODE = '55000';
            END IF;

            INSERT INTO %1$I.agent_graph_attempt_terminal_bindings
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_terminal_bindings,
                payload -> 'terminal_binding'
            ) supplied;
            INSERT INTO %1$I.agent_graph_attempt_events (
                principal_id, attempt_id, manifest_hash, sequence,
                event_type, occurred_at, phase_from, phase_to,
                role, run_id, task_id, actor, challenge_hash,
                request_ordinal, request_hash, model_requested,
                previous_head_hash, event_hash, current_head_hash,
                evidence_hash
            )
            SELECT
                supplied.principal_id, supplied.attempt_id,
                supplied.manifest_hash, supplied.sequence,
                supplied.event_type, supplied.occurred_at,
                supplied.phase_from, supplied.phase_to,
                supplied.role, supplied.run_id, supplied.task_id,
                supplied.actor, supplied.challenge_hash,
                supplied.request_ordinal, supplied.request_hash,
                supplied.model_requested,
                supplied.previous_head_hash, supplied.event_hash,
                supplied.current_head_hash, supplied.evidence_hash
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_events,
                payload -> 'parent_event'
            ) supplied;

            WITH supplied AS (
                SELECT event.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_graph_attempt_events,
                    payload -> 'parent_event'
                ) event
            )
            UPDATE %1$I.agent_graph_attempt_heads head
            SET phase = supplied.phase_to,
                state_version = supplied.sequence,
                last_sequence = supplied.sequence,
                head_hash = supplied.current_head_hash,
                billing_status = 'ATTRIBUTED',
                provider_intent_count = 2,
                provider_attribution_count = 2,
                updated_at = supplied.occurred_at
            FROM supplied
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
              AND supplied.principal_id = checked_principal
              AND supplied.attempt_id = checked_attempt
              AND supplied.sequence = 16
              AND supplied.event_type = 'PARENT_TERMINAL'
              AND supplied.phase_from = 'CHILD_TERMINAL'
              AND supplied.phase_to = 'PARENT_TERMINAL'
              AND head.last_sequence = supplied.sequence - 1
              AND head.state_version = supplied.sequence - 1
              AND head.phase = supplied.phase_from
              AND head.head_hash = supplied.previous_head_hash;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V9 parent head CAS lost'
                    USING ERRCODE = '55000';
            END IF;

            INSERT INTO %1$I.agent_graph_attempt_seals
            SELECT supplied.*
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_seals,
                payload -> 'seal'
            ) supplied;
            INSERT INTO %1$I.agent_graph_attempt_events (
                principal_id, attempt_id, manifest_hash, sequence,
                event_type, occurred_at, phase_from, phase_to,
                role, run_id, task_id, actor, challenge_hash,
                request_ordinal, request_hash, model_requested,
                previous_head_hash, event_hash, current_head_hash,
                evidence_hash
            )
            SELECT
                supplied.principal_id, supplied.attempt_id,
                supplied.manifest_hash, supplied.sequence,
                supplied.event_type, supplied.occurred_at,
                supplied.phase_from, supplied.phase_to,
                supplied.role, supplied.run_id, supplied.task_id,
                supplied.actor, supplied.challenge_hash,
                supplied.request_ordinal, supplied.request_hash,
                supplied.model_requested,
                supplied.previous_head_hash, supplied.event_hash,
                supplied.current_head_hash, supplied.evidence_hash
            FROM pg_catalog.jsonb_populate_record(
                NULL::%1$I.agent_graph_attempt_events,
                payload -> 'seal_event'
            ) supplied;

            WITH supplied AS (
                SELECT event.*
                FROM pg_catalog.jsonb_populate_record(
                    NULL::%1$I.agent_graph_attempt_events,
                    payload -> 'seal_event'
                ) event
            )
            UPDATE %1$I.agent_graph_attempt_heads head
            SET phase = supplied.phase_to,
                state_version = supplied.sequence,
                last_sequence = supplied.sequence,
                head_hash = supplied.current_head_hash,
                billing_status = 'ATTRIBUTED',
                provider_intent_count = 2,
                provider_attribution_count = 2,
                updated_at = supplied.occurred_at
            FROM supplied
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
              AND supplied.principal_id = checked_principal
              AND supplied.attempt_id = checked_attempt
              AND supplied.sequence = 17
              AND supplied.event_type = 'TERMINAL_SEALED'
              AND supplied.phase_from = 'PARENT_TERMINAL'
              AND supplied.phase_to = 'TERMINAL'
              AND head.last_sequence = supplied.sequence - 1
              AND head.state_version = supplied.sequence - 1
              AND head.phase = supplied.phase_from
              AND head.head_hash = supplied.previous_head_hash;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V9 seal head CAS lost'
                    USING ERRCODE = '55000';
            END IF;

            PERFORM %1$I.agent_assert_graph_attempt_v8(
                checked_principal, checked_attempt::CHAR(64));
            SET CONSTRAINTS ALL IMMEDIATE;
            RETURN pg_catalog.jsonb_build_object(
                'sequence', 17,
                'phase', 'TERMINAL',
                'head_hash', payload #>> '{seal_event,current_head_hash}'
            );
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_complete_parent_and_seal_v9(JSONB) '
            || 'FROM PUBLIC',
        target_schema
    );
END;
$migration$;
