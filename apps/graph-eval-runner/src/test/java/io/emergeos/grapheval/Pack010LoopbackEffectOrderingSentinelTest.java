package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.LogLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentModelFailure;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.port.CancellationSignal;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Pack010LoopbackEffectOrderingSentinelTest {

  private static final String SYNTHETIC_CREDENTIAL =
      "synthetic-loopback-only-not-a-real-key";

  @Test
  void durableIntentFailurePreventsTheLoopbackHttpRequest()
      throws Exception {
    Fixture fixture = fixture(true, false);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer();
        ReviewedOpenAiClient client = client(server)) {
      AgentModel.Session session =
          model(fixture, client)
              .open(Pack010GraphEvalCatalog.childTask(1));

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> session.next(turn(), context()));

      assertEquals(
          "SYNTHETIC_PROVIDER_INTENT_PERSISTENCE_FAILURE",
          failure.getMessage());
      assertEquals(0, server.requestCount());
      assertEquals(0, fixture.store().providerIntents);
      assertEquals(
          GraphAttemptEventType.MODEL_CREATED,
          fixture.store().events.getLast());
    }
  }

  @Test
  void durableAttributionFailureStopsAfterOneUnknownResponse()
      throws Exception {
    Fixture fixture = fixture(false, true);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer();
        ReviewedOpenAiClient client = client(server)) {
      AgentModel.Session session =
          model(fixture, client)
              .open(Pack010GraphEvalCatalog.childTask(1));

      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> session.next(turn(), context()));

      assertEquals(
          "SYNTHETIC_PROVIDER_ATTRIBUTION_PERSISTENCE_FAILURE",
          failure.getMessage());
      assertEquals(1, server.requestCount());
      assertEquals(1, fixture.store().providerIntents);
      assertEquals(0, fixture.store().providerAttributions);
      assertEquals(
          GraphAttemptEventType.PROVIDER_INTENT,
          fixture.store().events.getLast());

      AgentModelFailure replay =
          assertThrows(
              AgentModelFailure.class,
              () -> session.next(turn(), context()));
      assertEquals(
          AgentModelFailure.Code.EGRESS_NOT_ALLOWED,
          replay.code());
      assertEquals(1, server.requestCount());
      assertEquals(1, fixture.store().providerIntents);
      assertEquals(0, fixture.store().providerAttributions);
    }
  }

  @Test
  void exactLoopbackResponseIsDurableBeforeModelDecisionReturns()
      throws Exception {
    Fixture fixture = fixture(false, false);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(RESPONSE, SECOND_RESPONSE);
        ReviewedOpenAiClient client = client(server)) {
      AgentModel.Session session =
          model(fixture, client)
              .open(Pack010GraphEvalCatalog.childTask(1));

      AgentModel.ModelStep first = session.next(turn(), context());
      AgentModel.ModelStep second =
          session.next(
              new AgentModel.Turn(
                  Pack010GraphEvalCatalog.childTask(1),
                  List.of(
                      new AgentModel.ToolResult(
                          "capture.read",
                          "capture://capture-openai-worker-010",
                          "synthetic PUBLIC capture"))),
              context());

      assertEquals(AgentModel.ToolCall.class, first.decision().getClass());
      assertEquals(AgentModel.FinalDraft.class, second.decision().getClass());
      assertEquals(2, server.requestCount());
      assertEquals(2, fixture.store().providerIntents);
      assertEquals(2, fixture.store().providerAttributions);
      assertEquals(
          GraphAttemptEventType.PROVIDER_ATTRIBUTED,
          fixture.store().events.getLast());
      var firstAttribution = fixture.store().attributions.getFirst();
      assertEquals(
          IntegrityHashes.utf8ContentHash(RESPONSE),
          firstAttribution.responseHash());
      assertEquals(1, firstAttribution.requestOrdinal());
      assertEquals(100, firstAttribution.inputTokens());
      assertEquals(0, firstAttribution.cachedInputTokens());
      assertEquals(10, firstAttribution.outputTokens());
      assertEquals(5, firstAttribution.reasoningOutputTokens());
      assertEquals(110, firstAttribution.totalTokens());
      var secondAttribution = fixture.store().attributions.get(1);
      assertEquals(
          IntegrityHashes.utf8ContentHash(SECOND_RESPONSE),
          secondAttribution.responseHash());
      assertEquals(2, secondAttribution.requestOrdinal());
      assertEquals(120, secondAttribution.inputTokens());
      assertEquals(20, secondAttribution.cachedInputTokens());
      assertEquals(30, secondAttribution.outputTokens());
      assertEquals(10, secondAttribution.reasoningOutputTokens());
      assertEquals(150, secondAttribution.totalTokens());
    }
  }

  @Test
  void actualStructuredFinalMintsOneExactOpaqueOutcome() throws Exception {
    Fixture fixture = fixture(false, false);
    Clock clock =
        Clock.fixed(
            fixture.manifest().startedAt().plusMillis(9),
            ZoneOffset.UTC);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(RESPONSE, SECOND_RESPONSE);
        ReviewedOpenAiClient client = client(server);
        Pack010ProviderSessionComposer.ProviderSession session =
            providerSessionWithOutcomeLedger(
                fixture, client, clock)) {
      var first = session.next(turn(), context());
      var second = session.next(toolResultTurn(), context());

      assertInstanceOf(
          AgentModel.ToolCall.class, first.decision());
      assertInstanceOf(
          AgentModel.FinalDraft.class, second.decision());
      assertEquals(2, server.requestCount());
      assertEquals(2, fixture.store().providerAttributions);

      HarnessCandidateEnvelope exact =
          candidate(
              fixture.manifest(),
              SECOND_RESPONSE,
              "synthetic PUBLIC draft");
      HarnessCandidateEnvelope forged =
          candidate(
              fixture.manifest(),
              SECOND_RESPONSE,
              "synthetic PUBLIC forged draft");
      assertThrows(
          RuntimeException.class,
          () ->
              Pack010ProviderSessionComposer.reviewStructuredFinal(
                  second,
                  fixture.coordinator(),
                  fixture.egress(),
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  fixture.manifest(),
                  forged));
      assertThrows(
          RuntimeException.class,
          () ->
              Pack010ProviderSessionComposer.claimStructuredFinal(
                  first,
                  fixture.coordinator(),
                  fixture.egress(),
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  fixture.manifest(),
                  exact));

      try (var executor = Executors.newFixedThreadPool(2)) {
        var left = executor.submit(() -> claim(second, fixture, exact));
        var right = executor.submit(() -> claim(second, fixture, exact));
        assertEquals(
            1,
            (left.get() ? 1 : 0) + (right.get() ? 1 : 0));
      }
      assertThrows(
          RuntimeException.class,
          () ->
              Pack010ProviderSessionComposer.claimStructuredFinal(
                  second,
                  fixture.coordinator(),
                  fixture.egress(),
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  fixture.manifest(),
                  exact));
    }
  }

  @Test
  void wrongModelOverLimitAndMalformedFinalNeverMintSuccessAuthority()
      throws Exception {
    assertFailedFinalCannotBeClaimed(
        SECOND_RESPONSE.replace(
            "\"model\":\"gpt-5.6-terra\"",
            "\"model\":\"gpt-5.6-terra-drift\""));
    assertFailedFinalCannotBeClaimed(
        SECOND_RESPONSE
            .replace("\"output_tokens\":30", "\"output_tokens\":5000")
            .replace("\"reasoning_tokens\":10", "\"reasoning_tokens\":100")
            .replace("\"total_tokens\":150", "\"total_tokens\":5120"));
    assertFailedFinalCannotBeClaimed(
        SECOND_RESPONSE.replace(
            "{\\\"content\\\":\\\"synthetic PUBLIC draft\\\","
                + "\\\"evidenceRefs\\\":[\\\"capture://"
                + "capture-openai-worker-010\\\"]}",
            "not-a-structured-final"));
  }

  @Test
  void structuredFinalOutcomeExpiresBeforeTerminalClaim() throws Exception {
    Instant expiresAt =
        Pack010GraphEvalCatalog.manifest(1).startedAt().plusSeconds(1);
    Fixture fixture = fixture(false, false, expiresAt);
    MutableClock clock =
        new MutableClock(expiresAt.minusNanos(1));
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(RESPONSE, SECOND_RESPONSE);
        ReviewedOpenAiClient client = client(server);
        Pack010ProviderSessionComposer.ProviderSession session =
            providerSessionWithOutcomeLedger(
                fixture, client, clock)) {
      session.next(turn(), context());
      var finalOutcome = session.next(toolResultTurn(), context());
      HarnessCandidateEnvelope exact =
          candidate(
              fixture.manifest(),
              SECOND_RESPONSE,
              "synthetic PUBLIC draft");

      clock.set(expiresAt);
      assertThrows(
          Pack010ProviderCredentialBroker.CredentialRejected.class,
          () ->
              Pack010ProviderSessionComposer.claimStructuredFinal(
                  finalOutcome,
                  fixture.coordinator(),
                  fixture.egress(),
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  fixture.manifest(),
                  exact));
      assertEquals(2, server.requestCount());
      assertEquals(2, fixture.store().providerAttributions);
    }
  }

  @Test
  void expiryBetweenSessionGuardAndObserverPreventsLoopbackHttpRequest()
      throws Exception {
    Instant expiresAt =
        Pack010GraphEvalCatalog.manifest(1).startedAt().plusMillis(10);
    Fixture fixture = fixture(false, false, expiresAt);
    Clock beforeExpiry =
        Clock.fixed(expiresAt.minusNanos(1), ZoneOffset.UTC);
    assertEquals(
        SYNTHETIC_CREDENTIAL,
        fixture.credentialLease().claim(
            fixture.coordinator(), fixture.egress(), beforeExpiry));
    SequencedClock clock =
        new SequencedClock(
            expiresAt.minusNanos(1), expiresAt, ZoneOffset.UTC);

    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer();
        ReviewedOpenAiClient client = client(server)) {
      AgentModel.Session modelSession =
          model(fixture, client, clock)
              .open(Pack010GraphEvalCatalog.childTask(1));
      try (Pack010ProviderSessionComposer.ProviderSession session =
          providerSession(fixture, modelSession, client, clock)) {
        assertThrows(
            Pack010ProviderCredentialBroker.CredentialRejected.class,
            () -> session.next(turn(), context()));

        assertEquals(2, clock.reads());
        assertEquals(0, server.requestCount());
        assertEquals(0, fixture.store().providerIntents);
        assertEquals(
            GraphAttemptEventType.MODEL_CREATED,
            fixture.store().events.getLast());
      }
    }
  }

  private static Fixture fixture(
      boolean rejectProviderIntent,
      boolean rejectProviderAttribution) {
    return fixture(
        rejectProviderIntent,
        rejectProviderAttribution,
        Instant.MAX);
  }

  private static Fixture fixture(
      boolean rejectProviderIntent,
      boolean rejectProviderAttribution,
      Instant expiresAt) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    Pack010ProviderSessionEffectOrderingTest.RecordingStore store =
        new Pack010ProviderSessionEffectOrderingTest.RecordingStore(
            rejectProviderIntent,
            rejectProviderAttribution);
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        Pack010ProviderSessionEffectOrderingTest.consumedEgress(
            coordinator, manifest, 1);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    coordinator.clientCreated(
        egress, manifest.startedAt().plusMillis(7));
    coordinator.modelCreated(
        egress, manifest.startedAt().plusMillis(8));
    return new Fixture(
        manifest,
        Pack010GraphEvalCatalog.workerProfile(1),
        store,
        coordinator,
        egress,
        syntheticLease(coordinator, egress, expiresAt));
  }

  private static OpenAiResponsesModel model(
      Fixture fixture, ReviewedOpenAiClient client) {
    return model(
        fixture,
        client,
        Clock.fixed(
            fixture.manifest().startedAt().plusMillis(9),
            ZoneOffset.UTC));
  }

  private static OpenAiResponsesModel model(
      Fixture fixture, ReviewedOpenAiClient client, Clock clock) {
    return OpenAiResponsesModel.withExactResponseAttribution(
        fixture.profile(),
        client,
        Pack010ProviderSessionComposer.durableIntentObserver(
            fixture.profile(),
            1,
            fixture.credentialLease(),
            fixture.coordinator(),
            fixture.egress(),
            clock),
        Pack010ProviderSessionComposer
            .durableAttributionObserver(
                fixture.profile(),
                1,
                fixture.coordinator(),
                fixture.egress(),
                clock));
  }

  private static void assertFailedFinalCannotBeClaimed(
      String secondResponse) throws Exception {
    Fixture fixture = fixture(false, false);
    Clock clock =
        Clock.fixed(
            fixture.manifest().startedAt().plusMillis(9),
            ZoneOffset.UTC);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(RESPONSE, secondResponse);
        ReviewedOpenAiClient client = client(server);
        Pack010ProviderSessionComposer.ProviderSession session =
            providerSessionWithOutcomeLedger(
                fixture, client, clock)) {
      session.next(turn(), context());
      var failed = session.next(toolResultTurn(), context());
      assertInstanceOf(AgentModel.Failed.class, failed.decision());
      assertEquals(2, server.requestCount());
      assertEquals(2, fixture.store().providerAttributions);
      HarnessCandidateEnvelope synthetic =
          candidate(
              fixture.manifest(),
              secondResponse,
              "synthetic PUBLIC draft");
      assertThrows(
          RuntimeException.class,
          () ->
              Pack010ProviderSessionComposer.claimStructuredFinal(
                  failed,
                  fixture.coordinator(),
                  fixture.egress(),
                  OwnerTtyGraphAuthority.Pack010Revision.R1,
                  fixture.manifest(),
                  synthetic));
    }
  }

  private static boolean claim(
      Pack010ProviderSessionComposer.Pack010AttributedModelOutcome outcome,
      Fixture fixture,
      HarnessCandidateEnvelope candidate) {
    try {
      Pack010ProviderSessionComposer.claimStructuredFinal(
          outcome,
          fixture.coordinator(),
          fixture.egress(),
          OwnerTtyGraphAuthority.Pack010Revision.R1,
          fixture.manifest(),
          candidate);
      return true;
    } catch (RuntimeException rejected) {
      return false;
    }
  }

  private static HarnessCandidateEnvelope candidate(
      GraphAttemptManifest manifest,
      String response,
      String content) {
    String evidenceRef =
        "capture://" + manifest.captureId();
    return HarnessCandidateEnvelope.create(
        manifest.attemptId(),
        manifest.executionSlotId(),
        manifest.experiment().repetition(),
        manifest.childSelection().runId(),
        manifest.childSelection().taskId(),
        IntegrityHashes.utf8ContentHash(response),
        IntegrityHashes.emptyTraceRoot(),
        Pack010GraphEvalCatalog
            .childTask(manifest.experiment().repetition())
            .outputSchema(),
        content,
        List.of(evidenceRef),
        List.of(evidenceRef),
        evidenceRef,
        true);
  }

  private static AgentModel.Turn toolResultTurn() {
    return new AgentModel.Turn(
        Pack010GraphEvalCatalog.childTask(1),
        List.of(
            new AgentModel.ToolResult(
                "capture.read",
                "capture://capture-openai-worker-010",
                "synthetic PUBLIC capture")));
  }

  private static Pack010ProviderSessionComposer.ProviderSession
      providerSessionWithOutcomeLedger(
          Fixture fixture,
          ReviewedOpenAiClient client,
          Clock clock) throws ReflectiveOperationException {
    fixture.credentialLease().claim(
        fixture.coordinator(), fixture.egress(), clock);
    Class<?> composer = Pack010ProviderSessionComposer.class;
    Class<?> ledgerType =
        Class.forName(
            "io.emergeos.grapheval."
                + "Pack010ProviderSessionComposer$OutcomeLedger");
    Constructor<?> ledgerConstructor =
        ledgerType.getDeclaredConstructor();
    ledgerConstructor.setAccessible(true);
    Object ledger = ledgerConstructor.newInstance();
    Method observerFactory =
        composer.getDeclaredMethod(
            "durableAttributionObserver",
            ModelBoundReadOnlyWorkerExecutionProfile.class,
            int.class,
            GraphAttemptCoordinator.class,
            GraphAttemptCoordinator.EgressAuthority.class,
            Clock.class,
            ledgerType);
    observerFactory.setAccessible(true);
    var attributionObserver =
        (OpenAiResponsesModel.ExactProviderAttributionObserver)
            observerFactory.invoke(
                null,
                fixture.profile(),
                1,
                fixture.coordinator(),
                fixture.egress(),
                clock,
                ledger);
    AgentModel.Session modelSession =
        OpenAiResponsesModel.withExactResponseAttribution(
                fixture.profile(),
                client,
                Pack010ProviderSessionComposer.durableIntentObserver(
                    fixture.profile(),
                    1,
                    fixture.credentialLease(),
                    fixture.coordinator(),
                    fixture.egress(),
                    clock),
                attributionObserver)
            .open(Pack010GraphEvalCatalog.childTask(1));
    Constructor<Pack010ProviderSessionComposer.ProviderSession>
        sessionConstructor =
            Pack010ProviderSessionComposer.ProviderSession.class
                .getDeclaredConstructor(
                    AgentModel.Session.class,
                    ReviewedOpenAiClient.class,
                    OwnerTtyGraphAuthority.Pack010Revision.class,
                    Pack010ProviderCredentialBroker.CredentialLease.class,
                    GraphAttemptCoordinator.class,
                    GraphAttemptCoordinator.EgressAuthority.class,
                    Clock.class,
                    GraphAttemptManifest.class,
                    ledgerType);
    sessionConstructor.setAccessible(true);
    return sessionConstructor.newInstance(
        modelSession,
        client,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        fixture.credentialLease(),
        fixture.coordinator(),
        fixture.egress(),
        clock,
        fixture.manifest(),
        ledger);
  }

  private static Pack010ProviderSessionComposer.ProviderSession
      providerSession(
          Fixture fixture,
          AgentModel.Session modelSession,
          ReviewedOpenAiClient client,
          Clock clock) throws ReflectiveOperationException {
    Constructor<Pack010ProviderSessionComposer.ProviderSession>
        constructor =
            Pack010ProviderSessionComposer.ProviderSession.class
                .getDeclaredConstructor(
                    AgentModel.Session.class,
                    ReviewedOpenAiClient.class,
                    OwnerTtyGraphAuthority.Pack010Revision.class,
                    Pack010ProviderCredentialBroker.CredentialLease.class,
                    GraphAttemptCoordinator.class,
                    GraphAttemptCoordinator.EgressAuthority.class,
                    Clock.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        modelSession,
        client,
        OwnerTtyGraphAuthority.Pack010Revision.R1,
        fixture.credentialLease(),
        fixture.coordinator(),
        fixture.egress(),
        clock);
  }

  private static ReviewedOpenAiClient client(
      LoopbackResponsesServer server) {
    return ReviewedOpenAiClient.defaultCodecNoRetry(
        SYNTHETIC_CREDENTIAL,
        server.baseUrl(),
        Proxy.NO_PROXY,
        Duration.ofSeconds(2),
        LogLevel.OFF);
  }

  private static AgentModel.Turn turn() {
    return new AgentModel.Turn(
        Pack010GraphEvalCatalog.childTask(1), List.of());
  }

  private static AgentModel.ModelCallContext context() {
    return new AgentModel.ModelCallContext(
        Pack010GraphEvalCatalog
            .workerProfile(1)
            .maxInputTokensPerStep(),
        Pack010GraphEvalCatalog.workerProfile(1).budgetUsd(),
        CancellationSignal.never());
  }

  private record Fixture(
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile profile,
      Pack010ProviderSessionEffectOrderingTest.RecordingStore store,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egress,
      Pack010ProviderCredentialBroker.CredentialLease credentialLease) {}

  private static Pack010ProviderCredentialBroker.CredentialLease
      syntheticLease(
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egress,
          Instant expiresAt) {
    try {
      return Pack010ProviderSessionEffectOrderingTest.syntheticLease(
          OwnerTtyGraphAuthority.Pack010Revision.R1,
          coordinator,
          egress,
          expiresAt);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static final class SequencedClock extends Clock {

    private final Instant first;
    private final Instant subsequent;
    private final ZoneId zone;
    private final AtomicInteger reads = new AtomicInteger();

    private SequencedClock(
        Instant first, Instant subsequent, ZoneId zone) {
      this.first = first;
      this.subsequent = subsequent;
      this.zone = zone;
    }

    private int reads() {
      return reads.get();
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId value) {
      return new SequencedClock(first, subsequent, value);
    }

    @Override
    public Instant instant() {
      return reads.getAndIncrement() == 0 ? first : subsequent;
    }
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      current = new AtomicReference<>(initial);
    }

    private void set(Instant value) {
      current.set(value);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("test clock is UTC only");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  private static final class LoopbackResponsesServer
      implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> responses;

    private LoopbackResponsesServer() throws IOException {
      this(RESPONSE);
    }

    private LoopbackResponsesServer(String... responses)
        throws IOException {
      this.responses = List.of(responses);
      if (this.responses.isEmpty()) {
        throw new IllegalArgumentException(
            "at least one loopback response is required");
      }
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
      int responseIndex = requests.getAndIncrement();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes =
          responses
              .get(Math.min(responseIndex, responses.size() - 1))
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static final String RESPONSE =
      """
      {
        "id":"resp-pack010-sentinel",
        "object":"response",
        "created_at":1785400000,
        "model":"gpt-5.6-terra",
        "status":"completed",
        "service_tier":"default",
        "parallel_tool_calls":false,
        "tool_choice":{"type":"function","name":"capture_read"},
        "tools":[],
        "output":[
          {
            "id":"reasoning-pack010-sentinel",
            "type":"reasoning",
            "summary":[],
            "encrypted_content":"synthetic-ciphertext-not-persisted",
            "status":"completed"
          },
          {
            "id":"function-pack010-sentinel",
            "type":"function_call",
            "call_id":"call-pack010-sentinel",
            "name":"capture_read",
            "arguments":"{\\\"reference\\\":\\\"capture://capture-openai-worker-010\\\"}",
            "status":"completed"
          }
        ],
        "usage":{
          "input_tokens":100,
          "input_tokens_details":{"cache_write_tokens":0,"cached_tokens":0},
          "output_tokens":10,
          "output_tokens_details":{"reasoning_tokens":5},
          "total_tokens":110
        }
      }
      """;

  static final String SECOND_RESPONSE =
      """
      {
        "id":"resp-pack010-second-sentinel",
        "object":"response",
        "created_at":1785400001,
        "model":"gpt-5.6-terra",
        "status":"completed",
        "service_tier":"default",
        "parallel_tool_calls":false,
        "tool_choice":"none",
        "tools":[],
        "output":[
          {
            "id":"reasoning-pack010-second-sentinel",
            "type":"reasoning",
            "summary":[],
            "encrypted_content":"synthetic-final-ciphertext-not-persisted",
            "status":"completed"
          },
          {
            "id":"message-pack010-second-sentinel",
            "type":"message",
            "role":"assistant",
            "status":"completed",
            "content":[
              {
                "type":"output_text",
                "annotations":[],
                "text":"{\\\"content\\\":\\\"synthetic PUBLIC draft\\\",\\\"evidenceRefs\\\":[\\\"capture://capture-openai-worker-010\\\"]}"
              }
            ]
          }
        ],
        "usage":{
          "input_tokens":120,
          "input_tokens_details":{"cache_write_tokens":0,"cached_tokens":20},
          "output_tokens":30,
          "output_tokens_details":{"reasoning_tokens":10},
          "total_tokens":150
        }
      }
      """;
}
