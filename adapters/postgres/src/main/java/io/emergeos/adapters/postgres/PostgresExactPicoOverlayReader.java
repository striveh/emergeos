package io.emergeos.adapters.postgres;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphExactPicoOverlayAttribution;
import io.emergeos.core.domain.GraphExactPicoOverlayRequirement;
import io.emergeos.core.domain.GraphExactPicoOverlaySnapshot;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;
import io.emergeos.core.domain.GraphExactPicoProviderSignatureVerifier;
import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphPricingSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.port.GraphExactPicoOverlayReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, fresh-snapshot verifier for the dormant V16 exact-pico overlay. */
public final class PostgresExactPicoOverlayReader
    implements GraphExactPicoOverlayReader {

  private static final String READER_ROLE =
      "emergeos_exact_overlay_reader_v19";
  private static final String SCHEMA_OWNER =
      "emergeos_pack010_schema_owner";
  private static final String[] RELATIONS = {
    "agent_graph_attempts",
    "agent_graph_attempt_heads",
    "agent_graph_attempt_events",
    "agent_graph_attempt_provider_attributions",
    "agent_graph_provider_session_intents",
    "agent_graph_provider_validation_keys",
    "agent_graph_provider_validations",
    "agent_graph_provider_profiles_v14",
    "agent_graph_exact_tx_a_requirements_v15",
    "agent_graph_exact_provider_validations_v16",
    "agent_graph_exact_provider_attributions_v16",
    "agent_graph_exact_attempt_events_v16",
    "agent_graph_exact_attempt_heads_v16"
  };
  private static final String RELATION_NAMES_SQL =
      String.join("','", RELATIONS);
  private static final String COLUMN_SHAPE_HASH =
      "384399a5cb39bc505b154d8f1aa5b6a26408d761ae1c1121b3fe330fb797b788";
  private static final String CONSTRAINT_SHAPE_HASH =
      "2a9a8cde0bd34ff4f9752f99348a781bd566efc95bba1cf981c4ba2dd81d178d";
  private static final String INDEX_SHAPE_HASH =
      "4b3fd04adfb136a17159c18fda162aeb1fc149359912932033647d2d65ba35d7";
  private static final String RELATION_SHAPE_HASH =
      "5888ea4076934e2d507903047b7e22dcab0d2153e91a7870b8f3a48b8ffc1e84";
  private static final String REQUIREMENT_DOMAIN =
      "emergeos.graph-exact-tx-a-requirement.v15";
  private static final String PROFILE_DOMAIN =
      "emergeos.provider-profile.v14";
  private static final String POLICY_DOMAIN =
      "emergeos.provider-validation-policy.v1";
  private static final JsonMapper JSON = JsonMapper.shared();

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final RuntimeIdentity frozen;

  public static GraphExactPicoOverlayReader open(DataSource dataSource) {
    return new PostgresExactPicoOverlayReader(dataSource);
  }

  private PostgresExactPicoOverlayReader(DataSource dataSource) {
    DataSource checked = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = JdbcClient.create(checked);
    DataSourceTransactionManager manager =
        new DataSourceTransactionManager(checked);
    manager.setEnforceReadOnly(true);
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.transactions.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setReadOnly(true);
    this.frozen = execute(this::requireAuthority);
    if (!frozen.equals(execute(this::requireAuthority))) {
      throw new GraphAttemptIntegrityException();
    }
  }

  @Override
  public GraphExactPicoOverlayVerification findVerified(
      GraphAttemptManifest expected) {
    if (expected == null) {
      throw new IllegalArgumentException("expected manifest is required");
    }
    return execute(
        () -> {
          requireFrozenAuthority();
          return readVerified(expected);
        });
  }

  private GraphExactPicoOverlayVerification readVerified(
      GraphAttemptManifest expected) {
    Optional<ManifestRow> manifest = loadManifest(expected);
    if (manifest.isEmpty()) {
      return new GraphExactPicoOverlayVerification.Missing();
    }
    ManifestRow storedRow = manifest.orElseThrow();
    GraphAttemptManifest stored;
    try {
      stored = JSON.readValue(storedRow.manifestJson(), GraphAttemptManifest.class);
    } catch (RuntimeException failure) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXPECTED_MANIFEST_MISMATCH);
    }
    if (!storedRow.matches(stored) || !stored.equals(expected)) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXPECTED_MANIFEST_MISMATCH);
    }

    HeadRow head = loadHead(expected).orElse(null);
    List<EventRow> events = loadEvents(expected);
    List<LegacyAttributionRow> legacyAttributions =
        loadLegacyAttributions(expected);
    SessionRow session = loadSession(expected).orElse(null);
    RequirementRow requirement = loadRequirement(expected).orElse(null);
    ProfileRow profile =
        requirement == null
            ? null
            : loadProfile(requirement.providerProfileId()).orElse(null);
    PolicyRow policy = loadPolicy(expected).orElse(null);
    KeyRow key =
        policy == null ? null : loadKey(policy.keyId()).orElse(null);
    ValidationRow validation = loadValidation(expected).orElse(null);
    OverlayAttributionRow attribution =
        loadOverlayAttribution(expected).orElse(null);
    OverlayEventRow overlayEvent = loadOverlayEvent(expected).orElse(null);
    OverlayHeadRow overlayHead = loadOverlayHead(expected).orElse(null);

    int overlayCount = count(validation, attribution, overlayEvent, overlayHead);
    if (requirement == null) {
      return overlayCount == 0
          ? new GraphExactPicoOverlayVerification.Missing()
          : invalid(
              GraphExactPicoOverlayVerification.InvalidReason
                  .EXACT_OVERLAY_PARTIAL);
    }

    VerifiedBase base =
        verifyBase(
            expected,
            storedRow,
            head,
            events,
            legacyAttributions,
            session,
            requirement,
            profile,
            policy,
            key);
    if (base == null) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_REQUIREMENT_INVALID);
    }
    if (overlayCount == 0) {
      return new GraphExactPicoOverlayVerification.Required(base.requirement());
    }
    if (overlayCount != 4) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_OVERLAY_PARTIAL);
    }
    if (policy == null || key == null) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_OVERLAY_INVALID);
    }
    try {
      return new GraphExactPicoOverlayVerification.Attributed(
          verifyOverlay(
              expected,
              base,
              policy,
              key,
              validation,
              attribution,
              overlayEvent,
              overlayHead));
    } catch (RuntimeException failure) {
      return invalid(
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_OVERLAY_INVALID);
    }
  }

  private VerifiedBase verifyBase(
      GraphAttemptManifest expected,
      ManifestRow manifest,
      HeadRow head,
      List<EventRow> eventRows,
      List<LegacyAttributionRow> attributionRows,
      SessionRow session,
      RequirementRow row,
      ProfileRow profile,
      PolicyRow policy,
      KeyRow key) {
    try {
      if (head == null
          || session == null
          || profile == null
          || eventRows.size() != 13
          || attributionRows.size() != 1
          || !head.isSequence13(expected)) {
        return null;
      }
      List<GraphAttemptEvent> events = new ArrayList<>(13);
      String previous = manifest.initialHeadHash();
      Instant previousTime = null;
      List<GraphAttemptEventType> prefix =
          List.of(
              GraphAttemptEventType.ATTEMPT_CLAIMED,
              GraphAttemptEventType.OPERATOR_APPROVED,
              GraphAttemptEventType.PARENT_AUTHORIZED,
              GraphAttemptEventType.PARENT_STARTED,
              GraphAttemptEventType.CHILD_AUTHORIZED,
              GraphAttemptEventType.CHILD_STARTED,
              GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
              GraphAttemptEventType.CREDENTIAL_READ_STARTED,
              GraphAttemptEventType.CLIENT_CREATED,
              GraphAttemptEventType.MODEL_CREATED,
              GraphAttemptEventType.PROVIDER_INTENT,
              GraphAttemptEventType.PROVIDER_ATTRIBUTED,
              GraphAttemptEventType.PROVIDER_INTENT);
      for (int index = 0; index < eventRows.size(); index++) {
        EventRow eventRow = eventRows.get(index);
        if (eventRow.sequence() != index + 1
            || !eventRow.manifestHash().equals(expected.manifestHash())
            || !eventRow.previousHeadHash().equals(previous)) {
          return null;
        }
        GraphAttemptEvent event = eventRow.toDomain();
        if (event.type() != prefix.get(index)
            || (previousTime != null
                && event.occurredAt().isBefore(previousTime))) {
          return null;
        }
        events.add(event);
        previous = event.currentHeadHash();
        previousTime = event.occurredAt();
      }
      if (!previous.equals(head.headHash())) {
        return null;
      }
      LegacyAttributionRow legacyRow = attributionRows.getFirst();
      GraphProviderAttribution legacy = legacyRow.toDomain();
      GraphAttemptEvent intent1 = events.get(10);
      GraphAttemptEvent attributed1 = events.get(11);
      GraphAttemptEvent intent2 = events.get(12);
      if (!legacyRow.matches(expected)
          || legacyRow.eventSequence() != 12
          || legacy.requestOrdinal() != 1
          || !legacy.requestHash().equals(intent1.requestHash())
          || !legacy.modelRequested().equals(intent1.modelRequested())
          || !legacy.providerActor().equals(expected.childActor())
          || !legacy
              .pricingProfileFingerprint()
              .equals(expected.pricingProfileFingerprint())
          || !legacy.attributionHash().equals(attributed1.evidenceHash())
          || !legacyRow.attributedAt().equals(attributed1.occurredAt())
          || intent2.requestOrdinal() == null
          || intent2.requestOrdinal() != 2
          || !session.valid(expected, events.get(6), intent1)
          || !row.valid(expected, head, intent2, frozen)
          || !profile.valid(row, intent2)
          || row.requiredAt().isBefore(profile.effectiveFrom())
          || row.requiredAt().isAfter(profile.effectiveUntil())) {
        return null;
      }
      GraphExactPicoOverlayRequirement requirement =
          row.toDomain(expected, intent2);
      if (policy != null
          && !policy.valid(
              expected, session, events.get(6), frozen, key)) {
        return null;
      }
      if (policy != null
          && (!policy.transportProfileHash().equals(profile.transportProfileHash())
              || !policy.parserProfileHash().equals(profile.parserProfileHash())
              || !policy.schemaProfileHash().equals(profile.schemaProfileHash()))) {
        return null;
      }
      return new VerifiedBase(requirement, profile, session);
    } catch (RuntimeException failure) {
      return null;
    }
  }

  private GraphExactPicoOverlaySnapshot verifyOverlay(
      GraphAttemptManifest expected,
      VerifiedBase base,
      PolicyRow policy,
      KeyRow key,
      ValidationRow validation,
      OverlayAttributionRow attribution,
      OverlayEventRow event,
      OverlayHeadRow head) {
    if (!validation.matches(expected, base, policy, key, frozen)
        || !attribution.matches(validation)
        || !event.matches(validation, attribution)
        || !head.matches(validation, attribution, event)
        || !key.validHistorical(validation)
        || !base.profile().validHistorical(validation)
        || validation.expiresAtMicros()
            != minimumExpiry(validation, policy, key, base.profile())
        || validation.validatedAtMicros()
            >= base.session().expiresAtMicros()) {
      throw new IllegalArgumentException("invalid exact overlay");
    }

    GraphExactPicoOverlayAttribution projectedAttribution =
        validation.toAttribution();
    GraphExactPicoProviderValidationReceipt receipt =
        new GraphExactPicoProviderValidationReceipt(
            validation.protocolVersion(),
            validation.overlaySequence(),
            validation.stateVersion(),
            validation.overlayHeadHash(),
            validation.statementHash(),
            validation.attributionHash(),
            validation.eventHash(),
            validation.transcriptHash(),
            validation.validationReceiptHash(),
            GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED);
    GraphExactPicoProviderValidationChallenge challenge =
        validation.toChallenge(expected);
    GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
        challenge, key.publicKeyDer(), HexFormat.of().formatHex(validation.signature()));
    if (!sha256(validation.signature()).equals(validation.signatureHash())) {
      throw new IllegalArgumentException("invalid exact overlay signature hash");
    }
    return new GraphExactPicoOverlaySnapshot(
        base.requirement(),
        projectedAttribution,
        validation.baseValidationPolicyHash(),
        validation.sessionIntentHash(),
        validation.keyId(),
        validation.keyFingerprint(),
        validation.challengeHash(),
        validation.signatureHash(),
        instant(validation.issuedAtMicros()),
        instant(validation.expiresAtMicros()),
        instant(validation.validatedAtMicros()),
        instant(validation.consumedAtMicros()),
        receipt);
  }

  private static long minimumExpiry(
      ValidationRow validation,
      PolicyRow policy,
      KeyRow key,
      ProfileRow profile) {
    long ttlExpiry =
        Math.addExact(
            validation.issuedAtMicros(),
            Math.multiplyExact((long) policy.challengeTtlMillis(), 1_000L));
    return Math.min(
        Math.min(ttlExpiry, validation.sessionExpiresAtMicros()),
        Math.min(micros(key.notAfter()), profile.effectiveUntilMicros()));
  }

  private Optional<ManifestRow> loadManifest(GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT principal_id, btrim(attempt_id)::text AS attempt_id,
                   execution_slot_id, manifest_schema_version,
                   graph_protocol_version, integrity_profile, case_id,
                   btrim(pack_raw_sha256)::text AS pack_raw_sha256,
                   btrim(environment_raw_sha256)::text AS environment_raw_sha256,
                   capture_id,
                   btrim(capture_request_hash)::text AS capture_request_hash,
                   artifact_id, started_at,
                   btrim(pricing_profile_fingerprint)::text
                     AS pricing_profile_fingerprint,
                   btrim(prompt_surface_fingerprint)::text
                     AS prompt_surface_fingerprint,
                   btrim(conductor_surface_fingerprint)::text
                     AS conductor_surface_fingerprint,
                   reservation_usd, maximum_provider_requests,
                   parent_actor, child_actor, experiment_arm,
                   experiment_repetition, manifest_json::text AS manifest_json,
                   btrim(manifest_hash)::text AS manifest_hash,
                   btrim(initial_head_hash)::text AS initial_head_hash
            FROM public.agent_graph_attempts
            WHERE principal_id = :principalId
              AND execution_slot_id = :executionSlotId
            """)
        .param("principalId", expected.principalId())
        .param("executionSlotId", expected.executionSlotId())
        .query(ManifestRow::from)
        .optional();
  }

  private Optional<HeadRow> loadHead(GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash, phase,
                   state_version, last_sequence,
                   btrim(head_hash)::text AS head_hash, billing_status,
                   provider_intent_count, provider_attribution_count
            FROM public.agent_graph_attempt_heads
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(HeadRow::from)
        .optional();
  }

  private List<EventRow> loadEvents(GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash, sequence,
                   event_type, occurred_at, phase_from, phase_to, role,
                   run_id, task_id, actor,
                   btrim(challenge_hash)::text AS challenge_hash,
                   request_ordinal,
                   btrim(request_hash)::text AS request_hash,
                   model_requested,
                   btrim(evidence_hash)::text AS evidence_hash,
                   btrim(previous_head_hash)::text AS previous_head_hash,
                   btrim(event_hash)::text AS event_hash,
                   btrim(current_head_hash)::text AS current_head_hash
            FROM public.agent_graph_attempt_events
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            ORDER BY sequence
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(EventRow::from)
        .list();
  }

  private List<LegacyAttributionRow> loadLegacyAttributions(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash,
                   request_ordinal, event_sequence,
                   btrim(request_hash)::text AS request_hash,
                   btrim(response_hash)::text AS response_hash,
                   provider_actor, model_requested, model_resolved,
                   pricing_profile_id, pricing_provider,
                   btrim(pricing_profile_fingerprint)::text
                     AS pricing_profile_fingerprint,
                   uncached_input_nano_usd_per_token,
                   cached_input_nano_usd_per_token,
                   output_nano_usd_per_token, input_tokens,
                   cached_input_tokens, output_tokens,
                   reasoning_output_tokens, total_tokens, observed_cost_usd,
                   btrim(attribution_hash)::text AS attribution_hash,
                   attributed_at
            FROM public.agent_graph_attempt_provider_attributions
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            ORDER BY request_ordinal
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(LegacyAttributionRow::from)
        .list();
  }

  private Optional<SessionRow> loadSession(GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash, revision,
                   cursor_sequence,
                   btrim(cursor_head_hash)::text AS cursor_head_hash,
                   request_ordinal,
                   btrim(request_hash)::text AS request_hash,
                   model_requested, btrim(intent_hash)::text AS intent_hash,
                   created_at, expires_at
            FROM public.agent_graph_provider_session_intents
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(SessionRow::from)
        .optional();
  }

  private Optional<RequirementRow> loadRequirement(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT protocol_version, database_name, database_oid::bigint,
                   schema_oid::bigint, attestor_role_oid::bigint,
                   btrim(manifest_hash)::text AS manifest_hash,
                   base_sequence, btrim(base_head_hash)::text AS base_head_hash,
                   btrim(execution_binding_hash)::text AS execution_binding_hash,
                   request_ordinal, provider_profile_id,
                   btrim(provider_profile_hash)::text AS provider_profile_hash,
                   state, btrim(requirement_hash)::text AS requirement_hash,
                   required_at
            FROM public.agent_graph_exact_tx_a_requirements_v15
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(RequirementRow::from)
        .optional();
  }

  private Optional<ProfileRow> loadProfile(String profileId) {
    return jdbc.sql(
            """
            SELECT profile_id, provider_id, provider_protocol,
                   transport_profile_id,
                   btrim(transport_profile_hash)::text AS transport_profile_hash,
                   parser_profile_id,
                   btrim(parser_profile_hash)::text AS parser_profile_hash,
                   schema_profile_id,
                   btrim(schema_profile_hash)::text AS schema_profile_hash,
                   model_profile_id,
                   btrim(model_profile_hash)::text AS model_profile_hash,
                   model_requested,
                   btrim(model_resolution_profile_hash)::text
                     AS model_resolution_profile_hash,
                   pricing_profile_id, pricing_provider_id,
                   btrim(pricing_profile_fingerprint)::text
                     AS pricing_profile_fingerprint,
                   btrim(pricing_source_hash)::text AS pricing_source_hash,
                   pricing_effective_from_epoch_micros,
                   pricing_effective_until_epoch_micros, rate_unit,
                   uncached_input_pico_usd_per_token,
                   cached_input_pico_usd_per_token,
                   output_pico_usd_per_token, reasoning_policy,
                   btrim(profile_hash)::text AS profile_hash, status
            FROM public.agent_graph_provider_profiles_v14
            WHERE profile_id = :profileId
            """)
        .param("profileId", profileId)
        .query(ProfileRow::from)
        .optional();
  }

  private Optional<PolicyRow> loadPolicy(GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT protocol_version, database_name, database_oid::bigint,
                   schema_oid::bigint, attestor_role_oid::bigint, revision,
                   btrim(manifest_hash)::text AS manifest_hash,
                   btrim(session_intent_hash)::text AS session_intent_hash,
                   session_expires_at, policy_sequence,
                   btrim(policy_head_hash)::text AS policy_head_hash,
                   key_id, btrim(key_fingerprint)::text AS key_fingerprint,
                   btrim(transport_profile_hash)::text AS transport_profile_hash,
                   btrim(parser_profile_hash)::text AS parser_profile_hash,
                   btrim(schema_profile_hash)::text AS schema_profile_hash,
                   challenge_ttl_millis,
                   btrim(policy_hash)::text AS policy_hash, state,
                   policy_created_at
            FROM public.agent_graph_provider_validations
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(PolicyRow::from)
        .optional();
  }

  private Optional<KeyRow> loadKey(String keyId) {
    return jdbc.sql(
            """
            SELECT key_id, algorithm, public_key_der,
                   btrim(key_fingerprint)::text AS key_fingerprint,
                   status, not_before, not_after, created_at
            FROM public.agent_graph_provider_validation_keys
            WHERE key_id = :keyId
            """)
        .param("keyId", keyId)
        .query(KeyRow::from)
        .optional();
  }

  private Optional<ValidationRow> loadValidation(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT protocol_version, database_name, database_oid::bigint,
                   schema_oid::bigint, attestor_role_oid::bigint,
                   btrim(manifest_hash)::text AS manifest_hash,
                   btrim(requirement_hash)::text AS requirement_hash,
                   btrim(base_validation_policy_hash)::text
                     AS base_validation_policy_hash,
                   revision,
                   btrim(session_intent_hash)::text AS session_intent_hash,
                   session_expires_at_epoch_micros, base_sequence,
                   btrim(base_head_hash)::text AS base_head_hash,
                   btrim(execution_binding_hash)::text AS execution_binding_hash,
                   overlay_sequence, state_version, request_ordinal,
                   btrim(request_hash)::text AS request_hash,
                   btrim(response_hash)::text AS response_hash,
                   provider_actor, provider_id, provider_protocol,
                   provider_profile_id,
                   btrim(provider_profile_hash)::text AS provider_profile_hash,
                   btrim(base_execution_pricing_fingerprint)::text
                     AS base_execution_pricing_fingerprint,
                   btrim(transport_profile_hash)::text AS transport_profile_hash,
                   btrim(parser_profile_hash)::text AS parser_profile_hash,
                   btrim(schema_profile_hash)::text AS schema_profile_hash,
                   model_requested,
                   btrim(model_resolved_hash)::text AS model_resolved_hash,
                   btrim(model_resolution_profile_hash)::text
                     AS model_resolution_profile_hash,
                   pricing_profile_id, pricing_provider_id,
                   btrim(pricing_profile_fingerprint)::text
                     AS pricing_profile_fingerprint,
                   btrim(pricing_source_hash)::text AS pricing_source_hash,
                   rate_unit, uncached_input_pico_usd_per_token,
                   cached_input_pico_usd_per_token,
                   output_pico_usd_per_token, input_tokens,
                   cached_input_tokens, output_tokens,
                   reasoning_output_tokens, total_tokens,
                   observed_cost_pico_usd,
                   btrim(statement_hash)::text AS statement_hash,
                   decision_kind,
                   btrim(decision_hash)::text AS decision_hash,
                   failure_code,
                   btrim(attribution_hash)::text AS attribution_hash,
                   btrim(event_hash)::text AS event_hash,
                   btrim(overlay_head_hash)::text AS overlay_head_hash,
                   key_id, btrim(key_fingerprint)::text AS key_fingerprint,
                   validation_nonce,
                   btrim(challenge_hash)::text AS challenge_hash,
                   btrim(transcript_hash)::text AS transcript_hash,
                   state, signature,
                   btrim(signature_hash)::text AS signature_hash,
                   btrim(validation_receipt_hash)::text
                     AS validation_receipt_hash,
                   issued_at_epoch_micros, expires_at_epoch_micros,
                   validated_at_epoch_micros, consumed_at_epoch_micros
            FROM public.agent_graph_exact_provider_validations_v16
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(ValidationRow::from)
        .optional();
  }

  private Optional<OverlayAttributionRow> loadOverlayAttribution(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash,
                   protocol_version,
                   btrim(requirement_hash)::text AS requirement_hash,
                   btrim(base_validation_policy_hash)::text
                     AS base_validation_policy_hash,
                   base_sequence,
                   btrim(base_head_hash)::text AS base_head_hash,
                   overlay_sequence, state_version, request_ordinal,
                   btrim(request_hash)::text AS request_hash,
                   btrim(response_hash)::text AS response_hash,
                   provider_actor, provider_id, provider_protocol,
                   provider_profile_id,
                   btrim(provider_profile_hash)::text AS provider_profile_hash,
                   btrim(base_execution_pricing_fingerprint)::text
                     AS base_execution_pricing_fingerprint,
                   btrim(transport_profile_hash)::text AS transport_profile_hash,
                   btrim(parser_profile_hash)::text AS parser_profile_hash,
                   btrim(schema_profile_hash)::text AS schema_profile_hash,
                   model_requested,
                   btrim(model_resolved_hash)::text AS model_resolved_hash,
                   btrim(model_resolution_profile_hash)::text
                     AS model_resolution_profile_hash,
                   pricing_profile_id, pricing_provider_id,
                   btrim(pricing_profile_fingerprint)::text
                     AS pricing_profile_fingerprint,
                   btrim(pricing_source_hash)::text AS pricing_source_hash,
                   rate_unit, uncached_input_pico_usd_per_token,
                   cached_input_pico_usd_per_token,
                   output_pico_usd_per_token, input_tokens,
                   cached_input_tokens, output_tokens,
                   reasoning_output_tokens, total_tokens,
                   observed_cost_pico_usd,
                   btrim(statement_hash)::text AS statement_hash,
                   decision_kind,
                   btrim(decision_hash)::text AS decision_hash,
                   failure_code,
                   btrim(attribution_hash)::text AS attribution_hash,
                   attributed_at_epoch_micros
            FROM public.agent_graph_exact_provider_attributions_v16
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(OverlayAttributionRow::from)
        .optional();
  }

  private Optional<OverlayEventRow> loadOverlayEvent(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash,
                   protocol_version,
                   btrim(requirement_hash)::text AS requirement_hash,
                   base_sequence,
                   btrim(base_head_hash)::text AS base_head_hash,
                   sequence, event_type, occurred_at_epoch_micros,
                   request_ordinal,
                   btrim(previous_head_hash)::text AS previous_head_hash,
                   btrim(evidence_hash)::text AS evidence_hash,
                   btrim(statement_hash)::text AS statement_hash,
                   btrim(event_hash)::text AS event_hash,
                   btrim(current_head_hash)::text AS current_head_hash
            FROM public.agent_graph_exact_attempt_events_v16
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(OverlayEventRow::from)
        .optional();
  }

  private Optional<OverlayHeadRow> loadOverlayHead(
      GraphAttemptManifest expected) {
    return jdbc.sql(
            """
            SELECT btrim(manifest_hash)::text AS manifest_hash,
                   protocol_version,
                   btrim(requirement_hash)::text AS requirement_hash,
                   base_sequence,
                   btrim(base_head_hash)::text AS base_head_hash,
                   state_version, last_sequence, phase,
                   attribution_status, request_ordinal,
                   base_provider_attribution_count,
                   overlay_provider_attribution_count,
                   effective_provider_attribution_count,
                   btrim(statement_hash)::text AS statement_hash,
                   btrim(attribution_hash)::text AS attribution_hash,
                   btrim(last_event_hash)::text AS last_event_hash,
                   btrim(head_hash)::text AS head_hash,
                   updated_at_epoch_micros
            FROM public.agent_graph_exact_attempt_heads_v16
            WHERE principal_id = :principalId AND attempt_id = :attemptId
            """)
        .param("principalId", expected.principalId())
        .param("attemptId", expected.attemptId())
        .query(OverlayHeadRow::from)
        .optional();
  }

  private RuntimeIdentity requireAuthority() {
    try {
      jdbc.sql("SET LOCAL search_path = pg_catalog, public, pg_temp").update();
      RuntimeIdentity identity =
          jdbc.sql(
                  """
                  SELECT session_user AS role_name,
                         current_database() AS database_name,
                         database.oid::bigint AS database_oid,
                         namespace.oid::bigint AS schema_oid,
                         reader.oid::bigint AS reader_role_oid,
                         v13_role.oid::bigint AS v13_role_oid,
                         v15_role.oid::bigint AS v15_role_oid,
                         v16_role.oid::bigint AS v16_role_oid,
                         COALESCE(pg_catalog.inet_server_addr()::text,
                                  'local-socket') AS server_address,
                         COALESCE(pg_catalog.inet_server_port(), -1)
                           AS server_port,
                         current_setting('transaction_read_only') = 'on'
                           AND current_setting('transaction_isolation') =
                             'repeatable read'
                           AND session_user = :readerRole
                           AND current_user = session_user
                           AND reader.rolcanlogin
                           AND NOT reader.rolinherit
                           AND NOT reader.rolsuper
                           AND NOT reader.rolcreatedb
                           AND NOT reader.rolcreaterole
                           AND NOT reader.rolreplication
                           AND NOT reader.rolbypassrls
                           AND NOT EXISTS (
                             SELECT 1 FROM pg_catalog.pg_auth_members membership
                             WHERE membership.member = reader.oid
                                OR membership.roleid = reader.oid)
                           AND NOT owner_role.rolcanlogin
                           AND NOT owner_role.rolinherit
                           AND NOT owner_role.rolsuper
                           AND NOT owner_role.rolcreatedb
                           AND NOT owner_role.rolcreaterole
                           AND NOT owner_role.rolreplication
                           AND NOT owner_role.rolbypassrls
                           AND NOT EXISTS (
                             SELECT 1 FROM pg_catalog.pg_auth_members membership
                             WHERE membership.member = owner_role.oid
                                OR membership.roleid = owner_role.oid)
                           AND v13_role.rolcanlogin
                           AND NOT v13_role.rolinherit
                           AND NOT v13_role.rolsuper
                           AND NOT v13_role.rolcreatedb
                           AND NOT v13_role.rolcreaterole
                           AND NOT v13_role.rolreplication
                           AND NOT v13_role.rolbypassrls
                           AND v15_role.rolcanlogin
                           AND NOT v15_role.rolinherit
                           AND NOT v15_role.rolsuper
                           AND NOT v15_role.rolcreatedb
                           AND NOT v15_role.rolcreaterole
                           AND NOT v15_role.rolreplication
                           AND NOT v15_role.rolbypassrls
                           AND v16_role.rolcanlogin
                           AND NOT v16_role.rolinherit
                           AND NOT v16_role.rolsuper
                           AND NOT v16_role.rolcreatedb
                           AND NOT v16_role.rolcreaterole
                           AND NOT v16_role.rolreplication
                           AND NOT v16_role.rolbypassrls
                           AND NOT EXISTS (
                             SELECT 1 FROM pg_catalog.pg_auth_members membership
                             WHERE membership.member IN (
                                     v13_role.oid, v15_role.oid, v16_role.oid)
                                OR membership.roleid IN (
                                     v13_role.oid, v15_role.oid, v16_role.oid))
                           AND pg_catalog.has_database_privilege(
                             reader.oid, database.oid, 'CONNECT')
                           AND NOT pg_catalog.has_database_privilege(
                             reader.oid, database.oid,
                             'CREATE,TEMPORARY,CONNECT WITH GRANT OPTION')
                           AND pg_catalog.has_schema_privilege(
                             reader.oid, namespace.oid, 'USAGE')
                           AND NOT pg_catalog.has_schema_privilege(
                             reader.oid, namespace.oid,
                             'CREATE,USAGE WITH GRANT OPTION')
                           AND namespace.nspowner = owner_role.oid AS exact
                  FROM pg_catalog.pg_database database
                  CROSS JOIN pg_catalog.pg_namespace namespace
                  CROSS JOIN pg_catalog.pg_roles reader
                  CROSS JOIN pg_catalog.pg_roles owner_role
                  CROSS JOIN pg_catalog.pg_roles v13_role
                  CROSS JOIN pg_catalog.pg_roles v15_role
                  CROSS JOIN pg_catalog.pg_roles v16_role
                  WHERE database.datname = current_database()
                    AND namespace.nspname = 'public'
                    AND reader.rolname = :readerRole
                    AND owner_role.rolname = :schemaOwner
                    AND v13_role.rolname = 'emergeos_provider_attestor'
                    AND v15_role.rolname = 'emergeos_provider_attestor_v15'
                    AND v16_role.rolname = 'emergeos_provider_attestor_v16'
                  """)
              .param("readerRole", READER_ROLE)
              .param("schemaOwner", SCHEMA_OWNER)
              .query(RuntimeIdentity::from)
              .single();
      if (!identity.exact()
          || !authorityRelationsExact()
          || !globalAclExact()
          || !catalogShapeExact()) {
        throw new GraphAttemptIntegrityException();
      }
      return identity;
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private boolean authorityRelationsExact() {
    String expected = "'" + RELATION_NAMES_SQL + "'";
    Boolean exact =
        jdbc.sql(
                """
                SELECT
                  (SELECT pg_catalog.array_agg(relation.relname::text
                                               ORDER BY relation.relname)
                   FROM pg_catalog.pg_class relation
                   JOIN pg_catalog.pg_namespace namespace
                     ON namespace.oid = relation.relnamespace
                   WHERE namespace.nspname = 'public'
                     AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                     AND pg_catalog.has_table_privilege(
                       :readerRole, relation.oid, 'SELECT')) =
                  (SELECT pg_catalog.array_agg(name ORDER BY name)
                   FROM pg_catalog.unnest(ARRAY["""
                    + expected
                    + """
                   ]::text[]) name)
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                      AND pg_catalog.has_table_privilege(
                        :readerRole, relation.oid,
                        'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'))
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = relation.relnamespace
                    WHERE namespace.nspname = 'public'
                      AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                      AND pg_catalog.has_table_privilege(
                        :readerRole, relation.oid,
                        'SELECT WITH GRANT OPTION'))
                  AND NOT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_sequences sequence_row
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.nspname = sequence_row.schemaname
                    JOIN pg_catalog.pg_class relation
                      ON relation.relnamespace = namespace.oid
                     AND relation.relname = sequence_row.sequencename
                    WHERE sequence_row.schemaname = 'public'
                      AND pg_catalog.has_sequence_privilege(
                        :readerRole, relation.oid, 'USAGE,SELECT,UPDATE'))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_namespace namespace
                      ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = 'public'
                      AND pg_catalog.has_function_privilege(
                        :readerRole, procedure.oid, 'EXECUTE'))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_namespace namespace
                    CROSS JOIN pg_catalog.pg_roles reader
                    WHERE reader.rolname = :readerRole
                      AND namespace.nspname <> 'public'
                      AND namespace.nspname <> 'information_schema'
                      AND namespace.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                      AND (namespace.nspowner = reader.oid
                           OR pg_catalog.has_schema_privilege(
                             reader.oid, namespace.oid, 'USAGE,CREATE')))
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_class relation
                    JOIN pg_catalog.pg_roles reader
                      ON reader.rolname = :readerRole
                    WHERE relation.relowner = reader.oid)
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_proc procedure
                    JOIN pg_catalog.pg_roles reader
                      ON reader.rolname = :readerRole
                    WHERE procedure.proowner = reader.oid) AS exact
                """)
            .param("readerRole", READER_ROLE)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exact);
  }

  private boolean globalAclExact() {
    String names = "'" + RELATION_NAMES_SQL + "'";
    String sql =
        """
        SELECT count(*) = 60
          AND pg_catalog.bool_and(
            NOT acl.is_grantable
            AND CASE
              WHEN grantee.rolname = 'emergeos_terminal_owner' THEN
                (relation.relname IN (
                   'agent_graph_attempts', 'agent_graph_attempt_heads',
                   'agent_graph_attempt_events',
                   'agent_graph_attempt_provider_attributions',
                   'agent_graph_provider_session_intents',
                   'agent_graph_provider_validations')
                 AND acl.privilege_type IN ('SELECT', 'INSERT', 'UPDATE'))
                OR (relation.relname =
                      'agent_graph_exact_tx_a_requirements_v15'
                    AND acl.privilege_type IN ('SELECT', 'INSERT'))
                OR (relation.relname IN (
                      'agent_graph_provider_validation_keys',
                      'agent_graph_provider_profiles_v14')
                    AND acl.privilege_type = 'SELECT')
                OR (relation.relname =
                      'agent_graph_exact_provider_validations_v16'
                    AND acl.privilege_type IN
                      ('SELECT', 'INSERT', 'UPDATE'))
                OR (relation.relname IN (
                      'agent_graph_exact_provider_attributions_v16',
                      'agent_graph_exact_attempt_events_v16',
                      'agent_graph_exact_attempt_heads_v16')
                    AND acl.privilege_type IN ('SELECT', 'INSERT'))
              WHEN grantee.rolname = 'emergeos_graph_prefix_writer' THEN
                relation.relname IN (
                  'agent_graph_attempts', 'agent_graph_attempt_heads',
                  'agent_graph_attempt_events',
                  'agent_graph_attempt_provider_attributions',
                  'agent_graph_provider_session_intents')
                AND (acl.privilege_type IN ('SELECT', 'INSERT')
                     OR (relation.relname = 'agent_graph_attempt_heads'
                         AND acl.privilege_type = 'UPDATE'))
              WHEN grantee.rolname = 'emergeos_graph_reader' THEN
                relation.relname IN (
                  'agent_graph_attempts', 'agent_graph_attempt_heads',
                  'agent_graph_attempt_events',
                  'agent_graph_attempt_provider_attributions',
                  'agent_graph_provider_session_intents')
                AND acl.privilege_type = 'SELECT'
              WHEN grantee.rolname = :readerRole THEN
                acl.privilege_type = 'SELECT'
              ELSE false
            END)
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace namespace
          ON namespace.oid = relation.relnamespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(
          COALESCE(relation.relacl,
                   pg_catalog.acldefault('r', relation.relowner))) acl
        LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
        WHERE namespace.nspname = 'public'
          AND relation.relkind IN ('r', 'p')
          AND relation.relname IN ("""
        + names
        + """
          )
          AND acl.grantee <> relation.relowner
          AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_attribute attribute
            WHERE attribute.attrelid = relation.oid
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
              AND attribute.attacl IS NOT NULL)
        """;
    Boolean exact =
        jdbc.sql(sql)
            .param("readerRole", READER_ROLE)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exact);
  }

  private boolean catalogShapeExact() {
    String names = "'" + RELATION_NAMES_SQL + "'";
    String sql =
        """
        SELECT
          (SELECT count(*) = 354
             AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
               pg_catalog.string_agg(pg_catalog.concat_ws('|',
                 shape.relname, shape.attnum::text, shape.attname,
                 shape.type_name, shape.attnotnull::text,
                 shape.attidentity::text, shape.attgenerated::text,
                 shape.default_expr, shape.collation_name),
                 ',' ORDER BY shape.relname, shape.attnum), 'UTF8')), 'hex')
                 = :columnHash
           FROM (
             SELECT relation.relname, attribute.attnum, attribute.attname,
                    pg_catalog.format_type(attribute.atttypid,
                                           attribute.atttypmod) AS type_name,
                    attribute.attnotnull, attribute.attidentity,
                    attribute.attgenerated,
                    COALESCE(pg_catalog.pg_get_expr(default_value.adbin,
                                                    default_value.adrelid), '')
                      AS default_expr,
                    COALESCE(collation_row.collname, '') AS collation_name
             FROM pg_catalog.pg_attribute attribute
             JOIN pg_catalog.pg_class relation
               ON relation.oid = attribute.attrelid
             JOIN pg_catalog.pg_namespace namespace
               ON namespace.oid = relation.relnamespace
             LEFT JOIN pg_catalog.pg_attrdef default_value
               ON default_value.adrelid = attribute.attrelid
              AND default_value.adnum = attribute.attnum
             LEFT JOIN pg_catalog.pg_collation collation_row
               ON collation_row.oid = attribute.attcollation
             WHERE namespace.nspname = 'public'
               AND relation.relname IN ("""
        + names
        + """
               ) AND attribute.attnum > 0 AND NOT attribute.attisdropped
           ) shape)
          AND
          (SELECT count(*) = 430
             AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
               pg_catalog.string_agg(pg_catalog.concat_ws('|',
                 relation.relname, constraint_row.conname,
                 constraint_row.contype::text,
                 constraint_row.condeferrable::text,
                 constraint_row.condeferred::text,
                 constraint_row.convalidated::text,
                 pg_catalog.pg_get_constraintdef(constraint_row.oid, true)),
                 ',' ORDER BY relation.relname, constraint_row.conname),
                 'UTF8')), 'hex') = :constraintHash
           FROM pg_catalog.pg_constraint constraint_row
           JOIN pg_catalog.pg_class relation
             ON relation.oid = constraint_row.conrelid
           JOIN pg_catalog.pg_namespace namespace
             ON namespace.oid = relation.relnamespace
           WHERE namespace.nspname = 'public'
             AND relation.relname IN ("""
        + names
        + """
             ))
          AND
          (SELECT count(*) = 38
             AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
               pg_catalog.string_agg(pg_catalog.concat_ws('|',
                 relation.relname, index_relation.relname,
                 index_row.indisunique::text, index_row.indisprimary::text,
                 index_row.indisvalid::text, index_row.indisready::text,
                 pg_catalog.pg_get_indexdef(index_relation.oid)),
                 ',' ORDER BY relation.relname, index_relation.relname),
                 'UTF8')), 'hex') = :indexHash
           FROM pg_catalog.pg_index index_row
           JOIN pg_catalog.pg_class index_relation
             ON index_relation.oid = index_row.indexrelid
           JOIN pg_catalog.pg_class relation
             ON relation.oid = index_row.indrelid
           JOIN pg_catalog.pg_namespace namespace
             ON namespace.oid = relation.relnamespace
           WHERE namespace.nspname = 'public'
             AND relation.relname IN ("""
        + names
        + """
             ))
          AND
          (SELECT count(*) = 13
             AND pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
               pg_catalog.string_agg(pg_catalog.concat_ws('|',
                 relation.relname, relation.relkind::text,
                 relation.relispartition::text,
                 relation.relpersistence::text,
                 relation.relrowsecurity::text,
                 relation.relforcerowsecurity::text,
                 relation.relreplident::text),
                 ',' ORDER BY relation.relname), 'UTF8')), 'hex')
                 = :relationHash
           FROM pg_catalog.pg_class relation
           JOIN pg_catalog.pg_namespace namespace
             ON namespace.oid = relation.relnamespace
           JOIN pg_catalog.pg_roles owner
             ON owner.oid = relation.relowner
           WHERE namespace.nspname = 'public'
             AND owner.rolname = :schemaOwner
             AND relation.relname IN ("""
        + names
        + """
             ))
          AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_inherits inheritance
            JOIN pg_catalog.pg_class child ON child.oid = inheritance.inhrelid
            JOIN pg_catalog.pg_class parent ON parent.oid = inheritance.inhparent
            JOIN pg_catalog.pg_namespace child_namespace
              ON child_namespace.oid = child.relnamespace
            JOIN pg_catalog.pg_namespace parent_namespace
              ON parent_namespace.oid = parent.relnamespace
            WHERE (child_namespace.nspname = 'public'
                   AND child.relname IN ("""
        + names
        + ")) OR (parent_namespace.nspname = 'public' AND parent.relname IN ("
        + names
        + """
             )))
          AND NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_rewrite rewrite
            JOIN pg_catalog.pg_class relation ON relation.oid = rewrite.ev_class
            JOIN pg_catalog.pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = 'public'
              AND relation.relname IN ("""
        + names
        + """
             )) AS exact
        """;
    Boolean exact =
        jdbc.sql(sql)
            .param("columnHash", COLUMN_SHAPE_HASH)
            .param("constraintHash", CONSTRAINT_SHAPE_HASH)
            .param("indexHash", INDEX_SHAPE_HASH)
            .param("relationHash", RELATION_SHAPE_HASH)
            .param("schemaOwner", SCHEMA_OWNER)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exact);
  }

  private void requireFrozenAuthority() {
    if (!frozen.equals(requireAuthority())) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private <T> T execute(Supplier<T> operation) {
    try {
      return Objects.requireNonNull(
          transactions.execute(ignored -> operation.get()),
          "exact overlay reader transaction result");
    } catch (GraphAttemptIntegrityException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw new GraphAttemptIntegrityException();
    } catch (RuntimeException failure) {
      throw new GraphAttemptIntegrityException();
    }
  }

  private static GraphExactPicoOverlayVerification.Invalid invalid(
      GraphExactPicoOverlayVerification.InvalidReason reason) {
    return new GraphExactPicoOverlayVerification.Invalid(reason);
  }

  private static int count(Object... rows) {
    int count = 0;
    for (Object row : rows) {
      if (row != null) {
        count++;
      }
    }
    return count;
  }

  private static Instant instant(long epochMicros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(epochMicros, 1_000_000L),
        Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
  }

  private static long micros(Instant instant) {
    long seconds = Math.multiplyExact(instant.getEpochSecond(), 1_000_000L);
    if (instant.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException("instant is not microsecond exact");
    }
    return Math.addExact(seconds, instant.getNano() / 1_000L);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static String framedHash(String domain, Object... fields) {
    return sha256(frame(domain, fields));
  }

  private static byte[] frame(String domain, Object... fields) {
    byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
    int size = domainBytes.length + 1;
    List<byte[]> encoded = new ArrayList<>(fields.length);
    for (Object field : fields) {
      if (field == null) {
        throw new IllegalArgumentException("canonical field is missing");
      }
      byte[] bytes = field.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 4096) {
        throw new IllegalArgumentException("canonical field is oversized");
      }
      encoded.add(bytes);
      size = Math.addExact(size, Math.addExact(4, bytes.length));
    }
    ByteBuffer framed = ByteBuffer.allocate(size);
    framed.put(domainBytes).put((byte) 0);
    for (byte[] bytes : encoded) {
      framed.putInt(bytes.length).put(bytes);
    }
    return framed.array();
  }

  private static String value(ResultSet row, String name) throws SQLException {
    return row.getString(name);
  }

  private static Instant timestamp(ResultSet row, String name)
      throws SQLException {
    Timestamp value = row.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }

  private static int nullableInt(ResultSet row, String name)
      throws SQLException {
    return row.getInt(name);
  }

  private record VerifiedBase(
      GraphExactPicoOverlayRequirement requirement,
      ProfileRow profile,
      SessionRow session) {}

  private record RuntimeIdentity(
      String roleName,
      String databaseName,
      long databaseOid,
      long schemaOid,
      long readerRoleOid,
      long v13RoleOid,
      long v15RoleOid,
      long v16RoleOid,
      String serverAddress,
      int serverPort,
      boolean exact) {

    private static RuntimeIdentity from(ResultSet row, int ignored)
        throws SQLException {
      return new RuntimeIdentity(
          row.getString("role_name"),
          row.getString("database_name"),
          row.getLong("database_oid"),
          row.getLong("schema_oid"),
          row.getLong("reader_role_oid"),
          row.getLong("v13_role_oid"),
          row.getLong("v15_role_oid"),
          row.getLong("v16_role_oid"),
          row.getString("server_address"),
          row.getInt("server_port"),
          row.getBoolean("exact"));
    }
  }

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

    private static ManifestRow from(ResultSet row, int ignored)
        throws SQLException {
      return new ManifestRow(
          value(row, "principal_id"),
          value(row, "attempt_id"),
          value(row, "execution_slot_id"),
          value(row, "manifest_schema_version"),
          value(row, "graph_protocol_version"),
          value(row, "integrity_profile"),
          value(row, "case_id"),
          value(row, "pack_raw_sha256"),
          value(row, "environment_raw_sha256"),
          value(row, "capture_id"),
          value(row, "capture_request_hash"),
          value(row, "artifact_id"),
          timestamp(row, "started_at"),
          value(row, "pricing_profile_fingerprint"),
          value(row, "prompt_surface_fingerprint"),
          value(row, "conductor_surface_fingerprint"),
          row.getBigDecimal("reservation_usd"),
          row.getInt("maximum_provider_requests"),
          value(row, "parent_actor"),
          value(row, "child_actor"),
          value(row, "experiment_arm"),
          row.getInt("experiment_repetition"),
          value(row, "manifest_json"),
          value(row, "manifest_hash"),
          value(row, "initial_head_hash"));
    }

    private boolean matches(GraphAttemptManifest manifest) {
      return principalId.equals(manifest.principalId())
          && attemptId.equals(manifest.attemptId())
          && executionSlotId.equals(manifest.executionSlotId())
          && schemaVersion.equals(manifest.schemaVersion())
          && graphProtocolVersion.equals(manifest.graphProtocolVersion())
          && integrityProfile.equals(manifest.integrityProfile())
          && caseId.equals(manifest.caseId())
          && packRawSha256.equals(manifest.packRawSha256())
          && environmentRawSha256.equals(manifest.environmentRawSha256())
          && captureId.equals(manifest.captureId())
          && captureRequestHash.equals(manifest.captureRequestHash())
          && artifactId.equals(manifest.artifactId())
          && startedAt.equals(manifest.startedAt())
          && pricingProfileFingerprint.equals(
              manifest.pricingProfileFingerprint())
          && promptSurfaceFingerprint.equals(
              manifest.promptSurfaceFingerprint())
          && conductorSurfaceFingerprint.equals(
              manifest.conductorSurfaceFingerprint())
          && reservationUsd.compareTo(manifest.reservationUsd()) == 0
          && maximumProviderRequests == manifest.maximumProviderRequests()
          && parentActor.equals(manifest.parentActor())
          && childActor.equals(manifest.childActor())
          && experimentArm.equals(manifest.experiment().arm())
          && experimentRepetition == manifest.experiment().repetition()
          && manifestHash.equals(manifest.manifestHash())
          && initialHeadHash.equals(
              GraphAttemptEvent.emptyHead(
                  manifest.attemptId(), manifest.manifestHash()));
    }
  }

  private record HeadRow(
      String manifestHash,
      String phase,
      long stateVersion,
      int lastSequence,
      String headHash,
      String billingStatus,
      int intentCount,
      int attributionCount) {

    private static HeadRow from(ResultSet row, int ignored)
        throws SQLException {
      return new HeadRow(
          value(row, "manifest_hash"),
          value(row, "phase"),
          row.getLong("state_version"),
          row.getInt("last_sequence"),
          value(row, "head_hash"),
          value(row, "billing_status"),
          row.getInt("provider_intent_count"),
          row.getInt("provider_attribution_count"));
    }

    private boolean isSequence13(GraphAttemptManifest expected) {
      return manifestHash.equals(expected.manifestHash())
          && "PROVIDER_PENDING".equals(phase)
          && stateVersion == 13L
          && lastSequence == 13
          && "UNKNOWN".equals(billingStatus)
          && intentCount == 2
          && attributionCount == 1;
    }
  }

  private record EventRow(
      String manifestHash,
      int sequence,
      String eventType,
      Instant occurredAt,
      String phaseFrom,
      String phaseTo,
      String role,
      String runId,
      String taskId,
      String actor,
      String challengeHash,
      Integer requestOrdinal,
      String requestHash,
      String modelRequested,
      String evidenceHash,
      String previousHeadHash,
      String eventHash,
      String currentHeadHash) {

    private static EventRow from(ResultSet row, int ignored)
        throws SQLException {
      return new EventRow(
          value(row, "manifest_hash"),
          row.getInt("sequence"),
          value(row, "event_type"),
          timestamp(row, "occurred_at"),
          value(row, "phase_from"),
          value(row, "phase_to"),
          value(row, "role"),
          value(row, "run_id"),
          value(row, "task_id"),
          value(row, "actor"),
          value(row, "challenge_hash"),
          row.getObject("request_ordinal", Integer.class),
          value(row, "request_hash"),
          value(row, "model_requested"),
          value(row, "evidence_hash"),
          value(row, "previous_head_hash"),
          value(row, "event_hash"),
          value(row, "current_head_hash"));
    }

    private GraphAttemptEvent toDomain() {
      return new GraphAttemptEvent(
          sequence,
          GraphAttemptEventType.valueOf(eventType),
          occurredAt,
          phaseFrom == null ? null : GraphAttemptPhase.valueOf(phaseFrom),
          GraphAttemptPhase.valueOf(phaseTo),
          role == null ? null : GraphRunRole.valueOf(role),
          runId,
          taskId,
          actor,
          challengeHash,
          requestOrdinal,
          requestHash,
          modelRequested,
          evidenceHash,
          previousHeadHash,
          eventHash,
          currentHeadHash);
    }
  }

  private record LegacyAttributionRow(
      String manifestHash,
      int requestOrdinal,
      int eventSequence,
      String requestHash,
      String responseHash,
      String providerActor,
      String modelRequested,
      String modelResolved,
      String pricingProfileId,
      String pricingProvider,
      String pricingProfileFingerprint,
      long uncachedRate,
      long cachedRate,
      long outputRate,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd,
      String attributionHash,
      Instant attributedAt) {

    private static LegacyAttributionRow from(ResultSet row, int ignored)
        throws SQLException {
      return new LegacyAttributionRow(
          value(row, "manifest_hash"),
          row.getInt("request_ordinal"),
          row.getInt("event_sequence"),
          value(row, "request_hash"),
          value(row, "response_hash"),
          value(row, "provider_actor"),
          value(row, "model_requested"),
          value(row, "model_resolved"),
          value(row, "pricing_profile_id"),
          value(row, "pricing_provider"),
          value(row, "pricing_profile_fingerprint"),
          row.getLong("uncached_input_nano_usd_per_token"),
          row.getLong("cached_input_nano_usd_per_token"),
          row.getLong("output_nano_usd_per_token"),
          row.getLong("input_tokens"),
          row.getLong("cached_input_tokens"),
          row.getLong("output_tokens"),
          row.getLong("reasoning_output_tokens"),
          row.getLong("total_tokens"),
          row.getBigDecimal("observed_cost_usd"),
          value(row, "attribution_hash"),
          timestamp(row, "attributed_at"));
    }

    private boolean matches(GraphAttemptManifest expected) {
      return manifestHash.equals(expected.manifestHash())
          && attributedAt != null;
    }

    private GraphProviderAttribution toDomain() {
      GraphPricingSnapshot pricing =
          new GraphPricingSnapshot(
              pricingProfileId,
              pricingProvider,
              modelRequested,
              uncachedRate,
              cachedRate,
              outputRate,
              pricingProfileFingerprint);
      return new GraphProviderAttribution(
          requestOrdinal,
          requestHash,
          responseHash,
          providerActor,
          modelRequested,
          modelResolved,
          pricing,
          inputTokens,
          cachedInputTokens,
          outputTokens,
          reasoningOutputTokens,
          totalTokens,
          observedCostUsd,
          attributionHash);
    }
  }

  private record SessionRow(
      String manifestHash,
      String revision,
      int cursorSequence,
      String cursorHeadHash,
      int requestOrdinal,
      String requestHash,
      String modelRequested,
      String intentHash,
      Instant createdAt,
      Instant expiresAt) {

    private static SessionRow from(ResultSet row, int ignored)
        throws SQLException {
      return new SessionRow(
          value(row, "manifest_hash"),
          value(row, "revision"),
          row.getInt("cursor_sequence"),
          value(row, "cursor_head_hash"),
          row.getInt("request_ordinal"),
          value(row, "request_hash"),
          value(row, "model_requested"),
          value(row, "intent_hash"),
          timestamp(row, "created_at"),
          timestamp(row, "expires_at"));
    }

    private boolean valid(
        GraphAttemptManifest expected,
        GraphAttemptEvent egress,
        GraphAttemptEvent requestOne) {
      String computed =
          IntegrityHashes.utf8ContentHash(
              String.join(
                  "\u001f",
                  "emergeos.provider-session-intent.v1",
                  expected.principalId(),
                  expected.attemptId(),
                  expected.manifestHash(),
                  revision,
                  egress.currentHeadHash(),
                  Integer.toString(requestOrdinal),
                  requestHash,
                  modelRequested,
                  expiresAt.toString()));
      return manifestHash.equals(expected.manifestHash())
          && cursorSequence == 7
          && cursorHeadHash.equals(egress.currentHeadHash())
          && requestOrdinal == 1
          && requestHash.equals(requestOne.requestHash())
          && modelRequested.equals(requestOne.modelRequested())
          && createdAt.isBefore(expiresAt)
          && computed.equals(intentHash)
          && micros(createdAt) > 0
          && micros(expiresAt) > 0;
    }

    private long expiresAtMicros() {
      return micros(expiresAt);
    }
  }

  private record RequirementRow(
      String protocolVersion,
      String databaseName,
      long databaseOid,
      long schemaOid,
      long attestorRoleOid,
      String manifestHash,
      int baseSequence,
      String baseHeadHash,
      String executionBindingHash,
      int requestOrdinal,
      String providerProfileId,
      String providerProfileHash,
      String state,
      String requirementHash,
      Instant requiredAt) {

    private static RequirementRow from(ResultSet row, int ignored)
        throws SQLException {
      return new RequirementRow(
          value(row, "protocol_version"),
          value(row, "database_name"),
          row.getLong("database_oid"),
          row.getLong("schema_oid"),
          row.getLong("attestor_role_oid"),
          value(row, "manifest_hash"),
          row.getInt("base_sequence"),
          value(row, "base_head_hash"),
          value(row, "execution_binding_hash"),
          row.getInt("request_ordinal"),
          value(row, "provider_profile_id"),
          value(row, "provider_profile_hash"),
          value(row, "state"),
          value(row, "requirement_hash"),
          timestamp(row, "required_at"));
    }

    private boolean valid(
        GraphAttemptManifest expected,
        HeadRow head,
        GraphAttemptEvent requestTwo,
        RuntimeIdentity runtime) {
      String computed =
          framedHash(
              REQUIREMENT_DOMAIN,
              protocolVersion,
              databaseName,
              databaseOid,
              schemaOid,
              attestorRoleOid,
              expected.principalId(),
              expected.attemptId(),
              manifestHash,
              baseSequence,
              baseHeadHash,
              executionBindingHash,
              requestOrdinal,
              providerProfileId,
              providerProfileHash,
              state);
      return GraphExactPicoProviderValidationChallenge.PROTOCOL_VERSION.equals(
              protocolVersion)
          && databaseName.equals(runtime.databaseName())
          && databaseOid == runtime.databaseOid()
          && schemaOid == runtime.schemaOid()
          && attestorRoleOid == runtime.v15RoleOid()
          && manifestHash.equals(expected.manifestHash())
          && baseSequence == 13
          && baseHeadHash.equals(head.headHash())
          && executionBindingHash.equals(expected.manifestHash())
          && requestOrdinal == 2
          && requestTwo.requestOrdinal() != null
          && requestTwo.requestOrdinal() == 2
          && "REQUIRED".equals(state)
          && requiredAt != null
          && micros(requiredAt) > 0
          && computed.equals(requirementHash);
    }

    private GraphExactPicoOverlayRequirement toDomain(
        GraphAttemptManifest expected, GraphAttemptEvent requestTwo) {
      return new GraphExactPicoOverlayRequirement(
          protocolVersion,
          expected.principalId(),
          expected.attemptId(),
          manifestHash,
          requirementHash,
          baseSequence,
          baseHeadHash,
          requestOrdinal,
          requestTwo.requestHash(),
          providerProfileId,
          providerProfileHash,
          requiredAt);
    }
  }

  private record ProfileRow(
      String profileId,
      String providerId,
      String providerProtocol,
      String transportProfileId,
      String transportProfileHash,
      String parserProfileId,
      String parserProfileHash,
      String schemaProfileId,
      String schemaProfileHash,
      String modelProfileId,
      String modelProfileHash,
      String modelRequested,
      String modelResolutionProfileHash,
      String pricingProfileId,
      String pricingProviderId,
      String pricingProfileFingerprint,
      String pricingSourceHash,
      long effectiveFromMicros,
      long effectiveUntilMicros,
      String rateUnit,
      long uncachedRate,
      long cachedRate,
      long outputRate,
      String reasoningPolicy,
      String profileHash,
      String status) {

    private static ProfileRow from(ResultSet row, int ignored)
        throws SQLException {
      return new ProfileRow(
          value(row, "profile_id"),
          value(row, "provider_id"),
          value(row, "provider_protocol"),
          value(row, "transport_profile_id"),
          value(row, "transport_profile_hash"),
          value(row, "parser_profile_id"),
          value(row, "parser_profile_hash"),
          value(row, "schema_profile_id"),
          value(row, "schema_profile_hash"),
          value(row, "model_profile_id"),
          value(row, "model_profile_hash"),
          value(row, "model_requested"),
          value(row, "model_resolution_profile_hash"),
          value(row, "pricing_profile_id"),
          value(row, "pricing_provider_id"),
          value(row, "pricing_profile_fingerprint"),
          value(row, "pricing_source_hash"),
          row.getLong("pricing_effective_from_epoch_micros"),
          row.getLong("pricing_effective_until_epoch_micros"),
          value(row, "rate_unit"),
          row.getLong("uncached_input_pico_usd_per_token"),
          row.getLong("cached_input_pico_usd_per_token"),
          row.getLong("output_pico_usd_per_token"),
          value(row, "reasoning_policy"),
          value(row, "profile_hash"),
          value(row, "status"));
    }

    private boolean valid(
        RequirementRow requirement, GraphAttemptEvent requestTwo) {
      String computed =
          framedHash(
              PROFILE_DOMAIN,
              profileId,
              providerId,
              providerProtocol,
              transportProfileId,
              transportProfileHash,
              parserProfileId,
              parserProfileHash,
              schemaProfileId,
              schemaProfileHash,
              modelProfileId,
              modelProfileHash,
              modelRequested,
              modelResolutionProfileHash,
              pricingProfileId,
              pricingProviderId,
              pricingProfileFingerprint,
              pricingSourceHash,
              effectiveFromMicros,
              effectiveUntilMicros,
              rateUnit,
              uncachedRate,
              cachedRate,
              outputRate,
              reasoningPolicy);
      return profileId.equals(requirement.providerProfileId())
          && profileHash.equals(requirement.providerProfileHash())
          && modelRequested.equals(requestTwo.modelRequested())
          && pricingProviderId.equals(providerId)
          && "PICO_USD_PER_TOKEN".equals(rateUnit)
          && "NONE".equals(reasoningPolicy)
          && effectiveFromMicros >= 0
          && effectiveUntilMicros > effectiveFromMicros
          && computed.equals(profileHash);
    }

    private boolean validHistorical(ValidationRow validation) {
      return validation.providerProfileId().equals(profileId)
          && validation.providerProfileHash().equals(profileHash)
          && validation.providerId().equals(providerId)
          && validation.providerProtocol().equals(providerProtocol)
          && validation.transportProfileHash().equals(transportProfileHash)
          && validation.parserProfileHash().equals(parserProfileHash)
          && validation.schemaProfileHash().equals(schemaProfileHash)
          && validation.modelRequested().equals(modelRequested)
          && validation
              .modelResolutionProfileHash()
              .equals(modelResolutionProfileHash)
          && validation.pricingProfileId().equals(pricingProfileId)
          && validation.pricingProviderId().equals(pricingProviderId)
          && validation
              .pricingProfileFingerprint()
              .equals(pricingProfileFingerprint)
          && validation.pricingSourceHash().equals(pricingSourceHash)
          && validation.uncachedRate() == uncachedRate
          && validation.cachedRate() == cachedRate
          && validation.outputRate() == outputRate
          && validation.validatedAtMicros() >= effectiveFromMicros
          && validation.issuedAtMicros() >= effectiveFromMicros
          && validation.issuedAtMicros() <= effectiveUntilMicros
          && validation.validatedAtMicros() <= effectiveUntilMicros
          && validation.expiresAtMicros() <= effectiveUntilMicros;
    }

    private Instant effectiveFrom() {
      return instant(effectiveFromMicros);
    }

    private Instant effectiveUntil() {
      return instant(effectiveUntilMicros);
    }
  }

  private record PolicyRow(
      String protocolVersion,
      String databaseName,
      long databaseOid,
      long schemaOid,
      long attestorRoleOid,
      String revision,
      String manifestHash,
      String sessionIntentHash,
      Instant sessionExpiresAt,
      int policySequence,
      String policyHeadHash,
      String keyId,
      String keyFingerprint,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      int challengeTtlMillis,
      String policyHash,
      String state,
      Instant policyCreatedAt) {

    private static PolicyRow from(ResultSet row, int ignored)
        throws SQLException {
      return new PolicyRow(
          value(row, "protocol_version"),
          value(row, "database_name"),
          row.getLong("database_oid"),
          row.getLong("schema_oid"),
          row.getLong("attestor_role_oid"),
          value(row, "revision"),
          value(row, "manifest_hash"),
          value(row, "session_intent_hash"),
          timestamp(row, "session_expires_at"),
          row.getInt("policy_sequence"),
          value(row, "policy_head_hash"),
          value(row, "key_id"),
          value(row, "key_fingerprint"),
          value(row, "transport_profile_hash"),
          value(row, "parser_profile_hash"),
          value(row, "schema_profile_hash"),
          row.getInt("challenge_ttl_millis"),
          value(row, "policy_hash"),
          value(row, "state"),
          timestamp(row, "policy_created_at"));
    }

    private boolean valid(
        GraphAttemptManifest expected,
        SessionRow session,
        GraphAttemptEvent eventSeven,
        RuntimeIdentity runtime,
        KeyRow key) {
      if (key == null) {
        return false;
      }
      String computed =
          framedHash(
              POLICY_DOMAIN,
              databaseName,
              databaseOid,
              schemaOid,
              attestorRoleOid,
              expected.principalId(),
              expected.attemptId(),
              manifestHash,
              revision,
              sessionIntentHash,
              micros(sessionExpiresAt),
              policySequence,
              policyHeadHash,
              keyId,
              keyFingerprint,
              transportProfileHash,
              parserProfileHash,
              schemaProfileHash);
      return "POSTGRES_ROLE_V1".equals(protocolVersion)
          && databaseName.equals(runtime.databaseName())
          && databaseOid == runtime.databaseOid()
          && schemaOid == runtime.schemaOid()
          && attestorRoleOid == runtime.v13RoleOid()
          && manifestHash.equals(expected.manifestHash())
          && revision.equals(session.revision())
          && sessionIntentHash.equals(session.intentHash())
          && sessionExpiresAt.equals(session.expiresAt())
          && policySequence == 7
          && policyHeadHash.equals(eventSeven.currentHeadHash())
          && key.keyIdMatches(keyId)
          && keyFingerprint.equals(key.keyFingerprint())
          && key.validPolicy(this)
          && challengeTtlMillis >= 100
          && challengeTtlMillis <= 30_000
          && "REQUIRED".equals(state)
          && policyCreatedAt != null
          && !policyCreatedAt.isBefore(key.notBefore())
          && !policyCreatedAt.isAfter(key.notAfter())
          && policyCreatedAt.isBefore(sessionExpiresAt)
          && computed.equals(policyHash);
    }
  }

  private record KeyRow(
      String keyId,
      String algorithm,
      byte[] publicKeyDer,
      String keyFingerprint,
      String status,
      Instant notBefore,
      Instant notAfter,
      Instant createdAt) {

    private static KeyRow from(ResultSet row, int ignored)
        throws SQLException {
      return new KeyRow(
          value(row, "key_id"),
          value(row, "algorithm"),
          row.getBytes("public_key_der"),
          value(row, "key_fingerprint"),
          value(row, "status"),
          timestamp(row, "not_before"),
          timestamp(row, "not_after"),
          timestamp(row, "created_at"));
    }

    private boolean keyIdMatches(String expected) {
      return keyId.equals(expected);
    }

    private boolean validHistorical(ValidationRow validation) {
      return validAnchor()
          && publicKeyDer != null
          && keyFingerprint.equals(validation.keyFingerprint())
          && createdAt != null
          && notBefore != null
          && notAfter != null
          && notAfter.isAfter(notBefore)
          && !createdAt.isAfter(notAfter)
          && validation.issuedAtMicros() >= micros(notBefore)
          && validation.validatedAtMicros() <= micros(notAfter)
          && validation.expiresAtMicros() <= micros(notAfter);
    }

    private boolean validPolicy(PolicyRow policy) {
      return validAnchor()
          && keyId.equals(policy.keyId())
          && keyFingerprint.equals(policy.keyFingerprint())
          && policy.policyCreatedAt() != null
          && !policy.policyCreatedAt().isBefore(notBefore)
          && !policy.policyCreatedAt().isAfter(notAfter);
    }

    private boolean validAnchor() {
      return "ED25519".equals(algorithm)
          && publicKeyDer != null
          && publicKeyDer.length >= 32
          && publicKeyDer.length <= 128
          && sha256(publicKeyDer).equals(keyFingerprint)
          && createdAt != null
          && notBefore != null
          && notAfter != null
          && notAfter.isAfter(notBefore)
          && !createdAt.isAfter(notAfter);
    }

    @Override
    public byte[] publicKeyDer() {
      return publicKeyDer == null ? null : publicKeyDer.clone();
    }
  }

  private record ValidationRow(
      String protocolVersion,
      String databaseName,
      long databaseOid,
      long schemaOid,
      long attestorRoleOid,
      String manifestHash,
      String requirementHash,
      String baseValidationPolicyHash,
      String revision,
      String sessionIntentHash,
      long sessionExpiresAtMicros,
      int baseSequence,
      String baseHeadHash,
      String executionBindingHash,
      int overlaySequence,
      long stateVersion,
      int requestOrdinal,
      String requestHash,
      String responseHash,
      String providerActor,
      String providerId,
      String providerProtocol,
      String providerProfileId,
      String providerProfileHash,
      String baseExecutionPricingFingerprint,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      String modelRequested,
      String modelResolvedHash,
      String modelResolutionProfileHash,
      String pricingProfileId,
      String pricingProviderId,
      String pricingProfileFingerprint,
      String pricingSourceHash,
      String rateUnit,
      long uncachedRate,
      long cachedRate,
      long outputRate,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCost,
      String statementHash,
      String decisionKind,
      String decisionHash,
      String failureCode,
      String attributionHash,
      String eventHash,
      String overlayHeadHash,
      String keyId,
      String keyFingerprint,
      UUID validationNonce,
      String challengeHash,
      String transcriptHash,
      String state,
      byte[] signature,
      String signatureHash,
      String validationReceiptHash,
      long issuedAtMicros,
      long expiresAtMicros,
      long validatedAtMicros,
      long consumedAtMicros) {

    private static ValidationRow from(ResultSet row, int ignored)
        throws SQLException {
      return new ValidationRow(
          value(row, "protocol_version"),
          value(row, "database_name"),
          row.getLong("database_oid"),
          row.getLong("schema_oid"),
          row.getLong("attestor_role_oid"),
          value(row, "manifest_hash"),
          value(row, "requirement_hash"),
          value(row, "base_validation_policy_hash"),
          value(row, "revision"),
          value(row, "session_intent_hash"),
          row.getLong("session_expires_at_epoch_micros"),
          row.getInt("base_sequence"),
          value(row, "base_head_hash"),
          value(row, "execution_binding_hash"),
          row.getInt("overlay_sequence"),
          row.getLong("state_version"),
          row.getInt("request_ordinal"),
          value(row, "request_hash"),
          value(row, "response_hash"),
          value(row, "provider_actor"),
          value(row, "provider_id"),
          value(row, "provider_protocol"),
          value(row, "provider_profile_id"),
          value(row, "provider_profile_hash"),
          value(row, "base_execution_pricing_fingerprint"),
          value(row, "transport_profile_hash"),
          value(row, "parser_profile_hash"),
          value(row, "schema_profile_hash"),
          value(row, "model_requested"),
          value(row, "model_resolved_hash"),
          value(row, "model_resolution_profile_hash"),
          value(row, "pricing_profile_id"),
          value(row, "pricing_provider_id"),
          value(row, "pricing_profile_fingerprint"),
          value(row, "pricing_source_hash"),
          value(row, "rate_unit"),
          row.getLong("uncached_input_pico_usd_per_token"),
          row.getLong("cached_input_pico_usd_per_token"),
          row.getLong("output_pico_usd_per_token"),
          row.getLong("input_tokens"),
          row.getLong("cached_input_tokens"),
          row.getLong("output_tokens"),
          row.getLong("reasoning_output_tokens"),
          row.getLong("total_tokens"),
          row.getBigDecimal("observed_cost_pico_usd"),
          value(row, "statement_hash"),
          value(row, "decision_kind"),
          value(row, "decision_hash"),
          value(row, "failure_code"),
          value(row, "attribution_hash"),
          value(row, "event_hash"),
          value(row, "overlay_head_hash"),
          value(row, "key_id"),
          value(row, "key_fingerprint"),
          row.getObject("validation_nonce", UUID.class),
          value(row, "challenge_hash"),
          value(row, "transcript_hash"),
          value(row, "state"),
          row.getBytes("signature"),
          value(row, "signature_hash"),
          value(row, "validation_receipt_hash"),
          row.getLong("issued_at_epoch_micros"),
          row.getLong("expires_at_epoch_micros"),
          row.getLong("validated_at_epoch_micros"),
          row.getLong("consumed_at_epoch_micros"));
    }

    private boolean matches(
        GraphAttemptManifest expected,
        VerifiedBase base,
        PolicyRow policy,
        KeyRow key,
        RuntimeIdentity runtime) {
      return GraphExactPicoProviderValidationChallenge.PROTOCOL_VERSION.equals(
              protocolVersion)
          && databaseName.equals(runtime.databaseName())
          && databaseOid == runtime.databaseOid()
          && schemaOid == runtime.schemaOid()
          && attestorRoleOid == runtime.v16RoleOid()
          && manifestHash.equals(expected.manifestHash())
          && requirementHash.equals(base.requirement().requirementHash())
          && baseValidationPolicyHash.equals(policy.policyHash())
          && revision.equals(policy.revision())
          && sessionIntentHash.equals(policy.sessionIntentHash())
          && sessionExpiresAtMicros == micros(policy.sessionExpiresAt())
          && baseSequence == base.requirement().baseSequence()
          && baseHeadHash.equals(base.requirement().baseHeadHash())
          && executionBindingHash.equals(expected.manifestHash())
          && overlaySequence == 14
          && stateVersion == 1L
          && requestOrdinal == base.requirement().requestOrdinal()
          && requestHash.equals(base.requirement().requestHash())
          && baseExecutionPricingFingerprint.equals(
              expected.pricingProfileFingerprint())
          && keyId.equals(policy.keyId())
          && key.keyIdMatches(keyId)
          && keyFingerprint.equals(policy.keyFingerprint())
          && "PICO_USD_PER_TOKEN".equals(rateUnit)
          && "STRUCTURED_FINAL".equals(decisionKind)
          && failureCode == null
          && "CONSUMED".equals(state)
          && signature != null
          && signature.length == 64
          && signatureHash != null
          && validationReceiptHash != null
          && validationNonce != null
          && issuedAtMicros > 0
          && expiresAtMicros > issuedAtMicros
          && expiresAtMicros < sessionExpiresAtMicros
          && validatedAtMicros >= issuedAtMicros
          && validatedAtMicros < expiresAtMicros
          && consumedAtMicros == validatedAtMicros;
    }

    private GraphExactPicoOverlayAttribution toAttribution() {
      return new GraphExactPicoOverlayAttribution(
          responseHash,
          providerActor,
          providerId,
          providerProtocol,
          providerProfileId,
          providerProfileHash,
          baseExecutionPricingFingerprint,
          transportProfileHash,
          parserProfileHash,
          schemaProfileHash,
          modelRequested,
          modelResolvedHash,
          modelResolutionProfileHash,
          pricingProfileId,
          pricingProviderId,
          pricingProfileFingerprint,
          pricingSourceHash,
          uncachedRate,
          cachedRate,
          outputRate,
          inputTokens,
          cachedInputTokens,
          outputTokens,
          reasoningOutputTokens,
          totalTokens,
          observedCost.toBigIntegerExact(),
          GraphProviderValidationDecision.valueOf(decisionKind),
          decisionHash,
          instant(issuedAtMicros));
    }

    private GraphExactPicoProviderValidationChallenge toChallenge(
        GraphAttemptManifest expected) {
      return new GraphExactPicoProviderValidationChallenge(
          protocolVersion,
          databaseName,
          databaseOid,
          schemaOid,
          attestorRoleOid,
          expected.principalId(),
          expected.attemptId(),
          manifestHash,
          requirementHash,
          baseValidationPolicyHash,
          keyId,
          keyFingerprint,
          validationNonce,
          instant(issuedAtMicros),
          instant(expiresAtMicros),
          statementHash,
          attributionHash,
          eventHash,
          overlayHeadHash,
          GraphProviderValidationDecision.valueOf(decisionKind),
          decisionHash,
          null,
          challengeHash,
          transcriptHash);
    }

    @Override
    public byte[] signature() {
      return signature == null ? null : signature.clone();
    }
  }

  private record OverlayAttributionRow(
      String manifestHash,
      String protocolVersion,
      String requirementHash,
      String baseValidationPolicyHash,
      int baseSequence,
      String baseHeadHash,
      int overlaySequence,
      long stateVersion,
      int requestOrdinal,
      String requestHash,
      String responseHash,
      String providerActor,
      String providerId,
      String providerProtocol,
      String providerProfileId,
      String providerProfileHash,
      String baseExecutionPricingFingerprint,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      String modelRequested,
      String modelResolvedHash,
      String modelResolutionProfileHash,
      String pricingProfileId,
      String pricingProviderId,
      String pricingProfileFingerprint,
      String pricingSourceHash,
      String rateUnit,
      long uncachedRate,
      long cachedRate,
      long outputRate,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCost,
      String statementHash,
      String decisionKind,
      String decisionHash,
      String failureCode,
      String attributionHash,
      long attributedAtMicros) {

    private static OverlayAttributionRow from(ResultSet row, int ignored)
        throws SQLException {
      return new OverlayAttributionRow(
          value(row, "manifest_hash"),
          value(row, "protocol_version"),
          value(row, "requirement_hash"),
          value(row, "base_validation_policy_hash"),
          row.getInt("base_sequence"),
          value(row, "base_head_hash"),
          row.getInt("overlay_sequence"),
          row.getLong("state_version"),
          row.getInt("request_ordinal"),
          value(row, "request_hash"),
          value(row, "response_hash"),
          value(row, "provider_actor"),
          value(row, "provider_id"),
          value(row, "provider_protocol"),
          value(row, "provider_profile_id"),
          value(row, "provider_profile_hash"),
          value(row, "base_execution_pricing_fingerprint"),
          value(row, "transport_profile_hash"),
          value(row, "parser_profile_hash"),
          value(row, "schema_profile_hash"),
          value(row, "model_requested"),
          value(row, "model_resolved_hash"),
          value(row, "model_resolution_profile_hash"),
          value(row, "pricing_profile_id"),
          value(row, "pricing_provider_id"),
          value(row, "pricing_profile_fingerprint"),
          value(row, "pricing_source_hash"),
          value(row, "rate_unit"),
          row.getLong("uncached_input_pico_usd_per_token"),
          row.getLong("cached_input_pico_usd_per_token"),
          row.getLong("output_pico_usd_per_token"),
          row.getLong("input_tokens"),
          row.getLong("cached_input_tokens"),
          row.getLong("output_tokens"),
          row.getLong("reasoning_output_tokens"),
          row.getLong("total_tokens"),
          row.getBigDecimal("observed_cost_pico_usd"),
          value(row, "statement_hash"),
          value(row, "decision_kind"),
          value(row, "decision_hash"),
          value(row, "failure_code"),
          value(row, "attribution_hash"),
          row.getLong("attributed_at_epoch_micros"));
    }

    private boolean matches(ValidationRow validation) {
      return manifestHash.equals(validation.manifestHash())
          && protocolVersion.equals(validation.protocolVersion())
          && requirementHash.equals(validation.requirementHash())
          && baseValidationPolicyHash.equals(
              validation.baseValidationPolicyHash())
          && baseSequence == validation.baseSequence()
          && baseHeadHash.equals(validation.baseHeadHash())
          && overlaySequence == validation.overlaySequence()
          && stateVersion == validation.stateVersion()
          && requestOrdinal == validation.requestOrdinal()
          && requestHash.equals(validation.requestHash())
          && responseHash.equals(validation.responseHash())
          && providerActor.equals(validation.providerActor())
          && providerId.equals(validation.providerId())
          && providerProtocol.equals(validation.providerProtocol())
          && providerProfileId.equals(validation.providerProfileId())
          && providerProfileHash.equals(validation.providerProfileHash())
          && baseExecutionPricingFingerprint.equals(
              validation.baseExecutionPricingFingerprint())
          && transportProfileHash.equals(validation.transportProfileHash())
          && parserProfileHash.equals(validation.parserProfileHash())
          && schemaProfileHash.equals(validation.schemaProfileHash())
          && modelRequested.equals(validation.modelRequested())
          && modelResolvedHash.equals(validation.modelResolvedHash())
          && modelResolutionProfileHash.equals(
              validation.modelResolutionProfileHash())
          && pricingProfileId.equals(validation.pricingProfileId())
          && pricingProviderId.equals(validation.pricingProviderId())
          && pricingProfileFingerprint.equals(
              validation.pricingProfileFingerprint())
          && pricingSourceHash.equals(validation.pricingSourceHash())
          && rateUnit.equals(validation.rateUnit())
          && uncachedRate == validation.uncachedRate()
          && cachedRate == validation.cachedRate()
          && outputRate == validation.outputRate()
          && inputTokens == validation.inputTokens()
          && cachedInputTokens == validation.cachedInputTokens()
          && outputTokens == validation.outputTokens()
          && reasoningOutputTokens == validation.reasoningOutputTokens()
          && totalTokens == validation.totalTokens()
          && observedCost.compareTo(validation.observedCost()) == 0
          && statementHash.equals(validation.statementHash())
          && decisionKind.equals(validation.decisionKind())
          && decisionHash.equals(validation.decisionHash())
          && failureCode == null
          && validation.failureCode() == null
          && attributionHash.equals(validation.attributionHash())
          && attributedAtMicros == validation.issuedAtMicros();
    }
  }

  private record OverlayEventRow(
      String manifestHash,
      String protocolVersion,
      String requirementHash,
      int baseSequence,
      String baseHeadHash,
      int sequence,
      String eventType,
      long occurredAtMicros,
      int requestOrdinal,
      String previousHeadHash,
      String evidenceHash,
      String statementHash,
      String eventHash,
      String currentHeadHash) {

    private static OverlayEventRow from(ResultSet row, int ignored)
        throws SQLException {
      return new OverlayEventRow(
          value(row, "manifest_hash"),
          value(row, "protocol_version"),
          value(row, "requirement_hash"),
          row.getInt("base_sequence"),
          value(row, "base_head_hash"),
          row.getInt("sequence"),
          value(row, "event_type"),
          row.getLong("occurred_at_epoch_micros"),
          row.getInt("request_ordinal"),
          value(row, "previous_head_hash"),
          value(row, "evidence_hash"),
          value(row, "statement_hash"),
          value(row, "event_hash"),
          value(row, "current_head_hash"));
    }

    private boolean matches(
        ValidationRow validation, OverlayAttributionRow attribution) {
      return manifestHash.equals(validation.manifestHash())
          && protocolVersion.equals(validation.protocolVersion())
          && requirementHash.equals(validation.requirementHash())
          && baseSequence == validation.baseSequence()
          && baseHeadHash.equals(validation.baseHeadHash())
          && sequence == 14
          && "EXACT_PROVIDER_ATTRIBUTED".equals(eventType)
          && occurredAtMicros == validation.issuedAtMicros()
          && requestOrdinal == validation.requestOrdinal()
          && previousHeadHash.equals(validation.baseHeadHash())
          && evidenceHash.equals(attribution.attributionHash())
          && statementHash.equals(validation.statementHash())
          && eventHash.equals(validation.eventHash())
          && currentHeadHash.equals(validation.overlayHeadHash());
    }
  }

  private record OverlayHeadRow(
      String manifestHash,
      String protocolVersion,
      String requirementHash,
      int baseSequence,
      String baseHeadHash,
      long stateVersion,
      int lastSequence,
      String phase,
      String attributionStatus,
      int requestOrdinal,
      int baseAttributionCount,
      int overlayAttributionCount,
      int effectiveAttributionCount,
      String statementHash,
      String attributionHash,
      String lastEventHash,
      String headHash,
      long updatedAtMicros) {

    private static OverlayHeadRow from(ResultSet row, int ignored)
        throws SQLException {
      return new OverlayHeadRow(
          value(row, "manifest_hash"),
          value(row, "protocol_version"),
          value(row, "requirement_hash"),
          row.getInt("base_sequence"),
          value(row, "base_head_hash"),
          row.getLong("state_version"),
          row.getInt("last_sequence"),
          value(row, "phase"),
          value(row, "attribution_status"),
          row.getInt("request_ordinal"),
          row.getInt("base_provider_attribution_count"),
          row.getInt("overlay_provider_attribution_count"),
          row.getInt("effective_provider_attribution_count"),
          value(row, "statement_hash"),
          value(row, "attribution_hash"),
          value(row, "last_event_hash"),
          value(row, "head_hash"),
          row.getLong("updated_at_epoch_micros"));
    }

    private boolean matches(
        ValidationRow validation,
        OverlayAttributionRow attribution,
        OverlayEventRow event) {
      return manifestHash.equals(validation.manifestHash())
          && protocolVersion.equals(validation.protocolVersion())
          && requirementHash.equals(validation.requirementHash())
          && baseSequence == validation.baseSequence()
          && baseHeadHash.equals(validation.baseHeadHash())
          && stateVersion == 1L
          && lastSequence == 14
          && "EXACT_PROVIDER_ATTRIBUTED".equals(phase)
          && "ATTRIBUTED".equals(attributionStatus)
          && requestOrdinal == 2
          && baseAttributionCount == 1
          && overlayAttributionCount == 1
          && effectiveAttributionCount == 2
          && statementHash.equals(validation.statementHash())
          && attributionHash.equals(attribution.attributionHash())
          && lastEventHash.equals(event.eventHash())
          && headHash.equals(validation.overlayHeadHash())
          && updatedAtMicros == validation.issuedAtMicros();
    }
  }
}
