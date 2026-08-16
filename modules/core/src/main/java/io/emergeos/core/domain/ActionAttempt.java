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
    ActionReceipt receipt,
    ActionApprovalScope approvalScope,
    LocalDraftCreationReceipt localDraftReceipt) {

  public ActionAttempt(
      String attemptId,
      ActionPlan plan,
      ApprovalDecision approval,
      ActionCapability capability,
      ActionAttemptStatus status,
      int capabilityUsedCalls,
      List<ActionTransition> transitions,
      ActionReceipt receipt,
      ActionApprovalScope approvalScope) {
    this(
        attemptId,
        plan,
        approval,
        capability,
        status,
        capabilityUsedCalls,
        transitions,
        receipt,
        approvalScope,
        null);
  }

  public ActionAttempt(
      String attemptId,
      ActionPlan plan,
      ApprovalDecision approval,
      ActionCapability capability,
      ActionAttemptStatus status,
      int capabilityUsedCalls,
      List<ActionTransition> transitions,
      ActionReceipt receipt) {
    this(
        attemptId,
        plan,
        approval,
        capability,
        status,
        capabilityUsedCalls,
        transitions,
        receipt,
        legacyUnprovenScope(plan, approval, capability),
        null);
  }

  public ActionAttempt {
    requireText(attemptId, "attemptId");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(approvalScope, "approvalScope");
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
    if (!approvalScope.configuredPrincipalId().equals(plan.principalId())
        || !approvalScope.actionType().equals(plan.actionType())
        || !approvalScope.targetRef().equals(plan.targetRef())
        || !approvalScope.artifactId().equals(plan.artifactId())
        || approvalScope.artifactVersion() != plan.artifactVersion()
        || !approvalScope.artifactHash().equals(plan.artifactHash())
        || approvalScope.risk() != plan.risk()
        || !approvalScope.policyVersion().equals(plan.policyVersion())
        || !approvalScope.connector().equals(capability.connector())
        || !approvalScope.audience().equals(capability.audience())
        || !approvalScope.accountRef().equals(capability.accountRef())
        || approvalScope.maxCalls() != capability.maxCalls()) {
      throw new IllegalArgumentException("approval scope must bind the exact action authority");
    }
    long approvalToExpiryMicros =
        Math.addExact(
            Math.multiplyExact(
                plan.expiresAt().getEpochSecond() - approval.decidedAt().getEpochSecond(),
                1_000_000L),
            (plan.expiresAt().getNano() - approval.decidedAt().getNano()) / 1_000L);
    if (approvalScope.capabilityTtlMicros() != approvalToExpiryMicros) {
      throw new IllegalArgumentException("approval scope must bind the capability TTL");
    }
    if (capabilityUsedCalls < 0 || capabilityUsedCalls > capability.maxCalls()) {
      throw new IllegalArgumentException("capabilityUsedCalls must stay within the budget");
    }
    validateTransitions(status, transitions, approvalScope);
    if (status == ActionAttemptStatus.PLANNED && capabilityUsedCalls != 0) {
      throw new IllegalArgumentException("PLANNED must not have spent capability budget");
    }
    if (status != ActionAttemptStatus.PLANNED && capabilityUsedCalls < 1) {
      throw new IllegalArgumentException("a dispatched ActionAttempt must have spent budget");
    }
    boolean localDraftboxV2 =
        ActionApprovalScope.LOCAL_DRAFTBOX_V2.equals(approvalScope.executionRoute());
    if (status.isTerminal()) {
      if (localDraftboxV2) {
        if (status != ActionAttemptStatus.SUCCEEDED
            || receipt != null
            || localDraftReceipt == null
            || !localDraftReceipt.attemptId().equals(attemptId)) {
          throw new IllegalArgumentException(
              "terminal local Draftbox ActionAttempt must own its creation Receipt");
        }
      } else if (receipt == null
          || localDraftReceipt != null
          || !receipt.attemptId().equals(attemptId)
          || receipt.outcome() != status) {
        throw new IllegalArgumentException("terminal ActionAttempt must own its matching Receipt");
      }
    } else if (receipt != null || localDraftReceipt != null) {
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
      ActionAttemptStatus status,
      List<ActionTransition> transitions,
      ActionApprovalScope approvalScope) {
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
      if (current.fromStatus() == ActionAttemptStatus.PLANNED
          && current.toStatus() == ActionAttemptStatus.SUCCEEDED
          && !ActionApprovalScope.LOCAL_DRAFTBOX_V2.equals(
              approvalScope.executionRoute())) {
        throw new IllegalArgumentException(
            "direct local success requires the local Draftbox V2 route");
      }
    }
    if (transitions.getLast().toStatus() != status) {
      throw new IllegalArgumentException("current status must match the transition history");
    }
    if (ActionApprovalScope.LOCAL_DRAFTBOX_V2.equals(approvalScope.executionRoute())) {
      boolean exactPlanned =
          status == ActionAttemptStatus.PLANNED && transitions.size() == 1;
      boolean exactSucceeded =
          status == ActionAttemptStatus.SUCCEEDED
              && transitions.size() == 2
              && transitions.get(1).fromStatus() == ActionAttemptStatus.PLANNED
              && transitions.get(1).toStatus() == ActionAttemptStatus.SUCCEEDED;
      if (!exactPlanned && !exactSucceeded) {
        throw new IllegalArgumentException(
            "local Draftbox V2 attempts allow only PLANNED or direct SUCCEEDED");
      }
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

  private static ActionApprovalScope legacyUnprovenScope(
      ActionPlan plan, ApprovalDecision approval, ActionCapability capability) {
    long seconds = plan.expiresAt().getEpochSecond() - approval.decidedAt().getEpochSecond();
    long nanos = plan.expiresAt().getNano() - approval.decidedAt().getNano();
    long ttlMicros = Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1_000L);
    return new ActionApprovalScope(
        ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
        plan.principalId(),
        ActionApprovalScope.PRE_V17_UNPROVEN,
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        plan.actionType(),
        plan.targetRef(),
        plan.artifactId(),
        plan.artifactVersion(),
        plan.artifactHash(),
        plan.risk(),
        plan.policyVersion(),
        capability.connector(),
        capability.audience(),
        capability.accountRef(),
        ttlMicros,
        capability.maxCalls());
  }
}
