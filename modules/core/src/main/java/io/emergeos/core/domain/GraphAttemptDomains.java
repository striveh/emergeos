package io.emergeos.core.domain;

import io.emergeos.contracts.ContractText;

final class GraphAttemptDomains {

  private GraphAttemptDomains() {}

  static String identifier(String value, String name) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException(
          name + " is outside the graph identifier domain");
    }
    return value;
  }

  static String runIdentifier(String value, String name) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(
          name + " is outside the graph Run identifier domain");
    }
    return value;
  }

  static String safeName(String value, String name) {
    return ContractText.require(
        value, name, ContractText.MAX_NAME_LENGTH);
  }

  static String hash(String value, String name) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          name + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  static String modelIdentifier(String value, String name) {
    ContractText.require(
        value, name, ContractText.MAX_MODEL_LENGTH);
    if (!ContractText.isSafeModelIdentifier(value)) {
      throw new IllegalArgumentException(
          name + " is outside the safe model domain");
    }
    return value;
  }
}
