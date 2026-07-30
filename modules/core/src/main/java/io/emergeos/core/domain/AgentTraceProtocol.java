package io.emergeos.core.domain;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic state-machine validation for adapter outcomes and durable Trace truth.
 *
 * <p>Field allowlists alone do not prove that a tool result was requested, that task limits were
 * respected, or that nothing happened after a terminal event. This protocol is the shared Core
 * boundary used both before persistence and when constructing a terminal {@link AgentRun}.
 */
public final class AgentTraceProtocol {

  private AgentTraceProtocol() {}

  public static void verifyKernelOutcome(TaskEnvelope task, AgentRunOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome");
    List<EventView> events = new ArrayList<>();
    for (AgentTraceEvent event : outcome.trace()) {
      if (event == null || event.type() == AgentTraceEventType.ARTIFACT_COMMITTED) {
        throw new IllegalArgumentException("Kernel Trace contains an invalid event");
      }
      events.add(
          new EventView(
              event.sequence(),
              TraceEventType.valueOf(event.type().name()),
              event.toolName(),
              event.status(),
              event.reference()));
    }
    verifyKernelEvents(
        task,
        outcome.status(),
        outcome.failureReason(),
        events,
        outcome.obtainedEvidenceRefs(),
        false,
        outcome.latencyMs());
  }

  public static void verifyTerminal(
      TaskEnvelope task, ResultEnvelope result, AgentTraceEnvelope trace) {
    List<AgentTraceEntry> entries = trace.events();
    int firstCommit = entries.size();
    for (int index = 0; index < entries.size(); index++) {
      if (entries.get(index).type() == TraceEventType.ARTIFACT_COMMITTED) {
        firstCommit = index;
        break;
      }
    }

    List<EventView> kernelEvents = new ArrayList<>();
    for (int index = 0; index < firstCommit; index++) {
      AgentTraceEntry event = entries.get(index);
      kernelEvents.add(
          new EventView(
              event.sequence(),
              event.type(),
              event.toolName(),
              event.status(),
              event.reference()));
    }
    List<AgentTraceEntry> commits = entries.subList(firstCommit, entries.size());
    if (commits.stream().anyMatch(event -> event.type() != TraceEventType.ARTIFACT_COMMITTED)) {
      throw new IllegalArgumentException("ARTIFACT_COMMITTED events must be a trailing suffix");
    }
    if (result.status() != RunStatus.SUCCEEDED && !commits.isEmpty()) {
      throw new IllegalArgumentException(
          "A non-success AgentRun cannot contain ARTIFACT_COMMITTED");
    }
    List<String> committedRefs = commits.stream().map(AgentTraceEntry::reference).toList();
    if (!committedRefs.equals(result.artifactRefs())) {
      throw new IllegalArgumentException(
          "Artifact commit Trace must match the exact Result artifact refs");
    }
    if (result.status() == RunStatus.SUCCEEDED
        && "CREATE_ARTICLE_DRAFT".equals(task.kind())
        && committedRefs.size() != 1) {
      throw new IllegalArgumentException(
          "A successful CREATE_ARTICLE_DRAFT Run requires one committed Artifact");
    }
    verifyKernelEvents(
        task,
        result.status(),
        result.failureReason(),
        kernelEvents,
        result.evidenceRefs(),
        true,
        result.latencyMs());
  }

