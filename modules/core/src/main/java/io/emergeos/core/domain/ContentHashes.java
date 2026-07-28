package io.emergeos.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentHashes {

  private ContentHashes() {}

  public static String sha256(String value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }
}

