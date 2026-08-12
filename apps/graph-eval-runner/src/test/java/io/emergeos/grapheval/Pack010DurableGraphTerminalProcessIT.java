package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010DurableGraphTerminalProcessIT {

  private static final Duration READY_TIMEOUT =
      Duration.ofSeconds(30);
  private static final Duration EXIT_TIMEOUT =
      Duration.ofSeconds(30);
  private static final String HARNESS_MAIN =
      "io.emergeos.grapheval.Pack010GraphTerminalHarnessMain";
  private static final String VERIFIER_MAIN =
      "io.emergeos.grapheval.Pack010GraphTerminalVerifierMain";
  private static final String SUCCESSOR_MAIN =
      "io.emergeos.grapheval.Pack010SuccessorClaimHarnessMain";
  private static final String BRIDGE_CLASS =
      "io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge";
  private static final String READER_ROLE =
      "pack010_graph_reader";
  private static final String READER_PASSWORD =
      "synthetic-" + UUID.randomUUID();
  private static final String DATABASE_PASSWORD =
      "synthetic-" + UUID.randomUUID();
  private static final List<String> READER_TABLES =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_attributed_failure_outcomes",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");
  private static final List<String> PUBLIC_TABLES =
      List.of(
          "action_attempt_transitions",
          "action_attempts",
          "action_receipts",
          "agent_graph_attempt_candidates",
          "agent_graph_attempt_events",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_seals",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempts",
          "agent_graph_attributed_failure_outcomes",
          "agent_graph_attributed_failure_terminal_resumes",
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_provider_validations_v16",
          "agent_graph_exact_tx_a_requirements_v15",
          "agent_graph_provider_profiles_v14",
          "agent_graph_provider_session_intents",
          "agent_graph_provider_validation_keys",
          "agent_graph_provider_validations",
          "agent_run_resource_bindings",
          "agent_runs",
          "agent_trace_events",
          "agent_worker_results",
          "artifact_versions",
          "artifacts",
          "captures",
          "flyway_schema_history");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_eval")
          .withUsername("emerge")
          .withPassword(DATABASE_PASSWORD);

  @TempDir Path tempDir;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    createRestrictedReaderRole();
  }

  @Test
  void everyTerminalStatementRollsBackAfterRealProcessKillAndRecovers()
      throws Exception {
    int crashCases = 0;
    for (String tx : List.of("TX_A", "TX_B", "TX_C")) {
      int ordinal = 0;
      for (String probe :
          Pack010GraphTerminalStoreBridge.probes(tx).stream()
              // The typed-failure-only V11 insert has its own hard-kill,
              // rollback, two-JVM and restart process Acceptance.
              .filter(
                  candidate ->
                      !candidate.equals(
                          "AFTER_ATTRIBUTED_FAILURE_OUTCOME_INSERT"))
              .toList()) {
        ordinal++;
        crashCases++;
        runCrashCase(tx, probe, ordinal);
      }
    }
    assertEquals(37, crashCases);
    System.out.println(
        "PACK010_PROCESS_KILL_MATRIX_RECEIPT"
            + " transactions=3 probes=37"
            + " externalVisibility=OLD_PREFIX_ONLY"
            + " recovery=FRESH_JVM"
            + " freshVerifiersPerProbe=2"
            + " modelCalls=0 providerCredentialFrames=0"
            + " providerNetworkCalls=0 synthetic=true");
  }

  @Test
  void twoIndependentJvmWritersHaveExactlyOneTerminalCasWinner()
      throws Exception {
    runRace("TX_A");
    runRace("TX_B");
    runRace("TX_C");
    System.out.println(
        "PACK010_TWO_JVM_RACE_RECEIPT"
            + " transactions=TX_A,TX_B,TX_C"
            + " writersPerRace=2"
            + " result=ONE_ADVANCED_ONE_CONFLICT"
            + " staleWriter=CONFLICT"
            + " freshVerifiersPerRace=2"
            + " modelCalls=0 providerNetworkCalls=0"
            + " synthetic=true");
  }

  @Test
  void packagedSuccessorClaimRollsBackAfterKillAndTwoJvmsHaveOneWinner()
      throws Exception {
    resetDatabase();
    String caseKey = "successor-r2";
    Path coordination = tempDir.resolve(caseKey);
    Files.createDirectory(coordination);
    ProcessResult sealed =
        runToCompletion(
            successorHarnessCommand(
                "seed", caseKey, "X", coordination),
            processFiles(caseKey + "-seal-r1"),
            POSTGRES.getPassword());
    assertEquals(0, sealed.exitCode(), sealed::safeText);
    DatabaseSnapshot predecessor = databaseSnapshot();
    GraphAttemptSnapshot verifiedPredecessor =
        restrictedCatalogSnapshot(1);
    assertEquals(17, verifiedPredecessor.cursor().lastSequence());

    ProcessFiles crashFiles = processFiles(caseKey + "-crash");
    Process crashing =
        start(
            successorHarnessCommand(
                "crash", caseKey, "X", coordination),
            crashFiles);
    try {
      writeOnlySecret(crashing, POSTGRES.getPassword());
      Path marker = coordination.resolve(caseKey + ".probe");
      awaitProcessFile(crashing, marker, crashFiles);
      assertEquals(
          "PACK010_SUCCESSOR_PROBE_REACHED",
          receiptKind(Pack009ProcessSupport.readBounded(marker)));
      assertEquals(
          "AFTER_HEAD_UPDATE",
          field(
              Pack009ProcessSupport.readBounded(marker),
              "boundary"));
      awaitProcessOutput(
          crashing,
          crashFiles,
          "PACK010_SUCCESSOR_PROBE_ACK");
      assertEquals(predecessor, databaseSnapshot());
      forceDestroyAndAwait(crashing, crashFiles);
      assertFalse(crashing.isAlive());
      assertNotEquals(0, crashing.exitValue());
      assertEquals(predecessor, databaseSnapshot());
    } finally {
      cleanupProcess(crashing, crashFiles);
    }

    ProcessFiles firstFiles = processFiles(caseKey + "-A");
    ProcessFiles secondFiles = processFiles(caseKey + "-B");
    Process first = null;
    Process second = null;
    try {
      first =
          start(
              successorHarnessCommand(
                  "race", caseKey, "A", coordination),
              firstFiles);
      second =
          start(
              successorHarnessCommand(
                  "race", caseKey, "B", coordination),
              secondFiles);
      writeOnlySecret(first, POSTGRES.getPassword());
      writeOnlySecret(second, POSTGRES.getPassword());
      awaitProcessFile(
          first,
          coordination.resolve(caseKey + "-A.ready"),
          firstFiles);
      awaitProcessFile(
          second,
          coordination.resolve(caseKey + "-B.ready"),
          secondFiles);
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(caseKey + ".go"),
          "PACK010_SUCCESSOR_GO revision=R2");
      awaitExit(first, firstFiles);
      awaitExit(second, secondFiles);
      assertEquals(
          Set.of(0, 10),
          Set.of(first.exitValue(), second.exitValue()));
      assertEquals(
          Set.of("CLAIMED", "CONFLICT"),
          Set.of(
              field(stdout(firstFiles), "outcome"),
              field(stdout(secondFiles), "outcome")));
      assertSuccessorClaimedExactlyOnce();
      assertEquals(
          verifiedPredecessor,
          restrictedCatalogSnapshot(1));
      System.out.println(
          "PACK010_PACKAGED_SUCCESSOR_RECEIPT"
              + " predecessor=R1_SEQUENCE_17"
              + " killedBoundary=AFTER_HEAD_UPDATE"
              + " rollback=R2_UNCOMMITTED_CLAIM_MISSING"
              + " freshJvms=2 winner=1 conflict=1"
              + " ambientEnvironment=EMPTY"
              + " modelCalls=0 providerNetworkCalls=0"
              + " synthetic=true");
    } finally {
      try {
        cleanupProcess(first, firstFiles);
      } finally {
        cleanupProcess(second, secondFiles);
      }
    }
  }

  @Test
  void shippingJarContainsVerifierButNoHarnessOrCliRoute()
      throws Exception {
    assertPackagingBoundary();
    ProcessResult rejected =
        runToCompletion(
            shippingCommand("--pack010-tx-b"),
            processFiles("shipping-pack010-rejection"),
            null);
    assertEquals(2, rejected.exitCode());
    assertEquals("", rejected.stdoutText());
    assertEquals(
        "GRAPH_EVAL_REJECTED reason=ARGUMENTS_INVALID",
        rejected.stderrText());
  }

  private void runCrashCase(
      String tx, String probe, int ordinal) throws Exception {
    resetDatabase();
    String caseKey =
        "crash-"
            + tx.toLowerCase().replace("_", "")
            + "-"
            + String.format("%02d", ordinal);
    Path coordination = tempDir.resolve(caseKey);
    Files.createDirectory(coordination);
    ProcessResult prepared =
        runToCompletion(
            harnessCommand(
                prepareMode(tx), caseKey, "NONE", coordination),
            processFiles(caseKey + "-prepare"),
            POSTGRES.getPassword());
    assertEquals(0, prepared.exitCode());
    assertTrue(
        prepared.stdoutText().startsWith(
            "PACK010_PREPARED tx=" + tx + " sequence="));
    Checkpoint before = beforeCheckpoint(tx);
    Checkpoint after = afterCheckpoint(tx);
    DatabaseSnapshot baseline = databaseSnapshot();

    ProcessFiles writerFiles =
        processFiles(caseKey + "-writer");
    Process writer =
        start(
            harnessCommand(
                writeMode(tx), caseKey, probe, coordination),
            writerFiles);
    try {
      try (DataOutputStream input =
          new DataOutputStream(writer.getOutputStream())) {
        writeSecretFrame(input, POSTGRES.getPassword());
      }
      Path marker = coordination.resolve(caseKey + ".probe");
      awaitProcessFile(writer, marker, writerFiles);
      String markerText =
          Pack009ProcessSupport.readBounded(marker);
      assertEquals(
          "PACK010_PROBE_REACHED", markerText.split(" ")[0]);
      assertEquals(tx, field(markerText, "tx"));
      assertEquals(probe, field(markerText, "probe"));
      assertEquals(
          Long.toString(writer.pid()), field(markerText, "pid"));
      assertEquals(
          Integer.toString(before.sequence),
          field(markerText, "expectedSequence"));
      awaitProcessOutput(
          writer,
          writerFiles,
          "PACK010_PROBE_ACK tx="
              + tx
              + " probe="
              + probe
              + " pid="
              + writer.pid());
      assertEquals(baseline, databaseSnapshot());
      assertTrue(writer.isAlive());
      forceDestroyAndAwait(writer, writerFiles);
      assertFalse(writer.isAlive());
      assertNotEquals(0, writer.exitValue());
      DatabaseSnapshot afterKill = databaseSnapshot();
      assertEquals(baseline, afterKill);

      ProcessResult verifierOne =
          runVerifier(
              before,
              caseKey + "-verifier-one");
      ProcessResult verifierTwo =
          runVerifier(
              before,
              caseKey + "-verifier-two");
      assertEquals(0, verifierOne.exitCode());
      assertEquals(0, verifierTwo.exitCode());
      assertNotEquals(verifierOne.pid(), verifierTwo.pid());
      assertArrayEquals(
          verifierOne.stdout(), verifierTwo.stdout());
      assertEquals(afterKill, databaseSnapshot());

      ProcessResult recovery =
          runToCompletion(
              harnessCommand(
                  writeMode(tx),
                  caseKey + "-recovery",
                  "NONE",
                  coordination),
              processFiles(caseKey + "-recovery"),
              POSTGRES.getPassword());
      assertEquals(
          0,
          recovery.exitCode(),
          () -> recovery.safeText());
      assertTrue(
          recovery.stdoutText().contains(
              "outcome=ADVANCED sequence=" + after.sequence));
      ProcessResult recoveredVerifier =
          runVerifier(after, caseKey + "-recovered-verifier");
      assertEquals(0, recoveredVerifier.exitCode());
    } finally {
      cleanupProcess(writer, writerFiles);
    }
  }

  private void runRace(String tx) throws Exception {
    resetDatabase();
    String caseKey = "race-" + tx.toLowerCase().replace("_", "");
    Path coordination = tempDir.resolve(caseKey);
    Files.createDirectory(coordination);
    ProcessResult prepared =
        runToCompletion(
            harnessCommand(
                prepareMode(tx), caseKey, "NONE", coordination),
            processFiles(caseKey + "-prepare"),
            POSTGRES.getPassword());
    assertEquals(0, prepared.exitCode());
    Checkpoint before = beforeCheckpoint(tx);
    Checkpoint after = afterCheckpoint(tx);

    ProcessFiles firstFiles = processFiles(caseKey + "-A");
    ProcessFiles secondFiles = processFiles(caseKey + "-B");
    Process first = null;
    Process second = null;
    try {
      first =
          start(
              harnessCommand(
                  raceMode(tx), caseKey, "A", coordination),
              firstFiles);
      second =
          start(
              harnessCommand(
                  raceMode(tx), caseKey, "B", coordination),
              secondFiles);
      writeOnlySecret(first, POSTGRES.getPassword());
      writeOnlySecret(second, POSTGRES.getPassword());
      Path firstReady = coordination.resolve(caseKey + "-A.ready");
      Path secondReady = coordination.resolve(caseKey + "-B.ready");
      awaitProcessFile(first, firstReady, firstFiles);
      awaitProcessFile(second, secondReady, secondFiles);
      String firstReceipt =
          Pack009ProcessSupport.readBounded(firstReady);
      String secondReceipt =
          Pack009ProcessSupport.readBounded(secondReady);
      assertEquals(
          "PACK010_RACE_READY", receiptKind(firstReceipt));
      assertEquals(
          "PACK010_RACE_READY", receiptKind(secondReceipt));
      assertEquals(tx, field(firstReceipt, "tx"));
      assertEquals(tx, field(secondReceipt, "tx"));
      assertEquals("A", field(firstReceipt, "actor"));
      assertEquals("B", field(secondReceipt, "actor"));
      assertEquals(
          Long.toString(first.pid()), field(firstReceipt, "pid"));
      assertEquals(
          Long.toString(second.pid()), field(secondReceipt, "pid"));
      assertEquals(
          Integer.toString(before.sequence),
          field(firstReceipt, "expectedSequence"));
      assertEquals(
          field(firstReceipt, "expectedSequence"),
          field(secondReceipt, "expectedSequence"));
      assertEquals(
          field(firstReceipt, "attemptId"),
          field(secondReceipt, "attemptId"));
      assertEquals(
          field(firstReceipt, "expectedHeadHash"),
          field(secondReceipt, "expectedHeadHash"));
      assertEquals(
          field(firstReceipt, "completionFingerprint"),
          field(secondReceipt, "completionFingerprint"));
      assertNotEquals(
          field(firstReceipt, "pid"),
          field(secondReceipt, "pid"));
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(caseKey + ".go"),
          "PACK010_RACE_GO tx=" + tx);
      awaitExit(first, firstFiles);
      awaitExit(second, secondFiles);
      assertEquals(Set.of(0, 10), Set.of(first.exitValue(), second.exitValue()));
      String firstResult =
          Pack009ProcessSupport.readBounded(
              coordination.resolve(caseKey + "-A.result"));
      String secondResult =
          Pack009ProcessSupport.readBounded(
              coordination.resolve(caseKey + "-B.result"));
      assertEquals(
          Set.of("ADVANCED", "CONFLICT"),
          Set.of(
              field(firstResult, "outcome"),
              field(secondResult, "outcome")));
      assertRaceResult(
          firstResult,
          tx,
          "A",
          first.pid(),
          first.exitValue(),
          before,
          after);
      assertRaceResult(
          secondResult,
          tx,
          "B",
          second.pid(),
          second.exitValue(),
          before,
          after);

      DatabaseSnapshot winnerSnapshot = databaseSnapshot();
      ProcessResult verifierOne =
          runVerifier(after, caseKey + "-verifier-one");
      ProcessResult verifierTwo =
          runVerifier(after, caseKey + "-verifier-two");
      assertEquals(0, verifierOne.exitCode());
      assertEquals(0, verifierTwo.exitCode());
      assertNotEquals(verifierOne.pid(), verifierTwo.pid());
      assertArrayEquals(
          verifierOne.stdout(), verifierTwo.stdout());
      assertEquals(winnerSnapshot, databaseSnapshot());

      ProcessResult stale =
          runToCompletion(
              harnessCommand(
                  raceMode(tx), caseKey, "C", coordination),
              processFiles(caseKey + "-stale-C"),
              POSTGRES.getPassword());
      assertEquals(10, stale.exitCode());
      assertEquals("CONFLICT", field(stale.stdoutText(), "outcome"));
      String staleResult =
          Pack009ProcessSupport.readBounded(
              coordination.resolve(caseKey + "-C.result"));
      assertEquals(stale.stdoutText(), staleResult);
      assertRaceResult(
          staleResult,
          tx,
          "C",
          stale.pid(),
          stale.exitCode(),
          before,
          after);
      assertEquals(winnerSnapshot, databaseSnapshot());
    } finally {
      try {
        cleanupProcess(first, firstFiles);
      } finally {
        cleanupProcess(second, secondFiles);
      }
    }
  }

  private ProcessResult runVerifier(
      Checkpoint checkpoint, String name) throws Exception {
    return runToCompletion(
        verifierCommand(checkpoint),
        processFiles(name),
        READER_PASSWORD);
  }

  private void assertPackagingBoundary() throws Exception {
    List<String> testResources;
    try (Stream<Path> paths = Files.walk(testClasses())) {
      testResources =
          paths.filter(Files::isRegularFile)
              .map(testClasses()::relativize)
              .map(Path::toString)
              .map(name -> name.replace('\\', '/'))
              .filter(name -> name.endsWith(".class"))
              .sorted()
              .toList();
    }
    for (String expected :
        List.of(
            HARNESS_MAIN,
            VERIFIER_MAIN,
            SUCCESSOR_MAIN,
            BRIDGE_CLASS)) {
      assertTrue(testResources.contains(classResource(expected)), expected);
    }
    try (JarFile jar = new JarFile(shippingJar().toFile())) {
      for (String resource : testResources) {
        assertNull(jar.getEntry(resource), resource);
      }
      assertTrue(
          jar.getEntry(
                  classResource(
                      Pack010GraphTerminalVerifier.class
                          .getName()))
              != null,
          "production Pack010 verifier is absent");
      for (String productionResource :
          List.of(
              "io/emergeos/adapters/postgres/"
                  + "PostgresProviderValidationAttestor.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$AuthorityProbe.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$Probe.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$ProbePoint.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$RuntimeIdentity.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$Stage.class",
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoProviderValidationAttestor$LocalFailure.class",
              "io/emergeos/core/domain/"
                  + "GraphExactPicoProviderSignatureVerifier.class",
              "io/emergeos/core/domain/"
                  + "GraphExactPicoProviderValidationChallenge.class",
              "io/emergeos/core/domain/"
                  + "GraphExactPicoProviderValidationCommand.class",
              "io/emergeos/core/domain/"
                  + "GraphExactPicoProviderValidationReceipt.class",
              "io/emergeos/core/domain/"
                  + "GraphExactPicoProviderValidationReceipt$ValidationState.class",
              "io/emergeos/core/port/"
                  + "GraphExactPicoProviderValidationAttestor.class",
              "io/emergeos/core/port/"
                  + "GraphExactPicoProviderValidationSigner.class",
              "io/emergeos/grapheval/"
                  + "Pack010ProviderCredentialBroker.class",
              "io/emergeos/grapheval/"
                  + "Pack010ProviderSessionComposer.class",
              "db/migration/"
                  + "V13__bounded_provider_validation_attestation.sql",
              "db/migration/"
                  + "V14__exact_provider_profile_authority.sql",
              "db/migration/"
                  + "V15__require_exact_provider_tx_a.sql",
              "db/migration/"
                  + "V16__exact_pico_provider_tx_a_overlay.sql",
              "db/provisioning/pack010_runtime_roles.sql",
              "db/provisioning/pack010_runtime_roles_check.sql")) {
        assertTrue(
            jar.getEntry(productionResource) != null,
            productionResource + " is absent");
      }
      for (String canonicalizedCapability :
          List.of(
              "io/emergeos/grapheval/"
                  + "Pack010ProviderCredentialBroker.class",
              "io/emergeos/grapheval/"
                  + "Pack010ProviderSessionComposer.class")) {
        byte[] target =
            Files.readAllBytes(
                repo()
                    .resolve("apps/graph-eval-runner/target/classes")
                    .resolve(canonicalizedCapability));
        try (var input =
            jar.getInputStream(jar.getJarEntry(canonicalizedCapability))) {
          assertArrayEquals(
              target,
              input.readAllBytes(),
              canonicalizedCapability + " target/app parity");
        }
      }
    }
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.shippingJarViolations(
            shippingJar()));
  }

  private static void resetDatabase() {
    JdbcClient.create(dataSource())
        .sql(
            """
            TRUNCATE TABLE
              agent_graph_exact_attempt_heads_v16,
              agent_graph_exact_attempt_events_v16,
              agent_graph_exact_provider_attributions_v16,
              agent_graph_exact_provider_validations_v16,
              agent_graph_exact_tx_a_requirements_v15,
              agent_graph_provider_validations,
              agent_graph_provider_validation_keys,
              agent_graph_attributed_failure_terminal_resumes,
              agent_graph_attributed_failure_outcomes,
              action_attempt_transitions,
              action_receipts,
              action_attempts,
              agent_graph_attempt_terminal_bindings,
              agent_graph_attempt_candidates,
              agent_graph_attempt_provider_attributions,
              agent_graph_provider_session_intents,
              agent_graph_attempt_seals,
              agent_graph_attempt_heads,
              agent_graph_attempt_events,
              agent_trace_events,
              agent_run_resource_bindings,
              agent_worker_results,
              agent_runs,
              agent_graph_attempt_run_bindings,
              agent_graph_attempts,
              artifact_versions,
              artifacts,
              captures
            """)
        .update();
  }

  private static void assertSuccessorClaimedExactlyOnce()
      throws SQLException {
    GraphAttemptManifest successor =
        Pack010GraphEvalCatalog.manifests().get(1);
    try (Connection connection = dataSource().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT h.last_sequence,
                       h.phase,
                       (SELECT count(*)
                          FROM agent_graph_attempt_events e
                         WHERE e.principal_id = h.principal_id
                           AND e.attempt_id = h.attempt_id),
                       (SELECT count(*)
                          FROM agent_graph_attempt_run_bindings b
                         WHERE b.principal_id = h.principal_id
                           AND b.attempt_id = h.attempt_id)
                  FROM agent_graph_attempt_heads h
                 WHERE h.principal_id = ?
                   AND h.attempt_id = ?
                """)) {
      statement.setString(1, successor.principalId());
      statement.setString(2, successor.attemptId());
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next(), "successor head is absent");
        assertEquals(1, rows.getInt(1));
        assertEquals("MARKED", rows.getString(2));
        assertEquals(1L, rows.getLong(3));
        assertEquals(2L, rows.getLong(4));
        assertFalse(rows.next(), "successor head is duplicated");
      }
    }
  }

  private static GraphAttemptSnapshot restrictedCatalogSnapshot(
      int repetition) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptVerification.Valid valid =
        assertInstanceOf(
            GraphAttemptVerification.Valid.class,
            PostgresGraphAttemptAccess
                .openReader(
                    dataSource(READER_ROLE, READER_PASSWORD))
                .findVerified(manifest));
    return valid.snapshot();
  }

  private static void createRestrictedReaderRole() {
    JdbcClient admin = JdbcClient.create(dataSource());
    admin.sql(
            "CREATE ROLE "
                + READER_ROLE
                + " LOGIN PASSWORD '"
                + READER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
    admin.sql(
            "GRANT CONNECT ON DATABASE "
                + POSTGRES.getDatabaseName()
                + " TO "
                + READER_ROLE)
        .update();
    admin.sql(
            "GRANT USAGE ON SCHEMA public TO "
                + READER_ROLE)
        .update();
    admin.sql(
            "GRANT SELECT ON TABLE "
                + String.join(", ", READER_TABLES)
                + " TO "
                + READER_ROLE)
        .update();
    admin.sql(
            "ALTER ROLE "
                + READER_ROLE
                + " IN DATABASE "
                + POSTGRES.getDatabaseName()
                + " SET default_transaction_read_only = on")
        .update();
  }

  private static DatabaseSnapshot databaseSnapshot()
      throws Exception {
    try (Connection connection = readConnection()) {
      List<String> observed = new ArrayList<>();
      try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT table_name
                  FROM information_schema.tables
                  WHERE table_schema = 'public'
                    AND table_type = 'BASE TABLE'
                  ORDER BY table_name
                  """);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          observed.add(rows.getString(1));
        }
      }
      assertEquals(PUBLIC_TABLES, observed);
      StringBuilder value = new StringBuilder();
      for (String table : observed) {
        if (!table.matches("[a-z][a-z0-9_]*")) {
          throw new IllegalStateException(
              "unsafe snapshot table");
        }
        value.append(table.length())
            .append(':')
            .append(table)
            .append('\n');
        try (Statement statement = connection.createStatement();
            ResultSet rows =
                statement.executeQuery(
                    "SELECT row_to_json(t)::text AS row_json,"
                        + " t.xmin::text AS row_xmin"
                        + " FROM public.\""
                        + table
                        + "\" AS t"
                        + " ORDER BY row_json, row_xmin")) {
          while (rows.next()) {
            String row = rows.getString("row_json");
            value.append(
                    row.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(row)
                .append('|')
                .append(rows.getString("row_xmin"))
                .append('\n');
          }
        }
      }
      connection.rollback();
      return new DatabaseSnapshot(
          value.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private ProcessResult runToCompletion(
      List<String> command,
      ProcessFiles files,
      String databasePassword) throws Exception {
    Process process = start(command, files);
    try {
      if (databasePassword == null) {
        process.getOutputStream().close();
      } else {
        writeOnlySecret(process, databasePassword);
      }
      boolean finished =
          process.waitFor(
              EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        forceDestroyAndAwait(process, files);
      }
      assertTrue(
          finished,
          () -> "process timed out; " + safeDiagnostics(files));
      return new ProcessResult(
          process.pid(),
          process.exitValue(),
          readBytes(files.stdout()),
          readBytes(files.stderr()));
    } finally {
      cleanupProcess(process, files);
    }
  }

  private Process start(
      List<String> command, ProcessFiles files)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo().toFile());
    builder.redirectOutput(files.stdout().toFile());
    builder.redirectError(files.stderr().toFile());
    builder.environment().clear();
    return builder.start();
  }

  private void awaitProcessFile(
      Process process, Path file, ProcessFiles files)
      throws Exception {
    long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline
        && process.isAlive()
        && !Files.isRegularFile(file)) {
      Thread.sleep(20);
    }
    if (Files.isRegularFile(file)) {
      return;
    }
    if (process.isAlive()) {
      forceDestroyAndAwait(process, files);
      fail(
          "process timed out before durable evidence; "
              + safeDiagnostics(files));
    }
    fail(
        "process exited before durable evidence; exit="
            + process.exitValue()
            + " "
            + safeDiagnostics(files));
  }

  private void awaitExit(Process process, ProcessFiles files)
      throws Exception {
    boolean finished =
        process.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      forceDestroyAndAwait(process, files);
    }
    assertTrue(
        finished,
        () -> "process timed out; " + safeDiagnostics(files));
  }

  private void awaitProcessOutput(
      Process process,
      ProcessFiles files,
      String expected) throws Exception {
    long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline && process.isAlive()) {
      if (stdout(files).contains(expected)) {
        return;
      }
      Thread.sleep(20);
    }
    if (stdout(files).contains(expected)) {
      return;
    }
    if (process.isAlive()) {
      forceDestroyAndAwait(process, files);
    }
    fail(
        "process did not acknowledge probe boundary; "
            + safeDiagnostics(files));
  }

  private static List<String> harnessCommand(
      String mode,
      String caseKey,
      String actorOrProbe,
      Path coordination) {
    List<String> command = processCommand(HARNESS_MAIN);
    command.addAll(
        List.of(
            mode,
            caseKey,
            actorOrProbe,
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> verifierCommand(
      Checkpoint checkpoint) {
    List<String> command = processCommand(VERIFIER_MAIN);
    command.addAll(
        List.of(
            "verify",
            checkpoint.name(),
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            READER_ROLE,
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> successorHarnessCommand(
      String mode,
      String caseKey,
      String actor,
      Path coordination) {
    List<String> command = processCommand(SUCCESSOR_MAIN);
    command.addAll(
        List.of(
            mode,
            caseKey,
            actor,
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> processCommand(String main) {
    return new ArrayList<>(
        List.of(
            javaExecutable(),
            "-Djava.net.useSystemProxies=false",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Dfile.encoding=UTF-8",
            "-cp",
            shippingJar() + File.pathSeparator + testClasses(),
            main));
  }

  private static List<String> shippingCommand(
      String... args) {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "-Djava.net.useSystemProxies=false",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dfile.encoding=UTF-8",
                "-jar",
                shippingJar().toString()));
    command.addAll(List.of(args));
    return command;
  }

  private static void writeOnlySecret(
      Process process, String password) throws IOException {
    try (DataOutputStream output =
        new DataOutputStream(process.getOutputStream())) {
      writeSecretFrame(output, password);
    }
  }

  private static void writeSecretFrame(
      DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
    output.flush();
  }

  private ProcessFiles processFiles(String name) {
    return new ProcessFiles(
        tempDir.resolve(name + ".stdout.log"),
        tempDir.resolve(name + ".stderr.log"));
  }

  private void assertNoSecrets(ProcessFiles files)
      throws IOException {
    String output = stdout(files) + "\n" + stderr(files);
    assertFalse(output.contains(POSTGRES.getPassword()));
    assertFalse(output.contains(READER_PASSWORD));
  }

  private String safeDiagnostics(ProcessFiles files) {
    try {
      return ("stdout="
              + stdout(files)
              + " stderr="
              + stderr(files))
          .replace(POSTGRES.getPassword(), "[REDACTED]")
          .replace(READER_PASSWORD, "[REDACTED]");
    } catch (IOException failure) {
      return "process logs unavailable";
    }
  }

  private static String stdout(ProcessFiles files)
      throws IOException {
    return new String(
        readBytes(files.stdout()), StandardCharsets.UTF_8);
  }

  private static String stderr(ProcessFiles files)
      throws IOException {
    return new String(
        readBytes(files.stderr()), StandardCharsets.UTF_8);
  }

  private static byte[] readBytes(Path path)
      throws IOException {
    return Files.isRegularFile(path)
        ? Files.readAllBytes(path)
        : new byte[0];
  }

  private static String field(String receipt, String name) {
    String prefix = name + "=";
    for (String token : receipt.strip().split(" ")) {
      if (token.startsWith(prefix)) {
        return token.substring(prefix.length());
      }
    }
    throw new AssertionError(
        "receipt field is absent: " + name);
  }

  private static String receiptKind(String receipt) {
    int separator = receipt.indexOf(' ');
    return separator < 0 ? receipt.strip() : receipt.substring(0, separator);
  }

  private static void assertRaceResult(
      String receipt,
      String tx,
      String actor,
      long pid,
      int exitCode,
      Checkpoint before,
      Checkpoint after) {
    assertEquals("PACK010_RACE_RESULT", receiptKind(receipt));
    assertEquals(tx, field(receipt, "tx"));
    assertEquals(actor, field(receipt, "actor"));
    assertEquals(Long.toString(pid), field(receipt, "pid"));
    if (exitCode == 0) {
      assertEquals("ADVANCED", field(receipt, "outcome"));
      assertEquals(
          Integer.toString(after.sequence),
          field(receipt, "sequence"));
      return;
    }
    assertEquals(10, exitCode);
    assertEquals("CONFLICT", field(receipt, "outcome"));
    assertEquals(
        Integer.toString(before.sequence),
        field(receipt, "expectedSequence"));
  }

  private void cleanupProcess(
      Process process, ProcessFiles files) throws Exception {
    try {
      forceDestroyAndAwait(process, files);
    } finally {
      assertNoSecrets(files);
    }
  }

  private void forceDestroyAndAwait(
      Process process, ProcessFiles files) throws Exception {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroyForcibly();
    assertTrue(
        process.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        () ->
            "process did not terminate after forced destroy; "
                + safeDiagnostics(files));
  }

  private static Connection readConnection()
      throws SQLException {
    Connection connection = dataSource().getConnection();
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(
        Connection.TRANSACTION_REPEATABLE_READ);
    return connection;
  }

  private static DataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private static DataSource dataSource(
      String username, String password) {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), username, password);
  }

  private static String prepareMode(String tx) {
    return switch (tx) {
      case "TX_A" -> "prepare-tx-a";
      case "TX_B" -> "prepare-tx-b";
      case "TX_C" -> "prepare-tx-c";
      default -> throw new IllegalArgumentException();
    };
  }

  private static String writeMode(String tx) {
    return switch (tx) {
      case "TX_A" -> "write-tx-a";
      case "TX_B" -> "write-tx-b";
      case "TX_C" -> "write-tx-c";
      default -> throw new IllegalArgumentException();
    };
  }

  private static String raceMode(String tx) {
    return switch (tx) {
      case "TX_A" -> "race-tx-a";
      case "TX_B" -> "race-tx-b";
      case "TX_C" -> "race-tx-c";
      default -> throw new IllegalArgumentException();
    };
  }

  private static Checkpoint beforeCheckpoint(String tx) {
    return switch (tx) {
      case "TX_A" -> Checkpoint.SEQ13;
      case "TX_B" -> Checkpoint.SEQ14;
      case "TX_C" -> Checkpoint.SEQ15;
      default -> throw new IllegalArgumentException();
    };
  }

  private static Checkpoint afterCheckpoint(String tx) {
    return switch (tx) {
      case "TX_A" -> Checkpoint.SEQ14;
      case "TX_B" -> Checkpoint.SEQ15;
      case "TX_C" -> Checkpoint.SEQ17;
      default -> throw new IllegalArgumentException();
    };
  }

  private static String classResource(String className) {
    return className.replace('.', '/') + ".class";
  }

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"), "bin", "java")
        .toString();
  }

  private static Path shippingJar() {
    return Path.of(
            System.getProperty("emerge.graph.it.jar"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path repo() {
    return Path.of(
            System.getProperty("emerge.graph.it.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return Path.of(
            System.getProperty("emerge.graph.it.testClasses"))
        .toAbsolutePath()
        .normalize();
  }

  private record ProcessFiles(Path stdout, Path stderr) {}

  private record ProcessResult(
      long pid, int exitCode, byte[] stdout, byte[] stderr) {

    private ProcessResult {
      stdout = stdout.clone();
      stderr = stderr.clone();
    }

    @Override
    public byte[] stdout() {
      return stdout.clone();
    }

    @Override
    public byte[] stderr() {
      return stderr.clone();
    }

    private String stdoutText() {
      return new String(stdout, StandardCharsets.UTF_8).strip();
    }

    private String stderrText() {
      return new String(stderr, StandardCharsets.UTF_8).strip();
    }

    private String safeText() {
      return ("stdout=" + stdoutText() + " stderr=" + stderrText())
          .replace(POSTGRES.getPassword(), "[REDACTED]")
          .replace(READER_PASSWORD, "[REDACTED]");
    }
  }

  private record DatabaseSnapshot(byte[] bytes) {

    private DatabaseSnapshot {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object candidate) {
      return candidate instanceof DatabaseSnapshot other
          && java.util.Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
      return java.util.Arrays.hashCode(bytes);
    }
  }

  private enum Checkpoint {
    SEQ13(13),
    SEQ14(14),
    SEQ15(15),
    SEQ17(17);

    private final int sequence;

    Checkpoint(int sequence) {
      this.sequence = sequence;
    }
  }
}