  private static void verifyKernelEvents(
      TaskEnvelope task,
      RunStatus status,
      String failureReason,
      List<EventView> events,
      List<String> declaredEvidenceRefs,
      boolean allowRejectedStructuredFinal,
      long latencyMs) {
    Phase phase = Phase.EXPECT_MODEL;
    int modelSteps = 0;
    int executedToolCalls = 0;
    String pendingTool = null;
    String pendingReference = null;
    boolean pendingOverLimit = false;
    boolean structuredFinal = false;
    boolean toolArgumentsRejected = false;
    boolean postDispatchDeadlineRejected = false;
    List<String> obtainedEvidence = new ArrayList<>();
    String taskRef = "task://" + task.id();

    for (int index = 0; index < events.size(); index++) {
      EventView event = events.get(index);
      if (event.sequence() != index + 1 || phase == Phase.TERMINAL) {
        throw new IllegalArgumentException(
            "Trace sequence is invalid or continues after a terminal event");
      }
      requireTaskScopedMetadata(event, task, taskRef);

      switch (phase) {
        case EXPECT_MODEL -> {
          if (event.type() != TraceEventType.MODEL_STEP) {
            throw new IllegalArgumentException("Trace expected a MODEL_STEP");
          }
          modelSteps++;
          if (modelSteps > task.maxModelSteps()) {
            throw new IllegalArgumentException("Trace exceeds maxModelSteps");
          }
          phase = "FAILED".equals(event.status()) ? Phase.TERMINAL : Phase.AFTER_MODEL;
        }
        case AFTER_MODEL -> {
          if (event.type() == TraceEventType.STRUCTURED_FINAL) {
            if (status != RunStatus.SUCCEEDED && !allowRejectedStructuredFinal) {
              throw new IllegalArgumentException(
                  "Only a successful Run may contain STRUCTURED_FINAL");
            }
            structuredFinal = true;
            phase = Phase.TERMINAL;
          } else if (event.type() == TraceEventType.TOOL_REQUEST) {
            pendingTool = event.toolName();
            pendingReference = event.reference();
            pendingOverLimit = executedToolCalls >= task.maxToolCalls();
            phase = Phase.EXPECT_TOOL_COMPLETION;
          } else if (event.type() == TraceEventType.TOOL_REJECTED
              && "untrusted".equals(event.toolName())
              && "BLOCKED".equals(event.status())
              && event.reference() == null) {
            phase = Phase.TERMINAL;
          } else if (event.type() == TraceEventType.TOOL_REJECTED
              && task.requiredTools().contains(event.toolName())
              && "FAILED".equals(event.status())
              && event.reference() == null) {
            toolArgumentsRejected = true;
            phase = Phase.TERMINAL;
          } else {
            throw new IllegalArgumentException(
                "Trace expected a tool request, safe rejection, or structured final");
          }
        }
        case EXPECT_TOOL_COMPLETION -> {
          if (!Objects.equals(pendingTool, event.toolName())
              || !Objects.equals(pendingReference, event.reference())) {
            throw new IllegalArgumentException(
                "Tool completion must match its pending request");
          }
          if (event.type() == TraceEventType.TOOL_RESULT) {
            if (pendingOverLimit) {
              throw new IllegalArgumentException("Trace executes a tool beyond maxToolCalls");
            }
            executedToolCalls++;
            if (!obtainedEvidence.contains(event.reference())) {
              obtainedEvidence.add(event.reference());
            }
            phase = Phase.EXPECT_MODEL;
          } else if (event.type() == TraceEventType.TOOL_REJECTED) {
            if (pendingOverLimit != "LIMIT_EXHAUSTED".equals(event.status())) {
              throw new IllegalArgumentException(
                  "Only the first over-limit request may use LIMIT_EXHAUSTED");
            }
            postDispatchDeadlineRejected =
                "DEADLINE_EXCEEDED".equals(event.status());
            phase = Phase.TERMINAL;
          } else {
            throw new IllegalArgumentException(
                "A tool request requires one matching result or rejection");
          }
        }
        case TERMINAL ->
            throw new IllegalArgumentException("Trace continues after a terminal event");
      }
    }

    if (phase == Phase.EXPECT_TOOL_COMPLETION) {
      throw new IllegalArgumentException("Trace ends with an unmatched TOOL_REQUEST");
    }
    if (status == RunStatus.SUCCEEDED) {
      if (!structuredFinal || phase != Phase.TERMINAL) {
        throw new IllegalArgumentException(
            "A successful Trace must terminate with STRUCTURED_FINAL");
      }
    } else if (structuredFinal && !allowRejectedStructuredFinal) {
      throw new IllegalArgumentException(
          "A non-success Trace cannot contain STRUCTURED_FINAL");
    }
    if (!obtainedEvidence.equals(declaredEvidenceRefs)) {
      throw new IllegalArgumentException(
          "Declared Evidence refs must equal distinct successful TOOL_RESULT refs");
    }
    boolean declaresToolArgumentsFailure =
        "TOOL_ARGUMENTS_INVALID".equals(failureReason)
            || "TOOL_ARGUMENT_VALIDATION_FAILED".equals(failureReason);
    if (declaresToolArgumentsFailure != toolArgumentsRejected
        || (toolArgumentsRejected && status != RunStatus.FAILED)) {
      throw new IllegalArgumentException(
          "Tool-argument failure and Trace rejection must be bound");
    }
    boolean declaresPostDispatchDeadlineFailure =
        ObservedExecutionLimits.POST_DISPATCH_DEADLINE_FAILURE.equals(
            failureReason);
    if (declaresPostDispatchDeadlineFailure != postDispatchDeadlineRejected
        || (postDispatchDeadlineRejected
            && !ObservedExecutionLimits.permitsFailureAttribution(
                task, status, latencyMs, failureReason))) {
      throw new IllegalArgumentException(
          "Post-dispatch Tool deadline and Trace rejection must be bound");
    }
  }

