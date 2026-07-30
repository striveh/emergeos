package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosixEvalRunRecordStoreTest {

  @TempDir Path tempDir;

  @Test
  void unsupportedHardLinkFailsClosedWithStableCode()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = tempDir.resolve("target");

    PosixEvalRunRecordStore.Rejected rejected =
        assertThrows(
            PosixEvalRunRecordStore.Rejected.class,
            () ->
                PosixEvalRunRecordStore.createOnlyLink(
                    target,
                    pending,
                    (ignoredTarget, ignoredPending) -> {
                      throw new UnsupportedOperationException();
                    }));

    assertEquals(
        "RUN_RECORD_LINK_COMMIT_UNSUPPORTED", rejected.code());
    assertFalse(Files.exists(target));
    assertTrue(Files.exists(pending));
  }

  @Test
  void duplicateTargetFailsClosedWithoutInvokingFallback()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = Files.createFile(tempDir.resolve("target"));

    PosixEvalRunRecordStore.Rejected rejected =
        assertThrows(
            PosixEvalRunRecordStore.Rejected.class,
            () ->
                PosixEvalRunRecordStore.createOnlyLink(
                    target,
                    pending,
                    (ignoredTarget, ignoredPending) -> {
                      throw new FileAlreadyExistsException(
                          target.toString());
                    }));

    assertEquals("RUN_RECORD_ALREADY_EXISTS", rejected.code());
    assertTrue(Files.exists(target));
    assertTrue(Files.exists(pending));
  }

  @Test
  void otherLinkIoFailurePropagatesWithoutFallback()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = tempDir.resolve("target");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                PosixEvalRunRecordStore.createOnlyLink(
                    target,
                    pending,
                    (ignoredTarget, ignoredPending) -> {
                      throw new IOException("synthetic-link-failure");
                    }));

    assertEquals("synthetic-link-failure", failure.getMessage());
    assertFalse(Files.exists(target));
    assertTrue(Files.exists(pending));
  }

  @Test
  void cleanupFailureWithSameCommittedInodeKeepsRecoverableResidue()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = tempDir.resolve("target");
    Files.createLink(target, pending);

    PosixEvalRunRecordStore.cleanupCommittedPending(
        pending,
        target,
        ignored -> {
          throw new IOException("synthetic-cleanup-failure");
        });

    assertTrue(Files.isSameFile(target, pending));
  }

  @Test
  void cleanupFailureAfterPendingDisappearsIsAlreadyComplete()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = tempDir.resolve("target");
    Files.createLink(target, pending);

    PosixEvalRunRecordStore.cleanupCommittedPending(
        pending,
        target,
        observedPending -> {
          Files.delete(observedPending);
          throw new IOException("synthetic-post-delete-failure");
        });

    assertFalse(Files.exists(pending));
    assertTrue(Files.exists(target));
  }

  @Test
  void cleanupFailureWithDifferentInodeFailsClosed()
      throws Exception {
    Path pending = Files.createFile(tempDir.resolve("pending"));
    Path target = Files.createFile(tempDir.resolve("target"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                PosixEvalRunRecordStore.cleanupCommittedPending(
                    pending,
                    target,
                    ignored -> {
                      throw new IOException(
                          "synthetic-cleanup-failure");
                    }));

    assertEquals("synthetic-cleanup-failure", failure.getMessage());
    assertFalse(Files.isSameFile(target, pending));
  }
}
