package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
