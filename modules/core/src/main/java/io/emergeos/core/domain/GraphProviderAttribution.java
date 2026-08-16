package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.contracts.ContractValueDomains;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded provider identity and usage attributed to one durable request
 * intent.
 *
 * <p>This record deliberately excludes raw request/response content,
 * headers, credentials, reasoning and exception text.
 */
public record GraphProviderAttribution(
    int requestOrdinal,
    String requestHash,
    String responseHash,
    String providerActor,
    String modelRequested,
    String modelResolved,
    GraphPricingSnapshot pricing,
    long inputTokens,
    long cachedInputTokens,
    long outputTokens,
    long reasoningOutputTokens,
    long totalTokens,
    BigDecimal observedCostUsd,
    String attributionHash) {

  private static final String DOMAIN =
      "emergeos.graph-provider-attribution.v1";

  public GraphProviderAttribution {
    if (requestOrdinal < 1 || requestOrdinal > 128) {
      throw new IllegalArgumentException(
          "provider attribution ordinal is invalid");
    }
    requestHash =
        GraphAttemptDomains.hash(requestHash, "requestHash");
    responseHash =
        GraphAttemptDomains.hash(responseHash, "responseHash");
    providerActor =
        GraphAttemptDomains.safeName(
            providerActor, "providerActor");
    modelRequested =
        GraphAttemptDomains.modelIdentifier(
            modelRequested, "modelRequested");
    modelResolved =
        GraphAttemptDomains.modelIdentifier(
            modelResolved, "modelResolved");
    pricing = Objects.requireNonNull(pricing, "pricing");
    if (!modelRequested.equals(pricing.modelRequested())) {
      throw new IllegalArgumentException(
          "provider attribution model does not match pricing");
    }
    ContractValueDomains.requireSafeCount(
        inputTokens, "inputTokens");
    ContractValueDomains.requireSafeCount(
        cachedInputTokens, "cachedInputTokens");
    ContractValueDomains.requireSafeCount(
        outputTokens, "outputTokens");
    ContractValueDomains.requireSafeCount(
        reasoningOutputTokens, "reasoningOutputTokens");
    ContractValueDomains.requireSafeCount(
        totalTokens, "totalTokens");
    if (cachedInputTokens > inputTokens
        || reasoningOutputTokens > outputTokens
        || Math.addExact(inputTokens, outputTokens)
            != totalTokens) {
      throw new IllegalArgumentException(
          "provider attribution token details are inconsistent");
    }
    ContractValueDomains.requireUsd(
        observedCostUsd, "observedCostUsd");
    if (pricing
            .actualCostUsd(
                inputTokens, cachedInputTokens, outputTokens)
            .compareTo(observedCostUsd)
        != 0) {
      throw new IllegalArgumentException(
          "provider attribution cost does not match pricing");
    }
    attributionHash =
        GraphAttemptDomains.hash(
            attributionHash, "attributionHash");
    if (!computeHash(
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
            observedCostUsd)
        .equals(attributionHash)) {
      throw new IllegalArgumentException(
          "provider attribution hash is inconsistent");
    }
  }

  public static GraphProviderAttribution create(
      int requestOrdinal,
      String requestHash,
      String responseHash,
      String providerActor,
      String modelRequested,
      String modelResolved,
      GraphPricingSnapshot pricing,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(
        observedCostUsd, "observedCostUsd");
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
        computeHash(
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
            observedCostUsd));
  }

  private static String computeHash(
      int requestOrdinal,
      String requestHash,
      String responseHash,
      String providerActor,
      String modelRequested,
      String modelResolved,
      GraphPricingSnapshot pricing,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("inputTokens", inputTokens);
    material.put("cachedInputTokens", cachedInputTokens);
    material.put("modelRequested", modelRequested);
    material.put("modelResolved", modelResolved);
    material.put("observedCostUsd", observedCostUsd);
    material.put("outputTokens", outputTokens);
    material.put("pricing", pricing);
    material.put("providerActor", providerActor);
    material.put(
        "reasoningOutputTokens", reasoningOutputTokens);
    material.put("requestHash", requestHash);
    material.put("requestOrdinal", requestOrdinal);
    material.put("responseHash", responseHash);
    material.put("totalTokens", totalTokens);
    return CanonicalIntegrity.hash(DOMAIN, material);
  }

  public String pricingProfileFingerprint() {
    return pricing.fingerprint();
  }
}
