package io.emergeos.core.domain;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable list-price material used to re-derive one graph attribution.
 */
public record GraphPricingSnapshot(
    String id,
    String provider,
    String modelRequested,
    long uncachedInputNanoUsdPerToken,
    long cachedInputNanoUsdPerToken,
    long outputNanoUsdPerToken,
    String fingerprint) {

  private static final int NANO_USD_SCALE = 9;

  public GraphPricingSnapshot {
    ContractText.require(
        id, "pricing profile id", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        provider, "pricing provider", 128);
    ContractText.require(
        modelRequested,
        "pricing modelRequested",
        ContractText.MAX_MODEL_LENGTH);
    if (!id.matches("[a-z][a-z0-9._-]{0,199}")
        || !provider.matches("[a-z][a-z0-9._-]{0,127}")
        || !ContractText.isSafeModelIdentifier(modelRequested)
        || uncachedInputNanoUsdPerToken <= 0
        || cachedInputNanoUsdPerToken < 0
        || outputNanoUsdPerToken <= 0
        || cachedInputNanoUsdPerToken
            > uncachedInputNanoUsdPerToken) {
      throw new IllegalArgumentException(
          "graph pricing material is outside the frozen domain");
    }
    fingerprint =
        GraphAttemptDomains.hash(
            fingerprint, "pricingProfileFingerprint");
    if (!computeFingerprint(
            id,
            provider,
            modelRequested,
            uncachedInputNanoUsdPerToken,
            cachedInputNanoUsdPerToken,
            outputNanoUsdPerToken)
        .equals(fingerprint)) {
      throw new IllegalArgumentException(
          "graph pricing fingerprint is inconsistent");
    }
  }

  public static GraphPricingSnapshot create(
      String id,
      String provider,
      String modelRequested,
      long uncachedInputNanoUsdPerToken,
      long cachedInputNanoUsdPerToken,
      long outputNanoUsdPerToken) {
    return new GraphPricingSnapshot(
        id,
        provider,
        modelRequested,
        uncachedInputNanoUsdPerToken,
        cachedInputNanoUsdPerToken,
        outputNanoUsdPerToken,
        computeFingerprint(
            id,
            provider,
            modelRequested,
            uncachedInputNanoUsdPerToken,
            cachedInputNanoUsdPerToken,
            outputNanoUsdPerToken));
  }

  public BigDecimal actualCostUsd(
      long inputTokens,
      long cachedInputTokens,
      long outputTokens) {
    ContractValueDomains.requireSafeCount(
        inputTokens, "inputTokens");
    ContractValueDomains.requireSafeCount(
        cachedInputTokens, "cachedInputTokens");
    ContractValueDomains.requireSafeCount(
        outputTokens, "outputTokens");
    if (cachedInputTokens > inputTokens) {
      throw new IllegalArgumentException(
          "cachedInputTokens cannot exceed inputTokens");
    }
    long uncachedInputTokens = inputTokens - cachedInputTokens;
    long totalNanoUsd =
        Math.addExact(
            Math.addExact(
                Math.multiplyExact(
                    uncachedInputTokens,
                    uncachedInputNanoUsdPerToken),
                Math.multiplyExact(
                    cachedInputTokens,
                    cachedInputNanoUsdPerToken)),
            Math.multiplyExact(
                outputTokens, outputNanoUsdPerToken));
    BigDecimal rounded =
        BigDecimal.valueOf(totalNanoUsd, NANO_USD_SCALE)
            .setScale(
                ContractValueDomains.USD_MAX_SCALE,
                RoundingMode.HALF_UP);
    if (rounded.signum() == 0) {
      return BigDecimal.ZERO;
    }
    ContractValueDomains.requireUsd(
        rounded, "calculated graph costUsd");
    return rounded;
  }

  private static String computeFingerprint(
      String id,
      String provider,
      String modelRequested,
      long uncachedInputNanoUsdPerToken,
      long cachedInputNanoUsdPerToken,
      long outputNanoUsdPerToken) {
    String material =
        String.join(
            "\n",
            "pricing-profile-fingerprint-v1",
            "id=" + id,
            "provider=" + provider,
            "modelRequested=" + modelRequested,
            "uncachedInputNanoUsdPerToken="
                + uncachedInputNanoUsdPerToken,
            "cachedInputNanoUsdPerToken="
                + cachedInputNanoUsdPerToken,
            "outputNanoUsdPerToken="
                + outputNanoUsdPerToken,
            "actualRounding=PER_CALL_HALF_UP_TO_MICRO_USD",
            "reservationRounding=PER_CALL_CEILING_TO_MICRO_USD");
    return ContentHashes.sha256(material);
  }
}
