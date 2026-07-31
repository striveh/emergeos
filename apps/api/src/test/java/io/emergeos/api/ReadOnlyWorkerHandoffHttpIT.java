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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ReadOnlyWorkerHandoffHttpIT {

  private static final String PRINCIPAL = "pack007-http-owner";
  private static final String RAW_SENTINEL =
      "PACK007_RAW_SENTINEL: Prompt 工程是在为概率程序构造运行时状态。";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void persistsAndRevalidatesTheCompleteWorkerGraphAcrossTwoFreshJvms()
      throws Exception {
    RunningApplication creator = startApplication();
    long creatorPid = creator.process().pid();
    String parentRunPath;
    String parentTracePath;
    String parentBundlePath;
    String childRunPath;
    String childTracePath;
    String childBundlePath;
    String artifactPath;
    GraphSnapshot expected;
    try {
      HttpResponse<String> captured =
          sendJson(
              creator.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "pack007-http-capture-001",
                "content": "%s",
                "sourceType": "TEXT",
                "sourceRef": "synthetic-pack-007-http",
                "dataClass": "PUBLIC"
              }
              """
                  .formatted(RAW_SENTINEL));
      assertEquals(201, captured.statusCode(), captured::body);
      String captureId = JsonPath.read(captured.body(), "$.captureId");

      HttpResponse<String> drafted =
          sendJson(
              creator.port(),
              "POST",
              "/api/v1/agent-drafts",
              """
              {
                "captureId": "%s",
                "intent": "把这个想法整理成一篇有证据的中文短文"
              }
              """
                  .formatted(captureId));
      assertEquals(201, drafted.statusCode(), drafted::body);
      String parentRunId = JsonPath.read(drafted.body(), "$.runId");
      artifactPath =
          drafted.headers().firstValue("Location").orElseThrow();
      parentRunPath = "/api/v1/agent-runs/" + parentRunId;
      parentTracePath = parentRunPath + "/trace";
      parentBundlePath = parentRunPath + "/bundle";

      String parentRun = requireOwnedJson(creator.port(), parentRunPath);
      String parentTrace = requireOwnedJson(creator.port(), parentTracePath);
      String parentBundle = requireOwnedJson(creator.port(), parentBundlePath);
      List<String> handoffRefs =
          JsonPath.read(parentBundle, "$.handoffRefs");
      assertEquals(1, handoffRefs.size());
      String childRef = handoffRefs.getFirst();
      assertTrue(childRef.startsWith("agent-run://"));
      String childRunId = childRef.substring("agent-run://".length());
      childRunPath = "/api/v1/agent-runs/" + childRunId;
      childTracePath = childRunPath + "/trace";
      childBundlePath = childRunPath + "/bundle";

      String childRun = requireOwnedJson(creator.port(), childRunPath);
      String childTrace = requireOwnedJson(creator.port(), childTracePath);
      String childBundle = requireOwnedJson(creator.port(), childBundlePath);
      String artifact = requireJson(creator.port(), artifactPath);
      expected =
          new GraphSnapshot(
              parentRun,
              parentTrace,
              parentBundle,
              childRun,
              childTrace,
              childBundle,
              artifact);

      assertEquals("SUCCEEDED", JsonPath.read(parentRun, "$.state"));
      assertEquals("SUCCEEDED", JsonPath.read(childRun, "$.state"));
      assertEquals(
          List.of(
              "MODEL_STEP",
              "HANDOFF_REQUEST",
              "HANDOFF_RESULT",
              "MODEL_STEP",
              "STRUCTURED_FINAL",
              "ARTIFACT_COMMITTED"),
          JsonPath.read(parentTrace, "$.events[*].type"));
      assertEquals(
          List.of(
              "MODEL_STEP",
              "TOOL_REQUEST",
              "TOOL_RESULT",
              "MODEL_STEP",
              "STRUCTURED_FINAL"),
          JsonPath.read(childTrace, "$.events[*].type"));
      assertEquals(List.of(), JsonPath.read(childRun, "$.result.artifactRefs"));
      assertEquals(List.of(), JsonPath.read(childRun, "$.result.receiptRefs"));
      assertEquals(List.of(), JsonPath.read(childBundle, "$.handoffRefs"));
      assertEquals(List.of(), JsonPath.read(childBundle, "$.checkpointRefs"));
      assertNull(JsonPath.read(childBundle, "$.verificationRef"));

      String childBundleHash =
          JsonPath.read(childBundle, "$.integrityHash");
      assertEquals(
          List.of(childBundleHash),
          JsonPath.read(
              parentBundle,
              "$.resourceBindings[?(@.role == 'HANDOFF')].contentHash"));

      JdbcClient jdbc = jdbc();
      String workerResult =
          jdbc.sql(
                  """
                  SELECT worker_result_envelope::text
                  FROM agent_worker_results
                  WHERE principal_id = :principalId
                    AND child_run_id = :childRunId
                  """)
              .param("principalId", PRINCIPAL)
              .param("childRunId", childRunId)
              .query(String.class)
              .single();
      String workerIntegrityHash =
          JsonPath.read(workerResult, "$.integrityHash");
      String proposalContentHash =
          JsonPath.read(workerResult, "$.contentHash");
      assertEquals(
          List.of(workerIntegrityHash),
          JsonPath.read(
              childBundle,
              "$.resourceBindings[?(@.role == 'WORKER_RESULT')].contentHash"));
      assertEquals(
          List.of("proposal://sha256:" + proposalContentHash),
          JsonPath.read(
              childTrace,
              "$.events[?(@.type == 'STRUCTURED_FINAL')].reference"));
      assertEquals(
          List.of("proposal://sha256:" + proposalContentHash),
          JsonPath.read(
              parentTrace,
              "$.events[?(@.type == 'STRUCTURED_FINAL')].reference"));
      assertEquals(
          proposalContentHash,
          JsonPath.read(artifact, "$.versions[0].contentHash"));

      assertEquals(2L, count(jdbc, "agent_runs"));
      assertEquals(1L, count(jdbc, "agent_worker_results"));
      assertEquals(1L, count(jdbc, "artifacts"));
      assertEquals(0L, count(jdbc, "action_attempts"));
      assertEquals(0L, count(jdbc, "action_receipts"));
      assertEquals(
          0L,
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM agent_run_resource_bindings
                  WHERE principal_id = :principalId
                    AND run_id = :childRunId
                    AND role IN (
                      'ARTIFACT', 'RECEIPT', 'CHECKPOINT', 'HANDOFF'
                    )
                  """)
              .param("principalId", PRINCIPAL)
              .param("childRunId", childRunId)
              .query(Long.class)
              .single());
      assertFalse(
          (parentRun + parentTrace + parentBundle + childRun + childTrace + childBundle)
              .contains(RAW_SENTINEL));
    } finally {
      forceStopAlive(creator);
    }

    EmergeDatabaseSnapshot frozenDatabase =
        EmergeDatabaseSnapshot.capture(jdbc());

    RunningApplication firstReader = startApplication();
    assertNotEquals(creatorPid, firstReader.process().pid());
    long firstReaderPid = firstReader.process().pid();
    try {
      assertSnapshotEquals(
          expected,
          readSnapshot(
              firstReader.port(),
              parentRunPath,
              parentTracePath,
              parentBundlePath,
              childRunPath,
              childTracePath,
              childBundlePath,
              artifactPath));
    } finally {
      forceStopAlive(firstReader);
    }
    assertEquals(
        frozenDatabase,
        EmergeDatabaseSnapshot.capture(jdbc()),
        "the first fresh JVM startup and verified reads must not mutate "
            + "application truth or Flyway history");

    RunningApplication secondReader = startApplication();
    assertNotEquals(firstReaderPid, secondReader.process().pid());
    try {
      assertSnapshotEquals(
          expected,
          readSnapshot(
              secondReader.port(),
              parentRunPath,
              parentTracePath,
              parentBundlePath,
              childRunPath,
              childTracePath,
              childBundlePath,
              artifactPath));
    } finally {
      forceStopAlive(secondReader);
    }
    assertEquals(
        frozenDatabase,
        EmergeDatabaseSnapshot.capture(jdbc()),
        "the second fresh JVM startup and verified reads must not mutate "
            + "application truth or Flyway history");
  }

  private static GraphSnapshot readSnapshot(
      int port,
      String parentRunPath,
      String parentTracePath,
      String parentBundlePath,
      String childRunPath,
      String childTracePath,
      String childBundlePath,
      String artifactPath)
      throws Exception {
    return new GraphSnapshot(
        requireOwnedJson(port, parentRunPath),
        requireOwnedJson(port, parentTracePath),
        requireOwnedJson(port, parentBundlePath),
        requireOwnedJson(port, childRunPath),
        requireOwnedJson(port, childTracePath),
        requireOwnedJson(port, childBundlePath),
        requireJson(port, artifactPath));
  }

  private static void assertSnapshotEquals(
      GraphSnapshot expected, GraphSnapshot actual) {
    assertEquals(semanticJson(expected.parentRun()), semanticJson(actual.parentRun()));
    assertEquals(
        semanticJson(expected.parentTrace()),
        semanticJson(actual.parentTrace()));
    assertEquals(
        semanticJson(expected.parentBundle()),
        semanticJson(actual.parentBundle()));
    assertEquals(semanticJson(expected.childRun()), semanticJson(actual.childRun()));
    assertEquals(
        semanticJson(expected.childTrace()),
        semanticJson(actual.childTrace()));
    assertEquals(
        semanticJson(expected.childBundle()),
        semanticJson(actual.childBundle()));
    assertEquals(semanticJson(expected.artifact()), semanticJson(actual.artifact()));
  }

  private static Object semanticJson(String json) {
    return JsonPath.parse(json).json();
  }

  private static JdbcClient jdbc() {
    return JdbcClient.create(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()));
  }

  private static long count(JdbcClient jdbc, String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private static String requireOwnedJson(int port, String path)
      throws Exception {
    HttpResponse<String> response = sendJson(port, "GET", path, null);
    assertEquals(200, response.statusCode(), response::body);
    assertEquals(
        "private, no-store",
        response.headers().firstValue("Cache-Control").orElse(null));
    return response.body();
  }

  private static String requireJson(int port, String path) throws Exception {
    HttpResponse<String> response = sendJson(port, "GET", path, null);
    assertEquals(200, response.statusCode(), response::body);
    return response.body();
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-pack007-http-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=" + PRINCIPAL);
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitHealthy(application);
      return application;
    } catch (Exception | AssertionError startupFailure) {
      stop(application);
      throw startupFailure;
    }
  }

  private static void awaitHealthy(RunningApplication application)
      throws Exception {
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
          .method(
              method,
              HttpRequest.BodyPublishers.ofString(
                  body, StandardCharsets.UTF_8));
    }
    return HTTP.send(
        request.build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(
            0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void forceStopAlive(RunningApplication application)
      throws Exception {
    assertTrue(
        application.process().isAlive(),
        () ->
            "packaged application exited before the forced stop:\n"
                + readLog(application.log()));
    stop(application);
    assertFalse(
        application.process().isAlive(),
        "packaged application survived forced stop");
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

  private record RunningApplication(Process process, int port, Path log) {}

  private record GraphSnapshot(
      String parentRun,
      String parentTrace,
      String parentBundle,
      String childRun,
      String childTrace,
      String childBundle,
      String artifact) {

    private GraphSnapshot {
      assertNotNull(parentRun);
      assertNotNull(parentTrace);
      assertNotNull(parentBundle);
      assertNotNull(childRun);
      assertNotNull(childTrace);
      assertNotNull(childBundle);
      assertNotNull(artifact);
    }
  }
}
