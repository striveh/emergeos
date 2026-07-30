package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTraceProtocolDeadlineTest {

  private static final String TASK_ID = "task-tool-deadline-006";
  private static final String CAPTURE_REF = "capture://capture-tool-deadline-006";
  private static final String FAILURE =
      "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH";

  @Test
  void acceptsPostDispatchDeadlineTruthWithoutClaimingEvidence() {
    assertDoesNotThrow(
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                task(),
                outcome(deadlineTrace("DEADLINE_EXCEEDED"), FAILURE, List.of())));
  }

  @Test
  void rejectsDeadlineTraceWithAnotherFailureReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                task(),
                outcome(
                    deadlineTrace("DEADLINE_EXCEEDED"),
                    "TOOL_EXECUTION_FAILED",
                    List.of())));
  }

  @Test
  void rejectsPostDispatchDeadlineReasonWithoutItsTraceStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                task(), outcome(deadlineTrace("FAILED"), FAILURE, List.of())));
  }

  @Test
  void rejectsLateToolCompletionClaimedAsObtainedEvidence() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentTraceProtocol.verifyKernelOutcome(
                task(),
                outcome(
                    deadlineTrace("DEADLINE_EXCEEDED"),
                    FAILURE,
                    List.of(CAPTURE_REF))));
  }

  @Test
  void rejectsDeadlineAttributionUnlessLatencyStrictlyExceedsTheDeadline() {
    for (long latencyMs : List.of(0L, 5L)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              AgentTraceProtocol.verifyKernelOutcome(
                  task(),
                  outcome(
                      deadlineTrace("DEADLINE_EXCEEDED"),
                      FAILURE,
                      List.of(),
                      latencyMs)));
    }
  }

  private static AgentRunOutcome outcome(
      List<AgentTraceEvent> trace,
      String failureReason,
      List<String> obtainedEvidenceRefs) {
    return outcome(trace, failureReason, obtainedEvidenceRefs, 7);
  }

  private static AgentRunOutcome outcome(
      List<AgentTraceEvent> trace,
      String failureReason,
      List<String> obtainedEvidenceRefs,
      long latencyMs) {
    return new AgentRunOutcome(
        RunStatus.FAILED,
        null,
        obtainedEvidenceRefs,
        trace,
        "scripted-tool-deadline-fault-v1",
        BigDecimal.ZERO,
        0,
        latencyMs,
        failureReason);
  }

  private static List<AgentTraceEvent> deadlineTrace(String rejectionStatus) {
    return List.of(
        new AgentTraceEvent(
            1,
            AgentTraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + TASK_ID),
        new AgentTraceEvent(
            2,
            AgentTraceEventType.TOOL_REQUEST,
            "capture.read",
            "REQUESTED",
            CAPTURE_REF),
        new AgentTraceEvent(
            3,
            AgentTraceEventType.TOOL_REJECTED,
            "capture.read",
            rejectionStatus,
            CAPTURE_REF));
  }

  private static TaskEnvelope task() {
    return new TaskEnvelope(
        "1.0",
        TASK_ID,
        null,
        "synthetic-tool-deadline-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Verify one post-dispatch read-only Tool deadline",
        List.of(CAPTURE_REF),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of(),
        false,
        2,
        1,
        5,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-s4-f2",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        List.of(),
        List.of(),
        "structured final or non-success");
  }
}
