package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresAttributedFailureResumeStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Packaged-process Acceptance harness for durable typed-failure recovery. */
public final class Pack010DurableAttributedFailureResumeHarnessMain {

  private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(30);

  private Pack010DurableAttributedFailureResumeHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (RuntimeException failure) {
      System.out.println(
          "PACK010_DURABLE_FAILURE_REJECTED"
              + " pid=" + ProcessHandle.current().pid()
              + " reason=" + rejectionReason(failure));
      System.out.flush();
      exitCode = 4;
    } catch (Exception failure) {
      System.out.println(
          "PACK010_DURABLE_FAILURE_REJECTED"
              + " pid=" + ProcessHandle.current().pid()
              + " reason=HARNESS_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static String rejectionReason(RuntimeException failure) {
    if (failure instanceof GraphAttemptConflictException) {
      return "FENCED";
    }
    if (failure instanceof GraphAttemptIntegrityException) {
      return "INTEGRITY";
    }
    if (failure instanceof IllegalArgumentException) {
      return "INPUT_INVALID";
    }
    if (failure instanceof IllegalStateException) {
      return "STATE_INVALID";
    }
    return "RUNTIME_FAILED";
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length < 1) {
      throw new IllegalArgumentException("ARGUMENTS_INVALID");
    }
    Mode mode = Mode.parse(args[0]);
    int expectedArguments = mode.completesTerminal() ? 10 : 9;
    if (args.length != expectedArguments) {
      throw new IllegalArgumentException("ARGUMENTS_INVALID");
    }
    String actor = Pack009ProcessSupport.requireBounded(args[1], "actor");
    if (!actor.matches("[a-z0-9-]{1,64}")) {
      throw new IllegalArgumentException("ACTOR_INVALID");
    }
    int repetition = Integer.parseInt(args[2]);
    Path repo = Pack009ProcessSupport.absoluteDirectory(args[3]);
    String jdbcUrl = Pack009ProcessSupport.requireLoopbackPostgres(args[4]);
    String resumerUsername =
        Pack009ProcessSupport.requireBounded(args[5], "username");
    String readerUsername =
        mode.completesTerminal()
            ? Pack009ProcessSupport.requireBounded(
                args[6], "readerUsername")
            : null;
    int pathOffset = mode.completesTerminal() ? 1 : 0;
    Path coordination =
        Pack009ProcessSupport.absoluteDirectory(args[6 + pathOffset]);
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[7 + pathOffset]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[8 + pathOffset]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010DurableAttributedFailureResumeHarnessMain.class,
        Pack010TerminalOutcomeTestBridge.class,
        Pack010GraphTerminalFixture.class,
        Pack010GraphTerminalStoreBridge.class,
        Pack010CommitBeforeDelegateHardKillDataSource.class);
    if (!repo.equals(Pack009ProcessSupport.absoluteDirectory(repo.toString()))
        || repetition < 1
        || repetition > Pack010GraphEvalCatalog.GENERATION_REPETITIONS) {
      throw new IllegalArgumentException("IDENTITY_INVALID");
    }

