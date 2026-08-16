package io.emergeos.adapters.postgres;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exact two-credential PostgreSQL writer composition for Pack010.
 *
 * <p>The prefix writer owns ordinary graph-prefix DML only. The terminal
 * writer owns no relation DML and can invoke only the two V10 semantic
 * functions. Construction freezes both database identities before either
 * capability is returned.
 */
public final class PostgresGraphRuntimeWriters {

  private final PostgresGraphAttemptStore prefixWriter;
  private final PostgresGraphTerminalExecutor terminalWriter;

  private PostgresGraphRuntimeWriters(
      PostgresGraphAttemptStore prefixWriter,
      PostgresGraphTerminalExecutor terminalWriter) {
    this.prefixWriter = prefixWriter;
    this.terminalWriter = terminalWriter;
  }

  public static PostgresGraphRuntimeWriters open(
      DataSource prefixDataSource,
      DataSource terminalDataSource,
      OwnerTtyGraphAuthority ownerAuthority) {
    PostgresGraphRuntimeWriters writers =
        open(prefixDataSource, terminalDataSource);
    Objects.requireNonNull(ownerAuthority, "ownerAuthority")
        .requireRuntimeAuthority(
            writers.prefixWriter.freezeAuthorityIdentity(),
            writers.terminalWriter.databaseIdentity());
    return writers;
  }

  static PostgresGraphRuntimeWriters open(
      DataSource prefixDataSource,
      DataSource terminalDataSource) {
    Objects.requireNonNull(prefixDataSource, "prefixDataSource");
    Objects.requireNonNull(terminalDataSource, "terminalDataSource");
    PostgresGraphAttemptStore directPrefix =
        new PostgresGraphAttemptStore(prefixDataSource);
    PostgresGraphAttemptStore.AuthorityIdentity frozenPrefix =
        directPrefix.freezeAuthorityIdentity();
    RuntimeIdentity prefix =
        requirePrefixAuthority(JdbcClient.create(prefixDataSource));
    PostgresGraphTerminalExecutor terminalWriter =
        new PostgresGraphTerminalExecutor(terminalDataSource);
    PostgresGraphTerminalExecutor.DatabaseIdentity terminal =
        terminalWriter.databaseIdentity();
    if (!prefix.sameDatabase(terminal)
        || !prefix.schema().equals("public")
        || !prefix.searchPath().equals("\"$user\", public")
        || !frozenPrefix.sessionUser().equals(prefix.role())
        || !frozenPrefix.currentUser().equals(prefix.role())
        || !frozenPrefix.database().equals(prefix.database())
        || !frozenPrefix.databaseOid().equals(prefix.databaseOid())
        || !frozenPrefix.schema().equals(prefix.schema())
        || !frozenPrefix.searchPath().equals(prefix.searchPath())
        || !frozenPrefix.serverAddress().equals(prefix.serverAddress())
        || frozenPrefix.serverPort() != prefix.serverPort()
        || !terminal.role().equals("emergeos_graph_executor")) {
      throw new GraphAttemptIntegrityException();
    }
    return new PostgresGraphRuntimeWriters(
        directPrefix.bindAuthorityIdentity(frozenPrefix),
        terminalWriter);
  }

  public GraphAttemptVerification findVerified(
      GraphAttemptManifest manifest) {
    return prefixWriter.findVerified(
        Objects.requireNonNull(manifest, "manifest"));
  }

  public ChildTerminalTransition prepareChild(
      GraphAttemptManifest manifest,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    String payload =
        PostgresGraphTerminalPayloads.child(
            before, terminalChild, candidate, workerResult);
    return new ChildTerminalTransition(
        this,
        manifest,
        before.cursor(),
        terminalChild.runId(),
        terminalChild.bundle().integrityHash(),
        candidate.integrityHash(),
        workerResult.integrityHash(),
        payload);
  }

