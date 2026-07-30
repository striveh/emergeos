package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparisonCanonicalEncodingTest {

  @Test
  void preservesTheExecutableProfilePrimitiveBytes() {
    assertHex("00", ComparisonCanonicalEncoding.encode(null));
    assertHex("0100", ComparisonCanonicalEncoding.encode(false));
    assertHex("0101", ComparisonCanonicalEncoding.encode(true));
    assertHex("0200000000", ComparisonCanonicalEncoding.encode(""));
    assertHex("030000000130", ComparisonCanonicalEncoding.encode(0));
    assertHex("0400000000", ComparisonCanonicalEncoding.encode(List.of()));
    assertHex("0500000000", ComparisonCanonicalEncoding.encode(Map.of()));
  }

  @Test
  void ignoresMapInsertionOrderButPreservesListAndNullMutations() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("nullable", null);
    first.put("ordered", List.of("first", "second"));
    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("ordered", List.of("first", "second"));
    reversed.put("nullable", null);
    Map<String, Object> listOrderMutation = new LinkedHashMap<>();
    listOrderMutation.put("nullable", null);
    listOrderMutation.put("ordered", List.of("second", "first"));

    assertArrayEquals(
        ComparisonCanonicalEncoding.encode(first),
        ComparisonCanonicalEncoding.encode(reversed));
    assertNotEquals(
        hex(ComparisonCanonicalEncoding.encode(first)),
        hex(ComparisonCanonicalEncoding.encode(listOrderMutation)));
    assertNotEquals(
        hex(ComparisonCanonicalEncoding.encode(first)),
        hex(
            ComparisonCanonicalEncoding.encode(
                Map.of("nullable", "", "ordered", List.of("first", "second")))));
  }

  @Test
  void sortsObjectKeysByUnicodeCodePointRatherThanUtf16CodeUnit() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("😀", "astral");
    fields.put("\uE000", "bmp");

    assertHex(
        "0500000002"
            + "0200000003ee8080"
            + "0200000003626d70"
            + "0200000004f09f9880"
            + "020000000661737472616c",
        ComparisonCanonicalEncoding.encode(fields));
  }

  @Test
  void usesUtf8ByteLengthAndDoesNotNormalizeUnicode() {
    assertHex(
        "0200000006e5b08fe6be9c",
        ComparisonCanonicalEncoding.encode("小澜"));
    assertNotEquals(
        hex(ComparisonCanonicalEncoding.encode("é")),
        hex(ComparisonCanonicalEncoding.encode("e\u0301")));
  }

  @Test
  void rejectsLoneUtf16SurrogatesInValuesAndKeys() {
    Map<String, Object> invalidKey = new LinkedHashMap<>();
    invalidKey.put("\uDC00", "value");

    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCanonicalEncoding.encode("\uD800"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCanonicalEncoding.encode(invalidKey));
  }

  @Test
  void acceptsOnlyTheJavascriptSafeIntegerDomain() {
    ComparisonCanonicalEncoding.encode(9_007_199_254_740_991L);
    ComparisonCanonicalEncoding.encode(-9_007_199_254_740_991L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComparisonCanonicalEncoding.encode(
                9_007_199_254_740_992L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComparisonCanonicalEncoding.encode(
                -9_007_199_254_740_992L));
  }

  @Test
  void rejectsDecimalAndUnsupportedValueTypesInThisLocalProfile() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCanonicalEncoding.encode(1.5d));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCanonicalEncoding.encode(new Object()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCanonicalEncoding.encode(Map.of(1, "not-a-string-key")));
  }

  @Test
  void domainHashMatchesIndependentNodeUnicodeCandidateVector() {
    String required = "capture://capture-s4-o1-reference-004";
    Map<String, Object> proposal = new LinkedHashMap<>();
    proposal.put("content", "虚构人物小澜：引用已落地。");
    proposal.put("evidenceRefs", List.of(required));
    Map<String, Object> candidate = new LinkedHashMap<>();
    candidate.put("proposal", proposal);
    candidate.put("obtainedEvidenceRefs", List.of(required));
    candidate.put("requiredEvidenceRef", required);
    candidate.put("requiredEvidenceAvailable", true);

    assertEquals(
        "17f33ede677f8ebbbd51aa3e14f8058be4e5b3abc862e67ba7c15b7a5f0ded3b",
        ComparisonCanonicalEncoding.domainHash(
            "emergeos.offline-harness.candidate.v1", candidate));
  }

  @Test
  void matchesAnIndependentFixedParityFixture() {
    Map<String, Object> fixture = new LinkedHashMap<>();
    fixture.put("nullable", null);
    fixture.put("ordered", List.of("α", 7, true));

    assertHex(
        "0500000002"
            + "02000000086e756c6c61626c65"
            + "00"
            + "02000000076f726465726564"
            + "0400000003"
            + "0200000002ceb1"
            + "030000000137"
            + "0101",
        ComparisonCanonicalEncoding.encode(fixture));
  }

  private static void assertHex(String expected, byte[] actual) {
    assertArrayEquals(
        HexFormat.of().parseHex(expected),
        actual,
        () -> "actual=" + HexFormat.of().formatHex(actual));
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
