package io.emergeos.adapters.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.ToolChoiceOptions;
import io.emergeos.contracts.IntegrityHashes;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Default-off one-request compatibility probe for DeepSeek's Responses API.
 *
 * <p>This is not a Pack010 execution route and does not expose response text,
 * response identifiers, reasoning content or credentials. The shipping apps
 * have no consumer. A live caller must provide the credential out of band and
 * receives only hashes, bounded usage and a list-price estimate.
 * The estimate ceiling is checked after the provider response; it is not a
 * provider-enforced budget or invoice cap.
 */
final class DeepSeekV4FlashResponsesProbe {

  static final String MODEL = "deepseek-v4-flash";
  static final String PRODUCTION_BASE_URL = "https://api.deepseek.com";
  static final int MAX_OUTPUT_TOKENS = 64;
  static final int MAX_REQUEST_BODY_BYTES = 4_096;
  static final String LIST_PRICE_PROFILE_ID =
      "deepseek-v4-flash-public-list-2026-08-11";
  static final BigDecimal MAX_ACCEPTED_LIST_PRICE_ESTIMATE_USD =
      new BigDecimal("0.001");

  private static final Duration LIVE_TIMEOUT = Duration.ofSeconds(30);
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final BigDecimal ONE_MILLION =
      BigDecimal.valueOf(1_000_000L);
  private static final BigDecimal CACHE_HIT_USD_PER_MILLION =
      new BigDecimal("0.0028");
  private static final BigDecimal CACHE_MISS_USD_PER_MILLION =
      new BigDecimal("0.14");
  private static final BigDecimal OUTPUT_USD_PER_MILLION =
      new BigDecimal("0.28");
  private static final String INSTRUCTIONS =
      "Return only the requested JSON object. Do not call tools.";
  private static final String INPUT =
      "Return status DEEPSEEK_COMPATIBLE.";

  private DeepSeekV4FlashResponsesProbe() {}

  static Receipt execute(String apiKey) {
    return executeBounded(
        apiKey, PRODUCTION_BASE_URL, LIVE_TIMEOUT, false);
  }

  static Receipt executeForTest(String apiKey, String baseUrl) {
    requireLoopback(baseUrl);
    return executeBounded(
        apiKey, baseUrl, Duration.ofSeconds(2), true);
  }

  private static Receipt executeBounded(
      String apiKey,
      String baseUrl,
      Duration timeout,
      boolean loopback) {
    try (ReviewedOpenAiClient client =
        ReviewedOpenAiClient.defaultCodecNoRetryNoRedirect(
            apiKey,
            baseUrl,
            Proxy.NO_PROXY,
            timeout,
            LogLevel.OFF)) {
      ResponseCreateParams request = request();
      int requestBytes = client.requestBodyLength(request._body());
      if (requestBytes > MAX_REQUEST_BODY_BYTES) {
        throw new ProbeRejected("REQUEST_BOUND_EXCEEDED");
      }
      String requestHash = client.requestBodyHash(request._body());
      ReviewedOpenAiClient.ReviewedResponse reviewed =
          client.createReviewedResponse(
              request,
              RequestOptions.builder().timeout(timeout).build());
      return receipt(requestHash, requestBytes, reviewed);
    } catch (ProbeRejected rejected) {
      throw rejected;
    } catch (RuntimeException failure) {
      throw new ProbeRejected(
          loopback
              ? "LOOPBACK_RESPONSES_PROBE_FAILED"
              : "DEEPSEEK_RESPONSES_PROBE_FAILED");
    }
  }

  private static ResponseCreateParams request() {
    StructuredResponseCreateParams.Builder<ProbePayload> builder =
        ResponseCreateParams.builder()
            .model(MODEL)
            .instructions(INSTRUCTIONS)
            .input(INPUT)
            .store(false)
            .maxOutputTokens(MAX_OUTPUT_TOKENS)
            .reasoning(
                Reasoning.builder()
                    .effort(ReasoningEffort.NONE)
                    .build())
            .tools(List.of())
            .toolChoice(ToolChoiceOptions.NONE)
            .text(ProbePayload.class);
    return builder.build().rawParams();
  }

