package io.emergeos.adapters.inmemory;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.CapabilityGrant;
import io.emergeos.core.port.ActionPolicy;
import java.time.Duration;
import java.time.Instant;

public final class DeterministicActionPolicy implements ActionPolicy {

  private static final String POLICY_VERSION = "policy-local-v1";

  @Override
  public ActionPlan plan(
      String planId,
      String manifestationId,
      String principalId,
      ArtifactVersion artifact,
      Instant now) {
    var shortHash = artifact.contentHash().substring(0, 12);
    var idempotencyKey =
        "local-draft:%s:%s:v%d:%s"
            .formatted(principalId, manifestationId, artifact.version(), shortHash);
    return new ActionPlan(
        planId,
        principalId,
        "local.draft.create",
        "local-draftbox",
        artifact.artifactId(),
        artifact.version(),
        artifact.contentHash(),
        RiskLevel.REVERSIBLE,
        POLICY_VERSION,
        idempotencyKey,
        now.plus(Duration.ofHours(24)));
  }

  @Override
  public CapabilityGrant authorize(
      String capabilityId,
      ActionPlan plan,
      ArtifactVersion artifact,
      ApprovalDecision approval,
      Instant now) {
    if (!approval.approved()
        || !plan.principalId().equals(approval.actor())
        || !plan.planId().equals(approval.planId())
        || !plan.planHash().equals(approval.planHash())) {
      throw new IllegalStateException("approver is not the owning principal");
    }
    if (!plan.matches(artifact) || !artifact.contentHash().equals(approval.artifactHash())) {
      throw new IllegalStateException("approval is not bound to the current artifact");
    }
    if (!plan.expiresAt().isAfter(now)) {
      throw new IllegalStateException("action plan has expired");
    }
    return new CapabilityGrant(
        capabilityId,
        approval.actor(),
        LocalDraftActionExecutor.CAPABILITY_AUDIENCE,
        plan.planId(),
        plan.actionType(),
        plan.targetRef(),
        LocalDraftActionExecutor.accountRef(plan.principalId()),
        artifact.contentHash(),
        plan.idempotencyKey(),
        plan.policyVersion(),
        now.plus(Duration.ofMinutes(10)));
  }
}
