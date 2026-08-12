package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.emergeos.core.application.HarnessEvaluationReportProjector;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphRunSelection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Pack010GraphEvalCatalogTest {

  @Test
  void freezesCheckedInAssetBytes() {
    assertEquals(
        "a569692018a902dc9a0e3b404e09696d41d1a771d8b24828fe2b12957ad09f1d",
        Pack010GraphEvalCatalog.PACK_RAW_SHA256);
    assertEquals(
        "affe0b8b1a14bf6c7b2dce039067318871275f4db85eec182790ff8082c769f0",
        Pack010GraphEvalCatalog.ENVIRONMENT_RAW_SHA256);
  }

  @Test
  void freezesThreeOrderedManifestAndAttemptIdentities() {
    List<GraphAttemptManifest> manifests =
        Pack010GraphEvalCatalog.manifests();

    assertEquals(3, manifests.size());
    assertEquals(
        List.of("pack010-r1", "pack010-r2", "pack010-r3"),
        manifests.stream()
            .map(GraphAttemptManifest::executionSlotId)
            .toList());
    assertEquals(
        List.of(1, 2, 3),
        manifests.stream()
            .map(manifest -> manifest.experiment().repetition())
            .toList());
    assertEquals(
        List.of(
            "bb0047b3c8b386896f45224f76940c7280342a4a2d0dae470ee0fa57bc364707",
            "d9aeea54ee84e8ff34dab0b5e21a8f355811261f259dc0d33dca69e1aec743c8",
            "733125cd9bacfdc34ec18c833ba1427fffa9581e53e1e25775a815109dfe01c0"),
        manifests.stream()
            .map(GraphAttemptManifest::manifestHash)
            .toList());
    assertEquals(
        List.of(
            "bb0047b3c8b386896f45224f76940c7280342a4a2d0dae470ee0fa57bc364707",
            "d9aeea54ee84e8ff34dab0b5e21a8f355811261f259dc0d33dca69e1aec743c8",
            "733125cd9bacfdc34ec18c833ba1427fffa9581e53e1e25775a815109dfe01c0"),
        manifests.stream().map(GraphAttemptManifest::attemptId).toList());
    assertEquals(
        3,
        manifests.stream()
            .map(GraphAttemptManifest::attemptId)
            .distinct()
            .count());
  }

  @Test
  void producesAProjectorCompatibleManifestSet() {
    HarnessEvaluationReportProjector.Reduction.Unavailable unavailable =
        assertInstanceOf(
            HarnessEvaluationReportProjector.Reduction.Unavailable.class,
            new HarnessEvaluationReportProjector(
                    ignored -> new GraphAttemptVerification.Missing())
                .project(Pack010GraphEvalCatalog.manifests()));

    assertEquals("REPETITION_MISSING", unavailable.reasonCode());
    assertEquals(1, unavailable.repetition());
  }

  @Test
  void givesEveryRepetitionUniqueRunAndTaskIdentities() {
    List<GraphAttemptManifest> manifests =
        Pack010GraphEvalCatalog.manifests();
    Set<String> runIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();

    for (GraphAttemptManifest manifest : manifests) {
      GraphRunSelection parent = manifest.parentSelection();
      GraphRunSelection child = manifest.childSelection();
      runIds.add(parent.runId());
      runIds.add(child.runId());
      taskIds.add(parent.taskId());
      taskIds.add(child.taskId());
    }

    assertEquals(6, runIds.size());
    assertEquals(6, taskIds.size());
    assertEquals(
        Set.of(
            "run-openai-worker-parent-010-r1",
            "run-openai-worker-child-010-r1",
            "run-openai-worker-parent-010-r2",
            "run-openai-worker-child-010-r2",
            "run-openai-worker-parent-010-r3",
            "run-openai-worker-child-010-r3"),
        runIds);
    assertEquals(
        Set.of(
            "task-openai-worker-parent-010-r1",
            "task-openai-worker-child-010-r1",
            "task-openai-worker-parent-010-r2",
            "task-openai-worker-child-010-r2",
            "task-openai-worker-parent-010-r3",
            "task-openai-worker-child-010-r3"),
        taskIds);
  }

  @Test
  void repetitionProducesThreeFrozenProfileFingerprints() {
    List<ModelBoundReadOnlyWorkerExecutionProfile> workers =
        Pack010GraphEvalCatalog.workerProfiles();

    assertEquals(3, workers.size());
    assertEquals(
        List.of(1, 2, 3),
        workers.stream()
            .map(worker -> worker.experiment().repetition())
            .toList());
    assertEquals(
        List.of(
            "1505d6e3236060e9833b2c15d187aae48ff6644c03693670908b99e60168e500",
            "9ff846a2e14000585b3fcaa9b47bd73ce28960eff1a67604d99549b519436d3d",
            "79a9bba05ac12e2438b1af5f938a26b9fd429e5b9de97c64343193ac4361adb7"),
        workers.stream()
            .map(ModelBoundReadOnlyWorkerExecutionProfile::fingerprint)
            .toList());
    assertEquals(
        3,
        workers.stream()
            .map(ModelBoundReadOnlyWorkerExecutionProfile::fingerprint)
            .distinct()
            .count());
  }

  @Test
  void freezesAllCompiledCatalogIdentities() {
    assertEquals(
        Pack010GraphEvalCatalog.EXPECTED_COMPILED_IDENTITIES,
        Pack010GraphEvalCatalog.computedCompiledIdentities());
  }
}
