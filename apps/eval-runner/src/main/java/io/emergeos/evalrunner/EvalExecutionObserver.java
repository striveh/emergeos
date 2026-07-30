package io.emergeos.evalrunner;

@FunctionalInterface
interface EvalExecutionObserver {

  void observed(Phase phase);

  static EvalExecutionObserver noop() {
    return ignored -> {};
  }

  enum Phase {
    PREFLIGHT_VERIFIED,
    TTY_VERIFIED,
    MARKER_CREATED,
    CHALLENGE_VERIFIED,
    PERMIT_ARMED,
    TASK_AUTHORIZED,
    CREDENTIAL_READ_STARTED,
    CLIENT_CREATED,
    MODEL_CREATED,
    RUN_STARTED,
    PERMIT_CONSUMED,
    PROVIDER_SDK_CREATE
  }
}
