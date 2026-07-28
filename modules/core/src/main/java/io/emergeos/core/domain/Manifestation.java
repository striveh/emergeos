package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public final class Manifestation {

  private final String id;
  private final String principalId;
  private final String seedEvidenceId;
  private final String workingSelfSnapshotId;
  private final String artifactId;
  private final int initialArtifactVersion;
  private final Instant createdAt;

  private int currentArtifactVersion;
  private String currentArtifactHash;
  private ActionPlan actionPlan;
  private ApprovalDecision approvalDecision;
  private ManifestationStatus status;
  private Receipt receipt;
  private ReflectionCandidate reflectionCandidate;
  private String reflectionStatus;
  private String reflectionFailure;
  private Instant updatedAt;

  private Manifestation(
      String id,
      String principalId,
      String seedEvidenceId,
      String workingSelfSnapshotId,
      ArtifactVersion artifact,
      ActionPlan actionPlan,
      Instant createdAt) {
    this.id = requireText(id, "id");
    this.principalId = requireText(principalId, "principalId");
    this.seedEvidenceId = requireText(seedEvidenceId, "seedEvidenceId");
    this.workingSelfSnapshotId = requireText(workingSelfSnapshotId, "workingSelfSnapshotId");
    this.artifactId = artifact.artifactId();
    this.initialArtifactVersion = artifact.version();
    this.currentArtifactVersion = artifact.version();
    this.currentArtifactHash = artifact.contentHash();
    this.actionPlan = Objects.requireNonNull(actionPlan, "actionPlan");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = createdAt;
    this.status = ManifestationStatus.AWAITING_APPROVAL;
    this.reflectionStatus = "NOT_STARTED";
    assertPlanMatchesArtifact(artifact, actionPlan);
    assertPlanOwnedByPrincipal(actionPlan, principalId);
  }

  public static Manifestation awaitingApproval(
      String id,
      String principalId,
      String seedEvidenceId,
      WorkingSelf workingSelf,
      ArtifactVersion artifact,
      ActionPlan actionPlan,
      Instant createdAt) {
    if (!principalId.equals(workingSelf.principalId())) {
      throw new IllegalArgumentException("Working Self belongs to a different principal");
    }
    if (!workingSelf.evidenceRefs().contains(seedEvidenceId)) {
      throw new IllegalArgumentException("Working Self must project the seed evidence");
    }
    if (!artifact.evidenceRefs().contains(seedEvidenceId)) {
      throw new IllegalArgumentException("artifact must reference seed evidence");
    }
    if (!artifact.workingSelfSnapshotId().equals(workingSelf.snapshotId())) {
      throw new IllegalArgumentException("artifact must reference the Working Self snapshot");
    }
    return new Manifestation(
        id,
        principalId,
        seedEvidenceId,
        workingSelf.snapshotId(),
        artifact,
        actionPlan,
        createdAt);
  }

  public void revise(ArtifactVersion revisedArtifact, ActionPlan revisedPlan, Instant now) {
    requireStatus(ManifestationStatus.AWAITING_APPROVAL);
    if (!artifactId.equals(revisedArtifact.artifactId())
        || revisedArtifact.version() != currentArtifactVersion + 1
        || revisedArtifact.baseVersion() == null
        || revisedArtifact.baseVersion() != currentArtifactVersion) {
      throw new IllegalStateException("revision must advance the current artifact by one version");
    }
    if (!workingSelfSnapshotId.equals(revisedArtifact.workingSelfSnapshotId())) {
      throw new IllegalStateException("revision cannot silently replace the Working Self");
    }
    assertPlanMatchesArtifact(revisedArtifact, revisedPlan);
    assertPlanOwnedByPrincipal(revisedPlan, principalId);
    currentArtifactVersion = revisedArtifact.version();
    currentArtifactHash = revisedArtifact.contentHash();
    actionPlan = revisedPlan;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  public void beginExecution(ApprovalDecision approval, Instant now) {
    requireStatus(ManifestationStatus.AWAITING_APPROVAL);
    Objects.requireNonNull(approval, "approval");
    if (!approval.approved()) {
      throw new IllegalStateException("action was not approved");
    }
    if (!principalId.equals(approval.actor())) {
      throw new IllegalStateException("only the owning principal can approve this action");
    }
    if (!actionPlan.planId().equals(approval.planId())
        || !actionPlan.planHash().equals(approval.planHash())
        || !currentArtifactHash.equals(approval.artifactHash())) {
      throw new IllegalStateException("approval is bound to a stale artifact hash");
    }
    if (!actionPlan.expiresAt().isAfter(now)) {
      throw new IllegalStateException("action plan has expired");
    }
    approvalDecision = approval;
    status = ManifestationStatus.EXECUTING;
    updatedAt = now;
  }

  public void completeWithReceipt(Receipt completedReceipt, Instant now) {
    requireStatus(ManifestationStatus.EXECUTING);
    Objects.requireNonNull(completedReceipt, "completedReceipt");
    if (!completedReceipt.succeeded()
        || !actionPlan.planId().equals(completedReceipt.actionPlanId())
        || !actionPlan.idempotencyKey().equals(completedReceipt.idempotencyKey())
        || !currentArtifactHash.equals(completedReceipt.artifactHash())) {
      throw new IllegalStateException("receipt does not prove the current action completed");
    }
    receipt = completedReceipt;
    status = ManifestationStatus.COMPLETED_WITH_RECEIPT;
    reflectionStatus = "PENDING";
    updatedAt = Objects.requireNonNull(now, "now");
  }

  public void attachReflection(ReflectionCandidate candidate, Instant now) {
    requireStatus(ManifestationStatus.COMPLETED_WITH_RECEIPT);
    Objects.requireNonNull(candidate, "candidate");
    String initialArtifactRef = artifactId + ":v" + initialArtifactVersion;
    String currentArtifactRef = artifactId + ":v" + currentArtifactVersion;
    if (!principalId.equals(candidate.principalId())
        || !id.equals(candidate.proposedByRun())
        || !candidate.evidenceRefs().contains(initialArtifactRef)
        || !candidate.evidenceRefs().contains(currentArtifactRef)
        || !candidate.evidenceRefs().contains(receipt.receiptId())) {
      throw new IllegalArgumentException(
          "reflection must match the principal, run, artifact lineage and receipt");
    }
    reflectionCandidate = candidate;
    reflectionStatus = "NO_CHANGE".equals(candidate.status()) ? "NO_CHANGE" : "PROPOSED";
    reflectionFailure = null;
    updatedAt = Objects.requireNonNull(now, "now");
  }

  public void markReflectionFailed(String reason, Instant now) {
    requireStatus(ManifestationStatus.COMPLETED_WITH_RECEIPT);
    reflectionStatus = "FAILED";
    reflectionFailure = requireText(reason, "reason");
    updatedAt = Objects.requireNonNull(now, "now");
  }

  public void assertApprovalReplay(String approvedBy, String artifactHash) {
    if (!principalId.equals(approvedBy) || !currentArtifactHash.equals(artifactHash)) {
      throw new IllegalStateException("approval replay does not match the completed action");
    }
  }

  private static void assertPlanMatchesArtifact(ArtifactVersion artifact, ActionPlan plan) {
    if (!plan.matches(artifact)) {
      throw new IllegalArgumentException("action plan must bind the current artifact");
    }
  }

  private static void assertPlanOwnedByPrincipal(ActionPlan plan, String principalId) {
    if (!plan.principalId().equals(principalId)) {
      throw new IllegalArgumentException("action plan belongs to a different principal");
    }
  }

  private void requireStatus(ManifestationStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("expected " + expected + " but was " + status);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  public String id() {
    return id;
  }

  public String principalId() {
    return principalId;
  }

  public String seedEvidenceId() {
    return seedEvidenceId;
  }

  public String workingSelfSnapshotId() {
    return workingSelfSnapshotId;
  }

  public String artifactId() {
    return artifactId;
  }

  public int initialArtifactVersion() {
    return initialArtifactVersion;
  }

  public int currentArtifactVersion() {
    return currentArtifactVersion;
  }

  public String currentArtifactHash() {
    return currentArtifactHash;
  }

  public ActionPlan actionPlan() {
    return actionPlan;
  }

  public ApprovalDecision approvalDecision() {
    return approvalDecision;
  }

  public ManifestationStatus status() {
    return status;
  }

  public Receipt receipt() {
    return receipt;
  }

  public ReflectionCandidate reflectionCandidate() {
    return reflectionCandidate;
  }

  public String reflectionStatus() {
    return reflectionStatus;
  }

  public String reflectionFailure() {
    return reflectionFailure;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
