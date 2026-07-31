package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerResultEnvelopeTest {

  private static final String CONTENT =
      """
      # 从想法到可信成果

      Prompt 工程是在为概率程序构造运行时状态。

      目标：产出一篇引用该 Capture 的短文 proposal
      """
          .strip();
  private static final String CONTENT_HASH =
      "e5b6493d7741cf43b65fb45c29d0351f3d038e470df31f964e6f1fa9cc8f767b";
  private static final String INTEGRITY_HASH =
      "d69ecb08300f1f1cd378d826243add922c0deb75a15d9393a63d5d00d2a2f5a8";

  @Test
  void createsAContentAndEvidenceBoundEnvelope() {
    WorkerResultEnvelope result =
        WorkerResultEnvelope.create(
            "pack007-child-run",
            "pack007-child-task",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            CONTENT,
            List.of("capture://pack007-capture"));

    assertEquals("worker-result://pack007-child-run", result.workerResultRef());
    assertEquals(CONTENT_HASH, result.contentHash());
    assertEquals(INTEGRITY_HASH, result.integrityHash());
    assertFalse(result.toString().contains(CONTENT));
  }

  @Test
  void rejectsContentOrIntegrityTampering() {
    WorkerResultEnvelope valid =
        WorkerResultEnvelope.create(
            "child-run-007",
            "child-task-007",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            CONTENT,
            List.of("capture://pack007-capture"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkerResultEnvelope(
                valid.schemaVersion(),
                valid.workerResultRef(),
                valid.childRunId(),
                valid.childTaskId(),
                valid.outputSchema(),
                CONTENT + "tampered",
                valid.contentHash(),
                valid.evidenceRefs(),
                valid.integrityProfile(),
                valid.integrityHash()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkerResultEnvelope(
                valid.schemaVersion(),
                valid.workerResultRef(),
                valid.childRunId(),
                valid.childTaskId(),
                valid.outputSchema(),
                valid.content(),
                valid.contentHash(),
                List.of("capture://other"),
                valid.integrityProfile(),
                valid.integrityHash()));
  }

  @Test
  void rejectsMalformedTypedBindings() {
    String hash = "a".repeat(64);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceBinding(ResourceRole.HANDOFF, 0, "task://child", hash));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceBinding(
                ResourceRole.WORKER_RESULT,
                1,
                "worker-result://child-run-007",
                hash));
  }

  @Test
  void carriesAFullDraftButRejectsContentBeyondTheArtifactBoundary() {
    WorkerResultEnvelope ordinaryDraft =
        WorkerResultEnvelope.create(
            "child-run-long",
            "child-task-long",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            "文".repeat(2_049),
            List.of("capture://pack007-capture"));
    WorkerResultEnvelope exactBmpBoundary =
        WorkerResultEnvelope.create(
            "child-run-exact-bmp",
            "child-task-exact-bmp",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            "文".repeat(WorkerResultEnvelope.MAX_CONTENT_LENGTH),
            List.of("capture://pack007-capture"));
    WorkerResultEnvelope exactAstralBoundary =
        WorkerResultEnvelope.create(
            "child-run-exact-astral",
            "child-task-exact-astral",
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            "😀".repeat(WorkerResultEnvelope.MAX_CONTENT_LENGTH / 2),
            List.of("capture://pack007-capture"));

    assertEquals(2_049, ordinaryDraft.content().length());
    assertEquals(
        WorkerResultEnvelope.MAX_CONTENT_LENGTH,
        exactBmpBoundary.content().length());
    assertEquals(
        WorkerResultEnvelope.MAX_CONTENT_LENGTH,
        exactAstralBoundary.content().length());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkerResultEnvelope.create(
                "child-run-too-long",
                "child-task-too-long",
                "urn:emergeos:schema:internal:agent-draft-proposal:v1",
                "文".repeat(WorkerResultEnvelope.MAX_CONTENT_LENGTH + 1),
                List.of("capture://pack007-capture")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkerResultEnvelope.create(
                "child-run-too-long-astral",
                "child-task-too-long-astral",
                "urn:emergeos:schema:internal:agent-draft-proposal:v1",
                "😀".repeat(WorkerResultEnvelope.MAX_CONTENT_LENGTH / 2 + 1),
                List.of("capture://pack007-capture")));
  }
}
