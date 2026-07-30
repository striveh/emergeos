package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentExecutionProfileTest {

  @Test
  void legacyFakeProfilePreservesTheExistingUnboundExecutionIdentity() {
    AgentExecutionProfile profile =
        AgentExecutionProfile.legacyFakeV1();

    assertFalse(profile.modelBound());
    assertEquals("1.0", profile.taskSchemaVersion());
    assertEquals(RiskLevel.REVERSIBLE, profile.risk());
    assertEquals(BigDecimal.ZERO, profile.budgetUsd());
    assertNull(profile.pricing());
    assertNull(profile.modelAdapterVersion());
    assertNull(profile.environmentSnapshotRef());
    assertNull(profile.requiredDataClass());
    assertNull(profile.experiment());
    assertNull(profile.taskIdempotencyKey("task-001"));
    assertEquals(
        Map.of(
            "agent", "agent-draft-service-v1",
            "verifier", "agent-draft-verifier-v1",
            "trace-integrity", IntegrityHashes.PROFILE),
        profile.componentVersions());
  }

  @Test
  void modelBoundProfileDerivesRoutingAndReservesTheWholeRun() {
    AgentExecutionProfile profile = modelBoundProfile(new BigDecimal("0.022000"));

    assertTrue(profile.modelBound());
    assertEquals("openai.responses", profile.modelProvider());
    assertEquals("gpt-5.6-sol", profile.modelRequested());
    assertEquals("openai-gpt-5.6-sol-2026-07-v1", profile.pricingProfile());
    assertTrue(
        profile
            .taskIdempotencyKey("task-001")
            .matches("agent-task-[a-f0-9]{64}"));
    assertEquals(
        profile.taskIdempotencyKey("task-001"),
        profile.taskIdempotencyKey("task-001"));
    assertEquals(new BigDecimal("0.022000"), profile.reservationUsd());
    assertEquals(
        "openai-responses-v1-openai-java-4.43.0",
        profile.componentVersions().get("model-adapter"));
    assertEquals(
        profile.id(),
        profile.componentVersions().get("execution-profile"));
    assertEquals(
        profile.fingerprint(),
        profile
            .componentVersions()
            .get("execution-profile-fingerprint"));
    assertEquals(
        profile.pricing().fingerprint(),
        profile
            .componentVersions()
            .get("pricing-profile-fingerprint"));

    AgentExecutionProfile changedEnvironment =
        copyModelBound(
            profile,
            DataClass.PUBLIC,
            RiskLevel.EXTERNAL,
            List.of("capability://model-egress/synthetic-openai-v1"),
            "environment://sha256:" + "b".repeat(64));
    assertNotEquals(profile.fingerprint(), changedEnvironment.fingerprint());
    assertNotEquals(
        profile.taskIdempotencyKey("task-001"),
        changedEnvironment.taskIdempotencyKey("task-001"));
  }

  @Test
  void modelBoundProfileRejectsMissingOrUnderfundedSafetyBindings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> modelBoundProfile(new BigDecimal("0.021999")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copyModelBound(
                modelBoundProfile(new BigDecimal("0.022000")),
                DataClass.PERSONAL,
                RiskLevel.EXTERNAL,
                List.of("capability://model-egress/synthetic-openai-v1"),
                "environment://sha256:" + "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copyModelBound(
                modelBoundProfile(new BigDecimal("0.022000")),
                DataClass.PUBLIC,
                RiskLevel.READ_ONLY,
                List.of("capability://model-egress/synthetic-openai-v1"),
                "environment://sha256:" + "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copyModelBound(
                modelBoundProfile(new BigDecimal("0.022000")),
                DataClass.PUBLIC,
                RiskLevel.EXTERNAL,
                List.of(),
                "environment://sha256:" + "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copyModelBound(
                modelBoundProfile(new BigDecimal("0.022000")),
                DataClass.PUBLIC,
                RiskLevel.EXTERNAL,
                List.of("capability://model-egress/synthetic-openai-v1"),
                "environment://latest"));
  }

  @Test
  void modelBoundProfileAcceptsOnlyTheReviewedInputTokenCeiling() {
    PricingProfile pricing = pricing();
    BigDecimal exactReservation =
        pricing.reserveCostUsd(
            2,
            AgentExecutionProfile.MAX_REVIEWED_INPUT_TOKENS_PER_STEP,
            200);

    AgentExecutionProfile accepted =
        modelBoundProfile(
            AgentExecutionProfile.MAX_REVIEWED_INPUT_TOKENS_PER_STEP,
            200,
            exactReservation);

    assertEquals(
        AgentExecutionProfile.MAX_REVIEWED_INPUT_TOKENS_PER_STEP,
        accepted.maxInputTokensPerStep());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            modelBoundProfile(
                AgentExecutionProfile.MAX_REVIEWED_INPUT_TOKENS_PER_STEP + 1,
                200,
                new BigDecimal("1000.000000")));
  }

  @Test
  void capabilityListIsDefensivelyCopied() {
    java.util.ArrayList<String> capabilities =
        new java.util.ArrayList<>(
            List.of("capability://model-egress/synthetic-openai-v1"));
    AgentExecutionProfile baseline =
        modelBoundProfile(new BigDecimal("0.022000"));
    AgentExecutionProfile profile =
        copyModelBound(
            baseline,
            DataClass.PUBLIC,
            RiskLevel.EXTERNAL,
            capabilities,
            "environment://sha256:" + "a".repeat(64));

    capabilities.clear();

    assertEquals(
        List.of("capability://model-egress/synthetic-openai-v1"),
        profile.capabilityRefs());
    assertThrows(
        UnsupportedOperationException.class,
        () -> profile.capabilityRefs().add("capability://unexpected"));
  }

  @Test
  void requireTaskBindingRejectsAnyClientOrWiringDrift() {
    AgentExecutionProfile profile =
        modelBoundProfile(new BigDecimal("0.022000"));
    TaskEnvelope task = task(profile, "openai.responses");

    profile.requireTaskBinding(task);
    assertThrows(
        IllegalArgumentException.class,
        () -> profile.requireTaskBinding(task(profile, "openai.responses-alt")));
  }

  @Test
  void createsTheExactServerOwnedDraftTaskUsedByPreflightAndService() {
    AgentExecutionProfile profile =
        modelBoundProfile(new BigDecimal("0.022000"));

    TaskEnvelope task =
        profile.newDraftTask(
            "task-001",
            "synthetic-owner",
            "Create one synthetic public draft",
            "capture://synthetic",
            DataClass.PUBLIC);

    profile.requireTaskBinding(task);
    assertEquals(profile.taskIdempotencyKey(task.id()), task.idempotencyKey());
    assertEquals(profile.fingerprint(), profile.fingerprint());
  }

  private static AgentExecutionProfile modelBoundProfile(BigDecimal budgetUsd) {
    return modelBoundProfile(1_000, 200, budgetUsd);
  }

  private static AgentExecutionProfile modelBoundProfile(
      long maxInputTokensPerStep,
      long maxOutputTokensPerStep,
      BigDecimal budgetUsd) {
    return new AgentExecutionProfile(
        "synthetic-openai-agent-draft-v1",
        "1.1",
        RiskLevel.EXTERNAL,
        2,
        1,
        30_000,
        budgetUsd,
        maxInputTokensPerStep,
        maxOutputTokensPerStep,
        pricing(),
        "openai-responses-v1-openai-java-4.43.0",
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        new HarnessExperiment("openai-responses-h0", 1),
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v1",
        "environment://sha256:" + "a".repeat(64),
        List.of("capability://model-egress/synthetic-openai-v1"),
        DataClass.PUBLIC);
  }

  private static PricingProfile pricing() {
    return new PricingProfile(
        "openai-gpt-5.6-sol-2026-07-v1",
        "openai.responses",
        "gpt-5.6-sol",
        5_000,
        500,
        30_000);
  }

  private static AgentExecutionProfile copyModelBound(
      AgentExecutionProfile source,
      DataClass dataClass,
      RiskLevel risk,
      List<String> capabilities,
      String environmentSnapshotRef) {
    return new AgentExecutionProfile(
        source.id(),
        source.taskSchemaVersion(),
        risk,
        source.maxModelSteps(),
        source.maxToolCalls(),
        source.deadlineMs(),
        source.budgetUsd(),
        source.maxInputTokensPerStep(),
        source.maxOutputTokensPerStep(),
        source.pricing(),
        source.modelAdapterVersion(),
        source.agentVersion(),
        source.verifierVersion(),
        source.harnessVersion(),
        source.experiment(),
        source.policyVersion(),
        source.stateVersion(),
        source.contextPolicyVersion(),
        source.toolRegistryVersion(),
        environmentSnapshotRef,
        capabilities,
        dataClass);
  }

  private static TaskEnvelope task(
      AgentExecutionProfile profile, String provider) {
    return new TaskEnvelope(
        profile.taskSchemaVersion(),
        "task-001",
        null,
        "synthetic-owner",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        "Create one synthetic public draft",
        List.of("capture://synthetic"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        profile.risk(),
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("draft cites the source Capture"),
        false,
        profile.maxModelSteps(),
        profile.maxToolCalls(),
        profile.deadlineMs(),
        profile.budgetUsd(),
        provider,
        profile.modelRequested(),
        profile.pricingProfile(),
        profile.taskIdempotencyKey("task-001"),
        profile.policyVersion(),
        profile.stateVersion(),
        profile.contextPolicyVersion(),
        profile.toolRegistryVersion(),
        profile.environmentSnapshotRef(),
        profile.capabilityRefs(),
        List.of(),
        "structured final or non-success");
  }
}
