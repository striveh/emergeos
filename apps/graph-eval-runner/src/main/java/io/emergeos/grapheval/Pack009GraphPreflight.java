package io.emergeos.grapheval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import io.emergeos.adapters.agentloop.DeterministicReadOnlyWorkerConductorModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Zero-effect Pack009 preflight.
 *
 * <p>Only the two bounded, hash-frozen repository assets and compiled
 * Tasks/profiles are read. This path has no DataSource, credential reader,
 * client/model factory, Run store, graph store, socket or execution
 * dependency.
 */
final class Pack009GraphPreflight {

  private static final long MAX_ASSET_BYTES = 128 * 1024;
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final Path repoRoot;

  Pack009GraphPreflight(Path repoRoot) {
    this.repoRoot =
        Objects.requireNonNull(repoRoot, "repoRoot")
            .toAbsolutePath()
            .normalize();
  }

  Result run() {
    if (Runtime.version().feature() != 21) {
      throw rejected("JAVA_RELEASE_MISMATCH");
    }
    byte[] packBytes =
        readBoundedAsset(
            Pack009GraphEvalCatalog.PACK_PATH,
            Pack009GraphEvalCatalog.PACK_RAW_SHA256);
    byte[] environmentBytes =
        readBoundedAsset(
            Pack009GraphEvalCatalog.ENVIRONMENT_PATH,
            Pack009GraphEvalCatalog.ENVIRONMENT_RAW_SHA256);
    verifyPackDocument(packBytes);
    verifyEnvironmentDocument(environmentBytes);
    verifyCompiledBindings();
    return new Result();
  }

  static void verifyPackDocument(byte[] bytes) {
    verifyPack(parse(bytes, TaskPack.class, "PACK009_INVALID"));
  }

  static void verifyEnvironmentDocument(byte[] bytes) {
    verifyEnvironment(
        parse(
            bytes,
            EnvironmentManifest.class,
            "ENVIRONMENT_INVALID"));
  }

