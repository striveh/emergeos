package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.adapters.postgres.PostgresActionAttemptStore;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.port.ActionAttemptStore;
import java.io.IOException;
import java.net.InetAddress;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Packaged-app crash/restart acceptance for the PostgreSQL-canonical local Draftbox effect.
 *
 * <p>The fault fixture is deliberately outside production code. An immediate PostgreSQL constraint
 * trigger blocks the final Receipt insert before the transaction manager can issue COMMIT; killing
 * the packaged JVM must therefore leave the exact V2 approval PLANNED. A separate raw TCP request
 * loses its response only after the same database has committed, so a fresh JVM must observe and
 * replay one canonical terminal result.
 */
@Testcontainers
class RealLocalDraftboxCrashHttpIT {

  private static final String OWNER = "real-local-draftbox-crash-owner";
  private static final String SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
  private static final String UNREACHABLE_PROVIDER = "http://127.0.0.1:1";
  private static final long PRECOMMIT_LOCK = 18_202_608_150_001L;
  private static final String PRECOMMIT_FUNCTION = "real_local_draftbox_crash_precommit_v1";
  private static final String PRECOMMIT_TRIGGER = "zz_real_local_draftbox_crash_precommit_v1";
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
  void crashBeforeCommitRollsBackWhileLostResponseAfterCommitReplaysCanonicalTruth()
      throws Exception {
    List<RunningApplication> applications = new ArrayList<>();
    CompletableFuture<HttpResponse<String>> blockedExecute = null;
    RunningApplication writer = startApplication();
    applications.add(writer);
    long writerPid = writer.process().pid();

    try {
      ArtifactHead rollbackArtifact = createCurrentArtifact(writer.port(), "rollback");
      PlannedApproval rollbackApproval =
          planExactV2Approval(writer.port(), rollbackArtifact, "rollback");
      assertPlannedDatabase(rollbackApproval.attemptId());
      AttemptMutationDigest plannedBaseline = digest(rollbackApproval.attemptId());

      installPrecommitBlocker();
      try (Connection lockConnection = connection();
          Statement lock = lockConnection.createStatement()) {
        int lockHolderPid = backendPid(lockConnection);
        lock.execute("SELECT pg_catalog.pg_advisory_lock(" + PRECOMMIT_LOCK + ")");
        blockedExecute =
            sendJsonAsync(
                writer.port(),
                "POST",
                executePath(rollbackApproval.attemptId()),
                executeRequest(rollbackApproval.scope()));

        int blockedBackendPid = awaitPrecommitBlock(writer.applicationName(), lockHolderPid);
        assertTrue(blockedBackendPid > 0);
        assertFalse(
            blockedExecute.isDone(),
            "execute returned before the immediate precommit blocker was reached");
        assertEquals(
            plannedBaseline,
            digest(rollbackApproval.attemptId()),
            "uncommitted local effect bytes became visible before process death");

        forceStopAlive(writer);
        lock.execute("SELECT pg_catalog.pg_advisory_unlock(" + PRECOMMIT_LOCK + ")");
        awaitNoApplicationBackends(writer.applicationName());
        assertEquals(
            plannedBaseline,
            digest(rollbackApproval.attemptId()),
            "hard-killed precommit transaction did not roll back exactly");
        assertPlannedDatabase(rollbackApproval.attemptId());
      } finally {
        if (blockedExecute != null) {
          blockedExecute.cancel(true);
        }
        stop(writer);
        awaitNoApplicationBackends(writer.applicationName());
        dropPrecommitBlocker();
      }

      RunningApplication recoveryWriter = startApplication();
      applications.add(recoveryWriter);
      assertNotEquals(writerPid, recoveryWriter.process().pid());

      HttpResponse<String> recoveredPlanned =
          sendJson(recoveryWriter.port(), "GET", approvalPath(rollbackApproval.attemptId()), null);
      assertEquals(200, recoveredPlanned.statusCode(), recoveredPlanned::body);
      assertEquals(rollbackApproval.canonicalPlannedBody(), recoveredPlanned.body());
      assertPlannedBody(recoveredPlanned.body(), rollbackArtifact, rollbackApproval.scope());
      assertEquals(plannedBaseline, digest(rollbackApproval.attemptId()));

      HttpResponse<String> firstExecuted =
          sendJson(
              recoveryWriter.port(),
              "POST",
              executePath(rollbackApproval.attemptId()),
              executeRequest(rollbackApproval.scope()));
      assertEquals(201, firstExecuted.statusCode(), firstExecuted::body);
      assertPrivateNoStore(firstExecuted);
      assertEquals(
          approvalPath(rollbackApproval.attemptId()),
          firstExecuted.headers().firstValue("Location").orElse(null));
      assertExecutedBody(firstExecuted.body(), rollbackApproval.attemptId(), rollbackArtifact);
      assertSucceededDatabase(rollbackApproval.attemptId());
      AttemptMutationDigest rollbackTerminal = digest(rollbackApproval.attemptId());

      ArtifactHead responseLossArtifact =
          createCurrentArtifact(recoveryWriter.port(), "response-loss");
      PlannedApproval responseLossApproval =
          planExactV2Approval(recoveryWriter.port(), responseLossArtifact, "response-loss");
      assertPlannedDatabase(responseLossApproval.attemptId());

      fireAndForgetJson(
          recoveryWriter.port(),
          executePath(responseLossApproval.attemptId()),
          executeRequest(responseLossApproval.scope()));
      awaitSucceededDatabase(responseLossApproval.attemptId());
      AttemptMutationDigest responseLossTerminal = digest(responseLossApproval.attemptId());
      assertSucceededDatabase(responseLossApproval.attemptId());

      long responseLossWriterPid = recoveryWriter.process().pid();
      forceStopAlive(recoveryWriter);
      RunningApplication freshReader = startApplication();
      applications.add(freshReader);
      assertNotEquals(responseLossWriterPid, freshReader.process().pid());

      HttpResponse<String> recoveredTerminal =
          sendJson(freshReader.port(), "GET", approvalPath(responseLossApproval.attemptId()), null);
      assertEquals(200, recoveredTerminal.statusCode(), recoveredTerminal::body);
      assertPrivateNoStore(recoveredTerminal);
      assertExecutedBody(
          recoveredTerminal.body(), responseLossApproval.attemptId(), responseLossArtifact);

      HttpResponse<String> terminalReplay =
          sendJson(
              freshReader.port(),
              "POST",
              executePath(responseLossApproval.attemptId()),
              executeRequest(responseLossApproval.scope()));
      assertEquals(200, terminalReplay.statusCode(), terminalReplay::body);
      assertPrivateNoStore(terminalReplay);
      assertEquals(
          approvalPath(responseLossApproval.attemptId()),
          terminalReplay.headers().firstValue("Location").orElse(null));
      assertEquals(
          recoveredTerminal.body(),
          terminalReplay.body(),
          "lost-response replay must return the canonical committed result");
      assertEquals(responseLossTerminal, digest(responseLossApproval.attemptId()));
      assertEquals(rollbackTerminal, digest(rollbackApproval.attemptId()));

      ArtifactHead retiredArtifact = createCurrentArtifact(freshReader.port(), "retired-v1");
      RetiredApproval retired = seedRetiredV1(retiredArtifact);
      HttpResponse<String> retiredGet =
          sendJson(freshReader.port(), "GET", approvalPath(retired.attemptId()), null);
      assertEquals(200, retiredGet.statusCode(), retiredGet::body);
      assertPrivateNoStore(retiredGet);
      assertEquals("PLANNED", JsonPath.read(retiredGet.body(), "$.status"));
      assertEquals(
          ActionApprovalScope.LOCAL_DRAFTBOX_V1,
          JsonPath.read(retiredGet.body(), "$.provenance.executionRoute"));
      AttemptMutationDigest retiredBaseline = digest(retired.attemptId());

      HttpResponse<String> retiredExecute =
          sendJson(
              freshReader.port(),
              "POST",
              executePath(retired.attemptId()),
              executeRequest(retired.scope()));
      ProblemShape retiredProblem = assertSafeProblem(retiredExecute, 412);
      assertEquals("urn:emergeos:problem:approval-stale", retiredProblem.type());
      assertEquals("Action approval stale", retiredProblem.title());
      assertEquals(retiredBaseline, digest(retired.attemptId()));
      assertPlannedDatabase(retired.attemptId());

      System.out.println(
          "REAL_LOCAL_DRAFTBOX_CRASH_RECEIPT "
              + "precommitKill=ROLLED_BACK freshPlannedGet=200 firstExecute=201 "
              + "responseLoss=COMMITTED freshTerminalGet=200 replay=200 "
              + "v1=412 v1Mutation=0 transitions=1>2 effects=0>1/1 "
              + "legacyReceipts=0 providerBase=UNREACHABLE synthetic=true shippingLive=DISABLED");
    } finally {
      if (blockedExecute != null) {
        blockedExecute.cancel(true);
      }
      stopAll(applications);
      for (RunningApplication application : applications) {
        awaitNoApplicationBackends(application.applicationName());
      }
      dropPrecommitBlocker();
    }
  }

