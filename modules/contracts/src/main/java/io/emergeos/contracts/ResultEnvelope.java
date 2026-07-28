package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ResultEnvelope(
    String schemaVersion,
    String taskId,
    RunStatus status,
    List<String> artifactRefs,
    List<String> evidenceRefs,
    List<Claim> claims,
    List<String> uncertainty,
    List<String> receiptRefs,
    String resolvedModel,
    String agentVersion,
    String verifierVersion,
    BigDecimal costUsd,
    long latencyMs,
    String traceRef,
    String failureReason) {

  public ResultEnvelope {
    requireText(schemaVersion, "schemaVersion");
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("ResultEnvelope supports schemaVersion 1.0");
    }
    requireText(taskId, "taskId");
    Objects.requireNonNull(status, "status");
    artifactRefs = copy(artifactRefs, "artifactRefs");
    evidenceRefs = copy(evidenceRefs, "evidenceRefs");
    claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    uncertainty = copy(uncertainty, "uncertainty");
    receiptRefs = copy(receiptRefs, "receiptRefs");
    requireText(agentVersion, "agentVersion");
    requireText(verifierVersion, "verifierVersion");
    if (Objects.requireNonNull(costUsd, "costUsd").signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must not be negative");
    }
  }

  private static <T> List<T> copy(List<T> value, String name) {
    return List.copyOf(Objects.requireNonNull(value, name));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
