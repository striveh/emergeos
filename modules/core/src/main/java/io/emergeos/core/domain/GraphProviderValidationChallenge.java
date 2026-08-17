package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One DB-issued, one-shot challenge for a V13 provider-validation receipt. */
public record GraphProviderValidationChallenge(
    String protocolVersion,
    String databaseName,
    long databaseOid,
    long schemaOid,
    long attestorRoleOid,
    String principalId,
    String attemptId,
    String manifestHash,
    String revision,
    String sessionIntentHash,
    Instant sessionExpiresAt,
    int policySequence,
    String policyHeadHash,
    String keyId,
    String keyFingerprint,
    String transportProfileHash,
    String parserProfileHash,
    String schemaProfileHash,
    UUID validationNonce,
    Instant issuedAt,
    Instant expiresAt,
    String policyHash,
    String challengeHash) {

  public static final String PROTOCOL_VERSION = "POSTGRES_ROLE_V1";
  private static final String POLICY_DOMAIN =
      "emergeos.provider-validation-policy.v1";
  private static final String CHALLENGE_DOMAIN =
      "emergeos.provider-validation-challenge.v1";

  public GraphProviderValidationChallenge {
    if (!PROTOCOL_VERSION.equals(protocolVersion)
        || databaseName == null
        || !databaseName.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,62}")
        || databaseOid < 1
        || databaseOid > 4_294_967_295L
        || schemaOid < 1
        || schemaOid > 4_294_967_295L
        || attestorRoleOid < 1
        || attestorRoleOid > 4_294_967_295L
        || !("r1".equals(revision)
            || "r2".equals(revision)
            || "r3".equals(revision))
        || policySequence != 7
        || keyId == null
        || !keyId.matches("[a-z][a-z0-9._-]{0,99}")) {
      throw new IllegalArgumentException(
          "provider validation challenge identity is invalid");
    }
    principalId =
        GraphAttemptDomains.safeName(principalId, "principalId");
    attemptId = GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash =
        GraphAttemptDomains.hash(manifestHash, "manifestHash");
    sessionIntentHash =
        GraphAttemptDomains.hash(
            sessionIntentHash, "sessionIntentHash");
    policyHeadHash =
        GraphAttemptDomains.hash(policyHeadHash, "policyHeadHash");
    keyFingerprint =
        GraphAttemptDomains.hash(
            keyFingerprint, "keyFingerprint");
    transportProfileHash =
        GraphAttemptDomains.hash(
            transportProfileHash, "transportProfileHash");
    parserProfileHash =
        GraphAttemptDomains.hash(parserProfileHash, "parserProfileHash");
    schemaProfileHash =
        GraphAttemptDomains.hash(schemaProfileHash, "schemaProfileHash");
    validationNonce =
        Objects.requireNonNull(validationNonce, "validationNonce");
    sessionExpiresAt =
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
    issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(issuedAt)
        || sessionExpiresAt.isBefore(expiresAt)) {
      throw new IllegalArgumentException(
          "provider validation challenge window is invalid");
    }
    long sessionExpiresMicros =
        GraphProviderValidationCanonical.epochMicros(
            sessionExpiresAt, "sessionExpiresAt");
    long issuedMicros =
        GraphProviderValidationCanonical.epochMicros(
            issuedAt, "issuedAt");
    long expiresMicros =
        GraphProviderValidationCanonical.epochMicros(
            expiresAt, "expiresAt");
    policyHash = GraphAttemptDomains.hash(policyHash, "policyHash");
    challengeHash =
        GraphAttemptDomains.hash(challengeHash, "challengeHash");
    String expectedPolicy =
        GraphProviderValidationCanonical.hash(
            POLICY_DOMAIN,
            databaseName,
            Long.toString(databaseOid),
            Long.toString(schemaOid),
            Long.toString(attestorRoleOid),
            principalId,
            attemptId,
            manifestHash,
            revision,
            sessionIntentHash,
            Long.toString(sessionExpiresMicros),
            Integer.toString(policySequence),
            policyHeadHash,
            keyId,
            keyFingerprint,
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash);
    String expectedChallenge =
        GraphProviderValidationCanonical.hash(
            CHALLENGE_DOMAIN,
            expectedPolicy,
            validationNonce.toString(),
            Long.toString(issuedMicros),
            Long.toString(expiresMicros));
    if (!expectedPolicy.equals(policyHash)
        || !expectedChallenge.equals(challengeHash)) {
      throw new IllegalArgumentException(
          "provider validation challenge hash is inconsistent");
    }
  }
}
