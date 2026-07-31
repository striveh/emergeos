package io.emergeos.evalrunner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelExecutionProfile;
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

final class SyntheticEvalPreflight {

  private static final long MAX_ASSET_BYTES = 128 * 1024;
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final Path repoRoot;

  SyntheticEvalPreflight(Path repoRoot) {
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
            SyntheticEvalCatalog.PACK_PATH,
            SyntheticEvalCatalog.PACK_RAW_SHA256);
    byte[] environmentBytes =
        readBoundedAsset(
            SyntheticEvalCatalog.ENVIRONMENT_PATH,
            SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256);

    verifyPackDocument(packBytes);
    verifyEnvironmentDocument(environmentBytes);
    verifyCompiledBindings();
    return new Result();
  }

  static void verifyPackDocument(byte[] bytes) {
    verifyPack(parse(bytes, TaskPack.class, "PACK_INVALID"));
  }

  static void verifyEnvironmentDocument(byte[] bytes) {
    AgentExecutionProfile profile = SyntheticEvalCatalog.profile();
    verifyEnvironmentDocument(
        bytes,
        profile,
        profile.harnessVersion(),
        profile.toolRegistryVersion(),
        List.of("capture.read"),
        SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS);
  }

  static void verifyEnvironmentDocument(
      byte[] bytes,
      ModelExecutionProfile profile,
      String harnessVersion,
      String toolRegistryVersion,
      List<String> tools,
      int maximumProviderRequests) {
    verifyEnvironment(
        parse(
            bytes,
            EnvironmentManifest.class,
            "ENVIRONMENT_INVALID"),
        Objects.requireNonNull(profile, "profile"),
        Objects.requireNonNull(harnessVersion, "harnessVersion"),
        Objects.requireNonNull(
            toolRegistryVersion, "toolRegistryVersion"),
        List.copyOf(Objects.requireNonNull(tools, "tools")),
        maximumProviderRequests);
  }

  private byte[] readBoundedAsset(
      String relative, String expectedSha256) {
    try {
      if (Files.isSymbolicLink(repoRoot)
          || !Files.isDirectory(repoRoot, LinkOption.NOFOLLOW_LINKS)) {
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
    if (!"0.2".equals(pack.schemaVersion())
        || !"synthetic-openai-public-draft-003".equals(pack.taskId())
        || !SyntheticEvalCatalog.PRINCIPAL_ID.equals(pack.principalRef())
        || pack.seed() == null
        || !SyntheticEvalCatalog.SOURCE_TYPE.equals(pack.seed().sourceType())
        || !SyntheticEvalCatalog.SOURCE_REF.equals(pack.seed().sourceRef())
        || pack.seed().dataClass() != DataClass.PUBLIC
        || !SyntheticEvalCatalog.CONTENT.equals(pack.seed().content())
        || pack.risk() != RiskLevel.EXTERNAL
        || !"ARTICLE_DRAFT".equals(pack.expectedArtifactKind())
        || pack.syntheticProvenance() == null
        || !"LITERAL_CHECKED_IN_SYNTHETIC"
            .equals(pack.syntheticProvenance().kind())
        || pack.syntheticProvenance().containsRealUserData()
        || pack.syntheticProvenance().containsRealAccount()
        || pack.modelEval() == null
        || !SyntheticEvalCatalog.CASE_ID.equals(pack.modelEval().caseId())
        || !SyntheticEvalCatalog.CAPTURE_ID.equals(pack.modelEval().captureId())
        || !SyntheticEvalCatalog.CLIENT_NONCE.equals(
            pack.modelEval().clientNonce())
        || !SyntheticEvalCatalog.RUN_ID.equals(pack.modelEval().runId())
        || !SyntheticEvalCatalog.TASK_ID.equals(pack.modelEval().taskId())
        || !SyntheticEvalCatalog.ARTIFACT_ID.equals(
            pack.modelEval().artifactId())
        || !SyntheticEvalCatalog.INTENT.equals(pack.modelEval().intent())
        || !List.of("capture.read").equals(pack.modelEval().requiredTools())
        || pack.modelEval().maximumProviderRequests()
            != SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
      throw rejected("PACK_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyEnvironment(
      EnvironmentManifest environment,
      ModelExecutionProfile profile,
      String harnessVersion,
      String toolRegistryVersion,
      List<String> tools,
      int maximumProviderRequests) {
    PricingProfile pricing = profile.pricing();
    if (!"0.1".equals(environment.schemaVersion())
        || !"2026-07-30".equals(environment.reviewedAt())
        || environment.javaRelease() != 21
        || !"4.43.0".equals(environment.openaiJavaVersion())
        || !OpenAiResponsesModel.PROTOCOL_VERSION.equals(
            environment.protocolVersion())
        || !harnessVersion.equals(environment.harnessVersion())
        || !toolRegistryVersion.equals(
            environment.toolRegistryVersion())
        || !tools.equals(environment.tools())
        || !"responses".equals(environment.providerApi())
        || environment.model() == null
        || !pricing.modelRequested().equals(environment.model().requested())
        || !"gpt-5.4-mini".equals(environment.model().pricingFamily())
        || environment.model().maxInputTokens()
            != profile.maxInputTokensPerStep()
        || environment.model().maxOutputTokens() != 128_000
        || environment.requestPolicy() == null
        || environment.requestPolicy().store()
        || environment.requestPolicy().parallelToolCalls()
        || !"default".equals(environment.requestPolicy().serviceTier())
        || environment.requestPolicy().maxRetries() != 0
        || !ProductionOpenAiClientFactory.PRODUCTION_BASE_URL.equals(
            environment.requestPolicy().productionBaseUrl())
        || environment.requestPolicy().productionBaseUrlOverrideAllowed()
        || environment.requestPolicy().ambientProxyAllowed()
        || !"OFF".equals(environment.requestPolicy().sdkLogLevel())
        || environment.requestPolicy().maximumProviderRequests()
            != maximumProviderRequests
        || environment.requestPolicy().maximumInputTokensPerRequest()
            != profile.maxInputTokensPerStep()
        || environment.requestPolicy().maximumOutputTokensPerRequest()
            != profile.maxOutputTokensPerStep()
        || environment.pricing() == null
        || !"USD".equals(environment.pricing().currency())
        || !"NANO_USD_PER_TOKEN".equals(environment.pricing().unit())
        || environment.pricing().uncachedInput()
            != pricing.uncachedInputNanoUsdPerToken()
        || environment.pricing().cachedInput()
            != pricing.cachedInputNanoUsdPerToken()
        || environment.pricing().output()
            != pricing.outputNanoUsdPerToken()
        || !profile
            .reservationUsd()
            .equals(
                new BigDecimal(
                    environment.pricing().fullRunReservationUsd()))
        || environment.inputTokenUpperBound() == null
        || !"OFFICIAL_MODEL_MAX_INPUT"
            .equals(environment.inputTokenUpperBound().method())
        || environment.inputTokenUpperBound().tokens()
            != profile.maxInputTokensPerStep()
        || environment.promptCachePolicy() == null
        || !"PROVIDER_DEFAULT_PRE_GPT_5_6"
            .equals(environment.promptCachePolicy().mode())
        || environment.promptCachePolicy().cacheWriteFeeIncluded()
        || environment.operatorGate() == null
        || !environment.operatorGate().posixOneShotMarkerRequired()
        || !environment.operatorGate().realTtyChallengeRequired()
        || !environment.operatorGate().credentialReadAfterPermit()
        || !environment.operatorGate().durableRunRecordRequired()
        || !environment.operatorGate().attemptJournalRequired()
        || !environment.operatorGate().atomicFinalPublishRequired()) {
      throw rejected("ENVIRONMENT_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyCompiledBindings() {
    AgentExecutionProfile profile = SyntheticEvalCatalog.profile();
    if (!SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH.equals(
            SyntheticEvalCatalog.computedCaptureRequestHash())
        || !SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
            IntegrityHashes.taskHash(SyntheticEvalCatalog.task()))
        || !SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT.equals(
            profile.pricing().fingerprint())
        || !SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT.equals(
            profile.fingerprint())
        || !SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID.equals(
            SyntheticEvalCatalog.computedAttemptId())
        || !("environment://sha256:"
                + SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256)
            .equals(profile.environmentSnapshotRef())) {
      throw rejected("COMPILED_BINDING_MISMATCH");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  record Result() {
    String receipt() {
      AgentExecutionProfile profile = SyntheticEvalCatalog.profile();
      return "EVAL_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " caseId="
          + SyntheticEvalCatalog.CASE_ID
          + " packSha256="
          + SyntheticEvalCatalog.PACK_RAW_SHA256
          + " environmentSha256="
          + SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256
          + " captureRequestHash="
          + SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH
          + " taskHash="
          + SyntheticEvalCatalog.EXPECTED_TASK_HASH
          + " executionProfileFingerprint="
          + SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT
          + " pricingProfileFingerprint="
          + SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT
          + " attemptId="
          + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
          + " modelRequested="
          + profile.modelRequested()
          + " maximumProviderRequests="
          + SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          + " deadlineMs="
          + profile.deadlineMs()
          + " maxInputTokensPerRequest="
          + profile.maxInputTokensPerStep()
          + " maxOutputTokensPerRequest="
          + profile.maxOutputTokensPerStep()
          + " reservationUsd="
          + profile.reservationUsd().toPlainString()
          + " keyReads=0 clientFactories=0 modelFactories=0"
          + " runStarts=0 httpRequests=0 markerCreated=false";
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
      SyntheticProvenance syntheticProvenance,
      ModelEval modelEval) {}

  private record Seed(
      String sourceType,
      String sourceRef,
      DataClass dataClass,
      String content) {}

  private record SyntheticProvenance(
      String kind,
      boolean containsRealUserData,
      boolean containsRealAccount) {}

  private record ModelEval(
      String caseId,
      String captureId,
      String clientNonce,
      String runId,
      String taskId,
      String artifactId,
      String intent,
      List<String> requiredTools,
      int maximumProviderRequests) {}

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
      String mode, boolean cacheWriteFeeIncluded, String reference) {}

  private record OperatorGate(
      boolean posixOneShotMarkerRequired,
      boolean realTtyChallengeRequired,
      boolean credentialReadAfterPermit,
      boolean durableRunRecordRequired,
      boolean attemptJournalRequired,
      boolean atomicFinalPublishRequired) {}
}
