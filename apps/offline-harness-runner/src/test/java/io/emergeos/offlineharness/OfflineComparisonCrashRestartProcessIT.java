package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineComparisonCrashRestartProcessIT {

  private static final Duration START_TIMEOUT =
      Duration.ofSeconds(15);
  private static final Duration EXIT_TIMEOUT =
      Duration.ofSeconds(10);
  private static final String HARNESS_MAIN =
      "io.emergeos.offlineharness."
          + "OfflineComparisonCrashHarnessMain";
  private static final String HARNESS_CLASS_ENTRY =
      HARNESS_MAIN.replace('.', '/') + ".class";
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
  void forceKilledPackagedWriterHasDeterministicReadOnlyState()
      throws Exception {
    assertHarnessIsTestOnly();
    for (CrashCase crashCase : crashCases()) {
      exercise(crashCase);
    }
  }

  @Test
  void shippingJarRetainsThirdPartyLegalMaterials()
      throws Exception {
    try (JarFile shipping = new JarFile(jar().toFile())) {
      assertJarTextContains(
          shipping,
          "META-INF/licenses/Apache-2.0.txt",
          "Apache License");
      assertJarTextContains(
          shipping,
          "META-INF/licenses/Apache-2.0.txt",
          "END OF TERMS AND CONDITIONS");
      assertJarTextContains(
          shipping,
          "META-INF/THIRD-PARTY-NOTICES.txt",
          "Jackson");
      assertJarTextContains(
          shipping, "META-INF/NOTICE", "Jackson");
      assertJarTextContains(
          shipping,
          "META-INF/FastDoubleParser-LICENSE",
          "Permission is hereby granted");
      assertJarTextContains(
          shipping,
          "META-INF/FastDoubleParser-ThirdParty-LICENSE",
          "Boost Software License");
      assertJarTextContains(
          shipping,
          "META-INF/Schubfach-LICENSE",
          "Permission is hereby granted");
      assertEquals(
          "io.emergeos.offlineharness.Pack004ComparisonMain",
          shipping
              .getManifest()
              .getMainAttributes()
              .getValue("Main-Class"));
    }
  }

  private void exercise(CrashCase crashCase) throws Exception {
    Path caseRoot =
        Files.createDirectory(tempDir.resolve(crashCase.id()));
    Path home = privateDirectory(caseRoot.resolve("home"));
    Path tmp = privateDirectory(caseRoot.resolve("tmp"));
    Path ready = caseRoot.resolve("ready");
    Path output = caseRoot.resolve("writer-output.log");

    Process writer =
        start(
            harnessCommand(
                home, tmp, crashCase.phase(), ready),
            output);
    awaitReady(writer, ready, output, crashCase);
    assertTrue(writer.isAlive(), crashCase.id());
    assertEquals(
        crashCase.phase(),
        Files.readString(ready, StandardCharsets.US_ASCII)
            .strip());

    writer.destroyForcibly();
    assertTrue(
        writer.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
        crashCase.id());
    assertNotEquals(0, writer.exitValue(), crashCase.id());
    String writerOutput =
        Files.readString(output, StandardCharsets.UTF_8)
            .strip();
    assertEquals(
        "CRASH_HARNESS_READY phase="
            + crashCase.phase(),
        writerOutput);
    assertSafeOutput(writerOutput);

    EvidenceSnapshot afterCrash = snapshot(home);
    assertCrashShape(home, crashCase, afterCrash);
    ProcessResult first =
        runPackaged(home, tmp, "--verify");
    ProcessResult second =
        runPackaged(home, tmp, "--verify");

    assertEquals(
        crashCase.verifyExitCode(),
        first.exitCode(),
        first.output());
    assertEquals(
        crashCase.expectedVerification(), first.output());
    assertEquals(first, second);
    assertEquals(afterCrash, snapshot(home));
    assertSafeOutput(first.output());

    ProcessResult replay =
        runPackaged(home, tmp, "--execute");
    assertEquals(2, replay.exitCode(), replay.output());
    assertEquals(
        "OFFLINE_COMPARISON_REJECTED reason="
            + crashCase.replayCode(),
        replay.output());
    assertEquals(afterCrash, snapshot(home));
    assertSafeOutput(replay.output());
  }

  private static void assertCrashShape(
      Path home,
      CrashCase crashCase,
      EvidenceSnapshot snapshot)
      throws IOException {
    List<String> names =
        snapshot.files().keySet().stream().sorted().toList();
    if ("CLAIM_DURABLE".equals(crashCase.phase())) {
      assertEquals(
          List.of(
              PosixOfflineComparisonReportStore
                  .CLAIM_FILE_NAME),
          names);
      return;
    }
    if ("REPORT_LINK_COMMIT_COMPLETE"
        .equals(crashCase.phase())) {
      assertEquals(
          List.of(
                  PosixOfflineComparisonReportStore
                      .CLAIM_FILE_NAME,
                  PosixOfflineComparisonReportStore
                      .PENDING_FILE_NAME,
                  PosixOfflineComparisonReportStore
                      .REPORT_FILE_NAME)
              .stream()
              .sorted()
              .toList(),
          names);
      Path state =
          home.resolve(".emergeos/offline-comparisons");
      assertTrue(
          Files.isSameFile(
              state.resolve(
                  PosixOfflineComparisonReportStore
                      .PENDING_FILE_NAME),
              state.resolve(
                  PosixOfflineComparisonReportStore
                      .REPORT_FILE_NAME)));
      return;
    }
    if ("REPORT_DIRECTORY_DURABLE"
        .equals(crashCase.phase())) {
      assertEquals(
          List.of(
                  PosixOfflineComparisonReportStore
                      .CLAIM_FILE_NAME,
                  PosixOfflineComparisonReportStore
                      .REPORT_FILE_NAME)
              .stream()
              .sorted()
              .toList(),
          names);
      return;
    }
    assertEquals(
        List.of(
                PosixOfflineComparisonReportStore
                    .CLAIM_FILE_NAME,
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME)
            .stream()
            .sorted()
            .toList(),
        names);
  }

  private static List<CrashCase> crashCases() {
    return List.of(
        new CrashCase(
            "claim",
            "CLAIM_DURABLE",
            3,
            unknownVerification(
                "CLAIM_NON_AUTHORITATIVE"),
            "REPORT_COMMIT_INCOMPLETE"),
        new CrashCase(
            "pending",
            "PENDING_WRITE_STARTED",
            3,
            unknownVerification(
                "PENDING_NON_AUTHORITATIVE"),
            "REPORT_COMMIT_INCOMPLETE"),
        new CrashCase(
            "pending-file-fsync",
            "PENDING_FILE_FSYNC_COMPLETE",
            3,
            unknownVerification(
                "PENDING_NON_AUTHORITATIVE"),
            "REPORT_COMMIT_INCOMPLETE"),
        new CrashCase(
            "pending-directory-durable",
            "PENDING_FILE_DURABLE",
            3,
            unknownVerification(
                "PENDING_NON_AUTHORITATIVE"),
            "REPORT_COMMIT_INCOMPLETE"),
        new CrashCase(
            "commit-started",
            "REPORT_COMMIT_STARTED",
            3,
            unknownVerification(
                "PENDING_NON_AUTHORITATIVE"),
            "REPORT_COMMIT_INCOMPLETE"),
        new CrashCase(
            "linked",
            "REPORT_LINK_COMMIT_COMPLETE",
            0,
            verifiedResult(),
            "REPORT_ALREADY_EXISTS"),
        new CrashCase(
            "directory-durable",
            "REPORT_DIRECTORY_DURABLE",
            0,
            verifiedResult(),
            "REPORT_ALREADY_EXISTS"));
  }

  private static String unknownVerification(String state) {
    return "OFFLINE_COMPARISON_VERIFY"
        + " verdict=UNKNOWN"
        + " code=REPORT_COMMIT_INCOMPLETE"
        + " fileState="
        + state
        + " reportId=NONE"
        + " reportHash=NONE"
        + " fileSha256=NONE"
        + " bytes=0";
  }

  private static String verifiedResult() {
    return "OFFLINE_COMPARISON_VERIFY"
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

  private static List<String> harnessCommand(
      Path home, Path tmp, String phase, Path ready) {
    return List.of(
        javaExecutable(),
        "-Duser.home=" + home,
        "-Djava.io.tmpdir=" + tmp,
        "-Djava.net.useSystemProxies=false",
        "-Demerge.offline.harness.jar=" + jar(),
        "-Demerge.offline.harness.testClasses=" + testClasses(),
        "-cp",
        testClasses()
            + System.getProperty("path.separator")
            + jar(),
        HARNESS_MAIN,
        phase,
        ready.toString());
  }

  private static ProcessResult runPackaged(
      Path home, Path tmp, String argument)
      throws Exception {
    Path output =
        Files.createTempFile(tmp, "verify-output-", ".log");
    Process process =
        start(
            List.of(
                javaExecutable(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + tmp,
                "-Djava.net.useSystemProxies=false",
                "-jar",
                jar().toString(),
                argument),
            output);
    boolean finished =
        process.waitFor(
            EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    String observed =
        Files.readString(output, StandardCharsets.UTF_8)
            .strip();
    Files.delete(output);
    assertTrue(finished, "child timed out: " + observed);
    return new ProcessResult(process.exitValue(), observed);
  }

  private static Process start(
      List<String> command, Path output) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repository().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.toFile());
    builder.environment().clear();
    return builder.start();
  }

  private static void awaitReady(
      Process writer,
      Path ready,
      Path output,
      CrashCase crashCase)
      throws Exception {
    long deadline =
        System.nanoTime() + START_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline
        && writer.isAlive()
        && !Files.isRegularFile(ready)) {
      Thread.sleep(20);
    }
    if (!Files.isRegularFile(ready)) {
      if (writer.isAlive()) {
        writer.destroyForcibly();
        writer.waitFor();
      }
      fail(
          crashCase.id()
              + " did not become ready; output="
              + (Files.exists(output)
                  ? Files.readString(output)
                  : "missing"));
    }
  }

  private static EvidenceSnapshot snapshot(Path home)
      throws IOException {
    Path state =
        home.resolve(".emergeos/offline-comparisons");
    Map<String, EvidenceFile> files = new LinkedHashMap<>();
    try (var entries = Files.list(state)) {
      for (Path file :
          entries.sorted(Comparator.comparing(Path::toString))
              .toList()) {
        byte[] bytes = Files.readAllBytes(file);
        files.put(
            file.getFileName().toString(),
            new EvidenceFile(
                bytes.length,
                Files.getLastModifiedTime(file).toMillis(),
                sha256(bytes)));
      }
    }
    return new EvidenceSnapshot(Map.copyOf(files));
  }

  private static void assertHarnessIsTestOnly()
      throws IOException {
    try (JarFile shipping = new JarFile(jar().toFile())) {
      assertNull(shipping.getEntry(HARNESS_CLASS_ENTRY));
      assertTrue(
          shipping.stream()
              .noneMatch(
                  entry ->
                      entry.getName()
                          .startsWith(
                              HARNESS_CLASS_ENTRY.replace(
                                  ".class", "$"))));
      List<String> shadowedProductionClasses;
      try (var paths = Files.walk(testClasses())) {
        shadowedProductionClasses =
            paths
                .filter(
                    path -> path.toString().endsWith(".class"))
                .map(testClasses()::relativize)
                .map(Path::toString)
                .map(
                    name ->
                        name.replace(
                            java.io.File.separatorChar, '/'))
                .filter(name -> shipping.getEntry(name) != null)
                .toList();
      }
      assertTrue(
          shadowedProductionClasses.isEmpty(),
          () ->
              "test classes shadow packaged production: "
                  + shadowedProductionClasses);
    }
    assertTrue(
        Files.isRegularFile(
            testClasses()
                .resolve(
                    HARNESS_MAIN.replace('.', '/') + ".class")));
  }

  private static void assertJarTextContains(
      JarFile jar, String entryName, String expected)
      throws IOException {
    var entry = jar.getJarEntry(entryName);
    assertTrue(entry != null, entryName);
    try (var input = jar.getInputStream(entry)) {
      String text =
          new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(text.isBlank(), entryName);
      assertTrue(text.contains(expected), entryName);
    }
  }

  private static void assertSafeOutput(String output) {
    assertFalse(output.contains("OPENAI_API_KEY"), output);
    assertFalse(output.contains("Authorization"), output);
    assertFalse(output.contains("Exception"), output);
    assertFalse(output.contains(repository().toString()), output);
    assertFalse(
        output.contains(
            "把看到的事实、自己的判断和下一步行动绑定在同一条可追溯链路里"),
        output);
  }

  private static Path privateDirectory(Path path)
      throws IOException {
    return Files.createDirectory(
        path,
        java.nio.file.attribute.PosixFilePermissions
            .asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------")));
  }

  private static Path jar() {
    return Path.of(
            System.getProperty("emerge.offline.it.jar"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return Path.of(
            System.getProperty(
                "emerge.offline.it.testClasses"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path repository() {
    return Path.of(
            System.getProperty("emerge.offline.it.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static String javaExecutable() {
    return Path.of(
            System.getProperty("java.home"), "bin", "java")
        .toString();
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

  private record CrashCase(
      String id,
      String phase,
      int verifyExitCode,
      String expectedVerification,
      String replayCode) {}

  private record ProcessResult(int exitCode, String output) {}

  private record EvidenceSnapshot(
      Map<String, EvidenceFile> files) {}

  private record EvidenceFile(
      long size, long modifiedAtMillis, String sha256) {}
}
