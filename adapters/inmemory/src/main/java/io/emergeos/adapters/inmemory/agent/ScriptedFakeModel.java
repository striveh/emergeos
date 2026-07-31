package io.emergeos.adapters.inmemory.agent;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import java.util.List;
import java.util.Objects;

public final class ScriptedFakeModel implements AgentModel {

  public static final String MODEL_ID = "scripted-fake-draft-v1";
  public static final String CONDUCTOR_MODEL_ID = "fake-model-v1";
  public static final String WORKER_MODEL_ID = "fake-worker-model-v1";

  private final Mode mode;
  private final String requestedTool;
  private final String modelId;

  private ScriptedFakeModel(
      Mode mode, String requestedTool, String modelId) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.requestedTool = requestedTool;
    this.modelId = Objects.requireNonNull(modelId, "modelId");
  }

  public static ScriptedFakeModel forCaptureDraft() {
    return captureDraft(CaptureReadTool.NAME, MODEL_ID);
  }

  public static ScriptedFakeModel forReadOnlyWorkerProposal() {
    return captureDraft(CaptureReadTool.NAME, WORKER_MODEL_ID);
  }

  public static ScriptedFakeModel forReadOnlyWorkerDraft() {
    return new ScriptedFakeModel(
        Mode.READ_ONLY_WORKER_CONDUCTOR, null, CONDUCTOR_MODEL_ID);
  }

  public static ScriptedFakeModel requestingTool(String toolName) {
    return captureDraft(
        Objects.requireNonNull(toolName, "toolName"), MODEL_ID);
  }

  private static ScriptedFakeModel captureDraft(
      String requestedTool, String modelId) {
    return new ScriptedFakeModel(
        Mode.CAPTURE_DRAFT,
        Objects.requireNonNull(requestedTool, "requestedTool"),
        modelId);
  }

  @Override
  public Session open(io.emergeos.contracts.TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    return (turn, context) ->
        new ModelStep(decide(turn), modelId, ModelUsage.zero());
  }

  public Decision decide(Turn turn) {
    Objects.requireNonNull(turn, "turn");
    if (mode == Mode.READ_ONLY_WORKER_CONDUCTOR) {
      return decideConductor(turn);
    }
    if (turn.toolResults().isEmpty()) {
      if (turn.task().inputRefs().isEmpty()) {
        return new FinalDraft(null, List.of());
      }
      return new ToolCall(
          requestedTool,
          ToolArguments.forReference(turn.task().inputRefs().getFirst()));
    }
    ToolResult source = turn.toolResults().getLast();
    String content =
        """
        # 从想法到可信成果

        %s

        目标：%s
        """
            .formatted(source.content(), turn.task().intent())
            .strip();
    return new FinalDraft(content, List.of(source.reference()));
  }

  private static Decision decideConductor(Turn turn) {
    if (turn.workerResults().isEmpty()) {
      return new WorkerCall(
          ReadOnlyWorkerExecutionProfile.WORKER_NAME,
          "产出一篇引用该 Capture 的短文 proposal",
          turn.task().inputRefs());
    }
    if (turn.workerResults().size() != 1
        || !turn.toolResults().isEmpty()) {
      return new Failed("MALFORMED_WORKER_RESULT");
    }
    WorkerResult result = turn.workerResults().getFirst();
    return new FinalDraft(result.content(), result.evidenceRefs());
  }

  private enum Mode {
    CAPTURE_DRAFT,
    READ_ONLY_WORKER_CONDUCTOR
  }
}
