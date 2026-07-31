package io.emergeos.core.domain;

import io.emergeos.contracts.ContractText;
import java.util.Objects;

/** Three-way result of a fresh read-only graph verification. */
public sealed interface GraphAttemptVerification
    permits GraphAttemptVerification.Missing,
        GraphAttemptVerification.Invalid,
        GraphAttemptVerification.Valid {

  record Missing() implements GraphAttemptVerification {}

  record Invalid(String reasonCode)
      implements GraphAttemptVerification {

    public Invalid {
      reasonCode =
          ContractText.require(
              reasonCode,
              "reasonCode",
              ContractText.MAX_NAME_LENGTH);
      if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,199}")) {
        throw new IllegalArgumentException(
            "reasonCode must be a stable uppercase code");
      }
    }
  }

  record Valid(GraphAttemptSnapshot snapshot)
      implements GraphAttemptVerification {

    public Valid {
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
  }
}
