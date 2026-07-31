package io.emergeos.core.domain;

import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
import java.math.BigDecimal;
import java.util.Objects;

/** Verified terminal child truth returned by a durable Worker runtime. */
public record AgentWorkerExecution(
    String workerName,
    String childRunRef,
    String childBundleHash,
    RunStatus status,
    WorkerResultEnvelope workerResult,
    BigDecimal costUsd,
    long tokenCount,
    String failureReason) {

  public AgentWorkerExecution {
    if (workerName == null
        || !workerName.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("workerName is invalid");
    }
    if (childRunRef == null
        || !childRunRef.matches(
            "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException("childRunRef is invalid");
    }
    if (childBundleHash == null
        || !childBundleHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("childBundleHash is invalid");
    }
    Objects.requireNonNull(status, "status");
    ContractValueDomains.requireUsd(costUsd, "child costUsd");
    ContractValueDomains.requireSafeCount(tokenCount, "child tokenCount");
    if (status == RunStatus.SUCCEEDED) {
      Objects.requireNonNull(workerResult, "workerResult");
      if (failureReason != null
          || !workerResult
              .workerResultRef()
              .equals(
                  "worker-result://"
                      + childRunRef.substring("agent-run://".length()))) {
        throw new IllegalArgumentException(
            "successful child execution truth is inconsistent");
      }
    } else if (workerResult != null
        || failureReason == null
        || !failureReason.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw new IllegalArgumentException(
          "non-success child execution truth is inconsistent");
    }
  }

  public AgentHandoffObservation observation() {
    if (workerResult == null) {
      return new AgentHandoffObservation(
          childRunRef,
          childBundleHash,
          null,
          null,
          null,
          null,
          java.util.List.of(),
          status,
          costUsd,
          tokenCount);
    }
    return new AgentHandoffObservation(
        childRunRef,
        childBundleHash,
        workerResult.workerResultRef(),
        workerResult.integrityHash(),
        "proposal://sha256:" + workerResult.contentHash(),
        workerResult.contentHash(),
        workerResult.evidenceRefs(),
        status,
        costUsd,
        tokenCount);
  }
}
