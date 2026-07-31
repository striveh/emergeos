package io.emergeos.adapters.postgres;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.port.GraphAttemptStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL-canonical store for the first one-shot graph prefix.
 *
 * <p>Every semantic transition rebuilds the committed prefix before it
 * appends one event and advances the CAS head in the same transaction.
 * There is deliberately no resume or generic append surface.
 */
public final class PostgresGraphAttemptStore
    implements GraphAttemptStore {

  private static final String INVALID_STORED_GRAPH =
      "STORED_GRAPH_INVALID";
  private static final String EXPECTED_MANIFEST_MISMATCH =
      "EXPECTED_MANIFEST_MISMATCH";

  private final JdbcClient jdbc;
  private final TransactionTemplate writes;
  private final TransactionTemplate verifiedReads;
  private final JsonMapper json;
  private final Probe probe;

  public PostgresGraphAttemptStore(DataSource dataSource) {
    this(dataSource, ignored -> {});
  }

  PostgresGraphAttemptStore(
      DataSource dataSource, Probe probe) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc =
        JdbcClient.create(dataSource);
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(dataSource);
    transactionManager.setEnforceReadOnly(true);
    this.writes = new TransactionTemplate(transactionManager);
    this.writes.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.writes.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.verifiedReads =
        new TransactionTemplate(transactionManager);
    this.verifiedReads.setIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.verifiedReads.setReadOnly(true);
    this.verifiedReads.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.json = JsonMapper.shared();
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  @Override
  public CreateResult create(
      GraphAttemptManifest manifest, Instant occurredAt) {
    Objects.requireNonNull(manifest, "manifest");
    requireDatabaseInstant(manifest.startedAt(), "manifest.startedAt");
    requireDatabaseInstant(occurredAt, "occurredAt");
    GraphAttemptEvent claimed =
        GraphAttemptEvent.claimed(manifest, occurredAt);
    try {
      GraphAttemptCursor cursor =
          writes.execute(
              ignored -> {
                insertManifest(manifest, claimed, occurredAt);
                probe.hit(ProbePoint.AFTER_MANIFEST_INSERT);
                for (GraphRunSelection selection :
                    manifest.selections()) {
                  insertBinding(
                      manifest, selection, occurredAt);
                }
                probe.hit(ProbePoint.AFTER_BINDINGS_INSERT);
                insertEvent(manifest, claimed);
                probe.hit(ProbePoint.AFTER_EVENT_INSERT);
                insertHead(manifest, claimed, occurredAt);
                probe.hit(ProbePoint.AFTER_HEAD_UPDATE);
                GraphAttemptCursor expected =
                    claimed.cursor(manifest);
                return requireMutableSnapshot(
                        loadVerified(manifest, true),
                        expected)
                    .cursor();
              });
      return new CreateResult.Created(
          Objects.requireNonNull(
              cursor, "graph create transaction result"));
    } catch (AlreadyClaimedException replay) {
      return new CreateResult.AlreadyExists();
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  @Override
  public GraphAttemptCursor approve(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphOperatorApproval approval,
      Instant occurredAt) {
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(manifest, "manifest");
    if (!approval.matches(manifest)) {
      throw new IllegalArgumentException(
          "graph approval does not match the exact owner challenge");
    }
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.OPERATOR_APPROVED,
        GraphAttemptPhase.OPERATOR_APPROVED,
        null,
        null,
        approval,
        null,
        occurredAt,
        false);
  }

  @Override
  public GraphAttemptCursor authorizeParent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt) {
    requireExactRun(
        manifest, manifest.parentSelection(), running);
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.PARENT_AUTHORIZED,
        GraphAttemptPhase.PARENT_AUTHORIZED,
        manifest.parentSelection(),
        running,
        null,
        null,
        occurredAt,
        false);
  }

  @Override
  public GraphAttemptCursor startParent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt) {
    requireExactRun(
        manifest, manifest.parentSelection(), running);
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.PARENT_STARTED,
        GraphAttemptPhase.PARENT_RUNNING,
        manifest.parentSelection(),
        running,
        null,
        null,
        occurredAt,
        true);
  }

  @Override
  public GraphAttemptCursor authorizeChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt) {
    requireExactRun(
        manifest, manifest.childSelection(), running);
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.CHILD_AUTHORIZED,
        GraphAttemptPhase.CHILD_AUTHORIZED,
        manifest.childSelection(),
        running,
        null,
        null,
        occurredAt,
        false);
  }

  @Override
  public GraphAttemptCursor startChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt) {
    requireExactRun(
        manifest, manifest.childSelection(), running);
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.CHILD_STARTED,
        GraphAttemptPhase.CHILD_RUNNING,
        manifest.childSelection(),
        running,
        null,
        null,
        occurredAt,
        true);
  }

  @Override
  public GraphAttemptCursor consumeChildEgress(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt) {
    return childAdvance(
        manifest,
        expected,
        GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
        GraphAttemptPhase.EGRESS_CONSUMED,
        occurredAt);
  }

  @Override
  public GraphAttemptCursor credentialReadStarted(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt) {
    return childAdvance(
        manifest,
        expected,
        GraphAttemptEventType.CREDENTIAL_READ_STARTED,
        GraphAttemptPhase.CREDENTIAL_READING,
        occurredAt);
  }

  @Override
  public GraphAttemptCursor clientCreated(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt) {
    return childAdvance(
        manifest,
        expected,
        GraphAttemptEventType.CLIENT_CREATED,
        GraphAttemptPhase.CLIENT_READY,
        occurredAt);
  }

  @Override
  public GraphAttemptCursor modelCreated(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt) {
    return childAdvance(
        manifest,
        expected,
        GraphAttemptEventType.MODEL_CREATED,
        GraphAttemptPhase.MODEL_READY,
        occurredAt);
  }

  @Override
  public GraphAttemptCursor providerIntent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderIntent intent,
      Instant occurredAt) {
    Objects.requireNonNull(intent, "intent");
    return advance(
        manifest,
        expected,
        GraphAttemptEventType.PROVIDER_INTENT,
        GraphAttemptPhase.PROVIDER_PENDING,
        manifest.childSelection(),
        null,
        null,
        intent,
        occurredAt,
        false);
  }

  @Override
  public GraphAttemptVerification findVerified(
      GraphAttemptManifest expected) {
    Objects.requireNonNull(expected, "expected");
    GraphAttemptVerification result =
        verifiedReads.execute(
            ignored -> {
              probe.hit(
                  ProbePoint.VERIFIED_READ_TRANSACTION);
              return loadVerified(expected, false);
            });
    return Objects.requireNonNull(
        result, "verified graph read transaction result");
  }

  private GraphAttemptCursor childAdvance(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      Instant occurredAt) {
    return advance(
        manifest,
        expected,
        type,
        phase,
        manifest.childSelection(),
        null,
        null,
        null,
        occurredAt,
        false);
  }

  private GraphAttemptCursor advance(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      GraphRunSelection selection,
      AgentRun running,
      GraphOperatorApproval approval,
      GraphProviderIntent providerIntent,
      Instant occurredAt,
      boolean insertRun) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    requireCursorIdentity(manifest, expected);
    requireDatabaseInstant(occurredAt, "occurredAt");
    GraphAttemptEvent event =
        GraphAttemptEvent.next(
            expected,
            type,
            occurredAt,
            phase,
            selection == null ? null : selection.role(),
            selection == null ? null : selection.runId(),
            selection == null ? null : selection.taskId(),
            approval,
            providerIntent);
    try {
      GraphAttemptCursor advanced =
          writes.execute(
              ignored -> {
                GraphAttemptVerification verification =
                    loadVerified(manifest, true);
                GraphAttemptSnapshot snapshot =
                    requireMutableSnapshot(
                        verification, expected);
                if (providerIntent != null) {
                  requireProviderIntent(
                      manifest, snapshot, providerIntent);
                }
                if (insertRun) {
                  insertGraphRun(
                      manifest,
                      Objects.requireNonNull(
                          selection, "selection"),
                      Objects.requireNonNull(running, "running"));
                  probe.hit(ProbePoint.AFTER_RUN_INSERT);
                }
                insertEvent(manifest, event);
                probe.hit(ProbePoint.AFTER_EVENT_INSERT);
                if (updateHead(manifest, expected, event) != 1) {
                  throw conflict();
                }
                probe.hit(ProbePoint.AFTER_HEAD_UPDATE);
                GraphAttemptCursor committed =
                    event.cursor(manifest);
                return requireMutableSnapshot(
                        loadVerified(manifest, true),
                        committed)
                    .cursor();
              });
      return Objects.requireNonNull(
          advanced, "graph mutation transaction result");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      String state = sqlState(failure);
      if ("23505".equals(state) || "40001".equals(state)) {
        throw conflict();
      }
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private GraphAttemptSnapshot requireMutableSnapshot(
      GraphAttemptVerification verification,
      GraphAttemptCursor expected) {
    if (verification
        instanceof GraphAttemptVerification.Missing) {
      throw conflict();
    }
    if (verification
        instanceof GraphAttemptVerification.Invalid) {
      throw new GraphAttemptIntegrityException();
    }
    GraphAttemptSnapshot snapshot =
        ((GraphAttemptVerification.Valid) verification)
            .snapshot();
    if (!snapshot.cursor().equals(expected)) {
      throw conflict();
    }
    return snapshot;
  }

  private void requireProviderIntent(
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot snapshot,
      GraphProviderIntent intent) {
    if (intent.requestOrdinal() > manifest.maximumProviderRequests()
        || snapshot.childRun() == null
        || !intent.modelRequested().equals(
            snapshot.childRun().task().modelRequested())) {
      throw new IllegalArgumentException(
          "provider intent does not match the frozen child selection");
    }
  }

  private GraphAttemptVerification loadVerified(
      GraphAttemptManifest expected, boolean lockHead) {
    Optional<ManifestRow> manifestRow =
        loadManifest(
            expected.principalId(),
            expected.executionSlotId());
    if (manifestRow.isEmpty()) {
      return new GraphAttemptVerification.Missing();
    }
    try {
      GraphAttemptManifest stored =
          json.readValue(
              manifestRow.orElseThrow().manifestJson(),
              GraphAttemptManifest.class);
      if (!manifestRow.orElseThrow().matches(stored)) {
        return invalid(INVALID_STORED_GRAPH);
      }
      if (!stored.equals(expected)) {
        return invalid(EXPECTED_MANIFEST_MISMATCH);
      }
      List<GraphRunSelection> bindings =
          loadBindings(stored);
      if (!bindings.equals(stored.selections())) {
        return invalid(INVALID_STORED_GRAPH);
      }
      Optional<HeadRow> headRow =
          loadHead(stored, lockHead);
      if (headRow.isEmpty()) {
        return invalid(INVALID_STORED_GRAPH);
      }
      HeadRow head = headRow.orElseThrow();
      GraphAttemptCursor cursor = head.cursor(stored);
      List<GraphAttemptEvent> events =
          loadEvents(stored);
      if (!eventsMatchBindings(events, stored)) {
        return invalid(INVALID_STORED_GRAPH);
      }
      Map<GraphRunRole, StoredRunEvidence> runs =
          loadRuns(stored);
      if (!runsMatchBindings(runs, stored)
          || !providerIntentMatches(events, runs)) {
        return invalid(INVALID_STORED_GRAPH);
      }
      if (sealCount(stored) != 0
          || prematureTruthCount(stored) != 0) {
        return invalid(INVALID_STORED_GRAPH);
      }
      AgentRun parent =
          Optional.ofNullable(runs.get(GraphRunRole.PARENT))
              .map(StoredRunEvidence::run)
              .orElse(null);
      AgentRun child =
          Optional.ofNullable(runs.get(GraphRunRole.CHILD))
              .map(StoredRunEvidence::run)
              .orElse(null);
      GraphAttemptSnapshot snapshot =
          new GraphAttemptSnapshot(
              stored,
              cursor,
              events,
              parent,
              child,
              false,
              GraphAttemptOutcome.INCOMPLETE,
              head.billingStatus());
      return new GraphAttemptVerification.Valid(snapshot);
    } catch (DataAccessException failure) {
      throw failure;
    } catch (RuntimeException invalidStoredGraph) {
      return invalid(INVALID_STORED_GRAPH);
    }
  }

  private Optional<ManifestRow> loadManifest(
      String principalId, String executionSlotId) {
    return jdbc.sql(
            """
            SELECT
              principal_id, attempt_id, execution_slot_id,
              manifest_schema_version, graph_protocol_version,
              integrity_profile, case_id, pack_raw_sha256,
              environment_raw_sha256, capture_id,
              capture_request_hash, artifact_id, started_at,
              pricing_profile_fingerprint,
              prompt_surface_fingerprint,
              conductor_surface_fingerprint, reservation_usd,
              maximum_provider_requests, parent_actor, child_actor,
              experiment_arm, experiment_repetition,
              manifest_json::text AS manifest_json,
              manifest_hash, initial_head_hash
            FROM agent_graph_attempts
            WHERE principal_id = :principalId
              AND execution_slot_id = :executionSlotId
            """)
        .param("principalId", principalId)
        .param("executionSlotId", executionSlotId)
        .query(
            (resultSet, rowNumber) ->
                ManifestRow.from(resultSet))
        .optional();
  }

  private List<GraphRunSelection> loadBindings(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT
              role, run_id, task_id, task_hash,
              selector_hash,
              execution_profile_id,
              execution_profile_fingerprint,
              worker_registry_version,
              worker_profile_id,
              worker_profile_fingerprint
            FROM agent_graph_attempt_run_bindings
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            ORDER BY CASE role
              WHEN 'PARENT' THEN 1
              WHEN 'CHILD' THEN 2
              ELSE 3
            END
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .query(
            (resultSet, rowNumber) ->
                selection(resultSet))
        .list();
  }

  private Optional<HeadRow> loadHead(
      GraphAttemptManifest manifest, boolean lock) {
    String lockClause = lock ? " FOR UPDATE" : "";
    return jdbc.sql(
            """
            SELECT phase, state_version, last_sequence, head_hash,
                   billing_status, provider_intent_count
            FROM agent_graph_attempt_heads
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            """
                + lockClause)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .query(
            (resultSet, rowNumber) ->
                new HeadRow(
                    GraphAttemptPhase.valueOf(
                        resultSet.getString("phase")),
                    resultSet.getLong("state_version"),
                    resultSet.getInt("last_sequence"),
                    resultSet.getString("head_hash"),
                    GraphBillingStatus.valueOf(
                        resultSet.getString("billing_status")),
                    resultSet.getInt(
                        "provider_intent_count")))
        .optional();
  }

  private List<GraphAttemptEvent> loadEvents(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT
              sequence, event_type, occurred_at, phase_from,
              phase_to, role, run_id, task_id, actor,
              challenge_hash, request_ordinal, request_hash,
              model_requested, previous_head_hash,
              event_hash, current_head_hash
            FROM agent_graph_attempt_events
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            ORDER BY sequence
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .query(
            (resultSet, rowNumber) ->
                event(resultSet))
        .list();
  }

  private Map<GraphRunRole, StoredRunEvidence> loadRuns(
      GraphAttemptManifest manifest) {
    List<StoredRunEvidence> rows =
        jdbc.sql(
                """
                SELECT
                  run.graph_attempt_id,
                  run.graph_manifest_hash,
                  run.graph_role,
                  run.run_id, run.task_id, run.graph_task_hash,
                  run.graph_selector_hash,
                  run.graph_execution_profile_id,
                  run.graph_execution_profile_fingerprint,
                  run.graph_worker_registry_version,
                  run.graph_worker_profile_id,
                  run.graph_worker_profile_fingerprint,
                  run.parent_run_id, run.parent_task_id,
                  run.run_depth, run.parent_run_depth,
                  run.lifecycle_status,
                  run.task_envelope::text AS task_json,
                  run.last_event_sequence,
                  run.resolved_model,
                  run.agent_version,
                  run.verifier_version,
                  run.harness_version,
                  run.tool_registry_version,
                  run.policy_version,
                  run.state_version,
                  run.context_policy_version,
                  run.model_provider,
                  run.model_requested,
                  run.pricing_profile,
                  run.cost_usd,
                  run.token_count,
                  run.latency_ms,
                  run.failure_attribution,
                  run.started_at
                FROM agent_graph_attempt_run_bindings binding
                JOIN agent_runs run
                  ON run.principal_id = binding.principal_id
                 AND run.run_id = binding.run_id
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
                  AND binding.manifest_hash = :manifestHash
                ORDER BY binding.role
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .param("manifestHash", manifest.manifestHash())
            .query(
                (resultSet, rowNumber) ->
                    storedRun(resultSet, manifest.principalId()))
            .list();
    Map<GraphRunRole, StoredRunEvidence> byRole =
        new EnumMap<>(GraphRunRole.class);
    for (StoredRunEvidence row : rows) {
      if (byRole.put(row.selection().role(), row) != null) {
        throw new IllegalArgumentException(
            "duplicate graph Run role");
      }
    }
    return Map.copyOf(byRole);
  }

  private long sealCount(GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT count(*)
            FROM agent_graph_attempt_seals
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(Long.class)
        .single();
  }

  private long prematureTruthCount(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT
              (
                SELECT count(*)
                FROM agent_trace_events trace
                JOIN agent_graph_attempt_run_bindings binding
                  ON binding.principal_id = trace.principal_id
                 AND binding.run_id = trace.run_id
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
              )
              + (
                SELECT count(*)
                FROM agent_run_resource_bindings resource
                JOIN agent_graph_attempt_run_bindings binding
                  ON binding.principal_id = resource.principal_id
                 AND binding.run_id = resource.run_id
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
              )
              + (
                SELECT count(*)
                FROM agent_worker_results result
                JOIN agent_graph_attempt_run_bindings binding
                  ON binding.principal_id = result.principal_id
                 AND binding.run_id = result.child_run_id
                WHERE binding.principal_id = :principalId
                  AND binding.attempt_id = :attemptId
              )
              + (
                SELECT count(*)
                FROM artifacts
                WHERE principal_id = :principalId
                  AND artifact_id = :artifactId
              )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("artifactId", manifest.artifactId())
        .query(Long.class)
        .single();
  }

  private void insertManifest(
      GraphAttemptManifest manifest,
      GraphAttemptEvent claimed,
      Instant occurredAt) {
    int inserted =
        jdbc.sql(
            """
            INSERT INTO agent_graph_attempts (
              principal_id, attempt_id, execution_slot_id,
              manifest_schema_version, graph_protocol_version,
              integrity_profile, case_id, pack_raw_sha256,
              environment_raw_sha256, capture_id,
              capture_request_hash, artifact_id, started_at,
              pricing_profile_fingerprint,
              prompt_surface_fingerprint,
              conductor_surface_fingerprint, reservation_usd,
              maximum_provider_requests, parent_actor, child_actor,
              experiment_arm, experiment_repetition,
              manifest_json, manifest_hash, initial_head_hash,
              created_at
            ) VALUES (
              :principalId, :attemptId, :executionSlotId,
              :schemaVersion, :graphProtocolVersion,
              :integrityProfile, :caseId, :packRawSha256,
              :environmentRawSha256, :captureId,
              :captureRequestHash, :artifactId, :startedAt,
              :pricingProfileFingerprint,
              :promptSurfaceFingerprint,
              :conductorSurfaceFingerprint, :reservationUsd,
              :maximumProviderRequests, :parentActor, :childActor,
              :experimentArm, :experimentRepetition,
              CAST(:manifestJson AS jsonb), :manifestHash,
              :initialHeadHash, :createdAt
            )
            ON CONFLICT DO NOTHING
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param(
            "executionSlotId", manifest.executionSlotId())
        .param("schemaVersion", manifest.schemaVersion())
        .param(
            "graphProtocolVersion",
            manifest.graphProtocolVersion())
        .param("integrityProfile", manifest.integrityProfile())
        .param("caseId", manifest.caseId())
        .param("packRawSha256", manifest.packRawSha256())
        .param(
            "environmentRawSha256",
            manifest.environmentRawSha256())
        .param("captureId", manifest.captureId())
        .param(
            "captureRequestHash",
            manifest.captureRequestHash())
        .param("artifactId", manifest.artifactId())
        .param(
            "startedAt", Timestamp.from(manifest.startedAt()))
        .param(
            "pricingProfileFingerprint",
            manifest.pricingProfileFingerprint())
        .param(
            "promptSurfaceFingerprint",
            manifest.promptSurfaceFingerprint())
        .param(
            "conductorSurfaceFingerprint",
            manifest.conductorSurfaceFingerprint())
        .param("reservationUsd", manifest.reservationUsd())
        .param(
            "maximumProviderRequests",
            manifest.maximumProviderRequests())
        .param("parentActor", manifest.parentActor())
        .param("childActor", manifest.childActor())
        .param("experimentArm", manifest.experiment().arm())
        .param(
            "experimentRepetition",
            manifest.experiment().repetition())
        .param(
            "manifestJson",
            json.writeValueAsString(manifest))
        .param("manifestHash", manifest.manifestHash())
        .param(
            "initialHeadHash", claimed.previousHeadHash())
        .param("createdAt", Timestamp.from(occurredAt))
        .update();
    if (inserted != 1) {
      throw new AlreadyClaimedException();
    }
  }

  private void insertBinding(
      GraphAttemptManifest manifest,
      GraphRunSelection selection,
      Instant occurredAt) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_run_bindings (
              principal_id, attempt_id, manifest_hash, role,
              run_id, task_id, task_hash, selector_hash,
              execution_profile_id,
              execution_profile_fingerprint,
              worker_registry_version, worker_profile_id,
              worker_profile_fingerprint, reserved_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :role,
              :runId, :taskId, :taskHash, :selectorHash,
              :executionProfileId,
              :executionProfileFingerprint,
              :workerRegistryVersion, :workerProfileId,
              :workerProfileFingerprint, :reservedAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("role", selection.role().name())
        .param("runId", selection.runId())
        .param("taskId", selection.taskId())
        .param("taskHash", selection.taskHash())
        .param("selectorHash", selection.selectorHash())
        .param(
            "executionProfileId",
            selection.executionProfileId())
        .param(
            "executionProfileFingerprint",
            selection.executionProfileFingerprint())
        .param(
            "workerRegistryVersion",
            selection.workerRegistryVersion())
        .param(
            "workerProfileId",
            selection.workerProfileId())
        .param(
            "workerProfileFingerprint",
            selection.workerProfileFingerprint())
        .param("reservedAt", Timestamp.from(occurredAt))
        .update();
  }

  private void insertEvent(
      GraphAttemptManifest manifest,
      GraphAttemptEvent event) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_events (
              principal_id, attempt_id, manifest_hash, sequence,
              event_type, occurred_at, phase_from, phase_to,
              role, run_id, task_id, actor, challenge_hash,
              request_ordinal, request_hash, model_requested,
              previous_head_hash, event_hash, current_head_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :sequence,
              :eventType, :occurredAt, :phaseFrom, :phaseTo,
              :role, :runId, :taskId, :actor, :challengeHash,
              :requestOrdinal, :requestHash, :modelRequested,
              :previousHeadHash, :eventHash, :currentHeadHash
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("sequence", event.sequence())
        .param("eventType", event.type().name())
        .param(
            "occurredAt", Timestamp.from(event.occurredAt()))
        .param(
            "phaseFrom",
            event.phaseFrom() == null
                ? null
                : event.phaseFrom().name(),
            Types.VARCHAR)
        .param("phaseTo", event.phaseTo().name())
        .param(
            "role",
            event.role() == null
                ? null
                : event.role().name(),
            Types.VARCHAR)
        .param("runId", event.runId(), Types.VARCHAR)
        .param("taskId", event.taskId(), Types.VARCHAR)
        .param("actor", event.actor(), Types.VARCHAR)
        .param(
            "challengeHash",
            event.challengeHash(),
            Types.CHAR)
        .param(
            "requestOrdinal",
            event.requestOrdinal(),
            Types.SMALLINT)
        .param(
            "requestHash", event.requestHash(), Types.CHAR)
        .param(
            "modelRequested",
            event.modelRequested(),
            Types.VARCHAR)
        .param(
            "previousHeadHash", event.previousHeadHash())
        .param("eventHash", event.eventHash())
        .param("currentHeadHash", event.currentHeadHash())
        .update();
  }

  private void insertHead(
      GraphAttemptManifest manifest,
      GraphAttemptEvent event,
      Instant occurredAt) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_heads (
              principal_id, attempt_id, manifest_hash, phase,
              state_version, last_sequence, head_hash,
              billing_status, provider_intent_count,
              created_at, updated_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :phase,
              :stateVersion, :lastSequence, :headHash,
              'NOT_INVOKED', 0, :createdAt, :updatedAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("phase", event.phaseTo().name())
        .param("stateVersion", (long) event.sequence())
        .param("lastSequence", event.sequence())
        .param("headHash", event.currentHeadHash())
        .param("createdAt", Timestamp.from(occurredAt))
        .param("updatedAt", Timestamp.from(occurredAt))
        .update();
  }

  private int updateHead(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphAttemptEvent event) {
    boolean invoked =
        event.type() == GraphAttemptEventType.PROVIDER_INTENT;
    return jdbc.sql(
            """
            UPDATE agent_graph_attempt_heads
            SET phase = :phase,
                state_version = :stateVersion,
                last_sequence = :lastSequence,
                head_hash = :headHash,
                billing_status = :billingStatus,
                provider_intent_count = :providerIntentCount,
                updated_at = :updatedAt
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
              AND phase = :expectedPhase
              AND state_version = :expectedVersion
              AND last_sequence = :expectedSequence
              AND head_hash = :expectedHeadHash
            """)
        .param("phase", event.phaseTo().name())
        .param("stateVersion", (long) event.sequence())
        .param("lastSequence", event.sequence())
        .param("headHash", event.currentHeadHash())
        .param(
            "billingStatus",
            invoked
                ? GraphBillingStatus.UNKNOWN.name()
                : GraphBillingStatus.NOT_INVOKED.name())
        .param("providerIntentCount", invoked ? 1 : 0)
        .param("updatedAt", Timestamp.from(event.occurredAt()))
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("expectedPhase", expected.phase().name())
        .param("expectedVersion", expected.stateVersion())
        .param("expectedSequence", expected.lastSequence())
        .param("expectedHeadHash", expected.headHash())
        .update();
  }

  private void insertGraphRun(
      GraphAttemptManifest manifest,
      GraphRunSelection selection,
      AgentRun running) {
    boolean child = selection.role() == GraphRunRole.CHILD;
    jdbc.sql(
            """
            INSERT INTO agent_runs (
              principal_id, run_id, task_id,
              parent_run_id, parent_task_id,
              run_depth, parent_run_depth,
              lifecycle_status, task_envelope,
              tool_registry_version, policy_version,
              state_version, context_policy_version,
              model_provider, model_requested, pricing_profile,
              started_at,
              graph_attempt_id, graph_manifest_hash, graph_role,
              graph_task_hash, graph_selector_hash,
              graph_execution_profile_id,
              graph_execution_profile_fingerprint,
              graph_worker_registry_version,
              graph_worker_profile_id,
              graph_worker_profile_fingerprint
            ) VALUES (
              :principalId, :runId, :taskId,
              :parentRunId, :parentTaskId,
              :runDepth, :parentRunDepth,
              'RUNNING', CAST(:taskJson AS jsonb),
              :toolRegistryVersion, :policyVersion,
              :stateVersion, :contextPolicyVersion,
              :modelProvider, :modelRequested, :pricingProfile,
              :startedAt,
              :attemptId, :manifestHash, :role,
              :taskHash, :selectorHash, :executionProfileId,
              :executionProfileFingerprint,
              :workerRegistryVersion,
              :workerProfileId,
              :workerProfileFingerprint
            )
            """)
        .param("principalId", manifest.principalId())
        .param("runId", running.runId())
        .param("taskId", running.task().id())
        .param(
            "parentRunId",
            child
                ? manifest.parentSelection().runId()
                : null,
            Types.VARCHAR)
        .param(
            "parentTaskId",
            child
                ? manifest.parentSelection().taskId()
                : null,
            Types.VARCHAR)
        .param("runDepth", child ? 1 : 0)
        .param(
            "parentRunDepth",
            child ? 0 : null,
            Types.SMALLINT)
        .param(
            "taskJson",
            json.writeValueAsString(running.task()))
        .param(
            "toolRegistryVersion",
            running.task().toolRegistryVersion())
        .param(
            "policyVersion", running.task().policyVersion())
        .param(
            "stateVersion", running.task().stateVersion())
        .param(
            "contextPolicyVersion",
            running.task().contextPolicyVersion())
        .param(
            "modelProvider",
            running.task().modelProvider(),
            Types.VARCHAR)
        .param(
            "modelRequested",
            running.task().modelRequested(),
            Types.VARCHAR)
        .param(
            "pricingProfile",
            running.task().pricingProfile(),
            Types.VARCHAR)
        .param(
            "startedAt", Timestamp.from(running.startedAt()))
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("role", selection.role().name())
        .param("taskHash", selection.taskHash())
        .param("selectorHash", selection.selectorHash())
        .param(
            "executionProfileId",
            selection.executionProfileId())
        .param(
            "executionProfileFingerprint",
            selection.executionProfileFingerprint())
        .param(
            "workerRegistryVersion",
            selection.workerRegistryVersion())
        .param(
            "workerProfileId",
            selection.workerProfileId())
        .param(
            "workerProfileFingerprint",
            selection.workerProfileFingerprint())
        .update();
  }

  private static GraphRunSelection selection(
      ResultSet resultSet) throws SQLException {
    GraphRunSelection selection =
        new GraphRunSelection(
            GraphRunRole.valueOf(resultSet.getString("role")),
            resultSet.getString("run_id"),
            resultSet.getString("task_id"),
            resultSet.getString("task_hash"),
            resultSet.getString("execution_profile_id"),
            resultSet.getString(
                "execution_profile_fingerprint"),
            resultSet.getString("worker_registry_version"),
            resultSet.getString("worker_profile_id"),
            resultSet.getString(
                "worker_profile_fingerprint"));
    if (!selection.selectorHash().equals(
        resultSet.getString("selector_hash"))) {
      throw new IllegalArgumentException(
          "graph binding selector hash is inconsistent");
    }
    return selection;
  }

  private static GraphAttemptEvent event(
      ResultSet resultSet) throws SQLException {
    String from = resultSet.getString("phase_from");
    String role = resultSet.getString("role");
    return new GraphAttemptEvent(
        resultSet.getInt("sequence"),
        GraphAttemptEventType.valueOf(
            resultSet.getString("event_type")),
        resultSet.getTimestamp("occurred_at").toInstant(),
        from == null
            ? null
            : GraphAttemptPhase.valueOf(from),
        GraphAttemptPhase.valueOf(
            resultSet.getString("phase_to")),
        role == null ? null : GraphRunRole.valueOf(role),
        resultSet.getString("run_id"),
        resultSet.getString("task_id"),
        resultSet.getString("actor"),
        resultSet.getString("challenge_hash"),
        resultSet.getObject(
            "request_ordinal", Integer.class),
        resultSet.getString("request_hash"),
        resultSet.getString("model_requested"),
        resultSet.getString("previous_head_hash"),
        resultSet.getString("event_hash"),
        resultSet.getString("current_head_hash"));
  }

  private StoredRunEvidence storedRun(
      ResultSet resultSet, String principalId)
      throws SQLException {
    GraphRunSelection selection =
        new GraphRunSelection(
            GraphRunRole.valueOf(
                resultSet.getString("graph_role")),
            resultSet.getString("run_id"),
            resultSet.getString("task_id"),
            resultSet.getString("graph_task_hash"),
            resultSet.getString(
                "graph_execution_profile_id"),
            resultSet.getString(
                "graph_execution_profile_fingerprint"),
            resultSet.getString(
                "graph_worker_registry_version"),
            resultSet.getString(
                "graph_worker_profile_id"),
            resultSet.getString(
                "graph_worker_profile_fingerprint"));
    if (!selection.selectorHash().equals(
        resultSet.getString("graph_selector_hash"))) {
      throw new IllegalArgumentException(
          "graph Run selector hash is inconsistent");
    }
    if (!AgentRunLifecycle.RUNNING.name().equals(
        resultSet.getString("lifecycle_status"))) {
      throw new IllegalArgumentException(
          "first graph prefix only permits RUNNING Runs");
    }
    if (resultSet.getInt("last_event_sequence") != 0
        || resultSet.getString("resolved_model") != null
        || resultSet.getString("agent_version") != null
        || resultSet.getString("verifier_version") != null
        || resultSet.getString("harness_version") != null
        || resultSet.getBigDecimal("cost_usd")
                .compareTo(BigDecimal.ZERO)
            != 0
        || resultSet.getLong("token_count") != 0L
        || resultSet.getLong("latency_ms") != 0L
        || resultSet.getString("failure_attribution") != null) {
      throw new IllegalArgumentException(
          "first graph prefix cannot contain Run effects or usage");
    }
    TaskEnvelope task =
        json.readValue(
            resultSet.getString("task_json"),
            TaskEnvelope.class);
    if (!task.toolRegistryVersion().equals(
            resultSet.getString("tool_registry_version"))
        || !task.policyVersion().equals(
            resultSet.getString("policy_version"))
        || !task.stateVersion().equals(
            resultSet.getString("state_version"))
        || !task.contextPolicyVersion().equals(
            resultSet.getString("context_policy_version"))
        || !Objects.equals(
            task.modelProvider(),
            resultSet.getString("model_provider"))
        || !Objects.equals(
            task.modelRequested(),
            resultSet.getString("model_requested"))
        || !Objects.equals(
            task.pricingProfile(),
            resultSet.getString("pricing_profile"))) {
      throw new IllegalArgumentException(
          "graph Run mirror columns do not match its Task");
    }
    AgentRun run =
        AgentRun.running(
            resultSet.getString("run_id"),
            principalId,
            task,
            resultSet.getTimestamp("started_at").toInstant());
    Integer parentDepth =
        resultSet.getObject(
            "parent_run_depth", Integer.class);
    return new StoredRunEvidence(
        selection,
        run,
        resultSet.getString("graph_attempt_id"),
        resultSet.getString("graph_manifest_hash"),
        resultSet.getString("parent_run_id"),
        resultSet.getString("parent_task_id"),
        resultSet.getInt("run_depth"),
        parentDepth);
  }

  private static boolean eventsMatchBindings(
      List<GraphAttemptEvent> events,
      GraphAttemptManifest manifest) {
    for (GraphAttemptEvent event : events) {
      if (event.type()
          == GraphAttemptEventType.OPERATOR_APPROVED) {
        GraphOperatorApproval storedApproval =
            new GraphOperatorApproval(
                event.actor(), event.challengeHash());
        if (!storedApproval.matches(manifest)) {
          return false;
        }
      }
      if (event.role() == null) {
        continue;
      }
      GraphRunSelection selection =
          event.role() == GraphRunRole.PARENT
              ? manifest.parentSelection()
              : manifest.childSelection();
      if (!selection.runId().equals(event.runId())
          || !selection.taskId().equals(event.taskId())) {
        return false;
      }
    }
    return true;
  }

  private static boolean runsMatchBindings(
      Map<GraphRunRole, StoredRunEvidence> runs,
      GraphAttemptManifest manifest) {
    StoredRunEvidence parent = runs.get(GraphRunRole.PARENT);
    if (parent != null
        && (!parent.selection().equals(
                manifest.parentSelection())
            || !manifest.attemptId().equals(
                parent.graphAttemptId())
            || !manifest.manifestHash().equals(
                parent.graphManifestHash())
            || !manifest.startedAt().equals(
                parent.run().startedAt())
            || parent.parentRunId() != null
            || parent.parentTaskId() != null
            || parent.runDepth() != 0
            || parent.parentRunDepth() != null)) {
      return false;
    }
    StoredRunEvidence child = runs.get(GraphRunRole.CHILD);
    return child == null
        || (child.selection().equals(
                manifest.childSelection())
            && manifest.attemptId().equals(
                child.graphAttemptId())
            && manifest.manifestHash().equals(
                child.graphManifestHash())
            && manifest.startedAt().equals(
                child.run().startedAt())
            && manifest.parentSelection().runId().equals(
                child.parentRunId())
            && manifest.parentSelection().taskId().equals(
                child.parentTaskId())
            && child.runDepth() == 1
            && Integer.valueOf(0).equals(
                child.parentRunDepth()));
  }

  private static boolean providerIntentMatches(
      List<GraphAttemptEvent> events,
      Map<GraphRunRole, StoredRunEvidence> runs) {
    Optional<GraphAttemptEvent> intent =
        events.stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_INTENT)
            .findFirst();
    if (intent.isEmpty()) {
      return true;
    }
    StoredRunEvidence child = runs.get(GraphRunRole.CHILD);
    return child != null
        && Objects.equals(
            intent.orElseThrow().modelRequested(),
            child.run().task().modelRequested());
  }

  private static void requireExactRun(
      GraphAttemptManifest manifest,
      GraphRunSelection selection,
      AgentRun running) {
    Objects.requireNonNull(running, "running");
    if (running.lifecycle() != AgentRunLifecycle.RUNNING
        || !manifest.principalId().equals(
            running.principalId())
        || !selection.runId().equals(running.runId())
        || !selection.taskId().equals(running.task().id())
        || !selection.taskHash().equals(
            io.emergeos.contracts.IntegrityHashes.taskHash(
                running.task()))
        || !manifest.startedAt().equals(
            running.startedAt())) {
      throw new IllegalArgumentException(
          "RUNNING AgentRun does not match its frozen graph selection");
    }
    if (selection.role() == GraphRunRole.PARENT
        && (running.task().parentId() != null
            || !running.task().delegationChain().isEmpty())) {
      throw new IllegalArgumentException(
          "graph parent Task must be a root Task");
    }
    if (selection.role() == GraphRunRole.CHILD
        && (!manifest.parentSelection().taskId().equals(
                running.task().parentId())
            || !running.task().delegationChain().equals(
                List.of(
                    manifest.parentSelection().taskId())))) {
      throw new IllegalArgumentException(
          "graph child Task must bind the exact parent Task");
    }
    requireDatabaseInstant(
        running.startedAt(), "AgentRun.startedAt");
  }

  private static void requireCursorIdentity(
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor) {
    if (!manifest.principalId().equals(cursor.principalId())
        || !manifest.attemptId().equals(cursor.attemptId())
        || !manifest.manifestHash().equals(
            cursor.manifestHash())) {
      throw new GraphAttemptConflictException(
          "graph attempt cursor identity conflict");
    }
  }

  private static void requireDatabaseInstant(
      Instant instant, String name) {
    Objects.requireNonNull(instant, name);
    if (!instant.equals(
        instant.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException(
          name + " exceeds PostgreSQL microsecond precision");
    }
  }

  private static GraphAttemptVerification.Invalid invalid(
      String reasonCode) {
    return new GraphAttemptVerification.Invalid(reasonCode);
  }

  private static GraphAttemptConflictException conflict() {
    return new GraphAttemptConflictException(
        "graph attempt cursor conflict");
  }

  private static String sqlState(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && sqlException.getSQLState() != null) {
        return sqlException.getSQLState();
      }
      current = current.getCause();
    }
    return null;
  }

  private record HeadRow(
      GraphAttemptPhase phase,
      long stateVersion,
      int lastSequence,
      String headHash,
      GraphBillingStatus billingStatus,
      int providerIntentCount) {

    private GraphAttemptCursor cursor(
        GraphAttemptManifest manifest) {
      int expectedIntentCount =
          lastSequence == 11 ? 1 : 0;
      if (providerIntentCount != expectedIntentCount) {
        throw new IllegalArgumentException(
            "graph head provider count is inconsistent");
      }
      return new GraphAttemptCursor(
          manifest.principalId(),
          manifest.attemptId(),
          manifest.manifestHash(),
          stateVersion,
          lastSequence,
          headHash,
          phase);
    }
  }

  private record StoredRunEvidence(
      GraphRunSelection selection,
      AgentRun run,
      String graphAttemptId,
      String graphManifestHash,
      String parentRunId,
      String parentTaskId,
      int runDepth,
      Integer parentRunDepth) {}

  private record ManifestRow(
      String principalId,
      String attemptId,
      String executionSlotId,
      String schemaVersion,
      String graphProtocolVersion,
      String integrityProfile,
      String caseId,
      String packRawSha256,
      String environmentRawSha256,
      String captureId,
      String captureRequestHash,
      String artifactId,
      Instant startedAt,
      String pricingProfileFingerprint,
      String promptSurfaceFingerprint,
      String conductorSurfaceFingerprint,
      BigDecimal reservationUsd,
      int maximumProviderRequests,
      String parentActor,
      String childActor,
      String experimentArm,
      int experimentRepetition,
      String manifestJson,
      String manifestHash,
      String initialHeadHash) {

    private static ManifestRow from(ResultSet resultSet)
        throws SQLException {
      return new ManifestRow(
          resultSet.getString("principal_id"),
          resultSet.getString("attempt_id"),
          resultSet.getString("execution_slot_id"),
          resultSet.getString("manifest_schema_version"),
          resultSet.getString("graph_protocol_version"),
          resultSet.getString("integrity_profile"),
          resultSet.getString("case_id"),
          resultSet.getString("pack_raw_sha256"),
          resultSet.getString(
              "environment_raw_sha256"),
          resultSet.getString("capture_id"),
          resultSet.getString("capture_request_hash"),
          resultSet.getString("artifact_id"),
          resultSet.getTimestamp("started_at").toInstant(),
          resultSet.getString(
              "pricing_profile_fingerprint"),
          resultSet.getString(
              "prompt_surface_fingerprint"),
          resultSet.getString(
              "conductor_surface_fingerprint"),
          resultSet.getBigDecimal("reservation_usd"),
          resultSet.getInt("maximum_provider_requests"),
          resultSet.getString("parent_actor"),
          resultSet.getString("child_actor"),
          resultSet.getString("experiment_arm"),
          resultSet.getInt("experiment_repetition"),
          resultSet.getString("manifest_json"),
          resultSet.getString("manifest_hash"),
          resultSet.getString("initial_head_hash"));
    }

    private boolean matches(GraphAttemptManifest manifest) {
      return principalId.equals(manifest.principalId())
          && attemptId.equals(manifest.attemptId())
          && executionSlotId.equals(
              manifest.executionSlotId())
          && schemaVersion.equals(manifest.schemaVersion())
          && graphProtocolVersion.equals(
              manifest.graphProtocolVersion())
          && integrityProfile.equals(
              manifest.integrityProfile())
          && caseId.equals(manifest.caseId())
          && packRawSha256.equals(
              manifest.packRawSha256())
          && environmentRawSha256.equals(
              manifest.environmentRawSha256())
          && captureId.equals(manifest.captureId())
          && captureRequestHash.equals(
              manifest.captureRequestHash())
          && artifactId.equals(manifest.artifactId())
          && startedAt.equals(manifest.startedAt())
          && pricingProfileFingerprint.equals(
              manifest.pricingProfileFingerprint())
          && promptSurfaceFingerprint.equals(
              manifest.promptSurfaceFingerprint())
          && conductorSurfaceFingerprint.equals(
              manifest.conductorSurfaceFingerprint())
          && reservationUsd.compareTo(
                  manifest.reservationUsd())
              == 0
          && maximumProviderRequests
              == manifest.maximumProviderRequests()
          && parentActor.equals(manifest.parentActor())
          && childActor.equals(manifest.childActor())
          && experimentArm.equals(
              manifest.experiment().arm())
          && experimentRepetition
              == manifest.experiment().repetition()
          && manifestHash.equals(manifest.manifestHash())
          && initialHeadHash.equals(
              GraphAttemptEvent.emptyHead(
                  manifest.attemptId(),
                  manifest.manifestHash()));
    }
  }

  private static final class AlreadyClaimedException
      extends RuntimeException {

    private AlreadyClaimedException() {
      super(null, null, false, false);
    }
  }

  enum ProbePoint {
    AFTER_MANIFEST_INSERT,
    AFTER_BINDINGS_INSERT,
    AFTER_RUN_INSERT,
    AFTER_EVENT_INSERT,
    AFTER_HEAD_UPDATE,
    VERIFIED_READ_TRANSACTION
  }

  @FunctionalInterface
  interface Probe {

    void hit(ProbePoint point);
  }
}
