package io.emergeos.core.domain;

public final class GraphAttemptConflictException
    extends RuntimeException {

  public GraphAttemptConflictException(String message) {
    super(message, null, false, false);
  }
}
