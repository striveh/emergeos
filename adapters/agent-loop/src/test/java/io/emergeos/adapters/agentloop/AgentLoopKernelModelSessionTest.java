package io.emergeos.adapters.agentloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
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
                    new AgentModel.ToolCall("capture.read", CAPTURE_REF),
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

    var outcome = kernel.run(task("usage-task"), CancellationSignal.never());

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
        new AgentLoopKernel(model, new AgentToolRegistry(List.of()), 2, 1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> kernel.run(task("session-a"), CancellationSignal.never()));
      var second = executor.submit(() -> kernel.run(task("session-b"), CancellationSignal.never()));

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
                        ? new AgentModel.ToolCall("capture.read", CAPTURE_REF)
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

    var outcome = kernel.run(task("drift-task"), CancellationSignal.never());

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
                        ? new AgentModel.ToolCall("capture.read", CAPTURE_REF)
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
        kernel.run(
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
        new AgentLoopKernel(model, new AgentToolRegistry(List.of()), 2, 1);

    var outcome = kernel.run(task("failure-task"), CancellationSignal.never());

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
        new AgentLoopKernel(model, new AgentToolRegistry(List.of()), 2, 1);

    var outcome =
        kernel.run(task("model-call-cancelled"), CancellationSignal.never());

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
        new AgentLoopKernel(model, new AgentToolRegistry(List.of()), 2, 1);

    var outcome =
        kernel.run(task("attributed-failure-task"), CancellationSignal.never());

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
            new AgentToolRegistry(List.of()),
            2,
            1,
            () -> now.getAndSet(TimeUnit.MILLISECONDS.toNanos(40)));

    var outcome =
        kernel.run(task("deadline-task", 100), CancellationSignal.never());

    assertEquals(RunStatus.SUCCEEDED, outcome.status());
    assertEquals(60, observed.get().remainingDeadlineMs());
  }

  private static AgentTool captureReadTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "capture.read";
      }

      @Override
      public AgentModel.ToolResult execute(
          TaskEnvelope task, AgentModel.ToolCall call) {
        return new AgentModel.ToolResult(name(), call.reference(), "synthetic evidence");
      }
    };
  }

  private static TaskEnvelope task(String id) {
    return task(id, 5_000);
  }

  private static TaskEnvelope task(String id, long deadlineMs) {
    return task(id, deadlineMs, new BigDecimal("0.010000"));
  }

  private static TaskEnvelope task(
      String id, long deadlineMs, BigDecimal budgetUsd) {
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
        2,
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
        "agent-tools-v1",
        "environment://sha256:" + "b".repeat(64),
        List.of("capability://model-egress/synthetic-openai-v1"),
        List.of(),
        "structured final or non-success");
  }
}
