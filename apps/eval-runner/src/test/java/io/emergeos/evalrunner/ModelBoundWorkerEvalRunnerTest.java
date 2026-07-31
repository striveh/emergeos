package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelBoundWorkerEvalRunnerTest {

  @Test
  void catalogGraphProducesTheExactFrozenParentAndChildTasks() {
    Pack008GraphExecutionPermit permit = armedPermit();
    AtomicInteger consumeReceipts = new AtomicInteger();

    ModelBoundWorkerEvalRunner.Observation observed =
        new ModelBoundWorkerEvalRunner()
            .run(
                catalogSpec(),
                catalogChildModel(),
                permit,
                consumeReceipts::incrementAndGet,
                () -> 0L);

    assertEquals(
        Pack008GraphExecutionPermit.State.CONSUMED, permit.state());
    assertTrue(permit.parentAuthorized());
    assertTrue(permit.childAuthorized());
    assertEquals(1, consumeReceipts.get());
    assertEquals(
        Pack008WorkerEvalCatalog.EXPECTED_PARENT_TASK_HASH,
        IntegrityHashes.taskHash(observed.parent().run().task()));
    assertEquals(
        Pack008WorkerEvalCatalog.EXPECTED_CHILD_TASK_HASH,
        IntegrityHashes.taskHash(observed.child().task()));
    assertEquals(
        Pack008WorkerEvalCatalog.INTENT,
        observed.child().task().intent());
    assertEquals(
        Pack008WorkerEvalCatalog
            .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT,
        observed
            .parent()
            .run()
            .bundle()
            .componentVersions()
            .get("conductor-decision-surface-fingerprint"));
  }

  @Test
  void loopbackRunsOneRealModelChildUnderTheDeterministicConductor()
      throws Exception {
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(firstResponse(), secondResponse())) {
      ModelBoundReadOnlyWorkerExecutionProfile worker =
          Pack008WorkerEvalCatalog.workerProfile();
      OpenAIClient client = client(server);
      try {
        AtomicInteger providerIntents = new AtomicInteger();
        OpenAiResponsesModel childModel =
            new OpenAiResponsesModel(
                worker, client, providerIntents::incrementAndGet);
        Pack008GraphExecutionPermit permit = armedPermit();
        AtomicInteger consumeReceipts = new AtomicInteger();

        ModelBoundWorkerEvalRunner.Observation observed =
            new ModelBoundWorkerEvalRunner()
                .run(
                    catalogSpec(),
                    childModel,
                    permit,
                    consumeReceipts::incrementAndGet,
                    () -> 0L);

        assertEquals(RunStatus.SUCCEEDED, observed.parent().result().status());
        assertEquals(RunStatus.SUCCEEDED, observed.child().result().status());
        assertNotNull(observed.parent().artifact());
        assertNotNull(observed.workerResult());
        assertEquals(
            observed.workerResult().contentHash(),
            observed
                .parent()
                .artifact()
                .current()
                .contentHash());
        assertEquals(1, observed.parentStarts());
        assertEquals(1, observed.childStarts());
        assertEquals(1, observed.parentCompletions());
        assertEquals(1, observed.childCompletions());
        assertEquals(1, observed.artifactCommits());
        assertEquals(
            Pack008GraphExecutionPermit.State.CONSUMED,
            permit.state());
        assertEquals(1, consumeReceipts.get());
        assertEquals(2, providerIntents.get());
        assertEquals(2, server.requestCount());

        TaskEnvelope parent = observed.parent().run().task();
        TaskEnvelope child = observed.child().task();
        assertEquals("1.0", parent.schemaVersion());
        assertNull(parent.modelProvider());
        assertEquals(List.of(), parent.requiredTools());
        assertEquals("agent-tools-none-v1", parent.toolRegistryVersion());
        assertEquals(DataClass.PUBLIC, parent.dataClass());
        assertEquals(worker.budgetUsd(), parent.budgetUsd());
        assertFalse(
            observed
                .parent()
                .run()
                .bundle()
                .componentVersions()
                .containsKey("model-adapter"));
        assertEquals(
            worker.fingerprint(),
            observed
                .parent()
                .run()
                .bundle()
                .componentVersions()
                .get("worker-profile-fingerprint"));

        assertEquals("1.1", child.schemaVersion());
        assertEquals(parent.id(), child.parentId());
        assertEquals(List.of("capture.read"), child.requiredTools());
        assertEquals("agent-tools-v2", child.toolRegistryVersion());
        assertEquals("openai.responses", child.modelProvider());
        assertEquals(
            OpenAiResponsesModel.PROTOCOL_VERSION,
            observed
                .child()
                .bundle()
                .componentVersions()
                .get("model-adapter"));
        assertEquals(
            worker.experiment(), observed.child().bundle().experiment());
        assertEquals(
            new BigDecimal("0.000275"),
            observed.child().result().costUsd());
        assertEquals(
            observed.child().result().costUsd(),
            observed.parent().result().costUsd());
        assertEquals(
            observed.child().result().tokenCount(),
            observed.parent().result().tokenCount());
      } finally {
        client.close();
      }
    }
  }

  @Test
  void malformedChildFinalProducesNoWorkerResultOrParentArtifact()
      throws Exception {
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            firstResponse(), malformedSecondResponse())) {
      ModelBoundReadOnlyWorkerExecutionProfile worker =
          Pack008WorkerEvalCatalog.workerProfile();
      OpenAIClient client = client(server);
      try {
        Pack008GraphExecutionPermit permit = armedPermit();

        ModelBoundWorkerEvalRunner.Observation observed =
            new ModelBoundWorkerEvalRunner()
                .run(
                    catalogSpec(),
                    new OpenAiResponsesModel(worker, client),
                    permit,
                    () -> {},
                    () -> 0L);

        assertEquals(RunStatus.FAILED, observed.child().result().status());
        assertEquals(
            "MODEL_RESPONSE_MALFORMED",
            observed.child().result().failureReason());
        assertEquals(RunStatus.FAILED, observed.parent().result().status());
        assertEquals(
            "HANDOFF_CHILD_FAILED",
            observed.parent().result().failureReason());
        assertNull(observed.workerResult());
        assertNull(observed.parent().artifact());
        assertEquals(0, observed.artifactCommits());
        assertEquals(2, server.requestCount());
        assertTrue(observed.child().result().tokenCount() > 0);
      } finally {
        client.close();
      }
    }
  }

  @Test
  void rejectsCaptureOrRunIdentityDriftBeforeTheFrozenGraphCanRun() {
    assertSpecRejected(
        Pack008WorkerEvalCatalog.CONTENT + " drift",
        Pack008WorkerEvalCatalog.PARENT_RUN_ID,
        Pack008WorkerEvalCatalog.ARTIFACT_ID);
    assertSpecRejected(
        Pack008WorkerEvalCatalog.CONTENT,
        "run-openai-worker-parent-008-drift",
        Pack008WorkerEvalCatalog.ARTIFACT_ID);
    assertSpecRejected(
        Pack008WorkerEvalCatalog.CONTENT,
        Pack008WorkerEvalCatalog.PARENT_RUN_ID,
        "art-openai-worker-008-drift");
  }

  private static ModelBoundWorkerEvalRunner.Spec catalogSpec() {
    return new ModelBoundWorkerEvalRunner.Spec(
        Pack008WorkerEvalCatalog.PRINCIPAL_ID,
        Pack008WorkerEvalCatalog.CAPTURE_ID,
        Pack008WorkerEvalCatalog.CLIENT_NONCE,
        Pack008WorkerEvalCatalog.CONTENT,
        Pack008WorkerEvalCatalog.SOURCE_REF,
        Pack008WorkerEvalCatalog.INTENT,
        Pack008WorkerEvalCatalog.PARENT_RUN_ID,
        Pack008WorkerEvalCatalog.CHILD_RUN_ID,
        Pack008WorkerEvalCatalog.PARENT_TASK_ID,
        Pack008WorkerEvalCatalog.CHILD_TASK_ID,
        Pack008WorkerEvalCatalog.ARTIFACT_ID,
        Pack008WorkerEvalCatalog.STARTED_AT,
        Pack008WorkerEvalCatalog.workerProfile());
  }

  private static void assertSpecRejected(
      String content, String parentRunId, String artifactId) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelBoundWorkerEvalRunner.Spec(
                Pack008WorkerEvalCatalog.PRINCIPAL_ID,
                Pack008WorkerEvalCatalog.CAPTURE_ID,
                Pack008WorkerEvalCatalog.CLIENT_NONCE,
                content,
                Pack008WorkerEvalCatalog.SOURCE_REF,
                Pack008WorkerEvalCatalog.INTENT,
                parentRunId,
                Pack008WorkerEvalCatalog.CHILD_RUN_ID,
                Pack008WorkerEvalCatalog.PARENT_TASK_ID,
                Pack008WorkerEvalCatalog.CHILD_TASK_ID,
                artifactId,
                Pack008WorkerEvalCatalog.STARTED_AT,
                Pack008WorkerEvalCatalog.workerProfile()));
  }

  private static AgentModel catalogChildModel() {
    return new AgentModel() {
      @Override
      public Session open(TaskEnvelope task) {
        return (turn, context) -> {
          Decision decision;
          if (turn.toolResults().isEmpty()) {
            decision =
                new ToolCall(
                    "capture.read",
                    ToolArguments.forReference(
                        turn.task().inputRefs().getFirst()));
          } else {
            AgentModel.ToolResult source =
                turn.toolResults().getFirst();
            decision =
                new FinalDraft(
                    "已根据完全虚构的公开素材形成可追溯短文。",
                    List.of(source.reference()));
          }
          return new ModelStep(
              decision,
              Pack008WorkerEvalCatalog.workerProfile().modelRequested(),
              ModelUsage.zero());
        };
      }

      @Override
      public String executionProfileId() {
        return Pack008WorkerEvalCatalog.workerProfile().id();
      }

      @Override
      public String executionProfileFingerprint() {
        return Pack008WorkerEvalCatalog.workerProfile().fingerprint();
      }
    };
  }

  private static Pack008GraphExecutionPermit armedPermit() {
    Pack008GraphExecutionPermit permit =
        new Pack008GraphExecutionPermit(() -> 0);
    permit.arm(
        Pack008WorkerEvalCatalog.EXPECTED_ATTEMPT_ID,
        Duration.ofSeconds(30));
    return permit;
  }

  private static OpenAIClient client(LoopbackResponsesServer server) {
    return OpenAIOkHttpClient.builder()
        .apiKey("sentinel-loopback-key")
        .baseUrl(server.baseUrl())
        .maxRetries(0)
        .timeout(Duration.ofSeconds(2))
        .build();
  }

  private static String firstResponse() {
    return """
        {
          "id":"resp-pack008-first",
          "object":"response",
          "created_at":1785400000,
          "model":"gpt-5.4-mini-2026-03-17",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":{"type":"function","name":"capture_read"},
          "tools":[],
          "output":[
            {
              "id":"reasoning-pack008-first",
              "type":"reasoning",
              "summary":[],
              "encrypted_content":"loopback-pack008-first",
              "status":"completed"
            },
            {
              "id":"function-pack008-first",
              "type":"function_call",
              "call_id":"call-pack008",
              "name":"capture_read",
              "arguments":"{\\"reference\\":\\"capture://capture-openai-worker-008\\"}",
              "status":"completed"
            }
          ],
          "usage":{
            "input_tokens":100,
            "input_tokens_details":{"cached_tokens":20},
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
          "id":"resp-pack008-second",
          "object":"response",
          "created_at":1785400001,
          "model":"gpt-5.4-mini-2026-03-17",
          "status":"completed",
          "service_tier":"default",
          "parallel_tool_calls":false,
          "tool_choice":"none",
          "tools":[],
          "output":[
            {
              "id":"reasoning-pack008-second",
              "type":"reasoning",
              "summary":[],
              "encrypted_content":"loopback-pack008-second",
              "status":"completed"
            },
            {
              "id":"message-pack008-second",
              "type":"message",
              "role":"assistant",
              "status":"completed",
              "content":[
                {
                  "type":"output_text",
                  "text":"{\\"content\\":\\"一篇完全虚构、可追溯的短文。\\",\\"evidenceRefs\\":[\\"capture://capture-openai-worker-008\\"]}",
                  "annotations":[],
                  "logprobs":[]
                }
              ]
            }
          ],
          "usage":{
            "input_tokens":200,
            "input_tokens_details":{"cached_tokens":40},
            "output_tokens":10,
            "output_tokens_details":{"reasoning_tokens":5},
            "total_tokens":210
          }
        }
        """;
  }

  private static String malformedSecondResponse() {
    return secondResponse().replace(
        "\\\"evidenceRefs\\\":[\\\"capture://capture-openai-worker-008\\\"]",
        "\\\"evidenceRefs\\\":[\\\"capture://foreign\\\"]");
  }

  private static final class LoopbackResponsesServer
      implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final Deque<String> responses;
    private final AtomicInteger requests = new AtomicInteger();

    private LoopbackResponsesServer(String... responses)
        throws IOException {
      this.responses = new ArrayDeque<>(List.of(responses));
      server =
          HttpServer.create(
              new InetSocketAddress(
                  InetAddress.getLoopbackAddress(), 0),
              0);
      executor = Executors.newSingleThreadExecutor();
      server.setExecutor(executor);
      server.createContext("/responses", this::respond);
      server.start();
    }

    private void respond(HttpExchange exchange) throws IOException {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      String body = responses.pollFirst();
      if (body == null) {
        body = "{}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange
          .getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/";
    }

    private int requestCount() {
      return requests.get();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
