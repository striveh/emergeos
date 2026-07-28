package io.emergeos.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReflectionCandidate(
    String candidateId,
    String principalId,
    String type,
    String claimOrRule,
    List<String> evidenceRefs,
    double confidence,
    String scope,
    String status,
    String proposedByRun,
    Instant createdAt) {

  public ReflectionCandidate {
    requireText(candidateId, "candidateId");
    requireText(principalId, "principalId");
    requireText(type, "type");
    requireText(claimOrRule, "claimOrRule");
    requireText(scope, "scope");
    requireText(status, "status");
    requireText(proposedByRun, "proposedByRun");
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    if (evidenceRefs.isEmpty()) {
      throw new IllegalArgumentException("evidenceRefs must not be empty");
    }
    evidenceRefs.forEach(evidenceRef -> requireText(evidenceRef, "evidenceRef"));
    Objects.requireNonNull(createdAt, "createdAt");
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
  }

  public boolean changesSelfModel() {
    return false;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