  private static Receipt receipt(
      String requestHash,
      int requestBytes,
      ReviewedOpenAiClient.ReviewedResponse reviewed) {
    try {
      Response response = reviewed.response();
      if (response.id().isBlank()
          || response.createdAt() < 0
          || !JsonValue.from("response").equals(response._object_())) {
        throw new ProbeRejected("RESPONSE_ENVELOPE_MISMATCH");
      }
      if (!JsonValue.from(false)
          .equals(response._additionalProperties().get("store"))) {
        throw new ProbeRejected("RESPONSE_STORE_MISMATCH");
      }
      if (response.status().filter(ResponseStatus.COMPLETED::equals).isEmpty()) {
        throw new ProbeRejected("RESPONSE_STATUS_MISMATCH");
      }
      if (!response.parallelToolCalls()) {
        throw new ProbeRejected("RESPONSE_PARALLEL_TOOLS_MISMATCH");
      }
      if (response.serviceTier().isPresent()) {
        throw new ProbeRejected("RESPONSE_SERVICE_TIER_MISMATCH");
      }
      if (response.error().isPresent()) {
        throw new ProbeRejected("RESPONSE_ERROR_PRESENT");
      }
      if (response.incompleteDetails().isPresent()) {
        throw new ProbeRejected("RESPONSE_INCOMPLETE");
      }
      if (response.previousResponseId().isPresent()
          || !response._previousResponseId().isNull()) {
        throw new ProbeRejected("RESPONSE_PREVIOUS_ID_MISMATCH");
      }

      ResponseOutputMessage message = null;
      for (ResponseOutputItem item : response.output()) {
        if (item.isMessage() && message == null) {
          message = item.asMessage();
          message.validate();
        } else {
          throw new ProbeRejected("RESPONSE_OUTPUT_MISMATCH");
        }
      }
      if (message == null
          || !ResponseOutputMessage.Status.COMPLETED.equals(message.status())
          || message.content().size() != 1
          || !message.content().getFirst().isOutputText()) {
        throw new ProbeRejected("RESPONSE_OUTPUT_MISMATCH");
      }
      JsonNode payload =
          STRICT_JSON.readTree(
              message.content().getFirst().asOutputText().text());
      if (!payload.isObject()
          || payload.size() != 1
          || !"DEEPSEEK_COMPATIBLE".equals(
              payload.path("status").asText(null))) {
        throw new ProbeRejected("RESPONSE_PAYLOAD_MISMATCH");
      }

      ResponseUsage usage =
          response
              .usage()
              .orElseThrow(
                  () -> new ProbeRejected("RESPONSE_USAGE_MISSING"));
      long inputTokens = usage.inputTokens();
      long cachedInputTokens = usage.inputTokensDetails().cachedTokens();
      long outputTokens = usage.outputTokens();
      long reasoningOutputTokens =
          usage.outputTokensDetails().reasoningTokens();
      long totalTokens = usage.totalTokens();
      if (inputTokens < 0
          || cachedInputTokens < 0
          || outputTokens < 0
          || outputTokens > MAX_OUTPUT_TOKENS
          || reasoningOutputTokens != 0
          || cachedInputTokens > inputTokens
          || reasoningOutputTokens > outputTokens
          || Math.addExact(inputTokens, outputTokens) != totalTokens) {
        throw new ProbeRejected("RESPONSE_USAGE_MISMATCH");
      }

      return new Receipt(
          observedModelHash(response.model()),
          requestHash,
          reviewed.responseHash(),
          requestBytes,
          inputTokens,
          cachedInputTokens,
          outputTokens,
          reasoningOutputTokens,
          totalTokens,
          LIST_PRICE_PROFILE_ID,
          MAX_ACCEPTED_LIST_PRICE_ESTIMATE_USD,
          requireWithinAcceptedEstimateCeiling(
              listPriceEstimate(
                  inputTokens, cachedInputTokens, outputTokens)));
    } catch (ProbeRejected rejected) {
      throw rejected;
    } catch (Exception invalidResponse) {
      throw new ProbeRejected("RESPONSE_INVALID");
    }
  }

  private static String observedModelHash(ResponsesModel model) {
    String resolved =
        model.string().orElseGet(
            () ->
                model.chat().map(value -> value.asString()).orElseGet(
                    () -> model.only().orElseThrow().asString()));
    if (!resolved.matches(
        "deepseek-v4-flash(?:-[A-Za-z0-9][A-Za-z0-9._-]{0,63})?")) {
      throw new ProbeRejected("RESPONSE_MODEL_MISMATCH");
    }
    return IntegrityHashes.utf8ContentHash(resolved);
  }

