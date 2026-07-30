package io.emergeos.offlineharness;

import io.emergeos.core.application.AgentDraftReferenceGrounding;
import io.emergeos.core.domain.ArtifactLineageEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Pack004ComparisonRunner {

  static final String SCHEMA_VERSION = "1.0";
  static final String REPORT_KIND = "OFFLINE_VERIFIER_COMPARISON";
  static final String RUNNER_VERSION =
      "pack004-comparison-runner-v1";
  static final String H0_VERIFIER_VERSION = "schema-only-eval-v1";
  private static final String H0_ARM_ID = "h0-schema-only";
  private static final String H1_ARM_ID = "h1-reference-grounding";

  OfflineComparisonReport run(Path repositoryRoot) {
    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(repositoryRoot);
    verifyComponentBindings(loaded.pack().harnessComparison());
    LiteralReferenceCandidateGenerator generator =
        new LiteralReferenceCandidateGenerator();

    String reportId =
        ComparisonIntegrityHashes.reportId(
            SCHEMA_VERSION, REPORT_KIND, RUNNER_VERSION, loaded);
    var effects =
        new LiteralReferenceCandidateGenerator.OwnedEffectRecorder();
    List<OfflineComparisonReport.PairRecord> pairs =
        new ArrayList<>();
    List<OfflineComparisonReport.VerifierEvaluation> evaluations =
        new ArrayList<>();
    List<OfflineComparisonReport.Issue> issues = new ArrayList<>();
    MutableSummary summary = new MutableSummary();

    Pack004Loader.HarnessComparison comparison =
        loaded.pack().harnessComparison();
    for (Pack004Loader.CaseDefinition testCase :
        comparison.cases()) {
      for (int repetition = 1;
          repetition <= comparison.repetitions();
          repetition++) {
        var candidate =
            generateCandidate(
                generator,
                comparison.frozen(),
                testCase,
                repetition,
                effects);
        effects.recordCandidateGeneration(candidate);
        OfflineComparisonReport.CandidateSnapshot candidateSnapshot =
            candidate.snapshot();
        String candidateFingerprint =
            ComparisonIntegrityHashes.candidateFingerprint(
                candidateSnapshot);
        String executionBaseId =
            testCase.executionIdPrefix() + "-r" + repetition;
        String pairId =
            ComparisonIntegrityHashes.pairId(
                reportId,
                testCase.id(),
                executionBaseId,
                repetition,
                comparison.frozen().candidateGeneratorVersion(),
                candidateFingerprint);
        OfflineComparisonReport.PairRecord pair =
            sealPair(
                pairId,
                executionBaseId,
                testCase,
                repetition,
                comparison.frozen().candidateGeneratorVersion(),
                candidateSnapshot,
                candidateFingerprint);
        pairs.add(pair);

        for (Pack004Loader.Arm arm : comparison.arms()) {
          EvaluationOutcome observed =
              evaluate(arm, candidate, effects);
          Pack004Loader.ExpectedOutcome expected =
              expected(testCase.expectedByArm(), arm.id());
          boolean matches =
              expected.status().equals(observed.status())
                  && Objects.equals(
                      expected.failureReason(),
                      observed.failureCode());
          OfflineComparisonReport.VerifierEvaluation evaluation =
              sealEvaluation(
                  pair,
                  arm,
                  expected,
                  observed,
                  matches);
          evaluations.add(evaluation);
          summary.observe(
              arm.id(),
              testCase.faultClass(),
              observed,
              matches);
          if (!matches) {
            issues.add(
                new OfflineComparisonReport.Issue(
                    "EXPECTED_OUTCOME_MISMATCH",
                    testCase.id(),
                    repetition,
                    arm.id()));
          }
        }
      }
    }

    effects.requireSharedCandidateIdentity(pairs.size());
    OfflineComparisonReport.OwnedEffects observedEffects =
        effects.snapshot();
    requireCompleteEffects(observedEffects);
    OfflineComparisonReport.Summary observedSummary =
        summary.snapshot(pairs.size(), evaluations.size());
    OfflineComparisonReport.Status status =
        issues.isEmpty()
            ? OfflineComparisonReport.Status.PASSED
            : OfflineComparisonReport.Status.FAILED;
    OfflineComparisonReport unsealed =
        new OfflineComparisonReport(
            SCHEMA_VERSION,
            REPORT_KIND,
            reportId,
            ComparisonIntegrityHashes.PROFILE,
            loaded.rawSha256(),
            loaded.pack().taskId(),
            comparison.suiteId(),
            comparison.variable(),
            comparison.repetitions(),
            RUNNER_VERSION,
            comparison.frozen().candidateGeneratorVersion(),
            H0_VERIFIER_VERSION,
            AgentDraftReferenceGrounding.VERIFIER_VERSION,
            comparison.frozen().frozenTime(),
            pairs,
            evaluations,
            observedSummary,
            observedEffects,
            status,
            issues,
            "UNSEALED");
    return copyWithIntegrity(
        unsealed, ComparisonIntegrityHashes.reportHash(unsealed));
  }

  private static LiteralReferenceCandidateGenerator.GeneratedCandidate
      generateCandidate(
          LiteralReferenceCandidateGenerator generator,
          Pack004Loader.Frozen frozen,
          Pack004Loader.CaseDefinition testCase,
          int repetition,
          LiteralReferenceCandidateGenerator.OwnedEffectRecorder
              effects) {
    try {
      return Objects.requireNonNull(
          generator.generate(frozen, testCase, repetition, effects),
          "candidate");
    } catch (
        LiteralReferenceCandidateGenerator.ComparisonExecutionRejected
            failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw LiteralReferenceCandidateGenerator.rejected(
          "COMPARISON_GENERATION_FAILED");
    }
  }

  private static EvaluationOutcome evaluate(
      Pack004Loader.Arm arm,
      LiteralReferenceCandidateGenerator.GeneratedCandidate
          candidate,
      LiteralReferenceCandidateGenerator.OwnedEffectRecorder
          effects) {
    try {
      return switch (arm.id()) {
        case H0_ARM_ID -> evaluateH0(candidate, effects);
        case H1_ARM_ID -> evaluateH1(candidate, effects);
        default ->
            throw LiteralReferenceCandidateGenerator.rejected(
                "COMPARISON_COMPONENT_BINDING_FAILED");
      };
    } catch (
        LiteralReferenceCandidateGenerator.ComparisonExecutionRejected
            failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw LiteralReferenceCandidateGenerator.rejected(
          "COMPARISON_EVALUATION_FAILED");
    }
  }

  private static EvaluationOutcome evaluateH0(
      LiteralReferenceCandidateGenerator.GeneratedCandidate
          candidate,
      LiteralReferenceCandidateGenerator.OwnedEffectRecorder
          effects) {
    effects.recordH0Evaluation(candidate);
    try {
      if (candidate.candidate().proposal() == null) {
        return new EvaluationOutcome(
            "FAILED", "INVALID_STRUCTURED_FINAL");
      }
      ArtifactLineageEntry.requireContent(
          candidate.candidate().proposal().content());
      return new EvaluationOutcome("SUCCEEDED", null);
    } catch (IllegalArgumentException invalidContent) {
      return new EvaluationOutcome(
          "FAILED", "INVALID_STRUCTURED_FINAL");
    }
  }

  private static EvaluationOutcome evaluateH1(
      LiteralReferenceCandidateGenerator.GeneratedCandidate
          candidate,
      LiteralReferenceCandidateGenerator.OwnedEffectRecorder
          effects) {
    effects.recordH1Evaluation(candidate);
    AgentDraftReferenceGrounding.Verification verification =
        AgentDraftReferenceGrounding.verify(candidate.candidate());
    return verification.accepted()
        ? new EvaluationOutcome("SUCCEEDED", null)
        : new EvaluationOutcome(
            "FAILED", verification.failureCode());
  }

  private static OfflineComparisonReport.PairRecord sealPair(
      String pairId,
      String executionBaseId,
      Pack004Loader.CaseDefinition testCase,
      int repetition,
      String candidateGeneratorVersion,
      OfflineComparisonReport.CandidateSnapshot candidate,
      String candidateFingerprint) {
    OfflineComparisonReport.PairRecord unsealed =
        new OfflineComparisonReport.PairRecord(
            pairId,
            executionBaseId,
            testCase.id(),
            repetition,
            testCase.candidateMode(),
            testCase.faultClass(),
            candidateGeneratorVersion,
            candidate,
            candidateFingerprint,
            "UNSEALED");
    return new OfflineComparisonReport.PairRecord(
        unsealed.pairId(),
        unsealed.executionBaseId(),
        unsealed.caseId(),
        unsealed.repetition(),
        unsealed.candidateMode(),
        unsealed.faultClass(),
        unsealed.candidateGeneratorVersion(),
        unsealed.candidate(),
        unsealed.candidateFingerprint(),
        ComparisonIntegrityHashes.pairHash(unsealed));
  }

  private static OfflineComparisonReport.VerifierEvaluation
      sealEvaluation(
          OfflineComparisonReport.PairRecord pair,
          Pack004Loader.Arm arm,
          Pack004Loader.ExpectedOutcome expected,
          EvaluationOutcome observed,
          boolean matches) {
    String evaluationId =
        ComparisonIntegrityHashes.evaluationId(
            pair.pairId(), arm.id(), arm.verifier());
    OfflineComparisonReport.VerifierEvaluation unsealed =
        new OfflineComparisonReport.VerifierEvaluation(
            evaluationId,
            pair.pairId(),
            pair.executionBaseId(),
            pair.caseId(),
            pair.repetition(),
            arm.id(),
            arm.verifier(),
            pair.candidateFingerprint(),
            expected.status(),
            expected.failureReason(),
            observed.status(),
            observed.failureCode(),
            matches,
            "UNSEALED");
    return new OfflineComparisonReport.VerifierEvaluation(
        unsealed.evaluationId(),
        unsealed.pairId(),
        unsealed.executionBaseId(),
        unsealed.caseId(),
        unsealed.repetition(),
        unsealed.armId(),
        unsealed.verifierVersion(),
        unsealed.candidateFingerprint(),
        unsealed.expectedStatus(),
        unsealed.expectedFailureCode(),
        unsealed.observedStatus(),
        unsealed.observedFailureCode(),
        unsealed.matchesExpected(),
        ComparisonIntegrityHashes.evaluationHash(unsealed));
  }

  private static Pack004Loader.ExpectedOutcome expected(
      Pack004Loader.ExpectedByArm expectedByArm, String armId) {
    return switch (armId) {
      case H0_ARM_ID -> expectedByArm.h0SchemaOnly();
      case H1_ARM_ID -> expectedByArm.h1ReferenceGrounding();
      default ->
          throw LiteralReferenceCandidateGenerator.rejected(
              "COMPARISON_COMPONENT_BINDING_FAILED");
    };
  }

  private static void verifyComponentBindings(
      Pack004Loader.HarnessComparison comparison) {
    boolean accepted =
        comparison.arms().size() == 2
            && H0_ARM_ID.equals(comparison.arms().get(0).id())
            && H0_VERIFIER_VERSION.equals(
                comparison.arms().get(0).verifier())
            && H1_ARM_ID.equals(comparison.arms().get(1).id())
            && AgentDraftReferenceGrounding.VERIFIER_VERSION.equals(
                comparison.arms().get(1).verifier())
            && LiteralReferenceCandidateGenerator.VERSION.equals(
                comparison.frozen().candidateGeneratorVersion());
    if (!accepted) {
      throw LiteralReferenceCandidateGenerator.rejected(
          "COMPARISON_COMPONENT_BINDING_FAILED");
    }
  }

  private static void requireCompleteEffects(
      OfflineComparisonReport.OwnedEffects effects) {
    boolean accepted =
        effects.sharedCandidateGenerations() == 12
            && effects.verifierEvaluations() == 24
            && effects.h0Evaluations() == 12
            && effects.h1Evaluations() == 12
            && effects.literalFixtureReads() == 9
            && effects.agentKernelRuns() == 0
            && effects.productAgentRuns() == 0
            && effects.modelInvocations() == 0
            && effects.toolLoopExecutions() == 0
            && effects.harnessRunBundles() == 0
            && effects.credentialReads() == 0
            && effects.networkCalls() == 0
            && effects.connectorCalls() == 0
            && effects.productTruthWrites() == 0
            && effects.externalSideEffects() == 0
            && effects.realUserDataReads() == 0;
    if (!accepted) {
      throw LiteralReferenceCandidateGenerator.rejected(
          "COMPARISON_EFFECTS_INVALID");
    }
  }

  private static OfflineComparisonReport copyWithIntegrity(
      OfflineComparisonReport report, String integrityHash) {
    return new OfflineComparisonReport(
        report.schemaVersion(),
        report.reportKind(),
        report.reportId(),
        report.integrityProfile(),
        report.packRawSha256(),
        report.taskId(),
        report.suiteId(),
        report.variable(),
        report.repetitions(),
        report.runnerVersion(),
        report.candidateGeneratorVersion(),
        report.h0VerifierVersion(),
        report.h1VerifierVersion(),
        report.frozenTime(),
        report.pairs(),
        report.evaluations(),
        report.summary(),
        report.effects(),
        report.status(),
        report.issues(),
        integrityHash);
  }

  record EvaluationOutcome(String status, String failureCode) {
    EvaluationOutcome {
      Objects.requireNonNull(status, "status");
    }
  }

  private static final class MutableSummary {
    private int h0Accepted;
    private int h0FaultAcceptances;
    private int h1Accepted;
    private int h1FaultRejections;
    private int outcomeMismatches;

    void observe(
        String armId,
        String faultClass,
        EvaluationOutcome outcome,
        boolean matches) {
      boolean accepted = "SUCCEEDED".equals(outcome.status());
      boolean fault = !"NONE".equals(faultClass);
      if (H0_ARM_ID.equals(armId) && accepted) {
        h0Accepted++;
        if (fault) {
          h0FaultAcceptances++;
        }
      }
      if (H1_ARM_ID.equals(armId)) {
        if (accepted) {
          h1Accepted++;
        } else if (fault) {
          h1FaultRejections++;
        }
      }
      if (!matches) {
        outcomeMismatches++;
      }
    }

    OfflineComparisonReport.Summary snapshot(
        int pairCount, int evaluationCount) {
      return new OfflineComparisonReport.Summary(
          pairCount,
          evaluationCount,
          h0Accepted,
          h0FaultAcceptances,
          h1Accepted,
          h1FaultRejections,
          outcomeMismatches);
    }
  }
}
