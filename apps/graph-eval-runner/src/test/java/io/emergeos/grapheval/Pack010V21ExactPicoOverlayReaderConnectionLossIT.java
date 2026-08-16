package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
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

/** Acceptance for V21 in-flight V19 overlay read connection loss. */
@Testcontainers
class Pack010V21ExactPicoOverlayReaderConnectionLossIT {

  private static final int REPETITION = 3;
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final String WRITER_ROLE = "emergeos_graph_prefix_writer";
  private static final String V13_ROLE = "emergeos_provider_attestor";
  private static final String V15_ROLE = "emergeos_provider_attestor_v15";
  private static final String V16_ROLE = "emergeos_provider_attestor_v16";
  private static final String READER_ROLE =
      "emergeos_exact_overlay_reader_v19";
  private static final String FAULT_MAIN =
      "io.emergeos.grapheval."
          + "Pack010V21ExactPicoOverlayReaderConnectionLossMain";
  private static final int FAULT_EXIT_CODE = 21;
  private static final String FAULT_RECEIPT =
      "PACK010_V21_EXACT_OVERLAY_CONNECTION_LOSS version=1"
          + " verdict=FAIL_CLOSED cause=GRAPH_ATTEMPT_INTEGRITY";

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
  void inFlightFinalOverlayReadFailsClosedAndFreshJvmRecovers()
      throws Exception {
    DataSource admin =
        dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

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

    ProcessReceipt freshJvmA = runFreshV19Reader(readerPassword);
    assertSecretFree(freshJvmA.output(), secrets);
    assertEquals(0, freshJvmA.exitCode(), freshJvmA.output());
    assertEquals(
        "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict=ATTRIBUTED"
            + " repetition=3 legacySequence=13",
        freshJvmA.output());
    assertDurableImage(
        admin, writer, manifest, durableImage, legacyBefore, durableIdentity);

    // Acceptance Red: the V21 fault child is a string-only surface until the
    // implementation agent adds its separate test-only Main.
    assertFaultMainPresent();

    String applicationName =
        "pack010_v21_fault_"
            + UUID.randomUUID().toString().replace("-", "");
    FaultObservation fault =
        runTerminatedFinalOverlayRead(
            admin, readerPassword, applicationName);
    assertTrue(
        freshJvmA.pid() != fault.child().pid(),
        "V21_FAULT_CHILD_OS_PID_REUSED_BASELINE");
    assertTrue(fault.backendPid() > 0, "V21_FAULT_BACKEND_PID_INVALID");
    assertSecretFree(fault.child().output(), secrets);
    assertEquals(
        FAULT_EXIT_CODE,
        fault.child().exitCode(),
        "V21_FAULT_CHILD_EXIT_CODE_INVALID");
    assertEquals(
        FAULT_RECEIPT,
        fault.child().output(),
        "V21_FAULT_CHILD_RECEIPT_INVALID");
    assertNoFourStateVerdict(fault.child().output());
    assertDurableImage(
        admin, writer, manifest, durableImage, legacyBefore, durableIdentity);

    // No migration, provisioning, fixture writer or automatic retry occurs
    // after the fault. Recovery is an explicit fresh packaged-JVM invocation.
    ProcessReceipt freshJvmC = runFreshV19Reader(readerPassword);
    assertTrue(
        freshJvmA.pid() != freshJvmC.pid(),
        "V21_RECOVERY_OS_PID_REUSED_BASELINE");
    assertTrue(
        fault.child().pid() != freshJvmC.pid(),
        "V21_RECOVERY_OS_PID_REUSED_FAULT_CHILD");
    assertSecretFree(freshJvmC.output(), secrets);
    assertEquals(0, freshJvmC.exitCode(), freshJvmC.output());
    assertEquals(freshJvmA.output(), freshJvmC.output());
    assertDurableImage(
        admin, writer, manifest, durableImage, legacyBefore, durableIdentity);

    System.out.println(
        "PACK010_V21_OVERLAY_CONNECTION_LOSS version=1"
            + " fixture=V13_V15_V18_ATTRIBUTED"
            + " fault=TERMINATE_WAITING_FINAL_OVERLAY_HEAD_READ"
            + " backendBinding=DATABASE_ROLE_APPLICATION_RELATION_WAIT"
            + " terminated=true faultVerdict=NONE failClosed=true"
            + " explicitFreshRecovery=ATTRIBUTED freshVerifierJvms=3"
            + " durableReceiptIdentity=UNCHANGED"
            + " publicTableJsonXmin=UNCHANGED"
            + " legacySequence=13 legacySequence14=0 overlayRows=4"
            + " postFaultMigrateProvisionFixture=0"
            + " shippingLiveRoute=DISABLED");
  }

