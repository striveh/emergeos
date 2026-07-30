package io.emergeos.evalrunner;

import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.CaptureCommand;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.ContentHashes;
import java.math.BigDecimal;
import java.util.List;

final class SyntheticEvalCatalog {

  static final String CASE_ID = "openai-public-draft-003-r1";
  static final String PACK_PATH =
      "evals/task-packs/synthetic/003-openai-public-draft-smoke.json";
  static final String PACK_RAW_SHA256 =
      "bd44cc3ea0230b9267da5cfb6c29fdd2a7452131fe14864e16e6c39b516c8711";
  static final String ENVIRONMENT_PATH =
      "evals/environments/openai-responses-synthetic-v2.json";
  static final String ENVIRONMENT_RAW_SHA256 =
      "440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968";

  static final String PRINCIPAL_ID = "synthetic-eval-owner";
  static final String CAPTURE_ID = "capture-openai-public-003";
  static final String CLIENT_NONCE = "openai-public-003-r1";
  static final String RUN_ID = "run-openai-public-003-r1";
  static final String TASK_ID = "task-openai-public-003-r1";
  static final String ARTIFACT_ID = "art-openai-public-003-r1";
  static final String SOURCE_TYPE = "TEXT";
  static final String SOURCE_REF =
      "synthetic://eval/openai-public-draft-003";
  static final String CONTENT =
      "虚构产品 Lumen Note 会把公开测试灵感整理成一张可追溯的创作卡片；本段不对应任何真实人物、账号或业务。";
  static final String INTENT =
      "将完全虚构的测试素材整理为一篇不超过 120 字的中文短文。";

  static final int MAXIMUM_PROVIDER_REQUESTS = 2;
  static final long MAXIMUM_INPUT_TOKENS_PER_REQUEST = 272_000;
  static final long MAXIMUM_OUTPUT_TOKENS_PER_REQUEST = 1_000;

  static final String EXPECTED_CAPTURE_REQUEST_HASH =
      "72e9f2a45fa1f2cf43cab0963cd0bd5cd16bedb5f8d40c0ae67401014b6f90ea";
  static final String EXPECTED_TASK_HASH =
      "842f24eba3bc3179d510959a1658876a1ee03a5286babd4552720ff172980a71";
  static final String EXPECTED_PRICING_FINGERPRINT =
      "96be6f771a5c8d967424f61571af3737072a1c85ed780f9e7f0ab06ba1c7e28c";
  static final String EXPECTED_PROFILE_FINGERPRINT =
      "bcf080220b5f6bb26446fa6aabae01800aff846b26b043e885f56af4e8412c6a";
  static final String EXPECTED_ATTEMPT_ID =
      "8a51691cfd4e5fdb46d441f225db65a390ffc637fbe0eca0c03e7aafc5156592";

  private SyntheticEvalCatalog() {}

  static PricingProfile pricing() {
    return new PricingProfile(
        "openai-gpt-5.4-mini-2026-03-17-standard-2026-07-30-v1",
        "openai.responses",
        "gpt-5.4-mini-2026-03-17",
        750,
        75,
        4_500);
  }

  static AgentExecutionProfile profile() {
    return new AgentExecutionProfile(
        "synthetic-openai-gpt-5.4-mini-draft-v1",
        "1.1",
        RiskLevel.EXTERNAL,
        2,
        1,
        30_000,
        new BigDecimal("0.417000"),
        MAXIMUM_INPUT_TOKENS_PER_REQUEST,
        MAXIMUM_OUTPUT_TOKENS_PER_REQUEST,
        pricing(),
        OpenAiResponsesModel.PROTOCOL_VERSION,
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        new HarnessExperiment("openai-responses-h0", 1),
        "synthetic-model-egress-policy-v1",
        "stage2-s3-eval",
        "ref-only-v1",
        "agent-tools-v2",
        "environment://sha256:" + ENVIRONMENT_RAW_SHA256,
        List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
        DataClass.PUBLIC);
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

  static TaskEnvelope task() {
    return profile()
        .newDraftTask(
            TASK_ID,
            PRINCIPAL_ID,
            INTENT,
            "capture://" + CAPTURE_ID,
            DataClass.PUBLIC);
  }

  static String computedCaptureRequestHash() {
    return captureCommand().requestHash();
  }

  static String computedTaskHash() {
    return IntegrityHashes.taskHash(task());
  }

  static String computedAttemptId() {
    String material =
        String.join(
            "\n",
            "emergeos.synthetic-model-attempt.v1",
            "caseId=" + CASE_ID,
            "packRawSha256=" + PACK_RAW_SHA256,
            "captureRequestHash=" + EXPECTED_CAPTURE_REQUEST_HASH,
            "taskHash=" + EXPECTED_TASK_HASH,
            "environmentRawSha256=" + ENVIRONMENT_RAW_SHA256,
            "executionProfileFingerprint=" + EXPECTED_PROFILE_FINGERPRINT,
            "pricingProfileFingerprint=" + EXPECTED_PRICING_FINGERPRINT,
            "reservationUsd=" + profile().reservationUsd().toPlainString(),
            "maximumProviderRequests=" + MAXIMUM_PROVIDER_REQUESTS,
            "experimentArm=" + profile().experiment().arm(),
            "experimentRepetition="
                + profile().experiment().repetition());
    return ContentHashes.sha256(material);
  }
}
