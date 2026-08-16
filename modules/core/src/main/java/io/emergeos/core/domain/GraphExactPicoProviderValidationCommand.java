package io.emergeos.core.domain;

import io.emergeos.contracts.ContractValueDomains;
import java.time.Duration;
import java.util.Objects;

/** Bounded typed material accepted before V16 mints its exact-pico challenge. */
public record GraphExactPicoProviderValidationCommand(
    String principalId,
    String attemptId,
    String manifestHash,
    String requirementHash,
    String expectedBaseHeadHash,
    String requestHash,
    String responseHash,
    String modelResolvedHash,
    long inputTokens,
    long cachedInputTokens,
    long outputTokens,
    String decisionHash,
    String keyId,
    Duration challengeTtl) {

  public GraphExactPicoProviderValidationCommand {
    principalId = GraphAttemptDomains.safeName(principalId, "principalId");
    attemptId = GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash = GraphAttemptDomains.hash(manifestHash, "manifestHash");
    requirementHash =
        GraphAttemptDomains.hash(requirementHash, "requirementHash");
    expectedBaseHeadHash =
        GraphAttemptDomains.hash(expectedBaseHeadHash, "expectedBaseHeadHash");
    requestHash = GraphAttemptDomains.hash(requestHash, "requestHash");
    responseHash = GraphAttemptDomains.hash(responseHash, "responseHash");
    modelResolvedHash =
        GraphAttemptDomains.hash(modelResolvedHash, "modelResolvedHash");
    ContractValueDomains.requireSafeCount(inputTokens, "inputTokens");
    ContractValueDomains.requireSafeCount(
        cachedInputTokens, "cachedInputTokens");
    ContractValueDomains.requireSafeCount(outputTokens, "outputTokens");
    if (cachedInputTokens > inputTokens) {
      throw new IllegalArgumentException(
          "exact pico provider token details are inconsistent");
    }
    ContractValueDomains.requireSafeCount(
        Math.addExact(inputTokens, outputTokens), "totalTokens");
    decisionHash = GraphAttemptDomains.hash(decisionHash, "decisionHash");
    if (keyId == null || !keyId.matches("[a-z][a-z0-9._-]{0,99}")) {
      throw new IllegalArgumentException(
          "exact pico provider validation key is invalid");
    }
    challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl");
    if (challengeTtl.compareTo(Duration.ofMillis(100)) < 0
        || challengeTtl.compareTo(Duration.ofSeconds(30)) > 0
        || challengeTtl.toNanos() % 1_000_000L != 0L) {
      throw new IllegalArgumentException(
          "exact pico provider challenge TTL is invalid");
    }
  }

  public long totalTokens() {
    return Math.addExact(inputTokens, outputTokens);
  }
}
