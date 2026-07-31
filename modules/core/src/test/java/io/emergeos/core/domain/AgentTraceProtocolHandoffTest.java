package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.port.AgentWorkerRuntime;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTraceProtocolHandoffTest {

  private static final String TASK_ID = "task-handoff-protocol";
  private static final String CHILD_REF = "agent-run://child-handoff-protocol";
  private static final String CAPTURE_REF = "capture://capture-handoff-protocol";

  @Test
  void genericCancellationDoesNotPretendThatAHandoffWasRejected() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                legacyTask(),
                outcome(
                    RunStatus.CANCELLED,
                    "CANCELLED",
                    List.of(),
                    List.of())));
  }

  @Test
  void cancellationAfterAnAcceptedChildDoesNotRewriteItAsRejected() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.CANCELLED,
                    "CANCELLED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REQUEST,
                            "REQUESTED",
                            CHILD_REF),
                        event(
                            3,
                            AgentTraceEventType.HANDOFF_RESULT,
                            "SUCCEEDED",
                            CHILD_REF)),
                    List.of(CAPTURE_REF))));
  }

  @Test
  void cancellationBetweenPrepareAndDispatchMustCarryTheRejectedHandoff() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.CANCELLED,
                    "CANCELLED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REQUEST,
                            "REQUESTED",
                            CHILD_REF),
                        event(
                            3,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "CANCELLED_UNOBSERVED",
                            CHILD_REF)),
                    List.of())));
  }

  @Test
  void ambiguousLegacyHandoffCancellationStatusIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.CANCELLED,
                    "CANCELLED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REQUEST,
                            "REQUESTED",
                            CHILD_REF),
                        event(
                            3,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "CANCELLED",
                            CHILD_REF)),
                    List.of())));
  }

  @Test
  void handoffFailureReasonWithoutARejectedTraceIsInvalid() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "HANDOFF_DISPATCH_FAILED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REQUEST,
                            "REQUESTED",
                            CHILD_REF),
                        event(
                            3,
                            AgentTraceEventType.HANDOFF_RESULT,
                            "SUCCEEDED",
                            CHILD_REF)),
                    List.of(CAPTURE_REF))));
  }

  @Test
  void acceptedHandoffCannotHideAParentModelFailureWithoutATerminalModelEvent() {
    for (String failureReason :
        List.of("MODEL_STEP_FAILED", "AGENT_KERNEL_FAILED")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              AgentTraceProtocol.verifyKernelOutcome(
                  workerTask(),
                  outcome(
                      RunStatus.FAILED,
                      failureReason,
                      acceptedHandoffTrace(),
                      List.of(CAPTURE_REF))));
    }
  }

  @Test
  void acceptedHandoffImplicitDeadlineMustReachTheDeclaredBoundary() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "DEADLINE_EXHAUSTED",
                    acceptedHandoffTrace(),
                    List.of(CAPTURE_REF),
                    5_000)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "DEADLINE_EXHAUSTED",
                    acceptedHandoffTrace(),
                    List.of(CAPTURE_REF),
                    4_999)));
  }

  @Test
  void acceptedHandoffImplicitStepLimitMustBeActuallyExhausted() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(1),
                outcome(
                    RunStatus.FAILED,
                    "MODEL_STEP_LIMIT_EXHAUSTED",
                    acceptedHandoffTrace(),
                    List.of(CAPTURE_REF))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(2),
                outcome(
                    RunStatus.FAILED,
                    "MODEL_STEP_LIMIT_EXHAUSTED",
                    acceptedHandoffTrace(),
                    List.of(CAPTURE_REF))));
  }

  @Test
  void untrustedHandoffObservationCanFailClosedWithoutTrustingItsTrace() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "UNSAFE_HANDOFF_OUTCOME",
                    List.of(),
                    List.of())));
  }

  @Test
  void preRequestHandoffRejectionsPreserveTheirStableBoundaryTruth() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                legacyTask(),
                outcome(
                    RunStatus.BLOCKED,
                    "HANDOFF_NOT_ALLOWED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "BLOCKED",
                            null)),
                    List.of())));
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "HANDOFF_PREPARATION_FAILED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "FAILED",
                            null)),
                    List.of())));
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    "UNSAFE_HANDOFF_OUTCOME",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "MALFORMED_RESULT",
                            null)),
                    List.of())));
  }

  @Test
  void preRequestHandoffRejectionStatusAndPreparationMappingAreExact() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                legacyTask(),
                outcome(
                    RunStatus.FAILED,
                    "HANDOFF_NOT_ALLOWED",
                    List.of(
                        modelStep(),
                        event(
                            2,
                            AgentTraceEventType.HANDOFF_REJECTED,
                            "FAILED",
                            null)),
                    List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentWorkerRuntime.Preparation.rejected(
                RunStatus.FAILED,
                "HANDOFF_NOT_ALLOWED"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentWorkerRuntime.Preparation.rejected(
                RunStatus.BLOCKED,
                "HANDOFF_SOMETHING_UNREGISTERED"));
  }

  @Test
  void postDispatchHandoffDeadlineRequiresStrictlyLateLatency() {
    List<AgentTraceEvent> trace =
        List.of(
            modelStep(),
            event(
                2,
                AgentTraceEventType.HANDOFF_REQUEST,
                "REQUESTED",
                CHILD_REF),
            event(
                3,
                AgentTraceEventType.HANDOFF_REJECTED,
                "DEADLINE_EXCEEDED_AFTER_CHILD",
                CHILD_REF));

    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
                    trace,
                    List.of(),
                    5_001)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                outcome(
                    RunStatus.FAILED,
                    ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
                    trace,
                    List.of(),
                    5_000)));
  }

  @Test
  void postRequestHandoffRejectionStatusAndTerminalTruthAreExactlyBound() {
    List<PostRequestRejection> valid =
        List.of(
            new PostRequestRejection(
                "CANCELLED_UNOBSERVED",
                RunStatus.CANCELLED,
                "CANCELLED",
                0),
            new PostRequestRejection(
                "DEADLINE_EXHAUSTED",
                RunStatus.FAILED,
                "HANDOFF_DEADLINE_EXHAUSTED",
                5_000),
            new PostRequestRejection(
                "DEADLINE_EXCEEDED_UNOBSERVED",
                RunStatus.FAILED,
                ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
                5_001),
            new PostRequestRejection(
                "FAILED",
                RunStatus.FAILED,
                "HANDOFF_DISPATCH_FAILED",
                0),
            new PostRequestRejection(
                "MALFORMED_RESULT",
                RunStatus.FAILED,
                "UNSAFE_HANDOFF_OUTCOME",
                0),
            new PostRequestRejection(
                "LIMIT_EXHAUSTED",
                RunStatus.BLOCKED,
                "HANDOFF_BUDGET_EXHAUSTED",
                0),
            new PostRequestRejection(
                "DEADLINE_EXCEEDED_AFTER_CHILD",
                RunStatus.FAILED,
                ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
                5_001),
            new PostRequestRejection(
                "CHILD_FAILED",
                RunStatus.FAILED,
                "HANDOFF_CHILD_FAILED",
                0),
            new PostRequestRejection(
                "CHILD_BLOCKED",
                RunStatus.BLOCKED,
                "HANDOFF_CHILD_BLOCKED",
                0),
            new PostRequestRejection(
                "CHILD_NEEDS_INPUT",
                RunStatus.NEEDS_INPUT,
                "HANDOFF_CHILD_NEEDS_INPUT",
                0),
            new PostRequestRejection(
                "CHILD_CANCELLED",
                RunStatus.CANCELLED,
                "HANDOFF_CHILD_CANCELLED",
                0));

    for (PostRequestRejection rejection : valid) {
      assertDoesNotThrow(
          () ->
              AgentTraceProtocol.verifyKernelOutcome(
                  workerTask(), rejection.outcome()));
    }

    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                postRequestRejectedOutcome(
                    "MALFORMED_RESULT",
                    RunStatus.FAILED,
                    "HANDOFF_DISPATCH_FAILED",
                    0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                postRequestRejectedOutcome(
                    "FAILED",
                    RunStatus.FAILED,
                    "UNSAFE_HANDOFF_OUTCOME",
                    0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                workerTask(),
                postRequestRejectedOutcome(
                    "BLOCKED",
                    RunStatus.BLOCKED,
                    "HANDOFF_NOT_ALLOWED",
                    0)));
  }

  private static AgentRunOutcome postRequestRejectedOutcome(
      String traceStatus,
      RunStatus status,
      String failureReason,
      long latencyMs) {
    return outcome(
        status,
        failureReason,
        List.of(
            modelStep(),
            event(
                2,
                AgentTraceEventType.HANDOFF_REQUEST,
                "REQUESTED",
                CHILD_REF),
            event(
                3,
                AgentTraceEventType.HANDOFF_REJECTED,
                traceStatus,
                CHILD_REF)),
        List.of(),
        latencyMs);
  }

  private static AgentRunOutcome outcome(
      RunStatus status,
      String failureReason,
      List<AgentTraceEvent> trace,
      List<String> evidenceRefs) {
    return outcome(status, failureReason, trace, evidenceRefs, 0);
  }

  private static AgentRunOutcome outcome(
      RunStatus status,
      String failureReason,
      List<AgentTraceEvent> trace,
      List<String> evidenceRefs,
      long latencyMs) {
    return new AgentRunOutcome(
        status,
        null,
        evidenceRefs,
        trace,
        null,
        BigDecimal.ZERO,
        0,
        latencyMs,
        failureReason);
  }

  private static AgentTraceEvent modelStep() {
    return new AgentTraceEvent(
        1,
        AgentTraceEventType.MODEL_STEP,
        null,
        "COMPLETED",
        "task://" + TASK_ID);
  }

  private static AgentTraceEvent event(
      int sequence,
      AgentTraceEventType type,
      String status,
      String reference) {
    return new AgentTraceEvent(sequence, type, null, status, reference);
  }

  private static TaskEnvelope legacyTask() {
    return task(List.of());
  }

  private static TaskEnvelope workerTask() {
    return workerTask(2);
  }

  private static TaskEnvelope workerTask(int maxModelSteps) {
    return task(
        List.of(AgentTraceProtocol.READ_ONLY_WORKER_CAPABILITY),
        maxModelSteps);
  }

  private static TaskEnvelope task(List<String> capabilities) {
    return task(capabilities, 2);
  }

  private static TaskEnvelope task(
      List<String> capabilities, int maxModelSteps) {
    return new TaskEnvelope(
        "1.0",
        TASK_ID,
        null,
        "handoff-protocol-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Verify typed Handoff Trace semantics",
        List.of(CAPTURE_REF),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of(),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of(),
        false,
        maxModelSteps,
        1,
        5_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-pack007",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        capabilities,
        List.of(),
        "structured final or non-success");
  }

  private static List<AgentTraceEvent> acceptedHandoffTrace() {
    return List.of(
        modelStep(),
        event(
            2,
            AgentTraceEventType.HANDOFF_REQUEST,
            "REQUESTED",
            CHILD_REF),
        event(
            3,
            AgentTraceEventType.HANDOFF_RESULT,
            "SUCCEEDED",
            CHILD_REF));
  }

  private record PostRequestRejection(
      String traceStatus,
      RunStatus status,
      String failureReason,
      long latencyMs) {

    private AgentRunOutcome outcome() {
      return postRequestRejectedOutcome(
          traceStatus, status, failureReason, latencyMs);
    }
  }
}
