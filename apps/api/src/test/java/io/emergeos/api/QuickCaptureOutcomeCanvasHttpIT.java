package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
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
import java.security.MessageDigest;
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

/** Packaged acceptance for starting an Outcome Canvas from one durable Quick Capture. */
@Testcontainers
class QuickCaptureOutcomeCanvasHttpIT {

  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
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
  void durableCaptureCanTriggerOutcomeCanvasFromTheActualServedPage() throws Exception {
    RunningApplication application = startApplication();
    Path htmlFile = null;
    Path scriptFile = null;
    Path harnessFile = null;
    try {
      HttpResponse<String> created =
          sendJson(
              application.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "outcome-canvas-seed-001",
                "content": "synthetic outcome canvas seed",
                "sourceType": "TEXT",
                "sourceRef": "quick-capture-ui:text",
                "dataClass": "PERSONAL"
              }
              """);
      assertEquals(201, created.statusCode(), "OUTCOME_CANVAS_CAPTURE_CREATE_FAILED");
      assertPrivateNoStore(created, "OUTCOME_CANVAS_CAPTURE_CREATE_CACHE_BOUNDARY_MISSING");
      String captureId = JsonPath.read(created.body(), "$.captureId");
      assertTrue(captureId != null && !captureId.isBlank(), "OUTCOME_CANVAS_CAPTURE_ID_MISSING");

      HttpResponse<String> persisted =
          sendJson(application.port(), "GET", "/api/v1/captures/" + captureId, null);
      assertEquals(200, persisted.statusCode(), "OUTCOME_CANVAS_CAPTURE_GET_FAILED");
      assertPrivateNoStore(persisted, "OUTCOME_CANVAS_CAPTURE_GET_CACHE_BOUNDARY_MISSING");
      assertEquals(captureId, JsonPath.read(persisted.body(), "$.captureId"));

      HttpResponse<String> page = send(application.port(), "/capture", "text/html");
      assertEquals(200, page.statusCode(), "OUTCOME_CANVAS_PAGE_UNAVAILABLE");
      assertPrivateNoStore(page, "OUTCOME_CANVAS_PAGE_CACHE_BOUNDARY_MISSING");
      Matcher scriptSource = SCRIPT_SOURCE.matcher(page.body());
      assertTrue(scriptSource.find(), "OUTCOME_CANVAS_SERVED_SCRIPT_REFERENCE_MISSING");
      assertEquals(
          "/capture/quick-capture.js",
          scriptSource.group(2),
          "OUTCOME_CANVAS_SERVED_SCRIPT_REFERENCE_UNEXPECTED");

      HttpResponse<String> script =
          send(application.port(), scriptSource.group(2), "text/javascript");
      assertEquals(200, script.statusCode(), "OUTCOME_CANVAS_SERVED_SCRIPT_MISSING");
      assertPrivateNoStore(script, "OUTCOME_CANVAS_SCRIPT_CACHE_BOUNDARY_MISSING");

      htmlFile = Files.createTempFile("emerge-outcome-canvas-page-", ".html");
      scriptFile = Files.createTempFile("emerge-outcome-canvas-served-", ".js");
      Files.writeString(htmlFile, page.body(), StandardCharsets.UTF_8);
      Files.writeString(scriptFile, script.body(), StandardCharsets.UTF_8);
      harnessFile = copyHarnessToTempFile();

      HarnessResult result =
          runHarness(
              harnessFile,
              htmlFile,
              scriptFile,
              "http://127.0.0.1:" + application.port(),
              sha256(captureId),
              "outcome-canvas-seed-001",
              "happy");
      assertEquals(22, result.nodeMajor(), "OUTCOME_CANVAS_NODE_22_REQUIRED");
      assertEquals(1, result.scriptExecuted(), "OUTCOME_CANVAS_SERVED_SCRIPT_NOT_EXECUTED");
      assertEquals(1, result.captureSubmitDispatched(), "OUTCOME_CANVAS_CAPTURE_REPLAY_NOT_DISPATCHED");
      assertEquals(200, result.captureReplayStatus(), "OUTCOME_CANVAS_CAPTURE_REPLAY_FAILED");
      assertEquals(1, result.captureIdMatch(), "OUTCOME_CANVAS_CAPTURE_REPLAY_ID_MISMATCH");
      assertEquals(1, result.triggerPresent(), "OUTCOME_CANVAS_TRIGGER_MISSING");
      assertEquals(1, result.triggerDispatched(), "OUTCOME_CANVAS_TRIGGER_NOT_DISPATCHED");
      assertEquals(
          1,
          result.agentDraftPostCount(),
          "OUTCOME_CANVAS_AGENT_DRAFT_POST_COUNT_UNEXPECTED");
      assertEquals(201, result.agentDraftStatus(), "OUTCOME_CANVAS_AGENT_DRAFT_NOT_CREATED");
      assertEquals(
          1,
          result.agentDraftCacheNoStore(),
          "OUTCOME_CANVAS_AGENT_DRAFT_CACHE_BOUNDARY_MISSING");
      assertEquals(
          1,
          result.agentDraftLocationValid(),
          "OUTCOME_CANVAS_AGENT_DRAFT_LOCATION_INVALID");
      assertEquals(1, result.agentDraftSucceeded(), "OUTCOME_CANVAS_AGENT_DRAFT_NOT_SUCCEEDED");
      assertEquals(1, result.artifactRefsExact(), "OUTCOME_CANVAS_ARTIFACT_REFS_MISMATCH");
      assertEquals(1, result.evidenceRefsExact(), "OUTCOME_CANVAS_EVIDENCE_REFS_MISMATCH");
      assertEquals(200, result.captureGetStatus(), "OUTCOME_CANVAS_CAPTURE_READ_AFTER_DRAFT_FAILED");
      assertEquals(200, result.artifactGetStatus(), "OUTCOME_CANVAS_ARTIFACT_V1_GET_FAILED");
      assertEquals(1, result.artifactV1Match(), "OUTCOME_CANVAS_ARTIFACT_V1_MISMATCH");
      assertEquals("CANVAS_READY", result.canvasState(), "OUTCOME_CANVAS_V1_DOM_STATE_MISMATCH");
      assertEquals(1, result.canvasV1ContentRendered(), "OUTCOME_CANVAS_V1_CONTENT_NOT_RENDERED");
      assertEquals(1, result.canvasFakeBoundaryRendered(), "OUTCOME_CANVAS_FAKE_BOUNDARY_MISSING");
      assertEquals(1, result.uiCaptureGetCount(), "OUTCOME_CANVAS_UI_CAPTURE_GET_COUNT");
      assertEquals(1, result.uiArtifactGetCount(), "OUTCOME_CANVAS_UI_ARTIFACT_GET_COUNT");
      assertEquals(1, result.uiCaptureGetPathExact(), "OUTCOME_CANVAS_UI_CAPTURE_GET_PATH_MISMATCH");
      assertEquals(1, result.uiArtifactGetPathExact(), "OUTCOME_CANVAS_UI_ARTIFACT_GET_PATH_MISMATCH");
      assertEquals(1, result.agentDraftPostCount(), "OUTCOME_CANVAS_SECOND_CLICK_CREATED_DRAFT");

      HarnessResult lost =
          runScenario(
              application.port(),
              harnessFile,
              htmlFile,
              scriptFile,
              "outcome-canvas-lost-001",
              "draft-response-lost");
      assertGenerationUnknownWithoutSecondDraft(lost, "OUTCOME_CANVAS_DRAFT_RESPONSE_LOST");

      HarnessResult invalidLocation =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-location-001", "invalid-location");
      assertGenerationUnknownWithoutSecondDraft(invalidLocation, "OUTCOME_CANVAS_INVALID_LOCATION");
      HarnessResult crossrefs =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-crossrefs-001", "crossrefs");
      assertGenerationUnknownWithoutSecondDraft(crossrefs, "OUTCOME_CANVAS_CROSSREFS");
      HarnessResult oversize =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-oversize-001", "oversize-body");
      assertGenerationUnknownWithoutSecondDraft(oversize, "OUTCOME_CANVAS_OVERSIZE_BODY");

      HarnessResult known422 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-known422-001", "known-422");
      assertEquals("GENERATION_FAILED", known422.canvasState(), "OUTCOME_CANVAS_KNOWN_422_NOT_FAILED");
      assertEquals(1, known422.agentDraftPostCount(), "OUTCOME_CANVAS_KNOWN_422_POST_COUNT");
      HarnessResult malformed422 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-malformed422-001", "malformed-422");
      assertGenerationUnknownWithoutSecondDraft(malformed422, "OUTCOME_CANVAS_MALFORMED_422");
      HarnessResult crossBound422 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-cross-bound422-001", "malformed-422-cross-run");
      assertGenerationUnknownWithoutSecondDraft(
          crossBound422, "OUTCOME_CANVAS_MALFORMED_422_CROSS_RUN");
      assertNoTrustedArtifact(crossBound422, "OUTCOME_CANVAS_MALFORMED_422_CROSS_RUN");
      HarnessResult schema422 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-schema422-001", "malformed-422-schema");
      assertGenerationUnknownWithoutSecondDraft(
          schema422, "OUTCOME_CANVAS_MALFORMED_422_SCHEMA");
      assertNoTrustedArtifact(schema422, "OUTCOME_CANVAS_MALFORMED_422_SCHEMA");

      HarnessResult pendingReset =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-pending-reset-001", "pending-reset");
      assertEquals(1, pendingReset.agentDraftPostCount(), "OUTCOME_CANVAS_PENDING_SECOND_DRAFT");
      assertEquals(
          1,
          pendingReset.pendingOldCompletionIsolated(),
          "OUTCOME_CANVAS_PENDING_STALE_COMPLETION_OVERWROTE_RESET");

      HarnessResult contentHashMismatch =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-hash-mismatch-001", "content-hash-mismatch");
      assertEquals(
          "GENERATION_UNKNOWN",
          contentHashMismatch.canvasState(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_NOT_UNKNOWN");
      assertEquals(
          1,
          contentHashMismatch.agentDraftPostCount(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_SECOND_DRAFT_CREATED");
      assertEquals(
          1,
          contentHashMismatch.uiCaptureGetCount(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_CAPTURE_GET_COUNT");
      assertEquals(
          1,
          contentHashMismatch.uiArtifactGetCount(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_ARTIFACT_GET_COUNT");
      assertEquals(
          1,
          contentHashMismatch.uiCaptureGetPathExact(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_CAPTURE_GET_PATH");
      assertEquals(
          1,
          contentHashMismatch.uiArtifactGetPathExact(),
          "OUTCOME_CANVAS_CONTENT_HASH_MISMATCH_ARTIFACT_GET_PATH");

      assertEquals(1, result.revisionSavePresent(), "OUTCOME_CANVAS_REVISION_SAVE_MISSING");
      assertEquals(1, result.revisionDirtyObserved(), "OUTCOME_CANVAS_REVISION_DIRTY_MISSING");
      assertEquals(1, result.revisionSaveDispatched(), "OUTCOME_CANVAS_REVISION_SAVE_NOT_DISPATCHED");
      assertEquals(2, result.revisionPutCount(), "OUTCOME_CANVAS_REVISION_PUT_COUNT_UNEXPECTED");
      assertEquals(1, result.revisionRequestExact(), "OUTCOME_CANVAS_REVISION_CAS_REQUEST_MISMATCH");
      assertEquals(200, result.revisionPutStatus(), "OUTCOME_CANVAS_REVISION_PUT_FAILED");
      assertEquals(1, result.revisionV2Strict(), "OUTCOME_CANVAS_REVISION_V2_LINEAGE_MISMATCH");
      assertEquals(1, result.artifactDurableV2(), "OUTCOME_CANVAS_REVISION_V2_NOT_DURABLE");
      assertEquals(1, result.revisionV3DirtyObserved(), "OUTCOME_CANVAS_REVISION_V3_DIRTY_MISSING");
      assertEquals(1, result.revisionV3PutCount(), "OUTCOME_CANVAS_REVISION_V3_PUT_COUNT");
      assertEquals(1, result.revisionV3RequestExact(), "OUTCOME_CANVAS_REVISION_V3_CAS_REQUEST");
      assertEquals(1, result.revisionV3Strict(), "OUTCOME_CANVAS_REVISION_V3_LINEAGE_MISMATCH");
      assertEquals(1, result.artifactDurableV3(), "OUTCOME_CANVAS_REVISION_V3_NOT_DURABLE");
      assertEquals(
          "REVISION_SAVED", result.revisionV3State(),
          "OUTCOME_CANVAS_REVISION_V3_NOT_SAVED");

      HarnessResult tamperedHistory =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-v3-history-001", "revision-v3-history-tamper");
      assertEquals(
          "REVISION_UNKNOWN", tamperedHistory.revisionV3State(),
          "OUTCOME_CANVAS_REVISION_V3_TAMPERED_HISTORY_NOT_UNKNOWN");
      assertEquals(1, tamperedHistory.revisionV3PutCount(), "OUTCOME_CANVAS_REVISION_V3_TAMPERED_PUT_COUNT");
      assertEquals(1, tamperedHistory.revisionV3RequestExact(), "OUTCOME_CANVAS_REVISION_V3_TAMPERED_CAS");
      assertEquals(0, tamperedHistory.revisionV3Strict(), "OUTCOME_CANVAS_REVISION_V3_TAMPERED_ACCEPTED");
      assertEquals(1, tamperedHistory.artifactDurableV3(), "OUTCOME_CANVAS_REVISION_V3_TAMPERED_BACKEND_WRITE");

      HarnessResult wrongMime201 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-wrong-mime-201-001", "wrong-mime-201");
      assertGenerationUnknownWithoutSecondDraft(wrongMime201, "OUTCOME_CANVAS_WRONG_MIME_201");
      HarnessResult wrongMime200 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-wrong-mime-200-001", "wrong-mime-200");
      assertEquals(
          "REVISION_UNKNOWN", wrongMime200.revisionState(),
          "OUTCOME_CANVAS_WRONG_MIME_200_NOT_UNKNOWN");

      assertEquals(1, known422.outcomeA11yExact(), "OUTCOME_CANVAS_FAILURE_A11Y_MISSING");
      assertEquals(1, malformed422.outcomeA11yExact(), "OUTCOME_CANVAS_UNKNOWN_A11Y_MISSING");

      HarnessResult doubleSave =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-double-001", "revision-double-save");
      assertRevisionSaved(doubleSave, 1, "OUTCOME_CANVAS_REVISION_DOUBLE_SAVE");

      HarnessResult lostRevision =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-lost-001", "revision-response-lost");
      assertRevisionSaved(lostRevision, 1, "OUTCOME_CANVAS_REVISION_RESPONSE_LOSS");
      assertEquals(
          1, lostRevision.revisionUiGetCount(),
          "OUTCOME_CANVAS_REVISION_RESPONSE_LOSS_READBACK_COUNT");

      HarnessResult competing409 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-conflict-001", "revision-409-competing");
      assertRevisionConflict(competing409, "OUTCOME_CANVAS_REVISION_409");
      assertEquals(409, competing409.revisionPutStatus(), "OUTCOME_CANVAS_REVISION_409_STATUS");
      assertEquals(1, competing409.outcomeA11yExact(), "OUTCOME_CANVAS_CONFLICT_A11Y_MISSING");

      HarnessResult retryAfter503 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-503-001", "revision-5xx-retry");
      assertRevisionSaved(retryAfter503, 2, "OUTCOME_CANVAS_REVISION_5XX_RETRY");
      assertEquals(1, retryAfter503.revisionExplicitRetry(), "OUTCOME_CANVAS_REVISION_5XX_AUTO_RETRY");
      assertEquals(1, retryAfter503.revisionUiGetCount(), "OUTCOME_CANVAS_REVISION_5XX_READBACK_COUNT");

      HarnessResult otherHead =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-other-head-001", "revision-other-head");
      assertRevisionConflict(otherHead, "OUTCOME_CANVAS_REVISION_OTHER_HEAD");

      HarnessResult readbackFailed =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-readback-001", "revision-get-fail");
      assertEquals(
          "REVISION_UNKNOWN", readbackFailed.revisionState(),
          "OUTCOME_CANVAS_REVISION_READBACK_FAILURE_NOT_UNKNOWN");
      assertEquals(1, readbackFailed.revisionPutCount(), "OUTCOME_CANVAS_REVISION_READBACK_SECOND_PUT");
      assertEquals(1, readbackFailed.revisionUiGetCount(), "OUTCOME_CANVAS_REVISION_READBACK_GET_COUNT");
      assertEquals(1, readbackFailed.revisionLocalPreserved(), "OUTCOME_CANVAS_REVISION_READBACK_LOST_LOCAL");
      assertEquals(1, readbackFailed.outcomeA11yExact(), "OUTCOME_CANVAS_REVISION_UNKNOWN_A11Y_MISSING");

      String restartArtifactPath = result.artifactPath();
      stop(application);
      application = startApplication();
      HttpResponse<String> restartedArtifact =
          sendJson(application.port(), "GET", restartArtifactPath, null);
      assertEquals(200, restartedArtifact.statusCode(), "OUTCOME_CANVAS_REVISION_RESTART_GET_FAILED");
      assertPrivateNoStore(restartedArtifact, "OUTCOME_CANVAS_REVISION_RESTART_CACHE_BOUNDARY");
      assertEquals(3, (Integer) JsonPath.read(restartedArtifact.body(), "$.currentVersion"));
      assertEquals(3, ((List<?>) JsonPath.read(restartedArtifact.body(), "$.versions")).size());

      HarnessResult invalid200 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-invalid-001", "revision-invalid-200");
      assertEquals(
          "REVISION_UNKNOWN", invalid200.revisionState(),
          "OUTCOME_CANVAS_REVISION_INVALID_200_NOT_UNKNOWN");
      assertEquals(1, invalid200.revisionPutCount(), "OUTCOME_CANVAS_REVISION_INVALID_200_SECOND_PUT");
      assertEquals(0, invalid200.revisionUiGetCount(), "OUTCOME_CANVAS_REVISION_INVALID_200_READBACK_COUNT");
      assertEquals(1, invalid200.revisionLocalPreserved(), "OUTCOME_CANVAS_REVISION_INVALID_200_LOST_LOCAL");

      HarnessResult oversize200 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-oversize-200-001", "revision-oversize-200");
      assertEquals(
          "REVISION_UNKNOWN", oversize200.revisionState(),
          "OUTCOME_CANVAS_REVISION_OVERSIZE_200_NOT_UNKNOWN");
      assertEquals(1, oversize200.revisionPutCount(), "OUTCOME_CANVAS_REVISION_OVERSIZE_200_SECOND_PUT");
      assertEquals(1, oversize200.revisionRequestExact(), "OUTCOME_CANVAS_REVISION_OVERSIZE_200_CAS");
      assertEquals(0, oversize200.revisionUiGetCount(), "OUTCOME_CANVAS_REVISION_OVERSIZE_200_READBACK_COUNT");
      assertEquals(1, oversize200.revisionLocalPreserved(), "OUTCOME_CANVAS_REVISION_OVERSIZE_200_LOST_LOCAL");

      HarnessResult interrupted200 =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptFile,
              "outcome-canvas-revision-interrupted-200-001", "revision-stream-interrupted-200");
      assertEquals(
          "REVISION_SAVED", interrupted200.revisionState(),
          "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_NOT_RECONCILED");
      assertEquals(1, interrupted200.revisionPutCount(), "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_SECOND_PUT");
      assertEquals(1, interrupted200.revisionRequestExact(), "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_CAS");
      assertEquals(1, interrupted200.revisionUiGetCount(), "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_READBACK_COUNT");
      assertEquals(1, interrupted200.revisionLocalPreserved(), "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_LOST_LOCAL");
      assertEquals(1, interrupted200.artifactDurableV2(), "OUTCOME_CANVAS_REVISION_STREAM_INTERRUPTED_200_NOT_DURABLE");
    } finally {
      Files.deleteIfExists(harnessFile);
      Files.deleteIfExists(scriptFile);
      Files.deleteIfExists(htmlFile);
      stop(application);
    }
  }

  private static Path copyHarnessToTempFile() throws IOException {
    try (InputStream source =
        QuickCaptureOutcomeCanvasHttpIT.class.getResourceAsStream(
            "/io/emergeos/api/quick-capture-outcome-canvas-harness.mjs")) {
      assertNotNull(source, "OUTCOME_CANVAS_NODE_HARNESS_MISSING");
      Path target = Files.createTempFile("emerge-outcome-canvas-harness-", ".mjs");
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    }
  }

  private static HarnessResult runHarness(
      Path harness,
      Path html,
      Path script,
      String baseUrl,
      String captureDigest,
      String nonce,
      String mode)
      throws Exception {
    Process process =
        new ProcessBuilder(
                "node",
                harness.toString(),
                html.toString(),
                script.toString(),
                baseUrl,
                captureDigest,
                nonce,
                mode)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, () -> "OUTCOME_CANVAS_NODE_HARNESS_TIMEOUT output=" + output);
    assertEquals(0, process.exitValue(), () -> "OUTCOME_CANVAS_NODE_HARNESS_FAILED output=" + output);
    return new HarnessResult(
        outputValue(output, "NODE_MAJOR"),
        outputValue(output, "SCRIPT_EXECUTED"),
        outputValue(output, "CAPTURE_SUBMIT_DISPATCHED"),
        outputValue(output, "CAPTURE_REPLAY_STATUS"),
        outputValue(output, "CAPTURE_ID_MATCH"),
        outputValue(output, "TRIGGER_PRESENT"),
        outputValue(output, "TRIGGER_DISPATCHED"),
        outputValue(output, "AGENT_DRAFT_POST_COUNT"),
        outputValue(output, "AGENT_DRAFT_STATUS"),
        outputValue(output, "AGENT_DRAFT_CACHE_NO_STORE"),
        outputValue(output, "AGENT_DRAFT_LOCATION_VALID"),
        outputValue(output, "AGENT_DRAFT_SUCCEEDED"),
        outputValue(output, "ARTIFACT_REFS_EXACT"),
        outputValue(output, "EVIDENCE_REFS_EXACT"),
        outputValue(output, "CAPTURE_GET_STATUS"),
        outputValue(output, "ARTIFACT_GET_STATUS"),
        outputValue(output, "ARTIFACT_V1_MATCH"),
        outputTextValue(output, "CANVAS_STATE"),
        outputValue(output, "CANVAS_V1_CONTENT_RENDERED"),
        outputValue(output, "CANVAS_FAKE_BOUNDARY_RENDERED"),
        outputValue(output, "UI_CAPTURE_GET_COUNT"),
        outputValue(output, "UI_ARTIFACT_GET_COUNT"),
        outputValue(output, "UI_CAPTURE_GET_PATH_EXACT"),
        outputValue(output, "UI_ARTIFACT_GET_PATH_EXACT"),
        outputValue(output, "PENDING_OLD_COMPLETION_ISOLATED"),
        outputValue(output, "REVISION_SAVE_PRESENT"),
        outputValue(output, "REVISION_DIRTY_OBSERVED"),
        outputValue(output, "REVISION_SAVE_DISPATCHED"),
        outputValue(output, "REVISION_PUT_COUNT"),
        outputValue(output, "REVISION_REQUEST_EXACT"),
        outputValue(output, "REVISION_REQUEST_STABLE"),
        outputValue(output, "REVISION_PUT_STATUS"),
        outputValue(output, "REVISION_V2_STRICT"),
        outputValue(output, "ARTIFACT_DURABLE_V2"),
        outputTextValue(output, "REVISION_STATE"),
        outputValue(output, "REVISION_UI_GET_COUNT"),
        outputValue(output, "REVISION_LOCAL_PRESERVED"),
        outputValue(output, "REVISION_REMOTE_SEPARATE"),
        outputValue(output, "REVISION_EXPLICIT_RETRY"),
        outputPathValue(output, "ARTIFACT_PATH"),
        outputValue(output, "REVISION_V3_DIRTY_OBSERVED"),
        outputValue(output, "REVISION_V3_PUT_COUNT"),
        outputValue(output, "REVISION_V3_REQUEST_EXACT"),
        outputValue(output, "REVISION_V3_STRICT"),
        outputTextValue(output, "REVISION_V3_STATE"),
        outputValue(output, "ARTIFACT_DURABLE_V3"),
        outputValue(output, "OUTCOME_A11Y_EXACT"));
  }

  private static HarnessResult runScenario(
      int port,
      Path harness,
      Path html,
      Path script,
      String nonce,
      String mode)
      throws Exception {
    String captureId = seedCapture(port, nonce);
    return runHarness(
        harness,
        html,
        script,
        "http://127.0.0.1:" + port,
        sha256(captureId),
        nonce,
        mode);
  }

  private static String seedCapture(int port, String nonce) throws Exception {
    HttpResponse<String> created =
        sendJson(
            port,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "%s",
              "content": "synthetic outcome canvas seed",
              "sourceType": "TEXT",
              "sourceRef": "quick-capture-ui:text",
              "dataClass": "PERSONAL"
            }
            """.formatted(nonce));
    assertEquals(201, created.statusCode(), "OUTCOME_CANVAS_SCENARIO_CAPTURE_CREATE_FAILED");
    return JsonPath.read(created.body(), "$.captureId");
  }

