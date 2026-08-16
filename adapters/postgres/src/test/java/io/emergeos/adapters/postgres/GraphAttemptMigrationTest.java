package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class GraphAttemptMigrationTest {

  private static final Instant STARTED =
      Instant.parse("2026-07-31T06:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_migrations")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void freshInstallReachesV7WithExactAdditiveShape() {
    DataSource dataSource = schemaDataSource("fresh_graph_v7");
    Flyway flyway = flywayAt(dataSource, 7);
    flyway.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);

    assertEquals(
        MigrationVersion.fromVersion("7"),
        flyway.info().current().getVersion());
    assertEquals(
        List.of(
            "agent_graph_attempt_events",
            "agent_graph_attempt_heads",
            "agent_graph_attempt_run_bindings",
            "agent_graph_attempt_seals",
            "agent_graph_attempts"),
        jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name LIKE 'agent_graph_%'
                ORDER BY table_name
                """)
            .query(String.class)
            .list());
    assertEquals(
        10L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_runs'
                  AND column_name LIKE 'graph_%'
                """)
            .query(Long.class)
            .single());
    assertEquals(0L, graphRowCount(jdbc));
  }

  @Test
  void freshInstallReachesV8WithExactTerminalShape() {
    DataSource dataSource = schemaDataSource("fresh_graph_v8");
    Flyway flyway = flywayAt(dataSource, 8);
    flyway.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);

    assertEquals(
        MigrationVersion.fromVersion("8"),
        flyway.info().current().getVersion());
    assertEquals(
        List.of(
            "agent_graph_attempt_candidates",
            "agent_graph_attempt_events",
            "agent_graph_attempt_heads",
            "agent_graph_attempt_provider_attributions",
            "agent_graph_attempt_run_bindings",
            "agent_graph_attempt_seals",
            "agent_graph_attempt_terminal_bindings",
            "agent_graph_attempts"),
        jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name LIKE 'agent_graph_%'
                ORDER BY table_name
                """)
            .query(String.class)
            .list());
    assertEquals(
        List.of("evidence_hash"),
        jdbc.sql(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_graph_attempt_events'
                  AND column_name = 'evidence_hash'
                """)
            .query(String.class)
            .list());
    assertEquals(
        List.of("provider_attribution_count"),
        jdbc.sql(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_graph_attempt_heads'
                  AND column_name = 'provider_attribution_count'
                """)
            .query(String.class)
            .list());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_constraint
                WHERE connamespace = current_schema()::regnamespace
                  AND conname = 'agent_graph_attempt_seals_disabled_v7'
                """)
            .query(Long.class)
            .single());
    assertEquals(0L, graphRowCountV8(jdbc));
  }

  @Test
  void v8CheckRejectsTerminalProtocolRequestLimitDrift() {
    DataSource dataSource =
        schemaDataSource("terminal_request_limit_v8");
    flywayAt(dataSource, 7).migrate();
    V7GraphAttemptSqlSeeder.Seed seed =
        new V7GraphAttemptSqlSeeder(dataSource).seed("limit", 1);
    flywayAt(dataSource, 8).migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);

    jdbc.sql(
            "ALTER TABLE agent_graph_attempts DISABLE TRIGGER USER")
        .update();
    try {
      for (int invalidLimit : List.of(1, 3)) {
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.sql(
                        """
                        UPDATE agent_graph_attempts
                        SET graph_protocol_version = :protocol,
                            maximum_provider_requests = :limit,
                            manifest_json = jsonb_set(
                              jsonb_set(
                                manifest_json,
                                '{graphProtocolVersion}',
                                to_jsonb(CAST(:protocol AS text))
                              ),
                              '{maximumProviderRequests}',
                              to_jsonb(CAST(:limit AS integer))
                            )
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                        """)
                    .param(
                        "protocol",
                        "postgres-graph-terminal-v1")
                    .param("limit", invalidLimit)
                    .param(
                        "principalId", seed.manifest().principalId())
                    .param("attemptId", seed.manifest().attemptId())
                    .update());
      }
    } finally {
      jdbc.sql(
              "ALTER TABLE agent_graph_attempts ENABLE TRIGGER USER")
          .update();
    }
  }

  @Test
  void populatedV6UpgradePreservesLegacyBytesAndXmin() {
    DataSource dataSource =
        schemaDataSource("populated_graph_v7");
    Flyway v6 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("6"))
            .load();
    v6.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TaskEnvelope task = legacyTask();
    String taskJson =
        JsonMapper.shared().writeValueAsString(task);
    jdbc.sql(
            """
            INSERT INTO agent_runs (
              principal_id, run_id, task_id, lifecycle_status,
              task_envelope, tool_registry_version, policy_version,
              state_version, context_policy_version, started_at
            ) VALUES (
              'legacy-owner', 'legacy-run', :taskId, 'RUNNING',
              CAST(:taskJson AS jsonb), :toolRegistryVersion,
              :policyVersion, :stateVersion,
              :contextPolicyVersion, :startedAt
            )
            """)
        .param("taskId", task.id())
        .param("taskJson", taskJson)
        .param(
            "toolRegistryVersion", task.toolRegistryVersion())
        .param("policyVersion", task.policyVersion())
        .param("stateVersion", task.stateVersion())
        .param(
            "contextPolicyVersion",
            task.contextPolicyVersion())
        .param("startedAt", Timestamp.from(STARTED))
        .update();
    LegacyRow before = legacyRow(jdbc);
    List<HistoryRow> historyBefore = history(jdbc);

    Flyway current = flywayAt(dataSource, 7);
    current.migrate();

    assertEquals(
        MigrationVersion.fromVersion("7"),
        current.info().current().getVersion());
    assertEquals(before, legacyRow(jdbc));
    assertEquals(
        historyBefore,
        history(jdbc).stream()
            .filter(row -> !"7".equals(row.version()))
            .toList());
    assertEquals(
        10L,
        jdbc.sql(
                """
                SELECT
                  (graph_attempt_id IS NULL)::int
                  + (graph_manifest_hash IS NULL)::int
                  + (graph_role IS NULL)::int
                  + (graph_task_hash IS NULL)::int
                  + (graph_selector_hash IS NULL)::int
                  + (graph_execution_profile_id IS NULL)::int
                  + (graph_execution_profile_fingerprint IS NULL)::int
                  + (graph_worker_registry_version IS NULL)::int
                  + (graph_worker_profile_id IS NULL)::int
                  + (graph_worker_profile_fingerprint IS NULL)::int
                FROM agent_runs
                WHERE principal_id = 'legacy-owner'
                  AND run_id = 'legacy-run'
                """)
            .query(Long.class)
            .single());
    assertEquals(0L, graphRowCount(jdbc));
  }

  @Test
  void populatedV7PrefixesUpgradeToV8WithoutTouchingLegacyRows() {
    DataSource dataSource =
        schemaDataSource("populated_graph_v8");
    Flyway v7 = flywayAt(dataSource, 7);
    v7.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    V7GraphAttemptSqlSeeder seeder =
        new V7GraphAttemptSqlSeeder(dataSource);
    seeder.seed("seq-1", 1);
    seeder.seed("seq-10", 10);
    seeder.seed("seq-11", 11);

    LegacyGraphImage before = legacyGraphImage(jdbc);
    List<HistoryRow> historyBefore = history(jdbc);

    Flyway current = flywayAt(dataSource, 8);
    current.migrate();

    assertEquals(
        MigrationVersion.fromVersion("8"),
        current.info().current().getVersion());
    assertEquals(before, legacyGraphImage(jdbc));
    assertEquals(
        historyBefore,
        history(jdbc).stream()
            .filter(row -> !"8".equals(row.version()))
            .toList());
    assertEquals(
        List.of(1, 10, 11),
        jdbc.sql(
                """
                SELECT last_sequence
                FROM agent_graph_attempt_heads
                ORDER BY last_sequence
                """)
            .query(Integer.class)
            .list());
    assertEquals(
        List.of("NOT_INVOKED", "NOT_INVOKED", "UNKNOWN"),
        jdbc.sql(
                """
                SELECT billing_status
                FROM agent_graph_attempt_heads
                ORDER BY last_sequence
                """)
            .query(String.class)
            .list());
    assertEquals(
        22L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM agent_graph_attempt_events
                WHERE evidence_hash IS NULL
                """)
            .query(Long.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT
                  (SELECT count(*)
                   FROM agent_graph_attempt_provider_attributions)
                  + (SELECT count(*)
                     FROM agent_graph_attempt_candidates)
                  + (SELECT count(*)
                     FROM agent_graph_attempt_terminal_bindings)
                  + (SELECT count(*)
                     FROM agent_graph_attempt_seals)
                """)
            .query(Long.class)
            .single());
  }

  @Test
  void incompatiblePartialV7FailsAtomicallyAtV6() {
    DataSource dataSource =
        schemaDataSource("partial_graph_v7");
    Flyway v6 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("6"))
            .load();
    v6.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    jdbc.sql(
            """
            CREATE TABLE agent_graph_attempts (
              incompatible INTEGER NOT NULL
            )
            """)
        .update();

    Flyway current = flywayAt(dataSource, 7);
    assertThrows(FlywayException.class, current::migrate);
    assertEquals(
        MigrationVersion.fromVersion("6"),
        current.info().current().getVersion());
    assertEquals(
        List.of("agent_graph_attempts"),
        jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name LIKE 'agent_graph_%'
                ORDER BY table_name
                """)
            .query(String.class)
            .list());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_runs'
                  AND column_name LIKE 'graph_%'
                """)
            .query(Long.class)
            .single());
  }

  private static TaskEnvelope legacyTask() {
    return new TaskEnvelope(
        "1.0",
        "legacy-task",
        null,
        "legacy-owner",
        List.of(),
        "MODEL_EVAL",
        "Preserve one populated V6 row across V7",
        List.of(),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.READ_ONLY,
        "INTERACTIVE",
        List.of(),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("legacy bytes and xmin remain exact"),
        false,
        1,
        1,
        5_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-s2",
        "ref-only-v1",
        "agent-tools-v1",
        null,
        List.of(),
        List.of(),
        "structured final or non-success");
  }

  private static LegacyRow legacyRow(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT
              task_envelope::text AS task_json,
              lifecycle_status,
              started_at,
              xmin::text AS xmin
            FROM agent_runs
            WHERE principal_id = 'legacy-owner'
              AND run_id = 'legacy-run'
            """)
        .query(
            (resultSet, rowNumber) ->
                new LegacyRow(
                    resultSet.getString("task_json"),
                    resultSet.getString("lifecycle_status"),
                    resultSet.getTimestamp("started_at").toInstant(),
                    resultSet.getString("xmin")))
        .single();
  }

  private static List<HistoryRow> history(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT version, checksum, success, xmin::text AS xmin
            FROM flyway_schema_history
            WHERE version IS NOT NULL
            ORDER BY installed_rank
            """)
        .query(
            (resultSet, rowNumber) ->
                new HistoryRow(
                    resultSet.getString("version"),
                    resultSet.getObject("checksum", Integer.class),
                    resultSet.getBoolean("success"),
                    resultSet.getString("xmin")))
        .list();
  }

  private static LegacyGraphImage legacyGraphImage(
      JdbcClient jdbc) {
    return new LegacyGraphImage(
        rowImages(
            jdbc,
            "agent_graph_attempts",
            "principal_id, attempt_id"),
        rowImages(
            jdbc,
            "agent_graph_attempt_run_bindings",
            "principal_id, attempt_id, role"),
        rowImages(
            jdbc,
            "agent_graph_attempt_events",
            "principal_id, attempt_id, sequence"),
        rowImages(
            jdbc,
            "agent_graph_attempt_heads",
            "principal_id, attempt_id"),
        rowImages(
            jdbc,
            "agent_runs",
            "principal_id, run_id"));
  }

  private static List<RowImage> rowImages(
      JdbcClient jdbc, String table, String orderBy) {
    return jdbc.sql(
            "SELECT (to_jsonb(t)"
                + " - 'evidence_hash'"
                + " - 'provider_attribution_count')::text AS row_json,"
                + " xmin::text AS xmin FROM "
                + table
                + " t ORDER BY "
                + orderBy)
        .query(
            (resultSet, rowNumber) ->
                new RowImage(
                    resultSet.getString("row_json"),
                    resultSet.getString("xmin")))
        .list();
  }

  private static long graphRowCount(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*) FROM agent_graph_attempts)
              + (SELECT count(*)
                 FROM agent_graph_attempt_run_bindings)
              + (SELECT count(*) FROM agent_graph_attempt_events)
              + (SELECT count(*) FROM agent_graph_attempt_heads)
              + (SELECT count(*) FROM agent_graph_attempt_seals)
            """)
        .query(Long.class)
        .single();
  }

  private static long graphRowCountV8(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*) FROM agent_graph_attempts)
              + (SELECT count(*)
                 FROM agent_graph_attempt_run_bindings)
              + (SELECT count(*) FROM agent_graph_attempt_events)
              + (SELECT count(*) FROM agent_graph_attempt_heads)
              + (SELECT count(*)
                 FROM agent_graph_attempt_provider_attributions)
              + (SELECT count(*)
                 FROM agent_graph_attempt_candidates)
              + (SELECT count(*)
                 FROM agent_graph_attempt_terminal_bindings)
              + (SELECT count(*) FROM agent_graph_attempt_seals)
            """)
        .query(Long.class)
        .single();
  }

  private static Flyway flywayAt(
      DataSource dataSource, int version) {
    return Flyway.configure()
        .dataSource(dataSource)
        .target(MigrationVersion.fromVersion(String.valueOf(version)))
        .load();
  }

  private static DataSource schemaDataSource(String schema) {
    PGSimpleDataSource admin = baseDataSource();
    JdbcClient.create(admin)
        .sql("CREATE SCHEMA " + schema)
        .update();
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

  private record LegacyRow(
      String taskJson,
      String lifecycle,
      Instant startedAt,
      String xmin) {}

  private record HistoryRow(
      String version,
      Integer checksum,
      boolean success,
      String xmin) {}

  private record LegacyGraphImage(
      List<RowImage> attempts,
      List<RowImage> bindings,
      List<RowImage> events,
      List<RowImage> heads,
      List<RowImage> runs) {}

  private record RowImage(String json, String xmin) {}
}
