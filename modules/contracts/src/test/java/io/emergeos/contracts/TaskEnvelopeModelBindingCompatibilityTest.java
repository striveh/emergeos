package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskEnvelopeModelBindingCompatibilityTest {

  @Test
  void legacyV10RequiresTheModelBindingToRemainAbsent() {
    assertDoesNotThrow(() -> task("1.0", null, null, null));
    for (List<String> binding :
        List.of(
            nullableBinding("openai.responses", null, null),
            nullableBinding(null, "gpt-5.6-sol", null),
            nullableBinding(null, null, "openai-gpt-5.6-sol-2026-07-v1"),
            nullableBinding("openai.responses", "gpt-5.6-sol", null),
            nullableBinding("openai.responses", null, "openai-gpt-5.6-sol-2026-07-v1"),
            nullableBinding(null, "gpt-5.6-sol", "openai-gpt-5.6-sol-2026-07-v1"),
            nullableBinding(
                "openai.responses",
                "gpt-5.6-sol",
                "openai-gpt-5.6-sol-2026-07-v1"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> task("1.0", binding.get(0), binding.get(1), binding.get(2)));
    }
  }

  @Test
  void modelBoundV11RequiresProviderRequestedModelAndPricingProfile() {
    assertThrows(
        IllegalArgumentException.class,
        () -> task("1.1", null, "gpt-5.6-sol", "openai-gpt-5.6-sol-2026-07-v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> task("1.1", "openai.responses", null, "openai-gpt-5.6-sol-2026-07-v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> task("1.1", "openai.responses", "gpt-5.6-sol", null));
    assertDoesNotThrow(
        () ->
            task(
                "1.1",
                "openai.responses",
                "gpt-5.6-sol",
                "openai-gpt-5.6-sol-2026-07-v1"));
  }

  @Test
  void modelBoundV11RequiresServerOwnedIdempotencyAndContentAddressedEnvironment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task(
                "1.1",
                "openai.responses",
                "gpt-5.6-sol",
                "openai-gpt-5.6-sol-2026-07-v1",
                null,
                "environment://sha256:" + "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task(
                "1.1",
                "openai.responses",
                "gpt-5.6-sol",
                "openai-gpt-5.6-sol-2026-07-v1",
                "synthetic-openai-eval-001",
                "environment://latest"));
  }

  @Test
  void modelBoundBundleRequiresAnAdapterVersion() {
    TaskEnvelope task =
        task(
            "1.1",
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1");
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            "run-model-bound",
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "gpt-5.6-sol-2026-07-15",
            "agent-v1",
            "verifier-v1",
            new BigDecimal("0.001000"),
            100,
            100,
            "/api/v1/agent-runs/run-model-bound/trace",
            "MODEL_RATE_LIMITED");

    assertThrows(
        IllegalArgumentException.class,
        () -> bundle(task, result, baseComponents()));
    assertDoesNotThrow(
        () ->
            bundle(
                task,
                result,
                Map.of(
                    "agent",
                    "agent-v1",
                    "verifier",
                    "verifier-v1",
                    "trace-integrity",
                    IntegrityHashes.PROFILE,
                    "model-adapter",
                    "openai-responses-v1-openai-java-4.43.0")));
  }

  @Test
  void legacyV10FrozenHashStillMatchesCreateConstructorAndPublicHelper() {
    HarnessRunBundle created =
        HarnessRunBundleConsistencyTest.goldenBundle();
    assertEquals(
        "58f245058fa868099468b8ae4f7b435b8587a5237ae64422b7524e60d8ffe828",
        created.integrityHash());
    assertEquals(created.integrityHash(), IntegrityHashes.bundleHash(created));

    HarnessRunBundle reconstructed =
        new HarnessRunBundle(
            created.schemaVersion(),
            created.runId(),
            created.taskId(),
            created.experiment(),
            created.modelResolved(),
            created.harnessVersion(),
            created.componentVersions(),
            created.environmentSnapshotRef(),
            created.toolRegistryVersion(),
            created.task(),
            created.result(),
            created.workingSelfRef(),
            created.traceRef(),
            created.traceRootHash(),
            created.handoffRefs(),
            created.checkpointRefs(),
            created.resourceBindings(),
            created.verificationRef(),
            created.failureAttribution(),
            created.outcome(),
            created.costUsd(),
            created.tokenCount(),
            created.latencyMs(),
            created.integrityProfile(),
            created.integrityHash());
    assertEquals(created.integrityHash(), reconstructed.integrityHash());
    assertEquals(reconstructed.integrityHash(), IntegrityHashes.bundleHash(reconstructed));
  }

  @Test
  void modelBoundV11HasOneFrozenCrossLanguageHashAndBindsRoutingMetadata() {
    ResultEnvelope result = failedResult();
    Map<String, String> components = modelBoundComponents();
    HarnessRunBundle original =
        bundle(
            task(
                "1.1",
                "openai.responses",
                "gpt-5.6-sol",
                "openai-gpt-5.6-sol-2026-07-v1"),
            result,
            components);

    assertEquals(
        "773f5a980a8feb67e5029ecdcbf17bd2220aa938ef4f53034af6a593a8c6c8e2",
        original.integrityHash(),
        "Java must match the shared JSON/Node v1.1 golden vector");
    assertEquals(original.integrityHash(), IntegrityHashes.bundleHash(original));
    assertNotEquals(
        original.integrityHash(),
        bundle(
                task(
                    "1.1",
                    "openai.responses-alt",
                    "gpt-5.6-sol",
                    "openai-gpt-5.6-sol-2026-07-v1"),
                result,
                components)
            .integrityHash());
    assertNotEquals(
        original.integrityHash(),
        bundle(
                task(
                    "1.1",
                    "openai.responses",
                    "gpt-5.6-sol-alt",
                    "openai-gpt-5.6-sol-2026-07-v1"),
                result,
                components)
            .integrityHash());
    assertNotEquals(
        original.integrityHash(),
        bundle(
                task(
                    "1.1",
                    "openai.responses",
                    "gpt-5.6-sol",
                    "openai-gpt-5.6-sol-2026-07-v2"),
                result,
                components)
            .integrityHash());
  }

  @Test
  void modelBoundFailurePreservesObservedCostEvenWhenTheBudgetWasBreached() {
    TaskEnvelope task =
        task(
            "1.1",
            "openai.responses",
            "gpt-5.6-sol",
            "openai-gpt-5.6-sol-2026-07-v1");
    ResultEnvelope failed =
        result(
            RunStatus.BLOCKED,
            new BigDecimal("0.011000"),
            "MODEL_BUDGET_EXHAUSTED");

    HarnessRunBundle bundle = bundle(task, failed, modelBoundComponents());

    assertEquals(new BigDecimal("0.011000"), bundle.costUsd());

    ResultEnvelope success =
        result(RunStatus.SUCCEEDED, new BigDecimal("0.011000"), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> bundle(task, success, modelBoundComponents()));

    ResultEnvelope unrelatedFailure =
        result(
            RunStatus.FAILED,
            new BigDecimal("0.011000"),
            "MODEL_RATE_LIMITED");
    assertThrows(
        IllegalArgumentException.class,
        () -> bundle(task, unrelatedFailure, modelBoundComponents()));
  }

  private static HarnessRunBundle bundle(
      TaskEnvelope task,
      ResultEnvelope result,
      Map<String, String> componentVersions) {
    return HarnessRunBundle.create(
        task.schemaVersion(),
        result.runId(),
        task.id(),
        new HarnessExperiment("openai-responses-h0", 1),
        result.resolvedModel(),
        "framework-free-agent-kernel-v2",
        componentVersions,
        task.environmentSnapshotRef(),
        task.toolRegistryVersion(),
        task,
        result,
        null,
        result.traceRef(),
        IntegrityHashes.emptyTraceRoot(),
        List.of(),
        List.of(),
        List.of(),
        null,
        result.failureReason(),
        result.status(),
        result.costUsd(),
        result.tokenCount(),
        result.latencyMs());
  }

  private static Map<String, String> baseComponents() {
    return Map.of(
        "agent",
        "agent-v1",
        "verifier",
        "verifier-v1",
        "trace-integrity",
        IntegrityHashes.PROFILE);
  }

  private static Map<String, String> modelBoundComponents() {
    return Map.of(
        "agent",
        "agent-v1",
        "verifier",
        "verifier-v1",
        "trace-integrity",
        IntegrityHashes.PROFILE,
        "model-adapter",
        "openai-responses-v1-openai-java-4.43.0");
  }

  private static ResultEnvelope failedResult() {
    return result(
        RunStatus.FAILED,
        new BigDecimal("0.001000"),
        "MODEL_RATE_LIMITED");
  }

  private static ResultEnvelope result(
      RunStatus status, BigDecimal costUsd, String failureReason) {
    return new ResultEnvelope(
        "1.0",
        "run-model-bound",
        "task-model-bound",
        status,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "gpt-5.6-sol-2026-07-15",
        "agent-v1",
        "verifier-v1",
        costUsd,
        100,
        100,
        "/api/v1/agent-runs/run-model-bound/trace",
        failureReason);
  }

  private static List<String> nullableBinding(
      String provider, String requestedModel, String pricingProfile) {
    return java.util.Arrays.asList(provider, requestedModel, pricingProfile);
  }

  private static TaskEnvelope task(
      String schemaVersion,
      String modelProvider,
      String modelRequested,
      String pricingProfile) {
    return task(
        schemaVersion,
        modelProvider,
        modelRequested,
        pricingProfile,
        "synthetic-openai-eval-001",
        "environment://sha256:" + "a".repeat(64));
  }

  private static TaskEnvelope task(
      String schemaVersion,
      String modelProvider,
      String modelRequested,
      String pricingProfile,
      String idempotencyKey,
      String environmentSnapshotRef) {
    return new TaskEnvelope(
        schemaVersion,
        "task-model-bound",
        null,
        "synthetic-eval-owner",
        List.of(),
        "MODEL_EVAL",
        "Evaluate one frozen synthetic prompt",
        List.of("capture://synthetic-model-eval"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.READ_ONLY,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("uses only frozen synthetic evidence"),
        false,
        2,
        1,
        30_000,
        new BigDecimal("0.010000"),
        modelProvider,
        modelRequested,
        pricingProfile,
        idempotencyKey,
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v1",
        environmentSnapshotRef,
        List.of("capability://model-egress/synthetic-openai-v1"),
        List.of(),
        "structured final or non-success");
  }
}
