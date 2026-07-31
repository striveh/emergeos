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

/** Compiled allowlist for the Pack009 durable graph experiment. */
final class Pack009GraphEvalCatalog {

  static final String CASE_ID =
      "openai-public-read-worker-provider-crash-009-r1";
  static final String EXECUTION_SLOT_ID =
      "pack009-provider-accepted-crash-r1";
  static final String GRAPH_PROTOCOL_VERSION =
      "postgres-graph-attempt-v1";
  static final String PACK_PATH =
      "evals/task-packs/synthetic/"
          + "009-openai-read-only-worker-provider-accepted-crash.json";
  static final String PACK_RAW_SHA256 =
      "8af9e2a71f6479cdc612eef6c24924e3dff2c5b12419ba3d0a65f37894c221a8";
  static final String ENVIRONMENT_PATH =
      "evals/environments/openai-responses-graph-synthetic-v1.json";
  static final String ENVIRONMENT_RAW_SHA256 =
      "d6c3cb929a4aa5f9be75dc7f385f1c0afe589277e2622501f684ee8ee5df8899";

  static final String PRINCIPAL_ID = "synthetic-graph-eval-owner";
  static final String CAPTURE_ID = "capture-openai-worker-009";
  static final String CLIENT_NONCE = "openai-worker-009-r1";
  static final String PARENT_RUN_ID =
      "run-openai-worker-parent-009-r1";
  static final String CHILD_RUN_ID =
      "run-openai-worker-child-009-r1";
  static final String PARENT_TASK_ID =
      "task-openai-worker-parent-009-r1";
  static final String CHILD_TASK_ID =
      "task-openai-worker-child-009-r1";
  static final String ARTIFACT_ID = "art-openai-worker-009-r1";
  static final Instant STARTED_AT =
      Instant.parse("2026-07-31T06:00:00Z");
  static final String SOURCE_TYPE = "TEXT";
  static final String SOURCE_REF =
      "synthetic://eval/openai-read-only-worker-provider-crash-009";
  static final String CONTENT =
      "虚构产品 Lumen Seed 会把公开测试素材整理成一张可追溯的创作卡片；本段不对应任何真实人物、账号或业务。";
  static final String INTENT =
      "将完全虚构的公开测试素材整理为一篇不超过 120 字的中文短文。";

  static final int MAXIMUM_PROVIDER_REQUESTS = 2;
  static final long MAXIMUM_INPUT_TOKENS_PER_REQUEST = 272_000;
  static final long MAXIMUM_OUTPUT_TOKENS_PER_REQUEST = 1_000;
  static final String EXPERIMENT_ARM =
      "openai-worker-graph-h0";
  static final int EXPERIMENT_REPETITION = 1;

  static final String EXPECTED_CAPTURE_REQUEST_HASH =
      "4347bd28fa9788f9550ff27874b118a44b10cc718031d087098b0ad973ed3c08";
  static final String EXPECTED_PARENT_TASK_HASH =
      "425052ee7fb426e8e78ba0ebbead02045b3943d56b2a366f0bc899fda28a3241";
  static final String EXPECTED_CHILD_TASK_HASH =
      "f761724620938d6d4ccab71ae7473cf26ee569d91d4780a8bd8837444ee593ca";
  static final String EXPECTED_PRICING_FINGERPRINT =
      "97484a33d9374dfe67b6f6e22260c4ff82750b285d0be2aaed36c2fdcf6a1b50";
  static final String EXPECTED_PARENT_PROFILE_FINGERPRINT =
      "09b51d5a3a718715aaf46a0adc3eb2943ac4d0d83b08018a0991c91bf7cb5093";
  static final String EXPECTED_WORKER_PROFILE_FINGERPRINT =
      "228d74be6f1097d518df62027d2314bbc5dc4957e81e180dd661a6fe320251b9";
  static final String EXPECTED_PROMPT_SURFACE_FINGERPRINT =
      "1a7a8b31dd2e8e5d4e598b086692dfb97558a6861464b8f122a5047c6a64a9f4";
  static final String EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT =
      "7920292ff1f3605c152719e7c771f9420a18b0110329c837e55c0cc90c026fa6";
  static final String EXPECTED_PARENT_SELECTOR_HASH =
      "790dddc6cc8e8b171235238693c2d55f7d60a5b995e6189648fdcf4fe8a5257b";
  static final String EXPECTED_CHILD_SELECTOR_HASH =
      "63cf801a017cf39cc20394a1af43e72d6d77142e5f0780aeb8f0b0027ae31bba";
  static final String EXPECTED_MANIFEST_HASH =
      "68cbc47a23c50b02771881d68d2c585e0662af9fe586c588003d9355fe75287f";
  static final String EXPECTED_FIRST_REQUEST_HASH =
      "3b1a11c3bcac1ef123401e82e71bea71d7c0f796e6a8c3d8b9634c016e74b6db";

