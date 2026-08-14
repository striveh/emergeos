package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Narrow PostgreSQL resume surface for one durable attributed failure. */
public final class PostgresAttributedFailureResumeStore {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final RuntimeIdentity authorityIdentity;

  public PostgresAttributedFailureResumeStore(DataSource dataSource) {
    DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = JdbcClient.create(checked);
    this.transactions =
        new TransactionTemplate(new DataSourceTransactionManager(checked));
    transactions.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.authorityIdentity =
        Objects.requireNonNull(
            transactions.execute(ignored -> requireAuthority()),
            "failure resumer authority identity");
    RuntimeIdentity second =
        Objects.requireNonNull(
            transactions.execute(ignored -> requireAuthority()),
            "failure resumer authority identity");
    if (!authorityIdentity.equals(second)) {
      throw new GraphAttemptIntegrityException();
    }
  }

  public DurableFailureCursor load(GraphAttemptManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                return loadCurrentTransaction(manifest);
              }),
          "durable failure cursor");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private DurableFailureCursor loadCurrentTransaction(
      GraphAttemptManifest manifest) {
    DurableFailureCursor cursor =
        jdbc.sql(
                "SELECT * FROM public."
                    + "agent_graph_read_attributed_failure_resume_v12("
                    + ":principalId, :attemptId, :manifestHash)")
            .param("principalId", manifest.principalId())
            .param("attemptId", manifest.attemptId())
            .param("manifestHash", manifest.manifestHash())
            .query(
                (row, ignored) ->
                    new DurableFailureCursor(
                        manifest.principalId(),
                        manifest.attemptId(),
                        row.getString("manifest_hash"),
                        row.getString("revision"),
                        row.getString("session_intent_hash"),
                        row.getObject("session_expires_at", OffsetDateTime.class)
                            .toInstant(),
                        row.getInt("cursor_sequence"),
                        row.getString("cursor_head_hash"),
                        row.getString("provider_attribution_1_hash"),
                        row.getString("provider_attribution_2_hash"),
                        row.getString("request_hash"),
                        row.getString("response_hash"),
                        row.getString("model_resolved"),
                        GraphAttributedFailureCode.require(
                            row.getString("failure_code")),
                        row.getString("provenance_hash"),
                        State.valueOf(row.getString("state")),
                        row.getLong("state_version"),
                        row.getString("claimant_id"),
                        row.getString("fence_token_hash"),
                        row.getObject("claim_expires_at", OffsetDateTime.class)
                            == null
                            ? null
                            : row.getObject(
                                    "claim_expires_at", OffsetDateTime.class)
                                .toInstant(),
                        row.getObject("child_claim_version", Long.class),
                        row.getString("child_claimant_id"),
                        row.getString("child_fence_token_hash"),
                        instant(row.getObject(
                            "child_claim_expires_at", OffsetDateTime.class)),
                        row.getObject("child_sequence", Integer.class),
                        row.getString("child_head_hash"),
                        row.getString("child_terminal_hash"),
                        row.getObject("parent_claim_version", Long.class),
                        row.getObject("parent_sequence", Integer.class),
                        row.getString("parent_head_hash"),
                        row.getString("parent_terminal_hash"),
                        row.getObject("terminal_sequence", Integer.class),
                        row.getString("terminal_head_hash"),
                        row.getString("seal_hash"),
                        row.getInt("live_sequence"),
                        row.getString("live_head_hash")))
            .single();
    if (!manifest.manifestHash().equals(cursor.manifestHash())) {
      throw new GraphAttemptConflictException(
          "durable attributed failure cursor is stale");
    }
    return cursor;
  }

  public DurableFailureCursor claim(
      GraphAttemptManifest manifest,
      DurableFailureCursor expected,
      String claimantId,
      String fenceTokenHash,
      Duration lease) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(lease, "lease");
    if (!manifest.principalId().equals(expected.principalId())
        || !manifest.attemptId().equals(expected.attemptId())
        || !manifest.manifestHash().equals(expected.manifestHash())
        || claimantId == null
        || claimantId.isBlank()
        || claimantId.length() > 200
        || fenceTokenHash == null
        || !fenceTokenHash.matches("[a-f0-9]{64}")
        || lease.compareTo(Duration.ofMillis(100)) < 0
        || lease.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException(
          "durable attributed failure claim is invalid");
    }
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                long nextVersion =
                    jdbc.sql(
                            "SELECT public."
                                + "agent_graph_claim_attributed_failure_resume_v12("
                                + ":principalId, :attemptId, :manifestHash, "
                                + ":provenanceHash, :stateVersion, :claimantId, "
                                + ":fenceTokenHash, :leaseMillis)")
                        .param("principalId", manifest.principalId())
                        .param("attemptId", manifest.attemptId())
                        .param("manifestHash", manifest.manifestHash())
                        .param("provenanceHash", expected.provenanceHash())
                        .param("stateVersion", expected.stateVersion())
                        .param("claimantId", claimantId)
                        .param("fenceTokenHash", fenceTokenHash)
                        .param(
                            "leaseMillis",
                            Math.toIntExact(lease.toMillis()))
                        .query(Long.class)
                        .single();
                DurableFailureCursor claimed =
                    loadCurrentTransaction(manifest);
                State expectedState =
                    switch (expected.state()) {
                      case READY, CLAIMED -> State.CLAIMED;
                      case CHILD_CONSUMED, PARENT_CLAIMED ->
                          State.PARENT_CLAIMED;
                      case TERMINAL_CONSUMED ->
                          throw new GraphAttemptConflictException(
                              "durable attributed failure is already consumed");
                    };
                if (claimed.state() != expectedState
                    || claimed.stateVersion() != nextVersion
                    || !claimantId.equals(claimed.claimantId())
                    || !fenceTokenHash.equals(claimed.fenceTokenHash())
                    || (expectedState == State.PARENT_CLAIMED
                        && claimed.parentClaimVersion() != nextVersion)) {
                  throw new GraphAttemptConflictException(
                      "durable attributed failure claim read-back drifted");
                }
                return claimed;
              }),
          "durable attributed failure claim");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw mappedFailure(
          failure, "durable attributed failure claim was fenced");
    }
  }

  public DurableFailureCursor completeClaimedFailureChild(
      GraphAttemptManifest manifest,
      DurableFailureCursor expectedClaim,
      DataSource readerDataSource,
      AgentRun terminalChild) {
    requireCompletionInput(manifest, expectedClaim, State.CLAIMED, 14);
    GraphAttemptSnapshot before =
        requireCompletionSnapshot(
            manifest, expectedClaim, readerDataSource, 14);
    AgentRun checkedTerminalChild =
        requireTerminalRun(before, terminalChild, true);
    String payload =
        PostgresGraphTerminalPayloads.preCandidateFailureChild(
            before, checkedTerminalChild);
    return complete(
        manifest,
        expectedClaim,
        payload,
        """
        SELECT (result ->> 'sequence')::integer AS sequence,
               result ->> 'head_hash' AS head_hash,
               result ->> 'resume_state' AS resume_state,
               (result ->> 'resume_version')::bigint AS resume_version
        FROM (
          SELECT public.agent_graph_complete_claimed_failure_child_v12(
            CAST(:payload AS jsonb), :provenanceHash, :stateVersion,
            :claimantId, :fenceTokenHash
          ) AS result
        ) completion
        """,
        State.CHILD_CONSUMED,
        15);
  }

  public DurableFailureCursor completeClaimedFailureParentAndSeal(
      GraphAttemptManifest manifest,
      DurableFailureCursor expectedClaim,
      DataSource readerDataSource,
      AgentRun terminalParent) {
    requireCompletionInput(
        manifest, expectedClaim, State.PARENT_CLAIMED, 15);
    GraphAttemptSnapshot before =
        requireCompletionSnapshot(
            manifest, expectedClaim, readerDataSource, 15);
    AgentRun checkedTerminalParent =
        requireTerminalRun(before, terminalParent, false);
    String payload =
        PostgresGraphTerminalPayloads.preCandidateFailureParentAndSeal(
            before, checkedTerminalParent);
    return complete(
        manifest,
        expectedClaim,
        payload,
        """
        SELECT (result ->> 'sequence')::integer AS sequence,
               result ->> 'head_hash' AS head_hash,
               result ->> 'resume_state' AS resume_state,
               (result ->> 'resume_version')::bigint AS resume_version
        FROM (
          SELECT public.agent_graph_complete_claimed_failure_parent_and_seal_v12(
            CAST(:payload AS jsonb), :provenanceHash, :stateVersion,
            :claimantId, :fenceTokenHash
          ) AS result
        ) completion
        """,
        State.TERMINAL_CONSUMED,
        17);
  }

  private DurableFailureCursor complete(
      GraphAttemptManifest manifest,
      DurableFailureCursor expectedClaim,
      String payload,
      String sql,
      State expectedState,
      int expectedSequence) {
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                CompletionReceipt receipt =
                    jdbc.sql(sql)
                        .param("payload", payload)
                        .param(
                            "provenanceHash",
                            expectedClaim.provenanceHash())
                        .param("stateVersion", expectedClaim.stateVersion())
                        .param("claimantId", expectedClaim.claimantId())
                        .param(
                            "fenceTokenHash",
                            expectedClaim.fenceTokenHash())
                        .query(
                            (row, rowNumber) ->
                                new CompletionReceipt(
                                    row.getInt("sequence"),
                                    row.getString("head_hash"),
                                    State.valueOf(
                                        row.getString("resume_state")),
                                    row.getLong("resume_version")))
                        .single();
                DurableFailureCursor completed =
                    loadCurrentTransaction(manifest);
                if (receipt.sequence() != expectedSequence
                    || receipt.state() != expectedState
                    || receipt.stateVersion()
                        != expectedClaim.stateVersion() + 1
                    || completed.state() != expectedState
                    || completed.stateVersion() != receipt.stateVersion()
                    || completed.liveSequence() != receipt.sequence()
                    || !completed.liveHeadHash().equals(receipt.headHash())) {
                  throw new GraphAttemptConflictException(
                      "durable failure terminal read-back drifted");
                }
                if (expectedState == State.CHILD_CONSUMED
                    && (completed.childClaimVersion()
                            != expectedClaim.stateVersion()
                        || !expectedClaim.claimantId().equals(
                            completed.childClaimantId())
                        || !expectedClaim.fenceTokenHash().equals(
                            completed.childFenceTokenHash()))) {
                  throw new GraphAttemptConflictException(
                      "durable child terminal claim read-back drifted");
                }
                if (expectedState == State.TERMINAL_CONSUMED
                    && (completed.parentClaimVersion()
                            != expectedClaim.stateVersion()
                        || !expectedClaim.claimantId().equals(
                            completed.claimantId())
                        || !expectedClaim.fenceTokenHash().equals(
                            completed.fenceTokenHash()))) {
                  throw new GraphAttemptConflictException(
                      "durable parent terminal claim read-back drifted");
                }
                return completed;
              }),
          "durable failure terminal completion");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw mappedFailure(
          failure, "durable failure terminal claim was fenced");
    }
  }

  private static void requireCompletionInput(
      GraphAttemptManifest manifest,
      DurableFailureCursor expectedClaim,
      State expectedState,
      int expectedSequence) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expectedClaim, "expectedClaim");
    if (!manifest.principalId().equals(expectedClaim.principalId())
        || !manifest.attemptId().equals(expectedClaim.attemptId())
        || !manifest.manifestHash().equals(expectedClaim.manifestHash())
        || expectedClaim.state() != expectedState
        || expectedClaim.claimantId() == null
        || expectedClaim.fenceTokenHash() == null
        || expectedClaim.claimExpiresAt() == null
        || expectedClaim.liveSequence() != expectedSequence) {
      throw new IllegalArgumentException(
          "durable failure terminal completion is invalid");
    }
  }

  private GraphAttemptSnapshot requireCompletionSnapshot(
      GraphAttemptManifest manifest,
      DurableFailureCursor expectedClaim,
      DataSource readerDataSource,
      int expectedSequence) {
    try {
      PostgresGraphAttemptStore directReader =
          new PostgresGraphAttemptStore(
              Objects.requireNonNull(readerDataSource, "readerDataSource"));
      PostgresGraphAttemptStore.AuthorityIdentity readerIdentity =
          directReader.freezeAuthorityIdentity();
      if (!authorityIdentity.sameDatabase(readerIdentity)) {
        throw new GraphAttemptIntegrityException();
      }
      GraphAttemptVerification verification =
          directReader
              .bindAuthorityIdentity(readerIdentity)
              .openRestrictedReader()
              .findVerified(manifest);
      if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
        throw new GraphAttemptIntegrityException();
      }
      GraphAttemptSnapshot before = valid.snapshot();
      if (!manifest.equals(before.manifest())
          || before.cursor().lastSequence() != expectedSequence
          || !expectedClaim.liveHeadHash().equals(
              before.cursor().headHash())) {
        throw new GraphAttemptConflictException(
            "durable failure terminal snapshot is stale");
      }
      return before;
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private static AgentRun requireTerminalRun(
      GraphAttemptSnapshot before,
      AgentRun terminal,
      boolean child) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminal, child ? "terminalChild" : "terminalParent");
    AgentRun running = child ? before.childRun() : before.parentRun();
    if (running == null
        || terminal.lifecycle() != AgentRunLifecycle.FAILED
        || !running.runId().equals(terminal.runId())
        || !running.principalId().equals(terminal.principalId())
        || !running.task().equals(terminal.task())
        || !running.startedAt().equals(terminal.startedAt())) {
      throw new IllegalArgumentException(
          "durable failure terminal "
              + (child ? "child" : "parent")
              + " does not match the running graph");
    }
    return terminal;
  }

  private static RuntimeException mappedFailure(
      RuntimeException failure, String conflictMessage) {
    String state = sqlState(failure);
    if ("55000".equals(state)) {
      return new GraphAttemptConflictException(conflictMessage);
    }
    return new GraphAttemptIntegrityException(failure);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private record CompletionReceipt(
      int sequence, String headHash, State state, long stateVersion) {

    private CompletionReceipt {
      Objects.requireNonNull(headHash, "headHash");
      Objects.requireNonNull(state, "state");
    }
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

  private void requireFrozenAuthority() {
    if (!authorityIdentity.equals(requireAuthority())) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private RuntimeIdentity requireAuthority() {
    try {
      AuthorityProbe probe =
          jdbc.sql(
                  """
                  SELECT current_user,
                         current_database(),
                         database.oid::text AS database_oid,
                         current_schema(),
                         current_setting('search_path') AS search_path,
                         COALESCE(
                           pg_catalog.inet_server_addr()::text,
                           'local-socket') AS server_address,
                         COALESCE(
                           pg_catalog.inet_server_port(), -1) AS server_port,
                         session_user = current_user
                         AND current_user =
                           'emergeos_failure_resumer'
                         AND role.rolcanlogin
                         AND NOT role.rolinherit
                         AND NOT role.rolsuper
                         AND NOT role.rolcreatedb
                         AND NOT role.rolcreaterole
                         AND NOT role.rolreplication
                         AND NOT role.rolbypassrls
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_auth_members membership
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
                               current_user, procedure.oid, 'EXECUTE')
                         ) = 5
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace namespace
                             ON namespace.oid = procedure.pronamespace
                           JOIN pg_catalog.pg_roles owner
                             ON owner.oid = procedure.proowner
                           JOIN pg_catalog.pg_language language
                             ON language.oid = procedure.prolang
                           WHERE namespace.nspname = 'public'
                             AND procedure.proname IN (
                               'agent_graph_read_attributed_failure_resume_v12',
                               'agent_graph_claim_attributed_failure_resume_v12',
                               'agent_graph_complete_claimed_failure_child_v12',
                               'agent_graph_complete_claimed_failure_parent_and_seal_v12',
                               'emergeos_pack010_schema_version_v1')
                             AND pg_catalog.pg_get_function_identity_arguments(
                               procedure.oid) = CASE procedure.proname
                               WHEN 'agent_graph_read_attributed_failure_resume_v12'
                                 THEN 'checked_principal character varying, checked_attempt character, checked_manifest character'
                               WHEN 'agent_graph_claim_attributed_failure_resume_v12'
                                 THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character, checked_lease_millis integer'
                               WHEN 'emergeos_pack010_schema_version_v1'
                                 THEN ''
                               ELSE 'payload jsonb, checked_provenance character, checked_state_version bigint, checked_claimant character varying, checked_fence_token_hash character'
                             END
                             AND (
                               procedure.proname <>
                                 'emergeos_pack010_schema_version_v1'
                               OR pg_catalog.pg_get_function_result(
                                    procedure.oid) = 'integer')
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 procedure.prosrc, 'UTF8')), 'hex') =
                               CASE procedure.proname
                               WHEN 'agent_graph_read_attributed_failure_resume_v12'
                                 THEN 'e3e6c0b9a14df5c741d5895bc189ef61682b78690a80921066ee7213645e9c9e'
                               WHEN 'agent_graph_claim_attributed_failure_resume_v12'
                                 THEN 'dbaf4bfaf17e0a647d1bf10520d4616ab7b099bfc2d30f2dd561e4a5a41de6fd'
                               WHEN 'agent_graph_complete_claimed_failure_child_v12'
                                 THEN '6600b78c7cd407864b04965b21c9b2559d2134f49236895eaa641b224e4c76f2'
                               WHEN 'agent_graph_complete_claimed_failure_parent_and_seal_v12'
                                 THEN '8ed2cb0c6fec62169c868097decfa9ddf577b1a0316f6e8f0772bb49af88abb0'
                               WHEN 'emergeos_pack010_schema_version_v1'
                                 THEN '1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143'
                               ELSE NULL
                             END
                             AND owner.rolname = CASE
                               WHEN procedure.proname IN (
                                 'agent_graph_complete_claimed_failure_child_v12',
                                 'agent_graph_complete_claimed_failure_parent_and_seal_v12')
                                 THEN 'emergeos_terminal_owner'
                               ELSE 'emergeos_pack010_schema_owner'
                             END
                             AND language.lanname = 'plpgsql'
                             AND procedure.prosecdef
                             AND procedure.provolatile = 'v'
                             AND procedure.proparallel = 'u'
                             AND procedure.prokind = 'f'
                             AND NOT procedure.proisstrict
                             AND NOT procedure.proleakproof
                             AND procedure.proconfig = ARRAY[
                               'search_path=pg_catalog, pg_temp']::text[]
                             AND pg_catalog.has_function_privilege(
                               current_user, procedure.oid, 'EXECUTE')
                             AND NOT pg_catalog.has_function_privilege(
                               current_user, procedure.oid,
                               'EXECUTE WITH GRANT OPTION')
                             AND NOT EXISTS (
                               SELECT 1
                               FROM pg_catalog.aclexplode(COALESCE(
                                 procedure.proacl,
                                 pg_catalog.acldefault(
                                   'f', procedure.proowner))) acl
                               WHERE acl.grantee = 0
                                 AND acl.privilege_type = 'EXECUTE')
                         ) = 5
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class relation
                           JOIN pg_catalog.pg_namespace namespace
                             ON namespace.oid = relation.relnamespace
                           WHERE namespace.nspname = 'public'
                             AND relation.relkind IN (
                               'r', 'p', 'v', 'm', 'S')
                             AND (
                               relation.relowner = role.oid
                               OR pg_catalog.has_table_privilege(
                                 current_user, relation.oid,
                                 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                               OR pg_catalog.has_any_column_privilege(
                                 current_user, relation.oid,
                                 'SELECT,INSERT,UPDATE,REFERENCES')
                               OR (relation.relkind = 'S'
                                   AND pg_catalog.has_sequence_privilege(
                                     current_user, relation.oid,
                                     'USAGE,SELECT,UPDATE'))))
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace namespace
                             ON namespace.oid = procedure.pronamespace
                           WHERE namespace.nspname <> 'public'
                             AND namespace.nspname <> 'pg_catalog'
                             AND namespace.nspname <> 'information_schema'
                             AND namespace.nspname !~ '^pg_toast'
                             AND namespace.nspname !~ '^pg_temp'
                             AND pg_catalog.has_schema_privilege(
                               current_user, namespace.oid, 'USAGE')
                             AND pg_catalog.has_function_privilege(
                               current_user, procedure.oid, 'EXECUTE'))
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace namespace
                             ON namespace.oid = procedure.pronamespace
                           JOIN pg_catalog.pg_roles owner
                             ON owner.oid = procedure.proowner
                           WHERE namespace.nspname = 'public'
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 procedure.prosrc, 'UTF8')), 'hex') =
                               CASE procedure.proname
                               WHEN 'agent_graph_require_failure_resumer_v12'
                                 THEN '814c2d1a58013749d0441f07411e0065c14acc1d9523bc1ea8d8505218573920'
                               WHEN 'agent_graph_assert_failure_terminal_resume_v12'
                                 THEN 'fdad487040c702df9b58481e30158a96e1564963babd86b9d4a0d8e39bceebc3'
                               WHEN 'agent_graph_require_executor_v10'
                                 THEN '794a126edf1b5a0812ac7feab6ea2be0acc8e808608c83b069b41bf4c6fb394e'
                               WHEN 'agent_graph_complete_child_v10'
                                 THEN 'db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae'
                               WHEN 'agent_graph_complete_parent_and_seal_v10'
                                 THEN '57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35'
                               ELSE NULL
                             END
                             AND owner.rolname = CASE
                               WHEN procedure.proname IN (
                                 'agent_graph_complete_child_v10',
                                 'agent_graph_complete_parent_and_seal_v10')
                                 THEN 'emergeos_terminal_owner'
                               ELSE 'emergeos_pack010_schema_owner'
                             END
                         ) = 5
                         AND (
                           SELECT pg_catalog.encode(
                             pg_catalog.sha256(pg_catalog.convert_to(
                               COALESCE(pg_catalog.string_agg(
                                 pg_catalog.concat_ws('|', relation.relname,
                                   trigger.tgname, trigger.tgenabled,
                                   trigger.tgtype::text, trigger.tgattr::text,
                                   COALESCE(pg_catalog.pg_get_expr(
                                     trigger.tgqual, trigger.tgrelid), ''),
                                   pg_catalog.encode(trigger.tgargs, 'hex'),
                                   (trigger.tgconstraint <> 0)::text,
                                   COALESCE(constraint_row.condeferrable,
                                     false)::text,
                                   COALESCE(constraint_row.condeferred,
                                     false)::text,
                                   procedure.proname || '(' ||
                                     pg_catalog.pg_get_function_identity_arguments(
                                       procedure.oid) || ')'),
                                 ',' ORDER BY relation.relname,
                                   trigger.tgname), ''), 'UTF8')), 'hex')
                           FROM pg_catalog.pg_trigger trigger
                           JOIN pg_catalog.pg_class relation
                             ON relation.oid = trigger.tgrelid
                           JOIN pg_catalog.pg_namespace namespace
                             ON namespace.oid = relation.relnamespace
                           JOIN pg_catalog.pg_proc procedure
                             ON procedure.oid = trigger.tgfoid
                           LEFT JOIN pg_catalog.pg_constraint constraint_row
                             ON constraint_row.oid = trigger.tgconstraint
                           WHERE namespace.nspname = 'public'
                             AND NOT trigger.tgisinternal
                         ) = CASE
                           public.emergeos_pack010_schema_version_v1()
                           WHEN 16
                             THEN 'e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b'
                           WHEN 17
                             THEN '6b86652d9d132538940ddac92752cd6a8741cff759b413d98e7e10e948c2779b'
                           ELSE NULL
                         END
                         AS exact
                  FROM pg_catalog.pg_database database
                  JOIN pg_catalog.pg_roles role
                    ON role.rolname = current_user
                  WHERE database.datname = current_database()
                  """)
              .query(
                  (row, ignored) ->
                      new AuthorityProbe(
                          new RuntimeIdentity(
                              row.getString("current_user"),
                              row.getString("current_database"),
                              row.getString("database_oid"),
                              row.getString("current_schema"),
                              row.getString("search_path"),
                              row.getString("server_address"),
                              row.getInt("server_port")),
                          row.getBoolean("exact")))
              .single();
      if (!probe.exact()) {
        throw new GraphAttemptIntegrityException();
      }
      return probe.identity();
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException(failure);
    }
  }

  private record AuthorityProbe(
      RuntimeIdentity identity, boolean exact) {}

  private record RuntimeIdentity(
      String user,
      String database,
      String databaseOid,
      String schema,
      String searchPath,
      String serverAddress,
      int serverPort) {

    private boolean sameDatabase(
        PostgresGraphAttemptStore.AuthorityIdentity other) {
      return database.equals(other.database())
          && databaseOid.equals(other.databaseOid())
          && serverAddress.equals(other.serverAddress())
          && serverPort == other.serverPort();
    }
  }

  public enum State {
    READY,
    CLAIMED,
    CHILD_CONSUMED,
    PARENT_CLAIMED,
    TERMINAL_CONSUMED
  }

  public record DurableFailureCursor(
      String principalId,
      String attemptId,
      String manifestHash,
      String revision,
      String sessionIntentHash,
      Instant sessionExpiresAt,
      int cursorSequence,
      String cursorHeadHash,
      String providerAttribution1Hash,
      String providerAttribution2Hash,
      String requestHash,
      String responseHash,
      String modelResolved,
      GraphAttributedFailureCode failureCode,
      String provenanceHash,
      State state,
      long stateVersion,
      String claimantId,
      String fenceTokenHash,
      Instant claimExpiresAt,
      Long childClaimVersion,
      String childClaimantId,
      String childFenceTokenHash,
      Instant childClaimExpiresAt,
      Integer childSequence,
      String childHeadHash,
      String childTerminalHash,
      Long parentClaimVersion,
      Integer parentSequence,
      String parentHeadHash,
      String parentTerminalHash,
      Integer terminalSequence,
      String terminalHeadHash,
      String sealHash,
      int liveSequence,
      String liveHeadHash) {

    public DurableFailureCursor {
      Objects.requireNonNull(principalId, "principalId");
      Objects.requireNonNull(attemptId, "attemptId");
      Objects.requireNonNull(manifestHash, "manifestHash");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(sessionIntentHash, "sessionIntentHash");
      Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
      Objects.requireNonNull(cursorHeadHash, "cursorHeadHash");
      Objects.requireNonNull(providerAttribution1Hash, "providerAttribution1Hash");
      Objects.requireNonNull(providerAttribution2Hash, "providerAttribution2Hash");
      Objects.requireNonNull(requestHash, "requestHash");
      Objects.requireNonNull(responseHash, "responseHash");
      Objects.requireNonNull(modelResolved, "modelResolved");
      Objects.requireNonNull(failureCode, "failureCode");
      Objects.requireNonNull(provenanceHash, "provenanceHash");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(liveHeadHash, "liveHeadHash");
      if (cursorSequence != 14
          || stateVersion < 1
          || stateVersion > 9_007_199_254_740_991L) {
        throw new IllegalArgumentException("stateVersion is invalid");
      }
      switch (state) {
        case READY -> {
          requirePrefixHead(
              cursorSequence, cursorHeadHash, liveSequence, liveHeadHash);
          requireNoClaim(claimantId, fenceTokenHash, claimExpiresAt);
          requireNoReceipt(
              childClaimVersion,
              childClaimantId,
              childFenceTokenHash,
              childClaimExpiresAt,
              childSequence,
              childHeadHash,
              childTerminalHash,
              parentClaimVersion,
              parentSequence,
              parentHeadHash,
              parentTerminalHash,
              terminalSequence,
              terminalHeadHash,
              sealHash);
          if (stateVersion != 1) {
            throw new IllegalArgumentException("ready version is invalid");
          }
        }
        case CLAIMED -> {
          requirePrefixHead(
              cursorSequence, cursorHeadHash, liveSequence, liveHeadHash);
          requireClaim(claimantId, fenceTokenHash, claimExpiresAt);
          requireNoReceipt(
              childClaimVersion,
              childClaimantId,
              childFenceTokenHash,
              childClaimExpiresAt,
              childSequence,
              childHeadHash,
              childTerminalHash,
              parentClaimVersion,
              parentSequence,
              parentHeadHash,
              parentTerminalHash,
              terminalSequence,
              terminalHeadHash,
              sealHash);
          if (stateVersion < 2) {
            throw new IllegalArgumentException("claim version is invalid");
          }
        }
        case CHILD_CONSUMED -> {
          requireChildReceipt(
              childClaimVersion,
              childClaimantId,
              childFenceTokenHash,
              childClaimExpiresAt,
              childSequence,
              childHeadHash,
              childTerminalHash);
          requireNoClaim(claimantId, fenceTokenHash, claimExpiresAt);
          if (stateVersion != childClaimVersion + 1
              || liveSequence != 15
              || childSequence != 15
              || !liveHeadHash.equals(childHeadHash)
              || parentClaimVersion != null
              || parentSequence != null
              || parentHeadHash != null
              || parentTerminalHash != null
              || terminalSequence != null
              || terminalHeadHash != null
              || sealHash != null) {
            throw new IllegalArgumentException(
                "child terminal receipt is invalid");
          }
        }
        case PARENT_CLAIMED -> {
          requireChildReceipt(
              childClaimVersion,
              childClaimantId,
              childFenceTokenHash,
              childClaimExpiresAt,
              childSequence,
              childHeadHash,
              childTerminalHash);
          requireClaim(claimantId, fenceTokenHash, claimExpiresAt);
          if (liveSequence != 15
              || childSequence != 15
              || !liveHeadHash.equals(childHeadHash)
              || parentClaimVersion == null
              || parentClaimVersion != stateVersion
              || parentClaimVersion <= childClaimVersion + 1
              || parentSequence != null
              || parentHeadHash != null
              || parentTerminalHash != null
              || terminalSequence != null
              || terminalHeadHash != null
              || sealHash != null) {
            throw new IllegalArgumentException(
                "parent claim receipt is invalid");
          }
        }
        case TERMINAL_CONSUMED -> {
          requireChildReceipt(
              childClaimVersion,
              childClaimantId,
              childFenceTokenHash,
              childClaimExpiresAt,
              childSequence,
              childHeadHash,
              childTerminalHash);
          requireClaim(claimantId, fenceTokenHash, claimExpiresAt);
          if (parentClaimVersion == null
              || stateVersion != parentClaimVersion + 1
              || parentSequence == null
              || parentSequence != 16
              || parentHeadHash == null
              || parentTerminalHash == null
              || terminalSequence == null
              || terminalSequence != 17
              || terminalHeadHash == null
              || sealHash == null
              || liveSequence != 17
              || !liveHeadHash.equals(terminalHeadHash)) {
            throw new IllegalArgumentException(
                "terminal consumption receipt is invalid");
          }
        }
      }
    }

    private static void requirePrefixHead(
        int cursorSequence,
        String cursorHeadHash,
        int liveSequence,
        String liveHeadHash) {
      if (liveSequence != 14 || !cursorHeadHash.equals(liveHeadHash)) {
        throw new IllegalArgumentException(
            "durable attributed failure cursor is stale: cursor="
                + cursorSequence
                + "/"
                + cursorHeadHash
                + ", live="
                + liveSequence
                + "/"
                + liveHeadHash);
      }
    }

    private static void requireClaim(
        String claimantId,
        String fenceTokenHash,
        Instant claimExpiresAt) {
      if (claimantId == null
          || claimantId.isBlank()
          || fenceTokenHash == null
          || !fenceTokenHash.matches("[a-f0-9]{64}")
          || claimExpiresAt == null) {
        throw new IllegalArgumentException("durable claim is invalid");
      }
    }

    private static void requireNoClaim(
        String claimantId,
        String fenceTokenHash,
        Instant claimExpiresAt) {
      if (claimantId != null
          || fenceTokenHash != null
          || claimExpiresAt != null) {
        throw new IllegalArgumentException("unexpected durable claim");
      }
    }

    private static void requireChildReceipt(
        Long childClaimVersion,
        String childClaimantId,
        String childFenceTokenHash,
        Instant childClaimExpiresAt,
        Integer childSequence,
        String childHeadHash,
        String childTerminalHash) {
      if (childClaimVersion == null
          || childClaimVersion < 2
          || childClaimantId == null
          || childClaimantId.isBlank()
          || childFenceTokenHash == null
          || !childFenceTokenHash.matches("[a-f0-9]{64}")
          || childClaimExpiresAt == null
          || childSequence == null
          || childHeadHash == null
          || childTerminalHash == null) {
        throw new IllegalArgumentException(
            "durable child receipt is invalid");
      }
    }

    private static void requireNoReceipt(
        Long childClaimVersion,
        String childClaimantId,
        String childFenceTokenHash,
        Instant childClaimExpiresAt,
        Integer childSequence,
        String childHeadHash,
        String childTerminalHash,
        Long parentClaimVersion,
        Integer parentSequence,
        String parentHeadHash,
        String parentTerminalHash,
        Integer terminalSequence,
        String terminalHeadHash,
        String sealHash) {
      if (childClaimVersion != null
          || childClaimantId != null
          || childFenceTokenHash != null
          || childClaimExpiresAt != null
          || childSequence != null
          || childHeadHash != null
          || childTerminalHash != null
          || parentClaimVersion != null
          || parentSequence != null
          || parentHeadHash != null
          || parentTerminalHash != null
          || terminalSequence != null
          || terminalHeadHash != null
          || sealHash != null) {
        throw new IllegalArgumentException(
            "unexpected durable terminal receipt");
      }
    }
  }
}
