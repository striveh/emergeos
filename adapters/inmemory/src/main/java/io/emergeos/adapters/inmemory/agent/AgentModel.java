package io.emergeos.adapters.inmemory.agent;

import io.emergeos.contracts.TaskEnvelope;
import java.util.List;
import java.util.Objects;

public interface AgentModel {

  String modelId();

  Decision decide(Turn turn);

  record Turn(TaskEnvelope task, List<ToolResult> toolResults) {

    public Turn {
      Objects.requireNonNull(task, "task");
      toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults"));
    }
  }

  sealed interface Decision permits ToolCall, FinalDraft {}

  record ToolCall(String toolName, String reference) implements Decision {

    public ToolCall {
      requireToolName(toolName);
      requireReference(reference);
    }
  }

  record FinalDraft(String content, List<String> evidenceRefs) implements Decision {

    public FinalDraft {
      evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
  }

  record ToolResult(String toolName, String reference, String content) {

    public ToolResult {
      requireToolName(toolName);
      requireReference(reference);
      Objects.requireNonNull(content, "content");
    }
  }

  private static void requireToolName(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException(
          "toolName must be a bounded lowercase canonical name");
    }
  }

  private static void requireReference(String value) {
    if (value == null
        || value.length() > 240
        || !value.matches("[a-z][a-z0-9+.-]{0,31}://[A-Za-z0-9._:-]{1,200}")) {
      throw new IllegalArgumentException(
          "reference must be a bounded canonical resource reference");
    }
  }
}
