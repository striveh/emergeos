package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.application.ReadOnlyWorkerProfileRegistry;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.GraphAttemptStore;
import io.emergeos.core.port.GraphAttemptReader;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresGraphAttemptStoreTest {

  private static final Instant STARTED =
      Instant.parse("2026-07-31T06:00:00Z");

  private static final List<String> RESTRICTED_READER_RELATIONS =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_attributed_failure_outcomes",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_attempts")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

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
              agent_graph_exact_attempt_heads_v16,
              agent_graph_exact_attempt_events_v16,
              agent_graph_exact_provider_attributions_v16,
              agent_graph_exact_provider_validations_v16,
              agent_graph_exact_tx_a_requirements_v15,
              agent_graph_provider_validations,
              agent_graph_provider_validation_keys,
              agent_graph_attributed_failure_terminal_resumes,
              agent_graph_attributed_failure_outcomes,
              agent_graph_attempt_terminal_bindings,
              agent_graph_attempt_candidates,
              agent_graph_attempt_provider_attributions,
              agent_graph_provider_session_intents,
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
    TransactionTemplate cleanup =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    cleanup.executeWithoutResult(
        ignored -> {
          jdbc.sql("DELETE FROM artifact_versions").update();
          jdbc.sql("DELETE FROM artifacts").update();
          jdbc.sql("DELETE FROM captures").update();
        });
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
        java.util.List.of(
            GraphAttemptEventType.ATTEMPT_CLAIMED,
            GraphAttemptEventType.OPERATOR_APPROVED,
            GraphAttemptEventType.PARENT_AUTHORIZED,
            GraphAttemptEventType.PARENT_STARTED,
            GraphAttemptEventType.CHILD_AUTHORIZED,
            GraphAttemptEventType.CHILD_STARTED,
            GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
            GraphAttemptEventType.CREDENTIAL_READ_STARTED,
            GraphAttemptEventType.CLIENT_CREATED,
            GraphAttemptEventType.MODEL_CREATED,
            GraphAttemptEventType.PROVIDER_INTENT),
        valid.snapshot().events().stream()
            .map(event -> event.type())
            .toList());
    assertEquals(11L, count("agent_graph_attempt_events"));
    assertEquals(2L, count("agent_runs"));
    assertEquals(0L, count("agent_graph_attempt_seals"));
  }

  @Test
  void providerAttributionIsOneAtomicFreshlyVerifiedTransition() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-tx-a",
            "case-terminal-tx-a",
            "principal");
    PostgresGraphAttemptStore first = store();
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "terminal request one"),
            fixture.profile().modelRequested());
    GraphAttemptCursor pending =
        first.providerIntent(
            fixture.manifest(),
            modelReady,
            intent,
            STARTED.plusMillis(10));
    GraphProviderAttribution attribution =
        attribution(fixture, 1, intent.requestHash());

    GraphAttemptCursor attributed =
        first.providerAttributed(
            fixture.manifest(),
            pending,
            attribution,
            STARTED.plusMillis(11));

    assertEquals(12, attributed.lastSequence());
    assertEquals(
        GraphAttemptPhase.PROVIDER_ATTRIBUTED,
        attributed.phase());
    assertEquals(
        1L,
        count("agent_graph_attempt_provider_attributions"));
    assertEquals(12L, count("agent_graph_attempt_events"));
    assertEquals(
        12L,
        jdbc.sql(
                """
                SELECT last_sequence
                FROM agent_graph_attempt_heads
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", fixture.manifest().principalId())
            .param("attemptId", fixture.manifest().attemptId())
            .query(Long.class)
            .single());

    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(attributed, valid.snapshot().cursor());
    assertEquals(
        GraphBillingStatus.ATTRIBUTED,
        valid.snapshot().billingStatus());
    assertEquals(
        List.of(attribution),
        valid.snapshot().providerAttributions());
    assertEquals(
        attribution.attributionHash(),
        valid.snapshot().events().getLast().evidenceHash());
  }

  @Test
  void providerSessionIntentIsDurableAtomicAndOneShotBeforeEffects() {
    Fixture fixture =
        terminalFixture(
            "slot-provider-session-intent",
            "case-provider-session-intent",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor = created(normal, fixture.manifest());
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
    GraphAttemptCursor egress =
        normal.consumeChildEgress(
            fixture.manifest(),
            cursor,
            STARTED.plusMillis(6));
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "provider session intent request"),
            fixture.profile().modelRequested());
    Instant expiresAt =
        jdbc.sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '30 seconds'")
            .query(Instant.class)
            .single();
    PostgresGraphAttemptStore.AuthorityIdentity identity =
        normal.freezeAuthorityIdentity();

    PostgresGraphAttemptStore.ProviderSessionClaim claimed =
        normal.claimProviderSessionIntent(
            fixture.manifest(),
            "r1",
            intent,
            expiresAt,
            identity);

    assertEquals(egress, claimed.cursor());
    assertEquals(expiresAt, claimed.expiresAt());
    assertEquals(64, claimed.intentHash().length());
    assertEquals(
        1L, count("agent_graph_provider_session_intents"));
    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            normal.claimProviderSessionIntent(
                fixture.manifest(),
                "r1",
                intent,
                expiresAt,
                identity));
    assertEquals(
        7,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                new PostgresGraphAttemptStore(dataSource)
                    .findVerified(fixture.manifest()))
            .snapshot()
            .cursor()
            .lastSequence());
    assertEquals(
        claimed.intentHash(),
        jdbc.sql(
                "SELECT intent_hash FROM "
                    + "public.agent_graph_provider_session_intents "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param(
                "principalId", fixture.manifest().principalId())
            .param("attemptId", fixture.manifest().attemptId())
            .query(String.class)
            .single());
  }

  @Test
  void providerSessionIntentRejectsTriggerRewrittenDurableCursor() {
    Fixture fixture =
        terminalFixture(
            "slot-provider-session-trigger-drift",
            "case-provider-session-trigger-drift",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor = created(normal, fixture.manifest());
    cursor =
        normal.approve(
            fixture.manifest(), cursor, approval(fixture.manifest()),
            STARTED.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(), cursor, fixture.parent(),
            STARTED.plusMillis(2));
    cursor =
        normal.startParent(
            fixture.manifest(), cursor, fixture.parent(),
            STARTED.plusMillis(3));
    cursor =
        normal.authorizeChild(
            fixture.manifest(), cursor, fixture.child(),
            STARTED.plusMillis(4));
    cursor =
        normal.startChild(
            fixture.manifest(), cursor, fixture.child(),
            STARTED.plusMillis(5));
    GraphAttemptCursor egress =
        normal.consumeChildEgress(
            fixture.manifest(), cursor, STARTED.plusMillis(6));
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("trigger rewritten request"),
            fixture.profile().modelRequested());
    Instant expiresAt =
        jdbc.sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '30 seconds'")
            .query(Instant.class)
            .single();
    jdbc.sql(
            """
            CREATE FUNCTION public.rewrite_provider_session_cursor()
            RETURNS trigger LANGUAGE plpgsql AS $function$
            BEGIN
              NEW.cursor_head_hash := repeat('f', 64);
              RETURN NEW;
            END
            $function$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER rewrite_provider_session_cursor
            BEFORE INSERT ON public.agent_graph_provider_session_intents
            FOR EACH ROW EXECUTE FUNCTION
              public.rewrite_provider_session_cursor()
            """)
        .update();
    try {
      assertThrows(
          GraphAttemptIntegrityException.class,
          () ->
              normal.claimProviderSessionIntent(
                  fixture.manifest(),
                  "r1",
                  intent,
                  expiresAt,
                  normal.freezeAuthorityIdentity()));
      assertEquals(7, egress.lastSequence());
      assertEquals(0L, count("agent_graph_provider_session_intents"));
    } finally {
      jdbc.sql(
              "DROP TRIGGER rewrite_provider_session_cursor ON "
                  + "public.agent_graph_provider_session_intents")
          .update();
      jdbc.sql("DROP FUNCTION public.rewrite_provider_session_cursor()")
          .update();
    }
  }

  @Test
  void databaseRejectsAttributedHeadWithoutDurableAttribution() {
    Fixture fixture =
        terminalFixture(
            "slot-missing-attribution",
            "case-missing-attribution",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "missing attribution request"),
            fixture.profile().modelRequested());
    GraphAttemptCursor pending =
        normal.providerIntent(
            fixture.manifest(),
            modelReady,
            intent,
            STARTED.plusMillis(10));
    GraphProviderAttribution attribution =
        attribution(fixture, 1, intent.requestHash());
    GraphAttemptEvent attributedEvent =
        GraphAttemptEvent.providerAttributed(
            pending,
            fixture.manifest().childSelection(),
            attribution,
            STARTED.plusMillis(11));
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
                      attributedEvent,
                      GraphRunRole.CHILD.name());
                  updateRawHead(
                      fixture.manifest(),
                      pending,
                      attributedEvent);
                }));

    assertEquals(11L, count("agent_graph_attempt_events"));
    assertEquals(
        0L,
        count("agent_graph_attempt_provider_attributions"));
    assertVerifiedPrefix(fixture, pending);
  }

  @Test
  void twoProviderAttributionsRoundTripInExactOrdinalOrder() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-two-attributions",
            "case-terminal-two-attributions",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor = advanceToModelCreated(fixture);
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("request one"),
            fixture.profile().modelRequested());
    cursor =
        normal.providerIntent(
            fixture.manifest(),
            cursor,
            firstIntent,
            STARTED.plusMillis(10));
    GraphProviderAttribution first =
        attribution(fixture, 1, firstIntent.requestHash());
    cursor =
        normal.providerAttributed(
            fixture.manifest(),
            cursor,
            first,
            STARTED.plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash("request two"),
            fixture.profile().modelRequested());
    cursor =
        normal.providerIntent(
            fixture.manifest(),
            cursor,
            secondIntent,
            STARTED.plusMillis(12));

    GraphAttemptVerification.Valid pendingSecond =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(13, pendingSecond.snapshot().cursor().lastSequence());
    assertEquals(
        GraphBillingStatus.UNKNOWN,
        pendingSecond.snapshot().billingStatus());
    assertEquals(
        List.of(first),
        pendingSecond.snapshot().providerAttributions());

    GraphProviderAttribution second =
        attribution(fixture, 2, secondIntent.requestHash());
    GraphAttemptCursor attributed =
        normal.providerAttributed(
            fixture.manifest(),
            cursor,
            second,
            STARTED.plusMillis(13));

    GraphAttemptVerification.Valid terminalProviderPrefix =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(14, attributed.lastSequence());
    assertEquals(
        GraphBillingStatus.ATTRIBUTED,
        terminalProviderPrefix.snapshot().billingStatus());
    assertEquals(
        List.of(first, second),
        terminalProviderPrefix.snapshot().providerAttributions());
    assertEquals(
        List.of(
            first.attributionHash(),
            second.attributionHash()),
        terminalProviderPrefix.snapshot().events().stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_ATTRIBUTED)
            .map(GraphAttemptEvent::evidenceHash)
            .toList());
  }

  @Test
  void attributionFaultsRollbackEveryWrittenStatement() {
    List<PostgresGraphAttemptStore.ProbePoint> faultPoints =
        List.of(
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PROVIDER_ATTRIBUTION_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_EVENT_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_HEAD_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PROVIDER_ATTRIBUTION_VERIFIED_READ);
    for (int index = 0; index < faultPoints.size(); index++) {
      PostgresGraphAttemptStore.ProbePoint faultPoint =
          faultPoints.get(index);
      Fixture fixture =
          terminalFixture(
              "slot-attribution-fault-" + index,
              "case-attribution-fault-" + index,
              "principal");
      PostgresGraphAttemptStore normal = store();
      GraphAttemptCursor modelReady =
          advanceToModelCreated(fixture);
      GraphProviderIntent intent =
          new GraphProviderIntent(
              1,
              IntegrityHashes.utf8ContentHash(
                  "fault request " + index),
              fixture.profile().modelRequested());
      GraphAttemptCursor pending =
          normal.providerIntent(
              fixture.manifest(),
              modelReady,
              intent,
              STARTED.plusMillis(10));
      GraphAttemptVerification.Valid before =
          assertInstanceOf(
              GraphAttemptVerification.Valid.class,
              store().findVerified(fixture.manifest()));
      PostgresGraphAttemptStore faulting =
          new PostgresGraphAttemptStore(
              dataSource,
              point -> {
                if (point == faultPoint) {
                  throw new SyntheticFault();
                }
              });

      assertThrows(
          io.emergeos.core.domain.GraphAttemptIntegrityException.class,
          () ->
              faulting.providerAttributed(
                  fixture.manifest(),
                  pending,
                  attribution(fixture, 1, intent.requestHash()),
                  STARTED.plusMillis(11)));

      GraphAttemptVerification.Valid after =
          assertInstanceOf(
              GraphAttemptVerification.Valid.class,
              store().findVerified(fixture.manifest()));
      assertEquals(before.snapshot(), after.snapshot());
      assertEquals(
          11L,
          countForAttempt(
              "agent_graph_attempt_events", fixture.manifest()));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_provider_attributions",
              fixture.manifest()));
    }
  }

  @Test
  void legacyProtocolCannotEnterAttributedTerminalPrefix() {
    Fixture fixture =
        fixture(
            "slot-legacy-attribution",
            "case-legacy-attribution",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("legacy request"),
            fixture.profile().modelRequested());
    GraphAttemptCursor pending =
        normal.providerIntent(
            fixture.manifest(),
            modelReady,
            intent,
            STARTED.plusMillis(10));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            normal.providerAttributed(
                fixture.manifest(),
                pending,
                attribution(fixture, 1, intent.requestHash()),
                STARTED.plusMillis(11)));

    assertVerifiedPrefix(fixture, pending);
    assertEquals(
        0L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions",
            fixture.manifest()));

    GraphProviderAttribution attribution =
        attribution(fixture, 1, intent.requestHash());
    GraphAttemptEvent attributedEvent =
        GraphAttemptEvent.providerAttributed(
            pending,
            fixture.manifest().childSelection(),
            attribution,
            STARTED.plusMillis(11));
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
                      attributedEvent,
                      GraphRunRole.CHILD.name());
                  updateRawHead(
                      fixture.manifest(),
                      pending,
                      attributedEvent);
                }));
    assertVerifiedPrefix(fixture, pending);
  }

  @Test
  void mismatchedAttributionRollsBackBeforeCanonicalWrite() {
    Fixture fixture =
        terminalFixture(
            "slot-attribution-mismatch",
            "case-attribution-mismatch",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("expected request"),
            fixture.profile().modelRequested());
    GraphAttemptCursor pending =
        normal.providerIntent(
            fixture.manifest(),
            modelReady,
            intent,
            STARTED.plusMillis(10));
    PricingProfile pricing = fixture.profile().pricing();
    GraphProviderAttribution mismatch =
        GraphProviderAttribution.create(
            1,
            IntegrityHashes.utf8ContentHash("different request"),
            IntegrityHashes.utf8ContentHash("valid response"),
            fixture.manifest().childActor(),
            pricing.modelRequested(),
            pricing.modelRequested(),
            pricing.graphSnapshot(),
            1,
            0,
            1,
            0,
            2,
            pricing.actualCostUsd(1, 0, 1));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            normal.providerAttributed(
                fixture.manifest(),
                pending,
                mismatch,
                STARTED.plusMillis(11)));

    assertVerifiedPrefix(fixture, pending);
    assertEquals(
        0L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions",
            fixture.manifest()));
  }

  @Test
  void concurrentAttributionHasExactlyOneCasWinner()
      throws Exception {
    Fixture fixture =
        terminalFixture(
            "slot-attribution-race",
            "case-attribution-race",
            "principal");
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor modelReady =
        advanceToModelCreated(fixture);
    GraphProviderIntent intent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("raced request"),
            fixture.profile().modelRequested());
    GraphAttemptCursor pending =
        normal.providerIntent(
            fixture.manifest(),
            modelReady,
            intent,
            STARTED.plusMillis(10));
    GraphProviderAttribution attribution =
        attribution(fixture, 1, intent.requestHash());
    CyclicBarrier barrier = new CyclicBarrier(2);

    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<String> first =
          pool.submit(
              () ->
                  attributeConcurrently(
                      fixture,
                      pending,
                      attribution,
                      barrier));
      Future<String> second =
          pool.submit(
              () ->
                  attributeConcurrently(
                      fixture,
                      pending,
                      attribution,
                      barrier));
      assertEquals(
          List.of("ADVANCED", "CONFLICT"),
          java.util.stream.Stream.of(first.get(), second.get())
              .sorted()
              .toList());
    }

    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions",
            fixture.manifest()));
    assertEquals(
        12L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(12, valid.snapshot().cursor().lastSequence());
  }

  @Test
  void failedChildTerminalTruthCommitsAtomicallyAtSequenceFifteen() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-tx-b",
            "case-terminal-tx-b",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    AgentRun terminalChild =
        failedChild(fixture, prefix.attributions());

    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                terminalChild,
                null,
                null,
                STARTED.plusMillis(14));

    assertEquals(15, childTerminal.lastSequence());
    assertEquals(
        GraphAttemptPhase.CHILD_TERMINAL,
        childTerminal.phase());
    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(childTerminal, valid.snapshot().cursor());
    assertEquals(terminalChild, valid.snapshot().childRun());
    assertEquals(fixture.parent(), valid.snapshot().parentRun());
    assertEquals(
        RunStatus.FAILED,
        valid.snapshot().terminalBindings().getFirst().status());
    assertNull(valid.snapshot().candidate());
    assertNull(valid.snapshot().workerResult());
    assertEquals(
        GraphAttemptOutcome.INCOMPLETE,
        valid.snapshot().outcome());
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_terminal_bindings",
            fixture.manifest()));
    assertEquals(
        1L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM agent_trace_events
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("principalId", fixture.manifest().principalId())
            .param("runId", fixture.child().runId())
            .query(Long.class)
            .single());
  }

  @Test
  void successfulChildCandidateAndWorkerResultCommitAtomicallyAtSequenceFifteen() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-success-tx-b",
            "case-terminal-success-tx-b",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth truth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);

    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                truth.terminal(),
                truth.candidate(),
                truth.workerResult(),
                STARTED.plusMillis(14));

    assertEquals(15, childTerminal.lastSequence());
    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(childTerminal, valid.snapshot().cursor());
    assertEquals(truth.terminal(), valid.snapshot().childRun());
    assertEquals(truth.candidate(), valid.snapshot().candidate());
    assertEquals(
        truth.workerResult(), valid.snapshot().workerResult());
    assertEquals(
        RunStatus.SUCCEEDED,
        valid.snapshot().terminalBindings().getFirst().status());
    assertEquals(
        truth.workerResult().workerResultRef(),
        valid.snapshot().terminalBindings().getFirst().effectRef());
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_candidates", fixture.manifest()));
    assertEquals(
        1L,
        countWorkerResultsForChild(
            fixture.manifest().principalId(),
            fixture.child().runId()));
    assertEquals(
        5L,
        countForRun(
            "agent_trace_events",
            fixture.manifest().principalId(),
            fixture.child().runId()));
    assertEquals(
        2L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.manifest().principalId(),
            fixture.child().runId()));
  }

  @Test
  void candidateCannotCommitIndependentlyAtHeadFourteen() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-orphan-candidate",
            "case-terminal-orphan-candidate",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    HarnessCandidateEnvelope candidate =
        successfulChildTruth(fixture, prefix.attributions())
            .candidate();
    TransactionTemplate rawTransaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    AtomicBoolean transactionBodyCompleted =
        new AtomicBoolean();

    RuntimeException rejection =
        assertThrows(
            RuntimeException.class,
            () ->
                rawTransaction.executeWithoutResult(
                    ignored -> {
                      insertRawCandidate(
                          fixture.manifest(),
                          candidate,
                          STARTED.plusMillis(14));
                      transactionBodyCompleted.set(true);
                    }));

    assertTrue(transactionBodyCompleted.get());
    PSQLException postgres = postgresFailure(rejection);
    assertEquals("23514", postgres.getSQLState());
    assertEquals(
        "graph_attempt_provider_truth_v8",
        postgres.getServerErrorMessage().getConstraint());
    assertEquals(
        0L,
        countForAttempt(
            "agent_graph_attempt_candidates", fixture.manifest()));
    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(prefix.cursor(), verified.cursor());
    assertNull(verified.candidate());
  }

  @Test
  void concurrentSuccessfulChildCompletionHasExactlyOneCasWinner()
      throws Exception {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-success-race",
            "case-terminal-success-race",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth truth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    CyclicBarrier barrier = new CyclicBarrier(2);

    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<String> first =
          pool.submit(
              () ->
                  completeChildConcurrently(
                      fixture, prefix, truth, barrier));
      Future<String> second =
          pool.submit(
              () ->
                  completeChildConcurrently(
                      fixture, prefix, truth, barrier));
      assertEquals(
          List.of("ADVANCED", "CONFLICT"),
          java.util.stream.Stream.of(first.get(), second.get())
              .sorted()
              .toList());
    }

    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(15, verified.cursor().lastSequence());
    assertEquals(truth.candidate(), verified.candidate());
    assertEquals(truth.workerResult(), verified.workerResult());
    assertEquals(
        15L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_candidates", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_terminal_bindings",
            fixture.manifest()));
  }

  @Test
  void successfulParentAndSealCommitAtomicallyAtSequenceSeventeen() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-success-tx-c",
            "case-terminal-success-tx-c",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);

    GraphAttemptCursor sealed =
        store()
            .completeParentAndSeal(
                fixture.manifest(),
                childTerminal,
                parentTruth.terminal(),
                parentTruth.artifact(),
                STARTED.plusMillis(15));

    assertEquals(17, sealed.lastSequence());
    assertEquals(GraphAttemptPhase.TERMINAL, sealed.phase());
    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(sealed, verified.cursor());
    assertEquals(parentTruth.terminal(), verified.parentRun());
    assertEquals(childTruth.terminal(), verified.childRun());
    assertEquals(parentTruth.artifact(), verified.artifact());
    assertTrue(verified.terminalSealPresent());
    assertEquals(GraphAttemptOutcome.SUCCEEDED, verified.outcome());
    assertEquals(2, verified.terminalBindings().size());
    assertEquals(
        17L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_seals", fixture.manifest()));
  }

  @Test
  void v10RestrictedExecutorOwnsOnlyExactAtomicTerminalFunctions()
      throws Exception {
    Fixture fixture =
        terminalFixture(
            "slot-v9-runtime-authority",
            "case-v9-runtime-authority",
            "principal");
    AttributedPrefix prefix = advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    insertCaptureFor(fixture);
    AtomicReference<String> childPayload = new AtomicReference<>();
    AtomicReference<String> parentPayload = new AtomicReference<>();
    GraphAttemptCursor characterizedChild =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    store()
        .completeParentAndSeal(
            fixture.manifest(),
            characterizedChild,
            parentTruth.terminal(),
            parentTruth.artifact(),
            STARTED.plusMillis(15));
    childPayload.set(childSemanticPayload(fixture.manifest()));
    parentPayload.set(parentSemanticPayload(fixture.manifest()));
    assertFalse(
        jdbc.sql(
                "SELECT pg_catalog.jsonb_exists("
                    + "CAST(:payload AS jsonb) -> 'event', "
                    + "'committed_at')")
            .param("payload", childPayload.get())
            .query(Boolean.class)
            .single());
    assertFalse(
        jdbc.sql(
                "SELECT pg_catalog.jsonb_exists("
                    + "CAST(:payload AS jsonb) -> 'parent_event', "
                    + "'committed_at') OR pg_catalog.jsonb_exists("
                    + "CAST(:payload AS jsonb) -> 'seal_event', "
                    + "'committed_at')")
            .param("payload", parentPayload.get())
            .query(Boolean.class)
            .single());
    clean();
    advanceToTwoAttributions(fixture);
    insertCaptureFor(fixture);
    assertEquals(
        14,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot()
            .cursor()
            .lastSequence());

    String nonce =
        UUID.randomUUID().toString().replace("-", "");
    String tableOwner = "emergeos_pack010_schema_owner";
    String terminalOwner = "emergeos_terminal_owner";
    String executorRole = "emergeos_graph_executor";
    String failureResumerRole = "emergeos_failure_resumer";
    String providerAttestorRole = "emergeos_provider_attestor";
    String schemaVersionDecoyRole = "v10_schema_helper_decoy_" + nonce;
    String writerRole = "v9_api_writer_" + nonce;
    String readerRole = "v9_graph_reader_" + nonce;
    String executorPassword = UUID.randomUUID().toString();
    String failureResumerPassword = UUID.randomUUID().toString();
    String providerAttestorPassword = UUID.randomUUID().toString();
    String writerPassword = UUID.randomUUID().toString();
    String readerPassword = UUID.randomUUID().toString();
    String originalFailureGuardDefinition =
        jdbc.sql(
                "SELECT pg_catalog.pg_get_functiondef("
                    + "'public.agent_graph_require_failure_resumer_v12("
                    + "regprocedure)'::regprocedure)")
            .query(String.class)
            .single();
    String originalExecutorGuardDefinition =
        jdbc.sql(
                "SELECT pg_catalog.pg_get_functiondef("
                    + "'public.agent_graph_require_executor_v10("
                    + "regprocedure)'::regprocedure)")
            .query(String.class)
            .single();
    createNoLoginRole(tableOwner);
    createNoLoginRole(terminalOwner);
    createRuntimeRole(executorRole, executorPassword);
    createRuntimeRole(failureResumerRole, failureResumerPassword);
    createRuntimeRole(providerAttestorRole, providerAttestorPassword);
    createNoLoginRole(schemaVersionDecoyRole);
    createRuntimeRole(writerRole, writerPassword);
    createRestrictedReaderRole(readerRole, readerPassword);
    try {
      transferPublicRelationOwnership(tableOwner);
      provisionV10Authority(
          tableOwner,
          terminalOwner,
          executorRole,
          failureResumerRole,
          providerAttestorRole,
          writerRole);
      DataSource executorDataSource =
          dataSource(executorRole, executorPassword);
      DataSource writerDataSource =
          dataSource(writerRole, writerPassword);

      assertEquals(
          List.of(false, false, false),
          jdbc.sql(
                  """
                  SELECT role.rolcanlogin,
                         role.rolsuper,
                         role.rolinherit
                  FROM pg_roles role
                  WHERE role.rolname = :role
                  """)
              .param("role", terminalOwner)
              .query(
                  (resultSet, rowNumber) ->
                      List.of(
                          resultSet.getBoolean("rolcanlogin"),
                          resultSet.getBoolean("rolsuper"),
                          resultSet.getBoolean("rolinherit")))
              .single());
      assertEquals(
          0L,
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM pg_auth_members membership
                  JOIN pg_roles member
                    ON member.oid = membership.member
                  WHERE member.rolname IN (:executor, :writer, :reader)
                  """)
              .param("executor", executorRole)
              .param("writer", writerRole)
              .param("reader", readerRole)
              .query(Long.class)
              .single());
      assertEquals(
          0L,
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_roles role
                  CROSS JOIN pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE role.rolname IN (
                    :terminalOwner,
                    :executor,
                    :failureResumer,
                    :providerAttestor)
                    AND namespace.nspname = 'public'
                    AND relation.relname = 'flyway_schema_history'
                    AND (
                      relation.relowner = role.oid
                      OR pg_catalog.has_table_privilege(
                        role.rolname, relation.oid,
                        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,'
                          || 'REFERENCES,TRIGGER,MAINTAIN')
                      OR pg_catalog.has_any_column_privilege(
                        role.rolname, relation.oid,
                        'SELECT,INSERT,UPDATE,REFERENCES'))
                  """)
              .param("terminalOwner", terminalOwner)
              .param("executor", executorRole)
              .param("failureResumer", failureResumerRole)
              .param("providerAttestor", providerAttestorRole)
              .query(Long.class)
              .single());
      assertEquals(
          tableOwner,
          jdbc.sql(
                  """
                  SELECT owner.rolname
                  FROM pg_catalog.pg_proc procedure
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid = procedure.proowner
                  WHERE procedure.oid = pg_catalog.to_regprocedure(
                    'public.emergeos_pack010_schema_version_v1()')
                    AND procedure.prosecdef
                    AND pg_catalog.encode(
                      pg_catalog.sha256(pg_catalog.convert_to(
                        procedure.prosrc, 'UTF8')), 'hex')
                      = '1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143'
                  """)
              .query(String.class)
              .single());
      assertEquals(
          List.of(
              "emergeos_failure_resumer|EXECUTE|false",
              "emergeos_graph_executor|EXECUTE|false",
              "emergeos_provider_attestor|EXECUTE|false",
              "emergeos_terminal_owner|EXECUTE|false"),
          jdbc.sql(
                  """
                  SELECT COALESCE(grantee.rolname, 'PUBLIC') || '|'
                         || acl.privilege_type || '|'
                         || acl.is_grantable::text
                  FROM pg_catalog.pg_proc procedure
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                    COALESCE(
                      procedure.proacl,
                      pg_catalog.acldefault('f', procedure.proowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee
                    ON grantee.oid = acl.grantee
                  WHERE procedure.oid = pg_catalog.to_regprocedure(
                    'public.emergeos_pack010_schema_version_v1()')
                    AND acl.grantee <> procedure.proowner
                  ORDER BY COALESCE(grantee.rolname, 'PUBLIC'),
                           acl.privilege_type,
                           acl.is_grantable
                  """)
              .query(String.class)
              .list());
      assertEquals(
          terminalOwner,
          jdbc.sql(
                  """
                  SELECT min(owner.rolname)
                  FROM pg_proc procedure
                  JOIN pg_namespace namespace
                    ON namespace.oid = procedure.pronamespace
                  JOIN pg_roles owner
                    ON owner.oid = procedure.proowner
                  WHERE namespace.nspname = 'public'
                    AND procedure.proname IN (
                      'agent_graph_complete_child_v10',
                      'agent_graph_complete_parent_and_seal_v10'
                    )
                  HAVING count(DISTINCT owner.oid) = 1
                  """)
              .query(String.class)
              .single());

      assertForgedGucCannotTerminalize(
          writerDataSource, fixture, childTruth.terminal(), "42501");
      assertForgedGucCannotTerminalize(
          executorDataSource, fixture, childTruth.terminal(), "42501");
      assertSqlPermissionDeniedWithRollback(
          writerDataSource,
          "SET ROLE " + terminalOwner);
      assertSqlPermissionDeniedWithRollback(
          executorDataSource,
          "SET ROLE " + terminalOwner);
      assertSqlPermissionDeniedWithRollback(
          writerDataSource,
          "SET ROLE " + POSTGRES.getUsername());
      assertSqlPermissionDeniedWithRollback(
          executorDataSource,
          "SET ROLE " + POSTGRES.getUsername());
      for (DataSource restricted :
          List.of(writerDataSource, executorDataSource)) {
        assertSqlPermissionDeniedWithRollback(
            restricted,
            "ALTER TABLE public.agent_runs DISABLE TRIGGER "
                + "agent_graph_run_selector_guard_v10");
        assertSqlPermissionDeniedWithRollback(
            restricted, "TRUNCATE public.agent_runs");
        assertCannotGrantUpdate(restricted, executorRole);
        assertSqlPermissionDeniedWithRollback(
            restricted,
            "CREATE TRIGGER v9_forged_trigger BEFORE UPDATE ON "
                + "public.agent_runs FOR EACH ROW EXECUTE FUNCTION "
                + "public.agent_graph_run_selector_guard_v10()");
      }
      assertSqlPermissionDeniedWithRollback(
          writerDataSource,
          "SELECT public.agent_graph_complete_child_v10('{}'::jsonb)");
      assertSqlPermissionDeniedWithRollback(
          executorDataSource,
          "SELECT public.agent_graph_authorize_terminal_v8("
              + "'x', repeat('0', 64)::char(64), 'CHILD', 'x')");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> new PostgresGraphTerminalExecutor(writerDataSource));
      assertEquals(
          14,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());

      String startupChildDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public.agent_graph_complete_child_v10(jsonb)'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_complete_child_v10(payload jsonb)
              RETURNS jsonb
              LANGUAGE plpgsql
              SECURITY DEFINER
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
              BEGIN
                RETURN pg_catalog.jsonb_build_object('forged', true);
              END
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(
                executorDataSource));
      } finally {
        jdbc.sql(startupChildDefinition).update();
      }

      String startupGuardDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public.agent_graph_run_selector_guard_v10()'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_run_selector_guard_v10()
              RETURNS trigger
              LANGUAGE plpgsql
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
              BEGIN
                RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
              END
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(
                executorDataSource));
      } finally {
        jdbc.sql(startupGuardDefinition).update();
      }

      String startupHelperDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public.agent_graph_require_executor_v10(regprocedure)'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_require_executor_v10(
                  expected_function regprocedure)
              RETURNS void
              LANGUAGE plpgsql
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
              BEGIN
                RETURN;
              END
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(
                executorDataSource));
      } finally {
        jdbc.sql(startupHelperDefinition).update();
      }
      jdbc.sql(
              "ALTER FUNCTION "
                  + "public.agent_graph_require_executor_v10(regprocedure) "
                  + "OWNER TO "
                  + writerRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(
                executorDataSource));
      } finally {
        jdbc.sql(
                "ALTER FUNCTION "
                    + "public.agent_graph_require_executor_v10(regprocedure) "
                    + "OWNER TO "
                    + tableOwner)
            .update();
        jdbc.sql(
                "GRANT EXECUTE ON FUNCTION "
                    + "public.agent_graph_require_executor_v10(regprocedure) "
                    + "TO "
                    + terminalOwner)
            .update();
      }

      assertGraphExecutorFunctionSurface(executorDataSource);
      PostgresGraphTerminalExecutor executor =
          new PostgresGraphTerminalExecutor(executorDataSource);
      String schemaVersionHelper =
          "public.emergeos_pack010_schema_version_v1()";

      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " TO "
                  + schemaVersionDecoyRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(executorDataSource),
            "GRAPH_SCHEMA_HELPER_FIFTH_GRANTEE_NOT_REJECTED");
      } finally {
        jdbc.sql(
                "REVOKE EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " FROM "
                    + schemaVersionDecoyRole)
            .update();
      }

      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " TO PUBLIC")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(executorDataSource),
            "GRAPH_SCHEMA_HELPER_PUBLIC_EXECUTE_NOT_REJECTED");
      } finally {
        jdbc.sql(
                "REVOKE EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " FROM PUBLIC")
            .update();
      }

      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " TO "
                  + providerAttestorRole
                  + " WITH GRANT OPTION")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(executorDataSource),
            "GRAPH_SCHEMA_HELPER_GRANT_OPTION_NOT_REJECTED");
      } finally {
        jdbc.sql(
                "REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " FROM "
                    + providerAttestorRole)
            .update();
      }

      jdbc.sql(
              "REVOKE EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " FROM "
                  + providerAttestorRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> new PostgresGraphTerminalExecutor(executorDataSource),
            "GRAPH_SCHEMA_HELPER_COUNT_DRIFT_NOT_REJECTED");
      } finally {
        jdbc.sql(
                "GRANT EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " TO "
                    + providerAttestorRole)
            .update();
      }

      GraphAttemptSnapshot beforeProviderSwap =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      long eventsBeforeProviderSwap =
          countForAttempt(
              "agent_graph_attempt_events", fixture.manifest());
      AtomicBoolean providerSwapAuthorityRechecked =
          new AtomicBoolean();
      AtomicBoolean providerSwapSemanticReturned =
          new AtomicBoolean();
      PostgresGraphTerminalExecutor providerSwapGuarded =
          new PostgresGraphTerminalExecutor(
              executorDataSource,
              point -> {
                if (point
                    == PostgresGraphTerminalExecutor.ProbePoint
                        .AFTER_AUTHORITY_RECHECK) {
                  providerSwapAuthorityRechecked.set(true);
                }
                if (point
                    == PostgresGraphTerminalExecutor.ProbePoint
                        .AFTER_SEMANTIC_FUNCTION) {
                  providerSwapSemanticReturned.set(true);
                }
              });
      jdbc.sql(
              "REVOKE EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " FROM "
                  + providerAttestorRole)
          .update();
      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + schemaVersionHelper
                  + " TO "
                  + schemaVersionDecoyRole)
          .update();
      try {
        GraphAttemptIntegrityException providerSwapRejection =
            assertThrows(
                GraphAttemptIntegrityException.class,
                () ->
                    PostgresGraphTerminalExecutorTestAccess.completeChild(
                        providerSwapGuarded, childPayload.get()),
                "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_NOT_REJECTED");
        assertEquals(
            "42501",
            postgresFailure(providerSwapRejection).getSQLState(),
            "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_NOT_REJECTED_BY_EXACT_GUARD");
        assertTrue(
            providerSwapAuthorityRechecked.get(),
            "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_AUTHORITY_RECHECK_NOT_REACHED");
        assertFalse(
            providerSwapSemanticReturned.get(),
            "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_SEMANTIC_RETURNED");
        assertEquals(
            beforeProviderSwap,
            assertInstanceOf(
                    GraphAttemptVerification.Valid.class,
                    store().findVerified(fixture.manifest()))
                .snapshot(),
            "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_MUTATED_STATE");
        assertEquals(
            eventsBeforeProviderSwap,
            countForAttempt(
                "agent_graph_attempt_events", fixture.manifest()),
            "GRAPH_SCHEMA_HELPER_PROVIDER_SWAP_MUTATED_EVENTS");
      } finally {
        jdbc.sql(
                "REVOKE EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " FROM "
                    + schemaVersionDecoyRole)
            .update();
        jdbc.sql(
                "GRANT EXECUTE ON FUNCTION "
                    + schemaVersionHelper
                    + " TO "
                    + providerAttestorRole)
            .update();
      }
      String escapedTracePayload =
          jdbc.sql(
                  """
                  SELECT pg_catalog.jsonb_set(
                    CAST(:payload AS jsonb),
                    ARRAY['trace_events', '0', 'run_id'],
                    pg_catalog.to_jsonb('unrelated-run'::text),
                    false
                  )::text
                  """)
              .param("payload", childPayload.get())
              .query(String.class)
              .single();
      String unknownNestedKeyPayload =
          jdbc.sql(
                  """
                  SELECT pg_catalog.jsonb_set(
                    CAST(:payload AS jsonb),
                    ARRAY['terminal_run', 'forged_column'],
                    pg_catalog.to_jsonb('ignored-value'::text),
                    true
                  )::text
                  """)
              .param("payload", childPayload.get())
              .query(String.class)
              .single();
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> PostgresGraphTerminalExecutorTestAccess.completeChild(executor, escapedTracePayload));
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> PostgresGraphTerminalExecutorTestAccess.completeChild(executor, unknownNestedKeyPayload));
      assertEquals(
          14,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());

      AtomicBoolean childFaultReached = new AtomicBoolean();
      PostgresGraphTerminalExecutor faultingChild =
          new PostgresGraphTerminalExecutor(
              executorDataSource,
              point -> {
                if (point
                    == PostgresGraphTerminalExecutor.ProbePoint
                        .AFTER_SEMANTIC_FUNCTION) {
                  childFaultReached.set(true);
                  throw new SyntheticFault();
                }
              });
      GraphAttemptIntegrityException childFault =
          assertThrows(
              GraphAttemptIntegrityException.class,
              () -> PostgresGraphTerminalExecutorTestAccess.completeChild(faultingChild, childPayload.get()));
      assertTrue(
          childFaultReached.get(),
          () -> postgresFailure(childFault).getMessage());
      assertEquals(
          14,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());

      runV9ProcessKill(
          "CHILD",
          executorRole,
          executorPassword,
          childPayload.get());
      assertEquals(
          14,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());
      runV9TwoProcessRace(
          "CHILD",
          executorRole,
          executorPassword,
          childPayload.get());
      assertEquals(
          15,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());
      assertTrue(
          jdbc.sql(
                  """
                  SELECT count(*) = 1
                         AND bool_and(committed_at >= occurred_at)
                  FROM public.agent_graph_attempt_events
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                    AND sequence = 15
                  """)
              .param("principalId", fixture.manifest().principalId())
              .param("attemptId", fixture.manifest().attemptId())
              .query(Boolean.class)
              .single());
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> PostgresGraphTerminalExecutorTestAccess.completeChild(executor, childPayload.get()));

      String failedParentWithArtifactPayload =
          jdbc.sql(
                  """
                  SELECT pg_catalog.jsonb_set(
                    CAST(:payload AS jsonb),
                    ARRAY['terminal_run', 'lifecycle_status'],
                    pg_catalog.to_jsonb('FAILED'::text),
                    false
                  )::text
                  """)
              .param("payload", parentPayload.get())
              .query(String.class)
              .single();
      String escapedArtifactPayload =
          jdbc.sql(
                  """
                  SELECT pg_catalog.jsonb_set(
                    pg_catalog.jsonb_set(
                      CAST(:payload AS jsonb),
                      ARRAY['artifact', 'artifact_id'],
                      pg_catalog.to_jsonb('unrelated-artifact'::text),
                      false
                    ),
                    ARRAY['artifact_version', 'artifact_id'],
                    pg_catalog.to_jsonb('unrelated-artifact'::text),
                    false
                  )::text
                  """)
              .param("payload", parentPayload.get())
              .query(String.class)
              .single();
      assertThrows(
          GraphAttemptIntegrityException.class,
          () ->
              PostgresGraphTerminalExecutorTestAccess.completeParentAndSeal(executor,
                  failedParentWithArtifactPayload));
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> PostgresGraphTerminalExecutorTestAccess.completeParentAndSeal(executor, escapedArtifactPayload));
      assertEquals(
          0L,
          jdbc.sql(
                  "SELECT count(*) FROM public.artifacts "
                      + "WHERE artifact_id = 'unrelated-artifact'")
              .query(Long.class)
              .single());

      AtomicBoolean parentFaultReached = new AtomicBoolean();
      PostgresGraphTerminalExecutor faultingParent =
          new PostgresGraphTerminalExecutor(
              executorDataSource,
              point -> {
                if (point
                    == PostgresGraphTerminalExecutor.ProbePoint
                        .AFTER_SEMANTIC_FUNCTION) {
                  parentFaultReached.set(true);
                  throw new SyntheticFault();
                }
              });
      GraphAttemptIntegrityException parentFault =
          assertThrows(
              GraphAttemptIntegrityException.class,
              () ->
                  PostgresGraphTerminalExecutorTestAccess.completeParentAndSeal(faultingParent,
                      parentPayload.get()));
      assertTrue(
          parentFaultReached.get(),
          () -> String.valueOf(parentFault.getCause()));
      assertEquals(
          15,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());
      runV9ProcessKill(
          "PARENT",
          executorRole,
          executorPassword,
          parentPayload.get());
      assertEquals(
          15,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot()
              .cursor()
              .lastSequence());
      runV9TwoProcessRace(
          "PARENT",
          executorRole,
          executorPassword,
          parentPayload.get());
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> PostgresGraphTerminalExecutorTestAccess.completeParentAndSeal(executor, parentPayload.get()));
      GraphAttemptSnapshot sealed =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      assertEquals(17, sealed.cursor().lastSequence());
      assertEquals(parentTruth.terminal(), sealed.parentRun());
      assertEquals(childTruth.terminal(), sealed.childRun());
      assertEquals(parentTruth.artifact(), sealed.artifact());
      assertTrue(
          jdbc.sql(
                  """
                  SELECT count(*) = 2
                         AND count(DISTINCT committed_at) = 1
                         AND bool_and(committed_at >= occurred_at)
                  FROM public.agent_graph_attempt_events
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                    AND sequence IN (16, 17)
                  """)
              .param("principalId", fixture.manifest().principalId())
              .param("attemptId", fixture.manifest().attemptId())
              .query(Boolean.class)
              .single());

      GraphAttemptReader reader =
          PostgresGraphAttemptAccess.openReader(
              dataSource(readerRole, readerPassword));
      assertEquals(
          sealed,
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  reader.findVerified(fixture.manifest()))
              .snapshot());

      AtomicBoolean driftReachedFunction = new AtomicBoolean();
      PostgresGraphTerminalExecutor driftGuarded =
          new PostgresGraphTerminalExecutor(
              executorDataSource,
              point -> driftReachedFunction.set(true));
      jdbc.sql(
              "GRANT TEMPORARY ON DATABASE "
                  + POSTGRES.getDatabaseName()
                  + " TO "
                  + executorRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(
                "REVOKE TEMPORARY ON DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " FROM "
                    + executorRole)
            .update();
      }
      jdbc.sql(
              "GRANT SELECT ON public.captures TO "
                  + executorRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(
                "REVOKE SELECT ON public.captures FROM "
                    + executorRole)
            .update();
      }
      jdbc.sql(
              "GRANT "
                  + writerRole
                  + " TO "
                  + executorRole
                  + " WITH INHERIT FALSE, SET FALSE")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(
                "REVOKE "
                    + writerRole
                    + " FROM "
                    + executorRole)
            .update();
      }
      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) TO "
                  + executorRole
                  + " WITH GRANT OPTION")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(
                "REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION "
                    + "public.agent_graph_complete_child_v10(jsonb) FROM "
                    + executorRole)
            .update();
      }
      jdbc.sql(
              "GRANT UPDATE (lifecycle_status) ON public.agent_runs TO "
                  + executorRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            executorDataSource, null, childPayload.get(), "42501");
      } finally {
        jdbc.sql(
                "REVOKE UPDATE (lifecycle_status) ON "
                    + "public.agent_runs FROM "
                    + executorRole)
            .update();
      }
      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) "
                  + "TO PUBLIC")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            writerDataSource, null, childPayload.get(), "42501");
      } finally {
        jdbc.sql(
                "REVOKE EXECUTE ON FUNCTION "
                    + "public.agent_graph_complete_child_v10(jsonb) "
                    + "FROM PUBLIC")
            .update();
      }
      jdbc.sql(
              "GRANT EXECUTE ON FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) TO "
                  + writerRole)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            writerDataSource, null, childPayload.get(), "42501");
      } finally {
        jdbc.sql(
                "REVOKE EXECUTE ON FUNCTION "
                    + "public.agent_graph_complete_child_v10(jsonb) FROM "
                    + writerRole)
            .update();
      }
      jdbc.sql(
              "GRANT "
                  + executorRole
                  + " TO "
                  + writerRole
                  + " WITH INHERIT FALSE, SET TRUE")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            writerDataSource,
            executorRole,
            childPayload.get(),
            "42501");
      } finally {
        jdbc.sql(
                "REVOKE "
                    + executorRole
                    + " FROM "
                    + writerRole)
            .update();
      }
      jdbc.sql(
              "GRANT "
                  + terminalOwner
                  + " TO "
                  + writerRole
                  + " WITH INHERIT FALSE, SET TRUE")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            writerDataSource,
            terminalOwner,
            childPayload.get(),
            "42501");
      } finally {
        jdbc.sql(
                "REVOKE "
                    + terminalOwner
                    + " FROM "
                    + writerRole)
            .update();
      }
      jdbc.sql("ALTER ROLE " + terminalOwner + " CREATEROLE")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql("ALTER ROLE " + terminalOwner + " NOCREATEROLE")
            .update();
      }
      jdbc.sql("ALTER ROLE " + tableOwner + " LOGIN").update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql("ALTER ROLE " + tableOwner + " NOLOGIN").update();
      }
      String originalHelperDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public.agent_graph_require_executor_v10(regprocedure)'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_require_executor_v10(
                  expected_function regprocedure)
              RETURNS void
              LANGUAGE plpgsql
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
              BEGIN
                RETURN;
              END
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(originalHelperDefinition).update();
      }
      String originalFailureResumeAssertionDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public."
                      + "agent_graph_assert_failure_terminal_resume_v12()'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_assert_failure_terminal_resume_v12()
              RETURNS trigger
              LANGUAGE plpgsql
              SECURITY DEFINER
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
              BEGIN
                RETURN NULL;
              END
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () ->
                PostgresGraphTerminalExecutorTestAccess.completeChild(
                    driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(originalFailureResumeAssertionDefinition).update();
      }
      jdbc.sql(
              "ALTER FUNCTION "
                  + "public.agent_graph_complete_child_v10(jsonb) "
                  + "SET search_path = pg_catalog")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(
                "ALTER FUNCTION "
                    + "public.agent_graph_complete_child_v10(jsonb) "
                    + "SET search_path = pg_catalog, pg_temp")
            .update();
      }
      String originalChildDefinition =
          jdbc.sql(
                  "SELECT pg_catalog.pg_get_functiondef("
                      + "'public.agent_graph_complete_child_v10(jsonb)'"
                      + "::regprocedure)")
              .query(String.class)
              .single();
      jdbc.sql(
              """
              CREATE OR REPLACE FUNCTION
                public.agent_graph_complete_child_v10(payload jsonb)
              RETURNS jsonb
              LANGUAGE sql
              SECURITY DEFINER
              SET search_path = pg_catalog, pg_temp
              AS $replacement$
                SELECT pg_catalog.jsonb_build_object(
                  'forged', true)
              $replacement$
              """)
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
      } finally {
        jdbc.sql(originalChildDefinition).update();
      }
      jdbc.sql(
              "ALTER TABLE public.agent_runs DISABLE TRIGGER "
                  + "agent_graph_run_selector_guard_v10")
          .update();
      try {
        assertThrows(
            GraphAttemptIntegrityException.class,
            () -> PostgresGraphTerminalExecutorTestAccess.completeChild(driftGuarded, childPayload.get()));
        assertFalse(driftReachedFunction.get());
        assertRawSemanticRejected(
            executorDataSource, null, childPayload.get(), "55000");
      } finally {
        jdbc.sql(
                "ALTER TABLE public.agent_runs ENABLE TRIGGER "
                    + "agent_graph_run_selector_guard_v10")
            .update();
      }
    } finally {
      jdbc.sql(
              "GRANT TEMPORARY ON DATABASE "
                  + POSTGRES.getDatabaseName()
                  + " TO PUBLIC")
          .update();
      restoreV10FunctionOwner();
      transferV10HelperFunctionOwnership(POSTGRES.getUsername());
      jdbc.sql(originalFailureGuardDefinition).update();
      jdbc.sql(originalExecutorGuardDefinition).update();
      jdbc.sql(
              "DROP FUNCTION IF EXISTS "
                  + "public.emergeos_pack010_schema_version_v1()")
          .update();
      transferPublicRelationOwnership(POSTGRES.getUsername());
      jdbc.sql(
              "REASSIGN OWNED BY "
                  + terminalOwner
                  + " TO "
                  + POSTGRES.getUsername())
          .update();
      jdbc.sql(
              "REASSIGN OWNED BY "
                  + tableOwner
                  + " TO "
                  + POSTGRES.getUsername())
          .update();
      dropEphemeralRole(readerRole);
      dropEphemeralRole(writerRole);
      dropEphemeralRole(schemaVersionDecoyRole);
      dropEphemeralRole(providerAttestorRole);
      dropEphemeralRole(failureResumerRole);
      dropEphemeralRole(executorRole);
      dropEphemeralRole(terminalOwner);
      dropEphemeralRole(tableOwner);
    }
  }

  @Test
  void restrictedReaderRequiresExactProductRelationAuthorityAndRechecksEveryRead()
      throws Exception {
    SealedGraph sealed = sealedSuccessfulGraph("restricted-reader");
    String role = "pack010_reader_authority_test";
    String password = "synthetic-reader-password";
    createRestrictedReaderRole(role, password);
    DataSource readerDataSource = dataSource(role, password);

    assertThrows(
        GraphAttemptIntegrityException.class,
        () -> PostgresGraphAttemptAccess.openReader(dataSource));
    GraphAttemptReader reader =
        PostgresGraphAttemptAccess.openReader(readerDataSource);
    assertFalse(reader instanceof PostgresGraphAttemptStore);
    assertFalse(reader instanceof GraphAttemptStore);
    assertEquals(
        sealed.snapshot(),
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                reader.findVerified(sealed.fixture().manifest()))
            .snapshot());

    Map<String, Long> before = restrictedRelationCounts();
    for (String rejectedSql :
        List.of(
            "SELECT count(*) FROM captures",
            "INSERT INTO agent_runs SELECT *"
                + " FROM agent_runs WHERE false",
            "UPDATE agent_runs SET principal_id = principal_id"
                + " WHERE false",
            "DELETE FROM agent_runs WHERE false",
            "TRUNCATE agent_runs",
            "SELECT run_id FROM agent_runs WHERE false"
                + " FOR UPDATE")) {
      assertSqlPermissionDeniedWithRollback(
          readerDataSource, rejectedSql);
    }
    assertEquals(before, restrictedRelationCounts());

    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT SELECT (content) ON captures TO " + role,
        "REVOKE SELECT (content) ON captures FROM " + role);
    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT SELECT ON agent_runs TO "
            + role
            + " WITH GRANT OPTION",
        "REVOKE GRANT OPTION FOR SELECT ON agent_runs FROM "
            + role);
    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT SELECT (run_id) ON agent_runs TO "
            + role
            + " WITH GRANT OPTION",
        "REVOKE GRANT OPTION FOR SELECT (run_id)"
            + " ON agent_runs FROM "
            + role);
    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT CONNECT ON DATABASE "
            + POSTGRES.getDatabaseName()
            + " TO "
            + role
            + " WITH GRANT OPTION",
        "REVOKE GRANT OPTION FOR CONNECT ON DATABASE "
            + POSTGRES.getDatabaseName()
            + " FROM "
            + role);
    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT USAGE ON SCHEMA public TO "
            + role
            + " WITH GRANT OPTION",
        "REVOKE GRANT OPTION FOR USAGE ON SCHEMA public FROM "
            + role);
    assertReaderRejectsAuthorityDrift(
        reader,
        sealed.fixture().manifest(),
        "GRANT UPDATE ON agent_runs TO " + role,
        "REVOKE UPDATE ON agent_runs FROM " + role);
    assertEquals(
        sealed.snapshot(),
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                reader.findVerified(sealed.fixture().manifest()))
            .snapshot());
    assertEquals(before, restrictedRelationCounts());
  }

  @Test
  void restrictedReaderPinsPublicBeforePollutedTemporarySchema()
      throws Exception {
    SealedGraph sealed = sealedSuccessfulGraph("restricted-shadow");
    String role = "pack010_reader_shadow_test";
    String password = "synthetic-shadow-password";
    createRestrictedReaderRole(role, password);
    try (Connection connection =
        dataSource(role, password).getConnection()) {
      try (var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TEMP TABLE agent_graph_attempts"
                + " (LIKE public.agent_graph_attempts INCLUDING ALL)");
      }
      GraphAttemptReader reader =
          PostgresGraphAttemptAccess.openReader(
              new SingleConnectionDataSource(connection, true));
      assertEquals(
          sealed.snapshot(),
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  reader.findVerified(sealed.fixture().manifest()))
              .snapshot());
    }
  }

  @Test
  void sequenceSixteenCannotBeCommitted() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-sequence-sixteen",
            "case-terminal-sequence-sixteen",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    GraphTerminalBinding parentBinding =
        GraphTerminalBinding.parent(
            parentTruth.terminal(), parentTruth.artifact());
    GraphAttemptEvent parentEvent =
        GraphAttemptEvent.terminal(
            childTerminal,
            GraphAttemptEventType.PARENT_TERMINAL,
            GraphAttemptPhase.PARENT_TERMINAL,
            fixture.manifest().parentSelection(),
            parentBinding,
            STARTED.plusMillis(15));
    GraphAttemptSnapshot before =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    TransactionTemplate transaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    AtomicBoolean transactionBodyCompleted =
        new AtomicBoolean();

    RuntimeException rejection =
        assertThrows(
            RuntimeException.class,
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      insertRawEvent(
                          fixture.manifest(),
                          parentEvent,
                          GraphRunRole.PARENT.name());
                      updateRawHead(
                          fixture.manifest(),
                          childTerminal,
                          parentEvent);
                      assertEquals(
                          16L,
                          jdbc.sql(
                                  """
                                  SELECT last_sequence
                                  FROM agent_graph_attempt_heads
                                  WHERE principal_id = :principalId
                                    AND attempt_id = :attemptId
                                  """)
                              .param(
                                  "principalId",
                                  fixture.manifest().principalId())
                              .param(
                                  "attemptId",
                                  fixture.manifest().attemptId())
                              .query(Long.class)
                              .single());
                      transactionBodyCompleted.set(true);
                    }));

    assertTrue(transactionBodyCompleted.get());
    PSQLException postgres = postgresFailure(rejection);
    assertEquals("23514", postgres.getSQLState());
    assertEquals(
        "graph_attempt_final_state_v8",
        postgres.getServerErrorMessage().getConstraint());
    GraphAttemptSnapshot after =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(before, after);
    assertEquals(15, after.cursor().lastSequence());
    assertEquals(fixture.parent(), after.parentRun());
    assertEquals(childTruth.terminal(), after.childRun());
    assertEquals(childTruth.candidate(), after.candidate());
    assertEquals(childTruth.workerResult(), after.workerResult());
    assertEquals(
        15L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_terminal_bindings",
            fixture.manifest()));
    assertEquals(
        0L,
        countForAttempt(
            "agent_graph_attempt_seals", fixture.manifest()));
    assertEquals(
        0L,
        countForRun(
            "agent_trace_events",
            fixture.manifest().principalId(),
            fixture.parent().runId()));
    assertEquals(
        0L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.manifest().principalId(),
            fixture.parent().runId()));
    assertEquals(0L, count("artifacts"));
    assertEquals(0L, count("artifact_versions"));
  }

  @Test
  void parentAndSealFaultsRollbackEveryTerminalStatement() {
    List<PostgresGraphAttemptStore.ProbePoint> faultPoints =
        List.of(
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_ARTIFACT_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_TRACE_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_RESOURCE_BINDINGS_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_RUN_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_TERMINAL_BINDING_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_EVENT_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_PARENT_HEAD_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_SEAL_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_SEAL_EVENT_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_SEALED_HEAD_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_SEALED_VERIFIED_READ);
    for (int index = 0; index < faultPoints.size(); index++) {
      PostgresGraphAttemptStore.ProbePoint faultPoint =
          faultPoints.get(index);
      Fixture fixture =
          terminalFixture(
              "slot-terminal-parent-fault-" + index,
              "case-terminal-parent-fault-" + index,
              "principal");
      AttributedPrefix prefix =
          advanceToTwoAttributions(fixture);
      SuccessfulChildTruth childTruth =
          successfulChildTruth(fixture, prefix.attributions());
      insertCaptureFor(fixture);
      GraphAttemptCursor childTerminal =
          store()
              .completeChild(
                  fixture.manifest(),
                  prefix.cursor(),
                  AgentRunContext.fromRunning(fixture.parent()),
                  childTruth.terminal(),
                  childTruth.candidate(),
                  childTruth.workerResult(),
                  STARTED.plusMillis(14));
      ParentTerminalTruth parentTruth =
          successfulParentTruth(fixture, childTruth);
      GraphAttemptSnapshot before =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      PostgresGraphAttemptStore faulting =
          new PostgresGraphAttemptStore(
              dataSource,
              point -> {
                if (point == faultPoint) {
                  throw new SyntheticFault();
                }
              });

      assertThrows(
          io.emergeos.core.domain.GraphAttemptIntegrityException.class,
          () ->
              faulting.completeParentAndSeal(
                  fixture.manifest(),
                  childTerminal,
                  parentTruth.terminal(),
                  parentTruth.artifact(),
                  STARTED.plusMillis(15)));

      GraphAttemptSnapshot after =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      assertEquals(before, after);
      assertEquals(15, after.cursor().lastSequence());
      assertEquals(fixture.parent(), after.parentRun());
      assertEquals(childTruth.terminal(), after.childRun());
      assertEquals(childTruth.candidate(), after.candidate());
      assertEquals(childTruth.workerResult(), after.workerResult());
      assertFalse(after.terminalSealPresent());
      assertNull(after.artifact());
      assertEquals(
          15L,
          countForAttempt(
              "agent_graph_attempt_events", fixture.manifest()));
      assertEquals(
          0L,
          countForRun(
              "agent_trace_events",
              fixture.manifest().principalId(),
              fixture.parent().runId()));
      assertEquals(
          0L,
          countForRun(
              "agent_run_resource_bindings",
              fixture.manifest().principalId(),
              fixture.parent().runId()));
      assertEquals(
          1L,
          countForAttempt(
              "agent_graph_attempt_terminal_bindings",
              fixture.manifest()));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_seals", fixture.manifest()));
      assertEquals(0L, count("artifacts"));
      assertEquals(0L, count("artifact_versions"));
    }
  }

  @Test
  void concurrentParentAndSealCompletionHasExactlyOneCasWinner()
      throws Exception {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-parent-race",
            "case-terminal-parent-race",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    CyclicBarrier barrier = new CyclicBarrier(2);

    try (var pool = Executors.newFixedThreadPool(2)) {
      Future<String> first =
          pool.submit(
              () ->
                  completeParentConcurrently(
                      fixture,
                      childTerminal,
                      parentTruth,
                      barrier));
      Future<String> second =
          pool.submit(
              () ->
                  completeParentConcurrently(
                      fixture,
                      childTerminal,
                      parentTruth,
                      barrier));
      assertEquals(
          List.of("ADVANCED", "CONFLICT"),
          java.util.stream.Stream.of(first.get(), second.get())
              .sorted()
              .toList());
    }

    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(17, verified.cursor().lastSequence());
    assertEquals(GraphAttemptPhase.TERMINAL, verified.cursor().phase());
    assertEquals(parentTruth.terminal(), verified.parentRun());
    assertEquals(childTruth.terminal(), verified.childRun());
    assertEquals(parentTruth.artifact(), verified.artifact());
    assertTrue(verified.terminalSealPresent());
    assertEquals(GraphAttemptOutcome.SUCCEEDED, verified.outcome());
    assertEquals(2, verified.terminalBindings().size());
    assertEquals(
        17L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_seals", fixture.manifest()));
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_attempt_candidates", fixture.manifest()));
    assertEquals(
        1L,
        countWorkerResultsForChild(
            fixture.manifest().principalId(),
            fixture.child().runId()));
    assertEquals(1L, count("artifacts"));
    assertEquals(1L, count("artifact_versions"));
    assertEquals(
        6L,
        countForRun(
            "agent_trace_events",
            fixture.manifest().principalId(),
            fixture.parent().runId()));
    assertEquals(
        3L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.manifest().principalId(),
            fixture.parent().runId()));
  }

  @Test
  void sealedGraphTerminalDetailsAndArtifactVersionAreImmutable() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-immutable",
            "case-terminal-immutable",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    store()
        .completeParentAndSeal(
            fixture.manifest(),
            childTerminal,
            parentTruth.terminal(),
            parentTruth.artifact(),
            STARTED.plusMillis(15));
    GraphAttemptSnapshot before =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();

    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_trace_events
                    SET status = 'TAMPERED'
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                      AND sequence = 1
                    """)
                .param(
                    "principalId",
                    fixture.manifest().principalId())
                .param("runId", fixture.parent().runId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_run_resource_bindings
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                      AND role = 'HANDOFF'
                    """)
                .param(
                    "principalId",
                    fixture.manifest().principalId())
                .param("runId", fixture.parent().runId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE artifact_versions
                    SET content = content || ' tampered'
                    WHERE principal_id = :principalId
                      AND artifact_id = :artifactId
                      AND version = 1
                    """)
                .param(
                    "principalId",
                    fixture.manifest().principalId())
                .param(
                    "artifactId", fixture.manifest().artifactId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM artifact_versions
                    WHERE principal_id = :principalId
                      AND artifact_id = :artifactId
                      AND version = 1
                    """)
                .param(
                    "principalId",
                    fixture.manifest().principalId())
                .param(
                    "artifactId", fixture.manifest().artifactId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE artifacts
                    SET source_capture_id = source_capture_id || '-tampered'
                    WHERE principal_id = :principalId
                      AND artifact_id = :artifactId
                    """)
                .param(
                    "principalId",
                    fixture.manifest().principalId())
                .param(
                    "artifactId", fixture.manifest().artifactId())
                .update());

    GraphAttemptSnapshot after =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(before, after);
  }

  @Test
  void disablingOnlyTerminalDetailImmutableCannotMoveTruthOutOfSealedGraph() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-owner-move",
            "case-terminal-owner-move",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    store()
        .completeParentAndSeal(
            fixture.manifest(),
            childTerminal,
            parentTruth.terminal(),
            parentTruth.artifact(),
            STARTED.plusMillis(15));
    GraphAttemptSnapshot before =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    String genericRunId = "generic-terminal-owner-move";
    cloneAsGenericTerminalRun(
        fixture.manifest().principalId(),
        fixture.parent().runId(),
        genericRunId);

    jdbc.sql(
            """
            ALTER TABLE agent_trace_events
            DISABLE TRIGGER graph_trace_immutable_v8
            """)
        .update();
    try {
      assertSqlState(
          "23514",
          () ->
              jdbc.sql(
                      """
                      UPDATE agent_trace_events
                      SET run_id = :genericRunId
                      WHERE principal_id = :principalId
                        AND run_id = :graphRunId
                        AND sequence = 1
                      """)
                  .param("genericRunId", genericRunId)
                  .param(
                      "principalId",
                      fixture.manifest().principalId())
                  .param("graphRunId", fixture.parent().runId())
                  .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_trace_events
              ENABLE TRIGGER graph_trace_immutable_v8
              """)
          .update();
    }

    jdbc.sql(
            """
            ALTER TABLE agent_run_resource_bindings
            DISABLE TRIGGER graph_resource_binding_immutable_v8
            """)
        .update();
    try {
      assertSqlState(
          "23514",
          () ->
              jdbc.sql(
                      """
                      UPDATE agent_run_resource_bindings
                      SET run_id = :genericRunId
                      WHERE principal_id = :principalId
                        AND run_id = :graphRunId
                        AND role = 'ARTIFACT'
                      """)
                  .param("genericRunId", genericRunId)
                  .param(
                      "principalId",
                      fixture.manifest().principalId())
                  .param("graphRunId", fixture.parent().runId())
                  .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_run_resource_bindings
              ENABLE TRIGGER graph_resource_binding_immutable_v8
              """)
          .update();
    }

    assertEquals(
        0L,
        countForRun(
            "agent_trace_events",
            fixture.manifest().principalId(),
            genericRunId));
    assertEquals(
        0L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.manifest().principalId(),
            genericRunId));
    assertEquals(
        before,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot());
  }

  @Test
  void freshReaderRejectsDbAcceptedTraceHashForgeryWithoutRepair() {
    SealedGraph sealed =
        sealedSuccessfulGraph("trace-hash-forgery");
    Fixture fixture = sealed.fixture();
    jdbc.sql(
            """
            ALTER TABLE agent_trace_events
            DISABLE TRIGGER graph_trace_immutable_v8
            """)
        .update();
    try {
      assertEquals(
          1,
          jdbc.sql(
                  """
                  UPDATE agent_trace_events
                  SET event_hash = :forgedHash
                  WHERE principal_id = :principalId
                    AND run_id = :runId
                    AND sequence = 1
                  """)
              .param("forgedHash", "f".repeat(64))
              .param(
                  "principalId",
                  fixture.manifest().principalId())
              .param("runId", fixture.parent().runId())
              .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_trace_events
              ENABLE TRIGGER graph_trace_immutable_v8
              """)
          .update();
    }
    TraceTruthVersion forged =
        traceTruthVersion(
            fixture.manifest().principalId(),
            fixture.parent().runId(),
            1);
    assertEquals("f".repeat(64), forged.eventHash());

    assertFreshReadersReject(fixture.manifest());
    assertEquals(
        forged,
        traceTruthVersion(
            fixture.manifest().principalId(),
            fixture.parent().runId(),
            1));
  }

  @Test
  void freshReaderRejectsDbAcceptedArtifactContentForgeryWithoutRepair() {
    SealedGraph sealed =
        sealedSuccessfulGraph("artifact-content-forgery");
    Fixture fixture = sealed.fixture();
    jdbc.sql(
            """
            ALTER TABLE artifact_versions
            DISABLE TRIGGER graph_sealed_artifact_version_immutable_v8
            """)
        .update();
    try {
      assertEquals(
          1,
          jdbc.sql(
                  """
                  UPDATE artifact_versions
                  SET content = content || ' forged'
                  WHERE principal_id = :principalId
                    AND artifact_id = :artifactId
                    AND version = 1
                  """)
              .param(
                  "principalId",
                  fixture.manifest().principalId())
              .param(
                  "artifactId", fixture.manifest().artifactId())
              .update());
    } finally {
      jdbc.sql(
              """
              ALTER TABLE artifact_versions
              ENABLE TRIGGER graph_sealed_artifact_version_immutable_v8
              """)
          .update();
    }
    ArtifactTruthVersion forged =
        artifactTruthVersion(fixture.manifest());
    assertTrue(forged.content().endsWith(" forged"));

    assertFreshReadersReject(fixture.manifest());
    assertEquals(forged, artifactTruthVersion(fixture.manifest()));
  }

  @Test
  void freshReaderRejectsRelationallyConsistentSealHashForgeryWithoutRepair() {
    SealedGraph sealed =
        sealedSuccessfulGraph("seal-hash-forgery");
    Fixture fixture = sealed.fixture();
    String forgedHash = "f".repeat(64);
    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_seals
            DISABLE TRIGGER agent_graph_seals_immutable_v7
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_graph_attempt_events
            DISABLE TRIGGER agent_graph_events_immutable_v7
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
                        UPDATE agent_graph_attempt_seals
                        SET seal_hash = :forgedHash
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                        """)
                    .param("forgedHash", forgedHash)
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
                        UPDATE agent_graph_attempt_events
                        SET evidence_hash = :forgedHash
                        WHERE principal_id = :principalId
                          AND attempt_id = :attemptId
                          AND sequence = 17
                        """)
                    .param("forgedHash", forgedHash)
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
              ALTER TABLE agent_graph_attempt_events
              ENABLE TRIGGER agent_graph_events_immutable_v7
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_graph_attempt_seals
              ENABLE TRIGGER agent_graph_seals_immutable_v7
              """)
          .update();
    }
    SealTruthVersion forged =
        sealTruthVersion(fixture.manifest());
    assertEquals(forgedHash, forged.sealHash());
    assertEquals(forgedHash, forged.eventEvidenceHash());

    assertFreshReadersReject(fixture.manifest());
    assertEquals(forged, sealTruthVersion(fixture.manifest()));
  }

  @Test
  void sealedGraphKeepsItsExactArtifactVersionAfterLineageAdvances() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-artifact-revision",
            "case-terminal-artifact-revision",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(fixture, prefix.attributions());
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                STARTED.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(fixture, childTruth);
    store()
        .completeParentAndSeal(
            fixture.manifest(),
            childTerminal,
            parentTruth.terminal(),
            parentTruth.artifact(),
            STARTED.plusMillis(15));
    ArtifactLineageEntry versionOne =
        parentTruth.artifact().current();
    String revisedContent =
        versionOne.content() + " 第二版保留原始 graph lineage。";
    ArtifactLineageEntry versionTwo =
        new ArtifactLineageEntry(
            2,
            revisedContent,
            IntegrityHashes.utf8ContentHash(revisedContent),
            1,
            versionOne.contentHash(),
            STARTED.plusMillis(16));
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    var revision =
        new PostgresArtifactLineageStore(dataSource, transactions)
            .compareAndSwap(
                fixture.manifest().principalId(),
                fixture.manifest().artifactId(),
                1,
                versionOne.contentHash(),
                versionTwo);
    ArtifactLineage advanced =
        assertInstanceOf(
                io.emergeos.core.port.ArtifactLineageStore
                    .RevisionResult.Revised.class,
                revision)
            .lineage();
    assertEquals(2, advanced.current().version());

    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(17, verified.cursor().lastSequence());
    assertEquals(parentTruth.artifact(), verified.artifact());
    assertEquals(
        versionOne.contentHash(),
        verified.terminalBindings().stream()
            .filter(
                binding ->
                    binding.role() == GraphRunRole.PARENT)
            .findFirst()
            .orElseThrow()
            .effectHash());
  }

  @Test
  void genericRunStoreCannotTerminalizeGraphBoundChildWithoutPermit() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-generic-denied",
            "case-terminal-generic-denied",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    AgentRun terminalChild =
        failedChild(fixture, prefix.attributions());

    RuntimeException rejection =
        assertThrows(
            RuntimeException.class,
            () ->
                genericRunStore(fixture)
                    .completeWorker(
                        AgentRunContext.fromRunning(fixture.parent()),
                        terminalChild,
                        null));
    assertEquals("55000", postgresFailure(rejection).getSQLState());

    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            store().findVerified(fixture.manifest()));
    assertEquals(prefix.cursor(), valid.snapshot().cursor());
    assertEquals(14, valid.snapshot().cursor().lastSequence());
    assertEquals(
        GraphAttemptPhase.PROVIDER_ATTRIBUTED,
        valid.snapshot().cursor().phase());
    assertEquals(fixture.child(), valid.snapshot().childRun());
    assertEquals(0L, count("agent_trace_events"));
    assertEquals(0L, count("agent_run_resource_bindings"));
    assertEquals(0L, count("agent_worker_results"));
    assertEquals(14L, count("agent_graph_attempt_events"));
    assertEquals(
        0L,
        count("agent_graph_attempt_terminal_bindings"));
  }

  @Test
  void legacyPermitDoesNotRemainReusableAfterV10TerminalPath() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-one-shot-permit",
            "case-terminal-one-shot-permit",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    AgentRun terminalChild =
        failedChild(fixture, prefix.attributions());
    AtomicReference<String> permitAfterUpdate =
        new AtomicReference<>();
    PostgresGraphAttemptStore guarded =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point
                  == PostgresGraphAttemptStore.ProbePoint
                      .AFTER_TERMINAL_RUN_UPDATE) {
                permitAfterUpdate.set(
                    jdbc.sql(
                            """
                            SELECT current_setting(
                              'emergeos.graph_terminal_permit', true
                            )
                            """)
                        .query(String.class)
                        .single());
              }
            });

    GraphAttemptCursor terminal =
        guarded.completeChild(
            fixture.manifest(),
            prefix.cursor(),
            AgentRunContext.fromRunning(fixture.parent()),
            terminalChild,
            null,
            null,
            STARTED.plusMillis(14));

    assertEquals(15, terminal.lastSequence());
    assertEquals("", permitAfterUpdate.get());
  }

  @Test
  void terminalPermitRejectsWrongRoleAndRunWithoutMutation() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-wrong-permit",
            "case-terminal-wrong-permit",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);

    assertSqlState(
        "55000",
        () ->
            callTerminalPermit(
                fixture.manifest(),
                GraphRunRole.PARENT,
                fixture.parent().runId()));
    assertSqlState(
        "55000",
        () ->
            callTerminalPermit(
                fixture.manifest(),
                GraphRunRole.CHILD,
                fixture.child().runId() + "-wrong"));

    GraphAttemptSnapshot verified =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot();
    assertEquals(prefix.cursor(), verified.cursor());
    assertEquals(fixture.child(), verified.childRun());
    assertEquals(0L, count("agent_trace_events"));
    assertEquals(
        0L,
        count("agent_graph_attempt_terminal_bindings"));
  }

  @Test
  void liveGateRemainsClosedWhileSameWriterCanForgeCustomGucPermit() {
    Fixture fixture =
        terminalFixture(
            "slot-terminal-forged-guc",
            "case-terminal-forged-guc",
            "principal");
    AttributedPrefix prefix =
        advanceToTwoAttributions(fixture);
    AgentRun terminalChild =
        failedChild(fixture, prefix.attributions());

    TransactionTemplate rawWriter =
        new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));
    assertThrows(
        SyntheticFault.class,
        () ->
            rawWriter.executeWithoutResult(
                ignored -> {
                  rawTerminalizeWithForgedGuc(fixture, terminalChild);
                  assertEquals(
                      "FAILED",
                      jdbc.sql(
                              """
                              SELECT lifecycle_status
                              FROM agent_runs
                              WHERE principal_id = :principalId
                                AND run_id = :runId
                              """)
                          .param(
                              "principalId",
                              fixture.manifest().principalId())
                          .param("runId", fixture.child().runId())
                          .query(String.class)
                          .single());
                  throw new SyntheticFault();
                }));

    assertThrows(
        TransactionSystemException.class,
        () ->
            rawWriter.executeWithoutResult(
                ignored ->
                    rawTerminalizeWithForgedGuc(
                        fixture, terminalChild)));

    assertEquals(
        "RUNNING",
        jdbc.sql(
                """
                SELECT lifecycle_status
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("principalId", fixture.manifest().principalId())
            .param("runId", fixture.child().runId())
            .query(String.class)
            .single());
    assertEquals(
        prefix.cursor(),
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot()
            .cursor());
    assertEquals(
        14L,
        countForAttempt(
            "agent_graph_attempt_events", fixture.manifest()));
  }

  @Test
  void failedChildFaultsRollbackEveryTerminalStatement() {
    List<PostgresGraphAttemptStore.ProbePoint> faultPoints =
        List.of(
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_TRACE_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_RESOURCE_BINDINGS_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_RUN_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_BINDING_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_EVENT_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_HEAD_UPDATE);
    for (int index = 0; index < faultPoints.size(); index++) {
      PostgresGraphAttemptStore.ProbePoint faultPoint =
          faultPoints.get(index);
      Fixture fixture =
          terminalFixture(
              "slot-terminal-child-fault-" + index,
              "case-terminal-child-fault-" + index,
              "principal");
      AttributedPrefix prefix =
          advanceToTwoAttributions(fixture);
      AgentRun terminalChild =
          failedChild(fixture, prefix.attributions());
      GraphAttemptSnapshot before =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      PostgresGraphAttemptStore faulting =
          new PostgresGraphAttemptStore(
              dataSource,
              point -> {
                if (point == faultPoint) {
                  throw new SyntheticFault();
                }
              });

      assertThrows(
          io.emergeos.core.domain.GraphAttemptIntegrityException.class,
          () ->
              faulting.completeChild(
                  fixture.manifest(),
                  prefix.cursor(),
                  AgentRunContext.fromRunning(fixture.parent()),
                  terminalChild,
                  null,
                  null,
                  STARTED.plusMillis(14)));

      GraphAttemptSnapshot after =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      assertEquals(before, after);
      assertEquals(
          14L,
          countForAttempt(
              "agent_graph_attempt_events", fixture.manifest()));
      assertEquals(
          0L,
          countForRun(
              "agent_trace_events",
              fixture.manifest().principalId(),
              fixture.child().runId()));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_terminal_bindings",
              fixture.manifest()));
    }
  }

  @Test
  void successfulChildFaultsRollbackCandidateThroughVerifiedRead() {
    List<PostgresGraphAttemptStore.ProbePoint> faultPoints =
        List.of(
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_CANDIDATE_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_WORKER_RESULT_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_TRACE_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_RESOURCE_BINDINGS_INSERT,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_RUN_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_TERMINAL_BINDING_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_EVENT_INSERT,
            PostgresGraphAttemptStore.ProbePoint.AFTER_HEAD_UPDATE,
            PostgresGraphAttemptStore.ProbePoint
                .AFTER_CHILD_TERMINAL_VERIFIED_READ);
    for (int index = 0; index < faultPoints.size(); index++) {
      PostgresGraphAttemptStore.ProbePoint faultPoint =
          faultPoints.get(index);
      Fixture fixture =
          terminalFixture(
              "slot-terminal-success-fault-" + index,
              "case-terminal-success-fault-" + index,
              "principal");
      AttributedPrefix prefix =
          advanceToTwoAttributions(fixture);
      SuccessfulChildTruth truth =
          successfulChildTruth(fixture, prefix.attributions());
      insertCaptureFor(fixture);
      GraphAttemptSnapshot before =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      PostgresGraphAttemptStore faulting =
          new PostgresGraphAttemptStore(
              dataSource,
              point -> {
                if (point == faultPoint) {
                  throw new SyntheticFault();
                }
              });

      assertThrows(
          io.emergeos.core.domain.GraphAttemptIntegrityException.class,
          () ->
              faulting.completeChild(
                  fixture.manifest(),
                  prefix.cursor(),
                  AgentRunContext.fromRunning(fixture.parent()),
                  truth.terminal(),
                  truth.candidate(),
                  truth.workerResult(),
                  STARTED.plusMillis(14)));

      GraphAttemptSnapshot after =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store().findVerified(fixture.manifest()))
              .snapshot();
      assertEquals(before, after);
      assertEquals(
          14L,
          countForAttempt(
              "agent_graph_attempt_events", fixture.manifest()));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_candidates", fixture.manifest()));
      assertEquals(
          0L,
          countWorkerResultsForChild(
              fixture.manifest().principalId(),
              fixture.child().runId()));
      assertEquals(
          0L,
          countForRun(
              "agent_trace_events",
              fixture.manifest().principalId(),
              fixture.child().runId()));
      assertEquals(
          0L,
          countForRun(
              "agent_run_resource_bindings",
              fixture.manifest().principalId(),
              fixture.child().runId()));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_terminal_bindings",
              fixture.manifest()));
    }
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
  void ownerAuthorityRejectsNoTtyBeforeClaim() {
    List<GraphAttemptManifest> catalog =
        authorityCatalog("case-owner", "principal");
    OwnerTtyGraphAuthority authority =
        PostgresGraphAttemptAccess.openOwnerTtyAuthority(
            dataSource,
            catalog,
            Duration.ofSeconds(30));

    OwnerTtyGraphAuthority.OwnerApprovalException rejected =
        assertThrows(
            OwnerTtyGraphAuthority.OwnerApprovalException.class,
            () ->
                authority.approve(
                    OwnerTtyGraphAuthority.Pack010Revision.R1));
    assertEquals("REAL_TTY_REQUIRED", rejected.getMessage());
    assertEquals(0L, count("agent_graph_attempts"));
    assertEquals(0L, count("agent_graph_attempt_events"));
  }

  @Test
  void ownerAuthorityRejectsCatalogIdentityDriftBeforeClaim() {
    List<GraphAttemptManifest> catalog =
        authorityCatalog("case-catalog-drift", "catalog-owner");
    GraphAttemptManifest duplicatedArtifact =
        manifestWithArtifactId(
            catalog.get(1), catalog.getFirst().artifactId());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            PostgresGraphAttemptAccess.openOwnerTtyAuthority(
                dataSource,
                List.of(
                    catalog.getFirst(),
                    duplicatedArtifact,
                    catalog.getLast()),
                Duration.ofSeconds(30)));
    assertEquals(0L, count("agent_graph_attempts"));
    assertEquals(0L, count("agent_graph_attempt_events"));
  }

  @Test
  void ownerAuthorityRealTtyMatrixBurnsRejectedClaimsAndPermit()
      throws Exception {
    Fixture piped =
        terminalFixture(
            "pack010-r1", "case-piped-r1", "piped-owner", 1);
    List<GraphAttemptManifest> pipedCatalog =
        authorityCatalog("case-piped", "piped-owner");
    ProcessResult pipedResult =
        runOwnerAuthorityProcess(
            pipedCatalog,
            false,
            io.emergeos.core.domain.GraphOperatorApproval
                .expectedChallenge(pipedCatalog.getFirst()),
            0,
            30_000,
            "approve-once");
    assertTrue(pipedResult.output().contains("REAL_TTY_REQUIRED"));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(piped.manifest()));

    List<GraphAttemptManifest> wrongCatalog =
        authorityCatalog("case-wrong", "wrong-owner");
    GraphAttemptManifest wrong = wrongCatalog.getFirst();
    ProcessResult wrongResult =
        runOwnerAuthorityProcess(
            wrongCatalog,
            true,
            "WRONG OWNER RESPONSE",
            0,
            30_000,
            "approve-once");
    assertTrue(
        wrongResult.output().contains(
            "OPERATOR_CHALLENGE_MISMATCH"));
    assertAttemptAt(wrong, 1, GraphAttemptPhase.MARKED);

    List<GraphAttemptManifest> expiredCatalog =
        authorityCatalog("case-expired", "expired-owner");
    GraphAttemptManifest expired = expiredCatalog.getFirst();
    ProcessResult expiredResult =
        runOwnerAuthorityProcess(
            expiredCatalog,
            true,
            io.emergeos.core.domain.GraphOperatorApproval
                .expectedChallenge(expired),
            300,
            100,
            "approve-once");
    assertTrue(
        expiredResult.output().contains("owner challenge expired"));
    assertAttemptAt(expired, 1, GraphAttemptPhase.MARKED);

    List<GraphAttemptManifest> successCatalog =
        authorityCatalog("case-success", "success-owner");
    GraphAttemptManifest success = successCatalog.getFirst();
    ProcessResult successResult =
        runOwnerAuthorityProcess(
            successCatalog,
            true,
            io.emergeos.core.domain.GraphOperatorApproval
                .expectedChallenge(success),
            0,
            30_000,
            "consume-race");
    assertEquals(0, successResult.exitCode(), successResult.output());
    assertTrue(successResult.output().contains("APPROVED"));
    assertTrue(
        successResult.output().contains("PERMIT_RACE_ONE_WINNER"));
    assertTrue(
        successResult.output().contains("SECOND_CONSUME_REJECTED"));
    assertTrue(
        successResult.output().contains("WRONG_COORDINATOR_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "SECOND_AUTHORIZED_CLAIM_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "GENERIC_APPROVAL_DISABLED"));
    assertTrue(
        successResult.output().contains("WRONG_EGRESS_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "EGRESS_RACE_ONE_WINNER"));
    assertTrue(
        successResult.output().contains(
            "PROCESS_REVISION_ALREADY_SELECTED"));
    assertAttemptAt(success, 7, GraphAttemptPhase.EGRESS_CONSUMED);
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(successCatalog.get(1)));

    ProcessResult replay =
        runOwnerAuthorityProcess(
            successCatalog,
            true,
            "",
            0,
            30_000,
            "expect-replay-rejection");
    assertTrue(
        replay.output().contains("execution slot was already claimed"));
    assertAttemptAt(success, 7, GraphAttemptPhase.EGRESS_CONSUMED);

    for (GraphAttemptManifest manifest :
        List.of(wrong, expired, success)) {
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_provider_attributions", manifest));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_candidates", manifest));
    }
  }

  @Test
  void ownerTerminalCapabilityIsExactOneShotAndKillRestartFailClosed()
      throws Exception {
    List<GraphAttemptManifest> successCatalog =
        authorityCatalog(
            "case-terminal-capability", "terminal-capability-owner");
    GraphAttemptManifest success = successCatalog.getFirst();
    ProcessResult successResult =
        runOwnerAuthorityProcess(
            successCatalog,
            true,
            GraphOperatorApproval.expectedChallenge(success),
            0,
            30_000,
            "terminal-capability");
    assertEquals(0, successResult.exitCode(), successResult.output());
    assertTrue(
        successResult.output().contains("TERMINAL_CAPABILITY_BOUND"));
    assertTrue(
        successResult.output().contains(
            "TERMINAL_CHILD_RACE_ONE_WINNER"));
    assertTrue(
        successResult.output().contains(
            "TERMINAL_PARENT_BEFORE_DURABLE_CHILD_REJECTED"));
    assertAttemptAt(
        success, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    assertEquals(
        2L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions", success));

    List<GraphAttemptManifest> killedCatalog =
        authorityCatalog(
            "case-terminal-capability-kill",
            "terminal-capability-kill-owner");
    GraphAttemptManifest killed = killedCatalog.getFirst();
    ProcessResult killedResult =
        runOwnerAuthorityProcess(
            killedCatalog,
            true,
            GraphOperatorApproval.expectedChallenge(killed),
            0,
            30_000,
            "kill-after-terminal-binding");
    assertEquals(91, killedResult.exitCode(), killedResult.output());
    assertAttemptAt(
        killed, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    assertEquals(
        2L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions", killed));

    ProcessResult restart =
        runOwnerAuthorityProcess(
            killedCatalog,
            true,
            "",
            0,
            30_000,
            "expect-replay-rejection");
    assertEquals(3, restart.exitCode(), restart.output());
    assertTrue(
        restart.output().contains("execution slot was already claimed"));
    assertAttemptAt(
        killed, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
  }

  @Test
  void ownerProviderSessionIntentIsExactConcurrentExpiryAndRestartFailClosed()
      throws Exception {
    List<GraphAttemptManifest> successCatalog =
        authorityCatalog(
            "case-provider-session-capability",
            "provider-session-capability-owner");
    GraphAttemptManifest success = successCatalog.getFirst();
    ProcessResult successResult =
        runOwnerAuthorityProcess(
            successCatalog,
            true,
            GraphOperatorApproval.expectedChallenge(success),
            0,
            30_000,
            "provider-session-capability");
    assertEquals(0, successResult.exitCode(), successResult.output());
    assertTrue(
        successResult.output().contains(
            "PROVIDER_SESSION_INTENT_RACE_ONE_WINNER"));
    assertTrue(
        successResult.output().contains(
            "WRONG_SESSION_REVISION_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "WRONG_SESSION_COORDINATOR_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "WRONG_SESSION_EGRESS_REJECTED"));
    assertTrue(
        successResult.output().contains(
            "PROVIDER_SESSION_CONSUME_RACE_ONE_WINNER"));
    assertTrue(
        successResult.output().contains(
            "PROVIDER_SESSION_REPLAY_REJECTED"));
    assertAttemptAt(success, 7, GraphAttemptPhase.EGRESS_CONSUMED);
    assertEquals(
        1L,
        countForAttempt(
            "agent_graph_provider_session_intents", success));
    assertEquals(
        0L,
        countForAttempt(
            "agent_graph_attempt_provider_attributions", success));

    for (String mode :
        List.of(
            "session-intent-expired",
            "kill-during-session-intent",
            "kill-after-session-intent")) {
      List<GraphAttemptManifest> catalog =
          authorityCatalog("case-" + mode, "owner-" + mode);
      GraphAttemptManifest manifest = catalog.getFirst();
      long ttlMs =
          mode.equals("session-intent-expired") ? 3_000 : 30_000;
      ProcessResult result =
          runOwnerAuthorityProcess(
              catalog,
              true,
              GraphOperatorApproval.expectedChallenge(manifest),
              0,
              ttlMs,
              mode);
      if (mode.equals("session-intent-expired")) {
        assertEquals(3, result.exitCode(), result.output());
        assertTrue(
            result.output().contains("owner capability expired"));
        assertTrue(
            result.output().contains("PROVIDER_SESSION_INTENT_DURABLE"),
            result.output());
        assertTrue(
            result.output().indexOf("PROVIDER_SESSION_INTENT_DURABLE")
                < result.output().indexOf("REJECTED owner capability expired"),
            result.output());
      } else if (mode.equals("kill-during-session-intent")) {
        assertEquals(93, result.exitCode(), result.output());
      } else {
        assertEquals(92, result.exitCode(), result.output());
      }
      assertAttemptAt(
          manifest, 7, GraphAttemptPhase.EGRESS_CONSUMED);
      assertEquals(
          mode.equals("kill-during-session-intent") ? 0L : 1L,
          countForAttempt(
              "agent_graph_provider_session_intents", manifest));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_provider_attributions", manifest));

      ProcessResult restart =
          runOwnerAuthorityProcess(
              catalog,
              true,
              "",
              0,
              30_000,
              "expect-replay-rejection");
      assertEquals(3, restart.exitCode(), restart.output());
      assertTrue(
          restart.output().contains(
              "execution slot was already claimed"));
      assertEquals(
          mode.equals("kill-during-session-intent") ? 0L : 1L,
          countForAttempt(
              "agent_graph_provider_session_intents", manifest));
    }
  }

  @Test
  void ownerHandoffExpiryKillAndRestartRemainFailClosed()
      throws Exception {
    for (String mode :
        List.of(
            "adopt-expired",
            "egress-expired",
            "kill-after-approval",
            "kill-during-adopt",
            "kill-after-adopt",
            "kill-after-egress")) {
      List<GraphAttemptManifest> catalog =
          authorityCatalog("case-" + mode, "owner-" + mode);
      GraphAttemptManifest manifest = catalog.getFirst();
      long ttlMs =
          mode.equals("adopt-expired")
                  || mode.equals("egress-expired")
              ? 3_000
              : 30_000;
      ProcessResult result =
          runOwnerAuthorityProcess(
              catalog,
              true,
              GraphOperatorApproval.expectedChallenge(manifest),
              0,
              ttlMs,
              mode);
      if (mode.equals("adopt-expired")
          || mode.equals("egress-expired")) {
        assertEquals(3, result.exitCode(), result.output());
        assertTrue(
            result.output().contains("owner capability expired"),
            result.output());
      } else {
        assertTrue(
            List.of(87, 88, 89, 90).contains(result.exitCode()),
            result.output());
      }
      assertAttemptAt(
          manifest,
          mode.equals("egress-expired")
                  || mode.equals("kill-after-egress")
              ? 7
              : 2,
          mode.equals("egress-expired")
                  || mode.equals("kill-after-egress")
              ? GraphAttemptPhase.EGRESS_CONSUMED
              : GraphAttemptPhase.OPERATOR_APPROVED);
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_provider_attributions", manifest));
      assertEquals(
          0L,
          countForAttempt(
              "agent_graph_attempt_candidates", manifest));

      ProcessResult restart =
          runOwnerAuthorityProcess(
              catalog,
              true,
              "",
              0,
              30_000,
              "expect-replay-rejection");
      assertEquals(3, restart.exitCode(), restart.output());
      assertTrue(
          restart.output().contains(
              "execution slot was already claimed"));
      assertAttemptAt(
          manifest,
          mode.equals("egress-expired")
                  || mode.equals("kill-after-egress")
              ? 7
              : 2,
          mode.equals("egress-expired")
                  || mode.equals("kill-after-egress")
              ? GraphAttemptPhase.EGRESS_CONSUMED
              : GraphAttemptPhase.OPERATOR_APPROVED);
    }
  }

  @Test
  void ownerHandoffRejectsSameDatabaseSchemaIdentitySplice()
      throws Exception {
    List<GraphAttemptManifest> catalog =
        authorityCatalog("case-schema-splice", "owner-schema-splice");
    ProcessResult result =
        runOwnerAuthorityProcess(
            catalog,
            true,
            GraphOperatorApproval.expectedChallenge(catalog.getFirst()),
            0,
            30_000,
            "schema-switch");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("CLONE_PREFIX_SEQ2"));
    assertTrue(result.output().contains("SCHEMA_IDENTITY_REJECTED"));
    assertAttemptAt(
        catalog.getFirst(),
        2,
        GraphAttemptPhase.OPERATOR_APPROVED);
  }

  @Test
  void pack010PredecessorVerificationAndClaimAreOneTransaction() {
    Fixture r1 =
        terminalFixture(
            "pack010-r1", "case-chain-r1", "principal", 1);
    Fixture r2 =
        terminalFixture(
            "pack010-r2", "case-chain-r2", "principal", 2);
    Fixture r3 =
        terminalFixture(
            "pack010-r3", "case-chain-r3", "principal", 3);

    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            store()
                .claimForOwner(
                    r2.manifest(),
                    r1.manifest(),
                    Duration.ofSeconds(30)));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(r2.manifest()));

    sealedSuccessfulGraph(r1, null);
    PostgresGraphAttemptStore.OwnerClaim r2Claim =
        store()
            .claimForOwner(
                r2.manifest(),
                r1.manifest(),
                Duration.ofSeconds(30));
    assertEquals(1, r2Claim.cursor().lastSequence());
    assertEquals(GraphAttemptPhase.MARKED, r2Claim.cursor().phase());

    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            store()
                .claimForOwner(
                    r3.manifest(),
                    r2.manifest(),
                    Duration.ofSeconds(30)));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(r3.manifest()));

    sealedSuccessfulGraph(
        r2,
        r2Claim.cursor(),
        r2Claim.expiresAt().minusSeconds(30));
    PostgresGraphAttemptStore.OwnerClaim r3Claim =
        store()
            .claimForOwner(
                r3.manifest(),
                r2.manifest(),
                Duration.ofSeconds(30));
    assertEquals(1, r3Claim.cursor().lastSequence());
    assertEquals(GraphAttemptPhase.MARKED, r3Claim.cursor().phase());
  }

  @Test
  void pack010UnknownOrExpectedManifestMismatchCannotUnlockSuccessor() {
    Fixture unknownR1 =
        terminalFixture(
            "pack010-r1", "case-unknown-r1", "unknown-owner", 1);
    Fixture unknownR2 =
        terminalFixture(
            "pack010-r2", "case-unknown-r2", "unknown-owner", 2);
    GraphAttemptCursor unknown = advanceToModelCreated(unknownR1);
    GraphProviderIntent unresolvedIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "unresolved predecessor request"),
            unknownR1.profile().modelRequested());
    store()
        .providerIntent(
            unknownR1.manifest(),
            unknown,
            unresolvedIntent,
            STARTED.plusMillis(10));

    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            store()
                .claimForOwner(
                    unknownR2.manifest(),
                    unknownR1.manifest(),
                    Duration.ofSeconds(30)));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(unknownR2.manifest()));

    Fixture storedR1 =
        terminalFixture(
            "pack010-r1", "case-stored-r1", "mismatch-owner", 1);
    Fixture mismatchR2 =
        terminalFixture(
            "pack010-r2", "case-stored-r2", "mismatch-owner", 2);
    sealedSuccessfulGraph(storedR1, null);
    GraphAttemptManifest forgedExpected =
        terminalFixture(
                "pack010-r1",
                "case-forged-expected-r1",
                "mismatch-owner",
                1)
            .manifest();

    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            store()
                .claimForOwner(
                    mismatchR2.manifest(),
                    forgedExpected,
                    Duration.ofSeconds(30)));
    assertInstanceOf(
        GraphAttemptVerification.Missing.class,
        store().findVerified(mismatchR2.manifest()));
  }

  @Test
  void concurrentPack010SuccessorClaimHasExactlyOneWinner()
      throws Exception {
    Fixture r1 =
        terminalFixture(
            "pack010-r1", "case-race-r1", "principal", 1);
    Fixture r2 =
        terminalFixture(
            "pack010-r2", "case-race-r2", "principal", 2);
    sealedSuccessfulGraph(r1, null);
    CyclicBarrier barrier = new CyclicBarrier(2);

    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Object>> attempts =
          List.of(
              pool.submit(
                  () -> claimSuccessorConcurrently(r1, r2, barrier)),
              pool.submit(
                  () -> claimSuccessorConcurrently(r1, r2, barrier)));
      List<Object> outcomes =
          List.of(
              attempts.get(0).get(10, TimeUnit.SECONDS),
              attempts.get(1).get(10, TimeUnit.SECONDS));
      assertEquals(
          1L,
          outcomes.stream()
              .filter(
                  PostgresGraphAttemptStore.OwnerClaim.class::isInstance)
              .count());
      assertEquals(
          1L,
          outcomes.stream().filter("CONFLICT"::equals).count());
    }
    assertAttemptAt(r2.manifest(), 1, GraphAttemptPhase.MARKED);
  }

  @Test
  void pack010SuccessorClaimRollsBackAcrossProcessCrash()
      throws Exception {
    Fixture r1 =
        terminalFixture(
            "pack010-r1", "case-crash-r1", "crash-owner", 1);
    Fixture r2 =
        terminalFixture(
            "pack010-r2", "case-crash-r2", "crash-owner", 2);
    sealedSuccessfulGraph(r1, null);
    Path catalog = writeManifestCatalog(List.of(r1.manifest(), r2.manifest()));
    try {
      ProcessResult crashed =
          awaitProcess(
              startSuccessorClaimProcess(
                  catalog, "crash-after-predecessor"));
      assertEquals(86, crashed.exitCode(), crashed.output());
      assertInstanceOf(
          GraphAttemptVerification.Missing.class,
          store().findVerified(r2.manifest()));

      PostgresGraphAttemptStore.OwnerClaim recovered =
          store()
              .claimForOwner(
                  r2.manifest(),
                  r1.manifest(),
                  Duration.ofSeconds(30));
      assertEquals(1, recovered.cursor().lastSequence());
      assertEquals(GraphAttemptPhase.MARKED, recovered.cursor().phase());
    } finally {
      Files.deleteIfExists(catalog);
    }
  }

  @Test
  void twoJvmPack010SuccessorClaimHasExactlyOneWinner()
      throws Exception {
    Fixture r1 =
        terminalFixture(
            "pack010-r1", "case-jvm-r1", "jvm-owner", 1);
    Fixture r2 =
        terminalFixture(
            "pack010-r2", "case-jvm-r2", "jvm-owner", 2);
    sealedSuccessfulGraph(r1, null);
    Path catalog = writeManifestCatalog(List.of(r1.manifest(), r2.manifest()));
    try {
      Process first = startSuccessorClaimProcess(catalog, "claim");
      Process second = startSuccessorClaimProcess(catalog, "claim");
      List<ProcessResult> results =
          List.of(awaitProcess(first), awaitProcess(second));
      assertEquals(
          1L,
          results.stream().filter(result -> result.exitCode() == 0).count(),
          results.toString());
      assertEquals(
          1L,
          results.stream().filter(result -> result.exitCode() == 3).count(),
          results.toString());
      assertEquals(
          1L,
          results.stream()
              .filter(result -> result.output().contains("CLAIMED"))
              .count(),
          results.toString());
      assertEquals(
          1L,
          results.stream()
              .filter(result -> result.output().contains("CONFLICT"))
              .count(),
          results.toString());
      assertAttemptAt(r2.manifest(), 1, GraphAttemptPhase.MARKED);
    } finally {
      Files.deleteIfExists(catalog);
    }
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
            DISABLE TRIGGER agent_graph_head_transition_guard_v8
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
              ENABLE TRIGGER agent_graph_head_transition_guard_v8
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
            DISABLE TRIGGER agent_graph_run_selector_guard_v8
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_runs
            DISABLE TRIGGER agent_graph_run_selector_guard_v10
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
              ENABLE TRIGGER agent_graph_run_selector_guard_v10
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_runs
              ENABLE TRIGGER agent_graph_run_selector_guard_v8
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
            DISABLE TRIGGER agent_graph_head_transition_guard_v8
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
              ENABLE TRIGGER agent_graph_head_transition_guard_v8
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
        "23503",
        () ->
            jdbc.sql(
                    """
                    INSERT INTO agent_graph_attempt_seals (
                      principal_id, attempt_id, manifest_hash,
                      pre_seal_sequence, pre_seal_head_hash,
                      final_sequence, final_head_hash,
                      provider_attribution_1_hash,
                      provider_attribution_2_hash,
                      candidate_ref, candidate_integrity_hash,
                      child_terminal_hash, parent_terminal_hash,
                      graph_outcome, billing_status,
                      seal_hash, sealed_at
                    ) VALUES (
                      :principalId, :attemptId, :manifestHash,
                      16, :preSealHeadHash,
                      :finalSequence, :finalHeadHash,
                      :attribution1Hash, :attribution2Hash,
                      NULL, NULL,
                      :childTerminalHash, :parentTerminalHash,
                      'SUCCEEDED', 'ATTRIBUTED',
                      :sealHash, :sealedAt
                    )
                    """)
                .param("principalId", fixture.manifest().principalId())
                .param("attemptId", fixture.manifest().attemptId())
                .param("manifestHash", fixture.manifest().manifestHash())
                .param(
                    "preSealHeadHash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid pre-seal head"))
                .param("finalSequence", 17)
                .param(
                    "finalHeadHash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid final head"))
                .param(
                    "attribution1Hash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid attribution one"))
                .param(
                    "attribution2Hash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid attribution two"))
                .param(
                    "childTerminalHash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid child terminal"))
                .param(
                    "parentTerminalHash",
                    IntegrityHashes.utf8ContentHash(
                        "invalid parent terminal"))
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
            DISABLE TRIGGER agent_graph_run_selector_guard_v8
            """)
        .update();
    jdbc.sql(
            """
            ALTER TABLE agent_runs
            DISABLE TRIGGER agent_graph_run_selector_guard_v10
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
              ENABLE TRIGGER agent_graph_run_selector_guard_v10
              """)
          .update();
      jdbc.sql(
              """
              ALTER TABLE agent_runs
              ENABLE TRIGGER agent_graph_run_selector_guard_v8
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

  private static Object claimSuccessorConcurrently(
      Fixture predecessor,
      Fixture current,
      CyclicBarrier barrier)
      throws Exception {
    barrier.await();
    try {
      return store()
          .claimForOwner(
              current.manifest(),
              predecessor.manifest(),
              Duration.ofSeconds(30));
    } catch (GraphAttemptConflictException conflict) {
      return "CONFLICT";
    }
  }

  private static String attributeConcurrently(
      Fixture fixture,
      GraphAttemptCursor pending,
      GraphProviderAttribution attribution,
      CyclicBarrier barrier)
      throws Exception {
    barrier.await();
    try {
      store()
          .providerAttributed(
              fixture.manifest(),
              pending,
              attribution,
              STARTED.plusMillis(11));
      return "ADVANCED";
    } catch (GraphAttemptConflictException conflict) {
      return "CONFLICT";
    }
  }

  private static String completeChildConcurrently(
      Fixture fixture,
      AttributedPrefix prefix,
      SuccessfulChildTruth truth,
      CyclicBarrier barrier)
      throws Exception {
    barrier.await();
    try {
      store()
          .completeChild(
              fixture.manifest(),
              prefix.cursor(),
              AgentRunContext.fromRunning(fixture.parent()),
              truth.terminal(),
              truth.candidate(),
              truth.workerResult(),
              STARTED.plusMillis(14));
      return "ADVANCED";
    } catch (GraphAttemptConflictException conflict) {
      return "CONFLICT";
    }
  }

  private static String completeParentConcurrently(
      Fixture fixture,
      GraphAttemptCursor childTerminal,
      ParentTerminalTruth truth,
      CyclicBarrier barrier)
      throws Exception {
    barrier.await();
    try {
      store()
          .completeParentAndSeal(
              fixture.manifest(),
              childTerminal,
              truth.terminal(),
              truth.artifact(),
              STARTED.plusMillis(15));
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
    return advanceToModelCreated(fixture, cursor);
  }

  private static GraphAttemptCursor advanceToModelCreated(
      Fixture fixture, GraphAttemptCursor cursor) {
    return advanceToModelCreated(fixture, cursor, STARTED);
  }

  private static GraphAttemptCursor advanceToModelCreated(
      Fixture fixture,
      GraphAttemptCursor cursor,
      Instant base) {
    PostgresGraphAttemptStore normal = store();
    cursor =
        normal.approve(
            fixture.manifest(),
            cursor,
            approval(fixture.manifest()),
            base.plusMillis(1));
    cursor =
        normal.authorizeParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            base.plusMillis(2));
    cursor =
        normal.startParent(
            fixture.manifest(),
            cursor,
            fixture.parent(),
            base.plusMillis(3));
    cursor =
        normal.authorizeChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            base.plusMillis(4));
    cursor =
        normal.startChild(
            fixture.manifest(),
            cursor,
            fixture.child(),
            base.plusMillis(5));
    cursor =
        normal.consumeChildEgress(
            fixture.manifest(),
            cursor,
            base.plusMillis(6));
    cursor =
        normal.credentialReadStarted(
            fixture.manifest(),
            cursor,
            base.plusMillis(7));
    cursor =
        normal.clientCreated(
            fixture.manifest(),
            cursor,
            base.plusMillis(8));
    return normal.modelCreated(
        fixture.manifest(),
        cursor,
        base.plusMillis(9));
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
              evidence_hash, previous_head_hash,
              event_hash, current_head_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :sequence,
              :eventType, :occurredAt, :phaseFrom, :phaseTo,
              :role, :runId, :taskId, :actor, :challengeHash,
              :requestOrdinal, :requestHash, :modelRequested,
              :evidenceHash, :previousHeadHash,
              :eventHash, :currentHeadHash
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
            "evidenceHash",
            event.evidenceHash(),
            java.sql.Types.CHAR)
        .param(
            "previousHeadHash", event.previousHeadHash())
        .param("eventHash", event.eventHash())
        .param("currentHeadHash", event.currentHeadHash())
        .update();
  }

  private static void callTerminalPermit(
      GraphAttemptManifest manifest,
      GraphRunRole role,
      String runId) {
    jdbc.sql(
            """
            SELECT agent_graph_authorize_terminal_v8(
              :principalId, :attemptId, :role, :runId
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("role", role.name())
        .param("runId", runId)
        .query((resultSet, rowNumber) -> Boolean.TRUE)
        .single();
  }

  private static void rawTerminalizeWithForgedGuc(
      Fixture fixture, AgentRun terminal) {
    GraphAttemptManifest manifest = fixture.manifest();
    String forged =
        manifest.principalId()
            + '\u001f'
            + manifest.attemptId()
            + '\u001f'
            + GraphRunRole.CHILD.name()
            + '\u001f'
            + terminal.runId();
    jdbc.sql(
            "SELECT set_config("
                + "'emergeos.graph_terminal_permit', :permit, true)")
        .param("permit", forged)
        .query(String.class)
        .single();
    int updated =
        jdbc.sql(
                """
                UPDATE agent_runs
                SET lifecycle_status = :lifecycle,
                    result_envelope = CAST(:resultJson AS jsonb),
                    bundle = CAST(:bundleJson AS jsonb),
                    bundle_hash = :bundleHash,
                    trace_root_hash = :traceRootHash,
                    last_event_sequence = :eventCount,
                    resolved_model = :resolvedModel,
                    agent_version = :agentVersion,
                    verifier_version = :verifierVersion,
                    harness_version = :harnessVersion,
                    cost_usd = :costUsd,
                    token_count = :tokenCount,
                    latency_ms = :latencyMs,
                    failure_attribution = :failureAttribution,
                    completed_at = :completedAt
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("lifecycle", terminal.lifecycle().name())
            .param(
                "resultJson",
                tools.jackson.databind.json.JsonMapper.shared()
                    .writeValueAsString(terminal.result()))
            .param(
                "bundleJson",
                tools.jackson.databind.json.JsonMapper.shared()
                    .writeValueAsString(terminal.bundle()))
            .param("bundleHash", terminal.bundle().integrityHash())
            .param("traceRootHash", terminal.trace().rootHash())
            .param("eventCount", terminal.trace().eventCount())
            .param("resolvedModel", terminal.result().resolvedModel())
            .param("agentVersion", terminal.result().agentVersion())
            .param(
                "verifierVersion",
                terminal.result().verifierVersion())
            .param("harnessVersion", terminal.bundle().harnessVersion())
            .param("costUsd", terminal.result().costUsd())
            .param("tokenCount", terminal.result().tokenCount())
            .param("latencyMs", terminal.result().latencyMs())
            .param(
                "failureAttribution",
                terminal.bundle().failureAttribution())
            .param(
                "completedAt",
                java.sql.Timestamp.from(terminal.completedAt()))
            .param("principalId", manifest.principalId())
            .param("runId", terminal.runId())
            .update();
    assertEquals(1, updated);
  }

  private static void insertRawCandidate(
      GraphAttemptManifest manifest,
      HarnessCandidateEnvelope candidate,
      Instant createdAt) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_candidates (
              principal_id, attempt_id, manifest_hash,
              schema_version, candidate_ref,
              execution_slot_id, repetition, child_role,
              child_run_id, child_task_id,
              source_request_ordinal, source_response_hash,
              trace_root_hash, output_schema,
              content, content_hash,
              required_evidence_ref,
              required_evidence_available,
              integrity_profile, integrity_hash,
              candidate_envelope, created_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash,
              :schemaVersion, :candidateRef,
              :executionSlotId, :repetition, 'CHILD',
              :childRunId, :childTaskId,
              :sourceRequestOrdinal, :sourceResponseHash,
              :traceRootHash, :outputSchema,
              :content, :contentHash,
              :requiredEvidenceRef,
              :requiredEvidenceAvailable,
              :integrityProfile, :integrityHash,
              CAST(:candidateJson AS jsonb), :createdAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("schemaVersion", candidate.schemaVersion())
        .param("candidateRef", candidate.candidateRef())
        .param("executionSlotId", candidate.executionSlotId())
        .param("repetition", candidate.repetition())
        .param("childRunId", candidate.childRunId())
        .param("childTaskId", candidate.childTaskId())
        .param(
            "sourceRequestOrdinal",
            candidate.sourceRequestOrdinal())
        .param(
            "sourceResponseHash", candidate.sourceResponseHash())
        .param("traceRootHash", candidate.traceRootHash())
        .param("outputSchema", candidate.outputSchema())
        .param("content", candidate.content())
        .param("contentHash", candidate.contentHash())
        .param(
            "requiredEvidenceRef", candidate.requiredEvidenceRef())
        .param(
            "requiredEvidenceAvailable",
            candidate.requiredEvidenceAvailable())
        .param("integrityProfile", candidate.integrityProfile())
        .param("integrityHash", candidate.integrityHash())
        .param(
            "candidateJson",
            tools.jackson.databind.json.JsonMapper.shared()
                .writeValueAsString(candidate))
        .param("createdAt", java.sql.Timestamp.from(createdAt))
        .update();
  }

  private static void updateRawHead(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphAttemptEvent event) {
    int sequence = event.sequence();
    int providerIntentCount =
        sequence >= 13 ? 2 : sequence >= 11 ? 1 : 0;
    int providerAttributionCount =
        sequence >= 14 ? 2 : sequence >= 12 ? 1 : 0;
    GraphBillingStatus billingStatus =
        sequence <= 10
            ? GraphBillingStatus.NOT_INVOKED
            : sequence == 11 || sequence == 13
                ? GraphBillingStatus.UNKNOWN
                : GraphBillingStatus.ATTRIBUTED;
    assertEquals(
        1,
        jdbc.sql(
                """
                UPDATE agent_graph_attempt_heads
                SET phase = :phase,
                    state_version = :stateVersion,
                    last_sequence = :lastSequence,
                    head_hash = :headHash,
                    billing_status = :billingStatus,
                    provider_intent_count = :providerIntentCount,
                    provider_attribution_count = :providerAttributionCount,
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
            .param("billingStatus", billingStatus.name())
            .param("providerIntentCount", providerIntentCount)
            .param(
                "providerAttributionCount",
                providerAttributionCount)
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

  private static long countForAttempt(
      String table, GraphAttemptManifest manifest) {
    return jdbc.sql(
            "SELECT count(*) FROM "
                + table
                + " WHERE principal_id = :principalId"
                + " AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static long countForRun(
      String table, String principalId, String runId) {
    return jdbc.sql(
            "SELECT count(*) FROM "
                + table
                + " WHERE principal_id = :principalId"
                + " AND run_id = :runId")
        .param("principalId", principalId)
        .param("runId", runId)
        .query(Long.class)
        .single();
  }

  private static long countWorkerResultsForChild(
      String principalId, String childRunId) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_worker_results
            WHERE principal_id = :principalId
              AND child_run_id = :childRunId
            """)
        .param("principalId", principalId)
        .param("childRunId", childRunId)
        .query(Long.class)
        .single();
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

  private static void cloneAsGenericTerminalRun(
      String principalId,
      String graphRunId,
      String genericRunId) {
    assertEquals(
        1,
        jdbc.sql(
                """
                INSERT INTO agent_runs (
                  principal_id, run_id, task_id,
                  parent_run_id, parent_task_id,
                  run_depth, parent_run_depth,
                  lifecycle_status, task_envelope,
                  result_envelope, bundle, bundle_hash,
                  trace_root_hash, last_event_sequence,
                  resolved_model, agent_version,
                  verifier_version, harness_version,
                  tool_registry_version, policy_version,
                  state_version, context_policy_version,
                  model_provider, model_requested,
                  pricing_profile, cost_usd, token_count,
                  latency_ms, failure_attribution,
                  started_at, completed_at,
                  graph_attempt_id, graph_manifest_hash,
                  graph_role, graph_task_hash,
                  graph_selector_hash,
                  graph_execution_profile_id,
                  graph_execution_profile_fingerprint,
                  graph_worker_registry_version,
                  graph_worker_profile_id,
                  graph_worker_profile_fingerprint
                )
                SELECT
                  principal_id, :genericRunId, task_id,
                  NULL, NULL, 0, NULL,
                  lifecycle_status, task_envelope,
                  result_envelope, bundle, bundle_hash,
                  trace_root_hash, last_event_sequence,
                  resolved_model, agent_version,
                  verifier_version, harness_version,
                  tool_registry_version, policy_version,
                  state_version, context_policy_version,
                  model_provider, model_requested,
                  pricing_profile, cost_usd, token_count,
                  latency_ms, failure_attribution,
                  started_at, completed_at,
                  NULL, NULL, NULL, NULL, NULL,
                  NULL, NULL, NULL, NULL, NULL
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :graphRunId
                """)
            .param("genericRunId", genericRunId)
            .param("principalId", principalId)
            .param("graphRunId", graphRunId)
            .update());
  }

  private static SealedGraph sealedSuccessfulGraph(
      String suffix) {
    Fixture fixture =
        terminalFixture(
            "slot-" + suffix,
            "case-" + suffix,
            "principal");
    return sealedSuccessfulGraph(fixture, null);
  }

  private static SealedGraph sealedSuccessfulGraph(
      Fixture fixture, GraphAttemptCursor claimed) {
    return sealedSuccessfulGraph(fixture, claimed, STARTED);
  }

  private static SealedGraph sealedSuccessfulGraph(
      Fixture fixture,
      GraphAttemptCursor claimed,
      Instant base) {
    AttributedPrefix prefix =
        claimed == null
            ? advanceToTwoAttributions(fixture)
            : advanceToTwoAttributions(fixture, claimed, base);
    SuccessfulChildTruth childTruth =
        successfulChildTruth(
            fixture,
            prefix.attributions(),
            base.plusMillis(14));
    insertCaptureFor(fixture);
    GraphAttemptCursor childTerminal =
        store()
            .completeChild(
                fixture.manifest(),
                prefix.cursor(),
                AgentRunContext.fromRunning(fixture.parent()),
                childTruth.terminal(),
                childTruth.candidate(),
                childTruth.workerResult(),
                base.plusMillis(14));
    ParentTerminalTruth parentTruth =
        successfulParentTruth(
            fixture, childTruth, base.plusMillis(15));
    store()
        .completeParentAndSeal(
            fixture.manifest(),
            childTerminal,
            parentTruth.terminal(),
            parentTruth.artifact(),
            base.plusMillis(15));
    return new SealedGraph(
        fixture,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(fixture.manifest()))
            .snapshot());
  }

  private static void assertFreshReadersReject(
      GraphAttemptManifest manifest) {
    for (int reader = 0; reader < 2; reader++) {
      assertEquals(
          "STORED_GRAPH_INVALID",
          assertInstanceOf(
                  GraphAttemptVerification.Invalid.class,
                  new PostgresGraphAttemptStore(dataSource)
                      .findVerified(manifest))
              .reasonCode());
    }
  }

  private static TraceTruthVersion traceTruthVersion(
      String principalId, String runId, int sequence) {
    return jdbc.sql(
            """
            SELECT event_hash, xmin::text AS row_version
            FROM agent_trace_events
            WHERE principal_id = :principalId
              AND run_id = :runId
              AND sequence = :sequence
            """)
        .param("principalId", principalId)
        .param("runId", runId)
        .param("sequence", sequence)
        .query(
            (rows, ignored) ->
                new TraceTruthVersion(
                    rows.getString("event_hash"),
                    rows.getString("row_version")))
        .single();
  }

  private static ArtifactTruthVersion artifactTruthVersion(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT content, xmin::text AS row_version
            FROM artifact_versions
            WHERE principal_id = :principalId
              AND artifact_id = :artifactId
              AND version = 1
            """)
        .param("principalId", manifest.principalId())
        .param("artifactId", manifest.artifactId())
        .query(
            (rows, ignored) ->
                new ArtifactTruthVersion(
                    rows.getString("content"),
                    rows.getString("row_version")))
        .single();
  }

  private static SealTruthVersion sealTruthVersion(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT seal.seal_hash,
                   seal.xmin::text AS seal_row_version,
                   event.evidence_hash,
                   event.event_hash,
                   event.xmin::text AS event_row_version
            FROM agent_graph_attempt_seals seal
            JOIN agent_graph_attempt_events event
              ON event.principal_id = seal.principal_id
             AND event.attempt_id = seal.attempt_id
             AND event.sequence = 17
            WHERE seal.principal_id = :principalId
              AND seal.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (rows, ignored) ->
                new SealTruthVersion(
                    rows.getString("seal_hash"),
                    rows.getString("seal_row_version"),
                    rows.getString("evidence_hash"),
                    rows.getString("event_hash"),
                    rows.getString("event_row_version")))
        .single();
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

  private static void assertSqlPermissionDeniedWithRollback(
      DataSource restricted, String sql) throws Exception {
    try (Connection connection = restricted.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        java.sql.SQLException rejection =
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute(sql));
        assertEquals("42501", rejection.getSQLState());
      } finally {
        connection.rollback();
      }
    }
  }

  private static void assertReaderRejectsAuthorityDrift(
      GraphAttemptReader reader,
      GraphAttemptManifest manifest,
      String grant,
      String revoke) {
    jdbc.sql(grant).update();
    try {
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> reader.findVerified(manifest));
    } finally {
      jdbc.sql(revoke).update();
    }
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

  private static PostgresAgentRunStore genericRunStore(
      Fixture fixture) {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    return new PostgresAgentRunStore(
        dataSource,
        transactions,
        new PostgresArtifactLineageStore(
            dataSource, transactions),
        ReadOnlyWorkerProfileRegistry.of(fixture.profile()));
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

  private static GraphAttemptManifest manifestWithStartedAt(
      GraphAttemptManifest original, Instant startedAt) {
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
        startedAt,
        original.pricingProfileFingerprint(),
        original.promptSurfaceFingerprint(),
        original.conductorSurfaceFingerprint(),
        original.reservationUsd(),
        original.maximumProviderRequests(),
        original.parentActor(),
        original.childActor(),
        original.experiment(),
        original.parentSelection(),
        original.childSelection());
  }

  private static GraphAttemptManifest manifestWithArtifactId(
      GraphAttemptManifest original, String artifactId) {
    return GraphAttemptManifest.create(
        original.graphProtocolVersion(),
        original.principalId(),
        original.executionSlotId(),
        original.caseId(),
        original.packRawSha256(),
        original.environmentRawSha256(),
        original.captureId(),
        original.captureRequestHash(),
        artifactId,
        original.startedAt(),
        original.pricingProfileFingerprint(),
        original.promptSurfaceFingerprint(),
        original.conductorSurfaceFingerprint(),
        original.reservationUsd(),
        original.maximumProviderRequests(),
        original.parentActor(),
        original.childActor(),
        original.experiment(),
        original.parentSelection(),
        original.childSelection());
  }

  private static List<GraphAttemptManifest> authorityCatalog(
      String casePrefix, String principal) {
    GraphAttemptManifest r1 =
        terminalFixture(
                "pack010-r1", casePrefix + "-r1", principal, 1)
            .manifest();
    GraphAttemptManifest r2 =
        terminalFixture(
                "pack010-r2", casePrefix + "-r2", principal, 2)
            .manifest();
    GraphAttemptManifest r3 =
        terminalFixture(
                "pack010-r3", casePrefix + "-r3", principal, 3)
            .manifest();
    return List.of(
        manifestWithStartedAt(r1, STARTED),
        manifestWithStartedAt(r2, STARTED.plusSeconds(300)),
        manifestWithStartedAt(r3, STARTED.plusSeconds(600)));
  }

  private static void assertAttemptAt(
      GraphAttemptManifest manifest,
      int sequence,
      GraphAttemptPhase phase) {
    GraphAttemptSnapshot snapshot =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store().findVerified(manifest))
            .snapshot();
    assertEquals(sequence, snapshot.cursor().lastSequence());
    assertEquals(phase, snapshot.cursor().phase());
  }

  private static ProcessResult runOwnerAuthorityProcess(
      List<GraphAttemptManifest> catalog,
      boolean tty,
      String response,
      long responseDelayMs,
      long ttlMs,
      String mode)
      throws Exception {
    Path catalogFile =
        Files.createTempFile("pack010-authority-catalog-", ".json");
    String cloneSchema =
        mode.equals("schema-switch")
            ? "pack010_clone_"
                + UUID.randomUUID().toString().replace("-", "")
            : null;
    Files.writeString(
        catalogFile,
        tools.jackson.databind.json.JsonMapper.shared()
            .writeValueAsString(catalog),
        StandardCharsets.UTF_8);
    try {
      if (cloneSchema != null) {
        JdbcClient.create(dataSource)
            .sql("CREATE SCHEMA " + cloneSchema)
            .update();
        PGSimpleDataSource cloneSource = new PGSimpleDataSource();
        cloneSource.setURL(POSTGRES.getJdbcUrl());
        cloneSource.setUser(POSTGRES.getUsername());
        cloneSource.setPassword(POSTGRES.getPassword());
        cloneSource.setCurrentSchema(cloneSchema);
        Flyway.configure()
            .dataSource(cloneSource)
            .schemas(cloneSchema)
            .load()
            .migrate();
      }
      ProcessBuilder builder;
      if (tty) {
        String script =
            mode.equals("expect-replay-rejection")
                ? """
                  set timeout 15
                  spawn -noecho $env(AUTH_JAVA) -cp $env(AUTH_CP) $env(AUTH_MAIN) $env(AUTH_CATALOG) $env(AUTH_TTL) $env(AUTH_MODE)
                  expect eof
                  catch wait result
                  exit [lindex $result 3]
                  """
                : """
                  set timeout 15
                  spawn -noecho $env(AUTH_JAVA) -cp $env(AUTH_CP) $env(AUTH_MAIN) $env(AUTH_CATALOG) $env(AUTH_TTL) $env(AUTH_MODE)
                  expect -exact $env(AUTH_CHALLENGE)
                  after $env(AUTH_DELAY)
                  send -- "$env(AUTH_RESPONSE)\r"
                  expect eof
                  catch wait result
                  exit [lindex $result 3]
                  """;
        builder = new ProcessBuilder("/usr/bin/expect", "-c", script);
        builder.environment().put(
            "AUTH_CHALLENGE",
            io.emergeos.core.domain.GraphOperatorApproval
                .expectedChallenge(catalog.getFirst()));
        builder.environment().put("AUTH_RESPONSE", response);
        builder.environment().put(
            "AUTH_DELAY", Long.toString(responseDelayMs));
      } else {
        builder =
            new ProcessBuilder(
                javaCommand(),
                "-cp",
                System.getProperty("java.class.path"),
                OwnerTtyAuthorityProcessMain.class.getName(),
                catalogFile.toString(),
                Long.toString(ttlMs),
                mode);
      }
      builder.redirectErrorStream(true);
      builder.environment().put("AUTH_JAVA", javaCommand());
      builder.environment().put(
          "AUTH_CP", System.getProperty("java.class.path"));
      builder.environment().put(
          "AUTH_MAIN", OwnerTtyAuthorityProcessMain.class.getName());
      builder.environment().put(
          "AUTH_CATALOG", catalogFile.toString());
      builder.environment().put("AUTH_TTL", Long.toString(ttlMs));
      builder.environment().put("AUTH_MODE", mode);
      builder.environment().put("AUTH_JDBC_URL", POSTGRES.getJdbcUrl());
      builder.environment().put("AUTH_DB_USER", POSTGRES.getUsername());
      builder.environment().put(
          "AUTH_DB_PASSWORD", POSTGRES.getPassword());
      if (cloneSchema != null) {
        builder.environment().put("AUTH_CLONE_SCHEMA", cloneSchema);
      }
      Process process = builder.start();
      if (!tty) {
        process.getOutputStream()
            .write(response.getBytes(StandardCharsets.UTF_8));
      }
      process.getOutputStream().close();
      boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "owner TTY process exceeded bounded wait");
      }
      String output =
          new String(
              process.getInputStream().readAllBytes(),
              StandardCharsets.UTF_8);
      return new ProcessResult(process.exitValue(), output);
    } finally {
      Files.deleteIfExists(catalogFile);
      if (cloneSchema != null) {
        JdbcClient.create(dataSource)
            .sql("DROP SCHEMA IF EXISTS " + cloneSchema + " CASCADE")
            .update();
      }
    }
  }

  private static Path writeManifestCatalog(
      List<GraphAttemptManifest> catalog) throws Exception {
    Path catalogFile =
        Files.createTempFile("pack010-successor-catalog-", ".json");
    Files.writeString(
        catalogFile,
        tools.jackson.databind.json.JsonMapper.shared()
            .writeValueAsString(catalog),
        StandardCharsets.UTF_8);
    return catalogFile;
  }

  private static Process startSuccessorClaimProcess(
      Path catalog, String mode) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            javaCommand(),
            "-cp",
            System.getProperty("java.class.path"),
            SuccessorClaimProcessMain.class.getName(),
            catalog.toString(),
            mode);
    builder.redirectErrorStream(true);
    builder.environment().put("AUTH_JDBC_URL", POSTGRES.getJdbcUrl());
    builder.environment().put("AUTH_DB_USER", POSTGRES.getUsername());
    builder.environment().put(
        "AUTH_DB_PASSWORD", POSTGRES.getPassword());
    return builder.start();
  }

  private static ProcessResult awaitProcess(Process process)
      throws Exception {
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException(
          "successor claim process exceeded bounded wait");
    }
    return new ProcessResult(
        process.exitValue(),
        new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8));
  }

  private static String javaCommand() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElseThrow();
  }

  public static final class OwnerTtyAuthorityProcessMain {

    private OwnerTtyAuthorityProcessMain() {}

    public static void main(String[] args) throws Exception {
      try {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getenv("AUTH_JDBC_URL"));
        source.setUser(System.getenv("AUTH_DB_USER"));
        source.setPassword(System.getenv("AUTH_DB_PASSWORD"));
        GraphAttemptManifest[] manifests =
            tools.jackson.databind.json.JsonMapper.shared()
                .readValue(
                    Path.of(args[0]).toFile(),
                    GraphAttemptManifest[].class);
        PostgresGraphAttemptStore authorityStore =
            new PostgresGraphAttemptStore(
                source,
                point -> {
                  if ("kill-during-adopt".equals(args[2])
                      && point
                          == PostgresGraphAttemptStore.ProbePoint
                              .VERIFIED_READ_TRANSACTION) {
                    Runtime.getRuntime().halt(88);
                  }
                  if ("kill-during-session-intent".equals(args[2])
                      && point
                          == PostgresGraphAttemptStore.ProbePoint
                              .AFTER_PROVIDER_SESSION_INTENT_INSERT) {
                    Runtime.getRuntime().halt(93);
                  }
                });
        OwnerTtyGraphAuthority authority =
            new OwnerTtyGraphAuthority(
                authorityStore,
                List.of(manifests),
                Duration.ofMillis(Long.parseLong(args[1])));
        OwnerTtyGraphAuthority.MutationPermit permit =
            authority.approve(
                OwnerTtyGraphAuthority.Pack010Revision.R1);
        if ("kill-after-approval".equals(args[2])) {
          Runtime.getRuntime().halt(87);
        }
        try {
          authority.approve(
              OwnerTtyGraphAuthority.Pack010Revision.R2);
          throw new AssertionError(
              "second process revision unexpectedly succeeded");
        } catch (
            OwnerTtyGraphAuthority.OwnerApprovalException expected) {
          if (!"PROCESS_REVISION_ALREADY_SELECTED"
              .equals(expected.getMessage())) {
            throw expected;
          }
          System.out.println("PROCESS_REVISION_ALREADY_SELECTED");
        }
        if ("schema-switch".equals(args[2])) {
          String clone = System.getenv("AUTH_CLONE_SCHEMA");
          try (Connection connection = source.getConnection();
              Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(
                "SET LOCAL session_replication_role = replica");
            for (String relation :
                List.of(
                    "agent_graph_attempts",
                    "agent_graph_attempt_run_bindings",
                    "agent_graph_attempt_heads",
                    "agent_graph_attempt_events")) {
              statement.executeUpdate(
                  "INSERT INTO "
                      + clone
                      + "."
                      + relation
                      + " SELECT * FROM public."
                      + relation
                      + " ON CONFLICT DO NOTHING");
            }
            connection.commit();
          }
          System.out.println("CLONE_PREFIX_SEQ2");
          source.setCurrentSchema(clone);
          try {
            authority.adopt(permit);
            throw new AssertionError(
                "schema-spliced owner handoff unexpectedly succeeded");
          } catch (GraphAttemptIntegrityException expected) {
            System.out.println("SCHEMA_IDENTITY_REJECTED");
            return;
          }
        }
        AtomicReference<OwnerTtyGraphAuthority.ApprovedHandoff>
            adopted = new AtomicReference<>();
        if ("consume-race".equals(args[2])) {
          CountDownLatch start = new CountDownLatch(1);
          try (var consumers = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results =
                List.of(
                    consumers.submit(
                        () ->
                            adoptAfter(
                                start, authority, permit, adopted)),
                    consumers.submit(
                        () ->
                            adoptAfter(
                                start, authority, permit, adopted)));
            start.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
              if (result.get(5, TimeUnit.SECONDS)) {
                winners++;
              }
            }
            if (winners != 1) {
              throw new AssertionError(
                  "permit race did not produce exactly one winner");
            }
            System.out.println("PERMIT_RACE_ONE_WINNER");
          }
        } else {
          if ("adopt-expired".equals(args[2])) {
            Thread.sleep(Long.parseLong(args[1]) + 150L);
          }
          adopted.set(authority.adopt(permit));
        }
        if ("kill-after-adopt".equals(args[2])) {
          Runtime.getRuntime().halt(89);
        }
        GraphAttemptCoordinator coordinator =
            authority.coordinator(adopted.get());
        try {
          coordinator.approve(
              manifests[1],
              new GraphAttemptCoordinator.InteractiveConsole() {
                @Override
                public boolean realTty() {
                  return true;
                }

                @Override
                public String readLine(String prompt) {
                  return prompt;
                }
              },
              java.time.Clock.systemUTC());
          throw new AssertionError(
              "owner coordinator generic approval unexpectedly succeeded");
        } catch (
            GraphAttemptCoordinator.OperatorApprovalException expected) {
          if (!"OWNER_ADOPTION_ONLY".equals(expected.getMessage())) {
            throw expected;
          }
          System.out.println("GENERIC_APPROVAL_DISABLED");
        }
        try {
          authority.adopt(permit);
          throw new AssertionError(
              "second permit consumption unexpectedly succeeded");
        } catch (IllegalStateException expected) {
          System.out.println("SECOND_CONSUME_REJECTED");
        }
        try {
          authority.takeAuthorized(
              adopted.get(),
              new GraphAttemptCoordinator(
                  new PostgresGraphAttemptStore(source)));
          throw new AssertionError(
              "wrong coordinator unexpectedly claimed handoff");
        } catch (IllegalStateException expected) {
          System.out.println("WRONG_COORDINATOR_REJECTED");
        }
        GraphAttemptCoordinator.Authorized authorized =
            authority.takeAuthorized(adopted.get(), coordinator);
        try {
          authority.takeAuthorized(adopted.get(), coordinator);
          throw new AssertionError(
              "second authorized claim unexpectedly succeeded");
        } catch (IllegalStateException expected) {
          System.out.println("SECOND_AUTHORIZED_CLAIM_REJECTED");
        }
        GraphAttemptCoordinator.EgressAuthority egress =
            advanceToEgress(
                coordinator,
                authorized,
                manifests[0]);
        if ("kill-after-egress".equals(args[2])) {
          Runtime.getRuntime().halt(90);
        }
        GraphAttemptCoordinator.EgressAuthority wrongEgress =
            unrelatedEgress(
                new GraphAttemptCoordinator(
                    new PostgresGraphAttemptStore(source)),
                manifests[0]);
        if ("provider-session-capability".equals(args[2])
            || "session-intent-expired".equals(args[2])
            || "kill-during-session-intent".equals(args[2])
            || "kill-after-session-intent".equals(args[2])) {
          Fixture sessionFixture =
              terminalFixture(
                  manifests[0].executionSlotId(),
                  manifests[0].caseId(),
                  manifests[0].principalId(),
                  manifests[0].experiment().repetition());
          GraphProviderIntent firstRequest =
              firstProviderIntent(sessionFixture);
          if ("provider-session-capability".equals(args[2])) {
            try {
              authority.claimProviderSessionIntent(
                  adopted.get(),
                  coordinator,
                  egress,
                  OwnerTtyGraphAuthority.Pack010Revision.R2,
                  firstRequest);
              throw new AssertionError(
                  "wrong provider session revision unexpectedly claimed");
            } catch (IllegalStateException expected) {
              System.out.println(
                  "WRONG_SESSION_REVISION_REJECTED");
            }
            try {
              authority.claimProviderSessionIntent(
                  adopted.get(),
                  new GraphAttemptCoordinator(
                      new PostgresGraphAttemptStore(source)),
                  egress,
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  firstRequest);
              throw new AssertionError(
                  "wrong provider session coordinator unexpectedly claimed");
            } catch (IllegalStateException expected) {
              System.out.println(
                  "WRONG_SESSION_COORDINATOR_REJECTED");
            }
            try {
              authority.claimProviderSessionIntent(
                  adopted.get(),
                  coordinator,
                  wrongEgress,
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  firstRequest);
              throw new AssertionError(
                  "wrong provider session egress unexpectedly claimed");
            } catch (IllegalArgumentException expected) {
              System.out.println(
                  "WRONG_SESSION_EGRESS_REJECTED");
            }
            AtomicReference<
                    OwnerTtyGraphAuthority.ProviderSessionIntent>
                sessionIntent = new AtomicReference<>();
            CountDownLatch claimStart = new CountDownLatch(1);
            try (var claimers = Executors.newFixedThreadPool(2)) {
              List<Future<Boolean>> results =
                  List.of(
                      claimers.submit(
                          () ->
                              claimProviderSessionIntentAfter(
                                  claimStart,
                                  authority,
                                  adopted.get(),
                                  coordinator,
                                  egress,
                                  firstRequest,
                                  sessionIntent)),
                      claimers.submit(
                          () ->
                              claimProviderSessionIntentAfter(
                                  claimStart,
                                  authority,
                                  adopted.get(),
                                  coordinator,
                                  egress,
                                  firstRequest,
                                  sessionIntent)));
              claimStart.countDown();
              long winners = 0;
              for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                  winners++;
                }
              }
              if (winners != 1) {
                throw new AssertionError(
                    "provider session intent race did not have one winner");
              }
              System.out.println(
                  "PROVIDER_SESSION_INTENT_RACE_ONE_WINNER");
            }
            try {
              authority.consumeProviderSessionIntent(
                  sessionIntent.get(),
                  adopted.get(),
                  coordinator,
                  wrongEgress);
              throw new AssertionError(
                  "wrong provider session egress unexpectedly consumed");
            } catch (IllegalStateException expected) {
              System.out.println(
                  "WRONG_SESSION_CONSUME_EGRESS_REJECTED");
            }
            CountDownLatch consumeStart = new CountDownLatch(1);
            try (var consumers = Executors.newFixedThreadPool(2)) {
              List<Future<Boolean>> results =
                  List.of(
                      consumers.submit(
                          () ->
                              consumeProviderSessionIntentAfter(
                                  consumeStart,
                                  authority,
                                  sessionIntent.get(),
                                  adopted.get(),
                                  coordinator,
                                  egress)),
                      consumers.submit(
                          () ->
                              consumeProviderSessionIntentAfter(
                                  consumeStart,
                                  authority,
                                  sessionIntent.get(),
                                  adopted.get(),
                                  coordinator,
                                  egress)));
              consumeStart.countDown();
              long winners = 0;
              for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                  winners++;
                }
              }
              if (winners != 1) {
                throw new AssertionError(
                    "provider session consume race did not have one winner");
              }
              System.out.println(
                  "PROVIDER_SESSION_CONSUME_RACE_ONE_WINNER");
            }
            try {
              authority.consumeProviderSessionIntent(
                  sessionIntent.get(),
                  adopted.get(),
                  coordinator,
                  egress);
              throw new AssertionError(
                  "provider session intent replay unexpectedly succeeded");
            } catch (IllegalStateException expected) {
              System.out.println(
                  "PROVIDER_SESSION_REPLAY_REJECTED");
            }
          } else {
            OwnerTtyGraphAuthority.ProviderSessionIntent sessionIntent =
                authority.claimProviderSessionIntent(
                    adopted.get(),
                    coordinator,
                    egress,
                    OwnerTtyGraphAuthority.Pack010Revision.R1,
                    firstRequest);
            System.out.println("PROVIDER_SESSION_INTENT_DURABLE");
            if ("kill-after-session-intent".equals(args[2])) {
              Runtime.getRuntime().halt(92);
            }
            Thread.sleep(Long.parseLong(args[1]) + 150L);
            authority.consumeProviderSessionIntent(
                sessionIntent,
                adopted.get(),
                coordinator,
                egress);
            throw new AssertionError(
                "expired provider session intent unexpectedly consumed");
          }
        }
        if ("egress-expired".equals(args[2])) {
          Thread.sleep(Long.parseLong(args[1]) + 150L);
          authority.consumeEgress(
              adopted.get(), coordinator, egress);
          throw new AssertionError(
              "expired egress handoff unexpectedly succeeded");
        }
        try {
          authority.consumeEgress(
              adopted.get(), coordinator, wrongEgress);
          throw new AssertionError(
              "wrong egress unexpectedly consumed handoff");
        } catch (IllegalArgumentException expected) {
          System.out.println("WRONG_EGRESS_REJECTED");
        }
        try {
          authority.consumeEgress(
              adopted.get(),
              new GraphAttemptCoordinator(
                  new PostgresGraphAttemptStore(source)),
              egress);
          throw new AssertionError(
              "wrong coordinator unexpectedly consumed egress");
        } catch (IllegalStateException expected) {
          System.out.println("WRONG_EGRESS_COORDINATOR_REJECTED");
        }
        CountDownLatch egressStart = new CountDownLatch(1);
        try (var consumers = Executors.newFixedThreadPool(2)) {
          List<Future<Boolean>> results =
              List.of(
                  consumers.submit(
                      () ->
                          consumeEgressAfter(
                              egressStart,
                              authority,
                              adopted.get(),
                              coordinator,
                              egress)),
                  consumers.submit(
                      () ->
                          consumeEgressAfter(
                              egressStart,
                              authority,
                              adopted.get(),
                              coordinator,
                              egress)));
          egressStart.countDown();
          long winners = 0;
          for (Future<Boolean> result : results) {
            if (result.get(5, TimeUnit.SECONDS)) {
              winners++;
            }
          }
          if (winners != 1) {
            throw new AssertionError(
                "egress race did not produce exactly one winner");
          }
            System.out.println("EGRESS_RACE_ONE_WINNER");
        }
        if ("terminal-capability".equals(args[2])
            || "kill-after-terminal-binding".equals(args[2])) {
          Fixture fixture =
              terminalFixture(
                  manifests[0].executionSlotId(),
                  manifests[0].caseId(),
                  manifests[0].principalId(),
                  manifests[0].experiment().repetition());
          advanceProviderAttributions(
              coordinator, egress, fixture);
          try {
            authority.claimTerminal(
                adopted.get(),
                coordinator,
                egress,
                OwnerTtyGraphAuthority.Pack010Revision.R2);
            throw new AssertionError(
                "wrong revision terminal claim unexpectedly succeeded");
          } catch (IllegalStateException expected) {
            System.out.println("WRONG_TERMINAL_REVISION_REJECTED");
          }
          OwnerTtyGraphAuthority.TerminalCapability terminal =
              authority.claimTerminal(
                  adopted.get(),
                  coordinator,
                  egress,
                  OwnerTtyGraphAuthority.Pack010Revision.R1);
          try {
            authority.claimTerminal(
                adopted.get(),
                coordinator,
                egress,
                OwnerTtyGraphAuthority.Pack010Revision.R1);
            throw new AssertionError(
                "terminal handoff replay unexpectedly succeeded");
          } catch (IllegalStateException expected) {
            System.out.println("TERMINAL_HANDOFF_REPLAY_REJECTED");
          }
          GraphAttemptManifest bound =
              authority.bindTerminal(
                  terminal,
                  coordinator,
                  egress,
                  OwnerTtyGraphAuthority.Pack010Revision.R1);
          if (!bound.equals(manifests[0])) {
            throw new AssertionError("terminal manifest drifted");
          }
          try {
            authority.bindTerminal(
                terminal,
                coordinator,
                egress,
                OwnerTtyGraphAuthority.Pack010Revision.R1);
            throw new AssertionError(
                "terminal binding replay unexpectedly succeeded");
          } catch (IllegalStateException expected) {
            System.out.println("TERMINAL_BIND_REPLAY_REJECTED");
          }
          System.out.println("TERMINAL_CAPABILITY_BOUND");
          if ("kill-after-terminal-binding".equals(args[2])) {
            Runtime.getRuntime().halt(91);
          }
          CountDownLatch terminalStart = new CountDownLatch(1);
          try (var claimers = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results =
                List.of(
                    claimers.submit(() ->
                        claimChildAfter(
                            terminalStart,
                            authority,
                            terminal,
                            coordinator,
                            egress)),
                    claimers.submit(() ->
                        claimChildAfter(
                            terminalStart,
                            authority,
                            terminal,
                            coordinator,
                            egress)));
            terminalStart.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
              if (result.get(5, TimeUnit.SECONDS)) {
                winners++;
              }
            }
            if (winners != 1) {
              throw new AssertionError(
                  "terminal child race did not have one winner");
            }
          }
          System.out.println("TERMINAL_CHILD_RACE_ONE_WINNER");
          try {
            authority.claimParentTerminal(
                terminal, coordinator, egress);
            throw new AssertionError(
                "terminal parent before durable child unexpectedly succeeded");
          } catch (IllegalStateException expected) {
            System.out.println(
                "TERMINAL_PARENT_BEFORE_DURABLE_CHILD_REJECTED");
          }
        }
        System.out.println("APPROVED");
      } catch (RuntimeException rejected) {
        System.out.println("REJECTED " + rejected.getMessage());
        System.exit(3);
      }
    }

    private static boolean adoptAfter(
        CountDownLatch start,
        OwnerTtyGraphAuthority authority,
        OwnerTtyGraphAuthority.MutationPermit permit,
        AtomicReference<OwnerTtyGraphAuthority.ApprovedHandoff>
            adopted)
        throws InterruptedException {
      start.await();
      try {
        adopted.set(authority.adopt(permit));
        return true;
      } catch (IllegalStateException rejected) {
        return false;
      }
    }

    private static Instant postgresPrecisionNow() {
      return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static GraphAttemptCoordinator.EgressAuthority
        advanceToEgress(
            GraphAttemptCoordinator coordinator,
            GraphAttemptCoordinator.Authorized authorized,
            GraphAttemptManifest manifest) {
      Fixture fixture =
          terminalFixture(
              manifest.executionSlotId(),
              manifest.caseId(),
              manifest.principalId(),
              manifest.experiment().repetition());
      Instant now = postgresPrecisionNow();
      GraphAttemptCoordinator.ParentStarted parent =
          coordinator.startParent(
              coordinator.authorizeParent(
                  authorized, fixture.parent(), now),
              now.plusMillis(1));
      GraphAttemptCoordinator.ChildStarted child =
          coordinator.startChild(
              coordinator.authorizeChild(
                  parent, fixture.child(), now.plusMillis(2)),
              now.plusMillis(3));
      return coordinator.consumeChildEgress(
          child, now.plusMillis(4));
    }

    private static GraphAttemptCoordinator.EgressAuthority
        unrelatedEgress(
            GraphAttemptCoordinator coordinator,
            GraphAttemptManifest approvedManifest) {
      Fixture fixture =
          terminalFixture(
              approvedManifest.executionSlotId() + "-wrong-egress",
              approvedManifest.caseId() + "-wrong-egress",
              approvedManifest.principalId(),
              approvedManifest.experiment().repetition());
      Instant now = postgresPrecisionNow();
      GraphAttemptCoordinator.Authorized authorized =
          coordinator.approve(
              fixture.manifest(),
              new GraphAttemptCoordinator.InteractiveConsole() {
                @Override
                public boolean realTty() {
                  return true;
                }

                @Override
                public String readLine(String prompt) {
                  return prompt;
                }
              },
              java.time.Clock.fixed(
                  now, java.time.ZoneOffset.UTC));
      GraphAttemptCoordinator.ParentStarted parent =
          coordinator.startParent(
              coordinator.authorizeParent(
                  authorized, fixture.parent(), now.plusMillis(1)),
              now.plusMillis(2));
      GraphAttemptCoordinator.ChildStarted child =
          coordinator.startChild(
              coordinator.authorizeChild(
                  parent, fixture.child(), now.plusMillis(3)),
              now.plusMillis(4));
      return coordinator.consumeChildEgress(
          child, now.plusMillis(5));
    }

    private static boolean consumeEgressAfter(
        CountDownLatch start,
        OwnerTtyGraphAuthority authority,
        OwnerTtyGraphAuthority.ApprovedHandoff handoff,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress)
        throws InterruptedException {
      start.await();
      try {
        authority.consumeEgress(handoff, coordinator, egress);
        return true;
      } catch (IllegalStateException rejected) {
        return false;
      }
    }

    private static GraphProviderIntent firstProviderIntent(
        Fixture fixture) {
      return new GraphProviderIntent(
          1,
          IntegrityHashes.utf8ContentHash(
              "terminal-capability-request-1"),
          fixture.profile().pricing().modelRequested());
    }

    private static boolean claimProviderSessionIntentAfter(
        CountDownLatch start,
        OwnerTtyGraphAuthority authority,
        OwnerTtyGraphAuthority.ApprovedHandoff handoff,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        GraphProviderIntent firstRequest,
        AtomicReference<OwnerTtyGraphAuthority.ProviderSessionIntent>
            claimed)
        throws InterruptedException {
      start.await();
      try {
        claimed.set(
            authority.claimProviderSessionIntent(
                handoff,
                coordinator,
                egress,
                OwnerTtyGraphAuthority.Pack010Revision.R1,
                firstRequest));
        return true;
      } catch (IllegalStateException rejected) {
        return false;
      }
    }

    private static boolean consumeProviderSessionIntentAfter(
        CountDownLatch start,
        OwnerTtyGraphAuthority authority,
        OwnerTtyGraphAuthority.ProviderSessionIntent intent,
        OwnerTtyGraphAuthority.ApprovedHandoff handoff,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress)
        throws InterruptedException {
      start.await();
      try {
        authority.consumeProviderSessionIntent(
            intent, handoff, coordinator, egress);
        return true;
      } catch (IllegalStateException rejected) {
        return false;
      }
    }

    private static void advanceProviderAttributions(
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        Fixture fixture) {
      Instant at = postgresPrecisionNow();
      coordinator.credentialReadStarted(egress, at);
      coordinator.clientCreated(egress, at.plusMillis(1));
      coordinator.modelCreated(egress, at.plusMillis(2));
      for (int ordinal = 1; ordinal <= 2; ordinal++) {
        String requestHash =
            IntegrityHashes.utf8ContentHash(
                "terminal-capability-request-" + ordinal);
        GraphProviderIntent intent =
            new GraphProviderIntent(
                ordinal,
                requestHash,
                fixture.profile().pricing().modelRequested());
        coordinator.providerIntent(
            egress, intent, at.plusMillis(ordinal * 2L + 1));
        coordinator.providerAttributed(
            egress,
            attribution(fixture, ordinal, requestHash),
            at.plusMillis(ordinal * 2L + 2));
      }
    }

    private static boolean claimChildAfter(
        CountDownLatch start,
        OwnerTtyGraphAuthority authority,
        OwnerTtyGraphAuthority.TerminalCapability terminal,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress)
        throws InterruptedException {
      start.await();
      try {
        authority.claimChildTerminal(
            terminal, coordinator, egress);
        return true;
      } catch (IllegalStateException rejected) {
        return false;
      }
    }
  }

  public static final class SuccessorClaimProcessMain {

    private SuccessorClaimProcessMain() {}

    public static void main(String[] args) throws Exception {
      PGSimpleDataSource source = new PGSimpleDataSource();
      source.setURL(System.getenv("AUTH_JDBC_URL"));
      source.setUser(System.getenv("AUTH_DB_USER"));
      source.setPassword(System.getenv("AUTH_DB_PASSWORD"));
      GraphAttemptManifest[] manifests =
          tools.jackson.databind.json.JsonMapper.shared()
              .readValue(
                  Path.of(args[0]).toFile(),
                  GraphAttemptManifest[].class);
      String mode = args[1];
      PostgresGraphAttemptStore claimant =
          "crash-after-predecessor".equals(mode)
              ? new PostgresGraphAttemptStore(
                  source,
                  point -> {
                    if (point
                        == PostgresGraphAttemptStore.ProbePoint
                            .AFTER_PREDECESSOR_VERIFIED) {
                      Runtime.getRuntime().halt(86);
                    }
                  })
              : new PostgresGraphAttemptStore(source);
      try {
        claimant.claimForOwner(
            manifests[1], manifests[0], Duration.ofSeconds(30));
        System.out.println("CLAIMED");
      } catch (GraphAttemptConflictException conflict) {
        System.out.println("CONFLICT");
        System.exit(3);
      }
    }
  }

  private record ProcessResult(int exitCode, String output) {}

  private static DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static DataSource dataSource(
      String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void createRestrictedReaderRole(
      String role, String password) {
    if (!role.matches("[a-z][a-z0-9_]*")
        || password.contains("'")) {
      throw new IllegalArgumentException(
          "unsafe synthetic database credential");
    }
    jdbc.sql(
            "CREATE ROLE "
                + role
                + " LOGIN PASSWORD '"
                + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
    jdbc.sql(
            "GRANT CONNECT ON DATABASE "
                + POSTGRES.getDatabaseName()
                + " TO "
                + role)
        .update();
    jdbc.sql("GRANT USAGE ON SCHEMA public TO " + role)
        .update();
    jdbc.sql(
            "GRANT SELECT ON TABLE "
                + String.join(", ", RESTRICTED_READER_RELATIONS)
                + " TO "
                + role)
        .update();
  }

  private static void createRuntimeRole(
      String role, String password) {
    if (!role.matches("[a-z][a-z0-9_]*")
        || password.contains("'")) {
      throw new IllegalArgumentException(
          "unsafe ephemeral database credential");
    }
    jdbc.sql(
            "CREATE ROLE "
                + role
                + " LOGIN PASSWORD '"
                + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
    jdbc.sql(
            "GRANT CONNECT ON DATABASE "
                + POSTGRES.getDatabaseName()
                + " TO "
                + role)
        .update();
    jdbc.sql("GRANT USAGE ON SCHEMA public TO " + role)
        .update();
  }

  private static void createNoLoginRole(String role) {
    if (!role.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe ephemeral role");
    }
    jdbc.sql(
            "CREATE ROLE "
                + role
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
  }

  private static void assertGraphExecutorFunctionSurface(
      DataSource executorDataSource) {
    List<String> effectiveFunctions =
        JdbcClient.create(executorDataSource)
            .sql(
                """
                SELECT procedure.proname || '('
                         || pg_catalog.pg_get_function_identity_arguments(
                           procedure.oid) || ')'
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'public'
                  AND pg_catalog.has_function_privilege(
                    current_user, procedure.oid, 'EXECUTE')
                ORDER BY procedure.proname
                """)
            .query(String.class)
            .list();
    assertEquals(
        3,
        effectiveFunctions.size(),
        "GRAPH_AUTH_PROBE_EFFECTIVE_FUNCTION_COUNT");
    assertEquals(
        List.of(
            "agent_graph_complete_child_v10(payload jsonb)",
            "agent_graph_complete_parent_and_seal_v10(payload jsonb)",
            "emergeos_pack010_schema_version_v1()"),
        effectiveFunctions,
        "GRAPH_AUTH_PROBE_EFFECTIVE_FUNCTION_SURFACE");
  }

  private static void provisionV10Authority(
      String tableOwner,
      String terminalOwner,
      String executorRole,
      String failureResumerRole,
      String providerAttestorRole,
      String writerRole) {
    jdbc.sql(
            "REVOKE TEMPORARY ON DATABASE "
                + POSTGRES.getDatabaseName()
                + " FROM PUBLIC")
        .update();
    jdbc.sql(
            "GRANT USAGE ON SCHEMA public TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "GRANT SELECT, INSERT, UPDATE ON ALL TABLES "
                + "IN SCHEMA public TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "REVOKE ALL ON TABLE public.flyway_schema_history FROM "
                + terminalOwner)
        .update();
    executeExactPack010SchemaVersionAndGuardUpgrade();
    transferV10HelperFunctionOwnership(tableOwner);
    jdbc.sql(
            "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC")
        .update();
    jdbc.sql(
            "GRANT EXECUTE ON FUNCTION "
                + "public.emergeos_pack010_schema_version_v1() TO "
                + terminalOwner
                + ", "
                + executorRole
                + ", "
                + failureResumerRole
                + ", "
                + providerAttestorRole)
        .update();
    jdbc.sql(
            "GRANT EXECUTE ON FUNCTION "
                + "public.agent_graph_run_selector_guard_v10(), "
                + "public.agent_graph_require_row_shape_v9("
                + "jsonb, regclass, boolean), "
                + "public.agent_graph_require_executor_v9(regprocedure), "
                + "public.agent_graph_require_executor_v10(regprocedure), "
                + "public.agent_assert_graph_attempt_v7(varchar, char), "
                + "public.agent_assert_graph_attempt_v8(varchar, char), "
                + "public.agent_assert_worker_graph_v6(varchar, varchar), "
                + "public.agent_graph_terminal_run_valid_v8("
                + "varchar, char, varchar), "
                + "public.agent_graph_utf16_length_v8(text), "
                + "public.agent_graph_authorize_terminal_v8("
                + "varchar, char, varchar, varchar) TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "ALTER FUNCTION public.agent_graph_complete_child_v10(jsonb) "
                + "OWNER TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "ALTER FUNCTION public.agent_graph_complete_child_v9(jsonb) "
                + "OWNER TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "ALTER FUNCTION "
                + "public.agent_graph_complete_parent_and_seal_v10(jsonb) "
                + "OWNER TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "ALTER FUNCTION "
                + "public.agent_graph_complete_parent_and_seal_v9(jsonb) "
                + "OWNER TO "
                + terminalOwner)
        .update();
    jdbc.sql(
            "GRANT EXECUTE ON FUNCTION "
                + "public.agent_graph_complete_child_v10(jsonb), "
                + "public.agent_graph_complete_parent_and_seal_v10(jsonb) "
                + "TO "
                + executorRole)
        .update();
    jdbc.sql(
            "GRANT SELECT, UPDATE ON public.agent_runs TO "
                + writerRole)
        .update();
  }

  private static void restoreV10FunctionOwner() {
    jdbc.sql(
            "ALTER FUNCTION public.agent_graph_complete_child_v10(jsonb) "
                + "OWNER TO "
                + POSTGRES.getUsername())
        .update();
    jdbc.sql(
            "ALTER FUNCTION public.agent_graph_complete_child_v9(jsonb) "
                + "OWNER TO "
                + POSTGRES.getUsername())
        .update();
    jdbc.sql(
            "ALTER FUNCTION "
                + "public.agent_graph_complete_parent_and_seal_v10(jsonb) "
                + "OWNER TO "
                + POSTGRES.getUsername())
        .update();
    jdbc.sql(
            "ALTER FUNCTION "
                + "public.agent_graph_complete_parent_and_seal_v9(jsonb) "
                + "OWNER TO "
                + POSTGRES.getUsername())
        .update();
  }

  private static void transferV10HelperFunctionOwnership(String owner) {
    if (!owner.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe ephemeral owner");
    }
    List<String> transfers =
        jdbc.sql(
                """
                SELECT 'ALTER FUNCTION '
                       || procedure.oid::regprocedure::text
                       || ' OWNER TO '
                       || pg_catalog.quote_ident(:owner)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'public'
                  AND procedure.proname IN (
                    'agent_assert_graph_attempt_v7',
                    'agent_assert_graph_attempt_v8',
                    'agent_assert_worker_graph_v6',
                    'agent_graph_assert_failure_terminal_resume_v12',
                    'agent_graph_assert_row_v7',
                    'agent_graph_assert_row_v8',
                    'agent_graph_assert_run_dependency_v8',
                    'agent_graph_assert_run_row_v7',
                    'agent_graph_authorize_terminal_v8',
                    'agent_graph_head_transition_guard_v8',
                    'agent_graph_require_executor_v9',
                    'agent_graph_require_executor_v10',
                    'agent_graph_require_row_shape_v9',
                    'agent_graph_run_selector_guard_v8',
                    'agent_graph_run_selector_guard_v10',
                    'agent_graph_terminal_run_valid_v8',
                    'agent_graph_utf16_length_v8',
                    'agent_worker_graph_guard_v6',
                    'emergeos_pack010_schema_version_v1')
                  AND procedure.proowner <> (
                    SELECT oid FROM pg_catalog.pg_roles
                    WHERE rolname = :owner)
                ORDER BY procedure.proname
                """)
            .param("owner", owner)
            .query(String.class)
            .list();
    transfers.forEach(statement -> jdbc.sql(statement).update());
  }

  private static void executeExactPack010SchemaVersionAndGuardUpgrade() {
    String startMarker =
        "CREATE OR REPLACE FUNCTION "
            + "public.emergeos_pack010_schema_version_v1()";
    String endMarker = "\nDO $authority$\n";
    String provisioning;
    try (var resource =
        PostgresGraphAttemptStoreTest.class.getResourceAsStream(
            "/db/provisioning/pack010_runtime_roles.sql")) {
      if (resource == null) {
        throw new IllegalStateException(
            "Pack010 provisioning resource is unavailable");
      }
      provisioning =
          new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(
          "Pack010 provisioning resource is unreadable", failure);
    }
    int start = provisioning.indexOf(startMarker);
    int end = provisioning.indexOf(endMarker, start);
    if (start < 0
        || end <= start
        || provisioning.indexOf(startMarker, start + 1) >= 0) {
      throw new IllegalStateException(
          "Pack010 schema-version authority slice is not exact");
    }
    String exactAuthoritySlice = provisioning.substring(start, end);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute(exactAuthoritySlice);
        connection.commit();
      } catch (java.sql.SQLException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(
          "Pack010 schema-version authority slice failed", failure);
    }
  }

  private static void transferPublicRelationOwnership(String owner) {
    if (!owner.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe ephemeral owner");
    }
    List<String> transfers =
        jdbc.sql(
                """
                SELECT CASE relation.relkind
                         WHEN 'S' THEN 'ALTER SEQUENCE '
                         ELSE 'ALTER TABLE '
                       END
                       || pg_catalog.quote_ident(namespace.nspname)
                       || '.'
                       || pg_catalog.quote_ident(relation.relname)
                       || ' OWNER TO '
                       || pg_catalog.quote_ident(:owner)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relkind IN ('r', 'p', 'S')
                  AND relation.relowner <> (
                    SELECT oid
                    FROM pg_catalog.pg_roles
                    WHERE rolname = :owner)
                ORDER BY CASE relation.relkind
                           WHEN 'S' THEN 1
                           ELSE 0
                         END,
                         relation.relname
                """)
            .param("owner", owner)
            .query(String.class)
            .list();
    transfers.forEach(statement -> jdbc.sql(statement).update());
  }

  private static void runV9ProcessKill(
      String operation,
      String executorRole,
      String executorPassword,
      String payload)
      throws Exception {
    Path payloadPath =
        Files.createTempFile("v9-terminal-payload-", ".json");
    Path markerPath =
        payloadPath.resolveSibling(
            payloadPath.getFileName() + ".marker");
    try {
      Files.writeString(
          payloadPath, payload, StandardCharsets.UTF_8);
      Process process =
          startV9Process(
              operation,
              executorRole,
              executorPassword,
              payloadPath,
              markerPath.toString());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!Files.exists(markerPath)
          && process.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      if (!Files.exists(markerPath)) {
        String output;
        if (process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(20, TimeUnit.SECONDS);
          output = "V9 process timed out before marker";
        } else {
          output = readProcessOutput(process);
        }
        assertTrue(false, output);
      }
      assertTrue(process.isAlive());
      process.destroyForcibly();
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      assertFalse(process.isAlive());
      assertNotEquals(0, process.exitValue());
    } finally {
      Files.deleteIfExists(markerPath);
      Files.deleteIfExists(payloadPath);
    }
  }

  private static void runV9TwoProcessRace(
      String operation,
      String executorRole,
      String executorPassword,
      String payload)
      throws Exception {
    Path payloadPath =
        Files.createTempFile("v9-terminal-race-", ".json");
    Path readyA =
        payloadPath.resolveSibling(
            payloadPath.getFileName() + ".a.ready");
    Path readyB =
        payloadPath.resolveSibling(
            payloadPath.getFileName() + ".b.ready");
    Path release =
        payloadPath.resolveSibling(
            payloadPath.getFileName() + ".go");
    Process processA = null;
    Process processB = null;
    try {
      Files.writeString(
          payloadPath, payload, StandardCharsets.UTF_8);
      processA =
          startV9Process(
              operation,
              executorRole,
              executorPassword,
              payloadPath,
              "RACE|" + readyA + "|" + release);
      processB =
          startV9Process(
              operation,
              executorRole,
              executorPassword,
              payloadPath,
              "RACE|" + readyB + "|" + release);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (!(Files.exists(readyA) && Files.exists(readyB))
          && (processA.isAlive() || processB.isAlive())
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(
          Files.exists(readyA) && Files.exists(readyB));
      Files.writeString(
          release,
          "V9_RACE_RELEASE",
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE_NEW);
      assertTrue(processA.waitFor(20, TimeUnit.SECONDS));
      assertTrue(processB.waitFor(20, TimeUnit.SECONDS));
      String outputA = readProcessOutput(processA);
      String outputB = readProcessOutput(processB);
      assertEquals(
          List.of(0, 3),
          java.util.stream.Stream.of(
                  processA.exitValue(), processB.exitValue())
              .sorted()
              .toList(),
          outputA + outputB);
      assertEquals(
          1L,
          java.util.stream.Stream.of(outputA, outputB)
              .filter(
                  output ->
                      output.contains(
                          "V9_TERMINAL_PROCESS_OK operation="
                              + operation))
              .count());
      assertEquals(
          1L,
          java.util.stream.Stream.of(outputA, outputB)
              .filter(
                  output ->
                      output.contains(
                          "V9_TERMINAL_PROCESS_REJECTED"))
              .count());
    } finally {
      for (Process process : new Process[] {processA, processB}) {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
          process.waitFor(20, TimeUnit.SECONDS);
        }
      }
      Files.deleteIfExists(release);
      Files.deleteIfExists(readyB);
      Files.deleteIfExists(readyA);
      Files.deleteIfExists(payloadPath);
    }
  }

  private static Process startV9Process(
      String operation,
      String executorRole,
      String executorPassword,
      Path payloadPath,
      String marker)
      throws Exception {
    Process process =
        new ProcessBuilder(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        "java")
                    .toString(),
                "-cp",
                System.getProperty("java.class.path"),
                V9GraphTerminalExecutorProcessMain.class.getName(),
                operation,
                POSTGRES.getJdbcUrl(),
                executorRole,
                payloadPath.toString(),
                marker)
            .redirectErrorStream(true)
            .start();
    byte[] secret =
        executorPassword.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream input =
        new DataOutputStream(process.getOutputStream())) {
      input.writeInt(secret.length);
      input.write(secret);
    }
    return process;
  }

  private static String readProcessOutput(Process process) {
    try {
      return new String(
          process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void assertRawSemanticRejected(
      DataSource caller,
      String setRole,
      String payload,
      String expectedState)
      throws Exception {
    try (Connection connection = caller.getConnection();
        var roleStatement = connection.createStatement();
        PreparedStatement semantic =
            connection.prepareStatement(
                "SELECT public.agent_graph_complete_child_v10("
                    + "CAST(? AS jsonb))")) {
      connection.setAutoCommit(false);
      try {
        if (setRole != null) {
          roleStatement.execute("SET ROLE " + setRole);
        }
        semantic.setString(1, payload);
        java.sql.SQLException rejection =
            assertThrows(
                java.sql.SQLException.class,
                semantic::executeQuery);
        assertEquals(expectedState, rejection.getSQLState());
      } finally {
        connection.rollback();
      }
    }
  }

  private static void dropEphemeralRole(String role) {
    jdbc.sql("DROP OWNED BY " + role).update();
    jdbc.sql("DROP ROLE " + role).update();
  }

  private static void assertForgedGucCannotTerminalize(
      DataSource restricted,
      Fixture fixture,
      AgentRun terminal,
      String expectedState)
      throws Exception {
    String permit =
        fixture.manifest().principalId()
            + '\u001f'
            + fixture.manifest().attemptId()
            + "\u001fCHILD\u001f"
            + terminal.runId();
    try (Connection connection = restricted.getConnection();
        PreparedStatement setGuc =
            connection.prepareStatement(
                "SELECT set_config("
                    + "'emergeos.graph_terminal_permit', ?, true)");
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE public.agent_runs "
                    + "SET lifecycle_status = 'FAILED' "
                    + "WHERE principal_id = ? AND run_id = ?")) {
      connection.setAutoCommit(false);
      try {
        setGuc.setString(1, permit);
        setGuc.execute();
        update.setString(1, fixture.manifest().principalId());
        update.setString(2, terminal.runId());
        java.sql.SQLException rejection =
            assertThrows(
                java.sql.SQLException.class, update::executeUpdate);
        assertEquals(expectedState, rejection.getSQLState());
      } finally {
        connection.rollback();
      }
    }
  }

  private static void assertCannotGrantUpdate(
      DataSource restricted, String grantee) throws Exception {
    try (Connection connection = restricted.getConnection();
        var statement = connection.createStatement();
        PreparedStatement effective =
            connection.prepareStatement(
                "SELECT has_table_privilege(?, "
                    + "'public.agent_runs', 'UPDATE')")) {
      connection.setAutoCommit(false);
      try {
        try {
          statement.execute(
              "GRANT UPDATE ON public.agent_runs TO " + grantee);
          effective.setString(1, grantee);
          try (ResultSet result = effective.executeQuery()) {
            assertTrue(result.next());
            assertFalse(result.getBoolean(1));
          }
        } catch (java.sql.SQLException rejection) {
          assertEquals("42501", rejection.getSQLState());
        }
      } finally {
        connection.rollback();
      }
    }
    assertFalse(
        jdbc.sql(
                "SELECT has_table_privilege("
                    + ":role, 'public.agent_runs', 'UPDATE')")
            .param("role", grantee)
            .query(Boolean.class)
            .single());
  }

  private static String childSemanticPayload(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT pg_catalog.jsonb_build_object(
              'protocol', 'emergeos.graph-terminal.v10',
              'principal_id', :principalId,
              'attempt_id', :attemptId,
              'candidate', (
                SELECT pg_catalog.to_jsonb(candidate)
                FROM agent_graph_attempt_candidates candidate
                WHERE candidate.principal_id = :principalId
                  AND candidate.attempt_id = :attemptId
              ),
              'worker_result', (
                SELECT pg_catalog.to_jsonb(result)
                FROM agent_worker_results result
                JOIN agent_runs run
                  ON run.principal_id = result.principal_id
                 AND run.run_id = result.child_run_id
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'CHILD'
              ),
              'trace_events', COALESCE((
                SELECT pg_catalog.jsonb_agg(
                  pg_catalog.to_jsonb(event)
                  ORDER BY event.sequence)
                FROM agent_trace_events event
                JOIN agent_runs run
                  ON run.principal_id = event.principal_id
                 AND run.run_id = event.run_id
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'CHILD'
              ), '[]'::jsonb),
              'resource_bindings', COALESCE((
                SELECT pg_catalog.jsonb_agg(
                  pg_catalog.to_jsonb(binding)
                  ORDER BY binding.ordinal)
                FROM agent_run_resource_bindings binding
                JOIN agent_runs run
                  ON run.principal_id = binding.principal_id
                 AND run.run_id = binding.run_id
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'CHILD'
              ), '[]'::jsonb),
              'terminal_run', (
                SELECT pg_catalog.to_jsonb(run)
                FROM agent_runs run
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'CHILD'
              ),
              'terminal_binding', (
                SELECT pg_catalog.to_jsonb(binding)
                FROM agent_graph_attempt_terminal_bindings binding
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
                  AND binding.role = 'CHILD'
              ),
              'event', (
                SELECT pg_catalog.to_jsonb(event) - 'committed_at'
                FROM agent_graph_attempt_events event
                WHERE event.principal_id = :principalId
                  AND event.attempt_id = :attemptId
                  AND event.sequence = 15
              )
            )::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static String parentSemanticPayload(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT pg_catalog.jsonb_build_object(
              'protocol', 'emergeos.graph-terminal.v10',
              'principal_id', :principalId,
              'attempt_id', :attemptId,
              'artifact', (
                SELECT pg_catalog.to_jsonb(artifact)
                FROM artifacts artifact
                WHERE artifact.principal_id = :principalId
                  AND artifact.artifact_id = :artifactId
              ),
              'artifact_version', (
                SELECT pg_catalog.to_jsonb(artifact_version_row)
                FROM artifact_versions artifact_version_row
                WHERE artifact_version_row.principal_id = :principalId
                  AND artifact_version_row.artifact_id = :artifactId
                  AND artifact_version_row.version = 1
              ),
              'trace_events', COALESCE((
                SELECT pg_catalog.jsonb_agg(
                  pg_catalog.to_jsonb(event)
                  ORDER BY event.sequence)
                FROM agent_trace_events event
                JOIN agent_runs run
                  ON run.principal_id = event.principal_id
                 AND run.run_id = event.run_id
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'PARENT'
              ), '[]'::jsonb),
              'resource_bindings', COALESCE((
                SELECT pg_catalog.jsonb_agg(
                  pg_catalog.to_jsonb(binding)
                  ORDER BY binding.ordinal)
                FROM agent_run_resource_bindings binding
                JOIN agent_runs run
                  ON run.principal_id = binding.principal_id
                 AND run.run_id = binding.run_id
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'PARENT'
              ), '[]'::jsonb),
              'terminal_run', (
                SELECT pg_catalog.to_jsonb(run)
                FROM agent_runs run
                WHERE run.principal_id = :principalId
                  AND run.graph_attempt_id = :attemptId
                  AND run.graph_role = 'PARENT'
              ),
              'terminal_binding', (
                SELECT pg_catalog.to_jsonb(binding)
                FROM agent_graph_attempt_terminal_bindings binding
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
                  AND binding.role = 'PARENT'
              ),
              'parent_event', (
                SELECT pg_catalog.to_jsonb(event) - 'committed_at'
                FROM agent_graph_attempt_events event
                WHERE event.principal_id = :principalId
                  AND event.attempt_id = :attemptId
                  AND event.sequence = 16
              ),
              'seal', (
                SELECT pg_catalog.to_jsonb(seal)
                FROM agent_graph_attempt_seals seal
                WHERE seal.principal_id = :principalId
                  AND seal.attempt_id = :attemptId
              ),
              'seal_event', (
                SELECT pg_catalog.to_jsonb(event) - 'committed_at'
                FROM agent_graph_attempt_events event
                WHERE event.principal_id = :principalId
                  AND event.attempt_id = :attemptId
                  AND event.sequence = 17
              )
            )::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("artifactId", manifest.artifactId())
        .query(String.class)
        .single();
  }

  private static Map<String, Long> restrictedRelationCounts() {
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    for (String relation : RESTRICTED_READER_RELATIONS) {
      counts.put(
          relation,
          jdbc.sql("SELECT count(*) FROM " + relation)
              .query(Long.class)
              .single());
    }
    return Map.copyOf(counts);
  }

  private static Fixture fixture(
      String slot, String caseId, String principal) {
    return fixture(
        slot,
        caseId,
        principal,
        "postgres-graph-attempt-v1");
  }

  private static Fixture terminalFixture(
      String slot, String caseId, String principal) {
    return terminalFixture(slot, caseId, principal, 1);
  }

  private static Fixture terminalFixture(
      String slot,
      String caseId,
      String principal,
      int repetition) {
    return fixture(
        slot,
        caseId,
        principal,
        GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION,
        repetition);
  }

  private static Fixture fixture(
      String slot,
      String caseId,
      String principal,
      String graphProtocolVersion) {
    return fixture(
        slot, caseId, principal, graphProtocolVersion, 1);
  }

  private static Fixture fixture(
      String slot,
      String caseId,
      String principal,
      String graphProtocolVersion,
      int repetition) {
    String captureContent =
        "完全虚构的 Pack010 公开证据。";
    String captureSource = "synthetic-pack010";
    String captureRequestHash =
        CaptureRequestHashes.sha256(
            captureContent,
            CaptureSourceType.TEXT,
            captureSource,
            DataClass.PUBLIC);
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
            new HarnessExperiment("graph-test", repetition),
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
            graphProtocolVersion,
            principal,
            slot,
            caseId,
            IntegrityHashes.utf8ContentHash("pack"),
            IntegrityHashes.utf8ContentHash("environment"),
            "capture-1",
            captureRequestHash,
            "artifact-" + caseId,
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

  private static GraphProviderAttribution attribution(
      Fixture fixture, int ordinal, String requestHash) {
    PricingProfile pricing = fixture.profile().pricing();
    return GraphProviderAttribution.create(
        ordinal,
        requestHash,
        IntegrityHashes.utf8ContentHash(
            "terminal response " + ordinal),
        fixture.manifest().childActor(),
        pricing.modelRequested(),
        pricing.modelRequested(),
        pricing.graphSnapshot(),
        1,
        0,
        1,
        0,
        2,
        pricing.actualCostUsd(1, 0, 1));
  }

  private static AttributedPrefix advanceToTwoAttributions(
      Fixture fixture) {
    return advanceToTwoAttributions(fixture, null);
  }

  private static AttributedPrefix advanceToTwoAttributions(
      Fixture fixture, GraphAttemptCursor claimed) {
    return advanceToTwoAttributions(fixture, claimed, STARTED);
  }

  private static AttributedPrefix advanceToTwoAttributions(
      Fixture fixture,
      GraphAttemptCursor claimed,
      Instant base) {
    PostgresGraphAttemptStore normal = store();
    GraphAttemptCursor cursor =
        claimed == null
            ? advanceToModelCreated(fixture)
            : advanceToModelCreated(fixture, claimed, base);
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "terminal prefix request one"),
            fixture.profile().modelRequested());
    cursor =
        normal.providerIntent(
            fixture.manifest(),
            cursor,
            firstIntent,
            base.plusMillis(10));
    GraphProviderAttribution first =
        attribution(fixture, 1, firstIntent.requestHash());
    cursor =
        normal.providerAttributed(
            fixture.manifest(),
            cursor,
            first,
            base.plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash(
                "terminal prefix request two"),
            fixture.profile().modelRequested());
    cursor =
        normal.providerIntent(
            fixture.manifest(),
            cursor,
            secondIntent,
            base.plusMillis(12));
    GraphProviderAttribution second =
        attribution(fixture, 2, secondIntent.requestHash());
    cursor =
        normal.providerAttributed(
            fixture.manifest(),
            cursor,
            second,
            base.plusMillis(13));
    return new AttributedPrefix(cursor, List.of(first, second));
  }

  private static AgentRun failedChild(
      Fixture fixture,
      List<GraphProviderAttribution> attributions) {
    TaskEnvelope task = fixture.child().task();
    String runId = fixture.child().runId();
    AgentTraceEntry failedStep =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "FAILED",
            "task://" + task.id(),
            IntegrityHashes.emptyTraceRoot());
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), List.of(failedStep));
    BigDecimal cost =
        attributions.stream()
            .map(GraphProviderAttribution::observedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long tokens =
        attributions.stream()
            .mapToLong(GraphProviderAttribution::totalTokens)
            .sum();
    String traceRef =
        "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            fixture.profile().modelRequested(),
            fixture.profile().agentVersion(),
            fixture.profile().verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            "MODEL_STEP_FAILED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            fixture.profile().experiment(),
            result.resolvedModel(),
            fixture.profile().harnessVersion(),
            fixture.profile().componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    return new AgentRun(
        runId,
        fixture.manifest().principalId(),
        task,
        io.emergeos.core.domain.AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(14));
  }

  private static SuccessfulChildTruth successfulChildTruth(
      Fixture fixture,
      List<GraphProviderAttribution> attributions) {
    return successfulChildTruth(
        fixture, attributions, STARTED.plusMillis(14));
  }

  private static SuccessfulChildTruth successfulChildTruth(
      Fixture fixture,
      List<GraphProviderAttribution> attributions,
      Instant completedAt) {
    TaskEnvelope task = fixture.child().task();
    String runId = fixture.child().runId();
    String evidenceRef =
        "capture://" + fixture.manifest().captureId();
    String content =
        "Prompt 不是咒语，而是在构造可验证的运行时状态。";
    WorkerResultEnvelope workerResult =
        WorkerResultEnvelope.create(
            runId,
            task.id(),
            task.outputSchema(),
            content,
            List.of(evidenceRef));
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.TOOL_REQUEST,
            "capture.read",
            "REQUESTED",
            evidenceRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.TOOL_RESULT,
            "capture.read",
            "SUCCEEDED",
            evidenceRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    appendTrace(
        events,
        TraceEventType.STRUCTURED_FINAL,
        null,
        "PROPOSED",
        "proposal://sha256:" + workerResult.contentHash(),
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), events);
    HarnessCandidateEnvelope candidate =
        HarnessCandidateEnvelope.create(
            fixture.manifest().attemptId(),
            fixture.manifest().executionSlotId(),
            fixture.manifest().experiment().repetition(),
            runId,
            task.id(),
            attributions.get(1).responseHash(),
            trace.rootHash(),
            task.outputSchema(),
            content,
            List.of(evidenceRef),
            List.of(evidenceRef),
            evidenceRef,
            true);
    BigDecimal cost =
        attributions.stream()
            .map(GraphProviderAttribution::observedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long tokens =
        attributions.stream()
            .mapToLong(GraphProviderAttribution::totalTokens)
            .sum();
    String traceRef =
        "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.SUCCEEDED,
            List.of(),
            List.of(evidenceRef),
            List.of(),
            List.of(),
            List.of(),
            fixture.profile().modelRequested(),
            fixture.profile().agentVersion(),
            fixture.profile().verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            null);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            fixture.profile().experiment(),
            result.resolvedModel(),
            fixture.profile().harnessVersion(),
            fixture.profile().componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    evidenceRef,
                    fixture.manifest().captureRequestHash()),
                new ResourceBinding(
                    ResourceRole.WORKER_RESULT,
                    0,
                    workerResult.workerResultRef(),
                    workerResult.integrityHash())),
            null,
            null,
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            fixture.manifest().principalId(),
            task,
            io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
            result,
            trace,
            bundle,
            STARTED,
            completedAt);
    return new SuccessfulChildTruth(
        terminal, candidate, workerResult);
  }

  private static ParentTerminalTruth successfulParentTruth(
      Fixture fixture, SuccessfulChildTruth childTruth) {
    return successfulParentTruth(
        fixture, childTruth, STARTED.plusMillis(15));
  }

  private static ParentTerminalTruth successfulParentTruth(
      Fixture fixture,
      SuccessfulChildTruth childTruth,
      Instant completedAt) {
    TaskEnvelope task = fixture.parent().task();
    String runId = fixture.parent().runId();
    String evidenceRef =
        "capture://" + fixture.manifest().captureId();
    String childRef =
        "agent-run://" + childTruth.terminal().runId();
    ArtifactLineage artifact =
        new ArtifactLineage(
            fixture.manifest().artifactId(),
            fixture.manifest().principalId(),
            fixture.manifest().captureId(),
            List.of(
                new ArtifactLineageEntry(
                    1,
                    childTruth.candidate().content(),
                    childTruth.candidate().contentHash(),
                    null,
                    null,
                    STARTED.plusMillis(15))));
    String artifactRef =
        "artifact-version://"
            + artifact.artifactId()
            + "/"
            + artifact.current().version();
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_REQUEST,
            null,
            "REQUESTED",
            childRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_RESULT,
            null,
            "SUCCEEDED",
            childRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.STRUCTURED_FINAL,
            null,
            "PROPOSED",
            "proposal://sha256:"
                + childTruth.workerResult().contentHash(),
            root);
    appendTrace(
        events,
        TraceEventType.ARTIFACT_COMMITTED,
        null,
        "SUCCEEDED",
        artifactRef,
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), events);
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(
            fixture.profile());
    BigDecimal cost =
        childTruth.terminal().result().costUsd();
    long tokens =
        childTruth.terminal().result().tokenCount();
    String traceRef =
        "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.SUCCEEDED,
            List.of(artifactRef),
            childTruth.workerResult().evidenceRefs(),
            List.of(),
            List.of(),
            List.of(),
            "fake-pack010-conductor-v1",
            parentProfile.agentVersion(),
            parentProfile.verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            null);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            fixture.profile().experiment(),
            result.resolvedModel(),
            parentProfile.harnessVersion(),
            parentProfile.componentVersions(fixture.profile()),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    evidenceRef,
                    fixture.manifest().captureRequestHash()),
                new ResourceBinding(
                    ResourceRole.ARTIFACT,
                    0,
                    artifactRef,
                    artifact.current().contentHash()),
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    childTruth.terminal().bundle().integrityHash())),
            null,
            null,
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            fixture.manifest().principalId(),
            task,
            io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
            result,
            trace,
            bundle,
            STARTED,
            completedAt);
    return new ParentTerminalTruth(terminal, artifact);
  }

  private static String appendTrace(
      List<AgentTraceEntry> events,
      TraceEventType type,
      String toolName,
      String status,
      String reference,
      String previousRootHash) {
    AgentTraceEntry entry =
        AgentTraceEntry.create(
            events.size() + 1,
            type,
            toolName,
            status,
            reference,
            previousRootHash);
    events.add(entry);
    return IntegrityHashes.nextTraceRoot(
        entry.previousRootHash(), entry.eventHash());
  }

  private static void insertCaptureFor(Fixture fixture) {
    String content = "完全虚构的 Pack010 公开证据。";
    String source = "synthetic-pack010";
    Capture capture =
        new Capture(
            fixture.manifest().captureId(),
            fixture.manifest().principalId(),
            "pack010-capture",
            fixture.manifest().captureRequestHash(),
            content,
            CaptureSourceType.TEXT,
            source,
            DataClass.PUBLIC,
            STARTED);
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    new PostgresCaptureStore(dataSource, transactions)
        .saveOrFindByNonce(capture);
  }

  private record Fixture(
      GraphAttemptManifest manifest,
      AgentRun parent,
      AgentRun child,
      ModelBoundReadOnlyWorkerExecutionProfile profile) {}

  private record AttributedPrefix(
      GraphAttemptCursor cursor,
      List<GraphProviderAttribution> attributions) {}

  private record SuccessfulChildTruth(
      AgentRun terminal,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {}

  private record ParentTerminalTruth(
      AgentRun terminal, ArtifactLineage artifact) {}

  private record SealedGraph(
      Fixture fixture, GraphAttemptSnapshot snapshot) {}

  private record TraceTruthVersion(
      String eventHash, String rowVersion) {}

  private record ArtifactTruthVersion(
      String content, String rowVersion) {}

  private record SealTruthVersion(
      String sealHash,
      String sealRowVersion,
      String eventEvidenceHash,
      String eventHash,
      String eventRowVersion) {}

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
