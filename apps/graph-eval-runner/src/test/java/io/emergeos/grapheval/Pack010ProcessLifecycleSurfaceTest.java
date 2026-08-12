package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class Pack010ProcessLifecycleSurfaceTest {

  private static final Pattern UNBOUNDED_WAIT =
      Pattern.compile("\\.(?:waitFor|await)\\s*\\(\\s*\\)\\s*;");

  @Test
  void probeAndParentCleanupHaveNoUnboundedWait() throws Exception {
    String harness =
        source("Pack010GraphTerminalHarnessMain.java");
    String successor =
        source("Pack010SuccessorClaimHarnessMain.java");
    String processTest =
        source("Pack010DurableGraphTerminalProcessIT.java");

    assertFalse(
        UNBOUNDED_WAIT.matcher(harness).find(),
        "the probe process needs a bounded self-expiring lease");
    assertTrue(
        harness.contains("Duration.ofSeconds(90)"),
        "the probe hold lease must remain within the reviewed 60-120s window");
    assertFalse(
        UNBOUNDED_WAIT.matcher(successor).find(),
        "the successor probe needs a bounded self-expiring lease");
    assertTrue(
        successor.contains("Duration.ofSeconds(90)"),
        "the successor probe hold lease must remain within the reviewed 60-120s window");
    assertFalse(
        UNBOUNDED_WAIT.matcher(processTest).find(),
        "forced process cleanup must never wait without a timeout");

    String crashCase =
        processTest.substring(
            processTest.indexOf("private void runCrashCase("),
            processTest.indexOf("private void runRace("));
    assertTrue(
        crashCase.contains("finally"),
        "the crash writer must be cleaned up on every failure path");
    assertTrue(
        crashCase.contains("cleanupProcess(writer, writerFiles)"),
        "the crash writer cleanup must also scan its logs");
  }

  private static String source(String name) throws Exception {
    Path repo =
        Path.of(System.getProperty("emerge.graph.repo"))
            .toAbsolutePath()
            .normalize();
    return Files.readString(
        repo.resolve(
            "apps/graph-eval-runner/src/test/java/"
                + "io/emergeos/grapheval/"
                + name),
        StandardCharsets.UTF_8);
  }
}
