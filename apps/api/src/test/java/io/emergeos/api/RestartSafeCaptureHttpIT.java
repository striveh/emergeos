package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RestartSafeCaptureHttpIT {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void retrievesTheOriginalCaptureAfterThePackagedApplicationProcessRestarts() throws Exception {
    RunningApplication first = startApplication("restart-owner");
    long firstPid = first.process().pid();
    String createdBody;
    String captureId;

    try {
      HttpResponse<String> created =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "restart-safe-capture-001",
                "content": "synthetic restart-safe thought",
                "sourceType": "TEXT",
                "sourceRef": "s1-http-red",
                "dataClass": "PERSONAL"
              }
              """);
      assertEquals(
          201,
          created.statusCode(),
          () -> "capture POST must exist before restart: " + created.body());
      createdBody = created.body();
      captureId = JsonPath.read(createdBody, "$.captureId");

      HttpResponse<String> beforeRestart =
          sendJson(first.port(), "GET", "/api/v1/captures/" + captureId, null);
      assertEquals(200, beforeRestart.statusCode(), beforeRestart::body);
      assertEquals(createdBody, beforeRestart.body());
    } finally {
      stop(first);
    }

    assertFalse(first.process().isAlive(), "the first packaged application must be terminated");

    RunningApplication second = startApplication("restart-owner");
    try {
      assertNotEquals(firstPid, second.process().pid(), "restart evidence requires a different JVM");
      HttpResponse<String> afterRestart =
          sendJson(second.port(), "GET", "/api/v1/captures/" + captureId, null);
      assertEquals(200, afterRestart.statusCode(), afterRestart::body);
      assertEquals(createdBody, afterRestart.body());
      HttpResponse<String> replayAfterRestart =
          sendJson(
              second.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "restart-safe-capture-001",
                "content": "synthetic restart-safe thought",
                "sourceType": "TEXT",
                "sourceRef": "s1-http-red",
                "dataClass": "PERSONAL"
              }
              """);
      assertEquals(200, replayAfterRestart.statusCode(), replayAfterRestart::body);
      assertEquals(createdBody, replayAfterRestart.body());
      HttpResponse<String> conflictAfterRestart =
          sendJson(
              second.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "restart-safe-capture-001",
                "content": "synthetic changed thought",
                "sourceType": "TEXT",
                "sourceRef": "s1-http-red",
                "dataClass": "PERSONAL"
              }
              """);
      assertEquals(409, conflictAfterRestart.statusCode(), conflictAfterRestart::body);

      RunningApplication otherPrincipal = startApplication("other-owner");
      try {
        HttpResponse<String> foreign =
            sendJson(otherPrincipal.port(), "GET", "/api/v1/captures/" + captureId, null);
        HttpResponse<String> missing =
            sendJson(
                otherPrincipal.port(), "GET", "/api/v1/captures/missing-capture", null);
        assertEquals(404, foreign.statusCode(), foreign::body);
        assertEquals(404, missing.statusCode(), missing::body);
        assertEquals(
            foreign.headers().firstValue("Content-Type"),
            missing.headers().firstValue("Content-Type"));
        assertEquals(normalizedProblem(foreign.body()), normalizedProblem(missing.body()));

        HttpResponse<String> independentlyOwned =
            sendJson(
                otherPrincipal.port(),
                "POST",
                "/api/v1/captures",
                """
                {
                  "clientNonce": "restart-safe-capture-001",
                  "content": "synthetic restart-safe thought",
                  "sourceType": "TEXT",
                  "sourceRef": "s1-http-red",
                  "dataClass": "PERSONAL"
                }
                """);
        assertEquals(201, independentlyOwned.statusCode(), independentlyOwned::body);
        assertEquals(
            "other-owner", JsonPath.read(independentlyOwned.body(), "$.principalId"));
        assertNotEquals(captureId, JsonPath.read(independentlyOwned.body(), "$.captureId"));

        System.out.printf(
            "S1_PROCESS_RECEIPT firstPid=%d restartedPid=%d otherPrincipalPid=%d "
                + "restart=200 replay=200 conflict=409 foreign=404 missing=404%n",
            firstPid, second.process().pid(), otherPrincipal.process().pid());
      } finally {
        stop(otherPrincipal);
      }
    } finally {
      stop(second);
    }
  }

  @Test
  void rejectsWildcardBindingBeforeThePackagedApplicationCanStart() throws Exception {
    RunningApplication application = launchApplication("restart-owner", "0.0.0.0");
    try {
      assertTrue(
          application.process().waitFor(10, TimeUnit.SECONDS),
          "wildcard-bound packaged application must fail fast");
      assertNotEquals(0, application.process().exitValue());
      String log = Files.readString(application.log());
      assertTrue(
          log.contains(
              "unauthenticated prototype requires loopback-only server.address: 0.0.0.0"),
          () -> "expected loopback safety failure in log:\n" + log);
    } finally {
      stop(application);
    }
  }

  private static RunningApplication startApplication(String principalId) throws Exception {
    RunningApplication application = launchApplication(principalId, "127.0.0.1");
    try {
      awaitHealthy(application);
      return application;
    } catch (Exception | AssertionError startupFailure) {
      stop(application);
      throw startupFailure;
    }
  }

  private static RunningApplication launchApplication(String principalId, String serverAddress)
      throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-s1-application-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=" + serverAddress);
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + principalId);
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    return new RunningApplication(process, port, log);
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
        "packaged application did not become healthy:\n" + Files.readString(application.log()));
  }

  private static HttpResponse<String> sendJson(
      int port, String method, String path, String body) throws IOException, InterruptedException {
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
    try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(String json) {
    Map<String, Object> problem = JsonPath.parse(json).read("$");
    problem.remove("instance");
    return problem;
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
