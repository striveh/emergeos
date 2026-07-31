package io.emergeos.adapters.inmemory.agent;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentTool;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
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
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.ReadOnlyWorkerRunStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic offline Pack 007 product runner.
 *
 * <p>The runner composes the same main-source Conductor and proposal Worker models as the API.
 * Repository JSON loading stays outside this class so replay cannot silently accept an unfrozen
 * file or parser configuration.
 */
public final class OfflineReadOnlyWorkerRunner {

  public Observation run(Spec spec) {
    Objects.requireNonNull(spec, "spec");
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerFakeV1();
    if (!parentProfile
        .contextPolicyVersion()
        .equals(spec.parentContextPolicyVersion())) {
      throw new IllegalArgumentException(
          "Pack 007 parent context policy is not bound to the product profile");
    }

    Capture capture =
        new Capture(
            spec.captureId(),
            spec.principalId(),
            spec.captureNonce(),
            CaptureRequestHashes.sha256(
                spec.captureContent(),
                CaptureSourceType.TEXT,
                spec.captureSourceRef(),
                DataClass.PUBLIC),
            spec.captureContent(),
            CaptureSourceType.TEXT,
            spec.captureSourceRef(),
            DataClass.PUBLIC,
            spec.startedAt());
    RecordingCaptureStore captures = new RecordingCaptureStore(capture);
    RecordingRunStore runs = new RecordingRunStore();
    FrozenIds ids =
        new FrozenIds(
            Map.of(
                "run", List.of(spec.parentRunId(), spec.childRunId()),
                "task", List.of(spec.parentTaskId(), spec.childTaskId()),
                "art", List.of(spec.artifactId())));
    Clock clock = Clock.fixed(spec.startedAt(), ZoneOffset.UTC);
    ReadOnlyWorkerExecutionProfile expectedWorkerProfile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1(
            spec.parentContextPolicyVersion());

    CountingModel parentModel =
        new CountingModel(ScriptedFakeModel.forReadOnlyWorkerDraft());
    CountingModel childModel =
        new CountingModel(
            ScriptedFakeModel.forReadOnlyWorkerProposal());
    CountingCaptureReadTool captureRead =
        new CountingCaptureReadTool(captures);
    AgentLoopKernel childKernel =
        new AgentLoopKernel(
            childModel,
            new AgentToolRegistry(
                parentProfile.toolRegistryVersion(), List.of(captureRead)),
            expectedWorkerProfile.maxModelSteps(),
            expectedWorkerProfile.maxToolCalls(),
            () -> 0L);

    AgentWorkerRuntime durableWorker =
        new DurableReadOnlyWorkerService(
            childKernel,
            runs,
            captures,
            ids,
            clock,
            expectedWorkerProfile);
    MutableContextRuntime workerRuntime =
        new MutableContextRuntime(expectedWorkerProfile, durableWorker);

    AgentLoopKernel parentKernel =
        new AgentLoopKernel(
            parentModel,
            new AgentToolRegistry(
                parentProfile.toolRegistryVersion(),
                List.of(new CaptureReadTool(captures))),
            workerRuntime,
            parentProfile.maxModelSteps(),
            parentProfile.maxToolCalls(),
            () -> 0L);
    AgentDraftService service =
        new AgentDraftService(
            parentKernel,
            runs,
            captures,
            ids,
            clock,
            parentProfile);
    workerRuntime.install(
        ReadOnlyWorkerExecutionProfile.pack007FakeV1(
            spec.registeredWorkerContextPolicyVersion()));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                spec.principalId(), spec.captureId(), spec.intent()));
    AgentRun child =
        runs.terminals().stream()
            .filter(run -> run.task().parentId() != null)
            .findFirst()
            .orElse(null);
    WorkerResultEnvelope workerResult =
        child == null ? null : runs.workerResults().get(child.runId());

    return new Observation(
        spec.caseId(),
        outcome.run(),
        child,
        workerResult,
        outcome.artifact(),
        runs.startCount(),
        runs.completeCount(),
        runs.artifactCount(),
        runs.workerResultCount(),
        runs.workerReadCount(),
        runs.pairVerificationCount(),
        parentModel.callCount(),
        childModel.callCount(),
        captureRead.executeCount(),
        captures.readCount(),
        workerRuntime.prepareCount());
  }

  public record Spec(
      String schemaVersion,
      String caseId,
      String principalId,
      String captureId,
      String captureNonce,
      String captureContent,
      String captureSourceRef,
      String intent,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId,
      String parentContextPolicyVersion,
      String registeredWorkerContextPolicyVersion,
      Instant startedAt) {

    public Spec {
      if (!"1.0".equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "Pack 007 runner supports schemaVersion 1.0");
      }
      requireSlug(caseId, "caseId");
      requireId(principalId, "principalId");
      requireId(captureId, "captureId");
      requireId(captureNonce, "captureNonce");
      requireText(captureContent, "captureContent");
      requireId(captureSourceRef, "captureSourceRef");
      requireText(intent, "intent");
      requireId(parentRunId, "parentRunId");
      requireId(childRunId, "childRunId");
      requireId(parentTaskId, "parentTaskId");
      requireId(childTaskId, "childTaskId");
      requireId(artifactId, "artifactId");
      requireId(parentContextPolicyVersion, "parentContextPolicyVersion");
      requireId(
          registeredWorkerContextPolicyVersion,
          "registeredWorkerContextPolicyVersion");
      Objects.requireNonNull(startedAt, "startedAt");
      if (parentRunId.equals(childRunId())
          || parentTaskId.equals(childTaskId())) {
        throw new IllegalArgumentException(
            "Pack 007 parent and child identities must differ");
      }
    }

    public boolean contextPolicyDrift() {
      return !parentContextPolicyVersion.equals(
          registeredWorkerContextPolicyVersion);
    }
  }

  public record Observation(
      String caseId,
      AgentRun parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact,
      int runStarts,
      int runCompletions,
      int artifactCommits,
      int workerResultCommits,
      int workerVerifiedReads,
      int pairVerifications,
      int parentModelCalls,
      int childModelCalls,
      int childToolExecutions,
      int captureReads,
      int workerPreparations) {

    public Observation {
      requireSlug(caseId, "caseId");
      Objects.requireNonNull(parent, "parent");
      if (runStarts < 0
          || runCompletions < 0
          || artifactCommits < 0
          || workerResultCommits < 0
          || workerVerifiedReads < 0
          || pairVerifications < 0
          || parentModelCalls < 0
          || childModelCalls < 0
          || childToolExecutions < 0
          || captureReads < 0
          || workerPreparations < 0) {
        throw new IllegalArgumentException(
            "Pack 007 observation counts cannot be negative");
      }
    }
  }

  private static final class CountingModel implements AgentModel {

    private final AgentModel delegate;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingModel(AgentModel delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

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

  private static final class MutableContextRuntime
      implements AgentWorkerRuntime {

    private ReadOnlyWorkerExecutionProfile profile;
    private final AgentWorkerRuntime delegate;
    private int preparations;

    private MutableContextRuntime(
        ReadOnlyWorkerExecutionProfile profile,
        AgentWorkerRuntime delegate) {
      this.profile = Objects.requireNonNull(profile, "profile");
      this.delegate = Objects.requireNonNull(delegate, "delegate");
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
      if (rejection != null) {
        return Preparation.rejected(RunStatus.BLOCKED, rejection);
      }
      return delegate.prepare(parent, request, window);
    }

    private void install(ReadOnlyWorkerExecutionProfile replacement) {
      profile = Objects.requireNonNull(replacement, "replacement");
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
      delegate = new CaptureReadTool(captures);
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
        io.emergeos.contracts.TaskEnvelope task,
        AgentModel.ToolCall call) {
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
      this.capture = Objects.requireNonNull(capture, "capture");
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException(
          "Pack 007 Capture is frozen");
    }

    @Override
    public Optional<Capture> findOwned(
        String principalId, String captureId) {
      if (!capture.principalId().equals(principalId)
          || !capture.captureId().equals(captureId)) {
        return Optional.empty();
      }
      reads++;
      return Optional.of(capture);
    }

    private int readCount() {
      return reads;
    }
  }

  private static final class RecordingRunStore
      implements AgentRunStore, ReadOnlyWorkerRunStore {

    private final Map<String, AgentRun> stored = new LinkedHashMap<>();
    private final Map<String, AgentRunContext> workerParents =
        new LinkedHashMap<>();
    private final Map<String, WorkerResultEnvelope> workerResults =
        new LinkedHashMap<>();
    private final List<AgentRun> terminals = new ArrayList<>();
    private int starts;
    private int completions;
    private int artifacts;
    private int workerResultCommits;
    private int workerReads;
    private int pairVerifications;

    @Override
    public AgentRun start(AgentRun running) {
      if (running.lifecycle() != AgentRunLifecycle.RUNNING
          || stored.putIfAbsent(running.runId(), running) != null) {
        throw new IllegalStateException(
            "Pack 007 Run start is invalid");
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
        throw new IllegalStateException(
            "Pack 007 terminal transition is invalid");
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
            stored.get(childRunId),
            workerResults.get(childRunId),
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
        AgentRunContext parent, AgentRun running) {
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
      workerParents.put(running.runId(), parent);
      return start(running);
    }

    @Override
    public WorkerCompletion completeWorker(
        AgentRunContext parent,
        AgentRun terminal,
        WorkerResultEnvelope workerResult) {
      AgentRun running = stored.get(terminal.runId());
      if (running == null
          || running.lifecycle() != AgentRunLifecycle.RUNNING
          || !terminal.lifecycle().terminal()
          || !running.task().equals(terminal.task())
          || !parent.equals(workerParents.get(terminal.runId()))) {
        throw new IllegalStateException(
            "Pack 007 Worker terminal transition is invalid");
      }
      stored.put(terminal.runId(), terminal);
      terminals.add(terminal);
      completions++;
      if (workerResult != null) {
        workerResultCommits++;
        workerResults.put(terminal.runId(), workerResult);
      }
      return new WorkerCompletion(terminal, workerResult);
    }

    @Override
    public Optional<WorkerCompletion> findWorkerOwned(
        AgentRunContext parent, String childRunId) {
      AgentRun run = stored.get(childRunId);
      if (run == null
          || !run.principalId().equals(parent.principalId())
          || !run.lifecycle().terminal()) {
        return Optional.empty();
      }
      if (!parent.equals(workerParents.get(childRunId))) {
        throw new IllegalStateException(
            "Pack 007 Worker read has a different parent Run");
      }
      workerReads++;
      return Optional.of(
          new WorkerCompletion(run, workerResults.get(childRunId)));
    }

    @Override
    public Optional<AgentRun> findOwned(
        String principalId, String runId) {
      AgentRun run = stored.get(runId);
      return run != null && run.principalId().equals(principalId)
          ? Optional.of(run)
          : Optional.empty();
    }

    private Map<String, WorkerResultEnvelope> workerResults() {
      return Map.copyOf(workerResults);
    }

    private List<AgentRun> terminals() {
      return List.copyOf(terminals);
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
      return workerResultCommits;
    }

    private int workerReadCount() {
      return workerReads;
    }

    private int pairVerificationCount() {
      return pairVerifications;
    }
  }

  private static final class FrozenIds implements IdGenerator {

    private final Map<String, Deque<String>> values =
        new LinkedHashMap<>();

    private FrozenIds(Map<String, List<String>> values) {
      values.forEach(
          (prefix, ids) ->
              this.values.put(prefix, new ArrayDeque<>(ids)));
    }

    @Override
    public String next(String prefix) {
      Deque<String> available = values.get(prefix);
      if (available == null || available.isEmpty()) {
        throw new IllegalStateException(
            "unexpected Pack 007 ID request: " + prefix);
      }
      return available.removeFirst();
    }
  }

  private static void requireId(String value, String field) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireSlug(String value, String field) {
    if (value == null
        || !value.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 65_536) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }
}
