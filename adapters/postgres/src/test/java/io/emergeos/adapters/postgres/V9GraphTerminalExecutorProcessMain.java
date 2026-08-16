package io.emergeos.adapters.postgres;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Forked-JVM V9 writer used only for local process-kill evidence. */
public final class V9GraphTerminalExecutorProcessMain {

  private static final int MAX_SECRET_BYTES = 4096;
  private static final int MAX_PAYLOAD_BYTES = 1_048_576;
  private static final Duration HOLD = Duration.ofSeconds(90);
  private static final List<String> SHADOW_RELATIONS =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_graph_attempt_seals",
          "agent_runs",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "agent_worker_results",
          "artifacts",
          "artifact_versions");

  private V9GraphTerminalExecutorProcessMain() {}

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception failure) {
      System.out.println("V9_TERMINAL_PROCESS_REJECTED");
      System.out.flush();
      System.exit(3);
    }
  }

  private static void run(String[] args) throws Exception {
    if (args == null || args.length != 5) {
      throw new IllegalArgumentException();
    }
    String operation = args[0];
    if (!operation.equals("CHILD") && !operation.equals("PARENT")) {
      throw new IllegalArgumentException();
    }
    String jdbcUrl = args[1];
    if (!(jdbcUrl.startsWith("jdbc:postgresql://localhost:")
        || jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:"))) {
      throw new IllegalArgumentException();
    }
    String username = requireRole(args[2]);
    Path payloadPath = Path.of(args[3]).toAbsolutePath().normalize();
    Coordination coordination = Coordination.parse(args[4]);
    byte[] payloadBytes = Files.readAllBytes(payloadPath);
    if (payloadBytes.length == 0
        || payloadBytes.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException();
    }
    String payload =
        new String(payloadBytes, StandardCharsets.UTF_8);
    String password = readSecretFrame();

    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(jdbcUrl);
    source.setUser(username);
    source.setPassword(password);
    try (Connection connection = source.getConnection()) {
      requireTempShadowsDenied(connection);
      DataSource oneConnection =
          new SingleConnectionDataSource(connection, true);
      PostgresGraphTerminalExecutor.Probe probe =
          coordination.faultMarker() == null
              ? ignored -> {}
              : point -> {
                if (point
                    == PostgresGraphTerminalExecutor.ProbePoint
                        .AFTER_SEMANTIC_FUNCTION) {
                  holdAfterSemanticFunction(
                      coordination.faultMarker(), operation);
                }
              };
      PostgresGraphTerminalExecutor executor =
          new PostgresGraphTerminalExecutor(oneConnection, probe);
      coordination.awaitRaceRelease();
      if (operation.equals("CHILD")) {
        PostgresGraphTerminalExecutorTestAccess.completeChild(executor, payload);
      } else {
        PostgresGraphTerminalExecutorTestAccess.completeParentAndSeal(executor, payload);
      }
    }
    System.out.println(
        "V9_TERMINAL_PROCESS_OK operation=" + operation);
    System.out.flush();
  }

  private static void requireTempShadowsDenied(Connection connection)
      throws Exception {
    try (var statement = connection.createStatement()) {
      for (String relation : SHADOW_RELATIONS) {
        try {
          statement.execute(
              "CREATE TEMP TABLE " + relation + " (forged text)");
          throw new IllegalStateException(
              "restricted executor created a temp shadow");
        } catch (SQLException denied) {
          if (!"42501".equals(denied.getSQLState())) {
            throw denied;
          }
        }
      }
    }
  }

  private static void holdAfterSemanticFunction(
      Path markerPath, String operation) {
    try {
      Files.writeString(
          markerPath,
          "V9_SEMANTIC_FUNCTION_REACHED operation="
              + operation
              + " pid="
              + ProcessHandle.current().pid(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      new CountDownLatch(1).await(
          HOLD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      throw new IllegalStateException();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String readSecretFrame() throws Exception {
    DataInputStream input = new DataInputStream(System.in);
    int length = input.readInt();
    if (length < 1 || length > MAX_SECRET_BYTES) {
      throw new IllegalArgumentException();
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length || input.read() != -1) {
      throw new IllegalArgumentException();
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String requireRole(String role) {
    if (role == null || !role.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException();
    }
    return role;
  }

  private record Coordination(
      Path faultMarker, Path readyMarker, Path releaseMarker) {

    private static Coordination parse(String value) {
      if (value.equals("NONE")) {
        return new Coordination(null, null, null);
      }
      if (value.startsWith("RACE|")) {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 3) {
          throw new IllegalArgumentException();
        }
        return new Coordination(
            null,
            Path.of(fields[1]).toAbsolutePath().normalize(),
            Path.of(fields[2]).toAbsolutePath().normalize());
      }
      return new Coordination(
          Path.of(value).toAbsolutePath().normalize(), null, null);
    }

    private void awaitRaceRelease() throws Exception {
      if (readyMarker == null) {
        return;
      }
      Files.writeString(
          readyMarker,
          "V9_RACE_READY pid=" + ProcessHandle.current().pid(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      long deadline =
          System.nanoTime()
              + Duration.ofSeconds(30).toNanos();
      while (!Files.exists(releaseMarker)
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      if (!Files.exists(releaseMarker)) {
        throw new IllegalStateException();
      }
    }
  }
}
