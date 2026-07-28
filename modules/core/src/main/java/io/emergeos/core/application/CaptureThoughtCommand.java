package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import java.util.Objects;

public record CaptureThoughtCommand(
    String principalId,
    String content,
    String sourceType,
    String sourceRef,
    DataClass dataClass) {

  public CaptureThoughtCommand {
    requireText(principalId, "principalId");
    requireText(content, "content");
    requireText(sourceType, "sourceType");
    requireText(sourceRef, "sourceRef");
    Objects.requireNonNull(dataClass, "dataClass");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

