package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.HarnessEvaluationReport.Evaluation;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorEffects;
import io.emergeos.contracts.HarnessEvaluationReport.GraphOutcome;
import io.emergeos.contracts.HarnessEvaluationReport.RepetitionReport;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalSealWitness;
import io.emergeos.contracts.HarnessEvaluationReport.UsageAggregate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessEvaluationReportTest {

  @Test
  void createsCompleteMixedAndAllSuccessReportsWithoutSlotOverfit() {
    HarnessEvaluationReport mixed =
        HarnessEvaluationReportFixture.mixed();
    HarnessEvaluationReport allSuccess =
        HarnessEvaluationReportFixture.allSuccess();

    assertEquals(3, mixed.repetitions().size());
    assertEquals(6, mixed.evaluations().size());
    assertEquals(
        HarnessEvaluationReport.ReportStatus.COMPLETE,
        mixed.reportStatus());
    assertEquals(
        mixed.integrityHash(),
        IntegrityHashes.harnessEvaluationReportHash(mixed));
    assertEquals(
        mixed.reportId(),
        IntegrityHashes.harnessEvaluationReportId(mixed));
    assertEquals(
        "harness-evaluation-report-01c1b2dd6eaedee7a2abf6bd865616cba2802de9076f0ee34145fb7251a5f14c",
        mixed.reportId());
    assertEquals(
        "3294d622073f72c2a8dac57468164eaf489c2ea48d7905bd7c86d8facc22fd8f",
        mixed.integrityHash());
    assertNotEquals(mixed.reportId(), mixed.integrityHash());
    assertDoesNotThrow(() -> allSuccess);
  }

  @Test
  void rejectsPartialDuplicateOrOutOfOrderRepetitions() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions().subList(0, 2),
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    List<RepetitionReport> duplicate =
        List.of(
            valid.repetitions().get(0),
            valid.repetitions().get(0),
            valid.repetitions().get(2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                duplicate,
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    List<RepetitionReport> swapped =
        List.of(
            valid.repetitions().get(1),
            valid.repetitions().get(0),
            valid.repetitions().get(2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                swapped,
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));
  }

  @Test
  void rejectsArmEvaluationAndCandidateAliasingDrift() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReport.create(
                valid.graphProtocolVersion(),
                valid.packRawSha256(),
                valid.environmentRawSha256(),
                valid.evaluatorArms().reversed(),
                valid.repetitions(),
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    List<Evaluation> swapped =
        new ArrayList<>(valid.evaluations());
    Evaluation first = swapped.get(0);
    swapped.set(0, swapped.get(1));
    swapped.set(1, first);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                swapped,
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    List<Evaluation> aliased =
        new ArrayList<>(valid.evaluations());
    Evaluation original = aliased.get(2);
    aliased.set(
        2,
        new Evaluation(
            original.repetition(),
            original.armId(),
            original.evaluatorVersion(),
            valid.repetitions().get(0).candidate().candidateRef(),
            valid.repetitions().get(0).candidate().integrityHash(),
            original.status(),
            original.failureCode()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                aliased,
                valid.usageAggregate(),
                valid.evaluatorEffects()));
  }

  @Test
  void rejectsCrossRepetitionTaskAliasingEvenWhenHashesAreRecomputed() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    String firstChildTaskId =
        valid
            .repetitions()
            .get(0)
            .childRun()
            .bundle()
            .taskId();
    RepetitionReport originalSecond = valid.repetitions().get(1);
    RepetitionReport aliasedSecond =
        HarnessEvaluationReportFixture.repetitionWithTaskIds(
            2,
            originalSecond.executionSlotId(),
            false,
            originalSecond.parentRun().bundle().taskId(),
            firstChildTaskId);
    List<RepetitionReport> repetitions =
        new ArrayList<>(valid.repetitions());
    repetitions.set(1, aliasedSecond);
    List<Evaluation> evaluations =
        new ArrayList<>(valid.evaluations());
    for (int index : List.of(2, 3)) {
      Evaluation original = evaluations.get(index);
      evaluations.set(
          index,
          new Evaluation(
              original.repetition(),
              original.armId(),
              original.evaluatorVersion(),
              aliasedSecond.candidate().candidateRef(),
              aliasedSecond.candidate().integrityHash(),
              original.status(),
              original.failureCode()));
    }

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                List.copyOf(repetitions),
                List.copyOf(evaluations),
                valid.usageAggregate(),
                valid.evaluatorEffects()));
  }

  @Test
  void rejectsUsageAndAnyNonZeroEvaluatorEffect() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    UsageAggregate changedUsage =
        new UsageAggregate(
            6,
            91,
            0,
            45,
            15,
            136,
            new BigDecimal("0.000135"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                valid.evaluations(),
                changedUsage,
                valid.evaluatorEffects()));

    EvaluatorEffects networked =
        new EvaluatorEffects(3, 6, 0, 0, 1, 0, 0, 0, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                valid.evaluations(),
                valid.usageAggregate(),
                networked));
  }

  @Test
  void rejectsSealRunAndSuccessFailureShapeDrift() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    RepetitionReport success = valid.repetitions().get(0);
    TerminalSealWitness seal = success.terminalSeal();
    TerminalSealWitness changedSeal =
        new TerminalSealWitness(
            seal.attemptId(),
            seal.manifestHash(),
            seal.finalSequence(),
            seal.preSealHeadHash(),
            seal.finalHeadHash(),
            GraphOutcome.FAILED,
            seal.billingStatus(),
            seal.providerAttributionHashes(),
            seal.candidateRef(),
            seal.candidateIntegrityHash(),
            seal.childTerminalHash(),
            seal.parentTerminalHash(),
            seal.sealHash(),
            seal.sealedAt());
    RepetitionReport changed =
        new RepetitionReport(
            success.repetition(),
            success.executionSlotId(),
            success.attemptId(),
            success.manifestHash(),
            success.providerAttributions(),
            success.parentRun(),
            success.childRun(),
            success.candidate(),
            success.workerResult(),
            success.artifactBinding(),
            changedSeal);
    List<RepetitionReport> changedRepetitions =
        new ArrayList<>(valid.repetitions());
    changedRepetitions.set(0, changed);
    List<RepetitionReport> changedSnapshot =
        List.copyOf(changedRepetitions);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                changedSnapshot,
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    RepetitionReport rejected = valid.repetitions().get(1);
    RepetitionReport rejectionWithWorker =
        new RepetitionReport(
            rejected.repetition(),
            rejected.executionSlotId(),
            rejected.attemptId(),
            rejected.manifestHash(),
            rejected.providerAttributions(),
            rejected.parentRun(),
            rejected.childRun(),
            rejected.candidate(),
            success.workerResult(),
            null,
            rejected.terminalSeal());
    List<RepetitionReport> workerRepetitions =
        new ArrayList<>(valid.repetitions());
    workerRepetitions.set(1, rejectionWithWorker);
    List<RepetitionReport> tampered =
        List.copyOf(workerRepetitions);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                tampered,
                valid.evaluations(),
                valid.usageAggregate(),
                valid.evaluatorEffects()));
  }

  @Test
  void defensivelyCopiesListsAndRedactsCandidateAndWorkerContent() {
    HarnessEvaluationReport report =
        HarnessEvaluationReportFixture.mixed();
    String candidateContent =
        report.repetitions().get(0).candidate().content();
    String workerContent =
        report.repetitions().get(0).workerResult().content();

    assertThrows(
        UnsupportedOperationException.class,
        () -> report.repetitions().add(report.repetitions().get(0)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> report.evaluations().clear());
    assertFalse(report.toString().contains(candidateContent));
    assertFalse(report.toString().contains(workerContent));
  }

  @Test
  void h0CannotRejectAndH1MustMatchGraphOutcome() {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    List<Evaluation> h0Evaluations =
        new ArrayList<>(valid.evaluations());
    Evaluation h0 = h0Evaluations.get(0);
    h0Evaluations.set(
        0,
        new Evaluation(
            h0.repetition(),
            h0.armId(),
            h0.evaluatorVersion(),
            h0.candidateRef(),
            h0.candidateIntegrityHash(),
            EvaluationStatus.REJECTED,
            "INVALID_EVIDENCE_CLAIM"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                List.copyOf(h0Evaluations),
                valid.usageAggregate(),
                valid.evaluatorEffects()));

    List<Evaluation> h1Evaluations =
        new ArrayList<>(valid.evaluations());
    Evaluation h1 = h1Evaluations.get(1);
    h1Evaluations.set(
        1,
        new Evaluation(
            h1.repetition(),
            h1.armId(),
            h1.evaluatorVersion(),
            h1.candidateRef(),
            h1.candidateIntegrityHash(),
            EvaluationStatus.REJECTED,
            "INVALID_EVIDENCE_CLAIM"));
    List<Evaluation> mismatch = List.copyOf(h1Evaluations);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessEvaluationReportFixture.create(
                valid.repetitions(),
                mismatch,
                valid.usageAggregate(),
                valid.evaluatorEffects()));
  }

  @Test
  void checkedJsonFixturesExactlyReplayFromTheJavaGenerator()
      throws Exception {
    Path root = repositoryRoot();
    Map<String, Path> fixtures = new LinkedHashMap<>();
    Path directory =
        root.resolve(
            "contracts/fixtures/v1/harness-evaluation-report");
    fixtures.put("valid", directory.resolve("valid-complete.json"));
    fixtures.put(
        "invalid-extra", directory.resolve("invalid-extra-field.json"));
    fixtures.put(
        "invalid-repetition-order",
        directory.resolve(
            "invalid-repetition-order-correct-integrity.json"));
    fixtures.put(
        "invalid-candidate-alias",
        directory.resolve(
            "invalid-candidate-alias-correct-integrity.json"));
    fixtures.put(
        "invalid-usage",
        directory.resolve(
            "invalid-usage-aggregate-correct-integrity.json"));
    fixtures.put(
        "invalid-network-effect",
        directory.resolve(
            "invalid-network-effect-correct-integrity.json"));
    fixtures.put(
        "invalid-h1-outcome",
        directory.resolve(
            "invalid-h1-outcome-correct-integrity.json"));
    fixtures.put(
        "invalid-partial",
        directory.resolve("invalid-partial-correct-integrity.json"));
    fixtures.put(
        "invalid-self-consistent-h1",
        directory.resolve(
            "invalid-self-consistent-h1-correct-integrity.json"));
    fixtures.put(
        "golden",
        root.resolve(
            "contracts/golden/v1/"
                + "harness-evaluation-report-integrity-hashes.json"));

    for (Map.Entry<String, Path> fixture : fixtures.entrySet()) {
      assertEquals(
          HarnessEvaluationReportFixtureJsonMain.render(
                  fixture.getKey())
              + System.lineSeparator(),
          Files.readString(fixture.getValue()),
          fixture.getValue().toString());
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("contracts/fixtures/v1"))
          && Files.isRegularFile(current.resolve("pom.xml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
