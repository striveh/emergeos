package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Pack010V17VerifierShippingJarIT {

  private static final String VERIFIER_CLASS =
      "io/emergeos/core/domain/"
          + "GraphExactPicoProviderSignatureVerifier.class";
  private static final String CHALLENGE_CLASS =
      "io/emergeos/core/domain/"
          + "GraphExactPicoProviderValidationChallenge.class";
  private static final String VERIFIER_TEST_CLASS =
      "io/emergeos/core/domain/"
          + "GraphExactPicoProviderSignatureVerifierTest.class";
  private static final String ACCEPTANCE_TEST_PREFIX =
      "io/emergeos/grapheval/"
          + "Pack010ExactPicoOverlayTxAAcceptanceTest";
  private static final List<String> PRIVATE_KEY_HEADERS =
      List.of(
          "-----BEGIN PRIVATE KEY-----",
          "-----BEGIN RSA PRIVATE KEY-----",
          "-----BEGIN EC PRIVATE KEY-----",
          "-----BEGIN OPENSSH PRIVATE KEY-----");
  private static final Pattern CREDENTIAL_VALUE =
      Pattern.compile("(?:^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}(?:$|[^A-Za-z0-9_-])");

  @Test
  void currentShadedJarRetainsTheReviewedVerifierWithoutPrivateMaterial()
      throws IOException {
    Path jarPath =
        Path.of(System.getProperty("emerge.graph.it.jar"))
            .toAbsolutePath()
            .normalize();
    assertTrue(Files.isRegularFile(jarPath), jarPath.toString());
    Path repo =
        Path.of(System.getProperty("emerge.graph.it.repo"))
            .toAbsolutePath()
            .normalize();
    Path coreJar = uniqueCoreJar(repo.resolve("modules/core/target"));

    int privateKeyHeadersOrNamedResources = 0;
    int deepSeekStyleCredentialValueMarkers = 0;
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      assertTrue(jar.getJarEntry(VERIFIER_CLASS) != null, VERIFIER_CLASS);
      assertTrue(jar.getJarEntry(CHALLENGE_CLASS) != null, CHALLENGE_CLASS);
      assertFalse(
          jar.getJarEntry(VERIFIER_TEST_CLASS) != null,
          VERIFIER_TEST_CLASS);
      assertTrue(
          jar.stream()
              .map(JarEntry::getName)
              .noneMatch(name -> name.startsWith(ACCEPTANCE_TEST_PREFIX)),
          ACCEPTANCE_TEST_PREFIX);
      for (JarEntry entry :
          jar.stream().filter(candidate -> !candidate.isDirectory()).toList()) {
        String lowerName = entry.getName().toLowerCase(Locale.ROOT);
        if ((lowerName.endsWith(".pem") || lowerName.endsWith(".key"))
            && lowerName.contains("private")) {
          privateKeyHeadersOrNamedResources++;
        }
        byte[] bytes;
        try (var input = jar.getInputStream(entry)) {
          bytes = input.readAllBytes();
        }
        String material = new String(bytes, StandardCharsets.ISO_8859_1);
        if (PRIVATE_KEY_HEADERS.stream().anyMatch(material::contains)) {
          privateKeyHeadersOrNamedResources++;
        }
        if (CREDENTIAL_VALUE.matcher(material).find()) {
          deepSeekStyleCredentialValueMarkers++;
        }
      }
    }
    assertClassParity(repo, coreJar, jarPath, VERIFIER_CLASS);
    assertClassParity(repo, coreJar, jarPath, CHALLENGE_CLASS);
    assertEquals(0, privateKeyHeadersOrNamedResources);
    assertEquals(0, deepSeekStyleCredentialValueMarkers);
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.shippingJarViolations(jarPath));
    System.out.println(
        "PACK010_V17_VERIFIER_ARTIFACT_RECEIPT"
            + " packagedVerifierPrimitive=1 reviewedTypedConsumer=1"
            + " shippingAppRouteConsumer=0"
            + " firstPartyPrivateKeyJcaReferences=0"
            + " privateKeyPemHeadersAndNamedResources=0"
            + " deepSeekStyleCredentialValueMarkers=0"
            + " testSignerAndAcceptance=ABSENT"
            + " postgresNativeSignatureVerify=0 shippingLive=DISABLED");
  }

  private static void assertClassParity(
      Path repo, Path coreJar, Path appJar, String className)
      throws IOException {
    byte[] target =
        Files.readAllBytes(
            repo.resolve("modules/core/target/classes").resolve(className));
    byte[] core;
    try (JarFile jar = new JarFile(coreJar.toFile())) {
      JarEntry entry = jar.getJarEntry(className);
      assertTrue(entry != null, className + " core");
      try (var input = jar.getInputStream(entry)) {
        core = input.readAllBytes();
      }
    }
    byte[] app;
    try (JarFile jar = new JarFile(appJar.toFile())) {
      JarEntry entry = jar.getJarEntry(className);
      assertTrue(entry != null, className + " app");
      try (var input = jar.getInputStream(entry)) {
        app = input.readAllBytes();
      }
    }
    assertArrayEquals(target, core, className + " target/core");
    assertArrayEquals(target, app, className + " target/app");
  }

  private static Path uniqueCoreJar(Path target) throws IOException {
    try (var paths = Files.list(target)) {
      List<Path> matches =
          paths.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().startsWith("emerge-core-"))
              .filter(path -> path.getFileName().toString().endsWith(".jar"))
              .filter(path -> !path.getFileName().toString().contains("sources"))
              .filter(path -> !path.getFileName().toString().contains("javadoc"))
              .toList();
      assertEquals(1, matches.size(), matches.toString());
      return matches.getFirst();
    }
  }
}
