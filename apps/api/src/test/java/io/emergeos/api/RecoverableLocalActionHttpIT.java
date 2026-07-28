package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

@Testcontainers
class RecoverableLocalActionHttpIT {

  private static final String OWNER = "action-owner";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final String AUDIENCE = "adapter:simulated-provider";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void reconcilesOneSimulatedObjectAfterResponseLossAndRealApplicationRestart()
      throws Exception {
    RunningProvider provider = startProvider();
    try {
      List<RunningApplication> contenders = startApplicationPair(OWNER, provider.port());
      RunningApplication first = contenders.get(0);
      RunningApplication second = contenders.get(1);
      long firstPid = first.process().pid();
      long secondPid = second.process().pid();
      String artifactId;
      int artifactVersion;
      String artifactHash;
      String attemptId;
      String beforeRestartBody;
      try {
        HttpResponse<String> captured =
            sendJson(
                first.port(),
                "POST",
                "/api/v1/captures",
                """
              {
                "clientNonce": "s3-action-source-001",
                "content": "synthetic thought for a recoverable simulated action",
                "sourceType": "TEXT",
                "sourceRef": "s3-http-red",
                "dataClass": "PERSONAL"
              }
              """);
        assertEquals(201, captured.statusCode(), captured::body);
        String captureId = JsonPath.read(captured.body(), "$.captureId");
        HttpResponse<String> artifact =
            sendJson(
                first.port(),
                "POST",
                "/api/v1/artifacts",
                """
              {
                "captureId": "%s",
                "content": "synthetic approved local draft"
              }
              """
                    .formatted(captureId));
        assertEquals(201, artifact.statusCode(), artifact::body);
        artifactId = JsonPath.read(artifact.body(), "$.artifactId");
        artifactVersion = number(artifact.body(), "$.currentVersion");
        assertEquals(1, artifactVersion);
        artifactHash = JsonPath.read(artifact.body(), "$.currentHash");

        String noObjectKey = "s3-timeout-without-object-001";
        configureProvider(provider, noObjectKey, "TIMEOUT_WITHOUT_OBJECT");
        HttpResponse<String> noObject =
            sendJson(
                first.port(),
                "POST",
                "/api/v1/artifacts/" + artifactId + "/actions",
                actionRequest(artifactHash, noObjectKey));
        assertEquals(
            202,
            noObject.statusCode(),
            () ->
                "local Action approval must be implemented after the Acceptance Red: "
                    + noObject.body());
        assertEquals("UNKNOWN", JsonPath.read(noObject.body(), "$.status"));
        assertNull(JsonPath.read(noObject.body(), "$.receipt"));
        String noObjectAttemptId = JsonPath.read(noObject.body(), "$.attemptId");
        HttpResponse<String> stillUnknown =
            sendJson(
                first.port(),
                "POST",
                "/api/v1/actions/" + noObjectAttemptId + "/reconcile",
                null);
        assertEquals(202, stillUnknown.statusCode(), stillUnknown::body);
        assertEquals("UNKNOWN", JsonPath.read(stillUnknown.body(), "$.status"));
        assertNull(JsonPath.read(stillUnknown.body(), "$.receipt"));
        ProviderObservation noObjectObservation = observation(provider, noObjectKey);
        assertEquals(2, noObjectObservation.requestCount());
        assertEquals(0, noObjectObservation.objectCount());
        assertEquals("UNKNOWN", databaseStatus(noObjectAttemptId));
        assertEquals(2, databaseCapabilityUses(noObjectAttemptId));
        assertEquals(0, receiptRowCount(noObjectAttemptId));

        String recoveryKey = "s3-response-loss-recovery-001";
        configureProvider(provider, recoveryKey, "BLOCK_CREATE_DROP_ONCE");
        try (ConcurrentRequests requests =
            startConcurrentRequests(
                () ->
                    sendJson(
                        first.port(),
                        "POST",
                        "/api/v1/artifacts/" + artifactId + "/actions",
                        actionRequest(artifactHash, recoveryKey)),
                () ->
                    sendJson(
                        second.port(),
                        "POST",
                        "/api/v1/artifacts/" + artifactId + "/actions",
                        actionRequest(artifactHash, recoveryKey)))) {
          awaitProviderBlocked(provider, recoveryKey);
          attemptId = awaitAttemptId(recoveryKey);
          assertEquals("DISPATCHING", databaseStatus(attemptId));
          assertEquals(1, databaseCapabilityUses(attemptId));
          assertEquals(List.of("PLANNED", "DISPATCHING"), transitionStates(attemptId));
          assertEquals(1, attemptRowCount(recoveryKey));
          assertEquals(0, receiptRowCount(attemptId));
          ProviderObservation beforeRelease = observation(provider, recoveryKey);
          assertEquals(1, beforeRelease.requestCount());
          assertEquals(0, beforeRelease.objectCount());

          releaseProvider(provider, recoveryKey);
          List<HttpResponse<String>> responses = requests.await();
          assertEquals(2, responses.size());
          assertTrue(
              responses.stream().allMatch(response -> response.statusCode() == 202),
              () -> "both same-key requests must remain accepted/holding: " + responses);
        }

        awaitDatabaseStatus(attemptId, "UNKNOWN");
        HttpResponse<String> beforeRestart =
            sendJson(first.port(), "GET", "/api/v1/actions/" + attemptId, null);
        assertEquals(200, beforeRestart.statusCode(), beforeRestart::body);
        assertEquals("UNKNOWN", JsonPath.read(beforeRestart.body(), "$.status"));
        assertNull(JsonPath.read(beforeRestart.body(), "$.receipt"));
        beforeRestartBody = beforeRestart.body();
        assertEquals(0, receiptRowCount(attemptId));
        ProviderObservation afterLoss = observation(provider, recoveryKey);
        assertEquals(1, afterLoss.requestCount());
        assertEquals(1, afterLoss.objectCount());
        assertEquals(1, afterLoss.totalObjectCount());
        assertEquals(1, providerStateRowCount(provider));
      } finally {
        stopAll(first, second);
      }

      assertFalse(first.process().isAlive(), "the first packaged application must be terminated");
      assertFalse(second.process().isAlive(), "the second packaged application must be terminated");
      assertTrue(provider.process().isAlive(), "the independent provider must survive the app kill");

      ProviderObservation beforeAuthorityMismatch =
          observation(provider, "s3-response-loss-recovery-001");
      RunningApplication audienceMismatch =
          startApplication(
              OWNER,
              provider.port(),
              CONNECTOR,
              "adapter:wrong-audience",
              "simulated-account:" + OWNER);
      long audienceMismatchPid = audienceMismatch.process().pid();
      try {
        HttpResponse<String> rejected =
            sendJson(
                audienceMismatch.port(),
                "POST",
                "/api/v1/actions/" + attemptId + "/reconcile",
                null);
        assertEquals(409, rejected.statusCode(), rejected::body);
      } finally {
        stop(audienceMismatch);
      }
      RunningApplication accountMismatch =
          startApplication(
              OWNER,
              provider.port(),
              CONNECTOR,
              AUDIENCE,
              "simulated-account:wrong-account");
      long accountMismatchPid = accountMismatch.process().pid();
      try {
        HttpResponse<String> rejected =
            sendJson(
                accountMismatch.port(),
                "POST",
                "/api/v1/actions/" + attemptId + "/reconcile",
                null);
        assertEquals(409, rejected.statusCode(), rejected::body);
      } finally {
        stop(accountMismatch);
      }
      assertEquals(
          beforeAuthorityMismatch,
          observation(provider, "s3-response-loss-recovery-001"),
          "authority mismatch must be rejected before provider access");
      assertEquals("UNKNOWN", databaseStatus(attemptId));
      assertEquals(1, databaseCapabilityUses(attemptId));
      assertEquals(0, receiptRowCount(attemptId));

      RunningApplication restarted = startApplication(OWNER, provider.port());
      try {
        assertNotEquals(firstPid, restarted.process().pid());
        assertNotEquals(secondPid, restarted.process().pid());
        HttpResponse<String> recoveredUnknown =
            sendJson(restarted.port(), "GET", "/api/v1/actions/" + attemptId, null);
        assertEquals(200, recoveredUnknown.statusCode(), recoveredUnknown::body);
        assertEquals(beforeRestartBody, recoveredUnknown.body());
        assertEquals("UNKNOWN", JsonPath.read(recoveredUnknown.body(), "$.status"));

      HttpResponse<String> reconciled =
          sendJson(
              restarted.port(),
              "POST",
              "/api/v1/actions/" + attemptId + "/reconcile",
              null);
      assertEquals(200, reconciled.statusCode(), reconciled::body);
      assertEquals("SUCCEEDED", JsonPath.read(reconciled.body(), "$.status"));
      assertEquals(true, JsonPath.read(reconciled.body(), "$.receipt.simulated"));
      assertTrue(
          ((String) JsonPath.read(reconciled.body(), "$.receipt.externalId"))
              .startsWith("sim-object-"));
      assertEquals(2, number(reconciled.body(), "$.capability.usedCalls"));
      assertEquals(2, number(reconciled.body(), "$.capability.maxCalls"));
      assertEquals(
          List.of("PLANNED", "DISPATCHING", "UNKNOWN", "RECONCILING", "SUCCEEDED"),
          JsonPath.read(reconciled.body(), "$.transitions[*].toStatus"));
      assertEquals("SUCCEEDED", databaseStatus(attemptId));
      assertEquals(2, databaseCapabilityUses(attemptId));
      assertEquals(1, receiptRowCount(attemptId));
      assertEquals(
          List.of("PLANNED", "DISPATCHING", "UNKNOWN", "RECONCILING", "SUCCEEDED"),
          transitionStates(attemptId));
      ProviderObservation afterReconcile = observation(provider, "s3-response-loss-recovery-001");
      assertEquals(2, afterReconcile.requestCount());
      assertEquals(1, afterReconcile.objectCount());
      assertEquals(1, afterReconcile.totalObjectCount());
      assertEquals(1, providerStateRowCount(provider));

      HttpResponse<String> replay =
          sendJson(
              restarted.port(),
              "POST",
              "/api/v1/actions/" + attemptId + "/reconcile",
              null);
      assertEquals(200, replay.statusCode(), replay::body);
      assertEquals(reconciled.body(), replay.body());
      assertEquals(
          afterReconcile,
          observation(provider, "s3-response-loss-recovery-001"),
          "a terminal replay must not spend budget or call the provider");

      RunningApplication otherPrincipal =
          startApplication("other-action-owner", provider.port());
      try {
        HttpResponse<String> foreign =
            sendJson(otherPrincipal.port(), "GET", "/api/v1/actions/" + attemptId, null);
        HttpResponse<String> missing =
            sendJson(
                otherPrincipal.port(), "GET", "/api/v1/actions/missing-attempt", null);
        assertSameProblem(foreign, missing);

        HttpResponse<String> foreignReconcile =
            sendJson(
                otherPrincipal.port(),
                "POST",
                "/api/v1/actions/" + attemptId + "/reconcile",
                null);
        HttpResponse<String> missingReconcile =
            sendJson(
                otherPrincipal.port(),
                "POST",
                "/api/v1/actions/missing-attempt/reconcile",
                null);
        assertSameProblem(foreignReconcile, missingReconcile);

        System.out.printf(
            "S3_PROCESS_RECEIPT providerPid=%d firstAppPid=%d secondAppPid=%d "
                + "restartedAppPid=%d otherPrincipalPid=%d attemptRows=1 receiptRows=1 "
                + "capabilityUses=2 providerObjects=1 providerCalls=2 "
                + "states=PLANNED>DISPATCHING>UNKNOWN>RECONCILING>SUCCEEDED "
                + "audienceMismatchPid=%d accountMismatchPid=%d "
                + "audienceMismatch=409 accountMismatch=409 "
                + "foreignGet=404 missingGet=404 foreignReconcile=404 missingReconcile=404 "
                + "simulated=true%n",
            provider.process().pid(),
            firstPid,
            secondPid,
            restarted.process().pid(),
            otherPrincipal.process().pid(),
            audienceMismatchPid,
            accountMismatchPid);
      } finally {
        stop(otherPrincipal);
      }
      } finally {
        stop(restarted);
      }
    } finally {
      stopProvider(provider);
    }
  }

