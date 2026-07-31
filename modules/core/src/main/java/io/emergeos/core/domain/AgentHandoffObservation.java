package io.emergeos.core.domain;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Safe, content-free observation of one durable child Run.
 *
 * <p>The proposal bytes stay in WorkerResultEnvelope; parent persistent Trace and Bundle carry only
 * typed refs and hashes.
 */
public record AgentHandoffObservation(
    String childRunRef,
    String childBundleHash,
    String workerResultRef,
    String workerResultIntegrityHash,
    String proposalRef,
    String proposalContentHash,
    List<String> evidenceRefs,
    RunStatus childStatus,
    BigDecimal childCostUsd,
    long childTokenCount) {

  public AgentHandoffObservation {
    if (childRunRef == null
        || !childRunRef.matches(
            "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException("childRunRef is invalid");
    }
    requireHash(childBundleHash, "childBundleHash");
    Objects.requireNonNull(childStatus, "childStatus");
    Objects.requireNonNull(childCostUsd, "childCostUsd");
    if (childCostUsd.signum() < 0 || childTokenCount < 0) {
      throw new IllegalArgumentException("child usage cannot be negative");
    }
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    if (childStatus == RunStatus.SUCCEEDED) {
      if (workerResultRef == null
          || !workerResultRef.matches(
              "worker-result://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
          || proposalRef == null
          || !proposalRef.matches("proposal://sha256:[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "a successful child observation requires typed output refs");
      }
      requireHash(workerResultIntegrityHash, "workerResultIntegrityHash");
      requireHash(proposalContentHash, "proposalContentHash");
      if (!proposalRef.equals("proposal://sha256:" + proposalContentHash)) {
        throw new IllegalArgumentException(
            "proposalRef and proposalContentHash disagree");
      }
    } else if (workerResultRef != null
        || workerResultIntegrityHash != null
        || proposalRef != null
        || proposalContentHash != null) {
      throw new IllegalArgumentException(
          "a non-success child cannot expose accepted output truth");
    }
  }

  public String childRunId() {
    return childRunRef.substring("agent-run://".length());
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
    }
  }
}
