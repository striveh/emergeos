package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CancellationSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OneShotExecutionPermitTest {

  @Test
  void authorizesThenConsumesTheExactTaskOnlyOnce() {
    AtomicLong now = new AtomicLong(10);
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(now::get);
    permit.arm(Duration.ofSeconds(30));

    permit.authorize(SyntheticEvalCatalog.task());
    permit.consume(SyntheticEvalCatalog.task());

    assertEquals(OneShotExecutionPermit.State.CONSUMED, permit.state());
    assertRejected(
        "PERMIT_ALREADY_CONSUMED",
        () -> permit.consume(SyntheticEvalCatalog.task()));
  }

  @Test
  void rejectsMissingExpiredAndMismatchedAuthority() {
    AtomicLong now = new AtomicLong(100);
    OneShotExecutionPermit unarmed =
        new OneShotExecutionPermit(now::get);
    assertRejected(
        "PERMIT_NOT_ARMED",
        () -> unarmed.authorize(SyntheticEvalCatalog.task()));

    OneShotExecutionPermit expired =
        new OneShotExecutionPermit(now::get);
    expired.arm(Duration.ofNanos(1));
    now.incrementAndGet();
    assertRejected(
        "PERMIT_EXPIRED",
        () -> expired.authorize(SyntheticEvalCatalog.task()));

    OneShotExecutionPermit mismatched =
        new OneShotExecutionPermit(() -> 0);
    mismatched.arm(Duration.ofSeconds(1));
    TaskEnvelope changed =
        SyntheticEvalCatalog.profile()
            .newDraftTask(
                "task-drift",
                SyntheticEvalCatalog.PRINCIPAL_ID,
                SyntheticEvalCatalog.INTENT,
                "capture://" + SyntheticEvalCatalog.CAPTURE_ID,
                io.emergeos.contracts.DataClass.PUBLIC);
    assertRejected(
        "PERMIT_TASK_MISMATCH", () -> mismatched.authorize(changed));
  }

  @Test
  void concurrentKernelCallsLetExactlyOneReachTheDelegate()
      throws Exception {
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(() -> 0);
    permit.arm(Duration.ofSeconds(30));
    AtomicInteger delegateCalls = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    AgentKernel delegate =
        new AgentKernel() {
          @Override
          public AgentRunOutcome run(
              TaskEnvelope task, CancellationSignal cancellation) {
            delegateCalls.incrementAndGet();
            return null;
          }

          @Override
          public String executionProfileId() {
            return SyntheticEvalCatalog.profile().id();
          }

          @Override
          public String executionProfileFingerprint() {
            return SyntheticEvalCatalog.profile().fingerprint();
          }
        };
    PermitBoundAgentKernel kernel =
        new PermitBoundAgentKernel(permit, delegate);
    Callable<String> attempt =
        () -> {
          start.await();
          try {
            kernel.run(
                SyntheticEvalCatalog.task(),
                CancellationSignal.never());
            return "DELEGATED";
          } catch (OneShotExecutionPermit.Rejected rejected) {
            return rejected.code();
          }
        };

    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Future<String>> futures = new ArrayList<>();
      futures.add(executor.submit(attempt));
      futures.add(executor.submit(attempt));
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> future : futures) {
        outcomes.add(future.get());
      }
      outcomes.sort(String::compareTo);
      assertEquals(
          List.of("DELEGATED", "PERMIT_ALREADY_CONSUMED"),
          outcomes);
    }
    assertEquals(1, delegateCalls.get());
    assertEquals(
        SyntheticEvalCatalog.profile().id(),
        kernel.executionProfileId());
    assertEquals(
        SyntheticEvalCatalog.profile().fingerprint(),
        kernel.executionProfileFingerprint());
  }

  @Test
  void concurrentArmPublishesOneAtomicExpiry() throws Exception {
    AtomicLong now = new AtomicLong(0);
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(now::get);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> shortArm =
        () -> armAfter(start, permit, Duration.ofNanos(10));
    Callable<String> longArm =
        () -> armAfter(start, permit, Duration.ofNanos(100));

    List<String> outcomes = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<String> first = executor.submit(shortArm);
      Future<String> second = executor.submit(longArm);
      start.countDown();
      outcomes.add(first.get());
      outcomes.add(second.get());
    }
    outcomes.sort(String::compareTo);
    assertEquals(
        1, outcomes.stream().filter(value -> value.startsWith("ARMED:")).count());
    assertEquals(
        1,
        outcomes.stream()
            .filter("PERMIT_ALREADY_ARMED"::equals)
            .count());

    long winningExpiry =
        outcomes.contains("ARMED:10") ? 10 : 100;
    now.set(winningExpiry - 1);
    permit.authorize(SyntheticEvalCatalog.task());
    now.set(winningExpiry);
    assertRejected(
        "PERMIT_EXPIRED",
        () -> permit.authorize(SyntheticEvalCatalog.task()));
  }

  @Test
  void expiryDuringConsumptionBurnsPermitBeforeDelegate() {
    AtomicInteger reads = new AtomicInteger();
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(
            () -> reads.getAndIncrement() < 2 ? 0 : 2);
    permit.arm(Duration.ofNanos(1));

    assertRejected(
        "PERMIT_EXPIRED",
        () -> permit.consume(SyntheticEvalCatalog.task()));
    assertEquals(OneShotExecutionPermit.State.CONSUMED, permit.state());
  }

  private static String armAfter(
      CountDownLatch start,
      OneShotExecutionPermit permit,
      Duration ttl)
      throws InterruptedException {
    start.await();
    try {
      permit.arm(ttl);
      return "ARMED:" + ttl.toNanos();
    } catch (OneShotExecutionPermit.Rejected rejected) {
      return rejected.code();
    }
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    OneShotExecutionPermit.Rejected rejected =
        assertThrows(OneShotExecutionPermit.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }
}
