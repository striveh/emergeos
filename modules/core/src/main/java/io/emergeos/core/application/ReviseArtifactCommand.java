package io.emergeos.core.application;

public record ReviseArtifactCommand(String principalId, String content) {

  public ReviseArtifactCommand {
    requireText(principalId, "principalId");
    requireText(content, "content");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

