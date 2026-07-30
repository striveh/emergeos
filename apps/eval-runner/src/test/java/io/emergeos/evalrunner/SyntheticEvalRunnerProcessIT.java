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
          + " packSha256=bd44cc3ea0230b9267da5cfb6c29fdd2a7452131fe14864e16e6c39b516c8711"
          + " environmentSha256=2a788ccc9b3e5be768f5707466b1b05aa340d79caf8ec728f2c6526919c7242b"
          + " captureRequestHash=72e9f2a45fa1f2cf43cab0963cd0bd5cd16bedb5f8d40c0ae67401014b6f90ea"
          + " taskHash=b55e73579f63736373337077dc2d928ccb8ed540b14193364800d3ec1d238a0a"
          + " executionProfileFingerprint=ff3bca47eeb5a6b43305d6d88f4c9ce4c9f9edaba78744f0435920d663db1a96"
          + " pricingProfileFingerprint=96be6f771a5c8d967424f61571af3737072a1c85ed780f9e7f0ab06ba1c7e28c"
          + " modelRequested=gpt-5.4-mini-2026-03-17"
          + " maximumProviderRequests=2 deadlineMs=30000"
          + " maxInputTokensPerRequest=272000"
          + " maxOutputTokensPerRequest=1000"
          + " reservationUsd=0.417000"
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
