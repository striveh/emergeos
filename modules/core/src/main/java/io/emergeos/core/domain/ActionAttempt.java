package io.emergeos.core.domain;

import java.util.List;
import java.util.Objects;

public record ActionAttempt(
    String attemptId,
    ActionPlan plan,
    ApprovalDecision approval,
    ActionCapability capability,
    ActionAttemptStatus status,
    int capabilityUsedCalls,
    List<ActionTransition> transitions,
    ActionReceipt receipt) {

  public ActionAttempt {
    requireText(attemptId, "attemptId");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(status, "status");
    transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
    if (!approval.approved()
        || !approval.planId().equals(plan.planId())
        || !approval.planHash().equals(plan.planHash())
        || !approval.artifactHash().equals(plan.artifactHash())
        || !approval.actor().equals(plan.principalId())) {
      throw new IllegalArgumentException("approval must authorize the exact ActionPlan");
    }
    if (!capability.subject().equals(plan.principalId())
        || !capability.actionPlanId().equals(plan.planId())
        || !capability.actionPlanHash().equals(plan.planHash())
        || !capability.artifactHash().equals(plan.artifactHash())
        || !capability.idempotencyKey().equals(plan.idempotencyKey())
        || !capability.expiresAt().equals(plan.expiresAt())) {
      throw new IllegalArgumentException("Capability must bind the exact ActionPlan");
    }
    if (capabilityUsedCalls < 0 || capabilityUsedCalls > capability.maxCalls()) {
      throw new IllegalArgumentException("capabilityUsedCalls must stay within the budget");
    }
    validateTransitions(status, transitions);
    if (status == ActionAttemptStatus.PLANNED && capabilityUsedCalls != 0) {
      throw new IllegalArgumentException("PLANNED must not have spent capability budget");
    }
    if (status != ActionAttemptStatus.PLANNED && capabilityUsedCalls < 1) {
      throw new IllegalArgumentException("a dispatched ActionAttempt must have spent budget");
    }
    if (status.isTerminal()) {
      if (receipt == null
          || !receipt.attemptId().equals(attemptId)
          || receipt.outcome() != status) {
        throw new IllegalArgumentException("terminal ActionAttempt must own its matching Receipt");
      }
    } else if (receipt != null) {
      throw new IllegalArgumentException("non-terminal ActionAttempt must not have a Receipt");
    }
  }

  public String connector() {
    return capability.connector();
  }

  public String audience() {
    return capability.audience();
  }

  public String accountRef() {
    return capability.accountRef();
  }

  private static void validateTransitions(
      ActionAttemptStatus status, List<ActionTransition> transitions) {
    if (transitions.isEmpty()) {
      throw new IllegalArgumentException("transitions must not be empty");
    }
    for (int index = 0; index < transitions.size(); index++) {
      ActionTransition current = transitions.get(index);
      if (current.sequence() != index + 1) {
        throw new IllegalArgumentException("transition sequence must be continuous");
      }
      if (index > 0
          && current.fromStatus() != transitions.get(index - 1).toStatus()) {
        throw new IllegalArgumentException("transition history must be continuous");
      }
    }
    if (transitions.getLast().toStatus() != status) {
      throw new IllegalArgumentException("current status must match the transition history");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > 200 || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
