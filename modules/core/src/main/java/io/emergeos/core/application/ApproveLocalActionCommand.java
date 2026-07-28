package io.emergeos.core.application;

public record ApproveLocalActionCommand(
    String artifactId, String approvedArtifactHash, String idempotencyKey) {

  public ApproveLocalActionCommand {
    CreateArtifactCommand.requireIdentifier(artifactId, "artifactId");
    requireHash(approvedArtifactHash, "approvedArtifactHash");
    CreateArtifactCommand.requireIdentifier(idempotencyKey, "idempotencyKey");
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }
}
