package io.emergeos.adapters.postgres;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Frozen test-only writer for populated V7 upgrade fixtures.
 *
 * <p>This class intentionally knows only the V7 column set. Migration tests
 * must not use the current production Store to manufacture historical rows.
 */
final class V7GraphAttemptSqlSeeder {

  private static final Instant STARTED =
      Instant.parse("2026-07-31T06:00:00Z");

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final JsonMapper json = JsonMapper.shared();

  V7GraphAttemptSqlSeeder(DataSource dataSource) {
    jdbc = JdbcClient.create(dataSource);
    transaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  Seed seed(String suffix, int lastSequence) {
    if (lastSequence != 1
        && lastSequence != 10
        && lastSequence != 11) {
      throw new IllegalArgumentException(
          "V7 upgrade fixture supports only sequence 1, 10 or 11");
    }
    Seed seed = fixture(suffix, "postgres-graph-attempt-v1");
    transaction.executeWithoutResult(
        ignored -> persist(seed, lastSequence));
    return seed;
  }

  Seed seedTerminalV8(String suffix) {
    return seedTerminalV8(suffix, 11);
  }

  Seed seedTerminalV8(String suffix, int lastSequence) {
    if (lastSequence != 7 && lastSequence != 11) {
      throw new IllegalArgumentException(
          "V8 terminal fixture supports only sequence 7 or 11");
    }
    Seed seed =
        fixture(
            suffix,
            io.emergeos.core.domain.GraphAttemptSnapshot
                .TERMINAL_PROTOCOL_VERSION);
    transaction.executeWithoutResult(
        ignored -> persist(seed, lastSequence));
    return seed;
  }

  private void persist(Seed seed, int lastSequence) {
    List<GraphAttemptEvent> events = events(seed);
    insertManifest(seed.manifest(), events.getFirst());
    seed.manifest().selections().forEach(
        selection -> insertBinding(seed.manifest(), selection));
    if (lastSequence >= 4) {
      insertRun(
          seed.manifest(),
          seed.manifest().parentSelection(),
          seed.parent());
    }
    if (lastSequence >= 6) {
      insertRun(
          seed.manifest(),
          seed.manifest().childSelection(),
          seed.child());
    }
    events.stream()
        .limit(lastSequence)
        .forEach(event -> insertEvent(seed.manifest(), event));
    insertHead(seed.manifest(), events.getFirst());
    for (int index = 1; index < lastSequence; index++) {
      updateHead(seed.manifest(), events.get(index));
    }
  }

  private List<GraphAttemptEvent> events(Seed seed) {
    GraphAttemptManifest manifest = seed.manifest();
    List<GraphAttemptEvent> events = new ArrayList<>();
    GraphAttemptEvent event =
        GraphAttemptEvent.claimed(manifest, STARTED);
    events.add(event);
    GraphAttemptCursor cursor = event.cursor(manifest);
    event =
        GraphAttemptEvent.next(
            cursor,
            GraphAttemptEventType.OPERATOR_APPROVED,
            STARTED.plusMillis(1),
            GraphAttemptPhase.OPERATOR_APPROVED,
            null,
            null,
            null,
            GraphOperatorApproval.ownerTty(manifest),
            null);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.PARENT_AUTHORIZED,
            GraphAttemptPhase.PARENT_AUTHORIZED,
            manifest.parentSelection(),
            2);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.PARENT_STARTED,
            GraphAttemptPhase.PARENT_RUNNING,
            manifest.parentSelection(),
            3);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.CHILD_AUTHORIZED,
            GraphAttemptPhase.CHILD_AUTHORIZED,
            manifest.childSelection(),
            4);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.CHILD_STARTED,
            GraphAttemptPhase.CHILD_RUNNING,
            manifest.childSelection(),
            5);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
            GraphAttemptPhase.EGRESS_CONSUMED,
            manifest.childSelection(),
            6);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.CREDENTIAL_READ_STARTED,
            GraphAttemptPhase.CREDENTIAL_READING,
            manifest.childSelection(),
            7);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.CLIENT_CREATED,
            GraphAttemptPhase.CLIENT_READY,
            manifest.childSelection(),
            8);
    events.add(event);
    cursor = event.cursor(manifest);
    event =
        nextRun(
            cursor,
            GraphAttemptEventType.MODEL_CREATED,
            GraphAttemptPhase.MODEL_READY,
            manifest.childSelection(),
            9);
    events.add(event);
    cursor = event.cursor(manifest);
    events.add(
        GraphAttemptEvent.next(
            cursor,
            GraphAttemptEventType.PROVIDER_INTENT,
            STARTED.plusMillis(10),
            GraphAttemptPhase.PROVIDER_PENDING,
            GraphRunRole.CHILD,
            manifest.childSelection().runId(),
            manifest.childSelection().taskId(),
            null,
            new GraphProviderIntent(
                1,
                IntegrityHashes.utf8ContentHash(
                    seed.suffix() + ":request-1"),
                seed.child().task().modelRequested())));
    return List.copyOf(events);
  }

  private static GraphAttemptEvent nextRun(
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      GraphRunSelection selection,
      long millis) {
    return GraphAttemptEvent.next(
        cursor,
        type,
        STARTED.plusMillis(millis),
        phase,
        selection.role(),
        selection.runId(),
        selection.taskId(),
        null,
        null);
  }

  private void insertManifest(
      GraphAttemptManifest manifest, GraphAttemptEvent claimed) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempts (
              principal_id, attempt_id, execution_slot_id,
              manifest_schema_version, graph_protocol_version,
              integrity_profile, case_id, pack_raw_sha256,
              environment_raw_sha256, capture_id,
              capture_request_hash, artifact_id, started_at,
              pricing_profile_fingerprint,
              prompt_surface_fingerprint,
              conductor_surface_fingerprint, reservation_usd,
              maximum_provider_requests, parent_actor, child_actor,
              experiment_arm, experiment_repetition,
              manifest_json, manifest_hash, initial_head_hash,
              created_at
            ) VALUES (
              :principalId, :attemptId, :executionSlotId,
              :schemaVersion, :graphProtocolVersion,
              :integrityProfile, :caseId, :packRawSha256,
              :environmentRawSha256, :captureId,
              :captureRequestHash, :artifactId, :startedAt,
              :pricingProfileFingerprint,
              :promptSurfaceFingerprint,
              :conductorSurfaceFingerprint, :reservationUsd,
              :maximumProviderRequests, :parentActor, :childActor,
              :experimentArm, :experimentRepetition,
              CAST(:manifestJson AS jsonb), :manifestHash,
              :initialHeadHash, :createdAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("executionSlotId", manifest.executionSlotId())
        .param("schemaVersion", manifest.schemaVersion())
        .param("graphProtocolVersion", manifest.graphProtocolVersion())
        .param("integrityProfile", manifest.integrityProfile())
        .param("caseId", manifest.caseId())
        .param("packRawSha256", manifest.packRawSha256())
        .param("environmentRawSha256", manifest.environmentRawSha256())
        .param("captureId", manifest.captureId())
        .param("captureRequestHash", manifest.captureRequestHash())
        .param("artifactId", manifest.artifactId())
        .param("startedAt", Timestamp.from(manifest.startedAt()))
        .param(
            "pricingProfileFingerprint",
            manifest.pricingProfileFingerprint())
        .param(
            "promptSurfaceFingerprint",
            manifest.promptSurfaceFingerprint())
        .param(
            "conductorSurfaceFingerprint",
            manifest.conductorSurfaceFingerprint())
        .param("reservationUsd", manifest.reservationUsd())
        .param(
            "maximumProviderRequests",
            manifest.maximumProviderRequests())
        .param("parentActor", manifest.parentActor())
        .param("childActor", manifest.childActor())
        .param("experimentArm", manifest.experiment().arm())
        .param(
            "experimentRepetition",
            manifest.experiment().repetition())
        .param("manifestJson", json.writeValueAsString(manifest))
        .param("manifestHash", manifest.manifestHash())
        .param("initialHeadHash", claimed.previousHeadHash())
        .param("createdAt", Timestamp.from(STARTED))
        .update();
  }

  private void insertBinding(
      GraphAttemptManifest manifest, GraphRunSelection selection) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_run_bindings (
              principal_id, attempt_id, manifest_hash, role,
              run_id, task_id, task_hash, selector_hash,
              execution_profile_id,
              execution_profile_fingerprint,
              worker_registry_version, worker_profile_id,
              worker_profile_fingerprint, reserved_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :role,
              :runId, :taskId, :taskHash, :selectorHash,
              :executionProfileId,
              :executionProfileFingerprint,
              :workerRegistryVersion, :workerProfileId,
              :workerProfileFingerprint, :reservedAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("role", selection.role().name())
        .param("runId", selection.runId())
        .param("taskId", selection.taskId())
        .param("taskHash", selection.taskHash())
        .param("selectorHash", selection.selectorHash())
        .param("executionProfileId", selection.executionProfileId())
        .param(
            "executionProfileFingerprint",
            selection.executionProfileFingerprint())
        .param(
            "workerRegistryVersion", selection.workerRegistryVersion())
        .param("workerProfileId", selection.workerProfileId())
        .param(
            "workerProfileFingerprint",
            selection.workerProfileFingerprint())
        .param("reservedAt", Timestamp.from(STARTED))
        .update();
  }

  private void insertEvent(
      GraphAttemptManifest manifest, GraphAttemptEvent event) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_events (
              principal_id, attempt_id, manifest_hash, sequence,
              event_type, occurred_at, phase_from, phase_to,
              role, run_id, task_id, actor, challenge_hash,
              request_ordinal, request_hash, model_requested,
              previous_head_hash, event_hash, current_head_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :sequence,
              :eventType, :occurredAt, :phaseFrom, :phaseTo,
              :role, :runId, :taskId, :actor, :challengeHash,
              :requestOrdinal, :requestHash, :modelRequested,
              :previousHeadHash, :eventHash, :currentHeadHash
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("sequence", event.sequence())
        .param("eventType", event.type().name())
        .param("occurredAt", Timestamp.from(event.occurredAt()))
        .param(
            "phaseFrom",
            event.phaseFrom() == null ? null : event.phaseFrom().name(),
            Types.VARCHAR)
        .param("phaseTo", event.phaseTo().name())
        .param(
            "role",
            event.role() == null ? null : event.role().name(),
            Types.VARCHAR)
        .param("runId", event.runId(), Types.VARCHAR)
        .param("taskId", event.taskId(), Types.VARCHAR)
        .param("actor", event.actor(), Types.VARCHAR)
        .param("challengeHash", event.challengeHash(), Types.CHAR)
        .param("requestOrdinal", event.requestOrdinal(), Types.SMALLINT)
        .param("requestHash", event.requestHash(), Types.CHAR)
        .param("modelRequested", event.modelRequested(), Types.VARCHAR)
        .param("previousHeadHash", event.previousHeadHash())
        .param("eventHash", event.eventHash())
        .param("currentHeadHash", event.currentHeadHash())
        .update();
  }

  private void insertHead(
      GraphAttemptManifest manifest, GraphAttemptEvent event) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_heads (
              principal_id, attempt_id, manifest_hash, phase,
              state_version, last_sequence, head_hash,
              billing_status, provider_intent_count,
              created_at, updated_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :phase,
              :stateVersion, :lastSequence, :headHash,
              :billingStatus, :providerIntentCount,
              :createdAt, :updatedAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("phase", event.phaseTo().name())
        .param("stateVersion", (long) event.sequence())
        .param("lastSequence", event.sequence())
        .param("headHash", event.currentHeadHash())
        .param("billingStatus", "NOT_INVOKED")
        .param("providerIntentCount", 0)
        .param("createdAt", Timestamp.from(STARTED))
        .param("updatedAt", Timestamp.from(event.occurredAt()))
        .update();
  }

  private void updateHead(
      GraphAttemptManifest manifest, GraphAttemptEvent event) {
    boolean invoked =
        event.type() == GraphAttemptEventType.PROVIDER_INTENT;
    jdbc.sql(
            """
            UPDATE agent_graph_attempt_heads
            SET phase = :phase,
                state_version = :stateVersion,
                last_sequence = :lastSequence,
                head_hash = :headHash,
                billing_status = :billingStatus,
                provider_intent_count = :providerIntentCount,
                updated_at = :updatedAt
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            """)
        .param("phase", event.phaseTo().name())
        .param("stateVersion", (long) event.sequence())
        .param("lastSequence", event.sequence())
        .param("headHash", event.currentHeadHash())
        .param("billingStatus", invoked ? "UNKNOWN" : "NOT_INVOKED")
        .param("providerIntentCount", invoked ? 1 : 0)
        .param("updatedAt", Timestamp.from(event.occurredAt()))
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .update();
  }

  private void insertRun(
      GraphAttemptManifest manifest,
      GraphRunSelection selection,
      AgentRun running) {
    boolean child = selection.role() == GraphRunRole.CHILD;
    jdbc.sql(
            """
            INSERT INTO agent_runs (
              principal_id, run_id, task_id,
              parent_run_id, parent_task_id,
              run_depth, parent_run_depth,
              lifecycle_status, task_envelope,
              tool_registry_version, policy_version,
              state_version, context_policy_version,
              model_provider, model_requested, pricing_profile,
              started_at,
              graph_attempt_id, graph_manifest_hash, graph_role,
              graph_task_hash, graph_selector_hash,
              graph_execution_profile_id,
              graph_execution_profile_fingerprint,
              graph_worker_registry_version,
              graph_worker_profile_id,
              graph_worker_profile_fingerprint
            ) VALUES (
              :principalId, :runId, :taskId,
              :parentRunId, :parentTaskId,
              :runDepth, :parentRunDepth,
              'RUNNING', CAST(:taskJson AS jsonb),
              :toolRegistryVersion, :policyVersion,
              :stateVersion, :contextPolicyVersion,
              :modelProvider, :modelRequested, :pricingProfile,
              :startedAt,
              :attemptId, :manifestHash, :role,
              :taskHash, :selectorHash, :executionProfileId,
              :executionProfileFingerprint,
              :workerRegistryVersion,
              :workerProfileId,
              :workerProfileFingerprint
            )
            """)
        .param("principalId", manifest.principalId())
        .param("runId", running.runId())
        .param("taskId", running.task().id())
        .param(
            "parentRunId",
            child ? manifest.parentSelection().runId() : null,
            Types.VARCHAR)
        .param(
            "parentTaskId",
            child ? manifest.parentSelection().taskId() : null,
            Types.VARCHAR)
        .param("runDepth", child ? 1 : 0)
        .param("parentRunDepth", child ? 0 : null, Types.SMALLINT)
        .param("taskJson", json.writeValueAsString(running.task()))
        .param(
            "toolRegistryVersion",
            running.task().toolRegistryVersion())
        .param("policyVersion", running.task().policyVersion())
        .param("stateVersion", running.task().stateVersion())
        .param(
            "contextPolicyVersion",
            running.task().contextPolicyVersion())
        .param("modelProvider", running.task().modelProvider(), Types.VARCHAR)
        .param("modelRequested", running.task().modelRequested(), Types.VARCHAR)
        .param("pricingProfile", running.task().pricingProfile(), Types.VARCHAR)
        .param("startedAt", Timestamp.from(running.startedAt()))
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("role", selection.role().name())
        .param("taskHash", selection.taskHash())
        .param("selectorHash", selection.selectorHash())
        .param("executionProfileId", selection.executionProfileId())
        .param(
            "executionProfileFingerprint",
            selection.executionProfileFingerprint())
        .param(
            "workerRegistryVersion", selection.workerRegistryVersion())
        .param("workerProfileId", selection.workerProfileId())
        .param(
            "workerProfileFingerprint",
            selection.workerProfileFingerprint())
        .update();
  }

  private static Seed fixture(
      String suffix, String graphProtocolVersion) {
    String principal = "v7-upgrade-owner-" + suffix;
    String caseId = "v7-upgrade-case-" + suffix;
    PricingProfile pricing =
        new PricingProfile(
            "openai-test-pricing-v1",
            "openai.responses",
            "gpt-test",
            750,
            75,
            4_500);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            pricing,
            "openai-test-protocol-v1",
            IntegrityHashes.utf8ContentHash("conductor"),
            "environment://sha256:"
                + IntegrityHashes.utf8ContentHash("environment"),
            new HarnessExperiment("graph-upgrade-test", 1),
            272_000,
            1_000,
            new BigDecimal("0.417000"));
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            caseId + "-parent-task",
            principal,
            "draft a synthetic article",
            "capture://capture-1",
            DataClass.PUBLIC);
    TaskEnvelope childTask =
        worker.newChildTask(
            parentTask,
            new WorkerHandoffRequest(
                worker.workerName(),
                parentTask.intent(),
                parentTask.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                parentTask.budgetUsd(),
                CancellationSignal.never()),
            caseId + "-child-task");
    AgentRun parent =
        AgentRun.running(
            caseId + "-parent-run", principal, parentTask, STARTED);
    AgentRun child =
        AgentRun.running(
            caseId + "-child-run", principal, childTask, STARTED);
    GraphRunSelection parentSelection =
        new GraphRunSelection(
            GraphRunRole.PARENT,
            parent.runId(),
            parentTask.id(),
            IntegrityHashes.taskHash(parentTask),
            parentProfile.id(),
            parentProfile.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphRunSelection childSelection =
        new GraphRunSelection(
            GraphRunRole.CHILD,
            child.runId(),
            childTask.id(),
            IntegrityHashes.taskHash(childTask),
            worker.id(),
            worker.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphAttemptManifest manifest =
        GraphAttemptManifest.create(
            graphProtocolVersion,
            principal,
            "v7-upgrade-slot-" + suffix,
            caseId,
            IntegrityHashes.utf8ContentHash("pack:" + suffix),
            IntegrityHashes.utf8ContentHash("environment:" + suffix),
            "capture-1",
            IntegrityHashes.utf8ContentHash("capture:" + suffix),
            "artifact-" + suffix,
            STARTED,
            pricing.fingerprint(),
            IntegrityHashes.utf8ContentHash("prompt:" + suffix),
            IntegrityHashes.utf8ContentHash("conductor:" + suffix),
            worker.reservationUsd(),
            2,
            "SCRIPTED_FAKE",
            "OPENAI_RESPONSES",
            worker.experiment(),
            parentSelection,
            childSelection);
    return new Seed(suffix, manifest, parent, child, worker);
  }

  record Seed(
      String suffix,
      GraphAttemptManifest manifest,
      AgentRun parent,
      AgentRun child,
      ModelBoundReadOnlyWorkerExecutionProfile profile) {}
}
