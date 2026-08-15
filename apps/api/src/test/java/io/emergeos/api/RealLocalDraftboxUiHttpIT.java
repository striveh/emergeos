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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Outside-in packaged UI Acceptance Red for the explicit second local-draftbox gesture. */
@Testcontainers
class RealLocalDraftboxUiHttpIT {

  private static final String OWNER = "real-local-draftbox-ui-owner";
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
  void exactApprovalRequiresASecondGestureAndRendersTheRealLocalReceipt() throws Exception {
    RunningApplication application = startApplication();
    Path tempDirectory = Files.createTempDirectory("emerge-real-local-draftbox-ui-");
    try {
      HttpResponse<String> page = get(application.port(), "/capture", "text/html");
      assertEquals(200, page.statusCode(), "REAL_LOCAL_DRAFTBOX_UI_PAGE_UNAVAILABLE");
      assertPrivateNoStore(page, "REAL_LOCAL_DRAFTBOX_UI_PAGE_CACHE_BOUNDARY_MISSING");

      Path html = tempDirectory.resolve("capture.html");
      Files.writeString(html, page.body(), StandardCharsets.UTF_8);
      List<String> servedScriptFiles = new ArrayList<>();
      Matcher sources = SCRIPT_SOURCE.matcher(page.body());
      int scriptIndex = 0;
      while (sources.find()) {
        String source = sources.group(2);
        if (!source.startsWith("/") || source.startsWith("//")) {
          continue;
        }
        HttpResponse<String> script = get(application.port(), source, "text/javascript");
        assertEquals(200, script.statusCode(), "REAL_LOCAL_DRAFTBOX_UI_SCRIPT_UNAVAILABLE");
        assertPrivateNoStore(script, "REAL_LOCAL_DRAFTBOX_UI_SCRIPT_CACHE_BOUNDARY_MISSING");
        Path scriptFile = tempDirectory.resolve("served-" + (++scriptIndex) + ".js");
        Files.writeString(scriptFile, script.body(), StandardCharsets.UTF_8);
        servedScriptFiles.add(scriptFile.toString());
      }
      assertTrue(
          servedScriptFiles.size() >= 2,
          "REAL_LOCAL_DRAFTBOX_UI_SERVED_SCRIPT_SET_INCOMPLETE");
      Path manifest = tempDirectory.resolve("scripts.txt");
      Files.write(manifest, servedScriptFiles, StandardCharsets.UTF_8);
      copyResource(
          "/io/emergeos/api/exact-local-approval-ui-harness.mjs",
          tempDirectory.resolve("exact-local-approval-ui-harness.mjs"));
      Path harness = tempDirectory.resolve("real-local-draftbox-ui-harness.mjs");
      copyResource("/io/emergeos/api/real-local-draftbox-ui-harness.mjs", harness);

      Scenario happy =
          runScenario(application.port(), harness, html, manifest, "real-created", "101");
      int executePosts = happy.number("EXECUTE_POST_COUNT");
      if (executePosts != 1) {
        System.out.printf(
            "REAL_LOCAL_DRAFTBOX_UI_EXECUTE_POST_MISSING expected=1 actual=%d%n",
            executePosts);
      }
      assertEquals(
          1,
          executePosts,
          () -> "REAL_LOCAL_DRAFTBOX_UI_EXECUTE_POST_MISSING expected=1 actual="
              + executePosts
              + "\n"
              + happy.output());
      assertSuccessfulSecondGesture(happy);
      assertEquals(1, happy.number("REPLAY_CANONICAL_EXACT"));
      assertEquals(1, happy.number("GET_CANONICAL_EXACT"));
      assertExecutedRows(happy.approvalNonce());

      Scenario doubleClick =
          runScenario(application.port(), harness, html, manifest, "real-double-click", "102");
      assertSuccessfulSecondGesture(doubleClick);
      assertEquals(1, doubleClick.number("EXECUTE_POST_COUNT"));
      assertExecutedRows(doubleClick.approvalNonce());

      Scenario responseLoss =
          runScenario(application.port(), harness, html, manifest, "real-response-loss", "103");
      assertExplicitGetRecovery(responseLoss, false);
      assertExecutedRows(responseLoss.approvalNonce());

      Scenario hang =
          runScenario(application.port(), harness, html, manifest, "real-hang", "104");
      assertExplicitGetRecovery(hang, true);
      assertExecutedRows(hang.approvalNonce());

      Scenario edited =
          runScenario(application.port(), harness, html, manifest, "real-planned-edit", "105");
      assertExactUiPlan(edited, "DIRTY");
      assertEquals(1, edited.number("EXECUTE_GESTURE_PRESENT"));
      assertEquals(1, edited.number("EXECUTE_INVALIDATED_AFTER_EDIT"));
      assertEquals(0, edited.number("EXECUTE_POST_COUNT"));
      assertEquals(1, edited.number("QUIET_AFTER_UNKNOWN"));
      assertEquals("INVALIDATED", edited.text("APPROVAL_FINAL_STATE"));
      assertPlannedRows(edited.approvalNonce());

      Scenario malformed =
          runScenario(application.port(), harness, html, manifest, "real-malformed", "106");
      assertFailClosedExecution(malformed, "UNKNOWN", 201);
      assertEquals(1, malformed.number("EXECUTE_MALFORMED_INJECTED"));
      assertPlannedRows(malformed.approvalNonce());

      Scenario stale =
          runScenario(application.port(), harness, html, manifest, "real-stale", "107");
      assertFailClosedExecution(stale, "STALE", 412);
      assertEquals(1, stale.number("EXECUTE_TYPED_STALE_PROBLEM"));
      assertEquals(1, stale.number("APPROVAL_STALE_RENDERED"));
      assertPlannedRows(stale.approvalNonce());

      List<String> driftFields =
          List.of("policy", "connector", "audience", "account-ref", "max-calls");
      for (int index = 0; index < driftFields.size(); index++) {
        String field = driftFields.get(index);
        Scenario drift =
            runScenario(
                application.port(),
                harness,
                html,
                manifest,
                "real-preview-" + field + "-drift",
                String.valueOf(108 + index));
        assertEquals(field, drift.text("PREVIEW_DRIFT_FIELD"));
        assertEquals(1, drift.number("PREVIEW_DRIFT_HASH_RECOMPUTED"));
        assertEquals(1, drift.number("PREVIEW_DRIFT_UNKNOWN"));
        assertEquals("UNKNOWN", drift.text("APPROVAL_FINAL_STATE"));
        assertEquals(0, drift.number("PREVIEW_V2_AUTHORITY_EXACT"));
        assertEquals(0, drift.number("APPROVAL_POST_COUNT"));
        assertEquals(0, drift.number("EXECUTE_POST_COUNT"));
        assertEquals(1, drift.number("QUIET_BEFORE_SECOND_GESTURE"));
        assertEquals(0, drift.number("PLANNED_FIXTURE_USED_FOR_RED_LOCALIZATION"));
        assertNoAttemptRows(drift.approvalNonce());
      }

      Scenario timerSpoof =
          runScenario(
              application.port(), harness, html, manifest, "real-timer-spoof", "113");
      assertSuccessfulSecondGesture(timerSpoof);
      assertEquals(1, timerSpoof.number("TIMER_CANARY_OBSERVED"));
      assertEquals(0, timerSpoof.number("TIMER_INHERITED_SECOND_GESTURE"));
      assertExecutedRows(timerSpoof.approvalNonce());

      Scenario readyRecovery =
          runScenario(
              application.port(),
              harness,
              html,
              manifest,
              "real-executing-edit-ready-recovery",
              "116");
      assertReadyRecoveryReleasesCurrentApproval(readyRecovery);
      assertExecutedRows(readyRecovery.approvalNonce());

      Scenario executingEdit =
          runScenario(
              application.port(), harness, html, manifest, "real-executing-edit", "114");
      assertExecutingEditRecovery(executingEdit);
      assertExecutedRows(executingEdit.approvalNonce());

      Scenario terminalNewArtifact =
          runScenario(
              application.port(),
              harness,
              html,
              manifest,
              "real-terminal-new-artifact",
              "115");
      assertTerminalResultSeparatedFromNewArtifact(terminalNewArtifact);
      assertExecutedRows(terminalNewArtifact.approvalNonce());

      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
    } finally {
      try {
        stop(application);
      } finally {
        try {
          deleteTree(tempDirectory);
        } catch (IOException cleanupFailure) {
          System.err.println("REAL_LOCAL_DRAFTBOX_UI_CLEANUP_WARNING " + cleanupFailure);
        }
      }
    }
  }

