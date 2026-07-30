package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Framework-free, provider-neutral Agent loop.
 *
 * <p>Core remains the authority for persistent lifecycle, Trace verification and Artifact commit.
 */
public final class AgentLoopKernel implements AgentKernel {

  private final AgentModel model;
  private final AgentToolRegistry tools;
  private final int modelStepCeiling;
  private final int toolCallCeiling;
  private final LongSupplier nanoTime;

  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      int maxModelSteps,
      int maxToolCalls) {
    this(model, tools, maxModelSteps, maxToolCalls, System::nanoTime);
  }

  /**
   * Deterministic clock injection for frozen evaluation and deadline tests.
   */
  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      int maxModelSteps,
      int maxToolCalls,
      LongSupplier nanoTime) {
    this.model = Objects.requireNonNull(model, "model");
    this.tools = Objects.requireNonNull(tools, "tools");
    if (maxModelSteps < 1 || maxToolCalls < 1) {
      throw new IllegalArgumentException("model and tool limits must be positive");
    }
    this.modelStepCeiling = maxModelSteps;
    this.toolCallCeiling = maxToolCalls;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  @Override
  public AgentRunOutcome run(
      TaskEnvelope task,
      CancellationSignal cancellation) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(cancellation, "cancellation");
    long startedNanos = nanoTime.getAsLong();
    List<AgentTraceEvent> trace = new ArrayList<>();
    List<AgentModel.ToolResult> toolResults = new ArrayList<>();
    int toolCalls = 0;
    if (task.maxModelSteps() > modelStepCeiling
        || task.maxToolCalls() > toolCallCeiling) {
      return outcome(
          RunStatus.BLOCKED, null, trace, "HARNESS_LIMIT_MISMATCH", startedNanos);
    }

    for (int modelStep = 0; modelStep < task.maxModelSteps(); modelStep++) {
      if (cancellation.isCancelled()) {
        return outcome(
            RunStatus.CANCELLED, null, trace, "CANCELLED", startedNanos);
      }
      if (deadlineExceeded(task, startedNanos)) {
        return outcome(
            RunStatus.FAILED, null, trace, "DEADLINE_EXHAUSTED", startedNanos);
      }
      AgentModel.Decision decision;
      try {
        decision = model.decide(new AgentModel.Turn(task, toolResults));
      } catch (RuntimeException modelFailure) {
        trace.add(
            event(
                trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            RunStatus.FAILED, null, trace, "MODEL_STEP_FAILED", startedNanos);
      }
      trace.add(
          event(
              trace,
              AgentTraceEventType.MODEL_STEP,
              null,
              "COMPLETED",
              "task://" + task.id()));
      if (cancellation.isCancelled()) {
        return outcome(
            RunStatus.CANCELLED, null, trace, "CANCELLED", startedNanos);
      }
      if (deadlineExceeded(task, startedNanos)) {
        return outcome(
            RunStatus.FAILED, null, trace, "DEADLINE_EXHAUSTED", startedNanos);
      }
      if (decision instanceof AgentModel.ToolCall call) {
        if (!tools.isRegistered(call.toolName())
            || !task.requiredTools().contains(call.toolName())
            || !task.inputRefs().contains(call.reference())) {
          trace.add(
              event(
                  trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  "untrusted",
                  "BLOCKED",
                  null));
          return outcome(
              RunStatus.BLOCKED, null, trace, "TOOL_NOT_ALLOWED", startedNanos);
        }
        trace.add(
            event(
                trace,
                AgentTraceEventType.TOOL_REQUEST,
                call.toolName(),
                "REQUESTED",
                call.reference()));
        if (toolCalls >= task.maxToolCalls()) {
          trace.add(
              event(
                  trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  call.toolName(),
                  "LIMIT_EXHAUSTED",
                  call.reference()));
          return outcome(
              RunStatus.BLOCKED,
              null,
              trace,
              "TOOL_CALL_LIMIT_EXHAUSTED",
              startedNanos);
        }
        AgentToolRegistry.ToolExecution execution;
        try {
          execution = tools.execute(task, call);
        } catch (RuntimeException toolFailure) {
          trace.add(
              event(
                  trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  call.toolName(),
                  "FAILED",
                  call.reference()));
          return outcome(
              RunStatus.FAILED,
              null,
              trace,
              "TOOL_EXECUTION_FAILED",
              startedNanos);
        }
        if (!execution.allowed()) {
          trace.add(
              event(
                  trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  call.toolName(),
                  "BLOCKED",
                  call.reference()));
          return outcome(
              RunStatus.BLOCKED,
              null,
              trace,
              execution.failureReason(),
              startedNanos);
        }
        if (execution.result() == null
            || !call.toolName().equals(execution.result().toolName())
            || !call.reference().equals(execution.result().reference())) {
          trace.add(
              event(
                  trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  call.toolName(),
                  "MALFORMED_RESULT",
                  call.reference()));
          return outcome(
              RunStatus.FAILED,
              null,
              trace,
              "MALFORMED_TOOL_RESULT",
              startedNanos);
        }
        toolCalls++;
        toolResults.add(execution.result());
        trace.add(
            event(
                trace,
                AgentTraceEventType.TOOL_RESULT,
                call.toolName(),
                "SUCCEEDED",
                execution.result().reference()));
        continue;
      }
      if (decision instanceof AgentModel.FinalDraft finalDraft) {
        trace.add(
            event(
                trace,
                AgentTraceEventType.STRUCTURED_FINAL,
                null,
                "PROPOSED",
                "task://" + task.id()));
        return outcome(
            RunStatus.SUCCEEDED,
            new AgentDraftProposal(finalDraft.content(), finalDraft.evidenceRefs()),
            trace,
            null,
            startedNanos);
      }
      return outcome(
          RunStatus.FAILED,
          null,
          trace,
          "UNSUPPORTED_MODEL_DECISION",
          startedNanos);
    }
    return outcome(
        RunStatus.FAILED,
        null,
        trace,
        "MODEL_STEP_LIMIT_EXHAUSTED",
        startedNanos);
  }

  private AgentRunOutcome outcome(
      RunStatus status,
      AgentDraftProposal proposal,
      List<AgentTraceEvent> trace,
      String failureReason,
      long startedNanos) {
    return new AgentRunOutcome(
        status,
        proposal,
        trace.stream()
            .filter(event -> event.type() == AgentTraceEventType.TOOL_RESULT)
            .map(AgentTraceEvent::reference)
            .distinct()
            .toList(),
        trace,
        model.modelId(),
        BigDecimal.ZERO,
        0,
        elapsedMillis(startedNanos),
        failureReason);
  }

  private boolean deadlineExceeded(TaskEnvelope task, long startedNanos) {
    long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(task.deadlineMs());
    return elapsedNanos(startedNanos) >= deadlineNanos;
  }

  private long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(elapsedNanos(startedNanos));
  }

  private long elapsedNanos(long startedNanos) {
    return Math.max(0, nanoTime.getAsLong() - startedNanos);
  }

  private static AgentTraceEvent event(
      List<AgentTraceEvent> trace,
      AgentTraceEventType type,
      String toolName,
      String status,
      String reference) {
    return new AgentTraceEvent(trace.size() + 1, type, toolName, status, reference);
  }
}
