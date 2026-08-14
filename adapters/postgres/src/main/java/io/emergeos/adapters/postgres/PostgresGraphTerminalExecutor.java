package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restricted V10 terminal writer.
 *
 * <p>This boundary is intentionally package-private and is not wired into the
 * shipping application. It owns no raw relation DML: each transaction can
 * invoke exactly one schema-qualified V10 semantic function.
 */
final class PostgresGraphTerminalExecutor {

  enum ProbePoint {
    AFTER_AUTHORITY_RECHECK,
    AFTER_SEMANTIC_FUNCTION
  }

  @FunctionalInterface
  interface Probe {
    void hit(ProbePoint point);
  }

  private static final Probe NOOP = ignored -> {};

  private final JdbcClient jdbc;
  private final TransactionTemplate writes;
  private final Probe probe;
  private final ExecutorAuthority frozen;

  PostgresGraphTerminalExecutor(DataSource dataSource) {
    this(dataSource, NOOP);
  }

  PostgresGraphTerminalExecutor(DataSource dataSource, Probe probe) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = JdbcClient.create(dataSource);
    this.probe = Objects.requireNonNull(probe, "probe");
    DataSourceTransactionManager manager =
        new DataSourceTransactionManager(dataSource);
    this.writes = new TransactionTemplate(manager);
    this.writes.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.frozen = executeVerified(this::loadAuthority);
    if (!this.frozen.equals(executeVerified(this::loadAuthority))) {
      throw new GraphAttemptIntegrityException();
    }
  }

  DatabaseIdentity databaseIdentity() {
    return frozen.databaseIdentity();
  }

  private String completeChild(String payload) {
    return executeSemantic(
        """
        SELECT public.agent_graph_complete_child_v10(
          CAST(:payload AS jsonb)
        )::text
        """,
        payload);
  }

  private String completeParentAndSeal(String payload) {
    return executeSemantic(
        """
        SELECT public.agent_graph_complete_parent_and_seal_v10(
          CAST(:payload AS jsonb)
        )::text
        """,
        payload);
  }

  String completeChild(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      PostgresGraphRuntimeWriters.ChildTerminalTransition transition) {
    String payload =
        Objects.requireNonNull(transition, "transition")
            .payloadForExecutor();
    return completeChild(manifest, expiresAt, payload);
  }

  private String completeChild(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      String payload) {
    return executeSemantic(
        """
        SELECT CASE
          WHEN pg_catalog.clock_timestamp()
                 < CAST(:expiresAt AS timestamptz)
            THEN public.agent_graph_complete_child_v10(
              CAST(:payload AS jsonb)
            )
          ELSE NULL
        END::text
        """,
        Objects.requireNonNull(manifest, "manifest"),
        Objects.requireNonNull(expiresAt, "expiresAt"),
        payload);
  }

  String completeParentAndSeal(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      PostgresGraphRuntimeWriters.ParentTerminalTransition transition) {
    String payload =
        Objects.requireNonNull(transition, "transition")
            .payloadForExecutor();
    return completeParentAndSeal(manifest, expiresAt, payload);
  }

  String completePreCandidateFailureChild(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      PostgresGraphRuntimeWriters.ChildFailureTransition transition) {
    String payload =
        Objects.requireNonNull(transition, "transition")
            .payloadForExecutor();
    return completeChild(manifest, expiresAt, payload);
  }

  String completePreCandidateFailureParentAndSeal(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      PostgresGraphRuntimeWriters.ParentFailureTransition transition) {
    String payload =
        Objects.requireNonNull(transition, "transition")
            .payloadForExecutor();
    return completeParentAndSeal(manifest, expiresAt, payload);
  }

  private String completeParentAndSeal(
      GraphAttemptManifest manifest,
      Instant expiresAt,
      String payload) {
    return executeSemantic(
        """
        SELECT CASE
          WHEN pg_catalog.clock_timestamp()
                 < CAST(:expiresAt AS timestamptz)
            THEN public.agent_graph_complete_parent_and_seal_v10(
              CAST(:payload AS jsonb)
            )
          ELSE NULL
        END::text
        """,
        Objects.requireNonNull(manifest, "manifest"),
        Objects.requireNonNull(expiresAt, "expiresAt"),
        payload);
  }

  private String executeSemantic(String sql, String payload) {
    return executeSemantic(sql, null, null, payload);
  }

  private String executeSemantic(
      String sql,
      GraphAttemptManifest expectedManifest,
      Instant expiresAt,
      String payload) {
    Objects.requireNonNull(payload, "payload");
    try {
      return executeVerified(
          () -> {
            if (!frozen.equals(loadAuthority())) {
              throw new GraphAttemptIntegrityException();
            }
            if (expectedManifest != null
                && !payloadMatches(expectedManifest, payload)) {
              throw new GraphAttemptIntegrityException();
            }
            probe.hit(ProbePoint.AFTER_AUTHORITY_RECHECK);
            var statement = jdbc.sql(sql).param("payload", payload);
            if (expiresAt != null) {
              statement =
                  statement.param(
                      "expiresAt",
                      OffsetDateTime.ofInstant(
                          expiresAt, ZoneOffset.UTC));
            }
            String result = statement.query(String.class).single();
            if (result == null) {
              throw new GraphAttemptIntegrityException();
            }
            probe.hit(ProbePoint.AFTER_SEMANTIC_FUNCTION);
            return result;
          });
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private boolean payloadMatches(
      GraphAttemptManifest manifest, String payload) {
    Boolean matches =
        jdbc.sql(
                """
                SELECT COALESCE(
                  CAST(:payload AS jsonb) ->> 'principal_id'
                    = :principalId
                  AND CAST(:payload AS jsonb) ->> 'attempt_id'
                    = :attemptId,
                  false)
                """)
            .param("payload", payload)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(matches);
  }

  private <T> T executeVerified(java.util.function.Supplier<T> work) {
    T result =
        writes.execute(
            ignored -> {
              jdbc.sql(
                      "SET LOCAL search_path = pg_catalog, public, pg_temp")
                  .update();
              return work.get();
            });
    return Objects.requireNonNull(result, "terminal transaction result");
  }

  private ExecutorAuthority loadAuthority() {
    try {
      return queryAuthority();
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private ExecutorAuthority queryAuthority() {
    ExecutorAuthority authority =
        jdbc.sql(
                """
                WITH exact_functions AS (
                  SELECT procedure.oid,
                         procedure.proowner,
                         procedure.proname,
                         procedure.prosecdef,
                         procedure.proconfig,
                         procedure.provolatile,
                         procedure.proparallel,
                         procedure.prokind,
                         procedure.prorettype,
                         procedure.proretset,
                         procedure.proisstrict,
                         procedure.proleakproof,
                         procedure.prolang,
                         pg_catalog.encode(
                           pg_catalog.sha256(
                             pg_catalog.convert_to(
                               procedure.prosrc, 'UTF8')),
                           'hex') AS definition_fingerprint,
                         pg_get_function_identity_arguments(
                           procedure.oid) AS identity_arguments
                  FROM pg_proc procedure
                  JOIN pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  WHERE namespace.nspname = 'public'
                    AND procedure.proname IN (
                      'agent_graph_complete_child_v10',
                      'agent_graph_complete_parent_and_seal_v10'
                    )
                ), exact_helpers AS (
                  SELECT procedure.oid,
                         procedure.proowner,
                         procedure.proname,
                         procedure.prosecdef,
                         procedure.proconfig,
                         procedure.provolatile,
                         procedure.proparallel,
                         procedure.prokind,
                         procedure.proretset,
                         procedure.proisstrict,
                         procedure.proleakproof,
                         language.lanname,
                         pg_catalog.pg_get_function_identity_arguments(
                           procedure.oid) AS identity_arguments,
                         pg_catalog.pg_get_function_result(
                           procedure.oid) AS function_result,
                         pg_catalog.encode(
                           pg_catalog.sha256(
                             pg_catalog.convert_to(
                               procedure.prosrc, 'UTF8')),
                           'hex') AS definition_fingerprint
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
                      'agent_graph_require_row_shape_v9',
                      'agent_graph_run_selector_guard_v8',
                      'agent_graph_run_selector_guard_v10',
                      'agent_graph_terminal_run_valid_v8',
                      'agent_graph_utf16_length_v8',
                      'agent_worker_graph_guard_v6',
                      'emergeos_pack010_schema_version_v1'
                    )
                ), effective_functions AS (
                  SELECT procedure.oid,
                         procedure.proname,
                         procedure.proowner,
                         pg_get_function_identity_arguments(
                           procedure.oid) AS identity_arguments
                  FROM pg_proc procedure
                  JOIN pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  WHERE namespace.nspname = 'public'
                    AND has_function_privilege(
                      current_user, procedure.oid, 'EXECUTE')
                )
                SELECT
                  session_user AS session_user_name,
                  current_user AS current_user_name,
                  current_database() AS database_name,
                  role.oid::text AS role_oid,
                  database.oid::text AS database_oid,
                  namespace.oid::text AS schema_oid,
                  COALESCE(
                    pg_catalog.inet_server_addr()::text,
                    'local-socket') AS server_address,
                  COALESCE(
                    pg_catalog.inet_server_port(), -1) AS server_port,
                  role.rolcanlogin,
                  role.rolinherit,
                  role.rolsuper,
                  role.rolcreatedb,
                  role.rolcreaterole,
                  role.rolreplication,
                  role.rolbypassrls,
                  (
                    SELECT count(*)
                    FROM pg_auth_members membership
                    WHERE membership.member = role.oid
                  ) AS memberships,
                  (
                    SELECT count(*)
                    FROM pg_auth_members membership
                    WHERE membership.roleid = role.oid
                  ) AS members_of_executor,
                  has_database_privilege(
                    current_user, current_database(), 'CONNECT')
                    AS database_connect,
                  has_database_privilege(
                    current_user, current_database(),
                    'CONNECT WITH GRANT OPTION')
                    AS database_connect_grant_option,
                  has_database_privilege(
                    current_user, current_database(), 'CREATE')
                    AS database_create,
                  has_database_privilege(
                    current_user, current_database(), 'TEMPORARY')
                    AS database_temporary,
                  has_database_privilege(
                    current_user, current_database(),
                    'TEMPORARY WITH GRANT OPTION')
                    AS database_temporary_grant_option,
                  has_schema_privilege(
                    current_user, 'public', 'USAGE')
                    AS schema_usage,
                  has_schema_privilege(
                    current_user, 'public',
                    'USAGE WITH GRANT OPTION')
                    AS schema_usage_grant_option,
                  has_schema_privilege(
                    current_user, 'public', 'CREATE')
                    AS schema_create,
                  (
                    SELECT count(*)
                    FROM pg_class relation
                    JOIN pg_namespace relation_namespace
                      ON relation_namespace.oid
                           = relation.relnamespace
                    WHERE relation_namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p', 'v', 'm')
                      AND (
                        relation.relowner = role.oid
                        OR has_table_privilege(
                          current_user, relation.oid, 'SELECT')
                        OR has_table_privilege(
                          current_user, relation.oid, 'INSERT')
                        OR has_table_privilege(
                          current_user, relation.oid, 'UPDATE')
                        OR has_table_privilege(
                          current_user, relation.oid, 'DELETE')
                        OR has_table_privilege(
                          current_user, relation.oid, 'TRUNCATE')
                        OR has_table_privilege(
                          current_user, relation.oid, 'REFERENCES')
                        OR has_table_privilege(
                          current_user, relation.oid, 'TRIGGER')
                        OR has_table_privilege(
                          current_user, relation.oid, 'MAINTAIN')
                        OR has_any_column_privilege(
                          current_user, relation.oid, 'SELECT')
                        OR has_any_column_privilege(
                          current_user, relation.oid, 'INSERT')
                        OR has_any_column_privilege(
                          current_user, relation.oid, 'UPDATE')
                        OR has_any_column_privilege(
                          current_user, relation.oid, 'REFERENCES')
                      )
                  ) AS relation_authorities,
                  (
                    SELECT count(*)
                    FROM effective_functions
                  ) AS executable_functions,
                  (
                    SELECT string_agg(
                      proname || '(' || identity_arguments || ')',
                      ',' ORDER BY proname)
                    FROM effective_functions
                  ) AS function_surface,
                  (
                    SELECT string_agg(
                      oid::text, ',' ORDER BY proname)
                    FROM exact_functions
                  ) AS function_oids,
                  (
                    SELECT count(*)
                    FROM exact_functions
                  ) AS exact_function_count,
                  (
                    SELECT bool_and(
                      function.prosecdef
                      AND function.proconfig
                            = ARRAY['search_path=pg_catalog, pg_temp']
                      AND function.provolatile = 'v'
                      AND function.proparallel = 'u'
                      AND function.prokind = 'f'
                      AND function.prorettype = 'jsonb'::regtype
                      AND NOT function.proretset
                      AND NOT function.proisstrict
                      AND NOT function.proleakproof
                      AND function.identity_arguments = 'payload jsonb'
                      AND language.lanname = 'plpgsql')
                    FROM exact_functions function
                    JOIN pg_language language
                      ON language.oid = function.prolang
                  ) AS exact_function_properties,
                  (
                    SELECT string_agg(
                      proname || ':' || definition_fingerprint,
                      ',' ORDER BY proname)
                    FROM exact_functions
                  ) AS function_definition_fingerprints,
                  (
                    SELECT count(DISTINCT proowner)
                    FROM exact_functions
                  ) AS function_owner_count,
                  (
                    SELECT min(proowner)::text
                    FROM exact_functions
                  ) AS function_owner_oid,
                  (
                    SELECT bool_and(
                      NOT owner.rolcanlogin
                      AND NOT owner.rolinherit
                      AND NOT owner.rolsuper
                      AND NOT owner.rolcreatedb
                      AND NOT owner.rolcreaterole
                      AND NOT owner.rolreplication
                      AND NOT owner.rolbypassrls
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pg_auth_members membership
                        WHERE membership.member = owner.oid
                           OR membership.roleid = owner.oid))
                    FROM exact_functions function
                    JOIN pg_roles owner
                      ON owner.oid = function.proowner
                  ) AS function_owner_safe,
                  (
                    SELECT count(*)
                    FROM pg_class relation
                    JOIN pg_namespace relation_namespace
                      ON relation_namespace.oid
                           = relation.relnamespace
                    WHERE relation_namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p')
                      AND relation.relowner IN (
                        SELECT proowner FROM exact_functions)
                  ) AS function_owner_relations,
                  (
                    SELECT count(*)
                    FROM exact_functions function
                    CROSS JOIN LATERAL aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_proc
                         WHERE oid = function.oid),
                        acldefault('f', function.proowner)
                      )
                    ) acl
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND (
                        acl.grantee = 0
                        OR acl.grantee NOT IN (
                          function.proowner, role.oid)
                        OR (acl.grantee = role.oid
                            AND acl.is_grantable)
                      )
                  ) AS function_acl_violations,
                  (
                    SELECT count(*)
                    FROM exact_functions function
                    CROSS JOIN LATERAL aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_proc
                         WHERE oid = function.oid),
                        acldefault('f', function.proowner)
                      )
                    ) acl
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND acl.grantee = role.oid
                      AND NOT acl.is_grantable
                  ) AS executor_function_grants,
                  (
                    SELECT count(*)
                    FROM exact_helpers
                  ) AS exact_helper_count,
                  (
                    SELECT string_agg(
                      proname || '(' || identity_arguments || ')' || ':'
                        || function_result || ':' || prosecdef::text || ':'
                        || provolatile::text || ':' || proparallel::text || ':'
                        || prokind::text || ':' || proretset::text || ':'
                        || proisstrict::text || ':' || proleakproof::text || ':'
                        || lanname || ':'
                        || pg_catalog.array_to_string(proconfig, ';') || ':'
                        || definition_fingerprint,
                      ',' ORDER BY proname)
                    FROM exact_helpers
                  ) AS helper_definition_surface,
                  (
                    SELECT count(DISTINCT proowner)
                    FROM exact_helpers
                  ) AS helper_owner_count,
                  (
                    SELECT min(proowner)::text
                    FROM exact_helpers
                  ) AS helper_owner_oid,
                  (
                    SELECT bool_and(
                      NOT owner.rolcanlogin
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
                    FROM exact_helpers helper
                    JOIN pg_catalog.pg_roles owner
                      ON owner.oid = helper.proowner
                  ) AS helper_owner_safe,
                  (
                    SELECT count(*)
                    FROM exact_helpers helper
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_catalog.pg_proc
                         WHERE oid = helper.oid),
                        pg_catalog.acldefault('f', helper.proowner))
                    ) acl
                    LEFT JOIN pg_catalog.pg_roles grantee
                      ON grantee.oid = acl.grantee
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND (
                        acl.grantee = 0
                        OR (acl.grantee <> helper.proowner
                            AND (
                              acl.is_grantable
                              OR NOT (
                                (acl.grantee = (
                                  SELECT min(proowner)
                                  FROM exact_functions)
                                 AND helper.proname IN (
                                  'agent_graph_run_selector_guard_v10',
                                  'agent_graph_require_row_shape_v9',
                                  'agent_graph_require_executor_v9',
                                  'agent_graph_require_executor_v10',
                                  'agent_assert_graph_attempt_v7',
                                  'agent_assert_graph_attempt_v8',
                                  'agent_assert_worker_graph_v6',
                                  'agent_graph_terminal_run_valid_v8',
                                  'agent_graph_utf16_length_v8',
                                  'agent_graph_authorize_terminal_v8'))
                                OR (grantee.rolname
                                      = 'emergeos_graph_prefix_writer'
                                    AND helper.proname IN (
                                      'agent_assert_graph_attempt_v7',
                                      'agent_assert_graph_attempt_v8',
                                      'agent_assert_worker_graph_v6',
                                      'agent_graph_terminal_run_valid_v8'))
                                OR helper.proname
                                     = 'emergeos_pack010_schema_version_v1'
                              )
                            ))
                      )
                  ) AS helper_acl_violations,
                  (
                    SELECT count(*)
                    FROM exact_helpers helper
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_catalog.pg_proc
                         WHERE oid = helper.oid),
                        pg_catalog.acldefault('f', helper.proowner))
                    ) acl
                    WHERE helper.proname
                            = 'emergeos_pack010_schema_version_v1'
                      AND acl.grantee <> helper.proowner
                  ) AS schema_version_helper_non_owner_acl_count,
                  (
                    SELECT COALESCE(
                      bool_and(
                        acl.grantee <> 0
                        AND acl.privilege_type = 'EXECUTE'
                        AND NOT acl.is_grantable),
                      false)
                    FROM exact_helpers helper
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_catalog.pg_proc
                         WHERE oid = helper.oid),
                        pg_catalog.acldefault('f', helper.proowner))
                    ) acl
                    WHERE helper.proname
                            = 'emergeos_pack010_schema_version_v1'
                      AND acl.grantee <> helper.proowner
                  ) AS schema_version_helper_non_owner_acl_safe,
                  public.emergeos_pack010_schema_version_v1()
                    AS schema_version,
                  (
                    SELECT count(*)
                    FROM exact_helpers helper
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_catalog.pg_proc
                         WHERE oid = helper.oid),
                        pg_catalog.acldefault('f', helper.proowner))
                    ) acl
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND acl.grantee = (
                        SELECT min(proowner) FROM exact_functions)
                      AND NOT acl.is_grantable
                  ) AS helper_terminal_grants,
                  EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_roles prefix_role
                    WHERE prefix_role.rolname
                          = 'emergeos_graph_prefix_writer'
                  ) AS prefix_role_exists,
                  (
                    SELECT count(*)
                    FROM exact_helpers helper
                    CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                        (SELECT proacl
                         FROM pg_catalog.pg_proc
                         WHERE oid = helper.oid),
                        pg_catalog.acldefault('f', helper.proowner))
                    ) acl
                    JOIN pg_catalog.pg_roles grantee
                      ON grantee.oid = acl.grantee
                    WHERE acl.privilege_type = 'EXECUTE'
                      AND grantee.rolname
                            = 'emergeos_graph_prefix_writer'
                      AND NOT acl.is_grantable
                  ) AS helper_prefix_grants,
                  (
                    SELECT count(DISTINCT relation.relowner)
                    FROM pg_class relation
                    JOIN pg_namespace relation_namespace
                      ON relation_namespace.oid = relation.relnamespace
                    WHERE relation_namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p', 'S')
                  ) AS relation_owner_count,
                  (
                    SELECT min(relation.relowner)::text
                    FROM pg_class relation
                    JOIN pg_namespace relation_namespace
                      ON relation_namespace.oid = relation.relnamespace
                    WHERE relation_namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p', 'S')
                  ) AS relation_owner_oid,
                  (
                    SELECT bool_and(
                      NOT owner.rolcanlogin
                      AND NOT owner.rolinherit
                      AND NOT owner.rolsuper
                      AND NOT owner.rolcreatedb
                      AND NOT owner.rolcreaterole
                      AND NOT owner.rolreplication
                      AND NOT owner.rolbypassrls
                      AND owner.oid <> role.oid
                      AND owner.oid NOT IN (
                        SELECT proowner FROM exact_functions)
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pg_auth_members membership
                        WHERE membership.member = owner.oid
                           OR membership.roleid = owner.oid))
                    FROM pg_roles owner
                    WHERE owner.oid IN (
                      SELECT relation.relowner
                      FROM pg_class relation
                      JOIN pg_namespace relation_namespace
                        ON relation_namespace.oid
                             = relation.relnamespace
                      WHERE relation_namespace.nspname = 'public'
                        AND relation.relkind IN ('r', 'p', 'S'))
                  ) AS relation_owner_safe,
                  (
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
                                  trigger.tgtype::text,
                                  trigger.tgattr::text,
                                  COALESCE(
                                    pg_catalog.pg_get_expr(
                                      trigger.tgqual,
                                      trigger.tgrelid),
                                    ''),
                                  pg_catalog.encode(
                                    trigger.tgargs, 'hex'),
                                  (trigger.tgconstraint <> 0)::text,
                                  COALESCE(
                                    constraint_row.condeferrable,
                                    false)::text,
                                  COALESCE(
                                    constraint_row.condeferred,
                                    false)::text,
                                  guard.proname || '('
                                    || pg_catalog.pg_get_function_identity_arguments(
                                        guard.oid)
                                    || ')'),
                                ',' ORDER BY
                                  relation.relname, trigger.tgname),
                              ''),
                            'UTF8')),
                      'hex')
                    FROM pg_trigger trigger
                    JOIN pg_class relation
                      ON relation.oid = trigger.tgrelid
                    JOIN pg_namespace relation_namespace
                      ON relation_namespace.oid = relation.relnamespace
                    JOIN pg_proc guard
                      ON guard.oid = trigger.tgfoid
                    LEFT JOIN pg_constraint constraint_row
                      ON constraint_row.oid = trigger.tgconstraint
                    WHERE relation_namespace.nspname = 'public'
                      AND NOT trigger.tgisinternal
                  ) AS trigger_topology
                FROM pg_roles role
                JOIN pg_database database
                  ON database.datname = current_database()
                JOIN pg_namespace namespace
                  ON namespace.nspname = 'public'
                WHERE role.rolname = current_user
                """)
            .query(
                (resultSet, rowNumber) ->
                    new ExecutorAuthority(
                        resultSet.getString("session_user_name"),
                        resultSet.getString("current_user_name"),
                        resultSet.getString("database_name"),
                        resultSet.getString("role_oid"),
                        resultSet.getString("database_oid"),
                        resultSet.getString("schema_oid"),
                        resultSet.getString("server_address"),
                        resultSet.getInt("server_port"),
                        resultSet.getBoolean("rolcanlogin"),
                        resultSet.getBoolean("rolinherit"),
                        resultSet.getBoolean("rolsuper"),
                        resultSet.getBoolean("rolcreatedb"),
                        resultSet.getBoolean("rolcreaterole"),
                        resultSet.getBoolean("rolreplication"),
                        resultSet.getBoolean("rolbypassrls"),
                        resultSet.getLong("memberships"),
                        resultSet.getLong("members_of_executor"),
                        resultSet.getBoolean("database_connect"),
                        resultSet.getBoolean(
                            "database_connect_grant_option"),
                        resultSet.getBoolean("database_create"),
                        resultSet.getBoolean("database_temporary"),
                        resultSet.getBoolean(
                            "database_temporary_grant_option"),
                        resultSet.getBoolean("schema_usage"),
                        resultSet.getBoolean(
                            "schema_usage_grant_option"),
                        resultSet.getBoolean("schema_create"),
                        resultSet.getLong("relation_authorities"),
                        resultSet.getLong("executable_functions"),
                        resultSet.getString("function_surface"),
                        resultSet.getString("function_oids"),
                        resultSet.getLong("exact_function_count"),
                        resultSet.getBoolean(
                            "exact_function_properties"),
                        resultSet.getString(
                            "function_definition_fingerprints"),
                        resultSet.getLong("function_owner_count"),
                        resultSet.getString("function_owner_oid"),
                        resultSet.getBoolean("function_owner_safe"),
                        resultSet.getLong("function_owner_relations"),
                        resultSet.getLong("function_acl_violations"),
                        resultSet.getLong("executor_function_grants"),
                        resultSet.getLong("exact_helper_count"),
                        resultSet.getString(
                            "helper_definition_surface"),
                        resultSet.getLong("helper_owner_count"),
                        resultSet.getString("helper_owner_oid"),
                        resultSet.getBoolean("helper_owner_safe"),
                        resultSet.getLong("helper_acl_violations"),
                        resultSet.getLong(
                            "schema_version_helper_non_owner_acl_count"),
                        resultSet.getBoolean(
                            "schema_version_helper_non_owner_acl_safe"),
                        resultSet.getLong("helper_terminal_grants"),
                        resultSet.getBoolean("prefix_role_exists"),
                        resultSet.getLong("helper_prefix_grants"),
                        resultSet.getLong("relation_owner_count"),
                        resultSet.getString("relation_owner_oid"),
                        resultSet.getBoolean("relation_owner_safe"),
                        resultSet.getInt("schema_version"),
                        resultSet.getString("trigger_topology")))
            .single();
    return authority.requireExact();
  }

  private record ExecutorAuthority(
      String sessionUser,
      String currentUser,
      String database,
      String roleOid,
      String databaseOid,
      String schemaOid,
      String serverAddress,
      int serverPort,
      boolean login,
      boolean inherit,
      boolean superuser,
      boolean createDatabase,
      boolean createRole,
      boolean replication,
      boolean bypassRowLevelSecurity,
      long memberships,
      long membersOfExecutor,
      boolean databaseConnect,
      boolean databaseConnectGrantOption,
      boolean databaseCreate,
      boolean databaseTemporary,
      boolean databaseTemporaryGrantOption,
      boolean schemaUsage,
      boolean schemaUsageGrantOption,
      boolean schemaCreate,
      long relationAuthorities,
      long executableFunctions,
      String functionSurface,
      String functionOids,
      long exactFunctionCount,
      boolean exactFunctionProperties,
      String functionDefinitionFingerprints,
      long functionOwnerCount,
      String functionOwnerOid,
      boolean functionOwnerSafe,
      long functionOwnerRelations,
      long functionAclViolations,
      long executorFunctionGrants,
      long exactHelperCount,
      String helperDefinitionSurface,
      long helperOwnerCount,
      String helperOwnerOid,
      boolean helperOwnerSafe,
      long helperAclViolations,
      long schemaVersionHelperNonOwnerAclCount,
      boolean schemaVersionHelperNonOwnerAclSafe,
      long helperTerminalGrants,
      boolean prefixRoleExists,
      long helperPrefixGrants,
      long relationOwnerCount,
      String relationOwnerOid,
      boolean relationOwnerSafe,
      int schemaVersion,
      String triggerTopology) {

    private static final String EXACT_FUNCTION_SURFACE =
        "agent_graph_complete_child_v10(payload jsonb),"
            + "agent_graph_complete_parent_and_seal_v10(payload jsonb),"
            + "emergeos_pack010_schema_version_v1()";
    private static final String EXACT_FUNCTION_BODY_FINGERPRINTS =
        "agent_graph_complete_child_v10:"
            + "db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae,"
            + "agent_graph_complete_parent_and_seal_v10:"
            + "57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35";
    private static final String EXACT_HELPER_DEFINITION_SURFACE =
        "agent_assert_graph_attempt_v7(checked_principal character varying, checked_attempt character):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:8c914b316805f490d108122a40da7ed7c5caf618faeb27a0cfc91c59ea0fcedd,"
            + "agent_assert_graph_attempt_v8(checked_principal character varying, checked_attempt character):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:2e1485d7b259b0c3653241de5d0619c609101d9c6afcc77b0b0421eb609b002a,"
            + "agent_assert_worker_graph_v6(checked_principal_id character varying, checked_run_id character varying):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:3fef7a6f1a51c0d3b695866a825d28488bbdd58e690b749ed13b1f0e231431f4,"
            + "agent_graph_assert_failure_terminal_resume_v12():trigger:true:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:fdad487040c702df9b58481e30158a96e1564963babd86b9d4a0d8e39bceebc3,"
            + "agent_graph_assert_row_v7():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:8754463e13085dbd8b6605d87e266f1e865ea4e02c6e014ea09fe986c9c31837,"
            + "agent_graph_assert_row_v8():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:ad59606e3267e9b177403e599936e00728e7a2f145adcfa58b25c276b2604644,"
            + "agent_graph_assert_run_dependency_v8():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:7f64d51e150ebf5dc3acbd2d1a3875d312e603e0dab77d5bae62f981449a6f2d,"
            + "agent_graph_assert_run_row_v7():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:a6af549b056f4719c666fe4cf756fa05bc0ba28e1b2175cffc882c51d7d5b11f,"
            + "agent_graph_authorize_terminal_v8(checked_principal character varying, checked_attempt character, checked_role character varying, checked_run character varying):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:d2bb778b5d916655c4cb5edaa336c3166afc8ace50ad803e428d5ec66a7b64e5,"
            + "agent_graph_head_transition_guard_v8():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:46a2a23112ec62c8eeb573905f096d1c6d30d1e0ba33d2f2c23d5af335001787,"
            + "agent_graph_require_executor_v10(expected_function regprocedure):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:794a126edf1b5a0812ac7feab6ea2be0acc8e808608c83b069b41bf4c6fb394e,"
            + "agent_graph_require_executor_v9(expected_function regprocedure):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:de848b7e1dbbee4ade31e6d28d4d654c2ac45cd7bb6ba0a4860413781bea3233,"
            + "agent_graph_require_row_shape_v9(supplied jsonb, expected_relation regclass, supplied_is_array boolean):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:55d34c4a2f030d396f2dfbd5a2d6864241156b2b4ba239622bc5a3353b16359d,"
            + "agent_graph_run_selector_guard_v10():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:561eaf8977811117ceb8357bfe002f98ab6eba2c52db38885777552454affb0b,"
            + "agent_graph_run_selector_guard_v8():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:1942e5f5f0ccd5e79e57e198d50e41e2af47520dd1947ed7ac7e95924b7bd235,"
            + "agent_graph_terminal_run_valid_v8(checked_principal character varying, checked_attempt character, checked_role character varying):boolean:false:s:u:f:false:false:false:sql:search_path=pg_catalog, public, pg_temp:1e6dc7088b526e77d369a725fbefeeb521284413916ad0073a4ed3699bc97db7,"
            + "agent_graph_utf16_length_v8(input text):integer:false:i:u:f:false:true:false:sql:search_path=pg_catalog, public, pg_temp:2767ad759736bcf480b24ffd451ebd04f4e322ba4d2a1c34c2a4c6007a36b4dc,"
            + "agent_worker_graph_guard_v6():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, public, pg_temp:fe1ce19545179e98080d72037fc7f1ef6ce2f0f5b2d0d07eafa2e73e8c4a9ff3,"
            + "emergeos_pack010_schema_version_v1():integer:true:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143";
    private static final String V16_TRIGGER_TOPOLOGY_FINGERPRINT =
        "e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b";
    private static final String V17_TRIGGER_TOPOLOGY_FINGERPRINT =
        "6b86652d9d132538940ddac92752cd6a8741cff759b413d98e7e10e948c2779b";

    private ExecutorAuthority {
      Objects.requireNonNull(sessionUser, "sessionUser");
      Objects.requireNonNull(currentUser, "currentUser");
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(databaseOid, "databaseOid");
      Objects.requireNonNull(serverAddress, "serverAddress");
    }

    private DatabaseIdentity databaseIdentity() {
      return new DatabaseIdentity(
          currentUser,
          database,
          databaseOid,
          serverAddress,
          serverPort);
    }

    private ExecutorAuthority requireExact() {
      String expectedTriggerTopology =
          switch (schemaVersion) {
            case 16 -> V16_TRIGGER_TOPOLOGY_FINGERPRINT;
            case 17 -> V17_TRIGGER_TOPOLOGY_FINGERPRINT;
            default -> throw new GraphAttemptIntegrityException();
          };
      if (!sessionUser.equals(currentUser)
          || !login
          || inherit
          || superuser
          || createDatabase
          || createRole
          || replication
          || bypassRowLevelSecurity
          || memberships != 0
          || membersOfExecutor != 0
          || !databaseConnect
          || databaseConnectGrantOption
          || databaseCreate
          || databaseTemporary
          || databaseTemporaryGrantOption
          || !schemaUsage
          || schemaUsageGrantOption
          || schemaCreate
          || relationAuthorities != 0
          || executableFunctions != 3
          || !EXACT_FUNCTION_SURFACE.equals(functionSurface)
          || functionOids == null
          || exactFunctionCount != 2
          || !exactFunctionProperties
          || !EXACT_FUNCTION_BODY_FINGERPRINTS.equals(
              functionDefinitionFingerprints)
          || functionOwnerCount != 1
          || functionOwnerOid == null
          || functionOwnerOid.equals(roleOid)
          || !functionOwnerSafe
          || functionOwnerRelations != 0
          || functionAclViolations != 0
          || executorFunctionGrants != 2
          || exactHelperCount != 19
          || !EXACT_HELPER_DEFINITION_SURFACE.equals(
              helperDefinitionSurface)
          || helperOwnerCount != 1
          || helperOwnerOid == null
          || !helperOwnerSafe
          || helperAclViolations != 0
          || schemaVersionHelperNonOwnerAclCount != 4
          || !schemaVersionHelperNonOwnerAclSafe
          || helperTerminalGrants != 11
          || helperPrefixGrants != (prefixRoleExists ? 4 : 0)
          || relationOwnerCount != 1
          || relationOwnerOid == null
          || relationOwnerOid.equals(roleOid)
          || relationOwnerOid.equals(functionOwnerOid)
          || !relationOwnerOid.equals(helperOwnerOid)
          || !relationOwnerSafe
          || !expectedTriggerTopology.equals(
              triggerTopology)) {
        throw new GraphAttemptIntegrityException();
      }
      return this;
    }
  }

  record DatabaseIdentity(
      String role,
      String database,
      String databaseOid,
      String serverAddress,
      int serverPort) {

    DatabaseIdentity {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(databaseOid, "databaseOid");
      Objects.requireNonNull(serverAddress, "serverAddress");
    }
  }
}
