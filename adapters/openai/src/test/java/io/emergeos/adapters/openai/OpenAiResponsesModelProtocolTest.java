package io.emergeos.adapters.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.adapters.agentloop.AgentModel;
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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OpenAiResponsesModelProtocolTest {

  @Test
  void performsTwoStatelessStrictResponsesCallsWithManualItemReplay()
      throws Exception {
    List<String> requests = new ArrayList<>();
    try (LoopbackResponsesServer server =
            new LoopbackResponsesServer(
                requests, firstResponse(), secondResponse())) {
      OpenAIClient client =
          OpenAIOkHttpClient.builder()
              .apiKey("sentinel-loopback-key")
              .baseUrl(server.baseUrl())
              .maxRetries(0)
              .timeout(Duration.ofSeconds(2))
              .build();
      try {
        AgentExecutionProfile profile = profile();
        TaskEnvelope task =
            profile.newDraftTask(
                "task-synthetic-003",
                "synthetic-owner",
                "Create a synthetic public draft",
                "capture://synthetic-003",
                DataClass.PUBLIC);
        OpenAiResponsesModel model = new OpenAiResponsesModel(profile, client);
        AgentModel.Session session = model.open(task);

        assertEquals(0, requests.size());
        AgentModel.ModelStep first =
            session.next(
                new AgentModel.Turn(task, List.of()),
                context(new BigDecimal("0.022000")));
        AgentModel.ToolCall toolCall =
            assertInstanceOf(AgentModel.ToolCall.class, first.decision());
        assertEquals("capture.read", toolCall.toolName());
        assertEquals("capture://synthetic-003", toolCall.reference());
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
        "agent-tools-v1",
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
            "input_tokens_details":{"cached_tokens":0},
            "output_tokens":20,
            "output_tokens_details":{"reasoning_tokens":8},
            "total_tokens":140
          }
        }
        """;
  }

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
