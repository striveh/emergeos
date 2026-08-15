package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.core.port.CaptureStore;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack009DurableGraphCrashProcessIT {

  private static final Duration READY_TIMEOUT =
      Duration.ofSeconds(20);
  private static final Duration EXIT_TIMEOUT =
      Duration.ofSeconds(15);
  private static final String WRITER_MAIN =
      "io.emergeos.grapheval.Pack009GraphCrashHarnessMain";
  private static final String PROVIDER_MAIN =
      "io.emergeos.grapheval.Pack009LoopbackProviderMain";
  private static final String VERIFIER_MAIN =
      "io.emergeos.grapheval.Pack009GraphVerifierMain";
  private static final String REPLAY_MAIN =
      "io.emergeos.grapheval.Pack009GraphReplayMain";
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
          "flyway_schema_history",
          "local_draft_creation_receipts",
          "local_draft_undo_receipts",
          "local_drafts");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_eval")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @TempDir Path tempDir;

  @Test
  void providerAcceptedCrashIsDurablyUnknownAndCannotReplay()
      throws Exception {
    assertPackagingBoundary();
    provisionDatabaseFixture();
    Path coordination = tempDir.resolve("coordination");
    Files.createDirectory(coordination);
    ProcessFiles providerFiles = processFiles("provider-serve");
    ProcessFiles writerFiles = processFiles("writer");
    Process provider = null;
    Process writer = null;
    try {
      provider =
          start(
              providerCommand("serve", coordination),
              providerFiles);
      long providerPid = provider.pid();
      Path providerReady =
          coordination.resolve(
              Pack009ProcessSupport.PROVIDER_READY);
      awaitProcessFile(
          provider, providerReady, providerFiles);
      String providerBaseUrl =
          Pack009ProcessSupport.readBounded(providerReady);
      assertEquals(
          Pack009ProcessSupport.requireLoopbackProvider(
              providerBaseUrl),
          providerBaseUrl);

      writer =
          start(
              writerCommand(providerBaseUrl, coordination),
              writerFiles);
      long writerPid = writer.pid();
      try (DataOutputStream input =
          new DataOutputStream(writer.getOutputStream())) {
        writeSecretFrame(input, POSTGRES.getPassword());
        awaitStdout(
            writer,
            writerFiles,
            "GRAPH_WRITER_PHASE"
                + " credentialReadStarted=DURABLE");
        assertTrue(writer.isAlive());
        assertFalse(
            Files.exists(
                coordination.resolve(
                    Pack009ProcessSupport.PROVIDER_ACCEPTED)));
        assertFalse(
            stdout(writerFiles)
                .contains("providerKeyReads=1"));

        writeSecretFrame(
            input,
            Pack009ProcessSupport.SYNTHETIC_PROVIDER_KEY);
      }

      Path providerAccepted =
          coordination.resolve(
              Pack009ProcessSupport.PROVIDER_ACCEPTED);
      awaitProcessFile(
          provider, providerAccepted, providerFiles);
      assertEquals(
          expectedProviderLedger(),
          Pack009ProcessSupport.readBounded(providerAccepted));
      awaitStdout(
          writer,
          writerFiles,
          "GRAPH_WRITER_PHASE providerIntent=DURABLE");
      assertTrue(
          writer.isAlive(),
          () -> safeDiagnostics(writerFiles));
      assertDatabasePrefix();

      Path providerResponse =
          coordination.resolve(
              Pack009ProcessSupport.PROVIDER_RESPONSE);
      assertFalse(Files.exists(providerResponse));
      assertTrue(
          writer.isAlive(),
          () -> safeDiagnostics(writerFiles));
      assertTrue(writer.toHandle().destroyForcibly());
      assertTrue(
          writer.waitFor(
              EXIT_TIMEOUT.toMillis(),
              TimeUnit.MILLISECONDS),
          () -> safeDiagnostics(writerFiles));
      assertFalse(writer.isAlive());
      assertNotEquals(0, writer.exitValue());
      assertNoSecrets(writerFiles);
      DatabaseSnapshot afterCrash = databaseSnapshot();

      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(
              Pack009ProcessSupport.PROVIDER_RELEASE),
          "release-after-writer-pid-" + writerPid);
      awaitProcessFile(
          provider, providerResponse, providerFiles);
      assertEquals(
          Pack009LoopbackProviderMain.expectedResponseReceipt(),
          Pack009ProcessSupport.readBounded(providerResponse));
      assertEquals(afterCrash, databaseSnapshot());

      ProcessResult providerVerificationBeforeReplay =
          runToCompletion(
              providerCommand("verify", coordination),
              processFiles("provider-verify-before-replay"),
              null);
      assertEquals(
          0, providerVerificationBeforeReplay.exitCode());
      assertEquals(
          expectedProviderVerifyReceipt(),
          providerVerificationBeforeReplay.stdoutText());

      String headHash = graphHeadHash();
      String expectedVerifierReceipt =
          expectedGraphVerifyReceipt(headHash);
      ProcessResult verifierOne =
          runToCompletion(
              verifierCommand(),
              processFiles("verifier-one"),
              POSTGRES.getPassword());
      assertEquals(0, verifierOne.exitCode());
      assertEquals(
          expectedVerifierReceipt,
          verifierOne.stdoutText());
      assertEquals(afterCrash, databaseSnapshot());

      ProcessResult verifierTwo =
          runToCompletion(
              verifierCommand(),
              processFiles("verifier-two"),
              POSTGRES.getPassword());
      assertEquals(0, verifierTwo.exitCode());
      assertEquals(
          expectedVerifierReceipt,
          verifierTwo.stdoutText());
      assertNotEquals(verifierOne.pid(), verifierTwo.pid());
      assertArrayEquals(
          verifierOne.stdout(), verifierTwo.stdout());
      assertEquals(afterCrash, databaseSnapshot());

      ProcessResult leastAuthorityReplay =
          runToCompletion(
              replayCommand(),
              processFiles("replay"),
              POSTGRES.getPassword());
      assertEquals(10, leastAuthorityReplay.exitCode());
      assertEquals(
          expectedReplayReceipt(),
          leastAuthorityReplay.stdoutText());
      assertEquals("", leastAuthorityReplay.stderrText());
      assertEquals(afterCrash, databaseSnapshot());

      ProcessResult writerReplay =
          runToCompletion(
              writerCommand(providerBaseUrl, coordination),
              processFiles("writer-replay"),
              POSTGRES.getPassword());
      assertEquals(10, writerReplay.exitCode());
      assertEquals(
          expectedWriterReplayOutput(),
          writerReplay.stdoutText());
      assertEquals("", writerReplay.stderrText());
      assertNotEquals(writerPid, writerReplay.pid());
      assertNotEquals(
          leastAuthorityReplay.pid(), writerReplay.pid());
      assertEquals(afterCrash, databaseSnapshot());

      ProcessResult providerVerificationAfterReplay =
          runToCompletion(
              providerCommand("verify", coordination),
              processFiles("provider-verify-after-replay"),
              null);
      assertEquals(
          0, providerVerificationAfterReplay.exitCode());
      assertEquals(
          expectedProviderVerifyReceipt(),
          providerVerificationAfterReplay.stdoutText());
      assertEquals(
          expectedProviderLedger(),
          Pack009ProcessSupport.readBounded(providerAccepted));
      assertTrue(
          provider.isAlive(),
          () -> safeDiagnostics(providerFiles));

      Pack009ProcessSupport.writeDurableCreateNew(
          coordination.resolve(
              Pack009ProcessSupport.PROVIDER_SHUTDOWN),
          "shutdown-after-replay");
      assertTrue(
          provider.waitFor(
              EXIT_TIMEOUT.toMillis(),
              TimeUnit.MILLISECONDS),
          () -> safeDiagnostics(providerFiles));
      assertEquals(
          0,
          provider.exitValue(),
          () -> safeDiagnostics(providerFiles));
      assertNoSecrets(providerFiles);

      String snapshotHash =
          Pack009ProcessSupport.sha256(afterCrash.bytes());
      System.out.println(
          "PACK009_PROCESS_RECEIPT"
              + " providerPid="
              + providerPid
              + " writerPid="
              + writerPid
              + " verifierPids="
              + verifierOne.pid()
              + ","
              + verifierTwo.pid()
              + " replayPids="
              + leastAuthorityReplay.pid()
              + ","
              + writerReplay.pid()
              + " sequence=11"
              + " phase=PROVIDER_PENDING"
              + " billing=UNKNOWN"
              + " providerCount=1"
              + " provider=LOOPBACK_SYNTHETIC"
              + " realKey=false"
              + " realModelResult=false"
              + " usageAttribution=false"
              + " terminalSeal=false"
              + " console=SYNTHETIC_TEST"
              + " headHash="
              + headHash
              + " dbSnapshotSha256="
              + snapshotHash);
      System.out.flush();
    } finally {
      destroyIfAlive(writer);
      destroyIfAlive(provider);
    }
  }

  @Test
  void shippingJarContainsNoTestExecutionSurface()
      throws Exception {
    assertPackagingBoundary();
    ProcessResult result =
        runToCompletion(
            shippingCommand(
                "--execute",
                "--jdbc-url",
                POSTGRES.getJdbcUrl(),
                "--provider-url",
                "http://127.0.0.1:9/v1",
                "--crash-phase",
                "PROVIDER_ACCEPTED"),
            processFiles("shipping-rejection"),
            null);
    assertEquals(2, result.exitCode());
    assertEquals("", result.stdoutText());
    assertEquals(
        "GRAPH_EVAL_REJECTED reason=ARGUMENTS_INVALID",
        result.stderrText());
    assertFalse(
        result.stderrText().contains(POSTGRES.getPassword()));

    ProcessResult reservedVerify =
        runToCompletion(
            shippingCommand("--verify"),
            processFiles("shipping-verify-disabled"),
            null);
    assertEquals(2, reservedVerify.exitCode());
    assertEquals(
        new Pack009GraphPreflight(repo()).run().receipt(),
        reservedVerify.stdoutText());
    assertEquals(
        "GRAPH_EVAL_REJECTED"
            + " reason=SHIPPING_VERIFY_ROUTE_DISABLED",
        reservedVerify.stderrText());
  }

  private void assertPackagingBoundary() throws Exception {
    List<String> testClassResources;
    try (Stream<Path> paths = Files.walk(testClasses())) {
      testClassResources =
          paths
              .filter(Files::isRegularFile)
              .map(testClasses()::relativize)
              .map(Path::toString)
              .map(name -> name.replace('\\', '/'))
              .filter(name -> name.endsWith(".class"))
              .sorted()
              .toList();
    }
    assertFalse(testClassResources.isEmpty());
    for (String expectedTestClass :
        List.of(
            WRITER_MAIN,
            PROVIDER_MAIN,
            VERIFIER_MAIN,
            REPLAY_MAIN)) {
      assertTrue(
          testClassResources.contains(
              classResource(expectedTestClass)),
          expectedTestClass);
    }
    assertTrue(
        testClassResources.stream()
            .anyMatch(
                name ->
                    name.equals(
                        classResource(WRITER_MAIN)
                            .replace(
                                ".class",
                                "$SyntheticConsole.class"))));
    try (JarFile jar = new JarFile(shippingJar().toFile())) {
      for (String testClassResource : testClassResources) {
        assertNull(
            jar.getEntry(testClassResource),
            testClassResource);
      }
      assertFalse(
          jar.stream()
              .anyMatch(
                  entry ->
                      entry.getName().startsWith("org/junit/")
                          || entry
                              .getName()
                              .startsWith("org/testcontainers/")));
    }
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.shippingJarViolations(
            shippingJar()));
    for (String productionClass :
        List.of(
            "io.emergeos.grapheval.GraphEvalMain",
            "io.emergeos.grapheval.Pack009GraphEvalCatalog",
            "io.emergeos.grapheval.Pack009GraphVerifier",
            "io.emergeos.core.application.GraphAttemptCoordinator",
            "io.emergeos.adapters.postgres.PostgresGraphAttemptStore",
            "io.emergeos.adapters.agentloop.AgentLoopKernel",
            "io.emergeos.adapters.openai.OpenAiResponsesModel",
            "io.emergeos.adapters.openai.ReviewedOpenAiClient",
            "io.emergeos.contracts.CanonicalIntegrity")) {
      assertFalse(
          Files.exists(
              testClasses().resolve(classResource(productionClass))),
          productionClass);
    }
  }

  private void assertDatabasePrefix() throws Exception {
    try (Connection connection = readConnection()) {
      assertEquals(1, count(connection, "captures"));
      assertEquals(0, count(connection, "artifacts"));
      assertEquals(0, count(connection, "artifact_versions"));
      assertEquals(0, count(connection, "action_attempts"));
      assertEquals(
          0, count(connection, "action_attempt_transitions"));
      assertEquals(0, count(connection, "action_receipts"));
      assertEquals(
          0,
          count(
              connection,
              "local_draft_creation_receipts"));
      assertEquals(0, count(connection, "local_draft_undo_receipts"));
      assertEquals(0, count(connection, "local_drafts"));
      assertEquals(2, count(connection, "agent_runs"));
      assertEquals(0, count(connection, "agent_trace_events"));
      assertEquals(
          0,
          count(connection, "agent_run_resource_bindings"));
      assertEquals(
          0, count(connection, "agent_worker_results"));
      assertEquals(
          1, count(connection, "agent_graph_attempts"));
      assertEquals(
          2,
          count(
              connection,
              "agent_graph_attempt_run_bindings"));
      assertEquals(
          11, count(connection, "agent_graph_attempt_events"));
      assertEquals(
          1, count(connection, "agent_graph_attempt_heads"));
      assertEquals(
          0, count(connection, "agent_graph_attempt_seals"));
      assertEquals(
          0,
          count(
              connection,
              "agent_graph_attempt_candidates"));
      assertEquals(
          0,
          count(
              connection,
              "agent_graph_attempt_provider_attributions"));
      assertEquals(
          0,
          count(
              connection,
              "agent_graph_attempt_terminal_bindings"));
      assertEquals(19, count(connection, "flyway_schema_history"));

      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              SELECT capture_id, request_hash, content
              FROM captures
              WHERE principal_id = ?
              """)) {
        statement.setString(
            1, Pack009GraphEvalCatalog.PRINCIPAL_ID);
        try (ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(
              Pack009GraphEvalCatalog.CAPTURE_ID,
              rows.getString("capture_id"));
          assertEquals(
              Pack009GraphEvalCatalog
                  .EXPECTED_CAPTURE_REQUEST_HASH,
              rows.getString("request_hash"));
          assertEquals(
              Pack009GraphEvalCatalog.CONTENT,
              rows.getString("content"));
          assertFalse(rows.next());
        }
      }

      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  """
                  SELECT
                      state_version,
                      last_sequence,
                      phase,
                      billing_status,
                      provider_intent_count
                  FROM agent_graph_attempt_heads
                  """)) {
        assertTrue(rows.next());
        assertEquals(11, rows.getLong("state_version"));
        assertEquals(11, rows.getInt("last_sequence"));
        assertEquals(
            "PROVIDER_PENDING", rows.getString("phase"));
        assertEquals(
            "UNKNOWN", rows.getString("billing_status"));
        assertEquals(
            1, rows.getInt("provider_intent_count"));
        assertFalse(rows.next());
      }

      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  """
                  SELECT
                      event_type,
                      request_ordinal,
                      request_hash,
                      model_requested
                  FROM agent_graph_attempt_events
                  WHERE sequence = 11
                  """)) {
        assertTrue(rows.next());
        assertEquals(
            "PROVIDER_INTENT", rows.getString("event_type"));
        assertEquals(1, rows.getInt("request_ordinal"));
        assertEquals(
            Pack009GraphEvalCatalog
                .EXPECTED_FIRST_REQUEST_HASH,
            rows.getString("request_hash"));
        assertEquals(
            Pack009GraphEvalCatalog
                .workerProfile()
                .modelRequested(),
            rows.getString("model_requested"));
        assertFalse(rows.next());
      }

      try (PreparedStatement statement =
          connection.prepareStatement(
              """
              SELECT count(*)
              FROM agent_runs
              WHERE principal_id = ?
                AND lifecycle_status = 'RUNNING'
                AND graph_attempt_id = ?
                AND graph_manifest_hash = ?
                AND graph_role IN ('PARENT', 'CHILD')
                AND result_envelope IS NULL
                AND bundle IS NULL
                AND bundle_hash IS NULL
                AND trace_root_hash IS NULL
                AND last_event_sequence = 0
                AND resolved_model IS NULL
                AND cost_usd = 0
                AND token_count = 0
                AND latency_ms = 0
                AND failure_attribution IS NULL
                AND completed_at IS NULL
              """)) {
        statement.setString(
            1, Pack009GraphEvalCatalog.PRINCIPAL_ID);
        statement.setString(
            2,
            Pack009GraphEvalCatalog
                .manifest()
                .attemptId());
        statement.setString(
            3,
            Pack009GraphEvalCatalog
                .manifest()
                .manifestHash());
        try (ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(2, rows.getInt(1));
        }
      }
      connection.rollback();
    }
  }

  private DatabaseSnapshot databaseSnapshot()
      throws Exception {
    try (Connection connection = readConnection()) {
      List<String> observedTables = new ArrayList<>();
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
          observedTables.add(rows.getString(1));
        }
      }
      assertEquals(PUBLIC_TABLES, observedTables);

      StringBuilder snapshot = new StringBuilder();
      for (String table : observedTables) {
        if (!table.matches("[a-z][a-z0-9_]*")) {
          throw new IllegalStateException(
              "unsafe public table identifier");
        }
        snapshot
            .append(table.length())
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
            String xmin = rows.getString("row_xmin");
            snapshot
                .append(
                    row.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(row)
                .append('|')
                .append(xmin)
                .append('\n');
          }
        }
      }
      connection.rollback();
      return new DatabaseSnapshot(
          snapshot.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private void provisionDatabaseFixture() {
    DataSource dataSource = dataSource();
    Flyway.configure().dataSource(dataSource).load().migrate();
    PostgresCaptureStore captures =
        new PostgresCaptureStore(
            dataSource,
            new DataSourceTransactionManager(dataSource));
    var expected = Pack009GraphEvalCatalog.capture();
    CaptureStore.SaveResult result =
        captures.saveOrFindByNonce(expected);
    assertTrue(result.created());
    assertEquals(expected, result.capture());
  }

  private Connection readConnection() throws SQLException {
    Connection connection = dataSource().getConnection();
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(
        Connection.TRANSACTION_REPEATABLE_READ);
    return connection;
  }

  private String graphHeadHash() throws Exception {
    try (Connection connection = readConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT head_hash"
                    + " FROM agent_graph_attempt_heads")) {
      assertTrue(rows.next());
      String headHash = rows.getString(1);
      assertTrue(headHash.matches("[a-f0-9]{64}"));
      assertFalse(rows.next());
      connection.rollback();
      return headHash;
    }
  }

  private static int count(
      Connection connection, String table) throws SQLException {
    if (!PUBLIC_TABLES.contains(table)) {
      throw new IllegalArgumentException(
          "table is outside the reviewed snapshot");
    }
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT count(*) FROM public.\""
                    + table
                    + "\"")) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private static void writeSecretFrame(
      DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
    output.flush();
  }

  private void awaitProcessFile(
      Process process, Path file, ProcessFiles files)
      throws Exception {
    long deadline =
        System.nanoTime() + READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline
        && process.isAlive()
        && !Files.isRegularFile(file)) {
      Thread.sleep(20);
    }
    if (Files.isRegularFile(file)) {
      return;
    }
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
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

  private void awaitStdout(
      Process process,
      ProcessFiles files,
      String expected) throws Exception {
    long deadline =
        System.nanoTime() + READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline
        && process.isAlive()
        && !stdout(files).contains(expected)) {
      Thread.sleep(20);
    }
    if (stdout(files).contains(expected)) {
      return;
    }
    if (process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
      fail(
          "process timed out before stdout marker; "
              + safeDiagnostics(files));
    }
    fail(
        "process exited before stdout marker; exit="
            + process.exitValue()
            + " "
            + safeDiagnostics(files));
  }

  private ProcessResult runToCompletion(
      List<String> command,
      ProcessFiles files,
      String databasePassword) throws Exception {
    Process process = start(command, files);
    if (databasePassword == null) {
      process.getOutputStream().close();
    } else {
      try (DataOutputStream input =
          new DataOutputStream(process.getOutputStream())) {
        writeSecretFrame(input, databasePassword);
      }
    }
    boolean finished =
        process.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    assertTrue(
        finished,
        () ->
            "process timed out; " + safeDiagnostics(files));
    assertNoSecrets(files);
    return new ProcessResult(
        process.pid(),
        process.exitValue(),
        readBytes(files.stdout()),
        readBytes(files.stderr()));
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

  private static List<String> providerCommand(
      String mode, Path coordination) {
    List<String> command =
        processCommand(PROVIDER_MAIN, true);
    command.addAll(
        List.of(
            mode,
            coordination.toAbsolutePath().normalize().toString(),
            repo().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> writerCommand(
      String providerBaseUrl, Path coordination) {
    List<String> command =
        processCommand(WRITER_MAIN, false);
    command.addAll(
        List.of(
            "execute",
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            providerBaseUrl,
            coordination.toAbsolutePath().normalize().toString(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> verifierCommand() {
    List<String> command =
        processCommand(VERIFIER_MAIN, false);
    command.addAll(
        List.of(
            "verify",
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> replayCommand() {
    List<String> command =
        processCommand(REPLAY_MAIN, false);
    command.addAll(
        List.of(
            "replay",
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static List<String> processCommand(
      String main, boolean needsHttpServerModule) {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "-Djava.net.useSystemProxies=false",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dfile.encoding=UTF-8"));
    if (needsHttpServerModule) {
      command.addAll(
          List.of("--add-modules", "jdk.httpserver"));
    }
    command.addAll(
        List.of(
            "-cp",
            shippingJar()
                + File.pathSeparator
                + testClasses(),
            main));
    return command;
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

  private ProcessFiles processFiles(String name) {
    return new ProcessFiles(
        tempDir.resolve(name + ".stdout.log"),
        tempDir.resolve(name + ".stderr.log"));
  }

  private void assertNoSecrets(ProcessFiles files)
      throws IOException {
    String output =
        stdout(files) + "\n" + stderr(files);
    assertFalse(output.contains(POSTGRES.getPassword()));
    assertFalse(
        output.contains(
            Pack009ProcessSupport.SYNTHETIC_PROVIDER_KEY));
  }

  private String safeDiagnostics(ProcessFiles files) {
    try {
      return ("stdout="
              + stdout(files)
              + " stderr="
              + stderr(files))
          .replace(POSTGRES.getPassword(), "[REDACTED]")
          .replace(
              Pack009ProcessSupport.SYNTHETIC_PROVIDER_KEY,
              "[REDACTED]");
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
    if (!Files.isRegularFile(path)) {
      return new byte[0];
    }
    return Files.readAllBytes(path);
  }

  private static void destroyIfAlive(Process process)
      throws InterruptedException {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  private static String expectedProviderLedger() {
    return "PACK009_PROVIDER_LEDGER version=1"
        + " count=1"
        + " ordinal=1"
        + " status=ACCEPTED_BLOCKED"
        + " model="
        + Pack009GraphEvalCatalog
            .workerProfile()
            .modelRequested()
        + " requestHash="
        + Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH;
  }

  private static String expectedProviderVerifyReceipt() {
    return "PACK009_PROVIDER_VERIFY count=1"
        + " requestHash="
        + Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH
        + " responseDurable=true";
  }

  private static String expectedGraphVerifyReceipt(
      String headHash) {
    return "GRAPH_VERIFY verdict=VALID"
        + " outcome=INCOMPLETE"
        + " billing=UNKNOWN"
        + " sequence=11"
        + " phase=PROVIDER_PENDING"
        + " parent=RUNNING"
        + " child=RUNNING"
        + " providerIntents=1"
        + " terminalSeal=false"
        + " attemptId="
        + Pack009GraphEvalCatalog.manifest().attemptId()
        + " headHash="
        + headHash;
  }

  private static String expectedReplayReceipt() {
    return "GRAPH_REPLAY_REJECTED"
        + " reason=EXECUTION_SLOT_ALREADY_CLAIMED"
        + " ttyAvailabilityChecks=1"
        + " challengeReads=0"
        + " providerEndpointArg=ABSENT"
        + " providerCredentialFrame=ABSENT";
  }

  private static String expectedWriterReplayOutput() {
    return String.join(
        "\n",
        "GRAPH_WRITER_PHASE arguments=VALID",
        new Pack009GraphPreflight(repo()).run().receipt(),
        "GRAPH_WRITER_PHASE database=PREPROVISIONED",
        "GRAPH_WRITER_REPLAY_REJECTED"
            + " reason=EXECUTION_SLOT_ALREADY_CLAIMED"
            + " ttyAvailabilityChecks=1"
            + " challengeReads=0"
            + " providerKeyReads=0"
            + " console=SYNTHETIC_TEST");
  }

  private DataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
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
}
