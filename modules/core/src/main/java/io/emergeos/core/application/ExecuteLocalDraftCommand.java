package io.emergeos.core.application;

public record ExecuteLocalDraftCommand(
    String attemptId, String scopeSchema, String scopeHash) {

  public ExecuteLocalDraftCommand {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    CreateArtifactCommand.requireIdentifier(scopeSchema, "scopeSchema");
    if (scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("scopeHash must be a lowercase SHA-256 hash");
    }
  }
}
