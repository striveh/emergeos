package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.emergeos.core.domain.LocalDraft;
import io.emergeos.core.domain.LocalDraftCreationReceipt;
import io.emergeos.core.domain.LocalDraftUndoReceipt;
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.LocalDraftboxStore;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresLocalDraftUndoStoreTest {

  private static final String OWNER = "logical-undo-owner";
  private static final String INTRUDER = "logical-undo-intruder";
  private static final String UNDO_SCHEMA = "emergeos.local-draft-undo-scope.v1";
  private static final Instant HOSTILE_SENTINEL = Instant.parse("1900-01-01T00:00:00Z");
  private static final long RACE_GATE_KEY = 1_900_190_019L;

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_logical_undo_store")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DriverManagerDataSource dataSource;
  private static DataSourceTransactionManager transactionManager;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    dataSource = newDataSource();
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
  void createdReplayAndCanonicalReadKeepCreationTruthAndUseExactDatabaseTime() {
    CreationFixture fixture = seedCreation(OWNER, "canonical", HOSTILE_SENTINEL);
    CreationTruthDigest beforeTruth = creationTruth(fixture.attempt().attemptId());
    String nonce = "undo-nonce-canonical";
    String scopeHash = independentScopeHash(fixture, nonce);

    GlobalDatabaseDigest invalidBefore = globalDatabaseDigest();
    LocalDraftboxStore.UndoResult wrongHash =
        draftbox()
            .undoOwned(
                OWNER,
                fixture.attempt().attemptId(),
                nonce,
                UNDO_SCHEMA,
                differentLowercaseHash(scopeHash),
                "undo-receipt-wrong-hash");
    assertInstanceOf(LocalDraftboxStore.UndoResult.Stale.class, wrongHash);
    assertOpaque(wrongHash, nonce, scopeHash, "undo-receipt-wrong-hash");
    assertEquals(invalidBefore, globalDatabaseDigest());

    LocalDraftboxStore.UndoResult wrongSchema =
        draftbox()
            .undoOwned(
                OWNER,
                fixture.attempt().attemptId(),
                nonce,
                "emergeos.local-draft-undo-scope.v0",
                scopeHash,
                "undo-receipt-wrong-schema");
    assertInstanceOf(LocalDraftboxStore.UndoResult.Stale.class, wrongSchema);
    assertOpaque(wrongSchema, nonce, scopeHash, "undo-receipt-wrong-schema");
    assertEquals(invalidBefore, globalDatabaseDigest());

    Instant databaseBefore = databaseNow();
    LocalDraftboxStore.UndoResult.Undone created =
        assertInstanceOf(
            LocalDraftboxStore.UndoResult.Undone.class,
            draftbox()
                .undoOwned(
                    OWNER,
                    fixture.attempt().attemptId(),
                    nonce,
                    UNDO_SCHEMA,
                    scopeHash,
                    "undo-receipt-canonical"));
    Instant databaseAfter = databaseNow();

    assertTrue(created.created());
    assertEquals(fixture.attempt(), created.creationAttempt());
    LocalDraftUndoReceipt receipt = created.receipt();
    assertEquals("undo-receipt-canonical", receipt.receiptId());
    assertEquals(fixture.attempt().attemptId(), receipt.creationAttemptId());
    assertEquals(fixture.draft().draftId(), receipt.draftId());
    assertEquals(fixture.creationReceipt().receiptId(), receipt.creationReceiptId());
    assertEquals(fixture.draft().artifactId(), receipt.artifactId());
    assertEquals(fixture.draft().artifactVersion(), receipt.artifactVersion());
    assertEquals(fixture.draft().artifactHash(), receipt.artifactHash());
    assertEquals(UNDO_SCHEMA, receipt.scopeSchema());
    assertEquals(scopeHash, receipt.scopeHash());
    assertEquals(nonce, receipt.undoNonce());
    assertEquals("LOCAL_DRAFT_LOGICALLY_UNDONE_V1", receipt.receiptType());
    assertEquals("LOGICALLY_UNDONE", receipt.effect());
    assertEquals("CAPTURE_ARTIFACT_HISTORY_RETAINED", receipt.retention());
    assertEquals(ActionAttemptStatus.SUCCEEDED, receipt.outcome());
    assertFalse(receipt.simulated());
    assertFalse(receipt.occurredAt().isBefore(databaseBefore));
    assertFalse(receipt.occurredAt().isAfter(databaseAfter));
    assertNotEquals(HOSTILE_SENTINEL, receipt.occurredAt());
    assertEquals(receipt.occurredAt(), storedOccurredAt(fixture.attempt().attemptId()));
    assertEquals("ACTIVE", rawDraftState(fixture.draft().draftId()));
    assertEquals(beforeTruth, creationTruth(fixture.attempt().attemptId()));

    assertEquals(receipt, draftbox().findUndoReceiptOwned(OWNER, fixture.attempt()).orElseThrow());
    UndoTruthDigest firstUndo = undoTruth();
    LocalDraftboxStore.UndoResult.Undone replay =
        assertInstanceOf(
            LocalDraftboxStore.UndoResult.Undone.class,
            draftbox()
                .undoOwned(
                    OWNER,
                    fixture.attempt().attemptId(),
                    nonce,
                    UNDO_SCHEMA,
                    scopeHash,
                    "undo-receipt-canonical-loser"));
    assertFalse(replay.created());
    assertEquals(created.creationAttempt(), replay.creationAttempt());
    assertEquals(receipt, replay.receipt());
    assertEquals(firstUndo, undoTruth());
    assertEquals(beforeTruth, creationTruth(fixture.attempt().attemptId()));

    System.out.println(
        "LOCAL_DRAFT_LOGICAL_UNDO created=1 replay=1 canonical=EXACT dbTime=BOUNDED "
            + "hostileSentinel=IGNORED rawDraft=ACTIVE creationTruth=UNCHANGED");
  }

  @Test
  void sameNonceRaceAcrossIndependentStoresHasOneCreatedAndOneReplay() throws Exception {
    CreationFixture fixture = seedCreation(OWNER, "same-nonce-race", databaseNow());
    CreationTruthDigest beforeTruth = creationTruth(fixture.attempt().attemptId());
    String nonce = "undo-nonce-race-same";
    String scopeHash = independentScopeHash(fixture, nonce);
    StoreHandle left = isolatedStore();
    StoreHandle right = isolatedStore();

    List<LocalDraftboxStore.UndoResult> results =
        runDatabaseControlledRace(
            () ->
                left.store()
                    .undoOwned(
                        OWNER,
                        fixture.attempt().attemptId(),
                        nonce,
                        UNDO_SCHEMA,
                        scopeHash,
                        "undo-receipt-race-left"),
            () ->
                right
                    .store()
                    .undoOwned(
                        OWNER,
                        fixture.attempt().attemptId(),
                        nonce,
                        UNDO_SCHEMA,
                        scopeHash,
                        "undo-receipt-race-right"));

    LocalDraftboxStore.UndoResult.Undone winner =
        assertInstanceOf(LocalDraftboxStore.UndoResult.Undone.class, results.get(0));
    LocalDraftboxStore.UndoResult.Undone replay =
        assertInstanceOf(LocalDraftboxStore.UndoResult.Undone.class, results.get(1));
    assertTrue(winner.created());
    assertFalse(replay.created());
    assertEquals("undo-receipt-race-left", winner.receipt().receiptId());
    assertEquals(winner.receipt(), replay.receipt());
    assertEquals(1, undoCount());
    assertEquals(beforeTruth, creationTruth(fixture.attempt().attemptId()));
    assertEquals("ACTIVE", rawDraftState(fixture.draft().draftId()));
  }

  @Test
  void differentValidNonceRaceOnOneDraftHasOneCreatedAndOneConflict() throws Exception {
    CreationFixture fixture = seedCreation(OWNER, "different-nonce-race", databaseNow());
    CreationTruthDigest beforeTruth = creationTruth(fixture.attempt().attemptId());
    String leftNonce = "undo-nonce-different-left";
    String rightNonce = "undo-nonce-different-right";
    StoreHandle left = isolatedStore();
    StoreHandle right = isolatedStore();

    List<LocalDraftboxStore.UndoResult> results =
        runDatabaseControlledRace(
            () ->
                left.store()
                    .undoOwned(
                        OWNER,
                        fixture.attempt().attemptId(),
                        leftNonce,
                        UNDO_SCHEMA,
                        independentScopeHash(fixture, leftNonce),
                        "undo-receipt-different-left"),
            () ->
                right
                    .store()
                    .undoOwned(
                        OWNER,
                        fixture.attempt().attemptId(),
                        rightNonce,
                        UNDO_SCHEMA,
                        independentScopeHash(fixture, rightNonce),
                        "undo-receipt-different-right"));

    LocalDraftboxStore.UndoResult.Undone winner =
        assertInstanceOf(LocalDraftboxStore.UndoResult.Undone.class, results.get(0));
    LocalDraftboxStore.UndoResult conflict = results.get(1);
    assertInstanceOf(LocalDraftboxStore.UndoResult.Conflict.class, conflict);
    assertTrue(winner.created());
    assertEquals(leftNonce, winner.receipt().undoNonce());
    assertEquals("undo-receipt-different-left", winner.receipt().receiptId());
    assertOpaque(
        conflict,
        winner.receipt().receiptId(),
        leftNonce,
        rightNonce,
        independentScopeHash(fixture, leftNonce),
        independentScopeHash(fixture, rightNonce));
    assertEquals(1, undoCount());
    assertEquals(beforeTruth, creationTruth(fixture.attempt().attemptId()));
    assertEquals("ACTIVE", rawDraftState(fixture.draft().draftId()));
  }

  @Test
  void sameNonceForAnotherDraftConflictsWithoutLeakingOrMutatingItsTruth() {
    CreationFixture first = seedCreation(OWNER, "nonce-first", databaseNow());
    CreationFixture second = seedCreation(OWNER, "nonce-second", databaseNow());
    String nonce = "undo-nonce-reused-across-drafts";
    LocalDraftboxStore.UndoResult.Undone firstUndo =
        assertInstanceOf(
            LocalDraftboxStore.UndoResult.Undone.class,
            draftbox()
                .undoOwned(
                    OWNER,
                    first.attempt().attemptId(),
                    nonce,
                    UNDO_SCHEMA,
                    independentScopeHash(first, nonce),
                    "undo-receipt-nonce-first"));
    assertTrue(firstUndo.created());
    GlobalDatabaseDigest beforeConflict = globalDatabaseDigest();

    LocalDraftboxStore.UndoResult conflict =
        draftbox()
            .undoOwned(
                OWNER,
                second.attempt().attemptId(),
                nonce,
                UNDO_SCHEMA,
                independentScopeHash(second, nonce),
                "undo-receipt-nonce-second");
    assertInstanceOf(LocalDraftboxStore.UndoResult.Conflict.class, conflict);
    assertOpaque(
        conflict,
        firstUndo.receipt().receiptId(),
        nonce,
        firstUndo.receipt().scopeHash(),
        "undo-receipt-nonce-second");
    assertTrue(draftbox().findUndoReceiptOwned(OWNER, second.attempt()).isEmpty());
    assertEquals(beforeConflict, globalDatabaseDigest());
    assertEquals("ACTIVE", rawDraftState(second.draft().draftId()));
  }

  @Test
  void sameNonceRaceAcrossIndependentDraftsReachesUniqueInsertAndHasOneConflict() throws Exception {
    CreationFixture leftFixture = seedCreation(OWNER, "nonce-race-left", databaseNow());
    CreationFixture rightFixture = seedCreation(OWNER, "nonce-race-right", databaseNow());
    CreationTruthDigest leftBefore = creationTruth(leftFixture.attempt().attemptId());
    CreationTruthDigest rightBefore = creationTruth(rightFixture.attempt().attemptId());
    String nonce = "undo-nonce-independent-draft-race";
    String leftScopeHash = independentScopeHash(leftFixture, nonce);
    String rightScopeHash = independentScopeHash(rightFixture, nonce);
    String leftReceiptId = "undo-receipt-independent-left";
    String rightReceiptId = "undo-receipt-independent-right";
    String winnerApplicationName = "logical-undo-unique-winner";
    String loserApplicationName = "logical-undo-unique-loser";
    StoreHandle left = isolatedStore(winnerApplicationName);
    StoreHandle right = isolatedStore(loserApplicationName);

    List<LocalDraftboxStore.UndoResult> results =
        runUniqueTransactionRace(
            new UniqueRaceGate(
                winnerApplicationName,
                leftFixture.attempt().attemptId(),
                leftFixture.draft().draftId(),
                nonce,
                leftReceiptId),
            loserApplicationName,
            () ->
                left.store()
                    .undoOwned(
                        OWNER,
                        leftFixture.attempt().attemptId(),
                        nonce,
                        UNDO_SCHEMA,
                        leftScopeHash,
                        leftReceiptId),
            () ->
                right
                    .store()
                    .undoOwned(
                        OWNER,
                        rightFixture.attempt().attemptId(),
                        nonce,
                        UNDO_SCHEMA,
                        rightScopeHash,
                        rightReceiptId));

    LocalDraftboxStore.UndoResult.Undone winner =
        assertInstanceOf(LocalDraftboxStore.UndoResult.Undone.class, results.get(0));
    LocalDraftboxStore.UndoResult.Conflict conflict =
        assertInstanceOf(LocalDraftboxStore.UndoResult.Conflict.class, results.get(1));
    assertTrue(winner.created());
    assertEquals(leftFixture.attempt().attemptId(), winner.receipt().creationAttemptId());
    assertEquals(leftReceiptId, winner.receipt().receiptId());
    assertEquals(nonce, winner.receipt().undoNonce());
    assertOpaque(
        conflict,
        nonce,
        leftScopeHash,
        rightScopeHash,
        leftReceiptId,
        rightReceiptId,
        leftFixture.attempt().attemptId(),
        rightFixture.attempt().attemptId(),
        leftFixture.draft().draftId(),
        rightFixture.draft().draftId());
    assertEquals(1, undoCount());
    assertEquals(
        winner.receipt(),
        draftbox().findUndoReceiptOwned(OWNER, leftFixture.attempt()).orElseThrow());
    assertTrue(draftbox().findUndoReceiptOwned(OWNER, rightFixture.attempt()).isEmpty());
    assertEquals(leftBefore, creationTruth(leftFixture.attempt().attemptId()));
    assertEquals(rightBefore, creationTruth(rightFixture.attempt().attemptId()));
    assertEquals("ACTIVE", rawDraftState(leftFixture.draft().draftId()));
    assertEquals("ACTIVE", rawDraftState(rightFixture.draft().draftId()));

    GlobalDatabaseDigest afterRace = globalDatabaseDigest();
    LocalDraftboxStore.UndoResult replayedLoser =
        draftbox()
            .undoOwned(
                OWNER,
                rightFixture.attempt().attemptId(),
                nonce,
                UNDO_SCHEMA,
                rightScopeHash,
                rightReceiptId);
    assertInstanceOf(LocalDraftboxStore.UndoResult.Conflict.class, replayedLoser);
    assertOpaque(
        replayedLoser,
        nonce,
        winner.receipt().receiptId(),
        winner.receipt().creationAttemptId(),
        rightFixture.attempt().attemptId(),
        rightFixture.draft().draftId(),
        rightScopeHash,
        rightReceiptId);
    assertEquals(afterRace, globalDatabaseDigest());
  }

  @Test
  void crossPrincipalIdLookupAndUndoAreNotFoundWhileBoundReadFailsIntegrity() {
    CreationFixture fixture = seedCreation(OWNER, "cross-principal", databaseNow());
    String nonce = "undo-nonce-cross-principal";
    assertInstanceOf(
        LocalDraftboxStore.UndoResult.Undone.class,
        draftbox()
            .undoOwned(
                OWNER,
                fixture.attempt().attemptId(),
                nonce,
                UNDO_SCHEMA,
                independentScopeHash(fixture, nonce),
                "undo-receipt-cross-principal"));
    GlobalDatabaseDigest beforeIntruder = globalDatabaseDigest();

    assertTrue(actionAttempts().findOwned(INTRUDER, fixture.attempt().attemptId()).isEmpty());
    ActionApprovalIntegrityException hiddenRead =
        assertThrows(
            ActionApprovalIntegrityException.class,
            () -> draftbox().findUndoReceiptOwned(INTRUDER, fixture.attempt()));
    assertEquals(ActionApprovalIntegrityException.PUBLIC_MESSAGE, hiddenRead.getMessage());
    assertFalse(hiddenRead.toString().contains(fixture.attempt().attemptId()));
    assertFalse(hiddenRead.toString().contains(fixture.draft().draftId()));
    assertFalse(hiddenRead.toString().contains(nonce));
    assertFalse(hiddenRead.toString().contains(independentScopeHash(fixture, nonce)));
    assertFalse(hiddenRead.toString().contains("undo-receipt-cross-principal"));
    assertEquals(beforeIntruder, globalDatabaseDigest());
    LocalDraftboxStore.UndoResult hidden =
        draftbox()
            .undoOwned(
                INTRUDER,
                fixture.attempt().attemptId(),
                nonce,
                UNDO_SCHEMA,
                independentScopeHash(fixture, nonce),
                "undo-receipt-intruder");
    assertInstanceOf(LocalDraftboxStore.UndoResult.NotFound.class, hidden);
    assertOpaque(
        hidden,
        fixture.attempt().attemptId(),
        nonce,
        independentScopeHash(fixture, nonce),
        "undo-receipt-cross-principal");
    assertEquals(beforeIntruder, globalDatabaseDigest());
    assertEquals("ACTIVE", rawDraftState(fixture.draft().draftId()));
  }

  private static List<LocalDraftboxStore.UndoResult> runDatabaseControlledRace(
      UndoCall winnerCall, UndoCall observerCall) throws Exception {
    installRaceGate();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<LocalDraftboxStore.UndoResult> winner = null;
    Future<LocalDraftboxStore.UndoResult> observer = null;
    boolean terminated;
    try (Connection gate = dataSource.getConnection()) {
      int gatePid;
      try (Statement statement = gate.createStatement()) {
        statement.execute("SELECT pg_catalog.pg_advisory_lock(" + RACE_GATE_KEY + ")");
        try (ResultSet row = statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
          assertTrue(row.next());
          gatePid = row.getInt(1);
        }
      }

      winner = executor.submit(winnerCall::execute);
      int winnerPid = awaitBlockedBy(gatePid, Duration.ofSeconds(5));
      assertTrue(winnerPid > 0, "winner never reached the database advisory gate");

      observer = executor.submit(observerCall::execute);
      assertTrue(
          awaitBlockedBy(winnerPid, Duration.ofSeconds(5)) > 0,
          "observer never waited behind the uncommitted winner");

      unlockRaceGate(gate);
      return List.of(winner.get(10, TimeUnit.SECONDS), observer.get(10, TimeUnit.SECONDS));
    } finally {
      cancelIfRunning(winner);
      cancelIfRunning(observer);
      executor.shutdownNow();
      terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
      uninstallRaceGate();
      assertTrue(terminated, "logical Undo race executor did not terminate");
    }
  }

  private static List<LocalDraftboxStore.UndoResult> runUniqueTransactionRace(
      UniqueRaceGate expectedWinner,
      String loserApplicationName,
      UndoCall winnerCall,
      UndoCall loserCall)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<LocalDraftboxStore.UndoResult> winner = null;
    Future<LocalDraftboxStore.UndoResult> loser = null;
    boolean terminated;
    try {
      installWinnerPostInsertGate(expectedWinner);
      try (Connection gate = dataSource.getConnection()) {
        int gatePid;
        try (Statement statement = gate.createStatement()) {
          statement.execute("SELECT pg_catalog.pg_advisory_lock(" + RACE_GATE_KEY + ")");
          try (ResultSet row = statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
            assertTrue(row.next());
            gatePid = row.getInt(1);
          }
        }

        winner = executor.submit(winnerCall::execute);
        int winnerPid = awaitWinnerPostInsertGate(gatePid, expectedWinner, Duration.ofSeconds(5));
        assertTrue(
            winnerPid > 0,
            "designated winner never completed its row INSERT and reached the post-insert gate");

        loser = executor.submit(loserCall::execute);
        int loserPid =
            awaitUniqueTransactionWait(winnerPid, loserApplicationName, Duration.ofSeconds(5));
        assertTrue(
            loserPid > 0,
            "loser never waited on the winner transaction while enforcing the nonce unique key");

        unlockRaceGate(gate);
        return List.of(winner.get(10, TimeUnit.SECONDS), loser.get(10, TimeUnit.SECONDS));
      }
    } finally {
      cancelIfRunning(winner);
      cancelIfRunning(loser);
      executor.shutdownNow();
      terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
      uninstallRaceGate();
      assertTrue(terminated, "unique transaction-wait race executor did not terminate");
    }
  }

  private static void installWinnerPostInsertGate(UniqueRaceGate expectedWinner) {
    uninstallRaceGate();
    jdbc.sql(
            """
            CREATE FUNCTION test_local_draft_undo_insert_gate()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS $gate$
            BEGIN
                IF current_setting('application_name') = %s
                   AND NEW.creation_attempt_id = %s
                   AND NEW.draft_id = %s
                   AND NEW.undo_nonce = %s
                   AND NEW.receipt_id = %s THEN
                    PERFORM pg_catalog.pg_advisory_xact_lock(1900190019);
                END IF;
                RETURN NULL;
            END
            $gate$
            """
                .formatted(
                    sqlLiteral(expectedWinner.applicationName()),
                    sqlLiteral(expectedWinner.attemptId()),
                    sqlLiteral(expectedWinner.draftId()),
                    sqlLiteral(expectedWinner.nonce()),
                    sqlLiteral(expectedWinner.receiptId())))
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER aaa_test_local_draft_undo_insert_gate
            AFTER INSERT ON local_draft_undo_receipts
            FOR EACH ROW
            EXECUTE FUNCTION test_local_draft_undo_insert_gate()
            """)
        .update();
  }

  private static void installRaceGate() {
    uninstallRaceGate();
    jdbc.sql(
            """
            CREATE FUNCTION test_local_draft_undo_insert_gate()
            RETURNS TRIGGER
            LANGUAGE plpgsql
            AS $gate$
            BEGIN
                PERFORM pg_catalog.pg_advisory_xact_lock(1900190019);
                RETURN NEW;
            END
            $gate$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER aaa_test_local_draft_undo_insert_gate
            BEFORE INSERT ON local_draft_undo_receipts
            FOR EACH ROW
            EXECUTE FUNCTION test_local_draft_undo_insert_gate()
            """)
        .update();
  }

  private static void uninstallRaceGate() {
    jdbc.sql(
            """
            DROP TRIGGER IF EXISTS aaa_test_local_draft_undo_insert_gate
            ON local_draft_undo_receipts
            """)
        .update();
    jdbc.sql("DROP FUNCTION IF EXISTS test_local_draft_undo_insert_gate()").update();
  }

  private static int awaitBlockedBy(int blockerPid, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      int waitingPid =
          jdbc.sql(
                  """
                  SELECT COALESCE((
                      SELECT activity.pid
                      FROM pg_catalog.pg_stat_activity activity
                      WHERE activity.pid <> :blockerPid
                        AND :blockerPid = ANY(pg_catalog.pg_blocking_pids(activity.pid))
                      ORDER BY activity.pid
                      LIMIT 1
                  ), 0)
                  """)
              .param("blockerPid", blockerPid)
              .query(Integer.class)
              .single();
      if (waitingPid > 0) {
        return waitingPid;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return 0;
  }

  private static int awaitWinnerPostInsertGate(
      int gatePid, UniqueRaceGate expectedWinner, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      int waitingPid =
          jdbc.sql(
                  """
                  SELECT COALESCE((
                      SELECT activity.pid
                      FROM pg_catalog.pg_stat_activity activity
                      WHERE activity.application_name = :applicationName
                        AND activity.state = 'active'
                        AND activity.wait_event_type = 'Lock'
                        AND activity.wait_event = 'advisory'
                        AND pg_catalog.cardinality(
                                pg_catalog.pg_blocking_pids(activity.pid)) = 1
                        AND :gatePid = ANY(pg_catalog.pg_blocking_pids(activity.pid))
                        AND activity.query ~*
                            'insert[[:space:]]+into[[:space:]]+local_draft_undo_receipts'
                      LIMIT 1
                  ), 0)
                  """)
              .param("applicationName", expectedWinner.applicationName())
              .param("gatePid", gatePid)
              .query(Integer.class)
              .single();
      if (waitingPid > 0) {
        return waitingPid;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return 0;
  }

  private static int awaitUniqueTransactionWait(
      int winnerPid, String loserApplicationName, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      int waitingPid =
          jdbc.sql(
                  """
                  SELECT COALESCE((
                      SELECT activity.pid
                      FROM pg_catalog.pg_stat_activity activity
                      WHERE activity.application_name = :applicationName
                        AND activity.pid <> :winnerPid
                        AND activity.state = 'active'
                        AND activity.wait_event_type = 'Lock'
                        AND activity.wait_event = 'transactionid'
                        AND pg_catalog.cardinality(
                                pg_catalog.pg_blocking_pids(activity.pid)) = 1
                        AND :winnerPid = ANY(pg_catalog.pg_blocking_pids(activity.pid))
                        AND activity.query ~*
                            'insert[[:space:]]+into[[:space:]]+local_draft_undo_receipts'
                      LIMIT 1
                  ), 0)
                  """)
              .param("applicationName", loserApplicationName)
              .param("winnerPid", winnerPid)
              .query(Integer.class)
              .single();
      if (waitingPid > 0) {
        return waitingPid;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return 0;
  }

  private static String sqlLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static void unlockRaceGate(Connection gate) throws Exception {
    try (Statement statement = gate.createStatement();
        ResultSet row =
            statement.executeQuery("SELECT pg_catalog.pg_advisory_unlock(" + RACE_GATE_KEY + ")")) {
      assertTrue(row.next());
      assertTrue(row.getBoolean(1));
    }
  }

  private static void cancelIfRunning(Future<?> future) {
    if (future != null && !future.isDone()) {
      future.cancel(true);
    }
  }

  private static CreationFixture seedCreation(
      String owner, String suffix, Instant provenanceTimestamp) {
    Instant approvedAt = databaseNow();
    String captureId = "capture-undo-" + suffix;
    String artifactId = "artifact-undo-" + suffix;
    String source = "synthetic logical Undo source " + suffix;
    new PostgresCaptureStore(dataSource, transactionManager)
        .saveOrFindByNonce(
            new Capture(
                captureId,
                owner,
                "capture-undo-nonce-" + suffix,
                CaptureRequestHashes.sha256(
                    source, CaptureSourceType.TEXT, "logical-undo-store-test", DataClass.PERSONAL),
                source,
                CaptureSourceType.TEXT,
                "logical-undo-store-test",
                DataClass.PERSONAL,
                provenanceTimestamp));
    String content = "synthetic logical Undo artifact " + suffix;
    ArtifactLineage artifact =
        new PostgresArtifactLineageStore(dataSource, transactionManager)
            .create(
                new ArtifactLineage(
                    artifactId,
                    owner,
                    captureId,
                    List.of(
                        new ArtifactLineageEntry(
                            1,
                            content,
                            ContentHashes.sha256(content),
                            null,
                            null,
                            provenanceTimestamp.plusSeconds(1)))));
    ActionAttempt planned = planned(owner, suffix, artifact, approvedAt);
    PostgresActionAttemptStore attempts = actionAttempts();
    assertInstanceOf(
        ActionAttemptStore.PlanResult.Accepted.class, attempts.planApprovalOrFind(planned));
    LocalDraftboxStore.ExecuteResult.Executed executed =
        assertInstanceOf(
            LocalDraftboxStore.ExecuteResult.Executed.class,
            draftbox()
                .executeOwned(
                    owner,
                    planned.attemptId(),
                    planned.approvalScope().scopeSchema(),
                    planned.approvalScope().scopeHash(),
                    "draft-undo-" + suffix,
                    "creation-receipt-undo-" + suffix));
    assertTrue(executed.created());
    return new CreationFixture(
        executed.attempt(), executed.draft(), executed.attempt().localDraftReceipt());
  }

  private static ActionAttempt planned(
      String owner, String suffix, ArtifactLineage artifact, Instant approvedAt) {
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    String planId = "plan-undo-" + suffix;
    String idempotencyKey = "idempotency-undo-" + suffix;
    ActionPlan plan =
        new ActionPlan(
            planId,
            owner,
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
            "approval-undo-" + suffix,
            planId,
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            owner,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-undo-" + suffix,
            owner,
            LocalDraftboxAuthority.CONNECTOR,
            LocalDraftboxAuthority.AUDIENCE,
            LocalDraftboxAuthority.accountRefFor(owner),
            planId,
            plan.planHash(),
            plan.artifactHash(),
            idempotencyKey,
            expiresAt,
            LocalDraftboxAuthority.MAX_CALLS);
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            owner,
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
        "attempt-undo-" + suffix,
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, approvedAt)),
        null,
        scope);
  }

  private static String independentScopeHash(CreationFixture fixture, String undoNonce) {
    List<String> fields =
        List.of(
            "CONFIGURED_LOCAL_PRINCIPAL",
            fixture.draft().principalId(),
            "EXPLICIT_LOCAL_OWNER_INPUT",
            "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
            "LOGICALLY_UNDO_LOCAL_DRAFT",
            "local://drafts/" + fixture.draft().draftId(),
            fixture.draft().draftId(),
            fixture.attempt().attemptId(),
            fixture.creationReceipt().receiptId(),
            fixture.draft().artifactId(),
            Integer.toString(fixture.draft().artifactVersion()),
            fixture.draft().artifactHash(),
            "ACTIVE",
            "CAPTURE_ARTIFACT_HISTORY_RETAINED",
            "local-draft-undo-v1",
            "emergeos.local-draftbox",
            "emergeos:local-draftbox",
            "local-draftbox:" + fixture.draft().principalId(),
            undoNonce,
            "1");
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    canonical.writeBytes(UNDO_SCHEMA.getBytes(StandardCharsets.UTF_8));
    canonical.write(0);
    for (String field : fields) {
      byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
      canonical.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
      canonical.writeBytes(utf8);
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  private static CreationTruthDigest creationTruth(String attemptId) {
    return new CreationTruthDigest(
        jdbc.sql(
                """
                SELECT jsonb_build_object('xmin', attempt.xmin::text, 'row', to_jsonb(attempt))::text
                FROM action_attempts attempt
                WHERE attempt.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_agg(
                           jsonb_build_object('xmin', transition.xmin::text,
                                              'row', to_jsonb(transition))
                           ORDER BY transition.sequence
                       )::text
                FROM action_attempt_transitions transition
                WHERE transition.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_build_object('xmin', draft.xmin::text, 'row', to_jsonb(draft))::text
                FROM local_drafts draft
                WHERE draft.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_build_object('xmin', receipt.xmin::text, 'row', to_jsonb(receipt))::text
                FROM local_draft_creation_receipts receipt
                WHERE receipt.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_build_object('xmin', artifact.xmin::text, 'row', to_jsonb(artifact))::text
                FROM artifacts artifact
                JOIN action_attempts attempt
                  ON attempt.principal_id = artifact.principal_id
                 AND attempt.artifact_id = artifact.artifact_id
                WHERE attempt.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_agg(
                           jsonb_build_object('xmin', version.xmin::text,
                                              'row', to_jsonb(version))
                           ORDER BY version.version
                       )::text
                FROM artifact_versions version
                JOIN action_attempts attempt
                  ON attempt.principal_id = version.principal_id
                 AND attempt.artifact_id = version.artifact_id
                WHERE attempt.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single(),
        jdbc.sql(
                """
                SELECT jsonb_build_object('xmin', capture.xmin::text, 'row', to_jsonb(capture))::text
                FROM captures capture
                JOIN artifacts artifact
                  ON artifact.principal_id = capture.principal_id
                 AND artifact.source_capture_id = capture.capture_id
                JOIN action_attempts attempt
                  ON attempt.principal_id = artifact.principal_id
                 AND attempt.artifact_id = artifact.artifact_id
                WHERE attempt.attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(String.class)
            .single());
  }

  private static GlobalDatabaseDigest globalDatabaseDigest() {
    return new GlobalDatabaseDigest(
        sealedRows("captures", "principal_id,capture_id"),
        sealedRows("artifacts", "principal_id,artifact_id"),
        sealedRows("artifact_versions", "principal_id,artifact_id,version"),
        sealedRows("action_attempts", "principal_id,attempt_id"),
        sealedRows("action_attempt_transitions", "principal_id,attempt_id,sequence"),
        sealedRows("local_drafts", "principal_id,draft_id"),
        sealedRows("local_draft_creation_receipts", "principal_id,attempt_id"),
        sealedRows("action_receipts", "principal_id,attempt_id"),
        sealedRows("local_draft_undo_receipts", "principal_id,creation_attempt_id"));
  }

  private static String sealedRows(String table, String orderBy) {
    Set<String> allowed =
        Set.of(
            "captures",
            "artifacts",
            "artifact_versions",
            "action_attempts",
            "action_attempt_transitions",
            "local_drafts",
            "local_draft_creation_receipts",
            "action_receipts",
            "local_draft_undo_receipts");
    if (!allowed.contains(table)) {
      throw new IllegalArgumentException("unsupported digest table");
    }
    return jdbc.sql(
            "SELECT COALESCE(jsonb_agg(jsonb_build_object('xmin', xmin::text, 'row', "
                + "to_jsonb(source)) ORDER BY "
                + orderBy
                + ")::text, '[]') FROM "
                + table
                + " source")
        .query(String.class)
        .single();
  }

  private static UndoTruthDigest undoTruth() {
    return new UndoTruthDigest(
        jdbc.sql(
                """
                SELECT COALESCE(
                    jsonb_agg(
                        jsonb_build_object('xmin', receipt.xmin::text,
                                           'row', to_jsonb(receipt))
                        ORDER BY receipt.principal_id, receipt.creation_attempt_id
                    )::text,
                    '[]'
                )
                FROM local_draft_undo_receipts receipt
                """)
            .query(String.class)
            .single());
  }

  private static String rawDraftState(String draftId) {
    return jdbc.sql("SELECT state FROM local_drafts WHERE draft_id = :draftId")
        .param("draftId", draftId)
        .query(String.class)
        .single();
  }

  private static Instant storedOccurredAt(String attemptId) {
    return jdbc.sql(
            """
            SELECT occurred_at
            FROM local_draft_undo_receipts
            WHERE creation_attempt_id = :attemptId
            """)
        .param("attemptId", attemptId)
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  private static int undoCount() {
    return jdbc.sql("SELECT count(*) FROM local_draft_undo_receipts").query(Integer.class).single();
  }

  private static String differentLowercaseHash(String hash) {
    char replacement = hash.charAt(0) == '0' ? '1' : '0';
    return replacement + hash.substring(1);
  }

  private static void assertOpaque(Object result, String... forbiddenValues) {
    assertTrue(result.getClass().isRecord());
    assertEquals(0, result.getClass().getRecordComponents().length);
    String surface = result.toString();
    for (String forbidden : forbiddenValues) {
      assertFalse(surface.contains(forbidden), "typed rejection leaked request or winner identity");
    }
  }

  private static Instant databaseNow() {
    return jdbc.sql("SELECT date_trunc('microseconds', clock_timestamp())")
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  private static PostgresActionAttemptStore actionAttempts() {
    return new PostgresActionAttemptStore(dataSource, transactionManager);
  }

  private static PostgresLocalDraftboxStore draftbox() {
    return new PostgresLocalDraftboxStore(dataSource, transactionManager, actionAttempts());
  }

  private static StoreHandle isolatedStore() {
    DriverManagerDataSource isolatedDataSource = newDataSource();
    DataSourceTransactionManager isolatedTransactions =
        new DataSourceTransactionManager(isolatedDataSource);
    PostgresActionAttemptStore isolatedAttempts =
        new PostgresActionAttemptStore(isolatedDataSource, isolatedTransactions);
    return new StoreHandle(
        new PostgresLocalDraftboxStore(isolatedDataSource, isolatedTransactions, isolatedAttempts));
  }

  private static StoreHandle isolatedStore(String applicationName) {
    DriverManagerDataSource isolatedDataSource = newDataSource(applicationName);
    DataSourceTransactionManager isolatedTransactions =
        new DataSourceTransactionManager(isolatedDataSource);
    PostgresActionAttemptStore isolatedAttempts =
        new PostgresActionAttemptStore(isolatedDataSource, isolatedTransactions);
    return new StoreHandle(
        new PostgresLocalDraftboxStore(isolatedDataSource, isolatedTransactions, isolatedAttempts));
  }

  private static DriverManagerDataSource newDataSource() {
    return newDataSource(null);
  }

  private static DriverManagerDataSource newDataSource(String applicationName) {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Properties properties = new Properties();
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("socketTimeout", "15");
    properties.setProperty("options", "-c lock_timeout=8000 -c statement_timeout=12000");
    if (applicationName != null) {
      properties.setProperty("ApplicationName", applicationName);
    }
    source.setConnectionProperties(properties);
    return source;
  }

  @FunctionalInterface
  private interface UndoCall {
    LocalDraftboxStore.UndoResult execute() throws Exception;
  }

  private record StoreHandle(PostgresLocalDraftboxStore store) {}

  private record UniqueRaceGate(
      String applicationName, String attemptId, String draftId, String nonce, String receiptId) {}

  private record CreationFixture(
      ActionAttempt attempt, LocalDraft draft, LocalDraftCreationReceipt creationReceipt) {}

  private record CreationTruthDigest(
      String attempt,
      String transitions,
      String draft,
      String creationReceipt,
      String artifact,
      String artifactVersions,
      String capture) {}

  private record UndoTruthDigest(String rows) {}

  private record GlobalDatabaseDigest(
      String captures,
      String artifacts,
      String artifactVersions,
      String attempts,
      String transitions,
      String drafts,
      String creationReceipts,
      String legacyActionReceipts,
      String undoReceipts) {}
}
