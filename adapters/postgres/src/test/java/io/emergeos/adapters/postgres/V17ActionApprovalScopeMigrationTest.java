package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.LocalActionAuthority;
import io.emergeos.core.application.RecoverableActionService;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionPlan;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V17ActionApprovalScopeMigrationTest {

  private static final String DATABASE = "v17_historical_scope";
  private static final String HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_v17_scope")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void populatedV16UpgradeMarksHistoryUnprovenAndNeverExplicit() throws Exception {
    DataSource dataSource = freshDatabaseDataSource();
    Flyway v16 = flyway(dataSource, "16");
    assertEquals(16, v16.migrate().migrationsExecuted);
    ActionPlan zeroTtlPlan = zeroTtlPlan();
    seedHistoricalPlannedAttempt(dataSource, zeroTtlPlan.planHash());
    String zeroTtlPlanHashBeforeMigration =
        JdbcClient.create(dataSource)
            .sql(
                "SELECT plan_hash FROM action_attempts "
                    + "WHERE attempt_id='attempt-expired-at-approval'")
            .query(String.class)
            .single();

    Flyway v17 = flyway(dataSource, "17");
    assertEquals(1, v17.migrate().migrationsExecuted);
    JdbcClient jdbc = JdbcClient.create(dataSource);

    assertEquals(
        ActionApprovalScope.PRE_V17_UNPROVEN,
        jdbc.sql("SELECT approval_origin FROM action_attempts WHERE attempt_id='attempt-history'")
            .query(String.class)
            .single());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        jdbc.sql("SELECT execution_route FROM action_attempts WHERE attempt_id='attempt-history'")
            .query(String.class)
            .single());
    assertEquals(
        0,
        jdbc.sql(
                "SELECT count(*) FROM action_attempts "
                    + "WHERE approval_origin='EXPLICIT_LOCAL_OWNER_INPUT'")
            .query(Integer.class)
            .single());
    assertEquals(
        300_000_000L,
        jdbc.sql(
                "SELECT scope_capability_ttl_micros FROM action_attempts "
                    + "WHERE attempt_id='attempt-history'")
            .query(Long.class)
            .single());

    ActionApprovalScope expected =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            "owner-history",
            ActionApprovalScope.PRE_V17_UNPROVEN,
            ActionApprovalScope.SIMULATED_PROVIDER_V1,
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            "artifact-history",
            1,
            HASH,
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            "simulated.local-draft",
            "adapter:simulated-provider",
            "simulated-account:owner-history",
            300_000_000L,
            2);
    assertEquals(
        expected.scopeHash(),
        jdbc.sql("SELECT scope_hash FROM action_attempts WHERE attempt_id='attempt-history'")
            .query(String.class)
            .single());
    assertEquals(
        0L,
        jdbc.sql(
                "SELECT scope_capability_ttl_micros FROM action_attempts "
                    + "WHERE attempt_id='attempt-expired-at-approval'")
            .query(Long.class)
            .single());
    assertEquals(
        ActionApprovalScope.PRE_V17_UNPROVEN,
        jdbc.sql(
                "SELECT approval_origin FROM action_attempts "
                    + "WHERE attempt_id='attempt-expired-at-approval'")
            .query(String.class)
            .single());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        jdbc.sql(
                "SELECT execution_route FROM action_attempts "
                    + "WHERE attempt_id='attempt-expired-at-approval'")
            .query(String.class)
            .single());
    assertEquals(
        zeroTtlPlanHashBeforeMigration,
        jdbc.sql(
                "SELECT plan_hash FROM action_attempts "
                    + "WHERE attempt_id='attempt-expired-at-approval'")
            .query(String.class)
            .single());
    assertEquals(zeroTtlPlan.planHash(), zeroTtlPlanHashBeforeMigration);

    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    PostgresActionAttemptStore attempts =
        new PostgresActionAttemptStore(dataSource, transactions);
    ActionAttempt historical =
        attempts.findOwned("owner-history", "attempt-expired-at-approval").orElseThrow();
    assertEquals(ActionAttemptStatus.PLANNED, historical.status());
    assertEquals(ActionApprovalScope.PRE_V17_UNPROVEN,
        historical.approvalScope().approvalOrigin());
    assertEquals(ActionApprovalScope.SIMULATED_PROVIDER_V1,
        historical.approvalScope().executionRoute());
    assertEquals(0L, historical.approvalScope().capabilityTtlMicros());
    assertEquals(zeroTtlPlanHashBeforeMigration, historical.plan().planHash());
    assertEquals(0, historical.capabilityUsedCalls());
    assertNull(historical.receipt());

    AtomicInteger providerCalls = new AtomicInteger();
    RecoverableActionService service =
        new RecoverableActionService(
            attempts,
            new PostgresArtifactLineageStore(dataSource, transactions),
            request -> {
              providerCalls.incrementAndGet();
              throw new AssertionError("unproven historical approval must never execute");
            },
            prefix -> prefix + "-unused",
            Clock.fixed(Instant.parse("2026-08-13T10:04:00Z"), ZoneOffset.UTC),
            new LocalActionAuthority(
                "owner-history",
                "simulated.local-draft",
                "adapter:simulated-provider",
                "simulated-account:expired-history",
                "CREATE_LOCAL_DRAFT",
                "local://drafts",
                RiskLevel.REVERSIBLE,
                "local-action-v1",
                Duration.ofMinutes(5),
                2));
    assertThrows(
        NoSuchElementException.class,
        () -> service.getPlannedExplicitApproval(historical.attemptId()));
    assertEquals(0, providerCalls.get());
  }

  private static void seedHistoricalPlannedAttempt(
      DataSource dataSource, String zeroTtlPlanHash)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.executeUpdate(
          "INSERT INTO captures VALUES ("
              + "'owner-history','capture-history','nonce-history','"
              + HASH
              + "','historical capture','TEXT','v17:test','PERSONAL',"
              + "TIMESTAMPTZ '2026-08-13 10:00:00Z')");
      sql.executeUpdate(
          "INSERT INTO artifacts VALUES ("
              + "'owner-history','artifact-history','capture-history',1,'"
              + HASH
              + "')");
      sql.executeUpdate(
          "INSERT INTO artifact_versions VALUES ("
              + "'owner-history','artifact-history',1,'historical artifact','"
              + HASH
              + "',NULL,NULL,TIMESTAMPTZ '2026-08-13 10:01:00Z')");
      sql.executeUpdate(
          """
          INSERT INTO action_attempts (
              principal_id, attempt_id, connector, account_ref, idempotency_key,
              status, state_version, capability_used_calls, capability_max_calls,
              plan_id, plan_hash, action_type, target_ref,
              artifact_id, artifact_version, artifact_hash,
              risk, policy_version, plan_expires_at,
              approval_id, approved_at,
              capability_id, capability_subject, capability_connector,
              capability_audience, capability_account_ref,
              capability_plan_id, capability_plan_hash, capability_artifact_hash,
              capability_idempotency_key, capability_expires_at,
              created_at, updated_at
          ) VALUES (
              'owner-history', 'attempt-history', 'simulated.local-draft',
              'simulated-account:owner-history', 'history-key',
              'PLANNED', 1, 0, 2,
              'plan-history', '%s', 'CREATE_LOCAL_DRAFT', 'local://drafts',
              'artifact-history', 1, '%s',
              'REVERSIBLE', 'local-action-v1', TIMESTAMPTZ '2026-08-13 10:07:00Z',
              'approval-history', TIMESTAMPTZ '2026-08-13 10:02:00Z',
              'capability-history', 'owner-history', 'simulated.local-draft',
              'adapter:simulated-provider', 'simulated-account:owner-history',
              'plan-history', '%s', '%s', 'history-key',
              TIMESTAMPTZ '2026-08-13 10:07:00Z',
              TIMESTAMPTZ '2026-08-13 10:02:00Z', TIMESTAMPTZ '2026-08-13 10:02:00Z'
          )
          """
              .formatted(HASH, HASH, HASH, HASH));
      sql.executeUpdate(
          "INSERT INTO action_attempt_transitions VALUES ("
              + "'owner-history','attempt-history',1,NULL,'PLANNED',0,"
              + "TIMESTAMPTZ '2026-08-13 10:02:00Z')");
      sql.executeUpdate(
          """
          INSERT INTO action_attempts (
              principal_id, attempt_id, connector, account_ref, idempotency_key,
              status, state_version, capability_used_calls, capability_max_calls,
              plan_id, plan_hash, action_type, target_ref,
              artifact_id, artifact_version, artifact_hash,
              risk, policy_version, plan_expires_at,
              approval_id, approved_at,
              capability_id, capability_subject, capability_connector,
              capability_audience, capability_account_ref,
              capability_plan_id, capability_plan_hash, capability_artifact_hash,
              capability_idempotency_key, capability_expires_at,
              created_at, updated_at
          ) VALUES (
              'owner-history', 'attempt-expired-at-approval', 'simulated.local-draft',
              'simulated-account:expired-history', 'expired-history-key',
              'PLANNED', 1, 0, 2,
              'plan-expired-history', '%s', 'CREATE_LOCAL_DRAFT', 'local://drafts',
              'artifact-history', 1, '%s',
              'REVERSIBLE', 'local-action-v1', TIMESTAMPTZ '2026-08-13 10:03:00Z',
              'approval-expired-history', TIMESTAMPTZ '2026-08-13 10:03:00Z',
              'capability-expired-history', 'owner-history', 'simulated.local-draft',
              'adapter:simulated-provider', 'simulated-account:expired-history',
              'plan-expired-history', '%s', '%s', 'expired-history-key',
              TIMESTAMPTZ '2026-08-13 10:03:00Z',
              TIMESTAMPTZ '2026-08-13 10:03:00Z', TIMESTAMPTZ '2026-08-13 10:03:00Z'
          )
          """
              .formatted(zeroTtlPlanHash, HASH, zeroTtlPlanHash, HASH));
      sql.executeUpdate(
          "INSERT INTO action_attempt_transitions VALUES ("
              + "'owner-history','attempt-expired-at-approval',1,NULL,'PLANNED',0,"
              + "TIMESTAMPTZ '2026-08-13 10:03:00Z')");
      connection.commit();
    }
  }

  private static ActionPlan zeroTtlPlan() {
    return new ActionPlan(
        "plan-expired-history",
        "owner-history",
        "CREATE_LOCAL_DRAFT",
        "local://drafts",
        "artifact-history",
        1,
        HASH,
        RiskLevel.REVERSIBLE,
        "local-action-v1",
        "expired-history-key",
        Instant.parse("2026-08-13T10:03:00Z"));
  }

  private static Flyway flyway(DataSource dataSource, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private static DataSource freshDatabaseDataSource() throws Exception {
    try (Connection connection = java.sql.DriverManager.getConnection(
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
}
