package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record Receipt(
    String receiptId,
    String actionPlanId,
    String status,
    String externalId,
    String idempotencyKey,
    String artifactHash,
    String providerRequestId,
    String rawResponseRef,
    Instant occurredAt) {

  public Receipt {
    requireText(receiptId, "receiptId");
    requireText(actionPlanId, "actionPlanId");
    requireText(status, "status");
    requireText(externalId, "externalId");
    requireText(idempotencyKey, "idempotencyKey");
    requireText(artifactHash, "artifactHash");
    requireText(providerRequestId, "providerRequestId");
    requireText(rawResponseRef, "rawResponseRef");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) {
      throw new IllegalArgumentException("unsupported receipt status: " + status);
    }
    if (!artifactHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("artifactHash must be a lowercase SHA-256 hex digest");
    }
  }

  public boolean succeeded() {
    return "SUCCEEDED".equals(status);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
