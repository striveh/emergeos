package io.emergeos.offlineharness;

import io.emergeos.core.application.AgentDraftReferenceGrounding;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.ArtifactLineageEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Independently replays Pack 004 and verifies a comparison report.
 *
 * <p>The verifier intentionally does not call the comparison runner. It owns
 * its enumeration, deterministic candidate reconstruction, H0 evaluator,
 * outcome reduction and effects reduction. Only the production H1 facade and
 * the canonical integrity encoding are shared.
 */
final class Pack004ComparisonReportVerifier {

  private static final String SCHEMA_VERSION = "1.0";
  private static final String REPORT_KIND =
      "OFFLINE_VERIFIER_COMPARISON";
  private static final String RUNNER_VERSION =
      "pack004-comparison-runner-v1";
  private static final String CANDIDATE_GENERATOR_VERSION =
      "literal-reference-candidate-fixture-v1";
  private static final String H0_ARM_ID = "h0-schema-only";
  private static final String H1_ARM_ID =
      "h1-reference-grounding";
  private static final String H0_VERIFIER_VERSION =
      "schema-only-eval-v1";
  private static final String H1_VERIFIER_VERSION =
      "agent-draft-verifier-v1";

  Verification verify(
      Path repositoryRoot, OfflineComparisonReport report) {
    if (report == null) {
      return invalid("REPORT_SHAPE_INVALID");
    }
    try {
      return verifyTrusted(repositoryRoot, report);
    } catch (Pack004Loader.Rejected packFailure) {
      return invalid("REPORT_PACK_PRECONDITION_FAILED");
    } catch (RuntimeException unexpected) {
      return invalid("REPORT_REPLAY_FAILED");
    }
  }

