package io.emergeos.contracts;

import java.util.Objects;

public record ResourceBinding(
    ResourceRole role,
    int ordinal,
    String ref,
    String contentHash) {

  public ResourceBinding {
    Objects.requireNonNull(role, "role");
    ContractText.require(ref, "ref");
    if (ordinal < 0 || ordinal >= ContractValueDomains.MAX_EXECUTION_LIMIT) {
      throw new IllegalArgumentException("ordinal must be between 0 and 127");
    }
    if (contentHash == null || !contentHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex digest");
    }
    if (role == ResourceRole.ARTIFACT
        && !ref.matches("artifact-version://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}/[1-9][0-9]*")) {
      throw new IllegalArgumentException("Artifact binding must name one immutable version");
    }
    if (role == ResourceRole.EVIDENCE
        && !ref.matches("capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException("Evidence binding must name one Capture");
    }
    if (role == ResourceRole.HANDOFF
        && (ordinal != 0
            || !ref.matches(
                "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}"))) {
      throw new IllegalArgumentException(
          "Handoff binding must name exactly one child AgentRun");
    }
    if (role == ResourceRole.WORKER_RESULT
        && (ordinal != 0
            || !ref.matches(
                "worker-result://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}"))) {
      throw new IllegalArgumentException(
          "Worker Result binding must name exactly one child result");
    }
  }

}
