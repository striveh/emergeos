package io.emergeos.core.application;

public final class ActionIdempotencyConflictException extends RuntimeException {

  public ActionIdempotencyConflictException() {
    super("Action idempotency key conflicts with another request");
  }
}
