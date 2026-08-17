package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class DeepSeekV4FlashShippingJarIT {

  private static final String PROBE_CLASS =
      "io/emergeos/adapters/openai/"
          + "DeepSeekV4FlashResponsesProbe.class";
  private static final String LIVE_MAIN_CLASS =
      "io/emergeos/adapters/openai/"
          + "DeepSeekV4FlashResponsesProbeMain.class";
  private static final String PROBE_TEST_CLASS =
      "io/emergeos/adapters/openai/"
          + "DeepSeekV4FlashResponsesProbeTest.class";

  @Test
  void currentShadedJarContainsOnlyTheDormantReviewedProbe()
      throws IOException {
    Path jarPath =
        Path.of(System.getProperty("emerge.graph.it.jar"))
            .toAbsolutePath()
            .normalize();
    assertTrue(Files.isRegularFile(jarPath), jarPath.toString());

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      assertTrue(jar.getJarEntry(PROBE_CLASS) != null, PROBE_CLASS);
      assertFalse(jar.getJarEntry(LIVE_MAIN_CLASS) != null, LIVE_MAIN_CLASS);
      assertFalse(jar.getJarEntry(PROBE_TEST_CLASS) != null, PROBE_TEST_CLASS);
    }
    assertEquals(
        java.util.List.of(),
        GraphEvalBytecodeGate.shippingJarViolations(jarPath));
  }
}
