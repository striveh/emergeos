package io.emergeos.adapters.inmemory.agent;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.port.CaptureStore;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class CaptureReadTool implements AgentTool {

  public static final String NAME = "capture.read";

  private final CaptureStore captures;

  public CaptureReadTool(CaptureStore captures) {
    this.captures = Objects.requireNonNull(captures, "captures");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public AgentModel.ToolResult execute(
      TaskEnvelope task,
      AgentModel.ToolCall call) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(call, "call");
    if (!NAME.equals(call.toolName()) || !task.inputRefs().contains(call.reference())) {
      throw new IllegalArgumentException("capture.read reference is outside the Task inputs");
    }
    String prefix = "capture://";
    if (!call.reference().startsWith(prefix) || call.reference().length() == prefix.length()) {
      throw new IllegalArgumentException("capture.read requires a Capture reference");
    }
    String captureId = call.reference().substring(prefix.length());
    Capture capture =
        captures
            .findOwned(task.principalRef(), captureId)
            .orElseThrow(() -> new NoSuchElementException("Capture not found"));
    return new AgentModel.ToolResult(NAME, call.reference(), capture.content());
  }
}
