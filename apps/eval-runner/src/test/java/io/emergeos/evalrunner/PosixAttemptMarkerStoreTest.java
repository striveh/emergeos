package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosixAttemptMarkerStoreTest {

  @TempDir Path tempDir;

  @Test
  void createsOneOwnerPrivateSafeMarkerWithoutSensitiveContent()
      throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("home"));
    PosixAttemptMarkerStore store =
        new PosixAttemptMarkerStore(home);

    Path marker =
        store.acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);

    assertEquals(
        PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(marker.getParent()));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(marker));
    String metadata =
        Files.readString(marker, StandardCharsets.UTF_8);
    assertTrue(
        metadata.contains(
            "attemptId=" + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));
    assertTrue(
        metadata.contains(
            "taskHash=" + SyntheticEvalCatalog.EXPECTED_TASK_HASH));
    assertFalse(metadata.contains(SyntheticEvalCatalog.CONTENT));
    assertFalse(metadata.contains("OPENAI_API_KEY"));
    assertRejected(
        "MARKER_ALREADY_EXISTS",
        () -> store.acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));
  }

  @Test
  void concurrentAcquisitionHasExactlyOneWinner() throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("concurrent-home"));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Future<String>> futures = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    new PosixAttemptMarkerStore(home)
                        .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
                    return "ACQUIRED";
                  } catch (PosixAttemptMarkerStore.Rejected rejected) {
                    return rejected.code();
                  }
                }));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> future : futures) {
        outcomes.add(future.get());
      }
      outcomes.sort(String::compareTo);
      assertEquals(
          List.of("ACQUIRED", "MARKER_ALREADY_EXISTS"),
          outcomes);
    }
  }

  @Test
  void rejectsSymlinkAndOverbroadStateDirectory() throws Exception {
    Path symlinkHome = Files.createDirectory(tempDir.resolve("symlink-home"));
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Files.createSymbolicLink(symlinkHome.resolve(".emergeos"), outside);
    assertRejected(
        "MARKER_DIRECTORY_UNSAFE",
        () ->
            new PosixAttemptMarkerStore(symlinkHome)
                .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));

    Path broadHome = Files.createDirectory(tempDir.resolve("broad-home"));
    Path state = Files.createDirectory(broadHome.resolve(".emergeos"));
    Files.setPosixFilePermissions(
        state, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertRejected(
        "MARKER_DIRECTORY_UNSAFE",
        () ->
            new PosixAttemptMarkerStore(broadHome)
                .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));
  }

  @Test
  void canonicalizesAncestorButRejectsWritableOrFinalSymlinkHome()
      throws Exception {
    Path realParent = Files.createDirectory(tempDir.resolve("real-parent"));
    Path realHome = Files.createDirectory(realParent.resolve("home"));
    Path aliasParent = tempDir.resolve("alias-parent");
    Files.createSymbolicLink(aliasParent, realParent);

    Path marker =
        new PosixAttemptMarkerStore(aliasParent.resolve("home"))
            .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
    assertTrue(marker.toRealPath().startsWith(realHome.toRealPath()));

    Path writableHome =
        Files.createDirectory(tempDir.resolve("writable-home"));
    Files.setPosixFilePermissions(
        writableHome, PosixFilePermissions.fromString("rwxrwxrwx"));
    assertRejected(
        "MARKER_HOME_UNSAFE",
        () ->
            new PosixAttemptMarkerStore(writableHome)
                .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));

    Path finalTarget =
        Files.createDirectory(tempDir.resolve("final-target"));
    Path finalLink = tempDir.resolve("final-link");
    Files.createSymbolicLink(finalLink, finalTarget);
    assertRejected(
        "MARKER_HOME_UNSAFE",
        () ->
            new PosixAttemptMarkerStore(finalLink)
                .acquire(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));
  }

  private static void assertRejected(
      String expectedCode, Runnable action) {
    PosixAttemptMarkerStore.Rejected rejected =
        assertThrows(PosixAttemptMarkerStore.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }
}
