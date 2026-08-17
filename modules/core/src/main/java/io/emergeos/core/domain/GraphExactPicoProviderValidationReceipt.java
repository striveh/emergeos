package io.emergeos.core.domain;

import java.util.Objects;

/** Typed V16 overlay completion receipt; it is not a legacy graph cursor. */
public record GraphExactPicoProviderValidationReceipt(
    String protocolVersion,
    int overlaySequence,
    long overlayStateVersion,
    String overlayHeadHash,
    String statementHash,
    String attributionHash,
    String eventHash,
    String transcriptHash,
    String validationReceiptHash,
    ValidationState validationState) {

  public enum ValidationState {
    CONSUMED
  }

  public GraphExactPicoProviderValidationReceipt {
    validationState = Objects.requireNonNull(validationState, "validationState");
    if (!GraphExactPicoProviderValidationChallenge.PROTOCOL_VERSION.equals(
            protocolVersion)
        || overlaySequence != 14
        || overlayStateVersion != 1L
        || validationState != ValidationState.CONSUMED) {
      throw new IllegalArgumentException(
          "exact pico provider validation receipt state is invalid");
    }
    overlayHeadHash =
        GraphAttemptDomains.hash(overlayHeadHash, "overlayHeadHash");
    statementHash = GraphAttemptDomains.hash(statementHash, "statementHash");
    attributionHash =
        GraphAttemptDomains.hash(attributionHash, "attributionHash");
    eventHash = GraphAttemptDomains.hash(eventHash, "eventHash");
    transcriptHash =
        GraphAttemptDomains.hash(transcriptHash, "transcriptHash");
    validationReceiptHash =
        GraphAttemptDomains.hash(
            validationReceiptHash, "validationReceiptHash");
  }
}
