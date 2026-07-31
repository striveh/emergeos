package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

  private static final String EXPECTED_WORKER_RECEIPT =
      "PACK008_PREFLIGHT_RECEIPT status=PREFLIGHT_READY"
          + " caseId=openai-public-read-worker-008-r1"
          + " packSha256=4803c227d88484dfe5796c286cb7b602242a54b452c39e5eb2be69f6a02bf1db"
          + " environmentSha256=440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968"
          + " captureRequestHash=dd51522e6a5c2bfe2dd425bb3c6ad4a7e05bccce420e126040f14af01ce1ab00"
          + " parentTaskHash=35465c2631b9616195beb85d5280c7debc26f80603ad8cf89bb5a722e4517470"
          + " childTaskHash=ed8988df7c4c1aaabee266720ef43aa0124116751ab1ae6eef773772c70a2d04"
          + " parentExecutionProfileFingerprint=b624df93b848c46b28be679dabdf463d8bca9fb9cdbc703a79c9965bb49f685c"
          + " workerProfileFingerprint=82a9081712a91b20ccfa39779d8e77637373a154d8d9eb2e1246f423f3b9ca82"
          + " pricingProfileFingerprint=97484a33d9374dfe67b6f6e22260c4ff82750b285d0be2aaed36c2fdcf6a1b50"
          + " promptSurfaceFingerprint=1a7a8b31dd2e8e5d4e598b086692dfb97558a6861464b8f122a5047c6a64a9f4"
          + " conductorDecisionSurfaceFingerprint=7920292ff1f3605c152719e7c771f9420a18b0110329c837e55c0cc90c026fa6"
          + " attemptId=e3bfef65db2dbc1eeabf726e2162909ff52c92466ea14a4f9e65eb8270d17661"
          + " modelRequested=gpt-5.4-mini-2026-03-17"
          + " parentActor=SCRIPTED_FAKE childActor=OPENAI_RESPONSES"
          + " dataClass=PUBLIC literalSynthetic=true"
          + " maximumProviderRequests=2"
          + " childDeadlineMs=30000 parentDeadlineMs=35000"
          + " maxInputTokensPerRequest=272000"
          + " maxOutputTokensPerRequest=1000"
          + " reservationUsd=0.417000"
          + " runtimeEffectCounters=NOT_INSTRUMENTED"
          + " zeroEgressProof=STATIC_PATH_PLUS_PROCESS_SENTINEL";

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

  @Test
  void workerPreflightIsAnExactPackagedZeroEgressRoute()
      throws Exception {
    Path isolatedHome =
        Files.createDirectory(tempDir.resolve("worker-home"));
    Path isolatedTmp =
        Files.createDirectory(tempDir.resolve("worker-tmp"));

    AtomicInteger requests = new AtomicInteger();
    HttpServer sentinel =
        HttpServer.create(
            new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0),
            0);
    sentinel.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    sentinel.start();
    ProcessResult result;
    try {
      result =
          runPackaged(
              isolatedHome,
              isolatedTmp,
              List.of("--worker-preflight"),
              true,
              "http://127.0.0.1:"
                  + sentinel.getAddress().getPort()
                  + "/v1");
    } finally {
      sentinel.stop(0);
    }

    assertEquals(0, result.exitCode(), result.output());
    assertEquals(EXPECTED_WORKER_RECEIPT, result.output());
    assertFalse(Files.exists(isolatedHome.resolve(".emergeos")));
    assertTrue(isDirectoryEmpty(isolatedHome));
    assertTrue(isDirectoryEmpty(isolatedTmp));
    assertFalse(result.output().contains("sentinel-never-read"));
    assertFalse(result.output().contains(Pack008WorkerEvalCatalog.CONTENT));
    assertFalse(result.output().contains("Authorization"));
    assertFalse(result.output().contains("Exception"));
    assertEquals(0, requests.get());
  }

  @Test
  void invalidWorkerExecutionCombinationStopsBeforeAnyPreflightOrEffect()
      throws Exception {
    List<List<String>> rejectedArguments =
        List.of(
            List.of("--worker-preflight", "--execute"),
            List.of("--worker-execute"),
            List.of(
                "--worker-preflight",
                "evals/task-packs/synthetic/"
                    + "007-offline-read-only-worker-handoff-context-drift.json"),
            List.of("--model", "gpt-5.6"),
            List.of("--base-url", "http://127.0.0.1:9/v1"),
            List.of("--api-key", "sentinel-never-read"),
            List.of("--worker-preflight", "--help"));

    for (int index = 0; index < rejectedArguments.size(); index++) {
      Path isolatedHome =
          Files.createDirectory(
              tempDir.resolve("invalid-worker-home-" + index));
      Path isolatedTmp =
          Files.createDirectory(
              tempDir.resolve("invalid-worker-tmp-" + index));

      ProcessResult result =
          runPackaged(
              isolatedHome,
              isolatedTmp,
              rejectedArguments.get(index),
              true);

      assertEquals(2, result.exitCode(), result.output());
      assertEquals(
          "EVAL_REJECTED reason=ARGUMENTS_INVALID", result.output());
      assertFalse(Files.exists(isolatedHome.resolve(".emergeos")));
      assertFalse(result.output().contains("sentinel-never-read"));
      assertFalse(result.output().contains("PREFLIGHT_READY"));
      assertFalse(result.output().contains("Authorization"));
      assertFalse(result.output().contains("Exception"));
    }
  }

  @Test
  void packagedHelpIsExactAndStopsBeforeAssetsOrEffects()
      throws Exception {
    Path isolatedHome =
        Files.createDirectory(tempDir.resolve("help-home"));
    Path isolatedTmp =
        Files.createDirectory(tempDir.resolve("help-tmp"));

    ProcessResult result =
        runPackaged(
            isolatedHome,
            isolatedTmp,
            List.of("--help"),
            true);

    assertEquals(0, result.exitCode(), result.output());
    assertEquals(
        """
        Usage: java -jar emerge-eval-runner.jar [mode]
          (no args)           Pack003 preflight only
          --worker-preflight  Pack008 preflight only
          --execute           Pack003 one-shot execution only
          --help              Show this help
        """
            .strip(),
        result.output());
    assertTrue(isDirectoryEmpty(isolatedHome));
    assertTrue(isDirectoryEmpty(isolatedTmp));
    assertFalse(result.output().contains("sentinel-never-read"));
    assertFalse(result.output().contains("PREFLIGHT_READY"));
    assertFalse(result.output().contains("Authorization"));
    assertFalse(result.output().contains("Exception"));
  }

  private ProcessResult runPackaged(
      Path isolatedHome,
      Path isolatedTmp,
      List<String> arguments,
      boolean hostileEnvironment)
      throws Exception {
    return runPackaged(
        isolatedHome,
        isolatedTmp,
        arguments,
        hostileEnvironment,
        "http://127.0.0.1:9/v1");
  }

  private ProcessResult runPackaged(
      Path isolatedHome,
      Path isolatedTmp,
      List<String> arguments,
      boolean hostileEnvironment,
      String hostileBaseUrl)
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
          .put("OPENAI_BASE_URL", hostileBaseUrl);
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

  private static boolean isDirectoryEmpty(Path directory)
      throws Exception {
    try (var entries = Files.list(directory)) {
      return entries.findAny().isEmpty();
    }
  }

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            "java")
        .toString();
  }
}
