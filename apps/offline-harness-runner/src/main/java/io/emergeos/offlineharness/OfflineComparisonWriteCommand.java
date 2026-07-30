package io.emergeos.offlineharness;

import java.nio.file.Path;
import java.util.Objects;

final class OfflineComparisonWriteCommand {

  WriteReceipt execute(
      Path repositoryRoot,
      Path ownerHome,
      OfflineComparisonPersistenceObserver observer) {
    Objects.requireNonNull(observer, "observer");
    OfflineComparisonReport report =
        new Pack004ComparisonRunner().run(repositoryRoot);
    Pack004ComparisonReportVerifier.Verification inMemory =
        new Pack004ComparisonReportVerifier()
            .verify(repositoryRoot, report);
    if (inMemory.verdict()
        == Pack004ComparisonReportVerifier.Verdict.INVALID) {
      throw rejected(inMemory.failureCode());
    }

    PosixOfflineComparisonReportStore.Stored stored =
        new PosixOfflineComparisonReportStore(ownerHome)
            .save(report, observer);
    OfflineComparisonReadCommand.DurableVerification durable =
        new OfflineComparisonReadCommand()
            .verify(repositoryRoot, ownerHome);
    if ((inMemory.verdict()
                == Pack004ComparisonReportVerifier.Verdict
                    .VERIFIED_PASSED
            && durable.verdict()
                != OfflineComparisonReadCommand.Verdict
                    .VERIFIED_PASSED)
        || (inMemory.verdict()
                == Pack004ComparisonReportVerifier.Verdict
                    .VERIFIED_FAILED
            && durable.verdict()
                != OfflineComparisonReadCommand.Verdict
                    .VERIFIED_FAILED)
        || !stored.reportId().equals(durable.reportId())
        || !stored.reportIntegrityHash()
            .equals(durable.reportIntegrityHash())
        || !stored.fileSha256().equals(durable.fileSha256())
        || stored.byteLength() != durable.byteLength()) {
      throw rejected("REPORT_POST_PUBLISH_VERIFY_FAILED");
    }
    return new WriteReceipt(stored, durable);
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  record WriteReceipt(
      PosixOfflineComparisonReportStore.Stored stored,
      OfflineComparisonReadCommand.DurableVerification
          verification) {
    WriteReceipt {
      Objects.requireNonNull(stored, "stored");
      Objects.requireNonNull(verification, "verification");
    }
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code);
      this.code = Objects.requireNonNull(code, "code");
    }

    String code() {
      return code;
    }
  }
}
