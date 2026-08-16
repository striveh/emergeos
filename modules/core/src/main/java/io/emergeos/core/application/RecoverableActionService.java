package io.emergeos.core.application;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionApprovalScope;
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
  private final LocalActionAuthority providerAuthority;
  private final LocalDraftboxAuthority localDraftboxAuthority;

  public RecoverableActionService(
      ActionAttemptStore attempts,
      ArtifactLineageStore artifacts,
      ActionProvider provider,
      IdGenerator ids,
      Clock clock,
      LocalActionAuthority authority) {
    this(
        attempts,
        artifacts,
        provider,
        ids,
        clock,
        authority,
        new LocalDraftboxAuthority(authority.principalId(), authority.capabilityTtl()));
  }

  public RecoverableActionService(
      ActionAttemptStore attempts,
      ArtifactLineageStore artifacts,
      ActionProvider provider,
      IdGenerator ids,
      Clock clock,
      LocalActionAuthority providerAuthority,
      LocalDraftboxAuthority localDraftboxAuthority) {
    this.attempts = Objects.requireNonNull(attempts, "attempts");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.providerAuthority =
        Objects.requireNonNull(providerAuthority, "providerAuthority");
    this.localDraftboxAuthority =
        Objects.requireNonNull(localDraftboxAuthority, "localDraftboxAuthority");
    if (!providerAuthority.principalId().equals(localDraftboxAuthority.principalId())) {
      throw new IllegalArgumentException(
          "provider and local Draftbox authority must bind the same principal");
    }
  }

  public ActionAttempt approve(ApproveLocalActionCommand command) {
    Objects.requireNonNull(command, "command");
    ArtifactLineage artifact = requireOwnedArtifact(command.artifactId());
    if (!artifact.current().contentHash().equals(command.approvedArtifactHash())) {
      throw new IllegalStateException("approved Artifact is not the current version");
    }
    Instant now = canonicalTime(clock.instant());
    ActionAttempt canonical =
        persistPlan(
                artifact,
                command.idempotencyKey(),
                now,
                ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
                ActionApprovalScope.SIMULATED_PROVIDER_V1)
            .attempt();
    if (canonical.status() != ActionAttemptStatus.PLANNED) {
      return canonical;
    }
    assertSimulatedProviderEligible(canonical);
    assertAuthority(canonical, now);
    ActionAttemptStore.ClaimResult claim = attempts.claimDispatch(canonical, now);
    if (claim instanceof ActionAttemptStore.ClaimResult.Claimed claimed) {
      return invoke(claimed.attempt(), ProviderActionRequest.ProviderOperation.EXECUTE);
    }
    return observedOrThrow(claim);
  }

  public PlannedApproval planApproval(PlanLocalApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    requireOwnedArtifact(command.artifactId());
    ActionApprovalScope scope =
        localDraftboxScope(
            command.artifactId(),
            command.approvedArtifactVersion(),
            command.approvedArtifactHash());
    if (!scope.scopeSchema().equals(command.approvedScopeSchema())
        || !scope.scopeHash().equals(command.approvedScopeHash())) {
      throw new ApprovalStaleException();
    }
    ActionAttempt proposed =
        planned(
            command.artifactId(),
            command.approvedArtifactVersion(),
            command.approvedArtifactHash(),
            command.approvalNonce(),
            canonicalTime(clock.instant()),
            scope,
            localDraftboxAuthority.capabilityTtl());
    ActionAttemptStore.PlanResult result = attempts.planApprovalOrFind(proposed);
    if (result instanceof ActionAttemptStore.PlanResult.Conflict) {
      throw new ActionIdempotencyConflictException();
    }
    if (result instanceof ActionAttemptStore.PlanResult.Stale) {
      throw new ApprovalStaleException();
    }
    ActionAttemptStore.PlanResult.Accepted accepted =
        (ActionAttemptStore.PlanResult.Accepted) result;
    return new PlannedApproval(accepted.attempt(), accepted.created());
  }

  public ActionAttempt reconcile(String attemptId) {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    ActionAttempt current = getSimulatedProviderAction(attemptId);
    if (current.status().isTerminal() || current.status() != ActionAttemptStatus.UNKNOWN) {
      return current;
    }
    Instant now = canonicalTime(clock.instant());
    assertSimulatedProviderEligible(current);
    assertAuthority(current, now);
    ActionAttemptStore.ClaimResult claim = attempts.claimReconciliation(current, now);
    if (claim instanceof ActionAttemptStore.ClaimResult.Claimed claimed) {
      return invoke(
          claimed.attempt(), ProviderActionRequest.ProviderOperation.RECONCILE_ONLY);
    }
    return observedOrThrow(claim);
  }

  public ActionApprovalScope previewApprovalScope(
      String artifactId, int artifactVersion, String artifactHash) {
    CreateArtifactCommand.requireIdentifier(artifactId, "artifactId");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    if (artifactHash == null || !artifactHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("artifactHash must be a lowercase SHA-256 hash");
    }
    ArtifactLineage artifact = requireOwnedArtifact(artifactId);
    if (artifact.current().version() != artifactVersion
        || !artifact.current().contentHash().equals(artifactHash)) {
      throw new ApprovalStaleException();
    }
    return localDraftboxScope(artifactId, artifactVersion, artifactHash);
  }

  public ActionAttempt get(String attemptId) {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    return attempts
        .findOwned(providerAuthority.principalId(), attemptId)
        .orElseThrow(() -> new NoSuchElementException("ActionAttempt not found"));
  }

  public ActionAttempt getExplicitApproval(String attemptId) {
    ActionAttempt attempt = get(attemptId);
    boolean explicit =
        ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT.equals(
            attempt.approvalScope().approvalOrigin());
    boolean historicalPlanned =
        ActionApprovalScope.LOCAL_DRAFTBOX_V1.equals(
                attempt.approvalScope().executionRoute())
            && attempt.status() == ActionAttemptStatus.PLANNED;
    boolean current =
        ActionApprovalScope.LOCAL_DRAFTBOX_V2.equals(
                attempt.approvalScope().executionRoute())
            && (attempt.status() == ActionAttemptStatus.PLANNED
                || attempt.status() == ActionAttemptStatus.SUCCEEDED);
    if (!explicit || (!historicalPlanned && !current)) {
      throw new NoSuchElementException("Action approval not found");
    }
    return attempt;
  }

  /** Retains the historical method name for source compatibility. */
  public ActionAttempt getPlannedExplicitApproval(String attemptId) {
    ActionAttempt attempt = getExplicitApproval(attemptId);
    if (attempt.status() != ActionAttemptStatus.PLANNED) {
      throw new NoSuchElementException("Action approval not found");
    }
    return attempt;
  }

  public ActionAttempt getSimulatedProviderAction(String attemptId) {
    ActionAttempt attempt = get(attemptId);
    if (!ActionApprovalScope.SIMULATED_PROVIDER_V1.equals(
        attempt.approvalScope().executionRoute())) {
      throw new NoSuchElementException("ActionAttempt not found");
    }
    return attempt;
  }

  private ActionAttempt invoke(
      ActionAttempt claimed, ProviderActionRequest.ProviderOperation operation) {
    ProviderResult result =
        provider.executeOrReconcile(
            new ProviderActionRequest(
                operation,
                claimed.plan(),
                providerAuthority.connector(),
                providerAuthority.audience(),
                providerAuthority.accountRef()));
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

  private ArtifactLineage requireOwnedArtifact(String artifactId) {
    return artifacts
        .findOwned(providerAuthority.principalId(), artifactId)
        .orElseThrow(() -> new NoSuchElementException("Artifact not found"));
  }

  private ActionAttemptStore.PlanResult.Accepted persistPlan(
      ArtifactLineage artifact,
      String idempotencyKey,
      Instant now,
      String approvalOrigin,
      String executionRoute) {
    ActionAttemptStore.PlanResult result =
        attempts.planOrFind(
            planned(
                artifact.artifactId(),
                artifact.current().version(),
                artifact.current().contentHash(),
                idempotencyKey,
                now,
                scope(
                    artifact.artifactId(),
                    artifact.current().version(),
                    artifact.current().contentHash(),
                    approvalOrigin,
                    executionRoute,
                    providerAuthority.principalId(),
                    providerAuthority.actionType(),
                    providerAuthority.targetRef(),
                    providerAuthority.risk(),
                    providerAuthority.policyVersion(),
                    providerAuthority.connector(),
                    providerAuthority.audience(),
                    providerAuthority.accountRef(),
                    providerAuthority.capabilityTtl(),
                    providerAuthority.maxProviderCalls()),
                providerAuthority.capabilityTtl()));
    if (result instanceof ActionAttemptStore.PlanResult.Conflict) {
      throw new ActionIdempotencyConflictException();
    }
    return (ActionAttemptStore.PlanResult.Accepted) result;
  }

  private ActionAttempt planned(
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String idempotencyKey,
      Instant now,
      ActionApprovalScope scope,
      java.time.Duration capabilityTtl) {
    Instant expiresAt =
        canonicalTime(now.plus(capabilityTtl));
    if (!expiresAt.isAfter(now)) {
      throw new IllegalStateException(
          "capabilityTtl is below the canonical time precision");
    }
    ActionPlan plan =
        new ActionPlan(
            ids.next("plan"),
            scope.configuredPrincipalId(),
            scope.actionType(),
            scope.targetRef(),
            artifactId,
            artifactVersion,
            artifactHash,
            scope.risk(),
            scope.policyVersion(),
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            ids.next("approval"),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            scope.configuredPrincipalId(),
            now);
    ActionCapability capability =
        new ActionCapability(
            ids.next("capability"),
            scope.configuredPrincipalId(),
            scope.connector(),
            scope.audience(),
            scope.accountRef(),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            plan.idempotencyKey(),
            expiresAt,
            scope.maxCalls());
    return new ActionAttempt(
        ids.next("attempt"),
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, now)),
        null,
        scope);
  }

  private ActionApprovalScope scope(
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String approvalOrigin,
      String executionRoute,
      String principalId,
      String actionType,
      String targetRef,
      io.emergeos.contracts.RiskLevel risk,
      String policyVersion,
      String connector,
      String audience,
      String accountRef,
      java.time.Duration capabilityTtl,
      int maxCalls) {
    long ttlMicros = Math.addExact(
        Math.multiplyExact(capabilityTtl.getSeconds(), 1_000_000L),
        capabilityTtl.getNano() / 1_000L);
    return new ActionApprovalScope(
        ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
        principalId,
        approvalOrigin,
        executionRoute,
        actionType,
        targetRef,
        artifactId,
        artifactVersion,
        artifactHash,
        risk,
        policyVersion,
        connector,
        audience,
        accountRef,
        ttlMicros,
        maxCalls);
  }

  private ActionApprovalScope localDraftboxScope(
      String artifactId, int artifactVersion, String artifactHash) {
    return scope(
        artifactId,
        artifactVersion,
        artifactHash,
        ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
        ActionApprovalScope.LOCAL_DRAFTBOX_V2,
        localDraftboxAuthority.principalId(),
        localDraftboxAuthority.actionType(),
        localDraftboxAuthority.targetRef(),
        localDraftboxAuthority.risk(),
        localDraftboxAuthority.policyVersion(),
        localDraftboxAuthority.connector(),
        localDraftboxAuthority.audience(),
        localDraftboxAuthority.accountRef(),
        localDraftboxAuthority.capabilityTtl(),
        localDraftboxAuthority.maxCalls());
  }

  public record PlannedApproval(ActionAttempt attempt, boolean created) {

    public PlannedApproval {
      Objects.requireNonNull(attempt, "attempt");
    }
  }

  private void assertAuthority(ActionAttempt attempt, Instant now) {
    attempt
        .capability()
        .assertAllows(
            attempt.plan(),
            providerAuthority.connector(),
            providerAuthority.audience(),
            providerAuthority.accountRef(),
            now);
  }

  private static void assertSimulatedProviderEligible(ActionAttempt attempt) {
    if (!ActionApprovalScope.LEGACY_SERVER_IMPLICIT.equals(
            attempt.approvalScope().approvalOrigin())
        || !ActionApprovalScope.SIMULATED_PROVIDER_V1.equals(
            attempt.approvalScope().executionRoute())) {
      throw new IllegalStateException(
          "ActionAttempt is not eligible for simulated provider execution or reconciliation");
    }
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