  private static String actionRequest(String artifactHash, String idempotencyKey) {
    return """
        {
          "approvedArtifactHash": "%s",
          "idempotencyKey": "%s"
        }
        """
        .formatted(artifactHash, idempotencyKey);
  }

  private static ConcurrentRequests startConcurrentRequests(
      ThrowingRequest first, ThrowingRequest second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<HttpResponse<String>> firstFuture =
        executor.submit(
            () -> {
              ready.countDown();
              assertTrue(ready.await(5, TimeUnit.SECONDS));
              start.await();
              return first.send();
            });
    Future<HttpResponse<String>> secondFuture =
        executor.submit(
            () -> {
              ready.countDown();
              assertTrue(ready.await(5, TimeUnit.SECONDS));
              start.await();
              return second.send();
            });
    assertTrue(ready.await(5, TimeUnit.SECONDS), "both app requests must be ready");
    start.countDown();
    return new ConcurrentRequests(executor, firstFuture, secondFuture);
  }

  private static RunningProvider startProvider() throws Exception {
    int port = availableLoopbackPort();
    Path directory = Files.createTempDirectory("emerge-s3-provider-");
    Path stateFile = directory.resolve("objects.tsv");
    Path log = directory.resolve("provider.log");
    Path testClasses =
        Path.of(
            SimulatedProviderProcessMain.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    List<String> command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-modules",
            "jdk.httpserver",
            "-cp",
            testClasses.toString(),
            SimulatedProviderProcessMain.class.getName(),
            Integer.toString(port),
            stateFile.toString());
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    RunningProvider provider = new RunningProvider(process, port, directory, stateFile, log);
    try {
      awaitProviderHealthy(provider);
      return provider;
    } catch (Exception | AssertionError startupFailure) {
      stopProviderAfterFailure(provider, startupFailure);
      throw startupFailure;
    }
  }

