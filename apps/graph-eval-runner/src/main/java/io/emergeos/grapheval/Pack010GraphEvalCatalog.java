package io.emergeos.grapheval;

import io.emergeos.adapters.agentloop.DeterministicReadOnlyWorkerConductorModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.CaptureCommand;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiled, zero-egress identity catalog for the Pack010 three-repetition
 * shared-candidate pilot.
 *
 * <p>This class only constructs immutable domain values. It has no Store,
 * credential, model client, network, clock, console or execution dependency.
 */
final class Pack010GraphEvalCatalog {

  static final String PACK_PATH =
      "evals/task-packs/synthetic/"
          + "010-openai-shared-candidate-terminal-graph.json";
  static final String PACK_RAW_SHA256 =
      "a569692018a902dc9a0e3b404e09696d41d1a771d8b24828fe2b12957ad09f1d";
  static final String ENVIRONMENT_PATH =
      "evals/environments/"
          + "openai-responses-pack010-offline-synthetic-v1.json";
  static final String ENVIRONMENT_RAW_SHA256 =
      "affe0b8b1a14bf6c7b2dce039067318871275f4db85eec182790ff8082c769f0";

  static final String GRAPH_PROTOCOL_VERSION =
      "postgres-graph-terminal-v1";
  static final String PRINCIPAL_ID = "synthetic-graph-eval-owner";
  static final String CAPTURE_ID = "capture-openai-worker-010";
  static final String CAPTURE_CLIENT_NONCE =
      "openai-worker-010-seed";
  static final String SOURCE_TYPE = "TEXT";
  static final String SOURCE_REF =
      "synthetic://eval/openai-shared-candidate-terminal-graph-010";
  static final String CONTENT =
      "虚构产品 Lumen Seed 会把公开测试素材整理成一张可追溯的创作卡片；本段不对应任何真实人物、账号或业务。";
  static final String INTENT =
      "将完全虚构的公开测试素材整理为一篇不超过 120 字、且引用 exact Capture 的中文短文。";
  static final String PARENT_ACTOR = "SCRIPTED_FAKE";
  static final String CHILD_ACTOR = "OPENAI_RESPONSES";
  static final String EXPERIMENT_ARM =
      "shared-candidate-verifier-pilot";
  static final String MODEL_REQUESTED = "gpt-5.6-terra";

  static final int GENERATION_REPETITIONS = 3;
  static final int MAXIMUM_PROVIDER_REQUESTS = 2;
  static final long MAXIMUM_INPUT_TOKENS_PER_REQUEST = 272_000;
  static final long MAXIMUM_OUTPUT_TOKENS_PER_REQUEST = 1_000;

