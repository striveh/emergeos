package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

/** A local Draftbox entry that references, but never copies, an immutable Artifact version. */
public record LocalDraft(
    String principalId,
    String draftId,
    String attemptId,
    String artifactId,
    int artifactVersion,
    String artifactHash,
    Instant createdAt) {

  public static final String ACTIVE = "ACTIVE";

  public LocalDraft {
    requireText(principalId, "principalId");
    requireText(draftId, "draftId");
    requireText(attemptId, "attemptId");
    requireText(artifactId, "artifactId");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    if (artifactHash == null || !artifactHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("artifactHash must be a lowercase SHA-256 hash");
    }
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public String state() {
    return ACTIVE;
  }

  private static void requireText(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
