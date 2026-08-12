package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresExactPicoProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Acceptance Red for the first read-only, fresh-JVM V19 overlay reader. */
@Testcontainers
class Pack010ExactPicoOverlayReaderAcceptanceIT {

  private static final String READER_ROLE =
      "emergeos_exact_overlay_reader_v19";
  private static final String WRITER_ROLE =
      "emergeos_graph_prefix_writer";
  private static final String V15_ROLE =
      "emergeos_provider_attestor_v15";
  private static final String V13_ROLE =
      "emergeos_provider_attestor";
  private static final String V16_ROLE =
      "emergeos_provider_attestor_v16";
  private static final String R2_PROFILE_ID =
      "pack010-v19-r2-synthetic-exact-pico-v1";
  private static final Set<String> READER_TABLES =
      Set.of(
          "agent_graph_attempts",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_provider_validation_keys",
          "agent_graph_provider_validations",
          "agent_graph_provider_profiles_v14",
          "agent_graph_exact_tx_a_requirements_v15",
          "agent_graph_exact_provider_validations_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_eval")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void freshJvmReturnsMissingWithoutChangingLegacyOrAnyPublicTable()
      throws Exception {
    DataSource admin = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

    String writerPassword = UUID.randomUUID().toString();
    String readerPassword = UUID.randomUUID().toString();
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource("db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(admin, WRITER_ROLE, writerPassword);
    configureReader(admin, readerPassword);
    assertExactReaderRole(admin, readerPassword);

    DataSource writerDataSource = dataSource(WRITER_ROLE, writerPassword);
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(1);
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, 1);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        manifest,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        Pack010GraphTerminalFixture.catalogIntent(1, 1),
        Instant.now()
            .truncatedTo(ChronoUnit.MICROS)
            .plus(Duration.ofMinutes(5)));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, 1);
    assertEquals(13, sequence13.cursor().lastSequence());
    assertNoOverlayMarker(admin, manifest);

    DatabaseImage before = databaseImage(admin);
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    ProcessReceipt receipt = runFreshJvm(1, "MISSING", readerPassword);

    // These mutation fences intentionally run before the expected Red verdict.
    assertEquals(before, databaseImage(admin));
    GraphAttemptSnapshot legacyAfter =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfter);
    assertEquals(13, legacyAfter.cursor().lastSequence());
    assertNoOverlayMarker(admin, manifest);

    assertFalse(receipt.output().contains(readerPassword));
    assertFalse(receipt.output().contains(writerPassword));
    assertFalse(receipt.output().contains(POSTGRES.getPassword()));
    assertEquals(0, receipt.exitCode(), receipt.output());
    assertEquals(
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=MISSING repetition=1 legacySequence=13",
        receipt.output());
  }

  @Test
  void freshJvmReturnsRequiredForV15MarkerWithoutV13Validation()
      throws Exception {
    DataSource admin = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

    String writerPassword = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    String readerPassword = UUID.randomUUID().toString();
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource("db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(admin, WRITER_ROLE, writerPassword);
    setEphemeralPassword(admin, V15_ROLE, v15Password);
    configureReader(admin, readerPassword);
    assertExactReaderRole(admin, readerPassword);
    SyntheticProfile profile = insertSyntheticProfile(admin, 2);
    assertTrue(profile.profileHash().matches("[0-9a-f]{64}"));

    DataSource writerDataSource = dataSource(WRITER_ROLE, writerPassword);
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(2);
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, 2);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        manifest,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        Pack010GraphTerminalFixture.catalogIntent(2, 1),
        Instant.now()
            .truncatedTo(ChronoUnit.MICROS)
            .plus(Duration.ofMinutes(5)));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, 2);
    assertEquals(13, sequence13.cursor().lastSequence());
    assertV13ValidationCount(admin, manifest, 0L);
    String requirementHash =
        requireExactTxA(
            dataSource(V15_ROLE, v15Password), manifest, profile.profileId());
    assertTrue(requirementHash.matches("[0-9a-f]{64}"));
    assertRequiredMarkerOnly(
        admin, manifest, profile.profileHash(), requirementHash);

    DatabaseImage before = databaseImage(admin);
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    ProcessReceipt receipt = runFreshJvm(2, "REQUIRED", readerPassword);

    // Reader authority must not turn a V15 marker into a V13/V16 mutation.
    assertEquals(before, databaseImage(admin));
    GraphAttemptSnapshot legacyAfter =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfter);
    assertEquals(13, legacyAfter.cursor().lastSequence());
    assertRequiredMarkerOnly(
        admin, manifest, profile.profileHash(), requirementHash);

    assertFalse(receipt.output().contains(readerPassword));
    assertFalse(receipt.output().contains(writerPassword));
    assertFalse(receipt.output().contains(v15Password));
    assertFalse(receipt.output().contains(POSTGRES.getPassword()));
    assertEquals(0, receipt.exitCode(), receipt.output());
    assertEquals(
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=REQUIRED repetition=2 legacySequence=13",
        receipt.output());
  }

  @Test
  void freshJvmVerifiesHistoricalReceiptAfterRevocationAndChallengeExpiry()
      throws Exception {
    AcceptanceContext context = prepareAcceptanceContext(3);
    context = completeExactOverlay(context);
    setAuthorityStatus(
        context.admin(), context.profile().profileId(), context.keyId(),
        "REVOKED");
    waitUntilExpired(context.admin(), context.expiresAtEpochMicros());

    assertEquals(4L, overlayRowCount(context.admin(), context.manifest()));
    assertEquals(0L, legacySequence14Count(context.admin(), context.manifest()));
    assertFreshRead(
        context,
        "ATTRIBUTED",
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=ATTRIBUTED repetition=3 legacySequence=13",
        4L);

    createOverlayBackup(context);
    try {
      installPartialOverlayInsideDisabledConstraintScope(context);
      assertFreshRead(
          context,
          "EXACT_OVERLAY_PARTIAL",
          "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=INVALID reason=EXACT_OVERLAY_PARTIAL repetition=3 legacySequence=13",
          1L);
      restoreOverlayChildren(context);
      assertEquals(4L, overlayRowCount(context.admin(), context.manifest()));
      assertAttributedRead(context);

      tamperCanonicalInputsInsideDisabledConstraintScope(context);
      assertFreshRead(
          context,
          "EXACT_OVERLAY_INVALID",
          "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=INVALID reason=EXACT_OVERLAY_INVALID repetition=3 legacySequence=13",
          4L);
      restoreCanonicalInputs(context);
      assertEquals(4L, overlayRowCount(context.admin(), context.manifest()));
      assertAttributedRead(context);

      tamperSignatureAndReceiptInsideDisabledConstraintScope(context);
      assertFreshRead(
          context,
          "EXACT_OVERLAY_INVALID",
          "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=INVALID reason=EXACT_OVERLAY_INVALID repetition=3 legacySequence=13",
          4L);
      restoreOverlayValidation(context);
      assertEquals(4L, overlayRowCount(context.admin(), context.manifest()));
      assertAttributedRead(context);

      assertLegacyRoleDriftRejected(
          context,
          "ALTER ROLE emergeos_provider_attestor SUPERUSER",
          "ALTER ROLE emergeos_provider_attestor NOSUPERUSER");
      assertLegacyRoleDriftRejected(
          context,
          "ALTER ROLE emergeos_provider_attestor_v15 BYPASSRLS",
          "ALTER ROLE emergeos_provider_attestor_v15 NOBYPASSRLS");
      assertUnexpectedViewGrantRejected(context);
    } finally {
      dropOverlayBackup(context.admin());
    }
  }

  private static ProcessReceipt runFreshJvm(
      int repetition, String expectedState, String readerPassword)
      throws Exception {
    Path appJar =
        Path.of(requiredProperty("emerge.graph.it.jar")).toRealPath();
    Path testClasses =
        Path.of(requiredProperty("emerge.graph.it.testClasses")).toRealPath();
    String classPath =
        appJar + System.getProperty("path.separator") + testClasses;
    ProcessBuilder builder =
        new ProcessBuilder(
            javaExecutable().toString(),
            "-cp",
            classPath,
            Pack010ExactPicoOverlayReaderMain.class.getName(),
            "verify",
            Integer.toString(repetition),
            expectedState,
            jdbcUrl(),
            READER_ROLE,
            appJar.toString(),
            testClasses.toString());
    builder.environment().clear();
    builder.redirectErrorStream(true);
    Process process = builder.start();
    byte[] secret = readerPassword.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream input =
        new DataOutputStream(process.getOutputStream())) {
      input.writeInt(secret.length);
      input.write(secret);
      input.flush();
    } finally {
      java.util.Arrays.fill(secret, (byte) 0);
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "fresh JVM did not stop");
      throw new AssertionError("fresh JVM timed out");
    }
    String output =
        new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .trim();
    return new ProcessReceipt(process.exitValue(), output);
  }

  private static AcceptanceContext prepareAcceptanceContext(int repetition)
      throws Exception {
    DataSource admin = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);
    String writerPassword = UUID.randomUUID().toString();
    String v13Password = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    String v16Password = UUID.randomUUID().toString();
    String readerPassword = UUID.randomUUID().toString();
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource("db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(admin, WRITER_ROLE, writerPassword);
    setEphemeralPassword(admin, V13_ROLE, v13Password);
    setEphemeralPassword(admin, V15_ROLE, v15Password);
    setEphemeralPassword(admin, V16_ROLE, v16Password);
    configureReader(admin, readerPassword);
    assertExactReaderRole(admin, readerPassword);

    SyntheticProfile profile = insertSyntheticProfile(admin, repetition);
    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String keyId = "pack010-v19-r" + repetition + "-ed25519-key";
    insertProviderValidationKey(admin, keyId, keyPair);
    DataSource writerDataSource = dataSource(WRITER_ROLE, writerPassword);
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
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        Pack010GraphTerminalFixture.catalogIntent(repetition, 1),
        Instant.now()
            .truncatedTo(ChronoUnit.MICROS)
            .plus(Duration.ofMinutes(5)));
    String policyHash =
        PostgresProviderValidationAttestor.open(
                dataSource(V13_ROLE, v13Password))
            .requireValidation(
                manifest,
                sequence7.cursor(),
                keyId,
                profile.transportProfileHash(),
                profile.parserProfileHash(),
                profile.schemaProfileHash(),
                Duration.ofMillis(5_000));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    String requirementHash =
        requireExactTxA(
            dataSource(V15_ROLE, v15Password), manifest, profile.profileId());
    assertEquals(13, sequence13.cursor().lastSequence());
    assertTrue(policyHash.matches("[0-9a-f]{64}"));
    assertTrue(requirementHash.matches("[0-9a-f]{64}"));
    return new AcceptanceContext(
        admin,
        writer,
        manifest,
        sequence13,
        profile,
        keyPair,
        keyId,
        requirementHash,
        dataSource(V16_ROLE, v16Password),
        readerPassword,
        List.of(
            writerPassword,
            v13Password,
            v15Password,
            v16Password,
            readerPassword,
            POSTGRES.getPassword()),
        0L);
  }

  private static AcceptanceContext completeExactOverlay(
      AcceptanceContext context) {
    int repetition = context.manifest().experiment().repetition();
    GraphProviderIntent request =
        Pack010GraphTerminalFixture.catalogIntent(
            repetition, 2);
    GraphProviderAttribution attribution =
        Pack010GraphTerminalFixture.catalogAttribution(
            repetition, 2);
    GraphExactPicoProviderValidationCommand command =
        new GraphExactPicoProviderValidationCommand(
            context.manifest().principalId(),
            context.manifest().attemptId(),
            context.manifest().manifestHash(),
            context.requirementHash(),
            context.sequence13().cursor().headHash(),
            request.requestHash(),
            attribution.responseHash(),
            IntegrityHashes.utf8ContentHash(attribution.modelResolved()),
            2L,
            1L,
            1L,
            IntegrityHashes.utf8ContentHash(
                "pack010-v19-structured-final-r"
                    + repetition),
            context.keyId(),
            Duration.ofMillis(5_000));
    GraphExactPicoProviderValidationReceipt receipt =
        PostgresExactPicoProviderValidationAttestor.open(
                context.v16DataSource())
            .complete(
                command,
                challenge ->
                    HexFormat.of().formatHex(
                        sign(
                            context.keyPair(),
                            challenge.signatureMaterial())));
    assertEquals(14, receipt.overlaySequence());
    long expiresAt =
        JdbcClient.create(context.admin())
            .sql(
                """
                SELECT expires_at_epoch_micros
                FROM public.agent_graph_exact_provider_validations_v16
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", context.manifest().principalId())
            .param("attemptId", context.manifest().attemptId())
            .query(Long.class)
            .single();
    return context.withExpiresAtEpochMicros(expiresAt);
  }

  private static void assertFreshRead(
      AcceptanceContext context,
      String expectedState,
      String expectedReceipt,
      long expectedOverlayRows) throws Exception {
    DatabaseImage before = databaseImage(context.admin());
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(
            context.writer(), context.manifest());
    ProcessReceipt receipt =
        runFreshJvm(3, expectedState, context.readerPassword());
    assertEquals(before, databaseImage(context.admin()));
    assertLegacy13Unchanged(context, legacyBefore);
    assertEquals(
        expectedOverlayRows,
        overlayRowCount(context.admin(), context.manifest()));
    assertSecretFree(receipt, context);
    assertEquals(0, receipt.exitCode(), receipt.output());
    assertEquals(expectedReceipt, receipt.output());
  }

  private static void assertAttributedRead(AcceptanceContext context)
      throws Exception {
    assertFreshRead(
        context,
        "ATTRIBUTED",
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=ATTRIBUTED repetition=3 legacySequence=13",
        4L);
  }

  private static void assertLegacyRoleDriftRejected(
      AcceptanceContext context, String driftSql, String restoreSql)
      throws Exception {
    DatabaseImage before = databaseImage(context.admin());
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(
            context.writer(), context.manifest());
    try {
      execute(context.admin(), driftSql);
      ProcessReceipt receipt =
          runFreshJvm(3, "ATTRIBUTED", context.readerPassword());
      assertEquals(before, databaseImage(context.admin()));
      assertLegacy13Unchanged(context, legacyBefore);
      assertSecretFree(receipt, context);
      assertEquals(3, receipt.exitCode(), receipt.output());
      assertEquals(
          "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=SURFACE_OR_VERIFICATION_INVALID",
          receipt.output());
    } finally {
      execute(context.admin(), restoreSql);
    }
    assertAttributedRead(context);
  }

  private static void assertUnexpectedViewGrantRejected(
      AcceptanceContext context) throws Exception {
    DatabaseImage before = databaseImage(context.admin());
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(
            context.writer(), context.manifest());
    try {
      execute(
          context.admin(),
          """
          CREATE VIEW public.pack010_v19_unreviewed_view AS
          SELECT principal_id, attempt_id
          FROM public.agent_graph_attempts;
          GRANT SELECT ON public.pack010_v19_unreviewed_view
            TO emergeos_exact_overlay_reader_v19
          """);
      ProcessReceipt receipt =
          runFreshJvm(3, "ATTRIBUTED", context.readerPassword());
      assertEquals(before, databaseImage(context.admin()));
      assertLegacy13Unchanged(context, legacyBefore);
      assertSecretFree(receipt, context);
      assertEquals(3, receipt.exitCode(), receipt.output());
      assertEquals(
          "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=SURFACE_OR_VERIFICATION_INVALID",
          receipt.output());
    } finally {
      execute(
          context.admin(),
          "DROP VIEW IF EXISTS public.pack010_v19_unreviewed_view");
    }
    assertAttributedRead(context);
  }

  private static void assertLegacy13Unchanged(
      AcceptanceContext context, GraphAttemptSnapshot before) {
    GraphAttemptSnapshot after =
        Pack010GraphTerminalFixture.verified(
            context.writer(), context.manifest());
    assertEquals(before, after);
    assertEquals(13, after.cursor().lastSequence());
    assertEquals(
        0L, legacySequence14Count(context.admin(), context.manifest()));
  }

  private static void assertSecretFree(
      ProcessReceipt receipt, AcceptanceContext context) {
    for (String secret : context.secrets()) {
      assertFalse(receipt.output().contains(secret));
    }
  }

  private static void createOverlayBackup(AcceptanceContext context)
      throws SQLException {
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(
          "DROP SCHEMA IF EXISTS pack010_v19_overlay_backup CASCADE");
      statement.execute("CREATE SCHEMA pack010_v19_overlay_backup");
      statement.execute(
          "REVOKE ALL ON SCHEMA pack010_v19_overlay_backup FROM PUBLIC");
      JdbcClient jdbc =
          JdbcClient.create(new SingleConnectionDataSource(connection, true));
      for (String table :
          List.of(
              "agent_graph_exact_provider_validations_v16",
              "agent_graph_exact_provider_attributions_v16",
              "agent_graph_exact_attempt_events_v16",
              "agent_graph_exact_attempt_heads_v16")) {
        jdbc.sql(
                """
                CREATE TABLE pack010_v19_overlay_backup.%s AS
                SELECT * FROM public.%s
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """.formatted(table, table))
            .param("principalId", context.manifest().principalId())
            .param("attemptId", context.manifest().attemptId())
            .update();
        statement.execute(
            "REVOKE ALL ON TABLE pack010_v19_overlay_backup."
                + table
                + " FROM PUBLIC");
      }
      connection.commit();
    }
    assertEquals(
        4L,
        JdbcClient.create(context.admin())
            .sql(
                """
                SELECT
                  (SELECT count(*) FROM
                    pack010_v19_overlay_backup.agent_graph_exact_provider_validations_v16)
                  +
                  (SELECT count(*) FROM
                    pack010_v19_overlay_backup.agent_graph_exact_provider_attributions_v16)
                  +
                  (SELECT count(*) FROM
                    pack010_v19_overlay_backup.agent_graph_exact_attempt_events_v16)
                  +
                  (SELECT count(*) FROM
                    pack010_v19_overlay_backup.agent_graph_exact_attempt_heads_v16)
                """)
            .query(Long.class)
            .single());
  }

  private static void installPartialOverlayInsideDisabledConstraintScope(
      AcceptanceContext context) throws SQLException {
    try (Connection connection = context.admin().getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL session_replication_role = replica");
      }
      JdbcClient jdbc =
          JdbcClient.create(new SingleConnectionDataSource(connection, true));
      int deleted = 0;
      for (String table :
          List.of(
              "agent_graph_exact_attempt_heads_v16",
              "agent_graph_exact_attempt_events_v16",
              "agent_graph_exact_provider_attributions_v16")) {
        deleted +=
            jdbc.sql(
                    """
                    DELETE FROM public.%s
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """.formatted(table))
                .param("principalId", context.manifest().principalId())
                .param("attemptId", context.manifest().attemptId())
                .update();
      }
      assertEquals(3, deleted);
      connection.commit();
    }
  }

  private static void restoreOverlayChildren(AcceptanceContext context)
      throws SQLException {
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL session_replication_role = replica");
      for (String table :
          List.of(
              "agent_graph_exact_provider_attributions_v16",
              "agent_graph_exact_attempt_events_v16",
              "agent_graph_exact_attempt_heads_v16")) {
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO public."
                    + table
                    + " SELECT * FROM pack010_v19_overlay_backup."
                    + table));
      }
      connection.commit();
    }
  }

  private static void tamperCanonicalInputsInsideDisabledConstraintScope(
      AcceptanceContext context) throws SQLException {
    String tamperedResponseHash =
        IntegrityHashes.utf8ContentHash(
            "pack010-v19-cross-row-consistent-stale-canonical-v1");
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL session_replication_role = replica");
      JdbcClient jdbc =
          JdbcClient.create(new SingleConnectionDataSource(connection, true));
      for (String table :
          List.of(
              "agent_graph_exact_provider_validations_v16",
              "agent_graph_exact_provider_attributions_v16")) {
        assertEquals(
            1,
            jdbc.sql(
                    """
                    UPDATE public.%s
                    SET response_hash = CAST(:responseHash AS char(64))
                    WHERE principal_id = :principalId
                      AND attempt_id = :attemptId
                    """.formatted(table))
                .param("responseHash", tamperedResponseHash)
                .param("principalId", context.manifest().principalId())
                .param("attemptId", context.manifest().attemptId())
                .update());
      }
      connection.commit();
    }
  }

  private static void restoreCanonicalInputs(AcceptanceContext context)
      throws SQLException {
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL session_replication_role = replica");
      for (String table :
          List.of(
              "agent_graph_exact_provider_validations_v16",
              "agent_graph_exact_provider_attributions_v16")) {
        assertEquals(
            1,
            statement.executeUpdate(
                """
                UPDATE public.%s target
                SET response_hash = backup.response_hash
                FROM pack010_v19_overlay_backup.%s backup
                WHERE target.principal_id = backup.principal_id
                  AND target.attempt_id = backup.attempt_id
                """.formatted(table, table)));
      }
      connection.commit();
    }
  }

  private static void tamperSignatureAndReceiptInsideDisabledConstraintScope(
      AcceptanceContext context) throws SQLException {
    ValidationReceiptMaterial material = validationReceiptMaterial(context);
    byte[] signature = new byte[64];
    for (int index = 0; index < signature.length; index++) {
      signature[index] = (byte) (index + 1);
    }
    assertFalse(java.util.Arrays.equals(signature, material.originalSignature()));
    String signatureHash = sha256Hex(signature);
    String receiptHash =
        canonicalHash(
            "emergeos.exact-provider-validation-receipt.v16",
            material.protocolVersion(),
            material.principalId(),
            material.attemptId(),
            material.manifestHash(),
            material.requirementHash(),
            material.transcriptHash(),
            signatureHash,
            Long.toString(material.validatedAtEpochMicros()),
            Long.toString(material.consumedAtEpochMicros()),
            "CONSUMED",
            material.statementHash(),
            material.attributionHash(),
            material.eventHash(),
            material.overlayHeadHash());
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL session_replication_role = replica");
      int updated =
          JdbcClient.create(new SingleConnectionDataSource(connection, true))
              .sql(
                  """
                  UPDATE public.agent_graph_exact_provider_validations_v16
                  SET signature = :signature,
                      signature_hash = CAST(:signatureHash AS char(64)),
                      validation_receipt_hash = CAST(:receiptHash AS char(64))
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                  """)
              .param("signature", signature)
              .param("signatureHash", signatureHash)
              .param("receiptHash", receiptHash)
              .param("principalId", context.manifest().principalId())
              .param("attemptId", context.manifest().attemptId())
              .update();
      assertEquals(1, updated);
      connection.commit();
    } finally {
      java.util.Arrays.fill(signature, (byte) 0);
    }
  }

  private static ValidationReceiptMaterial validationReceiptMaterial(
      AcceptanceContext context) {
    return JdbcClient.create(context.admin())
        .sql(
            """
            SELECT protocol_version, principal_id,
                   btrim(attempt_id)::text AS attempt_id,
                   btrim(manifest_hash)::text AS manifest_hash,
                   btrim(requirement_hash)::text AS requirement_hash,
                   btrim(transcript_hash)::text AS transcript_hash,
                   signature,
                   validated_at_epoch_micros,
                   consumed_at_epoch_micros,
                   btrim(statement_hash)::text AS statement_hash,
                   btrim(attribution_hash)::text AS attribution_hash,
                   btrim(event_hash)::text AS event_hash,
                   btrim(overlay_head_hash)::text AS overlay_head_hash
            FROM public.agent_graph_exact_provider_validations_v16
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", context.manifest().principalId())
        .param("attemptId", context.manifest().attemptId())
        .query(
            (row, ignored) ->
                new ValidationReceiptMaterial(
                    row.getString("protocol_version"),
                    row.getString("principal_id"),
                    row.getString("attempt_id"),
                    row.getString("manifest_hash"),
                    row.getString("requirement_hash"),
                    row.getString("transcript_hash"),
                    row.getBytes("signature"),
                    row.getLong("validated_at_epoch_micros"),
                    row.getLong("consumed_at_epoch_micros"),
                    row.getString("statement_hash"),
                    row.getString("attribution_hash"),
                    row.getString("event_hash"),
                    row.getString("overlay_head_hash")))
        .single();
  }

  private static void restoreOverlayValidation(AcceptanceContext context)
      throws SQLException {
    try (Connection connection = context.admin().getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL session_replication_role = replica");
      assertEquals(
          1,
          statement.executeUpdate(
              """
              UPDATE public.agent_graph_exact_provider_validations_v16 target
              SET signature = backup.signature,
                  signature_hash = backup.signature_hash,
                  validation_receipt_hash = backup.validation_receipt_hash
              FROM pack010_v19_overlay_backup.agent_graph_exact_provider_validations_v16
                backup
              WHERE target.principal_id = backup.principal_id
                AND target.attempt_id = backup.attempt_id
              """));
      connection.commit();
    }
  }

  private static void dropOverlayBackup(DataSource admin) {
    try {
      execute(
          admin,
          "DROP SCHEMA IF EXISTS pack010_v19_overlay_backup CASCADE");
    } catch (SQLException failure) {
      throw new IllegalStateException("OVERLAY_BACKUP_DROP_FAILED");
    }
  }

  private static void assertNoOverlayMarker(
      DataSource admin, GraphAttemptManifest manifest) {
    JdbcClient jdbc = JdbcClient.create(admin);
    long requirements =
        jdbc.sql(
                """
                SELECT count(*)
                FROM public.agent_graph_exact_tx_a_requirements_v15
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single();
    long overlayRows =
        jdbc.sql(
                """
                SELECT pg_catalog.sum(row_count)
                FROM (
                  SELECT count(*) AS row_count
                  FROM public.agent_graph_exact_provider_validations_v16
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                  UNION ALL
                  SELECT count(*)
                  FROM public.agent_graph_exact_provider_attributions_v16
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                  UNION ALL
                  SELECT count(*)
                  FROM public.agent_graph_exact_attempt_events_v16
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                  UNION ALL
                  SELECT count(*)
                  FROM public.agent_graph_exact_attempt_heads_v16
                  WHERE principal_id = :principalId
                    AND attempt_id = :attemptId
                ) rows
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single();
    assertEquals(0L, requirements);
    assertEquals(0L, overlayRows);
  }

  private static SyntheticProfile insertSyntheticProfile(
      DataSource admin, int repetition) {
    String prefix = "pack010-v19-r" + repetition;
    String profileId = prefix + "-synthetic-exact-pico-v1";
    String transportProfileId = prefix + "-responses-http-v1";
    String parserProfileId = prefix + "-responses-parser-v1";
    String schemaProfileId = prefix + "-structured-final-v1";
    String modelProfileId = prefix + "-gpt-5.6-terra-v1";
    String transportProfileHash =
        IntegrityHashes.utf8ContentHash(transportProfileId);
    String parserProfileHash =
        IntegrityHashes.utf8ContentHash(parserProfileId);
    String schemaProfileHash =
        IntegrityHashes.utf8ContentHash(schemaProfileId);
    String profileHash =
        JdbcClient.create(admin)
        .sql(
            """
            INSERT INTO agent_graph_provider_profiles_v14 (
              profile_id, provider_id, provider_protocol,
              transport_profile_id, transport_profile_hash,
              parser_profile_id, parser_profile_hash,
              schema_profile_id, schema_profile_hash,
              model_profile_id, model_profile_hash,
              model_requested, model_resolution_profile_hash,
              pricing_profile_id, pricing_provider_id,
              pricing_profile_fingerprint, pricing_source_hash,
              pricing_effective_from_epoch_micros,
              pricing_effective_until_epoch_micros,
              rate_unit,
              uncached_input_pico_usd_per_token,
              cached_input_pico_usd_per_token,
              output_pico_usd_per_token,
              reasoning_policy, status
            ) VALUES (
              :profileId, 'pack010-synthetic',
              'pack010.synthetic.responses',
              :transportProfileId, :transportProfileHash,
              :parserProfileId, :parserProfileHash,
              :schemaProfileId, :schemaProfileHash,
              :modelProfileId, :modelProfileHash,
              'gpt-5.6-terra', :modelResolutionProfileHash,
              :pricingProfileId, 'pack010-synthetic',
              :pricingProfileFingerprint, :pricingSourceHash,
              floor(extract(epoch from clock_timestamp()
                - interval '1 minute') * 1000000)::bigint,
              floor(extract(epoch from clock_timestamp()
                + interval '10 minutes') * 1000000)::bigint,
              'PICO_USD_PER_TOKEN', 140000, 2800, 280000,
              'NONE', 'ACTIVE'
            )
            RETURNING btrim(profile_hash)::text
            """)
        .param("profileId", profileId)
        .param("transportProfileId", transportProfileId)
        .param("transportProfileHash", transportProfileHash)
        .param("parserProfileId", parserProfileId)
        .param("parserProfileHash", parserProfileHash)
        .param("schemaProfileId", schemaProfileId)
        .param("schemaProfileHash", schemaProfileHash)
        .param("modelProfileId", modelProfileId)
        .param(
            "modelProfileHash",
            IntegrityHashes.utf8ContentHash(modelProfileId))
        .param(
            "modelResolutionProfileHash",
            IntegrityHashes.utf8ContentHash(
                prefix + "-gpt-5.6-terra-resolution-v1"))
        .param("pricingProfileId", prefix + "-exact-pico-v1")
        .param(
            "pricingProfileFingerprint",
            IntegrityHashes.utf8ContentHash(
                prefix + "-exact-pico-v1"))
        .param(
            "pricingSourceHash",
            IntegrityHashes.utf8ContentHash(
                prefix + "-local-price-source-v1"))
        .query(String.class)
        .single();
    return new SyntheticProfile(
        profileId,
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash,
        profileHash);
  }

  private static String requireExactTxA(
      DataSource v15DataSource,
      GraphAttemptManifest manifest,
      String profileId) {
    return JdbcClient.create(v15DataSource)
        .sql(
            """
            SELECT btrim(public.agent_graph_require_exact_tx_a_v15(
              CAST(:principalId AS varchar),
              CAST(:attemptId AS char(64)),
              CAST(:manifestHash AS char(64)),
              CAST(:profileId AS varchar)))::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("profileId", profileId)
        .query(String.class)
        .single();
  }

  private static void assertRequiredMarkerOnly(
      DataSource admin,
      GraphAttemptManifest manifest,
      String profileHash,
      String requirementHash) {
    assertEquals(
        1L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM public.agent_graph_exact_tx_a_requirements_v15
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND manifest_hash = CAST(:manifestHash AS char(64))
                  AND protocol_version = 'PICO_OVERLAY_V1'
                  AND base_sequence = 13
                  AND request_ordinal = 2
                  AND provider_profile_id = :profileId
                  AND provider_profile_hash = CAST(:profileHash AS char(64))
                  AND state = 'REQUIRED'
                  AND requirement_hash = CAST(:requirementHash AS char(64))
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .param("manifestHash", manifest.manifestHash())
            .param("profileId", R2_PROFILE_ID)
            .param("profileHash", profileHash)
            .param("requirementHash", requirementHash)
            .query(Long.class)
            .single());
    assertV13ValidationCount(admin, manifest, 0L);
    assertEquals(0L, overlayRowCount(admin, manifest));
  }

  private static void assertV13ValidationCount(
      DataSource admin, GraphAttemptManifest manifest, long expected) {
    assertEquals(
        expected,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM public.agent_graph_provider_validations
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());
  }

  private static long overlayRowCount(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT pg_catalog.sum(row_count)
            FROM (
              SELECT count(*) AS row_count
              FROM public.agent_graph_exact_provider_validations_v16
              WHERE principal_id = :principalId
                AND attempt_id = :attemptId
              UNION ALL
              SELECT count(*)
              FROM public.agent_graph_exact_provider_attributions_v16
              WHERE principal_id = :principalId
                AND attempt_id = :attemptId
              UNION ALL
              SELECT count(*)
              FROM public.agent_graph_exact_attempt_events_v16
              WHERE principal_id = :principalId
                AND attempt_id = :attemptId
              UNION ALL
              SELECT count(*)
              FROM public.agent_graph_exact_attempt_heads_v16
              WHERE principal_id = :principalId
                AND attempt_id = :attemptId
            ) rows
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static long legacySequence14Count(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT count(*)
            FROM public.agent_graph_attempt_events
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND sequence = 14
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static void insertProviderValidationKey(
      DataSource admin, String keyId, KeyPair keyPair)
      throws GeneralSecurityException {
    byte[] publicKey = keyPair.getPublic().getEncoded();
    String fingerprint = sha256Hex(publicKey);
    assertEquals(
        1,
        JdbcClient.create(admin)
            .sql(
                """
                INSERT INTO public.agent_graph_provider_validation_keys (
                  key_id, algorithm, public_key_der, key_fingerprint,
                  status, not_before, not_after
                ) VALUES (
                  :keyId, 'ED25519', :publicKey, :fingerprint,
                  'ACTIVE', clock_timestamp() - interval '1 minute',
                  clock_timestamp() + interval '10 minutes'
                )
                """)
            .param("keyId", keyId)
            .param("publicKey", publicKey)
            .param("fingerprint", fingerprint)
            .update());
  }

  private static void setAuthorityStatus(
      DataSource admin,
      String profileId,
      String keyId,
      String status) {
    if (!Set.of("ACTIVE", "REVOKED").contains(status)) {
      throw new IllegalArgumentException("AUTHORITY_STATUS_INVALID");
    }
    JdbcClient jdbc = JdbcClient.create(admin);
    assertEquals(
        1,
        jdbc.sql(
                """
                UPDATE public.agent_graph_provider_profiles_v14
                SET status = :status
                WHERE profile_id = :profileId
                """)
            .param("status", status)
            .param("profileId", profileId)
            .update());
    assertEquals(
        1,
        jdbc.sql(
                """
                UPDATE public.agent_graph_provider_validation_keys
                SET status = :status
                WHERE key_id = :keyId
                """)
            .param("status", status)
            .param("keyId", keyId)
            .update());
  }

  private static void waitUntilExpired(
      DataSource admin, long expiresAtEpochMicros) {
    long remainingMicros =
        Math.max(0L, expiresAtEpochMicros - databaseEpochMicros(admin));
    long waitMillis =
        Math.max(1L, Math.floorDiv(remainingMicros + 99_999L, 1_000L));
    assertTrue(waitMillis <= 5_500L);
    try {
      Thread.sleep(waitMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("V19_EXPIRY_WAIT_INTERRUPTED");
    }
    assertTrue(databaseEpochMicros(admin) >= expiresAtEpochMicros);
  }

  private static long databaseEpochMicros(DataSource admin) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT floor(extract(epoch FROM clock_timestamp())
              * 1000000)::bigint
            """)
        .query(Long.class)
        .single();
  }

  private static byte[] sign(KeyPair keyPair, byte[] material) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(material);
      return signer.sign();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("V19_TEST_SIGNING_FAILED");
    }
  }

  private static String canonicalHash(String domain, String... fields) {
    return sha256Hex(frame(domain, fields));
  }

  private static byte[] frame(String domain, String... fields) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    framed.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    framed.write(0);
    for (String field : fields) {
      byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(
          ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
      framed.writeBytes(encoded);
    }
    return framed.toByteArray();
  }

  private static String sha256Hex(byte[] material) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("SHA_256_UNAVAILABLE");
    }
  }

  private static void assertExactReaderRole(
      DataSource admin, String readerPassword) throws SQLException {
    JdbcClient jdbc = JdbcClient.create(admin);
    RoleShape role =
        jdbc.sql(
                """
                SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
                       rolinherit, rolreplication, rolbypassrls
                FROM pg_catalog.pg_roles
                WHERE rolname = :role
                """)
            .param("role", READER_ROLE)
            .query(
                (row, ignored) ->
                    new RoleShape(
                        row.getBoolean("rolcanlogin"),
                        row.getBoolean("rolsuper"),
                        row.getBoolean("rolcreatedb"),
                        row.getBoolean("rolcreaterole"),
                        row.getBoolean("rolinherit"),
                        row.getBoolean("rolreplication"),
                        row.getBoolean("rolbypassrls")))
            .single();
    assertEquals(
        new RoleShape(true, false, false, false, false, false, false),
        role);
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_auth_members membership
                JOIN pg_catalog.pg_roles member
                  ON member.oid = membership.member
                JOIN pg_catalog.pg_roles granted_role
                  ON granted_role.oid = membership.roleid
                WHERE member.rolname = :role
                   OR granted_role.rolname = :role
                """)
            .param("role", READER_ROLE)
            .query(Long.class)
            .single());
    Set<String> selectable =
        Set.copyOf(
            jdbc.sql(
                    """
                    SELECT relation.relname
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p')
                      AND pg_catalog.has_table_privilege(
                        :role, relation.oid, 'SELECT')
                    ORDER BY relation.relname
                    """)
                .param("role", READER_ROLE)
                .query(String.class)
                .list());
    assertEquals(READER_TABLES, selectable);
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relkind IN ('r', 'p')
                  AND pg_catalog.has_table_privilege(
                    :role, relation.oid,
                    'SELECT WITH GRANT OPTION,INSERT,UPDATE,DELETE,'
                      || 'TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                """)
            .param("role", READER_ROLE)
            .query(Long.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                WITH exact_sequences AS MATERIALIZED (
                  SELECT relation.oid
                  FROM pg_catalog.pg_sequences sequence
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.nspname = sequence.schemaname
                  JOIN pg_catalog.pg_class relation
                    ON relation.relnamespace = namespace.oid
                   AND relation.relname = sequence.sequencename
                   AND relation.relkind = 'S'
                  WHERE sequence.schemaname = 'public'
                )
                SELECT count(*)
                FROM exact_sequences sequence
                WHERE pg_catalog.has_sequence_privilege(
                  :role, sequence.oid, 'USAGE,SELECT,UPDATE')
                """)
            .param("role", READER_ROLE)
            .query(Long.class)
            .single());

    try (Connection connection =
        dataSource(READER_ROLE, readerPassword).getConnection()) {
      connection.setAutoCommit(false);
      assertEquals(
          "off",
          JdbcClient.create(new SingleConnectionDataSource(connection, true))
              .sql("SELECT pg_catalog.current_setting('transaction_read_only')")
              .query(String.class)
              .single());
      connection.rollback();
    }
  }

  private static void configureReader(
      DataSource admin, String readerPassword) throws SQLException {
    setEphemeralPassword(admin, READER_ROLE, readerPassword);
  }

  private static DatabaseImage databaseImage(DataSource source) {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      JdbcClient jdbc =
          JdbcClient.create(new SingleConnectionDataSource(connection, true));
      Map<String, TableImage> tables = new LinkedHashMap<>();
      List<String> tableNames =
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
      for (String table : tableNames) {
        if (!table.matches("[a-z][a-z0-9_]{0,62}")) {
          throw new IllegalStateException("PUBLIC_TABLE_NAME_INVALID");
        }
        TableImage image =
            jdbc.sql(
                    """
                    SELECT count(*) AS row_count,
                           pg_catalog.encode(
                             pg_catalog.sha256(pg_catalog.convert_to(
                               COALESCE(pg_catalog.string_agg(
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
                        new TableImage(
                            row.getLong("row_count"),
                            row.getString("row_digest")))
                .single();
        tables.put(table, image);
      }
      connection.rollback();
      return new DatabaseImage(Map.copyOf(tables));
    } catch (SQLException failure) {
      throw new IllegalStateException("DATABASE_IMAGE_FAILED");
    }
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!(WRITER_ROLE.equals(role)
        || V13_ROLE.equals(role)
        || V15_ROLE.equals(role)
        || V16_ROLE.equals(role)
        || READER_ROLE.equals(role))) {
      throw new IllegalArgumentException("ROLE_NOT_ALLOWLISTED");
    }
    try (Connection connection = admin.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement setting =
              connection.prepareStatement(
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
      throw new IllegalStateException("EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
    }
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

  private static String resource(String name) {
    try (InputStream input =
        Pack010ExactPicoOverlayReaderAcceptanceIT.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("PACK010_PROVISIONING_RESOURCE_MISSING");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("PACK010_PROVISIONING_RESOURCE_READ_FAILED");
    }
  }

  private static DataSource dataSource(String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(jdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static String jdbcUrl() {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(5432)
        + "/"
        + POSTGRES.getDatabaseName();
  }

  private static Path javaExecutable() {
    return Path.of(
        System.getProperty("java.home"), "bin", "java");
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record ProcessReceipt(int exitCode, String output) {}

  private record SyntheticProfile(
      String profileId,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      String profileHash) {}

  private record AcceptanceContext(
      DataSource admin,
      PostgresGraphAttemptStore writer,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      SyntheticProfile profile,
      KeyPair keyPair,
      String keyId,
      String requirementHash,
      DataSource v16DataSource,
      String readerPassword,
      List<String> secrets,
      long expiresAtEpochMicros) {

    private AcceptanceContext withExpiresAtEpochMicros(long value) {
      return new AcceptanceContext(
          admin,
          writer,
          manifest,
          sequence13,
          profile,
          keyPair,
          keyId,
          requirementHash,
          v16DataSource,
          readerPassword,
          secrets,
          value);
    }
  }

  private record ValidationReceiptMaterial(
      String protocolVersion,
      String principalId,
      String attemptId,
      String manifestHash,
      String requirementHash,
      String transcriptHash,
      byte[] originalSignature,
      long validatedAtEpochMicros,
      long consumedAtEpochMicros,
      String statementHash,
      String attributionHash,
      String eventHash,
      String overlayHeadHash) {

    private ValidationReceiptMaterial {
      originalSignature = originalSignature.clone();
    }

    @Override
    public byte[] originalSignature() {
      return originalSignature.clone();
    }
  }

  private record RoleShape(
      boolean login,
      boolean superuser,
      boolean createDatabase,
      boolean createRole,
      boolean inherit,
      boolean replication,
      boolean bypassRls) {}

  private record DatabaseImage(Map<String, TableImage> tables) {}

  private record TableImage(long rowCount, String rowDigest) {}
}
