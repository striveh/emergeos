package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Fully re-verified V16 overlay read model, separate from the legacy cursor.
 *
 * <p>The database-bound challenge and signature are verified by the reader.
 * This projection retains only their hashes and bounded semantic metadata.
 */
public record GraphExactPicoOverlaySnapshot(
    GraphExactPicoOverlayRequirement requirement,
    GraphExactPicoOverlayAttribution attribution,
    String baseValidationPolicyHash,
    String sessionIntentHash,
    String keyId,
    String keyFingerprint,
    String challengeHash,
    String signatureHash,
    Instant issuedAt,
    Instant expiresAt,
    Instant validatedAt,
    Instant consumedAt,
    GraphExactPicoProviderValidationReceipt receipt) {

  private static final String STATEMENT_DOMAIN =
      "emergeos.exact-provider-statement.v16";
  private static final String ATTRIBUTION_DOMAIN =
      "emergeos.graph-exact-provider-attribution.v16";
  private static final String EVENT_DOMAIN =
      "emergeos.graph-exact-tx-a-event.v16";
  private static final String HEAD_DOMAIN =
      "emergeos.graph-exact-tx-a-head.v16";
  private static final String TRANSCRIPT_DOMAIN =
      "emergeos.exact-provider-validation-transcript.v16";
  private static final String RECEIPT_DOMAIN =
      "emergeos.exact-provider-validation-receipt.v16";

  public GraphExactPicoOverlaySnapshot {
    requirement = Objects.requireNonNull(requirement, "requirement");
    attribution = Objects.requireNonNull(attribution, "attribution");
    receipt = Objects.requireNonNull(receipt, "receipt");
    baseValidationPolicyHash =
        GraphAttemptDomains.hash(
            baseValidationPolicyHash, "baseValidationPolicyHash");
    sessionIntentHash =
        GraphAttemptDomains.hash(sessionIntentHash, "sessionIntentHash");
    if (keyId == null || !keyId.matches("[a-z][a-z0-9._-]{0,99}")) {
      throw new IllegalArgumentException(
          "exact pico overlay key identity is invalid");
    }
    keyFingerprint =
        GraphAttemptDomains.hash(keyFingerprint, "keyFingerprint");
    challengeHash =
        GraphAttemptDomains.hash(challengeHash, "challengeHash");
    signatureHash =
        GraphAttemptDomains.hash(signatureHash, "signatureHash");
    issuedAt = requireInstant(issuedAt, "issuedAt");
    expiresAt = requireInstant(expiresAt, "expiresAt");
    validatedAt = requireInstant(validatedAt, "validatedAt");
    consumedAt = requireInstant(consumedAt, "consumedAt");

    if (!requirement.providerProfileId().equals(attribution.providerProfileId())
        || !requirement
            .providerProfileHash()
            .equals(attribution.providerProfileHash())
        || issuedAt.isBefore(requirement.requiredAt())
        || !issuedAt.equals(attribution.attributedAt())
        || !expiresAt.isAfter(issuedAt)
        || validatedAt.isBefore(issuedAt)
        || !validatedAt.isBefore(expiresAt)
        || !validatedAt.equals(consumedAt)
        || !requirement.protocolVersion().equals(receipt.protocolVersion())) {
      throw new IllegalArgumentException(
          "exact pico overlay snapshot binding is invalid");
    }

    String issuedMicros = micros(issuedAt, "issuedAt");
    String validatedMicros = micros(validatedAt, "validatedAt");
    String consumedMicros = micros(consumedAt, "consumedAt");
    String statementHash = statementHash(requirement, attribution,
        baseValidationPolicyHash);
    String attributionHash =
        attributionHash(
            requirement,
            attribution,
            baseValidationPolicyHash,
            statementHash,
            issuedMicros);
    String eventHash =
        eventHash(
            requirement,
            statementHash,
            attributionHash,
            issuedMicros);
    String headHash =
        headHash(
            requirement,
            statementHash,
            attributionHash,
            eventHash,
            issuedMicros);
    String transcriptHash =
        GraphProviderValidationCanonical.hash(
            TRANSCRIPT_DOMAIN,
            challengeHash,
            statementHash,
            attributionHash,
            eventHash,
            headHash,
            attribution.decision().name(),
            attribution.decisionHash(),
            "");
    String validationReceiptHash =
        GraphProviderValidationCanonical.hash(
            RECEIPT_DOMAIN,
            requirement.protocolVersion(),
            requirement.principalId(),
            requirement.attemptId(),
            requirement.manifestHash(),
            requirement.requirementHash(),
            transcriptHash,
            signatureHash,
            validatedMicros,
            consumedMicros,
            GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED
                .name(),
            statementHash,
            attributionHash,
            eventHash,
            headHash);

    if (!statementHash.equals(receipt.statementHash())
        || !attributionHash.equals(receipt.attributionHash())
        || !eventHash.equals(receipt.eventHash())
        || !headHash.equals(receipt.overlayHeadHash())
        || !transcriptHash.equals(receipt.transcriptHash())
        || !validationReceiptHash.equals(receipt.validationReceiptHash())) {
      throw new IllegalArgumentException(
          "exact pico overlay snapshot hashes are inconsistent");
    }
  }

  private static String statementHash(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution,
      String baseValidationPolicyHash) {
    return GraphProviderValidationCanonical.hash(
        STATEMENT_DOMAIN,
        requirement.requirementHash(),
        baseValidationPolicyHash,
        requirement.manifestHash(),
        Integer.toString(requirement.baseSequence()),
        requirement.baseHeadHash(),
        requirement.manifestHash(),
        Integer.toString(requirement.requestOrdinal()),
        requirement.requestHash(),
        attribution.responseHash(),
        attribution.providerId(),
        attribution.providerProtocol(),
        attribution.providerProfileId(),
        attribution.providerProfileHash(),
        attribution.baseExecutionPricingFingerprint(),
        attribution.modelRequested(),
        attribution.modelResolvedHash(),
        attribution.pricingProfileId(),
        attribution.pricingProviderId(),
        attribution.pricingProfileFingerprint(),
        attribution.pricingSourceHash(),
        "PICO_USD_PER_TOKEN",
        Long.toString(attribution.uncachedInputPicoUsdPerToken()),
        Long.toString(attribution.cachedInputPicoUsdPerToken()),
        Long.toString(attribution.outputPicoUsdPerToken()),
        Long.toString(attribution.inputTokens()),
        Long.toString(attribution.cachedInputTokens()),
        Long.toString(attribution.outputTokens()),
        Long.toString(attribution.reasoningOutputTokens()),
        Long.toString(attribution.totalTokens()),
        attribution.observedCostPicoUsd().toString(),
        attribution.decision().name(),
        attribution.decisionHash(),
        "");
  }

  private static String attributionHash(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution,
      String baseValidationPolicyHash,
      String statementHash,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        ATTRIBUTION_DOMAIN,
        requirement.protocolVersion(),
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        baseValidationPolicyHash,
        Integer.toString(requirement.baseSequence()),
        requirement.baseHeadHash(),
        "14",
        "1",
        Integer.toString(requirement.requestOrdinal()),
        requirement.requestHash(),
        attribution.responseHash(),
        attribution.providerActor(),
        attribution.providerId(),
        attribution.providerProtocol(),
        attribution.providerProfileId(),
        attribution.providerProfileHash(),
        attribution.baseExecutionPricingFingerprint(),
        attribution.transportProfileHash(),
        attribution.parserProfileHash(),
        attribution.schemaProfileHash(),
        attribution.modelRequested(),
        attribution.modelResolvedHash(),
        attribution.modelResolutionProfileHash(),
        attribution.pricingProfileId(),
        attribution.pricingProviderId(),
        attribution.pricingProfileFingerprint(),
        attribution.pricingSourceHash(),
        "PICO_USD_PER_TOKEN",
        Long.toString(attribution.uncachedInputPicoUsdPerToken()),
        Long.toString(attribution.cachedInputPicoUsdPerToken()),
        Long.toString(attribution.outputPicoUsdPerToken()),
        Long.toString(attribution.inputTokens()),
        Long.toString(attribution.cachedInputTokens()),
        Long.toString(attribution.outputTokens()),
        Long.toString(attribution.reasoningOutputTokens()),
        Long.toString(attribution.totalTokens()),
        attribution.observedCostPicoUsd().toString(),
        statementHash,
        attribution.decision().name(),
        attribution.decisionHash(),
        "",
        issuedMicros);
  }

  private static String eventHash(
      GraphExactPicoOverlayRequirement requirement,
      String statementHash,
      String attributionHash,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        EVENT_DOMAIN,
        requirement.protocolVersion(),
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        Integer.toString(requirement.baseSequence()),
        requirement.baseHeadHash(),
        "14",
        "EXACT_PROVIDER_ATTRIBUTED",
        issuedMicros,
        requirement.baseHeadHash(),
        attributionHash,
        statementHash);
  }

  private static String headHash(
      GraphExactPicoOverlayRequirement requirement,
      String statementHash,
      String attributionHash,
      String eventHash,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        HEAD_DOMAIN,
        requirement.protocolVersion(),
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        Integer.toString(requirement.baseSequence()),
        requirement.baseHeadHash(),
        "1",
        "14",
        "EXACT_PROVIDER_ATTRIBUTED",
        "ATTRIBUTED",
        Integer.toString(requirement.requestOrdinal()),
        "1",
        "1",
        "2",
        statementHash,
        attributionHash,
        eventHash,
        issuedMicros);
  }

  private static Instant requireInstant(Instant value, String name) {
    Instant checked = Objects.requireNonNull(value, name);
    GraphProviderValidationCanonical.epochMicros(checked, name);
    return checked;
  }

  private static String micros(Instant value, String name) {
    return Long.toString(
        GraphProviderValidationCanonical.epochMicros(value, name));
  }
}
