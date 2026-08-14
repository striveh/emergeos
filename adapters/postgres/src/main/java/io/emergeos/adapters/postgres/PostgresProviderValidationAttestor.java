package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphPricingSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationChallenge;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import io.emergeos.core.domain.GraphProviderValidationTranscript;
import io.emergeos.core.port.GraphProviderValidationAttestor;
import io.emergeos.core.port.GraphProviderValidationSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * PostgreSQL-authenticated V13 validation authority.
 *
 * <p>The database role and this reviewed verifier are both inside the V13
 * experiment's TCB. PostgreSQL does not perform Ed25519 verification.
 */
public final class PostgresProviderValidationAttestor
    implements GraphProviderValidationAttestor {

  enum ProbePoint {
    AFTER_PROVIDER_VALIDATION_STAGED,
    AFTER_SIGNATURE_VERIFIED,
    AFTER_PROVIDER_VALIDATION_COMMITTED
  }

  @FunctionalInterface
  interface Probe {
    void hit(ProbePoint point);
  }

  private static final JsonMapper JSON = JsonMapper.shared();
  private static final Probe NOOP = ignored -> {};

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Probe probe;
  private final RuntimeIdentity frozen;

  public static PostgresProviderValidationAttestor open(
      DataSource dataSource) {
    return new PostgresProviderValidationAttestor(dataSource, NOOP);
  }

  PostgresProviderValidationAttestor(
      DataSource dataSource, Probe probe) {
    DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = JdbcClient.create(checked);
    this.probe = Objects.requireNonNull(probe, "probe");
    DataSourceTransactionManager manager =
        new DataSourceTransactionManager(checked);
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(
        TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.transactions.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.frozen = executeVerified(this::requireAuthority);
    if (!frozen.equals(executeVerified(this::requireAuthority))) {
      throw new GraphAttemptIntegrityException();
    }
  }

  @Override
  public String requireValidation(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expectedPolicyCursor,
      String keyId,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      Duration challengeTtl) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expectedPolicyCursor, "expectedPolicyCursor");
    Objects.requireNonNull(challengeTtl, "challengeTtl");
    if (!manifest.principalId().equals(
            expectedPolicyCursor.principalId())
        || !manifest.attemptId().equals(
            expectedPolicyCursor.attemptId())
        || !manifest.manifestHash().equals(
            expectedPolicyCursor.manifestHash())
        || expectedPolicyCursor.lastSequence() != 7
        || expectedPolicyCursor.phase() != GraphAttemptPhase.EGRESS_CONSUMED
        || keyId == null
        || !keyId.matches("[a-z][a-z0-9._-]{0,99}")
        || !hash(transportProfileHash)
        || !hash(parserProfileHash)
        || !hash(schemaProfileHash)
        || challengeTtl.compareTo(Duration.ofMillis(100)) < 0
        || challengeTtl.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException(
          "provider validation policy is invalid");
    }
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                String policyHash =
                    jdbc.sql(
                            """
                            SELECT public.agent_graph_require_provider_validation_v13(
                              :principalId, :attemptId, :manifestHash, :keyId,
                              :transportProfileHash, :parserProfileHash,
                              :schemaProfileHash, :challengeTtlMillis)
                            """)
                        .param("principalId", manifest.principalId())
                        .param("attemptId", manifest.attemptId())
                        .param("manifestHash", manifest.manifestHash())
                        .param("keyId", keyId)
                        .param(
                            "transportProfileHash", transportProfileHash)
                        .param("parserProfileHash", parserProfileHash)
                        .param("schemaProfileHash", schemaProfileHash)
                        .param(
                            "challengeTtlMillis",
                            Math.toIntExact(challengeTtl.toMillis()))
                        .query(String.class)
                        .single();
                if (!hash(policyHash)) {
                  throw new GraphAttemptIntegrityException();
                }
                return policyHash;
              }),
          "provider validation policy hash");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw mappedFailure(failure);
    }
  }

  @Override
  public GraphAttemptCursor completeValidation(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderValidationStatement statement,
      GraphProviderValidationSigner signer) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(signer, "signer");
    if (!manifest.principalId().equals(expected.principalId())
        || !manifest.attemptId().equals(expected.attemptId())
        || !manifest.manifestHash().equals(expected.manifestHash())
        || expected.lastSequence() != 13
        || expected.phase() != GraphAttemptPhase.PROVIDER_PENDING
        || !manifest.manifestHash().equals(
            statement.executionBindingHash())
        || !manifest.childActor().equals(
            statement.attribution().providerActor())
        || !manifest.pricingProfileFingerprint().equals(
            statement.attribution().pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider validation completion is invalid");
    }
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                StageReceipt stage = stage(manifest, expected, statement);
                GraphProviderValidationChallenge challenge =
                    stage.challenge();
                GraphProviderValidationTranscript transcript =
                    GraphProviderValidationTranscript.create(
                        challenge,
                        expected,
                        statement.attribution(),
                        statement.executionBindingHash(),
                        statement.transportProfileHash(),
                        statement.parserProfileHash(),
                        statement.schemaProfileHash(),
                        statement.decision(),
                        statement.decisionHash(),
                        statement.failureCode());
                if (!transcript.transcriptHash().equals(
                    stage.transcriptHash())) {
                  throw new GraphAttemptIntegrityException();
                }
                probe.hit(
                    ProbePoint.AFTER_PROVIDER_VALIDATION_STAGED);
                GraphProviderValidationAttestation attestation =
                    Objects.requireNonNull(
                        signer.attest(transcript),
                        "provider validation attestation");
                if (!transcript.equals(attestation.transcript())
                    || !attestation.verifiesWith(stage.publicKeyDer())) {
                  throw new GraphAttemptIntegrityException();
                }
                probe.hit(ProbePoint.AFTER_SIGNATURE_VERIFIED);
                GraphAttemptEvent event =
                    GraphAttemptEvent.providerAttributed(
                        expected,
                        manifest.childSelection(),
                        statement.attribution(),
                        stage.challenge().issuedAt());
                CommitReceipt committed =
                    commit(
                        manifest,
                        challenge.policyHash(),
                        transcript.transcriptHash(),
                        attestation.signatureHex(),
                        event);
                GraphAttemptCursor cursor = event.cursor(manifest);
                if (committed.sequence() != 14
                    || !committed.headHash().equals(cursor.headHash())
                    || !committed.transcriptHash().equals(
                        transcript.transcriptHash())
                    || !committed.decisionKind().equals(
                        statement.decision().name())) {
                  throw new GraphAttemptIntegrityException();
                }
                probe.hit(
                    ProbePoint.AFTER_PROVIDER_VALIDATION_COMMITTED);
                return cursor;
              }),
          "provider validation cursor");
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw mappedFailure(failure);
    }
  }

  private StageReceipt stage(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderValidationStatement statement) {
    String payload = stagePayload(manifest, expected, statement);
    return jdbc.sql(
            """
            SELECT *
            FROM public.agent_graph_stage_provider_validation_v13(
              CAST(:payload AS jsonb))
            """)
        .param("payload", payload)
        .query(
            (row, ignored) -> {
              GraphProviderValidationChallenge challenge =
                  new GraphProviderValidationChallenge(
                      row.getString("protocol_version"),
                      row.getString("database_name"),
                      row.getLong("database_oid"),
                      row.getLong("schema_oid"),
                      row.getLong("attestor_role_oid"),
                      row.getString("principal_id"),
                      row.getString("attempt_id"),
                      row.getString("manifest_hash"),
                      row.getString("revision"),
                      row.getString("session_intent_hash"),
                      row.getObject(
                              "session_expires_at", OffsetDateTime.class)
                          .toInstant(),
                      row.getInt("policy_sequence"),
                      row.getString("policy_head_hash"),
                      row.getString("key_id"),
                      row.getString("key_fingerprint"),
                      row.getString("transport_profile_hash"),
                      row.getString("parser_profile_hash"),
                      row.getString("schema_profile_hash"),
                      row.getObject("validation_nonce", UUID.class),
                      row.getObject("issued_at", OffsetDateTime.class)
                          .toInstant(),
                      row.getObject("expires_at", OffsetDateTime.class)
                          .toInstant(),
                      row.getString("policy_hash"),
                      row.getString("challenge_hash"));
              return new StageReceipt(
                  challenge,
                  row.getString("transcript_hash"),
                  row.getBytes("public_key_der"));
            })
        .single();
  }

  private CommitReceipt commit(
      GraphAttemptManifest manifest,
      String policyHash,
      String transcriptHash,
      String signatureHex,
      GraphAttemptEvent event) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("principal_id", manifest.principalId());
    payload.put("attempt_id", manifest.attemptId());
    payload.put("manifest_hash", manifest.manifestHash());
    payload.put("policy_hash", policyHash);
    payload.put("transcript_hash", transcriptHash);
    payload.put("signature_hex", signatureHex);
    payload.put("event_hash", event.eventHash());
    payload.put("cursor_head_hash", event.currentHeadHash());
    return jdbc.sql(
            """
            SELECT (result ->> 'sequence')::integer AS sequence,
                   result ->> 'head_hash' AS head_hash,
                   result ->> 'transcript_hash' AS transcript_hash,
                   result ->> 'decision_kind' AS decision_kind,
                   result ->> 'validation_state' AS validation_state,
                   result ->> 'failure_provenance_hash'
                     AS failure_provenance_hash
            FROM (
              SELECT public.agent_graph_commit_provider_validation_v13(
                CAST(:payload AS jsonb)) AS result
            ) committed
            """)
        .param("payload", serialize(payload))
        .query(
            (row, ignored) ->
                new CommitReceipt(
                    row.getInt("sequence"),
                    row.getString("head_hash"),
                    row.getString("transcript_hash"),
                    row.getString("decision_kind"),
                    row.getString("validation_state"),
                    row.getString("failure_provenance_hash")))
        .single();
  }

  private static String stagePayload(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderValidationStatement statement) {
    GraphProviderAttribution attribution = statement.attribution();
    GraphPricingSnapshot pricing = attribution.pricing();
    ObjectNode payload = JSON.createObjectNode();
    payload.put("principal_id", manifest.principalId());
    payload.put("attempt_id", manifest.attemptId());
    payload.put("manifest_hash", manifest.manifestHash());
    payload.put(
        "execution_binding_hash", statement.executionBindingHash());
    payload.put("expected_sequence", expected.lastSequence());
    payload.put("expected_head_hash", expected.headHash());
    payload.put("request_ordinal", attribution.requestOrdinal());
    payload.put("request_hash", attribution.requestHash());
    payload.put("response_hash", attribution.responseHash());
    payload.put("attribution_hash", attribution.attributionHash());
    payload.put("provider_actor", attribution.providerActor());
    payload.put("model_requested", attribution.modelRequested());
    payload.put("model_resolved", attribution.modelResolved());
    payload.put("pricing_profile_id", pricing.id());
    payload.put("pricing_provider", pricing.provider());
    payload.put(
        "pricing_profile_fingerprint", pricing.fingerprint());
    payload.put(
        "uncached_input_nano_usd_per_token",
        pricing.uncachedInputNanoUsdPerToken());
    payload.put(
        "cached_input_nano_usd_per_token",
        pricing.cachedInputNanoUsdPerToken());
    payload.put(
        "output_nano_usd_per_token",
        pricing.outputNanoUsdPerToken());
    payload.put("input_tokens", attribution.inputTokens());
    payload.put(
        "cached_input_tokens", attribution.cachedInputTokens());
    payload.put("output_tokens", attribution.outputTokens());
    payload.put(
        "reasoning_output_tokens",
        attribution.reasoningOutputTokens());
    payload.put("total_tokens", attribution.totalTokens());
    payload.put("observed_cost_usd", attribution.observedCostUsd());
    payload.put(
        "transport_profile_hash", statement.transportProfileHash());
    payload.put("parser_profile_hash", statement.parserProfileHash());
    payload.put("schema_profile_hash", statement.schemaProfileHash());
    payload.put("decision_kind", statement.decision().name());
    payload.put("decision_hash", statement.decisionHash());
    if (statement.failureCode() == null) {
      payload.putNull("failure_code");
    } else {
      payload.put("failure_code", statement.failureCode().name());
    }
    return serialize(payload);
  }

  private <T> T executeVerified(java.util.function.Supplier<T> action) {
    try {
      return Objects.requireNonNull(
          transactions.execute(ignored -> action.get()),
          "provider validation transaction result");
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private void requireFrozenAuthority() {
    if (!frozen.equals(requireAuthority())) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private RuntimeIdentity requireAuthority() {
    try {
      AuthorityProbe authority =
          jdbc.sql(
                  """
                  SELECT session_user,
                         current_user,
                         current_database(),
                         database.oid::text AS database_oid,
                         namespace.oid::text AS schema_oid,
                         role.oid::text AS role_oid,
                         COALESCE(pg_catalog.inet_server_addr()::text,
                                  'local-socket') AS server_address,
                         COALESCE(pg_catalog.inet_server_port(), -1)
                           AS server_port,
                         session_user = current_user
                         AND current_user = 'emergeos_provider_attestor'
                         AND role.rolcanlogin
                         AND NOT role.rolinherit
                         AND NOT role.rolsuper
                         AND NOT role.rolcreatedb
                         AND NOT role.rolcreaterole
                         AND NOT role.rolreplication
                         AND NOT role.rolbypassrls
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
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_auth_members membership
                           WHERE membership.member = role.oid
                              OR membership.roleid = role.oid)
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_roles owner_role
                           WHERE owner_role.rolname IN (
                             'emergeos_pack010_schema_owner',
                             'emergeos_terminal_owner')
                             AND NOT owner_role.rolcanlogin
                             AND NOT owner_role.rolinherit
                             AND NOT owner_role.rolsuper
                             AND NOT owner_role.rolcreatedb
                             AND NOT owner_role.rolcreaterole
                             AND NOT owner_role.rolreplication
                             AND NOT owner_role.rolbypassrls
                         ) = 2
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_roles prerequisite_role
                           WHERE prerequisite_role.rolname =
                               'emergeos_exact_overlay_reader_v19'
                             AND prerequisite_role.rolcanlogin
                             AND NOT prerequisite_role.rolinherit
                             AND NOT prerequisite_role.rolsuper
                             AND NOT prerequisite_role.rolcreatedb
                             AND NOT prerequisite_role.rolcreaterole
                             AND NOT prerequisite_role.rolreplication
                             AND NOT prerequisite_role.rolbypassrls
                             AND NOT EXISTS (
                               SELECT 1
                               FROM pg_catalog.pg_auth_members membership
                               WHERE membership.member =
                                       prerequisite_role.oid
                                  OR membership.roleid =
                                       prerequisite_role.oid)
                         ) = 1
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace function_namespace
                             ON function_namespace.oid = procedure.pronamespace
                           WHERE function_namespace.nspname = 'public'
                             AND pg_catalog.has_function_privilege(
                               current_user, procedure.oid, 'EXECUTE')
                         ) = 4
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace function_namespace
                             ON function_namespace.oid = procedure.pronamespace
                           JOIN pg_catalog.pg_roles function_owner
                             ON function_owner.oid = procedure.proowner
                           JOIN pg_catalog.pg_language language
                             ON language.oid = procedure.prolang
                           WHERE function_namespace.nspname = 'public'
                             AND procedure.proname IN (
                               'agent_graph_framed_sha256_v13',
                               'agent_graph_require_provider_validation_v13',
                               'agent_graph_stage_provider_validation_v13',
                               'agent_graph_commit_provider_validation_v13',
                               'agent_graph_assert_provider_validation_v13',
                               'emergeos_pack010_schema_version_v1')
                             AND pg_catalog.pg_get_function_identity_arguments(
                               procedure.oid) = CASE procedure.proname
                               WHEN 'agent_graph_framed_sha256_v13'
                                 THEN 'hash_domain character varying, hash_fields text[]'
                               WHEN 'agent_graph_require_provider_validation_v13'
                                 THEN 'checked_principal character varying, checked_attempt character, checked_manifest character, checked_key_id character varying, checked_transport_profile character, checked_parser_profile character, checked_schema_profile character, checked_challenge_ttl_millis integer'
                               WHEN 'agent_graph_stage_provider_validation_v13'
                                 THEN 'payload jsonb'
                               WHEN 'agent_graph_commit_provider_validation_v13'
                                 THEN 'payload jsonb'
                               WHEN 'agent_graph_assert_provider_validation_v13'
                                 THEN ''
                               WHEN 'emergeos_pack010_schema_version_v1'
                                 THEN ''
                               ELSE NULL
                             END
                             AND pg_catalog.pg_get_function_result(
                               procedure.oid) = CASE procedure.proname
                               WHEN 'agent_graph_framed_sha256_v13'
                                 THEN 'character'
                               WHEN 'agent_graph_require_provider_validation_v13'
                                 THEN 'character'
                               WHEN 'agent_graph_stage_provider_validation_v13'
                                 THEN 'TABLE(protocol_version character varying, database_name character varying, database_oid oid, schema_oid oid, attestor_role_oid oid, principal_id character varying, attempt_id character, manifest_hash character, revision character varying, session_intent_hash character, session_expires_at timestamp with time zone, policy_sequence integer, policy_head_hash character, key_id character varying, key_fingerprint character, transport_profile_hash character, parser_profile_hash character, schema_profile_hash character, validation_nonce uuid, issued_at timestamp with time zone, expires_at timestamp with time zone, policy_hash character, challenge_hash character, transcript_hash character, public_key_der bytea)'
                               WHEN 'agent_graph_commit_provider_validation_v13'
                                 THEN 'jsonb'
                               WHEN 'agent_graph_assert_provider_validation_v13'
                                 THEN 'trigger'
                               WHEN 'emergeos_pack010_schema_version_v1'
                                 THEN 'integer'
                               ELSE NULL
                             END
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 procedure.prosrc, 'UTF8')), 'hex') =
                               CASE procedure.proname
                               WHEN 'agent_graph_framed_sha256_v13'
                                 THEN '864d95ca17378cd9cdd1beba355a846900640be21cbff97a0b9894c7c99ba2fd'
                               WHEN 'agent_graph_require_provider_validation_v13'
                                 THEN 'befee76a15fd634d8fc1b49838df8641dc68b9ea25535b45a1e54a2419ac1c32'
                               WHEN 'agent_graph_stage_provider_validation_v13'
                                 THEN '703349b8401a6b70f99df436fe425d5d1641e6552f4875ef8e9e7a084163d3d7'
                               WHEN 'agent_graph_commit_provider_validation_v13'
                                 THEN '2e2de1a391d9d069763927356683bb3d2ce1db52aa9a39496bb388fdc57a71e4'
                               WHEN 'agent_graph_assert_provider_validation_v13'
                                 THEN '49b95c2e4cb9f9de7de818bbe8bcaae196ade855ab179d38a8657dc0ca63521a'
                               WHEN 'emergeos_pack010_schema_version_v1'
                                 THEN '1a3bc853fa25e739491b1862478046d052686931d4a36a4683c0faad6e331143'
                               ELSE NULL
                             END
                             AND procedure.prosecdef =
                               (procedure.proname <>
                                 'agent_graph_framed_sha256_v13')
                             AND procedure.provolatile =
                               CASE procedure.proname
                               WHEN 'agent_graph_framed_sha256_v13'
                                 THEN 'i'::"char"
                               ELSE 'v'::"char"
                               END
                             AND procedure.proparallel = 'u'
                             AND procedure.prokind = 'f'
                             AND procedure.proretset =
                               (procedure.proname =
                                 'agent_graph_stage_provider_validation_v13')
                             AND procedure.proisstrict =
                               (procedure.proname =
                                 'agent_graph_framed_sha256_v13')
                             AND NOT procedure.proleakproof
                             AND procedure.proconfig = ARRAY[
                               'search_path=pg_catalog, pg_temp']::text[]
                             AND language.lanname = 'plpgsql'
                             AND function_owner.rolname =
                               CASE WHEN procedure.proname IN (
                                 'agent_graph_require_provider_validation_v13',
                                 'agent_graph_stage_provider_validation_v13',
                                 'agent_graph_commit_provider_validation_v13')
                               THEN 'emergeos_terminal_owner'
                               ELSE 'emergeos_pack010_schema_owner'
                               END
                             AND pg_catalog.has_function_privilege(
                               current_user, procedure.oid, 'EXECUTE') =
                               (procedure.proname IN (
                                 'agent_graph_require_provider_validation_v13',
                                 'agent_graph_stage_provider_validation_v13',
                                 'agent_graph_commit_provider_validation_v13',
                                 'emergeos_pack010_schema_version_v1'))
                             AND NOT pg_catalog.has_function_privilege(
                               current_user, procedure.oid,
                               'EXECUTE WITH GRANT OPTION')
                         ) = 6
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace function_namespace
                             ON function_namespace.oid = procedure.pronamespace
                           WHERE function_namespace.nspname = 'public'
                             AND procedure.proname IN (
                               'agent_graph_framed_sha256_v13',
                               'agent_graph_require_provider_validation_v13',
                               'agent_graph_stage_provider_validation_v13',
                               'agent_graph_commit_provider_validation_v13',
                               'agent_graph_assert_provider_validation_v13',
                               'emergeos_pack010_schema_version_v1')
                         ) = 6
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_proc procedure
                           JOIN pg_catalog.pg_namespace function_namespace
                             ON function_namespace.oid = procedure.pronamespace
                           CROSS JOIN LATERAL pg_catalog.aclexplode(
                             COALESCE(procedure.proacl,
                               pg_catalog.acldefault(
                                 'f', procedure.proowner))) acl
                           LEFT JOIN pg_catalog.pg_roles grantee
                             ON grantee.oid = acl.grantee
                           WHERE function_namespace.nspname = 'public'
                             AND procedure.proname IN (
                               'agent_graph_framed_sha256_v13',
                               'agent_graph_require_provider_validation_v13',
                               'agent_graph_stage_provider_validation_v13',
                               'agent_graph_commit_provider_validation_v13',
                               'agent_graph_assert_provider_validation_v13',
                               'emergeos_pack010_schema_version_v1')
                             AND acl.grantee <> procedure.proowner
                             AND (
                               grantee.rolname IS NULL
                               OR NOT (
                                 acl.privilege_type = 'EXECUTE'
                                 AND NOT acl.is_grantable
                                 AND (
                                   (grantee.rolname =
                                    'emergeos_provider_attestor'
                                  AND procedure.proname IN (
                                    'agent_graph_require_provider_validation_v13',
                                    'agent_graph_stage_provider_validation_v13',
                                    'agent_graph_commit_provider_validation_v13'))
                                 OR (grantee.rolname =
                                       'emergeos_terminal_owner'
                                     AND procedure.proname =
                                       'agent_graph_framed_sha256_v13')
                                 OR (procedure.proname =
                                       'emergeos_pack010_schema_version_v1'
                                     AND grantee.rolname IN (
                                       'emergeos_terminal_owner',
                                       'emergeos_graph_executor',
                                       'emergeos_failure_resumer',
                                       'emergeos_provider_attestor'))))))
                         AND (
                           SELECT count(*) = 67
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 pg_catalog.string_agg(
                                   pg_catalog.concat_ws('|',
                                     column_shape.relname,
                                     column_shape.attnum::text,
                                     column_shape.attname,
                                     column_shape.type_name,
                                     column_shape.attnotnull::text,
                                     column_shape.attidentity::text,
                                     column_shape.attgenerated::text,
                                     column_shape.default_expr,
                                     column_shape.collation_name),
                                   ',' ORDER BY column_shape.relname,
                                     column_shape.attnum),
                                 'UTF8')), 'hex') =
                               '9d85e8dc5f87993b545ee3a519d5c3f8870777e1ad7be7141c9a8181beb2a7f5'
                           FROM (
                             SELECT relation.relname,
                                    attribute.attnum,
                                    attribute.attname,
                                    pg_catalog.format_type(
                                      attribute.atttypid,
                                      attribute.atttypmod) AS type_name,
                                    attribute.attnotnull,
                                    attribute.attidentity,
                                    attribute.attgenerated,
                                    COALESCE(pg_catalog.pg_get_expr(
                                      default_value.adbin,
                                      default_value.adrelid), '')
                                      AS default_expr,
                                    COALESCE(collation_row.collname, '')
                                      AS collation_name
                             FROM pg_catalog.pg_attribute attribute
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = attribute.attrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid = relation.relnamespace
                             LEFT JOIN pg_catalog.pg_attrdef default_value
                               ON default_value.adrelid = attribute.attrelid
                              AND default_value.adnum = attribute.attnum
                             LEFT JOIN pg_catalog.pg_collation collation_row
                               ON collation_row.oid = attribute.attcollation
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations')
                               AND attribute.attnum > 0
                               AND NOT attribute.attisdropped
                           ) column_shape
                         )
                         AND (
                           SELECT count(*) = 46
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 pg_catalog.string_agg(
                                   pg_catalog.concat_ws('|',
                                     constraint_shape.relname,
                                     constraint_shape.conname,
                                     constraint_shape.contype::text,
                                     constraint_shape.condeferrable::text,
                                     constraint_shape.condeferred::text,
                                     constraint_shape.convalidated::text,
                                     constraint_shape.definition),
                                   ',' ORDER BY constraint_shape.relname,
                                     constraint_shape.conname),
                                 'UTF8')), 'hex') =
                               '44d64677439d87d9302d6589d94d58bc5fc20cdeb45ba29d8684b63510b5b097'
                           FROM (
                             SELECT relation.relname,
                                    constraint_row.conname,
                                    constraint_row.contype,
                                    constraint_row.condeferrable,
                                    constraint_row.condeferred,
                                    constraint_row.convalidated,
                                    pg_catalog.pg_get_constraintdef(
                                      constraint_row.oid, true) AS definition
                             FROM pg_catalog.pg_constraint constraint_row
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = constraint_row.conrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid = relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations')
                           ) constraint_shape
                         )
                         AND (
                           SELECT count(*) = 7
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 pg_catalog.string_agg(
                                   pg_catalog.concat_ws('|',
                                     index_shape.relname,
                                     index_shape.index_name,
                                     index_shape.indisunique::text,
                                     index_shape.indisprimary::text,
                                     index_shape.indisvalid::text,
                                     index_shape.indisready::text,
                                     index_shape.definition),
                                   ',' ORDER BY index_shape.relname,
                                     index_shape.index_name),
                                 'UTF8')), 'hex') =
                               'a6c0ee2f58fa42aa650efb435c9333dca54bab602a66762185bc276110020d32'
                           FROM (
                             SELECT relation.relname,
                                    index_relation.relname AS index_name,
                                    index_row.indisunique,
                                    index_row.indisprimary,
                                    index_row.indisvalid,
                                    index_row.indisready,
                                    pg_catalog.pg_get_indexdef(
                                      index_relation.oid) AS definition
                             FROM pg_catalog.pg_index index_row
                             JOIN pg_catalog.pg_class index_relation
                               ON index_relation.oid = index_row.indexrelid
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = index_row.indrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid = relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations')
                           ) index_shape
                         )
                         AND (
                           SELECT count(*) = 2
                             AND pg_catalog.encode(
                               pg_catalog.sha256(pg_catalog.convert_to(
                                 pg_catalog.string_agg(
                                   pg_catalog.concat_ws('|',
                                     relation.relname,
                                     relation.relkind::text,
                                     relation.relpersistence::text,
                                     relation.relrowsecurity::text,
                                     relation.relforcerowsecurity::text,
                                     relation.relreplident::text),
                                   ',' ORDER BY relation.relname),
                                 'UTF8')), 'hex') =
                               '9617d517077b727838127d47e3a95d2e93fe9ee2240eff18d00eb847b1e6f70a'
                             AND count(*) FILTER (
                               WHERE relation_owner.rolname =
                                 'emergeos_pack010_schema_owner') = 2
                           FROM pg_catalog.pg_class relation
                           JOIN pg_catalog.pg_namespace relation_namespace
                             ON relation_namespace.oid = relation.relnamespace
                           JOIN pg_catalog.pg_roles relation_owner
                             ON relation_owner.oid = relation.relowner
                           WHERE relation_namespace.nspname = 'public'
                             AND relation.relname IN (
                               'agent_graph_provider_validation_keys',
                               'agent_graph_provider_validations')
                         )
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class relation
                           JOIN pg_catalog.pg_namespace relation_namespace
                             ON relation_namespace.oid = relation.relnamespace
                           CROSS JOIN LATERAL pg_catalog.aclexplode(
                             COALESCE(relation.relacl,
                               pg_catalog.acldefault(
                                 'r', relation.relowner))) acl
                           LEFT JOIN pg_catalog.pg_roles grantee
                             ON grantee.oid = acl.grantee
                           WHERE relation_namespace.nspname = 'public'
                             AND relation.relname IN (
                               'agent_graph_provider_validation_keys',
                               'agent_graph_provider_validations')
                             AND acl.grantee <> relation.relowner
                             AND NOT (
                               NOT acl.is_grantable
                               AND (
                                 (grantee.rolname =
                                    'emergeos_terminal_owner'
                                  AND (acl.privilege_type = 'SELECT'
                                    OR (relation.relname =
                                          'agent_graph_provider_validations'
                                        AND acl.privilege_type IN (
                                          'INSERT', 'UPDATE'))))
                                 OR
                                 (grantee.rolname =
                                    'emergeos_exact_overlay_reader_v19'
                                  AND acl.privilege_type = 'SELECT')))
                         )
                         AND (
                           SELECT pg_catalog.encode(
                             pg_catalog.sha256(pg_catalog.convert_to(
                               COALESCE(pg_catalog.string_agg(
                                 pg_catalog.concat_ws('|',
                                   trigger_relation.relname,
                                   trigger.tgname,
                                   trigger.tgenabled,
                                   trigger.tgtype::text,
                                   trigger.tgattr::text,
                                   COALESCE(pg_catalog.pg_get_expr(
                                     trigger.tgqual,
                                     trigger.tgrelid), ''),
                                   pg_catalog.encode(trigger.tgargs, 'hex'),
                                   (trigger.tgconstraint <> 0)::text,
                                   COALESCE(
                                     constraint_row.condeferrable,
                                     false)::text,
                                   COALESCE(
                                     constraint_row.condeferred,
                                     false)::text,
                                   trigger_function.proname || '('
                                     || pg_catalog.pg_get_function_identity_arguments(
                                       trigger_function.oid) || ')'),
                                 ',' ORDER BY trigger_relation.relname,
                                   trigger.tgname), ''),
                               'UTF8')), 'hex') = CASE
                             public.emergeos_pack010_schema_version_v1()
                               WHEN 16
                                 THEN 'e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b'
                               WHEN 17
                                 THEN '6b86652d9d132538940ddac92752cd6a8741cff759b413d98e7e10e948c2779b'
                               ELSE NULL
                             END
                           FROM pg_catalog.pg_trigger trigger
                           JOIN pg_catalog.pg_class trigger_relation
                             ON trigger_relation.oid = trigger.tgrelid
                           JOIN pg_catalog.pg_namespace trigger_namespace
                             ON trigger_namespace.oid =
                               trigger_relation.relnamespace
                           JOIN pg_catalog.pg_proc trigger_function
                             ON trigger_function.oid = trigger.tgfoid
                           LEFT JOIN pg_catalog.pg_constraint constraint_row
                             ON constraint_row.oid = trigger.tgconstraint
                           WHERE trigger_namespace.nspname = 'public'
                             AND NOT trigger.tgisinternal
                         )
                         AND (
                           SELECT count(*)
                           FROM pg_catalog.pg_trigger trigger
                           JOIN pg_catalog.pg_class trigger_relation
                             ON trigger_relation.oid = trigger.tgrelid
                           JOIN pg_catalog.pg_namespace trigger_namespace
                             ON trigger_namespace.oid =
                               trigger_relation.relnamespace
                           JOIN pg_catalog.pg_proc trigger_function
                             ON trigger_function.oid = trigger.tgfoid
                           JOIN pg_catalog.pg_roles trigger_owner
                             ON trigger_owner.oid =
                               trigger_function.proowner
                           JOIN pg_catalog.pg_constraint constraint_row
                             ON constraint_row.oid = trigger.tgconstraint
                           WHERE trigger_namespace.nspname = 'public'
                             AND NOT trigger.tgisinternal
                             AND trigger.tgname =
                               CASE trigger_relation.relname
                               WHEN 'agent_graph_provider_validations'
                                 THEN 'agent_graph_provider_validation_state_v13'
                               WHEN 'agent_graph_attempt_provider_attributions'
                                 THEN 'agent_graph_provider_validation_attribution_v13'
                               WHEN 'agent_graph_attempt_events'
                                 THEN 'agent_graph_provider_validation_event_v13'
                               WHEN 'agent_graph_attempt_heads'
                                 THEN 'agent_graph_provider_validation_head_v13'
                               WHEN 'agent_graph_attributed_failure_outcomes'
                                 THEN 'agent_graph_provider_validation_failure_v13'
                               ELSE NULL
                               END
                             AND trigger.tgenabled = 'O'
                             AND trigger.tgtype = 29
                             AND trigger.tgqual IS NULL
                             AND trigger.tgnargs = 0
                             AND pg_catalog.octet_length(
                               trigger.tgargs) = 0
                             AND trigger.tgattr =
                               ''::pg_catalog.int2vector
                             AND constraint_row.condeferrable
                             AND constraint_row.condeferred
                             AND trigger_function.proname =
                               'agent_graph_assert_provider_validation_v13'
                             AND pg_catalog.pg_get_function_identity_arguments(
                               trigger_function.oid) = ''
                             AND trigger_owner.rolname =
                               'emergeos_pack010_schema_owner'
                         ) = 5
                         AND NOT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class relation
                           JOIN pg_catalog.pg_namespace relation_namespace
                             ON relation_namespace.oid = relation.relnamespace
                           WHERE relation_namespace.nspname = 'public'
                             AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
                             AND (
                               relation.relowner = role.oid
                               OR pg_catalog.has_table_privilege(
                                 current_user, relation.oid,
                                 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                               OR pg_catalog.has_any_column_privilege(
                                 current_user, relation.oid,
                                 'SELECT,INSERT,UPDATE,REFERENCES')))
                         AS exact
                  FROM pg_catalog.pg_database database
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.nspname = 'public'
                  JOIN pg_catalog.pg_roles role
                    ON role.rolname = current_user
                  WHERE database.datname = current_database()
                  """)
              .query(
                  (row, ignored) ->
                      new AuthorityProbe(
                          new RuntimeIdentity(
                              row.getString("session_user"),
                              row.getString("current_database"),
                              row.getString("database_oid"),
                              row.getString("schema_oid"),
                              row.getString("role_oid"),
                              row.getString("server_address"),
                              row.getInt("server_port")),
                          row.getBoolean("exact")))
              .single();
      if (!authority.exact()) {
        throw new GraphAttemptIntegrityException();
      }
      return authority.identity();
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private static RuntimeException mappedFailure(RuntimeException failure) {
    String state = sqlState(failure);
    if ("55000".equals(state)
        || "40001".equals(state)
        || "23505".equals(state)) {
      return new GraphAttemptConflictException(
          "provider validation was fenced");
    }
    return new GraphAttemptIntegrityException();
  }

  private static String sqlState(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql
          && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
      current = current.getCause();
    }
    return null;
  }

  private static boolean hash(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  private static String serialize(JsonNode value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException(
          "provider validation payload serialization failed");
    }
  }

  private record RuntimeIdentity(
      String role,
      String database,
      String databaseOid,
      String schemaOid,
      String roleOid,
      String serverAddress,
      int serverPort) {}

  private record AuthorityProbe(
      RuntimeIdentity identity, boolean exact) {}

  private record StageReceipt(
      GraphProviderValidationChallenge challenge,
      String transcriptHash,
      byte[] publicKeyDer) {

    private StageReceipt {
      Objects.requireNonNull(challenge, "challenge");
      if (!hash(transcriptHash)
          || publicKeyDer == null
          || publicKeyDer.length < 32
          || publicKeyDer.length > 128
          || !fingerprintMatches(
              publicKeyDer, challenge.keyFingerprint())) {
        throw new GraphAttemptIntegrityException();
      }
      publicKeyDer = publicKeyDer.clone();
    }

    @Override
    public byte[] publicKeyDer() {
      return publicKeyDer.clone();
    }
  }

  private record CommitReceipt(
      int sequence,
      String headHash,
      String transcriptHash,
      String decisionKind,
      String validationState,
      String failureProvenanceHash) {

    private CommitReceipt {
      if (sequence != 14
          || !hash(headHash)
          || !hash(transcriptHash)
          || !("STRUCTURED_FINAL".equals(decisionKind)
              || "FAILED".equals(decisionKind))
          || !"CONSUMED".equals(validationState)
          || ("STRUCTURED_FINAL".equals(decisionKind)
              && failureProvenanceHash != null)
          || ("FAILED".equals(decisionKind)
              && !hash(failureProvenanceHash))) {
        throw new GraphAttemptIntegrityException();
      }
    }
  }

  private static boolean fingerprintMatches(
      byte[] publicKeyDer, String fingerprint) {
    try {
      return MessageDigest.isEqual(
          MessageDigest.getInstance("SHA-256").digest(publicKeyDer),
          HexFormat.of().parseHex(fingerprint));
    } catch (IllegalArgumentException | NoSuchAlgorithmException failure) {
      return false;
    }
  }
}
