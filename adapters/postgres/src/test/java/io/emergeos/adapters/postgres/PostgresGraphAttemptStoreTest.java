package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.GraphAttemptStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresGraphAttemptStoreTest {

  private static final Instant STARTED =
      Instant.parse("2026-07-31T06:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_attempts")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DataSource dataSource;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    dataSource = dataSource();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void clean() {
    jdbc.sql(
            """
            TRUNCATE TABLE
              agent_graph_attempt_seals,
              agent_graph_attempt_heads,
              agent_graph_attempt_events,
              agent_trace_events,
              agent_run_resource_bindings,
              agent_worker_results,
              agent_runs,
              agent_graph_attempt_run_bindings,
              agent_graph_attempts
            """)
        .update();
  }

  @Test
  void persistsAndFreshlyVerifiesExactElevenEventPrefix() {
    Fixture fixture = fixture("slot-r1", "case-r1", "principal");
    PostgresGraphAttemptStore first = store();
    GraphAttemptCursor cursor = created(first, fixture.manifest());
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.approve(
            fixture.manifest(),
            cursor,
            GraphOperatorApproval.ownerTty(fixture.manifest()),
            STARTED.plusMillis(1));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.startParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(3));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.authorizeChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            STARTED.plusMillis(4));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.startChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            STARTED.plusMillis(5));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.consumeChildEgress(
            fixture.manifest(), cursor, STARTED.plusMillis(6));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.credentialReadStarted(
            fixture.manifest(), cursor, STARTED.plusMillis(7));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.clientCreated(
            fixture.manifest(), cursor, STARTED.plusMillis(8));
    assertVerifiedPrefix(fixture, cursor);
    cursor =
        first.modelCreated(
            fixture.manifest(), cursor, STARTED.plusMillis(9));
    assertVerifiedPrefix(fixture, cursor);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "exact provider request surface"),
            fixture.profile().modelRequested());
    cursor =
        first.providerIntent(
            fixture.manifest(),
            cursor,
            intent,
            STARTED.plusMillis(10));
    assertVerifiedPrefix(fixture, cursor);

    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(fixture.manifest(), valid.snapshot().manifest());
    assertEquals(cursor, valid.snapshot().cursor());
    assertEquals(fixture.parent(), valid.snapshot().parentRun());
    assertEquals(fixture.child(), valid.snapshot().childRun());
    assertEquals(
        GraphAttemptOutcome.INCOMPLETE,
        valid.snapshot().outcome());
    assertEquals(
        GraphBillingStatus.UNKNOWN,
        valid.snapshot().billingStatus());
    assertEquals(
        java.util.List.of(GraphAttemptEventType.values()),
        valid.snapshot().events().stream()
            .map(event -> event.type())
            .toList());
    assertEquals(11L, count("agent_graph_attempt_events"));
    assertEquals(2L, count("agent_runs"));
    assertEquals(0L, count("agent_graph_attempt_seals"));
  }

  @Test
  void replayAndStaleCursorCannotAcquireExecutionAuthority() {
    Fixture first = fixture("slot-r2", "case-r2", "principal");
    PostgresGraphAttemptStore initialStore = store();
    GraphAttemptCursor marked = created(initialStore, first.manifest());

    assertInstanceOf(
        GraphAttemptStore.CreateResult.AlreadyExists.class,
        store().create(first.manifest(), STARTED.plusSeconds(1)));

    Fixture changed =
        fixture("slot-r2", "changed-case-r2", "principal");
    assertInstanceOf(
        GraphAttemptStore.CreateResult.AlreadyExists.class,
        store().create(changed.manifest(), STARTED.plusSeconds(2)));
    assertEquals(
        "EXPECTED_MANIFEST_MISMATCH",
        assertInstanceOf(
                GraphAttemptVerification.Invalid.class,
                store().findVerified(changed.manifest()))
            .reasonCode());

    initialStore.approve(
        first.manifest(),
        marked,
        GraphOperatorApproval.ownerTty(first.manifest()),
        STARTED.plusMillis(1));
    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            store()
                .approve(
                    first.manifest(),
                    marked,
                    GraphOperatorApproval.ownerTty(first.manifest()),
                    STARTED.plusMillis(2)));
    assertEquals(2L, count("agent_graph_attempt_events"));
  }

  @Test
  void wrongOwnerChallengeHashIsRejectedBeforeEventInsert() {
    Fixture fixture =
        fixture("slot-wrong-approval", "case-wrong-approval",
            "principal");
    GraphAttemptCursor marked =
        created(store(), fixture.manifest());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            store()
                .approve(
                    fixture.manifest(),
                    marked,
                    new GraphOperatorApproval(
                        GraphOperatorApproval.OWNER_TTY,
                        IntegrityHashes.utf8ContentHash(
                            "wrong challenge")),
                    STARTED.plusMillis(1)));

    assertEquals(1L, count("agent_graph_attempt_events"));
    assertVerifiedPrefix(fixture, marked);
  }

  @Test
  void coherentApprovalTamperIsRejectedWithoutVerifierRepair() {
    Fixture fixture =
        fixture("slot-approval-tamper", "case-approval-tamper",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor marked =
        created(normal, fixture.manifest());
    normal.approve(
        fixture.manifest(),
        marked,
        approval(fixture.manifest()),
        STARTED.plusMillis(1));
    GraphAttemptEvent tamperedEvent =
        GraphAttemptEvent.next(
            marked,
            GraphAttemptEventType.OPERATOR_APPROVED,
            STARTED.plusMillis(1),
            GraphAttemptPhase.OPERATOR_APPROVED,
            null,
            null,
            null,
            new GraphOperatorApproval(
                GraphOperatorApproval.OWNER_TTY,
                IntegrityHashes.utf8ContentHash(
                    "coherently forged challenge")),
            null);

    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_events
            DISABLE TRIGGER agent_graph_events_immutable_v7
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_heads
            DISABLE TRIGGER agent_graph_head_transition_guard_v7
            """)
        .update();
    try {
      TransactionTemplate tamper =
          new TransactionTemplate(
              new DataSourceTransactionManager(dataSource));
      tamper.executeWithoutResult(
          ignored -> {
            assertEquals(
                1,
                jdbc.sql(
                        """
                        UPDATE agent_graph_attempt_events
                        SET challenge_hash = :challengeHash,
                            event_hash = :eventHash,
                            current_head_hash = :currentHeadHash
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                          AND sequence = 2
                        """)
                    .param(
                        "challengeHash",
                        tamperedEvent.challengeHash())
                    .param("eventHash", tamperedEvent.eventHash())
                    .param(
                        "currentHeadHash",
                        tamperedEvent.currentHeadHash())
                    .param(
                        "principalId",
                        fixture.manifest().principalId())
                    .param(
                        "attemptId",
                        fixture.manifest().attemptId())
                    .update());
            assertEquals(
                1,
                jdbc.sql(
                        """
                        UPDATE agent_graph_attempt_heads
                        SET head_hash = :headHash
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                        """)
                    .param(
                        "headHash", tamperedEvent.currentHeadHash())
                    .param(
                        "principalId",
                        fixture.manifest().principalId())
                    .param(
                        "attemptId",
                        fixture.manifest().attemptId())
                    .update());
          });
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_heads
              ENABLE TRIGGER agent_graph_head_transition_guard_v7
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_events
              ENABLE TRIGGER agent_graph_events_immutable_v7
              """)
          .update();
    }

    ApprovalTruthVersion beforeRead =
        approvalTruthVersion(fixture.manifest());
    assertEquals(
        "STORED_GRAPH_INVALID",
        assertInstanceOf(
                GraphAttemptVerification.Invalid.class,
                store().findVerified(fixture.manifest()))
            .reasonCode());
    assertEquals(
        beforeRead, approvalTruthVersion(fixture.manifest()));
  }

  @Test
  void directEventInsertCannotUseNullRoleToBypassPrefixChecks() {
    Fixture fixture =
        fixture("slot-null-role", "case-null-role", "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor marked =
        created(normal, fixture.manifest());
    GraphAttemptCursor approved =
        normal.approve(
            fixture.manifest(),
            marked,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    GraphAttemptEvent parentAuthorized =
        GraphAttemptEvent.next(
            approved,
            GraphAttemptEventType.PARENT_AUTHORIZED,
            STARTED.plusMillis(2),
            GraphAttemptPhase.PARENT_AUTHORIZED,
            GraphRunRole.PARENT,
            fixture.parent().runId(),
            fixture.parent().task().id(),
            null,
            null);

    assertSqlState(
        "23514",
        () ->
            insertRawEvent(
                fixture.manifest(), parentAuthorized, null));

    assertEquals(2L, count("agent_graph_attempt_events"));
    assertVerifiedPrefix(fixture, approved);
  }

  @Test
  void databaseRejectsProviderIntentForAnotherModelAtomically() {
    Fixture fixture =
        fixture("slot-wrong-model", "case-wrong-model", "principal");
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphAttemptEvent wrongIntent =
        GraphAttemptEvent.next(
            modelReady,
            GraphAttemptEventType.PROVIDER_INTENT,
            STARTED.plusMillis(10),
            GraphAttemptPhase.PROVIDER_PENDING,
            GraphRunRole.CHILD,
            fixture.child().runId(),
            fixture.child().task().id(),
            null,
            new GraphProviderIntent(
                1,
                IntegrityHashes.utf8ContentHash(
                    "wrong-model request"),
                "different-safe-model"));
    TransactionTemplate rawTransaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));

    assertSqlState(
        "23514",
        () ->
            rawTransaction.executeWithoutResult(
                ignored -> {
                  insertRawEvent(
                      fixture.manifest(),
                      wrongIntent,
                      GraphRunRole.CHILD.name());
                  updateRawHead(
                      fixture.manifest(),
                      modelReady,
                      wrongIntent);
                }));

    assertEquals(10L, count("agent_graph_attempt_events"));
    assertVerifiedPrefix(fixture, modelReady);
  }

  @Test
  void concurrentStoresHaveExactlyOneClaimAndOneCasWinner()
      throws Exception {
    Fixture claimFixture =
        fixture("slot-concurrent-claim", "case-concurrent-claim",
            "principal");
    CyclicBarrier claimBarrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<GraphAttemptStore.CreateResult> first =
          pool.submit(
              () -> {
                claimBarrier.await();
                return store()
                    .create(claimFixture.manifest(), STARTED);
              });
      Future<GraphAttemptStore.CreateResult> second =
          pool.submit(
              () -> {
                claimBarrier.await();
                return store()
                    .create(claimFixture.manifest(), STARTED);
              });
      List<GraphAttemptStore.CreateResult> results =
          List.of(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS));
      assertEquals(
          1L,
          results.stream()
              .filter(
                  GraphAttemptStore.CreateResult.Created.class
                      ::isInstance)
              .count());
      assertEquals(
          1L,
          results.stream()
              .filter(
                  GraphAttemptStore.CreateResult.AlreadyExists.class
                      ::isInstance)
              .count());
    }
    assertEquals(1L, count("agent_graph_attempts"));
    assertEquals(
        2L, count("agent_graph_attempt_run_bindings"));
    assertEquals(1L, count("agent_graph_attempt_events"));
    assertEquals(1L, count("agent_graph_attempt_heads"));

    Fixture casFixture =
        fixture("slot-concurrent-cas", "case-concurrent-cas",
            "principal");
    GraphAttemptCursor marked =
        created(store(), casFixture.manifest());
    CyclicBarrier casBarrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<String> first =
          pool.submit(
              () ->
                  approveConcurrently(
                      casFixture, marked, casBarrier));
      Future<String> second =
          pool.submit(
              () ->
                  approveConcurrently(
                      casFixture, marked, casBarrier));
      List<String> outcomes =
          List.of(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS));
      assertEquals(
          1L,
          outcomes.stream().filter("ADVANCED"::equals).count());
      assertEquals(
          1L,
          outcomes.stream().filter("CONFLICT"::equals).count());
    }
    assertEquals(3L, count("agent_graph_attempt_events"));
    assertEquals(
        2L,
        jdbc.sql(
                """
                SELECT last_sequence
                FROM agent_graph_attempt_heads
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", "principal")
            .param("attemptId", casFixture.manifest().attemptId())
            .query(Long.class)
            .single());
  }

  @Test
  void transactionBoundariesCommitIndependentlyAndVerifyReadOnly()
      throws Exception {
    Fixture fixture =
        fixture("slot-transaction", "case-transaction",
            "principal");
    TransactionTemplate outer =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    outer.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    outer.executeWithoutResult(
        ignored -> {
          created(store(), fixture.manifest());
          assertEquals(
              1L,
              independentCount(
                  "agent_graph_attempts"));
        });

    AtomicReference<List<String>> transactionMode =
        new AtomicReference<>();
    PostgresGraphAttemptStore verifier =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .VERIFIED_READ_TRANSACTION) {
                JdbcClient local =
                    JdbcClient.create(dataSource);
                transactionMode.set(
                    List.of(
                        local.sql(
                                "SHOW transaction_isolation")
                            .query(String.class)
                            .single(),
                        local.sql(
                                "SHOW transaction_read_only")
                            .query(String.class)
                            .single()));
              }
            });
    outer.executeWithoutResult(
        ignored ->
            assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                verifier.findVerified(fixture.manifest())));
    assertEquals(
        List.of("repeatable read", "on"),
        transactionMode.get());
  }

  @Test
  void injectedClaimAndRunFaultsRollbackWholeTransactions() {
    Fixture claimFixture =
        fixture("slot-fault-claim", "case-fault-claim",
            "principal");
    PostgresGraphAttemptStore claimFault =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .AFTER_BINDINGS_INSERT) {
                throw new SyntheticFault();
              }
            });
    assertThrows(
        io.emergeos.core.domain.GraphAttemptIntegrityException.class,
        () -> claimFault.create(claimFixture.manifest(), STARTED));
    assertEquals(0L, count("agent_graph_attempts"));
    assertEquals(
        0L, count("agent_graph_attempt_run_bindings"));
    assertEquals(0L, count("agent_graph_attempt_events"));
    assertEquals(0L, count("agent_graph_attempt_heads"));

    Fixture runFixture =
        fixture("slot-fault-run", "case-fault-run",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, runFixture.manifest());
    cursor =
        normal.approve(
            runFixture.manifest(),
            cursor,
            approval(runFixture.manifest()),
            STARTED.plusMillis(1));
    GraphAttemptCursor parentAuthorized =
        normal.authorizeParent(
            runFixture.manifest(),
            cursor,
            runFixture.parent(),
            STARTED.plusMillis(2));
    PostgresGraphAttemptStore runFault =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .AFTER_RUN_INSERT) {
                throw new SyntheticFault();
              }
            });
    assertThrows(
        io.emergeos.core.domain.GraphAttemptIntegrityException.class,
        () ->
            runFault.startParent(
                runFixture.manifest(),
                parentAuthorized,
                runFixture.parent(),
                STARTED.plusMillis(3)));
    assertEquals(3L, count("agent_graph_attempt_events"));
    assertEquals(0L, count("agent_runs"));
    assertVerifiedPrefix(runFixture, parentAuthorized);
  }

  @Test
  void advisoryLockClosesReservedRunLegacyWriterRace()
      throws Exception {
    Fixture fixture =
        fixture("slot-run-race", "case-run-race",
            "principal");
    CountDownLatch bindingsReady = new CountDownLatch(1);
    CountDownLatch releaseCreator = new CountDownLatch(1);
    PostgresGraphAttemptStore pausing =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .AFTER_BINDINGS_INSERT) {
                bindingsReady.countDown();
                await(releaseCreator);
              }
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<GraphAttemptStore.CreateResult> creator =
          pool.submit(
              () -> pausing.create(fixture.manifest(), STARTED));
      assertTrue(bindingsReady.await(10, TimeUnit.SECONDS));
      CountDownLatch legacyStarted = new CountDownLatch(1);
      Future<String> legacy =
          pool.submit(
              () -> {
                legacyStarted.countDown();
                try {
                  insertLegacyRun(fixture.parent());
                  return "INSERTED";
                } catch (DataIntegrityViolationException expected) {
                  return "REJECTED";
                }
              });
      assertTrue(legacyStarted.await(10, TimeUnit.SECONDS));
      assertFalse(legacy.isDone());
      releaseCreator.countDown();
      assertInstanceOf(
          GraphAttemptStore.CreateResult.Created.class,
          creator.get(10, TimeUnit.SECONDS));
      assertEquals(
          "REJECTED", legacy.get(10, TimeUnit.SECONDS));
    }
    assertEquals(0L, count("agent_runs"));
    assertEquals(1L, count("agent_graph_attempts"));
  }

  @Test
  void advisoryLockClosesLegacyWriterReservedRunRace()
      throws Exception {
    Fixture fixture =
        fixture("slot-run-race-reverse",
            "case-run-race-reverse", "principal");
    CountDownLatch manifestReady = new CountDownLatch(1);
    PostgresGraphAttemptStore creator =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .AFTER_MANIFEST_INSERT) {
                manifestReady.countDown();
              }
            });

    try (Connection legacyConnection =
            dataSource.getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      legacyConnection.setAutoCommit(false);
      insertLegacyRun(legacyConnection, fixture.parent());

      Future<String> reservation =
          pool.submit(
              () -> {
                try {
                  creator.create(fixture.manifest(), STARTED);
                  return "CREATED";
                } catch (
                    io.emergeos.core.domain
                        .GraphAttemptIntegrityException expected) {
                  return postgresFailure(expected).getSQLState();
                }
              });
      assertTrue(manifestReady.await(10, TimeUnit.SECONDS));
      assertFalse(reservation.isDone());

      legacyConnection.commit();
      assertEquals(
          "23505", reservation.get(10, TimeUnit.SECONDS));
    }

    assertEquals(1L, count("agent_runs"));
    assertEquals(0L, count("agent_graph_attempts"));
    assertEquals(
        0L, count("agent_graph_attempt_run_bindings"));
    assertEquals(0L, count("agent_graph_attempt_events"));
    assertEquals(0L, count("agent_graph_attempt_heads"));
    assertEquals(0L, count("agent_graph_attempt_seals"));
  }

  @Test
  void graphBoundRunCannotEscapeByClearingSelectorAndChangingKey() {
    Fixture fixture =
        fixture("slot-run-escape", "case-run-escape", "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    GraphAttemptCursor parentStarted =
        normal.startParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(3));

    DataAccessException rejection =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET run_id = 'escaped-run',
                        graph_attempt_id = NULL,
                        graph_manifest_hash = NULL,
                        graph_role = NULL,
                        graph_task_hash = NULL,
                        graph_selector_hash = NULL,
                        graph_execution_profile_id = NULL,
                        graph_execution_profile_fingerprint = NULL,
                        graph_worker_registry_version = NULL,
                        graph_worker_profile_id = NULL,
                        graph_worker_profile_fingerprint = NULL
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                    """)
                .param("principalId", fixture.parent().principalId())
                .param("runId", fixture.parent().runId())
                .update());
    assertEquals(
        "55000",
        assertInstanceOf(
                PSQLException.class, rejection.getRootCause())
            .getSQLState());

    assertVerifiedPrefix(fixture, parentStarted);
    assertEquals(1L, count("agent_runs"));
    assertEquals(
        1L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                  AND graph_attempt_id = :attemptId
                """)
            .param("principalId", fixture.parent().principalId())
            .param("runId", fixture.parent().runId())
            .param("attemptId", fixture.manifest().attemptId())
            .query(Long.class)
            .single());
  }

  @Test
  void privilegedSelectorTamperIsDetectedWithoutVerifierWrites() {
    Fixture fixture =
        fixture("slot-tamper", "case-tamper", "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    normal.startParent(
        fixture.manifest(),
        cursor,
        fixture.parent(),
        STARTED.plusMillis(3));
    SelectorTruthVersion before =
        selectorTruthVersion(
            fixture.manifest(), GraphRunRole.PARENT);

    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_run_bindings
            DISABLE TRIGGER agent_graph_bindings_immutable_v7
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_runs
            DISABLE TRIGGER agent_graph_run_selector_guard_v7
            """)
        .update();
    try {
      TransactionTemplate tamper =
          new TransactionTemplate(
              new DataSourceTransactionManager(dataSource));
      tamper.executeWithoutResult(
          ignored -> {
            assertEquals(
                1,
                jdbc.sql(
                        """
                        UPDATE agent_graph_attempt_run_bindings
                        SET selector_hash = :tamperedHash
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                          AND role = 'PARENT'
                        """)
                    .param("tamperedHash", "f".repeat(64))
                    .param(
                        "principalId",
                        fixture.manifest().principalId())
                    .param(
                        "attemptId",
                        fixture.manifest().attemptId())
                    .update());
            assertEquals(
                1,
                jdbc.sql(
                        """
                        UPDATE agent_runs
                        SET graph_selector_hash = :tamperedHash
                        WHERE principal_id = :principalId
                          AND run_id = :runId
                        """)
                    .param("tamperedHash", "f".repeat(64))
                    .param(
                        "principalId",
                        fixture.parent().principalId())
                    .param("runId", fixture.parent().runId())
                    .update());
          });
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_runs
              ENABLE TRIGGER agent_graph_run_selector_guard_v7
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_run_bindings
              ENABLE TRIGGER agent_graph_bindings_immutable_v7
              """)
          .update();
    }

    SelectorTruthVersion tampered =
        selectorTruthVersion(
            fixture.manifest(), GraphRunRole.PARENT);
    assertFalse(
        before.bindingSelectorHash().equals(
            tampered.bindingSelectorHash()));
    assertFalse(
        before.runSelectorHash().equals(
            tampered.runSelectorHash()));
    assertFalse(
        before.bindingRowVersion().equals(
            tampered.bindingRowVersion()));
    assertFalse(
        before.runRowVersion().equals(
            tampered.runRowVersion()));
    for (int verifier = 0; verifier < 2; verifier++) {
      assertEquals(
          "STORED_GRAPH_INVALID",
          assertInstanceOf(
                  GraphAttemptVerification.Invalid.class,
                  store().findVerified(fixture.manifest()))
              .reasonCode());
    }
    assertEquals(
        tampered,
        selectorTruthVersion(
            fixture.manifest(), GraphRunRole.PARENT));
  }

  @Test
  void maximumMultibyteSelectorNamesRoundTripThroughBoundedIndexes() {
    Fixture fixture =
        fixture("slot-wide-selector", "case-wide-selector",
            "principal");
    String maximum = "😀".repeat(200);
    GraphRunSelection parent =
        wideSelection(
            fixture.manifest().parentSelection(), maximum);
    GraphRunSelection child =
        wideSelection(
            fixture.manifest().childSelection(), maximum);
    GraphAttemptManifest manifest =
        manifestWithSelections(
            fixture.manifest(), parent, child);

    GraphAttemptCursor marked = created(store(), manifest);

    assertInstanceOf(
        GraphAttemptVerification.Valid.class,
        store().findVerified(manifest));
    assertEquals(
        List.of(parent.selectorHash(), child.selectorHash()),
        jdbc.sql(
                """
                SELECT selector_hash
                FROM agent_graph_attempt_run_bindings
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                ORDER BY CASE role
                  WHEN 'PARENT' THEN 1
                  ELSE 2
                END
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(String.class)
            .list());
    assertEquals(1, marked.lastSequence());
  }

  @Test
  void directSqlCannotRewriteOrSealCanonicalGraphTruth() {
    Fixture fixture =
        fixture("slot-direct-sql", "case-direct-sql", "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    GraphAttemptCursor parentStarted =
        normal.startParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(3));

    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_graph_attempts
                    SET artifact_id = artifact_id || '-changed'
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());

    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_heads
            DISABLE TRIGGER agent_graph_head_transition_guard_v7
            """)
        .update();
    try {
      assertSqlState(
          "23514",
          () ->
              jdbc.sql(
                      """
                      DELETE FROM agent_graph_attempt_heads
                      WHERE principal_id = :principalId
                        AND attempt_id = :attemptId
                      """)
                  .param("principalId", fixture.manifest().principalId())
                  .param("attemptId", fixture.manifest().attemptId())
                  .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_heads
              ENABLE TRIGGER agent_graph_head_transition_guard_v7
              """)
          .update();
    }

    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_events
            DISABLE TRIGGER agent_graph_events_immutable_v7
            """)
        .update();
    try {
      assertSqlState(
          "23503",
          () ->
              jdbc.sql(
                      """
                      DELETE FROM agent_graph_attempt_events
                      WHERE principal_id = :principalId
                        AND attempt_id = :attemptId
                        AND sequence = 4
                      """)
                  .param("principalId", fixture.manifest().principalId())
                  .param("attemptId", fixture.manifest().attemptId())
                  .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_events
              ENABLE TRIGGER agent_graph_events_immutable_v7
              """)
          .update();
    }

    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_graph_attempts
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_graph_attempt_run_bindings
                    SET worker_profile_id = worker_profile_id || '-changed'
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                      AND role = 'PARENT'
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_graph_attempt_run_bindings
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                      AND role = 'PARENT'
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_graph_attempt_events
                    SET actor = 'changed'
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                      AND sequence = 1
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_graph_attempt_events
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                      AND sequence = 1
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "40001",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_graph_attempt_heads
                    SET phase = 'MODEL_READY'
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_graph_attempt_heads
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET lifecycle_status = 'FAILED'
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                    """)
                .param("principalId", fixture.parent().principalId())
                .param("runId", fixture.parent().runId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_runs
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                    """)
                .param("principalId", fixture.parent().principalId())
                .param("runId", fixture.parent().runId())
                .update());
    assertSqlState(
        "23514",
        () ->
            jdbc.sql(
                    """
                    INSERT INTO agent_graph_attempt_seals (
                      principal_id, attempt_id, manifest_hash,
                      final_sequence, final_head_hash,
                      graph_outcome, billing_status,
                      seal_hash, sealed_at
                    ) VALUES (
                      :principalId, :attemptId, :manifestHash,
                      :finalSequence, :finalHeadHash,
                      'INCOMPLETE', 'NOT_INVOKED',
                      :sealHash, :sealedAt
                    )
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .param("manifestHash", fixture.manifest().manifestHash())
                .param("finalSequence", parentStarted.lastSequence())
                .param("finalHeadHash", parentStarted.headHash())
                .param(
                    "sealHash",
                    IntegrityHashes.utf8ContentHash("invalid V7 seal"))
                .param(
                    "sealedAt",
                    java.sql.Timestamp.from(STARTED.plusMillis(4)))
                .update());

    assertVerifiedPrefix(fixture, parentStarted);
    assertEquals(0L, count("agent_graph_attempt_seals"));
  }

  @Test
  void verifierRejectsShapeValidPrematureRunEffectTamper() {
    Fixture fixture =
        fixture("slot-run-effect-tamper", "case-run-effect-tamper",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    normal.startParent(
        fixture.manifest(),
        cursor,
        fixture.parent(),
        STARTED.plusMillis(3));

    jdbc.sql(
            """
            ALTER TABLE agent_runs
            DISABLE TRIGGER agent_graph_run_selector_guard_v7
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_runs
            DISABLE TRIGGER agent_graph_runs_assert_v7
            """)
        .update();
    try {
      assertEquals(
          1,
          jdbc.sql(
                  """
                  UPDATE agent_runs
                  SET resolved_model = 'tampered-model',
                      agent_version = 'tampered-agent',
                      verifier_version = 'tampered-verifier',
                      harness_version = 'tampered-harness',
                      cost_usd = 0.123456,
                      token_count = 1,
                      latency_ms = 1,
                      failure_attribution = 'tampered-attribution'
                  WHERE principal_id = :principalId
                    AND run_id = :runId
                  """)
              .param("principalId", fixture.parent().principalId())
              .param("runId", fixture.parent().runId())
              .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_runs
              ENABLE TRIGGER agent_graph_runs_assert_v7
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_runs
              ENABLE TRIGGER agent_graph_run_selector_guard_v7
              """)
          .update();
    }

    assertEquals(
        "STORED_GRAPH_INVALID",
        assertInstanceOf(
                GraphAttemptVerification.Invalid.class,
                store().findVerified(fixture.manifest()))
            .reasonCode());
  }

  @Test
  void reservedRunRejectsLegacyWriterAndMissingReadIsNonLeaking() {
    Fixture fixture = fixture("slot-r3", "case-r3", "principal");
    created(store(), fixture.manifest());

    assertSqlState(
        "23503",
        () -> insertLegacyRun(fixture.parent()));

    Fixture occupied =
        fixture("slot-occupied", "case-occupied",
            "principal");
    insertLegacyRun(occupied.parent());
    assertThrows(
        io.emergeos.core.domain.GraphAttemptIntegrityException.class,
        () -> store().create(occupied.manifest(), STARTED));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(occupied.manifest()));

    Fixture missing =
        fixture("missing-slot", "missing-case", "principal");
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(missing.manifest()));
  }

  private static GraphAttemptCursor created(
      PostgresGraphAttemptStore store,
      GraphAttemptManifest manifest) {
    return assertInstanceOf(
            GraphAttemptStore.CreateResult.Created.class,
            store.create(manifest, STARTED))
        .cursor();
  }

  private static String approveConcurrently(
      Fixture fixture,
      GraphAttemptCursor marked,
      CyclicBarrier barrier)
      throws Exception {
    barrier.await();
    try {
      store()
          .approve(
              fixture.manifest(),
              marked,
              approval(fixture.manifest()),
              STARTED.plusMillis(1));
      return "ADVANCED";
    } catch (GraphAttemptConflictException conflict) {
      return "CONFLICT";
    }
  }

  private static GraphOperatorApproval approval(
      GraphAttemptManifest manifest) {
    return GraphOperatorApproval.ownerTty(manifest);
  }

  private static GraphAttemptCursor advanceToModelCreated(
      Fixture fixture) {
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(2));
    cursor =
        normal.startParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            STARTED.plusMillis(3));
    cursor =
        normal.authorizeChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            STARTED.plusMillis(4));
    cursor =
        normal.startChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            STARTED.plusMillis(5));
    cursor =
        normal.consumeChildEgress(
            fixture.manifest(),
            cursor,
            STARTED.plusMillis(6));
    cursor =
        normal.credentialReadStarted(
            fixture.manifest(),
            cursor,
            STARTED.plusMillis(7));
    cursor =
        normal.clientCreated(
            fixture.manifest(),
            cursor,
            STARTED.plusMillis(8));
    return normal.modelCreated(
        fixture.manifest(),
        cursor,
        STARTED.plusMillis(9));
  }

  private static void insertRawEvent(
      GraphAttemptManifest manifest,
      GraphAttemptEvent event,
      String storedRole) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_events (
              principal_id, attempt_id, manifest_hash, sequence,
              event_type, occurred_at, phase_from, phase_to,
              role, run_id, task_id, actor, challenge_hash,
              request_ordinal, request_hash, model_requested,
              previous_head_hash, event_hash, current_head_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :sequence,
              :eventType, :occurredAt, :phaseFrom, :phaseTo,
              :role, :runId, :taskId, :actor, :challengeHash,
              :requestOrdinal, :requestHash, :modelRequested,
              :previousHeadHash, :eventHash, :currentHeadHash
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("sequence", event.sequence())
        .param("eventType", event.type().name())
        .param(
            "occurredAt",
            java.sql.Timestamp.from(event.occurredAt()))
        .param(
            "phaseFrom",
            event.phaseFrom() == null
                ? null
                : event.phaseFrom().name(),
            java.sql.Types.VARCHAR)
        .param("phaseTo", event.phaseTo().name())
        .param("role", storedRole, java.sql.Types.VARCHAR)
        .param("runId", event.runId(), java.sql.Types.VARCHAR)
        .param("taskId", event.taskId(), java.sql.Types.VARCHAR)
        .param("actor", event.actor(), java.sql.Types.VARCHAR)
        .param(
            "challengeHash",
            event.challengeHash(),
            java.sql.Types.CHAR)
        .param(
            "requestOrdinal",
            event.requestOrdinal(),
            java.sql.Types.SMALLINT)
        .param(
            "requestHash",
            event.requestHash(),
            java.sql.Types.CHAR)
        .param(
            "modelRequested",
            event.modelRequested(),
            java.sql.Types.VARCHAR)
        .param(
            "previousHeadHash", event.previousHeadHash())
        .param("eventHash", event.eventHash())
        .param("currentHeadHash", event.currentHeadHash())
        .update();
  }

  private static void updateRawHead(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphAttemptEvent event) {
    assertEquals(
        1,
        jdbc.sql(
                """
                UPDATE agent_graph_attempt_heads
                SET phase = :phase,
                    state_version = :stateVersion,
                    last_sequence = :lastSequence,
                    head_hash = :headHash,
                    billing_status = 'UNKNOWN',
                    provider_intent_count = 1,
                    updated_at = :updatedAt
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND manifest_hash = :manifestHash
                  AND state_version = :expectedVersion
                  AND last_sequence = :expectedSequence
                  AND head_hash = :expectedHeadHash
                """)
            .param("phase", event.phaseTo().name())
            .param("stateVersion", (long) event.sequence())
            .param("lastSequence", event.sequence())
            .param("headHash", event.currentHeadHash())
            .param(
                "updatedAt",
                java.sql.Timestamp.from(event.occurredAt()))
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .param("manifestHash", manifest.manifestHash())
            .param("expectedVersion", expected.stateVersion())
            .param("expectedSequence", expected.lastSequence())
            .param("expectedHeadHash", expected.headHash())
            .update());
  }

  private static long independentCount(String table) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM " + table);
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void insertLegacyRun(AgentRun running) {
    insertLegacyRun(JdbcClient.create(dataSource), running);
  }

  private static void insertLegacyRun(
      Connection connection, AgentRun running) {
    insertLegacyRun(
        JdbcClient.create(
            new SingleConnectionDataSource(connection, true)),
        running);
  }

  private static void insertLegacyRun(
      JdbcClient target, AgentRun running) {
    target
        .sql(
            """
            INSERT INTO agent_runs (
              principal_id, run_id, task_id, lifecycle_status,
              task_envelope, tool_registry_version, policy_version,
              state_version, context_policy_version,
              model_provider, model_requested, pricing_profile,
              started_at
            ) VALUES (
              :principalId, :runId, :taskId, 'RUNNING',
              CAST(:taskJson AS jsonb), :toolRegistryVersion,
              :policyVersion, :stateVersion,
              :contextPolicyVersion, :modelProvider,
              :modelRequested, :pricingProfile, :startedAt
            )
            """)
        .param("principalId", running.principalId())
        .param("runId", running.runId())
        .param("taskId", running.task().id())
        .param(
            "taskJson",
            tools.jackson.databind.json.JsonMapper.shared()
                .writeValueAsString(running.task()))
        .param(
            "toolRegistryVersion",
            running.task().toolRegistryVersion())
        .param(
            "policyVersion",
            running.task().policyVersion())
        .param(
            "stateVersion",
            running.task().stateVersion())
        .param(
            "contextPolicyVersion",
            running.task().contextPolicyVersion())
        .param(
            "modelProvider",
            running.task().modelProvider(),
            java.sql.Types.VARCHAR)
        .param(
            "modelRequested",
            running.task().modelRequested(),
            java.sql.Types.VARCHAR)
        .param(
            "pricingProfile",
            running.task().pricingProfile(),
            java.sql.Types.VARCHAR)
        .param(
            "startedAt",
            java.sql.Timestamp.from(running.startedAt()))
        .update();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "test coordination timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void assertVerifiedPrefix(
      Fixture fixture, GraphAttemptCursor expectedCursor) {
    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(expectedCursor, valid.snapshot().cursor());
    assertEquals(
        GraphAttemptOutcome.INCOMPLETE,
        valid.snapshot().outcome());
    assertEquals(
        expectedCursor.lastSequence() == 11
            ? GraphBillingStatus.UNKNOWN
            : GraphBillingStatus.NOT_INVOKED,
        valid.snapshot().billingStatus());
    if (expectedCursor.lastSequence() >= 4) {
      assertEquals(
          fixture.parent(), valid.snapshot().parentRun());
    } else {
      assertNull(valid.snapshot().parentRun());
    }
    if (expectedCursor.lastSequence() >= 6) {
      assertEquals(
          fixture.child(), valid.snapshot().childRun());
    } else {
      assertNull(valid.snapshot().childRun());
    }
  }

  private static long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table)
        .query(Long.class)
        .single();
  }

  private static void assertSqlState(
      String expected,
      org.junit.jupiter.api.function.Executable operation) {
    RuntimeException rejection =
        assertThrows(RuntimeException.class, operation);
    assertEquals(
        expected,
        postgresFailure(rejection).getSQLState());
  }

  private static PSQLException postgresFailure(
      RuntimeException rejection) {
    Throwable cause = rejection;
    while (cause != null) {
      if (cause instanceof PSQLException postgres) {
        return postgres;
      }
      cause = cause.getCause();
    }
    throw new AssertionError(
        "expected PostgreSQL failure", rejection);
  }

  private static SelectorTruthVersion selectorTruthVersion(
      GraphAttemptManifest manifest, GraphRunRole role) {
    return jdbc.sql(
            """
            SELECT binding.selector_hash AS binding_selector_hash,
                   binding.xmin::text AS binding_row_version,
                   run.graph_selector_hash AS run_selector_hash,
                   run.xmin::text AS run_row_version
            FROM agent_graph_attempt_run_bindings binding
            JOIN agent_runs run
              ON run.principal_id = binding.principal_id
             AND run.run_id = binding.run_id
            WHERE binding.principal_id = :principalId
              AND binding.attempt_id = :attemptId
              AND binding.role = :role
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("role", role.name())
        .query(
            (resultSet, rowNumber) ->
                new SelectorTruthVersion(
                    resultSet.getString(
                        "binding_selector_hash"),
                    resultSet.getString(
                        "binding_row_version"),
                    resultSet.getString("run_selector_hash"),
                    resultSet.getString("run_row_version")))
        .single();
  }

  private static ApprovalTruthVersion approvalTruthVersion(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT event.challenge_hash, event.event_hash,
                   event.current_head_hash,
                   event.xmin::text AS event_row_version,
                   head.head_hash,
                   head.xmin::text AS head_row_version
            FROM agent_graph_attempt_events event
            JOIN agent_graph_attempt_heads head
              ON head.principal_id = event.principal_id
             AND head.attempt_id = event.attempt_id
            WHERE event.principal_id = :principalId
              AND event.attempt_id = :attemptId
              AND event.sequence = 2
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (resultSet, rowNumber) ->
                new ApprovalTruthVersion(
                    resultSet.getString("challenge_hash"),
                    resultSet.getString("event_hash"),
                    resultSet.getString("current_head_hash"),
                    resultSet.getString("event_row_version"),
                    resultSet.getString("head_hash"),
                    resultSet.getString("head_row_version")))
        .single();
  }

  private static PostgresGraphAttemptStore store() {
    return new PostgresGraphAttemptStore(dataSource);
  }

  private static GraphRunSelection wideSelection(
      GraphRunSelection original, String maximum) {
    return new GraphRunSelection(
        original.role(),
        original.runId(),
        original.taskId(),
        original.taskHash(),
        maximum,
        original.executionProfileFingerprint(),
        maximum,
        maximum,
        original.workerProfileFingerprint());
  }

  private static GraphAttemptManifest manifestWithSelections(
      GraphAttemptManifest original,
      GraphRunSelection parent,
      GraphRunSelection child) {
    return GraphAttemptManifest.create(
        original.graphProtocolVersion(),
        original.principalId(),
        original.executionSlotId(),
        original.caseId(),
        original.packRawSha256(),
        original.environmentRawSha256(),
        original.captureId(),
        original.captureRequestHash(),
        original.artifactId(),
        original.startedAt(),
        original.pricingProfileFingerprint(),
        original.promptSurfaceFingerprint(),
        original.conductorSurfaceFingerprint(),
        original.reservationUsd(),
        original.maximumProviderRequests(),
        original.parentActor(),
        original.childActor(),
        original.experiment(),
        parent,
        child);
  }

  private static DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static Fixture fixture(
      String slot, String caseId, String principal) {
    PricingProfile pricing =
        new PricingProfile(
            "openai-test-pricing-v1",
            "openai.responses",
            "gpt-test",
            750,
            75,
            4_500);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            pricing,
            "openai-test-protocol-v1",
            IntegrityHashes.utf8ContentHash("conductor"),
            "environment://sha256:"
                + IntegrityHashes.utf8ContentHash("environment"),
            new HarnessExperiment("graph-test", 1),
            272_000,
            1_000,
            new BigDecimal("0.417000"));
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            caseId + "-parent-task",
            principal,
            "draft a synthetic article",
            "capture://capture-1",
            DataClass.PUBLIC);
    TaskEnvelope childTask =
        worker.newChildTask(
            parentTask,
            new WorkerHandoffRequest(
                worker.workerName(),
                parentTask.intent(),
                parentTask.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                parentTask.budgetUsd(),
                CancellationSignal.never()),
            caseId + "-child-task");
    AgentRun parent =
        AgentRun.running(
            caseId + "-parent-run",
            principal,
            parentTask,
            STARTED);
    AgentRun child =
        AgentRun.running(
            caseId + "-child-run",
            principal,
            childTask,
            STARTED);
    GraphRunSelection parentSelection =
        new GraphRunSelection(
            GraphRunRole.PARENT,
            parent.runId(),
            parentTask.id(),
            IntegrityHashes.taskHash(parentTask),
            parentProfile.id(),
            parentProfile.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphRunSelection childSelection =
        new GraphRunSelection(
            GraphRunRole.CHILD,
            child.runId(),
            childTask.id(),
            IntegrityHashes.taskHash(childTask),
            worker.id(),
            worker.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphAttemptManifest manifest =
        GraphAttemptManifest.create(
            "postgres-graph-attempt-v1",
            principal,
            slot,
            caseId,
            IntegrityHashes.utf8ContentHash("pack"),
            IntegrityHashes.utf8ContentHash("environment"),
            "capture-1",
            IntegrityHashes.utf8ContentHash("capture"),
            "artifact-1",
            STARTED,
            pricing.fingerprint(),
            IntegrityHashes.utf8ContentHash("prompt"),
            IntegrityHashes.utf8ContentHash("conductor"),
            worker.reservationUsd(),
            2,
            "SCRIPTED_FAKE",
            "OPENAI_RESPONSES",
            worker.experiment(),
            parentSelection,
            childSelection);
    return new Fixture(manifest, parent, child, worker);
  }

  private record Fixture(
      GraphAttemptManifest manifest,
      AgentRun parent,
      AgentRun child,
      ModelBoundReadOnlyWorkerExecutionProfile profile) {}

  private record SelectorTruthVersion(
      String bindingSelectorHash,
      String bindingRowVersion,
      String runSelectorHash,
      String runRowVersion) {}

  private record ApprovalTruthVersion(
      String challengeHash,
      String eventHash,
      String eventHeadHash,
      String eventRowVersion,
      String headHash,
      String headRowVersion) {}

  private static final class SyntheticFault
      extends RuntimeException {

    private SyntheticFault() {
      super(null, null, false, false);
    }
  }
}
