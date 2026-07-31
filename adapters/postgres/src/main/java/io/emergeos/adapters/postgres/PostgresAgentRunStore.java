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
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerHandoffVerifier;
import io.emergeos.core.application.ReadOnlyWorkerProfile;
import io.emergeos.core.application.ReadOnlyWorkerProfileRegistry;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.ReadOnlyWorkerRunStore;
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

public final class PostgresAgentRunStore
    implements AgentRunStore, ReadOnlyWorkerRunStore {

  private static final String RUN_COLUMNS =
      """
      principal_id, run_id, task_id, parent_run_id, parent_task_id,
      run_depth, parent_run_depth,
      lifecycle_status, task_envelope::text AS task_json,
      result_envelope::text AS result_json, bundle::text AS bundle_json,
      bundle_hash, trace_root_hash, last_event_sequence,
      resolved_model, agent_version, verifier_version, harness_version,
      tool_registry_version, policy_version, state_version, context_policy_version,
      model_provider, model_requested, pricing_profile,
      cost_usd, token_count, latency_ms, failure_attribution, started_at, completed_at
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PostgresArtifactLineageStore artifacts;
  private final ReadOnlyWorkerProfileRegistry workerProfiles;
  private final JsonMapper json;
  private final Runnable afterArtifactInsert;
  private final Runnable afterWorkerResultInsert;
  private final Runnable afterTerminalUpdateBeforeVerifiedRead;

  public PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        ReadOnlyWorkerProfileRegistry.pack007Only());
  }

  /** Pack007 binary-compatible constructor. */
  public PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerExecutionProfile workerProfile) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        ReadOnlyWorkerProfileRegistry.of(workerProfile));
  }

  public PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerProfileRegistry workerProfiles) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        workerProfiles,
        () -> {},
        () -> {},
        () -> {});
  }

  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      Runnable afterArtifactInsert) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        ReadOnlyWorkerProfileRegistry.pack007Only(),
        afterArtifactInsert,
        () -> {},
        () -> {});
  }

  /** Pack007 binary-compatible fault-injection constructor. */
  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerExecutionProfile workerProfile,
      Runnable afterArtifactInsert,
      Runnable afterWorkerResultInsert) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        ReadOnlyWorkerProfileRegistry.of(workerProfile),
        afterArtifactInsert,
        afterWorkerResultInsert,
        () -> {});
  }

  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerProfileRegistry workerProfiles,
      Runnable afterArtifactInsert,
      Runnable afterWorkerResultInsert) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        workerProfiles,
        afterArtifactInsert,
        afterWorkerResultInsert,
        () -> {});
  }

  /** Pack007 binary-compatible fault-injection constructor. */
  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerExecutionProfile workerProfile,
      Runnable afterArtifactInsert,
      Runnable afterWorkerResultInsert,
      Runnable afterTerminalUpdateBeforeVerifiedRead) {
    this(
        dataSource,
        transactionManager,
        artifacts,
        ReadOnlyWorkerProfileRegistry.of(workerProfile),
        afterArtifactInsert,
        afterWorkerResultInsert,
        afterTerminalUpdateBeforeVerifiedRead);
  }

  PostgresAgentRunStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresArtifactLineageStore artifacts,
      ReadOnlyWorkerProfileRegistry workerProfiles,
      Runnable afterArtifactInsert,
      Runnable afterWorkerResultInsert,
      Runnable afterTerminalUpdateBeforeVerifiedRead) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.workerProfiles =
        Objects.requireNonNull(workerProfiles, "workerProfiles");
    this.afterArtifactInsert = Objects.requireNonNull(afterArtifactInsert, "afterArtifactInsert");
    this.afterWorkerResultInsert =
        Objects.requireNonNull(afterWorkerResultInsert, "afterWorkerResultInsert");
    this.afterTerminalUpdateBeforeVerifiedRead =
        Objects.requireNonNull(
            afterTerminalUpdateBeforeVerifiedRead,
            "afterTerminalUpdateBeforeVerifiedRead");
    this.json = JsonMapper.shared();
  }

  @Override
  public AgentRun start(AgentRun running) {
    Objects.requireNonNull(running, "running");
    if (running.lifecycle() != AgentRunLifecycle.RUNNING) {
      throw new IllegalArgumentException("start requires a RUNNING AgentRun");
    }
    if (running.task().parentId() != null
        || !running.task().delegationChain().isEmpty()
        || "PROPOSE_ARTICLE_DRAFT".equals(running.task().kind())) {
      throw new IllegalArgumentException(
          "Worker child must be started through the parent-scoped capability");
    }
    requireWorkerParentAuthorityIfPresent(running.task());
    int inserted =
        jdbc.sql(
                """
                INSERT INTO agent_runs (
                    principal_id, run_id, task_id, lifecycle_status, task_envelope,
                    tool_registry_version, policy_version, state_version,
                    context_policy_version, model_provider, model_requested,
                    pricing_profile, started_at
                ) VALUES (
                    :principalId, :runId, :taskId, 'RUNNING', CAST(:taskJson AS jsonb),
                    :toolRegistryVersion, :policyVersion, :stateVersion,
                    :contextPolicyVersion, :modelProvider, :modelRequested,
                    :pricingProfile, :startedAt
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
            .param("modelProvider", running.task().modelProvider(), Types.VARCHAR)
            .param("modelRequested", running.task().modelRequested(), Types.VARCHAR)
            .param("pricingProfile", running.task().pricingProfile(), Types.VARCHAR)
            .param("startedAt", Timestamp.from(running.startedAt()))
            .update();
    if (inserted == 1) {
      AgentRun committed =
          loadLocalStoredRun(running.principalId(), running.runId(), false)
              .map(StoredRun::run)
              .orElseThrow(AgentRunIntegrityException::new);
      if (!committed.equals(running)) {
        throw new AgentRunIntegrityException();
      }
      return committed;
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
  public AgentRun startWorker(AgentRunContext parent, AgentRun running) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(running, "running");
    if (running.lifecycle() != AgentRunLifecycle.RUNNING) {
      throw new IllegalArgumentException(
          "startWorker requires a RUNNING child AgentRun");
    }
    ReadOnlyWorkerProfile workerProfile =
        requireWorkerParentAuthority(parent.task());
    workerProfile.requireChildBinding(parent.task(), running.task());
    if (!parent.principalId().equals(running.principalId())) {
      throw new IllegalArgumentException(
          "Worker child principal must match its exact parent Run");
    }
    return Objects.requireNonNull(
        transactions.execute(status -> startWorkerInTransaction(parent, running)),
        "Worker start transaction result");
  }

  private AgentRun startWorkerInTransaction(
      AgentRunContext parent, AgentRun running) {
    StoredRun durableParent =
        loadLocalStoredRun(
                parent.principalId(), parent.runId(), true)
            .orElseThrow(AgentRunConflictException::new);
    requireExactRunningParent(parent, durableParent);

    int inserted =
        jdbc.sql(
                """
                INSERT INTO agent_runs (
                    principal_id, run_id, task_id, parent_run_id, parent_task_id,
                    run_depth, parent_run_depth, lifecycle_status,
                    task_envelope, tool_registry_version,
                    policy_version, state_version, context_policy_version,
                    model_provider, model_requested, pricing_profile, started_at
                ) VALUES (
                    :principalId, :runId, :taskId, :parentRunId, :parentTaskId,
                    1, 0, 'RUNNING', CAST(:taskJson AS jsonb),
                    :toolRegistryVersion,
                    :policyVersion, :stateVersion, :contextPolicyVersion,
                    :modelProvider, :modelRequested, :pricingProfile, :startedAt
                )
                ON CONFLICT DO NOTHING
                """)
            .param("principalId", running.principalId())
            .param("runId", running.runId())
            .param("taskId", running.task().id())
            .param("parentRunId", parent.runId())
            .param("parentTaskId", parent.task().id())
            .param("taskJson", json.writeValueAsString(running.task()))
            .param("toolRegistryVersion", running.task().toolRegistryVersion())
            .param("policyVersion", running.task().policyVersion())
            .param("stateVersion", running.task().stateVersion())
            .param(
                "contextPolicyVersion",
                running.task().contextPolicyVersion())
            .param(
                "modelProvider", running.task().modelProvider(), Types.VARCHAR)
            .param(
                "modelRequested", running.task().modelRequested(), Types.VARCHAR)
            .param(
                "pricingProfile", running.task().pricingProfile(), Types.VARCHAR)
            .param("startedAt", Timestamp.from(running.startedAt()))
            .update();
    Optional<StoredRun> stored =
        loadLocalStoredRun(
            running.principalId(), running.runId(), false);
    if (inserted != 1
        && stored.isEmpty()) {
      throw new AgentRunConflictException();
    }
    StoredRun canonical =
        stored.orElseThrow(AgentRunIntegrityException::new);
    if (!canonical.run().equals(running)
        || !parent.runId().equals(canonical.parentRunId())
        || !parent.task().id().equals(canonical.parentTaskId())) {
      throw new AgentRunConflictException();
    }
    return canonical.run();
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
  public WorkerCompletion completeWorker(
      AgentRunContext parent,
      AgentRun terminal,
      WorkerResultEnvelope workerResult) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(terminal, "terminal");
    if (!terminal.lifecycle().terminal()) {
      throw new IllegalArgumentException(
          "completeWorker requires a terminal child AgentRun");
    }
    ReadOnlyWorkerProfile workerProfile =
        workerProfiles.requireForTerminalChild(
            parent.task(),
            terminal.task(),
            terminal.bundle().experiment(),
            terminal.bundle().harnessVersion(),
            terminal.bundle().componentVersions());
    ReadOnlyWorkerHandoffVerifier.verifyChild(
        parent.task(), terminal, workerResult, workerProfile);
    return Objects.requireNonNull(
        transactions.execute(
            status ->
                completeWorkerInTransaction(parent, terminal, workerResult)),
        "Worker completion transaction result");
  }

  @Override
  public Optional<WorkerCompletion> findWorkerOwned(
      AgentRunContext parent, String childRunId) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(childRunId, "childRunId");
    try {
      Optional<StoredRun> parentStored =
          loadLocalStoredRun(
              parent.principalId(), parent.runId(), false);
      if (parentStored.isEmpty()) {
        return Optional.empty();
      }
      requireExactParentIdentity(parent, parentStored.orElseThrow());
      Optional<StoredRun> childStored =
          loadLocalStoredRun(
              parent.principalId(), childRunId, false);
      if (childStored.isEmpty()) {
        return Optional.empty();
      }
      StoredRun child = childStored.orElseThrow();
      requireExactChildLineage(parent, child);
      if (!child.run().lifecycle().terminal()) {
        if (parentStored.orElseThrow().run().lifecycle().terminal()) {
          throw new AgentRunIntegrityException();
        }
        return Optional.empty();
      }
      WorkerResultEnvelope workerResult =
          loadWorkerResultLocal(
                  parent.principalId(), parent.runId(), childRunId)
              .orElse(null);
      StoredRun durableParent = parentStored.orElseThrow();
      if (durableParent.run().lifecycle().terminal()) {
        ReadOnlyWorkerProfile workerProfile =
            requireSameTerminalProfile(
                durableParent.run(), child.run());
        ReadOnlyWorkerHandoffVerifier.verifyPair(
            durableParent.run(),
            child.run(),
            workerResult,
            workerProfile);
      } else {
        ReadOnlyWorkerProfile workerProfile =
            workerProfiles.requireForTerminalChild(
                parent.task(),
                child.run().task(),
                child.run().bundle().experiment(),
                child.run().bundle().harnessVersion(),
                child.run().bundle().componentVersions());
        ReadOnlyWorkerHandoffVerifier.verifyChild(
            parent.task(), child.run(), workerResult, workerProfile);
      }
      return Optional.of(new WorkerCompletion(child.run(), workerResult));
    } catch (AgentRunIntegrityException knownIntegrityFailure) {
      throw knownIntegrityFailure;
    } catch (RuntimeException invalidStoredTruth) {
      throw new AgentRunIntegrityException(invalidStoredTruth);
    }
  }

  @Override
  public Optional<AgentRun> findOwned(String principalId, String runId) {
    try {
      Optional<StoredRun> stored =
          loadLocalStoredRun(principalId, runId, false);
      if (stored.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(verifyStoredGraph(stored.orElseThrow()));
    } catch (AgentRunIntegrityException knownIntegrityFailure) {
      throw knownIntegrityFailure;
    } catch (RuntimeException invalidStoredTruth) {
      throw new AgentRunIntegrityException(invalidStoredTruth);
    }
  }

  private CompletionResult completeInTransaction(
      AgentRun terminal, ArtifactLineage proposedArtifact) {
    StoredRun running =
        loadLocalStoredRun(
                terminal.principalId(), terminal.runId(), true)
            .orElseThrow(AgentRunConflictException::new);
    requireExactRunningTransition(terminal, running, false);

    List<StoredRun> children =
        loadChildrenForUpdate(terminal.principalId(), terminal.runId());
    List<ResourceBinding> handoffs =
        terminal.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.HANDOFF)
            .toList();
    if (children.isEmpty()) {
      if (!handoffs.isEmpty()) {
        throw new IllegalArgumentException(
            "parent Handoff names a child that was not started by this Run");
      }
      verifyWorkerParentProfileIfPresent(terminal);
    } else {
      if (children.size() != 1
          || handoffs.size() != 1
          || !handoffs
              .getFirst()
              .ref()
              .equals("agent-run://" + children.getFirst().run().runId())) {
        throw new IllegalArgumentException(
            "started Worker child must be observed by the exact parent terminal truth");
      }
      StoredRun child = children.getFirst();
      if (!child.run().lifecycle().terminal()) {
        throw new IllegalArgumentException(
            "parent cannot complete before its exact Worker child");
      }
      WorkerResultEnvelope workerResult =
          loadWorkerResultLocal(
                  terminal.principalId(),
                  terminal.runId(),
                  child.run().runId())
              .orElse(null);
      ReadOnlyWorkerProfile workerProfile =
          requireSameTerminalProfile(terminal, child.run());
      ReadOnlyWorkerHandoffVerifier.verifyPair(
          terminal, child.run(), workerResult, workerProfile);
    }

    ArtifactLineage committedArtifact = null;
    if (proposedArtifact != null) {
      committedArtifact = artifacts.create(proposedArtifact);
      afterArtifactInsert.run();
    }
    insertTrace(terminal);
    insertBindings(terminal);
    updateTerminal(terminal);
    afterTerminalUpdateBeforeVerifiedRead.run();
    AgentRun committed =
        verifyStoredGraph(
            loadLocalStoredRun(
                    terminal.principalId(), terminal.runId(), false)
                .orElseThrow(AgentRunIntegrityException::new));
    return new CompletionResult(committed, committedArtifact);
  }

  private WorkerCompletion completeWorkerInTransaction(
      AgentRunContext parent,
      AgentRun terminal,
      WorkerResultEnvelope workerResult) {
    StoredRun durableParent =
        loadLocalStoredRun(
                parent.principalId(), parent.runId(), true)
            .orElseThrow(AgentRunConflictException::new);
    requireExactRunningParent(parent, durableParent);
    StoredRun runningChild =
        loadLocalStoredRun(
                parent.principalId(), terminal.runId(), true)
            .orElseThrow(AgentRunConflictException::new);
    requireExactChildLineage(parent, runningChild);
    requireExactRunningTransition(terminal, runningChild, true);
    ReadOnlyWorkerProfile workerProfile =
        workerProfiles.requireForTerminalChild(
            parent.task(),
            terminal.task(),
            terminal.bundle().experiment(),
            terminal.bundle().harnessVersion(),
            terminal.bundle().componentVersions());
    ReadOnlyWorkerHandoffVerifier.verifyChild(
        parent.task(), terminal, workerResult, workerProfile);

    if (workerResult != null) {
      insertWorkerResult(parent, terminal, workerResult);
      afterWorkerResultInsert.run();
    }
    insertTrace(terminal);
    insertBindings(terminal);
    updateTerminal(terminal);
    afterTerminalUpdateBeforeVerifiedRead.run();

    StoredRun committed =
        loadLocalStoredRun(
                parent.principalId(), terminal.runId(), false)
            .orElseThrow(AgentRunIntegrityException::new);
    requireExactChildLineage(parent, committed);
    WorkerResultEnvelope committedResult =
        loadWorkerResultLocal(
                parent.principalId(), parent.runId(), terminal.runId())
            .orElse(null);
    ReadOnlyWorkerProfile committedProfile =
        workerProfiles.requireForTerminalChild(
            parent.task(),
            committed.run().task(),
            committed.run().bundle().experiment(),
            committed.run().bundle().harnessVersion(),
            committed.run().bundle().componentVersions());
    ReadOnlyWorkerHandoffVerifier.verifyChild(
        parent.task(), committed.run(), committedResult, committedProfile);
    return new WorkerCompletion(committed.run(), committedResult);
  }

  private void updateTerminal(AgentRun terminal) {
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
            .param(
                "resolvedModel",
                terminal.result().resolvedModel(),
                Types.VARCHAR)
            .param("agentVersion", terminal.result().agentVersion())
            .param("verifierVersion", terminal.result().verifierVersion())
            .param("harnessVersion", terminal.bundle().harnessVersion())
            .param("costUsd", terminal.result().costUsd())
            .param("tokenCount", terminal.result().tokenCount())
            .param("latencyMs", terminal.result().latencyMs())
            .param(
                "failureAttribution",
                terminal.bundle().failureAttribution(),
                Types.VARCHAR)
            .param("completedAt", Timestamp.from(terminal.completedAt()))
            .param("principalId", terminal.principalId())
            .param("runId", terminal.runId())
            .update();
    if (updated != 1) {
      throw new AgentRunConflictException();
    }
  }

  private void insertWorkerResult(
      AgentRunContext parent,
      AgentRun terminal,
      WorkerResultEnvelope workerResult) {
    jdbc.sql(
            """
            INSERT INTO agent_worker_results (
                principal_id, parent_run_id, child_run_id, child_task_id,
                child_status, worker_result_ref, worker_result_envelope,
                content_hash, integrity_hash
            ) VALUES (
                :principalId, :parentRunId, :childRunId, :childTaskId,
                :childStatus, :workerResultRef,
                CAST(:workerResultJson AS jsonb), :contentHash, :integrityHash
            )
            """)
        .param("principalId", terminal.principalId())
        .param("parentRunId", parent.runId())
        .param("childRunId", terminal.runId())
        .param("childTaskId", terminal.task().id())
        .param("childStatus", terminal.lifecycle().name())
        .param("workerResultRef", workerResult.workerResultRef())
        .param("workerResultJson", json.writeValueAsString(workerResult))
        .param("contentHash", workerResult.contentHash())
        .param("integrityHash", workerResult.integrityHash())
        .update();
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
      String handoffChildRunId =
          binding.role() == ResourceRole.HANDOFF
              ? parseAgentRunIdentity(binding.ref())
              : null;
      String workerResultChildRunId =
          binding.role() == ResourceRole.WORKER_RESULT
              ? parseWorkerResultIdentity(binding.ref())
              : null;
      jdbc.sql(
              """
              INSERT INTO agent_run_resource_bindings (
                  principal_id, run_id, role, ordinal, resource_ref, content_hash,
                  capture_id, artifact_id, artifact_version,
                  handoff_child_run_id, worker_result_child_run_id,
                  binding_owner_status
              ) VALUES (
                  :principalId, :runId, :role, :ordinal, :resourceRef, :contentHash,
                  :captureId, :artifactId, :artifactVersion,
                  :handoffChildRunId, :workerResultChildRunId,
                  :bindingOwnerStatus
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
          .param("handoffChildRunId", handoffChildRunId, Types.VARCHAR)
          .param(
              "workerResultChildRunId",
              workerResultChildRunId,
              Types.VARCHAR)
          .param("bindingOwnerStatus", terminal.lifecycle().name())
          .update();
    }
  }

  private AgentRun toLocalVerifiedRun(RunRow row) {
    TaskEnvelope task = json.readValue(row.taskJson(), TaskEnvelope.class);
    if (!row.taskId().equals(task.id())
        || !row.principalId().equals(task.principalRef())
        || !row.toolRegistryVersion().equals(task.toolRegistryVersion())
        || !row.policyVersion().equals(task.policyVersion())
        || !row.stateVersion().equals(task.stateVersion())
        || !row.contextPolicyVersion().equals(task.contextPolicyVersion())
        || !Objects.equals(row.modelProvider(), task.modelProvider())
        || !Objects.equals(row.modelRequested(), task.modelRequested())
        || !Objects.equals(row.pricingProfile(), task.pricingProfile())
        || ("PROPOSE_ARTICLE_DRAFT".equals(task.kind())
            != (row.parentRunId() != null))
        || ((row.parentRunId() == null)
            != (row.parentTaskId() == null))
        || ("PROPOSE_ARTICLE_DRAFT".equals(task.kind())
            != (row.runDepth() == 1))
        || (row.parentRunId() == null
            ? row.parentRunDepth() != null || row.runDepth() != 0
            : !Integer.valueOf(0).equals(row.parentRunDepth())
                || row.runDepth() != 1)
        || (row.parentTaskId() != null
            && !row.parentTaskId().equals(task.parentId()))) {
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
                    WHEN 'WORKER_RESULT' THEN 6
                    ELSE 7
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

  private Optional<StoredRun> loadLocalStoredRun(
      String principalId, String runId, boolean forUpdate) {
    Optional<RunRow> row =
        jdbc.sql(
                "SELECT "
                    + RUN_COLUMNS
                    + " FROM agent_runs "
                    + "WHERE principal_id = :principalId AND run_id = :runId"
                    + (forUpdate ? " FOR UPDATE" : ""))
            .param("principalId", principalId)
            .param("runId", runId)
            .query(PostgresAgentRunStore::mapRunRow)
            .optional();
    return row.map(
        value ->
            new StoredRun(
                toLocalVerifiedRun(value),
                value.parentRunId(),
                value.parentTaskId(),
                value.runDepth(),
                value.parentRunDepth()));
  }

  private List<StoredRun> loadChildrenForUpdate(
      String principalId, String parentRunId) {
    return loadChildren(principalId, parentRunId, true);
  }

  private List<StoredRun> loadChildren(
      String principalId, String parentRunId, boolean forUpdate) {
    List<RunRow> rows =
        jdbc.sql(
                "SELECT "
                    + RUN_COLUMNS
                    + " FROM agent_runs "
                    + "WHERE principal_id = :principalId "
                    + "AND parent_run_id = :parentRunId "
                    + "ORDER BY run_id"
                    + (forUpdate ? " FOR UPDATE" : ""))
            .param("principalId", principalId)
            .param("parentRunId", parentRunId)
            .query(PostgresAgentRunStore::mapRunRow)
            .list();
    return rows.stream()
        .map(
            row ->
                new StoredRun(
                    toLocalVerifiedRun(row),
                    row.parentRunId(),
                    row.parentTaskId(),
                    row.runDepth(),
                    row.parentRunDepth()))
        .toList();
  }

  private Optional<WorkerResultEnvelope> loadWorkerResultLocal(
      String principalId, String parentRunId, String childRunId) {
    Optional<WorkerResultRow> row =
        jdbc.sql(
                """
                SELECT parent_run_id, child_task_id, child_status,
                       worker_result_ref,
                       worker_result_envelope::text AS worker_result_json,
                       content_hash, integrity_hash
                FROM agent_worker_results
                WHERE principal_id = :principalId
                  AND child_run_id = :childRunId
                """)
            .param("principalId", principalId)
            .param("childRunId", childRunId)
            .query(
                (resultSet, rowNumber) ->
                    new WorkerResultRow(
                        resultSet.getString("parent_run_id"),
                        resultSet.getString("child_task_id"),
                        resultSet.getString("child_status"),
                        resultSet.getString("worker_result_ref"),
                        resultSet.getString("worker_result_json"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("integrity_hash")))
            .optional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    WorkerResultRow stored = row.orElseThrow();
    WorkerResultEnvelope workerResult =
        json.readValue(
            stored.workerResultJson(), WorkerResultEnvelope.class);
    if (!parentRunId.equals(stored.parentRunId())
        || !childRunId.equals(workerResult.childRunId())
        || !stored.childTaskId().equals(workerResult.childTaskId())
        || !"SUCCEEDED".equals(stored.childStatus())
        || !stored.workerResultRef().equals(workerResult.workerResultRef())
        || !stored.contentHash().equals(workerResult.contentHash())
        || !stored.integrityHash().equals(workerResult.integrityHash())) {
      throw new AgentRunIntegrityException();
    }
    return Optional.of(workerResult);
  }

  private AgentRun verifyStoredGraph(StoredRun stored) {
    AgentRun run = stored.run();
    if (stored.parentRunId() != null) {
      StoredRun parent =
          loadLocalStoredRun(
                  run.principalId(), stored.parentRunId(), false)
              .orElseThrow(AgentRunIntegrityException::new);
      requireExactStoredChildLineage(parent, stored);
      if (!run.lifecycle().terminal()) {
        if (parent.run().lifecycle().terminal()) {
          throw new AgentRunIntegrityException();
        }
        return run;
      }
      WorkerResultEnvelope workerResult =
          loadWorkerResultLocal(
                  run.principalId(), stored.parentRunId(), run.runId())
              .orElse(null);
      ReadOnlyWorkerProfile workerProfile =
          workerProfiles.requireForTerminalChild(
              parent.run().task(),
              run.task(),
              run.bundle().experiment(),
              run.bundle().harnessVersion(),
              run.bundle().componentVersions());
      if (parent.run().lifecycle().terminal()) {
        requireSameTerminalProfile(parent.run(), run);
        ReadOnlyWorkerHandoffVerifier.verifyPair(
            parent.run(), run, workerResult, workerProfile);
      } else {
        ReadOnlyWorkerHandoffVerifier.verifyChild(
            parent.run().task(), run, workerResult, workerProfile);
      }
      return run;
    }

    if (!run.lifecycle().terminal()) {
      requireWorkerParentAuthorityIfPresent(run.task());
      return run;
    }
    verifyWorkerParentProfileIfPresent(run);
    List<StoredRun> children =
        loadChildren(run.principalId(), run.runId(), false);
    List<ResourceBinding> handoffs =
        run.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.HANDOFF)
            .toList();
    if (children.isEmpty()) {
      if (!handoffs.isEmpty()) {
        throw new AgentRunIntegrityException();
      }
      return run;
    }
    if (children.size() != 1 || handoffs.size() != 1) {
      throw new AgentRunIntegrityException();
    }
    StoredRun child = children.getFirst();
    if (!child.run().lifecycle().terminal()) {
      throw new AgentRunIntegrityException();
    }
    WorkerResultEnvelope workerResult =
        loadWorkerResultLocal(
                run.principalId(), run.runId(), child.run().runId())
            .orElse(null);
    ReadOnlyWorkerProfile workerProfile =
        requireSameTerminalProfile(run, child.run());
    ReadOnlyWorkerHandoffVerifier.verifyPair(
        run, child.run(), workerResult, workerProfile);
    return run;
  }

  private void requireExactRunningParent(
      AgentRunContext expected, StoredRun stored) {
    requireExactParentIdentity(expected, stored);
    if (stored.run().lifecycle() != AgentRunLifecycle.RUNNING) {
      throw new AgentRunConflictException();
    }
  }

  private static void requireExactParentIdentity(
      AgentRunContext expected, StoredRun stored) {
    if (stored.parentRunId() != null
        || stored.parentTaskId() != null
        || stored.runDepth() != 0
        || stored.parentRunDepth() != null
        || !expected.runId().equals(stored.run().runId())
        || !expected.principalId().equals(stored.run().principalId())
        || !expected.task().equals(stored.run().task())) {
      throw new AgentRunIntegrityException();
    }
  }

  private void requireExactChildLineage(
      AgentRunContext parent, StoredRun child) {
    if (!parent.runId().equals(child.parentRunId())
        || !parent.task().id().equals(child.parentTaskId())
        || child.runDepth() != 1
        || !Integer.valueOf(0).equals(child.parentRunDepth())
        || !parent.principalId().equals(child.run().principalId())) {
      throw new AgentRunIntegrityException();
    }
    requireProfileForStoredChild(parent.task(), child.run());
  }

  private void requireExactStoredChildLineage(
      StoredRun parent, StoredRun child) {
    if (parent.parentRunId() != null
        || parent.runDepth() != 0
        || parent.parentRunDepth() != null
        || !parent.run().runId().equals(child.parentRunId())
        || !parent.run().task().id().equals(child.parentTaskId())
        || child.runDepth() != 1
        || !Integer.valueOf(0).equals(child.parentRunDepth())
        || !parent.run().principalId().equals(child.run().principalId())) {
      throw new AgentRunIntegrityException();
    }
    requireProfileForStoredChild(
        parent.run().task(), child.run());
  }

  private static void requireExactRunningTransition(
      AgentRun terminal, StoredRun running, boolean child) {
    AgentRun expected =
        AgentRun.running(
            terminal.runId(),
            terminal.principalId(),
            terminal.task(),
            terminal.startedAt());
    if (!running.run().equals(expected)
        || child != (running.parentRunId() != null)) {
      throw new AgentRunConflictException();
    }
  }

  private ReadOnlyWorkerProfile requireWorkerParentAuthority(
      TaskEnvelope parent) {
    return workerProfiles.requireForRunningParent(parent);
  }

  private void requireWorkerParentAuthorityIfPresent(TaskEnvelope parent) {
    if (parent
        .capabilityRefs()
        .contains(AgentExecutionProfile.READ_ONLY_WORKER_CAPABILITY)) {
      requireWorkerParentAuthority(parent);
    }
  }

  private void verifyWorkerParentProfileIfPresent(AgentRun parent) {
    if (!parent
        .task()
        .capabilityRefs()
        .contains(AgentExecutionProfile.READ_ONLY_WORKER_CAPABILITY)) {
      return;
    }
    workerProfiles.requireForTerminalParent(
        parent.task(),
        parent.bundle().experiment(),
        parent.bundle().harnessVersion(),
        parent.bundle().componentVersions());
  }

  private ReadOnlyWorkerProfile requireProfileForStoredChild(
      TaskEnvelope parent, AgentRun child) {
    return child.lifecycle().terminal()
        ? workerProfiles.requireForTerminalChild(
            parent,
            child.task(),
            child.bundle().experiment(),
            child.bundle().harnessVersion(),
            child.bundle().componentVersions())
        : workerProfiles.requireForChild(parent, child.task());
  }

  private ReadOnlyWorkerProfile requireSameTerminalProfile(
      AgentRun parent, AgentRun child) {
    ReadOnlyWorkerProfile parentProfile =
        workerProfiles.requireForTerminalParent(
            parent.task(),
            parent.bundle().experiment(),
            parent.bundle().harnessVersion(),
            parent.bundle().componentVersions());
    ReadOnlyWorkerProfile childProfile =
        workerProfiles.requireForTerminalChild(
            parent.task(),
            child.task(),
            child.bundle().experiment(),
            child.bundle().harnessVersion(),
            child.bundle().componentVersions());
    if (parentProfile != childProfile) {
      throw new AgentRunIntegrityException();
    }
    return parentProfile;
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

  private static String parseAgentRunIdentity(String ref) {
    if (ref == null
        || !ref.matches(
            "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(
          "Handoff binding must name one exact child AgentRun");
    }
    return ref.substring("agent-run://".length());
  }

  private static String parseWorkerResultIdentity(String ref) {
    if (ref == null
        || !ref.matches(
            "worker-result://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(
          "Worker Result binding must name one exact child AgentRun");
    }
    return ref.substring("worker-result://".length());
  }

  private static RunRow mapRunRow(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp completed = resultSet.getTimestamp("completed_at");
    return new RunRow(
        resultSet.getString("principal_id"),
        resultSet.getString("run_id"),
        resultSet.getString("task_id"),
        resultSet.getString("parent_run_id"),
        resultSet.getString("parent_task_id"),
        resultSet.getInt("run_depth"),
        resultSet.getObject("parent_run_depth", Integer.class),
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
        resultSet.getString("model_provider"),
        resultSet.getString("model_requested"),
        resultSet.getString("pricing_profile"),
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

  private record StoredRun(
      AgentRun run,
      String parentRunId,
      String parentTaskId,
      int runDepth,
      Integer parentRunDepth) {}

  private record WorkerResultRow(
      String parentRunId,
      String childTaskId,
      String childStatus,
      String workerResultRef,
      String workerResultJson,
      String contentHash,
      String integrityHash) {}

  private record StoredTraceEntry(
      AgentTraceEntry event,
      String currentRootHash) {}

  private record RunRow(
      String principalId,
      String runId,
      String taskId,
      String parentRunId,
      String parentTaskId,
      int runDepth,
      Integer parentRunDepth,
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
      String modelProvider,
      String modelRequested,
      String pricingProfile,
      java.math.BigDecimal costUsd,
      long tokenCount,
      long latencyMs,
      String failureAttribution,
      java.time.Instant startedAt,
      java.time.Instant completedAt) {}
}
