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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
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
class ConflictSafeRevisionHttpIT {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void oneConcurrentRevisionWinsAndItsLineageSurvivesAProcessRestart() throws Exception {
    List<RunningApplication> contenders = startApplicationPair("revision-owner");
    RunningApplication first = contenders.get(0);
    RunningApplication second = contenders.get(1);
    long firstPid = first.process().pid();
    long secondPid = second.process().pid();
    String artifactId;
    String baseHash;
    String beforeRestartBody;
    String winnerContent;
    String loserContent;
    Map<String, Object> versionOneBefore;

    assertNotEquals(firstPid, secondPid, "the race requires two independent application JVMs");
    try {
      HttpResponse<String> captured =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "s2-revision-source-001",
                "content": "synthetic captured thought for revision",
                "sourceType": "TEXT",
                "sourceRef": "s2-http-red",
                "dataClass": "PERSONAL"
              }
              """);
      assertEquals(201, captured.statusCode(), captured::body);
      String captureId = JsonPath.read(captured.body(), "$.captureId");

      HttpResponse<String> created =
          sendJson(
              first.port(),
              "POST",
              "/api/v1/artifacts",
              """
              {
                "captureId": "%s",
                "content": "artifact version one"
              }
              """
                  .formatted(captureId));
      assertEquals(
          201,
          created.statusCode(),
          () -> "Artifact create must be implemented after the Acceptance Red: " + created.body());
      artifactId = JsonPath.read(created.body(), "$.artifactId");
      assertEquals(1, number(created.body(), "$.currentVersion"));
      baseHash = JsonPath.read(created.body(), "$.currentHash");
      versionOneBefore = JsonPath.read(created.body(), "$.versions[0]");

      String revisionA =
          """
          {
            "content": "artifact version two from instance A",
            "expectedBaseVersion": 1,
            "expectedBaseHash": "%s"
          }
          """
              .formatted(baseHash);
      String revisionB =
          """
          {
            "content": "artifact version two from instance B",
            "expectedBaseVersion": 1,
            "expectedBaseHash": "%s"
          }
          """
              .formatted(baseHash);

      List<HttpResponse<String>> raced =
          race(
              () -> sendJson(first.port(), "PUT", "/api/v1/artifacts/" + artifactId, revisionA),
              () -> sendJson(second.port(), "PUT", "/api/v1/artifacts/" + artifactId, revisionB));
      raced.sort(java.util.Comparator.comparingInt(HttpResponse::statusCode));
      HttpResponse<String> winner = raced.get(0);
      HttpResponse<String> conflict = raced.get(1);
      assertEquals(200, winner.statusCode(), winner::body);
      assertEquals(409, conflict.statusCode(), conflict::body);
      assertEquals(
          "urn:emergeos:problem:artifact-revision-conflict",
          JsonPath.read(conflict.body(), "$.type"));
      assertEquals(2, number(conflict.body(), "$.currentVersion"));
      assertFalse(
          normalizedProblem(conflict.body()).containsKey("currentHash"),
          "a conflict must not expose a content-derived hash");
      assertFalse(
          conflict.body().contains("artifact version two"),
          "the conflict response must not disclose either revision body");
      winnerContent = JsonPath.read(winner.body(), "$.versions[1].content");
      assertTrue(
          winnerContent.equals("artifact version two from instance A")
              || winnerContent.equals("artifact version two from instance B"));
      loserContent =
          winnerContent.endsWith("A")
              ? "artifact version two from instance B"
              : "artifact version two from instance A";
      HttpResponse<String> beforeRestart =
          sendJson(first.port(), "GET", "/api/v1/artifacts/" + artifactId, null);
      assertEquals(200, beforeRestart.statusCode(), beforeRestart::body);
      assertEquals(winner.body(), beforeRestart.body());
      beforeRestartBody = beforeRestart.body();
      assertEquals(1, artifactRowCount(artifactId));
      assertEquals(2, versionRowCount(artifactId));
      assertEquals(2, databaseCurrentVersion(artifactId));
      assertEquals(1, linkedVersionTwoCount(artifactId, baseHash));
      assertEquals(0, contentRowCount(artifactId, loserContent));
    } finally {
      stopAll(first, second);
    }

    assertFalse(first.process().isAlive(), "the first packaged application must be terminated");
    assertFalse(second.process().isAlive(), "the second packaged application must be terminated");

    RunningApplication restarted = startApplication("revision-owner");
    try {
      assertNotEquals(firstPid, restarted.process().pid());
      assertNotEquals(secondPid, restarted.process().pid());
      HttpResponse<String> lineage =
          sendJson(restarted.port(), "GET", "/api/v1/artifacts/" + artifactId, null);
      assertEquals(200, lineage.statusCode(), lineage::body);
      assertEquals(beforeRestartBody, lineage.body());
      assertEquals(2, number(lineage.body(), "$.currentVersion"));
      assertEquals(2, number(lineage.body(), "$.versions.length()"));
      assertEquals(1, number(lineage.body(), "$.versions[0].version"));
      assertEquals("artifact version one", JsonPath.read(lineage.body(), "$.versions[0].content"));
      assertEquals(versionOneBefore, JsonPath.read(lineage.body(), "$.versions[0]"));
      assertEquals(2, number(lineage.body(), "$.versions[1].version"));
      assertEquals(winnerContent, JsonPath.read(lineage.body(), "$.versions[1].content"));
      assertFalse(lineage.body().contains(loserContent));
      assertEquals(1, number(lineage.body(), "$.versions[1].baseVersion"));
      assertEquals(baseHash, JsonPath.read(lineage.body(), "$.versions[1].baseHash"));
      String currentHash = JsonPath.read(lineage.body(), "$.currentHash");

      RunningApplication otherPrincipal = startApplication("other-revision-owner");
      try {
        HttpResponse<String> foreign =
            sendJson(otherPrincipal.port(), "GET", "/api/v1/artifacts/" + artifactId, null);
        HttpResponse<String> missing =
            sendJson(
                otherPrincipal.port(), "GET", "/api/v1/artifacts/missing-artifact", null);
        assertEquals(404, foreign.statusCode(), foreign::body);
        assertEquals(404, missing.statusCode(), missing::body);
        assertEquals(
            foreign.headers().firstValue("Content-Type"),
            missing.headers().firstValue("Content-Type"));
        assertEquals(normalizedProblem(foreign.body()), normalizedProblem(missing.body()));

        String staleRevision =
            """
            {
              "content": "foreign revision probe",
              "expectedBaseVersion": 2,
              "expectedBaseHash": "%s"
            }
            """
                .formatted(currentHash);
        HttpResponse<String> foreignRevision =
            sendJson(
                otherPrincipal.port(),
                "PUT",
                "/api/v1/artifacts/" + artifactId,
                staleRevision);
        HttpResponse<String> missingRevision =
            sendJson(
                otherPrincipal.port(),
                "PUT",
                "/api/v1/artifacts/missing-artifact",
                staleRevision);
        assertEquals(404, foreignRevision.statusCode(), foreignRevision::body);
        assertEquals(404, missingRevision.statusCode(), missingRevision::body);
        assertEquals(
            foreignRevision.headers().firstValue("Content-Type"),
            missingRevision.headers().firstValue("Content-Type"));
        assertEquals(
            normalizedProblem(foreignRevision.body()),
            normalizedProblem(missingRevision.body()));
        HttpResponse<String> unchanged =
            sendJson(restarted.port(), "GET", "/api/v1/artifacts/" + artifactId, null);
        assertEquals(200, unchanged.statusCode(), unchanged::body);
        assertEquals(2, number(unchanged.body(), "$.currentVersion"));
        assertEquals(2, number(unchanged.body(), "$.versions.length()"));

        System.out.printf(
            "S2_PROCESS_RECEIPT firstPid=%d secondPid=%d restartedPid=%d "
                + "otherPrincipalPid=%d winner=200 conflict=409 lineageVersions=2 "
                + "artifactRows=1 versionRows=2 currentVersion=2 baseLinked=true "
                + "foreignGet=404 missingGet=404 foreignPut=404 missingPut=404%n",
            firstPid,
            secondPid,
            restarted.process().pid(),
            otherPrincipal.process().pid());
      } finally {
        stop(otherPrincipal);
      }
    } finally {
      stop(restarted);
    }
  }

  private static List<HttpResponse<String>> race(
      ThrowingRequest firstRequest, ThrowingRequest secondRequest) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<HttpResponse<String>> first =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.await();
                return firstRequest.send();
              });
      Future<HttpResponse<String>> second =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.await();
                return secondRequest.send();
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS), "both requests must be ready before release");
      start.countDown();
      return new ArrayList<>(
          List.of(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS)));
    } finally {
      executor.shutdownNow();
    }
  }

  private static int number(String json, String path) {
    return ((Number) JsonPath.read(json, path)).intValue();
  }

  private static RunningApplication startApplication(String principalId) throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-s2-application-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + principalId);
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
      stopAfterFailure(application, startupFailure);
      throw startupFailure;
    }
  }

  private static List<RunningApplication> startApplicationPair(String principalId)
      throws Exception {
    RunningApplication first = startApplication(principalId);
    try {
      return List.of(first, startApplication(principalId));
    } catch (Exception | AssertionError startupFailure) {
      stopAfterFailure(first, startupFailure);
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

  private static int artifactRowCount(String artifactId) throws Exception {
    return queryCount(
        "SELECT count(*) FROM artifacts WHERE principal_id = ? AND artifact_id = ?",
        artifactId);
  }

  private static int versionRowCount(String artifactId) throws Exception {
    return queryCount(
        "SELECT count(*) FROM artifact_versions WHERE principal_id = ? AND artifact_id = ?",
        artifactId);
  }

  private static int databaseCurrentVersion(String artifactId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT current_version FROM artifacts "
                    + "WHERE principal_id = ? AND artifact_id = ?")) {
      statement.setString(1, "revision-owner");
      statement.setString(2, artifactId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static int linkedVersionTwoCount(String artifactId, String baseHash) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM artifact_versions "
                    + "WHERE principal_id = ? AND artifact_id = ? "
                    + "AND version = 2 AND base_version = 1 AND base_hash = ?")) {
      statement.setString(1, "revision-owner");
      statement.setString(2, artifactId);
      statement.setString(3, baseHash);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static int contentRowCount(String artifactId, String content) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM artifact_versions "
                    + "WHERE principal_id = ? AND artifact_id = ? AND content = ?")) {
      statement.setString(1, "revision-owner");
      statement.setString(2, artifactId);
      statement.setString(3, content);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
    }
  }

  private static int queryCount(String sql, String artifactId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, "revision-owner");
      statement.setString(2, artifactId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next());
        return resultSet.getInt(1);
      }
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

  private static void stopAfterFailure(RunningApplication application, Throwable originalFailure) {
    try {
      stop(application);
    } catch (Exception | AssertionError cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
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

  private static void rethrow(Throwable failure) throws Exception {
    if (failure instanceof Exception exception) {
      throw exception;
    }
    if (failure instanceof AssertionError assertionError) {
      throw assertionError;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(String json) {
    Map<String, Object> problem = JsonPath.parse(json).read("$");
    problem.remove("instance");
    return problem;
  }

  @FunctionalInterface
  private interface ThrowingRequest {
    HttpResponse<String> send() throws Exception;
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