    DataInputStream secrets = new DataInputStream(System.in);
    String resumerPassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "resumerDatabasePassword");
    String readerPassword =
        mode.completesTerminal()
            ? Pack009ProcessSupport.readSecretFrame(
                secrets, "readerDatabasePassword")
            : null;
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "durable failure secret input");
    DataSource baseResumerDataSource =
        new DriverManagerDataSource(
            jdbcUrl, resumerUsername, resumerPassword);
    Pack010CommitBeforeDelegateHardKillDataSource commitCutpoint =
        mode.killsBeforeCommit()
            ? new Pack010CommitBeforeDelegateHardKillDataSource(
                baseResumerDataSource)
            : null;
    DataSource resumerDataSource =
        commitCutpoint == null ? baseResumerDataSource : commitCutpoint;
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(repetition);

    if (mode == Mode.MINT_AND_HALT) {
      PostgresGraphAttemptStore store =
          Pack010GraphTerminalStoreBridge.openWriter(resumerDataSource);
      GraphAttemptSnapshot snapshot =
          Pack010GraphTerminalFixture.verified(store, manifest);
      if (snapshot.cursor().lastSequence() != 13) {
        throw new IllegalStateException("SEQUENCE_DRIFT");
      }
      Pack010GraphTerminalStoreBridge.recordAttributedFailure(
          resumerDataSource,
          manifest,
          snapshot.cursor(),
          Pack010GraphTerminalFixture.catalogAttribution(repetition, 2),
          GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
          manifest.startedAt().plusMillis(13));
      MintReceipt durable =
          JdbcClient.create(resumerDataSource)
              .sql(
                  "SELECT cursor_sequence, provenance_hash, state, "
                      + "state_version "
                      + "FROM agent_graph_attributed_failure_outcomes "
                      + "WHERE principal_id = :principalId "
                      + "AND attempt_id = :attemptId")
              .param("principalId", manifest.principalId())
              .param("attemptId", manifest.attemptId())
              .query(
                  (row, ignored) ->
                      new MintReceipt(
                          row.getInt("cursor_sequence"),
                          row.getString("provenance_hash"),
                          row.getString("state"),
                          row.getLong("state_version")))
              .single();
      System.out.println(
          "PACK010_DURABLE_FAILURE_MINTED"
              + " actor=" + actor
              + " pid=" + ProcessHandle.current().pid()
              + " sequence=" + durable.cursorSequence()
              + " provenanceHash=" + durable.provenanceHash()
              + " state=" + durable.state()
              + " stateVersion=" + durable.stateVersion()
              + " effects=0");
      System.out.flush();
      Runtime.getRuntime().halt(71);
    }

    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource);
    var durable = resume.load(manifest);
    if (durable.cursorSequence() != 14) {
      throw new IllegalStateException("SEQUENCE_DRIFT");
    }

    if (mode.completesTerminal()) {
      DataSource readerDataSource =
          new DriverManagerDataSource(
              jdbcUrl, readerUsername, readerPassword);
      GraphAttemptVerification verification =
          PostgresGraphAttemptAccess.openReader(readerDataSource)
              .findVerified(manifest);
      if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
        throw new IllegalStateException("VERIFIED_SNAPSHOT_ABSENT");
      }
      GraphAttemptSnapshot before = valid.snapshot();
      if (mode.completesChild()) {
        var terminalChild =
            Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
                before,
                manifest,
                Pack010GraphEvalCatalog.workerProfile(repetition),
                manifest.startedAt(),
                "MODEL_RESPONSE_MALFORMED")
                .terminal();
        awaitCompletionRelease(coordination, actor);
        armCommitCutpoint(commitCutpoint, mode, coordination, actor);
        var completed =
            resume.completeClaimedFailureChild(
                manifest, durable, readerDataSource, terminalChild);
        requireCommitCutpointReached(mode);
        System.out.println(
            "PACK010_DURABLE_FAILURE_CHILD_COMMITTED"
                + " actor=" + actor
                + " pid=" + ProcessHandle.current().pid()
                + " sequence=" + completed.liveSequence()
                + " state=" + completed.state()
                + " stateVersion=" + completed.stateVersion()
                + " effects=0");
        System.out.flush();
        Runtime.getRuntime().halt(73);
      }

      var terminalParent =
          Pack010GraphTerminalFixture.preCandidateFailureParent(
                  before,
                  manifest,
                  Pack010GraphEvalCatalog.workerProfile(repetition),
                  Pack010GraphEvalCatalog.parentProfile(repetition),
                  manifest.startedAt())
              .terminal();
      awaitCompletionRelease(coordination, actor);
      armCommitCutpoint(commitCutpoint, mode, coordination, actor);
      var completed =
          resume.completeClaimedFailureParentAndSeal(
              manifest, durable, readerDataSource, terminalParent);
      requireCommitCutpointReached(mode);
      System.out.println(
          "PACK010_DURABLE_FAILURE_PARENT_COMMITTED"
              + " actor=" + actor
              + " pid=" + ProcessHandle.current().pid()
              + " sequence=" + completed.liveSequence()
              + " state=" + completed.state()
              + " stateVersion=" + completed.stateVersion()
              + " effects=0");
      System.out.flush();
      Runtime.getRuntime().halt(75);
    }

    int expectedSequence =
        mode == Mode.CLAIM_PARENT_AND_HALT
                || mode == Mode.CLAIM_PARENT_SHORT_AND_HALT
            ? 15
            : 14;
    if (durable.liveSequence() != expectedSequence) {
      throw new IllegalStateException("SEQUENCE_DRIFT");
    }

    Path ready = coordination.resolve(actor + ".ready");
    Path release = coordination.resolve("claim.release");
    Pack009ProcessSupport.writeDurableCreateNew(
        ready,
        "PACK010_DURABLE_FAILURE_CLAIM_READY"
            + " actor=" + actor
            + " pid=" + ProcessHandle.current().pid());
    Pack009ProcessSupport.awaitFile(release, RELEASE_TIMEOUT);
    var claimed =
        resume.claim(
            manifest,
            durable,
            "packaged-jvm-" + actor,
            IntegrityHashes.utf8ContentHash(
                "pack010-durable-failure-fence-v2|"
                    + manifest.attemptId()
                    + "|"
                    + mode.claimStage()
                    + "|"
                    + actor),
            mode == Mode.CLAIM_AND_HALT
                ? Duration.ofSeconds(3)
                : mode == Mode.CLAIM_TERMINAL_CHILD_SHORT_AND_HALT
                    ? Duration.ofMillis(250)
                    : mode == Mode.CLAIM_PARENT_SHORT_AND_HALT
                        ? Duration.ofMillis(250)
                        : mode.haltsAfterClaim()
                            ? Duration.ofSeconds(20)
                            : Duration.ofSeconds(5));
    System.out.println(
        "PACK010_DURABLE_FAILURE_CLAIMED"
            + " actor=" + actor
            + " pid=" + ProcessHandle.current().pid()
            + " sequence=" + claimed.liveSequence()
            + " state=" + claimed.state()
            + " stateVersion=" + claimed.stateVersion()
            + " effects=0");
    System.out.flush();
    if (mode.haltsAfterClaim()) {
      Runtime.getRuntime().halt(
          mode == Mode.CLAIM_PARENT_AND_HALT
                  || mode == Mode.CLAIM_PARENT_SHORT_AND_HALT
              ? 74
              : 72);
    }
    return 0;
  }

  private static void awaitCompletionRelease(
      Path coordination, String actor) throws Exception {
    Pack009ProcessSupport.writeDurableCreateNew(
        coordination.resolve(actor + ".ready"),
        "PACK010_DURABLE_FAILURE_COMPLETION_READY"
            + " actor=" + actor
            + " pid=" + ProcessHandle.current().pid());
    Pack009ProcessSupport.awaitFile(
        coordination.resolve("complete.release"), RELEASE_TIMEOUT);
  }

  private static void armCommitCutpoint(
      Pack010CommitBeforeDelegateHardKillDataSource commitCutpoint,
      Mode mode,
      Path coordination,
      String actor) {
    if (commitCutpoint != null) {
      commitCutpoint.arm(
          coordination.resolve(actor + ".pre-commit"),
          actor,
          mode.preCommitHaltCode());
    }
  }

  private static void requireCommitCutpointReached(Mode mode) {
    if (mode.killsBeforeCommit()) {
      throw new IllegalStateException("PRECOMMIT_HALT_MISSED");
    }
  }

  private enum Mode {
    MINT_AND_HALT,
    CLAIM,
    CLAIM_AND_HALT,
    CLAIM_TERMINAL_CHILD_AND_HALT,
    CLAIM_TERMINAL_CHILD_SHORT_AND_HALT,
    CLAIM_PARENT_AND_HALT,
    CLAIM_PARENT_SHORT_AND_HALT,
    RESUME_CHILD_AND_HALT,
    RESUME_PARENT_AND_HALT,
    RESUME_CHILD_KILL_BEFORE_COMMIT,
    RESUME_PARENT_KILL_BEFORE_COMMIT;

    private boolean completesTerminal() {
      return this == RESUME_CHILD_AND_HALT
          || this == RESUME_PARENT_AND_HALT
          || this == RESUME_CHILD_KILL_BEFORE_COMMIT
          || this == RESUME_PARENT_KILL_BEFORE_COMMIT;
    }

    private boolean completesChild() {
      return this == RESUME_CHILD_AND_HALT
          || this == RESUME_CHILD_KILL_BEFORE_COMMIT;
    }

    private boolean killsBeforeCommit() {
      return this == RESUME_CHILD_KILL_BEFORE_COMMIT
          || this == RESUME_PARENT_KILL_BEFORE_COMMIT;
    }

    private int preCommitHaltCode() {
      return this == RESUME_CHILD_KILL_BEFORE_COMMIT ? 76 : 77;
    }

    private boolean haltsAfterClaim() {
      return this == CLAIM_AND_HALT
          || this == CLAIM_TERMINAL_CHILD_AND_HALT
          || this == CLAIM_TERMINAL_CHILD_SHORT_AND_HALT
          || this == CLAIM_PARENT_AND_HALT
          || this == CLAIM_PARENT_SHORT_AND_HALT;
    }

    private String claimStage() {
      return this == CLAIM_PARENT_AND_HALT
              || this == CLAIM_PARENT_SHORT_AND_HALT
          ? "parent"
          : "child";
    }

    private static Mode parse(String value) {
      return switch (value) {
        case "mint-and-halt" -> MINT_AND_HALT;
        case "claim" -> CLAIM;
        case "claim-and-halt" -> CLAIM_AND_HALT;
        case "claim-terminal-child-and-halt" ->
            CLAIM_TERMINAL_CHILD_AND_HALT;
        case "claim-terminal-child-short-and-halt" ->
            CLAIM_TERMINAL_CHILD_SHORT_AND_HALT;
        case "claim-parent-and-halt" -> CLAIM_PARENT_AND_HALT;
        case "claim-parent-short-and-halt" ->
            CLAIM_PARENT_SHORT_AND_HALT;
        case "resume-child-and-halt" -> RESUME_CHILD_AND_HALT;
        case "resume-parent-and-halt" -> RESUME_PARENT_AND_HALT;
        case "resume-child-kill-before-commit" ->
            RESUME_CHILD_KILL_BEFORE_COMMIT;
        case "resume-parent-kill-before-commit" ->
            RESUME_PARENT_KILL_BEFORE_COMMIT;
        default -> throw new IllegalArgumentException("MODE_INVALID");
      };
    }
  }

  private record MintReceipt(
      int cursorSequence,
      String provenanceHash,
      String state,
      long stateVersion) {}
}
