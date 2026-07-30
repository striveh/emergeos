package io.emergeos.core.domain;

import io.emergeos.contracts.RunStatus;

public enum AgentRunLifecycle {
  RUNNING,
  SUCCEEDED,
  FAILED,
  NEEDS_INPUT,
  BLOCKED,
  CANCELLED;

  public static AgentRunLifecycle terminal(RunStatus status) {
    return AgentRunLifecycle.valueOf(status.name());
  }

  public boolean terminal() {
    return this != RUNNING;
  }
}
