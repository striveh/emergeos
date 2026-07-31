package io.emergeos.adapters.agentloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.domain.AgentWorkerExecution;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AgentLoopKernelWorkerHandoffTest {

  private static final String TASK_ID = "worker-kernel-parent-task";
  private static final String CAPTURE_REF = "capture://worker-kernel-capture";
  private static final String WORKER = "article-draft.read-v1";

  @Test
  void workerBoundConductorCannotInvokeCaptureReadDirectly() {
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            scriptedModel(
                new AgentModel.ToolCall(
                    CaptureReadTool.NAME,
                    AgentModel.ToolArguments.fromJson(
                        "{\"reference\":\"" + CAPTURE_REF + "\"}"))),
            new AgentToolRegistry(
                List.of(countingCaptureRead(validations, executions))),
            2,
            1,
            new AtomicLong()::get);

    AgentRunOutcome outcome =
        run(
            kernel,
            task("direct-tool-escalation", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("TOOL_NOT_ALLOWED", outcome.failureReason());
    assertEquals(0, validations.get());
    assertEquals(0, executions.get());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals("untrusted", outcome.trace().getLast().toolName());
    assertEquals(null, outcome.trace().getLast().reference());
  }

  @Test
  void workerResultDoesNotGrantTheConductorDirectToolAuthority() {
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            scriptedModel(
                workerCall(),
                new AgentModel.ToolCall(
                    CaptureReadTool.NAME,
                    AgentModel.ToolArguments.fromJson(
                        "{\"reference\":\"" + CAPTURE_REF + "\"}"))),
            new AgentToolRegistry(
                List.of(countingCaptureRead(validations, executions))),
            runtime(
                () ->
                    succeededChild(
                        "child-before-tool-escalation", BigDecimal.ZERO)),
            2,
            1,
            new AtomicLong()::get);

    AgentRunOutcome outcome =
        run(
            kernel,
            task("post-worker-tool-escalation", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("TOOL_NOT_ALLOWED", outcome.failureReason());
    assertEquals(0, validations.get());
    assertEquals(0, executions.get());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REQUEST,
            AgentTraceEventType.HANDOFF_RESULT,
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals("untrusted", outcome.trace().getLast().toolName());
  }

  @Test
  void missingWorkerCapabilityBlocksBeforePreparationOrDispatch() {
    AtomicInteger preparations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AgentWorkerRuntime runtime =
        preparing(
            (parent, request, window) -> {
              preparations.incrementAndGet();
              return validPreparation(
                  "capability-missing-child",
                  () -> {
                    executions.incrementAndGet();
                    return succeededChild(
                        "capability-missing-child", BigDecimal.ZERO);
                  });
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            taskWithoutWorkerCapability(
                "capability-missing", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("HANDOFF_NOT_ALLOWED", outcome.failureReason());
    assertEquals(0, preparations.get());
    assertEquals(0, executions.get());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(null, outcome.trace().getLast().reference());
  }

  @Test
  void preparationFailureHasNoChildReferenceAndNeverDispatches() {
    AgentWorkerRuntime runtime =
        preparing(
            (parent, request, window) -> {
              throw new IllegalStateException("private preparation detail");
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("prepare-failure", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("HANDOFF_PREPARATION_FAILED", outcome.failureReason());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(null, outcome.trace().getLast().reference());
  }

  @Test
  void malformedPreparedIdentityFailsBeforePublishingAHandoffRequest() {
    AtomicInteger executions = new AtomicInteger();
    AgentWorkerRuntime runtime =
        preparing(
            (parent, request, window) ->
                AgentWorkerRuntime.Preparation.prepared(
                    new AgentWorkerRuntime.PreparedHandoff() {
                      @Override
                      public String workerName() {
                        return "another-worker";
                      }

                      @Override
                      public String childRunRef() {
                        return "agent-run://malformed-prepared-child";
                      }

                      @Override
                      public AgentWorkerExecution execute() {
                        executions.incrementAndGet();
                        return succeededChild(
                            "malformed-prepared-child", BigDecimal.ZERO);
                      }
                    }));
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("malformed-prepared", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("UNSAFE_HANDOFF_OUTCOME", outcome.failureReason());
    assertEquals(0, executions.get());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals("MALFORMED_RESULT", outcome.trace().getLast().status());
    assertEquals(null, outcome.trace().getLast().reference());
  }

  @Test
  void preparedIdentityGetterFailureIsAStablePreRequestRejection() {
    AgentWorkerRuntime runtime =
        preparing(
            (parent, request, window) ->
                AgentWorkerRuntime.Preparation.prepared(
                    new AgentWorkerRuntime.PreparedHandoff() {
                      @Override
                      public String workerName() {
                        throw new IllegalStateException(
                            "private registry getter detail");
                      }

                      @Override
                      public String childRunRef() {
                        throw new IllegalStateException(
                            "private child identity getter detail");
                      }

                      @Override
                      public AgentWorkerExecution execute() {
                        throw new AssertionError(
                            "malformed preparation must not dispatch");
                      }
                    }));
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("prepared-getter-failure", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("UNSAFE_HANDOFF_OUTCOME", outcome.failureReason());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals("MALFORMED_RESULT", outcome.trace().getLast().status());
    assertEquals(null, outcome.trace().getLast().reference());
  }

  @Test
  void cancellationAfterPreparationButBeforeDispatchIsExplicitlyUnobserved() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger executions = new AtomicInteger();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              executions.incrementAndGet();
              return succeededChild("child-never-dispatched", BigDecimal.ZERO);
            },
            () -> cancelled.set(true));
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("cancel-before-dispatch", BigDecimal.ZERO),
            cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(0, executions.get());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals("CANCELLED_UNOBSERVED", outcome.trace().getLast().status());
  }

  @Test
  void secondHandoffIsRejectedWithoutDispatchingASecondChild() {
    AtomicInteger executions = new AtomicInteger();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              executions.incrementAndGet();
              return succeededChild("child-one", BigDecimal.ZERO);
            });
    AgentLoopKernel kernel =
        kernel(
            scriptedModel(workerCall(), workerCall()),
            runtime,
            new AtomicLong());

    AgentRunOutcome outcome =
        run(kernel, task("second-handoff", BigDecimal.ZERO), CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("HANDOFF_LIMIT_EXHAUSTED", outcome.failureReason());
    assertEquals(1, executions.get());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REQUEST,
            AgentTraceEventType.HANDOFF_RESULT,
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
  }

  @Test
  void cancellationAfterACompletedChildPreservesItsObservation() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              cancelled.set(true);
              return succeededChild("child-cancelled-parent", BigDecimal.ZERO);
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(kernel,
            task("cancel-after-child", BigDecimal.ZERO),
            cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(
        AgentTraceEventType.HANDOFF_RESULT,
        outcome.trace().getLast().type());
    assertEquals("SUCCEEDED", outcome.trace().getLast().status());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
  }

  @Test
  void strictPostDispatchDeadlineBeatsCancellationAndChildFailureWithinBudget() {
    AtomicLong now = new AtomicLong();
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              now.set(TimeUnit.MILLISECONDS.toNanos(101));
              cancelled.set(true);
              return failedChild(
                  "child-late",
                  RunStatus.FAILED,
                  new BigDecimal("0.200000"));
            });
    AgentLoopKernel kernel = kernel(scriptedModel(workerCall()), runtime, now);

    AgentRunOutcome outcome =
        run(kernel,
            task("strict-late", new BigDecimal("0.300000")),
            cancelled::get);

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals(
        "HANDOFF_DEADLINE_EXCEEDED_AFTER_DISPATCH",
        outcome.failureReason());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(new BigDecimal("0.200000"), outcome.costUsd());
    assertTrue(outcome.latencyMs() > 100);
    assertEquals(
        "DEADLINE_EXCEEDED_AFTER_CHILD",
        outcome.trace().getLast().status());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REQUEST,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(List.of(), outcome.obtainedEvidenceRefs());
  }

  @Test
  void verifiedChildFailureBeatsCancellationWithinDeadlineAndBudget() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              cancelled.set(true);
              return failedChild(
                  "child-cancel-precedence",
                  RunStatus.FAILED,
                  BigDecimal.ZERO);
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(kernel,
            task("cancel-precedence", new BigDecimal("0.100000")),
            cancelled::get);

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("HANDOFF_CHILD_FAILED", outcome.failureReason());
    assertEquals(1, outcome.handoffs().size());
    assertEquals("CHILD_FAILED", outcome.trace().getLast().status());
  }

  @Test
  void aggregateSubtreeBudgetBeatsDeadlineCancellationAndChildFailure() {
    AtomicLong now = new AtomicLong();
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              now.set(TimeUnit.MILLISECONDS.toNanos(101));
              cancelled.set(true);
              return failedChild(
                  "child-budget-precedence",
                  RunStatus.FAILED,
                  new BigDecimal("0.200000"));
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, now);

    AgentRunOutcome outcome =
        run(kernel,
            task("budget-precedence", new BigDecimal("0.100000")),
            cancelled::get);

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("HANDOFF_BUDGET_EXHAUSTED", outcome.failureReason());
    assertEquals(1, outcome.handoffs().size());
    assertEquals("LIMIT_EXHAUSTED", outcome.trace().getLast().status());
    assertTrue(outcome.latencyMs() > 100);
  }

  @Test
  void overBudgetSuccessfulChildIsNotAcceptedAsModelEvidence() {
    AgentWorkerRuntime runtime =
        runtime(
            () ->
                succeededChild(
                    "child-success-over-budget",
                    new BigDecimal("0.200000")));
    AgentLoopKernel kernel =
        kernel(
            scriptedModel(
                workerCall(),
                new AgentModel.FinalDraft(
                    "must never be reached", List.of(CAPTURE_REF))),
            runtime,
            new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("success-over-budget", new BigDecimal("0.100000")),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("HANDOFF_BUDGET_EXHAUSTED", outcome.failureReason());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(List.of(), outcome.obtainedEvidenceRefs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.HANDOFF_REQUEST,
            AgentTraceEventType.HANDOFF_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
  }

  @Test
  void strictDeadlineStillWinsWhenTheDispatchedRuntimeThrows() {
    AtomicLong now = new AtomicLong();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              now.set(TimeUnit.MILLISECONDS.toNanos(101));
              throw new IllegalStateException("private runtime detail");
            });
    AgentLoopKernel kernel = kernel(scriptedModel(workerCall()), runtime, now);

    AgentRunOutcome outcome =
        run(kernel,
            task("late-dispatch-failure", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals(
        "HANDOFF_DEADLINE_EXCEEDED_AFTER_DISPATCH",
        outcome.failureReason());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        "DEADLINE_EXCEEDED_UNOBSERVED",
        outcome.trace().getLast().status());
  }

  @Test
  void cancellationBeatsARuntimeFailureWithinDeadlineWithoutClaimingAChildObservation() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              cancelled.set(true);
              throw new IllegalStateException("private runtime detail");
            });
    AgentLoopKernel kernel =
        kernel(scriptedModel(workerCall()), runtime, new AtomicLong());

    AgentRunOutcome outcome =
        run(
            kernel,
            task("cancel-runtime-failure", BigDecimal.ZERO),
            cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(List.of(), outcome.handoffs());
    assertEquals(
        "CANCELLED_UNOBSERVED", outcome.trace().getLast().status());
  }

  @Test
  void cancellationAtTheExactDeadlineWinsAndPreservesTheCompletedChild() {
    AtomicLong now = new AtomicLong();
    AtomicBoolean cancelled = new AtomicBoolean();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              now.set(TimeUnit.MILLISECONDS.toNanos(100));
              cancelled.set(true);
              return succeededChild(
                  "child-exact-deadline-cancelled", BigDecimal.ZERO);
            });
    AgentLoopKernel kernel = kernel(scriptedModel(workerCall()), runtime, now);

    AgentRunOutcome outcome =
        run(
            kernel,
            task("exact-deadline-cancelled", BigDecimal.ZERO),
            cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(100, outcome.latencyMs());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(
        AgentTraceEventType.HANDOFF_RESULT,
        outcome.trace().getLast().type());
    assertEquals("SUCCEEDED", outcome.trace().getLast().status());
  }

  @Test
  void exactDeadlineAfterACompletedChildUsesTheImplicitDeadlineBoundary() {
    AtomicLong now = new AtomicLong();
    AgentWorkerRuntime runtime =
        runtime(
            () -> {
              now.set(TimeUnit.MILLISECONDS.toNanos(100));
              return succeededChild(
                  "child-exact-deadline", BigDecimal.ZERO);
            });
    AgentLoopKernel kernel = kernel(scriptedModel(workerCall()), runtime, now);

    AgentRunOutcome outcome =
        run(
            kernel,
            task("exact-deadline", BigDecimal.ZERO),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("DEADLINE_EXHAUSTED", outcome.failureReason());
    assertEquals(100, outcome.latencyMs());
    assertEquals(1, outcome.handoffs().size());
    assertEquals(
        AgentTraceEventType.HANDOFF_RESULT,
        outcome.trace().getLast().type());
    assertEquals("SUCCEEDED", outcome.trace().getLast().status());
  }

  private static AgentLoopKernel kernel(
      AgentModel model,
      AgentWorkerRuntime runtime,
      AtomicLong now) {
    return new AgentLoopKernel(
        model,
        new AgentToolRegistry(List.of(noopCaptureRead())),
        runtime,
        2,
        1,
        now::get);
  }

  private static AgentModel scriptedModel(AgentModel.Decision... decisions) {
    return task ->
        new AgentModel.Session() {
          private int index;

          @Override
          public AgentModel.ModelStep next(
              AgentModel.Turn turn,
              AgentModel.ModelCallContext context) {
            return new AgentModel.ModelStep(
                decisions[index++],
                "scripted-conductor-v1",
                AgentModel.ModelUsage.zero());
          }
        };
  }

  private static AgentModel.WorkerCall workerCall() {
    return new AgentModel.WorkerCall(
        WORKER,
        "Create one read-only proposal",
        List.of(CAPTURE_REF));
  }

  private static AgentWorkerRuntime runtime(ChildExecution execution) {
    return runtime(execution, () -> {});
  }

  private static AgentWorkerRuntime runtime(
      ChildExecution execution, Runnable afterPrepare) {
    return new AgentWorkerRuntime() {
      private final AtomicInteger childIds = new AtomicInteger();

      @Override
      public String registryVersion() {
        return "agent-workers-v1";
      }

      @Override
      public String profileFingerprint() {
        return "a".repeat(64);
      }

      @Override
      public Preparation prepare(
          AgentRunContext parent,
          WorkerHandoffRequest request,
          ExecutionWindow window) {
        String childId = "prepared-child-" + childIds.incrementAndGet();
        afterPrepare.run();
        return validPreparation(
            childId,
            () -> {
              AgentWorkerExecution result = execution.execute();
              if (!result
                  .childRunRef()
                  .equals("agent-run://" + childId)) {
                return remapChild(result, childId);
              }
              return result;
            });
      }
    };
  }

  private static AgentWorkerRuntime preparing(
      PreparationFactory preparationFactory) {
    return new AgentWorkerRuntime() {
      @Override
      public String registryVersion() {
        return "agent-workers-v1";
      }

      @Override
      public String profileFingerprint() {
        return "a".repeat(64);
      }

      @Override
      public Preparation prepare(
          AgentRunContext parent,
          WorkerHandoffRequest request,
          ExecutionWindow window) {
        return preparationFactory.prepare(parent, request, window);
      }
    };
  }

  private static AgentWorkerRuntime.Preparation validPreparation(
      String childId, ChildExecution execution) {
    return AgentWorkerRuntime.Preparation.prepared(
        new AgentWorkerRuntime.PreparedHandoff() {
          @Override
          public String workerName() {
            return WORKER;
          }

          @Override
          public String childRunRef() {
            return "agent-run://" + childId;
          }

          @Override
          public AgentWorkerExecution execute() {
            return execution.execute();
          }
        });
  }

  private static AgentWorkerExecution remapChild(
      AgentWorkerExecution source,
      String childId) {
    if (source.status() == RunStatus.SUCCEEDED) {
      WorkerResultEnvelope result =
          WorkerResultEnvelope.create(
              childId,
              childId + "-task",
              "urn:emergeos:schema:internal:agent-draft-proposal:v1",
              source.workerResult().content(),
              source.workerResult().evidenceRefs());
      return new AgentWorkerExecution(
          WORKER,
          "agent-run://" + childId,
          source.childBundleHash(),
          RunStatus.SUCCEEDED,
          result,
          source.costUsd(),
          source.tokenCount(),
          null);
    }
    return new AgentWorkerExecution(
        WORKER,
        "agent-run://" + childId,
        source.childBundleHash(),
        source.status(),
        null,
        source.costUsd(),
        source.tokenCount(),
        source.failureReason());
  }

  private static AgentWorkerExecution succeededChild(
      String childId,
      BigDecimal cost) {
    WorkerResultEnvelope result =
        WorkerResultEnvelope.create(
            childId,
            childId + "-task",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            "A typed Worker proposal",
            List.of(CAPTURE_REF));
    return new AgentWorkerExecution(
        WORKER,
        "agent-run://" + childId,
        "b".repeat(64),
        RunStatus.SUCCEEDED,
        result,
        cost,
        7,
        null);
  }

  private static AgentWorkerExecution failedChild(
      String childId,
      RunStatus status,
      BigDecimal cost) {
    return new AgentWorkerExecution(
        WORKER,
        "agent-run://" + childId,
        "c".repeat(64),
        status,
        null,
        cost,
        11,
        "CHILD_INTERNAL_FAILURE");
  }

  private static AgentTool<ReferenceArguments> noopCaptureRead() {
    return countingCaptureRead(new AtomicInteger(), new AtomicInteger());
  }

  private static AgentTool<ReferenceArguments> countingCaptureRead(
      AtomicInteger validations, AtomicInteger executions) {
    return new AgentTool<>() {
      @Override
      public String name() {
        return CaptureReadTool.NAME;
      }

      @Override
      public String argumentSchemaId() {
        return CaptureReadTool.ARGUMENT_SCHEMA_ID;
      }

      @Override
      public Validation<ReferenceArguments> validate(
          TaskEnvelope task,
          AgentModel.ToolCall call) {
        validations.incrementAndGet();
        return Validation.valid(new ReferenceArguments(CAPTURE_REF));
      }

      @Override
      public AgentModel.ToolResult execute(
          TaskEnvelope task,
          ReferenceArguments arguments) {
        executions.incrementAndGet();
        throw new AssertionError("This Worker test must not execute a Tool");
      }
    };
  }

  private static TaskEnvelope task(
      String suffix,
      BigDecimal budget) {
    return task(
        suffix,
        budget,
        List.of(
            "capability://worker-handoff/article-draft-read-v1"));
  }

  private static TaskEnvelope taskWithoutWorkerCapability(
      String suffix, BigDecimal budget) {
    return task(suffix, budget, List.of());
  }

  private static TaskEnvelope task(
      String suffix,
      BigDecimal budget,
      List<String> capabilityRefs) {
    return new TaskEnvelope(
        "1.0",
        TASK_ID + "-" + suffix,
        null,
        "worker-kernel-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Verify Worker runtime precedence",
        List.of(CAPTURE_REF),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of(),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of(),
        false,
        2,
        1,
        100,
        budget,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-pack007",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        capabilityRefs,
        List.of(),
        "structured final or non-success");
  }

  private static AgentRunOutcome run(
      AgentLoopKernel kernel,
      TaskEnvelope task,
      CancellationSignal cancellation) {
    return kernel.run(
        new AgentRunContext(task.id(), task.principalRef(), task),
        cancellation);
  }

  @FunctionalInterface
  private interface ChildExecution {

    AgentWorkerExecution execute();
  }

  @FunctionalInterface
  private interface PreparationFactory {

    AgentWorkerRuntime.Preparation prepare(
        AgentRunContext parent,
        WorkerHandoffRequest request,
        AgentWorkerRuntime.ExecutionWindow window);
  }

  private record ReferenceArguments(String reference)
      implements AgentTool.ValidatedArguments {}
}
