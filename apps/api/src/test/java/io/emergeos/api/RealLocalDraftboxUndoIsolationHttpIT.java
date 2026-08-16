package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Two-process isolation Acceptance for the local logical-Undo Receipt. */
@Testcontainers
class RealLocalDraftboxUndoIsolationHttpIT {

  private static final String OWNER = "logical-undo-isolation-owner";
  private static final String FOREIGN = "logical-undo-isolation-foreign";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void twoJvmsSerializeReplayConflictNonceAndPrincipalPrivacy() throws Exception {
    LogicalUndoFaultHarness harness = new LogicalUndoFaultHarness(POSTGRES);
    try {
      LogicalUndoFaultHarness.RunningApplication ownerA =
          harness.startApplication(OWNER, "isolation-owner-a");
      LogicalUndoFaultHarness.RunningApplication ownerB =
          harness.startApplication(OWNER, "isolation-owner-b");
      LogicalUndoFaultHarness.ActiveDraft exactReplay =
          harness.createActiveDraft(ownerA, "same-scope");
      LogicalUndoFaultHarness.ActiveDraft differentNonce =
          harness.createActiveDraft(ownerA, "different-nonce");
      LogicalUndoFaultHarness.ActiveDraft reusedNonceTarget =
          harness.createActiveDraft(ownerA, "reused-nonce-target");
      LogicalUndoFaultHarness.TruthDigest retainedCreationTruth = harness.truthDigest();

      String exactNonce = "logical-undo-isolation-same-scope-nonce";
      LogicalUndoFaultHarness.UndoRequest exactRequest =
          harness.undoRequest(exactReplay, exactNonce);
      List<IndexedUndoResponse> exactResponses =
          raceUndo(harness, ownerA, ownerB, exactReplay.attemptId(), exactRequest);
      assertEquals(
          List.of(200, 201),
          exactResponses.stream()
              .map(response -> response.response().statusCode())
              .sorted()
              .toList());
      assertEquals(
          List.of(0, 1),
          exactResponses.stream().map(response -> response.request().requestIndex()).toList());
      exactResponses.forEach(response -> harness.assertPrivateNoStore(response.response()));
      assertEquals(
          harness.bodyHash(exactResponses.get(0).response().body()),
          harness.bodyHash(exactResponses.get(1).response().body()),
          "same-scope two-JVM replay must return byte-identical canonical JSON");
      assertEquals(
          harness.approvalPath(exactReplay.attemptId()),
          exactResponses
              .get(0)
              .response()
              .headers()
              .firstValue("Location")
              .orElse(null));
      assertEquals(
          exactResponses.get(0).response().headers().firstValue("Location"),
          exactResponses.get(1).response().headers().firstValue("Location"));
      IndexedUndoResponse exactCreated = responseWithStatus(exactResponses, 201);
      assertEquals(exactNonce, exactCreated.request().request().nonce());
      harness.assertLogicallyUndone(
          exactCreated.response().body(), exactReplay, exactCreated.request().request().nonce());
      harness.assertEvolutionResponseSurfaceAbsent(exactCreated.response().body());
      assertEquals(1, harness.undoCount(exactReplay.attemptId()));
      assertEquals(
          JsonPath.<String>read(
              exactResponses.get(0).response().body(), "$.undoReceipt.receiptId"),
          JsonPath.<String>read(
              exactResponses.get(1).response().body(), "$.undoReceipt.receiptId"));
      assertEquals(retainedCreationTruth, harness.truthDigest());

      String competingNonceA = "logical-undo-isolation-competing-nonce-a";
      String competingNonceB = "logical-undo-isolation-competing-nonce-b";
      LogicalUndoFaultHarness.UndoRequest competingRequestA =
          harness.undoRequest(differentNonce, competingNonceA);
      LogicalUndoFaultHarness.UndoRequest competingRequestB =
          harness.undoRequest(differentNonce, competingNonceB);
      List<IndexedUndoResponse> competingResponses =
          raceUndo(
              harness,
              ownerA,
              ownerB,
              differentNonce.attemptId(),
              competingRequestA,
              competingRequestB);
      assertEquals(
          List.of(201, 409),
          competingResponses.stream()
              .map(response -> response.response().statusCode())
              .sorted()
              .toList());
      assertEquals(
          List.of(0, 1),
          competingResponses.stream()
              .map(response -> response.request().requestIndex())
              .toList());
      assertEquals(
          List.of(competingNonceA, competingNonceB),
          competingResponses.stream()
              .map(response -> response.request().request().nonce())
              .toList());
      competingResponses.forEach(response -> harness.assertPrivateNoStore(response.response()));
      IndexedUndoResponse competingCreated = responseWithStatus(competingResponses, 201);
      IndexedUndoResponse competingConflict = responseWithStatus(competingResponses, 409);
      assertFalse(
          competingCreated.request().request().nonce().equals(
              competingConflict.request().request().nonce()));
      assertEquals(
          competingCreated.request().request().nonce(),
          JsonPath.read(competingCreated.response().body(), "$.undoReceipt.undoNonce"));
      harness.assertLogicallyUndone(
          competingCreated.response().body(),
          differentNonce,
          competingCreated.request().request().nonce());
      harness.assertEvolutionResponseSurfaceAbsent(competingCreated.response().body());
      LogicalUndoFaultHarness.ProblemShape competingProblem =
          harness.assertSafeProblem(competingConflict.response(), 409);
      assertEquals("urn:emergeos:problem:local-draft-undo-conflict", competingProblem.type());
      Set<String> protectedValues =
          protectedValues(
              List.of(exactReplay, differentNonce, reusedNonceTarget),
              List.of(exactRequest, competingRequestA, competingRequestB),
              List.of(
                  JsonPath.read(
                      exactCreated.response().body(), "$.undoReceipt.receiptId"),
                  JsonPath.read(
                      competingCreated.response().body(), "$.undoReceipt.receiptId")));
      harness.assertNoSensitiveProblemLeak(
          competingConflict.response(),
          protectedValues,
          harness.undoPath(differentNonce.attemptId()));
      assertEquals(1, harness.undoCount(differentNonce.attemptId()));
      assertEquals(retainedCreationTruth, harness.truthDigest());
      LogicalUndoFaultHarness.DatabaseDigest afterCompetingRace = harness.databaseDigest();
      LogicalUndoFaultHarness.UndoRequest losingRequest =
          competingConflict.request().request();
      HttpResponse<String> repeatedLoser =
          harness.send(
              ownerA,
              "POST",
              harness.undoPath(differentNonce.attemptId()),
              losingRequest.body());
      harness.assertSafeProblem(repeatedLoser, 409);
      harness.assertNoSensitiveProblemLeak(
          repeatedLoser, protectedValues, harness.undoPath(differentNonce.attemptId()));
      assertEquals(afterCompetingRace, harness.databaseDigest());

      LogicalUndoFaultHarness.DatabaseDigest beforeNonceReuse = harness.databaseDigest();
      LogicalUndoFaultHarness.UndoRequest reusedNonceRequest =
          harness.undoRequest(reusedNonceTarget, exactNonce);
      protectedValues.add(reusedNonceRequest.nonce());
      protectedValues.add(reusedNonceRequest.scopeHash());
      HttpResponse<String> reusedNonce =
          harness.send(
              ownerB,
              "POST",
              harness.undoPath(reusedNonceTarget.attemptId()),
              reusedNonceRequest.body());
      LogicalUndoFaultHarness.ProblemShape reusedNonceProblem =
          harness.assertSafeProblem(reusedNonce, 409);
      assertEquals("urn:emergeos:problem:local-draft-undo-conflict", reusedNonceProblem.type());
      harness.assertNoSensitiveProblemLeak(
          reusedNonce, protectedValues, harness.undoPath(reusedNonceTarget.attemptId()));
      assertEquals(0, harness.undoCount(reusedNonceTarget.attemptId()));
      assertEquals(beforeNonceReuse, harness.databaseDigest());

      LogicalUndoFaultHarness.RunningApplication foreign =
          harness.startApplication(FOREIGN, "isolation-foreign");
      LogicalUndoFaultHarness.DatabaseDigest beforeForeignRequests = harness.databaseDigest();
      String missingAttempt = "missing-logical-undo-isolation-attempt";
      protectedValues.add(FOREIGN);
      protectedValues.add(missingAttempt);
      HttpResponse<String> foreignOwnerGet =
          harness.send(foreign, "GET", harness.approvalPath(exactReplay.attemptId()), null);
      HttpResponse<String> foreignMissingGet =
          harness.send(foreign, "GET", harness.approvalPath(missingAttempt), null);
      HttpResponse<String> foreignOwnerUndo =
          harness.send(
              foreign,
              "POST",
              harness.undoPath(exactReplay.attemptId()),
              exactRequest.body());
      HttpResponse<String> foreignMissingUndo =
          harness.send(
              foreign, "POST", harness.undoPath(missingAttempt), exactRequest.body());
      assertEquals(
          harness.assertSafeProblem(foreignOwnerGet, 404),
          harness.assertSafeProblem(foreignMissingGet, 404));
      assertEquals(
          harness.assertSafeProblem(foreignOwnerUndo, 404),
          harness.assertSafeProblem(foreignMissingUndo, 404));
      harness.assertNoSensitiveProblemLeak(
          foreignOwnerGet, protectedValues, harness.approvalPath(exactReplay.attemptId()));
      harness.assertNoSensitiveProblemLeak(
          foreignMissingGet, protectedValues, harness.approvalPath(missingAttempt));
      harness.assertNoSensitiveProblemLeak(
          foreignOwnerUndo, protectedValues, harness.undoPath(exactReplay.attemptId()));
      harness.assertNoSensitiveProblemLeak(
          foreignMissingUndo, protectedValues, harness.undoPath(missingAttempt));
      assertEquals(beforeForeignRequests, harness.databaseDigest());

      assertEquals(3, harness.count("local_drafts"));
      assertEquals(3, harness.countActiveRawDrafts(OWNER));
      assertEquals(2, harness.count("local_draft_undo_receipts"));
      assertEquals(0, harness.count("action_receipts"));
      assertEquals(retainedCreationTruth, harness.truthDigest());
      harness.finishAndAssertNoProviderCalls();
      System.out.println(
          "REAL_LOCAL_DRAFTBOX_UNDO_ISOLATION_RECEIPT "
              + "sameScopeStatuses=[200,201] differentNonceStatuses=[201,409] "
              + "sameNonceOtherDraft=409 crossPrincipal=404 missing=404 privateRequestMutation=0 "
              + "localOnly=true synthetic=true providerBase=LOCAL_COUNTING_TRAP providerCalls=0 "
              + "legacyActionReceipts=0 shippingLive=DISABLED rawDraftState=ACTIVE "
              + "projectedState=LOGICALLY_UNDONE "
              + "retention=CAPTURE_ARTIFACT_HISTORY_RETAINED "
              + "reflectionResponseSurface=ABSENT workingSelfResponseSurface=ABSENT "
              + "reflectionMutation=NOT_OBSERVABLE workingSelfMutation=NOT_OBSERVABLE "
              + "authority=ENGINEERING_ONLY live=DISABLED");
    } finally {
      harness.close();
    }
  }

