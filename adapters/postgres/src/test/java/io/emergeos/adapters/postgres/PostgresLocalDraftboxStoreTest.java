package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.LocalDraftboxAuthority;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.LocalDraftCreationReceipt;
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.LocalDraftboxStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresLocalDraftboxStoreTest {

  private static final String OWNER = "local-draftbox-owner";
  private static final String ZERO_HASH = "0".repeat(64);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_local_draftbox")
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
    jdbc.sql("TRUNCATE TABLE captures CASCADE").update();
  }

  @AfterAll
  static void releaseReferences() {
    jdbc = null;
    transactionManager = null;
    dataSource = null;
  }

  @Test
  void directV2ExecuteAndReplayHaveOneCanonicalShapeAndOneDatabaseTime() {
    Instant approvedAt = databaseNow();
    ArtifactLineage artifact = ownArtifact("green", approvedAt);
    ActionAttempt planned = plannedV2("green", artifact, approvedAt);
    ActionAttemptStore.PlanResult.Accepted accepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            actionAttempts().planApprovalOrFind(planned));
    assertTrue(accepted.created());

    LocalDraftboxStore.ExecuteResult.Executed first =
        assertInstanceOf(
            LocalDraftboxStore.ExecuteResult.Executed.class,
            draftbox()
                .executeOwned(
                    OWNER,
                    planned.attemptId(),
                    planned.approvalScope().scopeSchema(),
                    planned.approvalScope().scopeHash(),
                    "draft-green-first",
                    "local-receipt-green-first"));
    assertTrue(first.created());
    assertEquals(ActionAttemptStatus.SUCCEEDED, first.attempt().status());
    assertEquals(1, first.attempt().capabilityUsedCalls());
    assertEquals(
        List.of(ActionAttemptStatus.PLANNED, ActionAttemptStatus.SUCCEEDED),
        first.attempt().transitions().stream().map(ActionTransition::toStatus).toList());
    assertEquals("draft-green-first", first.draft().draftId());
    assertEquals(artifact.current().contentHash(), first.draft().artifactHash());
    LocalDraftCreationReceipt localReceipt = first.attempt().localDraftReceipt();
    assertEquals(LocalDraftCreationReceipt.RECEIPT_TYPE, localReceipt.receiptType());
    assertEquals(ActionAttemptStatus.SUCCEEDED, localReceipt.outcome());
    assertFalse(localReceipt.simulated());

    Instant terminalTime = attemptUpdatedAt(planned.attemptId());
    assertEquals(terminalTime, first.attempt().transitions().get(1).occurredAt());
    assertEquals(terminalTime, first.draft().createdAt());
    assertEquals(terminalTime, localReceipt.occurredAt());

    DatabaseDigest firstDigest = digest(planned.attemptId());
    LocalDraftboxStore.ExecuteResult.Executed replay =
        assertInstanceOf(
            LocalDraftboxStore.ExecuteResult.Executed.class,
            draftbox()
                .executeOwned(
                    OWNER,
                    planned.attemptId(),
                    planned.approvalScope().scopeSchema(),
                    planned.approvalScope().scopeHash(),
                    "draft-green-replay-loser",
                    "local-receipt-green-replay-loser"));
    assertFalse(replay.created());
    assertEquals(first.attempt(), replay.attempt());
    assertEquals(first.draft(), replay.draft());
    assertEquals(firstDigest, digest(planned.attemptId()));
    System.out.println(
        "V18_LOCAL_DRAFTBOX_TX_RECEIPT first=CREATED replay=OBSERVED "
            + "route=LOCAL_DRAFTBOX_V2 transitions=PLANNED>SUCCEEDED "
            + "usedCalls=1 drafts=1 localReceipts=1 legacyReceipts=0 times=EXACT");
  }

  @Test
  void concurrentV2ExecuteHasOneWinnerAndOneCanonicalObserver() throws Exception {
    Instant approvedAt = databaseNow();
    ArtifactLineage artifact = ownArtifact("race", approvedAt);
    ActionAttempt planned = plannedV2("race", artifact, approvedAt);
    ActionAttemptStore.PlanResult.Accepted accepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            actionAttempts().planApprovalOrFind(planned));
    assertTrue(accepted.created());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    LocalDraftboxStore.ExecuteResult.Executed left;
    LocalDraftboxStore.ExecuteResult.Executed right;
    try {
      Future<LocalDraftboxStore.ExecuteResult.Executed> leftFuture =
          executor.submit(
              () ->
                  executeAfterStart(
                      planned,
                      ready,
                      start,
                      "draft-race-left",
                      "local-receipt-race-left"));
      Future<LocalDraftboxStore.ExecuteResult.Executed> rightFuture =
          executor.submit(
              () ->
                  executeAfterStart(
                      planned,
                      ready,
                      start,
                      "draft-race-right",
                      "local-receipt-race-right"));
      assertTrue(ready.await(5, TimeUnit.SECONDS), "both execute calls must reach the barrier");
      start.countDown();
      left = leftFuture.get(10, TimeUnit.SECONDS);
      right = rightFuture.get(10, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    List<LocalDraftboxStore.ExecuteResult.Executed> results = List.of(left, right);
    assertEquals(1L, results.stream().filter(LocalDraftboxStore.ExecuteResult.Executed::created).count());
    assertEquals(1L, results.stream().filter(result -> !result.created()).count());
    assertEquals(left.attempt(), right.attempt());
    assertEquals(left.draft(), right.draft());
    assertEquals(
        left.attempt(), actionAttempts().findOwned(OWNER, planned.attemptId()).orElseThrow());

    Set<String> candidateDraftIds = Set.of("draft-race-left", "draft-race-right");
    Map<String, String> receiptByDraft =
        Map.of(
            "draft-race-left", "local-receipt-race-left",
            "draft-race-right", "local-receipt-race-right");
    String winningDraftId = left.draft().draftId();
    assertTrue(candidateDraftIds.contains(winningDraftId));
    assertEquals(
        receiptByDraft.get(winningDraftId), left.attempt().localDraftReceipt().receiptId());

    DatabaseDigest finalDigest = digest(planned.attemptId());
    assertEquals(ActionAttemptStatus.SUCCEEDED.name(), finalDigest.attempt().status());
    assertEquals(2, finalDigest.attempt().stateVersion());
    assertEquals(1, finalDigest.attempt().usedCalls());
    assertEquals(2, finalDigest.transitions().size());
    assertEquals(
        1, finalDigest.transitions().stream().mapToInt(TransitionDigest::useDelta).sum());
    assertEquals(1, finalDigest.drafts());
    assertEquals(1, finalDigest.localReceipts());
    assertEquals(0, finalDigest.legacyReceipts());
    System.out.println(
        "V18_LOCAL_DRAFTBOX_RACE winners=1 observers=1 drafts=1 localReceipts=1");
  }

  @Test
  void capabilityExpiryIsCheckedWhenTheGuardedUpdateClaimsTheEffect() throws Exception {
    Instant fixtureTime = databaseNow();
    ArtifactLineage artifact = ownArtifact("db-time-expiry", fixtureTime);
    Instant approvedAt = databaseNow();
    ActionAttempt planned =
        plannedV2("db-time-expiry", artifact, approvedAt, Duration.ofSeconds(5));
    ActionAttemptStore.PlanResult.Accepted accepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            actionAttempts().planApprovalOrFind(planned));
    assertTrue(accepted.created());
    DatabaseDigest before = digest(planned.attemptId());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    LocalDraftboxStore.ExecuteResult result;
    boolean claimAfterExpiry;
    try {
      Future<LocalDraftboxStore.ExecuteResult> future;
      try (Connection lockConnection = dataSource.getConnection()) {
        lockConnection.setAutoCommit(false);
        boolean lockReleased = false;
        try {
          int lockHolderPid;
          try (Statement statement = lockConnection.createStatement()) {
            statement.execute("LOCK TABLE action_attempts IN SHARE MODE");
            try (ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
              assertTrue(rows.next());
              lockHolderPid = rows.getInt(1);
            }
          }

          future =
              executor.submit(
                  () ->
                      draftbox()
                          .executeOwned(
                              OWNER,
                              planned.attemptId(),
                              planned.approvalScope().scopeSchema(),
                              planned.approvalScope().scopeHash(),
                              "draft-db-time-expiry",
                              "local-receipt-db-time-expiry"));
          assertTrue(
              awaitBlockedRowExclusiveLock(lockHolderPid, Duration.ofSeconds(10)),
              "REAL_LOCAL_DRAFTBOX_DB_TIME_EXPIRY_HARNESS_BLOCK_MISSING");
          claimAfterExpiry =
              awaitCapabilityExpiry(planned.attemptId(), Duration.ofSeconds(10));
          assertTrue(
              claimAfterExpiry,
              "REAL_LOCAL_DRAFTBOX_DB_TIME_EXPIRY_HARNESS_EXPIRY_MISSING");

          lockConnection.commit();
          lockReleased = true;
        } finally {
          if (!lockReleased) {
            lockConnection.rollback();
          }
        }
      }
      result = future.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    String resultType = result.getClass().getSimpleName();
    String missingMarker =
        "REAL_LOCAL_DRAFTBOX_DB_TIME_EXPIRY_MISSING expected=STALE actual="
            + resultType
            + " claimAfterExpiry="
            + claimAfterExpiry;
    if (!(result instanceof LocalDraftboxStore.ExecuteResult.Stale)) {
      System.out.println(missingMarker);
    }
    assertInstanceOf(
        LocalDraftboxStore.ExecuteResult.Stale.class, result, () -> missingMarker);
    assertEquals(before, digest(planned.attemptId()));
  }

  @Test
  void storedPlanHashMismatchIsRejectedWithoutAnyAttemptOrEffectDelta() {
    Instant approvedAt = databaseNow();
    ArtifactLineage artifact = ownArtifact("bad-plan-hash", approvedAt);
    ActionAttempt canonical = plannedV2("bad-plan-hash", artifact, approvedAt);
    insertRawPlanned(canonical, ZERO_HASH, canonical.approvalScope());
    DatabaseDigest before = digest(canonical.attemptId());

    LocalDraftboxStore.ExecuteResult result = null;
    RuntimeException rejection = null;
    try {
      result =
          draftbox()
              .executeOwned(
                  OWNER,
                  canonical.attemptId(),
                  canonical.approvalScope().scopeSchema(),
                  canonical.approvalScope().scopeHash(),
                  "draft-bad-plan-hash",
                  "local-receipt-bad-plan-hash");
    } catch (RuntimeException failure) {
      rejection = failure;
    }

    assertTrue(
        rejection != null || !(result instanceof LocalDraftboxStore.ExecuteResult.Executed),
        "a stored planHash mismatch must fail closed before local effect creation");
    assertEquals(before, digest(canonical.attemptId()));
    System.out.println(
        "V18_LOCAL_DRAFTBOX_FULL_BINDING planHash=MISMATCH outcome=REJECTED digest=UNCHANGED");
  }

  @Test
  void storedCapabilityTtlMismatchIsRejectedWithoutAnyAttemptOrEffectDelta() {
    Instant approvedAt = databaseNow();
    ArtifactLineage artifact = ownArtifact("bad-ttl", approvedAt);
    ActionAttempt canonical = plannedV2("bad-ttl", artifact, approvedAt);
    ActionApprovalScope mismatchedScope =
        scopeWithTtl(
            canonical.approvalScope(),
            canonical.approvalScope().capabilityTtlMicros() + 1L);
    insertRawPlanned(canonical, canonical.plan().planHash(), mismatchedScope);
    DatabaseDigest before = digest(canonical.attemptId());

    LocalDraftboxStore.ExecuteResult result = null;
    RuntimeException rejection = null;
    try {
      result =
          draftbox()
              .executeOwned(
                  OWNER,
                  canonical.attemptId(),
                  mismatchedScope.scopeSchema(),
                  mismatchedScope.scopeHash(),
                  "draft-bad-ttl",
                  "local-receipt-bad-ttl");
    } catch (RuntimeException failure) {
      rejection = failure;
    }

    assertTrue(
        rejection != null || !(result instanceof LocalDraftboxStore.ExecuteResult.Executed),
        "a stored capability TTL mismatch must fail closed before local effect creation");
    assertEquals(before, digest(canonical.attemptId()));
    System.out.println(
        "V18_LOCAL_DRAFTBOX_FULL_BINDING ttl=MISMATCH outcome=REJECTED digest=UNCHANGED");
  }

  @Test
  void v2TerminalRejectsLegacyReceiptAndCanonicalReadRemainsUnchanged() {
    Instant approvedAt = databaseNow();
    ArtifactLineage artifact = ownArtifact("dual-receipt", approvedAt);
    ActionAttempt planned = plannedV2("dual-receipt", artifact, approvedAt);
    assertInstanceOf(
        ActionAttemptStore.PlanResult.Accepted.class,
        actionAttempts().planApprovalOrFind(planned));
    assertInstanceOf(
        LocalDraftboxStore.ExecuteResult.Executed.class,
        draftbox()
            .executeOwned(
                OWNER,
                planned.attemptId(),
                planned.approvalScope().scopeSchema(),
                planned.approvalScope().scopeHash(),
                "draft-dual-receipt",
                "local-receipt-dual-receipt"));
    ActionAttempt canonicalBefore =
        actionAttempts().findOwned(OWNER, planned.attemptId()).orElseThrow();
    DatabaseDigest digestBefore = digest(planned.attemptId());

    DataAccessException failure =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                        """
                        INSERT INTO action_receipts (
                            principal_id, attempt_id, receipt_id, outcome, external_id,
                            reason_code, provider_request_id, raw_response_ref,
                            occurred_at, simulated
                        ) VALUES (
                            :principalId, :attemptId, :receiptId, 'SUCCEEDED', :externalId,
                            NULL, :providerRequestId, :rawResponseRef,
                            :occurredAt, TRUE
                        )
                        """)
                    .param("principalId", OWNER)
                    .param("attemptId", planned.attemptId())
                    .param("receiptId", "legacy-receipt-dual-receipt")
                    .param("externalId", "synthetic-legacy-effect")
                    .param("providerRequestId", "synthetic-legacy-request")
                    .param("rawResponseRef", "memory://redacted")
                    .param("occurredAt", Timestamp.from(databaseNow()))
                    .update());
    assertEquals(
        "23514",
        assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getSQLState());
    assertEquals(
        canonicalBefore,
        actionAttempts().findOwned(OWNER, planned.attemptId()).orElseThrow());
    assertEquals(digestBefore, digest(planned.attemptId()));
    System.out.println(
        "V18_LOCAL_DRAFTBOX_DUAL_RECEIPT_FENCE legacyReceipt=FENCED canonical=UNCHANGED");
  }

  private static PostgresActionAttemptStore actionAttempts() {
    return new PostgresActionAttemptStore(dataSource, transactionManager);
  }

  private static LocalDraftboxStore.ExecuteResult.Executed executeAfterStart(
      ActionAttempt planned,
      CountDownLatch ready,
      CountDownLatch start,
      String proposedDraftId,
      String proposedReceiptId)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("concurrent execute start barrier timed out");
    }
    return assertInstanceOf(
        LocalDraftboxStore.ExecuteResult.Executed.class,
        draftbox()
            .executeOwned(
                OWNER,
                planned.attemptId(),
                planned.approvalScope().scopeSchema(),
                planned.approvalScope().scopeHash(),
                proposedDraftId,
                proposedReceiptId));
  }

  private static PostgresLocalDraftboxStore draftbox() {
    return new PostgresLocalDraftboxStore(dataSource, transactionManager, actionAttempts());
  }

  private static Instant databaseNow() {
    return jdbc.sql("SELECT date_trunc('microseconds', clock_timestamp())")
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  private static boolean awaitBlockedRowExclusiveLock(int lockHolderPid, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      boolean blocked =
          jdbc.sql(
                  """
                  SELECT EXISTS (
                      SELECT 1
                      FROM pg_locks waiting
                      WHERE waiting.locktype = 'relation'
                        AND waiting.relation = 'action_attempts'::regclass
                        AND waiting.mode = 'RowExclusiveLock'
                        AND NOT waiting.granted
                        AND :lockHolderPid = ANY(pg_blocking_pids(waiting.pid))
                  )
                  """)
              .param("lockHolderPid", lockHolderPid)
              .query(Boolean.class)
              .single();
      if (blocked) {
        return true;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static boolean awaitCapabilityExpiry(String attemptId, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      boolean expired =
          jdbc.sql(
                  """
                  SELECT clock_timestamp() >= capability_expires_at
                  FROM action_attempts
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                  """)
              .param("principalId", OWNER)
              .param("attemptId", attemptId)
              .query(Boolean.class)
              .single();
      if (expired) {
        return true;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static ArtifactLineage ownArtifact(String suffix, Instant now) {
    String artifactId = "artifact-" + suffix;
    String captureId = "capture-" + suffix;
    String source = "synthetic source " + suffix;
    new PostgresCaptureStore(dataSource, transactionManager)
        .saveOrFindByNonce(
            new Capture(
                captureId,
                OWNER,
                "nonce-" + suffix,
                CaptureRequestHashes.sha256(
                    source, CaptureSourceType.TEXT, "v18-local-draftbox-test", DataClass.PERSONAL),
                source,
                CaptureSourceType.TEXT,
                "v18-local-draftbox-test",
                DataClass.PERSONAL,
                now.minusSeconds(2)));
    String content = "synthetic local draft " + suffix;
    return new PostgresArtifactLineageStore(dataSource, transactionManager)
        .create(
            new ArtifactLineage(
                artifactId,
                OWNER,
                captureId,
                List.of(
                    new ArtifactLineageEntry(
                        1,
                        content,
                        ContentHashes.sha256(content),
                        null,
                        null,
                        now.minusSeconds(1)))));
  }

  private static ActionAttempt plannedV2(
      String suffix, ArtifactLineage artifact, Instant approvedAt) {
    return plannedV2(suffix, artifact, approvedAt, Duration.ofMinutes(5));
  }

  private static ActionAttempt plannedV2(
      String suffix, ArtifactLineage artifact, Instant approvedAt, Duration capabilityTtl) {
    Instant expiresAt = approvedAt.plus(capabilityTtl);
    String planId = "plan-" + suffix;
    String idempotencyKey = "idempotency-" + suffix;
    ActionPlan plan =
        new ActionPlan(
            planId,
            OWNER,
            LocalDraftboxAuthority.ACTION_TYPE,
            LocalDraftboxAuthority.TARGET_REF,
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            RiskLevel.REVERSIBLE,
            LocalDraftboxAuthority.POLICY_VERSION,
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-" + suffix,
            planId,
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            OWNER,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-" + suffix,
            OWNER,
            LocalDraftboxAuthority.CONNECTOR,
            LocalDraftboxAuthority.AUDIENCE,
            LocalDraftboxAuthority.accountRefFor(OWNER),
            planId,
            plan.planHash(),
            plan.artifactHash(),
            idempotencyKey,
            expiresAt,
            LocalDraftboxAuthority.MAX_CALLS);
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            OWNER,
            ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
            ActionApprovalScope.LOCAL_DRAFTBOX_V2,
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
            Duration.between(approvedAt, expiresAt).toNanos() / 1_000L,
            capability.maxCalls());
    return new ActionAttempt(
        "attempt-" + suffix,
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, approvedAt)),
        null,
        scope);
  }

  private static void insertRawPlanned(
      ActionAttempt canonical, String storedPlanHash, ActionApprovalScope storedScope) {
    ActionPlan plan = canonical.plan();
    ActionCapability capability = canonical.capability();
    Instant plannedAt = canonical.transitions().getFirst().occurredAt();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbc.sql(
            """
            INSERT INTO action_attempts (
                principal_id, attempt_id, connector, account_ref, idempotency_key,
                status, state_version, capability_used_calls, capability_max_calls,
                plan_id, plan_hash, action_type, target_ref,
                artifact_id, artifact_version, artifact_hash,
                risk, policy_version, plan_expires_at,
                approval_id, approved_at,
                capability_id, capability_subject, capability_connector,
                capability_audience, capability_account_ref,
                capability_plan_id, capability_plan_hash, capability_artifact_hash,
                capability_idempotency_key, capability_expires_at,
                approval_principal_basis, configured_principal_id,
                approval_origin, execution_route, scope_schema, scope_hash,
                scope_capability_ttl_micros, created_at, updated_at
            ) VALUES (
                :principalId, :attemptId, :connector, :accountRef, :idempotencyKey,
                'PLANNED', 1, 0, :maxCalls,
                :planId, :planHash, :actionType, :targetRef,
                :artifactId, :artifactVersion, :artifactHash,
                :risk, :policyVersion, :expiresAt,
                :approvalId, :approvedAt,
                :capabilityId, :subject, :capabilityConnector,
                :audience, :capabilityAccountRef,
                :capabilityPlanId, :capabilityPlanHash, :capabilityArtifactHash,
                :capabilityIdempotencyKey, :capabilityExpiresAt,
                :approvalPrincipalBasis, :configuredPrincipalId,
                :approvalOrigin, :executionRoute, :scopeSchema, :scopeHash,
                :scopeCapabilityTtlMicros, :plannedAt, :plannedAt
            )
            """)
        .param("principalId", plan.principalId())
        .param("attemptId", canonical.attemptId())
        .param("connector", capability.connector())
        .param("accountRef", capability.accountRef())
        .param("idempotencyKey", plan.idempotencyKey())
        .param("maxCalls", capability.maxCalls())
        .param("planId", plan.planId())
        .param("planHash", storedPlanHash)
        .param("actionType", plan.actionType())
        .param("targetRef", plan.targetRef())
        .param("artifactId", plan.artifactId())
        .param("artifactVersion", plan.artifactVersion())
        .param("artifactHash", plan.artifactHash())
        .param("risk", plan.risk().name())
        .param("policyVersion", plan.policyVersion())
        .param("expiresAt", Timestamp.from(plan.expiresAt()))
        .param("approvalId", canonical.approval().decisionId())
        .param("approvedAt", Timestamp.from(canonical.approval().decidedAt()))
        .param("capabilityId", capability.capabilityId())
        .param("subject", capability.subject())
        .param("capabilityConnector", capability.connector())
        .param("audience", capability.audience())
        .param("capabilityAccountRef", capability.accountRef())
        .param("capabilityPlanId", capability.actionPlanId())
        .param("capabilityPlanHash", storedPlanHash)
        .param("capabilityArtifactHash", capability.artifactHash())
        .param("capabilityIdempotencyKey", capability.idempotencyKey())
        .param("capabilityExpiresAt", Timestamp.from(capability.expiresAt()))
        .param("approvalPrincipalBasis", storedScope.principalBasis())
        .param("configuredPrincipalId", storedScope.configuredPrincipalId())
        .param("approvalOrigin", storedScope.approvalOrigin())
        .param("executionRoute", storedScope.executionRoute())
        .param("scopeSchema", storedScope.scopeSchema())
        .param("scopeHash", storedScope.scopeHash())
        .param("scopeCapabilityTtlMicros", storedScope.capabilityTtlMicros())
                  .param("plannedAt", Timestamp.from(plannedAt))
                  .update();
              jdbc.sql(
            """
            INSERT INTO action_attempt_transitions (
                principal_id, attempt_id, sequence, from_status, to_status,
                capability_use_delta, occurred_at
            ) VALUES (:principalId, :attemptId, 1, NULL, 'PLANNED', 0, :occurredAt)
            """)
                  .param("principalId", plan.principalId())
                  .param("attemptId", canonical.attemptId())
                  .param("occurredAt", Timestamp.from(plannedAt))
                  .update();
            });
  }

  private static ActionApprovalScope scopeWithTtl(
      ActionApprovalScope source, long capabilityTtlMicros) {
    return new ActionApprovalScope(
        source.principalBasis(),
        source.configuredPrincipalId(),
        source.approvalOrigin(),
        source.executionRoute(),
        source.actionType(),
        source.targetRef(),
        source.artifactId(),
        source.artifactVersion(),
        source.artifactHash(),
        source.risk(),
        source.policyVersion(),
        source.connector(),
        source.audience(),
        source.accountRef(),
        capabilityTtlMicros,
        source.maxCalls());
  }

  private static Instant attemptUpdatedAt(String attemptId) {
    return jdbc.sql("SELECT updated_at FROM action_attempts WHERE attempt_id = :attemptId")
        .param("attemptId", attemptId)
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  private static DatabaseDigest digest(String attemptId) {
    AttemptDigest attempt =
        jdbc.sql(
                """
                SELECT status, state_version, capability_used_calls, xmin::text, updated_at
                FROM action_attempts
                WHERE attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(
                (row, ignored) ->
                    new AttemptDigest(
                        row.getString("status"),
                        row.getInt("state_version"),
                        row.getInt("capability_used_calls"),
                        row.getString("xmin"),
                        row.getTimestamp("updated_at").toInstant()))
            .single();
    List<TransitionDigest> transitions =
        jdbc.sql(
                """
                SELECT sequence, from_status, to_status, capability_use_delta,
                       xmin::text, occurred_at
                FROM action_attempt_transitions
                WHERE attempt_id = :attemptId
                ORDER BY sequence
                """)
            .param("attemptId", attemptId)
            .query(
                (row, ignored) ->
                    new TransitionDigest(
                        row.getInt("sequence"),
                        row.getString("from_status"),
                        row.getString("to_status"),
                        row.getInt("capability_use_delta"),
                        row.getString("xmin"),
                        row.getTimestamp("occurred_at").toInstant()))
            .list();
    return new DatabaseDigest(
        attempt,
        transitions,
        count("local_drafts", attemptId),
        count("local_draft_creation_receipts", attemptId),
        count("action_receipts", attemptId));
  }

  private static int count(String table, String attemptId) {
    String allowedTable =
        switch (table) {
          case "local_drafts", "local_draft_creation_receipts", "action_receipts" -> table;
          default -> throw new IllegalArgumentException("unsupported digest table");
        };
    return jdbc.sql("SELECT count(*) FROM " + allowedTable + " WHERE attempt_id = :attemptId")
        .param("attemptId", attemptId)
        .query(Integer.class)
        .single();
  }

  private record AttemptDigest(
      String status, int stateVersion, int usedCalls, String xmin, Instant updatedAt) {}

  private record TransitionDigest(
      int sequence,
      String fromStatus,
      String toStatus,
      int useDelta,
      String xmin,
      Instant occurredAt) {}

  private record DatabaseDigest(
      AttemptDigest attempt,
      List<TransitionDigest> transitions,
      int drafts,
      int localReceipts,
      int legacyReceipts) {}
}
