package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.util.Objects;

public record CaptureCommand(
    String principalId,
    String clientNonce,
    String content,
    CaptureSourceType sourceType,
    String sourceRef,
    DataClass dataClass) {

  public CaptureCommand(
      String principalId,
      String clientNonce,
      String content,
      String sourceType,
      String sourceRef,
      DataClass dataClass) {
    this(
        principalId,
        clientNonce,
        content,
        CaptureSourceType.parse(sourceType),
        sourceRef,
        dataClass);
  }

  public CaptureCommand {
    requireText(principalId, "principalId", 200);
    Capture.requireNonce(clientNonce);
    requireText(content, "content", 65_536);
    Objects.requireNonNull(sourceType, "sourceType");
    requireText(sourceRef, "sourceRef", 2_048);
    if (dataClass == null) {
      throw new IllegalArgumentException("dataClass must not be null");
    }
  }

  public String requestHash() {
    return CaptureRequestHashes.sha256(content, sourceType, sourceRef, dataClass);
  }

  private static void requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
  }
}