  private static void assertExactUiPlan(Scenario scenario) {
    assertExactUiPlan(scenario, "CANVAS_READY");
  }

  private static void assertExactUiPlan(Scenario scenario, String expectedCanvasState) {
    assertEquals(22, scenario.number("NODE_MAJOR"));
    assertEquals(1, scenario.number("SCRIPT_EXECUTED"));
    assertEquals(200, scenario.number("CAPTURE_REPLAY_STATUS"));
    assertEquals(1, scenario.number("CAPTURE_ID_MATCH"));
    assertEquals(1, scenario.number("OUTCOME_TRIGGER_DISPATCHED"));
    assertEquals(expectedCanvasState, scenario.text("CANVAS_STATE"));
    assertEquals(1, scenario.number("ARTIFACT_CURRENT_EXACT"));
    assertEquals(1, scenario.number("APPROVAL_READY"));
    assertEquals(1, scenario.number("PREVIEW_V2_AUTHORITY_EXACT"));
    assertEquals(1, scenario.number("APPROVAL_VIA_UI"));
    assertEquals(0, scenario.number("PLANNED_FIXTURE_USED_FOR_RED_LOCALIZATION"));
    assertEquals(1, scenario.number("APPROVAL_POST_COUNT"));
    assertEquals(1, scenario.number("APPROVAL_REQUEST_EXACT"));
    assertEquals(1, scenario.number("APPROVAL_FETCH_OPTIONS_EXACT"));
    assertEquals(1, scenario.number("APPROVAL_RESPONSE_STRICT"));
    assertEquals(0, scenario.number("EXECUTE_POST_COUNT_BEFORE_SECOND_GESTURE"));
    assertEquals(1, scenario.number("QUIET_BEFORE_SECOND_GESTURE"));
    assertEquals(0, scenario.number("LEGACY_ACTION_CALLS"));
    assertEquals(0, scenario.number("RECONCILE_CALLS"));
    assertEquals(0, scenario.number("PROVIDER_SURFACE_CALLS"));
  }

