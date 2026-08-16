package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable Receipt for the local logical-Undo overlay. */
public record LocalDraftUndoReceipt(
    String receiptId,
    String creationAttemptId,
    String draftId,
    String creationReceiptId,
    String artifactId,
    int artifactVersion,
    String artifactHash,
    String scopeSchema,
    String scopeHash,
    String undoNonce,
    Instant occurredAt) {

  public static final String RECEIPT_TYPE = "LOCAL_DRAFT_LOGICALLY_UNDONE_V1";
  public static final String EFFECT = "LOGICALLY_UNDONE";

  public LocalDraftUndoReceipt {
    requireText(receiptId, "receiptId");
    requireText(creationAttemptId, "creationAttemptId");
    requireText(draftId, "draftId");
    requireText(creationReceiptId, "creationReceiptId");
    requireText(artifactId, "artifactId");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    requireHash(artifactHash, "artifactHash");
    if (!LocalDraftUndoScope.SCHEMA.equals(scopeSchema)) {
      throw new IllegalArgumentException("scopeSchema is unsupported");
    }
    requireHash(scopeHash, "scopeHash");
    requireText(undoNonce, "undoNonce");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  public String receiptType() {
    return RECEIPT_TYPE;
  }

  public String effect() {
    return EFFECT;
  }

  public String retention() {
    return LocalDraftUndoScope.RETENTION;
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

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }
}
