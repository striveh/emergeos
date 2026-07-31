package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Pack008GraphExecutionPermitAcceptanceTest {

  @Test
  void exactParentThenChildAuthorizationAllowsOneChildConsumption() {
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(() -> 0);
    permit.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofSeconds(30));

    permit.parentAuthorizer().authorize(Pack008WorkerEvalCatalog.parentTask());
    permit.parentAuthorizer().authorize(Pack008WorkerEvalCatalog.parentTask());
    permit.childAuthorizer().authorize(Pack008WorkerEvalCatalog.childTask());
    permit.childAuthorizer().authorize(Pack008WorkerEvalCatalog.childTask());
    permit.consumeChild(childContext());

    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, permit.state());
    assertTrue(permit.parentAuthorized());
    assertTrue(permit.childAuthorized());
    assertRejected(
        "GRAPH_PERMIT_ALREADY_CONSUMED",
        () -> permit.consumeChild(childContext()));
  }

  @Test
  void rejectsMissingOrderWrongConsumerTaskDriftAndExpiry() {
    Pack008GraphExecutionPermit unarmed =
        new Pack008GraphExecutionPermit(() -> 0);
    assertRejected(
        "GRAPH_PERMIT_NOT_ARMED",
        () ->
            unarmed
                .parentAuthorizer()
                .authorize(Pack008WorkerEvalCatalog.parentTask()));

    Pack008GraphExecutionPermit outOfOrder =
        new Pack008GraphExecutionPermit(() -> 0);
    outOfOrder.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofSeconds(1));
    assertRejected(
        "GRAPH_PERMIT_PARENT_NOT_AUTHORIZED",
        () ->
            outOfOrder
                .childAuthorizer()
                .authorize(Pack008WorkerEvalCatalog.childTask()));
    assertFalse(outOfOrder.childAuthorized());
    assertRejected(
        "GRAPH_PERMIT_WRONG_CONSUMER",
        () ->
            outOfOrder.consumeChild(parentContext()));

    TaskEnvelope driftedParent =
        Pack008WorkerEvalCatalog.parentProfile()
            .newDraftTask(
                "task-openai-worker-parent-drift",
                Pack008WorkerEvalCatalog.PRINCIPAL_ID,
                Pack008WorkerEvalCatalog.INTENT,
                "capture://" + Pack008WorkerEvalCatalog.CAPTURE_ID,
                DataClass.PUBLIC);
    assertRejected(
        "GRAPH_PERMIT_PARENT_TASK_MISMATCH",
        () ->
            outOfOrder
                .parentAuthorizer()
                .authorize(driftedParent));

    AtomicLong now = new AtomicLong(10);
    Pack008GraphExecutionPermit expired =
        new Pack008GraphExecutionPermit(now::get);
    expired.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofNanos(1));
    now.incrementAndGet();
    assertRejected(
        "GRAPH_PERMIT_EXPIRED",
        () ->
            expired
                .parentAuthorizer()
                .authorize(Pack008WorkerEvalCatalog.parentTask()));
    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, expired.state());
  }

  @Test
  void rejectsChildRunIdentityDriftWithoutConsumingThePermit() {
    Pack008GraphExecutionPermit permit =
        armedAndAuthorizedPermit(() -> 0);

    assertRejected(
        "GRAPH_PERMIT_CHILD_CONTEXT_MISMATCH",
        () ->
            permit.consumeChild(
                new AgentRunContext(
                    "run-openai-worker-child-008-drift",
                    Pack008WorkerEvalCatalog.PRINCIPAL_ID,
                    Pack008WorkerEvalCatalog.childTask())));

    assertEquals(Pack008GraphExecutionPermit.State.ARMED, permit.state());
  }

  @Test
  void expiryCrossingDuringAuthorizationBurnsThePermit() {
    AtomicBoolean crossing = new AtomicBoolean();
    AtomicInteger reads = new AtomicInteger();
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(
            () ->
                crossing.get() && reads.getAndIncrement() > 0
                    ? 2
                    : 0);
    permit.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofNanos(1));
    crossing.set(true);

    assertRejected(
        "GRAPH_PERMIT_EXPIRED",
        () ->
            permit
                .parentAuthorizer()
                .authorize(Pack008WorkerEvalCatalog.parentTask()));

    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, permit.state());
  }

  @Test
  void rejectsAttemptIdentityDriftBeforeArming() {
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(() -> 0);

    assertRejected(
        "GRAPH_PERMIT_ATTEMPT_MISMATCH",
        () -> permit.arm("0".repeat(64), Duration.ofSeconds(30)));

    assertEquals(
        Pack008GraphExecutionPermit.State.PREPARED, permit.state());
  }

  @Test
  void concurrentChildKernelCallsLetExactlyOneReachDelegate()
      throws Exception {
    Pack008GraphExecutionPermit permit =
        armedAndAuthorizedPermit(() -> 0);
    AtomicInteger delegateCalls = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    AgentKernel delegate = childKernel(delegateCalls);
    Pack008GraphPermitBoundAgentKernel kernel =
        new Pack008GraphPermitBoundAgentKernel(permit, delegate);
    AgentRunContext context =
        new AgentRunContext(
            Pack008WorkerEvalCatalog.CHILD_RUN_ID,
            Pack008WorkerEvalCatalog.PRINCIPAL_ID,
            Pack008WorkerEvalCatalog.childTask());
    Callable<String> attempt =
        () -> {
          start.await();
          try {
            kernel.run(context, CancellationSignal.never());
            return "DELEGATED";
          } catch (Pack008GraphExecutionPermit.Rejected rejected) {
            return rejected.code();
          }
        };

    List<String> outcomes = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<String> first = executor.submit(attempt);
      Future<String> second = executor.submit(attempt);
      start.countDown();
      outcomes.add(first.get());
      outcomes.add(second.get());
    }
    outcomes.sort(String::compareTo);

    assertEquals(
        List.of("DELEGATED", "GRAPH_PERMIT_ALREADY_CONSUMED"),
        outcomes);
    assertEquals(1, delegateCalls.get());
    assertEquals(
        Pack008WorkerEvalCatalog.workerProfile().id(),
        kernel.executionProfileId());
    assertEquals(
        Pack008WorkerEvalCatalog
            .EXPECTED_WORKER_PROFILE_FINGERPRINT,
        kernel.executionProfileFingerprint());
  }

  @Test
  void consumeObserverFailureBurnsPermitBeforeDelegate() {
    Pack008GraphExecutionPermit permit =
        armedAndAuthorizedPermit(() -> 0);
    AtomicInteger delegateCalls = new AtomicInteger();
    Pack008GraphPermitBoundAgentKernel kernel =
        new Pack008GraphPermitBoundAgentKernel(
            permit,
            childKernel(delegateCalls),
            () -> {
              throw new IllegalStateException("journal unavailable");
            });
    AgentRunContext context =
        new AgentRunContext(
            Pack008WorkerEvalCatalog.CHILD_RUN_ID,
            Pack008WorkerEvalCatalog.PRINCIPAL_ID,
            Pack008WorkerEvalCatalog.childTask());

    assertThrows(
        IllegalStateException.class,
        () -> kernel.run(context, CancellationSignal.never()));

    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, permit.state());
    assertEquals(0, delegateCalls.get());
  }

  @Test
  void expiryDuringAtomicConsumptionBurnsPermitBeforeDelegate() {
    AtomicBoolean crossing = new AtomicBoolean();
    AtomicInteger crossingReads = new AtomicInteger();
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(
            () ->
                crossing.get() && crossingReads.getAndIncrement() > 0
                    ? 2
                    : 0);
    permit.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofNanos(1));
    permit.parentAuthorizer().authorize(Pack008WorkerEvalCatalog.parentTask());
    permit.childAuthorizer().authorize(Pack008WorkerEvalCatalog.childTask());
    crossing.set(true);

    assertRejected(
        "GRAPH_PERMIT_EXPIRED",
        () -> permit.consumeChild(childContext()));

    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, permit.state());
  }

  @Test
  void parentExecutionProfileCannotBeBoundAsTheChildDelegate() {
    AgentKernel parentKernel =
        new AgentKernel() {
          @Override
          public AgentRunOutcome run(
              AgentRunContext context, CancellationSignal cancellation) {
            return null;
          }

          @Override
          public String executionProfileId() {
            return Pack008WorkerEvalCatalog.parentProfile().id();
          }

          @Override
          public String executionProfileFingerprint() {
            return Pack008WorkerEvalCatalog.parentProfile().fingerprint();
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack008GraphPermitBoundAgentKernel(
                new Pack008GraphExecutionPermit(() -> 0),
                parentKernel));
  }

  @Test
  void exactChildProfileCannotHideNestedWorkerAuthority() {
    assertRejectsNestedWorkerAuthority("nested-worker-registry", null);
    assertRejectsNestedWorkerAuthority(null, "f".repeat(64));
  }

  private static void assertRejectsNestedWorkerAuthority(
      String registryVersion, String profileFingerprint) {
    AgentKernel nestedWorkerKernel =
        new AgentKernel() {
          @Override
          public AgentRunOutcome run(
              AgentRunContext context, CancellationSignal cancellation) {
            return null;
          }

          @Override
          public String executionProfileId() {
            return Pack008WorkerEvalCatalog.workerProfile().id();
          }

          @Override
          public String executionProfileFingerprint() {
            return Pack008WorkerEvalCatalog
                .EXPECTED_WORKER_PROFILE_FINGERPRINT;
          }

          @Override
          public String workerRegistryVersion() {
            return registryVersion;
          }

          @Override
          public String workerProfileFingerprint() {
            return profileFingerprint;
          }
        };

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack008GraphPermitBoundAgentKernel(
                new Pack008GraphExecutionPermit(() -> 0),
                nestedWorkerKernel));
  }

  private static Pack008GraphExecutionPermit armedAndAuthorizedPermit(
      java.util.function.LongSupplier clock) {
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(clock);
    permit.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofSeconds(30));
    permit.parentAuthorizer().authorize(Pack008WorkerEvalCatalog.parentTask());
    permit.childAuthorizer().authorize(Pack008WorkerEvalCatalog.childTask());
    return permit;
  }

  private static AgentKernel childKernel(AtomicInteger calls) {
    return new AgentKernel() {
      @Override
      public AgentRunOutcome run(
          AgentRunContext context, CancellationSignal cancellation) {
        calls.incrementAndGet();
        return null;
      }

      @Override
      public String executionProfileId() {
        return Pack008WorkerEvalCatalog.workerProfile().id();
      }

      @Override
      public String executionProfileFingerprint() {
        return Pack008WorkerEvalCatalog
            .EXPECTED_WORKER_PROFILE_FINGERPRINT;
      }
    };
  }

  private static AgentRunContext childContext() {
    return new AgentRunContext(
        Pack008WorkerEvalCatalog.CHILD_RUN_ID,
        Pack008WorkerEvalCatalog.PRINCIPAL_ID,
        Pack008WorkerEvalCatalog.childTask());
  }

  private static AgentRunContext parentContext() {
    return new AgentRunContext(
        Pack008WorkerEvalCatalog.PARENT_RUN_ID,
        Pack008WorkerEvalCatalog.PRINCIPAL_ID,
        Pack008WorkerEvalCatalog.parentTask());
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    Pack008GraphExecutionPermit.Rejected rejected =
        assertThrows(
            Pack008GraphExecutionPermit.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }
}