  private static void assertSuccessfulSecondGesture(Scenario scenario) {
    assertSuccessfulSecondGesture(scenario, "CANVAS_READY");
  }

  private static void assertSuccessfulSecondGesture(
      Scenario scenario, String expectedCanvasState) {
    assertExactUiPlan(scenario, expectedCanvasState);
    assertEquals(1, scenario.number("EXECUTE_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("EXECUTE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_POST_DURING_SECOND_GESTURE"));
    assertEquals(0, scenario.number("UNEXPECTED_EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_SUBMITTING_STATE_OBSERVED"));
    assertEquals(1, scenario.number("EXECUTE_REQUEST_EXACT"));
    assertEquals(1, scenario.number("EXECUTE_FETCH_OPTIONS_EXACT"));
    assertEquals(201, scenario.number("EXECUTE_STATUS"));
    assertEquals(1, scenario.number("EXECUTE_RESPONSE_STRICT"));
    assertEquals(1, scenario.number("TERMINAL_WAIT_REACHED"));
    assertEquals("SUCCEEDED", scenario.text("APPROVAL_FINAL_STATE"));
    assertEquals(1, scenario.number("LOCAL_DRAFT_RENDERED"));
    assertEquals(1, scenario.number("TYPED_RECEIPT_RENDERED"));
    assertEquals(1, scenario.number("EXECUTION_SENSITIVE_REFS_HIDDEN"));
    assertEquals(1, scenario.number("NO_FALSE_UNDO_CLAIM"));
    assertEquals(1, scenario.number("UNDO_BOUNDARY_EXPLICIT"));
    assertEquals(1, scenario.number("UNDO_CONTROLS_SAFE"));
    assertEquals(0, scenario.number("FORBIDDEN_ACTION_CALLS"));
  }

  private static void assertExplicitGetRecovery(Scenario scenario, boolean expectedAbort) {
    assertSuccessfulSecondGesture(scenario);
    assertEquals(1, scenario.number("EXECUTE_UNKNOWN_BEFORE_RECOVERY"));
    assertEquals(1, scenario.number("QUIET_AFTER_UNKNOWN"));
    assertEquals(1, scenario.number("RECOVERY_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("RECOVERY_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("RECOVERY_GET_COUNT"));
    assertEquals(1, scenario.number("RECOVERY_GET_DURING_EXPLICIT_GESTURE"));
    assertEquals(1, scenario.number("RECOVERY_CANONICAL_EXACT"));
    assertEquals(expectedAbort ? 1 : 0, scenario.number("HANGING_SIGNAL_ABORTED"));
  }

  private static void assertExecutingEditRecovery(Scenario scenario) {
    assertEquals("real-executing-edit", scenario.text("SCENARIO_MODE"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_TRIGGERED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_HISTORICAL_UNKNOWN"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_GET_ONLY"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_SAVE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("ARTIFACT_REVISION_PUT_COUNT"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_V2_BOUNDARY_EXACT"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_V2_HANDLERS_FORCED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_V2_FORCED_HANDLERS_NO_POST"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_V2_SCOPE_RECOVERY_ISOLATED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_SECOND_EDIT_DISPATCHED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_SECOND_DIRTY_HANDLERS_FORCED"));
    assertEquals(
        1, scenario.number("EXECUTING_EDIT_SECOND_DIRTY_FORCED_HANDLERS_NO_POST"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_SECOND_DIRTY_RECOVERY_RETAINED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_POST_COUNTS_STABLE"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_OLD_SCOPE_POST_BLOCKED"));
    assertEquals(1, scenario.number("QUIET_AFTER_UNKNOWN"));
    assertEquals(1, scenario.number("RECOVERY_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("RECOVERY_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("RECOVERY_GET_COUNT"));
    assertEquals(1, scenario.number("RECOVERY_GET_DURING_EXPLICIT_GESTURE"));
    assertEquals(1, scenario.number("RECOVERY_CANONICAL_EXACT"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_SAME_ATTEMPT_RECOVERY"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_RECOVERED_SUCCEEDED"));
    assertEquals(1, scenario.number("EXECUTING_EDIT_NO_HANDLE_OVERWRITE"));
    assertEquals(1, scenario.number("EXECUTE_POST_COUNT"));
    assertSuccessfulSecondGesture(scenario, "DIRTY");
  }

  private static void assertReadyRecoveryReleasesCurrentApproval(Scenario scenario) {
    assertEquals(1, scenario.number("READY_RECOVERY_CURRENT_APPROVAL_REOPENED"));
    assertEquals("real-executing-edit-ready-recovery", scenario.text("SCENARIO_MODE"));
    assertEquals(1, scenario.number("READY_RECOVERY_PHASE_COMPLETED"));
    assertExactUiPlan(scenario, "REVISION_SAVED");
    assertEquals(1, scenario.number("READY_RECOVERY_EXECUTING_EDIT_TRIGGERED"));
    assertEquals(1, scenario.number("READY_RECOVERY_HISTORICAL_UNKNOWN"));
    assertEquals(1, scenario.number("READY_RECOVERY_SAVE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("ARTIFACT_REVISION_PUT_COUNT"));
    assertEquals(1, scenario.number("READY_RECOVERY_V2_BOUNDARY_EXACT"));
    assertEquals(1, scenario.number("READY_RECOVERY_V2_ISOLATED"));
    assertEquals(1, scenario.number("READY_RECOVERY_PRE_RECOVERY_SETTLED"));
    assertEquals(1, scenario.number("RECOVERY_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("RECOVERY_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("RECOVERY_GET_COUNT"));
    assertEquals(1, scenario.number("RECOVERY_GET_DURING_EXPLICIT_GESTURE"));
    assertEquals(1, scenario.number("RECOVERY_CANONICAL_EXACT"));
    assertEquals(1, scenario.number("READY_RECOVERY_SAME_OLD_ATTEMPT_GET"));
    assertEquals(1, scenario.number("READY_RECOVERY_HISTORICAL_RESULT_EXACT"));
    assertEquals(1, scenario.number("READY_RECOVERY_NO_AUTOMATIC_POSTS"));
    assertEquals(1, scenario.number("EXECUTE_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("EXECUTE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_POST_DURING_SECOND_GESTURE"));
    assertEquals(0, scenario.number("UNEXPECTED_EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_SUBMITTING_STATE_OBSERVED"));
    assertEquals(1, scenario.number("EXECUTE_REQUEST_EXACT"));
    assertEquals(1, scenario.number("EXECUTE_FETCH_OPTIONS_EXACT"));
    assertEquals(201, scenario.number("EXECUTE_STATUS"));
    assertEquals(1, scenario.number("EXECUTE_RESPONSE_STRICT"));
    assertEquals(1, scenario.number("TERMINAL_WAIT_REACHED"));
    assertEquals("READY", scenario.text("APPROVAL_FINAL_STATE"));
    assertEquals(1, scenario.number("LOCAL_DRAFT_RENDERED"));
    assertEquals(1, scenario.number("TYPED_RECEIPT_RENDERED"));
    assertEquals(1, scenario.number("EXECUTION_SENSITIVE_REFS_HIDDEN"));
    assertEquals(1, scenario.number("NO_FALSE_UNDO_CLAIM"));
    assertEquals(1, scenario.number("UNDO_BOUNDARY_EXPLICIT"));
    assertEquals(1, scenario.number("UNDO_CONTROLS_SAFE"));
    assertEquals(0, scenario.number("FORBIDDEN_ACTION_CALLS"));
    assertEquals(0, scenario.number("LEGACY_ACTION_CALLS"));
    assertEquals(0, scenario.number("RECONCILE_CALLS"));
    assertEquals(0, scenario.number("PROVIDER_SURFACE_CALLS"));
    assertEquals(0, scenario.number("PLANNED_FIXTURE_USED_FOR_RED_LOCALIZATION"));
  }

  private static void assertTerminalResultSeparatedFromNewArtifact(Scenario scenario) {
    assertEquals("real-terminal-new-artifact", scenario.text("SCENARIO_MODE"));
    assertExactUiPlan(scenario, "REVISION_SAVED");
    assertEquals(1, scenario.number("EXECUTE_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("EXECUTE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_POST_DURING_SECOND_GESTURE"));
    assertEquals(0, scenario.number("UNEXPECTED_EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_SUBMITTING_STATE_OBSERVED"));
    assertEquals(1, scenario.number("EXECUTE_REQUEST_EXACT"));
    assertEquals(1, scenario.number("EXECUTE_FETCH_OPTIONS_EXACT"));
    assertEquals(201, scenario.number("EXECUTE_STATUS"));
    assertEquals(1, scenario.number("EXECUTE_RESPONSE_STRICT"));
    assertEquals(1, scenario.number("TERMINAL_WAIT_REACHED"));
    assertEquals(1, scenario.number("TERMINAL_EDIT_INPUT_DISPATCHED"));
    assertEquals(1, scenario.number("TERMINAL_SAVE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("ARTIFACT_REVISION_PUT_COUNT"));
    assertEquals(1, scenario.number("TERMINAL_NEW_ARTIFACT_SAVED"));
    assertEquals(1, scenario.number("TERMINAL_HISTORICAL_CONTEXT_EXACT"));
    assertEquals(1, scenario.number("TERMINAL_HISTORICAL_ARTIFACT_EXACT"));
    assertEquals(1, scenario.number("TERMINAL_NEW_READY_FACTS_EXACT"));
    assertEquals(1, scenario.number("TERMINAL_CONTEXTS_NOT_MIXED"));
    assertEquals(1, scenario.number("TERMINAL_NEW_ARTIFACT_NO_APPROVAL_OR_EXECUTE"));
    assertEquals("READY", scenario.text("APPROVAL_FINAL_STATE"));
    assertEquals(1, scenario.number("LOCAL_DRAFT_RENDERED"));
    assertEquals(1, scenario.number("TYPED_RECEIPT_RENDERED"));
    assertEquals(1, scenario.number("EXECUTION_SENSITIVE_REFS_HIDDEN"));
    assertEquals(1, scenario.number("NO_FALSE_UNDO_CLAIM"));
    assertEquals(1, scenario.number("UNDO_BOUNDARY_EXPLICIT"));
    assertEquals(1, scenario.number("UNDO_CONTROLS_SAFE"));
    assertEquals(0, scenario.number("FORBIDDEN_ACTION_CALLS"));
    assertEquals(0, scenario.number("LEGACY_ACTION_CALLS"));
    assertEquals(0, scenario.number("RECONCILE_CALLS"));
    assertEquals(0, scenario.number("PROVIDER_SURFACE_CALLS"));
    assertEquals(0, scenario.number("PLANNED_FIXTURE_USED_FOR_RED_LOCALIZATION"));
  }

  private static void assertFailClosedExecution(
      Scenario scenario, String expectedState, int expectedStatus) {
    assertExactUiPlan(scenario);
    assertEquals(1, scenario.number("EXECUTE_GESTURE_PRESENT"));
    assertEquals(1, scenario.number("EXECUTE_CLICK_DISPATCHED"));
    assertEquals(1, scenario.number("EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_POST_DURING_SECOND_GESTURE"));
    assertEquals(0, scenario.number("UNEXPECTED_EXECUTE_POST_COUNT"));
    assertEquals(1, scenario.number("EXECUTE_SUBMITTING_STATE_OBSERVED"));
    assertEquals(1, scenario.number("EXECUTE_REQUEST_EXACT"));
    assertEquals(1, scenario.number("EXECUTE_FETCH_OPTIONS_EXACT"));
    assertEquals(expectedStatus, scenario.number("EXECUTE_STATUS"));
    assertEquals(0, scenario.number("EXECUTE_RESPONSE_STRICT"));
    assertEquals(1, scenario.number("TERMINAL_WAIT_REACHED"));
    assertEquals(expectedState, scenario.text("APPROVAL_FINAL_STATE"));
    assertEquals(1, scenario.number("QUIET_AFTER_UNKNOWN"));
    assertEquals(0, scenario.number("RECOVERY_GET_COUNT"));
    assertEquals(0, scenario.number("LOCAL_DRAFT_RENDERED"));
    assertEquals(0, scenario.number("TYPED_RECEIPT_RENDERED"));
    assertEquals(1, scenario.number("FAIL_CLOSED_NO_SUCCESS"));
  }

  private static void assertExecutedRows(String approvalNonce) throws Exception {
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempts "
                + "WHERE principal_id=? AND idempotency_key=? AND status='SUCCEEDED' "
                + "AND capability_used_calls=1",
            OWNER,
            approvalNonce));
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM local_drafts d JOIN action_attempts a "
                + "ON a.principal_id=d.principal_id AND a.attempt_id=d.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=? AND d.state='ACTIVE'",
            OWNER,
            approvalNonce));
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM local_draft_creation_receipts r JOIN action_attempts a "
                + "ON a.principal_id=r.principal_id AND a.attempt_id=r.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=? "
                + "AND r.receipt_type='LOCAL_DRAFT_CREATED_V1' "
                + "AND r.outcome='SUCCEEDED' AND NOT r.simulated",
            OWNER,
            approvalNonce));
  }

  private static void assertPlannedRows(String approvalNonce) throws Exception {
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempts "
                + "WHERE principal_id=? AND idempotency_key=? AND status='PLANNED' "
                + "AND capability_used_calls=0",
            OWNER,
            approvalNonce));
    assertEquals(
        0,
        queryInt(
            "SELECT count(*) FROM local_drafts d JOIN action_attempts a "
                + "ON a.principal_id=d.principal_id AND a.attempt_id=d.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=?",
            OWNER,
            approvalNonce));
    assertEquals(
        0,
        queryInt(
            "SELECT count(*) FROM local_draft_creation_receipts r JOIN action_attempts a "
                + "ON a.principal_id=r.principal_id AND a.attempt_id=r.attempt_id "
                + "WHERE a.principal_id=? AND a.idempotency_key=?",
            OWNER,
            approvalNonce));
  }

  private static void assertNoAttemptRows(String approvalNonce) throws Exception {
    assertEquals(
        0,
        queryInt(
            "SELECT count(*) FROM action_attempts WHERE principal_id=? AND idempotency_key=?",
            OWNER,
            approvalNonce));
  }

  private static Scenario runScenario(
      int port, Path harness, Path html, Path manifest, String mode, String suffix)
      throws Exception {
    String captureNonce = "11111111-1111-4111-8111-000000000" + suffix;
    String approvalNonce = "22222222-2222-4222-8222-000000000" + suffix;
    String content = "synthetic real local draftbox UI " + mode + " " + suffix;
    String captureId = seedCapture(port, captureNonce, content);
    String output =
        runHarness(
            port,
            harness,
            html,
            manifest,
            captureId,
            captureNonce,
            approvalNonce,
            content,
            mode);
    return new Scenario(mode, approvalNonce, output);
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
    assertEquals(201, created.statusCode(), "REAL_LOCAL_DRAFTBOX_UI_SEED_CAPTURE_FAILED");
    return JsonPath.read(created.body(), "$.captureId");
  }

  private static String runHarness(
      int port,
      Path harness,
      Path html,
      Path manifest,
      String captureId,
      String captureNonce,
      String approvalNonce,
      String content,
      String mode)
      throws Exception {
    Process process =
        new ProcessBuilder(
                "node",
                harness.toString(),
                html.toString(),
                manifest.toString(),
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
    assertTrue(finished, () -> "REAL_LOCAL_DRAFTBOX_UI_NODE_TIMEOUT\n" + output);
    assertEquals(0, process.exitValue(), () -> "REAL_LOCAL_DRAFTBOX_UI_NODE_FAILED\n" + output);
    return output;
  }

  private static void copyResource(String name, Path target) throws IOException {
    try (InputStream source = RealLocalDraftboxUiHttpIT.class.getResourceAsStream(name)) {
      assertNotNull(source, "missing test resource " + name);
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-real-local-draftbox-ui-app-", ".log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                System.getProperty("emerge.it.jar"),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--emerge.prototype.principal-id=" + OWNER,
                "--emerge.simulated-provider.base-url=" + UNREACHABLE_PROVIDER,
                "--emerge.simulated-provider.request-timeout=PT0.2S",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitLive(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stop(application);
      throw failure;
    }
  }

  private static void awaitLive(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError(
            "packaged application exited:\n" + Files.readString(application.log()));
      }
      try {
        if (get(application.port(), "/actuator/health/liveness", "application/json").statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // Loopback listener is still starting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become live:\n" + Files.readString(application.log()));
  }

  private static HttpResponse<String> get(int port, String path, String accept)
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
    return HTTP.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int queryInt(String sql, Object... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }

  private static void assertPrivateNoStore(HttpResponse<?> response, String marker) {
    assertEquals(
        "private, no-store",
        response.headers().firstValue("Cache-Control").orElse(null),
        marker);
  }

  private static int outputInt(String output, String key) {
    return Integer.parseInt(outputText(output, key));
  }

  private static String outputText(String output, String key) {
    Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=(.*)$").matcher(output);
    assertTrue(matcher.find(), () -> "missing " + key + " in harness output:\n" + output);
    return matcher.group(1).trim();
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

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      List<Path> ordered = new ArrayList<>(paths.toList());
      Collections.reverse(ordered);
      for (Path path : ordered) {
        Files.deleteIfExists(path);
      }
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}

  private record Scenario(String mode, String approvalNonce, String output) {
    int number(String key) {
      return outputInt(output, key);
    }

    String text(String key) {
      return outputText(output, key);
    }
  }
}
