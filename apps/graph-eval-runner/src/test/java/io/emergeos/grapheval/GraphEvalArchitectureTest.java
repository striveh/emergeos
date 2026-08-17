package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphEvalArchitectureTest {

  @TempDir Path tempDir;

  @Test
  void productionBytecodeUsesOnlyTheReviewedCompositionPackages()
      throws Exception {
    Path classes =
        Path.of(
                System.getProperty("emerge.graph.classes"))
            .toAbsolutePath()
            .normalize();

    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.directoryViolations(classes));
  }

  @Test
  void negativeFixtureProvesTheGateRejectsAForbiddenReference()
      throws Exception {
    Path sourceRoot = tempDir.resolve("src");
    Path output = tempDir.resolve("classes");
    Path forbiddenSource =
        sourceRoot.resolve(
            "io/emergeos/api/ForbiddenApi.java");
    Path consumerSource =
        sourceRoot.resolve(
            "io/emergeos/grapheval/fixture/"
                + "UsesForbiddenApi.java");
    Files.createDirectories(forbiddenSource.getParent());
    Files.createDirectories(consumerSource.getParent());
    Files.createDirectories(output);
    Files.writeString(
        forbiddenSource,
        """
        package io.emergeos.api;
        public final class ForbiddenApi {}
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        consumerSource,
        """
        package io.emergeos.grapheval.fixture;
        import io.emergeos.api.ForbiddenApi;
        public final class UsesForbiddenApi {
          public static final String INMEMORY =
              "io.emergeos.adapters.inmemory.InMemoryCaptureStore";
          public static final String TEMPORAL =
              "io.temporal.client.WorkflowClient";
          public static final String SPRING_AI =
              "org.springframework.ai.chat.client.ChatClient";
          private ForbiddenApi value;
        }
        """,
        StandardCharsets.UTF_8);

    JavaCompiler compiler =
        ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
    assertEquals(
        0,
        compiler.run(
            null,
            null,
            null,
            "-d",
            output.toString(),
            forbiddenSource.toString(),
            consumerSource.toString()));

    List<String> violations =
        GraphEvalBytecodeGate.classViolations(
            "UsesForbiddenApi.class",
            Files.readAllBytes(
                output.resolve(
                    "io/emergeos/grapheval/fixture/"
                        + "UsesForbiddenApi.class")));
    assertFalse(violations.isEmpty());
    assertEquals(
        List.of(
            "UsesForbiddenApi.class"
                + " -> io/emergeos/adapters/inmemory/"
                + "InMemoryCaptureStore",
            "UsesForbiddenApi.class"
                + " -> io/emergeos/api/ForbiddenApi",
            "UsesForbiddenApi.class -> io/temporal/",
            "UsesForbiddenApi.class"
                + " -> org/springframework/ai/"),
        violations);
  }

  @Test
  void negativeFixtureProvesMultiReleaseEntriesCannotBypassTheGate()
      throws Exception {
    Path sourceRoot = tempDir.resolve("mr-src");
    Path output = tempDir.resolve("mr-classes");
    Path source =
        sourceRoot.resolve(
            "io/emergeos/grapheval/fixture/"
                + "VersionedLeak.java");
    Files.createDirectories(source.getParent());
    Files.createDirectories(output);
    Files.writeString(
        source,
        """
        package io.emergeos.grapheval.fixture;
        public final class VersionedLeak {
          public static final String FORBIDDEN =
              "org.springframework.web.client.RestClient";
        }
        """,
        StandardCharsets.UTF_8);

    JavaCompiler compiler =
        ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
    assertEquals(
        0,
        compiler.run(
            null,
            null,
            null,
            "-d",
            output.toString(),
            source.toString()));

    Path jarPath = tempDir.resolve("multi-release.jar");
    Manifest manifest = new Manifest();
    manifest
        .getMainAttributes()
        .put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest
        .getMainAttributes()
        .putValue("Multi-Release", "true");
    try (JarOutputStream jar =
        new JarOutputStream(
            Files.newOutputStream(jarPath), manifest)) {
      JarEntry classEntry =
          new JarEntry(
              "META-INF/versions/21/"
                  + "io/emergeos/grapheval/fixture/"
                  + "VersionedLeak.class");
      jar.putNextEntry(classEntry);
      jar.write(
          Files.readAllBytes(
              output.resolve(
                  "io/emergeos/grapheval/fixture/"
                      + "VersionedLeak.class")));
      jar.closeEntry();

      JarEntry forbiddenArchiveEntry =
          new JarEntry(
              "META-INF/versions/21/"
                  + "org/springframework/web/Foo.class");
      jar.putNextEntry(forbiddenArchiveEntry);
      jar.write(new byte[] {0});
      jar.closeEntry();
    }

    assertEquals(
        List.of(
            "META-INF/versions/21/"
                + "io/emergeos/grapheval/fixture/"
                + "VersionedLeak.class"
                + " -> archive:unreviewed-graph-eval-class",
            "META-INF/versions/21/"
                + "io/emergeos/grapheval/fixture/"
                + "VersionedLeak.class"
                + " -> org/springframework/web/",
            "META-INF/versions/21/"
                + "org/springframework/web/Foo.class"
                + " -> archive:org/springframework/web/"),
        GraphEvalBytecodeGate.shippingJarViolations(
            jarPath));
  }

  @Test
  void negativeFixtureRejectsARealUnreviewedAppExecutionClass()
      throws Exception {
    String harnessResource =
        "io/emergeos/grapheval/"
            + "Pack009GraphCrashHarnessMain.class";
    byte[] harnessBytes;
    try (InputStream input =
        GraphEvalArchitectureTest.class
            .getClassLoader()
            .getResourceAsStream(harnessResource)) {
      assertNotNull(input);
      harnessBytes = input.readAllBytes();
    }

    Path jarPath = tempDir.resolve("unreviewed-app-class.jar");
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath))) {
      jar.putNextEntry(new JarEntry(harnessResource));
      jar.write(harnessBytes);
      jar.closeEntry();
    }

    List<String> violations =
        GraphEvalBytecodeGate.shippingJarViolations(jarPath);
    assertTrue(
        violations.contains(
            harnessResource
                + " -> archive:unreviewed-graph-eval-class"));
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.contains(
                        "capability:unreviewed-consumer:")));
  }

  @Test
  void negativeFixtureRejectsAppOutputInAnAllowedDependencyPackage()
      throws Exception {
    Path sourceRoot = tempDir.resolve("misplaced-src");
    Path output = tempDir.resolve("misplaced-classes");
    Path source =
        sourceRoot.resolve(
            "io/emergeos/core/InjectedMain.java");
    Files.createDirectories(source.getParent());
    Files.createDirectories(output);
    Files.writeString(
        source,
        """
        package io.emergeos.core;
        public final class InjectedMain {
          public static void main(String[] arguments) {}
        }
        """,
        StandardCharsets.UTF_8);

    JavaCompiler compiler =
        ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
    assertEquals(
        0,
        compiler.run(
            null,
            null,
            null,
            "-d",
            output.toString(),
            source.toString()));

    assertEquals(
        List.of(
            "io/emergeos/core/InjectedMain.class"
                + " -> archive:unreviewed-graph-eval-class"),
        GraphEvalBytecodeGate.directoryViolations(output));
  }
}
