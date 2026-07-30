package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack004LoaderTest {

  private static final Path SOURCE_PACK =
      Path.of(
              System.getProperty("emerge.offline.repo"),
              "evals/task-packs/synthetic/"
                  + "004-offline-reference-verifier-comparison.json")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDirectory;

  @Test
  void loadsOnlyTheHashBoundFrozenPack004() throws IOException {
    Path repository = repositoryWith(Files.readAllBytes(SOURCE_PACK));

    Pack004Loader.LoadedPack loaded = new Pack004Loader().load(repository);

    assertEquals(Pack004Loader.EXPECTED_RAW_SHA256, loaded.rawSha256());
    assertEquals(
        "offline-reference-grounding-verifier-v1",
        loaded.pack().harnessComparison().suiteId());
    assertEquals(2, loaded.pack().harnessComparison().arms().size());
    assertEquals(4, loaded.pack().harnessComparison().cases().size());
    assertEquals(3, loaded.pack().harnessComparison().repetitions());
    assertFalse(
        loaded.pack().harnessComparison().provenance().networkAllowed());
  }

  @Test
  void rejectsRawByteDriftEvenWhenJsonAndSemanticsAreUnchanged()
      throws IOException {
    byte[] original = Files.readAllBytes(SOURCE_PACK);
    byte[] drifted =
        (new String(original, StandardCharsets.UTF_8) + " \n")
            .getBytes(StandardCharsets.UTF_8);

    assertRejected("PACK_RAW_SHA256_MISMATCH", repositoryWith(drifted));
  }

  @Test
  void rejectsDuplicateKeys() throws IOException {
    String duplicate =
        sourceText()
            .replace(
                "\"schemaVersion\": \"0.3\",",
                "\"schemaVersion\": \"0.3\",\n"
                    + "  \"schemaVersion\": \"0.3\",");

    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(duplicate.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsUnknownProperties() throws IOException {
    String source = sourceText();
    String unknown =
        "{\n  \"unreviewedOverride\": true,"
            + source.substring(1);

    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(unknown.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsMissingNullReferenceAndNullPrimitiveProperties()
      throws IOException {
    String source = sourceText();
    String missingReference =
        source.replace(
            "  \"title\": "
                + "\"离线比较 schema-only 与 reference-grounding Verifier\",\n",
            "");
    String nullReference =
        source.replace(
            "\"title\": "
                + "\"离线比较 schema-only 与 reference-grounding Verifier\"",
            "\"title\": null");
    String nullPrimitive =
        source.replace("\"repetitions\": 3", "\"repetitions\": null");

    assertFalse(source.equals(missingReference));
    assertFalse(source.equals(nullReference));
    assertFalse(source.equals(nullPrimitive));
    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(
            missingReference.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(
            nullReference.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(
            nullPrimitive.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsNullOrDriftedNarrativeSafetyArrays() throws IOException {
    String source = sourceText();
    int acceptanceStart =
        source.indexOf("  \"acceptanceChecks\": [");
    int humanReviewStart =
        source.indexOf("  \"humanReviewQuestions\": [");
    assertTrue(acceptanceStart >= 0);
    assertTrue(humanReviewStart > acceptanceStart);
    String nullAcceptance =
        source.substring(0, acceptanceStart)
            + "  \"acceptanceChecks\": null,\n"
            + source.substring(humanReviewStart);
    String driftedHumanReview =
        source.replace(
            "这组对照是否只改变了 reference-grounding Verifier，"
                + "而没有混入 Planner、Critic 或 Subagent？",
            "这组对照可以同时改变 Planner 与 Verifier 吗？");
    String driftedFaultPlan =
        source.replace(
            "claim-without-tool candidate 未执行读取却直接声称引用 Capture",
            "claim-without-tool candidate 已执行读取并引用 Capture");

    assertFalse(source.equals(nullAcceptance));
    assertFalse(source.equals(driftedHumanReview));
    assertFalse(source.equals(driftedFaultPlan));
    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(
            nullAcceptance.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK_SEMANTICS_MISMATCH",
        repositoryWith(
            driftedHumanReview.getBytes(StandardCharsets.UTF_8)));
    assertRejected(
        "PACK_SEMANTICS_MISMATCH",
        repositoryWith(
            driftedFaultPlan.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsTrailingJsonTokens() throws IOException {
    String trailing = sourceText() + "\n{\"secondDocument\":true}\n";

    assertRejected(
        "PACK_JSON_INVALID",
        repositoryWith(trailing.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsSemanticDriftBeforeItCanBecomeAnExperiment()
      throws IOException {
    String drifted =
        sourceText()
            .replace(
                "\"variable\": \"reference-grounding-verifier\"",
                "\"variable\": \"planner-and-verifier\"");

    assertRejected(
        "PACK_SEMANTICS_MISMATCH",
        repositoryWith(drifted.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsAnyRealDataAccountNetworkModelOrConnectorPermission()
      throws IOException {
    String source = sourceText();
    for (String field :
        new String[] {
          "containsRealUserData",
          "containsRealAccount",
          "networkAllowed",
          "realModelAllowed",
          "connectorAllowed"
        }) {
      assertSemanticMutation(
          source,
          source.replace(
              "\"" + field + "\": false",
              "\"" + field + "\": true"));
    }
  }

  @Test
  void rejectsFrozenVersionShapeAndSharedIdDrift() throws IOException {
    String source = sourceText();
    assertSemanticMutation(
        source,
        source.replace(
            "\"candidateGeneratorVersion\": "
                + "\"literal-reference-candidate-fixture-v1\"",
            "\"candidateGeneratorVersion\": "
                + "\"literal-reference-candidate-fixture-v2\""));
    assertSemanticMutation(
        source,
        source.replace("\"repetitions\": 3", "\"repetitions\": 4"));
    assertSemanticMutation(
        source,
        source.replace(
            "\"pairedExecutionIdsSharedAcrossArms\": true",
            "\"pairedExecutionIdsSharedAcrossArms\": false"));
    assertSemanticMutation(
        source,
        source.replace(
            "\"verifier\": \"agent-draft-verifier-v1\"",
            "\"verifier\": \"critic-plus-verifier-v1\""));
  }

  @Test
  void rejectsExpectedCaseMatrixDrift() throws IOException {
    String source = sourceText();
    assertSemanticMutation(
        source,
        source.replace(
            "\"failureReason\": \"MISSING_REQUIRED_EVIDENCE\"",
            "\"failureReason\": \"INVALID_EVIDENCE_CLAIM\""));
  }

  @Test
  void rejectsOversizedPackBeforeParsing() throws IOException {
    byte[] oversized = new byte[Pack004Loader.MAX_PACK_BYTES + 1];

    assertRejected("PACK_SIZE_INVALID", repositoryWith(oversized));
  }

  @Test
  void rejectsRepositoryRootSymlink() throws IOException {
    Path realRepository = repositoryWith(Files.readAllBytes(SOURCE_PACK));
    Path rootLink = tempDirectory.resolve("repository-link");
    Files.createSymbolicLink(rootLink, realRepository);

    assertRejected("REPO_ROOT_INVALID", rootLink);
  }

  @Test
  void rejectsIntermediatePathSymlink() throws IOException {
    Path repository = tempDirectory.resolve("intermediate-link-repository");
    Path outside = tempDirectory.resolve("outside-task-packs");
    Files.createDirectories(repository.resolve("evals"));
    writeFixedPack(outside, Files.readAllBytes(SOURCE_PACK), "synthetic");
    Files.createSymbolicLink(
        repository.resolve("evals/task-packs"), outside);

    assertRejected("PACK_PATH_SYMLINK_REJECTED", repository);
  }

  @Test
  void rejectsTargetSymlink() throws IOException {
    Path repository = tempDirectory.resolve("target-link-repository");
    Path parent =
        repository.resolve("evals/task-packs/synthetic");
    Files.createDirectories(parent);
    Path outside = tempDirectory.resolve("outside-pack.json");
    Files.write(outside, Files.readAllBytes(SOURCE_PACK));
    Files.createSymbolicLink(
        parent.resolve(
            "004-offline-reference-verifier-comparison.json"),
        outside);

    assertRejected("PACK_PATH_SYMLINK_REJECTED", repository);
  }

  @Test
  void exposesNoArbitraryPackPathEntryPoint() {
    List<java.lang.reflect.Method> entryPoints =
        Arrays.stream(Pack004Loader.class.getDeclaredMethods())
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .toList();
    assertEquals(
        1,
        entryPoints.size(),
        () -> "unexpected loader entry points: " + entryPoints);
    assertEquals("load", entryPoints.getFirst().getName());
    assertTrue(
        Arrays.equals(
            new Class<?>[] {Path.class},
            entryPoints.getFirst().getParameterTypes()));
    assertEquals(1, Pack004Loader.class.getDeclaredConstructors().length);
    assertEquals(
        0,
        Pack004Loader.class
            .getDeclaredConstructors()[0]
            .getParameterCount());
  }

  private String sourceText() throws IOException {
    return Files.readString(SOURCE_PACK, StandardCharsets.UTF_8);
  }

  private Path repositoryWith(byte[] bytes) throws IOException {
    Path repository =
        tempDirectory.resolve("repository-" + System.nanoTime());
    writeFixedPack(
        repository.resolve("evals/task-packs"),
        bytes,
        "synthetic");
    return repository;
  }

  private static void writeFixedPack(
      Path taskPacks, byte[] bytes, String finalDirectory)
      throws IOException {
    Path parent = taskPacks.resolve(finalDirectory);
    Files.createDirectories(parent);
    Files.write(
        parent.resolve(
            "004-offline-reference-verifier-comparison.json"),
        bytes);
  }

  private static void assertRejected(String code, Path repository) {
    Pack004Loader.Rejected failure =
        assertThrows(
            Pack004Loader.Rejected.class,
            () -> new Pack004Loader().load(repository));
    assertEquals(code, failure.code());
  }

  private void assertSemanticMutation(String original, String mutated)
      throws IOException {
    assertFalse(
        original.equals(mutated),
        "the test mutation must alter the frozen Pack");
    assertRejected(
        "PACK_SEMANTICS_MISMATCH",
        repositoryWith(mutated.getBytes(StandardCharsets.UTF_8)));
  }
}
