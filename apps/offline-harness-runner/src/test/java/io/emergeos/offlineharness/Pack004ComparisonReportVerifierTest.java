package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pack004ComparisonReportVerifierTest {

  private static final Path REPOSITORY =
      Path.of(System.getProperty("emerge.offline.repo"))
          .toAbsolutePath()
          .normalize();

  @Test
  void independentlyReplaysAndVerifiesPassedReport() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(REPOSITORY);

    Pack004ComparisonReportVerifier.Verification verification =
        new Pack004ComparisonReportVerifier().verify(
            REPOSITORY, report);

    assertEquals(
        Pack004ComparisonReportVerifier.Verdict.VERIFIED_PASSED,
        verification.verdict());
    assertEquals(null, verification.failureCode());
  }

  @Test
  void rejectsShapeSchemaPackComponentAndReportIdMutations() {
    OfflineComparisonReport genuine = genuine();
    assertInvalid(null, "REPORT_SHAPE_INVALID");
    assertInvalid(
        reseal(with(genuine, "schemaVersion", "9.9")),
        "REPORT_SCHEMA_MISMATCH");
    assertInvalid(
        reseal(with(genuine, "reportKind", "OTHER")),
        "REPORT_SCHEMA_MISMATCH");
    assertInvalid(
        reseal(with(genuine, "integrityProfile", "other-profile")),
        "REPORT_SCHEMA_MISMATCH");

    for (OfflineComparisonReport mutation :
        List.of(
            reseal(with(genuine, "packRawSha256", "0".repeat(64))),
            reseal(with(genuine, "taskId", "other-task")),
            reseal(with(genuine, "suiteId", "other-suite")),
            reseal(with(genuine, "variable", "other-variable")),
            reseal(with(genuine, "repetitions", 4)),
            reseal(with(genuine, "frozenTime", "2000-01-01T00:00:00Z")))) {
      assertInvalid(mutation, "REPORT_PACK_BINDING_MISMATCH");
    }

    for (OfflineComparisonReport mutation :
        List.of(
            reseal(with(genuine, "runnerVersion", "other-runner")),
            reseal(
                with(
                    genuine,
                    "candidateGeneratorVersion",
                    "other-generator")),
            reseal(
                with(genuine, "h0VerifierVersion", "other-h0")),
            reseal(
                with(genuine, "h1VerifierVersion", "other-h1")))) {
      assertInvalid(
          mutation, "REPORT_COMPONENT_BINDING_MISMATCH");
    }
    assertInvalid(
        reseal(with(genuine, "reportId", "other-report-id")),
        "REPORT_ID_MISMATCH");
  }

  @Test
  void mapsTrustedPackFailureToOneNonLeakingCode() {
    Path missingRoot =
        REPOSITORY.resolve("missing-offline-verifier-root");

    Pack004ComparisonReportVerifier.Verification verification =
        new Pack004ComparisonReportVerifier().verify(
            missingRoot, genuine());

    assertEquals(
        Pack004ComparisonReportVerifier.Verdict.INVALID,
        verification.verdict());
    assertEquals(
        "REPORT_PACK_PRECONDITION_FAILED",
        verification.failureCode());
    assertTrue(
        !verification.failureCode().contains(
            missingRoot.toString()));
  }

  @Test
  void rejectsMissingExtraDuplicateAndReorderedPairs() {
    OfflineComparisonReport genuine = genuine();
    List<OfflineComparisonReport.PairRecord> pairs =
        genuine.pairs();

    assertInvalid(
        withPairs(genuine, pairs.subList(0, pairs.size() - 1)),
        "REPORT_PAIR_MATRIX_MISMATCH");
    List<OfflineComparisonReport.PairRecord> extra =
        new ArrayList<>(pairs);
    extra.add(pairs.get(0));
    assertInvalid(
        withPairs(genuine, extra),
        "REPORT_PAIR_MATRIX_MISMATCH");
    List<OfflineComparisonReport.PairRecord> duplicate =
        new ArrayList<>(pairs);
    duplicate.set(1, pairs.get(0));
    assertInvalid(
        withPairs(genuine, duplicate),
        "REPORT_PAIR_MATRIX_MISMATCH");
    assertInvalid(
        withPairs(genuine, swapped(pairs, 0, 1)),
        "REPORT_ORDER_MISMATCH");
  }

  @Test
  void rejectsMissingExtraDuplicateAndReorderedEvaluations() {
    OfflineComparisonReport genuine = genuine();
    List<OfflineComparisonReport.VerifierEvaluation> evaluations =
        genuine.evaluations();

    assertInvalid(
        withEvaluations(
            genuine,
            evaluations.subList(0, evaluations.size() - 1)),
        "REPORT_EVALUATION_MATRIX_MISMATCH");
    List<OfflineComparisonReport.VerifierEvaluation> extra =
        new ArrayList<>(evaluations);
    extra.add(evaluations.get(0));
    assertInvalid(
        withEvaluations(genuine, extra),
        "REPORT_EVALUATION_MATRIX_MISMATCH");
    List<OfflineComparisonReport.VerifierEvaluation> duplicate =
        new ArrayList<>(evaluations);
    duplicate.set(1, evaluations.get(0));
    assertInvalid(
        withEvaluations(genuine, duplicate),
        "REPORT_EVALUATION_MATRIX_MISMATCH");
    assertInvalid(
        withEvaluations(genuine, swapped(evaluations, 0, 1)),
        "REPORT_ORDER_MISMATCH");
  }

  @Test
  void rejectsPairAndEvaluationBindingMutationsAfterResealing() {
    OfflineComparisonReport genuine = genuine();
    OfflineComparisonReport.PairRecord pair =
        genuine.pairs().get(0);
    OfflineComparisonReport.PairRecord changedPair =
        reseal(
            with(
                pair,
                "executionBaseId",
                pair.executionBaseId() + "-other"));
    assertInvalid(
        replacePair(genuine, 0, changedPair),
        "REPORT_PAIR_BINDING_MISMATCH");

    OfflineComparisonReport.VerifierEvaluation evaluation =
        genuine.evaluations().get(0);
    OfflineComparisonReport.VerifierEvaluation changedEvaluation =
        reseal(
            with(
                evaluation,
                "verifierVersion",
                "other-verifier"));
    assertInvalid(
        replaceEvaluation(genuine, 0, changedEvaluation),
        "REPORT_EVALUATION_BINDING_MISMATCH");
  }

  @Test
  void rejectsFullyRechainedCandidateInputMutations() {
    OfflineComparisonReport genuine = genuine();
    OfflineComparisonReport.CandidateSnapshot original =
        genuine.pairs().get(0).candidate();
    OfflineComparisonReport.ProposalSnapshot proposal =
        original.proposal();
    List<OfflineComparisonReport.CandidateSnapshot> attacks =
        List.of(
            new OfflineComparisonReport.CandidateSnapshot(
                new OfflineComparisonReport.ProposalSnapshot(
                    proposal.content() + "篡改", proposal.evidenceRefs()),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                new OfflineComparisonReport.ProposalSnapshot(
                    proposal.content(), List.of()),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                proposal,
                List.of(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                proposal,
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef() + "-forged",
                original.requiredEvidenceAvailable()),
            new OfflineComparisonReport.CandidateSnapshot(
                proposal,
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                false));

    for (OfflineComparisonReport.CandidateSnapshot attack : attacks) {
      OfflineComparisonReport rechained =
          fullyRechainCandidate(genuine, 0, attack);
      assertEquals(
          ComparisonIntegrityHashes.reportHash(rechained),
          rechained.integrityHash());
      assertTrue(
          rechained.pairs().stream()
              .allMatch(
                  candidatePair ->
                      ComparisonIntegrityHashes.pairHash(candidatePair)
                          .equals(candidatePair.integrityHash())));
      assertTrue(
          rechained.evaluations().stream()
              .allMatch(
                  candidateEvaluation ->
                      ComparisonIntegrityHashes.evaluationHash(
                              candidateEvaluation)
                          .equals(
                              candidateEvaluation.integrityHash())));
      assertInvalid(rechained, "REPORT_CANDIDATE_MISMATCH");
    }
  }

  @Test
  void rejectsExpectationAndObservedOutcomeMutations() {
    OfflineComparisonReport genuine = genuine();
    OfflineComparisonReport.VerifierEvaluation evaluation =
        genuine.evaluations().get(0);

    assertInvalid(
        replaceEvaluation(
            genuine,
            0,
            reseal(with(evaluation, "expectedStatus", "FAILED"))),
        "REPORT_EXPECTATION_MISMATCH");
    assertInvalid(
        replaceEvaluation(
            genuine,
            0,
            reseal(with(evaluation, "observedStatus", "FAILED"))),
        "REPORT_OUTCOME_MISMATCH");
    assertInvalid(
        replaceEvaluation(
            genuine,
            0,
            reseal(with(evaluation, "matchesExpected", false))),
        "REPORT_OUTCOME_MISMATCH");
  }

  @Test
  void rejectsSummaryEffectsIssuesStatusAndNestedHashMutations() {
    OfflineComparisonReport genuine = genuine();
    OfflineComparisonReport.Summary changedSummary =
        with(
            genuine.summary(),
            "sharedCandidateGenerations",
            genuine.summary().sharedCandidateGenerations() + 1);
    assertInvalid(
        reseal(with(genuine, "summary", changedSummary)),
        "REPORT_SUMMARY_MISMATCH");

    OfflineComparisonReport.OwnedEffects changedEffects =
        with(genuine.effects(), "networkCalls", 1);
    assertInvalid(
        reseal(with(genuine, "effects", changedEffects)),
        "REPORT_EFFECTS_MISMATCH");

    List<OfflineComparisonReport.Issue> issues =
        List.of(
            new OfflineComparisonReport.Issue(
                "EXPECTED_OUTCOME_MISMATCH",
                "grounded",
                1,
                "h0-schema-only"));
    assertInvalid(
        reseal(with(genuine, "issues", issues)),
        "REPORT_ISSUES_MISMATCH");
    assertInvalid(
        reseal(
            with(
                genuine,
                "status",
                OfflineComparisonReport.Status.FAILED)),
        "REPORT_STATUS_MISMATCH");

    OfflineComparisonReport.PairRecord damagedPair =
        with(
            genuine.pairs().get(0),
            "integrityHash",
            "0".repeat(64));
    assertInvalid(
        replacePair(genuine, 0, damagedPair),
        "REPORT_INTEGRITY_MISMATCH");
    OfflineComparisonReport.VerifierEvaluation damagedEvaluation =
        with(
            genuine.evaluations().get(0),
            "integrityHash",
            "0".repeat(64));
    assertInvalid(
        replaceEvaluation(genuine, 0, damagedEvaluation),
        "REPORT_INTEGRITY_MISMATCH");
    assertInvalid(
        with(genuine, "integrityHash", "0".repeat(64)),
        "REPORT_INTEGRITY_MISMATCH");
  }

  @Test
  void verifierHasOneClosedEntryPointAndNoInjectionSurface() {
    List<java.lang.reflect.Method> nonPrivateMethods =
        Arrays.stream(
                Pack004ComparisonReportVerifier.class.getDeclaredMethods())
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .toList();

    assertEquals(1, nonPrivateMethods.size());
    java.lang.reflect.Method verify = nonPrivateMethods.get(0);
    assertEquals("verify", verify.getName());
    assertEquals(
        List.of(Path.class, OfflineComparisonReport.class),
        List.of(verify.getParameterTypes()));
    assertEquals(
        Pack004ComparisonReportVerifier.Verification.class,
        verify.getReturnType());
    assertEquals(
        1,
        Pack004ComparisonReportVerifier.class
            .getDeclaredConstructors()
            .length);
    assertEquals(
        0,
        Pack004ComparisonReportVerifier.class
            .getDeclaredConstructors()[0]
            .getParameterCount());
  }

  @Test
  void verificationResultCannotBeForgedBySamePackageCallers() {
    assertTrue(
        Arrays.stream(
                Pack004ComparisonReportVerifier.Verification.class
                    .getDeclaredConstructors())
            .allMatch(
                constructor ->
                    Modifier.isPrivate(constructor.getModifiers())));
    assertEquals(
        List.of(
            Pack004ComparisonReportVerifier.Verdict.VERIFIED_PASSED,
            Pack004ComparisonReportVerifier.Verdict.VERIFIED_FAILED,
            Pack004ComparisonReportVerifier.Verdict.INVALID),
        List.of(
            Pack004ComparisonReportVerifier.Verdict.values()));
  }

  private static OfflineComparisonReport genuine() {
    return new Pack004ComparisonRunner().run(REPOSITORY);
  }

  private static void assertInvalid(
      OfflineComparisonReport report, String expectedCode) {
    Pack004ComparisonReportVerifier.Verification verification =
        new Pack004ComparisonReportVerifier().verify(
            REPOSITORY, report);
    assertEquals(
        Pack004ComparisonReportVerifier.Verdict.INVALID,
        verification.verdict());
    assertEquals(expectedCode, verification.failureCode());
  }

  private static OfflineComparisonReport withPairs(
      OfflineComparisonReport report,
      List<OfflineComparisonReport.PairRecord> pairs) {
    return reseal(with(report, "pairs", List.copyOf(pairs)));
  }

  private static OfflineComparisonReport withEvaluations(
      OfflineComparisonReport report,
      List<OfflineComparisonReport.VerifierEvaluation> evaluations) {
    return reseal(
        with(report, "evaluations", List.copyOf(evaluations)));
  }

  private static OfflineComparisonReport replacePair(
      OfflineComparisonReport report,
      int index,
      OfflineComparisonReport.PairRecord replacement) {
    List<OfflineComparisonReport.PairRecord> pairs =
        replaced(report.pairs(), index, replacement);
    return reseal(with(report, "pairs", pairs));
  }

  private static OfflineComparisonReport replaceEvaluation(
      OfflineComparisonReport report,
      int index,
      OfflineComparisonReport.VerifierEvaluation replacement) {
    List<OfflineComparisonReport.VerifierEvaluation> evaluations =
        replaced(report.evaluations(), index, replacement);
    return reseal(with(report, "evaluations", evaluations));
  }

  private static OfflineComparisonReport fullyRechainCandidate(
      OfflineComparisonReport report,
      int pairIndex,
      OfflineComparisonReport.CandidateSnapshot candidate) {
    OfflineComparisonReport.PairRecord originalPair =
        report.pairs().get(pairIndex);
    String candidateFingerprint =
        ComparisonIntegrityHashes.candidateFingerprint(candidate);
    String pairId =
        ComparisonIntegrityHashes.pairId(
            report.reportId(),
            originalPair.caseId(),
            originalPair.executionBaseId(),
            originalPair.repetition(),
            originalPair.candidateGeneratorVersion(),
            candidateFingerprint);
    OfflineComparisonReport.PairRecord replacementPair =
        reseal(
            new OfflineComparisonReport.PairRecord(
                pairId,
                originalPair.executionBaseId(),
                originalPair.caseId(),
                originalPair.repetition(),
                originalPair.candidateMode(),
                originalPair.faultClass(),
                originalPair.candidateGeneratorVersion(),
                candidate,
                candidateFingerprint,
                "UNSEALED"));
    List<OfflineComparisonReport.PairRecord> pairs =
        replaced(report.pairs(), pairIndex, replacementPair);
    List<OfflineComparisonReport.VerifierEvaluation> evaluations =
        new ArrayList<>(report.evaluations());
    for (int index = pairIndex * 2;
        index < pairIndex * 2 + 2;
        index++) {
      OfflineComparisonReport.VerifierEvaluation original =
          evaluations.get(index);
      String evaluationId =
          ComparisonIntegrityHashes.evaluationId(
              pairId,
              original.armId(),
              original.verifierVersion());
      evaluations.set(
          index,
          reseal(
              new OfflineComparisonReport.VerifierEvaluation(
                  evaluationId,
                  pairId,
                  original.executionBaseId(),
                  original.caseId(),
                  original.repetition(),
                  original.armId(),
                  original.verifierVersion(),
                  candidateFingerprint,
                  original.expectedStatus(),
                  original.expectedFailureCode(),
                  original.observedStatus(),
                  original.observedFailureCode(),
                  original.matchesExpected(),
                  "UNSEALED")));
    }
    OfflineComparisonReport replacedPairs =
        with(report, "pairs", List.copyOf(pairs));
    OfflineComparisonReport replacedEvaluations =
        with(
            replacedPairs,
            "evaluations",
            List.copyOf(evaluations));
    return reseal(replacedEvaluations);
  }

  private static OfflineComparisonReport.PairRecord reseal(
      OfflineComparisonReport.PairRecord pair) {
    return with(
        pair,
        "integrityHash",
        ComparisonIntegrityHashes.pairHash(pair));
  }

  private static OfflineComparisonReport.VerifierEvaluation reseal(
      OfflineComparisonReport.VerifierEvaluation evaluation) {
    return with(
        evaluation,
        "integrityHash",
        ComparisonIntegrityHashes.evaluationHash(evaluation));
  }

  private static OfflineComparisonReport reseal(
      OfflineComparisonReport report) {
    return with(
        report,
        "integrityHash",
        ComparisonIntegrityHashes.reportHash(report));
  }

  private static <T> List<T> replaced(
      List<T> source, int index, T replacement) {
    List<T> result = new ArrayList<>(source);
    result.set(index, replacement);
    return List.copyOf(result);
  }

  private static <T> List<T> swapped(
      List<T> source, int first, int second) {
    List<T> result = new ArrayList<>(source);
    T previous = result.get(first);
    result.set(first, result.get(second));
    result.set(second, previous);
    return List.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private static <R extends Record> R with(
      R source, String componentName, Object replacement) {
    try {
      var components = source.getClass().getRecordComponents();
      Class<?>[] types = new Class<?>[components.length];
      Object[] values = new Object[components.length];
      boolean replaced = false;
      for (int index = 0; index < components.length; index++) {
        types[index] = components[index].getType();
        values[index] =
            components[index].getAccessor().invoke(source);
        if (components[index].getName().equals(componentName)) {
          values[index] = replacement;
          replaced = true;
        }
      }
      if (!replaced) {
        throw new AssertionError(
            "unknown record component: " + componentName);
      }
      var constructor =
          source.getClass().getDeclaredConstructor(types);
      if (!constructor.trySetAccessible()) {
        throw new AssertionError("record constructor unavailable");
      }
      return (R) constructor.newInstance(values);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }
}
