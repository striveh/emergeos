package io.emergeos.core.domain;

import java.util.Objects;

/** Exact optimistic cursor for one durable graph head. */
public record GraphAttemptCursor(
    String principalId,
    String attemptId,
    String manifestHash,
    long stateVersion,
    int lastSequence,
    String headHash,
    GraphAttemptPhase phase) {

  public GraphAttemptCursor {
    principalId =
        GraphAttemptDomains.safeName(principalId, "principalId");
    attemptId =
        GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash =
        GraphAttemptDomains.hash(
            manifestHash, "manifestHash");
    if (stateVersion < 1
        || stateVersion > 9_007_199_254_740_991L
        || lastSequence < 1
        || stateVersion != lastSequence) {
      throw new IllegalArgumentException(
          "graph cursor version/sequence is invalid");
    }
    headHash = GraphAttemptDomains.hash(headHash, "headHash");
    phase = Objects.requireNonNull(phase, "phase");
  }
}
