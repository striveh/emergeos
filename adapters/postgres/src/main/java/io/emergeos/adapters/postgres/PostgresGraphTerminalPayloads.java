package io.emergeos.adapters.postgres;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.GraphTerminalSeal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Canonical, adapter-owned projection for the two exact V10 terminal calls. */
final class PostgresGraphTerminalPayloads {

  private static final JsonMapper JSON = JsonMapper.shared();

  private PostgresGraphTerminalPayloads() {}

  static String child(
      GraphAttemptSnapshot before,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminalChild, "terminalChild");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(workerResult, "workerResult");
    return childProjection(
        before, terminalChild, candidate, workerResult);
  }

  static String preCandidateFailureChild(
      GraphAttemptSnapshot before, AgentRun terminalChild) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminalChild, "terminalChild");
    return childProjection(before, terminalChild, null, null);
  }

  private static String childProjection(
      GraphAttemptSnapshot before,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {
    if (before.cursor().lastSequence() != 14
        || before.cursor().phase() != GraphAttemptPhase.PROVIDER_ATTRIBUTED
        || before.candidate() != null
        || before.workerResult() != null
        || before.terminalSeal() != null
        || (terminalChild.result().status() == RunStatus.SUCCEEDED
            && (candidate == null || workerResult == null))
        || (terminalChild.result().status() != RunStatus.SUCCEEDED
            && (candidate != null || workerResult != null))) {
      throw new IllegalArgumentException(
          "child transition requires the exact attributed prefix");
    }

    GraphTerminalBinding binding =
        GraphTerminalBinding.child(terminalChild, workerResult);
    GraphAttemptEvent event =
        GraphAttemptEvent.terminal(
            before.cursor(),
            GraphAttemptEventType.CHILD_TERMINAL,
            GraphAttemptPhase.CHILD_TERMINAL,
            before.manifest().childSelection(),
            binding,
            terminalChild.completedAt());
    List<GraphAttemptEvent> events = new ArrayList<>(before.events());
    events.add(event);
    List<GraphTerminalBinding> bindings =
        new ArrayList<>(before.terminalBindings());
    bindings.add(binding);
    new GraphAttemptSnapshot(
        before.manifest(),
        event.cursor(before.manifest()),
        events,
        before.parentRun(),
        terminalChild,
        false,
        GraphAttemptOutcome.INCOMPLETE,
        GraphBillingStatus.ATTRIBUTED,
        before.providerAttributions(),
        bindings,
        null,
        candidate,
        workerResult,
        null);

    ObjectNode root = JSON.createObjectNode();
    root.put("protocol", "emergeos.graph-terminal.v10");
    root.put("principal_id", before.manifest().principalId());
    root.put("attempt_id", before.manifest().attemptId());
    if (candidate == null) {
      root.putNull("candidate");
    } else {
      root.set(
          "candidate",
          candidateRow(before, terminalChild, candidate));
    }
    if (workerResult == null) {
      root.putNull("worker_result");
    } else {
      root.set(
          "worker_result",
          workerResultRow(before, terminalChild, workerResult));
    }
    root.set("trace_events", traceRows(terminalChild));
    root.set("resource_bindings", resourceRows(terminalChild));
    root.set(
        "terminal_run",
        terminalRunRow(
            before,
            before.manifest().childSelection(),
            terminalChild));
    root.set(
        "terminal_binding",
        terminalBindingRow(before, event, binding));
    root.set("event", graphEventRow(before, event));
    return serialize(root);
  }

  static String parentAndSeal(
      GraphAttemptSnapshot before,
      AgentRun terminalParent,
      ArtifactLineage artifact) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminalParent, "terminalParent");
    Objects.requireNonNull(artifact, "artifact");
    return parentAndSealProjection(before, terminalParent, artifact);
  }

  static String preCandidateFailureParentAndSeal(
      GraphAttemptSnapshot before, AgentRun terminalParent) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminalParent, "terminalParent");
    if (before.candidate() != null
        || before.workerResult() != null
        || before.childRun() == null
        || before.childRun().result().status() == RunStatus.SUCCEEDED) {
      throw new IllegalArgumentException(
          "pre-Candidate parent requires exact failed child truth");
    }
    return parentAndSealProjection(before, terminalParent, null);
  }

  private static String parentAndSealProjection(
      GraphAttemptSnapshot before,
      AgentRun terminalParent,
      ArtifactLineage artifact) {
    if (before.cursor().lastSequence() != 15
        || before.cursor().phase() != GraphAttemptPhase.CHILD_TERMINAL
        || before.terminalBindings().size() != 1
        || before.terminalSeal() != null
        || before.artifact() != null
        || (terminalParent.result().status() == RunStatus.SUCCEEDED
            && (artifact == null
                || artifact.versions().size() != 1
                || artifact.current().version() != 1))
        || (terminalParent.result().status() != RunStatus.SUCCEEDED
            && artifact != null)) {
      throw new IllegalArgumentException(
          "parent transition requires the exact child-terminal prefix");
    }

    GraphTerminalBinding parentBinding =
        GraphTerminalBinding.parent(terminalParent, artifact);
    GraphAttemptEvent parentEvent =
        GraphAttemptEvent.terminal(
            before.cursor(),
            GraphAttemptEventType.PARENT_TERMINAL,
            GraphAttemptPhase.PARENT_TERMINAL,
            before.manifest().parentSelection(),
            parentBinding,
            terminalParent.completedAt());
    GraphAttemptOutcome outcome =
        terminalParent.result().status().name().equals("SUCCEEDED")
                && before.childRun().result().status().name().equals("SUCCEEDED")
            ? GraphAttemptOutcome.SUCCEEDED
            : GraphAttemptOutcome.FAILED;
    List<String> attributionHashes =
        before.providerAttributions().stream()
            .map(attribution -> attribution.attributionHash())
            .toList();
    GraphTerminalBinding childBinding =
        before.terminalBindings().getFirst();
    String sealHash =
        GraphTerminalSeal.computeHash(
            before.manifest().attemptId(),
            before.manifest().manifestHash(),
            17,
            parentEvent.currentHeadHash(),
            outcome,
            GraphBillingStatus.ATTRIBUTED,
            attributionHashes,
            before.candidate() == null
                ? null
                : before.candidate().candidateRef(),
            before.candidate() == null
                ? null
                : before.candidate().integrityHash(),
            childBinding.terminalHash(),
            parentBinding.terminalHash(),
            terminalParent.completedAt());
    GraphAttemptEvent sealEvent =
        GraphAttemptEvent.sealed(
            parentEvent.cursor(before.manifest()),
            sealHash,
            terminalParent.completedAt());
    GraphTerminalSeal seal =
        new GraphTerminalSeal(
            before.manifest().attemptId(),
            before.manifest().manifestHash(),
            17,
            parentEvent.currentHeadHash(),
            sealEvent.currentHeadHash(),
            outcome,
            GraphBillingStatus.ATTRIBUTED,
            attributionHashes,
            before.candidate() == null
                ? null
                : before.candidate().candidateRef(),
            before.candidate() == null
                ? null
                : before.candidate().integrityHash(),
            childBinding.terminalHash(),
            parentBinding.terminalHash(),
            sealHash,
            terminalParent.completedAt());
    List<GraphAttemptEvent> events = new ArrayList<>(before.events());
    events.add(parentEvent);
    events.add(sealEvent);
    List<GraphTerminalBinding> bindings =
        new ArrayList<>(before.terminalBindings());
    bindings.add(parentBinding);
    new GraphAttemptSnapshot(
        before.manifest(),
        sealEvent.cursor(before.manifest()),
        events,
        terminalParent,
        before.childRun(),
        true,
        outcome,
        GraphBillingStatus.ATTRIBUTED,
        before.providerAttributions(),
        bindings,
        seal,
        before.candidate(),
        before.workerResult(),
        artifact);

    ObjectNode root = JSON.createObjectNode();
    root.put("protocol", "emergeos.graph-terminal.v10");
    root.put("principal_id", before.manifest().principalId());
    root.put("attempt_id", before.manifest().attemptId());
    if (artifact == null) {
      root.putNull("artifact");
      root.putNull("artifact_version");
    } else {
      root.set("artifact", artifactRow(artifact));
      root.set("artifact_version", artifactVersionRow(artifact));
    }
    root.set("trace_events", traceRows(terminalParent));
    root.set("resource_bindings", resourceRows(terminalParent));
    root.set(
        "terminal_run",
        terminalRunRow(
            before,
            before.manifest().parentSelection(),
            terminalParent));
    root.set(
        "terminal_binding",
        terminalBindingRow(before, parentEvent, parentBinding));
    root.set("parent_event", graphEventRow(before, parentEvent));
    root.set("seal", sealRow(before, seal, childBinding, parentBinding));
    root.set("seal_event", graphEventRow(before, sealEvent));
    return serialize(root);
  }

  private static ObjectNode candidateRow(
      GraphAttemptSnapshot before,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", before.manifest().principalId());
    row.put("attempt_id", before.manifest().attemptId());
    row.put("manifest_hash", before.manifest().manifestHash());
    row.put("schema_version", candidate.schemaVersion());
    row.put("candidate_ref", candidate.candidateRef());
    row.put("execution_slot_id", candidate.executionSlotId());
    row.put("repetition", candidate.repetition());
    row.put("child_role", GraphRunRole.CHILD.name());
    row.put("child_run_id", candidate.childRunId());
    row.put("child_task_id", candidate.childTaskId());
    row.put("source_request_ordinal", candidate.sourceRequestOrdinal());
    row.put("source_response_hash", candidate.sourceResponseHash());
    row.put("trace_root_hash", candidate.traceRootHash());
    row.put("output_schema", candidate.outputSchema());
    row.put("content", candidate.content());
    row.put("content_hash", candidate.contentHash());
    row.put("required_evidence_ref", candidate.requiredEvidenceRef());
    row.put(
        "required_evidence_available",
        candidate.requiredEvidenceAvailable());
    row.put("integrity_profile", candidate.integrityProfile());
    row.put("integrity_hash", candidate.integrityHash());
    row.set("candidate_envelope", JSON.valueToTree(candidate));
    row.put("created_at", terminalChild.completedAt().toString());
    return row;
  }

  private static ObjectNode workerResultRow(
      GraphAttemptSnapshot before,
      AgentRun terminalChild,
      WorkerResultEnvelope workerResult) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", before.manifest().principalId());
    row.put("parent_run_id", before.parentRun().runId());
    row.put("child_run_id", terminalChild.runId());
    row.put("child_task_id", terminalChild.task().id());
    row.put("child_status", terminalChild.result().status().name());
    row.put("worker_result_ref", workerResult.workerResultRef());
    row.set("worker_result_envelope", JSON.valueToTree(workerResult));
    row.put("content_hash", workerResult.contentHash());
    row.put("integrity_hash", workerResult.integrityHash());
    return row;
  }

  private static ArrayNode traceRows(AgentRun terminal) {
    ArrayNode rows = JSON.createArrayNode();
    for (AgentTraceEntry entry : terminal.trace().events()) {
      ObjectNode row = rows.addObject();
      row.put("principal_id", terminal.principalId());
      row.put("run_id", terminal.runId());
      row.put("sequence", entry.sequence());
      row.put("event_type", entry.type().name());
      putNullable(row, "tool_name", entry.toolName());
      row.put("status", entry.status());
      putNullable(row, "resource_ref", entry.reference());
      row.put("previous_root_hash", entry.previousRootHash());
      row.put("event_hash", entry.eventHash());
      row.put(
          "current_root_hash",
          IntegrityHashes.nextTraceRoot(
              entry.previousRootHash(), entry.eventHash()));
    }
    return rows;
  }

  private static ArrayNode resourceRows(AgentRun terminal) {
    ArrayNode rows = JSON.createArrayNode();
    for (ResourceBinding binding : terminal.bundle().resourceBindings()) {
      ObjectNode row = rows.addObject();
      row.put("principal_id", terminal.principalId());
      row.put("run_id", terminal.runId());
      row.put("role", binding.role().name());
      row.put("ordinal", binding.ordinal());
      row.put("resource_ref", binding.ref());
      row.put("content_hash", binding.contentHash());
      putNullable(
          row,
          "capture_id",
          binding.role() == ResourceRole.EVIDENCE
              ? binding.ref().substring("capture://".length())
              : null);
      String artifactId = null;
      Integer artifactVersion = null;
      if (binding.role() == ResourceRole.ARTIFACT) {
        String identity =
            binding.ref().substring("artifact-version://".length());
        int separator = identity.lastIndexOf('/');
        artifactId = identity.substring(0, separator);
        artifactVersion =
            Integer.valueOf(identity.substring(separator + 1));
      }
      putNullable(row, "artifact_id", artifactId);
      putNullable(row, "artifact_version", artifactVersion);
      putNullable(
          row,
          "handoff_child_run_id",
          binding.role() == ResourceRole.HANDOFF
              ? binding.ref().substring("agent-run://".length())
              : null);
      putNullable(
          row,
          "worker_result_child_run_id",
          binding.role() == ResourceRole.WORKER_RESULT
              ? binding.ref().substring("worker-result://".length())
              : null);
      row.put("binding_owner_status", terminal.lifecycle().name());
    }
    return rows;
  }

  private static ObjectNode terminalRunRow(
      GraphAttemptSnapshot before,
      GraphRunSelection selection,
      AgentRun terminal) {
    boolean child = selection.role() == GraphRunRole.CHILD;
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", terminal.principalId());
    row.put("run_id", terminal.runId());
    row.put("task_id", terminal.task().id());
    row.put("lifecycle_status", terminal.lifecycle().name());
    row.set("task_envelope", JSON.valueToTree(terminal.task()));
    row.set("result_envelope", JSON.valueToTree(terminal.result()));
    row.set("bundle", JSON.valueToTree(terminal.bundle()));
    row.put("bundle_hash", terminal.bundle().integrityHash());
    row.put("trace_root_hash", terminal.trace().rootHash());
    row.put("last_event_sequence", terminal.trace().eventCount());
    putNullable(row, "resolved_model", terminal.result().resolvedModel());
    row.put("agent_version", terminal.result().agentVersion());
    row.put("verifier_version", terminal.result().verifierVersion());
    row.put("harness_version", terminal.bundle().harnessVersion());
    row.put("tool_registry_version", terminal.task().toolRegistryVersion());
    row.put("policy_version", terminal.task().policyVersion());
    row.put("state_version", terminal.task().stateVersion());
    row.put(
        "context_policy_version",
        terminal.task().contextPolicyVersion());
    row.put("cost_usd", terminal.result().costUsd());
    row.put("token_count", terminal.result().tokenCount());
    row.put("latency_ms", terminal.result().latencyMs());
    putNullable(
        row,
        "failure_attribution",
        terminal.bundle().failureAttribution());
    row.put("started_at", terminal.startedAt().toString());
    row.put("completed_at", terminal.completedAt().toString());
    putNullable(row, "model_provider", terminal.task().modelProvider());
    putNullable(row, "model_requested", terminal.task().modelRequested());
    putNullable(row, "pricing_profile", terminal.task().pricingProfile());
    putNullable(
        row,
        "parent_run_id",
        child ? before.parentRun().runId() : null);
    putNullable(
        row,
        "parent_task_id",
        child ? before.parentRun().task().id() : null);
    row.put("run_depth", child ? 1 : 0);
    putNullable(row, "parent_run_depth", child ? Integer.valueOf(0) : null);
    row.put("graph_attempt_id", before.manifest().attemptId());
    row.put("graph_manifest_hash", before.manifest().manifestHash());
    row.put("graph_role", selection.role().name());
    row.put("graph_task_hash", selection.taskHash());
    row.put("graph_selector_hash", selection.selectorHash());
    row.put("graph_execution_profile_id", selection.executionProfileId());
    row.put(
        "graph_execution_profile_fingerprint",
        selection.executionProfileFingerprint());
    row.put(
        "graph_worker_registry_version",
        selection.workerRegistryVersion());
    row.put("graph_worker_profile_id", selection.workerProfileId());
    row.put(
        "graph_worker_profile_fingerprint",
        selection.workerProfileFingerprint());
    return row;
  }

  private static ObjectNode terminalBindingRow(
      GraphAttemptSnapshot before,
      GraphAttemptEvent event,
      GraphTerminalBinding binding) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", before.manifest().principalId());
    row.put("attempt_id", before.manifest().attemptId());
    row.put("manifest_hash", before.manifest().manifestHash());
    row.put("role", binding.role().name());
    row.put("event_sequence", event.sequence());
    row.put("run_id", binding.runId());
    row.put("task_id", binding.taskId());
    row.put("status", binding.status().name());
    row.put("bundle_hash", binding.bundleHash());
    row.put("trace_root_hash", binding.traceRootHash());
    putNullable(row, "effect_ref", binding.effectRef());
    putNullable(row, "effect_hash", binding.effectHash());
    row.put("completed_at", binding.completedAt().toString());
    row.put("terminal_hash", binding.terminalHash());
    return row;
  }

  private static ObjectNode graphEventRow(
      GraphAttemptSnapshot before, GraphAttemptEvent event) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", before.manifest().principalId());
    row.put("attempt_id", before.manifest().attemptId());
    row.put("manifest_hash", before.manifest().manifestHash());
    row.put("sequence", event.sequence());
    row.put("event_type", event.type().name());
    row.put("occurred_at", event.occurredAt().toString());
    putNullable(
        row,
        "phase_from",
        event.phaseFrom() == null ? null : event.phaseFrom().name());
    row.put("phase_to", event.phaseTo().name());
    putNullable(
        row, "role", event.role() == null ? null : event.role().name());
    putNullable(row, "run_id", event.runId());
    putNullable(row, "task_id", event.taskId());
    putNullable(row, "actor", event.actor());
    putNullable(row, "challenge_hash", event.challengeHash());
    putNullable(row, "request_ordinal", event.requestOrdinal());
    putNullable(row, "request_hash", event.requestHash());
    putNullable(row, "model_requested", event.modelRequested());
    row.put("previous_head_hash", event.previousHeadHash());
    row.put("event_hash", event.eventHash());
    row.put("current_head_hash", event.currentHeadHash());
    putNullable(row, "evidence_hash", event.evidenceHash());
    return row;
  }

  private static ObjectNode artifactRow(ArtifactLineage artifact) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", artifact.principalId());
    row.put("artifact_id", artifact.artifactId());
    row.put("source_capture_id", artifact.sourceCaptureId());
    row.put("current_version", artifact.current().version());
    row.put("current_hash", artifact.current().contentHash());
    return row;
  }

  private static ObjectNode artifactVersionRow(ArtifactLineage artifact) {
    ArtifactLineageEntry version = artifact.current();
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", artifact.principalId());
    row.put("artifact_id", artifact.artifactId());
    row.put("version", version.version());
    row.put("content", version.content());
    row.put("content_hash", version.contentHash());
    putNullable(row, "base_version", version.baseVersion());
    putNullable(row, "base_hash", version.baseHash());
    row.put("created_at", version.createdAt().toString());
    return row;
  }

  private static ObjectNode sealRow(
      GraphAttemptSnapshot before,
      GraphTerminalSeal seal,
      GraphTerminalBinding childBinding,
      GraphTerminalBinding parentBinding) {
    ObjectNode row = JSON.createObjectNode();
    row.put("principal_id", before.manifest().principalId());
    row.put("attempt_id", seal.attemptId());
    row.put("manifest_hash", seal.manifestHash());
    row.put("final_sequence", seal.finalSequence());
    row.put("final_head_hash", seal.finalHeadHash());
    row.put("graph_outcome", seal.graphOutcome().name());
    row.put("billing_status", seal.billingStatus().name());
    row.put("seal_hash", seal.sealHash());
    row.put("sealed_at", seal.sealedAt().toString());
    row.put("pre_seal_sequence", 16);
    row.put("pre_seal_head_hash", seal.preSealHeadHash());
    row.put(
        "provider_attribution_1_hash",
        seal.providerAttributionHashes().get(0));
    row.put(
        "provider_attribution_2_hash",
        seal.providerAttributionHashes().get(1));
    putNullable(row, "candidate_ref", seal.candidateRef());
    putNullable(
        row,
        "candidate_integrity_hash",
        seal.candidateIntegrityHash());
    row.put("child_terminal_hash", childBinding.terminalHash());
    row.put("parent_terminal_hash", parentBinding.terminalHash());
    return row;
  }

  private static void putNullable(
      ObjectNode row, String name, String value) {
    if (value == null) {
      row.putNull(name);
    } else {
      row.put(name, value);
    }
  }

  private static void putNullable(
      ObjectNode row, String name, Integer value) {
    if (value == null) {
      row.putNull(name);
    } else {
      row.put(name, value);
    }
  }

  private static String serialize(JsonNode root) {
    try {
      return JSON.writeValueAsString(root);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException(
          "terminal payload serialization failed", failure);
    }
  }
}
