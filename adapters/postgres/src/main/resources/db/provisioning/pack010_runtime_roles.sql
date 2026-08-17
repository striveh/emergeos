-- Independent Pack010 runtime-role bootstrap.
--
-- Run only after Flyway V16 has completed. This file never creates schema
-- objects and never accepts or persists a password. LOGIN roles are created
-- with PASSWORD NULL so a separate secret manager must rotate ephemeral or
-- production credentials after this default-deny bootstrap succeeds.
-- Use scripts/provision-pack010-postgres-roles.sh against a dedicated Pack010
-- database; the wrapper supplies the required transaction and pure read-back
-- audit. Direct execution is unsupported.

DO $preflight$
DECLARE
    target_schema NAME := current_schema();
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
    role_name NAME;
    expected_login BOOLEAN;
    observed RECORD;
BEGIN
    IF target_schema IS DISTINCT FROM 'public' THEN
        RAISE EXCEPTION 'Pack010 provisioning requires exact public schema'
            USING ERRCODE = '55000';
    END IF;
    IF current_user = ANY(managed_roles)
       OR session_user = ANY(managed_roles) THEN
        RAISE EXCEPTION 'runtime role cannot provision Pack010 authority'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname = 'agent_graph_complete_child_v10'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'payload jsonb'
          AND procedure.prosecdef
          AND pg_catalog.md5(procedure.prosrc)
                = '26d1087933677c65697c73e14103c973'
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = 'db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae'
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname
                = 'agent_graph_complete_parent_and_seal_v10'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'payload jsonb'
          AND procedure.prosecdef
          AND pg_catalog.md5(procedure.prosrc)
                = '3bb316c246faa7f28ac8dc2560e45342'
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = '57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35'
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_proc procedure
          ON procedure.oid = trigger.tgfoid
        WHERE namespace.nspname = target_schema
          AND relation.relname = 'agent_runs'
          AND trigger.tgname = 'agent_graph_run_selector_guard_v10'
          AND trigger.tgenabled = 'O'
          AND NOT trigger.tgisinternal
          AND trigger.tgtype = 31
          AND trigger.tgqual IS NULL
          AND trigger.tgnargs = 0
          AND pg_catalog.octet_length(trigger.tgargs) = 0
          AND trigger.tgattr = ''::pg_catalog.int2vector
          AND procedure.proname = 'agent_graph_run_selector_guard_v10'
          AND pg_catalog.md5(procedure.prosrc)
                = '19e62fb3f842ec2b3ea3df3a174df34c'
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_trigger trigger
        JOIN pg_catalog.pg_class relation
          ON relation.oid = trigger.tgrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relname
                = 'agent_graph_provider_session_intents'
          AND NOT trigger.tgisinternal
    ) THEN
        RAISE EXCEPTION 'Pack010 V10 function or trigger definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
    ) <> 3 THEN
        RAISE EXCEPTION 'Pack010 V11 durable failure definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
    ) <> 4 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND (
            (procedure.proname = 'agent_graph_require_failure_resumer_v12'
             AND pg_catalog.encode(
                   pg_catalog.sha256(pg_catalog.convert_to(
                       procedure.prosrc, 'UTF8')), 'hex')
                   = '18e4c45e18c348d21b13c9de8ce600859c3acd64892006c99f8f93cbbd42340e')
            OR
            (procedure.proname = 'agent_graph_assert_failure_terminal_resume_v12'
             AND pg_catalog.encode(
                   pg_catalog.sha256(pg_catalog.convert_to(
                       procedure.prosrc, 'UTF8')), 'hex')
                   = 'fdad487040c702df9b58481e30158a96e1564963babd86b9d4a0d8e39bceebc3'))
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND procedure.prosecdef =
                (procedure.proname =
                  'agent_graph_assert_failure_terminal_resume_v12')
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V12 terminal resume definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind = 'r'
          AND relation.relname IN (
              'agent_graph_provider_validation_keys',
              'agent_graph_provider_validations')
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v13',
              'agent_graph_require_provider_validation_v13',
              'agent_graph_stage_provider_validation_v13',
              'agent_graph_commit_provider_validation_v13',
              'agent_graph_assert_provider_validation_v13')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
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
              WHEN 'agent_graph_assert_provider_validation_v13'
                THEN ''
              ELSE NULL
            END
          AND pg_catalog.pg_get_function_result(procedure.oid)
                = CASE procedure.proname
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
    ) <> 5 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v13',
              'agent_graph_require_provider_validation_v13',
              'agent_graph_stage_provider_validation_v13',
              'agent_graph_commit_provider_validation_v13',
              'agent_graph_assert_provider_validation_v13')
    ) <> 5 THEN
        RAISE EXCEPTION 'Pack010 V13 provider validation definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relname = 'agent_graph_provider_profiles_v14'
          AND relation.relkind = 'r'
          AND relation.relpersistence = 'p'
          AND NOT relation.relrowsecurity
          AND NOT relation.relforcerowsecurity
    ) OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
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
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V14 provider profile definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relname
                = 'agent_graph_exact_tx_a_requirements_v15'
          AND relation.relkind = 'r'
          AND relation.relpersistence = 'p'
          AND NOT relation.relrowsecurity
          AND NOT relation.relforcerowsecurity
    ) OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
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
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
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
        JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = target_schema
          AND NOT trigger.tgisinternal
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
          AND pg_catalog.pg_get_function_identity_arguments(
                guard.oid) = ''
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V15 exact TX-A requirement drifted'
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
            WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
    ) THEN
        RAISE EXCEPTION 'Pack010 V16 exact-pico relation topology drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
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
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
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
        JOIN pg_catalog.pg_constraint constraint_row
          ON constraint_row.oid = trigger.tgconstraint
        WHERE namespace.nspname = target_schema
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
          AND pg_catalog.pg_get_function_identity_arguments(
                guard.oid) = ''
    ) <> 4 THEN
        RAISE EXCEPTION 'Pack010 V16 exact-pico overlay drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND (
            (procedure.proname = 'agent_graph_require_row_shape_v9'
             AND pg_catalog.md5(procedure.prosrc)
                   = '865db3bb7b9d040e21e7e67fa4592980')
            OR (procedure.proname = 'agent_graph_require_executor_v9'
                AND pg_catalog.md5(procedure.prosrc)
                   = '90f8d826c5a2dbd8851f469322500d36')
            OR (procedure.proname = 'agent_graph_require_executor_v10'
                AND pg_catalog.md5(procedure.prosrc)
                   = 'd2e3f2e6ae39bdd92e45fde9338e6625'))
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
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
        RAISE EXCEPTION 'Pack010 V9/V10 helper definition drifted'
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
        WHERE namespace.nspname = target_schema
          AND NOT trigger.tgisinternal
    ) <> 'e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b'
    THEN
        RAISE EXCEPTION 'Pack010 full trigger topology drifted'
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
             = '614e75833b1d785ce6333dafc0e13d1999f66b6fa63b651972c5c429569c9d8b'
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_language language
          ON language.oid = procedure.prolang
        WHERE namespace.nspname = target_schema
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
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal character varying, checked_attempt character'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
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
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_assert_graph_attempt_v7',
              'agent_assert_graph_attempt_prefix_v7',
              'agent_assert_graph_attempt_v8')
    ) <> 3 THEN
        RAISE EXCEPTION 'Pack010 assertion closure definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname = 'agent_assert_worker_graph_v6'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal_id character varying, checked_run_id character varying'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND pg_catalog.md5(procedure.prosrc)
                = '7d9bf040a89092662f6fab37e12ab3c3'
    ) THEN
        RAISE EXCEPTION 'Pack010 worker assertion definition drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND pg_catalog.md5(procedure.prosrc) = CASE procedure.proname
            WHEN 'agent_graph_utf16_length_v8'
              THEN 'f3785ba550ab7e86ce5b3e91e71baef4'
            WHEN 'agent_graph_terminal_run_valid_v8'
              THEN 'b5fa7ea4af7f0724f6383f5ed11ec9e6'
            ELSE NULL
          END
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 terminal validation helper drifted'
            USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname = 'agent_graph_authorize_terminal_v8'
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'checked_principal character varying, checked_attempt character, checked_role character varying, checked_run character varying'
          AND NOT procedure.prosecdef
          AND procedure.proconfig
                = ARRAY[
                    'search_path=pg_catalog, public, pg_temp']::TEXT[]
          AND pg_catalog.md5(procedure.prosrc)
                = 'b18b53e8dd9ab4e6219bad8c7382d49d'
    ) THEN
        RAISE EXCEPTION 'Pack010 terminal authorization helper drifted'
            USING ERRCODE = '55000';
    END IF;

    FOREACH role_name IN ARRAY managed_roles LOOP
        expected_login := role_name IN (
            'emergeos_graph_executor',
            'emergeos_failure_resumer',
            'emergeos_provider_attestor',
            'emergeos_provider_attestor_v14',
            'emergeos_provider_attestor_v15',
            'emergeos_provider_attestor_v16',
            'emergeos_graph_prefix_writer',
            'emergeos_graph_reader',
            'emergeos_exact_overlay_reader_v19');
        SELECT role.rolcanlogin AS can_login,
               role.rolsuper AS superuser,
               role.rolcreatedb AS create_database,
               role.rolcreaterole AS create_role,
               role.rolinherit AS inherit,
               role.rolreplication AS replication,
               role.rolbypassrls AS bypass_rls
        INTO observed
        FROM pg_catalog.pg_roles role
        WHERE role.rolname = role_name;
        IF FOUND AND (
            observed.can_login IS DISTINCT FROM expected_login
            OR observed.superuser
            OR observed.create_database
            OR observed.create_role
            OR observed.inherit
            OR observed.replication
            OR observed.bypass_rls
        ) THEN
            RAISE EXCEPTION 'Pack010 runtime role attribute drift: %', role_name
                USING ERRCODE = '55000';
        END IF;
    END LOOP;
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
        RAISE EXCEPTION 'Pack010 runtime role membership drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('v', 'm', 'f')
    ) THEN
        RAISE EXCEPTION 'Pack010 unsupported relation kind drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles runtime
          ON runtime.rolname = ANY(managed_roles)
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND runtime.rolname <> 'emergeos_pack010_schema_owner'
          AND relation.relowner = runtime.oid
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles runtime
          ON runtime.rolname IN (
              'emergeos_graph_executor',
              'emergeos_failure_resumer',
              'emergeos_provider_attestor',
              'emergeos_provider_attestor_v14',
              'emergeos_provider_attestor_v15',
              'emergeos_provider_attestor_v16')
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND pg_catalog.has_table_privilege(
              runtime.oid, relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles runtime
          ON runtime.rolname IN (
              'emergeos_graph_reader',
              'emergeos_exact_overlay_reader_v19')
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND pg_catalog.has_table_privilege(
              runtime.oid, relation.oid,
              'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles runtime
          ON runtime.rolname = 'emergeos_graph_prefix_writer'
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND (
              pg_catalog.has_table_privilege(
                  runtime.oid, relation.oid,
                  'DELETE,TRUNCATE,REFERENCES,TRIGGER')
              OR (
                  pg_catalog.has_table_privilege(
                      runtime.oid, relation.oid, 'UPDATE')
                  AND relation.relname <> 'agent_graph_attempt_heads'
              )
              OR (
                  pg_catalog.has_table_privilege(
                      runtime.oid, relation.oid, 'INSERT')
                  AND relation.relname NOT IN (
                      'agent_graph_attempts',
                      'agent_graph_attempt_run_bindings',
                      'agent_graph_attempt_heads',
                      'agent_graph_attempt_events',
                      'agent_graph_attempt_provider_attributions',
                      'agent_graph_provider_session_intents',
                      'agent_runs'
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION 'Pack010 runtime relation authority drift'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        JOIN pg_catalog.pg_roles runtime
          ON runtime.rolname = ANY(managed_roles)
        WHERE namespace.nspname = target_schema
          AND runtime.rolname <> 'emergeos_pack010_schema_owner'
          AND pg_catalog.has_schema_privilege(
              runtime.oid, namespace.oid, 'CREATE')
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace namespace
        CROSS JOIN unnest(managed_roles) runtime(role_name)
        JOIN pg_catalog.pg_roles role
          ON role.rolname = runtime.role_name
        WHERE namespace.nspname <> target_schema
          AND namespace.nspname <> 'information_schema'
          AND namespace.nspname NOT LIKE 'pg\_%' ESCAPE '\'
          AND (
            namespace.nspowner = role.oid
            OR pg_catalog.has_schema_privilege(
                role.oid, namespace.oid, 'USAGE,CREATE'))
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute attribute
        JOIN pg_catalog.pg_class relation
          ON relation.oid = attribute.attrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
          AND attribute.attacl IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Pack010 schema or column authority drift'
            USING ERRCODE = '55000';
    END IF;
END;
$preflight$;

DO $roles$
DECLARE
    role_name NAME;
    login_clause TEXT;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
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
    ]::NAME[] LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = role_name
        ) THEN
            login_clause :=
                CASE WHEN role_name IN (
                    'emergeos_pack010_schema_owner',
                    'emergeos_terminal_owner')
                THEN 'NOLOGIN'
                ELSE 'LOGIN PASSWORD NULL'
                END;
            EXECUTE pg_catalog.format(
                'CREATE ROLE %I %s NOSUPERUSER NOCREATEDB NOCREATEROLE '
                    || 'NOINHERIT NOREPLICATION NOBYPASSRLS',
                role_name,
                login_clause
            );
        END IF;
    END LOOP;
END;
$roles$;

DO $authority$
DECLARE
    target_schema NAME := current_schema();
    database_name NAME := current_database();
    runtime_role NAME;
    owned_object TEXT;
    acl_entry RECORD;
    grantee_sql TEXT;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(
            current_database() || E'\\x1fpublic\\x1fpack010-role-v2',
            0));
    ALTER SCHEMA public OWNER TO emergeos_pack010_schema_owner;
    FOR owned_object IN
        SELECT relation.oid::regclass::TEXT
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p')
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER TABLE %s OWNER TO emergeos_pack010_schema_owner',
            owned_object);
    END LOOP;

    FOR acl_entry IN
        SELECT DISTINCT acl.grantee, role.rolname
        FROM pg_catalog.pg_database database
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                database.datacl,
                pg_catalog.acldefault('d', database.datdba))) acl
        LEFT JOIN pg_catalog.pg_roles role
          ON role.oid = acl.grantee
        WHERE database.datname = database_name
          AND acl.grantee <> database.datdba
    LOOP
        grantee_sql := CASE WHEN acl_entry.grantee = 0
            THEN 'PUBLIC'
            ELSE pg_catalog.quote_ident(acl_entry.rolname)
        END;
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON DATABASE %I FROM %s',
            database_name, grantee_sql);
    END LOOP;
    FOR acl_entry IN
        SELECT DISTINCT acl.grantee, role.rolname
        FROM pg_catalog.pg_namespace namespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                namespace.nspacl,
                pg_catalog.acldefault('n', namespace.nspowner))) acl
        LEFT JOIN pg_catalog.pg_roles role
          ON role.oid = acl.grantee
        WHERE namespace.nspname = target_schema
          AND acl.grantee <> namespace.nspowner
    LOOP
        grantee_sql := CASE WHEN acl_entry.grantee = 0
            THEN 'PUBLIC'
            ELSE pg_catalog.quote_ident(acl_entry.rolname)
        END;
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON SCHEMA %I FROM %s',
            target_schema, grantee_sql);
    END LOOP;
    FOR acl_entry IN
        SELECT DISTINCT relation.oid::regclass::TEXT AS object_name,
               relation.relkind,
               acl.grantee,
               role.rolname
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                relation.relacl,
                pg_catalog.acldefault(
                    CASE WHEN relation.relkind = 'S'
                        THEN 's'::"char" ELSE 'r'::"char" END,
                    relation.relowner))) acl
        LEFT JOIN pg_catalog.pg_roles role
          ON role.oid = acl.grantee
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND acl.grantee <> relation.relowner
    LOOP
        grantee_sql := CASE WHEN acl_entry.grantee = 0
            THEN 'PUBLIC'
            ELSE pg_catalog.quote_ident(acl_entry.rolname)
        END;
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON %s %s FROM %s',
            CASE WHEN acl_entry.relkind = 'S'
                THEN 'SEQUENCE' ELSE 'TABLE' END,
            acl_entry.object_name,
            grantee_sql);
    END LOOP;
    FOR acl_entry IN
        SELECT DISTINCT procedure.oid::regprocedure::TEXT
                   AS object_name,
               acl.grantee,
               role.rolname
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
            COALESCE(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner))) acl
        LEFT JOIN pg_catalog.pg_roles role
          ON role.oid = acl.grantee
        WHERE namespace.nspname = target_schema
          AND acl.grantee <> procedure.proowner
    LOOP
        grantee_sql := CASE WHEN acl_entry.grantee = 0
            THEN 'PUBLIC'
            ELSE pg_catalog.quote_ident(acl_entry.rolname)
        END;
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON FUNCTION %s FROM %s',
            acl_entry.object_name, grantee_sql);
    END LOOP;
    FOR owned_object IN
        SELECT relation.oid::regclass::TEXT
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind = 'S'
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER SEQUENCE %s OWNER TO emergeos_pack010_schema_owner',
            owned_object);
    END LOOP;
    FOR owned_object IN
        SELECT procedure.oid::regprocedure::TEXT
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname NOT IN (
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
    LOOP
        EXECUTE pg_catalog.format(
            'ALTER FUNCTION %s OWNER TO emergeos_pack010_schema_owner',
            owned_object);
    END LOOP;
    EXECUTE pg_catalog.format(
        'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC',
        database_name
    );
    EXECUTE pg_catalog.format(
        'REVOKE CREATE ON SCHEMA %I FROM PUBLIC', target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM PUBLIC',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM PUBLIC',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM PUBLIC',
        target_schema
    );

    FOREACH runtime_role IN ARRAY ARRAY[
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
    ]::NAME[] LOOP
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON DATABASE %I FROM %I',
            database_name,
            runtime_role
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON SCHEMA %I FROM %I',
            target_schema,
            runtime_role
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I',
            target_schema,
            runtime_role
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I',
            target_schema,
            runtime_role
        );
        EXECUTE pg_catalog.format(
            'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM %I',
            target_schema,
            runtime_role
        );
        EXECUTE pg_catalog.format(
            'GRANT USAGE ON SCHEMA %I TO %I',
            target_schema,
            runtime_role
        );
    END LOOP;

    EXECUTE pg_catalog.format(
        'GRANT CONNECT ON DATABASE %I TO emergeos_graph_executor, '
            || 'emergeos_failure_resumer, '
            || 'emergeos_provider_attestor, '
            || 'emergeos_provider_attestor_v14, '
            || 'emergeos_provider_attestor_v15, '
            || 'emergeos_provider_attestor_v16, '
            || 'emergeos_graph_prefix_writer, emergeos_graph_reader, '
            || 'emergeos_exact_overlay_reader_v19',
        database_name
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT, INSERT, UPDATE ON TABLE '
            || '%1$I.agent_graph_attempts, '
            || '%1$I.agent_graph_attempt_run_bindings, '
            || '%1$I.agent_graph_attempt_heads, '
            || '%1$I.agent_graph_attempt_events, '
            || '%1$I.agent_graph_attempt_provider_attributions, '
            || '%1$I.agent_graph_provider_session_intents, '
            || '%1$I.agent_graph_attributed_failure_outcomes, '
            || '%1$I.agent_graph_attributed_failure_terminal_resumes, '
            || '%1$I.agent_graph_provider_validations, '
            || '%1$I.agent_runs, '
            || '%1$I.agent_graph_attempt_terminal_bindings, '
            || '%1$I.agent_graph_attempt_candidates, '
            || '%1$I.agent_worker_results, '
            || '%1$I.agent_graph_attempt_seals, '
            || '%1$I.agent_trace_events, '
            || '%1$I.agent_run_resource_bindings, '
            || '%1$I.artifacts, '
            || '%1$I.artifact_versions '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT, INSERT ON TABLE '
            || '%I.agent_graph_exact_tx_a_requirements_v15 '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT, INSERT, UPDATE ON TABLE '
            || '%I.agent_graph_exact_provider_validations_v16 '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT, INSERT ON TABLE '
            || '%1$I.agent_graph_exact_provider_attributions_v16, '
            || '%1$I.agent_graph_exact_attempt_events_v16, '
            || '%1$I.agent_graph_exact_attempt_heads_v16 '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT ON TABLE '
            || '%1$I.agent_graph_provider_validation_keys, '
            || '%1$I.agent_graph_provider_profiles_v14 '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_run_selector_guard_v10(), '
            || '%1$I.agent_graph_require_row_shape_v9('
            || 'jsonb, regclass, boolean), '
            || '%1$I.agent_graph_require_executor_v9(regprocedure) '
            || ', %1$I.agent_graph_require_executor_v10(regprocedure) '
            || ', %1$I.agent_graph_require_failure_resumer_v12(regprocedure) '
            || ', %1$I.agent_graph_framed_sha256_v13(varchar, text[]) '
            || ', %1$I.agent_graph_framed_sha256_v14(varchar, text[]) '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_require_provider_validation_v13('
            || 'varchar, char, char, varchar, char, char, char, integer), '
            || '%1$I.agent_graph_stage_provider_validation_v13(jsonb), '
            || '%1$I.agent_graph_commit_provider_validation_v13(jsonb) '
            || 'TO emergeos_provider_attestor',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_assert_provider_statement_v14(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%1$I.agent_graph_assert_provider_statement_v14(jsonb) '
            || 'FROM PUBLIC, emergeos_graph_prefix_writer, '
            || 'emergeos_graph_reader, emergeos_graph_executor, '
            || 'emergeos_failure_resumer, emergeos_provider_attestor, '
            || 'emergeos_provider_attestor_v14, '
            || 'emergeos_provider_attestor_v15, '
            || 'emergeos_provider_attestor_v16',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_assert_provider_statement_v14(jsonb) '
            || 'TO emergeos_terminal_owner, '
            || 'emergeos_provider_attestor_v14',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_require_exact_tx_a_v15('
            || 'varchar, char, char, varchar) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_assert_exact_tx_a_requirement_v15() '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%1$I.agent_graph_require_exact_tx_a_v15('
            || 'varchar, char, char, varchar), '
            || '%1$I.agent_graph_assert_exact_tx_a_requirement_v15() '
            || 'FROM PUBLIC, emergeos_graph_prefix_writer, '
            || 'emergeos_graph_reader, emergeos_graph_executor, '
            || 'emergeos_failure_resumer, emergeos_provider_attestor, '
            || 'emergeos_provider_attestor_v14, '
            || 'emergeos_provider_attestor_v15, '
            || 'emergeos_provider_attestor_v16',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_require_exact_tx_a_v15('
            || 'varchar, char, char, varchar) '
            || 'TO emergeos_terminal_owner, '
            || 'emergeos_provider_attestor_v15',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%I.agent_graph_assert_exact_tx_a_requirement_v15() '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_stage_exact_tx_a_v16(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_commit_exact_tx_a_v16(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_assert_exact_tx_a_overlay_v16() '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%1$I.agent_graph_stage_exact_tx_a_v16(jsonb), '
            || '%1$I.agent_graph_commit_exact_tx_a_v16(jsonb), '
            || '%1$I.agent_graph_assert_exact_tx_a_overlay_v16() '
            || 'FROM PUBLIC, emergeos_graph_prefix_writer, '
            || 'emergeos_graph_reader, emergeos_graph_executor, '
            || 'emergeos_failure_resumer, emergeos_provider_attestor, '
            || 'emergeos_provider_attestor_v14, '
            || 'emergeos_provider_attestor_v15, '
            || 'emergeos_provider_attestor_v16',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_stage_exact_tx_a_v16(jsonb), '
            || '%1$I.agent_graph_commit_exact_tx_a_v16(jsonb) '
            || 'TO emergeos_terminal_owner, '
            || 'emergeos_provider_attestor_v16',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%I.agent_graph_assert_exact_tx_a_overlay_v16() '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    -- The prefix writer commits through the historical deferred integrity
    -- trigger.  Grant only that read-only assertion closure; neither function
    -- can advance terminal state and neither is SECURITY DEFINER.
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_assert_graph_attempt_v7(varchar, char), '
            || '%1$I.agent_assert_graph_attempt_prefix_v7(varchar, char), '
            || '%1$I.agent_assert_graph_attempt_v8(varchar, char), '
            || '%1$I.agent_assert_worker_graph_v6(varchar, varchar), '
            || '%1$I.agent_graph_terminal_run_valid_v8('
            || 'varchar, char, varchar) '
            || 'TO emergeos_graph_prefix_writer',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_record_attributed_failure_v11('
            || 'varchar, char, char, integer, char, varchar) '
            || 'TO emergeos_graph_prefix_writer',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_read_attributed_failure_resume_v12('
            || 'varchar, char, char), '
            || '%1$I.agent_graph_claim_attributed_failure_resume_v12('
            || 'varchar, char, char, char, bigint, varchar, char, integer), '
            || '%1$I.agent_graph_complete_claimed_failure_child_v12('
            || 'jsonb, char, bigint, varchar, char), '
            || '%1$I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'TO emergeos_failure_resumer',
        target_schema
    );
    -- TX-B/TX-C run as terminal_owner and commit through the V8 assertion
    -- branch after sequence 14.
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_assert_graph_attempt_v7(varchar, char), '
            || '%1$I.agent_assert_graph_attempt_prefix_v7(varchar, char), '
            || '%1$I.agent_assert_graph_attempt_v8(varchar, char), '
            || '%1$I.agent_assert_worker_graph_v6(varchar, varchar), '
            || '%1$I.agent_graph_terminal_run_valid_v8('
            || 'varchar, char, varchar), '
            || '%1$I.agent_graph_utf16_length_v8(text), '
            || '%1$I.agent_graph_authorize_terminal_v8('
            || 'varchar, char, varchar, varchar) '
            || 'TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_complete_child_v10(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_complete_child_v9(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_complete_parent_and_seal_v10(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_complete_parent_and_seal_v9(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION %I.agent_graph_complete_claimed_failure_child_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_require_provider_validation_v13('
            || 'varchar, char, char, varchar, char, char, char, integer) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_stage_provider_validation_v13(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'ALTER FUNCTION '
            || '%I.agent_graph_commit_provider_validation_v13(jsonb) '
            || 'OWNER TO emergeos_terminal_owner',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION '
            || '%1$I.agent_graph_require_provider_validation_v13('
            || 'varchar, char, char, varchar, char, char, char, integer), '
            || '%1$I.agent_graph_stage_provider_validation_v13(jsonb), '
            || '%1$I.agent_graph_commit_provider_validation_v13(jsonb) '
            || 'FROM PUBLIC, emergeos_graph_prefix_writer, '
            || 'emergeos_graph_reader, emergeos_graph_executor, '
            || 'emergeos_failure_resumer, emergeos_provider_attestor',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_require_provider_validation_v13('
            || 'varchar, char, char, varchar, char, char, char, integer), '
            || '%1$I.agent_graph_stage_provider_validation_v13(jsonb), '
            || '%1$I.agent_graph_commit_provider_validation_v13(jsonb) '
            || 'TO emergeos_terminal_owner, emergeos_provider_attestor',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'REVOKE ALL ON FUNCTION %I.agent_graph_complete_child_v9(jsonb), '
            || '%I.agent_graph_complete_child_v10(jsonb), '
            || '%I.agent_graph_complete_parent_and_seal_v9(jsonb), '
            || '%I.agent_graph_complete_parent_and_seal_v10(jsonb), '
            || '%I.agent_graph_complete_claimed_failure_child_v12('
            || 'jsonb, char, bigint, varchar, char), '
            || '%I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'FROM PUBLIC, emergeos_graph_prefix_writer, '
            || 'emergeos_graph_reader, emergeos_graph_executor, '
            || 'emergeos_failure_resumer',
        target_schema,
        target_schema,
        target_schema,
        target_schema,
        target_schema,
        target_schema
    );
    -- A second provisioning pass revokes explicit runtime grants before
    -- rebuilding the allowlist. PostgreSQL permits an owner to revoke its own
    -- EXECUTE privilege, so restore the exact owner surface explicitly to keep
    -- reconciliation idempotent.
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION %I.agent_graph_complete_child_v9(jsonb), '
            || '%I.agent_graph_complete_child_v10(jsonb), '
            || '%I.agent_graph_complete_parent_and_seal_v9(jsonb), '
            || '%I.agent_graph_complete_parent_and_seal_v10(jsonb), '
            || '%I.agent_graph_complete_claimed_failure_child_v12('
            || 'jsonb, char, bigint, varchar, char), '
            || '%I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'TO emergeos_terminal_owner',
        target_schema,
        target_schema,
        target_schema,
        target_schema,
        target_schema,
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION '
            || '%1$I.agent_graph_read_attributed_failure_resume_v12('
            || 'varchar, char, char), '
            || '%1$I.agent_graph_claim_attributed_failure_resume_v12('
            || 'varchar, char, char, char, bigint, varchar, char, integer), '
            || '%1$I.agent_graph_complete_claimed_failure_child_v12('
            || 'jsonb, char, bigint, varchar, char), '
            || '%1$I.agent_graph_complete_claimed_failure_parent_and_seal_v12('
            || 'jsonb, char, bigint, varchar, char) '
            || 'TO emergeos_failure_resumer',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT EXECUTE ON FUNCTION %I.agent_graph_complete_child_v10(jsonb), '
            || '%I.agent_graph_complete_parent_and_seal_v10(jsonb) '
            || 'TO emergeos_graph_executor',
        target_schema,
        target_schema
    );

    EXECUTE pg_catalog.format(
        'GRANT SELECT ON TABLE '
            || '%1$I.agent_graph_attempts, '
            || '%1$I.agent_graph_attempt_run_bindings, '
            || '%1$I.agent_graph_attempt_heads, '
            || '%1$I.agent_graph_attempt_events, '
            || '%1$I.agent_graph_attempt_provider_attributions, '
            || '%1$I.agent_graph_provider_session_intents, '
            || '%1$I.agent_graph_attributed_failure_outcomes, '
            || '%1$I.agent_runs, '
            || '%1$I.agent_graph_attempt_terminal_bindings, '
            || '%1$I.agent_graph_attempt_candidates, '
            || '%1$I.agent_worker_results, '
            || '%1$I.agent_graph_attempt_seals, '
            || '%1$I.agent_trace_events, '
            || '%1$I.agent_run_resource_bindings, '
            || '%1$I.artifacts, '
            || '%1$I.artifact_versions '
            || 'TO emergeos_graph_prefix_writer, emergeos_graph_reader',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT SELECT ON TABLE '
            || '%1$I.agent_graph_attempts, '
            || '%1$I.agent_graph_attempt_heads, '
            || '%1$I.agent_graph_attempt_events, '
            || '%1$I.agent_graph_attempt_provider_attributions, '
            || '%1$I.agent_graph_provider_session_intents, '
            || '%1$I.agent_graph_provider_validation_keys, '
            || '%1$I.agent_graph_provider_validations, '
            || '%1$I.agent_graph_provider_profiles_v14, '
            || '%1$I.agent_graph_exact_tx_a_requirements_v15, '
            || '%1$I.agent_graph_exact_provider_validations_v16, '
            || '%1$I.agent_graph_exact_provider_attributions_v16, '
            || '%1$I.agent_graph_exact_attempt_events_v16, '
            || '%1$I.agent_graph_exact_attempt_heads_v16 '
            || 'TO emergeos_exact_overlay_reader_v19',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT INSERT ON TABLE '
            || '%1$I.agent_graph_attempts, '
            || '%1$I.agent_graph_attempt_run_bindings, '
            || '%1$I.agent_graph_attempt_heads, '
            || '%1$I.agent_graph_attempt_events, '
            || '%1$I.agent_graph_attempt_provider_attributions, '
            || '%1$I.agent_graph_provider_session_intents, '
            || '%1$I.agent_runs '
            || 'TO emergeos_graph_prefix_writer',
        target_schema
    );
    EXECUTE pg_catalog.format(
        'GRANT UPDATE ON TABLE %I.agent_graph_attempt_heads '
            || 'TO emergeos_graph_prefix_writer',
        target_schema
    );
END;
$authority$;

DO $audit$
DECLARE
    target_schema NAME := current_schema();
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
BEGIN
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
        RAISE EXCEPTION 'Pack010 runtime role read-back failed'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = relation.relowner
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND owner.rolname <> 'emergeos_pack010_schema_owner'
    ) THEN
        RAISE EXCEPTION 'Pack010 relation owner drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_complete_child_v10',
              'agent_graph_complete_parent_and_seal_v10')
          AND pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                = 'payload jsonb'
          AND procedure.prosecdef
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_terminal_owner'
          AND pg_catalog.has_function_privilege(
              'emergeos_graph_executor', procedure.oid, 'EXECUTE')
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 terminal function authority drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_complete_claimed_failure_child_v12',
              'agent_graph_complete_claimed_failure_parent_and_seal_v12')
          AND procedure.prosecdef
          AND procedure.proconfig
                = ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_terminal_owner'
          AND pg_catalog.has_function_privilege(
              'emergeos_failure_resumer', procedure.oid, 'EXECUTE')
    ) <> 2 THEN
        RAISE EXCEPTION 'Pack010 V12 terminal function authority drifted'
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
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_require_provider_validation_v13',
              'agent_graph_stage_provider_validation_v13',
              'agent_graph_commit_provider_validation_v13')
          AND pg_catalog.pg_get_function_identity_arguments(
                procedure.oid) = CASE procedure.proname
              WHEN 'agent_graph_require_provider_validation_v13'
                THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_key_id character varying, checked_transport_profile character, checked_parser_profile character, checked_schema_profile character, checked_challenge_ttl_millis integer'
              ELSE 'payload jsonb'
            END
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex') = CASE procedure.proname
              WHEN 'agent_graph_require_provider_validation_v13'
                THEN 'befee76a15fd634d8fc1b49838df8641dc68b9ea25535b45a1e54a2419ac1c32'
              WHEN 'agent_graph_stage_provider_validation_v13'
                THEN '703349b8401a6b70f99df436fe425d5d1641e6552f4875ef8e9e7a084163d3d7'
              WHEN 'agent_graph_commit_provider_validation_v13'
                THEN '2e2de1a391d9d069763927356683bb3d2ce1db52aa9a39496bb388fdc57a71e4'
              ELSE NULL
            END
          AND procedure.prosecdef
          AND procedure.provolatile = 'v'
          AND procedure.proparallel = 'u'
          AND procedure.prokind = 'f'
          AND procedure.proretset =
                (procedure.proname =
                    'agent_graph_stage_provider_validation_v13')
          AND NOT procedure.proisstrict
          AND NOT procedure.proleakproof
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND language.lanname = 'plpgsql'
          AND owner.rolname = 'emergeos_terminal_owner'
          AND pg_catalog.has_function_privilege(
              'emergeos_provider_attestor', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
              'emergeos_provider_attestor', procedure.oid,
              'EXECUTE WITH GRANT OPTION')
    ) <> 3 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
              'emergeos_provider_attestor', procedure.oid, 'EXECUTE')
          AND procedure.oid NOT IN (
            pg_catalog.to_regprocedure(
              'public.agent_graph_require_provider_validation_v13(character varying,character,character,character varying,character,character,character,integer)'),
            pg_catalog.to_regprocedure(
              'public.agent_graph_stage_provider_validation_v13(jsonb)'),
            pg_catalog.to_regprocedure(
              'public.agent_graph_commit_provider_validation_v13(jsonb)'))
    ) THEN
        RAISE EXCEPTION 'Pack010 V13 attestor function authority drifted'
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
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_framed_sha256_v14',
              'agent_graph_assert_provider_statement_v14')
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
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
              'emergeos_provider_attestor_v14', procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
              'emergeos_provider_attestor_v14', procedure.oid,
              'EXECUTE WITH GRANT OPTION')
    ) <> 1 OR NOT pg_catalog.has_function_privilege(
        'emergeos_provider_attestor_v14',
        'public.agent_graph_assert_provider_statement_v14(jsonb)',
        'EXECUTE') THEN
        RAISE EXCEPTION 'Pack010 V14 profile attestor authority drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_require_exact_tx_a_v15',
              'agent_graph_assert_exact_tx_a_requirement_v15')
          AND pg_catalog.encode(
                pg_catalog.sha256(pg_catalog.convert_to(
                    procedure.prosrc, 'UTF8')), 'hex')
                = CASE procedure.proname
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
          AND owner.rolname = 'emergeos_terminal_owner'
          AND pg_catalog.has_function_privilege(
                'emergeos_terminal_owner', procedure.oid, 'EXECUTE')
    ) <> 2 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
                'emergeos_provider_attestor_v15',
                procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_provider_attestor_v15', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
    ) <> 1 OR NOT pg_catalog.has_function_privilege(
        'emergeos_provider_attestor_v15',
        'public.agent_graph_require_exact_tx_a_v15(character varying,character,character,character varying)',
        'EXECUTE') THEN
        RAISE EXCEPTION 'Pack010 V15 attestor function authority drifted'
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
            WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
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
        WHERE namespace.nspname = target_schema
          AND relation.relname IN (
              'agent_graph_exact_provider_validations_v16',
              'agent_graph_exact_provider_attributions_v16',
              'agent_graph_exact_attempt_events_v16',
              'agent_graph_exact_attempt_heads_v16')
    ) THEN
        RAISE EXCEPTION 'Pack010 V16 exact-pico relation topology drifted'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = procedure.proowner
        WHERE namespace.nspname = target_schema
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
          AND procedure.proconfig =
                ARRAY['search_path=pg_catalog, pg_temp']::TEXT[]
          AND owner.rolname = 'emergeos_terminal_owner'
          AND pg_catalog.has_function_privilege(
                'emergeos_terminal_owner', procedure.oid, 'EXECUTE')
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.proname IN (
              'agent_graph_stage_exact_tx_a_v16',
              'agent_graph_commit_exact_tx_a_v16',
              'agent_graph_assert_exact_tx_a_overlay_v16')
    ) <> 3 OR (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
                'emergeos_provider_attestor_v16',
                procedure.oid, 'EXECUTE')
          AND NOT pg_catalog.has_function_privilege(
                'emergeos_provider_attestor_v16', procedure.oid,
                'EXECUTE WITH GRANT OPTION')
    ) <> 2 OR NOT pg_catalog.has_function_privilege(
        'emergeos_provider_attestor_v16',
        'public.agent_graph_stage_exact_tx_a_v16(jsonb)', 'EXECUTE'
    ) OR NOT pg_catalog.has_function_privilege(
        'emergeos_provider_attestor_v16',
        'public.agent_graph_commit_exact_tx_a_v16(jsonb)', 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'Pack010 V16 attestor function authority drifted'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.prosecdef
          AND pg_catalog.has_function_privilege(
              'emergeos_graph_executor', procedure.oid, 'EXECUTE')
          AND procedure.proname NOT IN (
              'agent_graph_complete_child_v10',
              'agent_graph_complete_parent_and_seal_v10')
    ) THEN
        RAISE EXCEPTION 'graph executor has non-exact function authority'
            USING ERRCODE = '55000';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND procedure.prosecdef
          AND pg_catalog.has_function_privilege(
              'emergeos_failure_resumer', procedure.oid, 'EXECUTE')
          AND (
            (procedure.proname
                 = 'agent_graph_read_attributed_failure_resume_v12'
             AND procedure.oid = pg_catalog.to_regprocedure(
               'public.agent_graph_read_attributed_failure_resume_v12(character varying,character,character)'))
            OR
            (procedure.proname
                 = 'agent_graph_claim_attributed_failure_resume_v12'
             AND procedure.oid = pg_catalog.to_regprocedure(
               'public.agent_graph_claim_attributed_failure_resume_v12(character varying,character,character,character,bigint,character varying,character,integer)'))
            OR
            (procedure.proname
                 = 'agent_graph_complete_claimed_failure_child_v12'
             AND procedure.oid = pg_catalog.to_regprocedure(
               'public.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'))
            OR
            (procedure.proname
                 = 'agent_graph_complete_claimed_failure_parent_and_seal_v12'
             AND procedure.oid = pg_catalog.to_regprocedure(
               'public.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)')))
    ) <> 4 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
              'emergeos_failure_resumer', procedure.oid, 'EXECUTE')
          AND procedure.oid NOT IN (
            pg_catalog.to_regprocedure(
              'public.agent_graph_read_attributed_failure_resume_v12(character varying,character,character)'),
            pg_catalog.to_regprocedure(
              'public.agent_graph_claim_attributed_failure_resume_v12(character varying,character,character,character,bigint,character varying,character,integer)'),
            pg_catalog.to_regprocedure(
              'public.agent_graph_complete_claimed_failure_child_v12(jsonb,character,bigint,character varying,character)'),
            pg_catalog.to_regprocedure(
              'public.agent_graph_complete_claimed_failure_parent_and_seal_v12(jsonb,character,bigint,character varying,character)'))
    ) THEN
        RAISE EXCEPTION 'failure resumer has non-exact function authority'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p', 'S')
          AND (
            pg_catalog.has_table_privilege(
              'emergeos_graph_executor', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_table_privilege(
              'emergeos_failure_resumer', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_table_privilege(
              'emergeos_provider_attestor', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_table_privilege(
              'emergeos_provider_attestor_v14', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_table_privilege(
              'emergeos_provider_attestor_v15', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_table_privilege(
              'emergeos_provider_attestor_v16', relation.oid,
              'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
          )
    ) THEN
        RAISE EXCEPTION 'graph executor has relation authority'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p')
          AND (
            pg_catalog.has_table_privilege(
                'emergeos_exact_overlay_reader_v19', relation.oid,
                'SELECT')
              <> (relation.relname = ANY(exact_overlay_reader_relations))
            OR pg_catalog.has_table_privilege(
                'emergeos_exact_overlay_reader_v19', relation.oid,
                'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
            OR pg_catalog.has_table_privilege(
                'emergeos_exact_overlay_reader_v19', relation.oid,
                'SELECT WITH GRANT OPTION,INSERT WITH GRANT OPTION,'
                  || 'UPDATE WITH GRANT OPTION,DELETE WITH GRANT OPTION,'
                  || 'TRUNCATE WITH GRANT OPTION,'
                  || 'REFERENCES WITH GRANT OPTION,'
                  || 'TRIGGER WITH GRANT OPTION,'
                  || 'MAINTAIN WITH GRANT OPTION')
          )
    ) OR EXISTS (
        WITH exact_sequences AS MATERIALIZED (
            SELECT relation.oid
            FROM pg_catalog.pg_sequences sequence_row
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.nspname = sequence_row.schemaname
            JOIN pg_catalog.pg_class relation
              ON relation.relnamespace = namespace.oid
             AND relation.relname = sequence_row.sequencename
            WHERE sequence_row.schemaname = target_schema
        )
        SELECT 1
        FROM exact_sequences sequence_row
        WHERE pg_catalog.has_sequence_privilege(
              'emergeos_exact_overlay_reader_v19', sequence_row.oid,
              'USAGE,SELECT,UPDATE')
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc procedure
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = procedure.pronamespace
        WHERE namespace.nspname = target_schema
          AND pg_catalog.has_function_privilege(
              'emergeos_exact_overlay_reader_v19', procedure.oid,
              'EXECUTE')
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
        WHERE namespace.nspname = target_schema
          AND relation.relkind IN ('r', 'p')
          AND grantee.rolname = 'emergeos_exact_overlay_reader_v19'
          AND acl.privilege_type = 'SELECT'
          AND NOT acl.is_grantable
    ) <> 13 THEN
        RAISE EXCEPTION 'Pack010 V19 exact-overlay reader authority drifted'
            USING ERRCODE = '55000';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute attribute
        JOIN pg_catalog.pg_class relation
          ON relation.oid = attribute.attrelid
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = target_schema
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
          AND attribute.attacl IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Pack010 column ACL drifted'
            USING ERRCODE = '55000';
    END IF;
END;
$audit$;

SELECT role.rolname AS role_name,
       role.rolcanlogin AS can_login,
       role.rolinherit AS inherit,
       role.rolsuper AS superuser,
       pg_catalog.md5(
           role.rolname || ':' || role.rolcanlogin::TEXT || ':'
           || role.rolinherit::TEXT || ':' || role.rolsuper::TEXT
       ) AS non_secret_identity_fingerprint
FROM pg_catalog.pg_roles role
WHERE role.rolname IN (
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
)
ORDER BY role.rolname;
