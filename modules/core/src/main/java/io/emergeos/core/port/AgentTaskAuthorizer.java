package io.emergeos.core.port;

import io.emergeos.contracts.TaskEnvelope;

/**
 * Pre-start authorization boundary for a fully constructed server-owned Task.
 *
 * <p>Implementations must fail before {@code AgentRunStore.start} when a Task is not authorized.
 * Structural profile binding, PUBLIC classification, or a capability string is not authorization.
 */
@FunctionalInterface
public interface AgentTaskAuthorizer {

  AgentTaskAuthorizer ALLOW_ALL = task -> {};

  void authorize(TaskEnvelope task);

  static AgentTaskAuthorizer allowAll() {
    return ALLOW_ALL;
  }
}
