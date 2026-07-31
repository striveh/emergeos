package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AgentRunModelBindingMigrationTest {

  private static final String PRINCIPAL = "v4-owner";
  private static final Instant STARTED = Instant.parse("2026-07-30T02:00:00Z");
  private static final Instant COMPLETED = STARTED.plusSeconds(1);
  private static final JsonMapper JSON = JsonMapper.shared();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_agent_run_v4_v5")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void populatedV4RowsUpgradeWithoutRewritingLegacyJsonOrBundleHash() {
    DataSource dataSource = dataSource();
    Flyway v4 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("4"))
            .load();
    v4.migrate();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    LegacyFixture fixture = legacyFixture();
    assertEquals(
        "54d8396656c904ce1dbed0518bd44a41ade9840566516ac5fa9c7b1c07bf3671",
        fixture.terminal().bundle().integrityHash());

    String legacyTaskJson = withoutModelBinding(JSON.writeValueAsString(fixture.task()));
    String legacyBundleJson =
        withoutModelBinding(JSON.writeValueAsString(fixture.terminal().bundle()));
    assertFalse(legacyTaskJson.contains("modelProvider"));
    assertFalse(legacyBundleJson.contains("modelProvider"));

    insertRunning(jdbc, fixture.running(), legacyTaskJson);
    insertTerminal(jdbc, fixture.terminal(), legacyTaskJson, legacyBundleJson);

    LegacyStoredSnapshot before = legacySnapshot(jdbc, fixture.terminal().runId());
    Flyway current = Flyway.configure().dataSource(dataSource).load();
    current.migrate();
    StoredSnapshot after = snapshot(jdbc, fixture.terminal().runId());

    assertEquals(MigrationVersion.fromVersion("6"), current.info().current().getVersion());
    assertEquals(before.taskJson(), after.taskJson());
    assertEquals(before.bundleJson(), after.bundleJson());
    assertEquals(before.bundleHash(), after.bundleHash());
    assertNull(after.modelProvider());
    assertNull(after.modelRequested());
    assertNull(after.pricingProfile());
    assertFalse(after.taskJson().contains("modelProvider"));
    assertFalse(after.bundleJson().contains("modelProvider"));
    assertEquals(fixture.terminal().bundle().integrityHash(), after.bundleHash());

    PostgresAgentRunStore store =
        new PostgresAgentRunStore(
            dataSource,
            new DataSourceTransactionManager(dataSource),
            new PostgresArtifactLineageStore(
                dataSource, new DataSourceTransactionManager(dataSource)));
    assertEquals(
        fixture.running(),
        store.findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow());
    assertEquals(
        fixture.terminal(),
        store.findOwned(PRINCIPAL, fixture.terminal().runId()).orElseThrow());
  }

  private static void insertRunning(
      JdbcClient jdbc, AgentRun running, String taskJson) {
    jdbc.sql(
            """
            INSERT INTO agent_runs (
                principal_id, run_id, task_id, lifecycle_status, task_envelope,
                tool_registry_version, policy_version, state_version,
                context_policy_version, started_at
            ) VALUES (
                :principalId, :runId, :taskId, 'RUNNING', CAST(:taskJson AS jsonb),
                :toolRegistryVersion, :policyVersion, :stateVersion,
                :contextPolicyVersion, :startedAt
            )
            """)
        .param("principalId", running.principalId())
        .param("runId", running.runId())
        .param("taskId", running.task().id())
        .param("taskJson", taskJson)
        .param("toolRegistryVersion", running.task().toolRegistryVersion())
        .param("policyVersion", running.task().policyVersion())
        .param("stateVersion", running.task().stateVersion())
        .param("contextPolicyVersion", running.task().contextPolicyVersion())
        .param("startedAt", Timestamp.from(running.startedAt()))
        .update();
  }

  private static void insertTerminal(
      JdbcClient jdbc,
      AgentRun terminal,
      String taskJson,
      String bundleJson) {
    jdbc.sql(
            """
            INSERT INTO agent_runs (
                principal_id, run_id, task_id, lifecycle_status, task_envelope,
                result_envelope, bundle, bundle_hash, trace_root_hash,
                last_event_sequence, resolved_model, agent_version,
                verifier_version, harness_version, tool_registry_version,
                policy_version, state_version, context_policy_version,
                cost_usd, token_count, latency_ms, failure_attribution,
                started_at, completed_at
            ) VALUES (
                :principalId, :runId, :taskId, :lifecycle, CAST(:taskJson AS jsonb),
                CAST(:resultJson AS jsonb), CAST(:bundleJson AS jsonb), :bundleHash,
                :traceRootHash, 0, :resolvedModel, :agentVersion,
                :verifierVersion, :harnessVersion, :toolRegistryVersion,
                :policyVersion, :stateVersion, :contextPolicyVersion,
                :costUsd, :tokenCount, :latencyMs, :failureAttribution,
                :startedAt, :completedAt
            )
            """)
        .param("principalId", terminal.principalId())
        .param("runId", terminal.runId())
        .param("taskId", terminal.task().id())
        .param("lifecycle", terminal.lifecycle().name())
        .param("taskJson", taskJson)
        .param("resultJson", JSON.writeValueAsString(terminal.result()))
        .param("bundleJson", bundleJson)
        .param("bundleHash", terminal.bundle().integrityHash())
        .param("traceRootHash", terminal.trace().rootHash())
        .param("resolvedModel", terminal.result().resolvedModel())
        .param("agentVersion", terminal.result().agentVersion())
        .param("verifierVersion", terminal.result().verifierVersion())
        .param("harnessVersion", terminal.bundle().harnessVersion())
        .param("toolRegistryVersion", terminal.task().toolRegistryVersion())
        .param("policyVersion", terminal.task().policyVersion())
        .param("stateVersion", terminal.task().stateVersion())
        .param("contextPolicyVersion", terminal.task().contextPolicyVersion())
        .param("costUsd", terminal.result().costUsd())
        .param("tokenCount", terminal.result().tokenCount())
        .param("latencyMs", terminal.result().latencyMs())
        .param("failureAttribution", terminal.bundle().failureAttribution())
        .param("startedAt", Timestamp.from(terminal.startedAt()))
        .param("completedAt", Timestamp.from(terminal.completedAt()))
        .update();
  }

  private static StoredSnapshot snapshot(JdbcClient jdbc, String runId) {
    return jdbc.sql(
            """
            SELECT task_envelope::text AS task_json,
                   bundle::text AS bundle_json,
                   bundle_hash,
                   model_provider,
                   model_requested,
                   pricing_profile
            FROM agent_runs
            WHERE principal_id = :principalId AND run_id = :runId
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) ->
                new StoredSnapshot(
                    resultSet.getString("task_json"),
                    resultSet.getString("bundle_json"),
                    resultSet.getString("bundle_hash"),
                    resultSet.getString("model_provider"),
                    resultSet.getString("model_requested"),
                    resultSet.getString("pricing_profile")))
        .single();
  }

  private static LegacyStoredSnapshot legacySnapshot(JdbcClient jdbc, String runId) {
    return jdbc.sql(
            """
            SELECT task_envelope::text AS task_json,
                   bundle::text AS bundle_json,
                   bundle_hash
            FROM agent_runs
            WHERE principal_id = :principalId AND run_id = :runId
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) ->
                new LegacyStoredSnapshot(
                    resultSet.getString("task_json"),
                    resultSet.getString("bundle_json"),
                    resultSet.getString("bundle_hash")))
        .single();
  }

  private static LegacyFixture legacyFixture() {
    TaskEnvelope task =
        new TaskEnvelope(
            "1.0",
            "task-v4-legacy",
            null,
            PRINCIPAL,
            List.of(),
            "MODEL_EVAL",
            "Read one historical model evaluation",
            List.of(),
            List.of(),
            List.of("text"),
            DataClass.PUBLIC,
            RiskLevel.READ_ONLY,
            "INTERACTIVE",
            List.of(),
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            List.of("preserves historical truth"),
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
    AgentRun running = AgentRun.running("run-v4-running", PRINCIPAL, task, STARTED);
    String terminalRunId = "run-v4-terminal";
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", terminalRunId, task.id(), List.of());
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            terminalRunId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "scripted-fake-draft-v1",
            "agent-draft-service-v1",
            "agent-draft-verifier-v1",
            BigDecimal.ZERO,
            0,
            10,
            "/api/v1/agent-runs/" + terminalRunId + "/trace",
            "MODEL_STEP_FAILED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            "1.0",
            terminalRunId,
            task.id(),
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            Map.of(
                "agent", result.agentVersion(),
                "verifier", result.verifierVersion(),
                "trace-integrity", IntegrityHashes.PROFILE),
            null,
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            terminalRunId,
            PRINCIPAL,
            task,
            AgentRunLifecycle.FAILED,
            result,
            trace,
            bundle,
            STARTED,
            COMPLETED);
    return new LegacyFixture(task, running, terminal);
  }

  private static String withoutModelBinding(String json) {
    String legacy =
        json.replace(
            "\"modelProvider\":null,\"modelRequested\":null,\"pricingProfile\":null,",
            "");
    if (legacy.equals(json)) {
      throw new IllegalStateException("expected serialized TaskEnvelope model binding fields");
    }
    return legacy;
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private record LegacyFixture(
      TaskEnvelope task,
      AgentRun running,
      AgentRun terminal) {}

  private record StoredSnapshot(
      String taskJson,
      String bundleJson,
      String bundleHash,
      String modelProvider,
      String modelRequested,
      String pricingProfile) {}

  private record LegacyStoredSnapshot(
      String taskJson,
      String bundleJson,
      String bundleHash) {}
}
