package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack010GraphPreflightTest {

  @TempDir Path tempDir;

  @Test
  void validatesAllThreeRepetitionsWithoutEffects() {
    String receipt =
        new Pack010GraphPreflight(repoRoot()).run().receipt();

    assertTrue(
        receipt.startsWith(
            "PACK010_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"));
    assertTrue(receipt.contains("slots=pack010-r1,pack010-r2,pack010-r3"));
    assertTrue(receipt.contains("repetitions=1,2,3"));
    assertTrue(receipt.contains("modelRequested=gpt-5.6-terra"));
    assertTrue(receipt.contains("dataSources=0 keyReads=0"));
    assertTrue(receipt.contains("httpRequests=0"));
    assertTrue(receipt.contains("graphAttemptsCreated=0"));
    assertTrue(receipt.contains("networkAllowed=false"));
    assertTrue(receipt.contains("realModelAllowed=false"));
    assertTrue(receipt.endsWith("shippingExecute=false"));
    assertFalse(receipt.contains("OPENAI_API_KEY"));
    assertFalse(receipt.contains("Authorization"));
    assertFalse(receipt.contains(Pack010GraphEvalCatalog.CONTENT));
  }

  @Test
  void rejectsPackAndEnvironmentByteDrift() throws Exception {
    Path packDrift = copyFrozenAssets(tempDir.resolve("pack-drift"));
    Files.writeString(
        packDrift.resolve(Pack010GraphEvalCatalog.PACK_PATH),
        "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack010GraphPreflight(packDrift).run());

    Path environmentDrift =
        copyFrozenAssets(tempDir.resolve("environment-drift"));
    Files.writeString(
        environmentDrift.resolve(
            Pack010GraphEvalCatalog.ENVIRONMENT_PATH),
        "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new Pack010GraphPreflight(environmentDrift).run());
  }

  @Test
  void rejectsSymlinksAndOversizedAssetsBeforeParsing()
      throws Exception {
    Path symlinkRoot = tempDir.resolve("symlink");
    copyAsset(
        Pack010GraphEvalCatalog.ENVIRONMENT_PATH,
        symlinkRoot.resolve(
            Pack010GraphEvalCatalog.ENVIRONMENT_PATH));
    Path link =
        symlinkRoot.resolve(Pack010GraphEvalCatalog.PACK_PATH);
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(
        link,
        repoRoot()
            .resolve(Pack010GraphEvalCatalog.PACK_PATH)
            .toAbsolutePath());
    assertRejected(
        "ASSET_SYMLINK_REJECTED",
        () -> new Pack010GraphPreflight(symlinkRoot).run());

    Path oversized = copyFrozenAssets(tempDir.resolve("oversized"));
    Files.write(
        oversized.resolve(Pack010GraphEvalCatalog.PACK_PATH),
        new byte[128 * 1024 + 1]);
    assertRejected(
        "ASSET_SIZE_INVALID",
        () -> new Pack010GraphPreflight(oversized).run());
  }

  @Test
  void strictPackParserRejectsSemanticDuplicateTrailingAndUnknownDrift()
      throws Exception {
    String pack =
        Files.readString(
            repoRoot().resolve(Pack010GraphEvalCatalog.PACK_PATH),
            StandardCharsets.UTF_8);
    String semantic =
        pack.replace("\"networkAllowed\": false", "\"networkAllowed\": true");
    assertRejected(
        "PACK010_SEMANTICS_MISMATCH",
        () ->
            Pack010GraphPreflight.verifyPackDocument(
                semantic.getBytes(StandardCharsets.UTF_8)));
    String missingSafetyFlag =
        pack.replace("      \"networkAllowed\": false,\n", "");
    assertFalse(pack.equals(missingSafetyFlag));
    assertRejected(
        "PACK010_INVALID",
        () ->
            Pack010GraphPreflight.verifyPackDocument(
                missingSafetyFlag.getBytes(StandardCharsets.UTF_8)));
    String duplicate =
        pack.replaceFirst(
            "\"schemaVersion\": \"0.9\",",
            "\"schemaVersion\": \"0.9\","
                + " \"schemaVersion\": \"0.9\",");
    assertRejected(
        "PACK010_INVALID",
        () ->
            Pack010GraphPreflight.verifyPackDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK010_INVALID",
        () ->
            Pack010GraphPreflight.verifyPackDocument(
                (pack + "{}").getBytes(StandardCharsets.UTF_8)));
    String unknown =
        pack.replaceFirst("\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "PACK010_INVALID",
        () ->
            Pack010GraphPreflight.verifyPackDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void strictEnvironmentParserRejectsEgressDuplicateTrailingAndUnknownDrift()
      throws Exception {
    String environment =
        Files.readString(
            repoRoot()
                .resolve(Pack010GraphEvalCatalog.ENVIRONMENT_PATH),
            StandardCharsets.UTF_8);
    String semantic =
        environment.replace(
            "\"shippingExecuteRouteEnabled\": false",
            "\"shippingExecuteRouteEnabled\": true");
    assertRejected(
        "ENVIRONMENT_SEMANTICS_MISMATCH",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                semantic.getBytes(StandardCharsets.UTF_8)));
    String missingZeroEffect =
        environment.replace("    \"networkCalls\": 0,\n", "");
    assertFalse(environment.equals(missingZeroEffect));
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                missingZeroEffect.getBytes(StandardCharsets.UTF_8)));
    String missingShippingGate =
        environment.replace(
            ",\n    \"shippingExecuteRouteEnabled\": false\n", "\n");
    assertFalse(environment.equals(missingShippingGate));
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                missingShippingGate.getBytes(StandardCharsets.UTF_8)));
    String duplicate =
        environment.replaceFirst(
            "\"schemaVersion\": \"0.2\",",
            "\"schemaVersion\": \"0.2\","
                + " \"schemaVersion\": \"0.2\",");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                (environment + "{}")
                    .getBytes(StandardCharsets.UTF_8)));
    String unknown =
        environment.replaceFirst(
            "\\{", "{\"unreviewedOverride\":true,");
    assertRejected(
        "ENVIRONMENT_INVALID",
        () ->
            Pack010GraphPreflight.verifyEnvironmentDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  private Path copyFrozenAssets(Path root) throws IOException {
    copyAsset(
        Pack010GraphEvalCatalog.PACK_PATH,
        root.resolve(Pack010GraphEvalCatalog.PACK_PATH));
    copyAsset(
        Pack010GraphEvalCatalog.ENVIRONMENT_PATH,
        root.resolve(Pack010GraphEvalCatalog.ENVIRONMENT_PATH));
    return root;
  }

  private void copyAsset(String relative, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    Files.copy(repoRoot().resolve(relative), target);
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    Pack010GraphPreflight.Rejected rejected =
        assertThrows(Pack010GraphPreflight.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }

  private static Path repoRoot() {
    Path configured =
        Path.of(System.getProperty("emerge.graph.repo", "."))
            .toAbsolutePath()
            .normalize();
    if (Files.isRegularFile(configured.resolve("pom.xml"))
        && Files.isDirectory(configured.resolve("evals/task-packs"))) {
      return configured;
    }
    throw new IllegalStateException("repository root not found");
  }
}
