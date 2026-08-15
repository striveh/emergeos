package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.LocalDraftboxAuthority;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.LocalDraft;
import io.emergeos.core.domain.LocalDraftCreationReceipt;
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.LocalDraftboxStore;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V19LocalDraftUndoMigrationTest {

  private static final String DATABASE = "v19_local_draft_undo";
  private static final String OWNER = "v19-undo-owner";
  private static final String UNDO_SCHEMA = "emergeos.local-draft-undo-scope.v1";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_v19_undo_migration")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void v19AddsOnlyImmutableLogicalUndoTruthWithDatabaseAuthority() throws Exception {
    DataSource dataSource = freshDatabaseDataSource();
    assertEquals(18, flyway(dataSource, "18").migrate().migrationsExecuted);
    JdbcClient jdbc = JdbcClient.create(dataSource);

    CreationFixture primary = seedCreation(dataSource, OWNER, "primary");
    CreationFixture second = seedCreation(dataSource, OWNER, "second");
    CreationFixture third = seedCreation(dataSource, OWNER, "third");
    CreationFixture fourth = seedCreation(dataSource, OWNER, "fourth");
    CreationFixture forgedAuthority = seedCreation(dataSource, OWNER, "forged-authority");
    seedLegacyActionReceipt(dataSource, jdbc, primary);
    CreationTruthDigest v18Truth = creationTruth(jdbc);
    String v18LegacyReceipt = sealedRows(jdbc, "action_receipts", "principal_id, attempt_id");
    assertEquals(1, jdbc.sql("SELECT count(*) FROM action_receipts").query(Integer.class).single());
    CatalogSnapshot v18Catalog = legacyCatalogSnapshot(jdbc);
    Set<String> v18Objects = catalogObjects(jdbc);

    assertEquals(1, flyway(dataSource, "19").migrate().migrationsExecuted);
    assertEquals("19", currentVersion(jdbc));
    assertEquals(v18Truth, creationTruth(jdbc), "V19 must not rewrite any V18 creation truth");
    assertEquals(
        v18LegacyReceipt,
        sealedRows(jdbc, "action_receipts", "principal_id, attempt_id"),
        "V19 must preserve the sealed xmin and bytes of the legacy provider Receipt");
    assertEquals(v18Catalog, legacyCatalogSnapshot(jdbc), "V19 drifted a pre-existing object");
    assertCatalogDelta(v18Objects, catalogObjects(jdbc));

    assertTableShape(jdbc);
    assertConstraints(jdbc);
    assertTriggers(jdbc);
    assertFunctions(jdbc);

    String nonce = "undo-nonce-primary";
    Instant before = databaseNow(jdbc);
    insertUndo(jdbc, primary, "undo-receipt-primary", nonce, null);
    Instant after = databaseNow(jdbc);
    UndoRow stored = undoRow(jdbc, primary.attempt().attemptId());
    assertEquals(
        independentScopeHash(primary, nonce),
        stored.scopeHash(),
        "the generated hash must match the independent canonical-byte oracle");
    assertEquals(OWNER, stored.principalId());
    assertEquals("undo-receipt-primary", stored.receiptId());
    assertEquals(primary.attempt().attemptId(), stored.creationAttemptId());
    assertEquals(primary.draft().draftId(), stored.draftId());
    assertEquals(primary.creationReceipt().receiptId(), stored.creationReceiptId());
    assertEquals(primary.draft().artifactId(), stored.artifactId());
    assertEquals(primary.draft().artifactVersion(), stored.artifactVersion());
    assertEquals(primary.draft().artifactHash(), stored.artifactHash());
    assertEquals(UNDO_SCHEMA, stored.scopeSchema());
    assertEquals(nonce, stored.undoNonce());
    assertEquals("LOCAL_DRAFT_LOGICALLY_UNDONE_V1", stored.receiptType());
    assertEquals("LOGICALLY_UNDONE", stored.effect());
    assertEquals("CAPTURE_ARTIFACT_HISTORY_RETAINED", stored.retention());
    assertEquals("SUCCEEDED", stored.outcome());
    assertFalse(stored.simulated());
    assertTrue(stored.xmin().matches("[0-9]+"));
    assertFalse(stored.occurredAt().isBefore(before));
    assertFalse(stored.occurredAt().isAfter(after));
    assertEquals("ACTIVE", rawDraftState(jdbc, primary.draft().draftId()));
    assertEquals(v18Truth, creationTruth(jdbc));

    DataAccessException forgedIdentity =
        assertThrows(
            DataAccessException.class,
            () ->
                insertUndo(
                    jdbc,
                    forgedAuthority,
                    "undo-receipt-forged-artifact",
                    "undo-nonce-forged-artifact",
                    second));
    assertEquals("23514", sqlState(forgedIdentity));
    assertEquals(1, undoCount(jdbc));
    assertEquals(v18Truth, creationTruth(jdbc));

    DataAccessException duplicateReceipt =
        assertThrows(
            DataAccessException.class,
            () -> insertUndo(jdbc, second, stored.receiptId(), "undo-nonce-second", null));
    assertEquals("23505", sqlState(duplicateReceipt));

    DataAccessException duplicateNonce =
        assertThrows(
            DataAccessException.class,
            () -> insertUndo(jdbc, third, "undo-receipt-third", nonce, null));
    assertEquals("23505", sqlState(duplicateNonce));

    DataAccessException duplicateDraft =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                        """
                        INSERT INTO local_draft_undo_receipts (
                            principal_id, receipt_id, creation_attempt_id, draft_id,
                            creation_receipt_id, artifact_id, artifact_version,
                            artifact_hash, scope_schema, undo_nonce
                        ) VALUES (
                            :principalId, :receiptId, :creationAttemptId, :draftId,
                            :creationReceiptId, :artifactId, :artifactVersion,
                            :artifactHash, :scopeSchema, :undoNonce
                        )
                        """)
                    .param("principalId", OWNER)
                    .param("receiptId", "undo-receipt-fourth")
                    .param("creationAttemptId", fourth.attempt().attemptId())
                    .param("draftId", primary.draft().draftId())
                    .param("creationReceiptId", fourth.creationReceipt().receiptId())
                    .param("artifactId", fourth.draft().artifactId())
                    .param("artifactVersion", fourth.draft().artifactVersion())
                    .param("artifactHash", fourth.draft().artifactHash())
                    .param("scopeSchema", UNDO_SCHEMA)
                    .param("undoNonce", "undo-nonce-fourth")
                    .update());
    assertEquals("23505", sqlState(duplicateDraft));

    String sealedBefore = sealedUndoRow(jdbc, primary.attempt().attemptId());
    DataAccessException sameValueUpdate =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                        """
                        UPDATE local_draft_undo_receipts
                        SET receipt_id = receipt_id
                        WHERE principal_id = :principalId
                          AND creation_attempt_id = :attemptId
                        """)
                    .param("principalId", OWNER)
                    .param("attemptId", primary.attempt().attemptId())
                    .update());
    assertEquals("23514", sqlState(sameValueUpdate));
    assertEquals(sealedBefore, sealedUndoRow(jdbc, primary.attempt().attemptId()));

    DataAccessException delete =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.sql(
                        """
                        DELETE FROM local_draft_undo_receipts
                        WHERE principal_id = :principalId
                          AND creation_attempt_id = :attemptId
                        """)
                    .param("principalId", OWNER)
                    .param("attemptId", primary.attempt().attemptId())
                    .update());
    assertEquals("23514", sqlState(delete));
    assertEquals(sealedBefore, sealedUndoRow(jdbc, primary.attempt().attemptId()));
    assertEquals(v18Truth, creationTruth(jdbc));

    System.out.println(
        "V19_LOGICAL_UNDO_MIGRATION version=19 columns=17 constraints=28+1 triggers=3 "
            + "functions=4 catalogDelta=EXACT legacyCatalog=UNCHANGED "
            + "tableGrants=OWNER_ONLY functionGrants=OWNER_ONLY "
            + "typeGrants=OWNER_ONLY arrayAcl=ELEMENT_DERIVED "
            + "forgedIdentity=23514 uniques=3 immutable=23514 rawReceipt=EXACT "
            + "creationTruth=UNCHANGED legacyReceipt=UNCHANGED dbTime=BOUNDED "
            + "authority=ENGINEERING_ONLY live=DISABLED");
  }

  private static void assertTableShape(JdbcClient jdbc) {
    List<ColumnShape> columns =
        jdbc.sql(
                """
                SELECT attribute.attnum, attribute.attname,
                       pg_catalog.format_type(
                           attribute.atttypid, attribute.atttypmod) AS data_type,
                       attribute.attnotnull,
                       attribute.attgenerated::text AS attgenerated,
                       COALESCE(pg_catalog.pg_get_expr(
                           default_row.adbin, default_row.adrelid, true), '') AS expression
                FROM pg_catalog.pg_attribute attribute
                LEFT JOIN pg_catalog.pg_attrdef default_row
                  ON default_row.adrelid = attribute.attrelid
                 AND default_row.adnum = attribute.attnum
                WHERE attribute.attrelid = 'local_draft_undo_receipts'::regclass
                  AND attribute.attnum > 0
                  AND NOT attribute.attisdropped
                ORDER BY attribute.attnum
                """)
            .query(
                (row, ignored) ->
                    new ColumnShape(
                        row.getInt("attnum"),
                        row.getString("attname"),
                        row.getString("data_type"),
                        row.getBoolean("attnotnull"),
                        row.getString("attgenerated"),
                        canonicalSql(row.getString("expression"))))
            .list();
    assertEquals(
        List.of(
            column(1, "principal_id", "character varying(200)", true, "", ""),
            column(2, "receipt_id", "character varying(200)", true, "", ""),
            column(3, "creation_attempt_id", "character varying(200)", true, "", ""),
            column(4, "draft_id", "character varying(200)", true, "", ""),
            column(5, "creation_receipt_id", "character varying(200)", true, "", ""),
            column(6, "artifact_id", "character varying(200)", true, "", ""),
            column(7, "artifact_version", "integer", true, "", ""),
            column(8, "artifact_hash", "character(64)", true, "", ""),
            column(
                9,
                "scope_schema",
                "character varying(200)",
                true,
                "",
                "'emergeos.local-draft-undo-scope.v1'"),
            column(10, "undo_nonce", "character varying(200)", true, "", ""),
            column(
                11,
                "scope_hash",
                "character(64)",
                false,
                "s",
                "emerge_local_draft_undo_scope_hash_v1principal_id,draft_id,"
                    + "creation_attempt_id,creation_receipt_id,artifact_id,artifact_version,"
                    + "artifact_hash,undo_nonce"),
            column(
                12,
                "receipt_type",
                "character varying(64)",
                true,
                "",
                "'local_draft_logically_undone_v1'"),
            column(13, "effect", "character varying(64)", true, "", "'logically_undone'"),
            column(
                14,
                "retention",
                "character varying(64)",
                true,
                "",
                "'capture_artifact_history_retained'"),
            column(15, "outcome", "character varying(32)", true, "", "'succeeded'"),
            column(16, "occurred_at", "timestamp with time zone", true, "", ""),
            column(17, "simulated", "boolean", true, "", "false")),
        columns);
    assertExactTableAcl(jdbc);
  }

  private static void assertConstraints(JdbcClient jdbc) {
    List<ConstraintShape> constraints =
        jdbc.sql(
                """
                SELECT constraint_row.conname,
                       constraint_row.contype::text,
                       COALESCE((
                           SELECT pg_catalog.string_agg(attribute.attname, ',' ORDER BY key.ordinality)
                           FROM unnest(constraint_row.conkey) WITH ORDINALITY key(attnum, ordinality)
                           JOIN pg_catalog.pg_attribute attribute
                             ON attribute.attrelid = constraint_row.conrelid
                            AND attribute.attnum = key.attnum
                       ), '') AS key_columns,
                       COALESCE(reference_relation.relname, '') AS reference_relation,
                       COALESCE((
                           SELECT pg_catalog.string_agg(attribute.attname, ',' ORDER BY key.ordinality)
                           FROM unnest(constraint_row.confkey) WITH ORDINALITY key(attnum, ordinality)
                           JOIN pg_catalog.pg_attribute attribute
                             ON attribute.attrelid = constraint_row.confrelid
                            AND attribute.attnum = key.attnum
                       ), '') AS reference_columns,
                       constraint_row.condeferrable,
                       constraint_row.condeferred,
                       constraint_row.conenforced,
                       constraint_row.convalidated,
                       constraint_row.conislocal,
                       constraint_row.coninhcount,
                       constraint_row.connoinherit,
                       COALESCE(constraint_index.indnullsnotdistinct, false)
                           AS nulls_not_distinct,
                       constraint_row.conperiod,
                       pg_catalog.ascii(constraint_row.confmatchtype::text)::text
                           AS confmatchtype,
                       pg_catalog.ascii(constraint_row.confupdtype::text)::text
                           AS confupdtype,
                       pg_catalog.ascii(constraint_row.confdeltype::text)::text
                           AS confdeltype,
                       COALESCE(pg_catalog.pg_get_expr(
                           constraint_row.conbin, constraint_row.conrelid, true), '')
                           AS check_expression
                FROM pg_catalog.pg_constraint constraint_row
                LEFT JOIN pg_catalog.pg_index constraint_index
                  ON constraint_index.indexrelid = constraint_row.conindid
                 AND constraint_index.indrelid = constraint_row.conrelid
                LEFT JOIN pg_catalog.pg_class reference_relation
                  ON reference_relation.oid = constraint_row.confrelid
                WHERE constraint_row.conrelid = 'local_draft_undo_receipts'::regclass
                ORDER BY constraint_row.conname
                """)
            .query(
                (row, ignored) ->
                    new ConstraintShape(
                        row.getString("conname"),
                        row.getString("contype"),
                        row.getString("key_columns"),
                        row.getString("reference_relation"),
                        row.getString("reference_columns"),
                        row.getBoolean("condeferrable"),
                        row.getBoolean("condeferred"),
                        row.getBoolean("conenforced"),
                        row.getBoolean("convalidated"),
                        row.getBoolean("conislocal"),
                        row.getInt("coninhcount"),
                        row.getBoolean("connoinherit"),
                        row.getBoolean("nulls_not_distinct"),
                        row.getBoolean("conperiod"),
                        row.getString("confmatchtype"),
                        row.getString("confupdtype"),
                        row.getString("confdeltype"),
                        canonicalSql(row.getString("check_expression"))))
            .list();
    assertEquals(expectedUndoConstraints(), constraints);

    assertEquals(
        List.of(
            new ConstraintShape(
                "local_draft_creation_receipts_exact_identity_v19_uq",
                "u",
                "principal_id,attempt_id,receipt_id,draft_id",
                "",
                "",
                false,
                false,
                true,
                true,
                true,
                0,
                true,
                false,
                false,
                "32",
                "32",
                "32",
                "")),
        constraintByName(jdbc, "local_draft_creation_receipts_exact_identity_v19_uq"));
  }

  private static void assertTriggers(JdbcClient jdbc) {
    List<TriggerShape> triggers =
        jdbc.sql(
                """
                SELECT trigger_row.tgname,
                       trigger_row.tgenabled::text AS tgenabled,
                       trigger_row.tgtype,
                       trigger_row.tgattr::text,
                       COALESCE(pg_catalog.pg_get_expr(
                           trigger_row.tgqual, trigger_row.tgrelid, true), '') AS qualifier,
                       pg_catalog.encode(trigger_row.tgargs, 'hex') AS arguments,
                       (trigger_row.tgconstraint <> 0) AS constraint_trigger,
                       trigger_row.tgdeferrable,
                       trigger_row.tginitdeferred,
                       guard.proname || '(' ||
                         pg_catalog.pg_get_function_identity_arguments(guard.oid) || ')'
                         AS guard_function
                FROM pg_catalog.pg_trigger trigger_row
                JOIN pg_catalog.pg_proc guard ON guard.oid = trigger_row.tgfoid
                WHERE trigger_row.tgrelid = 'local_draft_undo_receipts'::regclass
                  AND NOT trigger_row.tgisinternal
                ORDER BY trigger_row.tgname
                """)
            .query(
                (row, ignored) ->
                    new TriggerShape(
                        row.getString("tgname"),
                        row.getString("tgenabled"),
                        row.getInt("tgtype"),
                        row.getString("tgattr"),
                        row.getString("qualifier"),
                        row.getString("arguments"),
                        row.getBoolean("constraint_trigger"),
                        row.getBoolean("tgdeferrable"),
                        row.getBoolean("tginitdeferred"),
                        row.getString("guard_function")))
            .list();
    assertEquals(
        List.of(
            trigger(
                "local_draft_undo_receipts_authority_v19",
                5,
                true,
                true,
                true,
                "enforce_local_draft_undo_authority_v19()"),
            trigger(
                "local_draft_undo_receipts_db_clock_v19",
                7,
                false,
                false,
                false,
                "canonicalize_local_draft_undo_occurred_at_v19()"),
            trigger(
                "local_draft_undo_receipts_immutable_v19",
                27,
                false,
                false,
                false,
                "freeze_local_draft_undo_receipt_v19()")),
        triggers);
  }

  private static void assertFunctions(JdbcClient jdbc) {
    List<FunctionShape> functions =
        jdbc.sql(
                """
                SELECT procedure.proname,
                       pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                           AS identity_arguments,
                       pg_catalog.pg_get_function_result(procedure.oid) AS result_type,
                       language.lanname,
                       procedure.provolatile::text,
                       procedure.proparallel::text,
                       procedure.prokind::text,
                       procedure.proretset,
                       procedure.proisstrict,
                       procedure.proleakproof,
                       procedure.prosecdef,
                       COALESCE(pg_catalog.array_to_string(procedure.proconfig, ';'), '')
                           AS configuration,
                       pg_catalog.pg_get_userbyid(procedure.proowner) AS owner_name,
                       COALESCE((
                           SELECT pg_catalog.string_agg(
                               COALESCE(grantee.rolname, 'PUBLIC') || '|' ||
                               acl.privilege_type || '|' || acl.is_grantable::text,
                               ',' ORDER BY COALESCE(grantee.rolname, 'PUBLIC'),
                                          acl.privilege_type, acl.is_grantable)
                           FROM pg_catalog.aclexplode(COALESCE(
                               procedure.proacl,
                               pg_catalog.acldefault('f', procedure.proowner))) acl
                           LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                       ), '') AS exact_acl
                FROM pg_catalog.pg_proc procedure
                JOIN pg_catalog.pg_language language ON language.oid = procedure.prolang
                WHERE procedure.pronamespace = current_schema()::regnamespace
                  AND procedure.proname IN (
                      'emerge_local_draft_undo_scope_hash_v1',
                      'canonicalize_local_draft_undo_occurred_at_v19',
                      'freeze_local_draft_undo_receipt_v19',
                      'enforce_local_draft_undo_authority_v19'
                  )
                ORDER BY procedure.proname
                """)
            .query(
                (row, ignored) ->
                    new FunctionShape(
                        row.getString("proname"),
                        row.getString("identity_arguments"),
                        row.getString("result_type"),
                        row.getString("lanname"),
                        row.getString("provolatile"),
                        row.getString("proparallel"),
                        row.getString("prokind"),
                        row.getBoolean("proretset"),
                        row.getBoolean("proisstrict"),
                        row.getBoolean("proleakproof"),
                        row.getBoolean("prosecdef"),
                        row.getString("configuration"),
                        row.getString("owner_name"),
                        row.getString("exact_acl")))
            .list();
    String currentUser = jdbc.sql("SELECT current_user").query(String.class).single();
    assertEquals(
        List.of(
            triggerFunction("canonicalize_local_draft_undo_occurred_at_v19", currentUser),
            new FunctionShape(
                "emerge_local_draft_undo_scope_hash_v1",
                "p_principal_id text, p_draft_id text, p_creation_attempt_id text, "
                    + "p_creation_receipt_id text, p_artifact_id text, "
                    + "p_artifact_version integer, p_artifact_hash text, p_undo_nonce text",
                "text",
                "sql",
                "i",
                "s",
                "f",
                false,
                true,
                false,
                false,
                "",
                currentUser,
                currentUser + "|EXECUTE|false"),
            triggerFunction("enforce_local_draft_undo_authority_v19", currentUser),
            triggerFunction("freeze_local_draft_undo_receipt_v19", currentUser)),
        functions);
  }

  private static ColumnShape column(
      int position,
      String name,
      String dataType,
      boolean notNull,
      String generated,
      String expression) {
    return new ColumnShape(position, name, dataType, notNull, generated, expression);
  }

  private static List<ConstraintShape> expectedUndoConstraints() {
    List<ConstraintShape> expected = new ArrayList<>();
    expected.add(
        checkConstraint(
            "local_draft_undo_receipts_artifact_hash_valid",
            "artifact_hash",
            "artifact_hash~'^[0-9a-f]{64}$'"));
    expected.add(
        checkConstraint(
            "local_draft_undo_receipts_artifact_version_valid",
            "artifact_version",
            "artifact_version>=1"));
    expected.add(
        foreignKey(
            "local_draft_undo_receipts_artifact_version_fk",
            "principal_id,artifact_id,artifact_version,artifact_hash",
            "artifact_versions",
            "principal_id,artifact_id,version,content_hash",
            false));
    expected.add(
        foreignKey(
            "local_draft_undo_receipts_creation_receipt_fk",
            "principal_id,creation_attempt_id,creation_receipt_id,draft_id",
            "local_draft_creation_receipts",
            "principal_id,attempt_id,receipt_id,draft_id",
            true));
    expected.add(triggerConstraint("local_draft_undo_receipts_authority_v19"));
    expected.add(uniqueConstraint("local_draft_undo_receipts_draft_uq", "principal_id,draft_id"));
    expected.add(
        foreignKey(
            "local_draft_undo_receipts_draft_fk",
            "principal_id,draft_id,creation_attempt_id",
            "local_drafts",
            "principal_id,draft_id,attempt_id",
            true));
    expected.add(
        checkConstraint(
            "local_draft_undo_receipts_identifiers_valid",
            "principal_id,receipt_id,creation_attempt_id,draft_id,creation_receipt_id,"
                + "artifact_id,undo_nonce",
            "btrimprincipal_id<>''andbtrimreceipt_id<>''and"
                + "btrimcreation_attempt_id<>''andbtrimdraft_id<>''and"
                + "btrimcreation_receipt_id<>''andbtrimartifact_id<>''and"
                + "btrimundo_nonce<>''"));
    expected.add(uniqueConstraint("local_draft_undo_receipts_nonce_uq", "principal_id,undo_nonce"));
    expected.add(primaryKey("local_draft_undo_receipts_pk", "principal_id,creation_attempt_id"));
    expected.add(uniqueConstraint("local_draft_undo_receipts_receipt_id_uq", "receipt_id"));
    expected.add(
        checkConstraint(
            "local_draft_undo_receipts_truth_valid",
            "scope_schema,receipt_type,effect,retention,outcome,simulated",
            "scope_schema='emergeos.local-draft-undo-scope.v1'and"
                + "receipt_type='local_draft_logically_undone_v1'and"
                + "effect='logically_undone'and"
                + "retention='capture_artifact_history_retained'and"
                + "outcome='succeeded'andnotsimulated"));
    List<String> requiredColumns =
        List.of(
            "principal_id",
            "receipt_id",
            "creation_attempt_id",
            "draft_id",
            "creation_receipt_id",
            "artifact_id",
            "artifact_version",
            "artifact_hash",
            "scope_schema",
            "undo_nonce",
            "receipt_type",
            "effect",
            "retention",
            "outcome",
            "occurred_at",
            "simulated");
    for (String column : requiredColumns) {
      expected.add(notNullConstraint("local_draft_undo_receipts_" + column + "_not_null", column));
    }
    expected.sort(Comparator.comparing(ConstraintShape::name));
    return List.copyOf(expected);
  }

  private static ConstraintShape primaryKey(String name, String columns) {
    return constraint(name, "p", columns, "", "", false, false, "", "32", "32", "32");
  }

  private static ConstraintShape uniqueConstraint(String name, String columns) {
    return constraint(name, "u", columns, "", "", false, false, "", "32", "32", "32");
  }

  private static ConstraintShape notNullConstraint(String name, String column) {
    return constraint(name, "n", column, "", "", false, false, "", "32", "32", "32");
  }

  private static ConstraintShape checkConstraint(String name, String columns, String expression) {
    return constraint(name, "c", columns, "", "", false, false, expression, "32", "32", "32");
  }

  private static ConstraintShape triggerConstraint(String name) {
    return constraint(name, "t", "", "", "", true, true, "", "32", "32", "32");
  }

  private static ConstraintShape foreignKey(
      String name,
      String columns,
      String referenceRelation,
      String referenceColumns,
      boolean deferred) {
    return constraint(
        name,
        "f",
        columns,
        referenceRelation,
        referenceColumns,
        deferred,
        deferred,
        "",
        "115",
        "97",
        "97");
  }

  private static ConstraintShape constraint(
      String name,
      String type,
      String columns,
      String referenceRelation,
      String referenceColumns,
      boolean deferrable,
      boolean initiallyDeferred,
      String checkExpression,
      String matchType,
      String updateType,
      String deleteType) {
    return new ConstraintShape(
        name,
        type,
        columns,
        referenceRelation,
        referenceColumns,
        deferrable,
        initiallyDeferred,
        true,
        true,
        true,
        0,
        expectedConstraintNoInherit(type),
        false,
        false,
        matchType,
        updateType,
        deleteType,
        checkExpression);
  }

  private static boolean expectedConstraintNoInherit(String type) {
    return switch (type) {
      case "f", "p", "t", "u" -> true;
      case "c", "n" -> false;
      default -> throw new IllegalArgumentException("unsupported constraint type: " + type);
    };
  }

  private static List<ConstraintShape> constraintByName(JdbcClient jdbc, String name) {
    return jdbc.sql(
            """
            SELECT constraint_row.conname,
                   constraint_row.contype::text,
                   COALESCE((
                       SELECT pg_catalog.string_agg(attribute.attname, ',' ORDER BY key.ordinality)
                       FROM unnest(constraint_row.conkey) WITH ORDINALITY key(attnum, ordinality)
                       JOIN pg_catalog.pg_attribute attribute
                         ON attribute.attrelid = constraint_row.conrelid
                        AND attribute.attnum = key.attnum
                   ), '') AS key_columns,
                   COALESCE(reference_relation.relname, '') AS reference_relation,
                   COALESCE((
                       SELECT pg_catalog.string_agg(attribute.attname, ',' ORDER BY key.ordinality)
                       FROM unnest(constraint_row.confkey) WITH ORDINALITY key(attnum, ordinality)
                       JOIN pg_catalog.pg_attribute attribute
                         ON attribute.attrelid = constraint_row.confrelid
                        AND attribute.attnum = key.attnum
                   ), '') AS reference_columns,
                   constraint_row.condeferrable,
                   constraint_row.condeferred,
                   constraint_row.conenforced,
                   constraint_row.convalidated,
                   constraint_row.conislocal,
                   constraint_row.coninhcount,
                   constraint_row.connoinherit,
                   COALESCE(constraint_index.indnullsnotdistinct, false)
                       AS nulls_not_distinct,
                   constraint_row.conperiod,
                   pg_catalog.ascii(constraint_row.confmatchtype::text)::text
                       AS confmatchtype,
                   pg_catalog.ascii(constraint_row.confupdtype::text)::text
                       AS confupdtype,
                   pg_catalog.ascii(constraint_row.confdeltype::text)::text
                       AS confdeltype,
                   COALESCE(pg_catalog.pg_get_expr(
                       constraint_row.conbin, constraint_row.conrelid, true), '')
                       AS check_expression
            FROM pg_catalog.pg_constraint constraint_row
            LEFT JOIN pg_catalog.pg_index constraint_index
              ON constraint_index.indexrelid = constraint_row.conindid
             AND constraint_index.indrelid = constraint_row.conrelid
            LEFT JOIN pg_catalog.pg_class reference_relation
              ON reference_relation.oid = constraint_row.confrelid
            WHERE constraint_row.conname = :name
              AND constraint_row.conrelid =
                    'local_draft_creation_receipts'::regclass
              AND constraint_row.connamespace = current_schema()::regnamespace
            ORDER BY constraint_row.conname
            """)
        .param("name", name)
        .query(
            (row, ignored) ->
                new ConstraintShape(
                    row.getString("conname"),
                    row.getString("contype"),
                    row.getString("key_columns"),
                    row.getString("reference_relation"),
                    row.getString("reference_columns"),
                    row.getBoolean("condeferrable"),
                    row.getBoolean("condeferred"),
                    row.getBoolean("conenforced"),
                    row.getBoolean("convalidated"),
                    row.getBoolean("conislocal"),
                    row.getInt("coninhcount"),
                    row.getBoolean("connoinherit"),
                    row.getBoolean("nulls_not_distinct"),
                    row.getBoolean("conperiod"),
                    row.getString("confmatchtype"),
                    row.getString("confupdtype"),
                    row.getString("confdeltype"),
                    canonicalSql(row.getString("check_expression"))))
        .list();
  }

  private static TriggerShape trigger(
      String name,
      int type,
      boolean constraint,
      boolean deferrable,
      boolean initiallyDeferred,
      String guard) {
    return new TriggerShape(
        name, "O", type, "", "", "", constraint, deferrable, initiallyDeferred, guard);
  }

  private static FunctionShape triggerFunction(String name, String owner) {
    return new FunctionShape(
        name,
        "",
        "trigger",
        "plpgsql",
        "v",
        "u",
        "f",
        false,
        false,
        false,
        false,
        "",
        owner,
        owner + "|EXECUTE|false");
  }

  private static void assertExactTableAcl(JdbcClient jdbc) {
    String owner = jdbc.sql("SELECT current_user").query(String.class).single();
    assertEquals(
        owner,
        jdbc.sql(
                """
                SELECT pg_catalog.pg_get_userbyid(relation.relowner)
                FROM pg_catalog.pg_class relation
                WHERE relation.oid = 'local_draft_undo_receipts'::regclass
                """)
            .query(String.class)
            .single());
    assertEquals(
        List.of(
            owner + "|DELETE|false",
            owner + "|INSERT|false",
            owner + "|MAINTAIN|false",
            owner + "|REFERENCES|false",
            owner + "|SELECT|false",
            owner + "|TRIGGER|false",
            owner + "|TRUNCATE|false",
            owner + "|UPDATE|false"),
        jdbc.sql(
                """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || '|' ||
                       acl.privilege_type || '|' || acl.is_grantable::text
                FROM pg_catalog.pg_class relation
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    relation.relacl,
                    pg_catalog.acldefault('r', relation.relowner))) acl
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                WHERE relation.oid = 'local_draft_undo_receipts'::regclass
                ORDER BY COALESCE(grantee.rolname, 'PUBLIC'),
                         acl.privilege_type, acl.is_grantable
                """)
            .query(String.class)
            .list());
    assertExactTypeAcl(jdbc);
  }

  private static void assertExactTypeAcl(JdbcClient jdbc) {
    String owner = jdbc.sql("SELECT current_user").query(String.class).single();
    assertEquals(
        List.of("local_draft_undo_receipts|" + owner + "|" + owner + "|USAGE|false"),
        jdbc.sql(
                """
                SELECT type_row.typname || '|' ||
                       pg_catalog.pg_get_userbyid(type_row.typowner) || '|' ||
                       COALESCE(grantee.rolname, 'PUBLIC') || '|' ||
                       acl.privilege_type || '|' || acl.is_grantable::text
                FROM pg_catalog.pg_type type_row
                JOIN pg_catalog.pg_namespace namespace
                  ON namespace.oid = type_row.typnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    type_row.typacl,
                    pg_catalog.acldefault('T', type_row.typowner))) acl
                LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
                WHERE namespace.nspname = current_schema()
                  AND type_row.typname = 'local_draft_undo_receipts'
                ORDER BY type_row.typname,
                         COALESCE(grantee.rolname, 'PUBLIC'),
                         acl.privilege_type, acl.is_grantable
                """)
            .query(String.class)
            .list(),
        "V19_TYPE_ACL_NOT_OWNER_ONLY");
    assertEquals(
        "public|local_draft_undo_receipts|public|_local_draft_undo_receipts|true|true|true",
        jdbc.sql(
                """
                SELECT element_namespace.nspname || '|' || element.typname || '|' ||
                       array_namespace.nspname || '|' || array_type.typname || '|' ||
                       (element.typarray = array_type.oid)::text || '|' ||
                       (array_type.typelem = element.oid)::text || '|' ||
                       (array_type.typacl IS NULL)::text
                FROM pg_catalog.pg_type element
                JOIN pg_catalog.pg_namespace element_namespace
                  ON element_namespace.oid = element.typnamespace
                LEFT JOIN pg_catalog.pg_type array_type
                  ON array_type.oid = element.typarray
                LEFT JOIN pg_catalog.pg_namespace array_namespace
                  ON array_namespace.oid = array_type.typnamespace
                WHERE element_namespace.nspname = current_schema()
                  AND element.typname = 'local_draft_undo_receipts'
                """)
            .query(String.class)
            .single(),
        "V19_ARRAY_TYPE_TOPOLOGY_DRIFT");
  }

  private static void assertCatalogDelta(Set<String> before, Set<String> after) {
    Set<String> added = new java.util.TreeSet<>(after);
    added.removeAll(before);
    Set<String> expected = new java.util.TreeSet<>();
    expected.add("RELATION|i|local_draft_creation_receipts_exact_identity_v19_uq");
    expected.add("RELATION|r|local_draft_undo_receipts");
    expected.add("RELATION|i|local_draft_undo_receipts_draft_uq");
    expected.add("RELATION|i|local_draft_undo_receipts_nonce_uq");
    expected.add("RELATION|i|local_draft_undo_receipts_pk");
    expected.add("RELATION|i|local_draft_undo_receipts_receipt_id_uq");
    expected.add("TYPE|A|b|_local_draft_undo_receipts");
    expected.add("TYPE|C|c|local_draft_undo_receipts");
    expected.add(
        "FUNCTION|emerge_local_draft_undo_scope_hash_v1|p_principal_id text, "
            + "p_draft_id text, p_creation_attempt_id text, p_creation_receipt_id text, "
            + "p_artifact_id text, p_artifact_version integer, p_artifact_hash text, "
            + "p_undo_nonce text");
    expected.add("FUNCTION|canonicalize_local_draft_undo_occurred_at_v19|");
    expected.add("FUNCTION|freeze_local_draft_undo_receipt_v19|");
    expected.add("FUNCTION|enforce_local_draft_undo_authority_v19|");
    expected.add(
        "CONSTRAINT|local_draft_creation_receipts|u|"
            + "local_draft_creation_receipts_exact_identity_v19_uq");
    for (ConstraintShape constraint : expectedUndoConstraints()) {
      expected.add(
          "CONSTRAINT|local_draft_undo_receipts|" + constraint.type() + "|" + constraint.name());
    }
    expected.add("TRIGGER|local_draft_undo_receipts|local_draft_undo_receipts_authority_v19");
    expected.add("TRIGGER|local_draft_undo_receipts|local_draft_undo_receipts_db_clock_v19");
    expected.add("TRIGGER|local_draft_undo_receipts|local_draft_undo_receipts_immutable_v19");
    assertEquals(expected, added, "V19 added an unexpected user-schema object");
  }

  private static Set<String> catalogObjects(JdbcClient jdbc) {
    return Set.copyOf(
        jdbc.sql(
                """
                SELECT object_name
                FROM (
                    SELECT 'RELATION|' || relation.relkind::text || '|' || relation.relname
                               AS object_name
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = current_schema()
                      AND relation.relkind::text IN ('r', 'p', 'v', 'm', 'S', 'f', 'i')
                    UNION ALL
                    SELECT 'TYPE|' || type_row.typcategory::text || '|' ||
                           type_row.typtype::text || '|' || type_row.typname
                    FROM pg_catalog.pg_type type_row
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = type_row.typnamespace
                    WHERE namespace.nspname = current_schema()
                    UNION ALL
                    SELECT 'FUNCTION|' || procedure.proname || '|' ||
                           pg_catalog.pg_get_function_identity_arguments(procedure.oid)
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = current_schema()
                    UNION ALL
                    SELECT 'CONSTRAINT|' || relation.relname || '|' ||
                           constraint_row.contype::text || '|' || constraint_row.conname
                    FROM pg_catalog.pg_constraint constraint_row
                    JOIN pg_catalog.pg_class relation ON relation.oid = constraint_row.conrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = constraint_row.connamespace
                    WHERE namespace.nspname = current_schema()
                    UNION ALL
                    SELECT 'TRIGGER|' || relation.relname || '|' || trigger_row.tgname
                    FROM pg_catalog.pg_trigger trigger_row
                    JOIN pg_catalog.pg_class relation ON relation.oid = trigger_row.tgrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = current_schema()
                      AND NOT trigger_row.tgisinternal
                ) objects
                ORDER BY object_name
                """)
            .query(String.class)
            .list());
  }

  private static CatalogSnapshot legacyCatalogSnapshot(JdbcClient jdbc) {
    return new CatalogSnapshot(
        catalogAggregate(
            jdbc,
            """
            SELECT namespace.nspname || '|' ||
                   pg_catalog.pg_get_userbyid(namespace.nspowner) || '|' ||
                   COALESCE(namespace.nspacl::text, '') || '|' ||
                   COALESCE((
                       SELECT string_agg(
                           CASE WHEN acl.grantee = 0
                                  THEN 'PUBLIC'
                                  ELSE pg_catalog.pg_get_userbyid(acl.grantee)
                           END || '|' ||
                           pg_catalog.pg_get_userbyid(acl.grantor) || '|' ||
                           acl.privilege_type || '|' || acl.is_grantable::text,
                           ',' ORDER BY acl.grantee, acl.grantor,
                                        acl.privilege_type, acl.is_grantable
                       )
                       FROM pg_catalog.aclexplode(COALESCE(
                           namespace.nspacl,
                           pg_catalog.acldefault('n', namespace.nspowner))) acl
                   ), '') AS surface
            FROM pg_catalog.pg_namespace namespace
            WHERE namespace.nspname = current_schema()
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT relation.relname || '|' || relation.relkind::text || '|' ||
                   relation.relpersistence::text || '|' || relation.relrowsecurity::text || '|' ||
                   relation.relforcerowsecurity::text || '|' ||
                   pg_catalog.pg_get_userbyid(relation.relowner) || '|' ||
                   COALESCE(relation.relacl::text, '') || '|' ||
                   CASE
                     WHEN relation.relkind::text = 'i'
                       THEN pg_catalog.pg_get_indexdef(relation.oid)
                     WHEN relation.relkind::text IN ('v', 'm')
                       THEN pg_catalog.pg_get_viewdef(relation.oid, true)
                     ELSE ''
                   END AS surface
            FROM pg_catalog.pg_class relation
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = current_schema()
              AND relation.relname NOT LIKE '%local_draft_undo%'
              AND relation.relname <> 'local_draft_creation_receipts_exact_identity_v19_uq'
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT relation.relname || '|' || attribute.attnum::text || '|' ||
                   attribute.attname || '|' ||
                   pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) || '|' ||
                   attribute.attnotnull::text || '|' || attribute.attgenerated::text || '|' ||
                   COALESCE(pg_catalog.pg_get_expr(
                       default_row.adbin, default_row.adrelid, true), '') AS surface
            FROM pg_catalog.pg_attribute attribute
            JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
            LEFT JOIN pg_catalog.pg_attrdef default_row
              ON default_row.adrelid = attribute.attrelid
             AND default_row.adnum = attribute.attnum
            WHERE namespace.nspname = current_schema()
              AND attribute.attnum > 0 AND NOT attribute.attisdropped
              AND relation.relname NOT LIKE 'local_draft_undo_receipts%'
              AND relation.relname <>
                    'local_draft_creation_receipts_exact_identity_v19_uq'
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT relation.relname || '|' || constraint_row.conname || '|' ||
                   constraint_row.contype::text || '|' ||
                   constraint_row.condeferrable::text || '|' ||
                   constraint_row.condeferred::text || '|' ||
                   pg_catalog.pg_get_constraintdef(constraint_row.oid, true) AS surface
            FROM pg_catalog.pg_constraint constraint_row
            JOIN pg_catalog.pg_class relation ON relation.oid = constraint_row.conrelid
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = constraint_row.connamespace
            WHERE namespace.nspname = current_schema()
              AND relation.relname <> 'local_draft_undo_receipts'
              AND constraint_row.conname <>
                    'local_draft_creation_receipts_exact_identity_v19_uq'
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT relation.relname || '|' || trigger_row.tgname || '|' ||
                   trigger_row.tgenabled::text || '|' || trigger_row.tgtype::text || '|' ||
                   trigger_row.tgattr::text || '|' ||
                   COALESCE(pg_catalog.pg_get_expr(
                       trigger_row.tgqual, trigger_row.tgrelid, true), '') || '|' ||
                   pg_catalog.encode(trigger_row.tgargs, 'hex') || '|' ||
                   (trigger_row.tgconstraint <> 0)::text || '|' ||
                   trigger_row.tgdeferrable::text || '|' ||
                   trigger_row.tginitdeferred::text || '|' ||
                   guard.proname || '(' ||
                   pg_catalog.pg_get_function_identity_arguments(guard.oid) || ')' AS surface
            FROM pg_catalog.pg_trigger trigger_row
            JOIN pg_catalog.pg_class relation ON relation.oid = trigger_row.tgrelid
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
            JOIN pg_catalog.pg_proc guard ON guard.oid = trigger_row.tgfoid
            WHERE namespace.nspname = current_schema()
              AND NOT trigger_row.tgisinternal
              AND relation.relname <> 'local_draft_undo_receipts'
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT procedure.proname || '|' ||
                   pg_catalog.pg_get_function_identity_arguments(procedure.oid) || '|' ||
                   pg_catalog.pg_get_function_result(procedure.oid) || '|' ||
                   language.lanname || '|' ||
                   pg_catalog.pg_get_userbyid(procedure.proowner) || '|' ||
                   procedure.prokind::text || '|' || procedure.proretset::text || '|' ||
                   procedure.prosecdef::text || '|' || procedure.provolatile::text || '|' ||
                   procedure.proparallel::text || '|' || procedure.proisstrict::text || '|' ||
                   procedure.proleakproof::text || '|' ||
                   COALESCE(procedure.proconfig::text, '') || '|' ||
                   COALESCE(procedure.proacl::text, '') || '|' ||
                   COALESCE((
                       SELECT string_agg(
                           CASE WHEN acl.grantee = 0
                                  THEN 'PUBLIC'
                                  ELSE pg_catalog.pg_get_userbyid(acl.grantee)
                           END || '|' ||
                           pg_catalog.pg_get_userbyid(acl.grantor) || '|' ||
                           acl.privilege_type || '|' || acl.is_grantable::text,
                           ',' ORDER BY acl.grantee, acl.grantor,
                                        acl.privilege_type, acl.is_grantable
                       )
                       FROM pg_catalog.aclexplode(COALESCE(
                           procedure.proacl,
                           pg_catalog.acldefault('f', procedure.proowner))) acl
                   ), '') || '|' ||
                   pg_catalog.encode(pg_catalog.sha256(
                       pg_catalog.convert_to(procedure.prosrc, 'UTF8')), 'hex') AS surface
            FROM pg_catalog.pg_proc procedure
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = procedure.pronamespace
            JOIN pg_catalog.pg_language language ON language.oid = procedure.prolang
            WHERE namespace.nspname = current_schema()
              AND procedure.proname NOT IN (
                  'emerge_local_draft_undo_scope_hash_v1',
                  'canonicalize_local_draft_undo_occurred_at_v19',
                  'freeze_local_draft_undo_receipt_v19',
                  'enforce_local_draft_undo_authority_v19')
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT type_row.typname || '|' || type_row.typtype::text || '|' ||
                   type_row.typcategory::text || '|' ||
                   pg_catalog.pg_get_userbyid(type_row.typowner) AS surface
            FROM pg_catalog.pg_type type_row
            JOIN pg_catalog.pg_namespace namespace ON namespace.oid = type_row.typnamespace
            WHERE namespace.nspname = current_schema()
              AND type_row.typname NOT IN (
                  'local_draft_undo_receipts', '_local_draft_undo_receipts')
            """),
        catalogAggregate(
            jdbc,
            """
            SELECT pg_catalog.pg_get_userbyid(default_acl.defaclrole) || '|' ||
                   COALESCE(namespace.nspname, '<global>') || '|' ||
                   default_acl.defaclobjtype::text || '|' ||
                   COALESCE(default_acl.defaclacl::text, '') || '|' ||
                   COALESCE((
                       SELECT string_agg(
                           CASE WHEN acl.grantee = 0
                                  THEN 'PUBLIC'
                                  ELSE pg_catalog.pg_get_userbyid(acl.grantee)
                           END || '|' ||
                           pg_catalog.pg_get_userbyid(acl.grantor) || '|' ||
                           acl.privilege_type || '|' || acl.is_grantable::text,
                           ',' ORDER BY acl.grantee, acl.grantor,
                                        acl.privilege_type, acl.is_grantable
                       )
                       FROM pg_catalog.aclexplode(
                           COALESCE(default_acl.defaclacl, '{}'::aclitem[])) acl
                   ), '') AS surface
            FROM pg_catalog.pg_default_acl default_acl
            LEFT JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = default_acl.defaclnamespace
            WHERE default_acl.defaclnamespace = 0
               OR namespace.nspname = current_schema()
            """));
  }

  private static String catalogAggregate(JdbcClient jdbc, String subquery) {
    return jdbc.sql(
            "SELECT COALESCE(jsonb_agg(surface ORDER BY surface)::text, '[]') FROM ("
                + subquery
                + ") catalog_surface")
        .query(String.class)
        .single();
  }

  private static String canonicalSql(String sql) {
    return sql.toLowerCase(Locale.ROOT)
        .replace("::character varying", "")
        .replace("::text", "")
        .replace("::bpchar", "")
        .replaceAll("[\\s()]", "");
  }

  private static CreationFixture seedCreation(DataSource dataSource, String owner, String suffix) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    Instant now = databaseNow(jdbc);
    String captureId = "capture-" + suffix;
    String artifactId = "artifact-" + suffix;
    String source = "synthetic V19 migration source " + suffix;
    new PostgresCaptureStore(dataSource, transactions)
        .saveOrFindByNonce(
            new Capture(
                captureId,
                owner,
                "capture-nonce-" + suffix,
                CaptureRequestHashes.sha256(
                    source, CaptureSourceType.TEXT, "v19-migration-test", DataClass.PERSONAL),
                source,
                CaptureSourceType.TEXT,
                "v19-migration-test",
                DataClass.PERSONAL,
                now.minusSeconds(2)));
    String content = "synthetic V19 migration artifact " + suffix;
    ArtifactLineage artifact =
        new PostgresArtifactLineageStore(dataSource, transactions)
            .create(
                new ArtifactLineage(
                    artifactId,
                    owner,
                    captureId,
                    List.of(
                        new ArtifactLineageEntry(
                            1,
                            content,
                            ContentHashes.sha256(content),
                            null,
                            null,
                            now.minusSeconds(1)))));
    ActionAttempt planned = planned(owner, suffix, artifact, now);
    PostgresActionAttemptStore attempts = new PostgresActionAttemptStore(dataSource, transactions);
    assertInstanceOf(
        ActionAttemptStore.PlanResult.Accepted.class, attempts.planApprovalOrFind(planned));
    LocalDraftboxStore.ExecuteResult.Executed executed =
        assertInstanceOf(
            LocalDraftboxStore.ExecuteResult.Executed.class,
            new PostgresLocalDraftboxStore(dataSource, transactions, attempts)
                .executeOwned(
                    owner,
                    planned.attemptId(),
                    planned.approvalScope().scopeSchema(),
                    planned.approvalScope().scopeHash(),
                    "draft-" + suffix,
                    "creation-receipt-" + suffix));
    assertTrue(executed.created());
    return new CreationFixture(
        executed.attempt(), executed.draft(), executed.attempt().localDraftReceipt());
  }

  private static ActionAttempt planned(
      String owner, String suffix, ArtifactLineage artifact, Instant approvedAt) {
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    String planId = "plan-" + suffix;
    String idempotencyKey = "idempotency-" + suffix;
    ActionPlan plan =
        new ActionPlan(
            planId,
            owner,
            LocalDraftboxAuthority.ACTION_TYPE,
            LocalDraftboxAuthority.TARGET_REF,
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            RiskLevel.REVERSIBLE,
            LocalDraftboxAuthority.POLICY_VERSION,
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-" + suffix,
            planId,
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            owner,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-" + suffix,
            owner,
            LocalDraftboxAuthority.CONNECTOR,
            LocalDraftboxAuthority.AUDIENCE,
            LocalDraftboxAuthority.accountRefFor(owner),
            planId,
            plan.planHash(),
            plan.artifactHash(),
            idempotencyKey,
            expiresAt,
            LocalDraftboxAuthority.MAX_CALLS);
    long ttlMicros = Duration.between(approvedAt, expiresAt).toNanos() / 1_000L;
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            owner,
            ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
            ActionApprovalScope.LOCAL_DRAFTBOX_V2,
            plan.actionType(),
            plan.targetRef(),
            plan.artifactId(),
            plan.artifactVersion(),
            plan.artifactHash(),
            plan.risk(),
            plan.policyVersion(),
            capability.connector(),
            capability.audience(),
            capability.accountRef(),
            ttlMicros,
            capability.maxCalls());
    return new ActionAttempt(
        "attempt-" + suffix,
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, approvedAt)),
        null,
        scope);
  }

  private static void seedLegacyActionReceipt(
      DataSource dataSource, JdbcClient jdbc, CreationFixture artifactFixture) {
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    PostgresActionAttemptStore attempts = new PostgresActionAttemptStore(dataSource, transactions);
    Instant approvedAt = databaseNow(jdbc);
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    String connector = "emergeos.synthetic-provider";
    String audience = "emergeos:synthetic-provider";
    String accountRef = "synthetic-provider:" + OWNER;
    String planId = "plan-legacy-receipt-v18";
    String idempotencyKey = "idempotency-legacy-receipt-v18";
    ActionPlan plan =
        new ActionPlan(
            planId,
            OWNER,
            "CREATE_SYNTHETIC_PROVIDER_EFFECT",
            "synthetic-provider://effects/v18",
            artifactFixture.draft().artifactId(),
            artifactFixture.draft().artifactVersion(),
            artifactFixture.draft().artifactHash(),
            RiskLevel.REVERSIBLE,
            "synthetic-provider-v18",
            idempotencyKey,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-legacy-receipt-v18",
            planId,
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            OWNER,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-legacy-receipt-v18",
            OWNER,
            connector,
            audience,
            accountRef,
            planId,
            plan.planHash(),
            plan.artifactHash(),
            idempotencyKey,
            expiresAt,
            1);
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            OWNER,
            ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
            ActionApprovalScope.SIMULATED_PROVIDER_V1,
            plan.actionType(),
            plan.targetRef(),
            plan.artifactId(),
            plan.artifactVersion(),
            plan.artifactHash(),
            plan.risk(),
            plan.policyVersion(),
            connector,
            audience,
            accountRef,
            Duration.between(approvedAt, expiresAt).toNanos() / 1_000L,
            1);
    ActionAttempt proposed =
        new ActionAttempt(
            "attempt-legacy-receipt-v18",
            plan,
            approval,
            capability,
            ActionAttemptStatus.PLANNED,
            0,
            List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, approvedAt)),
            null,
            scope);
    ActionAttemptStore.PlanResult.Accepted accepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class, attempts.planApprovalOrFind(proposed));
    assertTrue(accepted.created());
    ActionAttemptStore.ClaimResult.Claimed dispatch =
        assertInstanceOf(
            ActionAttemptStore.ClaimResult.Claimed.class,
            attempts.claimDispatch(accepted.attempt(), databaseNow(jdbc)));
    Instant terminalAt = databaseNow(jdbc);
    ActionReceipt receipt =
        ActionReceipt.succeeded(
            "legacy-action-receipt-v18",
            proposed.attemptId(),
            "synthetic-provider-effect-v18",
            "synthetic-provider-request-v18",
            "memory://synthetic-redacted-v18",
            terminalAt,
            true);
    ActionAttempt completed = attempts.complete(dispatch.attempt(), receipt, terminalAt);
    assertEquals(ActionAttemptStatus.SUCCEEDED, completed.status());
    assertEquals(receipt, completed.receipt());
  }

  private static void insertUndo(
      JdbcClient jdbc,
      CreationFixture identity,
      String receiptId,
      String undoNonce,
      CreationFixture forgedArtifact) {
    CreationFixture artifactSource = forgedArtifact == null ? identity : forgedArtifact;
    jdbc.sql(
            """
            INSERT INTO local_draft_undo_receipts (
                principal_id, receipt_id, creation_attempt_id, draft_id,
                creation_receipt_id, artifact_id, artifact_version,
                artifact_hash, scope_schema, undo_nonce, occurred_at
            ) VALUES (
                :principalId, :receiptId, :creationAttemptId, :draftId,
                :creationReceiptId, :artifactId, :artifactVersion,
                :artifactHash, :scopeSchema, :undoNonce,
                TIMESTAMPTZ '1900-01-01 00:00:00+00'
            )
            """)
        .param("principalId", identity.draft().principalId())
        .param("receiptId", receiptId)
        .param("creationAttemptId", identity.attempt().attemptId())
        .param("draftId", identity.draft().draftId())
        .param("creationReceiptId", identity.creationReceipt().receiptId())
        .param("artifactId", artifactSource.draft().artifactId())
        .param("artifactVersion", artifactSource.draft().artifactVersion())
        .param("artifactHash", artifactSource.draft().artifactHash())
        .param("scopeSchema", UNDO_SCHEMA)
        .param("undoNonce", undoNonce)
        .update();
  }

  private static UndoRow undoRow(JdbcClient jdbc, String attemptId) {
    return jdbc.sql(
            """
            SELECT principal_id, receipt_id, creation_attempt_id, draft_id,
                   creation_receipt_id, artifact_id, artifact_version, artifact_hash,
                   scope_schema, undo_nonce, scope_hash, receipt_type, effect,
                   retention, outcome, occurred_at, simulated, xmin::text
            FROM local_draft_undo_receipts receipt
            WHERE principal_id = :principalId
              AND creation_attempt_id = :attemptId
            """)
        .param("principalId", OWNER)
        .param("attemptId", attemptId)
        .query(
            (row, ignored) ->
                new UndoRow(
                    row.getString("principal_id"),
                    row.getString("receipt_id"),
                    row.getString("creation_attempt_id"),
                    row.getString("draft_id"),
                    row.getString("creation_receipt_id"),
                    row.getString("artifact_id"),
                    row.getInt("artifact_version"),
                    row.getString("artifact_hash"),
                    row.getString("scope_schema"),
                    row.getString("undo_nonce"),
                    row.getString("scope_hash"),
                    row.getString("receipt_type"),
                    row.getString("effect"),
                    row.getString("retention"),
                    row.getString("outcome"),
                    row.getTimestamp("occurred_at").toInstant(),
                    row.getBoolean("simulated"),
                    row.getString("xmin")))
        .single();
  }

  private static int undoCount(JdbcClient jdbc) {
    return jdbc.sql("SELECT count(*) FROM local_draft_undo_receipts").query(Integer.class).single();
  }

  private static String rawDraftState(JdbcClient jdbc, String draftId) {
    return jdbc.sql("SELECT state FROM local_drafts WHERE draft_id = :draftId")
        .param("draftId", draftId)
        .query(String.class)
        .single();
  }

  private static String sealedUndoRow(JdbcClient jdbc, String attemptId) {
    return jdbc.sql(
            """
            SELECT xmin::text || '|' || to_jsonb(receipt)::text
            FROM local_draft_undo_receipts receipt
            WHERE principal_id = :principalId
              AND creation_attempt_id = :attemptId
            """)
        .param("principalId", OWNER)
        .param("attemptId", attemptId)
        .query(String.class)
        .single();
  }

  private static CreationTruthDigest creationTruth(JdbcClient jdbc) {
    return new CreationTruthDigest(
        sealedRows(jdbc, "captures", "principal_id, capture_id"),
        sealedRows(jdbc, "artifacts", "principal_id, artifact_id"),
        sealedRows(jdbc, "artifact_versions", "principal_id, artifact_id, version"),
        sealedRows(jdbc, "action_attempts", "principal_id, attempt_id"),
        sealedRows(jdbc, "action_attempt_transitions", "principal_id, attempt_id, sequence"),
        sealedRows(jdbc, "local_drafts", "principal_id, draft_id"),
        sealedRows(jdbc, "local_draft_creation_receipts", "principal_id, attempt_id"),
        sealedRows(jdbc, "action_receipts", "principal_id, attempt_id"));
  }

  private static String sealedRows(JdbcClient jdbc, String table, String orderBy) {
    Set<String> allowedTables =
        Set.of(
            "captures",
            "artifacts",
            "artifact_versions",
            "action_attempts",
            "action_attempt_transitions",
            "local_drafts",
            "local_draft_creation_receipts",
            "action_receipts");
    if (!allowedTables.contains(table)) {
      throw new IllegalArgumentException("unsupported truth table");
    }
    return jdbc.sql(
            "SELECT COALESCE(jsonb_agg(jsonb_build_object('xmin', xmin::text, 'row', "
                + "to_jsonb(source)) ORDER BY "
                + orderBy
                + ")::text, '[]') FROM "
                + table
                + " source")
        .query(String.class)
        .single();
  }

  private static String independentScopeHash(CreationFixture fixture, String undoNonce) {
    List<String> fields =
        List.of(
            "CONFIGURED_LOCAL_PRINCIPAL",
            fixture.draft().principalId(),
            "EXPLICIT_LOCAL_OWNER_INPUT",
            "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
            "LOGICALLY_UNDO_LOCAL_DRAFT",
            "local://drafts/" + fixture.draft().draftId(),
            fixture.draft().draftId(),
            fixture.attempt().attemptId(),
            fixture.creationReceipt().receiptId(),
            fixture.draft().artifactId(),
            Integer.toString(fixture.draft().artifactVersion()),
            fixture.draft().artifactHash(),
            "ACTIVE",
            "CAPTURE_ARTIFACT_HISTORY_RETAINED",
            "local-draft-undo-v1",
            "emergeos.local-draftbox",
            "emergeos:local-draftbox",
            "local-draftbox:" + fixture.draft().principalId(),
            undoNonce,
            "1");
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    canonical.writeBytes(UNDO_SCHEMA.getBytes(StandardCharsets.UTF_8));
    canonical.write(0);
    for (String field : fields) {
      byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
      canonical.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
      canonical.writeBytes(utf8);
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  private static String sqlState(DataAccessException failure) {
    return assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getSQLState();
  }

  private static Instant databaseNow(JdbcClient jdbc) {
    return jdbc.sql("SELECT date_trunc('microseconds', clock_timestamp())")
        .query(Timestamp.class)
        .single()
        .toInstant();
  }

  private static String currentVersion(JdbcClient jdbc) {
    return jdbc.sql(
            """
            SELECT version
            FROM flyway_schema_history
            WHERE success
            ORDER BY installed_rank DESC
            LIMIT 1
            """)
        .query(String.class)
        .single();
  }

  private static Flyway flyway(DataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private static DataSource freshDatabaseDataSource() throws Exception {
    try (Connection connection =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement sql = connection.createStatement()) {
      sql.execute("CREATE DATABASE " + DATABASE);
    }
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setServerNames(new String[] {POSTGRES.getHost()});
    source.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)});
    source.setDatabaseName(DATABASE);
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private record CreationFixture(
      ActionAttempt attempt, LocalDraft draft, LocalDraftCreationReceipt creationReceipt) {}

  private record CreationTruthDigest(
      String captures,
      String artifacts,
      String artifactVersions,
      String attempts,
      String transitions,
      String drafts,
      String creationReceipts,
      String legacyActionReceipts) {}

  private record UndoRow(
      String principalId,
      String receiptId,
      String creationAttemptId,
      String draftId,
      String creationReceiptId,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String scopeSchema,
      String undoNonce,
      String scopeHash,
      String receiptType,
      String effect,
      String retention,
      String outcome,
      Instant occurredAt,
      boolean simulated,
      String xmin) {}

  private record ColumnShape(
      int position,
      String name,
      String dataType,
      boolean notNull,
      String generated,
      String expression) {}

  private record ConstraintShape(
      String name,
      String type,
      String keyColumns,
      String referenceRelation,
      String referenceColumns,
      boolean deferrable,
      boolean initiallyDeferred,
      boolean enforced,
      boolean validated,
      boolean local,
      int inheritedCount,
      boolean noInherit,
      boolean nullsNotDistinct,
      boolean period,
      String matchType,
      String updateType,
      String deleteType,
      String checkExpression) {}

  private record TriggerShape(
      String name,
      String enabled,
      int type,
      String attributes,
      String qualifier,
      String arguments,
      boolean constraint,
      boolean deferrable,
      boolean initiallyDeferred,
      String guardFunction) {}

  private record FunctionShape(
      String name,
      String identityArguments,
      String resultType,
      String language,
      String volatility,
      String parallel,
      String kind,
      boolean returnsSet,
      boolean strict,
      boolean leakproof,
      boolean securityDefiner,
      String configuration,
      String owner,
      String exactAcl) {}

  private record CatalogSnapshot(
      String schemas,
      String relations,
      String attributes,
      String constraints,
      String triggers,
      String functions,
      String types,
      String defaultAcls) {}
}
