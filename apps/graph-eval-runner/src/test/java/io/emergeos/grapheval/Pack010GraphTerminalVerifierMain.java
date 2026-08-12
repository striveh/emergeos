package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import java.io.DataInputStream;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Fresh read-only JVM boundary for Pack010 terminal truth. */
public final class Pack010GraphTerminalVerifierMain {

  private Pack010GraphTerminalVerifierMain() {}

  public static void main(String[] args) {
    int exitCode;
    String checkpoint =
        args != null && args.length > 1 ? args[1] : "UNKNOWN";
    try {
      exitCode = run(args);
    } catch (Pack010GraphTerminalVerifier.Rejected rejected) {
      System.out.println(
          rejection(checkpoint, rejected.code()));
      System.out.flush();
      exitCode = rejected.exitCode();
    } catch (IllegalArgumentException invalid) {
      System.out.println(
          rejection(checkpoint, "ARGUMENTS_INVALID"));
      System.out.flush();
      exitCode = 2;
    } catch (RuntimeException failure) {
      System.out.println(
          rejection(checkpoint, "VERIFIER_FAILED"));
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          rejection(checkpoint, "VERIFIER_IO_FAILED"));
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null
        || args.length != 7
        || !"verify".equals(args[0])) {
      throw new IllegalArgumentException();
    }
    Pack010GraphTerminalVerifier.Checkpoint checkpoint =
        Pack010GraphTerminalVerifier.Checkpoint.valueOf(args[1]);
    Path repoRoot =
        Pack009ProcessSupport.absoluteDirectory(args[2]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[3]);
    String username =
        Pack009ProcessSupport.requireBounded(args[4], "username");
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[5]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[6]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010GraphTerminalVerifierMain.class,
        Pack010GraphTerminalFixture.class);
    if (!repoRoot.equals(
        Pack009ProcessSupport.absoluteDirectory(
            repoRoot.toString()))) {
      throw new IllegalArgumentException();
    }

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "verifier secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    Pack010GraphTerminalVerifier.Result result =
        new Pack010GraphTerminalVerifier(
                PostgresGraphAttemptAccess.openReader(dataSource))
            .verify(
                Pack010GraphTerminalFixture.manifest(),
                checkpoint);
    System.out.println(result.receipt());
    System.out.flush();
    return 0;
  }

  private static String rejection(
      String checkpoint, String reason) {
    String safeCheckpoint =
        checkpoint != null
                && checkpoint.matches("SEQ13|SEQ14|SEQ15|SEQ17")
            ? checkpoint
            : "UNKNOWN";
    return "PACK010_GRAPH_VERIFY_REJECTED"
        + " version=1 checkpoint="
        + safeCheckpoint
        + " reason="
        + reason;
  }
}
