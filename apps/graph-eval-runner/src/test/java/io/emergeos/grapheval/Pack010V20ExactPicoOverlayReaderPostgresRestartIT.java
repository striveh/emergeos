package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresExactPicoProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Acceptance for V20 PostgreSQL restart readback of a V19 overlay. */
@Testcontainers
class Pack010V20ExactPicoOverlayReaderPostgresRestartIT {

  private static final int REPETITION = 3;
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final String WRITER_ROLE = "emergeos_graph_prefix_writer";
  private static final String V13_ROLE = "emergeos_provider_attestor";
  private static final String V15_ROLE = "emergeos_provider_attestor_v15";
  private static final String V16_ROLE = "emergeos_provider_attestor_v16";
  private static final String READER_ROLE =
      "emergeos_exact_overlay_reader_v19";

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

  @Test
  void committedOverlayIsReverifiedAfterObservedPostgresRestart()
      throws Exception {
    DataSource admin =
        dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);
    assertKeepalivePidOneTopology();

    String writerPassword = UUID.randomUUID().toString();
    String v13Password = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    String v16Password = UUID.randomUUID().toString();
    String readerPassword = UUID.randomUUID().toString();
    List<String> secrets =
        List.of(
            writerPassword,
            v13Password,
            v15Password,
            v16Password,
            readerPassword,
            POSTGRES.getPassword());

    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource("db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(admin, WRITER_ROLE, writerPassword);
    setEphemeralPassword(admin, V13_ROLE, v13Password);
    setEphemeralPassword(admin, V15_ROLE, v15Password);
    setEphemeralPassword(admin, V16_ROLE, v16Password);
    setEphemeralPassword(admin, READER_ROLE, readerPassword);

    SyntheticProfile profile = insertSyntheticProfile(admin);
    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String keyId = "pack010-v19-r3-ed25519-key";
    insertProviderValidationKey(admin, keyId, keyPair);

    DataSource writerDataSource = dataSource(WRITER_ROLE, writerPassword);
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(REPETITION);
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, REPETITION);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        manifest,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        Pack010GraphTerminalFixture.catalogIntent(REPETITION, 1),
        Instant.now().plus(Duration.ofMinutes(5)));
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
                Duration.ofSeconds(5));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, REPETITION);
    String requirementHash =
        requireExactTxA(
            dataSource(V15_ROLE, v15Password),
            manifest,
            profile.profileId());
    assertTrue(policyHash.matches("[0-9a-f]{64}"));
    assertTrue(requirementHash.matches("[0-9a-f]{64}"));
    assertEquals(13, sequence13.cursor().lastSequence());

    GraphProviderIntent request =
        Pack010GraphTerminalFixture.catalogIntent(REPETITION, 2);
    GraphProviderAttribution attribution =
        Pack010GraphTerminalFixture.catalogAttribution(REPETITION, 2);
    GraphExactPicoProviderValidationCommand command =
        new GraphExactPicoProviderValidationCommand(
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            requirementHash,
            sequence13.cursor().headHash(),
            request.requestHash(),
            attribution.responseHash(),
            IntegrityHashes.utf8ContentHash(attribution.modelResolved()),
            2L,
            1L,
            1L,
            IntegrityHashes.utf8ContentHash(
                "pack010-v19-structured-final-r3"),
            keyId,
            Duration.ofSeconds(5));
    GraphExactPicoProviderValidationReceipt durableReceipt =
        PostgresExactPicoProviderValidationAttestor.open(
                dataSource(V16_ROLE, v16Password))
            .complete(
                command,
                challenge ->
                    HexFormat.of().formatHex(
                        sign(keyPair, challenge.signatureMaterial())));
    assertEquals(14, durableReceipt.overlaySequence());
    assertEquals(4L, overlayRowCount(admin, manifest));
    assertEquals(0L, legacySequence14Count(admin, manifest));

    DatabaseImage durableImage = databaseImage(admin);
    GraphAttemptSnapshot legacyBefore =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    OverlayIdentity durableIdentity = overlayIdentity(admin, manifest);
    assertEquals(
        OverlayIdentity.from(durableReceipt), durableIdentity);
    String containerBefore = POSTGRES.getContainerId();
    String systemIdentifierBefore = systemIdentifier(admin);
    Instant postmasterBeforeRead = postmasterStartTime(admin);
    ProcessReceipt freshJvmA = runFreshV19Reader(readerPassword);
    Instant postmasterBeforeRestart = postmasterStartTime(admin);

    assertEquals(durableImage, databaseImage(admin));
    GraphAttemptSnapshot legacyAfterA =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfterA);
    assertEquals(13, legacyAfterA.cursor().lastSequence());
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertEquals(4L, overlayRowCount(admin, manifest));
    assertEquals(durableIdentity, overlayIdentity(admin, manifest));
    assertSecretFree(freshJvmA.output(), secrets);
    assertEquals(0, freshJvmA.exitCode(), freshJvmA.output());
    assertEquals(
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=ATTRIBUTED"
            + " repetition=3 legacySequence=13",
        freshJvmA.output());
    assertEquals(
        postmasterBeforeRead,
        postmasterBeforeRestart,
        "V20_PRE_RESTART_FRESH_READ_CHANGED_POSTMASTER");

    RestartObservation restart;
    try (Connection sentinel = admin.getConnection()) {
      int sentinelBackend = backendPid(sentinel);
      restart = restartPostgres(admin, sentinel, sentinelBackend);
    }
    assertTrue(POSTGRES.isRunning(), "V20_CONTAINER_STOPPED_DURING_RESTART");
    assertEquals(
        containerBefore,
        POSTGRES.getContainerId(),
        "V20_CONTAINER_ID_CHANGED_DURING_RESTART");
    assertEquals(
        systemIdentifierBefore,
        restart.systemIdentifier(),
        "V20_SYSTEM_IDENTIFIER_CHANGED_DURING_RESTART");
    assertTrue(
        restart.postmasterStartedAt().isAfter(postmasterBeforeRestart),
        "V20_POSTMASTER_START_DID_NOT_ADVANCE_AFTER_RESTART");
    assertKeepalivePidOneTopology();

    // No migration, provisioning or fixture writer is called after restart.
    assertEquals(durableImage, databaseImage(admin));
    GraphAttemptSnapshot legacyAfterRestart =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfterRestart);
    assertEquals(13, legacyAfterRestart.cursor().lastSequence());
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertEquals(4L, overlayRowCount(admin, manifest));
    assertEquals(durableIdentity, overlayIdentity(admin, manifest));

    ProcessReceipt freshJvmB = runFreshV19Reader(readerPassword);
    assertTrue(
        freshJvmA.pid() != freshJvmB.pid(),
        "V20_FRESH_VERIFIER_OS_PID_WAS_REUSED");
    assertSecretFree(freshJvmB.output(), secrets);
    assertEquals(0, freshJvmB.exitCode(), freshJvmB.output());
    assertEquals(freshJvmA.output(), freshJvmB.output());

    assertEquals(durableImage, databaseImage(admin));
    GraphAttemptSnapshot legacyAfterB =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfterB);
    assertEquals(13, legacyAfterB.cursor().lastSequence());
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertEquals(4L, overlayRowCount(admin, manifest));
    assertEquals(durableIdentity, overlayIdentity(admin, manifest));

    System.out.println(
        "PACK010_V20_OVERLAY_RESTART version=1"
            + " fixture=V13_V15_V18_ATTRIBUTED"
            + " postgresRestart=IMMEDIATE restartExit=0"
            + " keepalivePid1=true containerIdentity=SAME"
            + " systemIdentifier=SAME postmasterChanged=true"
            + " oldSentinel=DISCONNECTED newConnection=READY"
            + " freshVerifierJvms=2 verifierPidsDistinct=true"
            + " verdict=ATTRIBUTED durableReceiptIdentity=UNCHANGED"
            + " publicTableJsonXmin=UNCHANGED"
            + " legacySequence=13 legacySequence14=0 overlayRows=4"
            + " postRestartMigrateProvisionFixture=0"
            + " shippingLiveRoute=DISABLED");
  }

  private static ProcessReceipt runFreshV19Reader(String readerPassword)
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
            Integer.toString(REPETITION),
            "ATTRIBUTED",
            jdbcUrl(),
            READER_ROLE,
            appJar.toString(),
            testClasses.toString());
    builder.environment().clear();
    builder.redirectErrorStream(true);
    Process process = builder.start();
    long processId = process.pid();
    byte[] secret = readerPassword.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream input =
        new DataOutputStream(process.getOutputStream())) {
      input.writeInt(secret.length);
      input.write(secret);
      input.flush();
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
    if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(
          process.waitFor(5, TimeUnit.SECONDS),
          "V20_PRE_RESTART_FRESH_JVM_DID_NOT_STOP");
      throw new IllegalStateException("V20_PRE_RESTART_FRESH_JVM_TIMEOUT");
    }
    assertFalse(process.isAlive(), "V20_FRESH_VERIFIER_REMAINED_ACTIVE");
    String output =
        new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .trim();
    return new ProcessReceipt(processId, process.exitValue(), output);
  }

  private static SyntheticProfile insertSyntheticProfile(DataSource admin) {
    String prefix = "pack010-v19-r3";
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
    assertTrue(profileHash.matches("[0-9a-f]{64}"));
    return new SyntheticProfile(
        profileId,
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash);
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

  private static OverlayIdentity overlayIdentity(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT validation.protocol_version,
                   validation.overlay_sequence,
                   validation.state_version,
                   btrim(validation.overlay_head_hash)::text
                     AS overlay_head_hash,
                   btrim(validation.statement_hash)::text
                     AS statement_hash,
                   btrim(validation.attribution_hash)::text
                     AS attribution_hash,
                   btrim(validation.event_hash)::text AS event_hash,
                   btrim(validation.transcript_hash)::text
                     AS transcript_hash,
                   btrim(validation.validation_receipt_hash)::text
                     AS validation_receipt_hash,
                   validation.state
            FROM public.agent_graph_exact_provider_validations_v16 validation
            JOIN public.agent_graph_exact_provider_attributions_v16 attribution
              ON attribution.principal_id = validation.principal_id
             AND attribution.attempt_id = validation.attempt_id
             AND attribution.statement_hash = validation.statement_hash
             AND attribution.attribution_hash = validation.attribution_hash
            JOIN public.agent_graph_exact_attempt_events_v16 event
              ON event.principal_id = validation.principal_id
             AND event.attempt_id = validation.attempt_id
             AND event.statement_hash = validation.statement_hash
             AND event.evidence_hash = validation.attribution_hash
             AND event.event_hash = validation.event_hash
             AND event.current_head_hash = validation.overlay_head_hash
            JOIN public.agent_graph_exact_attempt_heads_v16 head
              ON head.principal_id = validation.principal_id
             AND head.attempt_id = validation.attempt_id
             AND head.statement_hash = validation.statement_hash
             AND head.attribution_hash = validation.attribution_hash
             AND head.last_event_hash = validation.event_hash
             AND head.head_hash = validation.overlay_head_hash
            WHERE validation.principal_id = :principalId
              AND validation.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (row, ignored) ->
                new OverlayIdentity(
                    row.getString("protocol_version"),
                    row.getInt("overlay_sequence"),
                    row.getLong("state_version"),
                    row.getString("overlay_head_hash"),
                    row.getString("statement_hash"),
                    row.getString("attribution_hash"),
                    row.getString("event_hash"),
                    row.getString("transcript_hash"),
                    row.getString("validation_receipt_hash"),
                    row.getString("state")))
        .single();
  }

  private static Instant postmasterStartTime(DataSource source) {
    try (Connection connection = source.getConnection()) {
      return backendIdentity(connection).postmasterStartedAt();
    } catch (SQLException failure) {
      throw new IllegalStateException("V20_POSTMASTER_IDENTITY_READ_FAILED");
    }
  }

  private static String systemIdentifier(DataSource source) {
    try (Connection connection = source.getConnection()) {
      return backendIdentity(connection).systemIdentifier();
    } catch (SQLException failure) {
      throw new IllegalStateException("V20_SYSTEM_IDENTITY_READ_FAILED");
    }
  }

  private static int backendPid(Connection connection) throws SQLException {
    return backendIdentity(connection).backendPid();
  }

  private static BackendIdentity backendIdentity(Connection connection)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                """
                SELECT pg_catalog.pg_backend_pid() AS backend_pid,
                       pg_catalog.pg_postmaster_start_time()
                         AS postmaster_started_at,
                       control.system_identifier::text
                         AS system_identifier
                FROM pg_catalog.pg_control_system() control
                """)) {
      assertTrue(result.next(), "V20_BACKEND_IDENTITY_MISSING");
      BackendIdentity identity =
          new BackendIdentity(
              result.getInt("backend_pid"),
              result
                  .getObject("postmaster_started_at", OffsetDateTime.class)
                  .toInstant(),
              result.getString("system_identifier"));
      assertFalse(result.next(), "V20_BACKEND_IDENTITY_AMBIGUOUS");
      return identity;
    }
  }

  private static RestartObservation restartPostgres(
      DataSource probe, Connection sentinel, int sentinelBackend)
      throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart"
                + " -D \"$PGDATA\" -m immediate -w");
    assertEquals(
        0,
        restart.getExitCode(),
        "V20_POSTGRES_IMMEDIATE_RESTART_FAILED");
    assertThrows(
        SQLException.class,
        () -> backendIdentity(sentinel),
        "V20_PRE_RESTART_SENTINEL_REMAINED_USABLE");

    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try (Connection recovered = probe.getConnection()) {
        BackendIdentity identity = backendIdentity(recovered);
        assertTrue(
            identity.backendPid() != sentinelBackend,
            "V20_RECOVERED_BACKEND_PID_DID_NOT_CHANGE");
        return new RestartObservation(
            identity.postmasterStartedAt(),
            identity.systemIdentifier());
      } catch (SQLException unavailable) {
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("V20_POSTGRES_RESTART_READINESS_TIMEOUT");
  }

  private static DatabaseImage databaseImage(DataSource source) {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_REPEATABLE_READ);
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
          throw new IllegalStateException("V20_PUBLIC_TABLE_NAME_INVALID");
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
      throw new IllegalStateException("V20_DATABASE_IMAGE_FAILED");
    }
  }

  private static void assertKeepalivePidOneTopology() throws Exception {
    var topology =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "test \"$(cat /proc/1/comm)\" = sh"
                + " && test \"$(sed -n '1p' \"$PGDATA/postmaster.pid\")\""
                + " != 1");
    assertEquals(
        0,
        topology.getExitCode(),
        "V20_KEEPALIVE_PID1_TOPOLOGY_INVALID");
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!List.of(
            WRITER_ROLE, V13_ROLE, V15_ROLE, V16_ROLE, READER_ROLE)
        .contains(role)) {
      throw new IllegalArgumentException("V20_ROLE_NOT_ALLOWLISTED");
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
      throw new IllegalStateException(
          "V20_EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
    }
  }

  private static byte[] sign(KeyPair keyPair, byte[] material) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(material);
      return signer.sign();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("V20_TEST_SIGNING_FAILED");
    }
  }

  private static String sha256Hex(byte[] material) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("V20_SHA_256_UNAVAILABLE");
    }
  }

  private static void assertSecretFree(
      String output, List<String> secrets) {
    for (String secret : secrets) {
      assertFalse(output.contains(secret));
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
        Pack010V20ExactPicoOverlayReaderPostgresRestartIT.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException(
            "V20_PROVISIONING_RESOURCE_MISSING");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "V20_PROVISIONING_RESOURCE_READ_FAILED");
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
    return Path.of(System.getProperty("java.home"), "bin", "java");
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record ProcessReceipt(long pid, int exitCode, String output) {}

  private record BackendIdentity(
      int backendPid,
      Instant postmasterStartedAt,
      String systemIdentifier) {}

  private record RestartObservation(
      Instant postmasterStartedAt, String systemIdentifier) {}

  private record OverlayIdentity(
      String protocolVersion,
      int overlaySequence,
      long overlayStateVersion,
      String overlayHeadHash,
      String statementHash,
      String attributionHash,
      String eventHash,
      String transcriptHash,
      String validationReceiptHash,
      String validationState) {

    private static OverlayIdentity from(
        GraphExactPicoProviderValidationReceipt receipt) {
      return new OverlayIdentity(
          receipt.protocolVersion(),
          receipt.overlaySequence(),
          receipt.overlayStateVersion(),
          receipt.overlayHeadHash(),
          receipt.statementHash(),
          receipt.attributionHash(),
          receipt.eventHash(),
          receipt.transcriptHash(),
          receipt.validationReceiptHash(),
          receipt.validationState().toString());
    }
  }

  private record SyntheticProfile(
      String profileId,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash) {}

  private record DatabaseImage(Map<String, TableImage> tables) {}

  private record TableImage(long rowCount, String rowDigest) {}
}
