package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public sealed interface ProviderResult {

  record Succeeded(
      String externalId,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt)
      implements ProviderResult {

    public Succeeded {
      requireText(externalId, "externalId");
      requireText(providerRequestId, "providerRequestId");
      requireText(rawResponseRef, "rawResponseRef");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record Failed(
      String reasonCode,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt)
      implements ProviderResult {

    public Failed {
      requireText(reasonCode, "reasonCode");
      requireText(providerRequestId, "providerRequestId");
      requireText(rawResponseRef, "rawResponseRef");
      Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  record Unknown() implements ProviderResult {}

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 1000 || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
