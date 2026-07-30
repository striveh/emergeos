package io.emergeos.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Shared text domain for persisted and hashed v1 contracts.
 *
 * <p>Contract strings are Unicode scalar text, never blank, never contain NUL, and are bounded
 * before they can reach JSON, canonical hashing, or a database column.
 */
public final class ContractText {

  public static final int MAX_TEXT_LENGTH = 2_048;
  public static final int MAX_NAME_LENGTH = 200;
  public static final int MAX_MODEL_LENGTH = 512;

  private ContractText() {}

  public static String require(String value, String name) {
    return require(value, name, MAX_TEXT_LENGTH);
  }

  public static String require(String value, String name, int maxLength) {
    if (value == null
        || !containsNonWhitespace(value)
        || value.codePointCount(0, value.length()) > maxLength
        || value.indexOf('\0') >= 0
        || containsLoneSurrogate(value)) {
      throw new IllegalArgumentException(name + " must be safe non-blank text");
    }
    return value;
  }

  public static String requireOptional(String value, String name) {
    return requireOptional(value, name, MAX_TEXT_LENGTH);
  }

  public static String requireOptional(String value, String name, int maxLength) {
    if (value != null) {
      require(value, name, maxLength);
    }
    return value;
  }

  public static List<String> copyStrings(List<String> value, String name) {
    return copyStrings(value, name, MAX_TEXT_LENGTH);
  }

  public static List<String> copyStrings(List<String> value, String name, int maxLength) {
    List<String> copy = List.copyOf(Objects.requireNonNull(value, name));
    for (int index = 0; index < copy.size(); index++) {
      require(copy.get(index), name + "[" + index + "]", maxLength);
    }
    return copy;
  }

  public static boolean containsLoneSurrogate(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          return true;
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsNonWhitespace(String value) {
    return value.codePoints().anyMatch(codePoint -> !isFrozenWhitespace(codePoint));
  }

  /**
   * Unicode White_Space property frozen for contract v1 instead of delegating to
   * runtime-version-specific trim/isBlank behavior.
   */
  private static boolean isFrozenWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000D)
        || codePoint == 0x0020
        || codePoint == 0x0085
        || codePoint == 0x00A0
        || codePoint == 0x1680
        || (codePoint >= 0x2000 && codePoint <= 0x200A)
        || codePoint == 0x2028
        || codePoint == 0x2029
        || codePoint == 0x202F
        || codePoint == 0x205F
        || codePoint == 0x3000;
  }
}
