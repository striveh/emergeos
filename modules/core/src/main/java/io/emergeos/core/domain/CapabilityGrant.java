package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record CapabilityGrant(
    String capabilityId,
    String subject,
    String audience,
    String actionPlanId,
    String action,
    String targetRef,
    String accountRef,
    String artifactHash,
    String idempotencyKey,
    String policyVersion,
    Instant expiresAt) {

  public CapabilityGrant {
    requireText(capabilityId, "capabilityId");
    requireText(subject, "subject");
    requireText(audience, "audience");
    requireText(actionPlanId, "actionPlanId");
    requireText(action, "action");
    requireText(targetRef, "targetRef");
    requireText(accountRef, "accountRef");
    requireText(artifactHash, "artifactHash");
    requireText(idempotencyKey, "idempotencyKey");
    requireText(policyVersion, "policyVersion");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public void assertAllows(
      ActionPlan plan,
      ArtifactVersion artifact,
      String expectedAudience,
      String expectedAccountRef,
      Instant now) {
    if (!subject.equals(plan.principalId())
        || !audience.equals(expectedAudience)
        || !actionPlanId.equals(plan.planId())
        || !action.equals(plan.actionType())
        || !targetRef.equals(plan.targetRef())
        || !accountRef.equals(expectedAccountRef)
        || !artifactHash.equals(artifact.contentHash())
        || !idempotencyKey.equals(plan.idempotencyKey())
        || !policyVersion.equals(plan.policyVersion())
        || !expiresAt.isAfter(now)) {
      throw new IllegalStateException("capability does not authorize the current action");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
