package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.core.domain.ContentHashes;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Packaged-app acceptance for separating exact owner approval from local action execution.
 *
 * <p>An approval may durably create only one {@code PLANNED} attempt. It must not claim the
 * capability, contact the simulated provider, create an effect, or manufacture a Receipt.
 */
@Testcontainers
class ExactLocalApprovalHttpIT {

  private static final String OWNER = "exact-approval-owner";
  private static final String OTHER_OWNER = "other-exact-approval-owner";
  private static final String UNREACHABLE_PROVIDER = "http://127.0.0.1:1";

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
  void exactApprovalPlansOnceWithoutDispatchAndSurvivesReplayRaceLossAndRestart()
      throws Exception {
    List<RunningApplication> applications = new ArrayList<>();
    RunningApplication first = startApplication(OWNER);
    applications.add(first);
    long firstPid = first.process().pid();

    try {
      ArtifactHead approved = createArtifactThroughAgentDraft(first, "initial");
      String approvalNonce = "exact-local-approval-001";
      ScopeHead approvedScope = approvalScope(first.port(), approved);
      String request = approvalRequest(approved, approvalNonce, approvedScope);

      HttpResponse<String> created =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts/" + approved.artifactId() + "/action-approvals",
              request);
      if (created.statusCode() == 404) {
        System.out.println("EXACT_LOCAL_APPROVAL_SURFACE_MISSING");
      }
      assertEquals(201, created.statusCode(), "EXACT_LOCAL_APPROVAL_SURFACE_MISSING");
      String attemptId = validatePlannedApproval(created, approved, OWNER);
      String location = approvalLocation(created, attemptId);
      String createdBody = created.body();
      assertStoredApproval(attemptId, approvalNonce, approved, createdBody);
      assertApprovalRows(approvalNonce, 1, 0, List.of("PLANNED"), 0);

      HttpResponse<String> replayed =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts/" + approved.artifactId() + "/action-approvals",
              request);
      assertEquals(200, replayed.statusCode(), replayed::body);
      assertEquals(attemptId, validatePlannedApproval(replayed, approved, OWNER));
      assertEquals(location, replayed.headers().firstValue("Location").orElse(null));
      assertEquals(createdBody, replayed.body(), "exact replay must return the canonical approval");
      assertApprovalRows(approvalNonce, 1, 0, List.of("PLANNED"), 0);
      assertStoredApproval(attemptId, approvalNonce, approved, replayed.body());

