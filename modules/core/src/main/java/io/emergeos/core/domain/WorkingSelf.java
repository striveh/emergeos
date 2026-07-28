package io.emergeos.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record WorkingSelf(
    String snapshotId,
    String principalId,
    String selfModelVersion,
    List<String> evidenceRefs,
    List<String> confirmedStyleRules,
    List<String> constraints,
    String snapshotHash,
    Instant createdAt) {

  public WorkingSelf {
    requireText(snapshotId, "snapshotId");
    requireText(principalId, "principalId");
    requireText(selfModelVersion, "selfModelVersion");
    requireText(snapshotHash, "snapshotHash");
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    if (evidenceRefs.isEmpty()) {
      throw new IllegalArgumentException("evidenceRefs must not be empty");
    }
    evidenceRefs.forEach(evidenceRef -> requireText(evidenceRef, "evidenceRef"));
    confirmedStyleRules =
        List.copyOf(Objects.requireNonNull(confirmedStyleRules, "confirmedStyleRules"));
    constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
