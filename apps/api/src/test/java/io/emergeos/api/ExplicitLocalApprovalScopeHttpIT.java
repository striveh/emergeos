package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Acceptance Red for the read-only, explicit local approval-scope preview. */
@Testcontainers
class ExplicitLocalApprovalScopeHttpIT {

  private static final String OWNER = "explicit-local-approval-scope-owner";
  private static final String SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
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
  void exposesTheExactCurrentArtifactApprovalScopeWithoutExecuting() throws Exception {
    RunningApplication application = startApplication();
    try {
      ArtifactHead artifact = createArtifact(application.port(), "primary");
      HttpResponse<String> persisted =
          send(application.port(), "GET", "/api/v1/artifacts/" + artifact.artifactId(), null);
      assertEquals(200, persisted.statusCode(), persisted::body);
      assertEquals(artifact.artifactId(), JsonPath.read(persisted.body(), "$.artifactId"));
      assertEquals(artifact.version(), number(persisted.body(), "$.currentVersion"));
      assertEquals(artifact.hash(), JsonPath.read(persisted.body(), "$.currentHash"));

      String path =
          "/api/v1/artifacts/"
              + artifact.artifactId()
              + "/action-approval-scope?artifactVersion="
              + artifact.version()
              + "&artifactHash="
              + artifact.hash();
      HttpResponse<String> scope = send(application.port(), "GET", path, null);
      if (scope.statusCode() != 200) {
        System.out.printf(
            "EXPLICIT_LOCAL_APPROVAL_SCOPE_MISSING expected=200 actual=%d%n",
            scope.statusCode());
      }
      assertEquals(
          200,
          scope.statusCode(),
          "EXPLICIT_LOCAL_APPROVAL_SCOPE_MISSING expected=200 actual=" + scope.statusCode());
      ScopeHead exactScope = assertExactScope(scope, artifact);
      assertEquals(0, queryInt("SELECT count(*) FROM action_attempts"));
      assertEquals(0, queryInt("SELECT count(*) FROM action_attempt_transitions"));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));

