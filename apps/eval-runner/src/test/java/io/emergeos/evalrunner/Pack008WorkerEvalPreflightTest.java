package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack008WorkerEvalPreflightTest {

  @TempDir Path tempDir;

  @Test
  void bindsTheFrozenWholeGraphAndReportsTheEvidenceBoundary() {
    String receipt =
        new Pack008WorkerEvalPreflight(repoRoot()).run().receipt();

    assertTrue(
        receipt.startsWith(
            "PACK008_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"));
    assertTrue(
        receipt.contains(
            "packSha256=" + Pack008WorkerEvalCatalog.PACK_RAW_SHA256));
    assertTrue(
        receipt.contains(
            "environmentSha256="
                + Pack008WorkerEvalCatalog.ENVIRONMENT_RAW_SHA256));
    assertTrue(
        receipt.contains(
            "captureRequestHash="
                + Pack008WorkerEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH));
    assertTrue(
        receipt.contains(
            "parentTaskHash="
                + Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH));
    assertTrue(
        receipt.contains(
            "childTaskHash="
                + Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH));
    assertTrue(
        receipt.contains(
            "parentExecutionProfileFingerprint="
                + Pack008WorkerEvalCatalog
                    .EXPECTED_PARENT_PROFILE_FINGERPRINT));
    assertTrue(
        receipt.contains(
            "workerProfileFingerprint="
                + Pack008WorkerEvalCatalog
                    .EXPECTED_WORKER_PROFILE_FINGERPRINT));
    assertTrue(
        receipt.contains(
            "pricingProfileFingerprint="
                + Pack008WorkerEvalCatalog
                    .EXPECTED_PRICING_FINGERPRINT));
    assertTrue(
        receipt.contains(
            "promptSurfaceFingerprint="
                + Pack008WorkerEvalCatalog
                    .EXPECTED_PROMPT_SURFACE_FINGERPRINT));
    assertTrue(
        receipt.contains(
            "conductorDecisionSurfaceFingerprint="
                + Pack008WorkerEvalCatalog
                    .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT));
    assertTrue(
        receipt.contains(
            "attemptId=" + Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID));
    assertTrue(
        receipt.contains(
            "parentActor=SCRIPTED_FAKE childActor=OPENAI_RESPONSES"));
    assertTrue(receipt.contains("dataClass=PUBLIC literalSynthetic=true"));
    assertTrue(
        receipt.contains(
            "maximumProviderRequests="
                + Pack008WorkerEvalCatalog.MAXIMUM_PROVIDER_REQUESTS));
    assertTrue(receipt.contains("reservationUsd=0.417000"));
    assertTrue(
        receipt.endsWith(
            "runtimeEffectCounters=NOT_INSTRUMENTED"
                + " zeroEgressProof=STATIC_PATH_PLUS_PROCESS_SENTINEL"));
    assertFalse(receipt.contains("OPENAI_API_KEY"));
    assertFalse(receipt.contains("Authorization"));
    assertFalse(receipt.contains(Pack008WorkerEvalCatalog.CONTENT));
  }

  @Test
  void rejectsActualPack007LegacyPack003AndPack008ByteDrift()
      throws Exception {
    byte[] pack007 =
        Files.readAllBytes(
            repoRoot()
                .resolve(
                    "evals/task-packs/synthetic/"
                        + "007-offline-read-only-worker-handoff-context-drift.json"));
    assertRejected(
        "PACK008_INVALID",
        () -> Pack008WorkerEvalPreflight.verifyPackDocument(pack007));

    byte[] pack003 =
        Files.readAllBytes(
            repoRoot().resolve(SyntheticEvalCatalog.PACK_PATH));
    assertRejected(
        "PACK008_INVALID",
        () -> Pack008WorkerEvalPreflight.verifyPackDocument(pack003));

    Path root = copyFrozenAssets(tempDir.resolve("drift"));
    Files.writeString(
        root.resolve(Pack008WorkerEvalCatalog.PACK_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack008WorkerEvalPreflight(root).run());
  }

  @Test
  void rejectsSymlinkedPackBeforeParsing() throws Exception {
    Path root = tempDir.resolve("symlink");
    copyAsset(
        Pack008WorkerEvalCatalog.ENVIRONMENT_PATH,
        root.resolve(Pack008WorkerEvalCatalog.ENVIRONMENT_PATH));
    Path link = root.resolve(Pack008WorkerEvalCatalog.PACK_PATH);
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(
        link,
        repoRoot()
            .resolve(Pack008WorkerEvalCatalog.PACK_PATH)
            .toAbsolutePath());

    assertRejected(
        "ASSET_SYMLINK_REJECTED",
        () -> new Pack008WorkerEvalPreflight(root).run());
  }

  @Test
  void rejectsEnvironmentByteDriftAndStrictJsonFaults()
      throws Exception {
    Path driftRoot =
        copyFrozenAssets(tempDir.resolve("environment-drift"));
    Files.writeString(
        driftRoot.resolve(Pack008WorkerEvalCatalog.ENVIRONMENT_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack008WorkerEvalPreflight(driftRoot).run());

    String environment =
        Files.readString(
            repoRoot()
                .resolve(Pack008WorkerEvalCatalog.ENVIRONMENT_PATH),
            StandardCharsets.UTF_8);
    String semanticDrift =
        environment.replaceFirst(
            "\"maximumProviderRequests\": 2",
            "\"maximumProviderRequests\": 3");
    assertRejected(
        "ENVIRONMENT_SEMANTICS_MISMATCH",
        () ->
            Pack008WorkerEvalPreflight.verifyEnvironmentDocument(
                semanticDrift.getBytes(StandardCharsets.UTF_8)));
    String duplicate =
        environment.replaceFirst(
            "\"schemaVersion\": \"0.1\",",
            "\"schemaVersion\": \"0.1\","
                + " \"schemaVersion\": \"0.1\",");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyEnvironmentDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyEnvironmentDocument(
                (environment + "{}")
                    .getBytes(StandardCharsets.UTF_8)));
    String unknown =
        environment.replaceFirst(
            "\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyEnvironmentDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void strictPackJsonRejectsDuplicateTrailingAndUnknownFields()
      throws Exception {
    String pack =
        Files.readString(
            repoRoot().resolve(Pack008WorkerEvalCatalog.PACK_PATH),
            StandardCharsets.UTF_8);
    String duplicate =
        pack.replaceFirst(
            "\"schemaVersion\": \"0.7\",",
            "\"schemaVersion\": \"0.7\","
                + " \"schemaVersion\": \"0.7\",");
    assertRejected(
        "PACK008_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyPackDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK008_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyPackDocument(
                (pack + "{}").getBytes(StandardCharsets.UTF_8)));
    String unknown =
        pack.replaceFirst("\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "PACK008_INVALID",
        () ->
            Pack008WorkerEvalPreflight.verifyPackDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  private Path copyFrozenAssets(Path root) throws IOException {
    copyAsset(
        Pack008WorkerEvalCatalog.PACK_PATH,
        root.resolve(Pack008WorkerEvalCatalog.PACK_PATH));
    copyAsset(
        Pack008WorkerEvalCatalog.ENVIRONMENT_PATH,
        root.resolve(Pack008WorkerEvalCatalog.ENVIRONMENT_PATH));
    return root;
  }

  private void copyAsset(String relative, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    Files.copy(repoRoot().resolve(relative), target);
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    Pack008WorkerEvalPreflight.Rejected rejected =
        assertThrows(
            Pack008WorkerEvalPreflight.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }

  private static Path repoRoot() {
    Path current =
        Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("evals/task-packs"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
