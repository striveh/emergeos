package io.emergeos.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete-only public evidence for the three-repetition shared-Candidate Harness pilot.
 *
 * <p>This contract never represents a partial run, a replacement repetition or a statistical model
 * conclusion. Candidate and Worker Result content remain available to independent verifiers but are
 * redacted from {@link #toString()}.
 */
public record HarnessEvaluationReport(
    String schemaVersion,
    String reportKind,
    String reportId,
    String graphProtocolVersion,
    String packRawSha256,
    String environmentRawSha256,
    List<EvaluatorArm> evaluatorArms,
    List<RepetitionReport> repetitions,
    List<Evaluation> evaluations,
    UsageAggregate usageAggregate,
    EvaluatorEffects evaluatorEffects,
    ReportStatus reportStatus,
    String integrityProfile,
    String integrityHash) {

  public static final String SCHEMA_VERSION = "1.0";
  public static final String REPORT_KIND =
      "LIVE_MODEL_SHARED_CANDIDATE_VERIFIER_PILOT";
  public static final String GRAPH_PROTOCOL_VERSION =
      "postgres-graph-terminal-v1";
  public static final String H0_ARM_ID = "h0-schema-only";
  public static final String H0_EVALUATOR_VERSION =
      "schema-only-eval-v1";
  public static final String H1_ARM_ID =
      "h1-reference-grounding";
  public static final String H1_EVALUATOR_VERSION =
      "agent-draft-verifier-v1";

  public HarnessEvaluationReport {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "HarnessEvaluationReport supports schemaVersion 1.0");
    }
    if (!REPORT_KIND.equals(reportKind)) {
      throw new IllegalArgumentException(
          "unsupported HarnessEvaluationReport kind");
    }
    if (!GRAPH_PROTOCOL_VERSION.equals(graphProtocolVersion)) {
      throw new IllegalArgumentException(
          "HarnessEvaluationReport requires the terminal graph protocol");
    }
    requireHash(packRawSha256, "packRawSha256");
    requireHash(environmentRawSha256, "environmentRawSha256");
    evaluatorArms =
        List.copyOf(
            Objects.requireNonNull(evaluatorArms, "evaluatorArms"));
    repetitions =
        List.copyOf(Objects.requireNonNull(repetitions, "repetitions"));
    evaluations =
        List.copyOf(Objects.requireNonNull(evaluations, "evaluations"));
    usageAggregate =
        Objects.requireNonNull(usageAggregate, "usageAggregate");
    evaluatorEffects =
        Objects.requireNonNull(evaluatorEffects, "evaluatorEffects");
    if (reportStatus != ReportStatus.COMPLETE) {
      throw new IllegalArgumentException(
          "HarnessEvaluationReport is complete-only");
    }
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException(
          "unsupported HarnessEvaluationReport integrityProfile");
    }
    verifyCompleteProtocol(
        environmentRawSha256,
        evaluatorArms,
        repetitions,
        evaluations,
        usageAggregate,
        evaluatorEffects);
    requireHash(integrityHash, "integrityHash");
    String expectedReportId =
        IntegrityHashes.harnessEvaluationReportId(
            idPreimage(
                schemaVersion,
                reportKind,
                graphProtocolVersion,
                packRawSha256,
                environmentRawSha256,
                evaluatorArms,
                repetitions,
                evaluations,
                usageAggregate,
                evaluatorEffects,
                reportStatus,
                integrityProfile));
    if (!expectedReportId.equals(reportId)) {
      throw new IllegalArgumentException(
          "reportId does not match HarnessEvaluationReport");
    }
    String expectedHash =
        IntegrityHashes.harnessEvaluationReportHash(
            preimage(
                schemaVersion,
                reportKind,
                reportId,
                graphProtocolVersion,
                packRawSha256,
                environmentRawSha256,
                evaluatorArms,
                repetitions,
                evaluations,
                usageAggregate,
                evaluatorEffects,
                reportStatus,
                integrityProfile));
    if (!expectedHash.equals(integrityHash)) {
      throw new IllegalArgumentException(
          "integrityHash does not match HarnessEvaluationReport");
    }
  }

  public static HarnessEvaluationReport create(
      String graphProtocolVersion,
      String packRawSha256,
      String environmentRawSha256,
      List<EvaluatorArm> evaluatorArms,
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usageAggregate,
      EvaluatorEffects evaluatorEffects) {
    String schemaVersion = SCHEMA_VERSION;
    String reportKind = REPORT_KIND;
    ReportStatus reportStatus = ReportStatus.COMPLETE;
    String integrityProfile = IntegrityHashes.PROFILE;
    String reportId =
        IntegrityHashes.harnessEvaluationReportId(
            idPreimage(
                schemaVersion,
                reportKind,
                graphProtocolVersion,
                packRawSha256,
                environmentRawSha256,
                evaluatorArms,
                repetitions,
                evaluations,
                usageAggregate,
                evaluatorEffects,
                reportStatus,
                integrityProfile));
    String integrityHash =
        IntegrityHashes.harnessEvaluationReportHash(
            preimage(
                schemaVersion,
                reportKind,
                reportId,
                graphProtocolVersion,
                packRawSha256,
                environmentRawSha256,
                evaluatorArms,
                repetitions,
                evaluations,
                usageAggregate,
                evaluatorEffects,
                reportStatus,
                integrityProfile));
    return new HarnessEvaluationReport(
        schemaVersion,
        reportKind,
        reportId,
        graphProtocolVersion,
        packRawSha256,
        environmentRawSha256,
        evaluatorArms,
        repetitions,
        evaluations,
        usageAggregate,
        evaluatorEffects,
        reportStatus,
        integrityProfile,
        integrityHash);
  }

  @Override
  public String toString() {
    return "HarnessEvaluationReport["
        + "reportId="
        + reportId
        + ", graphProtocolVersion="
        + graphProtocolVersion
        + ", packRawSha256="
        + packRawSha256
        + ", environmentRawSha256="
        + environmentRawSha256
        + ", repetitions="
        + repetitions.size()
        + ", evaluations="
        + evaluations.size()
        + ", usageAggregate="
        + usageAggregate
        + ", evaluatorEffects="
        + evaluatorEffects
        + ", reportStatus="
        + reportStatus
        + ", integrityHash="
        + integrityHash
        + "]";
  }

  public record EvaluatorArm(String armId, String evaluatorVersion) {
    public EvaluatorArm {
      requireIdentifier(armId, "armId", 200);
      requireIdentifier(evaluatorVersion, "evaluatorVersion", 200);
    }
  }

  public record ProviderAttributionWitness(
      int requestOrdinal,
      String requestHash,
      String responseHash,
      String providerActor,
      String modelRequested,
      String modelResolved,
      String pricingProfileId,
      String pricingProvider,
      String pricingModelRequested,
      long uncachedInputNanoUsdPerToken,
      long cachedInputNanoUsdPerToken,
      long outputNanoUsdPerToken,
      String pricingProfileFingerprint,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd,
      String attributionHash) {

    public ProviderAttributionWitness {
      if (requestOrdinal < 1 || requestOrdinal > 2) {
        throw new IllegalArgumentException(
            "provider requestOrdinal must be 1 or 2");
      }
      requireHash(requestHash, "requestHash");
      requireHash(responseHash, "responseHash");
      ContractText.require(
          providerActor,
          "providerActor",
          ContractText.MAX_NAME_LENGTH);
      requireModel(modelRequested, "modelRequested");
      requireModel(modelResolved, "modelResolved");
      requireIdentifier(
          pricingProfileId, "pricingProfileId", 200);
      requireIdentifier(pricingProvider, "pricingProvider", 128);
      requireModel(
          pricingModelRequested, "pricingModelRequested");
      if (!modelRequested.equals(pricingModelRequested)
          || uncachedInputNanoUsdPerToken <= 0
          || cachedInputNanoUsdPerToken < 0
          || cachedInputNanoUsdPerToken
              > uncachedInputNanoUsdPerToken
          || outputNanoUsdPerToken <= 0) {
        throw new IllegalArgumentException(
            "provider pricing witness is inconsistent");
      }
      ContractValueDomains.requireSafeCount(
          uncachedInputNanoUsdPerToken,
          "uncachedInputNanoUsdPerToken");
      ContractValueDomains.requireSafeCount(
          cachedInputNanoUsdPerToken,
          "cachedInputNanoUsdPerToken");
      ContractValueDomains.requireSafeCount(
          outputNanoUsdPerToken,
          "outputNanoUsdPerToken");
      requireHash(
          pricingProfileFingerprint,
          "pricingProfileFingerprint");
      ContractValueDomains.requireSafeCount(
          inputTokens, "inputTokens");
      ContractValueDomains.requireSafeCount(
          cachedInputTokens, "cachedInputTokens");
      ContractValueDomains.requireSafeCount(
          outputTokens, "outputTokens");
      ContractValueDomains.requireSafeCount(
          reasoningOutputTokens,
          "reasoningOutputTokens");
      ContractValueDomains.requireSafeCount(
          totalTokens, "totalTokens");
      if (cachedInputTokens > inputTokens
          || reasoningOutputTokens > outputTokens
          || safeAdd(inputTokens, outputTokens, "totalTokens")
              != totalTokens) {
        throw new IllegalArgumentException(
            "provider token witness is inconsistent");
      }
      ContractValueDomains.requireUsd(
          observedCostUsd, "observedCostUsd");
      if (calculatedCost(
                  uncachedInputNanoUsdPerToken,
                  cachedInputNanoUsdPerToken,
                  outputNanoUsdPerToken,
                  inputTokens,
                  cachedInputTokens,
                  outputTokens)
              .compareTo(observedCostUsd)
          != 0) {
        throw new IllegalArgumentException(
            "provider cost witness is inconsistent");
      }
      requireHash(attributionHash, "attributionHash");
    }
  }

  public record TerminalRunWitness(
      Instant startedAt,
      Instant completedAt,
      HarnessRunBundle bundle,
      String terminalHash) {

    public TerminalRunWitness {
      startedAt = requireMicros(startedAt, "startedAt");
      completedAt = requireMicros(completedAt, "completedAt");
      bundle = Objects.requireNonNull(bundle, "bundle");
      if (completedAt.isBefore(startedAt)) {
        throw new IllegalArgumentException(
            "terminal Run completedAt precedes startedAt");
      }
      requireHash(terminalHash, "terminalHash");
    }
  }

  public record TerminalSealWitness(
      String attemptId,
      String manifestHash,
      int finalSequence,
      String preSealHeadHash,
      String finalHeadHash,
      GraphOutcome graphOutcome,
      BillingStatus billingStatus,
      List<String> providerAttributionHashes,
      String candidateRef,
      String candidateIntegrityHash,
      String childTerminalHash,
      String parentTerminalHash,
      String sealHash,
      Instant sealedAt) {

    public TerminalSealWitness {
      requireHash(attemptId, "attemptId");
      requireHash(manifestHash, "manifestHash");
      if (finalSequence != 17) {
        throw new IllegalArgumentException(
            "terminal seal must bind sequence 17");
      }
      requireHash(preSealHeadHash, "preSealHeadHash");
      requireHash(finalHeadHash, "finalHeadHash");
      graphOutcome =
          Objects.requireNonNull(graphOutcome, "graphOutcome");
      if (billingStatus != BillingStatus.ATTRIBUTED) {
        throw new IllegalArgumentException(
            "terminal seal billing must be ATTRIBUTED");
      }
      providerAttributionHashes =
          List.copyOf(
              Objects.requireNonNull(
                  providerAttributionHashes,
                  "providerAttributionHashes"));
      if (providerAttributionHashes.size() != 2) {
        throw new IllegalArgumentException(
            "terminal seal requires exactly two provider attributions");
      }
      providerAttributionHashes.forEach(
          hash -> requireHash(hash, "providerAttributionHash"));
      ContractText.require(candidateRef, "candidateRef");
      requireHash(candidateIntegrityHash, "candidateIntegrityHash");
      requireHash(childTerminalHash, "childTerminalHash");
      requireHash(parentTerminalHash, "parentTerminalHash");
      requireHash(sealHash, "sealHash");
      sealedAt = requireMicros(sealedAt, "sealedAt");
    }
  }

  public record RepetitionReport(
      int repetition,
      String executionSlotId,
      String attemptId,
      String manifestHash,
      List<ProviderAttributionWitness> providerAttributions,
      TerminalRunWitness parentRun,
      TerminalRunWitness childRun,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      ResourceBinding artifactBinding,
      TerminalSealWitness terminalSeal) {

    public RepetitionReport {
      if (repetition < 1 || repetition > 3) {
        throw new IllegalArgumentException(
            "repetition must be 1, 2 or 3");
      }
      requireIdentifier(
          executionSlotId, "executionSlotId", 200);
      requireHash(attemptId, "attemptId");
      requireHash(manifestHash, "manifestHash");
      providerAttributions =
          List.copyOf(
              Objects.requireNonNull(
                  providerAttributions,
                  "providerAttributions"));
      parentRun = Objects.requireNonNull(parentRun, "parentRun");
      childRun = Objects.requireNonNull(childRun, "childRun");
      candidate = Objects.requireNonNull(candidate, "candidate");
      terminalSeal =
          Objects.requireNonNull(terminalSeal, "terminalSeal");
    }
  }

  public record Evaluation(
      int repetition,
      String armId,
      String evaluatorVersion,
      String candidateRef,
      String candidateIntegrityHash,
      EvaluationStatus status,
      String failureCode) {

    public Evaluation {
      if (repetition < 1 || repetition > 3) {
        throw new IllegalArgumentException(
            "evaluation repetition must be 1, 2 or 3");
      }
      requireIdentifier(armId, "armId", 200);
      requireIdentifier(
          evaluatorVersion, "evaluatorVersion", 200);
      ContractText.require(candidateRef, "candidateRef");
      requireHash(
          candidateIntegrityHash, "candidateIntegrityHash");
      status = Objects.requireNonNull(status, "status");
      if (status == EvaluationStatus.ACCEPTED) {
        if (failureCode != null) {
          throw new IllegalArgumentException(
              "accepted evaluation cannot carry a failureCode");
        }
      } else {
        requireFailureCode(failureCode);
      }
    }
  }

  public record UsageAggregate(
      long providerRequests,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd) {

    public UsageAggregate {
      ContractValueDomains.requireSafeCount(
          providerRequests, "providerRequests");
      ContractValueDomains.requireSafeCount(
          inputTokens, "inputTokens");
      ContractValueDomains.requireSafeCount(
          cachedInputTokens, "cachedInputTokens");
      ContractValueDomains.requireSafeCount(
          outputTokens, "outputTokens");
      ContractValueDomains.requireSafeCount(
          reasoningOutputTokens,
          "reasoningOutputTokens");
      ContractValueDomains.requireSafeCount(
          totalTokens, "totalTokens");
      if (cachedInputTokens > inputTokens
          || reasoningOutputTokens > outputTokens
          || safeAdd(inputTokens, outputTokens, "totalTokens")
              != totalTokens) {
        throw new IllegalArgumentException(
            "aggregate token witness is inconsistent");
      }
      ContractValueDomains.requireUsd(
          observedCostUsd, "observedCostUsd");
    }
  }

  public record EvaluatorEffects(
      long sharedCandidateInputs,
      long evaluations,
      long modelInvocations,
      long toolExecutions,
      long networkCalls,
      long credentialReads,
      long connectorCalls,
      long productTruthWrites,
      long externalSideEffects,
      long realUserDataReads) {

    public EvaluatorEffects {
      ContractValueDomains.requireSafeCount(
          sharedCandidateInputs, "sharedCandidateInputs");
      ContractValueDomains.requireSafeCount(
          evaluations, "evaluations");
      ContractValueDomains.requireSafeCount(
          modelInvocations, "modelInvocations");
      ContractValueDomains.requireSafeCount(
          toolExecutions, "toolExecutions");
      ContractValueDomains.requireSafeCount(
          networkCalls, "networkCalls");
      ContractValueDomains.requireSafeCount(
          credentialReads, "credentialReads");
      ContractValueDomains.requireSafeCount(
          connectorCalls, "connectorCalls");
      ContractValueDomains.requireSafeCount(
          productTruthWrites, "productTruthWrites");
      ContractValueDomains.requireSafeCount(
          externalSideEffects, "externalSideEffects");
      ContractValueDomains.requireSafeCount(
          realUserDataReads, "realUserDataReads");
    }
  }

  public enum EvaluationStatus {
    ACCEPTED,
    REJECTED
  }

  public enum GraphOutcome {
    SUCCEEDED,
    FAILED
  }

  public enum BillingStatus {
    ATTRIBUTED
  }

  public enum ReportStatus {
    COMPLETE
  }

  static Map<String, Object> idPreimage(
      String schemaVersion,
      String reportKind,
      String graphProtocolVersion,
      String packRawSha256,
      String environmentRawSha256,
      List<EvaluatorArm> evaluatorArms,
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usageAggregate,
      EvaluatorEffects evaluatorEffects,
      ReportStatus reportStatus,
      String integrityProfile) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("environmentRawSha256", environmentRawSha256);
    values.put("evaluations", evaluations);
    values.put("evaluatorArms", evaluatorArms);
    values.put("evaluatorEffects", evaluatorEffects);
    values.put("graphProtocolVersion", graphProtocolVersion);
    values.put("integrityProfile", integrityProfile);
    values.put("packRawSha256", packRawSha256);
    values.put("repetitions", repetitions);
    values.put("reportKind", reportKind);
    values.put("reportStatus", reportStatus);
    values.put("schemaVersion", schemaVersion);
    values.put("usageAggregate", usageAggregate);
    return values;
  }

  static Map<String, Object> preimage(
      String schemaVersion,
      String reportKind,
      String reportId,
      String graphProtocolVersion,
      String packRawSha256,
      String environmentRawSha256,
      List<EvaluatorArm> evaluatorArms,
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usageAggregate,
      EvaluatorEffects evaluatorEffects,
      ReportStatus reportStatus,
      String integrityProfile) {
    Map<String, Object> values =
        idPreimage(
            schemaVersion,
            reportKind,
            graphProtocolVersion,
            packRawSha256,
            environmentRawSha256,
            evaluatorArms,
            repetitions,
            evaluations,
            usageAggregate,
            evaluatorEffects,
            reportStatus,
            integrityProfile);
    values.put("reportId", reportId);
    return values;
  }

  private static void verifyCompleteProtocol(
      String environmentRawSha256,
      List<EvaluatorArm> arms,
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usage,
      EvaluatorEffects effects) {
    List<EvaluatorArm> expectedArms =
        List.of(
            new EvaluatorArm(H0_ARM_ID, H0_EVALUATOR_VERSION),
            new EvaluatorArm(H1_ARM_ID, H1_EVALUATOR_VERSION));
    if (!arms.equals(expectedArms)) {
      throw new IllegalArgumentException(
          "Harness evaluator arms must use the frozen H0/H1 order");
    }
    if (repetitions.size() != 3 || evaluations.size() != 6) {
      throw new IllegalArgumentException(
          "HarnessEvaluationReport requires exactly 3 repetitions and 6 evaluations");
    }
    if (!effects.equals(
        new EvaluatorEffects(3, 6, 0, 0, 0, 0, 0, 0, 0, 0))) {
      throw new IllegalArgumentException(
          "Harness evaluators must have exactly zero external effects");
    }

    List<String> slots =
        repetitions.stream()
            .map(RepetitionReport::executionSlotId)
            .toList();
    List<String> attempts =
        repetitions.stream().map(RepetitionReport::attemptId).toList();
    List<String> candidateRefs =
        repetitions.stream()
            .map(repetition -> repetition.candidate().candidateRef())
            .toList();
    List<String> runIds =
        repetitions.stream()
            .flatMap(
                repetition ->
                    java.util.stream.Stream.of(
                        repetition.parentRun().bundle().runId(),
                        repetition.childRun().bundle().runId()))
            .toList();
    List<String> taskIds =
        repetitions.stream()
            .flatMap(
                repetition ->
                    java.util.stream.Stream.of(
                        repetition.parentRun().bundle().taskId(),
                        repetition.childRun().bundle().taskId()))
            .toList();
    if (slots.stream().distinct().count() != 3
        || attempts.stream().distinct().count() != 3
        || candidateRefs.stream().distinct().count() != 3
        || runIds.stream().distinct().count() != 6
        || taskIds.stream().distinct().count() != 6) {
      throw new IllegalArgumentException(
          "Harness repetitions require distinct slots, attempts, Candidates, Runs and Tasks");
    }

    ProviderAttributionWitness baselineAttribution = null;
    String experimentArm = null;
    long providerRequests = 0;
    long inputTokens = 0;
    long cachedInputTokens = 0;
    long outputTokens = 0;
    long reasoningOutputTokens = 0;
    long totalTokens = 0;
    BigDecimal observedCostUsd = BigDecimal.ZERO;

    for (int index = 0; index < repetitions.size(); index++) {
      int expectedRepetition = index + 1;
      RepetitionReport repetition = repetitions.get(index);
      Evaluation h0 = evaluations.get(index * 2);
      Evaluation h1 = evaluations.get(index * 2 + 1);
      if (repetition.repetition() != expectedRepetition) {
        throw new IllegalArgumentException(
            "Harness repetitions must be ordered 1, 2, 3");
      }
      verifyRepetition(
          repetition,
          h0,
          h1,
          environmentRawSha256);
      HarnessExperiment childExperiment =
          repetition.childRun().bundle().experiment();
      HarnessExperiment parentExperiment =
          repetition.parentRun().bundle().experiment();
      if (childExperiment == null
          || !childExperiment.equals(parentExperiment)
          || childExperiment.repetition() != expectedRepetition) {
        throw new IllegalArgumentException(
            "terminal Runs must bind the same ordered Harness experiment");
      }
      if (experimentArm == null) {
        experimentArm = childExperiment.arm();
      } else if (!experimentArm.equals(childExperiment.arm())) {
        throw new IllegalArgumentException(
            "Harness experiment arm drifted across repetitions");
      }

      for (ProviderAttributionWitness attribution :
          repetition.providerAttributions()) {
        if (baselineAttribution == null) {
          baselineAttribution = attribution;
        } else {
          requireStableProviderProfile(
              baselineAttribution, attribution);
        }
        providerRequests =
            safeAdd(providerRequests, 1, "providerRequests");
        inputTokens =
            safeAdd(
                inputTokens,
                attribution.inputTokens(),
                "inputTokens");
        cachedInputTokens =
            safeAdd(
                cachedInputTokens,
                attribution.cachedInputTokens(),
                "cachedInputTokens");
        outputTokens =
            safeAdd(
                outputTokens,
                attribution.outputTokens(),
                "outputTokens");
        reasoningOutputTokens =
            safeAdd(
                reasoningOutputTokens,
                attribution.reasoningOutputTokens(),
                "reasoningOutputTokens");
        totalTokens =
            safeAdd(
                totalTokens,
                attribution.totalTokens(),
                "totalTokens");
        observedCostUsd =
            observedCostUsd.add(attribution.observedCostUsd());
      }
    }
    ContractValueDomains.requireUsd(
        observedCostUsd, "aggregate observedCostUsd");
    if (usage.providerRequests() != providerRequests
        || usage.inputTokens() != inputTokens
        || usage.cachedInputTokens() != cachedInputTokens
        || usage.outputTokens() != outputTokens
        || usage.reasoningOutputTokens()
            != reasoningOutputTokens
        || usage.totalTokens() != totalTokens
        || usage.observedCostUsd().compareTo(observedCostUsd) != 0) {
      throw new IllegalArgumentException(
          "usageAggregate does not equal the six provider attributions");
    }
  }

  private static void verifyRepetition(
      RepetitionReport repetition,
      Evaluation h0,
      Evaluation h1,
      String environmentRawSha256) {
    HarnessCandidateEnvelope candidate = repetition.candidate();
    TerminalRunWitness childRun = repetition.childRun();
    TerminalRunWitness parentRun = repetition.parentRun();
    HarnessRunBundle childBundle = childRun.bundle();
    HarnessRunBundle parentBundle = parentRun.bundle();
    TerminalSealWitness seal = repetition.terminalSeal();
    List<ProviderAttributionWitness> attributions =
        repetition.providerAttributions();

    if (attributions.size() != 2
        || attributions.get(0).requestOrdinal() != 1
        || attributions.get(1).requestOrdinal() != 2
        || attributions.get(0).attributionHash()
            .equals(attributions.get(1).attributionHash())) {
      throw new IllegalArgumentException(
          "each repetition requires ordered exact two provider attributions");
    }
    if (!repetition.attemptId().equals(repetition.manifestHash())
        || !repetition.attemptId().equals(candidate.attemptId())
        || !repetition
            .executionSlotId()
            .equals(candidate.executionSlotId())
        || repetition.repetition() != candidate.repetition()
        || !candidate.childRunId().equals(childBundle.runId())
        || !candidate.childTaskId().equals(childBundle.taskId())
        || candidate.sourceRequestOrdinal() != 2
        || !candidate
            .sourceResponseHash()
            .equals(attributions.get(1).responseHash())
        || !candidate
            .traceRootHash()
            .equals(childBundle.traceRootHash())
        || !candidate
            .outputSchema()
            .equals(childBundle.task().outputSchema())
        || !candidate
            .obtainedEvidenceRefs()
            .equals(childBundle.result().evidenceRefs())
        || candidate.requiredEvidenceAvailable()
            != childBundle
                .result()
                .evidenceRefs()
                .contains(candidate.requiredEvidenceRef())) {
      throw new IllegalArgumentException(
          "Harness Candidate does not match its exact terminal child");
    }
    String expectedEnvironmentRef =
        "environment://sha256:" + environmentRawSha256;
    if (!expectedEnvironmentRef.equals(
            childBundle.environmentSnapshotRef())
        || !expectedEnvironmentRef.equals(
            parentBundle.environmentSnapshotRef())
        || !expectedEnvironmentRef.equals(
            childBundle.task().environmentSnapshotRef())
        || !expectedEnvironmentRef.equals(
            parentBundle.task().environmentSnapshotRef())) {
      throw new IllegalArgumentException(
          "terminal Runs do not bind the Report environment");
    }

    BigDecimal attributedCost = BigDecimal.ZERO;
    long attributedTokens = 0;
    for (ProviderAttributionWitness attribution : attributions) {
      attributedCost =
          attributedCost.add(attribution.observedCostUsd());
      attributedTokens =
          safeAdd(
              attributedTokens,
              attribution.totalTokens(),
              "repetition totalTokens");
    }
    if (attributedCost.compareTo(childBundle.costUsd()) != 0
        || attributedCost.compareTo(parentBundle.costUsd()) != 0
        || attributedTokens != childBundle.tokenCount()
        || attributedTokens != parentBundle.tokenCount()
        || !attributions
            .get(1)
            .modelResolved()
            .equals(childBundle.modelResolved())) {
      throw new IllegalArgumentException(
          "provider, child and parent usage do not converge");
    }
    String childRef = "agent-run://" + childBundle.runId();
    ResourceBinding handoff =
        exactBinding(parentBundle, ResourceRole.HANDOFF);
    if (!parentBundle.handoffRefs().equals(List.of(childRef))
        || handoff == null
        || handoff.ordinal() != 0
        || !childRef.equals(handoff.ref())
        || !childBundle
            .integrityHash()
            .equals(handoff.contentHash())) {
      throw new IllegalArgumentException(
          "parent Run does not bind its exact child Bundle");
    }

    if (!seal.attemptId().equals(repetition.attemptId())
        || !seal.manifestHash().equals(repetition.manifestHash())
        || !seal
            .providerAttributionHashes()
            .equals(
                attributions.stream()
                    .map(
                        ProviderAttributionWitness
                            ::attributionHash)
                    .toList())
        || !seal.candidateRef().equals(candidate.candidateRef())
        || !seal
            .candidateIntegrityHash()
            .equals(candidate.integrityHash())
        || !seal
            .childTerminalHash()
            .equals(childRun.terminalHash())
        || !seal
            .parentTerminalHash()
            .equals(parentRun.terminalHash())
        || seal.preSealHeadHash().equals(seal.finalHeadHash())
        || seal.sealedAt().isBefore(childRun.completedAt())
        || seal.sealedAt().isBefore(parentRun.completedAt())
        || parentRun.completedAt().isBefore(childRun.completedAt())) {
      throw new IllegalArgumentException(
          "terminal seal does not bind the complete Report repetition");
    }

    verifyEvaluation(
        h0,
        repetition,
        H0_ARM_ID,
        H0_EVALUATOR_VERSION);
    verifyEvaluation(
        h1,
        repetition,
        H1_ARM_ID,
        H1_EVALUATOR_VERSION);
    if (h0.status() != EvaluationStatus.ACCEPTED
        || h0.failureCode() != null) {
      throw new IllegalArgumentException(
          "H0 must accept every contract-valid Candidate");
    }
    String expectedH1Failure = expectedH1Failure(candidate);
    if (expectedH1Failure == null
        ? h1.status() != EvaluationStatus.ACCEPTED
            || h1.failureCode() != null
        : h1.status() != EvaluationStatus.REJECTED
            || !expectedH1Failure.equals(h1.failureCode())) {
      throw new IllegalArgumentException(
          "H1 does not independently match the shared Candidate");
    }
    verifyOutcome(repetition, h1);
  }

  private static String expectedH1Failure(
      HarnessCandidateEnvelope candidate) {
    if (candidate.obtainedEvidenceRefs().isEmpty()) {
      return "MISSING_REQUIRED_EVIDENCE";
    }
    List<String> required = List.of(candidate.requiredEvidenceRef());
    if (!candidate.obtainedEvidenceRefs().equals(required)) {
      return "UNSAFE_EVIDENCE_BINDING";
    }
    if (!candidate.evidenceRefs().equals(required)) {
      return "INVALID_EVIDENCE_CLAIM";
    }
    if (!candidate.requiredEvidenceAvailable()) {
      return "REQUIRED_EVIDENCE_NOT_FOUND";
    }
    return null;
  }

  private static void verifyEvaluation(
      Evaluation evaluation,
      RepetitionReport repetition,
      String armId,
      String evaluatorVersion) {
    if (evaluation.repetition() != repetition.repetition()
        || !evaluation.armId().equals(armId)
        || !evaluation.evaluatorVersion().equals(evaluatorVersion)
        || !evaluation
            .candidateRef()
            .equals(repetition.candidate().candidateRef())
        || !evaluation
            .candidateIntegrityHash()
            .equals(repetition.candidate().integrityHash())) {
      throw new IllegalArgumentException(
          "Harness evaluation does not bind its exact shared Candidate");
    }
  }

  private static void verifyOutcome(
      RepetitionReport repetition, Evaluation h1) {
    HarnessRunBundle child = repetition.childRun().bundle();
    HarnessRunBundle parent = repetition.parentRun().bundle();
    ResourceBinding workerBinding =
        exactBinding(child, ResourceRole.WORKER_RESULT);
    ResourceBinding artifactBinding =
        exactBinding(parent, ResourceRole.ARTIFACT);
    if (h1.status() == EvaluationStatus.ACCEPTED) {
      WorkerResultEnvelope worker = repetition.workerResult();
      ResourceBinding artifact = repetition.artifactBinding();
      if (h1.failureCode() != null
          || repetition.terminalSeal().graphOutcome()
              != GraphOutcome.SUCCEEDED
          || child.outcome() != RunStatus.SUCCEEDED
          || parent.outcome() != RunStatus.SUCCEEDED
          || worker == null
          || artifact == null
          || workerBinding == null
          || artifactBinding == null
          || !worker.childRunId().equals(child.runId())
          || !worker.childTaskId().equals(child.taskId())
          || !worker
              .outputSchema()
              .equals(repetition.candidate().outputSchema())
          || !worker.content().equals(repetition.candidate().content())
          || !worker
              .contentHash()
              .equals(repetition.candidate().contentHash())
          || !worker
              .evidenceRefs()
              .equals(repetition.candidate().evidenceRefs())
          || !workerBinding.ref().equals(worker.workerResultRef())
          || !workerBinding
              .contentHash()
              .equals(worker.integrityHash())
          || !artifactBinding.equals(artifact)
          || !artifact
              .contentHash()
              .equals(repetition.candidate().contentHash())
          || !parent.result().artifactRefs().equals(List.of(artifact.ref()))) {
        throw new IllegalArgumentException(
            "accepted H1 does not match successful terminal graph truth");
      }
      return;
    }

    if (repetition.terminalSeal().graphOutcome()
            != GraphOutcome.FAILED
        || child.outcome() != RunStatus.FAILED
        || parent.outcome() != RunStatus.FAILED
        || !Objects.equals(
            child.failureAttribution(), h1.failureCode())
        || !"HANDOFF_CHILD_FAILED".equals(
            parent.failureAttribution())
        || repetition.workerResult() != null
        || repetition.artifactBinding() != null
        || workerBinding != null
        || artifactBinding != null) {
      throw new IllegalArgumentException(
          "rejected H1 does not match failed terminal graph truth");
    }
  }

  private static ResourceBinding exactBinding(
      HarnessRunBundle bundle, ResourceRole role) {
    List<ResourceBinding> matching =
        bundle.resourceBindings().stream()
            .filter(binding -> binding.role() == role)
            .toList();
    if (matching.size() > 1) {
      throw new IllegalArgumentException(
          "terminal Bundle has duplicate " + role + " bindings");
    }
    return matching.isEmpty() ? null : matching.getFirst();
  }

  private static void requireStableProviderProfile(
      ProviderAttributionWitness baseline,
      ProviderAttributionWitness current) {
    if (!baseline.providerActor().equals(current.providerActor())
        || !baseline.modelRequested().equals(current.modelRequested())
        || !baseline.modelResolved().equals(current.modelResolved())
        || !baseline
            .pricingProfileId()
            .equals(current.pricingProfileId())
        || !baseline
            .pricingProvider()
            .equals(current.pricingProvider())
        || !baseline
            .pricingModelRequested()
            .equals(current.pricingModelRequested())
        || baseline.uncachedInputNanoUsdPerToken()
            != current.uncachedInputNanoUsdPerToken()
        || baseline.cachedInputNanoUsdPerToken()
            != current.cachedInputNanoUsdPerToken()
        || baseline.outputNanoUsdPerToken()
            != current.outputNanoUsdPerToken()
        || !baseline
            .pricingProfileFingerprint()
            .equals(current.pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider/model/pricing drifted across the Harness Report");
    }
  }

  private static BigDecimal calculatedCost(
      long uncachedRate,
      long cachedRate,
      long outputRate,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens) {
    try {
      long uncachedInputTokens = inputTokens - cachedInputTokens;
      long nanoUsd =
          Math.addExact(
              Math.addExact(
                  Math.multiplyExact(uncachedInputTokens, uncachedRate),
                  Math.multiplyExact(cachedInputTokens, cachedRate)),
              Math.multiplyExact(outputTokens, outputRate));
      BigDecimal rounded =
          BigDecimal.valueOf(nanoUsd, 9)
              .setScale(
                  ContractValueDomains.USD_MAX_SCALE,
                  RoundingMode.HALF_UP);
      return rounded.signum() == 0 ? BigDecimal.ZERO : rounded;
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "provider cost witness exceeds the frozen numeric domain",
          overflow);
    }
  }

  private static long safeAdd(
      long left, long right, String name) {
    try {
      long value = Math.addExact(left, right);
      return ContractValueDomains.requireSafeCount(value, name);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          name + " exceeds the frozen numeric domain", overflow);
    }
  }

  private static Instant requireMicros(
      Instant value, String name) {
    Objects.requireNonNull(value, name);
    if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException(
          name + " must be aligned to microseconds");
    }
    return value;
  }

  private static void requireHash(String value, String name) {
    IntegrityHashes.requireHash(value, name);
  }

  private static void requireIdentifier(
      String value, String name, int maximumLength) {
    if (value == null
        || value.length() > maximumLength
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]*")) {
      throw new IllegalArgumentException(
          name + " has an invalid identifier");
    }
  }

  private static void requireModel(String value, String name) {
    ContractText.require(
        value, name, ContractText.MAX_MODEL_LENGTH);
    if (!ContractText.isSafeModelIdentifier(value)) {
      throw new IllegalArgumentException(
          name + " is outside the safe model domain");
    }
  }

  private static void requireFailureCode(String value) {
    if (!List.of(
            "MISSING_REQUIRED_EVIDENCE",
            "UNSAFE_EVIDENCE_BINDING",
            "INVALID_EVIDENCE_CLAIM",
            "REQUIRED_EVIDENCE_NOT_FOUND")
        .contains(value)) {
      throw new IllegalArgumentException(
          "failureCode is outside the H1 rejection domain");
    }
  }
}
