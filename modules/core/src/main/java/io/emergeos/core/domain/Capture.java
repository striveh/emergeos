package io.emergeos.core.domain;

import io.emergeos.contracts.DataClass;
import java.time.Instant;
import java.util.Objects;

public record Capture(
    String captureId,
    String principalId,
    String clientNonce,
    String requestHash,
    String content,
    CaptureSourceType sourceType,
    String sourceRef,
    DataClass dataClass,
    Instant capturedAt) {

  public Capture {
    requireText(captureId, "captureId", 200);
    requireText(principalId, "principalId", 200);
    requireNonce(clientNonce);
    requireText(content, "content", 65_536);
    Objects.requireNonNull(sourceType, "sourceType");
    requireText(sourceRef, "sourceRef", 2_048);
    Objects.requireNonNull(dataClass, "dataClass");
    Objects.requireNonNull(capturedAt, "capturedAt");
    if (dataClass == DataClass.SENSITIVE || dataClass == DataClass.SECRET) {
      throw new IllegalArgumentException(
          "restart-safe Capture does not accept SENSITIVE or SECRET content");
    }
    String expectedHash = CaptureRequestHashes.sha256(content, sourceType, sourceRef, dataClass);
    if (!expectedHash.equals(requestHash)) {
      throw new IllegalArgumentException("requestHash does not match the Capture payload");
    }
  }

  public static void requireNonce(String clientNonce) {
    if (clientNonce == null || !clientNonce.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException(
          "clientNonce must be 1-128 ASCII letters, digits, dot, underscore, colon or hyphen");
    }
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
