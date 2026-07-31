package io.emergeos.evalrunner;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.DeterministicReadOnlyWorkerConductorModel;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.DurableReadOnlyWorkerService;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerHandoffVerifier;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.ReadOnlyWorkerRunStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Test-only Pack008 loopback product-graph fixture.
 *
 * <p>The parent Conductor remains deterministic and has an empty physical Tool
 * registry. Only the child Kernel receives the injected model route and
 * {@code capture.read}. Keeping this fixture outside main source prevents it
 * from becoming an ungated provider entry point before Pack008 has a durable
 * one-shot permit and zero-egress preflight.
 */
final class ModelBoundWorkerEvalRunner {

  Observation run(
      Spec spec,
      AgentModel childModel,
      Pack008GraphExecutionPermit permit,
      Runnable consumeObserver,
      LongSupplier nanoTime) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(childModel, "childModel");
    Objects.requireNonNull(permit, "permit");
    Objects.requireNonNull(consumeObserver, "consumeObserver");
    Objects.requireNonNull(nanoTime, "nanoTime");

    ModelBoundReadOnlyWorkerExecutionProfile worker = spec.workerProfile();
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    Capture capture =
        new Capture(
            spec.captureId(),
            spec.principalId(),
            spec.captureNonce(),
            CaptureRequestHashes.sha256(
                spec.captureContent(),
                CaptureSourceType.TEXT,
                spec.captureSourceRef(),
                DataClass.PUBLIC),
            spec.captureContent(),
            CaptureSourceType.TEXT,
            spec.captureSourceRef(),
            DataClass.PUBLIC,
            spec.startedAt());
    GraphStores stores =
        new GraphStores(capture, worker);
    CaptureStore captures = new FrozenCaptureStore(capture);
    FrozenGraphIds ids = new FrozenGraphIds(spec);

