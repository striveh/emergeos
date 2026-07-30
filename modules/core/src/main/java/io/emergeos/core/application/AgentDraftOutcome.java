package io.emergeos.core.application;

import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.ArtifactLineage;
import java.util.List;
import java.util.Objects;

public record AgentDraftOutcome(
    ResultEnvelope result,
    ArtifactLineage artifact,
    List<AgentTraceEvent> trace) {

  public AgentDraftOutcome {
    Objects.requireNonNull(result, "result");
    trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
  }
}
