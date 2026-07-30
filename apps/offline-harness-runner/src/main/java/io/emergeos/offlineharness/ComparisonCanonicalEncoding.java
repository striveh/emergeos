package io.emergeos.offlineharness;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Module-local encoder for offline comparison integrity preimages.
 *
 * <p>It deliberately mirrors the executable
 * {@code emergeos-length-prefixed-sha256-v1} profile without exposing the
 * contracts module's package-private encoder. This bounded slice supports
 * null, booleans, strings, JavaScript-safe integers, lists and string-keyed
 * objects. Decimal and arbitrary object encodings are intentionally absent.
 */
final class ComparisonCanonicalEncoding {

  private static final byte NULL = 0;
  private static final byte BOOLEAN = 1;
  private static final byte STRING = 2;
  private static final byte NUMBER = 3;
  private static final byte LIST = 4;
  private static final byte OBJECT = 5;
  private static final BigInteger MAX_SAFE_INTEGER =
      BigInteger.valueOf(9_007_199_254_740_991L);
  private static final Comparator<String> CODE_POINT_ORDER =
      (left, right) -> {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int length = Math.min(leftPoints.length, rightPoints.length);
        for (int index = 0; index < length; index++) {
          int comparison =
              Integer.compare(leftPoints[index], rightPoints[index]);
          if (comparison != 0) {
            return comparison;
          }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
      };

  private ComparisonCanonicalEncoding() {}

  static byte[] encode(Object value) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, value);
      }
      return bytes.toByteArray();
    } catch (IOException impossibleForMemoryBuffer) {
      throw new IllegalStateException(
          "CANONICAL_ENCODING_FAILED", impossibleForMemoryBuffer);
    }
  }

  static String domainHash(String domain, Object value) {
    requireDomain(domain);
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    input.write(0);
    input.writeBytes(encode(value));
    try {
      return hex(
          MessageDigest.getInstance("SHA-256")
              .digest(input.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256_UNAVAILABLE", impossible);
    }
  }

  private static void write(DataOutputStream output, Object value)
      throws IOException {
    if (value == null) {
      output.writeByte(NULL);
      return;
    }
    if (value instanceof Boolean booleanValue) {
      output.writeByte(BOOLEAN);
      output.writeByte(booleanValue ? 1 : 0);
      return;
    }
    if (value instanceof CharSequence text) {
      String string = text.toString();
      requireUnicodeScalarString(string);
      writeBytes(
          output, STRING, string.getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (isInteger(value)) {
      BigInteger integer = new BigInteger(value.toString());
      if (integer.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
        throw new IllegalArgumentException(
            "CANONICAL_INTEGER_OUT_OF_RANGE");
      }
      writeBytes(
          output,
          NUMBER,
          integer.toString().getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (value instanceof Number) {
      throw new IllegalArgumentException(
          "CANONICAL_DECIMAL_UNSUPPORTED");
    }
    if (value instanceof List<?> list) {
      output.writeByte(LIST);
      output.writeInt(list.size());
      for (Object item : list) {
        write(output, item);
      }
      return;
    }
    if (value instanceof Map<?, ?> map) {
      writeMap(output, map);
      return;
    }
    throw new IllegalArgumentException(
        "CANONICAL_VALUE_TYPE_UNSUPPORTED");
  }

  private static void writeMap(
      DataOutputStream output, Map<?, ?> map) throws IOException {
    List<Map.Entry<String, Object>> entries = new ArrayList<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(
            "CANONICAL_OBJECT_KEY_INVALID");
      }
      requireUnicodeScalarString(key);
      entries.add(
          new AbstractMap.SimpleImmutableEntry<>(
              key, entry.getValue()));
    }
    entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
    output.writeByte(OBJECT);
    output.writeInt(entries.size());
    for (Map.Entry<String, Object> entry : entries) {
      write(output, entry.getKey());
      write(output, entry.getValue());
    }
  }

  private static boolean isInteger(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger;
  }

  private static void requireDomain(String domain) {
    if (domain == null || domain.isEmpty() || domain.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("CANONICAL_DOMAIN_INVALID");
    }
    requireUnicodeScalarString(domain);
  }

  private static void requireUnicodeScalarString(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(
              "CANONICAL_UNICODE_SCALAR_REQUIRED");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(
            "CANONICAL_UNICODE_SCALAR_REQUIRED");
      }
    }
  }

  private static void writeBytes(
      DataOutputStream output, byte tag, byte[] value)
      throws IOException {
    output.writeByte(tag);
    output.writeInt(value.length);
    output.write(value);
  }

  private static String hex(byte[] value) {
    StringBuilder encoded = new StringBuilder(value.length * 2);
    for (byte item : value) {
      encoded.append(
          Character.forDigit((item >>> 4) & 0xf, 16));
      encoded.append(Character.forDigit(item & 0xf, 16));
    }
    return encoded.toString();
  }
}