  private static BigDecimal listPriceEstimate(
      long inputTokens,
      long cachedInputTokens,
      long outputTokens) {
    long uncachedInputTokens = inputTokens - cachedInputTokens;
    return CACHE_MISS_USD_PER_MILLION
        .multiply(BigDecimal.valueOf(uncachedInputTokens))
        .add(
            CACHE_HIT_USD_PER_MILLION.multiply(
                BigDecimal.valueOf(cachedInputTokens)))
        .add(
            OUTPUT_USD_PER_MILLION.multiply(
                BigDecimal.valueOf(outputTokens)))
        .divide(ONE_MILLION)
        .stripTrailingZeros();
  }

  private static BigDecimal requireWithinAcceptedEstimateCeiling(
      BigDecimal listPriceEstimate) {
    if (listPriceEstimate.compareTo(
            MAX_ACCEPTED_LIST_PRICE_ESTIMATE_USD)
        > 0) {
      throw new ProbeRejected("LIST_PRICE_ESTIMATE_CEILING_EXCEEDED");
    }
    return listPriceEstimate;
  }

  private static void requireLoopback(String baseUrl) {
    try {
      URI uri = URI.create(Objects.requireNonNull(baseUrl, "baseUrl"));
      InetAddress address = InetAddress.getByName(uri.getHost());
      if (!"http".equals(uri.getScheme())
          || uri.getPort() < 1
          || !address.isLoopbackAddress()
          || (uri.getPath() != null && !uri.getPath().isEmpty())) {
        throw new ProbeRejected("LOOPBACK_ENDPOINT_INVALID");
      }
    } catch (ProbeRejected rejected) {
      throw rejected;
    } catch (RuntimeException | java.net.UnknownHostException invalid) {
      throw new ProbeRejected("LOOPBACK_ENDPOINT_INVALID");
    }
  }

  private record ProbePayload(String status) {}

  record Receipt(
      String modelObservedHash,
      String requestHash,
      String responseHash,
      int requestBodyBytes,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      String listPriceProfileId,
      BigDecimal acceptedListPriceEstimateCeilingUsd,
      BigDecimal listPriceEstimateUsd) {

    Receipt {
      if (modelObservedHash == null
          || !modelObservedHash.matches("[a-f0-9]{64}")
          || requestHash == null
          || !requestHash.matches("[a-f0-9]{64}")
          || responseHash == null
          || !responseHash.matches("[a-f0-9]{64}")
          || requestBodyBytes < 1
          || requestBodyBytes > MAX_REQUEST_BODY_BYTES
          || inputTokens < 0
          || cachedInputTokens < 0
          || outputTokens < 0
          || reasoningOutputTokens != 0
          || totalTokens < 0
          || !LIST_PRICE_PROFILE_ID.equals(listPriceProfileId)
          || !MAX_ACCEPTED_LIST_PRICE_ESTIMATE_USD.equals(
              acceptedListPriceEstimateCeilingUsd)
          || listPriceEstimateUsd == null
          || listPriceEstimateUsd.signum() < 0) {
        throw new IllegalArgumentException("probe receipt is invalid");
      }
    }

    @Override
    public String toString() {
      return "DeepSeekV4FlashResponsesReceipt[modelRequested="
          + MODEL
          + ",modelObservedHash="
          + modelObservedHash
          + ",requestHash="
          + requestHash
          + ",responseHash="
          + responseHash
          + ",requestBodyBytes="
          + requestBodyBytes
          + ",inputTokens="
          + inputTokens
          + ",cachedInputTokens="
          + cachedInputTokens
          + ",outputTokens="
          + outputTokens
          + ",reasoningOutputTokens="
          + reasoningOutputTokens
          + ",totalTokens="
          + totalTokens
          + ",listPriceProfileId="
          + listPriceProfileId
          + ",acceptedListPriceEstimateCeilingUsd="
          + acceptedListPriceEstimateCeilingUsd.toPlainString()
          + ",listPriceEstimateUsd="
          + listPriceEstimateUsd.toPlainString()
          + "]";
    }
  }

  static final class ProbeRejected extends RuntimeException {

    private ProbeRejected(String code) {
      super(Objects.requireNonNull(code, "code"));
    }
  }
}