  private Pack009GraphEvalCatalog() {}

  static PricingProfile pricing() {
    return new PricingProfile(
        "openai-gpt-5.4-mini-worker-2026-07-31-v1",
        "openai.responses",
        "gpt-5.4-mini-2026-03-17",
        750,
        75,
        4_500);
  }

  static ModelBoundReadOnlyWorkerExecutionProfile workerProfile() {
    return ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
        pricing(),
        OpenAiResponsesModel.PROTOCOL_VERSION,
        DeterministicReadOnlyWorkerConductorModel
            .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT,
        "environment://sha256:" + ENVIRONMENT_RAW_SHA256,
        new HarnessExperiment(
            EXPERIMENT_ARM, EXPERIMENT_REPETITION),
        MAXIMUM_INPUT_TOKENS_PER_REQUEST,
        MAXIMUM_OUTPUT_TOKENS_PER_REQUEST,
        new BigDecimal("0.417000"));
  }

  static AgentExecutionProfile parentProfile() {
    return AgentExecutionProfile.readOnlyWorkerModelV1(
        workerProfile());
  }

  static CaptureCommand captureCommand() {
    return new CaptureCommand(
        PRINCIPAL_ID,
        CLIENT_NONCE,
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
        CLIENT_NONCE,
        command.requestHash(),
        CONTENT,
        command.sourceType(),
        SOURCE_REF,
        DataClass.PUBLIC,
        STARTED_AT);
  }

  static TaskEnvelope parentTask() {
    return parentProfile()
        .newDraftTask(
            PARENT_TASK_ID,
            PRINCIPAL_ID,
            INTENT,
            "capture://" + CAPTURE_ID,
            DataClass.PUBLIC);
  }

  static TaskEnvelope childTask() {
    TaskEnvelope parent = parentTask();
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    return worker.newChildTask(
        parent,
        new WorkerHandoffRequest(
            worker.workerName(), INTENT, parent.inputRefs()),
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never()),
        CHILD_TASK_ID);
  }

  static AgentRun parentRun() {
    return AgentRun.running(
        PARENT_RUN_ID, PRINCIPAL_ID, parentTask(), STARTED_AT);
  }

  static AgentRun childRun() {
    return AgentRun.running(
        CHILD_RUN_ID, PRINCIPAL_ID, childTask(), STARTED_AT);
  }

  static GraphRunSelection parentSelection() {
    AgentExecutionProfile parent = parentProfile();
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    return new GraphRunSelection(
        GraphRunRole.PARENT,
        PARENT_RUN_ID,
        PARENT_TASK_ID,
        computedParentTaskHash(),
        parent.id(),
        parent.fingerprint(),
        worker.registryVersion(),
        worker.id(),
        worker.fingerprint());
  }

  static GraphRunSelection childSelection() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    return new GraphRunSelection(
        GraphRunRole.CHILD,
        CHILD_RUN_ID,
        CHILD_TASK_ID,
        computedChildTaskHash(),
        worker.id(),
        worker.fingerprint(),
        worker.registryVersion(),
        worker.id(),
        worker.fingerprint());
  }

  static GraphAttemptManifest manifest() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    return GraphAttemptManifest.create(
        GRAPH_PROTOCOL_VERSION,
        PRINCIPAL_ID,
        EXECUTION_SLOT_ID,
        CASE_ID,
        PACK_RAW_SHA256,
        ENVIRONMENT_RAW_SHA256,
        CAPTURE_ID,
        EXPECTED_CAPTURE_REQUEST_HASH,
        ARTIFACT_ID,
        STARTED_AT,
        EXPECTED_PRICING_FINGERPRINT,
        EXPECTED_PROMPT_SURFACE_FINGERPRINT,
        EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT,
        worker.reservationUsd(),
        MAXIMUM_PROVIDER_REQUESTS,
        "SCRIPTED_FAKE",
        "OPENAI_RESPONSES",
        new HarnessExperiment(
            EXPERIMENT_ARM, EXPERIMENT_REPETITION),
        parentSelection(),
        childSelection());
  }

  static String computedCaptureRequestHash() {
    return captureCommand().requestHash();
  }

  static String computedParentTaskHash() {
    return IntegrityHashes.taskHash(parentTask());
  }

  static String computedChildTaskHash() {
    return IntegrityHashes.taskHash(childTask());
  }

  static String computedPromptSurfaceFingerprint() {
    return OpenAiResponsesModel.promptSurfaceFingerprint(
        workerProfile().modelRequested());
  }

  static String computedFirstRequestHash() {
    return OpenAiResponsesModel.firstRequestFingerprint(
        workerProfile(), childTask());
  }
}
