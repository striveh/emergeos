package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.port.AgentRunContext;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only writer JVM for Pack010 terminal crash and race evidence. */
public final class Pack010GraphTerminalHarnessMain {

  private static final Duration PROBE_HOLD_LEASE =
      Duration.ofSeconds(90);

  private Pack010GraphTerminalHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (IllegalArgumentException invalid) {
      System.out.println(
          "PACK010_TERMINAL_REJECTED reason=ARGUMENTS_INVALID");
      System.out.flush();
      exitCode = 2;
    } catch (RuntimeException failure) {
      System.out.println(
          "PACK010_TERMINAL_REJECTED reason=HARNESS_FAILED");
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          "PACK010_TERMINAL_REJECTED reason=HARNESS_IO_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length != 9) {
      throw new IllegalArgumentException();
    }
    Mode mode = Mode.parse(args[0]);
    String caseKey = requireCaseKey(args[1]);
    String actorOrProbe =
        Pack009ProcessSupport.requireBounded(
            args[2], "actorOrProbe");
    Path repoRoot =
        Pack009ProcessSupport.absoluteDirectory(args[3]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[4]);
    String username =
        Pack009ProcessSupport.requireBounded(args[5], "username");
    Path coordination =
        Pack009ProcessSupport.absoluteDirectory(args[6]);
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[7]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[8]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010GraphTerminalHarnessMain.class,
        Pack010GraphTerminalFixture.class,
        Pack010GraphTerminalStoreBridge.class);
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
        secrets, "terminal writer secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    PostgresGraphAttemptStore normal =
        Pack010GraphTerminalStoreBridge.openWriter(dataSource);

    return switch (mode.kind) {
      case PREPARE ->
          prepare(mode.tx, actorOrProbe, dataSource, normal);
      case WRITE ->
          write(
              mode.tx,
              caseKey,
              actorOrProbe,
              coordination,
              dataSource,
              normal);
      case RACE ->
          race(
              mode.tx,
              caseKey,
              actorOrProbe,
              coordination,
              normal);
    };
  }

  private static int prepare(
      String tx,
      String actorOrProbe,
      DataSource dataSource,
      PostgresGraphAttemptStore store) {
    if (!"NONE".equals(actorOrProbe)) {
      throw new IllegalArgumentException();
    }
    GraphAttemptSnapshot snapshot =
        switch (tx) {
          case "TX_A" ->
              Pack010GraphTerminalFixture.prepareTxA(
                  dataSource, store);
          case "TX_B" ->
              Pack010GraphTerminalFixture.prepareTxB(
                  dataSource, store);
          case "TX_C" ->
              Pack010GraphTerminalFixture.prepareTxC(
                  dataSource, store);
          default -> throw new IllegalArgumentException();
        };
    System.out.println(
        "PACK010_PREPARED tx="
            + tx
            + " sequence="
            + snapshot.cursor().lastSequence()
            + " headHash="
            + snapshot.cursor().headHash());
    System.out.flush();
    return 0;
  }

  private static int write(
      String tx,
      String caseKey,
      String probe,
      Path coordination,
      DataSource dataSource,
      PostgresGraphAttemptStore normal) {
    GraphAttemptSnapshot before =
        Pack010GraphTerminalFixture.verified(normal);
    Pack010GraphTerminalFixture.requireCheckpoint(
        before, beforeCheckpoint(tx));
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.store(
            dataSource,
            tx,
            probe,
            reached ->
                blockAtProbe(
                    coordination,
                    caseKey,
                    tx,
                    reached,
                    before));
    advance(tx, writer, before);
    GraphAttemptSnapshot after =
        Pack010GraphTerminalFixture.verified(normal);
    Pack010GraphTerminalFixture.requireCheckpoint(
        after, afterCheckpoint(tx));
    System.out.println(
        "PACK010_WRITE_RESULT tx="
            + tx
            + " outcome=ADVANCED sequence="
            + after.cursor().lastSequence()
            + " headHash="
            + after.cursor().headHash());
    System.out.flush();
    return 0;
  }

  private static int race(
      String tx,
      String caseKey,
      String actor,
      Path coordination,
      PostgresGraphAttemptStore store)
      throws Exception {
    if (!actor.matches("A|B|C")) {
      throw new IllegalArgumentException();
    }
    GraphAttemptSnapshot before =
        Pack010GraphTerminalFixture.verified(store);
    if ("C".equals(actor)) {
      return staleRace(
          tx, caseKey, coordination, store, before);
    }
    Pack010GraphTerminalFixture.requireCheckpoint(
        before, beforeCheckpoint(tx));
    String ready =
        "PACK010_RACE_READY"
            + " tx="
            + tx
            + " actor="
            + actor
            + " pid="
            + ProcessHandle.current().pid()
            + " attemptId="
            + before.manifest().attemptId()
            + " expectedSequence="
            + before.cursor().lastSequence()
            + " expectedHeadHash="
            + before.cursor().headHash()
            + " completionFingerprint="
            + Pack010GraphTerminalFixture
                .completionFingerprint(before, tx);
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(caseKey + "-" + actor + ".ready"),
        ready);
    Pack009ProcessSupport.awaitFile(
        coordination.resolve(caseKey + ".go"),
        Duration.ofSeconds(30));
    String result;
    int exitCode;
    try {
      advance(tx, store, before);
      GraphAttemptSnapshot after =
          Pack010GraphTerminalFixture.verified(store);
      Pack010GraphTerminalFixture.requireCheckpoint(
          after, afterCheckpoint(tx));
      result =
          "PACK010_RACE_RESULT tx="
              + tx
              + " actor="
              + actor
              + " pid="
              + ProcessHandle.current().pid()
              + " outcome=ADVANCED sequence="
              + after.cursor().lastSequence();
      exitCode = 0;
    } catch (GraphAttemptConflictException conflict) {
      GraphAttemptSnapshot winner =
          Pack010GraphTerminalFixture.verified(store);
      Pack010GraphTerminalFixture.requireCheckpoint(
          winner, afterCheckpoint(tx));
      result =
          "PACK010_RACE_RESULT tx="
              + tx
              + " actor="
              + actor
              + " pid="
              + ProcessHandle.current().pid()
              + " outcome=CONFLICT expectedSequence="
              + before.cursor().lastSequence();
      exitCode = 10;
    }
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(caseKey + "-" + actor + ".result"),
        result);
    System.out.println(result);
    System.out.flush();
    return exitCode;
  }

  private static int staleRace(
      String tx,
      String caseKey,
      Path coordination,
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot winner)
      throws Exception {
    Pack010GraphTerminalFixture.requireCheckpoint(
        winner, afterCheckpoint(tx));
    String originalReady =
        Pack009ProcessSupport.readBounded(
            coordination.resolve(caseKey + "-A.ready"));
    GraphAttemptCursor stale =
        new GraphAttemptCursor(
            winner.manifest().principalId(),
            field(originalReady, "attemptId"),
            winner.manifest().manifestHash(),
            Long.parseLong(
                field(originalReady, "expectedSequence")),
            Integer.parseInt(
                field(originalReady, "expectedSequence")),
            field(originalReady, "expectedHeadHash"),
            beforePhase(tx));
    String ready =
        "PACK010_RACE_READY"
            + " tx="
            + tx
            + " actor=C"
            + " pid="
            + ProcessHandle.current().pid()
            + " attemptId="
            + stale.attemptId()
            + " expectedSequence="
            + stale.lastSequence()
            + " expectedHeadHash="
            + stale.headHash()
            + " completionFingerprint="
            + field(originalReady, "completionFingerprint");
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(caseKey + "-C.ready"), ready);
    Pack009ProcessSupport.awaitFile(
        coordination.resolve(caseKey + ".go"),
        Duration.ofSeconds(30));
    try {
      if ("TX_A".equals(tx)) {
        Pack010GraphTerminalFixture.requireCheckpoint(
            winner,
            Pack010GraphTerminalFixture.Checkpoint.SEQ14);
        store.providerAttributed(
            winner.manifest(),
            stale,
            winner.providerAttributions().get(1),
            Pack010GraphTerminalFixture.at(13));
      } else if ("TX_B".equals(tx)) {
        Pack010GraphTerminalFixture.requireCheckpoint(
            winner,
            Pack010GraphTerminalFixture.Checkpoint.SEQ15);
        store.completeChild(
            winner.manifest(),
            stale,
            AgentRunContext.fromRunning(winner.parentRun()),
            winner.childRun(),
            winner.candidate(),
            winner.workerResult(),
            Pack010GraphTerminalFixture.at(14));
      } else {
        Pack010GraphTerminalFixture.requireCheckpoint(
            winner,
            Pack010GraphTerminalFixture.Checkpoint.SEQ17);
        store.completeParentAndSeal(
            winner.manifest(),
            stale,
            winner.parentRun(),
            winner.artifact(),
            Pack010GraphTerminalFixture.at(15));
      }
      throw new IllegalStateException(
          "stale terminal cursor unexpectedly advanced");
    } catch (GraphAttemptConflictException expected) {
      GraphAttemptSnapshot unchanged =
          Pack010GraphTerminalFixture.verified(store);
      Pack010GraphTerminalFixture.requireCheckpoint(
          unchanged, afterCheckpoint(tx));
      String result =
          "PACK010_RACE_RESULT tx="
              + tx
              + " actor=C"
              + " pid="
              + ProcessHandle.current().pid()
              + " outcome=CONFLICT expectedSequence="
              + stale.lastSequence();
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(caseKey + "-C.result"), result);
      System.out.println(result);
      System.out.flush();
      return 10;
    }
  }

  private static void advance(
      String tx,
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot before) {
    switch (tx) {
      case "TX_A" ->
          Pack010GraphTerminalFixture.completeTxA(store, before);
      case "TX_B" ->
          Pack010GraphTerminalFixture.completeTxB(store, before);
      case "TX_C" ->
          Pack010GraphTerminalFixture.completeTxC(store, before);
      default -> throw new IllegalArgumentException();
    }
  }

  private static void blockAtProbe(
      Path coordination,
      String caseKey,
      String tx,
      String probe,
      GraphAttemptSnapshot before) {
    String marker =
        "PACK010_PROBE_REACHED"
            + " tx="
            + tx
            + " probe="
            + probe
            + " pid="
            + ProcessHandle.current().pid()
            + " attemptId="
            + before.manifest().attemptId()
            + " expectedSequence="
            + before.cursor().lastSequence()
            + " expectedHeadHash="
            + before.cursor().headHash();
    try {
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(caseKey + ".probe"), marker);
      System.out.println(
          "PACK010_PROBE_ACK"
              + " tx="
              + tx
              + " probe="
              + probe
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
              ? "Pack010 probe unexpectedly resumed"
              : "Pack010 probe hold lease expired");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Pack010GraphTerminalFixture.Checkpoint
      beforeCheckpoint(String tx) {
    return switch (tx) {
      case "TX_A" -> Pack010GraphTerminalFixture.Checkpoint.SEQ13;
      case "TX_B" -> Pack010GraphTerminalFixture.Checkpoint.SEQ14;
      case "TX_C" -> Pack010GraphTerminalFixture.Checkpoint.SEQ15;
      default -> throw new IllegalArgumentException();
    };
  }

  private static Pack010GraphTerminalFixture.Checkpoint
      afterCheckpoint(String tx) {
    return switch (tx) {
      case "TX_A" -> Pack010GraphTerminalFixture.Checkpoint.SEQ14;
      case "TX_B" -> Pack010GraphTerminalFixture.Checkpoint.SEQ15;
      case "TX_C" -> Pack010GraphTerminalFixture.Checkpoint.SEQ17;
      default -> throw new IllegalArgumentException();
    };
  }

  private static GraphAttemptPhase beforePhase(String tx) {
    return switch (tx) {
      case "TX_A" -> GraphAttemptPhase.PROVIDER_PENDING;
      case "TX_B" -> GraphAttemptPhase.PROVIDER_ATTRIBUTED;
      case "TX_C" -> GraphAttemptPhase.CHILD_TERMINAL;
      default -> throw new IllegalArgumentException();
    };
  }

  private static String field(String receipt, String name) {
    String prefix = name + "=";
    for (String token : receipt.strip().split(" ")) {
      if (token.startsWith(prefix)) {
        return token.substring(prefix.length());
      }
    }
    throw new IllegalArgumentException(
        "Pack010 race receipt field is absent");
  }

  private static String requireCaseKey(String value) {
    if (value == null
        || !value.matches("[a-z0-9][a-z0-9-]{0,79}")) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  private enum Kind {
    PREPARE,
    WRITE,
    RACE
  }

  private enum Mode {
    PREPARE_TX_A(Kind.PREPARE, "TX_A"),
    PREPARE_TX_B(Kind.PREPARE, "TX_B"),
    PREPARE_TX_C(Kind.PREPARE, "TX_C"),
    WRITE_TX_A(Kind.WRITE, "TX_A"),
    WRITE_TX_B(Kind.WRITE, "TX_B"),
    WRITE_TX_C(Kind.WRITE, "TX_C"),
    RACE_TX_A(Kind.RACE, "TX_A"),
    RACE_TX_B(Kind.RACE, "TX_B"),
    RACE_TX_C(Kind.RACE, "TX_C");

    private final Kind kind;
    private final String tx;

    Mode(Kind kind, String tx) {
      this.kind = kind;
      this.tx = tx;
    }

    private static Mode parse(String value) {
      return switch (value) {
        case "prepare-tx-a" -> PREPARE_TX_A;
        case "prepare-tx-b" -> PREPARE_TX_B;
        case "prepare-tx-c" -> PREPARE_TX_C;
        case "write-tx-a" -> WRITE_TX_A;
        case "write-tx-b" -> WRITE_TX_B;
        case "write-tx-c" -> WRITE_TX_C;
        case "race-tx-a" -> RACE_TX_A;
        case "race-tx-b" -> RACE_TX_B;
        case "race-tx-c" -> RACE_TX_C;
        default -> throw new IllegalArgumentException();
      };
    }
  }
}
