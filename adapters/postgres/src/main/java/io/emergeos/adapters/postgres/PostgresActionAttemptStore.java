package io.emergeos.adapters.postgres;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.ActionCapability;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.port.ActionAttemptStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresActionAttemptStore implements ActionAttemptStore {

  private static final String ATTEMPT_SELECT =
      """
      SELECT
          a.*,
          r.receipt_id,
          r.outcome AS receipt_outcome,
          r.external_id,
          r.reason_code,
          r.provider_request_id,
          r.raw_response_ref,
          r.occurred_at AS receipt_occurred_at,
          r.simulated,
          t.sequence AS transition_sequence,
          t.from_status AS transition_from_status,
          t.to_status AS transition_to_status,
          t.occurred_at AS transition_occurred_at
      FROM action_attempts a
      LEFT JOIN action_receipts r
        ON r.principal_id = a.principal_id
       AND r.attempt_id = a.attempt_id
      JOIN action_attempt_transitions t
        ON t.principal_id = a.principal_id
       AND t.attempt_id = a.attempt_id
      WHERE a.principal_id = :principalId
        AND a.attempt_id = :attemptId
      ORDER BY t.sequence
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public PostgresActionAttemptStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public PlanResult planOrFind(ActionAttempt proposed) {
    requireNewPlan(proposed);
    return Objects.requireNonNull(
        transactions.execute(status -> planOrFindInTransaction(proposed)),
        "ActionAttempt plan transaction result");
  }

  @Override
  public PlanResult planApprovalOrFind(ActionAttempt proposed) {
    requireNewPlan(proposed);
    return Objects.requireNonNull(
        transactions.execute(status -> planApprovalOrFindInTransaction(proposed)),
        "Action approval plan transaction result");
  }

  private static void requireNewPlan(ActionAttempt proposed) {
    Objects.requireNonNull(proposed, "proposed");
    if (proposed.status() != ActionAttemptStatus.PLANNED
        || proposed.transitions().size() != 1) {
      throw new IllegalArgumentException("a proposed ActionAttempt must be newly PLANNED");
    }
  }

  private PlanResult planOrFindInTransaction(ActionAttempt proposed) {
    int inserted = insertAttempt(proposed);
    if (inserted == 1) {
      insertTransition(
          proposed.plan().principalId(),
          proposed.attemptId(),
          proposed.transitions().getFirst(),
          0);
    }
    Optional<AttemptIdentity> winner = findWinner(proposed);
    if (winner.isEmpty()) {
      throw new ActionAttemptIntegrityException(
          "ActionAttempt insert did not yield a unique-key winner");
    }
    AttemptIdentity identity = winner.orElseThrow();
    if (!identity.sameSemanticRequest(proposed)) {
      return new PlanResult.Conflict();
    }
    return new PlanResult.Accepted(
        requireOwned(identity.principalId(), identity.attemptId()), inserted == 1);
  }

  private PlanResult planApprovalOrFindInTransaction(ActionAttempt proposed) {
    Optional<AttemptIdentity> existing = findWinner(proposed);
    if (existing.isPresent()) {
      return canonicalWinner(proposed, existing.orElseThrow(), false);
    }

    Optional<ArtifactHead> lockedHead =
        jdbc.sql(
                """
                SELECT current_version, current_hash
                FROM artifacts
                WHERE principal_id = :principalId
                  AND artifact_id = :artifactId
                FOR UPDATE
                """)
            .param("principalId", proposed.plan().principalId())
            .param("artifactId", proposed.plan().artifactId())
            .query(PostgresActionAttemptStore::mapArtifactHead)
            .optional();

    existing = findWinner(proposed);
    if (existing.isPresent()) {
      return canonicalWinner(proposed, existing.orElseThrow(), false);
    }
    if (lockedHead.isEmpty()
        || lockedHead.orElseThrow().version() != proposed.plan().artifactVersion()
        || !lockedHead.orElseThrow().hash().equals(proposed.plan().artifactHash())) {
      return new PlanResult.Stale();
    }

    int inserted = insertAttempt(proposed);
    if (inserted == 1) {
      insertTransition(
          proposed.plan().principalId(),
          proposed.attemptId(),
          proposed.transitions().getFirst(),
          0);
    }
    AttemptIdentity winner =
        findWinner(proposed)
            .orElseThrow(
                () ->
                    new ActionAttemptIntegrityException(
                        "Action approval insert did not yield a unique-key winner"));
    return canonicalWinner(proposed, winner, inserted == 1);
  }

  private Optional<AttemptIdentity> findWinner(ActionAttempt proposed) {
    return jdbc.sql(
            """
            SELECT
                principal_id, attempt_id, connector, account_ref, idempotency_key,
                action_type, target_ref, artifact_id, artifact_version, artifact_hash,
                risk, policy_version, capability_audience, capability_max_calls
            FROM action_attempts
            WHERE connector = :connector
              AND account_ref = :accountRef
              AND idempotency_key = :idempotencyKey
            """)
        .param("connector", proposed.connector())
        .param("accountRef", proposed.accountRef())
        .param("idempotencyKey", proposed.plan().idempotencyKey())
        .query(PostgresActionAttemptStore::mapIdentity)
        .optional();
  }

  private PlanResult canonicalWinner(
      ActionAttempt proposed, AttemptIdentity winner, boolean created) {
    if (!winner.sameSemanticRequest(proposed)) {
      return new PlanResult.Conflict();
    }
    return new PlanResult.Accepted(
        requireOwned(winner.principalId(), winner.attemptId()), created);
  }

  @Override
  public ClaimResult claimDispatch(ActionAttempt expected, Instant now) {
    if (expected.status() != ActionAttemptStatus.PLANNED) {
      throw new IllegalArgumentException("dispatch claim requires PLANNED");
    }
    return claim(
        expected,
        ActionAttemptStatus.PLANNED,
        ActionAttemptStatus.DISPATCHING,
        true,
        Objects.requireNonNull(now, "now"));
  }

  @Override
  public ClaimResult claimReconciliation(ActionAttempt expected, Instant now) {
    if (expected.status() != ActionAttemptStatus.UNKNOWN) {
      throw new IllegalArgumentException("reconciliation claim requires UNKNOWN");
    }
    return claim(
        expected,
        ActionAttemptStatus.UNKNOWN,
        ActionAttemptStatus.RECONCILING,
        false,
        Objects.requireNonNull(now, "now"));
  }

  private ClaimResult claim(
      ActionAttempt expected,
      ActionAttemptStatus from,
      ActionAttemptStatus to,
      boolean requireCurrentArtifact,
      Instant now) {
    expected
        .capability()
        .assertAllows(
            expected.plan(),
            expected.connector(),
            expected.audience(),
            expected.accountRef(),
            now);
    return Objects.requireNonNull(
        transactions.execute(
            status ->
                claimInTransaction(
                    expected, from, to, requireCurrentArtifact, now)),
        "ActionAttempt claim transaction result");
  }

  private ClaimResult claimInTransaction(
      ActionAttempt expected,
      ActionAttemptStatus from,
      ActionAttemptStatus to,
      boolean requireCurrentArtifact,
      Instant now) {
    int nextVersion = expected.transitions().size() + 1;
    Optional<Integer> updated =
        jdbc.sql(
                """
                UPDATE action_attempts a
                SET status = :toStatus,
                    state_version = :nextVersion,
                    capability_used_calls = capability_used_calls + 1,
                    updated_at = :now
                WHERE a.principal_id = :principalId
                  AND a.attempt_id = :attemptId
                  AND a.status = :fromStatus
                  AND a.state_version = :expectedVersion
                  AND a.connector = :connector
                  AND a.account_ref = :accountRef
                  AND a.idempotency_key = :idempotencyKey
                  AND a.plan_id = :planId
                  AND a.plan_hash = :planHash
                  AND a.action_type = :actionType
                  AND a.target_ref = :targetRef
                  AND a.artifact_id = :artifactId
                  AND a.artifact_version = :artifactVersion
                  AND a.artifact_hash = :artifactHash
                  AND a.risk = :risk
                  AND a.policy_version = :policyVersion
                  AND a.plan_expires_at = :expiresAt
                  AND a.capability_id = :capabilityId
                  AND a.capability_subject = :subject
                  AND a.capability_connector = :connector
                  AND a.capability_audience = :audience
                  AND a.capability_account_ref = :accountRef
                  AND a.capability_plan_id = :planId
                  AND a.capability_plan_hash = :planHash
                  AND a.capability_artifact_hash = :artifactHash
                  AND a.capability_idempotency_key = :idempotencyKey
                  AND a.capability_expires_at = :expiresAt
                  AND a.capability_max_calls = :maxCalls
                  AND a.capability_used_calls = :usedCalls
                  AND a.capability_used_calls < a.capability_max_calls
                  AND a.capability_expires_at > :now
                  AND (
                    NOT :requireCurrentArtifact
                    OR EXISTS (
                      SELECT 1
                      FROM artifacts current_artifact
                      WHERE current_artifact.principal_id = a.principal_id
                        AND current_artifact.artifact_id = a.artifact_id
                        AND current_artifact.current_version = a.artifact_version
                        AND current_artifact.current_hash = a.artifact_hash
                    )
                  )
                RETURNING state_version
                """)
            .param("toStatus", to.name())
            .param("nextVersion", nextVersion)
            .param("now", Timestamp.from(now))
            .param("principalId", expected.plan().principalId())
            .param("attemptId", expected.attemptId())
            .param("fromStatus", from.name())
            .param("expectedVersion", expected.transitions().size())
            .param("connector", expected.connector())
            .param("accountRef", expected.accountRef())
            .param("idempotencyKey", expected.plan().idempotencyKey())
            .param("planId", expected.plan().planId())
            .param("planHash", expected.plan().planHash())
            .param("actionType", expected.plan().actionType())
            .param("targetRef", expected.plan().targetRef())
            .param("artifactId", expected.plan().artifactId())
            .param("artifactVersion", expected.plan().artifactVersion())
            .param("artifactHash", expected.plan().artifactHash())
            .param("risk", expected.plan().risk().name())
            .param("policyVersion", expected.plan().policyVersion())
            .param("expiresAt", Timestamp.from(expected.plan().expiresAt()))
            .param("capabilityId", expected.capability().capabilityId())
            .param("subject", expected.capability().subject())
            .param("audience", expected.capability().audience())
            .param("maxCalls", expected.capability().maxCalls())
            .param("usedCalls", expected.capabilityUsedCalls())
            .param("requireCurrentArtifact", requireCurrentArtifact)
            .query(Integer.class)
            .optional();
    if (updated.isPresent()) {
      insertTransition(
          expected.plan().principalId(),
          expected.attemptId(),
          new ActionTransition(nextVersion, from, to, now),
          1);
      return new ClaimResult.Claimed(
          requireOwned(expected.plan().principalId(), expected.attemptId()));
    }
    Optional<ActionAttempt> current =
        findOwned(expected.plan().principalId(), expected.attemptId());
    if (current.isEmpty()) {
      return new ClaimResult.NotFound();
    }
    if (current.orElseThrow().status() != from
        || current.orElseThrow().transitions().size() != expected.transitions().size()) {
      return new ClaimResult.Observed(current.orElseThrow());
    }
    return new ClaimResult.Rejected();
  }

  @Override
  public ActionAttempt markUnknown(ActionAttempt claimed, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    if (claimed.status() != ActionAttemptStatus.DISPATCHING
        && claimed.status() != ActionAttemptStatus.RECONCILING) {
      throw new IllegalArgumentException("only an active provider call can become UNKNOWN");
    }
    return transition(
        claimed, ActionAttemptStatus.UNKNOWN, null, Objects.requireNonNull(now, "now"));
  }

  @Override
  public ActionAttempt complete(
      ActionAttempt claimed, ActionReceipt receipt, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    Objects.requireNonNull(receipt, "receipt");
    if (claimed.status() != ActionAttemptStatus.DISPATCHING
        && claimed.status() != ActionAttemptStatus.RECONCILING) {
      throw new IllegalArgumentException("only an active provider call can complete");
    }
    return transition(
        claimed, receipt.outcome(), receipt, Objects.requireNonNull(now, "now"));
  }

  private ActionAttempt transition(
      ActionAttempt claimed,
      ActionAttemptStatus next,
      ActionReceipt receipt,
      Instant now) {
    if (!claimed.status().canTransitionTo(next)) {
      throw new IllegalArgumentException(
          "invalid transition " + claimed.status() + " -> " + next);
    }
    return Objects.requireNonNull(
        transactions.execute(
            status -> transitionInTransaction(claimed, next, receipt, now)),
        "ActionAttempt transition transaction result");
  }

  private ActionAttempt transitionInTransaction(
      ActionAttempt claimed,
      ActionAttemptStatus next,
      ActionReceipt receipt,
      Instant now) {
    int nextVersion = claimed.transitions().size() + 1;
    int updated =
        jdbc.sql(
                """
                UPDATE action_attempts
                SET status = :nextStatus,
                    state_version = :nextVersion,
                    updated_at = :now
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND status = :expectedStatus
                  AND state_version = :expectedVersion
                  AND capability_used_calls = :usedCalls
                """)
            .param("nextStatus", next.name())
            .param("nextVersion", nextVersion)
            .param("now", Timestamp.from(now))
            .param("principalId", claimed.plan().principalId())
            .param("attemptId", claimed.attemptId())
            .param("expectedStatus", claimed.status().name())
            .param("expectedVersion", claimed.transitions().size())
            .param("usedCalls", claimed.capabilityUsedCalls())
            .update();
    if (updated != 1) {
      throw new ActionAttemptIntegrityException(
          "ActionAttempt changed while completing a claimed provider call");
    }
    insertTransition(
        claimed.plan().principalId(),
        claimed.attemptId(),
        new ActionTransition(nextVersion, claimed.status(), next, now),
        0);
    if (receipt != null) {
      insertReceipt(claimed.plan().principalId(), receipt);
    }
    return requireOwned(claimed.plan().principalId(), claimed.attemptId());
  }

  @Override
  public Optional<ActionAttempt> findOwned(String principalId, String attemptId) {
    List<AttemptReadRow> rows =
        jdbc.sql(ATTEMPT_SELECT)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .query(PostgresActionAttemptStore::mapAttemptReadRow)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    AttemptRow attempt = rows.getFirst().attempt();
    List<ActionTransition> transitions =
        rows.stream().map(AttemptReadRow::transition).toList();
    return Optional.of(assemble(attempt, transitions));
  }

  private ActionAttempt requireOwned(String principalId, String attemptId) {
    return findOwned(principalId, attemptId)
        .orElseThrow(
            () ->
                new ActionAttemptIntegrityException(
                    "ActionAttempt write completed without a committed row"));
  }

  private ActionAttempt assemble(
      AttemptRow row, List<ActionTransition> transitions) {
    ActionPlan plan =
        new ActionPlan(
            row.planId(),
            row.principalId(),
            row.actionType(),
            row.targetRef(),
            row.artifactId(),
            row.artifactVersion(),
            row.artifactHash(),
            RiskLevel.valueOf(row.risk()),
            row.policyVersion(),
            row.idempotencyKey(),
            row.expiresAt());
    ApprovalDecision approval =
        new ApprovalDecision(
            row.approvalId(),
            row.planId(),
            row.planHash(),
            row.artifactHash(),
            "APPROVED",
            row.principalId(),
            row.approvedAt());
    ActionCapability capability =
        new ActionCapability(
            row.capabilityId(),
            row.capabilitySubject(),
            row.capabilityConnector(),
            row.capabilityAudience(),
            row.capabilityAccountRef(),
            row.capabilityPlanId(),
            row.capabilityPlanHash(),
            row.capabilityArtifactHash(),
            row.capabilityIdempotencyKey(),
            row.capabilityExpiresAt(),
            row.capabilityMaxCalls());
    return new ActionAttempt(
        row.attemptId(),
        plan,
        approval,
        capability,
        ActionAttemptStatus.valueOf(row.status()),
        row.capabilityUsedCalls(),
        transitions,
        row.receipt());
  }

  private int insertAttempt(ActionAttempt attempt) {
    ActionPlan plan = attempt.plan();
    ActionCapability capability = attempt.capability();
    Instant plannedAt = attempt.transitions().getFirst().occurredAt();
    return jdbc.sql(
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
                :principalId, :attemptId, :connector, :accountRef, :idempotencyKey,
                'PLANNED', 1, 0, :maxCalls,
                :planId, :planHash, :actionType, :targetRef,
                :artifactId, :artifactVersion, :artifactHash,
                :risk, :policyVersion, :expiresAt,
                :approvalId, :approvedAt,
                :capabilityId, :subject, :capabilityConnector,
                :audience, :capabilityAccountRef,
                :capabilityPlanId, :capabilityPlanHash, :capabilityArtifactHash,
                :capabilityIdempotencyKey, :capabilityExpiresAt,
                :plannedAt, :plannedAt
            )
            ON CONFLICT DO NOTHING
            """)
        .param("principalId", plan.principalId())
        .param("attemptId", attempt.attemptId())
        .param("connector", attempt.connector())
        .param("accountRef", attempt.accountRef())
        .param("idempotencyKey", plan.idempotencyKey())
        .param("maxCalls", capability.maxCalls())
        .param("planId", plan.planId())
        .param("planHash", plan.planHash())
        .param("actionType", plan.actionType())
        .param("targetRef", plan.targetRef())
        .param("artifactId", plan.artifactId())
        .param("artifactVersion", plan.artifactVersion())
        .param("artifactHash", plan.artifactHash())
        .param("risk", plan.risk().name())
        .param("policyVersion", plan.policyVersion())
        .param("expiresAt", Timestamp.from(plan.expiresAt()))
        .param("approvalId", attempt.approval().decisionId())
        .param("approvedAt", Timestamp.from(attempt.approval().decidedAt()))
        .param("capabilityId", capability.capabilityId())
        .param("subject", capability.subject())
        .param("capabilityConnector", capability.connector())
        .param("audience", capability.audience())
        .param("capabilityAccountRef", capability.accountRef())
        .param("capabilityPlanId", capability.actionPlanId())
        .param("capabilityPlanHash", capability.actionPlanHash())
        .param("capabilityArtifactHash", capability.artifactHash())
        .param("capabilityIdempotencyKey", capability.idempotencyKey())
        .param("capabilityExpiresAt", Timestamp.from(capability.expiresAt()))
        .param("plannedAt", Timestamp.from(plannedAt))
        .update();
  }

  private void insertTransition(
      String principalId,
      String attemptId,
      ActionTransition transition,
      int capabilityUseDelta) {
    jdbc.sql(
            """
            INSERT INTO action_attempt_transitions (
                principal_id, attempt_id, sequence, from_status, to_status,
                capability_use_delta, occurred_at
            ) VALUES (
                :principalId, :attemptId, :sequence, :fromStatus, :toStatus,
                :capabilityUseDelta, :occurredAt
            )
            """)
        .param("principalId", principalId)
        .param("attemptId", attemptId)
        .param("sequence", transition.sequence())
        .param(
            "fromStatus",
            transition.fromStatus() == null ? null : transition.fromStatus().name(),
            java.sql.Types.VARCHAR)
        .param("toStatus", transition.toStatus().name())
        .param("capabilityUseDelta", capabilityUseDelta)
        .param("occurredAt", Timestamp.from(transition.occurredAt()))
        .update();
  }

  private void insertReceipt(String principalId, ActionReceipt receipt) {
    jdbc.sql(
            """
            INSERT INTO action_receipts (
                principal_id, attempt_id, receipt_id, outcome, external_id, reason_code,
                provider_request_id, raw_response_ref, occurred_at, simulated
            ) VALUES (
                :principalId, :attemptId, :receiptId, :outcome, :externalId, :reasonCode,
                :providerRequestId, :rawResponseRef, :occurredAt, :simulated
            )
            """)
        .param("principalId", principalId)
        .param("attemptId", receipt.attemptId())
        .param("receiptId", receipt.receiptId())
        .param("outcome", receipt.outcome().name())
        .param("externalId", receipt.externalId(), java.sql.Types.VARCHAR)
        .param("reasonCode", receipt.reasonCode(), java.sql.Types.VARCHAR)
        .param("providerRequestId", receipt.providerRequestId())
        .param("rawResponseRef", receipt.rawResponseRef())
        .param("occurredAt", Timestamp.from(receipt.occurredAt()))
        .param("simulated", receipt.simulated())
        .update();
  }

  private static AttemptIdentity mapIdentity(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new AttemptIdentity(
        resultSet.getString("principal_id"),
        resultSet.getString("attempt_id"),
        resultSet.getString("connector"),
        resultSet.getString("account_ref"),
        resultSet.getString("idempotency_key"),
        resultSet.getString("action_type"),
        resultSet.getString("target_ref"),
        resultSet.getString("artifact_id"),
        resultSet.getInt("artifact_version"),
        resultSet.getString("artifact_hash"),
        resultSet.getString("risk"),
        resultSet.getString("policy_version"),
        resultSet.getString("capability_audience"),
        resultSet.getInt("capability_max_calls"));
  }

  private static ArtifactHead mapArtifactHead(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ArtifactHead(
        resultSet.getInt("current_version"), resultSet.getString("current_hash"));
  }

  private static AttemptReadRow mapAttemptReadRow(ResultSet resultSet, int rowNumber)
      throws SQLException {
    String receiptOutcome = resultSet.getString("receipt_outcome");
    ActionReceipt receipt =
        receiptOutcome == null
            ? null
            : new ActionReceipt(
                resultSet.getString("receipt_id"),
                resultSet.getString("attempt_id"),
                ActionAttemptStatus.valueOf(receiptOutcome),
                resultSet.getString("external_id"),
                resultSet.getString("reason_code"),
                resultSet.getString("provider_request_id"),
                resultSet.getString("raw_response_ref"),
                resultSet.getTimestamp("receipt_occurred_at").toInstant(),
                resultSet.getBoolean("simulated"));
    AttemptRow attempt =
        new AttemptRow(
            resultSet.getString("principal_id"),
            resultSet.getString("attempt_id"),
            resultSet.getString("status"),
            resultSet.getInt("capability_used_calls"),
            resultSet.getInt("capability_max_calls"),
            resultSet.getString("idempotency_key"),
            resultSet.getString("plan_id"),
            resultSet.getString("plan_hash"),
            resultSet.getString("action_type"),
            resultSet.getString("target_ref"),
            resultSet.getString("artifact_id"),
            resultSet.getInt("artifact_version"),
            resultSet.getString("artifact_hash"),
            resultSet.getString("risk"),
            resultSet.getString("policy_version"),
            resultSet.getTimestamp("plan_expires_at").toInstant(),
            resultSet.getString("approval_id"),
            resultSet.getTimestamp("approved_at").toInstant(),
            resultSet.getString("capability_id"),
            resultSet.getString("capability_subject"),
            resultSet.getString("capability_connector"),
            resultSet.getString("capability_audience"),
            resultSet.getString("capability_account_ref"),
            resultSet.getString("capability_plan_id"),
            resultSet.getString("capability_plan_hash"),
            resultSet.getString("capability_artifact_hash"),
            resultSet.getString("capability_idempotency_key"),
            resultSet.getTimestamp("capability_expires_at").toInstant(),
            receipt);
    String from = resultSet.getString("transition_from_status");
    ActionTransition transition =
        new ActionTransition(
            resultSet.getInt("transition_sequence"),
            from == null ? null : ActionAttemptStatus.valueOf(from),
            ActionAttemptStatus.valueOf(resultSet.getString("transition_to_status")),
            resultSet.getTimestamp("transition_occurred_at").toInstant());
    return new AttemptReadRow(attempt, transition);
  }

  private record AttemptIdentity(
      String principalId,
      String attemptId,
      String connector,
      String accountRef,
      String idempotencyKey,
      String actionType,
      String targetRef,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String risk,
      String policyVersion,
      String audience,
      int maxCalls) {

    private boolean sameSemanticRequest(ActionAttempt proposed) {
      return principalId.equals(proposed.plan().principalId())
          && connector.equals(proposed.connector())
          && accountRef.equals(proposed.accountRef())
          && idempotencyKey.equals(proposed.plan().idempotencyKey())
          && actionType.equals(proposed.plan().actionType())
          && targetRef.equals(proposed.plan().targetRef())
          && artifactId.equals(proposed.plan().artifactId())
          && artifactVersion == proposed.plan().artifactVersion()
          && artifactHash.equals(proposed.plan().artifactHash())
          && risk.equals(proposed.plan().risk().name())
          && policyVersion.equals(proposed.plan().policyVersion())
          && audience.equals(proposed.audience())
          && maxCalls == proposed.capability().maxCalls();
    }
  }

  private record AttemptRow(
      String principalId,
      String attemptId,
      String status,
      int capabilityUsedCalls,
      int capabilityMaxCalls,
      String idempotencyKey,
      String planId,
      String planHash,
      String actionType,
      String targetRef,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String risk,
      String policyVersion,
      Instant expiresAt,
      String approvalId,
      Instant approvedAt,
      String capabilityId,
      String capabilitySubject,
      String capabilityConnector,
      String capabilityAudience,
      String capabilityAccountRef,
      String capabilityPlanId,
      String capabilityPlanHash,
      String capabilityArtifactHash,
      String capabilityIdempotencyKey,
      Instant capabilityExpiresAt,
      ActionReceipt receipt) {}

  private record AttemptReadRow(
      AttemptRow attempt, ActionTransition transition) {}

  private record ArtifactHead(int version, String hash) {}

  private static final class ActionAttemptIntegrityException extends RuntimeException {

    private ActionAttemptIntegrityException(String message) {
      super(message);
    }
  }
}
