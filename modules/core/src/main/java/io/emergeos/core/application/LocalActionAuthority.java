package io.emergeos.core.application;

import io.emergeos.contracts.RiskLevel;
import java.time.Duration;
import java.util.Objects;

public record LocalActionAuthority(
    String principalId,
    String connector,
    String audience,
    String accountRef,
    String actionType,
    String targetRef,
    RiskLevel risk,
    String policyVersion,
    Duration capabilityTtl,
    int maxProviderCalls) {

  public LocalActionAuthority {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    CreateArtifactCommand.requireIdentifier(connector, "connector");
    CreateArtifactCommand.requireIdentifier(audience, "audience");
    CreateArtifactCommand.requireIdentifier(accountRef, "accountRef");
    CreateArtifactCommand.requireIdentifier(actionType, "actionType");
    CreateArtifactCommand.requireIdentifier(targetRef, "targetRef");
    CreateArtifactCommand.requireIdentifier(policyVersion, "policyVersion");
    Objects.requireNonNull(risk, "risk");
    Objects.requireNonNull(capabilityTtl, "capabilityTtl");
    if (capabilityTtl.isZero() || capabilityTtl.isNegative()) {
      throw new IllegalArgumentException("capabilityTtl must be positive");
    }
    if (maxProviderCalls < 1) {
      throw new IllegalArgumentException("maxProviderCalls must be positive");
    }
  }
}
