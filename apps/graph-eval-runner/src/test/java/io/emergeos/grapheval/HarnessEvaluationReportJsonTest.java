package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.emergeos.contracts.HarnessEvaluationReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class HarnessEvaluationReportJsonTest {

  private static final ObjectMapper FIXTURE_JSON =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void canonicalBytesRoundTripTheReviewedContractFixture() {
    HarnessEvaluationReport report = reviewedContractFixture();

    byte[] canonical = HarnessEvaluationReportJson.encode(report);
    HarnessEvaluationReport decoded =
        HarnessEvaluationReportJson.decode(canonical);

    assertEquals(report, decoded);
    assertArrayEquals(
        canonical, HarnessEvaluationReportJson.encode(decoded));
    String json = new String(canonical, StandardCharsets.UTF_8);
    assertTrue(
        json.startsWith(
            "{\"storageSchemaVersion\":\"1.0\","
                + "\"serializationProfile\":"
                + "\"emergeos-harness-evaluation-report-json-v1\","
                + "\"report\":{\"schemaVersion\":\"1.0\""));
    assertTrue(json.contains("参透世界之后"));
    assertTrue(
        decoded.repetitions().getFirst().candidate().content()
            .contains("🌌"));
  }

  @Test
  void rejectsEveryParseableNonCanonicalForm() {
    byte[] canonical =
        HarnessEvaluationReportJson.encode(reviewedContractFixture());
    String json = new String(canonical, StandardCharsets.UTF_8);
    String prefix =
        "{\"storageSchemaVersion\":\"1.0\","
            + "\"serializationProfile\":"
            + "\"emergeos-harness-evaluation-report-json-v1\"";

    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        (" " + json).getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        (json + "\n").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        replaceFirst(
                json,
                prefix,
                "{\"serializationProfile\":"
                    + "\"emergeos-harness-evaluation-report-json-v1\","
                    + "\"storageSchemaVersion\":\"1.0\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        replaceFirst(
                json,
                "\"schemaVersion\":\"1.0\"",
                "\"schemaVersion\":\"\\u0031.0\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        replaceFirst(
                json,
                "\"observedCostUsd\":0.000015",
                "\"observedCostUsd\":1.5e-5")
            .getBytes(StandardCharsets.UTF_8));

    byte[] bom = new byte[canonical.length + 3];
    bom[0] = (byte) 0xef;
    bom[1] = (byte) 0xbb;
    bom[2] = (byte) 0xbf;
    System.arraycopy(canonical, 0, bom, 3, canonical.length);
    assertRejected("REPORT_JSON_NON_CANONICAL", bom);
  }

  @Test
  void rejectsDuplicateUnknownMissingNullWrongTypesAndCoercions() {
    String json =
        new String(
            HarnessEvaluationReportJson.encode(
                reviewedContractFixture()),
            StandardCharsets.UTF_8);

    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "{",
                "{\"storageSchemaVersion\":\"1.0\",")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"armId\":",
                "\"unexpected\":true,\"armId\":")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"armId\":",
                "\"armId\":\"duplicate\",\"armId\":")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(json, ",\"failureCode\":null", "")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"schemaVersion\":\"1.0\"",
                "\"schemaVersion\":null")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"repetition\":1",
                "\"repetition\":1.0")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"repetition\":1",
                "\"repetition\":\"1\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"requiredEvidenceAvailable\":true",
                "\"requiredEvidenceAvailable\":\"true\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"reportStatus\":\"COMPLETE\"",
                "\"reportStatus\":0")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"observedCostUsd\":0.000015",
                "\"observedCostUsd\":\"0.000015\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"serializationProfile\":"
                    + "\"emergeos-harness-evaluation-report-json-v1\",",
                "")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_STORAGE_PROFILE_MISMATCH",
        replaceFirst(
                json,
                "\"storageSchemaVersion\":\"1.0\"",
                "\"storageSchemaVersion\":\"2.0\"")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsCanonicalShapeThatFailsProductionRecordConstructors() {
    HarnessEvaluationReport report = reviewedContractFixture();
    String json =
        new String(
            HarnessEvaluationReportJson.encode(report),
            StandardCharsets.UTF_8);

    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"reportId\":\"" + report.reportId() + "\"",
                "\"reportId\":\"harness-evaluation-report-"
                    + "0".repeat(64)
                    + "\"")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsTrailingInvalidUtf8LoneSurrogateEmptyAndOversizedInput() {
    byte[] canonical =
        HarnessEvaluationReportJson.encode(reviewedContractFixture());
    byte[] trailing = Arrays.copyOf(canonical, canonical.length + 2);
    trailing[canonical.length] = '{';
    trailing[canonical.length + 1] = '}';

    assertRejected("REPORT_JSON_INVALID", trailing);
    assertRejected(
        "REPORT_JSON_INVALID",
        new byte[] {'{', '"', (byte) 0xc3, '(', '"', ':', '1', '}'});
    String json = new String(canonical, StandardCharsets.UTF_8);
    assertRejected(
        "REPORT_JSON_INVALID",
        replaceFirst(
                json,
                "\"schemaVersion\":\"1.0\"",
                "\"schemaVersion\":\"\\uD800\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected("REPORT_SIZE_INVALID", new byte[0]);
    assertRejected("REPORT_SIZE_INVALID", null);
    assertRejected(
        "REPORT_SIZE_INVALID",
        new byte[HarnessEvaluationReportJson.MAX_REPORT_BYTES + 1]);
  }

  private static void assertRejected(
      String expectedCode, byte[] bytes) {
    HarnessEvaluationReportJson.Rejected rejected =
        assertThrows(
            HarnessEvaluationReportJson.Rejected.class,
            () -> HarnessEvaluationReportJson.decode(bytes));
    assertEquals(expectedCode, rejected.code());
  }

  private static HarnessEvaluationReport reviewedContractFixture() {
    Path fixture =
        Path.of(System.getProperty("emerge.graph.repo"))
            .resolve(
                "contracts/fixtures/v1/harness-evaluation-report/valid-complete.json");
    try {
      return FIXTURE_JSON.readValue(
          Files.readAllBytes(fixture), HarnessEvaluationReport.class);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "cannot read reviewed HarnessEvaluationReport fixture",
          failure);
    }
  }

  private static String replaceFirst(
      String source, String target, String replacement) {
    int index = source.indexOf(target);
    if (index < 0) {
      throw new IllegalStateException(
          "canonical fixture does not contain " + target);
    }
    return source.substring(0, index)
        + replacement
        + source.substring(index + target.length());
  }
}
