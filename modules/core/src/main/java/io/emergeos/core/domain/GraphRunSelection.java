package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact durable Task/profile selection reserved for one graph role. */
public record GraphRunSelection(
    GraphRunRole role,
    String runId,
    String taskId,
    String taskHash,
    String executionProfileId,
    String executionProfileFingerprint,
    String workerRegistryVersion,
    String workerProfileId,
    String workerProfileFingerprint) {

  private static final String SELECTION_DOMAIN =
      "emergeos.graph-run-selection.v1";

  public GraphRunSelection {
    role = Objects.requireNonNull(role, "role");
    runId =
        GraphAttemptDomains.runIdentifier(runId, "runId");
    taskId =
        GraphAttemptDomains.runIdentifier(taskId, "taskId");
    taskHash = GraphAttemptDomains.hash(taskHash, "taskHash");
    executionProfileId =
        GraphAttemptDomains.safeName(
            executionProfileId, "executionProfileId");
    executionProfileFingerprint =
        GraphAttemptDomains.hash(
            executionProfileFingerprint,
            "executionProfileFingerprint");
    workerRegistryVersion =
        GraphAttemptDomains.safeName(
            workerRegistryVersion, "workerRegistryVersion");
    workerProfileId =
        GraphAttemptDomains.safeName(
            workerProfileId, "workerProfileId");
    workerProfileFingerprint =
        GraphAttemptDomains.hash(
            workerProfileFingerprint,
            "workerProfileFingerprint");
  }

  /**
   * Fixed-width database identity for the complete selector.
   *
   * <p>The readable selector columns remain persisted and independently
   * verified; this digest keeps PostgreSQL composite FK indexes bounded
   * even when reviewed profile names contain multi-byte Unicode.
   */
  public String selectorHash() {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("executionProfileFingerprint",
        executionProfileFingerprint);
    material.put("executionProfileId", executionProfileId);
    material.put("role", role);
    material.put("runId", runId);
    material.put("taskHash", taskHash);
    material.put("taskId", taskId);
    material.put("workerProfileFingerprint",
        workerProfileFingerprint);
    material.put("workerProfileId", workerProfileId);
    material.put("workerRegistryVersion",
        workerRegistryVersion);
    return CanonicalIntegrity.hash(SELECTION_DOMAIN, material);
  }
}