  private static List<RunningApplication> startApplicationPair(
      String principalId, int providerPort) throws Exception {
    RunningApplication first = startApplication(principalId, providerPort);
    try {
      return List.of(first, startApplication(principalId, providerPort));
    } catch (Exception | AssertionError startupFailure) {
      stopAfterFailure(first, startupFailure);
      throw startupFailure;
    }
  }

  private static RunningApplication startApplication(String principalId, int providerPort)
      throws Exception {
    return startApplication(
        principalId,
        providerPort,
        CONNECTOR,
        AUDIENCE,
        "simulated-account:" + principalId);
  }

  private static RunningApplication startApplication(
      String principalId,
      int providerPort,
      String connector,
      String audience,
      String accountRef)
      throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-s3-application-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + principalId);
    command.add("--emerge.simulated-provider.base-url=http://127.0.0.1:" + providerPort);
    command.add("--emerge.simulated-provider.connector=" + connector);
    command.add("--emerge.simulated-provider.audience=" + audience);
    command.add("--emerge.simulated-provider.account-ref=" + accountRef);
    command.add("--emerge.simulated-provider.request-timeout=PT0.5S");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitApplicationHealthy(application);
      return application;
    } catch (Exception | AssertionError startupFailure) {
      stopAfterFailure(application, startupFailure);
      throw startupFailure;
    }
  }

  private static void awaitApplicationHealthy(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError(
            "packaged application exited before becoming healthy:\n"
                + Files.readString(application.log()));
      }
      try {
        if (sendJson(application.port(), "GET", "/actuator/health", null).statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become healthy:\n"
            + Files.readString(application.log()));
  }

  private static void awaitProviderHealthy(RunningProvider provider) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (!provider.process().isAlive()) {
        throw new AssertionError(
            "simulated provider exited before becoming healthy:\n"
                + Files.readString(provider.log()));
      }
      try {
        HttpResponse<String> health =
            sendProvider(provider, "GET", "/health", null);
        if (health.statusCode() == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The provider listener may not be ready yet.
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "simulated provider did not become healthy:\n" + Files.readString(provider.log()));
  }

  private static void awaitProviderBlocked(RunningProvider provider, String key) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (observation(provider, key).blocked()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("simulated provider did not reach its deterministic hold point");
  }

  private static String awaitAttemptId(String key) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      String attemptId =
          queryOptionalString(
              "SELECT attempt_id FROM action_attempts "
                  + "WHERE connector = ? AND account_ref = ? AND idempotency_key = ?",
              CONNECTOR,
              "simulated-account:" + OWNER,
              key);
      if (attemptId != null) {
        return attemptId;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("durable ActionAttempt was not visible before provider release");
  }

  private static void awaitDatabaseStatus(String attemptId, String expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (expected.equals(databaseStatus(attemptId))) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError(
        "ActionAttempt did not reach " + expected + "; current=" + databaseStatus(attemptId));
  }

  private static void configureProvider(RunningProvider provider, String key, String mode)
      throws Exception {
    HttpResponse<String> response =
        sendProvider(
            provider,
            "POST",
            "/admin/configure",
            form(Map.of("idempotencyKey", key, "mode", mode)));
    assertEquals(204, response.statusCode(), response::body);
  }

  private static void releaseProvider(RunningProvider provider, String key) throws Exception {
    HttpResponse<String> response =
        sendProvider(
            provider,
            "POST",
            "/admin/release",
            form(Map.of("idempotencyKey", key)));
    assertEquals(204, response.statusCode(), response::body);
  }

  private static ProviderObservation observation(RunningProvider provider, String key)
      throws Exception {
    HttpResponse<String> response =
        sendProvider(
            provider,
            "GET",
            "/admin/observation?idempotencyKey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8),
            null);
    assertEquals(200, response.statusCode(), response::body);
    return new ProviderObservation(
        number(response.body(), "$.requestCount"),
        number(response.body(), "$.objectCount"),
        number(response.body(), "$.totalObjectCount"),
        JsonPath.read(response.body(), "$.blocked"));
  }

  private static HttpResponse<String> sendProvider(
      RunningProvider provider, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + provider.port() + path))
            .timeout(Duration.ofSeconds(5));
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/x-www-form-urlencoded")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> sendJson(
      int port, String method, String path, String body) throws IOException, InterruptedException {
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

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .sorted()
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static int number(String json, String path) {
    return ((Number) JsonPath.read(json, path)).intValue();
  }

  private static String databaseStatus(String attemptId) throws Exception {
    return queryRequiredString(
        "SELECT status FROM action_attempts WHERE principal_id = ? AND attempt_id = ?",
        OWNER,
        attemptId);
  }

  private static int databaseCapabilityUses(String attemptId) throws Exception {
    return queryRequiredInt(
        "SELECT capability_used_calls FROM action_attempts "
            + "WHERE principal_id = ? AND attempt_id = ?",
        OWNER,
        attemptId);
  }

  private static int attemptRowCount(String key) throws Exception {
    return queryRequiredInt(
        "SELECT count(*) FROM action_attempts "
            + "WHERE connector = ? AND account_ref = ? AND idempotency_key = ?",
        CONNECTOR,
        "simulated-account:" + OWNER,
        key);
  }

  private static int receiptRowCount(String attemptId) throws Exception {
    return queryRequiredInt(
        "SELECT count(*) FROM action_receipts WHERE principal_id = ? AND attempt_id = ?",
        OWNER,
        attemptId);
  }

  private static List<String> transitionStates(String attemptId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  private static String queryRequiredString(String sql, String first, String second)
      throws Exception {
    String value = queryOptionalString(sql, first, second);
    if (value == null) {
      throw new AssertionError("query returned no row: " + sql);
    }
    return value;
  }

  private static String queryOptionalString(String sql, String... parameters)
      throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private static int queryRequiredInt(String sql, String... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static int providerStateRowCount(RunningProvider provider) throws IOException {
    if (!Files.exists(provider.stateFile())) {
      return 0;
    }
    return (int)
        Files.readAllLines(provider.stateFile(), StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .count();
  }

  private static void assertSameProblem(
      HttpResponse<String> foreign, HttpResponse<String> missing) {
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

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stopAll(RunningApplication... applications) throws Exception {
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
    rethrow(failure);
  }

  private static void stopAfterFailure(
      RunningApplication application, Throwable originalFailure) {
    try {
      stop(application);
    } catch (Exception | AssertionError cleanupFailure) {
      if (originalFailure != null) {
        originalFailure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    Throwable failure = null;
    try {
      if (application.process().isAlive()) {
        application.process().destroyForcibly();
        if (!application.process().waitFor(10, TimeUnit.SECONDS)) {
          failure = new AssertionError("packaged application did not terminate");
        }
      }
    } catch (Exception | AssertionError cleanupFailure) {
      failure = cleanupFailure;
    }
    try {
      Files.deleteIfExists(application.log());
    } catch (IOException logCleanupFailure) {
      if (failure == null) {
        failure = logCleanupFailure;
      } else {
        failure.addSuppressed(logCleanupFailure);
      }
    }
    rethrow(failure);
  }

  private static void stopProviderAfterFailure(
      RunningProvider provider, Throwable originalFailure) {
    try {
      stopProvider(provider);
    } catch (Exception | AssertionError cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
  }

  private static void stopProvider(RunningProvider provider) throws Exception {
    Throwable failure = null;
    try {
      if (provider.process().isAlive()) {
        provider.process().destroyForcibly();
        if (!provider.process().waitFor(10, TimeUnit.SECONDS)) {
          failure = new AssertionError("simulated provider did not terminate");
        }
      }
    } catch (Exception | AssertionError cleanupFailure) {
      failure = cleanupFailure;
    }
    try {
      if (Files.exists(provider.directory())) {
        try (var paths = Files.walk(provider.directory())) {
          paths.sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  path -> {
                    try {
                      Files.deleteIfExists(path);
                    } catch (IOException cleanupFailure) {
                      throw new ProviderCleanupException(cleanupFailure);
                    }
                  });
        }
      }
    } catch (IOException | ProviderCleanupException cleanupFailure) {
      Throwable actual =
          cleanupFailure instanceof ProviderCleanupException wrapper
              ? wrapper.getCause()
              : cleanupFailure;
      if (failure == null) {
        failure = actual;
      } else {
        failure.addSuppressed(actual);
      }
    }
    rethrow(failure);
  }

  private static void rethrow(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof AssertionError assertionError) {
      throw assertionError;
    }
  }

  @FunctionalInterface
  private interface ThrowingRequest {
    HttpResponse<String> send() throws Exception;
  }

  private record ConcurrentRequests(
      ExecutorService executor,
      Future<HttpResponse<String>> first,
      Future<HttpResponse<String>> second)
      implements AutoCloseable {

    private List<HttpResponse<String>> await() throws Exception {
      return List.of(
          first.get(10, TimeUnit.SECONDS),
          second.get(10, TimeUnit.SECONDS));
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}

  private record RunningProvider(
      Process process, int port, Path directory, Path stateFile, Path log) {}

  private record ProviderObservation(
      int requestCount, int objectCount, int totalObjectCount, boolean blocked) {}

  private static final class ProviderCleanupException extends RuntimeException {
    private ProviderCleanupException(IOException cause) {
      super(cause);
    }

    @Override
    public synchronized IOException getCause() {
      return (IOException) super.getCause();
    }
  }
}
