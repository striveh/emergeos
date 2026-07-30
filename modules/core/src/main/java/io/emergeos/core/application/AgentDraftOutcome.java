package io.emergeos.core.application;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import java.util.List;
import java.util.Objects;

public record AgentDraftOutcome(
    AgentRun run,
    ArtifactLineage artifact) {

  public AgentDraftOutcome {
    Objects.requireNonNull(run, "run");
  }

  public ResultEnvelope result() {
    return run.result();
  }

  public List<AgentTraceEntry> trace() {
    return run.trace().events();
  }
}
