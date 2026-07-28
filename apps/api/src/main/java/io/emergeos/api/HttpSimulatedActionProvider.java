package io.emergeos.api;

import io.emergeos.core.domain.ProviderActionRequest;
import io.emergeos.core.domain.ProviderResult;
import io.emergeos.core.port.ActionProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class HttpSimulatedActionProvider implements ActionProvider {

  private final HttpClient http;
  private final URI endpoint;
  private final Duration requestTimeout;

  HttpSimulatedActionProvider(URI baseUri, Duration requestTimeout) {
    Objects.requireNonNull(baseUri, "baseUri");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("provider request timeout must be positive");
    }
    requireLoopbackOrigin(baseUri);
    this.endpoint = baseUri.resolve("/execute-or-reconcile");
    this.http =
        HttpClient.newBuilder().connectTimeout(requestTimeout).build();
  }

  @Override
  public ProviderResult executeOrReconcile(ProviderActionRequest request) {
    Objects.requireNonNull(request, "request");
    String body = form(request);
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          http.send(
              httpRequest,
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return new ProviderResult.Unknown();
      }
      return parse(response.body(), request);
    } catch (HttpTimeoutException timeout) {
      return new ProviderResult.Unknown();
    } catch (IOException transportFailure) {
      return new ProviderResult.Unknown();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new ProviderResult.Unknown();
    }
  }

  static ProviderResult parse(
      String responseBody, ProviderActionRequest request) {
    if ("UNKNOWN".equals(responseBody)) {
      return new ProviderResult.Unknown();
    }
    String[] fields = responseBody.split("\t", -1);
    if (fields.length != 15 || !"SUCCEEDED".equals(fields[0])) {
      return new ProviderResult.Unknown();
    }
    if (!fields[5].equals(request.plan().principalId())
        || !fields[6].equals(request.connector())
        || !fields[7].equals(request.audience())
        || !fields[8].equals(request.accountRef())
        || !fields[9].equals(request.plan().planId())
        || !fields[10].equals(request.plan().planHash())
        || !fields[11].equals(request.plan().artifactId())
        || !fields[12].equals(Integer.toString(request.plan().artifactVersion()))
        || !fields[13].equals(request.plan().artifactHash())
        || !fields[14].equals(request.plan().idempotencyKey())) {
      return new ProviderResult.Unknown();
    }
    try {
      return new ProviderResult.Succeeded(
          fields[1], fields[2], fields[3], Instant.parse(fields[4]));
    } catch (RuntimeException malformedProviderResponse) {
      return new ProviderResult.Unknown();
    }
  }

  private static String form(ProviderActionRequest request) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("operation", request.operation().name());
    fields.put("principalId", request.plan().principalId());
    fields.put("connector", request.connector());
    fields.put("audience", request.audience());
    fields.put("accountRef", request.accountRef());
    fields.put("actionPlanId", request.plan().planId());
    fields.put("actionPlanHash", request.plan().planHash());
    fields.put("artifactId", request.plan().artifactId());
    fields.put("artifactVersion", Integer.toString(request.plan().artifactVersion()));
    fields.put("artifactHash", request.plan().artifactHash());
    fields.put("idempotencyKey", request.plan().idempotencyKey());
    return fields.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private static void requireLoopbackOrigin(URI baseUri) {
    if (!"http".equalsIgnoreCase(baseUri.getScheme())
        || baseUri.getHost() == null
        || baseUri.getPort() < 1
        || baseUri.getUserInfo() != null
        || (baseUri.getPath() != null
            && !baseUri.getPath().isEmpty()
            && !"/".equals(baseUri.getPath()))
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null) {
      throw new IllegalArgumentException(
          "simulated provider base URL must be an HTTP loopback origin");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(baseUri.getHost())) {
        if (!address.isLoopbackAddress()) {
          throw new IllegalArgumentException(
              "simulated provider base URL must resolve only to loopback");
        }
      }
    } catch (IOException resolutionFailure) {
      throw new IllegalArgumentException(
          "simulated provider base URL host cannot be resolved", resolutionFailure);
    }
  }
}
