package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresAgentRunStoreTest {

  private static final String PRINCIPAL = "agent-run-owner";
  private static final String CAPTURE_ID = "capture-001";
  private static final Instant STARTED = Instant.parse("2026-07-30T01:00:00Z");
  private static final Instant COMPLETED = Instant.parse("2026-07-30T01:00:01Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_agent_runs")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DataSource dataSource;
  private static PlatformTransactionManager transactions;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    dataSource = dataSource();
    transactions = new DataSourceTransactionManager(dataSource);
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
              agent_graph_attempt_run_bindings,
              agent_graph_attempts,
              agent_trace_events,
              agent_run_resource_bindings,
              agent_worker_results,
              agent_runs,
              local_draft_undo_receipts,
              local_draft_creation_receipts,
              local_drafts,
              action_receipts,
              action_attempt_transitions,
              action_attempts,
              artifact_versions,
              artifacts,
              captures
            """)
        .update();
  }

  @Test
  void roundTripsOneVerifiedTerminalAggregateAcrossStoreInstances() {
    Fixture fixture = fixture("run-001");
    PostgresAgentRunStore first = store();
    first.start(fixture.running());
    first.complete(fixture.terminal(), fixture.artifact());

    AgentRun loaded =
        store().findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow();

    assertEquals(fixture.terminal(), loaded);
    assertEquals(fixture.terminal().trace().rootHash(), loaded.trace().rootHash());
    assertEquals(fixture.terminal().bundle().integrityHash(), loaded.bundle().integrityHash());
    assertTrue(store().findOwned("foreign-owner", fixture.running().runId()).isEmpty());
    assertTrue(store().findOwned(PRINCIPAL, "missing-run").isEmpty());
  }

  @Test
  void rejectsSubMicrosecondRunTimestampsBeforeAnyPersistenceMutation() {
    Fixture fixture = fixture("run-sub-microsecond");
    PostgresAgentRunStore store = store();
    Instant subMicrosecondStartedAt = STARTED.plusNanos(789);
    AgentRun overpreciseRunning =
        AgentRun.running(
            fixture.running().runId(),
            PRINCIPAL,
            fixture.running().task(),
            subMicrosecondStartedAt);

    IllegalArgumentException startFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.start(overpreciseRunning));

    assertEquals(
        "AgentRun.startedAt exceeds PostgreSQL microsecond precision",
        startFailure.getMessage());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM agent_runs").query(Long.class).single());

    store.start(fixture.running());
    AgentRun overpreciseTerminal =
        new AgentRun(
            fixture.terminal().runId(),
            fixture.terminal().principalId(),
            fixture.terminal().task(),
            fixture.terminal().lifecycle(),
            fixture.terminal().result(),
            fixture.terminal().trace(),
            fixture.terminal().bundle(),
            fixture.terminal().startedAt(),
            COMPLETED.plusNanos(789));

    IllegalArgumentException completionFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> store.complete(overpreciseTerminal, fixture.artifact()));

    assertEquals(
        "AgentRun.completedAt exceeds PostgreSQL microsecond precision",
        completionFailure.getMessage());
    assertEquals(
        fixture.running(),
        store.findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM agent_trace_events")
            .query(Long.class)
            .single());
  }

  @Test
  void legacyV10StoresNullModelBindingColumns() {
    Fixture fixture = fixture("run-v10-null-model-binding");

    store().start(fixture.running());

    assertEquals(
        new ModelBindingColumns(null, null, null),
        modelBindingColumns(fixture.running().runId()));
  }

  @Test
  void modelBoundV11RoundTripsItsFrozenBindingAcrossStoreInstances() {
    ModelBoundFixture fixture = modelBoundFixture("run-v11-model-bound");
    PostgresAgentRunStore first = store();

    first.start(fixture.running());
    assertEquals(
        new ModelBindingColumns(
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1"),
        modelBindingColumns(fixture.running().runId()));
    first.complete(fixture.failed(), null);

    assertEquals(
        fixture.failed(),
        store().findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow());
  }

  @Test
  void databaseRejectsJsonAndTypedModelBindingDrift() {
    Fixture legacy = fixture("run-v10-binding-drift");
    ModelBoundFixture bound = modelBoundFixture("run-v11-binding-drift");
    PostgresAgentRunStore store = store();
    store.start(legacy.running());
    store.start(bound.running());

    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET model_provider = 'openai.responses'
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("principalId", PRINCIPAL)
                .param("runId", legacy.running().runId())
                .update());
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET pricing_profile = 'openai-gpt-5.6-sol-2026-07-v2'
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("principalId", PRINCIPAL)
                .param("runId", bound.running().runId())
                .update());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidCoordinatedModelBindings")
  void databaseConstraintRejectsCoordinatedInvalidModelBindings(
      String caseName,
      String schemaVersion,
      String modelProvider,
      String modelRequested,
      String pricingProfile,
      boolean nullBindings) {
    ModelBoundFixture fixture =
        modelBoundFixture("run-v11-db-constraint-" + caseName);
    store().start(fixture.running());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET task_envelope = jsonb_set(
                            jsonb_set(
                                jsonb_set(
                                    jsonb_set(
                                        task_envelope,
                                        '{schemaVersion}',
                                        to_jsonb(CAST(:schemaVersion AS text))
                                    ),
                                    '{modelProvider}',
                                    CASE
                                        WHEN :nullBindings THEN 'null'::jsonb
                                        ELSE to_jsonb(CAST(:modelProvider AS text))
                                    END
                                ),
                                '{modelRequested}',
                                CASE
                                    WHEN :nullBindings THEN 'null'::jsonb
                                    ELSE to_jsonb(CAST(:modelRequested AS text))
                                END
                            ),
                            '{pricingProfile}',
                            CASE
                                WHEN :nullBindings THEN 'null'::jsonb
                                ELSE to_jsonb(CAST(:pricingProfile AS text))
                            END
                        ),
                        model_provider = CASE
                            WHEN :nullBindings THEN NULL
                            ELSE CAST(:modelProvider AS varchar)
                        END,
                        model_requested = CASE
                            WHEN :nullBindings THEN NULL
                            ELSE CAST(:modelRequested AS varchar)
                        END,
                        pricing_profile = CASE
                            WHEN :nullBindings THEN NULL
                            ELSE CAST(:pricingProfile AS varchar)
                        END
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("schemaVersion", schemaVersion)
                .param("modelProvider", modelProvider)
                .param("modelRequested", modelRequested)
                .param("pricingProfile", pricingProfile)
                .param("nullBindings", nullBindings)
                .param("principalId", PRINCIPAL)
                .param("runId", fixture.running().runId())
                .update());
  }

  private static Stream<Arguments> invalidCoordinatedModelBindings() {
    return Stream.of(
        Arguments.of(
            "coordinated-null",
            "1.1",
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1",
            true),
        Arguments.of(
            "invalid-provider",
            "1.1",
            "OpenAI.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1",
            false),
        Arguments.of(
            "overlong-model",
            "1.1",
            "openai.responses",
            "m".repeat(513),
            "openai-gpt-5.6-sol-2026-07-v1",
            false),
        Arguments.of(
            "invalid-pricing",
            "1.1",
            "openai.responses",
            "gpt-5.6-sol",
            "OpenAI/gpt-5.6-sol/v1",
            false),
        Arguments.of(
            "unknown-schema",
            "1.2",
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1",
            false));
  }

  @Test
  void verifiedTerminalReadRejectsCoordinatedJsonAndColumnTampering() {
    ModelBoundFixture fixture = modelBoundFixture("run-v11-coordinated-tamper");
    PostgresAgentRunStore store = store();
    store.start(fixture.running());
    store.complete(fixture.failed(), null);
    jdbc.sql(
            """
            UPDATE agent_runs
            SET task_envelope = jsonb_set(
                    task_envelope,
                    '{modelProvider}',
                    to_jsonb('openai.responses-alt'::text)
                ),
                model_provider = 'openai.responses-alt'
            WHERE principal_id = :principalId AND run_id = :runId
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", fixture.running().runId())
        .update();

    assertThrows(
        AgentRunIntegrityException.class,
        () -> store.findOwned(PRINCIPAL, fixture.running().runId()));
  }

  @Test
  void rollsBackArtifactAndTerminalTruthWhenFinalizationFailsMidTransaction() {
    Fixture fixture = fixture("run-atomic-failure");
    PostgresAgentRunStore failing =
        new PostgresAgentRunStore(
            dataSource,
            transactions,
            new PostgresArtifactLineageStore(dataSource, transactions),
            () -> {
              throw new SyntheticFinalizationFailure();
            });
    failing.start(fixture.running());

    assertThrows(
        SyntheticFinalizationFailure.class,
        () -> failing.complete(fixture.terminal(), fixture.artifact()));

    AgentRun stillRunning =
        store().findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow();
    assertEquals(AgentRunLifecycle.RUNNING, stillRunning.lifecycle());
    assertEquals(
        0,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
    assertEquals(
        0,
        jdbc.sql("SELECT count(*) FROM artifact_versions").query(Long.class).single());
    assertEquals(
        0,
        jdbc.sql("SELECT count(*) FROM agent_trace_events").query(Long.class).single());
  }

  @Test
  void detectsAnOwnedSafeEventMutationWithoutReturningPartialTruth() {
    Fixture fixture = fixture("run-tampered");
    PostgresAgentRunStore store = store();
    store.start(fixture.running());
    store.complete(fixture.terminal(), fixture.artifact());
    jdbc.sql(
            """
            UPDATE agent_trace_events
            SET status = 'BLOCKED'
            WHERE principal_id = :principalId
              AND run_id = :runId
              AND sequence = 1
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", fixture.running().runId())
        .update();

    assertThrows(
        AgentRunIntegrityException.class,
        () -> store.findOwned(PRINCIPAL, fixture.running().runId()));
  }

  @Test
  void detectsAMutationOfTheStoredTraceCheckpointRoot() {
    Fixture fixture = fixture("run-tampered-current-root");
    PostgresAgentRunStore store = store();
    store.start(fixture.running());
    store.complete(fixture.terminal(), fixture.artifact());
    jdbc.sql(
            """
            UPDATE agent_trace_events
            SET current_root_hash = :tamperedRoot
            WHERE principal_id = :principalId
              AND run_id = :runId
              AND sequence = 1
            """)
        .param("tamperedRoot", "0".repeat(64))
        .param("principalId", PRINCIPAL)
        .param("runId", fixture.running().runId())
        .update();

    assertThrows(
        AgentRunIntegrityException.class,
        () -> store.findOwned(PRINCIPAL, fixture.running().runId()));
  }

  @Test
  void rejectsASecondDifferentTerminalTruth() {
    Fixture fixture = fixture("run-terminal-conflict");
    PostgresAgentRunStore store = store();
    store.start(fixture.running());
    store.complete(fixture.terminal(), fixture.artifact());

    assertThrows(
        AgentRunConflictException.class,
        () -> store.complete(fixture.terminal(), fixture.artifact()));
  }

  @Test
  void roundTripsAFailedRunWithEvidenceAndWithoutAnArtifact() {
    Fixture fixture = fixture("run-failed-evidence");
    AgentRun failed = failedTerminal(fixture);
    PostgresAgentRunStore store = store();
    store.start(fixture.running());

    AgentRunStoreCompletion completed = completeWithoutArtifact(store, failed);
    AgentRun loaded = store().findOwned(PRINCIPAL, failed.runId()).orElseThrow();

    assertEquals(failed, completed.run());
    assertEquals(failed, loaded);
    assertEquals(RunStatus.FAILED, loaded.result().status());
    assertEquals(List.of("capture://" + CAPTURE_ID), loaded.result().evidenceRefs());
    assertEquals(
        List.of(ResourceRole.EVIDENCE),
        loaded.bundle().resourceBindings().stream().map(ResourceBinding::role).toList());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
  }

  @Test
  void roundTripsPostDispatchDeadlineTruthWithActualLateLatency() {
    Fixture fixture =
        fixture(
            "run-post-dispatch-deadline",
            BigDecimal.ZERO,
            0,
            4,
            BigDecimal.ZERO,
            5);
    AgentRun failed = postDispatchDeadlineFailedTerminal(fixture);
    PostgresAgentRunStore first = store();
    first.start(fixture.running());

    AgentRun completed = first.complete(failed, null).run();
    AgentRun loaded =
        store().findOwned(PRINCIPAL, failed.runId()).orElseThrow();

    assertEquals(failed, completed);
    assertEquals(failed, loaded);
    assertEquals(
        new DeadlineColumns(
            "FAILED",
            "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH",
            7),
        deadlineColumns(failed.runId()));
    assertEquals(
        List.of(
            TraceEventType.MODEL_STEP,
            TraceEventType.TOOL_REQUEST,
            TraceEventType.TOOL_REJECTED),
        loaded.trace().events().stream().map(AgentTraceEntry::type).toList());
    assertEquals(
        List.of("COMPLETED", "REQUESTED", "DEADLINE_EXCEEDED"),
        loaded.trace().events().stream().map(AgentTraceEntry::status).toList());
    assertEquals(List.of(), loaded.result().artifactRefs());
    assertEquals(List.of(), loaded.result().evidenceRefs());
    assertEquals(List.of(), loaded.result().uncertainty());
    assertEquals(List.of(), loaded.bundle().resourceBindings());
    assertEquals(loaded.result(), loaded.bundle().result());
    assertEquals(loaded.trace().rootHash(), loaded.bundle().traceRootHash());
    assertEquals(
        failed.bundle().integrityHash(), loaded.bundle().integrityHash());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM agent_run_resource_bindings")
            .query(Long.class)
            .single());
    assertEquals(
        3L,
        jdbc.sql("SELECT count(*) FROM agent_trace_events")
            .query(Long.class)
            .single());
  }

  @Test
  void exactlyOneConcurrentTerminalCompletionWins() throws Exception {
    Fixture fixture = fixture("run-concurrent-terminal");
    store().start(fixture.running());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<String> contender =
          () -> {
            ready.countDown();
            start.await();
            try {
              store().complete(fixture.terminal(), fixture.artifact());
              return "completed";
            } catch (AgentRunConflictException expected) {
              return "conflict";
            }
          };
      Future<String> first = executor.submit(contender);
      Future<String> second = executor.submit(contender);
      ready.await();
      start.countDown();

      assertEquals(Set.of("completed", "conflict"), Set.of(first.get(), second.get()));
      assertEquals(
          fixture.terminal(),
          store().findOwned(PRINCIPAL, fixture.terminal().runId()).orElseThrow());
      assertEquals(
          1L,
          jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void historicalRunKeepsExactV1AfterArtifactHeadAdvancesToV2() {
    Fixture fixture = fixture("run-lineage-v2");
    PostgresAgentRunStore runs = store();
    PostgresArtifactLineageStore artifactStore =
        new PostgresArtifactLineageStore(dataSource, transactions);
    runs.start(fixture.running());
    runs.complete(fixture.terminal(), fixture.artifact());
    String frozenBundleHash = fixture.terminal().bundle().integrityHash();
    ResourceBinding frozenArtifactBinding =
        fixture.terminal().bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.ARTIFACT)
            .findFirst()
            .orElseThrow();
    ArtifactLineageEntry v1 = fixture.artifact().current();
    String revisedContent = "synthetic immutable output version two";
    ArtifactLineageEntry v2 =
        new ArtifactLineageEntry(
            2,
            revisedContent,
            ContentHashes.sha256(revisedContent),
            1,
            v1.contentHash(),
            COMPLETED.plusSeconds(1));

    var revision =
        artifactStore.compareAndSwap(
            PRINCIPAL,
            fixture.artifact().artifactId(),
            1,
            v1.contentHash(),
            v2);

    assertInstanceOf(
        io.emergeos.core.port.ArtifactLineageStore.RevisionResult.Revised.class,
        revision);
    assertEquals(
        2,
        artifactStore
            .findOwned(PRINCIPAL, fixture.artifact().artifactId())
            .orElseThrow()
            .current()
            .version());
    AgentRun historical =
        runs.findOwned(PRINCIPAL, fixture.terminal().runId()).orElseThrow();
    assertEquals(fixture.terminal(), historical);
    assertEquals(frozenBundleHash, historical.bundle().integrityHash());
    assertEquals(
        frozenArtifactBinding,
        historical.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.ARTIFACT)
            .findFirst()
            .orElseThrow());
  }

  @Test
  void rejectsAnEvidenceHashThatDoesNotMatchTheOwnedCapture() {
    Fixture fixture = fixture("run-forged-evidence-hash");
    ResourceBinding forgedEvidence =
        new ResourceBinding(
            ResourceRole.EVIDENCE,
            0,
            "capture://" + CAPTURE_ID,
            "a".repeat(64));
    List<ResourceBinding> forgedBindings =
        List.of(forgedEvidence, fixture.terminal().bundle().resourceBindings().getLast());
    HarnessRunBundle forgedBundle =
        HarnessRunBundle.create(
            fixture.terminal().bundle().schemaVersion(),
            fixture.terminal().runId(),
            fixture.terminal().task().id(),
            fixture.terminal().bundle().experiment(),
            fixture.terminal().bundle().modelResolved(),
            fixture.terminal().bundle().harnessVersion(),
            fixture.terminal().bundle().componentVersions(),
            fixture.terminal().bundle().environmentSnapshotRef(),
            fixture.terminal().bundle().toolRegistryVersion(),
            fixture.terminal().task(),
            fixture.terminal().result(),
            fixture.terminal().bundle().workingSelfRef(),
            fixture.terminal().bundle().traceRef(),
            fixture.terminal().trace().rootHash(),
            fixture.terminal().bundle().handoffRefs(),
            fixture.terminal().bundle().checkpointRefs(),
            forgedBindings,
            fixture.terminal().bundle().verificationRef(),
            fixture.terminal().bundle().failureAttribution(),
            fixture.terminal().result().status(),
            fixture.terminal().result().costUsd(),
            fixture.terminal().result().tokenCount(),
            fixture.terminal().result().latencyMs());
    AgentRun forged =
        new AgentRun(
            fixture.terminal().runId(),
            PRINCIPAL,
            fixture.terminal().task(),
            AgentRunLifecycle.SUCCEEDED,
            fixture.terminal().result(),
            fixture.terminal().trace(),
            forgedBundle,
            STARTED,
            COMPLETED);
    PostgresAgentRunStore store = store();
    store.start(fixture.running());

    assertThrows(
        RuntimeException.class,
        () -> store.complete(forged, fixture.artifact()));
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store.findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow().lifecycle());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
  }

  @Test
  void roundTripsTheMaximumSharedNumericContractValues() {
    Fixture fixture =
        fixture(
            "run-max-numeric",
            ContractValueDomains.MAX_USD,
            ContractValueDomains.MAX_SAFE_INTEGER,
            ContractValueDomains.MAX_DURATION_MS,
            ContractValueDomains.MAX_USD,
            ContractValueDomains.MAX_DURATION_MS);
    PostgresAgentRunStore store = store();
    store.start(fixture.running());

    AgentRun completed =
        store.complete(fixture.terminal(), fixture.artifact()).run();
    AgentRun loaded =
        store.findOwned(PRINCIPAL, fixture.terminal().runId()).orElseThrow();

    assertEquals(ContractValueDomains.MAX_USD, completed.result().costUsd());
    assertEquals(ContractValueDomains.MAX_SAFE_INTEGER, loaded.result().tokenCount());
    assertEquals(ContractValueDomains.MAX_DURATION_MS, loaded.result().latencyMs());
    assertEquals(completed, loaded);
  }

  @Test
  void databaseRejectsOverprecisionAndNumericOverflowEvenForRawWrites() {
    Fixture base = fixture("run-db-invalid-base");
    AgentRun cost =
        AgentRun.running(
            "run-db-invalid-cost", PRINCIPAL, base.running().task(), STARTED);
    AgentRun tokens =
        AgentRun.running(
            "run-db-invalid-tokens", PRINCIPAL, base.running().task(), STARTED);
    AgentRun latency =
        AgentRun.running(
            "run-db-invalid-latency", PRINCIPAL, base.running().task(), STARTED);
    PostgresAgentRunStore store = store();
    store.start(cost);
    store.start(tokens);
    store.start(latency);

    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET cost_usd = :value
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("value", new BigDecimal("0.0000001"))
                .param("principalId", PRINCIPAL)
                .param("runId", cost.runId())
                .update());
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET token_count = :value
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("value", ContractValueDomains.MAX_SAFE_INTEGER + 1)
                .param("principalId", PRINCIPAL)
                .param("runId", tokens.runId())
                .update());
    assertThrows(
        RuntimeException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET latency_ms = :value
                    WHERE principal_id = :principalId AND run_id = :runId
                    """)
                .param("value", ContractValueDomains.MAX_DURATION_MS + 1)
                .param("principalId", PRINCIPAL)
                .param("runId", latency.runId())
                .update());

    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*) FROM agent_runs
                WHERE principal_id = :principalId
                  AND (cost_usd <> 0 OR token_count <> 0 OR latency_ms <> 0)
                """)
            .param("principalId", PRINCIPAL)
            .query(Long.class)
            .single());
  }

  @Test
  void storeRejectsDraftSuccessWithoutTheAtomicArtifactWrite() {
    Fixture fixture = fixture("run-missing-atomic-artifact");
    PostgresAgentRunStore store = store();
    store.start(fixture.running());

    assertThrows(
        IllegalArgumentException.class,
        () -> store.complete(fixture.terminal(), null));

    assertEquals(
        AgentRunLifecycle.RUNNING,
        store.findOwned(PRINCIPAL, fixture.running().runId()).orElseThrow().lifecycle());
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Long.class).single());
  }

  private static PostgresAgentRunStore store() {
    return new PostgresAgentRunStore(
        dataSource,
        transactions,
        new PostgresArtifactLineageStore(dataSource, transactions));
  }

  private static ModelBindingColumns modelBindingColumns(String runId) {
    return jdbc.sql(
            """
            SELECT model_provider, model_requested, pricing_profile
            FROM agent_runs
            WHERE principal_id = :principalId AND run_id = :runId
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) ->
                new ModelBindingColumns(
                    resultSet.getString("model_provider"),
                    resultSet.getString("model_requested"),
                    resultSet.getString("pricing_profile")))
        .single();
  }

  private static DeadlineColumns deadlineColumns(String runId) {
    return jdbc.sql(
            """
            SELECT lifecycle_status, failure_attribution, latency_ms
            FROM agent_runs
            WHERE principal_id = :principalId AND run_id = :runId
            """)
        .param("principalId", PRINCIPAL)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) ->
                new DeadlineColumns(
                    resultSet.getString("lifecycle_status"),
                    resultSet.getString("failure_attribution"),
                    resultSet.getLong("latency_ms")))
        .single();
  }

  private static ModelBoundFixture modelBoundFixture(String runId) {
    String taskId = "task-" + runId;
    TaskEnvelope task =
        new TaskEnvelope(
            "1.1",
            taskId,
            null,
            PRINCIPAL,
            List.of(),
            "MODEL_EVAL",
            "Evaluate one frozen synthetic prompt",
            List.of(),
            List.of(),
            List.of("text"),
            DataClass.PUBLIC,
            RiskLevel.READ_ONLY,
            "INTERACTIVE",
            List.of(),
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            List.of("uses only frozen synthetic evidence"),
            false,
            1,
            1,
            30_000,
            new BigDecimal("0.010000"),
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1",
            "synthetic-" + runId,
            "synthetic-model-egress-policy-v1",
            "stage2-s3",
            "ref-only-v1",
            "agent-tools-v1",
            "environment://sha256:" + "b".repeat(64),
            List.of("capability://model-egress/synthetic-openai-v1"),
            List.of(),
            "structured final or non-success");
    AgentRun running = AgentRun.running(runId, PRINCIPAL, task, STARTED);
    String taskRef = "task://" + taskId;
    AgentTraceEntry failedStep =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "FAILED",
            taskRef,
            IntegrityHashes.emptyTraceRoot());
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", runId, taskId, List.of(failedStep));
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            taskId,
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "gpt-5.6-sol",
            "agent-draft-service-v1",
            "agent-draft-verifier-v1",
            new BigDecimal("0.001000"),
            100,
            100,
            "/api/v1/agent-runs/" + runId + "/trace",
            "MODEL_RESPONSE_MALFORMED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            "1.1",
            runId,
            taskId,
            new io.emergeos.contracts.HarnessExperiment("openai-responses-h0", 1),
            result.resolvedModel(),
            "framework-free-agent-kernel-v2",
            Map.of(
                "agent", result.agentVersion(),
                "verifier", result.verifierVersion(),
                "trace-integrity", IntegrityHashes.PROFILE,
                "model-adapter", "openai-responses-v1-openai-java-4.43.0"),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
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
    AgentRun failed =
        new AgentRun(
            runId,
            PRINCIPAL,
            task,
            AgentRunLifecycle.FAILED,
            result,
            trace,
            bundle,
            STARTED,
            COMPLETED);
    return new ModelBoundFixture(running, failed);
  }

  private static AgentRun postDispatchDeadlineFailedTerminal(
      Fixture fixture) {
    TaskEnvelope task = fixture.running().task();
    String runId = fixture.running().runId();
    String captureRef = "capture://" + CAPTURE_ID;
    String root = IntegrityHashes.emptyTraceRoot();
    java.util.ArrayList<AgentTraceEntry> events = new java.util.ArrayList<>();
    for (TraceSpec event :
        List.of(
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://" + task.id()),
            new TraceSpec(
                TraceEventType.TOOL_REQUEST,
                "capture.read",
                "REQUESTED",
                captureRef),
            new TraceSpec(
                TraceEventType.TOOL_REJECTED,
                "capture.read",
                "DEADLINE_EXCEEDED",
                captureRef))) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              events.size() + 1,
              event.type(),
              event.toolName(),
              event.status(),
              event.reference(),
              root);
      events.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", runId, task.id(), events);
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
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
            "scripted-fake-draft-v1",
            "agent-draft-service-v1",
            "agent-draft-verifier-v1",
            BigDecimal.ZERO,
            0,
            7,
            traceRef,
            "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            "1.0",
            runId,
            task.id(),
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            Map.of(
                "agent", result.agentVersion(),
                "verifier", result.verifierVersion(),
                "trace-integrity", IntegrityHashes.PROFILE),
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
        PRINCIPAL,
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        COMPLETED);
  }

  private static AgentRun failedTerminal(Fixture fixture) {
    TaskEnvelope task = fixture.running().task();
    String runId = fixture.running().runId();
    String taskRef = "task://" + task.id();
    String captureRef = "capture://" + CAPTURE_ID;
    String root = IntegrityHashes.emptyTraceRoot();
    java.util.ArrayList<AgentTraceEntry> events = new java.util.ArrayList<>();
    for (var event :
        List.of(
            new TraceSpec(TraceEventType.MODEL_STEP, null, "COMPLETED", taskRef),
            new TraceSpec(TraceEventType.TOOL_REQUEST, "capture.read", "REQUESTED", captureRef),
            new TraceSpec(TraceEventType.TOOL_RESULT, "capture.read", "SUCCEEDED", captureRef),
            new TraceSpec(TraceEventType.MODEL_STEP, null, "FAILED", taskRef))) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              events.size() + 1,
              event.type(),
              event.toolName(),
              event.status(),
              event.reference(),
              root);
      events.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", runId, task.id(), events);
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(captureRef),
            List.of(),
            List.of(),
            List.of(),
            "scripted-fake-draft-v1",
            "agent-draft-service-v1",
            "agent-draft-verifier-v1",
            BigDecimal.ZERO,
            0,
            10,
            traceRef,
            "MODEL_STEP_FAILED");
    ResourceBinding evidence =
        fixture.terminal().bundle().resourceBindings().getFirst();
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            "1.0",
            runId,
            task.id(),
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            Map.of(
                "agent", result.agentVersion(),
                "verifier", result.verifierVersion(),
                "trace-integrity", IntegrityHashes.PROFILE),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(evidence),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    return new AgentRun(
        runId,
        PRINCIPAL,
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        COMPLETED);
  }

  private static AgentRunStoreCompletion completeWithoutArtifact(
      PostgresAgentRunStore store, AgentRun terminal) {
    var completion = store.complete(terminal, null);
    return new AgentRunStoreCompletion(completion.run());
  }

  private static Fixture fixture(String runId) {
    return fixture(
        runId,
        BigDecimal.ZERO,
        0,
        10,
        BigDecimal.ZERO,
        5_000);
  }

  private static Fixture fixture(
      String runId,
      BigDecimal costUsd,
      long tokenCount,
      long latencyMs,
      BigDecimal budgetUsd,
      long deadlineMs) {
    String captureContent = "synthetic persisted Evidence";
    String captureHash =
        CaptureRequestHashes.sha256(
            captureContent, CaptureSourceType.TEXT, "synthetic", DataClass.PERSONAL);
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "nonce-" + runId,
            captureHash,
            captureContent,
            CaptureSourceType.TEXT,
            "synthetic",
            DataClass.PERSONAL,
            STARTED);
    new PostgresCaptureStore(dataSource, transactions).saveOrFindByNonce(capture);

    String taskId = "task-" + runId;
    TaskEnvelope task =
        new TaskEnvelope(
            "1.0",
            taskId,
            null,
            PRINCIPAL,
            List.of(),
            "CREATE_ARTICLE_DRAFT",
            "Create a synthetic draft",
            List.of("capture://" + CAPTURE_ID),
            List.of(),
            List.of("text"),
            DataClass.PERSONAL,
            RiskLevel.REVERSIBLE,
            "INTERACTIVE",
            List.of("capture.read"),
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            List.of("uses owned evidence"),
            false,
            2,
            1,
            deadlineMs,
            budgetUsd,
            null,
            null,
            null,
            null,
            "agent-draft-policy-v1",
            "stage2-s2",
            "ref-only-v1",
            "agent-tools-v1",
            null,
            List.of(),
            List.of(),
            "structured final or non-success");
    AgentRun running = AgentRun.running(runId, PRINCIPAL, task, STARTED);

    String artifactContent = "synthetic immutable output";
    String artifactId = "artifact-" + runId;
    ArtifactLineage artifact =
        new ArtifactLineage(
            artifactId,
            PRINCIPAL,
            CAPTURE_ID,
            List.of(
                new ArtifactLineageEntry(
                    1,
                    artifactContent,
                    ContentHashes.sha256(artifactContent),
                    null,
                    null,
                    COMPLETED)));
    String artifactRef = "artifact-version://" + artifactId + "/1";
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    String root = IntegrityHashes.emptyTraceRoot();
    java.util.ArrayList<AgentTraceEntry> traceEvents = new java.util.ArrayList<>();
    for (TraceSpec spec :
        List.of(
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://" + taskId),
            new TraceSpec(
                TraceEventType.TOOL_REQUEST,
                "capture.read",
                "REQUESTED",
                "capture://" + CAPTURE_ID),
            new TraceSpec(
                TraceEventType.TOOL_RESULT,
                "capture.read",
                "SUCCEEDED",
                "capture://" + CAPTURE_ID),
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://" + taskId),
            new TraceSpec(
                TraceEventType.STRUCTURED_FINAL,
                null,
                "PROPOSED",
                "task://" + taskId),
            new TraceSpec(
                TraceEventType.ARTIFACT_COMMITTED,
                null,
                "SUCCEEDED",
                artifactRef))) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              traceEvents.size() + 1,
              spec.type(),
              spec.toolName(),
              spec.status(),
              spec.reference(),
              root);
      traceEvents.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", runId, taskId, traceEvents);
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            taskId,
            RunStatus.SUCCEEDED,
            List.of(artifactRef),
            List.of("capture://" + CAPTURE_ID),
            List.of(),
            List.of(),
            List.of(),
            "scripted-fake-draft-v1",
            "agent-draft-service-v1",
            "agent-draft-verifier-v1",
            costUsd,
            tokenCount,
            latencyMs,
            traceRef,
            null);
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE, 0, "capture://" + CAPTURE_ID, captureHash),
            new ResourceBinding(
                ResourceRole.ARTIFACT,
                0,
                artifactRef,
                artifact.current().contentHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            "1.0",
            runId,
            taskId,
            null,
            result.resolvedModel(),
            "framework-free-agent-kernel-v1",
            Map.of(
                "agent", result.agentVersion(),
                "verifier", result.verifierVersion(),
                "trace-integrity", IntegrityHashes.PROFILE),
            null,
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            bindings,
            null,
            null,
            RunStatus.SUCCEEDED,
            costUsd,
            tokenCount,
            latencyMs);
    AgentRun terminal =
        new AgentRun(
            runId,
            PRINCIPAL,
            task,
            AgentRunLifecycle.SUCCEEDED,
            result,
            trace,
            bundle,
            STARTED,
            COMPLETED);
    assertNotNull(bundle.integrityHash());
    return new Fixture(running, terminal, artifact);
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private record Fixture(
      AgentRun running,
      AgentRun terminal,
      ArtifactLineage artifact) {}

  private record ModelBoundFixture(AgentRun running, AgentRun failed) {}

  private record ModelBindingColumns(
      String provider,
      String requestedModel,
      String pricingProfile) {}

  private record DeadlineColumns(
      String lifecycle, String failureAttribution, long latencyMs) {}

  private record TraceSpec(
      TraceEventType type, String toolName, String status, String reference) {}

  private record AgentRunStoreCompletion(AgentRun run) {}

  private static final class SyntheticFinalizationFailure extends RuntimeException {}
}
