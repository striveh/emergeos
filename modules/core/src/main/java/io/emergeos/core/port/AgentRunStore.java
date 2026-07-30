package io.emergeos.core.port;

import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import java.util.Objects;
import java.util.Optional;

public interface AgentRunStore {

  AgentRun start(AgentRun running);

  CompletionResult complete(AgentRun terminal, ArtifactLineage proposedArtifact);

  Optional<AgentRun> findOwned(String principalId, String runId);

  record CompletionResult(AgentRun run, ArtifactLineage artifact) {

    public CompletionResult {
      Objects.requireNonNull(run, "run");
    }
  }
}
