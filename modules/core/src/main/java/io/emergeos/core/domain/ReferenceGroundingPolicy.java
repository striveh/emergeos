package io.emergeos.core.domain;

import java.util.List;
import java.util.Objects;

/** Shared post-parse reference-grounding policy for product and Harness truth. */
public final class ReferenceGroundingPolicy {

  private ReferenceGroundingPolicy() {}

  public static Failure failure(
      List<String> claimedEvidenceRefs,
      List<String> obtainedEvidenceRefs,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable) {
    Objects.requireNonNull(
        claimedEvidenceRefs, "claimedEvidenceRefs");
    Objects.requireNonNull(
        obtainedEvidenceRefs, "obtainedEvidenceRefs");
    Objects.requireNonNull(
        requiredEvidenceRef, "requiredEvidenceRef");
    if (obtainedEvidenceRefs.isEmpty()) {
      return Failure.MISSING_REQUIRED_EVIDENCE;
    }
    if (!obtainedEvidenceRefs.equals(
        List.of(requiredEvidenceRef))) {
      return Failure.UNSAFE_EVIDENCE_BINDING;
    }
    if (!claimedEvidenceRefs.equals(
        List.of(requiredEvidenceRef))) {
      return Failure.INVALID_EVIDENCE_CLAIM;
    }
    if (!requiredEvidenceAvailable) {
      return Failure.REQUIRED_EVIDENCE_NOT_FOUND;
    }
    return null;
  }

  public enum Failure {
    MISSING_REQUIRED_EVIDENCE,
    UNSAFE_EVIDENCE_BINDING,
    INVALID_EVIDENCE_CLAIM,
    REQUIRED_EVIDENCE_NOT_FOUND;

    public String code() {
      return name();
    }
  }
}
