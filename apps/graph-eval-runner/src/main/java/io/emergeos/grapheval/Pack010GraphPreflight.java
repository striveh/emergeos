package io.emergeos.grapheval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.GraphAttemptManifest;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Zero-effect Pack010 catalog preflight.
 *
 * <p>The only I/O is two bounded, hash-frozen repository asset reads. This
 * path does not construct a DataSource, graph Store, credential reader,
 * provider client/model, socket, Run or execution authority.
 */
final class Pack010GraphPreflight {

  private static final long MAX_ASSET_BYTES = 128 * 1024;
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(
              DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

  private final Path repoRoot;

  Pack010GraphPreflight(Path repoRoot) {
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
            Pack010GraphEvalCatalog.PACK_PATH,
            Pack010GraphEvalCatalog.PACK_RAW_SHA256);
    byte[] environmentBytes =
        readBoundedAsset(
            Pack010GraphEvalCatalog.ENVIRONMENT_PATH,
            Pack010GraphEvalCatalog.ENVIRONMENT_RAW_SHA256);
    verifyPackDocument(packBytes);
    verifyEnvironmentDocument(environmentBytes);
    verifyCompiledBindings();
    return new Result();
  }

  static void verifyPackDocument(byte[] bytes) {
    verifyPack(parse(bytes, TaskPack.class, "PACK010_INVALID"));
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
      byte[] bytes;
      try (InputStream input =
          Files.newInputStream(asset, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes((int) MAX_ASSET_BYTES + 1);
      }
      if (bytes.length > MAX_ASSET_BYTES) {
        throw rejected("ASSET_SIZE_INVALID");
      }
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
    HarnessPilot pilot = pack.harnessPilot();
    Provenance provenance = pilot == null ? null : pilot.provenance();
    if (!"0.9".equals(pack.schemaVersion())
        || !"synthetic-openai-shared-candidate-terminal-graph-010"
            .equals(pack.taskId())
        || !Pack010GraphEvalCatalog.PRINCIPAL_ID.equals(
            pack.principalRef())
        || pack.seed() == null
        || !Pack010GraphEvalCatalog.SOURCE_TYPE.equals(
            pack.seed().sourceType())
        || !Pack010GraphEvalCatalog.SOURCE_REF.equals(
            pack.seed().sourceRef())
        || pack.seed().dataClass() != DataClass.PUBLIC
        || !Pack010GraphEvalCatalog.CONTENT.equals(pack.seed().content())
        || pack.risk() != RiskLevel.EXTERNAL
        || !"ARTICLE_DRAFT".equals(pack.expectedArtifactKind())
        || !nonEmpty(pack.requiredConstraints())
        || !nonEmpty(pack.forbiddenActions())
        || !nonEmpty(pack.acceptanceChecks())
        || !nonEmpty(pack.humanReviewQuestions())
        || !nonEmpty(pack.faultPlan())
        || pilot == null
        || !Pack010GraphEvalCatalog.GRAPH_PROTOCOL_VERSION.equals(
            pilot.graphProtocolVersion())
        || !Pack010GraphEvalCatalog.EXPERIMENT_ARM.equals(
            pilot.experimentArm())
        || pilot.generationRepetitions()
            != Pack010GraphEvalCatalog.GENERATION_REPETITIONS
        || !"openai.responses".equals(pilot.provider())
        || !Pack010GraphEvalCatalog.MODEL_REQUESTED.equals(
            pilot.modelRequested())
        || !Pack010GraphEvalCatalog.PARENT_ACTOR.equals(
            pilot.parentActor())
        || !Pack010GraphEvalCatalog.CHILD_ACTOR.equals(
            pilot.childActor())
        || !Pack010GraphEvalCatalog.workerProfile(1)
            .workerName()
            .equals(pilot.workerName())
        || !List.of("capture.read").equals(pilot.requiredTools())
        || pilot.maximumProviderRequests()
            != Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || !List.of("capture.read", "structured-final")
            .equals(pilot.requestPlan())
        || !List.of(
                "h0-schema-only", "h1-reference-grounding")
            .equals(pilot.evaluatorArms())
        || !pilot.sharedCandidateRequired()
        || !pilot.completeOnlyReport()
        || !Pack010GraphEvalCatalog.CAPTURE_ID.equals(pilot.captureId())
        || !Pack010GraphEvalCatalog.CAPTURE_CLIENT_NONCE.equals(
            pilot.captureClientNonce())
        || !Pack010GraphEvalCatalog.INTENT.equals(pilot.intent())
        || provenance == null
        || !"LITERAL_CHECKED_IN_SYNTHETIC".equals(provenance.kind())
        || provenance.containsRealUserData()
        || provenance.containsRealAccount()
        || provenance.networkAllowed()
        || provenance.realModelAllowed()
        || provenance.credentialReadsAllowed()
        || provenance.connectorAllowed()
        || !validRepetitions(pilot.repetitions())) {
      throw rejected("PACK010_SEMANTICS_MISMATCH");
    }
  }

  private static boolean validRepetitions(
      List<Repetition> repetitions) {
    if (repetitions == null
        || repetitions.size()
            != Pack010GraphEvalCatalog.GENERATION_REPETITIONS) {
      return false;
    }
    Set<String> caseIds = new HashSet<>();
    Set<String> slots = new HashSet<>();
    Set<String> runIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();
    Set<String> artifactIds = new HashSet<>();
    for (int index = 0; index < repetitions.size(); index++) {
      Repetition actual = repetitions.get(index);
      Pack010GraphEvalCatalog.RepetitionSpec expected =
          Pack010GraphEvalCatalog.repetitions().get(index);
      if (actual == null
          || actual.repetition() != index + 1
          || actual.repetition() != expected.repetition()
          || !expected.caseId().equals(actual.caseId())
          || !expected.executionSlotId().equals(
              actual.executionSlotId())
          || !expected.startedAt().equals(actual.startedAt())
          || !expected.parentRunId().equals(actual.parentRunId())
          || !expected.childRunId().equals(actual.childRunId())
          || !expected.parentTaskId().equals(actual.parentTaskId())
          || !expected.childTaskId().equals(actual.childTaskId())
          || !expected.artifactId().equals(actual.artifactId())
          || !caseIds.add(actual.caseId())
          || !slots.add(actual.executionSlotId())
          || !runIds.add(actual.parentRunId())
          || !runIds.add(actual.childRunId())
          || !taskIds.add(actual.parentTaskId())
          || !taskIds.add(actual.childTaskId())
          || !artifactIds.add(actual.artifactId())) {
        return false;
      }
    }
    return runIds.size() == 6
        && taskIds.size() == 6
        && slots.size() == 3
        && caseIds.size() == 3
        && artifactIds.size() == 3;
  }

  private static void verifyEnvironment(
      EnvironmentManifest environment) {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack010GraphEvalCatalog.workerProfile(1);
    PricingProfile pricing = worker.pricing();
    RequestPolicy request = environment.requestPolicy();
    Pricing manifestPricing = environment.pricing();
    EvaluatorPolicy evaluator = environment.evaluatorPolicy();
    OperatorGate gate = environment.operatorGate();
    if (!"0.2".equals(environment.schemaVersion())
        || !"2026-08-01".equals(environment.reviewedAt())
        || environment.javaRelease() != 21
        || !"4.43.0".equals(environment.openaiJavaVersion())
        || !OpenAiResponsesModel.PROTOCOL_VERSION.equals(
            environment.protocolVersion())
        || !worker.harnessVersion().equals(environment.harnessVersion())
        || !worker.childToolRegistryVersion().equals(
            environment.toolRegistryVersion())
        || !List.of("capture.read").equals(environment.tools())
        || !"responses".equals(environment.providerApi())
        || environment.model() == null
        || !Pack010GraphEvalCatalog.MODEL_REQUESTED.equals(
            environment.model().requested())
        || !Pack010GraphEvalCatalog.MODEL_REQUESTED.equals(
            environment.model().pricingFamily())
        || environment.model().maxInputTokens()
            != worker.maxInputTokensPerStep()
        || environment.model().maxOutputTokens() != 128_000
        || !rfcReference(environment.model().reference())
        || request == null
        || request.store()
        || request.parallelToolCalls()
        || !"offline".equals(request.serviceTier())
        || request.maxRetries() != 0
        || request.productionBaseUrl() != null
        || request.productionBaseUrlOverrideAllowed()
        || request.ambientProxyAllowed()
        || !"OFF".equals(request.sdkLogLevel())
        || request.maximumProviderRequests()
            != Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || request.maximumInputTokensPerRequest()
            != worker.maxInputTokensPerStep()
        || request.maximumOutputTokensPerRequest()
            != worker.maxOutputTokensPerStep()
        || request.networkAllowed()
        || request.realModelAllowed()
        || request.credentialReadsAllowed()
        || manifestPricing == null
        || !"SYNTHETIC_NON_BILLING".equals(manifestPricing.kind())
        || !"USD".equals(manifestPricing.currency())
        || !"NANO_USD_PER_TOKEN".equals(manifestPricing.unit())
        || manifestPricing.uncachedInput()
            != pricing.uncachedInputNanoUsdPerToken()
        || manifestPricing.cachedInput()
            != pricing.cachedInputNanoUsdPerToken()
        || manifestPricing.output()
            != pricing.outputNanoUsdPerToken()
        || worker.reservationUsd().compareTo(
                decimal(manifestPricing.fullRunReservationUsd()))
            != 0
        || !rfcReference(manifestPricing.reference())
        || environment.inputTokenUpperBound() == null
        || !"REVIEWED_SYNTHETIC_BOUND".equals(
            environment.inputTokenUpperBound().method())
        || environment.inputTokenUpperBound().tokens()
            != worker.maxInputTokensPerStep()
        || !rfcReference(
            environment.inputTokenUpperBound().reference())
        || environment.promptCachePolicy() == null
        || !"DISABLED_OFFLINE_PREFLIGHT".equals(
            environment.promptCachePolicy().mode())
        || environment.promptCachePolicy().cacheWriteFeeIncluded()
        || !rfcReference(environment.promptCachePolicy().reference())
        || evaluator == null
        || !List.of(
                "h0-schema-only", "h1-reference-grounding")
            .equals(evaluator.arms())
        || evaluator.sharedCandidateInputs() != 3
        || evaluator.evaluations() != 6
        || evaluator.networkCalls() != 0
        || evaluator.credentialReads() != 0
        || evaluator.connectorCalls() != 0
        || evaluator.productTruthWrites() != 0
        || evaluator.externalSideEffects() != 0
        || evaluator.realUserDataReads() != 0
        || gate == null
        || !gate.postgresqlCanonicalGraphAttemptRequired()
        || !gate.stableExecutionSlotRequired()
        || !gate.realTtyChallengeRequired()
        || !gate.credentialReadAfterDurableChildConsume()
        || !gate.durableGraphJournalRequired()
        || !gate.terminalGraphSealRequired()
        || !gate.sequentialOwnerApprovalRequired()
        || gate.shippingExecuteRouteEnabled()) {
      throw rejected("ENVIRONMENT_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyCompiledBindings() {
    List<GraphAttemptManifest> manifests =
        Pack010GraphEvalCatalog.manifests();
    Set<String> attempts = new HashSet<>();
    Set<String> runIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();
    Set<String> workerFingerprints = new HashSet<>();
    if (!Pack010GraphEvalCatalog.EXPECTED_COMPILED_IDENTITIES.equals(
            Pack010GraphEvalCatalog.computedCompiledIdentities())
        || !Pack010GraphEvalCatalog.EXPECTED_MANIFEST_HASHES.equals(
            manifests.stream()
                .map(GraphAttemptManifest::attemptId)
                .toList())
        || manifests.size()
            != Pack010GraphEvalCatalog.GENERATION_REPETITIONS) {
      throw rejected("PACK010_COMPILED_BINDING_MISMATCH");
    }
    for (int index = 0; index < manifests.size(); index++) {
      int repetition = index + 1;
      GraphAttemptManifest manifest = manifests.get(index);
      ModelBoundReadOnlyWorkerExecutionProfile worker =
          Pack010GraphEvalCatalog.workerProfile(repetition);
      AgentExecutionProfile parent =
          Pack010GraphEvalCatalog.parentProfile(repetition);
      TaskEnvelope parentTask =
          Pack010GraphEvalCatalog.parentTask(repetition);
      TaskEnvelope childTask =
          Pack010GraphEvalCatalog.childTask(repetition);
      if (!Pack010GraphEvalCatalog.GRAPH_PROTOCOL_VERSION.equals(
              manifest.graphProtocolVersion())
          || !Pack010GraphEvalCatalog.PACK_RAW_SHA256.equals(
              manifest.packRawSha256())
          || !Pack010GraphEvalCatalog.ENVIRONMENT_RAW_SHA256.equals(
              manifest.environmentRawSha256())
          || !Pack010GraphEvalCatalog.CAPTURE_ID.equals(
              manifest.captureId())
          || !Pack010GraphEvalCatalog.computedCaptureRequestHash()
              .equals(manifest.captureRequestHash())
          || manifest.maximumProviderRequests()
              != Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || !Pack010GraphEvalCatalog.PARENT_ACTOR.equals(
              manifest.parentActor())
          || !Pack010GraphEvalCatalog.CHILD_ACTOR.equals(
              manifest.childActor())
          || !Pack010GraphEvalCatalog.EXPERIMENT_ARM.equals(
              manifest.experiment().arm())
          || manifest.experiment().repetition() != repetition
          || !manifest.executionSlotId().equals(
              "pack010-r" + repetition)
          || !attempts.add(manifest.attemptId())
          || !runIds.add(manifest.parentSelection().runId())
          || !runIds.add(manifest.childSelection().runId())
          || !taskIds.add(manifest.parentSelection().taskId())
          || !taskIds.add(manifest.childSelection().taskId())
          || !workerFingerprints.add(worker.fingerprint())
          || !Pack010GraphEvalCatalog.MODEL_REQUESTED.equals(
              worker.modelRequested())
          || !"openai.responses".equals(worker.modelProvider())
          || worker.maxModelSteps()
              != Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || worker.experiment().repetition() != repetition
          || parent.modelBound()
          || !parent.requiredDataClass().equals(DataClass.PUBLIC)
          || !parentTask.requiredTools().isEmpty()
          || !"agent-tools-none-v1".equals(
              parentTask.toolRegistryVersion())
          || !childTask.unresolvedDecisions().isEmpty()
          || !List.of("capture.read").equals(
              childTask.requiredTools())
          || childTask.maxModelSteps()
              != Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || !worker.modelProvider().equals(
              childTask.modelProvider())
          || !worker.modelRequested().equals(
              childTask.modelRequested())
          || !worker.pricingProfile().equals(
              childTask.pricingProfile())
          || !worker.environmentSnapshotRef().equals(
              childTask.environmentSnapshotRef())
          || !worker.environmentSnapshotRef().equals(
              parent.environmentSnapshotRef())) {
        throw rejected("PACK010_COMPILED_BINDING_MISMATCH");
      }
    }
    if (attempts.size() != 3
        || runIds.size() != 6
        || taskIds.size() != 6
        || workerFingerprints.size() != 3
        || !runIds.equals(
            Set.copyOf(Pack010GraphEvalCatalog.EXPECTED_RUN_IDS))
        || !taskIds.equals(
            Set.copyOf(Pack010GraphEvalCatalog.EXPECTED_TASK_IDS))) {
      throw rejected("PACK010_COMPILED_BINDING_MISMATCH");
    }
  }

  private static boolean nonEmpty(List<String> values) {
    return values != null
        && !values.isEmpty()
        && values.stream()
            .allMatch(value -> value != null && !value.isBlank());
  }

  private static boolean rfcReference(String value) {
    return "docs/rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md"
        .equals(value);
  }

  private static BigDecimal decimal(String value) {
    try {
      return new BigDecimal(value);
    } catch (RuntimeException invalid) {
      throw rejected("ENVIRONMENT_SEMANTICS_MISMATCH");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  static final class Result {

    private Result() {}

    String receipt() {
      return "PACK010_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " packSha256="
          + Pack010GraphEvalCatalog.PACK_RAW_SHA256
          + " environmentSha256="
          + Pack010GraphEvalCatalog.ENVIRONMENT_RAW_SHA256
          + " slots=pack010-r1,pack010-r2,pack010-r3"
          + " repetitions=1,2,3"
          + " attempts="
          + String.join(",", Pack010GraphEvalCatalog.EXPECTED_MANIFEST_HASHES)
          + " workerProfileFingerprints="
          + String.join(
              ",",
              Pack010GraphEvalCatalog
                  .EXPECTED_WORKER_PROFILE_FINGERPRINTS)
          + " modelRequested="
          + Pack010GraphEvalCatalog.MODEL_REQUESTED
          + " maximumProviderRequests="
          + Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          + " dataSources=0 keyReads=0 clientFactories=0"
          + " modelFactories=0 runStarts=0 httpRequests=0"
          + " graphAttemptsCreated=0 networkAllowed=false"
          + " realModelAllowed=false shippingExecute=false";
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
      HarnessPilot harnessPilot) {}

  private record Seed(
      String sourceType,
      String sourceRef,
      DataClass dataClass,
      String content) {}

  private record HarnessPilot(
      String graphProtocolVersion,
      String experimentArm,
      int generationRepetitions,
      String provider,
      String modelRequested,
      String parentActor,
      String childActor,
      String workerName,
      List<String> requiredTools,
      int maximumProviderRequests,
      List<String> requestPlan,
      List<String> evaluatorArms,
      boolean sharedCandidateRequired,
      boolean completeOnlyReport,
      String captureId,
      String captureClientNonce,
      String intent,
      List<Repetition> repetitions,
      Provenance provenance) {}

  private record Repetition(
      int repetition,
      String caseId,
      String executionSlotId,
      Instant startedAt,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId) {}

  private record Provenance(
      String kind,
      boolean containsRealUserData,
      boolean containsRealAccount,
      boolean networkAllowed,
      boolean realModelAllowed,
      boolean credentialReadsAllowed,
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
      EvaluatorPolicy evaluatorPolicy,
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
      long maximumOutputTokensPerRequest,
      boolean networkAllowed,
      boolean realModelAllowed,
      boolean credentialReadsAllowed) {}

  private record Pricing(
      String kind,
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

  private record EvaluatorPolicy(
      List<String> arms,
      int sharedCandidateInputs,
      int evaluations,
      int networkCalls,
      int credentialReads,
      int connectorCalls,
      int productTruthWrites,
      int externalSideEffects,
      int realUserDataReads) {}

  private record OperatorGate(
      boolean postgresqlCanonicalGraphAttemptRequired,
      boolean stableExecutionSlotRequired,
      boolean realTtyChallengeRequired,
      boolean credentialReadAfterDurableChildConsume,
      boolean durableGraphJournalRequired,
      boolean terminalGraphSealRequired,
      boolean sequentialOwnerApprovalRequired,
      boolean shippingExecuteRouteEnabled) {}
}