  private static void assertGenerationUnknownWithoutSecondDraft(
      HarnessResult result, String markerPrefix) {
    assertEquals("GENERATION_UNKNOWN", result.canvasState(), markerPrefix + "_NOT_UNKNOWN");
    assertEquals(1, result.agentDraftPostCount(), markerPrefix + "_SECOND_DRAFT_CREATED");
    assertEquals(0, result.uiCaptureGetCount(), markerPrefix + "_UNTRUSTED_CAPTURE_GET");
    assertEquals(0, result.uiArtifactGetCount(), markerPrefix + "_UNTRUSTED_ARTIFACT_GET");
  }

  private static void assertNoTrustedArtifact(HarnessResult result, String markerPrefix) {
    assertEquals(0, result.agentDraftLocationValid(), markerPrefix + "_TRUSTED_LOCATION");
    assertEquals(0, result.agentDraftSucceeded(), markerPrefix + "_TRUSTED_SUCCESS");
    assertEquals(0, result.artifactRefsExact(), markerPrefix + "_TRUSTED_ARTIFACT_REFS");
    assertEquals(0, result.evidenceRefsExact(), markerPrefix + "_TRUSTED_EVIDENCE_REFS");
  }

  private static void assertRevisionSaved(
      HarnessResult result, int expectedPutCount, String markerPrefix) {
    assertEquals("REVISION_SAVED", result.revisionState(), markerPrefix + "_NOT_SAVED");
    assertEquals(expectedPutCount, result.revisionPutCount(), markerPrefix + "_PUT_COUNT");
    assertEquals(1, result.revisionRequestExact(), markerPrefix + "_CAS_REQUEST");
    assertEquals(1, result.revisionRequestStable(), markerPrefix + "_CAS_CHANGED");
    assertEquals(1, result.artifactDurableV2(), markerPrefix + "_NOT_DURABLE_V2");
  }