  private static Verification verifyTrusted(
      Path repositoryRoot, OfflineComparisonReport report) {
    Verification failure = verifySchema(report);
    if (failure != null) {
      return failure;
    }

    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(repositoryRoot);
    Pack004Loader.HarnessComparison comparison =
        loaded.pack().harnessComparison();

    failure = verifyPackBinding(report, loaded);
    if (failure != null) {
      return failure;
    }
    failure = verifyComponentBinding(report, comparison);
    if (failure != null) {
      return failure;
    }

    String expectedReportId =
        ComparisonIntegrityHashes.reportId(
            SCHEMA_VERSION, REPORT_KIND, RUNNER_VERSION, loaded);
    if (!expectedReportId.equals(report.reportId())) {
      return invalid("REPORT_ID_MISMATCH");
    }

    List<PairKey> expectedPairKeys = expectedPairKeys(comparison);
    List<PairKey> actualPairKeys =
        report.pairs().stream()
            .map(
                pair ->
                    new PairKey(pair.caseId(), pair.repetition()))
            .toList();
    failure =
        verifyExactMatrix(
            expectedPairKeys,
            actualPairKeys,
            "REPORT_PAIR_MATRIX_MISMATCH");
    if (failure != null) {
      return failure;
    }
    if (!expectedPairKeys.equals(actualPairKeys)) {
      return invalid("REPORT_ORDER_MISMATCH");
    }

    List<EvaluationKey> expectedEvaluationKeys =
        expectedEvaluationKeys(comparison);
    List<EvaluationKey> actualEvaluationKeys =
        report.evaluations().stream()
            .map(
                evaluation ->
                    new EvaluationKey(
                        evaluation.caseId(),
                        evaluation.repetition(),
                        evaluation.armId()))
            .toList();
    failure =
        verifyExactMatrix(
            expectedEvaluationKeys,
            actualEvaluationKeys,
            "REPORT_EVALUATION_MATRIX_MISMATCH");
    if (failure != null) {
      return failure;
    }
    if (!expectedEvaluationKeys.equals(actualEvaluationKeys)) {
      return invalid("REPORT_ORDER_MISMATCH");
    }

    ReplayReduction reduction = new ReplayReduction();
    int pairIndex = 0;
    int evaluationIndex = 0;
    for (Pack004Loader.CaseDefinition testCase :
        comparison.cases()) {
      for (int repetition = 1;
          repetition <= comparison.repetitions();
          repetition++) {
        OfflineComparisonReport.PairRecord storedPair =
            report.pairs().get(pairIndex++);
        AgentDraftReferenceGrounding.Candidate candidate =
            reconstructCandidate(
                comparison.frozen(), testCase, reduction);
        OfflineComparisonReport.CandidateSnapshot snapshot =
            snapshotOf(candidate);
        String candidateFingerprint =
            ComparisonIntegrityHashes.candidateFingerprint(snapshot);
        String executionBaseId =
            testCase.executionIdPrefix() + "-r" + repetition;
        String pairId =
            ComparisonIntegrityHashes.pairId(
                expectedReportId,
                testCase.id(),
                executionBaseId,
                repetition,
                CANDIDATE_GENERATOR_VERSION,
                candidateFingerprint);

        if (!snapshot.equals(storedPair.candidate())
            || !candidateFingerprint.equals(
                storedPair.candidateFingerprint())) {
          return invalid("REPORT_CANDIDATE_MISMATCH");
        }
        if (!pairId.equals(storedPair.pairId())
            || !executionBaseId.equals(
                storedPair.executionBaseId())
            || !testCase.candidateMode()
                .equals(storedPair.candidateMode())
            || !testCase.faultClass()
                .equals(storedPair.faultClass())
            || !CANDIDATE_GENERATOR_VERSION.equals(
                storedPair.candidateGeneratorVersion())) {
          return invalid("REPORT_PAIR_BINDING_MISMATCH");
        }
        if (!expectedPairHash(
                pairId,
                executionBaseId,
                testCase,
                repetition,
                snapshot,
                candidateFingerprint)
            .equals(storedPair.integrityHash())) {
          return invalid("REPORT_INTEGRITY_MISMATCH");
        }

        reduction.recordCandidateGeneration();
        for (Pack004Loader.Arm arm : comparison.arms()) {
          OfflineComparisonReport.VerifierEvaluation stored =
              report.evaluations().get(evaluationIndex++);
          Pack004Loader.ExpectedOutcome expected =
              expected(testCase.expectedByArm(), arm.id());
          ReplayedOutcome observed =
              replay(arm.id(), candidate, reduction);
          boolean matches =
              expected.status().equals(observed.status())
                  && Objects.equals(
                      expected.failureReason(),
                      observed.failureCode());
          String evaluationId =
              ComparisonIntegrityHashes.evaluationId(
                  pairId, arm.id(), arm.verifier());

          if (!evaluationId.equals(stored.evaluationId())
              || !pairId.equals(stored.pairId())
              || !executionBaseId.equals(
                  stored.executionBaseId())
              || !arm.verifier()
                  .equals(stored.verifierVersion())
              || !candidateFingerprint.equals(
                  stored.candidateFingerprint())) {
            return invalid(
                "REPORT_EVALUATION_BINDING_MISMATCH");
          }
          if (!expected.status().equals(stored.expectedStatus())
              || !Objects.equals(
                  expected.failureReason(),
                  stored.expectedFailureCode())) {
            return invalid("REPORT_EXPECTATION_MISMATCH");
          }
          if (!observed.status().equals(stored.observedStatus())
              || !Objects.equals(
                  observed.failureCode(),
                  stored.observedFailureCode())
              || matches != stored.matchesExpected()) {
            return invalid("REPORT_OUTCOME_MISMATCH");
          }
          if (!expectedEvaluationHash(
                  evaluationId,
                  pairId,
                  executionBaseId,
                  testCase,
                  repetition,
                  arm,
                  candidateFingerprint,
                  expected,
                  observed,
                  matches)
              .equals(stored.integrityHash())) {
            return invalid("REPORT_INTEGRITY_MISMATCH");
          }

          reduction.observe(
              arm.id(),
              testCase,
              repetition,
              observed,
              matches);
        }
      }
    }

    OfflineComparisonReport.Summary expectedSummary =
        reduction.summary();
    if (!expectedSummary.equals(report.summary())) {
      return invalid("REPORT_SUMMARY_MISMATCH");
    }
    if (!reduction.effects().equals(report.effects())) {
      return invalid("REPORT_EFFECTS_MISMATCH");
    }
    if (!reduction.issues().equals(report.issues())) {
      return invalid("REPORT_ISSUES_MISMATCH");
    }
    OfflineComparisonReport.Status expectedStatus =
        reduction.issues().isEmpty()
            ? OfflineComparisonReport.Status.PASSED
            : OfflineComparisonReport.Status.FAILED;
    if (expectedStatus != report.status()) {
      return invalid("REPORT_STATUS_MISMATCH");
    }
    if (!ComparisonIntegrityHashes.reportHash(report)
        .equals(report.integrityHash())) {
      return invalid("REPORT_INTEGRITY_MISMATCH");
    }

    return expectedStatus == OfflineComparisonReport.Status.PASSED
        ? new Verification(Verdict.VERIFIED_PASSED, null)
        : new Verification(Verdict.VERIFIED_FAILED, null);
  }

