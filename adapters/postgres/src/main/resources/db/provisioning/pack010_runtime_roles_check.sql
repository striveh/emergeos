-- Pure read-back audit for the independent Pack010 production roles.
-- This script performs no reconciliation and is safe for --check.

DO $audit$
DECLARE
    managed_roles CONSTANT NAME[] := ARRAY[
        'emergeos_pack010_schema_owner',
        'emergeos_terminal_owner',
        'emergeos_graph_executor',
        'emergeos_failure_resumer',
        'emergeos_provider_attestor',
        'emergeos_provider_attestor_v14',
        'emergeos_provider_attestor_v15',
        'emergeos_provider_attestor_v16',
        'emergeos_graph_prefix_writer',
        'emergeos_graph_reader',
        'emergeos_exact_overlay_reader_v19'
    ]::NAME[];
    runtime_roles CONSTANT NAME[] := ARRAY[
        'emergeos_terminal_owner',
        'emergeos_graph_executor',
        'emergeos_failure_resumer',
        'emergeos_provider_attestor',
        'emergeos_provider_attestor_v14',
        'emergeos_provider_attestor_v15',
        'emergeos_provider_attestor_v16',
        'emergeos_graph_prefix_writer',
        'emergeos_graph_reader',
        'emergeos_exact_overlay_reader_v19'
    ]::NAME[];
    product_relations CONSTANT NAME[] := ARRAY[
        'agent_graph_attempts',
        'agent_graph_attempt_run_bindings',
        'agent_graph_attempt_heads',
        'agent_graph_attempt_events',
        'agent_graph_attempt_provider_attributions',
        'agent_graph_provider_session_intents',
        'agent_graph_attributed_failure_outcomes',
        'agent_graph_attributed_failure_terminal_resumes',
        'agent_graph_provider_validation_keys',
        'agent_graph_provider_validations',
        'agent_graph_provider_profiles_v14',
        'agent_graph_exact_tx_a_requirements_v15',
        'agent_graph_exact_provider_validations_v16',
        'agent_graph_exact_provider_attributions_v16',
        'agent_graph_exact_attempt_events_v16',
        'agent_graph_exact_attempt_heads_v16',
        'agent_runs',
        'agent_graph_attempt_terminal_bindings',
        'agent_graph_attempt_candidates',
        'agent_worker_results',
        'agent_graph_attempt_seals',
        'agent_trace_events',
        'agent_run_resource_bindings',
        'artifacts',
        'artifact_versions'
    ]::NAME[];
    exact_overlay_reader_relations CONSTANT NAME[] := ARRAY[
        'agent_graph_attempts',
        'agent_graph_attempt_heads',
        'agent_graph_attempt_events',
        'agent_graph_attempt_provider_attributions',
        'agent_graph_provider_session_intents',
        'agent_graph_provider_validation_keys',
        'agent_graph_provider_validations',
        'agent_graph_provider_profiles_v14',
        'agent_graph_exact_tx_a_requirements_v15',
        'agent_graph_exact_provider_validations_v16',
        'agent_graph_exact_provider_attributions_v16',
        'agent_graph_exact_attempt_events_v16',
        'agent_graph_exact_attempt_heads_v16'
    ]::NAME[];
    prefix_insert_relations CONSTANT NAME[] := ARRAY[
        'agent_graph_attempts',
        'agent_graph_attempt_run_bindings',
        'agent_graph_attempt_heads',
        'agent_graph_attempt_events',
        'agent_graph_attempt_provider_attributions',
        'agent_graph_provider_session_intents',
        'agent_runs'
    ]::NAME[];
    restricted_relations CONSTANT NAME[] := ARRAY[
        'agent_graph_attributed_failure_terminal_resumes',
        'agent_graph_provider_validation_keys',
        'agent_graph_provider_validations',
        'agent_graph_provider_profiles_v14',
        'agent_graph_exact_tx_a_requirements_v15',
        'agent_graph_exact_provider_validations_v16',
        'agent_graph_exact_provider_attributions_v16',
        'agent_graph_exact_attempt_events_v16',
        'agent_graph_exact_attempt_heads_v16'
    ]::NAME[];
    schema_version INTEGER;
    expected_trigger_topology TEXT;