  static final List<String> EXPECTED_RUN_IDS =
      List.of(
          "run-openai-worker-parent-010-r1",
          "run-openai-worker-child-010-r1",
          "run-openai-worker-parent-010-r2",
          "run-openai-worker-child-010-r2",
          "run-openai-worker-parent-010-r3",
          "run-openai-worker-child-010-r3");
  static final List<String> EXPECTED_TASK_IDS =
      List.of(
          "task-openai-worker-parent-010-r1",
          "task-openai-worker-child-010-r1",
          "task-openai-worker-parent-010-r2",
          "task-openai-worker-child-010-r2",
          "task-openai-worker-parent-010-r3",
          "task-openai-worker-child-010-r3");
  static final List<String> EXPECTED_WORKER_PROFILE_FINGERPRINTS =
      List.of(
          "1505d6e3236060e9833b2c15d187aae48ff6644c03693670908b99e60168e500",
          "9ff846a2e14000585b3fcaa9b47bd73ce28960eff1a67604d99549b519436d3d",
          "79a9bba05ac12e2438b1af5f938a26b9fd429e5b9de97c64343193ac4361adb7");
  static final List<String> EXPECTED_MANIFEST_HASHES =
      List.of(
          "bb0047b3c8b386896f45224f76940c7280342a4a2d0dae470ee0fa57bc364707",
          "d9aeea54ee84e8ff34dab0b5e21a8f355811261f259dc0d33dca69e1aec743c8",
          "733125cd9bacfdc34ec18c833ba1427fffa9581e53e1e25775a815109dfe01c0");
  static final List<String> EXPECTED_COMPILED_IDENTITIES =
      List.of(
          "257b7c4f7cfc4671a1390897c675f4a0f4f21628986250573290238c4a4a895e",
          "2f67a30dee90a8441a6e313a1e14fec2f3222bcd6d8ff3f41120e34b5eee04ab",
          "79c6fcad15f6ab9fdeba870e840a688585e9b12d3853ee4b07ca1bb652bfa909",
          "7920292ff1f3605c152719e7c771f9420a18b0110329c837e55c0cc90c026fa6",
          "c7e997b4c5173b0f2541a08d7a9dd3523810275bc1dd1a21c624c189ae00e210",
          "951d159ab2e9a88248da542b8380e69379e080a7d47627927bb0134f58164480",
          "eb8b8f9a9e7cd1818074ab2c50d00774d12a0e4cc5d0b7262de00819220ae76b",
          "1505d6e3236060e9833b2c15d187aae48ff6644c03693670908b99e60168e500",
          "ca9d5a481c32c2604642948ca2232f045d7cfb7b652a798cc2606f065c8f9ab7",
          "853c28be272e9469ad2bdb0461434faafcbe39e4052b70e48e144ebe94da2218",
          "bb0047b3c8b386896f45224f76940c7280342a4a2d0dae470ee0fa57bc364707",
          "6dc314a714f4c252eb4b3a54fb491db72859490e0893fdc89e144c89591a12ad",
          "6b7f2bac238a6d07f8fd09f59e30e8780235baeeb8a045de09340539b4d20a0a",
          "51856a605790598021d22557e62af0666989e7e2308a3c1f8523af903806e981",
          "4a4312927a1c340359cf6968ea791405aa087126464414b8b6ab7afbb06b3d75",
          "9ff846a2e14000585b3fcaa9b47bd73ce28960eff1a67604d99549b519436d3d",
          "d3efb3cb5a504b86336ff2e1f773b7ac992f98b53e02205a7a79ff836b8b69aa",
          "4c2807eaf1a95c3f324adca1b8a1d53254fbc9d6309cf8e7f3cdaaf9b2af42de",
          "d9aeea54ee84e8ff34dab0b5e21a8f355811261f259dc0d33dca69e1aec743c8",
          "6dc314a714f4c252eb4b3a54fb491db72859490e0893fdc89e144c89591a12ad",
          "95f133bf5915acb3bff12da68edcafc89e71c907704a94aafc457908f553a148",
          "5439c330634a4f22849e47ed498026490fae235751144e74c4c068d1aec3ff7c",
          "58c4b39e3c7dcc758086bc25deb86a2bb9c2fc336f964aa96305f714d83b08fb",
          "79a9bba05ac12e2438b1af5f938a26b9fd429e5b9de97c64343193ac4361adb7",
          "8146cb776f37d11221549bafc376d55278d0067691a6cb628fadd210d9b00c3b",
          "77f05ab782331af30f0f7330602cbbe29e0eaad99465447baa5d10ac4e14fdad",
          "733125cd9bacfdc34ec18c833ba1427fffa9581e53e1e25775a815109dfe01c0",
          "6dc314a714f4c252eb4b3a54fb491db72859490e0893fdc89e144c89591a12ad");

