package io.emergeos.evalrunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

final class OneShotOperatorGate {

  static final Duration PERMIT_TTL = Duration.ofSeconds(30);
  static final String CHALLENGE_PREFIX = "EXECUTE ";

  private final InteractiveConsole console;
  private final PosixAttemptMarkerStore markers;
  private final EvalExecutionObserver observer;

  OneShotOperatorGate(
      InteractiveConsole console, PosixAttemptMarkerStore markers) {
    this(console, markers, EvalExecutionObserver.noop());
  }

  OneShotOperatorGate(
      InteractiveConsole console,
      PosixAttemptMarkerStore markers,
      EvalExecutionObserver observer) {
    this.console = Objects.requireNonNull(console, "console");
    this.markers = Objects.requireNonNull(markers, "markers");
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  Path authorize(OneShotExecutionPermit permit) {
    Objects.requireNonNull(permit, "permit");
    if (!console.available()) {
      throw rejected("REAL_TTY_REQUIRED");
    }
    observer.observed(EvalExecutionObserver.Phase.TTY_VERIFIED);
    Path marker =
        markers.acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
    observer.observed(EvalExecutionObserver.Phase.MARKER_CREATED);
    String expected =
        CHALLENGE_PREFIX + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID;
    String entered =
        console.readLine(
            "输入完整 challenge 以批准这一次 synthetic model attempt:\n"
                + expected
                + "\n> ");
    if (!expected.equals(entered)) {
      throw rejected("CHALLENGE_MISMATCH");
    }
    observer.observed(EvalExecutionObserver.Phase.CHALLENGE_VERIFIED);
    permit.arm(PERMIT_TTL);
    observer.observed(EvalExecutionObserver.Phase.PERMIT_ARMED);
    permit.authorize(SyntheticEvalCatalog.task());
    observer.observed(EvalExecutionObserver.Phase.TASK_AUTHORIZED);
    return marker;
  }

  interface InteractiveConsole {
    boolean available();

    String readLine(String prompt);
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
