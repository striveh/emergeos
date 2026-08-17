package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptManifest;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only process shell for packaged successor-claim evidence. */
public final class Pack010SuccessorClaimHarnessMain {

  private static final Duration PROBE_HOLD_LEASE =
      Duration.ofSeconds(90);

  private Pack010SuccessorClaimHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (IllegalArgumentException invalid) {
      System.out.println(
          "PACK010_SUCCESSOR_REJECTED reason=ARGUMENTS_INVALID");
      System.out.flush();
      exitCode = 2;
    } catch (RuntimeException failure) {
      System.out.println(
          "PACK010_SUCCESSOR_REJECTED reason=HARNESS_FAILED");
      printFailureTypes(failure);
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          "PACK010_SUCCESSOR_REJECTED reason=HARNESS_IO_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static void printFailureTypes(Throwable failure) {
    Throwable current = failure;
    int depth = 0;
    while (current != null && depth < 8) {
      System.err.println(
          "PACK010_SUCCESSOR_FAILURE_TYPE depth="
              + depth
              + " type="
              + current.getClass().getName());
      current = current.getCause();
      depth++;
    }
    System.err.flush();
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length != 9) {
      throw new IllegalArgumentException();
    }
    Mode mode = Mode.parse(args[0]);
    String caseKey = requireCaseKey(args[1]);
    String actor = requireActor(args[2], mode);
    Pack009ProcessSupport.absoluteDirectory(args[3]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[4]);
    String username =
        Pack009ProcessSupport.requireBounded(
            args[5], "username");
    Path coordination =
        Pack009ProcessSupport.absoluteDirectory(args[6]);
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[7]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[8]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010SuccessorClaimHarnessMain.class,
        Pack010GraphTerminalStoreBridge.class);

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "successor writer secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    List<GraphAttemptManifest> manifests =
        Pack010GraphEvalCatalog.manifests();
    GraphAttemptManifest predecessor = manifests.get(0);
    GraphAttemptManifest current = manifests.get(1);
    return switch (mode) {
      case SEED -> seed(dataSource);
      case CRASH ->
          crash(
              caseKey,
              coordination,
              dataSource,
              current,
              predecessor);
      case RACE ->
          race(
              caseKey,
              actor,
              coordination,
              dataSource,
              current,
              predecessor);
    };
  }

  private static int seed(DataSource dataSource) {
    int sequence =
        Pack010GraphTerminalFixture
            .completeCatalogRepetition(
                dataSource,
                Pack010GraphTerminalStoreBridge
                    .openWriter(dataSource),
                1)
            .cursor()
            .lastSequence();
    if (sequence != 17) {
      throw new IllegalStateException(
          "predecessor did not reach sequence 17");
    }
    System.out.println(
        "PACK010_SUCCESSOR_PREDECESSOR_READY"
            + " revision=R1 sequence=17");
    System.out.flush();
    return 0;
  }

  private static int crash(
      String caseKey,
      Path coordination,
      DataSource dataSource,
      GraphAttemptManifest current,
      GraphAttemptManifest predecessor) {
    Pack010GraphTerminalStoreBridge.claimSuccessor(
        dataSource,
        current,
        predecessor,
        "AFTER_HEAD_UPDATE",
        boundary ->
            blockAtProbe(
                coordination,
                caseKey,
                boundary,
                current));
    throw new IllegalStateException(
        "successor crash probe unexpectedly resumed");
  }

  private static int race(
      String caseKey,
      String actor,
      Path coordination,
      DataSource dataSource,
      GraphAttemptManifest current,
      GraphAttemptManifest predecessor)
      throws Exception {
    String ready =
        "PACK010_SUCCESSOR_READY"
            + " revision=R2"
            + " actor="
            + actor
            + " pid="
            + ProcessHandle.current().pid()
            + " attemptId="
            + current.attemptId()
            + " predecessorAttemptId="
            + predecessor.attemptId();
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(caseKey + "-" + actor + ".ready"),
        ready);
    Pack009ProcessSupport.awaitFile(
        coordination.resolve(caseKey + ".go"),
        Duration.ofSeconds(30));
    String result;
    int exitCode;
    try {
      int sequence =
          Pack010GraphTerminalStoreBridge.claimSuccessor(
              dataSource,
              current,
              predecessor,
              "NONE",
              ignored -> {});
      result =
          "PACK010_SUCCESSOR_RESULT"
              + " revision=R2"
              + " actor="
              + actor
              + " pid="
              + ProcessHandle.current().pid()
              + " outcome=CLAIMED"
              + " sequence="
              + sequence;
      exitCode = 0;
    } catch (GraphAttemptConflictException conflict) {
      result =
          "PACK010_SUCCESSOR_RESULT"
              + " revision=R2"
              + " actor="
              + actor
              + " pid="
              + ProcessHandle.current().pid()
              + " outcome=CONFLICT";
      exitCode = 10;
    }
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(caseKey + "-" + actor + ".result"),
        result);
    System.out.println(result);
    System.out.flush();
    return exitCode;
  }

  private static void blockAtProbe(
      Path coordination,
      String caseKey,
      String boundary,
      GraphAttemptManifest current) {
    try {
      String marker =
          "PACK010_SUCCESSOR_PROBE_REACHED"
              + " revision=R2"
              + " boundary="
              + boundary
              + " pid="
              + ProcessHandle.current().pid()
              + " attemptId="
              + current.attemptId();
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(caseKey + ".probe"), marker);
      System.out.println(
          "PACK010_SUCCESSOR_PROBE_ACK"
              + " revision=R2"
              + " boundary="
              + boundary
              + " pid="
              + ProcessHandle.current().pid());
      System.out.flush();
      boolean unexpectedlyReleased =
          new CountDownLatch(1)
              .await(
                  PROBE_HOLD_LEASE.toMillis(),
                  TimeUnit.MILLISECONDS);
      throw new IllegalStateException(
          unexpectedlyReleased
              ? "successor probe unexpectedly resumed"
              : "successor probe hold lease expired");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String requireCaseKey(String value) {
    if (value == null
        || !value.matches("[a-z0-9][a-z0-9-]{0,79}")) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  private static String requireActor(String value, Mode mode) {
    if (((mode == Mode.SEED || mode == Mode.CRASH)
            && !"X".equals(value))
        || (mode == Mode.RACE && !value.matches("A|B"))) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  private enum Mode {
    SEED,
    CRASH,
    RACE;

    private static Mode parse(String value) {
      return switch (value) {
        case "seed" -> SEED;
        case "crash" -> CRASH;
        case "race" -> RACE;
        default -> throw new IllegalArgumentException();
      };
    }
  }
}
