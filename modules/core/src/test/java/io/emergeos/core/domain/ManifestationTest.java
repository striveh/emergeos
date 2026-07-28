package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.emergeos.contracts.RiskLevel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestationTest {

  private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

  @Test
  void rejectsApprovalForAStaleArtifactHash() {
    var workingSelf =
        new WorkingSelf(
            "ws-1", "user-1", "self-0", List.of("evi-1"), List.of(), List.of(), "hash", NOW);
    var artifact =
        new ArtifactVersion(
            "art-1",
            1,
            "draft",
            ContentHashes.sha256("draft"),
            List.of("evi-1"),
            "ws-1",
            "fake-agent",
            null,
            NOW);
    var plan =
        new ActionPlan(
            "plan-1",
            "user-1",
            "local.draft.create",
            "local-draftbox",
            "art-1",
            1,
            artifact.contentHash(),
            RiskLevel.REVERSIBLE,
            "policy-v1",
            "idem-1",
            NOW.plus(1, ChronoUnit.DAYS));
    var manifestation =
        Manifestation.awaitingApproval(
            "man-1", "user-1", "evi-1", workingSelf, artifact, plan, NOW);

    assertThrows(
        IllegalStateException.class,
        () ->
            manifestation.beginExecution(
                new ApprovalDecision(
                    "approval-1",
                    plan.planId(),
                    plan.planHash(),
                    "stale-hash",
                    "APPROVED",
                    "user-1",
                    NOW),
                NOW));
  }

  @Test
  void actionPlanHashCannotBeConfusedByFieldDelimiters() {
    var first =
        new ActionPlan(
            "plan|owner",
            "user",
            "local.draft.create",
            "box",
            "art-1",
            1,
            ContentHashes.sha256("draft"),
            RiskLevel.REVERSIBLE,
            "policy-v1",
            "idem-1",
            NOW.plus(1, ChronoUnit.DAYS));
    var second =
        new ActionPlan(
            "plan",
            "owner|user",
            "local.draft.create",
            "box",
            "art-1",
            1,
            ContentHashes.sha256("draft"),
            RiskLevel.REVERSIBLE,
            "policy-v1",
            "idem-1",
            NOW.plus(1, ChronoUnit.DAYS));

    assertNotEquals(first.planHash(), second.planHash());
  }
}
