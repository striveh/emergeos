package io.emergeos.core.domain;

import io.emergeos.contracts.RunStatus;
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
    long latencyMs,
    String failureReason) {

  public AgentRunOutcome {
    Objects.requireNonNull(status, "status");
    obtainedEvidenceRefs =
        List.copyOf(Objects.requireNonNull(obtainedEvidenceRefs, "obtainedEvidenceRefs"));
    trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
    if (resolvedModel == null || resolvedModel.isBlank()) {
      throw new IllegalArgumentException("resolvedModel must not be blank");
    }
    if (Objects.requireNonNull(costUsd, "costUsd").signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must not be negative");
    }
  }
}
