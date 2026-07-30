package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonIntegrityHashesTest {

  private static final Path REPOSITORY =
      Path.of(System.getProperty("emerge.offline.repo"))
          .toAbsolutePath()
          .normalize();

  @Test
  void candidateSnapshotMatchesIndependentNodeGoldenVector() {
    String required =
        "capture://capture-s4-o1-reference-004";
    OfflineComparisonReport.CandidateSnapshot candidate =
        new OfflineComparisonReport.CandidateSnapshot(
            new OfflineComparisonReport.ProposalSnapshot(
                "虚构人物小澜：引用已落地。", List.of(required)),
            List.of(required),
            required,
            true);

    assertEquals(
        "17f33ede677f8ebbbd51aa3e14f8058be4e5b3abc862e67ba7c15b7a5f0ded3b",
        ComparisonIntegrityHashes.candidateFingerprint(candidate));
  }

  @Test
  void everyVerifierInputAndReferenceOrderChangesFingerprint() {
    String required = "capture://required";
    OfflineComparisonReport.CandidateSnapshot original =
        new OfflineComparisonReport.CandidateSnapshot(
            new OfflineComparisonReport.ProposalSnapshot(
                "content", List.of(required, "capture://second")),
            List.of(required, "capture://second"),
            required,
            true);
    String originalHash =
        ComparisonIntegrityHashes.candidateFingerprint(original);
    List<OfflineComparisonReport.CandidateSnapshot> mutations =
        List.of(
            new OfflineComparisonReport.CandidateSnapshot(
                null,
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                new OfflineComparisonReport.ProposalSnapshot(
                    "other", original.proposal().evidenceRefs()),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                new OfflineComparisonReport.ProposalSnapshot(
                    original.proposal().content(),
                    List.of("capture://second", required)),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                original.proposal(),
                List.of("capture://second", required),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                original.proposal(),
                original.obtainedEvidenceRefs(),
                "capture://other",
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                original.proposal(),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                false));

    for (OfflineComparisonReport.CandidateSnapshot mutation :
        mutations) {
      assertNotEquals(
          originalHash,
          ComparisonIntegrityHashes.candidateFingerprint(mutation));
    }
  }

  @Test
  void reportIdentityBindsRunnerVersionWithoutObservedResults() {
    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(REPOSITORY);

    String current =
        ComparisonIntegrityHashes.reportId(
            "1.0",
            "OFFLINE_VERIFIER_COMPARISON",
            "pack004-comparison-runner-v1",
            loaded);
    String changedRunner =
        ComparisonIntegrityHashes.reportId(
            "1.0",
            "OFFLINE_VERIFIER_COMPARISON",
            "pack004-comparison-runner-v2",
            loaded);

    assertNotEquals(current, changedRunner);
  }

  @Test
  void pairAndEvaluationIdsUseSeparateDomains() {
    String pairId =
        ComparisonIntegrityHashes.pairId(
            "report",
            "case",
            "execution",
            1,
            "generator",
            "fingerprint");
    String evaluationId =
        ComparisonIntegrityHashes.evaluationId(
            pairId, "arm", "verifier");

    assertNotEquals(pairId, evaluationId);
    assertEquals(true, pairId.startsWith("comparison-pair-"));
    assertEquals(
        true,
        evaluationId.startsWith("comparison-evaluation-"));
  }
}