  private static final List<RepetitionSpec> REPETITIONS =
      List.of(
          new RepetitionSpec(
              1,
              "openai-shared-candidate-terminal-010-r1",
              "pack010-r1",
              Instant.parse("2026-08-01T08:00:00Z"),
              EXPECTED_RUN_IDS.get(0),
              EXPECTED_RUN_IDS.get(1),
              EXPECTED_TASK_IDS.get(0),
              EXPECTED_TASK_IDS.get(1),
              "art-openai-worker-010-r1"),
          new RepetitionSpec(
              2,
              "openai-shared-candidate-terminal-010-r2",
              "pack010-r2",
              Instant.parse("2026-08-01T08:05:00Z"),
              EXPECTED_RUN_IDS.get(2),
              EXPECTED_RUN_IDS.get(3),
              EXPECTED_TASK_IDS.get(2),
              EXPECTED_TASK_IDS.get(3),
              "art-openai-worker-010-r2"),
          new RepetitionSpec(
              3,
              "openai-shared-candidate-terminal-010-r3",
              "pack010-r3",
              Instant.parse("2026-08-01T08:10:00Z"),
              EXPECTED_RUN_IDS.get(4),
              EXPECTED_RUN_IDS.get(5),
              EXPECTED_TASK_IDS.get(4),
              EXPECTED_TASK_IDS.get(5),
              "art-openai-worker-010-r3"));

  private Pack010GraphEvalCatalog() {}

  static List<RepetitionSpec> repetitions() {
    return REPETITIONS;
  }

  static PricingProfile pricing() {
    return new PricingProfile(
        "openai-gpt-5.6-terra-pack010-v1",
        "openai.responses",
        MODEL_REQUESTED,
        1_000,
        1_000,
        1_000);
  }

  static List<ModelBoundReadOnlyWorkerExecutionProfile>
      workerProfiles() {
    return REPETITIONS.stream()
        .map(spec -> workerProfile(spec.repetition()))
        .toList();
  }

