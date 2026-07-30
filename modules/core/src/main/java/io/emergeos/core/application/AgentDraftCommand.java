package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;

public record AgentDraftCommand(String principalId, String captureId, String intent) {

  public AgentDraftCommand {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    CreateArtifactCommand.requireIdentifier(captureId, "captureId");
    ContractText.require(intent, "intent");
  }
}
