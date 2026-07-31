package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack009GraphPreflightTest {

  @TempDir Path tempDir;

  @Test
  void freezesTheZeroEffectGraphPreflightBoundary() {
    String receipt =
        new Pack009GraphPreflight(repoRoot()).run().receipt();

    assertTrue(
        receipt.startsWith(
            "PACK009_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"));
    assertTrue(
        receipt.contains(
            "executionSlotId="
                + Pack009GraphEvalCatalog.EXECUTION_SLOT_ID));
    assertTrue(
        receipt.contains(
            "packSha256="
                + Pack009GraphEvalCatalog.PACK_RAW_SHA256));
    assertTrue(
        receipt.contains(
            "environmentSha256="
                + Pack009GraphEvalCatalog
                    .ENVIRONMENT_RAW_SHA256));
    assertTrue(
        receipt.contains(
            "parentTaskHash="
                + Pack009GraphEvalCatalog
                    .EXPECTED_PARENT_TASK_HASH));
    assertTrue(
        receipt.contains(
            "childTaskHash="
                + Pack009GraphEvalCatalog
                    .EXPECTED_CHILD_TASK_HASH));
    assertTrue(receipt.contains("dataSources=0 keyReads=0"));
    assertTrue(receipt.contains("httpRequests=0"));
    assertTrue(receipt.contains("graphAttemptCreated=false"));
    assertTrue(receipt.endsWith("shippingExecute=false"));
    assertFalse(receipt.contains("OPENAI_API_KEY"));
    assertFalse(receipt.contains("Authorization"));
    assertFalse(receipt.contains(Pack009GraphEvalCatalog.CONTENT));
  }

  @Test
  void freezesAllCurrentlyCompiledIdentities() {
    assertEquals(
        List.of(
            Pack009GraphEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
            Pack009GraphEvalCatalog.EXPECTED_PARENT_TASK_HASH,
            Pack009GraphEvalCatalog.EXPECTED_CHILD_TASK_HASH,
            Pack009GraphEvalCatalog.EXPECTED_PRICING_FINGERPRINT,
            Pack009GraphEvalCatalog
                .EXPECTED_PARENT_PROFILE_FINGERPRINT,
            Pack009GraphEvalCatalog
                .EXPECTED_WORKER_PROFILE_FINGERPRINT,
            Pack009GraphEvalCatalog
                .EXPECTED_PROMPT_SURFACE_FINGERPRINT,
            Pack009GraphEvalCatalog
                .EXPECTED_PARENT_SELECTOR_HASH,
            Pack009GraphEvalCatalog
                .EXPECTED_CHILD_SELECTOR_HASH,
            Pack009GraphEvalCatalog.EXPECTED_MANIFEST_HASH,
            Pack009GraphEvalCatalog
                .EXPECTED_FIRST_REQUEST_HASH),
        List.of(
            Pack009GraphEvalCatalog.computedCaptureRequestHash(),
            Pack009GraphEvalCatalog.computedParentTaskHash(),
            Pack009GraphEvalCatalog.computedChildTaskHash(),
            Pack009GraphEvalCatalog.pricing().fingerprint(),
            Pack009GraphEvalCatalog.parentProfile().fingerprint(),
            Pack009GraphEvalCatalog.workerProfile().fingerprint(),
            Pack009GraphEvalCatalog
                .computedPromptSurfaceFingerprint(),
            Pack009GraphEvalCatalog
                .parentSelection()
                .selectorHash(),
            Pack009GraphEvalCatalog
                .childSelection()
                .selectorHash(),
            Pack009GraphEvalCatalog.manifest().manifestHash(),
            Pack009GraphEvalCatalog
                .computedFirstRequestHash()));
  }

  @Test
  void rejectsPackAndEnvironmentByteDrift() throws Exception {
    Path packDrift = copyFrozenAssets(tempDir.resolve("pack-drift"));
    Files.writeString(
        packDrift.resolve(Pack009GraphEvalCatalog.PACK_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack009GraphPreflight(packDrift).run());

    Path environmentDrift =
        copyFrozenAssets(tempDir.resolve("environment-drift"));
    Files.writeString(
        environmentDrift.resolve(
            Pack009GraphEvalCatalog.ENVIRONMENT_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack009GraphPreflight(environmentDrift).run());
  }

  @Test
  void rejectsSymlinksAndOversizedAssetsBeforeParsing()
      throws Exception {
    Path symlinkRoot = tempDir.resolve("symlink");
    copyAsset(
        Pack009GraphEvalCatalog.ENVIRONMENT_PATH,
        symlinkRoot.resolve(
            Pack009GraphEvalCatalog.ENVIRONMENT_PATH));
    Path link =
        symlinkRoot.resolve(Pack009GraphEvalCatalog.PACK_PATH);
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(
        link,
        repoRoot()
            .resolve(Pack009GraphEvalCatalog.PACK_PATH)
            .toAbsolutePath());
    assertRejected(
        "ASSET_SYMLINK_REJECTED",
        () -> new Pack009GraphPreflight(symlinkRoot).run());

    Path oversized = copyFrozenAssets(tempDir.resolve("oversized"));
    Files.write(
        oversized.resolve(Pack009GraphEvalCatalog.PACK_PATH),
        new byte[128 * 1024 + 1]);
    assertRejected(
        "ASSET_SIZE_INVALID",
        () -> new Pack009GraphPreflight(oversized).run());
  }

  @Test
  void strictPackParserRejectsSemanticDuplicateTrailingAndUnknownDrift()
      throws Exception {
    String pack =
        Files.readString(
            repoRoot().resolve(Pack009GraphEvalCatalog.PACK_PATH),
            StandardCharsets.UTF_8);
    String semantic =
        pack.replace(
            Pack009GraphEvalCatalog.EXECUTION_SLOT_ID,
            "pack009-provider-accepted-crash-r2");
    assertRejected(
        "PACK009_SEMANTICS_MISMATCH",
        () ->
            Pack009GraphPreflight.verifyPackDocument(
                semantic.getBytes(StandardCharsets.UTF_8)));
    String duplicate =
        pack.replaceFirst(
            "\"schemaVersion\": \"0.8\",",
            "\"schemaVersion\": \"0.8\","
                + " \"schemaVersion\": \"0.8\",");
    assertRejected(
        "PACK009_INVALID",
        () ->
            Pack009GraphPreflight.verifyPackDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK009_INVALID",
        () ->
            Pack009GraphPreflight.verifyPackDocument(
                (pack + "{}").getBytes(StandardCharsets.UTF_8)));
    String unknown =
        pack.replaceFirst("\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "PACK009_INVALID",
        () ->
            Pack009GraphPreflight.verifyPackDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void strictEnvironmentParserRejectsGateDuplicateTrailingAndUnknownDrift()
      throws Exception {
    String environment =
        Files.readString(
            repoRoot()
                .resolve(Pack009GraphEvalCatalog.ENVIRONMENT_PATH),
            StandardCharsets.UTF_8);
    String semantic =
        environment.replace(
            "\"shippingExecuteRouteEnabled\": false",
            "\"shippingExecuteRouteEnabled\": true");
    assertRejected(
        "ENVIRONMENT_SEMANTICS_MISMATCH",
        () ->
            Pack009GraphPreflight.verifyEnvironmentDocument(
                semantic.getBytes(StandardCharsets.UTF_8)));
    String duplicate =
        environment.replaceFirst(
            "\"schemaVersion\": \"0.1\",",
            "\"schemaVersion\": \"0.1\","
                + " \"schemaVersion\": \"0.1\",");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack009GraphPreflight.verifyEnvironmentDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack009GraphPreflight.verifyEnvironmentDocument(
                (environment + "{}")
                    .getBytes(StandardCharsets.UTF_8)));
    String unknown =
        environment.replaceFirst(
            "\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack009GraphPreflight.verifyEnvironmentDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  private Path copyFrozenAssets(Path root) throws IOException {
    copyAsset(
        Pack009GraphEvalCatalog.PACK_PATH,
        root.resolve(Pack009GraphEvalCatalog.PACK_PATH));
    copyAsset(
        Pack009GraphEvalCatalog.ENVIRONMENT_PATH,
        root.resolve(Pack009GraphEvalCatalog.ENVIRONMENT_PATH));
    return root;
  }

  private void copyAsset(String relative, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.copy(repoRoot().resolve(relative), target);
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    Pack009GraphPreflight.Rejected rejected =
        assertThrows(
            Pack009GraphPreflight.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }

  private static Path repoRoot() {
    Path configured =
        Path.of(System.getProperty("emerge.graph.repo", "."))
            .toAbsolutePath()
            .normalize();
    if (Files.isRegularFile(configured.resolve("pom.xml"))
        && Files.isDirectory(
            configured.resolve("evals/task-packs"))) {
      return configured;
    }
    throw new IllegalStateException("repository root not found");
  }
}
