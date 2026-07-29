package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresStage1OperationsProbeTest {

  private static final String OWNER = "operations-owner";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_operations")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DriverManagerDataSource dataSource;
  private static DataSourceTransactionManager transactions;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrateAndPopulate() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    transactions = new DataSourceTransactionManager(dataSource);
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);

    ArtifactLineage ownerArtifact = artifact(OWNER);
    persist(ownerArtifact, ActionAttemptStatus.PLANNED, "planned");
    persist(ownerArtifact, ActionAttemptStatus.DISPATCHING, "dispatching");
    persist(ownerArtifact, ActionAttemptStatus.UNKNOWN, "unknown");
    persist(ownerArtifact, ActionAttemptStatus.RECONCILING, "reconciling");
    persist(ownerArtifact, ActionAttemptStatus.SUCCEEDED, "succeeded");
    persist(ownerArtifact, ActionAttemptStatus.FAILED, "failed");

    ArtifactLineage foreignArtifact = artifact("other-operations-owner");
    persist(foreignArtifact, ActionAttemptStatus.UNKNOWN, "foreign-unknown");
  }

  @Test
  void reportsOwnerScopedUnresolvedAndTerminalCountsWithoutMutatingTruth() {
    int transitionsBefore = count("action_attempt_transitions");
    int receiptsBefore = count("action_receipts");

    PostgresStage1OperationsProbe.Snapshot snapshot =
        new PostgresStage1OperationsProbe(dataSource).snapshot(OWNER);

    assertEquals(1, snapshot.planned());
    assertEquals(1, snapshot.dispatching());
    assertEquals(1, snapshot.unknown());
    assertEquals(1, snapshot.reconciling());
    assertEquals(1, snapshot.succeeded());
    assertEquals(1, snapshot.failed());
    assertEquals(4, snapshot.unresolved());
    assertEquals(transitionsBefore, count("action_attempt_transitions"));
    assertEquals(receiptsBefore, count("action_receipts"));
  }

  private static void persist(
      ArtifactLineage artifact, ActionAttemptStatus target, String suffix) {
    PostgresActionAttemptStore store =
        new PostgresActionAttemptStore(dataSource, transactions);
    ActionAttempt proposed = planned(artifact, suffix);
    ActionAttempt canonical =
        assertInstanceOf(
                ActionAttemptStore.PlanResult.Accepted.class,
                store.planOrFind(proposed))
            .attempt();
    if (target == ActionAttemptStatus.PLANNED) {
      return;
    }
    ActionAttempt dispatching =
        assertInstanceOf(
                ActionAttemptStore.ClaimResult.Claimed.class,
                store.claimDispatch(canonical, NOW.plusSeconds(10)))
            .attempt();
    if (target == ActionAttemptStatus.DISPATCHING) {
      return;
    }
    if (target == ActionAttemptStatus.SUCCEEDED) {
      store.complete(
          dispatching,
          ActionReceipt.succeeded(
              "receipt-" + suffix,
              dispatching.attemptId(),
              "sim-object-" + suffix,
              "sim-request-" + suffix,
              "simulated://provider/" + suffix,
              NOW.plusSeconds(20),
              true),
          NOW.plusSeconds(20));
      return;
    }
    if (target == ActionAttemptStatus.FAILED) {
      store.complete(
          dispatching,
          ActionReceipt.failed(
              "receipt-" + suffix,
              dispatching.attemptId(),
              "SYNTHETIC_FAILURE",
              "sim-request-" + suffix,
              "simulated://provider/" + suffix,
              NOW.plusSeconds(20),
              true),
          NOW.plusSeconds(20));
      return;
    }
    ActionAttempt unknown = store.markUnknown(dispatching, NOW.plusSeconds(20));
    if (target == ActionAttemptStatus.UNKNOWN) {
      return;
    }
    assertEquals(ActionAttemptStatus.RECONCILING, target);
    assertInstanceOf(
        ActionAttemptStore.ClaimResult.Claimed.class,
        store.claimReconciliation(unknown, NOW.plusSeconds(30)));
  }

  private static ActionAttempt planned(ArtifactLineage artifact, String suffix) {
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
            "operations-key-" + suffix,
            NOW.plusSeconds(300));
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-" + suffix,
            plan.planId(),
            plan.planHash(),
            plan.artifactHash(),
            "APPROVED",
            plan.principalId(),
            NOW);
    String account = "simulated-account:" + artifact.principalId();
    ActionCapability capability =
        new ActionCapability(
            "capability-" + suffix,
            plan.principalId(),
            CONNECTOR,
            "adapter:simulated-provider",
            account,
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
        List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, NOW)),
        null);
  }

  private static ArtifactLineage artifact(String principalId) {
    String suffix = principalId.replace(':', '-');
    String captureContent = "synthetic operations capture for " + suffix;
    Capture capture =
        new Capture(
            "capture-" + suffix,
            principalId,
            "nonce-" + suffix,
            CaptureRequestHashes.sha256(
                captureContent,
                CaptureSourceType.TEXT,
                "s4-operations-probe",
                DataClass.PERSONAL),
            captureContent,
            CaptureSourceType.TEXT,
            "s4-operations-probe",
            DataClass.PERSONAL,
            NOW.minusSeconds(120));
    new PostgresCaptureStore(dataSource, transactions).saveOrFindByNonce(capture);
    String artifactContent = "synthetic operations artifact for " + suffix;
    return new PostgresArtifactLineageStore(dataSource, transactions)
        .create(
            new ArtifactLineage(
                "artifact-" + suffix,
                principalId,
                capture.captureId(),
                List.of(
                    new ArtifactLineageEntry(
                        1,
                        artifactContent,
                        ContentHashes.sha256(artifactContent),
                        null,
                        null,
                        NOW.minusSeconds(60)))));
  }

  private static int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }
}
