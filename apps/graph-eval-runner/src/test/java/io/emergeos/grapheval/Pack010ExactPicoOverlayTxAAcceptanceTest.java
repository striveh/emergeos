package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresExactPicoProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresExactPicoProviderValidationAttestorTestAccess;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphExactPicoProviderSignatureVerifier;
import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Acceptance Red for the first V16 exact-pico overlay TX-A.
 *
 * <p>V16 froze with no production Java surface. V17 added a dormant
 * public-key verifier. V18 adds one dormant typed stage-verify-commit adapter
 * while keeping public raw stage/commit methods and app consumers absent. The
 * test also exercises the closed V16 PostgreSQL ABI directly and requires the
 * exact-pico overlay to advance to its own sequence 14 without changing any
 * V1-V15 row from the post-fixture baseline or the verified V8 sequence-13
 * snapshot. Each negative fence compares its own immediate database baseline;
 * intentional fixture/status mutations are not attributed to the V16
 * stage/commit call.
 * PostgreSQL does not verify Ed25519 in this slice; possession of the V16
 * attestor credential is part of the trusted-caller boundary.
 */
@Testcontainers
class Pack010ExactPicoOverlayTxAAcceptanceTest {

  private static final String V15_ROLE =
      "emergeos_provider_attestor_v15";
  private static final String V16_ROLE =
      "emergeos_provider_attestor_v16";
  private static final String SYNTHETIC_PROFILE_ID =
      "pack010-gpt-5.6-terra-synthetic-exact-pico-v1";
  private static final String SYNTHETIC_PROVIDER_ID =
      "pack010-synthetic";
  private static final String SYNTHETIC_PROVIDER_PROTOCOL =
      "pack010.synthetic.responses";
  private static final String SYNTHETIC_MODEL_REQUESTED =
      "gpt-5.6-terra";
  private static final String STATEMENT_DOMAIN =
      "emergeos.exact-provider-statement.v16";
  private static final String ATTRIBUTION_DOMAIN =
      "emergeos.graph-exact-provider-attribution.v16";
  private static final String EVENT_DOMAIN =
      "emergeos.graph-exact-tx-a-event.v16";
  private static final String HEAD_DOMAIN =
      "emergeos.graph-exact-tx-a-head.v16";
  private static final String CHALLENGE_DOMAIN =
      "emergeos.exact-provider-validation-challenge.v16";
  private static final String TRANSCRIPT_DOMAIN =
      "emergeos.exact-provider-validation-transcript.v16";
  private static final String RECEIPT_DOMAIN =
      "emergeos.exact-provider-validation-receipt.v16";
  private static final String DECISION_KIND = "STRUCTURED_FINAL";
  private static final String V16_COMMIT_FENCED =
      "V16 exact TX-A commit was fenced";
  private static final String V16_STAGE_FENCED =
      "V16 exact TX-A stage was fenced";
  private static final String V16_STAGE_NOT_CONSUMED =
      "V16 exact TX-A stage was not consumed";
  private static final String V16_MID_COMMIT_FAULT_CONSTRAINT =
      "pack010_v16_test_fail_event_insert";
  private static final long INPUT_TOKENS = 2L;
  private static final long CACHED_INPUT_TOKENS = 1L;
  private static final long OUTPUT_TOKENS = 1L;
  private static final long REASONING_OUTPUT_TOKENS = 0L;
  private static final long TOTAL_TOKENS = 3L;
  private static final BigDecimal EXPECTED_COST_PICO_USD =
      new BigDecimal("422800");
  private static final int CHALLENGE_TTL_MILLIS = 20_000;
  private static final Set<String> V16_OVERLAY_TABLES =
      Set.of(
          "agent_graph_exact_provider_validations_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16");
  private static final Set<String> COMMIT_RECEIPT_KEYS =
      Set.of(
          "protocol_version",
          "sequence",
          "state_version",
          "head_hash",
          "statement_hash",
          "attribution_hash",
          "event_hash",
          "transcript_hash",
          "validation_receipt_hash",
          "validation_state");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_exact_pico_overlay_v16_red")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void typedAttestorVerifiesBeforeCommitAndLeavesLegacyHeadUnchanged()
      throws Exception {
    DataSource admin = dataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

    String writerPassword = UUID.randomUUID().toString();
    String v13Password = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    String v16Password = UUID.randomUUID().toString();
    provisionProductionRoles(
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password);
    DataSource writerDataSource =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource v13DataSource =
        dataSource("emergeos_provider_attestor", v13Password);
    DataSource v15DataSource = dataSource(V15_ROLE, v15Password);
    DataSource v16DataSource = dataSource(V16_ROLE, v16Password);
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);

    int repetition = 3;
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

    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String keyId = "pack010-v18-typed-exact-pico-test-key";
    insertProviderValidationKey(admin, keyId, keyPair);
    PostgresProviderValidationAttestor v13Attestor =
        PostgresProviderValidationAttestor.open(v13DataSource);
    String policyHash =
        v13Attestor.requireValidation(
            manifest,
            sequence7.cursor(),
            keyId,
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH,
            Duration.ofMillis(CHALLENGE_TTL_MILLIS));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    assertEquals(13, sequence13.cursor().lastSequence());

    String profileHash = ensureSyntheticExactPicoProfile(admin);
    String requirementHash =
        requireExactTxA(v15DataSource, manifest);
    assertHash(policyHash);
    assertHash(profileHash);
    assertHash(requirementHash);

