package io.emergeos.core.domain;

import java.util.Objects;

/** Bounded signed statement produced after exact request-2 validation. */
public record GraphProviderValidationTranscript(
    GraphProviderValidationChallenge challenge,
    GraphAttemptCursor expected,
    GraphProviderAttribution attribution,
    String executionBindingHash,
    String transportProfileHash,
    String parserProfileHash,
    String schemaProfileHash,
    GraphProviderValidationDecision decision,
    String decisionHash,
    GraphAttributedFailureCode failureCode,
    String transcriptHash) {

  private static final String TRANSCRIPT_DOMAIN =
      "emergeos.provider-validation-transcript.v1";

  public GraphProviderValidationTranscript {
    challenge = Objects.requireNonNull(challenge, "challenge");
    expected = Objects.requireNonNull(expected, "expected");
    attribution = Objects.requireNonNull(attribution, "attribution");
    decision = Objects.requireNonNull(decision, "decision");
    if (!challenge.principalId().equals(expected.principalId())
        || !challenge.attemptId().equals(expected.attemptId())
        || !challenge.manifestHash().equals(expected.manifestHash())
        || expected.lastSequence() != 13
        || expected.phase() != GraphAttemptPhase.PROVIDER_PENDING
        || attribution.requestOrdinal() != 2
        || (decision == GraphProviderValidationDecision.STRUCTURED_FINAL
            && failureCode != null)
        || (decision == GraphProviderValidationDecision.FAILED
            && failureCode == null)) {
      throw new IllegalArgumentException(
          "provider validation transcript identity is invalid");
    }
    executionBindingHash =
        GraphAttemptDomains.hash(
            executionBindingHash, "executionBindingHash");
    if (!executionBindingHash.equals(expected.manifestHash())) {
      throw new IllegalArgumentException(
          "provider validation transcript execution binding is invalid");
    }
    transportProfileHash =
        GraphAttemptDomains.hash(
            transportProfileHash, "transportProfileHash");
    parserProfileHash =
        GraphAttemptDomains.hash(parserProfileHash, "parserProfileHash");
    schemaProfileHash =
        GraphAttemptDomains.hash(schemaProfileHash, "schemaProfileHash");
    decisionHash =
        GraphAttemptDomains.hash(decisionHash, "decisionHash");
    if (!challenge.transportProfileHash().equals(transportProfileHash)
        || !challenge.parserProfileHash().equals(parserProfileHash)
        || !challenge.schemaProfileHash().equals(schemaProfileHash)) {
      throw new IllegalArgumentException(
          "provider validation transcript profile is invalid");
    }
    transcriptHash =
        GraphAttemptDomains.hash(transcriptHash, "transcriptHash");
    String expectedHash =
        GraphProviderValidationCanonical.hash(
            TRANSCRIPT_DOMAIN,
            challenge.challengeHash(),
            Integer.toString(expected.lastSequence()),
            expected.headHash(),
            executionBindingHash,
            Integer.toString(attribution.requestOrdinal()),
            attribution.requestHash(),
            attribution.responseHash(),
            attribution.attributionHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            decision.name(),
            decisionHash,
            failureCode == null ? "" : failureCode.name());
    if (!expectedHash.equals(transcriptHash)) {
      throw new IllegalArgumentException(
          "provider validation transcript hash is inconsistent");
    }
  }

  public static GraphProviderValidationTranscript create(
      GraphProviderValidationChallenge challenge,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      String executionBindingHash,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      GraphProviderValidationDecision decision,
      String decisionHash,
      GraphAttributedFailureCode failureCode) {
    Objects.requireNonNull(challenge, "challenge");
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(attribution, "attribution");
    Objects.requireNonNull(decision, "decision");
    String hash =
        GraphProviderValidationCanonical.hash(
            TRANSCRIPT_DOMAIN,
            challenge.challengeHash(),
            Integer.toString(expected.lastSequence()),
            expected.headHash(),
            executionBindingHash,
            Integer.toString(attribution.requestOrdinal()),
            attribution.requestHash(),
            attribution.responseHash(),
            attribution.attributionHash(),
            transportProfileHash,
            parserProfileHash,
            schemaProfileHash,
            decision.name(),
            decisionHash,
            failureCode == null ? "" : failureCode.name());
    return new GraphProviderValidationTranscript(
        challenge,
        expected,
        attribution,
        executionBindingHash,
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash,
        decision,
        decisionHash,
        failureCode,
        hash);
  }

  public byte[] signatureMaterial() {
    return GraphProviderValidationCanonical.frame(
        TRANSCRIPT_DOMAIN,
        challenge.challengeHash(),
        Integer.toString(expected.lastSequence()),
        expected.headHash(),
        executionBindingHash,
        Integer.toString(attribution.requestOrdinal()),
        attribution.requestHash(),
        attribution.responseHash(),
        attribution.attributionHash(),
        transportProfileHash,
        parserProfileHash,
        schemaProfileHash,
        decision.name(),
        decisionHash,
        failureCode == null ? "" : failureCode.name());
  }
}
