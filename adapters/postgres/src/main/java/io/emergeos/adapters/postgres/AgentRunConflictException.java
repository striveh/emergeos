package io.emergeos.adapters.postgres;

public final class AgentRunConflictException extends RuntimeException {

  public AgentRunConflictException() {
    super("AgentRun has a conflicting stored state");
  }
}
