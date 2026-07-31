package io.emergeos.adapters.openai;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import io.emergeos.contracts.IntegrityHashes;
import java.net.Proxy;
import java.time.Duration;
import java.util.Objects;

/**
 * SDK client bound to the exact default JSON codec used for request receipts.
 *
 * <p>The constructor is intentionally private. A typed request receipt cannot
 * be attached to an opaque SDK client whose configured mapper is unknown.
 */
public final class ReviewedOpenAiClient implements AutoCloseable {

  private static final JsonMapper REQUEST_CODEC_TEMPLATE =
      ObjectMappers.jsonMapper().copy();

  private final OpenAIClient client;
  private final JsonMapper requestMapper;

  private ReviewedOpenAiClient(
      OpenAIClient client, JsonMapper requestMapper) {
    this.client = Objects.requireNonNull(client, "client");
    this.requestMapper =
        Objects.requireNonNull(requestMapper, "requestMapper");
  }

  /**
   * Builds a no-retry SDK client and binds one private mapper instance to both
   * SDK transport serialization and EmergeOS request hashing.
   *
   * <p>The mapper is copied from a private reviewed template and never exposed,
   * so an observer cannot mutate the SDK body codec after a receipt is hashed
   * but before the SDK lazily writes the request body.
   */
  public static ReviewedOpenAiClient defaultCodecNoRetry(
      String apiKey,
      String baseUrl,
      Proxy proxy,
      Duration timeout,
      LogLevel logLevel) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey is required");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl is required");
    }
    JsonMapper mapper = newRequestMapper();
    OpenAIClient client =
        OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .proxy(Objects.requireNonNull(proxy, "proxy"))
            .maxRetries(0)
            .timeout(Objects.requireNonNull(timeout, "timeout"))
            .logLevel(Objects.requireNonNull(logLevel, "logLevel"))
            .jsonMapper(mapper)
            .build();
    return new ReviewedOpenAiClient(client, mapper);
  }

  OpenAIClient client() {
    return client;
  }

  String requestBodyHash(Object requestBody) {
    return requestBodyHash(requestMapper, requestBody);
  }

  static String reviewedRequestBodyHash(Object requestBody) {
    return requestBodyHash(newRequestMapper(), requestBody);
  }

  private static JsonMapper newRequestMapper() {
    return REQUEST_CODEC_TEMPLATE.copy();
  }

  private static String requestBodyHash(
      JsonMapper mapper, Object requestBody) {
    try {
      return IntegrityHashes.utf8ContentHash(
          mapper.writeValueAsString(
              Objects.requireNonNull(requestBody, "requestBody")));
    } catch (Exception serializationFailure) {
      throw new IllegalStateException(
          "OpenAI request serialization failed",
          serializationFailure);
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
