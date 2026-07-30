package io.emergeos.evalrunner;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.port.AgentTaskAuthorizer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class OneShotExecutionPermit implements AgentTaskAuthorizer {

  enum State {
    PREPARED,
    ARMED,
    CONSUMED
  }

  private static final Duration MAX_TTL = Duration.ofMinutes(5);

  private final LongSupplier nanoTime;
  private final AtomicReference<PermitState> state =
      new AtomicReference<>(new PermitState(State.PREPARED, 0));

  OneShotExecutionPermit(LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  void arm(Duration ttl) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
      throw rejected("PERMIT_TTL_INVALID");
    }
    long expiresAtNanos;
    try {
      expiresAtNanos =
          Math.addExact(nanoTime.getAsLong(), ttl.toNanos());
    } catch (ArithmeticException overflow) {
      throw rejected("PERMIT_TTL_INVALID");
    }
    PermitState prepared = state.get();
    if (prepared.phase() != State.PREPARED
        || !state.compareAndSet(
            prepared, new PermitState(State.ARMED, expiresAtNanos))) {
      throw rejected("PERMIT_ALREADY_ARMED");
    }
  }

  @Override
  public void authorize(TaskEnvelope task) {
    requireActiveBinding(task);
  }

  void consume(TaskEnvelope task) {
    PermitState armed = requireActiveBinding(task);
    if (!state.compareAndSet(
        armed, new PermitState(State.CONSUMED, armed.expiresAtNanos()))) {
      throw rejected("PERMIT_ALREADY_CONSUMED");
    }
    if (nanoTime.getAsLong() >= armed.expiresAtNanos()) {
      throw rejected("PERMIT_EXPIRED");
    }
  }

  State state() {
    return state.get().phase();
  }

  private PermitState requireActiveBinding(TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    try {
      SyntheticEvalCatalog.profile().requireTaskBinding(task);
    } catch (RuntimeException mismatch) {
      throw rejected("PERMIT_TASK_MISMATCH");
    }
    if (!SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
        IntegrityHashes.taskHash(task))) {
      throw rejected("PERMIT_TASK_MISMATCH");
    }
    PermitState observed = state.get();
    if (observed.phase() != State.ARMED) {
      throw rejected(
          observed.phase() == State.CONSUMED
              ? "PERMIT_ALREADY_CONSUMED"
              : "PERMIT_NOT_ARMED");
    }
    if (nanoTime.getAsLong() >= observed.expiresAtNanos()) {
      throw rejected("PERMIT_EXPIRED");
    }
    return observed;
  }

  private record PermitState(State phase, long expiresAtNanos) {}

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
