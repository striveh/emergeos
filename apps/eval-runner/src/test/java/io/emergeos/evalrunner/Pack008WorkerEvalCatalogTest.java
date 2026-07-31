package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class Pack008WorkerEvalCatalogTest {

  @Test
  void freezesTheEntireChildOnlyModelGraph() {
    assertEquals(
        List.of(
            Pack008WorkerEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
            Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH,
            Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH,
            Pack008WorkerEvalCatalog.EXPECTED_PRICING_FINGERPRINT,
            Pack008WorkerEvalCatalog.EXPECTED_PARENT_PROFILE_FINGERPRINT,
            Pack008WorkerEvalCatalog.EXPECTED_WORKER_PROFILE_FINGERPRINT,
            Pack008WorkerEvalCatalog.EXPECTED_PROMPT_SURFACE_FINGERPRINT,
            Pack008WorkerEvalCatalog
                .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT,
            Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID),
        List.of(
            Pack008WorkerEvalCatalog.computedCaptureRequestHash(),
            Pack008WorkerEvalCatalog.computedParentTaskHash(),
            Pack008WorkerEvalCatalog.computedChildTaskHash(),
            Pack008WorkerEvalCatalog.pricing().fingerprint(),
            Pack008WorkerEvalCatalog.parentProfile().fingerprint(),
            Pack008WorkerEvalCatalog.workerProfile().fingerprint(),
            Pack008WorkerEvalCatalog.computedPromptSurfaceFingerprint(),
            io.emergeos.adapters.agentloop
                .DeterministicReadOnlyWorkerConductorModel
                .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT,
            Pack008WorkerEvalCatalog.computedAttemptId()));
  }
}
