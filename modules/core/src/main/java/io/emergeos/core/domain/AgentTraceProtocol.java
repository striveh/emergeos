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

  public static final String READ_ONLY_WORKER_CAPABILITY =
      ObservedExecutionLimits.READ_ONLY_WORKER_CAPABILITY;

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

  /**
   * Returns the one proposal content hash bound by a structured-final
   * Trace event, or {@code null} when no structured final exists.
   */
  public static String structuredFinalProposalContentHash(
      AgentTraceEnvelope trace) {
    Objects.requireNonNull(trace, "trace");
    List<AgentTraceEntry> finals =
        trace.events().stream()
            .filter(
                event ->
                    event.type() == TraceEventType.STRUCTURED_FINAL)
            .toList();
    if (finals.isEmpty()) {
      return null;
    }
    if (finals.size() != 1
        || !finals
            .getFirst()
            .reference()
            .matches("proposal://sha256:[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          "Trace does not bind one structured-final proposal hash");
    }
    return finals
        .getFirst()
        .reference()
        .substring("proposal://sha256:".length());
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
    boolean handoffRejected = false;
    String preRequestHandoffRejectionStatus = null;
    String postRequestHandoffRejectionStatus = null;
    int handoffRequests = 0;
    int successfulHandoffs = 0;
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
          } else if (event.type() == TraceEventType.HANDOFF_REQUEST) {
            if (!hasReadOnlyWorkerCapability(task) || handoffRequests >= 1) {
              throw new IllegalArgumentException(
                  "Trace requests a Worker outside Task authority");
            }
            handoffRequests++;
            pendingReference = event.reference();
            phase = Phase.EXPECT_HANDOFF_COMPLETION;
          } else if (event.type() == TraceEventType.HANDOFF_REJECTED
              && event.reference() == null) {
            handoffRejected = true;
            preRequestHandoffRejectionStatus = event.status();
            phase = Phase.TERMINAL;
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
        case EXPECT_HANDOFF_COMPLETION -> {
          if (!Objects.equals(pendingReference, event.reference())) {
            throw new IllegalArgumentException(
                "Handoff completion must match its pending request");
          }
          if (event.type() == TraceEventType.HANDOFF_RESULT) {
            successfulHandoffs++;
            phase = Phase.EXPECT_MODEL;
          } else if (event.type() == TraceEventType.HANDOFF_REJECTED) {
            handoffRejected = true;
            postRequestHandoffRejectionStatus = event.status();
            phase = Phase.TERMINAL;
          } else {
            throw new IllegalArgumentException(
                "A Handoff request requires one matching result or rejection");
          }
        }
        case TERMINAL ->
            throw new IllegalArgumentException("Trace continues after a terminal event");
      }
    }

    if (phase == Phase.EXPECT_TOOL_COMPLETION
        || phase == Phase.EXPECT_HANDOFF_COMPLETION) {
      throw new IllegalArgumentException("Trace ends with an unmatched request");
    }
    if (status == RunStatus.SUCCEEDED) {
      if (!structuredFinal || phase != Phase.TERMINAL) {
        throw new IllegalArgumentException(
            "A successful Trace must terminate with STRUCTURED_FINAL");
      }
    } else {
      if (structuredFinal && !allowRejectedStructuredFinal) {
        throw new IllegalArgumentException(
            "A non-success Trace cannot contain STRUCTURED_FINAL");
      }
      if (phase != Phase.TERMINAL
          && !permitsNonEventBoundaryTermination(
              task,
              events,
              phase,
              status,
              failureReason,
              modelSteps,
              latencyMs)) {
        throw new IllegalArgumentException(
            "A non-success Trace requires an explicit terminal event");
      }
    }
    if (!obtainedEvidence.equals(declaredEvidenceRefs)) {
      boolean handoffEvidence =
          successfulHandoffs == 1
              && declaredEvidenceRefs.stream().allMatch(task.inputRefs()::contains)
              && declaredEvidenceRefs.containsAll(obtainedEvidence);
      if (!handoffEvidence) {
        throw new IllegalArgumentException(
            "Declared Evidence refs must originate in Tool or verified Handoff results");
      }
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
    boolean handoffSpecificFailure =
        failureReason != null && failureReason.startsWith("HANDOFF_");
    boolean declaresRejectedHandoff =
        handoffSpecificFailure
            || (handoffRejected
                && ("UNSAFE_HANDOFF_OUTCOME".equals(failureReason)
                    || "CANCELLED".equals(failureReason)));
    if ((declaresRejectedHandoff && !handoffRejected)
        || (handoffRejected
            && (!declaresRejectedHandoff
                || status == RunStatus.SUCCEEDED))) {
      throw new IllegalArgumentException(
          "Handoff failure and Trace rejection must be bound");
    }
    if (preRequestHandoffRejectionStatus != null
        && !matchesPreRequestHandoffRejection(
            status,
            failureReason,
            preRequestHandoffRejectionStatus)) {
      throw new IllegalArgumentException(
          "Pre-request Handoff rejection status and failure must match");
    }
    if (postRequestHandoffRejectionStatus != null
        && !matchesPostRequestHandoffRejection(
            task,
            status,
            failureReason,
            postRequestHandoffRejectionStatus,
            latencyMs)) {
      throw new IllegalArgumentException(
          "Post-request Handoff rejection status and failure must match");
    }
    if (status == RunStatus.SUCCEEDED
        && hasReadOnlyWorkerCapability(task)
        && successfulHandoffs != 1) {
      throw new IllegalArgumentException(
          "A Worker-enabled successful parent requires one accepted Handoff");
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
      case HANDOFF_REQUEST -> {
        if (event.toolName() != null
            || !"REQUESTED".equals(event.status())
            || !hasReadOnlyWorkerCapability(task)
            || event.reference() == null
            || !event.reference().matches(
                "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
          throw new IllegalArgumentException("HANDOFF_REQUEST metadata is unsafe");
        }
      }
      case HANDOFF_RESULT -> {
        if (event.toolName() != null
            || !"SUCCEEDED".equals(event.status())
            || !hasReadOnlyWorkerCapability(task)
            || event.reference() == null
            || !event.reference().matches(
                "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
          throw new IllegalArgumentException("HANDOFF_RESULT metadata is unsafe");
        }
      }
      case HANDOFF_REJECTED -> {
        boolean allowedStatus =
            List.of(
                    "BLOCKED",
                    "FAILED",
                    "CANCELLED_UNOBSERVED",
                    "DEADLINE_EXHAUSTED",
                    "DEADLINE_EXCEEDED_UNOBSERVED",
                    "DEADLINE_EXCEEDED_AFTER_CHILD",
                    "LIMIT_EXHAUSTED",
                    "CHILD_FAILED",
                    "CHILD_BLOCKED",
                    "CHILD_NEEDS_INPUT",
                    "CHILD_CANCELLED",
                    "MALFORMED_RESULT")
                .contains(event.status());
        boolean safeReference =
            event.reference() == null
                || event.reference().matches(
                    "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}");
        if (event.toolName() != null
            || !allowedStatus
            || !safeReference
            || (event.reference() != null
                && !hasReadOnlyWorkerCapability(task))) {
          throw new IllegalArgumentException("HANDOFF_REJECTED metadata is unsafe");
        }
      }
      case STRUCTURED_FINAL -> {
        boolean proposalBound =
            "PROPOSE_ARTICLE_DRAFT".equals(task.kind())
                || hasReadOnlyWorkerCapability(task);
        if (event.toolName() != null
            || !"PROPOSED".equals(event.status())
            || (proposalBound
                ? event.reference() == null
                    || !event.reference().matches(
                        "proposal://sha256:[a-f0-9]{64}")
                : !taskRef.equals(event.reference()))) {
          throw new IllegalArgumentException("STRUCTURED_FINAL metadata is unsafe");
        }
      }
      case ARTIFACT_COMMITTED ->
          throw new IllegalArgumentException(
              "Kernel Trace cannot contain ARTIFACT_COMMITTED");
    }
  }

  private static boolean matchesPreRequestHandoffRejection(
      RunStatus status,
      String failureReason,
      String traceStatus) {
    if (failureReason == null) {
      return false;
    }
    return switch (failureReason) {
      case "HANDOFF_NOT_ALLOWED",
          "HANDOFF_LIMIT_EXHAUSTED",
          "HANDOFF_CONTEXT_POLICY_DRIFT",
          "HANDOFF_AUTHORITY_ESCALATION" ->
          status == RunStatus.BLOCKED && "BLOCKED".equals(traceStatus);
      case "HANDOFF_PREPARATION_FAILED" ->
          status == RunStatus.FAILED && "FAILED".equals(traceStatus);
      case "UNSAFE_HANDOFF_OUTCOME" ->
          status == RunStatus.FAILED
              && "MALFORMED_RESULT".equals(traceStatus);
      default -> false;
    };
  }

  private static boolean matchesPostRequestHandoffRejection(
      TaskEnvelope task,
      RunStatus status,
      String failureReason,
      String traceStatus,
      long latencyMs) {
    if (failureReason == null) {
      return false;
    }
    return switch (traceStatus) {
      case "CANCELLED_UNOBSERVED" ->
          status == RunStatus.CANCELLED
              && "CANCELLED".equals(failureReason);
      case "DEADLINE_EXHAUSTED" ->
          status == RunStatus.FAILED
              && "HANDOFF_DEADLINE_EXHAUSTED".equals(failureReason);
      case "DEADLINE_EXCEEDED_UNOBSERVED",
          "DEADLINE_EXCEEDED_AFTER_CHILD" ->
          status == RunStatus.FAILED
              && ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE.equals(
                  failureReason)
              && ObservedExecutionLimits.permitsFailureAttribution(
                  task, status, latencyMs, failureReason);
      case "FAILED" ->
          status == RunStatus.FAILED
              && "HANDOFF_DISPATCH_FAILED".equals(failureReason);
      case "MALFORMED_RESULT" ->
          status == RunStatus.FAILED
              && "UNSAFE_HANDOFF_OUTCOME".equals(failureReason);
      case "LIMIT_EXHAUSTED" ->
          status == RunStatus.BLOCKED
              && "HANDOFF_BUDGET_EXHAUSTED".equals(failureReason);
      case "CHILD_FAILED" ->
          status == RunStatus.FAILED
              && "HANDOFF_CHILD_FAILED".equals(failureReason);
      case "CHILD_BLOCKED" ->
          status == RunStatus.BLOCKED
              && "HANDOFF_CHILD_BLOCKED".equals(failureReason);
      case "CHILD_NEEDS_INPUT" ->
          status == RunStatus.NEEDS_INPUT
              && "HANDOFF_CHILD_NEEDS_INPUT".equals(failureReason);
      case "CHILD_CANCELLED" ->
          status == RunStatus.CANCELLED
              && "HANDOFF_CHILD_CANCELLED".equals(failureReason);
      default -> false;
    };
  }

  public static boolean requiresTrustedChildObservation(
      String handoffRejectionStatus) {
    return "DEADLINE_EXCEEDED_AFTER_CHILD".equals(handoffRejectionStatus)
        || "LIMIT_EXHAUSTED".equals(handoffRejectionStatus)
        || (handoffRejectionStatus != null
            && handoffRejectionStatus.startsWith("CHILD_"));
  }

  private static boolean permitsNonEventBoundaryTermination(
      TaskEnvelope task,
      List<EventView> events,
      Phase phase,
      RunStatus status,
      String failureReason,
      int modelSteps,
      long latencyMs) {
    if (events.isEmpty()) {
      // Pre-execution checks and Model-session creation can fail before a Trace event exists.
      return true;
    }
    boolean cooperativeBoundary =
        (status == RunStatus.CANCELLED && "CANCELLED".equals(failureReason))
            || (status == RunStatus.FAILED
                && "DEADLINE_EXHAUSTED".equals(failureReason)
                && latencyMs >= task.deadlineMs());
    return switch (phase) {
      case EXPECT_MODEL ->
          cooperativeBoundary
              || (status == RunStatus.FAILED
                  && "MODEL_STEP_LIMIT_EXHAUSTED".equals(failureReason)
                  && modelSteps == task.maxModelSteps());
      case AFTER_MODEL ->
          cooperativeBoundary
              || (status == RunStatus.FAILED
                  && "UNSUPPORTED_MODEL_DECISION".equals(failureReason));
      default -> false;
    };
  }

  private enum Phase {
    EXPECT_MODEL,
    AFTER_MODEL,
    EXPECT_TOOL_COMPLETION,
    EXPECT_HANDOFF_COMPLETION,
    TERMINAL
  }

  public static boolean hasReadOnlyWorkerCapability(TaskEnvelope task) {
    return task.capabilityRefs().equals(
        List.of(READ_ONLY_WORKER_CAPABILITY));
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
