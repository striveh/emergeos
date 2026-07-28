package io.emergeos.core.domain;

import io.emergeos.contracts.DataClass;
import java.time.Instant;
import java.util.Objects;

public record EvidenceEvent(
    String id,
    String principalId,
    String kind,
    String content,
    String contentHash,
    String sourceType,
    String sourceRef,
    DataClass dataClass,
    Instant capturedAt) {

  public EvidenceEvent {
    requireText(id, "id");
    requireText(principalId, "principalId");
    requireText(kind, "kind");
    requireText(content, "content");
    requireText(contentHash, "contentHash");
    requireText(sourceType, "sourceType");
    requireText(sourceRef, "sourceRef");
    Objects.requireNonNull(dataClass, "dataClass");
    Objects.requireNonNull(capturedAt, "capturedAt");
    if (!contentHash.equals(ContentHashes.sha256(content))) {
      throw new IllegalArgumentException("contentHash does not match content");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

