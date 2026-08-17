package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack009ProcessSupportTest {

  @TempDir Path tempDir;

  @Test
  void durableCreateNewPublishesOnlyAfterCompleteBytesAreForced()
      throws Exception {
    Path target = tempDir.resolve("writer.ready");
    String receipt =
        "PACK010_RACE_READY tx=TX_B actor=A expectedSequence=14";
    CountDownLatch staged = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread writer =
        Thread.ofPlatform()
            .name("durable-create-new-writer")
            .daemon(true)
            .unstarted(
                () -> {
                  try {
                    Pack009ProcessSupport.writeDurableCreateNew(
                        target,
                        receipt,
                        () -> {
                          staged.countDown();
                          await(release);
                        });
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                  }
                });
    writer.start();

    try {
      assertTrue(staged.await(5, TimeUnit.SECONDS));
      assertFalse(
          Files.exists(target, LinkOption.NOFOLLOW_LINKS),
          "the public path must not expose staged zero or partial bytes");
    } finally {
      release.countDown();
      writer.join(Duration.ofSeconds(5));
    }
    assertFalse(writer.isAlive());
    assertNull(failure.get());
    assertEquals(receipt, Pack009ProcessSupport.readBounded(target));
    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(target));
    assertNoStagedFiles();
  }

  @Test
  void durableCreateNewNeverOverwritesAnExistingReceipt()
      throws Exception {
    Path target = tempDir.resolve("writer.result");
    Pack009ProcessSupport.writeDurableCreateNew(target, "first");

    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            Pack009ProcessSupport.writeDurableCreateNew(
                target, "first"));
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            Pack009ProcessSupport.writeDurableCreateNew(
                target, "second"));
    assertEquals("first", Pack009ProcessSupport.readBounded(target));
    assertNoStagedFiles();
  }

  @Test
  void concurrentDurableCreateNewHasExactlyOneCompleteWinner()
      throws Exception {
    Path target = tempDir.resolve("shared.ready");
    CountDownLatch staged = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    AtomicReference<Throwable> secondFailure = new AtomicReference<>();
    Thread first =
        stagedWriter(
            "durable-writer-A",
            target,
            "actor=A",
            staged,
            release,
            firstFailure);
    Thread second =
        stagedWriter(
            "durable-writer-B",
            target,
            "actor=B",
            staged,
            release,
            secondFailure);

    first.start();
    second.start();
    try {
      assertTrue(staged.await(5, TimeUnit.SECONDS));
      assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
    } finally {
      release.countDown();
      first.join(Duration.ofSeconds(5));
      second.join(Duration.ofSeconds(5));
    }

    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    List<Throwable> failures =
        java.util.stream.Stream.of(
                firstFailure.get(), secondFailure.get())
            .filter(java.util.Objects::nonNull)
            .toList();
    assertEquals(1, failures.size());
    assertTrue(failures.getFirst() instanceof FileAlreadyExistsException);
    assertTrue(
        Set.of("actor=A", "actor=B")
            .contains(Pack009ProcessSupport.readBounded(target)));
    assertNoStagedFiles();
  }

  private Thread stagedWriter(
      String name,
      Path target,
      String value,
      CountDownLatch staged,
      CountDownLatch release,
      AtomicReference<Throwable> failure) {
    return Thread.ofPlatform()
        .name(name)
        .daemon(true)
        .unstarted(
            () -> {
              try {
                Pack009ProcessSupport.writeDurableCreateNew(
                    target,
                    value,
                    () -> {
                      staged.countDown();
                      await(release);
                    });
              } catch (Throwable thrown) {
                failure.set(thrown);
              }
            });
  }

  private void assertNoStagedFiles() throws Exception {
    try (var files = Files.list(tempDir)) {
      assertEquals(
          List.of(),
          files
              .filter(
                  path ->
                      path.getFileName()
                          .toString()
                          .startsWith(".durable-"))
              .toList());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