  public ParentTerminalTransition prepareParentAndSeal(
      GraphAttemptManifest manifest,
      AgentRun terminalParent,
      ArtifactLineage artifact) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    String payload =
        PostgresGraphTerminalPayloads.parentAndSeal(
            before, terminalParent, artifact);
    return new ParentTerminalTransition(
        this,
        manifest,
        before.cursor(),
        terminalParent.runId(),
        terminalParent.bundle().integrityHash(),
        before.terminalBindings().getFirst().terminalHash(),
        artifact.current().contentHash(),
        payload);
  }

  public ChildFailureTransition preparePreCandidateFailureChild(
      GraphAttemptManifest manifest, AgentRun terminalChild) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    String payload =
        PostgresGraphTerminalPayloads.preCandidateFailureChild(
            before, terminalChild);
    return new ChildFailureTransition(
        this,
        manifest,
        before.cursor(),
        terminalChild.runId(),
        terminalChild.bundle().integrityHash(),
        payload);
  }

  public ParentFailureTransition
      preparePreCandidateFailureParentAndSeal(
          GraphAttemptManifest manifest, AgentRun terminalParent) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    String payload =
        PostgresGraphTerminalPayloads
            .preCandidateFailureParentAndSeal(
                before, terminalParent);
    return new ParentFailureTransition(
        this,
        manifest,
        before.cursor(),
        terminalParent.runId(),
        terminalParent.bundle().integrityHash(),
        before.terminalBindings().getFirst().terminalHash(),
        payload);
  }

  public String completeChild(
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.ChildTerminalClaim claim,
      GraphAttemptManifest manifest,
      ChildTerminalTransition transition) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    Objects.requireNonNull(transition, "transition")
        .requireMatches(this, manifest, before);
    Instant expiresAt =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority")
            .consumeChildTerminalClaim(
                Objects.requireNonNull(claim, "claim"), manifest);
    transition.consume(this, manifest, before);
    return terminalWriter.completeChild(manifest, expiresAt, transition);
  }

  public String completeParentAndSeal(
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.ParentTerminalClaim claim,
      GraphAttemptManifest manifest,
      ParentTerminalTransition transition) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    Objects.requireNonNull(transition, "transition")
        .requireMatches(this, manifest, before);
    Instant expiresAt =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority")
            .consumeParentTerminalClaim(
                Objects.requireNonNull(claim, "claim"), manifest);
    transition.consume(this, manifest, before);
    return terminalWriter.completeParentAndSeal(
        manifest, expiresAt, transition);
  }

  public String completePreCandidateFailureChild(
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.ChildTerminalClaim claim,
      GraphAttemptManifest manifest,
      ChildFailureTransition transition) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    Objects.requireNonNull(transition, "transition")
        .requireMatches(this, manifest, before);
    Instant expiresAt =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority")
            .consumeChildTerminalClaim(
                Objects.requireNonNull(claim, "claim"), manifest);
    transition.consume(this, manifest, before);
    return terminalWriter.completePreCandidateFailureChild(
        manifest, expiresAt, transition);
  }

  public String completePreCandidateFailureParentAndSeal(
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.ParentTerminalClaim claim,
      GraphAttemptManifest manifest,
      ParentFailureTransition transition) {
    GraphAttemptSnapshot before = verifiedSnapshot(manifest);
    Objects.requireNonNull(transition, "transition")
        .requireMatches(this, manifest, before);
    Instant expiresAt =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority")
            .consumeParentTerminalClaim(
                Objects.requireNonNull(claim, "claim"), manifest);
    transition.consume(this, manifest, before);
    return terminalWriter.completePreCandidateFailureParentAndSeal(
        manifest, expiresAt, transition);
  }

  private GraphAttemptSnapshot verifiedSnapshot(
      GraphAttemptManifest manifest) {
    GraphAttemptVerification verification = findVerified(manifest);
    if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
      throw new GraphAttemptIntegrityException();
    }
    return valid.snapshot();
  }

  public static final class ChildTerminalTransition {

    private final PostgresGraphRuntimeWriters owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final String runId;
    private final String bundleHash;
    private final String candidateHash;
    private final String workerResultHash;
    private final String payload;
    private final String payloadHash;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ChildTerminalTransition(
        PostgresGraphRuntimeWriters owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        String runId,
        String bundleHash,
        String candidateHash,
        String workerResultHash,
        String payload) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.runId = Objects.requireNonNull(runId, "runId");
      this.bundleHash = Objects.requireNonNull(bundleHash, "bundleHash");
      this.candidateHash =
          Objects.requireNonNull(candidateHash, "candidateHash");
      this.workerResultHash =
          Objects.requireNonNull(workerResultHash, "workerResultHash");
      this.payload = Objects.requireNonNull(payload, "payload");
      this.payloadHash = IntegrityHashes.utf8ContentHash(payload);
    }

    private void requireMatches(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(before.cursor())
          || before.childRun() == null
          || !runId.equals(before.manifest().childSelection().runId())
          || before.candidate() != null
          || before.workerResult() != null
          || before.terminalSeal() != null
          || !payloadHash.equals(IntegrityHashes.utf8ContentHash(payload))
          || consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      if (bundleHash.isBlank()
          || candidateHash.isBlank()
          || workerResultHash.isBlank()) {
        throw new GraphAttemptIntegrityException();
      }
    }

    private void consume(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      requireMatches(expectedOwner, expectedManifest, before);
      if (!consumed.compareAndSet(false, true)) {
        throw new GraphAttemptIntegrityException();
      }
    }

    String payloadForExecutor() {
      if (!consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      return payload;
    }
  }

  public static final class ParentTerminalTransition {

    private final PostgresGraphRuntimeWriters owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final String runId;
    private final String bundleHash;
    private final String childTerminalHash;
    private final String artifactHash;
    private final String payload;
    private final String payloadHash;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ParentTerminalTransition(
        PostgresGraphRuntimeWriters owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        String runId,
        String bundleHash,
        String childTerminalHash,
        String artifactHash,
        String payload) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.runId = Objects.requireNonNull(runId, "runId");
      this.bundleHash = Objects.requireNonNull(bundleHash, "bundleHash");
      this.childTerminalHash =
          Objects.requireNonNull(childTerminalHash, "childTerminalHash");
      this.artifactHash = Objects.requireNonNull(artifactHash, "artifactHash");
      this.payload = Objects.requireNonNull(payload, "payload");
      this.payloadHash = IntegrityHashes.utf8ContentHash(payload);
    }

    private void requireMatches(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(before.cursor())
          || !runId.equals(before.manifest().parentSelection().runId())
          || before.terminalBindings().size() != 1
          || !childTerminalHash.equals(
              before.terminalBindings().getFirst().terminalHash())
          || before.artifact() != null
          || before.terminalSeal() != null
          || !payloadHash.equals(IntegrityHashes.utf8ContentHash(payload))
          || consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      if (bundleHash.isBlank() || artifactHash.isBlank()) {
        throw new GraphAttemptIntegrityException();
      }
    }

    private void consume(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      requireMatches(expectedOwner, expectedManifest, before);
      if (!consumed.compareAndSet(false, true)) {
        throw new GraphAttemptIntegrityException();
      }
    }

    String payloadForExecutor() {
      if (!consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      return payload;
    }
  }

  public static final class ChildFailureTransition {

    private final PostgresGraphRuntimeWriters owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final String runId;
    private final String bundleHash;
    private final String payload;
    private final String payloadHash;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ChildFailureTransition(
        PostgresGraphRuntimeWriters owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        String runId,
        String bundleHash,
        String payload) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.runId = Objects.requireNonNull(runId, "runId");
      this.bundleHash = Objects.requireNonNull(bundleHash, "bundleHash");
      this.payload = Objects.requireNonNull(payload, "payload");
      this.payloadHash = IntegrityHashes.utf8ContentHash(payload);
    }

    private void requireMatches(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(before.cursor())
          || before.childRun() == null
          || !runId.equals(before.manifest().childSelection().runId())
          || before.candidate() != null
          || before.workerResult() != null
          || before.terminalSeal() != null
          || bundleHash.isBlank()
          || !payloadHash.equals(IntegrityHashes.utf8ContentHash(payload))
          || consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
    }

    private void consume(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      requireMatches(expectedOwner, expectedManifest, before);
      if (!consumed.compareAndSet(false, true)) {
        throw new GraphAttemptIntegrityException();
      }
    }

    String payloadForExecutor() {
      if (!consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      return payload;
    }
  }

  public static final class ParentFailureTransition {

    private final PostgresGraphRuntimeWriters owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final String runId;
    private final String bundleHash;
    private final String childTerminalHash;
    private final String payload;
    private final String payloadHash;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ParentFailureTransition(
        PostgresGraphRuntimeWriters owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        String runId,
        String bundleHash,
        String childTerminalHash,
        String payload) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.runId = Objects.requireNonNull(runId, "runId");
      this.bundleHash = Objects.requireNonNull(bundleHash, "bundleHash");
      this.childTerminalHash =
          Objects.requireNonNull(childTerminalHash, "childTerminalHash");
      this.payload = Objects.requireNonNull(payload, "payload");
      this.payloadHash = IntegrityHashes.utf8ContentHash(payload);
    }

    private void requireMatches(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(before.cursor())
          || !runId.equals(before.manifest().parentSelection().runId())
          || before.candidate() != null
          || before.workerResult() != null
          || before.terminalBindings().size() != 1
          || !childTerminalHash.equals(
              before.terminalBindings().getFirst().terminalHash())
          || before.artifact() != null
          || before.terminalSeal() != null
          || bundleHash.isBlank()
          || !payloadHash.equals(IntegrityHashes.utf8ContentHash(payload))
          || consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
    }

    private void consume(
        PostgresGraphRuntimeWriters expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot before) {
      requireMatches(expectedOwner, expectedManifest, before);
      if (!consumed.compareAndSet(false, true)) {
        throw new GraphAttemptIntegrityException();
      }
    }

    String payloadForExecutor() {
      if (!consumed.get()) {
        throw new GraphAttemptIntegrityException();
      }
      return payload;
    }
  }

  private static RuntimeIdentity requirePrefixAuthority(
      JdbcClient jdbc) {
    RuntimeIdentity identity = runtimeIdentity(jdbc);
    try {
      Boolean exact =
          jdbc.sql(
                  """
                  SELECT
                    session_user = current_user
                    AND current_user = 'emergeos_graph_prefix_writer'
                    AND role.rolcanlogin
                    AND NOT role.rolinherit
                    AND NOT role.rolsuper
                    AND NOT role.rolcreatedb
                    AND NOT role.rolcreaterole
                    AND NOT role.rolreplication
                    AND NOT role.rolbypassrls
                    AND NOT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_auth_members membership
                      WHERE membership.member = role.oid
                         OR membership.roleid = role.oid)
                    AND pg_catalog.has_database_privilege(
                      current_user, current_database(), 'CONNECT')
                    AND NOT pg_catalog.has_database_privilege(
                      current_user, current_database(),
                      'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
                    AND pg_catalog.has_schema_privilege(
                      current_user, 'public', 'USAGE')
                    AND NOT pg_catalog.has_schema_privilege(
                      current_user, 'public',
                      'CREATE,USAGE WITH GRANT OPTION')
                    AND (
                      SELECT count(*)
                      FROM pg_catalog.pg_proc procedure
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = procedure.pronamespace
                      WHERE namespace.nspname = 'public'
                        AND pg_catalog.has_function_privilege(
                          current_user, procedure.oid, 'EXECUTE')) = 6
                    AND NOT EXISTS (
                      SELECT 1
                      FROM pg_catalog.pg_proc procedure
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = procedure.pronamespace
                      JOIN pg_catalog.pg_roles owner
                        ON owner.oid = procedure.proowner
                      WHERE namespace.nspname = 'public'
                        AND pg_catalog.has_function_privilege(
                          current_user, procedure.oid, 'EXECUTE')
                        AND NOT (
                          procedure.proname IN (
                            'agent_assert_graph_attempt_v7',
                            'agent_assert_graph_attempt_prefix_v7',
                            'agent_assert_graph_attempt_v8',
                            'agent_assert_worker_graph_v6',
                            'agent_graph_terminal_run_valid_v8',
                            'agent_graph_record_attributed_failure_v11')
                          AND pg_catalog.pg_get_function_identity_arguments(
                                procedure.oid) = CASE procedure.proname
                            WHEN 'agent_assert_worker_graph_v6'
                              THEN 'checked_principal_id character varying, checked_run_id character varying'
                            WHEN 'agent_graph_terminal_run_valid_v8'
                              THEN 'checked_principal character varying, checked_attempt character, checked_role character varying'
                            WHEN 'agent_graph_record_attributed_failure_v11'
                              THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_previous_sequence integer, checked_previous_head character, checked_failure_code character varying'
                            ELSE 'checked_principal character varying, checked_attempt character'
                          END
                          AND NOT pg_catalog.has_function_privilege(
                                current_user, procedure.oid,
                                'EXECUTE WITH GRANT OPTION')
                          AND procedure.prosecdef =
                                (procedure.proname =
                                  'agent_graph_record_attributed_failure_v11')
                          AND procedure.proconfig = CASE
                            WHEN procedure.proname =
                                   'agent_graph_record_attributed_failure_v11'
                              THEN ARRAY[
                                'search_path=pg_catalog, pg_temp']::text[]
                            ELSE ARRAY[
                              'search_path=pg_catalog, public, pg_temp']::text[]
                          END
                          AND owner.rolname
                                = 'emergeos_pack010_schema_owner'
                          AND pg_catalog.encode(
                                pg_catalog.sha256(
                                  pg_catalog.convert_to(
                                    procedure.prosrc, 'UTF8')),
                                'hex')
                                = CASE procedure.proname
                                  WHEN 'agent_assert_graph_attempt_v7'
                                    THEN '8c914b316805f490d108122a40da7ed7c5caf618faeb27a0cfc91c59ea0fcedd'
                                  WHEN 'agent_assert_graph_attempt_prefix_v7'
                                    THEN '8ae2ce06772b6b0254a68e0a1c39bb18ad798e24212b9bbdd280f5f4db9e5ead'
                                  WHEN 'agent_assert_graph_attempt_v8'
                                    THEN '2e1485d7b259b0c3653241de5d0619c609101d9c6afcc77b0b0421eb609b002a'
                                  WHEN 'agent_assert_worker_graph_v6'
                                    THEN '3fef7a6f1a51c0d3b695866a825d28488bbdd58e690b749ed13b1f0e231431f4'
                                  WHEN 'agent_graph_terminal_run_valid_v8'
                                    THEN '1e6dc7088b526e77d369a725fbefeeb521284413916ad0073a4ed3699bc97db7'
                                  WHEN 'agent_graph_record_attributed_failure_v11'
                                    THEN '4ddd44034bcaf7aa516c78785f0095c9cacec933b651630d2a1675a161de31b4'
                                  ELSE NULL
                                END)
                    )
                    AND (
                      SELECT count(*)
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      WHERE namespace.nspname = 'public'
                        AND relation.relkind IN ('r', 'p')
                        AND relation.relname IN (
                          'agent_graph_attempts',
                          'agent_graph_attempt_run_bindings',
                          'agent_graph_attempt_heads',
                          'agent_graph_attempt_events',
                          'agent_graph_attempt_provider_attributions',
                          'agent_graph_provider_session_intents',
                          'agent_graph_attributed_failure_outcomes',
                          'agent_runs',
                          'agent_graph_attempt_terminal_bindings',
                          'agent_graph_attempt_candidates',
                          'agent_worker_results',
                          'agent_graph_attempt_seals',
                          'agent_trace_events',
                          'agent_run_resource_bindings',
                          'artifacts',
                          'artifact_versions')) = 16
                    AND NOT EXISTS (
                      SELECT 1
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      WHERE namespace.nspname = 'public'
                        AND relation.relkind IN ('r', 'p')
                        AND (
                          relation.relowner = role.oid
                          OR pg_catalog.has_table_privilege(
                                current_user, relation.oid, 'SELECT')
                              <> (relation.relname IN (
                                'agent_graph_attempts',
                                'agent_graph_attempt_run_bindings',
                                'agent_graph_attempt_heads',
                                'agent_graph_attempt_events',
                                'agent_graph_attempt_provider_attributions',
                                'agent_graph_provider_session_intents',
                                'agent_graph_attributed_failure_outcomes',
                                'agent_runs',
                                'agent_graph_attempt_terminal_bindings',
                                'agent_graph_attempt_candidates',
                                'agent_worker_results',
                                'agent_graph_attempt_seals',
                                'agent_trace_events',
                                'agent_run_resource_bindings',
                                'artifacts',
                                'artifact_versions'))
                          OR pg_catalog.has_table_privilege(
                            current_user, relation.oid,
                            'DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                          OR pg_catalog.has_table_privilege(
                                current_user, relation.oid, 'UPDATE')
                              <> (relation.relname
                                    = 'agent_graph_attempt_heads')
                          OR pg_catalog.has_table_privilege(
                                current_user, relation.oid, 'INSERT')
                              <> (relation.relname IN (
                                'agent_graph_attempts',
                                'agent_graph_attempt_run_bindings',
                                'agent_graph_attempt_heads',
                                'agent_graph_attempt_events',
                                'agent_graph_attempt_provider_attributions',
                                'agent_graph_provider_session_intents',
                                'agent_runs'))
                          OR pg_catalog.has_table_privilege(
                                current_user, relation.oid,
                                'SELECT WITH GRANT OPTION,INSERT WITH GRANT OPTION,UPDATE WITH GRANT OPTION,DELETE WITH GRANT OPTION,TRUNCATE WITH GRANT OPTION,REFERENCES WITH GRANT OPTION,TRIGGER WITH GRANT OPTION,MAINTAIN WITH GRANT OPTION'))
                    )
                    AND NOT EXISTS (
                      SELECT 1
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      WHERE namespace.nspname = 'public'
                        AND relation.relkind = 'S'
                        AND (
                          relation.relowner = role.oid
                          OR pg_catalog.has_sequence_privilege(
                            current_user, relation.oid,
                            'USAGE,SELECT,UPDATE')))
                    AND NOT EXISTS (
                      SELECT 1
                      FROM pg_catalog.pg_trigger trigger
                      JOIN pg_catalog.pg_class relation
                        ON relation.oid = trigger.tgrelid
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      WHERE namespace.nspname = 'public'
                        AND relation.relname
                              = 'agent_graph_provider_session_intents'
                        AND NOT trigger.tgisinternal)
                    AND NOT EXISTS (
                      SELECT 1
                      FROM pg_catalog.pg_attribute attribute
                      JOIN pg_catalog.pg_class relation
                        ON relation.oid = attribute.attrelid
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = relation.relnamespace
                      WHERE namespace.nspname = 'public'
                        AND attribute.attnum > 0
                        AND NOT attribute.attisdropped
                        AND attribute.attacl IS NOT NULL)
                  FROM pg_catalog.pg_roles role
                  WHERE role.rolname = current_user
                  """)
              .query(Boolean.class)
              .single();
      if (!Boolean.TRUE.equals(exact)) {
        throw new GraphAttemptIntegrityException();
      }
      return identity;
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private static RuntimeIdentity runtimeIdentity(JdbcClient jdbc) {
    try {
      return jdbc.sql(
              """
              SELECT current_user,
                     current_database(),
                     database.oid::text AS database_oid,
                     current_schema(),
                     current_setting('search_path'),
                     COALESCE(
                       pg_catalog.inet_server_addr()::text,
                       'local-socket') AS server_address,
                     COALESCE(
                       pg_catalog.inet_server_port(), -1) AS server_port
              FROM pg_catalog.pg_database database
              WHERE database.datname = current_database()
              """)
          .query(
              (row, ignored) ->
                  new RuntimeIdentity(
                      row.getString(1),
                      row.getString(2),
                      row.getString(3),
                      row.getString(4),
                      row.getString(5),
                      row.getString(6),
                      row.getInt(7)))
          .single();
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private record RuntimeIdentity(
      String role,
      String database,
      String databaseOid,
      String schema,
      String searchPath,
      String serverAddress,
      int serverPort) {

    private RuntimeIdentity {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(databaseOid, "databaseOid");
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(searchPath, "searchPath");
      Objects.requireNonNull(serverAddress, "serverAddress");
    }

    private boolean sameDatabase(
        PostgresGraphTerminalExecutor.DatabaseIdentity other) {
      return database.equals(other.database())
          && databaseOid.equals(other.databaseOid())
          && serverAddress.equals(other.serverAddress())
          && serverPort == other.serverPort();
    }
  }
}
