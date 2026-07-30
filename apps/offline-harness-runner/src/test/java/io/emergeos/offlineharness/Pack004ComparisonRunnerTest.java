package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Pack004ComparisonRunnerTest {

  private static final Path REPOSITORY =
      Path.of(System.getProperty("emerge.offline.repo"))
          .toAbsolutePath()
          .normalize();

  @Test
  void executesTwelveSharedCandidatesAsTwentyFourVerifierEvaluations() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);

    assertEquals(12, report.summary().sharedCandidateGenerations());
    assertEquals(24, report.summary().verifierEvaluations());
    assertEquals(12, report.effects().sharedCandidateGenerations());
    assertEquals(24, report.effects().verifierEvaluations());
    assertEquals(12, report.summary().h0Accepted());
    assertEquals(9, report.summary().h0FaultAcceptances());
    assertEquals(3, report.summary().h1Accepted());
    assertEquals(9, report.summary().h1FaultRejections());
    assertEquals(0, report.summary().outcomeMismatches());
    assertEquals(12, report.pairs().size());
    assertEquals(24, report.evaluations().size());
    assertEquals(OfflineComparisonReport.Status.PASSED, report.status());
    assertTrue(report.issues().isEmpty());
    assertEquals(
        ComparisonIntegrityHashes.reportHash(report),
        report.integrityHash());
  }

  @Test
  void preservesCanonicalPairAndArmOrderWithExactOutcomes() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);

    List<String> expectedPairKeys =
        List.of(
            "grounded:1",
            "grounded:2",
            "grounded:3",
            "claim-without-tool:1",
            "claim-without-tool:2",
            "claim-without-tool:3",
            "omitted-claim:1",
            "omitted-claim:2",
            "omitted-claim:3",
            "extra-claim:1",
            "extra-claim:2",
            "extra-claim:3");
    assertEquals(
        expectedPairKeys,
        report.pairs().stream()
            .map(pair -> pair.caseId() + ":" + pair.repetition())
            .toList());

    List<String> expectedEvaluationKeys = new ArrayList<>();
    for (String pairKey : expectedPairKeys) {
      expectedEvaluationKeys.add(pairKey + ":h0-schema-only");
      expectedEvaluationKeys.add(
          pairKey + ":h1-reference-grounding");
    }
    assertEquals(
        expectedEvaluationKeys,
        report.evaluations().stream()
            .map(
                evaluation ->
                    evaluation.caseId()
                        + ":"
                        + evaluation.repetition()
                        + ":"
                        + evaluation.armId())
            .toList());

    assertEquals(
        Set.of("MISSING_REQUIRED_EVIDENCE"),
        h1FailureCodes(report, "claim-without-tool"));
    assertEquals(
        Set.of("INVALID_EVIDENCE_CLAIM"),
        h1FailureCodes(report, "omitted-claim"));
    assertEquals(
        Set.of("INVALID_EVIDENCE_CLAIM"),
        h1FailureCodes(report, "extra-claim"));
    assertTrue(h1FailureCodes(report, "grounded").isEmpty());
    assertEquals(
        3,
        report.evaluations().stream()
            .filter(
                evaluation ->
                    "h1-reference-grounding".equals(
                        evaluation.armId()))
            .filter(
                evaluation ->
                    "MISSING_REQUIRED_EVIDENCE".equals(
                        evaluation.observedFailureCode()))
            .count());
    assertEquals(
        6,
        report.evaluations().stream()
            .filter(
                evaluation ->
                    "h1-reference-grounding".equals(
                        evaluation.armId()))
            .filter(
                evaluation ->
                    "INVALID_EVIDENCE_CLAIM".equals(
                        evaluation.observedFailureCode()))
            .count());
    assertTrue(
        report.evaluations().stream()
            .allMatch(
                evaluation ->
                    evaluation.matchesExpected()
                        && ComparisonIntegrityHashes.evaluationHash(
                                evaluation)
                            .equals(evaluation.integrityHash())));
    assertTrue(
        report.pairs().stream()
            .allMatch(
                pair ->
                    ComparisonIntegrityHashes.pairHash(pair)
                        .equals(pair.integrityHash())));
  }

  @Test
  void recordsTwelveSharedCandidatesAndSameFingerprintAcrossArms() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);

    assertEquals(12, report.effects().sharedCandidateGenerations());
    assertEquals(12, report.effects().h0Evaluations());
    assertEquals(12, report.effects().h1Evaluations());
    assertEquals(
        4,
        new HashSet<>(
                report.pairs().stream()
                    .map(
                        OfflineComparisonReport.PairRecord
                            ::candidateFingerprint)
                    .toList())
            .size());
    for (int index = 0;
        index < report.evaluations().size();
        index += 2) {
      assertEquals(
          report.evaluations().get(index).candidateFingerprint(),
          report.evaluations()
              .get(index + 1)
              .candidateFingerprint());
    }
  }

  @Test
  void matchesIndependentGoldenFingerprintForEveryCandidateMode() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);
    Map<String, String> independentNodeGolden =
        Map.of(
            "grounded",
            "afab18c45f481fe1d48a2a03980b3e5ab2f74f0f7e9831c00626a5ae87f0817a",
            "claim-without-tool",
            "39d9e63b22482d54115c234876a7758473013e5fc19f8467e9f44f63f80fabc6",
            "omitted-claim",
            "688d5c4206db5f086693491b536a01f20f17d8c68e56299b751b37274f554ea6",
            "extra-claim",
            "ce161910c06dc2201d3b9cb125a42236a410e3c21ad8bfc8f080a83d364cc02c");

    assertTrue(
        report.pairs().stream()
            .allMatch(
                pair ->
                    pair.candidateFingerprint()
                        .equals(
                            independentNodeGolden.get(
                                pair.caseId()))));
  }

  @Test
  void identityAuditRejectsASecondWrapperForTheOtherArm() {
    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(REPOSITORY);
    LiteralReferenceCandidateGenerator generator =
        new LiteralReferenceCandidateGenerator();
    var effects =
        new LiteralReferenceCandidateGenerator.OwnedEffectRecorder();
    var original =
        generator.generate(
            loaded.pack().harnessComparison().frozen(),
            loaded.pack().harnessComparison().cases().get(0),
            1,
            effects);
    var secondWrapper =
        new LiteralReferenceCandidateGenerator.GeneratedCandidate(
            original.candidate());
    effects.recordCandidateGeneration(original);
    effects.recordH0Evaluation(original);

    var failure =
        assertThrows(
            LiteralReferenceCandidateGenerator
                .ComparisonExecutionRejected.class,
            () -> effects.recordH1Evaluation(secondWrapper));

    assertEquals(
        "COMPARISON_CANDIDATE_IDENTITY_INVALID", failure.code());
  }

  @Test
  void isByteForByteDeterministicAtTheRecordBoundary() {
    OfflineComparisonReport first =
        new Pack004ComparisonRunner().run(REPOSITORY);
    OfflineComparisonReport second =
        new Pack004ComparisonRunner().run(REPOSITORY);

    assertEquals(first, second);
    assertEquals(first.reportId(), second.reportId());
    assertEquals(first.integrityHash(), second.integrityHash());
    assertFalse(first.reportId().isBlank());
    assertFalse(first.integrityHash().isBlank());
    assertNotEquals(first.reportId(), first.integrityHash());
  }

  @Test
  void reportAndNestedCandidateCollectionsAreImmutable() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);

    assertInstanceOf(
        UnsupportedOperationException.class,
        assertThrows(
            UnsupportedOperationException.class,
            () -> report.pairs().add(report.pairs().get(0))));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            report
                .pairs()
                .get(0)
                .candidate()
                .obtainedEvidenceRefs()
                .clear());
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            report
                .pairs()
                .get(0)
                .candidate()
                .proposal()
                .evidenceRefs()
                .clear());
  }

  @Test
  void generatorRejectsInvalidRepetitionWithOnlyFixedCode() {
    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(REPOSITORY);
    LiteralReferenceCandidateGenerator generator =
        new LiteralReferenceCandidateGenerator();
    var effects =
        new LiteralReferenceCandidateGenerator.OwnedEffectRecorder();

    var failure =
        assertThrows(
            LiteralReferenceCandidateGenerator
                .ComparisonExecutionRejected.class,
            () ->
                generator.generate(
                    loaded.pack().harnessComparison().frozen(),
                    loaded
                        .pack()
                        .harnessComparison()
                        .cases()
                        .get(0),
                    0,
                    effects));

    assertEquals("COMPARISON_GENERATION_FAILED", failure.code());
    assertEquals("COMPARISON_GENERATION_FAILED", failure.getMessage());
  }

  @Test
  void literalReaderCountsOnlyCanonicalSuccessfulReads() {
    Pack004Loader.LoadedPack loaded =
        new Pack004Loader().load(REPOSITORY);
    var effects =
        new LiteralReferenceCandidateGenerator.OwnedEffectRecorder();
    var reader =
        new LiteralReferenceCandidateGenerator
            .LiteralSyntheticCaptureReader();

    var failure =
        assertThrows(
            LiteralReferenceCandidateGenerator
                .ComparisonExecutionRejected.class,
            () ->
                reader.read(
                    loaded.pack().harnessComparison().frozen(),
                    "capture://forged",
                    effects));

    assertEquals("COMPARISON_LITERAL_READ_FAILED", failure.code());
    assertEquals(0, effects.snapshot().literalFixtureReads());
    String content =
        reader.read(
            loaded.pack().harnessComparison().frozen(),
            "capture://capture-s4-o1-reference-004",
            effects);
    assertEquals(
        loaded.pack().harnessComparison().frozen().content(),
        content);
    assertEquals(1, effects.snapshot().literalFixtureReads());
  }

  private static Set<String> h1FailureCodes(
      OfflineComparisonReport report, String caseId) {
    return report.evaluations().stream()
        .filter(
            evaluation ->
                caseId.equals(evaluation.caseId())
                    && "h1-reference-grounding".equals(
                        evaluation.armId()))
        .map(
            OfflineComparisonReport.VerifierEvaluation
                ::observedFailureCode)
        .filter(java.util.Objects::nonNull)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
