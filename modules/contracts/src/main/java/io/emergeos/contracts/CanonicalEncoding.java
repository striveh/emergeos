package io.emergeos.contracts;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CanonicalEncoding {

  private static final byte NULL = 0;
  private static final byte BOOLEAN = 1;
  private static final byte STRING = 2;
  private static final byte NUMBER = 3;
  private static final byte LIST = 4;
  private static final byte OBJECT = 5;
  private static final Comparator<String> CODE_POINT_ORDER =
      (left, right) -> {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int length = Math.min(leftPoints.length, rightPoints.length);
        for (int index = 0; index < length; index++) {
          int comparison = Integer.compare(leftPoints[index], rightPoints[index]);
          if (comparison != 0) {
            return comparison;
          }
        }
        return Integer.compare(leftPoints.length, rightPoints.length);
      };

  private CanonicalEncoding() {}

  static byte[] encode(Object value) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, value);
      }
      return bytes.toByteArray();
    } catch (IOException impossibleForMemoryBuffer) {
      throw new IllegalStateException("Unable to create canonical encoding", impossibleForMemoryBuffer);
    }
  }

  static Map<String, Object> recordValues(Object record, Set<String> excludedFields) {
    if (record == null || !record.getClass().isRecord()) {
      throw new IllegalArgumentException("value must be a record");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (RecordComponent component : record.getClass().getRecordComponents()) {
      if (!excludedFields.contains(component.getName())) {
        try {
          values.put(component.getName(), component.getAccessor().invoke(record));
        } catch (IllegalAccessException | InvocationTargetException reflectionFailure) {
          throw new IllegalStateException(
              "Unable to read record field " + component.getName(), reflectionFailure);
        }
      }
    }
    return values;
  }

  private static void write(DataOutputStream output, Object value) throws IOException {
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
      writeBytes(output, STRING, string.getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (value instanceof Enum<?> enumValue) {
      writeBytes(output, STRING, enumValue.name().getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (value instanceof Number number) {
      writeBytes(output, NUMBER, normalizedNumber(number).getBytes(StandardCharsets.UTF_8));
      return;
    }
    if (value instanceof Instant instant) {
      String normalized = instant.truncatedTo(ChronoUnit.MICROS).toString();
      writeBytes(output, STRING, normalized.getBytes(StandardCharsets.UTF_8));
      return;
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
    if (value.getClass().isRecord()) {
      Set<String> excludedFields =
          value instanceof TaskEnvelope task && "1.0".equals(task.schemaVersion())
              ? Set.of("modelProvider", "modelRequested", "pricingProfile")
              : Set.of();
      writeMap(output, recordValues(value, excludedFields));
      return;
    }
    throw new IllegalArgumentException(
        "Unsupported canonical value type: " + value.getClass().getName());
  }

  private static void writeMap(DataOutputStream output, Map<?, ?> map) throws IOException {
    List<Map.Entry<String, Object>> entries = new ArrayList<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Canonical object keys must be strings");
      }
      requireUnicodeScalarString(key);
      entries.add(new AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
    }
    entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
    output.writeByte(OBJECT);
    output.writeInt(entries.size());
    for (Map.Entry<String, Object> entry : entries) {
      write(output, entry.getKey());
      write(output, entry.getValue());
    }
  }

  private static String normalizedNumber(Number number) {
    if (number instanceof Byte
        || number instanceof Short
        || number instanceof Integer
        || number instanceof Long
        || number instanceof BigInteger) {
      BigInteger integer = new BigInteger(number.toString());
      if (integer.abs().compareTo(BigInteger.valueOf(ContractValueDomains.MAX_SAFE_INTEGER)) > 0) {
        throw new IllegalArgumentException(
            "Canonical integers must stay within the JavaScript-safe domain");
      }
      return integer.toString();
    }
    BigDecimal decimal =
        number instanceof BigDecimal bigDecimal
            ? bigDecimal
            : number instanceof Float || number instanceof Double
                ? BigDecimal.valueOf(number.doubleValue())
                : new BigDecimal(number.toString());
    if (number instanceof BigDecimal || number instanceof Float || number instanceof Double) {
      if (!Double.isFinite(number.doubleValue())) {
        throw new IllegalArgumentException("Canonical decimal numbers must be finite");
      }
      ContractValueDomains.requireUsd(decimal, "canonical decimal");
    }
    if (decimal.signum() == 0) {
      return "0";
    }
    return decimal.stripTrailingZeros().toPlainString();
  }

  private static void requireUnicodeScalarString(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(
              "Canonical strings must not contain lone UTF-16 surrogates");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(
            "Canonical strings must not contain lone UTF-16 surrogates");
      }
    }
  }

  private static void writeBytes(DataOutputStream output, byte tag, byte[] value)
      throws IOException {
    output.writeByte(tag);
    output.writeInt(value.length);
    output.write(value);
  }
}
