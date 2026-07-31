package io.emergeos.core.port;

import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow persistence capability for read-only Workers.
 *
 * <p>Unlike AgentRunStore, this port has no Artifact parameter. A Worker cannot obtain an Artifact
 * write capability through this boundary.
 */
public interface ReadOnlyWorkerRunStore {

  AgentRun startWorker(AgentRunContext parent, AgentRun running);

  WorkerCompletion completeWorker(
      AgentRunContext parent,
      AgentRun terminal,
      WorkerResultEnvelope workerResult);

  Optional<WorkerCompletion> findWorkerOwned(
      AgentRunContext parent, String childRunId);

  record WorkerCompletion(
      AgentRun run, WorkerResultEnvelope workerResult) {

    public WorkerCompletion {
      Objects.requireNonNull(run, "run");
      if (!run.lifecycle().terminal()) {
        throw new IllegalArgumentException(
            "WorkerCompletion requires terminal child truth");
      }
      if (run.result().status() == RunStatus.SUCCEEDED
          && "PROPOSE_ARTICLE_DRAFT".equals(run.task().kind())) {
        Objects.requireNonNull(workerResult, "workerResult");
        if (!run.runId().equals(workerResult.childRunId())
            || !run.task().id().equals(workerResult.childTaskId())) {
          throw new IllegalArgumentException(
              "Worker Result does not belong to its child Run");
        }
      } else if (workerResult != null) {
        throw new IllegalArgumentException(
            "Only a successful proposal Worker can have a Worker Result");
      }
    }
  }
}
