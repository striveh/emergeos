package io.emergeos.core.application;

import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact server-owned policy for one depth-one read-only Worker route. */
public interface ReadOnlyWorkerProfile {

  String id();

  String registryVersion();

  String workerName();

  int maxModelSteps();

  int maxToolCalls();

  long deadlineMs();

  String agentVersion();

  String verifierVersion();

  String harnessVersion();

  String expectedContextPolicyVersion();

  String expectedToolRegistryVersion();

  default String parentToolRegistryVersion() {
    return expectedToolRegistryVersion();
  }

  default String childToolRegistryVersion() {
    return expectedToolRegistryVersion();
  }

  String validatePreparation(
      TaskEnvelope parent,
      WorkerHandoffRequest request,
      AgentWorkerRuntime.ExecutionWindow window);

  void requireParentBinding(TaskEnvelope parent);

  void requireParentProfileBinding(AgentExecutionProfile parentProfile);

  AgentExecutionProfile parentExecutionProfile();

  TaskEnvelope newChildTask(
      TaskEnvelope parent,
      WorkerHandoffRequest request,
      AgentWorkerRuntime.ExecutionWindow window,
      String childTaskId);

  void requireChildBinding(TaskEnvelope parent, TaskEnvelope child);

  Map<String, String> componentVersions();

  String fingerprint();

  default boolean modelBound() {
    return false;
  }

  /**
   * Whether the non-model parent must copy the single child's usage exactly.
   *
   * <p>Historical Fake Worker profiles may account for parent-side model
   * work in addition to child usage. A child-only model route has no such
   * parent provider work, so accepting larger parent usage would create
   * unverified metering truth.
   */
  default boolean requiresExactParentUsageAggregation() {
    return false;
  }

  default HarnessExperiment experiment() {
    return null;
  }

  /**
   * Component identity copied into the parent Bundle.
   *
   * <p>The parent remains honest about its own Model route. A model-bound
   * child is represented through the Worker profile fingerprint rather than
   * pretending the parent used that Model directly.
   */
  default Map<String, String> parentComponentVersions() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("worker-registry", registryVersion());
    values.put("worker-profile-fingerprint", fingerprint());
    return Map.copyOf(values);
  }

  default Map<String, String> expectedParentComponentVersions() {
    return parentExecutionProfile().componentVersions(this);
  }
}
