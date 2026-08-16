package io.emergeos.core.domain;

public enum ActionAttemptStatus {
  PLANNED,
  DISPATCHING,
  UNKNOWN,
  RECONCILING,
  SUCCEEDED,
  FAILED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED;
  }

  public boolean canTransitionTo(ActionAttemptStatus next) {
    return switch (this) {
      case PLANNED -> next == DISPATCHING || next == SUCCEEDED;
      case DISPATCHING -> next == SUCCEEDED || next == FAILED || next == UNKNOWN;
      case UNKNOWN -> next == RECONCILING;
      case RECONCILING -> next == SUCCEEDED || next == FAILED || next == UNKNOWN;
      case SUCCEEDED, FAILED -> false;
    };
  }
}
