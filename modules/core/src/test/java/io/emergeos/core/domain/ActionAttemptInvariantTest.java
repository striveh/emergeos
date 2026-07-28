package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.RiskLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionAttemptInvariantTest {

  private static final String OWNER = "action-owner";
  private static final String CONNECTOR = "simulated.local-draft";
  private static final String AUDIENCE = "adapter:simulated-provider";
  private static final String ACCOUNT = "simulated-account:action-owner";
  private static final String ARTIFACT_HASH =
      "c4c44b55c3e7dfc4da429b52a25eb86cc20d52c900f19b0aef12232877855ae6";
  private static final String IDEMPOTENCY_KEY = "action-key-001";
  private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-07-28T10:05:00Z");

  @Test
  void capabilityBindsTheExactAuthorityPlanArtifactKeyAndExpiry() {
    ActionPlan plan = plan("plan-1", ARTIFACT_HASH, IDEMPOTENCY_KEY, EXPIRES_AT);
    ActionCapability capability =
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT);

    assertDoesNotThrow(
        () -> capability.assertAllows(plan, CONNECTOR, AUDIENCE, ACCOUNT, NOW));
    assertDenied(
        capability(
            "other-owner",
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(capability, plan, "other-connector", AUDIENCE, ACCOUNT, NOW);
    assertDenied(capability, plan, CONNECTOR, "other-audience", ACCOUNT, NOW);
    assertDenied(capability, plan, CONNECTOR, AUDIENCE, "other-account", NOW);
    assertDenied(
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            "other-plan",
            plan.planHash(),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            ContentHashes.sha256("substituted plan"),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ContentHashes.sha256("substituted Artifact"),
            IDEMPOTENCY_KEY,
            EXPIRES_AT),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            "other-key",
            EXPIRES_AT),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT.plusSeconds(1)),
        plan,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT,
        NOW);
    assertDenied(capability, plan, CONNECTOR, AUDIENCE, ACCOUNT, EXPIRES_AT);
  }

  @Test
  void attemptAndReceiptEnforceOnlyTheDeclaredStateMachine() {
    ActionPlan plan = plan("plan-1", ARTIFACT_HASH, IDEMPOTENCY_KEY, EXPIRES_AT);
    ApprovalDecision approval =
        new ApprovalDecision(
            "approval-1",
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            "APPROVED",
            OWNER,
            NOW);
    ActionCapability capability =
        capability(
            OWNER,
            CONNECTOR,
            AUDIENCE,
            ACCOUNT,
            plan.planId(),
            plan.planHash(),
            ARTIFACT_HASH,
            IDEMPOTENCY_KEY,
            EXPIRES_AT);
    ActionAttempt planned =
        new ActionAttempt(
            "attempt-1",
            plan,
            approval,
            capability,
            ActionAttemptStatus.PLANNED,
            0,
            List.of(new ActionTransition(1, null, ActionAttemptStatus.PLANNED, NOW)),
            null);

    assertEquals(ActionAttemptStatus.PLANNED, planned.status());
    assertEquals(0, planned.capabilityUsedCalls());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActionTransition(
                2,
                ActionAttemptStatus.PLANNED,
                ActionAttemptStatus.SUCCEEDED,
                NOW.plusSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActionAttempt(
                "attempt-1",
                plan,
                approval,
                capability,
                ActionAttemptStatus.UNKNOWN,
                1,
                planned.transitions(),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ActionReceipt.succeeded(
                "receipt-1",
                "attempt-1",
                "",
                "provider-request-1",
                "simulated://provider/request/1",
                NOW,
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ActionReceipt.failed(
                "receipt-1",
                "attempt-1",
                "",
                "provider-request-1",
                "simulated://provider/request/1",
                NOW,
                true));
  }

  private static void assertDenied(
      ActionCapability capability,
      ActionPlan plan,
      String connector,
      String audience,
      String account,
      Instant now) {
    assertThrows(
        IllegalStateException.class,
        () -> capability.assertAllows(plan, connector, audience, account, now));
  }

  private static ActionPlan plan(
      String planId, String artifactHash, String idempotencyKey, Instant expiresAt) {
    return new ActionPlan(
        planId,
        OWNER,
        "CREATE_LOCAL_DRAFT",
        "local://drafts",
        "artifact-1",
        1,
        artifactHash,
        RiskLevel.REVERSIBLE,
        "local-action-v1",
        idempotencyKey,
        expiresAt);
  }

  private static ActionCapability capability(
      String subject,
      String connector,
      String audience,
      String account,
      String planId,
      String planHash,
      String artifactHash,
      String idempotencyKey,
      Instant expiresAt) {
    return new ActionCapability(
        "capability-1",
        subject,
        connector,
        audience,
        account,
        planId,
        planHash,
        artifactHash,
        idempotencyKey,
        expiresAt,
        2);
  }
}
