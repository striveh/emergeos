package io.emergeos.core.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Package-local canonical bytes shared by the V13 challenge and transcript. */
final class GraphProviderValidationCanonical {

  private GraphProviderValidationCanonical() {}

  static byte[] frame(String domain, String... fields) {
    if (domain == null
        || !domain.matches(
            "emergeos\\.[a-z0-9][a-z0-9.-]{0,126}\\.v[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "provider validation domain is invalid");
    }
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    framed.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    framed.write(0);
    for (String field : fields) {
      if (field == null) {
        throw new IllegalArgumentException(
            "provider validation canonical field is missing");
      }
      byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(
          ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
      framed.writeBytes(encoded);
    }
    return framed.toByteArray();
  }

  static String hash(String domain, String... fields) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(frame(domain, fields)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static long epochMicros(Instant value, String name) {
    if (value == null || value.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          name + " must have PostgreSQL microsecond precision");
    }
    return Math.addExact(
        Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
        value.getNano() / 1_000L);
  }
}
