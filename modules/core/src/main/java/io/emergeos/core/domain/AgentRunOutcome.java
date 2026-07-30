package io.emergeos.core.domain;

import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.ContractValueDomains;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record AgentRunOutcome(
    RunStatus status,
    AgentDraftProposal proposal,
    List<String> obtainedEvidenceRefs,
    List<AgentTraceEvent> trace,
    String resolvedModel,
    BigDecimal costUsd,
    long tokenCount,
    long latencyMs,
    String failureReason) {

  public AgentRunOutcome {
    Objects.requireNonNull(status, "status");
    obtainedEvidenceRefs =
        List.copyOf(Objects.requireNonNull(obtainedEvidenceRefs, "obtainedEvidenceRefs"));
    trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
    if (resolvedModel != null && resolvedModel.isBlank()) {
      throw new IllegalArgumentException("resolvedModel must be null or non-blank");
    }
    ContractValueDomains.requireUsd(costUsd, "costUsd");
    ContractValueDomains.requireSafeCount(tokenCount, "tokenCount");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);
  }
}
