package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ResultEnvelope(
    String schemaVersion,
    String runId,
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
    long tokenCount,
    long latencyMs,
    String traceRef,
    String failureReason) {

  public ResultEnvelope {
    ContractText.require(schemaVersion, "schemaVersion", ContractText.MAX_NAME_LENGTH);
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("ResultEnvelope supports schemaVersion 1.0");
    }
    requireId(runId, "runId");
    requireId(taskId, "taskId");
    Objects.requireNonNull(status, "status");
    artifactRefs = ContractText.copyStrings(artifactRefs, "artifactRefs");
    evidenceRefs = ContractText.copyStrings(evidenceRefs, "evidenceRefs");
    claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    uncertainty = ContractText.copyStrings(uncertainty, "uncertainty");
    receiptRefs = ContractText.copyStrings(receiptRefs, "receiptRefs");
    ContractText.require(agentVersion, "agentVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(verifierVersion, "verifierVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(
        resolvedModel, "resolvedModel", ContractText.MAX_MODEL_LENGTH);
    ContractValueDomains.requireUsd(costUsd, "costUsd");
    ContractValueDomains.requireSafeCount(tokenCount, "tokenCount");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
    ContractText.require(traceRef, "traceRef");
    if (!traceRef.equals("/api/v1/agent-runs/" + runId + "/trace")) {
      throw new IllegalArgumentException("traceRef must resolve to the same AgentRun");
    }
    if (status == RunStatus.SUCCEEDED) {
      if (failureReason != null || resolvedModel == null) {
        throw new IllegalArgumentException(
            "A successful Result requires resolvedModel and cannot have failureReason");
      }
    } else if (failureReason == null
        || !failureReason.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw new IllegalArgumentException(
          "A non-success Result requires a stable failureReason code");
    }
  }

  private static void requireId(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " has an invalid identifier");
    }
  }
}