  private static void assertFaultMainPresent() throws Exception {
    Path testClasses =
        Path.of(requiredProperty("emerge.graph.it.testClasses")).toRealPath();
    Path mainClass =
        testClasses.resolve(FAULT_MAIN.replace('.', '/') + ".class");
    assertTrue(
        Files.isRegularFile(mainClass),
        "V21_FAULT_CHILD_SURFACE_MISSING");
  }

  private static FaultObservation runTerminatedFinalOverlayRead(
      DataSource admin,
      String readerPassword,
      String applicationName) throws Exception {
    Process child = null;
    try (Connection lock = admin.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute(
            """
            LOCK TABLE public.agent_graph_exact_attempt_heads_v16
            IN ACCESS EXCLUSIVE MODE
            """);
      }
      child = startFaultReader(readerPassword, applicationName);
      int backendPid =
          awaitBlockedReaderBackend(admin, child, applicationName);
      terminateExactReaderBackend(admin, backendPid, applicationName);
      awaitBackendGone(admin, backendPid, applicationName);
      ProcessReceipt receipt = awaitFaultReader(child);
      lock.rollback();
      return new FaultObservation(receipt, backendPid);
    } finally {
      if (child != null && child.isAlive()) {
        child.destroyForcibly();
        assertTrue(
            child.waitFor(5, TimeUnit.SECONDS),
            "V21_FAULT_CHILD_CLEANUP_TIMEOUT");
      }
    }
  }

  private static Process startFaultReader(
      String readerPassword, String applicationName) throws Exception {
    if (!applicationName.matches("[a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException(
          "V21_APPLICATION_NAME_INVALID");
    }
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
            FAULT_MAIN,
            "verify-loss",
            Integer.toString(REPETITION),
            jdbcUrl()
                + "?ApplicationName="
                + applicationName,
            READER_ROLE,
            appJar.toString(),
            testClasses.toString());
    builder.environment().clear();
    builder.redirectErrorStream(true);
    Process child = builder.start();
    byte[] secret = readerPassword.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream input =
        new DataOutputStream(child.getOutputStream())) {
      input.writeInt(secret.length);
      input.write(secret);
      input.flush();
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
    return child;
  }

  private static int awaitBlockedReaderBackend(
      DataSource admin, Process child, String applicationName)
      throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      assertTrue(
          child.isAlive(),
          "V21_FAULT_CHILD_EXITED_BEFORE_LOCK_WAIT");
      List<Integer> candidates =
          exactBlockedReaderBackends(admin, applicationName);
      assertTrue(
          candidates.size() <= 1,
          "V21_READER_BACKEND_BINDING_AMBIGUOUS");
      if (candidates.size() == 1) {
        return candidates.getFirst();
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException(
        "V21_READER_BACKEND_LOCK_WAIT_TIMEOUT");
  }

  private static List<Integer> exactBlockedReaderBackends(
      DataSource admin, String applicationName) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT activity.pid
            FROM pg_catalog.pg_stat_activity activity
            JOIN pg_catalog.pg_locks relation_lock
              ON relation_lock.pid = activity.pid
             AND relation_lock.locktype = 'relation'
             AND relation_lock.mode = 'AccessShareLock'
             AND NOT relation_lock.granted
            JOIN pg_catalog.pg_class locked_relation
              ON locked_relation.oid = relation_lock.relation
            JOIN pg_catalog.pg_namespace locked_namespace
              ON locked_namespace.oid = locked_relation.relnamespace
            WHERE activity.datname = :database
              AND activity.usename = :role
              AND activity.application_name = :applicationName
              AND activity.state = 'active'
              AND activity.wait_event_type = 'Lock'
              AND locked_namespace.nspname = 'public'
              AND locked_relation.relname =
                    'agent_graph_exact_attempt_heads_v16'
              AND locked_relation.relkind = 'r'
            ORDER BY activity.pid
            """)
        .param("database", POSTGRES.getDatabaseName())
        .param("role", READER_ROLE)
        .param("applicationName", applicationName)
        .query(Integer.class)
        .list();
  }

  private static void terminateExactReaderBackend(
      DataSource admin, int backendPid, String applicationName) {
    List<Boolean> terminated =
        JdbcClient.create(admin)
            .sql(
                """
                SELECT pg_catalog.pg_terminate_backend(activity.pid)
                FROM pg_catalog.pg_stat_activity activity
                WHERE activity.pid = :backendPid
                  AND activity.datname = :database
                  AND activity.usename = :role
                  AND activity.application_name = :applicationName
                  AND activity.state = 'active'
                  AND activity.wait_event_type = 'Lock'
                  AND EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_locks relation_lock
                        JOIN pg_catalog.pg_class locked_relation
                          ON locked_relation.oid = relation_lock.relation
                        JOIN pg_catalog.pg_namespace locked_namespace
                          ON locked_namespace.oid =
                             locked_relation.relnamespace
                        WHERE relation_lock.pid = activity.pid
                          AND relation_lock.locktype = 'relation'
                          AND relation_lock.mode = 'AccessShareLock'
                          AND NOT relation_lock.granted
                          AND locked_namespace.nspname = 'public'
                          AND locked_relation.relname =
                              'agent_graph_exact_attempt_heads_v16'
                          AND locked_relation.relkind = 'r')
                """)
            .param("backendPid", backendPid)
            .param("database", POSTGRES.getDatabaseName())
            .param("role", READER_ROLE)
            .param("applicationName", applicationName)
            .query(Boolean.class)
            .list();
    assertEquals(
        List.of(Boolean.TRUE),
        terminated,
        "V21_EXACT_READER_BACKEND_TERMINATION_REJECTED");
  }

  private static void awaitBackendGone(
      DataSource admin, int backendPid, String applicationName)
      throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      long remaining =
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_stat_activity activity
                  WHERE activity.pid = :backendPid
                    AND activity.datname = :database
                    AND activity.usename = :role
                    AND activity.application_name = :applicationName
                  """)
              .param("backendPid", backendPid)
              .param("database", POSTGRES.getDatabaseName())
              .param("role", READER_ROLE)
              .param("applicationName", applicationName)
              .query(Long.class)
              .single();
      if (remaining == 0L) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException(
        "V21_TERMINATED_READER_BACKEND_REMAINED_VISIBLE");
  }

  private static ProcessReceipt awaitFaultReader(Process child)
      throws Exception {
    long processId = child.pid();
    if (!child.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      child.destroyForcibly();
      assertTrue(
          child.waitFor(5, TimeUnit.SECONDS),
          "V21_FAULT_CHILD_DID_NOT_STOP");
      throw new IllegalStateException("V21_FAULT_CHILD_TIMEOUT");
    }
    assertFalse(child.isAlive(), "V21_FAULT_CHILD_REMAINED_ACTIVE");
    String output =
        new String(
                child.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .trim();
    return new ProcessReceipt(processId, child.exitValue(), output);
  }

  private static void assertNoFourStateVerdict(String output) {
    String normalized = output.toUpperCase(java.util.Locale.ROOT);
    for (String state :
        List.of("MISSING", "REQUIRED", "ATTRIBUTED", "INVALID")) {
      assertFalse(
          normalized.contains(state),
          "V21_FAULT_CHILD_EMITTED_FOUR_STATE_VERDICT");
    }
  }

  private static void assertDurableImage(
      DataSource admin,
      PostgresGraphAttemptStore writer,
      GraphAttemptManifest manifest,
      DatabaseImage durableImage,
      GraphAttemptSnapshot legacyBefore,
      OverlayIdentity durableIdentity) {
    assertEquals(durableImage, databaseImage(admin));
    GraphAttemptSnapshot legacyAfter =
        Pack010GraphTerminalFixture.verified(writer, manifest);
    assertEquals(legacyBefore, legacyAfter);
    assertEquals(13, legacyAfter.cursor().lastSequence());
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertEquals(4L, overlayRowCount(admin, manifest));
    assertEquals(durableIdentity, overlayIdentity(admin, manifest));
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
          "V21_FRESH_V19_JVM_DID_NOT_STOP");
      throw new IllegalStateException("V21_FRESH_V19_JVM_TIMEOUT");
    }
    assertFalse(process.isAlive(), "V21_FRESH_VERIFIER_REMAINED_ACTIVE");
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
          throw new IllegalStateException("V21_PUBLIC_TABLE_NAME_INVALID");
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
      throw new IllegalStateException("V21_DATABASE_IMAGE_FAILED");
    }
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!List.of(
            WRITER_ROLE, V13_ROLE, V15_ROLE, V16_ROLE, READER_ROLE)
        .contains(role)) {
      throw new IllegalArgumentException("V21_ROLE_NOT_ALLOWLISTED");
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
          "V21_EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
    }
  }

  private static byte[] sign(KeyPair keyPair, byte[] material) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(material);
      return signer.sign();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("V21_TEST_SIGNING_FAILED");
    }
  }

  private static String sha256Hex(byte[] material) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("V21_SHA_256_UNAVAILABLE");
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
        Pack010V21ExactPicoOverlayReaderConnectionLossIT.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException(
            "V21_PROVISIONING_RESOURCE_MISSING");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "V21_PROVISIONING_RESOURCE_READ_FAILED");
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

  private record FaultObservation(
      ProcessReceipt child, int backendPid) {}

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
