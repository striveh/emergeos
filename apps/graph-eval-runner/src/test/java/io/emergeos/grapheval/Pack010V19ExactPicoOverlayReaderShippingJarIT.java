package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class Pack010V19ExactPicoOverlayReaderShippingJarIT {

  private static final List<String> CORE_CLASS_ARTIFACTS =
      List.of(
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayAttribution.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayRequirement.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlaySnapshot.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification$Attributed.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification$Invalid.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification$InvalidReason.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification$Missing.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification$Required.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoOverlayVerification.class",
          "io/emergeos/core/port/GraphExactPicoOverlayReader.class");
  private static final List<String> CORE_CLASS_PREFIXES =
      List.of(
          "io/emergeos/core/domain/GraphExactPicoOverlayAttribution",
          "io/emergeos/core/domain/GraphExactPicoOverlayRequirement",
          "io/emergeos/core/domain/GraphExactPicoOverlaySnapshot",
          "io/emergeos/core/domain/GraphExactPicoOverlayVerification",
          "io/emergeos/core/port/GraphExactPicoOverlayReader");
  private static final List<String> READER_CLASS_ARTIFACTS =
      List.of(
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$EventRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$HeadRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$KeyRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$LegacyAttributionRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$ManifestRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$OverlayAttributionRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$OverlayEventRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$OverlayHeadRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$PolicyRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$ProfileRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$RequirementRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$RuntimeIdentity.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$SessionRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$ValidationRow.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader$VerifiedBase.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReader.class");
  private static final String READER_CLASS_PREFIX =
      "io/emergeos/adapters/postgres/PostgresExactPicoOverlayReader";
  private static final List<String> TEST_PREFIXES =
      List.of(
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReaderSurfaceTest",
          "io/emergeos/core/domain/GraphExactPicoOverlayTypesTest",
          "io/emergeos/core/port/"
              + "GraphExactPicoOverlayReaderSurfaceTest",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayReaderAcceptanceIT",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayReaderMain");
  private static final List<String> PROVISIONING_RESOURCES =
      List.of(
          "db/provisioning/pack010_runtime_roles.sql",
          "db/provisioning/pack010_runtime_roles_check.sql");
  private static final List<ReviewedMember> APP_FORBIDDEN_MEMBERS =
      List.of(
          new ReviewedMember(
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoOverlayReader",
              "open",
              "(Ljavax/sql/DataSource;)Lio/emergeos/core/port/"
                  + "GraphExactPicoOverlayReader;"),
          new ReviewedMember(
              "io/emergeos/adapters/postgres/"
                  + "PostgresExactPicoOverlayReader",
              "findVerified",
              readerDescriptor()),
          new ReviewedMember(
              "io/emergeos/core/port/GraphExactPicoOverlayReader",
              "findVerified",
              readerDescriptor()));

  @Test
  void actualJarContainsOnlyTheFrozenV19ReadSurfaceAndNoAppConsumer()
      throws IOException {
    Path repo =
        Path.of(System.getProperty("emerge.graph.it.repo"))
            .toAbsolutePath()
            .normalize();
    Path appJar =
        Path.of(System.getProperty("emerge.graph.it.jar"))
            .toAbsolutePath()
            .normalize();
    Path coreClasses = repo.resolve("modules/core/target/classes");
    Path postgresClasses =
        repo.resolve("adapters/postgres/target/classes");
    Path coreJar =
        uniqueJar(repo.resolve("modules/core/target"), "emerge-core-");
    Path postgresJar =
        uniqueJar(
            repo.resolve("adapters/postgres/target"),
            "emerge-adapter-postgres-");
    assertTrue(Files.isRegularFile(appJar), appJar.toString());

    List<String> exactCoreArtifacts = sorted(CORE_CLASS_ARTIFACTS);
    assertEquals(
        exactCoreArtifacts,
        classFiles(coreClasses, CORE_CLASS_PREFIXES),
        "core target class surface");
    assertEquals(
        exactCoreArtifacts,
        jarClassEntries(coreJar, CORE_CLASS_PREFIXES),
        "core module class surface");
    assertEquals(
        exactCoreArtifacts,
        jarClassEntries(appJar, CORE_CLASS_PREFIXES),
        "core app class surface");

    List<String> exactReaderArtifacts = sorted(READER_CLASS_ARTIFACTS);
    assertEquals(
        exactReaderArtifacts,
        classFiles(postgresClasses, List.of(READER_CLASS_PREFIX)),
        "reader target class surface");
    assertEquals(
        exactReaderArtifacts,
        jarClassEntries(postgresJar, List.of(READER_CLASS_PREFIX)),
        "reader module class surface");
    assertEquals(
        exactReaderArtifacts,
        jarClassEntries(appJar, List.of(READER_CLASS_PREFIX)),
        "reader app class surface");

    for (String className : CORE_CLASS_ARTIFACTS) {
      assertClassParity(coreClasses, coreJar, appJar, className);
    }
    for (String className : READER_CLASS_ARTIFACTS) {
      assertClassParity(postgresClasses, postgresJar, appJar, className);
    }
    assertProvisioningResourceParity(repo, postgresJar, appJar);

    try (JarFile jar = new JarFile(appJar.toFile())) {
      for (String testPrefix : TEST_PREFIXES) {
        assertFalse(
            jar.stream()
                .map(JarEntry::getName)
                .anyMatch(name -> name.startsWith(testPrefix)),
            testPrefix);
      }
    }
    assertEquals(List.of(), appConsumers(appJar));
    assertEquals(List.of(), GraphEvalBytecodeGate.shippingJarViolations(appJar));

    System.out.println(
        "PACK010_V19_EXACT_PICO_OVERLAY_READER_ARTIFACT_RECEIPT"
            + " publicCoreTypes=5 coreClassArtifacts=10"
            + " readerClassArtifacts=16 exactTableSurface=13"
            + " shippingAppOpenFindConsumer=0"
            + " writerSignerStageCommitGuardConsumer=0"
            + " classParity=TARGET_MODULE_APP"
            + " provisioningResourceParity=SOURCE_TARGET_MODULE_APP"
            + " testAcceptanceMainSurface=ABSENT"
            + " externalConfiguration=NOT_PROVEN"
            + " externalRuntimeInvocation=NOT_PROVEN"
            + " shippingLiveRoute=DISABLED");
  }

  private static void assertProvisioningResourceParity(
      Path repo, Path postgresJar, Path appJar) throws IOException {
    Path sourceRoot =
        repo.resolve("adapters/postgres/src/main/resources");
    Path targetRoot = repo.resolve("adapters/postgres/target/classes");
    for (String resource : PROVISIONING_RESOURCES) {
      byte[] source = Files.readAllBytes(sourceRoot.resolve(resource));
      assertArrayEquals(
          source,
          Files.readAllBytes(targetRoot.resolve(resource)),
          resource + " source/target");
      assertArrayEquals(
          source, entry(postgresJar, resource), resource + " source/module");
      assertArrayEquals(
          source, entry(appJar, resource), resource + " source/app");
    }
  }

  private static List<String> appConsumers(Path appJar) throws IOException {
    List<String> consumers = new ArrayList<>();
    try (JarFile jar = new JarFile(appJar.toFile())) {
      for (JarEntry entry :
          jar.stream()
              .filter(candidate -> !candidate.isDirectory())
              .filter(
                  candidate ->
                      candidate.getName().startsWith(
                          "io/emergeos/grapheval/"))
              .filter(candidate -> candidate.getName().endsWith(".class"))
              .toList()) {
        byte[] bytes;
        try (var input = jar.getInputStream(entry)) {
          bytes = input.readAllBytes();
        }
        for (GraphEvalBytecodeGate.MemberReference reference :
            GraphEvalBytecodeGate.memberReferences(bytes)) {
          for (ReviewedMember forbidden : APP_FORBIDDEN_MEMBERS) {
            if (forbidden.matches(reference)) {
              consumers.add(
                  entry.getName()
                      + " -> "
                      + reference.owner()
                      + "."
                      + reference.name()
                      + reference.descriptor());
            }
          }
        }
      }
    }
    return consumers.stream().sorted().distinct().toList();
  }

  private static void assertClassParity(
      Path classes, Path moduleJar, Path appJar, String className)
      throws IOException {
    byte[] target = Files.readAllBytes(classes.resolve(className));
    assertArrayEquals(
        target, entry(moduleJar, className), className + " target/module");
    assertArrayEquals(
        target, entry(appJar, className), className + " target/app");
  }

  private static List<String> classFiles(
      Path classes, List<String> prefixes) throws IOException {
    try (Stream<Path> paths = Files.walk(classes)) {
      return paths
          .filter(Files::isRegularFile)
          .map(classes::relativize)
          .map(Path::toString)
          .map(name -> name.replace('\\', '/'))
          .filter(name -> name.endsWith(".class"))
          .filter(name -> prefixes.stream().anyMatch(name::startsWith))
          .sorted()
          .toList();
    }
  }

  private static List<String> jarClassEntries(
      Path jarPath, List<String> prefixes) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      return jar.stream()
          .filter(entry -> !entry.isDirectory())
          .map(JarEntry::getName)
          .filter(name -> name.endsWith(".class"))
          .filter(name -> prefixes.stream().anyMatch(name::startsWith))
          .sorted()
          .toList();
    }
  }

  private static byte[] entry(Path jarPath, String name) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry entry = jar.getJarEntry(name);
      assertTrue(entry != null, name + " in " + jarPath);
      try (var input = jar.getInputStream(entry)) {
        return input.readAllBytes();
      }
    }
  }

  private static Path uniqueJar(Path target, String prefix)
      throws IOException {
    try (Stream<Path> paths = Files.list(target)) {
      List<Path> matches =
          paths
              .filter(Files::isRegularFile)
              .filter(
                  path -> path.getFileName().toString().startsWith(prefix))
              .filter(
                  path -> path.getFileName().toString().endsWith(".jar"))
              .filter(
                  path ->
                      !path.getFileName().toString().contains("sources"))
              .filter(
                  path ->
                      !path.getFileName().toString().contains("javadoc"))
              .toList();
      assertEquals(1, matches.size(), matches.toString());
      return matches.getFirst();
    }
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted().toList();
  }

  private static String readerDescriptor() {
    return "(Lio/emergeos/core/domain/GraphAttemptManifest;)"
        + "Lio/emergeos/core/domain/GraphExactPicoOverlayVerification;";
  }

  private record ReviewedMember(
      String owner, String name, String descriptor) {

    private boolean matches(
        GraphEvalBytecodeGate.MemberReference reference) {
      return owner.equals(reference.owner())
          && name.equals(reference.name())
          && descriptor.equals(reference.descriptor());
    }
  }
}
