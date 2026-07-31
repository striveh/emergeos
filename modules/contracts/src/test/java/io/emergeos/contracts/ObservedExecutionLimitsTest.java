package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservedExecutionLimitsTest {

  @Test
  void permitsOnlyTheTypedFailureThatExplainsAnObservedBudgetOverage() {
    TaskEnvelope worker = task(List.of(ObservedExecutionLimits.READ_ONLY_WORKER_CAPABILITY));
    BigDecimal overBudget = new BigDecimal("0.001000");

    assertTrue(
        ObservedExecutionLimits.permitsBudget(
            worker,
            RunStatus.BLOCKED,
            overBudget,
            "HANDOFF_BUDGET_EXHAUSTED"));
    assertFalse(
        ObservedExecutionLimits.permitsBudget(
            worker,
            RunStatus.FAILED,
            overBudget,
            "HANDOFF_CHILD_FAILED"));
    for (RunStatus wrongStatus :
        List.of(
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.NEEDS_INPUT)) {
      assertFalse(
          ObservedExecutionLimits.permitsBudget(
              worker,
              wrongStatus,
              overBudget,
              "HANDOFF_BUDGET_EXHAUSTED"));
    }
    assertFalse(
        ObservedExecutionLimits.permitsBudget(
            task(List.of()),
            RunStatus.BLOCKED,
            overBudget,
            "HANDOFF_BUDGET_EXHAUSTED"));
    assertFalse(
        ObservedExecutionLimits.permitsBudget(
            worker,
            RunStatus.SUCCEEDED,
            overBudget,
            null));
    assertFalse(
        ObservedExecutionLimits.permitsBudget(
            worker,
            RunStatus.BLOCKED,
            worker.budgetUsd(),
            "HANDOFF_BUDGET_EXHAUSTED"));
  }

  @Test
  void acceptsOrdinaryOutcomesWhenObservedCostStaysWithinTheTaskBudget() {
    TaskEnvelope task = task(List.of());

    for (RunStatus status : RunStatus.values()) {
      assertTrue(
          ObservedExecutionLimits.permitsBudget(
              task,
              status,
              BigDecimal.ZERO,
              status == RunStatus.SUCCEEDED ? null : "SYNTHETIC_FAILURE"));
    }
  }

  @Test
  void handoffPostDispatchDeadlineRequiresStrictlyLateFailedTruth() {
    TaskEnvelope task =
        task(List.of(ObservedExecutionLimits.READ_ONLY_WORKER_CAPABILITY));

    assertTrue(
        ObservedExecutionLimits.permitsFailureAttribution(
            task,
            RunStatus.FAILED,
            task.deadlineMs() + 1,
            ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE));
    assertFalse(
        ObservedExecutionLimits.permitsFailureAttribution(
            task,
            RunStatus.FAILED,
            task.deadlineMs(),
            ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE));
    assertFalse(
        ObservedExecutionLimits.permitsFailureAttribution(
            task,
            RunStatus.BLOCKED,
            task.deadlineMs() + 1,
            ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE));
  }

  private static TaskEnvelope task(List<String> capabilities) {
    return new TaskEnvelope(
        "1.0",
        "observed-budget-task",
        null,
        "observed-budget-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Verify observed subtree budget truth",
        List.of("capture://observed-budget-capture"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of(),
        false,
        2,
        1,
        5_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-pack007",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        capabilities,
        List.of(),
        "structured final or non-success");
  }
}
