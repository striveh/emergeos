package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record ApprovalDecision(
    String decisionId,
    String planId,
    String planHash,
    String artifactHash,
    String decision,
    String actor,
    Instant decidedAt) {

  public ApprovalDecision {
    requireText(decisionId, "decisionId");
    requireText(planId, "planId");
    requireText(planHash, "planHash");
    requireText(artifactHash, "artifactHash");
    requireText(decision, "decision");
    requireText(actor, "actor");
    Objects.requireNonNull(decidedAt, "decidedAt");
  }

  public boolean approved() {
    return "APPROVED".equals(decision);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

