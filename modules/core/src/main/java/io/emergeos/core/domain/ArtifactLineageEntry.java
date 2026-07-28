package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record ArtifactLineageEntry(
    int version,
    String content,
    String contentHash,
    Integer baseVersion,
    String baseHash,
    Instant createdAt) {

  public ArtifactLineageEntry {
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    requireContent(content);
    if (!ContentHashes.sha256(content).equals(contentHash)) {
      throw new IllegalArgumentException("contentHash does not match content");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    if (version == 1) {
      if (baseVersion != null || baseHash != null) {
        throw new IllegalArgumentException("version one must not have a base");
      }
    } else {
      if (baseVersion == null || baseVersion != version - 1) {
        throw new IllegalArgumentException("baseVersion must be the immediately preceding version");
      }
      requireHash(baseHash, "baseHash");
    }
  }

  public static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }

  public static void requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    if (content.length() > 65_536) {
      throw new IllegalArgumentException("content must be at most 65536 characters");
    }
    if (content.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("content must not contain NUL");
    }
  }
}
