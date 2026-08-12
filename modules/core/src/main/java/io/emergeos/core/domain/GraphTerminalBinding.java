package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.WorkerResultEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Safe hash-only bridge between one terminal AgentRun and its graph event.
 */
public record GraphTerminalBinding(
    GraphRunRole role,
    String runId,
    String taskId,
    RunStatus status,
    String bundleHash,
    String traceRootHash,
    String effectRef,
    String effectHash,
    Instant completedAt,
    String terminalHash) {

  private static final String DOMAIN =
      "emergeos.graph-terminal-binding.v1";

  public GraphTerminalBinding {
    role = Objects.requireNonNull(role, "role");
    runId = GraphAttemptDomains.runIdentifier(runId, "runId");
    taskId =
        GraphAttemptDomains.runIdentifier(taskId, "taskId");
    status = Objects.requireNonNull(status, "status");
    bundleHash =
        GraphAttemptDomains.hash(bundleHash, "bundleHash");
    traceRootHash =
        GraphAttemptDomains.hash(
            traceRootHash, "traceRootHash");
    if ((effectRef == null) != (effectHash == null)) {
      throw new IllegalArgumentException(
          "terminal effect ref and hash must be all-or-none");
    }
    if (effectRef != null) {
      effectRef =
          GraphAttemptDomains.safeReference(
              effectRef, "effectRef");
      effectHash =
          GraphAttemptDomains.hash(effectHash, "effectHash");
    }
    completedAt = Objects.requireNonNull(completedAt, "completedAt");
    terminalHash =
        GraphAttemptDomains.hash(
            terminalHash, "terminalHash");
    if (!computeHash(
            role,
            runId,
            taskId,
            status,
            bundleHash,
            traceRootHash,
            effectRef,
            effectHash,
            completedAt)
        .equals(terminalHash)) {
      throw new IllegalArgumentException(
          "terminal binding hash is inconsistent");
    }
    if (status == RunStatus.SUCCEEDED && effectRef == null) {
      throw new IllegalArgumentException(
          "a successful graph Run requires its bounded effect");
    }
    if (status != RunStatus.SUCCEEDED && effectRef != null) {
      throw new IllegalArgumentException(
          "a non-success graph Run cannot bind an effect");
    }
  }

  public static GraphTerminalBinding child(
      AgentRun terminal, WorkerResultEnvelope workerResult) {
    requireTerminal(terminal, GraphRunRole.CHILD);
    if (terminal.result().status() == RunStatus.SUCCEEDED) {
      Objects.requireNonNull(workerResult, "workerResult");
      requireExactBinding(
          terminal.bundle().resourceBindings(),
          ResourceRole.WORKER_RESULT,
          workerResult.workerResultRef(),
          workerResult.integrityHash());
      return create(
          GraphRunRole.CHILD,
          terminal,
          workerResult.workerResultRef(),
          workerResult.integrityHash());
    }
    if (workerResult != null) {
      throw new IllegalArgumentException(
          "a non-success child cannot bind a Worker Result");
    }
    return create(GraphRunRole.CHILD, terminal, null, null);
  }

  public static GraphTerminalBinding parent(
      AgentRun terminal, ArtifactLineage artifact) {
    requireTerminal(terminal, GraphRunRole.PARENT);
    if (terminal.result().status() == RunStatus.SUCCEEDED) {
      Objects.requireNonNull(artifact, "artifact");
      String ref =
          "artifact-version://"
              + artifact.artifactId()
              + "/"
              + artifact.current().version();
      requireExactBinding(
          terminal.bundle().resourceBindings(),
          ResourceRole.ARTIFACT,
          ref,
          artifact.current().contentHash());
      return create(
          GraphRunRole.PARENT,
          terminal,
          ref,
          artifact.current().contentHash());
    }
    if (artifact != null) {
      throw new IllegalArgumentException(
          "a non-success parent cannot bind an Artifact");
    }
    return create(GraphRunRole.PARENT, terminal, null, null);
  }

  private static GraphTerminalBinding create(
      GraphRunRole role,
      AgentRun terminal,
      String effectRef,
      String effectHash) {
    String hash =
        computeHash(
            role,
            terminal.runId(),
            terminal.task().id(),
            terminal.result().status(),
            terminal.bundle().integrityHash(),
            terminal.trace().rootHash(),
            effectRef,
            effectHash,
            terminal.completedAt());
    return new GraphTerminalBinding(
        role,
        terminal.runId(),
        terminal.task().id(),
        terminal.result().status(),
        terminal.bundle().integrityHash(),
        terminal.trace().rootHash(),
        effectRef,
        effectHash,
        terminal.completedAt(),
        hash);
  }

  private static void requireTerminal(
      AgentRun terminal, GraphRunRole role) {
    Objects.requireNonNull(terminal, "terminal");
    if (!terminal.lifecycle().terminal()
        || terminal.completedAt() == null) {
      throw new IllegalArgumentException(
          role + " terminal binding requires terminal AgentRun truth");
    }
  }

  private static void requireExactBinding(
      List<ResourceBinding> bindings,
      ResourceRole role,
      String ref,
      String hash) {
    List<ResourceBinding> matching =
        bindings.stream()
            .filter(binding -> binding.role() == role)
            .toList();
    if (matching.size() != 1
        || !ref.equals(matching.getFirst().ref())
        || !hash.equals(matching.getFirst().contentHash())) {
      throw new IllegalArgumentException(
          "terminal effect does not match its immutable Run binding");
    }
  }

  private static String computeHash(
      GraphRunRole role,
      String runId,
      String taskId,
      RunStatus status,
      String bundleHash,
      String traceRootHash,
      String effectRef,
      String effectHash,
      Instant completedAt) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("bundleHash", bundleHash);
    material.put("completedAt", completedAt);
    material.put("effectHash", effectHash);
    material.put("effectRef", effectRef);
    material.put("role", role);
    material.put("runId", runId);
    material.put("status", status);
    material.put("taskId", taskId);
    material.put("traceRootHash", traceRootHash);
    return CanonicalIntegrity.hash(DOMAIN, material);
  }
}
