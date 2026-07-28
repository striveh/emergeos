package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record ActionCapability(
    String capabilityId,
    String subject,
    String connector,
    String audience,
    String accountRef,
    String actionPlanId,
    String actionPlanHash,
    String artifactHash,
    String idempotencyKey,
    Instant expiresAt,
    int maxCalls) {

  public ActionCapability {
    requireText(capabilityId, "capabilityId");
    requireText(subject, "subject");
    requireText(connector, "connector");
    requireText(audience, "audience");
    requireText(accountRef, "accountRef");
    requireText(actionPlanId, "actionPlanId");
    requireHash(actionPlanHash, "actionPlanHash");
    requireHash(artifactHash, "artifactHash");
    requireText(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (maxCalls < 1) {
      throw new IllegalArgumentException("maxCalls must be positive");
    }
  }

  public void assertAllows(
      ActionPlan plan,
      String expectedConnector,
      String expectedAudience,
      String expectedAccountRef,
      Instant now) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(now, "now");
    if (!subject.equals(plan.principalId())
        || !connector.equals(expectedConnector)
        || !audience.equals(expectedAudience)
        || !accountRef.equals(expectedAccountRef)
        || !actionPlanId.equals(plan.planId())
        || !actionPlanHash.equals(plan.planHash())
        || !artifactHash.equals(plan.artifactHash())
        || !idempotencyKey.equals(plan.idempotencyKey())
        || !expiresAt.equals(plan.expiresAt())
        || !expiresAt.isAfter(now)) {
      throw new IllegalStateException("capability does not authorize the current action");
    }
  }

  private static void requireHash(String value, String name) {
    requireText(value, name);
    if (value.length() != 64 || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > 512) {
      throw new IllegalArgumentException(name + " must be at most 512 characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
  }
}
