package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.ReferenceGroundingPolicy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessEvaluationEffectClosureTest {

  private static final List<String> FORBIDDEN_CLASS_PREFIXES =
      List.of(
          "java/net/",
          "java/nio/file/",
          "java/sql/",
          "javax/sql/",
          "io/emergeos/adapters/");

  private static final List<String> FORBIDDEN_CLASSES =
      List.of(
          "java/io/File",
          "java/io/RandomAccessFile",
          "java/lang/System",
          "java/lang/Runtime",
          "java/lang/ProcessBuilder",
          "io/emergeos/core/port/GraphAttemptReader",
          "io/emergeos/core/port/GraphAttemptStore",
          "io/emergeos/core/port/ModelAdapter",
          "io/emergeos/core/port/ToolExecutor");

  @Test
  void evaluatorAndReducerClosureHasNoEffectCapabilityReference()
      throws Exception {
    List<Class<?>> closureRoots =
        List.of(
            SharedCandidateHarnessEvaluator.class,
            HarnessEvaluationReportReducer.class,
            AgentDraftReferenceGrounding.class,
            ReferenceGroundingPolicy.class,
            AgentDraftProposal.class,
            ArtifactLineageEntry.class);

    for (Class<?> type : closureRoots) {
      assertClassTreeHasNoForbiddenReference(type);
    }

    for (Field field :
        HarnessEvaluationReportReducer.class.getDeclaredFields()) {
      assertTrue(Modifier.isStatic(field.getModifiers()));
      assertTrue(Modifier.isFinal(field.getModifiers()));
      assertTrue(field.getType() == String.class);
    }
  }

  private static void assertClassTreeHasNoForbiddenReference(
      Class<?> root) throws IOException {
    ArrayDeque<Class<?>> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Class<?> type = pending.removeFirst();
      pending.addAll(List.of(type.getDeclaredClasses()));
      List<String> references = classReferences(classBytes(type));
      for (String reference : references) {
        assertFalse(
            FORBIDDEN_CLASSES.contains(reference)
                || FORBIDDEN_CLASS_PREFIXES.stream()
                    .anyMatch(reference::startsWith),
            () -> type.getName() + " references " + reference);
      }
    }
  }

  private static List<String> classReferences(byte[] bytecode)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(bytecode))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid class resource");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int count = input.readUnsignedShort();
      String[] utf8 = new String[count];
      int[] classNameIndexes = new int[count];
      for (int index = 1; index < count; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> utf8[index] = input.readUTF();
          case 3, 4 -> input.skipNBytes(4);
          case 5, 6 -> {
            input.skipNBytes(8);
            index++;
          }
          case 7 -> classNameIndexes[index] = input.readUnsignedShort();
          case 8, 16, 19, 20 -> input.skipNBytes(2);
          case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
          case 15 -> input.skipNBytes(3);
          default -> throw new IOException("unsupported class tag " + tag);
        }
      }
      List<String> references = new ArrayList<>();
      for (int classNameIndex : classNameIndexes) {
        if (classNameIndex != 0) {
          references.add(utf8[classNameIndex]);
        }
      }
      return List.copyOf(references);
    }
  }

  private static byte[] classBytes(Class<?> type) throws IOException {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream input = type.getResourceAsStream(resource)) {
      assertNotNull(input, () -> "missing class resource " + resource);
      return input.readAllBytes();
    }
  }
}
