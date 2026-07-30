package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.TaskEnvelope;

public interface AgentTool {

  String name();

  AgentModel.ToolResult execute(TaskEnvelope task, AgentModel.ToolCall call);
}
