package io.emergeos.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Narrow public boundary for domain-separated hashes over the shared
 * canonical encoding.
 *
 * <p>Callers supply a bounded, typed Map or record tree supported by
 * {@link CanonicalEncoding}. JSON rendering is never hash material.
 */
public final class CanonicalIntegrity {

  private CanonicalIntegrity() {}

  public static String hash(
      String domain, Map<String, Object> canonicalValue) {
    requireDomain(domain);
    if (canonicalValue == null) {
      throw new NullPointerException("canonicalValue");
    }
    return domainHash(
        domain, CanonicalEncoding.encode(canonicalValue));
  }

  public static String chain(
      String domain, String previousHash, String eventHash) {
    requireDomain(domain);
    requireHash(previousHash, "previousHash");
    requireHash(eventHash, "eventHash");
    ByteArrayOutputStream joined = new ByteArrayOutputStream(64);
    joined.writeBytes(fromHex(previousHash));
    joined.writeBytes(fromHex(eventHash));
    return domainHash(domain, joined.toByteArray());
  }

  private static String domainHash(
      String domain, byte[] canonicalValue) {
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    input.write(0);
    input.writeBytes(canonicalValue);
    return sha256(input.toByteArray());
  }

  private static void requireDomain(String domain) {
    if (domain == null
        || !domain.matches(
            "emergeos\\.[a-z0-9][a-z0-9.-]{0,126}\\.v[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "domain must be a versioned EmergeOS hash domain");
    }
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          name + " must be a lowercase SHA-256 digest");
    }
  }

  private static byte[] fromHex(String value) {
    byte[] decoded = new byte[value.length() / 2];
    for (int index = 0; index < decoded.length; index++) {
      decoded[index] =
          (byte)
              ((Character.digit(value.charAt(index * 2), 16) << 4)
                  + Character.digit(
                      value.charAt(index * 2 + 1), 16));
    }
    return decoded;
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is unavailable", impossible);
    }
  }
}
