package io.emergeos.adapters.postgres;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
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
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphPricingSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.GraphTerminalSeal;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.GraphAttemptReader;
import io.emergeos.core.port.GraphAttemptStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
  private final DataSource dataSource;
  private final TransactionTemplate writes;
  private final TransactionTemplate verifiedReads;
  private final JsonMapper json;
  private final PostgresArtifactLineageStore artifacts;
  private final Probe probe;

  PostgresGraphAttemptStore(DataSource dataSource) {
    this(dataSource, ignored -> {});
  }

  PostgresGraphAttemptStore(
      DataSource dataSource, Probe probe) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.dataSource = dataSource;
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
    this.artifacts =
        new PostgresArtifactLineageStore(
            dataSource,
            transactionManager,
            statement ->
                this.probe.hit(
                    switch (statement) {
                      case ARTIFACT_ROW_INSERTED ->
                          ProbePoint
                              .AFTER_PARENT_ARTIFACT_ROW_INSERT;
                      case ARTIFACT_VERSION_INSERTED ->
                          ProbePoint
                              .AFTER_PARENT_ARTIFACT_VERSION_INSERT;
                    }));
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
              ignored ->
                  claimInCurrentTransaction(
                      manifest, claimed, occurredAt));
      return new CreateResult.Created(
          Objects.requireNonNull(
              cursor, "graph create transaction result"));
    } catch (AlreadyClaimedException replay) {
      return new CreateResult.AlreadyExists();
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  OwnerClaim claimForOwner(
      GraphAttemptManifest manifest,
      GraphAttemptManifest predecessor,
      Duration challengeTtl) {
    return claimForOwner(
        manifest,
        predecessor,
        challengeTtl,
        freezeAuthorityIdentity());
  }

  OwnerClaim claimForOwner(
      GraphAttemptManifest manifest,
      GraphAttemptManifest predecessor,
      Duration challengeTtl,
      AuthorityIdentity expectedIdentity) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(challengeTtl, "challengeTtl");
    if (challengeTtl.isZero()
        || challengeTtl.isNegative()
        || challengeTtl.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException(
          "owner challenge TTL must be within (0, 5 minutes]");
    }
    requirePack010Sequence(manifest, predecessor);
    try {
      OwnerClaim claim =
          writes.execute(
              ignored -> {
                requireAuthorityIdentity(expectedIdentity);
                if (predecessor != null) {
                  GraphAttemptSnapshot prior =
                      requireValidPredecessor(
                          loadVerified(predecessor, true));
                  requireExactPredecessor(
                      manifest, predecessor, prior);
                  probe.hit(
                      ProbePoint.AFTER_PREDECESSOR_VERIFIED);
                }
                Instant occurredAt = databaseNow();
                GraphAttemptEvent claimed =
                    GraphAttemptEvent.claimed(
                        manifest, occurredAt);
                GraphAttemptCursor cursor =
                    claimInCurrentTransaction(
                        manifest, claimed, occurredAt);
                return new OwnerClaim(
                    cursor, occurredAt.plus(challengeTtl));
              });
      return Objects.requireNonNull(
          claim, "owner claim transaction result");
    } catch (AlreadyClaimedException replay) {
      throw new GraphAttemptConflictException(
          "execution slot was already claimed");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  GraphAttemptCursor approveForOwner(
      GraphAttemptManifest manifest,
      OwnerClaim claim) {
    return approveForOwner(
        manifest, claim, freezeAuthorityIdentity());
  }

  GraphAttemptCursor approveForOwner(
      GraphAttemptManifest manifest,
      OwnerClaim claim,
      AuthorityIdentity expectedIdentity) {
    Objects.requireNonNull(claim, "claim");
    return advance(
        manifest,
        claim.cursor(),
        GraphAttemptEventType.OPERATOR_APPROVED,
        GraphAttemptPhase.OPERATOR_APPROVED,
        null,
        null,
        GraphOperatorApproval.ownerTty(manifest),
        null,
        null,
        false,
        claim.expiresAt(),
        expectedIdentity);
  }

  void requireOwnerPermitFresh(
      Instant expiresAt,
      AuthorityIdentity expectedIdentity) {
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    try {
      verifiedReads.executeWithoutResult(
          ignored -> {
            requireAuthorityIdentity(expectedIdentity);
            if (!databaseNow().isBefore(expiresAt)) {
              throw new GraphAttemptConflictException(
                  "owner capability expired");
            }
          });
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  ProviderSessionClaim claimProviderSessionIntent(
      GraphAttemptManifest manifest,
      String revision,
      GraphProviderIntent intent,
      Instant expiresAt,
      AuthorityIdentity expectedIdentity) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(expectedIdentity, "expectedIdentity");
    if (!List.of("r1", "r2", "r3").contains(revision)
        || intent.requestOrdinal() != 1) {
      throw new IllegalArgumentException(
          "provider session intent identity is invalid");
    }
    try {
      ProviderSessionClaim claim =
          writes.execute(
              ignored -> {
                requireAuthorityIdentity(expectedIdentity);
                GraphAttemptVerification verification =
                    loadVerified(manifest, true);
                if (!(verification
                    instanceof GraphAttemptVerification.Valid valid)) {
                  if (verification
                      instanceof GraphAttemptVerification.Missing) {
                    throw new GraphAttemptConflictException(
                        "provider session intent attempt is missing");
                  }
                  throw new GraphAttemptIntegrityException();
                }
                GraphAttemptSnapshot snapshot = valid.snapshot();
                if (snapshot.cursor().lastSequence() != 7
                    || snapshot.cursor().phase()
                        != GraphAttemptPhase.EGRESS_CONSUMED) {
                  throw new GraphAttemptConflictException(
                      "provider session intent requires durable egress");
                }
                Instant createdAt = databaseNow();
                if (!createdAt.isBefore(expiresAt)) {
                  throw new GraphAttemptConflictException(
                      "owner capability expired");
                }
                String intentHash =
                    IntegrityHashes.utf8ContentHash(
                        String.join(
                            "\u001f",
                            "emergeos.provider-session-intent.v1",
                            manifest.principalId(),
                            manifest.attemptId(),
                            manifest.manifestHash(),
                            revision,
                            snapshot.cursor().headHash(),
                            Integer.toString(intent.requestOrdinal()),
                            intent.requestHash(),
                            intent.modelRequested(),
                            expiresAt.toString()));
                int inserted =
                    jdbc.sql(
                            """
                            INSERT INTO public.agent_graph_provider_session_intents (
                              principal_id, attempt_id, manifest_hash,
                              revision, cursor_sequence, cursor_head_hash,
                              request_ordinal, request_hash, model_requested,
                              intent_hash, created_at, expires_at
                            ) VALUES (
                              :principalId, :attemptId, :manifestHash,
                              :revision, 7, :cursorHeadHash,
                              :requestOrdinal, :requestHash, :modelRequested,
                              :intentHash, :createdAt, :expiresAt
                            )
                            ON CONFLICT (principal_id, attempt_id) DO NOTHING
                            """)
                        .param("principalId", manifest.principalId())
                        .param("attemptId", manifest.attemptId())
                        .param("manifestHash", manifest.manifestHash())
                        .param("revision", revision)
                        .param(
                            "cursorHeadHash",
                            snapshot.cursor().headHash())
                        .param(
                            "requestOrdinal", intent.requestOrdinal())
                        .param("requestHash", intent.requestHash())
                        .param(
                            "modelRequested", intent.modelRequested())
                        .param("intentHash", intentHash)
                        .param("createdAt", Timestamp.from(createdAt))
                        .param("expiresAt", Timestamp.from(expiresAt))
                        .update();
                if (inserted != 1) {
                  throw new GraphAttemptConflictException(
                      "provider session intent was already claimed");
                }
                probe.hit(
                    ProbePoint.AFTER_PROVIDER_SESSION_INTENT_INSERT);
                ProviderSessionClaim stored =
                    jdbc.sql(
                            """
                            SELECT cursor_sequence, cursor_head_hash,
                                   intent_hash, expires_at
                            FROM public.agent_graph_provider_session_intents
                            WHERE principal_id = :principalId
                              AND attempt_id = :attemptId
                              AND manifest_hash = :manifestHash
                              AND revision = :revision
                              AND request_ordinal = :requestOrdinal
                              AND request_hash = :requestHash
                              AND model_requested = :modelRequested
                            """)
                        .param("principalId", manifest.principalId())
                        .param("attemptId", manifest.attemptId())
                        .param("manifestHash", manifest.manifestHash())
                        .param("revision", revision)
                        .param(
                            "requestOrdinal", intent.requestOrdinal())
                        .param("requestHash", intent.requestHash())
                        .param(
                            "modelRequested", intent.modelRequested())
                        .query(
                            (row, rowNumber) ->
                                new ProviderSessionClaim(
                                    new GraphAttemptCursor(
                                        manifest.principalId(),
                                        manifest.attemptId(),
                                        manifest.manifestHash(),
                                        row.getInt("cursor_sequence"),
                                        row.getInt("cursor_sequence"),
                                        row.getString("cursor_head_hash"),
                                        GraphAttemptPhase.EGRESS_CONSUMED),
                                    row.getString("intent_hash"),
                                    row.getTimestamp("expires_at")
                                        .toInstant()))
                        .single();
                if (!stored.intentHash().equals(intentHash)
                    || !stored.cursor().equals(snapshot.cursor())
                    || !stored.expiresAt().equals(expiresAt)) {
                  throw new GraphAttemptIntegrityException();
                }
                return stored;
              });
      return Objects.requireNonNull(
          claim, "provider session intent transaction result");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private GraphAttemptCursor claimInCurrentTransaction(
      GraphAttemptManifest manifest,
      GraphAttemptEvent claimed,
      Instant occurredAt) {
    insertManifest(manifest, claimed, occurredAt);
    probe.hit(ProbePoint.AFTER_MANIFEST_INSERT);
    for (GraphRunSelection selection : manifest.selections()) {
      insertBinding(manifest, selection, occurredAt);
    }
    probe.hit(ProbePoint.AFTER_BINDINGS_INSERT);
    insertEvent(manifest, claimed);
    probe.hit(ProbePoint.AFTER_EVENT_INSERT);
    insertHead(manifest, claimed, occurredAt);
    probe.hit(ProbePoint.AFTER_HEAD_UPDATE);
    GraphAttemptCursor expected = claimed.cursor(manifest);
    return requireMutableSnapshot(
            loadVerified(manifest, true), expected)
        .cursor();
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
  public GraphAttemptCursor providerAttributed(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      Instant occurredAt) {
    return providerAttributed(
        manifest, expected, attribution, null, occurredAt);
  }

  @Override
  public GraphAttemptCursor providerFailureAttributed(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      GraphAttributedFailureCode failureCode,
      Instant occurredAt) {
    return providerAttributed(
        manifest,
        expected,
        attribution,
        Objects.requireNonNull(failureCode, "failureCode"),
        occurredAt);
  }

  private GraphAttemptCursor providerAttributed(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      GraphAttributedFailureCode failureCode,
      Instant occurredAt) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(attribution, "attribution");
    requireCursorIdentity(manifest, expected);
    requireDatabaseInstant(occurredAt, "occurredAt");
    GraphAttemptEvent event =
        GraphAttemptEvent.providerAttributed(
            expected,
            manifest.childSelection(),
            attribution,
            occurredAt);
    try {
      GraphAttemptCursor advanced =
          writes.execute(
              ignored -> {
                GraphAttemptSnapshot before =
                    requireMutableSnapshot(
                        loadVerified(manifest, true), expected);
                requireProviderAttribution(
                    manifest, before, attribution);
                insertProviderAttribution(
                    manifest,
                    event,
                    attribution,
                    occurredAt);
                probe.hit(
                    ProbePoint.AFTER_PROVIDER_ATTRIBUTION_INSERT);
                insertEvent(manifest, event);
                probe.hit(ProbePoint.AFTER_EVENT_INSERT);
                if (updateHead(manifest, expected, event) != 1) {
                  throw conflict();
                }
                probe.hit(ProbePoint.AFTER_HEAD_UPDATE);
                GraphAttemptCursor committed =
                    event.cursor(manifest);
                if (failureCode != null) {
                  if (attribution.requestOrdinal() != 2
                      || expected.lastSequence() != 13
                      || committed.lastSequence() != 14) {
                    throw new IllegalArgumentException(
                        "durable attributed failure requires request 2 at sequence 14");
                  }
                  String provenanceHash =
                      jdbc.sql(
                              "SELECT public."
                                  + "agent_graph_record_attributed_failure_v11("
                                  + ":principalId, :attemptId, :manifestHash, "
                                  + ":previousSequence, :previousHeadHash, "
                                  + ":failureCode)")
                          .param("principalId", manifest.principalId())
                          .param("attemptId", manifest.attemptId())
                          .param("manifestHash", manifest.manifestHash())
                          .param("previousSequence", expected.lastSequence())
                          .param("previousHeadHash", expected.headHash())
                          .param("failureCode", failureCode.name())
                          .query(String.class)
                          .single();
                  if (provenanceHash == null
                      || !provenanceHash.matches("[a-f0-9]{64}")) {
                    throw new GraphAttemptIntegrityException();
                  }
                  probe.hit(
                      ProbePoint.AFTER_ATTRIBUTED_FAILURE_OUTCOME_INSERT);
                }
                GraphAttemptCursor verified =
                    requireMutableSnapshot(
                            loadVerified(manifest, true),
                            committed)
                        .cursor();
                probe.hit(
                    ProbePoint.AFTER_PROVIDER_ATTRIBUTION_VERIFIED_READ);
                return verified;
              });
      return Objects.requireNonNull(
          advanced, "graph attribution transaction result");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException
        | IllegalArgumentException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      String state = sqlState(failure);
      if ("23505".equals(state) || "40001".equals(state)) {
        throw conflict();
      }
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  @Override
  public GraphAttemptCursor completeChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRunContext parent,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      Instant occurredAt) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(terminalChild, "terminalChild");
    requireCursorIdentity(manifest, expected);
    requireDatabaseInstant(occurredAt, "occurredAt");
    if (terminalChild.result().status() == RunStatus.SUCCEEDED
        && candidate == null) {
      throw new IllegalArgumentException(
          "successful child terminal truth requires its Candidate");
    }
    GraphTerminalBinding binding =
        GraphTerminalBinding.child(terminalChild, workerResult);
    GraphAttemptEvent event =
        GraphAttemptEvent.terminal(
            expected,
            GraphAttemptEventType.CHILD_TERMINAL,
            GraphAttemptPhase.CHILD_TERMINAL,
            manifest.childSelection(),
            binding,
            occurredAt);
    try {
      GraphAttemptCursor advanced =
          writes.execute(
              ignored -> {
                GraphAttemptSnapshot before =
                    requireMutableSnapshot(
                        loadVerified(manifest, true), expected);
                requireChildTerminal(
                    manifest,
                    before,
                    parent,
                    terminalChild,
                    occurredAt);
                GraphAttemptSnapshot expectedTerminal =
                    expectedChildTerminalSnapshot(
                        before,
                        event,
                        terminalChild,
                        binding,
                        candidate,
                        workerResult);
                authorizeTerminalMutation(
                    manifest, GraphRunRole.CHILD);
                if (candidate != null) {
                  insertCandidate(
                      manifest, candidate, occurredAt);
                  probe.hit(ProbePoint.AFTER_CANDIDATE_INSERT);
                }
                if (workerResult != null) {
                  insertWorkerResult(
                      parent, terminalChild, workerResult);
                  probe.hit(
                      ProbePoint.AFTER_WORKER_RESULT_INSERT);
                }
                insertTerminalTrace(
                    terminalChild,
                    ProbePoint.AFTER_TERMINAL_TRACE_ROW_INSERT);
                probe.hit(ProbePoint.AFTER_TERMINAL_TRACE_INSERT);
                insertTerminalResourceBindings(
                    terminalChild,
                    ProbePoint
                        .AFTER_TERMINAL_RESOURCE_BINDING_ROW_INSERT);
                probe.hit(
                    ProbePoint.AFTER_TERMINAL_RESOURCE_BINDINGS_INSERT);
                updateTerminalRun(
                    manifest, GraphRunRole.CHILD, terminalChild);
                probe.hit(ProbePoint.AFTER_TERMINAL_RUN_UPDATE);
                insertTerminalBinding(
                    manifest, event, binding);
                probe.hit(ProbePoint.AFTER_TERMINAL_BINDING_INSERT);
                insertEvent(manifest, event);
                probe.hit(ProbePoint.AFTER_EVENT_INSERT);
                if (updateHead(manifest, expected, event) != 1) {
                  throw conflict();
                }
                probe.hit(ProbePoint.AFTER_HEAD_UPDATE);
                GraphAttemptCursor committed =
                    event.cursor(manifest);
                GraphAttemptSnapshot verified =
                    requireMutableSnapshot(
                        loadVerified(manifest, true), committed);
                if (!verified.equals(expectedTerminal)) {
                  throw new GraphAttemptIntegrityException();
                }
                probe.hit(
                    ProbePoint.AFTER_CHILD_TERMINAL_VERIFIED_READ);
                return verified.cursor();
              });
      return Objects.requireNonNull(
          advanced, "child terminal transaction result");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException
        | IllegalArgumentException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      String state = sqlState(failure);
      if ("23505".equals(state) || "40001".equals(state)) {
        throw conflict();
      }
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  @Override
  public GraphAttemptCursor completeParentAndSeal(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun terminalParent,
      ArtifactLineage artifact,
      Instant occurredAt) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(terminalParent, "terminalParent");
    requireCursorIdentity(manifest, expected);
    requireDatabaseInstant(occurredAt, "occurredAt");
    GraphTerminalBinding parentBinding =
        GraphTerminalBinding.parent(terminalParent, artifact);
    GraphAttemptEvent parentEvent =
        GraphAttemptEvent.terminal(
            expected,
            GraphAttemptEventType.PARENT_TERMINAL,
            GraphAttemptPhase.PARENT_TERMINAL,
            manifest.parentSelection(),
            parentBinding,
            occurredAt);
    try {
      GraphAttemptCursor advanced =
          writes.execute(
              ignored -> {
                GraphAttemptSnapshot before =
                    requireMutableSnapshot(
                        loadVerified(manifest, true), expected);
                requireParentTerminal(
                    manifest,
                    before,
                    terminalParent,
                    artifact,
                    occurredAt);
                GraphAttemptCursor preSeal =
                    parentEvent.cursor(manifest);
                GraphAttemptOutcome outcome =
                    terminalParent.result().status()
                                    == RunStatus.SUCCEEDED
                                && before
                                        .childRun()
                                        .result()
                                        .status()
                                    == RunStatus.SUCCEEDED
                        ? GraphAttemptOutcome.SUCCEEDED
                        : GraphAttemptOutcome.FAILED;
                List<String> attributionHashes =
                    before.providerAttributions().stream()
                        .map(
                            GraphProviderAttribution
                                ::attributionHash)
                        .toList();
                GraphTerminalBinding childBinding =
                    before.terminalBindings().stream()
                        .filter(
                            binding ->
                                binding.role()
                                    == GraphRunRole.CHILD)
                        .findFirst()
                        .orElseThrow(
                            GraphAttemptIntegrityException::new);
                String sealHash =
                    GraphTerminalSeal.computeHash(
                        manifest.attemptId(),
                        manifest.manifestHash(),
                        17,
                        preSeal.headHash(),
                        outcome,
                        GraphBillingStatus.ATTRIBUTED,
                        attributionHashes,
                        before.candidate() == null
                            ? null
                            : before.candidate().candidateRef(),
                        before.candidate() == null
                            ? null
                            : before.candidate().integrityHash(),
                        childBinding.terminalHash(),
                        parentBinding.terminalHash(),
                        occurredAt);
                GraphAttemptEvent sealEvent =
                    GraphAttemptEvent.sealed(
                        preSeal, sealHash, occurredAt);
                GraphTerminalSeal seal =
                    new GraphTerminalSeal(
                        manifest.attemptId(),
                        manifest.manifestHash(),
                        17,
                        preSeal.headHash(),
                        sealEvent.currentHeadHash(),
                        outcome,
                        GraphBillingStatus.ATTRIBUTED,
                        attributionHashes,
                        before.candidate() == null
                            ? null
                            : before.candidate().candidateRef(),
                        before.candidate() == null
                            ? null
                            : before.candidate().integrityHash(),
                        childBinding.terminalHash(),
                        parentBinding.terminalHash(),
                        sealHash,
                        occurredAt);
                GraphAttemptSnapshot expectedTerminal =
                    expectedSealedSnapshot(
                        before,
                        parentEvent,
                        sealEvent,
                        terminalParent,
                        parentBinding,
                        seal,
                        outcome,
                        artifact);

                authorizeTerminalMutation(
                    manifest, GraphRunRole.PARENT);
                if (artifact != null) {
                  ArtifactLineage committedArtifact =
                      artifacts.create(artifact);
                  if (!artifact.equals(committedArtifact)) {
                    throw new GraphAttemptIntegrityException();
                  }
                  probe.hit(ProbePoint.AFTER_PARENT_ARTIFACT_INSERT);
                }
                insertTerminalTrace(
                    terminalParent,
                    ProbePoint.AFTER_PARENT_TRACE_ROW_INSERT);
                probe.hit(ProbePoint.AFTER_PARENT_TRACE_INSERT);
                insertTerminalResourceBindings(
                    terminalParent,
                    ProbePoint
                        .AFTER_PARENT_RESOURCE_BINDING_ROW_INSERT);
                probe.hit(
                    ProbePoint.AFTER_PARENT_RESOURCE_BINDINGS_INSERT);
                updateTerminalRun(
                    manifest, GraphRunRole.PARENT, terminalParent);
                probe.hit(ProbePoint.AFTER_PARENT_RUN_UPDATE);
                insertTerminalBinding(
                    manifest, parentEvent, parentBinding);
                probe.hit(
                    ProbePoint.AFTER_PARENT_TERMINAL_BINDING_INSERT);
                insertEvent(manifest, parentEvent);
                probe.hit(ProbePoint.AFTER_PARENT_EVENT_INSERT);
                if (updateHead(manifest, expected, parentEvent) != 1) {
                  throw conflict();
                }
                probe.hit(ProbePoint.AFTER_PARENT_HEAD_UPDATE);
                insertSeal(manifest, seal);
                probe.hit(ProbePoint.AFTER_TERMINAL_SEAL_INSERT);
                insertEvent(manifest, sealEvent);
                probe.hit(ProbePoint.AFTER_SEAL_EVENT_INSERT);
                if (updateHead(manifest, preSeal, sealEvent) != 1) {
                  throw conflict();
                }
                probe.hit(ProbePoint.AFTER_SEALED_HEAD_UPDATE);
                GraphAttemptSnapshot verified =
                    requireMutableSnapshot(
                        loadVerified(manifest, true),
                        sealEvent.cursor(manifest));
                if (!verified.equals(expectedTerminal)) {
                  throw new GraphAttemptIntegrityException();
                }
                probe.hit(
                    ProbePoint.AFTER_SEALED_VERIFIED_READ);
                return verified.cursor();
              });
      return Objects.requireNonNull(
          advanced, "parent terminal transaction result");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException
        | IllegalArgumentException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      String state = sqlState(failure);
      if ("23505".equals(state) || "40001".equals(state)) {
        throw conflict();
      }
      throw new GraphAttemptIntegrityException(failure);
    }
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

  GraphAttemptReader openRestrictedReader() {
    PostgresGraphAttemptAccess.ReaderAuthority authority =
        verifiedReads.execute(
            ignored ->
                PostgresGraphAttemptAccess.verifyReaderAuthority(
                    jdbc, null));
    PostgresGraphAttemptAccess.ReaderAuthority frozen =
        Objects.requireNonNull(
            authority, "reader authority transaction result");
    return expected -> findVerifiedRestricted(expected, frozen);
  }

  AuthorityIdentity freezeAuthorityIdentity() {
    return loadAuthorityIdentity();
  }

  PostgresGraphAttemptStore bindAuthorityIdentity(
      AuthorityIdentity expected) {
    return new PostgresGraphAttemptStore(
        new AuthorityBoundDataSource(
            dataSource,
            Objects.requireNonNull(expected, "expected")),
        probe);
  }

  void requireAuthorityIdentity(AuthorityIdentity expected) {
    if (!Objects.requireNonNull(expected, "expected")
        .equals(loadAuthorityIdentity())) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private AuthorityIdentity loadAuthorityIdentity() {
    return loadAuthorityIdentity(jdbc);
  }

  private static AuthorityIdentity loadAuthorityIdentity(
      JdbcClient identityJdbc) {
    try {
      return identityJdbc.sql(
              """
              SELECT
                session_user AS session_user_name,
                current_user AS current_user_name,
                current_database() AS database_name,
                current_schema() AS schema_name,
                current_setting('search_path') AS search_path,
                role.oid::text AS role_oid,
                database.oid::text AS database_oid,
                COALESCE(
                  pg_catalog.inet_server_addr()::text,
                  'local-socket'
                ) AS server_address,
                COALESCE(
                  pg_catalog.inet_server_port(), -1
                ) AS server_port,
                current_setting('server_version_num') AS server_version_num,
                role.rolcanlogin,
                role.rolsuper,
                role.rolcreatedb,
                role.rolcreaterole,
                role.rolinherit,
                role.rolreplication,
                role.rolbypassrls,
                pg_catalog.concat_ws(
                  E'\\x1f',
                  COALESCE(database.datacl::text, 'NULL'),
                  COALESCE((
                    SELECT namespace.nspacl::text
                    FROM pg_catalog.pg_namespace namespace
                    WHERE namespace.nspname = 'public'), 'NULL'),
                  COALESCE((
                    SELECT pg_catalog.string_agg(
                      relation.oid::text || ':'
                        || COALESCE(relation.relacl::text, 'NULL'),
                      ',' ORDER BY relation.oid)
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND relation.relkind IN (
                        'r', 'p', 'S', 'v', 'm')), ''),
                  COALESCE((
                    SELECT pg_catalog.string_agg(
                      procedure.oid::text || ':'
                        || COALESCE(procedure.proacl::text, 'NULL'),
                      ',' ORDER BY procedure.oid)
                    FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = 'public'), ''),
                  COALESCE((
                    SELECT pg_catalog.string_agg(
                      attribute.attrelid::text || ':'
                        || attribute.attnum::text || ':'
                        || COALESCE(attribute.attacl::text, 'NULL'),
                      ',' ORDER BY attribute.attrelid, attribute.attnum)
                    FROM pg_catalog.pg_attribute attribute
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = attribute.attrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND attribute.attnum > 0
                      AND NOT attribute.attisdropped), ''),
                  COALESCE((
                    SELECT pg_catalog.string_agg(
                      trigger.tgrelid::text || ':'
                        || trigger.tgname || ':'
                        || trigger.tgfoid::text || ':'
                        || trigger.tgenabled::text || ':'
                        || trigger.tgtype::text || ':'
                        || trigger.tgattr::text || ':'
                        || COALESCE(trigger.tgqual::text, 'NULL') || ':'
                        || pg_catalog.encode(trigger.tgargs, 'hex'),
                      ',' ORDER BY trigger.tgrelid, trigger.tgname)
                    FROM pg_catalog.pg_trigger trigger
                    JOIN pg_catalog.pg_class relation
                      ON relation.oid = trigger.tgrelid
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND NOT trigger.tgisinternal), ''),
                  COALESCE((
                    SELECT pg_catalog.string_agg(
                      defaults.oid::text || ':'
                        || defaults.defaclrole::text || ':'
                        || defaults.defaclnamespace::text || ':'
                        || defaults.defaclobjtype::text || ':'
                        || defaults.defaclacl::text,
                      ',' ORDER BY defaults.oid)
                    FROM pg_catalog.pg_default_acl defaults
                    WHERE defaults.defaclnamespace = 0
                       OR defaults.defaclnamespace = (
                         SELECT namespace.oid
                         FROM pg_catalog.pg_namespace namespace
                         WHERE namespace.nspname = 'public')), '')
                ) AS authority_acl_surface,
                (
                  SELECT count(*)
                  FROM pg_catalog.pg_auth_members membership
                  WHERE membership.member = role.oid
                ) AS memberships,
                pg_catalog.pg_has_role(
                  session_user, current_user, 'MEMBER')
                  AS session_can_assume_current
              FROM pg_catalog.pg_roles role
              JOIN pg_catalog.pg_database database
                ON database.datname = current_database()
              WHERE role.rolname = current_user
              """)
          .query(
              (resultSet, rowNumber) ->
                  new AuthorityIdentity(
                      resultSet.getString("session_user_name"),
                      resultSet.getString("current_user_name"),
                      resultSet.getString("database_name"),
                      resultSet.getString("schema_name"),
                      resultSet.getString("search_path"),
                      resultSet.getString("role_oid"),
                      resultSet.getString("database_oid"),
                      resultSet.getString("server_address"),
                      resultSet.getInt("server_port"),
                      resultSet.getString("server_version_num"),
                      resultSet.getBoolean("rolcanlogin"),
                      resultSet.getBoolean("rolsuper"),
                      resultSet.getBoolean("rolcreatedb"),
                      resultSet.getBoolean("rolcreaterole"),
                      resultSet.getBoolean("rolinherit"),
                      resultSet.getBoolean("rolreplication"),
                      resultSet.getBoolean("rolbypassrls"),
                      resultSet.getString("authority_acl_surface"),
                      resultSet.getLong("memberships"),
                      resultSet.getBoolean(
                          "session_can_assume_current")))
          .single()
          .requireDirectLogin();
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private GraphAttemptVerification findVerifiedRestricted(
      GraphAttemptManifest expected,
      PostgresGraphAttemptAccess.ReaderAuthority authority) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(authority, "authority");
    GraphAttemptVerification result =
        verifiedReads.execute(
            ignored -> {
              PostgresGraphAttemptAccess.verifyReaderAuthority(
                  jdbc, authority);
              probe.hit(ProbePoint.VERIFIED_READ_TRANSACTION);
              return loadVerified(expected, false);
            });
    return Objects.requireNonNull(
        result, "restricted graph read transaction result");
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
    return advance(
        manifest,
        expected,
        type,
        phase,
        selection,
        running,
        approval,
        providerIntent,
        occurredAt,
        insertRun,
        null,
        null);
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
      boolean insertRun,
      Instant notAfter) {
    return advance(
        manifest,
        expected,
        type,
        phase,
        selection,
        running,
        approval,
        providerIntent,
        occurredAt,
        insertRun,
        notAfter,
        null);
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
      boolean insertRun,
      Instant notAfter,
      AuthorityIdentity expectedIdentity) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    requireCursorIdentity(manifest, expected);
    if (expectedIdentity == null) {
      requireDatabaseInstant(occurredAt, "occurredAt");
    }
    try {
      GraphAttemptCursor advanced =
          writes.execute(
              ignored -> {
                if (expectedIdentity != null) {
                  requireAuthorityIdentity(expectedIdentity);
                }
                Instant eventTime =
                    expectedIdentity == null
                        ? occurredAt
                        : databaseNow();
                if (notAfter != null
                    && !eventTime.isBefore(notAfter)) {
                  throw new GraphAttemptConflictException(
                      "owner challenge expired");
                }
                GraphAttemptEvent event =
                    GraphAttemptEvent.next(
                        expected,
                        type,
                        eventTime,
                        phase,
                        selection == null
                            ? null
                            : selection.role(),
                        selection == null
                            ? null
                            : selection.runId(),
                        selection == null
                            ? null
                            : selection.taskId(),
                        approval,
                        providerIntent);
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

  private Instant databaseNow() {
    return jdbc.sql("SELECT clock_timestamp()")
        .query(Instant.class)
        .single();
  }

  private static void requirePack010Sequence(
      GraphAttemptManifest manifest,
      GraphAttemptManifest predecessor) {
    int repetition = manifest.experiment().repetition();
    if (!GraphAttemptManifest.TERMINAL_PROTOCOL_VERSION.equals(
            manifest.graphProtocolVersion())
        || repetition < 1
        || repetition > 3
        || !manifest.executionSlotId().equals(
            "pack010-r" + repetition)
        || (repetition == 1) != (predecessor == null)) {
      throw new IllegalArgumentException(
          "owner authority requires the exact Pack010 revision chain");
    }
  }

  private static GraphAttemptSnapshot requireValidPredecessor(
      GraphAttemptVerification verification) {
    if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
      throw new GraphAttemptConflictException(
          "Pack010 predecessor is missing or invalid");
    }
    GraphAttemptSnapshot prior = valid.snapshot();
    if (prior.cursor().phase() != GraphAttemptPhase.TERMINAL
        || prior.cursor().lastSequence() != 17
        || !prior.terminalSealPresent()
        || prior.terminalSeal() == null
        || prior.outcome() != GraphAttemptOutcome.SUCCEEDED
        || prior.billingStatus() != GraphBillingStatus.ATTRIBUTED
        || prior.providerAttributions().size()
            != prior.manifest().maximumProviderRequests()
        || prior.terminalBindings().size() != 2
        || prior.parentRun() == null
        || prior.parentRun().bundle() == null
        || prior.childRun() == null
        || prior.childRun().bundle() == null
        || prior.candidate() == null
        || prior.workerResult() == null
        || prior.artifact() == null) {
      throw new GraphAttemptConflictException(
          "Pack010 predecessor is not complete attributed truth");
    }
    return prior;
  }

  private static void requireExactPredecessor(
      GraphAttemptManifest current,
      GraphAttemptManifest predecessor,
      GraphAttemptSnapshot prior) {
    int repetition = current.experiment().repetition();
    if (!prior.manifest().equals(predecessor)
        || predecessor.experiment().repetition()
            != repetition - 1
        || !predecessor.executionSlotId().equals(
            "pack010-r" + (repetition - 1))
        || !current.principalId().equals(
            predecessor.principalId())
        || !current.schemaVersion().equals(
            predecessor.schemaVersion())
        || !current.graphProtocolVersion().equals(
            predecessor.graphProtocolVersion())
        || !current.packRawSha256().equals(
            predecessor.packRawSha256())
        || !current.environmentRawSha256().equals(
            predecessor.environmentRawSha256())
        || !current.captureId().equals(predecessor.captureId())
        || !current.captureRequestHash().equals(
            predecessor.captureRequestHash())
        || !current.pricingProfileFingerprint().equals(
            predecessor.pricingProfileFingerprint())
        || !current.promptSurfaceFingerprint().equals(
            predecessor.promptSurfaceFingerprint())
        || !current.conductorSurfaceFingerprint().equals(
            predecessor.conductorSurfaceFingerprint())
        || current.reservationUsd().compareTo(
                predecessor.reservationUsd())
            != 0
        || !current.parentActor().equals(
            predecessor.parentActor())
        || !current.childActor().equals(
            predecessor.childActor())
        || !current.experiment().arm().equals(
            predecessor.experiment().arm())
        || !current.integrityProfile().equals(
            predecessor.integrityProfile())
        || current.maximumProviderRequests()
            != predecessor.maximumProviderRequests()
        || !sameSelectionSurface(
            current.parentSelection(),
            predecessor.parentSelection())
        || !sameSelectionSurface(
            current.childSelection(),
            predecessor.childSelection())
        || current.parentSelection().runId().equals(
            predecessor.parentSelection().runId())
        || current.childSelection().runId().equals(
            predecessor.childSelection().runId())
        || current.parentSelection().taskId().equals(
            predecessor.parentSelection().taskId())
        || current.childSelection().taskId().equals(
            predecessor.childSelection().taskId())
        || current.attemptId().equals(predecessor.attemptId())
        || current.caseId().equals(predecessor.caseId())
        || current.artifactId().equals(predecessor.artifactId())) {
      throw new GraphAttemptConflictException(
          "Pack010 predecessor does not match the exact chain");
    }
  }

  private static boolean sameSelectionSurface(
      GraphRunSelection current, GraphRunSelection predecessor) {
    return current.role() == predecessor.role()
        && current.executionProfileId().equals(
            predecessor.executionProfileId())
        && current.workerRegistryVersion().equals(
            predecessor.workerRegistryVersion())
        && current.workerProfileId().equals(
            predecessor.workerProfileId());
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

  private void requireProviderAttribution(
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot snapshot,
      GraphProviderAttribution attribution) {
    GraphAttemptEvent pending = snapshot.events().getLast();
    int expectedOrdinal =
        snapshot.providerAttributions().size() + 1;
    if (!GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION.equals(
            manifest.graphProtocolVersion())
        || snapshot.cursor().phase()
            != GraphAttemptPhase.PROVIDER_PENDING
        || pending.type()
            != GraphAttemptEventType.PROVIDER_INTENT
        || pending.requestOrdinal() == null
        || pending.requestOrdinal() != expectedOrdinal
        || attribution.requestOrdinal() != expectedOrdinal
        || !pending.requestHash().equals(
            attribution.requestHash())
        || !pending.modelRequested().equals(
            attribution.modelRequested())
        || !manifest.childActor().equals(
            attribution.providerActor())
        || !manifest.pricingProfileFingerprint().equals(
            attribution.pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider attribution does not match the durable intent");
    }
  }

  private void requireChildTerminal(
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot snapshot,
      AgentRunContext parent,
      AgentRun terminalChild,
      Instant occurredAt) {
    AgentRun runningParent = snapshot.parentRun();
    AgentRun runningChild = snapshot.childRun();
    if (!GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION.equals(
            manifest.graphProtocolVersion())
        || snapshot.cursor().lastSequence() != 14
        || snapshot.cursor().phase()
            != GraphAttemptPhase.PROVIDER_ATTRIBUTED
        || runningParent == null
        || runningChild == null
        || !parent.runId().equals(runningParent.runId())
        || !parent.principalId().equals(
            runningParent.principalId())
        || !parent.task().equals(runningParent.task())
        || !terminalChild.lifecycle().terminal()
        || !runningChild.runId().equals(terminalChild.runId())
        || !runningChild.principalId().equals(
            terminalChild.principalId())
        || !runningChild.task().equals(terminalChild.task())
        || !runningChild.startedAt().equals(
            terminalChild.startedAt())
        || !occurredAt.equals(terminalChild.completedAt())) {
      throw new IllegalArgumentException(
          "terminal child does not match canonical graph truth");
    }
  }

  private GraphAttemptSnapshot expectedChildTerminalSnapshot(
      GraphAttemptSnapshot before,
      GraphAttemptEvent event,
      AgentRun terminalChild,
      GraphTerminalBinding binding,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {
    List<GraphAttemptEvent> events =
        new java.util.ArrayList<>(before.events());
    events.add(event);
    return new GraphAttemptSnapshot(
        before.manifest(),
        event.cursor(before.manifest()),
        events,
        before.parentRun(),
        terminalChild,
        false,
        GraphAttemptOutcome.INCOMPLETE,
        GraphBillingStatus.ATTRIBUTED,
        before.providerAttributions(),
        List.of(binding),
        null,
        candidate,
        workerResult,
        null);
  }

  private void requireParentTerminal(
      GraphAttemptManifest manifest,
      GraphAttemptSnapshot snapshot,
      AgentRun terminalParent,
      ArtifactLineage artifact,
      Instant occurredAt) {
    AgentRun runningParent = snapshot.parentRun();
    AgentRun terminalChild = snapshot.childRun();
    if (!GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION.equals(
            manifest.graphProtocolVersion())
        || snapshot.cursor().lastSequence() != 15
        || snapshot.cursor().phase()
            != GraphAttemptPhase.CHILD_TERMINAL
        || runningParent == null
        || terminalChild == null
        || !terminalChild.lifecycle().terminal()
        || !terminalParent.lifecycle().terminal()
        || !runningParent.runId().equals(terminalParent.runId())
        || !runningParent
            .principalId()
            .equals(terminalParent.principalId())
        || !runningParent.task().equals(terminalParent.task())
        || !runningParent
            .startedAt()
            .equals(terminalParent.startedAt())
        || !occurredAt.equals(terminalParent.completedAt())
        || (terminalParent.result().status()
                    == RunStatus.SUCCEEDED
                && artifact == null)) {
      throw new IllegalArgumentException(
          "terminal parent does not match canonical graph truth");
    }
  }

  private GraphAttemptSnapshot expectedSealedSnapshot(
      GraphAttemptSnapshot before,
      GraphAttemptEvent parentEvent,
      GraphAttemptEvent sealEvent,
      AgentRun terminalParent,
      GraphTerminalBinding parentBinding,
      GraphTerminalSeal seal,
      GraphAttemptOutcome outcome,
      ArtifactLineage artifact) {
    List<GraphAttemptEvent> events =
        new java.util.ArrayList<>(before.events());
    events.add(parentEvent);
    events.add(sealEvent);
    List<GraphTerminalBinding> bindings =
        new java.util.ArrayList<>(before.terminalBindings());
    bindings.add(parentBinding);
    return new GraphAttemptSnapshot(
        before.manifest(),
        sealEvent.cursor(before.manifest()),
        events,
        terminalParent,
        before.childRun(),
        true,
        outcome,
        GraphBillingStatus.ATTRIBUTED,
        before.providerAttributions(),
        bindings,
        seal,
        before.candidate(),
        before.workerResult(),
        artifact);
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
      List<GraphProviderAttribution> attributions =
          loadProviderAttributions(stored);
      List<GraphTerminalBinding> terminalBindings =
          loadTerminalBindings(stored);
      Map<GraphRunRole, StoredRunEvidence> runs =
          loadRuns(stored);
      StoredCandidate storedCandidate =
          loadCandidate(stored).orElse(null);
      HarnessCandidateEnvelope candidate =
          storedCandidate == null
              ? null
              : storedCandidate.candidate();
      WorkerResultEnvelope workerResult =
          loadWorkerResult(stored).orElse(null);
      GraphTerminalSeal terminalSeal =
          loadSeal(stored).orElse(null);
      ArtifactLineage artifact =
          loadGraphArtifact(stored, terminalBindings);
      if (!runsMatchBindings(runs, stored)
          || !providerIntentMatches(events, runs)) {
        return invalid(INVALID_STORED_GRAPH);
      }
      if (storedCandidate != null) {
        GraphAttemptEvent childTerminalEvent =
            events.stream()
                .filter(
                    event ->
                        event.type()
                            == GraphAttemptEventType.CHILD_TERMINAL)
                .findFirst()
                .orElseThrow();
        if (!storedCandidate
            .createdAt()
            .equals(childTerminalEvent.occurredAt())) {
          return invalid(INVALID_STORED_GRAPH);
        }
      }
      if ((head.lastSequence() < 15
              && prematureTruthCount(stored) != 0)) {
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
              terminalSeal != null,
              terminalSeal == null
                  ? GraphAttemptOutcome.INCOMPLETE
                  : terminalSeal.graphOutcome(),
              head.billingStatus(),
              attributions,
              terminalBindings,
              terminalSeal,
              candidate,
              workerResult,
              artifact);
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
                   billing_status, provider_intent_count,
                   provider_attribution_count
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
                        "provider_intent_count"),
                    resultSet.getInt(
                        "provider_attribution_count")))
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
              model_requested, evidence_hash,
              previous_head_hash,
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

  private List<GraphProviderAttribution>
      loadProviderAttributions(
          GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT
              request_ordinal, request_hash, response_hash,
              provider_actor, model_requested, model_resolved,
              pricing_profile_id, pricing_provider,
              pricing_profile_fingerprint,
              uncached_input_nano_usd_per_token,
              cached_input_nano_usd_per_token,
              output_nano_usd_per_token,
              input_tokens, cached_input_tokens, output_tokens,
              reasoning_output_tokens, total_tokens,
              observed_cost_usd, attribution_hash
            FROM agent_graph_attempt_provider_attributions
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            ORDER BY request_ordinal
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .query(
            (resultSet, rowNumber) ->
                providerAttribution(resultSet))
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
                  run.result_envelope::text AS result_json,
                  run.bundle::text AS bundle_json,
                  run.bundle_hash, run.trace_root_hash,
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
                  run.started_at, run.completed_at
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

  private List<GraphTerminalBinding> loadTerminalBindings(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT role, event_sequence, run_id, task_id, status,
                   bundle_hash, trace_root_hash,
                   effect_ref, effect_hash,
                   completed_at, terminal_hash
            FROM agent_graph_attempt_terminal_bindings
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
              AND manifest_hash = :manifestHash
            ORDER BY CASE role
              WHEN 'CHILD' THEN 0
              WHEN 'PARENT' THEN 1
              ELSE 2
            END
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .query(
            (resultSet, rowNumber) -> {
              GraphRunRole role =
                  GraphRunRole.valueOf(
                      resultSet.getString("role"));
              int expectedSequence =
                  role == GraphRunRole.CHILD ? 15 : 16;
              if (resultSet.getInt("event_sequence")
                  != expectedSequence) {
                throw new IllegalArgumentException(
                    "terminal binding sequence is inconsistent");
              }
              return new GraphTerminalBinding(
                  role,
                  resultSet.getString("run_id"),
                  resultSet.getString("task_id"),
                  RunStatus.valueOf(
                      resultSet.getString("status")),
                  resultSet.getString("bundle_hash"),
                  resultSet.getString("trace_root_hash"),
                  resultSet.getString("effect_ref"),
                  resultSet.getString("effect_hash"),
                  resultSet.getTimestamp("completed_at")
                      .toInstant(),
                  resultSet.getString("terminal_hash"));
            })
        .list();
  }

  private ArtifactLineage loadGraphArtifact(
      GraphAttemptManifest manifest,
      List<GraphTerminalBinding> terminalBindings) {
    ArtifactLineage lineage =
        artifacts
            .findOwned(
                manifest.principalId(), manifest.artifactId())
            .orElse(null);
    if (lineage == null) {
      return null;
    }
    GraphTerminalBinding parentBinding =
        terminalBindings.stream()
            .filter(
                binding ->
                    binding.role() == GraphRunRole.PARENT)
            .findFirst()
            .orElse(null);
    if (parentBinding == null
        || parentBinding.effectRef() == null) {
      return lineage;
    }
    ArtifactIdentity bound =
        parseArtifactIdentity(parentBinding.effectRef());
    if (!manifest.artifactId().equals(bound.artifactId())) {
      throw new IllegalArgumentException(
          "terminal graph binds another Artifact");
    }
    List<ArtifactLineageEntry> prefix =
        lineage.versions().stream()
            .filter(
                version ->
                    version.version() <= bound.version())
            .toList();
    if (prefix.isEmpty()
        || prefix.getLast().version() != bound.version()
        || prefix.size() != bound.version()) {
      throw new IllegalArgumentException(
          "terminal graph Artifact version is missing");
    }
    return new ArtifactLineage(
        lineage.artifactId(),
        lineage.principalId(),
        lineage.sourceCaptureId(),
        prefix);
  }

  private Optional<StoredCandidate> loadCandidate(
      GraphAttemptManifest manifest) {
    Optional<CandidateRow> row =
        jdbc.sql(
                """
                SELECT manifest_hash, schema_version, candidate_ref,
                       execution_slot_id, repetition, child_role,
                       child_run_id, child_task_id,
                       source_request_ordinal, source_response_hash,
                       trace_root_hash, output_schema,
                       content, content_hash,
                       required_evidence_ref,
                       required_evidence_available,
                       integrity_profile, integrity_hash,
                       candidate_envelope::text AS candidate_json,
                       created_at
                FROM agent_graph_attempt_candidates
                WHERE principal_id = :principalId
                  AND attempt_id = :attemptId
                """)
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .query(
                (resultSet, rowNumber) ->
                    new CandidateRow(
                        resultSet.getString("manifest_hash"),
                        resultSet.getString("schema_version"),
                        resultSet.getString("candidate_ref"),
                        resultSet.getString("execution_slot_id"),
                        resultSet.getInt("repetition"),
                        resultSet.getString("child_role"),
                        resultSet.getString("child_run_id"),
                        resultSet.getString("child_task_id"),
                        resultSet.getInt("source_request_ordinal"),
                        resultSet.getString("source_response_hash"),
                        resultSet.getString("trace_root_hash"),
                        resultSet.getString("output_schema"),
                        resultSet.getString("content"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("required_evidence_ref"),
                        resultSet.getBoolean(
                            "required_evidence_available"),
                        resultSet.getString("integrity_profile"),
                        resultSet.getString("integrity_hash"),
                        resultSet.getString("candidate_json"),
                        resultSet.getTimestamp("created_at").toInstant()))
            .optional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    CandidateRow stored = row.orElseThrow();
    HarnessCandidateEnvelope candidate =
        json.readValue(
            stored.candidateJson(),
            HarnessCandidateEnvelope.class);
    if (!stored.matches(manifest, candidate)) {
      throw new IllegalArgumentException(
          "stored Candidate projection is inconsistent");
    }
    return Optional.of(
        new StoredCandidate(candidate, stored.createdAt()));
  }

  private Optional<WorkerResultEnvelope> loadWorkerResult(
      GraphAttemptManifest manifest) {
    Optional<WorkerResultRow> row =
        jdbc.sql(
                """
                SELECT parent_run_id, child_run_id,
                       child_task_id, child_status,
                       worker_result_ref,
                       worker_result_envelope::text
                           AS worker_result_json,
                       content_hash, integrity_hash
                FROM agent_worker_results
                WHERE principal_id = :principalId
                  AND child_run_id = :childRunId
                """)
            .param("principalId", manifest.principalId())
            .param(
                "childRunId",
                manifest.childSelection().runId())
            .query(
                (resultSet, rowNumber) ->
                    new WorkerResultRow(
                        resultSet.getString("parent_run_id"),
                        resultSet.getString("child_run_id"),
                        resultSet.getString("child_task_id"),
                        resultSet.getString("child_status"),
                        resultSet.getString("worker_result_ref"),
                        resultSet.getString("worker_result_json"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("integrity_hash")))
            .optional();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    WorkerResultRow stored = row.orElseThrow();
    WorkerResultEnvelope workerResult =
        json.readValue(
            stored.workerResultJson(),
            WorkerResultEnvelope.class);
    if (!stored.matches(manifest, workerResult)) {
      throw new IllegalArgumentException(
          "stored Worker Result projection is inconsistent");
    }
    return Optional.of(workerResult);
  }

  private Optional<GraphTerminalSeal> loadSeal(
      GraphAttemptManifest manifest) {
    return jdbc.sql(
            """
            SELECT manifest_hash, final_sequence,
                   final_head_hash, graph_outcome,
                   billing_status, seal_hash, sealed_at,
                   pre_seal_sequence, pre_seal_head_hash,
                   provider_attribution_1_hash,
                   provider_attribution_2_hash,
                   candidate_ref, candidate_integrity_hash,
                   child_terminal_hash, parent_terminal_hash
            FROM agent_graph_attempt_seals
            WHERE principal_id = :principalId
              AND attempt_id = :attemptId
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .query(
            (resultSet, rowNumber) -> {
              if (!manifest
                      .manifestHash()
                      .equals(
                          resultSet.getString("manifest_hash"))
                  || resultSet.getInt("pre_seal_sequence")
                      != resultSet.getInt("final_sequence") - 1) {
                throw new IllegalArgumentException(
                    "stored terminal seal projection is inconsistent");
              }
              return new GraphTerminalSeal(
                  manifest.attemptId(),
                  manifest.manifestHash(),
                  resultSet.getInt("final_sequence"),
                  resultSet.getString("pre_seal_head_hash"),
                  resultSet.getString("final_head_hash"),
                  GraphAttemptOutcome.valueOf(
                      resultSet.getString("graph_outcome")),
                  GraphBillingStatus.valueOf(
                      resultSet.getString("billing_status")),
                  List.of(
                      resultSet.getString(
                          "provider_attribution_1_hash"),
                      resultSet.getString(
                          "provider_attribution_2_hash")),
                  resultSet.getString("candidate_ref"),
                  resultSet.getString(
                      "candidate_integrity_hash"),
                  resultSet.getString("child_terminal_hash"),
                  resultSet.getString("parent_terminal_hash"),
                  resultSet.getString("seal_hash"),
                  resultSet.getTimestamp("sealed_at").toInstant());
            })
        .optional();
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

  private void authorizeTerminalMutation(
      GraphAttemptManifest manifest, GraphRunRole role) {
    GraphRunSelection selection =
        role == GraphRunRole.CHILD
            ? manifest.childSelection()
            : manifest.parentSelection();
    jdbc.sql(
            """
            SELECT agent_graph_authorize_terminal_v8(
              :principalId, :attemptId, :role, :runId
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("role", role.name())
        .param("runId", selection.runId())
        .query((resultSet, rowNumber) -> Boolean.TRUE)
        .single();
  }

  private void insertCandidate(
      GraphAttemptManifest manifest,
      HarnessCandidateEnvelope candidate,
      Instant occurredAt) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_candidates (
              principal_id, attempt_id, manifest_hash,
              schema_version, candidate_ref,
              execution_slot_id, repetition, child_role,
              child_run_id, child_task_id,
              source_request_ordinal, source_response_hash,
              trace_root_hash, output_schema,
              content, content_hash,
              required_evidence_ref,
              required_evidence_available,
              integrity_profile, integrity_hash,
              candidate_envelope, created_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash,
              :schemaVersion, :candidateRef,
              :executionSlotId, :repetition, 'CHILD',
              :childRunId, :childTaskId,
              :sourceRequestOrdinal, :sourceResponseHash,
              :traceRootHash, :outputSchema,
              :content, :contentHash,
              :requiredEvidenceRef,
              :requiredEvidenceAvailable,
              :integrityProfile, :integrityHash,
              CAST(:candidateJson AS jsonb), :createdAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("schemaVersion", candidate.schemaVersion())
        .param("candidateRef", candidate.candidateRef())
        .param("executionSlotId", candidate.executionSlotId())
        .param("repetition", candidate.repetition())
        .param("childRunId", candidate.childRunId())
        .param("childTaskId", candidate.childTaskId())
        .param(
            "sourceRequestOrdinal",
            candidate.sourceRequestOrdinal())
        .param(
            "sourceResponseHash", candidate.sourceResponseHash())
        .param("traceRootHash", candidate.traceRootHash())
        .param("outputSchema", candidate.outputSchema())
        .param("content", candidate.content())
        .param("contentHash", candidate.contentHash())
        .param(
            "requiredEvidenceRef", candidate.requiredEvidenceRef())
        .param(
            "requiredEvidenceAvailable",
            candidate.requiredEvidenceAvailable())
        .param("integrityProfile", candidate.integrityProfile())
        .param("integrityHash", candidate.integrityHash())
        .param(
            "candidateJson", json.writeValueAsString(candidate))
        .param("createdAt", Timestamp.from(occurredAt))
        .update();
  }

  private void insertWorkerResult(
      AgentRunContext parent,
      AgentRun terminal,
      WorkerResultEnvelope workerResult) {
    jdbc.sql(
            """
            INSERT INTO agent_worker_results (
              principal_id, parent_run_id, child_run_id,
              child_task_id, child_status,
              worker_result_ref, worker_result_envelope,
              content_hash, integrity_hash
            ) VALUES (
              :principalId, :parentRunId, :childRunId,
              :childTaskId, :childStatus,
              :workerResultRef, CAST(:workerResultJson AS jsonb),
              :contentHash, :integrityHash
            )
            """)
        .param("principalId", terminal.principalId())
        .param("parentRunId", parent.runId())
        .param("childRunId", terminal.runId())
        .param("childTaskId", terminal.task().id())
        .param("childStatus", terminal.lifecycle().name())
        .param("workerResultRef", workerResult.workerResultRef())
        .param(
            "workerResultJson",
            json.writeValueAsString(workerResult))
        .param("contentHash", workerResult.contentHash())
        .param("integrityHash", workerResult.integrityHash())
        .update();
  }

  private void insertTerminalTrace(
      AgentRun terminal, ProbePoint statementProbe) {
    for (AgentTraceEntry event : terminal.trace().events()) {
      jdbc.sql(
              """
              INSERT INTO agent_trace_events (
                principal_id, run_id, sequence, event_type,
                tool_name, status, resource_ref,
                previous_root_hash, event_hash, current_root_hash
              ) VALUES (
                :principalId, :runId, :sequence, :eventType,
                :toolName, :status, :resourceRef,
                :previousRootHash, :eventHash, :currentRootHash
              )
              """)
          .param("principalId", terminal.principalId())
          .param("runId", terminal.runId())
          .param("sequence", event.sequence())
          .param("eventType", event.type().name())
          .param("toolName", event.toolName(), Types.VARCHAR)
          .param("status", event.status())
          .param("resourceRef", event.reference(), Types.VARCHAR)
          .param("previousRootHash", event.previousRootHash())
          .param("eventHash", event.eventHash())
          .param(
              "currentRootHash",
              IntegrityHashes.nextTraceRoot(
                  event.previousRootHash(), event.eventHash()))
          .update();
      probe.hit(statementProbe);
    }
  }

  private void insertTerminalResourceBindings(
      AgentRun terminal, ProbePoint statementProbe) {
    for (ResourceBinding binding :
        terminal.bundle().resourceBindings()) {
      String captureId =
          binding.role() == ResourceRole.EVIDENCE
              ? parseCaptureIdentity(binding.ref())
              : null;
      ArtifactIdentity artifactIdentity =
          binding.role() == ResourceRole.ARTIFACT
              ? parseArtifactIdentity(binding.ref())
              : new ArtifactIdentity(null, null);
      String handoffChildRunId =
          binding.role() == ResourceRole.HANDOFF
              ? parseAgentRunIdentity(binding.ref())
              : null;
      String workerResultChildRunId =
          binding.role() == ResourceRole.WORKER_RESULT
              ? parseWorkerResultIdentity(binding.ref())
              : null;
      jdbc.sql(
              """
              INSERT INTO agent_run_resource_bindings (
                principal_id, run_id, role, ordinal,
                resource_ref, content_hash,
                capture_id, artifact_id, artifact_version,
                handoff_child_run_id,
                worker_result_child_run_id,
                binding_owner_status
              ) VALUES (
                :principalId, :runId, :role, :ordinal,
                :resourceRef, :contentHash,
                :captureId, :artifactId, :artifactVersion,
                :handoffChildRunId,
                :workerResultChildRunId,
                :bindingOwnerStatus
              )
              """)
          .param("principalId", terminal.principalId())
          .param("runId", terminal.runId())
          .param("role", binding.role().name())
          .param("ordinal", binding.ordinal())
          .param("resourceRef", binding.ref())
          .param("contentHash", binding.contentHash())
          .param("captureId", captureId, Types.VARCHAR)
          .param(
              "artifactId",
              artifactIdentity.artifactId(),
              Types.VARCHAR)
          .param(
              "artifactVersion",
              artifactIdentity.version(),
              Types.INTEGER)
          .param(
              "handoffChildRunId",
              handoffChildRunId,
              Types.VARCHAR)
          .param(
              "workerResultChildRunId",
              workerResultChildRunId,
              Types.VARCHAR)
          .param(
              "bindingOwnerStatus",
              terminal.lifecycle().name())
          .update();
      probe.hit(statementProbe);
    }
  }

  private void updateTerminalRun(
      GraphAttemptManifest manifest,
      GraphRunRole role,
      AgentRun terminal) {
    GraphRunSelection selection =
        role == GraphRunRole.CHILD
            ? manifest.childSelection()
            : manifest.parentSelection();
    int updated =
        jdbc.sql(
                """
                UPDATE agent_runs
                SET lifecycle_status = :lifecycle,
                    result_envelope = CAST(:resultJson AS jsonb),
                    bundle = CAST(:bundleJson AS jsonb),
                    bundle_hash = :bundleHash,
                    trace_root_hash = :traceRootHash,
                    last_event_sequence = :eventCount,
                    resolved_model = :resolvedModel,
                    agent_version = :agentVersion,
                    verifier_version = :verifierVersion,
                    harness_version = :harnessVersion,
                    cost_usd = :costUsd,
                    token_count = :tokenCount,
                    latency_ms = :latencyMs,
                    failure_attribution = :failureAttribution,
                    completed_at = :completedAt
                WHERE principal_id = :principalId
                  AND run_id = :runId
                  AND task_id = :taskId
                  AND lifecycle_status = 'RUNNING'
                  AND graph_attempt_id = :attemptId
                  AND graph_manifest_hash = :manifestHash
                  AND graph_role = :graphRole
                  AND graph_task_hash = :taskHash
                  AND graph_selector_hash = :selectorHash
                  AND graph_execution_profile_id = :profileId
                  AND graph_execution_profile_fingerprint = :profileHash
                  AND graph_worker_registry_version = :registryVersion
                  AND graph_worker_profile_id = :workerProfileId
                  AND graph_worker_profile_fingerprint = :workerProfileHash
                """)
            .param("lifecycle", terminal.lifecycle().name())
            .param(
                "resultJson",
                json.writeValueAsString(terminal.result()))
            .param(
                "bundleJson",
                json.writeValueAsString(terminal.bundle()))
            .param(
                "bundleHash",
                terminal.bundle().integrityHash())
            .param(
                "traceRootHash", terminal.trace().rootHash())
            .param(
                "eventCount", terminal.trace().eventCount())
            .param(
                "resolvedModel",
                terminal.result().resolvedModel(),
                Types.VARCHAR)
            .param(
                "agentVersion",
                terminal.result().agentVersion())
            .param(
                "verifierVersion",
                terminal.result().verifierVersion())
            .param(
                "harnessVersion",
                terminal.bundle().harnessVersion())
            .param("costUsd", terminal.result().costUsd())
            .param(
                "tokenCount", terminal.result().tokenCount())
            .param("latencyMs", terminal.result().latencyMs())
            .param(
                "failureAttribution",
                terminal.bundle().failureAttribution(),
                Types.VARCHAR)
            .param(
                "completedAt",
                Timestamp.from(terminal.completedAt()))
            .param("principalId", terminal.principalId())
            .param("runId", terminal.runId())
            .param("taskId", terminal.task().id())
            .param("attemptId", manifest.attemptId())
            .param("manifestHash", manifest.manifestHash())
            .param("graphRole", role.name())
            .param("taskHash", selection.taskHash())
            .param("selectorHash", selection.selectorHash())
            .param("profileId", selection.executionProfileId())
            .param(
                "profileHash",
                selection.executionProfileFingerprint())
            .param(
                "registryVersion",
                selection.workerRegistryVersion())
            .param("workerProfileId", selection.workerProfileId())
            .param(
                "workerProfileHash",
                selection.workerProfileFingerprint())
            .update();
    if (updated != 1) {
      throw conflict();
    }
  }

  private void insertTerminalBinding(
      GraphAttemptManifest manifest,
      GraphAttemptEvent event,
      GraphTerminalBinding binding) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_terminal_bindings (
              principal_id, attempt_id, manifest_hash,
              role, event_sequence, run_id, task_id, status,
              bundle_hash, trace_root_hash,
              effect_ref, effect_hash,
              completed_at, terminal_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash,
              :role, :eventSequence, :runId, :taskId, :status,
              :bundleHash, :traceRootHash,
              :effectRef, :effectHash,
              :completedAt, :terminalHash
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("role", binding.role().name())
        .param("eventSequence", event.sequence())
        .param("runId", binding.runId())
        .param("taskId", binding.taskId())
        .param("status", binding.status().name())
        .param("bundleHash", binding.bundleHash())
        .param("traceRootHash", binding.traceRootHash())
        .param("effectRef", binding.effectRef(), Types.VARCHAR)
        .param("effectHash", binding.effectHash(), Types.CHAR)
        .param(
            "completedAt", Timestamp.from(binding.completedAt()))
        .param("terminalHash", binding.terminalHash())
        .update();
  }

  private void insertSeal(
      GraphAttemptManifest manifest, GraphTerminalSeal seal) {
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_seals (
              principal_id, attempt_id, manifest_hash,
              final_sequence, final_head_hash,
              graph_outcome, billing_status,
              seal_hash, sealed_at,
              pre_seal_sequence, pre_seal_head_hash,
              provider_attribution_1_hash,
              provider_attribution_2_hash,
              candidate_ref, candidate_integrity_hash,
              child_terminal_hash, parent_terminal_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash,
              :finalSequence, :finalHeadHash,
              :graphOutcome, :billingStatus,
              :sealHash, :sealedAt,
              :preSealSequence, :preSealHeadHash,
              :providerAttribution1Hash,
              :providerAttribution2Hash,
              :candidateRef, :candidateIntegrityHash,
              :childTerminalHash, :parentTerminalHash
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("finalSequence", seal.finalSequence())
        .param("finalHeadHash", seal.finalHeadHash())
        .param("graphOutcome", seal.graphOutcome().name())
        .param("billingStatus", seal.billingStatus().name())
        .param("sealHash", seal.sealHash())
        .param("sealedAt", Timestamp.from(seal.sealedAt()))
        .param("preSealSequence", seal.finalSequence() - 1)
        .param("preSealHeadHash", seal.preSealHeadHash())
        .param(
            "providerAttribution1Hash",
            seal.providerAttributionHashes().get(0))
        .param(
            "providerAttribution2Hash",
            seal.providerAttributionHashes().get(1))
        .param("candidateRef", seal.candidateRef(), Types.VARCHAR)
        .param(
            "candidateIntegrityHash",
            seal.candidateIntegrityHash(),
            Types.CHAR)
        .param("childTerminalHash", seal.childTerminalHash())
        .param("parentTerminalHash", seal.parentTerminalHash())
        .update();
  }

  private void insertProviderAttribution(
      GraphAttemptManifest manifest,
      GraphAttemptEvent event,
      GraphProviderAttribution attribution,
      Instant occurredAt) {
    GraphPricingSnapshot pricing = attribution.pricing();
    jdbc.sql(
            """
            INSERT INTO agent_graph_attempt_provider_attributions (
              principal_id, attempt_id, manifest_hash,
              request_ordinal, event_sequence,
              request_hash, response_hash,
              provider_actor, model_requested, model_resolved,
              pricing_profile_id, pricing_provider,
              pricing_profile_fingerprint,
              uncached_input_nano_usd_per_token,
              cached_input_nano_usd_per_token,
              output_nano_usd_per_token,
              input_tokens, cached_input_tokens, output_tokens,
              reasoning_output_tokens, total_tokens,
              observed_cost_usd, attribution_hash, attributed_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash,
              :requestOrdinal, :eventSequence,
              :requestHash, :responseHash,
              :providerActor, :modelRequested, :modelResolved,
              :pricingProfileId, :pricingProvider,
              :pricingProfileFingerprint,
              :uncachedInputRate, :cachedInputRate,
              :outputRate,
              :inputTokens, :cachedInputTokens, :outputTokens,
              :reasoningOutputTokens, :totalTokens,
              :observedCostUsd, :attributionHash, :attributedAt
            )
            """)
        .param("principalId", manifest.principalId())
        .param("attemptId", manifest.attemptId())
        .param("manifestHash", manifest.manifestHash())
        .param("requestOrdinal", attribution.requestOrdinal())
        .param("eventSequence", event.sequence())
        .param("requestHash", attribution.requestHash())
        .param("responseHash", attribution.responseHash())
        .param("providerActor", attribution.providerActor())
        .param("modelRequested", attribution.modelRequested())
        .param("modelResolved", attribution.modelResolved())
        .param("pricingProfileId", pricing.id())
        .param("pricingProvider", pricing.provider())
        .param(
            "pricingProfileFingerprint", pricing.fingerprint())
        .param(
            "uncachedInputRate",
            pricing.uncachedInputNanoUsdPerToken())
        .param(
            "cachedInputRate",
            pricing.cachedInputNanoUsdPerToken())
        .param(
            "outputRate", pricing.outputNanoUsdPerToken())
        .param("inputTokens", attribution.inputTokens())
        .param(
            "cachedInputTokens",
            attribution.cachedInputTokens())
        .param("outputTokens", attribution.outputTokens())
        .param(
            "reasoningOutputTokens",
            attribution.reasoningOutputTokens())
        .param("totalTokens", attribution.totalTokens())
        .param("observedCostUsd", attribution.observedCostUsd())
        .param("attributionHash", attribution.attributionHash())
        .param("attributedAt", Timestamp.from(occurredAt))
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
              evidence_hash, previous_head_hash,
              event_hash, current_head_hash
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :sequence,
              :eventType, :occurredAt, :phaseFrom, :phaseTo,
              :role, :runId, :taskId, :actor, :challengeHash,
              :requestOrdinal, :requestHash, :modelRequested,
              :evidenceHash, :previousHeadHash,
              :eventHash, :currentHeadHash
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
            "evidenceHash", event.evidenceHash(), Types.CHAR)
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
              provider_attribution_count,
              created_at, updated_at
            ) VALUES (
              :principalId, :attemptId, :manifestHash, :phase,
              :stateVersion, :lastSequence, :headHash,
              'NOT_INVOKED', 0, 0, :createdAt, :updatedAt
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
    int sequence = event.sequence();
    int providerIntentCount =
        sequence >= 13 ? 2 : sequence >= 11 ? 1 : 0;
    int providerAttributionCount =
        sequence >= 14 ? 2 : sequence >= 12 ? 1 : 0;
    GraphBillingStatus billingStatus =
        sequence <= 10
            ? GraphBillingStatus.NOT_INVOKED
            : sequence == 11 || sequence == 13
                ? GraphBillingStatus.UNKNOWN
                : GraphBillingStatus.ATTRIBUTED;
    return jdbc.sql(
            """
            UPDATE agent_graph_attempt_heads
            SET phase = :phase,
                state_version = :stateVersion,
                last_sequence = :lastSequence,
                head_hash = :headHash,
                billing_status = :billingStatus,
                provider_intent_count = :providerIntentCount,
                provider_attribution_count = :providerAttributionCount,
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
        .param("billingStatus", billingStatus.name())
        .param("providerIntentCount", providerIntentCount)
        .param(
            "providerAttributionCount",
            providerAttributionCount)
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
        resultSet.getString("evidence_hash"),
        resultSet.getString("previous_head_hash"),
        resultSet.getString("event_hash"),
        resultSet.getString("current_head_hash"));
  }

  private static GraphProviderAttribution providerAttribution(
      ResultSet resultSet) throws SQLException {
    GraphPricingSnapshot pricing =
        new GraphPricingSnapshot(
            resultSet.getString("pricing_profile_id"),
            resultSet.getString("pricing_provider"),
            resultSet.getString("model_requested"),
            resultSet.getLong(
                "uncached_input_nano_usd_per_token"),
            resultSet.getLong(
                "cached_input_nano_usd_per_token"),
            resultSet.getLong(
                "output_nano_usd_per_token"),
            resultSet.getString(
                "pricing_profile_fingerprint"));
    return new GraphProviderAttribution(
        resultSet.getInt("request_ordinal"),
        resultSet.getString("request_hash"),
        resultSet.getString("response_hash"),
        resultSet.getString("provider_actor"),
        resultSet.getString("model_requested"),
        resultSet.getString("model_resolved"),
        pricing,
        resultSet.getLong("input_tokens"),
        resultSet.getLong("cached_input_tokens"),
        resultSet.getLong("output_tokens"),
        resultSet.getLong("reasoning_output_tokens"),
        resultSet.getLong("total_tokens"),
        resultSet.getBigDecimal("observed_cost_usd")
            .stripTrailingZeros(),
        resultSet.getString("attribution_hash"));
  }

  private AgentTraceEnvelope loadTerminalTrace(
      String principalId, String runId, String taskId) {
    List<StoredTraceEntry> stored =
        jdbc.sql(
                """
                SELECT sequence, event_type, tool_name, status,
                       resource_ref, previous_root_hash,
                       event_hash, current_root_hash
                FROM agent_trace_events
                WHERE principal_id = :principalId
                  AND run_id = :runId
                ORDER BY sequence
                """)
            .param("principalId", principalId)
            .param("runId", runId)
            .query(
                (resultSet, rowNumber) -> {
                  AgentTraceEntry event =
                      new AgentTraceEntry(
                          resultSet.getInt("sequence"),
                          io.emergeos.contracts.TraceEventType
                              .valueOf(
                                  resultSet.getString(
                                      "event_type")),
                          resultSet.getString("tool_name"),
                          resultSet.getString("status"),
                          resultSet.getString("resource_ref"),
                          resultSet.getString(
                              "previous_root_hash"),
                          resultSet.getString("event_hash"));
                  return new StoredTraceEntry(
                      event,
                      resultSet.getString(
                          "current_root_hash"));
                })
            .list();
    for (StoredTraceEntry entry : stored) {
      if (!IntegrityHashes.nextTraceRoot(
              entry.event().previousRootHash(),
              entry.event().eventHash())
          .equals(entry.currentRootHash())) {
        throw new IllegalArgumentException(
            "stored terminal Trace root is inconsistent");
      }
    }
    return AgentTraceEnvelope.create(
        "1.0",
        runId,
        taskId,
        stored.stream()
            .map(StoredTraceEntry::event)
            .toList());
  }

  private List<ResourceBinding> loadTerminalResourceBindings(
      String principalId,
      String runId,
      AgentRunLifecycle lifecycle) {
    return jdbc.sql(
            """
            SELECT role, ordinal, resource_ref, content_hash,
                   capture_id, artifact_id, artifact_version,
                   handoff_child_run_id,
                   worker_result_child_run_id,
                   binding_owner_status
            FROM agent_run_resource_bindings
            WHERE principal_id = :principalId
              AND run_id = :runId
            ORDER BY
              CASE role
                WHEN 'EVIDENCE' THEN 0
                WHEN 'ARTIFACT' THEN 1
                WHEN 'RECEIPT' THEN 2
                WHEN 'VERIFICATION' THEN 3
                WHEN 'CHECKPOINT' THEN 4
                WHEN 'HANDOFF' THEN 5
                WHEN 'WORKER_RESULT' THEN 6
                ELSE 7
              END,
              ordinal
            """)
        .param("principalId", principalId)
        .param("runId", runId)
        .query(
            (resultSet, rowNumber) -> {
              ResourceRole role =
                  ResourceRole.valueOf(
                      resultSet.getString("role"));
              String ref = resultSet.getString("resource_ref");
              String expectedCaptureId =
                  role == ResourceRole.EVIDENCE
                      ? parseCaptureIdentity(ref)
                      : null;
              ArtifactIdentity expectedArtifact =
                  role == ResourceRole.ARTIFACT
                      ? parseArtifactIdentity(ref)
                      : new ArtifactIdentity(null, null);
              String expectedHandoffRunId =
                  role == ResourceRole.HANDOFF
                      ? parseAgentRunIdentity(ref)
                      : null;
              String expectedWorkerResultRunId =
                  role == ResourceRole.WORKER_RESULT
                      ? parseWorkerResultIdentity(ref)
                      : null;
              Integer storedArtifactVersion =
                  (Integer) resultSet.getObject(
                      "artifact_version");
              if (!Objects.equals(
                      expectedCaptureId,
                      resultSet.getString("capture_id"))
                  || !Objects.equals(
                      expectedArtifact.artifactId(),
                      resultSet.getString("artifact_id"))
                  || !Objects.equals(
                      expectedArtifact.version(),
                      storedArtifactVersion)
                  || !Objects.equals(
                      expectedHandoffRunId,
                      resultSet.getString("handoff_child_run_id"))
                  || !Objects.equals(
                      expectedWorkerResultRunId,
                      resultSet.getString(
                          "worker_result_child_run_id"))
                  || !lifecycle.name().equals(
                      resultSet.getString(
                          "binding_owner_status"))) {
                throw new IllegalArgumentException(
                    "terminal ResourceBinding mirrors are inconsistent");
              }
              return new ResourceBinding(
                  role,
                  resultSet.getInt("ordinal"),
                  ref,
                  resultSet.getString("content_hash"));
            })
        .list();
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
    AgentRunLifecycle lifecycle =
        AgentRunLifecycle.valueOf(
            resultSet.getString("lifecycle_status"));
    AgentRun run;
    if (lifecycle == AgentRunLifecycle.RUNNING) {
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
          || resultSet.getString("failure_attribution") != null
          || resultSet.getString("result_json") != null
          || resultSet.getString("bundle_json") != null
          || resultSet.getTimestamp("completed_at") != null) {
        throw new IllegalArgumentException(
            "running graph Run cannot contain terminal truth");
      }
      run =
          AgentRun.running(
              resultSet.getString("run_id"),
              principalId,
              task,
              resultSet.getTimestamp("started_at").toInstant());
    } else {
      ResultEnvelope result =
          json.readValue(
              resultSet.getString("result_json"),
              ResultEnvelope.class);
      HarnessRunBundle bundle =
          json.readValue(
              resultSet.getString("bundle_json"),
              HarnessRunBundle.class);
      AgentTraceEnvelope trace =
          loadTerminalTrace(
              principalId,
              resultSet.getString("run_id"),
              resultSet.getString("task_id"));
      List<ResourceBinding> bindings =
          loadTerminalResourceBindings(
              principalId,
              resultSet.getString("run_id"),
              lifecycle);
      if (!resultSet.getString("bundle_hash")
              .equals(bundle.integrityHash())
          || !resultSet.getString("trace_root_hash")
              .equals(trace.rootHash())
          || resultSet.getInt("last_event_sequence")
              != trace.eventCount()
          || !task.equals(bundle.task())
          || !result.equals(bundle.result())
          || !bindings.equals(bundle.resourceBindings())
          || !Objects.equals(
              resultSet.getString("resolved_model"),
              result.resolvedModel())
          || !resultSet.getString("agent_version")
              .equals(result.agentVersion())
          || !resultSet.getString("verifier_version")
              .equals(result.verifierVersion())
          || !resultSet.getString("harness_version")
              .equals(bundle.harnessVersion())
          || resultSet.getBigDecimal("cost_usd")
                  .compareTo(result.costUsd())
              != 0
          || resultSet.getLong("token_count")
              != result.tokenCount()
          || resultSet.getLong("latency_ms")
              != result.latencyMs()
          || !Objects.equals(
              resultSet.getString("failure_attribution"),
              bundle.failureAttribution())) {
        throw new IllegalArgumentException(
            "terminal graph Run mirrors are inconsistent");
      }
      run =
          new AgentRun(
              resultSet.getString("run_id"),
              principalId,
              task,
              lifecycle,
              result,
              trace,
              bundle,
              resultSet.getTimestamp("started_at").toInstant(),
              resultSet.getTimestamp("completed_at").toInstant());
    }
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

  private static String parseCaptureIdentity(String ref) {
    if (ref == null
        || !ref.matches(
            "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException(
          "Evidence binding must name one owned Capture");
    }
    return ref.substring("capture://".length());
  }

  private static ArtifactIdentity parseArtifactIdentity(
      String ref) {
    String remainder =
        ref.substring("artifact-version://".length());
    int separator = remainder.lastIndexOf('/');
    return new ArtifactIdentity(
        remainder.substring(0, separator),
        Integer.valueOf(remainder.substring(separator + 1)));
  }

  private static String parseAgentRunIdentity(String ref) {
    if (ref == null
        || !ref.matches(
            "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(
          "Handoff binding must name one exact child AgentRun");
    }
    return ref.substring("agent-run://".length());
  }

  private static String parseWorkerResultIdentity(String ref) {
    if (ref == null
        || !ref.matches(
            "worker-result://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(
          "Worker Result binding must name one exact child AgentRun");
    }
    return ref.substring("worker-result://".length());
  }

  private record HeadRow(
      GraphAttemptPhase phase,
      long stateVersion,
      int lastSequence,
      String headHash,
      GraphBillingStatus billingStatus,
      int providerIntentCount,
      int providerAttributionCount) {

    private GraphAttemptCursor cursor(
        GraphAttemptManifest manifest) {
      int expectedIntentCount =
          lastSequence >= 13
              ? 2
              : lastSequence >= 11 ? 1 : 0;
      int expectedAttributionCount =
          lastSequence >= 14
              ? 2
              : lastSequence >= 12 ? 1 : 0;
      if (providerIntentCount != expectedIntentCount
          || providerAttributionCount
              != expectedAttributionCount) {
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

  private record StoredTraceEntry(
      AgentTraceEntry event, String currentRootHash) {}

  private record StoredCandidate(
      HarnessCandidateEnvelope candidate, Instant createdAt) {}

  private record CandidateRow(
      String manifestHash,
      String schemaVersion,
      String candidateRef,
      String executionSlotId,
      int repetition,
      String childRole,
      String childRunId,
      String childTaskId,
      int sourceRequestOrdinal,
      String sourceResponseHash,
      String traceRootHash,
      String outputSchema,
      String content,
      String contentHash,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable,
      String integrityProfile,
      String integrityHash,
      String candidateJson,
      Instant createdAt) {

    private boolean matches(
        GraphAttemptManifest manifest,
        HarnessCandidateEnvelope candidate) {
      return manifestHash.equals(manifest.manifestHash())
          && "CHILD".equals(childRole)
          && manifest.attemptId().equals(candidate.attemptId())
          && schemaVersion.equals(candidate.schemaVersion())
          && candidateRef.equals(candidate.candidateRef())
          && executionSlotId.equals(candidate.executionSlotId())
          && repetition == candidate.repetition()
          && childRunId.equals(candidate.childRunId())
          && childTaskId.equals(candidate.childTaskId())
          && sourceRequestOrdinal
              == candidate.sourceRequestOrdinal()
          && sourceResponseHash.equals(
              candidate.sourceResponseHash())
          && traceRootHash.equals(candidate.traceRootHash())
          && outputSchema.equals(candidate.outputSchema())
          && content.equals(candidate.content())
          && contentHash.equals(candidate.contentHash())
          && requiredEvidenceRef.equals(
              candidate.requiredEvidenceRef())
          && requiredEvidenceAvailable
              == candidate.requiredEvidenceAvailable()
          && integrityProfile.equals(
              candidate.integrityProfile())
          && integrityHash.equals(candidate.integrityHash())
          && manifest
              .childSelection()
              .runId()
              .equals(candidate.childRunId())
          && manifest
              .childSelection()
              .taskId()
              .equals(candidate.childTaskId());
    }
  }

  private record WorkerResultRow(
      String parentRunId,
      String childRunId,
      String childTaskId,
      String childStatus,
      String workerResultRef,
      String workerResultJson,
      String contentHash,
      String integrityHash) {

    private boolean matches(
        GraphAttemptManifest manifest,
        WorkerResultEnvelope workerResult) {
      return parentRunId.equals(
              manifest.parentSelection().runId())
          && childRunId.equals(
              manifest.childSelection().runId())
          && childTaskId.equals(
              manifest.childSelection().taskId())
          && "SUCCEEDED".equals(childStatus)
          && childRunId.equals(workerResult.childRunId())
          && childTaskId.equals(workerResult.childTaskId())
          && workerResultRef.equals(
              workerResult.workerResultRef())
          && contentHash.equals(workerResult.contentHash())
          && integrityHash.equals(workerResult.integrityHash());
    }
  }

  private record ArtifactIdentity(
      String artifactId, Integer version) {}

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

  record OwnerClaim(
      GraphAttemptCursor cursor, Instant expiresAt) {

    OwnerClaim {
      Objects.requireNonNull(cursor, "cursor");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  record ProviderSessionClaim(
      GraphAttemptCursor cursor,
      String intentHash,
      Instant expiresAt) {

    ProviderSessionClaim {
      Objects.requireNonNull(cursor, "cursor");
      Objects.requireNonNull(intentHash, "intentHash");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  record AuthorityIdentity(
      String sessionUser,
      String currentUser,
      String database,
      String schema,
      String searchPath,
      String roleOid,
      String databaseOid,
      String serverAddress,
      int serverPort,
      String serverVersionNum,
      boolean canLogin,
      boolean superuser,
      boolean createDatabase,
      boolean createRole,
      boolean inherit,
      boolean replication,
      boolean bypassRowLevelSecurity,
      String authorityAclSurface,
      long memberships,
      boolean sessionCanAssumeCurrent) {

    AuthorityIdentity {
      Objects.requireNonNull(sessionUser, "sessionUser");
      Objects.requireNonNull(currentUser, "currentUser");
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(searchPath, "searchPath");
      Objects.requireNonNull(roleOid, "roleOid");
      Objects.requireNonNull(databaseOid, "databaseOid");
      Objects.requireNonNull(serverAddress, "serverAddress");
      Objects.requireNonNull(serverVersionNum, "serverVersionNum");
      Objects.requireNonNull(authorityAclSurface, "authorityAclSurface");
    }

    private AuthorityIdentity requireDirectLogin() {
      if (!canLogin
          || !sessionUser.equals(currentUser)
          || !sessionCanAssumeCurrent) {
        throw new GraphAttemptIntegrityException();
      }
      return this;
    }
  }

  private static final class AuthorityBoundDataSource
      extends DelegatingDataSource {

    private final AuthorityIdentity expected;

    private AuthorityBoundDataSource(
        DataSource target, AuthorityIdentity expected) {
      super(Objects.requireNonNull(target, "target"));
      this.expected = Objects.requireNonNull(expected, "expected");
    }

    @Override
    public Connection getConnection() throws SQLException {
      return verify(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password)
        throws SQLException {
      return verify(super.getConnection(username, password));
    }

    private Connection verify(Connection connection)
        throws SQLException {
      try {
        JdbcClient connectionJdbc =
            JdbcClient.create(
                new SingleConnectionDataSource(connection, true));
        if (!expected.equals(
            loadAuthorityIdentity(connectionJdbc))) {
          throw new GraphAttemptIntegrityException();
        }
        return connection;
      } catch (RuntimeException failure) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }
  }

  enum ProbePoint {
    AFTER_PREDECESSOR_VERIFIED,
    AFTER_MANIFEST_INSERT,
    AFTER_BINDINGS_INSERT,
    AFTER_RUN_INSERT,
    AFTER_EVENT_INSERT,
    AFTER_HEAD_UPDATE,
    AFTER_PROVIDER_SESSION_INTENT_INSERT,
    AFTER_PROVIDER_ATTRIBUTION_INSERT,
    AFTER_ATTRIBUTED_FAILURE_OUTCOME_INSERT,
    AFTER_PROVIDER_ATTRIBUTION_VERIFIED_READ,
    AFTER_CANDIDATE_INSERT,
    AFTER_WORKER_RESULT_INSERT,
    AFTER_TERMINAL_TRACE_ROW_INSERT,
    AFTER_TERMINAL_TRACE_INSERT,
    AFTER_TERMINAL_RESOURCE_BINDING_ROW_INSERT,
    AFTER_TERMINAL_RESOURCE_BINDINGS_INSERT,
    AFTER_TERMINAL_RUN_UPDATE,
    AFTER_TERMINAL_BINDING_INSERT,
    AFTER_CHILD_TERMINAL_VERIFIED_READ,
    AFTER_PARENT_ARTIFACT_INSERT,
    AFTER_PARENT_ARTIFACT_ROW_INSERT,
    AFTER_PARENT_ARTIFACT_VERSION_INSERT,
    AFTER_PARENT_TRACE_ROW_INSERT,
    AFTER_PARENT_TRACE_INSERT,
    AFTER_PARENT_RESOURCE_BINDING_ROW_INSERT,
    AFTER_PARENT_RESOURCE_BINDINGS_INSERT,
    AFTER_PARENT_RUN_UPDATE,
    AFTER_PARENT_TERMINAL_BINDING_INSERT,
    AFTER_PARENT_EVENT_INSERT,
    AFTER_PARENT_HEAD_UPDATE,
    AFTER_TERMINAL_SEAL_INSERT,
    AFTER_SEAL_EVENT_INSERT,
    AFTER_SEALED_HEAD_UPDATE,
    AFTER_SEALED_VERIFIED_READ,
    VERIFIED_READ_TRANSACTION
  }

  @FunctionalInterface
  interface Probe {

    void hit(ProbePoint point);
  }
}
