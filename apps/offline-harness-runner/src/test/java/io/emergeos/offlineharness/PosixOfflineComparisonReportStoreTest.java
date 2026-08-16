package io.emergeos.offlineharness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosixOfflineComparisonReportStoreTest {

  @TempDir Path tempDir;

  @Test
  void publishesCanonicalReportOnceWithPrivateMetadata()
      throws Exception {
    Path home = privateDirectory("home");
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(home);
    OfflineComparisonReport report = report();

    PosixOfflineComparisonReportStore.Stored stored =
        store.save(report);
    PosixOfflineComparisonReportStore.ReadResult read =
        store.inspect();

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.FINAL,
        read.disposition());
    assertEquals(report, read.report());
    assertEquals(stored.fileSha256(), read.fileSha256());
    assertEquals(stored.byteLength(), read.byteLength());
    Path state = state(home);
    assertEquals(
        Set.of(
            PosixOfflineComparisonReportStore.CLAIM_FILE_NAME,
            PosixOfflineComparisonReportStore.REPORT_FILE_NAME),
        fileNames(state));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(state));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(
            state.resolve(
                PosixOfflineComparisonReportStore
                    .CLAIM_FILE_NAME)));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(
            state.resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME)));
    assertFalse(
        Files.exists(
            state.resolve(
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME),
            LinkOption.NOFOLLOW_LINKS));

    assertRejected("REPORT_ALREADY_EXISTS", () -> store.save(report));
  }

  @Test
  void pendingAndClaimRemainUnknownAfterInterruptedPublish()
      throws Exception {
    Path home = privateDirectory("pending-home");
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(home);

    assertRejected(
        "REPORT_PERSIST_FAILED",
        () ->
            store.save(
                report(),
                phase -> {
                  if (phase
                      == OfflineComparisonPersistenceObserver
                          .Phase.PENDING_FILE_DURABLE) {
                    throw new StopHere();
                  }
                }));

    PosixOfflineComparisonReportStore.ReadResult read =
        store.inspect();
    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.UNKNOWN,
        read.disposition());
    assertEquals(
        PosixOfflineComparisonReportStore.RecordState
            .PENDING_NON_AUTHORITATIVE,
        read.state());
    assertEquals("REPORT_COMMIT_INCOMPLETE", read.failureCode());
    assertRejected(
        "REPORT_COMMIT_INCOMPLETE",
        () -> store.save(report()));
  }

  @Test
  void validFinalIsAuthoritativeAsSoonAsLinkCommitIsObserved()
      throws Exception {
    Path home = privateDirectory("moved-home");
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(home);

    assertRejected(
        "REPORT_PERSIST_FAILED",
        () ->
            store.save(
                report(),
                phase -> {
                  if (phase
                      == OfflineComparisonPersistenceObserver
                          .Phase.REPORT_LINK_COMMIT_COMPLETE) {
                    throw new StopHere();
                  }
                }));

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.FINAL,
        store.inspect().disposition());
  }

  @Test
  void durableClaimMakesConcurrentWriterAStableLoser()
      throws Exception {
    Path home = privateDirectory("race-home");
    OfflineComparisonReport report = report();
    PosixOfflineComparisonReportStore first =
        new PosixOfflineComparisonReportStore(home);
    PosixOfflineComparisonReportStore second =
        new PosixOfflineComparisonReportStore(home);
    CountDownLatch claimDurable = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (ExecutorService executor =
        Executors.newFixedThreadPool(2)) {
      Future<PosixOfflineComparisonReportStore.Stored> winner =
          executor.submit(
              () ->
                  first.save(
                      report,
                      phase -> {
                        if (phase
                            == OfflineComparisonPersistenceObserver
                                .Phase.CLAIM_DURABLE) {
                          claimDurable.countDown();
                          await(release);
                        }
                      }));
      assertTrue(claimDurable.await(5, TimeUnit.SECONDS));

      assertRejected(
          "REPORT_COMMIT_INCOMPLETE",
          () -> second.save(report));
      release.countDown();
      assertEquals(report.reportId(), winner.get().reportId());
    }

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.FINAL,
        first.inspect().disposition());
  }

  @Test
  void safeClaimWriteWindowIsIncompleteAndUnsafeMetadataStaysInvalid()
      throws Exception {
    Path home = privateDirectory("claim-write-race-home");
    OfflineComparisonReport report = report();
    PosixOfflineComparisonReportStore first =
        new PosixOfflineComparisonReportStore(home);
    PosixOfflineComparisonReportStore second =
        new PosixOfflineComparisonReportStore(home);
    CountDownLatch claimCreated = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (ExecutorService executor =
        Executors.newSingleThreadExecutor()) {
      Future<PosixOfflineComparisonReportStore.Stored> winner =
          executor.submit(
              () ->
                  first.save(
                      report,
                      phase -> {
                        if (phase
                            == OfflineComparisonPersistenceObserver
                                .Phase.CLAIM_WRITE_STARTED) {
                          claimCreated.countDown();
                          await(release);
                        }
                      }));
      assertTrue(claimCreated.await(5, TimeUnit.SECONDS));

      try {
        PosixOfflineComparisonReportStore.ReadResult observed =
            second.inspect();
        assertEquals(
            PosixOfflineComparisonReportStore.Disposition.UNKNOWN,
            observed.disposition());
        assertEquals(
            PosixOfflineComparisonReportStore.RecordState
                .CLAIM_NON_AUTHORITATIVE,
            observed.state());
        assertEquals(
            "REPORT_COMMIT_INCOMPLETE", observed.failureCode());
        assertRejected(
            "REPORT_COMMIT_INCOMPLETE",
            () -> second.save(report));
      } finally {
        release.countDown();
      }
      assertEquals(report.reportId(), winner.get().reportId());
    }

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.FINAL,
        first.inspect().disposition());

    Path unsafeHome = privateDirectory("unsafe-claim-home");
    PosixOfflineComparisonReportStore unsafe =
        new PosixOfflineComparisonReportStore(unsafeHome);
    Path unsafeClaim =
        state(unsafeHome)
            .resolve(
                PosixOfflineComparisonReportStore.CLAIM_FILE_NAME);
    Files.createDirectories(
        unsafeClaim.getParent(),
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
    Files.write(
        unsafeClaim,
        new byte[0],
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        unsafeClaim,
        PosixFilePermissions.fromString("rw-r-----"));
    assertInvalidCode("REPORT_FILE_UNSAFE", unsafe.inspect());

    Path malformedHome = privateDirectory("malformed-claim-home");
    PosixOfflineComparisonReportStore malformed =
        new PosixOfflineComparisonReportStore(malformedHome);
    Path malformedClaim =
        state(malformedHome)
            .resolve(
                PosixOfflineComparisonReportStore.CLAIM_FILE_NAME);
    Files.createDirectories(
        malformedClaim.getParent(),
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
    byte[] malformedBytes =
        new byte[
            Files.readAllBytes(
                    state(home)
                        .resolve(
                            PosixOfflineComparisonReportStore
                                .CLAIM_FILE_NAME))
                .length];
    Files.write(
        malformedClaim,
        malformedBytes,
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        malformedClaim,
        PosixFilePermissions.fromString("rw-------"));
    assertInvalidCode(
        "REPORT_CLAIM_INVALID", malformed.inspect());

    Path shortMalformedHome =
        privateDirectory("short-malformed-claim-home");
    PosixOfflineComparisonReportStore shortMalformed =
        new PosixOfflineComparisonReportStore(shortMalformedHome);
    Path shortMalformedClaim =
        state(shortMalformedHome)
            .resolve(
                PosixOfflineComparisonReportStore.CLAIM_FILE_NAME);
    Files.createDirectories(
        shortMalformedClaim.getParent(),
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
    Files.write(
        shortMalformedClaim,
        "forged".getBytes(StandardCharsets.US_ASCII),
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        shortMalformedClaim,
        PosixFilePermissions.fromString("rw-------"));
    assertInvalidCode(
        "REPORT_CLAIM_INVALID", shortMalformed.inspect());

    Path conflictedHome =
        privateDirectory("partial-claim-conflict-home");
    PosixOfflineComparisonReportStore conflicted =
        new PosixOfflineComparisonReportStore(conflictedHome);
    Path conflictedState = state(conflictedHome);
    Files.createDirectories(
        conflictedState,
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
    Files.write(
        conflictedState.resolve(
            PosixOfflineComparisonReportStore.CLAIM_FILE_NAME),
        new byte[0],
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        conflictedState.resolve(
            PosixOfflineComparisonReportStore.CLAIM_FILE_NAME),
        PosixFilePermissions.fromString("rw-------"));
    Files.write(
        conflictedState.resolve(
            PosixOfflineComparisonReportStore.PENDING_FILE_NAME),
        new byte[0],
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        conflictedState.resolve(
            PosixOfflineComparisonReportStore.PENDING_FILE_NAME),
        PosixFilePermissions.fromString("rw-------"));
    assertInvalidCode(
        "REPORT_PATH_STATE_CONFLICT", conflicted.inspect());
  }

  @Test
  void targetCreatedAfterPrecheckIsNeverOverwritten()
      throws Exception {
    Path home = privateDirectory("commit-race-home");
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(home);
    byte[] competingBytes =
        "competing-authoritative-target"
            .getBytes(StandardCharsets.US_ASCII);

    assertRejected(
        "REPORT_COMMIT_INCOMPLETE",
        () ->
            store.save(
                report(),
                phase -> {
                  if (phase
                      == OfflineComparisonPersistenceObserver
                          .Phase.REPORT_COMMIT_STARTED) {
                    writeCompetingTarget(
                        state(home)
                            .resolve(
                                PosixOfflineComparisonReportStore
                                    .REPORT_FILE_NAME),
                        competingBytes);
                  }
                }));

    assertArrayEquals(
        competingBytes,
        Files.readAllBytes(
            state(home)
                .resolve(
                    PosixOfflineComparisonReportStore
                        .REPORT_FILE_NAME)));
  }

  @Test
  void rejectsUnsafeHomePrivateDirectoryAndSymlink()
      throws Exception {
    assertRejected(
        "REPORT_PATH_UNSAFE",
        () ->
            new PosixOfflineComparisonReportStore(
                Path.of("relative-home")));

    Path unsafeHome = privateDirectory("unsafe-home");
    Files.setPosixFilePermissions(
        unsafeHome,
        PosixFilePermissions.fromString("rwxrwx---"));
    assertRejected(
        "REPORT_PATH_UNSAFE",
        () ->
            new PosixOfflineComparisonReportStore(unsafeHome)
                .save(report()));

    Path home = privateDirectory("mode-home");
    Path emerge = Files.createDirectory(home.resolve(".emergeos"));
    Files.setPosixFilePermissions(
        emerge,
        PosixFilePermissions.fromString("rwxr-x---"));
    assertRejected(
        "REPORT_PATH_UNSAFE",
        () ->
            new PosixOfflineComparisonReportStore(home)
                .save(report()));

    Path target = privateDirectory("real-home");
    Path symlink = tempDir.resolve("home-link");
    Files.createSymbolicLink(symlink, target);
    assertRejected(
        "REPORT_PATH_UNSAFE",
        () ->
            new PosixOfflineComparisonReportStore(
                    symlink.toAbsolutePath())
                .save(report()));

    Path linkedStateHome = privateDirectory("linked-state-home");
    Path linkedStateTarget =
        privateDirectory("linked-state-target");
    Files.createSymbolicLink(
        linkedStateHome.resolve(".emergeos"),
        linkedStateTarget);
    assertRejected(
        "REPORT_PATH_UNSAFE",
        () ->
            new PosixOfflineComparisonReportStore(
                    linkedStateHome)
                .save(report()));
  }

  @Test
  void canonicalHomeIsPinnedBeforeAncestorAliasCanRetarget()
      throws Exception {
    Path firstRoot = privateDirectory("first-root");
    Path secondRoot = privateDirectory("second-root");
    Path firstHome =
        Files.createDirectory(
            firstRoot.resolve("home"),
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    Path secondHome =
        Files.createDirectory(
            secondRoot.resolve("home"),
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    Path alias = tempDir.resolve("owner-alias");
    Files.createSymbolicLink(alias, firstRoot);
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(
            alias.resolve("home").toAbsolutePath());

    Files.delete(alias);
    Files.createSymbolicLink(alias, secondRoot);
    store.save(report());

    assertTrue(Files.isRegularFile(state(firstHome).resolve(
        PosixOfflineComparisonReportStore.REPORT_FILE_NAME)));
    assertFalse(Files.exists(secondHome.resolve(".emergeos")));
  }

  @Test
  void missingFileIdentityFailsClosed() throws Exception {
    Method changed =
        PosixOfflineComparisonReportStore.class
            .getDeclaredMethod(
                "fileIdentityChanged",
                BasicFileAttributes.class,
                BasicFileAttributes.class);
    changed.setAccessible(true);
    BasicFileAttributes first =
        attributes(null, FileTime.fromMillis(1));
    BasicFileAttributes second =
        attributes(null, FileTime.fromMillis(1));

    assertTrue((boolean) changed.invoke(null, first, second));
  }

  @Test
  void readFailsClosedWhenMetadataOrIdentityChanges()
      throws Exception {
    Path mtimeHome = privateDirectory("mtime-home");
    savedStore(mtimeHome);
    AtomicBoolean mtimeChanged = new AtomicBoolean();
    PosixOfflineComparisonReportStore.ReadResult mtimeRead =
        new PosixOfflineComparisonReportStore(
                mtimeHome,
                path -> {
                  if (isReport(path)
                      && mtimeChanged.compareAndSet(false, true)) {
                    changeModifiedTime(path);
                  }
                })
            .inspect();
    assertInvalidCode(
        "REPORT_CHANGED_DURING_READ", mtimeRead);

    Path modeHome = privateDirectory("read-mode-home");
    savedStore(modeHome);
    AtomicBoolean modeChanged = new AtomicBoolean();
    PosixOfflineComparisonReportStore.ReadResult modeRead =
        new PosixOfflineComparisonReportStore(
                modeHome,
                path -> {
                  if (isReport(path)
                      && modeChanged.compareAndSet(false, true)) {
                    changeMode(path, "rw-r-----");
                  }
                })
            .inspect();
    assertInvalidCode("REPORT_FILE_UNSAFE", modeRead);

    Path replacementHome =
        privateDirectory("replacement-home");
    savedStore(replacementHome);
    AtomicBoolean replaced = new AtomicBoolean();
    PosixOfflineComparisonReportStore.ReadResult replacementRead =
        new PosixOfflineComparisonReportStore(
                replacementHome,
                path -> {
                  if (isReport(path)
                      && replaced.compareAndSet(false, true)) {
                    replaceWithSameBytes(path);
                  }
                })
            .inspect();
    assertInvalidCode(
        "REPORT_CHANGED_DURING_READ", replacementRead);
  }

  @Test
  void pendingLinkCleanupDuringReadIsAValidForwardTransition()
      throws Exception {
    Path home = privateDirectory("cleanup-race-home");
    savedStore(home);
    Path target =
        state(home)
            .resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME);
    Path pending =
        state(home)
            .resolve(
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME);
    Files.createLink(pending, target);
    AtomicBoolean cleaned = new AtomicBoolean();

    PosixOfflineComparisonReportStore.ReadResult read =
        new PosixOfflineComparisonReportStore(
                home,
                path -> {
                  if (isReport(path)
                      && cleaned.compareAndSet(false, true)) {
                    delete(pending);
                  }
                })
            .inspect();

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.FINAL,
        read.disposition());
    assertFalse(Files.exists(pending, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void pendingCleanupCannotHideTargetDeletionOrReplacement()
      throws Exception {
    Path deletedHome = privateDirectory("deleted-target-home");
    savedStore(deletedHome);
    Path deletedTarget = reportPath(deletedHome);
    Path deletedPending = restorePendingLink(deletedHome);
    PosixOfflineComparisonReportStore.ReadResult deletedRead =
        new PosixOfflineComparisonReportStore(
                deletedHome,
                new PosixOfflineComparisonReportStore
                    .FileReadObserver() {
                  @Override
                  public void afterRead(Path ignored) {}

                  @Override
                  public void afterReportDecoded(Path ignored) {
                    delete(deletedPending);
                    delete(deletedTarget);
                  }
                })
            .inspect();
    assertInvalidCode(
        "REPORT_CHANGED_DURING_READ", deletedRead);

    Path replacedHome =
        privateDirectory("replaced-target-home");
    savedStore(replacedHome);
    Path replacedTarget = reportPath(replacedHome);
    Path replacedPending = restorePendingLink(replacedHome);
    PosixOfflineComparisonReportStore.ReadResult replacedRead =
        new PosixOfflineComparisonReportStore(
                replacedHome,
                new PosixOfflineComparisonReportStore
                    .FileReadObserver() {
                  @Override
                  public void afterRead(Path ignored) {}

                  @Override
                  public void afterReportDecoded(Path ignored) {
                    delete(replacedPending);
                    replaceWithSameBytes(replacedTarget);
                  }
                })
            .inspect();
    assertInvalidCode(
        "REPORT_CHANGED_DURING_READ", replacedRead);
  }

  @Test
  void invalidatesTamperedOversizedSymlinkAndConflictingStates()
      throws Exception {
    Path tamperedHome = privateDirectory("tampered-home");
    PosixOfflineComparisonReportStore tampered =
        savedStore(tamperedHome);
    Path tamperedFile =
        state(tamperedHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME);
    Files.writeString(
        tamperedFile,
        "{}",
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING);
    assertInvalid(tampered.inspect());

    Path oversizedHome = privateDirectory("oversized-home");
    PosixOfflineComparisonReportStore oversized =
        savedStore(oversizedHome);
    Files.write(
        state(oversizedHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME),
        new byte[
            OfflineComparisonReportJson.MAX_REPORT_BYTES + 1],
        StandardOpenOption.TRUNCATE_EXISTING);
    assertInvalid(oversized.inspect());

    Path symlinkHome = privateDirectory("symlink-home");
    PosixOfflineComparisonReportStore symlinkStore =
        savedStore(symlinkHome);
    Path finalFile =
        state(symlinkHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME);
    Path symlinkTarget =
        tempDir.resolve("symlink-target.json");
    Files.writeString(
        symlinkTarget,
        "{}",
        StandardCharsets.US_ASCII);
    Files.delete(finalFile);
    Files.createSymbolicLink(finalFile, symlinkTarget);
    assertInvalid(symlinkStore.inspect());

    Path conflictHome = privateDirectory("conflict-home");
    PosixOfflineComparisonReportStore conflict =
        savedStore(conflictHome);
    Files.writeString(
        state(conflictHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME),
        "partial",
        StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW);
    Files.setPosixFilePermissions(
        state(conflictHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME),
        PosixFilePermissions.fromString("rw-------"));
    PosixOfflineComparisonReportStore.ReadResult read =
        conflict.inspect();
    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.INVALID,
        read.disposition());
    assertEquals("REPORT_PATH_STATE_CONFLICT", read.failureCode());

    Path modeHome = privateDirectory("file-mode-home");
    PosixOfflineComparisonReportStore modeStore =
        savedStore(modeHome);
    Files.setPosixFilePermissions(
        state(modeHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .REPORT_FILE_NAME),
        PosixFilePermissions.fromString("rw-r-----"));
    assertInvalid(modeStore.inspect());

    Path claimHome = privateDirectory("claim-home");
    PosixOfflineComparisonReportStore claimStore =
        savedStore(claimHome);
    Files.writeString(
        state(claimHome)
            .resolve(
                PosixOfflineComparisonReportStore
                    .CLAIM_FILE_NAME),
        "forged",
        StandardCharsets.US_ASCII,
        StandardOpenOption.TRUNCATE_EXISTING);
    assertInvalid(claimStore.inspect());
  }

  @Test
  void unknownDirectoryEntryInvalidatesTheState() throws Exception {
    Path home = privateDirectory("unknown-home");
    PosixOfflineComparisonReportStore store = savedStore(home);
    Files.writeString(
        state(home).resolve("unexpected.txt"),
        "unexpected",
        StandardCharsets.US_ASCII);

    PosixOfflineComparisonReportStore.ReadResult read =
        store.inspect();

    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.INVALID,
        read.disposition());
    assertEquals(
        "REPORT_DIRECTORY_STATE_INVALID", read.failureCode());
  }

  private PosixOfflineComparisonReportStore savedStore(Path home) {
    PosixOfflineComparisonReportStore store =
        new PosixOfflineComparisonReportStore(home);
    store.save(report());
    return store;
  }

  private static void assertInvalid(
      PosixOfflineComparisonReportStore.ReadResult read) {
    assertEquals(
        PosixOfflineComparisonReportStore.Disposition.INVALID,
        read.disposition());
    assertFalse(read.failureCode().isBlank());
  }

  private static void assertInvalidCode(
      String expected,
      PosixOfflineComparisonReportStore.ReadResult read) {
    assertInvalid(read);
    assertEquals(expected, read.failureCode());
  }

  private static void assertRejected(
      String code, ThrowingRunnable runnable) {
    PosixOfflineComparisonReportStore.Rejected rejected =
        assertThrows(
            PosixOfflineComparisonReportStore.Rejected.class,
            runnable::run);
    assertEquals(code, rejected.code());
  }

  private Path privateDirectory(String name) throws IOException {
    return Files.createDirectory(
        tempDir.resolve(name),
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
  }

  private static Path state(Path home) {
    return home.resolve(".emergeos/offline-comparisons");
  }

  private static Set<String> fileNames(Path directory)
      throws IOException {
    try (var entries = Files.list(directory)) {
      return Set.copyOf(
          entries
              .map(path -> path.getFileName().toString())
              .toList());
    }
  }

  private static OfflineComparisonReport report() {
    return new Pack004ComparisonRunner().run(repository());
  }

  private static Path repository() {
    return Path.of(System.getProperty("emerge.offline.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "test latch interrupted", interrupted);
    }
  }

  private static void writeCompetingTarget(
      Path target, byte[] bytes) {
    try {
      Files.write(
          target, bytes, StandardOpenOption.CREATE_NEW);
      Files.setPosixFilePermissions(
          target,
          PosixFilePermissions.fromString("rw-------"));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static boolean isReport(Path path) {
    return PosixOfflineComparisonReportStore.REPORT_FILE_NAME
        .equals(path.getFileName().toString());
  }

  private static Path reportPath(Path home) {
    return state(home)
        .resolve(
            PosixOfflineComparisonReportStore.REPORT_FILE_NAME);
  }

  private static Path restorePendingLink(Path home) {
    Path target = reportPath(home);
    Path pending =
        state(home)
            .resolve(
                PosixOfflineComparisonReportStore
                    .PENDING_FILE_NAME);
    try {
      Files.createLink(pending, target);
      return pending;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void changeModifiedTime(Path path) {
    try {
      FileTime current = Files.getLastModifiedTime(path);
      Files.setLastModifiedTime(
          path, FileTime.fromMillis(current.toMillis() + 2000));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void changeMode(Path path, String mode) {
    try {
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(mode));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private void replaceWithSameBytes(Path target) {
    try {
      Path replacement =
          Files.createTempFile(
              tempDir, "same-bytes-replacement-", ".json");
      Files.write(
          replacement,
          Files.readAllBytes(target),
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.setPosixFilePermissions(
          replacement,
          PosixFilePermissions.fromString("rw-------"));
      Files.move(
          replacement,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void delete(Path path) {
    try {
      Files.delete(path);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static BasicFileAttributes attributes(
      Object fileKey, FileTime modified) {
    return new BasicFileAttributes() {
      @Override
      public FileTime lastModifiedTime() {
        return modified;
      }

      @Override
      public FileTime lastAccessTime() {
        return modified;
      }

      @Override
      public FileTime creationTime() {
        return modified;
      }

      @Override
      public boolean isRegularFile() {
        return true;
      }

      @Override
      public boolean isDirectory() {
        return false;
      }

      @Override
      public boolean isSymbolicLink() {
        return false;
      }

      @Override
      public boolean isOther() {
        return false;
      }

      @Override
      public long size() {
        return 1;
      }

      @Override
      public Object fileKey() {
        return fileKey;
      }
    };
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class StopHere extends RuntimeException {}
}
