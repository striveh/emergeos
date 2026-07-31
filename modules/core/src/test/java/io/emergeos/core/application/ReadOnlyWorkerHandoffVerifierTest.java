package io.emergeos.core.application;

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
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadOnlyWorkerHandoffVerifierTest {

  private static final String OWNER = "worker-pair-owner";
  private static final String PARENT_TASK_ID = "worker-pair-parent-task";
  private static final String PARENT_RUN_ID = "worker-pair-parent-run";
  private static final String CHILD_TASK_ID = "worker-pair-child-task";
  private static final String CHILD_RUN_ID = "worker-pair-child-run";
  private static final String CAPTURE_REF = "capture://worker-pair-capture";
  private static final String ARTIFACT_REF =
      "artifact-version://worker-pair-artifact/1";
  private static final String CONTENT =
      "Prompt 不是咒语，而是在构造概率程序的运行时状态。";
  private static final Instant STARTED = Instant.parse("2026-07-31T00:00:00Z");

  @Test
  void verifiesTheCompleteParentChildWorkerResultAndArtifactHashChain() {
    Fixture fixture = fixture();

    assertDoesNotThrow(
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                fixture.parent(),
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void rejectsAChildWhoseRehashedBundleDriftsFromTheWorkerHarnessVersion() {
    Fixture fixture = fixture();
    HarnessRunBundle original = fixture.child().bundle();
    HarnessRunBundle drifted =
        HarnessRunBundle.create(
            original.schemaVersion(),
            original.runId(),
            original.taskId(),
            original.experiment(),
            original.modelResolved(),
            "drifted-worker-harness-v2",
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
            original.resourceBindings(),
            original.verificationRef(),
            original.failureAttribution(),
            original.outcome(),
            original.costUsd(),
            original.tokenCount(),
            original.latencyMs());
    AgentRun driftedChild =
        new AgentRun(
            fixture.child().runId(),
            fixture.child().principalId(),
            fixture.child().task(),
            fixture.child().lifecycle(),
            fixture.child().result(),
            fixture.child().trace(),
            drifted,
            fixture.child().startedAt(),
            fixture.child().completedAt());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyChild(
                fixture.parent().task(),
                driftedChild,
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void rejectsALocallyValidParentThatBindsTheWrongChildBundleHash() {
    Fixture fixture = fixture();
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.workerResult(),
            "0".repeat(64),
            fixture.workerResult().contentHash(),
            fixture.parent().bundle().componentVersions());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void rejectsALocallyValidParentWhoseArtifactHashDiffersFromTheWorkerOutput() {
    Fixture fixture = fixture();
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.workerResult(),
            fixture.child().bundle().integrityHash(),
            "f".repeat(64),
            fixture.parent().bundle().componentVersions());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void rejectsASecondValidWorkerResultThatIsNotBoundByTheChild() {
    Fixture fixture = fixture();
    WorkerResultEnvelope different =
        WorkerResultEnvelope.create(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            ReadOnlyWorkerExecutionProfile.OUTPUT_SCHEMA,
            "另一份未被 child terminal truth 绑定的内容。",
            List.of(CAPTURE_REF));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                fixture.parent(),
                fixture.child(),
                different,
                fixture.profile()));
  }

  @Test
  void terminalAggregateRejectsAnExtraParentCapability() {
    Fixture fixture = fixture();
    TaskEnvelope expanded =
        copyTaskWithCapabilities(
            fixture.parent().task(),
            List.of(
                AgentExecutionProfile.READ_ONLY_WORKER_CAPABILITY,
                "capability://unexpected-write-authority"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            parentRun(
                expanded,
                fixture.child(),
                fixture.workerResult(),
                fixture.child().bundle().integrityHash(),
                fixture.workerResult().contentHash(),
                fixture.parent().bundle().componentVersions()));
  }

  @Test
  void reportsMissingWorkerComponentIdentityAsAContractViolation() {
    Fixture fixture = fixture();
    Map<String, String> components =
        new LinkedHashMap<>(fixture.parent().bundle().componentVersions());
    components.remove("worker-registry");
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.workerResult(),
            fixture.child().bundle().integrityHash(),
            fixture.workerResult().contentHash(),
            Map.copyOf(components));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void freshReadMustMatchTheExactPlannedChildIdentityAndTask() {
    Fixture fixture = fixture();

    assertDoesNotThrow(
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPlannedChild(
                fixture.parent().task(),
                fixture.child().runId(),
                fixture.child().task(),
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPlannedChild(
                fixture.parent().task(),
                "another-child-run",
                fixture.child().task(),
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPlannedChild(
                fixture.parent().task(),
                fixture.child().runId(),
                copyTaskWithIntent(
                    fixture.child().task(),
                    "另一个同样 profile-valid、但并非本次规划的 child intent"),
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void parentTraceMustNameTheSameChildAsItsHandoffBinding() {
    Fixture fixture = fixture();
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.workerResult(),
            fixture.child().bundle().integrityHash(),
            fixture.workerResult().contentHash(),
            fixture.parent().bundle().componentVersions(),
            "agent-run://another-locally-valid-child");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void parentAggregateTokenUsageCannotBeLowerThanItsChild() {
    Fixture fixture = fixture();
    AgentRun child =
        childRun(
            fixture.child().task(),
            fixture.workerResult(),
            fixture.profile(),
            1);
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            child,
            fixture.workerResult(),
            child.bundle().integrityHash(),
            fixture.workerResult().contentHash(),
            fixture.parent().bundle().componentVersions());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                child,
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void modelBoundParentCostMustExactlyMatchItsOnlyChild() {
    ModelBoundFixture fixture = modelBoundFixture();
    AgentRun inflated =
        withUsage(
            fixture.parent(),
            fixture
                .child()
                .result()
                .costUsd()
                .add(new BigDecimal("0.000001")),
            fixture.child().result().tokenCount());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                inflated,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void modelBoundParentTokensMustExactlyMatchItsOnlyChild() {
    ModelBoundFixture fixture = modelBoundFixture();
    AgentRun inflated =
        withUsage(
            fixture.parent(),
            fixture.child().result().costUsd(),
            fixture.child().result().tokenCount() + 1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                inflated,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void childSpecificRejectionStatusMustMatchTheActualChild() {
    Fixture fixture = fixture();
    AgentRun parent =
        rejectedParentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.parent().bundle().componentVersions());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void boundRejectionsWithoutDurableChildObservationAreRejected() {
    Fixture fixture = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rejectedParentRun(
                fixture.parent().task(),
                fixture.child(),
                fixture.parent().bundle().componentVersions(),
                RunStatus.BLOCKED,
                "HANDOFF_NOT_ALLOWED",
                "BLOCKED",
                BigDecimal.ZERO,
                1),
        "a pre-request rejection cannot be forged as a child-bound post-request event");
    List<RejectionSpec> impossibleBindings =
        List.of(
            new RejectionSpec(
                "FAILED", RunStatus.FAILED, "HANDOFF_DISPATCH_FAILED"),
            new RejectionSpec(
                "MALFORMED_RESULT",
                RunStatus.FAILED,
                "UNSAFE_HANDOFF_OUTCOME"),
            new RejectionSpec(
                "DEADLINE_EXHAUSTED",
                RunStatus.FAILED,
                "HANDOFF_DEADLINE_EXHAUSTED"),
            new RejectionSpec(
                "CANCELLED_UNOBSERVED",
                RunStatus.CANCELLED,
                "CANCELLED"),
            new RejectionSpec(
                "DEADLINE_EXCEEDED_UNOBSERVED",
                RunStatus.FAILED,
                io.emergeos.contracts.ObservedExecutionLimits
                    .POST_HANDOFF_DEADLINE_FAILURE));

    for (RejectionSpec rejection : impossibleBindings) {
      AgentRun parent =
          rejectedParentRun(
              fixture.parent().task(),
              fixture.child(),
              fixture.parent().bundle().componentVersions(),
              rejection.parentStatus(),
              rejection.parentFailure(),
              rejection.traceStatus(),
              BigDecimal.ZERO,
              rejection.traceStatus().startsWith("DEADLINE_EXCEEDED")
                  ? fixture.parent().task().deadlineMs() + 1
                  : 1);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              ReadOnlyWorkerHandoffVerifier.verifyPair(
                  parent,
                  fixture.child(),
                  fixture.workerResult(),
                  fixture.profile()),
          rejection.traceStatus());
    }
  }

  @Test
  void protocolRejectsChildSpecificRejectionWithForgedParentTruth() {
    Fixture fixture = fixture();
    AgentRun blockedChild =
        nonSuccessChildRun(
            fixture.child().task(),
            RunStatus.BLOCKED,
            "SYNTHETIC_CHILD_BLOCKED",
            fixture.profile());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            rejectedParentRun(
                fixture.parent().task(),
                blockedChild,
                fixture.parent().bundle().componentVersions(),
                RunStatus.FAILED,
                "HANDOFF_CHILD_FAILED",
                "CHILD_BLOCKED",
                BigDecimal.ZERO,
                1));
  }

  @Test
  void everyChildTerminalStatusRequiresItsExactParentMapping() {
    Fixture fixture = fixture();
    List<ChildRejectionSpec> cases =
        List.of(
            new ChildRejectionSpec(
                RunStatus.FAILED,
                "CHILD_FAILED",
                RunStatus.FAILED,
                "HANDOFF_CHILD_FAILED"),
            new ChildRejectionSpec(
                RunStatus.BLOCKED,
                "CHILD_BLOCKED",
                RunStatus.BLOCKED,
                "HANDOFF_CHILD_BLOCKED"),
            new ChildRejectionSpec(
                RunStatus.NEEDS_INPUT,
                "CHILD_NEEDS_INPUT",
                RunStatus.NEEDS_INPUT,
                "HANDOFF_CHILD_NEEDS_INPUT"),
            new ChildRejectionSpec(
                RunStatus.CANCELLED,
                "CHILD_CANCELLED",
                RunStatus.CANCELLED,
                "HANDOFF_CHILD_CANCELLED"));

    for (ChildRejectionSpec rejection : cases) {
      AgentRun child =
          nonSuccessChildRun(
              fixture.child().task(),
              rejection.childStatus(),
              "SYNTHETIC_CHILD_TERMINAL",
              fixture.profile());
      AgentRun parent =
          rejectedParentRun(
              fixture.parent().task(),
              child,
              fixture.parent().bundle().componentVersions(),
              rejection.parentStatus(),
              rejection.parentFailure(),
              rejection.traceStatus(),
              BigDecimal.ZERO,
              1);

      assertDoesNotThrow(
          () ->
              ReadOnlyWorkerHandoffVerifier.verifyPair(
                  parent, child, null, fixture.profile()),
          rejection.traceStatus());
    }
  }

  @Test
  void verifiedChildSupportsExactDeadlineBudgetAndPostResultCancellationTruth() {
    Fixture fixture = fixture();
    AgentRun deadline =
        rejectedParentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.parent().bundle().componentVersions(),
            RunStatus.FAILED,
            io.emergeos.contracts.ObservedExecutionLimits
                .POST_HANDOFF_DEADLINE_FAILURE,
            "DEADLINE_EXCEEDED_AFTER_CHILD",
            BigDecimal.ZERO,
            fixture.parent().task().deadlineMs() + 1);
    AgentRun budget =
        rejectedParentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.parent().bundle().componentVersions(),
            RunStatus.BLOCKED,
            "HANDOFF_BUDGET_EXHAUSTED",
            "LIMIT_EXHAUSTED",
            fixture
                .parent()
                .task()
                .budgetUsd()
                .add(new BigDecimal("0.000001")),
            1);
    AgentRun cancelled =
        cancelledParentAfterAcceptedChild(
            fixture.parent().task(),
            fixture.child(),
            fixture.parent().bundle().componentVersions());

    assertDoesNotThrow(
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                deadline,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
    assertDoesNotThrow(
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                budget,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
    assertDoesNotThrow(
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                cancelled,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  @Test
  void budgetRejectionRequiresAnActualAggregateOverage() {
    Fixture fixture = fixture();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            rejectedParentRun(
                fixture.parent().task(),
                fixture.child(),
                fixture.parent().bundle().componentVersions(),
                RunStatus.BLOCKED,
                "HANDOFF_BUDGET_EXHAUSTED",
                "LIMIT_EXHAUSTED",
                fixture.parent().task().budgetUsd(),
                1));
  }

  @Test
  void acceptedParentAndChildEvidenceBindingsMustMatchExactly() {
    Fixture fixture = fixture();
    AgentRun parent =
        parentRun(
            fixture.parent().task(),
            fixture.child(),
            fixture.workerResult(),
            fixture.child().bundle().integrityHash(),
            fixture.workerResult().contentHash(),
            fixture.parent().bundle().componentVersions(),
            "agent-run://" + CHILD_RUN_ID,
            "2".repeat(64));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReadOnlyWorkerHandoffVerifier.verifyPair(
                parent,
                fixture.child(),
                fixture.workerResult(),
                fixture.profile()));
  }

  private static Fixture fixture() {
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerFakeV1();
    ReadOnlyWorkerExecutionProfile workerProfile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            PARENT_TASK_ID,
            OWNER,
            "把 Capture 整理成可发布短文",
            CAPTURE_REF,
            DataClass.PUBLIC);
    TaskEnvelope childTask =
        workerProfile.newChildTask(
            parentTask,
            new WorkerHandoffRequest(
                workerProfile.workerName(),
                "只读生成 proposal",
                List.of(CAPTURE_REF)),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                parentTask.budgetUsd(),
                CancellationSignal.never()),
            CHILD_TASK_ID);
    WorkerResultEnvelope workerResult =
        WorkerResultEnvelope.create(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            childTask.outputSchema(),
            CONTENT,
            List.of(CAPTURE_REF));
    AgentRun child = childRun(childTask, workerResult, workerProfile);
    AgentRun parent =
        parentRun(
            parentTask,
            child,
            workerResult,
            child.bundle().integrityHash(),
            workerResult.contentHash(),
            parentProfile.componentVersions());
    return new Fixture(parent, child, workerResult, workerProfile);
  }

  private static ModelBoundFixture modelBoundFixture() {
    ModelBoundReadOnlyWorkerExecutionProfile profile =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            new PricingProfile(
                "pack008-verifier-openai-v1",
                "openai.responses",
                "gpt-5.6",
                5_000,
                500,
                30_000),
            "openai-responses-v1-openai-java-4.43.0",
            "f".repeat(64),
            "environment://sha256:" + "e".repeat(64),
            new io.emergeos.contracts.HarnessExperiment(
                "openai-worker-h0", 1),
            1_000,
            200,
            new BigDecimal("0.022000"));
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(profile);
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            PARENT_TASK_ID,
            OWNER,
            "把公开 Capture 整理成短文",
            CAPTURE_REF,
            DataClass.PUBLIC);
    TaskEnvelope childTask =
        profile.newChildTask(
            parentTask,
            new WorkerHandoffRequest(
                profile.workerName(),
                parentTask.intent(),
                parentTask.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                parentTask.budgetUsd(),
                CancellationSignal.never()),
            CHILD_TASK_ID);
    WorkerResultEnvelope workerResult =
        WorkerResultEnvelope.create(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            childTask.outputSchema(),
            CONTENT,
            List.of(CAPTURE_REF));
    String childTraceRef =
        "/api/v1/agent-runs/" + CHILD_RUN_ID + "/trace";
    AgentTraceEnvelope childTrace =
        trace(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + CHILD_TASK_ID),
                new TraceSpec(
                    TraceEventType.TOOL_REQUEST,
                    "capture.read",
                    "REQUESTED",
                    CAPTURE_REF),
                new TraceSpec(
                    TraceEventType.TOOL_RESULT,
                    "capture.read",
                    "SUCCEEDED",
                    CAPTURE_REF),
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + CHILD_TASK_ID),
                new TraceSpec(
                    TraceEventType.STRUCTURED_FINAL,
                    null,
                    "PROPOSED",
                    "proposal://sha256:"
                        + workerResult.contentHash())));
    BigDecimal childCost = new BigDecimal("0.001000");
    long childTokens = 10;
    ResultEnvelope childResult =
        new ResultEnvelope(
            "1.0",
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            RunStatus.SUCCEEDED,
            List.of(),
            List.of(CAPTURE_REF),
            List.of(),
            List.of(),
            List.of(),
            "gpt-5.6-2026-07-15",
            profile.agentVersion(),
            profile.verifierVersion(),
            childCost,
            childTokens,
            1,
            childTraceRef,
            null);
    HarnessRunBundle childBundle =
        HarnessRunBundle.create(
            childTask.schemaVersion(),
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            profile.experiment(),
            childResult.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            childTask.environmentSnapshotRef(),
            childTask.toolRegistryVersion(),
            childTask,
            childResult,
            null,
            childTraceRef,
            childTrace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    CAPTURE_REF,
                    "1".repeat(64)),
                new ResourceBinding(
                    ResourceRole.WORKER_RESULT,
                    0,
                    workerResult.workerResultRef(),
                    workerResult.integrityHash())),
            null,
            null,
            RunStatus.SUCCEEDED,
            childCost,
            childTokens,
            1);
    AgentRun child =
        new AgentRun(
            CHILD_RUN_ID,
            OWNER,
            childTask,
            AgentRunLifecycle.SUCCEEDED,
            childResult,
            childTrace,
            childBundle,
            STARTED,
            STARTED.plusMillis(1));

    String parentTraceRef =
        "/api/v1/agent-runs/" + PARENT_RUN_ID + "/trace";
    String childRef = "agent-run://" + CHILD_RUN_ID;
    AgentTraceEnvelope parentTrace =
        trace(
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    null,
                    "REQUESTED",
                    childRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_RESULT,
                    null,
                    "SUCCEEDED",
                    childRef),
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.STRUCTURED_FINAL,
                    null,
                    "PROPOSED",
                    "proposal://sha256:"
                        + workerResult.contentHash()),
                new TraceSpec(
                    TraceEventType.ARTIFACT_COMMITTED,
                    null,
                    "SUCCEEDED",
                    ARTIFACT_REF)));
    ResultEnvelope parentResult =
        new ResultEnvelope(
            "1.0",
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            RunStatus.SUCCEEDED,
            List.of(ARTIFACT_REF),
            List.of(CAPTURE_REF),
            List.of(),
            List.of(),
            List.of(),
            "fake-pack008-conductor-v1",
            parentProfile.agentVersion(),
            parentProfile.verifierVersion(),
            childCost,
            childTokens,
            1,
            parentTraceRef,
            null);
    HarnessRunBundle parentBundle =
        HarnessRunBundle.create(
            parentTask.schemaVersion(),
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            profile.experiment(),
            parentResult.resolvedModel(),
            parentProfile.harnessVersion(),
            parentProfile.componentVersions(profile),
            parentTask.environmentSnapshotRef(),
            parentTask.toolRegistryVersion(),
            parentTask,
            parentResult,
            null,
            parentTraceRef,
            parentTrace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    CAPTURE_REF,
                    "1".repeat(64)),
                new ResourceBinding(
                    ResourceRole.ARTIFACT,
                    0,
                    ARTIFACT_REF,
                    workerResult.contentHash()),
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    childBundle.integrityHash())),
            null,
            null,
            RunStatus.SUCCEEDED,
            childCost,
            childTokens,
            1);
    AgentRun parent =
        new AgentRun(
            PARENT_RUN_ID,
            OWNER,
            parentTask,
            AgentRunLifecycle.SUCCEEDED,
            parentResult,
            parentTrace,
            parentBundle,
            STARTED,
            STARTED.plusMillis(2));
    return new ModelBoundFixture(
        parent, child, workerResult, profile);
  }

  private static AgentRun withUsage(
      AgentRun run, BigDecimal costUsd, long tokenCount) {
    ResultEnvelope originalResult = run.result();
    ResultEnvelope changedResult =
        new ResultEnvelope(
            originalResult.schemaVersion(),
            originalResult.runId(),
            originalResult.taskId(),
            originalResult.status(),
            originalResult.artifactRefs(),
            originalResult.evidenceRefs(),
            originalResult.claims(),
            originalResult.uncertainty(),
            originalResult.receiptRefs(),
            originalResult.resolvedModel(),
            originalResult.agentVersion(),
            originalResult.verifierVersion(),
            costUsd,
            tokenCount,
            originalResult.latencyMs(),
            originalResult.traceRef(),
            originalResult.failureReason());
    HarnessRunBundle originalBundle = run.bundle();
    HarnessRunBundle changedBundle =
        HarnessRunBundle.create(
            originalBundle.schemaVersion(),
            originalBundle.runId(),
            originalBundle.taskId(),
            originalBundle.experiment(),
            originalBundle.modelResolved(),
            originalBundle.harnessVersion(),
            originalBundle.componentVersions(),
            originalBundle.environmentSnapshotRef(),
            originalBundle.toolRegistryVersion(),
            originalBundle.task(),
            changedResult,
            originalBundle.workingSelfRef(),
            originalBundle.traceRef(),
            originalBundle.traceRootHash(),
            originalBundle.handoffRefs(),
            originalBundle.checkpointRefs(),
            originalBundle.resourceBindings(),
            originalBundle.verificationRef(),
            originalBundle.failureAttribution(),
            originalBundle.outcome(),
            costUsd,
            tokenCount,
            originalBundle.latencyMs());
    return new AgentRun(
        run.runId(),
        run.principalId(),
        run.task(),
        run.lifecycle(),
        changedResult,
        run.trace(),
        changedBundle,
        run.startedAt(),
        run.completedAt());
  }

  private static AgentRun childRun(
      TaskEnvelope task,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile) {
    return childRun(task, workerResult, profile, 0);
  }

  private static AgentRun childRun(
      TaskEnvelope task,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile,
      long tokenCount) {
    String traceRef = "/api/v1/agent-runs/" + CHILD_RUN_ID + "/trace";
    AgentTraceEnvelope trace =
        trace(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + CHILD_TASK_ID),
                new TraceSpec(
                    TraceEventType.TOOL_REQUEST,
                    "capture.read",
                    "REQUESTED",
                    CAPTURE_REF),
                new TraceSpec(
                    TraceEventType.TOOL_RESULT,
                    "capture.read",
                    "SUCCEEDED",
                    CAPTURE_REF),
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + CHILD_TASK_ID),
                new TraceSpec(
                    TraceEventType.STRUCTURED_FINAL,
                    null,
                    "PROPOSED",
                    "proposal://sha256:" + workerResult.contentHash())));
    ResultEnvelope result =
        result(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            RunStatus.SUCCEEDED,
            List.of(),
            List.of(CAPTURE_REF),
            "fake-worker-model-v1",
            profile.agentVersion(),
            profile.verifierVersion(),
            traceRef,
            tokenCount);
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE,
                0,
                CAPTURE_REF,
                "1".repeat(64)),
            new ResourceBinding(
                ResourceRole.WORKER_RESULT,
                0,
                workerResult.workerResultRef(),
                workerResult.integrityHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            null,
            result.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            bindings,
            null,
            null,
            RunStatus.SUCCEEDED,
            BigDecimal.ZERO,
            tokenCount,
            1);
    return new AgentRun(
        CHILD_RUN_ID,
        OWNER,
        task,
        AgentRunLifecycle.SUCCEEDED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(1));
  }

  private static AgentRun nonSuccessChildRun(
      TaskEnvelope task,
      RunStatus status,
      String failureReason,
      ReadOnlyWorkerExecutionProfile profile) {
    String traceRef = "/api/v1/agent-runs/" + CHILD_RUN_ID + "/trace";
    AgentTraceEnvelope trace =
        trace(
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "FAILED",
                    "task://" + CHILD_TASK_ID)));
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            status,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "fake-worker-model-v1",
            profile.agentVersion(),
            profile.verifierVersion(),
            BigDecimal.ZERO,
            0,
            1,
            traceRef,
            failureReason);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            CHILD_RUN_ID,
            CHILD_TASK_ID,
            null,
            result.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            failureReason,
            status,
            BigDecimal.ZERO,
            0,
            1);
    return new AgentRun(
        CHILD_RUN_ID,
        OWNER,
        task,
        AgentRunLifecycle.terminal(status),
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(1));
  }

  private static AgentRun parentRun(
      TaskEnvelope task,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      String handoffHash,
      String artifactHash,
      Map<String, String> componentVersions) {
    return parentRun(
        task,
        child,
        workerResult,
        handoffHash,
        artifactHash,
        componentVersions,
        "agent-run://" + CHILD_RUN_ID,
        "1".repeat(64));
  }

  private static AgentRun parentRun(
      TaskEnvelope task,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      String handoffHash,
      String artifactHash,
      Map<String, String> componentVersions,
      String traceChildRef) {
    return parentRun(
        task,
        child,
        workerResult,
        handoffHash,
        artifactHash,
        componentVersions,
        traceChildRef,
        "1".repeat(64));
  }

  private static AgentRun parentRun(
      TaskEnvelope task,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      String handoffHash,
      String artifactHash,
      Map<String, String> componentVersions,
      String traceChildRef,
      String evidenceHash) {
    String traceRef = "/api/v1/agent-runs/" + PARENT_RUN_ID + "/trace";
    AgentTraceEnvelope trace =
        trace(
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    null,
                    "REQUESTED",
                    traceChildRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_RESULT,
                    null,
                    "SUCCEEDED",
                    traceChildRef),
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.STRUCTURED_FINAL,
                    null,
                    "PROPOSED",
                    "proposal://sha256:" + workerResult.contentHash()),
                new TraceSpec(
                    TraceEventType.ARTIFACT_COMMITTED,
                    null,
                    "SUCCEEDED",
                    ARTIFACT_REF)));
    ResultEnvelope result =
        result(
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            RunStatus.SUCCEEDED,
            List.of(ARTIFACT_REF),
            List.of(CAPTURE_REF),
            "fake-model-v1",
            componentVersions.get("agent"),
            componentVersions.get("verifier"),
            traceRef,
            0);
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE,
                0,
                CAPTURE_REF,
                evidenceHash),
            new ResourceBinding(
                ResourceRole.ARTIFACT,
                0,
                ARTIFACT_REF,
                artifactHash),
            new ResourceBinding(
                ResourceRole.HANDOFF,
                0,
                "agent-run://" + child.runId(),
                handoffHash));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            componentVersions,
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of("agent-run://" + child.runId()),
            List.of(),
            bindings,
            null,
            null,
            RunStatus.SUCCEEDED,
            BigDecimal.ZERO,
            0,
            1);
    return new AgentRun(
        PARENT_RUN_ID,
        OWNER,
        task,
        AgentRunLifecycle.SUCCEEDED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(2));
  }

  private static AgentRun rejectedParentRun(
      TaskEnvelope task,
      AgentRun child,
      Map<String, String> componentVersions) {
    return rejectedParentRun(
        task,
        child,
        componentVersions,
        RunStatus.FAILED,
        "HANDOFF_CHILD_FAILED",
        "CHILD_FAILED",
        BigDecimal.ZERO,
        1);
  }

  private static AgentRun rejectedParentRun(
      TaskEnvelope task,
      AgentRun child,
      Map<String, String> componentVersions,
      RunStatus parentStatus,
      String parentFailure,
      String traceStatus,
      BigDecimal costUsd,
      long latencyMs) {
    String traceRef = "/api/v1/agent-runs/" + PARENT_RUN_ID + "/trace";
    String childRef = "agent-run://" + child.runId();
    AgentTraceEnvelope trace =
        trace(
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    null,
                    "REQUESTED",
                    childRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_REJECTED,
                    null,
                    traceStatus,
                    childRef)));
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            parentStatus,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "fake-model-v1",
            componentVersions.get("agent"),
            componentVersions.get("verifier"),
            costUsd,
            0,
            latencyMs,
            traceRef,
            parentFailure);
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.HANDOFF,
                0,
                childRef,
                child.bundle().integrityHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            componentVersions,
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            bindings,
            null,
        result.failureReason(),
        result.status(),
        result.costUsd(),
        result.tokenCount(),
        result.latencyMs());
    return new AgentRun(
        PARENT_RUN_ID,
        OWNER,
        task,
        AgentRunLifecycle.terminal(parentStatus),
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(2));
  }

  private static AgentRun cancelledParentAfterAcceptedChild(
      TaskEnvelope task,
      AgentRun child,
      Map<String, String> componentVersions) {
    String traceRef =
        "/api/v1/agent-runs/" + PARENT_RUN_ID + "/trace";
    String childRef = "agent-run://" + child.runId();
    AgentTraceEnvelope trace =
        trace(
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + PARENT_TASK_ID),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    null,
                    "REQUESTED",
                    childRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_RESULT,
                    null,
                    "SUCCEEDED",
                    childRef)));
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            RunStatus.CANCELLED,
            List.of(),
            List.of(CAPTURE_REF),
            List.of(),
            List.of(),
            List.of(),
            "fake-model-v1",
            componentVersions.get("agent"),
            componentVersions.get("verifier"),
            BigDecimal.ZERO,
            0,
            1,
            traceRef,
            "CANCELLED");
    String evidenceHash =
        child.bundle().resourceBindings().stream()
            .filter(
                binding -> binding.role() == ResourceRole.EVIDENCE)
            .findFirst()
            .orElseThrow()
            .contentHash();
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE,
                0,
                CAPTURE_REF,
                evidenceHash),
            new ResourceBinding(
                ResourceRole.HANDOFF,
                0,
                childRef,
                child.bundle().integrityHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            PARENT_RUN_ID,
            PARENT_TASK_ID,
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            componentVersions,
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            bindings,
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    return new AgentRun(
        PARENT_RUN_ID,
        OWNER,
        task,
        AgentRunLifecycle.CANCELLED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(2));
  }

  private static ResultEnvelope result(
      String runId,
      String taskId,
      RunStatus status,
      List<String> artifactRefs,
      List<String> evidenceRefs,
      String resolvedModel,
      String agentVersion,
      String verifierVersion,
      String traceRef) {
    return result(
        runId,
        taskId,
        status,
        artifactRefs,
        evidenceRefs,
        resolvedModel,
        agentVersion,
        verifierVersion,
        traceRef,
        0);
  }

  private static ResultEnvelope result(
      String runId,
      String taskId,
      RunStatus status,
      List<String> artifactRefs,
      List<String> evidenceRefs,
      String resolvedModel,
      String agentVersion,
      String verifierVersion,
      String traceRef,
      long tokenCount) {
    return new ResultEnvelope(
        "1.0",
        runId,
        taskId,
        status,
        artifactRefs,
        evidenceRefs,
        List.of(),
        List.of(),
        List.of(),
        resolvedModel,
        agentVersion,
        verifierVersion,
        BigDecimal.ZERO,
        tokenCount,
        1,
        traceRef,
        null);
  }

  private static AgentTraceEnvelope trace(
      String runId,
      String taskId,
      List<TraceSpec> specs) {
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    for (TraceSpec spec : specs) {
      AgentTraceEntry event =
          AgentTraceEntry.create(
              events.size() + 1,
              spec.type(),
              spec.toolName(),
              spec.status(),
              spec.reference(),
              root);
      events.add(event);
      root = IntegrityHashes.nextTraceRoot(root, event.eventHash());
    }
    return AgentTraceEnvelope.create("1.0", runId, taskId, events);
  }

  private static TaskEnvelope copyTaskWithCapabilities(
      TaskEnvelope task,
      List<String> capabilityRefs) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        task.principalRef(),
        task.delegationChain(),
        task.kind(),
        task.intent(),
        task.inputRefs(),
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        task.requiredTools(),
        task.outputSchema(),
        task.acceptanceChecks(),
        task.allowParallel(),
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        task.contextPolicyVersion(),
        task.toolRegistryVersion(),
        task.environmentSnapshotRef(),
        capabilityRefs,
        task.unresolvedDecisions(),
        task.returnControlWhen());
  }

  private static TaskEnvelope copyTaskWithIntent(
      TaskEnvelope task,
      String intent) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        task.principalRef(),
        task.delegationChain(),
        task.kind(),
        intent,
        task.inputRefs(),
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        task.requiredTools(),
        task.outputSchema(),
        task.acceptanceChecks(),
        task.allowParallel(),
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        task.contextPolicyVersion(),
        task.toolRegistryVersion(),
        task.environmentSnapshotRef(),
        task.capabilityRefs(),
        task.unresolvedDecisions(),
        task.returnControlWhen());
  }

  private record Fixture(
      AgentRun parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile) {}

  private record ModelBoundFixture(
      AgentRun parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ModelBoundReadOnlyWorkerExecutionProfile profile) {}

  private record RejectionSpec(
      String traceStatus,
      RunStatus parentStatus,
      String parentFailure) {}

  private record ChildRejectionSpec(
      RunStatus childStatus,
      String traceStatus,
      RunStatus parentStatus,
      String parentFailure) {}

  private record TraceSpec(
      TraceEventType type,
      String toolName,
      String status,
      String reference) {}
}
