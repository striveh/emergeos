package io.emergeos.grapheval;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.core.application.GraphAttemptCoordinator;
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
import java.util.HexFormat;
import java.util.Set;

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
      Path appJar, Path testClasses, Class<?> testMain) {
    Path expectedJar = realRegularFile(appJar);
    Path expectedTests = realDirectory(testClasses);
    for (Class<?> production :
        new Class<?>[] {
          GraphEvalMain.class,
          Pack009GraphEvalCatalog.class,
          Pack009GraphVerifier.class,
          GraphAttemptCoordinator.class,
          PostgresGraphAttemptStore.class,
          AgentLoopKernel.class,
          OpenAiResponsesModel.class,
          ReviewedOpenAiClient.class,
          CanonicalIntegrity.class
        }) {
      if (!expectedJar.equals(codeSource(production))) {
        throw new IllegalStateException(
            "production class was not loaded from the shipping JAR");
      }
    }
    if (!expectedTests.equals(codeSource(testMain))
        || !expectedTests.equals(
            codeSource(Pack009ProcessSupport.class))) {
      throw new IllegalStateException(
          "test harness was not loaded from test-classes");
    }
  }

  static String readSecretFrame(
      DataInputStream input, String name) throws IOException {
    int length = input.readInt();
    if (length < 1 || length > 512) {
      throw new IllegalArgumentException(name + " length is invalid");
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new IllegalArgumentException(name + " frame is truncated");
    }
    String value = new String(bytes, StandardCharsets.UTF_8);
    if (value.isBlank()
        || value.indexOf('\0') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
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
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("durable value is required");
    }
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
    try (FileChannel channel =
        FileChannel.open(
            target,
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")))) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    if (!value.equals(readBounded(target))) {
      throw new IOException("durable read-back mismatch");
    }
    forceDirectory(parent);
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
