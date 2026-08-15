package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.port.ActionAttemptStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V18LocalDraftboxMigrationTest {

  private static final String DATABASE = "v18_local_draftbox_routes";
  private static final String OWNER = "v18-route-owner";
  private static final String ARTIFACT_ID = "artifact-v18-routes";
  private static final String ARTIFACT_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final String AUDIENCE = "adapter:simulated-provider";
  private static final String ACCOUNT = "simulated-account:v18-route-owner";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_v18_routes")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void v18FencesRetiredPostPlanRoutesButPreservesLegacySimulatedStateMachine()
      throws Exception {
    DataSource dataSource = freshDatabaseDataSource();
    assertEquals(16, flyway(dataSource, "16").migrate().migrationsExecuted);
    Instant historicalApproval = Instant.parse("2026-08-14T00:00:00Z");
    seedV16HistoricalUnknownAttempt(dataSource, historicalApproval);

    assertEquals(1, flyway(dataSource, "17").migrate().migrationsExecuted);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    PostgresActionAttemptStore attempts =
        new PostgresActionAttemptStore(dataSource, transactions);
    assertEquals(
        ActionApprovalScope.PRE_V17_UNPROVEN,
        jdbc.sql(
                "SELECT approval_origin FROM action_attempts "
                    + "WHERE attempt_id = 'attempt-pre-v17'")
            .query(String.class)
            .single());

    Instant v1Approval = databaseNow(jdbc);
    ActionAttempt v1 =
        planned(
            "v1",
            v1Approval,
            ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
            ActionApprovalScope.LOCAL_DRAFTBOX_V1);
    assertInstanceOf(
        ActionAttemptStore.PlanResult.Accepted.class,
        attempts.planApprovalOrFind(v1));

    assertEquals(1, Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted);
    assertEquals(
        ActionApprovalScope.LOCAL_DRAFTBOX_V1,
        jdbc.sql("SELECT execution_route FROM action_attempts WHERE attempt_id = :attemptId")
            .param("attemptId", v1.attemptId())
            .query(String.class)
            .single());
    assertEquals(
        ActionApprovalScope.SIMULATED_PROVIDER_V1,
        jdbc.sql(
                "SELECT execution_route FROM action_attempts "
                    + "WHERE attempt_id = 'attempt-pre-v17'")
            .query(String.class)
            .single());

    AttemptDigest v1Before = digest(jdbc, v1.attemptId());
    AttemptDigest preV17Before = digest(jdbc, "attempt-pre-v17");
    assertEquals("UNKNOWN", preV17Before.head().status());
    assertEquals(3, preV17Before.head().stateVersion());
    assertEquals(1, preV17Before.head().usedCalls());
    assertEquals(
        List.of("PLANNED", "DISPATCHING", "UNKNOWN"),
        preV17Before.transitions().stream().map(TransitionHead::toStatus).toList());
    assertEquals(
        List.of(0, 1, 0),
        preV17Before.transitions().stream().map(TransitionHead::useDelta).toList());
    ActionAttempt preV17Unknown =
        attempts.findOwned(OWNER, "attempt-pre-v17").orElseThrow();
    assertEquals(ActionAttemptStatus.UNKNOWN, preV17Unknown.status());
    assertEquals(1, preV17Unknown.capabilityUsedCalls());
    ActionAttemptStore.ClaimResult preV17Claim =
        attempts.claimReconciliation(
            preV17Unknown, historicalApproval.plusSeconds(3));
    assertInstanceOf(
        ActionAttemptStore.ClaimResult.Rejected.class,
        preV17Claim,
        "PRE_V17_UNPROVEN_RECONCILIATION_CLAIMED");
    assertEquals(preV17Before, digest(jdbc, "attempt-pre-v17"));

    String v1SqlState = rawDispatchCommitSqlState(dataSource, v1.attemptId());
    String preV17SqlState =
        rawReconciliationCommitSqlState(
            dataSource, "attempt-pre-v17", historicalApproval.plusSeconds(3));

    Instant legacyApproval = databaseNow(jdbc);
    ActionAttempt legacy =
        planned(
            "legacy-control",
            legacyApproval,
            ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
            ActionApprovalScope.SIMULATED_PROVIDER_V1);
    ActionAttemptStore.PlanResult.Accepted legacyAccepted =
        assertInstanceOf(
            ActionAttemptStore.PlanResult.Accepted.class,
            attempts.planOrFind(legacy));
    ActionAttemptStore.ClaimResult.Claimed claimed =
        assertInstanceOf(
            ActionAttemptStore.ClaimResult.Claimed.class,
            attempts.claimDispatch(
                legacyAccepted.attempt(), legacyApproval.plusMillis(1)));
    ActionAttempt completed =
        attempts.complete(
            claimed.attempt(),
            ActionReceipt.succeeded(
                "receipt-legacy-control",
                legacy.attemptId(),
                "synthetic-legacy-effect",
                "synthetic-legacy-request",
                "memory://redacted",
                legacyApproval.plusMillis(2),
                true),
            legacyApproval.plusMillis(2));

    assertEquals("23514", v1SqlState);
    assertEquals("23514", preV17SqlState);
    assertEquals(v1Before, digest(jdbc, v1.attemptId()));
    assertEquals(preV17Before, digest(jdbc, "attempt-pre-v17"));
    assertEquals(ActionAttemptStatus.SUCCEEDED, completed.status());
    assertEquals(1, completed.capabilityUsedCalls());
    assertEquals(
        List.of(
            ActionAttemptStatus.PLANNED,
            ActionAttemptStatus.DISPATCHING,
            ActionAttemptStatus.SUCCEEDED),
        completed.transitions().stream().map(ActionTransition::toStatus).toList());
    assertTrue(completed.receipt().simulated());
    System.out.println(
        "V18_RETIRED_ROUTE_FENCE v1=23514 preV17=23514 preClaim=REJECTED "
            + "legacySimulated=SUCCEEDED digest=UNCHANGED");
  }

  private static ActionAttempt planned(
      String suffix, Instant approvedAt, String approvalOrigin, String executionRoute) {
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    String planId = "plan-" + suffix;
    String key = "idempotency-" + suffix;
    ActionPlan plan =
        new ActionPlan(
            planId,
            OWNER,
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            ARTIFACT_ID,
            1,
            ARTIFACT_HASH,
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            key,
            expiresAt);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-" + suffix,
            planId,
            plan.planHash(),
            ARTIFACT_HASH,
            "APPROVED",
            OWNER,
            approvedAt);
    ActionCapability capability =
        new ActionCapability(
            "capability-" + suffix,
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            planId,
            plan.planHash(),
            ARTIFACT_HASH,
            key,
            expiresAt,
            2);
    ActionApprovalScope scope =
        new ActionApprovalScope(
            ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
            OWNER,
            approvalOrigin,
            executionRoute,
            plan.actionType(),
            plan.targetRef(),
            ARTIFACT_ID,
            1,
            ARTIFACT_HASH,
            RiskLevel.REVERSIBLE,
            plan.policyVersion(),
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            Duration.between(approvedAt, expiresAt).toNanos() / 1_000L,
            2);
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

  private static void seedV16HistoricalUnknownAttempt(
      DataSource dataSource, Instant approvedAt) {
    Instant expiresAt = approvedAt.plus(Duration.ofMinutes(5));
    Instant dispatchAt = approvedAt.plusSeconds(1);
    Instant unknownAt = approvedAt.plusSeconds(2);
    ActionPlan plan =
        new ActionPlan(
            "plan-pre-v17",
            OWNER,
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            ARTIFACT_ID,
            1,
            ARTIFACT_HASH,
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            "idempotency-pre-v17",
            expiresAt);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.executeWithoutResult(
        ignored -> {
          jdbc.sql(
            """
            INSERT INTO captures (
                principal_id, capture_id, client_nonce, request_hash, content,
                source_type, source_ref, data_class, captured_at
            ) VALUES (
                :principalId, 'capture-v18-routes', 'nonce-v18-routes', :hash,
                'synthetic historical capture', 'TEXT', 'v18:migration-test',
                'PERSONAL', :capturedAt
            )
            """)
        .param("principalId", OWNER)
              .param("hash", ARTIFACT_HASH)
              .param("capturedAt", Timestamp.from(approvedAt.minusSeconds(2)))
              .update();
          jdbc.sql(
            """
            INSERT INTO artifacts (
                principal_id, artifact_id, source_capture_id, current_version, current_hash
            ) VALUES (:principalId, :artifactId, 'capture-v18-routes', 1, :hash)
            """)
              .param("principalId", OWNER)
              .param("artifactId", ARTIFACT_ID)
              .param("hash", ARTIFACT_HASH)
              .update();
          jdbc.sql(
            """
            INSERT INTO artifact_versions (
                principal_id, artifact_id, version, content, content_hash,
                base_version, base_hash, created_at
            ) VALUES (
                :principalId, :artifactId, 1, 'synthetic historical artifact', :hash,
                NULL, NULL, :createdAt
            )
            """)
        .param("principalId", OWNER)
        .param("artifactId", ARTIFACT_ID)
              .param("hash", ARTIFACT_HASH)
              .param("createdAt", Timestamp.from(approvedAt.minusSeconds(1)))
              .update();
          jdbc.sql(
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
                :principalId, 'attempt-pre-v17', :connector, :accountRef,
                :idempotencyKey, 'UNKNOWN', 3, 1, 2,
                :planId, :planHash, :actionType, :targetRef,
                :artifactId, 1, :artifactHash,
                'REVERSIBLE', :policyVersion, :expiresAt,
                'approval-pre-v17', :approvedAt,
                'capability-pre-v17', :principalId, :connector,
                :audience, :accountRef,
                :planId, :planHash, :artifactHash,
                :idempotencyKey, :expiresAt,
                :approvedAt, :unknownAt
            )
            """)
        .param("principalId", OWNER)
        .param("connector", CONNECTOR)
        .param("accountRef", ACCOUNT)
        .param("idempotencyKey", plan.idempotencyKey())
        .param("planId", plan.planId())
        .param("planHash", plan.planHash())
        .param("actionType", plan.actionType())
        .param("targetRef", plan.targetRef())
        .param("artifactId", ARTIFACT_ID)
        .param("artifactHash", ARTIFACT_HASH)
        .param("policyVersion", plan.policyVersion())
              .param("expiresAt", Timestamp.from(expiresAt))
              .param("approvedAt", Timestamp.from(approvedAt))
              .param("unknownAt", Timestamp.from(unknownAt))
              .param("audience", AUDIENCE)
              .update();
          jdbc.sql(
            """
            INSERT INTO action_attempt_transitions (
                principal_id, attempt_id, sequence, from_status, to_status,
                capability_use_delta, occurred_at
            ) VALUES (
                :principalId, 'attempt-pre-v17', 1, NULL, 'PLANNED', 0, :plannedAt
            ), (
                :principalId, 'attempt-pre-v17', 2,
                'PLANNED', 'DISPATCHING', 1, :dispatchAt
            ), (
                :principalId, 'attempt-pre-v17', 3,
                'DISPATCHING', 'UNKNOWN', 0, :unknownAt
            )
            """)
              .param("principalId", OWNER)
              .param("plannedAt", Timestamp.from(approvedAt))
              .param("dispatchAt", Timestamp.from(dispatchAt))
              .param("unknownAt", Timestamp.from(unknownAt))
              .update();
        });
  }

  private static String rawDispatchCommitSqlState(DataSource dataSource, String attemptId)
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                UPDATE action_attempts
                SET status = 'DISPATCHING', state_version = 2,
                    capability_used_calls = 1, updated_at = clock_timestamp()
                WHERE principal_id = ? AND attempt_id = ?
                """)) {
          update.setString(1, OWNER);
          update.setString(2, attemptId);
          assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement transition =
            connection.prepareStatement(
                """
                INSERT INTO action_attempt_transitions (
                    principal_id, attempt_id, sequence, from_status, to_status,
                    capability_use_delta, occurred_at
                ) SELECT principal_id, attempt_id, 2, 'PLANNED', 'DISPATCHING',
                         1, updated_at
                  FROM action_attempts
                 WHERE principal_id = ? AND attempt_id = ?
                """)) {
          transition.setString(1, OWNER);
          transition.setString(2, attemptId);
          assertEquals(1, transition.executeUpdate());
        }
        connection.commit();
        return null;
      } catch (SQLException failure) {
        return failure.getSQLState();
      } finally {
        try {
          connection.rollback();
        } catch (SQLException ignored) {
          // The original SQLSTATE remains the acceptance evidence.
        }
      }
    }
  }

  private static String rawReconciliationCommitSqlState(
      DataSource dataSource, String attemptId, Instant occurredAt) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                UPDATE action_attempts
                SET status = 'RECONCILING', state_version = 4,
                    capability_used_calls = 2, updated_at = ?
                WHERE principal_id = ? AND attempt_id = ?
                  AND status = 'UNKNOWN' AND state_version = 3
                  AND capability_used_calls = 1
                """)) {
          update.setTimestamp(1, Timestamp.from(occurredAt));
          update.setString(2, OWNER);
          update.setString(3, attemptId);
          assertEquals(1, update.executeUpdate());
        }
        try (PreparedStatement transition =
            connection.prepareStatement(
                """
                INSERT INTO action_attempt_transitions (
                    principal_id, attempt_id, sequence, from_status, to_status,
                    capability_use_delta, occurred_at
                ) VALUES (?, ?, 4, 'UNKNOWN', 'RECONCILING', 1, ?)
                """)) {
          transition.setString(1, OWNER);
          transition.setString(2, attemptId);
          transition.setTimestamp(3, Timestamp.from(occurredAt));
          assertEquals(1, transition.executeUpdate());
        }
        connection.commit();
        return null;
      } catch (SQLException failure) {
        return failure.getSQLState();
      } finally {
        try {
          connection.rollback();
        } catch (SQLException ignored) {
          // The original SQLSTATE remains the acceptance evidence.
        }
      }
    }
  }

  private static AttemptDigest digest(JdbcClient jdbc, String attemptId) {
    AttemptHead head =
        jdbc.sql(
                """
                SELECT status, state_version, capability_used_calls, xmin::text
                FROM action_attempts
                WHERE attempt_id = :attemptId
                """)
            .param("attemptId", attemptId)
            .query(
                (row, ignored) ->
                    new AttemptHead(
                        row.getString("status"),
                        row.getInt("state_version"),
                        row.getInt("capability_used_calls"),
                        row.getString("xmin")))
            .single();
    List<TransitionHead> transitions =
        jdbc.sql(
                """
                SELECT sequence, from_status, to_status, capability_use_delta, occurred_at
                FROM action_attempt_transitions
                WHERE attempt_id = :attemptId
                ORDER BY sequence
                """)
            .param("attemptId", attemptId)
            .query(
                (row, ignored) ->
                    new TransitionHead(
                        row.getInt("sequence"),
                        row.getString("from_status"),
                        row.getString("to_status"),
                        row.getInt("capability_use_delta"),
                        row.getTimestamp("occurred_at").toInstant()))
            .list();
    return new AttemptDigest(head, transitions);
  }

  private static Instant databaseNow(JdbcClient jdbc) {
    return jdbc.sql("SELECT date_trunc('microseconds', clock_timestamp())")
        .query(Timestamp.class)
        .single()
        .toInstant();
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

  private record AttemptHead(String status, int stateVersion, int usedCalls, String xmin) {}

  private record TransitionHead(
      int sequence, String fromStatus, String toStatus, int useDelta, Instant occurredAt) {}

  private record AttemptDigest(AttemptHead head, List<TransitionHead> transitions) {}
}
