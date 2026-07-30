package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class AgentDraftHttpIT {

  private static final String SEED_SENTINEL =
      "SEED_SENTINEL: AI should turn thought into verified outcomes";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void turnsAnOwnedCaptureIntoAnInspectableAgentDraft() throws Exception {
    RunningApplication application = startApplication();
    long firstPid = application.process().pid();
    String captureId;
    String location;
    try {
      HttpResponse<String> captured =
          sendJson(
              application.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "agent-draft-capture-001",
                "content": "%s",
                "sourceType": "TEXT",
                "sourceRef": "stage2-agent-draft-red",
                "dataClass": "PERSONAL"
              }
              """
                  .formatted(SEED_SENTINEL));
      assertEquals(201, captured.statusCode(), captured::body);
      captureId = JsonPath.read(captured.body(), "$.captureId");

      HttpResponse<String> drafted =
          sendJson(
              application.port(),
              "POST",
              "/api/v1/agent-drafts",
              """
              {
                "captureId": "%s",
                "intent": "Create one evidence-linked Chinese article draft"
              }
              """
                  .formatted(captureId));

      assertEquals(
          201,
          drafted.statusCode(),
          () -> "agent draft endpoint must create one Artifact: " + drafted.body());
      location = drafted.headers().firstValue("Location").orElse(null);
      assertNotNull(location);
      assertTrue(location.startsWith("/api/v1/artifacts/"));
      String artifactId = location.substring(location.lastIndexOf('/') + 1);

      assertEquals("1.0", JsonPath.read(drafted.body(), "$.result.schemaVersion"));
      assertEquals("SUCCEEDED", JsonPath.read(drafted.body(), "$.result.status"));
      assertEquals(
          List.of("artifact://" + artifactId),
          JsonPath.read(drafted.body(), "$.result.artifactRefs"));
      assertEquals(
          List.of("capture://" + captureId),
          JsonPath.read(drafted.body(), "$.result.evidenceRefs"));
      assertEquals(
          "scripted-fake-draft-v1",
          JsonPath.read(drafted.body(), "$.result.resolvedModel"));
      assertEquals(0.0, JsonPath.<Number>read(drafted.body(), "$.result.costUsd").doubleValue());
      assertEquals(List.of(), JsonPath.read(drafted.body(), "$.result.receiptRefs"));
      assertNull(JsonPath.read(drafted.body(), "$.result.traceRef"));

      List<String> eventTypes = JsonPath.read(drafted.body(), "$.trace[*].type");
      assertEquals(
          List.of(
              "MODEL_STEP",
              "TOOL_REQUEST",
              "TOOL_RESULT",
              "MODEL_STEP",
              "STRUCTURED_FINAL",
              "ARTIFACT_COMMITTED"),
          eventTypes);
      assertEquals(
          List.of(1, 2, 3, 4, 5, 6),
          JsonPath.read(drafted.body(), "$.trace[*].sequence"));
      assertEquals(
          List.of("capture.read", "capture.read"),
          JsonPath.read(
              drafted.body(),
              "$.trace[?(@.type == 'TOOL_REQUEST' || @.type == 'TOOL_RESULT')].toolName"));
      assertFalse(
          JsonPath.parse(drafted.body()).read("$.trace").toString().contains(SEED_SENTINEL),
          "safe Trace must not contain raw Capture content");

    } finally {
      forceStopAlive(application);
    }

    RunningApplication restarted = startApplication();
    assertNotEquals(firstPid, restarted.process().pid());
    try {
      HttpResponse<String> persisted =
          sendJson(restarted.port(), "GET", location, null);
      assertEquals(200, persisted.statusCode(), persisted::body);
      assertEquals(captureId, JsonPath.read(persisted.body(), "$.captureId"));
      assertEquals(1, JsonPath.<Number>read(persisted.body(), "$.currentVersion").intValue());
      assertTrue(
          JsonPath.<String>read(persisted.body(), "$.versions[0].content")
              .contains(SEED_SENTINEL));
    } finally {
      forceStopAlive(restarted);
    }
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-stage2-agent-draft-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=agent-draft-owner");
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
