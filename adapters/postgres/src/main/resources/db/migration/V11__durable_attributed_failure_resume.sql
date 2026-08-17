-- Forward-only durable provenance and lease fence for Pack010 typed
-- pre-Candidate failures. V8/V9/V10 remain immutable history.

CREATE TABLE agent_graph_attributed_failure_outcomes (
    principal_id VARCHAR(200) NOT NULL,
    attempt_id CHAR(64) NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    revision VARCHAR(2) NOT NULL,
    session_intent_hash CHAR(64) NOT NULL,
    session_expires_at TIMESTAMPTZ NOT NULL,
    previous_sequence INTEGER NOT NULL,
    previous_head_hash CHAR(64) NOT NULL,
    cursor_sequence INTEGER NOT NULL,
    cursor_head_hash CHAR(64) NOT NULL,
    provider_attribution_1_hash CHAR(64) NOT NULL,
    provider_attribution_2_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    model_resolved VARCHAR(512) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    provenance_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    state_version BIGINT NOT NULL,
    claimant_id VARCHAR(200),
    fence_token_hash CHAR(64),
    claim_expires_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY (principal_id, attempt_id),
    UNIQUE (principal_id, provenance_hash),
    UNIQUE (principal_id, fence_token_hash),
    FOREIGN KEY (principal_id, attempt_id, manifest_hash)
        REFERENCES agent_graph_attempts (
            principal_id, attempt_id, manifest_hash),
    FOREIGN KEY (principal_id, session_intent_hash)
        REFERENCES agent_graph_provider_session_intents (
            principal_id, intent_hash),
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
    FOREIGN KEY (
        principal_id,
        attempt_id,
        provider_attribution_1_hash
    ) REFERENCES agent_graph_attempt_provider_attributions (
        principal_id,
        attempt_id,
        attribution_hash
    ),
    FOREIGN KEY (
        principal_id,
        attempt_id,
        provider_attribution_2_hash
    ) REFERENCES agent_graph_attempt_provider_attributions (
        principal_id,
        attempt_id,
        attribution_hash
    ),
    CHECK (revision IN ('r1', 'r2', 'r3')),
    CHECK (previous_sequence = 13 AND cursor_sequence = 14),
    CHECK (failure_code IN (
        'MODEL_RESPONSE_MALFORMED',
        'MODEL_USAGE_LIMIT_EXCEEDED')),
    CHECK (
        manifest_hash ~ '^[0-9a-f]{64}$'
        AND session_intent_hash ~ '^[0-9a-f]{64}$'
        AND previous_head_hash ~ '^[0-9a-f]{64}$'
        AND cursor_head_hash ~ '^[0-9a-f]{64}$'
        AND provider_attribution_1_hash ~ '^[0-9a-f]{64}$'
        AND provider_attribution_2_hash ~ '^[0-9a-f]{64}$'
        AND request_hash ~ '^[0-9a-f]{64}$'
        AND response_hash ~ '^[0-9a-f]{64}$'
        AND provenance_hash ~ '^[0-9a-f]{64}$'),
    CHECK (
        model_resolved
            ~ '^[A-Za-z0-9][A-Za-z0-9._~:/-]*$'
        AND char_length(model_resolved) <= 512),
    CHECK (
        state_version BETWEEN 1 AND 9007199254740991
        AND (
            (state = 'READY'
             AND state_version = 1
             AND claimant_id IS NULL
             AND fence_token_hash IS NULL
             AND claim_expires_at IS NULL
             AND claimed_at IS NULL)
            OR
            (state = 'CLAIMED'
             AND state_version >= 2
             AND char_length(btrim(claimant_id)) BETWEEN 1 AND 200
             AND fence_token_hash ~ '^[0-9a-f]{64}$'
             AND claim_expires_at > claimed_at
             AND claim_expires_at <= session_expires_at
             AND claimed_at >= recorded_at)))
);

DO $migration$
DECLARE
    target_schema NAME := current_schema();
