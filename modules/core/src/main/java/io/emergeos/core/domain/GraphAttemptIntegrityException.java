package io.emergeos.core.domain;

public final class GraphAttemptIntegrityException
    extends RuntimeException {

  public GraphAttemptIntegrityException() {
    super("durable graph attempt integrity verification failed");
  }

  public GraphAttemptIntegrityException(Throwable cause) {
    super(
        "durable graph attempt integrity verification failed",
        cause,
        false,
        false);
  }
}
