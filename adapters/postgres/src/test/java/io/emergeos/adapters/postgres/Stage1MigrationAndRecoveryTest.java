package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
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
import io.emergeos.core.port.ActionAttemptStore;
import io.emergeos.core.port.ArtifactLineageStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Stage1MigrationAndRecoveryTest {

  private static final String SOURCE_DATABASE = "emerge_stage1_evidence";
  private static final String RESTORED_DATABASE = "emerge_stage1_restored";
  private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
  private static final List<TableSnapshotSpec> S1_TABLES =
      List.of(new TableSnapshotSpec("captures", "principal_id, capture_id"));
  private static final List<TableSnapshotSpec> S2_TABLES =
      List.of(
          new TableSnapshotSpec("captures", "principal_id, capture_id"),
          new TableSnapshotSpec("artifacts", "principal_id, artifact_id"),
          new TableSnapshotSpec(
              "artifact_versions", "principal_id, artifact_id, version"));
  private static final List<TableSnapshotSpec> S3_TABLES =
      List.of(
          new TableSnapshotSpec("captures", "principal_id, capture_id"),
          new TableSnapshotSpec("artifacts", "principal_id, artifact_id"),
          new TableSnapshotSpec(
              "artifact_versions", "principal_id, artifact_id, version"),
          new TableSnapshotSpec("action_attempts", "principal_id, attempt_id"),
          new TableSnapshotSpec(
              "action_attempt_transitions",
              "principal_id, attempt_id, sequence"),
          new TableSnapshotSpec("action_receipts", "principal_id, attempt_id"));

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName(SOURCE_DATABASE)
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void populatedV1V2V3AndFreshInstallReachCurrentWithoutTruthDrift() {
    DriverManagerDataSource fromV1 = schemaDataSource(SOURCE_DATABASE, "from_v1");
    Flyway v1 = flyway(fromV1, "from_v1", "1");
    v1.migrate();
    populateCapture(fromV1, "v1");
    DatabaseSnapshot v1Before = snapshot(fromV1, S1_TABLES);
    Flyway v1Current = flyway(fromV1, "from_v1", null);
    v1Current.migrate();
    assertCurrent(v1Current);
    assertEquals(v1Before, snapshot(fromV1, S1_TABLES));

    DriverManagerDataSource fromV2 = schemaDataSource(SOURCE_DATABASE, "from_v2");
    Flyway v2 = flyway(fromV2, "from_v2", "2");
    v2.migrate();
    ArtifactLineage v2Artifact = populateArtifact(fromV2, "v2");
    reviseArtifact(fromV2, v2Artifact, "v2");
    DatabaseSnapshot v2Before = snapshot(fromV2, S2_TABLES);
    Flyway v2Current = flyway(fromV2, "from_v2", null);
    v2Current.migrate();
    assertCurrent(v2Current);
    assertEquals(v2Before, snapshot(fromV2, S2_TABLES));

    DriverManagerDataSource fromV3 = schemaDataSource(SOURCE_DATABASE, "from_v3");
    Flyway v3 = flyway(fromV3, "from_v3", "3");
    v3.migrate();
    ArtifactLineage v3Artifact = populateArtifact(fromV3, "v3");
    ArtifactLineage v3Revised = reviseArtifact(fromV3, v3Artifact, "v3");
    populateSucceededAction(fromV3, v3Revised, "v3");
    DatabaseSnapshot v3Before = snapshot(fromV3, S3_TABLES);
    Flyway v3Current = flyway(fromV3, "from_v3", null);
    v3Current.migrate();
    assertCurrent(v3Current);
    assertEquals(v3Before, snapshot(fromV3, S3_TABLES));

    DriverManagerDataSource fresh =
        schemaDataSource(SOURCE_DATABASE, "fresh_current");
    Flyway freshCurrent = flyway(fresh, "fresh_current", null);
    freshCurrent.migrate();
    assertCurrent(freshCurrent);
    assertEquals(6, businessTableCount(fresh));

    System.out.println(
        "S4_MIGRATION_RECEIPT fromV1=V3-stable fromV2=V3-stable "
            + "fromV3=V3-stable fresh=V3 tables=6 synthetic=true");
  }

  @Test
  void restoresSyntheticStage1TruthIntoANewDatabaseAfterDestructiveLoss()
      throws Exception {
    String schema = "game_day";
    DriverManagerDataSource source = schemaDataSource(SOURCE_DATABASE, schema);
    Flyway sourceFlyway = flyway(source, schema, null);
    sourceFlyway.migrate();
    ArtifactLineage artifact = populateArtifact(source, "game-day");
    ArtifactLineage revised = reviseArtifact(source, artifact, "game-day");
    populateSucceededAction(source, revised, "game-day");
    DatabaseSnapshot expected = snapshot(source, S3_TABLES);

    String archive = "/tmp/emerge-stage1-s4.dump";
    assertExecSuccess(
        POSTGRES.execInContainer(
            "pg_dump",
            "-Fc",
            "--no-owner",
            "--no-privileges",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            SOURCE_DATABASE,
            "-n",
            schema,
            "-f",
            archive),
        "pg_dump");

    JdbcClient.create(source).sql("TRUNCATE TABLE captures CASCADE").update();
    assertEquals(0, rowCount(source, "captures"));
    assertFalse(expected.equals(snapshot(source, S3_TABLES)));

    long recoveryStarted = System.nanoTime();
    assertExecSuccess(
        POSTGRES.execInContainer(
            "createdb",
            "-U",
            POSTGRES.getUsername(),
            "-T",
            "template0",
            RESTORED_DATABASE),
        "createdb");
    assertExecSuccess(
        POSTGRES.execInContainer(
            "pg_restore",
            "--single-transaction",
            "--exit-on-error",
            "--no-owner",
            "--no-privileges",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            RESTORED_DATABASE,
            archive),
        "pg_restore");

    DriverManagerDataSource restored =
        schemaDataSource(RESTORED_DATABASE, schema);
    Flyway restoredFlyway = flyway(restored, schema, null);
    assertCurrent(restoredFlyway);
    assertEquals(expected, snapshot(restored, S3_TABLES));
    long recoveryMillis =
        Duration.ofNanos(System.nanoTime() - recoveryStarted).toMillis();

    System.out.printf(
        "S4_BACKUP_RESTORE_RECEIPT format=custom failure=synthetic-truncate "
            + "destination=new-database validation=green truth=stable recoveryMillis=%d "
            + "synthetic=true%n",
        recoveryMillis);
  }

  private static Capture populateCapture(
      DriverManagerDataSource dataSource, String suffix) {
    String content = "synthetic Stage 1 capture " + suffix;
    Capture capture =
        new Capture(
            "capture-" + suffix,
            "owner-" + suffix,
            "nonce-" + suffix,
            CaptureRequestHashes.sha256(
                content,
                CaptureSourceType.TEXT,
                "s4-migration-evidence",
                DataClass.PERSONAL),
            content,
            CaptureSourceType.TEXT,
            "s4-migration-evidence",
            DataClass.PERSONAL,
            NOW);
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    new PostgresCaptureStore(dataSource, transactions)
        .saveOrFindByNonce(capture);
    return capture;
  }

  private static ArtifactLineage populateArtifact(
      DriverManagerDataSource dataSource, String suffix) {
    Capture capture = populateCapture(dataSource, suffix);
    String content = "synthetic Stage 1 artifact v1 " + suffix;
    return new PostgresArtifactLineageStore(
            dataSource, new DataSourceTransactionManager(dataSource))
        .create(
            new ArtifactLineage(
                "artifact-" + suffix,
                capture.principalId(),
                capture.captureId(),
                List.of(
                    new ArtifactLineageEntry(
                        1,
                        content,
                        ContentHashes.sha256(content),
                        null,
                        null,
                        NOW.plusSeconds(60)))));
  }

  private static ArtifactLineage reviseArtifact(
      DriverManagerDataSource dataSource,
      ArtifactLineage artifact,
      String suffix) {
    String content = "synthetic Stage 1 artifact v2 " + suffix;
    ArtifactLineageStore.RevisionResult revised =
        new PostgresArtifactLineageStore(
                dataSource, new DataSourceTransactionManager(dataSource))
            .compareAndSwap(
                artifact.principalId(),
                artifact.artifactId(),
                artifact.current().version(),
                artifact.current().contentHash(),
                new ArtifactLineageEntry(
                    2,
                    content,
                    ContentHashes.sha256(content),
                    artifact.current().version(),
                    artifact.current().contentHash(),
                    NOW.plusSeconds(120)));
    return assertInstanceOf(
            ArtifactLineageStore.RevisionResult.Revised.class, revised)
        .lineage();
  }

  private static void populateSucceededAction(
      DriverManagerDataSource dataSource,
      ArtifactLineage artifact,
      String suffix) {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    PostgresActionAttemptStore store =
        new PostgresActionAttemptStore(dataSource, transactions);
    ActionAttempt proposed = planned(artifact, suffix);
    ActionAttempt canonical =
        assertInstanceOf(
                ActionAttemptStore.PlanResult.Accepted.class,
                store.planOrFind(proposed))
            .attempt();
    ActionAttempt dispatching =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                store.claimDispatch(canonical, NOW.plusSeconds(180)))
            .attempt();
    ActionAttempt unknown =
        store.markUnknown(dispatching, NOW.plusSeconds(181));
    ActionAttempt reconciling =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                store.claimReconciliation(unknown, NOW.plusSeconds(182)))
            .attempt();
    store.complete(
        reconciling,
        ActionReceipt.succeeded(
            "receipt-" + suffix,
            proposed.attemptId(),
            "sim-object-" + suffix,
            "sim-request-" + suffix,
            "simulated://provider/" + suffix,
            NOW.plusSeconds(183),
            true),
        NOW.plusSeconds(183));
  }

  private static ActionAttempt planned(
      ArtifactLineage artifact, String suffix) {
    ActionPlan plan =
        new ActionPlan(
            "plan-" + suffix,
            artifact.principalId(),
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            artifact.artifactId(),
            artifact.current().version(),
            artifact.current().contentHash(),
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            "action-key-" + suffix,
            NOW.plusSeconds(600));
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-" + suffix,
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            plan.principalId(),
            NOW.plusSeconds(170));
    ActionCapability capability =
        new ActionCapability(
            "capability-" + suffix,
            plan.principalId(),
            "simulated.local-draft",
            "adapter:simulated-provider",
            "simulated-account:" + plan.principalId(),
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            plan.idempotencyKey(),
            plan.expiresAt(),
            2);
    return new ActionAttempt(
        "attempt-" + suffix,
        plan,
        approval,
        capability,
        ActionAttemptStatus.PLANNED,
        0,
        List.of(
            new ActionTransition(
                1, null, ActionAttemptStatus.PLANNED, NOW.plusSeconds(170))),
        null);
  }

  private static DatabaseSnapshot snapshot(
      DriverManagerDataSource dataSource,
      List<TableSnapshotSpec> tables) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    for (TableSnapshotSpec table : tables) {
      rows.put(
          table.table(),
          jdbc.sql(
                  "SELECT * FROM "
                      + table.table()
                      + " ORDER BY "
                      + table.orderBy())
              .query()
              .listOfRows());
    }
    return new DatabaseSnapshot(Map.copyOf(rows));
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource,
      String schema,
      String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    return configuration.load();
  }

  private static void assertCurrent(Flyway flyway) {
    assertTrue(flyway.validateWithResult().validationSuccessful);
    assertEquals(
        MigrationVersion.fromVersion("3"),
        flyway.info().current().getVersion());
    assertEquals(0, flyway.info().pending().length);
  }

  private static DriverManagerDataSource schemaDataSource(
      String database, String schema) {
    return new DriverManagerDataSource(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + database
            + "?currentSchema="
            + schema,
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private static int businessTableCount(
      DriverManagerDataSource dataSource) {
    return JdbcClient.create(dataSource)
        .sql(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = current_schema()
              AND table_name IN (
                'captures', 'artifacts', 'artifact_versions',
                'action_attempts', 'action_attempt_transitions', 'action_receipts'
              )
            """)
        .query(Integer.class)
        .single();
  }

  private static int rowCount(
      DriverManagerDataSource dataSource, String table) {
    return JdbcClient.create(dataSource)
        .sql("SELECT count(*) FROM " + table)
        .query(Integer.class)
        .single();
  }

  private static void assertExecSuccess(
      org.testcontainers.containers.Container.ExecResult result,
      String operation) {
    assertEquals(
        0,
        result.getExitCode(),
        () ->
            operation
                + " failed\nstdout:\n"
                + result.getStdout()
                + "\nstderr:\n"
                + result.getStderr());
  }

  private record TableSnapshotSpec(String table, String orderBy) {}

  private record DatabaseSnapshot(
      Map<String, List<Map<String, Object>>> rows) {}
}
