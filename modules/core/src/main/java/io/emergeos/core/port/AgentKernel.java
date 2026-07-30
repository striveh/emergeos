package io.emergeos.core.port;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;

public interface AgentKernel {

  AgentRunOutcome run(TaskEnvelope task, CancellationSignal cancellation);

  /**
   * Returns the immutable execution profile identity attested by a live model route.
   *
   * <p>Legacy Fake kernels remain unbound. A model-bound service rejects an absent or mismatched
   * identity at composition time.
   */
  default String executionProfileId() {
    return null;
  }

  /**
   * Returns the exact execution profile fingerprint attested by this Kernel.
   *
   * <p>A model-bound Kernel must override this together with {@link #executionProfileId()}.
   * Provider-neutral and legacy Fake kernels leave both values {@code null}.
   */
  default String executionProfileFingerprint() {
    return null;
  }
}
