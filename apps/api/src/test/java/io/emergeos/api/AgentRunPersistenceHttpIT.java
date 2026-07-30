package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AgentRunPersistenceHttpIT {

  private static final String RAW_CAPTURE_SENTINEL =
      "RAW_CAPTURE_SENTINEL: persist the idea, never persist this text in safe Trace";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void persistsAnIntegrityBoundSafeAgentRunAcrossAForcedJvmRestart() throws Exception {
    RunningApplication first = startApplication();
    long firstPid = first.process().pid();
    String runId;
    String runPath;
    String tracePath;
    String bundlePath;
    String runBeforeRestart;
    String traceBeforeRestart;
    String bundleBeforeRestart;
    try {
      HttpResponse<String> captured =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "agent-run-persistence-001",
                "content": "%s",
                "sourceType": "TEXT",
                "sourceRef": "stage2-s2-agent-run-red",
                "dataClass": "PERSONAL"
              }
              """
                  .formatted(RAW_CAPTURE_SENTINEL));
      assertEquals(201, captured.statusCode(), captured::body);
      String captureId = JsonPath.read(captured.body(), "$.captureId");

      HttpResponse<String> drafted =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/agent-drafts",
              """
              {
                "captureId": "%s",
                "intent": "Create an integrity-bound synthetic article draft"
              }
              """
                  .formatted(captureId));
      assertEquals(201, drafted.statusCode(), drafted::body);

      runId = JsonPath.read(drafted.body(), "$.runId");
      assertNotNull(runId);
      assertEquals(runId, JsonPath.read(drafted.body(), "$.result.runId"));
      assertEquals(
          "/api/v1/agent-runs/" + runId,
          JsonPath.read(drafted.body(), "$.runRef"));
      assertEquals(
          "/api/v1/agent-runs/" + runId + "/trace",
          JsonPath.read(drafted.body(), "$.result.traceRef"));

      runPath = "/api/v1/agent-runs/" + runId;
      tracePath = runPath + "/trace";
      bundlePath = runPath + "/bundle";
      runBeforeRestart = requireOwnedJson(first.port(), runPath);
      traceBeforeRestart = requireOwnedJson(first.port(), tracePath);
      bundleBeforeRestart = requireOwnedJson(first.port(), bundlePath);

      assertEquals(runId, JsonPath.read(runBeforeRestart, "$.runId"));
      assertEquals("SUCCEEDED", JsonPath.read(runBeforeRestart, "$.state"));
      assertEquals(runId, JsonPath.read(runBeforeRestart, "$.result.runId"));
      assertEquals(runId, JsonPath.read(traceBeforeRestart, "$.runId"));
      assertEquals(
          List.of(1, 2, 3, 4, 5, 6),
          JsonPath.read(traceBeforeRestart, "$.events[*].sequence"));
      assertEquals(
          List.of(
              "MODEL_STEP",
              "TOOL_REQUEST",
              "TOOL_RESULT",
              "MODEL_STEP",
              "STRUCTURED_FINAL",
              "ARTIFACT_COMMITTED"),
          JsonPath.read(traceBeforeRestart, "$.events[*].type"));
      assertEquals(runId, JsonPath.read(bundleBeforeRestart, "$.runId"));
      assertEquals(runId, JsonPath.read(bundleBeforeRestart, "$.result.runId"));
      assertEquals(
          JsonPath.<String>read(traceBeforeRestart, "$.rootHash"),
          JsonPath.<String>read(bundleBeforeRestart, "$.traceRootHash"));
      assertTrue(
          JsonPath.<String>read(bundleBeforeRestart, "$.integrityHash")
              .matches("[a-f0-9]{64}"));

      String safeEvidence = runBeforeRestart + traceBeforeRestart + bundleBeforeRestart;
      assertFalse(safeEvidence.contains(RAW_CAPTURE_SENTINEL));
      assertFalse(safeEvidence.contains("COT_SENTINEL"));
    } finally {
      forceStopAlive(first);
    }

    RunningApplication second = startApplication();
    assertNotEquals(firstPid, second.process().pid());
    try {
      assertEquals(
          semanticJson(runBeforeRestart),
          semanticJson(requireOwnedJson(second.port(), runPath)));
      assertEquals(
          semanticJson(traceBeforeRestart),
          semanticJson(requireOwnedJson(second.port(), tracePath)));
      assertEquals(
          semanticJson(bundleBeforeRestart),
          semanticJson(requireOwnedJson(second.port(), bundlePath)));
    } finally {
      forceStopAlive(second);
    }
  }

  private static Object semanticJson(String json) {
    return JsonPath.parse(json).json();
  }

  private static String requireOwnedJson(int port, String path) throws Exception {
    HttpResponse<String> response = sendJson(port, "GET", path, null);
    assertEquals(200, response.statusCode(), response::body);
    assertEquals(
        "private, no-store",
        response.headers().firstValue("Cache-Control").orElse(null));
    return response.body();
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-stage2-agent-run-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=agent-run-owner");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitHealthy(application);
      return application;
    } catch (Exception | AssertionError startupFailure) {
      stop(application);
      throw startupFailure;
    }
  }

  private static void awaitHealthy(RunningApplication application) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.process().isAlive()) {
        throw new AssertionError(
            "packaged application exited before becoming healthy:\n"
                + Files.readString(application.log()));
      }
      try {
        HttpResponse<String> response =
            sendJson(application.port(), "GET", "/actuator/health", null);
        if (response.statusCode() == 200) {
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

  private static HttpResponse<String> sendJson(
      int port, String method, String path, String body)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
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

  private static void forceStopAlive(RunningApplication application) throws Exception {
    assertTrue(
        application.process().isAlive(),
        () ->
            "packaged application exited before the forced stop:\n"
                + readLog(application.log()));
    stop(application);
    assertFalse(application.process().isAlive(), "packaged application survived forced stop");
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException readFailure) {
      return "unable to read application log: " + readFailure.getClass().getSimpleName();
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
