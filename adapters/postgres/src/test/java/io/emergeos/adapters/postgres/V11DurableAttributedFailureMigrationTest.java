package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Forward-only durable failure provenance and V12 authority acceptance. */
@Testcontainers
class V11DurableAttributedFailureMigrationTest {

  private static final List<String> V10_TRUTH_TABLES =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");

  private static final List<String> V11_TRUTH_TABLES =
      java.util.stream.Stream.concat(
              V10_TRUTH_TABLES.stream(),
              java.util.stream.Stream.of(
                  "agent_graph_attributed_failure_outcomes"))
          .toList();

  private static final List<String> V12_TRUTH_TABLES =
      java.util.stream.Stream.concat(
              V11_TRUTH_TABLES.stream(),
              java.util.stream.Stream.of(
                  "agent_graph_attributed_failure_terminal_resumes"))
          .toList();

  private static final List<String> V13_TRUTH_TABLES =
      java.util.stream.Stream.concat(
              V12_TRUTH_TABLES.stream(),
              java.util.stream.Stream.of(
                  "agent_graph_provider_validation_keys",
                  "agent_graph_provider_validations"))
          .toList();

  private static final List<String> V14_TRUTH_TABLES =
      java.util.stream.Stream.concat(
              V13_TRUTH_TABLES.stream(),
              java.util.stream.Stream.of(
                  "agent_graph_provider_profiles_v14"))
          .toList();

  private static final List<String> V15_TRUTH_TABLES =
      java.util.stream.Stream.concat(
              V14_TRUTH_TABLES.stream(),
              java.util.stream.Stream.of(
                  "agent_graph_exact_tx_a_requirements_v15"))
          .toList();

  private static final List<String> V13_FUNCTION_SIGNATURES =
      List.of(
          "public.agent_graph_framed_sha256_v13("
              + "character varying,text[])",
          "public.agent_graph_require_provider_validation_v13("
              + "character varying,character,character,character varying,"
              + "character,character,character,integer)",
          "public.agent_graph_stage_provider_validation_v13(jsonb)",
          "public.agent_graph_commit_provider_validation_v13(jsonb)",
          "public.agent_graph_assert_provider_validation_v13()");

  private static final List<String> V14_FUNCTION_SIGNATURES =
      List.of(
          "public.agent_graph_framed_sha256_v14("
              + "character varying,text[])",
          "public.agent_graph_assert_provider_statement_v14(jsonb)");

  private static final List<String> V13_V14_FUNCTION_SIGNATURES =
      java.util.stream.Stream.concat(
              V13_FUNCTION_SIGNATURES.stream(),
              V14_FUNCTION_SIGNATURES.stream())
          .toList();

  private static final List<String> V15_FUNCTION_SIGNATURES =
      List.of(
          "public.agent_graph_require_exact_tx_a_v15("
              + "character varying,character,character,character varying)",
          "public.agent_graph_assert_exact_tx_a_requirement_v15()");

  private static final List<String> V13_V15_FUNCTION_SIGNATURES =
      java.util.stream.Stream.concat(
              V13_V14_FUNCTION_SIGNATURES.stream(),
              V15_FUNCTION_SIGNATURES.stream())
          .toList();

  private static final List<String> V15_TRIGGER_NAMES =
      List.of(
          "agent_graph_exact_tx_a_attribution_v15",
          "agent_graph_exact_tx_a_event_v15",
          "agent_graph_exact_tx_a_head_v15",
          "agent_graph_exact_tx_a_requirement_v15");

  private static final List<String> V16_FUNCTION_SIGNATURES =
      List.of(
          "public.agent_graph_stage_exact_tx_a_v16(jsonb)",
          "public.agent_graph_commit_exact_tx_a_v16(jsonb)",
          "public.agent_graph_assert_exact_tx_a_overlay_v16()");

  private static final List<String> V16_TRIGGER_NAMES =
      List.of(
          "agent_graph_exact_attempt_event_v16",
          "agent_graph_exact_attempt_head_v16",
          "agent_graph_exact_provider_attribution_v16",
          "agent_graph_exact_provider_validation_v16");

