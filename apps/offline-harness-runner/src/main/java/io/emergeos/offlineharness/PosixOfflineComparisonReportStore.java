package io.emergeos.offlineharness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * POSIX owner/mode-restricted, cooperative append-once storage for the fixed
 * Pack 004 report.
 *
 * <p>The persistent claim is the cooperative single-writer boundary. A
 * pending file is never authoritative. The reader never repairs, removes or
 * rewrites evidence. ACL entries are rejected when the Java filesystem
 * provider exposes them, but POSIX mode checks are not a hostile-local-user
 * authorization boundary on providers that hide ACLs. This store contains
 * only the frozen synthetic Pack 004 report.
 */
final class PosixOfflineComparisonReportStore {

  static final String REPORT_FILE_NAME =
      "pack004-reference-grounding-v1.report.json";
  static final String PENDING_FILE_NAME =
      REPORT_FILE_NAME + ".pending";
  static final String CLAIM_FILE_NAME =
      "pack004-reference-grounding-v1.claim";

  private static final Path STATE_RELATIVE_PATH =
      Path.of(".emergeos", "offline-comparisons");
  private static final Set<PosixFilePermission>
      DIRECTORY_PERMISSIONS =
          PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission>
      UNTRUSTED_HOME_WRITE_PERMISSIONS =
          Set.of(
              PosixFilePermission.GROUP_WRITE,
              PosixFilePermission.OTHERS_WRITE);
  private static final byte[] CLAIM_BYTES =
      ("emergeos-offline-comparison-claim-v1\n"
              + "reportFile="
              + REPORT_FILE_NAME
              + "\n")
          .getBytes(StandardCharsets.US_ASCII);

  private final Path ownerHome;
  private final FileReadObserver fileReadObserver;

  PosixOfflineComparisonReportStore(Path ownerHome) {
    this(ownerHome, FileReadObserver.noop());
  }

