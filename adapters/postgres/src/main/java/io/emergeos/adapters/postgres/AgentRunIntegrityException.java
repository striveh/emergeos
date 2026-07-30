package io.emergeos.adapters.postgres;

public final class AgentRunIntegrityException extends RuntimeException {

  public AgentRunIntegrityException() {
    super("Stored AgentRun cannot be verified");
  }

  AgentRunIntegrityException(Throwable cause) {
    super("Stored AgentRun cannot be verified", cause);
  }
}
