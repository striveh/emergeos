package io.emergeos.adapters.openai;

import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure semantic bridge from one reviewed OpenAI outcome to the bounded V13
 * validation statement.
 *
 * <p>This class does not sign, access PostgreSQL or enable a provider route.
 * It only freezes the reviewed adapter profiles and rejects any attempt to
 * splice its exact outcome receipt onto a different graph attempt or different
 * provider-observable attribution truth. The V13 Store separately binds the
 * graph-owned actor and manifest authority.
 */
public final class OpenAiProviderValidationStatements {

  private static final String PROVIDER = "openai.responses";
  private static final String MISMATCH =
      "reviewed provider outcome does not match graph attribution";
  private static final String TRANSPORT_DOMAIN =
      "emergeos.openai-reviewed-transport-profile.v1";
  private static final String PARSER_DOMAIN =
      "emergeos.openai-reviewed-parser-profile.v1";
  private static final String SCHEMA_DOMAIN =
      "emergeos.openai-reviewed-schema-profile.v1";
  private static final Policy POLICY =
      new Policy(
          transportProfileHash(),
          parserProfileHash(),
          schemaProfileHash());

  private OpenAiProviderValidationStatements() {}

  /** Exact profile identity to enroll before a request-2 validation. */
  public static Policy policy() {
    return POLICY;
  }

  /**
   * Maps only a request-2 outcome minted by the reviewed adapter and exactly
   * matching already-typed graph attribution.
   */
  public static GraphProviderValidationStatement fromExactOutcome(
      OpenAiResponsesModel.ProviderOutcomeReceipt outcome,
      GraphProviderAttribution exactAttribution,
      String executionBindingHash) {
    if (!matches(outcome, exactAttribution, executionBindingHash)) {
      throw mismatch();
    }
    GraphProviderValidationDecision decision;
    GraphAttributedFailureCode failureCode = null;
    if (outcome.kind()
        == OpenAiResponsesModel.ProviderOutcomeKind.STRUCTURED_FINAL) {
      decision = GraphProviderValidationDecision.STRUCTURED_FINAL;
    } else if (outcome.kind()
        == OpenAiResponsesModel.ProviderOutcomeKind.FAILED) {
      decision = GraphProviderValidationDecision.FAILED;
      try {
        failureCode =
            GraphAttributedFailureCode.require(outcome.failureReason());
      } catch (RuntimeException unsupportedFailure) {
        throw mismatch();
      }
    } else {
      throw mismatch();
    }
    return new GraphProviderValidationStatement(
        exactAttribution,
        executionBindingHash,
        POLICY.transportProfileHash(),
        POLICY.parserProfileHash(),
        POLICY.schemaProfileHash(),
        decision,
        outcome.decisionHash(),
        failureCode);
  }

  private static boolean matches(
      OpenAiResponsesModel.ProviderOutcomeReceipt outcome,
      GraphProviderAttribution exactAttribution,
      String executionBindingHash) {
    if (outcome == null
        || exactAttribution == null
        || executionBindingHash == null
        || !executionBindingHash.matches("[a-f0-9]{64}")
        || !executionBindingHash.equals(
            outcome.executionBindingHash())) {
      return false;
    }
    OpenAiResponsesModel.ProviderAttributionReceipt receipt =
        outcome.attribution();
    OpenAiResponsesModel.ProviderInvocation invocation =
        receipt.invocation();
    return invocation.requestOrdinal() == 2
        && exactAttribution.requestOrdinal() == 2
        && invocation.requestOrdinal()
            == exactAttribution.requestOrdinal()
        && invocation.requestHash().equals(exactAttribution.requestHash())
        && receipt.responseHash().equals(exactAttribution.responseHash())
        && invocation.modelRequested()
            .equals(exactAttribution.modelRequested())
        && receipt.modelResolved()
            .equals(exactAttribution.modelResolved())
        && PROVIDER.equals(exactAttribution.pricing().provider())
        && outcome.pricingProfileFingerprint()
            .equals(exactAttribution.pricing().fingerprint())
        && receipt.inputTokens() == exactAttribution.inputTokens()
        && receipt.cachedInputTokens()
            == exactAttribution.cachedInputTokens()
        && receipt.outputTokens() == exactAttribution.outputTokens()
        && receipt.reasoningOutputTokens()
            == exactAttribution.reasoningOutputTokens()
        && receipt.totalTokens() == exactAttribution.totalTokens()
        && receipt.observedCostUsd().compareTo(
                exactAttribution.observedCostUsd())
            == 0;
  }

  private static IllegalArgumentException mismatch() {
    return new IllegalArgumentException(MISMATCH);
  }

  private static String transportProfileHash() {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("protocolVersion", OpenAiResponsesModel.PROTOCOL_VERSION);
    material.put(
        "clientFactory", "ReviewedOpenAiClient.defaultCodecNoRetry");
    material.put("requestCodec", "openai-java-default-json");
    material.put(
        "responseDigest", "sha256-content-decoded-entity-bytes");
    material.put("responseRoot", "single-strict-json-object");
    material.put("duplicateKeys", "rejected");
    material.put("trailingTokens", "rejected");
    material.put(
        "maxResponseBytes",
        ReviewedOpenAiClient.MAX_REVIEWED_RESPONSE_BYTES);
    material.put("maxRetries", 0);
    return CanonicalIntegrity.hash(TRANSPORT_DOMAIN, material);
  }

  private static String parserProfileHash() {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("protocolVersion", OpenAiResponsesModel.PROTOCOL_VERSION);
    material.put("completedStatusRequired", true);
    material.put("defaultServiceTierRequired", true);
    material.put("parallelToolCallsAllowed", false);
    material.put("reasoningItems", "validated");
    material.put(
        "request2Output", "one-completed-message/one-output-text");
    material.put(
        "payloadObject", "exact-content-and-evidenceRefs");
    material.put(
        "evidenceBinding", "one-exact-active-capture-ref");
    material.put(
        "modelBinding", "requested-or-requested-prefix");
    material.put("usageBinding", "present-safe-consistent");
    return CanonicalIntegrity.hash(PARSER_DOMAIN, material);
  }

  private static String schemaProfileHash() {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put(
        "promptSurfaceVersion",
        OpenAiResponsesModel.PROMPT_SURFACE_VERSION);
    material.put("requestOrdinal", 2);
    material.put("toolChoice", "none");
    material.put("root", "closed-object");
    material.put(
        "requiredFields", List.of("content", "evidenceRefs"));
    material.put("content", "string");
    material.put("evidenceRefs", "exactly-one-string");
    return CanonicalIntegrity.hash(SCHEMA_DOMAIN, material);
  }

  /** Immutable, hash-only profile policy accepted by the V13 DB contract. */
  public static final class Policy {

    private final String transportProfileHash;
    private final String parserProfileHash;
    private final String schemaProfileHash;

    private Policy(
        String transportProfileHash,
        String parserProfileHash,
        String schemaProfileHash) {
      requireHash(transportProfileHash, "transportProfileHash");
      requireHash(parserProfileHash, "parserProfileHash");
      requireHash(schemaProfileHash, "schemaProfileHash");
      this.transportProfileHash = transportProfileHash;
      this.parserProfileHash = parserProfileHash;
      this.schemaProfileHash = schemaProfileHash;
    }

    public String transportProfileHash() {
      return transportProfileHash;
    }

    public String parserProfileHash() {
      return parserProfileHash;
    }

    public String schemaProfileHash() {
      return schemaProfileHash;
    }

    private static void requireHash(String value, String name) {
      Objects.requireNonNull(value, name);
      if (!value.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(name + " is invalid");
      }
    }
  }
}
