package io.emergeos.adapters.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentModelFailure;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import io.emergeos.core.domain.GraphPricingSnapshot;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OpenAiResponsesModelProtocolTest {

  private static final String EXECUTION_BINDING_HASH =
      IntegrityHashes.utf8ContentHash(
          "synthetic-reviewed-openai-attempt-v1");

  @Test
  void typedRequestReceiptCannotBindAnOpaqueSdkClient() {
    assertThrows(
        NoSuchMethodException.class,
        () ->
            OpenAiResponsesModel.class.getConstructor(
                io.emergeos.core.application.ModelExecutionProfile.class,
                OpenAIClient.class,
                OpenAiResponsesModel.ProviderInvocationObserver.class));
  }

  @Test
  void reviewedRequestHashSurvivesGlobalMapperMutationBeforeTransportWrite()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<OpenAiResponsesModel.ProviderInvocation> providerInvocations =
        new ArrayList<>();
    boolean indentOriginallyEnabled =
        ObjectMappers.jsonMapper().isEnabled(
            SerializationFeature.INDENT_OUTPUT);
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(requests, firstResponse());
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      AgentExecutionProfile profile = profile();
      TaskEnvelope task =
          profile.newDraftTask(
              "task-global-mapper-mutation",
              "synthetic-owner",
              "Create a synthetic public draft",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      OpenAiResponsesModel model =
          new OpenAiResponsesModel(
              profile,
              client,
              invocation -> {
                providerInvocations.add(invocation);
                ObjectMappers.jsonMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
              });

      model
          .open(task)
          .next(
              new AgentModel.Turn(task, List.of()),
              context(new BigDecimal("0.022000")));

      assertEquals(1, providerInvocations.size());
      assertEquals(1, requests.size());
      assertEquals(
          providerInvocations.getFirst().requestHash(),
          IntegrityHashes.utf8ContentHash(requests.getFirst()));
      assertFalse(requests.getFirst().contains("\n"));
    } finally {
      ObjectMappers.jsonMapper()
          .configure(
              SerializationFeature.INDENT_OUTPUT,
              indentOriginallyEnabled);
    }
  }

  @Test
  void reviewedRawResponseHashesExactWireBytesAndEmitsCompleteTypedAttribution()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<OpenAiResponsesModel.ProviderAttributionReceipt> attributions =
        new ArrayList<>();
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(requests, firstResponse());
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      AgentExecutionProfile profile = profile();
      TaskEnvelope task =
          profile.newDraftTask(
              "task-exact-response-attribution",
              "synthetic-owner",
              "Create a synthetic public draft",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      AgentModel.Session session =
          OpenAiResponsesModel.withExactResponseAttribution(
                  profile,
                  client,
                  ignored -> {},
                  attributions::add)
              .open(task);

      session.next(
          new AgentModel.Turn(task, List.of()),
          context(new BigDecimal("0.022000")));

      assertEquals(1, requests.size());
      assertEquals(1, attributions.size());
      OpenAiResponsesModel.ProviderAttributionReceipt attribution =
          attributions.getFirst();
      assertEquals(1, attribution.invocation().requestOrdinal());
      assertEquals(
          IntegrityHashes.utf8ContentHash(firstResponse()),
          attribution.responseHash());
      assertEquals("gpt-5.6", attribution.invocation().modelRequested());
      assertEquals("gpt-5.6-2026-07-15", attribution.modelResolved());
      assertEquals(100, attribution.inputTokens());
      assertEquals(20, attribution.cachedInputTokens());
      assertEquals(10, attribution.outputTokens());
      assertEquals(5, attribution.reasoningOutputTokens());
      assertEquals(110, attribution.totalTokens());
      assertEquals(
          new BigDecimal("0.000710"), attribution.observedCostUsd());
      assertFalse(attribution.toString().contains("resp-first"));
      assertFalse(attribution.toString().contains("ciphertext-only-replay"));
      assertFalse(attribution.toString().contains("sentinel-loopback-key"));
    }
  }

  @Test
  void reviewedResponseRejectsDuplicateTrailingAndOversizedBodiesWithoutAttribution()
      throws Exception {
    List<String> malformedBodies =
        List.of(
            firstResponse().replaceFirst(
                "\\\"id\\\":\\\"resp-first\\\",",
                "\\\"id\\\":\\\"resp-first\\\",\\\"id\\\":\\\"duplicate\\\","),
            firstResponse() + "{}",
            " ".repeat(
                ReviewedOpenAiClient.MAX_REVIEWED_RESPONSE_BYTES + 1));
    for (int index = 0; index < malformedBodies.size(); index++) {
      List<String> requests = new ArrayList<>();
      List<OpenAiResponsesModel.ProviderAttributionReceipt> attributions =
          new ArrayList<>();
      try (LoopbackResponsesServer server =
              new LoopbackResponsesServer(
                  requests, malformedBodies.get(index));
          ReviewedOpenAiClient client =
              ReviewedOpenAiClient.defaultCodecNoRetry(
                  "sentinel-loopback-key",
                  server.baseUrl(),
                  Proxy.NO_PROXY,
                  Duration.ofSeconds(2),
                  LogLevel.OFF)) {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task =
            profile.newDraftTask(
                "task-malformed-response-" + index,
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        AgentModel.Session session =
            OpenAiResponsesModel.withExactResponseAttribution(
                    profile,
                    client,
                    ignored -> {},
                    attributions::add)
                .open(task);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(task, List.of()),
                        context(new BigDecimal("0.022000"))));

        assertEquals(
            AgentModelFailure.Code.RESPONSE_MALFORMED,
            failure.code());
        assertEquals(1, requests.size());
        assertEquals(0, attributions.size());
        assertFalse(failure.toString().contains("duplicate"));
        assertFalse(failure.toString().contains("sentinel-loopback-key"));
      }
    }
  }

  @Test
  void reviewedMissingAndInconsistentUsageEmitNoExactAttribution()
      throws Exception {
    List<ResponseFailureCase> cases =
        List.of(
            new ResponseFailureCase(
                firstResponse().replace(
                    "\"usage\":{", "\"usage_missing\":{"),
                AgentModelFailure.Code.USAGE_MISSING),
            new ResponseFailureCase(
                firstResponse().replace(
                    "\"total_tokens\":110",
                    "\"total_tokens\":111"),
                AgentModelFailure.Code.RESPONSE_MALFORMED),
            new ResponseFailureCase(
                firstResponse().replace(
                    "\"cached_tokens\":20",
                    "\"cached_tokens\":101"),
                AgentModelFailure.Code.RESPONSE_MALFORMED));
    for (int index = 0; index < cases.size(); index++) {
      ResponseFailureCase failureCase = cases.get(index);
      List<String> requests = new ArrayList<>();
      List<OpenAiResponsesModel.ProviderAttributionReceipt> attributions =
          new ArrayList<>();
      try (LoopbackResponsesServer server =
              new LoopbackResponsesServer(
                  requests, failureCase.body());
          ReviewedOpenAiClient client =
              ReviewedOpenAiClient.defaultCodecNoRetry(
                  "sentinel-loopback-key",
                  server.baseUrl(),
                  Proxy.NO_PROXY,
                  Duration.ofSeconds(2),
                  LogLevel.OFF)) {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task =
            profile.newDraftTask(
                "task-invalid-usage-" + index,
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        AgentModel.Session session =
            OpenAiResponsesModel.withExactResponseAttribution(
                    profile,
                    client,
                    ignored -> {},
                    attributions::add)
                .open(task);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () -> session.next(
                    new AgentModel.Turn(task, List.of()),
                    context(new BigDecimal("0.022000"))));

        assertEquals(failureCase.code(), failure.code());
        assertEquals(1, requests.size());
        assertEquals(0, attributions.size());
      }
    }
  }

  @Test
  void reviewedCompleteUsagePersistsBeforeMalformedOutputDecision()
      throws Exception {
    List<String> bodies =
        List.of(
            firstResponse().replace(
                "\"call_id\":\"call-synthetic-003\"",
                "\"call_id\":42"),
            firstResponse().replace(
                "\"type\":\"function_call\"",
                "\"type\":\"future_function_call\""));
    for (int index = 0; index < bodies.size(); index++) {
      List<String> requests = new ArrayList<>();
      List<OpenAiResponsesModel.ProviderAttributionReceipt> attributions =
          new ArrayList<>();
      try (LoopbackResponsesServer server =
              new LoopbackResponsesServer(requests, bodies.get(index));
          ReviewedOpenAiClient client =
              ReviewedOpenAiClient.defaultCodecNoRetry(
                  "sentinel-loopback-key",
                  server.baseUrl(),
                  Proxy.NO_PROXY,
                  Duration.ofSeconds(2),
                  LogLevel.OFF)) {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task =
            profile.newDraftTask(
                "task-malformed-output-" + index,
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        AgentModel.Session session =
            OpenAiResponsesModel.withExactResponseAttribution(
                    profile,
                    client,
                    ignored -> {},
                    attributions::add)
                .open(task);

        AgentModel.ModelStep result =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        assertInstanceOf(AgentModel.Failed.class, result.decision());
        assertEquals(1, requests.size());
        assertEquals(1, attributions.size());
        assertEquals(
            IntegrityHashes.utf8ContentHash(bodies.get(index)),
            attributions.getFirst().responseHash());
      }
    }
  }

  @Test
  void exactOutcomeObserverRunsAfterSecondResponseFailureIsTyped()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<OpenAiResponsesModel.ProviderOutcomeReceipt> outcomes =
        new ArrayList<>();
    String malformedFinal =
        secondResponse().replace("evidenceRefs", "unexpectedRefs");
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), malformedFinal);
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      AgentExecutionProfile profile = profile();
      TaskEnvelope task =
          profile.newDraftTask(
              "task-exact-typed-failure",
              "synthetic-owner",
              "Create a synthetic public draft",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      AgentModel.Session session =
          OpenAiResponsesModel.withExactResponseOutcome(
                  profile,
                  client,
                  EXECUTION_BINDING_HASH,
                  ignored -> {},
                  outcomes::add)
              .open(task);

      AgentModel.ModelStep first =
          session.next(
              new AgentModel.Turn(task, List.of()),
              context(new BigDecimal("0.022000")));
      assertInstanceOf(AgentModel.ToolCall.class, first.decision());
      AgentModel.ModelStep second =
          session.next(
              new AgentModel.Turn(
                  task,
                  List.of(
                      new AgentModel.ToolResult(
                          "capture.read",
                          "capture://synthetic-003",
                          "literal synthetic seed"))),
              context(new BigDecimal("0.021290")));

      AgentModel.Failed failed =
          assertInstanceOf(AgentModel.Failed.class, second.decision());
      assertEquals("MODEL_RESPONSE_MALFORMED", failed.failureReason());
      assertEquals(2, outcomes.size());
      assertEquals(
          OpenAiResponsesModel.ProviderOutcomeKind.TOOL_CALL,
          outcomes.get(0).kind());
      assertEquals(
          OpenAiResponsesModel.ProviderOutcomeKind.FAILED,
          outcomes.get(1).kind());
      assertEquals(
          "MODEL_RESPONSE_MALFORMED",
          outcomes.get(1).failureReason());
      assertEquals(
          IntegrityHashes.utf8ContentHash(malformedFinal),
          outcomes.get(1).attribution().responseHash());
      assertEquals(
          "c6addaaa765ce4bf10c4997b0e3cb73dc71aaecab1b3468421f882259ecaa3ad",
          outcomes.get(1).decisionHash());
      GraphProviderValidationStatement statement =
          OpenAiProviderValidationStatements.fromExactOutcome(
              outcomes.get(1),
              graphAttribution(profile, outcomes.get(1)),
              EXECUTION_BINDING_HASH);
      assertEquals(
          GraphProviderValidationDecision.FAILED,
          statement.decision());
      assertEquals(
          GraphAttributedFailureCode.MODEL_RESPONSE_MALFORMED,
          statement.failureCode());
      assertEquals(
          outcomes.get(1).decisionHash(), statement.decisionHash());
      assertFalse(outcomes.toString().contains("sentinel-loopback-key"));
    }
  }

  @Test
  void exactOutcomeMapsReviewedUsageLimitToClosedV13Failure()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<OpenAiResponsesModel.ProviderOutcomeReceipt> outcomes =
        new ArrayList<>();
    String usageLimitedFinal =
        secondResponse()
            .replace("\"input_tokens\":120", "\"input_tokens\":1001")
            .replace("\"total_tokens\":140", "\"total_tokens\":1021");
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), usageLimitedFinal);
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      AgentExecutionProfile profile = profile();
      TaskEnvelope task =
          profile.newDraftTask(
              "task-exact-usage-limit",
              "synthetic-owner",
              "Create a synthetic public draft",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      AgentModel.Session session =
          OpenAiResponsesModel.withExactResponseOutcome(
                  profile,
                  client,
                  EXECUTION_BINDING_HASH,
                  ignored -> {},
                  outcomes::add)
              .open(task);

      session.next(
          new AgentModel.Turn(task, List.of()),
          context(new BigDecimal("0.022000")));
      AgentModel.ModelStep second =
          session.next(
              new AgentModel.Turn(
                  task,
                  List.of(
                      new AgentModel.ToolResult(
                          "capture.read",
                          "capture://synthetic-003",
                          "literal synthetic seed"))),
              context(new BigDecimal("0.021290")));

      AgentModel.Failed failed =
          assertInstanceOf(AgentModel.Failed.class, second.decision());
      assertEquals("MODEL_USAGE_LIMIT_EXCEEDED", failed.failureReason());
      assertEquals(2, outcomes.size());
      OpenAiResponsesModel.ProviderOutcomeReceipt exact = outcomes.get(1);
      assertEquals(
          OpenAiResponsesModel.ProviderOutcomeKind.FAILED, exact.kind());
      assertEquals(
          "MODEL_USAGE_LIMIT_EXCEEDED", exact.failureReason());
      GraphProviderValidationStatement statement =
          OpenAiProviderValidationStatements.fromExactOutcome(
              exact,
              graphAttribution(profile, exact),
              EXECUTION_BINDING_HASH);
      assertEquals(
          GraphProviderValidationDecision.FAILED,
          statement.decision());
      assertEquals(
          GraphAttributedFailureCode.MODEL_USAGE_LIMIT_EXCEEDED,
          statement.failureCode());
      assertFalse(outcomes.toString().contains("sentinel-loopback-key"));
    }
  }

  @Test
  void exactReviewedFinalMapsToFrozenV13SemanticStatement()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<OpenAiResponsesModel.ProviderOutcomeReceipt> outcomes =
        new ArrayList<>();
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), secondResponse());
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF)) {
      AgentExecutionProfile profile =
          profile("gpt-5.6", 5_000, 4_999, 30_000);
      TaskEnvelope task =
          profile.newDraftTask(
              "task-exact-v13-statement",
              "synthetic-owner",
              "Create a synthetic public draft",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      AgentModel.Session session =
          OpenAiResponsesModel.withExactResponseOutcome(
                  profile,
                  client,
                  EXECUTION_BINDING_HASH,
                  ignored -> {},
                  outcomes::add)
              .open(task);

      session.next(
          new AgentModel.Turn(task, List.of()),
          context(new BigDecimal("0.022000")));
      AgentModel.ModelStep second =
          session.next(
              new AgentModel.Turn(
                  task,
                  List.of(
                      new AgentModel.ToolResult(
                          "capture.read",
                          "capture://synthetic-003",
                          "literal synthetic seed"))),
              context(new BigDecimal("0.021290")));
      assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
      assertEquals(2, outcomes.size());

      OpenAiResponsesModel.ProviderOutcomeReceipt exact =
          outcomes.get(1);
      assertEquals(
          1,
          OpenAiResponsesModel.ProviderOutcomeReceipt.class
              .getDeclaredConstructors()
              .length);
      assertTrue(
          Modifier.isPrivate(
              OpenAiResponsesModel.ProviderOutcomeReceipt.class
                  .getDeclaredConstructors()[0]
                  .getModifiers()));
      assertEquals(
          1,
          OpenAiProviderValidationStatements.Policy.class
              .getDeclaredConstructors()
              .length);
      assertTrue(
          Modifier.isPrivate(
              OpenAiProviderValidationStatements.Policy.class
                  .getDeclaredConstructors()[0]
                  .getModifiers()));
      GraphProviderAttribution attribution =
          graphAttribution(profile, exact);
      OpenAiProviderValidationStatements.Policy policy =
          OpenAiProviderValidationStatements.policy();
      assertEquals(
          "574f2a58230173a78e23611e41e9fe9fa0bef27fffe0d8eb58f2736d2852cf43",
          exact.decisionHash());
      assertEquals(
          profile.pricing().fingerprint(),
          exact.pricingProfileFingerprint());
      assertEquals(
          "c23d13d8e26bb67bbf1e58cd19a1ad959fe82e9d4a49a40550746642ead940dd",
          policy.transportProfileHash());
      assertEquals(
          "bbf57f6e1feab2fea760bf2dda10aabfa12e067e7dce02f9c62caa253aa8f72e",
          policy.parserProfileHash());
      assertEquals(
          "fd9f878d4277a3915a622030198f5745e0432d5aff8b3ac62ccaf243b42c439f",
          policy.schemaProfileHash());
      GraphProviderValidationStatement statement =
          OpenAiProviderValidationStatements.fromExactOutcome(
              exact, attribution, EXECUTION_BINDING_HASH);

      assertEquals(
          GraphProviderValidationDecision.STRUCTURED_FINAL,
          statement.decision());
      assertEquals(null, statement.failureCode());
      assertEquals(exact.decisionHash(), statement.decisionHash());
      assertEquals(
          policy.transportProfileHash(),
          statement.transportProfileHash());
      assertEquals(
          policy.parserProfileHash(), statement.parserProfileHash());
      assertEquals(
          policy.schemaProfileHash(), statement.schemaProfileHash());
      assertFalse(exact.toString().contains("A synthetic draft"));
      assertFalse(
          exact.toString().contains("capture://synthetic-003"));
      assertFalse(exact.toString().contains("sentinel-loopback-key"));

      GraphProviderAttribution wrongResponse =
          GraphProviderAttribution.create(
              attribution.requestOrdinal(),
              attribution.requestHash(),
              "f".repeat(64),
              attribution.providerActor(),
              attribution.modelRequested(),
              attribution.modelResolved(),
              attribution.pricing(),
              attribution.inputTokens(),
              attribution.cachedInputTokens(),
              attribution.outputTokens(),
              attribution.reasoningOutputTokens(),
              attribution.totalTokens(),
              attribution.observedCostUsd());
      IllegalArgumentException mismatch =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  OpenAiProviderValidationStatements.fromExactOutcome(
                      exact, wrongResponse, EXECUTION_BINDING_HASH));
      assertEquals(
          "reviewed provider outcome does not match graph attribution",
          mismatch.getMessage());

      GraphPricingSnapshot wrongPricing =
          GraphPricingSnapshot.create(
              "openai-model-protocol-test-drift-v1",
              attribution.pricing().provider(),
              attribution.pricing().modelRequested(),
              attribution.pricing().uncachedInputNanoUsdPerToken(),
              attribution.pricing().cachedInputNanoUsdPerToken(),
              attribution.pricing().outputNanoUsdPerToken());
      GraphProviderAttribution pricingSplice =
          GraphProviderAttribution.create(
              attribution.requestOrdinal(),
              attribution.requestHash(),
              attribution.responseHash(),
              attribution.providerActor(),
              attribution.modelRequested(),
              attribution.modelResolved(),
              wrongPricing,
              attribution.inputTokens(),
              attribution.cachedInputTokens(),
              attribution.outputTokens(),
              attribution.reasoningOutputTokens(),
              attribution.totalTokens(),
              attribution.observedCostUsd());
      IllegalArgumentException pricingMismatch =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  OpenAiProviderValidationStatements.fromExactOutcome(
                      exact, pricingSplice, EXECUTION_BINDING_HASH));
      assertEquals(
          "reviewed provider outcome does not match graph attribution",
          pricingMismatch.getMessage());

      GraphProviderAttribution cachedTokenDrift =
          attributionVariant(
              attribution,
              attribution.requestOrdinal(),
              attribution.requestHash(),
              attribution.modelResolved(),
              attribution.pricing(),
              attribution.inputTokens(),
              attribution.cachedInputTokens() + 1,
              attribution.outputTokens(),
              attribution.reasoningOutputTokens());
      assertEquals(
          attribution.observedCostUsd(),
          cachedTokenDrift.observedCostUsd());
      List<GraphProviderAttribution> observableFieldDrifts =
          List.of(
              attributionVariant(
                  attribution,
                  1,
                  attribution.requestHash(),
                  attribution.modelResolved(),
                  attribution.pricing(),
                  attribution.inputTokens(),
                  attribution.cachedInputTokens(),
                  attribution.outputTokens(),
                  attribution.reasoningOutputTokens()),
              attributionVariant(
                  attribution,
                  attribution.requestOrdinal(),
                  "e".repeat(64),
                  attribution.modelResolved(),
                  attribution.pricing(),
                  attribution.inputTokens(),
                  attribution.cachedInputTokens(),
                  attribution.outputTokens(),
                  attribution.reasoningOutputTokens()),
              attributionVariant(
                  attribution,
                  attribution.requestOrdinal(),
                  attribution.requestHash(),
                  "gpt-5.6-drift",
                  attribution.pricing(),
                  attribution.inputTokens(),
                  attribution.cachedInputTokens(),
                  attribution.outputTokens(),
                  attribution.reasoningOutputTokens()),
              attributionVariant(
                  attribution,
                  attribution.requestOrdinal(),
                  attribution.requestHash(),
                  attribution.modelResolved(),
                  attribution.pricing(),
                  attribution.inputTokens() + 1,
                  attribution.cachedInputTokens(),
                  attribution.outputTokens(),
                  attribution.reasoningOutputTokens()),
              attributionVariant(
                  attribution,
                  attribution.requestOrdinal(),
                  attribution.requestHash(),
                  attribution.modelResolved(),
                  attribution.pricing(),
                  attribution.inputTokens(),
                  attribution.cachedInputTokens(),
                  attribution.outputTokens() + 1,
                  attribution.reasoningOutputTokens()),
              cachedTokenDrift,
              attributionVariant(
                  attribution,
                  attribution.requestOrdinal(),
                  attribution.requestHash(),
                  attribution.modelResolved(),
                  attribution.pricing(),
                  attribution.inputTokens(),
                  attribution.cachedInputTokens(),
                  attribution.outputTokens(),
                  attribution.reasoningOutputTokens() + 1));
      for (GraphProviderAttribution drift : observableFieldDrifts) {
        IllegalArgumentException fieldMismatch =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    OpenAiProviderValidationStatements.fromExactOutcome(
                        exact, drift, EXECUTION_BINDING_HASH));
        assertEquals(
            "reviewed provider outcome does not match graph attribution",
            fieldMismatch.getMessage());
      }

      IllegalArgumentException toolCall =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  OpenAiProviderValidationStatements.fromExactOutcome(
                      outcomes.get(0),
                      graphAttribution(profile, outcomes.get(0)),
                      EXECUTION_BINDING_HASH));
      assertEquals(
          "reviewed provider outcome does not match graph attribution",
          toolCall.getMessage());

      IllegalArgumentException crossAttempt =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  OpenAiProviderValidationStatements.fromExactOutcome(
                      exact,
                      attribution,
                      IntegrityHashes.utf8ContentHash(
                          "synthetic-reviewed-openai-attempt-v2")));
      assertEquals(
          "reviewed provider outcome does not match graph attribution",
          crossAttempt.getMessage());
    }
  }

  @Test
  void concurrentReviewedSessionsCannotCrossResponseReceipts()
      throws Exception {
    String secondWireBody =
        firstResponse()
            .replace("resp-first", "resp-concurrent-second")
            .replace("\"object\"", "  \"object\"");
    List<String> requests = new ArrayList<>();
    ConcurrentLinkedQueue<
            OpenAiResponsesModel.ProviderAttributionReceipt>
        attributions = new ConcurrentLinkedQueue<>();
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), secondWireBody);
        ReviewedOpenAiClient client =
            ReviewedOpenAiClient.defaultCodecNoRetry(
                "sentinel-loopback-key",
                server.baseUrl(),
                Proxy.NO_PROXY,
                Duration.ofSeconds(2),
                LogLevel.OFF);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AgentExecutionProfile profile = profile();
      TaskEnvelope firstTask =
          profile.newDraftTask(
              "task-concurrent-first",
              "synthetic-owner",
              "Create concurrent synthetic draft alpha",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      TaskEnvelope secondTask =
          profile.newDraftTask(
              "task-concurrent-second",
              "synthetic-owner",
              "Create concurrent synthetic draft beta",
              "capture://synthetic-003",
              DataClass.PUBLIC);
      AgentModel.Session firstSession =
          OpenAiResponsesModel.withExactResponseAttribution(
                  profile, client, ignored -> {}, attributions::add)
              .open(firstTask);
      AgentModel.Session secondSession =
          OpenAiResponsesModel.withExactResponseAttribution(
                  profile, client, ignored -> {}, attributions::add)
              .open(secondTask);

      var first = executor.submit(
          () -> firstSession.next(
              new AgentModel.Turn(firstTask, List.of()),
              context(new BigDecimal("0.022000"))));
      var second = executor.submit(
          () -> secondSession.next(
              new AgentModel.Turn(secondTask, List.of()),
              context(new BigDecimal("0.022000"))));
      assertInstanceOf(AgentModel.ToolCall.class,
          first.get().decision());
      assertInstanceOf(AgentModel.ToolCall.class,
          second.get().decision());

      assertEquals(2, requests.size());
      assertEquals(2, attributions.size());
      Map<String, String> expectedByRequestHash = new HashMap<>();
      expectedByRequestHash.put(
          IntegrityHashes.utf8ContentHash(requests.getFirst()),
          IntegrityHashes.utf8ContentHash(firstResponse()));
      expectedByRequestHash.put(
          IntegrityHashes.utf8ContentHash(requests.get(1)),
          IntegrityHashes.utf8ContentHash(secondWireBody));
      for (var attribution : attributions) {
        assertEquals(
            expectedByRequestHash.get(
                attribution.invocation().requestHash()),
            attribution.responseHash());
      }
      assertEquals(2, expectedByRequestHash.size());
    }
  }

  @Test
  void performsTwoStatelessStrictResponsesCallsWithManualItemReplay()
      throws Exception {
    List<String> requests = new ArrayList<>();
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), secondResponse())) {
      ReviewedOpenAiClient client =
          ReviewedOpenAiClient.defaultCodecNoRetry(
              "sentinel-loopback-key",
              server.baseUrl(),
              Proxy.NO_PROXY,
              Duration.ofSeconds(2),
              LogLevel.OFF);
      try {
        AgentExecutionProfile profile = profile();
        List<OpenAiResponsesModel.ProviderInvocation>
            providerInvocations = new ArrayList<>();
        TaskEnvelope task =
            profile.newDraftTask(
                "task-synthetic-003",
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        OpenAiResponsesModel model =
            new OpenAiResponsesModel(
                profile, client, providerInvocations::add);
        AgentModel.Session session = model.open(task);

        assertEquals(0, requests.size());
        AgentModel.ModelStep first =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));
        AgentModel.ToolCall toolCall =
            assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        assertEquals("capture.read", toolCall.toolName());
        assertEquals(
            AgentModel.ToolArguments.forReference("capture://synthetic-003"),
            toolCall.arguments());
        assertEquals(new BigDecimal("0.000710"), first.usage().costUsd());

        AgentModel.ModelStep second =
            session.next(
                new AgentModel.Turn(
                    task,
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            "capture://synthetic-003",
                            "literal synthetic seed"))),
                context(new BigDecimal("0.021290")));
        AgentModel.FinalDraft draft =
            assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
        assertEquals("A synthetic draft", draft.content());
        assertEquals(
            List.of("capture://synthetic-003"), draft.evidenceRefs());
        assertEquals(new BigDecimal("0.001200"), second.usage().costUsd());

        assertEquals(2, requests.size());
        assertEquals(2, providerInvocations.size());
        assertEquals(
            new OpenAiResponsesModel.ProviderInvocation(
                1,
                IntegrityHashes.utf8ContentHash(requests.get(0)),
                profile.modelRequested()),
            providerInvocations.get(0));
        assertEquals(
            providerInvocations.get(0).requestHash(),
            OpenAiResponsesModel.firstRequestFingerprint(
                profile, task));
        assertEquals(
            new OpenAiResponsesModel.ProviderInvocation(
                2,
                IntegrityHashes.utf8ContentHash(requests.get(1)),
                profile.modelRequested()),
            providerInvocations.get(1));
        assertFirstRequest(
            ObjectMappers.jsonMapper().readTree(requests.get(0)));
        assertSecondRequest(
            ObjectMappers.jsonMapper().readTree(requests.get(1)));
      } finally {
        client.close();
      }
    }
  }

  @Test
  void acceptsTheExactPack008ModelBoundWorkerChildTask()
      throws Exception {
    List<String> requests = new ArrayList<>();
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(requests, firstResponse())) {
      OpenAIClient client =
          OpenAIOkHttpClient.builder()
              .apiKey("sentinel-loopback-key")
              .baseUrl(server.baseUrl())
              .maxRetries(0)
              .timeout(Duration.ofSeconds(2))
              .build();
      try {
        ModelBoundReadOnlyWorkerExecutionProfile worker =
            ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
                profile().pricing(),
                OpenAiResponsesModel.PROTOCOL_VERSION,
                "f".repeat(64),
                "environment://sha256:" + "b".repeat(64),
                new HarnessExperiment("openai-worker-h0", 1),
                1_000,
                200,
                new BigDecimal("0.022000"));
        AgentExecutionProfile parentProfile =
            AgentExecutionProfile.readOnlyWorkerModelV1(worker);
        TaskEnvelope parent =
            parentProfile.newDraftTask(
                "pack008-parent-task",
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        TaskEnvelope child =
            worker.newChildTask(
                parent,
                new WorkerHandoffRequest(
                    worker.workerName(),
                    parent.intent(),
                    parent.inputRefs()),
                new AgentWorkerRuntime.ExecutionWindow(
                    parent.deadlineMs(),
                    parent.budgetUsd(),
                    CancellationSignal.never()),
                "pack008-child-task");

        AgentModel.Session session =
            new OpenAiResponsesModel(worker, client).open(child);
        AgentModel.ModelStep first =
            session.next(
                new AgentModel.Turn(child, List.of()),
                context(worker.budgetUsd()));

        assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        assertEquals(1, requests.size());
        JsonNode request =
            ObjectMappers.jsonMapper().readTree(requests.getFirst());
        assertEquals("gpt-5.6", request.path("model").asText());
        assertEquals(
            "Task intent:\n"
                + parent.intent()
                + "\nDeclared capture reference:\n"
                + "capture://synthetic-003",
            request.at("/input/0/content").asText());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void omitsUnsupportedPromptCacheOptionsForAnEarlierModel()
      throws Exception {
    List<String> requests = new ArrayList<>();
    String response =
        firstResponse()
            .replace(
                "\"model\":\"gpt-5.6-2026-07-15\"",
                "\"model\":\"gpt-5-mini-2025-08-07\"")
            .replace(
                "\"input_tokens_details\":{\"cached_tokens\":20}",
                "\"input_tokens_details\":{\"cached_tokens\":0}");
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(requests, response)) {
      OpenAIClient client =
          OpenAIOkHttpClient.builder()
              .apiKey("sentinel-loopback-key")
              .baseUrl(server.baseUrl())
              .maxRetries(0)
              .timeout(Duration.ofSeconds(2))
              .build();
      try {
        AgentExecutionProfile profile =
            profile("gpt-5-mini", 250, 25, 2_000);
        TaskEnvelope task =
            profile.newDraftTask(
                "task-synthetic-mini-003",
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        assertInstanceOf(AgentModel.ToolCall.class, step.decision());
        assertEquals(1, requests.size());
        JsonNode request =
            ObjectMappers.jsonMapper().readTree(requests.getFirst());
        assertEquals("gpt-5-mini", request.path("model").asText());
        assertFalse(request.has("prompt_cache_options"));
      } finally {
        client.close();
      }
    }
  }

  private static void assertFirstRequest(JsonNode request) {
    assertEquals("gpt-5.6", request.path("model").asText());
    assertEquals(200, request.path("max_output_tokens").asInt());
    assertFalse(request.path("store").asBoolean(true));
    assertFalse(request.path("parallel_tool_calls").asBoolean(true));
    assertEquals("default", request.path("service_tier").asText());
    assertEquals(
        "explicit",
        request.at("/prompt_cache_options/mode").asText());
    assertEquals(1, request.path("prompt_cache_options").size());
    assertFalse(request.toString().contains("prompt_cache_breakpoint"));
    assertEquals(1, request.path("include").size());
    assertEquals(
        "reasoning.encrypted_content", request.path("include").get(0).asText());
    assertFalse(request.has("previous_response_id"));
    assertFalse(request.has("conversation"));
    assertFalse(request.toString().contains("literal synthetic seed"));
    assertEquals(1, request.path("input").size());
    assertEquals("user", request.at("/input/0/role").asText());
    assertEquals(
        "Task intent:\n"
            + "Create a synthetic public draft\n"
            + "Declared capture reference:\n"
            + "capture://synthetic-003",
        request.at("/input/0/content").asText());
    assertEquals(1, request.path("tools").size());
    assertEquals("capture_read", request.at("/tools/0/name").asText());
    assertTrue(request.at("/tools/0/strict").asBoolean());
    assertEquals(
        "object", request.at("/tools/0/parameters/type").asText());
    assertEquals(
        "string",
        request
            .at("/tools/0/parameters/properties/reference/type")
            .asText());
    assertEquals(
        "reference",
        request.at("/tools/0/parameters/required/0").asText());
    assertEquals(
        1, request.at("/tools/0/parameters/required").size());
    assertFalse(
        request
            .at("/tools/0/parameters/additionalProperties")
            .asBoolean(true));
    assertEquals(
        "capture_read", request.at("/tool_choice/name").asText());
  }

  private static void assertSecondRequest(JsonNode request) {
    assertEquals("gpt-5.6", request.path("model").asText());
    assertEquals(200, request.path("max_output_tokens").asInt());
    assertFalse(request.path("store").asBoolean(true));
    assertFalse(request.path("parallel_tool_calls").asBoolean(true));
    assertEquals("default", request.path("service_tier").asText());
    assertEquals(
        "explicit",
        request.at("/prompt_cache_options/mode").asText());
    assertEquals(1, request.path("prompt_cache_options").size());
    assertFalse(request.toString().contains("prompt_cache_breakpoint"));
    assertEquals(1, request.path("include").size());
    assertEquals(
        "reasoning.encrypted_content", request.path("include").get(0).asText());
    assertFalse(request.has("previous_response_id"));
    assertFalse(request.has("conversation"));
    assertEquals("none", request.path("tool_choice").asText());
    assertEquals(0, request.path("tools").size());
    assertEquals(4, request.path("input").size());
    assertEquals("user", request.at("/input/0/role").asText());
    assertEquals("reasoning", request.at("/input/1/type").asText());
    assertEquals(
        "ciphertext-only-replay",
        request.at("/input/1/encrypted_content").asText());
    assertEquals("function_call", request.at("/input/2/type").asText());
    assertEquals(
        "call-synthetic-003",
        request.at("/input/2/call_id").asText());
    assertEquals(
        "capture_read", request.at("/input/2/name").asText());
    assertEquals(
        "{\"reference\":\"capture://synthetic-003\"}",
        request.at("/input/2/arguments").asText());
    assertEquals(
        "function_call_output", request.at("/input/3/type").asText());
    assertEquals(
        "call-synthetic-003",
        request.at("/input/3/call_id").asText());
    assertEquals(
        "{\"reference\":\"capture://synthetic-003\","
            + "\"content\":\"literal synthetic seed\"}",
        request.at("/input/3/output").asText());
    assertEquals("direct", request.at("/input/3/caller/type").asText());
    assertEquals(
        "json_schema", request.at("/text/format/type").asText());
    assertTrue(request.at("/text/format/strict").asBoolean());
    assertEquals(
        "object", request.at("/text/format/schema/type").asText());
    assertEquals(
        "string",
        request
            .at("/text/format/schema/properties/content/type")
            .asText());
    assertEquals(
        "array",
        request
            .at("/text/format/schema/properties/evidenceRefs/type")
            .asText());
    assertEquals(
        "string",
        request
            .at("/text/format/schema/properties/evidenceRefs/items/type")
            .asText());
    assertEquals(
        List.of("content", "evidenceRefs"),
        List.of(
            request.at("/text/format/schema/required/0").asText(),
            request.at("/text/format/schema/required/1").asText()));
    assertEquals(2, request.at("/text/format/schema/required").size());
    assertFalse(
        request
            .at("/text/format/schema/additionalProperties")
            .asBoolean(true));
  }

  private static AgentModel.ModelCallContext context(BigDecimal budget) {
    return new AgentModel.ModelCallContext(
        1_500, budget, CancellationSignal.never());
  }

  private static GraphProviderAttribution graphAttribution(
      AgentExecutionProfile profile,
      OpenAiResponsesModel.ProviderOutcomeReceipt outcome) {
    OpenAiResponsesModel.ProviderAttributionReceipt receipt =
        outcome.attribution();
    return GraphProviderAttribution.create(
        receipt.invocation().requestOrdinal(),
        receipt.invocation().requestHash(),
        receipt.responseHash(),
        "synthetic-openai-worker",
        receipt.invocation().modelRequested(),
        receipt.modelResolved(),
        profile.pricing().graphSnapshot(),
        receipt.inputTokens(),
        receipt.cachedInputTokens(),
        receipt.outputTokens(),
        receipt.reasoningOutputTokens(),
        receipt.totalTokens(),
        receipt.observedCostUsd());
  }

  private static GraphProviderAttribution attributionVariant(
      GraphProviderAttribution source,
      int requestOrdinal,
      String requestHash,
      String modelResolved,
      GraphPricingSnapshot pricing,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens) {
    return GraphProviderAttribution.create(
        requestOrdinal,
        requestHash,
        source.responseHash(),
        source.providerActor(),
        source.modelRequested(),
        modelResolved,
        pricing,
        inputTokens,
        cachedInputTokens,
        outputTokens,
        reasoningOutputTokens,
        Math.addExact(inputTokens, outputTokens),
        pricing.actualCostUsd(
            inputTokens, cachedInputTokens, outputTokens));
  }

  private static AgentExecutionProfile profile() {
    return profile("gpt-5.6", 5_000, 500, 30_000);
  }

  private static AgentExecutionProfile profile(
      String modelRequested,
      long uncachedInputNanoUsdPerToken,
      long cachedInputNanoUsdPerToken,
      long outputNanoUsdPerToken) {
    PricingProfile pricing =
        new PricingProfile(
            "openai-model-protocol-test-v1",
            "openai.responses",
            modelRequested,
            uncachedInputNanoUsdPerToken,
            cachedInputNanoUsdPerToken,
            outputNanoUsdPerToken);
    return new AgentExecutionProfile(
        "synthetic-openai-agent-draft-v1",
        "1.1",
        RiskLevel.EXTERNAL,
        2,
        1,
        30_000,
        new BigDecimal("0.022000"),
        1_000,
        200,
        pricing,
        "openai-responses-v1-openai-java-4.43.0",
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        new HarnessExperiment("openai-responses-h0", 1),
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v2",
        "environment://sha256:" + "b".repeat(64),
        List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
        DataClass.PUBLIC);
  }

  private static String firstResponse() {
    return """
        {
          "id":"resp-first",
          "object":"response",
          "created_at":1785400000,
          "model":"gpt-5.6-2026-07-15",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":{"type":"function","name":"capture_read"},
          "tools":[],
          "output":[
            {
              "id":"reasoning-first",
              "type":"reasoning",
              "summary":[],
              "encrypted_content":"ciphertext-only-replay",
              "status":"completed"
            },
            {
              "id":"function-first",
              "type":"function_call",
              "call_id":"call-synthetic-003",
              "name":"capture_read",
              "arguments":"{\\"reference\\":\\"capture://synthetic-003\\"}",
              "status":"completed"
            }
          ],
          "usage":{
            "input_tokens":100,
            "input_tokens_details":{"cache_write_tokens":0,"cached_tokens":20},
            "output_tokens":10,
            "output_tokens_details":{"reasoning_tokens":5},
            "total_tokens":110
          }
        }
        """;
  }

  private static String secondResponse() {
    return """
        {
          "id":"resp-second",
          "object":"response",
          "created_at":1785400001,
          "model":"gpt-5.6-2026-07-15",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":"none",
          "tools":[],
          "output":[
            {
              "id":"reasoning-second",
              "type":"reasoning",
              "summary":[],
              "encrypted_content":"ciphertext-final-not-persisted",
              "status":"completed"
            },
            {
              "id":"message-second",
              "type":"message",
              "role":"assistant",
              "status":"completed",
              "content":[
                {
                  "type":"output_text",
                  "annotations":[],
                  "text":"{\\"content\\":\\"A synthetic draft\\",\\"evidenceRefs\\":[\\"capture://synthetic-003\\"]}"
                }
              ]
            }
          ],
          "usage":{
            "input_tokens":120,
            "input_tokens_details":{"cache_write_tokens":0,"cached_tokens":0},
            "output_tokens":20,
            "output_tokens_details":{"reasoning_tokens":8},
            "total_tokens":140
          }
        }
        """;
  }

  private record ResponseFailureCase(
      String body, AgentModelFailure.Code code) {}

  private static final class LoopbackResponsesServer implements AutoCloseable {
    private final HttpServer server;

    private LoopbackResponsesServer(
        List<String> requests, String... responses) throws IOException {
      server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/v1/responses",
          exchange -> {
            int index;
            synchronized (requests) {
              index = requests.size();
              requests.add(
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      StandardCharsets.UTF_8));
            }
            respond(exchange, responses[Math.min(index, responses.length - 1)]);
          });
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private static void respond(HttpExchange exchange, String body)
        throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }
}
