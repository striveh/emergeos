package io.emergeos.offlineharness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only process entry point. This class must never enter the shipping JAR.
 */
public final class OfflineComparisonCrashHarnessMain {

  private OfflineComparisonCrashHarnessMain() {}

  public static void main(String[] args) {
    try {
      if (args == null || args.length != 2) {
        rejected("ARGUMENTS_INVALID");
        return;
      }
      assertProductionLoadedFromPackagedJar();
      OfflineComparisonPersistenceObserver.Phase phase =
          OfflineComparisonPersistenceObserver.Phase.valueOf(
              args[0]);
      Path ready = absolutePath(args[1]);
      Path repository =
          Path.of(System.getProperty("user.dir"))
              .toAbsolutePath()
              .normalize();
      Path ownerHome =
          Path.of(System.getProperty("user.home"))
              .toAbsolutePath()
              .normalize();
      new OfflineComparisonWriteCommand()
          .execute(
              repository,
              ownerHome,
              new BlockingObserver(phase, ready));
      System.out.println(
          "CRASH_HARNESS_COMPLETED unexpectedly=true");
      System.out.flush();
    } catch (RuntimeException failure) {
      rejected("HARNESS_FAILED");
    }
  }

  private static void assertProductionLoadedFromPackagedJar() {
    try {
      Path expected =
          Path.of(
                  System.getProperty(
                      "emerge.offline.harness.jar"))
              .toAbsolutePath()
              .normalize();
      for (Class<?> productionType :
          new Class<?>[] {
            Pack004ComparisonMain.class,
            OfflineComparisonWriteCommand.class,
            OfflineComparisonReadCommand.class,
            PosixOfflineComparisonReportStore.class,
            OfflineComparisonReportJson.class,
            Pack004ComparisonRunner.class,
            Pack004ComparisonReportVerifier.class,
            Pack004Loader.class,
            LiteralReferenceCandidateGenerator.class,
            ComparisonIntegrityHashes.class,
            io.emergeos.core.application
                .AgentDraftReferenceGrounding.class,
            io.emergeos.contracts.DataClass.class
          }) {
        requireCodeSource(productionType, expected);
      }
      Path expectedTestClasses =
          Path.of(
                  System.getProperty(
                      "emerge.offline.harness.testClasses"))
              .toAbsolutePath()
              .normalize();
      Path harnessSource =
          codeSource(OfflineComparisonCrashHarnessMain.class);
      if (!expectedTestClasses.equals(harnessSource)
          || !Files.isDirectory(
              harnessSource, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException(
            "test harness code source mismatch");
      }
    } catch (URISyntaxException | RuntimeException failure) {
      throw new IllegalStateException(
          "production code source invalid", failure);
    }
  }

  private static void requireCodeSource(
      Class<?> type, Path expected) throws URISyntaxException {
    Path actual = codeSource(type);
    if (!expected.equals(actual)
        || !Files.isRegularFile(
            actual, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(
          "production code source mismatch");
    }
  }

  private static Path codeSource(Class<?> type)
      throws URISyntaxException {
    return Path.of(
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI())
        .toAbsolutePath()
        .normalize();
  }

  private static Path absolutePath(String value) {
    Path path = Path.of(value);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(
          "ready path must be absolute");
    }
    return path.normalize();
  }

  private static void rejected(String code) {
    System.out.println(
        "CRASH_HARNESS_REJECTED reason=" + code);
    System.out.flush();
    System.exit(3);
  }

  private static final class BlockingObserver
      implements OfflineComparisonPersistenceObserver {

    private final Phase target;
    private final Path ready;
    private final AtomicBoolean observed = new AtomicBoolean();

    private BlockingObserver(Phase target, Path ready) {
      this.target = target;
      this.ready = ready;
    }

    @Override
    public void observed(Phase phase) {
      if (phase != target) {
        return;
      }
      if (!observed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "phase observed more than once");
      }
      System.out.println(
          "CRASH_HARNESS_READY phase=" + phase);
      System.out.flush();
      writeReady(ready, phase);
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "crash harness interrupted", interrupted);
      }
    }

    private static void writeReady(Path ready, Phase phase) {
      try {
        Path parent = ready.getParent();
        if (parent == null
            || Files.isSymbolicLink(parent)
            || !Files.isDirectory(
                parent, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("ready parent unsafe");
        }
        byte[] bytes =
            (phase.name() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        try (FileChannel channel =
            FileChannel.open(
                ready,
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(
                        "rw-------")))) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }
          channel.force(true);
        }
        try (FileChannel channel =
            FileChannel.open(
                parent, StandardOpenOption.READ)) {
          channel.force(true);
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }
}
