package io.emergeos.core.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded V16 exact-pico challenge for reviewed public-key verification. */
public record GraphExactPicoProviderValidationChallenge(
    String protocolVersion,
    String databaseName,
    long databaseOid,
    long schemaOid,
    long attestorRoleOid,
    String principalId,
    String attemptId,
    String manifestHash,
    String requirementHash,
    String baseValidationPolicyHash,
    String keyId,
    String keyFingerprint,
    UUID validationNonce,
    Instant issuedAt,
    Instant expiresAt,
    String statementHash,
    String attributionHash,
    String eventHash,
    String overlayHeadHash,
    GraphProviderValidationDecision decision,
    String decisionHash,
    GraphAttributedFailureCode failureCode,
    String challengeHash,
    String transcriptHash) {

  public static final String PROTOCOL_VERSION = "PICO_OVERLAY_V1";
  private static final String CHALLENGE_DOMAIN =
      "emergeos.exact-provider-validation-challenge.v16";
  private static final String TRANSCRIPT_DOMAIN =
      "emergeos.exact-provider-validation-transcript.v16";

  public GraphExactPicoProviderValidationChallenge {
    if (!PROTOCOL_VERSION.equals(protocolVersion)
        || !databaseName(databaseName)
        || !oid(databaseOid)
        || !oid(schemaOid)
        || !oid(attestorRoleOid)
        || keyId == null
        || !keyId.matches("[a-z][a-z0-9._-]{0,99}")) {
      throw new IllegalArgumentException(
          "exact provider validation challenge identity is invalid");
    }
    principalId =
        GraphAttemptDomains.safeName(principalId, "principalId");
    attemptId = GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash =
        GraphAttemptDomains.hash(manifestHash, "manifestHash");
    requirementHash =
        GraphAttemptDomains.hash(requirementHash, "requirementHash");
    baseValidationPolicyHash =
        GraphAttemptDomains.hash(
            baseValidationPolicyHash, "baseValidationPolicyHash");
    keyFingerprint =
        GraphAttemptDomains.hash(keyFingerprint, "keyFingerprint");
    statementHash =
        GraphAttemptDomains.hash(statementHash, "statementHash");
    attributionHash =
        GraphAttemptDomains.hash(attributionHash, "attributionHash");
    eventHash = GraphAttemptDomains.hash(eventHash, "eventHash");
    overlayHeadHash =
        GraphAttemptDomains.hash(overlayHeadHash, "overlayHeadHash");
    decision = Objects.requireNonNull(decision, "decision");
    decisionHash =
        GraphAttemptDomains.hash(decisionHash, "decisionHash");
    challengeHash =
        GraphAttemptDomains.hash(challengeHash, "challengeHash");
    transcriptHash =
        GraphAttemptDomains.hash(transcriptHash, "transcriptHash");
    validationNonce =
        Objects.requireNonNull(validationNonce, "validationNonce");
    issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(issuedAt)
        || decision != GraphProviderValidationDecision.STRUCTURED_FINAL
        || failureCode != null) {
      throw new IllegalArgumentException(
          "exact provider validation challenge semantics are invalid");
    }
    long issuedMicros =
        GraphProviderValidationCanonical.epochMicros(
            issuedAt, "issuedAt");
    long expiresMicros =
        GraphProviderValidationCanonical.epochMicros(
            expiresAt, "expiresAt");
    String expectedChallenge =
        GraphProviderValidationCanonical.hash(
            CHALLENGE_DOMAIN,
            protocolVersion,
            databaseName,
            Long.toString(databaseOid),
            Long.toString(schemaOid),
            Long.toString(attestorRoleOid),
            principalId,
            attemptId,
            manifestHash,
            requirementHash,
            baseValidationPolicyHash,
            keyId,
            keyFingerprint,
            validationNonce.toString(),
            Long.toString(issuedMicros),
            Long.toString(expiresMicros));
    String expectedTranscript =
        GraphProviderValidationCanonical.hash(
            TRANSCRIPT_DOMAIN,
            expectedChallenge,
            statementHash,
            attributionHash,
            eventHash,
            overlayHeadHash,
            decision.name(),
            decisionHash,
            "");
    if (!expectedChallenge.equals(challengeHash)
        || !expectedTranscript.equals(transcriptHash)) {
      throw new IllegalArgumentException(
          "exact provider validation challenge hash is inconsistent");
    }
  }

  public byte[] signatureMaterial() {
    return GraphProviderValidationCanonical.frame(
        TRANSCRIPT_DOMAIN,
        challengeHash,
        statementHash,
        attributionHash,
        eventHash,
        overlayHeadHash,
        decision.name(),
        decisionHash,
        "");
  }

  @Override
  public String toString() {
    return "GraphExactPicoProviderValidationChallenge[protocolVersion="
        + protocolVersion
        + ", transcriptHash="
        + transcriptHash
        + ", details=<redacted>]";
  }

  private static boolean oid(long value) {
    return value >= 1L && value <= 4_294_967_295L;
  }

  private static boolean databaseName(String value) {
    return value != null
        && !value.isEmpty()
        && value.indexOf('\0') < 0
        && value.getBytes(StandardCharsets.UTF_8).length <= 63;
  }
}
