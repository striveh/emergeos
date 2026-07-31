package io.emergeos.grapheval;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Deterministic classfile gate for the Pack009 composition boundary.
 *
 * <p>Maven Enforcer rejects forbidden artifacts. This gate independently
 * rejects first-party bytecode references and forbidden shaded package
 * entries, including references introduced without a normal Maven dependency.
 */
final class GraphEvalBytecodeGate {

  private static final String MULTI_RELEASE_ROOT =
      "META-INF/versions/";
  private static final String APP_CLASS_ROOT =
      "io/emergeos/grapheval/";
  private static final String FIRST_PARTY_ROOT =
      "io/emergeos/";
  private static final Set<String> ALLOWED_APP_CLASSES =
      Set.of(
          "io/emergeos/grapheval/GraphEvalCli$Mode.class",
          "io/emergeos/grapheval/GraphEvalCli.class",
          "io/emergeos/grapheval/GraphEvalMain.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphEvalCatalog.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "EnvironmentManifest.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "InputTokenUpperBound.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Model.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "ModelWorkerEval.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "OperatorGate.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Pricing.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "PromptCachePolicy.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "Provenance.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "RequestPolicy.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Result.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Seed.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$TaskPack.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier$Result.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier.class");
  private static final List<String> ALLOWED_FIRST_PARTY =
      List.of(
          "io/emergeos/grapheval/",
          "io/emergeos/contracts/",
          "io/emergeos/core/",
          "io/emergeos/adapters/agentloop/",
          "io/emergeos/adapters/openai/",
          "io/emergeos/adapters/postgres/");
  private static final List<String> FORBIDDEN_EXTERNAL =
      List.of(
          "org/springframework/boot/",
          "org/springframework/web/",
          "org/springframework/ai/",
          "io/temporal/",
          "org/hibernate/",
          "jakarta/persistence/",
          "org/langchain4j/",
          "dev/langchain4j/",
          "com/microsoft/semantickernel/");
  private static final List<String> FORBIDDEN_ARCHIVE_ENTRIES =
      List.of(
          "io/emergeos/api/",
          "io/emergeos/evalrunner/",
          "io/emergeos/offlineharness/",
          "io/emergeos/adapters/inmemory/",
          "org/springframework/boot/",
          "org/springframework/web/",
          "org/springframework/ai/",
          "io/temporal/",
          "org/hibernate/",
          "jakarta/persistence/",
          "org/langchain4j/",
          "dev/langchain4j/",
          "com/microsoft/semantickernel/");

  private GraphEvalBytecodeGate() {}

  static List<String> directoryViolations(Path classes)
      throws IOException {
    if (!Files.isDirectory(classes)) {
      throw new IllegalArgumentException(
          "classes directory is required");
    }
    Set<String> violations = new TreeSet<>();
    try (Stream<Path> paths = Files.walk(classes)) {
      for (Path path :
          paths
              .filter(Files::isRegularFile)
              .filter(
                  candidate ->
                      candidate
                          .toString()
                          .endsWith(".class"))
              .toList()) {
        String name =
            classes
                .relativize(path)
                .toString()
                .replace('\\', '/');
        violations.addAll(
            appOwnedClassViolations(name));
        violations.addAll(
            archiveEntryViolations(name, name));
        violations.addAll(
            classViolations(name, Files.readAllBytes(path)));
      }
    }
    return List.copyOf(violations);
  }

  static List<String> shippingJarViolations(Path jarPath)
      throws IOException {
    Set<String> violations = new TreeSet<>();
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      for (JarEntry entry :
          jar.stream()
              .filter(candidate -> !candidate.isDirectory())
              .toList()) {
        String name = entry.getName();
        String logicalName = logicalEntryName(name);
        if (logicalName == null) {
          violations.add(
              name + " -> archive:malformed-multi-release-entry");
          continue;
        }
        violations.addAll(
            archiveEntryViolations(name, logicalName));
        if (logicalName.startsWith(FIRST_PARTY_ROOT)
            && logicalName.endsWith(".class")) {
          try (InputStream input = jar.getInputStream(entry)) {
            violations.addAll(
                classViolations(name, input.readAllBytes()));
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<String> archiveEntryViolations(
      String physicalName, String logicalName) {
    List<String> violations = new ArrayList<>();
    for (String forbidden : FORBIDDEN_ARCHIVE_ENTRIES) {
      if (logicalName.startsWith(forbidden)) {
        violations.add(
            physicalName + " -> archive:" + forbidden);
      }
    }
    if (logicalName.startsWith(APP_CLASS_ROOT)
        && logicalName.endsWith(".class")) {
      violations.addAll(
          appOwnedClassViolations(physicalName));
    }
    return List.copyOf(violations);
  }

  private static List<String> appOwnedClassViolations(
      String physicalName) {
    if (!physicalName.endsWith(".class")
        || ALLOWED_APP_CLASSES.contains(physicalName)) {
      return List.of();
    }
    return List.of(
        physicalName
            + " -> archive:unreviewed-graph-eval-class");
  }

  private static String logicalEntryName(String name) {
    if (!name.startsWith(MULTI_RELEASE_ROOT)) {
      return name;
    }
    int versionStart = MULTI_RELEASE_ROOT.length();
    int versionEnd = name.indexOf('/', versionStart);
    if (versionEnd <= versionStart
        || versionEnd == name.length() - 1) {
      return null;
    }
    for (int index = versionStart;
        index < versionEnd;
        index++) {
      if (!Character.isDigit(name.charAt(index))) {
        return null;
      }
    }
    return name.substring(versionEnd + 1);
  }

  static List<String> classViolations(
      String className, byte[] classBytes) throws IOException {
    Set<String> violations = new TreeSet<>();
    for (String constant : utf8Constants(classBytes)) {
      String normalized = constant.replace('.', '/');
      int firstPartyIndex =
          normalized.indexOf(FIRST_PARTY_ROOT);
      while (firstPartyIndex >= 0) {
        String reference =
            normalized.substring(firstPartyIndex);
        if (ALLOWED_FIRST_PARTY.stream()
            .noneMatch(reference::startsWith)) {
          violations.add(
              className + " -> " + boundedReference(reference));
        }
        firstPartyIndex =
            normalized.indexOf(
                FIRST_PARTY_ROOT,
                firstPartyIndex + FIRST_PARTY_ROOT.length());
      }
      for (String forbidden : FORBIDDEN_EXTERNAL) {
        if (normalized.contains(forbidden)) {
          violations.add(className + " -> " + forbidden);
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<String> utf8Constants(byte[] classBytes)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(
            new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid classfile magic");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      List<String> values = new ArrayList<>();
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> values.add(input.readUTF());
          case 3, 4 -> input.readInt();
          case 5, 6 -> {
            input.readLong();
            index++;
          }
          case 7, 8, 16, 19, 20 ->
              input.readUnsignedShort();
          case 9, 10, 11, 12, 17, 18 -> {
            input.readUnsignedShort();
            input.readUnsignedShort();
          }
          case 15 -> {
            input.readUnsignedByte();
            input.readUnsignedShort();
          }
          default ->
              throw new IOException(
                  "unsupported classfile constant tag " + tag);
        }
      }
      return List.copyOf(values);
    }
  }

  private static String boundedReference(String reference) {
    int end = 0;
    while (end < reference.length()
        && isReferenceCharacter(reference.charAt(end))) {
      end++;
    }
    String bounded = reference.substring(0, end);
    return bounded.length() <= 240
        ? bounded
        : bounded.substring(0, 240);
  }

  private static boolean isReferenceCharacter(char value) {
    return Character.isJavaIdentifierPart(value)
        || value == '/'
        || value == '$';
  }
}
