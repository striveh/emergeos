package io.emergeos.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ArtifactVersion(
    String artifactId,
    int version,
    String content,
    String contentHash,
    List<String> evidenceRefs,
    String workingSelfSnapshotId,
    String generatedBy,
    Integer baseVersion,
    Instant createdAt) {

  public ArtifactVersion {
    requireText(artifactId, "artifactId");
    requireText(content, "content");
    requireText(contentHash, "contentHash");
    requireText(workingSelfSnapshotId, "workingSelfSnapshotId");
    requireText(generatedBy, "generatedBy");
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    if (evidenceRefs.isEmpty()) {
      throw new IllegalArgumentException("evidenceRefs must not be empty");
    }
    evidenceRefs.forEach(evidenceRef -> requireText(evidenceRef, "evidenceRef"));
    Objects.requireNonNull(createdAt, "createdAt");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    if (!contentHash.equals(ContentHashes.sha256(content))) {
      throw new IllegalArgumentException("contentHash does not match content");
    }
    if (baseVersion != null && (baseVersion < 1 || baseVersion >= version)) {
      throw new IllegalArgumentException("baseVersion must refer to an earlier version");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
