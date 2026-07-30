package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricingProfileTest {

  @Test
  void calculatesObservedCostFromUncachedCachedAndOutputTokens() {
    PricingProfile pricing = pricing(5_000, 500, 30_000);

    assertEquals(
        new BigDecimal("0.009200"),
        pricing.actualCostUsd(1_000, 400, 200));
  }

  @Test
  void observedCostRoundsHalfUpToTheContractMicroUsdDomain() {
    assertEquals(
        new BigDecimal("0.000001"),
        pricing(500, 0, 1).actualCostUsd(1, 0, 0));
    assertEquals(
        BigDecimal.ZERO,
        pricing(499, 0, 1).actualCostUsd(1, 0, 0));
    assertEquals(
        new BigDecimal("0.000002"),
        pricing(500, 0, 1)
            .actualCostUsd(1, 0, 0)
            .add(pricing(500, 0, 1).actualCostUsd(1, 0, 0)));
  }

  @Test
  void reservationUsesUncachedInputAndCoversEveryModelStep() {
    PricingProfile pricing = pricing(5_000, 500, 30_000);

    assertEquals(
        new BigDecimal("0.022000"),
        pricing.reserveCostUsd(2, 1_000, 200));
  }

  @Test
  void reservationRoundsUpInsteadOfUnderstatingTheCeiling() {
    assertEquals(
        new BigDecimal("0.000001"),
        pricing(1, 0, 1).reserveCostUsd(1, 1, 0));
    assertEquals(
        new BigDecimal("0.000002"),
        pricing(500, 0, 1).reserveCostUsd(2, 1, 0));
  }

  @Test
  void rejectsImpossibleUsageAndUnsafePricingIdentity() {
    PricingProfile pricing = pricing(5_000, 500, 30_000);

    assertThrows(
        IllegalArgumentException.class,
        () -> pricing.actualCostUsd(10, 11, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> pricing.actualCostUsd(-1, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PricingProfile(
                "OpenAI/live",
                "openai.responses",
                "gpt-5.6-sol",
                5_000,
                500,
                30_000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PricingProfile(
                "openai-gpt-5.6-sol-v1",
                "openai.responses",
                "gpt-5.6-sol",
                500,
                5_000,
                30_000));
  }

  @Test
  void arithmeticOverflowFailsClosed() {
    PricingProfile pricing =
        pricing(Long.MAX_VALUE, 0, 1);

    assertThrows(
        ArithmeticException.class,
        () -> pricing.actualCostUsd(2, 0, 0));
    assertThrows(
        ArithmeticException.class,
        () -> pricing.reserveCostUsd(2, 2, 0));
  }

  @Test
  void fingerprintChangesWhenOneRateChangesUnderTheSameClaimedId() {
    assertNotEquals(
        pricing(5_000, 500, 30_000).fingerprint(),
        pricing(5_001, 500, 30_000).fingerprint());
  }

  private static PricingProfile pricing(
      long uncachedInput,
      long cachedInput,
      long output) {
    return new PricingProfile(
        "openai-gpt-5.6-sol-2026-07-v1",
        "openai.responses",
        "gpt-5.6-sol",
        uncachedInput,
        cachedInput,
        output);
  }
}
