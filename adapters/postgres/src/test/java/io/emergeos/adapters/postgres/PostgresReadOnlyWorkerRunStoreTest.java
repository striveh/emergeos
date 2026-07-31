package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class PostgresReadOnlyWorkerRunStoreTest {

  private static final String PRINCIPAL = "pack007-owner";
  private static final String CAPTURE_ID = "pack007-capture";
  private static final String CAPTURE_CONTENT =
      "Prompt 工程是在为概率程序构造运行时状态。";
  private static final String CAPTURE_SOURCE = "synthetic-pack-007";
  private static final Instant STARTED =
      Instant.parse("2026-07-31T03:30:00Z");
  private static final Instant CHILD_COMPLETED =
      Instant.parse("2026-07-31T03:30:01Z");
  private static final Instant PARENT_COMPLETED =
      Instant.parse("2026-07-31T03:30:02Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_worker_runs")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final JsonMapper JSON = JsonMapper.shared();
  private static final ReadOnlyWorkerExecutionProfile WORKER_PROFILE =
      ReadOnlyWorkerExecutionProfile.pack007FakeV1();

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
              agent_trace_events,
              agent_run_resource_bindings,
              agent_worker_results,
              agent_runs,
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
  void roundTripsExactParentChildResultAndArtifactAcrossFreshStores() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore first = store();
    AgentRun parentRunning = first.start(fixture.parentRunning());
    AgentRunContext parent = AgentRunContext.fromRunning(parentRunning);
    first.startWorker(parent, fixture.childRunning());

    var childCompletion =
        first.completeWorker(
            parent, fixture.childTerminal(), fixture.workerResult());

    assertEquals(fixture.childTerminal(), childCompletion.run());
    assertEquals(fixture.workerResult(), childCompletion.workerResult());
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store()
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow()
            .lifecycle());
    assertEquals(
        fixture.childTerminal(),
        store()
            .findWorkerOwned(parent, fixture.childRunning().runId())
            .orElseThrow()
            .run());
    assertEquals(0L, count("artifacts"));
    assertEquals(0L, parentHandoffCount());

    first.complete(fixture.parentTerminal(), fixture.artifact());

    PostgresAgentRunStore restarted = store();
    assertEquals(
        fixture.parentTerminal(),
        restarted
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow());
    assertEquals(
        fixture.childTerminal(),
        restarted
            .findOwned(PRINCIPAL, fixture.childRunning().runId())
            .orElseThrow());
    assertEquals(
        fixture.workerResult(),
        restarted
            .findWorkerOwned(parent, fixture.childRunning().runId())
            .orElseThrow()
            .workerResult());
    assertEquals(2L, count("agent_runs"));
    assertEquals(1L, count("agent_worker_results"));
    assertEquals(1L, count("artifacts"));
    assertEquals(1L, parentHandoffCount());
    assertEquals(0L, childArtifactBindingCount());
  }

  @Test
  void exactParentRunScopeRejectsSameTaskFromAnotherRun() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore store = store();
    AgentRun firstParent = store.start(fixture.parentRunning());
    AgentRun sameTaskOtherRun =
        store.start(
            AgentRun.running(
                "pack007-same-task-other-parent-run",
                PRINCIPAL,
                fixture.parentRunning().task(),
                STARTED));
    AgentRunContext exact = AgentRunContext.fromRunning(firstParent);
    AgentRunContext alias = AgentRunContext.fromRunning(sameTaskOtherRun);
    store.startWorker(exact, fixture.childRunning());
    store.completeWorker(
        exact, fixture.childTerminal(), fixture.workerResult());

    assertThrows(
        AgentRunIntegrityException.class,
        () ->
            store.findWorkerOwned(
                alias, fixture.childRunning().runId()));
    assertTrue(store.findWorkerOwned(exact, "missing-child-run").isEmpty());
    assertTrue(
        store
            .findWorkerOwned(
                new AgentRunContext(
                    "foreign-parent-run",
                    "foreign-owner",
                    foreignTask(fixture.parentRunning().task())),
                fixture.childRunning().runId())
            .isEmpty());
  }

  @Test
  void durableWorkerStartRejectsParentWithDirectToolAuthority() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore store = store();
    TaskEnvelope expandedParentTask =
        copyRequiredTools(
            fixture.parentRunning().task(), List.of("capture.read"));
    AgentRun expandedParent =
        AgentRun.running(
            fixture.parentRunning().runId(),
            PRINCIPAL,
            expandedParentTask,
            STARTED);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.start(expandedParent));
    assertEquals(0L, count("agent_runs"));
  }

  @Test
  void verifiedReadRejectsTamperedRunningWorkerParentToolAuthority() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore store = store();
    store.start(fixture.parentRunning());
    TaskEnvelope expandedParentTask =
        copyRequiredTools(
            fixture.parentRunning().task(), List.of("capture.read"));
    jdbc.sql(
            """
            UPDATE agent_runs
            SET task_envelope = CAST(:taskJson AS jsonb)
            WHERE principal_id = :principalId
              AND run_id = :runId
            """)
        .param("taskJson", JSON.writeValueAsString(expandedParentTask))
        .param("principalId", PRINCIPAL)
        .param("runId", fixture.parentRunning().runId())
        .update();

    assertThrows(
        AgentRunIntegrityException.class,
        () ->
            store.findOwned(
                PRINCIPAL, fixture.parentRunning().runId()));
  }

  @Test
  void workerResultFaultRollsBackResultTraceBindingsAndTerminalState() {
    Pack007 fixture = fixture();
    SyntheticWorkerFinalizationFailure failure =
        new SyntheticWorkerFinalizationFailure();
    PostgresAgentRunStore failing =
        new PostgresAgentRunStore(
            dataSource,
            transactions,
            new PostgresArtifactLineageStore(dataSource, transactions),
            WORKER_PROFILE,
            () -> {},
            () -> {
              throw failure;
            });
    AgentRun parentRunning = failing.start(fixture.parentRunning());
    AgentRunContext parent = AgentRunContext.fromRunning(parentRunning);
    failing.startWorker(parent, fixture.childRunning());

    SyntheticWorkerFinalizationFailure thrown =
        assertThrows(
            SyntheticWorkerFinalizationFailure.class,
            () ->
                failing.completeWorker(
                    parent,
                    fixture.childTerminal(),
                    fixture.workerResult()));

    assertEquals(failure, thrown);
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store()
            .findOwned(PRINCIPAL, fixture.childRunning().runId())
            .orElseThrow()
            .lifecycle());
    assertEquals(0L, count("agent_worker_results"));
    assertEquals(0L, count("agent_trace_events"));
    assertEquals(0L, count("agent_run_resource_bindings"));
  }

  @Test
  void lateWorkerFinalizationFaultRollsBackEveryChildWrite() {
    Pack007 fixture = fixture();
    SyntheticWorkerFinalizationFailure failure =
        new SyntheticWorkerFinalizationFailure();
    PostgresAgentRunStore failing =
        new PostgresAgentRunStore(
            dataSource,
            transactions,
            new PostgresArtifactLineageStore(dataSource, transactions),
            WORKER_PROFILE,
            () -> {},
            () -> {},
            () -> {
              throw failure;
            });
    AgentRun parentRunning = failing.start(fixture.parentRunning());
    AgentRunContext parent = AgentRunContext.fromRunning(parentRunning);
    failing.startWorker(parent, fixture.childRunning());

    SyntheticWorkerFinalizationFailure thrown =
        assertThrows(
            SyntheticWorkerFinalizationFailure.class,
            () ->
                failing.completeWorker(
                    parent,
                    fixture.childTerminal(),
                    fixture.workerResult()));

    assertEquals(failure, thrown);
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store()
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow()
            .lifecycle());
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store()
            .findOwned(PRINCIPAL, fixture.childRunning().runId())
            .orElseThrow()
            .lifecycle());
    assertEquals(0L, count("agent_worker_results"));
    assertEquals(0L, countForRun("agent_trace_events", fixture.childRunning().runId()));
    assertEquals(
        0L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.childRunning().runId()));
  }

  @Test
  void lateParentFinalizationFaultRollsBackParentOnlyAndPreservesCommittedChild() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore normal = store();
    AgentRun parentRunning = normal.start(fixture.parentRunning());
    AgentRunContext parent = AgentRunContext.fromRunning(parentRunning);
    normal.startWorker(parent, fixture.childRunning());
    normal.completeWorker(
        parent, fixture.childTerminal(), fixture.workerResult());

    SyntheticWorkerFinalizationFailure failure =
        new SyntheticWorkerFinalizationFailure();
    PostgresAgentRunStore failing =
        new PostgresAgentRunStore(
            dataSource,
            transactions,
            new PostgresArtifactLineageStore(dataSource, transactions),
            WORKER_PROFILE,
            () -> {},
            () -> {},
            () -> {
              throw failure;
            });

    SyntheticWorkerFinalizationFailure thrown =
        assertThrows(
            SyntheticWorkerFinalizationFailure.class,
            () -> failing.complete(fixture.parentTerminal(), fixture.artifact()));

    assertEquals(failure, thrown);
    PostgresAgentRunStore fresh = store();
    assertEquals(
        AgentRunLifecycle.RUNNING,
        fresh
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow()
            .lifecycle());
    assertEquals(
        fixture.childTerminal(),
        fresh
            .findOwned(PRINCIPAL, fixture.childRunning().runId())
            .orElseThrow());
    assertEquals(
        fixture.workerResult(),
        fresh
            .findWorkerOwned(parent, fixture.childRunning().runId())
            .orElseThrow()
            .workerResult());
    assertEquals(0L, count("artifacts"));
    assertEquals(0L, count("artifact_versions"));
    assertEquals(
        0L,
        countForRun(
            "agent_trace_events",
            fixture.parentRunning().runId()));
    assertEquals(
        0L,
        countForRun(
            "agent_run_resource_bindings",
            fixture.parentRunning().runId()));
    assertTrue(
        countForRun(
                "agent_trace_events",
                fixture.childRunning().runId())
            > 0);
    assertTrue(
        countForRun(
                "agent_run_resource_bindings",
                fixture.childRunning().runId())
            > 0);
    assertEquals(1L, count("agent_worker_results"));
  }

  @Test
  void roundTripsRepresentativeFailedChildAndParentWithoutAcceptedWorkerResult() {
    Pack007 fixture = fixture();
    AgentRun failedChild = failedChild(fixture);
    AgentRun failedParent = failedParent(fixture, failedChild);
    PostgresAgentRunStore first = store();
    AgentRunContext parent =
        AgentRunContext.fromRunning(first.start(fixture.parentRunning()));
    first.startWorker(parent, fixture.childRunning());

    var childCompletion =
        first.completeWorker(parent, failedChild, null);
    first.complete(failedParent, null);

    assertNull(childCompletion.workerResult());
    PostgresAgentRunStore fresh = store();
    assertEquals(
        failedChild,
        fresh
            .findOwned(PRINCIPAL, fixture.childRunning().runId())
            .orElseThrow());
    assertEquals(
        failedParent,
        fresh
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow());
    assertNull(
        fresh
            .findWorkerOwned(parent, fixture.childRunning().runId())
            .orElseThrow()
            .workerResult());
    assertEquals(0L, count("agent_worker_results"));
    assertEquals(0L, count("artifacts"));
    assertEquals(0L, count("artifact_versions"));
    assertEquals(1L, parentHandoffCount());
    assertEquals(0L, childWorkerResultBindingCount());
  }

  @Test
  void parentCannotCommitArtifactBeforeItsExactChildIsTerminal() {
    Pack007 fixture = fixture();
    PostgresAgentRunStore store = store();
    AgentRun parentRunning = store.start(fixture.parentRunning());
    AgentRunContext parent = AgentRunContext.fromRunning(parentRunning);
    store.startWorker(parent, fixture.childRunning());

    assertThrows(
        IllegalArgumentException.class,
        () -> store.complete(fixture.parentTerminal(), fixture.artifact()));

    assertEquals(0L, count("artifacts"));
    assertEquals(
        AgentRunLifecycle.RUNNING,
        store
            .findOwned(PRINCIPAL, fixture.parentRunning().runId())
            .orElseThrow()
            .lifecycle());
  }

  @Test
  void exactlyOneConcurrentChildStartWinsForOneParent() throws Exception {
    Pack007 fixture = fixture();
    PostgresAgentRunStore store = store();
    AgentRunContext parent =
        AgentRunContext.fromRunning(store.start(fixture.parentRunning()));
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            WORKER_PROFILE.workerName(),
            "产出一篇引用该 Capture 的短文 proposal",
            parent.task().inputRefs());
    AgentWorkerRuntime.ExecutionWindow window =
        new AgentWorkerRuntime.ExecutionWindow(
            parent.task().deadlineMs(),
            parent.task().budgetUsd(),
            CancellationSignal.never());
    TaskEnvelope firstTask =
        WORKER_PROFILE.newChildTask(
            parent.task(), request, window, "pack007-child-a-task");
    TaskEnvelope secondTask =
        WORKER_PROFILE.newChildTask(
            parent.task(), request, window, "pack007-child-b-task");
    AgentRun first =
        AgentRun.running(
            "pack007-child-a-run", PRINCIPAL, firstTask, STARTED);
    AgentRun second =
        AgentRun.running(
            "pack007-child-b-run", PRINCIPAL, secondTask, STARTED);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<String> firstContender =
          () -> startChild(store, parent, first, ready, start);
      java.util.concurrent.Callable<String> secondContender =
          () -> startChild(store, parent, second, ready, start);
      Future<String> firstResult = executor.submit(firstContender);
      Future<String> secondResult = executor.submit(secondContender);
      ready.await();
      start.countDown();

      assertEquals(
          Set.of("started", "conflict"),
          Set.of(firstResult.get(), secondResult.get()));
      assertEquals(
          1L,
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM agent_runs
                  WHERE principal_id = :principalId
                    AND parent_run_id = :parentRunId
                  """)
              .param("principalId", PRINCIPAL)
              .param("parentRunId", parent.runId())
              .query(Long.class)
              .single());
    } finally {
      executor.shutdownNow();
    }
  }

  private static String startChild(
      PostgresAgentRunStore store,
      AgentRunContext parent,
      AgentRun child,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      store.startWorker(parent, child);
      return "started";
    } catch (AgentRunConflictException expected) {
      return "conflict";
    }
  }

  private static Pack007 fixture() {
    TaskEnvelope parentTask =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-parent.json",
            TaskEnvelope.class);
    TaskEnvelope childTask =
        readFixture(
            "contracts/fixtures/v1/task-envelope/valid-pack007-child.json",
            TaskEnvelope.class);
    AgentTraceEnvelope parentTrace =
        readFixture(
            "contracts/fixtures/v1/agent-trace-envelope/"
                + "valid-pack007-parent-handoff.json",
            AgentTraceEnvelope.class);
    AgentTraceEnvelope childTrace =
        readFixture(
            "contracts/fixtures/v1/agent-trace-envelope/"
                + "valid-pack007-child-proposal.json",
            AgentTraceEnvelope.class);
    HarnessRunBundle parentBundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-pack007-parent.json",
            HarnessRunBundle.class);
    HarnessRunBundle childBundle =
        readFixture(
            "contracts/fixtures/v1/harness-run-bundle/"
                + "valid-pack007-child.json",
            HarnessRunBundle.class);
    WorkerResultEnvelope workerResult =
        readFixture(
            "contracts/fixtures/v1/worker-result-envelope/valid.json",
            WorkerResultEnvelope.class);
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-postgres-nonce",
            CaptureRequestHashes.sha256(
                CAPTURE_CONTENT,
                CaptureSourceType.TEXT,
                CAPTURE_SOURCE,
                DataClass.PUBLIC),
            CAPTURE_CONTENT,
            CaptureSourceType.TEXT,
            CAPTURE_SOURCE,
            DataClass.PUBLIC,
            STARTED);
    new PostgresCaptureStore(dataSource, transactions)
        .saveOrFindByNonce(capture);

    AgentRun parentRunning =
        AgentRun.running(
            parentBundle.runId(), PRINCIPAL, parentTask, STARTED);
    AgentRun childRunning =
        AgentRun.running(
            childBundle.runId(), PRINCIPAL, childTask, STARTED);
    AgentRun childTerminal =
        new AgentRun(
            childBundle.runId(),
            PRINCIPAL,
            childTask,
            AgentRunLifecycle.SUCCEEDED,
            childBundle.result(),
            childTrace,
            childBundle,
            STARTED,
            CHILD_COMPLETED);
    AgentRun parentTerminal =
        new AgentRun(
            parentBundle.runId(),
            PRINCIPAL,
            parentTask,
            AgentRunLifecycle.SUCCEEDED,
            parentBundle.result(),
            parentTrace,
            parentBundle,
            STARTED,
            PARENT_COMPLETED);
    ArtifactLineage artifact =
        new ArtifactLineage(
            "pack007-parent-artifact",
            PRINCIPAL,
            CAPTURE_ID,
            List.of(
                new ArtifactLineageEntry(
                    1,
                    workerResult.content(),
                    ContentHashes.sha256(workerResult.content()),
                    null,
                    null,
                    PARENT_COMPLETED)));
    return new Pack007(
        parentRunning,
        childRunning,
        childTerminal,
        parentTerminal,
        workerResult,
        artifact);
  }

  private static AgentRun failedChild(Pack007 fixture) {
    TaskEnvelope task = fixture.childRunning().task();
    String runId = fixture.childRunning().runId();
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    AgentTraceEnvelope trace =
        trace(
            runId,
            task.id(),
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    "FAILED",
                    "task://" + task.id())));
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
            "fake-worker-model-v1",
            WORKER_PROFILE.agentVersion(),
            WORKER_PROFILE.verifierVersion(),
            BigDecimal.ZERO,
            0,
            1,
            traceRef,
            "AGENT_KERNEL_FAILED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            null,
            result.resolvedModel(),
            WORKER_PROFILE.harnessVersion(),
            WORKER_PROFILE.componentVersions(),
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
        CHILD_COMPLETED);
  }

  private static AgentRun failedParent(Pack007 fixture, AgentRun child) {
    TaskEnvelope task = fixture.parentRunning().task();
    String runId = fixture.parentRunning().runId();
    String childRef = "agent-run://" + child.runId();
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    AgentTraceEnvelope trace =
        trace(
            runId,
            task.id(),
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    "COMPLETED",
                    "task://" + task.id()),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    "REQUESTED",
                    childRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_REJECTED,
                    "CHILD_FAILED",
                    childRef)));
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
            "fake-model-v1",
            fixture.parentTerminal().result().agentVersion(),
            fixture.parentTerminal().result().verifierVersion(),
            BigDecimal.ZERO,
            0,
            1,
            traceRef,
            "HANDOFF_CHILD_FAILED");
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.HANDOFF,
                0,
                childRef,
                child.bundle().integrityHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            null,
            result.resolvedModel(),
            fixture.parentTerminal().bundle().harnessVersion(),
            fixture.parentTerminal().bundle().componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            bindings,
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
        PARENT_COMPLETED);
  }

  private static AgentTraceEnvelope trace(
      String runId, String taskId, List<TraceSpec> specs) {
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    for (TraceSpec spec : specs) {
      AgentTraceEntry event =
          AgentTraceEntry.create(
              events.size() + 1,
              spec.type(),
              null,
              spec.status(),
              spec.reference(),
              root);
      events.add(event);
      root = IntegrityHashes.nextTraceRoot(root, event.eventHash());
    }
    return AgentTraceEnvelope.create("1.0", runId, taskId, events);
  }

  private static TaskEnvelope foreignTask(TaskEnvelope task) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        "foreign-owner",
        task.delegationChain(),
        task.kind(),
        task.intent(),
        task.inputRefs(),
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        task.requiredTools(),
        task.outputSchema(),
        task.acceptanceChecks(),
        task.allowParallel(),
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        task.contextPolicyVersion(),
        task.toolRegistryVersion(),
        task.environmentSnapshotRef(),
        task.capabilityRefs(),
        task.unresolvedDecisions(),
            task.returnControlWhen());
  }

  private static TaskEnvelope copyRequiredTools(
      TaskEnvelope task, List<String> requiredTools) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        task.principalRef(),
        task.delegationChain(),
        task.kind(),
        task.intent(),
        task.inputRefs(),
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        requiredTools,
        task.outputSchema(),
        task.acceptanceChecks(),
        task.allowParallel(),
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        task.contextPolicyVersion(),
        task.toolRegistryVersion(),
        task.environmentSnapshotRef(),
        task.capabilityRefs(),
        task.unresolvedDecisions(),
        task.returnControlWhen());
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

  private static long countForRun(String table, String runId) {
    return jdbc.sql(
            "SELECT count(*) FROM "
                + table
                + " WHERE principal_id = :principalId AND run_id = :runId")
        .param("principalId", PRINCIPAL)
        .param("runId", runId)
        .query(Long.class)
        .single();
  }

  private static long parentHandoffCount() {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_run_resource_bindings
            WHERE principal_id = :principalId
              AND run_id = 'pack007-parent-run'
              AND role = 'HANDOFF'
            """)
        .param("principalId", PRINCIPAL)
        .query(Long.class)
        .single();
  }

  private static long childArtifactBindingCount() {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_run_resource_bindings
            WHERE principal_id = :principalId
              AND run_id = 'pack007-child-run'
              AND role = 'ARTIFACT'
            """)
        .param("principalId", PRINCIPAL)
        .query(Long.class)
        .single();
  }

  private static long childWorkerResultBindingCount() {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_run_resource_bindings
            WHERE principal_id = :principalId
              AND run_id = 'pack007-child-run'
              AND role = 'WORKER_RESULT'
            """)
        .param("principalId", PRINCIPAL)
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

  private record Pack007(
      AgentRun parentRunning,
      AgentRun childRunning,
      AgentRun childTerminal,
      AgentRun parentTerminal,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact) {}

  private record TraceSpec(
      TraceEventType type, String status, String reference) {}

  private static final class SyntheticWorkerFinalizationFailure
      extends RuntimeException {}
}
