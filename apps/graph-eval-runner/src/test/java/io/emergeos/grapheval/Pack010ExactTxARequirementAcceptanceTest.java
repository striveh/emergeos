package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestorTestAccess;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationTranscript;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance Red for the first V15 database-authority slice.
 *
 * <p>This test deliberately does not define a V15 attribution, transcript,
 * signature, commit, or sequence-14 overlay. It requires only a durable
 * sequence-13 marker and proves that neither the already-shipping typed legacy
 * TX-A nor the signed V13 typed completion can bypass that marker through
 * Java-only checks.
 */
@Testcontainers
class Pack010ExactTxARequirementAcceptanceTest {

  private static final String V15_ROLE =
      "emergeos_provider_attestor_v15";
  private static final String V15_PROFILE_ID =
      "deepseek-v4-flash-responses-public-list-2026-08-11-v1";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_exact_tx_a_v15_red")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void v15RequirementFencesAndLinearizesLegacyAndV13TypedTxA()
      throws Exception {
    DataSource admin = dataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

    String writerPassword = UUID.randomUUID().toString();
    String attestorPassword = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    provisionProductionRoles(
        admin, writerPassword, attestorPassword, v15Password);
    DataSource writerDataSource =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource attestorDataSource =
        dataSource("emergeos_provider_attestor", attestorPassword);
    DataSource v15DataSource = dataSource(V15_ROLE, v15Password);
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);

