package io.emergeos.evalrunner;

import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.agentloop.DeterministicReadOnlyWorkerConductorModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.CaptureCommand;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.time.Instant;

/** Compiled allowlist for the separate Pack008 child-only Model graph. */
final class Pack008WorkerEvalCatalog {

  static final String CASE_ID = "openai-public-read-worker-008-r1";
  static final String PACK_PATH =
      "evals/task-packs/synthetic/008-openai-read-only-worker-baseline.json";
  static final String PACK_RAW_SHA256 =
      "4803c227d88484dfe5796c286cb7b602242a54b452c39e5eb2be69f6a02bf1db";
  static final String ENVIRONMENT_PATH =
      "evals/environments/openai-responses-synthetic-v2.json";
  static final String ENVIRONMENT_RAW_SHA256 =
      SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256;

  static final String PRINCIPAL_ID = "synthetic-worker-eval-owner";
  static final String CAPTURE_ID = "capture-openai-worker-008";
  static final String CLIENT_NONCE = "openai-worker-008-r1";
  static final String PARENT_RUN_ID =
      "run-openai-worker-parent-008-r1";
  static final String CHILD_RUN_ID =
      "run-openai-worker-child-008-r1";
  static final String PARENT_TASK_ID =
      "task-openai-worker-parent-008-r1";
  static final String CHILD_TASK_ID =
      "task-openai-worker-child-008-r1";
  static final String ARTIFACT_ID = "art-openai-worker-008-r1";
  static final Instant STARTED_AT =
      Instant.parse("2026-07-31T03:30:00Z");
  static final String SOURCE_TYPE = "TEXT";
  static final String SOURCE_REF =
      "synthetic://eval/openai-read-only-worker-008";
  static final String CONTENT =
      "虚构产品 Aurora Seed 会把公开测试灵感整理成一张可追溯的创作卡片；本段不对应任何真实人物、账号或业务。";
  static final String INTENT =
      "将完全虚构的公开测试素材整理为一篇不超过 120 字的中文短文。";

  static final int MAXIMUM_PROVIDER_REQUESTS = 2;
  static final long MAXIMUM_INPUT_TOKENS_PER_REQUEST = 272_000;
  static final long MAXIMUM_OUTPUT_TOKENS_PER_REQUEST = 1_000;

  static final String EXPECTED_CAPTURE_REQUEST_HASH =
      "dd51522e6a5c2bfe2dd425bb3c6ad4a7e05bccce420e126040f14af01ce1ab00";
  static final String EXPECTED_PARENT_TASK_HASH =
      "35465c2631b9616195beb85d5280c7debc26f80603ad8cf89bb5a722e4517470";
  static final String EXPECTED_CHILD_TASK_HASH =
      "ed8988df7c4c1aaabee266720ef43aa0124116751ab1ae6eef773772c70a2d04";
  static final String EXPECTED_PRICING_FINGERPRINT =
      "97484a33d9374dfe67b6f6e22260c4ff82750b285d0be2aaed36c2fdcf6a1b50";
  static final String EXPECTED_PARENT_PROFILE_FINGERPRINT =
      "b624df93b848c46b28be679dabdf463d8bca9fb9cdbc703a79c9965bb49f685c";
  static final String EXPECTED_WORKER_PROFILE_FINGERPRINT =
      "82a9081712a91b20ccfa39779d8e77637373a154d8d9eb2e1246f423f3b9ca82";
  static final String EXPECTED_PROMPT_SURFACE_FINGERPRINT =
      "1a7a8b31dd2e8e5d4e598b086692dfb97558a6861464b8f122a5047c6a64a9f4";
  static final String EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT =
      "7920292ff1f3605c152719e7c771f9420a18b0110329c837e55c0cc90c026fa6";
  static final String EXPECTED_ATTEMPT_ID =
      "e3bfef65db2dbc1eeabf726e2162909ff52c92466ea14a4f9e65eb8270d17661";

  private Pack008WorkerEvalCatalog() {}

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
        new HarnessExperiment("openai-worker-h0", 1),
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

  static String computedAttemptId() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    AgentExecutionProfile parent = parentProfile();
    StringBuilder material =
        new StringBuilder(
            "emergeos.synthetic-model-worker-attempt.v3");
    append(material, "caseId", CASE_ID);
    append(material, "packRawSha256", PACK_RAW_SHA256);
    append(material, "principalId", PRINCIPAL_ID);
    append(material, "captureId", CAPTURE_ID);
    append(material, "captureNonce", CLIENT_NONCE);
    append(
        material,
        "captureRequestHash",
        computedCaptureRequestHash());
    append(material, "parentRunId", PARENT_RUN_ID);
    append(material, "childRunId", CHILD_RUN_ID);
    append(material, "parentTaskId", PARENT_TASK_ID);
    append(material, "childTaskId", CHILD_TASK_ID);
    append(material, "artifactId", ARTIFACT_ID);
    append(material, "startedAt", STARTED_AT.toString());
    append(material, "parentTaskHash", computedParentTaskHash());
    append(material, "childTaskHash", computedChildTaskHash());
    append(
        material, "environmentRawSha256", ENVIRONMENT_RAW_SHA256);
    append(
        material,
        "parentExecutionProfileFingerprint",
        parent.fingerprint());
    append(
        material, "workerProfileFingerprint", worker.fingerprint());
    append(
        material,
        "pricingProfileFingerprint",
        pricing().fingerprint());
    append(
        material,
        "promptSurfaceFingerprint",
        computedPromptSurfaceFingerprint());
    append(
        material,
        "conductorDecisionSurfaceFingerprint",
        DeterministicReadOnlyWorkerConductorModel
            .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT);
    append(
        material,
        "reservationUsd",
        worker.reservationUsd().toPlainString());
    append(
        material,
        "maximumProviderRequests",
        Integer.toString(MAXIMUM_PROVIDER_REQUESTS));
    append(material, "parentActor", "SCRIPTED_FAKE");
    append(material, "childActor", "OPENAI_RESPONSES");
    append(
        material,
        "parentModelBound",
        Boolean.toString(parent.modelBound()));
    append(material, "experimentArm", worker.experiment().arm());
    append(
        material,
        "experimentRepetition",
        Integer.toString(worker.experiment().repetition()));
    return ContentHashes.sha256(material.toString());
  }

  private static void append(
      StringBuilder target, String name, String value) {
    target
        .append('|')
        .append(name.length())
        .append(':')
        .append(name)
        .append('=')
        .append(value.length())
        .append(':')
        .append(value);
  }
}
