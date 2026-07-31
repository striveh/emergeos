package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AgentWorkerResultAndHandoffMigrationTest {

  private static final JsonMapper JSON = JsonMapper.shared();
  private static final Instant STARTED =
      Instant.parse("2026-07-31T05:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_worker_migration")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void legacyGenericHandoffFailsAtomicallyAtV5() {
    String schema = "legacy_handoff";
    DriverManagerDataSource dataSource = dataSource(schema);
    migrateToV5(dataSource, schema);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TaskEnvelope root = rootTask("legacy-handoff-task");
    insertRunning(jdbc, "legacy-handoff-run", root);
    jdbc.sql(
            """
            INSERT INTO agent_run_resource_bindings (
                principal_id, run_id, role, ordinal, resource_ref, content_hash
            ) VALUES (
                :principalId, :runId, 'HANDOFF', 0,
                'agent-run://unproven-child', :contentHash
            )
            """)
        .param("principalId", root.principalRef())
        .param("runId", "legacy-handoff-run")
        .param("contentHash", "a".repeat(64))
        .update();

    assertMigrationFailsAndLeavesV5(dataSource, schema);

    assertEquals(
        1L,
        jdbc.sql(
                "SELECT count(*) FROM agent_run_resource_bindings "
                    + "WHERE role = 'HANDOFF'")
            .query(Long.class)
            .single());
  }

  @Test
  void legacyChildTaskFailsAtomicallyInsteadOfGuessingAParentRun() {
    String schema = "legacy_child";
    DriverManagerDataSource dataSource = dataSource(schema);
    migrateToV5(dataSource, schema);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TaskEnvelope root = rootTask("legacy-parent-task");
    ReadOnlyWorkerExecutionProfile worker =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    TaskEnvelope child =
        worker.newChildTask(
            root,
            new WorkerHandoffRequest(
                worker.workerName(),
                "Create one read-only proposal",
                root.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                root.deadlineMs(),
                root.budgetUsd(),
                CancellationSignal.never()),
            "legacy-child-task");
    insertRunning(jdbc, "legacy-child-run", child);

    assertMigrationFailsAndLeavesV5(dataSource, schema);

    assertEquals(
        child,
        JSON.readValue(
            jdbc.sql(
                    "SELECT task_envelope::text FROM agent_runs "
                        + "WHERE run_id = 'legacy-child-run'")
                .query(String.class)
                .single(),
            TaskEnvelope.class));
  }

  @Test
  void legacyRunningCheckpointRemainsAnAdditiveV6Upgrade() {
    String schema = "legacy_running_checkpoint";
    DriverManagerDataSource dataSource = dataSource(schema);
    migrateToV5(dataSource, schema);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TaskEnvelope root = rootTask("legacy-checkpoint-task");
    insertRunning(jdbc, "legacy-checkpoint-run", root);
    jdbc.sql(
            """
            INSERT INTO agent_run_resource_bindings (
                principal_id, run_id, role, ordinal,
                resource_ref, content_hash
            ) VALUES (
                :principalId, :runId, 'CHECKPOINT', 0,
                'checkpoint://legacy-running', :contentHash
            )
            """)
        .param("principalId", root.principalRef())
        .param("runId", "legacy-checkpoint-run")
        .param("contentHash", "c".repeat(64))
        .update();

    Flyway upgraded = flyway(dataSource, schema, null);
    upgraded.migrate();

    assertEquals(
        MigrationVersion.fromVersion("6"),
        upgraded.info().current().getVersion());
    assertEquals(
        "RUNNING",
        jdbc.sql(
                """
                SELECT binding_owner_status
                FROM agent_run_resource_bindings
                WHERE principal_id = :principalId
                  AND run_id = :runId
                  AND role = 'CHECKPOINT'
                """)
            .param("principalId", root.principalRef())
            .param("runId", "legacy-checkpoint-run")
            .query(String.class)
            .single());
    assertEquals(
        0,
        jdbc.sql(
                """
                SELECT run_depth
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("principalId", root.principalRef())
            .param("runId", "legacy-checkpoint-run")
            .query(Integer.class)
            .single());
  }

  @Test
  void populatedV5ModelBoundTerminalUpgradesWithoutRewritingTruth() {
    String schema = "v5_model_bound_terminal";
    DriverManagerDataSource dataSource = dataSource(schema);
    migrateToV5(dataSource, schema);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    HarnessRunBundle bundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-model-bound-v11.json",
            HarnessRunBundle.class);
    TaskEnvelope task = bundle.task();
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", bundle.runId(), bundle.taskId(), List.of());
    AgentRun terminal =
        new AgentRun(
            bundle.runId(),
            task.principalRef(),
            task,
            AgentRunLifecycle.FAILED,
            bundle.result(),
            trace,
            bundle,
            STARTED,
            STARTED.plusSeconds(1));
    insertTerminalV5(jdbc, terminal);
    ModelBoundSnapshot before =
        modelBoundSnapshot(jdbc, terminal.principalId(), terminal.runId());

    Flyway upgraded = flyway(dataSource, schema, null);
    upgraded.migrate();
    ModelBoundSnapshot after =
        modelBoundSnapshot(jdbc, terminal.principalId(), terminal.runId());

    assertEquals(
        MigrationVersion.fromVersion("6"),
        upgraded.info().current().getVersion());
    assertEquals(before, after);
    assertEquals(
        task,
        JSON.readValue(before.taskJson(), TaskEnvelope.class));
    assertEquals(
        bundle.result(),
        JSON.readValue(before.resultJson(), ResultEnvelope.class));
    assertEquals(
        bundle,
        JSON.readValue(before.bundleJson(), HarnessRunBundle.class));
    assertEquals(bundle.integrityHash(), before.bundleHash());
    assertEquals(task.modelProvider(), before.modelProvider());
    assertEquals(task.modelRequested(), before.modelRequested());
    assertEquals(task.pricingProfile(), before.pricingProfile());

    LineageSnapshot lineage =
        jdbc.sql(
                """
                SELECT parent_run_id, parent_task_id,
                       run_depth, parent_run_depth
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("principalId", terminal.principalId())
            .param("runId", terminal.runId())
            .query(
                (resultSet, rowNumber) ->
                    new LineageSnapshot(
                        resultSet.getString("parent_run_id"),
                        resultSet.getString("parent_task_id"),
                        resultSet.getInt("run_depth"),
                        (Integer) resultSet.getObject("parent_run_depth")))
            .single();
    assertNull(lineage.parentRunId());
    assertNull(lineage.parentTaskId());
    assertEquals(0, lineage.runDepth());
    assertNull(lineage.parentRunDepth());

    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(dataSource);
    PostgresAgentRunStore store =
        new PostgresAgentRunStore(
            dataSource,
            transactionManager,
            new PostgresArtifactLineageStore(
                dataSource, transactionManager));
    assertEquals(
        terminal,
        store
            .findOwned(terminal.principalId(), terminal.runId())
            .orElseThrow());
  }

  private static void assertMigrationFailsAndLeavesV5(
      DriverManagerDataSource dataSource, String schema) {
    Flyway current = flyway(dataSource, schema, null);

    assertThrows(FlywayException.class, current::migrate);

    Flyway afterFailure = flyway(dataSource, schema, null);
    assertEquals(
        MigrationVersion.fromVersion("5"),
        afterFailure.info().current().getVersion());
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_runs'
                  AND column_name IN (
                      'parent_run_id',
                      'parent_task_id',
                      'run_depth',
                      'parent_run_depth'
                  )
                """)
            .query(Long.class)
            .single());
    assertFalse(
        jdbc.sql(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = current_schema()
                      AND table_name = 'agent_worker_results'
                )
                """)
            .query(Boolean.class)
            .single());
  }

  private static void insertRunning(
      JdbcClient jdbc, String runId, TaskEnvelope task) {
    jdbc.sql(
            """
            INSERT INTO agent_runs (
                principal_id, run_id, task_id, lifecycle_status, task_envelope,
                tool_registry_version, policy_version, state_version,
                context_policy_version, model_provider, model_requested,
                pricing_profile, started_at
            ) VALUES (
                :principalId, :runId, :taskId, 'RUNNING',
                CAST(:taskJson AS jsonb), :toolRegistryVersion,
                :policyVersion, :stateVersion, :contextPolicyVersion,
                :modelProvider, :modelRequested, :pricingProfile, :startedAt
            )
            """)
        .param("principalId", task.principalRef())
        .param("runId", runId)
        .param("taskId", task.id())
        .param("taskJson", JSON.writeValueAsString(task))
        .param("toolRegistryVersion", task.toolRegistryVersion())
        .param("policyVersion", task.policyVersion())
        .param("stateVersion", task.stateVersion())
        .param("contextPolicyVersion", task.contextPolicyVersion())
        .param("modelProvider", task.modelProvider())
        .param("modelRequested", task.modelRequested())
        .param("pricingProfile", task.pricingProfile())
        .param("startedAt", Timestamp.from(STARTED))
        .update();
  }

  private static void insertTerminalV5(
      JdbcClient jdbc, AgentRun terminal) {
    jdbc.sql(
            """
            INSERT INTO agent_runs (
                principal_id, run_id, task_id, lifecycle_status,
                task_envelope, result_envelope, bundle, bundle_hash,
                trace_root_hash, last_event_sequence, resolved_model,
                agent_version, verifier_version, harness_version,
                tool_registry_version, policy_version, state_version,
                context_policy_version, model_provider, model_requested,
                pricing_profile, cost_usd, token_count, latency_ms,
                failure_attribution, started_at, completed_at
            ) VALUES (
                :principalId, :runId, :taskId, :lifecycle,
                CAST(:taskJson AS jsonb), CAST(:resultJson AS jsonb),
                CAST(:bundleJson AS jsonb), :bundleHash,
                :traceRootHash, :lastEventSequence, :resolvedModel,
                :agentVersion, :verifierVersion, :harnessVersion,
                :toolRegistryVersion, :policyVersion, :stateVersion,
                :contextPolicyVersion, :modelProvider, :modelRequested,
                :pricingProfile, :costUsd, :tokenCount, :latencyMs,
                :failureAttribution, :startedAt, :completedAt
            )
            """)
        .param("principalId", terminal.principalId())
        .param("runId", terminal.runId())
        .param("taskId", terminal.task().id())
        .param("lifecycle", terminal.lifecycle().name())
        .param("taskJson", JSON.writeValueAsString(terminal.task()))
        .param("resultJson", JSON.writeValueAsString(terminal.result()))
        .param("bundleJson", JSON.writeValueAsString(terminal.bundle()))
        .param("bundleHash", terminal.bundle().integrityHash())
        .param("traceRootHash", terminal.trace().rootHash())
        .param("lastEventSequence", terminal.trace().eventCount())
        .param("resolvedModel", terminal.result().resolvedModel())
        .param("agentVersion", terminal.result().agentVersion())
        .param("verifierVersion", terminal.result().verifierVersion())
        .param("harnessVersion", terminal.bundle().harnessVersion())
        .param("toolRegistryVersion", terminal.task().toolRegistryVersion())
        .param("policyVersion", terminal.task().policyVersion())
        .param("stateVersion", terminal.task().stateVersion())
        .param(
            "contextPolicyVersion",
            terminal.task().contextPolicyVersion())
        .param("modelProvider", terminal.task().modelProvider())
        .param("modelRequested", terminal.task().modelRequested())
        .param("pricingProfile", terminal.task().pricingProfile())
        .param("costUsd", terminal.result().costUsd())
        .param("tokenCount", terminal.result().tokenCount())
        .param("latencyMs", terminal.result().latencyMs())
        .param(
            "failureAttribution",
            terminal.bundle().failureAttribution())
        .param("startedAt", Timestamp.from(terminal.startedAt()))
        .param("completedAt", Timestamp.from(terminal.completedAt()))
        .update();
  }

  private static ModelBoundSnapshot modelBoundSnapshot(
      JdbcClient jdbc, String principalId, String runId) {
    return jdbc.sql(
            """
            SELECT task_envelope::text AS task_json,
                   result_envelope::text AS result_json,
                   bundle::text AS bundle_json,
                   bundle_hash, model_provider, model_requested,
                   pricing_profile
            FROM agent_runs
            WHERE principal_id = :principalId
              AND run_id = :runId
            """)
        .param("principalId", principalId)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) ->
                new ModelBoundSnapshot(
                    resultSet.getString("task_json"),
                    resultSet.getString("result_json"),
                    resultSet.getString("bundle_json"),
                    resultSet.getString("bundle_hash"),
                    resultSet.getString("model_provider"),
                    resultSet.getString("model_requested"),
                    resultSet.getString("pricing_profile")))
        .single();
  }

  private static <T> T readFixture(String relativePath, Class<T> type) {
    try {
      Path root = Path.of("").toAbsolutePath();
      while (root != null
          && !Files.isDirectory(root.resolve("contracts/fixtures"))) {
        root = root.getParent();
      }
      if (root == null) {
        throw new IllegalStateException("Cannot locate repository root");
      }
      return JSON.readValue(
          Files.readString(root.resolve(relativePath)), type);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Cannot load frozen fixture " + relativePath, failure);
    }
  }

  private static TaskEnvelope rootTask(String taskId) {
    return AgentExecutionProfile.readOnlyWorkerFakeV1()
        .newDraftTask(
            taskId,
            "legacy-worker-owner",
            "Create one read-only proposal",
            "capture://legacy-worker-capture",
            DataClass.PUBLIC);
  }

  private static void migrateToV5(
      DriverManagerDataSource dataSource, String schema) {
    flyway(dataSource, schema, "5").migrate();
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource,
      String schema,
      String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    return new DriverManagerDataSource(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + POSTGRES.getDatabaseName()
            + "?currentSchema="
            + schema,
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private record ModelBoundSnapshot(
      String taskJson,
      String resultJson,
      String bundleJson,
      String bundleHash,
      String modelProvider,
      String modelRequested,
      String pricingProfile) {}

  private record LineageSnapshot(
      String parentRunId,
      String parentTaskId,
      int runDepth,
      Integer parentRunDepth) {}
}