  private static PlannedApproval planExactV2Approval(int port, ArtifactHead artifact, String suffix)
      throws Exception {
    ScopeHead scope = previewExactV2Scope(port, artifact);
    HttpResponse<String> response =
        sendJson(
            port,
            "POST",
            "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
            """
            {
              "approvedArtifactVersion": %d,
              "approvedArtifactHash": "%s",
              "approvalNonce": "real-local-draftbox-crash-%s",
              "approvedScopeSchema": "%s",
              "approvedScopeHash": "%s"
            }
            """
                .formatted(
                    artifact.version(), artifact.hash(), suffix, scope.schema(), scope.hash()));
    assertEquals(201, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    String attemptId = JsonPath.read(response.body(), "$.attemptId");
    assertNotNull(attemptId);
    assertFalse(attemptId.isBlank());
    assertEquals(approvalPath(attemptId), response.headers().firstValue("Location").orElse(null));
    assertPlannedBody(response.body(), artifact, scope);
    return new PlannedApproval(attemptId, scope, response.body());
  }

  private static ArtifactHead createCurrentArtifact(int port, String suffix) throws Exception {
    HttpResponse<String> captured =
        sendJson(
            port,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "real-local-draftbox-crash-capture-%s",
              "content": "synthetic local Draftbox crash source %s",
              "sourceType": "TEXT",
              "sourceRef": "real-local-draftbox-crash:http-it",
              "dataClass": "PERSONAL"
            }
            """
                .formatted(suffix, suffix));
    assertEquals(201, captured.statusCode(), captured::body);
    String captureId = JsonPath.read(captured.body(), "$.captureId");

    HttpResponse<String> created =
        sendJson(
            port,
            "POST",
            "/api/v1/artifacts",
            """
            {
              "captureId": "%s",
              "content": "synthetic current Artifact for crash acceptance %s"
            }
            """
                .formatted(captureId, suffix));
    assertEquals(201, created.statusCode(), created::body);
    assertEquals(1, number(created.body(), "$.currentVersion"));
    return new ArtifactHead(
        JsonPath.read(created.body(), "$.artifactId"),
        number(created.body(), "$.currentVersion"),
        JsonPath.read(created.body(), "$.currentHash"));
  }

  private static ScopeHead previewExactV2Scope(int port, ArtifactHead artifact) throws Exception {
    HttpResponse<String> preview =
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
    assertEquals(200, preview.statusCode(), preview::body);
    assertPrivateNoStore(preview);
    assertEquals(SCOPE_SCHEMA, JsonPath.read(preview.body(), "$.scopeSchema"));
    assertEquals(
        ActionApprovalScope.LOCAL_DRAFTBOX_V2,
        JsonPath.read(preview.body(), "$.provenance.executionRoute"));
    assertEquals("local-action-v2", JsonPath.read(preview.body(), "$.action.policyVersion"));
    assertEquals(
        "emergeos.local-draftbox", JsonPath.read(preview.body(), "$.capability.connector"));
    assertEquals("emergeos:local-draftbox", JsonPath.read(preview.body(), "$.capability.audience"));
    assertEquals(
        "local-draftbox:" + OWNER, JsonPath.read(preview.body(), "$.capability.accountRef"));
    assertEquals(1, number(preview.body(), "$.capability.maxCalls"));
    assertEquals(artifact.artifactId(), JsonPath.read(preview.body(), "$.artifact.artifactId"));
    assertEquals(artifact.version(), number(preview.body(), "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(preview.body(), "$.artifact.artifactHash"));
    String scopeHash = JsonPath.read(preview.body(), "$.scopeHash");
    assertTrue(scopeHash.matches("[0-9a-f]{64}"));
    return new ScopeHead(SCOPE_SCHEMA, scopeHash);
  }

  private static void assertPlannedBody(String json, ArtifactHead artifact, ScopeHead scope) {
    assertEquals("PLANNED", JsonPath.read(json, "$.status"));
    assertEquals("NOT_EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(scope.schema(), JsonPath.read(json, "$.scopeSchema"));
    assertEquals(scope.hash(), JsonPath.read(json, "$.scopeHash"));
    assertEquals(
        ActionApprovalScope.LOCAL_DRAFTBOX_V2, JsonPath.read(json, "$.provenance.executionRoute"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.artifact.artifactId"));
    assertEquals(artifact.version(), number(json, "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.artifact.artifactHash"));
    assertEquals(0, number(json, "$.capability.usedCalls"));
    assertEquals(1, number(json, "$.transitions.length()"));
    assertEquals(1, number(json, "$.transitions[0].sequence"));
    assertNull(JsonPath.read(json, "$.transitions[0].fromStatus"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[0].toStatus"));
    assertNull(JsonPath.read(json, "$.localDraft"));
    assertNull(JsonPath.read(json, "$.receipt"));
  }

  private static void assertExecutedBody(String json, String attemptId, ArtifactHead artifact)
      throws Exception {
    assertEquals(attemptId, JsonPath.read(json, "$.attemptId"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.status"));
    assertEquals("EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(
        ActionApprovalScope.LOCAL_DRAFTBOX_V2, JsonPath.read(json, "$.provenance.executionRoute"));
    assertEquals(1, number(json, "$.capability.usedCalls"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.localDraft.artifactId"));
    assertEquals(artifact.version(), number(json, "$.localDraft.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.localDraft.artifactHash"));
    assertEquals("ACTIVE", JsonPath.read(json, "$.localDraft.state"));
    String draftId = JsonPath.read(json, "$.localDraft.draftId");
    assertNotNull(draftId);
    assertFalse(draftId.isBlank());
    assertEquals("LOCAL_DRAFT_CREATED_V1", JsonPath.read(json, "$.receipt.receiptType"));
    String receiptId = JsonPath.read(json, "$.receipt.receiptId");
    assertNotNull(receiptId);
    assertFalse(receiptId.isBlank());
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
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM local_draft_creation_receipts receipt "
                + "JOIN local_drafts draft "
                + "ON draft.principal_id=receipt.principal_id "
                + "AND draft.attempt_id=receipt.attempt_id "
                + "AND draft.draft_id=receipt.draft_id "
                + "WHERE receipt.principal_id=? AND receipt.attempt_id=? "
                + "AND receipt.receipt_id=? AND receipt.draft_id=? "
                + "AND receipt.receipt_type='LOCAL_DRAFT_CREATED_V1' "
                + "AND receipt.outcome='SUCCEEDED' AND NOT receipt.simulated "
                + "AND draft.state='ACTIVE'",
            OWNER,
            attemptId,
            receiptId,
            draftId));
  }

  private static RetiredApproval seedRetiredV1(ArtifactHead artifact) throws Exception {
    Instant approvedAt = databaseNow();
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    String planId = "plan-real-local-draftbox-retired-v1";
    String idempotencyKey = "real-local-draftbox-retired-v1";
    String connector = "simulated.local-draft";
    String audience = "adapter:simulated-provider";
    String accountRef = "simulated-account:" + OWNER;
    ActionPlan plan =
        new ActionPlan(
            planId,
            OWNER,
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            artifact.artifactId(),
            artifact.version(),
            artifact.hash(),
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-real-local-draftbox-retired-v1",
            planId,
            plan.planHash(),
            artifact.hash(),
            "APPROVED",
            OWNER,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-real-local-draftbox-retired-v1",
            OWNER,
            connector,
            audience,
            accountRef,
            planId,
            plan.planHash(),
            artifact.hash(),
            idempotencyKey,
            expiresAt,
            2);
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            OWNER,
            ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
            ActionApprovalScope.LOCAL_DRAFTBOX_V1,
            plan.actionType(),
            plan.targetRef(),
            artifact.artifactId(),
            artifact.version(),
            artifact.hash(),
            plan.risk(),
            plan.policyVersion(),
            connector,
            audience,
            accountRef,
            Duration.between(approvedAt, expiresAt).toNanos() / 1_000L,
            capability.maxCalls());
    ActionAttempt attempt =
        new ActionAttempt(
            "attempt-real-local-draftbox-retired-v1",
            plan,
            approval,
            capability,
            ActionAttemptStatus.PLANNED,
            0,
            List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, approvedAt)),
            null,
            scope);

    DriverManagerDataSource dataSource = dataSource();
    ActionAttemptStore.PlanResult.Accepted accepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            new PostgresActionAttemptStore(dataSource, new DataSourceTransactionManager(dataSource))
                .planApprovalOrFind(attempt));
    assertTrue(accepted.created());
    return new RetiredApproval(
        attempt.attemptId(), new ScopeHead(scope.scopeSchema(), scope.scopeHash()));
  }

  private static void installPrecommitBlocker() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      configureTriggerDdlTimeouts(statement);
      statement.execute(
          """
          CREATE FUNCTION real_local_draftbox_crash_precommit_v1()
          RETURNS trigger
          LANGUAGE plpgsql
          AS $$
          BEGIN
            IF NEW.outcome = 'SUCCEEDED'
               AND NEW.receipt_type = 'LOCAL_DRAFT_CREATED_V1' THEN
              PERFORM pg_catalog.pg_advisory_lock(18202608150001);
              PERFORM pg_catalog.pg_advisory_unlock(18202608150001);
            END IF;
            RETURN NULL;
          END
          $$
          """);
      statement.execute(
          """
          CREATE CONSTRAINT TRIGGER zz_real_local_draftbox_crash_precommit_v1
          AFTER INSERT ON local_draft_creation_receipts
          DEFERRABLE INITIALLY IMMEDIATE
          FOR EACH ROW
          EXECUTE FUNCTION real_local_draftbox_crash_precommit_v1()
          """);
    }
  }

  private static void dropPrecommitBlocker() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      configureTriggerDdlTimeouts(statement);
      statement.execute(
          "DROP TRIGGER IF EXISTS " + PRECOMMIT_TRIGGER + " ON local_draft_creation_receipts");
      statement.execute("DROP FUNCTION IF EXISTS " + PRECOMMIT_FUNCTION + "()");
    }
  }

  private static void configureTriggerDdlTimeouts(Statement statement) throws Exception {
    statement.execute("SET lock_timeout = '2s'");
    statement.execute("SET statement_timeout = '5s'");
  }

  private static int backendPid(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
      assertTrue(row.next());
      int pid = row.getInt(1);
      assertFalse(row.next());
      return pid;
    }
  }

  private static int awaitPrecommitBlock(String applicationName, int lockHolderPid)
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
          AND activity.wait_event = 'advisory'
          AND ? = ANY(pg_catalog.pg_blocking_pids(activity.pid))
        ORDER BY activity.pid
        """;
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, POSTGRES.getUsername());
      statement.setString(2, applicationName);
      statement.setInt(3, lockHolderPid);
      while (System.nanoTime() < deadline) {
        try (ResultSet rows = statement.executeQuery()) {
          if (rows.next()) {
            int pid = rows.getInt(1);
            assertFalse(rows.next(), "multiple execute backends reached the precommit blocker");
            return pid;
          }
        }
        Thread.sleep(25);
      }
    }
    throw new AssertionError("local Draftbox execute did not reach the precommit blocker");
  }

  private static void awaitNoApplicationBackends(String applicationName) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (queryInt(
              "SELECT count(*) FROM pg_catalog.pg_stat_activity "
                  + "WHERE datname=current_database() AND application_name=?",
              applicationName)
          == 0) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("killed packaged JVM left an application database backend");
  }

  private static void awaitSucceededDatabase(String attemptId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (queryInt(
                  "SELECT count(*) FROM action_attempts "
                      + "WHERE principal_id=? AND attempt_id=? "
                      + "AND status='SUCCEEDED' AND state_version=2 "
                      + "AND capability_used_calls=1",
                  OWNER,
                  attemptId)
              == 1
          && queryInt(
                  "SELECT count(*) FROM local_drafts " + "WHERE principal_id=? AND attempt_id=?",
                  OWNER,
                  attemptId)
              == 1
          && queryInt(
                  "SELECT count(*) FROM local_draft_creation_receipts "
                      + "WHERE principal_id=? AND attempt_id=?",
                  OWNER,
                  attemptId)
              == 1) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("lost-response execute did not reach one committed terminal effect");
  }

  private static void assertPlannedDatabase(String attemptId) throws Exception {
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempts "
                + "WHERE principal_id=? AND attempt_id=? AND status='PLANNED' "
                + "AND state_version=1 AND capability_used_calls=0",
            OWNER,
            attemptId));
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempt_transitions "
                + "WHERE principal_id=? AND attempt_id=? AND sequence=1 "
                + "AND from_status IS NULL AND to_status='PLANNED' "
                + "AND capability_use_delta=0",
            OWNER,
            attemptId));
    assertEquals(
        1,
        queryInt(
            "SELECT count(*) FROM action_attempt_transitions "
                + "WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId));
    assertEquals(0, effectCount("local_drafts", attemptId));
    assertEquals(0, effectCount("local_draft_creation_receipts", attemptId));
    assertEquals(0, effectCount("action_receipts", attemptId));
  }

  private static void assertSucceededDatabase(String attemptId) throws Exception {
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
    assertEquals(1, effectCount("local_drafts", attemptId));
    assertEquals(1, effectCount("local_draft_creation_receipts", attemptId));
    assertEquals(0, effectCount("action_receipts", attemptId));
  }

  private static AttemptMutationDigest digest(String attemptId) throws Exception {
    return new AttemptMutationDigest(
        queryString(
            "SELECT concat_ws('|', status, state_version::text, "
                + "capability_used_calls::text, xmin::text, updated_at::text) "
                + "FROM action_attempts WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId),
        queryString(
            "SELECT COALESCE(string_agg(concat_ws('|', sequence::text, "
                + "COALESCE(from_status, '<null>'), to_status, capability_use_delta::text, "
                + "xmin::text, occurred_at::text), ';' ORDER BY sequence), '') "
                + "FROM action_attempt_transitions WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId),
        queryString(
            "SELECT COALESCE(string_agg(jsonb_build_array("
                + "principal_id, draft_id, attempt_id, attempt_status, artifact_id, "
                + "artifact_version, artifact_hash, state, "
                + "extract(epoch FROM created_at)::text, xmin::text)::text, "
                + "E'\\n' ORDER BY principal_id, draft_id, attempt_id), '') "
                + "FROM local_drafts WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId),
        queryString(
            "SELECT COALESCE(string_agg(jsonb_build_array("
                + "principal_id, attempt_id, outcome, receipt_id, receipt_type, draft_id, "
                + "extract(epoch FROM occurred_at)::text, simulated, xmin::text)::text, "
                + "E'\\n' ORDER BY principal_id, attempt_id, receipt_id), '') "
                + "FROM local_draft_creation_receipts WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId),
        queryString(
            "SELECT COALESCE(string_agg(jsonb_build_array("
                + "principal_id, attempt_id, receipt_id, outcome, external_id, reason_code, "
                + "provider_request_id, raw_response_ref, "
                + "extract(epoch FROM occurred_at)::text, simulated, xmin::text)::text, "
                + "E'\\n' ORDER BY principal_id, attempt_id, receipt_id), '') "
                + "FROM action_receipts WHERE principal_id=? AND attempt_id=?",
            OWNER,
            attemptId));
  }

  private static int effectCount(String table, String attemptId) throws Exception {
    String allowed =
        switch (table) {
          case "local_drafts", "local_draft_creation_receipts", "action_receipts" -> table;
          default -> throw new IllegalArgumentException("unsupported effect table");
        };
    return queryInt(
        "SELECT count(*) FROM " + allowed + " WHERE principal_id=? AND attempt_id=?",
        OWNER,
        attemptId);
  }

  private static ProblemShape assertSafeProblem(HttpResponse<String> response, int expectedStatus) {
    assertEquals(expectedStatus, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    assertTrue(
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("application/problem+json"));
    Map<String, Object> body = JsonPath.parse(response.body()).read("$");
    assertTrue(Set.of("type", "title", "status", "detail", "instance").containsAll(body.keySet()));
    String type = JsonPath.read(response.body(), "$.type");
    String title = JsonPath.read(response.body(), "$.title");
    String detail = JsonPath.read(response.body(), "$.detail");
    assertEquals(expectedStatus, number(response.body(), "$.status"));
    assertNotNull(type);
    assertNotNull(title);
    assertNotNull(detail);
    assertFalse(type.isBlank());
    assertFalse(title.isBlank());
    assertFalse(detail.isBlank());
    String normalized = (title + " " + detail).toLowerCase(java.util.Locale.ROOT);
    assertFalse(normalized.contains("action_attempts"));
    assertFalse(normalized.contains("local_drafts"));
    assertFalse(normalized.contains("sql"));
    assertFalse(normalized.contains("exception"));
    assertFalse(normalized.contains("stack"));
    assertFalse(SHA_256_TEXT.matcher(normalized).find());
    return new ProblemShape(type, title, detail);
  }

  private static String executeRequest(ScopeHead scope) {
    return """
    {
      "scopeSchema": "%s",
      "scopeHash": "%s"
    }
    """
        .formatted(scope.schema(), scope.hash());
  }

  private static String approvalPath(String attemptId) {
    return "/api/v1/action-approvals/" + attemptId;
  }

  private static String executePath(String attemptId) {
    return approvalPath(attemptId) + "/execute";
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    String applicationName = "real-local-draftbox-crash-http-it-" + port;
    Path log = Files.createTempFile("emerge-real-local-draftbox-crash-", ".log");
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
                "--spring.datasource.url="
                    + postgresJdbcUrl()
                    + "&ApplicationName="
                    + applicationName,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application = new RunningApplication(process, port, log, applicationName);
    try {
      awaitLive(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stopAfterFailure(application, failure);
      throw failure;
    }
  }

  private static void awaitLive(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError(
            "packaged application exited before liveness:\n" + readLog(application.log()));
      }
      try {
        if (sendJson(application.port(), "GET", "/actuator/health/liveness", null).statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener is still starting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become live:\n" + readLog(application.log()));
  }

  private static CompletableFuture<HttpResponse<String>> sendJsonAsync(
      int port, String method, String path, String body) {
    return HTTP.sendAsync(
        request(port, method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> sendJson(int port, String method, String path, String body)
      throws IOException, InterruptedException {
    return HTTP.send(
        request(port, method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpRequest request(int port, String method, String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json");
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return request.build();
  }

  private static void fireAndForgetJson(int port, String path, String body) throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String headers =
        "POST "
            + path
            + " HTTP/1.1\r\nHost: 127.0.0.1:"
            + port
            + "\r\nAccept: application/json\r\nContent-Type: application/json\r\nContent-Length: "
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

  private static void assertPrivateNoStore(HttpResponse<?> response) {
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null));
  }

  private static int number(String json, String path) {
    return JsonPath.<Number>read(json, path).intValue();
  }

  private static Instant databaseNow() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT date_trunc('microseconds', pg_catalog.clock_timestamp())")) {
      assertTrue(rows.next());
      Instant now = rows.getTimestamp(1).toInstant();
      assertFalse(rows.next());
      return now;
    }
  }

  private static int queryInt(String sql, Object... values) throws Exception {
    return ((Number) query(sql, values)).intValue();
  }

  private static String queryString(String sql, Object... values) throws Exception {
    return (String) query(sql, values);
  }

  private static Object query(String sql, Object... values) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next(), "query returned no row: " + sql);
        Object value = rows.getObject(1);
        assertFalse(rows.next(), "query returned multiple rows: " + sql);
        return value;
      }
    }
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void forceStopAlive(RunningApplication application) throws Exception {
    assertTrue(
        application.process().isAlive(),
        () -> "packaged application exited before forced stop:\n" + readLog(application.log()));
    stop(application);
    assertFalse(application.process().isAlive(), "packaged application survived forced stop");
  }

  private static void stopAll(List<RunningApplication> applications) throws Exception {
    Exception first = null;
    for (RunningApplication application : applications) {
      try {
        stop(application);
      } catch (Exception failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }

  private static void stopAfterFailure(RunningApplication application, Throwable startupFailure) {
    try {
      stop(application);
    } catch (Exception stopFailure) {
      startupFailure.addSuppressed(stopFailure);
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
    } catch (IOException failure) {
      return "unable to read application log: " + failure.getClass().getSimpleName();
    }
  }

  private record RunningApplication(Process process, int port, Path log, String applicationName) {}

  private record ArtifactHead(String artifactId, int version, String hash) {}

  private record ScopeHead(String schema, String hash) {}

  private record PlannedApproval(String attemptId, ScopeHead scope, String canonicalPlannedBody) {}

  private record RetiredApproval(String attemptId, ScopeHead scope) {}

  private record ProblemShape(String type, String title, String detail) {}

  private record AttemptMutationDigest(
      String head,
      String transitions,
      String drafts,
      String localReceipts,
      String legacyReceipts) {}
}
