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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

/** Packaged acceptance for exact, non-executing approval from a trusted Outcome Canvas. */
@Testcontainers
class ExactLocalApprovalUiHttpIT {

  private static final String OWNER = "exact-approval-ui-owner";
  private static final String UNREACHABLE_PROVIDER = "http://127.0.0.1:1";
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
  void exactApprovalCardPlansOnlyTheTrustedCurrentArtifact() throws Exception {
    RunningApplication application = startApplication();
    Path htmlFile = null;
    Path scriptManifestFile = null;
    List<Path> scriptFiles = new ArrayList<>();
    Path harnessFile = null;
    try {
      HttpResponse<String> page = send(application.port(), "/capture", "text/html");
      assertEquals(200, page.statusCode(), "EXACT_LOCAL_APPROVAL_UI_PAGE_UNAVAILABLE");
      assertPrivateNoStore(page, "EXACT_LOCAL_APPROVAL_UI_PAGE_CACHE_BOUNDARY_MISSING");
      Matcher source = SCRIPT_SOURCE.matcher(page.body());
      htmlFile = Files.createTempFile("emerge-exact-approval-ui-page-", ".html");
      Files.writeString(htmlFile, page.body(), StandardCharsets.UTF_8);
      List<String> sameOriginSources = new ArrayList<>();
      while (source.find()) {
        String scriptSource = source.group(2);
        if (!scriptSource.startsWith("/") || scriptSource.startsWith("//")) {
          continue;
        }
        HttpResponse<String> script = send(application.port(), scriptSource, "text/javascript");
        assertEquals(200, script.statusCode(), "EXACT_LOCAL_APPROVAL_UI_SERVED_SCRIPT_MISSING");
        assertPrivateNoStore(script, "EXACT_LOCAL_APPROVAL_UI_SCRIPT_CACHE_BOUNDARY_MISSING");
        Path scriptFile = Files.createTempFile("emerge-exact-approval-ui-served-", ".js");
        Files.writeString(scriptFile, script.body(), StandardCharsets.UTF_8);
        sameOriginSources.add(scriptSource);
        scriptFiles.add(scriptFile);
      }
      assertTrue(
          !sameOriginSources.isEmpty(),
          "EXACT_LOCAL_APPROVAL_UI_SERVED_SCRIPT_REFERENCE_MISSING");
      assertTrue(
          sameOriginSources.contains("/capture/quick-capture.js")
              && sameOriginSources.contains("/capture/approval-card.js"),
          "EXACT_LOCAL_APPROVAL_UI_SERVED_SCRIPT_SET_INCOMPLETE");
      scriptManifestFile = Files.createTempFile("emerge-exact-approval-ui-scripts-", ".txt");
      Files.write(
          scriptManifestFile,
          scriptFiles.stream().map(Path::toString).toList(),
          StandardCharsets.UTF_8);
      harnessFile = copyHarnessToTempFile();

      Scenario happy =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "created", "001");
      assertTrustedOutcomePrerequisite(happy);
      assertEquals(1, happy.approvalTriggerPresent(), "EXACT_LOCAL_APPROVAL_UI_TRIGGER_MISSING");
      assertApprovalCard(happy);
      assertEquals(1, happy.approvalClickDispatched(), "EXACT_LOCAL_APPROVAL_UI_CLICK_NOT_DISPATCHED");
      assertEquals(1, happy.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_POST_COUNT_UNEXPECTED");
      assertEquals(1, happy.approvalRequestExact(), "EXACT_LOCAL_APPROVAL_UI_REQUEST_MISMATCH");
      assertEquals(1, happy.approvalFetchOptionsExact(), "EXACT_LOCAL_APPROVAL_UI_FETCH_OPTIONS_UNSAFE");
      assertEquals(1, happy.approvalNonceOnClick(), "EXACT_LOCAL_APPROVAL_UI_NONCE_NOT_CREATED_ON_CLICK");
      assertEquals(201, happy.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_CREATED_STATUS_MISMATCH");
      assertEquals(1, happy.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_CREATED_RECEIPT_INVALID");
      assertEquals(1, happy.approvalPlannedRendered(), "EXACT_LOCAL_APPROVAL_UI_PLANNED_NOT_RENDERED");
      assertNoExecutionSurface(happy);
      assertPlannedOnly(happy.approvalNonce(), 1);

      Scenario replay =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "replay", "002");
      assertTrustedOutcomePrerequisite(replay);
      assertApprovalCard(replay);
      assertEquals(1, replay.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_REPLAY_POST_COUNT");
      assertEquals(200, replay.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_REPLAY_STATUS_MISMATCH");
      assertEquals(1, replay.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_REPLAY_RECEIPT_INVALID");
      assertEquals(1, replay.approvalPlannedRendered(), "EXACT_LOCAL_APPROVAL_UI_REPLAY_NOT_RENDERED");
      assertNoExecutionSurface(replay);
      assertPlannedOnly(replay.approvalNonce(), 1);

      Scenario doubleClick =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "double-click", "003");
      assertTrustedOutcomePrerequisite(doubleClick);
      assertApprovalCard(doubleClick);
      assertEquals(1, doubleClick.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_DOUBLE_CLICK_POSTED_TWICE");
      assertEquals(1, doubleClick.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_DOUBLE_CLICK_RECEIPT_INVALID");
      assertNoExecutionSurface(doubleClick);
      assertPlannedOnly(doubleClick.approvalNonce(), 1);

      Scenario responseLoss =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "response-loss", "004");
      assertTrustedOutcomePrerequisite(responseLoss);
      assertApprovalCard(responseLoss);
      assertEquals(2, responseLoss.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_LOSS_RETRY_COUNT");
      assertEquals(1, responseLoss.approvalRequestStable(), "EXACT_LOCAL_APPROVAL_UI_LOSS_CHANGED_REQUEST");
      assertEquals(1, responseLoss.approvalFetchOptionsExact(), "EXACT_LOCAL_APPROVAL_UI_LOSS_FETCH_OPTIONS_UNSAFE");
      assertEquals(1, responseLoss.approvalPostCountBeforeExplicitRetry(), "EXACT_LOCAL_APPROVAL_UI_LOSS_AUTO_RETRIED");
      assertEquals(1, responseLoss.explicitSameNonceRetry(), "EXACT_LOCAL_APPROVAL_UI_LOSS_RETRY_NOT_EXPLICIT");
      assertEquals(200, responseLoss.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_LOSS_REPLAY_STATUS");
      assertEquals(1, responseLoss.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_LOSS_REPLAY_INVALID");
      assertEquals(1, responseLoss.approvalPlannedRendered(), "EXACT_LOCAL_APPROVAL_UI_LOSS_NOT_RECOVERED");
      assertNoExecutionSurface(responseLoss);
      assertPlannedOnly(responseLoss.approvalNonce(), 1);

      Scenario edited =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "artifact-edit", "005");
      assertTrustedOutcomePrerequisite(edited, "DIRTY");
      assertApprovalCard(edited);
      assertEquals(1, edited.approvalInvalidated(), "EXACT_LOCAL_APPROVAL_UI_EDIT_DID_NOT_INVALIDATE_CARD");
      assertEquals(1, edited.outcomeFocusRetained(), "EXACT_LOCAL_APPROVAL_UI_EDIT_STOLE_FOCUS");
      assertEquals(1, edited.outcomeContinuousInputExact(), "EXACT_LOCAL_APPROVAL_UI_CONTINUOUS_INPUT_TRUNCATED");
      assertEquals(1, edited.approvalInvalidationNonAssertive(), "EXACT_LOCAL_APPROVAL_UI_INVALIDATION_ASSERTIVE");
      assertEquals(0, edited.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_EDIT_POSTED_OLD_APPROVAL");
      assertEquals(0, edited.approvalNonceOnClick(), "EXACT_LOCAL_APPROVAL_UI_EDIT_CREATED_APPROVAL_NONCE");
      assertNoExecutionSurface(edited);
      assertPlannedOnly(edited.approvalNonce(), 0);

      Scenario stale =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "stale-409", "006");
      assertTrustedOutcomePrerequisite(stale);
      assertApprovalCard(stale);
      assertEquals(1, stale.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_STALE_POST_COUNT");
      assertEquals(412, stale.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_STALE_NOT_REJECTED");
      assertEquals(1, stale.actualProblemMimeExact(), "EXACT_LOCAL_APPROVAL_UI_STALE_PROBLEM_MIME_DRIFT");
      assertEquals(1, stale.actualProblemNoStore(), "EXACT_LOCAL_APPROVAL_UI_STALE_PROBLEM_CACHE_UNSAFE");
      assertEquals(1, stale.actualProblemTypeExact(), "EXACT_LOCAL_APPROVAL_UI_STALE_PROBLEM_TYPE_DRIFT");
      assertEquals(1, stale.approvalStaleRendered(), "EXACT_LOCAL_APPROVAL_UI_TYPED_412_NOT_STALE");
      assertEquals(0, stale.approvalUnknownRendered(), "EXACT_LOCAL_APPROVAL_UI_TYPED_412_RENDERED_UNKNOWN");
      assertNoExecutionSurface(stale);
      assertPlannedOnly(stale.approvalNonce(), 0);

      Scenario malformed =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "malformed-201", "007");
      assertTrustedOutcomePrerequisite(malformed);
      assertApprovalCard(malformed);
      assertEquals(1, malformed.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_MALFORMED_POST_COUNT");
      assertEquals(0, malformed.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_MALFORMED_TRUSTED");
      assertEquals(1, malformed.approvalUnknownRendered(), "EXACT_LOCAL_APPROVAL_UI_MALFORMED_NOT_UNKNOWN");
      assertEquals(0, malformed.approvalPlannedRendered(), "EXACT_LOCAL_APPROVAL_UI_MALFORMED_CLAIMED_APPROVAL");
      assertNoExecutionSurface(malformed);
      assertPlannedOnly(malformed.approvalNonce(), 0);

      Scenario hanging =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "hanging-fetch", "008");
      assertTrustedOutcomePrerequisite(hanging);
      assertApprovalCard(hanging);
      assertEquals(1, hanging.hangingSignalAborted(), "EXACT_LOCAL_APPROVAL_UI_HANG_SIGNAL_NOT_ABORTED");
      assertEquals(1, hanging.approvalPostCountBeforeExplicitRetry(), "EXACT_LOCAL_APPROVAL_UI_HANG_AUTO_RETRIED");
      assertEquals(2, hanging.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_HANG_RETRY_COUNT");
      assertEquals(1, hanging.approvalRequestStable(), "EXACT_LOCAL_APPROVAL_UI_HANG_CHANGED_REQUEST");
      assertEquals(1, hanging.approvalFetchOptionsExact(), "EXACT_LOCAL_APPROVAL_UI_HANG_FETCH_OPTIONS_UNSAFE");
      assertEquals(1, hanging.explicitSameNonceRetry(), "EXACT_LOCAL_APPROVAL_UI_HANG_RETRY_NOT_EXPLICIT");
      assertEquals(201, hanging.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_HANG_RETRY_STATUS");
      assertEquals(1, hanging.approvalResponseStrict(), "EXACT_LOCAL_APPROVAL_UI_HANG_RETRY_INVALID");
      assertEquals(1, hanging.approvalPlannedRendered(), "EXACT_LOCAL_APPROVAL_UI_HANG_NOT_RECOVERED");
      assertNoExecutionSurface(hanging);
      assertPlannedOnly(hanging.approvalNonce(), 1);

      Scenario missingAbortController =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "missing-abort-controller", "009");
      assertTrustedOutcomePrerequisite(missingAbortController);
      assertApprovalCard(missingAbortController);
      assertEquals(1, missingAbortController.approvalClickDispatched(), "EXACT_LOCAL_APPROVAL_UI_NO_ABORT_CLICK_FAILED");
      assertEquals(0, missingAbortController.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_NO_ABORT_POSTED");
      assertEquals(1, missingAbortController.approvalUnknownRendered(), "EXACT_LOCAL_APPROVAL_UI_NO_ABORT_NOT_UNKNOWN");
      assertNoExecutionSurface(missingAbortController);
      assertPlannedOnly(missingAbortController.approvalNonce(), 0);

      Scenario nonceConflict =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "nonce-conflict-409", "010");
      assertTrustedOutcomePrerequisite(nonceConflict);
      assertApprovalCard(nonceConflict);
      assertEquals(1, nonceConflict.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_NONCE_CONFLICT_POST_COUNT");
      assertEquals(409, nonceConflict.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_NONCE_CONFLICT_STATUS");
      assertEquals(1, nonceConflict.actualProblemMimeExact(), "EXACT_LOCAL_APPROVAL_UI_NONCE_PROBLEM_MIME_DRIFT");
      assertEquals(1, nonceConflict.actualProblemNoStore(), "EXACT_LOCAL_APPROVAL_UI_NONCE_PROBLEM_CACHE_UNSAFE");
      assertEquals(1, nonceConflict.actualProblemTypeExact(), "EXACT_LOCAL_APPROVAL_UI_NONCE_PROBLEM_TYPE_DRIFT");
      assertEquals(0, nonceConflict.approvalStaleRendered(), "EXACT_LOCAL_APPROVAL_UI_NONCE_CONFLICT_CLAIMED_STALE");
      assertEquals(1, nonceConflict.approvalUnknownRendered(), "EXACT_LOCAL_APPROVAL_UI_NONCE_CONFLICT_NOT_UNKNOWN");
      assertNoExecutionSurface(nonceConflict);
      assertPlannedOnly(nonceConflict.approvalNonce(), 1);

      Scenario strictStale =
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "strict-stale-412", "011");
      assertTrustedOutcomePrerequisite(strictStale);
      assertApprovalCard(strictStale);
      assertEquals(1, strictStale.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_STRICT_STALE_POST_COUNT");
      assertEquals(412, strictStale.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_STRICT_STALE_STATUS");
      assertEquals(1, strictStale.approvalStaleRendered(), "EXACT_LOCAL_APPROVAL_UI_STRICT_STALE_NOT_RENDERED");
      assertEquals(0, strictStale.approvalUnknownRendered(), "EXACT_LOCAL_APPROVAL_UI_STRICT_STALE_RENDERED_UNKNOWN");
      assertNoExecutionSurface(strictStale);
      assertPlannedOnly(strictStale.approvalNonce(), 0);

      assertUntrustedProblemUnknown(
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "problem-wrong-mime", "012"),
          "MIME");
      assertUntrustedProblemUnknown(
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "problem-missing-no-store", "013"),
          "CACHE");
      assertUntrustedProblemUnknown(
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "problem-oversize", "014"),
          "OVERSIZE");
      assertUntrustedProblemUnknown(
          runScenario(
              application.port(), harnessFile, htmlFile, scriptManifestFile, "problem-wrong-type", "015"),
          "TYPE");
    } finally {
      Files.deleteIfExists(harnessFile);
      Files.deleteIfExists(scriptManifestFile);
      for (Path scriptFile : scriptFiles) {
        Files.deleteIfExists(scriptFile);
      }
      Files.deleteIfExists(htmlFile);
      stop(application);
    }
  }

  private static void assertTrustedOutcomePrerequisite(Scenario result) {
    assertTrustedOutcomePrerequisite(result, "CANVAS_READY");
  }

  private static void assertTrustedOutcomePrerequisite(
      Scenario result, String expectedCanvasState) {
    assertEquals(22, result.nodeMajor(), "EXACT_LOCAL_APPROVAL_UI_NODE_22_REQUIRED");
    assertEquals(1, result.scriptExecuted(), "EXACT_LOCAL_APPROVAL_UI_SERVED_SCRIPT_NOT_EXECUTED");
    assertEquals(200, result.captureReplayStatus(), "EXACT_LOCAL_APPROVAL_UI_CAPTURE_REPLAY_FAILED");
    assertEquals(1, result.captureIdMatch(), "EXACT_LOCAL_APPROVAL_UI_CAPTURE_ID_MISMATCH");
    assertEquals(1, result.outcomeTriggerDispatched(), "EXACT_LOCAL_APPROVAL_UI_OUTCOME_NOT_TRIGGERED");
    assertEquals(expectedCanvasState, result.canvasState(), "EXACT_LOCAL_APPROVAL_UI_CANVAS_STATE_UNEXPECTED");
    assertEquals(1, result.artifactCurrentExact(), "EXACT_LOCAL_APPROVAL_UI_ARTIFACT_HEAD_UNTRUSTED");
  }

  private static void assertApprovalCard(Scenario result) {
    assertEquals(1, result.approvalTriggerPresent(), "EXACT_LOCAL_APPROVAL_UI_TRIGGER_MISSING");
    assertEquals(1, result.cardStructureExact(), "EXACT_LOCAL_APPROVAL_UI_CARD_STRUCTURE_MISMATCH");
    assertEquals(1, result.cardTargetRiskExact(), "EXACT_LOCAL_APPROVAL_UI_TARGET_RISK_MISSING");
    assertEquals(1, result.cardArtifactExact(), "EXACT_LOCAL_APPROVAL_UI_ARTIFACT_BINDING_MISSING");
    assertEquals(1, result.cardBoundaryExact(), "EXACT_LOCAL_APPROVAL_UI_BOUNDARY_COPY_MISSING");
    assertEquals(1, result.cardScopeExact(), "EXACT_LOCAL_APPROVAL_UI_SCOPE_BINDING_MISSING");
    assertEquals(
        1,
        result.cardSensitiveRefsHidden(),
        "EXACT_LOCAL_APPROVAL_UI_SENSITIVE_REFERENCE_EXPOSED");
  }

  private static void assertUntrustedProblemUnknown(Scenario result, String variant)
      throws Exception {
    assertTrustedOutcomePrerequisite(result);
    assertApprovalCard(result);
    assertEquals(1, result.approvalPostCount(), "EXACT_LOCAL_APPROVAL_UI_" + variant + "_POST_COUNT");
    assertEquals(412, result.approvalStatus(), "EXACT_LOCAL_APPROVAL_UI_" + variant + "_STATUS");
    assertEquals(
        1,
        result.approvalFetchOptionsExact(),
        "EXACT_LOCAL_APPROVAL_UI_" + variant + "_FETCH_OPTIONS_UNSAFE");
    assertEquals(
        0,
        result.approvalStaleRendered(),
        "EXACT_LOCAL_APPROVAL_UI_" + variant + "_CLAIMED_STALE");
    assertEquals(
        1,
        result.approvalUnknownRendered(),
        "EXACT_LOCAL_APPROVAL_UI_" + variant + "_NOT_UNKNOWN");
    assertNoExecutionSurface(result);
    assertPlannedOnly(result.approvalNonce(), 0);
  }

  private static void assertNoExecutionSurface(Scenario result) {
    assertEquals(0, result.forbiddenActionCalls(), "EXACT_LOCAL_APPROVAL_UI_EXECUTION_ENDPOINT_CALLED");
    assertEquals(0, result.reconcileCalls(), "EXACT_LOCAL_APPROVAL_UI_RECONCILE_CALLED");
    assertEquals(0, result.completionClaimRendered(), "EXACT_LOCAL_APPROVAL_UI_FALSE_COMPLETION_RENDERED");
  }

  private static Path copyHarnessToTempFile() throws IOException {
    try (InputStream source =
        ExactLocalApprovalUiHttpIT.class.getResourceAsStream(
            "/io/emergeos/api/exact-local-approval-ui-harness.mjs")) {
      assertNotNull(source, "EXACT_LOCAL_APPROVAL_UI_NODE_HARNESS_MISSING");
      Path target = Files.createTempFile("emerge-exact-approval-ui-harness-", ".mjs");
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    }
  }

  private static Scenario runScenario(
      int port, Path harness, Path html, Path script, String mode, String suffix) throws Exception {
    String captureNonce = "11111111-1111-4111-8111-000000000" + suffix;
    String approvalNonce = "22222222-2222-4222-8222-000000000" + suffix;
    String content = "synthetic exact approval UI source " + suffix;
    String captureId = seedCapture(port, captureNonce, content);
    Process process =
        new ProcessBuilder(
                "node",
                harness.toString(),
                html.toString(),
                script.toString(),
                "http://127.0.0.1:" + port,
                captureId,
                captureNonce,
                approvalNonce,
                content,
                mode)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, () -> "EXACT_LOCAL_APPROVAL_UI_NODE_TIMEOUT output=" + output);
    assertEquals(0, process.exitValue(), () -> "EXACT_LOCAL_APPROVAL_UI_NODE_FAILED output=" + output);
    return Scenario.from(output, approvalNonce);
  }

  private static String seedCapture(int port, String nonce, String content) throws Exception {
    HttpResponse<String> created =
        sendJson(
            port,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "%s",
              "content": "%s",
              "sourceType": "TEXT",
              "sourceRef": "quick-capture-ui:text",
              "dataClass": "PERSONAL"
            }
            """.formatted(nonce, content));
    assertEquals(201, created.statusCode(), "EXACT_LOCAL_APPROVAL_UI_SEED_CAPTURE_FAILED");
    return JsonPath.read(created.body(), "$.captureId");
  }

  private static void assertPlannedOnly(String nonce, int expectedRows) throws Exception {
    assertEquals(
        expectedRows,
        queryInt("SELECT count(*) FROM action_attempts WHERE principal_id = ? AND idempotency_key = ?", OWNER, nonce));
    if (expectedRows == 0) {
      return;
    }
    assertEquals(
        expectedRows,
        queryInt(
            "SELECT count(*) FROM action_attempts WHERE principal_id = ? AND idempotency_key = ? "
                + "AND status = 'PLANNED' AND capability_used_calls = 0",
            OWNER,
            nonce));
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempt_transitions t JOIN action_attempts a "
                + "ON a.principal_id=t.principal_id AND a.attempt_id=t.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=? AND t.sequence=1 "
                + "AND t.from_status IS NULL AND t.to_status='PLANNED' AND t.capability_use_delta=0",
            OWNER,
            nonce));
    assertEquals(
        0,
        queryInt(
            "SELECT count(*) FROM action_receipts r JOIN action_attempts a "
                + "ON a.principal_id=r.principal_id AND a.attempt_id=r.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=?",
            OWNER,
            nonce));
  }

  private static int queryInt(String sql, String... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-exact-approval-ui-app-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + OWNER);
    command.add("--emerge.simulated-provider.base-url=" + UNREACHABLE_PROVIDER);
    command.add("--emerge.simulated-provider.request-timeout=PT0.2S");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitApplicationLive(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stop(application);
      throw failure;
    }
  }

  private static void awaitApplicationLive(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError("packaged app exited:\n" + Files.readString(application.log()));
      }
      try {
        if (send(application.port(), "/actuator/health/liveness", "application/json").statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // Loopback listener is still starting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError("packaged app did not become live:\n" + Files.readString(application.log()));
  }

  private static HttpResponse<String> send(int port, String path, String accept)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", accept)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> sendJson(int port, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
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
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null), marker);
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    try {
      if (application.process().isAlive()) {
        application.process().destroyForcibly();
        assertTrue(application.process().waitFor(10, TimeUnit.SECONDS));
      }
    } finally {
      Files.deleteIfExists(application.log());
    }
  }

  private static int outputInt(String output, String key) {
    return Integer.parseInt(outputText(output, key));
  }

  private static String outputText(String output, String key) {
    Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(.*)$").matcher(output);
    assertTrue(matcher.find(), () -> "missing " + key + " in harness output:\n" + output);
    return matcher.group(1).trim();
  }

  private record RunningApplication(Process process, int port, Path log) {}

  private record Scenario(
      int nodeMajor,
      int scriptExecuted,
      int captureReplayStatus,
      int captureIdMatch,
      int outcomeTriggerDispatched,
      String canvasState,
      int artifactCurrentExact,
      int approvalTriggerPresent,
      int cardStructureExact,
      int cardTargetRiskExact,
      int cardArtifactExact,
      int cardBoundaryExact,
      int cardScopeExact,
      int cardSensitiveRefsHidden,
      int approvalClickDispatched,
      int approvalPostCount,
      int approvalRequestExact,
      int approvalRequestStable,
      int approvalFetchOptionsExact,
      int approvalNonceOnClick,
      int approvalStatus,
      int approvalResponseStrict,
      int approvalPlannedRendered,
      int explicitSameNonceRetry,
      int approvalPostCountBeforeExplicitRetry,
      int hangingSignalAborted,
      int approvalInvalidated,
      int outcomeFocusRetained,
      int outcomeContinuousInputExact,
      int approvalInvalidationNonAssertive,
      int approvalStaleRendered,
      int approvalUnknownRendered,
      int actualProblemMimeExact,
      int actualProblemNoStore,
      int actualProblemTypeExact,
      int forbiddenActionCalls,
      int reconcileCalls,
      int completionClaimRendered,
      String approvalNonce) {

    static Scenario from(String output, String approvalNonce) {
      return new Scenario(
          outputInt(output, "NODE_MAJOR"),
          outputInt(output, "SCRIPT_EXECUTED"),
          outputInt(output, "CAPTURE_REPLAY_STATUS"),
          outputInt(output, "CAPTURE_ID_MATCH"),
          outputInt(output, "OUTCOME_TRIGGER_DISPATCHED"),
          outputText(output, "CANVAS_STATE"),
          outputInt(output, "ARTIFACT_CURRENT_EXACT"),
          outputInt(output, "APPROVAL_TRIGGER_PRESENT"),
          outputInt(output, "CARD_STRUCTURE_EXACT"),
          outputInt(output, "CARD_TARGET_RISK_EXACT"),
          outputInt(output, "CARD_ARTIFACT_EXACT"),
          outputInt(output, "CARD_BOUNDARY_EXACT"),
          outputInt(output, "CARD_SCOPE_EXACT"),
          outputInt(output, "CARD_SENSITIVE_REFS_HIDDEN"),
          outputInt(output, "APPROVAL_CLICK_DISPATCHED"),
          outputInt(output, "APPROVAL_POST_COUNT"),
          outputInt(output, "APPROVAL_REQUEST_EXACT"),
          outputInt(output, "APPROVAL_REQUEST_STABLE"),
          outputInt(output, "APPROVAL_FETCH_OPTIONS_EXACT"),
          outputInt(output, "APPROVAL_NONCE_ON_CLICK"),
          outputInt(output, "APPROVAL_STATUS"),
          outputInt(output, "APPROVAL_RESPONSE_STRICT"),
          outputInt(output, "APPROVAL_PLANNED_RENDERED"),
          outputInt(output, "EXPLICIT_SAME_NONCE_RETRY"),
          outputInt(output, "APPROVAL_POST_COUNT_BEFORE_EXPLICIT_RETRY"),
          outputInt(output, "HANGING_SIGNAL_ABORTED"),
          outputInt(output, "APPROVAL_INVALIDATED"),
          outputInt(output, "OUTCOME_FOCUS_RETAINED"),
          outputInt(output, "OUTCOME_CONTINUOUS_INPUT_EXACT"),
          outputInt(output, "APPROVAL_INVALIDATION_NON_ASSERTIVE"),
          outputInt(output, "APPROVAL_STALE_RENDERED"),
          outputInt(output, "APPROVAL_UNKNOWN_RENDERED"),
          outputInt(output, "ACTUAL_PROBLEM_MIME_EXACT"),
          outputInt(output, "ACTUAL_PROBLEM_NO_STORE"),
          outputInt(output, "ACTUAL_PROBLEM_TYPE_EXACT"),
          outputInt(output, "FORBIDDEN_ACTION_CALLS"),
          outputInt(output, "RECONCILE_CALLS"),
          outputInt(output, "COMPLETION_CLAIM_RENDERED"),
          approvalNonce);
    }
  }
}
