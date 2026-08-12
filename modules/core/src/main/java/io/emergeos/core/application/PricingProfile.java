package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.GraphPricingSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable list-price identity used for bounded model evaluation.
 *
 * <p>Rates are integer nano-USD per token. Floating-point arithmetic is deliberately absent from
 * the budget gate.
 */
public record PricingProfile(
    String id,
    String provider,
    String modelRequested,
    long uncachedInputNanoUsdPerToken,
    long cachedInputNanoUsdPerToken,
    long outputNanoUsdPerToken) {

  private static final int NANO_USD_SCALE = 9;

  public PricingProfile {
    ContractText.require(id, "pricing profile id", ContractText.MAX_NAME_LENGTH);
    ContractText.require(provider, "pricing provider", 128);
    ContractText.require(
        modelRequested, "pricing modelRequested", ContractText.MAX_MODEL_LENGTH);
    if (!id.matches("[a-z][a-z0-9._-]{0,199}")) {
      throw new IllegalArgumentException(
          "pricing profile id must be a stable lowercase slug");
    }
    if (!provider.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException(
          "pricing provider must be a stable lowercase slug");
    }
    if (!ContractText.isSafeModelIdentifier(modelRequested)) {
      throw new IllegalArgumentException(
          "pricing modelRequested is outside the safe model domain");
    }
    if (uncachedInputNanoUsdPerToken <= 0
        || cachedInputNanoUsdPerToken < 0
        || outputNanoUsdPerToken <= 0) {
      throw new IllegalArgumentException(
          "uncached input and output rates must be positive; cached input cannot be negative");
    }
    if (cachedInputNanoUsdPerToken > uncachedInputNanoUsdPerToken) {
      throw new IllegalArgumentException(
          "cached input rate cannot exceed uncached input rate");
    }
  }

  public BigDecimal actualCostUsd(
      long inputTokens, long cachedInputTokens, long outputTokens) {
    return graphSnapshot()
        .actualCostUsd(
            inputTokens, cachedInputTokens, outputTokens);
  }

  public BigDecimal reserveCostUsd(
      int maxModelSteps,
      long maxInputTokensPerStep,
      long maxOutputTokensPerStep) {
    ContractValueDomains.requireExecutionLimit(maxModelSteps, "maxModelSteps");
    ContractValueDomains.requireSafeCount(
        maxInputTokensPerStep, "maxInputTokensPerStep");
    ContractValueDomains.requireSafeCount(
        maxOutputTokensPerStep, "maxOutputTokensPerStep");
    long perStepNanoUsd =
        addExact(
            multiplyExact(
                maxInputTokensPerStep, uncachedInputNanoUsdPerToken),
            multiplyExact(
                maxOutputTokensPerStep, outputNanoUsdPerToken));
    long perStepMicroUsd = divideNanoUsdCeilingToMicroUsd(perStepNanoUsd);
    long totalMicroUsd = multiplyExact(perStepMicroUsd, maxModelSteps);
    return microUsdToContractUsd(totalMicroUsd);
  }

  /**
   * Content fingerprint for compiled-catalog and Bundle binding.
   *
   * <p>The rounding rules are part of the identity; changing them requires a new fingerprint even
   * when every list-price rate is unchanged.
   */
  public String fingerprint() {
    return graphSnapshot().fingerprint();
  }

  public GraphPricingSnapshot graphSnapshot() {
    return GraphPricingSnapshot.create(
        id,
        provider,
        modelRequested,
        uncachedInputNanoUsdPerToken,
        cachedInputNanoUsdPerToken,
        outputNanoUsdPerToken);
  }

  private static long addExact(long... values) {
    long total = 0;
    for (long value : values) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  private static long multiplyExact(long left, long right) {
    return Math.multiplyExact(left, right);
  }

  private static long divideNanoUsdCeilingToMicroUsd(long nanoUsd) {
    long wholeMicroUsd = nanoUsd / 1_000;
    return nanoUsd % 1_000 == 0
        ? wholeMicroUsd
        : Math.addExact(wholeMicroUsd, 1);
  }

  private static BigDecimal microUsdToContractUsd(long microUsd) {
    BigDecimal usd =
        BigDecimal.valueOf(microUsd, ContractValueDomains.USD_MAX_SCALE);
    if (usd.signum() == 0) {
      return BigDecimal.ZERO;
    }
    ContractValueDomains.requireUsd(usd, "calculated reservationUsd");
    return usd;
  }

  private static BigDecimal toContractUsd(
      long nanoUsd, RoundingMode roundingMode) {
    BigDecimal rounded =
        BigDecimal.valueOf(nanoUsd, NANO_USD_SCALE)
            .setScale(ContractValueDomains.USD_MAX_SCALE, roundingMode);
    if (rounded.signum() == 0) {
      return BigDecimal.ZERO;
    }
    ContractValueDomains.requireUsd(rounded, "calculated costUsd");
    return rounded;
  }
}
