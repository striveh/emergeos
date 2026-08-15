package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class PostgresReadOnlyWorkerConstraintTest {

  private static final String PRINCIPAL = "pack007-owner";
  private static final Instant STARTED =
      Instant.parse("2026-07-31T03:30:00Z");
  private static final Instant COMPLETED =
      Instant.parse("2026-07-31T03:30:01Z");
  private static final JsonMapper JSON = JsonMapper.shared();
  private static final ReadOnlyWorkerExecutionProfile WORKER_PROFILE =
      ReadOnlyWorkerExecutionProfile.pack007FakeV1();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_worker_constraints")
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
  void missingTaskIdentityAndNullHandoffReferenceCannotUseSqlNullBypass() {
    RunningPair pair = runningPair();

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET task_envelope = '{"schemaVersion":"1.0"}'::jsonb
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                    """)
                .param("principalId", PRINCIPAL)
                .param("runId", pair.parent().runId())
                .update());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET task_envelope = task_envelope - 'kind'
                    WHERE principal_id = :principalId
                      AND run_id = :runId
                    """)
                .param("principalId", PRINCIPAL)
                .param("runId", pair.parent().runId())
                .update());

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO agent_trace_events (
                        principal_id, run_id, sequence, event_type,
                        tool_name, status, resource_ref, previous_root_hash,
                        event_hash, current_root_hash
                    ) VALUES (
                        :principalId, :runId, 1, 'HANDOFF_REQUEST',
                        NULL, 'REQUESTED', NULL, :previousRoot,
                        :eventHash, :currentRoot
                    )
                    """)
                .param("principalId", PRINCIPAL)
                .param("runId", pair.parent().runId())
                .param("previousRoot", "a".repeat(64))
                .param("eventHash", "b".repeat(64))
                .param("currentRoot", "c".repeat(64))
                .update());
  }

  @Test
  void emptyWorkerResultJsonAndNonWorkerOwnershipAreRejected() {
    RunningPair pair = runningPair();

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO agent_worker_results (
                        principal_id, parent_run_id, child_run_id,
                        child_task_id, child_status, worker_result_ref,
                        worker_result_envelope, content_hash, integrity_hash
                    ) VALUES (
                        :principalId, :parentRunId, :childRunId,
                        :childTaskId, 'SUCCEEDED', :workerResultRef,
                        '{}'::jsonb, :contentHash, :integrityHash
                    )
                    """)
                .param("principalId", PRINCIPAL)
                .param("parentRunId", pair.parent().runId())
                .param("childRunId", pair.child().runId())
                .param("childTaskId", pair.child().task().id())
                .param(
                    "workerResultRef",
                    "worker-result://" + pair.child().runId())
                .param("contentHash", "a".repeat(64))
                .param("integrityHash", "b".repeat(64))
                .update());

    String coordinatedRootResult =
        coordinatedWorkerResultJson(
            pair.parent().runId(), pair.parent().task().id());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO agent_worker_results (
                        principal_id, parent_run_id, child_run_id,
                        child_task_id, child_status, worker_result_ref,
                        worker_result_envelope, content_hash, integrity_hash
                    ) VALUES (
                        :principalId, 'fictional-parent-run', :childRunId,
                        :childTaskId, 'SUCCEEDED', :workerResultRef,
                        CAST(:workerResultJson AS jsonb),
                        :contentHash, :integrityHash
                    )
                    """)
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.parent().runId())
                .param("childTaskId", pair.parent().task().id())
                .param(
                    "workerResultRef",
                    "worker-result://" + pair.parent().runId())
                .param("workerResultJson", coordinatedRootResult)
                .param("contentHash", "a".repeat(64))
                .param("integrityHash", "b".repeat(64))
                .update());
  }

  @Test
  void successfulWorkerCannotCommitWithoutItsExactResultAndBinding() {
    RunningPair pair = runningPair();
    HarnessRunBundle childBundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-pack007-child.json",
            HarnessRunBundle.class);

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET lifecycle_status = 'SUCCEEDED',
                        result_envelope = CAST(:resultJson AS jsonb),
                        bundle = CAST(:bundleJson AS jsonb),
                        bundle_hash = :bundleHash,
                        trace_root_hash = :traceRootHash,
                        last_event_sequence = 5,
                        resolved_model = :resolvedModel,
                        agent_version = :agentVersion,
                        verifier_version = :verifierVersion,
                        harness_version = :harnessVersion,
                        cost_usd = 0,
                        token_count = 0,
                        latency_ms = 0,
                        completed_at = :completedAt
                    WHERE principal_id = :principalId
                      AND run_id = :childRunId
                    """)
                .param(
                    "resultJson",
                    JSON.writeValueAsString(childBundle.result()))
                .param("bundleJson", JSON.writeValueAsString(childBundle))
                .param("bundleHash", childBundle.integrityHash())
                .param("traceRootHash", childBundle.traceRootHash())
                .param("resolvedModel", childBundle.modelResolved())
                .param(
                    "agentVersion",
                    childBundle.result().agentVersion())
                .param(
                    "verifierVersion",
                    childBundle.result().verifierVersion())
                .param("harnessVersion", childBundle.harnessVersion())
                .param("completedAt", Timestamp.from(COMPLETED))
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update());

    assertEquals(
        "RUNNING",
        jdbc.sql(
                """
                SELECT lifecycle_status
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :childRunId
                """)
            .param("principalId", PRINCIPAL)
            .param("childRunId", pair.child().runId())
            .query(String.class)
            .single());
  }

  @Test
  void crossOwnerChildCannotReferenceForeignParent() {
    RunningPair pair = runningPair();
    TaskEnvelope foreignChild =
        copyTaskPrincipal(pair.child().task(), "foreign-owner");
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertConstraintFailure(
        "23503",
        "agent_runs_parent_run_fk",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawWorker(
                      "foreign-child-run",
                      pair.parent().runId(),
                      pair.parent().task().id(),
                      foreignChild);
                  forceConstraint("agent_runs_parent_run_fk");
                }));
    assertEquals(2L, count("agent_runs"));
  }

  @Test
  void runningChildCannotSatisfyHandoffForeignKey() {
    RunningPair pair = runningPair();
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertConstraintFailure(
        "23503",
        "agent_run_resource_bindings_handoff_child_fk",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawHandoff(
                      pair.parent().runId(),
                      pair.child().runId(),
                      "a".repeat(64));
                  forceConstraint(
                      "agent_run_resource_bindings_handoff_child_fk");
                }));
    assertEquals(0L, count("agent_run_resource_bindings"));
  }

  @Test
  void terminalChildCannotBeBoundBeforeItsParentBecomesTerminal() {
    RunningPair pair = runningPair();
    AgentRun child = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), child, workerResult());
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertConstraintFailure(
        "23503",
        "agent_run_resource_bindings_owner_terminal_fk",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawHandoff(
                      pair.parent().runId(),
                      child.runId(),
                      child.bundle().integrityHash());
                  forceConstraint(
                      "agent_run_resource_bindings_owner_terminal_fk");
                }));
    assertEquals(0L, handoffCount(pair.parent().runId()));
  }

  @Test
  void wrongParentCannotBindTerminalChild() {
    RunningPair pair = runningPair();
    WorkerResultEnvelope workerResult = workerResult();
    AgentRun childTerminal = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), childTerminal, workerResult);
    AgentRun alias =
        pair.store()
            .start(
                AgentRun.running(
                    "pack007-alias-parent-run",
                    PRINCIPAL,
                    pair.parent().task(),
                    STARTED));
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertConstraintFailure(
        "23503",
        "agent_run_resource_bindings_handoff_child_fk",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawHandoff(
                      alias.runId(),
                      pair.child().runId(),
                      childTerminal.bundle().integrityHash());
                  forceConstraint(
                      "agent_run_resource_bindings_handoff_child_fk");
                }));
    assertEquals(0L, handoffCount(alias.runId()));
  }

  @Test
  void wrongBundleHashCannotBindTerminalChild() {
    RunningPair pair = runningPair();
    pair.store()
        .completeWorker(
            pair.context(), workerTerminal(pair.child()), workerResult());
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertConstraintFailure(
        "23503",
        "agent_run_resource_bindings_handoff_child_fk",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawHandoff(
                      pair.parent().runId(),
                      pair.child().runId(),
                      "f".repeat(64));
                  forceConstraint(
                      "agent_run_resource_bindings_handoff_child_fk");
                }));
    assertEquals(0L, handoffCount(pair.parent().runId()));
  }

  @Test
  void immediateSelfLinkCannotCommit() {
    TaskEnvelope base =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-child.json",
            TaskEnvelope.class);
    TaskEnvelope self =
        cycleTask(base, "self-child-task", "self-parent-task");

    assertConstraintFailure(
        "23514",
        "agent_runs_parent_columns_shape",
        () ->
            insertRawWorker(
                "self-child-run",
                "self-child-run",
                "self-parent-task",
                self));
    assertEquals(0L, count("agent_runs"));
  }

  @Test
  void committedChildCannotBeConsumedBySecondParent() {
    RunningPair pair = runningPair();
    AgentRun child = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), child, workerResult());
    ParentCompletion completion = happyParent(pair);
    pair.store().complete(completion.terminal(), completion.artifact());
    AgentRun alias =
        pair.store()
            .start(
                AgentRun.running(
                    "pack007-second-parent-run",
                    PRINCIPAL,
                    pair.parent().task(),
                    STARTED));

    assertConstraintFailure(
        "23505",
        "agent_run_resource_bindings_child_consumed_uq",
        () ->
            insertRawHandoff(
                alias.runId(),
                child.runId(),
                child.bundle().integrityHash()));
    assertEquals(1L, handoffCount(pair.parent().runId()));
    assertEquals(0L, handoffCount(alias.runId()));
  }

  @Test
  void exactParentCompletionWinsAgainstConcurrentAliasConsumer()
      throws Exception {
    RunningPair pair = runningPair();
    AgentRun child = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), child, workerResult());
    AgentRun alias =
        pair.store()
            .start(
                AgentRun.running(
                    "pack007-racing-alias-parent-run",
                    PRINCIPAL,
                    pair.parent().task(),
                    STARTED));
    ParentCompletion completion = happyParent(pair);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Future<String> exact =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                store()
                    .complete(
                        completion.terminal(),
                        completion.artifact());
                return "exact-parent-committed";
              });
      Future<String> contender =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                try {
                  TransactionTemplate transaction =
                      new TransactionTemplate(transactions);
                  transaction.executeWithoutResult(
                      status -> {
                        insertRawHandoff(
                            alias.runId(),
                            child.runId(),
                            child.bundle().integrityHash());
                        forceConstraint(
                            "agent_run_resource_bindings_handoff_child_fk");
                      });
                  return "unexpected-alias-commit";
                } catch (RuntimeException expected) {
                  PSQLException postgres = postgresCause(expected);
                  assertNotNull(postgres);
                  return postgres.getSQLState();
                }
              });
      ready.await();
      start.countDown();

      assertEquals("exact-parent-committed", exact.get());
      assertTrue(Set.of("23503", "23505").contains(contender.get()));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1L, handoffCount(pair.parent().runId()));
    assertEquals(0L, handoffCount(alias.runId()));
    assertEquals(1L, count("artifacts"));
    assertEquals(
        "SUCCEEDED",
        jdbc.sql(
                """
                SELECT lifecycle_status
                FROM agent_runs
                WHERE principal_id = :principalId
                  AND run_id = :runId
                """)
            .param("principalId", PRINCIPAL)
            .param("runId", pair.parent().runId())
            .query(String.class)
            .single());
  }

  @Test
  void databaseValidHandoffCannotBeMovedWithoutInvalidatingItsOldParent() {
    RunningPair pair = runningPair();
    AgentRun child = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), child, workerResult());
    ParentCompletion completion = happyParent(pair);
    pair.store().complete(completion.terminal(), completion.artifact());
    String secondParentRunId = "pack007-second-terminal-parent-run";
    String secondChildRunId = "pack007-second-terminal-child-run";
    cloneTerminalGraph(
        pair.parent().runId(),
        child.runId(),
        secondParentRunId,
        secondChildRunId);
    assertEquals(1L, handoffCount(pair.parent().runId()));
    assertEquals(1L, handoffCount(secondParentRunId));
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertSqlState(
        "23514",
        () ->
            transaction.executeWithoutResult(
                status -> {
                  jdbc.sql(
                          """
                          DELETE FROM agent_run_resource_bindings
                          WHERE principal_id = :principalId
                            AND run_id = :runId
                            AND role = 'HANDOFF'
                          """)
                      .param("principalId", PRINCIPAL)
                      .param("runId", secondParentRunId)
                      .update();
                  jdbc.sql(
                          """
                          UPDATE agent_run_resource_bindings
                          SET run_id = :secondParentRunId,
                              resource_ref = :secondChildRunRef,
                              handoff_child_run_id = :secondChildRunId
                          WHERE principal_id = :principalId
                            AND run_id = :firstParentRunId
                            AND role = 'HANDOFF'
                          """)
                      .param("secondParentRunId", secondParentRunId)
                      .param(
                          "secondChildRunRef",
                          "agent-run://" + secondChildRunId)
                      .param("secondChildRunId", secondChildRunId)
                      .param("principalId", PRINCIPAL)
                      .param("firstParentRunId", pair.parent().runId())
                      .update();
                }));

    assertEquals(1L, handoffCount(pair.parent().runId()));
    assertEquals(1L, handoffCount(secondParentRunId));
  }

  @Test
  void coordinatedShapeValidButCryptographicallyTamperedWorkerResultFailsFreshVerifiedRead() {
    RunningPair pair = runningPair();
    AgentRun child = workerTerminal(pair.child());
    pair.store()
        .completeWorker(pair.context(), child, workerResult());
    String tamperedContent = "coordinated but cryptographically invalid";
    String tamperedContentHash = "a".repeat(64);
    String tamperedIntegrityHash = "b".repeat(64);
    HarnessRunBundle tamperedBundle =
        replaceWorkerResultBinding(
            child.bundle(), tamperedIntegrityHash);
    String tamperedWorkerResultJson =
        coordinatedWorkerResultJson(
            pair.child().runId(),
            pair.child().task().id(),
            tamperedContent,
            tamperedContentHash,
            tamperedIntegrityHash);
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    jdbc.sql(
            """
            ALTER TABLE agent_worker_results
            DISABLE TRIGGER agent_worker_results_immutable_v6
            """)
        .update();
    try {
      transaction.executeWithoutResult(
          status -> {
            jdbc.sql(
                    """
                    UPDATE agent_worker_results
                    SET worker_result_envelope =
                            CAST(:workerResultJson AS jsonb),
                        content_hash = :contentHash,
                        integrity_hash = :integrityHash
                    WHERE principal_id = :principalId
                      AND child_run_id = :childRunId
                    """)
                .param("workerResultJson", tamperedWorkerResultJson)
                .param("contentHash", tamperedContentHash)
                .param("integrityHash", tamperedIntegrityHash)
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update();
            jdbc.sql(
                    """
                    UPDATE agent_run_resource_bindings
                    SET content_hash = :integrityHash
                    WHERE principal_id = :principalId
                      AND run_id = :childRunId
                      AND role = 'WORKER_RESULT'
                    """)
                .param("integrityHash", tamperedIntegrityHash)
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update();
            jdbc.sql(
                    """
                    UPDATE agent_runs
                    SET bundle = CAST(:bundleJson AS jsonb),
                        bundle_hash = :bundleHash
                    WHERE principal_id = :principalId
                      AND run_id = :childRunId
                    """)
                .param(
                    "bundleJson",
                    JSON.writeValueAsString(tamperedBundle))
                .param("bundleHash", tamperedBundle.integrityHash())
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update();
          });
    } finally {
      jdbc.sql(
              """
              ALTER TABLE agent_worker_results
              ENABLE TRIGGER agent_worker_results_immutable_v6
              """)
          .update();
    }

    assertThrows(
        AgentRunIntegrityException.class,
        () ->
            store()
                .findWorkerOwned(
                    pair.context(), pair.child().runId()));
  }

  @Test
  void depthOneWorkersCannotFormANestedCycle() {
    TaskEnvelope baseChild =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-child.json",
            TaskEnvelope.class);
    TaskEnvelope first =
        cycleTask(baseChild, "cycle-a-task", "cycle-b-task");
    TaskEnvelope second =
        cycleTask(baseChild, "cycle-b-task", "cycle-a-task");
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertThrows(
        TransactionSystemException.class,
        () ->
            transaction.executeWithoutResult(
                status -> {
                  insertRawWorker(
                      "cycle-a-run", "cycle-b-run", "cycle-b-task", first);
                  insertRawWorker(
                      "cycle-b-run", "cycle-a-run", "cycle-a-task", second);
                }));

    assertEquals(0L, count("agent_runs"));
  }

  @Test
  void committedWorkerResultCannotBeUpdatedOrDeleted() {
    RunningPair pair = runningPair();
    WorkerResultEnvelope result =
        readFixture(
            "contracts/fixtures/v1/worker-result-envelope/valid.json",
            WorkerResultEnvelope.class);
    pair.store()
        .completeWorker(
            pair.context(),
            workerTerminal(pair.child()),
            result);

    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    UPDATE agent_worker_results
                    SET content_hash = :contentHash
                    WHERE principal_id = :principalId
                      AND child_run_id = :childRunId
                    """)
                .param("contentHash", "f".repeat(64))
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update());
    assertSqlState(
        "55000",
        () ->
            jdbc.sql(
                    """
                    DELETE FROM agent_worker_results
                    WHERE principal_id = :principalId
                      AND child_run_id = :childRunId
                    """)
                .param("principalId", PRINCIPAL)
                .param("childRunId", pair.child().runId())
                .update());
    assertEquals(1L, count("agent_worker_results"));
  }

  private static RunningPair runningPair() {
    TaskEnvelope parentTask =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-parent.json",
            TaskEnvelope.class);
    TaskEnvelope childTask =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-child.json",
            TaskEnvelope.class);
    String captureContent =
        "Prompt 工程是在为概率程序构造运行时状态。";
    String sourceRef = "synthetic-pack-007";
    new PostgresCaptureStore(dataSource, transactions)
        .saveOrFindByNonce(
            new Capture(
                "pack007-capture",
                PRINCIPAL,
                "pack007-constraint-nonce",
                CaptureRequestHashes.sha256(
                    captureContent,
                    CaptureSourceType.TEXT,
                    sourceRef,
                    DataClass.PUBLIC),
                captureContent,
                CaptureSourceType.TEXT,
                sourceRef,
                DataClass.PUBLIC,
                STARTED));
    PostgresAgentRunStore store = store();
    AgentRun parent =
        store.start(
            AgentRun.running(
                "pack007-parent-run", PRINCIPAL, parentTask, STARTED));
    AgentRunContext context = AgentRunContext.fromRunning(parent);
    AgentRun child =
        store.startWorker(
            context,
            AgentRun.running(
                "pack007-child-run", PRINCIPAL, childTask, STARTED));
    return new RunningPair(store, context, parent, child);
  }

  private static AgentRun workerTerminal(AgentRun running) {
    HarnessRunBundle bundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-pack007-child.json",
            HarnessRunBundle.class);
    return new AgentRun(
        running.runId(),
        running.principalId(),
        running.task(),
        io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
        bundle.result(),
        readFixture(
            "contracts/fixtures/v1/agent-trace-envelope/"
                + "valid-pack007-child-proposal.json",
            io.emergeos.contracts.AgentTraceEnvelope.class),
        bundle,
        running.startedAt(),
        COMPLETED);
  }

  private static WorkerResultEnvelope workerResult() {
    return readFixture(
        "contracts/fixtures/v1/worker-result-envelope/valid.json",
        WorkerResultEnvelope.class);
  }

  private static HarnessRunBundle replaceWorkerResultBinding(
      HarnessRunBundle bundle, String integrityHash) {
    List<ResourceBinding> bindings =
        bundle.resourceBindings().stream()
            .map(
                binding ->
                    binding.role() == ResourceRole.WORKER_RESULT
                        ? new ResourceBinding(
                            binding.role(),
                            binding.ordinal(),
                            binding.ref(),
                            integrityHash)
                        : binding)
            .toList();
    return HarnessRunBundle.create(
        bundle.schemaVersion(),
        bundle.runId(),
        bundle.taskId(),
        bundle.experiment(),
        bundle.modelResolved(),
        bundle.harnessVersion(),
        bundle.componentVersions(),
        bundle.environmentSnapshotRef(),
        bundle.toolRegistryVersion(),
        bundle.task(),
        bundle.result(),
        bundle.workingSelfRef(),
        bundle.traceRef(),
        bundle.traceRootHash(),
        bundle.handoffRefs(),
        bundle.checkpointRefs(),
        bindings,
        bundle.verificationRef(),
        bundle.failureAttribution(),
        bundle.outcome(),
        bundle.costUsd(),
        bundle.tokenCount(),
        bundle.latencyMs());
  }

  private static ParentCompletion happyParent(RunningPair pair) {
    HarnessRunBundle parentBundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-pack007-parent.json",
            HarnessRunBundle.class);
    AgentTraceEnvelope parentTrace =
        readFixture(
            "contracts/fixtures/v1/agent-trace-envelope/"
                + "valid-pack007-parent-handoff.json",
            AgentTraceEnvelope.class);
    AgentRun terminal =
        new AgentRun(
            pair.parent().runId(),
            PRINCIPAL,
            pair.parent().task(),
            io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
            parentBundle.result(),
            parentTrace,
            parentBundle,
            STARTED,
            COMPLETED.plusSeconds(1));
    WorkerResultEnvelope workerResult = workerResult();
    ArtifactLineage artifact =
        new ArtifactLineage(
            "pack007-parent-artifact",
            PRINCIPAL,
            "pack007-capture",
            List.of(
                new ArtifactLineageEntry(
                    1,
                    workerResult.content(),
                    ContentHashes.sha256(workerResult.content()),
                    null,
                    null,
                    COMPLETED.plusSeconds(1))));
    return new ParentCompletion(terminal, artifact);
  }

  private static TaskEnvelope cycleTask(
      TaskEnvelope base, String taskId, String parentTaskId) {
    return new TaskEnvelope(
        base.schemaVersion(),
        taskId,
        parentTaskId,
        base.principalRef(),
        List.of(parentTaskId),
        base.kind(),
        base.intent(),
        base.inputRefs(),
        base.evidenceRefs(),
        base.modalities(),
        base.dataClass(),
        base.risk(),
        base.latencyClass(),
        base.requiredTools(),
        base.outputSchema(),
        base.acceptanceChecks(),
        base.allowParallel(),
        base.maxModelSteps(),
        base.maxToolCalls(),
        base.deadlineMs(),
        base.budgetUsd(),
        base.modelProvider(),
        base.modelRequested(),
        base.pricingProfile(),
        base.idempotencyKey(),
        base.policyVersion(),
        base.stateVersion(),
        base.contextPolicyVersion(),
        base.toolRegistryVersion(),
        base.environmentSnapshotRef(),
        base.capabilityRefs(),
        base.unresolvedDecisions(),
        base.returnControlWhen());
  }

  private static TaskEnvelope copyTaskPrincipal(
      TaskEnvelope base, String principal) {
    return new TaskEnvelope(
        base.schemaVersion(),
        base.id(),
        base.parentId(),
        principal,
        base.delegationChain(),
        base.kind(),
        base.intent(),
        base.inputRefs(),
        base.evidenceRefs(),
        base.modalities(),
        base.dataClass(),
        base.risk(),
        base.latencyClass(),
        base.requiredTools(),
        base.outputSchema(),
        base.acceptanceChecks(),
        base.allowParallel(),
        base.maxModelSteps(),
        base.maxToolCalls(),
        base.deadlineMs(),
        base.budgetUsd(),
        base.modelProvider(),
        base.modelRequested(),
        base.pricingProfile(),
        base.idempotencyKey(),
        base.policyVersion(),
        base.stateVersion(),
        base.contextPolicyVersion(),
        base.toolRegistryVersion(),
        base.environmentSnapshotRef(),
        base.capabilityRefs(),
        base.unresolvedDecisions(),
        base.returnControlWhen());
  }

  private static void insertRawWorker(
      String runId,
      String parentRunId,
      String parentTaskId,
      TaskEnvelope task) {
    jdbc.sql(
            """
            INSERT INTO agent_runs (
                principal_id, run_id, task_id, parent_run_id,
                parent_task_id, run_depth, parent_run_depth,
                lifecycle_status, task_envelope, tool_registry_version,
                policy_version, state_version, context_policy_version,
                started_at
            ) VALUES (
                :principalId, :runId, :taskId, :parentRunId,
                :parentTaskId, 1, 0, 'RUNNING',
                CAST(:taskJson AS jsonb), :toolRegistryVersion,
                :policyVersion, :stateVersion, :contextPolicyVersion,
                :startedAt
            )
            """)
        .param("principalId", task.principalRef())
        .param("runId", runId)
        .param("taskId", task.id())
        .param("parentRunId", parentRunId)
        .param("parentTaskId", parentTaskId)
        .param("taskJson", JSON.writeValueAsString(task))
        .param("toolRegistryVersion", task.toolRegistryVersion())
        .param("policyVersion", task.policyVersion())
        .param("stateVersion", task.stateVersion())
        .param("contextPolicyVersion", task.contextPolicyVersion())
        .param("startedAt", Timestamp.from(STARTED))
        .update();
  }

  private static void insertRawHandoff(
      String parentRunId, String childRunId, String childBundleHash) {
    jdbc.sql(
            """
            INSERT INTO agent_run_resource_bindings (
                principal_id, run_id, role, ordinal, resource_ref,
                content_hash, capture_id, artifact_id, artifact_version,
                handoff_child_run_id, worker_result_child_run_id,
                binding_owner_status
            ) VALUES (
                :principalId, :parentRunId, 'HANDOFF', 0, :resourceRef,
                :contentHash, NULL, NULL, NULL,
                :childRunId, NULL, 'FAILED'
            )
            """)
        .param("principalId", PRINCIPAL)
        .param("parentRunId", parentRunId)
        .param("resourceRef", "agent-run://" + childRunId)
        .param("contentHash", childBundleHash)
        .param("childRunId", childRunId)
        .update();
  }

  private static void cloneTerminalGraph(
      String firstParentRunId,
      String firstChildRunId,
      String secondParentRunId,
      String secondChildRunId) {
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    transaction.executeWithoutResult(
        status -> {
          jdbc.sql(
                  """
                  INSERT INTO agent_runs
                  SELECT (
                    jsonb_populate_record(
                      NULL::agent_runs,
                      to_jsonb(source)
                        || jsonb_build_object(
                          'run_id', CAST(:secondParentRunId AS text)
                        )
                    )
                  ).*
                  FROM agent_runs AS source
                  WHERE principal_id = :principalId
                    AND run_id = :firstParentRunId
                  """)
              .param("secondParentRunId", secondParentRunId)
              .param("principalId", PRINCIPAL)
              .param("firstParentRunId", firstParentRunId)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO agent_runs
                  SELECT (
                    jsonb_populate_record(
                      NULL::agent_runs,
                      to_jsonb(source)
                        || jsonb_build_object(
                          'run_id', CAST(:secondChildRunId AS text),
                          'parent_run_id',
                            CAST(:secondParentRunId AS text)
                        )
                    )
                  ).*
                  FROM agent_runs AS source
                  WHERE principal_id = :principalId
                    AND run_id = :firstChildRunId
                  """)
              .param("secondChildRunId", secondChildRunId)
              .param("secondParentRunId", secondParentRunId)
              .param("principalId", PRINCIPAL)
              .param("firstChildRunId", firstChildRunId)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO agent_worker_results
                  SELECT (
                    jsonb_populate_record(
                      NULL::agent_worker_results,
                      to_jsonb(source)
                        || jsonb_build_object(
                          'parent_run_id',
                            CAST(:secondParentRunId AS text),
                          'child_run_id',
                            CAST(:secondChildRunId AS text),
                          'worker_result_ref',
                            CAST(:secondWorkerResultRef AS text),
                          'worker_result_envelope',
                            source.worker_result_envelope
                              || jsonb_build_object(
                                'childRunId',
                                  CAST(:secondChildRunId AS text),
                                'workerResultRef',
                                  CAST(:secondWorkerResultRef AS text)
                              )
                        )
                    )
                  ).*
                  FROM agent_worker_results AS source
                  WHERE principal_id = :principalId
                    AND child_run_id = :firstChildRunId
                  """)
              .param("secondParentRunId", secondParentRunId)
              .param("secondChildRunId", secondChildRunId)
              .param(
                  "secondWorkerResultRef",
                  "worker-result://" + secondChildRunId)
              .param("principalId", PRINCIPAL)
              .param("firstChildRunId", firstChildRunId)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO agent_run_resource_bindings
                  SELECT (
                    jsonb_populate_record(
                      NULL::agent_run_resource_bindings,
                      to_jsonb(source)
                        || jsonb_build_object(
                          'run_id', CAST(:secondChildRunId AS text),
                          'resource_ref',
                            CASE
                              WHEN source.role = 'WORKER_RESULT'
                                THEN CAST(
                                  :secondWorkerResultRef AS text
                                )
                              ELSE source.resource_ref
                            END,
                          'worker_result_child_run_id',
                            CASE
                              WHEN source.role = 'WORKER_RESULT'
                                THEN CAST(:secondChildRunId AS text)
                              ELSE source.worker_result_child_run_id
                            END
                        )
                    )
                  ).*
                  FROM agent_run_resource_bindings AS source
                  WHERE principal_id = :principalId
                    AND run_id = :firstChildRunId
                  """)
              .param("secondChildRunId", secondChildRunId)
              .param(
                  "secondWorkerResultRef",
                  "worker-result://" + secondChildRunId)
              .param("principalId", PRINCIPAL)
              .param("firstChildRunId", firstChildRunId)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO agent_run_resource_bindings
                  SELECT (
                    jsonb_populate_record(
                      NULL::agent_run_resource_bindings,
                      to_jsonb(source)
                        || jsonb_build_object(
                          'run_id', CAST(:secondParentRunId AS text),
                          'resource_ref',
                            CASE
                              WHEN source.role = 'HANDOFF'
                                THEN CAST(:secondChildRunRef AS text)
                              ELSE source.resource_ref
                            END,
                          'handoff_child_run_id',
                            CASE
                              WHEN source.role = 'HANDOFF'
                                THEN CAST(:secondChildRunId AS text)
                              ELSE source.handoff_child_run_id
                            END
                        )
                    )
                  ).*
                  FROM agent_run_resource_bindings AS source
                  WHERE principal_id = :principalId
                    AND run_id = :firstParentRunId
                  """)
              .param("secondParentRunId", secondParentRunId)
              .param(
                  "secondChildRunRef",
                  "agent-run://" + secondChildRunId)
              .param("secondChildRunId", secondChildRunId)
              .param("principalId", PRINCIPAL)
              .param("firstParentRunId", firstParentRunId)
              .update();
        });
  }

  private static void forceConstraint(String constraint) {
    jdbc.sql("SET CONSTRAINTS " + constraint + " IMMEDIATE")
        .update();
  }

  private static void assertConstraintFailure(
      String sqlState, String constraint, Runnable action) {
    PSQLException postgres = assertPostgresFailure(sqlState, action);
    assertNotNull(postgres.getServerErrorMessage());
    assertEquals(
        constraint,
        postgres.getServerErrorMessage().getConstraint());
  }

  private static void assertSqlState(String sqlState, Runnable action) {
    assertPostgresFailure(sqlState, action);
  }

  private static PSQLException assertPostgresFailure(
      String sqlState, Runnable action) {
    RuntimeException failure =
        assertThrows(RuntimeException.class, action::run);
    PSQLException postgres = postgresCause(failure);
    assertNotNull(postgres);
    assertEquals(sqlState, postgres.getSQLState());
    return postgres;
  }

  private static PSQLException postgresCause(Throwable failure) {
    Throwable current = failure;
    while (current != null && !(current instanceof PSQLException)) {
      current = current.getCause();
    }
    return (PSQLException) current;
  }

  private static String coordinatedWorkerResultJson(
      String childRunId, String childTaskId) {
    return coordinatedWorkerResultJson(
        childRunId,
        childTaskId,
        "coordinated raw SQL content",
        "a".repeat(64),
        "b".repeat(64));
  }

  private static String coordinatedWorkerResultJson(
      String childRunId,
      String childTaskId,
      String content,
      String contentHash,
      String integrityHash) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", "1.0");
    values.put("workerResultRef", "worker-result://" + childRunId);
    values.put("childRunId", childRunId);
    values.put("childTaskId", childTaskId);
    values.put(
        "outputSchema",
        ReadOnlyWorkerExecutionProfile.OUTPUT_SCHEMA);
    values.put("content", content);
    values.put("contentHash", contentHash);
    values.put(
        "evidenceRefs",
        List.of("capture://pack007-capture"));
    values.put(
        "integrityProfile",
        "emergeos-length-prefixed-sha256-v1");
    values.put("integrityHash", integrityHash);
    return JSON.writeValueAsString(values);
  }

  private static <T> T readFixture(String relativePath, Class<T> type) {
    try {
      Path root = Path.of("").toAbsolutePath();
      while (root != null
          && !Files.isDirectory(root.resolve("contracts/fixtures"))) {
        root = root.getParent();
      }
      if (root == null) {
        throw new IllegalStateException("Cannot locate repository root");
      }
      return JSON.readValue(
          Files.readString(root.resolve(relativePath)), type);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Cannot load frozen Pack007 fixture " + relativePath, failure);
    }
  }

  private static PostgresAgentRunStore store() {
    return new PostgresAgentRunStore(
        dataSource,
        transactions,
        new PostgresArtifactLineageStore(dataSource, transactions),
        WORKER_PROFILE);
  }

  private static long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table)
        .query(Long.class)
        .single();
  }

  private static long handoffCount(String parentRunId) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_run_resource_bindings
            WHERE principal_id = :principalId
              AND run_id = :parentRunId
              AND role = 'HANDOFF'
            """)
        .param("principalId", PRINCIPAL)
        .param("parentRunId", parentRunId)
        .query(Long.class)
        .single();
  }

  private static DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private record RunningPair(
      PostgresAgentRunStore store,
      AgentRunContext context,
      AgentRun parent,
      AgentRun child) {}

  private record ParentCompletion(
      AgentRun terminal, ArtifactLineage artifact) {}
}
