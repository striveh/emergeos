package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresAttributedFailureResumeStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphBillingStatus;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010ProviderValidationAttestationProcessIT {

  private static final String HARNESS_MAIN =
      "io.emergeos.grapheval."
          + "Pack010ProviderValidationAttestationHarnessMain";
  private static final String WRITER_PASSWORD = UUID.randomUUID().toString();
  private static final String ATTESTOR_PASSWORD = UUID.randomUUID().toString();
  private static final String RESUMER_PASSWORD = UUID.randomUUID().toString();
  private static final String READER_PASSWORD = UUID.randomUUID().toString();
  private static final String KEY_ID = "pack010-v13-process-key";
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

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

  private static KeyPair keyPair;

  @TempDir Path temporary;

  @BeforeAll
  static void prepareDatabase() throws Exception {
    DataSource admin = adminDataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);
    provisionProductionRoles(admin);
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] publicKey = keyPair.getPublic().getEncoded();
    JdbcClient.create(admin)
        .sql(
            """
            INSERT INTO agent_graph_provider_validation_keys (
              key_id, algorithm, public_key_der, key_fingerprint,
              status, not_before, not_after
            ) VALUES (
              :keyId, 'ED25519', :publicKey, :fingerprint,
              'ACTIVE', clock_timestamp() - interval '1 minute',
              clock_timestamp() + interval '1 hour'
            )
            """)
        .param("keyId", KEY_ID)
        .param("publicKey", publicKey)
        .param(
            "fingerprint",
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(publicKey)))
        .update();
  }

  @Test
  void freshJvmsRollBackFaultsSurviveRestartAndFenceRaces()
      throws Exception {
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.shippingJarViolations(shippingJar()));
    AttemptCase stageCase = prepareAttempt(1);
    Path stageCoordination = directory("stage");
    DatabaseDigest stageBefore = databaseDigest(adminDataSource());
    Process stage =
        start("stage-halt", "stage", stageCase, stageCoordination);
    awaitFile(stage, stageCoordination.resolve("stage.staged"));
    ProcessResult stageResult = await(stage);
    assertEquals(81, stageResult.exitCode(), stageResult.output());
    assertTrue(stageResult.output().contains("signerCalls=0"));
    long stageBackend =
        Long.parseLong(
            field(
                    Pack009ProcessSupport.readBounded(
                        stageCoordination.resolve("stage.staged")),
                    "backend")
                .split(":", 2)[0]);
    awaitBackendGone(stageBackend);
    assertEquals(stageBefore, databaseDigest(adminDataSource()));
    assertAttempt(stageCase, 13, "REQUIRED");

    Process committed =
        start(
            "complete-and-halt",
            "committed",
            stageCase,
            stageCoordination);
    ProcessResult committedResult = await(committed);
    assertEquals(83, committedResult.exitCode(), committedResult.output());
    assertTrue(
        committedResult.output().contains(
            "PACK010_V13_PROVIDER_VALIDATION_COMMITTED"));
    assertAttempt(stageCase, 14, "CONSUMED");
    DatabaseDigest durable = databaseDigest(adminDataSource());
    Instant postmasterBefore = postmasterStartTime(adminDataSource());
    restartPostgres(adminDataSource());
    assertTrue(
        postmasterStartTime(adminDataSource()).isAfter(postmasterBefore));
    assertEquals(durable, databaseDigest(adminDataSource()));
    Process replay =
        start("replay", "replay", stageCase, stageCoordination);
    ProcessResult replayResult = await(replay);
    assertEquals(4, replayResult.exitCode(), replayResult.output());
    assertTrue(replayResult.output().contains("reason=FENCED"));
    assertTrue(replayResult.output().contains("signerCalls=0"));
    assertEquals(durable, databaseDigest(adminDataSource()));

    AttemptCase precommitCase = prepareAttempt(2);
    Path precommitCoordination = directory("precommit");
    DatabaseDigest precommitBefore = databaseDigest(adminDataSource());
    Process precommit =
        start(
            "precommit-halt",
            "precommit",
            precommitCase,
            precommitCoordination);
    awaitFile(
        precommit,
        precommitCoordination.resolve("precommit.pre-commit"));
    ProcessResult precommitResult = await(precommit);
    assertEquals(82, precommitResult.exitCode(), precommitResult.output());
    String precommitMarker =
        Pack009ProcessSupport.readBounded(
            precommitCoordination.resolve("precommit.pre-commit"));
    assertTrue(
        precommitMarker.startsWith("PACK010_V13_PRECOMMIT_HARD_KILL"));
    awaitBackendGone(Long.parseLong(field(precommitMarker, "backendPid")));
    assertEquals(precommitBefore, databaseDigest(adminDataSource()));
    assertAttempt(precommitCase, 13, "REQUIRED");
    Process precommitRetry =
        start(
            "complete-and-halt",
            "retry",
            precommitCase,
            precommitCoordination);
    ProcessResult retryResult = await(precommitRetry);
    assertEquals(83, retryResult.exitCode(), retryResult.output());
    assertAttempt(precommitCase, 14, "CONSUMED");

    AttemptCase raceCase = prepareAttempt(3);
    Path raceCoordination = directory("race");
    Process first = start("race", "race-a", raceCase, raceCoordination);
    Process second = start("race", "race-b", raceCase, raceCoordination);
    awaitFile(first, raceCoordination.resolve("race-a.ready"));
    awaitFile(second, raceCoordination.resolve("race-b.ready"));
    Files.writeString(raceCoordination.resolve("race.release"), "release");
    ProcessResult firstResult = await(first);
    ProcessResult secondResult = await(second);
    assertEquals(
        Set.of(4, 83),
        Set.of(firstResult.exitCode(), secondResult.exitCode()));
    ProcessResult loser =
        firstResult.exitCode() == 4 ? firstResult : secondResult;
    ProcessResult winner =
        firstResult.exitCode() == 83 ? firstResult : secondResult;
    assertTrue(loser.output().contains("reason=FENCED"));
    assertTrue(loser.output().contains("signerCalls=0"));
    assertTrue(winner.output().contains("decision=FAILED"));
    assertTrue(winner.output().contains("signerCalls=1"));
    assertAttempt(raceCase, 14, "CONSUMED");
    assertEquals(
        1L,
        JdbcClient.create(adminDataSource())
            .sql(
                """
                SELECT count(*)
                FROM agent_graph_attributed_failure_outcomes
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND failure_code = 'MODEL_RESPONSE_MALFORMED'
                  AND provenance_hash ~ '^[0-9a-f]{64}$'
                """)
            .param("principalId", raceCase.manifest().principalId())
            .param("attemptId", raceCase.manifest().attemptId())
            .query(Long.class)
            .single());

    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    GraphAttemptManifest failureManifest = raceCase.manifest();
    ValidationImage validationAtSequence14 =
        validationImage(failureManifest);
    assertEquals("CONSUMED", validationAtSequence14.state());
    assertEquals("FAILED", validationAtSequence14.decisionKind());
    assertEquals(14, validationAtSequence14.cursorSequence());

    var ready = resume.load(failureManifest);
    assertEquals(PostgresAttributedFailureResumeStore.State.READY, ready.state());
    assertEquals(1L, ready.stateVersion());
    assertEquals(14, ready.cursorSequence());
    assertEquals(14, ready.liveSequence());
    assertEquals(
        GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
        ready.failureCode());
    assertEquals(
        validationAtSequence14.failureProvenanceHash(),
        ready.provenanceHash());

    var childClaim =
        resume.claim(
            failureManifest,
            ready,
            "v13-failure-child",
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-failure-child-fence"),
            Duration.ofSeconds(20));
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CLAIMED,
        childClaim.state());
    assertEquals(2L, childClaim.stateVersion());
    assertEquals(14, childClaim.liveSequence());
    OutcomeImage claimedOutcome = failureOutcomeImage(failureManifest);
    assertEquals("CLAIMED", claimedOutcome.state());
    assertEquals(2L, claimedOutcome.stateVersion());
    assertEquals(childClaim.provenanceHash(), claimedOutcome.provenanceHash());

    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource());
    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.verified(writer, failureManifest);
    var child =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
            sequence14,
            failureManifest,
            Pack010GraphEvalCatalog.workerProfile(raceCase.repetition()),
            sequence14.childRun().startedAt(),
            GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED.name());
    AgentRun terminalChild =
        withCompletedAt(
            child.terminal(),
            sequence14.events().getLast().occurredAt().plusMillis(1));
    assertEquals(sequence14.childRun().startedAt(), terminalChild.startedAt());
    assertTrue(
        terminalChild.completedAt().isAfter(
            sequence14.events().getLast().occurredAt()));
    AgentRun succeededChild =
        withCompletedAt(
            Pack010GraphTerminalFixture.successfulChild(
                    sequence14,
                    failureManifest,
                    Pack010GraphEvalCatalog.workerProfile(
                        raceCase.repetition()),
                    sequence14.childRun().startedAt())
                .terminal(),
            sequence14.events().getLast().occurredAt().plusMillis(1));
    assertEquals(AgentRunLifecycle.SUCCEEDED, succeededChild.lifecycle());
    assertEquals(sequence14.childRun().runId(), succeededChild.runId());
    assertEquals(
        sequence14.childRun().principalId(), succeededChild.principalId());
    assertEquals(sequence14.childRun().task(), succeededChild.task());
    assertEquals(
        sequence14.childRun().startedAt(), succeededChild.startedAt());
    DatabaseDigest beforeWrongChildLifecycle =
        databaseDigest(adminDataSource());
    IllegalArgumentException wrongChildLifecycle =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resume.completeClaimedFailureChild(
                    failureManifest,
                    childClaim,
                    readerDataSource(),
                    succeededChild));
    assertEquals(
        "durable failure terminal child does not match the running graph",
        wrongChildLifecycle.getMessage());
    assertEquals(
        beforeWrongChildLifecycle, databaseDigest(adminDataSource()));
    DatabaseDigest beforeWrongChildStart = databaseDigest(adminDataSource());
    IllegalArgumentException wrongChildStart =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resume.completeClaimedFailureChild(
                    failureManifest,
                    childClaim,
                    readerDataSource(),
                    withStartedAt(
                        terminalChild,
                        terminalChild.startedAt().plusMillis(1))));
    assertEquals(
        "durable failure terminal child does not match the running graph",
        wrongChildStart.getMessage());
    assertEquals(beforeWrongChildStart, databaseDigest(adminDataSource()));
    var childConsumed =
        resume.completeClaimedFailureChild(
            failureManifest,
            childClaim,
            readerDataSource(),
            terminalChild);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CHILD_CONSUMED,
        childConsumed.state());
    assertEquals(3L, childConsumed.stateVersion());
    assertEquals(2L, childConsumed.childClaimVersion());
    assertEquals(15, childConsumed.childSequence());
    assertEquals(15, childConsumed.liveSequence());

    GraphAttemptSnapshot sequence15 =
        Pack010GraphTerminalFixture.verified(writer, failureManifest);
    var parent =
        Pack010GraphTerminalFixture.preCandidateFailureParent(
            sequence15,
            failureManifest,
            Pack010GraphEvalCatalog.workerProfile(raceCase.repetition()),
            Pack010GraphEvalCatalog.parentProfile(raceCase.repetition()),
            sequence15.parentRun().startedAt());
    AgentRun terminalParent =
        withCompletedAt(
            parent.terminal(),
            sequence15.events().getLast().occurredAt().plusMillis(1));
    assertEquals(sequence15.parentRun().startedAt(), terminalParent.startedAt());
    assertTrue(
        terminalParent.completedAt().isAfter(
            sequence15.events().getLast().occurredAt()));
    var parentClaim =
        resume.claim(
            failureManifest,
            childConsumed,
            "v13-failure-parent",
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-failure-parent-fence"),
            Duration.ofSeconds(20));
    assertEquals(
        PostgresAttributedFailureResumeStore.State.PARENT_CLAIMED,
        parentClaim.state());
    assertEquals(4L, parentClaim.stateVersion());
    assertEquals(4L, parentClaim.parentClaimVersion());
    assertEquals(15, parentClaim.liveSequence());

    AgentRun blockedParent =
        withCompletedAt(
            Pack010GraphTerminalFixture.preCandidateBlockedParent(
                    sequence15,
                    failureManifest,
                    Pack010GraphEvalCatalog.workerProfile(
                        raceCase.repetition()),
                    Pack010GraphEvalCatalog.parentProfile(
                        raceCase.repetition()),
                    sequence15.parentRun().startedAt())
                .terminal(),
            sequence15.events().getLast().occurredAt().plusMillis(1));
    assertEquals(AgentRunLifecycle.BLOCKED, blockedParent.lifecycle());
    assertEquals(sequence15.parentRun().runId(), blockedParent.runId());
    assertEquals(
        sequence15.parentRun().principalId(), blockedParent.principalId());
    assertEquals(sequence15.parentRun().task(), blockedParent.task());
    assertEquals(
        sequence15.parentRun().startedAt(), blockedParent.startedAt());
    DatabaseDigest beforeWrongParentLifecycle =
        databaseDigest(adminDataSource());
    IllegalArgumentException wrongParentLifecycle =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resume.completeClaimedFailureParentAndSeal(
                    failureManifest,
                    parentClaim,
                    readerDataSource(),
                    blockedParent));
    assertEquals(
        "durable failure terminal parent does not match the running graph",
        wrongParentLifecycle.getMessage());
    assertEquals(
        beforeWrongParentLifecycle, databaseDigest(adminDataSource()));
    DatabaseDigest beforeWrongParentStart = databaseDigest(adminDataSource());
    IllegalArgumentException wrongParentStart =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resume.completeClaimedFailureParentAndSeal(
                    failureManifest,
                    parentClaim,
                    readerDataSource(),
                    withStartedAt(
                        terminalParent,
                        terminalParent.startedAt().plusMillis(1))));
    assertEquals(
        "durable failure terminal parent does not match the running graph",
        wrongParentStart.getMessage());
    assertEquals(beforeWrongParentStart, databaseDigest(adminDataSource()));

    var terminalConsumed =
        resume.completeClaimedFailureParentAndSeal(
            failureManifest,
            parentClaim,
            readerDataSource(),
            terminalParent);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.TERMINAL_CONSUMED,
        terminalConsumed.state());
    assertEquals(5L, terminalConsumed.stateVersion());
    assertEquals(16, terminalConsumed.parentSequence());
    assertEquals(17, terminalConsumed.terminalSequence());
    assertEquals(17, terminalConsumed.liveSequence());

    GraphAttemptSnapshot sequence17 =
        Pack010GraphTerminalFixture.verified(writer, failureManifest);
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
    assertEquals(validationAtSequence14, validationImage(failureManifest));
    assertEquals(claimedOutcome, failureOutcomeImage(failureManifest));

    System.out.println(
        "PACK010_V13_PROVIDER_VALIDATION_PROCESS_RECEIPT"
            + " stageRollback=FULL_JSON_XMIN"
            + " preCommitRollback=FULL_JSON_XMIN"
            + " restart=IMMEDIATE"
            + " replay=FENCED"
            + " raceWinner=1 raceLoser=FENCED"
            + " failedOutcome=ATOMIC"
            + " failureTerminalResume="
            + "V13_CONSUMED14->V12_READY1->CLAIMED2->"
            + "CHILD3_HEAD15->PARENT4->TERMINAL5_HEAD17"
            + " wrongLifecycle=CHILD_SUCCEEDED,PARENT_BLOCKED_TYPED_REJECTED"
            + " wrongStartedAt=CHILD,PARENT_TYPED_REJECTED"
            + " realProvider=false externalProviderNetwork=0 "
            + "loopbackPostgresTcp=true billing=0");
  }

  private static AttemptCase prepareAttempt(int repetition) {
    DataSource admin = adminDataSource();
    DataSource writerDataSource = writerDataSource();
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, repetition);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        manifest,
        OwnerTtyGraphAuthority.Pack010Revision.values()[repetition - 1],
        Pack010GraphTerminalFixture.catalogIntent(repetition, 1),
        Instant.now().plus(Duration.ofMinutes(5)));
    PostgresProviderValidationAttestor.open(attestorDataSource())
        .requireValidation(
            manifest,
            sequence7.cursor(),
            KEY_ID,
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH,
            Duration.ofSeconds(20));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    return new AttemptCase(repetition, manifest, sequence13);
  }

  private static void assertAttempt(
      AttemptCase attempt, int sequence, String validationState) {
    GraphAttemptSnapshot snapshot =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(writerDataSource()),
            attempt.manifest());
    assertEquals(sequence, snapshot.cursor().lastSequence());
    assertEquals(
        validationState,
        JdbcClient.create(adminDataSource())
            .sql(
                """
                SELECT state
                FROM agent_graph_provider_validations
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", attempt.manifest().principalId())
            .param("attemptId", attempt.manifest().attemptId())
            .query(String.class)
            .single());
  }

  private Process start(
      String mode,
      String actor,
      AttemptCase attempt,
      Path coordination) throws IOException {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString(),
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dfile.encoding=UTF-8",
                "-cp",
                shippingJar() + File.pathSeparator + testClasses(),
                HARNESS_MAIN,
                mode,
                actor,
                Integer.toString(attempt.repetition()),
                attempt.sequence13().cursor().headHash(),
                POSTGRES.getJdbcUrl(),
                "emergeos_provider_attestor",
                shippingJar().toString(),
                testClasses().toString(),
                coordination.toString()));
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo().toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    Process process = builder.start();
    writeSecrets(process);
    return process;
  }

  private static void writeSecrets(Process process) throws IOException {
    byte[] password = ATTESTOR_PASSWORD.getBytes(StandardCharsets.UTF_8);
    byte[] privateKey = keyPair.getPrivate().getEncoded();
    try (DataOutputStream output =
        new DataOutputStream(process.getOutputStream())) {
      output.writeInt(password.length);
      output.write(password);
      output.writeInt(privateKey.length);
      output.write(privateKey);
    } finally {
      java.util.Arrays.fill(password, (byte) 0);
      java.util.Arrays.fill(privateKey, (byte) 0);
    }
  }

  private static ProcessResult await(Process process) throws Exception {
    try {
      assertTrue(
          process.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      String output =
          new String(
              process.getInputStream().readAllBytes(),
              StandardCharsets.UTF_8);
      assertSecretsAbsent(output);
      return new ProcessResult(process.exitValue(), output);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  private static void awaitFile(Process process, Path file)
      throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(file)) {
        return;
      }
      if (!process.isAlive()) {
        String output =
            new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertSecretsAbsent(output);
        throw new IllegalStateException(
            "V13_SUBPROCESS_EXITED_EARLY " + output);
      }
      Thread.sleep(25);
    }
    throw new IllegalStateException("V13_PROCESS_MARKER_TIMEOUT");
  }

  private static void assertSecretsAbsent(String output) {
    assertFalse(output.contains(ATTESTOR_PASSWORD), "ATTESTOR_SECRET_OUTPUT");
    assertFalse(output.contains(WRITER_PASSWORD), "WRITER_SECRET_OUTPUT");
    assertFalse(output.contains(RESUMER_PASSWORD), "RESUMER_SECRET_OUTPUT");
    assertFalse(output.contains(READER_PASSWORD), "READER_SECRET_OUTPUT");
    assertFalse(
        output.contains(POSTGRES.getPassword()), "ADMIN_SECRET_OUTPUT");
    byte[] privateKey = keyPair.getPrivate().getEncoded();
    try {
      assertFalse(
          output.contains(Base64.getEncoder().encodeToString(privateKey)),
          "PRIVATE_KEY_BASE64_OUTPUT");
      assertFalse(
          output.contains(HexFormat.of().formatHex(privateKey)),
          "PRIVATE_KEY_HEX_OUTPUT");
    } finally {
      java.util.Arrays.fill(privateKey, (byte) 0);
    }
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
    throw new IllegalStateException("V13_BACKEND_REMAINED_ACTIVE");
  }

  private static void restartPostgres(DataSource probe) throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart -D \"$PGDATA\" -m immediate -w");
    assertEquals(0, restart.getExitCode(), restart.getStderr());
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try (var ignored = probe.getConnection()) {
        return;
      } catch (SQLException unavailable) {
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("V13_POSTGRES_RESTART_TIMEOUT");
  }

  private static Instant postmasterStartTime(DataSource source) {
    return JdbcClient.create(source)
        .sql("SELECT pg_catalog.pg_postmaster_start_time()")
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private Path directory(String name) throws IOException {
    return Files.createDirectory(temporary.resolve(name));
  }

  private static String field(String receipt, String name) {
    String prefix = name + "=";
    for (String token : receipt.split(" ")) {
      if (token.startsWith(prefix)) {
        return token.substring(prefix.length());
      }
    }
    throw new IllegalArgumentException("V13_RECEIPT_FIELD_MISSING");
  }

  private static DatabaseDigest databaseDigest(DataSource source) {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_REPEATABLE_READ);
      JdbcClient jdbc =
          JdbcClient.create(
              new SingleConnectionDataSource(connection, true));
      List<String> tables =
          jdbc.sql(
                  """
                  SELECT relation.relname
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND relation.relkind IN ('r', 'p')
                  ORDER BY relation.relname
                  """)
              .query(String.class)
              .list();
      long rows = 0;
      StringBuilder framed = new StringBuilder();
      for (String table : tables) {
        if (!table.matches("[a-z][a-z0-9_]{0,62}")) {
          throw new IllegalStateException("PUBLIC_TABLE_NAME_INVALID");
        }
        RowDigest digest =
            jdbc.sql(
                    """
                    SELECT count(*) AS row_count,
                           pg_catalog.encode(pg_catalog.sha256(
                             pg_catalog.convert_to(COALESCE(
                               pg_catalog.string_agg(
                                 image.row_image, E'\\n'
                                 ORDER BY image.row_image), ''),
                               'UTF8')), 'hex') AS row_digest
                    FROM (
                      SELECT pg_catalog.to_jsonb(row_data)::text
                               || E'\\x1f' || row_data.xmin::text
                               AS row_image
                      FROM public."%s" row_data
                    ) image
                    """.formatted(table))
                .query(
                    (row, ignored) ->
                        new RowDigest(
                            row.getLong("row_count"),
                            row.getString("row_digest")))
                .single();
        rows += digest.rowCount();
        framed.append(table).append('|')
            .append(digest.rowCount()).append('|')
            .append(digest.digest()).append('\n');
      }
      connection.rollback();
      return new DatabaseDigest(
          tables.size(), rows,
          IntegrityHashes.utf8ContentHash(framed.toString()));
    } catch (SQLException failure) {
      throw new IllegalStateException("V13_DATABASE_DIGEST_FAILED");
    }
  }

  private static AgentRun withCompletedAt(
      AgentRun source, Instant completedAt) {
    return new AgentRun(
        source.runId(),
        source.principalId(),
        source.task(),
        source.lifecycle(),
        source.result(),
        source.trace(),
        source.bundle(),
        source.startedAt(),
        completedAt);
  }

  private static AgentRun withStartedAt(
      AgentRun source, Instant startedAt) {
    return new AgentRun(
        source.runId(),
        source.principalId(),
        source.task(),
        source.lifecycle(),
        source.result(),
        source.trace(),
        source.bundle(),
        startedAt,
        source.completedAt());
  }

  private static ValidationImage validationImage(
      GraphAttemptManifest manifest) {
    return JdbcClient.create(adminDataSource())
        .sql(
            """
            SELECT validation.state,
                   validation.decision_kind,
                   validation.cursor_sequence,
                   validation.failure_provenance_hash,
                   pg_catalog.encode(pg_catalog.sha256(
                     pg_catalog.convert_to(
                       pg_catalog.to_jsonb(validation)::text
                         || pg_catalog.chr(31)
                         || validation.xmin::text,
                       'UTF8')), 'hex') AS row_digest
            FROM agent_graph_provider_validations validation
            WHERE validation.principal_id = :principalId
              AND validation.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (row, ignored) ->
                new ValidationImage(
                    row.getString("state"),
                    row.getString("decision_kind"),
                    row.getInt("cursor_sequence"),
                    row.getString("failure_provenance_hash"),
                    row.getString("row_digest")))
        .single();
  }

  private static OutcomeImage failureOutcomeImage(
      GraphAttemptManifest manifest) {
    return JdbcClient.create(adminDataSource())
        .sql(
            """
            SELECT outcome.state,
                   outcome.state_version,
                   outcome.provenance_hash,
                   pg_catalog.encode(pg_catalog.sha256(
                     pg_catalog.convert_to(
                       pg_catalog.to_jsonb(outcome)::text
                         || pg_catalog.chr(31)
                         || outcome.xmin::text,
                       'UTF8')), 'hex') AS row_digest
            FROM agent_graph_attributed_failure_outcomes outcome
            WHERE outcome.principal_id = :principalId
              AND outcome.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (row, ignored) ->
                new OutcomeImage(
                    row.getString("state"),
                    row.getLong("state_version"),
                    row.getString("provenance_hash"),
                    row.getString("row_digest")))
        .single();
  }

  private static DataSource adminDataSource() {
    return dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DataSource writerDataSource() {
    return dataSource("emergeos_graph_prefix_writer", WRITER_PASSWORD);
  }

  private static DataSource attestorDataSource() {
    return dataSource("emergeos_provider_attestor", ATTESTOR_PASSWORD);
  }

  private static DataSource resumerDataSource() {
    return dataSource("emergeos_failure_resumer", RESUMER_PASSWORD);
  }

  private static DataSource readerDataSource() {
    return dataSource("emergeos_graph_reader", READER_PASSWORD);
  }

  private static DataSource dataSource(String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void provisionProductionRoles(DataSource admin)
      throws Exception {
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource("db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(
        admin, "emergeos_graph_prefix_writer", WRITER_PASSWORD);
    setEphemeralPassword(
        admin, "emergeos_provider_attestor", ATTESTOR_PASSWORD);
    setEphemeralPassword(
        admin, "emergeos_failure_resumer", RESUMER_PASSWORD);
    setEphemeralPassword(
        admin, "emergeos_graph_reader", READER_PASSWORD);
  }

  private static void execute(DataSource source, String sql)
      throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(sql);
      connection.commit();
    }
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!("emergeos_graph_prefix_writer".equals(role)
        || "emergeos_provider_attestor".equals(role)
        || "emergeos_failure_resumer".equals(role)
        || "emergeos_graph_reader".equals(role))) {
      throw new IllegalArgumentException("ROLE_NOT_ALLOWLISTED");
    }
    try (Connection connection = admin.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement setting = connection.prepareStatement(
              "SELECT pg_catalog.set_config("
                  + "'emergeos.pack010_ephemeral_password', ?, true)");
          Statement statement = connection.createStatement()) {
        setting.setString(1, password);
        setting.execute();
        statement.execute(
            """
            DO $password$
            DECLARE
              ephemeral_password TEXT := pg_catalog.current_setting(
                'emergeos.pack010_ephemeral_password', false);
            BEGIN
              EXECUTE pg_catalog.format(
                'ALTER ROLE %%I PASSWORD %%L', '%s', ephemeral_password);
            END;
            $password$;
            """.formatted(role));
        connection.commit();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
    }
  }

  private static String resource(String name) {
    try (InputStream input =
        Pack010ProviderValidationAttestationProcessIT.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("V13_RESOURCE_MISSING");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("V13_RESOURCE_READ_FAILED");
    }
  }

  private static Path shippingJar() {
    return Path.of(System.getProperty("emerge.graph.it.jar"))
        .toAbsolutePath().normalize();
  }

  private static Path testClasses() {
    return Path.of(System.getProperty("emerge.graph.it.testClasses"))
        .toAbsolutePath().normalize();
  }

  private static Path repo() {
    return Path.of(System.getProperty("emerge.graph.it.repo"))
        .toAbsolutePath().normalize();
  }

  private record AttemptCase(
      int repetition,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13) {}

  private record ProcessResult(int exitCode, String output) {}

  private record RowDigest(long rowCount, String digest) {}

  private record DatabaseDigest(
      int tableCount, long rowCount, String digest) {}

  private record ValidationImage(
      String state,
      String decisionKind,
      int cursorSequence,
      String failureProvenanceHash,
      String rowDigest) {}

  private record OutcomeImage(
      String state,
      long stateVersion,
      String provenanceHash,
      String rowDigest) {}
}
