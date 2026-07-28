package io.emergeos.core.domain;

import java.util.Objects;

public record ProviderActionRequest(
    ProviderOperation operation,
    ActionPlan plan,
    String connector,
    String audience,
    String accountRef) {

  public ProviderActionRequest {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(plan, "plan");
    requireText(connector, "connector");
    requireText(audience, "audience");
    requireText(accountRef, "accountRef");
  }

  public enum ProviderOperation {
    EXECUTE,
    RECONCILE_ONLY
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
