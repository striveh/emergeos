package io.emergeos.core.application;

public record PlanLocalApprovalCommand(
    String artifactId,
    int approvedArtifactVersion,
    String approvedArtifactHash,
    String approvalNonce) {

  public PlanLocalApprovalCommand {
    CreateArtifactCommand.requireIdentifier(artifactId, "artifactId");
    if (approvedArtifactVersion < 1) {
      throw new IllegalArgumentException("approvedArtifactVersion must be positive");
    }
    requireHash(approvedArtifactHash, "approvedArtifactHash");
    CreateArtifactCommand.requireIdentifier(approvalNonce, "approvalNonce");
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }
}