  static ModelBoundReadOnlyWorkerExecutionProfile workerProfile(
      int repetition) {
    requireRepetition(repetition);
    PricingProfile pricing = pricing();
    return ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
        pricing,
        OpenAiResponsesModel.PROTOCOL_VERSION,
        DeterministicReadOnlyWorkerConductorModel
            .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT,
        "environment://sha256:" + ENVIRONMENT_RAW_SHA256,
        new HarnessExperiment(EXPERIMENT_ARM, repetition),
        MAXIMUM_INPUT_TOKENS_PER_REQUEST,
        MAXIMUM_OUTPUT_TOKENS_PER_REQUEST,
        pricing.reserveCostUsd(
            MAXIMUM_PROVIDER_REQUESTS,
            MAXIMUM_INPUT_TOKENS_PER_REQUEST,
            MAXIMUM_OUTPUT_TOKENS_PER_REQUEST));
  }

  static AgentExecutionProfile parentProfile(int repetition) {
    return AgentExecutionProfile.readOnlyWorkerModelV1(
        workerProfile(repetition));
  }

  static CaptureCommand captureCommand() {
    return new CaptureCommand(
        PRINCIPAL_ID,
        CAPTURE_CLIENT_NONCE,
        CONTENT,
        SOURCE_TYPE,
        SOURCE_REF,
        DataClass.PUBLIC);
  }

  static Capture capture() {
    CaptureCommand command = captureCommand();
    return new Capture(
        CAPTURE_ID,
        PRINCIPAL_ID,
        CAPTURE_CLIENT_NONCE,
        command.requestHash(),
        CONTENT,
        command.sourceType(),
        SOURCE_REF,
        DataClass.PUBLIC,
        REPETITIONS.getFirst().startedAt());
  }

  static TaskEnvelope parentTask(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    return parentProfile(repetition)
        .newDraftTask(
            spec.parentTaskId(),
            PRINCIPAL_ID,
            INTENT,
            "capture://" + CAPTURE_ID,
            DataClass.PUBLIC);
  }

  static TaskEnvelope childTask(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    TaskEnvelope parent = parentTask(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile(repetition);
    return worker.newChildTask(
        parent,
        new WorkerHandoffRequest(
            worker.workerName(), INTENT, parent.inputRefs()),
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never()),
        spec.childTaskId());
  }

  static AgentRun parentRun(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    return AgentRun.running(
        spec.parentRunId(),
        PRINCIPAL_ID,
        parentTask(repetition),
        spec.startedAt());
  }

  static AgentRun childRun(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    return AgentRun.running(
        spec.childRunId(),
        PRINCIPAL_ID,
        childTask(repetition),
        spec.startedAt());
  }

  static GraphRunSelection parentSelection(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    AgentExecutionProfile parent = parentProfile(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile(repetition);
    return new GraphRunSelection(
        GraphRunRole.PARENT,
        spec.parentRunId(),
        spec.parentTaskId(),
        computedParentTaskHash(repetition),
        parent.id(),
        parent.fingerprint(),
        worker.registryVersion(),
        worker.id(),
        worker.fingerprint());
  }

  static GraphRunSelection childSelection(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile(repetition);
    return new GraphRunSelection(
        GraphRunRole.CHILD,
        spec.childRunId(),
        spec.childTaskId(),
        computedChildTaskHash(repetition),
        worker.id(),
        worker.fingerprint(),
        worker.registryVersion(),
        worker.id(),
        worker.fingerprint());
  }

  static List<GraphAttemptManifest> manifests() {
    return REPETITIONS.stream()
        .map(spec -> manifest(spec.repetition()))
        .toList();
  }

  static GraphAttemptManifest manifest(int repetition) {
    RepetitionSpec spec = requireRepetition(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile(repetition);
    return GraphAttemptManifest.create(
        GRAPH_PROTOCOL_VERSION,
        PRINCIPAL_ID,
        spec.executionSlotId(),
        spec.caseId(),
        PACK_RAW_SHA256,
        ENVIRONMENT_RAW_SHA256,
        CAPTURE_ID,
        computedCaptureRequestHash(),
        spec.artifactId(),
        spec.startedAt(),
        worker.pricing().fingerprint(),
        computedPromptSurfaceFingerprint(),
        DeterministicReadOnlyWorkerConductorModel
            .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT,
        worker.reservationUsd(),
        MAXIMUM_PROVIDER_REQUESTS,
        PARENT_ACTOR,
        CHILD_ACTOR,
        new HarnessExperiment(EXPERIMENT_ARM, repetition),
        parentSelection(repetition),
        childSelection(repetition));
  }

  static String computedCaptureRequestHash() {
    return captureCommand().requestHash();
  }

  static String computedParentTaskHash(int repetition) {
    return IntegrityHashes.taskHash(parentTask(repetition));
  }

  static String computedChildTaskHash(int repetition) {
    return IntegrityHashes.taskHash(childTask(repetition));
  }

  static String computedPromptSurfaceFingerprint() {
    return OpenAiResponsesModel.promptSurfaceFingerprint(
        MODEL_REQUESTED);
  }

  static String computedFirstRequestHash(int repetition) {
    return OpenAiResponsesModel.firstRequestFingerprint(
        workerProfile(repetition), childTask(repetition));
  }

  static List<String> computedCompiledIdentities() {
    List<String> identities = new ArrayList<>();
    identities.add(computedCaptureRequestHash());
    identities.add(pricing().fingerprint());
    identities.add(computedPromptSurfaceFingerprint());
    identities.add(
        DeterministicReadOnlyWorkerConductorModel
            .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT);
    for (RepetitionSpec spec : REPETITIONS) {
      int repetition = spec.repetition();
      identities.add(computedParentTaskHash(repetition));
      identities.add(computedChildTaskHash(repetition));
      identities.add(parentProfile(repetition).fingerprint());
      identities.add(workerProfile(repetition).fingerprint());
      identities.add(parentSelection(repetition).selectorHash());
      identities.add(childSelection(repetition).selectorHash());
      identities.add(manifest(repetition).manifestHash());
      identities.add(computedFirstRequestHash(repetition));
    }
    return List.copyOf(identities);
  }

  private static RepetitionSpec requireRepetition(int repetition) {
    if (repetition < 1 || repetition > GENERATION_REPETITIONS) {
      throw new IllegalArgumentException(
          "Pack010 repetition must be 1, 2, or 3");
    }
    return REPETITIONS.get(repetition - 1);
  }

  record RepetitionSpec(
      int repetition,
      String caseId,
      String executionSlotId,
      Instant startedAt,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId) {}
}
