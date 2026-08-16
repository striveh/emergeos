package io.emergeos.grapheval;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.emergeos.contracts.HarnessEvaluationReport;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical durable UTF-8 JSON for one complete Harness evaluation Report.
 *
 * <p>The storage encoding is deliberately separate from the Report's
 * semantic integrity encoding. Decode is fail-closed: the bounded input must
 * have an exact record-shaped JSON tree, survive every production record
 * constructor, and re-encode to the identical bytes.
 */
final class HarnessEvaluationReportJson {

  static final String STORAGE_SCHEMA_VERSION = "1.0";
  static final String SERIALIZATION_PROFILE =
      "emergeos-harness-evaluation-report-json-v1";
  static final int MAX_REPORT_BYTES = 1024 * 1024;

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

  private static final ObjectMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_REPORT_BYTES)
                          .maxTokenCount(100_000)
                          .maxNestingDepth(32)
                          .maxNumberLength(32)
                          .maxStringLength(128 * 1024)
                          .maxNameLength(256)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                  .build())
          .addModule(new JavaTimeModule())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .build();

  private HarnessEvaluationReportJson() {}

  static byte[] encode(HarnessEvaluationReport report) {
    Objects.requireNonNull(report, "report");
    try {
      ObjectNode envelope = JSON.createObjectNode();
      envelope.put("storageSchemaVersion", STORAGE_SCHEMA_VERSION);
      envelope.put("serializationProfile", SERIALIZATION_PROFILE);
      envelope.set("report", canonicalNode(report));
      requireUnicodeScalarTree(envelope);
      byte[] bytes = JSON.writeValueAsBytes(envelope);
      if (bytes.length < 1 || bytes.length > MAX_REPORT_BYTES) {
        throw rejected("REPORT_SIZE_INVALID");
      }
      return bytes;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
      throw rejected("REPORT_SERIALIZATION_FAILED");
    }
  }

  static HarnessEvaluationReport decode(byte[] bytes) {
    if (bytes == null
        || bytes.length < 1
        || bytes.length > MAX_REPORT_BYTES) {
      throw rejected("REPORT_SIZE_INVALID");
    }
    try {
      ObjectNode envelope =
          exactObject(
              JSON.readTree(bytes),
              "storageSchemaVersion",
              "serializationProfile",
              "report");
      requireUnicodeScalarTree(envelope);
      if (!STORAGE_SCHEMA_VERSION.equals(
              text(envelope, "storageSchemaVersion"))
          || !SERIALIZATION_PROFILE.equals(
              text(envelope, "serializationProfile"))) {
        throw rejected("REPORT_STORAGE_PROFILE_MISMATCH");
      }

      JsonNode reportNode = envelope.get("report");
      requireExactShape(reportNode, HarnessEvaluationReport.class);
      HarnessEvaluationReport report =
          JSON.treeToValue(reportNode, HarnessEvaluationReport.class);
      byte[] canonical = encode(report);
      if (!MessageDigest.isEqual(bytes, canonical)) {
        throw rejected("REPORT_JSON_NON_CANONICAL");
      }
      return report;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("REPORT_JSON_INVALID");
    }
  }

  private static JsonNode canonicalNode(Object value)
      throws ReflectiveOperationException {
    if (value == null) {
      return JSON.nullNode();
    }
    if (value instanceof String text) {
      return JSON.getNodeFactory().textNode(
          requireUnicodeScalarString(text));
    }
    if (value instanceof Boolean booleanValue) {
      return JSON.getNodeFactory().booleanNode(booleanValue);
    }
    if (value instanceof Integer integer) {
      return JSON.getNodeFactory().numberNode(integer);
    }
    if (value instanceof Long longValue) {
      return JSON.getNodeFactory().numberNode(longValue);
    }
    if (value instanceof Double doubleValue) {
      if (!Double.isFinite(doubleValue)) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return JSON.getNodeFactory().numberNode(doubleValue);
    }
    if (value instanceof BigDecimal decimal) {
      return JSON.getNodeFactory().numberNode(decimal);
    }
    if (value instanceof Instant instant) {
      return JSON.getNodeFactory().textNode(instant.toString());
    }
    if (value instanceof Enum<?> enumeration) {
      return JSON.getNodeFactory().textNode(enumeration.name());
    }
    if (value instanceof List<?> list) {
      ArrayNode node = JSON.createArrayNode();
      for (Object element : list) {
        node.add(canonicalNode(element));
      }
      return node;
    }
    if (value instanceof Map<?, ?> map) {
      List<Map.Entry<String, ?>> entries = new ArrayList<>(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String name)) {
          throw rejected("REPORT_JSON_INVALID");
        }
        entries.add(
            Map.entry(
                requireUnicodeScalarString(name), entry.getValue()));
      }
      entries.sort(
          (left, right) ->
              CODE_POINT_ORDER.compare(
                  left.getKey(), right.getKey()));
      ObjectNode node = JSON.createObjectNode();
      for (Map.Entry<String, ?> entry : entries) {
        node.set(entry.getKey(), canonicalNode(entry.getValue()));
      }
      return node;
    }
    if (value.getClass().isRecord()) {
      ObjectNode node = JSON.createObjectNode();
      for (RecordComponent component :
          value.getClass().getRecordComponents()) {
        node.set(
            component.getName(),
            canonicalNode(component.getAccessor().invoke(value)));
      }
      return node;
    }
    throw rejected("REPORT_JSON_INVALID");
  }

  private static void requireExactShape(JsonNode value, Type expected) {
    if (value == null) {
      throw rejected("REPORT_JSON_INVALID");
    }
    if (value.isNull()) {
      if (expected instanceof Class<?> type && type.isPrimitive()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (expected instanceof ParameterizedType parameterized) {
      requireParameterizedShape(value, parameterized);
      return;
    }
    if (!(expected instanceof Class<?> type)) {
      throw rejected("REPORT_JSON_INVALID");
    }
    if (type == String.class) {
      if (!value.isTextual()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      requireUnicodeScalarString(value.textValue());
      return;
    }
    if (type == Instant.class) {
      if (!value.isTextual()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      try {
        Instant.parse(requireUnicodeScalarString(value.textValue()));
      } catch (DateTimeParseException failure) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type == int.class || type == Integer.class) {
      if (!value.isIntegralNumber() || !value.canConvertToInt()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type == long.class || type == Long.class) {
      if (!value.isIntegralNumber() || !value.canConvertToLong()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type == boolean.class || type == Boolean.class) {
      if (!value.isBoolean()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type == BigDecimal.class) {
      if (!value.isNumber()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type == double.class || type == Double.class) {
      if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type.isEnum()) {
      if (!value.isTextual()
          || Arrays.stream(type.getEnumConstants())
              .map(constant -> ((Enum<?>) constant).name())
              .noneMatch(value.textValue()::equals)) {
        throw rejected("REPORT_JSON_INVALID");
      }
      return;
    }
    if (type.isRecord()) {
      requireRecordShape(value, type);
      return;
    }
    throw rejected("REPORT_JSON_INVALID");
  }

  private static void requireParameterizedShape(
      JsonNode value, ParameterizedType expected) {
    if (!(expected.getRawType() instanceof Class<?> rawType)) {
      throw rejected("REPORT_JSON_INVALID");
    }
    if (rawType == List.class) {
      if (!(value instanceof ArrayNode array)) {
        throw rejected("REPORT_JSON_INVALID");
      }
      Type elementType = expected.getActualTypeArguments()[0];
      array.forEach(element -> requireExactShape(element, elementType));
      return;
    }
    if (rawType == Map.class) {
      if (!(value instanceof ObjectNode object)
          || expected.getActualTypeArguments()[0] != String.class) {
        throw rejected("REPORT_JSON_INVALID");
      }
      Type valueType = expected.getActualTypeArguments()[1];
      object.forEachEntry(
          (name, child) -> {
            requireUnicodeScalarString(name);
            requireExactShape(child, valueType);
          });
      return;
    }
    throw rejected("REPORT_JSON_INVALID");
  }

  private static void requireRecordShape(
      JsonNode value, Class<?> recordType) {
    RecordComponent[] components = recordType.getRecordComponents();
    String[] names =
        Arrays.stream(components)
            .map(RecordComponent::getName)
            .toArray(String[]::new);
    ObjectNode object = exactObject(value, names);
    for (RecordComponent component : components) {
      requireExactShape(
          object.get(component.getName()),
          component.getGenericType());
    }
  }

  private static ObjectNode exactObject(
      JsonNode value, String... fields) {
    if (!(value instanceof ObjectNode object)
        || object.size() != fields.length
        || !fieldSet(object).equals(Set.of(fields))) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return object;
  }

  private static Set<String> fieldSet(ObjectNode node) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return Set.copyOf(fields);
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return requireUnicodeScalarString(value.textValue());
  }

  private static String requireUnicodeScalarString(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw rejected("REPORT_JSON_INVALID");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw rejected("REPORT_JSON_INVALID");
      }
    }
    return value;
  }

  private static void requireUnicodeScalarTree(JsonNode value) {
    if (value.isTextual()) {
      requireUnicodeScalarString(value.textValue());
      return;
    }
    if (value.isArray()) {
      value.forEach(HarnessEvaluationReportJson::requireUnicodeScalarTree);
      return;
    }
    if (value.isObject()) {
      value.forEachEntry(
          (name, child) -> {
            requireUnicodeScalarString(name);
            requireUnicodeScalarTree(child);
          });
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