  private static Verification verifySchema(
      OfflineComparisonReport report) {
    if (!SCHEMA_VERSION.equals(report.schemaVersion())
        || !REPORT_KIND.equals(report.reportKind())
        || !ComparisonIntegrityHashes.PROFILE.equals(
            report.integrityProfile())) {
      return invalid("REPORT_SCHEMA_MISMATCH");
    }
    return null;
  }

  private static Verification verifyPackBinding(
      OfflineComparisonReport report,
      Pack004Loader.LoadedPack loaded) {
    Pack004Loader.HarnessComparison comparison =
        loaded.pack().harnessComparison();
    boolean accepted =
        loaded.rawSha256().equals(report.packRawSha256())
            && loaded.pack().taskId().equals(report.taskId())
            && comparison.suiteId().equals(report.suiteId())
            && comparison.variable().equals(report.variable())
            && comparison.repetitions() == report.repetitions()
            && comparison.frozen().frozenTime()
                .equals(report.frozenTime());
    return accepted
        ? null
        : invalid("REPORT_PACK_BINDING_MISMATCH");
  }

  private static Verification verifyComponentBinding(
      OfflineComparisonReport report,
      Pack004Loader.HarnessComparison comparison) {
    boolean accepted =
        RUNNER_VERSION.equals(report.runnerVersion())
            && CANDIDATE_GENERATOR_VERSION.equals(
                report.candidateGeneratorVersion())
            && H0_VERIFIER_VERSION.equals(
                report.h0VerifierVersion())
            && H1_VERIFIER_VERSION.equals(
                report.h1VerifierVersion())
            && CANDIDATE_GENERATOR_VERSION.equals(
                comparison.frozen().candidateGeneratorVersion())
            && H1_VERIFIER_VERSION.equals(
                AgentDraftReferenceGrounding.VERIFIER_VERSION)
            && comparison.arms().size() == 2
            && H0_ARM_ID.equals(comparison.arms().get(0).id())
            && H0_VERIFIER_VERSION.equals(
                comparison.arms().get(0).verifier())
            && H1_ARM_ID.equals(comparison.arms().get(1).id())
            && H1_VERIFIER_VERSION.equals(
                comparison.arms().get(1).verifier());
    return accepted
        ? null
        : invalid("REPORT_COMPONENT_BINDING_MISMATCH");
  }

  private static <T> Verification verifyExactMatrix(
      List<T> expected, List<T> actual, String failureCode) {
    if (expected.size() != actual.size()) {
      return invalid(failureCode);
    }
    Set<T> expectedSet = new HashSet<>(expected);
    Set<T> actualSet = new HashSet<>(actual);
    if (expectedSet.size() != expected.size()
        || actualSet.size() != actual.size()
        || !expectedSet.equals(actualSet)) {
      return invalid(failureCode);
    }
    return null;
  }

  private static List<PairKey> expectedPairKeys(
      Pack004Loader.HarnessComparison comparison) {
    List<PairKey> keys = new ArrayList<>();
    for (Pack004Loader.CaseDefinition testCase :
        comparison.cases()) {
      for (int repetition = 1;
          repetition <= comparison.repetitions();
          repetition++) {
        keys.add(new PairKey(testCase.id(), repetition));
      }
    }
    return List.copyOf(keys);
  }

