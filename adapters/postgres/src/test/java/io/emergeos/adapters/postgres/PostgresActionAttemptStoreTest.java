package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.ApproveLocalActionCommand;
import io.emergeos.core.application.LocalActionAuthority;
import io.emergeos.core.application.PlanLocalApprovalCommand;
import io.emergeos.core.application.RecoverableActionService;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.ProviderResult;
import io.emergeos.core.port.ActionAttemptStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresActionAttemptStoreTest {

  private static final String OWNER = "action-owner";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final String AUDIENCE = "adapter:simulated-provider";
  private static final String ACCOUNT = "simulated-account:action-owner";
  private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_actions")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DriverManagerDataSource dataSource;
  private static DataSourceTransactionManager transactionManager;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    transactionManager = new DataSourceTransactionManager(dataSource);
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void clearDatabase() {
    jdbc.sql(
            "TRUNCATE TABLE agent_graph_exact_attempt_heads_v16, "
                + "agent_graph_exact_attempt_events_v16, "
                + "agent_graph_exact_provider_attributions_v16, "
                + "agent_graph_exact_provider_validations_v16, "
                + "agent_graph_exact_tx_a_requirements_v15, "
                + "agent_graph_provider_validations, "
                + "agent_graph_provider_validation_keys, "
                + "agent_graph_attributed_failure_terminal_resumes, "
                + "agent_graph_attributed_failure_outcomes, "
                + "agent_graph_attempt_terminal_bindings, "
                + "agent_graph_attempt_candidates, "
                + "agent_graph_attempt_provider_attributions, "
                + "agent_graph_provider_session_intents, "
                + "agent_graph_attempt_seals, agent_graph_attempt_heads, "
                + "agent_graph_attempt_events, "
                + "agent_graph_attempt_run_bindings, agent_graph_attempts, "
                + "agent_trace_events, agent_run_resource_bindings, "
                + "agent_worker_results, agent_runs, "
                + "local_draft_creation_receipts, local_drafts, "
                + "action_receipts, action_attempt_transitions, action_attempts, "
                + "artifact_versions, artifacts, captures")
        .update();
  }

  @AfterAll
  static void releaseReferences() {
    jdbc = null;
    transactionManager = null;
    dataSource = null;
  }

  @Test
  void persistsEveryStateBudgetUseAndReceiptAcrossAdapterInstances() {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-1");
    ActionAttempt planned =
        planned(
            "attempt-1",
            "plan-1",
            "capability-1",
            "approval-1",
            artifact,
            "recoverable-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW);

    ActionAttempt canonical =
        assertInstanceOf(
                ActionAttemptStore.PlanResult.Accepted.class,
                store().planOrFind(planned))
            .attempt();
    ActionAttempt dispatching =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                secondStore().claimDispatch(canonical, NOW.plusSeconds(1)))
            .attempt();
    ActionAttempt unknown = store().markUnknown(dispatching, NOW.plusSeconds(2));
    reviseArtifact(artifact);
    ActionAttempt reconciling =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                secondStore().claimReconciliation(unknown, NOW.plusSeconds(3)))
            .attempt();
    ActionReceipt receipt =
        ActionReceipt.succeeded(
            "receipt-1",
            planned.attemptId(),
            "sim-object-1",
            "sim-request-1",
            "simulated://provider/objects/sim-object-1",
            NOW.plusSeconds(4),
            true);
    ActionAttempt succeeded =
        store().complete(reconciling, receipt, NOW.plusSeconds(4));

    assertEquals(ActionAttemptStatus.SUCCEEDED, succeeded.status());
    assertEquals(2, succeeded.capabilityUsedCalls());
    assertEquals(receipt, succeeded.receipt());
    assertEquals(
        List.of("PLANNED", "DISPATCHING", "UNKNOWN", "RECONCILING", "SUCCEEDED"),
        transitionStates(planned.attemptId()));
    assertEquals(2, capabilityUseSum(planned.attemptId()));
    assertEquals(1, receiptCount(planned.attemptId()));
    assertEquals(succeeded, secondStore().findOwned(OWNER, planned.attemptId()).orElseThrow());
  }

  @Test
  void providerClaimsRejectUnprovenAndExplicitScopesWithoutMutationButAllowLegacy() {
    ActionAttempt preV17Planned =
        persistPlanned(
            asPreV17Unproven(
                planned(
                    "attempt-pre-v17-planned",
                    "plan-pre-v17-planned",
                    "capability-pre-v17-planned",
                    "approval-pre-v17-planned",
                    ownArtifact(OWNER, "artifact-pre-v17-planned"),
                    "pre-v17-planned-key",
                    CONNECTOR,
                    AUDIENCE,
                    ACCOUNT,
                    NOW)));
    assertEquals(
        ActionApprovalScope.PRE_V17_UNPROVEN,
        preV17Planned.approvalScope().approvalOrigin());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        preV17Planned.approvalScope().executionRoute());
    assertEquals(ActionAttemptStatus.PLANNED, preV17Planned.status());
    assertEquals(0, preV17Planned.capabilityUsedCalls());
    assertNull(preV17Planned.receipt());
    ClaimSnapshot preV17Before = claimSnapshot(preV17Planned.attemptId());

    ActionAttemptStore.ClaimResult preV17Dispatch =
        store().claimDispatch(preV17Planned, NOW.plusSeconds(1));

    assertTrue(
        preV17Dispatch instanceof ActionAttemptStore.ClaimResult.Rejected,
        "PRE_V17_UNPROVEN_DISPATCH_CLAIMED");
    assertEquals(
        preV17Before,
        claimSnapshot(preV17Planned.attemptId()),
        "PRE_V17_UNPROVEN_DISPATCH_MUTATED");

    ActionAttempt explicitPlanned =
        persistPlanned(
            withScope(
                planned(
                    "attempt-explicit-dispatch",
                    "plan-explicit-dispatch",
                    "capability-explicit-dispatch",
                    "approval-explicit-dispatch",
                    ownArtifact(OWNER, "artifact-explicit-dispatch"),
                    "explicit-dispatch-key",
                    CONNECTOR,
                    AUDIENCE,
                    ACCOUNT,
                    NOW),
                ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
                ActionApprovalScope.LOCAL_DRAFTBOX_V1));
    assertEquals(
        ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
        explicitPlanned.approvalScope().approvalOrigin());
    assertEquals(
        ActionApprovalScope.LOCAL_DRAFTBOX_V1,
        explicitPlanned.approvalScope().executionRoute());
    assertEquals(ActionAttemptStatus.PLANNED, explicitPlanned.status());
    ClaimSnapshot explicitPlannedBefore = claimSnapshot(explicitPlanned.attemptId());

    ActionAttemptStore.ClaimResult explicitDispatch =
        store().claimDispatch(explicitPlanned, NOW.plusSeconds(1));

    assertTrue(
        explicitDispatch instanceof ActionAttemptStore.ClaimResult.Rejected,
        "EXPLICIT_LOCAL_APPROVAL_DISPATCH_CLAIMED");
    assertEquals(
        explicitPlannedBefore,
        claimSnapshot(explicitPlanned.attemptId()),
        "EXPLICIT_LOCAL_APPROVAL_DISPATCH_MUTATED");

    ActionAttempt legacyPlanned =
        persistPlanned(
            planned(
                "attempt-legacy-provider",
                "plan-legacy-provider",
                "capability-legacy-provider",
                "approval-legacy-provider",
                ownArtifact(OWNER, "artifact-legacy-provider"),
                "legacy-provider-key",
                CONNECTOR,
                AUDIENCE,
                ACCOUNT,
                NOW));
    assertEquals(
        ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
        legacyPlanned.approvalScope().approvalOrigin());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        legacyPlanned.approvalScope().executionRoute());
    ActionAttemptStore.ClaimResult legacyDispatch =
        store().claimDispatch(legacyPlanned, NOW.plusSeconds(1));
    ActionAttempt legacyDispatching =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                legacyDispatch,
                "LEGACY_SERVER_IMPLICIT_DISPATCH_NOT_CLAIMED")
            .attempt();
    ActionAttempt legacyUnknown = store().markUnknown(legacyDispatching, NOW.plusSeconds(2));

    ActionAttemptStore.ClaimResult legacyReconciliation =
        store().claimReconciliation(legacyUnknown, NOW.plusSeconds(3));

    assertInstanceOf(
        ActionAttemptStore.ClaimResult.Claimed.class,
        legacyReconciliation,
        "LEGACY_SERVER_IMPLICIT_RECONCILE_NOT_CLAIMED");
  }

  @Test
  void serviceCanonicalizesNanosecondClockBeforePlanHashRoundTrip() {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-nanosecond-clock");
    Instant nanosecondNow = NOW.plusNanos(123_456_789);
    RecoverableActionService service =
        new RecoverableActionService(
            store(),
            new PostgresArtifactLineageStore(dataSource, transactionManager),
            request -> new ProviderResult.Unknown(),
            prefix -> prefix + "-nanosecond-clock",
            Clock.fixed(nanosecondNow, java.time.ZoneOffset.UTC),
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

    ActionAttempt unknown =
        service.approve(
            new ApproveLocalActionCommand(
                artifact.artifactId(),
                artifact.current().contentHash(),
                "nanosecond-clock-key"));

    assertEquals(ActionAttemptStatus.UNKNOWN, unknown.status());
    assertEquals(0, unknown.plan().expiresAt().getNano() % 1_000);
    assertEquals(
        unknown,
        secondStore().findOwned(OWNER, unknown.attemptId()).orElseThrow());
    assertEquals(
        ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
        unknown.approvalScope().approvalOrigin());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        unknown.approvalScope().executionRoute());
    assertEquals(
        ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
        jdbc.sql("SELECT approval_origin FROM action_attempts WHERE attempt_id = :attemptId")
            .param("attemptId", unknown.attemptId())
            .query(String.class)
            .single());
  }

  @Test
  void explicitScopeRoundTripsWithDatabaseHashAndRejectsRawScopeTampering()
      throws Exception {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-explicit-scope");
    RecoverableActionService service =
        new RecoverableActionService(
            store(),
            new PostgresArtifactLineageStore(dataSource, transactionManager),
            request -> {
              throw new AssertionError("explicit planning must not invoke a provider");
            },
            prefix -> prefix + "-explicit-scope",
            Clock.fixed(NOW, java.time.ZoneOffset.UTC),
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
    ActionApprovalScope preview =
        service.previewApprovalScope(
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash());
    assertEquals(ActionApprovalScope.LOCAL_DRAFTBOX_V2, preview.executionRoute());
    ActionAttempt planned =
        service
            .planApproval(
                new PlanLocalApprovalCommand(
                    artifact.artifactId(),
                    artifact.current().version(),
                    artifact.current().contentHash(),
                    "explicit-scope-key",
                    preview.scopeSchema(),
                    preview.scopeHash()))
            .attempt();

    assertEquals(ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
        planned.approvalScope().approvalOrigin());
    assertEquals(ActionApprovalScope.LOCAL_DRAFTBOX_V2,
        planned.approvalScope().executionRoute());
    assertEquals(preview.scopeHash(), planned.approvalScope().scopeHash());
    assertEquals(
        preview.scopeHash(),
        jdbc.sql("SELECT scope_hash FROM action_attempts WHERE attempt_id = :attemptId")
            .param("attemptId", planned.attemptId())
            .query(String.class)
            .single());
    assertEquals(planned, secondStore().findOwned(OWNER, planned.attemptId()).orElseThrow());

    assertScopeUpdateRejected(planned.attemptId(), "approval_origin", "LEGACY_SERVER_IMPLICIT");
    assertScopeUpdateRejected(planned.attemptId(), "execution_route", "SIMULATED_PROVIDER_V1");
    assertScopeUpdateRejected(planned.attemptId(), "scope_schema", "emergeos.action-approval-scope.v2");
    assertScopeUpdateRejected(planned.attemptId(), "scope_capability_ttl_micros", 1L);
    assertScopeUpdateRejected(planned.attemptId(), "approval_id", "approval-tampered");
    assertScopeUpdateRejected(planned.attemptId(), "capability_id", "capability-tampered");
    assertScopeUpdateRejected(
        planned.attemptId(),
        "scope_hash",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertTransitionAndAttemptHistoryCannotBeRewrittenOrDeleted(planned.attemptId());
    assertEquals(planned, secondStore().findOwned(OWNER, planned.attemptId()).orElseThrow());

    ActionAttempt transitionable =
        assertInstanceOf(
                ActionAttemptStore.PlanResult.Accepted.class,
                store()
                    .planOrFind(
                        planned(
                            "attempt-identity-transition",
                            "plan-identity-transition",
                            "capability-identity-transition",
                            "approval-identity-transition",
                            ownArtifact(OWNER, "artifact-identity-transition"),
                            "identity-transition-key",
                            CONNECTOR,
                            AUDIENCE,
                            ACCOUNT,
                            NOW)))
            .attempt();
    ActionAttempt dispatching =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                store().claimDispatch(transitionable, NOW.plusSeconds(1)))
            .attempt();
    assertEquals(ActionAttemptStatus.DISPATCHING, dispatching.status());
    assertEquals(1, dispatching.capabilityUsedCalls());
  }

  @Test
  void exactApprovalPlansAgainstLockedHeadAndReplaysAfterHeadAdvances() {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-exact-approval");
    ActionAttempt proposed =
        planned(
            "attempt-exact-approval",
            "plan-exact-approval",
            "capability-exact-approval",
            "approval-exact-approval",
            artifact,
            "exact-approval-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW);

    ActionAttemptStore.PlanResult.Accepted created =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            store().planApprovalOrFind(proposed));
    assertTrue(created.created());
    reviseArtifact(artifact);

    ActionAttempt replayProposal =
        planned(
            "attempt-exact-approval-replay",
            "plan-exact-approval-replay",
            "capability-exact-approval-replay",
            "approval-exact-approval-replay",
            artifact,
            "exact-approval-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW.plusMillis(100));
    ActionAttemptStore.PlanResult.Accepted replayed =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            secondStore().planApprovalOrFind(replayProposal));
    assertFalse(replayed.created());
    assertEquals(created.attempt(), replayed.attempt());

    ActionAttempt staleNewNonce =
        planned(
            "attempt-stale-approval",
            "plan-stale-approval",
            "capability-stale-approval",
            "approval-stale-approval",
            artifact,
            "stale-approval-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW.plusMillis(200));
    assertInstanceOf(
        ActionAttemptStore.PlanResult.Stale.class,
        store().planApprovalOrFind(staleNewNonce));
    assertEquals(1, attemptCount("exact-approval-key"));
    assertEquals(0, attemptCount("stale-approval-key"));
  }

  @Test
  void committedRevisionWinsBlockedApprovalWithoutLeavingAStaleAttempt() throws Exception {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-revision-first");
    ActionAttempt proposed =
        planned(
            "attempt-revision-first",
            "plan-revision-first",
            "capability-revision-first",
            "approval-revision-first",
            artifact,
            "revision-first-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW);
    String revisedContent = "synthetic revision committed before exact approval";
    String revisedHash = ContentHashes.sha256(revisedContent);

    try (var revisionConnection = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      revisionConnection.setAutoCommit(false);
      try {
        try (var update =
            revisionConnection.prepareStatement(
                """
                UPDATE artifacts
                SET current_version = 2, current_hash = ?
                WHERE principal_id = ? AND artifact_id = ?
                  AND current_version = 1 AND current_hash = ?
                """)) {
          update.setString(1, revisedHash);
          update.setString(2, artifact.principalId());
          update.setString(3, artifact.artifactId());
          update.setString(4, artifact.current().contentHash());
          assertEquals(1, update.executeUpdate());
        }
        try (var insert =
            revisionConnection.prepareStatement(
                """
                INSERT INTO artifact_versions (
                    principal_id, artifact_id, version, content, content_hash,
                    base_version, base_hash, created_at
                ) VALUES (?, ?, 2, ?, ?, 1, ?, ?)
                """)) {
          insert.setString(1, artifact.principalId());
          insert.setString(2, artifact.artifactId());
          insert.setString(3, revisedContent);
          insert.setString(4, revisedHash);
          insert.setString(5, artifact.current().contentHash());
          insert.setTimestamp(6, java.sql.Timestamp.from(NOW.plusMillis(500)));
          assertEquals(1, insert.executeUpdate());
        }

        var approval = executor.submit(() -> secondStore().planApprovalOrFind(proposed));
        awaitBlockedApprovalHeadLock();
        assertFalse(approval.isDone());
        revisionConnection.commit();

        assertInstanceOf(
            ActionAttemptStore.PlanResult.Stale.class,
            approval.get(5, TimeUnit.SECONDS));
      } catch (Exception | Error failure) {
        revisionConnection.rollback();
        throw failure;
      }
    }
    assertEquals(0, attemptCount("revision-first-key"));
  }

  @Test
  void twoAdaptersPlanOneAttemptAndOnlyOneCanSpendTheDispatchUse() throws Exception {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-race");
    ActionAttempt first =
        planned(
            "attempt-a",
            "plan-a",
            "capability-a",
            "approval-a",
            artifact,
            "race-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW);
    ActionAttempt second =
        planned(
            "attempt-b",
            "plan-b",
            "capability-b",
            "approval-b",
            artifact,
            "race-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW.plusMillis(100));
    var start = new CountDownLatch(1);

    List<ActionAttemptStore.ClaimResult> claims;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture =
          executor.submit(
              () -> {
                start.await();
                ActionAttempt canonical =
                    ((ActionAttemptStore.PlanResult.Accepted) store().planOrFind(first))
                        .attempt();
                return store().claimDispatch(canonical, NOW.plusSeconds(1));
              });
      var secondFuture =
          executor.submit(
              () -> {
                start.await();
                ActionAttempt canonical =
                    ((ActionAttemptStore.PlanResult.Accepted) secondStore().planOrFind(second))
                        .attempt();
                return secondStore().claimDispatch(canonical, NOW.plusSeconds(1));
              });
      start.countDown();
      claims = List.of(firstFuture.get(), secondFuture.get());
    }

    assertEquals(
        1,
        claims.stream()
            .filter(ActionAttemptStore.ClaimResult.Claimed.class::isInstance)
            .count());
    assertEquals(
        1,
        claims.stream()
            .filter(ActionAttemptStore.ClaimResult.Observed.class::isInstance)
            .count());
    assertEquals(1, attemptCount("race-key"));
    String attemptId = attemptId("race-key");
    assertEquals("DISPATCHING", status(attemptId));
    assertEquals(1, capabilityUseSum(attemptId));
  }

  @Test
  void ownerReadsStayCoherentWhileAnotherAdapterCommitsTransitions()
      throws Exception {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-read-race");
    ActionAttempt planned =
        planned(
            "attempt-read-race",
            "plan-read-race",
            "capability-read-race",
            "approval-read-race",
            artifact,
            "read-race-key",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            NOW);
    ActionAttempt canonical =
        ((ActionAttemptStore.PlanResult.Accepted)
                store().planOrFind(planned))
            .attempt();
    AtomicBoolean writerFinished = new AtomicBoolean();
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(7)) {
      var writer =
          executor.submit(
              () -> {
                start.await();
                try {
                  ActionAttempt dispatching =
                      ((ActionAttemptStore.ClaimResult.Claimed)
                              store()
                                  .claimDispatch(
                                      canonical, NOW.plusSeconds(1)))
                          .attempt();
                  ActionAttempt unknown =
                      secondStore()
                          .markUnknown(dispatching, NOW.plusSeconds(2));
                  ActionAttempt reconciling =
                      ((ActionAttemptStore.ClaimResult.Claimed)
                              store()
                                  .claimReconciliation(
                                      unknown, NOW.plusSeconds(3)))
                          .attempt();
                  ActionReceipt receipt =
                      ActionReceipt.succeeded(
                          "receipt-read-race",
                          canonical.attemptId(),
                          "sim-object-read-race",
                          "sim-request-read-race",
                          "simulated://provider/objects/sim-object-read-race",
                          NOW.plusSeconds(4),
                          true);
                  return store()
                      .complete(reconciling, receipt, NOW.plusSeconds(4));
                } finally {
                  writerFinished.set(true);
                }
              });
      var readers =
          java.util.stream.IntStream.range(0, 6)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            start.await();
                            int reads = 0;
                            while (!writerFinished.get() || reads < 25) {
                              ActionAttempt observed =
                                  secondStore()
                                      .findOwned(OWNER, canonical.attemptId())
                                      .orElseThrow();
                              assertEquals(
                                  observed.status(),
                                  observed.transitions().getLast().toStatus());
                              reads++;
                            }
                            return reads;
                          }))
              .toList();
      start.countDown();

      assertEquals(ActionAttemptStatus.SUCCEEDED, writer.get().status());
      for (var reader : readers) {
        assertEquals(true, reader.get() >= 25);
      }
    }
  }

  @Test
  void exactCapabilityAndCurrentArtifactPredicateRejectEverySubstitution() {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-matrix");
    ActionAttempt canonical =
        ((ActionAttemptStore.PlanResult.Accepted)
                store()
                    .planOrFind(
                        planned(
                            "attempt-matrix",
                            "plan-matrix",
                            "capability-matrix",
                            "approval-matrix",
                            artifact,
                            "matrix-key",
                            CONNECTOR,
                            AUDIENCE,
                            ACCOUNT,
                            NOW)))
            .attempt();

    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            "other-connector",
            canonical.audience(),
            canonical.accountRef(),
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            "other-audience",
            canonical.accountRef(),
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            canonical.audience(),
            "other-account",
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            canonical.audience(),
            canonical.accountRef(),
            "other-plan",
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            canonical.audience(),
            canonical.accountRef(),
            canonical.plan().planId(),
            ContentHashes.sha256("other Artifact"),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            canonical.audience(),
            canonical.accountRef(),
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            "other-key",
            canonical.plan().expiresAt()));
    assertRejected(
        mutated(
            canonical,
            canonical.plan().principalId(),
            canonical.connector(),
            canonical.audience(),
            canonical.accountRef(),
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt().plusSeconds(1)));
    ActionAttempt foreign =
        mutated(
            canonical,
            "other-owner",
            canonical.connector(),
            canonical.audience(),
            canonical.accountRef(),
            canonical.plan().planId(),
            canonical.plan().artifactHash(),
            canonical.plan().idempotencyKey(),
            canonical.plan().expiresAt());
    assertInstanceOf(
        ActionAttemptStore.ClaimResult.NotFound.class,
        store().claimDispatch(foreign, NOW.plusSeconds(1)));

    reviseArtifact(artifact);
    assertRejected(canonical);
    assertEquals("PLANNED", status(canonical.attemptId()));
    assertEquals(0, capabilityUseSum(canonical.attemptId()));
  }

  @Test
  void terminalStateAndReceiptRollBackTogetherWhenReceiptInsertFails() {
    ArtifactLineage artifact = ownArtifact(OWNER, "artifact-rollback");
    ActionAttempt canonical =
        ((ActionAttemptStore.PlanResult.Accepted)
                store()
                    .planOrFind(
                        planned(
                            "attempt-rollback",
                            "plan-rollback",
                            "capability-rollback",
                            "approval-rollback",
                            artifact,
                            "rollback-key",
                            CONNECTOR,
                            AUDIENCE,
                            ACCOUNT,
                            NOW)))
            .attempt();
    ActionAttempt dispatching =
        ((ActionAttemptStore.ClaimResult.Claimed)
                store().claimDispatch(canonical, NOW.plusSeconds(1)))
            .attempt();
    installFailingReceiptTrigger();
    try {
      ActionReceipt receipt =
          ActionReceipt.succeeded(
              "receipt-rollback",
              canonical.attemptId(),
              "sim-object-rollback",
              "sim-request-rollback",
              "simulated://provider/objects/sim-object-rollback",
              NOW.plusSeconds(2),
              true);
      assertThrows(
          DataAccessException.class,
          () -> store().complete(dispatching, receipt, NOW.plusSeconds(2)));
    } finally {
      removeFailingReceiptTrigger();
    }

    assertEquals("DISPATCHING", status(canonical.attemptId()));
    assertEquals(List.of("PLANNED", "DISPATCHING"), transitionStates(canonical.attemptId()));
    assertEquals(0, receiptCount(canonical.attemptId()));
  }

  @Test
  void uniqueConnectorAccountKeyDoesNotChangeAcrossPrincipals() {
    ArtifactLineage ownerArtifact = ownArtifact(OWNER, "artifact-owner");
    ArtifactLineage changedArtifact = ownArtifact(OWNER, "artifact-changed");
    ArtifactLineage otherArtifact = ownArtifact("other-owner", "artifact-other");
    ActionAttempt first =
        planned(
            "attempt-owner",
            "plan-owner",
            "capability-owner",
            "approval-owner",
            ownerArtifact,
            "global-key",
            CONNECTOR,
            AUDIENCE,
            "shared-account",
            NOW);
    ActionAttempt other =
        planned(
            "attempt-other",
            "plan-other",
            "capability-other",
            "approval-other",
            otherArtifact,
            "global-key",
            CONNECTOR,
            AUDIENCE,
            "shared-account",
            NOW);
    ActionAttempt changed =
        planned(
            "attempt-changed",
            "plan-changed",
            "capability-changed",
            "approval-changed",
            changedArtifact,
            "global-key",
            CONNECTOR,
            AUDIENCE,
            "shared-account",
            NOW);

    assertInstanceOf(ActionAttemptStore.PlanResult.Accepted.class, store().planOrFind(first));
    assertInstanceOf(ActionAttemptStore.PlanResult.Conflict.class, store().planOrFind(changed));
    assertInstanceOf(ActionAttemptStore.PlanResult.Conflict.class, store().planOrFind(other));
    assertEquals(1, attemptCount("global-key"));
  }

  private static void assertRejected(ActionAttempt expected) {
    assertInstanceOf(
        ActionAttemptStore.ClaimResult.Rejected.class,
        store().claimDispatch(expected, NOW.plusSeconds(1)));
  }

  private static ActionAttempt mutated(
      ActionAttempt original,
      String principal,
      String connector,
      String audience,
      String account,
      String planId,
      String artifactHash,
      String idempotencyKey,
      Instant expiresAt) {
    ActionPlan plan =
        new ActionPlan(
            planId,
            principal,
            original.plan().actionType(),
            original.plan().targetRef(),
            original.plan().artifactId(),
            original.plan().artifactVersion(),
            artifactHash,
            original.plan().risk(),
            original.plan().policyVersion(),
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            original.approval().decisionId(),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            principal,
            original.approval().decidedAt());
    ActionCapability capability =
        new ActionCapability(
            original.capability().capabilityId(),
            principal,
            connector,
            audience,
            account,
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            plan.idempotencyKey(),
            expiresAt,
            original.capability().maxCalls());
    return new ActionAttempt(
        original.attemptId(),
        plan,
        approval,
        capability,
        original.status(),
        original.capabilityUsedCalls(),
        original.transitions(),
        original.receipt(),
        approvalScope(
            plan,
            approval,
            capability,
            original.approvalScope().approvalOrigin(),
            original.approvalScope().executionRoute()));
  }

  private static ActionAttempt planned(
      String attemptId,
      String planId,
      String capabilityId,
      String approvalId,
      ArtifactLineage artifact,
      String idempotencyKey,
      String connector,
      String audience,
      String account,
      Instant plannedAt) {
    ActionPlan plan =
        new ActionPlan(
            planId,
            artifact.principalId(),
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            idempotencyKey,
            plannedAt.plusSeconds(300));
    ApprovalDecision approval =
        new ApprovalDecision(
            approvalId,
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            plan.principalId(),
            plannedAt);
    ActionCapability capability =
        new ActionCapability(
            capabilityId,
            plan.principalId(),
            connector,
            audience,
            account,
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            plan.idempotencyKey(),
            plan.expiresAt(),
            2);
    return new ActionAttempt(
        attemptId,
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, plannedAt)),
        null,
        approvalScope(
            plan,
            approval,
            capability,
            ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
            ActionApprovalScope.SIMULATED_PROVIDER_V1));
  }

  private static ActionAttempt asPreV17Unproven(ActionAttempt attempt) {
    return new ActionAttempt(
        attempt.attemptId(),
        attempt.plan(),
        attempt.approval(),
        attempt.capability(),
        attempt.status(),
        attempt.capabilityUsedCalls(),
        attempt.transitions(),
        attempt.receipt());
  }

  private static ActionAttempt withScope(
      ActionAttempt attempt, String approvalOrigin, String executionRoute) {
    return new ActionAttempt(
        attempt.attemptId(),
        attempt.plan(),
        attempt.approval(),
        attempt.capability(),
        attempt.status(),
        attempt.capabilityUsedCalls(),
        attempt.transitions(),
        attempt.receipt(),
        approvalScope(
            attempt.plan(),
            attempt.approval(),
            attempt.capability(),
            approvalOrigin,
            executionRoute));
  }

  private static ActionApprovalScope approvalScope(
      ActionPlan plan,
      ApprovalDecision approval,
      ActionCapability capability,
      String approvalOrigin,
      String executionRoute) {
    long ttlMicros =
        Math.addExact(
            Math.multiplyExact(
                plan.expiresAt().getEpochSecond() - approval.decidedAt().getEpochSecond(),
                1_000_000L),
            (plan.expiresAt().getNano() - approval.decidedAt().getNano()) / 1_000L);
    return new ActionApprovalScope(
        ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
        plan.principalId(),
        approvalOrigin,
        executionRoute,
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

  private static ActionAttempt persistPlanned(ActionAttempt proposed) {
    return assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            store().planOrFind(proposed),
            "DIRECT_CLAIM_FIXTURE_PLAN_NOT_PERSISTED")
        .attempt();
  }

  private static ClaimSnapshot claimSnapshot(String attemptId) {
    return new ClaimSnapshot(
        secondStore().findOwned(OWNER, attemptId).orElseThrow(),
        jdbc.sql("SELECT state_version FROM action_attempts WHERE attempt_id = :attemptId")
            .param("attemptId", attemptId)
            .query(Integer.class)
            .single(),
        jdbc.sql(
                """
                SELECT capability_use_delta
                FROM action_attempt_transitions
                WHERE attempt_id = :attemptId
                ORDER BY sequence
                """)
            .param("attemptId", attemptId)
            .query(Integer.class)
            .list(),
        receiptCount(attemptId));
  }

  private record ClaimSnapshot(
      ActionAttempt attempt,
      int stateVersion,
      List<Integer> capabilityUseDeltas,
      int receiptCount) {}

  private static ArtifactLineage ownArtifact(String principalId, String artifactId) {
    String captureId = "capture-" + artifactId;
    String source = "synthetic source for " + artifactId;
    new PostgresCaptureStore(dataSource, transactionManager)
        .saveOrFindByNonce(
            new Capture(
                captureId,
                principalId,
                "nonce-" + artifactId,
                CaptureRequestHashes.sha256(
                    source,
                    CaptureSourceType.TEXT,
                    "s3-postgres-test",
                    DataClass.PERSONAL),
                source,
                CaptureSourceType.TEXT,
                "s3-postgres-test",
                DataClass.PERSONAL,
                NOW.minusSeconds(120)));
    String content = "synthetic approved draft for " + artifactId;
    return new PostgresArtifactLineageStore(dataSource, transactionManager)
        .create(
            new ArtifactLineage(
                artifactId,
                principalId,
                captureId,
                List.of(
                    new ArtifactLineageEntry(
                        1,
                        content,
                        ContentHashes.sha256(content),
                        null,
                        null,
                        NOW.minusSeconds(60)))));
  }

  private static void reviseArtifact(ArtifactLineage artifact) {
    String revised = "synthetic revised draft";
    new PostgresArtifactLineageStore(dataSource, transactionManager)
        .compareAndSwap(
            artifact.principalId(),
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            new ArtifactLineageEntry(
                2,
                revised,
                ContentHashes.sha256(revised),
                1,
                artifact.current().contentHash(),
                NOW.plusMillis(500)));
  }

  private static PostgresActionAttemptStore store() {
    return new PostgresActionAttemptStore(dataSource, transactionManager);
  }

  private static PostgresActionAttemptStore secondStore() {
    return new PostgresActionAttemptStore(dataSource, transactionManager);
  }

  private static int attemptCount(String key) {
    return jdbc.sql(
            "SELECT count(*) FROM action_attempts WHERE idempotency_key = :key")
        .param("key", key)
        .query(Integer.class)
        .single();
  }

  private static String attemptId(String key) {
    return jdbc.sql(
            "SELECT attempt_id FROM action_attempts WHERE idempotency_key = :key")
        .param("key", key)
        .query(String.class)
        .single();
  }

  private static String status(String attemptId) {
    return jdbc.sql(
            "SELECT status FROM action_attempts WHERE attempt_id = :attemptId")
        .param("attemptId", attemptId)
        .query(String.class)
        .single();
  }

  private static List<String> transitionStates(String attemptId) {
    return jdbc.sql(
            """
            SELECT to_status
            FROM action_attempt_transitions
            WHERE attempt_id = :attemptId
            ORDER BY sequence
            """)
        .param("attemptId", attemptId)
        .query(String.class)
        .list();
  }

  private static int capabilityUseSum(String attemptId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(capability_use_delta), 0)
            FROM action_attempt_transitions
            WHERE attempt_id = :attemptId
            """)
        .param("attemptId", attemptId)
        .query(Integer.class)
        .single();
  }

  private static int receiptCount(String attemptId) {
    return jdbc.sql(
            "SELECT count(*) FROM action_receipts WHERE attempt_id = :attemptId")
        .param("attemptId", attemptId)
        .query(Integer.class)
        .single();
  }

  private static void assertScopeUpdateRejected(
      String attemptId, String column, Object value) {
    String allowedColumn =
        switch (column) {
          case "approval_id", "capability_id", "approval_origin", "execution_route",
              "scope_schema", "scope_hash", "scope_capability_ttl_micros" -> column;
          default -> throw new IllegalArgumentException("unsupported test column");
        };
    DataAccessException failure = assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql(
                    "UPDATE action_attempts SET "
                        + allowedColumn
                        + " = :value WHERE attempt_id = :attemptId")
                .param("value", value)
                .param("attemptId", attemptId)
                .update());
    assertEquals(
        "23514",
        assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause())
            .getSQLState());
  }

  private static void assertTransitionAndAttemptHistoryCannotBeRewrittenOrDeleted(
      String attemptId) throws Exception {
    DataAccessException transitionUpdate =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                        "UPDATE action_attempt_transitions "
                            + "SET occurred_at = occurred_at + interval '1 microsecond' "
                            + "WHERE attempt_id = :attemptId AND sequence = 1")
                    .param("attemptId", attemptId)
                    .update());
    assertEquals(
        "23514",
        assertInstanceOf(java.sql.SQLException.class, transitionUpdate.getMostSpecificCause())
            .getSQLState());

    int transitionsBefore =
        jdbc.sql(
                "SELECT count(*) FROM action_attempt_transitions "
                    + "WHERE attempt_id = :attemptId")
            .param("attemptId", attemptId)
            .query(Integer.class)
            .single();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        var childAttempt = connection.setSavepoint("before_child_delete");
        try (var deleteChild =
            connection.prepareStatement(
                "DELETE FROM action_attempt_transitions WHERE attempt_id = ?")) {
          deleteChild.setString(1, attemptId);
          java.sql.SQLException childRejected =
              assertThrows(java.sql.SQLException.class, deleteChild::executeUpdate);
          assertEquals("23514", childRejected.getSQLState());
        }
        connection.rollback(childAttempt);
        try (var deleteAttempt =
            connection.prepareStatement(
                "DELETE FROM action_attempts WHERE attempt_id = ?")) {
          deleteAttempt.setString(1, attemptId);
          java.sql.SQLException attemptRejected =
              assertThrows(java.sql.SQLException.class, deleteAttempt::executeUpdate);
          assertEquals("23514", attemptRejected.getSQLState());
        }
      } finally {
        connection.rollback();
      }
    }
    assertEquals(1, attemptCount("explicit-scope-key"));
    assertEquals(
        transitionsBefore,
        jdbc.sql(
                "SELECT count(*) FROM action_attempt_transitions "
                    + "WHERE attempt_id = :attemptId")
            .param("attemptId", attemptId)
            .query(Integer.class)
            .single());
  }

  private static void awaitBlockedApprovalHeadLock() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      int blocked =
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM pg_stat_activity
                  WHERE datname = current_database()
                    AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock'
                    AND query LIKE '%FROM artifacts%'
                    AND query LIKE '%FOR UPDATE%'
                  """)
              .query(Integer.class)
              .single();
      if (blocked > 0) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("exact approval did not block on the Artifact head row");
  }

  private static void installFailingReceiptTrigger() {
    jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION fail_s3_receipt_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
              RAISE EXCEPTION 'synthetic S3 Receipt insert failure';
            END;
            $$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER fail_s3_receipt_insert_trigger
            BEFORE INSERT ON action_receipts
            FOR EACH ROW
            EXECUTE FUNCTION fail_s3_receipt_insert()
            """)
        .update();
  }

  private static void removeFailingReceiptTrigger() {
    jdbc.sql("DROP TRIGGER IF EXISTS fail_s3_receipt_insert_trigger ON action_receipts")
        .update();
    jdbc.sql("DROP FUNCTION IF EXISTS fail_s3_receipt_insert()").update();
  }
}
