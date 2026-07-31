package io.emergeos.evalrunner;

import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import java.util.Objects;

/**
 * Child-only execution boundary for a consumed Pack008 graph permit.
 *
 * <p>The observer is called after atomic permit consumption and before the
 * delegate. A durable implementation can fsync the consume fact there; failure
 * burns the permit and prevents the delegate from running.
 */
final class Pack008GraphPermitBoundAgentKernel implements AgentKernel {

  private final Pack008GraphExecutionPermit permit;
  private final AgentKernel delegate;
  private final Runnable consumeObserver;

  Pack008GraphPermitBoundAgentKernel(
      Pack008GraphExecutionPermit permit, AgentKernel delegate) {
    this(permit, delegate, () -> {});
  }

  Pack008GraphPermitBoundAgentKernel(
      Pack008GraphExecutionPermit permit,
      AgentKernel delegate,
      Runnable consumeObserver) {
    this.permit = Objects.requireNonNull(permit, "permit");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.consumeObserver =
        Objects.requireNonNull(consumeObserver, "consumeObserver");
    if (!Pack008WorkerEvalCatalog.workerProfile().id().equals(
            delegate.executionProfileId())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_WORKER_PROFILE_FINGERPRINT
            .equals(delegate.executionProfileFingerprint())
        || delegate.workerRegistryVersion() != null
        || delegate.workerProfileFingerprint() != null) {
      throw new IllegalArgumentException(
          "Pack008 permit can bind only an exact non-delegating child execution profile");
    }
  }

  @Override
  public AgentRunOutcome run(
      AgentRunContext context, CancellationSignal cancellation) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(cancellation, "cancellation");
    permit.consumeChild(context);
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

  @Override
  public String workerRegistryVersion() {
    return delegate.workerRegistryVersion();
  }

  @Override
  public String workerProfileFingerprint() {
    return delegate.workerProfileFingerprint();
  }
}
