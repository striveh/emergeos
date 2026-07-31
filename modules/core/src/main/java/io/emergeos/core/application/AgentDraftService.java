package io.emergeos.core.application;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ObservedExecutionLimits;
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
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.AgentTaskAuthorizer;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentDraftService {

  private static final String RESULT_SCHEMA_VERSION = "1.0";
  private static final String TRACE_SCHEMA_VERSION = "1.0";
  private static final String AGENT_VERIFIER_FAILED = "AGENT_VERIFIER_FAILED";

  private final AgentKernel kernel;
  private final AgentRunStore runs;
  private final CaptureStore captures;
  private final IdGenerator ids;
  private final Clock clock;
  private final AgentExecutionProfile executionProfile;
  private final AgentTaskAuthorizer taskAuthorizer;
  private final AgentDraftVerifier verifier;
  private final String verifierVersion;
  private final Map<String, String> componentVersions;

  public AgentDraftService(
      AgentKernel kernel,
      AgentRunStore runs,
      CaptureStore captures,
      IdGenerator ids,
      Clock clock,
      AgentExecutionProfile executionProfile) {
    this(
        kernel,
        runs,
        captures,
        ids,
        clock,
        executionProfile,
        legacyOfflineAuthorizer(executionProfile),
        ReferenceGroundingAgentDraftVerifier.INSTANCE);
  }

  public AgentDraftService(
      AgentKernel kernel,
      AgentRunStore runs,
      CaptureStore captures,
      IdGenerator ids,
      Clock clock,
      AgentExecutionProfile executionProfile,
      AgentTaskAuthorizer taskAuthorizer) {
    this(
        kernel,
        runs,
        captures,
        ids,
        clock,
        executionProfile,
        taskAuthorizer,
        ReferenceGroundingAgentDraftVerifier.INSTANCE);
  }

  AgentDraftService(
      AgentKernel kernel,
      AgentRunStore runs,
      CaptureStore captures,
      IdGenerator ids,
      Clock clock,
      AgentExecutionProfile executionProfile,
      AgentTaskAuthorizer taskAuthorizer,
      AgentDraftVerifier verifier) {
    this.kernel = Objects.requireNonNull(kernel, "kernel");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.captures = Objects.requireNonNull(captures, "captures");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executionProfile =
        Objects.requireNonNull(executionProfile, "executionProfile");
    this.taskAuthorizer =
        Objects.requireNonNull(taskAuthorizer, "taskAuthorizer");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.verifierVersion =
        Objects.requireNonNull(verifier.version(), "verifier version");
    this.componentVersions = executionProfile.componentVersions();
    if (!executionProfile.verifierVersion().equals(verifierVersion)
        || !verifierVersion.equals(componentVersions.get("verifier"))) {
      throw new IllegalArgumentException(
          "execution profile and AgentDraftVerifier identity disagree");
    }
    if (executionProfile.modelBound()
        && taskAuthorizer == AgentTaskAuthorizer.allowAll()) {
      throw new IllegalArgumentException(
          "a model-bound AgentDraftService rejects the unrestricted task authorizer");
    }
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
    if (executionProfile.workerBound()) {
      ReadOnlyWorkerExecutionProfile worker =
          ReadOnlyWorkerExecutionProfile.pack007FakeV1(
              executionProfile.contextPolicyVersion());
      if (!worker.registryVersion().equals(kernel.workerRegistryVersion())
          || !worker.fingerprint().equals(kernel.workerProfileFingerprint())) {
        throw new IllegalArgumentException(
            "execution profile and Worker runtime identity disagree");
      }
    } else if (kernel.workerRegistryVersion() != null
        || kernel.workerProfileFingerprint() != null) {
      throw new IllegalArgumentException(
          "an undeclared Worker runtime cannot be attached to this route");
    }
  }

  public AgentDraftOutcome draft(AgentDraftCommand command) {
    return draft(command, CancellationSignal.never());
  }

  public AgentDraftOutcome draft(
      AgentDraftCommand command, CancellationSignal cancellation) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(cancellation, "cancellation");
    String runId = ids.next("run");
    String captureRef = "capture://" + command.captureId();
    Capture ownedCapture =
        captures.findOwned(command.principalId(), command.captureId()).orElse(null);
    TaskEnvelope task = task(command, captureRef, ownedCapture);
    executionProfile.requireTaskBinding(task);
    taskAuthorizer.authorize(task);
    Instant startedAt = clock.instant();
    AgentRun planned =
        AgentRun.running(runId, command.principalId(), task, startedAt);
    AgentRun canonical = runs.start(planned);
    if (!planned.equals(canonical)) {
      throw new IllegalStateException(
          "AgentRunStore returned different RUNNING truth");
    }
    AgentRunContext runContext = AgentRunContext.fromRunning(canonical);

    AgentRunOutcome kernelRun;
    try {
      kernelRun =
          sanitizeKernelOutcome(
              kernel.run(runContext, cancellation), task);
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
      List<ResourceBinding> terminalBindings =
          appendHandoffBindings(evidenceBindings, kernelRun);
      return complete(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          kernelRun.status(),
          kernelRun.failureReason(),
          null,
          terminalBindings);
    }
    AgentDraftReferenceGrounding.Verification verification;
    try {
      verification =
          Objects.requireNonNull(
              verifier.verify(
                  new AgentDraftReferenceGrounding.Candidate(
                      kernelRun.proposal(),
                      kernelRun.obtainedEvidenceRefs(),
                      captureRef,
                      ownedCapture != null)),
              "verifier result");
    } catch (RuntimeException unexpectedVerifierFailure) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          AGENT_VERIFIER_FAILED,
          evidenceBindings);
    }
    if (!verification.accepted()) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          verification.failureCode(),
          evidenceBindings);
    }
    AgentDraftProposal proposal = kernelRun.proposal();
    if (!validAcceptedHandoffProposal(task, kernelRun, proposal)) {
      return rejected(
          command,
          runId,
          task,
          startedAt,
          kernelRun,
          "UNSAFE_WORKER_OUTPUT_BINDING",
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
        appendHandoffBindings(
            appendArtifactBinding(evidenceBindings, artifactRef, artifact),
            kernelRun));
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
        appendHandoffBindings(evidenceBindings, kernelRun));
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
            verifierVersion,
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
            componentVersions,
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            kernelRun.handoffs().stream()
                .map(io.emergeos.core.domain.AgentHandoffObservation::childRunRef)
                .toList(),
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
    return executionProfile.newDraftTask(
        ids.next("task"),
        command.principalId(),
        command.intent(),
        captureRef,
        ownedCapture == null ? DataClass.PERSONAL : ownedCapture.dataClass());
  }

  private static AgentTaskAuthorizer legacyOfflineAuthorizer(
      AgentExecutionProfile executionProfile) {
    Objects.requireNonNull(executionProfile, "executionProfile");
    if (executionProfile.modelBound()) {
      throw new IllegalArgumentException(
          "a model-bound AgentDraftService requires an explicit task authorizer");
    }
    return AgentTaskAuthorizer.allowAll();
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
          || !ObservedExecutionLimits.permitsLatency(
              task, candidate.status(), candidate.latencyMs())
          || !ObservedExecutionLimits.permitsFailureAttribution(
              task,
              candidate.status(),
              candidate.latencyMs(),
              candidate.failureReason())
          || !ObservedExecutionLimits.permitsBudget(
              task,
              candidate.status(),
              candidate.costUsd(),
              candidate.failureReason())) {
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
      if (!safeHandoffObservations(task, candidate)) {
        return safeFailure("UNSAFE_HANDOFF_OUTCOME");
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
    return model == null || ContractText.isSafeModelIdentifier(model);
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

  private static List<ResourceBinding> appendHandoffBindings(
      List<ResourceBinding> bindings,
      AgentRunOutcome kernelRun) {
    List<ResourceBinding> result = new ArrayList<>(bindings);
    for (int index = 0; index < kernelRun.handoffs().size(); index++) {
      var handoff = kernelRun.handoffs().get(index);
      result.add(
          new ResourceBinding(
              ResourceRole.HANDOFF,
              index,
              handoff.childRunRef(),
              handoff.childBundleHash()));
    }
    return List.copyOf(result);
  }

  private static boolean validAcceptedHandoffProposal(
      TaskEnvelope task,
      AgentRunOutcome kernelRun,
      AgentDraftProposal proposal) {
    if (!AgentTraceProtocol.hasReadOnlyWorkerCapability(task)) {
      return kernelRun.handoffs().isEmpty();
    }
    if (kernelRun.handoffs().size() != 1) {
      return false;
    }
    var handoff = kernelRun.handoffs().getFirst();
    return handoff.childStatus() == RunStatus.SUCCEEDED
        && handoff.proposalContentHash().equals(
            ContentHashes.sha256(proposal.content()))
        && handoff.evidenceRefs().equals(proposal.evidenceRefs())
        && handoff.evidenceRefs().equals(kernelRun.obtainedEvidenceRefs());
  }

  private static boolean safeHandoffObservations(
      TaskEnvelope task, AgentRunOutcome candidate) {
    if (candidate.handoffs().size() > 1
        || (!AgentTraceProtocol.hasReadOnlyWorkerCapability(task)
            && !candidate.handoffs().isEmpty())) {
      return false;
    }
    List<String> observedRefs =
        candidate.handoffs().stream()
            .map(io.emergeos.core.domain.AgentHandoffObservation::childRunRef)
            .toList();
    List<String> mandatoryObservedRefs =
        candidate.trace().stream()
            .filter(
                event ->
                    event.type() == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_RESULT
                        || (event.type()
                                == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_REJECTED
                            && event.reference() != null
                            && AgentTraceProtocol.requiresTrustedChildObservation(
                                event.status())))
            .map(AgentTraceEvent::reference)
            .distinct()
            .toList();
    if (!observedRefs.containsAll(mandatoryObservedRefs)) {
      return false;
    }
    List<String> correlatedRefs =
        candidate.trace().stream()
            .filter(
                event ->
                    (event.type()
                            == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_RESULT
                        || event.type()
                            == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_REJECTED)
                        && event.reference() != null
                        && observedRefs.contains(event.reference()))
            .map(AgentTraceEvent::reference)
            .distinct()
            .toList();
    if (!correlatedRefs.equals(observedRefs)) {
      return false;
    }
    if (!candidate.handoffs().isEmpty()) {
      var observation = candidate.handoffs().getFirst();
      if (candidate.costUsd().compareTo(observation.childCostUsd()) < 0
          || candidate.tokenCount() < observation.childTokenCount()) {
        return false;
      }
      AgentTraceEvent completion =
          candidate.trace().stream()
              .filter(
                  event ->
                      observation.childRunRef().equals(event.reference())
                          && (event.type()
                                  == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_RESULT
                              || event.type()
                                  == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_REJECTED))
              .findFirst()
              .orElse(null);
      if (completion == null) {
        return false;
      }
      if (completion.type()
          == io.emergeos.core.domain.AgentTraceEventType.HANDOFF_RESULT) {
        if (observation.childStatus() != RunStatus.SUCCEEDED
            || !candidate
                .obtainedEvidenceRefs()
                .equals(observation.evidenceRefs())) {
          return false;
        }
      } else if (!matchesRejectedChildTruth(
          task, candidate, observation, completion.status())) {
        return false;
      }
    }
    if (candidate.status() == RunStatus.SUCCEEDED) {
      return !AgentTraceProtocol.hasReadOnlyWorkerCapability(task)
          || (candidate.handoffs().size() == 1
              && candidate.handoffs().getFirst().childStatus()
                  == RunStatus.SUCCEEDED);
    }
    return true;
  }

  private static boolean matchesRejectedChildTruth(
      TaskEnvelope task,
      AgentRunOutcome candidate,
      io.emergeos.core.domain.AgentHandoffObservation observation,
      String traceStatus) {
    return switch (traceStatus) {
      case "CHILD_FAILED" ->
          observation.childStatus() == RunStatus.FAILED
              && candidate.status() == RunStatus.FAILED
              && "HANDOFF_CHILD_FAILED".equals(candidate.failureReason());
      case "CHILD_BLOCKED" ->
          observation.childStatus() == RunStatus.BLOCKED
              && candidate.status() == RunStatus.BLOCKED
              && "HANDOFF_CHILD_BLOCKED".equals(candidate.failureReason());
      case "CHILD_NEEDS_INPUT" ->
          observation.childStatus() == RunStatus.NEEDS_INPUT
              && candidate.status() == RunStatus.NEEDS_INPUT
              && "HANDOFF_CHILD_NEEDS_INPUT".equals(candidate.failureReason());
      case "CHILD_CANCELLED" ->
          observation.childStatus() == RunStatus.CANCELLED
              && candidate.status() == RunStatus.CANCELLED
              && "HANDOFF_CHILD_CANCELLED".equals(candidate.failureReason());
      case "DEADLINE_EXCEEDED_AFTER_CHILD" ->
          candidate.status() == RunStatus.FAILED
              && ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE.equals(
                  candidate.failureReason());
      case "LIMIT_EXHAUSTED" ->
          candidate.status() == RunStatus.BLOCKED
              && "HANDOFF_BUDGET_EXHAUSTED".equals(candidate.failureReason())
              && candidate.costUsd().compareTo(task.budgetUsd()) > 0;
      default -> false;
    };
  }

}