  private static void assertRevisionConflict(HarnessResult result, String markerPrefix) {
    assertEquals("REVISION_CONFLICT", result.revisionState(), markerPrefix + "_NOT_CONFLICT");
    assertEquals(1, result.revisionPutCount(), markerPrefix + "_PUT_COUNT");
    assertEquals(1, result.revisionUiGetCount(), markerPrefix + "_READBACK_COUNT");
    assertEquals(1, result.revisionLocalPreserved(), markerPrefix + "_LOCAL_NOT_PRESERVED");
    assertEquals(1, result.revisionRemoteSeparate(), markerPrefix + "_REMOTE_NOT_SEPARATE");
  }

  private static int outputValue(String output, String key) {
    Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "=(\\d+)$").matcher(output);
    assertTrue(
        matcher.find(),
        () -> "OUTCOME_CANVAS_NODE_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return Integer.parseInt(matcher.group(1));
  }

  private static String outputTextValue(String output, String key) {
    Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "=([A-Z_]+)$").matcher(output);
    assertTrue(
        matcher.find(),
        () -> "OUTCOME_CANVAS_NODE_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return matcher.group(1);
  }

  private static String outputPathValue(String output, String key) {
    Matcher matcher =
        Pattern.compile(
                "(?m)^" + Pattern.quote(key)
                    + "=(MISSING|/api/v1/artifacts/[A-Za-z0-9][A-Za-z0-9._~-]{0,127})$")
            .matcher(output);
    assertTrue(
        matcher.find(),
        () -> "OUTCOME_CANVAS_NODE_HARNESS_OUTPUT_INVALID key=" + key + " output=" + output);
    return matcher.group(1);
  }

