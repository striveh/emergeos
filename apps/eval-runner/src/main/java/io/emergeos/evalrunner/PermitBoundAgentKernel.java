package io.emergeos.evalrunner;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CancellationSignal;
import java.util.Objects;

final class PermitBoundAgentKernel implements AgentKernel {

  private final OneShotExecutionPermit permit;
  private final AgentKernel delegate;
  private final Runnable consumeObserver;

  PermitBoundAgentKernel(
      OneShotExecutionPermit permit, AgentKernel delegate) {
    this(permit, delegate, () -> {});
  }

  PermitBoundAgentKernel(
      OneShotExecutionPermit permit,
      AgentKernel delegate,
      Runnable consumeObserver) {
    this.permit = Objects.requireNonNull(permit, "permit");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.consumeObserver =
        Objects.requireNonNull(consumeObserver, "consumeObserver");
  }

  @Override
  public AgentRunOutcome run(
      TaskEnvelope task, CancellationSignal cancellation) {
    permit.consume(task);
    consumeObserver.run();
    return delegate.run(task, cancellation);
  }

  @Override
  public String executionProfileId() {
    return delegate.executionProfileId();
  }

  @Override
  public String executionProfileFingerprint() {
    return delegate.executionProfileFingerprint();
  }
}