BEGIN
    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_read_attributed_failure_v11(
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
            state VARCHAR(16),
            state_version BIGINT,
            claimant_id VARCHAR(200),
            fence_token_hash CHAR(64),
            claim_expires_at TIMESTAMPTZ,
            live_sequence INTEGER,
            live_head_hash CHAR(64)
        )
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        BEGIN
            IF session_user IS DISTINCT FROM
                    'emergeos_failure_resumer' THEN
                RAISE EXCEPTION 'V11 failure read authority rejected'
                    USING ERRCODE = '42501';
            END IF;
            IF NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.rolname = session_user
                      AND role.rolcanlogin
                      AND NOT role.rolinherit
                      AND NOT role.rolsuper
                      AND NOT role.rolcreatedb
                      AND NOT role.rolcreaterole
                      AND NOT role.rolreplication
                      AND NOT role.rolbypassrls
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_auth_members membership
                        WHERE membership.member = role.oid
                           OR membership.roleid = role.oid))
               OR NOT pg_catalog.has_database_privilege(
                    session_user, current_database(), 'CONNECT')
               OR pg_catalog.has_database_privilege(
                    session_user, current_database(),
                    'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
               OR NOT pg_catalog.has_schema_privilege(
                    session_user, '%1$I', 'USAGE')
               OR pg_catalog.has_schema_privilege(
                    session_user, '%1$I',
                    'CREATE,USAGE WITH GRANT OPTION')
               OR (SELECT count(*)
                   FROM pg_catalog.pg_proc procedure
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = procedure.pronamespace
                   WHERE namespace.nspname = '%1$I'
                     AND pg_catalog.has_function_privilege(
                       session_user, procedure.oid, 'EXECUTE')) <> 2
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    JOIN pg_catalog.pg_roles owner
                      ON owner.oid = procedure.proowner
                    WHERE namespace.nspname = '%1$I'
                      AND procedure.proname IN (
                        'agent_graph_record_attributed_failure_v11',
                        'agent_graph_read_attributed_failure_v11',
                        'agent_graph_claim_attributed_failure_v11')
                      AND NOT (
                        owner.rolname = 'emergeos_pack010_schema_owner'
                        AND procedure.prosecdef
                        AND procedure.provolatile = 'v'
                        AND procedure.proparallel = 'u'
                        AND procedure.prokind = 'f'
                        AND NOT procedure.proisstrict
                        AND NOT procedure.proleakproof
                        AND procedure.proconfig = ARRAY[
                          'search_path=pg_catalog, pg_temp']::text[]))
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
                    LEFT JOIN pg_catalog.pg_roles grantee
                      ON grantee.oid = acl.grantee
                    WHERE namespace.nspname = '%1$I'
                      AND procedure.proname IN (
                        'agent_graph_record_attributed_failure_v11',
                        'agent_graph_read_attributed_failure_v11',
                        'agent_graph_claim_attributed_failure_v11')
                      AND NOT (
                        acl.privilege_type = 'EXECUTE'
                        AND (
                          acl.grantee = procedure.proowner
                          OR
                          (NOT acl.is_grantable
                           AND (
                             (procedure.proname =
                                'agent_graph_record_attributed_failure_v11'
                              AND grantee.rolname =
                                'emergeos_graph_prefix_writer')
                             OR
                             (procedure.proname IN (
                                'agent_graph_read_attributed_failure_v11',
                                'agent_graph_claim_attributed_failure_v11')
                              AND grantee.rolname =
                                'emergeos_failure_resumer'))))))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relkind IN ('r', 'p')
                      AND pg_catalog.has_table_privilege(
                        session_user, relation.oid,
                        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_attribute attribute
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = attribute.attrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND attribute.attnum > 0
                      AND NOT attribute.attisdropped
                      AND (
                        pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'SELECT')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'INSERT')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'UPDATE')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'REFERENCES')))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relkind = 'S'
                      AND pg_catalog.has_sequence_privilege(
                        session_user, relation.oid,
                        'USAGE,SELECT,UPDATE'))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_trigger trigger
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = trigger.tgrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relname =
                        'agent_graph_attributed_failure_outcomes'
                      AND NOT trigger.tgisinternal) THEN
                RAISE EXCEPTION 'V11 failure resumer topology drifted'
                    USING ERRCODE = '42501';
            END IF;
            RETURN QUERY
            SELECT durable.manifest_hash,
                   durable.revision,
                   durable.session_intent_hash,
                   durable.session_expires_at,
                   durable.cursor_sequence,
                   durable.cursor_head_hash,
                   durable.provider_attribution_1_hash,
                   durable.provider_attribution_2_hash,
                   durable.request_hash,
                   durable.response_hash,
                   durable.model_resolved,
                   durable.failure_code,
                   durable.provenance_hash,
                   durable.state,
                   durable.state_version,
                   durable.claimant_id,
                   durable.fence_token_hash,
                   durable.claim_expires_at,
                   head.last_sequence,
                   head.head_hash
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            JOIN %1$I.agent_graph_attempt_heads head
              ON head.principal_id = durable.principal_id
             AND head.attempt_id = durable.attempt_id
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
              AND durable.manifest_hash = checked_manifest;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_read_attributed_failure_v11('
            || 'VARCHAR, CHAR, CHAR) FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_record_attributed_failure_v11(
            checked_principal VARCHAR,
            checked_attempt CHAR,
            checked_manifest CHAR,
            checked_previous_sequence INTEGER,
            checked_previous_head CHAR,
            checked_failure_code VARCHAR
        )
        RETURNS CHAR(64)
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        AS $function$
        DECLARE
            locked_head RECORD;
            session_intent RECORD;
            first_attribution RECORD;
            second_attribution RECORD;
            outcome_hash CHAR(64);
        BEGIN
            IF session_user IS DISTINCT FROM
                    'emergeos_graph_prefix_writer'
               OR checked_previous_sequence <> 13
               OR checked_failure_code NOT IN (
                    'MODEL_RESPONSE_MALFORMED',
                    'MODEL_USAGE_LIMIT_EXCEEDED') THEN
                RAISE EXCEPTION 'V11 failure recorder authority rejected'
                    USING ERRCODE = '42501';
            END IF;

            SELECT head.manifest_hash,
                   head.last_sequence,
                   head.head_hash,
                   event.previous_head_hash
            INTO STRICT locked_head
            FROM %1$I.agent_graph_attempt_heads head
            JOIN %1$I.agent_graph_attempt_events event
              ON event.principal_id = head.principal_id
             AND event.attempt_id = head.attempt_id
             AND event.sequence = head.last_sequence
             AND event.current_head_hash = head.head_hash
            WHERE head.principal_id = checked_principal
              AND head.attempt_id = checked_attempt
            FOR UPDATE OF head;
            IF locked_head.manifest_hash IS DISTINCT FROM checked_manifest
               OR locked_head.last_sequence <> 14
               OR locked_head.previous_head_hash
                    IS DISTINCT FROM checked_previous_head THEN
                RAISE EXCEPTION 'V11 failure recorder cursor drift'
                    USING ERRCODE = '55000';
            END IF;

            SELECT intent.revision,
                   intent.intent_hash,
                   intent.expires_at
            INTO STRICT session_intent
            FROM %1$I.agent_graph_provider_session_intents intent
            WHERE intent.principal_id = checked_principal
              AND intent.attempt_id = checked_attempt;
            IF session_intent.expires_at
                    <= pg_catalog.transaction_timestamp() THEN
                RAISE EXCEPTION 'V11 failure recorder session expired'
                    USING ERRCODE = '55000';
            END IF;

            SELECT attribution.attribution_hash
            INTO STRICT first_attribution
            FROM %1$I.agent_graph_attempt_provider_attributions attribution
            WHERE attribution.principal_id = checked_principal
              AND attribution.attempt_id = checked_attempt
              AND attribution.request_ordinal = 1;
            SELECT attribution.attribution_hash,
                   attribution.request_hash,
                   attribution.response_hash,
                   attribution.model_resolved,
                   attribution.attributed_at
            INTO STRICT second_attribution
            FROM %1$I.agent_graph_attempt_provider_attributions attribution
            WHERE attribution.principal_id = checked_principal
              AND attribution.attempt_id = checked_attempt
              AND attribution.request_ordinal = 2
              AND attribution.event_sequence = 14;

            outcome_hash := pg_catalog.encode(
                pg_catalog.sha256(
                    pg_catalog.convert_to(
                        'emergeos.graph-attributed-failure-outcome.v1|'
                        || checked_manifest || '|'
                        || session_intent.revision || '|'
                        || session_intent.intent_hash || '|'
                        || checked_previous_head || '|'
                        || locked_head.head_hash || '|'
                        || first_attribution.attribution_hash || '|'
                        || second_attribution.attribution_hash || '|'
                        || second_attribution.request_hash || '|'
                        || second_attribution.response_hash || '|'
                        || second_attribution.model_resolved || '|'
                        || checked_failure_code,
                        'UTF8')),
                'hex');

            INSERT INTO %1$I.agent_graph_attributed_failure_outcomes (
                principal_id,
                attempt_id,
                manifest_hash,
                revision,
                session_intent_hash,
                session_expires_at,
                previous_sequence,
                previous_head_hash,
                cursor_sequence,
                cursor_head_hash,
                provider_attribution_1_hash,
                provider_attribution_2_hash,
                request_hash,
                response_hash,
                model_resolved,
                failure_code,
                provenance_hash,
                state,
                state_version,
                recorded_at
            ) VALUES (
                checked_principal,
                checked_attempt,
                checked_manifest,
                session_intent.revision,
                session_intent.intent_hash,
                session_intent.expires_at,
                13,
                checked_previous_head,
                14,
                locked_head.head_hash,
                first_attribution.attribution_hash,
                second_attribution.attribution_hash,
                second_attribution.request_hash,
                second_attribution.response_hash,
                second_attribution.model_resolved,
                checked_failure_code,
                outcome_hash,
                'READY',
                1,
                second_attribution.attributed_at
            );
            RETURN outcome_hash;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_record_attributed_failure_v11('
            || 'VARCHAR, CHAR, CHAR, INTEGER, CHAR, VARCHAR) FROM PUBLIC',
        target_schema);

    EXECUTE pg_catalog.format($definition$
        CREATE FUNCTION %1$I.agent_graph_claim_attributed_failure_v11(
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
            now_at TIMESTAMPTZ := pg_catalog.clock_timestamp();
            next_expiry TIMESTAMPTZ;
            affected INTEGER;
        BEGIN
            IF session_user IS DISTINCT FROM
                    'emergeos_failure_resumer'
               OR checked_lease_millis < 100
               OR checked_lease_millis > 30000
               OR checked_claimant IS NULL
               OR char_length(btrim(checked_claimant)) NOT BETWEEN 1 AND 200
               OR checked_fence_token_hash !~ '^[0-9a-f]{64}$' THEN
                RAISE EXCEPTION 'V11 failure claim authority rejected'
                    USING ERRCODE = '42501';
            END IF;
            IF NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles role
                    WHERE role.rolname = session_user
                      AND role.rolcanlogin
                      AND NOT role.rolinherit
                      AND NOT role.rolsuper
                      AND NOT role.rolcreatedb
                      AND NOT role.rolcreaterole
                      AND NOT role.rolreplication
                      AND NOT role.rolbypassrls
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_auth_members membership
                        WHERE membership.member = role.oid
                           OR membership.roleid = role.oid))
               OR NOT pg_catalog.has_database_privilege(
                    session_user, current_database(), 'CONNECT')
               OR pg_catalog.has_database_privilege(
                    session_user, current_database(),
                    'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
               OR NOT pg_catalog.has_schema_privilege(
                    session_user, '%1$I', 'USAGE')
               OR pg_catalog.has_schema_privilege(
                    session_user, '%1$I',
                    'CREATE,USAGE WITH GRANT OPTION')
               OR (SELECT count(*)
                   FROM pg_catalog.pg_proc procedure
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = procedure.pronamespace
                   WHERE namespace.nspname = '%1$I'
                     AND pg_catalog.has_function_privilege(
                       session_user, procedure.oid, 'EXECUTE')) <> 2
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    JOIN pg_catalog.pg_roles owner
                      ON owner.oid = procedure.proowner
                    WHERE namespace.nspname = '%1$I'
                      AND procedure.proname IN (
                        'agent_graph_record_attributed_failure_v11',
                        'agent_graph_read_attributed_failure_v11',
                        'agent_graph_claim_attributed_failure_v11')
                      AND NOT (
                        owner.rolname = 'emergeos_pack010_schema_owner'
                        AND procedure.prosecdef
                        AND procedure.provolatile = 'v'
                        AND procedure.proparallel = 'u'
                        AND procedure.prokind = 'f'
                        AND NOT procedure.proisstrict
                        AND NOT procedure.proleakproof
                        AND procedure.proconfig = ARRAY[
                          'search_path=pg_catalog, pg_temp']::text[]))
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
                    LEFT JOIN pg_catalog.pg_roles grantee
                      ON grantee.oid = acl.grantee
                    WHERE namespace.nspname = '%1$I'
                      AND procedure.proname IN (
                        'agent_graph_record_attributed_failure_v11',
                        'agent_graph_read_attributed_failure_v11',
                        'agent_graph_claim_attributed_failure_v11')
                      AND NOT (
                        acl.privilege_type = 'EXECUTE'
                        AND (
                          acl.grantee = procedure.proowner
                          OR
                          (NOT acl.is_grantable
                           AND (
                             (procedure.proname =
                                'agent_graph_record_attributed_failure_v11'
                              AND grantee.rolname =
                                'emergeos_graph_prefix_writer')
                             OR
                             (procedure.proname IN (
                                'agent_graph_read_attributed_failure_v11',
                                'agent_graph_claim_attributed_failure_v11')
                              AND grantee.rolname =
                                'emergeos_failure_resumer'))))))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relkind IN ('r', 'p')
                      AND pg_catalog.has_table_privilege(
                        session_user, relation.oid,
                        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_attribute attribute
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = attribute.attrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND attribute.attnum > 0
                      AND NOT attribute.attisdropped
                      AND (
                        pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'SELECT')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'INSERT')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'UPDATE')
                        OR pg_catalog.has_column_privilege(
                          session_user, relation.oid,
                          attribute.attnum, 'REFERENCES')))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relkind = 'S'
                      AND pg_catalog.has_sequence_privilege(
                        session_user, relation.oid,
                        'USAGE,SELECT,UPDATE'))
               OR EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_trigger trigger
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = trigger.tgrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = '%1$I'
                      AND relation.relname =
                        'agent_graph_attributed_failure_outcomes'
                      AND NOT trigger.tgisinternal) THEN
                RAISE EXCEPTION 'V11 failure resumer topology drifted'
                    USING ERRCODE = '42501';
            END IF;

            SELECT durable.*
            INTO STRICT outcome
            FROM %1$I.agent_graph_attributed_failure_outcomes durable
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
            FOR UPDATE;
            IF outcome.manifest_hash IS DISTINCT FROM checked_manifest
               OR outcome.provenance_hash
                    IS DISTINCT FROM checked_provenance
               OR outcome.state_version <> checked_state_version
               OR outcome.session_expires_at <= now_at
               OR NOT (
                    outcome.state = 'READY'
                    OR (outcome.state = 'CLAIMED'
                        AND outcome.claim_expires_at <= now_at))
               OR NOT EXISTS (
                    SELECT 1
                    FROM %1$I.agent_graph_attempt_heads head
                    WHERE head.principal_id = checked_principal
                      AND head.attempt_id = checked_attempt
                      AND head.manifest_hash = checked_manifest
                      AND head.last_sequence = 14
                      AND head.head_hash = outcome.cursor_head_hash) THEN
                RAISE EXCEPTION 'V11 failure claim fence rejected'
                    USING ERRCODE = '55000';
            END IF;
            next_expiry := now_at
                + pg_catalog.make_interval(
                    secs => checked_lease_millis::DOUBLE PRECISION / 1000.0);
            IF next_expiry > outcome.session_expires_at THEN
                RAISE EXCEPTION 'V11 failure claim exceeds session expiry'
                    USING ERRCODE = '55000';
            END IF;

            UPDATE %1$I.agent_graph_attributed_failure_outcomes durable
            SET state = 'CLAIMED',
                state_version = checked_state_version + 1,
                claimant_id = checked_claimant,
                fence_token_hash = checked_fence_token_hash,
                claim_expires_at = next_expiry,
                claimed_at = now_at
            WHERE durable.principal_id = checked_principal
              AND durable.attempt_id = checked_attempt
              AND durable.state_version = checked_state_version;
            GET DIAGNOSTICS affected = ROW_COUNT;
            IF affected <> 1 THEN
                RAISE EXCEPTION 'V11 failure claim CAS rejected'
                    USING ERRCODE = '55000';
            END IF;
            RETURN checked_state_version + 1;
        END;
        $function$
    $definition$, target_schema);
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%I.agent_graph_claim_attributed_failure_v11('
            || 'VARCHAR, CHAR, CHAR, CHAR, BIGINT, VARCHAR, CHAR, INTEGER) '
            || 'FROM PUBLIC',
        target_schema);
END;
$migration$;
