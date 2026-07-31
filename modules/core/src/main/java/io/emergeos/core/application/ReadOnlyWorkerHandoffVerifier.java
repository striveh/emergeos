package io.emergeos.core.application;

import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import java.util.List;
import java.util.Objects;

/**
 * Shared cross-Run verifier for one depth-1 read-only Worker Handoff.
 *
 * <p>JSON Schema can validate local shapes only. Write paths and verified reads call this class
 * after loading both complete Runs and the immutable Worker Result.
 */
public final class ReadOnlyWorkerHandoffVerifier {

  private ReadOnlyWorkerHandoffVerifier() {}

  public static void verifyPlannedChild(
      io.emergeos.contracts.TaskEnvelope parentTask,
      String expectedChildRunId,
      io.emergeos.contracts.TaskEnvelope expectedChildTask,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile) {
    verifyPlannedChild(
        parentTask,
        expectedChildRunId,
        expectedChildTask,
        child,
        workerResult,
        (ReadOnlyWorkerProfile) profile);
  }

  public static void verifyPlannedChild(
      io.emergeos.contracts.TaskEnvelope parentTask,
      String expectedChildRunId,
      io.emergeos.contracts.TaskEnvelope expectedChildTask,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerProfile profile) {
    Objects.requireNonNull(expectedChildRunId, "expectedChildRunId");
    Objects.requireNonNull(expectedChildTask, "expectedChildTask");
    if (!expectedChildRunId.equals(child.runId())
        || !expectedChildTask.equals(child.task())) {
      throw new IllegalArgumentException(
          "durable Worker read does not match the planned child identity");
    }
    verifyChild(parentTask, child, workerResult, profile);
  }

  public static void verifyChild(
      io.emergeos.contracts.TaskEnvelope parentTask,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile) {
    verifyChild(
        parentTask,
        child,
        workerResult,
        (ReadOnlyWorkerProfile) profile);
  }