    int repetition = 1;
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
        Instant.now().plus(Duration.ofMinutes(5)));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    assertEquals(13, sequence13.cursor().lastSequence());
    assertEquals(
        0L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM agent_graph_provider_validations
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());

    String profileHash = insertExactPicoProfile(admin);
    String requirementHash = requireExactTxA(v15DataSource, manifest);
    assertTrue(requirementHash.matches("[a-f0-9]{64}"));

    String requirementBefore = requirementImage(admin, manifest);
    assertTrue(requirementBefore.contains(manifest.principalId()));
    assertTrue(requirementBefore.contains(manifest.attemptId()));
    assertTrue(requirementBefore.contains(manifest.manifestHash()));
    assertTrue(requirementBefore.contains(sequence13.cursor().headHash()));
    assertTrue(requirementBefore.contains(profileHash));
    assertTrue(requirementBefore.contains(requirementHash));
    DatabaseDigest before = databaseDigest(admin);

    GraphAttemptIntegrityException failure =
        captureFailure(
            GraphAttemptIntegrityException.class,
            () ->
                Pack010GraphTerminalFixture.completeCatalogTxA(
                    writer, sequence13, repetition),
            writerPassword,
            attestorPassword,
            v15Password,
            POSTGRES.getPassword());
    PSQLException postgres = rootPostgresFailure(failure);
    assertEquals("55000", postgres.getSQLState());
    assertEquals(
        "V15 exact provider attribution is required",
        serverMessage(postgres));
    assertEquals(before, databaseDigest(admin));
    assertEquals(requirementBefore, requirementImage(admin, manifest));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertEquals(
        0L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM agent_graph_provider_validations
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());

    int v13Repetition = 2;
    GraphAttemptManifest v13Manifest =
        Pack010GraphEvalCatalog.manifest(v13Repetition);
    GraphAttemptSnapshot v13Sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, v13Repetition);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        v13Manifest,
        OwnerTtyGraphAuthority.Pack010Revision.R2,
        Pack010GraphTerminalFixture.catalogIntent(v13Repetition, 1),
        Instant.now().plus(Duration.ofMinutes(5)));

    String keyId = "pack010-v15-v13-test-key";
    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    insertProviderValidationKey(admin, keyId, keyPair);
    PostgresProviderValidationAttestor attestor =
        PostgresProviderValidationAttestor.open(attestorDataSource);
    String policyHash =
        attestor.requireValidation(
            v13Manifest,
            v13Sequence7.cursor(),
            keyId,
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH,
            Duration.ofSeconds(20));
    assertTrue(policyHash.matches("[a-f0-9]{64}"));
    GraphAttemptSnapshot v13Sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, v13Sequence7, v13Repetition);
    assertEquals(13, v13Sequence13.cursor().lastSequence());
    AtomicInteger staged = new AtomicInteger();
    AtomicInteger verified = new AtomicInteger();
    AtomicInteger committed = new AtomicInteger();
    AtomicBoolean signerCalled = new AtomicBoolean();
    AtomicReference<PSQLException> observedPostgres =
        new AtomicReference<>();
    StatementProbe v13StageProbe =
        new StatementProbe(
            "agent_graph_stage_provider_validation_v13");
    CountDownLatch v13StagedAfterMarker = new CountDownLatch(1);
    CountDownLatch releaseV13AfterWinnerFrozen =
        new CountDownLatch(1);
    DataSource observedAttestorDataSource =
        capturingDataSource(
            attestorDataSource, observedPostgres, v13StageProbe);
    PostgresProviderValidationAttestor guardedAttestor =
        PostgresProviderValidationAttestorTestAccess.open(
            observedAttestorDataSource,
            point -> {
              switch (point) {
                case AFTER_PROVIDER_VALIDATION_STAGED -> {
                  staged.incrementAndGet();
                  v13StagedAfterMarker.countDown();
                  awaitLatch(
                      releaseV13AfterWinnerFrozen,
                      "V15_MARKER_FIRST_WINNER_FREEZE_TIMED_OUT");
                }
                case AFTER_SIGNATURE_VERIFIED -> verified.incrementAndGet();
                case AFTER_PROVIDER_VALIDATION_COMMITTED ->
                    committed.incrementAndGet();
              }
            });
    observedPostgres.set(null);
    v13StageProbe.arm();
    GraphAttemptConflictException v13Failure;
    String v13RequirementBefore;
    String v13ValidationBefore;
    DatabaseDigest v13Before;
    try (ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();
        Connection markerConnection = v15DataSource.getConnection()) {
      markerConnection.setAutoCommit(false);
      assertEquals(
          Connection.TRANSACTION_READ_COMMITTED,
          markerConnection.getTransactionIsolation());
      String v13RequirementHash =
          requireExactTxA(
              new SingleConnectionDataSource(markerConnection, true),
              v13Manifest);
      assertTrue(v13RequirementHash.matches("[a-f0-9]{64}"));
      Future<GraphAttemptConflictException> losingCompletion =
          executor.submit(
              () ->
                  captureFailure(
                      GraphAttemptConflictException.class,
                      () ->
                          guardedAttestor.completeValidation(
                              v13Manifest,
                              v13Sequence13.cursor(),
                              Pack010ProviderValidationAttestationHarnessMain
                                  .statement(v13Repetition),
                              transcript -> {
                                signerCalled.set(true);
                                return sign(keyPair, transcript);
                              }),
                      writerPassword,
                      attestorPassword,
                      v15Password,
                      POSTGRES.getPassword()));
      v13StageProbe.awaitEntered();
      int markerBackendPid = backendPid(markerConnection);
      int v13BackendPid = v13StageProbe.backendPid();
      assertTrue(markerBackendPid != v13BackendPid);
      awaitBlockedBy(
          admin, v13BackendPid, markerBackendPid);
      markerConnection.commit();
      awaitLatch(
          v13StagedAfterMarker,
          "V15_MARKER_FIRST_STAGE_PROBE_TIMED_OUT");
      v13RequirementBefore = requirementImage(admin, v13Manifest);
      v13ValidationBefore =
          providerValidationImage(admin, v13Manifest);
      assertEquals(
          "REQUIRED", providerValidationState(admin, v13Manifest));
      v13Before = databaseDigest(admin);
      releaseV13AfterWinnerFrozen.countDown();
      v13Failure = awaitFuture(losingCompletion);
    } finally {
      releaseV13AfterWinnerFrozen.countDown();
    }
    assertEquals("provider validation was fenced", v13Failure.getMessage());
    PSQLException v13Postgres = observedPostgres.get();
    assertTrue(v13Postgres != null);
    assertSecretAbsent(
        v13Postgres,
        writerPassword,
        attestorPassword,
        v15Password,
        POSTGRES.getPassword());
    assertEquals("55000", v13Postgres.getSQLState());
    assertEquals(
        "V15 exact provider attribution is required",
        serverMessage(v13Postgres));
    assertEquals(1, staged.get());
    assertEquals(1, verified.get());
    assertEquals(0, committed.get());
    assertTrue(signerCalled.get());
    assertEquals(v13Before, databaseDigest(admin));
    assertEquals(
        v13RequirementBefore, requirementImage(admin, v13Manifest));
    assertEquals(
        v13ValidationBefore,
        providerValidationImage(admin, v13Manifest));
    assertEquals("REQUIRED", providerValidationState(admin, v13Manifest));
    assertEquals(
        v13Sequence13,
        Pack010GraphTerminalFixture.verified(writer, v13Manifest));

    int commitFirstRepetition = 3;
    GraphAttemptManifest commitFirstManifest =
        Pack010GraphEvalCatalog.manifest(commitFirstRepetition);
    GraphAttemptSnapshot commitFirstSequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, commitFirstRepetition);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        commitFirstManifest,
        OwnerTtyGraphAuthority.Pack010Revision.R3,
        Pack010GraphTerminalFixture.catalogIntent(
            commitFirstRepetition, 1),
        Instant.now().plus(Duration.ofMinutes(5)));
    attestor.requireValidation(
        commitFirstManifest,
        commitFirstSequence7.cursor(),
        keyId,
        Pack010ProviderValidationAttestationHarnessMain
            .TRANSPORT_PROFILE_HASH,
        Pack010ProviderValidationAttestationHarnessMain
            .PARSER_PROFILE_HASH,
        Pack010ProviderValidationAttestationHarnessMain
            .SCHEMA_PROFILE_HASH,
        Duration.ofSeconds(20));
    GraphAttemptSnapshot commitFirstSequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, commitFirstSequence7, commitFirstRepetition);
    assertEquals(13, commitFirstSequence13.cursor().lastSequence());
    assertEquals(0L, requirementCount(admin, commitFirstManifest));

    AtomicInteger winnerStaged = new AtomicInteger();
    AtomicInteger winnerVerified = new AtomicInteger();
    AtomicInteger winnerCommitted = new AtomicInteger();
    AtomicBoolean winnerSignerCalled = new AtomicBoolean();
    AtomicReference<PSQLException> winningPostgres =
        new AtomicReference<>();
    StatementProbe v13CommitProbe =
        new StatementProbe(
            "agent_graph_commit_provider_validation_v13");
    CountDownLatch v13CommittedBeforeJdbcCommit =
        new CountDownLatch(1);
    CountDownLatch releaseV13JdbcCommit = new CountDownLatch(1);
    DataSource observedCommitFirstDataSource =
        capturingDataSource(
            attestorDataSource, winningPostgres, v13CommitProbe);
    PostgresProviderValidationAttestor commitFirstAttestor =
        PostgresProviderValidationAttestorTestAccess.open(
            observedCommitFirstDataSource,
            point -> {
              switch (point) {
                case AFTER_PROVIDER_VALIDATION_STAGED ->
                    winnerStaged.incrementAndGet();
                case AFTER_SIGNATURE_VERIFIED ->
                    winnerVerified.incrementAndGet();
                case AFTER_PROVIDER_VALIDATION_COMMITTED -> {
                  winnerCommitted.incrementAndGet();
                  v13CommittedBeforeJdbcCommit.countDown();
                  awaitLatch(
                      releaseV13JdbcCommit,
                      "V15_V13_FIRST_JDBC_COMMIT_RELEASE_TIMED_OUT");
                }
              }
            });
    winningPostgres.set(null);
    v13CommitProbe.arm();
    StatementProbe v15RequireProbe =
        new StatementProbe(
            "agent_graph_require_exact_tx_a_v15", true);
    AtomicReference<PSQLException> losingRequirePostgres =
        new AtomicReference<>();
    DataSource blockedV15DataSource =
        capturingDataSource(
            v15DataSource, losingRequirePostgres, v15RequireProbe);
    RuntimeException losingRequire;
    GraphAttemptSnapshot committedSnapshot;
    String committedValidationImage;
    DatabaseDigest committedDigest;
    try (ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor()) {
      Future<GraphAttemptCursor> winningCompletion =
          executor.submit(
              () ->
                  commitFirstAttestor.completeValidation(
                      commitFirstManifest,
                      commitFirstSequence13.cursor(),
                      Pack010ProviderValidationAttestationHarnessMain
                          .statement(commitFirstRepetition),
                      transcript -> {
                        winnerSignerCalled.set(true);
                        return sign(keyPair, transcript);
                      }));
      awaitLatch(
          v13CommittedBeforeJdbcCommit,
          "V15_V13_FIRST_PRECOMMIT_PROBE_TIMED_OUT");
      v15RequireProbe.arm();
      Future<RuntimeException> losingRequirement =
          executor.submit(
              () ->
                  captureFailure(
                      RuntimeException.class,
                      () ->
                          requireExactTxA(
                              blockedV15DataSource,
                              commitFirstManifest),
                      writerPassword,
                      attestorPassword,
                      v15Password,
                      POSTGRES.getPassword()));
      v15RequireProbe.awaitEntered();
      int v13BackendPid = v13CommitProbe.backendPid();
      int v15BackendPid = v15RequireProbe.backendPid();
      assertTrue(v13BackendPid != v15BackendPid);
      awaitBlockedBy(admin, v15BackendPid, v13BackendPid);
      releaseV13JdbcCommit.countDown();
      GraphAttemptCursor committedCursor =
          awaitFuture(winningCompletion);
      assertTrue(winningPostgres.get() == null);
      assertEquals(14, committedCursor.lastSequence());
      assertEquals(1, winnerStaged.get());
      assertEquals(1, winnerVerified.get());
      assertEquals(1, winnerCommitted.get());
      assertTrue(winnerSignerCalled.get());
      v15RequireProbe.awaitFailure();
      committedSnapshot =
          Pack010GraphTerminalFixture.verified(
              writer, commitFirstManifest);
      assertEquals(committedCursor, committedSnapshot.cursor());
      assertEquals(
          "CONSUMED",
          providerValidationState(admin, commitFirstManifest));
      committedValidationImage =
          providerValidationImage(admin, commitFirstManifest);
      committedDigest = databaseDigest(admin);
      v15RequireProbe.releaseFailure();
      losingRequire = awaitFuture(losingRequirement);
    } finally {
      releaseV13JdbcCommit.countDown();
      v15RequireProbe.releaseFailure();
    }
    PSQLException losingRequireSql =
        rootPostgresFailure(losingRequire);
    assertEquals(losingRequireSql, losingRequirePostgres.get());
    assertSecretAbsent(
        losingRequireSql,
        writerPassword,
        attestorPassword,
        v15Password,
        POSTGRES.getPassword());
    assertEquals("55000", losingRequireSql.getSQLState());
    assertEquals(
        "V15 exact TX-A requirement was fenced",
        serverMessage(losingRequireSql));
    assertEquals(0L, requirementCount(admin, commitFirstManifest));
    assertEquals(committedDigest, databaseDigest(admin));
    assertEquals(
        committedValidationImage,
        providerValidationImage(admin, commitFirstManifest));
    assertEquals(
        committedSnapshot,
        Pack010GraphTerminalFixture.verified(
            writer, commitFirstManifest));

    System.out.println(
        "PACK010_V15_EXACT_TX_A_REQUIREMENT_ACCEPTANCE_RECEIPT"
            + " markerAt=REP1_SEQ13,REP2_SEQ13"
            + " legacyTypedTxA=DB_55000"
            + " v13TypedTxA=STAGED1_VERIFIED1_COMMITTED0_DB_55000"
            + " linearization=MARKER_FIRST_V13_FENCED,V13_FIRST_MARKER_FENCED"
            + " dbBlocking=PG_BLOCKING_PIDS_BOTH_DIRECTIONS"
            + " fullPublicTableJsonXmin=UNCHANGED"
            + " rep1V13Policy=ABSENT"
            + " rep2V13Policy=REQUIRED_UNCHANGED"
            + " rep3Marker=ABSENT"
            + " exactPicoAttribution=NOT_IMPLEMENTED"
            + " txA=NOT_IMPLEMENTED"
            + " providerNetwork=0 billing=0");
  }

  private static String insertExactPicoProfile(DataSource admin) {
    String transportProfileId = "deepseek-responses-http-v1";
    String parserProfileId = "deepseek-responses-parser-v1";
    String schemaProfileId = "deepseek-structured-final-v1";
    String modelProfileId = "deepseek-v4-flash-effort-none-v1";
    return JdbcClient.create(admin)
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
              :profileId, 'deepseek', 'deepseek.responses',
              :transportProfileId, :transportProfileHash,
              :parserProfileId, :parserProfileHash,
              :schemaProfileId, :schemaProfileHash,
              :modelProfileId, :modelProfileHash,
              'deepseek-v4-flash', :modelResolutionProfileHash,
              'deepseek-v4-flash-public-list-2026-08-11', 'deepseek',
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
        .param("profileId", V15_PROFILE_ID)
        .param("transportProfileId", transportProfileId)
        .param(
            "transportProfileHash",
            IntegrityHashes.utf8ContentHash(transportProfileId))
        .param("parserProfileId", parserProfileId)
        .param(
            "parserProfileHash",
            IntegrityHashes.utf8ContentHash(parserProfileId))
        .param("schemaProfileId", schemaProfileId)
        .param(
            "schemaProfileHash",
            IntegrityHashes.utf8ContentHash(schemaProfileId))
        .param("modelProfileId", modelProfileId)
        .param(
            "modelProfileHash",
            IntegrityHashes.utf8ContentHash(modelProfileId))
        .param(
            "modelResolutionProfileHash",
            IntegrityHashes.utf8ContentHash(
                "deepseek-v4-flash-response-family-hash-v1"))
        .param(
            "pricingProfileFingerprint",
            IntegrityHashes.utf8ContentHash(
                "deepseek-v4-flash-public-list-pico-v1"))
        .param(
            "pricingSourceHash",
            IntegrityHashes.utf8ContentHash(
                "deepseek-public-pricing-reviewed-2026-08-11"))
        .query(String.class)
        .single();
  }

  private static String requireExactTxA(
      DataSource v15DataSource, GraphAttemptManifest manifest) {
    return JdbcClient.create(v15DataSource)
        .sql(
            """
            SELECT btrim(public.agent_graph_require_exact_tx_a_v15(
              CAST(:principalId AS varchar),
              CAST(:attemptId AS char(64)),
              CAST(:manifestHash AS char(64)),
              CAST(:providerProfileId AS varchar)))::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("providerProfileId", V15_PROFILE_ID)
        .query(String.class)
        .single();
  }

  private static void insertProviderValidationKey(
      DataSource admin, String keyId, KeyPair keyPair)
      throws GeneralSecurityException {
    byte[] publicKey = keyPair.getPublic().getEncoded();
    String fingerprint =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(publicKey));
    JdbcClient.create(admin)
        .sql(
            """
            INSERT INTO agent_graph_provider_validation_keys (
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
        .update();
  }

  private static GraphProviderValidationAttestation sign(
      KeyPair keyPair, GraphProviderValidationTranscript transcript) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(keyPair.getPrivate());
      signature.update(transcript.signatureMaterial());
      return new GraphProviderValidationAttestation(
          transcript, HexFormat.of().formatHex(signature.sign()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(
          "EPHEMERAL_PROVIDER_VALIDATION_SIGNING_FAILED");
    }
  }

  private static String requirementImage(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT pg_catalog.to_jsonb(requirement)::text
                     || E'\\x1f' || requirement.xmin::text
            FROM agent_graph_exact_tx_a_requirements_v15 requirement
            WHERE requirement.principal_id = :principalId
              AND requirement.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static long requirementCount(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT count(*)
            FROM agent_graph_exact_tx_a_requirements_v15
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static String providerValidationImage(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT pg_catalog.to_jsonb(validation)::text
                     || E'\\x1f' || validation.xmin::text
            FROM agent_graph_provider_validations validation
            WHERE validation.principal_id = :principalId
              AND validation.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static String providerValidationState(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT state
            FROM agent_graph_provider_validations
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static DataSource capturingDataSource(
      DataSource delegate,
      AtomicReference<PSQLException> observed,
      StatementProbe probe) {
    return new DelegatingDataSource(delegate) {
      @Override
      public Connection getConnection() throws SQLException {
        return capturingConnection(
            super.getConnection(), observed, probe);
      }

      @Override
      public Connection getConnection(String username, String password)
          throws SQLException {
        return capturingConnection(
            super.getConnection(username, password), observed, probe);
      }
    };
  }

  private static Connection capturingConnection(
      Connection delegate,
      AtomicReference<PSQLException> observed,
      StatementProbe probe) throws SQLException {
    configureBoundedLockWait(delegate);
    probe.connectionOpened(backendPid(delegate));
    return (Connection)
        Proxy.newProxyInstance(
            Pack010ExactTxARequirementAcceptanceTest.class
                .getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(delegate, arguments);
                if (result instanceof Statement statement) {
                  String preparedSql =
                      arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String sql
                          ? sql
                          : null;
                  return capturingStatement(
                      statement,
                      method.getReturnType(),
                      preparedSql,
                      observed,
                      probe);
                }
                return result;
              } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                rememberPostgresFailure(cause, observed);
                throw cause;
              }
            });
  }

  private static Statement capturingStatement(
      Statement delegate,
      Class<?> statementType,
      String preparedSql,
      AtomicReference<PSQLException> observed,
      StatementProbe probe) {
    return (Statement)
        Proxy.newProxyInstance(
            Pack010ExactTxARequirementAcceptanceTest.class
                .getClassLoader(),
            new Class<?>[] {statementType},
            (proxy, method, arguments) -> {
              String executedSql =
                  preparedSql != null
                      ? preparedSql
                      : arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String sql
                          ? sql
                          : null;
              probe.beforeExecute(method.getName(), executedSql);
              try {
                return method.invoke(delegate, arguments);
              } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                rememberPostgresFailure(cause, observed);
                probe.afterFailure(method.getName(), executedSql);
                throw cause;
              }
            });
  }

  private static void configureBoundedLockWait(Connection connection)
      throws SQLException {
    if (connection.getTransactionIsolation()
        != Connection.TRANSACTION_READ_COMMITTED) {
      throw new SQLException("READ_COMMITTED_REQUIRED");
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET lock_timeout = '15s'");
      statement.execute("SET statement_timeout = '20s'");
    }
  }

  private static int backendPid(Connection connection)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT pg_catalog.pg_backend_pid()")) {
      if (!result.next()) {
        throw new SQLException("BACKEND_PID_MISSING");
      }
      return result.getInt(1);
    }
  }

  private static void awaitBlockedBy(
      DataSource admin, int blockedPid, int blockerPid) {
    long deadline =
        System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      boolean blocked =
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT CAST(:blockerPid AS integer) = ANY(
                    pg_catalog.pg_blocking_pids(
                      CAST(:blockedPid AS integer)))
                  """)
              .param("blockerPid", blockerPid)
              .param("blockedPid", blockedPid)
              .query(Boolean.class)
              .single();
      if (blocked) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("V15_RACE_WAIT_INTERRUPTED");
      }
    }
    throw new AssertionError("V15_EXPECTED_DATABASE_BLOCK_MISSING");
  }

  private static void awaitLatch(
      CountDownLatch latch, String timeoutMessage) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError(timeoutMessage);
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("V15_RACE_WAIT_INTERRUPTED");
    }
  }

  private static <T> T awaitFuture(Future<T> future) {
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("V15_RACE_WAIT_INTERRUPTED");
    } catch (ExecutionException failure) {
      throw new AssertionError("V15_RACE_WORKER_FAILED");
    } catch (TimeoutException failure) {
      throw new AssertionError("V15_RACE_WORKER_TIMED_OUT");
    }
  }

  private static void rememberPostgresFailure(
      Throwable failure, AtomicReference<PSQLException> observed) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException postgres) {
        observed.compareAndSet(null, postgres);
      }
      current = current.getCause();
    }
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
      long rowCount = 0;
      StringBuilder framed = new StringBuilder();
      for (String table : tables) {
        if (!table.matches("[a-z][a-z0-9_]{0,62}")) {
          throw new IllegalStateException("PUBLIC_TABLE_NAME_INVALID");
        }
        RowDigest rows =
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
                        new RowDigest(
                            row.getLong("row_count"),
                            row.getString("row_digest")))
                .single();
        rowCount += rows.rowCount();
        framed.append(table)
            .append('|')
            .append(rows.rowCount())
            .append('|')
            .append(rows.digest())
            .append('\n');
      }
      connection.rollback();
      return new DatabaseDigest(
          tables.size(),
          rowCount,
          IntegrityHashes.utf8ContentHash(framed.toString()));
    } catch (SQLException failure) {
      throw new IllegalStateException("DATABASE_DIGEST_FAILED");
    }
  }

  private static <T extends Throwable> T captureFailure(
      Class<T> expectedType,
      Executable action,
      String... secrets) {
    Throwable observed = null;
    try {
      action.execute();
    } catch (Throwable failure) {
      observed = failure;
    }
    if (observed == null) {
      throw new AssertionError("EXPECTED_FAILURE_MISSING");
    }
    assertSecretAbsent(observed, secrets);
    if (!expectedType.isInstance(observed)) {
      throw new AssertionError("UNEXPECTED_FAILURE_TYPE");
    }
    return expectedType.cast(observed);
  }

  private static void assertSecretAbsent(
      Throwable failure, String... secrets) {
    Set<Throwable> seen =
        Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> remaining = new ArrayDeque<>();
    remaining.add(failure);
    while (!remaining.isEmpty()) {
      Throwable current = remaining.removeFirst();
      if (!seen.add(current)) {
        continue;
      }
      String message = current.getMessage();
      for (String secret : secrets) {
        if (secret != null
            && !secret.isEmpty()
            && message != null
            && message.contains(secret)) {
          fail("SECRET_PRESENT_IN_FAILURE_CHAIN");
        }
      }
      if (current.getCause() != null) {
        remaining.addLast(current.getCause());
      }
      Collections.addAll(remaining, current.getSuppressed());
    }
  }

  private static PSQLException rootPostgresFailure(Throwable failure) {
    Set<Throwable> seen =
        Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> remaining = new ArrayDeque<>();
    remaining.add(failure);
    PSQLException postgres = null;
    while (!remaining.isEmpty()) {
      Throwable current = remaining.removeFirst();
      if (!seen.add(current)) {
        continue;
      }
      if (current instanceof PSQLException observed) {
        postgres = observed;
      }
      if (current.getCause() != null) {
        remaining.addLast(current.getCause());
      }
      Collections.addAll(remaining, current.getSuppressed());
    }
    if (postgres == null) {
      throw new AssertionError("EXPECTED_POSTGRES_FAILURE");
    }
    return postgres;
  }

  private static String serverMessage(PSQLException failure) {
    if (failure.getServerErrorMessage() == null
        || failure.getServerErrorMessage().getMessage() == null) {
      throw new AssertionError("POSTGRES_SERVER_MESSAGE_MISSING");
    }
    return failure.getServerErrorMessage().getMessage();
  }

  private static DataSource dataSource() {
    return dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DataSource dataSource(String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + POSTGRES.getDatabaseName());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void provisionProductionRoles(
      DataSource admin,
      String writerPassword,
      String attestorPassword,
      String v15Password) throws SQLException {
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource(
                "db/provisioning/pack010_runtime_roles_check.sql"));
    setEphemeralPassword(
        admin, "emergeos_graph_prefix_writer", writerPassword);
    setEphemeralPassword(
        admin, "emergeos_provider_attestor", attestorPassword);
    assertEquals(
        1L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_roles
                WHERE rolname = 'emergeos_provider_attestor_v15'
                """)
            .query(Long.class)
            .single());
    setEphemeralPassword(admin, V15_ROLE, v15Password);
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!("emergeos_graph_prefix_writer".equals(role)
        || "emergeos_provider_attestor".equals(role)
        || V15_ROLE.equals(role))) {
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
      throw new IllegalStateException(
          "EPHEMERAL_ROLE_PASSWORD_SETUP_FAILED");
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
        Pack010ExactTxARequirementAcceptanceTest.class
            .getClassLoader()
            .getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException(
            "PACK010_PROVISIONING_RESOURCE_MISSING");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "PACK010_PROVISIONING_RESOURCE_READ_FAILED");
    }
  }

  private static final class StatementProbe {

    private final String sqlFragment;
    private final boolean holdFailure;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean enteredClaimed = new AtomicBoolean();
    private final AtomicBoolean failureClaimed = new AtomicBoolean();
    private final AtomicInteger backendPid = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch failureObserved = new CountDownLatch(1);
    private final CountDownLatch failureReleased = new CountDownLatch(1);

    private StatementProbe(String sqlFragment) {
      this(sqlFragment, false);
    }

    private StatementProbe(String sqlFragment, boolean holdFailure) {
      this.sqlFragment = sqlFragment;
      this.holdFailure = holdFailure;
    }

    private void arm() {
      if (!armed.compareAndSet(false, true)) {
        throw new AssertionError("V15_STATEMENT_PROBE_ALREADY_ARMED");
      }
    }

    private void connectionOpened(int observedBackendPid) {
      if (armed.get()) {
        backendPid.compareAndSet(0, observedBackendPid);
      }
    }

    private void beforeExecute(String methodName, String sql) {
      if (!matches(methodName, sql)
          || !enteredClaimed.compareAndSet(false, true)) {
        return;
      }
      entered.countDown();
    }

    private void afterFailure(String methodName, String sql) {
      if (!holdFailure
          || !matches(methodName, sql)
          || !failureClaimed.compareAndSet(false, true)) {
        return;
      }
      failureObserved.countDown();
      awaitLatch(
          failureReleased,
          "V15_RACE_FAILURE_RELEASE_TIMED_OUT");
    }

    private boolean matches(String methodName, String sql) {
      return armed.get()
          && sql != null
          && methodName.startsWith("execute")
          && sql.contains(sqlFragment);
    }

    private void awaitEntered() {
      awaitLatch(entered, "V15_RACE_STATEMENT_ENTRY_TIMED_OUT");
    }

    private int backendPid() {
      int observed = backendPid.get();
      if (observed <= 0) {
        throw new AssertionError("V15_RACE_BACKEND_PID_MISSING");
      }
      return observed;
    }

    private void awaitFailure() {
      awaitLatch(
          failureObserved, "V15_RACE_FAILURE_NOT_OBSERVED");
    }

    private void releaseFailure() {
      failureReleased.countDown();
    }
  }

  private record RowDigest(long rowCount, String digest) {}

  private record DatabaseDigest(
      int tableCount, long rowCount, String digest) {}
}
