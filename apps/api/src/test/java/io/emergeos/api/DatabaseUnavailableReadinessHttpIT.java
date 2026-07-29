package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class DatabaseUnavailableReadinessHttpIT {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

  @Test
  void databaseLossMakesReadinessDownWithoutStoppingOrLeakingTheProcess()
      throws Exception {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    int databasePort = POSTGRES.getMappedPort(5432);
    RunningApplication application = launchApplication();
    try {
      awaitLiveness(application);
      HttpResponse<String> ready = awaitReadiness(application, 200);
      assertEquals("UP", JsonPath.read(ready.body(), "$.status"));

      POSTGRES.stop();

      HttpResponse<String> unavailable = awaitReadiness(application, 503);
      assertEquals("DOWN", JsonPath.read(unavailable.body(), "$.status"));
      assertEquals(
          "DOWN",
          JsonPath.read(
              unavailable.body(), "$.components.stage1Durability.status"));
      assertEquals(
          "DATABASE_OR_MIGRATION_UNAVAILABLE",
          JsonPath.read(
              unavailable.body(),
              "$.components.stage1Durability.details.faultCode"));
      assertEquals(
          200,
          send(application.port(), "/actuator/health/liveness").statusCode(),
          "database loss must not be disguised as process death");
      assertTrue(application.process().isAlive());
      assertFalse(unavailable.body().contains(POSTGRES.getPassword()));
      assertFalse(unavailable.body().contains(jdbcUrl));
      assertFalse(unavailable.body().contains(Integer.toString(databasePort)));
      assertFalse(unavailable.body().contains("org.postgresql"));

      System.out.println(
          "S4_DATABASE_READINESS_RECEIPT database=unavailable readiness=DOWN "
              + "liveness=UP faultCode=DATABASE_OR_MIGRATION_UNAVAILABLE "
              + "connectionDetailsExposed=false synthetic=true");
    } finally {
      if (application.process().isAlive()) {
        application.process().destroyForcibly();
        application.process().waitFor(5, TimeUnit.SECONDS);
      }
      Files.deleteIfExists(application.log());
    }
  }

  private static RunningApplication launchApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-s4-database-readiness-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=s4-database-fault-owner");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());
    command.add("--spring.datasource.hikari.connection-timeout=250");
    command.add("--spring.datasource.hikari.validation-timeout=250");

    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    return new RunningApplication(process, port, log);
  }

  private static void awaitLiveness(RunningApplication application)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      assertStillRunning(application);
      try {
        if (send(application.port(), "/actuator/health/liveness").statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "packaged application did not become live:\n"
            + Files.readString(application.log()));
  }

  private static HttpResponse<String> awaitReadiness(
      RunningApplication application, int expectedStatus) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    HttpResponse<String> latest = null;
    while (System.nanoTime() < deadline) {
      assertStillRunning(application);
      try {
        latest = send(application.port(), "/actuator/health/readiness");
        if (latest.statusCode() == expectedStatus) {
          return latest;
        }
      } catch (IOException ignored) {
        // A database fault must eventually produce a bounded HTTP response.
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "readiness did not reach "
            + expectedStatus
            + "; latest="
            + (latest == null ? "none" : latest.body())
            + "\napplication log:\n"
            + Files.readString(application.log()));
  }

  private static void assertStillRunning(RunningApplication application)
      throws IOException {
    if (!application.process().isAlive()) {
      throw new AssertionError(
          "packaged application exited during database fault:\n"
              + Files.readString(application.log()));
    }
  }

  private static HttpResponse<String> send(int port, String path)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
    return HTTP.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