    BaseValidation baseValidation =
        baseValidation(admin, manifest);
    assertEquals("REQUIRED", baseValidation.state());
    assertEquals(policyHash, baseValidation.policyHash());
    assertEquals("r1", baseValidation.revision());
    assertEquals(keyId, baseValidation.keyId());
    String validationBefore =
        rowImage(
            admin,
            "agent_graph_provider_validations",
            manifest);
    String requirementBefore =
        rowImage(
            admin,
            "agent_graph_exact_tx_a_requirements_v15",
            manifest);
    DatabaseImage initialBefore = databaseImage(admin);
    assertEquals(
        V16_OVERLAY_TABLES,
        initialBefore.tables().keySet().stream()
            .filter(V16_OVERLAY_TABLES::contains)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));

    GraphProviderIntent request =
        Pack010GraphTerminalFixture.catalogIntent(repetition, 2);
    GraphProviderAttribution catalogAttribution =
        Pack010GraphTerminalFixture.catalogAttribution(repetition, 2);
    assertEquals(request.requestHash(), catalogAttribution.requestHash());
    String modelResolvedHash =
        IntegrityHashes.utf8ContentHash(
            catalogAttribution.modelResolved());
    String decisionHash =
        IntegrityHashes.utf8ContentHash(
            "pack010-v16-synthetic-structured-final-decision-v1");
    ObjectNode stagePayload =
        stagePayload(
            manifest,
            requirementHash,
            sequence13,
            request.requestHash(),
            catalogAttribution.responseHash(),
            modelResolvedHash,
            decisionHash,
            keyId);
    RuntimeIdentity runtimeIdentity = runtimeIdentity(admin);

    assertRawDatabaseAcceptsInvalidSignatureOnlyInsideRolledBackTcbCanary(
        v16DataSource, stagePayload, admin);
    assertReviewedVerifierRejectsInvalidSignatureBeforeCommit(
        v16DataSource,
        stagePayload,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertReviewedVerifierRejectsWrongKeyBeforeCommit(
        v16DataSource,
        stagePayload,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertReviewedVerifierRejectsFreshChallengeReplayBeforeCommit(
        v16DataSource,
        stagePayload,
        keyPair,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    PostgresExactPicoProviderValidationAttestor typedAttestor =
        PostgresExactPicoProviderValidationAttestor.open(v16DataSource);
    GraphExactPicoProviderValidationCommand typedCommand =
        typedCommand(
            manifest,
            sequence13,
            requirementHash,
            request.requestHash(),
            catalogAttribution.responseHash(),
        modelResolvedHash,
        decisionHash,
        keyId);
    assertTypedAuthorityDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedGlobalFunctionAclDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedGlobalRelationAclDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedUpstreamRelationAclDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedPrerequisiteRoleMembershipDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedUpstreamIndexDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedRewriteRuleDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedExtraTriggerDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedTransitiveHashHelperAclDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedTransitiveHashHelperBodyDriftFencedBeforeStage(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedAttestorRejectsWrongKeyBeforeCommit(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedAttestorRollsBackAfterCommitReceipt(
        v16DataSource,
        typedCommand,
        keyPair,
        admin,
        writer,
        manifest,
        sequence13,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertTypedSignerFailureCannotForgeConflict(
        v16DataSource,
        typedCommand,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    GraphExactPicoProviderValidationReceipt typedReceipt =
        typedAttestor.complete(
            typedCommand,
            challenge ->
                HexFormat.of().formatHex(
                    sign(keyPair, challenge.signatureMaterial())));
    assertEquals("PICO_OVERLAY_V1", typedReceipt.protocolVersion());
    assertEquals(14, typedReceipt.overlaySequence());
    assertEquals(1L, typedReceipt.overlayStateVersion());
    assertEquals(
        GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED,
        typedReceipt.validationState());
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertOnlyV16OverlayChanged(initialBefore, databaseImage(admin));
    assertTypedOverlayRows(
        admin,
        manifest,
        sequence13,
        typedCommand,
        typedReceipt,
        profileHash);
    assertTypedReplayFencedBeforeSigner(
        typedAttestor,
        typedCommand,
        admin,
        writer,
        manifest,
        sequence13,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    System.out.println(
        "PACK010_V18_TYPED_EXACT_PICO_ATTESTOR_ACCEPTANCE_RECEIPT"
            + " scope=DORMANT_TYPED_V16_STAGE_VERIFY_COMMIT_LOCAL_ONLY"
            + " productionTypedAuthorityAdapter=1"
            + " publicJavaRawStageMethod=0 publicJavaRawCommitMethod=0"
            + " shippingAppRouteConsumer=0 shippingLiveRoute=DISABLED"
            + " externalConfiguration=NOT_PROVEN"
            + " externalRuntimeInvocation=NOT_PROVEN"
            + " wrongKey=JAVA_VERIFY_REJECT_COMMIT_SQL_0_PUBLIC_TABLE_ROWS_XMIN_UNCHANGED"
            + " postReceiptFault=COMMIT_SQL_1_OUTER_TX_PUBLIC_TABLE_ROWS_XMIN_ROLLED_BACK"
            + " signerException=CAUSE_FREE_INTEGRITY_PUBLIC_TABLE_ROWS_XMIN_UNCHANGED"
            + " authorityCanary=EXTRA_TABLE_SELECT_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " functionAclCanary=EXTRA_LOGIN_EXECUTE_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " relationAclCanary=EXTRA_LOGIN_INSERT_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " upstreamRelationAclCanary=EXTRA_LOGIN_UPDATE_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " prerequisiteRoleCanary=MEMBERSHIP_DRIFT_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " upstreamShapeCanary=EXTRA_INDEX_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " rewriteRuleCanary=EXTRA_RULE_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " triggerCanary=EXTRA_NONINTERNAL_TRIGGER_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " transitiveHashHelperCanary=EXTRA_LOGIN_EXECUTE_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " transitiveHashHelperBodyCanary=PROSRC_DRIFT_REJECTED_BEFORE_SIGNER_AND_STAGE_MAPPED_PROBE"
            + " typedReplay=PRE_SIGNER_CAUSE_FREE_CONFLICT_PUBLIC_TABLE_ROWS_XMIN_UNCHANGED"
            + " valid=V16_OVERLAY_CONSUMED_SEQUENCE14_STATE1_LEGACY13_UNCHANGED"
            + " semanticSlice=STRUCTURED_FINAL_SUCCESS_ONLY"
            + " fixedPath=BASE13_REQUEST2_OVERLAY14"
            + " reasoningOutputTokens=0 failureCode=NULL"
            + " stageResultProjection=25_OF_62"
            + " challengeTranscript=JAVA_RECOMPUTED"
            + " componentHashes=DATABASE_MINTED_NOT_JAVA_RECOMPUTED_TCB"
            + " postgresNativeSignatureVerify=0"
            + " rawV16CredentialBypassInTcb=true"
            + " acceptanceSigner=EPHEMERAL_TEST_ONLY"
            + " shippingSignerImplementation=0 keyCustody=NOT_IMPLEMENTED"
            + " postOuterCommitConnectionLossOutcome=NOT_PROVEN"
            + " providerNetwork=0 billing=0 live=NOT_PROVEN");
  }

  @Test
  void signedExactPicoTxACommitsOnlyTheV16OverlayAndRejectsReplay()
      throws Exception {
    DataSource admin = dataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);

    String writerPassword = UUID.randomUUID().toString();
    String v13Password = UUID.randomUUID().toString();
    String v15Password = UUID.randomUUID().toString();
    String v16Password = UUID.randomUUID().toString();
    provisionProductionRoles(
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password);
    DataSource writerDataSource =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource v13DataSource =
        dataSource("emergeos_provider_attestor", v13Password);
    DataSource v15DataSource = dataSource(V15_ROLE, v15Password);
    DataSource v16DataSource = dataSource(V16_ROLE, v16Password);
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

    KeyPair keyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String keyId = "pack010-v16-exact-pico-test-key";
    insertProviderValidationKey(admin, keyId, keyPair);
    PostgresProviderValidationAttestor v13Attestor =
        PostgresProviderValidationAttestor.open(v13DataSource);
    String policyHash =
        v13Attestor.requireValidation(
            manifest,
            sequence7.cursor(),
            keyId,
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH,
            Duration.ofMillis(CHALLENGE_TTL_MILLIS));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    assertEquals(13, sequence13.cursor().lastSequence());

    String profileHash = ensureSyntheticExactPicoProfile(admin);
    String requirementHash =
        requireExactTxA(v15DataSource, manifest);
    assertHash(policyHash);
    assertHash(profileHash);
    assertHash(requirementHash);

    BaseValidation baseValidation =
        baseValidation(admin, manifest);
    assertEquals("REQUIRED", baseValidation.state());
    assertEquals(policyHash, baseValidation.policyHash());
    assertEquals("r1", baseValidation.revision());
    assertEquals(keyId, baseValidation.keyId());
    String validationBefore =
        rowImage(
            admin,
            "agent_graph_provider_validations",
            manifest);
    String requirementBefore =
        rowImage(
            admin,
            "agent_graph_exact_tx_a_requirements_v15",
            manifest);
    DatabaseImage initialBefore = databaseImage(admin);
    assertEquals(
        V16_OVERLAY_TABLES,
        initialBefore.tables().keySet().stream()
            .filter(V16_OVERLAY_TABLES::contains)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));

    GraphProviderIntent request =
        Pack010GraphTerminalFixture.catalogIntent(repetition, 2);
    GraphProviderAttribution catalogAttribution =
        Pack010GraphTerminalFixture.catalogAttribution(repetition, 2);
    assertEquals(request.requestHash(), catalogAttribution.requestHash());
    String modelResolvedHash =
        IntegrityHashes.utf8ContentHash(
            catalogAttribution.modelResolved());
    String decisionHash =
        IntegrityHashes.utf8ContentHash(
            "pack010-v16-synthetic-structured-final-decision-v1");
    ObjectNode stagePayload =
        stagePayload(
            manifest,
            requirementHash,
            sequence13,
            request.requestHash(),
            catalogAttribution.responseHash(),
            modelResolvedHash,
            decisionHash,
            keyId);
    RuntimeIdentity runtimeIdentity = runtimeIdentity(admin);

    assertRawDatabaseAcceptsInvalidSignatureOnlyInsideRolledBackTcbCanary(
        v16DataSource, stagePayload, admin);
    assertReviewedVerifierRejectsInvalidSignatureBeforeCommit(
        v16DataSource,
        stagePayload,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertReviewedVerifierRejectsWrongKeyBeforeCommit(
        v16DataSource,
        stagePayload,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertReviewedVerifierRejectsFreshChallengeReplayBeforeCommit(
        v16DataSource,
        stagePayload,
        keyPair,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    DatabaseImage stageOnlyBefore = databaseImage(admin);
    RuntimeException stageOnlyFailure =
        captureFailure(
            RuntimeException.class,
            () -> callStage(JdbcClient.create(v16DataSource), stagePayload),
            writerPassword,
            v13Password,
            v15Password,
            v16Password,
            POSTGRES.getPassword());
    PSQLException stageOnlyPostgres =
        rootPostgresFailure(stageOnlyFailure);
    assertEquals("55000", stageOnlyPostgres.getSQLState());
    assertEquals(
        V16_STAGE_NOT_CONSUMED,
        serverMessage(stageOnlyPostgres));
    assertEquals(stageOnlyBefore, databaseImage(admin));
    assertEquals(
        validationBefore,
        rowImage(admin, "agent_graph_provider_validations", manifest));
    assertEquals(
        requirementBefore,
        rowImage(
            admin,
            "agent_graph_exact_tx_a_requirements_v15",
            manifest));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));

    assertStageFenced(
        v16DataSource,
        stagePayload.deepCopy().put(
            "expected_base_head_hash",
            IntegrityHashes.utf8ContentHash("v16-wrong-base-head")),
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertStageFenced(
        v16DataSource,
        stagePayload.deepCopy().put(
            "request_hash",
            IntegrityHashes.utf8ContentHash("v16-wrong-request")),
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertStageFenced(
        v16DataSource,
        stagePayload.deepCopy().put(
            "key_id", "pack010-v16-wrong-key"),
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertStageFenced(
        v16DataSource,
        stagePayload.deepCopy().put(
            "requirement_hash",
            IntegrityHashes.utf8ContentHash("v16-wrong-requirement")),
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    assertCommitTranscriptFenced(
        v16DataSource,
        stagePayload,
        manifest,
        requirementHash,
        admin,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    assertPostStageAuthorityRevocationFenced(
        v16DataSource,
        stagePayload,
        keyPair,
        manifest,
        requirementHash,
        admin,
        SYNTHETIC_PROFILE_ID,
        null,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());
    assertPostStageAuthorityRevocationFenced(
        v16DataSource,
        stagePayload,
        keyPair,
        manifest,
        requirementHash,
        admin,
        null,
        keyId,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    DatabaseImage before = databaseImage(admin);

    installMidCommitFault(admin);
    DatabaseImage faultBefore = databaseImage(admin);
    try {
      RuntimeException fault =
          captureFailure(
              RuntimeException.class,
              () ->
                  completeExactPicoTxA(
                      v16DataSource,
                      stagePayload,
                      keyPair,
                      manifest,
                      sequence13,
                      requirementHash,
                      policyHash,
                      profileHash,
                      baseValidation,
                      runtimeIdentity,
                      request.requestHash(),
                      catalogAttribution.responseHash(),
                      modelResolvedHash,
                      decisionHash),
              writerPassword,
              v13Password,
              v15Password,
              v16Password,
              POSTGRES.getPassword());
      PSQLException faultPostgres = rootPostgresFailure(fault);
      assertEquals("23514", faultPostgres.getSQLState());
      assertEquals(
          V16_MID_COMMIT_FAULT_CONSTRAINT,
          faultPostgres.getServerErrorMessage().getConstraint());
      assertEquals(faultBefore, databaseImage(admin));
      assertEquals(
          validationBefore,
          rowImage(
              admin, "agent_graph_provider_validations", manifest));
      assertEquals(
          requirementBefore,
          rowImage(
              admin,
              "agent_graph_exact_tx_a_requirements_v15",
              manifest));
      assertEquals(
          sequence13,
          Pack010GraphTerminalFixture.verified(writer, manifest));
    } finally {
      removeMidCommitFault(admin);
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }

    Completion completion =
        completeExactPicoTxA(
            v16DataSource,
            stagePayload,
            keyPair,
            manifest,
            sequence13,
            requirementHash,
            policyHash,
            profileHash,
            baseValidation,
            runtimeIdentity,
            request.requestHash(),
            catalogAttribution.responseHash(),
            modelResolvedHash,
            decisionHash);

    assertCommitReceipt(completion);
    DatabaseImage committed = databaseImage(admin);
    assertOnlyV16OverlayChanged(before, committed);
    assertEquals(
        validationBefore,
        rowImage(
            admin,
            "agent_graph_provider_validations",
            manifest));
    assertEquals(
        requirementBefore,
        rowImage(
            admin,
            "agent_graph_exact_tx_a_requirements_v15",
            manifest));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertEquals(0L, legacySequence14Count(admin, manifest));
    assertV16OverlayRows(
        admin,
        manifest,
        sequence13,
        completion,
        requirementHash,
        profileHash,
        request.requestHash(),
        catalogAttribution.responseHash(),
        modelResolvedHash,
        decisionHash);

    DatabaseImage replayBefore = databaseImage(admin);
    RuntimeException replayFailure =
        captureFailure(
            RuntimeException.class,
            () -> callCommit(v16DataSource, completion.commitPayload()),
            writerPassword,
            v13Password,
            v15Password,
            v16Password,
            POSTGRES.getPassword());
    PSQLException replayPostgres = rootPostgresFailure(replayFailure);
    assertEquals("55000", replayPostgres.getSQLState());
    assertEquals(V16_COMMIT_FENCED, serverMessage(replayPostgres));
    assertEquals(replayBefore, databaseImage(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));

    assertExpiredChallengeFenced(
        admin,
        writerDataSource,
        writer,
        v13Attestor,
        v15DataSource,
        v16DataSource,
        keyPair,
        keyId,
        writerPassword,
        v13Password,
        v15Password,
        v16Password,
        POSTGRES.getPassword());

    System.out.println(
        "PACK010_V16_EXACT_PICO_OVERLAY_TX_A_ACCEPTANCE_RECEIPT"
            + " v15Marker=REQUIRED_UNCHANGED"
            + " v13Validation=REQUIRED_UNCHANGED"
            + " v8Head=SEQ13_UNCHANGED"
            + " overlayHead=SEQ14_STATE1"
            + " exactCostPicoUsd=422800"
            + " cachedRatePicoUsd=2800"
            + " profileTruth=SYNTHETIC_GPT_PRECISION_CANARY"
            + " signature=TEST_JVM_ED25519_VERIFIED"
            + " postgresNativeSignatureVerify=0"
            + " v16HistoricalVerifierAtSliceFreeze=0"
            + " trustedCallerCredentialInTcb=true"
            + " stageOnly=DB_55000_FULL_ROLLBACK"
            + " midCommitFault=EVENT_INSERT_23514_FULL_ROLLBACK"
            + " postStageRevocation=PROFILE,KEY_DB_55000_"
            + "OVERLAY_TX_ROLLBACK_STATUS_COMPENSATED"
            + " challengeExpiry=DB_55000_FULL_ROLLBACK"
            + " replay=DB_55000"
            + " positiveCommitDeltaFromPostCanaryBaseline="
            + "V1_V15_UNCHANGED_V16_ALLOWLIST_ONLY"
            + " negativeFenceDelta=FULL_PUBLIC_TABLE_JSON_XMIN_UNCHANGED"
            + " fixtureMutations=PROFILE_KEY_STATUS_XMIN,REP2_SETUP_EXCLUDED"
            + " v16HistoricalProductionJavaStageCommitApiAtSliceFreeze=0"
            + " tamperMatrix=STAGE_HEAD,REQUEST,KEY,REQUIREMENT,"
            + "COMMIT_TRANSCRIPT,PROFILE_REVOKED,KEY_REVOKED,"
            + "CHALLENGE_EXPIRED,STAGE_ONLY,REPLAY"
            + " precommitCrash=NOT_PROVEN race=NOT_PROVEN"
            + " txB=NOT_IMPLEMENTED txC=NOT_IMPLEMENTED"
            + " providerNetwork=0 billing=0 shippingLive=DISABLED");
    System.out.println(
        "PACK010_V17_PRODUCTION_VERIFIER_ACCEPTANCE_RECEIPT"
            + " scope=DORMANT_COMPILED_V16_ED25519_VERIFIER_LOCAL_ONLY"
            + " compiledProductionVerifierPrimitive=1"
            + " v17HistoricalProductionJavaStageCommitApiAtSliceFreeze=0"
            + " v17HistoricalShippingConsumerAtSliceFreeze=0"
            + " currentReviewedTypedConsumer=1"
            + " currentShippingAppRouteConsumer=0"
            + " externalConfiguration=NOT_PROVEN"
            + " externalRuntimeInvocation=NOT_PROVEN"
            + " postgresNativeSignatureVerify=0"
            + " javaVerifierTcb=true rawV16CredentialInTcb=true"
            + " rawDbInvalidSignature=ACCEPTED_ROLLED_BACK_TCB_CANARY"
            + " invalidSignature=LOCAL_REJECT_COMMIT0_FULL_ROLLBACK"
            + " wrongKey=LOCAL_REJECT_COMMIT0_FULL_ROLLBACK"
            + " wrongTranscript=LOCAL_REJECT_COMMIT0_FULL_ROLLBACK"
            + " expiryAuthority=POSTGRES_DB_CLOCK_55000_FULL_ROLLBACK"
            + " validPath=TEST_HARNESS_LOCAL_VERIFY_THEN_DB_CONSUMED"
            + " packagedArtifactEvidence=SEPARATE_GATE_REQUIRED"
            + " challengeComponentHashes=DATABASE_MINTED_TCB"
            + " keyCustody=NOT_IMPLEMENTED"
            + " providerProvenance=TRUSTED_CALLER_UNCHANGED"
            + " providerNetwork=0 billing=0 live=NOT_PROVEN");
  }

  private static Completion completeExactPicoTxA(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      KeyPair keyPair,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String requirementHash,
      String policyHash,
      String profileHash,
      BaseValidation baseValidation,
      RuntimeIdentity runtimeIdentity,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash) throws Exception {
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_READ_COMMITTED);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge challenge = callStage(jdbc, stagePayload);
        assertStageChallenge(
            challenge,
            manifest,
            sequence13,
            requirementHash,
            policyHash,
            profileHash,
            baseValidation,
            keyPair,
            runtimeIdentity,
            requestHash,
            responseHash,
            modelResolvedHash,
            decisionHash);
        byte[] signatureMaterial = signatureMaterial(challenge);
        assertEquals(
            challenge.transcriptHash(), sha256Hex(signatureMaterial));
        ObjectNode commitPayload = signedCommitPayload(
            challenge, keyPair, manifest, requirementHash);
        JsonNode receipt = callCommit(jdbc, commitPayload);
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
        connection.commit();
        return new Completion(
            challenge,
            commitPayload,
            receipt,
            commitPayload.path("signature_hex").asText());
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static void assertReviewedVerifierRejectsWrongKeyBeforeCommit(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      DataSource admin,
      String... secrets) throws Exception {
    DatabaseImage before = databaseImage(admin);
    AtomicInteger commitCalls = new AtomicInteger();
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_READ_COMMITTED);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge stage = callStage(jdbc, stagePayload);
        GraphExactPicoProviderValidationChallenge challenge =
            reviewedChallenge(stage);
        KeyPair wrongKeyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String wrongSignature =
            HexFormat.of().formatHex(
                sign(wrongKeyPair, signatureMaterial(stage)));
        ObjectNode wrongCommitPayload =
            JsonMapper.shared().createObjectNode();
        wrongCommitPayload.put("principal_id", stage.principalId());
        wrongCommitPayload.put("attempt_id", stage.attemptId());
        wrongCommitPayload.put("manifest_hash", stage.manifestHash());
        wrongCommitPayload.put(
            "requirement_hash", stage.requirementHash());
        wrongCommitPayload.put(
            "transcript_hash", stage.transcriptHash());
        wrongCommitPayload.put("signature_hex", wrongSignature);
        GraphAttemptIntegrityException failure =
            captureFailure(
                GraphAttemptIntegrityException.class,
                () -> {
                  GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                      challenge,
                      stage.publicKeyDer(),
                      wrongSignature);
                  commitCalls.incrementAndGet();
                  callCommit(jdbc, wrongCommitPayload);
                },
                secrets);
        assertEquals(
            "durable graph attempt integrity verification failed",
            failure.getMessage());
        assertTrue(failure.getCause() == null);
        assertEquals(0, commitCalls.get());
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
  }

  private static void assertTypedAttestorRejectsWrongKeyBeforeCommit(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws Exception {
    DatabaseImage before = databaseImage(admin);
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    AtomicInteger stageSqlCalls = new AtomicInteger();
    AtomicInteger commitSqlCalls = new AtomicInteger();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            observingV16SqlDataSource(
                v16DataSource, stageSqlCalls, commitSqlCalls),
            probes::add);
    assertEquals(0, stageSqlCalls.get());
    assertEquals(0, commitSqlCalls.get());
    KeyPair wrongKeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    AtomicInteger signerCalls = new AtomicInteger();
    GraphAttemptIntegrityException failure =
        captureFailure(
            GraphAttemptIntegrityException.class,
            () ->
                attestor.complete(
                    command,
                    challenge -> {
                      signerCalls.incrementAndGet();
                      return HexFormat.of().formatHex(
                          sign(
                              wrongKeyPair,
                              challenge.signatureMaterial()));
                    }),
            secrets);
    assertEquals(
        "durable graph attempt integrity verification failed",
        failure.getMessage());
    assertTrue(failure.getCause() == null);
    assertEquals(1, signerCalls.get());
    assertEquals(1, stageSqlCalls.get());
    assertEquals(0, commitSqlCalls.get());
    assertEquals(
        java.util.List.of(
            PostgresExactPicoProviderValidationAttestorTestAccess
                .ProbePoint.AFTER_STAGE_MAPPED),
        probes);
    assertEquals(before, databaseImage(admin));
  }

  private static DataSource observingV16SqlDataSource(
      DataSource delegate,
      AtomicInteger stageSqlCalls,
      AtomicInteger commitSqlCalls) {
    return new org.springframework.jdbc.datasource.DelegatingDataSource(
        delegate) {
      @Override
      public Connection getConnection() throws SQLException {
        return observingV16SqlConnection(
            super.getConnection(), stageSqlCalls, commitSqlCalls);
      }

      @Override
      public Connection getConnection(String username, String password)
          throws SQLException {
        return observingV16SqlConnection(
            super.getConnection(username, password),
            stageSqlCalls,
            commitSqlCalls);
      }
    };
  }

  private static Connection observingV16SqlConnection(
      Connection delegate,
      AtomicInteger stageSqlCalls,
      AtomicInteger commitSqlCalls) {
    return (Connection)
        Proxy.newProxyInstance(
            Pack010ExactPicoOverlayTxAAcceptanceTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object value = method.invoke(delegate, arguments);
                if (value instanceof Statement statement) {
                  String sql =
                      arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String text
                          ? text
                          : null;
                  return observingV16SqlStatement(
                      statement,
                      method.getReturnType(),
                      sql,
                      stageSqlCalls,
                      commitSqlCalls);
                }
                return value;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static Statement observingV16SqlStatement(
      Statement delegate,
      Class<?> statementType,
      String preparedSql,
      AtomicInteger stageSqlCalls,
      AtomicInteger commitSqlCalls) {
    return (Statement)
        Proxy.newProxyInstance(
            Pack010ExactPicoOverlayTxAAcceptanceTest.class.getClassLoader(),
            new Class<?>[] {statementType},
            (proxy, method, arguments) -> {
              String sql =
                  preparedSql != null
                      ? preparedSql
                      : arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String text
                          ? text
                          : null;
              if (method.getName().equals("execute")
                  || method.getName().equals("executeQuery")
                  || method.getName().equals("executeUpdate")) {
                if (isExactV16StageSql(sql)) {
                  stageSqlCalls.incrementAndGet();
                }
                if (isExactV16CommitSql(sql)) {
                  commitSqlCalls.incrementAndGet();
                }
              }
              try {
                return method.invoke(delegate, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static boolean isExactV16CommitSql(String sql) {
    if (sql == null) {
      return false;
    }
    String normalized = sql.replaceAll("\\s+", " ").strip();
    return normalized.matches(
        "SELECT public\\.agent_graph_commit_exact_tx_a_v16\\s*"
            + "\\(\\s*CAST\\s*\\(\\s*\\?\\s+AS\\s+jsonb\\s*\\)"
            + "\\s*\\)\\s*::text");
  }

  private static boolean isExactV16StageSql(String sql) {
    if (sql == null) {
      return false;
    }
    String normalized = sql.replaceAll("\\s+", " ").strip();
    return normalized.matches(
        "SELECT .* FROM public\\.agent_graph_stage_exact_tx_a_v16\\s*"
            + "\\(\\s*CAST\\s*\\(\\s*\\?\\s+AS\\s+jsonb\\s*\\)"
            + "\\s*\\)");
  }

  private static void assertTypedAttestorRollsBackAfterCommitReceipt(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      KeyPair keyPair,
      DataSource admin,
      PostgresGraphAttemptStore writer,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String... secrets) throws Exception {
    DatabaseImage before = databaseImage(admin);
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    AtomicInteger stageSqlCalls = new AtomicInteger();
    AtomicInteger commitSqlCalls = new AtomicInteger();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            observingV16SqlDataSource(
                v16DataSource, stageSqlCalls, commitSqlCalls),
            point -> {
              probes.add(point);
              if (point
                  == PostgresExactPicoProviderValidationAttestorTestAccess
                      .ProbePoint.AFTER_COMMIT_RECEIPT_VERIFIED) {
                throw new IllegalStateException("INJECTED_V18_POST_RECEIPT");
              }
            });
    assertEquals(0, stageSqlCalls.get());
    assertEquals(0, commitSqlCalls.get());
    AtomicInteger signerCalls = new AtomicInteger();
    GraphAttemptIntegrityException failure =
        captureFailure(
            GraphAttemptIntegrityException.class,
            () ->
                attestor.complete(
                    command,
                    challenge -> {
                      signerCalls.incrementAndGet();
                      return HexFormat.of().formatHex(
                          sign(keyPair, challenge.signatureMaterial()));
                    }),
            secrets);
    assertEquals(
        "durable graph attempt integrity verification failed",
        failure.getMessage());
    assertTrue(failure.getCause() == null);
    assertEquals(1, signerCalls.get());
    assertEquals(1, stageSqlCalls.get());
    assertEquals(1, commitSqlCalls.get());
    assertEquals(
        java.util.List.of(
            PostgresExactPicoProviderValidationAttestorTestAccess
                .ProbePoint.AFTER_STAGE_MAPPED,
            PostgresExactPicoProviderValidationAttestorTestAccess
                .ProbePoint.AFTER_SIGNATURE_VERIFIED,
            PostgresExactPicoProviderValidationAttestorTestAccess
                .ProbePoint.AFTER_COMMIT_RECEIPT_VERIFIED),
        probes);
    assertEquals(before, databaseImage(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertEquals(0L, legacySequence14Count(admin, manifest));
  }

  private static void assertTypedAuthorityDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    AtomicInteger stageSqlCalls = new AtomicInteger();
    AtomicInteger commitSqlCalls = new AtomicInteger();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            observingV16SqlDataSource(
                v16DataSource, stageSqlCalls, commitSqlCalls),
            probes::add);
    assertEquals(0, stageSqlCalls.get());
    assertEquals(0, commitSqlCalls.get());
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT SELECT ON public.agent_graph_exact_provider_validations_v16 "
            + "TO emergeos_provider_attestor_v16");
    try {
      assertTrue(
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT pg_catalog.has_table_privilege(
                    'emergeos_provider_attestor_v16',
                    'public.agent_graph_exact_provider_validations_v16',
                    'SELECT')
                  """)
              .query(Boolean.class)
              .single());
      DatabaseImage before = databaseImage(admin);
      GraphAttemptIntegrityException failure =
          captureFailure(
              GraphAttemptIntegrityException.class,
              () ->
                  attestor.complete(
                      command,
                      challenge -> {
                        signerCalls.incrementAndGet();
                        return "00".repeat(64);
                      }),
              secrets);
      assertEquals(
          "durable graph attempt integrity verification failed",
          failure.getMessage());
      assertTrue(failure.getCause() == null);
      assertEquals(0, signerCalls.get());
      assertEquals(java.util.List.of(), probes);
      assertEquals(before, databaseImage(admin));
    } finally {
      execute(
          admin,
          "REVOKE SELECT ON "
              + "public.agent_graph_exact_provider_validations_v16 "
              + "FROM emergeos_provider_attestor_v16");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedGlobalFunctionAclDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_commit_exact_tx_a_v16(jsonb) "
            + "TO emergeos_graph_reader");
    try {
      assertTrue(
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT pg_catalog.has_function_privilege(
                    'emergeos_graph_reader',
                    'public.agent_graph_commit_exact_tx_a_v16(jsonb)',
                    'EXECUTE')
                  """)
              .query(Boolean.class)
              .single());
      DatabaseImage before = databaseImage(admin);
      GraphAttemptIntegrityException failure =
          captureFailure(
              GraphAttemptIntegrityException.class,
              () ->
                  attestor.complete(
                      command,
                      challenge -> {
                        signerCalls.incrementAndGet();
                        return "00".repeat(64);
                      }),
              secrets);
      assertEquals(
          "durable graph attempt integrity verification failed",
          failure.getMessage());
      assertTrue(failure.getCause() == null);
      assertEquals(0, signerCalls.get());
      assertEquals(java.util.List.of(), probes);
      assertEquals(before, databaseImage(admin));
    } finally {
      execute(
          admin,
          "REVOKE EXECUTE ON FUNCTION "
              + "public.agent_graph_commit_exact_tx_a_v16(jsonb) "
              + "FROM emergeos_graph_reader");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedGlobalRelationAclDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT INSERT ON public.agent_graph_exact_provider_attributions_v16 "
            + "TO emergeos_graph_reader");
    try {
      assertTrue(
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT pg_catalog.has_table_privilege(
                    'emergeos_graph_reader',
                    'public.agent_graph_exact_provider_attributions_v16',
                    'INSERT')
                  """)
              .query(Boolean.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "REVOKE INSERT ON "
              + "public.agent_graph_exact_provider_attributions_v16 "
              + "FROM emergeos_graph_reader");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedUpstreamRelationAclDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT UPDATE ON public.agent_graph_provider_profiles_v14 "
            + "TO emergeos_graph_reader");
    try {
      assertTrue(
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT pg_catalog.has_table_privilege(
                    'emergeos_graph_reader',
                    'public.agent_graph_provider_profiles_v14',
                    'UPDATE')
                  """)
              .query(Boolean.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "REVOKE UPDATE ON public.agent_graph_provider_profiles_v14 "
              + "FROM emergeos_graph_reader");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedPrerequisiteRoleMembershipDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT emergeos_graph_prefix_writer TO emergeos_graph_reader");
    try {
      assertEquals(
          1,
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid = membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid = membership.member
                  WHERE granted_role.rolname =
                          'emergeos_graph_prefix_writer'
                    AND member_role.rolname = 'emergeos_graph_reader'
                  """)
              .query(Integer.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "REVOKE emergeos_graph_prefix_writer FROM emergeos_graph_reader");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedUpstreamIndexDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "CREATE INDEX pack010_v18_test_upstream_index "
            + "ON public.agent_graph_attempts(principal_id, attempt_id)");
    try {
      assertEquals(
          1,
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_class index_relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = index_relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND index_relation.relname =
                          'pack010_v18_test_upstream_index'
                    AND index_relation.relkind = 'i'
                  """)
              .query(Integer.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(admin, "DROP INDEX public.pack010_v18_test_upstream_index");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedRewriteRuleDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "CREATE RULE pack010_v18_test_rewrite_rule AS ON UPDATE TO "
            + "public.agent_graph_exact_attempt_heads_v16 DO ALSO NOTHING");
    try {
      assertEquals(
          1,
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_rewrite rewrite_rule
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid = rewrite_rule.ev_class
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND relation.relname =
                          'agent_graph_exact_attempt_heads_v16'
                    AND rewrite_rule.rulename =
                          'pack010_v18_test_rewrite_rule'
                  """)
              .query(Integer.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "DROP RULE pack010_v18_test_rewrite_rule ON "
              + "public.agent_graph_exact_attempt_heads_v16");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedExtraTriggerDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        """
        CREATE FUNCTION public.pack010_v18_test_extra_trigger()
        RETURNS trigger
        LANGUAGE plpgsql
        AS 'BEGIN RETURN NEW; END'
        """);
    execute(
        admin,
        "REVOKE ALL ON FUNCTION "
            + "public.pack010_v18_test_extra_trigger() FROM PUBLIC");
    execute(
        admin,
        """
        CREATE TRIGGER pack010_v18_test_extra_trigger
        BEFORE INSERT ON public.agent_graph_exact_provider_validations_v16
        FOR EACH ROW
        EXECUTE FUNCTION public.pack010_v18_test_extra_trigger()
        """);
    try {
      assertEquals(
          5,
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_trigger trigger
                  JOIN pg_catalog.pg_class relation
                    ON relation.oid = trigger.tgrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  WHERE namespace.nspname = 'public'
                    AND NOT trigger.tgisinternal
                    AND relation.relname IN (
                      'agent_graph_exact_provider_validations_v16',
                      'agent_graph_exact_provider_attributions_v16',
                      'agent_graph_exact_attempt_events_v16',
                      'agent_graph_exact_attempt_heads_v16')
                  """)
              .query(Integer.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "DROP TRIGGER pack010_v18_test_extra_trigger ON "
              + "public.agent_graph_exact_provider_validations_v16");
      execute(
          admin,
          "DROP FUNCTION public.pack010_v18_test_extra_trigger()");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedTransitiveHashHelperAclDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            v16DataSource, probes::add);
    AtomicInteger signerCalls = new AtomicInteger();
    execute(
        admin,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_framed_sha256_v14(varchar, text[]) "
            + "TO emergeos_graph_reader");
    try {
      assertTrue(
          JdbcClient.create(admin)
              .sql(
                  """
                  SELECT pg_catalog.has_function_privilege(
                    'emergeos_graph_reader',
                    'public.agent_graph_framed_sha256_v14(varchar,text[])',
                    'EXECUTE')
                  """)
              .query(Boolean.class)
              .single());
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
    } finally {
      execute(
          admin,
          "REVOKE EXECUTE ON FUNCTION "
              + "public.agent_graph_framed_sha256_v14(varchar, text[]) "
              + "FROM emergeos_graph_reader");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedTransitiveHashHelperBodyDriftFencedBeforeStage(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) throws SQLException {
    java.util.List<
        PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
        probes = new ArrayList<>();
    AtomicInteger stageSqlCalls = new AtomicInteger();
    AtomicInteger commitSqlCalls = new AtomicInteger();
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestorTestAccess.open(
            observingV16SqlDataSource(
                v16DataSource, stageSqlCalls, commitSqlCalls),
            probes::add);
    assertEquals(0, stageSqlCalls.get());
    assertEquals(0, commitSqlCalls.get());
    AtomicInteger signerCalls = new AtomicInteger();
    String signature =
        "public.agent_graph_framed_sha256_v14(character varying,text[])";
    String definition =
        JdbcClient.create(admin)
            .sql(
                "SELECT pg_catalog.pg_get_functiondef('"
                    + signature
                    + "'::pg_catalog.regprocedure)")
            .query(String.class)
            .single();
    execute(
        admin,
        """
        CREATE OR REPLACE FUNCTION public.agent_graph_framed_sha256_v14(
          hash_domain character varying, hash_fields text[])
        RETURNS character
        LANGUAGE plpgsql
        IMMUTABLE STRICT PARALLEL UNSAFE
        SET search_path = pg_catalog, pg_temp
        AS $drift$
        BEGIN
          RETURN repeat('0', 64)::character;
        END;
        $drift$
        """);
    try {
      assertTypedAuthorityRejectedBeforeSigner(
          attestor, command, admin, probes, signerCalls, secrets);
      assertEquals(0, stageSqlCalls.get());
      assertEquals(0, commitSqlCalls.get());
    } finally {
      execute(admin, definition);
      execute(
          admin,
          "ALTER FUNCTION "
              + signature
              + " OWNER TO emergeos_pack010_schema_owner");
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles.sql"));
      execute(
          admin,
          resource("db/provisioning/pack010_runtime_roles_check.sql"));
    }
  }

  private static void assertTypedAuthorityRejectedBeforeSigner(
      PostgresExactPicoProviderValidationAttestor attestor,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      java.util.List<
          PostgresExactPicoProviderValidationAttestorTestAccess.ProbePoint>
          probes,
      AtomicInteger signerCalls,
      String... secrets) {
    DatabaseImage before = databaseImage(admin);
    GraphAttemptIntegrityException failure =
        captureFailure(
            GraphAttemptIntegrityException.class,
            () ->
                attestor.complete(
                    command,
                    challenge -> {
                      signerCalls.incrementAndGet();
                      return "00".repeat(64);
                    }),
            secrets);
    assertEquals(
        "durable graph attempt integrity verification failed",
        failure.getMessage());
    assertTrue(failure.getCause() == null);
    assertEquals(0, signerCalls.get());
    assertEquals(java.util.List.of(), probes);
    assertEquals(before, databaseImage(admin));
  }

  private static void assertTypedReplayFencedBeforeSigner(
      PostgresExactPicoProviderValidationAttestor attestor,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      PostgresGraphAttemptStore writer,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String... secrets) {
    DatabaseImage before = databaseImage(admin);
    AtomicInteger signerCalls = new AtomicInteger();
    GraphAttemptConflictException failure =
        captureFailure(
            GraphAttemptConflictException.class,
            () ->
                attestor.complete(
                    command,
                    challenge -> {
                      signerCalls.incrementAndGet();
                      return "00".repeat(64);
                    }),
            secrets);
    assertEquals(
        "exact pico provider validation was fenced", failure.getMessage());
    assertTrue(failure.getCause() == null);
    assertEquals(0, signerCalls.get());
    assertEquals(before, databaseImage(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
    assertEquals(0L, legacySequence14Count(admin, manifest));
  }

  private static void assertTypedSignerFailureCannotForgeConflict(
      DataSource v16DataSource,
      GraphExactPicoProviderValidationCommand command,
      DataSource admin,
      String... secrets) {
    DatabaseImage before = databaseImage(admin);
    PostgresExactPicoProviderValidationAttestor attestor =
        PostgresExactPicoProviderValidationAttestor.open(v16DataSource);
    GraphAttemptIntegrityException failure =
        captureFailure(
            GraphAttemptIntegrityException.class,
            () ->
                attestor.complete(
                    command,
                    challenge -> {
                      throw new GraphAttemptConflictException(
                          "PRIVATE_SIGNER_FORGED_CONFLICT");
                    }),
            secrets);
    assertEquals(
        "durable graph attempt integrity verification failed",
        failure.getMessage());
    assertTrue(failure.getCause() == null);
    assertEquals(before, databaseImage(admin));
  }

  private static GraphExactPicoProviderValidationCommand typedCommand(
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String requirementHash,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash,
      String keyId) {
    return new GraphExactPicoProviderValidationCommand(
        manifest.principalId(),
        manifest.attemptId(),
        manifest.manifestHash(),
        requirementHash,
        sequence13.cursor().headHash(),
        requestHash,
        responseHash,
        modelResolvedHash,
        INPUT_TOKENS,
        CACHED_INPUT_TOKENS,
        OUTPUT_TOKENS,
        decisionHash,
        keyId,
        Duration.ofMillis(CHALLENGE_TTL_MILLIS));
  }

  private static void
      assertRawDatabaseAcceptsInvalidSignatureOnlyInsideRolledBackTcbCanary(
          DataSource v16DataSource,
          ObjectNode stagePayload,
          DataSource admin) throws Exception {
    DatabaseImage before = databaseImage(admin);
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge stage = callStage(jdbc, stagePayload);
        ObjectNode payload = JsonMapper.shared().createObjectNode();
        payload.put("principal_id", stage.principalId());
        payload.put("attempt_id", stage.attemptId());
        payload.put("manifest_hash", stage.manifestHash());
        payload.put("requirement_hash", stage.requirementHash());
        payload.put("transcript_hash", stage.transcriptHash());
        payload.put("signature_hex", "00".repeat(64));
        JsonNode receipt = callCommit(jdbc, payload);
        assertEquals("CONSUMED", receipt.path("validation_state").asText());
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
  }

  private static void assertReviewedVerifierRejectsInvalidSignatureBeforeCommit(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      DataSource admin,
      String... secrets) throws Exception {
    assertReviewedVerifierRejectsSignatureBeforeCommit(
        v16DataSource,
        stagePayload,
        "00".repeat(64),
        admin,
        secrets);
  }

  private static void
      assertReviewedVerifierRejectsFreshChallengeReplayBeforeCommit(
          DataSource v16DataSource,
          ObjectNode stagePayload,
          KeyPair trustedKeyPair,
          DataSource admin,
          String... secrets) throws Exception {
    String priorSignature;
    String priorChallengeHash;
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        StageChallenge prior =
            callStage(
                JdbcClient.create(
                    new SingleConnectionDataSource(connection, true)),
                stagePayload);
        priorSignature =
            HexFormat.of().formatHex(
                sign(trustedKeyPair, signatureMaterial(prior)));
        priorChallengeHash = prior.challengeHash();
      } finally {
        connection.rollback();
      }
    }
    DatabaseImage before = databaseImage(admin);
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge fresh = callStage(jdbc, stagePayload);
        assertTrue(!priorChallengeHash.equals(fresh.challengeHash()));
        AtomicInteger commitCalls = new AtomicInteger();
        GraphAttemptIntegrityException failure =
            captureFailure(
                GraphAttemptIntegrityException.class,
                () -> {
                  GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                      reviewedChallenge(fresh),
                      fresh.publicKeyDer(),
                      priorSignature);
                  commitCalls.incrementAndGet();
                },
                secrets);
        assertEquals(
            "durable graph attempt integrity verification failed",
            failure.getMessage());
        assertTrue(failure.getCause() == null);
        assertEquals(0, commitCalls.get());
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
  }

  private static void assertReviewedVerifierRejectsSignatureBeforeCommit(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      String signatureHex,
      DataSource admin,
      String... secrets) throws Exception {
    DatabaseImage before = databaseImage(admin);
    AtomicInteger commitCalls = new AtomicInteger();
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        StageChallenge stage =
            callStage(
                JdbcClient.create(
                    new SingleConnectionDataSource(connection, true)),
                stagePayload);
        GraphAttemptIntegrityException failure =
            captureFailure(
                GraphAttemptIntegrityException.class,
                () -> {
                  GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                      reviewedChallenge(stage),
                      stage.publicKeyDer(),
                      signatureHex);
                  commitCalls.incrementAndGet();
                },
                secrets);
        assertEquals(
            "durable graph attempt integrity verification failed",
            failure.getMessage());
        assertTrue(failure.getCause() == null);
        assertEquals(0, commitCalls.get());
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
  }

  private static GraphExactPicoProviderValidationChallenge
      reviewedChallenge(StageChallenge stage) {
    return new GraphExactPicoProviderValidationChallenge(
        stage.protocolVersion(),
        stage.databaseName(),
        stage.databaseOid(),
        stage.schemaOid(),
        stage.attestorRoleOid(),
        stage.principalId(),
        stage.attemptId(),
        stage.manifestHash(),
        stage.requirementHash(),
        stage.baseValidationPolicyHash(),
        stage.keyId(),
        stage.keyFingerprint(),
        UUID.fromString(stage.validationNonce()),
        instantFromEpochMicros(stage.issuedAtEpochMicros()),
        instantFromEpochMicros(stage.expiresAtEpochMicros()),
        stage.statementHash(),
        stage.attributionHash(),
        stage.eventHash(),
        stage.overlayHeadHash(),
        GraphProviderValidationDecision.valueOf(stage.decisionKind()),
        stage.decisionHash(),
        null,
        stage.challengeHash(),
        stage.transcriptHash());
  }

  private static Instant instantFromEpochMicros(long epochMicros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, 1_000_000L),
        Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
  }

  private static void assertStageFenced(
      DataSource v16DataSource,
      ObjectNode payload,
      DataSource admin,
      String... secrets) {
    DatabaseImage before = databaseImage(admin);
    RuntimeException failure =
        captureFailure(
            RuntimeException.class,
            () -> callStage(JdbcClient.create(v16DataSource), payload),
            secrets);
    PSQLException postgres = rootPostgresFailure(failure);
    assertEquals("55000", postgres.getSQLState());
    assertEquals(V16_STAGE_FENCED, serverMessage(postgres));
    assertEquals(before, databaseImage(admin));
  }

  private static void assertCommitTranscriptFenced(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      GraphAttemptManifest manifest,
      String requirementHash,
      DataSource admin,
      String... secrets) throws SQLException {
    DatabaseImage before = databaseImage(admin);
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        callStage(jdbc, stagePayload);
        ObjectNode commitPayload =
            JsonMapper.shared().createObjectNode();
        commitPayload.put("principal_id", manifest.principalId());
        commitPayload.put("attempt_id", manifest.attemptId());
        commitPayload.put("manifest_hash", manifest.manifestHash());
        commitPayload.put("requirement_hash", requirementHash);
        commitPayload.put(
            "transcript_hash",
            IntegrityHashes.utf8ContentHash("v16-wrong-transcript"));
        commitPayload.put("signature_hex", "00".repeat(64));
        RuntimeException failure =
            captureFailure(
                RuntimeException.class,
                () -> callCommit(jdbc, commitPayload),
                secrets);
        PSQLException postgres = rootPostgresFailure(failure);
        assertEquals("55000", postgres.getSQLState());
        assertEquals(V16_COMMIT_FENCED, serverMessage(postgres));
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
  }

  private static void assertPostStageAuthorityRevocationFenced(
      DataSource v16DataSource,
      ObjectNode stagePayload,
      KeyPair keyPair,
      GraphAttemptManifest manifest,
      String requirementHash,
      DataSource admin,
      String profileId,
      String keyId,
      String... secrets) throws SQLException {
    boolean revoked = false;
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_READ_COMMITTED);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge challenge = callStage(jdbc, stagePayload);
        ObjectNode commitPayload = signedCommitPayload(
            challenge, keyPair, manifest, requirementHash);
        setAuthorityStatus(admin, profileId, keyId, "REVOKED");
        revoked = true;
        DatabaseImage revokedTruth = databaseImage(admin);
        RuntimeException failure =
            captureFailure(
                RuntimeException.class,
                () -> callCommit(jdbc, commitPayload),
                secrets);
        PSQLException postgres = rootPostgresFailure(failure);
        assertEquals("55000", postgres.getSQLState());
        assertEquals(V16_COMMIT_FENCED, serverMessage(postgres));
        connection.rollback();
        assertEquals(revokedTruth, databaseImage(admin));
      } finally {
        connection.rollback();
      }
    } finally {
      if (revoked) {
        setAuthorityStatus(admin, profileId, keyId, "ACTIVE");
      }
    }
  }

  private static void assertExpiredChallengeFenced(
      DataSource admin,
      DataSource writerDataSource,
      PostgresGraphAttemptStore writer,
      PostgresProviderValidationAttestor v13Attestor,
      DataSource v15DataSource,
      DataSource v16DataSource,
      KeyPair keyPair,
      String keyId,
      String... secrets) throws Exception {
    int repetition = 2;
    int expiryTtlMillis = 5_000;
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
    String policyHash =
        v13Attestor.requireValidation(
            manifest,
            sequence7.cursor(),
            keyId,
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH,
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH,
            Duration.ofMillis(expiryTtlMillis));
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            writer, sequence7, repetition);
    String requirementHash = requireExactTxA(v15DataSource, manifest);
    assertHash(policyHash);
    assertHash(requirementHash);

    GraphProviderIntent request =
        Pack010GraphTerminalFixture.catalogIntent(repetition, 2);
    GraphProviderAttribution attribution =
        Pack010GraphTerminalFixture.catalogAttribution(repetition, 2);
    String modelResolvedHash =
        IntegrityHashes.utf8ContentHash(attribution.modelResolved());
    String decisionHash =
        IntegrityHashes.utf8ContentHash(
            "pack010-v16-expired-challenge-decision-v1");
    ObjectNode payload =
        stagePayload(
            manifest,
            requirementHash,
            sequence13,
            request.requestHash(),
            attribution.responseHash(),
            modelResolvedHash,
            decisionHash,
            keyId,
            expiryTtlMillis);
    DatabaseImage before = databaseImage(admin);
    try (Connection connection = v16DataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_READ_COMMITTED);
      try {
        JdbcClient jdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        StageChallenge challenge = callStage(jdbc, payload);
        ObjectNode commitPayload = signedCommitPayload(
            challenge, keyPair, manifest, requirementHash);
        waitUntilExpired(admin, challenge.expiresAtEpochMicros());
        RuntimeException failure =
            captureFailure(
                RuntimeException.class,
                () -> callCommit(jdbc, commitPayload),
                secrets);
        PSQLException postgres = rootPostgresFailure(failure);
        assertEquals("55000", postgres.getSQLState());
        assertEquals(V16_COMMIT_FENCED, serverMessage(postgres));
      } finally {
        connection.rollback();
      }
    }
    assertEquals(before, databaseImage(admin));
    assertEquals(
        sequence13,
        Pack010GraphTerminalFixture.verified(writer, manifest));
  }

  private static ObjectNode signedCommitPayload(
      StageChallenge challenge,
      KeyPair keyPair,
      GraphAttemptManifest manifest,
      String requirementHash) {
    byte[] material = signatureMaterial(challenge);
    assertEquals(challenge.transcriptHash(), sha256Hex(material));
    byte[] signature = sign(keyPair, material);
    verify(keyPair, material, signature);
    GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
        reviewedChallenge(challenge),
        challenge.publicKeyDer(),
        HexFormat.of().formatHex(signature));
    ObjectNode payload = JsonMapper.shared().createObjectNode();
    payload.put("principal_id", manifest.principalId());
    payload.put("attempt_id", manifest.attemptId());
    payload.put("manifest_hash", manifest.manifestHash());
    payload.put("requirement_hash", requirementHash);
    payload.put("transcript_hash", challenge.transcriptHash());
    payload.put("signature_hex", HexFormat.of().formatHex(signature));
    return payload;
  }

  private static void setAuthorityStatus(
      DataSource admin,
      String profileId,
      String keyId,
      String status) {
    int updated;
    if (profileId != null) {
      updated = JdbcClient.create(admin)
          .sql(
              """
              UPDATE agent_graph_provider_profiles_v14
              SET status = :status
              WHERE profile_id = :profileId
              """)
          .param("status", status)
          .param("profileId", profileId)
          .update();
    } else {
      updated = JdbcClient.create(admin)
          .sql(
              """
              UPDATE agent_graph_provider_validation_keys
              SET status = :status
              WHERE key_id = :keyId
              """)
          .param("status", status)
          .param("keyId", keyId)
          .update();
    }
    assertEquals(1, updated);
  }

  private static void waitUntilExpired(
      DataSource admin, long expiresAtEpochMicros) {
    long now = databaseEpochMicros(admin);
    long waitMillis = Math.max(
        1L,
        Math.floorDiv(
            Math.max(0L, expiresAtEpochMicros - now) + 99_999L,
            1_000L));
    assertTrue(waitMillis <= 5_500L);
    try {
      Thread.sleep(waitMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("V16_EXPIRY_WAIT_INTERRUPTED");
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

  private static void installMidCommitFault(DataSource admin)
      throws SQLException {
    execute(
        admin,
        """
        ALTER TABLE public.agent_graph_exact_attempt_events_v16
        ADD CONSTRAINT pack010_v16_test_fail_event_insert
        CHECK (false) NOT VALID
        """);
  }

  private static void removeMidCommitFault(DataSource admin)
      throws SQLException {
    execute(
        admin,
        """
        ALTER TABLE public.agent_graph_exact_attempt_events_v16
        DROP CONSTRAINT pack010_v16_test_fail_event_insert
        """);
  }

  private static StageChallenge callStage(
      JdbcClient jdbc, JsonNode payload) {
    return jdbc.sql(
            """
            SELECT protocol_version, database_name, database_oid,
                   schema_oid, attestor_role_oid,
                   principal_id, attempt_id, manifest_hash,
                   requirement_hash, base_validation_policy_hash,
                   revision, session_intent_hash,
                   session_expires_at_epoch_micros,
                   base_sequence, base_head_hash, overlay_sequence,
                   request_ordinal, provider_profile_id,
                   provider_profile_hash, transport_profile_hash,
                   parser_profile_hash, schema_profile_hash,
                   key_id, key_fingerprint, validation_nonce,
                   issued_at_epoch_micros, expires_at_epoch_micros,
                   statement_hash, attribution_hash, event_hash,
                   overlay_head_hash, challenge_hash, transcript_hash,
                   observed_cost_pico_usd, public_key_der,
                   state_version, provider_actor, provider_id,
                   provider_protocol,
                   base_execution_pricing_fingerprint,
                   model_requested, model_resolved_hash,
                   model_resolution_profile_hash,
                   pricing_profile_id, pricing_provider_id,
                   pricing_profile_fingerprint, pricing_source_hash,
                   rate_unit,
                   uncached_input_pico_usd_per_token,
                   cached_input_pico_usd_per_token,
                   output_pico_usd_per_token,
                   input_tokens, cached_input_tokens, output_tokens,
                   reasoning_output_tokens, total_tokens,
                   decision_kind, decision_hash, failure_code,
                   attributed_at_epoch_micros,
                   occurred_at_epoch_micros,
                   updated_at_epoch_micros
            FROM public.agent_graph_stage_exact_tx_a_v16(
              CAST(:payload AS jsonb))
            """)
        .param("payload", payload.toString())
        .query(
            (row, ignored) ->
                new StageChallenge(
                    trimmed(row.getString("protocol_version")),
                    row.getString("database_name"),
                    row.getLong("database_oid"),
                    row.getLong("schema_oid"),
                    row.getLong("attestor_role_oid"),
                    row.getString("principal_id"),
                    trimmed(row.getString("attempt_id")),
                    trimmed(row.getString("manifest_hash")),
                    trimmed(row.getString("requirement_hash")),
                    trimmed(
                        row.getString(
                            "base_validation_policy_hash")),
                    row.getString("revision"),
                    trimmed(row.getString("session_intent_hash")),
                    row.getLong(
                        "session_expires_at_epoch_micros"),
                    row.getInt("base_sequence"),
                    trimmed(row.getString("base_head_hash")),
                    row.getInt("overlay_sequence"),
                    row.getInt("request_ordinal"),
                    row.getString("provider_profile_id"),
                    trimmed(row.getString("provider_profile_hash")),
                    trimmed(row.getString("transport_profile_hash")),
                    trimmed(row.getString("parser_profile_hash")),
                    trimmed(row.getString("schema_profile_hash")),
                    row.getString("key_id"),
                    trimmed(row.getString("key_fingerprint")),
                    row.getString("validation_nonce"),
                    row.getLong("issued_at_epoch_micros"),
                    row.getLong("expires_at_epoch_micros"),
                    trimmed(row.getString("statement_hash")),
                    trimmed(row.getString("attribution_hash")),
                    trimmed(row.getString("event_hash")),
                    trimmed(row.getString("overlay_head_hash")),
                    trimmed(row.getString("challenge_hash")),
                    trimmed(row.getString("transcript_hash")),
                    row.getBigDecimal("observed_cost_pico_usd"),
                    row.getBytes("public_key_der"),
                    row.getLong("state_version"),
                    row.getString("provider_actor"),
                    row.getString("provider_id"),
                    row.getString("provider_protocol"),
                    trimmed(
                        row.getString(
                            "base_execution_pricing_fingerprint")),
                    row.getString("model_requested"),
                    trimmed(row.getString("model_resolved_hash")),
                    trimmed(
                        row.getString(
                            "model_resolution_profile_hash")),
                    row.getString("pricing_profile_id"),
                    row.getString("pricing_provider_id"),
                    trimmed(
                        row.getString(
                            "pricing_profile_fingerprint")),
                    trimmed(row.getString("pricing_source_hash")),
                    row.getString("rate_unit"),
                    row.getLong(
                        "uncached_input_pico_usd_per_token"),
                    row.getLong(
                        "cached_input_pico_usd_per_token"),
                    row.getLong("output_pico_usd_per_token"),
                    row.getLong("input_tokens"),
                    row.getLong("cached_input_tokens"),
                    row.getLong("output_tokens"),
                    row.getLong("reasoning_output_tokens"),
                    row.getLong("total_tokens"),
                    row.getString("decision_kind"),
                    trimmed(row.getString("decision_hash")),
                    row.getString("failure_code"),
                    row.getLong("attributed_at_epoch_micros"),
                    row.getLong("occurred_at_epoch_micros"),
                    row.getLong("updated_at_epoch_micros")))
        .single();
  }

  private static JsonNode callCommit(
      DataSource v16DataSource, JsonNode payload) throws IOException {
    return callCommit(JdbcClient.create(v16DataSource), payload);
  }

  private static JsonNode callCommit(
      JdbcClient jdbc, JsonNode payload) throws IOException {
    String committed =
        jdbc.sql(
                """
                SELECT public.agent_graph_commit_exact_tx_a_v16(
                  CAST(:payload AS jsonb))::text
                """)
            .param("payload", payload.toString())
            .query(String.class)
            .single();
    return JsonMapper.shared().readTree(committed);
  }

  private static void assertStageChallenge(
      StageChallenge challenge,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      String requirementHash,
      String policyHash,
      String profileHash,
      BaseValidation baseValidation,
      KeyPair keyPair,
      RuntimeIdentity runtimeIdentity,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash) {
    assertEquals("PICO_OVERLAY_V1", challenge.protocolVersion());
    assertEquals(runtimeIdentity.databaseName(), challenge.databaseName());
    assertEquals(runtimeIdentity.databaseOid(), challenge.databaseOid());
    assertEquals(runtimeIdentity.schemaOid(), challenge.schemaOid());
    assertEquals(
        runtimeIdentity.attestorRoleOid(), challenge.attestorRoleOid());
    assertEquals(manifest.principalId(), challenge.principalId());
    assertEquals(manifest.attemptId(), challenge.attemptId());
    assertEquals(manifest.manifestHash(), challenge.manifestHash());
    assertEquals(requirementHash, challenge.requirementHash());
    assertEquals(policyHash, challenge.baseValidationPolicyHash());
    assertEquals(baseValidation.revision(), challenge.revision());
    assertEquals(
        baseValidation.sessionIntentHash(),
        challenge.sessionIntentHash());
    assertEquals(
        baseValidation.sessionExpiresAtEpochMicros(),
        challenge.sessionExpiresAtEpochMicros());
    assertEquals(13, challenge.baseSequence());
    assertEquals(
        sequence13.cursor().headHash(), challenge.baseHeadHash());
    assertEquals(14, challenge.overlaySequence());
    assertEquals(1L, challenge.stateVersion());
    assertEquals(2, challenge.requestOrdinal());
    assertEquals(SYNTHETIC_PROFILE_ID, challenge.providerProfileId());
    assertEquals(profileHash, challenge.providerProfileHash());
    assertEquals(
        Pack010ProviderValidationAttestationHarnessMain
            .TRANSPORT_PROFILE_HASH,
        challenge.transportProfileHash());
    assertEquals(
        Pack010ProviderValidationAttestationHarnessMain
            .PARSER_PROFILE_HASH,
        challenge.parserProfileHash());
    assertEquals(
        Pack010ProviderValidationAttestationHarnessMain
            .SCHEMA_PROFILE_HASH,
        challenge.schemaProfileHash());
    assertEquals(baseValidation.keyId(), challenge.keyId());
    assertEquals(
        baseValidation.keyFingerprint(), challenge.keyFingerprint());
    UUID.fromString(challenge.validationNonce());
    assertTrue(challenge.issuedAtEpochMicros() > 0L);
    assertEquals(
        Math.multiplyExact(CHALLENGE_TTL_MILLIS, 1_000L),
        challenge.expiresAtEpochMicros()
            - challenge.issuedAtEpochMicros());
    assertTrue(
        challenge.expiresAtEpochMicros()
            < challenge.sessionExpiresAtEpochMicros());
    assertEquals(
        0, EXPECTED_COST_PICO_USD.compareTo(
            challenge.observedCostPicoUsd()));
    assertEquals(manifest.childActor(), challenge.providerActor());
    assertEquals(SYNTHETIC_PROVIDER_ID, challenge.providerId());
    assertEquals(
        SYNTHETIC_PROVIDER_PROTOCOL, challenge.providerProtocol());
    assertEquals(
        manifest.pricingProfileFingerprint(),
        challenge.baseExecutionPricingFingerprint());
    assertEquals(SYNTHETIC_MODEL_REQUESTED, challenge.modelRequested());
    assertEquals(modelResolvedHash, challenge.modelResolvedHash());
    assertEquals(
        IntegrityHashes.utf8ContentHash(
            "pack010-gpt-5.6-terra-synthetic-resolution-v1"),
        challenge.modelResolutionProfileHash());
    assertEquals(
        "pack010-synthetic-exact-pico-precision-v1",
        challenge.pricingProfileId());
    assertEquals(SYNTHETIC_PROVIDER_ID, challenge.pricingProviderId());
    assertEquals(
        IntegrityHashes.utf8ContentHash(
            "pack010-synthetic-exact-pico-precision-v1"),
        challenge.pricingProfileFingerprint());
    assertEquals(
        IntegrityHashes.utf8ContentHash(
            "pack010-test-only-noncommercial-price-source-v1"),
        challenge.pricingSourceHash());
    assertEquals("PICO_USD_PER_TOKEN", challenge.rateUnit());
    assertEquals(140_000L, challenge.uncachedInputPicoUsdPerToken());
    assertEquals(2_800L, challenge.cachedInputPicoUsdPerToken());
    assertEquals(280_000L, challenge.outputPicoUsdPerToken());
    assertEquals(INPUT_TOKENS, challenge.inputTokens());
    assertEquals(CACHED_INPUT_TOKENS, challenge.cachedInputTokens());
    assertEquals(OUTPUT_TOKENS, challenge.outputTokens());
    assertEquals(
        REASONING_OUTPUT_TOKENS, challenge.reasoningOutputTokens());
    assertEquals(TOTAL_TOKENS, challenge.totalTokens());
    assertEquals(DECISION_KIND, challenge.decisionKind());
    assertEquals(decisionHash, challenge.decisionHash());
    assertTrue(challenge.failureCode() == null);
    assertEquals(
        challenge.issuedAtEpochMicros(),
        challenge.attributedAtEpochMicros());
    assertEquals(
        challenge.issuedAtEpochMicros(),
        challenge.occurredAtEpochMicros());
    assertEquals(
        challenge.issuedAtEpochMicros(),
        challenge.updatedAtEpochMicros());
    assertArrayEquals(
        keyPair.getPublic().getEncoded(), challenge.publicKeyDer());
    assertEquals(
        canonicalHash(
            STATEMENT_DOMAIN,
            requirementHash,
            policyHash,
            manifest.manifestHash(),
            "13",
            sequence13.cursor().headHash(),
            manifest.manifestHash(),
            "2",
            requestHash,
            responseHash,
            challenge.providerId(),
            challenge.providerProtocol(),
            challenge.providerProfileId(),
            challenge.providerProfileHash(),
            challenge.baseExecutionPricingFingerprint(),
            challenge.modelRequested(),
            challenge.modelResolvedHash(),
            challenge.pricingProfileId(),
            challenge.pricingProviderId(),
            challenge.pricingProfileFingerprint(),
            challenge.pricingSourceHash(),
            challenge.rateUnit(),
            Long.toString(challenge.uncachedInputPicoUsdPerToken()),
            Long.toString(challenge.cachedInputPicoUsdPerToken()),
            Long.toString(challenge.outputPicoUsdPerToken()),
            Long.toString(challenge.inputTokens()),
            Long.toString(challenge.cachedInputTokens()),
            Long.toString(challenge.outputTokens()),
            Long.toString(challenge.reasoningOutputTokens()),
            Long.toString(challenge.totalTokens()),
            challenge.observedCostPicoUsd().toPlainString(),
            challenge.decisionKind(),
            challenge.decisionHash(),
            ""),
        challenge.statementHash());
    assertEquals(
        canonicalHash(
            ATTRIBUTION_DOMAIN,
            challenge.protocolVersion(),
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            requirementHash,
            policyHash,
            "13",
            sequence13.cursor().headHash(),
            "14",
            "1",
            "2",
            requestHash,
            responseHash,
            challenge.providerActor(),
            challenge.providerId(),
            challenge.providerProtocol(),
            challenge.providerProfileId(),
            challenge.providerProfileHash(),
            challenge.baseExecutionPricingFingerprint(),
            challenge.transportProfileHash(),
            challenge.parserProfileHash(),
            challenge.schemaProfileHash(),
            challenge.modelRequested(),
            challenge.modelResolvedHash(),
            challenge.modelResolutionProfileHash(),
            challenge.pricingProfileId(),
            challenge.pricingProviderId(),
            challenge.pricingProfileFingerprint(),
            challenge.pricingSourceHash(),
            challenge.rateUnit(),
            Long.toString(challenge.uncachedInputPicoUsdPerToken()),
            Long.toString(challenge.cachedInputPicoUsdPerToken()),
            Long.toString(challenge.outputPicoUsdPerToken()),
            Long.toString(challenge.inputTokens()),
            Long.toString(challenge.cachedInputTokens()),
            Long.toString(challenge.outputTokens()),
            Long.toString(challenge.reasoningOutputTokens()),
            Long.toString(challenge.totalTokens()),
            challenge.observedCostPicoUsd().toPlainString(),
            challenge.statementHash(),
            challenge.decisionKind(),
            challenge.decisionHash(),
            "",
            Long.toString(challenge.attributedAtEpochMicros())),
        challenge.attributionHash());
    assertEquals(
        canonicalHash(
            EVENT_DOMAIN,
            challenge.protocolVersion(),
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            requirementHash,
            "13",
            sequence13.cursor().headHash(),
            "14",
            "EXACT_PROVIDER_ATTRIBUTED",
            Long.toString(challenge.occurredAtEpochMicros()),
            sequence13.cursor().headHash(),
            challenge.attributionHash(),
            challenge.statementHash()),
        challenge.eventHash());
    assertEquals(
        canonicalHash(
            HEAD_DOMAIN,
            challenge.protocolVersion(),
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            requirementHash,
            "13",
            sequence13.cursor().headHash(),
            "1",
            "14",
            "EXACT_PROVIDER_ATTRIBUTED",
            "ATTRIBUTED",
            "2",
            "1",
            "1",
            "2",
            challenge.statementHash(),
            challenge.attributionHash(),
            challenge.eventHash(),
            Long.toString(challenge.updatedAtEpochMicros())),
        challenge.overlayHeadHash());
    assertEquals(
        canonicalHash(
            CHALLENGE_DOMAIN,
            challenge.protocolVersion(),
            runtimeIdentity.databaseName(),
            Long.toString(runtimeIdentity.databaseOid()),
            Long.toString(runtimeIdentity.schemaOid()),
            Long.toString(runtimeIdentity.attestorRoleOid()),
            manifest.principalId(),
            manifest.attemptId(),
            manifest.manifestHash(),
            requirementHash,
            policyHash,
            challenge.keyId(),
            challenge.keyFingerprint(),
            challenge.validationNonce(),
            Long.toString(challenge.issuedAtEpochMicros()),
            Long.toString(challenge.expiresAtEpochMicros())),
        challenge.challengeHash());
    assertEquals(
        canonicalHash(
            TRANSCRIPT_DOMAIN,
            challenge.challengeHash(),
            challenge.statementHash(),
            challenge.attributionHash(),
            challenge.eventHash(),
            challenge.overlayHeadHash(),
            challenge.decisionKind(),
            challenge.decisionHash(),
            ""),
        challenge.transcriptHash());
  }

  private static void assertCommitReceipt(Completion completion) {
    JsonNode receipt = completion.receipt();
    assertEquals(COMMIT_RECEIPT_KEYS.size(), receipt.size());
    for (String key : COMMIT_RECEIPT_KEYS) {
      assertTrue(receipt.has(key));
    }
    assertTrue(receipt.path("sequence").isIntegralNumber());
    assertTrue(receipt.path("state_version").isIntegralNumber());
    for (String key
        : Set.of(
            "protocol_version",
            "head_hash",
            "statement_hash",
            "attribution_hash",
            "event_hash",
            "transcript_hash",
            "validation_receipt_hash",
            "validation_state")) {
      assertTrue(receipt.path(key).isTextual());
    }
    assertEquals(
        completion.challenge().protocolVersion(),
        receipt.path("protocol_version").asText());
    assertEquals(14, receipt.path("sequence").asInt());
    assertEquals(1L, receipt.path("state_version").asLong());
    assertEquals(
        completion.challenge().overlayHeadHash(),
        receipt.path("head_hash").asText());
    assertEquals(
        completion.challenge().statementHash(),
        receipt.path("statement_hash").asText());
    assertEquals(
        completion.challenge().attributionHash(),
        receipt.path("attribution_hash").asText());
    assertEquals(
        completion.challenge().eventHash(),
        receipt.path("event_hash").asText());
    assertEquals(
        completion.challenge().transcriptHash(),
        receipt.path("transcript_hash").asText());
    assertHash(receipt.path("validation_receipt_hash").asText());
    assertEquals(
        "CONSUMED", receipt.path("validation_state").asText());
  }

  private static void assertOnlyV16OverlayChanged(
      DatabaseImage before, DatabaseImage after) {
    assertEquals(before.tables().keySet(), after.tables().keySet());
    for (Map.Entry<String, TableImage> entry
        : before.tables().entrySet()) {
      String table = entry.getKey();
      if (V16_OVERLAY_TABLES.contains(table)) {
        assertEquals(
            entry.getValue().rowCount() + 1L,
            after.tables().get(table).rowCount(),
            () -> "EXPECTED_ONE_V16_OVERLAY_ROW_" + table);
      } else {
        assertEquals(
            entry.getValue(),
            after.tables().get(table),
            () -> "UNEXPECTED_NON_V16_MUTATION_" + table);
      }
    }
  }

  private static void assertTypedOverlayRows(
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      GraphExactPicoProviderValidationCommand command,
      GraphExactPicoProviderValidationReceipt receipt,
      String profileHash) {
    long asserted =
        JdbcClient.create(admin)
            .sql(
                """
                SELECT btrim(validation.requirement_hash)::text
                         AS requirement_hash,
                       btrim(validation.provider_profile_hash)::text
                         AS profile_hash,
                       btrim(validation.request_hash)::text AS request_hash,
                       btrim(validation.response_hash)::text AS response_hash,
                       btrim(validation.model_resolved_hash)::text
                         AS model_resolved_hash,
                       validation.input_tokens,
                       validation.cached_input_tokens,
                       validation.output_tokens,
                       btrim(validation.decision_hash)::text
                         AS decision_hash,
                       validation.key_id,
                       validation.state,
                       btrim(validation.statement_hash)::text
                         AS statement_hash,
                       btrim(validation.attribution_hash)::text
                         AS attribution_hash,
                       btrim(validation.event_hash)::text AS event_hash,
                       btrim(validation.overlay_head_hash)::text
                         AS overlay_head_hash,
                       btrim(validation.transcript_hash)::text
                         AS transcript_hash,
                       btrim(validation.validation_receipt_hash)::text
                         AS validation_receipt_hash,
                       head.last_sequence,
                       head.state_version,
                       btrim(head.base_head_hash)::text AS base_head_hash
                FROM agent_graph_exact_provider_validations_v16 validation
                JOIN agent_graph_exact_attempt_heads_v16 head
                  USING (principal_id, attempt_id)
                WHERE validation.principal_id = :principalId
                  AND validation.attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(
                (row, ignored) -> {
                  assertEquals(
                      command.requirementHash(),
                      row.getString("requirement_hash"));
                  assertEquals(profileHash, row.getString("profile_hash"));
                  assertEquals(
                      command.requestHash(), row.getString("request_hash"));
                  assertEquals(
                      command.responseHash(), row.getString("response_hash"));
                  assertEquals(
                      command.modelResolvedHash(),
                      row.getString("model_resolved_hash"));
                  assertEquals(
                      command.inputTokens(), row.getLong("input_tokens"));
                  assertEquals(
                      command.cachedInputTokens(),
                      row.getLong("cached_input_tokens"));
                  assertEquals(
                      command.outputTokens(), row.getLong("output_tokens"));
                  assertEquals(
                      command.decisionHash(), row.getString("decision_hash"));
                  assertEquals(command.keyId(), row.getString("key_id"));
                  assertEquals("CONSUMED", row.getString("state"));
                  assertEquals(
                      receipt.statementHash(), row.getString("statement_hash"));
                  assertEquals(
                      receipt.attributionHash(),
                      row.getString("attribution_hash"));
                  assertEquals(receipt.eventHash(), row.getString("event_hash"));
                  assertEquals(
                      receipt.overlayHeadHash(),
                      row.getString("overlay_head_hash"));
                  assertEquals(
                      receipt.transcriptHash(),
                      row.getString("transcript_hash"));
                  assertEquals(
                      receipt.validationReceiptHash(),
                      row.getString("validation_receipt_hash"));
                  assertEquals(14, row.getInt("last_sequence"));
                  assertEquals(1L, row.getLong("state_version"));
                  assertEquals(
                      sequence13.cursor().headHash(),
                      row.getString("base_head_hash"));
                  return 1L;
                })
            .single();
    assertEquals(1L, asserted);
  }

  private static void assertV16OverlayRows(
      DataSource admin,
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot sequence13,
      Completion completion,
      String requirementHash,
      String profileHash,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash) {
    StageChallenge challenge = completion.challenge();
    long asserted = JdbcClient.create(admin)
        .sql(
            """
            SELECT validation.state AS validation_state,
                   encode(validation.signature, 'hex') AS signature_hex,
                   btrim(validation.signature_hash)::text
                     AS signature_hash,
                   btrim(validation.validation_receipt_hash)::text
                     AS validation_receipt_hash,
                   validation.validated_at_epoch_micros,
                   validation.consumed_at_epoch_micros,
                   btrim(attribution.provider_profile_hash)::text
                     AS attribution_profile_hash,
                   btrim(attribution.request_hash)::text
                     AS attribution_request_hash,
                   btrim(attribution.response_hash)::text
                     AS attribution_response_hash,
                   attribution.provider_actor,
                   attribution.provider_id,
                   attribution.provider_protocol,
                   attribution.model_requested,
                   btrim(attribution.model_resolved_hash)::text
                     AS attribution_model_resolved_hash,
                   attribution.uncached_input_pico_usd_per_token,
                   attribution.cached_input_pico_usd_per_token,
                   attribution.output_pico_usd_per_token,
                   attribution.input_tokens,
                   attribution.cached_input_tokens,
                   attribution.output_tokens,
                   attribution.reasoning_output_tokens,
                   attribution.total_tokens,
                   attribution.observed_cost_pico_usd,
                   btrim(attribution.statement_hash)::text
                     AS attribution_statement_hash,
                   attribution.decision_kind,
                   btrim(attribution.decision_hash)::text
                     AS attribution_decision_hash,
                   attribution.failure_code,
                   btrim(attribution.attribution_hash)::text
                     AS durable_attribution_hash,
                   attribution.attributed_at_epoch_micros,
                   event.sequence AS event_sequence,
                   event.event_type,
                   event.occurred_at_epoch_micros,
                   btrim(event.previous_head_hash)::text
                     AS event_previous_head_hash,
                   btrim(event.evidence_hash)::text
                     AS event_evidence_hash,
                   btrim(event.statement_hash)::text
                     AS event_statement_hash,
                   btrim(event.event_hash)::text AS durable_event_hash,
                   btrim(event.current_head_hash)::text
                     AS event_current_head_hash,
                   head.state_version AS head_state_version,
                   head.last_sequence,
                   head.phase,
                   head.attribution_status,
                   head.request_ordinal AS head_request_ordinal,
                   head.base_provider_attribution_count,
                   head.overlay_provider_attribution_count,
                   head.effective_provider_attribution_count,
                   btrim(head.statement_hash)::text
                     AS head_statement_hash,
                   btrim(head.attribution_hash)::text
                     AS head_attribution_hash,
                   btrim(head.last_event_hash)::text
                     AS head_last_event_hash,
                   btrim(head.head_hash)::text AS durable_head_hash,
                   head.updated_at_epoch_micros
            FROM agent_graph_exact_provider_validations_v16 validation
            JOIN agent_graph_exact_provider_attributions_v16 attribution
              USING (principal_id, attempt_id)
            JOIN agent_graph_exact_attempt_events_v16 event
              USING (principal_id, attempt_id)
            JOIN agent_graph_exact_attempt_heads_v16 head
              USING (principal_id, attempt_id)
            WHERE validation.principal_id = :principalId
              AND validation.attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (row, ignored) -> {
              assertEquals("CONSUMED", row.getString("validation_state"));
              assertEquals(completion.signatureHex(),
                  row.getString("signature_hex"));
              String signatureHash = row.getString("signature_hash");
              assertEquals(
                  sha256Hex(HexFormat.of().parseHex(
                      completion.signatureHex())),
                  signatureHash);
              long validatedMicros =
                  row.getLong("validated_at_epoch_micros");
              long consumedMicros =
                  row.getLong("consumed_at_epoch_micros");
              assertEquals(validatedMicros, consumedMicros);
              String receiptHash = canonicalHash(
                  RECEIPT_DOMAIN,
                  challenge.protocolVersion(),
                  manifest.principalId(),
                  manifest.attemptId(),
                  manifest.manifestHash(),
                  requirementHash,
                  challenge.transcriptHash(),
                  signatureHash,
                  Long.toString(validatedMicros),
                  Long.toString(consumedMicros),
                  "CONSUMED",
                  challenge.statementHash(),
                  challenge.attributionHash(),
                  challenge.eventHash(),
                  challenge.overlayHeadHash());
              assertEquals(receiptHash,
                  row.getString("validation_receipt_hash"));
              assertEquals(
                  receiptHash,
                  completion.receipt()
                      .path("validation_receipt_hash")
                      .asText());
              assertEquals(profileHash,
                  row.getString("attribution_profile_hash"));
              assertEquals(requestHash,
                  row.getString("attribution_request_hash"));
              assertEquals(responseHash,
                  row.getString("attribution_response_hash"));
              assertEquals(manifest.childActor(),
                  row.getString("provider_actor"));
              assertEquals(SYNTHETIC_PROVIDER_ID,
                  row.getString("provider_id"));
              assertEquals(SYNTHETIC_PROVIDER_PROTOCOL,
                  row.getString("provider_protocol"));
              assertEquals(SYNTHETIC_MODEL_REQUESTED,
                  row.getString("model_requested"));
              assertEquals(modelResolvedHash,
                  row.getString("attribution_model_resolved_hash"));
              assertEquals(140_000L,
                  row.getLong("uncached_input_pico_usd_per_token"));
              assertEquals(2_800L,
                  row.getLong("cached_input_pico_usd_per_token"));
              assertEquals(280_000L,
                  row.getLong("output_pico_usd_per_token"));
              assertEquals(INPUT_TOKENS, row.getLong("input_tokens"));
              assertEquals(CACHED_INPUT_TOKENS,
                  row.getLong("cached_input_tokens"));
              assertEquals(OUTPUT_TOKENS, row.getLong("output_tokens"));
              assertEquals(REASONING_OUTPUT_TOKENS,
                  row.getLong("reasoning_output_tokens"));
              assertEquals(TOTAL_TOKENS, row.getLong("total_tokens"));
              assertEquals(0, EXPECTED_COST_PICO_USD.compareTo(
                  row.getBigDecimal("observed_cost_pico_usd")));
              assertEquals(challenge.statementHash(),
                  row.getString("attribution_statement_hash"));
              assertEquals(DECISION_KIND,
                  row.getString("decision_kind"));
              assertEquals(decisionHash,
                  row.getString("attribution_decision_hash"));
              assertTrue(row.getString("failure_code") == null);
              assertEquals(challenge.attributionHash(),
                  row.getString("durable_attribution_hash"));
              assertEquals(challenge.issuedAtEpochMicros(),
                  row.getLong("attributed_at_epoch_micros"));
              assertEquals(14, row.getInt("event_sequence"));
              assertEquals("EXACT_PROVIDER_ATTRIBUTED",
                  row.getString("event_type"));
              assertEquals(challenge.issuedAtEpochMicros(),
                  row.getLong("occurred_at_epoch_micros"));
              assertEquals(sequence13.cursor().headHash(),
                  row.getString("event_previous_head_hash"));
              assertEquals(challenge.attributionHash(),
                  row.getString("event_evidence_hash"));
              assertEquals(challenge.statementHash(),
                  row.getString("event_statement_hash"));
              assertEquals(challenge.eventHash(),
                  row.getString("durable_event_hash"));
              assertEquals(challenge.overlayHeadHash(),
                  row.getString("event_current_head_hash"));
              assertEquals(1L, row.getLong("head_state_version"));
              assertEquals(14, row.getInt("last_sequence"));
              assertEquals("EXACT_PROVIDER_ATTRIBUTED",
                  row.getString("phase"));
              assertEquals("ATTRIBUTED",
                  row.getString("attribution_status"));
              assertEquals(2, row.getInt("head_request_ordinal"));
              assertEquals(1,
                  row.getInt("base_provider_attribution_count"));
              assertEquals(1,
                  row.getInt("overlay_provider_attribution_count"));
              assertEquals(2,
                  row.getInt("effective_provider_attribution_count"));
              assertEquals(challenge.statementHash(),
                  row.getString("head_statement_hash"));
              assertEquals(challenge.attributionHash(),
                  row.getString("head_attribution_hash"));
              assertEquals(challenge.eventHash(),
                  row.getString("head_last_event_hash"));
              assertEquals(challenge.overlayHeadHash(),
                  row.getString("durable_head_hash"));
              assertEquals(challenge.issuedAtEpochMicros(),
                  row.getLong("updated_at_epoch_micros"));
              return 1L;
            })
        .single();
    assertEquals(1L, asserted);
  }

  private static ObjectNode stagePayload(
      GraphAttemptManifest manifest,
      String requirementHash,
      GraphAttemptSnapshot sequence13,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash,
      String keyId) {
    return stagePayload(
        manifest,
        requirementHash,
        sequence13,
        requestHash,
        responseHash,
        modelResolvedHash,
        decisionHash,
        keyId,
        CHALLENGE_TTL_MILLIS);
  }

  private static ObjectNode stagePayload(
      GraphAttemptManifest manifest,
      String requirementHash,
      GraphAttemptSnapshot sequence13,
      String requestHash,
      String responseHash,
      String modelResolvedHash,
      String decisionHash,
      String keyId,
      int challengeTtlMillis) {
    ObjectNode payload = JsonMapper.shared().createObjectNode();
    payload.put("principal_id", manifest.principalId());
    payload.put("attempt_id", manifest.attemptId());
    payload.put("manifest_hash", manifest.manifestHash());
    payload.put("requirement_hash", requirementHash);
    payload.put("expected_base_sequence", 13);
    payload.put(
        "expected_base_head_hash", sequence13.cursor().headHash());
    payload.put("request_ordinal", 2);
    payload.put("request_hash", requestHash);
    payload.put("response_hash", responseHash);
    payload.put("model_resolved_hash", modelResolvedHash);
    payload.put("input_tokens", INPUT_TOKENS);
    payload.put("cached_input_tokens", CACHED_INPUT_TOKENS);
    payload.put("output_tokens", OUTPUT_TOKENS);
    payload.put(
        "reasoning_output_tokens", REASONING_OUTPUT_TOKENS);
    payload.put("total_tokens", TOTAL_TOKENS);
    payload.put("decision_kind", DECISION_KIND);
    payload.put("decision_hash", decisionHash);
    payload.putNull("failure_code");
    payload.put("key_id", keyId);
    payload.put("challenge_ttl_millis", challengeTtlMillis);
    return payload;
  }

  private static byte[] signatureMaterial(StageChallenge challenge) {
    return frame(
        TRANSCRIPT_DOMAIN,
        challenge.challengeHash(),
        challenge.statementHash(),
        challenge.attributionHash(),
        challenge.eventHash(),
        challenge.overlayHeadHash(),
        challenge.decisionKind(),
        challenge.decisionHash(),
        challenge.failureCode() == null ? "" : challenge.failureCode());
  }

  private static byte[] frame(String domain, String... fields) {
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
    return framed.toByteArray();
  }

  private static byte[] sign(KeyPair keyPair, byte[] material) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(material);
      return signer.sign();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("V16_TEST_SIGNING_FAILED");
    }
  }

  private static void verify(
      KeyPair keyPair, byte[] material, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(keyPair.getPublic());
      verifier.update(material);
      assertTrue(verifier.verify(signature));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("V16_TEST_VERIFICATION_FAILED");
    }
  }

  private static String sha256Hex(byte[] material) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("SHA_256_UNAVAILABLE");
    }
  }

  private static String canonicalHash(
      String domain, String... fields) {
    return sha256Hex(frame(domain, fields));
  }

  private static String insertSyntheticExactPicoProfile(
      DataSource admin) {
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
              :profileId, 'pack010-synthetic',
              'pack010.synthetic.responses',
              'pack010-synthetic-responses-http-v1',
              :transportProfileHash,
              'pack010-synthetic-responses-parser-v1',
              :parserProfileHash,
              'pack010-synthetic-structured-final-v1',
              :schemaProfileHash,
              'pack010-gpt-5.6-terra-synthetic-v1',
              :modelProfileHash,
              'gpt-5.6-terra', :modelResolutionProfileHash,
              'pack010-synthetic-exact-pico-precision-v1',
              'pack010-synthetic',
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
        .param("profileId", SYNTHETIC_PROFILE_ID)
        .param(
            "transportProfileHash",
            Pack010ProviderValidationAttestationHarnessMain
                .TRANSPORT_PROFILE_HASH)
        .param(
            "parserProfileHash",
            Pack010ProviderValidationAttestationHarnessMain
                .PARSER_PROFILE_HASH)
        .param(
            "schemaProfileHash",
            Pack010ProviderValidationAttestationHarnessMain
                .SCHEMA_PROFILE_HASH)
        .param(
            "modelProfileHash",
            IntegrityHashes.utf8ContentHash(
                "pack010-gpt-5.6-terra-synthetic-v1"))
        .param(
            "modelResolutionProfileHash",
            IntegrityHashes.utf8ContentHash(
                "pack010-gpt-5.6-terra-synthetic-resolution-v1"))
        .param(
            "pricingProfileFingerprint",
            IntegrityHashes.utf8ContentHash(
                "pack010-synthetic-exact-pico-precision-v1"))
        .param(
            "pricingSourceHash",
            IntegrityHashes.utf8ContentHash(
                "pack010-test-only-noncommercial-price-source-v1"))
        .query(String.class)
        .single();
  }

  private static String ensureSyntheticExactPicoProfile(
      DataSource admin) {
    JdbcClient jdbc = JdbcClient.create(admin);
    jdbc.sql(
            """
            UPDATE agent_graph_provider_profiles_v14
            SET pricing_effective_until_epoch_micros =
              floor(extract(epoch from clock_timestamp()
                + interval '10 minutes') * 1000000)::bigint
            WHERE profile_id = :profileId
              AND status = 'ACTIVE'
              AND pricing_effective_until_epoch_micros <=
                floor(extract(epoch from clock_timestamp()
                  + interval '5 minutes') * 1000000)::bigint
            """)
        .param("profileId", SYNTHETIC_PROFILE_ID)
        .update();
    return jdbc
        .sql(
            """
            SELECT btrim(profile_hash)::text
            FROM agent_graph_provider_profiles_v14
            WHERE profile_id = :profileId
            """)
        .param("profileId", SYNTHETIC_PROFILE_ID)
        .query(String.class)
        .optional()
        .orElseGet(() -> insertSyntheticExactPicoProfile(admin));
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
              CAST(:profileId AS varchar)))::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("profileId", SYNTHETIC_PROFILE_ID)
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

  private static BaseValidation baseValidation(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT btrim(policy_hash)::text AS policy_hash,
                   revision,
                   btrim(session_intent_hash)::text
                     AS session_intent_hash,
                   floor(extract(epoch FROM session_expires_at)
                     * 1000000)::bigint
                     AS session_expires_at_epoch_micros,
                   key_id,
                   btrim(key_fingerprint)::text AS key_fingerprint,
                   state
            FROM agent_graph_provider_validations
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (row, ignored) ->
                new BaseValidation(
                    row.getString("policy_hash"),
                    row.getString("revision"),
                    row.getString("session_intent_hash"),
                    row.getLong(
                        "session_expires_at_epoch_micros"),
                    row.getString("key_id"),
                    row.getString("key_fingerprint"),
                    row.getString("state")))
        .single();
  }

  private static RuntimeIdentity runtimeIdentity(DataSource admin) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT current_database() AS database_name,
                   database.oid AS database_oid,
                   namespace.oid AS schema_oid,
                   role.oid AS attestor_role_oid
            FROM pg_catalog.pg_database database
            CROSS JOIN pg_catalog.pg_namespace namespace
            CROSS JOIN pg_catalog.pg_roles role
            WHERE database.datname = current_database()
              AND namespace.nspname = 'public'
              AND role.rolname = 'emergeos_provider_attestor_v16'
            """)
        .query(
            (row, ignored) ->
                new RuntimeIdentity(
                    row.getString("database_name"),
                    row.getLong("database_oid"),
                    row.getLong("schema_oid"),
                    row.getLong("attestor_role_oid")))
        .single();
  }

  private static long legacySequence14Count(
      DataSource admin, GraphAttemptManifest manifest) {
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT count(*)
            FROM agent_graph_attempt_events
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND sequence = 14
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private static String rowImage(
      DataSource admin,
      String table,
      GraphAttemptManifest manifest) {
    if (!table.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("PUBLIC_TABLE_NAME_INVALID");
    }
    return JdbcClient.create(admin)
        .sql(
            """
            SELECT pg_catalog.to_jsonb(row_data)::text
                     || E'\\x1f' || row_data.xmin::text
            FROM public."%s" row_data
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """.formatted(table))
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(String.class)
        .single();
  }

  private static DatabaseImage databaseImage(DataSource source) {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      connection.setTransactionIsolation(
          Connection.TRANSACTION_REPEATABLE_READ);
      JdbcClient jdbc =
          JdbcClient.create(
              new SingleConnectionDataSource(connection, true));
      Map<String, TableImage> tables = new LinkedHashMap<>();
      for (String table
          : jdbc.sql(
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
              .list()) {
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

  private static void assertHash(String value) {
    assertTrue(value != null && value.matches("[0-9a-f]{64}"));
  }

  private static String trimmed(String value) {
    return value == null ? null : value.trim();
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
      String v13Password,
      String v15Password,
      String v16Password) throws SQLException {
    execute(
        admin,
        resource("db/provisioning/pack010_runtime_roles.sql")
            + System.lineSeparator()
            + resource(
                "db/provisioning/pack010_runtime_roles_check.sql"));
    assertEquals(
        1L,
        JdbcClient.create(admin)
            .sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_roles
                WHERE rolname = 'emergeos_provider_attestor_v16'
                """)
            .query(Long.class)
            .single());
    setEphemeralPassword(
        admin, "emergeos_graph_prefix_writer", writerPassword);
    setEphemeralPassword(
        admin, "emergeos_provider_attestor", v13Password);
    setEphemeralPassword(admin, V15_ROLE, v15Password);
    setEphemeralPassword(admin, V16_ROLE, v16Password);
  }

  private static void setEphemeralPassword(
      DataSource admin, String role, String password) {
    if (!("emergeos_graph_prefix_writer".equals(role)
        || "emergeos_provider_attestor".equals(role)
        || V15_ROLE.equals(role)
        || V16_ROLE.equals(role))) {
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
        Pack010ExactPicoOverlayTxAAcceptanceTest.class
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

  private record BaseValidation(
      String policyHash,
      String revision,
      String sessionIntentHash,
      long sessionExpiresAtEpochMicros,
      String keyId,
      String keyFingerprint,
      String state) {}

  private record StageChallenge(
      String protocolVersion,
      String databaseName,
      long databaseOid,
      long schemaOid,
      long attestorRoleOid,
      String principalId,
      String attemptId,
      String manifestHash,
      String requirementHash,
      String baseValidationPolicyHash,
      String revision,
      String sessionIntentHash,
      long sessionExpiresAtEpochMicros,
      int baseSequence,
      String baseHeadHash,
      int overlaySequence,
      int requestOrdinal,
      String providerProfileId,
      String providerProfileHash,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      String keyId,
      String keyFingerprint,
      String validationNonce,
      long issuedAtEpochMicros,
      long expiresAtEpochMicros,
      String statementHash,
      String attributionHash,
      String eventHash,
      String overlayHeadHash,
      String challengeHash,
      String transcriptHash,
      BigDecimal observedCostPicoUsd,
      byte[] publicKeyDer,
      long stateVersion,
      String providerActor,
      String providerId,
      String providerProtocol,
      String baseExecutionPricingFingerprint,
      String modelRequested,
      String modelResolvedHash,
      String modelResolutionProfileHash,
      String pricingProfileId,
      String pricingProviderId,
      String pricingProfileFingerprint,
      String pricingSourceHash,
      String rateUnit,
      long uncachedInputPicoUsdPerToken,
      long cachedInputPicoUsdPerToken,
      long outputPicoUsdPerToken,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      String decisionKind,
      String decisionHash,
      String failureCode,
      long attributedAtEpochMicros,
      long occurredAtEpochMicros,
      long updatedAtEpochMicros) {}

  private record Completion(
      StageChallenge challenge,
      ObjectNode commitPayload,
      JsonNode receipt,
      String signatureHex) {}

  private record TableImage(long rowCount, String digest) {}

  private record DatabaseImage(Map<String, TableImage> tables) {}

  private record RuntimeIdentity(
      String databaseName,
      long databaseOid,
      long schemaOid,
      long attestorRoleOid) {}
}
