package io.emergeos.core.domain;

import io.emergeos.contracts.ContractValueDomains;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/**
 * Bounded semantic projection of one V16 exact-pico attribution.
 *
 * <p>It contains hashes and pricing/usage metadata, never provider request or
 * response bytes, credentials, signature material, or reasoning content.
 */
public record GraphExactPicoOverlayAttribution(
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
    long uncachedInputPicoUsdPerToken,
    long cachedInputPicoUsdPerToken,
    long outputPicoUsdPerToken,
    long inputTokens,
    long cachedInputTokens,
    long outputTokens,
    long reasoningOutputTokens,
    long totalTokens,
    BigInteger observedCostPicoUsd,
    GraphProviderValidationDecision decision,
    String decisionHash,
    Instant attributedAt) {

  public GraphExactPicoOverlayAttribution {
    responseHash = GraphAttemptDomains.hash(responseHash, "responseHash");
    providerActor =
        GraphAttemptDomains.safeName(providerActor, "providerActor");
    if (!providerName(providerId)
        || !providerName(providerProtocol)
        || !profileId(providerProfileId)
        || !profileId(pricingProfileId)
        || !providerName(pricingProviderId)
        || !providerId.equals(pricingProviderId)) {
      throw new IllegalArgumentException(
          "exact pico overlay provider identity is invalid");
    }
    providerProfileHash =
        GraphAttemptDomains.hash(providerProfileHash, "providerProfileHash");
    baseExecutionPricingFingerprint =
        GraphAttemptDomains.hash(
            baseExecutionPricingFingerprint,
            "baseExecutionPricingFingerprint");
    transportProfileHash =
        GraphAttemptDomains.hash(transportProfileHash, "transportProfileHash");
    parserProfileHash =
        GraphAttemptDomains.hash(parserProfileHash, "parserProfileHash");
    schemaProfileHash =
        GraphAttemptDomains.hash(schemaProfileHash, "schemaProfileHash");
    modelRequested =
        GraphAttemptDomains.modelIdentifier(modelRequested, "modelRequested");
    modelResolvedHash =
        GraphAttemptDomains.hash(modelResolvedHash, "modelResolvedHash");
    modelResolutionProfileHash =
        GraphAttemptDomains.hash(
            modelResolutionProfileHash, "modelResolutionProfileHash");
    pricingProfileFingerprint =
        GraphAttemptDomains.hash(
            pricingProfileFingerprint, "pricingProfileFingerprint");
    pricingSourceHash =
        GraphAttemptDomains.hash(pricingSourceHash, "pricingSourceHash");

    if (uncachedInputPicoUsdPerToken < 1
        || uncachedInputPicoUsdPerToken
            > ContractValueDomains.MAX_SAFE_INTEGER
        || cachedInputPicoUsdPerToken < 0
        || cachedInputPicoUsdPerToken > uncachedInputPicoUsdPerToken
        || outputPicoUsdPerToken < 1
        || outputPicoUsdPerToken > ContractValueDomains.MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(
          "exact pico overlay pricing is invalid");
    }
    ContractValueDomains.requireSafeCount(inputTokens, "inputTokens");
    ContractValueDomains.requireSafeCount(
        cachedInputTokens, "cachedInputTokens");
    ContractValueDomains.requireSafeCount(outputTokens, "outputTokens");
    if (cachedInputTokens > inputTokens
        || reasoningOutputTokens != 0
        || Math.addExact(inputTokens, outputTokens) != totalTokens) {
      throw new IllegalArgumentException(
          "exact pico overlay token details are inconsistent");
    }

    observedCostPicoUsd =
        Objects.requireNonNull(observedCostPicoUsd, "observedCostPicoUsd");
    BigInteger expectedCost =
        cost(
            uncachedInputPicoUsdPerToken,
            cachedInputPicoUsdPerToken,
            outputPicoUsdPerToken,
            inputTokens,
            cachedInputTokens,
            outputTokens);
    if (observedCostPicoUsd.signum() < 0
        || observedCostPicoUsd.toString().length() > 38
        || !expectedCost.equals(observedCostPicoUsd)) {
      throw new IllegalArgumentException(
          "exact pico overlay cost is inconsistent");
    }
    decision = Objects.requireNonNull(decision, "decision");
    if (decision != GraphProviderValidationDecision.STRUCTURED_FINAL) {
      throw new IllegalArgumentException(
          "exact pico overlay decision is invalid");
    }
    decisionHash = GraphAttemptDomains.hash(decisionHash, "decisionHash");
    attributedAt = Objects.requireNonNull(attributedAt, "attributedAt");
    GraphProviderValidationCanonical.epochMicros(
        attributedAt, "attributedAt");
  }

  private static BigInteger cost(
      long uncachedInputRate,
      long cachedInputRate,
      long outputRate,
      long input,
      long cachedInput,
      long output) {
    return BigInteger.valueOf(input - cachedInput)
        .multiply(BigInteger.valueOf(uncachedInputRate))
        .add(
            BigInteger.valueOf(cachedInput)
                .multiply(BigInteger.valueOf(cachedInputRate)))
        .add(
            BigInteger.valueOf(output)
                .multiply(BigInteger.valueOf(outputRate)));
  }

  private static boolean providerName(String value) {
    return value != null
        && value.matches("[a-z][a-z0-9._-]{0,127}");
  }

  private static boolean profileId(String value) {
    return value != null
        && value.matches("[a-z][a-z0-9._-]{0,199}");
  }
}
