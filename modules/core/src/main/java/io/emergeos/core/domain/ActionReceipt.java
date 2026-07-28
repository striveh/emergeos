package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record ActionReceipt(
    String receiptId,
    String attemptId,
    ActionAttemptStatus outcome,
    String externalId,
    String reasonCode,
    String providerRequestId,
    String rawResponseRef,
    Instant occurredAt,
    boolean simulated) {

  public ActionReceipt {
    requireText(receiptId, "receiptId");
    requireText(attemptId, "attemptId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (!outcome.isTerminal()) {
      throw new IllegalArgumentException("Receipt outcome must be terminal");
    }
    if (!simulated) {
      throw new IllegalArgumentException("S3 supports simulated Receipts only");
    }
    if (outcome == ActionAttemptStatus.SUCCEEDED) {
      requireText(externalId, "externalId");
      if (reasonCode != null) {
        throw new IllegalArgumentException("a successful Receipt must not contain a reasonCode");
      }
    } else {
      requireText(reasonCode, "reasonCode");
      if (externalId != null) {
        throw new IllegalArgumentException("a failed Receipt must not contain an externalId");
      }
    }
    requireText(providerRequestId, "providerRequestId");
    requireText(rawResponseRef, "rawResponseRef");
  }

  public static ActionReceipt succeeded(
      String receiptId,
      String attemptId,
      String externalId,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt,
      boolean simulated) {
    return new ActionReceipt(
        receiptId,
        attemptId,
        ActionAttemptStatus.SUCCEEDED,
        externalId,
        null,
        providerRequestId,
        rawResponseRef,
        occurredAt,
        simulated);
  }

  public static ActionReceipt failed(
      String receiptId,
      String attemptId,
      String reasonCode,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt,
      boolean simulated) {
    return new ActionReceipt(
        receiptId,
        attemptId,
        ActionAttemptStatus.FAILED,
        null,
        reasonCode,
        providerRequestId,
        rawResponseRef,
        occurredAt,
        simulated);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > 1000) {
      throw new IllegalArgumentException(name + " must be at most 1000 characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
  }
}