  PosixOfflineComparisonReportStore(
      Path ownerHome, FileReadObserver fileReadObserver) {
    if (ownerHome == null || !ownerHome.isAbsolute()) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    Objects.requireNonNull(fileReadObserver, "fileReadObserver");
    Path normalized = ownerHome.normalize();
    try {
      if (Files.isSymbolicLink(normalized)
          || !Files.isDirectory(
              normalized, LinkOption.NOFOLLOW_LINKS)) {
        throw rejected("REPORT_PATH_UNSAFE");
      }
      this.ownerHome = normalized.toRealPath();
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    this.fileReadObserver = fileReadObserver;
  }

  Stored save(OfflineComparisonReport report) {
    return save(
        report, OfflineComparisonPersistenceObserver.noop());
  }

  Stored save(
      OfflineComparisonReport report,
      OfflineComparisonPersistenceObserver observer) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(observer, "observer");
    byte[] bytes = OfflineComparisonReportJson.encode(report);
    try {
      TrustedDirectory trusted = ensureTrustedDirectory();
      ReadResult before = inspectTrusted(trusted);
      if (before.disposition() == Disposition.FINAL) {
        throw rejected("REPORT_ALREADY_EXISTS");
      }
      if (before.disposition() == Disposition.UNKNOWN
          && before.state() != RecordState.ABSENT) {
        throw rejected("REPORT_COMMIT_INCOMPLETE");
      }
      if (before.disposition() == Disposition.INVALID) {
        throw rejected(before.failureCode());
      }

      Path claim =
          trusted.directory().resolve(CLAIM_FILE_NAME);
      writeNewFile(
          claim,
          CLAIM_BYTES,
          () ->
              observer.observed(
                  OfflineComparisonPersistenceObserver.Phase
                      .CLAIM_WRITE_STARTED));
      requireSafeFile(
          claim,
          trusted.owner(),
          1,
          CLAIM_BYTES.length);
      if (!MessageDigest.isEqual(
          readBounded(
              claim,
              trusted.owner(),
              CLAIM_BYTES.length,
              CLAIM_BYTES.length),
          CLAIM_BYTES)) {
        throw rejected("REPORT_CLAIM_INVALID");
      }
      forceDirectory(trusted.directory());
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase.CLAIM_DURABLE);

      Path pending =
          trusted.directory().resolve(PENDING_FILE_NAME);
      writeNewFile(
          pending,
          bytes,
          () ->
              observer.observed(
                  OfflineComparisonPersistenceObserver.Phase
                      .PENDING_WRITE_STARTED));
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase
              .PENDING_FILE_FSYNC_COMPLETE);
      requireSafeFile(
          pending,
          trusted.owner(),
          1,
          OfflineComparisonReportJson.MAX_REPORT_BYTES);
      byte[] readBack =
          readBounded(
              pending,
              trusted.owner(),
              1,
              OfflineComparisonReportJson.MAX_REPORT_BYTES);
      if (!MessageDigest.isEqual(bytes, readBack)) {
        throw rejected("REPORT_READ_BACK_MISMATCH");
      }
      OfflineComparisonReport decoded =
          OfflineComparisonReportJson.decode(readBack);
      if (!decoded.equals(report)) {
        throw rejected("REPORT_READ_BACK_MISMATCH");
      }
      forceDirectory(trusted.directory());
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase
              .PENDING_FILE_DURABLE);

      Path target =
          trusted.directory().resolve(REPORT_FILE_NAME);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw rejected("REPORT_ALREADY_EXISTS");
      }
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase
              .REPORT_COMMIT_STARTED);
      try {
        Files.createLink(target, pending);
      } catch (UnsupportedOperationException unsupported) {
        throw rejected("REPORT_LINK_COMMIT_UNSUPPORTED");
      }
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase
              .REPORT_LINK_COMMIT_COMPLETE);
      forceDirectory(trusted.directory());
      try {
        Files.delete(pending);
      } catch (IOException cleanupFailure) {
        if (pendingLinkState(pending, target)
            == PendingLinkState.DIFFERENT_FILE) {
          throw cleanupFailure;
        }
      }
      forceDirectory(trusted.directory());

      requireSafeFile(
          target,
          trusted.owner(),
          1,
          OfflineComparisonReportJson.MAX_REPORT_BYTES);
      byte[] finalBytes =
          readBounded(
              target,
              trusted.owner(),
              1,
              OfflineComparisonReportJson.MAX_REPORT_BYTES);
      if (!MessageDigest.isEqual(bytes, finalBytes)
          || !OfflineComparisonReportJson.decode(finalBytes)
              .equals(report)) {
        throw rejected("REPORT_FINAL_READ_BACK_MISMATCH");
      }
      observer.observed(
          OfflineComparisonPersistenceObserver.Phase
              .REPORT_DIRECTORY_DURABLE);
      return new Stored(
          REPORT_FILE_NAME,
          bytes.length,
          sha256(bytes),
          report.reportId(),
          report.integrityHash());
    } catch (Rejected failure) {
      throw failure;
    } catch (FileAlreadyExistsException duplicate) {
      ReadResult observed = inspect();
      if (observed.disposition() == Disposition.FINAL) {
        throw rejected("REPORT_ALREADY_EXISTS");
      }
      throw rejected("REPORT_COMMIT_INCOMPLETE");
    } catch (IOException | RuntimeException failure) {
      throw rejected("REPORT_PERSIST_FAILED");
    }
  }

  ReadResult inspect() {
    try {
      TrustedDirectory trusted = findTrustedDirectory();
      if (trusted == null) {
        return ReadResult.unknown(
            RecordState.ABSENT, "REPORT_FILE_MISSING");
      }
      return inspectTrusted(trusted);
    } catch (Rejected failure) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT, failure.code());
    } catch (IOException | RuntimeException failure) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT,
          "REPORT_READ_FAILED");
    }
  }

  private ReadResult inspectTrusted(TrustedDirectory trusted)
      throws IOException {
    List<String> names;
    try (var entries = Files.list(trusted.directory())) {
      names =
          entries
              .map(path -> path.getFileName().toString())
              .sorted(Comparator.naturalOrder())
              .toList();
    }
    if (names.stream().anyMatch(name -> !knownName(name))) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT,
          "REPORT_DIRECTORY_STATE_INVALID");
    }

    Path claim =
        trusted.directory().resolve(CLAIM_FILE_NAME);
    Path pending =
        trusted.directory().resolve(PENDING_FILE_NAME);
    Path target =
        trusted.directory().resolve(REPORT_FILE_NAME);
    boolean claimExists =
        Files.exists(claim, LinkOption.NOFOLLOW_LINKS);
    boolean pendingExists =
        Files.exists(pending, LinkOption.NOFOLLOW_LINKS);
    boolean targetExists =
        Files.exists(target, LinkOption.NOFOLLOW_LINKS);

    if (!claimExists && !pendingExists && !targetExists) {
      return ReadResult.unknown(
          RecordState.ABSENT, "REPORT_FILE_MISSING");
    }
    if (pendingExists) {
      try {
        requireSafeFile(
            pending,
            trusted.owner(),
            0,
            OfflineComparisonReportJson.MAX_REPORT_BYTES);
      } catch (NoSuchFileException disappeared) {
        if (Files.notExists(
            pending, LinkOption.NOFOLLOW_LINKS)) {
          pendingExists = false;
        } else {
          throw disappeared;
        }
      } catch (Rejected unsafe) {
        return ReadResult.invalid(
            RecordState.PATH_STATE_CONFLICT, unsafe.code());
      }
    }
    if (claimExists) {
      try {
        byte[] claimBytes;
        try {
          claimBytes =
              readBounded(
                  claim,
                  trusted.owner(),
                  0,
                  CLAIM_BYTES.length);
        } catch (Rejected changed) {
          if ("REPORT_CHANGED_DURING_READ".equals(
                  changed.code())
              && !pendingExists
              && !targetExists) {
            return ReadResult.unknown(
                RecordState.CLAIM_NON_AUTHORITATIVE,
                "REPORT_COMMIT_INCOMPLETE");
          }
          throw changed;
        }
        if (claimBytes.length < CLAIM_BYTES.length) {
          if (pendingExists || targetExists) {
            return ReadResult.invalid(
                RecordState.PATH_STATE_CONFLICT,
                "REPORT_PATH_STATE_CONFLICT");
          }
          if (!isClaimPrefix(claimBytes)) {
            return ReadResult.invalid(
                RecordState.PATH_STATE_CONFLICT,
                "REPORT_CLAIM_INVALID");
          }
          return ReadResult.unknown(
              RecordState.CLAIM_NON_AUTHORITATIVE,
              "REPORT_COMMIT_INCOMPLETE");
        }
        if (!MessageDigest.isEqual(claimBytes, CLAIM_BYTES)) {
          return ReadResult.invalid(
              RecordState.PATH_STATE_CONFLICT,
              "REPORT_CLAIM_INVALID");
        }
      } catch (Rejected unsafe) {
        return ReadResult.invalid(
            RecordState.PATH_STATE_CONFLICT, unsafe.code());
      }
    }
    PendingLinkState pendingLinkState =
        targetExists && pendingExists
            ? pendingLinkState(pending, target)
            : PendingLinkState.ABSENT;
    if (targetExists
        && pendingExists
        && pendingLinkState == PendingLinkState.ABSENT) {
      pendingExists = false;
    }
    boolean committedPendingLink =
        pendingLinkState == PendingLinkState.SAME_FILE;
    if (targetExists
        && (!claimExists
            || (pendingExists && !committedPendingLink))) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT,
          "REPORT_PATH_STATE_CONFLICT");
    }
    if (!targetExists) {
      if (!claimExists && pendingExists) {
        return ReadResult.invalid(
            RecordState.PATH_STATE_CONFLICT,
            "REPORT_PATH_STATE_CONFLICT");
      }
      return ReadResult.unknown(
          pendingExists
              ? RecordState.PENDING_NON_AUTHORITATIVE
              : RecordState.CLAIM_NON_AUTHORITATIVE,
          "REPORT_COMMIT_INCOMPLETE");
    }

    try {
      requireSafeFile(
          target,
          trusted.owner(),
          1,
          OfflineComparisonReportJson.MAX_REPORT_BYTES);
      BoundedRead boundedRead =
          readBoundedWithIdentity(
              target,
              trusted.owner(),
              1,
              OfflineComparisonReportJson.MAX_REPORT_BYTES);
      byte[] bytes = boundedRead.bytes();
      OfflineComparisonReport report =
          OfflineComparisonReportJson.decode(bytes);
      fileReadObserver.afterReportDecoded(target);
      revalidateTrustedDirectory(trusted);
      if (committedPendingLink) {
        PendingLinkState after =
            pendingLinkState(pending, target);
        if (after == PendingLinkState.DIFFERENT_FILE) {
          throw rejected("REPORT_CHANGED_DURING_READ");
        }
      }
      requireCurrentIdentity(
          target,
          trusted.owner(),
          1,
          OfflineComparisonReportJson.MAX_REPORT_BYTES,
          boundedRead.identity());
      return ReadResult.finalReport(
          report, bytes.length, sha256(bytes));
    } catch (OfflineComparisonReportJson.Rejected invalidJson) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT,
          invalidJson.code());
    } catch (Rejected unsafe) {
      return ReadResult.invalid(
          RecordState.PATH_STATE_CONFLICT, unsafe.code());
    }
  }

  private TrustedDirectory ensureTrustedDirectory()
      throws IOException {
    UserPrincipal owner = requireTrustedHome();
    Path emergeHome = ownerHome.resolve(".emergeos");
    ensurePrivateDirectory(emergeHome, owner, ownerHome);
    Path state = ownerHome.resolve(STATE_RELATIVE_PATH);
    ensurePrivateDirectory(state, owner, emergeHome);
    return new TrustedDirectory(state, owner);
  }

  private TrustedDirectory findTrustedDirectory()
      throws IOException {
    UserPrincipal owner = requireTrustedHome();
    Path emergeHome = ownerHome.resolve(".emergeos");
    if (!Files.exists(
        emergeHome, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    requirePrivateDirectory(emergeHome, owner);
    Path state = ownerHome.resolve(STATE_RELATIVE_PATH);
    if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    requirePrivateDirectory(state, owner);
    return new TrustedDirectory(state, owner);
  }

  private UserPrincipal requireTrustedHome() throws IOException {
    if (Files.isSymbolicLink(ownerHome)
        || !Files.isDirectory(
            ownerHome, LinkOption.NOFOLLOW_LINKS)) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(
            ownerHome, LinkOption.NOFOLLOW_LINKS);
    if (permissions.stream()
        .anyMatch(
            UNTRUSTED_HOME_WRITE_PERMISSIONS::contains)) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    UserPrincipal owner =
        Files.getOwner(ownerHome, LinkOption.NOFOLLOW_LINKS);
    if (hasVisibleForeignAllowAcl(ownerHome, owner)) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    return owner;
  }

  private static void ensurePrivateDirectory(
      Path directory, UserPrincipal owner, Path parent)
      throws IOException {
    if (!Files.exists(
        directory, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createDirectory(
            directory,
            PosixFilePermissions.asFileAttribute(
                DIRECTORY_PERMISSIONS));
        forceDirectory(parent);
      } catch (FileAlreadyExistsException race) {
        // A concurrent creator must still pass the same validation.
      }
    }
    requirePrivateDirectory(directory, owner);
  }

  private static void requirePrivateDirectory(
      Path directory, UserPrincipal owner) throws IOException {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(
            directory, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(
                directory, LinkOption.NOFOLLOW_LINKS)
            .equals(DIRECTORY_PERMISSIONS)
        || !owner.equals(
            Files.getOwner(
                directory, LinkOption.NOFOLLOW_LINKS))
        || hasVisibleForeignAllowAcl(directory, owner)) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
  }

  private static void requireSafeFile(
      Path file,
      UserPrincipal owner,
      long minimumSize,
      long maximumSize)
      throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(
            file,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
    if (Files.isSymbolicLink(file)
        || !attributes.isRegularFile()
        || attributes.size() < minimumSize
        || attributes.size() > maximumSize
        || !Files.getPosixFilePermissions(
                file, LinkOption.NOFOLLOW_LINKS)
            .equals(FILE_PERMISSIONS)
        || !owner.equals(
            Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))
        || hasVisibleForeignAllowAcl(file, owner)) {
      throw rejected("REPORT_FILE_UNSAFE");
    }
  }

  private static void writeNewFile(Path file, byte[] bytes)
      throws IOException {
    writeNewFile(file, bytes, () -> {});
  }

  private static void writeNewFile(
      Path file, byte[] bytes, Runnable afterCreate)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            file,
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(
                FILE_PERMISSIONS))) {
      afterCreate.run();
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private byte[] readBounded(
      Path file,
      UserPrincipal owner,
      long minimum,
      int maximum)
      throws IOException {
    return readBoundedWithIdentity(
            file, owner, minimum, maximum)
        .bytes();
  }

  private BoundedRead readBoundedWithIdentity(
      Path file,
      UserPrincipal owner,
      long minimum,
      int maximum)
      throws IOException {
    requireSafeFile(file, owner, minimum, maximum);
    BasicFileAttributes before =
        Files.readAttributes(
            file,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()
        || before.size() < minimum
        || before.size() > maximum) {
      throw rejected("REPORT_SIZE_INVALID");
    }
    Set<OpenOption> options =
        Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS);
    byte[] bytes;
    try (SeekableByteChannel channel =
            Files.newByteChannel(file, options);
        ByteArrayOutputStream output =
            new ByteArrayOutputStream(
                (int) Math.min(before.size(), maximum))) {
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (true) {
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          continue;
        }
        if (output.size() + read > maximum) {
          throw rejected("REPORT_SIZE_INVALID");
        }
        output.write(buffer.array(), 0, read);
        buffer.clear();
      }
      bytes = output.toByteArray();
    }
    fileReadObserver.afterRead(file);
    requireSafeFile(file, owner, minimum, maximum);
    BasicFileAttributes after =
        Files.readAttributes(
            file,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || before.size() != bytes.length
        || after.size() != bytes.length
        || fileIdentityChanged(before, after)) {
      throw rejected("REPORT_CHANGED_DURING_READ");
    }
    return new BoundedRead(bytes, FileIdentity.from(after));
  }

  private static boolean fileIdentityChanged(
      BasicFileAttributes before, BasicFileAttributes after) {
    Object beforeKey = before.fileKey();
    Object afterKey = after.fileKey();
    return beforeKey == null
        || afterKey == null
        || !beforeKey.equals(afterKey)
        || !before.lastModifiedTime()
            .equals(after.lastModifiedTime())
        || !before.creationTime().equals(after.creationTime());
  }

  private static void forceDirectory(Path directory)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void requireCurrentIdentity(
      Path file,
      UserPrincipal owner,
      long minimum,
      long maximum,
      FileIdentity expected)
      throws IOException {
    try {
      requireSafeFile(
          file, owner, minimum, maximum);
      BasicFileAttributes current =
          Files.readAttributes(
              file,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      if (!expected.matches(current)) {
        throw rejected("REPORT_CHANGED_DURING_READ");
      }
    } catch (NoSuchFileException disappeared) {
      throw rejected("REPORT_CHANGED_DURING_READ");
    }
  }

  private void revalidateTrustedDirectory(
      TrustedDirectory trusted) throws IOException {
    UserPrincipal currentOwner = requireTrustedHome();
    if (!trusted.owner().equals(currentOwner)) {
      throw rejected("REPORT_PATH_UNSAFE");
    }
    Path emergeHome = ownerHome.resolve(".emergeos");
    requirePrivateDirectory(emergeHome, currentOwner);
    requirePrivateDirectory(trusted.directory(), currentOwner);
  }

  private static boolean sameFileIdentity(
      Path first, Path second) throws IOException {
    BasicFileAttributes firstAttributes =
        Files.readAttributes(
            first,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
    BasicFileAttributes secondAttributes =
        Files.readAttributes(
            second,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
    Object firstKey = firstAttributes.fileKey();
    Object secondKey = secondAttributes.fileKey();
    return firstAttributes.isRegularFile()
        && secondAttributes.isRegularFile()
        && firstKey != null
        && firstKey.equals(secondKey);
  }

  private static PendingLinkState pendingLinkState(
      Path pending, Path target) throws IOException {
    try {
      return sameFileIdentity(pending, target)
          ? PendingLinkState.SAME_FILE
          : PendingLinkState.DIFFERENT_FILE;
    } catch (NoSuchFileException disappeared) {
      if (Files.notExists(
          pending, LinkOption.NOFOLLOW_LINKS)) {
        return PendingLinkState.ABSENT;
      }
      throw disappeared;
    }
  }

  private static boolean hasVisibleForeignAllowAcl(
      Path path, UserPrincipal owner) throws IOException {
    AclFileAttributeView view =
        Files.getFileAttributeView(
            path,
            AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
    return view != null
        && view.getAcl().stream()
            .anyMatch(
                entry ->
                    entry.type() == AclEntryType.ALLOW
                        && !owner.equals(entry.principal()));
  }

  private static boolean knownName(String name) {
    return CLAIM_FILE_NAME.equals(name)
        || PENDING_FILE_NAME.equals(name)
        || REPORT_FILE_NAME.equals(name);
  }

  private static boolean isClaimPrefix(byte[] bytes) {
    for (int index = 0; index < bytes.length; index++) {
      if (bytes[index] != CLAIM_BYTES[index]) {
        return false;
      }
    }
    return true;
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

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  enum Disposition {
    FINAL,
    UNKNOWN,
    INVALID
  }

  enum RecordState {
    ABSENT,
    CLAIM_NON_AUTHORITATIVE,
    PENDING_NON_AUTHORITATIVE,
    FINAL,
    PATH_STATE_CONFLICT
  }

  record Stored(
      String fileName,
      long byteLength,
      String fileSha256,
      String reportId,
      String reportIntegrityHash) {
    Stored {
      Objects.requireNonNull(fileName, "fileName");
      Objects.requireNonNull(fileSha256, "fileSha256");
      Objects.requireNonNull(reportId, "reportId");
      Objects.requireNonNull(
          reportIntegrityHash, "reportIntegrityHash");
    }
  }

  record ReadResult(
      Disposition disposition,
      RecordState state,
      OfflineComparisonReport report,
      long byteLength,
      String fileSha256,
      String failureCode) {

    ReadResult {
      Objects.requireNonNull(disposition, "disposition");
      Objects.requireNonNull(state, "state");
      if (disposition == Disposition.FINAL) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(fileSha256, "fileSha256");
        if (failureCode != null || byteLength < 1) {
          throw new IllegalArgumentException(
              "invalid final read result");
        }
      } else if (report != null
          || fileSha256 != null
          || failureCode == null
          || byteLength != 0) {
        throw new IllegalArgumentException(
            "invalid non-final read result");
      }
    }

    static ReadResult finalReport(
        OfflineComparisonReport report,
        long byteLength,
        String fileSha256) {
      return new ReadResult(
          Disposition.FINAL,
          RecordState.FINAL,
          report,
          byteLength,
          fileSha256,
          null);
    }

    static ReadResult unknown(
        RecordState state, String code) {
      return new ReadResult(
          Disposition.UNKNOWN,
          state,
          null,
          0,
          null,
          code);
    }

    static ReadResult invalid(
        RecordState state, String code) {
      return new ReadResult(
          Disposition.INVALID,
          state,
          null,
          0,
          null,
          code);
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

  private record TrustedDirectory(
      Path directory, UserPrincipal owner) {}

  private record BoundedRead(
      byte[] bytes, FileIdentity identity) {
    BoundedRead {
      Objects.requireNonNull(bytes, "bytes");
      Objects.requireNonNull(identity, "identity");
    }
  }

  private record FileIdentity(
      Object fileKey,
      long size,
      java.nio.file.attribute.FileTime lastModifiedTime,
      java.nio.file.attribute.FileTime creationTime) {

    FileIdentity {
      Objects.requireNonNull(fileKey, "fileKey");
      Objects.requireNonNull(
          lastModifiedTime, "lastModifiedTime");
      Objects.requireNonNull(creationTime, "creationTime");
    }

    static FileIdentity from(BasicFileAttributes attributes) {
      return new FileIdentity(
          attributes.fileKey(),
          attributes.size(),
          attributes.lastModifiedTime(),
          attributes.creationTime());
    }

    boolean matches(BasicFileAttributes attributes) {
      return attributes.isRegularFile()
          && fileKey.equals(attributes.fileKey())
          && size == attributes.size()
          && lastModifiedTime.equals(
              attributes.lastModifiedTime())
          && creationTime.equals(attributes.creationTime());
    }
  }

  private enum PendingLinkState {
    ABSENT,
    SAME_FILE,
    DIFFERENT_FILE
  }

  @FunctionalInterface
  interface FileReadObserver {
    void afterRead(Path file);

    default void afterReportDecoded(Path file) {}

    static FileReadObserver noop() {
      return ignored -> {};
    }
  }
}
