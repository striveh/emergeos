package io.emergeos.core.application;

import io.emergeos.core.domain.ArtifactLineageEntry;

public record ReviseArtifactLineageCommand(
    String principalId,
    String artifactId,
    String content,
    int expectedBaseVersion,
    String expectedBaseHash) {

  public ReviseArtifactLineageCommand {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    CreateArtifactCommand.requireIdentifier(artifactId, "artifactId");
    ArtifactLineageEntry.requireContent(content);
    if (expectedBaseVersion < 1 || expectedBaseVersion == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("expectedBaseVersion must be a positive revisable version");
    }
    ArtifactLineageEntry.requireHash(expectedBaseHash, "expectedBaseHash");
  }
}
