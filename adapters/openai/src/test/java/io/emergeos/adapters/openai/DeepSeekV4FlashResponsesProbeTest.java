package io.emergeos.adapters.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DeepSeekV4FlashResponsesProbeTest {

  private static final ObjectMapper JSON = ObjectMappers.jsonMapper();
  private static final String SENTINEL_KEY =
      "sentinel-deepseek-key-never-reported";
  private static final String RESPONSE_ID =
      "resp-deepseek-private-id-never-reported";

  @Test
  void officialResponsesCompatibilityShapeProducesOneBoundedRedactedReceipt()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<String> requestPaths = new ArrayList<>();
    List<Boolean> bearerHeaders = new ArrayList<>();
    try (LoopbackDeepSeekServer server =
        new LoopbackDeepSeekServer(
            requests,
            requestPaths,
            bearerHeaders,
            responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null))) {
      DeepSeekV4FlashResponsesProbe.Receipt receipt =
          DeepSeekV4FlashResponsesProbe.executeForTest(
              SENTINEL_KEY, server.baseUrl());

      assertEquals(1, requests.size());
      assertEquals(List.of("/responses"), requestPaths);
      assertEquals(List.of(true), bearerHeaders);
      JsonNode request = JSON.readTree(requests.getFirst());
      Set<String> requestFields = new TreeSet<>();
      request.fieldNames().forEachRemaining(requestFields::add);
      assertEquals(
          Set.of(
              "input",
              "instructions",
              "max_output_tokens",
              "model",
              "reasoning",
              "store",
              "text",
              "tool_choice",
              "tools"),
          requestFields);
      assertEquals("deepseek-v4-flash", request.path("model").asText());
      assertEquals(64, request.path("max_output_tokens").asInt());
      assertFalse(request.path("store").asBoolean(true));
      assertFalse(request.has("service_tier"));
      assertFalse(request.has("include"));
      assertFalse(request.has("parallel_tool_calls"));
      assertEquals("none", request.path("reasoning").path("effort").asText());
      assertEquals("none", request.path("tool_choice").asText());
      assertEquals(
          "json_schema",
          request.path("text").path("format").path("type").asText());
      assertTrue(
          request.path("text").path("format").path("strict").asBoolean());
      assertEquals(
          "object",
          request
              .path("text")
              .path("format")
              .path("schema")
              .path("type")
              .asText());
      assertEquals(
          "string",
          request
              .path("text")
              .path("format")
              .path("schema")
              .path("properties")
              .path("status")
              .path("type")
              .asText());
      assertEquals(
          List.of("status"),
          JSON.convertValue(
              request
                  .path("text")
                  .path("format")
                  .path("schema")
                  .path("required"),
              JSON.getTypeFactory()
                  .constructCollectionType(List.class, String.class)));
      assertFalse(
          request
              .path("text")
              .path("format")
              .path("schema")
              .path("additionalProperties")
              .asBoolean(true));

      assertTrue(receipt.modelObservedHash().matches("[a-f0-9]{64}"));
      assertEquals(100, receipt.inputTokens());
      assertEquals(20, receipt.cachedInputTokens());
      assertEquals(10, receipt.outputTokens());
      assertEquals(0, receipt.reasoningOutputTokens());
      assertEquals(110, receipt.totalTokens());
      assertEquals(
          "deepseek-v4-flash-public-list-2026-08-11",
          receipt.listPriceProfileId());
      assertEquals(
          "0.001",
          receipt.acceptedListPriceEstimateCeilingUsd().toPlainString());
      assertEquals("0.000014056", receipt.listPriceEstimateUsd().toPlainString());
      assertTrue(receipt.requestHash().matches("[a-f0-9]{64}"));
      assertTrue(receipt.responseHash().matches("[a-f0-9]{64}"));

      String safeReceipt = receipt.toString();
      assertFalse(safeReceipt.contains(SENTINEL_KEY));
      assertFalse(safeReceipt.contains(RESPONSE_ID));
      assertFalse(safeReceipt.contains("DEEPSEEK_COMPATIBLE"));
    }
  }

  @Test
  void rejectsUnsupportedResponseMetadataWithOneRedactedAttempt()
      throws Exception {
    assertRejectedResponse(
        responseBody(
            "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
            false,
            false,
            null),
        "RESPONSE_PARALLEL_TOOLS_MISMATCH");
    assertRejectedResponse(
        responseBody(
            "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
            true,
            false,
            "default"),
        "RESPONSE_SERVICE_TIER_MISMATCH");
    assertRejectedResponse(
        responseBody(
            "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
            true,
            true,
            null),
        "RESPONSE_STORE_MISMATCH");
    assertRejectedResponse(
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replaceFirst(
                "\\\"status\\\":\\\"completed\\\",",
                "\\\"status\\\":\\\"incomplete\\\","),
        "RESPONSE_STATUS_MISMATCH");
    assertRejectedResponse(
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replace(
                "\"previous_response_id\":null",
                "\"previous_response_id\":\"PRIVATE_PREVIOUS_ID\""),
        "RESPONSE_PREVIOUS_ID_MISMATCH");
  }

  @Test
  void rejectsDuplicateOrTrailingPayloadWithoutLeakingRawText()
      throws Exception {
    assertRejectedResponse(
        responseBody(
            "{\"status\":\"DEEPSEEK_COMPATIBLE\","
                + "\"status\":\"PRIVATE_DUPLICATE\"}",
            true,
            false,
            null),
        "RESPONSE_INVALID");
    assertRejectedResponse(
        responseBody(
            "{\"status\":\"DEEPSEEK_COMPATIBLE\"} "
                + "{\"private\":\"TRAILING\"}",
            true,
            false,
            null),
        "RESPONSE_INVALID");
  }

  @Test
  void rejectsReasoningItemsAndNonZeroReasoningUsageWithoutLeakingCot()
      throws Exception {
    String reasoningItem =
        """
        {
          "id":"reasoning-private",
          "type":"reasoning",
          "summary":[],
          "content":[
            {
              "type":"reasoning_text",
              "text":"PRIVATE_COT_SENTINEL"
            }
          ],
          "status":"completed"
        },
        """;
    assertRejectedResponse(
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replace("\"output\":[", "\"output\":[" + reasoningItem),
        "RESPONSE_OUTPUT_MISMATCH");
    assertRejectedResponse(
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replace(
                "\"reasoning_tokens\":0", "\"reasoning_tokens\":1"),
        "RESPONSE_USAGE_MISMATCH");
  }

  @Test
  void rejectsReceiptWhenPostResponseEstimateExceedsAcceptedCeiling()
      throws Exception {
    String oversizedUsage =
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replace("\"input_tokens\":100", "\"input_tokens\":10000")
            .replace("\"total_tokens\":110", "\"total_tokens\":10010");
    assertRejectedResponse(
        oversizedUsage, "LIST_PRICE_ESTIMATE_CEILING_EXCEEDED");
  }

  @Test
  void hashesAllowedObservedModelSuffixInsteadOfReportingIt()
      throws Exception {
    String privateModel =
        "deepseek-v4-flash-private-model-suffix";
    String response =
        responseBody(
                "{\"status\":\"DEEPSEEK_COMPATIBLE\"}",
                true,
                false,
                null)
            .replace("deepseek-v4-flash", privateModel);
    List<String> requests = new ArrayList<>();
    try (LoopbackDeepSeekServer server =
        new LoopbackDeepSeekServer(
            requests,
            new ArrayList<>(),
            new ArrayList<>(),
            response)) {
      DeepSeekV4FlashResponsesProbe.Receipt receipt =
          DeepSeekV4FlashResponsesProbe.executeForTest(
              SENTINEL_KEY, server.baseUrl());
      assertEquals(1, requests.size());
      assertTrue(receipt.modelObservedHash().matches("[a-f0-9]{64}"));
      assertFalse(receipt.toString().contains(privateModel));
      assertFalse(receipt.toString().contains("private-model-suffix"));
    }
  }

  @Test
  void rejectsNonLoopbackTestEndpointBeforeClientOrNetworkUse() {
    DeepSeekV4FlashResponsesProbe.ProbeRejected failure =
        assertThrows(
            DeepSeekV4FlashResponsesProbe.ProbeRejected.class,
            () ->
                DeepSeekV4FlashResponsesProbe.executeForTest(
                    SENTINEL_KEY, "https://api.deepseek.com"));
    assertEquals("LOOPBACK_ENDPOINT_INVALID", failure.getMessage());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains(SENTINEL_KEY));
  }

  @Test
  void doesNotFollowProviderRedirectsOrSendASecondRequest()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    List<Boolean> bearerHeaders = new ArrayList<>();
    try (LoopbackDeepSeekServer server =
        new LoopbackDeepSeekServer(
            requests,
            paths,
            bearerHeaders,
            "{}",
            307,
            "/redirected")) {
      DeepSeekV4FlashResponsesProbe.ProbeRejected failure =
          assertThrows(
              DeepSeekV4FlashResponsesProbe.ProbeRejected.class,
              () ->
                  DeepSeekV4FlashResponsesProbe.executeForTest(
                      SENTINEL_KEY, server.baseUrl()));
      assertEquals(
          "LOOPBACK_RESPONSES_PROBE_FAILED", failure.getMessage());
      assertNull(failure.getCause());
      assertEquals(1, requests.size());
      assertEquals(List.of("/responses"), paths);
      assertEquals(List.of(true), bearerHeaders);
    }
  }

  @Test
  void doesNotRetryRateLimitOrProviderUnavailableResponses()
      throws Exception {
    for (int statusCode : List.of(429, 503)) {
      List<String> requests = new ArrayList<>();
      List<String> paths = new ArrayList<>();
      List<Boolean> bearerHeaders = new ArrayList<>();
      try (LoopbackDeepSeekServer server =
          new LoopbackDeepSeekServer(
              requests,
              paths,
              bearerHeaders,
              "{\"private\":\"PRIVATE_PROVIDER_ERROR_SENTINEL\"}",
              statusCode,
              null)) {
        DeepSeekV4FlashResponsesProbe.ProbeRejected failure =
            assertThrows(
                DeepSeekV4FlashResponsesProbe.ProbeRejected.class,
                () ->
                    DeepSeekV4FlashResponsesProbe.executeForTest(
                        SENTINEL_KEY, server.baseUrl()));
        assertEquals(
            "LOOPBACK_RESPONSES_PROBE_FAILED", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(SENTINEL_KEY));
        assertFalse(
            failure.toString().contains("PRIVATE_PROVIDER_ERROR_SENTINEL"));
        assertEquals(1, requests.size());
        assertEquals(List.of("/responses"), paths);
        assertEquals(List.of(true), bearerHeaders);
      }
    }
  }

  private static void assertRejectedResponse(
      String responseBody, String expectedCode) throws Exception {
    List<String> requests = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    List<Boolean> bearerHeaders = new ArrayList<>();
    try (LoopbackDeepSeekServer server =
        new LoopbackDeepSeekServer(
            requests, paths, bearerHeaders, responseBody)) {
      DeepSeekV4FlashResponsesProbe.ProbeRejected failure =
          assertThrows(
              DeepSeekV4FlashResponsesProbe.ProbeRejected.class,
              () ->
                  DeepSeekV4FlashResponsesProbe.executeForTest(
                      SENTINEL_KEY, server.baseUrl()));
      assertEquals(expectedCode, failure.getMessage());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains(SENTINEL_KEY));
      assertFalse(failure.toString().contains("PRIVATE_DUPLICATE"));
      assertFalse(failure.toString().contains("TRAILING"));
      assertFalse(failure.toString().contains("PRIVATE_COT_SENTINEL"));
      assertFalse(failure.toString().contains("PRIVATE_PREVIOUS_ID"));
      assertEquals(1, requests.size());
      assertEquals(List.of("/responses"), paths);
      assertEquals(List.of(true), bearerHeaders);
    }
  }

  private static final class LoopbackDeepSeekServer
      implements AutoCloseable {

    private final HttpServer server;

    private LoopbackDeepSeekServer(
        List<String> requests,
        List<String> requestPaths,
        List<Boolean> bearerHeaders,
        String responseBody)
        throws IOException {
      this(
          requests,
          requestPaths,
          bearerHeaders,
          responseBody,
          200,
          null);
    }

    private LoopbackDeepSeekServer(
        List<String> requests,
        List<String> requestPaths,
        List<Boolean> bearerHeaders,
        String responseBody,
        int statusCode,
        String location)
        throws IOException {
      server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/",
          exchange -> {
            synchronized (requests) {
              requestPaths.add(exchange.getRequestURI().getPath());
              requests.add(
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      StandardCharsets.UTF_8));
              String authorization =
                  exchange.getRequestHeaders().getFirst("Authorization");
              bearerHeaders.add(
                  ("Bearer " + SENTINEL_KEY).equals(authorization));
            }
            respond(
                exchange,
                responseBody,
                statusCode,
                location);
          });
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private static void respond(
        HttpExchange exchange,
        String responseBody,
        int statusCode,
        String location)
        throws IOException {
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      if (location != null) {
        exchange.getResponseHeaders().set("Location", location);
      }
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }

  private static String responseBody(
      String payload,
      boolean parallelToolCalls,
      boolean store,
      String serviceTier)
      throws IOException {
    String serviceTierField =
        serviceTier == null
            ? ""
            : "\"service_tier\":"
                + JSON.writeValueAsString(serviceTier)
                + ",";
    return """
        {
          "id":"%s",
          "object":"response",
          "created_at":1786400000,
          "model":"deepseek-v4-flash",
          "status":"completed",
          %s
          "parallel_tool_calls":%s,
          "store":%s,
          "previous_response_id":null,
          "output":[
            {
              "id":"message-deepseek-probe",
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
            "input_tokens":100,
            "input_tokens_details":{"cached_tokens":20},
            "output_tokens":10,
              "output_tokens_details":{"reasoning_tokens":0},
            "total_tokens":110
          }
        }
        """
        .formatted(
            RESPONSE_ID,
            serviceTierField,
            parallelToolCalls,
            store,
            JSON.writeValueAsString(payload));
  }
}
