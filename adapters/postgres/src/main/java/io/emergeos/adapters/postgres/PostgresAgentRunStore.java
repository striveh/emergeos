package io.emergeos.adapters.postgres;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.port.AgentRunStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class PostgresAgentRunStore implements AgentRunStore {

  private static final String RUN_COLUMNS =
      """
      principal_id, run_id, task_id, lifecycle_status, task_envelope::text AS task_json,
      result_envelope::text AS result_json, bundle::text AS bundle_json,
      bundle_hash, trace_root_hash, last_event_sequence,
      resolved_model, agent_version, verifier_version, harness_version,
      tool_registry_version, policy_version, state_version, context_policy_version,
      cost_usd, token_count, latency_ms, failure_attribution, started_at, completed_at
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PostgresArtifactLineageStore artifacts;
  private final JsonMapper json;
  private final Runnable afterArtifactInsert;

  public PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts) {
    this(dataSource, transactionManager, artifacts, () -> {});
  }

  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      Runnable afterArtifactInsert) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.afterArtifactInsert = Objects.requireNonNull(afterArtifactInsert, "afterArtifactInsert");
    this.json = JsonMapper.shared();
  }

  @Override
  public AgentRun start(AgentRun running) {
    Objects.requireNonNull(running, "running");
    if (running.lifecycle() != AgentRunLifecycle.RUNNING) {
      throw new IllegalArgumentException("start requires a RUNNING AgentRun");
    }
    int inserted =
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
                ON CONFLICT (principal_id, run_id) DO NOTHING
                """)
            .param("principalId", running.principalId())
            .param("runId", running.runId())
            .param("taskId", running.task().id())
            .param("taskJson", json.writeValueAsString(running.task()))
            .param("toolRegistryVersion", running.task().toolRegistryVersion())
            .param("policyVersion", running.task().policyVersion())
            .param("stateVersion", running.task().stateVersion())
            .param("contextPolicyVersion", running.task().contextPolicyVersion())
            .param("startedAt", Timestamp.from(running.startedAt()))
            .update();
    if (inserted == 1) {
      return running;
    }
    AgentRun existing =
        findOwned(running.principalId(), running.runId())
            .orElseThrow(AgentRunConflictException::new);
    if (!existing.equals(running)) {
      throw new AgentRunConflictException();
    }
    return existing;
  }

  @Override
  public CompletionResult complete(AgentRun terminal, ArtifactLineage proposedArtifact) {
    Objects.requireNonNull(terminal, "terminal");
    if (!terminal.lifecycle().terminal()) {
      throw new IllegalArgumentException("complete requires a terminal AgentRun");
    }
    validateArtifactBinding(terminal, proposedArtifact);
    return Objects.requireNonNull(
        transactions.execute(status -> completeInTransaction(terminal, proposedArtifact)),
        "AgentRun completion transaction result");
  }

  @Override
  public Optional<AgentRun> findOwned(String principalId, String runId) {
    Optional<RunRow> row =
        jdbc.sql(
                "SELECT " + RUN_COLUMNS + " FROM agent_runs "
                    + "WHERE principal_id = :principalId AND run_id = :runId")
            .param("principalId", principalId)
            .param("runId", runId)
            .query(PostgresAgentRunStore::mapRunRow)
            .optional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(toVerifiedRun(row.orElseThrow()));
    } catch (AgentRunIntegrityException knownIntegrityFailure) {
      throw knownIntegrityFailure;
    } catch (RuntimeException invalidStoredTruth) {
      throw new AgentRunIntegrityException(invalidStoredTruth);
    }
  }

  private CompletionResult completeInTransaction(
      AgentRun terminal, ArtifactLineage proposedArtifact) {
    String current =
        jdbc.sql(
                """
                SELECT lifecycle_status
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                FOR UPDATE
                """)
            .param("principalId", terminal.principalId())
            .param("runId", terminal.runId())
            .query(String.class)
            .optional()
            .orElseThrow(AgentRunConflictException::new);
    if (!"RUNNING".equals(current)) {
      throw new AgentRunConflictException();
    }

    ArtifactLineage committedArtifact = null;
    if (proposedArtifact != null) {
      committedArtifact = artifacts.create(proposedArtifact);
      afterArtifactInsert.run();
    }
    insertTrace(terminal);
    insertBindings(terminal);
    int updated =
        jdbc.sql(
                """
                UPDATE agent_runs
                SET lifecycle_status = :lifecycle,
                    result_envelope = CAST(:resultJson AS jsonb),
                    bundle = CAST(:bundleJson AS jsonb),
                    bundle_hash = :bundleHash,
                    trace_root_hash = :traceRootHash,
                    last_event_sequence = :eventCount,
                    resolved_model = :resolvedModel,
                    agent_version = :agentVersion,
                    verifier_version = :verifierVersion,
                    harness_version = :harnessVersion,
                    cost_usd = :costUsd,
                    token_count = :tokenCount,
                    latency_ms = :latencyMs,
                    failure_attribution = :failureAttribution,
                    completed_at = :completedAt
                WHERE principal_id = :principalId
                  AND run_id = :runId
                  AND lifecycle_status = 'RUNNING'
                """)
            .param("lifecycle", terminal.lifecycle().name())
            .param("resultJson", json.writeValueAsString(terminal.result()))
            .param("bundleJson", json.writeValueAsString(terminal.bundle()))
            .param("bundleHash", terminal.bundle().integrityHash())
            .param("traceRootHash", terminal.trace().rootHash())
            .param("eventCount", terminal.trace().eventCount())
            .param("resolvedModel", terminal.result().resolvedModel(), Types.VARCHAR)
            .param("agentVersion", terminal.result().agentVersion())
            .param("verifierVersion", terminal.result().verifierVersion())
            .param("harnessVersion", terminal.bundle().harnessVersion())
            .param("costUsd", terminal.result().costUsd())
            .param("tokenCount", terminal.result().tokenCount())
            .param("latencyMs", terminal.result().latencyMs())
            .param("failureAttribution", terminal.bundle().failureAttribution(), Types.VARCHAR)
            .param("completedAt", Timestamp.from(terminal.completedAt()))
            .param("principalId", terminal.principalId())
            .param("runId", terminal.runId())
            .update();
    if (updated != 1) {
      throw new AgentRunConflictException();
    }
    AgentRun committed =
        findOwned(terminal.principalId(), terminal.runId())
            .orElseThrow(AgentRunIntegrityException::new);
    return new CompletionResult(committed, committedArtifact);
  }

  private void insertTrace(AgentRun terminal) {
    for (AgentTraceEntry event : terminal.trace().events()) {
      jdbc.sql(
              """
              INSERT INTO agent_trace_events (
                  principal_id, run_id, sequence, event_type, tool_name, status,
                  resource_ref, previous_root_hash, event_hash, current_root_hash
              ) VALUES (
                  :principalId, :runId, :sequence, :eventType, :toolName, :status,
                  :resourceRef, :previousRootHash, :eventHash, :currentRootHash
              )
              """)
          .param("principalId", terminal.principalId())
          .param("runId", terminal.runId())
          .param("sequence", event.sequence())
          .param("eventType", event.type().name())
          .param("toolName", event.toolName(), Types.VARCHAR)
          .param("status", event.status())
          .param("resourceRef", event.reference(), Types.VARCHAR)
          .param("previousRootHash", event.previousRootHash())
          .param("eventHash", event.eventHash())
          .param(
              "currentRootHash",
              IntegrityHashes.nextTraceRoot(event.previousRootHash(), event.eventHash()))
          .update();
    }
  }

  private void insertBindings(AgentRun terminal) {
    for (ResourceBinding binding : terminal.bundle().resourceBindings()) {
      String captureId =
          binding.role() == ResourceRole.EVIDENCE
              ? parseCaptureIdentity(binding.ref())
              : null;
      ArtifactIdentity artifactIdentity =
          binding.role() == ResourceRole.ARTIFACT
              ? parseArtifactIdentity(binding.ref())
              : new ArtifactIdentity(null, null);
      jdbc.sql(
              """
              INSERT INTO agent_run_resource_bindings (
                  principal_id, run_id, role, ordinal, resource_ref, content_hash,
                  capture_id, artifact_id, artifact_version
              ) VALUES (
                  :principalId, :runId, :role, :ordinal, :resourceRef, :contentHash,
                  :captureId, :artifactId, :artifactVersion
              )
              """)
          .param("principalId", terminal.principalId())
          .param("runId", terminal.runId())
          .param("role", binding.role().name())
          .param("ordinal", binding.ordinal())
          .param("resourceRef", binding.ref())
          .param("contentHash", binding.contentHash())
          .param("captureId", captureId, Types.VARCHAR)
          .param("artifactId", artifactIdentity.artifactId(), Types.VARCHAR)
          .param("artifactVersion", artifactIdentity.version(), Types.INTEGER)
          .update();
    }
  }

  private AgentRun toVerifiedRun(RunRow row) {
    TaskEnvelope task = json.readValue(row.taskJson(), TaskEnvelope.class);
    if (!row.taskId().equals(task.id())
        || !row.toolRegistryVersion().equals(task.toolRegistryVersion())
        || !row.policyVersion().equals(task.policyVersion())
        || !row.stateVersion().equals(task.stateVersion())
        || !row.contextPolicyVersion().equals(task.contextPolicyVersion())) {
      throw new AgentRunIntegrityException();
    }
    AgentRunLifecycle lifecycle = AgentRunLifecycle.valueOf(row.lifecycleStatus());
    if (lifecycle == AgentRunLifecycle.RUNNING) {
      return AgentRun.running(row.runId(), row.principalId(), task, row.startedAt());
    }

    ResultEnvelope result = json.readValue(row.resultJson(), ResultEnvelope.class);
    HarnessRunBundle bundle = json.readValue(row.bundleJson(), HarnessRunBundle.class);
    List<StoredTraceEntry> storedEvents =
        jdbc.sql(
                """
                SELECT sequence, event_type, tool_name, status, resource_ref,
                       previous_root_hash, event_hash, current_root_hash
                FROM agent_trace_events
                WHERE principal_id = :principalId
                  AND run_id = :runId
                ORDER BY sequence
                """)
            .param("principalId", row.principalId())
            .param("runId", row.runId())
            .query(PostgresAgentRunStore::mapStoredTraceEntry)
            .list();
    for (StoredTraceEntry storedEvent : storedEvents) {
      String expectedCurrentRoot =
          IntegrityHashes.nextTraceRoot(
              storedEvent.event().previousRootHash(),
              storedEvent.event().eventHash());
      if (!expectedCurrentRoot.equals(storedEvent.currentRootHash())) {
        throw new AgentRunIntegrityException();
      }
    }
    List<AgentTraceEntry> events =
        storedEvents.stream().map(StoredTraceEntry::event).toList();
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", row.runId(), row.taskId(), events);
    List<ResourceBinding> bindings =
        jdbc.sql(
                """
                SELECT role, ordinal, resource_ref, content_hash
                FROM agent_run_resource_bindings
                WHERE principal_id = :principalId
                  AND run_id = :runId
                ORDER BY
                  CASE role
                    WHEN 'EVIDENCE' THEN 0
                    WHEN 'ARTIFACT' THEN 1
                    WHEN 'RECEIPT' THEN 2
                    WHEN 'VERIFICATION' THEN 3
                    WHEN 'CHECKPOINT' THEN 4
                    WHEN 'HANDOFF' THEN 5
                  END,
                  ordinal
                """)
            .param("principalId", row.principalId())
            .param("runId", row.runId())
            .query(PostgresAgentRunStore::mapBinding)
            .list();

    if (!row.bundleHash().equals(bundle.integrityHash())
        || !row.traceRootHash().equals(trace.rootHash())
        || row.lastEventSequence() != trace.eventCount()
        || !task.equals(bundle.task())
        || !result.equals(bundle.result())
        || !bindings.equals(bundle.resourceBindings())
        || !Objects.equals(row.resolvedModel(), result.resolvedModel())
        || !row.agentVersion().equals(result.agentVersion())
        || !row.verifierVersion().equals(result.verifierVersion())
        || !row.harnessVersion().equals(bundle.harnessVersion())
        || row.costUsd().compareTo(result.costUsd()) != 0
        || row.tokenCount() != result.tokenCount()
        || row.latencyMs() != result.latencyMs()
        || !Objects.equals(row.failureAttribution(), bundle.failureAttribution())) {
      throw new AgentRunIntegrityException();
    }
    return new AgentRun(
        row.runId(),
        row.principalId(),
        task,
        lifecycle,
        result,
        trace,
        bundle,
        row.startedAt(),
        row.completedAt());
  }

  private static void validateArtifactBinding(
      AgentRun terminal, ArtifactLineage proposedArtifact) {
    List<ResourceBinding> evidenceBindings =
        terminal.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.EVIDENCE)
            .toList();
    if (evidenceBindings.stream()
        .anyMatch(binding -> !terminal.task().inputRefs().contains(binding.ref()))) {
      throw new IllegalArgumentException(
          "Evidence bindings must be declared Task inputs");
    }
    List<ResourceBinding> artifactBindings =
        terminal.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.ARTIFACT)
            .toList();
    if (proposedArtifact == null) {
      if (!artifactBindings.isEmpty()) {
        throw new IllegalArgumentException("Artifact binding requires an atomic Artifact write");
      }
      if (terminal.lifecycle() == AgentRunLifecycle.SUCCEEDED
          && "CREATE_ARTICLE_DRAFT".equals(terminal.task().kind())) {
        throw new IllegalArgumentException(
            "A successful CREATE_ARTICLE_DRAFT completion requires an atomic Artifact write");
      }
      return;
    }
    if (artifactBindings.size() != 1) {
      throw new IllegalArgumentException("Agent draft success requires one Artifact binding");
    }
    ResourceBinding binding = artifactBindings.getFirst();
    String expectedRef =
        "artifact-version://"
            + proposedArtifact.artifactId()
            + "/"
            + proposedArtifact.current().version();
    String sourceCaptureRef = "capture://" + proposedArtifact.sourceCaptureId();
    if (!terminal.principalId().equals(proposedArtifact.principalId())
        || !binding.ref().equals(expectedRef)
        || !binding.contentHash().equals(proposedArtifact.current().contentHash())
        || evidenceBindings.size() != 1
        || !sourceCaptureRef.equals(evidenceBindings.getFirst().ref())
        || !terminal.result().evidenceRefs().equals(List.of(sourceCaptureRef))) {
      throw new IllegalArgumentException("Artifact proposal does not match immutable binding");
    }
  }

  private static String parseCaptureIdentity(String ref) {
    if (ref == null
        || !ref.matches("capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException(
          "Evidence binding must name one owned Capture");
    }
    return ref.substring("capture://".length());
  }

  private static ArtifactIdentity parseArtifactIdentity(String ref) {
    String remainder = ref.substring("artifact-version://".length());
    int separator = remainder.lastIndexOf('/');
    return new ArtifactIdentity(
        remainder.substring(0, separator),
        Integer.valueOf(remainder.substring(separator + 1)));
  }

  private static RunRow mapRunRow(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp completed = resultSet.getTimestamp("completed_at");
    return new RunRow(
        resultSet.getString("principal_id"),
        resultSet.getString("run_id"),
        resultSet.getString("task_id"),
        resultSet.getString("lifecycle_status"),
        resultSet.getString("task_json"),
        resultSet.getString("result_json"),
        resultSet.getString("bundle_json"),
        resultSet.getString("bundle_hash"),
        resultSet.getString("trace_root_hash"),
        resultSet.getInt("last_event_sequence"),
        resultSet.getString("resolved_model"),
        resultSet.getString("agent_version"),
        resultSet.getString("verifier_version"),
        resultSet.getString("harness_version"),
        resultSet.getString("tool_registry_version"),
        resultSet.getString("policy_version"),
        resultSet.getString("state_version"),
        resultSet.getString("context_policy_version"),
        resultSet.getBigDecimal("cost_usd"),
        resultSet.getLong("token_count"),
        resultSet.getLong("latency_ms"),
        resultSet.getString("failure_attribution"),
        resultSet.getTimestamp("started_at").toInstant(),
        completed == null ? null : completed.toInstant());
  }

  private static StoredTraceEntry mapStoredTraceEntry(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new StoredTraceEntry(
        new AgentTraceEntry(
            resultSet.getInt("sequence"),
            TraceEventType.valueOf(resultSet.getString("event_type")),
            resultSet.getString("tool_name"),
            resultSet.getString("status"),
            resultSet.getString("resource_ref"),
            resultSet.getString("previous_root_hash"),
            resultSet.getString("event_hash")),
        resultSet.getString("current_root_hash"));
  }

  private static ResourceBinding mapBinding(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ResourceBinding(
        ResourceRole.valueOf(resultSet.getString("role")),
        resultSet.getInt("ordinal"),
        resultSet.getString("resource_ref"),
        resultSet.getString("content_hash"));
  }

  private record ArtifactIdentity(String artifactId, Integer version) {}

  private record StoredTraceEntry(
      AgentTraceEntry event,
      String currentRootHash) {}

  private record RunRow(
      String principalId,
      String runId,
      String taskId,
      String lifecycleStatus,
      String taskJson,
      String resultJson,
      String bundleJson,
      String bundleHash,
      String traceRootHash,
      int lastEventSequence,
      String resolvedModel,
      String agentVersion,
      String verifierVersion,
      String harnessVersion,
      String toolRegistryVersion,
      String policyVersion,
      String stateVersion,
      String contextPolicyVersion,
      java.math.BigDecimal costUsd,
      long tokenCount,
      long latencyMs,
      String failureAttribution,
      java.time.Instant startedAt,
      java.time.Instant completedAt) {}
}
