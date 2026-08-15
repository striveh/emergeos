package io.emergeos.core.application;

/** The requested nonce or target is already bound to a different logical Undo. */
public final class LocalDraftUndoConflictException extends IllegalStateException {

  public LocalDraftUndoConflictException() {
    super("Logical Undo conflicts with an existing local Receipt");
  }
}
