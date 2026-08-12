package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

/** Verified V15 requirement projected without changing the legacy graph cursor. */
public record GraphExactPicoOverlayRequirement(
    String protocolVersion,
    String principalId,
    String attemptId,
    String manifestHash,
    String requirementHash,
    int baseSequence,
    String baseHeadHash,
    int requestOrdinal,
    String requestHash,
    String providerProfileId,
    String providerProfileHash,
    Instant requiredAt) {

  public GraphExactPicoOverlayRequirement {
    if (!GraphExactPicoProviderValidationChallenge.PROTOCOL_VERSION.equals(
            protocolVersion)
        || baseSequence != 13
        || requestOrdinal != 2
        || !profileId(providerProfileId)) {
      throw new IllegalArgumentException(
          "exact pico overlay requirement shape is invalid");
    }
    principalId = GraphAttemptDomains.safeName(principalId, "principalId");
    attemptId = GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash = GraphAttemptDomains.hash(manifestHash, "manifestHash");
    requirementHash =
        GraphAttemptDomains.hash(requirementHash, "requirementHash");
    baseHeadHash = GraphAttemptDomains.hash(baseHeadHash, "baseHeadHash");
    requestHash = GraphAttemptDomains.hash(requestHash, "requestHash");
    providerProfileHash =
        GraphAttemptDomains.hash(providerProfileHash, "providerProfileHash");
    requiredAt = Objects.requireNonNull(requiredAt, "requiredAt");
    GraphProviderValidationCanonical.epochMicros(requiredAt, "requiredAt");
  }

  private static boolean profileId(String value) {
    return value != null
        && value.matches("[a-z][a-z0-9._-]{0,199}");
  }
}
