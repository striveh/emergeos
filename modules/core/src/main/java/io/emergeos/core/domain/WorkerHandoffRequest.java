package io.emergeos.core.domain;

import io.emergeos.contracts.ContractText;
import java.util.List;

/** Bounded model intent for one server-authorized Worker delegation. */
public record WorkerHandoffRequest(
    String workerName,
    String intent,
    List<String> inputRefs) {

  public WorkerHandoffRequest {
    if (workerName == null
        || !workerName.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException(
          "workerName must be a bounded lowercase canonical name");
    }
    ContractText.require(intent, "worker intent");
    inputRefs = ContractText.copyStrings(inputRefs, "worker inputRefs");
  }
}
