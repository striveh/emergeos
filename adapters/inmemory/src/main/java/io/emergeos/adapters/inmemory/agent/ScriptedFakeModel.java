package io.emergeos.adapters.inmemory.agent;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import java.util.List;
import java.util.Objects;

public final class ScriptedFakeModel implements AgentModel {

  public static final String MODEL_ID = "scripted-fake-draft-v1";

  private final String requestedTool;

  private ScriptedFakeModel(String requestedTool) {
    this.requestedTool = Objects.requireNonNull(requestedTool, "requestedTool");
  }

  public static ScriptedFakeModel forCaptureDraft() {
    return new ScriptedFakeModel(CaptureReadTool.NAME);
  }

  public static ScriptedFakeModel requestingTool(String toolName) {
    return new ScriptedFakeModel(toolName);
  }

  @Override
  public Session open(io.emergeos.contracts.TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    return (turn, context) ->
        new ModelStep(decide(turn), MODEL_ID, ModelUsage.zero());
  }

  public Decision decide(Turn turn) {
    Objects.requireNonNull(turn, "turn");
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
}
