package io.emergeos.offlineharness;

import java.nio.file.Path;

/** Packaged entry point for the fixed, zero-network Pack 004 comparison. */
public final class Pack004ComparisonMain {

  private Pack004ComparisonMain() {}

  public static void main(String[] args) {
    int exitCode = run(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(String[] args) {
    if (args == null || args.length != 1) {
      return argumentsInvalid();
    }
    Path repositoryRoot;
    Path ownerHome;
    try {
      repositoryRoot =
          Path.of(requireProperty("user.dir"))
              .toAbsolutePath()
              .normalize();
      ownerHome =
          Path.of(requireProperty("user.home"))
              .toAbsolutePath()
              .normalize();
    } catch (RuntimeException invalidProperty) {
      return argumentsInvalid();
    }

    try {
      return switch (args[0]) {
        case "--execute" ->
            execute(repositoryRoot, ownerHome);
        case "--verify" ->
            verify(repositoryRoot, ownerHome);
        default -> argumentsInvalid();
      };
    } catch (PosixOfflineComparisonReportStore.Rejected rejected) {
      return rejected(rejected.code());
    } catch (OfflineComparisonWriteCommand.Rejected rejected) {
      return rejected(rejected.code());
    } catch (Pack004Loader.Rejected rejected) {
      return rejected("PACK_PRECONDITION_FAILED");
    } catch (RuntimeException failure) {
      return rejected("OFFLINE_COMPARISON_FAILED");
    }
  }

  private static int execute(
      Path repositoryRoot, Path ownerHome) {
    OfflineComparisonWriteCommand.WriteReceipt receipt =
        new OfflineComparisonWriteCommand()
            .execute(
                repositoryRoot,
                ownerHome,
                OfflineComparisonPersistenceObserver.noop());
    printStored(receipt.verification());
    return 0;
  }

  private static int verify(
      Path repositoryRoot, Path ownerHome) {
    OfflineComparisonReadCommand.DurableVerification
        verification =
            new OfflineComparisonReadCommand()
                .verify(repositoryRoot, ownerHome);
    System.out.println(
        "OFFLINE_COMPARISON_VERIFY"
            + " verdict="
            + verification.verdict()
            + " code="
            + verification.code()
            + " fileState="
            + verification.fileState()
            + " reportId="
            + verification.reportId()
            + " reportHash="
            + verification.reportIntegrityHash()
            + " fileSha256="
            + verification.fileSha256()
            + " bytes="
            + verification.byteLength());
    System.out.flush();
    return verification.verdict()
                == OfflineComparisonReadCommand.Verdict
                    .VERIFIED_PASSED
            || verification.verdict()
                == OfflineComparisonReadCommand.Verdict
                    .VERIFIED_FAILED
        ? 0
        : 3;
  }

  private static void printStored(
      OfflineComparisonReadCommand.DurableVerification
          verification) {
    System.out.println(
        "OFFLINE_COMPARISON_STORED"
            + " verdict="
            + verification.verdict()
            + " code="
            + verification.code()
            + " fileState="
            + verification.fileState()
            + " reportId="
            + verification.reportId()
            + " reportHash="
            + verification.reportIntegrityHash()
            + " fileSha256="
            + verification.fileSha256()
            + " bytes="
            + verification.byteLength());
    System.out.flush();
  }

  private static String requireProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing property");
    }
    return value;
  }

  private static int argumentsInvalid() {
    System.out.println(
        "OFFLINE_COMPARISON_REJECTED reason=ARGUMENTS_INVALID");
    System.out.flush();
    return 2;
  }

  private static int rejected(String code) {
    String safeCode =
        code != null && code.matches("[A-Z][A-Z0-9_]{0,127}")
            ? code
            : "OFFLINE_COMPARISON_FAILED";
    System.out.println(
        "OFFLINE_COMPARISON_REJECTED reason=" + safeCode);
    System.out.flush();
    return 2;
  }
}
