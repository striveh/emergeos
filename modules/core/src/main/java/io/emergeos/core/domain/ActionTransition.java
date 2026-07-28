package io.emergeos.core.domain;

import java.time.Instant;
import java.util.Objects;

public record ActionTransition(
    int sequence,
    ActionAttemptStatus fromStatus,
    ActionAttemptStatus toStatus,
    Instant occurredAt) {

  public ActionTransition {
    Objects.requireNonNull(toStatus, "toStatus");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    if (fromStatus == null) {
      if (sequence != 1 || toStatus != ActionAttemptStatus.PLANNED) {
        throw new IllegalArgumentException("only the first transition may enter PLANNED");
      }
    } else if (sequence == 1 || !fromStatus.canTransitionTo(toStatus)) {
      throw new IllegalArgumentException(
          "invalid ActionAttempt transition " + fromStatus + " -> " + toStatus);
    }
  }
}
