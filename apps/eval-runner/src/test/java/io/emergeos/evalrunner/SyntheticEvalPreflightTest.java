package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticEvalPreflightTest {

  @TempDir Path tempDir;

  @Test
  void acceptsOnlyTheFrozenCheckedInAssets() {
    SyntheticEvalPreflight.Result result =
        new SyntheticEvalPreflight(repoRoot()).run();

    assertTrue(result.receipt().contains("status=PREFLIGHT_READY"));
    assertTrue(
        result
            .receipt()
            .contains(
                "taskHash="
                    + SyntheticEvalCatalog.EXPECTED_TASK_HASH));
  }

  @Test
  void rejectsPackOrEnvironmentByteDrift() throws Exception {
    Path packRoot = copyFrozenAssets(tempDir.resolve("pack-drift"));
    Files.writeString(
        packRoot.resolve(SyntheticEvalCatalog.PACK_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new SyntheticEvalPreflight(packRoot).run());

    Path environmentRoot =
        copyFrozenAssets(tempDir.resolve("environment-drift"));
    Files.writeString(
        environmentRoot.resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH),
        "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
    assertRejected(
        "ASSET_HASH_MISMATCH",
        () -> new SyntheticEvalPreflight(environmentRoot).run());
  }

  @Test
  void rejectsSymlinkedOrOversizedAssetsBeforeParsing() throws Exception {
    Path symlinkRoot = tempDir.resolve("symlink");
    copyAsset(
        SyntheticEvalCatalog.ENVIRONMENT_PATH,
        symlinkRoot.resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH));
    Path packLink = symlinkRoot.resolve(SyntheticEvalCatalog.PACK_PATH);
    Files.createDirectories(packLink.getParent());
    Files.createSymbolicLink(
        packLink,
        repoRoot()
            .resolve(SyntheticEvalCatalog.PACK_PATH)
            .toAbsolutePath());
    assertRejected(
        "ASSET_SYMLINK_REJECTED",
        () -> new SyntheticEvalPreflight(symlinkRoot).run());

    Path oversizedRoot = tempDir.resolve("oversized");
    copyAsset(
        SyntheticEvalCatalog.ENVIRONMENT_PATH,
        oversizedRoot.resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH));
    Path oversizedPack =
        oversizedRoot.resolve(SyntheticEvalCatalog.PACK_PATH);
    Files.createDirectories(oversizedPack.getParent());
    Files.write(oversizedPack, new byte[128 * 1024 + 1]);
    assertRejected(
        "ASSET_SIZE_INVALID",
        () -> new SyntheticEvalPreflight(oversizedRoot).run());
  }

  @Test
  void strictJsonRejectsDuplicateKeysTrailingTokensAndUnknownFields()
      throws Exception {
    String pack =
        Files.readString(
            repoRoot().resolve(SyntheticEvalCatalog.PACK_PATH),
            StandardCharsets.UTF_8);
    String duplicate =
        pack.replaceFirst(
            "\\\"schemaVersion\\\": \\\"0.2\\\",",
            "\"schemaVersion\": \"0.2\", \"schemaVersion\": \"0.2\",");
    assertRejected(
        "PACK_INVALID",
        () ->
            SyntheticEvalPreflight.verifyPackDocument(
                duplicate.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK_INVALID",
        () ->
            SyntheticEvalPreflight.verifyPackDocument(
                (pack + "{}").getBytes(StandardCharsets.UTF_8)));
    String unknown =
        pack.replaceFirst(
            "\\{",
            "{\"unreviewedOverride\":true,");
    assertRejected(
        "PACK_INVALID",
        () ->
            SyntheticEvalPreflight.verifyPackDocument(
                unknown.getBytes(StandardCharsets.UTF_8)));
  }

  private Path copyFrozenAssets(Path root) throws IOException {
    copyAsset(
        SyntheticEvalCatalog.PACK_PATH,
        root.resolve(SyntheticEvalCatalog.PACK_PATH));
    copyAsset(
        SyntheticEvalCatalog.ENVIRONMENT_PATH,
        root.resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH));
    return root;
  }

  private void copyAsset(String relative, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    Files.copy(repoRoot().resolve(relative), target);
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    SyntheticEvalPreflight.Rejected rejected =
        assertThrows(SyntheticEvalPreflight.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }

  private static Path repoRoot() {
    Path current =
        Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
    while (current != null) {
      if (Files.isRegularFile(
              current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("evals/task-packs"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
