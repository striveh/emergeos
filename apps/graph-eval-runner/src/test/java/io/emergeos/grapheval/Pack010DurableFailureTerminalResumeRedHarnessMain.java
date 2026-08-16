package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only packaged witness for the pre-V12 raw terminal bypass. */
public final class Pack010DurableFailureTerminalResumeRedHarnessMain {

  private Pack010DurableFailureTerminalResumeRedHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (RuntimeException failure) {
      System.out.println(
          "PACK010_DURABLE_FAILURE_RAW_TERMINAL_REJECTED reason="
              + failure.getClass().getSimpleName());
      System.out.flush();
      exitCode = 4;
    } catch (Exception failure) {
      System.out.println(
          "PACK010_DURABLE_FAILURE_RAW_TERMINAL_REJECTED reason=HARNESS_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length != 9) {
      throw new IllegalArgumentException("ARGUMENTS_INVALID");
    }
    Mode mode = Mode.parse(args[0]);
    Path payloadPath = Pack009ProcessSupport.absoluteRegularFile(args[1]);
    String jdbcUrl = Pack009ProcessSupport.requireLoopbackPostgres(args[2]);
    String username =
        Pack009ProcessSupport.requireBounded(args[3], "username");
    String principalId =
        Pack009ProcessSupport.requireBounded(args[4], "principalId");
    String attemptId =
        Pack009ProcessSupport.requireBounded(args[5], "attemptId");
    Path appJar = Pack009ProcessSupport.absoluteRegularFile(args[6]);
    Path testClasses = Pack009ProcessSupport.absoluteDirectory(args[7]);
    Path repo = Pack009ProcessSupport.absoluteDirectory(args[8]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010DurableFailureTerminalResumeRedHarnessMain.class,
        Pack010GraphTerminalStoreBridge.class,
        Pack010GraphTerminalFixture.class);
    if (!repo.equals(Pack009ProcessSupport.absoluteDirectory(repo.toString()))) {
      throw new IllegalArgumentException("REPO_INVALID");
    }

    DataInputStream secrets = new DataInputStream(System.in);
    String password =
        Pack009ProcessSupport.readSecretFrame(secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "terminal resume Red secret input");
    DataSource dataSource =
        new DriverManagerDataSource(jdbcUrl, username, password);
    String payload = Files.readString(payloadPath);
    String result;
    try {
      result =
          JdbcClient.create(dataSource)
              .sql(
                  "SELECT public."
                      + mode.functionName()
                      + "(CAST(:payload AS jsonb))::text")
              .param("payload", payload)
              .query(String.class)
              .single();
    } catch (RuntimeException failure) {
      SQLException sqlFailure = sqlFailure(failure);
      if (sqlFailure != null
          && "55000".equals(sqlFailure.getSQLState())
          && sqlFailure.getMessage().contains(mode.expectedReason())) {
        System.out.println(
            "PACK010_EXPECTED_RAW_V10_FAILURE_REJECTED"
                + " mode=" + mode.label()
                + " pid=" + ProcessHandle.current().pid()
                + " sqlState=55000"
                + " reason=" + mode.expectedReason().replace(' ', '_')
                + " effects=0");
        System.out.flush();
        return 73;
      }
      throw failure;
    }
    Integer sequence =
        JdbcClient.create(dataSource)
            .sql(
                "SELECT last_sequence FROM public.agent_graph_attempt_heads "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .query(Integer.class)
            .single();
    System.out.println(
        "PACK010_UNSAFE_V10_FAILURE_TERMINAL_COMMITTED"
            + " mode=" + mode.label()
            + " pid=" + ProcessHandle.current().pid()
            + " sequence=" + sequence
            + " result=" + result
            + " claimMetadata=ABSENT"
            + " effects=0");
    System.out.flush();
    return 0;
  }

  private static SQLException sqlFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      current = current.getCause();
    }
    return null;
  }

  private enum Mode {
    RAW_CHILD(
        "raw-child",
        "agent_graph_complete_child_v10",
        "V12 durable failure claim receipt drifted"),
    RAW_PARENT(
        "raw-parent",
        "agent_graph_complete_parent_and_seal_v10",
        "V12 terminal failure receipt is incomplete");

    private final String label;
    private final String functionName;
    private final String expectedReason;

    Mode(String label, String functionName, String expectedReason) {
      this.label = label;
      this.functionName = functionName;
      this.expectedReason = expectedReason;
    }

    private String label() {
      return label;
    }

    private String functionName() {
      return functionName;
    }

    private String expectedReason() {
      return expectedReason;
    }

    private static Mode parse(String value) {
      return switch (value) {
        case "raw-child" -> RAW_CHILD;
        case "raw-parent" -> RAW_PARENT;
        default -> throw new IllegalArgumentException("MODE_INVALID");
      };
    }
  }
}
