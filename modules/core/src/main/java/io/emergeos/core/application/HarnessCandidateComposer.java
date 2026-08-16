package io.emergeos.core.application;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.AgentTraceProtocol;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphRunSelection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Composes one Harness Candidate from already durable child and provider truth.
 *
 * <p>The caller cannot provide a response hash, trace root, output schema or
 * obtained-evidence list independently: those values are derived from the
 * exact attributed request and terminal child Run. This class performs no
 * model, Tool, credential, network or persistence operation.
 */
public final class HarnessCandidateComposer {

  private HarnessCandidateComposer() {}

  public static HarnessCandidateEnvelope compose(
      GraphAttemptManifest manifest,
      AgentRun terminalChild,
      AgentDraftProposal structuredFinal,
      List<GraphProviderAttribution> providerAttributions) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(terminalChild, "terminalChild");
    Objects.requireNonNull(structuredFinal, "structuredFinal");
    List<GraphProviderAttribution> attributions =
        List.copyOf(
            Objects.requireNonNull(
                providerAttributions, "providerAttributions"));

    TaskEnvelope task = terminalChild.task();
    GraphRunSelection selection = manifest.childSelection();
    String requiredEvidenceRef =
        "capture://" + manifest.captureId();
    if (terminalChild.lifecycle() == AgentRunLifecycle.RUNNING
        || terminalChild.result() == null
        || terminalChild.trace() == null
        || terminalChild.bundle() == null
        || terminalChild.completedAt() == null
        || !manifest.principalId().equals(terminalChild.principalId())
        || !manifest.principalId().equals(task.principalRef())
        || !selection.runId().equals(terminalChild.runId())
        || !selection.taskId().equals(task.id())
        || !selection.taskHash().equals(IntegrityHashes.taskHash(task))
        || !manifest.experiment().equals(
            terminalChild.bundle().experiment())
        || !terminalChild.runId().equals(
            terminalChild.bundle().runId())
        || !task.id().equals(terminalChild.bundle().taskId())
        || !task.inputRefs().equals(List.of(requiredEvidenceRef))
        || task.modelProvider() == null
        || task.modelRequested() == null
        || task.pricingProfile() == null) {
      throw new IllegalArgumentException(
          "terminal child does not match the Harness manifest");
    }
    requireProfileBindings(manifest, terminalChild, selection);
    GraphProviderAttribution source =
        requireExactProviderTruth(manifest, terminalChild, attributions);

    String structuredFinalContentHash =
        AgentTraceProtocol.structuredFinalProposalContentHash(
            terminalChild.trace());
    if (structuredFinalContentHash == null
        || !structuredFinalContentHash.equals(
            IntegrityHashes.utf8ContentHash(
                structuredFinal.content()))) {
      throw new IllegalArgumentException(
          "structured final does not match the terminal Trace");
    }
    requireExactEvidenceBinding(
        manifest, terminalChild, requiredEvidenceRef);

    return HarnessCandidateEnvelope.create(
        manifest.attemptId(),
        manifest.executionSlotId(),
        manifest.experiment().repetition(),
        terminalChild.runId(),
        task.id(),
        source.responseHash(),
        terminalChild.trace().rootHash(),
        task.outputSchema(),
        structuredFinal.content(),
        structuredFinal.evidenceRefs(),
        terminalChild.result().evidenceRefs(),
        requiredEvidenceRef,
        terminalChild
            .result()
            .evidenceRefs()
            .contains(requiredEvidenceRef));
  }

  private static void requireProfileBindings(
      GraphAttemptManifest manifest,
      AgentRun terminalChild,
      GraphRunSelection selection) {
    var components = terminalChild.bundle().componentVersions();
    if (!selection.executionProfileId().equals(
            components.get("execution-profile"))
        || !selection.executionProfileFingerprint().equals(
            components.get("execution-profile-fingerprint"))
        || !selection.workerRegistryVersion().equals(
            components.get("worker-registry"))
        || !selection.workerProfileId().equals(
            selection.executionProfileId())
        || !selection.workerProfileFingerprint().equals(
            components.get("worker-profile-fingerprint"))
        || !manifest.pricingProfileFingerprint().equals(
            components.get("pricing-profile-fingerprint"))) {
      throw new IllegalArgumentException(
          "terminal child profile binding is invalid");
    }
  }

  private static GraphProviderAttribution requireExactProviderTruth(
      GraphAttemptManifest manifest,
      AgentRun terminalChild,
      List<GraphProviderAttribution> attributions) {
    if (attributions.size() != 2) {
      throw new IllegalArgumentException(
          "Harness Candidate requires exactly two provider attributions");
    }
    GraphProviderAttribution first = attributions.get(0);
    GraphProviderAttribution second = attributions.get(1);
    TaskEnvelope task = terminalChild.task();
    if (first.requestOrdinal() != 1
        || second.requestOrdinal() != 2
        || first.requestHash().equals(second.requestHash())
        || !first.providerActor().equals(manifest.childActor())
        || !second.providerActor().equals(manifest.childActor())
        || !first.modelRequested().equals(task.modelRequested())
        || !second.modelRequested().equals(task.modelRequested())
        || !first.modelResolved().equals(second.modelResolved())
        || !first.modelResolved().equals(
            terminalChild.result().resolvedModel())
        || !first.pricing().provider().equals(task.modelProvider())
        || !second.pricing().provider().equals(task.modelProvider())
        || !first.pricing().id().equals(task.pricingProfile())
        || !second.pricing().id().equals(task.pricingProfile())
        || !first.pricingProfileFingerprint().equals(
            manifest.pricingProfileFingerprint())
        || !second.pricingProfileFingerprint().equals(
            manifest.pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider attribution does not match the terminal child");
    }
    long tokenCount;
    try {
      tokenCount =
          Math.addExact(first.totalTokens(), second.totalTokens());
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "provider attribution aggregate overflows", overflow);
    }
    BigDecimal costUsd =
        first.observedCostUsd().add(second.observedCostUsd());
    if (tokenCount != terminalChild.result().tokenCount()
        || costUsd.compareTo(terminalChild.result().costUsd()) != 0) {
      throw new IllegalArgumentException(
          "provider attribution aggregate does not match child Result");
    }
    return second;
  }

  private static void requireExactEvidenceBinding(
      GraphAttemptManifest manifest,
      AgentRun terminalChild,
      String requiredEvidenceRef) {
    List<ResourceBinding> evidence =
        terminalChild.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.EVIDENCE)
            .toList();
    if (evidence.size() != 1
        || evidence.getFirst().ordinal() != 0
        || !requiredEvidenceRef.equals(evidence.getFirst().ref())
        || !manifest
            .captureRequestHash()
            .equals(evidence.getFirst().contentHash())
        || !terminalChild
            .result()
            .evidenceRefs()
            .equals(List.of(requiredEvidenceRef))) {
      throw new IllegalArgumentException(
          "terminal child does not bind the exact durable Evidence");
    }
  }
}
