package io.emergeos.core.domain;

import java.util.Objects;

public record AgentTraceEvent(
    int sequence,
    AgentTraceEventType type,
    String toolName,
    String status,
    String reference) {

  public AgentTraceEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(type, "type");
    requireText(status, "status");
    if ((type == AgentTraceEventType.TOOL_REQUEST
            || type == AgentTraceEventType.TOOL_RESULT
            || type == AgentTraceEventType.TOOL_REJECTED)
        && (toolName == null || toolName.isBlank())) {
      throw new IllegalArgumentException("toolName is required for tool events");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
