package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEnvelopeIntegrityHashTest {

  @Test
  void hashesTheCompletePromptBearingTaskDeterministically() {
    TaskEnvelope baseline = task("Create a synthetic public draft");
    TaskEnvelope intentDrift = task("Create a different synthetic public draft");

    assertEquals(
        IntegrityHashes.taskHash(baseline),
        IntegrityHashes.taskHash(baseline));
    assertNotEquals(
        IntegrityHashes.taskHash(baseline),
        IntegrityHashes.taskHash(intentDrift));
    assertEquals(
        "eebb3b1de68b34b74ea11b5431931c57e72805dcc0e8bb0266fc6725739ed188",
        IntegrityHashes.taskHash(baseline));
  }

  @Test
  void preservesTheLegacyV10CanonicalShapeAcrossJavaAndNode() {
    assertEquals(
        "0860319a84c2e02369cd62c0147b1dd25d7d64343d3fce620559584b2b77f015",
        IntegrityHashes.taskHash(legacyV10Task()));
  }

  private static TaskEnvelope task(String intent) {
    return new TaskEnvelope(
        "1.1",
        "task-synthetic-003",
        null,
        "synthetic-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        intent,
        List.of("capture://synthetic-003"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.EXTERNAL,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("draft cites the source Capture"),
        false,
        2,
        1,
        30_000,
        new BigDecimal("0.022000"),
        "openai.responses",
        "gpt-5.6",
        "openai-gpt-5.6-2026-07-v1",
        "agent-task-"
            + "a".repeat(64),
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v1",
        "environment://sha256:" + "b".repeat(64),
        List.of("capability://model-egress/synthetic-openai-v1"),
        List.of(),
        "structured final or non-success");
  }

  private static TaskEnvelope legacyV10Task() {
    return new TaskEnvelope(
        "1.0",
        "task-v10-explicit-null-binding",
        null,
        "synthetic-persona-quiet-builder",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Turn one thought into a local article draft",
        List.of("evidence://thought/001"),
        List.of("evidence://thought/001"),
        List.of("text"),
        DataClass.PERSONAL,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("local-draft-writer"),
        "urn:emergeos:output:article-draft:v1",
        List.of("draft preserves the original thought"),
        false,
        2,
        1,
        30_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        "task-v10-explicit-null-attempt",
        "policy-1",
        "state-1",
        "context-policy-1",
        "tools-1",
        null,
        List.of("capability://local-draft"),
        List.of(),
        "approval is required");
  }
}
