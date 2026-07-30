package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
          + " environmentSha256=440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968"
          + " captureRequestHash=72e9f2a45fa1f2cf43cab0963cd0bd5cd16bedb5f8d40c0ae67401014b6f90ea"
          + " taskHash=842f24eba3bc3179d510959a1658876a1ee03a5286babd4552720ff172980a71"
          + " executionProfileFingerprint=bcf080220b5f6bb26446fa6aabae01800aff846b26b043e885f56af4e8412c6a"
          + " pricingProfileFingerprint=96be6f771a5c8d967424f61571af3737072a1c85ed780f9e7f0ab06ba1c7e28c"
          + " attemptId=8a51691cfd4e5fdb46d441f225db65a390ffc637fbe0eca0c03e7aafc5156592"
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

  @Test
  void packagedExecuteWithoutRealTtyStopsBeforeMarkerCredentialAndClient()
      throws Exception {
    Path isolatedHome =
        Files.createDirectory(tempDir.resolve("execute-home"));
    Path isolatedTmp =
        Files.createDirectory(tempDir.resolve("execute-tmp"));
    ProcessResult result =
        runPackaged(
            isolatedHome,
            isolatedTmp,
            List.of("--execute"),
            true);

    assertEquals(2, result.exitCode(), result.output());
    assertTrue(result.output().startsWith(EXPECTED_RECEIPT + "\n"));
    assertTrue(
        result.output().endsWith(
            "EVAL_REJECTED reason=REAL_TTY_REQUIRED"));
    assertFalse(Files.exists(isolatedHome.resolve(".emergeos")));
    assertFalse(result.output().contains("sentinel-never-read"));
    assertFalse(result.output().contains(SyntheticEvalCatalog.CONTENT));
    assertFalse(result.output().contains("Authorization"));
    assertFalse(result.output().contains("Exception"));
  }

  private ProcessResult runPackaged(
      Path isolatedHome,
      Path isolatedTmp,
      List<String> arguments,
      boolean hostileEnvironment)
      throws Exception {
    Path jar =
        Path.of(System.getProperty("emerge.eval.it.jar"))
            .toAbsolutePath()
            .normalize();
    Path repo =
        Path.of(System.getProperty("emerge.eval.it.repo"))
            .toAbsolutePath()
            .normalize();
    var command =
        new java.util.ArrayList<>(
            List.of(
                javaExecutable(),
                "-Duser.home=" + isolatedHome,
                "-Djava.io.tmpdir=" + isolatedTmp,
                "-jar",
                jar.toString()));
    command.addAll(arguments);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo.toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    if (hostileEnvironment) {
      builder
          .environment()
          .put("OPENAI_API_KEY", "sentinel-never-read");
      builder
          .environment()
          .put("OPENAI_BASE_URL", "http://127.0.0.1:9/v1");
      builder.environment().put("OPENAI_LOG", "debug");
    }

    Process process = builder.start();
    boolean finished =
        process.waitFor(
            Duration.ofSeconds(10).toMillis(),
            TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    String output =
        new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .strip();
    assertTrue(finished, "packaged command timed out");
    return new ProcessResult(process.exitValue(), output);
  }

  private record ProcessResult(int exitCode, String output) {}

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            "java")
        .toString();
  }
}