  private static List<EvaluationKey> expectedEvaluationKeys(
      Pack004Loader.HarnessComparison comparison) {
    List<EvaluationKey> keys = new ArrayList<>();
    for (Pack004Loader.CaseDefinition testCase :
        comparison.cases()) {
      for (int repetition = 1;
          repetition <= comparison.repetitions();
          repetition++) {
        for (Pack004Loader.Arm arm : comparison.arms()) {
          keys.add(
              new EvaluationKey(
                  testCase.id(), repetition, arm.id()));
        }
      }
    }
    return List.copyOf(keys);
  }

  private static AgentDraftReferenceGrounding.Candidate
      reconstructCandidate(
          Pack004Loader.Frozen frozen,
          Pack004Loader.CaseDefinition testCase,
          ReplayReduction reduction) {
    String requiredRef = "capture://" + frozen.captureId();
    List<String> proposalRefs;
    List<String> obtainedRefs;
    switch (testCase.candidateMode()) {
      case "GROUNDED" -> {
        proposalRefs = List.of(requiredRef);
        obtainedRefs = List.of(requiredRef);
        reduction.recordLiteralFixtureRead();
      }
      case "CLAIM_WITHOUT_TOOL" -> {
        proposalRefs = List.of(requiredRef);
        obtainedRefs = List.of();
      }
      case "OMITTED_EVIDENCE_REF" -> {
        proposalRefs = List.of();
        obtainedRefs = List.of(requiredRef);
        reduction.recordLiteralFixtureRead();
      }
      case "EXTRA_EVIDENCE_REF" -> {
        proposalRefs =
            List.of(requiredRef, requiredRef + "-forged");
        obtainedRefs = List.of(requiredRef);
        reduction.recordLiteralFixtureRead();
      }
      default -> throw new IllegalStateException("case");
    }
    return new AgentDraftReferenceGrounding.Candidate(
        new AgentDraftProposal(frozen.content(), proposalRefs),
        obtainedRefs,
        requiredRef,
        true);
  }

  private static OfflineComparisonReport.CandidateSnapshot snapshotOf(
      AgentDraftReferenceGrounding.Candidate candidate) {
    AgentDraftProposal proposal = candidate.proposal();
    OfflineComparisonReport.ProposalSnapshot proposalSnapshot =
        proposal == null
            ? null
            : new OfflineComparisonReport.ProposalSnapshot(
                proposal.content(), proposal.evidenceRefs());
    return new OfflineComparisonReport.CandidateSnapshot(
        proposalSnapshot,
        candidate.obtainedEvidenceRefs(),
        candidate.requiredEvidenceRef(),
        candidate.requiredEvidenceAvailable());
  }

  private static ReplayedOutcome replay(
      String armId,
      AgentDraftReferenceGrounding.Candidate candidate,
      ReplayReduction reduction) {
    return switch (armId) {
      case H0_ARM_ID -> replayH0(candidate, reduction);
      case H1_ARM_ID -> replayH1(candidate, reduction);
      default -> throw new IllegalStateException("arm");
    };
  }

  private static ReplayedOutcome replayH0(
      AgentDraftReferenceGrounding.Candidate candidate,
      ReplayReduction reduction) {
    reduction.recordH0Evaluation();
    try {
      if (candidate.proposal() == null) {
        return new ReplayedOutcome(
            "FAILED", "INVALID_STRUCTURED_FINAL");
      }
      ArtifactLineageEntry.requireContent(
          candidate.proposal().content());
      return new ReplayedOutcome("SUCCEEDED", null);
    } catch (IllegalArgumentException invalidContent) {
      return new ReplayedOutcome(
          "FAILED", "INVALID_STRUCTURED_FINAL");
    }
  }

  private static ReplayedOutcome replayH1(
      AgentDraftReferenceGrounding.Candidate candidate,
      ReplayReduction reduction) {
    reduction.recordH1Evaluation();
    AgentDraftReferenceGrounding.Verification verification =
        AgentDraftReferenceGrounding.verify(candidate);
    return verification.accepted()
        ? new ReplayedOutcome("SUCCEEDED", null)
        : new ReplayedOutcome(
            "FAILED", verification.failureCode());
  }

  private static Pack004Loader.ExpectedOutcome expected(
      Pack004Loader.ExpectedByArm expectedByArm, String armId) {
    return switch (armId) {
      case H0_ARM_ID -> expectedByArm.h0SchemaOnly();
      case H1_ARM_ID ->
          expectedByArm.h1ReferenceGrounding();
      default -> throw new IllegalStateException("arm");
    };
  }

