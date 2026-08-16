package io.emergeos.grapheval;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.PostgresArtifactLineageStore;
import io.emergeos.adapters.postgres.PostgresAttributedFailureResumeStore;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.adapters.postgres.PostgresExactPicoOverlayReader;
import io.emergeos.adapters.postgres.PostgresExactPicoProviderValidationAttestor;
import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.PostgresProviderValidationAttestor;
import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphExactPicoOverlayAttribution;
import io.emergeos.core.domain.GraphExactPicoOverlayRequirement;
import io.emergeos.core.domain.GraphExactPicoOverlaySnapshot;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;
import io.emergeos.core.domain.GraphExactPicoProviderSignatureVerifier;
import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationChallenge;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import io.emergeos.core.domain.GraphProviderValidationTranscript;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.GraphTerminalSeal;
import io.emergeos.core.port.GraphExactPicoOverlayReader;
import io.emergeos.core.port.GraphExactPicoProviderValidationAttestor;
import io.emergeos.core.port.GraphExactPicoProviderValidationSigner;
import io.emergeos.core.port.GraphProviderValidationAttestor;
import io.emergeos.core.port.GraphProviderValidationSigner;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class Pack009ProcessSupport {

  static final String PROVIDER_READY = "provider-ready.txt";
  static final String PROVIDER_ACCEPTED = "provider-accepted.txt";
  static final String PROVIDER_RELEASE = "provider-release";
  static final String PROVIDER_RESPONSE = "provider-response.txt";
  static final String PROVIDER_SHUTDOWN = "provider-shutdown";
  static final String SYNTHETIC_PROVIDER_KEY =
      "sentinel-pack009-loopback-key";

  private static final int MAX_FILE_BYTES = 16 * 1024;

  private Pack009ProcessSupport() {}

  static void assertCodeSources(
      Path appJar, Path testClasses, Class<?>... testTypes) {
    Path expectedJar = realRegularFile(appJar);
    Path expectedTests = realDirectory(testClasses);
    for (Class<?> production :
        new Class<?>[] {
          GraphEvalMain.class,
          Pack009GraphEvalCatalog.class,
          Pack009GraphVerifier.class,
          Pack010GraphEvalCatalog.class,
          Pack010GraphTerminalVerifier.class,
          GraphAttemptCoordinator.class,
          PostgresGraphAttemptAccess.class,
          PostgresGraphAttemptStore.class,
          PostgresAttributedFailureResumeStore.class,
          PostgresExactPicoOverlayReader.class,
          PostgresExactPicoProviderValidationAttestor.class,
          PostgresProviderValidationAttestor.class,
          PostgresArtifactLineageStore.class,
          PostgresCaptureStore.class,
          AgentLoopKernel.class,
          OpenAiResponsesModel.class,
          ReviewedOpenAiClient.class,
          CanonicalIntegrity.class,
          HarnessCandidateEnvelope.class,
          WorkerResultEnvelope.class,
          GraphAttemptSnapshot.class,
          GraphExactPicoOverlayAttribution.class,
          GraphExactPicoOverlayRequirement.class,
          GraphExactPicoOverlaySnapshot.class,
          GraphExactPicoOverlayVerification.class,
          GraphExactPicoProviderSignatureVerifier.class,
          GraphExactPicoProviderValidationChallenge.class,
          GraphExactPicoProviderValidationCommand.class,
          GraphExactPicoProviderValidationReceipt.class,
          GraphProviderAttribution.class,
          GraphProviderValidationAttestation.class,
          GraphProviderValidationChallenge.class,
          GraphProviderValidationStatement.class,
          GraphProviderValidationTranscript.class,
          GraphTerminalBinding.class,
          GraphTerminalSeal.class,
          GraphExactPicoOverlayReader.class,
          GraphExactPicoProviderValidationAttestor.class,
          GraphExactPicoProviderValidationSigner.class,
          GraphProviderValidationAttestor.class,
          GraphProviderValidationSigner.class
        }) {
      if (!expectedJar.equals(codeSource(production))) {
        throw new IllegalStateException(
            "production class was not loaded from the shipping JAR");
      }
    }
    if (!expectedTests.equals(
        codeSource(Pack009ProcessSupport.class))) {
      throw new IllegalStateException(
          "test support was not loaded from test-classes");
    }
    for (Class<?> testType : testTypes) {
      if (!expectedTests.equals(codeSource(testType))) {
        throw new IllegalStateException(
            "test harness was not loaded from test-classes");
      }
    }
  }

  static String readSecretFrame(
      DataInputStream input, String name) throws IOException {
    byte[] bytes = readSecretBytesFrame(input, name);
    try {
      String value = new String(bytes, StandardCharsets.UTF_8);
      if (value.isBlank()
          || value.indexOf('\0') >= 0
          || value.indexOf('\n') >= 0
          || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException(name + " is invalid");
      }
      return value;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  static byte[] readSecretBytesFrame(
      DataInputStream input, String name) throws IOException {
    int length = input.readInt();
    if (length < 1 || length > 512) {
      throw new IllegalArgumentException(name + " length is invalid");
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      Arrays.fill(bytes, (byte) 0);
      throw new IllegalArgumentException(name + " frame is truncated");
    }
    return bytes;
  }

  static void requireEndOfInput(
      DataInputStream input, String name) throws IOException {
    if (input.read() != -1) {
      throw new IllegalArgumentException(
          name + " contains an unexpected extra frame");
    }
  }

  static Path absoluteDirectory(String value) {
    return realDirectory(absolutePath(value));
  }

  static Path absoluteRegularFile(String value) {
    return realRegularFile(absolutePath(value));
  }

  static String requireLoopbackPostgres(String value) {
    if (value == null || !value.startsWith("jdbc:")) {
      throw new IllegalArgumentException(
          "only a credential-free loopback PostgreSQL URL is allowed");
    }
    URI uri = URI.create(value.substring("jdbc:".length()));
    String query = uri.getRawQuery();
    if (!"postgresql".equals(uri.getScheme())
        || !("127.0.0.1".equals(uri.getHost())
            || "localhost".equals(uri.getHost()))
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/emerge_graph_eval".equals(uri.getRawPath())
        || uri.getRawUserInfo() != null
        || (query != null && !"loggerLevel=OFF".equals(query))
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "only a credential-free loopback PostgreSQL URL is allowed");
    }
    return value;
  }

  static String requireLoopbackProvider(String value) {
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
          "only an exact loopback provider URL is allowed");
    }
    return value;
  }

  static String requireBounded(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 256
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  static void writeDurableCreateNew(Path target, String value)
      throws IOException {
    writeDurableCreateNew(target, value, () -> {});
  }

  static void writeDurableCreateNew(
      Path target, String value, Runnable beforePublish)
      throws IOException {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("durable value is required");
    }
    Objects.requireNonNull(beforePublish, "beforePublish");
    Path parent = target.getParent();
    if (parent == null
        || !Files.isDirectory(
            parent, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(parent)) {
      throw new IllegalArgumentException(
          "durable parent is unsafe");
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FILE_BYTES) {
      throw new IllegalArgumentException(
          "durable value is too large");
    }
    Path staged =
        parent.resolve(
            ".durable-" + UUID.randomUUID() + ".tmp");
    boolean stagedExists = false;
    try {
      try (FileChannel channel =
          FileChannel.open(
              staged,
              Set.of(
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE),
              PosixFilePermissions.asFileAttribute(
                  PosixFilePermissions.fromString("rw-------")))) {
        stagedExists = true;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      if (!value.equals(readBounded(staged))) {
        throw new IOException("staged durable read-back mismatch");
      }
      beforePublish.run();

      Files.createLink(target, staged);
      forceDirectory(parent);
      if (!value.equals(readBounded(target))) {
        throw new IOException("durable read-back mismatch");
      }
      Files.delete(staged);
      stagedExists = false;
      forceDirectory(parent);
    } catch (IOException | RuntimeException | Error failure) {
      if (stagedExists) {
        try {
          Files.deleteIfExists(staged);
          forceDirectory(parent);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  static String readBounded(Path source) throws IOException {
    if (Files.isSymbolicLink(source)
        || !Files.isRegularFile(
            source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("bounded source is not a regular file");
    }
    long size = Files.size(source);
    if (size < 1 || size > MAX_FILE_BYTES) {
      throw new IOException("bounded source size is invalid");
    }
    byte[] bytes = Files.readAllBytes(source);
    if (bytes.length != size) {
      throw new IOException("bounded source changed during read");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  static void awaitFile(Path file, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline
        && !Files.isRegularFile(
            file, LinkOption.NOFOLLOW_LINKS)) {
      Thread.sleep(20);
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(
          "timed out waiting for process evidence");
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is unavailable", impossible);
    }
  }

  private static void forceDirectory(Path directory)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
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

  private static Path realDirectory(Path value) {
    try {
      if (Files.isSymbolicLink(value)
          || !Files.isDirectory(
              value, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "directory is required");
      }
      return value.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException failure) {
      throw new IllegalArgumentException(
          "directory is invalid", failure);
    }
  }

  private static Path realRegularFile(Path value) {
    try {
      if (Files.isSymbolicLink(value)
          || !Files.isRegularFile(
              value, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            "regular file is required");
      }
      return value.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException failure) {
      throw new IllegalArgumentException(
          "regular file is invalid", failure);
    }
  }

  private static Path codeSource(Class<?> type) {
    try {
      if (type.getProtectionDomain() == null
          || type.getProtectionDomain().getCodeSource() == null
          || type
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
              == null) {
        throw new IllegalStateException("class CodeSource is absent");
      }
      return Path.of(
              type.getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI())
          .toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "class CodeSource is invalid", failure);
    }
  }
}
