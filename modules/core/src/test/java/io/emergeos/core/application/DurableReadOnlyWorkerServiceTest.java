package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurableReadOnlyWorkerServiceTest {

  @Test
  void unsafeResolvedModelFailsClosedBeforeDurableProjection() {
    TaskEnvelope task = childTask();
    AgentRunOutcome candidate =
        new AgentRunOutcome(
            RunStatus.FAILED,
            null,
            List.of(),
            List.of(
                new AgentTraceEvent(
                    1,
                    AgentTraceEventType.MODEL_STEP,
                    null,
                    "FAILED",
                    "task://" + task.id())),
            "unsafe\nmodel",
            BigDecimal.ZERO,
            0,
            0,
            "MODEL_STEP_FAILED");

    AgentRunOutcome sanitized =
        DurableReadOnlyWorkerService.sanitizeKernelOutcome(candidate, task);

    assertEquals(RunStatus.FAILED, sanitized.status());
    assertEquals("UNSAFE_AGENT_OUTCOME", sanitized.failureReason());
    assertEquals(null, sanitized.resolvedModel());
    assertEquals(List.of(), sanitized.trace());
  }

  private static TaskEnvelope childTask() {
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerFakeV1();
    TaskEnvelope parent =
        parentProfile.newDraftTask(
            "worker-sanitizer-parent-task",
            "worker-sanitizer-owner",
            "Create one delegated synthetic draft",
            "capture://worker-sanitizer-capture",
            DataClass.PUBLIC);
    ReadOnlyWorkerExecutionProfile workerProfile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1(
            parent.contextPolicyVersion());
    return workerProfile.newChildTask(
        parent,
        new WorkerHandoffRequest(
            workerProfile.workerName(),
            "Create one read-only proposal",
            parent.inputRefs()),
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never()),
        "worker-sanitizer-child-task");
  }
}
