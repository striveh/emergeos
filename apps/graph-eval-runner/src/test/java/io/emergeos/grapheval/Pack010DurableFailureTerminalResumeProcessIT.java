package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresAttributedFailureResumeStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.AgentRunLifecycle;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010DurableFailureTerminalResumeProcessIT {

  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final String CLAIM_HARNESS =
      "io.emergeos.grapheval."
          + "Pack010DurableAttributedFailureResumeHarnessMain";
  private static final String RED_HARNESS =
      "io.emergeos.grapheval."
          + "Pack010DurableFailureTerminalResumeRedHarnessMain";
  private static final String WRITER_PASSWORD = UUID.randomUUID().toString();
  private static final String EXECUTOR_PASSWORD = UUID.randomUUID().toString();
  private static final String RESUMER_PASSWORD = UUID.randomUUID().toString();
  private static final String READER_PASSWORD = UUID.randomUUID().toString();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_eval")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString())
          .withCreateContainerCmdModifier(
              command -> {
                command.getHostConfig().withAutoRemove(false);
                command.withEntrypoint("sh", "-c");
                command.withCmd(
                    "/usr/local/bin/docker-entrypoint.sh postgres & "
                        + "while :; do sleep 1; done");
              });

  @TempDir Path tempDir;

  @BeforeAll
  static void prepareFailurePrefix() {
    DataSource admin = adminDataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles.sql"));
    setEphemeralPassword(admin, "emergeos_graph_prefix_writer", WRITER_PASSWORD);
    setEphemeralPassword(admin, "emergeos_graph_executor", EXECUTOR_PASSWORD);
    setEphemeralPassword(admin, "emergeos_failure_resumer", RESUMER_PASSWORD);
    setEphemeralPassword(admin, "emergeos_graph_reader", READER_PASSWORD);
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles_check.sql"));

    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource());
    for (int repetition = 1;
        repetition <= Pack010GraphEvalCatalog.GENERATION_REPETITIONS;
        repetition++) {
      GraphAttemptManifest manifest =
          Pack010GraphEvalCatalog.manifest(repetition);
      GraphAttemptSnapshot sequence7 =
          Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
              admin, writer, repetition);
      Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
          writerDataSource(),
          manifest,
          OwnerTtyGraphAuthority.Pack010Revision.R1,
          Pack010GraphTerminalFixture.catalogIntent(repetition, 1),
          Instant.now()
              .truncatedTo(ChronoUnit.MICROS)
              .plus(Duration.ofMinutes(5)));
      GraphAttemptSnapshot sequence13 =
          Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
              writer, sequence7, repetition);
      Pack010GraphTerminalStoreBridge.recordAttributedFailure(
          writerDataSource(),
          manifest,
          sequence13.cursor(),
          Pack010GraphTerminalFixture.catalogAttribution(repetition, 2),
          GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
          manifest.startedAt().plusMillis(13));
    }
  }

  @Test
  void packagedResumeOwnsAtomicFailureTxBAndTxCAcrossFreshJvms()
      throws Exception {
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(1);
    String claimantOutput =
        runClaimAndHalt(
            "claim-terminal-child-short-and-halt",
            "child-expiring-claimant",
            tempDir.resolve("child-expiring-claim"),
            72);
    assertTrue(claimantOutput.contains("stateVersion=2"));
    assertFalse(claimantOutput.contains(RESUMER_PASSWORD));

    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    var claimed = resume.load(manifest);
    assertEquals(PostgresAttributedFailureResumeStore.State.CLAIMED, claimed.state());
    assertEquals(2L, claimed.stateVersion());
    assertEquals(14, claimed.liveSequence());

    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource());
    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    var child =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
            sequence14,
            manifest,
            Pack010GraphTerminalFixture.workerProfile(),
            manifest.startedAt(),
            "MODEL_RESPONSE_MALFORMED");
    awaitClaimExpired(manifest, false);
    assertChildCompletionFencedWithoutMutation(
        resume, manifest, claimed, child.terminal());

    String childReclaimOutput =
        runClaimAndHalt(
            "claim-terminal-child-and-halt",
            "child-reclaimer",
            tempDir.resolve("child-reclaim"),
            72);
    assertTrue(
        childReclaimOutput.contains("state=CLAIMED"),
        childReclaimOutput);
    assertTrue(
        childReclaimOutput.contains("stateVersion=3"),
        childReclaimOutput);
    claimed = resume.load(manifest);
    assertEquals(PostgresAttributedFailureResumeStore.State.CLAIMED, claimed.state());
    assertEquals(3L, claimed.stateVersion());

    assertChildCompletionClaimMatrixRejected(
        resume, manifest, claimed, child.terminal());
    Path payload = tempDir.resolve("failure-child-v10.json");
    Files.writeString(
        payload,
        Pack010GraphTerminalStoreBridge.preCandidateFailureChildPayload(
            sequence14, child.terminal()));
    assertChildWrongModelSqlFencedWithoutMutation(
        manifest, claimed, Files.readString(payload));

    Map<String, String> beforeRawChild = databaseSnapshot();
    String bypassOutput = runRawV10("raw-child", payload, manifest);
    assertTrue(
        bypassOutput.contains("sqlState=55000"), bypassOutput);
    assertTrue(
        bypassOutput.contains(
            "reason=V12_durable_failure_claim_receipt_drifted"),
        bypassOutput);
    assertEquals(beforeRawChild, databaseSnapshot());
    assertEquals(14, liveSequence(manifest));
    assertEquals(3L, resumeVersion(manifest));

    String childCompletionOutput =
        runCompletionAndHalt(
            "resume-child-and-halt",
            "child-completer",
            tempDir.resolve("child-completion"),
            73);
    assertTrue(
        childCompletionOutput.contains("state=CHILD_CONSUMED"),
        childCompletionOutput);
    assertTrue(
        childCompletionOutput.contains("stateVersion=4"),
        childCompletionOutput);
    assertFalse(childCompletionOutput.contains(RESUMER_PASSWORD));
    assertFalse(childCompletionOutput.contains(READER_PASSWORD));

    var childConsumed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CHILD_CONSUMED,
        childConsumed.state());
    assertEquals(4L, childConsumed.stateVersion());
    assertEquals(3L, childConsumed.childClaimVersion());
    assertEquals(15, childConsumed.liveSequence());

    String expiringParentClaimOutput =
        runClaimAndHalt(
            "claim-parent-short-and-halt",
            "parent-expiring-claimant",
            tempDir.resolve("parent-expiring-claim"),
            74);
    assertTrue(
        expiringParentClaimOutput.contains("state=PARENT_CLAIMED"),
        expiringParentClaimOutput);
    assertTrue(
        expiringParentClaimOutput.contains("stateVersion=5"),
        expiringParentClaimOutput);

    var expiringParentClaim = resume.load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.PARENT_CLAIMED,
        expiringParentClaim.state());
    assertEquals(5L, expiringParentClaim.stateVersion());
    assertEquals(15, expiringParentClaim.liveSequence());

    GraphAttemptSnapshot sequence15 =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    var parent =
        Pack010GraphTerminalFixture.preCandidateFailureParent(
            sequence15,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(1),
            Pack010GraphEvalCatalog.parentProfile(1),
            manifest.startedAt());
    awaitClaimExpired(manifest, true);
    assertParentCompletionFencedWithoutMutation(
        resume,
        manifest,
        expiringParentClaim,
        parent.terminal());

    String parentReclaimOutput =
        runClaimAndHalt(
            "claim-parent-and-halt",
            "parent-reclaimer",
            tempDir.resolve("parent-reclaim"),
            74);
    assertTrue(
        parentReclaimOutput.contains("state=PARENT_CLAIMED"),
        parentReclaimOutput);
    assertTrue(
        parentReclaimOutput.contains("stateVersion=6"),
        parentReclaimOutput);
    var parentClaimed = resume.load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.PARENT_CLAIMED,
        parentClaimed.state());
    assertEquals(6L, parentClaimed.stateVersion());
    assertEquals(15, parentClaimed.liveSequence());
    assertParentCompletionClaimMatrixRejected(
        resume, manifest, parentClaimed, parent.terminal());

    Path parentPayload = tempDir.resolve("failure-parent-v10.json");
    Files.writeString(
        parentPayload,
        Pack010GraphTerminalStoreBridge.preCandidateFailureParentPayload(
            sequence15, parent.terminal()));
    Map<String, String> beforeRawParent = databaseSnapshot();
    String rawParentOutput =
        runRawV10("raw-parent", parentPayload, manifest);
    assertTrue(
        rawParentOutput.contains("sqlState=55000"), rawParentOutput);
    assertTrue(
        rawParentOutput.contains(
            "reason=V12_terminal_failure_receipt_is_incomplete"),
        rawParentOutput);
    assertEquals(beforeRawParent, databaseSnapshot());
    assertEquals(15, liveSequence(manifest));
    assertEquals(6L, resumeVersion(manifest));

    String parentCompletionOutput =
        runCompletionAndHalt(
            "resume-parent-and-halt",
            "parent-completer",
            tempDir.resolve("parent-completion"),
            75);
    assertTrue(
        parentCompletionOutput.contains("state=TERMINAL_CONSUMED"),
        parentCompletionOutput);
    assertTrue(
        parentCompletionOutput.contains("stateVersion=7"),
        parentCompletionOutput);

    var terminalConsumed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.TERMINAL_CONSUMED,
        terminalConsumed.state());
    assertEquals(7L, terminalConsumed.stateVersion());
    assertEquals(17, terminalConsumed.liveSequence());
    GraphAttemptSnapshot sequence17 =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(17, sequence17.cursor().lastSequence());
    assertEquals(GraphAttemptOutcome.FAILED, sequence17.outcome());
    assertEquals(GraphBillingStatus.ATTRIBUTED, sequence17.billingStatus());
    assertEquals(AgentRunLifecycle.FAILED, sequence17.childRun().lifecycle());
    assertEquals(AgentRunLifecycle.FAILED, sequence17.parentRun().lifecycle());
    assertNull(sequence17.candidate());
    assertNull(sequence17.workerResult());
    assertNull(sequence17.artifact());
    assertEquals(2, sequence17.terminalBindings().size());
    assertTrue(sequence17.terminalSeal() != null);
    assertEquals(16, terminalConsumed.parentSequence());
    assertEquals(17, terminalConsumed.terminalSequence());
    assertEquals(
        sequence17.cursor().headHash(), terminalConsumed.terminalHeadHash());
    assertEquals(
        sequence17.terminalSeal().childTerminalHash(),
        terminalConsumed.childTerminalHash());
    assertEquals(
        sequence17.terminalSeal().parentTerminalHash(),
        terminalConsumed.parentTerminalHash());
    assertEquals(
        sequence17.terminalSeal().sealHash(), terminalConsumed.sealHash());

    System.out.println(
        "PACK010_V12_DURABLE_FAILURE_TERMINAL_RECEIPT"
            + " packagedFreshJvmClaims=4"
            + " packagedFreshJvmCompletions=2"
            + " rawV10Child=FENCED"
            + " rawV10Parent=FENCED"
            + " fullDatabaseXminRollback=true"
            + " head=14->15->17"
            + " resumeVersion=1->2(expired)->3->4->5(expired)->6->7"
            + " completionNegativeMatrix=12"
            + " terminalState=TERMINAL_CONSUMED"
            + " providerCalls=0 effects=0");
  }

  @Test
  void twoFreshJvmsRaceEachTerminalStageExactlyOnce() throws Exception {
    int repetition = 2;
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    runClaimAndHalt(
        "claim-terminal-child-and-halt",
        "race-child-claimant",
        repetition,
        tempDir.resolve("race-child-claim"),
        72);

    RaceOutputs childRace =
        runCompletionRace(
            "resume-child-and-halt",
            repetition,
            tempDir.resolve("child-completion-race"),
            73);
    assertTrue(
        childRace.combined().contains("state=CHILD_CONSUMED"),
        childRace.combined());
    var childConsumed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CHILD_CONSUMED,
        childConsumed.state());
    assertEquals(3L, childConsumed.stateVersion());
    assertEquals(15, childConsumed.liveSequence());
    assertEquals(15L, eventCount(manifest));

    runClaimAndHalt(
        "claim-parent-and-halt",
        "race-parent-claimant",
        repetition,
        tempDir.resolve("race-parent-claim"),
        74);
    RaceOutputs parentRace =
        runCompletionRace(
            "resume-parent-and-halt",
            repetition,
            tempDir.resolve("parent-completion-race"),
            75);
    assertTrue(
        parentRace.combined().contains("state=TERMINAL_CONSUMED"),
        parentRace.combined());
    var terminal =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.TERMINAL_CONSUMED,
        terminal.state());
    assertEquals(5L, terminal.stateVersion());
    assertEquals(17, terminal.liveSequence());
    assertEquals(17L, eventCount(manifest));

    System.out.println(
        "PACK010_V12_DURABLE_FAILURE_CROSS_JVM_RACE_RECEIPT"
            + " childProcesses=2 childWinners=1"
            + " childLoser=FENCED"
            + " parentProcesses=2 parentWinners=1"
            + " parentLoser=FENCED"
            + " distinctJvmPids=true"
            + " resumeVersion=1->2->3->4->5"
            + " head=14->15->17"
            + " providerCalls=0 effects=0");
  }

  @Test
  void preCommitHardKillsRollBackTxBAndTxCAcrossRestartAndFreshJvmRetry()
      throws Exception {
    int repetition = 3;
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    String childClaim =
        runClaimAndHalt(
            "claim-terminal-child-and-halt",
            "fault-child-claimant",
            repetition,
            tempDir.resolve("fault-child-claim"),
            72);
    assertTrue(childClaim.contains("stateVersion=2"), childClaim);
    assertEquals(14, liveSequence(manifest));
    assertEquals(14L, eventCount(manifest));
    assertCrossDatabaseSnapshotSpliceRejected(manifest);

    Map<String, String> beforeChild = databaseSnapshot();
    PreCommitKillReceipt childKill =
        runPreCommitKill(
            "resume-child-kill-before-commit",
            "fault-child-completer",
            repetition,
            tempDir.resolve("fault-child-completion"),
            76);
    awaitBackendGone(childKill.backendPid());
    assertEquals(beforeChild, databaseSnapshot());
    var childClaimed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CLAIMED,
        childClaimed.state());
    assertEquals(2L, childClaimed.stateVersion());
    assertEquals(14, childClaimed.liveSequence());
    assertEquals(0L, terminalResumeReceiptCount(manifest));
    assertEquals(14L, eventCount(manifest));

    String childRetry =
        runCompletionAndHalt(
            "resume-child-and-halt",
            "fault-child-retry",
            repetition,
            tempDir.resolve("fault-child-retry"),
            73);
    assertTrue(childRetry.contains("state=CHILD_CONSUMED"), childRetry);
    assertTrue(tokenLong(childRetry, "pid") != childKill.javaPid());
    var childConsumed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CHILD_CONSUMED,
        childConsumed.state());
    assertEquals(3L, childConsumed.stateVersion());
    assertEquals(15, childConsumed.liveSequence());
    assertEquals(15L, eventCount(manifest));

    Map<String, String> beforeRestart = databaseSnapshot();
    restartPostgres(writerDataSource());
    assertEquals(beforeRestart, databaseSnapshot());
    var afterRestart =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CHILD_CONSUMED,
        afterRestart.state());
    assertEquals(3L, afterRestart.stateVersion());
    assertEquals(15, afterRestart.liveSequence());

    String parentClaim =
        runClaimAndHalt(
            "claim-parent-and-halt",
            "fault-parent-claimant",
            repetition,
            tempDir.resolve("fault-parent-claim"),
            74);
    assertTrue(parentClaim.contains("stateVersion=4"), parentClaim);
    Map<String, String> beforeParent = databaseSnapshot();
    PreCommitKillReceipt parentKill =
        runPreCommitKill(
            "resume-parent-kill-before-commit",
            "fault-parent-completer",
            repetition,
            tempDir.resolve("fault-parent-completion"),
            77);
    awaitBackendGone(parentKill.backendPid());
    assertEquals(beforeParent, databaseSnapshot());
    var parentClaimed =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.PARENT_CLAIMED,
        parentClaimed.state());
    assertEquals(4L, parentClaimed.stateVersion());
    assertEquals(15, parentClaimed.liveSequence());
    assertEquals(15L, eventCount(manifest));

    String parentRetry =
        runCompletionAndHalt(
            "resume-parent-and-halt",
            "fault-parent-retry",
            repetition,
            tempDir.resolve("fault-parent-retry"),
            75);
    assertTrue(
        parentRetry.contains("state=TERMINAL_CONSUMED"), parentRetry);
    assertTrue(tokenLong(parentRetry, "pid") != parentKill.javaPid());
    var terminal =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.TERMINAL_CONSUMED,
        terminal.state());
    assertEquals(5L, terminal.stateVersion());
    assertEquals(17, terminal.liveSequence());
    assertEquals(17L, eventCount(manifest));
    GraphAttemptSnapshot sequence17 =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(writerDataSource()),
            manifest);
    assertEquals(GraphAttemptOutcome.FAILED, sequence17.outcome());
    assertEquals(GraphBillingStatus.ATTRIBUTED, sequence17.billingStatus());
    assertEquals(AgentRunLifecycle.FAILED, sequence17.childRun().lifecycle());
    assertEquals(AgentRunLifecycle.FAILED, sequence17.parentRun().lifecycle());
    assertNull(sequence17.candidate());
    assertNull(sequence17.workerResult());
    assertNull(sequence17.artifact());
    assertTrue(sequence17.terminalSeal() != null);

    System.out.println(
        "PACK010_V12_PRECOMMIT_HARD_KILL_RECEIPT"
            + " repetition=3 txBExit=76 txCExit=77"
            + " rollback=FULL_JSON_XMIN"
            + " pgRestart=AFTER_TX_B"
            + " freshJvmRetries=2"
            + " head=14->15->17"
            + " resumeVersion=1->2->3->4->5");
  }

  private String runClaimAndHalt(
      String mode, String actor, Path coordination, int expectedExit)
      throws Exception {
    return runClaimAndHalt(
        mode, actor, 1, coordination, expectedExit);
  }

  private String runClaimAndHalt(
      String mode,
      String actor,
      int repetition,
      Path coordination,
      int expectedExit)
      throws Exception {
    Files.createDirectory(coordination);
    Process process =
        start(claimCommand(mode, actor, repetition, coordination));
    try {
      writeSecrets(process, RESUMER_PASSWORD);
      awaitFile(process, coordination.resolve(actor + ".ready"));
      Files.writeString(coordination.resolve("claim.release"), "release");
      assertTrue(
          process.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      String output = readOutput(process);
      assertSecretAbsent(output, RESUMER_PASSWORD, "claim subprocess");
      assertEquals(expectedExit, process.exitValue(), output);
      return output;
    } finally {
      destroy(process);
    }
  }

  private String runCompletionAndHalt(
      String mode, String actor, Path coordination, int expectedExit)
      throws Exception {
    return runCompletionAndHalt(
        mode, actor, 1, coordination, expectedExit);
  }

  private String runCompletionAndHalt(
      String mode,
      String actor,
      int repetition,
      Path coordination,
      int expectedExit)
      throws Exception {
    Files.createDirectory(coordination);
    Process process =
        start(completionCommand(mode, actor, repetition, coordination));
    try {
      writeSecrets(process, RESUMER_PASSWORD, READER_PASSWORD);
      awaitFile(process, coordination.resolve(actor + ".ready"));
      Files.writeString(
          coordination.resolve("complete.release"), "release");
      assertTrue(
          process.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      String output = readOutput(process);
      assertSecretAbsent(output, RESUMER_PASSWORD, "completion subprocess");
      assertSecretAbsent(output, READER_PASSWORD, "completion subprocess");
      assertEquals(expectedExit, process.exitValue(), output);
      return output;
    } finally {
      destroy(process);
    }
  }

  private String runRawV10(
      String mode, Path payload, GraphAttemptManifest manifest)
      throws Exception {
    Process process = start(rawV10Command(mode, payload, manifest));
    try {
      writeSecrets(process, EXECUTOR_PASSWORD);
      assertTrue(
          process.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      String output = readOutput(process);
      assertSecretAbsent(output, EXECUTOR_PASSWORD, "raw V10 subprocess");
      assertEquals(73, process.exitValue(), output);
      return output;
    } finally {
      destroy(process);
    }
  }

  private PreCommitKillReceipt runPreCommitKill(
      String mode,
      String actor,
      int repetition,
      Path coordination,
      int expectedExit)
      throws Exception {
    String output =
        runCompletionAndHalt(
            mode, actor, repetition, coordination, expectedExit);
    Path marker = coordination.resolve(actor + ".pre-commit");
    assertTrue(Files.isRegularFile(marker), output);
    String durableReceipt = Files.readString(marker);
    assertSecretAbsent(
        durableReceipt, RESUMER_PASSWORD, "pre-commit marker");
    assertSecretAbsent(
        durableReceipt, READER_PASSWORD, "pre-commit marker");
    assertTrue(
        durableReceipt.contains("PACK010_V12_PRECOMMIT_HARD_KILL"),
        durableReceipt);
    assertTrue(output.contains(durableReceipt), output);
    return new PreCommitKillReceipt(
        tokenLong(durableReceipt, "pid"),
        tokenLong(durableReceipt, "backendPid"),
        token(durableReceipt, "transactionId"));
  }

  private RaceOutputs runCompletionRace(
      String mode,
      int repetition,
      Path coordination,
      int winningExit)
      throws Exception {
    Files.createDirectory(coordination);
    Process first =
        start(
            completionCommand(
                mode, "race-a", repetition, coordination));
    Process second =
        start(
            completionCommand(
                mode, "race-b", repetition, coordination));
    long firstPid = first.pid();
    long secondPid = second.pid();
    try {
      writeSecrets(first, RESUMER_PASSWORD, READER_PASSWORD);
      writeSecrets(second, RESUMER_PASSWORD, READER_PASSWORD);
      awaitFile(first, coordination.resolve("race-a.ready"));
      awaitFile(second, coordination.resolve("race-b.ready"));
      Files.writeString(
          coordination.resolve("complete.release"), "release");
      assertTrue(
          first.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      assertTrue(
          second.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      String firstOutput = readOutput(first);
      String secondOutput = readOutput(second);
      assertSecretAbsent(firstOutput, RESUMER_PASSWORD, "first race subprocess");
      assertSecretAbsent(firstOutput, READER_PASSWORD, "first race subprocess");
      assertSecretAbsent(secondOutput, RESUMER_PASSWORD, "second race subprocess");
      assertSecretAbsent(secondOutput, READER_PASSWORD, "second race subprocess");
      assertEquals(
          List.of(4, winningExit).stream().sorted().toList(),
          List.of(first.exitValue(), second.exitValue())
              .stream()
              .sorted()
              .toList(),
          firstOutput + "\n" + secondOutput);
      long loserPid = first.exitValue() == 4 ? firstPid : secondPid;
      String expectedRejection =
          "PACK010_DURABLE_FAILURE_REJECTED pid="
              + loserPid
              + " reason=FENCED";
      assertEquals(
          List.of(expectedRejection),
          List.of(firstOutput, secondOutput).stream()
              .flatMap(String::lines)
              .filter(
                  line ->
                      line.startsWith(
                          "PACK010_DURABLE_FAILURE_REJECTED "))
              .toList(),
          firstOutput + "\n" + secondOutput);
      assertTrue(firstPid != secondPid);
      assertTrue(firstOutput.contains("pid=" + firstPid), firstOutput);
      assertTrue(secondOutput.contains("pid=" + secondPid), secondOutput);
      return new RaceOutputs(firstOutput, secondOutput);
    } finally {
      destroy(first);
      destroy(second);
    }
  }

  private static void assertSecretAbsent(
      String text, String secret, String source) {
    assertFalse(
        text.contains(secret), source + " must not expose a database secret");
  }

  private static List<String> claimCommand(
      String mode, String actor, Path coordination) {
    return claimCommand(mode, actor, 1, coordination);
  }

  private static List<String> claimCommand(
      String mode, String actor, int repetition, Path coordination) {
    List<String> command = javaCommand(CLAIM_HARNESS);
    command.addAll(
        List.of(
            mode,
            actor,
            Integer.toString(repetition),
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            "emergeos_failure_resumer",
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> completionCommand(
      String mode, String actor, Path coordination) {
    return completionCommand(mode, actor, 1, coordination);
  }

  private static List<String> completionCommand(
      String mode, String actor, int repetition, Path coordination) {
    List<String> command = javaCommand(CLAIM_HARNESS);
    command.addAll(
        List.of(
            mode,
            actor,
            Integer.toString(repetition),
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            "emergeos_failure_resumer",
            "emergeos_graph_reader",
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> rawV10Command(
      String mode, Path payload, GraphAttemptManifest manifest) {
    List<String> command = javaCommand(RED_HARNESS);
    command.addAll(
        List.of(
            mode,
            payload.toAbsolutePath().normalize().toString(),
            POSTGRES.getJdbcUrl(),
            "emergeos_graph_executor",
            manifest.principalId(),
            manifest.attemptId(),
            shippingJar().toString(),
            testClasses().toString(),
            repo().toString()));
    return command;
  }

  private static List<String> javaCommand(String mainClass) {
    return new ArrayList<>(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.net.useSystemProxies=false",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Dfile.encoding=UTF-8",
            "-cp",
            shippingJar() + File.pathSeparator + testClasses(),
            mainClass));
  }

  private static Process start(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo().toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    return builder.start();
  }

  private static void writeSecrets(Process process, String... secrets)
      throws IOException {
    try (DataOutputStream output =
        new DataOutputStream(process.getOutputStream())) {
      for (String secret : secrets) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
      }
    }
  }

  private static void awaitFile(Process process, Path path)
      throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(path)) {
        return;
      }
      if (!process.isAlive()) {
        String output = readOutput(process);
        assertSecretAbsent(output, RESUMER_PASSWORD, "early subprocess output");
        assertSecretAbsent(output, READER_PASSWORD, "early subprocess output");
        assertSecretAbsent(output, EXECUTOR_PASSWORD, "early subprocess output");
        throw new IllegalStateException(
            "packaged claimant exited early: " + output);
      }
      Thread.sleep(25);
    }
    throw new IllegalStateException("claimant readiness timed out");
  }

  private static void destroy(Process process) throws InterruptedException {
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  private static String readOutput(Process process) throws IOException {
    return new String(
        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static int liveSequence(GraphAttemptManifest manifest) {
    return JdbcClient.create(adminDataSource())
        .sql(
            "SELECT last_sequence FROM agent_graph_attempt_heads "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Integer.class)
        .single();
  }

  private static long eventCount(GraphAttemptManifest manifest) {
    return JdbcClient.create(adminDataSource())
        .sql(
            "SELECT count(*) FROM agent_graph_attempt_events "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static long terminalResumeReceiptCount(
      GraphAttemptManifest manifest) {
    return JdbcClient.create(adminDataSource())
        .sql(
            "SELECT count(*) "
                + "FROM agent_graph_attributed_failure_terminal_resumes "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static void awaitBackendGone(long backendPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      long count =
          JdbcClient.create(adminDataSource())
              .sql(
                  "SELECT count(*) FROM pg_catalog.pg_stat_activity "
                      + "WHERE pid = :backendPid")
              .param("backendPid", backendPid)
              .query(Long.class)
              .single();
      if (count == 0L) {
        return;
      }
      Thread.sleep(25);
    }
    throw new IllegalStateException("pre-commit backend remained active");
  }

  private static void restartPostgres(DataSource probe) throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart -D \"$PGDATA\" -m immediate -w");
    assertEquals(0, restart.getExitCode(), restart.getStderr());
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    java.sql.SQLException last = null;
    while (System.nanoTime() < deadline) {
      try (var ignored = probe.getConnection()) {
        return;
      } catch (java.sql.SQLException unavailable) {
        last = unavailable;
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("PostgreSQL restart timed out", last);
  }

  private static long tokenLong(String receipt, String name) {
    return Long.parseLong(token(receipt, name));
  }

  private static String token(String receipt, String name) {
    for (String value : receipt.split(" ")) {
      if (value.startsWith(name + "=")) {
        String token = value.substring(name.length() + 1);
        if (!token.isBlank()) {
          return token;
        }
      }
    }
    throw new IllegalArgumentException(name + " missing");
  }

  private static long resumeVersion(GraphAttemptManifest manifest) {
    return new PostgresAttributedFailureResumeStore(resumerDataSource())
        .load(manifest)
        .stateVersion();
  }

  private static Map<String, String> databaseSnapshot() {
    return databaseSnapshot(adminDataSource());
  }

  private static Map<String, String> databaseSnapshot(
      DataSource adminDataSource) {
    JdbcClient jdbc = JdbcClient.create(adminDataSource);
    List<String> tables =
        jdbc.sql(
                "SELECT relation.relname "
                    + "FROM pg_catalog.pg_class relation "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = relation.relnamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND relation.relkind IN ('r', 'p') "
                    + "ORDER BY relation.relname")
            .query(String.class)
            .list();
    Map<String, String> snapshot = new LinkedHashMap<>();
    for (String table : tables) {
      if (!table.matches("[a-z0-9_]+")) {
        throw new IllegalStateException("unexpected relation identity");
      }
      String rows =
          jdbc.sql(
                  "SELECT COALESCE(jsonb_agg("
                      + "to_jsonb(row_data) || jsonb_build_object("
                      + "'__xmin', row_data.xmin::text) "
                      + "ORDER BY to_jsonb(row_data)::text), "
                      + "'[]'::jsonb)::text FROM public."
                      + table
                      + " row_data")
              .query(String.class)
              .single();
      snapshot.put(table, rows);
    }
    return Map.copyOf(snapshot);
  }

  private static void assertChildCompletionClaimMatrixRejected(
      PostgresAttributedFailureResumeStore resume,
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor claim,
      AgentRun terminalChild) {
    String wrongProvenance =
        IntegrityHashes.utf8ContentHash(
            "pack010-v12-wrong-child-provenance");
    String wrongFence =
        IntegrityHashes.utf8ContentHash(
            "pack010-v12-wrong-child-fence");
    assertClaimLiveForCompletion(manifest, false);
    assertChildCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            wrongProvenance,
            claim.stateVersion(),
            claim.claimantId(),
            claim.fenceTokenHash()),
        terminalChild);
    assertClaimLiveForCompletion(manifest, false);
    assertChildCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion() + 1,
            claim.claimantId(),
            claim.fenceTokenHash()),
        terminalChild);
    assertClaimLiveForCompletion(manifest, false);
    assertChildCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion(),
            "wrong-child-claimant",
            claim.fenceTokenHash()),
        terminalChild);
    assertClaimLiveForCompletion(manifest, false);
    assertChildCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion(),
            claim.claimantId(),
            wrongFence),
        terminalChild);

    assertClaimLiveForCompletion(manifest, false);
    Map<String, String> beforeWrongModel = databaseSnapshot();
    IllegalArgumentException wrongModel =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resume.completeClaimedFailureChild(
                    manifest,
                    claim,
                    readerDataSource(),
                    withResolvedModel(
                        terminalChild, "wrong-provider-model")));
    assertEquals(
        "child terminal usage is not exactly provider-attributed",
        wrongModel.getMessage());
    assertEquals(beforeWrongModel, databaseSnapshot());
  }

  private static void assertParentCompletionClaimMatrixRejected(
      PostgresAttributedFailureResumeStore resume,
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor claim,
      AgentRun terminalParent) {
    String wrongProvenance =
        IntegrityHashes.utf8ContentHash(
            "pack010-v12-wrong-parent-provenance");
    String wrongFence =
        IntegrityHashes.utf8ContentHash(
            "pack010-v12-wrong-parent-fence");
    assertClaimLiveForCompletion(manifest, true);
    assertParentCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            wrongProvenance,
            claim.stateVersion(),
            claim.claimantId(),
            claim.fenceTokenHash()),
        terminalParent);
    assertClaimLiveForCompletion(manifest, true);
    assertParentCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion() + 1,
            claim.claimantId(),
            claim.fenceTokenHash()),
        terminalParent);
    assertClaimLiveForCompletion(manifest, true);
    assertParentCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion(),
            "wrong-parent-claimant",
            claim.fenceTokenHash()),
        terminalParent);
    assertClaimLiveForCompletion(manifest, true);
    assertParentCompletionFencedWithoutMutation(
        resume,
        manifest,
        forgedClaim(
            claim,
            claim.provenanceHash(),
            claim.stateVersion(),
            claim.claimantId(),
            wrongFence),
        terminalParent);
  }

  private static void assertChildCompletionFencedWithoutMutation(
      PostgresAttributedFailureResumeStore resume,
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor claim,
      AgentRun terminalChild) {
    Map<String, String> before = databaseSnapshot();
    GraphAttemptConflictException failure =
        assertThrows(
            GraphAttemptConflictException.class,
            () ->
                resume.completeClaimedFailureChild(
                    manifest,
                    claim,
                    readerDataSource(),
                    terminalChild));
    assertEquals(
        "durable failure terminal claim was fenced",
        failure.getMessage());
    assertEquals(before, databaseSnapshot());
  }

  private static void assertParentCompletionFencedWithoutMutation(
      PostgresAttributedFailureResumeStore resume,
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor claim,
      AgentRun terminalParent) {
    Map<String, String> before = databaseSnapshot();
    GraphAttemptConflictException failure =
        assertThrows(
            GraphAttemptConflictException.class,
            () ->
                resume.completeClaimedFailureParentAndSeal(
                    manifest,
                    claim,
                    readerDataSource(),
                    terminalParent));
    assertEquals(
        "durable failure terminal claim was fenced",
        failure.getMessage());
    assertEquals(before, databaseSnapshot());
  }

  private static void assertChildWrongModelSqlFencedWithoutMutation(
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor claim,
      String validPayload) {
    assertClaimLiveForCompletion(manifest, false);
    String modelField =
        "\"resolved_model\":\"" + claim.modelResolved() + "\"";
    assertTrue(validPayload.contains(modelField));
    assertEquals(
        validPayload.indexOf(modelField),
        validPayload.lastIndexOf(modelField),
        "resolved_model target must be unique");
    String wrongModelPayload =
        validPayload.replace(
            modelField, "\"resolved_model\":\"wrong-provider-model\"");
    assertFalse(validPayload.equals(wrongModelPayload));
    Map<String, String> before = databaseSnapshot();
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(resumerDataSource())
                    .sql(
                        "SELECT public."
                            + "agent_graph_complete_claimed_failure_child_v12("
                            + "CAST(:payload AS jsonb), :provenanceHash, "
                            + ":stateVersion, :claimantId, :fenceTokenHash)"
                            + "::text")
                    .param("payload", wrongModelPayload)
                    .param("provenanceHash", claim.provenanceHash())
                    .param("stateVersion", claim.stateVersion())
                    .param("claimantId", claim.claimantId())
                    .param("fenceTokenHash", claim.fenceTokenHash())
                    .query(String.class)
                    .single());
    assertEquals("55000", sqlState(failure));
    assertTrue(
        containsMessage(
            failure, "V12 child terminal claim was fenced"));
    assertEquals(before, databaseSnapshot());
  }

  private static void assertClaimLiveForCompletion(
      GraphAttemptManifest manifest, boolean parentClaim) {
    String sql =
        parentClaim
            ? "SELECT claim_expires_at > "
                + "clock_timestamp() + interval '100 milliseconds' "
                + "FROM agent_graph_attributed_failure_terminal_resumes "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId"
            : "SELECT claim_expires_at > "
                + "clock_timestamp() + interval '100 milliseconds' "
                + "FROM agent_graph_attributed_failure_outcomes "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId";
    boolean live =
        JdbcClient.create(adminDataSource())
            .sql(sql)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Boolean.class)
            .single();
    assertTrue(live, "claim must remain live while testing field fences");
  }

  private static void awaitClaimExpired(
      GraphAttemptManifest manifest, boolean parentClaim)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    String table =
        parentClaim
            ? "agent_graph_attributed_failure_terminal_resumes"
            : "agent_graph_attributed_failure_outcomes";
    while (System.nanoTime() < deadline) {
      boolean expired =
          JdbcClient.create(adminDataSource())
              .sql(
                  "SELECT claim_expires_at <= clock_timestamp() "
                      + "FROM "
                      + table
                      + " "
                      + "WHERE principal_id = :principalId "
                      + "AND attempt_id = :attemptId")
              .param("principalId", manifest.principalId())
              .param("attemptId", manifest.attemptId())
              .query(Boolean.class)
              .single();
      if (expired) {
        return;
      }
      Thread.sleep(25L);
    }
    throw new IllegalStateException("durable claim did not expire");
  }

  private static PostgresAttributedFailureResumeStore.DurableFailureCursor
      forgedClaim(
          PostgresAttributedFailureResumeStore.DurableFailureCursor source,
          String provenanceHash,
          long stateVersion,
          String claimantId,
          String fenceTokenHash) {
    Long parentClaimVersion =
        source.state()
                == PostgresAttributedFailureResumeStore.State.PARENT_CLAIMED
            ? Long.valueOf(stateVersion)
            : source.parentClaimVersion();
    return new PostgresAttributedFailureResumeStore.DurableFailureCursor(
        source.principalId(),
        source.attemptId(),
        source.manifestHash(),
        source.revision(),
        source.sessionIntentHash(),
        source.sessionExpiresAt(),
        source.cursorSequence(),
        source.cursorHeadHash(),
        source.providerAttribution1Hash(),
        source.providerAttribution2Hash(),
        source.requestHash(),
        source.responseHash(),
        source.modelResolved(),
        source.failureCode(),
        provenanceHash,
        source.state(),
        stateVersion,
        claimantId,
        fenceTokenHash,
        source.claimExpiresAt(),
        source.childClaimVersion(),
        source.childClaimantId(),
        source.childFenceTokenHash(),
        source.childClaimExpiresAt(),
        source.childSequence(),
        source.childHeadHash(),
        source.childTerminalHash(),
        parentClaimVersion,
        source.parentSequence(),
        source.parentHeadHash(),
        source.parentTerminalHash(),
        source.terminalSequence(),
        source.terminalHeadHash(),
        source.sealHash(),
        source.liveSequence(),
        source.liveHeadHash());
  }

  private static AgentRun withResolvedModel(
      AgentRun source, String resolvedModel) {
    ResultEnvelope result = source.result();
    ResultEnvelope changedResult =
        new ResultEnvelope(
            result.schemaVersion(),
            result.runId(),
            result.taskId(),
            result.status(),
            result.artifactRefs(),
            result.evidenceRefs(),
            result.claims(),
            result.uncertainty(),
            result.receiptRefs(),
            resolvedModel,
            result.agentVersion(),
            result.verifierVersion(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs(),
            result.traceRef(),
            result.failureReason());
    HarnessRunBundle bundle = source.bundle();
    HarnessRunBundle changedBundle =
        HarnessRunBundle.create(
            bundle.schemaVersion(),
            bundle.runId(),
            bundle.taskId(),
            bundle.experiment(),
            resolvedModel,
            bundle.harnessVersion(),
            bundle.componentVersions(),
            bundle.environmentSnapshotRef(),
            bundle.toolRegistryVersion(),
            bundle.task(),
            changedResult,
            bundle.workingSelfRef(),
            bundle.traceRef(),
            bundle.traceRootHash(),
            bundle.handoffRefs(),
            bundle.checkpointRefs(),
            bundle.resourceBindings(),
            bundle.verificationRef(),
            bundle.failureAttribution(),
            bundle.outcome(),
            bundle.costUsd(),
            bundle.tokenCount(),
            bundle.latencyMs());
    return new AgentRun(
        source.runId(),
        source.principalId(),
        source.task(),
        source.lifecycle(),
        changedResult,
        source.trace(),
        changedBundle,
        source.startedAt(),
        source.completedAt());
  }

  private static boolean containsMessage(
      Throwable failure, String expected) {
    for (Throwable current = failure;
        current != null;
        current = current.getCause()) {
      if (current.getMessage() != null
          && current.getMessage().contains(expected)) {
        return true;
      }
    }
    return false;
  }

  private static String sqlState(Throwable failure) {
    for (Throwable current = failure;
        current != null;
        current = current.getCause()) {
      if (current instanceof java.sql.SQLException sqlFailure) {
        return sqlFailure.getSQLState();
      }
    }
    return null;
  }

  private static void assertCrossDatabaseSnapshotSpliceRejected(
      GraphAttemptManifest manifest) {
    String cloneDatabase =
        "pack010_v12_splice_"
            + UUID.randomUUID().toString().replace("-", "");
    if (!cloneDatabase.matches("[a-z][a-z0-9_]{1,62}")) {
      throw new IllegalStateException("clone database identity drifted");
    }
    execute(
        clusterAdminDataSource(),
        "CREATE DATABASE "
            + cloneDatabase
            + " TEMPLATE "
            + POSTGRES.getDatabaseName());
    DataSource cloneAdmin =
        databaseDataSource(
            cloneDatabase,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    DataSource cloneReader =
        databaseDataSource(
            cloneDatabase,
            "emergeos_graph_reader",
            READER_PASSWORD);
    GraphAttemptVerification verification =
        PostgresGraphAttemptAccess.openReader(cloneReader)
            .findVerified(manifest);
    if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
      throw new IllegalStateException("clone snapshot was not valid");
    }
    GraphAttemptSnapshot cloneSnapshot = valid.snapshot();
    assertEquals(14, cloneSnapshot.cursor().lastSequence());
    assertEquals(
        liveHeadHash(adminDataSource(), manifest),
        cloneSnapshot.cursor().headHash());
    assertTrue(
        !databaseOid(adminDataSource()).equals(databaseOid(cloneAdmin)));
    var cloneTerminalChild =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
                cloneSnapshot,
                manifest,
                Pack010GraphEvalCatalog.workerProfile(3),
                manifest.startedAt(),
                "MODEL_RESPONSE_MALFORMED")
            .terminal();
    Map<String, String> sourceBefore = databaseSnapshot();
    Map<String, String> cloneBefore = databaseSnapshot(cloneAdmin);
    PostgresAttributedFailureResumeStore sourceResume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    var sourceClaim = sourceResume.load(manifest);
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            sourceResume.completeClaimedFailureChild(
                manifest,
                sourceClaim,
                cloneReader,
                cloneTerminalChild));
    assertEquals(sourceBefore, databaseSnapshot());
    assertEquals(cloneBefore, databaseSnapshot(cloneAdmin));
  }

  private static String liveHeadHash(
      DataSource source, GraphAttemptManifest manifest) {
    return JdbcClient.create(source)
        .sql(
            "SELECT head_hash FROM agent_graph_attempt_heads "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static String databaseOid(DataSource source) {
    return JdbcClient.create(source)
        .sql(
            "SELECT oid::text FROM pg_catalog.pg_database "
                + "WHERE datname = current_database()")
        .query(String.class)
        .single();
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    try {
      JdbcClient.create(admin)
          .sql("ALTER ROLE " + role + " PASSWORD '" + password + "'")
          .update();
    } catch (RuntimeException ignored) {
      throw new IllegalStateException("EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
    }
  }

  private static DataSource adminDataSource() {
    return databaseDataSource(
        POSTGRES.getDatabaseName(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private static DataSource clusterAdminDataSource() {
    return databaseDataSource(
        "postgres", POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DataSource writerDataSource() {
    return dataSource("emergeos_graph_prefix_writer", WRITER_PASSWORD);
  }

  private static DataSource resumerDataSource() {
    return dataSource("emergeos_failure_resumer", RESUMER_PASSWORD);
  }

  private static DataSource readerDataSource() {
    return dataSource("emergeos_graph_reader", READER_PASSWORD);
  }

  private static DataSource dataSource(String user, String password) {
    return databaseDataSource(POSTGRES.getDatabaseName(), user, password);
  }

  private static DataSource databaseDataSource(
      String database, String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setServerNames(new String[] {POSTGRES.getHost()});
    source.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
    source.setDatabaseName(database);
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void execute(DataSource dataSource, String sql) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String resource(String path) {
    try (var stream =
        Pack010DurableFailureTerminalResumeProcessIT.class
            .getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalArgumentException("missing resource " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Path shippingJar() {
    return repo()
        .resolve(
            "apps/graph-eval-runner/target/"
                + "emerge-graph-eval-runner-0.1.0-SNAPSHOT-app.jar")
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return repo()
        .resolve("apps/graph-eval-runner/target/test-classes")
        .toAbsolutePath()
        .normalize();
  }

  private static Path repo() {
    return Path.of(System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize()
        .getParent()
        .getParent();
  }

  private record RaceOutputs(String first, String second) {
    private String combined() {
      return first + "\n" + second;
    }
  }

  private record PreCommitKillReceipt(
      long javaPid, long backendPid, String transactionId) {
    private PreCommitKillReceipt {
      assertTrue(javaPid > 0L);
      assertTrue(backendPid > 0L);
      assertFalse(transactionId.isBlank());
    }
  }
}
