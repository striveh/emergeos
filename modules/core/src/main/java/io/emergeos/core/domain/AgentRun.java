package io.emergeos.core.domain;

import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentRun(
    String runId,
    String principalId,
    TaskEnvelope task,
    AgentRunLifecycle lifecycle,
    ResultEnvelope result,
    AgentTraceEnvelope trace,
    HarnessRunBundle bundle,
    Instant startedAt,
    Instant completedAt) {

  public AgentRun {
    requireRunId(runId);
    ContractText.require(principalId, "principalId", ContractText.MAX_NAME_LENGTH);
    task = Objects.requireNonNull(task, "task");
    lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
    if (!principalId.equals(task.principalRef())) {
      throw new IllegalArgumentException("AgentRun principal must match Task principal");
    }
    if (lifecycle == AgentRunLifecycle.RUNNING) {
      if (result != null || trace != null || bundle != null || completedAt != null) {
        throw new IllegalArgumentException("RUNNING AgentRun cannot contain terminal truth");
      }
    } else {
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(trace, "trace");
      Objects.requireNonNull(bundle, "bundle");
      Objects.requireNonNull(completedAt, "completedAt");
      if (!runId.equals(result.runId())
          || !runId.equals(trace.runId())
          || !runId.equals(bundle.runId())
          || !task.id().equals(result.taskId())
          || !task.id().equals(trace.taskId())
          || !task.id().equals(bundle.taskId())
          || !task.equals(bundle.task())
          || !result.equals(bundle.result())
          || !trace.rootHash().equals(bundle.traceRootHash())
          || !lifecycle.name().equals(result.status().name())
          || !ObservedExecutionLimits.permitsBudget(
              task,
              result.status(),
              result.costUsd(),
              result.failureReason())
          || !ObservedExecutionLimits.permitsLatency(
              task, result.status(), result.latencyMs())
          || !ObservedExecutionLimits.permitsFailureAttribution(
              task,
              result.status(),
              result.latencyMs(),
              result.failureReason())
          || completedAt.isBefore(startedAt)) {
        throw new IllegalArgumentException("terminal AgentRun aggregate is inconsistent");
      }
      AgentTraceProtocol.verifyTerminal(task, result, trace);
    }
  }

  public static AgentRun running(
      String runId, String principalId, TaskEnvelope task, Instant startedAt) {
    return new AgentRun(
        runId,
        principalId,
        task,
        AgentRunLifecycle.RUNNING,
        null,
        null,
        null,
        startedAt,
        null);
  }

  private static void requireRunId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException("runId has an invalid identifier");
    }
  }

}
