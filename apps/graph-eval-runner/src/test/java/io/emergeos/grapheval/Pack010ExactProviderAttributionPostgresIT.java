package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.openai.core.LogLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.port.CancellationSignal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010ExactProviderAttributionPostgresIT {

  private static final String ACTUAL_FINAL_CONTENT =
      "Prompt 不是咒语，而是在构造可验证的运行时状态。";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_exact_provider_attribution")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString())
          .withCreateContainerCmdModifier(
              command -> {
                command.getHostConfig().withAutoRemove(false);
                command.withEntrypoint("sh", "-c");
                command.withCmd(
                    "/usr/local/bin/docker-entrypoint.sh postgres & "
                        + "while :; do sleep 1; done");
              });

  @Test
  void twoExactLoopbackAttributionsSurvivePostgresRestart()
      throws Exception {
    DataSource admin = dataSource();
    Flyway.configure().dataSource(admin).load().migrate();
    Pack010GraphTerminalFixture.ensureCapture(admin);
    execute(
        admin,
        resource("/db/provisioning/pack010_runtime_roles.sql"));
    String executorPassword = UUID.randomUUID().toString();
    String writerPassword = UUID.randomUUID().toString();
    JdbcClient adminJdbc = JdbcClient.create(admin);
    adminJdbc
        .sql(
            "ALTER ROLE emergeos_graph_executor PASSWORD '"
                + executorPassword
                + "'")
        .update();
    adminJdbc
        .sql(
            "ALTER ROLE emergeos_graph_prefix_writer PASSWORD '"
                + writerPassword
                + "'")
        .update();
    DataSource prefix =
        dataSource("emergeos_graph_prefix_writer", writerPassword);
    DataSource terminal =
        dataSource("emergeos_graph_executor", executorPassword);
    PostgresGraphAttemptStore store =
        Pack010GraphTerminalStoreBridge.openWriter(prefix);
    var manifest = Pack010GraphEvalCatalog.manifest(1);
    List<io.emergeos.core.domain.GraphAttemptManifest> catalog =
        List.of(
            Pack010GraphEvalCatalog.manifest(1),
            Pack010GraphEvalCatalog.manifest(2),
            Pack010GraphEvalCatalog.manifest(3));
    GraphAttemptSnapshot sequence7 =
        Pack010GraphTerminalFixture.prepareCatalogEgressPrefixOnly(
            admin, store, 1);
    Instant expiresAt =
        adminJdbc
            .sql(
                "SELECT pg_catalog.clock_timestamp() "
                    + "+ interval '5 minutes'")
            .query(Instant.class)
            .single();
    var sessionIntent =
        Pack010GraphTerminalStoreBridge.claimProviderSessionIntent(
            prefix,
            manifest,
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            Pack010GraphTerminalFixture.catalogIntent(1, 1),
            expiresAt);
    assertEquals(7, sessionIntent.cursorSequence());
    assertEquals(sequence7.cursor().headHash(),
        sessionIntent.cursorHeadHash());
    var providerBinding =
        Pack010GraphTerminalStoreBridge.syntheticTerminalBinding(
            prefix,
            catalog,
            manifest,
            sequence7.cursor(),
            Pack010GraphEvalCatalog.parentRun(1),
            Pack010GraphEvalCatalog.childRun(1),
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            "CONSUMED",
            expiresAt);
    GraphAttemptCoordinator coordinator = providerBinding.coordinator();
    GraphAttemptCoordinator.EgressAuthority egress =
        providerBinding.egress();
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(7));
    coordinator.clientCreated(
        egress, manifest.startedAt().plusMillis(8));
    coordinator.modelCreated(
        egress, manifest.startedAt().plusMillis(9));
    var lease =
        Pack010ProviderSessionEffectOrderingTest.syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            coordinator,
            egress,
            expiresAt);
    Clock clock =
        Clock.fixed(
            manifest.startedAt().plusMillis(10), ZoneOffset.UTC);

    Pack010ProviderSessionComposer.Pack010AttributedModelOutcome
        finalOutcome;
    try (LoopbackServer server = new LoopbackServer()) {
      ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "synthetic-loopback-only-not-a-real-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF);
      try (Pack010ProviderSessionComposer.ProviderSession session =
          Pack010TerminalOutcomeTestBridge.actualSession(
              client,
              lease,
              coordinator,
              egress,
              clock,
              manifest,
              OwnerTtyGraphAuthority.Pack010Revision.R1)) {
        AgentModel.ModelCallContext context =
            new AgentModel.ModelCallContext(
                Pack010GraphEvalCatalog
                    .workerProfile(1)
                    .deadlineMs(),
                Pack010GraphEvalCatalog.workerProfile(1).budgetUsd(),
                CancellationSignal.never());

        var first = session.next(
              new AgentModel.Turn(
                  Pack010GraphEvalCatalog.childTask(1), List.of()),
              context);
        finalOutcome = session.next(
              new AgentModel.Turn(
                  Pack010GraphEvalCatalog.childTask(1),
                  List.of(
                      new AgentModel.ToolResult(
                          "capture.read",
                          "capture://capture-openai-worker-010",
                          "synthetic PUBLIC capture"))),
              context);

        assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        assertInstanceOf(
            AgentModel.FinalDraft.class, finalOutcome.decision());
        assertEquals(2, server.requestCount());
      }
    }

    GraphAttemptSnapshot sequence14 =
        Pack010GraphTerminalFixture.verified(store, manifest);
    assertEquals(14, sequence14.cursor().lastSequence());
    assertEquals(2, sequence14.providerAttributions().size());
    assertEquals(
        IntegrityHashes.utf8ContentHash(
            Pack010LoopbackEffectOrderingSentinelTest.RESPONSE),
        sequence14.providerAttributions().getFirst().responseHash());
    assertEquals(
        IntegrityHashes.utf8ContentHash(
            LoopbackServer.secondResponse()),
        sequence14.providerAttributions().get(1).responseHash());

    var childTruth =
        Pack010GraphTerminalFixture.successfulChild(
            sequence14,
            manifest,
            Pack010GraphEvalCatalog.workerProfile(1),
            manifest.startedAt());
    assertEquals(
        14,
        Pack010GraphTerminalFixture.verified(store, manifest)
            .cursor()
            .lastSequence());
    var candidate = childTruth.candidate();
    assertEquals(ACTUAL_FINAL_CONTENT, candidate.content());
    var reviewed =
        Pack010ProviderSessionComposer.reviewStructuredFinal(
            finalOutcome,
            providerBinding.coordinator(),
            providerBinding.egress(),
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            manifest,
            candidate);
    assertEquals(
        sequence14.providerAttributions().stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList(),
        reviewed.attributions().stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList());
    Pack010PostgresRuntimeComposition runtime =
        Pack010PostgresRuntimeComposition.open(
            prefix,
            terminal,
            providerBinding.ownerAuthority(),
            providerBinding.capability(),
            providerBinding.coordinator(),
            providerBinding.egress(),
            OwnerTtyGraphAuthority.Pack010Revision.R1);
    var command =
        runtime.prepareChild(
            finalOutcome,
            candidate,
            childTruth.terminal(),
            childTruth.workerResult());
    runtime.completeChild(command);
    GraphAttemptSnapshot sequence15 =
        Pack010GraphTerminalFixture.verified(store, manifest);
    assertEquals(15, sequence15.cursor().lastSequence());

    restartPostgres(prefix);
    GraphAttemptSnapshot reconciled =
        Pack010GraphTerminalFixture.verified(
            Pack010GraphTerminalStoreBridge.openWriter(prefix),
            manifest);
    assertEquals(sequence15, reconciled);
    System.out.println(
        "PACK010_EXACT_ATTRIBUTION_RECEIPT"
            + " transport=LOOPBACK_PUBLIC_SYNTHETIC"
            + " requests=2 sequence=15"
            + " actualOpaqueOutcomeTxB=SUCCEEDED"
            + " durableSessionIntent=true"
            + " postgresRestart=RECONCILED"
            + " providerExactlyOnceClaim=false"
            + " realProvider=false billing=0"
            + " shippingExecute=DISABLED");
  }

  private static DataSource dataSource() {
    return dataSource(
        POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DataSource dataSource(
      String user, String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(POSTGRES.getJdbcUrl());
    source.setUser(user);
    source.setPassword(password);
    return source;
  }

  private static void execute(DataSource source, String sql)
      throws SQLException {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute(sql);
      connection.commit();
    }
  }

  private static String resource(String path) throws Exception {
    try (var input =
        Pack010ExactProviderAttributionPostgresIT.class
            .getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("resource missing");
      }
      return new String(
          input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void restartPostgres(DataSource probe)
      throws Exception {
    var restart =
        POSTGRES.execInContainer(
            "sh",
            "-c",
            "gosu postgres pg_ctl restart -D \"$PGDATA\" -m immediate -w");
    assertEquals(0, restart.getExitCode(), restart.getStderr());
    long deadline =
        System.nanoTime() + Duration.ofSeconds(30).toNanos();
    SQLException last = null;
    while (System.nanoTime() < deadline) {
      try (Connection ignored = probe.getConnection()) {
        return;
      } catch (SQLException unavailable) {
        last = unavailable;
        Thread.sleep(50);
      }
    }
    throw last == null ? new IllegalStateException() : last;
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
      int ordinal = requests.getAndIncrement();
      exchange.getRequestBody().readAllBytes();
      String response =
          ordinal == 0
              ? Pack010LoopbackEffectOrderingSentinelTest.RESPONSE
              : secondResponse();
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var body = exchange.getResponseBody()) {
        body.write(bytes);
      }
    }

    private static String secondResponse() {
      return Pack010LoopbackEffectOrderingSentinelTest.SECOND_RESPONSE
          .replace("synthetic PUBLIC draft", ACTUAL_FINAL_CONTENT);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
