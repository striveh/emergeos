package io.emergeos.evalrunner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import java.io.IOException;
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
 * Zero-egress preflight for Pack008.
 *
 * <p>This class only reads two bounded, hash-frozen repository assets and
 * reconstructs server-owned Tasks/profiles. It has no credential reader,
 * model/client factory, marker, Run store, socket or execution dependency.
 */
final class Pack008WorkerEvalPreflight {

  private static final long MAX_ASSET_BYTES = 128 * 1024;
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private final Path repoRoot;

  Pack008WorkerEvalPreflight(Path repoRoot) {
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
            Pack008WorkerEvalCatalog.PACK_PATH,
            Pack008WorkerEvalCatalog.PACK_RAW_SHA256);
    byte[] environmentBytes =
        readBoundedAsset(
            Pack008WorkerEvalCatalog.ENVIRONMENT_PATH,
            Pack008WorkerEvalCatalog.ENVIRONMENT_RAW_SHA256);
    verifyPackDocument(packBytes);
    verifyEnvironmentDocument(environmentBytes);
    verifyCompiledBindings();
    return new Result();
  }

  static void verifyPackDocument(byte[] bytes) {
    verifyPack(parse(bytes, TaskPack.class, "PACK008_INVALID"));
  }

  static void verifyEnvironmentDocument(byte[] bytes) {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack008WorkerEvalCatalog.workerProfile();
    try {
      SyntheticEvalPreflight.verifyEnvironmentDocument(
          bytes,
          worker,
          worker.harnessVersion(),
          worker.childToolRegistryVersion(),
          List.of("capture.read"),
          Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS);
    } catch (SyntheticEvalPreflight.Rejected rejected) {
      throw rejected(rejected.code());
    }
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
    ModelWorkerEval model = pack.modelWorkerEval();
    Provenance provenance = model == null ? null : model.provenance();
    if (!"0.7".equals(pack.schemaVersion())
        || !"synthetic-openai-read-only-worker-baseline-008"
            .equals(pack.taskId())
        || !Pack008WorkerEvalCatalog.PRINCIPAL_ID.equals(
            pack.principalRef())
        || pack.seed() == null
        || !Pack008WorkerEvalCatalog.SOURCE_TYPE.equals(
            pack.seed().sourceType())
        || !Pack008WorkerEvalCatalog.SOURCE_REF.equals(
            pack.seed().sourceRef())
        || pack.seed().dataClass() != DataClass.PUBLIC
        || !Pack008WorkerEvalCatalog.CONTENT.equals(
            pack.seed().content())
        || pack.risk() != RiskLevel.EXTERNAL
        || !"ARTICLE_DRAFT".equals(pack.expectedArtifactKind())
        || model == null
        || !Pack008WorkerEvalCatalog.CASE_ID.equals(model.caseId())
        || !Pack008WorkerEvalCatalog.CAPTURE_ID.equals(model.captureId())
        || !Pack008WorkerEvalCatalog.CLIENT_NONCE.equals(
            model.clientNonce())
        || !Pack008WorkerEvalCatalog.PARENT_RUN_ID.equals(
            model.parentRunId())
        || !Pack008WorkerEvalCatalog.CHILD_RUN_ID.equals(
            model.childRunId())
        || !Pack008WorkerEvalCatalog.PARENT_TASK_ID.equals(
            model.parentTaskId())
        || !Pack008WorkerEvalCatalog.CHILD_TASK_ID.equals(
            model.childTaskId())
        || !Pack008WorkerEvalCatalog.ARTIFACT_ID.equals(
            model.artifactId())
        || !Pack008WorkerEvalCatalog.INTENT.equals(model.intent())
        || !"SCRIPTED_FAKE".equals(model.parentActor())
        || !"OPENAI_RESPONSES".equals(model.childActor())
        || !Pack008WorkerEvalCatalog.workerProfile()
            .workerName()
            .equals(model.workerName())
        || !List.of("capture.read").equals(model.requiredTools())
        || model.maximumProviderRequests()
            != Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || provenance == null
        || !"LITERAL_CHECKED_IN_SYNTHETIC".equals(provenance.kind())
        || provenance.containsRealUserData()
        || provenance.containsRealAccount()
        || !provenance.networkAllowed()
        || !provenance.realModelAllowed()
        || provenance.connectorAllowed()) {
      throw rejected("PACK008_SEMANTICS_MISMATCH");
    }
  }

  private static void verifyCompiledBindings() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack008WorkerEvalCatalog.workerProfile();
    AgentExecutionProfile parent =
        Pack008WorkerEvalCatalog.parentProfile();
    TaskEnvelope parentTask = Pack008WorkerEvalCatalog.parentTask();
    TaskEnvelope childTask = Pack008WorkerEvalCatalog.childTask();
    if (!Pack008WorkerEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH.equals(
            Pack008WorkerEvalCatalog.computedCaptureRequestHash())
        || !Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH.equals(
            IntegrityHashes.taskHash(parentTask))
        || !Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH.equals(
            IntegrityHashes.taskHash(childTask))
        || !Pack008WorkerEvalCatalog.EXPECTED_PRICING_FINGERPRINT.equals(
            worker.pricing().fingerprint())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_PARENT_PROFILE_FINGERPRINT
            .equals(parent.fingerprint())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_WORKER_PROFILE_FINGERPRINT
            .equals(worker.fingerprint())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_PROMPT_SURFACE_FINGERPRINT
            .equals(
                Pack008WorkerEvalCatalog
                    .computedPromptSurfaceFingerprint())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT
            .equals(
                worker.parentConductorDecisionSurfaceFingerprint())
        || !Pack008WorkerEvalCatalog
            .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT
            .equals(
                io.emergeos.adapters.agentloop
                    .DeterministicReadOnlyWorkerConductorModel
                    .INHERITED_INTENT_DECISION_SURFACE_FINGERPRINT)
        || !Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID.equals(
            Pack008WorkerEvalCatalog.computedAttemptId())
        || parent.modelBound()
        || !parent.requiredDataClass().equals(DataClass.PUBLIC)
        || !parentTask.requiredTools().isEmpty()
        || !"agent-tools-none-v1".equals(
            parentTask.toolRegistryVersion())
        || childTask.unresolvedDecisions().size() != 0
        || !List.of("capture.read").equals(childTask.requiredTools())
        || worker.maxModelSteps()
            != Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || childTask.maxModelSteps()
            != Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || !worker.modelProvider().equals(childTask.modelProvider())
        || !worker.modelRequested().equals(childTask.modelRequested())
        || !worker.pricingProfile().equals(childTask.pricingProfile())
        || worker.budgetUsd().compareTo(worker.reservationUsd()) != 0
        || parent.budgetUsd().compareTo(worker.reservationUsd()) != 0
        || !("environment://sha256:"
                + Pack008WorkerEvalCatalog.ENVIRONMENT_RAW_SHA256)
            .equals(worker.environmentSnapshotRef())
        || !worker.environmentSnapshotRef().equals(
            parent.environmentSnapshotRef())) {
      throw rejected("PACK008_COMPILED_BINDING_MISMATCH");
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
          Pack008WorkerEvalCatalog.workerProfile();
      AgentExecutionProfile parent =
          Pack008WorkerEvalCatalog.parentProfile();
      return "PACK008_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " caseId="
          + Pack008WorkerEvalCatalog.CASE_ID
          + " packSha256="
          + Pack008WorkerEvalCatalog.PACK_RAW_SHA256
          + " environmentSha256="
          + Pack008WorkerEvalCatalog.ENVIRONMENT_RAW_SHA256
          + " captureRequestHash="
          + Pack008WorkerEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH
          + " parentTaskHash="
          + Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH
          + " childTaskHash="
          + Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH
          + " parentExecutionProfileFingerprint="
          + Pack008WorkerEvalCatalog
              .EXPECTED_PARENT_PROFILE_FINGERPRINT
          + " workerProfileFingerprint="
          + Pack008WorkerEvalCatalog
              .EXPECTED_WORKER_PROFILE_FINGERPRINT
          + " pricingProfileFingerprint="
          + Pack008WorkerEvalCatalog.EXPECTED_PRICING_FINGERPRINT
          + " promptSurfaceFingerprint="
          + Pack008WorkerEvalCatalog
              .EXPECTED_PROMPT_SURFACE_FINGERPRINT
          + " conductorDecisionSurfaceFingerprint="
          + Pack008WorkerEvalCatalog
              .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT
          + " attemptId="
          + Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID
          + " modelRequested="
          + worker.modelRequested()
          + " parentActor=SCRIPTED_FAKE childActor=OPENAI_RESPONSES"
          + " dataClass=PUBLIC literalSynthetic=true"
          + " maximumProviderRequests="
          + Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          + " childDeadlineMs="
          + worker.deadlineMs()
          + " parentDeadlineMs="
          + parent.deadlineMs()
          + " maxInputTokensPerRequest="
          + worker.maxInputTokensPerStep()
          + " maxOutputTokensPerRequest="
          + worker.maxOutputTokensPerStep()
          + " reservationUsd="
          + worker.reservationUsd().toPlainString()
          + " runtimeEffectCounters=NOT_INSTRUMENTED"
          + " zeroEgressProof=STATIC_PATH_PLUS_PROCESS_SENTINEL";
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
}