      String nonce = "explicit-local-approval-scope-approval-001";
      HttpResponse<String> missingScope =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              """
              {
                "approvedArtifactVersion": %d,
                "approvedArtifactHash": "%s",
                "approvalNonce": "%s"
              }
              """.formatted(artifact.version(), artifact.hash(), nonce));
      assertEquals(400, missingScope.statusCode(), missingScope::body);
      assertPrivateNoStore(missingScope);
      assertEquals(0, queryInt("SELECT count(*) FROM action_attempts"));

      String approval = approvalRequest(artifact, nonce, exactScope);
      HttpResponse<String> created =
          send(application.port(), "POST", "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals", approval);
      assertEquals(201, created.statusCode(), created::body);
      assertPrivateNoStore(created);
      String attemptId = assertPlannedScopeParity(created.body(), artifact, exactScope);
      assertEquals("/api/v1/action-approvals/" + attemptId, created.headers().firstValue("Location").orElse(null));

      HttpResponse<String> replay =
          send(application.port(), "POST", "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals", approval);
      assertEquals(200, replay.statusCode(), replay::body);
      assertPrivateNoStore(replay);
      assertEquals(created.body(), replay.body());
      HttpResponse<String> persistedApproval =
          send(application.port(), "GET", "/api/v1/action-approvals/" + attemptId, null);
      assertEquals(200, persistedApproval.statusCode(), persistedApproval::body);
      assertPrivateNoStore(persistedApproval);
      assertEquals(created.body(), persistedApproval.body());

      HttpResponse<String> wrongSchema =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              approvalRequest(artifact, "explicit-local-approval-scope-wrong-schema", new ScopeHead("emergeos.action-approval-scope.v2", exactScope.hash())));
      assertApprovalStale(wrongSchema);
      String wrongHashValue = (exactScope.hash().charAt(0) == 'a' ? "b" : "a") + exactScope.hash().substring(1);
      HttpResponse<String> wrongHash =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              approvalRequest(artifact, "explicit-local-approval-scope-wrong-hash", new ScopeHead(SCOPE_SCHEMA, wrongHashValue)));
      assertApprovalStale(wrongHash);

      HttpResponse<String> revised =
          send(
              application.port(),
              "PUT",
              "/api/v1/artifacts/" + artifact.artifactId(),
              """
              {
                "content": "synthetic revised explicit approval scope artifact",
                "expectedBaseVersion": %d,
                "expectedBaseHash": "%s"
              }
              """.formatted(artifact.version(), artifact.hash()));
      assertEquals(200, revised.statusCode(), revised::body);
      HttpResponse<String> stale =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + artifact.artifactId() + "/action-approvals",
              approvalRequest(artifact, "explicit-local-approval-scope-stale", exactScope));
      assertApprovalStale(stale);

      assertEquals(1, queryInt("SELECT count(*) FROM action_attempts WHERE approval_origin='EXPLICIT_LOCAL_OWNER_INPUT' AND execution_route='LOCAL_DRAFTBOX_V2' AND status='PLANNED' AND capability_used_calls=0"));
      assertEquals(1, queryInt("SELECT count(*) FROM action_attempt_transitions"));
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
      assertEquals(exactScope.hash(), queryString("SELECT scope_hash FROM action_attempts WHERE attempt_id=?", attemptId));

      ArtifactHead legacyArtifact = createArtifact(application.port(), "legacy");
      HttpResponse<String> legacy =
          send(
              application.port(),
              "POST",
              "/api/v1/artifacts/" + legacyArtifact.artifactId() + "/actions",
              """
              {
                "approvedArtifactHash": "%s",
                "idempotencyKey": "explicit-local-approval-legacy-001"
              }
              """.formatted(legacyArtifact.hash()));
      assertEquals(202, legacy.statusCode(), legacy::body);
      assertPrivateNoStore(legacy);
      String legacyAttemptId = JsonPath.read(legacy.body(), "$.attemptId");
      assertEquals("UNKNOWN", JsonPath.read(legacy.body(), "$.status"));
      assertEquals(1, queryInt("SELECT count(*) FROM action_attempts WHERE attempt_id=? AND approval_origin='LEGACY_SERVER_IMPLICIT' AND execution_route='SIMULATED_PROVIDER_V1'", legacyAttemptId));
      HttpResponse<String> legacyApprovalView =
          send(application.port(), "GET", "/api/v1/action-approvals/" + legacyAttemptId, null);
      assertEquals(404, legacyApprovalView.statusCode(), legacyApprovalView::body);
      assertPrivateNoStore(legacyApprovalView);
      assertEquals(0, queryInt("SELECT count(*) FROM action_receipts"));
    } finally {
      stop(application);
    }
  }

  private static ArtifactHead createArtifact(int port, String suffix) throws Exception {
    HttpResponse<String> captured =
        send(
            port,
            "POST",
            "/api/v1/captures",
            """
            {
              "clientNonce": "explicit-local-approval-scope-capture-%s",
              "content": "synthetic explicit local approval scope source %s",
              "sourceType": "TEXT",
              "sourceRef": "explicit-local-approval-scope:http-it",
              "dataClass": "PERSONAL"
            }
            """.formatted(suffix, suffix));
    assertEquals(201, captured.statusCode(), captured::body);
    String captureId = JsonPath.read(captured.body(), "$.captureId");

    HttpResponse<String> drafted =
        send(
            port,
            "POST",
            "/api/v1/agent-drafts",
            """
            {
              "captureId": "%s",
              "intent": "Create one local Artifact for explicit approval-scope acceptance"
            }
            """
                .formatted(captureId));
    assertEquals(201, drafted.statusCode(), drafted::body);
    assertEquals("SUCCEEDED", JsonPath.read(drafted.body(), "$.result.status"));
    String artifactLocation = drafted.headers().firstValue("Location").orElse(null);
    assertNotNull(artifactLocation);
    assertTrue(artifactLocation.startsWith("/api/v1/artifacts/"));

    HttpResponse<String> artifact = send(port, "GET", artifactLocation, null);
    assertEquals(200, artifact.statusCode(), artifact::body);
    assertEquals(captureId, JsonPath.read(artifact.body(), "$.captureId"));
    return new ArtifactHead(
        JsonPath.read(artifact.body(), "$.artifactId"),
        number(artifact.body(), "$.currentVersion"),
        JsonPath.read(artifact.body(), "$.currentHash"));
  }

  private static ScopeHead assertExactScope(
      HttpResponse<String> response, ArtifactHead artifact) throws Exception {
    assertPrivateNoStore(response);
    Map<String, Object> body = JsonPath.parse(response.body()).read("$");
    assertEquals(
        Set.of(
            "scopeSchema",
            "scopeHash",
            "approvalPrincipal",
            "provenance",
            "action",
            "artifact",
            "capability",
            "executionState"),
        body.keySet());
    assertEquals(SCOPE_SCHEMA, JsonPath.read(response.body(), "$.scopeSchema"));
    assertEquals("CONFIGURED_LOCAL_PRINCIPAL", JsonPath.read(response.body(), "$.approvalPrincipal.basis"));
    assertEquals(OWNER, JsonPath.read(response.body(), "$.approvalPrincipal.configuredPrincipalId"));
    assertEquals("EXPLICIT_LOCAL_OWNER_INPUT", JsonPath.read(response.body(), "$.provenance.approvalOrigin"));
    assertEquals("LOCAL_DRAFTBOX_V2", JsonPath.read(response.body(), "$.provenance.executionRoute"));
    assertEquals("CREATE_LOCAL_DRAFT", JsonPath.read(response.body(), "$.action.actionType"));
    assertEquals("local://drafts", JsonPath.read(response.body(), "$.action.targetRef"));
    assertEquals("REVERSIBLE", JsonPath.read(response.body(), "$.action.risk"));
    assertEquals("local-action-v2", JsonPath.read(response.body(), "$.action.policyVersion"));
    assertEquals(artifact.artifactId(), JsonPath.read(response.body(), "$.artifact.artifactId"));
    assertEquals(artifact.version(), number(response.body(), "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(response.body(), "$.artifact.artifactHash"));
    assertEquals(
        "emergeos.local-draftbox", JsonPath.read(response.body(), "$.capability.connector"));
    assertEquals(
        "emergeos:local-draftbox", JsonPath.read(response.body(), "$.capability.audience"));
    assertEquals(
        "local-draftbox:" + OWNER, JsonPath.read(response.body(), "$.capability.accountRef"));
    assertEquals(1, number(response.body(), "$.capability.maxCalls"));
    assertEquals(0, number(response.body(), "$.capability.usedCalls"));
    assertEquals("NOT_EXECUTED", JsonPath.read(response.body(), "$.executionState"));
    String hash = JsonPath.read(response.body(), "$.scopeHash");
    assertTrue(hash.matches("[0-9a-f]{64}"));
    assertEquals(hash, recomputeScopeHash(response.body()));
    return new ScopeHead(SCOPE_SCHEMA, hash);
  }

  private static String recomputeScopeHash(String json) throws Exception {
    List<String> values =
        List.of(
            JsonPath.read(json, "$.approvalPrincipal.basis"),
            JsonPath.read(json, "$.approvalPrincipal.configuredPrincipalId"),
            JsonPath.read(json, "$.provenance.approvalOrigin"),
            JsonPath.read(json, "$.provenance.executionRoute"),
            JsonPath.read(json, "$.action.actionType"),
            JsonPath.read(json, "$.action.targetRef"),
            JsonPath.read(json, "$.artifact.artifactId"),
            Integer.toString(number(json, "$.artifact.artifactVersion")),
            JsonPath.read(json, "$.artifact.artifactHash"),
            JsonPath.read(json, "$.action.risk"),
            JsonPath.read(json, "$.action.policyVersion"),
            JsonPath.read(json, "$.capability.connector"),
            JsonPath.read(json, "$.capability.audience"),
            JsonPath.read(json, "$.capability.accountRef"),
            Long.toString(JsonPath.<Number>read(json, "$.capability.capabilityTtlMicros").longValue()),
            Integer.toString(number(json, "$.capability.maxCalls")));
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    canonical.writeBytes(SCOPE_SCHEMA.getBytes(StandardCharsets.UTF_8));
    canonical.write(0);
    for (String value : values) {
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      canonical.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
      canonical.writeBytes(utf8);
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
  }

  private static String approvalRequest(
      ArtifactHead artifact, String nonce, ScopeHead scope) {
    return """
        {
          "approvedArtifactVersion": %d,
          "approvedArtifactHash": "%s",
          "approvalNonce": "%s",
          "approvedScopeSchema": "%s",
          "approvedScopeHash": "%s"
        }
        """.formatted(artifact.version(), artifact.hash(), nonce, scope.schema(), scope.hash());
  }

  private static String assertPlannedScopeParity(
      String json, ArtifactHead artifact, ScopeHead scope) {
    String attemptId = JsonPath.read(json, "$.attemptId");
    assertTrue(attemptId != null && !attemptId.isBlank());
    assertEquals("PLANNED", JsonPath.read(json, "$.status"));
    assertEquals(scope.schema(), JsonPath.read(json, "$.scopeSchema"));
    assertEquals(scope.hash(), JsonPath.read(json, "$.scopeHash"));
    assertEquals("EXPLICIT_LOCAL_OWNER_INPUT", JsonPath.read(json, "$.provenance.approvalOrigin"));
    assertEquals("LOCAL_DRAFTBOX_V2", JsonPath.read(json, "$.provenance.executionRoute"));
    assertEquals("local-action-v2", JsonPath.read(json, "$.action.policyVersion"));
    assertEquals(artifact.artifactId(), JsonPath.read(json, "$.artifact.artifactId"));
    assertEquals(artifact.version(), number(json, "$.artifact.artifactVersion"));
    assertEquals(artifact.hash(), JsonPath.read(json, "$.artifact.artifactHash"));
    assertEquals("emergeos.local-draftbox", JsonPath.read(json, "$.capability.connector"));
    assertEquals("emergeos:local-draftbox", JsonPath.read(json, "$.capability.audience"));
    assertEquals("local-draftbox:" + OWNER, JsonPath.read(json, "$.capability.accountRef"));
    assertEquals(1, number(json, "$.capability.maxCalls"));
    assertEquals(0, number(json, "$.capability.usedCalls"));
    assertEquals("NOT_EXECUTED", JsonPath.read(json, "$.executionState"));
    assertEquals(1, number(json, "$.transitions.length()"));
    assertEquals("PLANNED", JsonPath.read(json, "$.transitions[0].toStatus"));
    assertNull(JsonPath.read(json, "$.receipt"));
    return attemptId;
  }

  private static void assertApprovalStale(HttpResponse<String> response) {
    assertEquals(412, response.statusCode(), response::body);
    assertPrivateNoStore(response);
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
    assertEquals(412, number(response.body(), "$.status"));
    assertEquals("urn:emergeos:problem:approval-stale", JsonPath.read(response.body(), "$.type"));
    assertEquals("Action approval stale", JsonPath.read(response.body(), "$.title"));
  }

  private static void assertPrivateNoStore(HttpResponse<?> response) {
    assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(null));
  }

  private static int queryInt(String sql, Object... values) throws Exception {
    return ((Number) query(sql, values)).intValue();
  }

  private static String queryString(String sql, Object... values) throws Exception {
    return (String) query(sql, values);
  }

  private static Object query(String sql, Object... values) throws Exception {
    try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-explicit-approval-scope-", ".log");
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
        throw new AssertionError("packaged application exited:\n" + Files.readString(application.log()));
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
    throw new AssertionError("packaged application did not become live:\n" + Files.readString(application.log()));
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

  private static int number(String json, String path) {
    return JsonPath.<Number>read(json, path).intValue();
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

  private record ScopeHead(String schema, String hash) {}
}
