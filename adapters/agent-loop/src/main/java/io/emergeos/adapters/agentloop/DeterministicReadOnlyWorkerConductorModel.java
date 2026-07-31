package io.emergeos.adapters.agentloop;

import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.ContentHashes;
import java.util.List;
import java.util.Objects;

/**
 * Frozen deterministic Conductor used to isolate the child Model variable.
 *
 * <p>It has no provider route and no Tool call. The first step requests the
 * exact read-only Worker; the second step returns that Worker's already
 * verified proposal without editing it.
 */
public final class DeterministicReadOnlyWorkerConductorModel
    implements AgentModel {

  public static final String MODEL_ID = "fake-model-v1";
  public static final String PROTOCOL_VERSION =
      "deterministic-read-only-worker-conductor-v1";
  public static final String INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT =
      ContentHashes.sha256(
          PROTOCOL_VERSION
              + "\nmodelId="
              + MODEL_ID
              + "\nworkerName="
              + ReadOnlyWorkerExecutionProfile.WORKER_NAME
              + "\nfirstDecision=exact-one-worker-call"
              + "\nworkerIntent=parent-task-intent"
              + "\nworkerInputs=parent-task-input-refs"
              + "\nsecondDecision=single-worker-result-pass-through"
              + "\nmalformed=MALFORMED_WORKER_RESULT");
  private static final String FIXED_WORKER_INTENT =
      "产出一篇引用该 Capture 的短文 proposal";

  private final boolean inheritParentIntent;

  public DeterministicReadOnlyWorkerConductorModel() {
    this(false);
  }

  private DeterministicReadOnlyWorkerConductorModel(
      boolean inheritParentIntent) {
    this.inheritParentIntent = inheritParentIntent;
  }

  public static DeterministicReadOnlyWorkerConductorModel
      inheritingParentIntent() {
    return new DeterministicReadOnlyWorkerConductorModel(true);
  }

  @Override
  public Session open(io.emergeos.contracts.TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    return (turn, context) ->
        new ModelStep(
            decide(turn, inheritParentIntent),
            MODEL_ID,
            ModelUsage.zero());
  }

  public static Decision decide(Turn turn) {
    return decide(turn, false);
  }

  private static Decision decide(
      Turn turn, boolean inheritParentIntent) {
    Objects.requireNonNull(turn, "turn");
    if (turn.workerResults().isEmpty()) {
      return new WorkerCall(
          ReadOnlyWorkerExecutionProfile.WORKER_NAME,
          inheritParentIntent
              ? turn.task().intent()
              : FIXED_WORKER_INTENT,
          turn.task().inputRefs());
    }
    if (turn.workerResults().size() != 1
        || !turn.toolResults().isEmpty()) {
      return new Failed("MALFORMED_WORKER_RESULT");
    }
    WorkerResult result = turn.workerResults().getFirst();
    return new FinalDraft(result.content(), result.evidenceRefs());
  }
}