  private static final List<String> V16_TABLES =
      List.of(
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_provider_validations_v16");

  private static final List<String> V11_FUNCTIONS =
      List.of(
          "agent_graph_claim_attributed_failure_v11",
          "agent_graph_read_attributed_failure_v11",
          "agent_graph_record_attributed_failure_v11");

  private static final List<String> V12_NEW_FUNCTIONS =
      List.of(
          "agent_graph_require_failure_resumer_v12",
          "agent_graph_read_attributed_failure_resume_v12",
          "agent_graph_claim_attributed_failure_resume_v12",
          "agent_graph_complete_claimed_failure_child_v12",
          "agent_graph_complete_claimed_failure_parent_and_seal_v12",
          "agent_graph_assert_failure_terminal_resume_v12");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_v11_failure_resume")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @Test
  void freshV16PreservesV12RevocationAndProvisioningCreatesExactRoleSplit() {
    DataSource admin = baseDataSource();
    Flyway flyway =
        Flyway.configure()
            .dataSource(admin)
            .target(MigrationVersion.fromVersion("16"))
            .load();
    assertEquals(16, flyway.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("16"),
        flyway.info().current().getVersion());
    JdbcClient jdbc = JdbcClient.create(admin);
    assertEquals(
        V11_FUNCTIONS,
        jdbc.sql(
                "SELECT proname FROM pg_catalog.pg_proc procedure "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = procedure.pronamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND proname LIKE 'agent_graph_%attributed_failure_v11' "
                    + "ORDER BY proname")
            .query(String.class)
            .list());
    assertEquals(0L, publicExecuteCount(jdbc));
    assertEquals(3L, trustedSecurityDefinerCount(jdbc));
    assertEquals(0L, publicExecuteCount(jdbc, V12_NEW_FUNCTIONS));
    assertEquals(
        V12_NEW_FUNCTIONS.size() - 1L,
        trustedSecurityDefinerCount(jdbc, V12_NEW_FUNCTIONS));
    assertEquals(
        List.of("agent_graph_require_failure_resumer_v12"),
        fixedSearchPathInvokerFunctions(jdbc, V12_NEW_FUNCTIONS));
    assertEquals(1L, relationCount(jdbc, "agent_graph_attributed_failure_outcomes"));
    assertEquals(
        1L,
        relationCount(
            jdbc, "agent_graph_attributed_failure_terminal_resumes"));
    assertEquals(
        Map.of(
            "agent_graph_claim_attributed_failure_v11",
            "877a7751f6e514716d7e93d6c2753e5b|"
                + "546182f85842c6adba7db4f37260f111f87f55c50e311624c273025410a1955b|"
                + "checked_principal character varying, checked_attempt character, checked_manifest character, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character, checked_lease_millis integer|bigint",
            "agent_graph_record_attributed_failure_v11",
            "fa3fcf0d0bd93465f089b03dd6a0194e|"
                + "4ddd44034bcaf7aa516c78785f0095c9cacec933b651630d2a1675a161de31b4|"
                + "checked_principal character varying, checked_attempt character, checked_manifest character, checked_previous_sequence integer, checked_previous_head character, checked_failure_code character varying|character",
            "agent_graph_read_attributed_failure_v11",
            "a64ef86c586c2d11ca9a375b1756b3c3|"
                + "d8e46321a9f61246a5dff147bab321f8985552a430042b208c42cbd8b0219d41|"
                + "checked_principal character varying, checked_attempt character, checked_manifest character|"
                + "TABLE(manifest_hash character, revision character varying, session_intent_hash character, session_expires_at timestamp with time zone, cursor_sequence integer, cursor_head_hash character, provider_attribution_1_hash character, provider_attribution_2_hash character, request_hash character, response_hash character, model_resolved character varying, failure_code character varying, provenance_hash character, state character varying, state_version bigint, claimant_id character varying, fence_token_hash character, claim_expires_at timestamp with time zone, live_sequence integer, live_head_hash character)"),
        v11Surfaces(jdbc));

    execute(admin, resource("/db/provisioning/pack010_runtime_roles.sql"));
    execute(admin, resource("/db/provisioning/pack010_runtime_roles_check.sql"));

    assertFunctionPrivilege(
        jdbc, "emergeos_graph_prefix_writer", "record", true);
    assertFunctionPrivilege(
        jdbc, "emergeos_graph_prefix_writer", "read", false);
    assertFunctionPrivilege(
        jdbc, "emergeos_graph_prefix_writer", "claim", false);
    assertFunctionPrivilege(jdbc, "emergeos_graph_executor", "record", false);
    assertFunctionPrivilege(jdbc, "emergeos_graph_executor", "read", false);
    assertFunctionPrivilege(jdbc, "emergeos_graph_executor", "claim", false);
    assertFunctionPrivilege(jdbc, "emergeos_failure_resumer", "record", false);
    assertFunctionPrivilege(jdbc, "emergeos_failure_resumer", "read", false);
    assertFunctionPrivilege(jdbc, "emergeos_failure_resumer", "claim", false);
    for (String verb : List.of("read", "claim", "child", "parent")) {
      assertV12FunctionPrivilege(
          jdbc, "emergeos_failure_resumer", verb, true);
      assertV12FunctionPrivilege(
          jdbc, "emergeos_graph_executor", verb, false);
      assertV12FunctionPrivilege(
          jdbc, "emergeos_graph_prefix_writer", verb, false);
    }
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM pg_catalog.pg_class relation "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = relation.relnamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND pg_catalog.has_table_privilege("
                    + "'emergeos_failure_resumer', relation.oid, "
                    + "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")
            .query(Long.class)
            .single());
    assertEquals(0L, publicExecuteCount(jdbc));
    assertEquals(
        2L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = procedure.pronamespace
                WHERE namespace.nspname = 'public'
                  AND pg_catalog.has_function_privilege(
                    'emergeos_provider_attestor_v16',
                    procedure.oid, 'EXECUTE')
                """)
            .query(Long.class)
            .single());
    assertTrue(
        jdbc.sql(
                """
                SELECT pg_catalog.has_function_privilege(
                  'emergeos_provider_attestor_v16',
                  'public.agent_graph_stage_exact_tx_a_v16(jsonb)',
                  'EXECUTE')
                  AND pg_catalog.has_function_privilege(
                  'emergeos_provider_attestor_v16',
                  'public.agent_graph_commit_exact_tx_a_v16(jsonb)',
                  'EXECUTE')
                  AND NOT pg_catalog.has_function_privilege(
                  'emergeos_provider_attestor_v16',
                  'public.agent_graph_assert_exact_tx_a_overlay_v16()',
                  'EXECUTE')
                """)
            .query(Boolean.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*)
                FROM pg_catalog.pg_class relation
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = relation.relnamespace
                WHERE namespace.nspname = 'public'
                  AND relation.relkind IN ('r', 'p', 'S')
                  AND pg_catalog.has_table_privilege(
                    'emergeos_provider_attestor_v16', relation.oid,
                    'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,'
                    || 'TRIGGER,MAINTAIN')
                """)
            .query(Long.class)
            .single());

    try {
      execute(
          admin,
          "GRANT SELECT ON public."
              + "agent_graph_exact_provider_validations_v16 "
              + "TO emergeos_provider_attestor_v16");
      RuntimeException v16RelationAclDrift =
          assertThrows(
              RuntimeException.class,
              () ->
                  execute(
                      admin,
                      resource(
                          "/db/provisioning/"
                              + "pack010_runtime_roles_check.sql")));
      assertSqlState(v16RelationAclDrift, "55000");
    } finally {
      execute(
          admin,
          "REVOKE SELECT ON public."
              + "agent_graph_exact_provider_validations_v16 "
              + "FROM emergeos_provider_attestor_v16");
    }
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles_check.sql"));

    String prefixPassword = UUID.randomUUID().toString();
    String resumerPassword = UUID.randomUUID().toString();
    jdbc.sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + prefixPassword
                + "'")
        .update();
    jdbc.sql(
            "ALTER ROLE emergeos_failure_resumer PASSWORD '"
                + resumerPassword
                + "'")
        .update();
    RuntimeException prefixClaim =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(
                        roleDataSource(
                            "emergeos_graph_prefix_writer", prefixPassword))
                    .sql(
                        "SELECT public.agent_graph_claim_attributed_failure_v11("
                            + "'p','a','m','h',1,'c','f',1000)")
                    .query(Long.class)
                    .single());
    assertSqlState(prefixClaim, "42501");
    RuntimeException resumerRecord =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(
                        roleDataSource(
                            "emergeos_failure_resumer", resumerPassword))
                    .sql(
                        "SELECT public.agent_graph_record_attributed_failure_v11("
                            + "'p','a','m',13,'h','MODEL_RESPONSE_MALFORMED')")
                    .query(String.class)
                    .single());
    assertSqlState(resumerRecord, "42501");
    RuntimeException resumerRelation =
        assertThrows(
            RuntimeException.class,
            () ->
                JdbcClient.create(
                        roleDataSource(
                            "emergeos_failure_resumer", resumerPassword))
                    .sql(
                        "SELECT count(*) FROM "
                            + "public.agent_graph_attributed_failure_outcomes")
                    .query(Long.class)
                    .single());
    assertSqlState(resumerRelation, "42501");

    V7GraphAttemptSqlSeeder.Seed driftSeed =
        new V7GraphAttemptSqlSeeder(admin)
            .seedTerminalV8("v11-resumer-runtime-drift", 7);
    GraphAttemptManifest driftManifest = driftSeed.manifest();
    PostgresGraphAttemptStore prefixStore =
        new PostgresGraphAttemptStore(
            roleDataSource(
                "emergeos_graph_prefix_writer", prefixPassword));
    GraphAttemptCursor driftCursor =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                prefixStore.findVerified(driftManifest))
            .snapshot()
            .cursor();
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash("v11 drift request 1"),
            driftSeed.profile().pricing().modelRequested());
    Instant sessionExpiry =
        jdbc.sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '5 minutes'")
            .query(Instant.class)
            .single();
    prefixStore.claimProviderSessionIntent(
        driftManifest,
        "r1",
        firstIntent,
        sessionExpiry,
        prefixStore.freezeAuthorityIdentity());
    driftCursor =
        prefixStore.credentialReadStarted(
            driftManifest,
            driftCursor,
            driftManifest.startedAt().plusMillis(7));
    driftCursor =
        prefixStore.clientCreated(
            driftManifest,
            driftCursor,
            driftManifest.startedAt().plusMillis(8));
    driftCursor =
        prefixStore.modelCreated(
            driftManifest,
            driftCursor,
            driftManifest.startedAt().plusMillis(9));
    driftCursor =
        prefixStore.providerIntent(
            driftManifest,
            driftCursor,
            firstIntent,
            driftManifest.startedAt().plusMillis(10));
    GraphProviderAttribution firstAttribution =
        attribution(driftSeed, firstIntent);
    driftCursor =
        prefixStore.providerAttributed(
            driftManifest,
            driftCursor,
            firstAttribution,
            driftManifest.startedAt().plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash("v11 drift request 2"),
            driftSeed.profile().pricing().modelRequested());
    driftCursor =
        prefixStore.providerIntent(
            driftManifest,
            driftCursor,
            secondIntent,
            driftManifest.startedAt().plusMillis(12));
    driftCursor =
        prefixStore.providerFailureAttributed(
            driftManifest,
            driftCursor,
            attribution(driftSeed, secondIntent),
            GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
            driftManifest.startedAt().plusMillis(13));
    assertEquals(14, driftCursor.lastSequence());
    DataSource resumerDataSource =
        roleDataSource("emergeos_failure_resumer", resumerPassword);
    PostgresAttributedFailureResumeStore frozenResume =
        new PostgresAttributedFailureResumeStore(resumerDataSource);
    PostgresAttributedFailureResumeStore.DurableFailureCursor baseline =
        frozenResume.load(driftManifest);
    assertEquals(14, baseline.cursorSequence());
    assertEquals(
        PostgresAttributedFailureResumeStore.State.READY,
        baseline.state());

    execute(admin, "CREATE ROLE emergeos_v11_resumer_drift NOLOGIN");
    try {
      execute(
          admin,
          "GRANT emergeos_v11_resumer_drift "
              + "TO emergeos_failure_resumer");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> frozenResume.load(driftManifest));
      assertThrows(
          GraphAttemptIntegrityException.class,
          () ->
              frozenResume.claim(
                  driftManifest,
                  baseline,
                  "membership-drift",
                  IntegrityHashes.utf8ContentHash("membership-drift"),
                  Duration.ofSeconds(1)));
    } finally {
      execute(
          admin,
          "REVOKE emergeos_v11_resumer_drift "
              + "FROM emergeos_failure_resumer");
      execute(admin, "DROP ROLE emergeos_v11_resumer_drift");
    }
    assertEquals(baseline, frozenResume.load(driftManifest));

    try {
      execute(
          admin,
          "GRANT SELECT ON public."
              + "agent_graph_attributed_failure_outcomes "
              + "TO emergeos_failure_resumer");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> frozenResume.load(driftManifest));
    } finally {
      execute(
          admin,
          "REVOKE SELECT ON public."
              + "agent_graph_attributed_failure_outcomes "
              + "FROM emergeos_failure_resumer");
    }
    assertEquals(baseline, frozenResume.load(driftManifest));

    String recordSignature =
        "public.agent_graph_record_attributed_failure_v11("
            + "character varying,character,character,integer,"
            + "character,character varying)";
    try {
      execute(
          admin,
          "GRANT EXECUTE ON FUNCTION "
              + recordSignature
              + " TO emergeos_failure_resumer");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> frozenResume.load(driftManifest));
    } finally {
      execute(
          admin,
          "REVOKE EXECUTE ON FUNCTION "
              + recordSignature
              + " FROM emergeos_failure_resumer");
    }
    assertEquals(baseline, frozenResume.load(driftManifest));

    String readSignature =
        "public.agent_graph_read_attributed_failure_resume_v12("
            + "character varying,character,character)";
    try {
      execute(
          admin,
          "ALTER FUNCTION "
              + readSignature
              + " OWNER TO emerge");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> frozenResume.load(driftManifest));
    } finally {
      execute(
          admin,
          "ALTER FUNCTION "
              + readSignature
              + " OWNER TO emergeos_pack010_schema_owner");
      execute(admin, resource("/db/provisioning/pack010_runtime_roles.sql"));
    }
    assertEquals(baseline, frozenResume.load(driftManifest));

    String claimSignature =
        "public.agent_graph_claim_attributed_failure_resume_v12("
            + "character varying,character,character,character,bigint,"
            + "character varying,character,integer)";
    String claimDefinition =
        jdbc.sql(
                "SELECT pg_catalog.pg_get_functiondef("
                    + "'"
                    + claimSignature
                    + "'::pg_catalog.regprocedure)")
            .query(String.class)
            .single();
    try {
      execute(
          admin,
          "CREATE OR REPLACE FUNCTION public."
              + "agent_graph_claim_attributed_failure_resume_v12("
              + "checked_principal character varying, "
              + "checked_attempt character, "
              + "checked_manifest character, "
              + "checked_provenance character, "
              + "checked_state_version bigint, "
              + "checked_claimant character varying, "
              + "checked_fence_token_hash character, "
              + "checked_lease_millis integer) RETURNS bigint "
              + "LANGUAGE plpgsql SECURITY DEFINER "
              + "SET search_path = pg_catalog, pg_temp "
              + "AS $drift$ BEGIN RETURN 1; END; $drift$");
      assertThrows(
          GraphAttemptIntegrityException.class,
          () -> frozenResume.load(driftManifest));
    } finally {
      execute(admin, claimDefinition);
    }
    assertEquals(baseline, frozenResume.load(driftManifest));
    new PostgresAttributedFailureResumeStore(resumerDataSource);
    try {
      execute(
          admin,
          "GRANT SELECT ON public.agent_graph_provider_profiles_v14 "
              + "TO emergeos_provider_attestor_v14");
      RuntimeException v14RelationAclDrift =
          assertThrows(
              RuntimeException.class,
              () -> execute(
                  admin,
                  resource(
                      "/db/provisioning/"
                          + "pack010_runtime_roles_check.sql")));
      assertSqlState(v14RelationAclDrift, "55000");
    } finally {
      execute(
          admin,
          "REVOKE SELECT ON public.agent_graph_provider_profiles_v14 "
              + "FROM emergeos_provider_attestor_v14");
    }
    execute(admin, resource("/db/provisioning/pack010_runtime_roles_check.sql"));
    System.out.println(
        "PACK010_V16_FRESH_V12_AUTHORITY_RECEIPT migration=16 "
            + "PUBLIC_EXECUTE=0 prefix=record-only executor=v10-pair-only "
            + "resumer=v12-read+claim+terminal-only resumerRelationACL=0 "
            + "v14ProfileAuthority=ASSERT_ONLY "
            + "v15ExactTxARequirement=GUARD_ONLY "
            + "v16ExactPicoOverlay=STAGE_COMMIT_ONLY "
            + "v14RelationAclDrift=55000 "
            + "v16Attestor=STAGE_COMMIT_ONLY v16RelationAcl=0 "
            + "v16RelationAclDrift=55000 "
            + "runtimeDrift=MEMBERSHIP,RELATION_ACL,EXTRA_EXECUTE,OWNER,BODY "
            + "secrets=ephemeral");
  }

  @Test
  void populatedV10UpgradePreservesHistoricalTruthAndAddsEmptySidecar() {
    DataSource dataSource = schemaDataSource("populated_v10_to_v11");
    Flyway v10 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("10"))
            .load();
    assertEquals(10, v10.migrate().migrationsExecuted);
    new V7GraphAttemptSqlSeeder(dataSource)
        .seedTerminalV8("v11-populated-fidelity");
    JdbcClient jdbc = JdbcClient.create(dataSource);
    Map<String, String> rowsBefore = rowImagesWithXmin(jdbc);
    List<String> historyBefore = migrationHistory(jdbc);

    Flyway v11 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("11"))
            .load();
    assertEquals(1, v11.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("11"),
        v11.info().current().getVersion());
    assertEquals(rowsBefore, rowImagesWithXmin(jdbc));
    assertEquals(
        historyBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("11|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("11|"));
    assertEquals(
        0L,
        jdbc.sql("SELECT count(*) FROM agent_graph_attributed_failure_outcomes")
            .query(Long.class)
            .single());
    assertFalse(rowsBefore.isEmpty());
    System.out.println(
        "PACK010_V10_TO_V11_FIDELITY_RECEIPT populated=true "
            + "rowsAndXmin=UNCHANGED historyV1V10=UNCHANGED sidecar=EMPTY");
  }

  @Test
  void populatedV11ReadyAndClaimedUpgradeThroughV14PreservesTruth() {
    DataSource dataSource = freshDatabaseDataSource("v11_v12_positive");
    Flyway v11 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("11"))
            .load();
    assertEquals(11, v11.migrate().migrationsExecuted);
    FailurePrefix ready =
        seedV11FailurePrefix(dataSource, "v11-v12-ready", true);
    FailurePrefix claimed =
        seedV11FailurePrefix(dataSource, "v11-v12-claimed", true);
    FailurePrefix consumed =
        seedV11FailurePrefix(dataSource, "v11-v12-consumed", true);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    provisionHistoricalV11V10Authority(dataSource);
    claimV11Outcome(dataSource, claimed.seed().manifest());
    claimV11Outcome(dataSource, consumed.seed().manifest());
    assertEquals(
        List.of("CLAIMED|2|2", "READY|1|1"),
        jdbc.sql(
                "SELECT state || '|' || state_version::text || '|' "
                    + "|| count(*)::text FROM "
                    + "agent_graph_attributed_failure_outcomes "
                    + "GROUP BY state, state_version ORDER BY state")
            .query(String.class)
            .list());
    assertEquals(14, ready.cursor().lastSequence());
    assertEquals(14, claimed.cursor().lastSequence());
    assertEquals(14, consumed.cursor().lastSequence());

    Map<String, String> rowsBefore =
        rowImagesWithXmin(jdbc, V11_TRUTH_TABLES);
    List<String> historyBefore = migrationHistory(jdbc);
    Map<String, String> functionsBefore = v11Surfaces(jdbc);

    Flyway v12 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("12"))
            .load();
    assertEquals(1, v12.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("12"),
        v12.info().current().getVersion());
    assertEquals(rowsBefore, rowImagesWithXmin(jdbc, V11_TRUTH_TABLES));
    assertEquals(functionsBefore, v11Surfaces(jdbc));
    assertEquals(
        historyBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("12|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("12|"));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM "
                    + "agent_graph_attributed_failure_terminal_resumes")
            .query(Long.class)
            .single());
    PostgresGraphAttemptStore v12Store =
        new PostgresGraphAttemptStore(dataSource);
    GraphAttemptSnapshot consumedBefore =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                v12Store.findVerified(consumed.seed().manifest()))
            .snapshot();
    String consumedPayload =
        PostgresGraphTerminalPayloads.preCandidateFailureChild(
            consumedBefore, failedChild(consumed));
    consumeClaimedV12ChildInSameTransaction(
        dataSource, consumed.seed().manifest(), consumedPayload);
    assertEquals(
        15,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                v12Store.findVerified(consumed.seed().manifest()))
            .snapshot()
            .cursor()
            .lastSequence());
    assertEquals(
        "CHILD_CONSUMED|3|15",
        jdbc.sql(
                "SELECT state || '|' || state_version::text || '|' "
                    + "|| child_sequence::text FROM "
                    + "agent_graph_attributed_failure_terminal_resumes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", consumed.seed().manifest().principalId())
            .param("attemptId", consumed.seed().manifest().attemptId())
            .query(String.class)
            .single());
    Map<String, String> v12RowsBefore =
        rowImagesWithXmin(jdbc, V12_TRUTH_TABLES);
    List<String> v12HistoryBefore = migrationHistory(jdbc);
    Flyway v13 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("13"))
            .load();
    assertEquals(1, v13.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("13"),
        v13.info().current().getVersion());
    assertEquals(
        v12RowsBefore, rowImagesWithXmin(jdbc, V12_TRUTH_TABLES));
    assertEquals(
        v12HistoryBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("13|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("13|"));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT (SELECT count(*) FROM "
                    + "agent_graph_provider_validation_keys) + "
                    + "(SELECT count(*) FROM "
                    + "agent_graph_provider_validations)")
            .query(Long.class)
            .single());
    jdbc.sql(
            """
            INSERT INTO agent_graph_provider_validation_keys (
              key_id, algorithm, public_key_der, key_fingerprint,
              status, not_before, not_after
            ) VALUES (
              'v13-v14-fidelity-key', 'ED25519',
              pg_catalog.decode(pg_catalog.repeat('01', 32), 'hex'),
              pg_catalog.encode(pg_catalog.sha256(
                pg_catalog.decode(pg_catalog.repeat('01', 32), 'hex')),
                'hex'),
              'ACTIVE', clock_timestamp() - interval '1 minute',
              clock_timestamp() + interval '10 minutes'
            )
            """)
        .update();
    Map<String, String> v13RowsBefore =
        rowImagesWithXmin(jdbc, V13_TRUTH_TABLES);
    List<String> v13HistoryBefore = migrationHistory(jdbc);
    Map<String, String> v13FunctionsBefore =
        functionCatalogImages(jdbc, V13_FUNCTION_SIGNATURES);
    String v13TriggersBefore = triggerCatalogImage(jdbc);
    String v13CanonicalBefore =
        jdbc.sql(
                """
                SELECT btrim(agent_graph_framed_sha256_v13(
                  'emergeos.provider-policy.v13',
                  ARRAY['v13-v14-fidelity', 'sample']))::text
                """)
            .query(String.class)
            .single();
    Flyway v14 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("14"))
            .load();
    assertEquals(1, v14.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("14"),
        v14.info().current().getVersion());
    assertEquals(
        v13RowsBefore, rowImagesWithXmin(jdbc, V13_TRUTH_TABLES));
    assertEquals(v13FunctionsBefore,
        functionCatalogImages(jdbc, V13_FUNCTION_SIGNATURES));
    assertEquals(v13TriggersBefore, triggerCatalogImage(jdbc));
    assertEquals(
        v13CanonicalBefore,
        jdbc.sql(
                """
                SELECT btrim(agent_graph_framed_sha256_v13(
                  'emergeos.provider-policy.v13',
                  ARRAY['v13-v14-fidelity', 'sample']))::text
                """)
            .query(String.class)
            .single());
    assertEquals(
        v13HistoryBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("14|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("14|"));
    assertEquals(
        "0|2",
        jdbc.sql(
                """
                SELECT (SELECT count(*)
                        FROM agent_graph_provider_profiles_v14)::text
                       || '|' ||
                       (SELECT count(*) FROM pg_catalog.pg_proc procedure
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = procedure.pronamespace
                        WHERE namespace.nspname = 'public'
                          AND procedure.proname IN (
                            'agent_graph_framed_sha256_v14',
                            'agent_graph_assert_provider_statement_v14'
                          ))::text
                """)
            .query(String.class)
            .single());
    System.out.println(
        "PACK010_V11_TO_V14_FIDELITY_RECEIPT ready=1 claimed=1 "
            + "childConsumed=1 heads=14,15 "
            + "rowsAndXmin=UNCHANGED historyV1V11=UNCHANGED "
            + "v11Functions=UNCHANGED v12InitialReceipt=EMPTY "
            + "v12ChildReceipt=CHILD_CONSUMED/1 "
            + "historyV1V12=UNCHANGED v13Key=1 "
            + "v13RowsFunctionsTriggersCanonical=UNCHANGED "
            + "v14Profiles=EMPTY v14Functions=2");
  }

  @Test
  void populatedV14UpgradeThroughV16PreservesHistoricalTruthAndClosesAcl() {
    DataSource dataSource =
        freshDatabaseDataSource("v14_v15_populated_fidelity");
    Flyway v14 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("14"))
            .load();
    assertEquals(14, v14.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("14"),
        v14.info().current().getVersion());

    V7GraphAttemptSqlSeeder.Seed seed =
        new V7GraphAttemptSqlSeeder(dataSource)
            .seedTerminalV8("v14-v15-populated-fidelity", 7);
    seedV13RequiredValidationTruth(dataSource, seed);
    GraphAttemptCursor sequence13 =
        advanceToProviderPendingSequence13(dataSource, seed);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    insertActiveV14Profile(jdbc);
    assertEquals(
        "1|1|1",
        jdbc.sql(
                """
                SELECT
                  (SELECT count(*)
                   FROM agent_graph_provider_validation_keys)::text
                  || '|' ||
                  (SELECT count(*)
                   FROM agent_graph_provider_validations)::text
                  || '|' ||
                  (SELECT count(*)
                   FROM agent_graph_provider_profiles_v14
                   WHERE status = 'ACTIVE')::text
                """)
            .query(String.class)
            .single());

    Map<String, String> rowsBefore =
        rowImagesWithXmin(jdbc, V14_TRUTH_TABLES);
    Map<String, String> functionsBefore =
        functionCatalogImages(jdbc, V13_V14_FUNCTION_SIGNATURES);
    String triggersBefore = historicalTriggerCatalogImage(jdbc);
    List<String> historyBefore = migrationHistory(jdbc);
    String v13CanonicalBefore =
        canonicalHash(
            jdbc,
            "agent_graph_framed_sha256_v13",
            "emergeos.provider-policy.v13");
    String v14CanonicalBefore =
        canonicalHash(
            jdbc,
            "agent_graph_framed_sha256_v14",
            "emergeos.provider-profile.v14");

    Flyway v15 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("15"))
            .load();
    assertEquals(1, v15.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("15"),
        v15.info().current().getVersion());
    assertEquals(rowsBefore, rowImagesWithXmin(jdbc, V14_TRUTH_TABLES));
    assertEquals(
        functionsBefore,
        functionCatalogImages(jdbc, V13_V14_FUNCTION_SIGNATURES));
    assertEquals(triggersBefore, historicalTriggerCatalogImage(jdbc));
    assertEquals(
        v13CanonicalBefore,
        canonicalHash(
            jdbc,
            "agent_graph_framed_sha256_v13",
            "emergeos.provider-policy.v13"));
    assertEquals(
        v14CanonicalBefore,
        canonicalHash(
            jdbc,
            "agent_graph_framed_sha256_v14",
            "emergeos.provider-profile.v14"));
    assertEquals(
        historyBefore,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("15|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("15|"));
    ensureManagedRole(
        dataSource, "emergeos_provider_attestor_v15", true);
    String requirementHash =
        insertSyntheticV15Requirement(jdbc, seed, sequence13);
    assertTrue(requirementHash.matches("[0-9a-f]{64}"));
    assertEquals(
        1L,
        jdbc.sql(
                "SELECT count(*) FROM "
                    + "agent_graph_exact_tx_a_requirements_v15")
            .query(Long.class)
            .single());
    assertEquals(2, functionCatalogImages(jdbc, V15_FUNCTION_SIGNATURES).size());
    assertEquals(
        V15_TRIGGER_NAMES,
        jdbc.sql(
                """
                SELECT trigger.tgname
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_proc guard
                  ON guard.oid = trigger.tgfoid
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = guard.pronamespace
                WHERE namespace.nspname = 'public'
                  AND NOT trigger.tgisinternal
                  AND guard.proname =
                    'agent_graph_assert_exact_tx_a_requirement_v15'
                ORDER BY trigger.tgname
                """)
            .query(String.class)
            .list());

    Map<String, String> rowsAtV15 =
        rowImagesWithXmin(jdbc, V15_TRUTH_TABLES);
    Map<String, String> functionsAtV15 =
        functionCatalogImages(jdbc, V13_V15_FUNCTION_SIGNATURES);
    String triggersAtV15 = preV16TriggerCatalogImage(jdbc);
    List<String> historyAtV15 = migrationHistory(jdbc);

    Flyway v16 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("16"))
            .load();
    assertEquals(1, v16.migrate().migrationsExecuted);
    assertEquals(
        MigrationVersion.fromVersion("16"),
        v16.info().current().getVersion());
    assertEquals(rowsAtV15, rowImagesWithXmin(jdbc, V15_TRUTH_TABLES));
    assertEquals(
        functionsAtV15,
        functionCatalogImages(jdbc, V13_V15_FUNCTION_SIGNATURES));
    assertEquals(triggersAtV15, preV16TriggerCatalogImage(jdbc));
    assertEquals(
        historyAtV15,
        migrationHistory(jdbc).stream()
            .filter(row -> !row.startsWith("16|"))
            .toList());
    assertTrue(migrationHistory(jdbc).getLast().startsWith("16|"));
    for (String table : V16_TABLES) {
      assertEquals(
          0L,
          jdbc.sql("SELECT count(*) FROM " + table)
              .query(Long.class)
              .single());
    }
    assertEquals(
        V16_FUNCTION_SIGNATURES.size(),
        functionCatalogImages(jdbc, V16_FUNCTION_SIGNATURES).size());
    assertEquals(
        V16_TRIGGER_NAMES,
        jdbc.sql(
                """
                SELECT trigger.tgname
                FROM pg_catalog.pg_trigger trigger
                JOIN pg_catalog.pg_proc guard
                  ON guard.oid = trigger.tgfoid
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = guard.pronamespace
                WHERE namespace.nspname = 'public'
                  AND NOT trigger.tgisinternal
                  AND guard.proname =
                    'agent_graph_assert_exact_tx_a_overlay_v16'
                ORDER BY trigger.tgname
                """)
            .query(String.class)
            .list());

    execute(dataSource, resource("/db/provisioning/pack010_runtime_roles.sql"));
    execute(
        dataSource,
        resource("/db/provisioning/pack010_runtime_roles_check.sql"));
    assertEquals(
        1L,
        jdbc.sql(
                "SELECT count(*) FROM pg_catalog.pg_proc procedure "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = procedure.pronamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND pg_catalog.has_function_privilege("
                    + "'emergeos_provider_attestor_v15', procedure.oid, "
                    + "'EXECUTE')")
            .query(Long.class)
            .single());
    assertTrue(
        jdbc.sql(
                "SELECT pg_catalog.has_function_privilege("
                    + "'emergeos_provider_attestor_v15', "
                    + "'public.agent_graph_require_exact_tx_a_v15("
                    + "character varying,character,character,"
                    + "character varying)', 'EXECUTE')")
            .query(Boolean.class)
            .single());
    assertFalse(
        jdbc.sql(
                "SELECT pg_catalog.has_function_privilege("
                    + "'emergeos_provider_attestor_v15', "
                    + "'public.agent_graph_assert_exact_tx_a_requirement_v15()', "
                    + "'EXECUTE')")
            .query(Boolean.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM pg_catalog.pg_class relation "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = relation.relnamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND relation.relkind IN ('r', 'p', 'S') "
                    + "AND pg_catalog.has_table_privilege("
                    + "'emergeos_provider_attestor_v15', relation.oid, "
                    + "'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,"
                    + "TRIGGER,MAINTAIN')")
            .query(Long.class)
            .single());

    try {
      execute(
          dataSource,
          "GRANT SELECT ON public."
              + "agent_graph_exact_tx_a_requirements_v15 "
              + "TO emergeos_provider_attestor_v15");
      RuntimeException relationDrift =
          assertThrows(
              RuntimeException.class,
              () ->
                  execute(
                      dataSource,
                      resource(
                          "/db/provisioning/"
                              + "pack010_runtime_roles_check.sql")));
      assertSqlState(relationDrift, "55000");
    } finally {
      execute(
          dataSource,
          "REVOKE SELECT ON public."
              + "agent_graph_exact_tx_a_requirements_v15 "
              + "FROM emergeos_provider_attestor_v15");
    }
    execute(
        dataSource,
        resource("/db/provisioning/pack010_runtime_roles_check.sql"));

    System.out.println(
        "PACK010_V14_TO_V15_POPULATED_FIDELITY_RECEIPT "
            + "v13Key=1 v13Validation=1 v14ActiveProfile=1 "
            + "rowsAndXminV1V14=UNCHANGED "
            + "v13V14FunctionCatalog=UNCHANGED "
            + "oldTriggers=UNCHANGED historyV1V14=UNCHANGED "
            + "marker=REQUIRED/1 v15Functions=2 v15Triggers=4 "
            + "v15Attestor=REQUIRE_ONLY v15RelationAcl=0 "
            + "grantSelectDrift=55000");
    System.out.println(
        "PACK010_V15_TO_V16_POPULATED_FIDELITY_RECEIPT "
            + "syntheticAdminMarker=REQUIRED/1 "
            + "rowsAndXminV1V15=UNCHANGED "
            + "v13V14V15FunctionCatalog=UNCHANGED "
            + "oldTriggers=UNCHANGED historyV1V15=UNCHANGED "
            + "v16OverlayTables=EMPTY v16Functions=3 v16Triggers=4");
  }

  @Test
  void v12UpgradeFailsClosedForConsumedOutcomeAndNoSidecarRawV10Truth() {
    for (int terminalSequence : List.of(15, 17)) {
      assertV12MigrationFailsClosedAfterRawV10Terminal(
          "v11-v12-claimed-raw-v10-" + terminalSequence,
          true,
          terminalSequence);
      assertV12MigrationFailsClosedAfterRawV10Terminal(
          "v11-v12-no-sidecar-raw-v10-" + terminalSequence,
          false,
          terminalSequence);
    }
    System.out.println(
        "PACK010_V11_TO_V12_FAIL_CLOSED_RECEIPT "
            + "claimedRawV10Head15And17=55000 "
            + "noSidecarRawV10Head15And17=55000 "
            + "rowsAndXmin=UNCHANGED history=UNCHANGED version=11");
  }

  @Test
  void v12ReceiptRejectsExpiredChildParentAndTerminalLeaseTruthAtRest() {
    DataSource dataSource = freshDatabaseDataSource("v12_lease_checks");
    assertEquals(
        12,
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("12"))
            .load()
            .migrate()
            .migrationsExecuted);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    String hash = IntegrityHashes.utf8ContentHash("v12-lease-check-hash");
    String otherHash =
        IntegrityHashes.utf8ContentHash("v12-lease-check-other-hash");

    RuntimeException expiredChild =
        assertThrows(
            RuntimeException.class,
            () ->
                jdbc.sql(
                        "INSERT INTO "
                            + "agent_graph_attributed_failure_terminal_resumes ("
                            + "principal_id, attempt_id, manifest_hash, "
                            + "provenance_hash, state, state_version, "
                            + "child_claim_version, child_claimant_id, "
                            + "child_fence_token_hash, child_claim_expires_at, "
                            + "child_claimed_at, child_sequence, child_head_hash, "
                            + "child_terminal_hash, child_applied_at) VALUES ("
                            + "'lease-child', :attempt, :hash, :hash, "
                            + "'CHILD_CONSUMED', 3, 2, 'child', :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:02+00', "
                            + "TIMESTAMPTZ '2026-08-10 00:00:00+00', "
                            + "15, :hash, :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:02+00')")
                    .param("attempt", hash)
                    .param("hash", hash)
                    .update());
    assertCheckConstraint(
        expiredChild, "graph_failure_terminal_resume_child_v12");

    RuntimeException parentBeforeChild =
        assertThrows(
            RuntimeException.class,
            () ->
                jdbc.sql(
                        "INSERT INTO "
                            + "agent_graph_attributed_failure_terminal_resumes ("
                            + "principal_id, attempt_id, manifest_hash, "
                            + "provenance_hash, state, state_version, "
                            + "child_claim_version, child_claimant_id, "
                            + "child_fence_token_hash, child_claim_expires_at, "
                            + "child_claimed_at, child_sequence, child_head_hash, "
                            + "child_terminal_hash, child_applied_at, "
                            + "parent_claim_version, claimant_id, "
                            + "fence_token_hash, claim_expires_at, claimed_at) "
                            + "VALUES ('lease-parent', :attempt, :hash, :hash, "
                            + "'PARENT_CLAIMED', 4, 2, 'child', :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:03+00', "
                            + "TIMESTAMPTZ '2026-08-10 00:00:00+00', "
                            + "15, :hash, :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:02+00', "
                            + "4, 'parent', :otherHash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:04+00', "
                            + "TIMESTAMPTZ '2026-08-10 00:00:01+00')")
                    .param("attempt", otherHash)
                    .param("hash", hash)
                    .param("otherHash", otherHash)
                    .update());
    assertCheckConstraint(
        parentBeforeChild, "graph_failure_terminal_resume_state_v12");

    RuntimeException expiredTerminal =
        assertThrows(
            RuntimeException.class,
            () ->
                jdbc.sql(
                        "INSERT INTO "
                            + "agent_graph_attributed_failure_terminal_resumes ("
                            + "principal_id, attempt_id, manifest_hash, "
                            + "provenance_hash, state, state_version, "
                            + "child_claim_version, child_claimant_id, "
                            + "child_fence_token_hash, child_claim_expires_at, "
                            + "child_claimed_at, child_sequence, child_head_hash, "
                            + "child_terminal_hash, child_applied_at, "
                            + "parent_claim_version, claimant_id, "
                            + "fence_token_hash, claim_expires_at, claimed_at, "
                            + "parent_sequence, parent_head_hash, "
                            + "parent_terminal_hash, terminal_sequence, "
                            + "terminal_head_hash, seal_hash, terminal_applied_at) "
                            + "VALUES ('lease-terminal', :attempt, :hash, :hash, "
                            + "'TERMINAL_CONSUMED', 5, 2, 'child', :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:03+00', "
                            + "TIMESTAMPTZ '2026-08-10 00:00:00+00', "
                            + "15, :hash, :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:01+00', "
                            + "4, 'parent', :otherHash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:04+00', "
                            + "TIMESTAMPTZ '2026-08-10 00:00:02+00', "
                            + "16, :hash, :hash, 17, :hash, :hash, "
                            + "TIMESTAMPTZ '2026-08-10 00:00:04+00')")
                    .param("attempt", hash)
                    .param("hash", hash)
                    .param("otherHash", otherHash)
                    .update());
    assertCheckConstraint(
        expiredTerminal, "graph_failure_terminal_resume_state_v12");
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM "
                    + "agent_graph_attributed_failure_terminal_resumes")
            .query(Long.class)
            .single());
    System.out.println(
        "PACK010_V12_LEASE_CHECK_RECEIPT childExpiry=23514 "
            + "parentBeforeChild=23514 terminalExpiry=23514 rows=0");
  }

  private static void seedV13RequiredValidationTruth(
      DataSource dataSource, V7GraphAttemptSqlSeeder.Seed seed) {
    GraphAttemptManifest manifest = seed.manifest();
    PostgresGraphAttemptStore store =
        new PostgresGraphAttemptStore(dataSource);
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "v14-v15-fidelity-request-1"),
            seed.profile().pricing().modelRequested());
    Instant sessionExpiry =
        JdbcClient.create(dataSource)
            .sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '10 minutes'")
            .query(Instant.class)
            .single();
    store.claimProviderSessionIntent(
        manifest,
        "r1",
        firstIntent,
        sessionExpiry,
        store.freezeAuthorityIdentity());

    JdbcClient jdbc = JdbcClient.create(dataSource);
    jdbc.sql(
            """
            INSERT INTO agent_graph_provider_validation_keys (
              key_id, algorithm, public_key_der, key_fingerprint,
              status, not_before, not_after
            ) VALUES (
              'v14-v15-fidelity-key', 'ED25519',
              pg_catalog.decode(pg_catalog.repeat('02', 32), 'hex'),
              pg_catalog.encode(pg_catalog.sha256(
                pg_catalog.decode(pg_catalog.repeat('02', 32), 'hex')),
                'hex'),
              'ACTIVE', clock_timestamp() - interval '1 minute',
              clock_timestamp() + interval '10 minutes'
            )
            """)
        .update();

    String transportHash =
        IntegrityHashes.utf8ContentHash(
            "v14-v15-fidelity-transport-profile");
    String parserHash =
        IntegrityHashes.utf8ContentHash(
            "v14-v15-fidelity-parser-profile");
    String schemaHash =
        IntegrityHashes.utf8ContentHash(
            "v14-v15-fidelity-schema-profile");
    jdbc.sql(
            """
            WITH authority AS (
              SELECT current_database()::varchar(63) AS database_name,
                     database.oid AS database_oid,
                     namespace.oid AS schema_oid,
                     role.oid AS attestor_role_oid
              FROM pg_catalog.pg_database database
              CROSS JOIN pg_catalog.pg_namespace namespace
              CROSS JOIN pg_catalog.pg_roles role
              WHERE database.datname = current_database()
                AND namespace.nspname = 'public'
                AND role.rolname = current_user
            ), policy_truth AS (
              SELECT attempt.principal_id, attempt.attempt_id,
                     attempt.manifest_hash,
                     intent.revision, intent.intent_hash,
                     intent.expires_at, intent.cursor_sequence,
                     intent.cursor_head_hash,
                     key.key_id, key.key_fingerprint
              FROM agent_graph_attempts attempt
              JOIN agent_graph_provider_session_intents intent
                ON intent.principal_id = attempt.principal_id
               AND intent.attempt_id = attempt.attempt_id
              JOIN agent_graph_provider_validation_keys key
                ON key.key_id = 'v14-v15-fidelity-key'
              WHERE attempt.principal_id = :principalId
                AND attempt.attempt_id = :attemptId
            )
            INSERT INTO agent_graph_provider_validations (
              principal_id, attempt_id, manifest_hash,
              protocol_version, database_name, database_oid,
              schema_oid, attestor_role_oid,
              revision, session_intent_hash, session_expires_at,
              policy_sequence, policy_head_hash,
              key_id, key_fingerprint,
              transport_profile_hash, parser_profile_hash,
              schema_profile_hash, challenge_ttl_millis,
              policy_hash, state, policy_created_at
            )
            SELECT truth.principal_id, truth.attempt_id,
                   truth.manifest_hash,
                   'POSTGRES_ROLE_V1', authority.database_name,
                   authority.database_oid, authority.schema_oid,
                   authority.attestor_role_oid,
                   truth.revision, truth.intent_hash,
                   truth.expires_at, truth.cursor_sequence,
                   truth.cursor_head_hash,
                   truth.key_id, truth.key_fingerprint,
                   :transportHash, :parserHash, :schemaHash, 1000,
                   public.agent_graph_framed_sha256_v13(
                     'emergeos.provider-validation-policy.v1',
                     ARRAY[
                       authority.database_name::text,
                       authority.database_oid::text,
                       authority.schema_oid::text,
                       authority.attestor_role_oid::text,
                       truth.principal_id::text,
                       truth.attempt_id::text,
                       truth.manifest_hash::text,
                       truth.revision::text,
                       truth.intent_hash::text,
                       ((extract(epoch FROM truth.expires_at)
                         * 1000000)::bigint)::text,
                       truth.cursor_sequence::text,
                       truth.cursor_head_hash::text,
                       truth.key_id::text,
                       truth.key_fingerprint::text,
                       CAST(:transportHash AS text),
                       CAST(:parserHash AS text),
                       CAST(:schemaHash AS text)
                     ]),
                   'REQUIRED', clock_timestamp()
            FROM policy_truth truth
            CROSS JOIN authority
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("transportHash", transportHash)
        .param("parserHash", parserHash)
        .param("schemaHash", schemaHash)
        .update();
  }

  private static GraphAttemptCursor advanceToProviderPendingSequence13(
      DataSource dataSource, V7GraphAttemptSqlSeeder.Seed seed) {
    GraphAttemptManifest manifest = seed.manifest();
    PostgresGraphAttemptStore store =
        new PostgresGraphAttemptStore(dataSource);
    GraphAttemptCursor cursor =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store.findVerified(manifest))
            .snapshot()
            .cursor();
    assertEquals(7, cursor.lastSequence());
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "v14-v15-fidelity-request-1"),
            seed.profile().pricing().modelRequested());
    cursor =
        store.credentialReadStarted(
            manifest, cursor, manifest.startedAt().plusMillis(7));
    cursor =
        store.clientCreated(
            manifest, cursor, manifest.startedAt().plusMillis(8));
    cursor =
        store.modelCreated(
            manifest, cursor, manifest.startedAt().plusMillis(9));
    cursor =
        store.providerIntent(
            manifest,
            cursor,
            firstIntent,
            manifest.startedAt().plusMillis(10));
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            attribution(seed, firstIntent),
            manifest.startedAt().plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash(
                "v15-v16-fidelity-request-2"),
            seed.profile().pricing().modelRequested());
    cursor =
        store.providerIntent(
            manifest,
            cursor,
            secondIntent,
            manifest.startedAt().plusMillis(12));
    assertEquals(13, cursor.lastSequence());
    return cursor;
  }

  private static String insertSyntheticV15Requirement(
      JdbcClient jdbc,
      V7GraphAttemptSqlSeeder.Seed seed,
      GraphAttemptCursor sequence13) {
    GraphAttemptManifest manifest = seed.manifest();
    return jdbc.sql(
            """
            INSERT INTO agent_graph_exact_tx_a_requirements_v15 (
              principal_id, attempt_id, manifest_hash,
              protocol_version, database_name, database_oid,
              schema_oid, attestor_role_oid,
              base_sequence, base_head_hash, execution_binding_hash,
              request_ordinal, provider_profile_id,
              provider_profile_hash, state, required_at
            )
            SELECT :principalId, :attemptId, :manifestHash,
                   'PICO_OVERLAY_V1', current_database(),
                   database.oid, namespace.oid, role.oid,
                   13, :baseHeadHash, :manifestHash,
                   2, profile.profile_id, profile.profile_hash,
                   'REQUIRED', clock_timestamp()
            FROM pg_catalog.pg_database database
            CROSS JOIN pg_catalog.pg_namespace namespace
            CROSS JOIN pg_catalog.pg_roles role
            CROSS JOIN agent_graph_provider_profiles_v14 profile
            WHERE database.datname = current_database()
              AND namespace.nspname = 'public'
              AND role.rolname = 'emergeos_provider_attestor_v15'
              AND profile.profile_id =
                    'v14-v15-fidelity-profile-v1'
            RETURNING btrim(requirement_hash)::text
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("baseHeadHash", sequence13.headHash())
        .query(String.class)
        .single();
  }

  private static void insertActiveV14Profile(JdbcClient jdbc) {
    String transportProfileId = "deepseek-responses-http-v1";
    String parserProfileId = "deepseek-responses-parser-v1";
    String schemaProfileId = "deepseek-structured-final-v1";
    String modelProfileId = "deepseek-v4-flash-effort-none-v1";
    jdbc.sql(
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
              'v14-v15-fidelity-profile-v1',
              'deepseek', 'deepseek.responses',
              :transportProfileId, :transportProfileHash,
              :parserProfileId, :parserProfileHash,
              :schemaProfileId, :schemaProfileHash,
              :modelProfileId, :modelProfileHash,
              'deepseek-v4-flash', :modelResolutionProfileHash,
              'deepseek-v4-flash-public-list-2026-08-11',
              'deepseek', :pricingProfileFingerprint,
              :pricingSourceHash,
              floor(extract(epoch from clock_timestamp()
                - interval '1 minute') * 1000000)::bigint,
              floor(extract(epoch from clock_timestamp()
                + interval '10 minutes') * 1000000)::bigint,
              'PICO_USD_PER_TOKEN', 140000, 2800, 280000,
              'NONE', 'ACTIVE'
            )
            """)
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
        .update();
  }

  private static String canonicalHash(
      JdbcClient jdbc, String functionName, String domain) {
    if (!List.of(
            "agent_graph_framed_sha256_v13",
            "agent_graph_framed_sha256_v14")
        .contains(functionName)) {
      throw new IllegalArgumentException("unexpected canonical helper");
    }
    return jdbc.sql(
            "SELECT btrim(public."
                + functionName
                + "(:domain, ARRAY['v14-v15-fidelity', 'sample']))::text")
        .param("domain", domain)
        .query(String.class)
        .single();
  }

  private static FailurePrefix seedV11FailurePrefix(
      DataSource dataSource, String suffix, boolean recordOutcome) {
    V7GraphAttemptSqlSeeder.Seed seed =
        new V7GraphAttemptSqlSeeder(dataSource)
            .seedTerminalV8(suffix, 7);
    GraphAttemptManifest manifest = seed.manifest();
    PostgresGraphAttemptStore store =
        new PostgresGraphAttemptStore(dataSource);
    GraphAttemptCursor cursor =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store.findVerified(manifest))
            .snapshot()
            .cursor();
    GraphProviderIntent firstIntent =
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(suffix + ":request-1"),
            seed.profile().pricing().modelRequested());
    Instant sessionExpiry =
        JdbcClient.create(dataSource)
            .sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '5 minutes'")
            .query(Instant.class)
            .single();
    store.claimProviderSessionIntent(
        manifest,
        "r1",
        firstIntent,
        sessionExpiry,
        store.freezeAuthorityIdentity());
    cursor =
        store.credentialReadStarted(
            manifest, cursor, manifest.startedAt().plusMillis(7));
    cursor =
        store.clientCreated(
            manifest, cursor, manifest.startedAt().plusMillis(8));
    cursor =
        store.modelCreated(
            manifest, cursor, manifest.startedAt().plusMillis(9));
    cursor =
        store.providerIntent(
            manifest,
            cursor,
            firstIntent,
            manifest.startedAt().plusMillis(10));
    GraphProviderAttribution first = attribution(seed, firstIntent);
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            first,
            manifest.startedAt().plusMillis(11));
    GraphProviderIntent secondIntent =
        new GraphProviderIntent(
            2,
            IntegrityHashes.utf8ContentHash(suffix + ":request-2"),
            seed.profile().pricing().modelRequested());
    cursor =
        store.providerIntent(
            manifest,
            cursor,
            secondIntent,
            manifest.startedAt().plusMillis(12));
    GraphAttemptCursor previous = cursor;
    GraphProviderAttribution second = attribution(seed, secondIntent);
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            second,
            manifest.startedAt().plusMillis(13));
    if (recordOutcome) {
      recordV11Outcome(dataSource, manifest, previous);
    }
    return new FailurePrefix(seed, cursor, List.of(first, second));
  }

  private static void recordV11Outcome(
      DataSource dataSource,
      GraphAttemptManifest manifest,
      GraphAttemptCursor previous) {
    execute(
        dataSource,
        "DO $role$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles "
            + "WHERE rolname = 'emergeos_graph_prefix_writer') THEN "
            + "CREATE ROLE emergeos_graph_prefix_writer LOGIN NOINHERIT "
            + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION "
            + "NOBYPASSRLS; "
            + "END IF; END $role$");
    execute(
        dataSource,
        "GRANT USAGE ON SCHEMA public TO emergeos_graph_prefix_writer");
    execute(
        dataSource,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_record_attributed_failure_v11("
            + "character varying,character,character,integer,character,"
            + "character varying) TO emergeos_graph_prefix_writer");
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "SET SESSION AUTHORIZATION emergeos_graph_prefix_writer");
        }
        String provenance;
        try (var statement =
            connection.prepareStatement(
                "SELECT public.agent_graph_record_attributed_failure_v11("
                    + "?, ?, ?, ?, ?, ?)")) {
          statement.setString(1, manifest.principalId());
          statement.setString(2, manifest.attemptId());
          statement.setString(3, manifest.manifestHash());
          statement.setInt(4, previous.lastSequence());
          statement.setString(5, previous.headHash());
          statement.setString(
              6, GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED.name());
          try (var result = statement.executeQuery()) {
            if (!result.next()) {
              throw new IllegalStateException("missing V11 outcome receipt");
            }
            provenance = result.getString(1);
          }
        }
        try (var statement = connection.createStatement()) {
          statement.execute("RESET SESSION AUTHORIZATION");
        }
        connection.commit();
        if (provenance == null || !provenance.matches("[0-9a-f]{64}")) {
          throw new IllegalStateException("invalid V11 outcome receipt");
        }
      } catch (RuntimeException | java.sql.SQLException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void provisionHistoricalV11V10Authority(
      DataSource dataSource) {
    ensureManagedRole(
        dataSource, "emergeos_pack010_schema_owner", false);
    ensureManagedRole(dataSource, "emergeos_terminal_owner", false);
    ensureManagedRole(dataSource, "emergeos_graph_executor", true);
    ensureManagedRole(dataSource, "emergeos_failure_resumer", true);
    ensureManagedRole(dataSource, "emergeos_graph_prefix_writer", true);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    String database =
        jdbc.sql("SELECT current_database()")
            .query(String.class)
            .single();
    if (!database.matches("[a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("unsafe fixture database");
    }
    List<String> managed =
        List.of(
            "emergeos_pack010_schema_owner",
            "emergeos_terminal_owner",
            "emergeos_graph_executor",
            "emergeos_failure_resumer",
            "emergeos_graph_prefix_writer");
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM pg_catalog.pg_auth_members membership "
                    + "JOIN pg_catalog.pg_roles granted "
                    + "ON granted.oid = membership.roleid "
                    + "JOIN pg_catalog.pg_roles member "
                    + "ON member.oid = membership.member "
                    + "WHERE granted.rolname IN (:roles) "
                    + "OR member.rolname IN (:roles)")
            .param("roles", managed)
            .query(Long.class)
            .single());

    execute(
        dataSource,
        "REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE "
            + database
            + " FROM PUBLIC");
    for (String role : managed) {
      execute(
          dataSource,
          "REVOKE ALL ON DATABASE " + database + " FROM " + role);
      execute(
          dataSource,
          "REVOKE ALL ON SCHEMA public FROM " + role);
      execute(
          dataSource,
          "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM " + role);
      execute(
          dataSource,
          "REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM " + role);
      execute(
          dataSource,
          "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM " + role);
    }
    execute(dataSource, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
    execute(
        dataSource,
        "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC");
    execute(
        dataSource,
        "REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC");
    execute(
        dataSource,
        "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");

    List<String> relationTransfers =
        jdbc.sql(
                "SELECT CASE relation.relkind WHEN 'S' THEN "
                    + "'ALTER SEQUENCE ' ELSE 'ALTER TABLE ' END "
                    + "|| relation.oid::regclass::text || ' OWNER TO "
                    + "emergeos_pack010_schema_owner' "
                    + "FROM pg_catalog.pg_class relation "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = relation.relnamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND relation.relkind IN ('r','p','S') "
                    + "ORDER BY CASE relation.relkind WHEN 'S' THEN 1 ELSE 0 END, "
                    + "relation.relname")
            .query(String.class)
            .list();
    relationTransfers.forEach(statement -> execute(dataSource, statement));
    List<String> functionTransfers =
        jdbc.sql(
                "SELECT 'ALTER FUNCTION ' "
                    + "|| procedure.oid::regprocedure::text "
                    + "|| ' OWNER TO emergeos_pack010_schema_owner' "
                    + "FROM pg_catalog.pg_proc procedure "
                    + "JOIN pg_catalog.pg_namespace namespace "
                    + "ON namespace.oid = procedure.pronamespace "
                    + "WHERE namespace.nspname = 'public' "
                    + "AND procedure.proname NOT IN ("
                    + "'agent_graph_complete_child_v9', "
                    + "'agent_graph_complete_child_v10', "
                    + "'agent_graph_complete_parent_and_seal_v9', "
                    + "'agent_graph_complete_parent_and_seal_v10') "
                    + "ORDER BY procedure.proname")
            .query(String.class)
            .list();
    functionTransfers.forEach(statement -> execute(dataSource, statement));
    for (String signature :
        List.of(
            "public.agent_graph_complete_child_v9(jsonb)",
            "public.agent_graph_complete_child_v10(jsonb)",
            "public.agent_graph_complete_parent_and_seal_v9(jsonb)",
            "public.agent_graph_complete_parent_and_seal_v10(jsonb)")) {
      execute(
          dataSource,
          "ALTER FUNCTION "
              + signature
              + " OWNER TO emergeos_terminal_owner");
    }

    execute(
        dataSource,
        "GRANT CONNECT ON DATABASE "
            + database
            + " TO emergeos_graph_executor, emergeos_failure_resumer, "
            + "emergeos_graph_prefix_writer");
    execute(
        dataSource,
        "GRANT USAGE ON SCHEMA public TO "
            + "emergeos_pack010_schema_owner, emergeos_terminal_owner, "
            + "emergeos_graph_executor, emergeos_failure_resumer, "
            + "emergeos_graph_prefix_writer");
    execute(
        dataSource,
        "GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public "
            + "TO emergeos_terminal_owner");
    execute(
        dataSource,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_run_selector_guard_v10(), "
            + "public.agent_graph_require_row_shape_v9(jsonb, regclass, boolean), "
            + "public.agent_graph_require_executor_v9(regprocedure), "
            + "public.agent_graph_require_executor_v10(regprocedure), "
            + "public.agent_assert_graph_attempt_v7(varchar, char), "
            + "public.agent_assert_graph_attempt_v8(varchar, char), "
            + "public.agent_assert_worker_graph_v6(varchar, varchar), "
            + "public.agent_graph_terminal_run_valid_v8(varchar, char, varchar), "
            + "public.agent_graph_utf16_length_v8(text), "
            + "public.agent_graph_authorize_terminal_v8("
            + "varchar, char, varchar, varchar) "
            + "TO emergeos_terminal_owner");
    execute(
        dataSource,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_complete_child_v10(jsonb), "
            + "public.agent_graph_complete_parent_and_seal_v10(jsonb) "
            + "TO emergeos_graph_executor");
    execute(
        dataSource,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_record_attributed_failure_v11("
            + "varchar, char, char, integer, char, varchar) "
            + "TO emergeos_graph_prefix_writer");
    execute(
        dataSource,
        "GRANT EXECUTE ON FUNCTION "
            + "public.agent_graph_read_attributed_failure_v11("
            + "varchar, char, char), "
            + "public.agent_graph_claim_attributed_failure_v11("
            + "varchar, char, char, char, bigint, varchar, char, integer) "
            + "TO emergeos_failure_resumer");
  }

  private static void ensureManagedRole(
      DataSource dataSource, String role, boolean login) {
    if (!role.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe fixture role");
    }
    JdbcClient jdbc = JdbcClient.create(dataSource);
    boolean exists =
        jdbc.sql(
                "SELECT count(*) = 1 FROM pg_catalog.pg_roles "
                    + "WHERE rolname = :role")
            .param("role", role)
            .query(Boolean.class)
            .single();
    if (!exists) {
      execute(
          dataSource,
          "CREATE ROLE "
              + role
              + (login ? " LOGIN" : " NOLOGIN")
              + " NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
              + "NOREPLICATION NOBYPASSRLS");
    }
    assertEquals(
        login + "|false|false|false|false|false|false",
        jdbc.sql(
                "SELECT rolcanlogin::text || '|' || rolinherit::text "
                    + "|| '|' || rolsuper::text || '|' || rolcreatedb::text "
                    + "|| '|' || rolcreaterole::text || '|' "
                    + "|| rolreplication::text || '|' || rolbypassrls::text "
                    + "FROM pg_catalog.pg_roles WHERE rolname = :role")
            .param("role", role)
            .query(String.class)
            .single());
  }

  private static void claimV11Outcome(
      DataSource dataSource, GraphAttemptManifest manifest) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    String provenance =
        jdbc.sql(
                "SELECT provenance_hash FROM "
                    + "agent_graph_attributed_failure_outcomes "
                    + "WHERE principal_id = :principalId "
                    + "AND attempt_id = :attemptId")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(String.class)
            .single();
    String fence =
        IntegrityHashes.utf8ContentHash(
            "v11-migration-claim:" + manifest.attemptId());
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "SET SESSION AUTHORIZATION emergeos_failure_resumer");
        }
        long version;
        try (var statement =
            connection.prepareStatement(
                "SELECT public.agent_graph_claim_attributed_failure_v11("
                    + "?, ?, ?, ?, ?, ?, ?, ?)")) {
          statement.setString(1, manifest.principalId());
          statement.setString(2, manifest.attemptId());
          statement.setString(3, manifest.manifestHash());
          statement.setString(4, provenance);
          statement.setLong(5, 1L);
          statement.setString(6, "migration-claimant");
          statement.setString(7, fence);
          statement.setInt(8, 30_000);
          try (var result = statement.executeQuery()) {
            if (!result.next()) {
              throw new IllegalStateException("missing V11 claim receipt");
            }
            version = result.getLong(1);
          }
        }
        try (var statement = connection.createStatement()) {
          statement.execute("RESET SESSION AUTHORIZATION");
        }
        connection.commit();
        if (version != 2L) {
          throw new IllegalStateException("invalid V11 claim receipt");
        }
      } catch (RuntimeException | java.sql.SQLException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String callRawV10Semantic(
      DataSource dataSource, String function, String payload) {
    if (!function.equals("agent_graph_complete_child_v10")
        && !function.equals("agent_graph_complete_parent_and_seal_v10")) {
      throw new IllegalArgumentException("unknown V10 semantic function");
    }
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "SET SESSION AUTHORIZATION emergeos_graph_executor");
        }
        String receipt;
        try (var statement =
            connection.prepareStatement(
                "SELECT public." + function + "(CAST(? AS jsonb))::text")) {
          statement.setString(1, payload);
          try (var result = statement.executeQuery()) {
            if (!result.next()) {
              throw new IllegalStateException("missing raw V10 receipt");
            }
            receipt = result.getString(1);
          }
        }
        try (var statement = connection.createStatement()) {
          statement.execute("RESET SESSION AUTHORIZATION");
        }
        connection.commit();
        if (receipt == null || receipt.isBlank()) {
          throw new IllegalStateException("invalid raw V10 receipt");
        }
        return receipt;
      } catch (RuntimeException | java.sql.SQLException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void consumeClaimedV12ChildInSameTransaction(
      DataSource dataSource,
      GraphAttemptManifest manifest,
      String payload) {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement =
            connection.prepareStatement(
                "WITH input(payload) AS ("
                    + "SELECT CAST(? AS jsonb)) "
                    + "INSERT INTO public."
                    + "agent_graph_attributed_failure_terminal_resumes ("
                    + "principal_id, attempt_id, manifest_hash, "
                    + "provenance_hash, state, state_version, "
                    + "child_claim_version, child_claimant_id, "
                    + "child_fence_token_hash, child_claim_expires_at, "
                    + "child_claimed_at, child_sequence, child_head_hash, "
                    + "child_terminal_hash, child_applied_at) "
                    + "SELECT outcome.principal_id, outcome.attempt_id, "
                    + "outcome.manifest_hash, outcome.provenance_hash, "
                    + "'CHILD_CONSUMED', outcome.state_version + 1, "
                    + "outcome.state_version, outcome.claimant_id, "
                    + "outcome.fence_token_hash, outcome.claim_expires_at, "
                    + "outcome.claimed_at, 15, "
                    + "input.payload #>> '{event,current_head_hash}', "
                    + "input.payload #>> '{terminal_binding,terminal_hash}', "
                    + "pg_catalog.clock_timestamp() "
                    + "FROM public.agent_graph_attributed_failure_outcomes "
                    + "outcome CROSS JOIN input "
                    + "WHERE outcome.principal_id = ? "
                    + "AND outcome.attempt_id = ? "
                    + "AND outcome.manifest_hash = ? "
                    + "AND outcome.state = 'CLAIMED' "
                    + "AND outcome.state_version = 2")) {
          statement.setString(1, payload);
          statement.setString(2, manifest.principalId());
          statement.setString(3, manifest.attemptId());
          statement.setString(4, manifest.manifestHash());
          if (statement.executeUpdate() != 1) {
            throw new IllegalStateException(
                "V12_RECEIPT_PREINSERT_FAILED");
          }
        }
        try (var statement = connection.createStatement()) {
          statement.execute(
              "SET SESSION AUTHORIZATION emergeos_graph_executor");
        }
        try (var statement =
            connection.prepareStatement(
                "SELECT public.agent_graph_complete_child_v10("
                    + "CAST(? AS jsonb))::text")) {
          statement.setString(1, payload);
          try (var result = statement.executeQuery()) {
            if (!result.next() || result.getString(1) == null) {
              throw new IllegalStateException(
                  "V12_RAW_CHILD_RECEIPT_MISSING");
            }
          }
        }
        try (var statement = connection.createStatement()) {
          statement.execute("RESET SESSION AUTHORIZATION");
        }
        connection.commit();
      } catch (RuntimeException | java.sql.SQLException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(
          "V12_CHILD_CONSUME_FIXTURE_FAILED");
    }
  }

  private static void assertV12MigrationFailsClosedAfterRawV10Terminal(
      String suffix, boolean withOutcome, int terminalSequence) {
    if (terminalSequence != 15 && terminalSequence != 17) {
      throw new IllegalArgumentException("terminal sequence must be 15 or 17");
    }
    DataSource dataSource = freshDatabaseDataSource(suffix);
    Flyway v11 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("11"))
            .load();
    assertEquals(11, v11.migrate().migrationsExecuted);
    FailurePrefix prefix =
        seedV11FailurePrefix(dataSource, suffix, withOutcome);
    PostgresGraphAttemptStore store =
        new PostgresGraphAttemptStore(dataSource);
    GraphAttemptSnapshot before =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store.findVerified(prefix.seed().manifest()))
            .snapshot();
    AgentRun terminalChild = failedChild(prefix);
    String childPayload =
        PostgresGraphTerminalPayloads.preCandidateFailureChild(
            before, terminalChild);
    provisionHistoricalV11V10Authority(dataSource);
    if (withOutcome) {
      claimV11Outcome(dataSource, prefix.seed().manifest());
    }
    assertEquals(
        14,
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store.findVerified(prefix.seed().manifest()))
            .snapshot()
            .cursor()
            .lastSequence());
    callRawV10Semantic(
        dataSource, "agent_graph_complete_child_v10", childPayload);
    assertEquals(14, before.cursor().lastSequence());
    GraphAttemptSnapshot terminalSnapshot =
        assertInstanceOf(
                GraphAttemptVerification.Valid.class,
                store.findVerified(prefix.seed().manifest()))
            .snapshot();
    assertEquals(15, terminalSnapshot.cursor().lastSequence());
    if (terminalSequence == 17) {
      AgentRun terminalParent = failedParent(prefix, terminalSnapshot);
      String parentPayload =
          PostgresGraphTerminalPayloads
              .preCandidateFailureParentAndSeal(
                  terminalSnapshot, terminalParent);
      callRawV10Semantic(
          dataSource,
          "agent_graph_complete_parent_and_seal_v10",
          parentPayload);
      terminalSnapshot =
          assertInstanceOf(
                  GraphAttemptVerification.Valid.class,
                  store.findVerified(prefix.seed().manifest()))
              .snapshot();
    }
    assertEquals(terminalSequence, terminalSnapshot.cursor().lastSequence());
    if (terminalSequence == 17) {
      assertTrue(terminalSnapshot.terminalSealPresent());
      assertEquals(GraphAttemptOutcome.FAILED, terminalSnapshot.outcome());
      assertEquals(2, terminalSnapshot.terminalBindings().size());
      assertEquals(RunStatus.FAILED, terminalSnapshot.parentRun().result().status());
    }
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        withOutcome ? 1L : 0L,
        jdbc.sql(
                "SELECT count(*) FROM "
                    + "agent_graph_attributed_failure_outcomes")
            .query(Long.class)
            .single());
    if (withOutcome) {
      assertEquals(
          "CLAIMED|2|14",
          jdbc.sql(
                  "SELECT state || '|' || state_version::text || '|' "
                      + "|| cursor_sequence::text FROM "
                      + "agent_graph_attributed_failure_outcomes "
                      + "WHERE principal_id = :principalId "
                      + "AND attempt_id = :attemptId")
              .param("principalId", prefix.seed().manifest().principalId())
              .param("attemptId", prefix.seed().manifest().attemptId())
              .query(String.class)
              .single());
    }
    assertEquals(
        GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED.name(),
        jdbc.sql(
                "SELECT failure_attribution FROM agent_runs "
                    + "WHERE principal_id = :principalId "
                    + "AND run_id = :runId")
            .param("principalId", prefix.seed().manifest().principalId())
            .param("runId", prefix.seed().child().runId())
            .query(String.class)
            .single());

    Map<String, String> rowsBefore =
        rowImagesWithXmin(jdbc, V11_TRUTH_TABLES);
    List<String> historyBefore = migrationHistory(jdbc);
    String executorHelperBefore =
        functionCatalogImage(
            jdbc,
            "public.agent_graph_require_executor_v10(regprocedure)");
    assertEquals(
        0L,
        relationCount(
            jdbc, "agent_graph_attributed_failure_terminal_resumes"));
    assertEquals(0L, v12NewFunctionCount(jdbc));
    Flyway v12 =
        Flyway.configure()
            .dataSource(dataSource)
            .target(MigrationVersion.fromVersion("12"))
            .load();
    RuntimeException failure =
        assertThrows(RuntimeException.class, v12::migrate);
    assertSqlState(failure, "55000");
    assertRootMessageContains(
        failure, "V12 cannot attest an already-consumed V11 failure");
    assertEquals(
        MigrationVersion.fromVersion("11"),
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .info()
            .current()
            .getVersion());
    assertEquals(rowsBefore, rowImagesWithXmin(jdbc, V11_TRUTH_TABLES));
    assertEquals(historyBefore, migrationHistory(jdbc));
    assertEquals(
        executorHelperBefore,
        functionCatalogImage(
            jdbc,
            "public.agent_graph_require_executor_v10(regprocedure)"));
    assertEquals(
        0L,
        relationCount(
            jdbc, "agent_graph_attributed_failure_terminal_resumes"));
    assertEquals(
        0L,
        v12NewFunctionCount(jdbc));
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT count(*) FROM pg_catalog.pg_trigger "
                    + "WHERE tgname IN ("
                    + "'agent_graph_heads_failure_resume_assert_v12', "
                    + "'agent_graph_failure_resume_assert_v12')")
            .query(Long.class)
            .single());
  }

  private static AgentRun failedChild(FailurePrefix prefix) {
    V7GraphAttemptSqlSeeder.Seed seed = prefix.seed();
    TaskEnvelope task = seed.child().task();
    String runId = seed.child().runId();
    AgentTraceEntry failedStep =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "FAILED",
            "task://" + task.id(),
            IntegrityHashes.emptyTraceRoot());
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), List.of(failedStep));
    BigDecimal cost =
        prefix.attributions().stream()
            .map(GraphProviderAttribution::observedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long tokens =
        prefix.attributions().stream()
            .mapToLong(GraphProviderAttribution::totalTokens)
            .sum();
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    String failureCode =
        GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED.name();
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            prefix.attributions().getLast().modelResolved(),
            seed.profile().agentVersion(),
            seed.profile().verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            failureCode);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            seed.profile().experiment(),
            result.resolvedModel(),
            seed.profile().harnessVersion(),
            seed.profile().componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            failureCode,
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    return new AgentRun(
        runId,
        seed.manifest().principalId(),
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        seed.child().startedAt(),
        seed.manifest().startedAt().plusMillis(14));
  }

  private static AgentRun failedParent(
      FailurePrefix prefix, GraphAttemptSnapshot sequence15) {
    V7GraphAttemptSqlSeeder.Seed seed = prefix.seed();
    TaskEnvelope task = sequence15.parentRun().task();
    String runId = sequence15.parentRun().runId();
    String childRef = "agent-run://" + sequence15.childRun().runId();
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_REQUEST,
            null,
            "REQUESTED",
            childRef,
            root);
    appendTrace(
        events,
        TraceEventType.HANDOFF_REJECTED,
        null,
        "CHILD_FAILED",
        childRef,
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create("1.0", runId, task.id(), events);
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(seed.profile());
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "fake-pack010-conductor-v1",
            parentProfile.agentVersion(),
            parentProfile.verifierVersion(),
            sequence15.childRun().result().costUsd(),
            sequence15.childRun().result().tokenCount(),
            2,
            "/api/v1/agent-runs/" + runId + "/trace",
            "HANDOFF_CHILD_FAILED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            seed.profile().experiment(),
            result.resolvedModel(),
            parentProfile.harnessVersion(),
            parentProfile.componentVersions(seed.profile()),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    sequence15.childRun().bundle().integrityHash())),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    return new AgentRun(
        runId,
        seed.manifest().principalId(),
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        sequence15.parentRun().startedAt(),
        seed.manifest().startedAt().plusMillis(15));
  }

  private static String appendTrace(
      List<AgentTraceEntry> events,
      TraceEventType type,
      String toolName,
      String status,
      String reference,
      String previousRoot) {
    AgentTraceEntry entry =
        AgentTraceEntry.create(
            events.size() + 1,
            type,
            toolName,
            status,
            reference,
            previousRoot);
    events.add(entry);
    return IntegrityHashes.nextTraceRoot(
        entry.previousRootHash(), entry.eventHash());
  }

  private record FailurePrefix(
      V7GraphAttemptSqlSeeder.Seed seed,
      GraphAttemptCursor cursor,
      List<GraphProviderAttribution> attributions) {}

  private static GraphProviderAttribution attribution(
      V7GraphAttemptSqlSeeder.Seed seed,
      GraphProviderIntent intent) {
    var pricing = seed.profile().pricing();
    return GraphProviderAttribution.create(
        intent.requestOrdinal(),
        intent.requestHash(),
        IntegrityHashes.utf8ContentHash(
            "v11 drift response " + intent.requestOrdinal()),
        seed.manifest().childActor(),
        pricing.modelRequested(),
        pricing.modelRequested(),
        pricing.graphSnapshot(),
        1,
        0,
        1,
        0,
        2,
        pricing.actualCostUsd(1, 0, 1));
  }

  private static long publicExecuteCount(JdbcClient jdbc) {
    return publicExecuteCount(jdbc, V11_FUNCTIONS);
  }

  private static long publicExecuteCount(
      JdbcClient jdbc, List<String> functionNames) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = procedure.pronamespace "
                + "CROSS JOIN LATERAL pg_catalog.aclexplode("
                + "COALESCE(procedure.proacl, "
                + "pg_catalog.acldefault('f', procedure.proowner))) acl "
                + "WHERE namespace.nspname = 'public' "
                + "AND procedure.proname IN (:names) "
                + "AND acl.grantee = 0 "
                + "AND acl.privilege_type = 'EXECUTE'")
        .param("names", functionNames)
        .query(Long.class)
        .single();
  }

  private static Map<String, String> v11Surfaces(JdbcClient jdbc) {
    return jdbc.sql(
            "SELECT procedure.proname, pg_catalog.md5(procedure.prosrc) "
                + "|| '|' || pg_catalog.encode(pg_catalog.sha256("
                + "pg_catalog.convert_to(procedure.prosrc, 'UTF8')), 'hex') "
                + "|| '|' || pg_catalog.pg_get_function_identity_arguments("
                + "procedure.oid) || '|' "
                + "|| pg_catalog.pg_get_function_result(procedure.oid) "
                + "AS surface FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = procedure.pronamespace "
                + "WHERE namespace.nspname = 'public' "
                + "AND procedure.proname IN (:names) ORDER BY proname")
        .param("names", V11_FUNCTIONS)
        .query(
            (row, ignored) ->
                Map.entry(row.getString("proname"), row.getString("surface")))
        .list()
        .stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, Map.Entry::getValue));
  }

  private static long v12NewFunctionCount(JdbcClient jdbc) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = procedure.pronamespace "
                + "WHERE namespace.nspname = 'public' "
                + "AND procedure.proname IN (:names)")
        .param("names", V12_NEW_FUNCTIONS)
        .query(Long.class)
        .single();
  }

  private static String functionCatalogImage(
      JdbcClient jdbc, String signature) {
    return jdbc.sql(
            "SELECT pg_catalog.jsonb_build_object("
                + "'definition', pg_catalog.pg_get_functiondef(procedure.oid), "
                + "'owner', owner.rolname, "
                + "'acl', procedure.proacl::text, "
                + "'xmin', procedure.xmin::text)::text "
                + "FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_roles owner "
                + "ON owner.oid = procedure.proowner "
                + "WHERE procedure.oid = CAST(:signature AS regprocedure)")
        .param("signature", signature)
        .query(String.class)
        .single();
  }

  private static Map<String, String> functionCatalogImages(
      JdbcClient jdbc, List<String> signatures) {
    Map<String, String> images = new LinkedHashMap<>();
    for (String signature : signatures) {
      images.put(signature, functionCatalogImage(jdbc, signature));
    }
    return Map.copyOf(images);
  }

  private static String triggerCatalogImage(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT COALESCE(pg_catalog.jsonb_agg(
              pg_catalog.jsonb_build_object(
                'name', trigger.tgname,
                'relation', relation.relname,
                'definition', pg_catalog.pg_get_triggerdef(
                  trigger.oid, true),
                'enabled', trigger.tgenabled,
                'xmin', trigger.xmin::text)
              ORDER BY relation.relname, trigger.tgname),
              '[]'::jsonb)::text
            FROM pg_catalog.pg_trigger trigger
            JOIN pg_catalog.pg_class relation
              ON relation.oid = trigger.tgrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = 'public'
              AND NOT trigger.tgisinternal
            """)
        .query(String.class)
        .single();
  }

  private static String historicalTriggerCatalogImage(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT COALESCE(pg_catalog.jsonb_agg(
              pg_catalog.jsonb_build_object(
                'name', trigger.tgname,
                'relation', relation.relname,
                'definition', pg_catalog.pg_get_triggerdef(
                  trigger.oid, true),
                'enabled', trigger.tgenabled,
                'xmin', trigger.xmin::text)
              ORDER BY relation.relname, trigger.tgname),
              '[]'::jsonb)::text
            FROM pg_catalog.pg_trigger trigger
            JOIN pg_catalog.pg_class relation
              ON relation.oid = trigger.tgrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = 'public'
              AND NOT trigger.tgisinternal
              AND trigger.tgname NOT IN (
                'agent_graph_exact_tx_a_attribution_v15',
                'agent_graph_exact_tx_a_event_v15',
                'agent_graph_exact_tx_a_head_v15',
                'agent_graph_exact_tx_a_requirement_v15',
                'agent_graph_exact_provider_validation_v16',
                'agent_graph_exact_provider_attribution_v16',
                'agent_graph_exact_attempt_event_v16',
                'agent_graph_exact_attempt_head_v16'
              )
            """)
        .query(String.class)
        .single();
  }

  private static String preV16TriggerCatalogImage(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT COALESCE(pg_catalog.jsonb_agg(
              pg_catalog.jsonb_build_object(
                'name', trigger.tgname,
                'relation', relation.relname,
                'definition', pg_catalog.pg_get_triggerdef(
                  trigger.oid, true),
                'enabled', trigger.tgenabled,
                'xmin', trigger.xmin::text)
              ORDER BY relation.relname, trigger.tgname),
              '[]'::jsonb)::text
            FROM pg_catalog.pg_trigger trigger
            JOIN pg_catalog.pg_class relation
              ON relation.oid = trigger.tgrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = 'public'
              AND NOT trigger.tgisinternal
              AND trigger.tgname NOT IN (
                'agent_graph_exact_provider_validation_v16',
                'agent_graph_exact_provider_attribution_v16',
                'agent_graph_exact_attempt_event_v16',
                'agent_graph_exact_attempt_head_v16'
              )
            """)
        .query(String.class)
        .single();
  }

  private static long trustedSecurityDefinerCount(JdbcClient jdbc) {
    return trustedSecurityDefinerCount(jdbc, V11_FUNCTIONS);
  }

  private static long trustedSecurityDefinerCount(
      JdbcClient jdbc, List<String> functionNames) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = procedure.pronamespace "
                + "WHERE namespace.nspname = 'public' "
                + "AND procedure.proname IN (:names) "
                + "AND procedure.prosecdef "
                + "AND procedure.proconfig = "
                + "ARRAY['search_path=pg_catalog, pg_temp']::text[]")
        .param("names", functionNames)
        .query(Long.class)
        .single();
  }

  private static List<String> fixedSearchPathInvokerFunctions(
      JdbcClient jdbc, List<String> functionNames) {
    return jdbc.sql(
            "SELECT procedure.proname FROM pg_catalog.pg_proc procedure "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = procedure.pronamespace "
                + "WHERE namespace.nspname = 'public' "
                + "AND procedure.proname IN (:names) "
                + "AND NOT procedure.prosecdef "
                + "AND procedure.proconfig = "
                + "ARRAY['search_path=pg_catalog, pg_temp']::text[] "
                + "ORDER BY procedure.proname")
        .param("names", functionNames)
        .query(String.class)
        .list();
  }

  private static long relationCount(JdbcClient jdbc, String name) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_catalog.pg_class relation "
                + "JOIN pg_catalog.pg_namespace namespace "
                + "ON namespace.oid = relation.relnamespace "
                + "WHERE namespace.nspname = current_schema() "
                + "AND relation.relname = :name AND relation.relkind = 'r'")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private static void assertFunctionPrivilege(
      JdbcClient jdbc, String role, String verb, boolean expected) {
    String signature =
        switch (verb) {
          case "record" ->
              "public.agent_graph_record_attributed_failure_v11("
                  + "character varying,character,character,integer,"
                  + "character,character varying)";
          case "read" ->
              "public.agent_graph_read_attributed_failure_v11("
                  + "character varying,character,character)";
          case "claim" ->
              "public.agent_graph_claim_attributed_failure_v11("
                  + "character varying,character,character,character,bigint,"
                  + "character varying,character,integer)";
          default -> throw new IllegalArgumentException("unknown verb");
        };
    Boolean actual =
        jdbc.sql("SELECT pg_catalog.has_function_privilege(:role, :fn, 'EXECUTE')")
            .param("role", role)
            .param("fn", signature)
            .query(Boolean.class)
            .single();
    assertEquals(expected, actual);
  }

  private static void assertV12FunctionPrivilege(
      JdbcClient jdbc, String role, String verb, boolean expected) {
    String signature =
        switch (verb) {
          case "read" ->
              "public.agent_graph_read_attributed_failure_resume_v12("
                  + "character varying,character,character)";
          case "claim" ->
              "public.agent_graph_claim_attributed_failure_resume_v12("
                  + "character varying,character,character,character,bigint,"
                  + "character varying,character,integer)";
          case "child" ->
              "public.agent_graph_complete_claimed_failure_child_v12("
                  + "jsonb,character,bigint,character varying,character)";
          case "parent" ->
              "public.agent_graph_complete_claimed_failure_parent_and_seal_v12("
                  + "jsonb,character,bigint,character varying,character)";
          default -> throw new IllegalArgumentException("unknown V12 verb");
        };
    Boolean actual =
        jdbc.sql("SELECT pg_catalog.has_function_privilege(:role, :fn, 'EXECUTE')")
            .param("role", role)
            .param("fn", signature)
            .query(Boolean.class)
            .single();
    assertEquals(expected, actual);
  }

  private static Map<String, String> rowImagesWithXmin(JdbcClient jdbc) {
    return rowImagesWithXmin(jdbc, V10_TRUTH_TABLES);
  }

  private static Map<String, String> rowImagesWithXmin(
      JdbcClient jdbc, List<String> tables) {
    Map<String, String> images = new LinkedHashMap<>();
    for (String table : tables) {
      images.put(
          table,
          jdbc.sql(
                  "SELECT COALESCE(pg_catalog.jsonb_agg("
                      + "pg_catalog.jsonb_build_object('row', "
                      + "pg_catalog.to_jsonb(row_image), 'xmin', "
                      + "row_image.xmin::text) ORDER BY "
                      + "pg_catalog.to_jsonb(row_image)::text), "
                      + "'[]'::jsonb)::text FROM "
                      + table
                      + " row_image")
              .query(String.class)
              .single());
    }
    return Map.copyOf(images);
  }

  private static List<String> migrationHistory(JdbcClient jdbc) {
    return jdbc.sql(
            "SELECT pg_catalog.concat_ws('|', version, checksum::text, "
                + "success::text, xmin::text) FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank")
        .query(String.class)
        .list();
  }

  private static DataSource schemaDataSource(String prefix) {
    String schema =
        prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    JdbcClient.create(baseDataSource()).sql("CREATE SCHEMA " + schema).update();
    PGSimpleDataSource source = baseDataSource();
    source.setCurrentSchema(schema);
    return source;
  }

  private static DataSource freshDatabaseDataSource(String prefix) {
    String safePrefix =
        prefix
            .toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9_]", "_");
    String database =
        safePrefix
            + "_"
            + UUID.randomUUID().toString().replace("-", "");
    if (!database.matches("[a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("invalid fixture database name");
    }
    execute(baseDataSource(), "CREATE DATABASE " + database);
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setServerNames(new String[] {POSTGRES.getHost()});
    source.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
    source.setDatabaseName(database);
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static PGSimpleDataSource roleDataSource(
      String username, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(username);
    source.setPassword(password);
    return source;
  }

  private static PGSimpleDataSource baseDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
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
        V11DurableAttributedFailureMigrationTest.class
            .getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalArgumentException("missing resource " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void assertSqlState(
      RuntimeException failure, String expected) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root instanceof java.sql.SQLException, failure.toString());
    assertEquals(expected, ((java.sql.SQLException) root).getSQLState());
  }

  private static void assertCheckConstraint(
      RuntimeException failure, String constraint) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root instanceof java.sql.SQLException, failure.toString());
    java.sql.SQLException postgres = (java.sql.SQLException) root;
    assertEquals("23514", postgres.getSQLState());
    assertTrue(postgres.getMessage().contains(constraint), postgres.toString());
  }

  private static void assertRootMessageContains(
      RuntimeException failure, String expected) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    assertTrue(root.getMessage().contains(expected), root.toString());
  }
}
