package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.port.GraphAttemptReader;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verified graph reader with exact public product-relation authority.
 *
 * <p>This boundary does not claim database-global SELECT-only authority or
 * tenant isolation.
 */
public final class PostgresGraphAttemptAccess {

  private static final Set<String> REQUIRED_SELECT_RELATIONS =
      Set.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_attributed_failure_outcomes",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");

  private PostgresGraphAttemptAccess() {}

  public static GraphAttemptReader openReader(DataSource dataSource) {
    return new PostgresGraphAttemptStore(
            Objects.requireNonNull(dataSource, "dataSource"))
        .openRestrictedReader();
  }

  static OwnerTtyGraphAuthority openOwnerTtyAuthority(
      DataSource dataSource,
      List<GraphAttemptManifest> serverOwnedManifests,
      Duration challengeTtl) {
    return new OwnerTtyGraphAuthority(
        new PostgresGraphAttemptStore(
            Objects.requireNonNull(dataSource, "dataSource")),
        serverOwnedManifests,
        challengeTtl);
  }

  static ReaderAuthority verifyReaderAuthority(
      JdbcClient jdbc, ReaderAuthority expected) {
    Objects.requireNonNull(jdbc, "jdbc");
    try {
      jdbc.sql(
              "SET LOCAL search_path = pg_catalog, public, pg_temp")
          .update();
      RoleIdentity identity = loadIdentity(jdbc);
      if (!identity.readOnly()
          || !identity.sessionUser().equals(identity.currentUser())
          || !identity.canLogin()
          || identity.superuser()
          || identity.createDatabase()
          || identity.createRole()
          || identity.inherit()
          || identity.replication()
          || identity.bypassRowLevelSecurity()
          || identity.memberships() != 0
          || !identity.databaseConnect()
          || identity.databaseConnectGrantOption()
          || identity.databaseCreate()
          || !identity.schemaUsage()
          || identity.schemaUsageGrantOption()
          || identity.schemaCreate()
          || identity.executableSecurityDefiners() != 0
          || !exactRelationAuthority(jdbc)) {
        throw new GraphAttemptIntegrityException();
      }
      ReaderAuthority observed =
          new ReaderAuthority(
              identity.sessionUser(),
              identity.currentUser(),
              identity.database());
      if (expected != null && !expected.equals(observed)) {
        throw new GraphAttemptIntegrityException();
      }
      return observed;
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private static RoleIdentity loadIdentity(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT
              session_user AS session_user_name,
              current_user AS current_user_name,
              current_database() AS database_name,
              current_setting('transaction_read_only')::boolean
                AS read_only,
              role.rolcanlogin,
              role.rolsuper,
              role.rolcreatedb,
              role.rolcreaterole,
              role.rolinherit,
              role.rolreplication,
              role.rolbypassrls,
              (
                SELECT count(*)
                FROM pg_auth_members membership
                WHERE membership.member = role.oid
              ) AS memberships,
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
                FROM pg_proc procedure
                JOIN pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'public'
                  AND procedure.prosecdef
                  AND has_function_privilege(
                    current_user, procedure.oid, 'EXECUTE')
              ) AS executable_security_definers
            FROM pg_roles role
            WHERE role.rolname = current_user
            """)
        .query(
            (resultSet, rowNumber) ->
                new RoleIdentity(
                    resultSet.getString("session_user_name"),
                    resultSet.getString("current_user_name"),
                    resultSet.getString("database_name"),
                    resultSet.getBoolean("read_only"),
                    resultSet.getBoolean("rolcanlogin"),
                    resultSet.getBoolean("rolsuper"),
                    resultSet.getBoolean("rolcreatedb"),
                    resultSet.getBoolean("rolcreaterole"),
                    resultSet.getBoolean("rolinherit"),
                    resultSet.getBoolean("rolreplication"),
                    resultSet.getBoolean("rolbypassrls"),
                    resultSet.getLong("memberships"),
                    resultSet.getBoolean("database_connect"),
                    resultSet.getBoolean(
                        "database_connect_grant_option"),
                    resultSet.getBoolean("database_create"),
                    resultSet.getBoolean("schema_usage"),
                    resultSet.getBoolean(
                        "schema_usage_grant_option"),
                    resultSet.getBoolean("schema_create"),
                    resultSet.getLong(
                        "executable_security_definers")))
        .single();
  }

  private static boolean exactRelationAuthority(JdbcClient jdbc) {
    List<RelationAuthority> relations =
        jdbc.sql(
                """
                SELECT
                  relation.relname,
                  has_table_privilege(
                    current_user, relation.oid, 'SELECT') AS can_select,
                  has_table_privilege(
                    current_user, relation.oid,
                    'SELECT WITH GRANT OPTION')
                    AS can_grant_select,
                  has_any_column_privilege(
                    current_user, relation.oid, 'SELECT')
                    AS has_column_select,
                  has_any_column_privilege(
                    current_user, relation.oid,
                    'SELECT WITH GRANT OPTION')
                    AS can_grant_column_select,
                  has_table_privilege(
                    current_user, relation.oid, 'INSERT') AS can_insert,
                  has_table_privilege(
                    current_user, relation.oid, 'UPDATE') AS can_update,
                  has_table_privilege(
                    current_user, relation.oid, 'DELETE') AS can_delete,
                  has_table_privilege(
                    current_user, relation.oid, 'TRUNCATE') AS can_truncate,
                  has_table_privilege(
                    current_user, relation.oid, 'REFERENCES')
                    AS can_references,
                  has_table_privilege(
                    current_user, relation.oid, 'TRIGGER') AS can_trigger,
                  has_table_privilege(
                    current_user, relation.oid, 'MAINTAIN') AS can_maintain,
                  has_any_column_privilege(
                    current_user, relation.oid, 'INSERT')
                    OR has_any_column_privilege(
                      current_user, relation.oid, 'UPDATE')
                    OR has_any_column_privilege(
                      current_user, relation.oid, 'REFERENCES')
                    AS has_column_mutation
                FROM pg_class relation
                JOIN pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                ORDER BY relation.relname
                """)
            .query(
                (resultSet, rowNumber) ->
                    relationAuthority(resultSet))
            .list();
    Set<String> selectable = new HashSet<>();
    for (RelationAuthority relation : relations) {
      if (relation.canSelect()) {
        selectable.add(relation.name());
      }
      if (relation.hasEscalationOrMutationAuthority()) {
        return false;
      }
    }
    return selectable.equals(REQUIRED_SELECT_RELATIONS);
  }

  private static RelationAuthority relationAuthority(
      java.sql.ResultSet resultSet) throws SQLException {
    return new RelationAuthority(
        resultSet.getString("relname"),
        resultSet.getBoolean("can_select"),
        resultSet.getBoolean("can_grant_select"),
        resultSet.getBoolean("has_column_select"),
        resultSet.getBoolean("can_grant_column_select"),
        resultSet.getBoolean("can_insert"),
        resultSet.getBoolean("can_update"),
        resultSet.getBoolean("can_delete"),
        resultSet.getBoolean("can_truncate"),
        resultSet.getBoolean("can_references"),
        resultSet.getBoolean("can_trigger"),
        resultSet.getBoolean("can_maintain"),
        resultSet.getBoolean("has_column_mutation"));
  }

  record ReaderAuthority(
      String sessionUser, String currentUser, String database) {

    ReaderAuthority {
      Objects.requireNonNull(sessionUser, "sessionUser");
      Objects.requireNonNull(currentUser, "currentUser");
      Objects.requireNonNull(database, "database");
    }
  }

  private record RoleIdentity(
      String sessionUser,
      String currentUser,
      String database,
      boolean readOnly,
      boolean canLogin,
      boolean superuser,
      boolean createDatabase,
      boolean createRole,
      boolean inherit,
      boolean replication,
      boolean bypassRowLevelSecurity,
      long memberships,
      boolean databaseConnect,
      boolean databaseConnectGrantOption,
      boolean databaseCreate,
      boolean schemaUsage,
      boolean schemaUsageGrantOption,
      boolean schemaCreate,
      long executableSecurityDefiners) {}

  private record RelationAuthority(
      String name,
      boolean canSelect,
      boolean canGrantSelect,
      boolean hasColumnSelect,
      boolean canGrantColumnSelect,
      boolean canInsert,
      boolean canUpdate,
      boolean canDelete,
      boolean canTruncate,
      boolean canReferences,
      boolean canTrigger,
      boolean canMaintain,
      boolean hasColumnMutation) {

    private boolean hasEscalationOrMutationAuthority() {
      return canGrantSelect
          || canGrantColumnSelect
          || (!canSelect && hasColumnSelect)
          || canInsert
          || canUpdate
          || canDelete
          || canTruncate
          || canReferences
          || canTrigger
          || canMaintain
          || hasColumnMutation;
    }
  }
}
