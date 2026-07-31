package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import java.io.DataInputStream;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Fresh test-process boundary for production Pack009 verification.
 *
 * <p>The verifier receives only a bounded database-password frame. It never
 * migrates, repairs, resumes or writes graph state.
 */
public final class Pack009GraphVerifierMain {

  private Pack009GraphVerifierMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (Pack009GraphVerifier.Rejected rejected) {
      System.out.println(
          "GRAPH_VERIFY_REJECTED reason=" + rejected.code());
      System.out.flush();
      exitCode = rejected.exitCode();
    } catch (RuntimeException failure) {
      System.out.println(
          "GRAPH_VERIFY_REJECTED reason=VERIFIER_FAILED"
              + " failureType="
              + failure.getClass().getName());
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          "GRAPH_VERIFY_REJECTED reason=VERIFIER_IO_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null
        || args.length != 6
        || !"verify".equals(args[0])) {
      return argumentsInvalid();
    }
    Path repoRoot =
        Pack009ProcessSupport.absoluteDirectory(args[1]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[2]);
    String username =
        Pack009ProcessSupport.requireBounded(args[3], "username");
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[4]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[5]);
    Pack009ProcessSupport.assertCodeSources(
        appJar, testClasses, Pack009GraphVerifierMain.class);
    new Pack009GraphPreflight(repoRoot).run();

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "verifier secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    Pack009GraphVerifier.Result result =
        new Pack009GraphVerifier(
                new PostgresGraphAttemptStore(dataSource))
            .verify();
    System.out.println(result.receipt());
    System.out.flush();
    return 0;
  }

  private static int argumentsInvalid() {
    System.out.println(
        "GRAPH_VERIFY_REJECTED reason=ARGUMENTS_INVALID");
    System.out.flush();
    return 2;
  }
}
