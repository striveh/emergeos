package io.emergeos.core.port;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;

public interface AgentKernel {

  AgentRunOutcome run(TaskEnvelope task, CancellationSignal cancellation);
}
