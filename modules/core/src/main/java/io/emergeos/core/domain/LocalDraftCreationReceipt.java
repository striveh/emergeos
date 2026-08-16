package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

/** A durable Receipt for a local database effect, never a provider observation. */
public record LocalDraftCreationReceipt(
    String receiptId, String attemptId, String draftId, Instant occurredAt) {

  public static final String RECEIPT_TYPE = "LOCAL_DRAFT_CREATED_V1";

  public LocalDraftCreationReceipt {
    requireText(receiptId, "receiptId");
    requireText(attemptId, "attemptId");
    requireText(draftId, "draftId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public String receiptType() {
    return RECEIPT_TYPE;
  }

  public ActionAttemptStatus outcome() {
    return ActionAttemptStatus.SUCCEEDED;
  }

  public boolean simulated() {
    return false;
  }

  private static void requireText(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}
