package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GraphRunSelectionTest {

  @Test
  void runAndTaskIdentifiersShareTheExact128CharacterDomain() {
    String maximum = "r" + "a".repeat(127);
    assertEquals(maximum, selection(maximum, maximum, "profile").runId());

    String tooLong = "r" + "a".repeat(128);
    assertThrows(
        IllegalArgumentException.class,
        () -> selection(tooLong, "task", "profile"));
    assertThrows(
        IllegalArgumentException.class,
        () -> selection("run", tooLong, "profile"));
  }

  @Test
  void selectorHashHasReviewedUnicodeGoldenVectorsWithoutNormalization() {
    assertEquals(
        "5bac2ebcc48c7df087922e591c21e59109dbb73818a1affb5dbf74d987f599d9",
        selection(
                "run-unicode-1",
                "task-unicode-1",
                "执行🌱-\u00e9-e\u0301")
            .selectorHash());
    assertEquals(
        "8960e94aa89655f92a444c814e26554901a872f9776a60e90b4b7848cd134eaa",
        selection(
                "run-unicode-1",
                "task-unicode-1",
                "\u00e9")
            .selectorHash());
    assertEquals(
        "3ded58524fb477ce5af9eb8128b5306d4f94dcb229502c05278a5010b11fee03",
        selection(
                "run-unicode-1",
                "task-unicode-1",
                "e\u0301")
            .selectorHash());
    assertEquals(
        "af60d3c90a1bda2907b696bfbff28ec15778e977ba8c8b6c59e23bfe3079cc45",
        selection(
                "run-unicode-1",
                "task-unicode-1",
                "😀".repeat(200))
            .selectorHash());
    assertEquals(
        "31e461bca680bfc10bafca477a1055e5155088e7f82596169de8dc8b9a9124a4",
        selection(
                GraphRunRole.CHILD,
                "run-unicode-1",
                "task-unicode-1",
                "执行🌱-\u00e9-e\u0301")
            .selectorHash());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            selection(
                "run-unicode-1",
                "task-unicode-1",
                "\uD800"));
  }

  @Test
  void providerIntentAndEventShareTheExact512CharacterModelDomain() {
    String maximum = "m" + "a".repeat(511);
    GraphProviderIntent intent =
        new GraphProviderIntent(1, "4".repeat(64), maximum);
    GraphAttemptEvent event =
        GraphAttemptEvent.next(
            new GraphAttemptCursor(
                "principal",
                "5".repeat(64),
                "6".repeat(64),
                10,
                10,
                "7".repeat(64),
                GraphAttemptPhase.MODEL_READY),
            GraphAttemptEventType.PROVIDER_INTENT,
            Instant.parse("2026-07-31T06:00:00Z"),
            GraphAttemptPhase.PROVIDER_PENDING,
            GraphRunRole.CHILD,
            "child-run",
            "child-task",
            null,
            intent);

    assertEquals(maximum, event.modelRequested());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphProviderIntent(
                1, "4".repeat(64), "m" + "a".repeat(512)));
  }

  private static GraphRunSelection selection(
      String runId, String taskId, String executionProfileId) {
    return selection(
        GraphRunRole.PARENT,
        runId,
        taskId,
        executionProfileId);
  }

  private static GraphRunSelection selection(
      GraphRunRole role,
      String runId,
      String taskId,
      String executionProfileId) {
    return new GraphRunSelection(
        role,
        runId,
        taskId,
        "1".repeat(64),
        executionProfileId,
        "2".repeat(64),
        "注册表-版本😀",
        "伙伴-e\u0301-\u00e9",
        "3".repeat(64));
  }
}