  private static void requireTaskScopedMetadata(
      EventView event, TaskEnvelope task, String taskRef) {
    switch (event.type()) {
      case MODEL_STEP -> {
        if (event.toolName() != null
            || !("COMPLETED".equals(event.status()) || "FAILED".equals(event.status()))
            || !taskRef.equals(event.reference())) {
          throw new IllegalArgumentException("MODEL_STEP metadata is unsafe");
        }
      }
      case TOOL_REQUEST -> {
        if (!"REQUESTED".equals(event.status())
            || !task.requiredTools().contains(event.toolName())
            || !task.inputRefs().contains(event.reference())) {
          throw new IllegalArgumentException("TOOL_REQUEST metadata is unsafe");
        }
      }
      case TOOL_RESULT -> {
        if (!"SUCCEEDED".equals(event.status())
            || !task.requiredTools().contains(event.toolName())
            || !task.inputRefs().contains(event.reference())) {
          throw new IllegalArgumentException("TOOL_RESULT metadata is unsafe");
        }
      }
      case TOOL_REJECTED -> {
        boolean allowedStatus =
            "BLOCKED".equals(event.status())
                || "LIMIT_EXHAUSTED".equals(event.status())
                || "FAILED".equals(event.status())
                || "MALFORMED_RESULT".equals(event.status())
                || "DEADLINE_EXCEEDED".equals(event.status());
        boolean safeUntrusted =
            "untrusted".equals(event.toolName()) && event.reference() == null;
        boolean safePreDispatchRejection =
            task.requiredTools().contains(event.toolName())
                && "FAILED".equals(event.status())
                && event.reference() == null;
        boolean declaredTool =
            task.requiredTools().contains(event.toolName())
                && event.reference() != null
                && task.inputRefs().contains(event.reference());
        if (!allowedStatus
            || !(safeUntrusted || safePreDispatchRejection || declaredTool)) {
          throw new IllegalArgumentException("TOOL_REJECTED metadata is unsafe");
        }
      }
      case STRUCTURED_FINAL -> {
        if (event.toolName() != null
            || !"PROPOSED".equals(event.status())
            || !taskRef.equals(event.reference())) {
          throw new IllegalArgumentException("STRUCTURED_FINAL metadata is unsafe");
        }
      }
      case ARTIFACT_COMMITTED ->
          throw new IllegalArgumentException(
              "Kernel Trace cannot contain ARTIFACT_COMMITTED");
    }
  }

  private enum Phase {
    EXPECT_MODEL,
    AFTER_MODEL,
    EXPECT_TOOL_COMPLETION,
    TERMINAL
  }

  private record EventView(
      int sequence,
      TraceEventType type,
      String toolName,
      String status,
      String reference) {

    private EventView {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(status, "status");
    }
  }
}