  private static List<IndexedUndoResponse> raceUndo(
      LogicalUndoFaultHarness harness,
      LogicalUndoFaultHarness.RunningApplication first,
      LogicalUndoFaultHarness.RunningApplication second,
      String attemptId,
      LogicalUndoFaultHarness.UndoRequest request)
      throws Exception {
    return raceUndo(harness, first, second, attemptId, request, request);
  }

  private static List<IndexedUndoResponse> raceUndo(
      LogicalUndoFaultHarness harness,
      LogicalUndoFaultHarness.RunningApplication first,
      LogicalUndoFaultHarness.RunningApplication second,
      String attemptId,
      LogicalUndoFaultHarness.UndoRequest firstRequest,
      LogicalUndoFaultHarness.UndoRequest secondRequest)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    IndexedUndoRequest firstBinding = new IndexedUndoRequest(0, firstRequest);
    IndexedUndoRequest secondBinding = new IndexedUndoRequest(1, secondRequest);
    try {
      Future<IndexedUndoResponse> firstResponse =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return new IndexedUndoResponse(
                    firstBinding,
                    harness.send(
                        first,
                        "POST",
                        harness.undoPath(attemptId),
                        firstBinding.request().body()));
              });
      Future<IndexedUndoResponse> secondResponse =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return new IndexedUndoResponse(
                    secondBinding,
                    harness.send(
                        second,
                        "POST",
                        harness.undoPath(attemptId),
                        secondBinding.request().body()));
              });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      return List.of(
          firstResponse.get(30, TimeUnit.SECONDS),
          secondResponse.get(30, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static IndexedUndoResponse responseWithStatus(
      List<IndexedUndoResponse> responses, int expectedStatus) {
    List<IndexedUndoResponse> matching =
        responses.stream()
            .filter(response -> response.response().statusCode() == expectedStatus)
            .toList();
    assertEquals(1, matching.size());
    return matching.get(0);
  }

  private static Set<String> protectedValues(
      List<LogicalUndoFaultHarness.ActiveDraft> drafts,
      List<LogicalUndoFaultHarness.UndoRequest> requests,
      List<String> undoReceiptIds) {
    Set<String> values = new LinkedHashSet<>();
    for (LogicalUndoFaultHarness.ActiveDraft draft : drafts) {
      values.addAll(draft.protectedValues());
    }
    for (LogicalUndoFaultHarness.UndoRequest request : requests) {
      values.add(request.nonce());
      values.add(request.scopeHash());
    }
    values.addAll(undoReceiptIds);
    return values;
  }

  private record IndexedUndoRequest(
      int requestIndex, LogicalUndoFaultHarness.UndoRequest request) {}

  private record IndexedUndoResponse(
      IndexedUndoRequest request, HttpResponse<String> response) {}
}

/** Shared test-only packaged-JVM and PostgreSQL harness for logical-Undo fault Acceptance. */
final class LogicalUndoFaultHarness implements AutoCloseable {

  static final String OWNER_SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
  static final String UNDO_SCOPE_SCHEMA = "emergeos.local-draft-undo-scope.v1";
  static final long PRECOMMIT_LOCK = 18_202_608_150_019L;
  static final String PRECOMMIT_TRIGGER = "zz_real_local_draftbox_undo_crash_precommit_v1";
  static final String PRECOMMIT_FUNCTION = "real_local_draftbox_undo_crash_precommit_v1";
  private static final Pattern SHA_256_TEXT = Pattern.compile("[0-9a-f]{64}");
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  private final PostgreSQLContainer postgres;
  private final List<RunningApplication> applications = new ArrayList<>();
  private final CountingNetworkTrap providerTrap;
  private boolean closed;

  LogicalUndoFaultHarness(PostgreSQLContainer postgres) throws IOException {
    this.postgres = postgres;
    this.providerTrap = new CountingNetworkTrap();
  }

  RunningApplication startApplication(String principal, String label) throws Exception {
    int port = availableLoopbackPort();
    String applicationName = "logical-undo-" + label + "-" + port;
    Path log = Files.createTempFile("emerge-logical-undo-", ".log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                System.getProperty("emerge.it.jar"),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--emerge.prototype.principal-id=" + principal,
                "--emerge.simulated-provider.base-url=" + providerTrap.baseUrl(),
                "--emerge.simulated-provider.request-timeout=PT0.2S",
                "--spring.datasource.url="
                    + postgresJdbcUrl()
                    + "&ApplicationName="
                    + applicationName,
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application =
        new RunningApplication(process, port, log, applicationName, principal);
    applications.add(application);
    try {
      awaitLive(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stop(application);
      throw failure;
    }
  }

  ActiveDraft createActiveDraft(RunningApplication application, String suffix) throws Exception {
    String captureNonce = "logical-undo-capture-" + suffix;
    HttpResponse<String> captured =
        send(
            application,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "%s",
              "content": "synthetic logical Undo source %s",
              "sourceType": "TEXT",
              "sourceRef": "logical-undo:fault-acceptance",
              "dataClass": "PERSONAL"
            }
            """
                .formatted(captureNonce, suffix));
    assertEquals(201, captured.statusCode());
    String captureId = JsonPath.read(captured.body(), "$.captureId");
    assertEquals(captureNonce, JsonPath.read(captured.body(), "$.clientNonce"));
    String captureRequestHash = JsonPath.read(captured.body(), "$.requestHash");
    assertTrue(SHA_256_TEXT.matcher(captureRequestHash).matches());

    HttpResponse<String> artifactResponse =
        send(
            application,
            "POST",
            "/api/v1/artifacts",
            """
            {
              "captureId": "%s",
              "content": "synthetic logical Undo Artifact %s"
            }
            """
                .formatted(captureId, suffix));
    assertEquals(201, artifactResponse.statusCode());
    ArtifactHead artifact =
        new ArtifactHead(
            JsonPath.read(artifactResponse.body(), "$.artifactId"),
            number(artifactResponse.body(), "$.currentVersion"),
            JsonPath.read(artifactResponse.body(), "$.currentHash"));
    assertEquals(1, artifact.version());

    HttpResponse<String> preview =
        send(
            application,
            "GET",
            "/api/v1/artifacts/"
                + artifact.artifactId()
                + "/action-approval-scope?artifactVersion="
                + artifact.version()
                + "&artifactHash="
                + artifact.hash(),
            null);
    assertEquals(200, preview.statusCode());
    assertPrivateNoStore(preview);
    assertEquals(OWNER_SCOPE_SCHEMA, JsonPath.read(preview.body(), "$.scopeSchema"));
    assertEquals(
        "LOCAL_DRAFTBOX_V2", JsonPath.read(preview.body(), "$.provenance.executionRoute"));
    ScopeHead scope =
        new ScopeHead(
            JsonPath.read(preview.body(), "$.scopeSchema"),
            JsonPath.read(preview.body(), "$.scopeHash"));
    assertTrue(SHA_256_TEXT.matcher(scope.hash()).matches());

    String approvalNonce = "logical-undo-approval-" + suffix;
    HttpResponse<String> planned =
        send(
            application,
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
    assertEquals(201, planned.statusCode());
    assertPrivateNoStore(planned);
    String attemptId = JsonPath.read(planned.body(), "$.attemptId");
    assertNotNull(attemptId);
    assertFalse(attemptId.isBlank());
    String planId = JsonPath.read(planned.body(), "$.plan.planId");
    String planHash = JsonPath.read(planned.body(), "$.plan.planHash");
    String idempotencyKey = JsonPath.read(planned.body(), "$.plan.idempotencyKey");
    String approvalDecisionId = JsonPath.read(planned.body(), "$.approval.decisionId");
    String capabilityId = JsonPath.read(planned.body(), "$.capability.capabilityId");
    String capabilityAccountRef = JsonPath.read(planned.body(), "$.capability.accountRef");
    assertEquals(approvalNonce, idempotencyKey);
    assertEquals(application.principal(), JsonPath.read(planned.body(), "$.approval.actor"));
    assertEquals(
        application.principal(),
        JsonPath.read(planned.body(), "$.approvalPrincipal.configuredPrincipalId"));

    HttpResponse<String> executed =
        send(
            application,
            "POST",
            approvalPath(attemptId) + "/execute",
            """
            {
              "scopeSchema": "%s",
              "scopeHash": "%s"
            }
            """
                .formatted(scope.schema(), scope.hash()));
    assertEquals(201, executed.statusCode());
    assertPrivateNoStore(executed);
    assertEquals(approvalPath(attemptId), executed.headers().firstValue("Location").orElse(null));
    assertEquals("SUCCEEDED", JsonPath.read(executed.body(), "$.status"));
    assertEquals("ACTIVE", JsonPath.read(executed.body(), "$.localDraft.state"));
    assertEquals(true, JsonPath.read(executed.body(), "$.undoAvailable"));
    assertEquals(0, count("action_receipts"));
    return new ActiveDraft(
        application.principal(),
        captureId,
        captureNonce,
        captureRequestHash,
        artifact,
        scope.hash(),
        planId,
        planHash,
        approvalDecisionId,
        approvalNonce,
        idempotencyKey,
        capabilityId,
        capabilityAccountRef,
        attemptId,
        JsonPath.read(executed.body(), "$.localDraft.draftId"),
        JsonPath.read(executed.body(), "$.receipt.receiptId"),
        executed.body());
  }

  UndoRequest undoRequest(ActiveDraft draft, String undoNonce) throws Exception {
    String scopeHash = logicalUndoScopeHash(draft, undoNonce);
    String body =
        """
        {
          "undoNonce": "%s",
          "scopeSchema": "%s",
          "scopeHash": "%s"
        }
        """
            .formatted(undoNonce, UNDO_SCOPE_SCHEMA, scopeHash);
    return new UndoRequest(undoNonce, scopeHash, body);
  }

  void assertLogicallyUndone(String json, ActiveDraft draft, String expectedNonce)
      throws Exception {
    assertEquals(draft.attemptId(), JsonPath.read(json, "$.attemptId"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.status"));
    assertEquals("EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(false, JsonPath.read(json, "$.undoAvailable"));
    assertEquals("LOGICALLY_UNDONE", JsonPath.read(json, "$.localDraft.state"));
    assertEquals(draft.draftId(), JsonPath.read(json, "$.localDraft.draftId"));
    assertEquals(draft.creationReceiptId(), JsonPath.read(json, "$.receipt.receiptId"));
    assertEquals(
        "LOCAL_DRAFT_LOGICALLY_UNDONE_V1",
        JsonPath.read(json, "$.undoReceipt.receiptType"));
    assertEquals(draft.attemptId(), JsonPath.read(json, "$.undoReceipt.creationAttemptId"));
    assertEquals(draft.draftId(), JsonPath.read(json, "$.undoReceipt.draftId"));
    assertEquals(
        draft.creationReceiptId(), JsonPath.read(json, "$.undoReceipt.creationReceiptId"));
    assertEquals(draft.artifact().artifactId(), JsonPath.read(json, "$.undoReceipt.artifactId"));
    assertEquals(
        draft.artifact().version(), number(json, "$.undoReceipt.artifactVersion"));
    assertEquals(draft.artifact().hash(), JsonPath.read(json, "$.undoReceipt.artifactHash"));
    assertEquals(UNDO_SCOPE_SCHEMA, JsonPath.read(json, "$.undoReceipt.scopeSchema"));
    assertEquals(expectedNonce, JsonPath.read(json, "$.undoReceipt.undoNonce"));
    assertEquals("LOGICALLY_UNDONE", JsonPath.read(json, "$.undoReceipt.effect"));
    assertEquals(
        "CAPTURE_ARTIFACT_HISTORY_RETAINED",
        JsonPath.read(json, "$.undoReceipt.retention"));
    assertEquals("SUCCEEDED", JsonPath.read(json, "$.undoReceipt.outcome"));
    assertEquals(false, JsonPath.read(json, "$.undoReceipt.simulated"));
    String receiptId = JsonPath.read(json, "$.undoReceipt.receiptId");
    Instant responseTime = Instant.parse(JsonPath.read(json, "$.undoReceipt.occurredAt"));
    assertEquals(
        responseTime,
        queryInstant(
            "SELECT occurred_at FROM local_draft_undo_receipts "
                + "WHERE principal_id=? AND receipt_id=?",
            draft.principal(),
            receiptId));
  }

  void assertEvolutionResponseSurfaceAbsent(String json) {
    String responseSurface = json.toLowerCase(Locale.ROOT);
    assertFalse(responseSurface.contains("reflection"));
    assertFalse(responseSurface.contains("workingself"));
    assertFalse(responseSurface.contains("working_self"));
    assertFalse(responseSurface.contains("working self"));
  }

  ProblemShape assertSafeProblem(HttpResponse<String> response, int expectedStatus) {
    assertEquals(expectedStatus, response.statusCode());
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
    assertNotNull(type);
    assertNotNull(title);
    assertNotNull(detail);
    String safeText = (title + " " + detail).toLowerCase(Locale.ROOT);
    assertFalse(safeText.contains("action_attempts"));
    assertFalse(safeText.contains("local_drafts"));
    assertFalse(safeText.contains("exception"));
    assertFalse(safeText.contains("stack"));
    assertFalse(SHA_256_TEXT.matcher(safeText).find());
    return new ProblemShape(type, title, expectedStatus, detail);
  }

  void assertNoSensitiveProblemLeak(
      HttpResponse<String> response, Set<String> sensitiveValues, String expectedInstance) {
    Map<String, Object> body = JsonPath.parse(response.body()).read("$");
    assertEquals(expectedInstance, String.valueOf(body.get("instance")));
    String detail = String.valueOf(body.get("detail")).toLowerCase(Locale.ROOT);
    Map<String, Object> normalized = new java.util.LinkedHashMap<>(body);
    normalized.put("instance", "<request-path>");
    String nonEchoProblemSurface = normalized.toString().toLowerCase(Locale.ROOT);
    for (String sensitiveValue : sensitiveValues) {
      assertNotNull(sensitiveValue);
      assertFalse(sensitiveValue.isBlank());
      assertFalse(
          detail.contains(sensitiveValue.toLowerCase(Locale.ROOT)),
          "problem detail leaked a protected identifier");
      assertFalse(
          nonEchoProblemSurface.contains(sensitiveValue.toLowerCase(Locale.ROOT)),
          "problem body leaked a protected identifier outside exact request-path instance");
    }
  }

  void finishAndAssertNoProviderCalls() throws Exception {
    close();
    providerTrap.assertFinalHealthyAndUnused();
  }

  HttpResponse<String> send(
      RunningApplication application, String method, String path, String body)
      throws IOException, InterruptedException {
    return HTTP.send(
        request(application.port(), method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  CompletableFuture<HttpResponse<String>> sendAsync(
      RunningApplication application, String method, String path, String body) {
    return HTTP.sendAsync(
        request(application.port(), method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  void fireAndForget(RunningApplication application, String path, String body)
      throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String headers =
        "POST "
            + path
            + " HTTP/1.1\r\nHost: 127.0.0.1:"
            + application.port()
            + "\r\nAccept: application/json\r\nContent-Type: application/json\r\n"
            + "Content-Length: "
            + bodyBytes.length
            + "\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", application.port()), 2_000);
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(bodyBytes);
      socket.getOutputStream().flush();
      socket.shutdownOutput();
    }
  }

  String approvalPath(String attemptId) {
    return "/api/v1/action-approvals/" + attemptId;
  }

  String undoPath(String attemptId) {
    return approvalPath(attemptId) + "/undo";
  }

  void assertPrivateNoStore(HttpResponse<?> response) {
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null));
  }

  String bodyHash(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  int undoCount(String attemptId) throws Exception {
    return queryInt(
        "SELECT count(*) FROM local_draft_undo_receipts "
            + "WHERE creation_attempt_id=?",
        attemptId);
  }

  int count(String table) throws Exception {
    String allowed =
        switch (table) {
          case "local_drafts", "local_draft_undo_receipts", "action_receipts" -> table;
          default -> throw new IllegalArgumentException("unsupported count table");
        };
    return queryInt("SELECT count(*) FROM " + allowed);
  }

  int countActiveRawDrafts(String principal) throws Exception {
    return queryInt(
        "SELECT count(*) FROM local_drafts WHERE principal_id=? AND state='ACTIVE'",
        principal);
  }

  String undoReceiptDigest(String attemptId) throws Exception {
    return hashRows(
        "SELECT r.xmin::text || '|' || to_jsonb(r)::text AS row_image "
            + "FROM local_draft_undo_receipts r WHERE creation_attempt_id=?",
        attemptId);
  }

  TruthDigest truthDigest() throws Exception {
    return new TruthDigest(
        tableDigest("captures"),
        tableDigest("artifacts"),
        tableDigest("artifact_versions"),
        tableDigest("action_attempts"),
        tableDigest("action_attempt_transitions"),
        tableDigest("local_drafts"),
        tableDigest("local_draft_creation_receipts"));
  }

  DatabaseDigest databaseDigest() throws Exception {
    return new DatabaseDigest(
        truthDigest(),
        tableDigest("local_draft_undo_receipts"),
        tableDigest("action_receipts"));
  }

  void installUndoPrecommitBlocker() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      configureDdlTimeouts(statement);
      statement.execute(
          """
          CREATE FUNCTION real_local_draftbox_undo_crash_precommit_v1()
          RETURNS trigger
          LANGUAGE plpgsql
          AS $$
          BEGIN
            IF NEW.outcome = 'SUCCEEDED'
               AND NEW.receipt_type = 'LOCAL_DRAFT_LOGICALLY_UNDONE_V1' THEN
              PERFORM pg_catalog.pg_advisory_lock(18202608150019);
              PERFORM pg_catalog.pg_advisory_unlock(18202608150019);
            END IF;
            RETURN NULL;
          END
          $$
          """);
      statement.execute(
          "REVOKE ALL ON FUNCTION " + PRECOMMIT_FUNCTION + "() FROM PUBLIC");
      statement.execute(
          """
          CREATE CONSTRAINT TRIGGER zz_real_local_draftbox_undo_crash_precommit_v1
          AFTER INSERT ON local_draft_undo_receipts
          DEFERRABLE INITIALLY IMMEDIATE
          FOR EACH ROW
          EXECUTE FUNCTION real_local_draftbox_undo_crash_precommit_v1()
          """);
    }
  }

  void dropUndoPrecommitBlocker() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      configureDdlTimeouts(statement);
      statement.execute(
          "DROP TRIGGER IF EXISTS " + PRECOMMIT_TRIGGER + " ON local_draft_undo_receipts");
      statement.execute("DROP FUNCTION IF EXISTS " + PRECOMMIT_FUNCTION + "()");
    }
  }

  Connection connection() throws Exception {
    return DriverManager.getConnection(
        postgresJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  int backendPid(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet row = executeBackendPidQuery(statement)) {
      assertTrue(row.next());
      int pid = row.getInt(1);
      assertFalse(row.next());
      return pid;
    }
  }

  void awaitUndoPrecommitBlock(String applicationName, int lockHolderPid) throws Exception {
    String sql =
        """
        SELECT count(*)
        FROM pg_catalog.pg_stat_activity activity
        WHERE activity.datname = current_database()
          AND activity.usename = ?
          AND activity.application_name = ?
          AND activity.state = 'active'
          AND activity.wait_event_type = 'Lock'
          AND activity.wait_event = 'advisory'
          AND ? = ANY(pg_catalog.pg_blocking_pids(activity.pid))
        """;
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (queryInt(sql, postgres.getUsername(), applicationName, lockHolderPid) == 1) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("logical Undo did not reach the precommit blocker");
  }

  void awaitUndoCommitted(String attemptId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (undoCount(attemptId) == 1) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("lost-response logical Undo did not commit exactly once");
  }

  void awaitNoApplicationBackends(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (queryInt(
              "SELECT count(*) FROM pg_catalog.pg_stat_activity "
                  + "WHERE datname=current_database() AND application_name=?",
              application.applicationName())
          == 0) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("stopped packaged JVM retained a database backend");
  }

  void forceStop(RunningApplication application) throws Exception {
    assertTrue(application.process().isAlive(), "packaged JVM exited before forced stop");
    stop(application);
    assertFalse(application.process().isAlive(), "packaged JVM survived forced stop");
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) {
      return;
    }
    Throwable first = null;
    for (RunningApplication application : applications) {
      try {
        stop(application);
      } catch (Exception | AssertionError failure) {
        first = appendFailure(first, failure);
      }
    }
    for (RunningApplication application : applications) {
      try {
        awaitNoApplicationBackends(application);
      } catch (Exception | AssertionError failure) {
        first = appendFailure(first, failure);
      }
    }
    try {
      providerTrap.close();
    } catch (Exception | AssertionError failure) {
      first = appendFailure(first, failure);
    }
    if (first != null) {
      if (first instanceof Exception exception) {
        throw exception;
      }
      throw (AssertionError) first;
    }
    closed = true;
  }

  private static Throwable appendFailure(Throwable first, Throwable failure) {
    if (first == null) {
      return failure;
    }
    first.addSuppressed(failure);
    return first;
  }

  private void awaitLive(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError("packaged application exited before liveness");
      }
      try {
        if (send(application, "GET", "/actuator/health/liveness", null).statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener is still starting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError("packaged application did not become live");
  }

  private HttpRequest request(int port, String method, String path, String body) {
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

  private String logicalUndoScopeHash(ActiveDraft draft, String undoNonce) throws Exception {
    List<String> values =
        List.of(
            "CONFIGURED_LOCAL_PRINCIPAL",
            draft.principal(),
            "EXPLICIT_LOCAL_OWNER_INPUT",
            "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
            "LOGICALLY_UNDO_LOCAL_DRAFT",
            "local://drafts/" + draft.draftId(),
            draft.draftId(),
            draft.attemptId(),
            draft.creationReceiptId(),
            draft.artifact().artifactId(),
            Integer.toString(draft.artifact().version()),
            draft.artifact().hash(),
            "ACTIVE",
            "CAPTURE_ARTIFACT_HISTORY_RETAINED",
            "local-draft-undo-v1",
            "emergeos.local-draftbox",
            "emergeos:local-draftbox",
            "local-draftbox:" + draft.principal(),
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

  private String tableDigest(String table) throws Exception {
    String allowed =
        switch (table) {
          case "captures",
              "artifacts",
              "artifact_versions",
              "action_attempts",
              "action_attempt_transitions",
              "local_drafts",
              "local_draft_creation_receipts",
              "local_draft_undo_receipts",
              "action_receipts" -> table;
          default -> throw new IllegalArgumentException("unsupported digest table");
        };
    return hashRows(
        "SELECT record_row.xmin::text || '|' || to_jsonb(record_row)::text AS row_image FROM "
            + allowed
            + " record_row");
  }

  private String hashRows(String rowQuery, Object... values) throws Exception {
    return queryString(
        "SELECT encode(sha256(convert_to(COALESCE(string_agg(row_image, E'\\n' "
            + "ORDER BY row_image), ''), 'UTF8')), 'hex') FROM ("
            + rowQuery
            + ") rows",
        values);
  }

  private int queryInt(String sql, Object... values) throws Exception {
    return ((Number) query(sql, values)).intValue();
  }

  private String queryString(String sql, Object... values) throws Exception {
    return (String) query(sql, values);
  }

  private Instant queryInstant(String sql, Object... values) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      bind(statement, values);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        Instant result = rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
        assertFalse(rows.next());
        return result;
      }
    }
  }

  private Object query(String sql, Object... values) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      bind(statement, values);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        Object result = rows.getObject(1);
        assertFalse(rows.next());
        return result;
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... values) throws Exception {
    for (int index = 0; index < values.length; index++) {
      statement.setObject(index + 1, values[index]);
    }
  }

  private String postgresJdbcUrl() {
    String jdbcUrl = ensureJdbcParameter(postgres.getJdbcUrl(), "sslmode", "disable");
    jdbcUrl = ensureJdbcParameter(jdbcUrl, "connectTimeout", "2");
    return ensureJdbcParameter(jdbcUrl, "socketTimeout", "5");
  }

  private static String ensureJdbcParameter(String jdbcUrl, String name, String value) {
    int queryStart = jdbcUrl.indexOf('?');
    if (queryStart >= 0) {
      for (String parameter : jdbcUrl.substring(queryStart + 1).split("&")) {
        int equals = parameter.indexOf('=');
        String observed = equals >= 0 ? parameter.substring(0, equals) : parameter;
        if (observed.equalsIgnoreCase(name)) {
          return jdbcUrl;
        }
      }
    }
    return jdbcUrl + (queryStart >= 0 ? "&" : "?") + name + "=" + value;
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static int number(String json, String path) {
    return JsonPath.<Number>read(json, path).intValue();
  }

  private static void configureDdlTimeouts(Statement statement) throws Exception {
    statement.setQueryTimeout(5);
    statement.execute("SET lock_timeout = '2s'");
    statement.execute("SET statement_timeout = '5s'");
  }

  private static ResultSet executeBackendPidQuery(Statement statement) throws Exception {
    statement.setQueryTimeout(5);
    return statement.executeQuery("SELECT pg_catalog.pg_backend_pid()");
  }

  private static void stop(RunningApplication application) throws Exception {
    if (application.process().isAlive()) {
      application.process().destroyForcibly();
      assertTrue(application.process().waitFor(10, TimeUnit.SECONDS));
    }
    Files.deleteIfExists(application.log());
  }

  private static final class CountingNetworkTrap implements AutoCloseable {

    private final ServerSocket listener;
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final AtomicReference<Throwable> observationFailure = new AtomicReference<>();
    private final Thread observer;
    private volatile boolean closing;
    private volatile boolean closed;

    private CountingNetworkTrap() throws IOException {
      listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      listener.setSoTimeout(250);
      observer =
          Thread.ofPlatform()
              .daemon(true)
              .name("logical-undo-provider-counting-trap")
              .start(this::observe);
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + listener.getLocalPort();
    }

    private void assertFinalHealthyAndUnused() {
      assertTrue(closed, "provider trap observation window is still open");
      assertFalse(observer.isAlive(), "provider trap observer is still running");
      assertTrue(observationFailure.get() == null, "provider trap observation failed");
      assertEquals(0, acceptedConnections.get(), "local Undo attempted provider network access");
    }

    private void observe() {
      while (true) {
        try (Socket ignored = listener.accept()) {
          acceptedConnections.incrementAndGet();
        } catch (SocketTimeoutException ignored) {
          if (closing) {
            return;
          }
        } catch (SocketException failure) {
          if (!closing) {
            observationFailure.compareAndSet(null, failure);
          }
          return;
        } catch (IOException failure) {
          observationFailure.compareAndSet(null, failure);
          return;
        }
      }
    }

    @Override
    public synchronized void close() throws Exception {
      if (closed) {
        return;
      }
      closing = true;
      observer.join(TimeUnit.SECONDS.toMillis(5));
      if (observer.isAlive()) {
        listener.close();
        observer.join(TimeUnit.SECONDS.toMillis(5));
      }
      listener.close();
      assertFalse(observer.isAlive(), "provider trap observer did not stop");
      closed = true;
    }
  }

  record RunningApplication(
      Process process, int port, Path log, String applicationName, String principal) {}

  record ArtifactHead(String artifactId, int version, String hash) {}

  record ScopeHead(String schema, String hash) {}

  record ActiveDraft(
      String principal,
      String captureId,
      String captureNonce,
      String captureRequestHash,
      ArtifactHead artifact,
      String approvalScopeHash,
      String planId,
      String planHash,
      String approvalDecisionId,
      String approvalNonce,
      String idempotencyKey,
      String capabilityId,
      String capabilityAccountRef,
      String attemptId,
      String draftId,
      String creationReceiptId,
      String canonicalActiveBody) {

    Set<String> protectedValues() {
      return new LinkedHashSet<>(
          List.of(
              principal,
              captureId,
              captureNonce,
              captureRequestHash,
              artifact.artifactId(),
              Integer.toString(artifact.version()),
              artifact.hash(),
              approvalScopeHash,
              planId,
              planHash,
              approvalDecisionId,
              approvalNonce,
              idempotencyKey,
              capabilityId,
              capabilityAccountRef,
              attemptId,
              draftId,
              creationReceiptId));
    }
  }

  record UndoRequest(String nonce, String scopeHash, String body) {}

  record ProblemShape(String type, String title, int status, String detail) {}

  record TruthDigest(
      String captures,
      String artifacts,
      String artifactVersions,
      String attempts,
      String transitions,
      String drafts,
      String creationReceipts) {}

  record DatabaseDigest(
      TruthDigest truth, String undoReceipts, String legacyActionReceipts) {}
}
