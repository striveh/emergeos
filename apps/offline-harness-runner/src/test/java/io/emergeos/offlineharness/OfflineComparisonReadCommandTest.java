package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineComparisonReadCommandTest {

  @TempDir Path tempDir;

  @Test
  void absentReadIsUnknownAndDoesNotCreateState()
      throws Exception {
    Path home = privateDirectory("absent-home");

    OfflineComparisonReadCommand.DurableVerification result =
        new OfflineComparisonReadCommand()
            .verify(repository(), home);

    assertEquals(
        OfflineComparisonReadCommand.Verdict.UNKNOWN,
        result.verdict());
    assertEquals(
        PosixOfflineComparisonReportStore.RecordState.ABSENT,
        result.fileState());
    assertEquals("REPORT_FILE_MISSING", result.code());
    assertFalse(Files.exists(home.resolve(".emergeos")));
  }

  @Test
  void canonicalFinalIsReplayedAndVerified() throws Exception {
    Path home = privateDirectory("verified-home");
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(repository());
    new PosixOfflineComparisonReportStore(home).save(report);

    OfflineComparisonReadCommand.DurableVerification result =
        new OfflineComparisonReadCommand()
            .verify(repository(), home);

    assertEquals(
        OfflineComparisonReadCommand.Verdict.VERIFIED_PASSED,
        result.verdict());
    assertEquals("NONE", result.code());
    assertEquals(report.reportId(), result.reportId());
    assertEquals(
        report.integrityHash(), result.reportIntegrityHash());
  }

  @Test
  void independentlyRejectsCanonicalFullyResealedReportIdAttack()
      throws Exception {
    Path home = privateDirectory("resealed-home");
    OfflineComparisonReport genuine =
        new Pack004ComparisonRunner().run(repository());
    new PosixOfflineComparisonReportStore(home).save(genuine);
    OfflineComparisonReport changed =
        copy(
            genuine,
            "comparison-report-" + "0".repeat(64),
            "UNSEALED");
    changed =
        copy(
            changed,
            changed.reportId(),
            ComparisonIntegrityHashes.reportHash(changed));
    Files.write(
        home.resolve(
            ".emergeos/offline-comparisons/"
                + PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME),
        OfflineComparisonReportJson.encode(changed),
        StandardOpenOption.TRUNCATE_EXISTING);

    OfflineComparisonReadCommand.DurableVerification result =
        new OfflineComparisonReadCommand()
            .verify(repository(), home);

    assertEquals(
        OfflineComparisonReadCommand.Verdict.INVALID,
        result.verdict());
    assertEquals(
        PosixOfflineComparisonReportStore.RecordState.FINAL,
        result.fileState());
    assertEquals("REPORT_ID_MISMATCH", result.code());
  }

  private static OfflineComparisonReport copy(
      OfflineComparisonReport source,
      String reportId,
      String integrityHash) {
    return new OfflineComparisonReport(
        source.schemaVersion(),
        source.reportKind(),
        reportId,
        source.integrityProfile(),
        source.packRawSha256(),
        source.taskId(),
        source.suiteId(),
        source.variable(),
        source.repetitions(),
        source.runnerVersion(),
        source.candidateGeneratorVersion(),
        source.h0VerifierVersion(),
        source.h1VerifierVersion(),
        source.frozenTime(),
        source.pairs(),
        source.evaluations(),
        source.summary(),
        source.effects(),
        source.status(),
        source.issues(),
        integrityHash);
  }

  private Path privateDirectory(String name) throws IOException {
    return Files.createDirectory(
        tempDir.resolve(name),
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
  }

  private static Path repository() {
    return Path.of(System.getProperty("emerge.offline.repo"))
        .toAbsolutePath()
        .normalize();
  }
}
