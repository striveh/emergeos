package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.ContractValueDomains;
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

  /** Deterministic clock injection for frozen evaluation and deadline tests. */
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
  public String executionProfileId() {
    return model.executionProfileId();
  }

  @Override
  public String executionProfileFingerprint() {
    return model.executionProfileFingerprint();
  }

  @Override
  public AgentRunOutcome run(TaskEnvelope task, CancellationSignal cancellation) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(cancellation, "cancellation");
    long startedNanos = nanoTime.getAsLong();
    RunState state = new RunState();
    if (!tools.version().equals(task.toolRegistryVersion())) {
      return outcome(
          task,
          RunStatus.BLOCKED,
          null,
          state,
          "TOOL_REGISTRY_MISMATCH",
          startedNanos);
    }
    if (task.maxModelSteps() > modelStepCeiling
        || task.maxToolCalls() > toolCallCeiling) {
      return outcome(
          task,
          RunStatus.BLOCKED,
          null,
          state,
          "HARNESS_LIMIT_MISMATCH",
          startedNanos);
    }

    AgentModel.Session session;
    try {
      session = Objects.requireNonNull(model.open(task), "model session");
    } catch (AgentModelFailure failure) {
      return outcome(
          task,
          failure.code().status(),
          null,
          state,
          failure.code().failureReason(),
          startedNanos);
    } catch (RuntimeException sessionFailure) {
      return outcome(
          task,
          RunStatus.FAILED,
          null,
          state,
          "MODEL_SESSION_FAILED",
          startedNanos);
    }

    try {
      return runSession(task, cancellation, session, state, startedNanos);
    } finally {
      try {
        session.close();
      } catch (RuntimeException ignoredCloseFailure) {
        // Session cleanup cannot rewrite an already determined product outcome.
      }
    }
  }

  private AgentRunOutcome runSession(
      TaskEnvelope task,
      CancellationSignal cancellation,
      AgentModel.Session session,
      RunState state,
      long startedNanos) {
    int toolCalls = 0;
    for (int modelStep = 0; modelStep < task.maxModelSteps(); modelStep++) {
      if (cancellation.isCancelled()) {
        return outcome(
            task, RunStatus.CANCELLED, null, state, "CANCELLED", startedNanos);
      }
      long remainingDeadlineMs = remainingDeadlineMs(task, startedNanos);
      if (remainingDeadlineMs <= 0) {
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            "DEADLINE_EXHAUSTED",
            startedNanos);
      }

      AgentModel.ModelStep step;
      try {
        step =
            Objects.requireNonNull(
                session.next(
                    new AgentModel.Turn(task, state.toolResults),
                    new AgentModel.ModelCallContext(
                        remainingDeadlineMs,
                        remainingBudget(task, state.costUsd),
                        cancellation)),
                "model step");
      } catch (AgentModelFailure failure) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            failure.code().status(),
            null,
            state,
            failure.code().failureReason(),
            startedNanos);
      } catch (RuntimeException modelFailure) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            "MODEL_STEP_FAILED",
            startedNanos);
      }

      try {
        state.costUsd = state.costUsd.add(step.usage().costUsd());
        state.tokenCount = Math.addExact(state.tokenCount, step.usage().tokenCount());
        ContractValueDomains.requireUsd(state.costUsd, "aggregate model costUsd");
        ContractValueDomains.requireSafeCount(
            state.tokenCount, "aggregate model tokenCount");
      } catch (RuntimeException invalidUsage) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            "MODEL_USAGE_INVALID",
            startedNanos);
      }

      String priorResolvedModel = state.resolvedModel;
      state.resolvedModel = step.resolvedModel();
      if (state.costUsd.compareTo(task.budgetUsd()) > 0) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.BLOCKED,
            null,
            state,
            "MODEL_BUDGET_EXHAUSTED",
            startedNanos);
      }
      if (priorResolvedModel != null && !priorResolvedModel.equals(step.resolvedModel())) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            "MODEL_IDENTITY_DRIFT",
            startedNanos);
      }

      AgentModel.Decision decision = step.decision();
      if (decision instanceof AgentModel.Failed failed) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.MODEL_STEP,
                null,
                "FAILED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            failed.failureReason(),
            startedNanos);
      }
      state.trace.add(
          event(
              state.trace,
              AgentTraceEventType.MODEL_STEP,
              null,
              "COMPLETED",
              "task://" + task.id()));
      if (cancellation.isCancelled()) {
        return outcome(
            task, RunStatus.CANCELLED, null, state, "CANCELLED", startedNanos);
      }
      if (deadlineExceeded(task, startedNanos)) {
        return outcome(
            task,
            RunStatus.FAILED,
            null,
            state,
            "DEADLINE_EXHAUSTED",
            startedNanos);
      }
      if (decision instanceof AgentModel.ToolCall call) {
        if (!tools.isRegistered(call.toolName())
            || !task.requiredTools().contains(call.toolName())) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  "untrusted",
                  "BLOCKED",
                  null));
          return outcome(
              task,
              RunStatus.BLOCKED,
              null,
              state,
              "TOOL_NOT_ALLOWED",
              startedNanos);
        }
        AgentToolRegistry.ToolPreparation preparation =
            tools.prepare(task, call);
        if (cancellation.isCancelled()) {
          return outcome(
              task, RunStatus.CANCELLED, null, state, "CANCELLED", startedNanos);
        }
        if (deadlineExceeded(task, startedNanos)) {
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "DEADLINE_EXHAUSTED",
              startedNanos);
        }
        if (!preparation.prepared()) {
          boolean notAllowed =
              "TOOL_NOT_ALLOWED".equals(preparation.failureReason());
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  notAllowed ? "untrusted" : call.toolName(),
                  notAllowed ? "BLOCKED" : "FAILED",
                  null));
          return outcome(
              task,
              notAllowed ? RunStatus.BLOCKED : RunStatus.FAILED,
              null,
              state,
              preparation.failureReason(),
              startedNanos);
        }
        AgentToolRegistry.PreparedToolExecution prepared =
            preparation.execution();
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.TOOL_REQUEST,
                prepared.toolName(),
                "REQUESTED",
                prepared.reference()));
        if (toolCalls >= task.maxToolCalls()) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  prepared.toolName(),
                  "LIMIT_EXHAUSTED",
                  prepared.reference()));
          return outcome(
              task,
              RunStatus.BLOCKED,
              null,
              state,
              "TOOL_CALL_LIMIT_EXHAUSTED",
              startedNanos);
        }
        AgentModel.ToolResult result;
        try {
          result = prepared.execute();
        } catch (RuntimeException toolFailure) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  prepared.toolName(),
                  "FAILED",
                  prepared.reference()));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "TOOL_EXECUTION_FAILED",
              startedNanos);
        }
        if (result == null
            || !prepared.toolName().equals(result.toolName())
            || !prepared.reference().equals(result.reference())) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  prepared.toolName(),
                  "MALFORMED_RESULT",
                  prepared.reference()));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "MALFORMED_TOOL_RESULT",
              startedNanos);
        }
        toolCalls++;
        state.toolResults.add(result);
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.TOOL_RESULT,
                prepared.toolName(),
                "SUCCEEDED",
                result.reference()));
        continue;
      }
      if (decision instanceof AgentModel.FinalDraft finalDraft) {
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.STRUCTURED_FINAL,
                null,
                "PROPOSED",
                "task://" + task.id()));
        return outcome(
            task,
            RunStatus.SUCCEEDED,
            new AgentDraftProposal(finalDraft.content(), finalDraft.evidenceRefs()),
            state,
            null,
            startedNanos);
      }
      return outcome(
          task,
          RunStatus.FAILED,
          null,
          state,
          "UNSUPPORTED_MODEL_DECISION",
          startedNanos);
    }
    return outcome(
        task,
        RunStatus.FAILED,
        null,
        state,
        "MODEL_STEP_LIMIT_EXHAUSTED",
        startedNanos);
  }

  private AgentRunOutcome outcome(
      TaskEnvelope task,
      RunStatus status,
      AgentDraftProposal proposal,
      RunState state,
      String failureReason,
      long startedNanos) {
    return new AgentRunOutcome(
        status,
        proposal,
        state.trace.stream()
            .filter(event -> event.type() == AgentTraceEventType.TOOL_RESULT)
            .map(AgentTraceEvent::reference)
            .distinct()
            .toList(),
        state.trace,
        state.resolvedModel,
        state.costUsd,
        state.tokenCount,
        elapsedMillis(startedNanos),
        failureReason);
  }

  private BigDecimal remainingBudget(TaskEnvelope task, BigDecimal spent) {
    BigDecimal remaining = task.budgetUsd().subtract(spent);
    return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
  }

  private long remainingDeadlineMs(TaskEnvelope task, long startedNanos) {
    return task.deadlineMs() - elapsedMillis(startedNanos);
  }

  private boolean deadlineExceeded(TaskEnvelope task, long startedNanos) {
    return remainingDeadlineMs(task, startedNanos) <= 0;
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

  private static final class RunState {

    private final List<AgentTraceEvent> trace = new ArrayList<>();
    private final List<AgentModel.ToolResult> toolResults = new ArrayList<>();
    private String resolvedModel;
    private BigDecimal costUsd = BigDecimal.ZERO;
    private long tokenCount;
  }
}
