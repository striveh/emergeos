package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class OfflineComparisonReportJsonTest {

  @Test
  void canonicalBytesRoundTripAndMatchFrozenGolden() {
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(repository());

    byte[] bytes = OfflineComparisonReportJson.encode(report);

    assertEquals(
        report, OfflineComparisonReportJson.decode(bytes));
    assertAll(
        () -> assertEquals(28_343, bytes.length),
        () ->
            assertEquals(
                "b1152849fc2807d536d59e7a1412bfe4336df4ded51a74ec412bc94d840b12a7",
                sha256(bytes)));
  }

  @Test
  void rejectsEveryParseableNonCanonicalForm() {
    byte[] canonical =
        OfflineComparisonReportJson.encode(
            new Pack004ComparisonRunner().run(repository()));
    String json =
        new String(canonical, StandardCharsets.UTF_8);

    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        (" " + json).getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        (json + "\n").getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_NON_CANONICAL",
        json.replaceFirst(
                "\\{\"storageSchemaVersion\":\"1.0\","
                    + "\"serializationProfile\":"
                    + "\"emergeos-offline-comparison-json-v1\"",
                "{\"serializationProfile\":"
                    + "\"emergeos-offline-comparison-json-v1\","
                    + "\"storageSchemaVersion\":\"1.0\"")
            .getBytes(StandardCharsets.UTF_8));
    byte[] bom = new byte[canonical.length + 3];
    bom[0] = (byte) 0xef;
    bom[1] = (byte) 0xbb;
    bom[2] = (byte) 0xbf;
    System.arraycopy(canonical, 0, bom, 3, canonical.length);
    assertRejected("REPORT_JSON_NON_CANONICAL", bom);
  }

  @Test
  void rejectsDuplicateUnknownMissingNullAndWrongPrimitiveTypes() {
    String json =
        new String(
            OfflineComparisonReportJson.encode(
                new Pack004ComparisonRunner().run(repository())),
            StandardCharsets.UTF_8);
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\\{",
                "{\"storageSchemaVersion\":\"1.0\",")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"report\":\\{",
                "\"unknown\":true,\"report\":{")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"pairId\":",
                "\"pairId\":\"duplicate\",\"pairId\":")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"pairId\":",
                "\"nestedUnknown\":true,\"pairId\":")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"serializationProfile\":"
                    + "\"emergeos-offline-comparison-json-v1\",",
                "")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_STORAGE_PROFILE_MISMATCH",
        json.replaceFirst(
                "\"storageSchemaVersion\":\"1.0\"",
                "\"storageSchemaVersion\":\"2.0\"")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"storageSchemaVersion\":\"1.0\"",
                "\"storageSchemaVersion\":null")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"repetitions\":3",
                "\"repetitions\":3.0")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"requiredEvidenceRef\":\"[^\"]*\"",
                "\"requiredEvidenceRef\":null")
            .getBytes(StandardCharsets.UTF_8));
    assertRejected(
        "REPORT_JSON_INVALID",
        json.replaceFirst(
                "\"schemaVersion\":\"1.0\"",
                "\"schemaVersion\":\"\\\\uD800\"")
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsTrailingInvalidUtf8EmptyAndOversizedInput() {
    byte[] canonical =
        OfflineComparisonReportJson.encode(
            new Pack004ComparisonRunner().run(repository()));
    byte[] trailing =
        Arrays.copyOf(canonical, canonical.length + 2);
    trailing[canonical.length] = '{';
    trailing[canonical.length + 1] = '}';

    assertRejected("REPORT_JSON_INVALID", trailing);
    assertRejected(
        "REPORT_JSON_INVALID",
        new byte[] {'{', '"', (byte) 0xc3, '(', '"', ':', '1', '}'});
    assertRejected("REPORT_SIZE_INVALID", new byte[0]);
    assertRejected(
        "REPORT_SIZE_INVALID",
        new byte[
            OfflineComparisonReportJson.MAX_REPORT_BYTES + 1]);
  }

  @Test
  void encodeRejectsLoneSurrogateAndPreservesUnicodeScalars() {
    OfflineComparisonReport genuine =
        new Pack004ComparisonRunner().run(repository());
    OfflineComparisonReport invalid =
        withVariable(genuine, "invalid-\uD800");

    OfflineComparisonReportJson.Rejected rejected =
        assertThrows(
            OfflineComparisonReportJson.Rejected.class,
            () -> OfflineComparisonReportJson.encode(invalid));
    assertEquals("REPORT_JSON_INVALID", rejected.code());

    OfflineComparisonReport unicode =
        withVariable(genuine, "emoji-😀-NFC-é-NFD-e\u0301");
    assertEquals(
        unicode,
        OfflineComparisonReportJson.decode(
            OfflineComparisonReportJson.encode(unicode)));
  }

  private static void assertRejected(
      String expectedCode, byte[] bytes) {
    OfflineComparisonReportJson.Rejected rejected =
        assertThrows(
            OfflineComparisonReportJson.Rejected.class,
            () -> OfflineComparisonReportJson.decode(bytes));
    assertEquals(expectedCode, rejected.code());
  }

  private static Path repository() {
    return Path.of(System.getProperty("emerge.offline.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static OfflineComparisonReport withVariable(
      OfflineComparisonReport source, String variable) {
    return new OfflineComparisonReport(
        source.schemaVersion(),
        source.reportKind(),
        source.reportId(),
        source.integrityProfile(),
        source.packRawSha256(),
        source.taskId(),
        source.suiteId(),
        variable,
        source.repetitions(),
        source.runnerVersion(),
        source.candidateGeneratorVersion(),
        source.h0VerifierVersion(),
        source.h1VerifierVersion(),
        source.frozenTime(),
        source.pairs(),
        source.evaluations(),
        source.summary(),
        source.effects(),
        source.status(),
        source.issues(),
        source.integrityHash());
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 unavailable", impossible);
    }
  }
}
