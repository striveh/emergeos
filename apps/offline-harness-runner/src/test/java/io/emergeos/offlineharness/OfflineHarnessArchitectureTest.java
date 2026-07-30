package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OfflineHarnessArchitectureTest {

  private static final Set<String> FORBIDDEN_EXACT_REFERENCES =
      Set.of(
          "java/lang/ProcessBuilder",
          "java/lang/Runtime",
          "java/nio/channels/AsynchronousServerSocketChannel",
          "java/nio/channels/AsynchronousSocketChannel",
          "java/nio/channels/DatagramChannel",
          "java/nio/channels/ServerSocketChannel",
          "java/nio/channels/SocketChannel",
          "java/nio/channels/spi/AsynchronousChannelProvider",
          "java/nio/channels/spi/SelectorProvider");

  private static final List<String> FORBIDDEN_PREFIXES =
      List.of(
          "java/net/",
          "javax/net/",
          "sun/net/",
          "java/rmi/");
  private static final List<String>
      PRODUCT_RUNTIME_DENIED_TYPE_PREFIXES =
          List.of(
              "io/emergeos/core/application/",
              "io/emergeos/core/domain/",
              "io/emergeos/core/port/",
              "io/emergeos/adapters/",
              "io/emergeos/api/",
              "io/emergeos/evalrunner/",
              "io/emergeos/contracts/AgentTrace",
              "io/emergeos/contracts/Harness",
              "io/emergeos/contracts/ResultEnvelope",
              "io/emergeos/contracts/TaskEnvelope",
              "io/emergeos/contracts/TraceEventType");
  private static final Set<String> ALLOWED_OFFLINE_DOMAIN_TYPES =
      Set.of(
          "io/emergeos/core/application/"
              + "AgentDraftReferenceGrounding",
          "io/emergeos/core/domain/AgentDraftProposal",
          "io/emergeos/core/domain/ArtifactLineageEntry");

  @Test
  void offlineProductionHasNoProductAgentRuntimeTypeReferences()
      throws IOException {
    Path productionClasses =
        Path.of(System.getProperty("emerge.offline.classes"))
            .toAbsolutePath()
            .normalize();

    List<String> violations =
        findProductRuntimeViolations(List.of(productionClasses));

    assertTrue(
        violations.isEmpty(),
        () -> "forbidden product runtime refs: " + violations);
  }

  @Test
  void independentVerifierDoesNotReferenceRunnerOrGenerator()
      throws IOException {
    Path productionClasses =
        Path.of(System.getProperty("emerge.offline.classes"))
            .toAbsolutePath()
            .normalize();
    Path verifierPackage =
        productionClasses.resolve(
            Path.of("io", "emergeos", "offlineharness"));
    List<String> constants = new ArrayList<>();
    try (var paths = Files.list(verifierPackage)) {
      for (Path classFile :
          paths
              .filter(
                  path ->
                      path.getFileName()
                              .toString()
                              .startsWith(
                                  "Pack004ComparisonReportVerifier")
                          && path.toString().endsWith(".class"))
              .toList()) {
        constants.addAll(
            classUtf8Constants(Files.readAllBytes(classFile)));
      }
    }

    assertFalse(
        constants.stream()
            .anyMatch(
                reference ->
                    reference.contains("Pack004ComparisonRunner")
                        || reference.contains(
                            "LiteralReferenceCandidateGenerator")),
        () -> "verifier common-mode refs: " + constants);
  }

  @Test
  void durableReadCommandDoesNotReferenceRunnerGeneratorOrWriter()
      throws IOException {
    Path productionClasses =
        Path.of(System.getProperty("emerge.offline.classes"))
            .toAbsolutePath()
            .normalize();
    Path packageDirectory =
        productionClasses.resolve(
            Path.of("io", "emergeos", "offlineharness"));
    List<String> constants = new ArrayList<>();
    try (var paths = Files.list(packageDirectory)) {
      for (Path classFile :
          paths
              .filter(
                  path ->
                      path.getFileName()
                              .toString()
                              .startsWith(
                                  "OfflineComparisonReadCommand")
                          && path.toString().endsWith(".class"))
              .toList()) {
        constants.addAll(
            classUtf8Constants(Files.readAllBytes(classFile)));
      }
    }

    assertFalse(
        constants.stream()
            .anyMatch(
                reference ->
                    reference.contains("Pack004ComparisonRunner")
                        || reference.contains(
                            "LiteralReferenceCandidateGenerator")
                        || reference.contains(
                            "OfflineComparisonWriteCommand")),
        () -> "durable read common-mode refs: " + constants);
  }

  @Test
  void offlineHarnessExportsOnlyThePackagedEntrypoint()
      throws Exception {
    Path productionClasses =
        Path.of(System.getProperty("emerge.offline.classes"))
            .toAbsolutePath()
            .normalize();
    Path packageDirectory =
        productionClasses.resolve(
            Path.of("io", "emergeos", "offlineharness"));
    Set<String> publicTopLevelTypes = new HashSet<>();
    try (var paths = Files.list(packageDirectory)) {
      for (Path classFile :
          paths
              .filter(
                  path ->
                      path.toString().endsWith(".class")
                          && !path.getFileName()
                              .toString()
                              .contains("$"))
              .toList()) {
        String simpleName =
            classFile
                .getFileName()
                .toString()
                .replaceFirst("\\.class$", "");
        Class<?> type =
            Class.forName(
                "io.emergeos.offlineharness." + simpleName,
                false,
                getClass().getClassLoader());
        if (Modifier.isPublic(type.getModifiers())) {
          publicTopLevelTypes.add(type.getName());
        }
      }
    }

    assertTrue(
        publicTopLevelTypes.equals(
            Set.of(
                "io.emergeos.offlineharness."
                    + "Pack004ComparisonMain")),
        () -> "unexpected public surface: " + publicTopLevelTypes);
  }

  @Test
  void productRuntimeClassifierDetectsDescriptorsAndReflectiveNames()
      throws IOException {
    Path testClasses =
        Path.of(System.getProperty("emerge.offline.testClasses"))
            .toAbsolutePath()
            .normalize();

    List<String> violations =
        findProductRuntimeViolations(List.of(testClasses));

    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains(
                            "ForbiddenProductRuntimeTypeFixture")
                        && violation.contains(
                            "Lio/emergeos/core/domain/AgentRun;")));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains(
                            "ForbiddenProductRuntimeTypeFixture")
                        && violation.contains(
                            "io.emergeos.core.application.AgentDraftService")));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains(
                            "ForbiddenProductRuntimeTypeFixture")
                        && violation.contains(
                            "Lio/emergeos/core/port/AgentKernel;")
                        && violation.contains(
                            "Lio/emergeos/contracts/HarnessRunBundle;")));
    assertFalse(
        violations.stream()
            .filter(
                violation ->
                    violation.contains(
                        "ForbiddenProductRuntimeTypeFixture"))
            .anyMatch(
                violation ->
                    violation.contains(
                            "AgentDraftReferenceGrounding")
                        || violation.contains("AgentDraftProposal")
                        || violation.contains("ArtifactLineageEntry")));
  }

  @Test
  void allowedProductionClosureHasNoKnownJdkNetworkOrProcessReferences()
      throws IOException {
    Path repository =
        Path.of(System.getProperty("emerge.offline.repo"))
            .toAbsolutePath()
            .normalize();
    List<Path> productionRoots =
        List.of(
            repository.resolve("modules/contracts/target/classes"),
            repository.resolve("modules/core/target/classes"),
            Path.of(System.getProperty("emerge.offline.classes"))
                .toAbsolutePath()
                .normalize());

    List<String> violations = findViolations(productionRoots);

    assertTrue(violations.isEmpty(), () -> "forbidden refs: " + violations);
  }

  @Test
  void classifierDetectsAsyncSocketAndProcessEscapeFixture()
      throws IOException {
    Path testClasses =
        Path.of(System.getProperty("emerge.offline.testClasses"))
            .toAbsolutePath()
            .normalize();
    List<String> violations = findViolations(List.of(testClasses));

    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains("ForbiddenJdkEscapeFixture")
                        && violation.contains(
                            "AsynchronousSocketChannel")));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains("ForbiddenJdkEscapeFixture")
                        && violation.contains("ProcessBuilder")));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains("ForbiddenJdkEscapeFixture")
                        && violation.contains("java.net.Socket")));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains("ForbiddenJdkEscapeFixture")
                        && violation.contains(
                            "java.lang.ProcessBuilder")));
  }

  private static List<String> findViolations(List<Path> roots)
      throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path root : roots) {
      assertTrue(Files.isDirectory(root), () -> "missing classes: " + root);
      try (var paths = Files.walk(root)) {
        for (Path classFile :
            paths.filter(path -> path.toString().endsWith(".class"))
                .toList()) {
          for (String reference :
              classUtf8Constants(Files.readAllBytes(classFile))) {
            if (isForbidden(reference)) {
              violations.add(
                  root.relativize(classFile)
                      + " references "
                      + reference);
            }
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<String> findProductRuntimeViolations(
      List<Path> roots) throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path root : roots) {
      assertTrue(
          Files.isDirectory(root), () -> "missing classes: " + root);
      try (var paths = Files.walk(root)) {
        for (Path classFile :
            paths.filter(path -> path.toString().endsWith(".class"))
                .toList()) {
          for (String reference :
              classUtf8Constants(Files.readAllBytes(classFile))) {
            if (containsDeniedProductRuntimeType(reference)) {
              violations.add(
                  root.relativize(classFile)
                      + " references "
                      + reference);
            }
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static boolean containsDeniedProductRuntimeType(
      String reference) {
    String normalized = reference.replace('.', '/');
    int searchFrom = 0;
    while (true) {
      int start = normalized.indexOf("io/emergeos/", searchFrom);
      if (start < 0) {
        return false;
      }
      int end = start;
      while (end < normalized.length()
          && isInternalTypeCharacter(normalized.charAt(end))) {
        end++;
      }
      String type = normalized.substring(start, end);
      if (!isAllowedOfflineDomainType(type)
          && PRODUCT_RUNTIME_DENIED_TYPE_PREFIXES.stream()
              .anyMatch(type::startsWith)) {
        return true;
      }
      searchFrom = Math.max(end, start + 1);
    }
  }

  private static boolean isAllowedOfflineDomainType(String type) {
    return ALLOWED_OFFLINE_DOMAIN_TYPES.stream()
        .anyMatch(
            allowed ->
                type.equals(allowed)
                    || type.startsWith(allowed + "$"));
  }

  private static boolean isInternalTypeCharacter(char value) {
    return value == '/'
        || value == '$'
        || value == '_'
        || Character.isLetterOrDigit(value);
  }

  private static boolean isForbidden(String reference) {
    return FORBIDDEN_EXACT_REFERENCES.stream()
            .anyMatch(
                forbidden ->
                    reference.equals(forbidden)
                        || reference.equals(
                            forbidden.replace('/', '.'))
                        || reference.contains("L" + forbidden + ";"))
        || FORBIDDEN_PREFIXES.stream()
            .anyMatch(
                forbidden ->
                    reference.startsWith(forbidden)
                        || reference.startsWith(
                            forbidden.replace('/', '.'))
                        || reference.contains("L" + forbidden));
  }

  private static List<String> classUtf8Constants(byte[] classBytes)
      throws IOException {
    List<String> constants = new ArrayList<>();
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("CLASS_MAGIC_INVALID");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> constants.add(input.readUTF());
          case 3, 4 -> input.skipNBytes(4);
          case 5, 6 -> {
            input.skipNBytes(8);
            index++;
          }
          case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
          case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
          case 15 -> input.skipNBytes(3);
          default -> throw new IOException("CLASS_CONSTANT_TAG_INVALID");
        }
      }
    }
    return List.copyOf(constants);
  }
}
