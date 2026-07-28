package io.emergeos.core.application;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ProviderActionRequest;
import io.emergeos.core.domain.ProviderResult;
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.ActionProvider;
import io.emergeos.core.port.ArtifactLineageStore;
import io.emergeos.core.port.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class RecoverableActionService {

  private final ActionAttemptStore attempts;
  private final ArtifactLineageStore artifacts;
  private final ActionProvider provider;
  private final IdGenerator ids;
  private final Clock clock;
  private final LocalActionAuthority authority;

  public RecoverableActionService(
      ActionAttemptStore attempts,
      ArtifactLineageStore artifacts,
      ActionProvider provider,
      IdGenerator ids,
      Clock clock,
      LocalActionAuthority authority) {
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  public ActionAttempt approve(ApproveLocalActionCommand command) {
    Objects.requireNonNull(command, "command");
    ArtifactLineage artifact =
        artifacts
            .findOwned(authority.principalId(), command.artifactId())
            .orElseThrow(() -> new NoSuchElementException("Artifact not found"));
    if (!artifact.current().contentHash().equals(command.approvedArtifactHash())) {
      throw new IllegalStateException("approved Artifact is not the current version");
    }
    Instant now = canonicalTime(clock.instant());
    ActionAttempt proposed = planned(command, artifact, now);
    ActionAttemptStore.PlanResult planned = attempts.planOrFind(proposed);
    if (planned instanceof ActionAttemptStore.PlanResult.Conflict) {
      throw new ActionIdempotencyConflictException();
    }
    ActionAttempt canonical = ((ActionAttemptStore.PlanResult.Accepted) planned).attempt();
    if (canonical.status() != ActionAttemptStatus.PLANNED) {
      return canonical;
    }
    assertAuthority(canonical, now);
    ActionAttemptStore.ClaimResult claim = attempts.claimDispatch(canonical, now);
    if (claim instanceof ActionAttemptStore.ClaimResult.Claimed claimed) {
      return invoke(claimed.attempt(), ProviderActionRequest.ProviderOperation.EXECUTE);
    }
    return observedOrThrow(claim);
  }

  public ActionAttempt reconcile(String attemptId) {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    ActionAttempt current = get(attemptId);
    if (current.status().isTerminal() || current.status() != ActionAttemptStatus.UNKNOWN) {
      return current;
    }
    Instant now = canonicalTime(clock.instant());
    assertAuthority(current, now);
    ActionAttemptStore.ClaimResult claim = attempts.claimReconciliation(current, now);
    if (claim instanceof ActionAttemptStore.ClaimResult.Claimed claimed) {
      return invoke(
          claimed.attempt(), ProviderActionRequest.ProviderOperation.RECONCILE_ONLY);
    }
    return observedOrThrow(claim);
  }

  public ActionAttempt get(String attemptId) {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    return attempts
        .findOwned(authority.principalId(), attemptId)
        .orElseThrow(() -> new NoSuchElementException("ActionAttempt not found"));
  }

  private ActionAttempt invoke(
      ActionAttempt claimed, ProviderActionRequest.ProviderOperation operation) {
    ProviderResult result =
        provider.executeOrReconcile(
            new ProviderActionRequest(
                operation,
                claimed.plan(),
                authority.connector(),
                authority.audience(),
                authority.accountRef()));
    Instant now = canonicalTime(clock.instant());
    if (result instanceof ProviderResult.Succeeded succeeded) {
      ActionReceipt receipt =
          ActionReceipt.succeeded(
              ids.next("rcpt"),
              claimed.attemptId(),
              succeeded.externalId(),
              succeeded.providerRequestId(),
              succeeded.rawResponseRef(),
              succeeded.occurredAt(),
              true);
      return attempts.complete(claimed, receipt, now);
    }
    if (result instanceof ProviderResult.Failed failed) {
      ActionReceipt receipt =
          ActionReceipt.failed(
              ids.next("rcpt"),
              claimed.attemptId(),
              failed.reasonCode(),
              failed.providerRequestId(),
              failed.rawResponseRef(),
              failed.occurredAt(),
              true);
      return attempts.complete(claimed, receipt, now);
    }
    return attempts.markUnknown(claimed, now);
  }

  private ActionAttempt planned(
      ApproveLocalActionCommand command, ArtifactLineage artifact, Instant now) {
    Instant expiresAt =
        canonicalTime(now.plus(authority.capabilityTtl()));
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException(
          "capabilityTtl is below the canonical time precision");
    }
    ActionPlan plan =
        new ActionPlan(
            ids.next("plan"),
            authority.principalId(),
            authority.actionType(),
            authority.targetRef(),
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            authority.risk(),
            authority.policyVersion(),
            command.idempotencyKey(),
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            ids.next("approval"),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            authority.principalId(),
            now);
    ActionCapability capability =
        new ActionCapability(
            ids.next("capability"),
            authority.principalId(),
            authority.connector(),
            authority.audience(),
            authority.accountRef(),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            plan.idempotencyKey(),
            expiresAt,
            authority.maxProviderCalls());
    return new ActionAttempt(
        ids.next("attempt"),
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, now)),
        null);
  }

  private void assertAuthority(ActionAttempt attempt, Instant now) {
    attempt
        .capability()
        .assertAllows(
            attempt.plan(),
            authority.connector(),
            authority.audience(),
            authority.accountRef(),
            now);
  }

  private static ActionAttempt observedOrThrow(ActionAttemptStore.ClaimResult claim) {
    if (claim instanceof ActionAttemptStore.ClaimResult.Observed observed) {
      return observed.attempt();
    }
    if (claim instanceof ActionAttemptStore.ClaimResult.NotFound) {
      throw new NoSuchElementException("ActionAttempt not found");
    }
    throw new IllegalStateException("Action capability claim was rejected");
  }

  private static Instant canonicalTime(Instant instant) {
    return Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS);
  }
}
