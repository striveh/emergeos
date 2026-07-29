package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class IncompatibleMigrationFailsFastHttpIT {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).build();

  @Test
  void anIncompatibleAppliedMigrationPreventsThePackagedApplicationFromServing()
      throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();

    int corrupted =
        JdbcClient.create(dataSource)
            .sql(
                """
                UPDATE flyway_schema_history
                SET checksum = checksum + 1
                WHERE version = '3' AND success
                """)
            .update();
    assertEquals(1, corrupted, "the applied V3 checksum must be deliberately corrupted");

    RunningApplication application = launchApplication();
    boolean everReady = false;
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (application.process().isAlive() && System.nanoTime() < deadline) {
        everReady |= readinessIsUp(application.port());
        Thread.sleep(25);
      }

      assertTrue(
          application.process().waitFor(2, TimeUnit.SECONDS),
          "the packaged application must terminate after Flyway validation fails");
      assertNotEquals(0, application.process().exitValue());
      assertFalse(everReady, "an incompatible migration must never reach readiness");

      String log = Files.readString(application.log());
      assertTrue(
          log.contains("Migration checksum mismatch")
              && log.contains("migration version 3"),
          () -> "expected Flyway V3 checksum failure in packaged application log:\n" + log);
      assertFalse(
          log.contains(POSTGRES.getPassword()),
          "the packaged-process failure log must not disclose the database password");

      System.out.println(
          "S4_MIGRATION_FAILFAST_RECEIPT version=3 processExited=true "
              + "nonZero=true readinessReached=false credentialsLogged=false synthetic=true");
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
    Path log = Files.createTempFile("emerge-s4-incompatible-migration-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=s4-fail-fast-owner");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    return new RunningApplication(process, port, log);
  }

  private static boolean readinessIsUp(int port) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
              .timeout(Duration.ofMillis(500))
              .GET()
              .build();
      HttpResponse<String> response =
          HTTP.send(
              request,
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
    } catch (IOException ignored) {
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
