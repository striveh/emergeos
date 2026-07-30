package io.emergeos.adapters.inmemory.agent;

import io.emergeos.contracts.TaskEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryToolRegistry {

  private final Map<String, AgentTool> tools;

  public InMemoryToolRegistry(List<AgentTool> tools) {
    Objects.requireNonNull(tools, "tools");
    Map<String, AgentTool> indexed = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      Objects.requireNonNull(tool, "tool");
      if (indexed.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
    this.tools = Map.copyOf(indexed);
  }

  ToolExecution execute(TaskEnvelope task, AgentModel.ToolCall call) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(call, "call");
    AgentTool tool = tools.get(call.toolName());
    if (tool == null || !task.requiredTools().contains(call.toolName())) {
      return new ToolExecution(false, null, "TOOL_NOT_ALLOWED");
    }
    return new ToolExecution(true, tool.execute(task, call), null);
  }

  boolean isRegistered(String toolName) {
    return tools.containsKey(toolName);
  }

  record ToolExecution(
      boolean allowed,
      AgentModel.ToolResult result,
      String failureReason) {}
}