    AgentLoopKernel childDelegate =
        new AgentLoopKernel(
            childModel,
            new AgentToolRegistry(
                worker.childToolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            worker.maxModelSteps(),
            worker.maxToolCalls(),
            nanoTime);
    AgentKernel childKernel =
        new Pack008GraphPermitBoundAgentKernel(
            permit, childDelegate, consumeObserver);
    DurableReadOnlyWorkerService durableWorker =
        new DurableReadOnlyWorkerService(
            childKernel,
            stores,
            captures,
            ids,
            spec.clock(),
            worker,
            permit.childAuthorizer());

    AgentLoopKernel parentKernel =
        new AgentLoopKernel(
            DeterministicReadOnlyWorkerConductorModel
                .inheritingParentIntent(),
            new AgentToolRegistry(
                AgentToolRegistry.EMPTY_VERSION, List.of()),
            durableWorker,
            parentProfile.maxModelSteps(),
            parentProfile.maxToolCalls(),
            nanoTime);
    AgentDraftService service =
        new AgentDraftService(
            parentKernel,
            stores,
            captures,
            ids,
            spec.clock(),
            parentProfile,
            worker,
            permit.parentAuthorizer());

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                spec.principalId(), spec.captureId(), spec.intent()));
    ids.requireConsumed(outcome.artifact() != null);
    return new Observation(
        outcome,
        stores.child(),
        stores.workerResult(),
        stores.parentStarts,
        stores.childStarts,
        stores.parentCompletions,
        stores.childCompletions,
        stores.artifactCommits);
  }

  record Spec(
      String principalId,
      String captureId,
      String captureNonce,
      String captureContent,
      String captureSourceRef,
      String intent,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId,
      Instant startedAt,
      ModelBoundReadOnlyWorkerExecutionProfile workerProfile) {

    Spec {
      requireId(principalId, "principalId");
      requireId(captureId, "captureId");
      requireId(captureNonce, "captureNonce");
      requireText(captureContent, "captureContent");
      requireText(captureSourceRef, "captureSourceRef");
      requireText(intent, "intent");
      requireId(parentRunId, "parentRunId");
      requireId(childRunId, "childRunId");
      requireId(parentTaskId, "parentTaskId");
      requireId(childTaskId, "childTaskId");
      requireId(artifactId, "artifactId");
      Objects.requireNonNull(startedAt, "startedAt");
      Objects.requireNonNull(workerProfile, "workerProfile");
      if (parentRunId.equals(childRunId)
          || parentTaskId.equals(childTaskId)) {
        throw new IllegalArgumentException(
            "Pack008 parent and child identities must differ");
      }
      if (!Pack008WorkerEvalCatalog.PRINCIPAL_ID.equals(principalId)
          || !Pack008WorkerEvalCatalog.CAPTURE_ID.equals(captureId)
          || !Pack008WorkerEvalCatalog.CLIENT_NONCE.equals(captureNonce)
          || !Pack008WorkerEvalCatalog.CONTENT.equals(captureContent)
          || !Pack008WorkerEvalCatalog.SOURCE_REF.equals(captureSourceRef)
          || !Pack008WorkerEvalCatalog.INTENT.equals(intent)
          || !Pack008WorkerEvalCatalog.PARENT_RUN_ID.equals(parentRunId)
          || !Pack008WorkerEvalCatalog.CHILD_RUN_ID.equals(childRunId)
          || !Pack008WorkerEvalCatalog.PARENT_TASK_ID.equals(parentTaskId)
          || !Pack008WorkerEvalCatalog.CHILD_TASK_ID.equals(childTaskId)
          || !Pack008WorkerEvalCatalog.ARTIFACT_ID.equals(artifactId)
          || !Pack008WorkerEvalCatalog.STARTED_AT.equals(startedAt)
          || !Pack008WorkerEvalCatalog.workerProfile().equals(workerProfile)
          || !Pack008WorkerEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH.equals(
              CaptureRequestHashes.sha256(
                  captureContent,
                  CaptureSourceType.TEXT,
                  captureSourceRef,
                  DataClass.PUBLIC))) {
        throw new IllegalArgumentException(
            "Pack008 test graph Spec does not match the frozen attempt");
      }
    }

    Clock clock() {
      return Clock.fixed(startedAt, java.time.ZoneOffset.UTC);
    }
  }

  record Observation(
      AgentDraftOutcome parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      int parentStarts,
      int childStarts,
      int parentCompletions,
      int childCompletions,
      int artifactCommits) {

    Observation {
      Objects.requireNonNull(parent, "parent");
      if (parentStarts < 0
          || childStarts < 0
          || parentCompletions < 0
          || childCompletions < 0
          || artifactCommits < 0) {
        throw new IllegalArgumentException(
            "Pack008 observation counts cannot be negative");
      }
    }
  }

  private static final class FrozenGraphIds implements IdGenerator {

    private final Map<String, Deque<String>> ids = new LinkedHashMap<>();

    private FrozenGraphIds(Spec spec) {
      ids.put(
          "run",
          new ArrayDeque<>(
              List.of(spec.parentRunId(), spec.childRunId())));
      ids.put(
          "task",
          new ArrayDeque<>(
              List.of(spec.parentTaskId(), spec.childTaskId())));
      ids.put("art", new ArrayDeque<>(List.of(spec.artifactId())));
    }

    @Override
    public String next(String prefix) {
      Deque<String> values = ids.get(prefix);
      if (values == null || values.isEmpty()) {
        throw new IllegalStateException("unexpected Pack008 ID request");
      }
      return values.removeFirst();
    }

    private void requireConsumed(boolean artifactExpected) {
      if (!ids.get("run").isEmpty()
          || !ids.get("task").isEmpty()
          || ids.get("art").size() != (artifactExpected ? 0 : 1)) {
        throw new IllegalStateException(
            "Pack008 execution did not consume the expected frozen IDs");
      }
    }
  }

  private static final class FrozenCaptureStore
      implements CaptureStore {

    private final Capture capture;

    private FrozenCaptureStore(Capture capture) {
      this.capture = Objects.requireNonNull(capture, "capture");
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException(
          "Pack008 Capture is frozen");
    }

    @Override
    public Optional<Capture> findOwned(
        String principalId, String captureId) {
      return capture.principalId().equals(principalId)
              && capture.captureId().equals(captureId)
          ? Optional.of(capture)
          : Optional.empty();
    }
  }

  private static final class GraphStores
      implements AgentRunStore, ReadOnlyWorkerRunStore {

    private final ModelBoundReadOnlyWorkerExecutionProfile profile;
    private AgentRun parent;
    private AgentRun child;
    private WorkerResultEnvelope workerResult;
    private AgentRunContext expectedWorkerParent;
    private int parentStarts;
    private int childStarts;
    private int parentCompletions;
    private int childCompletions;
    private int artifactCommits;

    private GraphStores(
        Capture capture,
        ModelBoundReadOnlyWorkerExecutionProfile profile) {
      Objects.requireNonNull(capture, "capture");
      this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public AgentRun start(AgentRun running) {
      Objects.requireNonNull(running, "running");
      if (parent != null
          || running.lifecycle() != AgentRunLifecycle.RUNNING
          || running.task().parentId() != null) {
        throw new IllegalStateException(
            "Pack008 parent start is invalid");
      }
      profile.requireParentBinding(running.task());
      parent = running;
      parentStarts++;
      return running;
    }

    @Override
    public CompletionResult complete(
        AgentRun terminal, ArtifactLineage proposedArtifact) {
      if (parent == null
          || parent.lifecycle() != AgentRunLifecycle.RUNNING
          || !parent.runId().equals(terminal.runId())
          || !parent.task().equals(terminal.task())
          || !terminal.lifecycle().terminal()) {
        throw new IllegalStateException(
            "Pack008 parent completion is invalid");
      }
      if (child != null) {
        ReadOnlyWorkerHandoffVerifier.verifyPair(
            terminal, child, workerResult, profile);
      }
      if ((proposedArtifact != null)
          != (terminal.result().status() == RunStatus.SUCCEEDED)) {
        throw new IllegalStateException(
            "Pack008 Artifact truth disagrees with parent status");
      }
      parent = terminal;
      parentCompletions++;
      if (proposedArtifact != null) {
        artifactCommits++;
      }
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public Optional<AgentRun> findOwned(
        String principalId, String runId) {
      if (parent != null
          && parent.principalId().equals(principalId)
          && parent.runId().equals(runId)) {
        return Optional.of(parent);
      }
      if (child != null
          && child.principalId().equals(principalId)
          && child.runId().equals(runId)) {
        return Optional.of(child);
      }
      return Optional.empty();
    }

    @Override
    public AgentRun startWorker(
        AgentRunContext parentContext, AgentRun running) {
      if (parent == null
          || parent.lifecycle() != AgentRunLifecycle.RUNNING
          || child != null
          || !parent.runId().equals(parentContext.runId())
          || !parent.principalId().equals(parentContext.principalId())
          || !parent.task().equals(parentContext.task())
          || running.lifecycle() != AgentRunLifecycle.RUNNING) {
        throw new IllegalStateException(
            "Pack008 child start is invalid");
      }
      profile.requireChildBinding(parent.task(), running.task());
      child = running;
      expectedWorkerParent = parentContext;
      childStarts++;
      return running;
    }

    @Override
    public WorkerCompletion completeWorker(
        AgentRunContext parentContext,
        AgentRun terminal,
        WorkerResultEnvelope proposedWorkerResult) {
      requireExactParent(parentContext);
      if (child == null
          || child.lifecycle() != AgentRunLifecycle.RUNNING
          || !child.runId().equals(terminal.runId())
          || !child.task().equals(terminal.task())
          || !terminal.lifecycle().terminal()) {
        throw new IllegalStateException(
            "Pack008 child completion is invalid");
      }
      ReadOnlyWorkerHandoffVerifier.verifyChild(
          parent.task(), terminal, proposedWorkerResult, profile);
      child = terminal;
      workerResult = proposedWorkerResult;
      childCompletions++;
      return new WorkerCompletion(child, workerResult);
    }

    @Override
    public Optional<WorkerCompletion> findWorkerOwned(
        AgentRunContext parentContext, String childRunId) {
      requireExactParent(parentContext);
      if (child == null
          || !child.lifecycle().terminal()
          || !child.runId().equals(childRunId)) {
        return Optional.empty();
      }
      ReadOnlyWorkerHandoffVerifier.verifyChild(
          parent.task(), child, workerResult, profile);
      return Optional.of(new WorkerCompletion(child, workerResult));
    }

    private void requireExactParent(AgentRunContext parentContext) {
      if (expectedWorkerParent == null
          || !expectedWorkerParent.equals(parentContext)
          || parent == null
          || parent.lifecycle() != AgentRunLifecycle.RUNNING
          || !parent.runId().equals(parentContext.runId())
          || !parent.task().equals(parentContext.task())) {
        throw new IllegalStateException(
            "Pack008 Worker parent identity drifted");
      }
    }

    private AgentRun child() {
      return child;
    }

    private WorkerResultEnvelope workerResult() {
      return workerResult;
    }
  }

  private static void requireId(String value, String name) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is blank");
    }
  }
}
