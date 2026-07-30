package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunInvariantTest {

  private static final Instant STARTED = Instant.parse("2026-07-30T00:00:00Z");
  private static final Instant COMPLETED = STARTED.plusSeconds(1);

  @Test
  void acceptsOneFullyCrossBoundTerminalAggregate() {
    Parts parts = succeededParts(task("original intent"), "artifact-version://artifact-001/1");

    assertDoesNotThrow(() -> aggregate(parts.task(), parts.result(), parts.trace(), parts.bundle()));
  }

  @Test
  void acceptsTheExistingSafePrincipalDomain() {
    TaskEnvelope task = task("original intent", "owner:local");

    assertDoesNotThrow(
        () -> AgentRun.running("run-owner-local", "owner:local", task, STARTED));
  }

  @Test
  void rejectsABundleThatHashesASecondTraceRoot() {
    Parts parts = succeededParts(task("original intent"), "artifact-version://artifact-001/1");
    HarnessRunBundle wrongRoot =
        bundle(
            parts.task(),
            parts.result(),
            "0".repeat(64),
            parts.bundle().resourceBindings(),
            parts.result().failureReason());

    assertThrows(
        IllegalArgumentException.class,
        () -> aggregate(parts.task(), parts.result(), parts.trace(), wrongRoot));
  }

  @Test
  void rejectsAnEmbeddedTaskSnapshotThatDiffersDespiteUsingTheSameId() {
    Parts parts = succeededParts(task("original intent"), "artifact-version://artifact-001/1");
    TaskEnvelope changed = task("silently changed intent");
    HarnessRunBundle changedBundle =
        bundle(
            changed,
            parts.result(),
            parts.trace().rootHash(),
            parts.bundle().resourceBindings(),
            null);

    assertThrows(
        IllegalArgumentException.class,
        () -> aggregate(parts.task(), parts.result(), parts.trace(), changedBundle));
  }

  @Test
  void rejectsAnArtifactCommitThatDoesNotMatchTheResult() {
    TaskEnvelope task = task("original intent");
    Parts resultParts = succeededParts(task, "artifact-version://artifact-001/1");
    AgentTraceEnvelope wrongTrace =
        trace("artifact-version://artifact-other/1");
    HarnessRunBundle wrongTraceBundle =
        bundle(
            task,
            resultParts.result(),
            wrongTrace.rootHash(),
            resultParts.bundle().resourceBindings(),
            null);

    assertThrows(
        IllegalArgumentException.class,
        () -> aggregate(task, resultParts.result(), wrongTrace, wrongTraceBundle));
  }

  @Test
  void rejectsANonSuccessTraceThatClaimsAnArtifactCommit() {
    TaskEnvelope task = task("original intent");
    AgentTraceEnvelope trace = trace("artifact-version://artifact-001/1");
    ResultEnvelope failed =
        new ResultEnvelope(
            "1.0",
            "run-001",
            task.id(),
            RunStatus.FAILED,
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
            1,
            "/api/v1/agent-runs/run-001/trace",
            "MODEL_STEP_FAILED");
    HarnessRunBundle failedBundle =
        bundle(task, failed, trace.rootHash(), List.of(), failed.failureReason());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentRun(
                "run-001",
                "owner-001",
                task,
                AgentRunLifecycle.FAILED,
                failed,
                trace,
                failedBundle,
                STARTED,
                COMPLETED));
  }

  @Test
  void successfulDraftWithoutAnArtifactIsRejected() {
    TaskEnvelope task = task("original intent");
    String root = IntegrityHashes.emptyTraceRoot();
    AgentTraceEntry model =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://task-001",
            root);
    root = IntegrityHashes.nextTraceRoot(root, model.eventHash());
    AgentTraceEntry structured =
        AgentTraceEntry.create(
            2,
            TraceEventType.STRUCTURED_FINAL,
            null,
            "PROPOSED",
            "task://task-001",
            root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", "run-001", task.id(), List.of(model, structured));
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            "run-001",
            task.id(),
            RunStatus.SUCCEEDED,
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
            1,
            "/api/v1/agent-runs/run-001/trace",
            null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            bundle(
                task,
                result,
                trace.rootHash(),
                List.of(),
                null));
  }

  @Test
  void preservesObservedOverDeadlineLatencyForANonSuccessAggregate() {
    TaskEnvelope task = task("record the late failure honestly");
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", "run-001", task.id(), List.of());
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            "run-001",
            task.id(),
            RunStatus.FAILED,
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
            5_001,
            "/api/v1/agent-runs/run-001/trace",
            "MODEL_PROVIDER_UNAVAILABLE");

    assertDoesNotThrow(
        () -> {
          HarnessRunBundle bundle =
              bundle(
                  task,
                  result,
                  trace.rootHash(),
                  List.of(),
                  result.failureReason());
          new AgentRun(
              "run-001",
              "owner-001",
              task,
              AgentRunLifecycle.FAILED,
              result,
              trace,
              bundle,
              STARTED,
              STARTED.plusMillis(5_001));
        });
  }

  private static AgentRun aggregate(
      TaskEnvelope task,
      ResultEnvelope result,
      AgentTraceEnvelope trace,
      HarnessRunBundle bundle) {
    return new AgentRun(
        "run-001",
        "owner-001",
        task,
        AgentRunLifecycle.SUCCEEDED,
        result,
        trace,
        bundle,
        STARTED,
        COMPLETED);
  }

  private static Parts succeededParts(TaskEnvelope task, String artifactRef) {
    AgentTraceEnvelope trace = trace(artifactRef);
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            "run-001",
            task.id(),
            RunStatus.SUCCEEDED,
            List.of(artifactRef),
            List.of("capture://capture-001"),
            List.of(),
            List.of(),
            List.of(),
            "fake-model",
            "agent-v1",
            "verifier-v1",
            BigDecimal.ZERO,
            0,
            1,
            "/api/v1/agent-runs/run-001/trace",
            null);
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE, 0, "capture://capture-001", "1".repeat(64)),
            new ResourceBinding(ResourceRole.ARTIFACT, 0, artifactRef, "2".repeat(64)));
    return new Parts(task, result, trace, bundle(task, result, trace.rootHash(), bindings, null));
  }

  private static HarnessRunBundle bundle(
      TaskEnvelope task,
      ResultEnvelope result,
      String traceRootHash,
      List<ResourceBinding> bindings,
      String failureAttribution) {
    return HarnessRunBundle.create(
        "1.0",
        "run-001",
        task.id(),
        null,
        result.resolvedModel(),
        "harness-v1",
        Map.of(
            "agent", result.agentVersion(),
            "verifier", result.verifierVersion(),
            "trace-integrity", IntegrityHashes.PROFILE),
        task.environmentSnapshotRef(),
        task.toolRegistryVersion(),
        task,
        result,
        null,
        result.traceRef(),
        traceRootHash,
        List.of(),
        List.of(),
        bindings,
        null,
        failureAttribution,
        result.status(),
        result.costUsd(),
        result.tokenCount(),
        result.latencyMs());
  }

  private static AgentTraceEnvelope trace(String artifactRef) {
    String root = IntegrityHashes.emptyTraceRoot();
    List<AgentTraceEntry> events = new java.util.ArrayList<>();
    AgentTraceEntry model =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://task-001",
            root);
    events.add(model);
    root = IntegrityHashes.nextTraceRoot(root, model.eventHash());
    AgentTraceEntry request =
        AgentTraceEntry.create(
            2,
            TraceEventType.TOOL_REQUEST,
            "capture.read",
            "REQUESTED",
            "capture://capture-001",
            root);
    events.add(request);
    root = IntegrityHashes.nextTraceRoot(root, request.eventHash());
    AgentTraceEntry toolResult =
        AgentTraceEntry.create(
            3,
            TraceEventType.TOOL_RESULT,
            "capture.read",
            "SUCCEEDED",
            "capture://capture-001",
            root);
    events.add(toolResult);
    root = IntegrityHashes.nextTraceRoot(root, toolResult.eventHash());
    AgentTraceEntry secondModel =
        AgentTraceEntry.create(
            4,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://task-001",
            root);
    events.add(secondModel);
    root = IntegrityHashes.nextTraceRoot(root, secondModel.eventHash());
    AgentTraceEntry structuredFinal =
        AgentTraceEntry.create(
            5,
            TraceEventType.STRUCTURED_FINAL,
            null,
            "PROPOSED",
            "task://task-001",
            root);
    events.add(structuredFinal);
    root = IntegrityHashes.nextTraceRoot(root, structuredFinal.eventHash());
    AgentTraceEntry committed =
        AgentTraceEntry.create(
            6,
            TraceEventType.ARTIFACT_COMMITTED,
            null,
            "SUCCEEDED",
            artifactRef,
            root);
    events.add(committed);
    return AgentTraceEnvelope.create("1.0", "run-001", "task-001", events);
  }

  private static TaskEnvelope task(String intent) {
    return task(intent, "owner-001");
  }

  private static TaskEnvelope task(String intent, String principalRef) {
    return new TaskEnvelope(
        "1.0",
        "task-001",
        null,
        principalRef,
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        intent,
        List.of("capture://capture-001"),
        List.of(),
        List.of("text"),
        DataClass.PERSONAL,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:test:artifact",
        List.of(),
        false,
        2,
        1,
        5_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "policy-v1",
        "state-v1",
        "context-v1",
        "tools-v1",
        null,
        List.of(),
        List.of(),
        "terminal");
  }

  private record Parts(
      TaskEnvelope task,
      ResultEnvelope result,
      AgentTraceEnvelope trace,
      HarnessRunBundle bundle) {}
}
