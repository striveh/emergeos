package io.emergeos.core.domain;

import io.emergeos.contracts.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

public record ActionPlan(
    String planId,
    String principalId,
    String actionType,
    String targetRef,
    String artifactId,
    int artifactVersion,
    String artifactHash,
    RiskLevel risk,
    String policyVersion,
    String idempotencyKey,
    Instant expiresAt) {

  public ActionPlan {
    requireText(planId, "planId");
    requireText(principalId, "principalId");
    requireText(actionType, "actionType");
    requireText(targetRef, "targetRef");
    requireText(artifactId, "artifactId");
    requireText(artifactHash, "artifactHash");
    requireText(policyVersion, "policyVersion");
    requireText(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
  }

  public boolean matches(ArtifactVersion artifact) {
    return artifactId.equals(artifact.artifactId())
        && artifactVersion == artifact.version()
        && artifactHash.equals(artifact.contentHash());
  }

  public String planHash() {
    return ContentHashes.sha256(
        String.join(
            "\n",
            "emergeos.action-plan.v1",
            encoded("planId", planId),
            encoded("principalId", principalId),
            encoded("actionType", actionType),
            encoded("targetRef", targetRef),
            encoded("artifactId", artifactId),
            encoded("artifactVersion", Integer.toString(artifactVersion)),
            encoded("artifactHash", artifactHash),
            encoded("risk", risk.name()),
            encoded("policyVersion", policyVersion),
            encoded("idempotencyKey", idempotencyKey),
            encoded("expiresAt", expiresAt.toString())));
  }

  private static String encoded(String name, String value) {
    String encodedValue =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    return name + "=" + encodedValue;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
