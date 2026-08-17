package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class Pack010V18TypedAttestorShippingJarIT {

  private static final List<String> PRODUCTION_CLASSES =
      List.of(
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$AuthorityProbe.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$LocalFailure.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$Probe.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$ProbePoint.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$RuntimeIdentity.class",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor$Stage.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoProviderValidationCommand.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoProviderValidationReceipt.class",
          "io/emergeos/core/domain/"
              + "GraphExactPicoProviderValidationReceipt$ValidationState.class",
          "io/emergeos/core/port/"
              + "GraphExactPicoProviderValidationAttestor.class",
          "io/emergeos/core/port/"
              + "GraphExactPicoProviderValidationSigner.class");
  private static final List<String> TEST_PREFIXES =
      List.of(
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestorTestAccess",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestorSurfaceTest",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayTxAAcceptanceTest");

  @Test
  void actualJarContainsReviewedDormantTypedAuthorityWithNoAppConsumer()
      throws IOException {
    Path repo =
        Path.of(System.getProperty("emerge.graph.it.repo"))
            .toAbsolutePath()
            .normalize();
    Path appJar =
        Path.of(System.getProperty("emerge.graph.it.jar"))
            .toAbsolutePath()
            .normalize();
    Path coreJar = uniqueJar(repo.resolve("modules/core/target"), "emerge-core-");
    Path postgresJar =
        uniqueJar(
            repo.resolve("adapters/postgres/target"),
            "emerge-adapter-postgres-");
    assertTrue(Files.isRegularFile(appJar), appJar.toString());
    try (JarFile jar = new JarFile(appJar.toFile())) {
      for (String production : PRODUCTION_CLASSES) {
        assertTrue(jar.getJarEntry(production) != null, production);
      }
      for (String testPrefix : TEST_PREFIXES) {
        assertFalse(
            jar.stream()
                .map(JarEntry::getName)
                .anyMatch(name -> name.startsWith(testPrefix)),
            testPrefix);
      }
    }
    for (String postgresClass : PRODUCTION_CLASSES.subList(0, 7)) {
      assertParity(
          repo.resolve("adapters/postgres/target/classes"),
          postgresJar,
          appJar,
          postgresClass);
    }
    for (String coreClass :
        PRODUCTION_CLASSES.subList(7, PRODUCTION_CLASSES.size())) {
      assertParity(
          repo.resolve("modules/core/target/classes"),
          coreJar,
          appJar,
          coreClass);
    }
    assertEquals(List.of(), GraphEvalBytecodeGate.shippingJarViolations(appJar));
    assertEquals(
        List.of(
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoProviderValidationAttestor.class",
            "io/emergeos/core/port/"
                + "GraphExactPicoProviderValidationAttestor.class",
            "io/emergeos/core/port/"
                + "GraphExactPicoProviderValidationSigner.class"),
        classesContaining(appJar, "GraphExactPicoProviderValidationSigner"));
    assertEquals(
        List.of(
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoProviderValidationAttestor.class",
            "io/emergeos/core/port/"
                + "GraphExactPicoProviderValidationAttestor.class"),
        classesContaining(appJar, "GraphExactPicoProviderValidationAttestor"));
    System.out.println(
        "PACK010_V18_TYPED_ATTESTOR_ARTIFACT_RECEIPT"
            + " packagedTypedAuthorityAdapter=1"
            + " shippingAppRouteConsumer=0 shippingSignerImplementation=0"
            + " publicJavaRawStageMethod=SURFACE_TEST_PROVEN_ZERO"
            + " publicJavaRawCommitMethod=SURFACE_TEST_PROVEN_ZERO"
            + " classParity=TARGET_MODULE_APP"
            + " testBridgeAcceptance=ABSENT"
            + " externalConfiguration=NOT_PROVEN"
            + " externalRuntimeInvocation=NOT_PROVEN"
            + " shippingLiveRoute=DISABLED");
  }

  private static List<String> classesContaining(Path jarPath, String token)
      throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      List<String> names =
          jar.stream()
              .filter(entry -> !entry.isDirectory())
              .filter(entry -> entry.getName().endsWith(".class"))
              .filter(
                  entry -> {
                    try (var input = jar.getInputStream(entry)) {
                      return new String(
                              input.readAllBytes(),
                              java.nio.charset.StandardCharsets.ISO_8859_1)
                          .contains(token);
                    } catch (IOException failure) {
                      throw new java.io.UncheckedIOException(failure);
                    }
                  })
              .map(JarEntry::getName)
              .sorted()
              .toList();
      return names;
    } catch (java.io.UncheckedIOException failure) {
      throw failure.getCause();
    }
  }

  private static void assertParity(
      Path classes, Path moduleJar, Path appJar, String className)
      throws IOException {
    byte[] target = Files.readAllBytes(classes.resolve(className));
    assertArrayEquals(target, entry(moduleJar, className));
    assertArrayEquals(target, entry(appJar, className));
  }

  private static byte[] entry(Path jarPath, String name) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      JarEntry entry = jar.getJarEntry(name);
      assertTrue(entry != null, name);
      try (var input = jar.getInputStream(entry)) {
        return input.readAllBytes();
      }
    }
  }

  private static Path uniqueJar(Path target, String prefix)
      throws IOException {
    try (var paths = Files.list(target)) {
      List<Path> matches =
          paths.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().startsWith(prefix))
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .filter(path -> !path.getFileName().toString().contains("sources"))
              .filter(path -> !path.getFileName().toString().contains("javadoc"))
              .toList();
      assertEquals(1, matches.size(), matches.toString());
      return matches.getFirst();
    }
  }
}
