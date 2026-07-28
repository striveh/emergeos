package io.emergeos.core.domain;

public enum CaptureSourceType {
  TEXT,
  LINK,
  VOICE_FILE;

  public static CaptureSourceType parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sourceType must not be blank");
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException unsupported) {
      throw new IllegalArgumentException("unsupported sourceType: " + value, unsupported);
    }
  }
}
