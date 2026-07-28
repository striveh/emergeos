package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.Claim;
import io.emergeos.contracts.RiskLevel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainInvariantTest {

  private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

  @Test
  void claimRequiresNonBlankTextAndFiniteConfidence() {
    assertThrows(IllegalArgumentException.class, () -> new Claim(" ", List.of(), 0.5));
    assertThrows(
        IllegalArgumentException.class, () -> new Claim("grounded claim", List.of(), Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Claim("grounded claim", List.of(), Double.POSITIVE_INFINITY));
  }

  @Test
  void reflectionCandidateRequiresFiniteConfidence() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReflectionCandidate(
                "reflection-1",
                "user-1",
                "STYLE",
                "Prefer short sentences",
                List.of("evi-1"),
                Double.NaN,
                "writing",
                "PENDING",
                "run-1",
                NOW));
  }

  @Test
  void artifactRequiresAtLeastOneNonBlankEvidenceReference() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactVersion(
                "art-1",
                1,
                "draft",
                ContentHashes.sha256("draft"),
                List.of(),
                "ws-1",
                "fake-agent",
                null,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactVersion(
                "art-1",
                1,
                "draft",
                ContentHashes.sha256("draft"),
                List.of(" "),
                "ws-1",
                "fake-agent",
                null,
                NOW));
  }

  @Test
  void workingSelfRequiresAtLeastOneNonBlankEvidenceReference() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkingSelf(
                "ws-1", "user-1", "self-0", List.of(), List.of(), List.of(), "hash", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkingSelf(
                "ws-1", "user-1", "self-0", List.of(" "), List.of(), List.of(), "hash", NOW));
  }

  @Test
  void manifestationRequiresSeedEvidenceInWorkingSelfProjection() {
    var workingSelf =
        new WorkingSelf(
            "ws-1",
            "user-1",
            "self-0",
            List.of("evi-other"),
            List.of(),
            List.of(),
            "hash",
            NOW);
    var artifact =
        new ArtifactVersion(
            "art-1",
            1,
            "draft",
            ContentHashes.sha256("draft"),
            List.of("evi-seed"),
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

    assertThrows(
        IllegalArgumentException.class,
        () ->
            Manifestation.awaitingApproval(
                "man-1", "user-1", "evi-seed", workingSelf, artifact, plan, NOW));
  }

  @Test
  void manifestationRejectsReflectionFromAnotherPrincipal() {
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
    manifestation.beginExecution(
        new ApprovalDecision(
            "approval-1",
            plan.planId(),
            plan.planHash(),
            artifact.contentHash(),
            "APPROVED",
            "user-1",
            NOW),
        NOW);
    manifestation.completeWithReceipt(
        new Receipt(
            "receipt-1",
            plan.planId(),
            "SUCCEEDED",
            "draft-1",
            plan.idempotencyKey(),
            artifact.contentHash(),
            "request-1",
            "memory://draft-1",
            NOW),
        NOW);

    var foreignCandidate =
        new ReflectionCandidate(
            "reflection-1",
            "user-2",
            "STYLE",
            "Prefer short sentences",
            List.of("art-1:v1", "receipt-1"),
            0.25,
            "writing",
            "PENDING",
            "man-1",
            NOW);

    assertThrows(
        IllegalArgumentException.class,
        () -> manifestation.attachReflection(foreignCandidate, NOW));
  }
}
