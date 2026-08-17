package io.emergeos.adapters.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import io.emergeos.contracts.IntegrityHashes;
import java.io.IOException;
import java.net.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SDK client bound to the exact default JSON codec used for request receipts.
 *
 * <p>The constructor is intentionally private. A typed request receipt cannot
 * be attached to an opaque SDK client whose configured mapper is unknown.
 */
public final class ReviewedOpenAiClient implements AutoCloseable {

  static final int MAX_REVIEWED_RESPONSE_BYTES = 1_048_576;

  private static final JsonMapper REQUEST_CODEC_TEMPLATE =
      ObjectMappers.jsonMapper().copy();
  private static final ObjectMapper RESPONSE_CODEC_TEMPLATE =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private final OpenAIClient client;
  private final JsonMapper requestMapper;
  private final ObjectMapper responseMapper;

  private ReviewedOpenAiClient(
      OpenAIClient client,
      JsonMapper requestMapper,
      ObjectMapper responseMapper) {
    this.client = Objects.requireNonNull(client, "client");
    this.requestMapper =
        Objects.requireNonNull(requestMapper, "requestMapper");
    this.responseMapper =
        Objects.requireNonNull(responseMapper, "responseMapper");
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
    return new ReviewedOpenAiClient(
        client, mapper, newResponseMapper());
  }

  static ReviewedOpenAiClient defaultCodecNoRetryNoRedirect(
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
    Proxy exactProxy = Objects.requireNonNull(proxy, "proxy");
    Duration exactTimeout = Objects.requireNonNull(timeout, "timeout");
    JsonMapper mapper = newRequestMapper();
    okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(exactProxy)
            .connectTimeout(exactTimeout)
            .readTimeout(exactTimeout)
            .writeTimeout(exactTimeout)
            .callTimeout(exactTimeout)
            .build();
    OpenAIClient client =
        new OpenAIClientImpl(
            ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .httpClient(
                    new com.openai.client.okhttp.OkHttpClient(
                        transport))
                .maxRetries(0)
                .timeout(exactTimeout)
                .logLevel(
                    Objects.requireNonNull(logLevel, "logLevel"))
                .jsonMapper(mapper)
                .build());
    return new ReviewedOpenAiClient(
        client, mapper, newResponseMapper());
  }

  OpenAIClient client() {
    return client;
  }

  String requestBodyHash(Object requestBody) {
    return requestBodyHash(requestMapper, requestBody);
  }

  int requestBodyLength(Object requestBody) {
    try {
      return requestMapper
          .writeValueAsBytes(
              Objects.requireNonNull(requestBody, "requestBody"))
          .length;
    } catch (Exception serializationFailure) {
      throw new IllegalStateException(
          "OpenAI request serialization failed",
          serializationFailure);
    }
  }

  ReviewedResponse createReviewedResponse(
      ResponseCreateParams params, RequestOptions requestOptions) {
    Objects.requireNonNull(params, "params");
    Objects.requireNonNull(requestOptions, "requestOptions");
    byte[] body;
    try (HttpResponseFor<Response> response =
        client
            .responses()
            .withRawResponse()
            .create(params, requestOptions)) {
      body =
          response
              .body()
              .readNBytes(MAX_REVIEWED_RESPONSE_BYTES + 1);
    } catch (IOException failure) {
      throw new OpenAIIoException(
          "OpenAI response body read failed", failure);
    }
    if (body.length > MAX_REVIEWED_RESPONSE_BYTES) {
      throw new OpenAIInvalidDataException(
          "OpenAI response body exceeds the reviewed limit");
    }
    try {
      requireSingleStrictJsonObject(body);
      Response parsed =
          responseMapper.readValue(body, Response.class);
      return new ReviewedResponse(parsed, exactBytesHash(body));
    } catch (OpenAIInvalidDataException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new OpenAIInvalidDataException(
          "OpenAI response body is not strict valid JSON", failure);
    } finally {
      body = null;
    }
  }

  /**
   * Rejects duplicate keys and trailing JSON before the SDK generated
   * deserializers inspect the envelope.
   *
   * <p>{@code FAIL_ON_TRAILING_TOKENS} cannot be enabled on the mapper used by
   * the SDK because generated nested deserializers read one field value at a
   * time; a following sibling field would then be mistaken for a trailing
   * root token. This independent root pass preserves strict envelope
   * semantics without changing generated-field parsing behavior.
   */
  private void requireSingleStrictJsonObject(byte[] body)
      throws IOException {
    try (JsonParser parser =
        responseMapper.getFactory().createParser(body)) {
      JsonNode root = parser.readValueAsTree();
      if (root == null
          || !root.isObject()
          || parser.nextToken() != null) {
        throw new IOException(
            "OpenAI response body must contain exactly one JSON object");
      }
    }
  }

  static String reviewedRequestBodyHash(Object requestBody) {
    return requestBodyHash(newRequestMapper(), requestBody);
  }

  private static JsonMapper newRequestMapper() {
    return REQUEST_CODEC_TEMPLATE.copy();
  }

  private static ObjectMapper newResponseMapper() {
    return RESPONSE_CODEC_TEMPLATE.copy();
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

  private static String exactBytesHash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is unavailable", impossible);
    }
  }

  static final class ReviewedResponse {

    private final Response response;
    private final String responseHash;

    private ReviewedResponse(
        Response response, String responseHash) {
      this.response = Objects.requireNonNull(response, "response");
      this.responseHash =
          Objects.requireNonNull(responseHash, "responseHash");
    }

    Response response() {
      return response;
    }

    String responseHash() {
      return responseHash;
    }

    @Override
    public String toString() {
      return "ReviewedResponse[responseHash=" + responseHash + "]";
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
