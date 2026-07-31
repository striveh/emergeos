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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ReadOnlyWorkerCrashGapHttpIT {

  private static final String PRINCIPAL = "pack007-crash-owner";
  private static final long PARENT_TERMINAL_LOCK = 7007L;

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void childCommitSurvivesARealProcessKillBeforeParentTerminalCommit()
      throws Exception {
    RunningApplication writer = startApplication();
    long writerPid = writer.process().pid();
    JdbcClient jdbc = jdbc();
    installParentTerminalBlocker(jdbc);
    String captureId;
    CompletableFuture<HttpResponse<String>> pendingDraft = null;
    String parentRunId;
    String childRunId;

    try (Connection lockConnection = dataSource().getConnection();
        Statement lock = lockConnection.createStatement()) {
      lock.execute("SELECT pg_advisory_lock(" + PARENT_TERMINAL_LOCK + ")");

      HttpResponse<String> captured =
          sendJson(
              writer.port(),
              "POST",
              "/api/v1/captures",
              """
              {
                "clientNonce": "pack007-crash-gap-001",
                "content": "Prompt 工程是在为概率程序构造运行时状态。",
                "sourceType": "TEXT",
                "sourceRef": "synthetic-pack-007-crash-gap",
                "dataClass": "PUBLIC"
              }
              """);
      assertEquals(201, captured.statusCode(), captured::body);
      captureId = JsonPath.read(captured.body(), "$.captureId");

      pendingDraft =
          sendJsonAsync(
              writer.port(),
              "POST",
              "/api/v1/agent-drafts",
              """
              {
                "captureId": "%s",
                "intent": "验证 child commit 与 parent commit 之间的进程终止边界"
              }
              """
                  .formatted(captureId));

      await(
          () -> waitingParentTerminalUpdate(jdbc),
          "parent terminal transaction did not reach the advisory blocker");
      assertFalse(
          pendingDraft.isDone(),
          "the parent request completed before the intended kill window");

      List<Map<String, Object>> runs =
          jdbc.sql(
                  """
                  SELECT run_id, parent_run_id, lifecycle_status
                  FROM agent_runs
                  WHERE principal_id = :principalId
                  ORDER BY run_depth
                  """)
              .param("principalId", PRINCIPAL)
              .query()
              .listOfRows();
      assertEquals(2, runs.size());
      parentRunId = (String) runs.getFirst().get("run_id");
      childRunId = (String) runs.getLast().get("run_id");
      assertNull(runs.getFirst().get("parent_run_id"));
      assertEquals(parentRunId, runs.getLast().get("parent_run_id"));
      assertEquals("RUNNING", runs.getFirst().get("lifecycle_status"));
      assertEquals("SUCCEEDED", runs.getLast().get("lifecycle_status"));
      assertEquals(1L, count(jdbc, "agent_worker_results"));
      assertEquals(0L, count(jdbc, "artifacts"));
      assertEquals(0L, parentTruthCount(jdbc, parentRunId));

      forceStopAlive(writer);
      lock.execute("SELECT pg_advisory_unlock(" + PARENT_TERMINAL_LOCK + ")");
    } finally {
      if (writer.process().isAlive()) {
        forceStopAlive(writer);
      }
      if (pendingDraft != null) {
        pendingDraft.cancel(true);
      }
    }

    assertEquals(0L, count(jdbc, "artifacts"));
    assertEquals(0L, parentTruthCount(jdbc, parentRunId));
    assertEquals(1L, count(jdbc, "agent_worker_results"));
    EmergeDatabaseSnapshot frozenDatabase =
        EmergeDatabaseSnapshot.capture(jdbc);

    RunningApplication firstReader = startApplication();
    assertNotEquals(writerPid, firstReader.process().pid());
    long firstReaderPid = firstReader.process().pid();
    try {
      verifyCrashGap(firstReader.port(), parentRunId, childRunId);
    } finally {
      forceStopAlive(firstReader);
    }
    assertEquals(
        frozenDatabase,
        EmergeDatabaseSnapshot.capture(jdbc),
        "the first fresh JVM startup and crash-gap reads must not mutate "
            + "application truth or Flyway history");

    RunningApplication secondReader = startApplication();
    assertNotEquals(firstReaderPid, secondReader.process().pid());
    try {
      verifyCrashGap(secondReader.port(), parentRunId, childRunId);
    } finally {
      forceStopAlive(secondReader);
    }
    assertEquals(
        frozenDatabase,
        EmergeDatabaseSnapshot.capture(jdbc),
        "the second fresh JVM startup and crash-gap reads must not mutate "
            + "application truth or Flyway history");

    System.out.println(
        "PACK007_CRASH_GAP_回执 "
            + "child=terminal+已验证WorkerResult parent=RUNNING "
            + "parentArtifact=0 parentTraceHandoff=0 freshJvmReads=2 "
            + "freshJvmProductTruthSnapshotUnchanged=2 processKill=真实 synthetic=true");
  }

  private static void verifyCrashGap(
      int port, String parentRunId, String childRunId) throws Exception {
    String parentPath = "/api/v1/agent-runs/" + parentRunId;
    String childPath = "/api/v1/agent-runs/" + childRunId;
    HttpResponse<String> parent =
        sendJson(port, "GET", parentPath, null);
    assertEquals(200, parent.statusCode(), parent::body);
    assertEquals("RUNNING", JsonPath.read(parent.body(), "$.state"));
    assertNull(JsonPath.read(parent.body(), "$.result"));

    HttpResponse<String> incompleteTrace =
        sendJson(port, "GET", parentPath + "/trace", null);
    assertEquals(409, incompleteTrace.statusCode(), incompleteTrace::body);
    assertEquals(
        "urn:emergeos:problem:agent-run-incomplete",
        JsonPath.read(incompleteTrace.body(), "$.type"));

    String child = requireOwnedJson(port, childPath);
    String childTrace = requireOwnedJson(port, childPath + "/trace");
    String childBundle = requireOwnedJson(port, childPath + "/bundle");
    assertEquals("SUCCEEDED", JsonPath.read(child, "$.state"));
    assertEquals(
        List.of(
            "MODEL_STEP",
            "TOOL_REQUEST",
            "TOOL_RESULT",
            "MODEL_STEP",
            "STRUCTURED_FINAL"),
        JsonPath.read(childTrace, "$.events[*].type"));
    assertEquals(List.of(), JsonPath.read(child, "$.result.artifactRefs"));
    assertEquals(List.of(), JsonPath.read(childBundle, "$.handoffRefs"));
    assertEquals(
        1,
        JsonPath.<List<String>>read(
                childBundle,
                "$.resourceBindings[?(@.role == 'WORKER_RESULT')].ref")
            .size());
  }

  private static void installParentTerminalBlocker(JdbcClient jdbc) {
    jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION pack007_block_parent_terminal()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
              IF OLD.parent_run_id IS NULL
                 AND OLD.lifecycle_status = 'RUNNING'
                 AND NEW.lifecycle_status <> 'RUNNING' THEN
                PERFORM pg_advisory_lock(7007);
                PERFORM pg_advisory_unlock(7007);
              END IF;
              RETURN NEW;
            END
            $$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER pack007_block_parent_terminal
            BEFORE UPDATE ON agent_runs
            FOR EACH ROW
            EXECUTE FUNCTION pack007_block_parent_terminal()
            """)
        .update();
  }

  private static boolean waitingParentTerminalUpdate(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND wait_event = 'advisory'
              AND query ILIKE '%UPDATE agent_runs%'
            """)
        .query(Long.class)
        .single() == 1L;
  }

  private static long parentTruthCount(JdbcClient jdbc, String parentRunId) {
    return jdbc.sql(
            """
            SELECT
              (SELECT count(*)
               FROM agent_trace_events
               WHERE principal_id = :principalId
                 AND run_id = :parentRunId)
              +
              (SELECT count(*)
               FROM agent_run_resource_bindings
               WHERE principal_id = :principalId
                 AND run_id = :parentRunId)
            """)
        .param("principalId", PRINCIPAL)
        .param("parentRunId", parentRunId)
        .query(Long.class)
        .single();
  }

  private static void await(BooleanSupplier condition, String failure)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError(failure);
  }

  private static JdbcClient jdbc() {
    return JdbcClient.create(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
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

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-pack007-crash-gap-", ".log");
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

  private static CompletableFuture<HttpResponse<String>> sendJsonAsync(
      int port, String method, String path, String body) {
    return HTTP.sendAsync(
        request(port, method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> sendJson(
      int port, String method, String path, String body)
      throws IOException, InterruptedException {
    return HTTP.send(
        request(port, method, path, body),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpRequest request(
      int port, String method, String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20));
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
    return request.build();
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
}
