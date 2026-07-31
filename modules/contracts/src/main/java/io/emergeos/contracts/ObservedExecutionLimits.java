package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
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
  public static final String POST_HANDOFF_DEADLINE_FAILURE =
      "HANDOFF_DEADLINE_EXCEEDED_AFTER_DISPATCH";
  public static final String READ_ONLY_WORKER_CAPABILITY =
      "capability://worker-handoff/article-draft-read-v1";

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
    boolean toolDeadline =
        POST_DISPATCH_DEADLINE_FAILURE.equals(failureReason);
    boolean handoffDeadline =
        POST_HANDOFF_DEADLINE_FAILURE.equals(failureReason);
    if (!toolDeadline && !handoffDeadline) {
      return true;
    }
    if (handoffDeadline
        && !task
            .capabilityRefs()
            .equals(List.of(READ_ONLY_WORKER_CAPABILITY))) {
      return false;
    }
    return status == RunStatus.FAILED
        && postDispatchDeadlineExceeded(task, latencyMs);
  }

  public static boolean postDispatchDeadlineExceeded(
      TaskEnvelope task, long latencyMs) {
    Objects.requireNonNull(task, "task");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
    return latencyMs > task.deadlineMs();
  }

  /**
   * Preserves an observed over-budget subtree only when the terminal failure explicitly attributes
   * that overage to the execution boundary which incurred it.
   */
  public static boolean permitsBudget(
      TaskEnvelope task,
      RunStatus status,
      BigDecimal observedCostUsd,
      String failureReason) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(status, "status");
    ContractValueDomains.requireUsd(observedCostUsd, "observedCostUsd");
    boolean declaresModelBudget =
        "1.1".equals(task.schemaVersion())
            && "MODEL_BUDGET_EXHAUSTED".equals(failureReason);
    boolean declaresWorkerSubtreeBudget =
        "HANDOFF_BUDGET_EXHAUSTED".equals(failureReason);
    boolean overBudget =
        observedCostUsd.compareTo(task.budgetUsd()) > 0;
    if (declaresModelBudget) {
      return overBudget && status != RunStatus.SUCCEEDED;
    }
    if (declaresWorkerSubtreeBudget) {
      return overBudget
          && status == RunStatus.BLOCKED
          && task.capabilityRefs().equals(
              List.of(READ_ONLY_WORKER_CAPABILITY));
    }
    if (!overBudget) {
      return true;
    }
    return false;
  }
}
