package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.contracts.IntegrityHashes;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010ExactAttributionProcessIT {

  private static final String HARNESS_MAIN =
      "io.emergeos.grapheval.Pack010ExactAttributionCrashHarnessMain";
  private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(30);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_graph_eval")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString());

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
  }

  @Test
  void hardKillBeforeAndAfterAttributionCommitRecoverInFreshJvm()
      throws Exception {
    runKillCase("kill-before-attribution", 1, 71, 11, 0, 1);
    runKillCase("kill-after-commit", 2, 72, 12, 1, 1);
    runKillCase("kill-after-outcome", 3, 73, 14, 2, 2);
    System.out.println(
        "PACK010_EXACT_ATTRIBUTION_PROCESS_RECEIPT"
            + " killPoints=BEFORE_ATTRIBUTION,AFTER_COMMIT,AFTER_OUTCOME"
            + " recovery=FRESH_PACKAGED_JVM"
            + " outcomeRecovery=FAIL_CLOSED"
            + " providerReplay=0 providerExactlyOnceClaim=false"
            + " transport=LOOPBACK_PUBLIC_SYNTHETIC"
            + " realProvider=false billing=0");
  }

  private static void runKillCase(
      String mode,
      int repetition,
      int expectedExit,
      int expectedSequence,
      int expectedAttributions,
      int expectedRequests) throws Exception {
    try (LoopbackServer server = new LoopbackServer()) {
      Process killed =
          start(
              command(
                  mode,
                  repetition,
                  server.baseUrl()));
      String killedOutput;
      try {
        writeOnlySecret(killed, POSTGRES.getPassword());
        assertTrue(
            killed.waitFor(
                EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        killedOutput =
            new String(
                killed.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(expectedExit, killed.exitValue(), killedOutput);
      } finally {
        if (killed.isAlive()) {
          killed.destroyForcibly();
          killed.waitFor();
        }
      }
      assertEquals(expectedRequests, server.requestCount());
      assertFalse(killedOutput.contains(POSTGRES.getPassword()));

      Process replay =
          start(command("replay", repetition, server.baseUrl()));
      String replayOutput;
      try {
        writeOnlySecret(replay, POSTGRES.getPassword());
        assertTrue(
            replay.waitFor(
                EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        replayOutput =
            new String(
                replay.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, replay.exitValue(), replayOutput);
      } finally {
        if (replay.isAlive()) {
          replay.destroyForcibly();
          replay.waitFor();
        }
      }
      assertTrue(
          replayOutput.contains(
              "reason=EXECUTION_SLOT_ALREADY_CLAIMED"));
      assertTrue(replayOutput.contains("clientFactories=0"));
      assertTrue(replayOutput.contains("httpRequests=0"));
      assertFalse(replayOutput.contains(POSTGRES.getPassword()));
      assertEquals(expectedRequests, server.requestCount());
    }

    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    var snapshot =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(dataSource()),
            manifest);
    assertEquals(expectedSequence, snapshot.cursor().lastSequence());
    assertEquals(
        expectedAttributions,
        snapshot.providerAttributions().size());
    if (expectedAttributions == 1) {
      assertEquals(
          IntegrityHashes.utf8ContentHash(
              Pack010LoopbackEffectOrderingSentinelTest.RESPONSE),
          snapshot
              .providerAttributions()
              .getFirst()
              .responseHash());
    } else if (expectedAttributions == 2) {
      assertEquals(
          IntegrityHashes.utf8ContentHash(
              Pack010LoopbackEffectOrderingSentinelTest.SECOND_RESPONSE),
          snapshot
              .providerAttributions()
              .get(1)
              .responseHash());
    }

    Process verifier =
        start(command("verify", repetition, "NONE"));
    String verifierOutput;
    try {
      writeOnlySecret(verifier, POSTGRES.getPassword());
      assertTrue(
          verifier.waitFor(
              EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      verifierOutput =
          new String(
              verifier.getInputStream().readAllBytes(),
              StandardCharsets.UTF_8);
      assertEquals(0, verifier.exitValue(), verifierOutput);
    } finally {
      if (verifier.isAlive()) {
        verifier.destroyForcibly();
        verifier.waitFor();
      }
    }
    assertTrue(
        verifierOutput.contains(
            "sequence=" + expectedSequence));
    assertTrue(
        verifierOutput.contains(
            "attributions=" + expectedAttributions));
    assertFalse(verifierOutput.contains(POSTGRES.getPassword()));
  }

  private static Process start(List<String> command)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(repo().toFile());
    builder.redirectErrorStream(true);
    builder.environment().clear();
    return builder.start();
  }

  private static List<String> command(
      String mode, int repetition, String providerBaseUrl) {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        "java")
                    .toString(),
                "-Djava.net.useSystemProxies=false",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dfile.encoding=UTF-8",
                "-cp",
                shippingJar()
                    + File.pathSeparator
                    + testClasses(),
                HARNESS_MAIN));
    command.addAll(
        List.of(
            mode,
            Integer.toString(repetition),
            repo().toString(),
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            providerBaseUrl,
            shippingJar().toString(),
            testClasses().toString()));
    return command;
  }

  private static void writeOnlySecret(
      Process process, String secret) throws IOException {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream output =
        new DataOutputStream(process.getOutputStream())) {
      output.writeInt(bytes.length);
      output.write(bytes);
    }
  }

  private static DataSource dataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static Path shippingJar() {
    return Path.of(
            System.getProperty("emerge.graph.it.jar"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path repo() {
    return Path.of(
            System.getProperty("emerge.graph.it.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return Path.of(
            System.getProperty("emerge.graph.it.testClasses"))
        .toAbsolutePath()
        .normalize();
  }

  private static final class LoopbackServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    private LoopbackServer() throws IOException {
      server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
              0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext("/v1/responses", this::respond);
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/v1";
    }

    private int requestCount() {
      return requests.get();
    }

    private void respond(HttpExchange exchange) throws IOException {
      int requestOrdinal = requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes =
          (requestOrdinal == 1
                  ? Pack010LoopbackEffectOrderingSentinelTest.RESPONSE
                  : Pack010LoopbackEffectOrderingSentinelTest
                      .SECOND_RESPONSE)
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var body = exchange.getResponseBody()) {
        body.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
