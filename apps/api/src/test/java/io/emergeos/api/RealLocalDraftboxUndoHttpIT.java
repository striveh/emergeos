package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Packaged-app Acceptance Red for executing one exact approval into the local draftbox. */
@Testcontainers
class RealLocalDraftboxUndoHttpIT {

  private static final String OWNER = "real-local-draftbox-owner";
  private static final String SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
  private static final String UNDO_SCOPE_SCHEMA = "emergeos.local-draft-undo-scope.v1";
  private static final String UNREACHABLE_PROVIDER = "http://127.0.0.1:1";
  private static final String ZERO_HASH = "0".repeat(64);
  private static final Pattern SHA_256_TEXT = Pattern.compile("[0-9a-f]{64}");

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
  void executesAnExactPlannedApprovalIntoTheRealLocalDraftbox() throws Exception {
    RunningApplication application = startApplication();
    try {
      ArtifactHead artifact = createCurrentArtifact(application.port());
      ScopeHead scope = previewExactScope(application.port(), artifact);

      HttpResponse<String> approval =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              """
              {
                "approvedArtifactVersion": %d,
                "approvedArtifactHash": "%s",
                "approvalNonce": "real-local-draftbox-approval-001",
                "approvedScopeSchema": "%s",
                "approvedScopeHash": "%s"
              }
              """
                  .formatted(
                      artifact.version(), artifact.hash(), scope.schema(), scope.hash()));
      assertEquals(201, approval.statusCode(), approval::body);
      assertPrivateNoStore(approval);
      assertEquals("PLANNED", JsonPath.read(approval.body(), "$.status"));
      assertEquals(scope.schema(), JsonPath.read(approval.body(), "$.scopeSchema"));
      assertEquals(scope.hash(), JsonPath.read(approval.body(), "$.scopeHash"));
      String attemptId = JsonPath.read(approval.body(), "$.attemptId");
      assertNotNull(attemptId);
      assertTrue(!attemptId.isBlank());

      HttpResponse<String> executed =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + attemptId + "/execute",
              """
              {
                "scopeSchema": "%s",
                "scopeHash": "%s"
              }
              """
                  .formatted(scope.schema(), scope.hash()));
      if (executed.statusCode() != 201) {
        System.out.printf(
            "REAL_LOCAL_DRAFTBOX_EXECUTE_MISSING expected=201 actual=%d%n",
            executed.statusCode());
      }
      assertEquals(
          201,
          executed.statusCode(),
          "REAL_LOCAL_DRAFTBOX_EXECUTE_MISSING expected=201 actual="
              + executed.statusCode());
      assertPrivateNoStore(executed);
      String approvalLocation = "/api/v1/action-approvals/" + attemptId;
      assertEquals(
          approvalLocation,
          executed.headers().firstValue("Location").orElse(null));
      assertEquals("LOCAL_DRAFTBOX_V2", scope.executionRoute());
      assertEquals("local-action-v2", scope.policyVersion());
      assertEquals("emergeos.local-draftbox", scope.connector());
      assertEquals("emergeos:local-draftbox", scope.audience());
      assertEquals("local-draftbox:" + OWNER, scope.accountRef());
      assertEquals(1, scope.maxCalls());
      assertExecutedLocalDraft(executed.body(), attemptId, artifact, scope);
      String canonicalExecutedBody = executed.body();
      String draftId = JsonPath.read(canonicalExecutedBody, "$.localDraft.draftId");
      String receiptId = JsonPath.read(canonicalExecutedBody, "$.receipt.receiptId");

