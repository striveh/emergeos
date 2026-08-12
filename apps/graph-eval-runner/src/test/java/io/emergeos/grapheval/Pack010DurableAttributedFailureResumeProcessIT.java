package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresAttributedFailureResumeStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttributedFailureCode;
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
import java.util.List;
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
class Pack010DurableAttributedFailureResumeProcessIT {

  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final String HARNESS_MAIN =
      "io.emergeos.grapheval."
          + "Pack010DurableAttributedFailureResumeHarnessMain";
  private static final String WRITER_PASSWORD = UUID.randomUUID().toString();
  private static final String RESUMER_PASSWORD = UUID.randomUUID().toString();

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
  static void prepareDurablePrefixes() {
    DataSource admin = adminDataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles.sql"));
    JdbcClient.create(admin)
        .sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + WRITER_PASSWORD
                + "'")
        .update();
    JdbcClient.create(admin)
        .sql(
            "ALTER ROLE emergeos_failure_resumer PASSWORD '"
                + RESUMER_PASSWORD
                + "'")
        .update();
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles_check.sql"));
    seedSequence13(admin, writerDataSource(), 2);
    seedSequence13(admin, writerDataSource(), 3);
    seedSequence13(admin, writerDataSource(), 1);
  }

  @Test
  void hardKillAfterTypedFailureMustLeaveExactDurableProvenance()
      throws Exception {
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(2);
    GraphAttemptSnapshot before =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(writerDataSource()),
            manifest);
    PostgresGraphAttemptStore faulting =
        Pack010GraphTerminalStoreBridge.store(
            writerDataSource(),
            "TX_A",
            "AFTER_ATTRIBUTED_FAILURE_OUTCOME_INSERT",
            ignored -> {
              throw new IllegalStateException(
                  "SIMULATED_AFTER_DURABLE_FAILURE_OUTCOME_INSERT");
            });
    assertThrows(
        GraphAttemptIntegrityException.class,
        () ->
            faulting.providerFailureAttributed(
                manifest,
                before.cursor(),
                Pack010GraphTerminalFixture.catalogAttribution(2, 2),
                GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
                manifest.startedAt().plusMillis(13)));
    assertSequenceAndOutcomeCount(manifest, 13, 0);

    Process process =
        start(command("mint-and-halt", "a", 2, tempDir));
    String output;
    try {
      writeOnlySecret(process, WRITER_PASSWORD);
      assertTrue(
          process.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      output = readOutput(process);
      assertEquals(71, process.exitValue(), output);
    } finally {
      destroy(process);
    }
    assertTrue(output.contains("PACK010_DURABLE_FAILURE_MINTED"));
    assertTrue(output.contains("effects=0"));
    assertFalse(output.contains(WRITER_PASSWORD));
    assertFalse(output.contains(RESUMER_PASSWORD));

    Long durableOutcomes =
        JdbcClient.create(writerDataSource())
            .sql(
                "SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId "
                    + "AND state = 'READY' "
                    + "AND state_version = 1 "
                    + "AND failure_code = 'MODEL_RESPONSE_MALFORMED'")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single();
    assertEquals(1L, durableOutcomes);

    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    var ready = resume.load(manifest);
    assertRawClaimRejected(
        manifest,
        ready,
        "wrong-manifest",
        "0".repeat(64),
        ready.provenanceHash(),
        ready.stateVersion(),
        1000,
        "55000");
    assertRawClaimRejected(
        manifest,
        ready,
        "wrong-provenance",
        manifest.manifestHash(),
        "0".repeat(64),
        ready.stateVersion(),
        1000,
        "55000");
    assertRawClaimRejected(
        manifest,
        ready,
        "stale-version",
        manifest.manifestHash(),
        ready.provenanceHash(),
        ready.stateVersion() + 1,
        1000,
        "55000");
    assertRawClaimRejected(
        manifest,
        ready,
        "invalid-lease",
        manifest.manifestHash(),
        ready.provenanceHash(),
        ready.stateVersion(),
        30001,
        "22023");
    assertEquals(ready, resume.load(manifest));

    JdbcClient.create(adminDataSource())
        .sql(
            "UPDATE agent_graph_attributed_failure_outcomes "
                + "SET session_expires_at = "
                + "pg_catalog.clock_timestamp() - interval '1 second' "
                + "WHERE principal_id = :principalId "
                + "AND attempt_id = :attemptId")
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .update();
    assertRawClaimRejected(
        manifest,
        ready,
        "expired-session",
        manifest.manifestHash(),
        ready.provenanceHash(),
        ready.stateVersion(),
        1000,
        "55000");
    assertSequenceAndOutcomeCount(manifest, 14, 1);
  }

  @Test
  void twoFreshJvmsClaimingSameFailureHaveExactlyOneWinner()
      throws Exception {
    Path coordination = tempDir.resolve("claim-race");
    Files.createDirectory(coordination);
    Process mint =
        start(command("mint-and-halt", "seed", 3, coordination));
    try {
      writeOnlySecret(mint, WRITER_PASSWORD);
      assertTrue(
          mint.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      assertEquals(71, mint.exitValue(), readOutput(mint));
    } finally {
      destroy(mint);
    }
    Process first = start(command("claim", "a", 3, coordination));
    Process second = start(command("claim", "b", 3, coordination));
    String firstOutput;
    String secondOutput;
    try {
      writeOnlySecret(first, RESUMER_PASSWORD);
      writeOnlySecret(second, RESUMER_PASSWORD);
      awaitProcessFile(
          first, coordination.resolve("a.ready"), PROCESS_TIMEOUT);
      awaitProcessFile(
          second, coordination.resolve("b.ready"), PROCESS_TIMEOUT);
      String firstReady =
          Pack009ProcessSupport.readBounded(coordination.resolve("a.ready"));
      String secondReady =
          Pack009ProcessSupport.readBounded(coordination.resolve("b.ready"));
      assertNotEquals(pid(firstReady), pid(secondReady));
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve("claim.release"), "release");
      assertTrue(
          first.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      assertTrue(
          second.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      firstOutput = readOutput(first);
      secondOutput = readOutput(second);
    } finally {
      destroy(first);
      destroy(second);
    }
    assertTrue(
        (first.exitValue() == 0) ^ (second.exitValue() == 0),
        firstOutput + System.lineSeparator() + secondOutput);
    assertTrue(
        (first.exitValue() == 4) ^ (second.exitValue() == 4),
        firstOutput + System.lineSeparator() + secondOutput);
    assertTrue((firstOutput + secondOutput).contains("effects=0"));
    assertFalse((firstOutput + secondOutput).contains(WRITER_PASSWORD));
    assertFalse((firstOutput + secondOutput).contains(RESUMER_PASSWORD));

    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(3);
    var state =
        JdbcClient.create(writerDataSource())
            .sql(
                "SELECT state, state_version "
                    + "FROM agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(
                (row, ignored) ->
                    row.getString("state")
                        + ":"
                        + row.getInt("state_version"))
            .single();
    assertEquals("CLAIMED:2", state);
  }

  @Test
  void killedClaimIsFencedAndExpiredLeaseCanResumeAfterPostgresRestart()
      throws Exception {
    Path coordination = tempDir.resolve("claim-recovery");
    Files.createDirectory(coordination);
    Process mint =
        start(command("mint-and-halt", "seed", 1, coordination));
    try {
      writeOnlySecret(mint, WRITER_PASSWORD);
      assertTrue(
          mint.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      assertEquals(71, mint.exitValue(), readOutput(mint));
    } finally {
      destroy(mint);
    }

    Process killed =
        start(command("claim-and-halt", "dead", 1, coordination));
    try {
      writeOnlySecret(killed, RESUMER_PASSWORD);
      awaitProcessFile(
          killed, coordination.resolve("dead.ready"), PROCESS_TIMEOUT);
      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve("claim.release"), "release");
      assertTrue(
          killed.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      assertEquals(72, killed.exitValue(), readOutput(killed));
    } finally {
      destroy(killed);
    }

    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(1);
    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    var killedCursor = resume.load(manifest);
    assertEquals(
        PostgresAttributedFailureResumeStore.State.CLAIMED,
        killedCursor.state());
    assertEquals(2, killedCursor.stateVersion());
    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            resume.claim(
                manifest,
                killedCursor,
                "too-early-successor",
                IntegrityHashes.utf8ContentHash("too-early-successor"),
                Duration.ofSeconds(1)));

    restartPostgres(writerDataSource());
    awaitLeaseExpiry(manifest);
    Path resumedCoordination = tempDir.resolve("claim-recovery-resumed");
    Files.createDirectory(resumedCoordination);
    Process resumed =
        start(command("claim", "recovered", 1, resumedCoordination));
    String resumedOutput;
    try {
      writeOnlySecret(resumed, RESUMER_PASSWORD);
      awaitProcessFile(
          resumed,
          resumedCoordination.resolve("recovered.ready"),
          PROCESS_TIMEOUT);
      Pack009ProcessSupport.writeDurableCreateNew(
          resumedCoordination.resolve("claim.release"), "release");
      assertTrue(
          resumed.waitFor(
              PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      resumedOutput = readOutput(resumed);
      assertEquals(0, resumed.exitValue(), resumedOutput);
    } finally {
      destroy(resumed);
    }
    assertTrue(resumedOutput.contains("stateVersion=3"));
    assertTrue(resumedOutput.contains("effects=0"));
    assertFalse(resumedOutput.contains(WRITER_PASSWORD));
    assertFalse(resumedOutput.contains(RESUMER_PASSWORD));

    var recoveredCursor = resume.load(manifest);
    assertEquals(3, recoveredCursor.stateVersion());
    assertEquals("packaged-jvm-recovered", recoveredCursor.claimantId());
    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            resume.claim(
                manifest,
                killedCursor,
                "stale-dead-process",
                IntegrityHashes.utf8ContentHash("stale-dead-process"),
                Duration.ofSeconds(1)));
    assertThrows(
        GraphAttemptConflictException.class,
        () ->
            resume.claim(
                manifest,
                recoveredCursor,
                "replayed-successor",
                IntegrityHashes.utf8ContentHash("replayed-successor"),
                Duration.ofSeconds(1)));
    assertEquals(recoveredCursor, resume.load(manifest));
  }

  private static void seedSequence13(
      DataSource admin, DataSource writerDataSource, int repetition) {
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, repetition);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        manifest,
        OwnerTtyGraphAuthority.Pack010Revision.values()[repetition - 1],
        Pack010GraphTerminalFixture.catalogIntent(repetition, 1),
        Instant.now()
            .truncatedTo(ChronoUnit.MICROS)
            .plus(Duration.ofMinutes(5)));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    assertEquals(
        13,
        Pack010GraphTerminalFixture.verified(writer, manifest)
            .cursor()
            .lastSequence());
  }

  private static Process start(List<String> command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo().toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    return builder.start();
  }

  private static List<String> command(
      String mode, String actor, int repetition, Path coordination) {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString(),
                "-Djava.net.useSystemProxies=false",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dfile.encoding=UTF-8",
                "-cp",
                shippingJar() + File.pathSeparator + testClasses(),
                HARNESS_MAIN));
    command.addAll(
        List.of(
            mode,
            actor,
            Integer.toString(repetition),
            repo().toString(),
                POSTGRES.getJdbcUrl(),
                mode.equals("mint-and-halt")
                    ? "emergeos_graph_prefix_writer"
                    : "emergeos_failure_resumer",
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static void writeOnlySecret(Process process, String secret)
      throws IOException {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream output =
        new DataOutputStream(process.getOutputStream())) {
      output.writeInt(bytes.length);
      output.write(bytes);
    }
  }

  private static String readOutput(Process process) throws IOException {
    return new String(
        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void awaitProcessFile(
      Process process, Path path, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(path)) {
        return;
      }
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "packaged process exited before evidence: "
                + process.exitValue()
                + System.lineSeparator()
                + readOutput(process));
      }
      Thread.sleep(25);
    }
    throw new IllegalStateException("timed out waiting for process evidence");
  }

  private static long pid(String receipt) {
    for (String token : receipt.split(" ")) {
      if (token.startsWith("pid=")) {
        return Long.parseLong(token.substring("pid=".length()));
      }
    }
    throw new IllegalArgumentException("pid missing");
  }

  private static void destroy(Process process) throws InterruptedException {
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  private static void restartPostgres(DataSource probe) throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart -D \"$PGDATA\" -m immediate -w");
    assertEquals(0, restart.getExitCode(), restart.getStderr());
    long deadline =
        System.nanoTime() + PROCESS_TIMEOUT.toNanos();
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

  private static void awaitLeaseExpiry(GraphAttemptManifest manifest)
      throws InterruptedException {
    PostgresAttributedFailureResumeStore resume =
        new PostgresAttributedFailureResumeStore(resumerDataSource());
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      var cursor = resume.load(manifest);
      if (cursor.claimExpiresAt() != null
          && !databaseNow().isBefore(cursor.claimExpiresAt())) {
        return;
      }
      Thread.sleep(25);
    }
    throw new IllegalStateException("durable failure lease did not expire");
  }

  private static Instant databaseNow() {
    return JdbcClient.create(adminDataSource())
        .sql("SELECT pg_catalog.clock_timestamp()")
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private static void assertSequenceAndOutcomeCount(
      GraphAttemptManifest manifest,
      int expectedSequence,
      int expectedOutcomes) {
    JdbcClient jdbc = JdbcClient.create(writerDataSource());
    Integer sequence =
        jdbc.sql(
                "SELECT last_sequence FROM agent_graph_attempt_heads "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Integer.class)
            .single();
    Long outcomes =
        jdbc.sql(
                "SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single();
    assertEquals(expectedSequence, sequence);
    assertEquals((long) expectedOutcomes, outcomes);
  }

  private static void assertRawClaimRejected(
      GraphAttemptManifest manifest,
      PostgresAttributedFailureResumeStore.DurableFailureCursor expected,
      String claimant,
      String manifestHash,
      String provenanceHash,
      long stateVersion,
      int leaseMillis,
      String expectedSqlState) {
    RuntimeException rejection =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(resumerDataSource())
                    .sql(
                        "SELECT public."
                            + "agent_graph_claim_attributed_failure_resume_v12("
                            + ":principalId, :attemptId, :manifestHash, "
                            + ":provenanceHash, :stateVersion, :claimantId, "
                            + ":fenceTokenHash, :leaseMillis)")
                    .param("principalId", manifest.principalId())
                    .param("attemptId", manifest.attemptId())
                    .param("manifestHash", manifestHash)
                    .param("provenanceHash", provenanceHash)
                    .param("stateVersion", stateVersion)
                    .param("claimantId", claimant)
                    .param(
                        "fenceTokenHash",
                        IntegrityHashes.utf8ContentHash(
                            "pack010-rejected-fence|" + claimant))
                    .param("leaseMillis", leaseMillis)
                    .query(Long.class)
                    .single());
    Throwable root = rejection;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root instanceof java.sql.SQLException, rejection.toString());
    assertEquals(
        expectedSqlState,
        ((java.sql.SQLException) root).getSQLState(),
        rejection.toString());
    var unchanged =
        new PostgresAttributedFailureResumeStore(resumerDataSource())
            .load(manifest);
    assertEquals(expected.state(), unchanged.state());
    assertEquals(expected.stateVersion(), unchanged.stateVersion());
    assertEquals(expected.cursorHeadHash(), unchanged.cursorHeadHash());
  }

  private static DataSource adminDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static DataSource writerDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser("emergeos_graph_prefix_writer");
    source.setPassword(WRITER_PASSWORD);
    return source;
  }

  private static DataSource resumerDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser("emergeos_failure_resumer");
    source.setPassword(RESUMER_PASSWORD);
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
        Pack010DurableAttributedFailureResumeProcessIT.class
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
    return Path.of(System.getProperty("emerge.graph.it.jar"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return Path.of(System.getProperty("emerge.graph.it.testClasses"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path repo() {
    return Path.of(System.getProperty("emerge.graph.it.repo"))
        .toAbsolutePath()
        .normalize();
  }
}
