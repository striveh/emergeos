package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
class Pack010PostgresRuntimeCompositionIT {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_runtime_composition")
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
  void typedRuntimeOwnsCanonicalTxBAndTxCAfterAttributionAndRestart()
      throws Exception {
    DataSource admin =
        dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    JdbcClient jdbc = JdbcClient.create(admin);
    String provisioning =
        resource("/db/provisioning/pack010_runtime_roles.sql");
    execute(admin, provisioning);
    String executorPassword = UUID.randomUUID().toString();
    String writerPassword = UUID.randomUUID().toString();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_executor PASSWORD '"
                + executorPassword
                + "'")
        .update();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + writerPassword
                + "'")
        .update();
    DataSource prefix =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource terminal =
        dataSource("emergeos_graph_executor", executorPassword);
    PostgresGraphAttemptStore prefixWriter =
        Pack010GraphTerminalStoreBridge.openWriter(prefix);
    GraphAttemptManifest manifest = Pack010GraphEvalCatalog.manifest(1);
    List<GraphAttemptManifest> catalog =
        List.of(
            Pack010GraphEvalCatalog.manifest(1),
            Pack010GraphEvalCatalog.manifest(2),
            Pack010GraphEvalCatalog.manifest(3));
    Pack010GraphTerminalFixture.ensureCapture(admin);

    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, prefixWriter, 1);
    Instant sessionIntentExpiry =
        jdbc.sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '5 minutes'")
            .query(Instant.class)
            .single();
    var sessionIntent =
        Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
            prefix,
            manifest,
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            Pack010GraphTerminalFixture.catalogIntent(1, 1),
            sessionIntentExpiry);
    assertEquals(7, sessionIntent.cursorSequence());
    assertEquals(sequence7.cursor().headHash(), sessionIntent.cursorHeadHash());
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.advanceCatalogEgressToTxAPrefixOnly(
            prefixWriter, sequence7, 1);
    Pack010GraphTerminalFixture.completeCatalogTxA(
        prefixWriter, sequence13, 1);
    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest);
    assertEquals(14, sequence14.cursor().lastSequence());
    assertEquals(2, sequence14.providerAttributions().size());
    assertTrue(
        sequence14.providerAttributions().stream()
            .allMatch(
                attribution ->
                    attribution.responseHash().length() == 64
                        && attribution.cachedInputTokens()
                            <= attribution.inputTokens()
                        && attribution.reasoningOutputTokens()
                            <= attribution.outputTokens()
                        && attribution.inputTokens()
                                + attribution.outputTokens()
                            == attribution.totalTokens()));

    GraphAttemptManifest otherManifest = Pack010GraphEvalCatalog.manifest(2);
    GraphAttemptSnapshot otherSequence13 =
        Pack010GraphTerminalFixture.prepareCatalogTxAPrefixOnly(
            admin, prefixWriter, 2);
    Pack010GraphTerminalFixture.completeCatalogTxA(
        prefixWriter, otherSequence13, 2);
    GraphAttemptSnapshot otherSequence14 =
        Pack010GraphTerminalFixture.verified(prefixWriter, otherManifest);
    var otherChildTruth =
        Pack010GraphTerminalFixture.successfulChild(
            otherSequence14,
            otherManifest,
            Pack010GraphEvalCatalog.workerProfile(2),
            otherManifest.startedAt());

    var binding =
        Pack010GraphTerminalStoreBridge.syntheticTerminalBinding(
            prefix,
            catalog,
            manifest,
            sequence14.cursor(),
            Pack010GraphEvalCatalog.parentRun(1),
            Pack010GraphEvalCatalog.childRun(1),
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            "PROVIDER_ATTRIBUTED_2");
    Pack010PostgresRuntimeComposition runtime =
        Pack010PostgresRuntimeComposition.open(
            prefix,
            terminal,
            binding.ownerAuthority(),
            binding.capability(),
            binding.coordinator(),
            binding.egress(),
            OwnerTtyGraphAuthority.Pack010Revision.R1);
    var childTruth =
        Pack010GraphTerminalFixture.successfulChild(
            sequence14,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(1),
            manifest.startedAt());
    var outcome =
        Pack010TerminalOutcomeTestBridge.syntheticOutcome(
            binding,
            manifest,
            sequence14,
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            childTruth.candidate());

    assertThrows(
        RuntimeException.class,
        () ->
            runtime.prepareChild(
                outcome,
                childTruth.candidate(),
                otherChildTruth.terminal(),
                childTruth.workerResult()));
    assertThrows(
        RuntimeException.class,
        () ->
            runtime.prepareChild(
                outcome,
                childTruth.candidate(),
                childTruth.terminal(),
                otherChildTruth.workerResult()));
    assertEquals(
        14,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest)
            .cursor()
            .lastSequence());
    var childCommand =
        runtime.prepareChild(
            outcome,
            childTruth.candidate(),
            childTruth.terminal(),
            childTruth.workerResult());

    ConcurrentLinkedQueue<RuntimeException> childFailures =
        new ConcurrentLinkedQueue<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(() -> tryCompleteChild(runtime, childCommand, childFailures));
      var second =
          pool.submit(() -> tryCompleteChild(runtime, childCommand, childFailures));
      assertTrue(first.get() ^ second.get());
    }
    assertEquals(1, childFailures.size());
    GraphAttemptSnapshot sequence15 =
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest);
    assertEquals(15, sequence15.cursor().lastSequence());
    assertEquals(childTruth.terminal(), sequence15.childRun());
    assertEquals(childTruth.candidate(), sequence15.candidate());
    assertEquals(childTruth.workerResult(), sequence15.workerResult());
    assertThrows(RuntimeException.class, () -> runtime.completeChild(childCommand));

    var parentTruth =
        Pack010GraphTerminalFixture.successfulParent(
            sequence15,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(1),
            Pack010GraphEvalCatalog.parentProfile(1),
            manifest.startedAt());
    ArtifactLineage wrongPrincipalArtifact =
        new ArtifactLineage(
            parentTruth.artifact().artifactId(),
            "synthetic-wrong-principal",
            parentTruth.artifact().sourceCaptureId(),
            parentTruth.artifact().versions());
    assertThrows(
        RuntimeException.class,
        () ->
            runtime.prepareParentAndSeal(
                parentTruth.terminal(), wrongPrincipalArtifact));
    assertEquals(
        15,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest)
            .cursor()
            .lastSequence());
    var parentCommand1 =
        runtime.prepareParentAndSeal(
            parentTruth.terminal(), parentTruth.artifact());
    var parentCommand2 =
        runtime.prepareParentAndSeal(
            parentTruth.terminal(), parentTruth.artifact());

    restartPostgres(prefix);
    prefixWriter = Pack010GraphTerminalStoreBridge.openWriter(prefix);
    assertEquals(
        sequence15,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest));
    assertEquals(
        1L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM agent_graph_provider_session_intents
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND revision = 'r1'
                  AND cursor_sequence = :cursorSequence
                  AND cursor_head_hash = :cursorHeadHash
                  AND request_ordinal = 1
                  AND intent_hash = :intentHash
                  AND expires_at = CAST(:expiresAt AS timestamptz)
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .param("cursorSequence", sessionIntent.cursorSequence())
            .param("cursorHeadHash", sessionIntent.cursorHeadHash())
            .param("intentHash", sessionIntent.intentHash())
            .param("expiresAt", sessionIntent.expiresAt().toString())
            .query(Long.class)
            .single());
    ConcurrentLinkedQueue<RuntimeException> parentFailures =
        new ConcurrentLinkedQueue<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> tryCompleteParent(runtime, parentCommand1, parentFailures));
      var second =
          pool.submit(
              () -> tryCompleteParent(runtime, parentCommand2, parentFailures));
      assertTrue(first.get() ^ second.get());
    }
    assertEquals(1, parentFailures.size());
    GraphAttemptSnapshot sequence17 =
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest);
    assertEquals(17, sequence17.cursor().lastSequence());
    assertEquals(parentTruth.terminal(), sequence17.parentRun());
    assertEquals(parentTruth.artifact(), sequence17.artifact());
    assertTrue(sequence17.terminalSealPresent());
    assertEquals(
        3L,
        jdbc.sql(
                "SELECT count(*) FROM agent_graph_attempt_events "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId "
                    + "AND sequence IN (15, 16, 17) "
                    + "AND committed_at >= occurred_at")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());
    assertThrows(
        RuntimeException.class,
        () -> runtime.completeParentAndSeal(parentCommand1));
    assertThrows(
        RuntimeException.class,
        () -> runtime.completeParentAndSeal(parentCommand2));

    restartPostgres(prefix);
    GraphAttemptSnapshot reconciled =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(prefix), manifest);
    assertEquals(sequence17, reconciled);
    System.out.println(
        "PACK010_TYPED_RUNTIME_COMPOSITION_RECEIPT"
            + " prefixRole=FIXED executorRole=FIXED"
            + " canonicalChild=true canonicalParent=true"
            + " rawCallerPayload=false txB=SEMANTIC_ONCE"
            + " txC=SEMANTIC_ONCE attributionBeforeTerminal=true"
            + " typedDriftNoBurn=true concurrentOneWinner=true"
            + " postgresRestart=RECONCILED"
            + " keyReads=0 clientFactories=0 modelFactories=0"
            + " httpRequests=0 providerCalls=0 billing=0"
            + " providerExactlyOnceClaim=false"
            + " shippingExecute=DISABLED synthetic=true");
  }

  @Test
  void legacyProcessLocalFailureCompletionIsFencedWithoutEffects()
      throws Exception {
    DataSource admin =
        dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles.sql"));
    JdbcClient jdbc = JdbcClient.create(admin);
    String executorPassword = UUID.randomUUID().toString();
    String writerPassword = UUID.randomUUID().toString();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_executor PASSWORD '"
                + executorPassword
                + "'")
        .update();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + writerPassword
                + "'")
        .update();
    DataSource prefix =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource terminal =
        dataSource("emergeos_graph_executor", executorPassword);
    PostgresGraphAttemptStore prefixWriter =
        Pack010GraphTerminalStoreBridge.openWriter(prefix);
    int repetition = 3;
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    List<GraphAttemptManifest> catalog =
        List.of(
            Pack010GraphEvalCatalog.manifest(1),
            Pack010GraphEvalCatalog.manifest(2),
            manifest);
    GraphAttemptSnapshot sequence13 =
        Pack010GraphTerminalFixture.prepareCatalogTxAPrefixOnly(
            admin, prefixWriter, repetition);
    Pack010GraphTerminalFixture.completeCatalogTxA(
        prefixWriter, sequence13, repetition);
    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest);
    var binding =
        Pack010GraphTerminalStoreBridge.syntheticTerminalBinding(
            prefix,
            catalog,
            manifest,
            sequence14.cursor(),
            Pack010GraphEvalCatalog.parentRun(repetition),
            Pack010GraphEvalCatalog.childRun(repetition),
            OwnerTtyGraphAuthority.Pack010Revision.R3,
            "PROVIDER_ATTRIBUTED_2");
    Pack010PostgresRuntimeComposition runtime =
        Pack010PostgresRuntimeComposition.open(
            prefix,
            terminal,
            binding.ownerAuthority(),
            binding.capability(),
            binding.coordinator(),
            binding.egress(),
            OwnerTtyGraphAuthority.Pack010Revision.R3);
    var malformed =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
            sequence14,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(repetition),
            manifest.startedAt(),
            "MODEL_RESPONSE_MALFORMED");
    var wrongTerminal =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
            sequence14,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(repetition),
            manifest.startedAt(),
            "MODEL_USAGE_LIMIT_EXCEEDED");
    var successShape =
        Pack010GraphTerminalFixture.successfulChild(
            sequence14,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(repetition),
            manifest.startedAt());
    JsonMapper json = JsonMapper.shared();
    var nonAllowlisted =
        Pack010GraphTerminalFixture
            .rawNonAllowlistedAttributedFailureChild(
                sequence14,
                manifest,
                Pack010GraphEvalCatalog.workerProfile(repetition),
                manifest.startedAt());
    String nonAllowlistedPayload =
        Pack010GraphTerminalStoreBridge
            .preCandidateFailureChildPayload(
                sequence14, nonAllowlisted.terminal());
    assertRawChildRejectedWithoutMutation(
        terminal, jdbc, manifest, nonAllowlistedPayload);
    ObjectNode forgedFailurePayload =
        (ObjectNode)
            json.readTree(
                Pack010GraphTerminalStoreBridge
                    .preCandidateFailureChildPayload(
                        sequence14, malformed.terminal()));
    ObjectNode successPayload =
        (ObjectNode)
            json.readTree(
                Pack010GraphTerminalStoreBridge.successfulChildPayload(
                    sequence14,
                    successShape.terminal(),
                    successShape.candidate(),
                    successShape.workerResult()));
    forgedFailurePayload.set("candidate", successPayload.get("candidate"));
    assertRawChildRejectedWithoutMutation(
        terminal,
        jdbc,
        manifest,
        json.writeValueAsString(forgedFailurePayload));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM agent_graph_attempt_candidates "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());
    var outcome =
        Pack010TerminalOutcomeTestBridge.syntheticFailedOutcome(
            binding,
            manifest,
            sequence14,
            OwnerTtyGraphAuthority.Pack010Revision.R3,
            malformed.failureReason());

    assertThrows(
        RuntimeException.class,
        () ->
            runtime.preparePreCandidateFailureChild(
                outcome, wrongTerminal.terminal()));
    assertEquals(
        14,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest)
            .cursor()
            .lastSequence());
    var childCommand =
        runtime.preparePreCandidateFailureChild(
            outcome, malformed.terminal());
    RuntimeException fenced =
        assertThrows(
            RuntimeException.class,
            () -> runtime.completePreCandidateFailureChild(childCommand));
    assertV12FailureResumeGuard(
        fenced, "V12 durable failure outcome is missing");
    assertEquals(
        sequence14,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT "
                    + "(SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId) + "
                    + "(SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_terminal_resumes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId)")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());
    IllegalStateException replayFenced =
        assertThrows(
            IllegalStateException.class,
            () -> runtime.completePreCandidateFailureChild(childCommand));
    assertEquals(
        "Pack010 failed child command is invalid or consumed",
        replayFenced.getMessage());
    restartPostgres(prefix);
    assertEquals(
        sequence14,
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(prefix), manifest));
    System.out.println(
        "PACK010_V12_LEGACY_FAILURE_PATH_FENCED_RECEIPT"
            + " request2Attributed=true failureCode=CLOSED_ALLOWLIST"
            + " legacyProcessLocalTxB=FENCED sequence=14"
            + " terminalReceipt=NONE postgresRestart=RECONCILED"
            + " keyReads=0 clientFactories=0 modelFactories=0"
            + " httpRequests=0 providerCalls=0 billingEffects=0"
            + " providerExactlyOnceClaim=false"
            + " shippingExecute=DISABLED synthetic=true");
  }

  @Test
  void rawV10FailureSemanticFunctionIsFencedAcrossIndependentSessions()
      throws Exception {
    DataSource admin =
        dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles.sql"));
    JdbcClient jdbc = JdbcClient.create(admin);
    String executorPassword = UUID.randomUUID().toString();
    String writerPassword = UUID.randomUUID().toString();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_executor PASSWORD '"
                + executorPassword
                + "'")
        .update();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + writerPassword
                + "'")
        .update();
    DataSource prefix =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource terminal =
        dataSource("emergeos_graph_executor", executorPassword);
    PostgresGraphAttemptStore prefixWriter =
        Pack010GraphTerminalStoreBridge.openWriter(prefix);
    GraphAttemptManifest manifest = Pack010GraphTerminalFixture.manifest();
    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.prepareTxB(admin, prefixWriter);
    JsonMapper json = JsonMapper.shared();
    var successfulChild =
        Pack010GraphTerminalFixture.successfulChild(sequence14);
    ObjectNode forgedSuccessfulFailure =
        (ObjectNode)
            json.readTree(
                Pack010GraphTerminalStoreBridge.successfulChildPayload(
                    sequence14,
                    successfulChild.terminal(),
                    successfulChild.candidate(),
                    successfulChild.workerResult()));
    ObjectNode forgedSuccessfulRun =
        (ObjectNode) forgedSuccessfulFailure.get("terminal_run");
    forgedSuccessfulRun.put(
        "failure_attribution", "FORGED_STABLE_FAILURE");
    ((ObjectNode) forgedSuccessfulRun.get("result_envelope"))
        .put("failureReason", "FORGED_STABLE_FAILURE");
    ((ObjectNode) forgedSuccessfulRun.get("bundle"))
        .put("failureAttribution", "FORGED_STABLE_FAILURE");
    ((ObjectNode)
            ((ObjectNode) forgedSuccessfulRun.get("bundle")).get("result"))
        .put("failureReason", "FORGED_STABLE_FAILURE");
    assertRawChildRejectedWithoutMutation(
        terminal,
        jdbc,
        manifest,
        json.writeValueAsString(forgedSuccessfulFailure));
    var failedChild =
        Pack010GraphTerminalFixture.attributedPreCandidateFailureChild(
            sequence14,
            manifest,
            Pack010GraphTerminalFixture.workerProfile(),
            manifest.startedAt(),
            "MODEL_RESPONSE_MALFORMED");
    String childPayload =
        Pack010GraphTerminalStoreBridge.preCandidateFailureChildPayload(
            sequence14, failedChild.terminal());
    ObjectNode forgedNestedChildFailure =
        (ObjectNode) json.readTree(childPayload);
    ObjectNode forgedChildRun =
        (ObjectNode) forgedNestedChildFailure.get("terminal_run");
    ((ObjectNode) forgedChildRun.get("result_envelope"))
        .put("failureReason", "FORGED_STABLE_FAILURE");
    ((ObjectNode)
            ((ObjectNode) forgedChildRun.get("bundle")).get("result"))
        .put("failureReason", "FORGED_STABLE_FAILURE");
    assertRawChildRejectedWithoutMutation(
        terminal,
        jdbc,
        manifest,
        json.writeValueAsString(forgedNestedChildFailure));
    ConcurrentLinkedQueue<RuntimeException> childFailures =
        new ConcurrentLinkedQueue<>();
    assertRawSemanticRaceRejected(
        terminal,
        "agent_graph_complete_child_v10",
        childPayload,
        childFailures);
    assertEquals(2, childFailures.size());
    childFailures.forEach(
        failure ->
            assertV12FailureResumeGuard(
                failure, "V12 durable failure outcome is missing"));
    assertEquals(
        sequence14,
        Pack010GraphTerminalFixture.verified(prefixWriter, manifest));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT "
                    + "(SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId) + "
                    + "(SELECT count(*) "
                    + "FROM agent_graph_attributed_failure_terminal_resumes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId)")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(Long.class)
            .single());

    restartPostgres(prefix);
    assertEquals(
        sequence14,
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(prefix),
            manifest));
    System.out.println(
        "PACK010_V12_RAW_V10_FAILURE_FENCE_RECEIPT"
            + " txBIndependentSessions=2 txBWinners=0"
            + " sequence=14 partialRows=NONE terminalReceipt=NONE"
            + " postgresRestart=SEQ14_RECONCILED"
            + " providerExactlyOnceClaim=false synthetic=true");
  }

  private static void assertRawSemanticRaceRejected(
      DataSource terminal,
      String function,
      String payload,
      ConcurrentLinkedQueue<RuntimeException> failures)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  tryRawSemantic(
                      terminal,
                      function,
                      payload,
                      ready,
                      release,
                      failures));
      var second =
          pool.submit(
              () ->
                  tryRawSemantic(
                      terminal,
                      function,
                      payload,
                      ready,
                      release,
                      failures));
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      release.countDown();
      assertFalse(first.get());
      assertFalse(second.get());
    }
  }

  private static void assertRawChildRejectedWithoutMutation(
      DataSource terminal,
      JdbcClient jdbc,
      GraphAttemptManifest manifest,
      String payload) {
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(terminal)
                    .sql(
                        "SELECT public."
                            + "agent_graph_complete_child_v10("
                            + "CAST(:payload AS jsonb))")
                    .param("payload", payload)
                    .query(String.class)
                    .single());
    assertSemanticGuardFailure(
        failure, "V10 child payload escapes TX-B aggregate");
    assertEquals(
        "14|0|0|RUNNING",
        jdbc.sql(
                """
                SELECT head.last_sequence::text || '|'
                       || (SELECT count(*)
                           FROM agent_graph_attempt_events event
                           WHERE event.principal_id = head.principal_id
                             AND event.attempt_id = head.attempt_id
                             AND event.sequence > 14)::text || '|'
                       || (SELECT count(*)
                           FROM agent_graph_attempt_terminal_bindings binding
                           WHERE binding.principal_id = head.principal_id
                             AND binding.attempt_id = head.attempt_id
                             AND binding.role = 'CHILD')::text || '|'
                       || (SELECT run.lifecycle_status
                           FROM agent_runs run
                           WHERE run.principal_id = head.principal_id
                             AND run.graph_attempt_id = head.attempt_id
                             AND run.graph_role = 'CHILD')
                FROM agent_graph_attempt_heads head
                WHERE head.principal_id = :principalId
                  AND head.attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(String.class)
            .single());
  }

  private static void assertSemanticGuardFailure(
      RuntimeException failure, String expectedMessage) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root instanceof SQLException);
    assertEquals("22023", ((SQLException) root).getSQLState());
    assertTrue(root.getMessage().contains(expectedMessage));
  }

  private static void assertV12FailureResumeGuard(
      RuntimeException failure, String expectedMessage) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root instanceof SQLException);
    assertEquals("55000", ((SQLException) root).getSQLState());
    assertTrue(root.getMessage().contains(expectedMessage));
  }

  private static boolean tryRawSemantic(
      DataSource terminal,
      String function,
      String payload,
      CountDownLatch ready,
      CountDownLatch release,
      ConcurrentLinkedQueue<RuntimeException> failures) {
    ready.countDown();
    try {
      release.await();
      JdbcClient.create(terminal)
          .sql(
              "SELECT public."
                  + function
                  + "(CAST(:payload AS jsonb))")
          .param("payload", payload)
          .query(String.class)
          .single();
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      IllegalStateException failure =
          new IllegalStateException("raw semantic race interrupted");
      failures.add(failure);
      return false;
    } catch (RuntimeException rejected) {
      failures.add(rejected);
      return false;
    }
  }

  private static boolean tryCompleteChild(
      Pack010PostgresRuntimeComposition runtime,
      Pack010PostgresRuntimeComposition.Pack010ChildTerminalCommand command,
      ConcurrentLinkedQueue<RuntimeException> failures) {
    try {
      runtime.completeChild(command);
      return true;
    } catch (RuntimeException rejected) {
      failures.add(rejected);
      return false;
    }
  }

  private static boolean tryCompleteParent(
      Pack010PostgresRuntimeComposition runtime,
      Pack010PostgresRuntimeComposition.Pack010ParentTerminalCommand command,
      ConcurrentLinkedQueue<RuntimeException> failures) {
    try {
      runtime.completeParentAndSeal(command);
      return true;
    } catch (RuntimeException rejected) {
      failures.add(rejected);
      return false;
    }
  }

  private static String resource(String path) throws Exception {
    try (var input =
        Pack010PostgresRuntimeCompositionIT.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("resource missing");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
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

  private static DataSource dataSource(String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void restartPostgres(DataSource probe) throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart -D \"$PGDATA\" -m immediate -w");
    assertEquals(0, restart.getExitCode(), restart.getStderr());
    long deadline =
        System.nanoTime() + Duration.ofSeconds(30).toNanos();
    SQLException last = null;
    while (System.nanoTime() < deadline) {
      try (Connection ignored = probe.getConnection()) {
        return;
      } catch (SQLException unavailable) {
        last = unavailable;
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("PostgreSQL restart timed out", last);
  }
}
