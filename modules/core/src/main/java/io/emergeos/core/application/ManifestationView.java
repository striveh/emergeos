package io.emergeos.core.application;

public record ManifestationView(
    String manifestationId,
    String principalId,
    String status,
    String evidenceId,
    String workingSelfSnapshotId,
    ArtifactView artifact,
    ActionView action,
    ReceiptView receipt,
    String reflectionStatus,
    String reflectionFailure,
    ReflectionView reflection) {

  public record ArtifactView(
      String artifactId, int version, String content, String contentHash, String generatedBy) {}

  public record ActionView(
      String planId,
      String actionType,
      String targetRef,
      String risk,
      String policyVersion,
      String idempotencyKey,
      String expiresAt,
      String approvalId,
      boolean approvalRequired) {}

  public record ReceiptView(
      String receiptId,
      String status,
      String externalId,
      String artifactHash,
      String occurredAt) {}

  public record ReflectionView(
      String candidateId,
      String status,
      String claimOrRule,
      double confidence,
      boolean appliedToSelfModel) {}
}
