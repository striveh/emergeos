package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphExactPicoProviderSignatureVerifier;
import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.port.GraphExactPicoProviderValidationAttestor;
import io.emergeos.core.port.GraphExactPicoProviderValidationSigner;
import java.sql.SQLException;
import java.time.Instant;
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

/** Reviewed typed V16 stage, local verification, and overlay commit adapter. */
public final class PostgresExactPicoProviderValidationAttestor
    implements GraphExactPicoProviderValidationAttestor {

  enum ProbePoint {
    AFTER_STAGE_MAPPED,
    AFTER_SIGNATURE_VERIFIED,
    AFTER_COMMIT_RECEIPT_VERIFIED
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

  public static PostgresExactPicoProviderValidationAttestor open(
      DataSource dataSource) {
    return new PostgresExactPicoProviderValidationAttestor(dataSource, NOOP);
  }

  PostgresExactPicoProviderValidationAttestor(
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
  public GraphExactPicoProviderValidationReceipt complete(
      GraphExactPicoProviderValidationCommand command,
      GraphExactPicoProviderValidationSigner signer) {
    if (command == null || signer == null) {
      throw new IllegalArgumentException(
          "exact pico provider validation input is invalid");
    }
    try {
      return Objects.requireNonNull(
          transactions.execute(
              ignored -> {
                requireFrozenAuthority();
                Stage stage = sqlOperation(() -> stage(command));
                localOperation(
                    () -> probe.hit(ProbePoint.AFTER_STAGE_MAPPED));
                String signature =
                    localOperation(() -> signer.sign(stage.challenge()));
                localOperation(
                    () ->
                        GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                            stage.challenge(),
                            stage.publicKeyDer(),
                            signature));
                localOperation(
                    () -> probe.hit(ProbePoint.AFTER_SIGNATURE_VERIFIED));
                GraphExactPicoProviderValidationReceipt receipt =
                    sqlOperation(
                        () -> commit(stage.challenge(), signature));
                localOperation(
                    () ->
                        probe.hit(
                            ProbePoint.AFTER_COMMIT_RECEIPT_VERIFIED));
                return receipt;
              }),
          "exact pico provider validation receipt");
    } catch (LocalFailure failure) {
      throw new GraphAttemptIntegrityException();
    } catch (GraphAttemptConflictException
        | GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw mappedFailure(failure);
    }
  }

  private static <T> T sqlOperation(
      java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      throw mappedFailure(failure);
    }
  }

  private static <T> T localOperation(
      java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      throw new LocalFailure();
    }
  }

  private static void localOperation(Runnable operation) {
    localOperation(
        () -> {
          operation.run();
          return Boolean.TRUE;
        });
  }

  private Stage stage(GraphExactPicoProviderValidationCommand command) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("principal_id", command.principalId());
    payload.put("attempt_id", command.attemptId());
    payload.put("manifest_hash", command.manifestHash());
    payload.put("requirement_hash", command.requirementHash());
    payload.put("expected_base_sequence", 13);
    payload.put("expected_base_head_hash", command.expectedBaseHeadHash());
    payload.put("request_ordinal", 2);
    payload.put("request_hash", command.requestHash());
    payload.put("response_hash", command.responseHash());
    payload.put("model_resolved_hash", command.modelResolvedHash());
    payload.put("input_tokens", command.inputTokens());
    payload.put("cached_input_tokens", command.cachedInputTokens());
    payload.put("output_tokens", command.outputTokens());
    payload.put("reasoning_output_tokens", 0L);
    payload.put("total_tokens", command.totalTokens());
    payload.put("decision_kind", "STRUCTURED_FINAL");
    payload.put("decision_hash", command.decisionHash());
    payload.putNull("failure_code");
    payload.put("key_id", command.keyId());
    payload.put("challenge_ttl_millis", command.challengeTtl().toMillis());
    return jdbc.sql(
            """
            SELECT protocol_version, database_name, database_oid,
                   schema_oid, attestor_role_oid,
                   principal_id, btrim(attempt_id)::text AS attempt_id,
                   btrim(manifest_hash)::text AS manifest_hash,
                   btrim(requirement_hash)::text AS requirement_hash,
                   btrim(base_validation_policy_hash)::text
                     AS base_validation_policy_hash,
                   key_id, btrim(key_fingerprint)::text AS key_fingerprint,
                   validation_nonce, issued_at_epoch_micros,
                   expires_at_epoch_micros,
                   btrim(statement_hash)::text AS statement_hash,
                   btrim(attribution_hash)::text AS attribution_hash,
                   btrim(event_hash)::text AS event_hash,
                   btrim(overlay_head_hash)::text AS overlay_head_hash,
                   btrim(challenge_hash)::text AS challenge_hash,
                   btrim(transcript_hash)::text AS transcript_hash,
                   public_key_der, decision_kind,
                   btrim(decision_hash)::text AS decision_hash,
                   failure_code
            FROM public.agent_graph_stage_exact_tx_a_v16(
              CAST(:payload AS jsonb))
            """)
        .param("payload", payload.toString())
        .query(
            (row, ignored) -> {
              String failure = row.getString("failure_code");
              GraphExactPicoProviderValidationChallenge challenge =
                  new GraphExactPicoProviderValidationChallenge(
                      row.getString("protocol_version"),
                      row.getString("database_name"),
                      row.getLong("database_oid"),
                      row.getLong("schema_oid"),
                      row.getLong("attestor_role_oid"),
                      row.getString("principal_id"),
                      row.getString("attempt_id"),
                      row.getString("manifest_hash"),
                      row.getString("requirement_hash"),
                      row.getString("base_validation_policy_hash"),
                      row.getString("key_id"),
                      row.getString("key_fingerprint"),
                      row.getObject("validation_nonce", UUID.class),
                      instant(row.getLong("issued_at_epoch_micros")),
                      instant(row.getLong("expires_at_epoch_micros")),
                      row.getString("statement_hash"),
                      row.getString("attribution_hash"),
                      row.getString("event_hash"),
                      row.getString("overlay_head_hash"),
                      GraphProviderValidationDecision.valueOf(
                          row.getString("decision_kind")),
                      row.getString("decision_hash"),
                      failure == null
                          ? null
                          : GraphAttributedFailureCode.require(failure),
                      row.getString("challenge_hash"),
                      row.getString("transcript_hash"));
              return new Stage(challenge, row.getBytes("public_key_der"));
            })
        .single();
  }

  private GraphExactPicoProviderValidationReceipt commit(
      GraphExactPicoProviderValidationChallenge challenge,
      String signature) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("principal_id", challenge.principalId());
    payload.put("attempt_id", challenge.attemptId());
    payload.put("manifest_hash", challenge.manifestHash());
    payload.put("requirement_hash", challenge.requirementHash());
    payload.put("transcript_hash", challenge.transcriptHash());
    payload.put("signature_hex", signature);
    String result =
        jdbc.sql(
                """
                SELECT public.agent_graph_commit_exact_tx_a_v16(
                  CAST(:payload AS jsonb))::text
                """)
            .param("payload", payload.toString())
            .query(String.class)
            .single();
    try {
      JsonNode receipt = JSON.readTree(result);
      if (receipt.size() != 10
          || !receipt.has("protocol_version")
          || !receipt.has("sequence")
          || !receipt.has("state_version")
          || !receipt.has("head_hash")
          || !receipt.has("statement_hash")
          || !receipt.has("attribution_hash")
          || !receipt.has("event_hash")
          || !receipt.has("transcript_hash")
          || !receipt.has("validation_receipt_hash")
          || !receipt.has("validation_state")
          || !receipt.path("protocol_version").isTextual()
          || !receipt.path("sequence").isIntegralNumber()
          || !receipt.path("sequence").canConvertToInt()
          || !receipt.path("state_version").isIntegralNumber()
          || !receipt.path("state_version").canConvertToLong()
          || !receipt.path("head_hash").isTextual()
          || !receipt.path("statement_hash").isTextual()
          || !receipt.path("attribution_hash").isTextual()
          || !receipt.path("event_hash").isTextual()
          || !receipt.path("transcript_hash").isTextual()
          || !receipt.path("validation_receipt_hash").isTextual()
          || !receipt.path("validation_state").isTextual()) {
        throw new GraphAttemptIntegrityException();
      }
      GraphExactPicoProviderValidationReceipt typed =
          new GraphExactPicoProviderValidationReceipt(
              receipt.path("protocol_version").asText(),
              receipt.path("sequence").asInt(),
              receipt.path("state_version").asLong(),
              receipt.path("head_hash").asText(),
              receipt.path("statement_hash").asText(),
              receipt.path("attribution_hash").asText(),
              receipt.path("event_hash").asText(),
              receipt.path("transcript_hash").asText(),
              receipt.path("validation_receipt_hash").asText(),
              GraphExactPicoProviderValidationReceipt.ValidationState.valueOf(
                  receipt.path("validation_state").asText()));
      if (!typed.overlayHeadHash().equals(challenge.overlayHeadHash())
          || !typed.statementHash().equals(challenge.statementHash())
          || !typed.attributionHash().equals(challenge.attributionHash())
          || !typed.eventHash().equals(challenge.eventHash())
          || !typed.transcriptHash().equals(challenge.transcriptHash())) {
        throw new GraphAttemptIntegrityException();
      }
      return typed;
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private static Instant instant(long epochMicros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, 1_000_000L),
        Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
  }

  private RuntimeIdentity requireAuthority() {
    try {
      AuthorityProbe authority =
          jdbc.sql(
                  """
                  SELECT session_user AS role_name,
                         current_database() AS database_name,
                         database.oid::text AS database_oid,
                         namespace.oid::text AS schema_oid,
                         runtime_role.oid::text AS role_oid,
                         COALESCE(pg_catalog.inet_server_addr()::text,
                                  'local-socket') AS server_address,
                         COALESCE(pg_catalog.inet_server_port(), -1)
                           AS server_port,
                         session_user = 'emergeos_provider_attestor_v16'
                           AND current_user = session_user
                           AND runtime_role.rolcanlogin
                           AND NOT runtime_role.rolinherit
                           AND NOT runtime_role.rolsuper
                           AND NOT runtime_role.rolcreatedb
                           AND NOT runtime_role.rolcreaterole
                           AND NOT runtime_role.rolreplication
                           AND NOT runtime_role.rolbypassrls
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_auth_members membership
                             WHERE membership.member = runtime_role.oid
                                OR membership.roleid = runtime_role.oid)
                           AND pg_catalog.has_database_privilege(
                             runtime_role.oid, database.oid, 'CONNECT')
                           AND NOT pg_catalog.has_database_privilege(
                             runtime_role.oid, database.oid,
                             'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
                           AND pg_catalog.has_schema_privilege(
                             runtime_role.oid, namespace.oid, 'USAGE')
                           AND NOT pg_catalog.has_schema_privilege(
                             runtime_role.oid, namespace.oid,
                             'CREATE,USAGE WITH GRANT OPTION')
                           AND namespace.nspowner = (
                             SELECT owner_role.oid
                             FROM pg_catalog.pg_roles owner_role
                             WHERE owner_role.rolname =
                               'emergeos_pack010_schema_owner')
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_namespace other_namespace
                             WHERE other_namespace.nspname <> 'public'
                               AND other_namespace.nspname <>
                                 'information_schema'
                               AND other_namespace.nspname NOT LIKE
                                 'pg\\_%' ESCAPE '\\'
                               AND (
                                 other_namespace.nspowner = runtime_role.oid
                                 OR pg_catalog.has_schema_privilege(
                                   runtime_role.oid, other_namespace.oid,
                                   'USAGE,CREATE')))
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
                               AND NOT EXISTS (
                                 SELECT 1
                                 FROM pg_catalog.pg_auth_members membership
                                 WHERE membership.member = owner_role.oid
                                    OR membership.roleid = owner_role.oid)
                           ) = 2
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_roles prerequisite_role
                             WHERE prerequisite_role.rolname IN (
                               'emergeos_graph_prefix_writer',
                               'emergeos_graph_reader',
                               'emergeos_exact_overlay_reader_v19')
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
                           ) = 3
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_attribute attribute
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = attribute.attrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND attribute.attnum > 0
                               AND NOT attribute.attisdropped
                               AND attribute.attacl IS NOT NULL)
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_proc procedure
                             JOIN pg_catalog.pg_namespace function_namespace
                               ON function_namespace.oid =
                                  procedure.pronamespace
                             JOIN pg_catalog.pg_roles owner
                               ON owner.oid = procedure.proowner
                             JOIN pg_catalog.pg_language language
                               ON language.oid = procedure.prolang
                             WHERE function_namespace.nspname = 'public'
                               AND procedure.proname IN (
                                 'agent_graph_stage_exact_tx_a_v16',
                                 'agent_graph_commit_exact_tx_a_v16')
                               AND pg_catalog.pg_get_function_identity_arguments(
                                     procedure.oid) = 'payload jsonb'
                               AND procedure.prosecdef
                               AND procedure.provolatile = 'v'
                               AND procedure.proparallel = 'u'
                               AND procedure.prokind = 'f'
                               AND NOT procedure.proisstrict
                               AND NOT procedure.proleakproof
                               AND procedure.proconfig = ARRAY[
                                 'search_path=pg_catalog, pg_temp']::TEXT[]
                               AND owner.rolname =
                                 'emergeos_terminal_owner'
                               AND language.lanname = 'plpgsql'
                               AND pg_catalog.has_function_privilege(
                                 runtime_role.oid, procedure.oid, 'EXECUTE')
                               AND NOT pg_catalog.has_function_privilege(
                                 runtime_role.oid, procedure.oid,
                                 'EXECUTE WITH GRANT OPTION')
                               AND pg_catalog.encode(pg_catalog.sha256(
                                     pg_catalog.convert_to(
                                       procedure.prosrc, 'UTF8')), 'hex') =
                                 CASE procedure.proname
                                 WHEN 'agent_graph_stage_exact_tx_a_v16'
                                   THEN '593aab6d426090ea11d82970100ac90cbb2840463a7919030ac4b5fc970a5cf8'
                                 ELSE '7d15cc5f1e613da201ecd2219e85d522bde2ce671354fdf1eb112f43064af07b'
                                 END
                               AND (
                                 (procedure.proname =
                                    'agent_graph_stage_exact_tx_a_v16'
                                  AND procedure.proretset
                                  AND pg_catalog.octet_length(
                                    pg_catalog.pg_get_function_result(
                                      procedure.oid)) = 1879
                                  AND pg_catalog.md5(
                                    pg_catalog.pg_get_function_result(
                                      procedure.oid)) =
                                    'b53651040ad5e8695dd553dbdf34adcc')
                                 OR
                                 (procedure.proname =
                                    'agent_graph_commit_exact_tx_a_v16'
                                  AND NOT procedure.proretset
                                  AND pg_catalog.pg_get_function_result(
                                    procedure.oid) = 'jsonb'))
                           ) = 2
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_proc procedure
                             JOIN pg_catalog.pg_namespace function_namespace
                               ON function_namespace.oid =
                                  procedure.pronamespace
                             JOIN pg_catalog.pg_roles owner
                               ON owner.oid = procedure.proowner
                             JOIN pg_catalog.pg_language language
                               ON language.oid = procedure.prolang
                             WHERE function_namespace.nspname = 'public'
                               AND procedure.proname =
                                 'agent_graph_framed_sha256_v14'
                               AND pg_catalog.pg_get_function_identity_arguments(
                                     procedure.oid) =
                                 'hash_domain character varying, hash_fields text[]'
                               AND pg_catalog.pg_get_function_result(
                                     procedure.oid) = 'character'
                               AND NOT procedure.prosecdef
                               AND procedure.provolatile = 'i'
                               AND procedure.proparallel = 'u'
                               AND procedure.prokind = 'f'
                               AND NOT procedure.proretset
                               AND procedure.proisstrict
                               AND NOT procedure.proleakproof
                               AND procedure.proconfig = ARRAY[
                                 'search_path=pg_catalog, pg_temp']::TEXT[]
                               AND owner.rolname =
                                 'emergeos_pack010_schema_owner'
                               AND language.lanname = 'plpgsql'
                               AND pg_catalog.encode(pg_catalog.sha256(
                                     pg_catalog.convert_to(
                                       procedure.prosrc, 'UTF8')), 'hex') =
                                 'e0be5f17a805405befc5630145182c73ff8b325522b4ac00ee341acc58bd1edf'
                           ) = 1
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_proc procedure
                             JOIN pg_catalog.pg_namespace function_namespace
                               ON function_namespace.oid =
                                  procedure.pronamespace
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(
                                 procedure.proacl,
                                 pg_catalog.acldefault(
                                   'f', procedure.proowner))) acl
                             LEFT JOIN pg_catalog.pg_roles grantee
                               ON grantee.oid = acl.grantee
                             WHERE function_namespace.nspname = 'public'
                               AND (
                                 (procedure.proname IN (
                                    'agent_graph_stage_exact_tx_a_v16',
                                    'agent_graph_commit_exact_tx_a_v16')
                                  AND pg_catalog.pg_get_function_identity_arguments(
                                        procedure.oid) = 'payload jsonb')
                                 OR
                                 (procedure.proname =
                                    'agent_graph_framed_sha256_v14'
                                  AND pg_catalog.pg_get_function_identity_arguments(
                                        procedure.oid) =
                                    'hash_domain character varying, hash_fields text[]')
                                 OR
                                 (procedure.proname =
                                    'agent_graph_assert_exact_tx_a_overlay_v16'
                                  AND pg_catalog.pg_get_function_identity_arguments(
                                        procedure.oid) = ''))
                               AND acl.grantee <> procedure.proowner
                               AND NOT (
                                 acl.privilege_type = 'EXECUTE'
                                 AND NOT acl.is_grantable
                                 AND COALESCE(
                                   (grantee.rolname =
                                      'emergeos_provider_attestor_v16'
                                    AND procedure.proname IN (
                                      'agent_graph_stage_exact_tx_a_v16',
                                      'agent_graph_commit_exact_tx_a_v16'))
                                   OR
                                   (grantee.rolname =
                                      'emergeos_terminal_owner'
                                    AND procedure.proname =
                                      'agent_graph_framed_sha256_v14'),
                                   false))
                           )
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_proc procedure
                             JOIN pg_catalog.pg_namespace function_namespace
                               ON function_namespace.oid =
                                  procedure.pronamespace
                             WHERE function_namespace.nspname = 'public'
                               AND procedure.proname IN (
                                 'agent_graph_stage_exact_tx_a_v16',
                                 'agent_graph_commit_exact_tx_a_v16',
                                 'agent_graph_assert_exact_tx_a_overlay_v16',
                                 'agent_graph_framed_sha256_v14')
                           ) = 4
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_proc procedure
                             JOIN pg_catalog.pg_namespace function_namespace
                               ON function_namespace.oid =
                                  procedure.pronamespace
                             WHERE function_namespace.nspname = 'public'
                               AND pg_catalog.has_function_privilege(
                                 runtime_role.oid, procedure.oid, 'EXECUTE')
                           ) = 2
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relkind IN (
                                 'r', 'p', 'v', 'm', 'f')
                               AND (
                                 pg_catalog.has_table_privilege(
                                   runtime_role.oid, relation.oid,
                                   'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                                 OR pg_catalog.has_any_column_privilege(
                                   runtime_role.oid, relation.oid,
                                   'SELECT,INSERT,UPDATE,REFERENCES')))
                           AND NOT EXISTS (
                             WITH exact_sequences AS MATERIALIZED (
                               SELECT relation.oid
                               FROM pg_catalog.pg_sequences sequence_row
                               JOIN pg_catalog.pg_namespace sequence_namespace
                                 ON sequence_namespace.nspname =
                                    sequence_row.schemaname
                               JOIN pg_catalog.pg_class relation
                                 ON relation.relnamespace =
                                    sequence_namespace.oid
                                AND relation.relname =
                                    sequence_row.sequencename
                               WHERE sequence_row.schemaname = 'public'
                             )
                             SELECT 1
                             FROM exact_sequences sequence_row
                             WHERE pg_catalog.has_sequence_privilege(
                               runtime_role.oid, sequence_row.oid,
                               'USAGE,SELECT,UPDATE'))
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(
                                 relation.relacl,
                                 pg_catalog.acldefault(
                                   'r', relation.relowner))) acl
                             LEFT JOIN pg_catalog.pg_roles grantee
                               ON grantee.oid = acl.grantee
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                               AND relation.relkind IN ('r', 'p')
                               AND acl.grantee <> relation.relowner
                               AND NOT (
                                 NOT acl.is_grantable
                                 AND (
                                   (grantee.rolname =
                                      'emergeos_terminal_owner'
                                    AND (
                                      acl.privilege_type IN (
                                        'SELECT', 'INSERT')
                                      OR
                                      (relation.relname =
                                         'agent_graph_exact_provider_validations_v16'
                                       AND acl.privilege_type = 'UPDATE')))
                                   OR
                                   (grantee.rolname =
                                      'emergeos_exact_overlay_reader_v19'
                                    AND acl.privilege_type = 'SELECT')))
                           )
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(
                                 relation.relacl,
                                 pg_catalog.acldefault(
                                   'r', relation.relowner))) acl
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                               AND relation.relkind IN ('r', 'p')
                               AND acl.grantee <> relation.relowner
                           ) = 13
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             JOIN pg_catalog.pg_roles relation_owner
                               ON relation_owner.oid = relation.relowner
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15')
                               AND relation.relkind IN ('r', 'p')
                               AND relation.relpersistence = 'p'
                               AND NOT relation.relrowsecurity
                               AND NOT relation.relforcerowsecurity
                               AND relation_owner.rolname =
                                 'emergeos_pack010_schema_owner'
                           ) = 9
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(
                                 relation.relacl,
                                 pg_catalog.acldefault(
                                   'r', relation.relowner))) acl
                             LEFT JOIN pg_catalog.pg_roles grantee
                               ON grantee.oid = acl.grantee
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15')
                               AND relation.relkind IN ('r', 'p')
                               AND acl.grantee <> relation.relowner
                               AND NOT acl.is_grantable
                               AND (
                                 (grantee.rolname =
                                    'emergeos_terminal_owner'
                                  AND (
                                    (relation.relname IN (
                                       'agent_graph_attempts',
                                       'agent_graph_attempt_heads',
                                       'agent_graph_attempt_events',
                                       'agent_graph_attempt_provider_attributions',
                                       'agent_graph_provider_session_intents',
                                       'agent_graph_provider_validations')
                                     AND acl.privilege_type IN (
                                       'SELECT', 'INSERT', 'UPDATE'))
                                    OR
                                    (relation.relname =
                                       'agent_graph_exact_tx_a_requirements_v15'
                                     AND acl.privilege_type IN (
                                       'SELECT', 'INSERT'))
                                    OR
                                    (relation.relname IN (
                                       'agent_graph_provider_validation_keys',
                                       'agent_graph_provider_profiles_v14')
                                     AND acl.privilege_type = 'SELECT')))
                                 OR
                                 (grantee.rolname =
                                    'emergeos_graph_prefix_writer'
                                  AND relation.relname IN (
                                    'agent_graph_attempts',
                                    'agent_graph_attempt_heads',
                                    'agent_graph_attempt_events',
                                    'agent_graph_attempt_provider_attributions',
                                    'agent_graph_provider_session_intents')
                                  AND (
                                    acl.privilege_type IN (
                                      'SELECT', 'INSERT')
                                    OR
                                    (relation.relname =
                                       'agent_graph_attempt_heads'
                                     AND acl.privilege_type = 'UPDATE')))
                                 OR
                                 (grantee.rolname = 'emergeos_graph_reader'
                                  AND relation.relname IN (
                                    'agent_graph_attempts',
                                    'agent_graph_attempt_heads',
                                    'agent_graph_attempt_events',
                                    'agent_graph_attempt_provider_attributions',
                                    'agent_graph_provider_session_intents')
                                  AND acl.privilege_type = 'SELECT')
                                 OR
                                 (grantee.rolname =
                                    'emergeos_exact_overlay_reader_v19'
                                  AND acl.privilege_type = 'SELECT'))
                           ) = 47
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             CROSS JOIN LATERAL pg_catalog.aclexplode(
                               COALESCE(
                                 relation.relacl,
                                 pg_catalog.acldefault(
                                   'r', relation.relowner))) acl
                             LEFT JOIN pg_catalog.pg_roles grantee
                               ON grantee.oid = acl.grantee
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15')
                               AND relation.relkind IN ('r', 'p')
                               AND acl.grantee <> relation.relowner
                               AND NOT (
                                 NOT acl.is_grantable
                                 AND (
                                   (grantee.rolname =
                                      'emergeos_terminal_owner'
                                    AND (
                                      (relation.relname IN (
                                         'agent_graph_attempts',
                                         'agent_graph_attempt_heads',
                                         'agent_graph_attempt_events',
                                         'agent_graph_attempt_provider_attributions',
                                         'agent_graph_provider_session_intents',
                                         'agent_graph_provider_validations')
                                       AND acl.privilege_type IN (
                                         'SELECT', 'INSERT', 'UPDATE'))
                                      OR
                                      (relation.relname =
                                         'agent_graph_exact_tx_a_requirements_v15'
                                       AND acl.privilege_type IN (
                                         'SELECT', 'INSERT'))
                                      OR
                                      (relation.relname IN (
                                         'agent_graph_provider_validation_keys',
                                         'agent_graph_provider_profiles_v14')
                                       AND acl.privilege_type = 'SELECT')))
                                   OR
                                   (grantee.rolname =
                                      'emergeos_graph_prefix_writer'
                                    AND relation.relname IN (
                                      'agent_graph_attempts',
                                      'agent_graph_attempt_heads',
                                      'agent_graph_attempt_events',
                                      'agent_graph_attempt_provider_attributions',
                                      'agent_graph_provider_session_intents')
                                    AND (
                                      acl.privilege_type IN (
                                        'SELECT', 'INSERT')
                                      OR
                                      (relation.relname =
                                         'agent_graph_attempt_heads'
                                       AND acl.privilege_type = 'UPDATE')))
                                   OR
                                   (grantee.rolname = 'emergeos_graph_reader'
                                    AND relation.relname IN (
                                      'agent_graph_attempts',
                                      'agent_graph_attempt_heads',
                                      'agent_graph_attempt_events',
                                      'agent_graph_attempt_provider_attributions',
                                      'agent_graph_provider_session_intents')
                                    AND acl.privilege_type = 'SELECT')
                                   OR
                                   (grantee.rolname =
                                      'emergeos_exact_overlay_reader_v19'
                                    AND acl.privilege_type = 'SELECT')))
                           )
                           AND (
                             SELECT count(*) = 354
                                AND pg_catalog.encode(
                                  pg_catalog.sha256(
                                    pg_catalog.convert_to(
                                      pg_catalog.string_agg(
                                        pg_catalog.concat_ws('|',
                                          column_shape.relname,
                                          column_shape.attnum::TEXT,
                                          column_shape.attname,
                                          column_shape.type_name,
                                          column_shape.attnotnull::TEXT,
                                          column_shape.attidentity::TEXT,
                                          column_shape.attgenerated::TEXT,
                                          column_shape.default_expr,
                                          column_shape.collation_name),
                                        ',' ORDER BY column_shape.relname,
                                          column_shape.attnum),
                                      'UTF8')),
                                  'hex') =
                                  '384399a5cb39bc505b154d8f1aa5b6a26408d761ae1c1121b3fe330fb797b788'
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
                                      COALESCE(
                                        collation_row.collname, '')
                                        AS collation_name
                               FROM pg_catalog.pg_attribute attribute
                               JOIN pg_catalog.pg_class relation
                                 ON relation.oid = attribute.attrelid
                               JOIN pg_catalog.pg_namespace relation_namespace
                                 ON relation_namespace.oid =
                                    relation.relnamespace
                               LEFT JOIN pg_catalog.pg_attrdef default_value
                                 ON default_value.adrelid =
                                    attribute.attrelid
                                AND default_value.adnum = attribute.attnum
                               LEFT JOIN pg_catalog.pg_collation collation_row
                                 ON collation_row.oid =
                                    attribute.attcollation
                               WHERE relation_namespace.nspname = 'public'
                                 AND relation.relname IN (
                                   'agent_graph_attempts',
                                   'agent_graph_attempt_heads',
                                   'agent_graph_attempt_events',
                                   'agent_graph_attempt_provider_attributions',
                                   'agent_graph_provider_session_intents',
                                   'agent_graph_provider_validation_keys',
                                   'agent_graph_provider_validations',
                                   'agent_graph_provider_profiles_v14',
                                   'agent_graph_exact_tx_a_requirements_v15',
                                   'agent_graph_exact_provider_validations_v16',
                                   'agent_graph_exact_provider_attributions_v16',
                                   'agent_graph_exact_attempt_events_v16',
                                   'agent_graph_exact_attempt_heads_v16')
                                 AND attribute.attnum > 0
                                 AND NOT attribute.attisdropped
                             ) column_shape
                           )
                           AND (
                             SELECT count(*) = 430
                                AND pg_catalog.encode(
                                  pg_catalog.sha256(
                                    pg_catalog.convert_to(
                                      pg_catalog.string_agg(
                                        pg_catalog.concat_ws('|',
                                          relation.relname,
                                          constraint_row.conname,
                                          constraint_row.contype::TEXT,
                                          constraint_row.condeferrable::TEXT,
                                          constraint_row.condeferred::TEXT,
                                          constraint_row.convalidated::TEXT,
                                          pg_catalog.pg_get_constraintdef(
                                            constraint_row.oid, true)),
                                        ',' ORDER BY relation.relname,
                                          constraint_row.conname),
                                      'UTF8')),
                                  'hex') =
                                  '2a9a8cde0bd34ff4f9752f99348a781bd566efc95bba1cf981c4ba2dd81d178d'
                             FROM pg_catalog.pg_constraint constraint_row
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = constraint_row.conrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15',
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                           )
                           AND (
                             SELECT count(*) = 38
                                AND pg_catalog.encode(
                                  pg_catalog.sha256(
                                    pg_catalog.convert_to(
                                      pg_catalog.string_agg(
                                        pg_catalog.concat_ws('|',
                                          relation.relname,
                                          index_relation.relname,
                                          index_row.indisunique::TEXT,
                                          index_row.indisprimary::TEXT,
                                          index_row.indisvalid::TEXT,
                                          index_row.indisready::TEXT,
                                          pg_catalog.pg_get_indexdef(
                                            index_relation.oid)),
                                        ',' ORDER BY relation.relname,
                                          index_relation.relname),
                                      'UTF8')),
                                  'hex') =
                                  '4b3fd04adfb136a17159c18fda162aeb1fc149359912932033647d2d65ba35d7'
                             FROM pg_catalog.pg_index index_row
                             JOIN pg_catalog.pg_class index_relation
                               ON index_relation.oid = index_row.indexrelid
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = index_row.indrelid
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15',
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                           )
                           AND (
                             SELECT count(*) = 13
                                AND pg_catalog.encode(
                                  pg_catalog.sha256(
                                    pg_catalog.convert_to(
                                      pg_catalog.string_agg(
                                        pg_catalog.concat_ws('|',
                                          relation.relname,
                                         relation.relkind::TEXT,
                                          relation.relispartition::TEXT,
                                          relation.relpersistence::TEXT,
                                          relation.relrowsecurity::TEXT,
                                          relation.relforcerowsecurity::TEXT,
                                          relation.relreplident::TEXT),
                                        ',' ORDER BY relation.relname),
                                      'UTF8')),
                                  'hex') =
                                  '5888ea4076934e2d507903047b7e22dcab0d2153e91a7870b8f3a48b8ffc1e84'
                             FROM pg_catalog.pg_class relation
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             JOIN pg_catalog.pg_roles relation_owner
                               ON relation_owner.oid = relation.relowner
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15',
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                               AND relation_owner.rolname =
                                 'emergeos_pack010_schema_owner'
                           )
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_inherits inheritance
                             JOIN pg_catalog.pg_class child_relation
                               ON child_relation.oid = inheritance.inhrelid
                             JOIN pg_catalog.pg_namespace child_namespace
                               ON child_namespace.oid =
                                  child_relation.relnamespace
                             JOIN pg_catalog.pg_class parent_relation
                               ON parent_relation.oid = inheritance.inhparent
                             JOIN pg_catalog.pg_namespace parent_namespace
                               ON parent_namespace.oid =
                                  parent_relation.relnamespace
                             WHERE (child_namespace.nspname = 'public'
                                    AND child_relation.relname IN (
                                      'agent_graph_attempts',
                                      'agent_graph_attempt_heads',
                                      'agent_graph_attempt_events',
                                      'agent_graph_attempt_provider_attributions',
                                      'agent_graph_provider_session_intents',
                                      'agent_graph_provider_validation_keys',
                                      'agent_graph_provider_validations',
                                      'agent_graph_provider_profiles_v14',
                                      'agent_graph_exact_tx_a_requirements_v15',
                                      'agent_graph_exact_provider_validations_v16',
                                      'agent_graph_exact_provider_attributions_v16',
                                      'agent_graph_exact_attempt_events_v16',
                                      'agent_graph_exact_attempt_heads_v16'))
                                OR (parent_namespace.nspname = 'public'
                                    AND parent_relation.relname IN (
                                      'agent_graph_attempts',
                                      'agent_graph_attempt_heads',
                                      'agent_graph_attempt_events',
                                      'agent_graph_attempt_provider_attributions',
                                      'agent_graph_provider_session_intents',
                                      'agent_graph_provider_validation_keys',
                                      'agent_graph_provider_validations',
                                      'agent_graph_provider_profiles_v14',
                                      'agent_graph_exact_tx_a_requirements_v15',
                                      'agent_graph_exact_provider_validations_v16',
                                      'agent_graph_exact_provider_attributions_v16',
                                      'agent_graph_exact_attempt_events_v16',
                                      'agent_graph_exact_attempt_heads_v16'))
                           )
                           AND NOT EXISTS (
                             SELECT 1
                             FROM pg_catalog.pg_rewrite rewrite_rule
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = rewrite_rule.ev_class
                             JOIN pg_catalog.pg_namespace relation_namespace
                               ON relation_namespace.oid =
                                  relation.relnamespace
                             WHERE relation_namespace.nspname = 'public'
                               AND relation.relname IN (
                                 'agent_graph_attempts',
                                 'agent_graph_attempt_heads',
                                 'agent_graph_attempt_events',
                                 'agent_graph_attempt_provider_attributions',
                                 'agent_graph_provider_session_intents',
                                 'agent_graph_provider_validation_keys',
                                 'agent_graph_provider_validations',
                                 'agent_graph_provider_profiles_v14',
                                 'agent_graph_exact_tx_a_requirements_v15',
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                           )
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_trigger trigger
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = trigger.tgrelid
                             JOIN pg_catalog.pg_namespace trigger_namespace
                               ON trigger_namespace.oid =
                                  relation.relnamespace
                             WHERE trigger_namespace.nspname = 'public'
                               AND NOT trigger.tgisinternal
                               AND relation.relname IN (
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                           ) = 4
                           AND (
                             SELECT count(*)
                             FROM pg_catalog.pg_trigger trigger
                             JOIN pg_catalog.pg_class relation
                               ON relation.oid = trigger.tgrelid
                             JOIN pg_catalog.pg_namespace trigger_namespace
                               ON trigger_namespace.oid =
                                  relation.relnamespace
                             JOIN pg_catalog.pg_proc function
                               ON function.oid = trigger.tgfoid
                             JOIN pg_catalog.pg_roles function_owner
                               ON function_owner.oid = function.proowner
                             JOIN pg_catalog.pg_language function_language
                               ON function_language.oid = function.prolang
                             JOIN pg_catalog.pg_constraint constraint_row
                               ON constraint_row.oid = trigger.tgconstraint
                             WHERE trigger_namespace.nspname = 'public'
                               AND NOT trigger.tgisinternal
                               AND relation.relname IN (
                                 'agent_graph_exact_provider_validations_v16',
                                 'agent_graph_exact_provider_attributions_v16',
                                 'agent_graph_exact_attempt_events_v16',
                                 'agent_graph_exact_attempt_heads_v16')
                               AND trigger.tgname = CASE relation.relname
                                 WHEN 'agent_graph_exact_provider_validations_v16'
                                   THEN 'agent_graph_exact_provider_validation_v16'
                                 WHEN 'agent_graph_exact_provider_attributions_v16'
                                   THEN 'agent_graph_exact_provider_attribution_v16'
                                 WHEN 'agent_graph_exact_attempt_events_v16'
                                   THEN 'agent_graph_exact_attempt_event_v16'
                                 WHEN 'agent_graph_exact_attempt_heads_v16'
                                   THEN 'agent_graph_exact_attempt_head_v16'
                                 ELSE NULL
                                 END
                               AND trigger.tgenabled = 'O'
                               AND trigger.tgtype = 29
                               AND trigger.tgqual IS NULL
                               AND trigger.tgnargs = 0
                               AND pg_catalog.octet_length(trigger.tgargs) = 0
                               AND trigger.tgattr =
                                 ''::pg_catalog.int2vector
                               AND constraint_row.condeferrable
                               AND constraint_row.condeferred
                               AND function.proname =
                                 'agent_graph_assert_exact_tx_a_overlay_v16'
                               AND pg_catalog.pg_get_function_identity_arguments(
                                     function.oid) = ''
                               AND pg_catalog.pg_get_function_result(
                                     function.oid) = 'trigger'
                               AND pg_catalog.encode(pg_catalog.sha256(
                                     pg_catalog.convert_to(
                                       function.prosrc, 'UTF8')), 'hex') =
                                 '5083d24c002811e58d819b638e02ec2e83ba0c8f783c41362beef84ffb09de3f'
                               AND function.prosecdef
                               AND function.provolatile = 'v'
                               AND function.proparallel = 'u'
                               AND function.prokind = 'f'
                               AND NOT function.proretset
                               AND NOT function.proisstrict
                               AND NOT function.proleakproof
                               AND function.proconfig = ARRAY[
                                 'search_path=pg_catalog, pg_temp']::TEXT[]
                               AND function_owner.rolname =
                                 'emergeos_terminal_owner'
                               AND function_language.lanname = 'plpgsql'
                           ) = 4 AS exact
                  FROM pg_catalog.pg_database database
                  CROSS JOIN pg_catalog.pg_namespace namespace
                  CROSS JOIN pg_catalog.pg_roles runtime_role
                  WHERE database.datname = current_database()
                    AND namespace.nspname = 'public'
                    AND runtime_role.rolname = session_user
                  """)
              .query(
                  (row, ignored) ->
                      new AuthorityProbe(
                          new RuntimeIdentity(
                              row.getString("role_name"),
                              row.getString("database_name"),
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

  private void requireFrozenAuthority() {
    if (!frozen.equals(requireAuthority())) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private <T> T executeVerified(java.util.function.Supplier<T> operation) {
    try {
      return Objects.requireNonNull(
          transactions.execute(ignored -> operation.get()),
          "exact pico authority result");
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
        || "40P01".equals(state)
        || "23505".equals(state)) {
      return new GraphAttemptConflictException(
          "exact pico provider validation was fenced");
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

  private record Stage(
      GraphExactPicoProviderValidationChallenge challenge,
      byte[] publicKeyDer) {

    private Stage {
      challenge = Objects.requireNonNull(challenge, "challenge");
      publicKeyDer = Objects.requireNonNull(publicKeyDer, "publicKeyDer").clone();
    }

    @Override
    public byte[] publicKeyDer() {
      return publicKeyDer.clone();
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

  private static final class LocalFailure extends RuntimeException {
    private LocalFailure() {
      super(null, null, false, false);
    }
  }
}