  public static void verifyChild(
      io.emergeos.contracts.TaskEnvelope parentTask,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerProfile profile) {
    Objects.requireNonNull(parentTask, "parentTask");
    Objects.requireNonNull(child, "child");
    Objects.requireNonNull(profile, "profile");
    profile.requireChildBinding(parentTask, child.task());
    if (!child.lifecycle().terminal()) {
      throw new IllegalArgumentException("Handoff child must be terminal");
    }
    if (!child.principalId().equals(parentTask.principalRef())
        || !child.bundle().harnessVersion().equals(profile.harnessVersion())
        || !Objects.equals(
            child.bundle().experiment(), profile.experiment())
        || !child.bundle().componentVersions().equals(profile.componentVersions())
        || !child.result().artifactRefs().isEmpty()
        || !child.result().receiptRefs().isEmpty()
        || !child.bundle().handoffRefs().isEmpty()
        || !child.bundle().checkpointRefs().isEmpty()
        || child.bundle().verificationRef() != null
        || child.trace().events().stream()
            .anyMatch(
                event ->
                    event.type() == TraceEventType.ARTIFACT_COMMITTED
                        || event.type() == TraceEventType.HANDOFF_REQUEST
                        || event.type() == TraceEventType.HANDOFF_RESULT
                        || event.type() == TraceEventType.HANDOFF_REJECTED)) {
      throw new IllegalArgumentException(
          "read-only Worker terminal truth contains forbidden writes or delegation");
    }

    List<ResourceBinding> workerBindings =
        child.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.WORKER_RESULT)
            .toList();
    if (child.result().status() == RunStatus.SUCCEEDED) {
      Objects.requireNonNull(workerResult, "workerResult");
      List<ResourceBinding> evidenceBindings =
          child.bundle().resourceBindings().stream()
              .filter(binding -> binding.role() == ResourceRole.EVIDENCE)
              .toList();
      String proposalRef =
          child.trace().events().stream()
              .filter(event -> event.type() == TraceEventType.STRUCTURED_FINAL)
              .map(event -> event.reference())
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "successful Worker lacks structured final"));
      if (!workerResult.childRunId().equals(child.runId())
          || !workerResult.childTaskId().equals(child.task().id())
          || !workerResult.outputSchema().equals(child.task().outputSchema())
          || !workerResult.evidenceRefs().equals(child.result().evidenceRefs())
          || !workerResult.evidenceRefs().equals(
              evidenceBindings.stream().map(ResourceBinding::ref).toList())
          || workerBindings.size() != 1
          || !workerBindings
              .getFirst()
              .ref()
              .equals(workerResult.workerResultRef())
          || !workerBindings
              .getFirst()
              .contentHash()
              .equals(workerResult.integrityHash())
          || !proposalRef.equals(
              "proposal://sha256:" + workerResult.contentHash())) {
        throw new IllegalArgumentException(
            "Worker Result is not hash-bound to child terminal truth");
      }
    } else if (workerResult != null || !workerBindings.isEmpty()) {
      throw new IllegalArgumentException(
          "non-success Worker cannot bind an accepted Worker Result");
    }
  }

  public static void verifyPair(
      AgentRun parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerExecutionProfile profile) {
    verifyPair(
        parent,
        child,
        workerResult,
        (ReadOnlyWorkerProfile) profile);
  }

  public static void verifyPair(
      AgentRun parent,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ReadOnlyWorkerProfile profile) {
    Objects.requireNonNull(parent, "parent");
    verifyChild(parent.task(), child, workerResult, profile);
    if (!parent.lifecycle().terminal()
        || !parent.principalId().equals(child.principalId())
        || parent.task().parentId() != null
        || !parent.task().delegationChain().isEmpty()
        || !parent
            .task()
            .capabilityRefs()
            .equals(List.of(AgentExecutionProfile.READ_ONLY_WORKER_CAPABILITY))
        || !parent
            .bundle()
            .harnessVersion()
            .equals(profile.parentExecutionProfile().harnessVersion())
        || !parent
            .bundle()
            .componentVersions()
            .equals(profile.expectedParentComponentVersions())
        || !Objects.equals(
            parent.bundle().experiment(), profile.experiment())) {
      throw new IllegalArgumentException(
          "parent Run is not bound to the exact Worker profile");
    }
    String childRef = "agent-run://" + child.runId();
    List<ResourceBinding> handoffBindings =
        parent.bundle().resourceBindings().stream()
            .filter(binding -> binding.role() == ResourceRole.HANDOFF)
            .toList();
    if (!parent.bundle().handoffRefs().equals(List.of(childRef))
        || handoffBindings.size() != 1
        || !handoffBindings.getFirst().ref().equals(childRef)
        || !handoffBindings
            .getFirst()
            .contentHash()
            .equals(child.bundle().integrityHash())) {
      throw new IllegalArgumentException(
          "parent Handoff does not bind the exact child Bundle");
    }

    List<io.emergeos.contracts.AgentTraceEntry> handoffRequests =
        parent.trace().events().stream()
            .filter(event -> event.type() == TraceEventType.HANDOFF_REQUEST)
            .toList();
    List<io.emergeos.contracts.AgentTraceEntry> boundCompletions =
        parent.trace().events().stream()
            .filter(
                event ->
                    event.reference() != null
                        && (event.type() == TraceEventType.HANDOFF_RESULT
                            || event.type() == TraceEventType.HANDOFF_REJECTED))
            .toList();
    if (handoffRequests.size() != 1
        || !childRef.equals(handoffRequests.getFirst().reference())
        || boundCompletions.size() != 1
        || !childRef.equals(boundCompletions.getFirst().reference())
        || !completionMatchesChild(
            boundCompletions.getFirst(), parent, child.result().status())
        || !aggregateUsageMatches(
            parent.result().costUsd(),
            parent.result().tokenCount(),
            child.result().costUsd(),
            child.result().tokenCount(),
            profile)) {
      throw new IllegalArgumentException(
          "parent Trace, child status or aggregate usage is not bound");
    }
    if (boundCompletions.getFirst().type() == TraceEventType.HANDOFF_RESULT
        && !bindings(parent, ResourceRole.EVIDENCE)
            .equals(bindings(child, ResourceRole.EVIDENCE))) {
      throw new IllegalArgumentException(
          "accepted parent and child Evidence bindings must match exactly");
    }

    if (parent.result().status() == RunStatus.SUCCEEDED) {
      Objects.requireNonNull(workerResult, "workerResult");
      List<ResourceBinding> artifacts =
          parent.bundle().resourceBindings().stream()
              .filter(binding -> binding.role() == ResourceRole.ARTIFACT)
              .toList();
      String parentProposalRef =
          parent.trace().events().stream()
              .filter(event -> event.type() == TraceEventType.STRUCTURED_FINAL)
              .map(event -> event.reference())
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "successful parent lacks structured final"));
      if (child.result().status() != RunStatus.SUCCEEDED
          || artifacts.size() != 1
          || !artifacts
              .getFirst()
              .contentHash()
              .equals(workerResult.contentHash())
          || !parentProposalRef.equals(
              "proposal://sha256:" + workerResult.contentHash())
          || !parent
              .result()
              .evidenceRefs()
              .equals(workerResult.evidenceRefs())) {
        throw new IllegalArgumentException(
            "parent Artifact is not the verified Worker proposal");
      }
    } else if (!parent.result().artifactRefs().isEmpty()) {
      throw new IllegalArgumentException(
          "non-success parent cannot commit an Artifact");
    }
  }

  private static boolean aggregateUsageMatches(
      java.math.BigDecimal parentCostUsd,
      long parentTokenCount,
      java.math.BigDecimal childCostUsd,
      long childTokenCount,
      ReadOnlyWorkerProfile profile) {
    if (profile.requiresExactParentUsageAggregation()) {
      return parentCostUsd.compareTo(childCostUsd) == 0
          && parentTokenCount == childTokenCount;
    }
    return parentCostUsd.compareTo(childCostUsd) >= 0
        && parentTokenCount >= childTokenCount;
  }

  private static boolean completionMatchesChild(
      io.emergeos.contracts.AgentTraceEntry completion,
      AgentRun parent,
      RunStatus childStatus) {
    if (completion.type() == TraceEventType.HANDOFF_RESULT) {
      return childStatus == RunStatus.SUCCEEDED;
    }
    return switch (completion.status()) {
      case "CHILD_FAILED" ->
          childStatus == RunStatus.FAILED
              && parent.result().status() == RunStatus.FAILED
              && "HANDOFF_CHILD_FAILED".equals(
                  parent.result().failureReason());
      case "CHILD_BLOCKED" ->
          childStatus == RunStatus.BLOCKED
              && parent.result().status() == RunStatus.BLOCKED
              && "HANDOFF_CHILD_BLOCKED".equals(
                  parent.result().failureReason());
      case "CHILD_NEEDS_INPUT" ->
          childStatus == RunStatus.NEEDS_INPUT
              && parent.result().status() == RunStatus.NEEDS_INPUT
              && "HANDOFF_CHILD_NEEDS_INPUT".equals(
                  parent.result().failureReason());
      case "CHILD_CANCELLED" ->
          childStatus == RunStatus.CANCELLED
              && parent.result().status() == RunStatus.CANCELLED
              && "HANDOFF_CHILD_CANCELLED".equals(
                  parent.result().failureReason());
      case "DEADLINE_EXCEEDED_AFTER_CHILD" ->
          parent.result().status() == RunStatus.FAILED
              && io.emergeos.contracts.ObservedExecutionLimits
                  .POST_HANDOFF_DEADLINE_FAILURE
                  .equals(parent.result().failureReason())
              && io.emergeos.contracts.ObservedExecutionLimits
                  .postDispatchDeadlineExceeded(
                      parent.task(), parent.result().latencyMs());
      case "LIMIT_EXHAUSTED" ->
          parent.result().status() == RunStatus.BLOCKED
              && "HANDOFF_BUDGET_EXHAUSTED".equals(
                  parent.result().failureReason())
              && parent
                      .result()
                      .costUsd()
                      .compareTo(parent.task().budgetUsd())
                  > 0;
      default -> false;
    };
  }

  private static List<ResourceBinding> bindings(
      AgentRun run,
      ResourceRole role) {
    return run.bundle().resourceBindings().stream()
        .filter(binding -> binding.role() == role)
        .toList();
  }
}
