package io.emergeos.evalrunner;

import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunContext;
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
      AgentRunContext context, CancellationSignal cancellation) {
    permit.consume(context.task());
    consumeObserver.run();
    return delegate.run(context, cancellation);
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
