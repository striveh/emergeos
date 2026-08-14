package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.ProviderActionRequest;
import io.emergeos.core.domain.ProviderResult;
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.ActionProvider;
import io.emergeos.core.port.ArtifactLineageStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecoverableActionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
  private static final String OWNER = "action-owner";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final String AUDIENCE = "adapter:simulated-provider";
  private static final String ACCOUNT = "simulated-account:action-owner";

  @Test
  void exactApprovalPlansOnceWithoutClaimingOrInvokingProvider() {
    ArtifactLineage artifact = artifact();
    InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    RecordingProvider provider = new RecordingProvider(attempts);
    RecoverableActionService service = service(attempts, provider, artifact);
    ActionApprovalScope scope =
        service.previewApprovalScope(
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash());
    PlanLocalApprovalCommand command =
        new PlanLocalApprovalCommand(
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            "exact-approval-nonce",
            scope.scopeSchema(),
            scope.scopeHash());

    RecoverableActionService.PlannedApproval created = service.planApproval(command);
    RecoverableActionService.PlannedApproval replayed = service.planApproval(command);
    attempts.artifact = revised(artifact);
    RecoverableActionService.PlannedApproval replayedAfterHeadAdvanced =
        service.planApproval(command);

    assertTrue(created.created());
    assertFalse(replayed.created());
    assertFalse(replayedAfterHeadAdvanced.created());
    assertEquals(created.attempt(), replayed.attempt());
    assertEquals(created.attempt(), replayedAfterHeadAdvanced.attempt());
    assertEquals(ActionAttemptStatus.PLANNED, created.attempt().status());
    assertEquals(0, created.attempt().capabilityUsedCalls());
    assertEquals(1, created.attempt().transitions().size());
    assertNull(created.attempt().receipt());
    assertTrue(provider.requests.isEmpty());
    assertEquals(
        created.attempt(),
        service.getPlannedExplicitApproval(created.attempt().attemptId()));
    attempts.current =
        transitioned(
            created.attempt(),
            ActionAttemptStatus.DISPATCHING,
            1,
            null,
            NOW.plusSeconds(1));
    assertThrows(
        java.util.NoSuchElementException.class,
        () -> service.getPlannedExplicitApproval(created.attempt().attemptId()));
    attempts.current = created.attempt();
    assertThrows(
        ApprovalStaleException.class,
        () ->
            service.planApproval(
                new PlanLocalApprovalCommand(
                    artifact.artifactId(),
                    artifact.current().version() + 1,
                    artifact.current().contentHash(),
                    "stale-approval-nonce",
                    scope.scopeSchema(),
                    scope.scopeHash())));
    assertEquals(created.attempt(), attempts.current);
  }

  @Test
  void persistsAndClaimsBeforeProviderThenReconcilesOneDefiniteReceipt() {
    ArtifactLineage artifact = artifact();
    InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    RecordingProvider provider = new RecordingProvider(attempts);
    provider.results.add(new ProviderResult.Unknown());
    provider.results.add(
        new ProviderResult.Succeeded(
            "sim-object-1",
            "sim-request-1",
            "simulated://provider/objects/sim-object-1",
            NOW.plusSeconds(2)));
    RecoverableActionService service = service(attempts, provider, artifact);

    ActionAttempt unknown =
        service.approve(
            new ApproveLocalActionCommand(
                artifact.artifactId(), artifact.current().contentHash(), "recoverable-key"));

    assertEquals(ActionAttemptStatus.UNKNOWN, unknown.status());
    assertEquals(1, unknown.capabilityUsedCalls());
    assertNull(unknown.receipt());
    assertEquals(
        List.of(ActionAttemptStatus.PLANNED, ActionAttemptStatus.DISPATCHING,
            ActionAttemptStatus.UNKNOWN),
        unknown.transitions().stream().map(ActionTransition::toStatus).toList());
    assertEquals(
        ProviderActionRequest.ProviderOperation.EXECUTE,
        provider.requests.getFirst().operation());
    assertThrows(
        java.util.NoSuchElementException.class,
        () -> service.getPlannedExplicitApproval(unknown.attemptId()));

    ActionAttempt succeeded = service.reconcile(unknown.attemptId());

    assertEquals(ActionAttemptStatus.SUCCEEDED, succeeded.status());
    assertEquals(2, succeeded.capabilityUsedCalls());
    assertEquals("sim-object-1", succeeded.receipt().externalId());
    assertEquals(true, succeeded.receipt().simulated());
    assertEquals(
        List.of(
            ActionAttemptStatus.PLANNED,
            ActionAttemptStatus.DISPATCHING,
            ActionAttemptStatus.UNKNOWN,
            ActionAttemptStatus.RECONCILING,
            ActionAttemptStatus.SUCCEEDED),
        succeeded.transitions().stream().map(ActionTransition::toStatus).toList());
    assertEquals(
        ProviderActionRequest.ProviderOperation.RECONCILE_ONLY,
        provider.requests.getLast().operation());

    assertEquals(succeeded, service.reconcile(succeeded.attemptId()));
    assertEquals(2, provider.requests.size());
  }

  @Test
  void preV17UnprovenUnknownHistoryCannotBeReconciledOrMutated() {
    ArtifactLineage artifact = artifact();
    InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    RecordingProvider provider = new RecordingProvider(attempts);
    RecoverableActionService service = service(attempts, provider, artifact);
    ActionApprovalScope scope =
        service.previewApprovalScope(
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash());
    ActionAttempt planned =
        service
            .planApproval(
                new PlanLocalApprovalCommand(
                    artifact.artifactId(),
                    artifact.current().version(),
                    artifact.current().contentHash(),
                    "pre-v17-unproven-reconcile",
                    scope.scopeSchema(),
                    scope.scopeHash()))
            .attempt();
    ActionAttempt dispatching =
        transitioned(planned, ActionAttemptStatus.DISPATCHING, 1, null, NOW);
    ActionAttempt provenUnknown =
        transitioned(dispatching, ActionAttemptStatus.UNKNOWN, 1, null, NOW);
    ActionAttempt historicalUnknown =
        new ActionAttempt(
            provenUnknown.attemptId(),
            provenUnknown.plan(),
            provenUnknown.approval(),
            provenUnknown.capability(),
            provenUnknown.status(),
            provenUnknown.capabilityUsedCalls(),
            provenUnknown.transitions(),
            provenUnknown.receipt());
    attempts.current = historicalUnknown;
    attempts.stateVersion = 2;
    provider.results.add(new ProviderResult.Unknown());

    assertEquals(
        ActionApprovalScope.PRE_V17_UNPROVEN,
        historicalUnknown.approvalScope().approvalOrigin());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        historicalUnknown.approvalScope().executionRoute());
    assertEquals(ActionAttemptStatus.UNKNOWN, historicalUnknown.status());
    int stateVersionBefore = attempts.stateVersion;
    List<ActionTransition> transitionsBefore = historicalUnknown.transitions();
    int usedCallsBefore = historicalUnknown.capabilityUsedCalls();
    ActionReceipt receiptBefore = historicalUnknown.receipt();

    RuntimeException rejection =
        assertThrows(
            RuntimeException.class,
            () -> service.reconcile(historicalUnknown.attemptId()),
            "PRE_V17_UNPROVEN_RECONCILE_NOT_REJECTED");

    assertTrue(
        rejection instanceof IllegalStateException
            || rejection instanceof java.util.NoSuchElementException
            || rejection.getClass().getName().startsWith("io.emergeos.core."),
        "PRE_V17_UNPROVEN_RECONCILE_REJECTION_NOT_TYPED");
    assertEquals(0, attempts.dispatchClaimCalls, "PRE_V17_UNPROVEN_DISPATCH_CLAIMED");
    assertEquals(
        0,
        attempts.reconciliationClaimCalls,
        "PRE_V17_UNPROVEN_RECONCILIATION_CLAIMED");
    assertEquals(
        stateVersionBefore,
        attempts.stateVersion,
        "PRE_V17_UNPROVEN_STATE_VERSION_MUTATED");
    assertEquals(
        0L,
        provider.requests.stream()
            .filter(
                request ->
                    request.operation() == ProviderActionRequest.ProviderOperation.EXECUTE)
            .count(),
        "PRE_V17_UNPROVEN_PROVIDER_DISPATCHED");
    assertEquals(
        0L,
        provider.requests.stream()
            .filter(
                request ->
                    request.operation()
                        == ProviderActionRequest.ProviderOperation.RECONCILE_ONLY)
            .count(),
        "PRE_V17_UNPROVEN_PROVIDER_RECONCILED");
    assertEquals(historicalUnknown, attempts.current, "PRE_V17_UNPROVEN_STORE_MUTATED");
    assertEquals(
        transitionsBefore,
        attempts.current.transitions(),
        "PRE_V17_UNPROVEN_TRANSITIONS_MUTATED");
    assertEquals(
        usedCallsBefore,
        attempts.current.capabilityUsedCalls(),
        "PRE_V17_UNPROVEN_USED_CALLS_MUTATED");
    assertEquals(
        receiptBefore,
        attempts.current.receipt(),
        "PRE_V17_UNPROVEN_RECEIPT_MUTATED");
  }

  @Test
  void noIdentifierAfterExecuteAndReconcileRemainsUnknownWithoutReceipt() {
    ArtifactLineage artifact = artifact();
    InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    RecordingProvider provider = new RecordingProvider(attempts);
    provider.results.add(new ProviderResult.Unknown());
    provider.results.add(new ProviderResult.Unknown());
    RecoverableActionService service = service(attempts, provider, artifact);

    ActionAttempt first =
        service.approve(
            new ApproveLocalActionCommand(
                artifact.artifactId(), artifact.current().contentHash(), "no-object-key"));
    ActionAttempt second = service.reconcile(first.attemptId());

    assertEquals(ActionAttemptStatus.UNKNOWN, second.status());
    assertEquals(2, second.capabilityUsedCalls());
    assertNull(second.receipt());
    assertEquals(
        List.of(
            ActionAttemptStatus.PLANNED,
            ActionAttemptStatus.DISPATCHING,
            ActionAttemptStatus.UNKNOWN,
            ActionAttemptStatus.RECONCILING,
            ActionAttemptStatus.UNKNOWN),
        second.transitions().stream().map(ActionTransition::toStatus).toList());
  }

  @Test
  void aDefinitiveProviderFailureCreatesOnlyAMatchingFailedReceipt() {
    ArtifactLineage artifact = artifact();
    InMemoryAttemptStore attempts = new InMemoryAttemptStore();
    RecordingProvider provider = new RecordingProvider(attempts);
    provider.results.add(
        new ProviderResult.Failed(
            "SIMULATED_REJECTION",
            "sim-request-rejected",
            "simulated://provider/requests/rejected",
            NOW.plusSeconds(1)));
    RecoverableActionService service = service(attempts, provider, artifact);

    ActionAttempt failed =
        service.approve(
            new ApproveLocalActionCommand(
                artifact.artifactId(), artifact.current().contentHash(), "failed-key"));

    assertEquals(ActionAttemptStatus.FAILED, failed.status());
    assertEquals("SIMULATED_REJECTION", failed.receipt().reasonCode());
    assertNull(failed.receipt().externalId());
    assertEquals(1, failed.capabilityUsedCalls());
  }

  private static RecoverableActionService service(
      InMemoryAttemptStore attempts, ActionProvider provider, ArtifactLineage artifact) {
    attempts.artifact = artifact;
    return new RecoverableActionService(
        attempts,
        new OwnedArtifactStore(artifact),
        provider,
        new SequencedIds(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new LocalActionAuthority(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            Duration.ofMinutes(5),
            2));
  }

  private static ArtifactLineage artifact() {
    String content = "synthetic approved local draft";
    return new ArtifactLineage(
        "artifact-1",
        OWNER,
        "capture-1",
        List.of(
            new ArtifactLineageEntry(
                1, content, ContentHashes.sha256(content), null, null, NOW.minusSeconds(60))));
  }

  private static ArtifactLineage revised(ArtifactLineage artifact) {
    String content = "synthetic revised local draft";
    return new ArtifactLineage(
        artifact.artifactId(),
        artifact.principalId(),
        artifact.sourceCaptureId(),
        List.of(
            artifact.current(),
            new ArtifactLineageEntry(
                2,
                content,
                ContentHashes.sha256(content),
                1,
                artifact.current().contentHash(),
                NOW.plusSeconds(1))));
  }

  private static ActionAttempt transitioned(
      ActionAttempt current,
      ActionAttemptStatus to,
      int usedCalls,
      ActionReceipt receipt,
      Instant now) {
    List<ActionTransition> transitions = new ArrayList<>(current.transitions());
    transitions.add(
        new ActionTransition(
            transitions.size() + 1, current.status(), to, now));
    return new ActionAttempt(
        current.attemptId(),
        current.plan(),
        current.approval(),
        current.capability(),
        to,
        usedCalls,
        transitions,
        receipt,
        current.approvalScope());
  }

  private static final class RecordingProvider implements ActionProvider {
    private final InMemoryAttemptStore attempts;
    private final List<ProviderActionRequest> requests = new ArrayList<>();
    private final List<ProviderResult> results = new ArrayList<>();

    private RecordingProvider(InMemoryAttemptStore attempts) {
      this.attempts = attempts;
    }

    @Override
    public ProviderResult executeOrReconcile(ProviderActionRequest request) {
      ActionAttempt durable = attempts.current;
      ActionAttemptStatus expected =
          request.operation() == ProviderActionRequest.ProviderOperation.EXECUTE
              ? ActionAttemptStatus.DISPATCHING
              : ActionAttemptStatus.RECONCILING;
      assertEquals(expected, durable.status(), "claim must commit before provider access");
      int expectedUsedCalls =
          request.operation() == ProviderActionRequest.ProviderOperation.EXECUTE
              ? requests.size() + 1
              : 2;
      assertEquals(expectedUsedCalls, durable.capabilityUsedCalls());
      requests.add(request);
      return results.removeFirst();
    }
  }

  private static final class InMemoryAttemptStore implements ActionAttemptStore {
    private ActionAttempt current;
    private int stateVersion;
    private int dispatchClaimCalls;
    private int reconciliationClaimCalls;

    @Override
    public PlanResult planOrFind(ActionAttempt proposed) {
      return plan(proposed, false);
    }

    @Override
    public PlanResult planApprovalOrFind(ActionAttempt proposed) {
      return plan(proposed, true);
    }

    private PlanResult plan(ActionAttempt proposed, boolean requireCurrentArtifact) {
      if (current == null) {
        if (requireCurrentArtifact
            && (artifact == null
                || !artifact.artifactId().equals(proposed.plan().artifactId())
                || artifact.current().version() != proposed.plan().artifactVersion()
                || !artifact.current().contentHash().equals(proposed.plan().artifactHash()))) {
          return new PlanResult.Stale();
        }
        current = proposed;
        return new PlanResult.Accepted(current, true);
      }
      boolean sameUniqueKey =
          current.connector().equals(proposed.connector())
              && current.accountRef().equals(proposed.accountRef())
              && current.plan().idempotencyKey().equals(proposed.plan().idempotencyKey());
      if (sameUniqueKey) {
        boolean sameRequest =
            current.plan().principalId().equals(proposed.plan().principalId())
                && current.plan().artifactId().equals(proposed.plan().artifactId())
                && current.plan().artifactVersion() == proposed.plan().artifactVersion()
                && current.plan().artifactHash().equals(proposed.plan().artifactHash());
        return sameRequest
            ? new PlanResult.Accepted(current, false)
            : new PlanResult.Conflict();
      }
      if (requireCurrentArtifact
          && (artifact == null
              || !artifact.artifactId().equals(proposed.plan().artifactId())
              || artifact.current().version() != proposed.plan().artifactVersion()
              || !artifact.current().contentHash().equals(proposed.plan().artifactHash()))) {
        return new PlanResult.Stale();
      }
      current = proposed;
      return new PlanResult.Accepted(current, true);
    }

    private ArtifactLineage artifact;

    @Override
    public ClaimResult claimDispatch(ActionAttempt expected, Instant now) {
      dispatchClaimCalls += 1;
      if (current.status() != ActionAttemptStatus.PLANNED) {
        return new ClaimResult.Observed(current);
      }
      current =
          transitioned(
              current,
              ActionAttemptStatus.DISPATCHING,
              current.capabilityUsedCalls() + 1,
              null,
              now);
      stateVersion += 1;
      return new ClaimResult.Claimed(current);
    }

    @Override
    public ClaimResult claimReconciliation(ActionAttempt expected, Instant now) {
      reconciliationClaimCalls += 1;
      if (current.status() != ActionAttemptStatus.UNKNOWN) {
        return new ClaimResult.Observed(current);
      }
      if (current.capabilityUsedCalls() >= current.capability().maxCalls()) {
        return new ClaimResult.Rejected();
      }
      current =
          transitioned(
              current,
              ActionAttemptStatus.RECONCILING,
              current.capabilityUsedCalls() + 1,
              null,
              now);
      stateVersion += 1;
      return new ClaimResult.Claimed(current);
    }

    @Override
    public ActionAttempt markUnknown(ActionAttempt claimed, Instant now) {
      current =
          transitioned(
              current,
              ActionAttemptStatus.UNKNOWN,
              current.capabilityUsedCalls(),
              null,
              now);
      stateVersion += 1;
      return current;
    }

    @Override
    public ActionAttempt complete(
        ActionAttempt claimed, ActionReceipt receipt, Instant now) {
      current =
          transitioned(
              current,
              receipt.outcome(),
              current.capabilityUsedCalls(),
              receipt,
              now);
      stateVersion += 1;
      return current;
    }

    @Override
    public Optional<ActionAttempt> findOwned(String principalId, String attemptId) {
      if (current != null
          && current.plan().principalId().equals(principalId)
          && current.attemptId().equals(attemptId)) {
        return Optional.of(current);
      }
      return Optional.empty();
    }
  }

  private record OwnedArtifactStore(ArtifactLineage artifact)
      implements ArtifactLineageStore {

    @Override
    public ArtifactLineage create(ArtifactLineage proposed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RevisionResult compareAndSwap(
        String principalId,
        String artifactId,
        int expectedBaseVersion,
        String expectedBaseHash,
        ArtifactLineageEntry proposed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ArtifactLineage> findOwned(String principalId, String artifactId) {
      return artifact.principalId().equals(principalId)
              && artifact.artifactId().equals(artifactId)
          ? Optional.of(artifact)
          : Optional.empty();
    }
  }

  private static final class SequencedIds
      implements io.emergeos.core.port.IdGenerator {
    private final Map<String, Integer> counts = new HashMap<>();

    @Override
    public String next(String prefix) {
      int next = counts.merge(prefix, 1, Integer::sum);
      return prefix + "-" + next;
    }
  }
}
