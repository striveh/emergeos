package io.emergeos.core.application;

import io.emergeos.core.domain.ArtifactLineageEntry;

public record CreateArtifactCommand(String principalId, String sourceCaptureId, String content) {

  public CreateArtifactCommand {
    requireIdentifier(principalId, "principalId");
    requireIdentifier(sourceCaptureId, "sourceCaptureId");
    ArtifactLineageEntry.requireContent(content);
  }

  static void requireIdentifier(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > 200) {
      throw new IllegalArgumentException(name + " must be at most 200 characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
  }
}
