package io.emergeos.adapters.agentloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AgentLoopKernelModelSessionTest {

  private static final String CAPTURE_REF = "capture://synthetic-agent-loop";

  @Test
  void opensOneRunScopedSessionAndAggregatesProviderUsage() {
    AtomicInteger opened = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AtomicReference<AgentModel.ModelCallContext> secondContext = new AtomicReference<>();
    AgentModel model =
        task -> {
          opened.incrementAndGet();
          return new AgentModel.Session() {
            private int step;

            @Override
            public AgentModel.ModelStep next(
                AgentModel.Turn turn, AgentModel.ModelCallContext context) {
              step++;
              if (step == 1) {
                return new AgentModel.ModelStep(
                    new AgentModel.ToolCall(
                        "capture.read",
                        AgentModel.ToolArguments.forReference(CAPTURE_REF)),
                    "provider-model-snapshot",
                    new AgentModel.ModelUsage(new BigDecimal("0.001000"), 11));
              }
              secondContext.set(context);
              return new AgentModel.ModelStep(
                  new AgentModel.FinalDraft("synthetic draft", List.of(CAPTURE_REF)),
                  "provider-model-snapshot",
                  new AgentModel.ModelUsage(new BigDecimal("0.002000"), 23));
            }

            @Override
            public void close() {
              closed.incrementAndGet();
            }
          };
        };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome = run(kernel, task("usage-task"), CancellationSignal.never());

    assertEquals(RunStatus.SUCCEEDED, outcome.status());
    assertEquals("provider-model-snapshot", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.003000"), outcome.costUsd());
    assertEquals(34, outcome.tokenCount());
    assertEquals(1, opened.get());
    assertEquals(1, closed.get());
    assertEquals(new BigDecimal("0.009000"), secondContext.get().remainingBudgetUsd());
    assertTrue(secondContext.get().remainingDeadlineMs() > 0);
  }

  @Test
  void opensIndependentSessionsForConcurrentRuns() throws Exception {
    CountDownLatch bothOpened = new CountDownLatch(2);
    AtomicReference<AgentModel.Session> firstSession = new AtomicReference<>();
    AtomicReference<AgentModel.Session> secondSession = new AtomicReference<>();
    AgentModel model =
        task -> {
          AgentModel.Session session =
              (turn, context) ->
                  new AgentModel.ModelStep(
                      new AgentModel.FinalDraft(
                          "draft for " + task.id(), List.of(CAPTURE_REF)),
                      "provider-model-snapshot",
                      AgentModel.ModelUsage.zero());
          if (!firstSession.compareAndSet(null, session)) {
            secondSession.set(session);
          }
          bothOpened.countDown();
          try {
            if (!bothOpened.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("concurrent session barrier timed out");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent session barrier interrupted");
          }
          return session;
        };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> run(kernel, task("session-a"), CancellationSignal.never()));
      var second = executor.submit(() -> run(kernel, task("session-b"), CancellationSignal.never()));

      assertEquals(RunStatus.SUCCEEDED, first.get(5, TimeUnit.SECONDS).status());
      assertEquals(RunStatus.SUCCEEDED, second.get(5, TimeUnit.SECONDS).status());
    }
    assertNotSame(firstSession.get(), secondSession.get());
  }

  @Test
  void preservesUsageAndFailsWhenTheResolvedModelDrifts() {
    AgentModel model =
        task ->
            new AgentModel.Session() {
              private int step;

              @Override
              public AgentModel.ModelStep next(
                  AgentModel.Turn turn, AgentModel.ModelCallContext context) {
                step++;
                return new AgentModel.ModelStep(
                    step == 1
                        ? new AgentModel.ToolCall(
                            "capture.read",
                            AgentModel.ToolArguments.forReference(CAPTURE_REF))
                        : new AgentModel.FinalDraft("must not commit", List.of(CAPTURE_REF)),
                    step == 1 ? "provider-model-a" : "provider-model-b",
                    new AgentModel.ModelUsage(new BigDecimal("0.001000"), 10));
              }
            };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome = run(kernel, task("drift-task"), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("MODEL_IDENTITY_DRIFT", outcome.failureReason());
    assertEquals("provider-model-b", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.002000"), outcome.costUsd());
    assertEquals(20, outcome.tokenCount());
  }

  @Test
  void budgetExhaustionTakesPrecedenceWhenTheSameStepAlsoDrifts() {
    AgentModel model =
        task ->
            new AgentModel.Session() {
              private int step;

              @Override
              public AgentModel.ModelStep next(
                  AgentModel.Turn turn, AgentModel.ModelCallContext context) {
                step++;
                return new AgentModel.ModelStep(
                    step == 1
                        ? new AgentModel.ToolCall(
                            "capture.read",
                            AgentModel.ToolArguments.forReference(CAPTURE_REF))
                        : new AgentModel.FinalDraft("must not commit", List.of(CAPTURE_REF)),
                    step == 1 ? "provider-model-a" : "provider-model-b",
                    new AgentModel.ModelUsage(new BigDecimal("0.006000"), 10));
              }
            };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome =
        run(kernel,
            task("drift-and-over-budget-task", 5_000, new BigDecimal("0.010000")),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("MODEL_BUDGET_EXHAUSTED", outcome.failureReason());
    assertEquals("provider-model-b", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.012000"), outcome.costUsd());
    assertEquals(20, outcome.tokenCount());
  }

  @Test
  void mapsTypedProviderFailureWithoutPersistingItsRawMessage() {
    String providerSecret = "RAW_PROVIDER_BODY_WITH_SECRET";
    AgentModel model =
        task ->
            (turn, context) -> {
              throw new AgentModelFailure(
                  AgentModelFailure.Code.RATE_LIMITED,
                  new IllegalStateException(providerSecret));
            };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome = run(kernel, task("failure-task"), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("MODEL_RATE_LIMITED", outcome.failureReason());
    assertTrue(outcome.toString().contains(providerSecret) == false);
  }

  @Test
  void mapsCancellationDetectedInsideTheModelCallToCancelled() {
    AgentModel model =
        task ->
            (turn, context) -> {
              throw new AgentModelFailure(AgentModelFailure.Code.CANCELLED);
            };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome =
        run(kernel, task("model-call-cancelled"), CancellationSignal.never());

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
  }

  @Test
  void preservesUsageWhenProviderReturnsAnAttributedFailureReceipt() {
    AgentModel model =
        task ->
            (turn, context) ->
                new AgentModel.ModelStep(
                    new AgentModel.Failed("MODEL_RESPONSE_MALFORMED"),
                    "provider-model-snapshot",
                    new AgentModel.ModelUsage(new BigDecimal("0.004000"), 41));
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome =
        run(kernel, task("attributed-failure-task"), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("MODEL_RESPONSE_MALFORMED", outcome.failureReason());
    assertEquals("provider-model-snapshot", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.004000"), outcome.costUsd());
    assertEquals(41, outcome.tokenCount());
  }

  @Test
  void passesTheRemainingDeadlineInsteadOfResettingTheTaskDeadline() {
    AtomicLong now = new AtomicLong();
    AtomicReference<AgentModel.ModelCallContext> observed = new AtomicReference<>();
    AgentModel model =
        task ->
            (turn, context) -> {
              observed.set(context);
              return new AgentModel.ModelStep(
                  new AgentModel.FinalDraft("synthetic draft", List.of(CAPTURE_REF)),
                  "provider-model-snapshot",
                  AgentModel.ModelUsage.zero());
            };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1,
            () -> now.getAndSet(TimeUnit.MILLISECONDS.toNanos(40)));

    var outcome =
        run(kernel, task("deadline-task", 100), CancellationSignal.never());

    assertEquals(RunStatus.SUCCEEDED, outcome.status());
    assertEquals(60, observed.get().remainingDeadlineMs());
  }

  @Test
  void validatorFailureIsTypedAndRedactedBeforeToolExecution() {
    String sentinel = "PRIVATE_VALIDATOR_SENTINEL";
    AtomicInteger executions = new AtomicInteger();
    AgentTool<Arguments> brokenTool =
        new AgentTool<>() {
          @Override
          public String name() {
            return "capture.read";
          }

          @Override
          public String argumentSchemaId() {
            return "urn:emergeos:tool:capture-read-arguments:v1";
          }

          @Override
          public Validation<Arguments> validate(
              TaskEnvelope task, AgentModel.ToolCall call) {
            throw new IllegalStateException(sentinel);
          }

          @Override
          public AgentModel.ToolResult execute(TaskEnvelope task, Arguments arguments) {
            executions.incrementAndGet();
            throw new AssertionError("Tool execute must not run");
          }
        };
    AgentModel model =
        task ->
            (turn, context) ->
                new AgentModel.ModelStep(
                    new AgentModel.ToolCall(
                        "capture.read",
                        AgentModel.ToolArguments.forReference(CAPTURE_REF)),
                    "provider-model-snapshot",
                    new AgentModel.ModelUsage(new BigDecimal("0.001000"), 11));
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(brokenTool)),
            2,
            1);

    var outcome = run(kernel, task("validator-failure"), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("TOOL_ARGUMENT_VALIDATION_FAILED", outcome.failureReason());
    assertEquals(new BigDecimal("0.001000"), outcome.costUsd());
    assertEquals(11, outcome.tokenCount());
    assertEquals(0, executions.get());
    assertEquals(2, outcome.trace().size());
    assertEquals("FAILED", outcome.trace().getLast().status());
    assertNull(outcome.trace().getLast().reference());
    assertFalse(outcome.toString().contains(sentinel));
  }

  @Test
  void cancellationObservedDuringValidationTakesPrecedenceOverInvalidArguments() {
    AtomicInteger executions = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    AgentTool<Arguments> invalidatingTool =
        captureReadTool(
            () -> cancelled.set(true),
            executions);
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            toolCallingModel(),
            new AgentToolRegistry(List.of(invalidatingTool)),
            2,
            1);

    var outcome =
        run(kernel, task("cancel-during-validation"), cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(0, executions.get());
    assertEquals(1, outcome.trace().size());
  }

  @Test
  void deadlineObservedDuringValidationTakesPrecedenceOverInvalidArguments() {
    AtomicLong now = new AtomicLong();
    AtomicInteger executions = new AtomicInteger();
    AgentTool<Arguments> invalidatingTool =
        captureReadTool(
            () -> now.set(TimeUnit.MILLISECONDS.toNanos(2)),
            executions);
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            toolCallingModel(),
            new AgentToolRegistry(List.of(invalidatingTool)),
            2,
            1,
            now::get);

    var outcome =
        run(kernel, task("deadline-during-validation", 1), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("DEADLINE_EXHAUSTED", outcome.failureReason());
    assertEquals(0, executions.get());
    assertEquals(1, outcome.trace().size());
  }

  @ParameterizedTest
  @EnumSource(LateToolMode.class)
  void rejectsEveryLateToolOutcomeBeforeResultShapeOrExceptionAttribution(
      LateToolMode mode) {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    String sentinel = "PRIVATE_LATE_TOOL_SENTINEL_" + mode;
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        mode,
                        7,
                        now,
                        validations,
                        executions,
                        sentinel,
                        () -> {}))),
            2,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel,
            task("late-tool-" + mode.name().toLowerCase(), 5),
            CancellationSignal.never());

    assertPostDispatchDeadlineOutcome(outcome);
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertFalse(outcome.toString().contains(sentinel));
  }

  @Test
  void toolCompletionAtTheExactDeadlineIsNotAttributedAsExceeded() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.VALID,
                        5,
                        now,
                        validations,
                        executions,
                        "SYNTHETIC_EXACT_BOUNDARY_RESULT",
                        () -> {}))),
            2,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel, task("exact-tool-deadline", 5), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("DEADLINE_EXHAUSTED", outcome.failureReason());
    assertEquals(5, outcome.latencyMs());
    assertEquals("provider-model-snapshot", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.001000"), outcome.costUsd());
    assertEquals(11, outcome.tokenCount());
    assertNull(outcome.proposal());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_RESULT),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(
        List.of("COMPLETED", "REQUESTED", "SUCCEEDED"),
        outcome.trace().stream().map(event -> event.status()).toList());
    assertFalse(outcome.toString().contains("SYNTHETIC_EXACT_BOUNDARY_RESULT"));
  }

  @Test
  void exactDeadlineStillWinsAtTheFinalAllowedModelStep() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.VALID,
                        5,
                        now,
                        validations,
                        executions,
                        "SYNTHETIC_FINAL_STEP_EXACT_BOUNDARY_RESULT",
                        () -> {}))),
            1,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel,
            taskWithMaxModelSteps("final-step-exact-deadline", 5, 1),
            CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("DEADLINE_EXHAUSTED", outcome.failureReason());
    assertEquals(5, outcome.latencyMs());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_RESULT),
        outcome.trace().stream().map(event -> event.type()).toList());
  }

  @Test
  void preDeadlineToolExceptionRemainsAnExecutionFailure() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    String sentinel = "PRIVATE_PRE_DEADLINE_TOOL_EXCEPTION";
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.THROW,
                        4,
                        now,
                        validations,
                        executions,
                        sentinel,
                        () -> {}))),
            2,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel, task("pre-deadline-tool-throw", 5), CancellationSignal.never());

    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals("TOOL_EXECUTION_FAILED", outcome.failureReason());
    assertEquals(4, outcome.latencyMs());
    assertEquals("provider-model-snapshot", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.001000"), outcome.costUsd());
    assertEquals(11, outcome.tokenCount());
    assertNull(outcome.proposal());
    assertEquals(List.of(), outcome.obtainedEvidenceRefs());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(
        List.of("COMPLETED", "REQUESTED", "FAILED"),
        outcome.trace().stream().map(event -> event.status()).toList());
    assertFalse(outcome.toString().contains(sentinel));
  }

  @Test
  void postDispatchDeadlineIsCanonicalWhenCancellationIsAlsoTrueAtThatBoundary() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.VALID,
                        7,
                        now,
                        validations,
                        executions,
                        "PRIVATE_SIMULTANEOUS_STOP_SENTINEL",
                        () -> cancelled.set(true)))),
            2,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel, task("simultaneous-post-dispatch-stop", 5), cancelled::get);

    assertPostDispatchDeadlineOutcome(outcome);
    assertTrue(cancelled.get());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
  }

  @Test
  void cancellationAloneAfterAReadPreservesItsResultButStopsBeforeTheNextModel() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.VALID,
                        4,
                        now,
                        validations,
                        executions,
                        "SYNTHETIC_CANCELLED_READ_RESULT",
                        () -> cancelled.set(true)))),
            2,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel, task("post-dispatch-cancellation-only", 5), cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(4, outcome.latencyMs());
    assertNull(outcome.proposal());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_RESULT),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertFalse(outcome.toString().contains("SYNTHETIC_CANCELLED_READ_RESULT"));
  }

  @Test
  void cancellationStillWinsAtTheFinalAllowedModelStep() {
    AtomicLong now = new AtomicLong();
    AtomicInteger modelCalls = new AtomicInteger();
    AtomicInteger validations = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            attributedToolCallingModel(modelCalls),
            new AgentToolRegistry(
                List.of(
                    timedCaptureReadTool(
                        LateToolMode.VALID,
                        4,
                        now,
                        validations,
                        executions,
                        "SYNTHETIC_FINAL_STEP_CANCELLED_RESULT",
                        () -> cancelled.set(true)))),
            1,
            1,
            now::get);

    AgentRunOutcome outcome =
        run(kernel,
            taskWithMaxModelSteps("final-step-cancelled", 5, 1),
            cancelled::get);

    assertEquals(RunStatus.CANCELLED, outcome.status());
    assertEquals("CANCELLED", outcome.failureReason());
    assertEquals(4, outcome.latencyMs());
    assertEquals(List.of(CAPTURE_REF), outcome.obtainedEvidenceRefs());
    assertEquals(1, modelCalls.get());
    assertEquals(1, validations.get());
    assertEquals(1, executions.get());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_RESULT),
        outcome.trace().stream().map(event -> event.type()).toList());
  }

  @Test
  void registryVersionMismatchBlocksBeforeOpeningAModelSession() {
    AtomicInteger opens = new AtomicInteger();
    AgentModel model =
        task -> {
          opens.incrementAndGet();
          throw new AssertionError("model must not open");
        };
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(List.of(captureReadTool())),
            2,
            1);

    var outcome =
        run(kernel,
            task(
                "registry-version-mismatch",
                5_000,
                new BigDecimal("0.010000"),
                "agent-tools-v3"),
            CancellationSignal.never());

    assertEquals(RunStatus.BLOCKED, outcome.status());
    assertEquals("TOOL_REGISTRY_MISMATCH", outcome.failureReason());
    assertEquals(0, opens.get());
    assertEquals(List.of(), outcome.trace());
  }

  @Test
  void registryVersionRejectsAnUnboundToolSchema() {
    AgentTool<Arguments> driftedSchema =
        new AgentTool<>() {
          @Override
          public String name() {
            return "capture.read";
          }

          @Override
          public String argumentSchemaId() {
            return "urn:emergeos:tool:capture-read-arguments:v2";
          }

          @Override
          public Validation<Arguments> validate(
              TaskEnvelope task, AgentModel.ToolCall call) {
            return Validation.invalid();
          }

          @Override
          public AgentModel.ToolResult execute(
              TaskEnvelope task, Arguments arguments) {
            throw new AssertionError("Tool execute must not run");
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolRegistry(List.of(driftedSchema)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolRegistry(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentToolRegistry("agent-tools-v3", List.of()));
  }

  @Test
  void opaqueToolArgumentsAreBoundedImmutableAndRedacted() {
    String sentinel = "PRIVATE_ARGUMENT_SENTINEL";
    AgentModel.ToolArguments arguments =
        AgentModel.ToolArguments.fromJson(
            "{\"reference\":\"capture://synthetic-agent-loop\","
                + "\"unexpected\":\""
                + sentinel
                + "\"}");
    byte[] exported = arguments.copyUtf8();
    exported[0] = 'x';

    assertEquals(
        AgentModel.ToolArguments.fromJson(
            "{\"reference\":\"capture://synthetic-agent-loop\","
                + "\"unexpected\":\""
                + sentinel
                + "\"}"),
        arguments);
    assertFalse(arguments.toString().contains(sentinel));
    assertFalse(
        new AgentModel.ToolCall("capture.read", arguments)
            .toString()
            .contains(sentinel));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentModel.ToolArguments.fromJson(
                "x".repeat(AgentModel.ToolArguments.MAX_UTF8_BYTES + 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentModel.ToolArguments.fromJson("before\0after"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentModel.ToolArguments.fromJson("\uD800"));
  }

  private static void assertPostDispatchDeadlineOutcome(AgentRunOutcome outcome) {
    assertEquals(RunStatus.FAILED, outcome.status());
    assertEquals(
        "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH", outcome.failureReason());
    assertEquals("provider-model-snapshot", outcome.resolvedModel());
    assertEquals(new BigDecimal("0.001000"), outcome.costUsd());
    assertEquals(11, outcome.tokenCount());
    assertEquals(7, outcome.latencyMs());
    assertNull(outcome.proposal());
    assertEquals(List.of(), outcome.obtainedEvidenceRefs());
    assertEquals(
        List.of(
            AgentTraceEventType.MODEL_STEP,
            AgentTraceEventType.TOOL_REQUEST,
            AgentTraceEventType.TOOL_REJECTED),
        outcome.trace().stream().map(event -> event.type()).toList());
    assertEquals(
        List.of("COMPLETED", "REQUESTED", "DEADLINE_EXCEEDED"),
        outcome.trace().stream().map(event -> event.status()).toList());
    assertEquals(
        List.of(CAPTURE_REF, CAPTURE_REF),
        outcome.trace().stream()
            .filter(event -> event.toolName() != null)
            .map(event -> event.reference())
            .toList());
    assertEquals(
        List.of("capture.read", "capture.read"),
        outcome.trace().stream()
            .filter(event -> event.toolName() != null)
            .map(event -> event.toolName())
            .toList());
    assertFalse(
        outcome.trace().stream()
            .anyMatch(event -> event.type() == AgentTraceEventType.TOOL_RESULT));
  }

  private static AgentModel attributedToolCallingModel(AtomicInteger calls) {
    return task ->
        (turn, context) -> {
          calls.incrementAndGet();
          return new AgentModel.ModelStep(
              new AgentModel.ToolCall(
                  "capture.read",
                  AgentModel.ToolArguments.forReference(CAPTURE_REF)),
              "provider-model-snapshot",
              new AgentModel.ModelUsage(new BigDecimal("0.001000"), 11));
        };
  }

  private static AgentTool<Arguments> timedCaptureReadTool(
      LateToolMode mode,
      long completionMs,
      AtomicLong now,
      AtomicInteger validations,
      AtomicInteger executions,
      String sentinel,
      Runnable completionHook) {
    return new AgentTool<>() {
      @Override
      public String name() {
        return "capture.read";
      }

      @Override
      public String argumentSchemaId() {
        return "urn:emergeos:tool:capture-read-arguments:v1";
      }

      @Override
      public Validation<Arguments> validate(
          TaskEnvelope task, AgentModel.ToolCall call) {
        validations.incrementAndGet();
        return Validation.valid(new Arguments(task.inputRefs().getFirst()));
      }

      @Override
      public AgentModel.ToolResult execute(
          TaskEnvelope task, Arguments arguments) {
        executions.incrementAndGet();
        now.set(TimeUnit.MILLISECONDS.toNanos(completionMs));
        completionHook.run();
        return switch (mode) {
          case VALID ->
              new AgentModel.ToolResult(name(), arguments.reference(), sentinel);
          case THROW -> throw new IllegalStateException(sentinel);
          case NULL -> null;
          case WRONG_REFERENCE ->
              new AgentModel.ToolResult(
                  name(), "capture://wrong-reference", sentinel);
        };
      }
    };
  }

  private static AgentTool<Arguments> captureReadTool() {
    return new AgentTool<>() {
      @Override
      public String name() {
        return "capture.read";
      }

      @Override
      public String argumentSchemaId() {
        return "urn:emergeos:tool:capture-read-arguments:v1";
      }

      @Override
      public Validation<Arguments> validate(
          TaskEnvelope task, AgentModel.ToolCall call) {
        return Validation.valid(new Arguments(task.inputRefs().getFirst()));
      }

      @Override
      public AgentModel.ToolResult execute(TaskEnvelope task, Arguments arguments) {
        return new AgentModel.ToolResult(
            name(), arguments.reference(), "synthetic evidence");
      }
    };
  }

  private static AgentTool<Arguments> captureReadTool(
      Runnable duringValidation, AtomicInteger executions) {
    return new AgentTool<>() {
      @Override
      public String name() {
        return "capture.read";
      }

      @Override
      public String argumentSchemaId() {
        return "urn:emergeos:tool:capture-read-arguments:v1";
      }

      @Override
      public Validation<Arguments> validate(
          TaskEnvelope task, AgentModel.ToolCall call) {
        duringValidation.run();
        return Validation.invalid();
      }

      @Override
      public AgentModel.ToolResult execute(TaskEnvelope task, Arguments arguments) {
        executions.incrementAndGet();
        throw new AssertionError("Tool execute must not run");
      }
    };
  }

  private static AgentModel toolCallingModel() {
    return task ->
        (turn, context) ->
            new AgentModel.ModelStep(
                new AgentModel.ToolCall(
                    "capture.read",
                    AgentModel.ToolArguments.forReference(CAPTURE_REF)),
                "provider-model-snapshot",
                AgentModel.ModelUsage.zero());
  }

  private enum LateToolMode {
    VALID,
    THROW,
    NULL,
    WRONG_REFERENCE
  }

  private record Arguments(String reference) implements AgentTool.ValidatedArguments {}

  private static AgentRunOutcome run(
      AgentLoopKernel kernel,
      TaskEnvelope task,
      CancellationSignal cancellation) {
    return kernel.run(
        new AgentRunContext(task.id(), task.principalRef(), task),
        cancellation);
  }

  private static TaskEnvelope task(String id) {
    return task(id, 5_000);
  }

  private static TaskEnvelope task(String id, long deadlineMs) {
    return task(id, deadlineMs, new BigDecimal("0.010000"));
  }

  private static TaskEnvelope task(
      String id, long deadlineMs, BigDecimal budgetUsd) {
    return task(id, deadlineMs, budgetUsd, "agent-tools-v2");
  }

  private static TaskEnvelope task(
      String id,
      long deadlineMs,
      BigDecimal budgetUsd,
      String toolRegistryVersion) {
    return task(id, deadlineMs, budgetUsd, toolRegistryVersion, 2);
  }

  private static TaskEnvelope taskWithMaxModelSteps(
      String id, long deadlineMs, int maxModelSteps) {
    return task(
        id,
        deadlineMs,
        new BigDecimal("0.010000"),
        "agent-tools-v2",
        maxModelSteps);
  }

  private static TaskEnvelope task(
      String id,
      long deadlineMs,
      BigDecimal budgetUsd,
      String toolRegistryVersion,
      int maxModelSteps) {
    return new TaskEnvelope(
        "1.1",
        id,
        null,
        "synthetic-eval-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Create a synthetic evidence-linked draft",
        List.of(CAPTURE_REF),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.READ_ONLY,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("draft cites the synthetic Capture"),
        false,
        maxModelSteps,
        1,
        deadlineMs,
        budgetUsd,
        "openai.responses",
        "gpt-5.6-sol",
        "openai-gpt-5.6-sol-2026-07-v1",
        "synthetic-eval-" + id,
        "synthetic-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        toolRegistryVersion,
        "environment://sha256:" + "b".repeat(64),
        List.of("capability://model-egress/synthetic-openai-v1"),
        List.of(),
        "structured final or non-success");
  }
}