  private static String expectedPairHash(
      String pairId,
      String executionBaseId,
      Pack004Loader.CaseDefinition testCase,
      int repetition,
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
            CANDIDATE_GENERATOR_VERSION,
            candidate,
            candidateFingerprint,
            "UNSEALED");
    return ComparisonIntegrityHashes.pairHash(unsealed);
  }

  private static String expectedEvaluationHash(
      String evaluationId,
      String pairId,
      String executionBaseId,
      Pack004Loader.CaseDefinition testCase,
      int repetition,
      Pack004Loader.Arm arm,
      String candidateFingerprint,
      Pack004Loader.ExpectedOutcome expected,
      ReplayedOutcome observed,
      boolean matches) {
    OfflineComparisonReport.VerifierEvaluation unsealed =
        new OfflineComparisonReport.VerifierEvaluation(
            evaluationId,
            pairId,
            executionBaseId,
            testCase.id(),
            repetition,
            arm.id(),
            arm.verifier(),
            candidateFingerprint,
            expected.status(),
            expected.failureReason(),
            observed.status(),
            observed.failureCode(),
            matches,
            "UNSEALED");
    return ComparisonIntegrityHashes.evaluationHash(unsealed);
  }

  private static Verification invalid(String failureCode) {
    return new Verification(Verdict.INVALID, failureCode);
  }

  enum Verdict {
    VERIFIED_PASSED,
    VERIFIED_FAILED,
    INVALID
  }

  static final class Verification {
    private final Verdict verdict;
    private final String failureCode;

    private Verification(Verdict verdict, String failureCode) {
      this.verdict = Objects.requireNonNull(verdict, "verdict");
      this.failureCode = failureCode;
      if ((verdict == Verdict.INVALID) != (failureCode != null)) {
        throw new IllegalArgumentException(
            "invalid verification result");
      }
    }

    Verdict verdict() {
      return verdict;
    }

    String failureCode() {
      return failureCode;
    }
  }

  private record PairKey(String caseId, int repetition) {}

  private record EvaluationKey(
      String caseId, int repetition, String armId) {}

  private record ReplayedOutcome(
      String status, String failureCode) {}

  private static final class ReplayReduction {
    private int candidateGenerations;
    private int verifierEvaluations;
    private int h0Evaluations;
    private int h1Evaluations;
    private int literalFixtureReads;
    private int h0Accepted;
    private int h0FaultAcceptances;
    private int h1Accepted;
    private int h1FaultRejections;
    private int outcomeMismatches;
    private final List<OfflineComparisonReport.Issue> issues =
        new ArrayList<>();

    void recordCandidateGeneration() {
      candidateGenerations++;
    }

    void recordLiteralFixtureRead() {
      literalFixtureReads++;
    }

    void recordH0Evaluation() {
      verifierEvaluations++;
      h0Evaluations++;
    }

    void recordH1Evaluation() {
      verifierEvaluations++;
      h1Evaluations++;
    }

    void observe(
        String armId,
        Pack004Loader.CaseDefinition testCase,
        int repetition,
        ReplayedOutcome observed,
        boolean matches) {
      boolean accepted = "SUCCEEDED".equals(observed.status());
      boolean fault = !"NONE".equals(testCase.faultClass());
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
        issues.add(
            new OfflineComparisonReport.Issue(
                "EXPECTED_OUTCOME_MISMATCH",
                testCase.id(),
                repetition,
                armId));
      }
    }

    OfflineComparisonReport.Summary summary() {
      return new OfflineComparisonReport.Summary(
          candidateGenerations,
          verifierEvaluations,
          h0Accepted,
          h0FaultAcceptances,
          h1Accepted,
          h1FaultRejections,
          outcomeMismatches);
    }

    OfflineComparisonReport.OwnedEffects effects() {
      return new OfflineComparisonReport.OwnedEffects(
          "OWNED_OFFLINE_RUNNER_SEAMS_V1",
          candidateGenerations,
          verifierEvaluations,
          h0Evaluations,
          h1Evaluations,
          literalFixtureReads,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0);
    }

    List<OfflineComparisonReport.Issue> issues() {
      return List.copyOf(issues);
    }
  }
}
