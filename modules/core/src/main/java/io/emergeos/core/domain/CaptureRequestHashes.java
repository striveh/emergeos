package io.emergeos.core.domain;

import io.emergeos.contracts.DataClass;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public final class CaptureRequestHashes {

  private CaptureRequestHashes() {}

  public static String sha256(
      String content, CaptureSourceType sourceType, String sourceRef, DataClass dataClass) {
    Objects.requireNonNull(sourceType, "sourceType");
    Objects.requireNonNull(dataClass, "dataClass");
    return ContentHashes.sha256(
        String.join(
            "\n",
            "emergeos.capture-request.v1",
            encoded("content", content),
            encoded("sourceType", sourceType.name()),
            encoded("sourceRef", sourceRef),
            encoded("dataClass", dataClass.name())));
  }

  private static String encoded(String name, String value) {
    Objects.requireNonNull(value, name);
    String encodedValue =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    return name + "=" + encodedValue;
  }
}