      HttpResponse<String> replayed =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + attemptId + "/execute",
              """
              {
                "scopeSchema": "%s",
                "scopeHash": "%s"
              }
              """
                  .formatted(scope.schema(), scope.hash()));
      assertEquals(200, replayed.statusCode(), replayed::body);
      assertPrivateNoStore(replayed);
      assertEquals(
          approvalLocation,
          replayed.headers().firstValue("Location").orElse(null));
      assertEquals(canonicalExecutedBody, replayed.body());
      assertEquals(draftId, JsonPath.read(replayed.body(), "$.localDraft.draftId"));
      assertEquals(receiptId, JsonPath.read(replayed.body(), "$.receipt.receiptId"));

      HttpResponse<String> persisted =
          send(application.port(), "GET", approvalLocation, null);
      assertEquals(200, persisted.statusCode(), persisted::body);
      assertPrivateNoStore(persisted);
      assertEquals(canonicalExecutedBody, persisted.body());
      assertEquals(draftId, JsonPath.read(persisted.body(), "$.localDraft.draftId"));
      assertEquals(receiptId, JsonPath.read(persisted.body(), "$.receipt.receiptId"));

      assertEquals(1, queryInt("SELECT count(*) FROM action_attempts"));
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM action_attempts "
                  + "WHERE principal_id=? AND attempt_id=? AND status='SUCCEEDED' "
                  + "AND capability_used_calls=1",
              OWNER,
              attemptId));
      assertEquals(1, queryInt("SELECT count(*) FROM local_drafts"));
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM local_drafts "
                  + "WHERE principal_id=? AND attempt_id=? AND draft_id=? AND state='ACTIVE' "
                  + "AND artifact_id=? AND artifact_version=? AND artifact_hash=?",
              OWNER,
              attemptId,
              draftId,
              artifact.artifactId(),
              artifact.version(),
              artifact.hash()));
      assertEquals(1, queryInt("SELECT count(*) FROM local_draft_creation_receipts"));
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM local_draft_creation_receipts "
                  + "WHERE principal_id=? AND attempt_id=? AND receipt_id=? AND draft_id=? "
                  + "AND receipt_type='LOCAL_DRAFT_CREATED_V1' "
                  + "AND outcome='SUCCEEDED' AND NOT simulated",
              OWNER,
              attemptId,
              receiptId,
              draftId));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
      assertEquals(2, queryInt("SELECT count(*) FROM action_attempt_transitions"));
      assertEquals(
          0,
          queryInt(
              "SELECT count(*) FROM action_attempt_transitions "
                  + "WHERE principal_id=? AND attempt_id=? AND NOT ("
                  + "(sequence=1 AND from_status IS NULL AND to_status='PLANNED' "
                  + "AND capability_use_delta=0) OR "
                  + "(sequence=2 AND from_status='PLANNED' AND to_status='SUCCEEDED' "
                  + "AND capability_use_delta=1))",
              OWNER,
              attemptId));

      String undoNonce = "real-local-draftbox-logical-undo-001";
      String undoScopeHash =
          logicalUndoScopeHash(
              draftId,
              attemptId,
              receiptId,
              artifact,
              undoNonce);
      Map<String, Object> canonicalCreationReceipt =
          JsonPath.parse(canonicalExecutedBody).read("$.receipt");
      RetainedTruthSnapshot retainedBeforeUndo =
          retainedTruthSnapshot(artifact.artifactId(), attemptId);

      HttpResponse<String> undone =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + attemptId + "/undo",
              undoRequest(undoNonce, UNDO_SCOPE_SCHEMA, undoScopeHash));
      if (undone.statusCode() != 201) {
        System.out.printf(
            "REAL_LOCAL_DRAFTBOX_LOGICAL_UNDO_MISSING expected=201 actual=%d%n",
            undone.statusCode());
      }
      assertEquals(
          201,
          undone.statusCode(),
          "REAL_LOCAL_DRAFTBOX_LOGICAL_UNDO_MISSING expected=201 actual="
              + undone.statusCode());
      assertPrivateNoStore(undone);
      assertEquals(
          approvalLocation,
          undone.headers().firstValue("Location").orElse(null));
      assertLogicallyUndone(
          undone.body(),
          attemptId,
          draftId,
          receiptId,
          artifact,
          undoNonce,
          undoScopeHash,
          canonicalCreationReceipt);
      String canonicalUndoneBody = undone.body();
      String undoReceiptId = JsonPath.read(canonicalUndoneBody, "$.undoReceipt.receiptId");
      assertNotNull(undoReceiptId);
      assertTrue(!undoReceiptId.isBlank());

      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM local_draft_undo_receipts "
                  + "WHERE principal_id=? AND creation_attempt_id=? AND draft_id=? "
                  + "AND creation_receipt_id=? AND artifact_id=? AND artifact_version=? "
                  + "AND artifact_hash=? AND scope_schema=? AND scope_hash=? AND undo_nonce=? "
                  + "AND receipt_id=? AND receipt_type='LOCAL_DRAFT_LOGICALLY_UNDONE_V1' "
                  + "AND effect='LOGICALLY_UNDONE' "
                  + "AND retention='CAPTURE_ARTIFACT_HISTORY_RETAINED' "
                  + "AND outcome='SUCCEEDED' AND NOT simulated",
              OWNER,
              attemptId,
              draftId,
              receiptId,
              artifact.artifactId(),
              artifact.version(),
              artifact.hash(),
              UNDO_SCOPE_SCHEMA,
              undoScopeHash,
              undoNonce,
              undoReceiptId));
      String undoReceiptBeforeReplay = undoReceiptSnapshot(undoReceiptId);

      HttpResponse<String> undoReplay =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + attemptId + "/undo",
              undoRequest(undoNonce, UNDO_SCOPE_SCHEMA, undoScopeHash));
      assertEquals(200, undoReplay.statusCode(), undoReplay::body);
      assertPrivateNoStore(undoReplay);
      assertEquals(
          approvalLocation,
          undoReplay.headers().firstValue("Location").orElse(null));
      assertEquals(canonicalUndoneBody, undoReplay.body());

      HttpResponse<String> recoveredUndo =
          send(application.port(), "GET", approvalLocation, null);
      assertEquals(200, recoveredUndo.statusCode(), recoveredUndo::body);
      assertPrivateNoStore(recoveredUndo);
      assertEquals(canonicalUndoneBody, recoveredUndo.body());

      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM action_attempts "
                  + "WHERE principal_id=? AND attempt_id=? AND status='SUCCEEDED' "
                  + "AND state_version=2 AND capability_used_calls=1",
              OWNER,
              attemptId));
      assertEquals(
          2,
          queryInt(
              "SELECT count(*) FROM action_attempt_transitions "
                  + "WHERE principal_id=? AND attempt_id=?",
              OWNER,
              attemptId));
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM local_drafts "
                  + "WHERE principal_id=? AND attempt_id=? AND draft_id=? AND state='ACTIVE'",
              OWNER,
              attemptId,
              draftId));
      assertEquals(1, queryInt("SELECT count(*) FROM local_draft_undo_receipts"));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
      assertEquals(
          retainedBeforeUndo, retainedTruthSnapshot(artifact.artifactId(), attemptId));
      assertEquals(undoReceiptBeforeReplay, undoReceiptSnapshot(undoReceiptId));
      assertUndoReceiptImmutable(undoReceiptId);
      assertEquals(
          retainedBeforeUndo, retainedTruthSnapshot(artifact.artifactId(), attemptId));
      assertEquals(undoReceiptBeforeReplay, undoReceiptSnapshot(undoReceiptId));
      assertFalse(canonicalUndoneBody.toLowerCase(Locale.ROOT).contains("reflection"));

      ActiveDraftFixture wrongUndoFixture =
          createIndependentActiveDraftFixture(application.port());
      assertFalse(artifact.artifactId().equals(wrongUndoFixture.artifact().artifactId()));
      assertEquals(2, queryInt("SELECT count(*) FROM captures"));
      assertEquals(2, queryInt("SELECT count(*) FROM artifacts"));
      String wrongUndoNonce = "real-local-draftbox-logical-undo-wrong-scope-002";
      String validSecondUndoScopeHash =
          logicalUndoScopeHash(
              wrongUndoFixture.draftId(),
              wrongUndoFixture.attemptId(),
              wrongUndoFixture.creationReceiptId(),
              wrongUndoFixture.artifact(),
              wrongUndoNonce);
      RetainedTruthSnapshot secondRetainedBeforeWrongUndo =
          retainedTruthSnapshot(
              wrongUndoFixture.artifact().artifactId(), wrongUndoFixture.attemptId());
      DatabaseMutationDigest wrongUndoBaseline = databaseMutationDigest();

      HttpResponse<String> wrongUndoScope =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + wrongUndoFixture.attemptId() + "/undo",
              undoRequest(
                  wrongUndoNonce,
                  UNDO_SCOPE_SCHEMA,
                  differentValidHash(validSecondUndoScopeHash)));
      ProblemShape wrongUndoShape = assertSafeProblem(wrongUndoScope, 412);
      assertEquals("urn:emergeos:problem:approval-stale", wrongUndoShape.type());
      assertEquals(wrongUndoBaseline, databaseMutationDigest());
      assertEquals(
          secondRetainedBeforeWrongUndo,
          retainedTruthSnapshot(
              wrongUndoFixture.artifact().artifactId(), wrongUndoFixture.attemptId()));
      assertEquals(1, queryInt("SELECT count(*) FROM local_draft_undo_receipts"));
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM local_draft_undo_receipts "
                  + "WHERE principal_id=? AND receipt_id=?",
              OWNER,
              undoReceiptId));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
      assertFalse(wrongUndoScope.body().toLowerCase(Locale.ROOT).contains("reflection"));

      DatabaseMutationDigest negativeBaseline = databaseMutationDigest();
      String wrongScopeHash = differentValidHash(scope.hash());
      HttpResponse<String> wrongScope =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + attemptId + "/execute",
              executeRequest(scope.schema(), wrongScopeHash));
      ProblemShape staleShape = assertSafeProblem(wrongScope, 412);
      assertEquals("urn:emergeos:problem:approval-stale", staleShape.type());
      assertEquals("Action approval stale", staleShape.title());
      assertEquals(
          "The approved action scope is not the current local scope.",
          staleShape.detail());
      assertEquals(negativeBaseline, databaseMutationDigest());

      HttpResponse<String> legacyGet =
          send(application.port(), "GET", "/api/v1/actions/" + attemptId, null);
      HttpResponse<String> legacyReconcile =
          send(
              application.port(),
              "POST",
              "/api/v1/actions/" + attemptId + "/reconcile",
              null);
      ProblemShape hiddenShape = assertSafeProblem(legacyGet, 404);
      assertEquals(hiddenShape, assertSafeProblem(legacyReconcile, 404));
      assertEquals(negativeBaseline, databaseMutationDigest());

      String missingAttemptId = "missing-real-local-draftbox-approval";
      HttpResponse<String> missingGet =
          send(
              application.port(),
              "GET",
              "/api/v1/action-approvals/" + missingAttemptId,
              null);
      HttpResponse<String> missingExecute =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + missingAttemptId + "/execute",
              executeRequest(scope.schema(), scope.hash()));
      assertSafeProblem(missingGet, 404);
      assertSafeProblem(missingExecute, 404);
      assertEquals(negativeBaseline, databaseMutationDigest());

      HttpResponse<String> integrityPlanned =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              """
              {
                "approvedArtifactVersion": %d,
                "approvedArtifactHash": "%s",
                "approvalNonce": "real-local-draftbox-integrity-001",
                "approvedScopeSchema": "%s",
                "approvedScopeHash": "%s"
              }
              """
                  .formatted(
                      artifact.version(), artifact.hash(), scope.schema(), scope.hash()));
      assertEquals(201, integrityPlanned.statusCode(), integrityPlanned::body);
      assertPrivateNoStore(integrityPlanned);
      assertEquals("PLANNED", JsonPath.read(integrityPlanned.body(), "$.status"));
      String integrityAttemptId = JsonPath.read(integrityPlanned.body(), "$.attemptId");
      corruptStoredPlanHashes(integrityAttemptId);
      assertEquals(
          1,
          queryInt(
              "SELECT count(*) FROM pg_trigger "
                  + "WHERE tgrelid='action_attempts'::regclass "
                  + "AND tgname='action_attempts_freeze_scope_v17' "
                  + "AND NOT tgisinternal AND tgenabled='O'"));
      assertEquals(
          ZERO_HASH,
          queryString(
              "SELECT plan_hash FROM action_attempts "
                  + "WHERE principal_id=? AND attempt_id=?",
              OWNER,
              integrityAttemptId));
      assertEquals(
          ZERO_HASH,
          queryString(
              "SELECT capability_plan_hash FROM action_attempts "
                  + "WHERE principal_id=? AND attempt_id=?",
              OWNER,
              integrityAttemptId));
      DatabaseMutationDigest corruptionBaseline = databaseMutationDigest();

      HttpResponse<String> integrityConflict =
          send(
              application.port(),
              "POST",
              "/api/v1/action-approvals/" + integrityAttemptId + "/execute",
              executeRequest(scope.schema(), scope.hash()));
      if (integrityConflict.statusCode() != 409) {
        System.out.printf(
            "REAL_LOCAL_DRAFTBOX_INTEGRITY_MISSING expected=409 actual=%d%n",
            integrityConflict.statusCode());
      }
      ProblemShape integrityShape = assertSafeProblem(integrityConflict, 409);
      assertEquals(
          "urn:emergeos:problem:action-approval-integrity",
          integrityShape.type());
      assertEquals(corruptionBaseline, databaseMutationDigest());
    } finally {
      stop(application);
    }
  }

  private static String executeRequest(String scopeSchema, String scopeHash) {
    return """
        {
          "scopeSchema": "%s",
          "scopeHash": "%s"
        }
        """
        .formatted(scopeSchema, scopeHash);
  }

  private static String undoRequest(
      String undoNonce, String scopeSchema, String scopeHash) {
    return """
        {
          "undoNonce": "%s",
          "scopeSchema": "%s",
          "scopeHash": "%s"
        }
        """
        .formatted(undoNonce, scopeSchema, scopeHash);
  }

  private static String logicalUndoScopeHash(
      String draftId,
      String creationAttemptId,
      String creationReceiptId,
      ArtifactHead artifact,
      String undoNonce)
      throws Exception {
    List<String> values =
        List.of(
            "CONFIGURED_LOCAL_PRINCIPAL",
            OWNER,
            "EXPLICIT_LOCAL_OWNER_INPUT",
            "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
            "LOGICALLY_UNDO_LOCAL_DRAFT",
            "local://drafts/" + draftId,
            draftId,
            creationAttemptId,
            creationReceiptId,
            artifact.artifactId(),
            Integer.toString(artifact.version()),
            artifact.hash(),
            "ACTIVE",
            "CAPTURE_ARTIFACT_HISTORY_RETAINED",
            "local-draft-undo-v1",
            "emergeos.local-draftbox",
            "emergeos:local-draftbox",
            "local-draftbox:" + OWNER,
            undoNonce,
            "1");
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    canonical.writeBytes(UNDO_SCOPE_SCHEMA.getBytes(StandardCharsets.UTF_8));
    canonical.write(0);
    for (String value : values) {
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      canonical.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
      canonical.writeBytes(utf8);
    }
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
  }

  private static void assertLogicallyUndone(
      String json,
      String attemptId,
      String draftId,
      String creationReceiptId,
      ArtifactHead artifact,
      String undoNonce,
      String scopeHash,
      Map<String, Object> canonicalCreationReceipt)
      throws Exception {
    Map<String, Object> body = JsonPath.parse(json).read("$");
    assertEquals(
        Set.of(
            "attemptId",
            "status",
            "plan",
            "approval",
            "capability",
            "transitions",
            "receipt",
            "localDraft",
            "scopeSchema",
            "scopeHash",
            "approvalPrincipal",
            "provenance",
            "action",
            "artifact",
            "executionState",
            "undoReceipt",
            "undoAvailable"),
        body.keySet());
    assertEquals(attemptId, JsonPath.read(json, "$.attemptId"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.status"));
    assertEquals("EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(false, JsonPath.read(json, "$.undoAvailable"));
    assertEquals(1, number(json, "$.capability.usedCalls"));
    assertEquals(2, number(json, "$.transitions.length()"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[0].toStatus"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[1].fromStatus"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.transitions[1].toStatus"));
    Map<String, Object> localDraft = JsonPath.parse(json).read("$.localDraft");
    assertEquals(
        Set.of(
            "draftId",
            "state",
            "artifactId",
            "artifactVersion",
            "artifactHash",
            "createdAt"),
        localDraft.keySet());
    assertEquals("LOGICALLY_UNDONE", JsonPath.read(json, "$.localDraft.state"));
    assertEquals(draftId, JsonPath.read(json, "$.localDraft.draftId"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.localDraft.artifactId"));
    assertEquals(artifact.version(), number(json, "$.localDraft.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.localDraft.artifactHash"));
    assertEquals(
        canonicalCreationReceipt.get("occurredAt"),
        JsonPath.read(json, "$.localDraft.createdAt"));
    assertEquals(canonicalCreationReceipt, JsonPath.parse(json).read("$.receipt"));

    Map<String, Object> undoReceipt = JsonPath.parse(json).read("$.undoReceipt");
    assertEquals(
        Set.of(
            "receiptType",
            "receiptId",
            "creationAttemptId",
            "draftId",
            "creationReceiptId",
            "artifactId",
            "artifactVersion",
            "artifactHash",
            "scopeSchema",
            "scopeHash",
            "undoNonce",
            "effect",
            "retention",
            "outcome",
            "occurredAt",
            "simulated"),
        undoReceipt.keySet());
    assertEquals(
        "LOCAL_DRAFT_LOGICALLY_UNDONE_V1",
        JsonPath.read(json, "$.undoReceipt.receiptType"));
    assertEquals(attemptId, JsonPath.read(json, "$.undoReceipt.creationAttemptId"));
    assertEquals(draftId, JsonPath.read(json, "$.undoReceipt.draftId"));
    assertEquals(
        creationReceiptId, JsonPath.read(json, "$.undoReceipt.creationReceiptId"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.undoReceipt.artifactId"));
    assertEquals(artifact.version(), number(json, "$.undoReceipt.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.undoReceipt.artifactHash"));
    assertEquals(UNDO_SCOPE_SCHEMA, JsonPath.read(json, "$.undoReceipt.scopeSchema"));
    assertEquals(scopeHash, JsonPath.read(json, "$.undoReceipt.scopeHash"));
    assertEquals(undoNonce, JsonPath.read(json, "$.undoReceipt.undoNonce"));
    assertEquals("LOGICALLY_UNDONE", JsonPath.read(json, "$.undoReceipt.effect"));
    assertEquals(
        "CAPTURE_ARTIFACT_HISTORY_RETAINED",
        JsonPath.read(json, "$.undoReceipt.retention"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.undoReceipt.outcome"));
    assertEquals(false, JsonPath.read(json, "$.undoReceipt.simulated"));
    Instant occurredAt = Instant.parse(JsonPath.read(json, "$.undoReceipt.occurredAt"));
    assertEquals(
        occurredAt,
        queryInstant(
            "SELECT occurred_at FROM local_draft_undo_receipts "
                + "WHERE principal_id=? AND receipt_id=?",
            OWNER,
            JsonPath.read(json, "$.undoReceipt.receiptId")));
  }

  private static String differentValidHash(String hash) {
    return (hash.charAt(0) == 'a' ? "b" : "a") + hash.substring(1);
  }

  private static ProblemShape assertSafeProblem(
      HttpResponse<String> response, int expectedStatus) {
    assertEquals(expectedStatus, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    assertTrue(
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("application/problem+json"));
    Map<String, Object> body = JsonPath.parse(response.body()).read("$");
    assertTrue(
        Set.of("type", "title", "status", "detail", "instance")
            .containsAll(body.keySet()));
    String type = JsonPath.read(response.body(), "$.type");
    String title = JsonPath.read(response.body(), "$.title");
    String detail = JsonPath.read(response.body(), "$.detail");
    assertEquals(expectedStatus, number(response.body(), "$.status"));
    assertTrue(type != null && !type.isBlank());
    assertTrue(title != null && !title.isBlank());
    assertTrue(detail != null && !detail.isBlank());
    assertSafeProblemText(title + " " + detail);
    return new ProblemShape(type, title, expectedStatus, detail);
  }

  private static void assertSafeProblemText(String text) {
    String normalized = text.toLowerCase(java.util.Locale.ROOT);
    assertFalse(normalized.contains("action_attempts"));
    assertFalse(normalized.contains("plan_hash"));
    assertFalse(normalized.contains("capability_plan_hash"));
    assertFalse(normalized.contains("sql"));
    assertFalse(normalized.contains("exception"));
    assertFalse(normalized.contains("stack"));
    assertFalse(normalized.contains("hash"));
    assertFalse(SHA_256_TEXT.matcher(normalized).find());
  }

  private static void corruptStoredPlanHashes(String attemptId) throws Exception {
    try (var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      try {
        try (Statement statement = connection.createStatement()) {
          statement.execute(
              "ALTER TABLE action_attempts "
                  + "DISABLE TRIGGER action_attempts_freeze_scope_v17");
        }
        try {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE action_attempts "
                      + "SET plan_hash=?, capability_plan_hash=? "
                      + "WHERE principal_id=? AND attempt_id=? "
                      + "AND status='PLANNED' AND state_version=1 "
                      + "AND capability_used_calls=0")) {
            statement.setString(1, ZERO_HASH);
            statement.setString(2, ZERO_HASH);
            statement.setString(3, OWNER);
            statement.setString(4, attemptId);
            assertEquals(1, statement.executeUpdate());
          }
          try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
          }
        } finally {
          try (Statement statement = connection.createStatement()) {
            statement.execute(
                "ALTER TABLE action_attempts "
                    + "ENABLE TRIGGER action_attempts_freeze_scope_v17");
          }
        }
        connection.commit();
      } catch (Exception | AssertionError failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static DatabaseMutationDigest databaseMutationDigest() throws Exception {
    return new DatabaseMutationDigest(
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', principal_id, attempt_id, status, "
                + "state_version::text, capability_used_calls::text), ';' "
                + "ORDER BY principal_id, attempt_id), '') FROM action_attempts"),
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', principal_id, attempt_id, "
                + "sequence::text, COALESCE(from_status, '<null>'), to_status, "
                + "capability_use_delta::text), ';' "
                + "ORDER BY principal_id, attempt_id, sequence), '') "
                + "FROM action_attempt_transitions"),
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', principal_id, attempt_id, draft_id, "
                + "state, artifact_id, artifact_version::text, artifact_hash), ';' "
                + "ORDER BY principal_id, attempt_id, draft_id), '') FROM local_drafts"),
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', principal_id, attempt_id, receipt_id, "
                + "receipt_type, draft_id, outcome, simulated::text), ';' "
                + "ORDER BY principal_id, attempt_id, receipt_id), '') "
                + "FROM local_draft_creation_receipts"),
        queryString(
            "SELECT COALESCE(string_agg(r.xmin::text || '|' || to_jsonb(r)::text, ';' "
                + "ORDER BY principal_id, draft_id, receipt_id), '') "
                + "FROM local_draft_undo_receipts r"),
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', principal_id, attempt_id, receipt_id, "
                + "outcome, simulated::text), ';' "
                + "ORDER BY principal_id, attempt_id, receipt_id), '') "
                + "FROM action_receipts"));
  }

  private static void assertExecutedLocalDraft(
      String json, String attemptId, ArtifactHead artifact, ScopeHead scope) {
    assertEquals(attemptId, JsonPath.read(json, "$.attemptId"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.status"));
    assertEquals("EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(scope.schema(), JsonPath.read(json, "$.scopeSchema"));
    assertEquals(scope.hash(), JsonPath.read(json, "$.scopeHash"));
    assertEquals(scope.executionRoute(), JsonPath.read(json, "$.provenance.executionRoute"));
    assertEquals(scope.policyVersion(), JsonPath.read(json, "$.action.policyVersion"));
    assertEquals(scope.connector(), JsonPath.read(json, "$.capability.connector"));
    assertEquals(scope.audience(), JsonPath.read(json, "$.capability.audience"));
    assertEquals(scope.accountRef(), JsonPath.read(json, "$.capability.accountRef"));
    assertEquals(scope.maxCalls(), number(json, "$.capability.maxCalls"));
    assertEquals(1, number(json, "$.capability.usedCalls"));
    String draftId = JsonPath.read(json, "$.localDraft.draftId");
    assertNotNull(draftId);
    assertTrue(!draftId.isBlank());
    assertEquals("ACTIVE", JsonPath.read(json, "$.localDraft.state"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.localDraft.artifactId"));
    assertEquals(artifact.version(), number(json, "$.localDraft.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.localDraft.artifactHash"));

    Map<String, Object> receipt = JsonPath.parse(json).read("$.receipt");
    assertEquals(
        Set.of(
            "receiptType",
            "receiptId",
            "attemptId",
            "draftId",
            "outcome",
            "occurredAt",
            "simulated"),
        receipt.keySet());
    String receiptId = JsonPath.read(json, "$.receipt.receiptId");
    assertNotNull(receiptId);
    assertTrue(!receiptId.isBlank());
    assertEquals("LOCAL_DRAFT_CREATED_V1", JsonPath.read(json, "$.receipt.receiptType"));
    assertEquals(attemptId, JsonPath.read(json, "$.receipt.attemptId"));
    assertEquals(draftId, JsonPath.read(json, "$.receipt.draftId"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.receipt.outcome"));
    assertEquals(false, JsonPath.read(json, "$.receipt.simulated"));
    assertEquals(
        JsonPath.<String>read(json, "$.localDraft.createdAt"),
        JsonPath.<String>read(json, "$.receipt.occurredAt"));
    assertEquals(2, number(json, "$.transitions.length()"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[0].toStatus"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[1].fromStatus"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.transitions[1].toStatus"));
  }

  private static ArtifactHead createCurrentArtifact(int port) throws Exception {
    return createArtifact(
        port,
        "real-local-draftbox-capture-001",
        "synthetic thought that becomes one local draft",
        "real-local-draftbox:http-it",
        "synthetic current Artifact for the real local draftbox");
  }

  private static ActiveDraftFixture createIndependentActiveDraftFixture(int port)
      throws Exception {
    ArtifactHead artifact =
        createArtifact(
            port,
            "real-local-draftbox-capture-wrong-undo-002",
            "synthetic independent thought for wrong logical Undo scope",
            "real-local-draftbox:http-it:wrong-undo-002",
            "synthetic independent Artifact for wrong logical Undo scope");
    ScopeHead scope = previewExactScope(port, artifact);
    String approvalNonce = "real-local-draftbox-approval-wrong-undo-002";
    HttpResponse<String> approval =
        send(
            port,
            "POST",
            "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
            """
            {
              "approvedArtifactVersion": %d,
              "approvedArtifactHash": "%s",
              "approvalNonce": "%s",
              "approvedScopeSchema": "%s",
              "approvedScopeHash": "%s"
            }
            """
                .formatted(
                    artifact.version(),
                    artifact.hash(),
                    approvalNonce,
                    scope.schema(),
                    scope.hash()));
    assertEquals(201, approval.statusCode(), approval::body);
    assertPrivateNoStore(approval);
    String attemptId = JsonPath.read(approval.body(), "$.attemptId");
    assertNotNull(attemptId);
    assertTrue(!attemptId.isBlank());
    assertEquals(
        approvalNonce,
        queryString(
            "SELECT idempotency_key FROM action_attempts "
                + "WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId));

    HttpResponse<String> executed =
        send(
            port,
            "POST",
            "/api/v1/action-approvals/" + attemptId + "/execute",
            executeRequest(scope.schema(), scope.hash()));
    assertEquals(201, executed.statusCode(), executed::body);
    assertPrivateNoStore(executed);
    assertEquals(
        "/api/v1/action-approvals/" + attemptId,
        executed.headers().firstValue("Location").orElse(null));
    assertExecutedLocalDraft(executed.body(), attemptId, artifact, scope);
    return new ActiveDraftFixture(
        artifact,
        attemptId,
        JsonPath.read(executed.body(), "$.localDraft.draftId"),
        JsonPath.read(executed.body(), "$.receipt.receiptId"));
  }

  private static ArtifactHead createArtifact(
      int port,
      String clientNonce,
      String captureContent,
      String sourceRef,
      String artifactContent)
      throws Exception {
    HttpResponse<String> captured =
        send(
            port,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "%s",
              "content": "%s",
              "sourceType": "TEXT",
              "sourceRef": "%s",
              "dataClass": "PERSONAL"
            }
            """
                .formatted(clientNonce, captureContent, sourceRef));
    assertEquals(201, captured.statusCode(), captured::body);
    String captureId = JsonPath.read(captured.body(), "$.captureId");

    HttpResponse<String> created =
        send(
            port,
            "POST",
            "/api/v1/artifacts",
            """
            {
              "captureId": "%s",
              "content": "%s"
            }
            """
                .formatted(captureId, artifactContent));
    assertEquals(201, created.statusCode(), created::body);
    assertEquals(1, number(created.body(), "$.currentVersion"));
    return new ArtifactHead(
        JsonPath.read(created.body(), "$.artifactId"),
        number(created.body(), "$.currentVersion"),
        JsonPath.read(created.body(), "$.currentHash"));
  }

  private static ScopeHead previewExactScope(int port, ArtifactHead artifact) throws Exception {
    HttpResponse<String> preview =
        send(
            port,
            "GET",
            "/api/v1/artifacts/"
                + artifact.artifactId()
                + "/action-approval-scope?artifactVersion="
                + artifact.version()
                + "&artifactHash="
                + artifact.hash(),
            null);
    assertEquals(200, preview.statusCode(), preview::body);
    assertPrivateNoStore(preview);
    assertEquals(SCOPE_SCHEMA, JsonPath.read(preview.body(), "$.scopeSchema"));
    assertEquals(
        artifact.artifactId(), JsonPath.read(preview.body(), "$.artifact.artifactId"));
    assertEquals(
        artifact.version(), number(preview.body(), "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(preview.body(), "$.artifact.artifactHash"));
    assertEquals(
        "EXPLICIT_LOCAL_OWNER_INPUT",
        JsonPath.read(preview.body(), "$.provenance.approvalOrigin"));
    String scopeHash = JsonPath.read(preview.body(), "$.scopeHash");
    assertTrue(scopeHash.matches("[0-9a-f]{64}"));
    return new ScopeHead(
        SCOPE_SCHEMA,
        scopeHash,
        JsonPath.read(preview.body(), "$.provenance.executionRoute"),
        JsonPath.read(preview.body(), "$.action.policyVersion"),
        JsonPath.read(preview.body(), "$.capability.connector"),
        JsonPath.read(preview.body(), "$.capability.audience"),
        JsonPath.read(preview.body(), "$.capability.accountRef"),
        number(preview.body(), "$.capability.maxCalls"));
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-real-local-draftbox-", ".log");
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
        if (send(application.port(), "GET", "/actuator/health/liveness", null).statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener is still starting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become live:\n" + Files.readString(application.log()));
  }

  private static HttpResponse<String> send(int port, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
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

  private static void assertPrivateNoStore(HttpResponse<?> response) {
    assertEquals(
        "private, no-store", response.headers().firstValue("Cache-Control").orElse(null));
  }

  private static int number(String json, String path) {
    return JsonPath.<Number>read(json, path).intValue();
  }

  private static int queryInt(String sql, Object... values) throws Exception {
    return ((Number) query(sql, values)).intValue();
  }

  private static String queryString(String sql, Object... values) throws Exception {
    return (String) query(sql, values);
  }

  private static Instant queryInstant(String sql, Object... values) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
      }
    }
  }

  private static RetainedTruthSnapshot retainedTruthSnapshot(
      String artifactId, String attemptId)
      throws Exception {
    return new RetainedTruthSnapshot(
        queryInt(
            "SELECT count(*) FROM captures c JOIN artifacts a "
                + "ON a.principal_id=c.principal_id AND a.source_capture_id=c.capture_id "
                + "WHERE a.principal_id=? AND a.artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT c.xmin::text || '|' || to_jsonb(c)::text FROM captures c "
                + "JOIN artifacts a ON a.principal_id=c.principal_id "
                + "AND a.source_capture_id=c.capture_id "
                + "WHERE a.principal_id=? AND a.artifact_id=?",
            OWNER,
            artifactId),
        queryInt(
            "SELECT count(*) FROM artifacts WHERE principal_id=? AND artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT a.xmin::text || '|' || to_jsonb(a)::text FROM artifacts a "
                + "WHERE principal_id=? AND artifact_id=?",
            OWNER,
            artifactId),
        queryInt(
            "SELECT count(*) FROM artifact_versions WHERE principal_id=? AND artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT string_agg(v.xmin::text || '|' || to_jsonb(v)::text, ';' "
                + "ORDER BY v.version) FROM artifact_versions v "
                + "WHERE principal_id=? AND artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT d.xmin::text || '|' || to_jsonb(d)::text FROM local_drafts d "
                + "WHERE principal_id=? AND artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT r.xmin::text || '|' || to_jsonb(r)::text "
                + "FROM local_draft_creation_receipts r JOIN local_drafts d "
                + "ON d.principal_id=r.principal_id AND d.attempt_id=r.attempt_id "
                + "AND d.draft_id=r.draft_id "
                + "WHERE d.principal_id=? AND d.artifact_id=?",
            OWNER,
            artifactId),
        queryString(
            "SELECT a.xmin::text || '|' || a.updated_at::text || '|' || to_jsonb(a)::text "
                + "FROM action_attempts a WHERE a.principal_id=? AND a.attempt_id=?",
            OWNER,
            attemptId),
        queryString(
            "SELECT string_agg(t.xmin::text || '|' || to_jsonb(t)::text, ';' "
                + "ORDER BY t.sequence) FROM action_attempt_transitions t "
                + "WHERE t.principal_id=? AND t.attempt_id=?",
            OWNER,
            attemptId));
  }

  private static String undoReceiptSnapshot(String receiptId) throws Exception {
    return queryString(
        "SELECT r.xmin::text || '|' || to_jsonb(r)::text "
            + "FROM local_draft_undo_receipts r "
            + "WHERE principal_id=? AND receipt_id=?",
        OWNER,
        receiptId);
  }

  private static void assertUndoReceiptImmutable(String receiptId) throws Exception {
    assertUndoReceiptMutationRejected(
        "UPDATE local_draft_undo_receipts SET outcome=outcome "
            + "WHERE principal_id=? AND receipt_id=?",
        receiptId);
    assertUndoReceiptMutationRejected(
        "DELETE FROM local_draft_undo_receipts WHERE principal_id=? AND receipt_id=?",
        receiptId);
  }

  private static void assertUndoReceiptMutationRejected(String sql, String receiptId)
      throws Exception {
    SQLException rejection = null;
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, OWNER);
      statement.setString(2, receiptId);
      statement.executeUpdate();
    } catch (SQLException failure) {
      rejection = failure;
    }
    assertNotNull(rejection, "logical Undo Receipt must reject UPDATE and DELETE");
    assertEquals("23514", rejection.getSQLState());
  }

  private static Object query(String sql, Object... values) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getObject(1);
      }
    }
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

  private record RunningApplication(Process process, int port, Path log) {}

  private record ArtifactHead(String artifactId, int version, String hash) {}

  private record ActiveDraftFixture(
      ArtifactHead artifact,
      String attemptId,
      String draftId,
      String creationReceiptId) {}

  private record ProblemShape(String type, String title, int status, String detail) {}

  private record DatabaseMutationDigest(
      String attempts,
      String transitions,
      String drafts,
      String localReceipts,
      String localUndoReceipts,
      String legacyReceipts) {}

  private record RetainedTruthSnapshot(
      int captureCount,
      String captureRow,
      int artifactCount,
      String artifactRow,
      int artifactVersionCount,
      String artifactVersionRows,
      String localDraftRow,
      String creationReceiptRow,
      String actionAttemptRow,
      String actionAttemptTransitionRows) {}

  private record ScopeHead(
      String schema,
      String hash,
      String executionRoute,
      String policyVersion,
      String connector,
      String audience,
      String accountRef,
      int maxCalls) {}
}
