package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessRunBundleConsistencyTest {

  @Test
  void bindsTaskResultTraceAndImmutableResourcesToOneRun() {
    HarnessRunBundle bundle = bundle(components("agent", "verifier"), "task-001");

    assertEquals("run-001", bundle.result().runId());
    assertEquals("task-001", bundle.task().id());
    assertEquals("task-001", bundle.result().taskId());
    assertEquals(bundle.result().traceRef(), bundle.traceRef());
    assertEquals(IntegrityHashes.PROFILE, bundle.integrityProfile());
    assertEquals(
        "58f245058fa868099468b8ae4f7b435b8587a5237ae64422b7524e60d8ffe828",
        bundle.integrityHash(),
        "Java must match the shared JSON/Node golden vector");
  }

  @Test
  void rejectsTaskIdDifferentFromEmbeddedTaskOrResult() {
    assertThrows(
        IllegalArgumentException.class,
        () -> bundle(components("agent", "verifier"), "different-task"));
  }

  @Test
  void rejectsAWellFormedButIncorrectIntegrityHash() {
    HarnessRunBundle valid = bundle(components("agent", "verifier"), "task-001");
    String wrong =
        valid.integrityHash().charAt(0) == '0'
            ? "1" + valid.integrityHash().substring(1)
            : "0" + valid.integrityHash().substring(1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessRunBundle(
                valid.schemaVersion(),
                valid.runId(),
                valid.taskId(),
                valid.experiment(),
                valid.modelResolved(),
                valid.harnessVersion(),
                valid.componentVersions(),
                valid.environmentSnapshotRef(),
                valid.toolRegistryVersion(),
                valid.task(),
                valid.result(),
                valid.workingSelfRef(),
                valid.traceRef(),
                valid.traceRootHash(),
                valid.handoffRefs(),
                valid.checkpointRefs(),
                valid.resourceBindings(),
                valid.verificationRef(),
                valid.failureAttribution(),
                valid.outcome(),
                valid.costUsd(),
                valid.tokenCount(),
                valid.latencyMs(),
                valid.integrityProfile(),
                wrong));
  }

  @Test
  void canonicalHashIsIndependentOfMapInsertionOrder() {
    Map<String, String> first = components("agent", "verifier");
    Map<String, String> second = components("trace-integrity", "verifier");

    assertEquals(
        bundle(first, "task-001").integrityHash(),
        bundle(second, "task-001").integrityHash());
  }

  @Test
  void traceMutationCannotReuseAnOldEventHash() {
    AgentTraceEnvelope trace = trace();
    AgentTraceEntry event = trace.events().getFirst();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentTraceEntry(
                event.sequence(),
                event.type(),
                event.toolName(),
                "BLOCKED",
                event.reference(),
                event.previousRootHash(),
                event.eventHash()));
  }

  @Test
  void allowsSafePostDispatchDeadlineRejectionMetadata() {
    AgentTraceEnvelope trace = deadlineTrace();

    assertEquals(
        "2532fa4beeafb6dbdd0e20d55ff349dba905256aa0f0664de4e9843bb96500ac",
        trace.rootHash(),
        "Java must match the valid Node deadline Trace fixture");
  }

  private static AgentTraceEnvelope deadlineTrace() {
    String root = IntegrityHashes.emptyTraceRoot();
    List<AgentTraceEntry> events = new java.util.ArrayList<>();
    for (TraceSpec event :
        List.of(
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://task-deadline-policy"),
            new TraceSpec(
                TraceEventType.TOOL_REQUEST,
                "capture.read",
                "REQUESTED",
                "capture://capture-001"),
            new TraceSpec(
                TraceEventType.TOOL_REJECTED,
                "capture.read",
                "DEADLINE_EXCEEDED",
                "capture://capture-001"))) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              events.size() + 1,
              event.type(),
              event.toolName(),
              event.status(),
              event.reference(),
              root);
      events.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    return AgentTraceEnvelope.create(
        "1.0",
        "run-deadline-policy",
        "task-deadline-policy",
        events);
  }

  @Test
  void changingAnyBoundResourceChangesBundleHash() {
    HarnessRunBundle original = bundle(components("agent", "verifier"), "task-001");
    ResourceBinding artifact = original.resourceBindings().get(1);
    ResourceBinding changed =
        new ResourceBinding(
            artifact.role(),
            artifact.ordinal(),
            artifact.ref(),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    HarnessRunBundle modified =
        HarnessRunBundle.create(
            original.schemaVersion(),
            original.runId(),
            original.taskId(),
            original.experiment(),
            original.modelResolved(),
            original.harnessVersion(),
            original.componentVersions(),
            original.environmentSnapshotRef(),
            original.toolRegistryVersion(),
            original.task(),
            original.result(),
            original.workingSelfRef(),
            original.traceRef(),
            original.traceRootHash(),
            original.handoffRefs(),
            original.checkpointRefs(),
            List.of(original.resourceBindings().getFirst(), changed),
            original.verificationRef(),
            original.failureAttribution(),
            original.outcome(),
            original.costUsd(),
            original.tokenCount(),
            original.latencyMs());

    assertNotEquals(original.integrityHash(), modified.integrityHash());
  }

  @Test
  void rejectsAResourceListOutsideTheFrozenGlobalOrder() {
    HarnessRunBundle original = bundle(components("agent", "verifier"), "task-001");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRunBundle.create(
                original.schemaVersion(),
                original.runId(),
                original.taskId(),
                original.experiment(),
                original.modelResolved(),
                original.harnessVersion(),
                original.componentVersions(),
                original.environmentSnapshotRef(),
                original.toolRegistryVersion(),
                original.task(),
                original.result(),
                original.workingSelfRef(),
                original.traceRef(),
                original.traceRootHash(),
                original.handoffRefs(),
                original.checkpointRefs(),
                List.of(
                    original.resourceBindings().getLast(),
                    original.resourceBindings().getFirst()),
                original.verificationRef(),
                original.failureAttribution(),
                original.outcome(),
                original.costUsd(),
                original.tokenCount(),
                original.latencyMs()));
  }

  @Test
  void rejectsComponentVersionsThatContradictTheResult() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bundle(
                Map.of(
                    "agent", "different-agent",
                    "verifier", "agent-draft-verifier-v1",
                    "trace-integrity", IntegrityHashes.PROFILE),
                "task-001"));
  }

  @Test
  void legacyBundleKeepsReadingPreviouslyAllowedAdditionalComponentVersions() {
    bundle(
        Map.of(
            "agent", "agent-draft-service-v1",
            "verifier", "agent-draft-verifier-v1",
            "trace-integrity", IntegrityHashes.PROFILE,
            "model-adapter", "historical-adapter-v1"),
        "task-001");
  }

  @Test
  void preservesObservedOverDeadlineLatencyOnlyForNonSuccess() {
    HarnessRunBundle failed =
        assertDoesNotThrow(
            () ->
                deadlineBundle(
                    RunStatus.FAILED,
                    7,
                    "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH"));
    assertEquals(
        "d2fdbf0c2c506392cd3da1fdbb0507599c6c4e3733fe4b7b289722e939e77f39",
        failed.integrityHash(),
        "Java must match the valid Node deadline-failure fixture");
    for (RunStatus status : RunStatus.values()) {
      if (status != RunStatus.SUCCEEDED) {
        assertDoesNotThrow(
            () -> deadlineBundle(status, 7, "SYNTHETIC_NON_SUCCESS"));
      }
    }
    assertDoesNotThrow(
        () -> deadlineBundle(RunStatus.FAILED, 5, "DEADLINE_EXHAUSTED"));
    for (long latencyMs : List.of(0L, 5L)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              deadlineBundle(
                  RunStatus.FAILED,
                  latencyMs,
                  "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> deadlineBundle(RunStatus.SUCCEEDED, 7, null));
  }

  static HarnessRunBundle goldenBundle() {
    return bundle(components("agent", "verifier"), "task-001");
  }

  private static HarnessRunBundle bundle(
      Map<String, String> componentVersions, String bundleTaskId) {
    TaskEnvelope task = task();
    ResultEnvelope result = result();
    AgentTraceEnvelope trace = trace();
    return HarnessRunBundle.create(
        "1.0",
        "run-001",
        bundleTaskId,
        null,
        "scripted-fake-draft-v1",
        "framework-free-agent-kernel-v1",
        componentVersions,
        null,
        "agent-tools-v1",
        task,
        result,
        null,
        result.traceRef(),
        trace.rootHash(),
        List.of(),
        List.of(),
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE,
                0,
                "capture://capture-001",
                "1111111111111111111111111111111111111111111111111111111111111111"),
            new ResourceBinding(
                ResourceRole.ARTIFACT,
                0,
                "artifact-version://artifact-001/1",
                "2222222222222222222222222222222222222222222222222222222222222222")),
        null,
        null,
        RunStatus.SUCCEEDED,
        BigDecimal.ZERO,
        0,
        25);
  }

  private static TaskEnvelope task() {
    return new TaskEnvelope(
        "1.0",
        "task-001",
        null,
        "owner-001",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Create one safe synthetic draft",
        List.of("capture://capture-001"),
        List.of(),
        List.of("text"),
        DataClass.PERSONAL,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("uses owned evidence"),
        false,
        2,
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

  private static ResultEnvelope result() {
    return new ResultEnvelope(
        "1.0",
        "run-001",
        "task-001",
        RunStatus.SUCCEEDED,
        List.of("artifact-version://artifact-001/1"),
        List.of("capture://capture-001"),
        List.of(),
        List.of(),
        List.of(),
        "scripted-fake-draft-v1",
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        BigDecimal.ZERO,
        0,
        25,
        "/api/v1/agent-runs/run-001/trace",
        null);
  }

  private static AgentTraceEnvelope trace() {
    String root = IntegrityHashes.emptyTraceRoot();
    List<AgentTraceEntry> events = new java.util.ArrayList<>();
    for (TraceSpec event :
        List.of(
            new TraceSpec(
                TraceEventType.MODEL_STEP, null, "COMPLETED", "task://task-001"),
            new TraceSpec(
                TraceEventType.TOOL_REQUEST,
                "capture.read",
                "REQUESTED",
                "capture://capture-001"),
            new TraceSpec(
                TraceEventType.TOOL_RESULT,
                "capture.read",
                "SUCCEEDED",
                "capture://capture-001"),
            new TraceSpec(
                TraceEventType.MODEL_STEP, null, "COMPLETED", "task://task-001"),
            new TraceSpec(
                TraceEventType.STRUCTURED_FINAL, null, "PROPOSED", "task://task-001"),
            new TraceSpec(
                TraceEventType.ARTIFACT_COMMITTED,
                null,
                "SUCCEEDED",
                "artifact-version://artifact-001/1"))) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              events.size() + 1,
              event.type(),
              event.toolName(),
              event.status(),
              event.reference(),
              root);
      events.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    return AgentTraceEnvelope.create("1.0", "run-001", "task-001", events);
  }

  private static Map<String, String> components(String first, String second) {
    Map<String, String> versions = new LinkedHashMap<>();
    versions.put(first, componentVersion(first));
    versions.put(second, componentVersion(second));
    versions.putIfAbsent("agent", "agent-draft-service-v1");
    versions.putIfAbsent("verifier", "agent-draft-verifier-v1");
    versions.putIfAbsent("trace-integrity", IntegrityHashes.PROFILE);
    return versions;
  }

  private static String componentVersion(String name) {
    return switch (name) {
      case "agent" -> "agent-draft-service-v1";
      case "verifier" -> "agent-draft-verifier-v1";
      case "trace-integrity" -> IntegrityHashes.PROFILE;
      default -> throw new IllegalArgumentException("unsupported component " + name);
    };
  }

  private static HarnessRunBundle deadlineBundle(
      RunStatus status, long latencyMs, String failureReason) {
    TaskEnvelope task =
        new TaskEnvelope(
            "1.0",
            "task-deadline-policy",
            null,
            "owner-001",
            List.of(),
            "READ_ONLY_EVAL",
            "Preserve observed failure latency",
            List.of("capture://capture-001"),
            List.of(),
            List.of("text"),
            DataClass.PUBLIC,
            RiskLevel.REVERSIBLE,
            "INTERACTIVE",
            List.of("capture.read"),
            "urn:test:read-only-result",
            List.of(),
            false,
            1,
            1,
            5,
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            "policy-v1",
            "state-v1",
            "context-v1",
            "agent-tools-v1",
            null,
            List.of(),
            List.of(),
            "terminal");
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            "run-deadline-policy",
            task.id(),
            status,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "fake-model",
            "agent-v1",
            "verifier-v1",
            BigDecimal.ZERO,
            0,
            latencyMs,
            "/api/v1/agent-runs/run-deadline-policy/trace",
            failureReason);
    return HarnessRunBundle.create(
        "1.0",
        result.runId(),
        task.id(),
        null,
        result.resolvedModel(),
        "harness-v1",
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
        deadlineTrace().rootHash(),
        List.of(),
        List.of(),
        List.of(),
        null,
        failureReason,
        status,
        BigDecimal.ZERO,
        0,
        latencyMs);
  }

  private record TraceSpec(
      TraceEventType type, String toolName, String status, String reference) {}
}
