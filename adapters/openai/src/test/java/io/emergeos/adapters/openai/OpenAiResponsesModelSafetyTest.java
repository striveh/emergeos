package io.emergeos.adapters.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentModelFailure;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.port.CancellationSignal;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OpenAiResponsesModelSafetyTest {

  @Test
  void cancellationWinsBeforeBudgetAndSocket() throws Exception {
    try (RecordingServer server =
        new RecordingServer(ok(firstResponse(
            "capture://synthetic-003",
            "call-cancel",
            "cipher-cancel",
            normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(task, List.of()),
                        new AgentModel.ModelCallContext(
                            1_000, BigDecimal.ZERO, () -> true)));

        assertEquals(AgentModelFailure.Code.CANCELLED, failure.code());
        assertEquals("CANCELLED", failure.getMessage());
        assertEquals(0, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void requiresFullPerCallReservationBeforeSocket() throws Exception {
    try (RecordingServer server =
        new RecordingServer(ok(firstResponse(
            "capture://synthetic-003",
            "call-budget",
            "cipher-budget",
            normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(task, List.of()),
                        context(new BigDecimal("0.010999"))));

        assertEquals(
            AgentModelFailure.Code.BUDGET_EXHAUSTED, failure.code());
        assertEquals("MODEL_BUDGET_EXHAUSTED", failure.getMessage());
        assertEquals(0, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void rejectsInconsistentUsageWithoutInventingAReceipt() throws Exception {
    List<UsageCase> cases =
        List.of(
            new UsageCase(null, AgentModelFailure.Code.USAGE_MISSING),
            new UsageCase(
                usage(10, 11, 2, 1, 12),
                AgentModelFailure.Code.RESPONSE_MALFORMED),
            new UsageCase(
                usage(10, 0, 2, 3, 12),
                AgentModelFailure.Code.RESPONSE_MALFORMED),
            new UsageCase(
                usage(10, 0, 2, 1, 13),
                AgentModelFailure.Code.RESPONSE_MALFORMED),
            new UsageCase(
                usage(-1, 0, 2, 1, 1),
                AgentModelFailure.Code.RESPONSE_MALFORMED));

    for (int index = 0; index < cases.size(); index++) {
      UsageCase usageCase = cases.get(index);
      try (RecordingServer server =
          new RecordingServer(
              ok(
                  firstResponse(
                      "capture://synthetic-003",
                      "call-usage-" + index,
                      "cipher-usage-" + index,
                      usageCase.usageJson())))) {
        OpenAIClient client = client(server);
        try {
          AgentExecutionProfile profile = profile();
          TaskEnvelope task = task(profile, "003");
          AgentModel.Session session =
              new OpenAiResponsesModel(profile, client).open(task);

          AgentModelFailure failure =
              assertThrows(
                  AgentModelFailure.class,
                  () ->
                      session.next(
                          new AgentModel.Turn(task, List.of()),
                          context(new BigDecimal("0.022000"))));

          assertEquals(usageCase.expected(), failure.code());
          assertEquals(1, server.requestCount());
        } finally {
          client.close();
        }
      }
    }
  }

  @Test
  void retainsAttributedUsageAboveTheReviewedTokenCeiling()
      throws Exception {
    String overLimitUsage = usage(1_001, 0, 10, 5, 1_011);
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://synthetic-003",
                    "call-over-limit",
                    "cipher-over-limit",
                    overLimitUsage)))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals(
            "MODEL_USAGE_LIMIT_EXCEEDED", failure.failureReason());
        assertEquals(new BigDecimal("0.005305"), step.usage().costUsd());
        assertEquals(1_011, step.usage().tokenCount());
        assertEquals("gpt-5.6-2026-07-15", step.resolvedModel());
        assertEquals(1, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void retainsUsageWhenTheProviderResolvesToAnotherSafeModel()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "gpt-4.1-2026-06-01",
                    "capture://synthetic-003",
                    "call-model-mismatch",
                    "cipher-model-mismatch",
                    normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals(
            "MODEL_ATTRIBUTION_MISMATCH", failure.failureReason());
        assertEquals("gpt-4.1-2026-06-01", step.resolvedModel());
        assertEquals(new BigDecimal("0.000710"), step.usage().costUsd());
        assertEquals(110, step.usage().tokenCount());
        assertEquals(1, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void returnsAttributedFailureForAFunctionCallOutsideTheTask()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://foreign-source",
                    "call-foreign",
                    "cipher-foreign",
                    normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals("MODEL_RESPONSE_MALFORMED", failure.failureReason());
        assertEquals(new BigDecimal("0.000710"), step.usage().costUsd());
        assertEquals(110, step.usage().tokenCount());
        assertEquals(1, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void rejectsAmbiguousToolArgumentsWithoutLosingUsage() throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponseArguments(
                    "gpt-5.6-2026-07-15",
                    """
                    {"reference":"capture://synthetic-003",
                     "reference":"capture://foreign-source"}
                    """,
                    "call-ambiguous-tool",
                    "cipher-ambiguous-tool",
                    normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals("MODEL_RESPONSE_MALFORMED", failure.failureReason());
        assertEquals(new BigDecimal("0.000710"), step.usage().costUsd());
        assertEquals(110, step.usage().tokenCount());
        assertEquals(1, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void returnsAttributedFailureForAFinalDraftWithForeignEvidence()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://synthetic-003",
                    "call-final-foreign",
                    "cipher-final-foreign",
                    normalUsage())),
            ok(secondResponse("capture://foreign-source", "Unsafe draft")))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);
        session.next(
            new AgentModel.Turn(task, List.of()),
            context(new BigDecimal("0.022000")));

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(
                    task,
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            "capture://synthetic-003",
                            "literal synthetic seed"))),
                context(new BigDecimal("0.021290")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals("MODEL_RESPONSE_MALFORMED", failure.failureReason());
        assertEquals(new BigDecimal("0.001200"), step.usage().costUsd());
        assertEquals(140, step.usage().tokenCount());
        assertEquals(2, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void retainsSecondCallUsageWhenTypedFinalJsonIsInvalidOrAmbiguous()
      throws Exception {
    List<String> invalidFinals =
        List.of(
            "[]",
            """
            {"content":"first","content":"second",
             "evidenceRefs":["capture://synthetic-003"]}
            """,
            """
            {"content":"first",
             "evidenceRefs":["capture://synthetic-003"]} {}
            """);

    for (int index = 0; index < invalidFinals.size(); index++) {
      try (RecordingServer server =
          new RecordingServer(
              ok(
                  firstResponse(
                      "capture://synthetic-003",
                      "call-invalid-final-" + index,
                      "cipher-invalid-final-" + index,
                      normalUsage())),
              ok(secondResponseText(invalidFinals.get(index))))) {
        OpenAIClient client = client(server);
        try {
          AgentExecutionProfile profile = profile();
          TaskEnvelope task = task(profile, "003");
          AgentModel.Session session =
              new OpenAiResponsesModel(profile, client).open(task);
          session.next(
              new AgentModel.Turn(task, List.of()),
              context(new BigDecimal("0.022000")));

          AgentModel.ModelStep step =
              session.next(
                  new AgentModel.Turn(
                      task,
                      List.of(
                          new AgentModel.ToolResult(
                              "capture.read",
                              "capture://synthetic-003",
                              "literal synthetic seed"))),
                  context(new BigDecimal("0.021290")));

          AgentModel.Failed failure =
              assertInstanceOf(AgentModel.Failed.class, step.decision());
          assertEquals(
              "MODEL_RESPONSE_MALFORMED", failure.failureReason());
          assertEquals("gpt-5.6-2026-07-15", step.resolvedModel());
          assertEquals(
              new BigDecimal("0.001200"), step.usage().costUsd());
          assertEquals(140, step.usage().tokenCount());
          assertEquals(2, server.requestCount());
        } finally {
          client.close();
        }
      }
    }
  }

  @Test
  void retainsSecondCallUsageWhenResolvedModelDriftsAcrossSteps()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://synthetic-003",
                    "call-model-drift",
                    "cipher-model-drift",
                    normalUsage())),
            ok(
                secondResponse(
                    "gpt-5.6-2026-07-16",
                    "capture://synthetic-003",
                    "Unsafe drifted draft")))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);
        session.next(
            new AgentModel.Turn(task, List.of()),
            context(new BigDecimal("0.022000")));

        AgentModel.ModelStep step =
            session.next(
                new AgentModel.Turn(
                    task,
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            "capture://synthetic-003",
                            "literal synthetic seed"))),
                context(new BigDecimal("0.021290")));

        AgentModel.Failed failure =
            assertInstanceOf(AgentModel.Failed.class, step.decision());
        assertEquals(
            "MODEL_ATTRIBUTION_MISMATCH", failure.failureReason());
        assertEquals("gpt-5.6-2026-07-16", step.resolvedModel());
        assertEquals(new BigDecimal("0.001200"), step.usage().costUsd());
        assertEquals(140, step.usage().tokenCount());
        assertEquals(2, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void malformedSuccessfulHttpBodyHasNoInventedAttribution()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(ok("{not-json"))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(task, List.of()),
                        context(new BigDecimal("0.022000"))));

        assertEquals(
            AgentModelFailure.Code.RESPONSE_MALFORMED, failure.code());
        assertEquals("MODEL_RESPONSE_MALFORMED", failure.getMessage());
        assertEquals(1, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void aSessionRejectsAnotherValidTaskBeforeSocket() throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://synthetic-a",
                    "call-a",
                    "cipher-a",
                    normalUsage())))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope taskA =
            profile.newDraftTask(
                "task-synthetic-a",
                "synthetic-owner",
                "Intent A",
                "capture://synthetic-a",
                DataClass.PUBLIC);
        TaskEnvelope taskB =
            profile.newDraftTask(
                "task-synthetic-b",
                "synthetic-owner",
                "Intent B",
                "capture://synthetic-b",
                DataClass.PUBLIC);
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(taskA);

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(taskB, List.of()),
                        context(new BigDecimal("0.022000"))));

        assertEquals(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED, failure.code());
        assertEquals(0, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void mapsHttpFailuresWithoutRetryOrRawProviderLeak() throws Exception {
    List<HttpFailureCase> cases =
        List.of(
            new HttpFailureCase(
                401, AgentModelFailure.Code.AUTHENTICATION_FAILED),
            new HttpFailureCase(
                400, AgentModelFailure.Code.REQUEST_REJECTED),
            new HttpFailureCase(
                403, AgentModelFailure.Code.REQUEST_REJECTED),
            new HttpFailureCase(
                404, AgentModelFailure.Code.REQUEST_REJECTED),
            new HttpFailureCase(
                422, AgentModelFailure.Code.REQUEST_REJECTED),
            new HttpFailureCase(429, AgentModelFailure.Code.RATE_LIMITED),
            new HttpFailureCase(
                500, AgentModelFailure.Code.PROVIDER_UNAVAILABLE),
            new HttpFailureCase(
                503, AgentModelFailure.Code.PROVIDER_UNAVAILABLE));

    for (HttpFailureCase failureCase : cases) {
      try (RecordingServer server =
          new RecordingServer(
              new ResponseSpec(
                  failureCase.status(),
                  errorResponse(),
                  0))) {
        OpenAIClient client = client(server);
        try {
          AgentExecutionProfile profile = profile();
          TaskEnvelope task = task(profile, "003");
          AgentModel.Session session =
              new OpenAiResponsesModel(profile, client).open(task);

          AgentModelFailure failure =
              assertThrows(
                  AgentModelFailure.class,
                  () ->
                      session.next(
                          new AgentModel.Turn(task, List.of()),
                          context(new BigDecimal("0.022000"))));

          assertEquals(failureCase.expected(), failure.code());
          assertFalse(failure.getMessage().contains("RAW_PROVIDER_SECRET"));
          assertEquals(null, failure.getCause());
          assertFalse(failure.toString().contains("RAW_PROVIDER_SECRET"));
          assertEquals(1, server.requestCount());
        } finally {
          client.close();
        }
      }
    }
  }

  @Test
  void perRequestDeadlineMapsAcceptedTimeoutToUnknownWithoutRetry()
      throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            new ResponseSpec(
                200,
                firstResponse(
                    "capture://synthetic-003",
                    "call-timeout",
                    "cipher-timeout",
                    normalUsage()),
                250))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        AgentModel.Session session =
            new OpenAiResponsesModel(profile, client).open(task);
        long started = System.nanoTime();

        AgentModelFailure failure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    session.next(
                        new AgentModel.Turn(task, List.of()),
                        new AgentModel.ModelCallContext(
                            25,
                            new BigDecimal("0.022000"),
                            CancellationSignal.never())));

        long elapsedMs =
            Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(
            AgentModelFailure.Code.CALL_OUTCOME_UNKNOWN, failure.code());
        assertEquals(1, server.requestCount());
        assertTrue(elapsedMs < 1_000);
      } finally {
        client.close();
      }
    }
  }

  @Test
  void secondRequestTimeoutAndDisconnectAreNeverRetried() throws Exception {
    List<ResponseSpec> secondFailures =
        List.of(
            new ResponseSpec(
                200,
                secondResponse(
                    "capture://synthetic-003", "Too late"),
                250),
            new ResponseSpec(-1, "", 0));

    for (ResponseSpec secondFailure : secondFailures) {
      try (RecordingServer server =
          new RecordingServer(
              ok(
                  firstResponse(
                      "capture://synthetic-003",
                      "call-second-network-failure",
                      "cipher-second-network-failure",
                      normalUsage())),
              secondFailure)) {
        OpenAIClient client = client(server);
        try {
          AgentExecutionProfile profile = profile();
          TaskEnvelope task = task(profile, "003");
          AgentModel.Session session =
              new OpenAiResponsesModel(profile, client).open(task);
          session.next(
              new AgentModel.Turn(task, List.of()),
              context(new BigDecimal("0.022000")));

          AgentModelFailure failure =
              assertThrows(
                  AgentModelFailure.class,
                  () ->
                      session.next(
                          new AgentModel.Turn(
                              task,
                              List.of(
                                  new AgentModel.ToolResult(
                                      "capture.read",
                                      "capture://synthetic-003",
                                      "literal synthetic seed"))),
                          new AgentModel.ModelCallContext(
                              25,
                              new BigDecimal("0.021290"),
                              CancellationSignal.never())));

          assertEquals(
              AgentModelFailure.Code.CALL_OUTCOME_UNKNOWN,
              failure.code());
          assertEquals(2, server.requestCount());

          AgentModelFailure repeated =
              assertThrows(
                  AgentModelFailure.class,
                  () ->
                      session.next(
                          new AgentModel.Turn(
                              task,
                              List.of(
                                  new AgentModel.ToolResult(
                                      "capture.read",
                                      "capture://synthetic-003",
                                      "literal synthetic seed"))),
                          context(new BigDecimal("0.021290"))));
          assertEquals(
              AgentModelFailure.Code.EGRESS_NOT_ALLOWED,
              repeated.code());
          assertEquals(2, server.requestCount());
        } finally {
          client.close();
        }
      }
    }
  }

  @Test
  void closedAndTerminalSessionsNeverOpenAnotherSocket() throws Exception {
    try (RecordingServer server =
        new RecordingServer(
            ok(
                firstResponse(
                    "capture://synthetic-003",
                    "call-terminal",
                    "cipher-terminal",
                    normalUsage())),
            ok(
                secondResponse(
                    "capture://synthetic-003", "Terminal draft")))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task = task(profile, "003");
        OpenAiResponsesModel model =
            new OpenAiResponsesModel(profile, client);

        AgentModel.Session closed = model.open(task);
        closed.close();
        closed.close();
        AgentModelFailure closedFailure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    closed.next(
                        new AgentModel.Turn(task, List.of()),
                        context(new BigDecimal("0.022000"))));
        assertEquals(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED,
            closedFailure.code());
        assertEquals(0, server.requestCount());

        AgentModel.Session terminal = model.open(task);
        AgentModel.ModelStep first =
            terminal.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));
        assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        AgentModel.ModelStep second =
            terminal.next(
                new AgentModel.Turn(
                    task,
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            "capture://synthetic-003",
                            "literal synthetic seed"))),
                context(new BigDecimal("0.021290")));
        assertInstanceOf(AgentModel.FinalDraft.class, second.decision());
        AgentModelFailure terminalFailure =
            assertThrows(
                AgentModelFailure.class,
                () ->
                    terminal.next(
                        new AgentModel.Turn(task, List.of()),
                        context(new BigDecimal("0.021290"))));
        assertEquals(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED,
            terminalFailure.code());
        assertEquals(2, server.requestCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void keepsManualReplayStateInsideInterleavedSessions() throws Exception {
    List<String> requests = new ArrayList<>();
    try (RecordingServer server =
        new RecordingServer(
            requests,
            ok(
                firstResponse(
                    "capture://synthetic-a",
                    "call-a",
                    "CIPHER_A_ONLY",
                    normalUsage())),
            ok(
                firstResponse(
                    "capture://synthetic-b",
                    "call-b",
                    "CIPHER_B_ONLY",
                    normalUsage())),
            ok(secondResponse("capture://synthetic-a", "Draft A")),
            ok(secondResponse("capture://synthetic-b", "Draft B")))) {
      OpenAIClient client = client(server);
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope taskA =
            profile.newDraftTask(
                "task-synthetic-a",
                "synthetic-owner",
                "INTENT_A_ONLY",
                "capture://synthetic-a",
                DataClass.PUBLIC);
        TaskEnvelope taskB =
            profile.newDraftTask(
                "task-synthetic-b",
                "synthetic-owner",
                "INTENT_B_ONLY",
                "capture://synthetic-b",
                DataClass.PUBLIC);
        OpenAiResponsesModel model =
            new OpenAiResponsesModel(profile, client);
        AgentModel.Session sessionA = model.open(taskA);
        AgentModel.Session sessionB = model.open(taskB);

        sessionA.next(
            new AgentModel.Turn(taskA, List.of()),
            context(new BigDecimal("0.022000")));
        sessionB.next(
            new AgentModel.Turn(taskB, List.of()),
            context(new BigDecimal("0.022000")));
        sessionA.next(
            new AgentModel.Turn(
                taskA,
                List.of(
                    new AgentModel.ToolResult(
                        "capture.read",
                        "capture://synthetic-a",
                        "CONTENT_A_ONLY"))),
            context(new BigDecimal("0.021290")));
        sessionB.next(
            new AgentModel.Turn(
                taskB,
                List.of(
                    new AgentModel.ToolResult(
                        "capture.read",
                        "capture://synthetic-b",
                        "CONTENT_B_ONLY"))),
            context(new BigDecimal("0.021290")));

        assertEquals(4, requests.size());
        assertContainsOnlySession(
            requests.get(2), "A", "B");
        assertContainsOnlySession(
            requests.get(3), "B", "A");
      } finally {
        client.close();
      }
    }
  }

  private static void assertContainsOnlySession(
      String request, String expected, String foreign) {
    assertTrue(request.contains("INTENT_" + expected + "_ONLY"));
    assertTrue(request.contains("CIPHER_" + expected + "_ONLY"));
    assertTrue(request.contains("call-" + expected.toLowerCase()));
    assertTrue(request.contains("CONTENT_" + expected + "_ONLY"));
    assertFalse(request.contains("INTENT_" + foreign + "_ONLY"));
    assertFalse(request.contains("CIPHER_" + foreign + "_ONLY"));
    assertFalse(request.contains("call-" + foreign.toLowerCase()));
    assertFalse(request.contains("CONTENT_" + foreign + "_ONLY"));
  }

  private static OpenAIClient client(RecordingServer server) {
    return OpenAIOkHttpClient.builder()
        .apiKey("sentinel-loopback-key")
        .baseUrl(server.baseUrl())
        .maxRetries(0)
        .timeout(Duration.ofSeconds(2))
        .build();
  }

  private static AgentModel.ModelCallContext context(BigDecimal budget) {
    return new AgentModel.ModelCallContext(
        1_500, budget, CancellationSignal.never());
  }

  private static TaskEnvelope task(
      AgentExecutionProfile profile, String suffix) {
    return profile.newDraftTask(
        "task-synthetic-" + suffix,
        "synthetic-owner",
        "Create a synthetic public draft",
        "capture://synthetic-" + suffix,
        DataClass.PUBLIC);
  }

  private static AgentExecutionProfile profile() {
    PricingProfile pricing =
        new PricingProfile(
            "openai-gpt-5.6-2026-07-v1",
            "openai.responses",
            "gpt-5.6",
            5_000,
            500,
            30_000);
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
        OpenAiResponsesModel.PROTOCOL_VERSION,
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        new HarnessExperiment("openai-responses-h0", 1),
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v1",
        "environment://sha256:" + "b".repeat(64),
        List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
        DataClass.PUBLIC);
  }

  private static ResponseSpec ok(String body) {
    return new ResponseSpec(200, body, 0);
  }

  private static String normalUsage() {
    return usage(100, 20, 10, 5, 110);
  }

  private static String usage(
      long input,
      long cached,
      long output,
      long reasoning,
      long total) {
    return """
        {
          "input_tokens":%d,
          "input_tokens_details":{"cached_tokens":%d},
          "output_tokens":%d,
          "output_tokens_details":{"reasoning_tokens":%d},
          "total_tokens":%d
        }
        """
        .formatted(input, cached, output, reasoning, total);
  }

  private static String firstResponse(
      String reference, String callId, String ciphertext, String usage) {
    return firstResponse(
        "gpt-5.6-2026-07-15",
        reference,
        callId,
        ciphertext,
        usage);
  }

  private static String firstResponse(
      String model,
      String reference,
      String callId,
      String ciphertext,
      String usage) {
    String arguments =
        ObjectMappers.jsonMapper()
            .createObjectNode()
            .put("reference", reference)
            .toString();
    return firstResponseArguments(
        model, arguments, callId, ciphertext, usage);
  }

  private static String firstResponseArguments(
      String model,
      String arguments,
      String callId,
      String ciphertext,
      String usage) {
    String usageField = usage == null ? "" : ",\"usage\":" + usage;
    String encodedArguments =
        ObjectMappers.jsonMapper().valueToTree(arguments).toString();
    return """
        {
          "id":"resp-first",
          "object":"response",
          "created_at":1785400000,
          "model":"%s",
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
              "encrypted_content":"%s",
              "status":"completed"
            },
            {
              "id":"function-first",
              "type":"function_call",
              "call_id":"%s",
              "name":"capture_read",
              "arguments":%s,
              "status":"completed"
            }
          ]
          %s
        }
        """
        .formatted(model, ciphertext, callId, encodedArguments, usageField);
  }

  private static String secondResponse(String reference, String content) {
    return secondResponse(
        "gpt-5.6-2026-07-15", reference, content);
  }

  private static String secondResponse(
      String model, String reference, String content) {
    return secondResponseText(
        model,
        """
        {"content":"%s","evidenceRefs":["%s"]}
        """
            .formatted(content, reference)
            .strip());
  }

  private static String secondResponseText(String outputText) {
    return secondResponseText("gpt-5.6-2026-07-15", outputText);
  }

  private static String secondResponseText(
      String model, String outputText) {
    String encodedOutputText =
        ObjectMappers.jsonMapper().valueToTree(outputText).toString();
    return """
        {
          "id":"resp-second",
          "object":"response",
          "created_at":1785400001,
          "model":"%s",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":"none",
          "tools":[],
          "output":[
            {
              "id":"message-second",
              "type":"message",
              "role":"assistant",
              "status":"completed",
              "content":[
                {
                  "type":"output_text",
                  "annotations":[],
                  "text":%s
                }
              ]
            }
          ],
          "usage":{
            "input_tokens":120,
            "input_tokens_details":{"cached_tokens":0},
            "output_tokens":20,
            "output_tokens_details":{"reasoning_tokens":8},
            "total_tokens":140
          }
        }
        """
        .formatted(model, encodedOutputText);
  }

  private static String errorResponse() {
    return """
        {
          "error":{
            "message":"RAW_PROVIDER_SECRET",
            "type":"synthetic_error",
            "code":"synthetic_error"
          }
        }
        """;
  }

  private record UsageCase(
      String usageJson, AgentModelFailure.Code expected) {}

  private record HttpFailureCase(
      int status, AgentModelFailure.Code expected) {}

  private record ResponseSpec(int status, String body, long delayMs) {}

  private static final class RecordingServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<String> requests;
    private final List<ResponseSpec> responses;

    private RecordingServer(ResponseSpec... responses) throws IOException {
      this(new ArrayList<>(), responses);
    }

    private RecordingServer(
        List<String> requests, ResponseSpec... responses)
        throws IOException {
      this.requests = requests;
      this.responses = List.of(responses);
      this.executor = Executors.newVirtualThreadPerTaskExecutor();
      this.server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/responses", this::handle);
      server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
      int index;
      synchronized (requests) {
        index = requests.size();
        requests.add(
            new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
      }
      ResponseSpec response =
          responses.get(Math.min(index, responses.size() - 1));
      if (response.status() < 0) {
        exchange.close();
        return;
      }
      if (response.delayMs() > 0) {
        try {
          Thread.sleep(response.delayMs());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          exchange.close();
          return;
        }
      }
      byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      try {
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (var output = exchange.getResponseBody()) {
          output.write(bytes);
        }
      } catch (IOException disconnectedClient) {
        exchange.close();
      }
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/v1";
    }

    private int requestCount() {
      synchronized (requests) {
        return requests.size();
      }
    }

    @Override
    public void close() {
      server.stop(0);
      executor.close();
    }
  }
}
