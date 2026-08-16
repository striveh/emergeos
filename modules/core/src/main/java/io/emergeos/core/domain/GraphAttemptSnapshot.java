package io.emergeos.core.domain;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fully re-verified read model from one repeatable-read snapshot. */
public record GraphAttemptSnapshot(
    GraphAttemptManifest manifest,
    GraphAttemptCursor cursor,
    List<GraphAttemptEvent> events,
    AgentRun parentRun,
    AgentRun childRun,
    boolean terminalSealPresent,
    GraphAttemptOutcome outcome,
    GraphBillingStatus billingStatus,
    List<GraphProviderAttribution> providerAttributions,
    List<GraphTerminalBinding> terminalBindings,
    GraphTerminalSeal terminalSeal,
    HarnessCandidateEnvelope candidate,
    WorkerResultEnvelope workerResult,
    ArtifactLineage artifact) {

  public static final String TERMINAL_PROTOCOL_VERSION =
      GraphAttemptManifest.TERMINAL_PROTOCOL_VERSION;

  private static final List<GraphAttemptEventType> TERMINAL_PREFIX =
      List.of(
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
          GraphAttemptEventType.PROVIDER_INTENT,
          GraphAttemptEventType.PROVIDER_ATTRIBUTED,
          GraphAttemptEventType.PROVIDER_INTENT,
          GraphAttemptEventType.PROVIDER_ATTRIBUTED,
          GraphAttemptEventType.CHILD_TERMINAL,
          GraphAttemptEventType.PARENT_TERMINAL,
          GraphAttemptEventType.TERMINAL_SEALED);

  /** Backward-compatible V7 prefix constructor. */
  public GraphAttemptSnapshot(
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor,
      List<GraphAttemptEvent> events,
      AgentRun parentRun,
      AgentRun childRun,
      boolean terminalSealPresent,
      GraphAttemptOutcome outcome,
      GraphBillingStatus billingStatus) {
    this(
        manifest,
        cursor,
        events,
        parentRun,
        childRun,
        terminalSealPresent,
        outcome,
        billingStatus,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null);
  }

  public GraphAttemptSnapshot {
    manifest = Objects.requireNonNull(manifest, "manifest");
    cursor = Objects.requireNonNull(cursor, "cursor");
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    outcome = Objects.requireNonNull(outcome, "outcome");
    billingStatus =
        Objects.requireNonNull(billingStatus, "billingStatus");
    providerAttributions =
        List.copyOf(
            Objects.requireNonNull(
                providerAttributions, "providerAttributions"));
    terminalBindings =
        List.copyOf(
            Objects.requireNonNull(
                terminalBindings, "terminalBindings"));
    requireHead(manifest, cursor, events);
    requirePrefix(manifest, events);
    requireProviderTruth(
        manifest,
        events,
        providerAttributions,
        billingStatus);
    requireRun(
        manifest.parentSelection(),
        parentRun,
        contains(events, GraphAttemptEventType.PARENT_STARTED),
        contains(events, GraphAttemptEventType.PARENT_TERMINAL));
    requireRun(
        manifest.childSelection(),
        childRun,
        contains(events, GraphAttemptEventType.CHILD_STARTED),
        contains(events, GraphAttemptEventType.CHILD_TERMINAL));
    requireTerminalTruth(
        manifest,
        events,
        parentRun,
        childRun,
        terminalBindings,
        terminalSealPresent,
        terminalSeal,
        outcome,
        billingStatus,
        providerAttributions,
        candidate,
        workerResult,
        artifact);
  }

  public String verdict() {
    return "VALID";
  }

  public static GraphBillingStatus deriveBilling(
      List<GraphAttemptEvent> events,
      List<GraphProviderAttribution> attributions) {
    long intents =
        events.stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_INTENT)
            .count();
    if (intents == 0) {
      return GraphBillingStatus.NOT_INVOKED;
    }
    return intents == attributions.size()
        ? GraphBillingStatus.ATTRIBUTED
        : GraphBillingStatus.UNKNOWN;
  }

  /** V7 compatibility helper. */
  public static GraphBillingStatus deriveBilling(
      List<GraphAttemptEvent> events) {
    return deriveBilling(events, List.of());
  }

  private static void requireHead(
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor,
      List<GraphAttemptEvent> events) {
    if (events.isEmpty()
        || !manifest.principalId().equals(cursor.principalId())
        || !manifest.attemptId().equals(cursor.attemptId())
        || !manifest.manifestHash().equals(cursor.manifestHash())
        || events.size() != cursor.lastSequence()
        || !events.getLast().currentHeadHash().equals(
            cursor.headHash())
        || events.getLast().phaseTo() != cursor.phase()) {
      throw new IllegalArgumentException(
          "graph snapshot does not match its durable head");
    }
  }

  private static void requirePrefix(
      GraphAttemptManifest manifest,
      List<GraphAttemptEvent> events) {
    boolean terminalProtocol =
        TERMINAL_PROTOCOL_VERSION.equals(
            manifest.graphProtocolVersion());
    if ((!terminalProtocol && events.size() > 11)
        || events.size() > TERMINAL_PREFIX.size()
        || events.size() == 16) {
      throw new IllegalArgumentException(
          "graph snapshot exceeds its frozen protocol");
    }
    String expectedPrevious =
        GraphAttemptEvent.emptyHead(
            manifest.attemptId(), manifest.manifestHash());
    Instant previousTime = null;
    for (int index = 0; index < events.size(); index++) {
      GraphAttemptEvent event = events.get(index);
      if (event.sequence() != index + 1
          || !event.previousHeadHash().equals(expectedPrevious)
          || event.type() != TERMINAL_PREFIX.get(index)
          || (previousTime != null
              && event.occurredAt().isBefore(previousTime))) {
        throw new IllegalArgumentException(
            "graph snapshot event chain is discontinuous");
      }
      expectedPrevious = event.currentHeadHash();
      previousTime = event.occurredAt();
    }
  }

  private static void requireProviderTruth(
      GraphAttemptManifest manifest,
      List<GraphAttemptEvent> events,
      List<GraphProviderAttribution> attributions,
      GraphBillingStatus billingStatus) {
    List<GraphAttemptEvent> intents =
        events.stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_INTENT)
            .toList();
    List<GraphAttemptEvent> attributedEvents =
        events.stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_ATTRIBUTED)
            .toList();
    if (intents.size() > manifest.maximumProviderRequests()
        || attributions.size() != attributedEvents.size()
        || deriveBilling(events, attributions) != billingStatus) {
      throw new IllegalArgumentException(
          "graph provider attribution count is inconsistent");
    }
    for (int index = 0; index < attributions.size(); index++) {
      GraphProviderAttribution attribution =
          attributions.get(index);
      GraphAttemptEvent intent = intents.get(index);
      GraphAttemptEvent attributed = attributedEvents.get(index);
      int expectedOrdinal = index + 1;
      if (attribution.requestOrdinal() != expectedOrdinal
          || intent.requestOrdinal() != expectedOrdinal
          || attributed.requestOrdinal() != expectedOrdinal
          || !intent.requestHash().equals(
              attribution.requestHash())
          || !intent.modelRequested().equals(
              attribution.modelRequested())
          || !manifest.childActor().equals(
              attribution.providerActor())
          || !manifest
              .pricingProfileFingerprint()
              .equals(
                  attribution.pricingProfileFingerprint())
          || !attribution.attributionHash().equals(
              attributed.evidenceHash())) {
        throw new IllegalArgumentException(
            "graph provider attribution does not match its intent");
      }
    }
  }

  private static void requireTerminalTruth(
      GraphAttemptManifest manifest,
      List<GraphAttemptEvent> events,
      AgentRun parentRun,
      AgentRun childRun,
      List<GraphTerminalBinding> bindings,
      boolean terminalSealPresent,
      GraphTerminalSeal seal,
      GraphAttemptOutcome outcome,
      GraphBillingStatus billingStatus,
      List<GraphProviderAttribution> attributions,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact) {
    GraphTerminalBinding child =
        binding(bindings, GraphRunRole.CHILD);
    GraphTerminalBinding parent =
        binding(bindings, GraphRunRole.PARENT);
    GraphAttemptEvent childEvent =
        event(events, GraphAttemptEventType.CHILD_TERMINAL);
    GraphAttemptEvent parentEvent =
        event(events, GraphAttemptEventType.PARENT_TERMINAL);
    GraphAttemptEvent sealEvent =
        event(events, GraphAttemptEventType.TERMINAL_SEALED);

    if ((child == null) != (childEvent == null)
        || (parent == null) != (parentEvent == null)
        || (seal == null) != (sealEvent == null)
        || terminalSealPresent != (seal != null)) {
      throw new IllegalArgumentException(
          "graph terminal evidence count is inconsistent");
    }
    if (child != null) {
      requireBinding(child, childRun, childEvent);
      BigDecimal attributedCost =
          attributions.stream()
              .map(GraphProviderAttribution::observedCostUsd)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      long attributedTokens =
          attributions.stream()
              .mapToLong(GraphProviderAttribution::totalTokens)
              .sum();
      if (attributions.isEmpty()
          || attributedCost.compareTo(
                  childRun.result().costUsd())
              != 0
          || attributedTokens
              != childRun.result().tokenCount()
          || attributions.stream()
              .map(GraphProviderAttribution::modelResolved)
              .distinct()
              .count()
              != 1
          || !attributions
              .getLast()
              .modelResolved()
              .equals(childRun.result().resolvedModel())) {
        throw new IllegalArgumentException(
            "child terminal usage is not exactly provider-attributed");
      }
      requireChildTruth(
          manifest,
          childRun,
          child,
          attributions,
          candidate,
          workerResult);
    } else if (candidate != null || workerResult != null) {
      throw new IllegalArgumentException(
          "Candidate or Worker Result exists before child terminal");
    }
    if (parent != null) {
      requireBinding(parent, parentRun, parentEvent);
      if (child == null) {
        throw new IllegalArgumentException(
            "parent cannot terminalize before its child");
      }
      requireParentChildTruth(parentRun, childRun);
      requireParentTruth(
          manifest,
          parentRun,
          parent,
          candidate,
          workerResult,
          artifact);
    } else if (artifact != null) {
      throw new IllegalArgumentException(
          "Artifact exists before parent terminal");
    }
    if (seal == null) {
      if (outcome != GraphAttemptOutcome.INCOMPLETE) {
        throw new IllegalArgumentException(
            "an unsealed graph must remain incomplete");
      }
      return;
    }
    if (parent == null
        || billingStatus != GraphBillingStatus.ATTRIBUTED
        || outcome != seal.graphOutcome()
        || !manifest.attemptId().equals(seal.attemptId())
        || !manifest.manifestHash().equals(seal.manifestHash())
        || seal.finalSequence() != sealEvent.sequence()
        || !seal.preSealHeadHash().equals(
            sealEvent.previousHeadHash())
        || !seal.finalHeadHash().equals(
            sealEvent.currentHeadHash())
        || !seal.sealHash().equals(sealEvent.evidenceHash())
        || !seal.childTerminalHash().equals(
            child.terminalHash())
        || !seal.parentTerminalHash().equals(
            parent.terminalHash())
        || !seal.providerAttributionHashes().equals(
            attributions.stream()
                .map(
                    GraphProviderAttribution
                        ::attributionHash)
                .toList())
        || !Objects.equals(
            seal.candidateRef(),
            candidate == null
                ? null
                : candidate.candidateRef())
        || !Objects.equals(
            seal.candidateIntegrityHash(),
            candidate == null
                ? null
                : candidate.integrityHash())
        || !seal.sealedAt().equals(sealEvent.occurredAt())) {
      throw new IllegalArgumentException(
          "terminal seal does not bind the complete graph truth");
    }
    GraphAttemptOutcome expectedOutcome =
        parentRun.result().status()
                    == RunStatus.SUCCEEDED
                && childRun.result().status()
                    == RunStatus.SUCCEEDED
            ? GraphAttemptOutcome.SUCCEEDED
            : GraphAttemptOutcome.FAILED;
    if (outcome != expectedOutcome) {
      throw new IllegalArgumentException(
          "terminal graph outcome does not match its Runs");
    }
  }

  private static void requireBinding(
      GraphTerminalBinding binding,
      AgentRun run,
      GraphAttemptEvent event) {
    if (run == null
        || binding.role() != event.role()
        || !binding.runId().equals(event.runId())
        || !binding.taskId().equals(event.taskId())
        || !binding.runId().equals(run.runId())
        || !binding.taskId().equals(run.task().id())
        || binding.status() != run.result().status()
        || !binding.bundleHash().equals(
            run.bundle().integrityHash())
        || !binding.traceRootHash().equals(
            run.trace().rootHash())
        || !binding.completedAt().equals(run.completedAt())
        || !binding.terminalHash().equals(
            event.evidenceHash())) {
      throw new IllegalArgumentException(
          "terminal binding does not match its durable Run");
    }
    ResourceRole effectRole =
        binding.role() == GraphRunRole.CHILD
            ? ResourceRole.WORKER_RESULT
            : ResourceRole.ARTIFACT;
    List<io.emergeos.contracts.ResourceBinding> effects =
        run.bundle().resourceBindings().stream()
            .filter(item -> item.role() == effectRole)
            .toList();
    if (binding.effectRef() == null) {
      if (!effects.isEmpty()) {
        throw new IllegalArgumentException(
            "terminal Run has an unbound effect");
      }
    } else if (effects.size() != 1
        || !binding.effectRef().equals(
            effects.getFirst().ref())
        || !binding.effectHash().equals(
            effects.getFirst().contentHash())) {
      throw new IllegalArgumentException(
          "terminal effect does not match its Run binding");
    }
  }

  private static void requireChildTruth(
      GraphAttemptManifest manifest,
      AgentRun child,
      GraphTerminalBinding binding,
      List<GraphProviderAttribution> attributions,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {
    String structuredFinalContentHash =
        AgentTraceProtocol
            .structuredFinalProposalContentHash(child.trace());
    if (candidate != null) {
      String requiredRef =
          "capture://" + manifest.captureId();
      GraphProviderAttribution source =
          attributions.size() == 2
              ? attributions.get(1)
              : null;
      if (!manifest.attemptId().equals(candidate.attemptId())
          || !manifest
              .executionSlotId()
              .equals(candidate.executionSlotId())
          || manifest.experiment().repetition()
              != candidate.repetition()
          || !child.runId().equals(candidate.childRunId())
          || !child.task().id().equals(
              candidate.childTaskId())
          || !child.trace().rootHash().equals(
              candidate.traceRootHash())
          || !child.task().outputSchema().equals(
              candidate.outputSchema())
          || !requiredRef.equals(
              candidate.requiredEvidenceRef())
          || source == null
          || candidate.sourceRequestOrdinal()
              != source.requestOrdinal()
          || !candidate
              .sourceResponseHash()
              .equals(source.responseHash())
          || !candidate
              .contentHash()
              .equals(structuredFinalContentHash)
          || !candidate
              .obtainedEvidenceRefs()
              .equals(child.result().evidenceRefs())
          || candidate.requiredEvidenceAvailable()
              != child
                  .result()
                  .evidenceRefs()
                  .contains(requiredRef)) {
        throw new IllegalArgumentException(
            "Candidate does not match its exact graph child");
      }
    } else if (structuredFinalContentHash != null
        && !"INVALID_STRUCTURED_FINAL".equals(
            child.result().failureReason())) {
      throw new IllegalArgumentException(
          "a valid structured final requires its exact Candidate");
    }
    if (child.result().status() == RunStatus.SUCCEEDED) {
      if (candidate == null
          || workerResult == null
          || groundingFailure(candidate) != null
          || !workerResult.workerResultRef().equals(
              binding.effectRef())
          || !workerResult.integrityHash().equals(
              binding.effectHash())
          || !workerResult.childRunId().equals(child.runId())
          || !workerResult
              .childTaskId()
              .equals(child.task().id())
          || !workerResult
              .outputSchema()
              .equals(candidate.outputSchema())
          || !workerResult.content().equals(
              candidate.content())
          || !workerResult.contentHash().equals(
              candidate.contentHash())
          || !workerResult.evidenceRefs().equals(
              candidate.evidenceRefs())) {
        throw new IllegalArgumentException(
            "successful child does not bind its exact Candidate");
      }
      return;
    }
    if (workerResult != null || binding.effectRef() != null) {
      throw new IllegalArgumentException(
          "non-success child cannot expose a Worker Result");
    }
    String failure = child.result().failureReason();
    boolean candidateRequired =
        List.of(
                "MISSING_REQUIRED_EVIDENCE",
                "UNSAFE_EVIDENCE_BINDING",
                "INVALID_EVIDENCE_CLAIM",
                "REQUIRED_EVIDENCE_NOT_FOUND")
            .contains(failure);
    if ((candidateRequired && candidate == null)
        || (candidate != null
            && !Objects.equals(
                groundingFailure(candidate), failure))) {
      throw new IllegalArgumentException(
          "rejected Candidate does not match child failure");
    }
  }

  private static void requireParentTruth(
      GraphAttemptManifest manifest,
      AgentRun parent,
      GraphTerminalBinding binding,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact) {
    if (parent.result().status() == RunStatus.SUCCEEDED) {
      if (candidate == null
          || workerResult == null
          || artifact == null
          || !manifest.artifactId().equals(
              artifact.artifactId())
          || !manifest.principalId().equals(
              artifact.principalId())
          || !manifest.captureId().equals(
              artifact.sourceCaptureId())
          || !artifact.current().content().equals(
              candidate.content())
          || !artifact.current().contentHash().equals(
              candidate.contentHash())
          || !workerResult.contentHash().equals(
              artifact.current().contentHash())
          || !binding.effectRef().equals(
              "artifact-version://"
                  + artifact.artifactId()
                  + "/"
                  + artifact.current().version())
          || !binding.effectHash().equals(
              artifact.current().contentHash())) {
        throw new IllegalArgumentException(
            "successful parent does not bind its exact Artifact");
      }
    } else if (artifact != null || binding.effectRef() != null) {
      throw new IllegalArgumentException(
          "non-success parent cannot expose an Artifact");
    }
  }

  private static String groundingFailure(
      HarnessCandidateEnvelope candidate) {
    ReferenceGroundingPolicy.Failure failure =
        ReferenceGroundingPolicy.failure(
            candidate.evidenceRefs(),
            candidate.obtainedEvidenceRefs(),
            candidate.requiredEvidenceRef(),
            candidate.requiredEvidenceAvailable());
    return failure == null ? null : failure.code();
  }

  private static void requireParentChildTruth(
      AgentRun parent, AgentRun child) {
    String childRef = "agent-run://" + child.runId();
    List<io.emergeos.contracts.ResourceBinding> handoffs =
        parent.bundle().resourceBindings().stream()
            .filter(
                binding ->
                    binding.role() == ResourceRole.HANDOFF)
            .toList();
    List<io.emergeos.contracts.AgentTraceEntry> requests =
        parent.trace().events().stream()
            .filter(
                event ->
                    event.type() == TraceEventType.HANDOFF_REQUEST)
            .toList();
    List<io.emergeos.contracts.AgentTraceEntry> completions =
        parent.trace().events().stream()
            .filter(
                event ->
                    event.type() == TraceEventType.HANDOFF_RESULT
                        || event.type()
                            == TraceEventType.HANDOFF_REJECTED)
            .toList();
    if (!parent.bundle().handoffRefs().equals(List.of(childRef))
        || handoffs.size() != 1
        || handoffs.getFirst().ordinal() != 0
        || !childRef.equals(handoffs.getFirst().ref())
        || !child.bundle().integrityHash().equals(
            handoffs.getFirst().contentHash())
        || requests.size() != 1
        || !childRef.equals(requests.getFirst().reference())
        || completions.size() != 1
        || !childRef.equals(
            completions.getFirst().reference())
        || !completionMatches(
            parent.result().status(),
            parent.result().failureReason(),
            child.result().status(),
            completions.getFirst())
        || parent
                .result()
                .costUsd()
                .compareTo(child.result().costUsd())
            != 0
        || parent.result().tokenCount()
            != child.result().tokenCount()) {
      throw new IllegalArgumentException(
          "parent terminal truth does not bind its exact child");
    }
  }

  private static boolean completionMatches(
      RunStatus parentStatus,
      String parentFailure,
      RunStatus childStatus,
      io.emergeos.contracts.AgentTraceEntry completion) {
    if (childStatus == RunStatus.SUCCEEDED) {
      return completion.type() == TraceEventType.HANDOFF_RESULT
          && parentStatus == RunStatus.SUCCEEDED
          && parentFailure == null;
    }
    if (completion.type() != TraceEventType.HANDOFF_REJECTED) {
      return false;
    }
    return switch (childStatus) {
      case FAILED ->
          parentStatus == RunStatus.FAILED
              && "HANDOFF_CHILD_FAILED".equals(parentFailure)
              && "CHILD_FAILED".equals(completion.status());
      case BLOCKED ->
          parentStatus == RunStatus.BLOCKED
              && "HANDOFF_CHILD_BLOCKED".equals(parentFailure)
              && "CHILD_BLOCKED".equals(completion.status());
      case NEEDS_INPUT ->
          parentStatus == RunStatus.NEEDS_INPUT
              && "HANDOFF_CHILD_NEEDS_INPUT".equals(parentFailure)
              && "CHILD_NEEDS_INPUT".equals(completion.status());
      case CANCELLED ->
          parentStatus == RunStatus.CANCELLED
              && "HANDOFF_CHILD_CANCELLED".equals(parentFailure)
              && "CHILD_CANCELLED".equals(completion.status());
      case SUCCEEDED -> false;
    };
  }

  private static void requireRun(
      GraphRunSelection selection,
      AgentRun run,
      boolean mustExist,
      boolean mustBeTerminal) {
    if (!mustExist) {
      if (run != null) {
        throw new IllegalArgumentException(
            "graph Run exists before its STARTED event");
      }
      return;
    }
    if (run == null
        || (mustBeTerminal
            ? !run.lifecycle().terminal()
            : run.lifecycle() != AgentRunLifecycle.RUNNING)
        || !selection.runId().equals(run.runId())
        || !selection.taskId().equals(run.task().id())
        || !selection.taskHash().equals(
            IntegrityHashes.taskHash(run.task()))) {
      throw new IllegalArgumentException(
          "graph Run does not match its exact durable selection");
    }
  }

  private static GraphTerminalBinding binding(
      List<GraphTerminalBinding> bindings, GraphRunRole role) {
    List<GraphTerminalBinding> matching =
        bindings.stream()
            .filter(binding -> binding.role() == role)
            .toList();
    if (matching.size() > 1) {
      throw new IllegalArgumentException(
          "duplicate graph terminal binding");
    }
    return matching.isEmpty() ? null : matching.getFirst();
  }

  private static GraphAttemptEvent event(
      List<GraphAttemptEvent> events,
      GraphAttemptEventType type) {
    List<GraphAttemptEvent> matching =
        events.stream()
            .filter(event -> event.type() == type)
            .toList();
    if (matching.size() > 1
        && type
            != GraphAttemptEventType.PROVIDER_INTENT
        && type
            != GraphAttemptEventType.PROVIDER_ATTRIBUTED) {
      throw new IllegalArgumentException(
          "duplicate graph terminal event");
    }
    return matching.isEmpty() ? null : matching.getLast();
  }

  private static boolean contains(
      List<GraphAttemptEvent> events,
      GraphAttemptEventType type) {
    return events.stream().anyMatch(event -> event.type() == type);
  }
}
