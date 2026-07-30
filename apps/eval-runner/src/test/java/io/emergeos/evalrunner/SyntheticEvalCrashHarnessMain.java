package io.emergeos.evalrunner;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only child-process entry point loaded beside the packaged runner.
 *
 * <p>This class lives only in {@code target/test-classes}. The formal
 * executable JAR must never contain it.
 */
public final class SyntheticEvalCrashHarnessMain {

  private static final String SYNTHETIC_KEY =
      "sentinel-crash-harness-key";

  private SyntheticEvalCrashHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (SyntheticEvalExecutor.Rejected rejected) {
      System.out.println(
          "CRASH_HARNESS_REJECTED reason=" + rejected.code());
      System.out.flush();
      exitCode = 2;
    } catch (RuntimeException failure) {
      System.out.println(
          "CRASH_HARNESS_REJECTED reason=HARNESS_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) {
    if (args == null || args.length == 0) {
      return argumentsInvalid();
    }
    return switch (args[0]) {
      case "execute" -> execute(args);
      case "verify" -> verify(args);
      default -> argumentsInvalid();
    };
  }

  private static int execute(String[] args) {
    if (args.length != 6) {
      return argumentsInvalid();
    }
    Path repoRoot = absolutePath(args[1]);
    Path ownerHome = absolutePath(args[2]);
    String loopbackBaseUrl = requireLoopbackBaseUrl(args[3]);
    EvalExecutionObserver observer =
        "NONE".equals(args[4])
            ? EvalExecutionObserver.noop()
            : new BlockingCrashObserver(
                EvalExecutionObserver.Phase.valueOf(args[4]),
                absolutePath(args[5]));
    SyntheticEvalExecutor.ExecutionResult result =
        new SyntheticEvalExecutor(repoRoot)
            .execute(
                new SyntheticEvalExecutor.Dependencies(
                    exactConsole(),
                    ownerHome,
                    () -> SYNTHETIC_KEY,
                    ignored -> loopbackClient(loopbackBaseUrl),
                    Clock.fixed(
                        Instant.parse("2026-07-30T10:00:00Z"),
                        ZoneOffset.UTC),
                    System::nanoTime,
                    observer));
    System.out.println(
        "CRASH_HARNESS_COMPLETED status="
            + result.outcome().result().status());
    System.out.flush();
    return 0;
  }

  private static int verify(String[] args) {
    if (args.length != 2) {
      return argumentsInvalid();
    }
    Path ownerHome = absolutePath(args[1]);
    Path marker =
        ownerHome
            .resolve(".emergeos/eval-attempts")
            .resolve(
                SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".attempt");
    PosixAttemptJournalVerifier.Verification verification =
        new PosixAttemptJournalVerifier().verify(marker);
    PosixAttemptJournalVerifier.BillingEvidence billing =
        verification.billing();
    System.out.println(
        "VERIFY verdict="
            + verification.verdict()
            + " code="
            + verification.code()
            + " billingStatus="
            + billing.status()
            + " trustedPrefix="
            + billing.trustedPrefix()
            + " observedCostUsd="
            + billing.observedCostUsd().toPlainString()
            + " observedTokenCount="
            + billing.observedTokenCount()
            + " providerSdkCreateInvocations="
            + billing.providerSdkCreateInvocations()
            + " providerAttributedInvocations="
            + billing.providerAttributedInvocations()
            + " recordState="
            + verification.recordState()
            + " terminalPresent="
            + (verification.terminal() != null));
    System.out.flush();
    return verification.verdict()
            == PosixAttemptJournalVerifier.Verdict.INVALID
        ? 3
        : 0;
  }

  private static OpenAIClient loopbackClient(String baseUrl) {
    return OpenAIOkHttpClient.builder()
        .apiKey(SYNTHETIC_KEY)
        .baseUrl(baseUrl)
        .proxy(Proxy.NO_PROXY)
        .maxRetries(0)
        .timeout(Duration.ofSeconds(2))
        .logLevel(LogLevel.OFF)
        .build();
  }

  private static OneShotOperatorGate.InteractiveConsole
      exactConsole() {
    return new OneShotOperatorGate.InteractiveConsole() {
      @Override
      public boolean available() {
        return true;
      }

      @Override
      public String readLine(String prompt) {
        return OneShotOperatorGate.CHALLENGE_PREFIX
            + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID;
      }
    };
  }

  private static Path absolutePath(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    Path path = Path.of(value);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("path must be absolute");
    }
    return path.normalize();
  }

  private static String requireLoopbackBaseUrl(String value) {
    URI uri = URI.create(value);
    if (!"http".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/v1".equals(uri.getRawPath())
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "only an exact IPv4 loopback base URL is allowed");
    }
    return uri.toASCIIString();
  }

  private static int argumentsInvalid() {
    System.out.println(
        "CRASH_HARNESS_REJECTED reason=ARGUMENTS_INVALID");
    System.out.flush();
    return 2;
  }

  private static final class BlockingCrashObserver
      implements EvalExecutionObserver {

    private final Phase target;
    private final Path ready;
    private final AtomicBoolean observed = new AtomicBoolean();

    private BlockingCrashObserver(Phase target, Path ready) {
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
            "crash phase observed more than once");
      }
      System.out.println("CRASH_HARNESS_READY phase=" + phase);
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
            || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("ready parent is unsafe");
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
                    PosixFilePermissions.fromString("rw-------")))) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) {
            channel.write(buffer);
          }
          channel.force(true);
        }
        try (FileChannel channel =
            FileChannel.open(parent, StandardOpenOption.READ)) {
          channel.force(true);
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }
}
