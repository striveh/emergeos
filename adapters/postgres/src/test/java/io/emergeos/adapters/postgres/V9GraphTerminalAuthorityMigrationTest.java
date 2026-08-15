package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphPricingSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V9GraphTerminalAuthorityMigrationTest {

  private static final Instant V8_STARTED = Instant.parse("2026-07-31T06:00:00Z");
  private static final String PACK010_SCHEMA_HELPER_SHA =
      "51d4368bbaff6d78ec69a6ae5ea13a746816bf478fe6d58b86319b0a041be7e9";
  private static final String PACK010_FAILURE_GUARD_SHA =
      "40256147c9316e680e6cb276f2b4fa41e90280385e41997a23177425a9f7e36e";
  private static final String PACK010_EXECUTOR_GUARD_SHA =
      "d9909934e3e4e4f015857654c05b02dbe36372ef2421ccd4f8475adb108ee4e1";
  private static final String PACK010_HELPER_CLOSURE_SHA =
      "0bf03dba92918a6b042370debdc818d390095e53e5d49d1aaf8b3e3f72d3ab6c";
  private static final String PACK010_V16_TRIGGER_TOPOLOGY =
      "e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b";
  private static final String PACK010_V17_TRIGGER_TOPOLOGY =
      "6b86652d9d132538940ddac92752cd6a8741cff759b413d98e7e10e948c2779b";
  private static final String PACK010_V18_TRIGGER_TOPOLOGY =
      "0f2cdc12cb4dc587eb0f5bd648fcb3778401b99e1fdd5aa9a0cc4252ba108afb";
  private static final String PACK010_V19_TRIGGER_TOPOLOGY =
      "d08a2c229ef90e16cc6e076fac68671a4f1fe6ebddec9abcfff813e4b81c1827";
  private static final List<String> PACK010_V19_CATALOG_FINGERPRINT =
      List.of(
          "trigger-topology:" + PACK010_V19_TRIGGER_TOPOLOGY,
          "agent_graph_require_executor_v10:"
              + PACK010_EXECUTOR_GUARD_SHA
              + ":e2e5b865650e39fc7ea425edf4001b19",
          "agent_graph_require_failure_resumer_v12:"
              + PACK010_FAILURE_GUARD_SHA
              + ":b7cffa1c95261f33b62f973ffefd6ef7",
          "emergeos_pack010_schema_version_v1:"
              + PACK010_SCHEMA_HELPER_SHA
              + ":5e6d50f28c89c58242b4d90f65472927",
          "helper-closure:19:" + PACK010_HELPER_CLOSURE_SHA);
  private static final String LEGACY_PACK010_SCHEMA_HELPER_SHA =
      "1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143";
  private static final String LEGACY_PACK010_FAILURE_GUARD_SHA =
      "814c2d1a58013749d0441f07411e0065c14acc1d9523bc1ea8d8505218573920";
  private static final String LEGACY_PACK010_EXECUTOR_GUARD_SHA =
      "794a126edf1b5a0812ac7feab6ea2be0acc8e808608c83b069b41bf4c6fb394e";
  private static final String LEGACY_PACK010_HELPER_CLOSURE_SHA =
      "bb6f50aa547584083dfc2f7d797cc2a165161521f53cb6342084fcf0e0eab83b";
  private static final String PACK010_H18_SCHEMA_HELPER_SHA =
      "8cbe0f904dee8af44976b05ba9f0e8481e279a0686eb0357bc7b73b531ce10e3";
  private static final String PACK010_H18_FAILURE_GUARD_SHA =
      "2ec9d651a84a1db7b414cbe86a2e2f8b13793114bb1e29662783eea03245a3e0";
  private static final String PACK010_H18_EXECUTOR_GUARD_SHA =
      "d3bbadacfea777b9d0a5e590ab7e068babc9594a2e50fb5600a7c2438b32777a";
  private static final String PACK010_H18_HELPER_CLOSURE_SHA =
      "3711ff2b89dba5b36e564a8a74a5399d80be8d04178434c74503b688e5f49b9f";
  private static final List<String> PACK010_RUNTIME_ROLES =
      List.of(
          "emergeos_pack010_schema_owner",
          "emergeos_terminal_owner",
          "emergeos_graph_executor",
          "emergeos_failure_resumer",
          "emergeos_provider_attestor",
          "emergeos_provider_attestor_v14",
          "emergeos_provider_attestor_v15",
          "emergeos_provider_attestor_v16",
          "emergeos_graph_prefix_writer",
          "emergeos_graph_reader",
          "emergeos_exact_overlay_reader_v19");
  private static final List<String> V8_TRUTH_TABLES =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_v9_authority")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void freshInstallRequiresExactV9SemanticAuthoritySurface() {
    DataSource dataSource = schemaDataSource("fresh_v9_authority");
    Flyway flyway = flywayAt(dataSource, 9);
    flyway.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);

    assertEquals(MigrationVersion.fromVersion("9"), flyway.info().current().getVersion());
    assertProviderSessionIntentTable(jdbc);
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM " + "agent_graph_provider_session_intents")
            .query(Long.class)
            .single());
    assertEquals(
        List.of("agent_graph_complete_child_v9", "agent_graph_complete_parent_and_seal_v9"),
        jdbc.sql(
                """
                SELECT procedure.proname
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname IN (
                    'agent_graph_complete_child_v9',
                    'agent_graph_complete_parent_and_seal_v9'
                  )
                  AND pg_catalog.pg_get_function_identity_arguments(
                        procedure.oid
                      ) = 'payload jsonb'
                  AND procedure.prosecdef
                  AND procedure.proconfig
                        = ARRAY['search_path=pg_catalog, pg_temp']
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.aclexplode(
                      COALESCE(
                        procedure.proacl,
                        pg_catalog.acldefault('f', procedure.proowner)
                      )
                    ) acl
                    WHERE acl.grantee = 0
                      AND acl.privilege_type = 'EXECUTE'
                  )
                ORDER BY procedure.proname
                """)
            .query(String.class)
            .list());
    assertEquals(
        2L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname IN (
                    'agent_graph_complete_child_v9',
                    'agent_graph_complete_parent_and_seal_v9'
                  )
                """)
            .query(Long.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(
                    procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner)
                  )
                ) acl
                WHERE namespace.nspname = current_schema()
                  AND acl.grantee = 0
                  AND acl.privilege_type = 'EXECUTE'
                """)
            .query(Long.class)
            .single());
    assertEquals(
        1L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_class relation
                  ON relation.oid = trigger.tgrelid
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND relation.relname = 'agent_runs'
                  AND trigger.tgname
                        = 'agent_graph_run_selector_guard_v8'
                  AND NOT trigger.tgisinternal
                """)
            .query(Long.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND pg_catalog.right(procedure.proname, 3) = '_v8'
                  AND procedure.proconfig
                        <> ARRAY[
                          'search_path=pg_catalog, '
                            || current_schema()
                            || ', pg_temp'
                        ]
                """)
            .query(Long.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  COALESCE(
                    procedure.proacl,
                    pg_catalog.acldefault('f', procedure.proowner)
                  )
                ) acl
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname
                        = 'agent_graph_authorize_terminal_v8'
                  AND acl.grantee = 0
                  AND acl.privilege_type = 'EXECUTE'
                """)
            .query(Long.class)
            .single());
    String guardDefinition =
        jdbc.sql(
                """
                SELECT pg_catalog.pg_get_functiondef(procedure.oid)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname
                        = 'agent_graph_run_selector_guard_v9'
                """)
            .query(String.class)
            .single();
    assertFalse(guardDefinition.contains("graph_terminal_permit"));
    assertTrue(guardDefinition.contains("current_user"));
    assertTrue(guardDefinition.contains("proowner"));
    assertTrue(guardDefinition.contains("relowner"));
    assertFalse(
        jdbc.sql(
                """
                SELECT procedure.prosecdef
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname
                        = 'agent_graph_run_selector_guard_v9'
                """)
            .query(Boolean.class)
            .single());
    String executorGuardDefinition =
        jdbc.sql(
                """
                SELECT pg_catalog.pg_get_functiondef(procedure.oid)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname
                        = 'agent_graph_require_executor_v9'
                """)
            .query(String.class)
            .single();
    assertTrue(executorGuardDefinition.contains("session_user"));
    assertTrue(executorGuardDefinition.contains("aclexplode"));
    assertTrue(executorGuardDefinition.contains("has_any_column_privilege"));
  }

  @Test
  void populatedV8UpgradePreservesRowsAndHistoryBeforeV9() {
    DataSource dataSource = schemaDataSource("populated_v8_to_v9");
    Flyway v8 = flywayAt(dataSource, 8);
    v8.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    V7GraphAttemptSqlSeeder.Seed seed =
        new V7GraphAttemptSqlSeeder(dataSource).seedTerminalV8("v9-preserve");
    advanceV8FixtureToAttributedPrefix(dataSource, seed);
    assertEquals(
        List.of(14L, 2L, 0L, 0L),
        jdbc.sql(
                """
                SELECT
                  (SELECT count(*)
                   FROM agent_graph_attempt_events),
                  (SELECT count(*)
                   FROM agent_graph_attempt_provider_attributions),
                  (SELECT count(*)
                   FROM agent_graph_attempt_terminal_bindings),
                  (SELECT count(*)
                   FROM agent_graph_attempt_seals)
                """)
            .query(
                (resultSet, rowNumber) ->
                    List.of(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getLong(3),
                        resultSet.getLong(4)))
            .single());
    Map<String, String> before = rowImages(jdbc);
    List<String> historyBefore = history(jdbc);

    Flyway v9 = flywayAt(dataSource, 9);
    v9.migrate();

    assertEquals(MigrationVersion.fromVersion("9"), v9.info().current().getVersion());
    assertProviderSessionIntentTable(jdbc);
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM " + "agent_graph_provider_session_intents")
            .query(Long.class)
            .single());
    assertEquals(before, rowImages(jdbc));
    assertEquals(
        historyBefore, history(jdbc).stream().filter(row -> !row.startsWith("9|")).toList());
  }

  @Test
  void provisioningKeepsNoLoginOwnersOutsideExactRuntimeAcl() throws IOException {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String schema = "v9_role_split_" + nonce;
    String schemaOwner = "v9_schema_owner_" + nonce;
    String terminalOwner = "v9_terminal_owner_" + nonce;
    String executor = "v9_executor_" + nonce;
    String schemaOwnerPassword = UUID.randomUUID().toString();
    String executorPassword = UUID.randomUUID().toString();
    JdbcClient admin = JdbcClient.create(baseDataSource());
    createLoginRole(admin, schemaOwner, schemaOwnerPassword);
    createNoLoginRole(admin, terminalOwner);
    createLoginRole(admin, executor, executorPassword);
    admin.sql("CREATE SCHEMA " + schema + " AUTHORIZATION " + schemaOwner).update();
    try {
      DataSource migrationDataSource = dataSource(schemaOwner, schemaOwnerPassword, schema);
      Flyway flyway = flywayAt(migrationDataSource, 9);
      flyway.migrate();
      assertEquals(MigrationVersion.fromVersion("9"), flyway.info().current().getVersion());

      admin.sql("ALTER ROLE " + schemaOwner + " NOLOGIN").update();
      admin.sql("GRANT USAGE ON SCHEMA " + schema + " TO " + terminalOwner).update();
      admin
          .sql(
              "GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA "
                  + schema
                  + " TO "
                  + terminalOwner)
          .update();
      admin
          .sql("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA " + schema + " TO " + terminalOwner)
          .update();
      admin
          .sql(
              "ALTER FUNCTION "
                  + schema
                  + ".agent_graph_complete_child_v9(jsonb) OWNER TO "
                  + terminalOwner)
          .update();
      admin
          .sql(
              "ALTER FUNCTION "
                  + schema
                  + ".agent_graph_complete_parent_and_seal_v9(jsonb) "
                  + "OWNER TO "
                  + terminalOwner)
          .update();
      admin
          .sql("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + executor)
          .update();
      admin.sql("GRANT USAGE ON SCHEMA " + schema + " TO " + executor).update();
      admin
          .sql(
              "GRANT EXECUTE ON FUNCTION "
                  + schema
                  + ".agent_graph_complete_child_v9(jsonb), "
                  + schema
                  + ".agent_graph_complete_parent_and_seal_v9(jsonb) "
                  + "TO "
                  + executor)
          .update();

      assertEquals(
          List.of(schemaOwner, terminalOwner),
          admin
              .sql(
                  """
                  SELECT role.rolname
                  FROM pg_catalog.pg_roles role
                  WHERE role.rolname IN (:schemaOwner, :terminalOwner)
                    AND NOT role.rolcanlogin
                    AND NOT role.rolsuper
                    AND NOT role.rolinherit
                  ORDER BY role.rolname
                  """)
              .param("schemaOwner", schemaOwner)
              .param("terminalOwner", terminalOwner)
              .query(String.class)
              .list()
              .stream()
              .sorted()
              .toList());
      assertEquals(
          0L,
          admin
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = relation.relowner
                  WHERE namespace.nspname = :schema
                    AND relation.relkind IN ('r', 'p')
                    AND owner.rolname <> :schemaOwner
                  """)
              .param("schema", schema)
              .param("schemaOwner", schemaOwner)
              .query(Long.class)
              .single());

      assertEquals(
          List.of(
              "agent_graph_complete_child_v9(payload jsonb)",
              "agent_graph_complete_parent_and_seal_v9(payload jsonb)"),
          admin
              .sql(
                  """
                  SELECT procedure.proname || '('
                         || pg_catalog.pg_get_function_identity_arguments(
                              procedure.oid) || ')'
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = procedure.proowner
                  WHERE namespace.nspname = :schema
                    AND owner.rolname = :terminalOwner
                    AND pg_catalog.has_function_privilege(
                      :executor, procedure.oid, 'EXECUTE')
                  ORDER BY procedure.proname
                  """)
              .param("schema", schema)
              .param("terminalOwner", terminalOwner)
              .param("executor", executor)
              .query(String.class)
              .list());
      assertEquals(
          0L,
          admin
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = :schema
                    AND (
                      relation.relowner = (
                        SELECT oid FROM pg_catalog.pg_roles
                        WHERE rolname = :executor)
                      OR pg_catalog.has_table_privilege(
                        :executor, relation.oid,
                        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,'
                          || 'REFERENCES,TRIGGER,MAINTAIN')
                    )
                  """)
              .param("schema", schema)
              .param("executor", executor)
              .query(Long.class)
              .single());
      assertEquals(
          0L,
          admin
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles member
                    ON member.oid = membership.member
                  WHERE member.rolname = :executor
                  """)
              .param("executor", executor)
              .query(Long.class)
              .single());

      String migration =
          new String(
                  V9GraphTerminalAuthorityMigrationTest.class
                      .getResourceAsStream(
                          "/db/migration/" + "V9__split_graph_terminal_authority.sql")
                      .readAllBytes(),
                  StandardCharsets.UTF_8)
              .toUpperCase(java.util.Locale.ROOT);
      assertFalse(migration.contains("CREATE ROLE"));
      assertFalse(migration.contains("ALTER ROLE"));
      assertFalse(migration.contains("PASSWORD"));
      assertFalse(migration.contains("GRAPH_EXECUTOR"));
    } finally {
      admin.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
      dropRole(admin, executor);
      dropRole(admin, terminalOwner);
      dropRole(admin, schemaOwner);
    }
  }

  @Test
  void pack010V19CatalogTopologyMatchesFrozenFingerprint() throws Exception {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String database = "pack010_v19_fingerprint_" + nonce;
    String provisioning = resourceText("/db/provisioning/pack010_runtime_roles.sql");
    String audit = resourceText("/db/provisioning/pack010_runtime_roles_check.sql");
    JdbcClient clusterAdmin = JdbcClient.create(baseDataSource());
    DataSource dataSource = databaseDataSource(database);

    try {
      Flyway v18 = flywayAt(dataSource, 18);
      v18.migrate();
      assertEquals(
          MigrationVersion.fromVersion("18"),
          v18.info().current().getVersion(),
          "PACK010_V19_FINGERPRINT_H18_SOURCE_MIGRATION");
      executeAndCommit(dataSource, provisioning);
      executeAndCommit(dataSource, provisioning);
      executeAndCommit(dataSource, audit);
      installLegacyV18ProvisionedGuardFixture(dataSource);

      Flyway v19 = flywayAt(dataSource, 19);
      v19.migrate();
      assertEquals(
          MigrationVersion.fromVersion("19"),
          v19.info().current().getVersion(),
          "PACK010_V19_FINGERPRINT_MIGRATION");

      JdbcClient jdbc = JdbcClient.create(dataSource);
      assertEquals(
          List.of(
              "agent_graph_require_executor_v10:" + PACK010_H18_EXECUTOR_GUARD_SHA,
              "agent_graph_require_failure_resumer_v12:" + PACK010_H18_FAILURE_GUARD_SHA,
              "emergeos_pack010_schema_version_v1:" + PACK010_H18_SCHEMA_HELPER_SHA),
          pack010AuthorityFunctionHashes(jdbc),
          "PACK010_V19_FINGERPRINT_H18_SOURCE_FUNCTIONS_NOT_EXACT");
      assertEquals(
          "19:" + PACK010_H18_HELPER_CLOSURE_SHA,
          pack010HelperClosure(jdbc),
          "PACK010_V19_FINGERPRINT_H18_SOURCE_CLOSURE_NOT_EXACT");
      List<String> sourceFingerprint = pack010V19CatalogFingerprint(jdbc);
      List<String> derivedV19Fingerprint;
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        JdbcClient transaction =
            JdbcClient.create(new SingleConnectionDataSource(connection, true));
        try {
          derivePack010V19CatalogFingerprintTarget(transaction);
          derivedV19Fingerprint = pack010V19CatalogFingerprint(transaction);
        } finally {
          connection.rollback();
        }
      }
      assertEquals(
          sourceFingerprint,
          pack010V19CatalogFingerprint(jdbc),
          "PACK010_V19_FINGERPRINT_DERIVATION_LEAKED");
      assertEquals(
          PACK010_V19_CATALOG_FINGERPRINT,
          derivedV19Fingerprint,
          "PACK010_V19_ENGINEERING_CATALOG_FINGERPRINT_FREEZE_REQUIRED_"
              + "AUTHORITY_LIVE_REMAIN_FAIL_CLOSED");
    } finally {
      dropDatabase(clusterAdmin, database);
      List<String> roles = new java.util.ArrayList<>(PACK010_RUNTIME_ROLES);
      java.util.Collections.reverse(roles);
      for (String role : roles) {
        if (roleCount(clusterAdmin, List.of(role)) == 1L) {
          dropRole(clusterAdmin, role);
        }
      }
    }
  }

  @Test
  void pack010SchemaVersionAuthorityAcceptsOnlyExactV16ThroughV19() throws Exception {
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String provisioning = resourceText("/db/provisioning/pack010_runtime_roles.sql");
    String audit = resourceText("/db/provisioning/pack010_runtime_roles_check.sql");
    JdbcClient clusterAdmin = JdbcClient.create(baseDataSource());
    Map<Integer, String> databases = new LinkedHashMap<>();
    Map<Integer, DataSource> dataSources = new LinkedHashMap<>();

    try {
      for (int version : List.of(16, 17, 18, 19)) {
        String database = "pack010_v" + version + "_matrix_" + nonce;
        databases.put(version, database);
        DataSource dataSource = databaseDataSource(database);
        dataSources.put(version, dataSource);
        Flyway flyway = flywayAt(dataSource, version);
        flyway.migrate();
        assertEquals(
            MigrationVersion.fromVersion(String.valueOf(version)),
            flyway.info().current().getVersion(),
            "PACK010_SCHEMA_VERSION_MATRIX_MIGRATION_V" + version);

        executeAndCommit(dataSource, provisioning);
        executeAndCommit(dataSource, provisioning);
        executeAndCommit(dataSource, audit);
        assertPack010SchemaAuthority(
            JdbcClient.create(dataSource),
            version,
            expectedTriggerTopology(version),
            "PACK010_SCHEMA_VERSION_MATRIX_V" + version);
      }

      DataSource v17DataSource = dataSources.get(17);
      JdbcClient v17 = JdbcClient.create(v17DataSource);
      installLegacyV17ProvisionedGuardFixture(v17DataSource);
      assertEquals(
          List.of(
              "agent_graph_require_executor_v10:" + LEGACY_PACK010_EXECUTOR_GUARD_SHA,
              "agent_graph_require_failure_resumer_v12:" + LEGACY_PACK010_FAILURE_GUARD_SHA,
              "emergeos_pack010_schema_version_v1:" + LEGACY_PACK010_SCHEMA_HELPER_SHA),
          pack010AuthorityFunctionHashes(v17),
          "PACK010_V17_PROVISIONED_GUARD_FIXTURE_NOT_EXACT");
      assertEquals(
          "19:" + LEGACY_PACK010_HELPER_CLOSURE_SHA,
          pack010HelperClosure(v17),
          "PACK010_V17_PROVISIONED_HELPER_CLOSURE_NOT_EXACT");
      executeAndCommit(v17DataSource, provisioning);
      executeAndCommit(v17DataSource, provisioning);
      executeAndCommit(v17DataSource, audit);
      assertPack010SchemaAuthority(
          v17, 17, PACK010_V17_TRIGGER_TOPOLOGY, "PACK010_V17_PROVISIONED_GUARD_UPGRADE_NOT_GREEN");

      DataSource v18DataSource = dataSources.get(18);
      JdbcClient v18 = JdbcClient.create(v18DataSource);
      installLegacyV18ProvisionedGuardFixture(v18DataSource);
      assertEquals(
          List.of(
              "agent_graph_require_executor_v10:" + PACK010_H18_EXECUTOR_GUARD_SHA,
              "agent_graph_require_failure_resumer_v12:" + PACK010_H18_FAILURE_GUARD_SHA,
              "emergeos_pack010_schema_version_v1:" + PACK010_H18_SCHEMA_HELPER_SHA),
          pack010AuthorityFunctionHashes(v18),
          "PACK010_V18_PROVISIONED_GUARD_FIXTURE_NOT_EXACT");
      assertEquals(
          "19:" + PACK010_H18_HELPER_CLOSURE_SHA,
          pack010HelperClosure(v18),
          "PACK010_V18_PROVISIONED_HELPER_CLOSURE_NOT_EXACT");
      executeAndCommit(v18DataSource, provisioning);
      executeAndCommit(v18DataSource, provisioning);
      executeAndCommit(v18DataSource, audit);
      assertPack010SchemaAuthority(
          v18, 18, PACK010_V18_TRIGGER_TOPOLOGY, "PACK010_V18_PROVISIONED_GUARD_UPGRADE_NOT_GREEN");

      DataSource v19DataSource = dataSources.get(19);
      assertProvisioningRejectedWithoutMutation(
          v19DataSource,
          """
          UPDATE public.flyway_schema_history
          SET version = '20'
          WHERE installed_rank = (
            SELECT max(installed_rank)
            FROM public.flyway_schema_history
          )
          """,
          provisioning,
          "PACK010_FUTURE_SCHEMA_NOT_REJECTED");
      assertProvisioningRejectedWithoutMutation(
          v18DataSource,
          """
          UPDATE public.flyway_schema_history
          SET success = false
          WHERE installed_rank = (
            SELECT max(installed_rank)
            FROM public.flyway_schema_history
          )
          """,
          provisioning,
          "PACK010_FAILED_LATEST_NOT_REJECTED");
      assertProvisioningRejectedWithoutMutation(
          v18DataSource,
          "ALTER TABLE public.flyway_schema_history "
              + "RENAME TO flyway_schema_history_missing_test",
          provisioning,
          "PACK010_MISSING_HISTORY_NOT_REJECTED");
      assertProvisioningRejectedWithoutMutation(
          v18DataSource,
          """
          CREATE OR REPLACE FUNCTION
            public.emergeos_pack010_schema_version_v1()
          RETURNS INTEGER
          LANGUAGE plpgsql
          VOLATILE
          PARALLEL UNSAFE
          SECURITY DEFINER
          SET search_path = pg_catalog, pg_temp
          AS $function$
          BEGIN
            RETURN 18;
          END;
          $function$
          """,
          provisioning,
          "PACK010_HELPER_DRIFT_NOT_REJECTED");
    } finally {
      List<String> databaseNames = new java.util.ArrayList<>(databases.values());
      java.util.Collections.reverse(databaseNames);
      databaseNames.forEach(database -> dropDatabase(clusterAdmin, database));
      List<String> roles = new java.util.ArrayList<>(PACK010_RUNTIME_ROLES);
      java.util.Collections.reverse(roles);
      for (String role : roles) {
        if (roleCount(clusterAdmin, List.of(role)) == 1L) {
          dropRole(clusterAdmin, role);
        }
      }
    }
  }

  @Test
  void independentProvisioningIsTransactionalIdempotentAndFailsOnDrift() throws Exception {
    String database = "pack010_provision_" + UUID.randomUUID().toString().replace("-", "");
    String alternateDatabase = "pack010_alternate_" + UUID.randomUUID().toString().replace("-", "");
    DataSource dataSource = databaseDataSource(database);
    Flyway flyway = flyway(dataSource);
    flyway.migrate();
    JdbcClient admin = JdbcClient.create(dataSource);
    JdbcClient clusterAdmin = JdbcClient.create(baseDataSource());
    JdbcClient scoped = JdbcClient.create(dataSource);
    String provisioning =
        new String(
            V9GraphTerminalAuthorityMigrationTest.class
                .getResourceAsStream("/db/provisioning/pack010_runtime_roles.sql")
                .readAllBytes(),
            StandardCharsets.UTF_8);
    String audit =
        new String(
            V9GraphTerminalAuthorityMigrationTest.class
                .getResourceAsStream("/db/provisioning/pack010_runtime_roles_check.sql")
                .readAllBytes(),
            StandardCharsets.UTF_8);
    List<String> historyBefore = history(scoped);
    List<String> roles =
        List.of(
            "emergeos_pack010_schema_owner",
            "emergeos_terminal_owner",
            "emergeos_graph_executor",
            "emergeos_failure_resumer",
            "emergeos_graph_prefix_writer",
            "emergeos_graph_reader",
            "emergeos_provider_attestor");
    String unknownGrantee = "pack010_unknown_" + UUID.randomUUID().toString().replace("-", "");
    try {
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute(provisioning);
        assertEquals(7L, roleCount(statement, roles));
        connection.rollback();
      }
      assertEquals(0L, roleCount(admin, roles));

      executeAndCommit(dataSource, provisioning);
      executeAndCommit(dataSource, provisioning);
      executeAndCommit(dataSource, audit);
      assertEquals(
          List.of(
              "agent_graph_complete_child_v10:" + "26d1087933677c65697c73e14103c973",
              "agent_graph_complete_child_v9:" + "d16610fd93141a98485a1fd541ca1fe9",
              "agent_graph_complete_parent_and_seal_v10:" + "3bb316c246faa7f28ac8dc2560e45342",
              "agent_graph_complete_parent_and_seal_v9:" + "ac438c4b71c8f044c9189b8be35db918",
              "agent_graph_require_executor_v10:" + "e2e5b865650e39fc7ea425edf4001b19",
              "agent_graph_require_executor_v9:" + "90f8d826c5a2dbd8851f469322500d36",
              "agent_graph_require_failure_resumer_v12:" + "b7cffa1c95261f33b62f973ffefd6ef7",
              "agent_graph_require_row_shape_v9:" + "865db3bb7b9d040e21e7e67fa4592980",
              "agent_graph_run_selector_guard_v10:" + "19e62fb3f842ec2b3ea3df3a174df34c",
              "emergeos_pack010_schema_version_v1:" + "5e6d50f28c89c58242b4d90f65472927"),
          scoped
              .sql(
                  """
                  SELECT procedure.proname || ':'
                         || pg_catalog.md5(procedure.prosrc)
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  WHERE namespace.nspname = 'public'
                    AND procedure.proname IN (
                      'agent_graph_run_selector_guard_v10',
                      'agent_graph_require_row_shape_v9',
                      'agent_graph_require_executor_v9',
                      'agent_graph_require_executor_v10',
                      'agent_graph_require_failure_resumer_v12',
                      'agent_graph_complete_child_v9',
                      'agent_graph_complete_child_v10',
                      'agent_graph_complete_parent_and_seal_v9',
                      'agent_graph_complete_parent_and_seal_v10',
                      'emergeos_pack010_schema_version_v1')
                  ORDER BY procedure.proname
                  """)
              .query(String.class)
              .list());

      assertEquals(historyBefore, history(scoped));
      assertEquals(
          List.of(
              "emergeos_failure_resumer|true|false|false",
              "emergeos_graph_executor|true|false|false",
              "emergeos_graph_prefix_writer|true|false|false",
              "emergeos_graph_reader|true|false|false",
              "emergeos_pack010_schema_owner|false|false|false",
              "emergeos_provider_attestor|true|false|false",
              "emergeos_terminal_owner|false|false|false"),
          admin
              .sql(
                  """
                  SELECT role.rolname || '|' || role.rolcanlogin::text
                         || '|' || role.rolinherit::text
                         || '|' || role.rolsuper::text
                  FROM pg_catalog.pg_roles role
                  WHERE role.rolname IN (
                    'emergeos_terminal_owner',
                    'emergeos_pack010_schema_owner',
                    'emergeos_graph_executor',
                    'emergeos_failure_resumer',
                    'emergeos_graph_prefix_writer',
                    'emergeos_graph_reader',
                    'emergeos_provider_attestor'
                  )
                  ORDER BY role.rolname
                  """)
              .query(String.class)
              .list());
      assertEquals(
          5L,
          admin
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_authid role
                  WHERE role.rolname IN (
                    'emergeos_graph_executor',
                    'emergeos_failure_resumer',
                    'emergeos_graph_prefix_writer',
                    'emergeos_graph_reader',
                    'emergeos_provider_attestor'
                  )
                    AND role.rolpassword IS NULL
                  """)
              .query(Long.class)
              .single());
      assertEquals(
          2L,
          scoped
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = procedure.proowner
                  WHERE namespace.nspname = current_schema()
                    AND procedure.proname IN (
                      'agent_graph_complete_child_v10',
                      'agent_graph_complete_parent_and_seal_v10'
                    )
                    AND owner.rolname = 'emergeos_terminal_owner'
                    AND pg_catalog.has_function_privilege(
                      'emergeos_graph_executor', procedure.oid, 'EXECUTE')
                  """)
              .query(Long.class)
              .single());
      assertEquals(
          0L,
          scoped
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  WHERE namespace.nspname = current_schema()
                    AND procedure.proname IN (
                      'agent_graph_complete_child_v9',
                      'agent_graph_complete_parent_and_seal_v9'
                    )
                    AND pg_catalog.has_function_privilege(
                      'emergeos_graph_executor', procedure.oid, 'EXECUTE')
                  """)
              .query(Long.class)
              .single());
      assertEquals(
          0L,
          scoped
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = relation.relowner
                  WHERE namespace.nspname = current_schema()
                    AND owner.rolname
                          <> 'emergeos_pack010_schema_owner'
                  """)
              .query(Long.class)
              .single());

      String provisionedExecutorPassword = UUID.randomUUID().toString();
      String provisionedWriterPassword = UUID.randomUUID().toString();
      String provisionedReaderPassword = UUID.randomUUID().toString();
      admin
          .sql("ALTER ROLE emergeos_graph_executor PASSWORD '" + provisionedExecutorPassword + "'")
          .update();
      admin
          .sql(
              "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                  + provisionedWriterPassword
                  + "'")
          .update();
      admin
          .sql("ALTER ROLE emergeos_graph_reader PASSWORD '" + provisionedReaderPassword + "'")
          .update();
      DataSource provisionedExecutor =
          databaseDataSource(database, "emergeos_graph_executor", provisionedExecutorPassword);
      DataSource provisionedWriter =
          databaseDataSource(database, "emergeos_graph_prefix_writer", provisionedWriterPassword);
      DataSource provisionedReader =
          databaseDataSource(database, "emergeos_graph_reader", provisionedReaderPassword);
      new PostgresGraphTerminalExecutor(provisionedExecutor);
      DataSource alternateAdmin = databaseDataSource(alternateDatabase);
      flyway(alternateAdmin).migrate();
      executeAndCommit(alternateAdmin, provisioning);
      DataSource alternateExecutor =
          databaseDataSource(
              alternateDatabase, "emergeos_graph_executor", provisionedExecutorPassword);
      DataSource alternateWriter =
          databaseDataSource(
              alternateDatabase, "emergeos_graph_prefix_writer", provisionedWriterPassword);
      assertThrows(
          RuntimeException.class,
          () ->
              new PostgresGraphTerminalExecutor(
                  new AlternatingDataSource(provisionedExecutor, alternateExecutor)));
      assertThrows(
          RuntimeException.class,
          () ->
              PostgresGraphRuntimeWriters.open(
                  new AlternatingDataSource(provisionedWriter, alternateWriter),
                  provisionedExecutor));
      PostgresGraphAttemptAccess.openReader(provisionedReader);
      try (Connection connection = provisionedWriter.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("SET LOCAL emergeos.graph_terminal_permit = 'forged'");
        assertThrows(
            SQLException.class,
            () ->
                statement.execute("UPDATE public.agent_runs " + "SET lifecycle_status = 'FAILED'"));
        connection.rollback();
      }
      try (Connection connection = provisionedWriter.getConnection();
          Statement statement = connection.createStatement()) {
        assertThrows(
            SQLException.class, () -> statement.execute("SET ROLE emergeos_terminal_owner"));
        assertThrows(
            SQLException.class,
            () ->
                statement.execute(
                    "SELECT public.agent_graph_authorize_terminal_v8("
                        + "'synthetic',repeat('0',64)::char(64),"
                        + "'CHILD','synthetic-run')"));
      }
      try (Connection connection = provisionedExecutor.getConnection();
          Statement statement = connection.createStatement()) {
        assertThrows(
            SQLException.class,
            () -> statement.execute("SELECT * FROM public.agent_graph_attempts"));
        assertThrows(
            SQLException.class,
            () ->
                statement.execute(
                    "SELECT public.agent_graph_authorize_terminal_v8("
                        + "'synthetic',repeat('0',64)::char(64),"
                        + "'CHILD','synthetic-run')"));
        assertThrows(
            SQLException.class,
            () ->
                statement.execute("SELECT public.agent_graph_complete_child_v9(" + "'{}'::jsonb)"));
        assertThrows(
            SQLException.class,
            () ->
                statement.execute(
                    "SELECT public.agent_graph_complete_parent_and_seal_v9(" + "'{}'::jsonb)"));
      }
      try (Connection connection = provisionedReader.getConnection();
          Statement statement = connection.createStatement()) {
        assertThrows(
            SQLException.class,
            () -> statement.execute("INSERT INTO public.agent_graph_attempts DEFAULT VALUES"));
      }
      executeAndCommit(dataSource, audit);

      clusterAdmin.sql("CREATE ROLE " + unknownGrantee + " LOGIN PASSWORD NULL NOINHERIT").update();

      for (String drift :
          List.of(
              "GRANT CONNECT ON DATABASE " + database + " TO " + unknownGrantee,
              "REVOKE CONNECT ON DATABASE " + database + " FROM emergeos_provider_attestor",
              "GRANT USAGE ON SCHEMA public TO " + unknownGrantee,
              "GRANT SELECT ON public.agent_runs TO " + unknownGrantee,
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) TO "
                  + unknownGrantee,
              "CREATE VIEW public.pack010_unsupported_view " + "AS SELECT 1 AS value",
              "CREATE FUNCTION public.pack010_unknown_terminal_acl() "
                  + "RETURNS void LANGUAGE sql AS 'SELECT'; "
                  + "ALTER FUNCTION public.pack010_unknown_terminal_acl() "
                  + "OWNER TO emergeos_pack010_schema_owner; "
                  + "REVOKE ALL ON FUNCTION "
                  + "public.pack010_unknown_terminal_acl() FROM PUBLIC; "
                  + "GRANT EXECUTE ON FUNCTION "
                  + "public.pack010_unknown_terminal_acl() "
                  + "TO emergeos_terminal_owner",
              "ALTER DEFAULT PRIVILEGES IN SCHEMA public "
                  + "GRANT SELECT ON TABLES TO "
                  + unknownGrantee,
              "GRANT SELECT ON public.flyway_schema_history " + "TO emergeos_graph_reader",
              "GRANT SELECT ON public.agent_runs "
                  + "TO emergeos_graph_prefix_writer WITH GRANT OPTION",
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_require_row_shape_v9("
                  + "jsonb,regclass,boolean) TO emergeos_graph_executor",
              "ALTER FUNCTION "
                  + "public.agent_graph_require_executor_v9(regprocedure) "
                  + "OWNER TO "
                  + unknownGrantee,
              "ALTER FUNCTION "
                  + "public.agent_graph_require_row_shape_v9("
                  + "jsonb,regclass,boolean) OWNER TO "
                  + unknownGrantee,
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) "
                  + "TO PUBLIC",
              "GRANT SELECT(run_id) ON public.agent_runs " + "TO emergeos_graph_reader",
              "ALTER TABLE public.agent_runs OWNER TO " + POSTGRES.getUsername(),
              "ALTER TABLE public.agent_runs DISABLE TRIGGER "
                  + "agent_graph_run_selector_guard_v10",
              "DROP TRIGGER agent_graph_run_selector_guard_v10 "
                  + "ON public.agent_runs; CREATE TRIGGER "
                  + "agent_graph_run_selector_guard_v10 BEFORE INSERT "
                  + "OR UPDATE OR DELETE ON public.agent_runs "
                  + "FOR EACH ROW WHEN (false) EXECUTE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10()",
              "DROP TRIGGER agent_graph_run_selector_guard_v10 "
                  + "ON public.agent_runs; CREATE TRIGGER "
                  + "agent_graph_run_selector_guard_v10 BEFORE INSERT "
                  + "OR UPDATE OR DELETE ON public.agent_runs "
                  + "FOR EACH ROW EXECUTE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10('drift')",
              "DROP TRIGGER agent_graph_run_selector_guard_v10 "
                  + "ON public.agent_runs; CREATE TRIGGER "
                  + "agent_graph_run_selector_guard_v10 BEFORE INSERT "
                  + "OR UPDATE OF principal_id OR DELETE "
                  + "ON public.agent_runs FOR EACH ROW EXECUTE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10()",
              "DROP TRIGGER agent_graph_run_selector_guard_v10 "
                  + "ON public.agent_runs; CREATE TRIGGER "
                  + "agent_graph_run_selector_guard_v10 AFTER INSERT "
                  + "ON public.agent_runs FOR EACH ROW EXECUTE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10()",
              "CREATE TRIGGER forged_provider_session_trigger BEFORE INSERT "
                  + "ON public.agent_graph_provider_session_intents "
                  + "FOR EACH ROW EXECUTE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10()",
              "CREATE TRIGGER forged_terminal_relation_trigger BEFORE INSERT "
                  + "ON public.agent_graph_attempt_candidates "
                  + "FOR EACH ROW EXECUTE FUNCTION "
                  + "public.agent_graph_reject_immutable_v7()",
              "CREATE SCHEMA AUTHORIZATION " + "emergeos_graph_prefix_writer",
              "CREATE OR REPLACE FUNCTION "
                  + "public.agent_graph_run_selector_guard_v10() "
                  + "RETURNS trigger LANGUAGE plpgsql "
                  + "SET search_path = pg_catalog, pg_temp "
                  + "AS 'BEGIN RETURN NEW; END'",
              "CREATE OR REPLACE FUNCTION "
                  + "public.agent_graph_authorize_terminal_v8("
                  + "checked_principal varchar,checked_attempt char,"
                  + "checked_role varchar,checked_run varchar) "
                  + "RETURNS void LANGUAGE plpgsql "
                  + "SET search_path = pg_catalog, public, pg_temp "
                  + "AS 'BEGIN RETURN; END'",
              "CREATE OR REPLACE FUNCTION "
                  + "public.agent_assert_worker_graph_v6("
                  + "checked_principal_id varchar,"
                  + "checked_run_id varchar) "
                  + "RETURNS void LANGUAGE plpgsql "
                  + "SET search_path = pg_catalog, public, pg_temp "
                  + "AS 'BEGIN RETURN; END'",
              "CREATE OR REPLACE FUNCTION "
                  + "public.agent_graph_head_transition_guard_v8() "
                  + "RETURNS trigger LANGUAGE plpgsql "
                  + "SET search_path = pg_catalog, public, pg_temp "
                  + "AS 'BEGIN RETURN NEW; END'")) {
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()) {
          connection.setAutoCommit(false);
          statement.execute(drift);
          assertThrows(SQLException.class, () -> statement.execute(audit), drift);
          connection.rollback();
        }
      }

      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute(
            "GRANT DELETE ON " + "public" + ".agent_runs TO emergeos_graph_prefix_writer");
        assertThrows(SQLException.class, () -> statement.execute(provisioning));
        connection.rollback();
      }

      String driftMember = "pack010_drift_" + UUID.randomUUID().toString().replace("-", "");
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("CREATE ROLE " + driftMember + " NOLOGIN");
        statement.execute("GRANT emergeos_graph_executor TO " + driftMember);
        assertThrows(SQLException.class, () -> statement.execute(provisioning));
        connection.rollback();
      }
      assertEquals(
          0L,
          admin
              .sql("SELECT count(*) FROM pg_catalog.pg_roles " + "WHERE rolname = :role")
              .param("role", driftMember)
              .query(Long.class)
              .single());

      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute(
            "ALTER FUNCTION "
                + "public"
                + ".agent_graph_complete_child_v10(jsonb) "
                + "SET search_path = public");
        assertThrows(SQLException.class, () -> statement.execute(provisioning));
        connection.rollback();
      }
      executeAndCommit(dataSource, provisioning);
      executeAndCommit(dataSource, audit);
      assertFalse(provisioning.contains("PASSWORD '"));
      assertFalse(provisioning.contains("CREATE TABLE"));
      assertTrue(provisioning.contains("FROM public.flyway_schema_history AS history"));
      assertEquals(
          1L,
          scoped
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = procedure.proowner
                  JOIN pg_catalog.pg_language language
                    ON language.oid = procedure.prolang
                  WHERE procedure.oid = pg_catalog.to_regprocedure(
                    'public.emergeos_pack010_schema_version_v1()')
                    AND namespace.nspname = 'public'
                    AND pg_catalog.pg_get_function_identity_arguments(
                          procedure.oid) = ''
                    AND pg_catalog.pg_get_function_result(procedure.oid)
                          = 'integer'
                    AND procedure.prosecdef
                    AND procedure.provolatile = 'v'
                    AND procedure.proparallel = 'u'
                    AND procedure.prokind = 'f'
                    AND NOT procedure.proretset
                    AND NOT procedure.proisstrict
                    AND NOT procedure.proleakproof
                    AND procedure.proconfig = ARRAY[
                      'search_path=pg_catalog, pg_temp']::text[]
                    AND language.lanname = 'plpgsql'
                    AND owner.rolname = 'emergeos_pack010_schema_owner'
                    AND pg_catalog.encode(
                          pg_catalog.sha256(pg_catalog.convert_to(
                            procedure.prosrc, 'UTF8')), 'hex')
                          = '51d4368bbaff6d78ec69a6ae5ea13a746816bf478fe6d58b86319b0a041be7e9'
                  """)
              .query(Long.class)
              .single());
      assertEquals(
          19,
          scoped
              .sql("SELECT public.emergeos_pack010_schema_version_v1()")
              .query(Integer.class)
              .single());
      assertEquals(
          List.of(
              "emergeos_failure_resumer|EXECUTE|false",
              "emergeos_graph_executor|EXECUTE|false",
              "emergeos_provider_attestor|EXECUTE|false",
              "emergeos_terminal_owner|EXECUTE|false"),
          scoped
              .sql(
                  """
                  SELECT COALESCE(grantee.rolname, 'PUBLIC') || '|'
                         || acl.privilege_type || '|'
                         || acl.is_grantable::text
                  FROM pg_catalog.pg_proc procedure
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                    COALESCE(
                      procedure.proacl,
                      pg_catalog.acldefault('f', procedure.proowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee
                    ON grantee.oid = acl.grantee
                  WHERE procedure.oid = pg_catalog.to_regprocedure(
                    'public.emergeos_pack010_schema_version_v1()')
                    AND acl.grantee <> procedure.proowner
                  ORDER BY COALESCE(grantee.rolname, 'PUBLIC'),
                           acl.privilege_type,
                           acl.is_grantable
                  """)
              .query(String.class)
              .list());
      assertEquals(
          0L,
          scoped
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  CROSS JOIN (VALUES
                    ('emergeos_graph_executor'),
                    ('emergeos_failure_resumer'),
                    ('emergeos_provider_attestor')) runtime(role_name)
                  WHERE namespace.nspname = 'public'
                    AND relation.relname = 'flyway_schema_history'
                    AND (
                      relation.relowner = (
                        SELECT role.oid
                        FROM pg_catalog.pg_roles role
                        WHERE role.rolname = runtime.role_name)
                      OR pg_catalog.has_table_privilege(
                        runtime.role_name, relation.oid,
                        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,'
                          || 'REFERENCES,TRIGGER,MAINTAIN')
                      OR pg_catalog.has_any_column_privilege(
                        runtime.role_name, relation.oid,
                        'SELECT,INSERT,UPDATE,REFERENCES'))
                  """)
              .query(Long.class)
              .single());
    } finally {
      clusterAdmin
          .sql(
              "SELECT pg_terminate_backend(pid) "
                  + "FROM pg_stat_activity "
                  + "WHERE datname = :database "
                  + "AND pid <> pg_backend_pid()")
          .param("database", alternateDatabase)
          .query(Boolean.class)
          .list();
      clusterAdmin.sql("DROP DATABASE IF EXISTS " + alternateDatabase).update();
      clusterAdmin
          .sql(
              "SELECT pg_terminate_backend(pid) "
                  + "FROM pg_stat_activity "
                  + "WHERE datname = :database "
                  + "AND pid <> pg_backend_pid()")
          .param("database", database)
          .query(Boolean.class)
          .list();
      clusterAdmin.sql("DROP DATABASE IF EXISTS " + database).update();
      for (String role : roles) {
        if (roleCount(clusterAdmin, List.of(role)) == 1L) {
          clusterAdmin.sql("DROP ROLE " + role).update();
        }
      }
      if (roleCount(clusterAdmin, List.of(unknownGrantee)) == 1L) {
        clusterAdmin.sql("DROP ROLE " + unknownGrantee).update();
      }
    }
  }

  private static void assertPack010SchemaAuthority(
      JdbcClient jdbc, int expectedVersion, String expectedTopology, String marker) {
    assertEquals(
        expectedVersion,
        jdbc.sql("SELECT public.emergeos_pack010_schema_version_v1()")
            .query(Integer.class)
            .single(),
        marker + "_HELPER_RESULT");
    assertEquals(
        List.of(
            "agent_graph_require_executor_v10:" + PACK010_EXECUTOR_GUARD_SHA,
            "agent_graph_require_failure_resumer_v12:" + PACK010_FAILURE_GUARD_SHA,
            "emergeos_pack010_schema_version_v1:" + PACK010_SCHEMA_HELPER_SHA),
        pack010AuthorityFunctionHashes(jdbc),
        marker + "_FUNCTION_HASHES");
    assertEquals(
        "19:" + PACK010_HELPER_CLOSURE_SHA, pack010HelperClosure(jdbc), marker + "_HELPER_CLOSURE");
    assertEquals(
        List.of(
            "emergeos_failure_resumer|EXECUTE|false",
            "emergeos_graph_executor|EXECUTE|false",
            "emergeos_provider_attestor|EXECUTE|false",
            "emergeos_terminal_owner|EXECUTE|false"),
        pack010SchemaHelperAcl(jdbc),
        marker + "_HELPER_ACL");
    assertEquals(expectedTopology, pack010TriggerTopology(jdbc), marker + "_FULL_TRIGGER_TOPOLOGY");
  }

  private static List<String> pack010AuthorityFunctionHashes(JdbcClient jdbc) {
    return pack010AuthorityFunctionHashes(jdbc, false);
  }

  private static List<String> pack010AuthorityFunctionHashes(JdbcClient jdbc, boolean includeMd5) {
    return jdbc.sql(
            """
            SELECT procedure.proname || ':' || pg_catalog.encode(
                     pg_catalog.sha256(pg_catalog.convert_to(
                       procedure.prosrc, 'UTF8')), 'hex')
                   || CASE WHEN :includeMd5
                        THEN ':' || pg_catalog.md5(procedure.prosrc)
                        ELSE ''
                      END
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = 'public'
              AND procedure.proname IN (
                'agent_graph_require_executor_v10',
                'agent_graph_require_failure_resumer_v12',
                'emergeos_pack010_schema_version_v1')
            ORDER BY procedure.proname
            """)
        .param("includeMd5", includeMd5)
        .query(String.class)
        .list();
  }

  private static List<String> pack010V19CatalogFingerprint(JdbcClient jdbc) {
    List<String> fingerprint = new java.util.ArrayList<>();
    fingerprint.add("trigger-topology:" + pack010TriggerTopology(jdbc));
    fingerprint.addAll(pack010AuthorityFunctionHashes(jdbc, true));
    fingerprint.add("helper-closure:" + pack010HelperClosure(jdbc));
    return List.copyOf(fingerprint);
  }

  private static String pack010HelperClosure(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT count(*)::text || ':' || pg_catalog.encode(
                     pg_catalog.sha256(pg_catalog.convert_to(
                       pg_catalog.string_agg(
                         procedure.proname || '('
                           || pg_catalog.pg_get_function_identity_arguments(
                                procedure.oid)
                           || ')' || ':'
                           || pg_catalog.pg_get_function_result(procedure.oid)
                           || ':' || procedure.prosecdef::text
                           || ':' || procedure.provolatile::text
                           || ':' || procedure.proparallel::text
                           || ':' || procedure.prokind::text
                           || ':' || procedure.proretset::text
                           || ':' || procedure.proisstrict::text
                           || ':' || procedure.proleakproof::text
                           || ':' || language.lanname
                           || ':' || pg_catalog.array_to_string(
                                procedure.proconfig, ';')
                           || ':' || pg_catalog.encode(
                                pg_catalog.sha256(pg_catalog.convert_to(
                                  procedure.prosrc, 'UTF8')), 'hex'),
                         ',' ORDER BY procedure.proname),
                       'UTF8')), 'hex')
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
            """)
        .query(String.class)
        .single();
  }

  private static List<String> pack010SchemaHelperAcl(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT COALESCE(grantee.rolname, 'PUBLIC') || '|'
                   || acl.privilege_type || '|'
                   || acl.is_grantable::text
            FROM pg_catalog.pg_proc procedure
            CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
              procedure.proacl,
              pg_catalog.acldefault('f', procedure.proowner))) acl
            LEFT JOIN pg_catalog.pg_roles grantee
              ON grantee.oid = acl.grantee
            WHERE procedure.oid = pg_catalog.to_regprocedure(
              'public.emergeos_pack010_schema_version_v1()')
              AND acl.grantee <> procedure.proowner
            ORDER BY COALESCE(grantee.rolname, 'PUBLIC'),
                     acl.privilege_type,
                     acl.is_grantable
            """)
        .query(String.class)
        .list();
  }

  private static String pack010TriggerTopology(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT pg_catalog.encode(pg_catalog.sha256(
                     pg_catalog.convert_to(COALESCE(
                       pg_catalog.string_agg(pg_catalog.concat_ws('|',
                         relation.relname,
                         trigger.tgname,
                         trigger.tgenabled,
                         trigger.tgtype::text,
                         trigger.tgattr::text,
                         COALESCE(pg_catalog.pg_get_expr(
                           trigger.tgqual, trigger.tgrelid), ''),
                         pg_catalog.encode(trigger.tgargs, 'hex'),
                         (trigger.tgconstraint <> 0)::text,
                         COALESCE(constraint_row.condeferrable, false)::text,
                         COALESCE(constraint_row.condeferred, false)::text,
                         guard.proname || '('
                           || pg_catalog.pg_get_function_identity_arguments(
                                guard.oid) || ')'),
                       ',' ORDER BY relation.relname, trigger.tgname), ''),
                     'UTF8')), 'hex')
            FROM pg_catalog.pg_trigger trigger
            JOIN pg_catalog.pg_class relation
              ON relation.oid = trigger.tgrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_proc guard
              ON guard.oid = trigger.tgfoid
            LEFT JOIN pg_catalog.pg_constraint constraint_row
              ON constraint_row.oid = trigger.tgconstraint
            WHERE namespace.nspname = 'public'
              AND NOT trigger.tgisinternal
            """)
        .query(String.class)
        .single();
  }

  private static void installLegacyV17ProvisionedGuardFixture(DataSource dataSource)
      throws SQLException {
    installLegacyProvisionedGuardFixture(
        dataSource,
        "NOT IN (''16'', ''17'')",
        LEGACY_PACK010_SCHEMA_HELPER_SHA,
        LEGACY_PACK010_FAILURE_GUARD_SHA,
        LEGACY_PACK010_EXECUTOR_GUARD_SHA);
  }

  private static void installLegacyV18ProvisionedGuardFixture(DataSource dataSource)
      throws SQLException {
    installLegacyProvisionedGuardFixture(
        dataSource,
        "NOT IN (''16'', ''17'', ''18'')",
        PACK010_H18_SCHEMA_HELPER_SHA,
        PACK010_H18_FAILURE_GUARD_SHA,
        PACK010_H18_EXECUTOR_GUARD_SHA);
  }

  private static void installLegacyProvisionedGuardFixture(
      DataSource dataSource,
      String legacyVersionClause,
      String expectedHelperSha,
      String expectedFailureGuardSha,
      String expectedExecutorGuardSha)
      throws SQLException {
    String fixture =
        """
        DO $fixture$
        DECLARE
          helper_body TEXT;
          target_helper_hash TEXT;
          guard_body TEXT;
          guard_name NAME;
          observed_hash TEXT;
        BEGIN
          SELECT procedure.prosrc,
                 pg_catalog.encode(pg_catalog.sha256(
                   pg_catalog.convert_to(procedure.prosrc, 'UTF8')), 'hex')
          INTO helper_body, target_helper_hash
          FROM pg_catalog.pg_proc procedure
          JOIN pg_catalog.pg_namespace namespace
            ON namespace.oid = procedure.pronamespace
          WHERE namespace.nspname = 'public'
            AND procedure.proname =
                  'emergeos_pack010_schema_version_v1';
          helper_body := pg_catalog.replace(
            helper_body,
            'NOT IN (''16'', ''17'', ''18'', ''19'')',
            '__LEGACY_VERSION_CLAUSE__');
          helper_body := pg_catalog.replace(
            helper_body,
            'NOT IN (''16'', ''17'', ''18'')',
            '__LEGACY_VERSION_CLAUSE__');
          observed_hash := pg_catalog.encode(pg_catalog.sha256(
            pg_catalog.convert_to(helper_body, 'UTF8')), 'hex');
          IF observed_hash IS DISTINCT FROM '__EXPECTED_HELPER_SHA__' THEN
            RAISE EXCEPTION 'legacy helper fixture drifted';
          END IF;
          EXECUTE pg_catalog.format(
            'CREATE OR REPLACE FUNCTION '
              || 'public.emergeos_pack010_schema_version_v1() '
              || 'RETURNS INTEGER LANGUAGE plpgsql VOLATILE '
              || 'PARALLEL UNSAFE SECURITY DEFINER '
              || 'SET search_path = pg_catalog, pg_temp AS %L',
            helper_body);

          FOREACH guard_name IN ARRAY ARRAY[
            'agent_graph_require_failure_resumer_v12',
            'agent_graph_require_executor_v10']::NAME[] LOOP
            SELECT procedure.prosrc
            INTO guard_body
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = 'public'
              AND procedure.proname = guard_name
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'expected_function regprocedure';
            guard_body := pg_catalog.replace(
              guard_body,
              target_helper_hash,
              '__EXPECTED_HELPER_SHA__');
            observed_hash := pg_catalog.encode(pg_catalog.sha256(
              pg_catalog.convert_to(guard_body, 'UTF8')), 'hex');
            IF observed_hash IS DISTINCT FROM (CASE guard_name
              WHEN 'agent_graph_require_failure_resumer_v12'
                THEN '__EXPECTED_FAILURE_SHA__'
              WHEN 'agent_graph_require_executor_v10'
                THEN '__EXPECTED_EXECUTOR_SHA__'
              ELSE NULL
            END) THEN
              RAISE EXCEPTION 'legacy guard fixture drifted';
            END IF;
            EXECUTE pg_catalog.format(
              'CREATE OR REPLACE FUNCTION public.%I('
                || 'expected_function REGPROCEDURE) RETURNS VOID '
                || 'LANGUAGE plpgsql VOLATILE PARALLEL UNSAFE '
                || 'SECURITY INVOKER '
                || 'SET search_path = pg_catalog, pg_temp AS %L',
              guard_name,
              guard_body);
          END LOOP;
        END;
        $fixture$;
        """
            .replace("__LEGACY_VERSION_CLAUSE__", legacyVersionClause)
            .replace("__EXPECTED_HELPER_SHA__", expectedHelperSha)
            .replace("__EXPECTED_FAILURE_SHA__", expectedFailureGuardSha)
            .replace("__EXPECTED_EXECUTOR_SHA__", expectedExecutorGuardSha);
    executeAndCommit(dataSource, fixture);
  }

  private static void derivePack010V19CatalogFingerprintTarget(JdbcClient jdbc) {
    String fixture =
        """
        DO $fixture$
        DECLARE
          helper_body TEXT;
          helper_hash TEXT;
          target_helper_body TEXT;
          target_helper_hash TEXT;
          guard_body TEXT;
          guard_hash TEXT;
          target_guard_body TEXT;
          guard_name NAME;
        BEGIN
          SELECT procedure.prosrc,
                 pg_catalog.encode(pg_catalog.sha256(
                   pg_catalog.convert_to(procedure.prosrc, 'UTF8')), 'hex')
          INTO helper_body, helper_hash
          FROM pg_catalog.pg_proc procedure
          JOIN pg_catalog.pg_namespace namespace
            ON namespace.oid = procedure.pronamespace
          WHERE namespace.nspname = 'public'
            AND procedure.proname =
                  'emergeos_pack010_schema_version_v1'
            AND pg_catalog.pg_get_function_identity_arguments(
                  procedure.oid) = '';
          IF helper_hash IS DISTINCT FROM '__H18_HELPER_SHA__'
             OR pg_catalog.strpos(
                  helper_body, 'NOT IN (''16'', ''17'', ''18'')') = 0 THEN
            RAISE EXCEPTION
              'Pack010 V19 fingerprint H18 helper source drifted';
          END IF;

          target_helper_body := pg_catalog.replace(
            helper_body,
            'NOT IN (''16'', ''17'', ''18'')',
            'NOT IN (''16'', ''17'', ''18'', ''19'')');
          IF target_helper_body IS NOT DISTINCT FROM helper_body
             OR pg_catalog.strpos(
                  target_helper_body,
                  'NOT IN (''16'', ''17'', ''18'')') <> 0
             OR pg_catalog.strpos(
                  target_helper_body,
                  'NOT IN (''16'', ''17'', ''18'', ''19'')') = 0 THEN
            RAISE EXCEPTION
              'Pack010 V19 fingerprint helper derivation drifted';
          END IF;
          target_helper_hash := pg_catalog.encode(pg_catalog.sha256(
            pg_catalog.convert_to(target_helper_body, 'UTF8')), 'hex');
          IF target_helper_hash IS NOT DISTINCT FROM helper_hash THEN
            RAISE EXCEPTION
              'Pack010 V19 fingerprint helper hash did not change';
          END IF;
          EXECUTE pg_catalog.format(
            'CREATE OR REPLACE FUNCTION '
              || 'public.emergeos_pack010_schema_version_v1() '
              || 'RETURNS INTEGER LANGUAGE plpgsql VOLATILE '
              || 'PARALLEL UNSAFE SECURITY DEFINER '
              || 'SET search_path = pg_catalog, pg_temp AS %L',
            target_helper_body);

          FOREACH guard_name IN ARRAY ARRAY[
            'agent_graph_require_failure_resumer_v12',
            'agent_graph_require_executor_v10']::NAME[] LOOP
            SELECT procedure.prosrc,
                   pg_catalog.encode(pg_catalog.sha256(
                     pg_catalog.convert_to(
                       procedure.prosrc, 'UTF8')), 'hex')
            INTO guard_body, guard_hash
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            WHERE namespace.nspname = 'public'
              AND procedure.proname = guard_name
              AND pg_catalog.pg_get_function_identity_arguments(
                    procedure.oid) = 'expected_function regprocedure';
            IF guard_hash IS DISTINCT FROM (CASE guard_name
              WHEN 'agent_graph_require_failure_resumer_v12'
                THEN '__H18_FAILURE_SHA__'
              WHEN 'agent_graph_require_executor_v10'
                THEN '__H18_EXECUTOR_SHA__'
              ELSE NULL
            END) THEN
              RAISE EXCEPTION
                'Pack010 V19 fingerprint H18 guard source drifted';
            END IF;

            target_guard_body := pg_catalog.replace(
              guard_body,
              '__H18_HELPER_SHA__',
              target_helper_hash);
            IF target_guard_body IS NOT DISTINCT FROM guard_body
               OR pg_catalog.strpos(
                    target_guard_body, '__H18_HELPER_SHA__') <> 0
               OR pg_catalog.strpos(
                    target_guard_body, target_helper_hash) = 0 THEN
              RAISE EXCEPTION
                'Pack010 V19 fingerprint guard derivation drifted';
            END IF;
            EXECUTE pg_catalog.format(
              'CREATE OR REPLACE FUNCTION public.%I('
                || 'expected_function REGPROCEDURE) RETURNS VOID '
                || 'LANGUAGE plpgsql VOLATILE PARALLEL UNSAFE '
                || 'SECURITY INVOKER '
                || 'SET search_path = pg_catalog, pg_temp AS %L',
              guard_name,
              target_guard_body);
          END LOOP;
        END;
        $fixture$;
        """
            .replace("__H18_HELPER_SHA__", PACK010_H18_SCHEMA_HELPER_SHA)
            .replace("__H18_FAILURE_SHA__", PACK010_H18_FAILURE_GUARD_SHA)
            .replace("__H18_EXECUTOR_SHA__", PACK010_H18_EXECUTOR_GUARD_SHA);
    jdbc.sql(fixture).update();
  }

  private static void assertProvisioningRejectedWithoutMutation(
      DataSource dataSource, String setup, String provisioning, String marker) throws SQLException {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    List<String> historyBefore = history(jdbc);
    List<String> hashesBefore = pack010AuthorityFunctionHashes(jdbc);
    List<String> aclBefore = pack010SchemaHelperAcl(jdbc);
    String topologyBefore = pack010TriggerTopology(jdbc);
    long rolesBefore = roleCount(jdbc, PACK010_RUNTIME_ROLES);

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute(setup);
        SQLException rejection =
            assertThrows(SQLException.class, () -> statement.execute(provisioning), marker);
        assertEquals("55000", rejection.getSQLState(), marker + "_SQLSTATE");
      } finally {
        connection.rollback();
      }
    }

    assertEquals(historyBefore, history(jdbc), marker + "_HISTORY_MUTATED");
    assertEquals(hashesBefore, pack010AuthorityFunctionHashes(jdbc), marker + "_FUNCTIONS_MUTATED");
    assertEquals(aclBefore, pack010SchemaHelperAcl(jdbc), marker + "_ACL_MUTATED");
    assertEquals(topologyBefore, pack010TriggerTopology(jdbc), marker + "_TOPOLOGY_MUTATED");
    assertEquals(rolesBefore, roleCount(jdbc, PACK010_RUNTIME_ROLES), marker + "_ROLES_MUTATED");
  }

  private static String expectedTriggerTopology(int version) {
    return switch (version) {
      case 16 -> PACK010_V16_TRIGGER_TOPOLOGY;
      case 17 -> PACK010_V17_TRIGGER_TOPOLOGY;
      case 18 -> PACK010_V18_TRIGGER_TOPOLOGY;
      case 19 -> expectedV19TriggerTopology();
      default -> throw new IllegalArgumentException("unsupported Pack010 schema version");
    };
  }

  private static String expectedV19TriggerTopology() {
    String fingerprint = PACK010_V19_CATALOG_FINGERPRINT.get(0);
    String prefix = "trigger-topology:";
    return fingerprint.startsWith(prefix) ? fingerprint.substring(prefix.length()) : fingerprint;
  }

  private static String resourceText(String path) throws IOException {
    try (var resource = V9GraphTerminalAuthorityMigrationTest.class.getResourceAsStream(path)) {
      if (resource == null) {
        throw new IOException("missing test resource: " + path);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void dropDatabase(JdbcClient admin, String database) {
    requireSafeRole(database);
    admin
        .sql(
            "SELECT pg_terminate_backend(pid) "
                + "FROM pg_stat_activity "
                + "WHERE datname = :database "
                + "AND pid <> pg_backend_pid()")
        .param("database", database)
        .query(Boolean.class)
        .list();
    admin.sql("DROP DATABASE IF EXISTS " + database).update();
  }

  private static final class AlternatingDataSource extends AbstractDataSource {

    private final DataSource first;
    private final DataSource second;
    private final AtomicInteger connections = new AtomicInteger();

    private AlternatingDataSource(DataSource first, DataSource second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return delegate().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return delegate().getConnection(username, password);
    }

    private DataSource delegate() {
      return connections.getAndIncrement() % 2 == 0 ? first : second;
    }
  }

  private static void executeAndCommit(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(sql);
      connection.commit();
    }
  }

  private static long roleCount(Statement statement, List<String> roles) throws SQLException {
    String names =
        roles.stream()
            .map(role -> "'" + role + "'")
            .collect(java.util.stream.Collectors.joining(","));
    try (java.sql.ResultSet result =
        statement.executeQuery(
            "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname IN (" + names + ")")) {
      result.next();
      return result.getLong(1);
    }
  }

  private static long roleCount(JdbcClient jdbc, List<String> roles) {
    return roles.stream()
        .mapToLong(
            role ->
                jdbc.sql("SELECT count(*) FROM pg_catalog.pg_roles " + "WHERE rolname = :role")
                    .param("role", role)
                    .query(Long.class)
                    .single())
        .sum();
  }

  private static void assertProviderSessionIntentTable(JdbcClient jdbc) {
    assertEquals(
        List.of(
            "principal_id:character varying(200):true",
            "attempt_id:character(64):true",
            "manifest_hash:character(64):true",
            "revision:character varying(2):true",
            "cursor_sequence:integer:true",
            "cursor_head_hash:character(64):true",
            "request_ordinal:integer:true",
            "request_hash:character(64):true",
            "model_requested:character varying(200):true",
            "intent_hash:character(64):true",
            "created_at:timestamp with time zone:true",
            "expires_at:timestamp with time zone:true"),
        jdbc.sql(
                """
                SELECT attribute.attname || ':'
                       || pg_catalog.format_type(
                            attribute.atttypid, attribute.atttypmod)
                       || ':' || attribute.attnotnull::text
                FROM pg_catalog.pg_attribute attribute
                WHERE attribute.attrelid
                      = 'agent_graph_provider_session_intents'::regclass
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY attribute.attnum
                """)
            .query(String.class)
            .list());
    assertEquals(
        List.of("c:4", "f:2", "n:12", "p:1", "u:1"),
        jdbc.sql(
                """
                SELECT constraint_record.contype::text || ':'
                       || count(*)::text
                FROM pg_catalog.pg_constraint constraint_record
                WHERE constraint_record.conrelid
                      = 'agent_graph_provider_session_intents'::regclass
                GROUP BY constraint_record.contype
                ORDER BY constraint_record.contype
                """)
            .query(String.class)
            .list());
  }

  private static void advanceV8FixtureToAttributedPrefix(
      DataSource dataSource, V7GraphAttemptSqlSeeder.Seed seed) {
    PostgresGraphAttemptStore store = new PostgresGraphAttemptStore(dataSource);
    GraphAttemptSnapshot snapshot =
        assertInstanceOf(GraphAttemptVerification.Valid.class, store.findVerified(seed.manifest()))
            .snapshot();
    GraphAttemptCursor cursor = snapshot.cursor();
    String firstRequestHash = IntegrityHashes.utf8ContentHash(seed.suffix() + ":request-1");
    GraphProviderAttribution first = v8Attribution(seed, 1, firstRequestHash);
    cursor = store.providerAttributed(seed.manifest(), cursor, first, V8_STARTED.plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash("v8 terminal preservation request two"),
            seed.profile().modelRequested());
    cursor = store.providerIntent(seed.manifest(), cursor, secondIntent, V8_STARTED.plusMillis(12));
    GraphProviderAttribution second = v8Attribution(seed, 2, secondIntent.requestHash());
    cursor = store.providerAttributed(seed.manifest(), cursor, second, V8_STARTED.plusMillis(13));
    assertEquals(14, cursor.lastSequence());
  }

  private static GraphProviderAttribution v8Attribution(
      V7GraphAttemptSqlSeeder.Seed seed, int ordinal, String requestHash) {
    GraphPricingSnapshot pricing = seed.profile().pricing().graphSnapshot();
    BigDecimal cost = pricing.actualCostUsd(1, 0, 1);
    return GraphProviderAttribution.create(
        ordinal,
        requestHash,
        IntegrityHashes.utf8ContentHash("v8 terminal preservation response " + ordinal),
        seed.manifest().childActor(),
        pricing.modelRequested(),
        pricing.modelRequested(),
        pricing,
        1,
        0,
        1,
        0,
        2,
        cost);
  }

  private static Map<String, String> rowImages(JdbcClient jdbc) {
    Map<String, String> images = new LinkedHashMap<>();
    for (String table : V8_TRUTH_TABLES) {
      images.put(
          table,
          jdbc.sql(
                  "SELECT COALESCE(pg_catalog.jsonb_agg("
                      + "pg_catalog.to_jsonb(row_image) ORDER BY "
                      + "pg_catalog.to_jsonb(row_image)::text), "
                      + "'[]'::jsonb)::text FROM "
                      + table
                      + " row_image")
              .query(String.class)
              .single());
    }
    return Map.copyOf(images);
  }

  private static List<String> history(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT pg_catalog.concat_ws(
              '|', version, checksum::text, success::text,
              xmin::text
            )
            FROM flyway_schema_history
            WHERE version IS NOT NULL
            ORDER BY installed_rank
            """)
        .query(String.class)
        .list();
  }

  private static Flyway flyway(DataSource dataSource) {
    return Flyway.configure().dataSource(dataSource).load();
  }

  private static DataSource databaseDataSource(String database) {
    requireSafeRole(database);
    JdbcClient.create(baseDataSource()).sql("CREATE DATABASE " + database).update();
    PGSimpleDataSource source = baseDataSource();
    String baseUrl = POSTGRES.getJdbcUrl();
    source.setURL(baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + database);
    return source;
  }

  private static DataSource databaseDataSource(String database, String user, String password) {
    requireSafeRole(database);
    requireSafeRole(user);
    PGSimpleDataSource source = baseDataSource();
    String baseUrl = POSTGRES.getJdbcUrl();
    source.setURL(baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + database);
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static Flyway flywayAt(DataSource dataSource, int version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .target(MigrationVersion.fromVersion(String.valueOf(version)))
        .load();
  }

  private static DataSource schemaDataSource(String schema) {
    PGSimpleDataSource admin = baseDataSource();
    JdbcClient.create(admin).sql("CREATE SCHEMA " + schema).update();
    PGSimpleDataSource scoped = baseDataSource();
    scoped.setCurrentSchema(schema);
    return scoped;
  }

  private static PGSimpleDataSource baseDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static PGSimpleDataSource dataSource(String user, String password, String schema) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    source.setCurrentSchema(schema);
    return source;
  }

  private static void createLoginRole(JdbcClient admin, String role, String password) {
    requireSafeRole(role);
    if (password.contains("'")) {
      throw new IllegalArgumentException("unsafe ephemeral database credential");
    }
    admin
        .sql(
            "CREATE ROLE "
                + role
                + " LOGIN PASSWORD '"
                + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
  }

  private static void createNoLoginRole(JdbcClient admin, String role) {
    requireSafeRole(role);
    admin
        .sql(
            "CREATE ROLE "
                + role
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
  }

  private static void dropRole(JdbcClient admin, String role) {
    requireSafeRole(role);
    admin.sql("DROP OWNED BY " + role).update();
    admin.sql("DROP ROLE " + role).update();
  }

  private static void requireSafeRole(String role) {
    if (!role.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe ephemeral role");
    }
  }
}
