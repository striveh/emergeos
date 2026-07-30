package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineComparisonDurabilityProcessIT {

  private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(15);
  private static final String REPORT_ID =
      "comparison-report-"
          + "10d922825a4edb0d050f79f91f795a1b"
          + "5ec5f122d182ab9ebd66e159cd0ecdf9";
  private static final String REPORT_HASH =
      "9ff55976a03af6c871f87da54e1e00d0"
          + "83ed3782d353ec677ab49d6bbb963ab1";
  private static final String FILE_SHA256 =
      "b1152849fc2807d536d59e7a1412bfe4"
          + "336df4ded51a74ec412bc94d840b12a7";

  @TempDir Path tempDir;

  @Test
  void packagedWriterAndFreshReadOnlyJvmsShareOneDurableReport()
      throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("home"));
    Path tmp = Files.createDirectory(tempDir.resolve("tmp"));

    ProcessResult execution =
        runPackaged(home, tmp, List.of("--execute"));

    assertEquals(0, execution.exitCode(), execution.output());
    assertEquals(
        receipt("OFFLINE_COMPARISON_STORED"),
        execution.output());
    assertSafeOutput(execution.output());

    EvidenceSnapshot afterExecution = snapshot(home);
    assertEquals(
        List.of(
            "pack004-reference-grounding-v1.claim",
            "pack004-reference-grounding-v1.report.json"),
        afterExecution.files().keySet().stream().sorted().toList());

    ProcessResult firstVerification =
        runPackaged(home, tmp, List.of("--verify"));
    EvidenceSnapshot afterFirstVerification = snapshot(home);
    ProcessResult secondVerification =
        runPackaged(home, tmp, List.of("--verify"));

    assertEquals(0, firstVerification.exitCode(), firstVerification.output());
    assertEquals(firstVerification, secondVerification);
    assertEquals(
        receipt("OFFLINE_COMPARISON_VERIFY"),
        firstVerification.output());
    assertEquals(afterExecution, afterFirstVerification);
    assertEquals(afterExecution, snapshot(home));
    assertSafeOutput(firstVerification.output());

    ProcessResult replay =
        runPackaged(home, tmp, List.of("--execute"));
    assertEquals(2, replay.exitCode(), replay.output());
    assertEquals(
        "OFFLINE_COMPARISON_REJECTED"
            + " reason=REPORT_ALREADY_EXISTS",
        replay.output());
    assertEquals(afterExecution, snapshot(home));
    assertSafeOutput(replay.output());
  }

  @Test
  void shippingCliRejectsCrashInjectionArgumentsWithoutCreatingState()
      throws Exception {
    Path home =
        Files.createDirectory(tempDir.resolve("invalid-home"));
    Path tmp =
        Files.createDirectory(tempDir.resolve("invalid-tmp"));

    ProcessResult result =
        runPackaged(
            home,
            tmp,
            List.of("--crash-at=REPORT_PENDING_DURABLE"));

    assertEquals(2, result.exitCode(), result.output());
    assertEquals(
        "OFFLINE_COMPARISON_REJECTED reason=ARGUMENTS_INVALID",
        result.output());
    assertFalse(Files.exists(home.resolve(".emergeos")));
    assertSafeOutput(result.output());

    ProcessResult absent =
        runPackaged(home, tmp, List.of("--verify"));
    assertEquals(3, absent.exitCode(), absent.output());
    assertEquals(
        "OFFLINE_COMPARISON_VERIFY"
            + " verdict=UNKNOWN"
            + " code=REPORT_FILE_MISSING"
            + " fileState=ABSENT"
            + " reportId=NONE"
            + " reportHash=NONE"
            + " fileSha256=NONE"
            + " bytes=0",
        absent.output());
    assertFalse(Files.exists(home.resolve(".emergeos")));
    assertSafeOutput(absent.output());
  }

  @Test
  void twoPackagedWriterJvmsHaveExactlyOnePhysicalPublisher()
      throws Exception {
    Path home =
        Files.createDirectory(tempDir.resolve("race-home"));
    Path tmp =
        Files.createDirectory(tempDir.resolve("race-tmp"));

    Process first = startPackaged(home, tmp, List.of("--execute"));
    Process second = startPackaged(home, tmp, List.of("--execute"));
    ProcessResult firstResult = finish(first);
    ProcessResult secondResult = finish(second);
    List<ProcessResult> results =
        List.of(firstResult, secondResult);

    assertEquals(
        1,
        results.stream()
            .filter(result -> result.exitCode() == 0)
            .count());
    assertEquals(
        1,
        results.stream()
            .filter(result -> result.exitCode() == 2)
            .count());
    assertTrue(
        results.stream()
            .filter(result -> result.exitCode() == 0)
            .allMatch(
                result ->
                    receipt("OFFLINE_COMPARISON_STORED")
                        .equals(result.output())));
    assertTrue(
        results.stream()
            .filter(result -> result.exitCode() == 2)
            .allMatch(
                result ->
                    Set.of(
                            "OFFLINE_COMPARISON_REJECTED"
                                + " reason=REPORT_COMMIT_INCOMPLETE",
                            "OFFLINE_COMPARISON_REJECTED"
                                + " reason=REPORT_ALREADY_EXISTS")
                        .contains(result.output())));
    results.forEach(
        result -> assertSafeOutput(result.output()));

    ProcessResult verification =
        runPackaged(home, tmp, List.of("--verify"));
    assertEquals(0, verification.exitCode(), verification.output());
    assertEquals(
        receipt("OFFLINE_COMPARISON_VERIFY"),
        verification.output());
  }

  private static ProcessResult runPackaged(
      Path home, Path tmp, List<String> arguments)
      throws Exception {
    return finish(startPackaged(home, tmp, arguments));
  }

  private static Process startPackaged(
      Path home, Path tmp, List<String> arguments)
      throws IOException {
    Path jar =
        Path.of(System.getProperty("emerge.offline.it.jar"))
            .toAbsolutePath()
            .normalize();
    Path repository =
        Path.of(System.getProperty("emerge.offline.it.repo"))
            .toAbsolutePath()
            .normalize();
    var command =
        new java.util.ArrayList<>(
            List.of(
                javaExecutable(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + tmp,
                "-Djava.net.useSystemProxies=false",
                "-jar",
                jar.toString()));
    command.addAll(arguments);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repository.toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    return builder.start();
  }

  private static ProcessResult finish(Process process)
      throws Exception {
    boolean finished =
        process.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    String output =
        new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8)
            .strip();
    assertTrue(finished, "process timed out; output=" + output);
    return new ProcessResult(process.exitValue(), output);
  }

  private static EvidenceSnapshot snapshot(Path home)
      throws IOException {
    Path directory =
        home.resolve(".emergeos/offline-comparisons");
    Map<String, EvidenceFile> files = new LinkedHashMap<>();
    try (var entries = Files.list(directory)) {
      for (Path path :
          entries.sorted(Comparator.comparing(Path::toString)).toList()) {
        byte[] bytes = Files.readAllBytes(path);
        files.put(
            path.getFileName().toString(),
            new EvidenceFile(
                bytes.length,
                Files.getLastModifiedTime(path).toMillis(),
                sha256(bytes)));
      }
    }
    return new EvidenceSnapshot(Map.copyOf(files));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 unavailable", impossible);
    }
  }

  private static void assertSafeOutput(String output) {
    assertFalse(output.contains("OPENAI_API_KEY"), output);
    assertFalse(output.contains("Authorization"), output);
    assertFalse(output.contains("Exception"), output);
    assertFalse(
        output.contains(
            Path.of(
                    System.getProperty("emerge.offline.it.repo"))
                .toAbsolutePath()
                .normalize()
                .toString()),
        output);
    assertFalse(
        output.contains(
            "把看到的事实、自己的判断和下一步行动绑定在同一条可追溯链路里"),
        output);
  }

  private static String receipt(String prefix) {
    return prefix
        + " verdict=VERIFIED_PASSED"
        + " code=NONE"
        + " fileState=FINAL"
        + " reportId="
        + REPORT_ID
        + " reportHash="
        + REPORT_HASH
        + " fileSha256="
        + FILE_SHA256
        + " bytes=28343";
  }

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            "java")
        .toString();
  }

  private record ProcessResult(int exitCode, String output) {}

  private record EvidenceSnapshot(
      Map<String, EvidenceFile> files) {}

  private record EvidenceFile(
      long size, long modifiedAtMillis, String sha256) {}
}
