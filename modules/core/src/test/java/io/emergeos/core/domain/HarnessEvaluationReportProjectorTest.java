package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.HarnessEvaluationReport;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.core.application.HarnessEvaluationReportProjector;
import io.emergeos.core.port.GraphAttemptReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessEvaluationReportProjectorTest {

  @Test
  void projectsOneCompleteMixedReportFromThreeOrderedVerifiedSnapshots() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    RecordingReader reader = RecordingReader.valid(snapshots);
    HarnessEvaluationReportProjector projector =
        new HarnessEvaluationReportProjector(reader);

    HarnessEvaluationReportProjector.Reduction.Complete complete =
        assertInstanceOf(
            HarnessEvaluationReportProjector.Reduction.Complete.class,
            projector.project(manifests(snapshots)));
    HarnessEvaluationReport report = complete.report();

    assertEquals(3, reader.reads.size());
    assertEquals(3, report.repetitions().size());
    assertEquals(6, report.evaluations().size());
    assertEquals(
        List.of(
            EvaluationStatus.ACCEPTED,
            EvaluationStatus.ACCEPTED,
            EvaluationStatus.ACCEPTED,
            EvaluationStatus.REJECTED,
            EvaluationStatus.ACCEPTED,
            EvaluationStatus.ACCEPTED),
        report.evaluations().stream()
            .map(HarnessEvaluationReport.Evaluation::status)
            .toList());
    assertEquals("INVALID_EVIDENCE_CLAIM", report.evaluations().get(3).failureCode());
    assertEquals(6, report.usageAggregate().providerRequests());
    assertEquals(90, report.usageAggregate().totalTokens());
    assertEquals("0.000570", report.usageAggregate().observedCostUsd().toPlainString());
    assertEquals(3, report.evaluatorEffects().sharedCandidateInputs());
    assertEquals(6, report.evaluatorEffects().evaluations());
    assertEquals(0, report.evaluatorEffects().modelInvocations());
    assertEquals(0, report.evaluatorEffects().toolExecutions());
    assertEquals(0, report.evaluatorEffects().networkCalls());
    assertEquals(0, report.evaluatorEffects().credentialReads());
    assertEquals(0, report.evaluatorEffects().connectorCalls());
    assertEquals(0, report.evaluatorEffects().productTruthWrites());
    assertEquals(0, report.evaluatorEffects().externalSideEffects());
    assertEquals(0, report.evaluatorEffects().realUserDataReads());
    assertNotEquals(report.reportId(), report.integrityHash());
    assertEquals(
        6,
        report.repetitions().stream()
            .flatMap(
                repetition ->
                    java.util.stream.Stream.of(
                        repetition.parentRun().bundle().runId(),
                        repetition.childRun().bundle().runId()))
            .distinct()
            .count());
    assertEquals(
        3,
        report.repetitions().stream()
            .map(repetition -> repetition.candidate().candidateRef())
            .distinct()
            .count());
  }

  @Test
  void missingOrInvalidRepetitionStopsBeforeReadingTheNextSlot() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    RecordingReader missing =
        new RecordingReader(
            List.of(new GraphAttemptVerification.Missing()));
    assertUnavailable(
        new HarnessEvaluationReportProjector(missing)
            .project(manifests(snapshots)),
        "REPETITION_MISSING",
        1);
    assertEquals(1, missing.reads.size());

    RecordingReader invalid =
        new RecordingReader(
            List.of(
                new GraphAttemptVerification.Valid(snapshots.get(0)),
                new GraphAttemptVerification.Invalid("STORED_GRAPH_INVALID")));
    assertUnavailable(
        new HarnessEvaluationReportProjector(invalid)
            .project(manifests(snapshots)),
        "REPETITION_INVALID",
        2);
    assertEquals(2, invalid.reads.size());
  }

  @Test
  void seq15OrPreCandidateSeq17NeverCreatesAReport() {
    GraphAttemptSnapshot seq15 =
        GraphAttemptTerminalProtocolTest.childTerminalSnapshot(1);
    RecordingReader incomplete = RecordingReader.valid(List.of(seq15));
    List<GraphAttemptManifest> expected =
        List.of(
            seq15.manifest(),
            GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(2, false)
                .manifest(),
            GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(3, true)
                .manifest());
    assertUnavailable(
        new HarnessEvaluationReportProjector(incomplete).project(expected),
        "REPETITION_NOT_COMPLETE",
        1);
    assertEquals(1, incomplete.reads.size());

    GraphAttemptSnapshot noCandidate =
        GraphAttemptTerminalProtocolTest.preCandidateTerminalSnapshot(1);
    RecordingReader ineligible = RecordingReader.valid(List.of(noCandidate));
    expected =
        List.of(
            noCandidate.manifest(), expected.get(1), expected.get(2));
    assertUnavailable(
        new HarnessEvaluationReportProjector(ineligible).project(expected),
        "REPETITION_NOT_REPORT_ELIGIBLE",
        1);
    assertEquals(1, ineligible.reads.size());
  }

  @Test
  void malformedManifestSetIsRejectedBeforeAnyRead() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    List<GraphAttemptManifest> reordered =
        List.of(
            snapshots.get(1).manifest(),
            snapshots.get(0).manifest(),
            snapshots.get(2).manifest());
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader).project(reordered),
        "MANIFEST_SET_INVALID",
        0);
    assertEquals(0, reader.reads.size());
  }

  @Test
  void crossRepetitionRunAliasIsRejectedBeforeAnyRead() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    GraphAttemptManifest second = snapshots.get(1).manifest();
    GraphRunSelection aliasedParent =
        copySelection(
            second.parentSelection(),
            snapshots.get(0).manifest().parentSelection().runId());
    List<GraphAttemptManifest> aliased =
        List.of(
            snapshots.get(0).manifest(),
            copyManifest(second, second.caseId(), aliasedParent),
            snapshots.get(2).manifest());
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader).project(aliased),
        "MANIFEST_SET_INVALID",
        0);
    assertEquals(0, reader.reads.size());
  }

  @Test
  void crossRepetitionTaskAliasIsRejectedBeforeAnyRead() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    GraphAttemptManifest second = snapshots.get(1).manifest();
    GraphRunSelection aliasedParent =
        copySelectionWithTaskId(
            second.parentSelection(),
            snapshots.get(0).manifest().parentSelection().taskId());
    List<GraphAttemptManifest> aliased =
        List.of(
            snapshots.get(0).manifest(),
            copyManifest(second, second.caseId(), aliasedParent),
            snapshots.get(2).manifest());
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader).project(aliased),
        "MANIFEST_SET_INVALID",
        0);
    assertEquals(0, reader.reads.size());
  }

  @Test
  void validSnapshotForDifferentExpectedManifestFailsClosed() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    GraphAttemptManifest first = snapshots.get(0).manifest();
    List<GraphAttemptManifest> expected =
        List.of(
            copyManifest(
                first, first.caseId() + "-drift", first.parentSelection()),
            snapshots.get(1).manifest(),
            snapshots.get(2).manifest());
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader).project(expected),
        "REPETITION_MISMATCH",
        1);
    assertEquals(1, reader.reads.size());
  }

  @Test
  void selectionFingerprintsMustMatchTheTerminalRunBundles() {
    List<GraphAttemptSnapshot> baseline = eligibleSnapshots();
    GraphAttemptSnapshot executionDrift =
        GraphAttemptTerminalProtocolTest
            .reportEligibleSnapshotWithChildExecutionFingerprintDrift(1);
    List<GraphAttemptSnapshot> snapshots =
        List.of(executionDrift, baseline.get(1), baseline.get(2));
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader)
            .project(manifests(snapshots)),
        "REPETITION_NOT_REPORT_ELIGIBLE",
        1);
    assertEquals(1, reader.reads.size());

    GraphAttemptSnapshot workerDrift =
        GraphAttemptTerminalProtocolTest
            .reportEligibleSnapshotWithSharedWorkerFingerprintDrift(1);
    snapshots = List.of(workerDrift, baseline.get(1), baseline.get(2));
    reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader)
            .project(manifests(snapshots)),
        "REPETITION_NOT_REPORT_ELIGIBLE",
        1);
    assertEquals(1, reader.reads.size());
  }

  @Test
  void providerNamespaceDriftCannotMasqueradeAsPack010() {
    List<GraphAttemptSnapshot> snapshots =
        List.of(
            GraphAttemptTerminalProtocolTest
                .reportEligibleSnapshotWithProviderNamespace(
                    1, true, "other.responses"),
            GraphAttemptTerminalProtocolTest
                .reportEligibleSnapshotWithProviderNamespace(
                    2, false, "other.responses"),
            GraphAttemptTerminalProtocolTest
                .reportEligibleSnapshotWithProviderNamespace(
                    3, true, "other.responses"));
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader)
            .project(manifests(snapshots)),
        "REPETITION_NOT_REPORT_ELIGIBLE",
        1);
    assertEquals(1, reader.reads.size());
  }

  @Test
  void modelBoundParentCannotMasqueradeAsThePack010Conductor() {
    List<GraphAttemptSnapshot> baseline = eligibleSnapshots();
    GraphAttemptSnapshot modelBoundParent =
        GraphAttemptTerminalProtocolTest
            .reportEligibleSnapshotWithModelBoundParent(1);
    List<GraphAttemptSnapshot> snapshots =
        List.of(modelBoundParent, baseline.get(1), baseline.get(2));
    RecordingReader reader = RecordingReader.valid(snapshots);

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader)
            .project(manifests(snapshots)),
        "REPETITION_NOT_REPORT_ELIGIBLE",
        1);
    assertEquals(1, reader.reads.size());
  }

  @Test
  void unavailableThirdRepetitionNeverCreatesAPartialReport() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    RecordingReader reader =
        new RecordingReader(
            List.of(
                new GraphAttemptVerification.Valid(snapshots.get(0)),
                new GraphAttemptVerification.Valid(snapshots.get(1)),
                new GraphAttemptVerification.Missing()));

    assertUnavailable(
        new HarnessEvaluationReportProjector(reader)
            .project(manifests(snapshots)),
        "REPETITION_MISSING",
        3);
    assertEquals(3, reader.reads.size());
  }

  @Test
  void callerManifestListIsDefensivelyCopiedBeforeReadsBegin() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    List<GraphAttemptManifest> callerOwned =
        new ArrayList<>(manifests(snapshots));
    MutatingReader reader = new MutatingReader(snapshots, callerOwned);

    assertInstanceOf(
        HarnessEvaluationReportProjector.Reduction.Complete.class,
        new HarnessEvaluationReportProjector(reader).project(callerOwned));
    assertTrue(callerOwned.isEmpty());
    assertEquals(3, reader.reads.size());
  }

  @Test
  void repeatedProjectionFromTheSameTruthIsExactlyDeterministic() {
    List<GraphAttemptSnapshot> snapshots = eligibleSnapshots();
    HarnessEvaluationReport first =
        assertInstanceOf(
                HarnessEvaluationReportProjector.Reduction.Complete.class,
                new HarnessEvaluationReportProjector(
                        RecordingReader.valid(snapshots))
                    .project(manifests(snapshots)))
            .report();
    HarnessEvaluationReport second =
        assertInstanceOf(
                HarnessEvaluationReportProjector.Reduction.Complete.class,
                new HarnessEvaluationReportProjector(
                        RecordingReader.valid(snapshots))
                    .project(manifests(snapshots)))
            .report();

    assertEquals(first, second);
    assertEquals(first.reportId(), second.reportId());
    assertEquals(first.integrityHash(), second.integrityHash());
  }

  private static List<GraphAttemptSnapshot> eligibleSnapshots() {
    return List.of(
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(1, true),
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(2, false),
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(3, true));
  }

  private static List<GraphAttemptManifest> manifests(
      List<GraphAttemptSnapshot> snapshots) {
    return snapshots.stream()
        .map(GraphAttemptSnapshot::manifest)
        .toList();
  }

  private static GraphRunSelection copySelection(
      GraphRunSelection source, String runId) {
    return new GraphRunSelection(
        source.role(),
        runId,
        source.taskId(),
        source.taskHash(),
        source.executionProfileId(),
        source.executionProfileFingerprint(),
        source.workerRegistryVersion(),
        source.workerProfileId(),
        source.workerProfileFingerprint());
  }

  private static GraphRunSelection copySelectionWithTaskId(
      GraphRunSelection source, String taskId) {
    return new GraphRunSelection(
        source.role(),
        source.runId(),
        taskId,
        source.taskHash(),
        source.executionProfileId(),
        source.executionProfileFingerprint(),
        source.workerRegistryVersion(),
        source.workerProfileId(),
        source.workerProfileFingerprint());
  }

  private static GraphAttemptManifest copyManifest(
      GraphAttemptManifest source,
      String caseId,
      GraphRunSelection parentSelection) {
    return GraphAttemptManifest.create(
        source.graphProtocolVersion(),
        source.principalId(),
        source.executionSlotId(),
        caseId,
        source.packRawSha256(),
        source.environmentRawSha256(),
        source.captureId(),
        source.captureRequestHash(),
        source.artifactId(),
        source.startedAt(),
        source.pricingProfileFingerprint(),
        source.promptSurfaceFingerprint(),
        source.conductorSurfaceFingerprint(),
        source.reservationUsd(),
        source.maximumProviderRequests(),
        source.parentActor(),
        source.childActor(),
        source.experiment(),
        parentSelection,
        source.childSelection());
  }

  private static void assertUnavailable(
      HarnessEvaluationReportProjector.Reduction reduction,
      String reasonCode,
      int repetition) {
    HarnessEvaluationReportProjector.Reduction.Unavailable unavailable =
        assertInstanceOf(
            HarnessEvaluationReportProjector.Reduction.Unavailable.class,
            reduction);
    assertEquals(reasonCode, unavailable.reasonCode());
    assertEquals(repetition, unavailable.repetition());
  }

  private static final class RecordingReader
      implements GraphAttemptReader {

    private final List<GraphAttemptVerification> responses;
    private final List<GraphAttemptManifest> reads = new ArrayList<>();

    private RecordingReader(
        List<GraphAttemptVerification> responses) {
      this.responses = List.copyOf(responses);
    }

    static RecordingReader valid(
        List<GraphAttemptSnapshot> snapshots) {
      return new RecordingReader(
          snapshots.stream()
              .map(GraphAttemptVerification.Valid::new)
              .map(value -> (GraphAttemptVerification) value)
              .toList());
    }

    @Override
    public GraphAttemptVerification findVerified(
        GraphAttemptManifest expected) {
      reads.add(expected);
      if (reads.size() > responses.size()) {
        throw new AssertionError("projector read beyond the scripted boundary");
      }
      return responses.get(reads.size() - 1);
    }
  }

  private static final class MutatingReader
      implements GraphAttemptReader {

    private final List<GraphAttemptSnapshot> snapshots;
    private final List<GraphAttemptManifest> callerOwned;
    private final List<GraphAttemptManifest> reads = new ArrayList<>();

    private MutatingReader(
        List<GraphAttemptSnapshot> snapshots,
        List<GraphAttemptManifest> callerOwned) {
      this.snapshots = List.copyOf(snapshots);
      this.callerOwned = callerOwned;
    }

    @Override
    public GraphAttemptVerification findVerified(
        GraphAttemptManifest expected) {
      reads.add(expected);
      if (reads.size() == 1) {
        callerOwned.clear();
      }
      return new GraphAttemptVerification.Valid(
          snapshots.get(reads.size() - 1));
    }
  }
}
