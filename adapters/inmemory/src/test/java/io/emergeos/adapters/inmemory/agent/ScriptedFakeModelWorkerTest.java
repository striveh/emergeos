package io.emergeos.adapters.inmemory.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedFakeModelWorkerTest {

  private static final String CAPTURE_REF = "capture://pack007-capture";

  @Test
  void conductorRequestsTheExactWorkerThenConsumesOnlyItsVerifiedResult() {
    var profile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var task =
        profile.newDraftTask(
            "pack007-parent-task",
            "pack007-owner",
            "把想法整理成一篇短文",
            CAPTURE_REF,
            DataClass.PUBLIC);
    var session = ScriptedFakeModel.forReadOnlyWorkerDraft().open(task);
    var context =
        new AgentModel.ModelCallContext(
            task.deadlineMs(),
            task.budgetUsd(),
            CancellationSignal.never());

    AgentModel.ModelStep first =
        session.next(new AgentModel.Turn(task, List.of(), List.of()), context);
    AgentModel.WorkerCall call =
        assertInstanceOf(AgentModel.WorkerCall.class, first.decision());
    assertEquals(ScriptedFakeModel.CONDUCTOR_MODEL_ID, first.resolvedModel());
    assertEquals(ReadOnlyWorkerExecutionProfile.WORKER_NAME, call.workerName());
    assertEquals(List.of(CAPTURE_REF), call.inputRefs());

    String content = "Prompt 不是咒语，而是在构造运行时状态。";
    AgentModel.ModelStep second =
        session.next(
            new AgentModel.Turn(
                task,
                List.of(),
                List.of(
                    new AgentModel.WorkerResult(
                        call.workerName(),
                        "worker-result://pack007-child-run",
                        content,
                        "a".repeat(64),
                        List.of(CAPTURE_REF)))),
            context);
    AgentModel.FinalDraft finalDraft =
        assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
    assertEquals(ScriptedFakeModel.CONDUCTOR_MODEL_ID, second.resolvedModel());
    assertEquals(content, finalDraft.content());
    assertEquals(List.of(CAPTURE_REF), finalDraft.evidenceRefs());
  }

  @Test
  void proposalWorkerUsesCaptureReadAndCannotDelegateAgain() {
    var parentProfile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var parent =
        parentProfile.newDraftTask(
            "pack007-parent-task",
            "pack007-owner",
            "把想法整理成一篇短文",
            CAPTURE_REF,
            DataClass.PUBLIC);
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var request =
        new WorkerHandoffRequest(
            workerProfile.workerName(),
            "产出一篇引用该 Capture 的短文 proposal",
            List.of(CAPTURE_REF));
    var child =
        workerProfile.newChildTask(
            parent,
            request,
            new AgentWorkerRuntime.ExecutionWindow(
                parent.deadlineMs(),
                BigDecimal.ZERO,
                CancellationSignal.never()),
            "pack007-child-task");
    var session = ScriptedFakeModel.forReadOnlyWorkerProposal().open(child);
    var context =
        new AgentModel.ModelCallContext(
            child.deadlineMs(),
            child.budgetUsd(),
            CancellationSignal.never());

    AgentModel.ModelStep first =
        session.next(new AgentModel.Turn(child, List.of(), List.of()), context);
    AgentModel.ToolCall call =
        assertInstanceOf(AgentModel.ToolCall.class, first.decision());
    assertEquals(ScriptedFakeModel.WORKER_MODEL_ID, first.resolvedModel());
    assertEquals("capture.read", call.toolName());

    AgentModel.ModelStep second =
        session.next(
            new AgentModel.Turn(
                child,
                List.of(
                    new AgentModel.ToolResult(
                        "capture.read",
                        CAPTURE_REF,
                        "Prompt 工程是在为概率程序构造运行时状态。")),
                List.of()),
            context);
    AgentModel.FinalDraft proposal =
        assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
    assertEquals(ScriptedFakeModel.WORKER_MODEL_ID, second.resolvedModel());
    assertEquals(
        """
        # 从想法到可信成果

        Prompt 工程是在为概率程序构造运行时状态。

        目标：产出一篇引用该 Capture 的短文 proposal
        """
            .strip(),
        proposal.content());
    assertEquals(List.of(CAPTURE_REF), proposal.evidenceRefs());
  }
}
