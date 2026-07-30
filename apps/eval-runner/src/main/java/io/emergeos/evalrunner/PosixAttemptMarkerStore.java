package io.emergeos.evalrunner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;

final class PosixAttemptMarkerStore {

  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final Path configuredOwnerHome;

  PosixAttemptMarkerStore(Path ownerHome) {
    this.configuredOwnerHome =
        Objects.requireNonNull(ownerHome, "ownerHome")
            .toAbsolutePath()
            .normalize();
  }

  Path acquire(String attemptId) {
    if (attemptId == null || !attemptId.matches("[a-f0-9]{64}")) {
      throw rejected("MARKER_ATTEMPT_ID_INVALID");
    }
    try {
      Path ownerHome = requireSafeOwnerHome();
      UserPrincipal owner =
          Files.getOwner(ownerHome, LinkOption.NOFOLLOW_LINKS);
      requireNoForeignAllowAcl(ownerHome, owner, "MARKER_HOME_UNSAFE");
      Path stateDirectory = ownerHome.resolve(".emergeos");
      createOrVerifyPrivateDirectory(stateDirectory, owner);
      Path attemptDirectory = stateDirectory.resolve("eval-attempts");
      createOrVerifyPrivateDirectory(attemptDirectory, owner);
      Path marker = attemptDirectory.resolve(attemptId + ".attempt");
      if (!marker.getParent().equals(attemptDirectory)) {
        throw rejected("MARKER_PATH_INVALID");
      }
      byte[] safeMetadata = safeMetadata(attemptId);
      try (FileChannel channel =
          FileChannel.open(
              marker,
              Set.of(
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))) {
        ByteBuffer bytes = ByteBuffer.wrap(safeMetadata);
        while (bytes.hasRemaining()) {
          channel.write(bytes);
        }
        channel.force(true);
      }
      if (Files.isSymbolicLink(marker)
          || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
          || !Files.getPosixFilePermissions(
                  marker, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)
          || !owner.equals(
              Files.getOwner(marker, LinkOption.NOFOLLOW_LINKS))) {
        throw rejected("MARKER_FILE_UNSAFE");
      }
      requireNoForeignAllowAcl(marker, owner, "MARKER_FILE_UNSAFE");
      forceDirectory(attemptDirectory);
      forceDirectory(stateDirectory);
      forceDirectory(ownerHome);
      return marker;
    } catch (Rejected failure) {
      throw failure;
    } catch (FileAlreadyExistsException replay) {
      throw rejected("MARKER_ALREADY_EXISTS");
    } catch (UnsupportedOperationException unsupportedPosix) {
      throw rejected("MARKER_POSIX_REQUIRED");
    } catch (IOException | RuntimeException failure) {
      throw rejected("MARKER_ACQUIRE_FAILED");
    }
  }

  private Path requireSafeOwnerHome() throws IOException {
    if (Files.isSymbolicLink(configuredOwnerHome)) {
      throw rejected("MARKER_HOME_UNSAFE");
    }
    Path ownerHome = configuredOwnerHome.toRealPath();
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(
            ownerHome, LinkOption.NOFOLLOW_LINKS);
    if (!Files.isDirectory(ownerHome, LinkOption.NOFOLLOW_LINKS)
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw rejected("MARKER_HOME_UNSAFE");
    }
    return ownerHome;
  }

  private static void createOrVerifyPrivateDirectory(
      Path directory, UserPrincipal owner) throws IOException {
    try {
      Files.createDirectory(
          directory,
          PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
    } catch (FileAlreadyExistsException exists) {
      // Existing owner state is acceptable only when it is the exact private directory.
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(
                directory, LinkOption.NOFOLLOW_LINKS)
            .equals(DIRECTORY_PERMISSIONS)
        || !owner.equals(
            Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS))) {
      throw rejected("MARKER_DIRECTORY_UNSAFE");
    }
    requireNoForeignAllowAcl(
        directory, owner, "MARKER_DIRECTORY_UNSAFE");
  }

  private static void requireNoForeignAllowAcl(
      Path path, UserPrincipal owner, String failureCode)
      throws IOException {
    if (VisibleAclPolicy.hasForeignAllow(path, owner)) {
      throw rejected(failureCode);
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel =
        FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static byte[] safeMetadata(String attemptId) {
    return String.join(
            "\n",
            "schemaVersion=1",
            "attemptId=" + attemptId,
            "caseId=" + SyntheticEvalCatalog.CASE_ID,
            "packSha256=" + SyntheticEvalCatalog.PACK_RAW_SHA256,
            "environmentSha256="
                + SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256,
            "captureRequestHash="
                + SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
            "taskHash=" + SyntheticEvalCatalog.EXPECTED_TASK_HASH,
            "executionProfileFingerprint="
                + SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT,
            "pricingProfileFingerprint="
                + SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT,
            "reservationUsd="
                + SyntheticEvalCatalog.profile().reservationUsd().toPlainString(),
            "maximumProviderRequests="
                + SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS,
            "")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
