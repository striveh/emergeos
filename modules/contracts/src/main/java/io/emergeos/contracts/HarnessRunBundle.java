package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HarnessRunBundle(
    String schemaVersion,
    String runId,
    String taskId,
    String experimentArm,
    int repetition,
    String modelResolved,
    String harnessVersion,
    Map<String, String> componentVersions,
    String environmentSnapshotRef,
    String toolRegistryVersion,
    TaskEnvelope task,
    String workingSelfRef,
    List<String> traceRefs,
    List<String> handoffRefs,
    List<String> checkpointRefs,
    List<String> artifactRefs,
    List<String> receiptRefs,
    String verificationRef,
    String failureAttribution,
    RunStatus outcome,
    BigDecimal costUsd,
    long tokenCount,
    long latencyMs,
    String integrityHash) {

  public HarnessRunBundle {
    requireText(schemaVersion, "schemaVersion");
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("HarnessRunBundle supports schemaVersion 1.0");
    }
    requireText(runId, "runId");
    requireText(taskId, "taskId");
    requireText(experimentArm, "experimentArm");
    requireText(modelResolved, "modelResolved");
    requireText(harnessVersion, "harnessVersion");
    requireText(environmentSnapshotRef, "environmentSnapshotRef");
    requireText(toolRegistryVersion, "toolRegistryVersion");
    requireText(workingSelfRef, "workingSelfRef");
    requireText(verificationRef, "verificationRef");
    requireText(integrityHash, "integrityHash");
    if (!integrityHash.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("integrityHash must be a lowercase SHA-256 hex digest");
    }
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(outcome, "outcome");
    componentVersions = Map.copyOf(Objects.requireNonNull(componentVersions, "componentVersions"));
    traceRefs = copy(traceRefs, "traceRefs");
    handoffRefs = copy(handoffRefs, "handoffRefs");
    checkpointRefs = copy(checkpointRefs, "checkpointRefs");
    artifactRefs = copy(artifactRefs, "artifactRefs");
    receiptRefs = copy(receiptRefs, "receiptRefs");
    if (repetition < 1 || tokenCount < 0 || latencyMs < 0) {
      throw new IllegalArgumentException("repetition must be positive; counts must not be negative");
    }
    if (Objects.requireNonNull(costUsd, "costUsd").signum() < 0) {
      throw new IllegalArgumentException("costUsd must not be negative");
    }
  }

  private static <T> List<T> copy(List<T> value, String name) {
    return List.copyOf(Objects.requireNonNull(value, name));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
