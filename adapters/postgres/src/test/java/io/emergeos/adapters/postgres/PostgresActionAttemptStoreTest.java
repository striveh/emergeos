package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.ApproveLocalActionCommand;
import io.emergeos.core.application.LocalActionAuthority;
import io.emergeos.core.application.RecoverableActionService;
import io.emergeos.core.domain.ActionAttempt;
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
            "TRUNCATE TABLE agent_trace_events, agent_run_resource_bindings, "
                + "agent_worker_results, agent_runs, "
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
        original.receipt());
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
        null);
  }

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
