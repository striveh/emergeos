package io.emergeos.adapters.postgres;

import io.emergeos.core.application.LocalDraftboxAuthority;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionAttemptStatus;
import io.emergeos.core.domain.LocalDraft;
import io.emergeos.core.domain.LocalDraftCreationReceipt;
import io.emergeos.core.port.LocalDraftboxStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL-canonical local effect writer. It never dispatches an ActionProvider. */
public final class PostgresLocalDraftboxStore implements LocalDraftboxStore {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PostgresActionAttemptStore attempts;

  public PostgresLocalDraftboxStore(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PostgresActionAttemptStore attempts) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.attempts = Objects.requireNonNull(attempts, "attempts");
  }

  @Override
  public ExecuteResult executeOwned(
      String principalId,
      String attemptId,
      String scopeSchema,
      String scopeHash,
      String proposedDraftId,
      String proposedReceiptId) {
    requireText(principalId, "principalId");
    requireText(attemptId, "attemptId");
    requireText(scopeSchema, "scopeSchema");
    requireHash(scopeHash, "scopeHash");
    requireText(proposedDraftId, "proposedDraftId");
    requireText(proposedReceiptId, "proposedReceiptId");

    TxOutcome outcome =
        Objects.requireNonNull(
            transactions.execute(
                ignored ->
                    executeInTransaction(
                        principalId,
                        attemptId,
                        scopeSchema,
                        scopeHash,
                        proposedDraftId,
                        proposedReceiptId)),
            "local Draftbox transaction result");
    if (outcome == TxOutcome.NOT_FOUND) {
      return new ExecuteResult.NotFound();
    }
    if (outcome == TxOutcome.STALE) {
      return new ExecuteResult.Stale();
    }
    if (outcome == TxOutcome.CONFLICT) {
      return new ExecuteResult.Conflict();
    }
    ActionAttempt attempt =
        attempts
            .findOwned(principalId, attemptId)
            .orElseThrow(
                () ->
                    new ActionApprovalIntegrityException());
    LocalDraft draft = requireOwnedDraft(principalId, attemptId, attempt);
    return hydrateExecutedResult(attempt, draft, outcome == TxOutcome.CREATED);
  }

  private TxOutcome executeInTransaction(
      String principalId,
      String attemptId,
      String scopeSchema,
      String scopeHash,
      String proposedDraftId,
      String proposedReceiptId) {
    Optional<AttemptHead> selected =
        jdbc.sql(
                """
                SELECT
                    principal_id, attempt_id, status, state_version,
                    capability_used_calls, approval_principal_basis,
                    configured_principal_id, approval_origin, execution_route,
                    scope_schema, scope_hash, action_type, target_ref,
                    artifact_id, artifact_version, artifact_hash, risk,
                    policy_version, connector, account_ref,
                    capability_subject, capability_connector,
                    capability_audience, capability_account_ref,
                    capability_max_calls, capability_expires_at
                FROM action_attempts
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                FOR UPDATE
                """)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .query(PostgresLocalDraftboxStore::mapAttemptHead)
            .optional();
    if (selected.isEmpty()) {
      return TxOutcome.NOT_FOUND;
    }
    AttemptHead attempt = selected.orElseThrow();
    if (ActionApprovalScope.LOCAL_DRAFTBOX_V1.equals(attempt.executionRoute())) {
      return TxOutcome.STALE;
    }
    if (!ActionApprovalScope.LOCAL_DRAFTBOX_V2.equals(attempt.executionRoute())
        || !ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT.equals(attempt.approvalOrigin())) {
      return TxOutcome.NOT_FOUND;
    }
    requireExactAuthority(attempt);
    ActionAttempt canonicalAttempt =
        attempts
            .findOwned(principalId, attemptId)
            .orElseThrow(
                () ->
                    new ActionApprovalIntegrityException());
    if (!canonicalAttempt.approvalScope().scopeSchema().equals(scopeSchema)
        || !canonicalAttempt.approvalScope().scopeHash().equals(scopeHash)) {
      return TxOutcome.STALE;
    }
    if (canonicalAttempt.status() == ActionAttemptStatus.SUCCEEDED) {
      return TxOutcome.REPLAY;
    }
    if (canonicalAttempt.status() != ActionAttemptStatus.PLANNED
        || attempt.stateVersion() != 1
        || canonicalAttempt.transitions().size() != 1
        || canonicalAttempt.capabilityUsedCalls() != 0) {
      return TxOutcome.CONFLICT;
    }

    Optional<ArtifactHead> artifact =
        jdbc.sql(
                """
                SELECT current_version, current_hash
                FROM artifacts
                WHERE principal_id = :principalId
                  AND artifact_id = :artifactId
                FOR UPDATE
                """)
            .param("principalId", principalId)
            .param("artifactId", canonicalAttempt.plan().artifactId())
            .query(
                (resultSet, rowNumber) ->
                    new ArtifactHead(
                        resultSet.getInt("current_version"),
                        resultSet.getString("current_hash")))
            .optional();
    if (artifact.isEmpty()
        || artifact.orElseThrow().version() != canonicalAttempt.plan().artifactVersion()
        || !artifact.orElseThrow().hash().equals(canonicalAttempt.plan().artifactHash())) {
      return TxOutcome.STALE;
    }

    Optional<Timestamp> updatedAt =
        jdbc.sql(
                """
                WITH execution_clock AS MATERIALIZED (
                    SELECT pg_catalog.clock_timestamp() AS occurred_at
                )
                UPDATE action_attempts
                SET status = 'SUCCEEDED',
                    state_version = 2,
                    capability_used_calls = 1,
                    updated_at = execution_clock.occurred_at
                FROM execution_clock
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                  AND status = 'PLANNED'
                  AND state_version = 1
                  AND capability_used_calls = 0
                  AND capability_max_calls = 1
                  AND approval_principal_basis = 'CONFIGURED_LOCAL_PRINCIPAL'
                  AND configured_principal_id = :principalId
                  AND approval_origin = 'EXPLICIT_LOCAL_OWNER_INPUT'
                  AND execution_route = 'LOCAL_DRAFTBOX_V2'
                  AND scope_schema = :scopeSchema
                  AND scope_hash = :scopeHash
                  AND action_type = 'CREATE_LOCAL_DRAFT'
                  AND target_ref = 'local://drafts'
                  AND artifact_id = :artifactId
                  AND artifact_version = :artifactVersion
                  AND artifact_hash = :artifactHash
                  AND risk = 'REVERSIBLE'
                  AND policy_version = 'local-action-v2'
                  AND connector = 'emergeos.local-draftbox'
                  AND account_ref = :accountRef
                  AND capability_subject = :principalId
                  AND capability_connector = 'emergeos.local-draftbox'
                  AND capability_audience = 'emergeos:local-draftbox'
                  AND capability_account_ref = :accountRef
                  AND capability_max_calls = 1
                  AND capability_expires_at > execution_clock.occurred_at
                RETURNING updated_at
                """)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .param("scopeSchema", canonicalAttempt.approvalScope().scopeSchema())
            .param("scopeHash", canonicalAttempt.approvalScope().scopeHash())
            .param("artifactId", canonicalAttempt.plan().artifactId())
            .param("artifactVersion", canonicalAttempt.plan().artifactVersion())
            .param("artifactHash", canonicalAttempt.plan().artifactHash())
            .param("accountRef", canonicalAttempt.accountRef())
            .query(Timestamp.class)
            .optional();
    if (updatedAt.isEmpty()) {
      return TxOutcome.STALE;
    }
    Instant occurredAt = updatedAt.orElseThrow().toInstant();

    requireOne(
        jdbc.sql(
                """
                INSERT INTO action_attempt_transitions (
                    principal_id, attempt_id, sequence, from_status, to_status,
                    capability_use_delta, occurred_at
                ) VALUES (
                    :principalId, :attemptId, 2, 'PLANNED', 'SUCCEEDED', 1, :occurredAt
                )
                """)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .param("occurredAt", Timestamp.from(occurredAt))
            .update());
    requireOne(
        jdbc.sql(
                """
                INSERT INTO local_drafts (
                    principal_id, draft_id, attempt_id, attempt_status,
                    artifact_id, artifact_version, artifact_hash, state, created_at
                ) VALUES (
                    :principalId, :draftId, :attemptId, 'SUCCEEDED',
                    :artifactId, :artifactVersion, :artifactHash, 'ACTIVE', :occurredAt
                )
                """)
            .param("principalId", principalId)
            .param("draftId", proposedDraftId)
            .param("attemptId", attemptId)
            .param("artifactId", canonicalAttempt.plan().artifactId())
            .param("artifactVersion", canonicalAttempt.plan().artifactVersion())
            .param("artifactHash", canonicalAttempt.plan().artifactHash())
            .param("occurredAt", Timestamp.from(occurredAt))
            .update());
    requireOne(
        jdbc.sql(
                """
                INSERT INTO local_draft_creation_receipts (
                    principal_id, attempt_id, outcome, receipt_id,
                    receipt_type, draft_id, occurred_at, simulated
                ) VALUES (
                    :principalId, :attemptId, 'SUCCEEDED', :receiptId,
                    'LOCAL_DRAFT_CREATED_V1', :draftId, :occurredAt, FALSE
                )
                """)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .param("receiptId", proposedReceiptId)
            .param("draftId", proposedDraftId)
            .param("occurredAt", Timestamp.from(occurredAt))
            .update());
    return TxOutcome.CREATED;
  }

  private LocalDraft requireOwnedDraft(
      String principalId, String attemptId, ActionAttempt attempt) {
    LocalDraft draft =
        jdbc.sql(
                """
                SELECT draft_id, artifact_id, artifact_version, artifact_hash,
                       state, created_at
                FROM local_drafts
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", principalId)
            .param("attemptId", attemptId)
            .query(
                (resultSet, rowNumber) ->
                    hydrateLocalDraft(resultSet, principalId, attemptId))
            .optional()
            .orElseThrow(
                () ->
                    new ActionApprovalIntegrityException());
    LocalDraftCreationReceipt receipt = attempt.localDraftReceipt();
    if (receipt == null
        || !receipt.draftId().equals(draft.draftId())
        || !receipt.occurredAt().equals(draft.createdAt())
        || !attempt.plan().artifactId().equals(draft.artifactId())
        || attempt.plan().artifactVersion() != draft.artifactVersion()
        || !attempt.plan().artifactHash().equals(draft.artifactHash())) {
      throw new ActionApprovalIntegrityException();
    }
    return draft;
  }

  private static LocalDraft hydrateLocalDraft(
      ResultSet resultSet, String principalId, String attemptId) throws SQLException {
    if (!LocalDraft.ACTIVE.equals(resultSet.getString("state"))) {
      throw new ActionApprovalIntegrityException();
    }
    try {
      return new LocalDraft(
          principalId,
          resultSet.getString("draft_id"),
          attemptId,
          resultSet.getString("artifact_id"),
          resultSet.getInt("artifact_version"),
          resultSet.getString("artifact_hash"),
          resultSet.getTimestamp("created_at").toInstant());
    } catch (ActionApprovalIntegrityException exception) {
      throw exception;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new ActionApprovalIntegrityException(exception);
    }
  }

  private static ExecuteResult.Executed hydrateExecutedResult(
      ActionAttempt attempt, LocalDraft draft, boolean created) {
    try {
      return new ExecuteResult.Executed(attempt, draft, created);
    } catch (ActionApprovalIntegrityException exception) {
      throw exception;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new ActionApprovalIntegrityException(exception);
    }
  }

  private static AttemptHead mapAttemptHead(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new AttemptHead(
        resultSet.getString("principal_id"),
        resultSet.getString("attempt_id"),
        resultSet.getString("status"),
        resultSet.getInt("state_version"),
        resultSet.getInt("capability_used_calls"),
        resultSet.getString("approval_principal_basis"),
        resultSet.getString("configured_principal_id"),
        resultSet.getString("approval_origin"),
        resultSet.getString("execution_route"),
        resultSet.getString("scope_schema"),
        resultSet.getString("scope_hash"),
        resultSet.getString("action_type"),
        resultSet.getString("target_ref"),
        resultSet.getString("artifact_id"),
        resultSet.getInt("artifact_version"),
        resultSet.getString("artifact_hash"),
        resultSet.getString("risk"),
        resultSet.getString("policy_version"),
        resultSet.getString("connector"),
        resultSet.getString("account_ref"),
        resultSet.getString("capability_subject"),
        resultSet.getString("capability_connector"),
        resultSet.getString("capability_audience"),
        resultSet.getString("capability_account_ref"),
        resultSet.getInt("capability_max_calls"),
        resultSet.getTimestamp("capability_expires_at").toInstant());
  }

  private static void requireExactAuthority(AttemptHead attempt) {
    String accountRef = LocalDraftboxAuthority.accountRefFor(attempt.principalId());
    if (!ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL.equals(
            attempt.approvalPrincipalBasis())
        || !attempt.principalId().equals(attempt.configuredPrincipalId())
        || !LocalDraftboxAuthority.ACTION_TYPE.equals(attempt.actionType())
        || !LocalDraftboxAuthority.TARGET_REF.equals(attempt.targetRef())
        || !"REVERSIBLE".equals(attempt.risk())
        || !LocalDraftboxAuthority.POLICY_VERSION.equals(attempt.policyVersion())
        || !LocalDraftboxAuthority.CONNECTOR.equals(attempt.connector())
        || !accountRef.equals(attempt.accountRef())
        || !attempt.principalId().equals(attempt.capabilitySubject())
        || !LocalDraftboxAuthority.CONNECTOR.equals(attempt.capabilityConnector())
        || !LocalDraftboxAuthority.AUDIENCE.equals(attempt.capabilityAudience())
        || !accountRef.equals(attempt.capabilityAccountRef())
        || attempt.capabilityMaxCalls() != LocalDraftboxAuthority.MAX_CALLS) {
      throw new ActionApprovalIntegrityException();
    }
  }

  private static void requireOne(int count) {
    if (count != 1) {
      throw new ActionApprovalIntegrityException();
    }
  }

  private static void requireText(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }

  private enum TxOutcome {
    CREATED,
    REPLAY,
    STALE,
    CONFLICT,
    NOT_FOUND
  }

  private record ArtifactHead(int version, String hash) {}

  private record AttemptHead(
      String principalId,
      String attemptId,
      String status,
      int stateVersion,
      int capabilityUsedCalls,
      String approvalPrincipalBasis,
      String configuredPrincipalId,
      String approvalOrigin,
      String executionRoute,
      String scopeSchema,
      String scopeHash,
      String actionType,
      String targetRef,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String risk,
      String policyVersion,
      String connector,
      String accountRef,
      String capabilitySubject,
      String capabilityConnector,
      String capabilityAudience,
      String capabilityAccountRef,
      int capabilityMaxCalls,
      Instant capabilityExpiresAt) {}

}