      ArtifactHead changedRequest = createArtifactThroughAgentDraft(first, "nonce-conflict");
      ScopeHead changedScope = approvalScope(first.port(), changedRequest);
      HttpResponse<String> nonceConflict =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts/" + changedRequest.artifactId() + "/action-approvals",
              approvalRequest(changedRequest, approvalNonce, changedScope));
      assertEquals(409, nonceConflict.statusCode(), nonceConflict::body);
      assertApprovalRows(approvalNonce, 1, 0, List.of("PLANNED"), 0);

      ArtifactHead stale = createArtifactThroughAgentDraft(first, "stale");
      ScopeHead staleScope = approvalScope(first.port(), stale);
      HttpResponse<String> revised =
          sendJson(
              first.port(),
              "PUT",
              "/api/v1/artifacts/" + stale.artifactId(),
              revisionRequest("synthetic revised artifact", stale));
      assertEquals(200, revised.statusCode(), revised::body);
      assertEquals(2, number(revised.body(), "$.currentVersion"));
      String staleNonce = "exact-local-approval-stale-001";
      HttpResponse<String> staleApproval =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts/" + stale.artifactId() + "/action-approvals",
              approvalRequest(stale, staleNonce, staleScope));
      assertApprovalStale(staleApproval);
      assertEquals(0, attemptRowCount(staleNonce));

      RunningApplication contender = startApplication(OWNER);
      applications.add(contender);
      assertNotEquals(first.process().pid(), contender.process().pid());
      ArtifactHead staleRaceArtifact = createArtifactThroughAgentDraft(first, "stale-race");
      assertRevisionWinsBeforeApprovalCanCommit(
          first,
          contender,
          staleRaceArtifact,
          "exact-local-approval-stale-race-001");

      RunningApplication foreign = startApplication(OTHER_OWNER);
      applications.add(foreign);
      String foreignNonce = "exact-local-approval-foreign-001";
      HttpResponse<String> foreignArtifact =
          sendJson(
              foreign.port(),
              "POST",
              "/api/v1/artifacts/" + approved.artifactId() + "/action-approvals",
              approvalRequest(approved, foreignNonce, approvedScope));
      HttpResponse<String> missingArtifact =
          sendJson(
              foreign.port(),
              "POST",
              "/api/v1/artifacts/missing-artifact/action-approvals",
              approvalRequest(
                  new ArtifactHead("missing-artifact", approved.version(), approved.hash()),
                  foreignNonce,
                  approvedScope));
      assertSameNotFound(foreignArtifact, missingArtifact);
      assertEquals(0, attemptRowCount(foreignNonce));
      stop(foreign);

      ArtifactHead racedArtifact = createArtifactThroughAgentDraft(first, "race");
      String raceNonce = "exact-local-approval-race-001";
      String raceRequest =
          approvalRequest(racedArtifact, raceNonce, approvalScope(first.port(), racedArtifact));
      List<HttpResponse<String>> raced =
          new ArrayList<>(
              race(
                  () ->
                      sendJson(
                          first.port(),
                          "POST",
                          "/api/v1/artifacts/"
                              + racedArtifact.artifactId()
                              + "/action-approvals",
                          raceRequest),
                  () ->
                      sendJson(
                          contender.port(),
                          "POST",
                          "/api/v1/artifacts/"
                              + racedArtifact.artifactId()
                              + "/action-approvals",
                          raceRequest)));
      raced.sort(Comparator.comparingInt(HttpResponse::statusCode));
      assertEquals(List.of(200, 201), raced.stream().map(HttpResponse::statusCode).toList());
      String raceAttempt = validatePlannedApproval(raced.get(0), racedArtifact, OWNER);
      assertEquals(raceAttempt, validatePlannedApproval(raced.get(1), racedArtifact, OWNER));
      assertEquals(raced.get(0).body(), raced.get(1).body());
      assertEquals(
          raced.get(0).headers().firstValue("Location"),
          raced.get(1).headers().firstValue("Location"));
      assertApprovalRows(raceNonce, 1, 0, List.of("PLANNED"), 0);
      assertStoredApproval(raceAttempt, raceNonce, racedArtifact, raced.get(0).body());
      stop(contender);

      ArtifactHead responseLossArtifact = createArtifactThroughAgentDraft(first, "response-loss");
      String responseLossNonce = "exact-local-approval-response-loss-001";
      String responseLossRequest =
          approvalRequest(
              responseLossArtifact,
              responseLossNonce,
              approvalScope(first.port(), responseLossArtifact));
      fireAndForgetJson(
          first.port(),
          "/api/v1/artifacts/"
              + responseLossArtifact.artifactId()
              + "/action-approvals",
          responseLossRequest);
      String responseLossAttempt = awaitAttemptId(responseLossNonce);
      HttpResponse<String> recovered =
          sendJson(
              first.port(),
              "GET",
              "/api/v1/action-approvals/" + responseLossAttempt,
              null);
      assertEquals(200, recovered.statusCode(), recovered::body);
      assertEquals(
          responseLossAttempt,
          validatePlannedApproval(recovered, responseLossArtifact, OWNER));
      HttpResponse<String> recoveredReplay =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts/"
                  + responseLossArtifact.artifactId()
                  + "/action-approvals",
              responseLossRequest);
      assertEquals(200, recoveredReplay.statusCode(), recoveredReplay::body);
      assertEquals(recovered.body(), recoveredReplay.body());
      assertEquals(
          "/api/v1/action-approvals/" + responseLossAttempt,
          recoveredReplay.headers().firstValue("Location").orElse(null));
      assertApprovalRows(responseLossNonce, 1, 0, List.of("PLANNED"), 0);
      assertStoredApproval(
          responseLossAttempt, responseLossNonce, responseLossArtifact, recovered.body());

      stop(first);
      RunningApplication restarted = startApplication(OWNER);
      applications.add(restarted);
      assertNotEquals(firstPid, restarted.process().pid());
      HttpResponse<String> afterRestart =
          sendJson(restarted.port(), "GET", location, null);
      assertEquals(200, afterRestart.statusCode(), afterRestart::body);
      assertEquals(createdBody, afterRestart.body());
      assertEquals(attemptId, validatePlannedApproval(afterRestart, approved, OWNER));
      assertStoredApproval(attemptId, approvalNonce, approved, afterRestart.body());

      assertEquals(3, queryInt("SELECT count(*) FROM action_attempts"));
      assertEquals(
          3,
          queryInt(
              "SELECT count(*) FROM action_attempts "
                  + "WHERE status = 'PLANNED' AND capability_used_calls = 0"));
      assertEquals(3, queryInt("SELECT count(DISTINCT plan_id) FROM action_attempts"));
      assertEquals(3, queryInt("SELECT count(DISTINCT approval_id) FROM action_attempts"));
      assertEquals(3, queryInt("SELECT count(*) FROM action_attempt_transitions"));
      assertEquals(
          0,
          queryInt(
              "SELECT count(*) FROM action_attempt_transitions "
                  + "WHERE sequence <> 1 OR from_status IS NOT NULL "
                  + "OR to_status <> 'PLANNED' OR capability_use_delta <> 0"));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));

      System.out.printf(
          "EXACT_LOCAL_APPROVAL_RECEIPT first=201 replay=200 conflict=409 "
              + "stale=%d foreign=404 race=200/201 responseLossRecovered=true restartGet=200 "
              + "attempts=3 plans=3 approvals=3 transitions=PLANNED usedCalls=0 receipts=0 "
              + "providerBase=unreachable%n",
          staleApproval.statusCode());
    } finally {
      stopAll(applications);
    }
  }

  private static void assertRevisionWinsBeforeApprovalCanCommit(
      RunningApplication approvalApplication,
      RunningApplication revisionApplication,
      ArtifactHead approvedBase,
      String approvalNonce)
      throws Exception {
    int receiptsBefore = queryInt("SELECT count(*) FROM action_receipts");
    ScopeHead approvedScope = approvalScope(approvalApplication.port(), approvedBase);
    String revisionContent = "synthetic revision wins approval race";
    String revisionHash = ContentHashes.sha256(revisionContent);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<HttpResponse<String>> approvalFuture = null;
    int blockedBackendPid;
    try {
      try (var revisionConnection =
          DriverManager.getConnection(
              postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        revisionConnection.setTransactionIsolation(
            java.sql.Connection.TRANSACTION_READ_COMMITTED);
        revisionConnection.setAutoCommit(false);
        try {
          assertEquals(
              1,
              updateArtifactHeadForRace(
                  revisionConnection, approvedBase, revisionHash),
              "race fixture must CAS exactly one Artifact head");
          insertArtifactRevisionForRace(
              revisionConnection, approvedBase, revisionContent, revisionHash);

          approvalFuture =
              executor.submit(
                  () ->
                      sendJson(
                          approvalApplication.port(),
                          "POST",
                          "/api/v1/artifacts/"
                              + approvedBase.artifactId()
                              + "/action-approvals",
                          approvalRequest(approvedBase, approvalNonce, approvedScope)));
          blockedBackendPid =
              awaitBlockedApprovalArtifactHeadLock(approvalApplication.applicationName());
          revisionConnection.commit();
        } catch (Exception | AssertionError failure) {
          revisionConnection.rollback();
          throw failure;
        }
      }

      HttpResponse<String> approval = approvalFuture.get(10, TimeUnit.SECONDS);
      HttpResponse<String> current =
          sendJson(
              revisionApplication.port(),
              "GET",
              "/api/v1/artifacts/" + approvedBase.artifactId(),
              null);
      assertEquals(200, current.statusCode(), current::body);
      int currentVersion = number(current.body(), "$.currentVersion");
      assertEquals(approvedBase.version() + 1, currentVersion);
      assertEquals(revisionHash, JsonPath.<String>read(current.body(), "$.currentHash"));
      assertEquals(2, number(current.body(), "$.versions.length()"));
      assertEquals(approvedBase.version(), number(current.body(), "$.versions[0].version"));
      assertEquals(approvedBase.hash(), JsonPath.read(current.body(), "$.versions[0].contentHash"));
      assertEquals(
          approvedBase.version() + 1,
          number(current.body(), "$.versions[1].version"));
      assertEquals(revisionContent, JsonPath.read(current.body(), "$.versions[1].content"));
      assertEquals(revisionHash, JsonPath.read(current.body(), "$.versions[1].contentHash"));
      assertEquals(approvedBase.version(), number(current.body(), "$.versions[1].baseVersion"));
      assertEquals(approvedBase.hash(), JsonPath.read(current.body(), "$.versions[1].baseHash"));

      int attemptRows = attemptRowCount(approvalNonce);
      int capabilityUses =
          queryInt(
              "SELECT COALESCE(sum(capability_used_calls), 0) FROM action_attempts "
                  + "WHERE principal_id = ? AND idempotency_key = ?",
              OWNER,
              approvalNonce);
      int receiptRows =
          queryInt(
              "SELECT count(*) FROM action_receipts r JOIN action_attempts a "
                  + "ON a.principal_id = r.principal_id AND a.attempt_id = r.attempt_id "
                  + "WHERE a.principal_id = ? AND a.idempotency_key = ?",
              OWNER,
              approvalNonce);
      int receiptsAfter = queryInt("SELECT count(*) FROM action_receipts");
      if (approval.statusCode() == 201) {
        System.out.printf(
            "EXACT_LOCAL_APPROVAL_STALE_RACE_ACCEPTED backendPid=%d approval=201 "
                + "currentVersion=%d attemptRows=%d usedCalls=%d receiptRows=%d%n",
            blockedBackendPid,
            currentVersion,
            attemptRows,
            capabilityUses,
            receiptRows);
      }
      assertTrue(
          approval.statusCode() == 409 || approval.statusCode() == 412,
          () ->
              "EXACT_LOCAL_APPROVAL_STALE_RACE_ACCEPTED: approval="
                  + approval.statusCode()
                  + " currentVersion="
                  + currentVersion
                  + " attemptRows="
                  + attemptRows
                  + " usedCalls="
                  + capabilityUses
                  + " receiptRows="
                  + receiptRows);
      assertEquals(0, attemptRows, "stale approval race must leave no ActionAttempt");
      assertEquals(0, capabilityUses, "stale approval race must not claim provider capability");
      assertEquals(0, receiptRows, "stale approval race must not create a Receipt");
      assertEquals(receiptsBefore, receiptsAfter, "stale approval race changed Receipt rows");
    } finally {
      if (approvalFuture != null && !approvalFuture.isDone()) {
        approvalFuture.cancel(true);
      }
      executor.shutdownNow();
      assertTrue(
          executor.awaitTermination(5, TimeUnit.SECONDS),
          "stale approval race child did not terminate");
    }
  }

  private static int updateArtifactHeadForRace(
      java.sql.Connection connection, ArtifactHead base, String revisionHash)
      throws Exception {
    String sql =
        """
        UPDATE artifacts
        SET current_version = ?,
            current_hash = ?
        WHERE principal_id = ?
          AND artifact_id = ?
          AND current_version = ?
          AND current_hash = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, base.version() + 1);
      statement.setString(2, revisionHash);
      statement.setString(3, OWNER);
      statement.setString(4, base.artifactId());
      statement.setInt(5, base.version());
      statement.setString(6, base.hash());
      return statement.executeUpdate();
    }
  }

  private static void insertArtifactRevisionForRace(
      java.sql.Connection connection,
      ArtifactHead base,
      String revisionContent,
      String revisionHash)
      throws Exception {
    String sql =
        """
        INSERT INTO artifact_versions (
            principal_id, artifact_id, version, content, content_hash,
            base_version, base_hash, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, OWNER);
      statement.setString(2, base.artifactId());
      statement.setInt(3, base.version() + 1);
      statement.setString(4, revisionContent);
      statement.setString(5, revisionHash);
      statement.setInt(6, base.version());
      statement.setString(7, base.hash());
      statement.setTimestamp(8, java.sql.Timestamp.from(Instant.now()));
      assertEquals(1, statement.executeUpdate(), "race fixture must insert exact v2 lineage");
    }
  }

  private static int awaitBlockedApprovalArtifactHeadLock(String applicationName)
      throws Exception {
    String sql =
        """
        SELECT activity.pid
        FROM pg_catalog.pg_stat_activity activity
        WHERE activity.datname = current_database()
          AND activity.usename = ?
          AND activity.application_name = ?
          AND activity.state = 'active'
          AND activity.wait_event_type = 'Lock'
          AND activity.query LIKE '%FROM artifacts%'
          AND activity.query LIKE '%FOR UPDATE%'
          AND EXISTS (
                SELECT 1
                FROM pg_catalog.pg_locks relation_lock
                JOIN pg_catalog.pg_class relation
                  ON relation.oid = relation_lock.relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE relation_lock.pid = activity.pid
                  AND relation_lock.locktype = 'relation'
                  AND relation_lock.mode = 'RowShareLock'
                  AND relation_lock.granted
                  AND namespace.nspname = 'public'
                  AND relation.relname = 'artifacts'
                  AND relation.relkind = 'r')
        """;
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    try (var connection =
            DriverManager.getConnection(
                postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, POSTGRES.getUsername());
      statement.setString(2, applicationName);
      while (System.nanoTime() < deadline) {
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            int backendPid = resultSet.getInt(1);
            assertFalse(
                resultSet.next(),
                "more than one exact approval backend waited on the Artifact head lock");
            return backendPid;
          }
        }
        Thread.sleep(25);
      }
    }
    throw new AssertionError(
        "stale approval race did not reach the exact Artifact FOR UPDATE boundary");
  }

  private static ArtifactHead createArtifactThroughAgentDraft(
      RunningApplication application, String suffix) throws Exception {
    HttpResponse<String> captured =
        sendJson(
            application.port(),
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "exact-approval-capture-%s",
              "content": "synthetic exact approval source %s",
              "sourceType": "TEXT",
              "sourceRef": "exact-local-approval:http-it",
              "dataClass": "PERSONAL"
            }
            """
                .formatted(suffix, suffix));
    assertEquals(201, captured.statusCode(), captured::body);
    String captureId = JsonPath.read(captured.body(), "$.captureId");

    HttpResponse<String> drafted =
        sendJson(
            application.port(),
            "POST",
            "/api/v1/agent-drafts",
            """
            {
              "captureId": "%s",
              "intent": "Create one editable local draft for exact approval"
            }
            """
                .formatted(captureId));
    assertEquals(201, drafted.statusCode(), drafted::body);
    assertEquals("SUCCEEDED", JsonPath.read(drafted.body(), "$.result.status"));
    assertEquals("fake-model-v1", JsonPath.read(drafted.body(), "$.result.resolvedModel"));
    assertEquals(0.0, JsonPath.<Number>read(drafted.body(), "$.result.costUsd").doubleValue());
    assertEquals(List.of(), JsonPath.read(drafted.body(), "$.result.receiptRefs"));
    String artifactLocation = drafted.headers().firstValue("Location").orElse(null);
    assertNotNull(artifactLocation);
    assertTrue(artifactLocation.startsWith("/api/v1/artifacts/"));

    HttpResponse<String> artifact =
        sendJson(application.port(), "GET", artifactLocation, null);
    assertEquals(200, artifact.statusCode(), artifact::body);
    assertEquals(captureId, JsonPath.read(artifact.body(), "$.captureId"));
    assertEquals(1, number(artifact.body(), "$.currentVersion"));
    return new ArtifactHead(
        JsonPath.read(artifact.body(), "$.artifactId"),
        number(artifact.body(), "$.currentVersion"),
        JsonPath.read(artifact.body(), "$.currentHash"));
  }

  private static String validatePlannedApproval(
      HttpResponse<String> response, ArtifactHead expected, String expectedActor) {
    assertPrivateNoStore(response);
    String attemptId = JsonPath.read(response.body(), "$.attemptId");
    assertTrue(attemptId != null && !attemptId.isBlank());
    assertEquals("PLANNED", JsonPath.read(response.body(), "$.status"));
    assertEquals(expected.artifactId(), JsonPath.read(response.body(), "$.plan.artifactId"));
    assertEquals(expected.version(), number(response.body(), "$.plan.artifactVersion"));
    assertEquals(expected.hash(), JsonPath.read(response.body(), "$.plan.artifactHash"));
    String planId = JsonPath.read(response.body(), "$.plan.planId");
    String planHash = JsonPath.read(response.body(), "$.plan.planHash");
    assertTrue(planId != null && !planId.isBlank());
    assertTrue(planHash.matches("[0-9a-f]{64}"));

    String decisionId = JsonPath.read(response.body(), "$.approval.decisionId");
    assertTrue(decisionId != null && !decisionId.isBlank());
    assertEquals("APPROVED", JsonPath.read(response.body(), "$.approval.decision"));
    assertEquals(expectedActor, JsonPath.read(response.body(), "$.approval.actor"));
    Instant decidedAt = Instant.parse(JsonPath.read(response.body(), "$.approval.decidedAt"));
    assertEquals(planId, JsonPath.read(response.body(), "$.approval.planId"));
    assertEquals(planHash, JsonPath.read(response.body(), "$.approval.planHash"));
    assertEquals(expected.hash(), JsonPath.read(response.body(), "$.approval.artifactHash"));

    assertEquals(
        "emergeos.action-approval-scope.v1",
        JsonPath.read(response.body(), "$.scopeSchema"));
    assertTrue(JsonPath.<String>read(response.body(), "$.scopeHash").matches("[0-9a-f]{64}"));
    assertEquals(
        "CONFIGURED_LOCAL_PRINCIPAL",
        JsonPath.read(response.body(), "$.approvalPrincipal.basis"));
    assertEquals(expectedActor, JsonPath.read(response.body(), "$.approvalPrincipal.configuredPrincipalId"));
    assertEquals(
        "EXPLICIT_LOCAL_OWNER_INPUT",
        JsonPath.read(response.body(), "$.provenance.approvalOrigin"));
    assertEquals(
        "LOCAL_DRAFTBOX_V1",
        JsonPath.read(response.body(), "$.provenance.executionRoute"));
    assertEquals(expected.artifactId(), JsonPath.read(response.body(), "$.artifact.artifactId"));
    assertEquals(expected.version(), number(response.body(), "$.artifact.artifactVersion"));
    assertEquals(expected.hash(), JsonPath.read(response.body(), "$.artifact.artifactHash"));
    assertEquals("NOT_EXECUTED", JsonPath.read(response.body(), "$.executionState"));

    assertEquals(0, number(response.body(), "$.capability.usedCalls"));
    assertEquals(1, number(response.body(), "$.transitions.length()"));
    assertEquals(1, number(response.body(), "$.transitions[0].sequence"));
    assertNull(JsonPath.read(response.body(), "$.transitions[0].fromStatus"));
    assertEquals("PLANNED", JsonPath.read(response.body(), "$.transitions[0].toStatus"));
    Instant plannedAt = Instant.parse(JsonPath.read(response.body(), "$.transitions[0].occurredAt"));
    assertEquals(decidedAt, plannedAt, "approval and PLANNED transition must be one decision");
    assertNull(JsonPath.read(response.body(), "$.receipt"));
    return attemptId;
  }

  private static String approvalLocation(HttpResponse<String> response, String attemptId) {
    String location = response.headers().firstValue("Location").orElse(null);
    assertEquals("/api/v1/action-approvals/" + attemptId, location);
    return location;
  }

  private static void assertStoredApproval(
      String attemptId, String approvalNonce, ArtifactHead expected, String responseBody)
      throws Exception {
    StoredApproval stored = storedApproval(attemptId);
    assertEquals(attemptId, stored.attemptId());
    assertEquals(approvalNonce, stored.approvalNonce());
    assertEquals("PLANNED", stored.status());
    assertEquals(0, stored.usedCalls());
    assertEquals(expected.artifactId(), stored.artifactId());
    assertEquals(expected.version(), stored.artifactVersion());
    assertEquals(expected.hash(), stored.artifactHash());
    assertEquals(stored.planId(), JsonPath.read(responseBody, "$.plan.planId"));
    assertEquals(stored.planHash(), JsonPath.read(responseBody, "$.plan.planHash"));
    assertEquals(stored.approvalId(), JsonPath.read(responseBody, "$.approval.decisionId"));
    assertEquals(stored.approvedAt(), Instant.parse(JsonPath.read(responseBody, "$.approval.decidedAt")));
    assertEquals(1, attemptRowCount(approvalNonce));
    assertEquals(
        "EXPLICIT_LOCAL_OWNER_INPUT",
        queryString(
            "SELECT approval_origin FROM action_attempts WHERE principal_id = ? AND attempt_id = ?",
            OWNER,
            attemptId));
    assertEquals(
        "LOCAL_DRAFTBOX_V1",
        queryString(
            "SELECT execution_route FROM action_attempts WHERE principal_id = ? AND attempt_id = ?",
            OWNER,
            attemptId));
    assertEquals(
        JsonPath.read(responseBody, "$.scopeHash"),
        queryString(
            "SELECT scope_hash FROM action_attempts WHERE principal_id = ? AND attempt_id = ?",
            OWNER,
            attemptId));
    assertEquals(
        1,
        queryInt(
            "SELECT count(DISTINCT plan_id) FROM action_attempts "
                + "WHERE principal_id = ? AND idempotency_key = ?",
            OWNER,
            approvalNonce));
    assertEquals(
        1,
        queryInt(
            "SELECT count(DISTINCT approval_id) FROM action_attempts "
                + "WHERE principal_id = ? AND idempotency_key = ?",
            OWNER,
            approvalNonce));
  }

  private static void assertApprovalRows(
      String approvalNonce,
      int expectedAttempts,
      int expectedCapabilityUses,
      List<String> expectedTransitions,
      int expectedReceipts)
      throws Exception {
    assertEquals(expectedAttempts, attemptRowCount(approvalNonce));
    if (expectedAttempts == 0) {
      return;
    }
    String attemptId = queryString(
        "SELECT attempt_id FROM action_attempts "
            + "WHERE principal_id = ? AND idempotency_key = ?",
        OWNER,
        approvalNonce);
    assertEquals(
        expectedCapabilityUses,
        queryInt(
            "SELECT capability_used_calls FROM action_attempts "
                + "WHERE principal_id = ? AND attempt_id = ?",
            OWNER,
            attemptId));
    assertEquals(expectedTransitions, transitionStates(attemptId));
    assertEquals(
        expectedReceipts,
        queryInt(
            "SELECT count(*) FROM action_receipts "
                + "WHERE principal_id = ? AND attempt_id = ?",
            OWNER,
            attemptId));
  }

  private static void assertSameNotFound(
      HttpResponse<String> foreign, HttpResponse<String> missing) {
    assertPrivateNoStore(foreign);
    assertPrivateNoStore(missing);
    assertEquals(404, foreign.statusCode(), foreign::body);
    assertEquals(404, missing.statusCode(), missing::body);
    assertEquals(
        foreign.headers().firstValue("Content-Type"),
        missing.headers().firstValue("Content-Type"));
    assertEquals(normalizedProblem(foreign.body()), normalizedProblem(missing.body()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(String json) {
    Map<String, Object> problem = JsonPath.parse(json).read("$");
    problem.remove("instance");
    return problem;
  }

  private static ScopeHead approvalScope(int port, ArtifactHead artifact) throws Exception {
    HttpResponse<String> response =
        sendJson(
            port,
            "GET",
            "/api/v1/artifacts/"
                + artifact.artifactId()
                + "/action-approval-scope?artifactVersion="
                + artifact.version()
                + "&artifactHash="
                + artifact.hash(),
            null);
    assertEquals(200, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    assertEquals(
        "emergeos.action-approval-scope.v1", JsonPath.read(response.body(), "$.scopeSchema"));
    String scopeHash = JsonPath.read(response.body(), "$.scopeHash");
    assertTrue(scopeHash.matches("[0-9a-f]{64}"));
    assertEquals(artifact.artifactId(), JsonPath.read(response.body(), "$.artifact.artifactId"));
    assertEquals(artifact.version(), number(response.body(), "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(response.body(), "$.artifact.artifactHash"));
    assertEquals("NOT_EXECUTED", JsonPath.read(response.body(), "$.executionState"));
    return new ScopeHead("emergeos.action-approval-scope.v1", scopeHash);
  }

  private static String approvalRequest(
      ArtifactHead artifact, String approvalNonce, ScopeHead scope) {
    return """
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
            scope.hash());
  }

  private static void assertApprovalStale(HttpResponse<String> response) {
    assertEquals(412, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
    assertEquals(412, number(response.body(), "$.status"));
    assertEquals("urn:emergeos:problem:approval-stale", JsonPath.read(response.body(), "$.type"));
    assertEquals("Action approval stale", JsonPath.read(response.body(), "$.title"));
  }

  private static void assertPrivateNoStore(HttpResponse<?> response) {
    assertEquals(
        "private, no-store", response.headers().firstValue("Cache-Control").orElse(null));
  }

  private static String revisionRequest(String content, ArtifactHead base) {
    return """
        {
          "content": "%s",
          "expectedBaseVersion": %d,
          "expectedBaseHash": "%s"
        }
        """
        .formatted(content, base.version(), base.hash());
  }

  private static RunningApplication startApplication(String principalId) throws Exception {
    int port = availableLoopbackPort();
    String applicationName = "exact-approval-http-it-" + port;
    Path log = Files.createTempFile("emerge-exact-approval-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + principalId);
    command.add("--emerge.simulated-provider.base-url=" + UNREACHABLE_PROVIDER);
    command.add("--emerge.simulated-provider.request-timeout=PT0.2S");
    command.add(
        "--spring.datasource.url="
            + postgresJdbcUrl()
            + "&ApplicationName="
            + applicationName);
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    RunningApplication application =
        new RunningApplication(process, port, log, applicationName);
    try {
      awaitApplicationLive(application);
      return application;
    } catch (Exception | AssertionError startupFailure) {
      stopAfterFailure(application, startupFailure);
      throw startupFailure;
    }
  }

  private static void awaitApplicationLive(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError(
            "packaged application exited before liveness:\n" + readLog(application.log()));
      }
      try {
        HttpResponse<String> liveness =
            sendJson(application.port(), "GET", "/actuator/health/liveness", null);
        if (liveness.statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become live:\n" + readLog(application.log()));
  }

  private static HttpResponse<String> sendJson(
      int port, String method, String path, String body)
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

  private static void fireAndForgetJson(int port, String path, String body) throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String headers =
        "POST "
            + path
            + " HTTP/1.1\r\nHost: 127.0.0.1:"
            + port
            + "\r\nContent-Type: application/json\r\nContent-Length: "
            + bodyBytes.length
            + "\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(bodyBytes);
      socket.getOutputStream().flush();
      socket.shutdownOutput();
    }
  }

  private static String awaitAttemptId(String approvalNonce) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      String attemptId =
          queryOptionalString(
              "SELECT attempt_id FROM action_attempts "
                  + "WHERE principal_id = ? AND idempotency_key = ?",
              OWNER,
              approvalNonce);
      if (attemptId != null) {
        return attemptId;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("approval response loss did not leave one durable PLANNED attempt");
  }

  private static List<HttpResponse<String>> race(
      RequestCall first, RequestCall second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<HttpResponse<String>> firstFuture =
          executor.submit(() -> awaitAndCall(ready, start, first));
      Future<HttpResponse<String>> secondFuture =
          executor.submit(() -> awaitAndCall(ready, start, second));
      assertTrue(ready.await(5, TimeUnit.SECONDS), "approval contenders were not ready");
      start.countDown();
      return List.of(
          firstFuture.get(15, TimeUnit.SECONDS),
          secondFuture.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static HttpResponse<String> awaitAndCall(
      CountDownLatch ready, CountDownLatch start, RequestCall call) throws Exception {
    ready.countDown();
    assertTrue(start.await(5, TimeUnit.SECONDS));
    return call.call();
  }

  private static int attemptRowCount(String approvalNonce) throws Exception {
    return queryInt(
        "SELECT count(*) FROM action_attempts "
            + "WHERE principal_id = ? AND idempotency_key = ?",
        OWNER,
        approvalNonce);
  }

  private static List<String> transitionStates(String attemptId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT to_status FROM action_attempt_transitions "
                    + "WHERE principal_id = ? AND attempt_id = ? ORDER BY sequence")) {
      statement.setString(1, OWNER);
      statement.setString(2, attemptId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> states = new ArrayList<>();
        while (resultSet.next()) {
          states.add(resultSet.getString(1));
        }
        return states;
      }
    }
  }

  private static StoredApproval storedApproval(String attemptId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT attempt_id, idempotency_key, status, capability_used_calls, "
                    + "plan_id, plan_hash, artifact_id, artifact_version, artifact_hash, "
                    + "approval_id, approved_at FROM action_attempts "
                    + "WHERE principal_id = ? AND attempt_id = ?")) {
      statement.setString(1, OWNER);
      statement.setString(2, attemptId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "durable approval row missing");
        StoredApproval stored =
            new StoredApproval(
                resultSet.getString("attempt_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("status"),
                resultSet.getInt("capability_used_calls"),
                resultSet.getString("plan_id"),
                resultSet.getString("plan_hash"),
                resultSet.getString("artifact_id"),
                resultSet.getInt("artifact_version"),
                resultSet.getString("artifact_hash"),
                resultSet.getString("approval_id"),
                resultSet.getTimestamp("approved_at").toInstant());
        assertFalse(resultSet.next(), "attempt identity returned duplicate rows");
        return stored;
      }
    }
  }

  private static int queryInt(String sql, String... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "count query returned no row: " + sql);
        int value = resultSet.getInt(1);
        assertFalse(resultSet.next(), "count query returned multiple rows: " + sql);
        return value;
      }
    }
  }

  private static String queryString(String sql, String... parameters) throws Exception {
    String result = queryOptionalString(sql, parameters);
    assertNotNull(result, "query returned no row: " + sql);
    return result;
  }

  private static String queryOptionalString(String sql, String... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        String result = resultSet.next() ? resultSet.getString(1) : null;
        assertFalse(resultSet.next(), "query returned multiple rows: " + sql);
        return result;
      }
    }
  }

  private static void bind(PreparedStatement statement, String... parameters) throws Exception {
    for (int index = 0; index < parameters.length; index++) {
      statement.setString(index + 1, parameters[index]);
    }
  }

  private static String postgresJdbcUrl() {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    int queryStart = jdbcUrl.indexOf('?');
    if (queryStart >= 0) {
      for (String parameter : jdbcUrl.substring(queryStart + 1).split("&")) {
        int equals = parameter.indexOf('=');
        String name = equals >= 0 ? parameter.substring(0, equals) : parameter;
        if (name.equalsIgnoreCase("sslmode")) {
          return jdbcUrl;
        }
      }
    }
    return jdbcUrl + (queryStart >= 0 ? "&" : "?") + "sslmode=disable";
  }

  private static int number(String json, String path) {
    return ((Number) JsonPath.read(json, path)).intValue();
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stopAll(List<RunningApplication> applications) throws Exception {
    Throwable failure = null;
    for (RunningApplication application : applications) {
      try {
        stop(application);
      } catch (Exception | AssertionError cleanupFailure) {
        if (failure == null) {
          failure = cleanupFailure;
        } else {
          failure.addSuppressed(cleanupFailure);
        }
      }
    }
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof Error error) {
      throw error;
    }
  }

  private static void stopAfterFailure(
      RunningApplication application, Throwable originalFailure) {
    try {
      stop(application);
    } catch (Exception | AssertionError cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    if (application.process().isAlive()) {
      application.process().destroyForcibly();
      assertTrue(
          application.process().waitFor(10, TimeUnit.SECONDS),
          "packaged application did not terminate");
    }
    Files.deleteIfExists(application.log());
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException readFailure) {
      return "unable to read application log: " + readFailure.getClass().getSimpleName();
    }
  }

  @FunctionalInterface
  private interface RequestCall {
    HttpResponse<String> call() throws Exception;
  }

  private record ArtifactHead(String artifactId, int version, String hash) {}

  private record ScopeHead(String schema, String hash) {}

  private record StoredApproval(
      String attemptId,
      String approvalNonce,
      String status,
      int usedCalls,
      String planId,
      String planHash,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String approvalId,
      Instant approvedAt) {}

  private record RunningApplication(
      Process process, int port, Path log, String applicationName) {}
}