BEGIN
    IF current_schema() IS DISTINCT FROM 'public' THEN
        RAISE EXCEPTION 'Pack010 audit requires exact public schema'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname
                = 'emergeos_pack010_schema_version_v1'
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = ''
          AND pg_catalog.pg_get_function_result(procedure.oid) = 'integer'
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_pack010_schema_owner'
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = '1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143'
          AND pg_catalog.has_function_privilege(
                'emergeos_terminal_owner', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_terminal_owner', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
          AND pg_catalog.has_function_privilege(
                'emergeos_graph_executor', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_graph_executor', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
          AND pg_catalog.has_function_privilege(
                'emergeos_failure_resumer', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_failure_resumer', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
          AND pg_catalog.has_function_privilege(
                'emergeos_provider_attestor', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_provider_attestor', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
    ) <> 1 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname
                = 'emergeos_pack010_schema_version_v1'
    ) <> 1 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner))) acl
        JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND procedure.proname
                = 'emergeos_pack010_schema_version_v1'
          AND acl.grantee <> procedure.proowner
          AND acl.privilege_type = 'EXECUTE'
          AND NOT acl.is_grantable
          AND grantee.rolname IN (
              'emergeos_terminal_owner',
              'emergeos_graph_executor',
              'emergeos_failure_resumer',
              'emergeos_provider_attestor')
    ) <> 4 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND procedure.proname
                = 'emergeos_pack010_schema_version_v1'
          AND acl.grantee <> procedure.proowner
          AND (
              acl.privilege_type <> 'EXECUTE'
              OR acl.is_grantable
              OR grantee.rolname IS NULL
              OR grantee.rolname NOT IN (
                  'emergeos_terminal_owner',
                  'emergeos_graph_executor',
                  'emergeos_failure_resumer',
                  'emergeos_provider_attestor')
          )
    ) THEN
        RAISE EXCEPTION 'Pack010 schema-version helper drifted'
            USING ERRCODE = '55000';
    END IF;
    schema_version := public.emergeos_pack010_schema_version_v1();
    expected_trigger_topology := CASE schema_version
        WHEN 16
          THEN 'e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b'
        WHEN 17
          THEN '6b86652d9d132538940ddac92752cd6a8741cff759b413d98e7e10e948c2779b'
        ELSE NULL
    END;
    IF expected_trigger_topology IS NULL THEN
        RAISE EXCEPTION 'Pack010 schema version is unsupported'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_require_executor_v10')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = 'expected_function regprocedure'
          AND pg_catalog.pg_get_function_result(procedure.oid) = 'void'
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') =
                CASE procedure.proname
                  WHEN 'agent_graph_require_failure_resumer_v12'
                    THEN '814c2d1a58013749d0441f07411e0065c14acc1d9523bc1ea8d8505218573920'
                  WHEN 'agent_graph_require_executor_v10'
                    THEN '794a126edf1b5a0812ac7feab6ea2be0acc8e808608c83b069b41bf4c6fb394e'
                  ELSE NULL
                END
          AND NOT procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_require_executor_v10')
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
            procedure.proacl,
            pg_catalog.acldefault('f', procedure.proowner))) acl
        JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_require_executor_v10')
          AND acl.grantee <> procedure.proowner
          AND acl.privilege_type = 'EXECUTE'
          AND NOT acl.is_grantable
          AND grantee.rolname = 'emergeos_terminal_owner'
    ) <> 2 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
            procedure.proacl,
            pg_catalog.acldefault('f', procedure.proowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_require_executor_v10')
          AND acl.grantee <> procedure.proowner
          AND (
              acl.privilege_type <> 'EXECUTE'
              OR acl.is_grantable
              OR grantee.rolname IS DISTINCT FROM
                    'emergeos_terminal_owner')
    ) THEN
        RAISE EXCEPTION 'Pack010 live authority guards drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_roles role
        WHERE role.rolname = ANY(managed_roles)
          AND NOT role.rolsuper
          AND NOT role.rolcreatedb
          AND NOT role.rolcreaterole
          AND NOT role.rolinherit
          AND NOT role.rolreplication
          AND NOT role.rolbypassrls
          AND role.rolcanlogin = (role.rolname IN (
              'emergeos_graph_executor',
              'emergeos_failure_resumer',
              'emergeos_provider_attestor',
              'emergeos_provider_attestor_v14',
              'emergeos_provider_attestor_v15',
              'emergeos_provider_attestor_v16',
              'emergeos_graph_prefix_writer',
              'emergeos_graph_reader',
              'emergeos_exact_overlay_reader_v19'))
    ) <> 11 THEN
        RAISE EXCEPTION 'Pack010 role attribute drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members membership
        JOIN pg_catalog.pg_roles granted
          ON granted.oid = membership.roleid
        JOIN pg_catalog.pg_roles member
          ON member.oid = membership.member
        WHERE granted.rolname = ANY(managed_roles)
           OR member.rolname = ANY(managed_roles)
    ) THEN
        RAISE EXCEPTION 'Pack010 role membership drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('v', 'm', 'f')
    ) THEN
        RAISE EXCEPTION 'Pack010 unsupported relation kind drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = namespace.nspowner
        WHERE namespace.nspname = 'public'
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = relation.relowner
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p', 'S')
          AND owner.rolname <> 'emergeos_pack010_schema_owner'
    ) THEN
        RAISE EXCEPTION 'Pack010 schema/relation owner drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        SELECT count(*) = 67
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            column_shape.relname,
                            column_shape.attnum::TEXT,
                            column_shape.attname,
                            column_shape.type_name,
                            column_shape.attnotnull::TEXT,
                            column_shape.attidentity::TEXT,
                            column_shape.attgenerated::TEXT,
                            column_shape.default_expr,
                            column_shape.collation_name),
                        ',' ORDER BY column_shape.relname,
                            column_shape.attnum),
                    'UTF8')), 'hex') =
                '9d85e8dc5f87993b545ee3a519d5c3f8870777e1ad7be7141c9a8181beb2a7f5'
        FROM (
            SELECT relation.relname,
                   attribute.attnum,
                   attribute.attname,
                   pg_catalog.format_type(
                       attribute.atttypid,
                       attribute.atttypmod) AS type_name,
                   attribute.attnotnull,
                   attribute.attidentity,
                   attribute.attgenerated,
                   COALESCE(pg_catalog.pg_get_expr(
                       default_value.adbin,
                       default_value.adrelid), '') AS default_expr,
                   COALESCE(collation_row.collname, '') AS collation_name
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation
              ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef default_value
              ON default_value.adrelid = attribute.attrelid
             AND default_value.adnum = attribute.attnum
            LEFT JOIN pg_catalog.pg_collation collation_row
              ON collation_row.oid = attribute.attcollation
            WHERE namespace.nspname = 'public'
              AND relation.relname IN (
                  'agent_graph_provider_validation_keys',
                  'agent_graph_provider_validations')
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
        ) column_shape
    ) OR NOT (
        SELECT count(*) = 46
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            constraint_row.conname,
                            constraint_row.contype::TEXT,
                            constraint_row.condeferrable::TEXT,
                            constraint_row.condeferred::TEXT,
                            constraint_row.convalidated::TEXT,
                            pg_catalog.pg_get_constraintdef(
                                constraint_row.oid, true)),
                        ',' ORDER BY relation.relname,
                            constraint_row.conname),
                    'UTF8')), 'hex') =
                '44d64677439d87d9302d6589d94d58bc5fc20cdeb45ba29d8684b63510b5b097'
        FROM pg_catalog.pg_constraint constraint_row
        JOIN pg_catalog.pg_class relation
          ON relation.oid = constraint_row.conrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_provider_validation_keys',
              'agent_graph_provider_validations')
    ) OR NOT (
        SELECT count(*) = 7
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            index_relation.relname,
                            index_row.indisunique::TEXT,
                            index_row.indisprimary::TEXT,
                            index_row.indisvalid::TEXT,
                            index_row.indisready::TEXT,
                            pg_catalog.pg_get_indexdef(
                                index_relation.oid)),
                        ',' ORDER BY relation.relname,
                            index_relation.relname),
                    'UTF8')), 'hex') =
                'a6c0ee2f58fa42aa650efb435c9333dca54bab602a66762185bc276110020d32'
        FROM pg_catalog.pg_index index_row
        JOIN pg_catalog.pg_class index_relation
          ON index_relation.oid = index_row.indexrelid
        JOIN pg_catalog.pg_class relation
          ON relation.oid = index_row.indrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_provider_validation_keys',
              'agent_graph_provider_validations')
    ) OR NOT (
        SELECT count(*) = 2
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            relation.relkind::TEXT,
                            relation.relpersistence::TEXT,
                            relation.relrowsecurity::TEXT,
                            relation.relforcerowsecurity::TEXT,
                            relation.relreplident::TEXT),
                        ',' ORDER BY relation.relname),
                    'UTF8')), 'hex') =
                '9617d517077b727838127d47e3a95d2e93fe9ee2240eff18d00eb847b1e6f70a'
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_provider_validation_keys',
              'agent_graph_provider_validations')
    ) THEN
        RAISE EXCEPTION 'Pack010 V13 relation topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        SELECT count(*) = 27
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            column_shape.relname,
                            column_shape.attnum::TEXT,
                            column_shape.attname,
                            column_shape.type_name,
                            column_shape.attnotnull::TEXT,
                            column_shape.attidentity::TEXT,
                            column_shape.attgenerated::TEXT,
                            column_shape.default_expr,
                            column_shape.collation_name),
                        ',' ORDER BY column_shape.attnum),
                    'UTF8')), 'hex') =
                'b0ee453d7f976706d2e3583d9419535ef14f5655c9888a9fef7be11fb76b007c'
        FROM (
            SELECT relation.relname,
                   attribute.attnum,
                   attribute.attname,
                   pg_catalog.format_type(
                       attribute.atttypid,
                       attribute.atttypmod) AS type_name,
                   attribute.attnotnull,
                   attribute.attidentity,
                   attribute.attgenerated,
                   COALESCE(pg_catalog.pg_get_expr(
                       default_value.adbin,
                       default_value.adrelid), '') AS default_expr,
                   COALESCE(collation_row.collname, '') AS collation_name
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation
              ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef default_value
              ON default_value.adrelid = attribute.attrelid
             AND default_value.adnum = attribute.attnum
            LEFT JOIN pg_catalog.pg_collation collation_row
              ON collation_row.oid = attribute.attcollation
            WHERE namespace.nspname = 'public'
              AND relation.relname = 'agent_graph_provider_profiles_v14'
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
        ) column_shape
    ) OR NOT (
        SELECT count(*) = 29
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            constraint_row.conname,
                            constraint_row.contype::TEXT,
                            constraint_row.condeferrable::TEXT,
                            constraint_row.condeferred::TEXT,
                            constraint_row.convalidated::TEXT,
                            pg_catalog.pg_get_constraintdef(
                                constraint_row.oid, true)),
                        ',' ORDER BY constraint_row.conname),
                    'UTF8')), 'hex') =
                '0249dbb2d0a2668553b38ff81a253f7b668106eed8e8535c33da393638ef9d14'
        FROM pg_catalog.pg_constraint constraint_row
        JOIN pg_catalog.pg_class relation
          ON relation.oid = constraint_row.conrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname = 'agent_graph_provider_profiles_v14'
    ) OR NOT (
        SELECT count(*) = 2
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            index_relation.relname,
                            index_row.indisunique::TEXT,
                            index_row.indisprimary::TEXT,
                            index_row.indisvalid::TEXT,
                            index_row.indisready::TEXT,
                            pg_catalog.pg_get_indexdef(
                                index_relation.oid)),
                        ',' ORDER BY index_relation.relname),
                    'UTF8')), 'hex') =
                'cf246f159b26d74b71eb891ddad442f1509a0e33f8976624c3c188f2831da04a'
        FROM pg_catalog.pg_index index_row
        JOIN pg_catalog.pg_class index_relation
          ON index_relation.oid = index_row.indexrelid
        JOIN pg_catalog.pg_class relation
          ON relation.oid = index_row.indrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname = 'agent_graph_provider_profiles_v14'
    ) OR NOT (
        SELECT count(*) = 1
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            relation.relkind::TEXT,
                            relation.relpersistence::TEXT,
                            relation.relrowsecurity::TEXT,
                            relation.relforcerowsecurity::TEXT,
                            relation.relreplident::TEXT),
                        ',' ORDER BY relation.relname),
                    'UTF8')), 'hex') =
                'f0b7dbab875649d00c52ec0b289ff21c442044d7b8fee9c35610399bec4c0483'
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname = 'agent_graph_provider_profiles_v14'
    ) THEN
        RAISE EXCEPTION 'Pack010 V14 provider profile topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        SELECT count(*) = 17
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            column_shape.relname,
                            column_shape.attnum::TEXT,
                            column_shape.attname,
                            column_shape.type_name,
                            column_shape.attnotnull::TEXT,
                            column_shape.attidentity::TEXT,
                            column_shape.attgenerated::TEXT,
                            column_shape.default_expr,
                            column_shape.collation_name),
                        ',' ORDER BY column_shape.attnum),
                    'UTF8')), 'hex') =
                '1a1a18b62f697870b372aa83c7c7bfb5d7281132221ac89745ec5e73d559e6c4'
        FROM (
            SELECT relation.relname,
                   attribute.attnum,
                   attribute.attname,
                   pg_catalog.format_type(
                       attribute.atttypid,
                       attribute.atttypmod) AS type_name,
                   attribute.attnotnull,
                   attribute.attidentity,
                   attribute.attgenerated,
                   COALESCE(pg_catalog.pg_get_expr(
                       default_value.adbin,
                       default_value.adrelid), '') AS default_expr,
                   COALESCE(collation_row.collname, '') AS collation_name
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation
              ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef default_value
              ON default_value.adrelid = attribute.attrelid
             AND default_value.adnum = attribute.attnum
            LEFT JOIN pg_catalog.pg_collation collation_row
              ON collation_row.oid = attribute.attcollation
            WHERE namespace.nspname = 'public'
              AND relation.relname
                    = 'agent_graph_exact_tx_a_requirements_v15'
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
        ) column_shape
    ) OR NOT (
        SELECT count(*) = 24
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            constraint_row.conname,
                            constraint_row.contype::TEXT,
                            constraint_row.condeferrable::TEXT,
                            constraint_row.condeferred::TEXT,
                            constraint_row.convalidated::TEXT,
                            pg_catalog.pg_get_constraintdef(
                                constraint_row.oid, true)),
                        ',' ORDER BY constraint_row.conname),
                    'UTF8')), 'hex') =
                '04e4a9fb12a87cf6962a549445d8aa2481871938d11b119f389c60623f3a7142'
        FROM pg_catalog.pg_constraint constraint_row
        JOIN pg_catalog.pg_class relation
          ON relation.oid = constraint_row.conrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname
                = 'agent_graph_exact_tx_a_requirements_v15'
    ) OR NOT (
        SELECT count(*) = 2
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            index_relation.relname,
                            index_row.indisunique::TEXT,
                            index_row.indisprimary::TEXT,
                            index_row.indisvalid::TEXT,
                            index_row.indisready::TEXT,
                            pg_catalog.pg_get_indexdef(
                                index_relation.oid)),
                        ',' ORDER BY index_relation.relname),
                    'UTF8')), 'hex') =
                'c3402d765391b9eb542c42d1e3e63a372cd61af0807b89888226c9cb7bfc18b4'
        FROM pg_catalog.pg_index index_row
        JOIN pg_catalog.pg_class index_relation
          ON index_relation.oid = index_row.indexrelid
        JOIN pg_catalog.pg_class relation
          ON relation.oid = index_row.indrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname
                = 'agent_graph_exact_tx_a_requirements_v15'
    ) OR NOT (
        SELECT count(*) = 1
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            relation.relkind::TEXT,
                            relation.relpersistence::TEXT,
                            relation.relrowsecurity::TEXT,
                            relation.relforcerowsecurity::TEXT,
                            relation.relreplident::TEXT),
                        ',' ORDER BY relation.relname),
                    'UTF8')), 'hex') =
                'dff070d8412651d4f2d970f7f96bc7e0a98d7fe13f29c919d9528a1160383588'
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname
                = 'agent_graph_exact_tx_a_requirements_v15'
    ) THEN
        RAISE EXCEPTION 'Pack010 V15 requirement topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_record_attributed_failure_v11',
              'agent_graph_read_attributed_failure_v11',
              'agent_graph_claim_attributed_failure_v11')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
              WHEN 'agent_graph_record_attributed_failure_v11'
                THEN '4ddd44034bcaf7aa516c78785f0095c9cacec933b651630d2a1675a161de31b4'
              WHEN 'agent_graph_read_attributed_failure_v11'
                THEN 'd8e46321a9f61246a5dff147bab321f8985552a430042b208c42cbd8b0219d41'
              WHEN 'agent_graph_claim_attributed_failure_v11'
                THEN '546182f85842c6adba7db4f37260f111f87f55c50e311624c273025410a1955b'
              ELSE NULL
            END
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_record_attributed_failure_v11'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_previous_sequence integer, checked_previous_head character, checked_failure_code character varying'
              WHEN 'agent_graph_read_attributed_failure_v11'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character'
              WHEN 'agent_graph_claim_attributed_failure_v11'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character, checked_lease_millis integer'
              ELSE NULL
            END
          AND pg_catalog.md5(
                pg_catalog.pg_get_function_result(procedure.oid))
                = CASE procedure.proname
              WHEN 'agent_graph_record_attributed_failure_v11'
                THEN 'a956161a69928cd130a889b88082fb6e'
              WHEN 'agent_graph_read_attributed_failure_v11'
                THEN 'a58f80a0c139add8aad971aaa5aae370'
              WHEN 'agent_graph_claim_attributed_failure_v11'
                THEN '428fda282c5955ae90508b5edf6812cf'
              ELSE NULL
            END
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.proretset
                = (procedure.proname
                    = 'agent_graph_read_attributed_failure_v11')
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 3 THEN
        RAISE EXCEPTION 'Pack010 V11 durable failure definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_read_attributed_failure_resume_v12',
              'agent_graph_claim_attributed_failure_resume_v12',
              'agent_graph_complete_claimed_failure_child_v12',
              'agent_graph_complete_claimed_failure_parent_and_seal_v12')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
              WHEN 'agent_graph_read_attributed_failure_resume_v12'
                THEN 'e3e6c0b9a14df5c741d5895bc189ef61682b78690a80921066ee7213645e9c9e'
              WHEN 'agent_graph_claim_attributed_failure_resume_v12'
                THEN 'dbaf4bfaf17e0a647d1bf10520d4616ab7b099bfc2d30f2dd561e4a5a41de6fd'
              WHEN 'agent_graph_complete_claimed_failure_child_v12'
                THEN '6600b78c7cd407864b04965b21c9b2559d2134f49236895eaa641b224e4c76f2'
              WHEN 'agent_graph_complete_claimed_failure_parent_and_seal_v12'
                THEN '8ed2cb0c6fec62169c868097decfa9ddf577b1a0316f6e8f0772bb49af88abb0'
              ELSE NULL
            END
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = CASE
            WHEN procedure.proname IN (
                'agent_graph_complete_claimed_failure_child_v12',
                'agent_graph_complete_claimed_failure_parent_and_seal_v12')
              THEN 'emergeos_terminal_owner'
            ELSE 'emergeos_pack010_schema_owner'
          END
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V12 terminal resume definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v13',
              'agent_graph_require_provider_validation_v13',
              'agent_graph_stage_provider_validation_v13',
              'agent_graph_commit_provider_validation_v13',
              'agent_graph_assert_provider_validation_v13')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v13'
                THEN '864d95ca17378cd9cdd1beba355a846900640be21cbff97a0b9894c7c99ba2fd'
              WHEN 'agent_graph_require_provider_validation_v13'
                THEN 'befee76a15fd634d8fc1b49838df8641dc68b9ea25535b45a1e54a2419ac1c32'
              WHEN 'agent_graph_stage_provider_validation_v13'
                THEN '703349b8401a6b70f99df436fe425d5d1641e6552f4875ef8e9e7a084163d3d7'
              WHEN 'agent_graph_commit_provider_validation_v13'
                THEN '2e2de1a391d9d069763927356683bb3d2ce1db52aa9a39496bb388fdc57a71e4'
              WHEN 'agent_graph_assert_provider_validation_v13'
                THEN '49b95c2e4cb9f9de7de818bbe8bcaae196ade855ab179d38a8657dc0ca63521a'
              ELSE NULL
            END
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v13'
                THEN 'hash_domain character varying, hash_fields text[]'
              WHEN 'agent_graph_require_provider_validation_v13'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_key_id character varying, checked_transport_profile character, checked_parser_profile character, checked_schema_profile character, checked_challenge_ttl_millis integer'
              WHEN 'agent_graph_stage_provider_validation_v13'
                THEN 'payload jsonb'
              WHEN 'agent_graph_commit_provider_validation_v13'
                THEN 'payload jsonb'
              WHEN 'agent_graph_assert_provider_validation_v13' THEN ''
              ELSE NULL
            END
          AND pg_catalog.pg_get_function_result(procedure.oid) =
            CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v13' THEN 'character'
              WHEN 'agent_graph_require_provider_validation_v13'
                THEN 'character'
              WHEN 'agent_graph_stage_provider_validation_v13'
                THEN 'TABLE(protocol_version character varying, database_name character varying, database_oid oid, schema_oid oid, attestor_role_oid oid, principal_id character varying, attempt_id character, manifest_hash character, revision character varying, session_intent_hash character, session_expires_at timestamp with time zone, policy_sequence integer, policy_head_hash character, key_id character varying, key_fingerprint character, transport_profile_hash character, parser_profile_hash character, schema_profile_hash character, validation_nonce uuid, issued_at timestamp with time zone, expires_at timestamp with time zone, policy_hash character, challenge_hash character, transcript_hash character, public_key_der bytea)'
              WHEN 'agent_graph_commit_provider_validation_v13'
                THEN 'jsonb'
              WHEN 'agent_graph_assert_provider_validation_v13'
                THEN 'trigger'
              ELSE NULL
            END
          AND procedure.prosecdef =
                (procedure.proname <>
                    'agent_graph_framed_sha256_v13')
          AND procedure.provolatile = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v13' THEN 'i'::"char"
              ELSE 'v'::"char"
            END
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.proretset =
                (procedure.proname =
                    'agent_graph_stage_provider_validation_v13')
          AND procedure.proisstrict =
                (procedure.proname =
                    'agent_graph_framed_sha256_v13')
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = CASE
              WHEN procedure.proname IN (
                  'agent_graph_require_provider_validation_v13',
                  'agent_graph_stage_provider_validation_v13',
                  'agent_graph_commit_provider_validation_v13')
                THEN 'emergeos_terminal_owner'
              ELSE 'emergeos_pack010_schema_owner'
            END
    ) <> 5 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v13',
              'agent_graph_require_provider_validation_v13',
              'agent_graph_stage_provider_validation_v13',
              'agent_graph_commit_provider_validation_v13',
              'agent_graph_assert_provider_validation_v13')
    ) <> 5 THEN
        RAISE EXCEPTION 'Pack010 V13 provider validation definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v14',
              'agent_graph_assert_provider_statement_v14')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v14'
                THEN 'hash_domain character varying, hash_fields text[]'
              ELSE 'payload jsonb'
            END
          AND pg_catalog.pg_get_function_result(procedure.oid)
                = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v14' THEN 'character'
              ELSE 'TABLE(profile_hash character, observed_cost_pico_usd numeric, statement_hash character)'
            END
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v14'
                THEN 'e0be5f17a805405befc5630145182c73ff8b325522b4ac00ee341acc58bd1edf'
              WHEN 'agent_graph_assert_provider_statement_v14'
                THEN '4f2cfa38fa65f1c8bf75f47c5f90558c13b5c8bbfbb4f1d5f7a9fc43c4bee950'
              ELSE NULL
            END
          AND procedure.prosecdef =
                (procedure.proname =
                    'agent_graph_assert_provider_statement_v14')
          AND procedure.provolatile = CASE procedure.proname
              WHEN 'agent_graph_framed_sha256_v14' THEN 'i'::"char"
              ELSE 'v'::"char"
            END
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.proretset =
                (procedure.proname =
                    'agent_graph_assert_provider_statement_v14')
          AND procedure.proisstrict =
                (procedure.proname = 'agent_graph_framed_sha256_v14')
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = CASE procedure.proname
              WHEN 'agent_graph_assert_provider_statement_v14'
                THEN 'emergeos_terminal_owner'
              ELSE 'emergeos_pack010_schema_owner'
            END
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V14 provider profile definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_exact_tx_a_v15',
              'agent_graph_assert_exact_tx_a_requirement_v15')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_require_exact_tx_a_v15'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_profile_id character varying'
              ELSE ''
            END
          AND pg_catalog.pg_get_function_result(procedure.oid)
                = CASE procedure.proname
              WHEN 'agent_graph_require_exact_tx_a_v15'
                THEN 'character'
              ELSE 'trigger'
            END
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') = CASE procedure.proname
              WHEN 'agent_graph_require_exact_tx_a_v15'
                THEN '01207450245d44045b895a864c34ddf5704f47b172162d6ed4deb0c3b7478026'
              ELSE '26c40c69c18c6b5f680abb5fc6789905a5f41774122264dd8962c1559a4e77fd'
            END
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_terminal_owner'
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_exact_tx_a_v15',
              'agent_graph_assert_exact_tx_a_requirement_v15')
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc guard
          ON guard.oid = trigger.tgfoid
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = guard.proowner
        JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = 'public'
          AND NOT trigger.tgisinternal
          AND relation.relname <> ALL(ARRAY[
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16']::NAME[])
          AND relation.relname IN (
              'agent_graph_exact_tx_a_requirements_v15',
              'agent_graph_attempt_provider_attributions',
              'agent_graph_attempt_events',
              'agent_graph_attempt_heads')
          AND trigger.tgname = CASE relation.relname
              WHEN 'agent_graph_exact_tx_a_requirements_v15'
                THEN 'agent_graph_exact_tx_a_requirement_v15'
              WHEN 'agent_graph_attempt_provider_attributions'
                THEN 'agent_graph_exact_tx_a_attribution_v15'
              WHEN 'agent_graph_attempt_events'
                THEN 'agent_graph_exact_tx_a_event_v15'
              WHEN 'agent_graph_attempt_heads'
                THEN 'agent_graph_exact_tx_a_head_v15'
              ELSE NULL
            END
          AND trigger.tgenabled = 'O'
          AND trigger.tgtype = 29
          AND trigger.tgqual IS NULL
          AND trigger.tgnargs = 0
          AND pg_catalog.octet_length(trigger.tgargs) = 0
          AND trigger.tgattr = ''::pg_catalog.int2vector
          AND constraint_row.condeferrable
          AND constraint_row.condeferred
          AND guard.proname
                = 'agent_graph_assert_exact_tx_a_requirement_v15'
          AND pg_catalog.pg_get_function_identity_arguments(guard.oid) = ''
          AND owner.rolname = 'emergeos_terminal_owner'
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V15 exact TX-A requirement drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        SELECT count(*) = 148
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            column_shape.relname,
                            column_shape.attnum::TEXT,
                            column_shape.attname,
                            column_shape.type_name,
                            column_shape.attnotnull::TEXT,
                            column_shape.attidentity::TEXT,
                            column_shape.attgenerated::TEXT,
                            column_shape.default_expr,
                            column_shape.collation_name),
                        ',' ORDER BY column_shape.relname,
                            column_shape.attnum),
                    'UTF8')), 'hex') =
                '3299b96870e4742e7d37b4bf59103ff026b24d09b02d85013701bca2de06c2df'
        FROM (
            SELECT relation.relname,
                   attribute.attnum,
                   attribute.attname,
                   pg_catalog.format_type(
                       attribute.atttypid,
                       attribute.atttypmod) AS type_name,
                   attribute.attnotnull,
                   attribute.attidentity,
                   attribute.attgenerated,
                   COALESCE(pg_catalog.pg_get_expr(
                       default_value.adbin,
                       default_value.adrelid), '') AS default_expr,
                   COALESCE(collation_row.collname, '') AS collation_name
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation
              ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef default_value
              ON default_value.adrelid = attribute.attrelid
             AND default_value.adnum = attribute.attnum
            LEFT JOIN pg_catalog.pg_collation collation_row
              ON collation_row.oid = attribute.attcollation
            WHERE namespace.nspname = 'public'
              AND relation.relname IN (
                  'agent_graph_exact_provider_validations_v16',
                  'agent_graph_exact_provider_attributions_v16',
                  'agent_graph_exact_attempt_events_v16',
                  'agent_graph_exact_attempt_heads_v16')
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
        ) column_shape
    ) OR NOT (
        SELECT count(*) = 188
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            constraint_row.conname,
                            constraint_row.contype::TEXT,
                            constraint_row.condeferrable::TEXT,
                            constraint_row.condeferred::TEXT,
                            constraint_row.convalidated::TEXT,
                            pg_catalog.pg_get_constraintdef(
                                constraint_row.oid, true)),
                        ',' ORDER BY relation.relname,
                            constraint_row.conname),
                    'UTF8')), 'hex') =
                '884c50b44a54c53f1c39b8dc0403726140221895f40332032e66592a641465a7'
        FROM pg_catalog.pg_constraint constraint_row
        JOIN pg_catalog.pg_class relation
          ON relation.oid = constraint_row.conrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
    ) OR NOT (
        SELECT count(*) = 13
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            index_relation.relname,
                            index_row.indisunique::TEXT,
                            index_row.indisprimary::TEXT,
                            index_row.indisvalid::TEXT,
                            index_row.indisready::TEXT,
                            pg_catalog.pg_get_indexdef(
                                index_relation.oid)),
                        ',' ORDER BY relation.relname,
                            index_relation.relname),
                    'UTF8')), 'hex') =
                'e1b91b24b602affd4e457ded6a3e14c0c5e4207ac25262c91fbe247dd6932bca'
        FROM pg_catalog.pg_index index_row
        JOIN pg_catalog.pg_class index_relation
          ON index_relation.oid = index_row.indexrelid
        JOIN pg_catalog.pg_class relation
          ON relation.oid = index_row.indrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
    ) OR NOT (
        SELECT count(*) = 4
           AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    pg_catalog.string_agg(
                        pg_catalog.concat_ws('|',
                            relation.relname,
                            relation.relkind::TEXT,
                            relation.relpersistence::TEXT,
                            relation.relrowsecurity::TEXT,
                            relation.relforcerowsecurity::TEXT,
                            relation.relreplident::TEXT),
                        ',' ORDER BY relation.relname),
                    'UTF8')), 'hex') =
                'fd1821d10250508f87ac44c3ed7e48772631b1f1017085aa839a86b7a2bc4255'
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
    ) THEN
        RAISE EXCEPTION 'Pack010 V16 exact-pico relation topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
          AND relation.relkind = 'r'
          AND relation.relpersistence = 'p'
          AND NOT relation.relrowsecurity
          AND NOT relation.relforcerowsecurity
    ) <> 4 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_stage_exact_tx_a_v16',
              'agent_graph_commit_exact_tx_a_v16',
              'agent_graph_assert_exact_tx_a_overlay_v16')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_assert_exact_tx_a_overlay_v16' THEN ''
              ELSE 'payload jsonb'
            END
          AND (
              (procedure.proname = 'agent_graph_stage_exact_tx_a_v16'
               AND pg_catalog.octet_length(
                     pg_catalog.pg_get_function_result(procedure.oid)) = 1879
               AND pg_catalog.md5(
                     pg_catalog.pg_get_function_result(procedure.oid))
                     = 'b53651040ad5e8695dd553dbdf34adcc')
              OR (procedure.proname = 'agent_graph_commit_exact_tx_a_v16'
                  AND pg_catalog.pg_get_function_result(procedure.oid)
                        = 'jsonb')
              OR (procedure.proname
                    = 'agent_graph_assert_exact_tx_a_overlay_v16'
                  AND pg_catalog.pg_get_function_result(procedure.oid)
                        = 'trigger'))
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') = CASE procedure.proname
              WHEN 'agent_graph_stage_exact_tx_a_v16'
                THEN '593aab6d426090ea11d82970100ac90cbb2840463a7919030ac4b5fc970a5cf8'
              WHEN 'agent_graph_commit_exact_tx_a_v16'
                THEN '7d15cc5f1e613da201ecd2219e85d522bde2ce671354fdf1eb112f43064af07b'
              ELSE '5083d24c002811e58d819b638e02ec2e83ba0c8f783c41362beef84ffb09de3f'
            END
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.proretset =
                (procedure.proname = 'agent_graph_stage_exact_tx_a_v16')
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_terminal_owner'
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_stage_exact_tx_a_v16',
              'agent_graph_commit_exact_tx_a_v16',
              'agent_graph_assert_exact_tx_a_overlay_v16')
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc guard
          ON guard.oid = trigger.tgfoid
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = guard.proowner
        JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = 'public'
          AND NOT trigger.tgisinternal
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
          AND trigger.tgenabled = 'O'
          AND trigger.tgtype = 29
          AND trigger.tgqual IS NULL
          AND trigger.tgnargs = 0
          AND pg_catalog.octet_length(trigger.tgargs) = 0
          AND trigger.tgattr = ''::pg_catalog.int2vector
          AND constraint_row.condeferrable
          AND constraint_row.condeferred
          AND guard.proname
                = 'agent_graph_assert_exact_tx_a_overlay_v16'
          AND owner.rolname = 'emergeos_terminal_owner'
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V16 exact-pico overlay drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_complete_child_v9',
              'agent_graph_complete_child_v10',
              'agent_graph_complete_parent_and_seal_v9',
              'agent_graph_complete_parent_and_seal_v10')
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'payload jsonb'
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.prorettype = 'jsonb'::regtype
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_terminal_owner'
    ) <> 4 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_complete_child_v9',
              'agent_graph_complete_child_v10',
              'agent_graph_complete_parent_and_seal_v9',
              'agent_graph_complete_parent_and_seal_v10')
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                <> 'payload jsonb'
    ) THEN
        RAISE EXCEPTION 'Pack010 protected function topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND owner.rolname <> CASE
            WHEN procedure.proname IN (
                'agent_graph_complete_child_v9',
                'agent_graph_complete_child_v10',
                'agent_graph_complete_parent_and_seal_v9',
                'agent_graph_complete_parent_and_seal_v10',
                'agent_graph_complete_claimed_failure_child_v12',
                'agent_graph_complete_claimed_failure_parent_and_seal_v12',
                'agent_graph_require_provider_validation_v13',
                'agent_graph_stage_provider_validation_v13',
                'agent_graph_commit_provider_validation_v13',
                'agent_graph_assert_provider_statement_v14',
                'agent_graph_require_exact_tx_a_v15',
                'agent_graph_assert_exact_tx_a_requirement_v15',
                'agent_graph_stage_exact_tx_a_v16',
                'agent_graph_commit_exact_tx_a_v16',
                'agent_graph_assert_exact_tx_a_overlay_v16')
              THEN 'emergeos_terminal_owner'
            ELSE 'emergeos_pack010_schema_owner'
          END
    ) THEN
        RAISE EXCEPTION 'Pack010 function owner drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_row_shape_v9',
              'agent_graph_require_executor_v9',
              'agent_graph_require_executor_v10',
              'agent_graph_run_selector_guard_v10')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
            WHEN 'agent_graph_require_row_shape_v9'
              THEN 'supplied jsonb, expected_relation regclass, supplied_is_array boolean'
            WHEN 'agent_graph_require_executor_v9'
              THEN 'expected_function regprocedure'
            WHEN 'agent_graph_require_executor_v10'
              THEN 'expected_function regprocedure'
            WHEN 'agent_graph_run_selector_guard_v10' THEN ''
            ELSE NULL
          END
          AND NOT procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.prorettype = CASE procedure.proname
            WHEN 'agent_graph_run_selector_guard_v10'
              THEN 'trigger'::regtype
            ELSE 'void'::regtype
          END
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 4 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_row_shape_v9',
              'agent_graph_require_executor_v9',
              'agent_graph_require_executor_v10',
              'agent_graph_run_selector_guard_v10')
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V9/V10 helper topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_assert_failure_terminal_resume_v12')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
              WHEN 'agent_graph_require_failure_resumer_v12'
                THEN '814c2d1a58013749d0441f07411e0065c14acc1d9523bc1ea8d8505218573920'
              WHEN 'agent_graph_assert_failure_terminal_resume_v12'
                THEN 'fdad487040c702df9b58481e30158a96e1564963babd86b9d4a0d8e39bceebc3'
              ELSE NULL
            END
          AND procedure.prosecdef =
                (procedure.proname =
                  'agent_graph_assert_failure_terminal_resume_v12')
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND NOT procedure.proretset
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V12 helper topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND pg_catalog.md5(procedure.prosrc) = CASE procedure.proname
            WHEN 'agent_graph_complete_child_v9'
              THEN 'd16610fd93141a98485a1fd541ca1fe9'
            WHEN 'agent_graph_complete_child_v10'
              THEN '26d1087933677c65697c73e14103c973'
            WHEN 'agent_graph_complete_parent_and_seal_v9'
              THEN 'ac438c4b71c8f044c9189b8be35db918'
            WHEN 'agent_graph_complete_parent_and_seal_v10'
              THEN '3bb316c246faa7f28ac8dc2560e45342'
            WHEN 'agent_graph_require_executor_v9'
                THEN '90f8d826c5a2dbd8851f469322500d36'
            WHEN 'agent_graph_require_executor_v10'
                THEN '43a27e4878e7af5a5f7d5416e9f2518f'
            WHEN 'agent_graph_require_row_shape_v9'
              THEN '865db3bb7b9d040e21e7e67fa4592980'
            WHEN 'agent_graph_run_selector_guard_v10'
              THEN '19e62fb3f842ec2b3ea3df3a174df34c'
            ELSE NULL
          END
    ) <> 8 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_graph_run_selector_guard_v10',
              'agent_graph_require_row_shape_v9',
              'agent_graph_require_executor_v9',
              'agent_graph_require_executor_v10',
              'agent_graph_complete_child_v9',
              'agent_graph_complete_child_v10',
              'agent_graph_complete_parent_and_seal_v9',
              'agent_graph_complete_parent_and_seal_v10')
    ) <> 8 THEN
        RAISE EXCEPTION 'Pack010 V9/V10 function body fingerprint drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
              = CASE procedure.proname
                WHEN 'agent_graph_complete_child_v10'
                  THEN 'db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae'
                WHEN 'agent_graph_complete_parent_and_seal_v10'
                  THEN '57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35'
                ELSE NULL
              END
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V10 semantic SHA-256 fingerprint drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal character varying, checked_attempt character'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
          AND pg_catalog.md5(procedure.prosrc) = CASE procedure.proname
            WHEN 'agent_assert_graph_attempt_v7'
              THEN 'f56dd001c4efeef7afd53759b93fd0bb'
            WHEN 'agent_assert_graph_attempt_prefix_v7'
              THEN '62a4701366eac48468df6bb1af5fba75'
            WHEN 'agent_assert_graph_attempt_v8'
              THEN '313c6c25c245065f6a826b90aa5b8cfb'
            ELSE NULL
          END
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_assert_graph_attempt_v7',
              'agent_assert_graph_attempt_prefix_v7',
              'agent_assert_graph_attempt_v8')
    ) <> 3 THEN
        RAISE EXCEPTION 'Pack010 assertion closure definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND procedure.proname = 'agent_assert_worker_graph_v6'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal_id character varying, checked_run_id character varying'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
          AND pg_catalog.md5(procedure.prosrc)
                = '7d9bf040a89092662f6fab37e12ab3c3'
    ) THEN
        RAISE EXCEPTION 'Pack010 worker assertion definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
          AND pg_catalog.md5(procedure.prosrc) = CASE procedure.proname
            WHEN 'agent_graph_utf16_length_v8'
              THEN 'f3785ba550ab7e86ce5b3e91e71baef4'
            WHEN 'agent_graph_terminal_run_valid_v8'
              THEN 'b5fa7ea4af7f0724f6383f5ed11ec9e6'
            ELSE NULL
          END
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 terminal validation helper drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = 'public'
          AND procedure.proname = 'agent_graph_authorize_terminal_v8'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal character varying, checked_attempt character, checked_role character varying, checked_run character varying'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
          AND pg_catalog.md5(procedure.prosrc)
                = 'b18b53e8dd9ab4e6219bad8c7382d49d'
    ) THEN
        RAISE EXCEPTION 'Pack010 terminal authorization helper drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT pg_catalog.encode(
            pg_catalog.sha256(
                pg_catalog.convert_to(
                    COALESCE(
                        pg_catalog.string_agg(
                            pg_catalog.concat_ws(
                                '|',
                                relation.relname,
                                trigger.tgname,
                                trigger.tgenabled,
                                trigger.tgtype::TEXT,
                                trigger.tgattr::TEXT,
                                COALESCE(
                                    pg_catalog.pg_get_expr(
                                        trigger.tgqual,
                                        trigger.tgrelid),
                                    ''),
                                pg_catalog.encode(trigger.tgargs, 'hex'),
                                (trigger.tgconstraint <> 0)::TEXT,
                                COALESCE(
                                    constraint_row.condeferrable,
                                    FALSE)::TEXT,
                                COALESCE(
                                    constraint_row.condeferred,
                                    FALSE)::TEXT,
                                procedure.proname || '('
                                    || pg_catalog.pg_get_function_identity_arguments(
                                            procedure.oid)
                                    || ')'),
                            ',' ORDER BY relation.relname, trigger.tgname),
                        ''),
                    'UTF8')),
            'hex')
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc procedure
          ON procedure.oid = trigger.tgfoid
        LEFT JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = 'public'
          AND NOT trigger.tgisinternal
    ) <> expected_trigger_topology
    THEN
        RAISE EXCEPTION 'Pack010 full trigger topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc guard
          ON guard.oid = trigger.tgfoid
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = guard.proowner
        JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = 'public'
          AND NOT trigger.tgisinternal
          AND trigger.tgname = CASE relation.relname
              WHEN 'agent_graph_provider_validations'
                THEN 'agent_graph_provider_validation_state_v13'
              WHEN 'agent_graph_attempt_provider_attributions'
                THEN 'agent_graph_provider_validation_attribution_v13'
              WHEN 'agent_graph_attempt_events'
                THEN 'agent_graph_provider_validation_event_v13'
              WHEN 'agent_graph_attempt_heads'
                THEN 'agent_graph_provider_validation_head_v13'
              WHEN 'agent_graph_attributed_failure_outcomes'
                THEN 'agent_graph_provider_validation_failure_v13'
              ELSE NULL
            END
          AND trigger.tgenabled = 'O'
          AND trigger.tgtype = 29
          AND trigger.tgqual IS NULL
          AND trigger.tgnargs = 0
          AND pg_catalog.octet_length(trigger.tgargs) = 0
          AND trigger.tgattr = ''::pg_catalog.int2vector
          AND constraint_row.condeferrable
          AND constraint_row.condeferred
          AND guard.proname =
                'agent_graph_assert_provider_validation_v13'
          AND pg_catalog.pg_get_function_identity_arguments(
                guard.oid) = ''
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 5 THEN
        RAISE EXCEPTION 'Pack010 V13 trigger topology drift'
            USING ERRCODE = '55000';
    END IF;
    IF NOT (
        SELECT count(*) = 19
           AND pg_catalog.encode(
                pg_catalog.sha256(
                    pg_catalog.convert_to(
                        pg_catalog.string_agg(
                            procedure.proname || '('
                                || pg_catalog.pg_get_function_identity_arguments(
                                        procedure.oid)
                                || ')' || ':'
                                || pg_catalog.pg_get_function_result(
                                    procedure.oid)
                                || ':' || procedure.prosecdef::TEXT
                                || ':' || procedure.provolatile::TEXT
                                || ':' || procedure.proparallel::TEXT
                                || ':' || procedure.prokind::TEXT
                                || ':' || procedure.proretset::TEXT
                                || ':' || procedure.proisstrict::TEXT
                                || ':' || procedure.proleakproof::TEXT
                                || ':' || language.lanname
                                || ':' || pg_catalog.array_to_string(
                                    procedure.proconfig, ';')
                                || ':' || pg_catalog.encode(
                                    pg_catalog.sha256(
                                        pg_catalog.convert_to(
                                            procedure.prosrc, 'UTF8')),
                                    'hex'),
                            ',' ORDER BY procedure.proname),
                        'UTF8')),
                'hex')
             = 'bb6f50aa547584083dfc2f7d797cc2a165161521f53cb6342084fcf0e0eab83b'
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = 'public'
          AND procedure.proname IN (
              'agent_assert_graph_attempt_v7',
              'agent_assert_graph_attempt_v8',
              'agent_assert_worker_graph_v6',
              'agent_graph_assert_failure_terminal_resume_v12',
              'agent_graph_assert_row_v7',
              'agent_graph_assert_row_v8',
              'agent_graph_assert_run_dependency_v8',
              'agent_graph_assert_run_row_v7',
              'agent_graph_authorize_terminal_v8',
              'agent_graph_head_transition_guard_v8',
              'agent_graph_require_executor_v9',
              'agent_graph_require_executor_v10',
              'agent_graph_require_failure_resumer_v12',
              'agent_graph_require_row_shape_v9',
              'agent_graph_run_selector_guard_v8',
              'agent_graph_run_selector_guard_v10',
              'agent_graph_terminal_run_valid_v8',
              'agent_graph_utf16_length_v8',
              'agent_worker_graph_guard_v6')
    ) THEN
        RAISE EXCEPTION 'Pack010 transitive helper closure drift'
            USING ERRCODE = '55000';
    END IF;

    IF (
        SELECT count(*)
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc guard
          ON guard.oid = trigger.tgfoid
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = guard.proowner
        WHERE namespace.nspname = 'public'
          AND relation.relname = 'agent_runs'
          AND trigger.tgname = 'agent_graph_run_selector_guard_v10'
          AND NOT trigger.tgisinternal
          AND trigger.tgenabled = 'O'
          AND trigger.tgtype = 31
          AND trigger.tgqual IS NULL
          AND trigger.tgnargs = 0
          AND pg_catalog.octet_length(trigger.tgargs) = 0
          AND trigger.tgattr = ''::pg_catalog.int2vector
          AND guard.proname = 'agent_graph_run_selector_guard_v10'
          AND NOT guard.prosecdef
          AND guard.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_pack010_schema_owner'
    ) <> 1 THEN
        RAISE EXCEPTION 'Pack010 trigger definition drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND relation.relname
                = 'agent_graph_provider_session_intents'
          AND NOT trigger.tgisinternal
    ) THEN
        RAISE EXCEPTION 'Pack010 provider-session trigger drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN unnest(runtime_roles) runtime(role_name)
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
          AND (
            pg_catalog.has_table_privilege(
                runtime.role_name, relation.oid, 'SELECT')
              <> (relation.relname = ANY(product_relations)
                  AND (runtime.role_name = 'emergeos_terminal_owner'
                       OR (runtime.role_name IN (
                              'emergeos_graph_prefix_writer',
                              'emergeos_graph_reader')
                           AND relation.relname <>
                              ALL(restricted_relations))
                       OR (runtime.role_name
                              = 'emergeos_exact_overlay_reader_v19'
                           AND relation.relname
                              = ANY(exact_overlay_reader_relations))))
            OR pg_catalog.has_table_privilege(
                runtime.role_name, relation.oid, 'INSERT')
              <> (relation.relname = ANY(product_relations)
                  AND ((runtime.role_name = 'emergeos_terminal_owner'
                        AND relation.relname <> ALL(ARRAY[
                          'agent_graph_provider_validation_keys',
                          'agent_graph_provider_profiles_v14']::NAME[]))
                       OR (runtime.role_name
                              = 'emergeos_graph_prefix_writer'
                           AND relation.relname
                              = ANY(prefix_insert_relations))))
            OR pg_catalog.has_table_privilege(
                runtime.role_name, relation.oid, 'UPDATE')
              <> (relation.relname = ANY(product_relations)
                  AND ((runtime.role_name = 'emergeos_terminal_owner'
                        AND relation.relname <> ALL(ARRAY[
                          'agent_graph_provider_validation_keys',
                          'agent_graph_provider_profiles_v14',
                          'agent_graph_exact_tx_a_requirements_v15',
                          'agent_graph_exact_provider_attributions_v16',
                          'agent_graph_exact_attempt_events_v16',
                          'agent_graph_exact_attempt_heads_v16']::NAME[]))
                       OR (runtime.role_name
                              = 'emergeos_graph_prefix_writer'
                           AND relation.relname
                              = 'agent_graph_attempt_heads')))
            OR pg_catalog.has_table_privilege(
                runtime.role_name, relation.oid,
                'DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
            OR pg_catalog.has_table_privilege(
                runtime.role_name, relation.oid,
                'SELECT WITH GRANT OPTION,INSERT WITH GRANT OPTION,'
                  || 'UPDATE WITH GRANT OPTION,DELETE WITH GRANT OPTION,'
                  || 'TRUNCATE WITH GRANT OPTION,'
                  || 'REFERENCES WITH GRANT OPTION,'
                  || 'TRIGGER WITH GRANT OPTION,'
                  || 'MAINTAIN WITH GRANT OPTION')
          )
    ) OR (
        SELECT count(*)
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                relation.relacl,
                pg_catalog.acldefault('r', relation.relowner))) acl
        JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
          AND grantee.rolname = 'emergeos_exact_overlay_reader_v19'
          AND acl.privilege_type = 'SELECT'
          AND NOT acl.is_grantable
    ) <> 13 OR EXISTS (
        WITH exact_sequences AS MATERIALIZED (
            SELECT relation.oid
            FROM pg_catalog.pg_sequences sequence_row
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.nspname = sequence_row.schemaname
            JOIN pg_catalog.pg_class relation
              ON relation.relnamespace = namespace.oid
             AND relation.relname = sequence_row.sequencename
            WHERE sequence_row.schemaname = 'public'
        )
        SELECT 1
        FROM exact_sequences sequence_row
        CROSS JOIN unnest(runtime_roles) runtime(role_name)
        WHERE pg_catalog.has_sequence_privilege(
              runtime.role_name, sequence_row.oid, 'USAGE,SELECT,UPDATE')
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute attribute
        JOIN pg_catalog.pg_class relation
          ON relation.oid = attribute.attrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'public'
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
          AND attribute.attacl IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Pack010 exact relation ACL drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN unnest(runtime_roles) runtime(role_name)
        WHERE namespace.nspname = 'public'
          AND pg_catalog.has_function_privilege(
                runtime.role_name, procedure.oid, 'EXECUTE')
              <> (
                (runtime.role_name IN (
                    'emergeos_terminal_owner',
                    'emergeos_graph_executor',
                    'emergeos_failure_resumer',
                    'emergeos_provider_attestor')
                 AND procedure.oid = pg_catalog.to_regprocedure(
                   'public.emergeos_pack010_schema_version_v1()'))
                OR (runtime.role_name = 'emergeos_graph_executor'
                 AND procedure.proname IN (
                      'agent_graph_complete_child_v10',
                      'agent_graph_complete_parent_and_seal_v10')
                 AND pg_catalog.pg_get_function_identity_arguments(
                       procedure.oid) = 'payload jsonb')
                OR (runtime.role_name = 'emergeos_failure_resumer'
                    AND (
                      (procedure.proname
                         = 'agent_graph_claim_attributed_failure_resume_v12'
                       AND procedure.oid = pg_catalog.to_regprocedure(
                         'public.agent_graph_claim_attributed_failure_resume_v12(character varying,character,character,character,bigint,character varying,character,integer)'))
                      OR (procedure.proname
                         = 'agent_graph_read_attributed_failure_resume_v12'
                       AND procedure.oid = pg_catalog.to_regprocedure(
                         'public.agent_graph_read_attributed_failure_resume_v12(character varying,character,character)'))
                      OR (procedure.proname
                         = 'agent_graph_complete_claimed_failure_child_v12'
                       AND procedure.oid = pg_catalog.to_regprocedure(
                         'public.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'))
                      OR (procedure.proname
                         = 'agent_graph_complete_claimed_failure_parent_and_seal_v12'
                       AND procedure.oid = pg_catalog.to_regprocedure(
                         'public.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)'))))
                OR (runtime.role_name = 'emergeos_provider_attestor'
                    AND procedure.oid IN (
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_require_provider_validation_v13(character varying,character,character,character varying,character,character,character,integer)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_stage_provider_validation_v13(jsonb)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_commit_provider_validation_v13(jsonb)')))
                OR (runtime.role_name = 'emergeos_provider_attestor_v14'
                    AND procedure.oid = pg_catalog.to_regprocedure(
                      'public.agent_graph_assert_provider_statement_v14(jsonb)'))
                OR (runtime.role_name = 'emergeos_provider_attestor_v15'
                    AND procedure.oid = pg_catalog.to_regprocedure(
                      'public.agent_graph_require_exact_tx_a_v15(character varying,character,character,character varying)'))
                OR (runtime.role_name = 'emergeos_provider_attestor_v16'
                    AND procedure.oid IN (
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_stage_exact_tx_a_v16(jsonb)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_commit_exact_tx_a_v16(jsonb)')))
                OR (runtime.role_name = 'emergeos_terminal_owner'
                    AND (
                      (procedure.proname IN (
                         'agent_graph_complete_child_v9',
                         'agent_graph_complete_child_v10',
                         'agent_graph_complete_parent_and_seal_v9',
                         'agent_graph_complete_parent_and_seal_v10')
                       AND pg_catalog.pg_get_function_identity_arguments(
                             procedure.oid) = 'payload jsonb')
                      OR (procedure.proname IN (
                         'agent_graph_complete_claimed_failure_child_v12',
                         'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                       AND pg_catalog.pg_get_function_identity_arguments(
                             procedure.oid) =
                         'payload jsonb, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character')
                      OR procedure.oid IN (
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_require_provider_validation_v13(character varying,character,character,character varying,character,character,character,integer)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_stage_provider_validation_v13(jsonb)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_commit_provider_validation_v13(jsonb)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_framed_sha256_v13(character varying,text[])'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_framed_sha256_v14(character varying,text[])'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_assert_provider_statement_v14(jsonb)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_require_exact_tx_a_v15(character varying,character,character,character varying)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_assert_exact_tx_a_requirement_v15()'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_stage_exact_tx_a_v16(jsonb)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_commit_exact_tx_a_v16(jsonb)'),
                        pg_catalog.to_regprocedure(
                          'public.agent_graph_assert_exact_tx_a_overlay_v16()'))
                      OR (procedure.proname IN (
                         'agent_graph_run_selector_guard_v10',
                         'agent_graph_require_row_shape_v9',
                         'agent_graph_require_executor_v9',
                         'agent_graph_require_executor_v10',
                         'agent_graph_require_failure_resumer_v12',
                         'agent_assert_graph_attempt_v7',
                         'agent_assert_graph_attempt_prefix_v7',
                         'agent_assert_graph_attempt_v8',
                         'agent_assert_worker_graph_v6',
                         'agent_graph_terminal_run_valid_v8',
                         'agent_graph_utf16_length_v8',
                         'agent_graph_authorize_terminal_v8')
                       AND pg_catalog.pg_get_function_identity_arguments(
                             procedure.oid) = CASE procedure.proname
                      WHEN 'agent_graph_run_selector_guard_v10' THEN ''
                      WHEN 'agent_graph_require_row_shape_v9'
                        THEN 'supplied jsonb, expected_relation regclass, supplied_is_array boolean'
                      WHEN 'agent_graph_require_executor_v9'
                        THEN 'expected_function regprocedure'
                      WHEN 'agent_graph_require_executor_v10'
                        THEN 'expected_function regprocedure'
                      WHEN 'agent_graph_require_failure_resumer_v12'
                        THEN 'expected_function regprocedure'
                      WHEN 'agent_assert_graph_attempt_v7'
                        THEN 'checked_principal character varying, checked_attempt character'
                      WHEN 'agent_assert_graph_attempt_prefix_v7'
                        THEN 'checked_principal character varying, checked_attempt character'
                      WHEN 'agent_assert_graph_attempt_v8'
                        THEN 'checked_principal character varying, checked_attempt character'
                      WHEN 'agent_assert_worker_graph_v6'
                        THEN 'checked_principal_id character varying, checked_run_id character varying'
                      WHEN 'agent_graph_terminal_run_valid_v8'
                        THEN 'checked_principal character varying, checked_attempt character, checked_role character varying'
                      WHEN 'agent_graph_utf16_length_v8'
                        THEN 'input text'
                      WHEN 'agent_graph_authorize_terminal_v8'
                        THEN 'checked_principal character varying, checked_attempt character, checked_role character varying, checked_run character varying'
                         ELSE NULL
                       END)))
                OR (runtime.role_name = 'emergeos_graph_prefix_writer'
                    AND procedure.proname IN (
                      'agent_assert_graph_attempt_v7',
                      'agent_assert_graph_attempt_prefix_v7',
                      'agent_assert_graph_attempt_v8',
                      'agent_assert_worker_graph_v6',
                      'agent_graph_terminal_run_valid_v8',
                      'agent_graph_record_attributed_failure_v11')
                    AND pg_catalog.pg_get_function_identity_arguments(
                          procedure.oid) = CASE procedure.proname
                      WHEN 'agent_assert_worker_graph_v6'
                        THEN 'checked_principal_id character varying, checked_run_id character varying'
                      WHEN 'agent_graph_terminal_run_valid_v8'
                        THEN 'checked_principal character varying, checked_attempt character, checked_role character varying'
                      WHEN 'agent_graph_record_attributed_failure_v11'
                        THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_previous_sequence integer, checked_previous_head character, checked_failure_code character varying'
                      ELSE 'checked_principal character varying, checked_attempt character'
                    END))
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner))) acl
        WHERE namespace.nspname = 'public'
          AND acl.is_grantable
          AND acl.grantee <> procedure.proowner
    ) THEN
        RAISE EXCEPTION 'Pack010 exact function ACL drift: %', (
            SELECT pg_catalog.string_agg(
                runtime.role_name || ':' || procedure.oid::regprocedure::TEXT
                  || ':'
                  || pg_catalog.has_function_privilege(
                        runtime.role_name, procedure.oid,
                        'EXECUTE')::TEXT,
                ',' ORDER BY runtime.role_name, procedure.proname)
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            CROSS JOIN unnest(runtime_roles) runtime(role_name)
            WHERE namespace.nspname = 'public'
              AND pg_catalog.has_function_privilege(
                    runtime.role_name, procedure.oid, 'EXECUTE'))
            USING ERRCODE = '55000';
    END IF;
    -- Dedicated-database allowlist: every explicit non-owner ACL entry must
    -- be one of the exact Pack010 grants below.  Checking only the managed
    -- roles is insufficient because an unrelated LOGIN role could otherwise
    -- retain equivalent raw SQL authority.
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_database database
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                database.datacl,
                pg_catalog.acldefault('d', database.datdba))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE database.datname = current_database()
          AND acl.grantee <> database.datdba
          AND NOT (
            grantee.rolname IN (
                'emergeos_graph_executor',
                'emergeos_failure_resumer',
                'emergeos_provider_attestor',
                'emergeos_provider_attestor_v14',
                'emergeos_provider_attestor_v15',
                'emergeos_provider_attestor_v16',
                'emergeos_graph_prefix_writer',
                'emergeos_graph_reader',
                'emergeos_exact_overlay_reader_v19')
            AND acl.privilege_type = 'CONNECT'
            AND NOT acl.is_grantable)
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                namespace.nspacl,
                pg_catalog.acldefault('n', namespace.nspowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND acl.grantee <> namespace.nspowner
          AND NOT (
            grantee.rolname = ANY(runtime_roles)
            AND acl.privilege_type = 'USAGE'
            AND NOT acl.is_grantable)
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                relation.relacl,
                pg_catalog.acldefault('r', relation.relowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
          AND acl.grantee <> relation.relowner
          AND NOT (
            NOT acl.is_grantable
            AND relation.relname = ANY(product_relations)
            AND (
              (grantee.rolname = 'emergeos_terminal_owner'
               AND (acl.privilege_type = 'SELECT'
                    OR (acl.privilege_type = 'INSERT'
                        AND relation.relname <> ALL(ARRAY[
                          'agent_graph_provider_validation_keys',
                          'agent_graph_provider_profiles_v14']::NAME[]))
                    OR (acl.privilege_type = 'UPDATE'
                        AND relation.relname <> ALL(ARRAY[
                          'agent_graph_provider_validation_keys',
                          'agent_graph_provider_profiles_v14',
                          'agent_graph_exact_tx_a_requirements_v15',
                          'agent_graph_exact_provider_attributions_v16',
                          'agent_graph_exact_attempt_events_v16',
                          'agent_graph_exact_attempt_heads_v16']::NAME[]))))
              OR (grantee.rolname = 'emergeos_graph_prefix_writer'
                  AND (acl.privilege_type = 'SELECT'
                       AND relation.relname <>
                           ALL(restricted_relations)
                       OR (acl.privilege_type = 'INSERT'
                           AND relation.relname
                               = ANY(prefix_insert_relations))
                       OR (acl.privilege_type = 'UPDATE'
                           AND relation.relname
                               = 'agent_graph_attempt_heads')))
              OR (grantee.rolname = 'emergeos_graph_reader'
                  AND acl.privilege_type = 'SELECT'
                  AND relation.relname <>
                      ALL(restricted_relations))
              OR (grantee.rolname
                      = 'emergeos_exact_overlay_reader_v19'
                  AND acl.privilege_type = 'SELECT'
                  AND relation.relname
                      = ANY(exact_overlay_reader_relations))))
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                relation.relacl,
                pg_catalog.acldefault('s', relation.relowner))) acl
        WHERE namespace.nspname = 'public'
          AND relation.relkind = 'S'
          AND acl.grantee <> relation.relowner
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee
          ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND acl.grantee <> procedure.proowner
          AND NOT (
            acl.privilege_type = 'EXECUTE'
            AND NOT acl.is_grantable
            AND (
              (grantee.rolname IN (
                  'emergeos_terminal_owner',
                  'emergeos_graph_executor',
                  'emergeos_failure_resumer',
                  'emergeos_provider_attestor')
               AND procedure.oid = pg_catalog.to_regprocedure(
                 'public.emergeos_pack010_schema_version_v1()'))
              OR (grantee.rolname = 'emergeos_graph_executor'
               AND procedure.proname IN (
                    'agent_graph_complete_child_v10',
                    'agent_graph_complete_parent_and_seal_v10')
               AND pg_catalog.pg_get_function_identity_arguments(
                     procedure.oid) = 'payload jsonb')
              OR (grantee.rolname = 'emergeos_failure_resumer'
                  AND (
                    (procedure.proname
                       = 'agent_graph_claim_attributed_failure_resume_v12'
                     AND procedure.oid = pg_catalog.to_regprocedure(
                       'public.agent_graph_claim_attributed_failure_resume_v12(character varying,character,character,character,bigint,character varying,character,integer)'))
                    OR (procedure.proname
                       = 'agent_graph_read_attributed_failure_resume_v12'
                     AND procedure.oid = pg_catalog.to_regprocedure(
                       'public.agent_graph_read_attributed_failure_resume_v12(character varying,character,character)'))
                    OR (procedure.proname
                       = 'agent_graph_complete_claimed_failure_child_v12'
                     AND procedure.oid = pg_catalog.to_regprocedure(
                       'public.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'))
                    OR (procedure.proname
                       = 'agent_graph_complete_claimed_failure_parent_and_seal_v12'
                     AND procedure.oid = pg_catalog.to_regprocedure(
                       'public.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)'))))
              OR (grantee.rolname = 'emergeos_provider_attestor'
                  AND procedure.oid IN (
                    pg_catalog.to_regprocedure(
                      'public.agent_graph_require_provider_validation_v13(character varying,character,character,character varying,character,character,character,integer)'),
                    pg_catalog.to_regprocedure(
                      'public.agent_graph_stage_provider_validation_v13(jsonb)'),
                    pg_catalog.to_regprocedure(
                      'public.agent_graph_commit_provider_validation_v13(jsonb)')))
              OR (grantee.rolname = 'emergeos_provider_attestor_v14'
                  AND procedure.oid = pg_catalog.to_regprocedure(
                    'public.agent_graph_assert_provider_statement_v14(jsonb)'))
              OR (grantee.rolname = 'emergeos_provider_attestor_v15'
                  AND procedure.oid = pg_catalog.to_regprocedure(
                    'public.agent_graph_require_exact_tx_a_v15(character varying,character,character,character varying)'))
              OR (grantee.rolname = 'emergeos_provider_attestor_v16'
                  AND procedure.oid IN (
                    pg_catalog.to_regprocedure(
                      'public.agent_graph_stage_exact_tx_a_v16(jsonb)'),
                    pg_catalog.to_regprocedure(
                      'public.agent_graph_commit_exact_tx_a_v16(jsonb)')))
              OR (grantee.rolname = 'emergeos_terminal_owner'
                  AND (
                    (procedure.proname IN (
                       'agent_graph_complete_child_v9',
                       'agent_graph_complete_child_v10',
                       'agent_graph_complete_parent_and_seal_v9',
                       'agent_graph_complete_parent_and_seal_v10')
                     AND pg_catalog.pg_get_function_identity_arguments(
                           procedure.oid) = 'payload jsonb')
                    OR (procedure.proname IN (
                       'agent_graph_complete_claimed_failure_child_v12',
                       'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                     AND pg_catalog.pg_get_function_identity_arguments(
                           procedure.oid) =
                       'payload jsonb, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character')
                    OR procedure.oid IN (
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_framed_sha256_v13(character varying,text[])'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_framed_sha256_v14(character varying,text[])'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_require_exact_tx_a_v15(character varying,character,character,character varying)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_assert_exact_tx_a_requirement_v15()'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_stage_exact_tx_a_v16(jsonb)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_commit_exact_tx_a_v16(jsonb)'),
                      pg_catalog.to_regprocedure(
                        'public.agent_graph_assert_exact_tx_a_overlay_v16()'))
                    OR (procedure.proname IN (
                       'agent_graph_run_selector_guard_v10',
                       'agent_graph_require_row_shape_v9',
                       'agent_graph_require_executor_v9',
                       'agent_graph_require_executor_v10',
                       'agent_graph_require_failure_resumer_v12',
                       'agent_assert_graph_attempt_v7',
                       'agent_assert_graph_attempt_prefix_v7',
                       'agent_assert_graph_attempt_v8',
                       'agent_assert_worker_graph_v6',
                       'agent_graph_terminal_run_valid_v8',
                       'agent_graph_utf16_length_v8',
                       'agent_graph_authorize_terminal_v8')
                     AND pg_catalog.pg_get_function_identity_arguments(
                           procedure.oid) = CASE procedure.proname
                    WHEN 'agent_graph_run_selector_guard_v10' THEN ''
                    WHEN 'agent_graph_require_row_shape_v9'
                      THEN 'supplied jsonb, expected_relation regclass, supplied_is_array boolean'
                    WHEN 'agent_graph_require_executor_v9'
                      THEN 'expected_function regprocedure'
                    WHEN 'agent_graph_require_executor_v10'
                      THEN 'expected_function regprocedure'
                    WHEN 'agent_graph_require_failure_resumer_v12'
                      THEN 'expected_function regprocedure'
                    WHEN 'agent_assert_graph_attempt_v7'
                      THEN 'checked_principal character varying, checked_attempt character'
                    WHEN 'agent_assert_graph_attempt_prefix_v7'
                      THEN 'checked_principal character varying, checked_attempt character'
                    WHEN 'agent_assert_graph_attempt_v8'
                      THEN 'checked_principal character varying, checked_attempt character'
                    WHEN 'agent_assert_worker_graph_v6'
                      THEN 'checked_principal_id character varying, checked_run_id character varying'
                    WHEN 'agent_graph_terminal_run_valid_v8'
                      THEN 'checked_principal character varying, checked_attempt character, checked_role character varying'
                    WHEN 'agent_graph_utf16_length_v8' THEN 'input text'
                    WHEN 'agent_graph_authorize_terminal_v8'
                      THEN 'checked_principal character varying, checked_attempt character, checked_role character varying, checked_run character varying'
                       ELSE NULL
                     END)))
              OR (grantee.rolname = 'emergeos_graph_prefix_writer'
                  AND procedure.proname IN (
                    'agent_assert_graph_attempt_v7',
                    'agent_assert_graph_attempt_prefix_v7',
                    'agent_assert_graph_attempt_v8',
                    'agent_assert_worker_graph_v6',
                    'agent_graph_terminal_run_valid_v8',
                    'agent_graph_record_attributed_failure_v11')
                  AND pg_catalog.pg_get_function_identity_arguments(
                        procedure.oid) = CASE procedure.proname
                    WHEN 'agent_assert_worker_graph_v6'
                      THEN 'checked_principal_id character varying, checked_run_id character varying'
                    WHEN 'agent_graph_terminal_run_valid_v8'
                      THEN 'checked_principal character varying, checked_attempt character, checked_role character varying'
                    WHEN 'agent_graph_record_attributed_failure_v11'
                      THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_previous_sequence integer, checked_previous_head character, checked_failure_code character varying'
                    ELSE 'checked_principal character varying, checked_attempt character'
                  END)))
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_default_acl defaults
        LEFT JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = defaults.defaclnamespace
        WHERE namespace.nspname = 'public'
           OR defaults.defaclnamespace = 0
    ) THEN
        RAISE EXCEPTION 'Pack010 all-grantee ACL allowlist drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
            SELECT 1
            FROM pg_catalog.pg_database database
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(
                    database.datacl,
                    pg_catalog.acldefault('d', database.datdba))) acl
            WHERE database.datname = current_database()
              AND acl.grantee = 0
       ) OR EXISTS (
            SELECT 1
            FROM pg_catalog.pg_namespace namespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                COALESCE(
                    namespace.nspacl,
                    pg_catalog.acldefault('n', namespace.nspowner))) acl
            WHERE namespace.nspname = 'public'
              AND acl.grantee = 0
       ) OR EXISTS (
            SELECT 1
            FROM pg_catalog.pg_default_acl defaults
            CROSS JOIN LATERAL pg_catalog.aclexplode(
                defaults.defaclacl) acl
            LEFT JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = defaults.defaclnamespace
            WHERE (namespace.nspname = 'public'
                   OR defaults.defaclnamespace = 0)
              AND (acl.grantee = 0
                   OR acl.grantee IN (
                       SELECT oid FROM pg_catalog.pg_roles
                       WHERE rolname = ANY(managed_roles)))
       ) THEN
        RAISE EXCEPTION 'Pack010 PUBLIC/default ACL drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        CROSS JOIN unnest(runtime_roles) runtime(role_name)
        WHERE namespace.nspname = 'public'
          AND (
            pg_catalog.has_schema_privilege(
                runtime.role_name, namespace.oid, 'USAGE') IS FALSE
            OR pg_catalog.has_schema_privilege(
                runtime.role_name, namespace.oid,
                'CREATE,USAGE WITH GRANT OPTION'))
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        CROSS JOIN unnest(managed_roles) runtime(role_name)
        JOIN pg_catalog.pg_roles role
          ON role.rolname = runtime.role_name
        WHERE namespace.nspname <> 'public'
          AND namespace.nspname <> 'information_schema'
          AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
          AND (
            namespace.nspowner = role.oid
            OR pg_catalog.has_schema_privilege(
                role.oid, namespace.oid, 'USAGE,CREATE'))
    ) OR EXISTS (
        SELECT 1
        FROM unnest(ARRAY[
            'emergeos_graph_executor',
            'emergeos_failure_resumer',
            'emergeos_graph_prefix_writer',
            'emergeos_graph_reader',
            'emergeos_exact_overlay_reader_v19',
            'emergeos_provider_attestor',
            'emergeos_provider_attestor_v14',
            'emergeos_provider_attestor_v15',
            'emergeos_provider_attestor_v16']::NAME[]) runtime(role_name)
        WHERE NOT pg_catalog.has_database_privilege(
              runtime.role_name, current_database(), 'CONNECT')
          OR pg_catalog.has_database_privilege(
              runtime.role_name, current_database(),
              'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
    ) OR pg_catalog.has_database_privilege(
        'emergeos_terminal_owner', current_database(),
        'CONNECT,CREATE,TEMPORARY') THEN
        RAISE EXCEPTION 'Pack010 database/schema ACL drift'
            USING ERRCODE = '55000';
    END IF;
END;
$audit$;

SELECT pg_catalog.md5(
    current_database() || ':public:pack010-role-audit-v1')
    AS non_secret_audit_fingerprint;
