package io.emergeos.adapters.inmemory.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentTool;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.DurableReadOnlyWorkerService;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerHandoffVerifier;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.ReadOnlyWorkerRunStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Runnable Pack 007 deterministic product-path acceptance and fault suite. */
class OfflineReadOnlyWorkerHandoffTest {

  private static final String PRINCIPAL = "pack007-owner";
  private static final String CAPTURE_ID = "pack007-capture";
  private static final String CAPTURE_REF = "capture://" + CAPTURE_ID;
  private static final Instant NOW = Instant.parse("2026-07-31T03:30:00Z");

  @Test
  void oneReadOnlyWorkerProducesDurableOutputAndOnlyParentCommitsArtifact() {
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-nonce",
            CaptureRequestHashes.sha256(
                "Prompt 工程是在为概率程序构造运行时状态。",
                CaptureSourceType.TEXT,
                "synthetic-pack-007",
                DataClass.PUBLIC),
            "Prompt 工程是在为概率程序构造运行时状态。",
            CaptureSourceType.TEXT,
            "synthetic-pack-007",
            DataClass.PUBLIC,
            NOW);
    var captures = new RecordingCaptureStore(capture);
    var runs = new RecordingRunStore();
    var model = new ConductorModel();
    var profile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var captureRead = new CountingCaptureReadTool(captures);
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var ids =
        new FrozenIds(
            Map.of(
                "run", List.of("pack007-parent-run", "pack007-child-run"),
                "task", List.of("pack007-parent-task", "pack007-child-task"),
                "art", List.of("pack007-parent-artifact")));
    var workerKernel =
        new AgentLoopKernel(
            new ProposalWorkerModel(),
            new AgentToolRegistry(
                profile.toolRegistryVersion(), List.of(captureRead)),
            workerProfile.maxModelSteps(),
            workerProfile.maxToolCalls(),
            new FrozenNanoTime());
    var workers =
        new DurableReadOnlyWorkerService(
            workerKernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            workerProfile);
    var kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(
                profile.toolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            workers,
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            new FrozenNanoTime());
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            profile);
    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL,
                CAPTURE_ID,
                "把这个想法整理成一篇有证据的短文"));

    long handoffBindings =
        outcome.run().bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.HANDOFF)
            .count();
    long childArtifacts =
        runs.terminals().stream()
            .filter(run -> run.task().parentId() != null)
            .flatMap(run -> run.result().artifactRefs().stream())
            .count();
    AgentRun child =
        runs.terminals().stream()
            .filter(run -> run.task().parentId() != null)
            .findFirst()
            .orElseThrow();
    var workerResult = runs.durableWorkerResults.get(child.runId());

    assertAll(
        () -> assertEquals(RunStatus.SUCCEEDED, outcome.run().result().status()),
        () -> assertEquals(2, runs.startCount()),
        () -> assertEquals(2, runs.completeCount()),
        () -> assertEquals(2, runs.terminals().size()),
        () -> assertEquals(1, captureRead.executeCount()),
        () -> assertEquals(1, handoffBindings),
        () -> assertEquals(1, runs.workerResultCount()),
        () -> assertEquals(1, runs.workerReadCount()),
        () -> assertEquals(1, runs.pairVerificationCount()),
        () ->
            assertEquals(
                List.of(
                    "pack007-parent-run",
                    "pack007-parent-run",
                    "pack007-parent-run"),
                runs.workerParentRunIds()),
        () -> assertEquals(0, childArtifacts),
        () -> assertEquals(1, runs.artifactCount()),
        () -> assertNotNull(outcome.artifact()),
        () ->
            assertEquals(
                List.of(
                    TraceEventType.MODEL_STEP,
                    TraceEventType.HANDOFF_REQUEST,
                    TraceEventType.HANDOFF_RESULT,
                    TraceEventType.MODEL_STEP,
                    TraceEventType.STRUCTURED_FINAL,
                    TraceEventType.ARTIFACT_COMMITTED),
                outcome.run().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () ->
            assertEquals(
                List.of(
                    TraceEventType.MODEL_STEP,
                    TraceEventType.TOOL_REQUEST,
                    TraceEventType.TOOL_RESULT,
                    TraceEventType.MODEL_STEP,
                    TraceEventType.STRUCTURED_FINAL),
                child.trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () ->
            assertEquals(
                "d1dfaa518503963bb71c4401aa51e23000fb2f81b35892b29fb09e01229852e8",
                IntegrityHashes.taskHash(outcome.run().task())),
        () ->
            assertEquals(
                "f18aeb3d9c5b58929f22c55ead9e71f5aa6f74c5708fddfc413f3ffee94cfbd3",
                IntegrityHashes.taskHash(child.task())),
        () ->
            assertEquals(
                "eba203ca7e61046363308f297ab94e509d82badf70c601ce1136de2071b3faef",
                outcome.run().trace().rootHash()),
        () ->
            assertEquals(
                "ea07ac77589c9f71fcae95a5e0e52e16bbdca00343c48ecd70fea7257a5af9f5",
                child.trace().rootHash()),
        () ->
            assertEquals(
                "fa3d7840adfc0333dbd071db4229af0664654b50f17b2f1c8d6f6e03eb24efbe",
                outcome.run().bundle().integrityHash()),
        () ->
            assertEquals(
                "c12b2771c506bf823139af860396ddc8736bb81a1f0bbd9b09a4138d83cd3e0a",
                child.bundle().integrityHash()),
        () ->
            assertEquals(
                "d69ecb08300f1f1cd378d826243add922c0deb75a15d9393a63d5d00d2a2f5a8",
                workerResult.integrityHash()),
        () ->
            assertEquals(
                "e5b6493d7741cf43b65fb45c29d0351f3d038e470df31f964e6f1fa9cc8f767b",
                workerResult.contentHash()));

    AgentRun sameTaskDifferentRun =
        runs.start(
            AgentRun.running(
                "pack007-same-task-other-parent-run",
                PRINCIPAL,
                outcome.run().task(),
                NOW));
    AgentRunContext wrongParent =
        AgentRunContext.fromRunning(sameTaskDifferentRun);
    assertThrows(
        IllegalStateException.class,
        () -> runs.findWorkerOwned(wrongParent, child.runId()));
  }

  @Test
  void committedWorkerSurvivesLostCompletionAcknowledgement() {
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-ack-loss-nonce",
            CaptureRequestHashes.sha256(
                "Prompt 工程是在为概率程序构造运行时状态。",
                CaptureSourceType.TEXT,
                "synthetic-pack-007-ack-loss",
                DataClass.PUBLIC),
            "Prompt 工程是在为概率程序构造运行时状态。",
            CaptureSourceType.TEXT,
            "synthetic-pack-007-ack-loss",
            DataClass.PUBLIC,
            NOW);
    var captures = new RecordingCaptureStore(capture);
    var runs = new RecordingRunStore(true);
    var profile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var captureRead = new CountingCaptureReadTool(captures);
    var ids =
        new FrozenIds(
            Map.of(
                "run",
                List.of(
                    "pack007-ack-loss-parent-run",
                    "pack007-ack-loss-child-run"),
                "task",
                List.of(
                    "pack007-ack-loss-parent-task",
                    "pack007-ack-loss-child-task"),
                "art",
                List.of("pack007-ack-loss-parent-artifact")));
    var workerKernel =
        new AgentLoopKernel(
            new ProposalWorkerModel(),
            new AgentToolRegistry(
                profile.toolRegistryVersion(), List.of(captureRead)),
            workerProfile.maxModelSteps(),
            workerProfile.maxToolCalls(),
            new FrozenNanoTime());
    var workers =
        new DurableReadOnlyWorkerService(
            workerKernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            workerProfile);
    var kernel =
        new AgentLoopKernel(
            new ConductorModel(),
            new AgentToolRegistry(
                profile.toolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            workers,
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            new FrozenNanoTime());
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            profile);
    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL,
                CAPTURE_ID,
                "在 child commit acknowledgement 丢失后继续 verified read"));

    assertAll(
        () -> assertEquals(RunStatus.SUCCEEDED, outcome.result().status()),
        () -> assertEquals(2, runs.startCount()),
        () -> assertEquals(2, runs.completeCount()),
        () -> assertEquals(1, runs.workerResultCount()),
        () -> assertEquals(1, runs.workerReadCount()),
        () -> assertEquals(1, runs.pairVerificationCount()),
        () -> assertEquals(1, runs.artifactCount()),
        () -> assertNotNull(outcome.artifact()));
  }

  @Test
  void workerCompletionFailureBeforeCommitRethrowsTheOriginalFailure() {
    DirectWorkerFault scenario =
        directWorkerFault(
            WorkerPersistenceFault.FAIL_BEFORE_COMMIT,
            "precommit");

    RuntimeException failure =
        assertThrows(
            RuntimeException.class, scenario.prepared()::execute);

    assertSame(scenario.runs().workerPersistenceFailure(), failure);
    assertEquals(0, scenario.runs().workerResultCount());
    assertEquals(0, scenario.runs().workerReadCount());
  }

  @Test
  void acknowledgedCompletionWithoutAFreshReadFailsClosed() {
    DirectWorkerFault scenario =
        directWorkerFault(
            WorkerPersistenceFault.MISSING_AFTER_ACK,
            "missing-read");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, scenario.prepared()::execute);

    assertEquals(
        "committed Worker is not durably readable", failure.getMessage());
    assertEquals(1, scenario.runs().workerResultCount());
    assertEquals(0, scenario.runs().workerReadCount());
  }

  @Test
  void lostAcknowledgementWithTamperedFreshReadFailsClosedAndKeepsTheAckFault() {
    DirectWorkerFault scenario =
        directWorkerFault(
            WorkerPersistenceFault.TAMPERED_AFTER_ACK_LOSS,
            "tampered-read");

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, scenario.prepared()::execute);

    assertTrue(
        failure
            .getMessage()
            .contains("Worker Result is not hash-bound"));
    assertEquals(1, failure.getSuppressed().length);
    assertSame(
        scenario.runs().workerPersistenceFailure(),
        failure.getSuppressed()[0]);
    assertEquals(1, scenario.runs().workerReadCount());
  }

  @Test
  void tamperedFreshReadCannotReachTheParentModelOrCommitAnArtifact() {
    ParentWorkerFault scenario =
        parentWorkerFault(
            WorkerPersistenceFault.TAMPERED_AFTER_ACK_LOSS,
            "tampered-parent");

    AgentDraftOutcome outcome = scenario.outcome();
    long acceptedBindings =
        outcome.run().bundle().resourceBindings().stream()
            .filter(
                binding ->
                    binding.role() == ResourceRole.HANDOFF
                        || binding.role() == ResourceRole.EVIDENCE)
            .count();

    assertAll(
        () -> assertEquals(RunStatus.FAILED, outcome.result().status()),
        () ->
            assertEquals(
                "HANDOFF_DISPATCH_FAILED",
                outcome.result().failureReason()),
        () ->
            assertEquals(
                List.of(
                    TraceEventType.MODEL_STEP,
                    TraceEventType.HANDOFF_REQUEST,
                    TraceEventType.HANDOFF_REJECTED),
                outcome.run().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () -> assertEquals(1, scenario.model().callCount()),
        () -> assertEquals(2, scenario.runs().startCount()),
        () -> assertEquals(2, scenario.runs().completeCount()),
        () -> assertEquals(1, scenario.runs().workerResultCount()),
        () -> assertEquals(1, scenario.runs().workerReadCount()),
        () -> assertEquals(0, scenario.runs().artifactCount()),
        () -> assertEquals(0, acceptedBindings),
        () -> assertEquals(List.of(), outcome.run().bundle().handoffRefs()),
        () -> assertEquals(null, outcome.artifact()));
  }

  @Test
  void precommitWorkerFailureCannotReachTheParentModelOrCommitAnArtifact() {
    ParentWorkerFault scenario =
        parentWorkerFault(
            WorkerPersistenceFault.FAIL_BEFORE_COMMIT,
            "precommit-parent");

    AgentDraftOutcome outcome = scenario.outcome();

    assertAll(
        () -> assertEquals(RunStatus.FAILED, outcome.result().status()),
        () ->
            assertEquals(
                "HANDOFF_DISPATCH_FAILED",
                outcome.result().failureReason()),
        () ->
            assertEquals(
                List.of(
                    TraceEventType.MODEL_STEP,
                    TraceEventType.HANDOFF_REQUEST,
                    TraceEventType.HANDOFF_REJECTED),
                outcome.run().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () -> assertEquals(1, scenario.model().callCount()),
        () -> assertEquals(2, scenario.runs().startCount()),
        () -> assertEquals(1, scenario.runs().completeCount()),
        () -> assertEquals(0, scenario.runs().workerResultCount()),
        () -> assertEquals(0, scenario.runs().workerReadCount()),
        () -> assertEquals(0, scenario.runs().artifactCount()),
        () -> assertEquals(List.of(), outcome.run().bundle().handoffRefs()),
        () -> assertEquals(null, outcome.artifact()));
  }

  @Test
  void contextPolicyDriftBlocksBeforeAnyChildRunModelToolOrArtifact() {
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-drift-nonce",
            CaptureRequestHashes.sha256(
                "synthetic drift control",
                CaptureSourceType.TEXT,
                "synthetic-pack-007-drift",
                DataClass.PUBLIC),
            "synthetic drift control",
            CaptureSourceType.TEXT,
            "synthetic-pack-007-drift",
            DataClass.PUBLIC,
            NOW);
    var captures = new RecordingCaptureStore(capture);
    var runs = new RecordingRunStore();
    var profile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var workers = new ContextDriftRuntime(workerProfile);
    var kernel =
        new AgentLoopKernel(
            new ConductorModel(),
            new AgentToolRegistry(
                profile.toolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            workers,
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            new FrozenNanoTime());
    var ids =
        new FrozenIds(
            Map.of(
                "run", List.of("pack007-drift-parent-run"),
                "task", List.of("pack007-drift-parent-task")));
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            profile);
    workers.install(
        ReadOnlyWorkerExecutionProfile.pack007FakeV1(
            "ref-only-v2"));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL,
                CAPTURE_ID,
                "尝试委派一个 context policy 已漂移的 Worker"));

    assertAll(
        () -> assertEquals(RunStatus.BLOCKED, outcome.run().result().status()),
        () ->
            assertEquals(
                "HANDOFF_CONTEXT_POLICY_DRIFT",
                outcome.run().result().failureReason()),
        () ->
            assertEquals(
                List.of(
                    TraceEventType.MODEL_STEP,
                    TraceEventType.HANDOFF_REJECTED),
                outcome.run().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () -> assertEquals(1, workers.prepareCount()),
        () -> assertEquals(1, runs.startCount()),
        () -> assertEquals(1, runs.completeCount()),
        () -> assertEquals(0, runs.workerResultCount()),
        () -> assertEquals(0, runs.artifactCount()),
        () -> assertEquals(List.of(), outcome.run().bundle().handoffRefs()),
        () -> assertEquals(null, outcome.artifact()));
  }

  private static DirectWorkerFault directWorkerFault(
      WorkerPersistenceFault fault, String suffix) {
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-" + suffix + "-nonce",
            CaptureRequestHashes.sha256(
                "Prompt 工程是在为概率程序构造运行时状态。",
                CaptureSourceType.TEXT,
                "synthetic-pack-007-" + suffix,
                DataClass.PUBLIC),
            "Prompt 工程是在为概率程序构造运行时状态。",
            CaptureSourceType.TEXT,
            "synthetic-pack-007-" + suffix,
            DataClass.PUBLIC,
            NOW);
    var captures = new RecordingCaptureStore(capture);
    var runs = new RecordingRunStore(fault);
    var parentProfile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var captureRead = new CountingCaptureReadTool(captures);
    var ids =
        new FrozenIds(
            Map.of(
                "run", List.of("pack007-" + suffix + "-child-run"),
                "task", List.of("pack007-" + suffix + "-child-task")));
    var workerKernel =
        new AgentLoopKernel(
            new ProposalWorkerModel(),
            new AgentToolRegistry(
                parentProfile.toolRegistryVersion(), List.of(captureRead)),
            workerProfile.maxModelSteps(),
            workerProfile.maxToolCalls(),
            new FrozenNanoTime());
    var workers =
        new DurableReadOnlyWorkerService(
            workerKernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            workerProfile);
    var parentTask =
        parentProfile.newDraftTask(
            "pack007-" + suffix + "-parent-task",
            PRINCIPAL,
            "验证 Worker persistence fault",
            CAPTURE_REF,
            DataClass.PUBLIC);
    AgentRun parent =
        runs.start(
            AgentRun.running(
                "pack007-" + suffix + "-parent-run",
                PRINCIPAL,
                parentTask,
                NOW));
    AgentRunContext parentContext =
        AgentRunContext.fromRunning(parent);
    AgentWorkerRuntime.Preparation preparation =
        workers.prepare(
            parentContext,
            new WorkerHandoffRequest(
                workerProfile.workerName(),
                "产出一篇引用该 Capture 的短文 proposal",
                List.of(CAPTURE_REF)),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                BigDecimal.ZERO,
                io.emergeos.core.port.CancellationSignal.never()));
    if (!preparation.accepted()) {
      throw new IllegalStateException(
          "synthetic Worker fault scenario was rejected before dispatch");
    }
    return new DirectWorkerFault(runs, preparation.prepared());
  }

  private static ParentWorkerFault parentWorkerFault(
      WorkerPersistenceFault fault, String suffix) {
    Capture capture =
        new Capture(
            CAPTURE_ID,
            PRINCIPAL,
            "pack007-" + suffix + "-nonce",
            CaptureRequestHashes.sha256(
                "Prompt 工程是在为概率程序构造运行时状态。",
                CaptureSourceType.TEXT,
                "synthetic-pack-007-" + suffix,
                DataClass.PUBLIC),
            "Prompt 工程是在为概率程序构造运行时状态。",
            CaptureSourceType.TEXT,
            "synthetic-pack-007-" + suffix,
            DataClass.PUBLIC,
            NOW);
    var captures = new RecordingCaptureStore(capture);
    var runs = new RecordingRunStore(fault);
    var model = new ConductorModel();
    var profile = AgentExecutionProfile.readOnlyWorkerFakeV1();
    var workerProfile = ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    var ids =
        new FrozenIds(
            Map.of(
                "run",
                List.of(
                    "pack007-" + suffix + "-parent-run",
                    "pack007-" + suffix + "-child-run"),
                "task",
                List.of(
                    "pack007-" + suffix + "-parent-task",
                    "pack007-" + suffix + "-child-task")));
    var workerKernel =
        new AgentLoopKernel(
            new ProposalWorkerModel(),
            new AgentToolRegistry(
                profile.toolRegistryVersion(),
                List.of(new CountingCaptureReadTool(captures))),
            workerProfile.maxModelSteps(),
            workerProfile.maxToolCalls(),
            new FrozenNanoTime());
    var workers =
        new DurableReadOnlyWorkerService(
            workerKernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            workerProfile);
    var kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(
                profile.toolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            workers,
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            new FrozenNanoTime());
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(NOW, ZoneOffset.UTC),
            profile);
    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL,
                CAPTURE_ID,
                "验证 Worker persistence fault 不会越过 parent boundary"));
    return new ParentWorkerFault(runs, model, outcome);
  }

  private static final class ConductorModel implements AgentModel {

    private final AtomicInteger calls = new AtomicInteger();
    private final AgentModel delegate =
        ScriptedFakeModel.forReadOnlyWorkerDraft();

    @Override
    public Session open(io.emergeos.contracts.TaskEnvelope task) {
      Session session = delegate.open(task);
      return (turn, context) -> {
        calls.incrementAndGet();
        return session.next(turn, context);
      };
    }

    private int callCount() {
      return calls.get();
    }
  }

  private static final class ProposalWorkerModel implements AgentModel {

    private final AgentModel delegate =
        ScriptedFakeModel.forReadOnlyWorkerProposal();

    @Override
    public Session open(io.emergeos.contracts.TaskEnvelope task) {
      return delegate.open(task);
    }
  }

  private static final class ContextDriftRuntime
      implements AgentWorkerRuntime {

    private ReadOnlyWorkerExecutionProfile profile;
    private int preparations;

    private ContextDriftRuntime(
        ReadOnlyWorkerExecutionProfile profile) {
      this.profile = profile;
    }

    @Override
    public String registryVersion() {
      return profile.registryVersion();
    }

    @Override
    public String profileFingerprint() {
      return profile.fingerprint();
    }

    @Override
    public Preparation prepare(
        AgentRunContext parent,
        io.emergeos.core.domain.WorkerHandoffRequest request,
        ExecutionWindow window) {
      preparations++;
      String rejection =
          profile.validatePreparation(parent.task(), request, window);
      if (rejection == null) {
        throw new AssertionError(
            "synthetic dynamic registry did not drift");
      }
      return Preparation.rejected(RunStatus.BLOCKED, rejection);
    }

    private void install(
        ReadOnlyWorkerExecutionProfile replacement) {
      this.profile = replacement;
    }

    private int prepareCount() {
      return preparations;
    }
  }

  private static final class CountingCaptureReadTool
      implements AgentTool<CaptureReadTool.Arguments> {

    private final CaptureReadTool delegate;
    private int executions;

    private CountingCaptureReadTool(CaptureStore captures) {
      this.delegate = new CaptureReadTool(captures);
    }

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public String argumentSchemaId() {
      return delegate.argumentSchemaId();
    }

    @Override
    public Validation<CaptureReadTool.Arguments> validate(
        io.emergeos.contracts.TaskEnvelope task, AgentModel.ToolCall call) {
      return delegate.validate(task, call);
    }

    @Override
    public AgentModel.ToolResult execute(
        io.emergeos.contracts.TaskEnvelope task,
        CaptureReadTool.Arguments arguments) {
      executions++;
      return delegate.execute(task, arguments);
    }

    private int executeCount() {
      return executions;
    }
  }

  private static final class RecordingCaptureStore implements CaptureStore {

    private final Capture capture;
    private int reads;

    private RecordingCaptureStore(Capture capture) {
      this.capture = capture;
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException("Pack 007 Capture is frozen");
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      if (!capture.principalId().equals(principalId)
          || !capture.captureId().equals(captureId)) {
        return Optional.empty();
      }
      reads++;
      return Optional.of(capture);
    }

  }

  private static final class RecordingRunStore
      implements AgentRunStore, ReadOnlyWorkerRunStore {

    private final Map<String, AgentRun> stored = new LinkedHashMap<>();
    private final Map<String, AgentRunContext> workerParents =
        new LinkedHashMap<>();
    private final List<String> workerParentRunIds = new ArrayList<>();
    private final Map<String, io.emergeos.contracts.WorkerResultEnvelope>
        durableWorkerResults = new LinkedHashMap<>();
    private final List<AgentRun> terminals = new ArrayList<>();
    private int starts;
    private int completions;
    private int artifacts;
    private int workerResults;
    private int workerReads;
    private int pairVerifications;
    private final WorkerPersistenceFault workerPersistenceFault;
    private final RuntimeException workerPersistenceFailure;

    private RecordingRunStore() {
      this(WorkerPersistenceFault.NONE);
    }

    private RecordingRunStore(
        boolean loseWorkerCompletionAcknowledgement) {
      this(
          loseWorkerCompletionAcknowledgement
              ? WorkerPersistenceFault.ACK_LOST_AFTER_COMMIT
              : WorkerPersistenceFault.NONE);
    }

    private RecordingRunStore(
        WorkerPersistenceFault workerPersistenceFault) {
      this.workerPersistenceFault = workerPersistenceFault;
      this.workerPersistenceFailure =
          new IllegalStateException(
              "synthetic Worker persistence fault: "
                  + workerPersistenceFault);
    }

    @Override
    public AgentRun start(AgentRun running) {
      if (running.lifecycle() != AgentRunLifecycle.RUNNING
          || stored.putIfAbsent(running.runId(), running) != null) {
        throw new IllegalStateException("Pack 007 Run start is invalid");
      }
      starts++;
      return running;
    }

    @Override
    public CompletionResult complete(
        AgentRun terminal, ArtifactLineage proposedArtifact) {
      AgentRun running = stored.get(terminal.runId());
      if (running == null
          || running.lifecycle() != AgentRunLifecycle.RUNNING
          || !terminal.lifecycle().terminal()
          || !running.task().equals(terminal.task())) {
        throw new IllegalStateException("Pack 007 terminal transition is invalid");
      }
      List<io.emergeos.contracts.ResourceBinding> handoffs =
          terminal.bundle().resourceBindings().stream()
              .filter(binding -> binding.role() == ResourceRole.HANDOFF)
              .toList();
      if (!handoffs.isEmpty()) {
        String childRunId =
            handoffs
                .getFirst()
                .ref()
                .substring("agent-run://".length());
        AgentRun child = stored.get(childRunId);
        io.emergeos.contracts.WorkerResultEnvelope workerResult =
            durableWorkerResults.get(childRunId);
        AgentRunContext exactParent = workerParents.get(childRunId);
        if (exactParent == null
            || !exactParent.runId().equals(terminal.runId())
            || !exactParent.principalId().equals(terminal.principalId())
            || !exactParent.task().equals(terminal.task())) {
          throw new IllegalStateException(
              "Pack 007 Handoff binds a child from another parent Run");
        }
        ReadOnlyWorkerHandoffVerifier.verifyPair(
            terminal,
            child,
            workerResult,
            ReadOnlyWorkerExecutionProfile.pack007FakeV1(
                terminal.task().contextPolicyVersion()));
        pairVerifications++;
      }
      stored.put(terminal.runId(), terminal);
      terminals.add(terminal);
      completions++;
      if (proposedArtifact != null) {
        artifacts++;
      }
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public AgentRun startWorker(
        AgentRunContext parent,
        AgentRun running) {
      AgentRun durableParent = stored.get(parent.runId());
      if (durableParent == null
          || durableParent.lifecycle() != AgentRunLifecycle.RUNNING
          || !durableParent.principalId().equals(parent.principalId())
          || !durableParent.task().equals(parent.task())
          || !parent.principalId().equals(running.principalId())
          || !parent.task().id().equals(running.task().parentId())) {
        throw new IllegalStateException(
            "Pack 007 Worker parent binding is invalid");
      }
      workerParentRunIds.add(parent.runId());
      workerParents.put(running.runId(), parent);
      return start(running);
    }

    @Override
    public WorkerCompletion completeWorker(
        AgentRunContext parent,
        AgentRun terminal,
        io.emergeos.contracts.WorkerResultEnvelope workerResult) {
      AgentRun running = stored.get(terminal.runId());
      if (running == null
          || running.lifecycle() != AgentRunLifecycle.RUNNING
          || !terminal.lifecycle().terminal()
          || !running.task().equals(terminal.task())
          || !parent.equals(workerParents.get(terminal.runId()))) {
        throw new IllegalStateException(
            "Pack 007 Worker terminal transition is invalid");
      }
      if (workerPersistenceFault
          == WorkerPersistenceFault.FAIL_BEFORE_COMMIT) {
        throw workerPersistenceFailure;
      }
      workerParentRunIds.add(parent.runId());
      stored.put(terminal.runId(), terminal);
      terminals.add(terminal);
      completions++;
      if (workerResult != null) {
        workerResults++;
        durableWorkerResults.put(terminal.runId(), workerResult);
      }
      if (workerPersistenceFault
              == WorkerPersistenceFault.ACK_LOST_AFTER_COMMIT
          || workerPersistenceFault
              == WorkerPersistenceFault.TAMPERED_AFTER_ACK_LOSS) {
        throw workerPersistenceFailure;
      }
      return new WorkerCompletion(terminal, workerResult);
    }

    @Override
    public Optional<WorkerCompletion> findWorkerOwned(
        AgentRunContext parent,
        String childRunId) {
      AgentRun run = stored.get(childRunId);
      if (workerPersistenceFault
          == WorkerPersistenceFault.MISSING_AFTER_ACK) {
        return Optional.empty();
      }
      if (run == null
          || !run.principalId().equals(parent.principalId())
          || !run.lifecycle().terminal()) {
        return Optional.empty();
      }
      if (!parent.equals(workerParents.get(childRunId))) {
        throw new IllegalStateException(
            "Pack 007 Worker read has a different parent Run");
      }
      workerParentRunIds.add(parent.runId());
      workerReads++;
      if (workerPersistenceFault
          == WorkerPersistenceFault.TAMPERED_AFTER_ACK_LOSS) {
        io.emergeos.contracts.WorkerResultEnvelope committed =
            durableWorkerResults.get(childRunId);
        io.emergeos.contracts.WorkerResultEnvelope tampered =
            io.emergeos.contracts.WorkerResultEnvelope.create(
                committed.childRunId(),
                committed.childTaskId(),
                committed.outputSchema(),
                committed.content() + "（已篡改）",
                committed.evidenceRefs());
        return Optional.of(new WorkerCompletion(run, tampered));
      }
      return Optional.of(
          new WorkerCompletion(
              run, durableWorkerResults.get(childRunId)));
    }

    @Override
    public Optional<AgentRun> findOwned(String principalId, String runId) {
      AgentRun run = stored.get(runId);
      return run != null && run.principalId().equals(principalId)
          ? Optional.of(run)
          : Optional.empty();
    }

    private int startCount() {
      return starts;
    }

    private int completeCount() {
      return completions;
    }

    private int artifactCount() {
      return artifacts;
    }

    private int workerResultCount() {
      return workerResults;
    }

    private int workerReadCount() {
      return workerReads;
    }

    private int pairVerificationCount() {
      return pairVerifications;
    }

    private RuntimeException workerPersistenceFailure() {
      return workerPersistenceFailure;
    }

    private List<String> workerParentRunIds() {
      return List.copyOf(workerParentRunIds);
    }

    private List<AgentRun> terminals() {
      return List.copyOf(terminals);
    }
  }

  private record DirectWorkerFault(
      RecordingRunStore runs,
      AgentWorkerRuntime.PreparedHandoff prepared) {}

  private record ParentWorkerFault(
      RecordingRunStore runs,
      ConductorModel model,
      AgentDraftOutcome outcome) {}

  private enum WorkerPersistenceFault {
    NONE,
    ACK_LOST_AFTER_COMMIT,
    FAIL_BEFORE_COMMIT,
    MISSING_AFTER_ACK,
    TAMPERED_AFTER_ACK_LOSS
  }

  private static final class FrozenIds implements IdGenerator {

    private final Map<String, Deque<String>> values = new LinkedHashMap<>();

    private FrozenIds(Map<String, List<String>> values) {
      values.forEach(
          (prefix, ids) -> this.values.put(prefix, new ArrayDeque<>(ids)));
    }

    @Override
    public String next(String prefix) {
      Deque<String> available = values.get(prefix);
      if (available == null || available.isEmpty()) {
        throw new IllegalStateException("unexpected Pack 007 ID request: " + prefix);
      }
      return available.removeFirst();
    }
  }

  private static final class FrozenNanoTime
      implements java.util.function.LongSupplier {

    @Override
    public long getAsLong() {
      return 0;
    }
  }
}
