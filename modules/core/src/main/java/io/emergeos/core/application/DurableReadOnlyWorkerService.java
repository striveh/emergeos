package io.emergeos.core.application;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceProtocol;
import io.emergeos.core.domain.AgentWorkerExecution;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.ReadOnlyWorkerRunStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable application runtime for Pack007's one read-only proposal Worker.
 *
 * <p>This class owns child Task construction, lifecycle and Worker Result persistence. It has no
 * Artifact store or external-action capability.
 */
public final class DurableReadOnlyWorkerService
    implements AgentWorkerRuntime {

  private static final String RESULT_SCHEMA_VERSION = "1.0";
  private static final String TRACE_SCHEMA_VERSION = "1.0";

  private final AgentKernel workerKernel;
  private final ReadOnlyWorkerRunStore runs;
  private final CaptureStore captures;
  private final IdGenerator ids;
  private final Clock clock;
  private final ReadOnlyWorkerExecutionProfile profile;
  private final AgentDraftVerifier verifier;

  public DurableReadOnlyWorkerService(
      AgentKernel workerKernel,
      ReadOnlyWorkerRunStore runs,
      CaptureStore captures,
      IdGenerator ids,
      Clock clock,
      ReadOnlyWorkerExecutionProfile profile) {
    this.workerKernel = Objects.requireNonNull(workerKernel, "workerKernel");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.captures = Objects.requireNonNull(captures, "captures");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.profile = Objects.requireNonNull(profile, "profile");
    this.verifier = ReferenceGroundingAgentDraftVerifier.INSTANCE;
    if (workerKernel.workerRegistryVersion() != null
        || workerKernel.workerProfileFingerprint() != null) {
      throw new IllegalArgumentException(
          "a read-only Worker Kernel cannot dispatch another Worker");
    }
    if (workerKernel.executionProfileId() != null
        || workerKernel.executionProfileFingerprint() != null) {
      throw new IllegalArgumentException(
          "Pack007 Worker currently accepts only the frozen offline model route");
    }
    if (!profile.verifierVersion().equals(verifier.version())) {
      throw new IllegalArgumentException(
          "Worker profile and verifier identity disagree");
    }
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
      WorkerHandoffRequest request,
      ExecutionWindow window) {
    String rejection =
        profile.validatePreparation(parent.task(), request, window);
    if (rejection != null) {
      return Preparation.rejected(RunStatus.BLOCKED, rejection);
    }
    String childRunId = ids.next("run");
    String childTaskId = ids.next("task");
    TaskEnvelope childTask =
        profile.newChildTask(parent.task(), request, window, childTaskId);
    return Preparation.prepared(
        new Prepared(parent, childTask, childRunId, window));
  }

  private AgentWorkerExecution execute(
      AgentRunContext parent,
      TaskEnvelope childTask,
      String childRunId,
      ExecutionWindow window) {
    profile.requireChildBinding(parent.task(), childTask);
    Instant startedAt = clock.instant();
    AgentRun planned =
        AgentRun.running(
            childRunId, childTask.principalRef(), childTask, startedAt);
    AgentRun canonical = runs.startWorker(parent, planned);
    if (!planned.equals(canonical)) {
      throw new IllegalStateException(
          "ReadOnlyWorkerRunStore returned different RUNNING child truth");
    }
    AgentRunContext childContext = AgentRunContext.fromRunning(canonical);

    AgentRunOutcome kernelRun;
    try {
      kernelRun =
          sanitizeKernelOutcome(
              workerKernel.run(childContext, window.cancellation()), childTask);
    } catch (RuntimeException unexpectedKernelFailure) {
      kernelRun = safeFailure("AGENT_KERNEL_FAILED");
    }

    String captureRef = childTask.inputRefs().getFirst();
    Capture ownedCapture =
        captures
            .findOwned(
                childTask.principalRef(),
                captureRef.substring("capture://".length()))
            .orElse(null);
    List<ResourceBinding> bindings =
        evidenceBindings(kernelRun, captureRef, ownedCapture);
    if (bindings == null) {
      kernelRun = safeFailure("UNSAFE_EVIDENCE_BINDING");
      bindings = List.of();
    }

    WorkerResultEnvelope workerResult = null;
    RunStatus status = kernelRun.status();
    String failureReason = kernelRun.failureReason();
    if (status == RunStatus.SUCCEEDED) {
      AgentDraftReferenceGrounding.Verification verification = null;
      try {
        verification =
            verifier.verify(
                new AgentDraftReferenceGrounding.Candidate(
                    kernelRun.proposal(),
                    kernelRun.obtainedEvidenceRefs(),
                    captureRef,
                    ownedCapture != null));
      } catch (RuntimeException verifierFailure) {
        status = RunStatus.FAILED;
        failureReason = "AGENT_VERIFIER_FAILED";
      }
      if (verification != null && !verification.accepted()) {
        status = RunStatus.FAILED;
        failureReason = verification.failureCode();
      } else if (verification != null) {
        AgentDraftProposal proposal = kernelRun.proposal();
        workerResult =
            WorkerResultEnvelope.create(
                childRunId,
                childTask.id(),
                childTask.outputSchema(),
                proposal.content(),
                proposal.evidenceRefs());
        List<ResourceBinding> successBindings = new ArrayList<>(bindings);
        successBindings.add(
            new ResourceBinding(
                ResourceRole.WORKER_RESULT,
                0,
                workerResult.workerResultRef(),
                workerResult.integrityHash()));
        bindings = List.copyOf(successBindings);
      }
    }

    AgentTraceEnvelope trace =
        trace(childRunId, childTask.id(), kernelRun.trace());
    String traceRef = "/api/v1/agent-runs/" + childRunId + "/trace";
    List<String> evidenceRefs =
        bindings.stream()
            .filter(binding -> binding.role() == ResourceRole.EVIDENCE)
            .map(ResourceBinding::ref)
            .toList();
    ResultEnvelope result =
        new ResultEnvelope(
            RESULT_SCHEMA_VERSION,
            childRunId,
            childTask.id(),
            status,
            List.of(),
            evidenceRefs,
            List.of(),
            List.of(),
            List.of(),
            kernelRun.resolvedModel(),
            profile.agentVersion(),
            profile.verifierVersion(),
            kernelRun.costUsd(),
            kernelRun.tokenCount(),
            kernelRun.latencyMs(),
            traceRef,
            failureReason);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            childTask.schemaVersion(),
            childRunId,
            childTask.id(),
            null,
            kernelRun.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            childTask.environmentSnapshotRef(),
            childTask.toolRegistryVersion(),
            childTask,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            bindings,
            null,
            failureReason,
            status,
            kernelRun.costUsd(),
            kernelRun.tokenCount(),
            kernelRun.latencyMs());
    AgentRun terminal =
        new AgentRun(
            childRunId,
            childTask.principalRef(),
            childTask,
            AgentRunLifecycle.terminal(status),
            result,
            trace,
            bundle,
            startedAt,
            clock.instant());
    ReadOnlyWorkerHandoffVerifier.verifyChild(
        parent.task(), terminal, workerResult, profile);
    RuntimeException completionFailure = null;
    try {
      runs.completeWorker(parent, terminal, workerResult);
    } catch (RuntimeException failedAcknowledgement) {
      completionFailure = failedAcknowledgement;
    }
    RuntimeException acknowledgedFailure = completionFailure;
    ReadOnlyWorkerRunStore.WorkerCompletion committed;
    try {
      committed =
          runs
              .findWorkerOwned(parent, childRunId)
              .orElseThrow(
                  () -> {
                    if (acknowledgedFailure != null) {
                      return acknowledgedFailure;
                    }
                    return new IllegalStateException(
                        "committed Worker is not durably readable");
                  });
    } catch (RuntimeException readFailure) {
      if (acknowledgedFailure != null && readFailure != acknowledgedFailure) {
        readFailure.addSuppressed(acknowledgedFailure);
      }
      throw readFailure;
    }
    try {
      ReadOnlyWorkerHandoffVerifier.verifyPlannedChild(
          parent.task(),
          childRunId,
          childTask,
          committed.run(),
          committed.workerResult(),
          profile);
    } catch (RuntimeException verificationFailure) {
      if (acknowledgedFailure != null
          && verificationFailure != acknowledgedFailure) {
        verificationFailure.addSuppressed(acknowledgedFailure);
      }
      throw verificationFailure;
    }
    return new AgentWorkerExecution(
        profile.workerName(),
        "agent-run://" + childRunId,
        committed.run().bundle().integrityHash(),
        committed.run().result().status(),
        committed.workerResult(),
        committed.run().result().costUsd(),
        committed.run().result().tokenCount(),
        committed.run().result().failureReason());
  }

  static AgentRunOutcome sanitizeKernelOutcome(
      AgentRunOutcome candidate, TaskEnvelope task) {
    if (candidate == null) {
      return safeFailure("UNSAFE_AGENT_OUTCOME");
    }
    boolean success = candidate.status() == RunStatus.SUCCEEDED;
    try {
      if (!candidate.handoffs().isEmpty()
          || (success && candidate.proposal() == null)
          || (!success && candidate.proposal() != null)
          || (success && candidate.failureReason() != null)
          || (!success
              && (candidate.failureReason() == null
                  || !candidate
                      .failureReason()
                      .matches("[A-Z][A-Z0-9_]{0,127}")))
          || (candidate.resolvedModel() != null
              && !ContractText.isSafeModelIdentifier(
                  candidate.resolvedModel()))
          || (success && candidate.resolvedModel() == null)
          || !ObservedExecutionLimits.permitsBudget(
              task,
              candidate.status(),
              candidate.costUsd(),
              candidate.failureReason())
          || !ObservedExecutionLimits.permitsLatency(
              task, candidate.status(), candidate.latencyMs())
          || !ObservedExecutionLimits.permitsFailureAttribution(
              task,
              candidate.status(),
              candidate.latencyMs(),
              candidate.failureReason())) {
        return safeFailure("UNSAFE_AGENT_OUTCOME");
      }
      AgentTraceProtocol.verifyKernelOutcome(task, candidate);
      return candidate;
    } catch (RuntimeException unsafe) {
      return safeFailure("UNSAFE_AGENT_TRACE");
    }
  }

  private static AgentRunOutcome safeFailure(String reason) {
    return new AgentRunOutcome(
        RunStatus.FAILED,
        null,
        List.of(),
        List.of(),
        null,
        BigDecimal.ZERO,
        0,
        0,
        reason);
  }

  private static List<ResourceBinding> evidenceBindings(
      AgentRunOutcome kernelRun,
      String captureRef,
      Capture ownedCapture) {
    if (kernelRun.obtainedEvidenceRefs().isEmpty()) {
      return List.of();
    }
    if (ownedCapture == null
        || !kernelRun.obtainedEvidenceRefs().equals(List.of(captureRef))) {
      return null;
    }
    return List.of(
        new ResourceBinding(
            ResourceRole.EVIDENCE,
            0,
            captureRef,
            ownedCapture.requestHash()));
  }

  private static AgentTraceEnvelope trace(
      String runId,
      String taskId,
      List<AgentTraceEvent> safeEvents) {
    List<AgentTraceEntry> entries = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    for (AgentTraceEvent event : safeEvents) {
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              entries.size() + 1,
              TraceEventType.valueOf(event.type().name()),
              event.toolName(),
              event.status(),
              event.reference(),
              root);
      entries.add(entry);
      root = IntegrityHashes.nextTraceRoot(root, entry.eventHash());
    }
    return AgentTraceEnvelope.create(
        TRACE_SCHEMA_VERSION, runId, taskId, entries);
  }

  private final class Prepared implements PreparedHandoff {

    private final AgentRunContext parent;
    private final TaskEnvelope child;
    private final String childRunId;
    private final ExecutionWindow window;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Prepared(
        AgentRunContext parent,
        TaskEnvelope child,
        String childRunId,
        ExecutionWindow window) {
      this.parent = parent;
      this.child = child;
      this.childRunId = childRunId;
      this.window = window;
    }

    @Override
    public String workerName() {
      return profile.workerName();
    }

    @Override
    public String childRunRef() {
      return "agent-run://" + childRunId;
    }

    @Override
    public AgentWorkerExecution execute() {
      if (!consumed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "Prepared Worker Handoff is one-shot");
      }
      return DurableReadOnlyWorkerService.this.execute(
          parent, child, childRunId, window);
    }
  }
}
