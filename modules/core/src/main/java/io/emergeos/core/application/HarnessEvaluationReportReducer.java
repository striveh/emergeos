package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.HarnessEvaluationReport;
import io.emergeos.contracts.HarnessEvaluationReport.BillingStatus;
import io.emergeos.contracts.HarnessEvaluationReport.Evaluation;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorArm;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorEffects;
import io.emergeos.contracts.HarnessEvaluationReport.GraphOutcome;
import io.emergeos.contracts.HarnessEvaluationReport.ProviderAttributionWitness;
import io.emergeos.contracts.HarnessEvaluationReport.RepetitionReport;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalRunWitness;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalSealWitness;
import io.emergeos.contracts.HarnessEvaluationReport.UsageAggregate;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.GraphTerminalSeal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure complete-only reduction of three already verified terminal graph snapshots. */
final class HarnessEvaluationReportReducer {

  private static final String PACK010_PROVIDER_ACTOR =
      "OPENAI_RESPONSES";
  private static final String PACK010_PROVIDER = "openai.responses";
  private static final String PACK010_MODEL = "gpt-5.6-terra";

  private HarnessEvaluationReportReducer() {}

  static void requireReportEligible(
      GraphAttemptSnapshot snapshot,
      GraphAttemptManifest expected,
      int repetition) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(expected, "expected");
    if (!snapshot.manifest().equals(expected)) {
      throw failure("REPETITION_MISMATCH", repetition);
    }
    AgentRun parent = snapshot.parentRun();
    AgentRun child = snapshot.childRun();
    GraphTerminalSeal seal = snapshot.terminalSeal();
    if (!GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION.equals(
            expected.graphProtocolVersion())
        || expected.maximumProviderRequests() != 2
        || snapshot.cursor().lastSequence() != 17
        || snapshot.cursor().phase() != GraphAttemptPhase.TERMINAL
        || !snapshot.terminalSealPresent()
        || seal == null
        || seal.finalSequence() != 17
        || !seal.finalHeadHash().equals(snapshot.cursor().headHash())
        || snapshot.outcome() == GraphAttemptOutcome.INCOMPLETE
        || snapshot.billingStatus() != GraphBillingStatus.ATTRIBUTED) {
      throw failure("REPETITION_NOT_COMPLETE", repetition);
    }
    List<GraphProviderAttribution> attributions =
        snapshot.providerAttributions();
    if (snapshot.candidate() == null
        || parent == null
        || child == null
        || parent.lifecycle() == AgentRunLifecycle.RUNNING
        || child.lifecycle() == AgentRunLifecycle.RUNNING
        || parent.completedAt() == null
        || child.completedAt() == null
        || parent.task().dataClass() != DataClass.PUBLIC
        || child.task().dataClass() != DataClass.PUBLIC
        || !"1.0".equals(parent.task().schemaVersion())
        || !"1.1".equals(child.task().schemaVersion())
        || parent.task().allowParallel()
        || child.task().allowParallel()
        || parent.task().parentId() != null
        || !parent.task().delegationChain().isEmpty()
        || parent.task().modelProvider() != null
        || parent.task().modelRequested() != null
        || parent.task().pricingProfile() != null
        || !expected.principalId().equals(parent.principalId())
        || !expected.principalId().equals(child.principalId())
        || !Objects.equals(child.task().parentId(), parent.task().id())
        || !child.task().delegationChain().equals(
            List.of(parent.task().id()))
        || !expected.experiment().equals(parent.bundle().experiment())
        || !expected.experiment().equals(child.bundle().experiment())
        || !selectionProfilesMatchBundles(expected, parent, child)
        || !PACK010_PROVIDER_ACTOR.equals(expected.childActor())
        || !PACK010_PROVIDER.equals(child.task().modelProvider())
        || !PACK010_MODEL.equals(child.task().modelRequested())
        || attributions.size() != 2
        || attributions.get(0).requestOrdinal() != 1
        || attributions.get(1).requestOrdinal() != 2
        || !expected.childActor().equals(
            attributions.get(0).providerActor())
        || !expected.childActor().equals(
            attributions.get(1).providerActor())
        || !Objects.equals(
            child.task().modelRequested(),
            attributions.get(0).modelRequested())
        || !Objects.equals(
            child.task().modelRequested(),
            attributions.get(1).modelRequested())
        || !Objects.equals(
            child.task().pricingProfile(),
            attributions.get(0).pricing().id())
        || !Objects.equals(
            child.task().pricingProfile(),
            attributions.get(1).pricing().id())
        || !PACK010_PROVIDER.equals(
            attributions.get(0).pricing().provider())
        || !PACK010_PROVIDER.equals(
            attributions.get(1).pricing().provider())
        || !PACK010_MODEL.equals(
            attributions.get(0).pricing().modelRequested())
        || !PACK010_MODEL.equals(
            attributions.get(1).pricing().modelRequested())
        || !expected.pricingProfileFingerprint().equals(
            attributions.get(0).pricingProfileFingerprint())
        || !expected.pricingProfileFingerprint().equals(
            attributions.get(1).pricingProfileFingerprint())
        || !attributions.get(0).modelResolved().equals(
            attributions.get(1).modelResolved())
        || !safePricing(attributions.get(0))
        || !safePricing(attributions.get(1))
        || !microsecondAligned(parent.startedAt())
        || !microsecondAligned(parent.completedAt())
        || !microsecondAligned(child.startedAt())
        || !microsecondAligned(child.completedAt())
        || !microsecondAligned(seal.sealedAt())
        || snapshot.terminalBindings().size() != 2
        || binding(snapshot, GraphRunRole.PARENT) == null
        || binding(snapshot, GraphRunRole.CHILD) == null) {
      throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
    }
  }

  static HarnessEvaluationReport reduce(
      List<GraphAttemptSnapshot> snapshots) {
    Objects.requireNonNull(snapshots, "snapshots");
    if (snapshots.size() != 3) {
      throw failure("REPORT_REDUCTION_INVALID", 0);
    }

    List<RepetitionReport> repetitions = new ArrayList<>(3);
    List<Evaluation> evaluations = new ArrayList<>(6);
    long providerRequests = 0;
    long inputTokens = 0;
    long cachedInputTokens = 0;
    long outputTokens = 0;
    long reasoningOutputTokens = 0;
    long totalTokens = 0;
    long sharedCandidateInputs = 0;
    BigDecimal observedCostUsd = BigDecimal.ZERO;

    for (int index = 0; index < snapshots.size(); index++) {
      int repetition = index + 1;
      GraphAttemptSnapshot snapshot = snapshots.get(index);
      requireReportEligible(snapshot, snapshot.manifest(), repetition);

      sharedCandidateInputs =
          safeAggregateAdd(sharedCandidateInputs, 1, repetition);
      SharedCandidateHarnessEvaluator.EvaluationPair pair =
          SharedCandidateHarnessEvaluator.evaluate(snapshot.candidate());
      requireEvaluatorConvergence(snapshot, pair, repetition);
      List<ProviderAttributionWitness> attributions =
          snapshot.providerAttributions().stream()
              .map(HarnessEvaluationReportReducer::attributionWitness)
              .toList();
      repetitions.add(repetitionReport(snapshot, attributions));
      evaluations.add(pair.h0());
      evaluations.add(pair.h1());

      for (ProviderAttributionWitness attribution : attributions) {
        providerRequests =
            safeAggregateAdd(providerRequests, 1, repetition);
        inputTokens =
            safeAggregateAdd(
                inputTokens, attribution.inputTokens(), repetition);
        cachedInputTokens =
            safeAggregateAdd(
                cachedInputTokens,
                attribution.cachedInputTokens(),
                repetition);
        outputTokens =
            safeAggregateAdd(
                outputTokens, attribution.outputTokens(), repetition);
        reasoningOutputTokens =
            safeAggregateAdd(
                reasoningOutputTokens,
                attribution.reasoningOutputTokens(),
                repetition);
        totalTokens =
            safeAggregateAdd(
                totalTokens, attribution.totalTokens(), repetition);
        observedCostUsd =
            observedCostUsd.add(attribution.observedCostUsd());
        if (observedCostUsd.compareTo(ContractValueDomains.MAX_USD) > 0
            || observedCostUsd.scale()
                > ContractValueDomains.USD_MAX_SCALE) {
          throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
        }
      }
    }

    GraphAttemptManifest first = snapshots.getFirst().manifest();
    return HarnessEvaluationReport.create(
        HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION,
        first.packRawSha256(),
        first.environmentRawSha256(),
        List.of(
            new EvaluatorArm(
                HarnessEvaluationReport.H0_ARM_ID,
                HarnessEvaluationReport.H0_EVALUATOR_VERSION),
            new EvaluatorArm(
                HarnessEvaluationReport.H1_ARM_ID,
                HarnessEvaluationReport.H1_EVALUATOR_VERSION)),
        repetitions,
        evaluations,
        new UsageAggregate(
            providerRequests,
            inputTokens,
            cachedInputTokens,
            outputTokens,
            reasoningOutputTokens,
            totalTokens,
            observedCostUsd),
        new EvaluatorEffects(
            sharedCandidateInputs,
            evaluations.size(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0));
  }

  private static void requireEvaluatorConvergence(
      GraphAttemptSnapshot snapshot,
      SharedCandidateHarnessEvaluator.EvaluationPair pair,
      int repetition) {
    if (pair.h0().status() != EvaluationStatus.ACCEPTED
        || pair.h0().failureCode() != null) {
      throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
    }
    Evaluation h1 = pair.h1();
    AgentRun child = snapshot.childRun();
    AgentRun parent = snapshot.parentRun();
    if (h1.status() == EvaluationStatus.ACCEPTED) {
      if (snapshot.outcome() != GraphAttemptOutcome.SUCCEEDED
          || child.result().status() != RunStatus.SUCCEEDED
          || parent.result().status() != RunStatus.SUCCEEDED
          || snapshot.workerResult() == null
          || snapshot.artifact() == null) {
        throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
      }
      return;
    }
    if (snapshot.outcome() != GraphAttemptOutcome.FAILED
        || child.result().status() != RunStatus.FAILED
        || parent.result().status() != RunStatus.FAILED
        || !Objects.equals(
            child.result().failureReason(), h1.failureCode())
        || !"HANDOFF_CHILD_FAILED".equals(
            parent.result().failureReason())
        || snapshot.workerResult() != null
        || snapshot.artifact() != null) {
      throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
    }
  }

  private static RepetitionReport repetitionReport(
      GraphAttemptSnapshot snapshot,
      List<ProviderAttributionWitness> attributions) {
    GraphAttemptManifest manifest = snapshot.manifest();
    GraphTerminalBinding parentBinding =
        binding(snapshot, GraphRunRole.PARENT);
    GraphTerminalBinding childBinding =
        binding(snapshot, GraphRunRole.CHILD);
    ResourceBinding artifactBinding =
        exactBinding(snapshot.parentRun(), ResourceRole.ARTIFACT);
    return new RepetitionReport(
        manifest.experiment().repetition(),
        manifest.executionSlotId(),
        manifest.attemptId(),
        manifest.manifestHash(),
        attributions,
        runWitness(snapshot.parentRun(), parentBinding),
        runWitness(snapshot.childRun(), childBinding),
        snapshot.candidate(),
        snapshot.workerResult(),
        artifactBinding,
        sealWitness(snapshot.terminalSeal()));
  }

  private static ProviderAttributionWitness attributionWitness(
      GraphProviderAttribution attribution) {
    return new ProviderAttributionWitness(
        attribution.requestOrdinal(),
        attribution.requestHash(),
        attribution.responseHash(),
        attribution.providerActor(),
        attribution.modelRequested(),
        attribution.modelResolved(),
        attribution.pricing().id(),
        attribution.pricing().provider(),
        attribution.pricing().modelRequested(),
        attribution.pricing().uncachedInputNanoUsdPerToken(),
        attribution.pricing().cachedInputNanoUsdPerToken(),
        attribution.pricing().outputNanoUsdPerToken(),
        attribution.pricing().fingerprint(),
        attribution.inputTokens(),
        attribution.cachedInputTokens(),
        attribution.outputTokens(),
        attribution.reasoningOutputTokens(),
        attribution.totalTokens(),
        attribution.observedCostUsd(),
        attribution.attributionHash());
  }

  private static TerminalRunWitness runWitness(
      AgentRun run, GraphTerminalBinding binding) {
    if (binding == null) {
      throw failure("REPORT_REDUCTION_INVALID", 0);
    }
    return new TerminalRunWitness(
        run.startedAt(),
        run.completedAt(),
        run.bundle(),
        binding.terminalHash());
  }

  private static TerminalSealWitness sealWitness(
      GraphTerminalSeal seal) {
    GraphOutcome outcome =
        switch (seal.graphOutcome()) {
          case SUCCEEDED -> GraphOutcome.SUCCEEDED;
          case FAILED -> GraphOutcome.FAILED;
          case INCOMPLETE ->
              throw new IllegalStateException(
                  "report-eligible seal cannot be INCOMPLETE");
        };
    BillingStatus billing =
        switch (seal.billingStatus()) {
          case ATTRIBUTED -> BillingStatus.ATTRIBUTED;
          case NOT_INVOKED, UNKNOWN ->
              throw new IllegalStateException(
                  "report-eligible seal must be ATTRIBUTED");
        };
    return new TerminalSealWitness(
        seal.attemptId(),
        seal.manifestHash(),
        seal.finalSequence(),
        seal.preSealHeadHash(),
        seal.finalHeadHash(),
        outcome,
        billing,
        seal.providerAttributionHashes(),
        seal.candidateRef(),
        seal.candidateIntegrityHash(),
        seal.childTerminalHash(),
        seal.parentTerminalHash(),
        seal.sealHash(),
        seal.sealedAt());
  }

  private static GraphTerminalBinding binding(
      GraphAttemptSnapshot snapshot, GraphRunRole role) {
    List<GraphTerminalBinding> matching =
        snapshot.terminalBindings().stream()
            .filter(binding -> binding.role() == role)
            .toList();
    return matching.size() == 1 ? matching.getFirst() : null;
  }

  private static ResourceBinding exactBinding(
      AgentRun run, ResourceRole role) {
    List<ResourceBinding> matching =
        run.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == role)
            .toList();
    return matching.size() == 1 ? matching.getFirst() : null;
  }

  private static boolean safePricing(
      GraphProviderAttribution attribution) {
    return attribution.pricing().uncachedInputNanoUsdPerToken()
            <= ContractValueDomains.MAX_SAFE_INTEGER
        && attribution.pricing().cachedInputNanoUsdPerToken()
            <= ContractValueDomains.MAX_SAFE_INTEGER
        && attribution.pricing().outputNanoUsdPerToken()
            <= ContractValueDomains.MAX_SAFE_INTEGER;
  }

  private static boolean selectionProfilesMatchBundles(
      GraphAttemptManifest manifest, AgentRun parent, AgentRun child) {
    GraphRunSelection parentSelection = manifest.parentSelection();
    GraphRunSelection childSelection = manifest.childSelection();
    var parentComponents = parent.bundle().componentVersions();
    var childComponents = child.bundle().componentVersions();
    return childSelection.executionProfileId().equals(
            childComponents.get("execution-profile"))
        && childSelection.executionProfileFingerprint().equals(
            childComponents.get("execution-profile-fingerprint"))
        && childSelection.workerRegistryVersion().equals(
            childComponents.get("worker-registry"))
        && childSelection.workerProfileFingerprint().equals(
            childComponents.get("worker-profile-fingerprint"))
        && childSelection.workerProfileId().equals(
            childSelection.executionProfileId())
        && parentSelection.workerRegistryVersion().equals(
            childSelection.workerRegistryVersion())
        && parentSelection.workerProfileId().equals(
            childSelection.workerProfileId())
        && parentSelection.workerProfileFingerprint().equals(
            childSelection.workerProfileFingerprint())
        && parentSelection.workerRegistryVersion().equals(
            parentComponents.get("worker-registry"))
        && parentSelection.workerProfileFingerprint().equals(
            parentComponents.get("worker-profile-fingerprint"))
        && manifest.pricingProfileFingerprint().equals(
            childComponents.get("pricing-profile-fingerprint"))
        && childComponents.containsKey("model-adapter")
        && !parentComponents.containsKey("model-adapter");
  }

  private static boolean microsecondAligned(java.time.Instant instant) {
    return instant != null && instant.getNano() % 1_000 == 0;
  }

  private static long safeAggregateAdd(
      long left, long right, int repetition) {
    try {
      long sum = Math.addExact(left, right);
      if (sum > ContractValueDomains.MAX_SAFE_INTEGER) {
        throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
      }
      return sum;
    } catch (ArithmeticException overflow) {
      throw failure("REPETITION_NOT_REPORT_ELIGIBLE", repetition);
    }
  }

  private static ReductionFailure failure(
      String reasonCode, int repetition) {
    return new ReductionFailure(reasonCode, repetition);
  }

  static final class ReductionFailure extends RuntimeException {
    private final String reasonCode;
    private final int repetition;

    private ReductionFailure(String reasonCode, int repetition) {
      super(reasonCode);
      this.reasonCode = reasonCode;
      this.repetition = repetition;
    }

    String reasonCode() {
      return reasonCode;
    }

    int repetition() {
      return repetition;
    }
  }
}
