package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Acceptance Red for the packaged Quick Capture page's first durable POST. */
@Testcontainers
class QuickCaptureDurableReceiptHttpIT {

  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final String PRINCIPAL_ID = "quick-capture-receipt-owner";
  private static final Pattern SCRIPT_SOURCE =
      Pattern.compile("(?i)<script\\b[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void packagedServedScriptProvesCreateReplayDoubleSubmitAndLostResponseRecovery()
      throws Exception {
    RunningApplication application = startApplication();
    Path harness = null;
    Path servedScript = null;
    try {
      HttpResponse<String> page = get(application.port(), "/capture", "text/html");
      assertEquals(200, page.statusCode(), "QUICK_CAPTURE_PAGE_UNAVAILABLE");
      assertPrivateNoStore(page, "QUICK_CAPTURE_PAGE_CACHE_BOUNDARY_MISSING");
      String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
      assertTrue(csp.contains("default-src 'self'"), "QUICK_CAPTURE_CSP_DEFAULT_SELF_MISSING");
      assertTrue(csp.contains("connect-src 'self'"), "QUICK_CAPTURE_CSP_CONNECT_SELF_MISSING");
      assertFalse(
          Pattern.compile("(?i)(?:src|href)\\s*=\\s*['\"](?:https?:)?//")
              .matcher(page.body())
              .find(),
          "QUICK_CAPTURE_EXTERNAL_ASSET_REFERENCE_PRESENT");

      Matcher source = SCRIPT_SOURCE.matcher(page.body());
      assertTrue(source.find(), "QUICK_CAPTURE_SERVED_SCRIPT_REFERENCE_MISSING");
      String scriptPath = source.group(2);
      assertEquals(
          "/capture/quick-capture.js",
          scriptPath,
          "QUICK_CAPTURE_SERVED_SCRIPT_REFERENCE_UNEXPECTED");

      HttpResponse<String> script =
          get(application.port(), scriptPath, "text/javascript");
      assertEquals(200, script.statusCode(), "QUICK_CAPTURE_SERVED_SCRIPT_MISSING");
      assertPrivateNoStore(script, "QUICK_CAPTURE_SCRIPT_CACHE_BOUNDARY_MISSING");

      harness = copyHarnessToTempFile();
      servedScript = Files.createTempFile("emerge-served-quick-capture-", ".js");
      Files.writeString(servedScript, script.body(), StandardCharsets.UTF_8);

      String baseUrl = "http://127.0.0.1:" + application.port();

      HarnessResult created =
          runNodeHarness(harness, servedScript, baseUrl, "create", "qc-phase1-replay-001");
      assertSuccessfulReceipt(created, 201, "SAVED");

      HarnessResult replayed =
          runNodeHarness(harness, servedScript, baseUrl, "create", "qc-phase1-replay-001");
      assertSuccessfulReceipt(replayed, 200, "REPLAYED");
      assertEquals(
          created.captureIdDigest(),
          replayed.captureIdDigest(),
          "QUICK_CAPTURE_EXACT_REPLAY_CAPTURE_ID_CHANGED");
      assertEquals(1, captureRows("qc-phase1-replay-001"), "QUICK_CAPTURE_REPLAY_DUPLICATED_ROW");

      HarnessResult doubled =
          runNodeHarness(harness, servedScript, baseUrl, "double", "qc-phase1-double-001");
      assertSuccessfulReceipt(doubled, 201, "SAVED");
      assertEquals(1, doubled.postCount(), "QUICK_CAPTURE_DOUBLE_SUBMIT_POSTED_TWICE");
      assertEquals(1, doubled.uuidCount(), "QUICK_CAPTURE_DOUBLE_SUBMIT_ROTATED_NONCE");
      assertEquals(1, captureRows("qc-phase1-double-001"), "QUICK_CAPTURE_DOUBLE_SUBMIT_DUPLICATED_ROW");

      HarnessResult lost =
          runNodeHarness(harness, servedScript, baseUrl, "lost", "qc-phase1-lost-001");
      assertSuccessfulReceipt(lost, 200, "REPLAYED");
      assertEquals(2, lost.postCount(), "QUICK_CAPTURE_LOST_RESPONSE_EXACT_RETRY_MISSING");
      assertEquals(201, lost.firstPostStatus(), "QUICK_CAPTURE_LOST_RESPONSE_NOT_AFTER_REAL_201");
      assertEquals(1, lost.unknownObserved(), "QUICK_CAPTURE_LOST_RESPONSE_UNKNOWN_NOT_SHOWN");
      assertEquals(1, lost.postBodyStable(), "QUICK_CAPTURE_LOST_RESPONSE_BODY_CHANGED");
      assertEquals(1, lost.uuidCount(), "QUICK_CAPTURE_LOST_RESPONSE_NONCE_ROTATED");
      assertEquals(
          lost.firstCaptureIdDigest(),
          lost.captureIdDigest(),
          "QUICK_CAPTURE_LOST_RESPONSE_RETRY_CAPTURE_ID_CHANGED");
      assertEquals(1, captureRows("qc-phase1-lost-001"), "QUICK_CAPTURE_LOST_RESPONSE_DUPLICATED_ROW");

      HttpResponse<String> conflictSeed =
          postJson(
              application.port(),
              """
              {"clientNonce":"qc-phase2-conflict-001","content":"synthetic conflicting thought",
               "sourceType":"TEXT","sourceRef":"quick-capture-ui:text","dataClass":"PERSONAL"}
              """,
              "application/json");
      assertEquals(201, conflictSeed.statusCode(), "QUICK_CAPTURE_CONFLICT_SEED_FAILED");
      assertPrivateNoStore(conflictSeed, "QUICK_CAPTURE_201_CACHE_BOUNDARY_MISSING");

      HarnessResult conflict =
          runNodeHarness(harness, servedScript, baseUrl, "conflict", "qc-phase2-conflict-001");
      assertUiFailure(conflict, 409, "CONFLICT");
      assertEquals(1, conflict.postCount(), "QUICK_CAPTURE_CONFLICT_AUTO_RETRIED");
      assertEquals(1, conflict.uuidCount(), "QUICK_CAPTURE_CONFLICT_NONCE_ROTATED");
      assertEquals(1, conflict.inputRetained(), "QUICK_CAPTURE_CONFLICT_INPUT_LOST");
      assertEquals(1, captureRows("qc-phase2-conflict-001"), "QUICK_CAPTURE_CONFLICT_CHANGED_ROW");

      HarnessResult cryptoMissing =
          runNodeHarness(harness, servedScript, baseUrl, "crypto-missing", "unused");
      assertEquals(0, cryptoMissing.postCount(), "QUICK_CAPTURE_CRYPTO_MISSING_POSTED");
      assertEquals("INVALID", cryptoMissing.statusState(), "QUICK_CAPTURE_CRYPTO_MISSING_NOT_INVALID");
      assertEquals(1, cryptoMissing.inputRetained(), "QUICK_CAPTURE_CRYPTO_MISSING_INPUT_LOST");
      assertEquals(1, cryptoMissing.safeCryptoHint(), "QUICK_CAPTURE_CRYPTO_MISSING_HINT_UNSAFE");

      HarnessResult invalid400 =
          runNodeHarness(harness, servedScript, baseUrl, "invalid-400", "invalid nonce");
      assertUiFailure(invalid400, 400, "INVALID");
      HarnessResult invalid415 =
          runNodeHarness(harness, servedScript, baseUrl, "invalid-415", "qc-phase2-415-001");
      assertUiFailure(invalid415, 415, "INVALID");

      HarnessResult conflictRepeat =
          runNodeHarness(
              harness, servedScript, baseUrl, "conflict-repeat", "qc-phase2-conflict-001");
      assertEquals(1, conflictRepeat.postCount(), "QUICK_CAPTURE_CONFLICT_REPEAT_POSTED");
      assertEquals(1, conflictRepeat.uuidCount(), "QUICK_CAPTURE_CONFLICT_REPEAT_ROTATED_NONCE");

      assertReceiptPresentation(created, "SAVED");
      assertReceiptPresentation(replayed, "REPLAYED");
      assertFailureAccessibility(conflict, "CONFLICT");
      assertFailureAccessibility(invalid400, "INVALID");
      HarnessResult unknown =
          runNodeHarness(
              harness, servedScript, baseUrl, "unknown-only", "qc-phase3-unknown-001");
      assertFailureAccessibility(unknown, "UNKNOWN");

      HarnessResult explicitNewEntry =
          runNodeHarness(
              harness, servedScript, baseUrl, "success-new-entry", "qc-phase3-new-entry-001");
      assertEquals(2, explicitNewEntry.postCount(), "QUICK_CAPTURE_EXPLICIT_NEW_ENTRY_POST_COUNT");
      assertEquals(2, explicitNewEntry.uuidCount(), "QUICK_CAPTURE_EXPLICIT_NEW_ENTRY_UUID_COUNT");
      assertEquals(1, explicitNewEntry.newEntryCleared(), "QUICK_CAPTURE_NEW_ENTRY_NOT_CLEARED");
      assertEquals(1, explicitNewEntry.newEntryTextDefault(), "QUICK_CAPTURE_NEW_ENTRY_NOT_TEXT_DEFAULT");
      assertEquals(0, explicitNewEntry.newEntryClickPostDelta(), "QUICK_CAPTURE_NEW_ENTRY_CLICK_POSTED");
      assertEquals(0, explicitNewEntry.newEntryClickUuidDelta(), "QUICK_CAPTURE_NEW_ENTRY_CLICK_UUID");
      assertEquals("INVALID", explicitNewEntry.emptySubmitState(), "QUICK_CAPTURE_EMPTY_SUBMIT_NOT_INVALID");
      assertEquals(0, explicitNewEntry.emptySubmitPostDelta(), "QUICK_CAPTURE_EMPTY_SUBMIT_POSTED");
      assertEquals(0, explicitNewEntry.emptySubmitUuidDelta(), "QUICK_CAPTURE_EMPTY_SUBMIT_UUID");
      assertEquals(1, explicitNewEntry.newContentPostDelta(), "QUICK_CAPTURE_NEW_CONTENT_POST_COUNT");
      assertEquals(1, explicitNewEntry.newContentUuidDelta(), "QUICK_CAPTURE_NEW_CONTENT_UUID_COUNT");
      assertEquals(201, explicitNewEntry.postStatus(), "QUICK_CAPTURE_NEW_CONTENT_NOT_CREATED");

      HarnessResult abandonedRestore =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "lost-abandon-restore",
              "qc-phase4-abandoned-restore-001");
      assertEquals(1, abandonedRestore.unknownObserved(), "QUICK_CAPTURE_ABANDON_PRECONDITION_NOT_UNKNOWN");
      assertEquals(0, abandonedRestore.restoredSnapshotPostDelta(), "QUICK_CAPTURE_ABANDONED_RESTORED_SNAPSHOT_POSTED");
      assertEquals(0, abandonedRestore.restoredSnapshotUuidDelta(), "QUICK_CAPTURE_ABANDONED_RESTORED_SNAPSHOT_UUID");
      assertEquals("UNKNOWN", abandonedRestore.restoredSnapshotState(), "QUICK_CAPTURE_ABANDONED_RESTORED_SNAPSHOT_STATE");

      HttpResponse<String> restoreConflictSeed =
          postJson(
              application.port(),
              """
              {"clientNonce":"qc-phase4-conflict-restore-001","content":"synthetic conflicting thought",
               "sourceType":"TEXT","sourceRef":"quick-capture-ui:text","dataClass":"PERSONAL"}
              """,
              "application/json");
      assertEquals(201, restoreConflictSeed.statusCode(), "QUICK_CAPTURE_RESTORE_CONFLICT_SEED_FAILED");
      HarnessResult conflictRestore =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "conflict-edit-restore",
              "qc-phase4-conflict-restore-001");
      assertEquals(0, conflictRestore.restoredSnapshotPostDelta(), "QUICK_CAPTURE_CONFLICT_RESTORED_SNAPSHOT_POSTED");
      assertEquals(0, conflictRestore.restoredSnapshotUuidDelta(), "QUICK_CAPTURE_CONFLICT_RESTORED_SNAPSHOT_UUID");
      assertEquals("CONFLICT", conflictRestore.restoredSnapshotState(), "QUICK_CAPTURE_CONFLICT_RESTORED_SNAPSHOT_STATE");

      HarnessResult server5xx =
          runNodeHarness(
              harness, servedScript, baseUrl, "server-5xx-retry", "qc-phase4-5xx-001");
      assertRetryUnknown(server5xx, 503, "QUICK_CAPTURE_5XX");
      HarnessResult timeout =
          runNodeHarness(
              harness, servedScript, baseUrl, "timeout-retry", "qc-phase4-timeout-001");
      assertEquals(1, timeout.unknownObserved(), "QUICK_CAPTURE_TIMEOUT_NOT_UNKNOWN");
      assertEquals(2, timeout.postCount(), "QUICK_CAPTURE_TIMEOUT_RETRY_POST_COUNT");
      assertEquals(1, timeout.postBodyStable(), "QUICK_CAPTURE_TIMEOUT_RETRY_BODY_CHANGED");
      assertEquals(1, timeout.uuidCount(), "QUICK_CAPTURE_TIMEOUT_RETRY_NONCE_ROTATED");
      assertEquals(201, timeout.postStatus(), "QUICK_CAPTURE_TIMEOUT_RETRY_NOT_CREATED");

      HarnessResult malformed201 =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "receipt-malformed-201",
              "qc-phase4-malformed-201-001");
      assertEquals("UNKNOWN", malformed201.statusState(), "QUICK_CAPTURE_MALFORMED_201_RECEIPT_NOT_UNKNOWN");

