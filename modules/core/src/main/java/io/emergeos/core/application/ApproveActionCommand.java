package io.emergeos.core.application;

public record ApproveActionCommand(String approvedBy, String artifactHash) {

  public ApproveActionCommand {
    requireText(approvedBy, "approvedBy");
    requireText(artifactHash, "artifactHash");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

