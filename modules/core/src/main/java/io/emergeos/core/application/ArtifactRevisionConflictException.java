package io.emergeos.core.application;

public final class ArtifactRevisionConflictException extends RuntimeException {

  private final int currentVersion;

  public ArtifactRevisionConflictException(int currentVersion) {
    super("Artifact changed since the expected base; reload the latest Artifact before revising");
    if (currentVersion < 1) {
      throw new IllegalArgumentException("currentVersion must be positive");
    }
    this.currentVersion = currentVersion;
  }

  public int currentVersion() {
    return currentVersion;
  }
}
