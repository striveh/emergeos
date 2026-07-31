package io.emergeos.core.port;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import java.util.Objects;

/**
 * Server-owned identity of one concrete Agent execution.
 *
 * <p>This context never enters a Model prompt or public Task contract. It carries the exact Run
 * identity needed to bind durable child execution lineage before dispatch.
 */
public record AgentRunContext(
    String runId,
    String principalId,
    TaskEnvelope task) {

  public AgentRunContext {
    if (runId == null
        || !runId.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException("runId has an invalid identifier");
    }
    ContractText.require(
        principalId, "principalId", ContractText.MAX_NAME_LENGTH);
    task = Objects.requireNonNull(task, "task");
    if (!principalId.equals(task.principalRef())) {
      throw new IllegalArgumentException(
          "AgentRunContext principal must match its Task");
    }
  }

  public static AgentRunContext fromRunning(AgentRun running) {
    Objects.requireNonNull(running, "running");
    if (running.lifecycle() != AgentRunLifecycle.RUNNING) {
      throw new IllegalArgumentException(
          "AgentRunContext requires canonical RUNNING truth");
    }
    return new AgentRunContext(
        running.runId(), running.principalId(), running.task());
  }
}
