package io.emergeos.core.domain;

/** Closed typed failure decisions that may be durably resumed before Candidate minting. */
public enum GraphAttributedFailureCode {
  MODEL_RESPONSE_MALFORMED,
  MODEL_USAGE_LIMIT_EXCEEDED;

  public static GraphAttributedFailureCode require(String value) {
    for (GraphAttributedFailureCode code : values()) {
      if (code.name().equals(value)) {
        return code;
      }
    }
    throw new IllegalArgumentException(
        "attributed failure code is not allowed");
  }
}
