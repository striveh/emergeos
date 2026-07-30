package io.emergeos.contracts;

import java.util.Objects;

/**
 * Shared cross-aggregate policy for observed execution values.
 *
 * <p>A deadline is a success acceptance boundary. A cooperative caller may only observe a failure
 * after the deadline, so non-success outcomes preserve their actual latency instead of clamping or
 * erasing it.
 */
public final class ObservedExecutionLimits {

  public static final String POST_DISPATCH_DEADLINE_FAILURE =
      "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH";

  private ObservedExecutionLimits() {}

  public static boolean permitsLatency(
      TaskEnvelope task, RunStatus status, long latencyMs) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(status, "status");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
    return status != RunStatus.SUCCEEDED || latencyMs <= task.deadlineMs();
  }

  public static boolean permitsFailureAttribution(
      TaskEnvelope task,
      RunStatus status,
      long latencyMs,
      String failureReason) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(status, "status");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
    return !POST_DISPATCH_DEADLINE_FAILURE.equals(failureReason)
        || (status == RunStatus.FAILED
            && postDispatchDeadlineExceeded(task, latencyMs));
  }

  public static boolean postDispatchDeadlineExceeded(
      TaskEnvelope task, long latencyMs) {
    Objects.requireNonNull(task, "task");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
    return latencyMs > task.deadlineMs();
  }
}
