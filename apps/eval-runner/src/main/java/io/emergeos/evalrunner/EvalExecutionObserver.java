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
    GATE_APPROVED_DURABLE,
    CREDENTIAL_READ_STARTED,
    CLIENT_CREATED,
    MODEL_CREATED,
    RUN_STARTED,
    PERMIT_CONSUMED,
    PROVIDER_SDK_CREATE_INTENT_DURABLE,
    PROVIDER_SDK_CREATE,
    PROVIDER_ATTRIBUTED_DURABLE,
    RUN_RECORD_PENDING_DURABLE,
    RUN_RECORD_LINK_COMMIT_COMPLETE,
    RUN_RECORD_FINAL_DURABLE,
    TERMINAL_JOURNAL_DURABLE
  }
}
