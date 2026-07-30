package io.emergeos.core.application;

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
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceProtocol;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentDraftService {

  private static final String RESULT_SCHEMA_VERSION = "1.0";
  private static final String TRACE_SCHEMA_VERSION = "1.0";

  private final AgentKernel kernel;
  private final AgentRunStore runs;
  private final CaptureStore captures;
  private final IdGenerator ids;
  private final Clock clock;
  private final AgentExecutionProfile executionProfile;

  public AgentDraftService(
      AgentKernel kernel,
      AgentRunStore runs,
      CaptureStore captures,
      IdGenerator ids,
      Clock clock,
      AgentExecutionProfile executionProfile) {
    this.kernel = Objects.requireNonNull(kernel, "kernel");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.captures = Objects.requireNonNull(captures, "captures");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executionProfile =
        Objects.requireNonNull(executionProfile, "executionProfile");
    String kernelProfileId = kernel.executionProfileId();
    String kernelProfileFingerprint = kernel.executionProfileFingerprint();
    if (executionProfile.modelBound()
        ? !executionProfile.id().equals(kernelProfileId)
            || !executionProfile
                .fingerprint()
                .equals(kernelProfileFingerprint)
        : kernelProfileId != null || kernelProfileFingerprint != null) {
      throw new IllegalArgumentException(
          "execution profile and AgentKernel identity disagree");
    }
  }

  public AgentDraftOutcome draft(AgentDraftCommand command) {
    Objects.requireNonNull(command, "command");
    String runId = ids.next("run");
    String captureRef = "capture://" + command.captureId();
    Capture ownedCapture =
        captures.findOwned(command.principalId(), command.captureId()).orElse(null);
    TaskEnvelope task = task(command, captureRef, ownedCapture);
    executionProfile.requireTaskBinding(task);
    Instant startedAt = clock.instant();
    runs.start(AgentRun.running(runId, command.principalId(), task, startedAt));

    AgentRunOutcome kernelRun;
    try {
      kernelRun = sanitizeKernelOutcome(kernel.run(task, CancellationSignal.never()), task);
    } catch (RuntimeException unexpectedKernelFailure) {
      kernelRun = safeFailure("AGENT_KERNEL_FAILED");
    }

    List<ResourceBinding> evidenceBindings =
        evidenceBindings(kernelRun, captureRef, ownedCapture);
    if (evidenceBindings == null) {
      kernelRun = safeFailure("UNSAFE_EVIDENCE_BINDING");
      evidenceBindings = List.of();
    }

    if (kernelRun.status() != RunStatus.SUCCEEDED) {
      return complete(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          kernelRun.status(),
          kernelRun.failureReason(),
          null,
          evidenceBindings);
    }
    AgentDraftProposal proposal = kernelRun.proposal();
    if (proposal == null || !isValidArtifactContent(proposal.content())) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          "INVALID_STRUCTURED_FINAL",
          evidenceBindings);
    }
    if (!kernelRun.obtainedEvidenceRefs().contains(captureRef)) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          "MISSING_REQUIRED_EVIDENCE",
          evidenceBindings);
    }
    if (!proposal.evidenceRefs().equals(List.of(captureRef))) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          "INVALID_EVIDENCE_CLAIM",
          evidenceBindings);
    }

    if (ownedCapture == null) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          "REQUIRED_EVIDENCE_NOT_FOUND",
          evidenceBindings);
    }

    String artifactId = ids.next("art");
    Instant completedAt = clock.instant();
    var initial =
        new ArtifactLineageEntry(
            1,
            proposal.content(),
            ContentHashes.sha256(proposal.content()),
            null,
            null,
            completedAt);
    var artifact =
        new ArtifactLineage(
            artifactId,
            command.principalId(),
            command.captureId(),
            List.of(initial));
    String artifactRef = "artifact-version://" + artifactId + "/1";
    return complete(
        command,
        runId,
        task,
        startedAt,
        kernelRun,
        RunStatus.SUCCEEDED,
        null,
        artifact,
        appendArtifactBinding(evidenceBindings, artifactRef, artifact));
  }

  private AgentDraftOutcome rejected(
      AgentDraftCommand command,
      String runId,
      TaskEnvelope task,
      Instant startedAt,
      AgentRunOutcome kernelRun,
      String reason,
      List<ResourceBinding> evidenceBindings) {
    return complete(
        command,
        runId,
        task,
        startedAt,
        kernelRun,
        RunStatus.FAILED,
        reason,
        null,
        evidenceBindings);
  }

  private AgentDraftOutcome complete(
      AgentDraftCommand command,
      String runId,
      TaskEnvelope task,
      Instant startedAt,
      AgentRunOutcome kernelRun,
      RunStatus status,
      String failureReason,
      ArtifactLineage artifact,
      List<ResourceBinding> bindings) {
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    List<AgentTraceEvent> safeEvents = new ArrayList<>(kernelRun.trace());
    List<String> artifactRefs = List.of();
    List<String> evidenceRefs =
        bindings.stream()
            .filter(binding -> binding.role() == ResourceRole.EVIDENCE)
            .map(ResourceBinding::ref)
            .toList();
    if (artifact != null) {
      String artifactRef = "artifact-version://" + artifact.artifactId() + "/1";
      safeEvents.add(
          new AgentTraceEvent(
              safeEvents.size() + 1,
              io.emergeos.core.domain.AgentTraceEventType.ARTIFACT_COMMITTED,
              null,
              "SUCCEEDED",
              artifactRef));
      artifactRefs = List.of(artifactRef);
    }
    AgentTraceEnvelope trace = trace(runId, task.id(), safeEvents);
    ResultEnvelope result =
        new ResultEnvelope(
            RESULT_SCHEMA_VERSION,
            runId,
            task.id(),
            status,
            artifactRefs,
            evidenceRefs,
            List.of(),
            List.of(),
            List.of(),
            kernelRun.resolvedModel(),
            executionProfile.agentVersion(),
            executionProfile.verifierVersion(),
            kernelRun.costUsd(),
            kernelRun.tokenCount(),
            kernelRun.latencyMs(),
            traceRef,
            failureReason);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            executionProfile.experiment(),
            kernelRun.resolvedModel(),
            executionProfile.harnessVersion(),
            executionProfile.componentVersions(),
            task.environmentSnapshotRef(),
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
            failureReason,
            status,
            kernelRun.costUsd(),
            kernelRun.tokenCount(),
            kernelRun.latencyMs());
    Instant completedAt = clock.instant();
    AgentRun terminal =
        new AgentRun(
            runId,
            command.principalId(),
            task,
            AgentRunLifecycle.terminal(status),
            result,
            trace,
            bundle,
            startedAt,
            completedAt);
    AgentRunStore.CompletionResult committed = runs.complete(terminal, artifact);
    return new AgentDraftOutcome(committed.run(), committed.artifact());
  }

  private static AgentTraceEnvelope trace(
      String runId, String taskId, List<AgentTraceEvent> safeEvents) {
    List<AgentTraceEntry> entries = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    for (AgentTraceEvent event : safeEvents) {
      TraceEventType type = TraceEventType.valueOf(event.type().name());
      AgentTraceEntry entry =
          AgentTraceEntry.create(
              entries.size() + 1,
              type,
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

  private TaskEnvelope task(
      AgentDraftCommand command, String captureRef, Capture ownedCapture) {
    String taskId = ids.next("task");
    return new TaskEnvelope(
        executionProfile.taskSchemaVersion(),
        taskId,
        null,
        command.principalId(),
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        command.intent(),
        List.of(captureRef),
        List.of(),
        List.of("text"),
        ownedCapture == null ? DataClass.PERSONAL : ownedCapture.dataClass(),
        executionProfile.risk(),
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("draft cites the source Capture"),
        false,
        executionProfile.maxModelSteps(),
        executionProfile.maxToolCalls(),
        executionProfile.deadlineMs(),
        executionProfile.budgetUsd(),
        executionProfile.modelProvider(),
        executionProfile.modelRequested(),
        executionProfile.pricingProfile(),
        executionProfile.taskIdempotencyKey(taskId),
        executionProfile.policyVersion(),
        executionProfile.stateVersion(),
        executionProfile.contextPolicyVersion(),
        executionProfile.toolRegistryVersion(),
        executionProfile.environmentSnapshotRef(),
        executionProfile.capabilityRefs(),
        List.of(),
        "structured final or non-success");
  }

  private static AgentRunOutcome sanitizeKernelOutcome(
      AgentRunOutcome candidate, TaskEnvelope task) {
    if (candidate == null) {
      return safeFailure("UNSAFE_AGENT_OUTCOME");
    }
    boolean success = candidate.status() == RunStatus.SUCCEEDED;
    try {
      if (!safeModel(candidate.resolvedModel())
          || (success && candidate.resolvedModel() == null)
          || candidate.trace().size() > (success ? 127 : 128)
          || candidate.tokenCount() > io.emergeos.contracts.ContractValueDomains.MAX_SAFE_INTEGER
          || candidate.latencyMs()
              > io.emergeos.contracts.ContractValueDomains.MAX_DURATION_MS
          || candidate.latencyMs() > task.deadlineMs()
          || (candidate.costUsd().compareTo(task.budgetUsd()) > 0
              && !("1.1".equals(task.schemaVersion())
                  && !success
                  && "MODEL_BUDGET_EXHAUSTED".equals(candidate.failureReason())))) {
        return safeFailure("UNSAFE_AGENT_OUTCOME");
      }
      io.emergeos.contracts.ContractValueDomains.requireUsd(
          candidate.costUsd(), "kernel costUsd");
      if ((success && candidate.failureReason() != null)
          || (!success
              && (candidate.failureReason() == null
                  || !candidate.failureReason().matches("[A-Z][A-Z0-9_]{0,127}")))
          || (success && candidate.proposal() == null)
          || (!success && candidate.proposal() != null)) {
        return safeFailure("UNSAFE_AGENT_OUTCOME");
      }
    } catch (RuntimeException invalidAdapterOutcome) {
      return safeFailure("UNSAFE_AGENT_OUTCOME");
    }
    try {
      AgentTraceProtocol.verifyKernelOutcome(task, candidate);
      return candidate;
    } catch (RuntimeException invalidTrace) {
      return safeFailure("UNSAFE_AGENT_TRACE");
    }
  }

  private static boolean safeModel(String model) {
    return model == null || model.matches("[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}");
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
      AgentRunOutcome kernelRun, String captureRef, Capture ownedCapture) {
    if (kernelRun.obtainedEvidenceRefs().isEmpty()) {
      return List.of();
    }
    if (ownedCapture == null
        || !kernelRun.obtainedEvidenceRefs().equals(List.of(captureRef))) {
      return null;
    }
    return List.of(
        new ResourceBinding(
            ResourceRole.EVIDENCE, 0, captureRef, ownedCapture.requestHash()));
  }

  private static List<ResourceBinding> appendArtifactBinding(
      List<ResourceBinding> evidenceBindings,
      String artifactRef,
      ArtifactLineage artifact) {
    List<ResourceBinding> bindings = new ArrayList<>(evidenceBindings);
    bindings.add(
        new ResourceBinding(
            ResourceRole.ARTIFACT,
            0,
            artifactRef,
            artifact.current().contentHash()));
    return List.copyOf(bindings);
  }

  private static boolean isValidArtifactContent(String content) {
    try {
      ArtifactLineageEntry.requireContent(content);
      return true;
    } catch (IllegalArgumentException invalidContent) {
      return false;
    }
  }
}
