package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalIntegrityTest {

  @Test
  void hashesCanonicalTypedMaterialIndependentOfMapOrder() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("中文", "值");
    first.put("count", 1);
    first.put("cost", new BigDecimal("0.417000"));
    first.put("at", Instant.parse("2026-07-31T06:00:00Z"));
    first.put("items", List.of("a", "b"));

    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("items", List.of("a", "b"));
    reordered.put("at", Instant.parse("2026-07-31T06:00:00Z"));
    reordered.put("cost", new BigDecimal("0.417"));
    reordered.put("count", 1L);
    reordered.put("中文", "值");

    assertEquals(
        CanonicalIntegrity.hash("emergeos.test-vector.v1", first),
        CanonicalIntegrity.hash(
            "emergeos.test-vector.v1", reordered));
    assertNotEquals(
        CanonicalIntegrity.hash("emergeos.test-vector.v1", first),
        CanonicalIntegrity.hash("emergeos.test-vector.v2", first));
  }

  @Test
  void chainsRawHashesAndRejectsAmbiguousInputs() {
    String left =
        CanonicalIntegrity.hash(
            "emergeos.test-left.v1", Map.of("value", "a"));
    String right =
        CanonicalIntegrity.hash(
            "emergeos.test-right.v1", Map.of("value", "b"));

    assertEquals(
        64,
        CanonicalIntegrity.chain(
                "emergeos.test-chain.v1", left, right)
            .length());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalIntegrity.hash(
                "unversioned", Map.of("value", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanonicalIntegrity.chain(
                "emergeos.test-chain.v1", "no", right));
  }
}