      seedExactCapture(application.port(), "qc-phase4-malformed-200-001");
      HarnessResult malformed200 =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "receipt-malformed-200",
              "qc-phase4-malformed-200-001");
      assertEquals("UNKNOWN", malformed200.statusState(), "QUICK_CAPTURE_MALFORMED_200_RECEIPT_NOT_UNKNOWN");

      HarnessResult mismatch201 =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "receipt-mismatch-201",
              "qc-phase4-mismatch-201-001");
      assertEquals("UNKNOWN", mismatch201.statusState(), "QUICK_CAPTURE_MISMATCHED_201_RECEIPT_NOT_UNKNOWN");

      seedExactCapture(application.port(), "qc-phase4-mismatch-200-001");
      HarnessResult mismatch200 =
          runNodeHarness(
              harness,
              servedScript,
              baseUrl,
              "receipt-mismatch-200",
              "qc-phase4-mismatch-200-001");
      assertEquals("UNKNOWN", mismatch200.statusState(), "QUICK_CAPTURE_MISMATCHED_200_RECEIPT_NOT_UNKNOWN");
    } finally {
      Files.deleteIfExists(servedScript);
      Files.deleteIfExists(harness);
      stop(application);
    }
  }

  @Test
  void exactUiReplayAndReadSurvivePackagedJvmRestartAgainstTheSamePostgres()
      throws Exception {
    Path harness = copyHarnessToTempFile();
    Path servedScript = Files.createTempFile("emerge-served-quick-capture-restart-", ".js");
    RunningApplication first = startApplication();
    long firstPid = first.process().pid();
    String captureId;
    String beforeRestartBody;
    HarnessResult created;
    try {
      HttpResponse<String> script =
          get(first.port(), "/capture/quick-capture.js", "text/javascript");
      Files.writeString(servedScript, script.body(), StandardCharsets.UTF_8);
      created =
          runNodeHarness(
              harness,
              servedScript,
              "http://127.0.0.1:" + first.port(),
              "create",
              "qc-phase2-restart-001");
      assertSuccessfulReceipt(created, 201, "SAVED");
      captureId = captureId("qc-phase2-restart-001");
      HttpResponse<String> beforeRestart =
          get(first.port(), "/api/v1/captures/" + captureId, "application/json");
      assertEquals(200, beforeRestart.statusCode(), "QUICK_CAPTURE_PRE_RESTART_GET_FAILED");
      beforeRestartBody = beforeRestart.body();
    } finally {
      stop(first);
    }

    RunningApplication second = startApplication();
    try {
      assertNotEquals(firstPid, second.process().pid(), "QUICK_CAPTURE_RESTART_REUSED_JVM");
      HttpResponse<String> read =
          get(second.port(), "/api/v1/captures/" + captureId, "application/json");
      assertEquals(200, read.statusCode(), "QUICK_CAPTURE_RESTART_GET_FAILED");
      assertPrivateNoStore(read, "QUICK_CAPTURE_RESTART_GET_CACHE_BOUNDARY_MISSING");
      assertEquals(beforeRestartBody, read.body(), "QUICK_CAPTURE_RESTART_GET_CHANGED_CAPTURE");

      HarnessResult replay =
          runNodeHarness(
              harness,
              servedScript,
              "http://127.0.0.1:" + second.port(),
              "create",
              "qc-phase2-restart-001");
      assertSuccessfulReceipt(replay, 200, "REPLAYED");
      assertEquals(
          created.captureIdDigest(),
          replay.captureIdDigest(),
          "QUICK_CAPTURE_RESTART_REPLAY_CAPTURE_ID_CHANGED");
      assertEquals(1, captureRows("qc-phase2-restart-001"), "QUICK_CAPTURE_RESTART_DUPLICATED_ROW");
    } finally {
      stop(second);
      Files.deleteIfExists(servedScript);
      Files.deleteIfExists(harness);
    }
  }

  private static Path copyHarnessToTempFile() throws IOException {
    try (InputStream source =
        QuickCaptureDurableReceiptHttpIT.class.getResourceAsStream(
            "/io/emergeos/api/quick-capture-dom-harness.mjs")) {
      assertNotNull(source, "QUICK_CAPTURE_DOM_HARNESS_MISSING");
      Path target = Files.createTempFile("emerge-quick-capture-dom-harness-", ".mjs");
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    }
  }

  private static HarnessResult runNodeHarness(
      Path harness, Path servedScript, String appBaseUrl, String mode, String nonce)
      throws Exception {
    Process process =
        new ProcessBuilder(
                "node", harness.toString(), servedScript.toString(), appBaseUrl, mode, nonce)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, () -> "QUICK_CAPTURE_DOM_HARNESS_TIMEOUT output=" + output);
    assertEquals(
        0,
        process.exitValue(),
        () -> "QUICK_CAPTURE_DOM_HARNESS_FAILED output=" + output);
    return new HarnessResult(
        outputValue(output, "NODE_MAJOR"),
        outputValue(output, "SCRIPT_EXECUTED"),
        outputValue(output, "SUBMIT_DISPATCHED"),
        outputValue(output, "POST_COUNT"),
        outputValue(output, "POST_BODY_STABLE"),
        outputValue(output, "FIRST_POST_STATUS"),
        outputValue(output, "POST_STATUS"),
        outputValue(output, "POST_CACHE_CONTROL_NO_STORE"),
        outputValue(output, "UUID_COUNT"),
        outputValue(output, "UNKNOWN_OBSERVED"),
        outputTextValue(output, "STATUS_STATE"),
        outputValue(output, "INPUT_RETAINED"),
        outputValue(output, "SAFE_CRYPTO_HINT"),
        outputValue(output, "SERVER_DETAIL_LEAKED"),
        outputSafeTokenValue(output, "STATUS_ROLE"),
        outputSafeTokenValue(output, "FOCUSED_ELEMENT"),
        outputValue(output, "RECEIPT_CAPTURE_ID_DISPLAYED"),
        outputValue(output, "RECEIPT_CAPTURED_AT_DISPLAYED"),
        outputValue(output, "RECEIPT_SOURCE_TYPE_DISPLAYED"),
        outputValue(output, "RECEIPT_DATA_CLASS_DISPLAYED"),
        outputValue(output, "UNSAFE_RECEIPT_VALUE_DISPLAYED"),
        outputValue(output, "NEW_ENTRY_CLEARED"),
        outputValue(output, "NEW_ENTRY_TEXT_DEFAULT"),
        outputValue(output, "NEW_ENTRY_CLICK_POST_DELTA"),
        outputValue(output, "NEW_ENTRY_CLICK_UUID_DELTA"),
        outputTextValue(output, "EMPTY_SUBMIT_STATE"),
        outputValue(output, "EMPTY_SUBMIT_POST_DELTA"),
        outputValue(output, "EMPTY_SUBMIT_UUID_DELTA"),
        outputValue(output, "NEW_CONTENT_POST_DELTA"),
        outputValue(output, "NEW_CONTENT_UUID_DELTA"),
        outputValue(output, "RESTORED_SNAPSHOT_POST_DELTA"),
        outputValue(output, "RESTORED_SNAPSHOT_UUID_DELTA"),
        outputTextValue(output, "RESTORED_SNAPSHOT_STATE"),
        outputSafeTokenValue(output, "CAPTURE_ID_DIGEST"),
        outputSafeTokenValue(output, "FIRST_CAPTURE_ID_DIGEST"),
        outputValue(output, "CAPTURE_ID_PRESENT"),
        outputTextValue(output, "RECEIPT_SOURCE_TYPE"),
        outputTextValue(output, "RECEIPT_DATA_CLASS"),
        outputValue(output, "GET_STATUS"),
        outputValue(output, "GET_CACHE_CONTROL_NO_STORE"),
        outputValue(output, "GET_CAPTURE_ID_MATCH"));
  }

  private static void assertSuccessfulReceipt(
      HarnessResult result, int expectedStatus, String expectedState) {
    assertEquals(22, result.nodeMajor(), "QUICK_CAPTURE_NODE_22_REQUIRED");
    assertEquals(1, result.scriptExecuted(), "QUICK_CAPTURE_SERVED_SCRIPT_NOT_EXECUTED");
    assertEquals(1, result.submitDispatched(), "QUICK_CAPTURE_SUBMIT_NOT_DISPATCHED");
    assertEquals(expectedStatus, result.postStatus(), "QUICK_CAPTURE_POST_STATUS_UNEXPECTED");
    assertEquals(1, result.postCacheControlNoStore(), "QUICK_CAPTURE_POST_CACHE_BOUNDARY_MISSING");
    assertEquals(expectedState, result.statusState(), "QUICK_CAPTURE_UI_STATE_UNEXPECTED");
    assertEquals(1, result.captureIdPresent(), "QUICK_CAPTURE_RECEIPT_CAPTURE_ID_MISSING");
    assertEquals("TEXT", result.receiptSourceType(), "QUICK_CAPTURE_RECEIPT_SOURCE_TYPE_UNEXPECTED");
    assertEquals("PERSONAL", result.receiptDataClass(), "QUICK_CAPTURE_RECEIPT_DATA_CLASS_UNEXPECTED");
    assertEquals(200, result.getStatus(), "QUICK_CAPTURE_DURABLE_GET_FAILED");
    assertEquals(1, result.getCacheControlNoStore(), "QUICK_CAPTURE_GET_CACHE_BOUNDARY_MISSING");
    assertEquals(1, result.getCaptureIdMatch(), "QUICK_CAPTURE_DURABLE_GET_CAPTURE_ID_MISMATCH");
  }

  private static void assertUiFailure(
      HarnessResult result, int expectedStatus, String expectedState) {
    assertEquals(1, result.scriptExecuted(), "QUICK_CAPTURE_SERVED_SCRIPT_NOT_EXECUTED");
    assertEquals(1, result.submitDispatched(), "QUICK_CAPTURE_SUBMIT_NOT_DISPATCHED");
    assertEquals(expectedStatus, result.postStatus(), "QUICK_CAPTURE_FAILURE_STATUS_UNEXPECTED");
    assertEquals(1, result.postCacheControlNoStore(), "QUICK_CAPTURE_FAILURE_CACHE_BOUNDARY_MISSING");
    assertEquals(expectedState, result.statusState(), "QUICK_CAPTURE_FAILURE_UI_STATE_UNEXPECTED");
    assertEquals(1, result.inputRetained(), "QUICK_CAPTURE_FAILURE_INPUT_LOST");
    assertEquals(0, result.serverDetailLeaked(), "QUICK_CAPTURE_SERVER_DETAIL_LEAKED");
  }

  private static void assertReceiptPresentation(HarnessResult result, String expectedState) {
    assertEquals(expectedState, result.statusState(), "QUICK_CAPTURE_RECEIPT_STATE_UNEXPECTED");
    assertEquals("status", result.statusRole(), "QUICK_CAPTURE_RECEIPT_ROLE_NOT_STATUS");
    assertEquals(1, result.receiptCaptureIdDisplayed(), "QUICK_CAPTURE_RECEIPT_ID_NOT_DISPLAYED");
    assertEquals(1, result.receiptCapturedAtDisplayed(), "QUICK_CAPTURE_RECEIPT_TIME_NOT_DISPLAYED");
    assertEquals(1, result.receiptSourceTypeDisplayed(), "QUICK_CAPTURE_RECEIPT_SOURCE_NOT_DISPLAYED");
    assertEquals(1, result.receiptDataClassDisplayed(), "QUICK_CAPTURE_RECEIPT_DATA_CLASS_NOT_DISPLAYED");
    assertEquals(0, result.unsafeReceiptValueDisplayed(), "QUICK_CAPTURE_UNSAFE_RECEIPT_VALUE_DISPLAYED");
    assertEquals("capture-status", result.focusedElement(), "QUICK_CAPTURE_RECEIPT_NOT_FOCUSED");
  }

  private static void assertFailureAccessibility(HarnessResult result, String expectedState) {
    assertEquals(expectedState, result.statusState(), "QUICK_CAPTURE_FAILURE_STATE_UNEXPECTED");
    assertEquals("alert", result.statusRole(), "QUICK_CAPTURE_FAILURE_ROLE_NOT_ALERT");
    assertEquals("capture-status", result.focusedElement(), "QUICK_CAPTURE_FAILURE_NOT_FOCUSED");
  }

  private static void assertRetryUnknown(
      HarnessResult result, int firstStatus, String markerPrefix) {
    assertEquals(1, result.unknownObserved(), markerPrefix + "_NOT_UNKNOWN");
    assertEquals(2, result.postCount(), markerPrefix + "_RETRY_POST_COUNT");
    assertEquals(firstStatus, result.firstPostStatus(), markerPrefix + "_FIRST_STATUS");
    assertEquals(1, result.postBodyStable(), markerPrefix + "_RETRY_BODY_CHANGED");
    assertEquals(1, result.uuidCount(), markerPrefix + "_RETRY_NONCE_ROTATED");
    assertEquals(201, result.postStatus(), markerPrefix + "_RETRY_NOT_CREATED");
  }

  private static void seedExactCapture(int port, String nonce) throws Exception {
    HttpResponse<String> seed =
        postJson(
            port,
            """
            {"clientNonce":"%s","content":"synthetic durable thought",
             "sourceType":"TEXT","sourceRef":"quick-capture-ui:text","dataClass":"PERSONAL"}
            """.formatted(nonce),
            "application/json");
    assertEquals(201, seed.statusCode(), "QUICK_CAPTURE_RECEIPT_200_SEED_FAILED");
  }

  private static int captureRows(String nonce) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT count(*) FROM captures WHERE principal_id = ? AND client_nonce = ?")) {
      statement.setString(1, PRINCIPAL_ID);
      statement.setString(2, nonce);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next(), "QUICK_CAPTURE_PG_COUNT_MISSING");
        return rows.getInt(1);
      }
    }
  }

  private static String captureId(String nonce) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT capture_id FROM captures WHERE principal_id = ? AND client_nonce = ?")) {
      statement.setString(1, PRINCIPAL_ID);
      statement.setString(2, nonce);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next(), "QUICK_CAPTURE_PG_CAPTURE_MISSING");
        String captureId = rows.getString(1);
        assertFalse(rows.next(), "QUICK_CAPTURE_PG_CAPTURE_DUPLICATED");
        return captureId;
      }
    }
  }

  private static int outputValue(String output, String key) {
    Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "=(\\d+)$").matcher(output);
    assertTrue(
        matcher.find(),
        () -> "QUICK_CAPTURE_DOM_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return Integer.parseInt(matcher.group(1));
  }

  private static String outputTextValue(String output, String key) {
    Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "=([A-Z]+)$").matcher(output);
    assertTrue(
        matcher.find(),
        () -> "QUICK_CAPTURE_DOM_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return matcher.group(1);
  }

  private static String outputSafeTokenValue(String output, String key) {
    Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "=([A-Za-z0-9_-]+)$").matcher(output);
    assertTrue(
        matcher.find(),
        () -> "QUICK_CAPTURE_DOM_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return matcher.group(1);
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-quick-capture-receipt-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + PRINCIPAL_ID);
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitHealthy(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stop(application);
      throw failure;
    }
  }

  private static void awaitHealthy(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      assertTrue(
          application.process().isAlive(),
          () -> "QUICK_CAPTURE_PACKAGED_APP_EXITED log=" + readLog(application.log()));
      try {
        if (get(application.port(), "/actuator/health", "application/json")
                .statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("QUICK_CAPTURE_PACKAGED_APP_START_TIMEOUT");
  }

  private static HttpResponse<String> get(int port, String path, String accept)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", accept)
            .GET()
            .build();
    return HTTP.send(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> postJson(
      int port, String body, String contentType) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/captures"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HTTP.send(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void assertPrivateNoStore(HttpResponse<?> response, String marker) {
    assertEquals(
        "private, no-store",
        response.headers().firstValue("Cache-Control").orElse(""),
        marker);
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    if (application.process().isAlive()) {
      application.process().destroyForcibly();
      assertTrue(
          application.process().waitFor(10, TimeUnit.SECONDS),
          "QUICK_CAPTURE_PACKAGED_APP_DID_NOT_STOP");
    }
    Files.deleteIfExists(application.log());
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException failure) {
      return "unavailable";
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}

  private record HarnessResult(
      int nodeMajor,
      int scriptExecuted,
      int submitDispatched,
      int postCount,
      int postBodyStable,
      int firstPostStatus,
      int postStatus,
      int postCacheControlNoStore,
      int uuidCount,
      int unknownObserved,
      String statusState,
      int inputRetained,
      int safeCryptoHint,
      int serverDetailLeaked,
      String statusRole,
      String focusedElement,
      int receiptCaptureIdDisplayed,
      int receiptCapturedAtDisplayed,
      int receiptSourceTypeDisplayed,
      int receiptDataClassDisplayed,
      int unsafeReceiptValueDisplayed,
      int newEntryCleared,
      int newEntryTextDefault,
      int newEntryClickPostDelta,
      int newEntryClickUuidDelta,
      String emptySubmitState,
      int emptySubmitPostDelta,
      int emptySubmitUuidDelta,
      int newContentPostDelta,
      int newContentUuidDelta,
      int restoredSnapshotPostDelta,
      int restoredSnapshotUuidDelta,
      String restoredSnapshotState,
      String captureIdDigest,
      String firstCaptureIdDigest,
      int captureIdPresent,
      String receiptSourceType,
      String receiptDataClass,
      int getStatus,
      int getCacheControlNoStore,
      int getCaptureIdMatch) {}
}
