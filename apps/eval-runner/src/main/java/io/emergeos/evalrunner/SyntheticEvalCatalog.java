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
import java.math.BigDecimal;
import java.util.List;

final class SyntheticEvalCatalog {

  static final String CASE_ID = "openai-public-draft-003-r1";
  static final String PACK_PATH =
      "evals/task-packs/synthetic/003-openai-public-draft-smoke.json";
  static final String PACK_RAW_SHA256 =
      "bd44cc3ea0230b9267da5cfb6c29fdd2a7452131fe14864e16e6c39b516c8711";
  static final String ENVIRONMENT_PATH =
      "evals/environments/openai-responses-synthetic-v1.json";
  static final String ENVIRONMENT_RAW_SHA256 =
      "2a788ccc9b3e5be768f5707466b1b05aa340d79caf8ec728f2c6526919c7242b";

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
      "b55e73579f63736373337077dc2d928ccb8ed540b14193364800d3ec1d238a0a";
  static final String EXPECTED_PRICING_FINGERPRINT =
      "96be6f771a5c8d967424f61571af3737072a1c85ed780f9e7f0ab06ba1c7e28c";
  static final String EXPECTED_PROFILE_FINGERPRINT =
      "ff3bca47eeb5a6b43305d6d88f4c9ce4c9f9edaba78744f0435920d663db1a96";

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
        "agent-tools-v1",
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
}