  private static String sha256(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    return java.util.HexFormat.of().formatHex(digest);
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-outcome-canvas-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=outcome-canvas-owner");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
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
          () -> "OUTCOME_CANVAS_PACKAGED_APP_EXITED log=" + readLog(application.log()));
      try {
        if (send(application.port(), "/actuator/health", "application/json").statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("OUTCOME_CANVAS_PACKAGED_APP_START_TIMEOUT");
  }

  private static HttpResponse<String> send(int port, String path, String accept)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", accept)
            .GET()
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> sendJson(int port, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void assertPrivateNoStore(HttpResponse<?> response, String marker) {
    assertEquals(
        "private, no-store",
        response.headers().firstValue("Cache-Control").orElse(""),
        marker);
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    if (application.process().isAlive()) {
      application.process().destroyForcibly();
      assertTrue(
          application.process().waitFor(10, TimeUnit.SECONDS),
          "OUTCOME_CANVAS_PACKAGED_APP_DID_NOT_STOP");
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
      int captureSubmitDispatched,
      int captureReplayStatus,
      int captureIdMatch,
      int triggerPresent,
      int triggerDispatched,
      int agentDraftPostCount,
      int agentDraftStatus,
      int agentDraftCacheNoStore,
      int agentDraftLocationValid,
      int agentDraftSucceeded,
      int artifactRefsExact,
      int evidenceRefsExact,
      int captureGetStatus,
      int artifactGetStatus,
      int artifactV1Match,
      String canvasState,
      int canvasV1ContentRendered,
      int canvasFakeBoundaryRendered,
      int uiCaptureGetCount,
      int uiArtifactGetCount,
      int uiCaptureGetPathExact,
      int uiArtifactGetPathExact,
      int pendingOldCompletionIsolated,
      int revisionSavePresent,
      int revisionDirtyObserved,
      int revisionSaveDispatched,
      int revisionPutCount,
      int revisionRequestExact,
      int revisionRequestStable,
      int revisionPutStatus,
      int revisionV2Strict,
      int artifactDurableV2,
      String revisionState,
      int revisionUiGetCount,
      int revisionLocalPreserved,
      int revisionRemoteSeparate,
      int revisionExplicitRetry,
      String artifactPath,
      int revisionV3DirtyObserved,
      int revisionV3PutCount,
      int revisionV3RequestExact,
      int revisionV3Strict,
      String revisionV3State,
      int artifactDurableV3,
      int outcomeA11yExact) {}
}
