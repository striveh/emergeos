package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Forward-only V10 migration and authority-fingerprint acceptance. */
@Testcontainers
class V10GraphChildAuthorityMigrationTest {

  private static final List<String> TRUTH_TABLES =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
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
          .withDatabaseName("pack010_v10_authority")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void freshMigrationCreatesExactV10ChildAuthorityDefinitions() {
    DataSource dataSource = baseDataSource();
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("10"))
            .load();
    assertEquals(10, flyway.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("10"),
        flyway.info().current().getVersion());
    Map<String, String> fingerprints =
        JdbcClient.create(dataSource)
            .sql(
                """
                SELECT procedure.proname,
                       pg_catalog.md5(procedure.prosrc)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname IN (
                    'agent_graph_require_executor_v10',
                    'agent_graph_complete_child_v10',
                    'agent_graph_complete_parent_and_seal_v10',
                    'agent_graph_run_selector_guard_v10')
                ORDER BY procedure.proname
                """)
            .query(
                (row, ignored) ->
                    Map.entry(row.getString(1), row.getString(2)))
            .list()
            .stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, Map.Entry::getValue));
    assertEquals(
        Map.of(
            "agent_graph_complete_child_v10",
            "26d1087933677c65697c73e14103c973",
            "agent_graph_complete_parent_and_seal_v10",
            "3bb316c246faa7f28ac8dc2560e45342",
            "agent_graph_require_executor_v10",
            "a4e1b77ac6ddc3f42a76ba8bbbd6af0f",
            "agent_graph_run_selector_guard_v10",
            "19e62fb3f842ec2b3ea3df3a174df34c"),
        fingerprints);
    String closure =
        JdbcClient.create(dataSource)
            .sql(
                """
                SELECT pg_catalog.encode(
                    pg_catalog.sha256(
                      pg_catalog.convert_to(
                        pg_catalog.string_agg(
                          procedure.proname || '('
                            || pg_catalog.pg_get_function_identity_arguments(
                                 procedure.oid)
                            || ')' || ':'
                            || pg_catalog.pg_get_function_result(procedure.oid)
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
                                 pg_catalog.sha256(pg_catalog.convert_to(
                                   procedure.prosrc, 'UTF8')), 'hex'),
                          ',' ORDER BY procedure.proname),
                        'UTF8')),
                    'hex')
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                JOIN pg_catalog.pg_language language
                  ON language.oid = procedure.prolang
                WHERE namespace.nspname = current_schema()
                  AND procedure.proname IN (
                    'agent_assert_graph_attempt_v7',
                    'agent_assert_graph_attempt_v8',
                    'agent_assert_worker_graph_v6',
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
                    'agent_worker_graph_guard_v6')
                """)
            .query(String.class)
            .single();
    assertEquals(
        "3a86bdd6b2509fdcb7e8d3d15f931e0a"
            + "c4a2f23818b4d225e61aa0957bc6202a",
        closure);
    Map<String, String> bodyHashes =
        JdbcClient.create(dataSource)
        .sql(
            """
            SELECT procedure.proname || ':' || pg_catalog.encode(
                     pg_catalog.sha256(pg_catalog.convert_to(
                       procedure.prosrc, 'UTF8')), 'hex') AS body_hash,
                   procedure.proname || '('
                     || pg_catalog.pg_get_function_identity_arguments(
                          procedure.oid)
                     || ')' || ':'
                     || pg_catalog.pg_get_function_result(procedure.oid)
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
                          pg_catalog.sha256(pg_catalog.convert_to(
                            procedure.prosrc, 'UTF8')), 'hex') AS surface
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = procedure.pronamespace
            JOIN pg_catalog.pg_language language
              ON language.oid = procedure.prolang
            WHERE namespace.nspname = current_schema()
              AND procedure.proname IN (
                'agent_graph_require_executor_v10',
                'agent_graph_complete_child_v10',
                'agent_graph_complete_parent_and_seal_v10',
                'agent_graph_run_selector_guard_v10')
            ORDER BY procedure.proname
            """)
        .query(
            (row, ignored) ->
                Map.entry(
                    row.getString("body_hash").split(":", 2)[0],
                    row.getString("body_hash").split(":", 2)[1]
                        + "|"
                        + row.getString("surface")))
        .list()
        .stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, Map.Entry::getValue));
    assertEquals(
        Map.of(
            "agent_graph_complete_child_v10",
            "db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae"
                + "|agent_graph_complete_child_v10(payload jsonb):jsonb:true:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:"
                + "db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae",
            "agent_graph_complete_parent_and_seal_v10",
            "57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35"
                + "|agent_graph_complete_parent_and_seal_v10(payload jsonb):jsonb:true:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:"
                + "57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35",
            "agent_graph_require_executor_v10",
            "bc77084ab3530fdabd0b8e03ebb8bd59e22395cc81fbbd91876bdd7b7a39ccd1"
                + "|agent_graph_require_executor_v10(expected_function regprocedure):void:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:"
                + "bc77084ab3530fdabd0b8e03ebb8bd59e22395cc81fbbd91876bdd7b7a39ccd1",
            "agent_graph_run_selector_guard_v10",
            "561eaf8977811117ceb8357bfe002f98ab6eba2c52db38885777552454affb0b"
                + "|agent_graph_run_selector_guard_v10():trigger:false:v:u:f:false:false:false:plpgsql:search_path=pg_catalog, pg_temp:"
                + "561eaf8977811117ceb8357bfe002f98ab6eba2c52db38885777552454affb0b"),
        bodyHashes);
    assertEquals(
        0L,
        JdbcClient.create(dataSource)
            .sql(
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
                  AND procedure.proname IN (
                    'agent_graph_require_executor_v10',
                    'agent_graph_complete_child_v10',
                    'agent_graph_complete_parent_and_seal_v10',
                    'agent_graph_run_selector_guard_v10')
                  AND acl.grantee = 0
                  AND acl.privilege_type = 'EXECUTE'
                """)
            .query(Long.class)
            .single());
    String triggerTopology =
        JdbcClient.create(dataSource)
            .sql(
                """
                SELECT pg_catalog.encode(
                  pg_catalog.sha256(pg_catalog.convert_to(
                    COALESCE(pg_catalog.string_agg(
                      pg_catalog.concat_ws('|', relation.relname,
                        trigger.tgname, trigger.tgenabled,
                        trigger.tgtype::TEXT, trigger.tgattr::TEXT,
                        COALESCE(pg_catalog.pg_get_expr(
                          trigger.tgqual, trigger.tgrelid), ''),
                        pg_catalog.encode(trigger.tgargs, 'hex'),
                        (trigger.tgconstraint <> 0)::TEXT,
                        COALESCE(constraint_row.condeferrable, FALSE)::TEXT,
                        COALESCE(constraint_row.condeferred, FALSE)::TEXT,
                        procedure.proname || '(' ||
                          pg_catalog.pg_get_function_identity_arguments(
                            procedure.oid) || ')'),
                      ',' ORDER BY relation.relname, trigger.tgname), ''),
                    'UTF8')), 'hex')
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_class relation
                  ON relation.oid = trigger.tgrelid
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                JOIN pg_catalog.pg_proc procedure
                  ON procedure.oid = trigger.tgfoid
                LEFT JOIN pg_catalog.pg_constraint constraint_row
                  ON constraint_row.oid = trigger.tgconstraint
                WHERE namespace.nspname = current_schema()
                  AND NOT trigger.tgisinternal
                """)
            .query(String.class)
            .single();
    assertEquals(
        "5bbd4e20e187296c0094b11f3aa4b6871d1bc40b9301a05e5669d418c887f84c",
        triggerTopology);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        List.of("graph_seal_candidate_fk_v10|s|true|true"),
        jdbc.sql(
                """
                SELECT constraint_row.conname || '|'
                       || constraint_row.confmatchtype::text || '|'
                       || constraint_row.condeferrable::text || '|'
                       || constraint_row.condeferred::text
                FROM pg_catalog.pg_constraint constraint_row
                WHERE constraint_row.conrelid
                      = 'agent_graph_attempt_seals'::regclass
                  AND constraint_row.conname LIKE
                        'graph_seal_candidate_fk_%'
                ORDER BY constraint_row.conname
                """)
            .query(String.class)
            .list());
    assertEquals(
        List.of(
            "agent_graph_run_selector_guard_v10|"
                + "agent_graph_run_selector_guard_v10",
            "agent_graph_run_selector_guard_v8|"
                + "agent_graph_run_selector_guard_v8"),
        jdbc.sql(
                """
                SELECT trigger.tgname || '|' || procedure.proname
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_class relation
                  ON relation.oid = trigger.tgrelid
                JOIN pg_catalog.pg_proc procedure
                  ON procedure.oid = trigger.tgfoid
                WHERE relation.oid = 'agent_runs'::regclass
                  AND trigger.tgname LIKE
                        'agent_graph_run_selector_guard_v%'
                  AND NOT trigger.tgisinternal
                ORDER BY trigger.tgname
                """)
            .query(String.class)
            .list());
    System.out.println(
        "PACK010_V10_FRESH_RECEIPT migration=10 exactFunctions=4 "
            + "helperClosure=17 authorityGuard=v10 integrityGuard=v8 "
            + "sealCandidateFk=MATCH_SIMPLE");
  }

  @Test
  void populatedV9UpgradePreservesRowsAndHistoricalMigrationIdentity() {
    DataSource dataSource = schemaDataSource("populated_v9_to_v10");
    Flyway v9 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("9"))
            .load();
    assertEquals(9, v9.migrate().migrationsExecuted);
    new V7GraphAttemptSqlSeeder(dataSource)
        .seedTerminalV8("v10-populated-fidelity");
    JdbcClient jdbc = JdbcClient.create(dataSource);
    Map<String, String> rowImagesBefore = rowImagesWithXmin(jdbc);
    List<String> historyBefore = migrationHistory(jdbc);

    Flyway v10 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("10"))
            .load();
    assertEquals(1, v10.migrate().migrationsExecuted);

    assertEquals(
        MigrationVersion.fromVersion("10"),
        v10.info().current().getVersion());
    assertEquals(rowImagesBefore, rowImagesWithXmin(jdbc));
    assertEquals(
        historyBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("10|"))
            .toList());
    assertTrue(
        migrationHistory(jdbc).getLast().startsWith("10|"));
    assertFalse(rowImagesBefore.isEmpty());
    System.out.println(
        "PACK010_V9_TO_V10_FIDELITY_RECEIPT populated=true "
            + "rowsAndXmin=UNCHANGED historyV1V9=UNCHANGED");
  }

  private static Map<String, String> rowImagesWithXmin(JdbcClient jdbc) {
    Map<String, String> images = new LinkedHashMap<>();
    for (String table : TRUTH_TABLES) {
      images.put(
          table,
          jdbc.sql(
                  "SELECT COALESCE(pg_catalog.jsonb_agg("
                      + "pg_catalog.jsonb_build_object('row', "
                      + "pg_catalog.to_jsonb(row_image), 'xmin', "
                      + "row_image.xmin::text) ORDER BY "
                      + "pg_catalog.to_jsonb(row_image)::text), "
                      + "'[]'::jsonb)::text FROM "
                      + table
                      + " row_image")
              .query(String.class)
              .single());
    }
    return Map.copyOf(images);
  }

  private static List<String> migrationHistory(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT pg_catalog.concat_ws(
              '|', version, checksum::text, success::text, xmin::text)
            FROM flyway_schema_history
            WHERE version IS NOT NULL
            ORDER BY installed_rank
            """)
        .query(String.class)
        .list();
  }

  private static DataSource schemaDataSource(String prefix) {
    String schema =
        prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    JdbcClient.create(baseDataSource())
        .sql("CREATE SCHEMA " + schema)
        .update();
    PGSimpleDataSource source = baseDataSource();
    source.setCurrentSchema(schema);
    return source;
  }

  private static PGSimpleDataSource baseDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }
}
