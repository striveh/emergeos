package io.emergeos.evalrunner;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentTaskAuthorizer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Process-local, one-shot authority for the exact frozen Pack008 graph.
 *
 * <p>The permit owns no credential, client, model or network object. Parent and
 * child Tasks are authorized separately; only the exact child may consume the
 * one execution opportunity.
 */
final class Pack008GraphExecutionPermit {

  enum State {
    PREPARED,
    ARMED,
    CONSUMED
  }

  private static final Duration MAX_TTL = Duration.ofMinutes(5);
  private static final int PARENT_AUTHORIZED = 1;
  private static final int CHILD_AUTHORIZED = 1 << 1;

  private final LongSupplier nanoTime;
  private final AtomicReference<PermitState> state =
      new AtomicReference<>(
          new PermitState(State.PREPARED, 0, 0));

  Pack008GraphExecutionPermit(LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  void arm(String approvedAttemptId, Duration ttl) {
    if (!Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID.equals(
        approvedAttemptId)) {
      throw rejected("GRAPH_PERMIT_ATTEMPT_MISMATCH");
    }
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero()
        || ttl.isNegative()
        || ttl.compareTo(MAX_TTL) > 0) {
      throw rejected("GRAPH_PERMIT_TTL_INVALID");
    }
    long expiresAtNanos;
    try {
      expiresAtNanos =
          Math.addExact(nanoTime.getAsLong(), ttl.toNanos());
    } catch (ArithmeticException overflow) {
      throw rejected("GRAPH_PERMIT_TTL_INVALID");
    }
    PermitState prepared = state.get();
    if (prepared.phase() != State.PREPARED
        || !state.compareAndSet(
            prepared,
            new PermitState(State.ARMED, expiresAtNanos, 0))) {
      throw rejected("GRAPH_PERMIT_ALREADY_ARMED");
    }
  }

  AgentTaskAuthorizer parentAuthorizer() {
    return this::authorizeParent;
  }

  AgentTaskAuthorizer childAuthorizer() {
    return this::authorizeChild;
  }

  void consumeChild(AgentRunContext context) {
    Objects.requireNonNull(context, "context");
    TaskEnvelope task = context.task();
    Objects.requireNonNull(task, "task");
    if (isExactParent(task)) {
      throw rejected("GRAPH_PERMIT_WRONG_CONSUMER");
    }
    if (!Pack008WorkerEvalCatalog.CHILD_RUN_ID.equals(context.runId())
        || !Pack008WorkerEvalCatalog.PRINCIPAL_ID.equals(
            context.principalId())) {
      throw rejected("GRAPH_PERMIT_CHILD_CONTEXT_MISMATCH");
    }
    requireExactChild(task);
    while (true) {
      PermitState armed = requireActive();
      if ((armed.authorizationMask() & PARENT_AUTHORIZED) == 0) {
        throw rejected("GRAPH_PERMIT_PARENT_NOT_AUTHORIZED");
      }
      if ((armed.authorizationMask() & CHILD_AUTHORIZED) == 0) {
        throw rejected("GRAPH_PERMIT_CHILD_NOT_AUTHORIZED");
      }
      PermitState consumed =
          new PermitState(
              State.CONSUMED,
              armed.expiresAtNanos(),
              armed.authorizationMask());
      if (!state.compareAndSet(armed, consumed)) {
        continue;
      }
      if (burnIfExpired(consumed)) {
        throw rejected("GRAPH_PERMIT_EXPIRED");
      }
      return;
    }
  }

  State state() {
    return state.get().phase();
  }

  boolean parentAuthorized() {
    return (state.get().authorizationMask() & PARENT_AUTHORIZED) != 0;
  }

  boolean childAuthorized() {
    return (state.get().authorizationMask() & CHILD_AUTHORIZED) != 0;
  }

  private void authorizeParent(TaskEnvelope task) {
    requireExactParent(task);
    authorize(PARENT_AUTHORIZED, 0, null);
  }

  private void authorizeChild(TaskEnvelope task) {
    requireExactChild(task);
    authorize(
        CHILD_AUTHORIZED,
        PARENT_AUTHORIZED,
        "GRAPH_PERMIT_PARENT_NOT_AUTHORIZED");
  }

  private void authorize(
      int authority,
      int prerequisite,
      String prerequisiteFailure) {
    while (true) {
      PermitState armed = requireActive();
      if ((armed.authorizationMask() & prerequisite) != prerequisite) {
        throw rejected(prerequisiteFailure);
      }
      if ((armed.authorizationMask() & authority) == authority) {
        return;
      }
      PermitState updated =
          new PermitState(
              State.ARMED,
              armed.expiresAtNanos(),
              armed.authorizationMask() | authority);
      if (!state.compareAndSet(armed, updated)) {
        continue;
      }
      if (burnIfExpired(updated)) {
        throw rejected("GRAPH_PERMIT_EXPIRED");
      }
      return;
    }
  }

  private PermitState requireActive() {
    PermitState observed = state.get();
    if (observed.phase() != State.ARMED) {
      throw rejected(
          observed.phase() == State.CONSUMED
              ? "GRAPH_PERMIT_ALREADY_CONSUMED"
              : "GRAPH_PERMIT_NOT_ARMED");
    }
    if (burnIfExpired(observed)) {
      throw rejected("GRAPH_PERMIT_EXPIRED");
    }
    return observed;
  }

  private boolean burnIfExpired(PermitState observed) {
    if (nanoTime.getAsLong() < observed.expiresAtNanos()) {
      return false;
    }
    state.compareAndSet(
        observed,
        new PermitState(
            State.CONSUMED,
            observed.expiresAtNanos(),
            observed.authorizationMask()));
    return true;
  }

  private static void requireExactParent(TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    try {
      Pack008WorkerEvalCatalog.parentProfile().requireTaskBinding(task);
      Pack008WorkerEvalCatalog.workerProfile().requireParentBinding(task);
    } catch (RuntimeException mismatch) {
      throw rejected("GRAPH_PERMIT_PARENT_TASK_MISMATCH");
    }
    if (!Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH.equals(
        IntegrityHashes.taskHash(task))) {
      throw rejected("GRAPH_PERMIT_PARENT_TASK_MISMATCH");
    }
  }

  private static void requireExactChild(TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    try {
      Pack008WorkerEvalCatalog.workerProfile()
          .requireChildBinding(
              Pack008WorkerEvalCatalog.parentTask(), task);
    } catch (RuntimeException mismatch) {
      throw rejected("GRAPH_PERMIT_CHILD_TASK_MISMATCH");
    }
    if (!Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH.equals(
        IntegrityHashes.taskHash(task))) {
      throw rejected("GRAPH_PERMIT_CHILD_TASK_MISMATCH");
    }
  }

  private static boolean isExactParent(TaskEnvelope task) {
    try {
      requireExactParent(task);
      return true;
    } catch (Rejected mismatch) {
      return false;
    }
  }

  private record PermitState(
      State phase, long expiresAtNanos, int authorizationMask) {}

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