  private byte[] readBoundedAsset(
      String relative, String expectedSha256) {
    try {
      if (Files.isSymbolicLink(repoRoot)
          || !Files.isDirectory(
              repoRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw rejected("REPO_ROOT_INVALID");
      }
      Path relativePath = Path.of(relative);
      if (relativePath.isAbsolute()) {
        throw rejected("ASSET_PATH_INVALID");
      }
      Path asset = repoRoot.resolve(relativePath).normalize();
      if (!asset.startsWith(repoRoot)) {
        throw rejected("ASSET_PATH_INVALID");
      }
      Path current = repoRoot;
      for (Path segment : relativePath) {
        current = current.resolve(segment);
        if (Files.isSymbolicLink(current)) {
          throw rejected("ASSET_SYMLINK_REJECTED");
        }
      }
      BasicFileAttributes attributes =
          Files.readAttributes(
              asset,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()
          || attributes.size() < 1
          || attributes.size() > MAX_ASSET_BYTES) {
        throw rejected("ASSET_SIZE_INVALID");
      }
      byte[] bytes = Files.readAllBytes(asset);
      if (bytes.length != attributes.size()
          || !expectedSha256.equals(sha256(bytes))) {
        throw rejected("ASSET_HASH_MISMATCH");
      }
      return bytes;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("ASSET_READ_FAILED");
    }
  }

  private static <T> T parse(
      byte[] bytes, Class<T> type, String failureCode) {
    try {
      return STRICT_JSON.readValue(bytes, type);
    } catch (IOException | RuntimeException failure) {
      throw rejected(failureCode);
    }
  }

  private static void verifyPack(TaskPack pack) {
    ModelWorkerEval model = pack.modelWorkerEval();
    Provenance provenance = model == null ? null : model.provenance();
    if (!"0.8".equals(pack.schemaVersion())
        || !"synthetic-openai-read-only-worker-provider-accepted-crash-009"
            .equals(pack.taskId())
        || !Pack009GraphEvalCatalog.PRINCIPAL_ID.equals(
            pack.principalRef())
        || pack.seed() == null
        || !Pack009GraphEvalCatalog.SOURCE_TYPE.equals(
            pack.seed().sourceType())
        || !Pack009GraphEvalCatalog.SOURCE_REF.equals(
            pack.seed().sourceRef())
        || pack.seed().dataClass() != DataClass.PUBLIC
        || !Pack009GraphEvalCatalog.CONTENT.equals(
            pack.seed().content())
        || pack.risk() != RiskLevel.EXTERNAL
        || !"ARTICLE_DRAFT".equals(pack.expectedArtifactKind())
        || model == null
        || !Pack009GraphEvalCatalog.CASE_ID.equals(model.caseId())
        || !Pack009GraphEvalCatalog.EXECUTION_SLOT_ID.equals(
            model.executionSlotId())
        || !Pack009GraphEvalCatalog.GRAPH_PROTOCOL_VERSION.equals(
            model.graphProtocolVersion())
        || !Pack009GraphEvalCatalog.STARTED_AT.equals(
            model.startedAt())
        || !Pack009GraphEvalCatalog.CAPTURE_ID.equals(
            model.captureId())
        || !Pack009GraphEvalCatalog.CLIENT_NONCE.equals(
            model.clientNonce())
        || !Pack009GraphEvalCatalog.PARENT_RUN_ID.equals(
            model.parentRunId())
        || !Pack009GraphEvalCatalog.CHILD_RUN_ID.equals(
            model.childRunId())
        || !Pack009GraphEvalCatalog.PARENT_TASK_ID.equals(
            model.parentTaskId())
        || !Pack009GraphEvalCatalog.CHILD_TASK_ID.equals(
            model.childTaskId())
        || !Pack009GraphEvalCatalog.ARTIFACT_ID.equals(
            model.artifactId())
        || !Pack009GraphEvalCatalog.INTENT.equals(model.intent())
        || !"SCRIPTED_FAKE".equals(model.parentActor())
        || !"OPENAI_RESPONSES".equals(model.childActor())
        || !Pack009GraphEvalCatalog.workerProfile()
            .workerName()
            .equals(model.workerName())
        || !List.of("capture.read").equals(model.requiredTools())
        || model.maximumProviderRequests()
            != Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || provenance == null
        || !"LITERAL_CHECKED_IN_SYNTHETIC".equals(provenance.kind())
        || provenance.containsRealUserData()
        || provenance.containsRealAccount()
        || !provenance.networkAllowed()
        || !provenance.realModelAllowed()
        || provenance.connectorAllowed()) {
      throw rejected("PACK009_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyEnvironment(
      EnvironmentManifest environment) {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack009GraphEvalCatalog.workerProfile();
    PricingProfile pricing = worker.pricing();
    OperatorGate gate = environment.operatorGate();
    if (!"0.1".equals(environment.schemaVersion())
        || !"2026-07-31".equals(environment.reviewedAt())
        || environment.javaRelease() != 21
        || !"4.43.0".equals(environment.openaiJavaVersion())
        || !OpenAiResponsesModel.PROTOCOL_VERSION.equals(
            environment.protocolVersion())
        || !worker.harnessVersion().equals(
            environment.harnessVersion())
        || !worker.childToolRegistryVersion().equals(
            environment.toolRegistryVersion())
        || !List.of("capture.read").equals(environment.tools())
        || !"responses".equals(environment.providerApi())
        || environment.model() == null
        || !worker.modelRequested().equals(
            environment.model().requested())
        || !"gpt-5.4-mini".equals(
            environment.model().pricingFamily())
        || environment.model().maxInputTokens()
            != worker.maxInputTokensPerStep()
        || environment.model().maxOutputTokens() != 128_000
        || !"https://developers.openai.com/api/docs/models/gpt-5.4-mini"
            .equals(environment.model().reference())
        || environment.requestPolicy() == null
        || environment.requestPolicy().store()
        || environment.requestPolicy().parallelToolCalls()
        || !"default".equals(
            environment.requestPolicy().serviceTier())
        || environment.requestPolicy().maxRetries() != 0
        || !"https://api.openai.com/v1".equals(
            environment.requestPolicy().productionBaseUrl())
        || environment.requestPolicy()
            .productionBaseUrlOverrideAllowed()
        || environment.requestPolicy().ambientProxyAllowed()
        || !"OFF".equals(
            environment.requestPolicy().sdkLogLevel())
        || environment.requestPolicy().maximumProviderRequests()
            != Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || environment.requestPolicy()
                .maximumInputTokensPerRequest()
            != worker.maxInputTokensPerStep()
        || environment.requestPolicy()
                .maximumOutputTokensPerRequest()
            != worker.maxOutputTokensPerStep()
        || environment.pricing() == null
        || !"USD".equals(environment.pricing().currency())
        || !"NANO_USD_PER_TOKEN".equals(
            environment.pricing().unit())
        || environment.pricing().uncachedInput()
            != pricing.uncachedInputNanoUsdPerToken()
        || environment.pricing().cachedInput()
            != pricing.cachedInputNanoUsdPerToken()
        || environment.pricing().output()
            != pricing.outputNanoUsdPerToken()
        || worker.reservationUsd().compareTo(
                decimal(environment.pricing().fullRunReservationUsd()))
            != 0
        || !"https://developers.openai.com/api/docs/pricing"
            .equals(environment.pricing().reference())
        || environment.inputTokenUpperBound() == null
        || !"OFFICIAL_MODEL_MAX_INPUT".equals(
            environment.inputTokenUpperBound().method())
        || environment.inputTokenUpperBound().tokens()
            != worker.maxInputTokensPerStep()
        || !"https://developers.openai.com/api/docs/models/gpt-5.4-mini"
            .equals(environment.inputTokenUpperBound().reference())
        || environment.promptCachePolicy() == null
        || !"PROVIDER_DEFAULT_PRE_GPT_5_6".equals(
            environment.promptCachePolicy().mode())
        || environment.promptCachePolicy().cacheWriteFeeIncluded()
        || !"https://developers.openai.com/api/docs/guides/prompt-caching"
            .equals(environment.promptCachePolicy().reference())
        || gate == null
        || !gate.postgresqlCanonicalGraphAttemptRequired()
        || !gate.stableExecutionSlotRequired()
        || !gate.realTtyChallengeRequired()
        || !gate.credentialReadAfterDurableChildConsume()
        || !gate.durableGraphJournalRequired()
        || !gate.terminalGraphSealRequired()
        || gate.shippingExecuteRouteEnabled()) {
      throw rejected("ENVIRONMENT_SEMANTICS_MISMATCH");
    }
  }

  private static BigDecimal decimal(String value) {
    try {
      return new BigDecimal(value);
    } catch (RuntimeException invalid) {
      throw rejected("ENVIRONMENT_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyCompiledBindings() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack009GraphEvalCatalog.workerProfile();
    AgentExecutionProfile parent =
        Pack009GraphEvalCatalog.parentProfile();
    TaskEnvelope parentTask = Pack009GraphEvalCatalog.parentTask();
    TaskEnvelope childTask = Pack009GraphEvalCatalog.childTask();
    if (!Pack009GraphEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH.equals(
            Pack009GraphEvalCatalog.computedCaptureRequestHash())
        || !Pack009GraphEvalCatalog.EXPECTED_PARENT_TASK_HASH.equals(
            Pack009GraphEvalCatalog.computedParentTaskHash())
        || !Pack009GraphEvalCatalog.EXPECTED_CHILD_TASK_HASH.equals(
            Pack009GraphEvalCatalog.computedChildTaskHash())
        || !Pack009GraphEvalCatalog.EXPECTED_PRICING_FINGERPRINT.equals(
            worker.pricing().fingerprint())
        || !Pack009GraphEvalCatalog
            .EXPECTED_PARENT_PROFILE_FINGERPRINT
            .equals(parent.fingerprint())
        || !Pack009GraphEvalCatalog
            .EXPECTED_WORKER_PROFILE_FINGERPRINT
            .equals(worker.fingerprint())
        || !Pack009GraphEvalCatalog
            .EXPECTED_PROMPT_SURFACE_FINGERPRINT
            .equals(
                Pack009GraphEvalCatalog
                    .computedPromptSurfaceFingerprint())
        || !Pack009GraphEvalCatalog
            .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT
            .equals(
                DeterministicReadOnlyWorkerConductorModel
                    .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT)
        || !Pack009GraphEvalCatalog
            .EXPECTED_PARENT_SELECTOR_HASH
            .equals(
                Pack009GraphEvalCatalog
                    .parentSelection()
                    .selectorHash())
        || !Pack009GraphEvalCatalog
            .EXPECTED_CHILD_SELECTOR_HASH
            .equals(
                Pack009GraphEvalCatalog
                    .childSelection()
                    .selectorHash())
        || !Pack009GraphEvalCatalog.EXPECTED_MANIFEST_HASH.equals(
            Pack009GraphEvalCatalog.manifest().manifestHash())
        || !Pack009GraphEvalCatalog
            .EXPECTED_FIRST_REQUEST_HASH
            .equals(
                Pack009GraphEvalCatalog
                    .computedFirstRequestHash())
        || parent.modelBound()
        || !parent.requiredDataClass().equals(DataClass.PUBLIC)
        || !parentTask.requiredTools().isEmpty()
        || !"agent-tools-none-v1".equals(
            parentTask.toolRegistryVersion())
        || !childTask.unresolvedDecisions().isEmpty()
        || !List.of("capture.read").equals(childTask.requiredTools())
        || worker.maxModelSteps()
            != Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || childTask.maxModelSteps()
            != Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || !worker.modelProvider().equals(
            childTask.modelProvider())
        || !worker.modelRequested().equals(
            childTask.modelRequested())
        || !worker.pricingProfile().equals(
            childTask.pricingProfile())
        || worker.budgetUsd().compareTo(
                worker.reservationUsd())
            != 0
        || parent.budgetUsd().compareTo(
                worker.reservationUsd())
            != 0
        || !("environment://sha256:"
                + Pack009GraphEvalCatalog.ENVIRONMENT_RAW_SHA256)
            .equals(worker.environmentSnapshotRef())
        || !worker.environmentSnapshotRef().equals(
            parent.environmentSnapshotRef())) {
      throw rejected("PACK009_COMPILED_BINDING_MISMATCH");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 unavailable", impossible);
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  record Result() {

    String receipt() {
      ModelBoundReadOnlyWorkerExecutionProfile worker =
          Pack009GraphEvalCatalog.workerProfile();
      return "PACK009_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " caseId="
          + Pack009GraphEvalCatalog.CASE_ID
          + " executionSlotId="
          + Pack009GraphEvalCatalog.EXECUTION_SLOT_ID
          + " graphProtocolVersion="
          + Pack009GraphEvalCatalog.GRAPH_PROTOCOL_VERSION
          + " packSha256="
          + Pack009GraphEvalCatalog.PACK_RAW_SHA256
          + " environmentSha256="
          + Pack009GraphEvalCatalog.ENVIRONMENT_RAW_SHA256
          + " captureRequestHash="
          + Pack009GraphEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH
          + " parentTaskHash="
          + Pack009GraphEvalCatalog.EXPECTED_PARENT_TASK_HASH
          + " childTaskHash="
          + Pack009GraphEvalCatalog.EXPECTED_CHILD_TASK_HASH
          + " parentExecutionProfileFingerprint="
          + Pack009GraphEvalCatalog
              .EXPECTED_PARENT_PROFILE_FINGERPRINT
          + " workerProfileFingerprint="
          + Pack009GraphEvalCatalog
              .EXPECTED_WORKER_PROFILE_FINGERPRINT
          + " pricingProfileFingerprint="
          + Pack009GraphEvalCatalog.EXPECTED_PRICING_FINGERPRINT
          + " promptSurfaceFingerprint="
          + Pack009GraphEvalCatalog
              .EXPECTED_PROMPT_SURFACE_FINGERPRINT
          + " conductorDecisionSurfaceFingerprint="
          + Pack009GraphEvalCatalog
              .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT
          + " parentSelectorHash="
          + Pack009GraphEvalCatalog.EXPECTED_PARENT_SELECTOR_HASH
          + " childSelectorHash="
          + Pack009GraphEvalCatalog.EXPECTED_CHILD_SELECTOR_HASH
          + " attemptId="
          + Pack009GraphEvalCatalog.EXPECTED_MANIFEST_HASH
          + " firstRequestHash="
          + Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH
          + " modelRequested="
          + worker.modelRequested()
          + " maximumProviderRequests="
          + Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          + " reservationUsd="
          + worker.reservationUsd().toPlainString()
          + " dataSources=0 keyReads=0 clientFactories=0"
          + " modelFactories=0 runStarts=0 httpRequests=0"
          + " graphAttemptCreated=false shippingExecute=false";
    }
  }

  static final class Rejected extends RuntimeException {

    private final String code;

    Rejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private record TaskPack(
      String schemaVersion,
      String taskId,
      String title,
      String principalRef,
      Seed seed,
      List<String> requiredConstraints,
      List<String> forbiddenActions,
      List<String> acceptanceChecks,
      List<String> humanReviewQuestions,
      RiskLevel risk,
      String expectedArtifactKind,
      List<String> faultPlan,
      ModelWorkerEval modelWorkerEval) {}

  private record Seed(
      String sourceType,
      String sourceRef,
      DataClass dataClass,
      String content) {}

  private record ModelWorkerEval(
      String caseId,
      String executionSlotId,
      String graphProtocolVersion,
      java.time.Instant startedAt,
      String captureId,
      String clientNonce,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId,
      String intent,
      String parentActor,
      String childActor,
      String workerName,
      List<String> requiredTools,
      int maximumProviderRequests,
      Provenance provenance) {}

  private record Provenance(
      String kind,
      boolean containsRealUserData,
      boolean containsRealAccount,
      boolean networkAllowed,
      boolean realModelAllowed,
      boolean connectorAllowed) {}

  private record EnvironmentManifest(
      String schemaVersion,
      String reviewedAt,
      int javaRelease,
      String openaiJavaVersion,
      String protocolVersion,
      String harnessVersion,
      String toolRegistryVersion,
      List<String> tools,
      String providerApi,
      Model model,
      RequestPolicy requestPolicy,
      Pricing pricing,
      InputTokenUpperBound inputTokenUpperBound,
      PromptCachePolicy promptCachePolicy,
      OperatorGate operatorGate) {}

  private record Model(
      String requested,
      String pricingFamily,
      long maxInputTokens,
      long maxOutputTokens,
      String reference) {}

  private record RequestPolicy(
      boolean store,
      boolean parallelToolCalls,
      String serviceTier,
      int maxRetries,
      String productionBaseUrl,
      boolean productionBaseUrlOverrideAllowed,
      boolean ambientProxyAllowed,
      String sdkLogLevel,
      int maximumProviderRequests,
      long maximumInputTokensPerRequest,
      long maximumOutputTokensPerRequest) {}

  private record Pricing(
      String currency,
      String unit,
      long uncachedInput,
      long cachedInput,
      long output,
      String fullRunReservationUsd,
      String reference) {}

  private record InputTokenUpperBound(
      String method, long tokens, String reference) {}

  private record PromptCachePolicy(
      String mode,
      boolean cacheWriteFeeIncluded,
      String reference) {}

  private record OperatorGate(
      boolean postgresqlCanonicalGraphAttemptRequired,
      boolean stableExecutionSlotRequired,
      boolean realTtyChallengeRequired,
      boolean credentialReadAfterDurableChildConsume,
      boolean durableGraphJournalRequired,
      boolean terminalGraphSealRequired,
      boolean shippingExecuteRouteEnabled) {}
}
