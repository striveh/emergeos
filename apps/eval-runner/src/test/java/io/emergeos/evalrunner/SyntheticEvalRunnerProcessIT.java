package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticEvalRunnerProcessIT {

  private static final String EXPECTED_RECEIPT =
      "EVAL_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " caseId=openai-public-draft-003-r1"
          + " keyReads=0 clientFactories=0 modelFactories=0"
          + " runStarts=0 httpRequests=0 markerCreated=false";

  @TempDir Path tempDir;

  @Test
  void defaultPackagedCommandProducesZeroEgressPreflight()
      throws Exception {
    Path jar =
        Path.of(System.getProperty("emerge.eval.it.jar"))
            .toAbsolutePath()
            .normalize();
    Path repo =
        Path.of(System.getProperty("emerge.eval.it.repo"))
            .toAbsolutePath()
            .normalize();
    Path isolatedHome = Files.createDirectory(tempDir.resolve("home"));
    Path isolatedTmp = Files.createDirectory(tempDir.resolve("tmp"));

    ProcessBuilder builder =
        new ProcessBuilder(
            List.of(
                javaExecutable(),
                "-Duser.home=" + isolatedHome,
                "-Djava.io.tmpdir=" + isolatedTmp,
                "-jar",
                jar.toString()));
    builder.directory(repo.toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();

    Process process = builder.start();
    boolean finished =
        process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    String output =
        new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .strip();

    assertEquals(true, finished, "packaged preflight timed out");
    assertEquals(0, process.exitValue(), output);
    assertEquals(EXPECTED_RECEIPT, output);
    assertFalse(
        Files.exists(isolatedHome.resolve(".emergeos/eval-attempts")));
    assertFalse(output.contains("OPENAI_API_KEY"));
    assertFalse(output.contains("Authorization"));
    assertFalse(output.contains("Exception"));
  }

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            "java")
        .toString();
  }
}
