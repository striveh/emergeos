package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.HarnessEvaluationReport;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.port.GraphAttemptReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read-only orchestration boundary for complete Harness Report projection. */
public final class HarnessEvaluationReportProjector {

  private final GraphAttemptReader reader;

  public HarnessEvaluationReportProjector(GraphAttemptReader reader) {
    this.reader = Objects.requireNonNull(reader, "reader");
  }

  public Reduction project(
      List<GraphAttemptManifest> orderedExpectedManifests) {
    Objects.requireNonNull(
        orderedExpectedManifests, "orderedExpectedManifests");
    List<GraphAttemptManifest> manifests;
    try {
      manifests = List.copyOf(orderedExpectedManifests);
    } catch (NullPointerException invalidManifest) {
      return new Reduction.Unavailable("MANIFEST_SET_INVALID", 0);
    }
    if (!validManifestSet(manifests)) {
      return new Reduction.Unavailable("MANIFEST_SET_INVALID", 0);
    }
    List<GraphAttemptSnapshot> snapshots = new ArrayList<>(3);
    for (int index = 0;
        index < manifests.size();
        index++) {
      int repetition = index + 1;
      GraphAttemptManifest expected =
          manifests.get(index);
      GraphAttemptVerification verification =
          reader.findVerified(expected);
      if (verification instanceof GraphAttemptVerification.Missing) {
        return new Reduction.Unavailable(
            "REPETITION_MISSING", repetition);
      }
      if (verification instanceof GraphAttemptVerification.Invalid
          || verification == null) {
        return new Reduction.Unavailable(
            "REPETITION_INVALID", repetition);
      }
      GraphAttemptSnapshot snapshot =
          ((GraphAttemptVerification.Valid) verification).snapshot();
      try {
        HarnessEvaluationReportReducer.requireReportEligible(
            snapshot, expected, repetition);
      } catch (
          HarnessEvaluationReportReducer.ReductionFailure failure) {
        return new Reduction.Unavailable(
            failure.reasonCode(), failure.repetition());
      }
      snapshots.add(snapshot);
    }
    try {
      return new Reduction.Complete(
          HarnessEvaluationReportReducer.reduce(snapshots));
    } catch (HarnessEvaluationReportReducer.ReductionFailure failure) {
      return new Reduction.Unavailable(
          failure.reasonCode(), failure.repetition());
    }
  }

  private static boolean validManifestSet(
      List<GraphAttemptManifest> manifests) {
    if (manifests.size() != 3
        || manifests.stream().anyMatch(Objects::isNull)) {
      return false;
    }
    GraphAttemptManifest baseline = manifests.getFirst();
    Set<String> attempts = new HashSet<>();
    Set<String> runIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();
    for (int index = 0; index < manifests.size(); index++) {
      int repetition = index + 1;
      GraphAttemptManifest manifest = manifests.get(index);
      if (manifest.experiment().repetition() != repetition
          || !manifest.executionSlotId().equals(
              "pack010-r" + repetition)
          || manifest.maximumProviderRequests() != 2
          || !HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION.equals(
              manifest.graphProtocolVersion())
          || !attempts.add(manifest.attemptId())
          || !runIds.add(manifest.parentSelection().runId())
          || !runIds.add(manifest.childSelection().runId())
          || !taskIds.add(manifest.parentSelection().taskId())
          || !taskIds.add(manifest.childSelection().taskId())
          || !sameFrozenManifestSurface(baseline, manifest)
          || !sameSelectionSurface(
              baseline.parentSelection(),
              manifest.parentSelection())
          || !sameSelectionSurface(
              baseline.childSelection(),
              manifest.childSelection())) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameFrozenManifestSurface(
      GraphAttemptManifest baseline,
      GraphAttemptManifest candidate) {
    return baseline.schemaVersion().equals(candidate.schemaVersion())
        && baseline.graphProtocolVersion().equals(
            candidate.graphProtocolVersion())
        && baseline.principalId().equals(candidate.principalId())
        && baseline.packRawSha256().equals(candidate.packRawSha256())
        && baseline.environmentRawSha256().equals(
            candidate.environmentRawSha256())
        && baseline.captureId().equals(candidate.captureId())
        && baseline.captureRequestHash().equals(
            candidate.captureRequestHash())
        && baseline.pricingProfileFingerprint().equals(
            candidate.pricingProfileFingerprint())
        && baseline.promptSurfaceFingerprint().equals(
            candidate.promptSurfaceFingerprint())
        && baseline.conductorSurfaceFingerprint().equals(
            candidate.conductorSurfaceFingerprint())
        && baseline.reservationUsd().compareTo(
            candidate.reservationUsd()) == 0
        && baseline.parentActor().equals(candidate.parentActor())
        && baseline.childActor().equals(candidate.childActor())
        && baseline.experiment().arm().equals(
            candidate.experiment().arm())
        && baseline.integrityProfile().equals(
            candidate.integrityProfile());
  }

  private static boolean sameSelectionSurface(
      GraphRunSelection baseline, GraphRunSelection candidate) {
    return baseline.role() == candidate.role()
        && baseline.executionProfileId().equals(
            candidate.executionProfileId())
        && baseline.workerRegistryVersion().equals(
            candidate.workerRegistryVersion())
        && baseline.workerProfileId().equals(
            candidate.workerProfileId());
  }

  public sealed interface Reduction {

    record Complete(HarnessEvaluationReport report)
        implements Reduction {
      public Complete {
        report = Objects.requireNonNull(report, "report");
      }
    }

    record Unavailable(String reasonCode, int repetition)
        implements Reduction {
      public Unavailable {
        ContractText.require(
            reasonCode,
            "reasonCode",
            ContractText.MAX_NAME_LENGTH);
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,199}")
            || repetition < 0
            || repetition > 3) {
          throw new IllegalArgumentException(
              "unavailable Report reason is outside the frozen domain");
        }
      }
    }
  }
}
