package io.emergeos.core.domain;

import java.util.List;
import java.util.Objects;

public record AgentDraftProposal(String content, List<String> evidenceRefs) {

  public AgentDraftProposal {
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
  }
}
