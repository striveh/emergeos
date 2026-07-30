package io.emergeos.core.application;

public record AgentDraftCommand(String principalId, String captureId, String intent) {

  public AgentDraftCommand {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    CreateArtifactCommand.requireIdentifier(captureId, "captureId");
    if (intent == null || intent.isBlank()) {
      throw new IllegalArgumentException("intent must not be blank");
    }
    if (intent.length() > 4_000) {
      throw new IllegalArgumentException("intent must be at most 4000 characters");
    }
    if (intent.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("intent must not contain NUL");
    }
  }
}
