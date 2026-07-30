package io.emergeos.offlineharness;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only durable report command.
 *
 * <p>This path never invokes the comparison Runner or candidate generator.
 * It first loads canonical bytes from the fixed POSIX mode-restricted file
 * and then invokes the independent replay verifier.
 */
final class OfflineComparisonReadCommand {

  DurableVerification verify(
      Path repositoryRoot, Path ownerHome) {
    PosixOfflineComparisonReportStore.ReadResult read =
        new PosixOfflineComparisonReportStore(ownerHome).inspect();
    if (read.disposition()
        == PosixOfflineComparisonReportStore.Disposition.UNKNOWN) {
      return DurableVerification.nonFinal(
          Verdict.UNKNOWN, read.state(), read.failureCode());
    }
    if (read.disposition()
        == PosixOfflineComparisonReportStore.Disposition.INVALID) {
      return DurableVerification.nonFinal(
          Verdict.INVALID, read.state(), read.failureCode());
    }

    Pack004ComparisonReportVerifier.Verification verification =
        new Pack004ComparisonReportVerifier()
            .verify(repositoryRoot, read.report());
    if (verification.verdict()
        == Pack004ComparisonReportVerifier.Verdict.INVALID) {
      return DurableVerification.nonFinal(
          Verdict.INVALID,
          read.state(),
          verification.failureCode());
    }
    Verdict verdict =
        verification.verdict()
                == Pack004ComparisonReportVerifier.Verdict
                    .VERIFIED_PASSED
            ? Verdict.VERIFIED_PASSED
            : Verdict.VERIFIED_FAILED;
    return DurableVerification.verified(
        verdict,
        read.state(),
        read.report(),
        read.byteLength(),
        read.fileSha256());
  }

  enum Verdict {
    VERIFIED_PASSED,
    VERIFIED_FAILED,
    UNKNOWN,
    INVALID
  }

  record DurableVerification(
      Verdict verdict,
      PosixOfflineComparisonReportStore.RecordState fileState,
      String code,
      String reportId,
      String reportIntegrityHash,
      long byteLength,
      String fileSha256) {

    DurableVerification {
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(fileState, "fileState");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(reportId, "reportId");
      Objects.requireNonNull(
          reportIntegrityHash, "reportIntegrityHash");
      Objects.requireNonNull(fileSha256, "fileSha256");
      boolean verified =
          verdict == Verdict.VERIFIED_PASSED
              || verdict == Verdict.VERIFIED_FAILED;
      if (verified
          != ("NONE".equals(code)
              && !"NONE".equals(reportId)
              && !"NONE".equals(reportIntegrityHash)
              && byteLength > 0
              && !"NONE".equals(fileSha256))) {
        throw new IllegalArgumentException(
            "invalid durable verification");
      }
    }

    private static DurableVerification verified(
        Verdict verdict,
        PosixOfflineComparisonReportStore.RecordState state,
        OfflineComparisonReport report,
        long byteLength,
        String fileSha256) {
      return new DurableVerification(
          verdict,
          state,
          "NONE",
          report.reportId(),
          report.integrityHash(),
          byteLength,
          fileSha256);
    }

    private static DurableVerification nonFinal(
        Verdict verdict,
        PosixOfflineComparisonReportStore.RecordState state,
        String code) {
      return new DurableVerification(
          verdict,
          state,
          Objects.requireNonNull(code, "code"),
          "NONE",
          "NONE",
          0,
          "NONE");
    }
  }
}
