package io.emergeos.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunContextTest {

  @Test
  void derivesTheExactServerOwnedIdentityFromCanonicalRunningTruth() {
    TaskEnvelope task = task();
    AgentRun running =
        AgentRun.running(
            "parent-run-007",
            task.principalRef(),
            task,
            Instant.parse("2026-07-31T00:00:00Z"));

    AgentRunContext context = AgentRunContext.fromRunning(running);

    assertEquals("parent-run-007", context.runId());
    assertEquals(task.principalRef(), context.principalId());
    assertEquals(task, context.task());
  }

  @Test
  void rejectsInvalidRunIdentityOrPrincipalTaskDrift() {
    TaskEnvelope task = task();

    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRunContext("invalid/run", task.principalRef(), task));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRunContext("parent-run-007", "another-owner", task));
  }

  private static TaskEnvelope task() {
    return AgentExecutionProfile.legacyFakeV1()
        .newDraftTask(
            "parent-task-007",
            "context-owner",
            "验证 exact parent Run context",
            "capture://context-capture",
            DataClass.PUBLIC);
  }
}
