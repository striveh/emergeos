package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.IntegrityHashes;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GraphExactPicoOverlayTypesTest {

  private static final String PROTOCOL = "PICO_OVERLAY_V1";
  private static final String BASE_POLICY_HASH = hash("base-policy");
  private static final String SESSION_INTENT_HASH = hash("session-intent");
  private static final String CHALLENGE_HASH = hash("challenge");
  private static final String SIGNATURE_HASH = hash("signature");
  private static final Instant REQUIRED_AT =
      Instant.parse("2026-08-12T00:00:00Z");
  private static final Instant ISSUED_AT =
      Instant.parse("2026-08-12T00:00:01Z");
  private static final Instant EXPIRES_AT =
      Instant.parse("2026-08-12T00:00:10Z");
  private static final Instant VALIDATED_AT =
      Instant.parse("2026-08-12T00:00:02Z");

  @Test
  void recordsFormOneSelfVerifyingOverlayProjection() {
    Fixture fixture = fixture();
    assertEquals(13, fixture.requirement().baseSequence());
    assertEquals(2, fixture.requirement().requestOrdinal());
    assertEquals(
        fixture.receipt(), fixture.snapshot().receipt());
    assertTrue(
        new GraphExactPicoOverlayVerification.Missing()
            instanceof GraphExactPicoOverlayVerification);
    assertEquals(
        fixture.requirement(),
        new GraphExactPicoOverlayVerification.Required(
                fixture.requirement())
            .requirement());
    assertEquals(
        fixture.snapshot(),
        new GraphExactPicoOverlayVerification.Attributed(
                fixture.snapshot())
            .snapshot());
    assertEquals(
        GraphExactPicoOverlayVerification.InvalidReason
            .EXACT_OVERLAY_INVALID,
        new GraphExactPicoOverlayVerification.Invalid(
                GraphExactPicoOverlayVerification.InvalidReason
                    .EXACT_OVERLAY_INVALID)
            .reason());
  }

  @Test
  void attributionAllowsTheExactSumOfTwoSafeCounts() {
    long maximum = ContractValueDomains.MAX_SAFE_INTEGER;
    long total = Math.addExact(maximum, maximum);
    BigInteger maximumValue = BigInteger.valueOf(maximum);
    BigInteger cost = maximumValue.multiply(maximumValue).multiply(
        BigInteger.TWO);
    GraphExactPicoOverlayAttribution attribution =
        attribution(
            maximum,
            0,
            maximum,
            maximum,
            0,
            maximum,
            total,
            cost,
            ISSUED_AT);
    assertEquals(total, attribution.totalTokens());
    assertEquals(cost, attribution.observedCostPicoUsd());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            attribution(
                maximum,
                0,
                maximum,
                maximum,
                0,
                maximum,
                maximum,
                cost,
                ISSUED_AT));
  }

  @Test
  void attributionRejectsCostDecisionAndReasoningDrift() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            attribution(
                100,
                20,
                10,
                2,
                1,
                1,
                3,
                BigInteger.valueOf(131),
                ISSUED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoOverlayAttribution(
                hash("response"),
                "actor",
                "provider",
                "responses",
                "profile",
                hash("profile"),
                hash("base-pricing"),
                hash("transport"),
                hash("parser"),
                hash("schema"),
                "model",
                hash("model-resolved"),
                hash("model-resolution-profile"),
                "pricing",
                "provider",
                hash("pricing-fingerprint"),
                hash("pricing-source"),
                100,
                20,
                10,
                2,
                1,
                1,
                1,
                3,
                BigInteger.valueOf(130),
                GraphProviderValidationDecision.STRUCTURED_FINAL,
                hash("decision"),
                ISSUED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoOverlayAttribution(
                hash("response"),
                "actor",
                "provider",
                "responses",
                "profile",
                hash("profile"),
                hash("base-pricing"),
                hash("transport"),
                hash("parser"),
                hash("schema"),
                "model",
                hash("model-resolved"),
                hash("model-resolution-profile"),
                "pricing",
                "provider",
                hash("pricing-fingerprint"),
                hash("pricing-source"),
                100,
                20,
                10,
                2,
                1,
                1,
                0,
                3,
                BigInteger.valueOf(130),
                GraphProviderValidationDecision.FAILED,
                hash("decision"),
                ISSUED_AT));
  }

  @Test
  void snapshotRejectsHashAndTemporalCrossBindingDrift() {
    Fixture fixture = fixture();
    GraphExactPicoProviderValidationReceipt wrongReceipt =
        new GraphExactPicoProviderValidationReceipt(
            PROTOCOL,
            14,
            1,
            fixture.receipt().overlayHeadHash(),
            hash("wrong-statement"),
            fixture.receipt().attributionHash(),
            fixture.receipt().eventHash(),
            fixture.receipt().transcriptHash(),
            fixture.receipt().validationReceiptHash(),
            GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED);
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(fixture.requirement(), fixture.attribution(),
            wrongReceipt, ISSUED_AT, VALIDATED_AT));

    GraphExactPicoOverlayAttribution lateAttribution =
        attribution(
            100,
            20,
            10,
            2,
            1,
            1,
            3,
            BigInteger.valueOf(130),
            ISSUED_AT.plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            snapshot(
                fixture.requirement(),
                lateAttribution,
                fixture.receipt(),
                ISSUED_AT,
                VALIDATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            snapshot(
                fixture.requirement(),
                fixture.attribution(),
                fixture.receipt(),
                ISSUED_AT,
                EXPIRES_AT));
  }

  @Test
  void requirementAndResultsRejectInvalidOrMissingState() {
    GraphExactPicoOverlayRequirement valid = requirement();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoOverlayRequirement(
                PROTOCOL,
                valid.principalId(),
                valid.attemptId(),
                valid.manifestHash(),
                valid.requirementHash(),
                14,
                valid.baseHeadHash(),
                2,
                valid.requestHash(),
                valid.providerProfileId(),
                valid.providerProfileHash(),
                valid.requiredAt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoOverlayRequirement(
                PROTOCOL,
                valid.principalId(),
                valid.attemptId(),
                valid.manifestHash(),
                valid.requirementHash(),
                13,
                valid.baseHeadHash(),
                2,
                valid.requestHash(),
                valid.providerProfileId(),
                valid.providerProfileHash(),
                REQUIRED_AT.plusNanos(1)));
    assertThrows(
        NullPointerException.class,
        () -> new GraphExactPicoOverlayVerification.Required(null));
    assertThrows(
        NullPointerException.class,
        () -> new GraphExactPicoOverlayVerification.Attributed(null));
    assertThrows(
        NullPointerException.class,
        () -> new GraphExactPicoOverlayVerification.Invalid(null));
  }

  private static Fixture fixture() {
    GraphExactPicoOverlayRequirement requirement = requirement();
    GraphExactPicoOverlayAttribution attribution =
        attribution(
            100,
            20,
            10,
            2,
            1,
            1,
            3,
            BigInteger.valueOf(130),
            ISSUED_AT);
    GraphExactPicoProviderValidationReceipt receipt =
        receipt(requirement, attribution);
    return new Fixture(
        requirement,
        attribution,
        receipt,
        snapshot(
            requirement,
            attribution,
            receipt,
            ISSUED_AT,
            VALIDATED_AT));
  }

  private static GraphExactPicoOverlayRequirement requirement() {
    return new GraphExactPicoOverlayRequirement(
        PROTOCOL,
        "principal",
        hash("attempt"),
        hash("manifest"),
        hash("requirement"),
        13,
        hash("base-head"),
        2,
        hash("request"),
        "profile",
        hash("profile"),
        REQUIRED_AT);
  }

  private static GraphExactPicoOverlayAttribution attribution(
      long uncachedRate,
      long cachedRate,
      long outputRate,
      long input,
      long cachedInput,
      long output,
      long total,
      BigInteger cost,
      Instant attributedAt) {
    return new GraphExactPicoOverlayAttribution(
        hash("response"),
        "actor",
        "provider",
        "responses",
        "profile",
        hash("profile"),
        hash("base-pricing"),
        hash("transport"),
        hash("parser"),
        hash("schema"),
        "model",
        hash("model-resolved"),
        hash("model-resolution-profile"),
        "pricing",
        "provider",
        hash("pricing-fingerprint"),
        hash("pricing-source"),
        uncachedRate,
        cachedRate,
        outputRate,
        input,
        cachedInput,
        output,
        0,
        total,
        cost,
        GraphProviderValidationDecision.STRUCTURED_FINAL,
        hash("decision"),
        attributedAt);
  }

  private static GraphExactPicoOverlaySnapshot snapshot(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution,
      GraphExactPicoProviderValidationReceipt receipt,
      Instant issuedAt,
      Instant validatedAt) {
    return new GraphExactPicoOverlaySnapshot(
        requirement,
        attribution,
        BASE_POLICY_HASH,
        SESSION_INTENT_HASH,
        "key",
        hash("key-fingerprint"),
        CHALLENGE_HASH,
        SIGNATURE_HASH,
        issuedAt,
        EXPIRES_AT,
        validatedAt,
        validatedAt,
        receipt);
  }

  private static GraphExactPicoProviderValidationReceipt receipt(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution) {
    String issuedMicros = micros(ISSUED_AT);
    String validatedMicros = micros(VALIDATED_AT);
    String statement = statement(requirement, attribution);
    String attributionHash =
        attribution(requirement, attribution, statement, issuedMicros);
    String event = event(requirement, statement, attributionHash, issuedMicros);
    String head =
        head(requirement, statement, attributionHash, event, issuedMicros);
    String transcript =
        GraphProviderValidationCanonical.hash(
            "emergeos.exact-provider-validation-transcript.v16",
            CHALLENGE_HASH,
            statement,
            attributionHash,
            event,
            head,
            attribution.decision().name(),
            attribution.decisionHash(),
            "");
    String receiptHash =
        GraphProviderValidationCanonical.hash(
            "emergeos.exact-provider-validation-receipt.v16",
            PROTOCOL,
            requirement.principalId(),
            requirement.attemptId(),
            requirement.manifestHash(),
            requirement.requirementHash(),
            transcript,
            SIGNATURE_HASH,
            validatedMicros,
            validatedMicros,
            "CONSUMED",
            statement,
            attributionHash,
            event,
            head);
    return new GraphExactPicoProviderValidationReceipt(
        PROTOCOL,
        14,
        1,
        head,
        statement,
        attributionHash,
        event,
        transcript,
        receiptHash,
        GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED);
  }

  private static String statement(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution) {
    return GraphProviderValidationCanonical.hash(
        "emergeos.exact-provider-statement.v16",
        requirement.requirementHash(),
        BASE_POLICY_HASH,
        requirement.manifestHash(),
        "13",
        requirement.baseHeadHash(),
        requirement.manifestHash(),
        "2",
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
        "0",
        Long.toString(attribution.totalTokens()),
        attribution.observedCostPicoUsd().toString(),
        "STRUCTURED_FINAL",
        attribution.decisionHash(),
        "");
  }

  private static String attribution(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution,
      String statement,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        "emergeos.graph-exact-provider-attribution.v16",
        PROTOCOL,
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        BASE_POLICY_HASH,
        "13",
        requirement.baseHeadHash(),
        "14",
        "1",
        "2",
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
        "0",
        Long.toString(attribution.totalTokens()),
        attribution.observedCostPicoUsd().toString(),
        statement,
        "STRUCTURED_FINAL",
        attribution.decisionHash(),
        "",
        issuedMicros);
  }

  private static String event(
      GraphExactPicoOverlayRequirement requirement,
      String statement,
      String attribution,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        "emergeos.graph-exact-tx-a-event.v16",
        PROTOCOL,
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        "13",
        requirement.baseHeadHash(),
        "14",
        "EXACT_PROVIDER_ATTRIBUTED",
        issuedMicros,
        requirement.baseHeadHash(),
        attribution,
        statement);
  }

  private static String head(
      GraphExactPicoOverlayRequirement requirement,
      String statement,
      String attribution,
      String event,
      String issuedMicros) {
    return GraphProviderValidationCanonical.hash(
        "emergeos.graph-exact-tx-a-head.v16",
        PROTOCOL,
        requirement.principalId(),
        requirement.attemptId(),
        requirement.manifestHash(),
        requirement.requirementHash(),
        "13",
        requirement.baseHeadHash(),
        "1",
        "14",
        "EXACT_PROVIDER_ATTRIBUTED",
        "ATTRIBUTED",
        "2",
        "1",
        "1",
        "2",
        statement,
        attribution,
        event,
        issuedMicros);
  }

  private static String micros(Instant value) {
    return Long.toString(
        GraphProviderValidationCanonical.epochMicros(value, "instant"));
  }

  private static String hash(String value) {
    return IntegrityHashes.utf8ContentHash(value);
  }

  private record Fixture(
      GraphExactPicoOverlayRequirement requirement,
      GraphExactPicoOverlayAttribution attribution,
      GraphExactPicoProviderValidationReceipt receipt,
      GraphExactPicoOverlaySnapshot snapshot) {}
}
