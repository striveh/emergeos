package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentHandoffObservation;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.domain.AgentTraceProtocol;
import io.emergeos.core.domain.AgentWorkerExecution;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentWorkerRuntime;
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
  private final AgentWorkerRuntime workers;
  private final int modelStepCeiling;
  private final int toolCallCeiling;
  private final LongSupplier nanoTime;

  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      int maxModelSteps,
      int maxToolCalls) {
    this(
        model,
        tools,
        AgentWorkerRuntime.disabled(),
        maxModelSteps,
        maxToolCalls,
        System::nanoTime);
  }

  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      AgentWorkerRuntime workers,
      int maxModelSteps,
      int maxToolCalls) {
    this(
        model,
        tools,
        workers,
        maxModelSteps,
        maxToolCalls,
        System::nanoTime);
  }

  /** Deterministic clock injection for frozen evaluation and deadline tests. */
  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      int maxModelSteps,
      int maxToolCalls,
      LongSupplier nanoTime) {
    this(
        model,
        tools,
        AgentWorkerRuntime.disabled(),
        maxModelSteps,
        maxToolCalls,
        nanoTime);
  }

  /** Deterministic clock injection for frozen Worker, evaluation and deadline tests. */
  public AgentLoopKernel(
      AgentModel model,
      AgentToolRegistry tools,
      AgentWorkerRuntime workers,
      int maxModelSteps,
      int maxToolCalls,
      LongSupplier nanoTime) {
    this.model = Objects.requireNonNull(model, "model");
    this.tools = Objects.requireNonNull(tools, "tools");
    this.workers = Objects.requireNonNull(workers, "workers");
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
  public String workerRegistryVersion() {
    return workers == AgentWorkerRuntime.disabled()
        ? null
        : workers.registryVersion();
  }

  @Override
  public String workerProfileFingerprint() {
    return workers == AgentWorkerRuntime.disabled()
        ? null
        : workers.profileFingerprint();
  }

  @Override
  public AgentRunOutcome run(
      AgentRunContext context, CancellationSignal cancellation) {
    Objects.requireNonNull(context, "context");
    TaskEnvelope task = context.task();
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
      return runSession(
          context, task, cancellation, session, state, startedNanos);
    } finally {
      try {
        session.close();
      } catch (RuntimeException ignoredCloseFailure) {
        // Session cleanup cannot rewrite an already determined product outcome.
      }
    }
  }

  private AgentRunOutcome runSession(
      AgentRunContext context,
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
                    new AgentModel.Turn(
                        task, state.toolResults, state.workerResults),
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
      if (deadlineExhausted(task, startedNanos)) {
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
        if (deadlineExhausted(task, startedNanos)) {
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
          if (deadlineExceeded(task, startedNanos)) {
            state.trace.add(
                event(
                    state.trace,
                    AgentTraceEventType.TOOL_REJECTED,
                    prepared.toolName(),
                    "DEADLINE_EXCEEDED",
                    prepared.reference()));
            return outcome(
                task,
                RunStatus.FAILED,
                null,
                state,
                ObservedExecutionLimits.POST_DISPATCH_DEADLINE_FAILURE,
                startedNanos);
          }
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
        if (deadlineExceeded(task, startedNanos)) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.TOOL_REJECTED,
                  prepared.toolName(),
                  "DEADLINE_EXCEEDED",
                  prepared.reference()));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              ObservedExecutionLimits.POST_DISPATCH_DEADLINE_FAILURE,
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
        if (!state.obtainedEvidenceRefs.contains(result.reference())) {
          state.obtainedEvidenceRefs.add(result.reference());
        }
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.TOOL_RESULT,
                prepared.toolName(),
                "SUCCEEDED",
                result.reference()));
        // The post-result cooperative boundary exists even when this was the
        // final permitted model step. Do not let loop exhaustion overwrite a
        // cancellation or exact-deadline observation.
        if (cancellation.isCancelled()) {
          return outcome(
              task,
              RunStatus.CANCELLED,
              null,
              state,
              "CANCELLED",
              startedNanos);
        }
        if (deadlineExhausted(task, startedNanos)) {
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "DEADLINE_EXHAUSTED",
              startedNanos);
        }
        continue;
      }
      if (decision instanceof AgentModel.WorkerCall call) {
        if (!AgentTraceProtocol.hasReadOnlyWorkerCapability(task)) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "BLOCKED",
                  null));
          return outcome(
              task,
              RunStatus.BLOCKED,
              null,
              state,
              "HANDOFF_NOT_ALLOWED",
              startedNanos);
        }
        if (!state.handoffs.isEmpty()) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "BLOCKED",
                  null));
          return outcome(
              task,
              RunStatus.BLOCKED,
              null,
              state,
              "HANDOFF_LIMIT_EXHAUSTED",
              startedNanos);
        }

        AgentWorkerRuntime.Preparation preparation;
        try {
          preparation =
              Objects.requireNonNull(
                  workers.prepare(
                      context,
                      new WorkerHandoffRequest(
                          call.workerName(), call.intent(), call.inputRefs()),
                      new AgentWorkerRuntime.ExecutionWindow(
                          remainingDeadlineMs(task, startedNanos),
                          remainingBudget(task, state.costUsd),
                          cancellation)),
                  "Worker preparation");
        } catch (RuntimeException unsafePreparation) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "FAILED",
                  null));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "HANDOFF_PREPARATION_FAILED",
              startedNanos);
        }
        if (!preparation.accepted()) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "BLOCKED",
                  null));
          return outcome(
              task,
              preparation.rejectionStatus(),
              null,
              state,
              preparation.failureReason(),
              startedNanos);
        }

        AgentWorkerRuntime.PreparedHandoff prepared = preparation.prepared();
        String preparedWorkerName;
        String childRunRef;
        try {
          preparedWorkerName = prepared.workerName();
          childRunRef = prepared.childRunRef();
        } catch (RuntimeException unsafePreparedIdentity) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "MALFORMED_RESULT",
                  null));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "UNSAFE_HANDOFF_OUTCOME",
              startedNanos);
        }
        if (!call.workerName().equals(preparedWorkerName)
            || childRunRef == null
            || !childRunRef.matches(
                "agent-run://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "MALFORMED_RESULT",
                  null));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "UNSAFE_HANDOFF_OUTCOME",
              startedNanos);
        }
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.HANDOFF_REQUEST,
                null,
                "REQUESTED",
                childRunRef));
        if (cancellation.isCancelled()) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "CANCELLED_UNOBSERVED",
                  childRunRef));
          return outcome(
              task, RunStatus.CANCELLED, null, state, "CANCELLED", startedNanos);
        }
        if (deadlineExhausted(task, startedNanos)) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "DEADLINE_EXHAUSTED",
                  childRunRef));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "HANDOFF_DEADLINE_EXHAUSTED",
              startedNanos);
        }

        AgentWorkerExecution child;
        try {
          child = Objects.requireNonNull(prepared.execute(), "Worker execution");
        } catch (RuntimeException dispatchFailure) {
          if (deadlineExceeded(task, startedNanos)) {
            state.trace.add(
                event(
                    state.trace,
                    AgentTraceEventType.HANDOFF_REJECTED,
                    null,
                    "DEADLINE_EXCEEDED_UNOBSERVED",
                    childRunRef));
            return outcome(
                task,
                RunStatus.FAILED,
                null,
                state,
                ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
                startedNanos);
          }
          if (cancellation.isCancelled()) {
            state.trace.add(
                event(
                    state.trace,
                    AgentTraceEventType.HANDOFF_REJECTED,
                    null,
                    "CANCELLED_UNOBSERVED",
                    childRunRef));
            return outcome(
                task,
                RunStatus.CANCELLED,
                null,
                state,
                "CANCELLED",
                startedNanos);
          }
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "FAILED",
                  childRunRef));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "HANDOFF_DISPATCH_FAILED",
              startedNanos);
        }
        if (!call.workerName().equals(child.workerName())
            || !childRunRef.equals(child.childRunRef())) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "MALFORMED_RESULT",
                  childRunRef));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "UNSAFE_HANDOFF_OUTCOME",
              startedNanos);
        }
        try {
          state.costUsd = state.costUsd.add(child.costUsd());
          state.tokenCount = Math.addExact(state.tokenCount, child.tokenCount());
          ContractValueDomains.requireUsd(
              state.costUsd, "aggregate subtree costUsd");
          ContractValueDomains.requireSafeCount(
              state.tokenCount, "aggregate subtree tokenCount");
        } catch (RuntimeException invalidChildUsage) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "MALFORMED_RESULT",
                  childRunRef));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "UNSAFE_HANDOFF_OUTCOME",
              startedNanos);
        }

        AgentHandoffObservation observation = child.observation();
        state.handoffs.add(observation);
        if (state.costUsd.compareTo(task.budgetUsd()) > 0) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "LIMIT_EXHAUSTED",
                  childRunRef));
          return outcome(
              task,
              RunStatus.BLOCKED,
              null,
              state,
              "HANDOFF_BUDGET_EXHAUSTED",
              startedNanos);
        }
        if (deadlineExceeded(task, startedNanos)) {
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  "DEADLINE_EXCEEDED_AFTER_CHILD",
                  childRunRef));
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              ObservedExecutionLimits.POST_HANDOFF_DEADLINE_FAILURE,
              startedNanos);
        }
        if (child.status() != RunStatus.SUCCEEDED) {
          String rejectionStatus = childRejectionStatus(child.status());
          state.trace.add(
              event(
                  state.trace,
                  AgentTraceEventType.HANDOFF_REJECTED,
                  null,
                  rejectionStatus,
                  childRunRef));
          return outcome(
              task,
              child.status(),
              null,
              state,
              childFailureReason(child.status()),
              startedNanos);
        }

        var result = child.workerResult();
        state.workerResults.add(
            new AgentModel.WorkerResult(
                child.workerName(),
                result.workerResultRef(),
                result.content(),
                result.contentHash(),
                result.evidenceRefs()));
        for (String evidenceRef : result.evidenceRefs()) {
          if (!state.obtainedEvidenceRefs.contains(evidenceRef)) {
            state.obtainedEvidenceRefs.add(evidenceRef);
          }
        }
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.HANDOFF_RESULT,
                null,
                "SUCCEEDED",
                childRunRef));
        if (cancellation.isCancelled()) {
          return outcome(
              task, RunStatus.CANCELLED, null, state, "CANCELLED", startedNanos);
        }
        if (deadlineExhausted(task, startedNanos)) {
          return outcome(
              task,
              RunStatus.FAILED,
              null,
              state,
              "DEADLINE_EXHAUSTED",
              startedNanos);
        }
        continue;
      }
      if (decision instanceof AgentModel.FinalDraft finalDraft) {
        String finalRef =
            ("PROPOSE_ARTICLE_DRAFT".equals(task.kind())
                    || AgentTraceProtocol.hasReadOnlyWorkerCapability(task))
                ? "proposal://sha256:"
                    + IntegrityHashes.utf8ContentHash(finalDraft.content())
                : "task://" + task.id();
        state.trace.add(
            event(
                state.trace,
                AgentTraceEventType.STRUCTURED_FINAL,
                null,
                "PROPOSED",
                finalRef));
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
        state.obtainedEvidenceRefs,
        state.trace,
        state.resolvedModel,
        state.costUsd,
        state.tokenCount,
        elapsedMillis(startedNanos),
        failureReason,
        state.handoffs);
  }

  private BigDecimal remainingBudget(TaskEnvelope task, BigDecimal spent) {
    BigDecimal remaining = task.budgetUsd().subtract(spent);
    return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
  }

  private long remainingDeadlineMs(TaskEnvelope task, long startedNanos) {
    return task.deadlineMs() - elapsedMillis(startedNanos);
  }

  private boolean deadlineExceeded(TaskEnvelope task, long startedNanos) {
    return ObservedExecutionLimits.postDispatchDeadlineExceeded(
        task, elapsedMillis(startedNanos));
  }

  private boolean deadlineExhausted(TaskEnvelope task, long startedNanos) {
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
    private final List<AgentModel.WorkerResult> workerResults = new ArrayList<>();
    private final List<String> obtainedEvidenceRefs = new ArrayList<>();
    private final List<AgentHandoffObservation> handoffs = new ArrayList<>();
    private String resolvedModel;
    private BigDecimal costUsd = BigDecimal.ZERO;
    private long tokenCount;
  }

  private static String childRejectionStatus(RunStatus status) {
    return switch (status) {
      case FAILED -> "CHILD_FAILED";
      case BLOCKED -> "CHILD_BLOCKED";
      case NEEDS_INPUT -> "CHILD_NEEDS_INPUT";
      case CANCELLED -> "CHILD_CANCELLED";
      case SUCCEEDED -> throw new IllegalArgumentException("successful child is not rejected");
    };
  }

  private static String childFailureReason(RunStatus status) {
    return switch (status) {
      case FAILED -> "HANDOFF_CHILD_FAILED";
      case BLOCKED -> "HANDOFF_CHILD_BLOCKED";
      case NEEDS_INPUT -> "HANDOFF_CHILD_NEEDS_INPUT";
      case CANCELLED -> "HANDOFF_CHILD_CANCELLED";
      case SUCCEEDED -> throw new IllegalArgumentException("successful child has no failure");
    };
  }
}
