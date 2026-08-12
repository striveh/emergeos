package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.openai.core.LogLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestorTestAccess;
import io.emergeos.adapters.openai.OpenAiProviderValidationStatements;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import io.emergeos.core.domain.GraphProviderValidationTranscript;
import io.emergeos.core.port.GraphProviderValidationSigner;
import io.emergeos.core.port.CancellationSignal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
class Pack010ProviderValidationAttestationAcceptanceTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_provider_validation_red")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void rawBypassAndSignedValidationAreBoundedAndAtomic()
      throws Exception {
    DataSource admin = dataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);
    String writerPassword = UUID.randomUUID().toString();
    String attestorPassword = UUID.randomUUID().toString();
    String profileAttestorPassword = UUID.randomUUID().toString();
    provisionProductionRoles(
        admin,
        writerPassword,
        attestorPassword,
        profileAttestorPassword);
    DataSource writerDataSource =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource attestorDataSource =
        dataSource("emergeos_provider_attestor", attestorPassword);
    DataSource profileAttestorDataSource =
        dataSource(
            "emergeos_provider_attestor_v14",
            profileAttestorPassword);
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
    String keyId = "pack010-v13-test-key";
    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] publicKey = keyPair.getPublic().getEncoded();
    String keyFingerprint =
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
        .param("fingerprint", keyFingerprint)
        .update();
    OpenAiProviderValidationStatements.Policy reviewedPolicy =
        OpenAiProviderValidationStatements.policy();
    String transportProfileHash =
        reviewedPolicy.transportProfileHash();
    String parserProfileHash = reviewedPolicy.parserProfileHash();
    String schemaProfileHash = reviewedPolicy.schemaProfileHash();
    PostgresProviderValidationAttestor attestor =
        PostgresProviderValidationAttestor.open(attestorDataSource);
    DatabaseDigest missingKeyBefore = databaseDigest(admin);
    GraphAttemptConflictException missingKeyFailure = captureFailure(
        GraphAttemptConflictException.class,
        () ->
            attestor.requireValidation(
                manifest,
                sequence7.cursor(),
                "pack010-v13-missing-key",
                transportProfileHash,
                parserProfileHash,
                schemaProfileHash,
                Duration.ofSeconds(30)),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertEquals(
        "provider validation was fenced", missingKeyFailure.getMessage());
    assertEquals(missingKeyBefore, databaseDigest(admin));
    String policyHash =
        attestor.requireValidation(
            manifest,
            sequence7.cursor(),
            keyId,
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            Duration.ofSeconds(30));
    assertEquals(64, policyHash.length());
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    assertEquals(13, sequence13.cursor().lastSequence());

    DatabaseDigest rawBefore = databaseDigest(admin);
    GraphAttemptIntegrityException rawFailure = captureFailure(
        GraphAttemptIntegrityException.class,
        () ->
            Pack010GraphTerminalFixture.completeCatalogTxA(
                writer, sequence13, repetition),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    PSQLException rawSql = rootPostgresFailure(rawFailure);
    assertEquals("55000", rawSql.getSQLState());
    assertEquals(
        "V13 request-2 validation attestation is missing",
        serverMessage(rawSql));
    assertEquals(rawBefore, databaseDigest(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertDeepSeekV14ProviderProfileAuthority(
        admin,
        profileAttestorDataSource,
        writer,
        manifest,
        sequence13,
        writerPassword,
        attestorPassword,
        profileAttestorPassword,
        POSTGRES.getPassword());
    DatabaseDigest v14NoUnlockBefore = databaseDigest(admin);
    GraphAttemptIntegrityException v14NoUnlockFailure = captureFailure(
        GraphAttemptIntegrityException.class,
        () ->
            Pack010GraphTerminalFixture.completeCatalogTxA(
                writer, sequence13, repetition),
        writerPassword,
        attestorPassword,
        profileAttestorPassword,
        POSTGRES.getPassword());
    PSQLException v14NoUnlockSql =
        rootPostgresFailure(v14NoUnlockFailure);
    assertEquals(
        "V13 request-2 validation attestation is missing",
        serverMessage(v14NoUnlockSql));
    assertEquals("55000", v14NoUnlockSql.getSQLState());
    assertEquals(v14NoUnlockBefore, databaseDigest(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    GraphProviderValidationStatement wrongExecutionBinding =
        new GraphProviderValidationStatement(
            Pack010GraphTerminalFixture.catalogAttribution(repetition, 2),
            Pack010GraphEvalCatalog.manifest(2).manifestHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            GraphProviderValidationDecision.STRUCTURED_FINAL,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-wrong-execution-binding-final-v1"),
            null);
    assertNotEquals(
        manifest.manifestHash(),
        wrongExecutionBinding.executionBindingHash());
    assertRawExecutionBindingFence(
        attestorDataSource,
        admin,
        manifest,
        sequence13.cursor(),
        wrongExecutionBinding,
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    GraphProviderValidationStatement crossDatabaseStatement =
        new GraphProviderValidationStatement(
            Pack010GraphTerminalFixture.catalogAttribution(repetition, 2),
            manifest.manifestHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            GraphProviderValidationDecision.STRUCTURED_FINAL,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-cross-database-final-v1"),
            null);
    assertCrossDatabaseCloneIsFenced(
        admin,
        manifest,
        sequence13,
        crossDatabaseStatement,
        attestorPassword,
        writerPassword,
        POSTGRES.getPassword());

    DatabaseDigest nullSignatureBefore = databaseDigest(admin);
    RuntimeException nullSignatureFailure = captureFailure(
        RuntimeException.class,
        () ->
            JdbcClient.create(attestorDataSource)
                .sql(
                    """
                    SELECT public.agent_graph_commit_provider_validation_v13(
                      pg_catalog.jsonb_build_object(
                        'principal_id', :principalId,
                        'attempt_id', :attemptId,
                        'manifest_hash', :manifestHash,
                        'policy_hash', :policyHash,
                        'transcript_hash', :transcriptHash,
                        'signature_hex', NULL,
                        'event_hash', :eventHash,
                        'cursor_head_hash', :cursorHeadHash))
                    """)
                .param("principalId", manifest.principalId())
                .param("attemptId", manifest.attemptId())
                .param("manifestHash", manifest.manifestHash())
                .param("policyHash", "0".repeat(64))
                .param("transcriptHash", "1".repeat(64))
                .param("eventHash", "2".repeat(64))
                .param("cursorHeadHash", "3".repeat(64))
                .query(String.class)
                .single(),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    PSQLException nullSignatureSql =
        rootPostgresFailure(nullSignatureFailure);
    assertEquals("22023", nullSignatureSql.getSQLState());
    assertEquals(
        "V13 provider validation commit input is invalid",
        serverMessage(nullSignatureSql));
    assertEquals(nullSignatureBefore, databaseDigest(admin));

    int signedRepetition = 2;
    GraphAttemptManifest signedManifest =
        Pack010GraphEvalCatalog.manifest(signedRepetition);
    GraphAttemptSnapshot signedSequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, signedRepetition);
    List<OpenAiResponsesModel.ProviderInvocation> reviewedInvocations =
        new ArrayList<>();
    List<OpenAiResponsesModel.ProviderOutcomeReceipt> reviewedOutcomes =
        new ArrayList<>();
    var signedWorker =
        Pack010GraphEvalCatalog.workerProfile(signedRepetition);
    var signedTask =
        Pack010GraphEvalCatalog.childTask(signedRepetition);
    String captureRef = signedTask.inputRefs().getFirst();
    GraphProviderIntent reviewedFirstIntent =
        new GraphProviderIntent(
            1,
            OpenAiResponsesModel.firstRequestFingerprint(
                signedWorker, signedTask),
            signedWorker.modelRequested());
    assertEquals(
        Pack010GraphEvalCatalog.computedFirstRequestHash(
            signedRepetition),
        reviewedFirstIntent.requestHash());
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        signedManifest,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        reviewedFirstIntent,
        Instant.now().plus(Duration.ofMinutes(5)));
    attestor.requireValidation(
        signedManifest,
        signedSequence7.cursor(),
        keyId,
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash,
        Duration.ofSeconds(20));
    Instant signedStartedAt = signedManifest.startedAt();
    GraphAttemptCursor beforeProviderCursor =
        writer.credentialReadStarted(
            signedManifest,
            signedSequence7.cursor(),
            signedStartedAt.plusMillis(7));
    AtomicReference<GraphAttemptCursor> liveCursor =
        new AtomicReference<>(beforeProviderCursor);
    try (ReviewedLoopbackResponsesServer server =
            new ReviewedLoopbackResponsesServer(
                reviewedToolResponse(captureRef),
                reviewedFinalResponse(captureRef));
        ReviewedOpenAiClient reviewedClient =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-v13-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      liveCursor.set(
          writer.clientCreated(
              signedManifest,
              liveCursor.get(),
              signedStartedAt.plusMillis(8)));
      OpenAiResponsesModel reviewedModel =
          OpenAiResponsesModel.withExactResponseOutcome(
                  signedWorker,
                  reviewedClient,
                  signedManifest.manifestHash(),
                  invocation -> {
                    reviewedInvocations.add(invocation);
                    if (invocation.requestOrdinal() == 1) {
                      assertEquals(
                          reviewedFirstIntent,
                          reviewedIntent(invocation));
                    }
                    liveCursor.set(
                        writer.providerIntent(
                            signedManifest,
                            liveCursor.get(),
                            reviewedIntent(invocation),
                            signedStartedAt.plusMillis(
                                invocation.requestOrdinal() == 1
                                    ? 10
                                    : 12)));
                  },
                  outcome -> {
                    if (outcome.attribution().invocation()
                            .requestOrdinal()
                        == 1) {
                      liveCursor.set(
                          writer.providerAttributed(
                              signedManifest,
                              liveCursor.get(),
                              reviewedAttribution(
                                  signedRepetition, outcome),
                              signedStartedAt.plusMillis(11)));
                    }
                    reviewedOutcomes.add(outcome);
                  });
      liveCursor.set(
          writer.modelCreated(
              signedManifest,
              liveCursor.get(),
              signedStartedAt.plusMillis(9)));
      try (AgentModel.Session reviewedSession =
          reviewedModel.open(signedTask)) {
        AgentModel.ModelStep first =
            reviewedSession.next(
                new AgentModel.Turn(signedTask, List.of()),
                modelContext(signedTask.budgetUsd()));
        assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        AgentModel.ModelStep second =
            reviewedSession.next(
                new AgentModel.Turn(
                    signedTask,
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            captureRef,
                            Pack010GraphEvalCatalog.CONTENT))),
                modelContext(signedTask.budgetUsd()));
        assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
        assertEquals(2, server.requestCount());
      }
    }
    assertEquals(2, reviewedInvocations.size());
    assertEquals(2, reviewedOutcomes.size());
    assertEquals(
        OpenAiResponsesModel.ProviderOutcomeKind.TOOL_CALL,
        reviewedOutcomes.get(0).kind());
    assertEquals(
        OpenAiResponsesModel.ProviderOutcomeKind.STRUCTURED_FINAL,
        reviewedOutcomes.get(1).kind());
    GraphAttemptSnapshot signedSequence13 =
        Pack010GraphTerminalFixture.verified(writer, signedManifest);
    assertEquals(liveCursor.get(), signedSequence13.cursor());
    assertEquals(13, signedSequence13.cursor().lastSequence());
    assertEquals(
        GraphAttemptPhase.PROVIDER_PENDING,
        signedSequence13.cursor().phase());
    assertEquals(1, signedSequence13.providerAttributions().size());
    GraphProviderAttribution reviewedAttribution =
        reviewedAttribution(signedRepetition, reviewedOutcomes.get(1));
    GraphProviderValidationStatement statement =
        OpenAiProviderValidationStatements.fromExactOutcome(
            reviewedOutcomes.get(1),
            reviewedAttribution,
            signedManifest.manifestHash());
    assertEquals(
        signedManifest.manifestHash(),
        statement.executionBindingHash());
    assertEquals(
        GraphProviderValidationDecision.STRUCTURED_FINAL,
        statement.decision());
    assertEquals(null, statement.failureCode());
    AtomicReference<GraphProviderValidationAttestation>
        consumedAttestation = new AtomicReference<>();
    var signedCursor =
        attestor.completeValidation(
            signedManifest,
            signedSequence13.cursor(),
            statement,
            transcript -> {
              GraphProviderValidationAttestation signed =
                  sign(keyPair, transcript);
              consumedAttestation.set(signed);
              return signed;
            });
    assertTrue(consumedAttestation.get() != null);
    assertEquals(14, signedCursor.lastSequence());
    GraphAttemptSnapshot signedSequence14 =
        Pack010GraphTerminalFixture.verified(writer, signedManifest);
    assertEquals(signedCursor, signedSequence14.cursor());
    assertEquals(2, signedSequence14.providerAttributions().size());
    assertEquals(
        statement.attribution(),
        signedSequence14.providerAttributions().get(1));
    assertEquals(
        "CONSUMED:"
            + signedManifest.manifestHash()
            + ":"
            + statement.decisionHash()
            + ":STRUCTURED_FINAL:SIGNED",
        JdbcClient.create(admin)
            .sql(
                """
                SELECT state || ':' || execution_binding_hash || ':'
                    || decision_hash || ':' || decision_kind || ':'
                    || CASE
                         WHEN transcript_hash ~ '^[0-9a-f]{64}$'
                          AND pg_catalog.octet_length(signature) = 64
                         THEN 'SIGNED'
                         ELSE 'INVALID'
                       END
                FROM agent_graph_provider_validations
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", signedManifest.principalId())
            .param("attemptId", signedManifest.attemptId())
            .query(String.class)
            .single());
    assertFalse(
        reviewedOutcomes.toString().contains("resp-v13-final"));
    assertFalse(
        reviewedOutcomes.toString().contains("call-v13-reviewed"));
    assertFalse(
        reviewedOutcomes.toString().contains(
            "这是一段公开测试短文。"));
    assertFalse(
        reviewedOutcomes.toString().contains(
            "sentinel-v13-loopback-key"));
    for (String forbidden :
        List.of(
            "resp-v13-final",
            "call-v13-reviewed",
            "这是一段公开测试短文。",
            "sentinel-v13-loopback-key")) {
      assertEquals(0, databaseTextMatchCount(admin, forbidden));
    }
    AtomicBoolean replaySignerCalled = new AtomicBoolean();
    DatabaseDigest replayBefore = databaseDigest(admin);
    GraphAttemptConflictException replayFailure = captureFailure(
        GraphAttemptConflictException.class,
        () ->
            attestor.completeValidation(
                signedManifest,
                signedSequence13.cursor(),
                statement,
                transcript -> {
                  replaySignerCalled.set(true);
                  return sign(keyPair, transcript);
                }),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertEquals("provider validation was fenced", replayFailure.getMessage());
    assertFalse(replaySignerCalled.get());
    assertEquals(replayBefore, databaseDigest(admin));

    DatabaseDigest signatureShapeBefore = databaseDigest(admin);
    PSQLException signatureShapeFailure = rejectedAdminMutation(
        admin,
        """
        UPDATE agent_graph_provider_validations
        SET signature = NULL
        WHERE principal_id = ? AND attempt_id = ?
        """,
        signedManifest,
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertEquals("23514", signatureShapeFailure.getSQLState());
    assertTrue(
        serverMessage(signatureShapeFailure).contains(
            "graph_provider_validations_state_shape_v13"));
    assertEquals(signatureShapeBefore, databaseDigest(admin));

    DatabaseDigest executionBindingShapeBefore = databaseDigest(admin);
    PSQLException executionBindingShapeFailure =
        rejectedAdminCheckMutationWithUserTriggersDisabled(
            admin,
            """
            UPDATE agent_graph_provider_validations
            SET execution_binding_hash = CASE
                  WHEN manifest_hash = repeat('0', 64)
                    THEN repeat('1', 64)
                  ELSE repeat('0', 64)
                END
            WHERE principal_id = ? AND attempt_id = ?
            """,
            signedManifest,
            writerPassword,
            attestorPassword,
            POSTGRES.getPassword());
    assertEquals("23514", executionBindingShapeFailure.getSQLState());
    assertTrue(
        serverMessage(executionBindingShapeFailure).contains(
            "graph_provider_validations_execution_binding_v13"));
    assertEquals(
        executionBindingShapeBefore,
        databaseDigest(admin));

    DatabaseDigest immutableBefore = databaseDigest(admin);
    PSQLException immutableFailure = rejectedAdminMutation(
        admin,
        """
        DELETE FROM agent_graph_provider_validations
        WHERE principal_id = ? AND attempt_id = ?
        """,
        signedManifest,
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertEquals("55000", immutableFailure.getSQLState());
    assertEquals(
        "V13 provider validation policy is immutable",
        serverMessage(immutableFailure));
    assertEquals(immutableBefore, databaseDigest(admin));

    int tamperRepetition = 3;
    GraphAttemptManifest tamperManifest =
        Pack010GraphEvalCatalog.manifest(tamperRepetition);
    GraphAttemptSnapshot tamperSequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, writer, tamperRepetition);
    Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
        writerDataSource,
        tamperManifest,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        Pack010GraphTerminalFixture.catalogIntent(tamperRepetition, 1),
        Instant.now().plus(Duration.ofMinutes(5)));
    attestor.requireValidation(
        tamperManifest,
        tamperSequence7.cursor(),
        keyId,
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash,
        Duration.ofSeconds(2));
    GraphAttemptSnapshot tamperSequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, tamperSequence7, tamperRepetition);
    GraphProviderValidationStatement tamperStatement =
        new GraphProviderValidationStatement(
            Pack010GraphTerminalFixture.catalogAttribution(
                tamperRepetition, 2),
            tamperManifest.manifestHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            GraphProviderValidationDecision.STRUCTURED_FINAL,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-tamper-structured-final-v1"),
            null);
    GraphProviderAttribution tamperAttribution =
        tamperStatement.attribution();
    assertProfileFence(
        attestor,
        admin,
        tamperManifest,
        tamperSequence13,
        new GraphProviderValidationStatement(
            tamperStatement.attribution(),
            tamperStatement.executionBindingHash(),
            transportProfileHash,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-wrong-parser-v1"),
            schemaProfileHash,
            tamperStatement.decision(),
            tamperStatement.decisionHash(),
            null),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertProfileFence(
        attestor,
        admin,
        tamperManifest,
        tamperSequence13,
        new GraphProviderValidationStatement(
            attributionWith(
                tamperAttribution,
                IntegrityHashes.utf8ContentHash(
                    "pack010-v13-stage-wrong-request-v1"),
                tamperAttribution.responseHash(),
                tamperAttribution.modelResolved()),
            tamperStatement.executionBindingHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            tamperStatement.decision(),
            tamperStatement.decisionHash(),
            tamperStatement.failureCode()),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    GraphAttemptCursor wrongHeadCursor =
        new GraphAttemptCursor(
            tamperSequence13.cursor().principalId(),
            tamperSequence13.cursor().attemptId(),
            tamperSequence13.cursor().manifestHash(),
            tamperSequence13.cursor().stateVersion(),
            tamperSequence13.cursor().lastSequence(),
            "f".repeat(64),
            tamperSequence13.cursor().phase());
    assertStageFence(
        attestor,
        admin,
        tamperManifest,
        wrongHeadCursor,
        tamperStatement,
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        tamperStatement,
        ignored -> consumedAttestation.get(),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        tamperStatement,
        expected ->
            sign(
                keyPair,
                transcriptWithAttribution(
                    expected,
                    attributionWith(
                        tamperAttribution,
                        IntegrityHashes.utf8ContentHash(
                            "pack010-v13-wrong-request-v1"),
                        tamperAttribution.responseHash(),
                        tamperAttribution.modelResolved()))),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        tamperStatement,
        expected ->
            sign(
                keyPair,
                transcriptWithAttribution(
                    expected,
                    attributionWith(
                        tamperAttribution,
                        tamperAttribution.requestHash(),
                        IntegrityHashes.utf8ContentHash(
                            "pack010-v13-wrong-response-v1"),
                        tamperAttribution.modelResolved()))),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        tamperStatement,
        expected ->
            sign(
                keyPair,
                transcriptWithAttribution(
                    expected,
                    attributionWith(
                        tamperAttribution,
                        tamperAttribution.requestHash(),
                        tamperAttribution.responseHash(),
                        "fake-pack010-v13-wrong-model-v1"))),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        tamperStatement,
        expected ->
            sign(
                keyPair,
                GraphProviderValidationTranscript.create(
                    expected.challenge(),
                    expected.expected(),
                    expected.attribution(),
                    expected.executionBindingHash(),
                    expected.transportProfileHash(),
                    expected.parserProfileHash(),
                    expected.schemaProfileHash(),
                    GraphProviderValidationDecision.FAILED,
                    IntegrityHashes.utf8ContentHash(
                        "pack010-v13-wrong-decision-v1"),
                    GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED)),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    GraphProviderValidationStatement expectedFailureStatement =
        new GraphProviderValidationStatement(
            tamperAttribution,
            tamperManifest.manifestHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            GraphProviderValidationDecision.FAILED,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-expected-failure-v1"),
            GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED);
    assertSignedTranscriptFence(
        attestorDataSource,
        admin,
        tamperManifest,
        tamperSequence13,
        expectedFailureStatement,
        expected ->
            sign(
                keyPair,
                GraphProviderValidationTranscript.create(
                    expected.challenge(),
                    expected.expected(),
                    expected.attribution(),
                    expected.executionBindingHash(),
                    expected.transportProfileHash(),
                    expected.parserProfileHash(),
                    expected.schemaProfileHash(),
                    expected.decision(),
                    expected.decisionHash(),
                    GraphAttributedFailureCode
                        .MODEL_USAGE_LIMIT_EXCEEDED)),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    JdbcClient.create(admin)
        .sql(
            "UPDATE agent_graph_provider_validation_keys "
                + "SET status = 'REVOKED' WHERE key_id = :keyId")
        .param("keyId", keyId)
        .update();
    try {
      PostgresProviderValidationAttestor revokedKeyAttestor =
          PostgresProviderValidationAttestor.open(attestorDataSource);
      DatabaseDigest revokedKeyBefore = databaseDigest(admin);
      AtomicBoolean revokedKeySignerCalled = new AtomicBoolean();
      GraphAttemptConflictException revokedKeyFailure = captureFailure(
          GraphAttemptConflictException.class,
          () ->
              revokedKeyAttestor.completeValidation(
                  tamperManifest,
                  tamperSequence13.cursor(),
                  tamperStatement,
                  transcript -> {
                    revokedKeySignerCalled.set(true);
                    return sign(keyPair, transcript);
                  }),
          writerPassword,
          attestorPassword,
          POSTGRES.getPassword());
      assertEquals(
          "provider validation was fenced", revokedKeyFailure.getMessage());
      assertFalse(revokedKeySignerCalled.get());
      assertEquals(revokedKeyBefore, databaseDigest(admin));
    } finally {
      JdbcClient.create(admin)
          .sql(
              "UPDATE agent_graph_provider_validation_keys "
                  + "SET status = 'ACTIVE' WHERE key_id = :keyId")
          .param("keyId", keyId)
          .update();
    }
    AtomicInteger keyDriftStaged = new AtomicInteger();
    AtomicInteger keyDriftVerified = new AtomicInteger();
    AtomicInteger keyDriftCommitted = new AtomicInteger();
    AtomicReference<DatabaseDigest> revokedAfterStage =
        new AtomicReference<>();
    PostgresProviderValidationAttestor postStageKeyDriftAttestor =
        PostgresProviderValidationAttestorTestAccess.open(
            attestorDataSource,
            point -> {
              switch (point) {
                case AFTER_PROVIDER_VALIDATION_STAGED -> {
                  keyDriftStaged.incrementAndGet();
                  JdbcClient.create(admin)
                      .sql(
                          "UPDATE agent_graph_provider_validation_keys "
                              + "SET status = 'REVOKED' "
                              + "WHERE key_id = :keyId")
                      .param("keyId", keyId)
                      .update();
                  revokedAfterStage.set(databaseDigest(admin));
                }
                case AFTER_SIGNATURE_VERIFIED ->
                    keyDriftVerified.incrementAndGet();
                case AFTER_PROVIDER_VALIDATION_COMMITTED ->
                    keyDriftCommitted.incrementAndGet();
              }
            });
    AtomicBoolean postStageKeySignerCalled = new AtomicBoolean();
    try {
      GraphAttemptConflictException postStageKeyFailure = captureFailure(
          GraphAttemptConflictException.class,
          () ->
              postStageKeyDriftAttestor.completeValidation(
                  tamperManifest,
                  tamperSequence13.cursor(),
                  tamperStatement,
                  transcript -> {
                    postStageKeySignerCalled.set(true);
                    return sign(keyPair, transcript);
                  }),
          writerPassword,
          attestorPassword,
          POSTGRES.getPassword());
      assertEquals(
          "provider validation was fenced",
          postStageKeyFailure.getMessage());
      assertEquals(1, keyDriftStaged.get());
      assertEquals(1, keyDriftVerified.get());
      assertEquals(0, keyDriftCommitted.get());
      assertTrue(postStageKeySignerCalled.get());
      assertTrue(revokedAfterStage.get() != null);
      assertEquals(revokedAfterStage.get(), databaseDigest(admin));
    } finally {
      JdbcClient.create(admin)
          .sql(
              "UPDATE agent_graph_provider_validation_keys "
                  + "SET status = 'ACTIVE' WHERE key_id = :keyId")
          .param("keyId", keyId)
          .update();
    }
    assertProfileFence(
        attestor,
        admin,
        tamperManifest,
        tamperSequence13,
        new GraphProviderValidationStatement(
            tamperStatement.attribution(),
            tamperStatement.executionBindingHash(),
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-wrong-transport-v1"),
            parserProfileHash,
            schemaProfileHash,
            tamperStatement.decision(),
            tamperStatement.decisionHash(),
            null),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertProfileFence(
        attestor,
        admin,
        tamperManifest,
        tamperSequence13,
        new GraphProviderValidationStatement(
            tamperStatement.attribution(),
            tamperStatement.executionBindingHash(),
            transportProfileHash,
            parserProfileHash,
            IntegrityHashes.utf8ContentHash(
                "pack010-v13-wrong-schema-v1"),
            tamperStatement.decision(),
            tamperStatement.decisionHash(),
            null),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    AtomicInteger stagedProbes = new AtomicInteger();
    AtomicInteger verifiedProbes = new AtomicInteger();
    AtomicInteger committedProbes = new AtomicInteger();
    PostgresProviderValidationAttestor invalidSignatureAttestor =
        PostgresProviderValidationAttestorTestAccess.open(
            attestorDataSource,
            point -> {
              switch (point) {
                case AFTER_PROVIDER_VALIDATION_STAGED ->
                    stagedProbes.incrementAndGet();
                case AFTER_SIGNATURE_VERIFIED ->
                    verifiedProbes.incrementAndGet();
                case AFTER_PROVIDER_VALIDATION_COMMITTED ->
                    committedProbes.incrementAndGet();
              }
            });
    DatabaseDigest signatureBefore = databaseDigest(admin);
    AtomicBoolean invalidSignerCalled = new AtomicBoolean();
    captureFailure(
        GraphAttemptIntegrityException.class,
        () ->
            invalidSignatureAttestor.completeValidation(
                tamperManifest,
                tamperSequence13.cursor(),
                tamperStatement,
                transcript -> {
                  invalidSignerCalled.set(true);
                  return new GraphProviderValidationAttestation(
                      transcript, "00".repeat(64));
                }),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertTrue(invalidSignerCalled.get());
    assertEquals(1, stagedProbes.get());
    assertEquals(0, verifiedProbes.get());
    assertEquals(0, committedProbes.get());
    assertEquals(signatureBefore, databaseDigest(admin));
    assertEquals(
        tamperSequence13,
        Pack010GraphTerminalFixture.verified(writer, tamperManifest));

    AtomicBoolean liveBeforeExpiry = new AtomicBoolean();
    AtomicBoolean expiredBeforeCommit = new AtomicBoolean();
    PostgresProviderValidationAttestor expiryAttestor =
        PostgresProviderValidationAttestorTestAccess.open(
            attestorDataSource,
            point -> {
              if (point
                  == PostgresProviderValidationAttestorTestAccess.ProbePoint
                      .AFTER_PROVIDER_VALIDATION_STAGED) {
                sleep(Duration.ofMillis(2200));
              }
            });
    DatabaseDigest expiryBefore = databaseDigest(admin);
    AtomicBoolean expirySignerCalled = new AtomicBoolean();
    GraphAttemptConflictException expiryFailure = captureFailure(
        GraphAttemptConflictException.class,
        () ->
            expiryAttestor.completeValidation(
                tamperManifest,
                tamperSequence13.cursor(),
                tamperStatement,
                transcript -> {
                  expirySignerCalled.set(true);
                  liveBeforeExpiry.set(
                      transcript.challenge().expiresAt().isAfter(
                          transcript.challenge().issuedAt()));
                  expiredBeforeCommit.set(
                      !databaseNow(attestorDataSource).isBefore(
                          transcript.challenge().expiresAt()));
                  return sign(keyPair, transcript);
                }),
        writerPassword,
        attestorPassword,
        POSTGRES.getPassword());
    assertEquals("provider validation was fenced", expiryFailure.getMessage());
    assertTrue(liveBeforeExpiry.get());
    assertTrue(expiredBeforeCommit.get());
    assertTrue(expirySignerCalled.get());
    assertEquals(expiryBefore, databaseDigest(admin));
    assertEquals(
        tamperSequence13,
        Pack010GraphTerminalFixture.verified(writer, tamperManifest));

    System.out.println(
        "PACK010_V13_PROVIDER_VALIDATION_ACCEPTANCE_RECEIPT "
            + "rawBypass=55000 "
            + "reviewedLoopback=STRUCTURED_FINAL_TO_CONSUMED "
            + "missingKey=FENCED revokedKey=PRE_STAGE,POST_STAGE "
            + "profiles=TRANSPORT,PARSER,SCHEMA "
            + "signedTamper=CHALLENGE,ATTEMPT,REQUEST,RESPONSE,ATTRIBUTION,"
            + "DECISION,FAILURE_CODE wrongCursorHead=FENCED "
            + "invalidSignature=ROLLBACK "
            + "expiry=FENCED crossDatabase=FENCED "
            + "executionBinding=CROSS_ATTEMPT_FENCED "
            + "executionBindingSameTableCheck=23514 "
            + "v14Foundation=PROFILE_ASSERTION_ONLY "
            + "syntheticProfile=ADMIN_FIXTURE "
            + "opaqueHashes=BOUND_NOT_GRAPH_VERIFIED "
            + "v14ExactPicoCost=14056000 "
            + "v14Input=AUTHORITY_42501,"
            + "MISSING_EXTRA_MALFORMED_OVERSIZED_22023 "
            + "v13RawTxA=PRE,POST_FENCED txA=NOT_IMPLEMENTED "
            + "fullPublicTableJsonXmin=UNCHANGED");

  }

  private static void assertRawExecutionBindingFence(
      DataSource attestorDataSource,
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderValidationStatement statement,
      String... secrets) {
    GraphProviderAttribution attribution = statement.attribution();
    var pricing = attribution.pricing();
    ObjectNode payload = JsonMapper.shared().createObjectNode();
    payload.put("principal_id", manifest.principalId());
    payload.put("attempt_id", manifest.attemptId());
    payload.put("manifest_hash", manifest.manifestHash());
    payload.put(
        "execution_binding_hash", statement.executionBindingHash());
    payload.put("expected_sequence", expected.lastSequence());
    payload.put("expected_head_hash", expected.headHash());
    payload.put("request_ordinal", attribution.requestOrdinal());
    payload.put("request_hash", attribution.requestHash());
    payload.put("response_hash", attribution.responseHash());
    payload.put("attribution_hash", attribution.attributionHash());
    payload.put("provider_actor", attribution.providerActor());
    payload.put("model_requested", attribution.modelRequested());
    payload.put("model_resolved", attribution.modelResolved());
    payload.put("pricing_profile_id", pricing.id());
    payload.put("pricing_provider", pricing.provider());
    payload.put(
        "pricing_profile_fingerprint", pricing.fingerprint());
    payload.put(
        "uncached_input_nano_usd_per_token",
        pricing.uncachedInputNanoUsdPerToken());
    payload.put(
        "cached_input_nano_usd_per_token",
        pricing.cachedInputNanoUsdPerToken());
    payload.put(
        "output_nano_usd_per_token",
        pricing.outputNanoUsdPerToken());
    payload.put("input_tokens", attribution.inputTokens());
    payload.put("cached_input_tokens", attribution.cachedInputTokens());
    payload.put("output_tokens", attribution.outputTokens());
    payload.put(
        "reasoning_output_tokens", attribution.reasoningOutputTokens());
    payload.put("total_tokens", attribution.totalTokens());
    payload.put("observed_cost_usd", attribution.observedCostUsd());
    payload.put(
        "transport_profile_hash", statement.transportProfileHash());
    payload.put("parser_profile_hash", statement.parserProfileHash());
    payload.put("schema_profile_hash", statement.schemaProfileHash());
    payload.put("decision_kind", statement.decision().name());
    payload.put("decision_hash", statement.decisionHash());
    if (statement.failureCode() == null) {
      payload.putNull("failure_code");
    } else {
      payload.put("failure_code", statement.failureCode().name());
    }
    DatabaseDigest before = databaseDigest(admin);
    RuntimeException failure = captureFailure(
        RuntimeException.class,
        () ->
            JdbcClient.create(attestorDataSource)
                .sql(
                    """
                    SELECT protocol_version
                    FROM public.agent_graph_stage_provider_validation_v13(
                      CAST(:payload AS jsonb))
                    """)
                .param("payload", payload.toString())
                .query(String.class)
                .single(),
        secrets);
    PSQLException sqlFailure = rootPostgresFailure(failure);
    assertEquals("55000", sqlFailure.getSQLState());
    assertEquals(
        "V13 provider validation stage was fenced",
        serverMessage(sqlFailure));
    assertEquals(before, databaseDigest(admin));
  }

  private static void assertCrossDatabaseCloneIsFenced(
      DataSource sourceAdmin,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      GraphProviderValidationStatement statement,
      String attestorPassword,
      String writerPassword,
      String adminPassword) {
    String sourceDatabase = POSTGRES.getDatabaseName();
    String cloneDatabase =
        "pack010_v13_clone_"
            + UUID.randomUUID().toString().replace("-", "");
    DataSource clusterAdmin =
        databaseDataSource(
            "postgres", POSTGRES.getUsername(), POSTGRES.getPassword());
    terminateDatabaseSessions(clusterAdmin, sourceDatabase);
    JdbcClient.create(clusterAdmin)
        .sql(
            "CREATE DATABASE "
                + checkedDatabaseName(cloneDatabase)
                + " TEMPLATE "
                + checkedDatabaseName(sourceDatabase))
        .update();
    try {
      JdbcClient cluster = JdbcClient.create(clusterAdmin);
      cluster.sql(
              "REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE "
                  + checkedDatabaseName(cloneDatabase)
                  + " FROM PUBLIC")
          .update();
      cluster.sql(
              "GRANT CONNECT ON DATABASE "
                  + checkedDatabaseName(cloneDatabase)
                  + " TO emergeos_provider_attestor")
          .update();
      DataSource cloneAdmin =
          databaseDataSource(
              cloneDatabase,
              POSTGRES.getUsername(),
              POSTGRES.getPassword());
      DataSource cloneAttestor =
          databaseDataSource(
              cloneDatabase,
              "emergeos_provider_attestor",
              attestorPassword);
      JdbcClient cloneJdbc = JdbcClient.create(cloneAdmin);
      assertTrue(
          cloneJdbc.sql(
                  "SELECT state = 'REQUIRED' "
                      + "AND database_name = :sourceDatabase "
                      + "AND database_oid = ("
                      + "SELECT oid FROM pg_catalog.pg_database "
                      + "WHERE datname = :sourceDatabase) "
                      + "AND database_name <> current_database() "
                      + "AND database_oid <> ("
                      + "SELECT oid FROM pg_catalog.pg_database "
                      + "WHERE datname = current_database()) "
                      + "AND session_expires_at > "
                      + "pg_catalog.clock_timestamp() + interval '1 minute' "
                      + "FROM public.agent_graph_provider_validations "
                      + "WHERE principal_id = :principalId "
                      + "AND attempt_id = :attemptId")
              .param("sourceDatabase", sourceDatabase)
              .param("principalId", manifest.principalId())
              .param("attemptId", manifest.attemptId())
              .query(Boolean.class)
              .single());
      DatabaseDigest sourceBefore = databaseDigest(sourceAdmin);
      DatabaseDigest cloneBefore = databaseDigest(cloneAdmin);
      assertEquals(sourceBefore, cloneBefore);
      PostgresProviderValidationAttestor cloned =
          PostgresProviderValidationAttestor.open(cloneAttestor);
      AtomicBoolean signerCalled = new AtomicBoolean();
      GraphAttemptConflictException failure = captureFailure(
          GraphAttemptConflictException.class,
          () ->
              cloned.completeValidation(
                  manifest,
                  sequence13.cursor(),
                  statement,
                  transcript -> {
                    signerCalled.set(true);
                    throw new AssertionError(
                        "CROSS_DATABASE_SIGNER_MUST_NOT_BE_CALLED");
                  }),
          attestorPassword,
          writerPassword,
          adminPassword);
      assertEquals("provider validation was fenced", failure.getMessage());
      assertFalse(signerCalled.get());
      assertEquals(sourceBefore, databaseDigest(sourceAdmin));
      assertEquals(cloneBefore, databaseDigest(cloneAdmin));
    } finally {
      terminateDatabaseSessions(clusterAdmin, cloneDatabase);
      JdbcClient.create(clusterAdmin)
          .sql(
              "DROP DATABASE IF EXISTS "
                  + checkedDatabaseName(cloneDatabase))
          .update();
    }
  }

  private static void assertDeepSeekV14ProviderProfileAuthority(
      DataSource admin,
      DataSource profileAttestor,
      PostgresGraphAttemptStore writer,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String... secrets) {
    String profileId =
        "deepseek-v4-flash-responses-public-list-2026-08-11-v1";
    String providerId = "deepseek";
    String providerProtocol = "deepseek.responses";
    String transportProfileId = "deepseek-responses-http-v1";
    String parserProfileId = "deepseek-responses-parser-v1";
    String schemaProfileId = "deepseek-structured-final-v1";
    String modelProfileId = "deepseek-v4-flash-effort-none-v1";
    String pricingProfileId =
        "deepseek-v4-flash-public-list-2026-08-11";
    String transportProfileHash =
        IntegrityHashes.utf8ContentHash(transportProfileId);
    String parserProfileHash =
        IntegrityHashes.utf8ContentHash(parserProfileId);
    String schemaProfileHash =
        IntegrityHashes.utf8ContentHash(schemaProfileId);
    String modelProfileHash =
        IntegrityHashes.utf8ContentHash(modelProfileId);
    String modelResolutionProfileHash =
        IntegrityHashes.utf8ContentHash(
            "deepseek-v4-flash-response-family-hash-v1");
    String pricingProfileFingerprint =
        IntegrityHashes.utf8ContentHash(
            "deepseek-v4-flash-public-list-pico-v1");
    String pricingSourceHash =
        IntegrityHashes.utf8ContentHash(
            "deepseek-public-pricing-reviewed-2026-08-11");
    V14ProfileSeed profileSeed =
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
                  :profileId, :providerId, :providerProtocol,
                  :transportProfileId, :transportProfileHash,
                  :parserProfileId, :parserProfileHash,
                  :schemaProfileId, :schemaProfileHash,
                  :modelProfileId, :modelProfileHash,
                  'deepseek-v4-flash', :modelResolutionProfileHash,
                  :pricingProfileId, :providerId,
                  :pricingProfileFingerprint, :pricingSourceHash,
                  floor(extract(epoch from clock_timestamp()
                    - interval '1 minute') * 1000000)::bigint,
                  floor(extract(epoch from clock_timestamp()
                    + interval '10 minutes') * 1000000)::bigint,
                  'PICO_USD_PER_TOKEN', 140000, 2800, 280000,
                  'NONE', 'ACTIVE'
                )
                RETURNING btrim(profile_hash)::text AS profile_hash,
                  pricing_effective_from_epoch_micros,
                  pricing_effective_until_epoch_micros
                """)
            .param("profileId", profileId)
            .param("providerId", providerId)
            .param("providerProtocol", providerProtocol)
            .param("transportProfileId", transportProfileId)
            .param("transportProfileHash", transportProfileHash)
            .param("parserProfileId", parserProfileId)
            .param("parserProfileHash", parserProfileHash)
            .param("schemaProfileId", schemaProfileId)
            .param("schemaProfileHash", schemaProfileHash)
            .param("modelProfileId", modelProfileId)
            .param("modelProfileHash", modelProfileHash)
            .param(
                "modelResolutionProfileHash",
                modelResolutionProfileHash)
            .param("pricingProfileId", pricingProfileId)
            .param(
                "pricingProfileFingerprint",
                pricingProfileFingerprint)
            .param("pricingSourceHash", pricingSourceHash)
            .query(
                (row, ignored) ->
                    new V14ProfileSeed(
                        row.getString("profile_hash"),
                        row.getLong(
                            "pricing_effective_from_epoch_micros"),
                        row.getLong(
                            "pricing_effective_until_epoch_micros")))
            .single();
    String profileHash = profileSeed.profileHash();
    assertTrue(profileHash.matches("[a-f0-9]{64}"));
    assertEquals(
        framedSha256V14(
            "emergeos.provider-profile.v14",
            profileId,
            providerId,
            providerProtocol,
            transportProfileId,
            transportProfileHash,
            parserProfileId,
            parserProfileHash,
            schemaProfileId,
            schemaProfileHash,
            modelProfileId,
            modelProfileHash,
            "deepseek-v4-flash",
            modelResolutionProfileHash,
            pricingProfileId,
            providerId,
            pricingProfileFingerprint,
            pricingSourceHash,
            Long.toString(
                profileSeed.pricingEffectiveFromEpochMicros()),
            Long.toString(
                profileSeed.pricingEffectiveUntilEpochMicros()),
            "PICO_USD_PER_TOKEN",
            "140000",
            "2800",
            "280000",
            "NONE"),
        profileHash);
    assertEquals(
        0L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM information_schema.role_table_grants grant_row
                WHERE grant_row.grantee =
                      'emergeos_provider_attestor_v14'
                """)
            .query(Long.class)
            .single());

    ObjectNode statement = JsonMapper.shared().createObjectNode();
    statement.put("profile_id", profileId);
    statement.put("profile_hash", profileHash);
    statement.put("provider_id", providerId);
    statement.put("provider_protocol", providerProtocol);
    statement.put("transport_profile_id", transportProfileId);
    statement.put("transport_profile_hash", transportProfileHash);
    statement.put("parser_profile_id", parserProfileId);
    statement.put("parser_profile_hash", parserProfileHash);
    statement.put("schema_profile_id", schemaProfileId);
    statement.put("schema_profile_hash", schemaProfileHash);
    statement.put("model_profile_id", modelProfileId);
    statement.put("model_profile_hash", modelProfileHash);
    statement.put("model_requested", "deepseek-v4-flash");
    statement.put(
        "model_resolved_hash",
        IntegrityHashes.utf8ContentHash("deepseek-v4-flash"));
    statement.put(
        "model_resolution_profile_hash",
        modelResolutionProfileHash);
    statement.put("pricing_profile_id", pricingProfileId);
    statement.put("pricing_provider_id", providerId);
    statement.put(
        "pricing_profile_fingerprint", pricingProfileFingerprint);
    statement.put("pricing_source_hash", pricingSourceHash);
    statement.put("rate_unit", "PICO_USD_PER_TOKEN");
    statement.put("uncached_input_pico_usd_per_token", 140000L);
    statement.put("cached_input_pico_usd_per_token", 2800L);
    statement.put("output_pico_usd_per_token", 280000L);
    statement.put("input_tokens", 100L);
    statement.put("cached_input_tokens", 20L);
    statement.put("output_tokens", 10L);
    statement.put("reasoning_output_tokens", 0L);
    statement.put("total_tokens", 110L);
    statement.put("reasoning_policy", "NONE");
    statement.put("observed_cost_pico_usd", 14056000L);
    statement.put(
        "execution_binding_hash", manifest.manifestHash());
    statement.put(
        "request_hash",
        IntegrityHashes.utf8ContentHash("deepseek-v14-request"));
    statement.put(
        "response_hash",
        IntegrityHashes.utf8ContentHash("deepseek-v14-response"));
    statement.put(
        "decision_hash",
        IntegrityHashes.utf8ContentHash("deepseek-v14-decision"));

    assertV14ProviderStatementAuthorityRejected(
        admin, statement, secrets);
    ObjectNode missingField = statement.deepCopy();
    missingField.remove("request_hash");
    assertV14ProviderStatementInvalid(
        profileAttestor, admin, missingField, secrets);
    ObjectNode malformedField = statement.deepCopy();
    malformedField.put("input_tokens", "100");
    assertV14ProviderStatementInvalid(
        profileAttestor, admin, malformedField, secrets);
    assertV14ProviderStatementInvalid(
        profileAttestor,
        admin,
        JsonMapper.shared().createArrayNode().add("malformed"),
        secrets);
    String privateOversizedSentinel =
        "PRIVATE_V14_OVERSIZED_SENTINEL_4c090d218e7a";
    StringBuilder oversizedModel =
        new StringBuilder(privateOversizedSentinel);
    for (int index = 0; index < 320; index++) {
      oversizedModel.append(
          IntegrityHashes.utf8ContentHash(
              "v14-oversized-input-" + index));
    }
    ObjectNode oversizedStatement = statement.deepCopy();
    oversizedStatement.put("model_requested", oversizedModel.toString());
    int oversizedPayloadBytes =
        JdbcClient.create(admin)
            .sql("SELECT pg_column_size(CAST(:payload AS jsonb))")
            .param("payload", oversizedStatement.toString())
            .query(Integer.class)
            .single();
    assertTrue(oversizedPayloadBytes > 16384);
    assertV14ProviderStatementInvalid(
        profileAttestor,
        admin,
        oversizedStatement,
        secretsWith(secrets, privateOversizedSentinel));
    assertEquals(
        0L,
        databaseTextMatchCount(admin, privateOversizedSentinel));
    String privateRawBodySentinel =
        "PRIVATE_V14_RAW_BODY_SENTINEL_8f375dfef074";
    ObjectNode extraField = statement.deepCopy();
    extraField.put("raw_response", privateRawBodySentinel);
    assertV14ProviderStatementInvalid(
        profileAttestor,
        admin,
        extraField,
        secretsWith(secrets, privateRawBodySentinel));
    assertEquals(
        0L,
        databaseTextMatchCount(admin, privateRawBodySentinel));
    ObjectNode wrongProvider = statement.deepCopy();
    wrongProvider.put("provider_id", "openai");
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongProvider, secrets);
    ObjectNode wrongPricingProvider = statement.deepCopy();
    wrongPricingProvider.put("pricing_provider_id", "openai");
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongPricingProvider, secrets);
    ObjectNode wrongPicoRate = statement.deepCopy();
    wrongPicoRate.put("cached_input_pico_usd_per_token", 3000L);
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongPicoRate, secrets);
    ObjectNode wrongTransportProfile = statement.deepCopy();
    wrongTransportProfile.put(
        "transport_profile_hash",
        IntegrityHashes.utf8ContentHash("wrong-transport-profile"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongTransportProfile, secrets);
    ObjectNode wrongParserProfile = statement.deepCopy();
    wrongParserProfile.put(
        "parser_profile_hash",
        IntegrityHashes.utf8ContentHash("wrong-parser-profile"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongParserProfile, secrets);
    ObjectNode wrongSchemaProfile = statement.deepCopy();
    wrongSchemaProfile.put(
        "schema_profile_hash",
        IntegrityHashes.utf8ContentHash("wrong-schema-profile"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongSchemaProfile, secrets);
    ObjectNode wrongModelProfile = statement.deepCopy();
    wrongModelProfile.put(
        "model_resolution_profile_hash",
        IntegrityHashes.utf8ContentHash("wrong-model-profile"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongModelProfile, secrets);
    ObjectNode wrongPricingProfile = statement.deepCopy();
    wrongPricingProfile.put(
        "pricing_profile_id", "openai-public-list-v1");
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongPricingProfile, secrets);
    ObjectNode wrongPricingFingerprint = statement.deepCopy();
    wrongPricingFingerprint.put(
        "pricing_profile_fingerprint",
        IntegrityHashes.utf8ContentHash("wrong-pricing-fingerprint"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongPricingFingerprint, secrets);
    ObjectNode wrongPricingSource = statement.deepCopy();
    wrongPricingSource.put(
        "pricing_source_hash",
        IntegrityHashes.utf8ContentHash("wrong-pricing-source"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongPricingSource, secrets);
    ObjectNode wrongProfileHash = statement.deepCopy();
    wrongProfileHash.put(
        "profile_hash",
        IntegrityHashes.utf8ContentHash("wrong-provider-profile"));
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongProfileHash, secrets);
    ObjectNode wrongClaimedCost = statement.deepCopy();
    wrongClaimedCost.put("observed_cost_pico_usd", 14056001L);
    assertV14ProviderStatementFenced(
        profileAttestor, admin, wrongClaimedCost, secrets);

    DatabaseDigest before = databaseDigest(admin);
    V14ProfileAssertion assertion =
        callV14ProfileAssertion(profileAttestor, statement);
    assertEquals(profileHash, assertion.profileHash());
    assertEquals(
        new BigDecimal("14056000"),
        assertion.observedCostPicoUsd());
    assertEquals(
        expectedV14StatementHash(profileHash, statement),
        assertion.statementHash());
    assertEquals(before, databaseDigest(admin));
    ObjectNode alternateOpaqueRequest = statement.deepCopy();
    alternateOpaqueRequest.put(
        "request_hash",
        IntegrityHashes.utf8ContentHash(
            "deepseek-v14-alternate-opaque-request"));
    V14ProfileAssertion alternateAssertion =
        callV14ProfileAssertion(profileAttestor, alternateOpaqueRequest);
    assertEquals(profileHash, alternateAssertion.profileHash());
    assertEquals(
        new BigDecimal("14056000"),
        alternateAssertion.observedCostPicoUsd());
    assertEquals(
        expectedV14StatementHash(
            profileHash, alternateOpaqueRequest),
        alternateAssertion.statementHash());
    assertNotEquals(
        assertion.statementHash(), alternateAssertion.statementHash());
    assertEquals(before, databaseDigest(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
  }

  private static V14ProfileAssertion callV14ProfileAssertion(
      DataSource profileAttestor, JsonNode statement) {
    return JdbcClient.create(profileAttestor)
        .sql(
            """
            SELECT btrim(profile_hash)::text AS profile_hash,
                   observed_cost_pico_usd,
                   btrim(statement_hash)::text AS statement_hash
            FROM agent_graph_assert_provider_statement_v14(
              CAST(:payload AS jsonb))
            """)
        .param("payload", statement.toString())
        .query(
            (row, ignored) ->
                new V14ProfileAssertion(
                    row.getString("profile_hash"),
                    row.getBigDecimal("observed_cost_pico_usd"),
                    row.getString("statement_hash")))
        .single();
  }

  private static void assertV14ProviderStatementAuthorityRejected(
      DataSource admin,
      ObjectNode statement,
      String... secrets) {
    DatabaseDigest before = databaseDigest(admin);
    RuntimeException failure =
        captureFailure(
            RuntimeException.class,
            () -> callV14ProfileAssertion(admin, statement),
            secrets);
    PSQLException postgres = rootPostgresFailure(failure);
    assertEquals(
        "V14 provider statement authority rejected",
        serverMessage(postgres));
    assertEquals("42501", postgres.getSQLState());
    assertEquals(before, databaseDigest(admin));
  }

  private static void assertV14ProviderStatementInvalid(
      DataSource profileAttestor,
      DataSource admin,
      JsonNode statement,
      String... secrets) {
    DatabaseDigest before = databaseDigest(admin);
    RuntimeException failure =
        captureFailure(
            RuntimeException.class,
            () -> callV14ProfileAssertion(profileAttestor, statement),
            secrets);
    PSQLException postgres = rootPostgresFailure(failure);
    assertEquals(
        "V14 provider statement input is invalid",
        serverMessage(postgres));
    assertEquals("22023", postgres.getSQLState());
    assertEquals(before, databaseDigest(admin));
  }

  private static void assertV14ProviderStatementFenced(
      DataSource profileAttestor,
      DataSource admin,
      ObjectNode statement,
      String... secrets) {
    DatabaseDigest before = databaseDigest(admin);
    RuntimeException failure =
        captureFailure(
            RuntimeException.class,
            () ->
                JdbcClient.create(profileAttestor)
                    .sql(
                        """
                        SELECT profile_hash
                        FROM agent_graph_assert_provider_statement_v14(
                          CAST(:payload AS jsonb))
                        """)
                    .param("payload", statement.toString())
                    .query(String.class)
                    .single(),
            secrets);
    PSQLException postgres = rootPostgresFailure(failure);
    assertEquals(
        "V14 provider statement was fenced",
        serverMessage(postgres));
    assertEquals("55000", postgres.getSQLState());
    assertEquals(before, databaseDigest(admin));
  }

  private static String expectedV14StatementHash(
      String profileHash, ObjectNode statement) {
    return framedSha256V14(
        "emergeos.provider-statement.v14",
        profileHash,
        statement.path("model_resolved_hash").asText(),
        statement.path("input_tokens").asText(),
        statement.path("cached_input_tokens").asText(),
        statement.path("output_tokens").asText(),
        statement.path("reasoning_output_tokens").asText(),
        statement.path("total_tokens").asText(),
        statement.path("observed_cost_pico_usd").asText(),
        statement.path("execution_binding_hash").asText(),
        statement.path("request_hash").asText(),
        statement.path("response_hash").asText(),
        statement.path("decision_hash").asText());
  }

  private static String framedSha256V14(
      String domain, String... fields) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    framed.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    framed.write(0);
    for (String field : fields) {
      byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(
          ByteBuffer.allocate(Integer.BYTES)
              .putInt(encoded.length)
              .array());
      framed.writeBytes(encoded);
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(framed.toByteArray()));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static String[] secretsWith(
      String[] secrets, String additionalSecret) {
    String[] extended = new String[secrets.length + 1];
    System.arraycopy(secrets, 0, extended, 0, secrets.length);
    extended[secrets.length] = additionalSecret;
    return extended;
  }

  private static void assertProfileFence(
      PostgresProviderValidationAttestor attestor,
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      GraphProviderValidationStatement statement,
      String... secrets) {
    assertStageFence(
        attestor,
        admin,
        manifest,
        sequence13.cursor(),
        statement,
        secrets);
  }

  private static void assertStageFence(
      PostgresProviderValidationAttestor attestor,
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor,
      GraphProviderValidationStatement statement,
      String... secrets) {
    DatabaseDigest before = databaseDigest(admin);
    AtomicBoolean signerCalled = new AtomicBoolean();
    GraphAttemptConflictException failure = captureFailure(
        GraphAttemptConflictException.class,
        () ->
            attestor.completeValidation(
                manifest,
                cursor,
                statement,
                transcript -> {
                  signerCalled.set(true);
                  throw new AssertionError("SIGNER_MUST_NOT_BE_CALLED");
                }),
        secrets);
    assertEquals("provider validation was fenced", failure.getMessage());
    assertFalse(signerCalled.get());
    assertEquals(before, databaseDigest(admin));
  }

  private static void assertSignedTranscriptFence(
      DataSource attestorDataSource,
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      GraphProviderValidationStatement statement,
      GraphProviderValidationSigner signer,
      String... secrets) {
    AtomicInteger stagedProbes = new AtomicInteger();
    AtomicInteger verifiedProbes = new AtomicInteger();
    AtomicInteger committedProbes = new AtomicInteger();
    PostgresProviderValidationAttestor guarded =
        PostgresProviderValidationAttestorTestAccess.open(
            attestorDataSource,
            point -> {
              switch (point) {
                case AFTER_PROVIDER_VALIDATION_STAGED ->
                    stagedProbes.incrementAndGet();
                case AFTER_SIGNATURE_VERIFIED ->
                    verifiedProbes.incrementAndGet();
                case AFTER_PROVIDER_VALIDATION_COMMITTED ->
                    committedProbes.incrementAndGet();
              }
            });
    DatabaseDigest before = databaseDigest(admin);
    captureFailure(
        GraphAttemptIntegrityException.class,
        () ->
            guarded.completeValidation(
                manifest, sequence13.cursor(), statement, signer),
        secrets);
    assertEquals(1, stagedProbes.get());
    assertEquals(0, verifiedProbes.get());
    assertEquals(0, committedProbes.get());
    assertEquals(before, databaseDigest(admin));
  }

  private static AgentModel.ModelCallContext modelContext(
      BigDecimal remainingBudgetUsd) {
    return new AgentModel.ModelCallContext(
        1_500, remainingBudgetUsd, CancellationSignal.never());
  }

  private static GraphProviderIntent reviewedIntent(
      OpenAiResponsesModel.ProviderInvocation invocation) {
    return new GraphProviderIntent(
        invocation.requestOrdinal(),
        invocation.requestHash(),
        invocation.modelRequested());
  }

  private static GraphProviderAttribution reviewedAttribution(
      int repetition,
      OpenAiResponsesModel.ProviderOutcomeReceipt outcome) {
    OpenAiResponsesModel.ProviderAttributionReceipt receipt =
        outcome.attribution();
    var pricing =
        Pack010GraphEvalCatalog.workerProfile(repetition).pricing();
    return GraphProviderAttribution.create(
        receipt.invocation().requestOrdinal(),
        receipt.invocation().requestHash(),
        receipt.responseHash(),
        Pack010GraphEvalCatalog.manifest(repetition).childActor(),
        receipt.invocation().modelRequested(),
        receipt.modelResolved(),
        pricing.graphSnapshot(),
        receipt.inputTokens(),
        receipt.cachedInputTokens(),
        receipt.outputTokens(),
        receipt.reasoningOutputTokens(),
        receipt.totalTokens(),
        receipt.observedCostUsd());
  }

  private static String reviewedToolResponse(String captureRef) {
    return """
        {
          "id":"resp-v13-tool",
          "object":"response",
          "created_at":1786400000,
          "model":"gpt-5.6-terra",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":{"type":"function","name":"capture_read"},
          "tools":[],
          "output":[{
            "id":"function-v13-tool",
            "type":"function_call",
            "call_id":"call-v13-reviewed",
            "name":"capture_read",
            "arguments":"{\\"reference\\":\\"%s\\"}",
            "status":"completed"
          }],
          "usage":{
            "input_tokens":1,
            "input_tokens_details":{"cached_tokens":0},
            "output_tokens":1,
            "output_tokens_details":{"reasoning_tokens":0},
            "total_tokens":2
          }
        }
        """.formatted(captureRef);
  }

  private static String reviewedFinalResponse(String captureRef) {
    return """
        {
          "id":"resp-v13-final",
          "object":"response",
          "created_at":1786400001,
          "model":"gpt-5.6-terra",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":"none",
          "tools":[],
          "output":[{
            "id":"message-v13-final",
            "type":"message",
            "role":"assistant",
            "status":"completed",
            "content":[{
              "type":"output_text",
              "annotations":[],
              "text":"{\\"content\\":\\"这是一段公开测试短文。\\",\\"evidenceRefs\\":[\\"%s\\"]}"
            }]
          }],
          "usage":{
            "input_tokens":1,
            "input_tokens_details":{"cached_tokens":0},
            "output_tokens":1,
            "output_tokens_details":{"reasoning_tokens":0},
            "total_tokens":2
          }
        }
        """.formatted(captureRef);
  }

  private static GraphProviderValidationTranscript transcriptWithAttribution(
      GraphProviderValidationTranscript expected,
      GraphProviderAttribution attribution) {
    return GraphProviderValidationTranscript.create(
        expected.challenge(),
        expected.expected(),
        attribution,
        expected.executionBindingHash(),
        expected.transportProfileHash(),
        expected.parserProfileHash(),
        expected.schemaProfileHash(),
        expected.decision(),
        expected.decisionHash(),
        expected.failureCode());
  }

  private static GraphProviderAttribution attributionWith(
      GraphProviderAttribution source,
      String requestHash,
      String responseHash,
      String modelResolved) {
    return GraphProviderAttribution.create(
        source.requestOrdinal(),
        requestHash,
        responseHash,
        source.providerActor(),
        source.modelRequested(),
        modelResolved,
        source.pricing(),
        source.inputTokens(),
        source.cachedInputTokens(),
        source.outputTokens(),
        source.reasoningOutputTokens(),
        source.totalTokens(),
        source.observedCostUsd());
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("V13_EXPIRY_WAIT_INTERRUPTED");
    }
  }

  private static Instant databaseNow(DataSource source) {
    return JdbcClient.create(source)
        .sql("SELECT pg_catalog.clock_timestamp()")
        .query(java.time.OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private static GraphProviderValidationAttestation sign(
      KeyPair keyPair,
      GraphProviderValidationTranscript transcript) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(keyPair.getPrivate());
      signature.update(transcript.signatureMaterial());
      return new GraphProviderValidationAttestation(
          transcript,
          HexFormat.of().formatHex(signature.sign()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(
          "EPHEMERAL_PROVIDER_VALIDATION_SIGNING_FAILED");
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

  private static long databaseTextMatchCount(
      DataSource source, String sentinel) {
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
      long matches = 0;
      for (String table : tables) {
        if (!table.matches("[a-z][a-z0-9_]{0,62}")) {
          throw new IllegalStateException("PUBLIC_TABLE_NAME_INVALID");
        }
        matches +=
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM public."%s" row_data
                    WHERE pg_catalog.to_jsonb(row_data)::text
                          LIKE '%%' || :sentinel || '%%'
                       OR pg_catalog.to_jsonb(row_data)::text
                          LIKE '%%'
                               || pg_catalog.encode(
                                    pg_catalog.convert_to(
                                      :sentinel,
                                      'UTF8'),
                                    'hex')
                               || '%%'
                    """.formatted(table))
                .param("sentinel", sentinel)
                .query(Long.class)
                .single();
      }
      connection.rollback();
      return matches;
    } catch (SQLException failure) {
      throw new IllegalStateException("DATABASE_TEXT_SCAN_FAILED");
    }
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

  private static PSQLException rejectedAdminMutation(
      DataSource admin,
      String mutation,
      GraphAttemptManifest manifest,
      String... secrets) {
    Throwable observed = null;
    try (Connection connection = admin.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(mutation)) {
          statement.setString(1, manifest.principalId());
          statement.setString(2, manifest.attemptId());
          statement.executeUpdate();
        }
        try (Statement constraints = connection.createStatement()) {
          constraints.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
        connection.commit();
      } catch (Throwable failure) {
        observed = failure;
        connection.rollback();
      }
    } catch (SQLException failure) {
      if (observed == null) {
        observed = failure;
      }
    }
    if (observed == null) {
      throw new AssertionError("ADMIN_MUTATION_WAS_NOT_REJECTED");
    }
    assertSecretAbsent(observed, secrets);
    return rootPostgresFailure(observed);
  }

  private static PSQLException
      rejectedAdminCheckMutationWithUserTriggersDisabled(
          DataSource admin,
          String mutation,
          GraphAttemptManifest manifest,
          String... secrets) {
    Throwable observed = null;
    try (Connection connection = admin.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (Statement disableTriggers = connection.createStatement()) {
          disableTriggers.execute(
              "ALTER TABLE agent_graph_provider_validations "
                  + "DISABLE TRIGGER USER");
        }
        try (PreparedStatement statement =
            connection.prepareStatement(mutation)) {
          statement.setString(1, manifest.principalId());
          statement.setString(2, manifest.attemptId());
          statement.executeUpdate();
        }
        try (Statement constraints = connection.createStatement()) {
          constraints.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
      } catch (Throwable failure) {
        observed = failure;
      } finally {
        connection.rollback();
      }
    } catch (SQLException failure) {
      if (observed == null) {
        observed = failure;
      }
    }
    if (observed == null) {
      throw new AssertionError(
          "ADMIN_CHECK_MUTATION_WAS_NOT_REJECTED");
    }
    assertSecretAbsent(observed, secrets);
    return rootPostgresFailure(observed);
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
    return databaseDataSource(POSTGRES.getDatabaseName(), user, password);
  }

  private static DataSource databaseDataSource(
      String database, String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + checkedDatabaseName(database));
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void terminateDatabaseSessions(
      DataSource clusterAdmin, String database) {
    JdbcClient.create(clusterAdmin)
        .sql(
            "SELECT pg_catalog.pg_terminate_backend(pid) "
                + "FROM pg_catalog.pg_stat_activity "
                + "WHERE datname = :database "
                + "AND pid <> pg_catalog.pg_backend_pid()")
        .param("database", checkedDatabaseName(database))
        .query(Boolean.class)
        .list();
  }

  private static String checkedDatabaseName(String database) {
    if (database == null || !database.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("DATABASE_NAME_INVALID");
    }
    return database;
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

  private static void provisionProductionRoles(
      DataSource admin,
      String writerPassword,
      String attestorPassword,
      String profileAttestorPassword) throws SQLException {
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
    setEphemeralPassword(
        admin,
        "emergeos_provider_attestor_v14",
        profileAttestorPassword);
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!("emergeos_graph_prefix_writer".equals(role)
        || "emergeos_provider_attestor".equals(role)
        || "emergeos_provider_attestor_v14".equals(role))) {
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
        Pack010ProviderValidationAttestationAcceptanceTest.class
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

  private record RowDigest(long rowCount, String digest) {}

  private record DatabaseDigest(
      int tableCount, long rowCount, String digest) {}

  private record V14ProfileSeed(
      String profileHash,
      long pricingEffectiveFromEpochMicros,
      long pricingEffectiveUntilEpochMicros) {}

  private record V14ProfileAssertion(
      String profileHash,
      BigDecimal observedCostPicoUsd,
      String statementHash) {}

  private static final class ReviewedLoopbackResponsesServer
      implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final String[] responses;
    private final AtomicInteger requestCount = new AtomicInteger();

    private ReviewedLoopbackResponsesServer(String... responses)
        throws IOException {
      this.responses = responses.clone();
      server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
              0);
      executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext("/v1/responses", this::respond);
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/v1";
    }

    private int requestCount() {
      return requestCount.get();
    }

    private void respond(HttpExchange exchange) throws IOException {
      exchange.getRequestBody().readAllBytes();
      int index = requestCount.getAndIncrement();
      byte[] bytes =
          responses[Math.min(index, responses.length - 1)]
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.close();
    }
  }

}
