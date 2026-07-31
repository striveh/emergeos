package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphAttemptConflictException;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Fresh test-process proof that a claimed execution slot cannot replay.
 *
 * <p>Only the database credential is available. Console, provider-key,
 * client, model and invocation counters must remain zero.
 */
public final class Pack009GraphReplayMain {

  private Pack009GraphReplayMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (RuntimeException failure) {
      System.out.println(
          "GRAPH_REPLAY_REJECTED reason=REPLAY_FAILED"
              + " failureType="
              + failure.getClass().getName());
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          "GRAPH_REPLAY_REJECTED reason=REPLAY_IO_FAILED");
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
        || !"replay".equals(args[0])) {
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
        appJar, testClasses, Pack009GraphReplayMain.class);
    new Pack009GraphPreflight(repoRoot).run();

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "replay secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    AtomicInteger ttyAvailabilityChecks = new AtomicInteger();
    AtomicInteger challengeReads = new AtomicInteger();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(
            new PostgresGraphAttemptStore(dataSource));
    try {
      coordinator.approve(
          Pack009GraphEvalCatalog.manifest(),
          new GraphAttemptCoordinator.InteractiveConsole() {
            @Override
            public boolean realTty() {
              ttyAvailabilityChecks.incrementAndGet();
              return true;
            }

            @Override
            public String readLine(String prompt) {
              challengeReads.incrementAndGet();
              throw new IllegalStateException(
                  "replay read operator input");
            }
          },
          Clock.fixed(
              Pack009GraphEvalCatalog.STARTED_AT,
              ZoneOffset.UTC));
      throw new IllegalStateException(
          "replay unexpectedly claimed the execution slot");
    } catch (GraphAttemptConflictException expected) {
      if (ttyAvailabilityChecks.get() != 1
          || challengeReads.get() != 0) {
        throw new IllegalStateException(
            "replay crossed a forbidden authority boundary");
      }
      System.out.println(
          "GRAPH_REPLAY_REJECTED"
              + " reason=EXECUTION_SLOT_ALREADY_CLAIMED"
              + " ttyAvailabilityChecks=1"
              + " challengeReads=0"
              + " providerEndpointArg=ABSENT"
              + " providerCredentialFrame=ABSENT");
      System.out.flush();
      return 10;
    }
  }

  private static int argumentsInvalid() {
    System.out.println(
        "GRAPH_REPLAY_REJECTED reason=ARGUMENTS_INVALID");
    System.out.flush();
    return 2;
  }
}
