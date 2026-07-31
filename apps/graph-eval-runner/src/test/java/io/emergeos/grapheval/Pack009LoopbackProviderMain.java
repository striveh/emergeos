package io.emergeos.grapheval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Independent, durable, test-only Responses provider for Pack009. */
public final class Pack009LoopbackProviderMain {

  private static final int MAX_REQUEST_BYTES = 128 * 1024;
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private Pack009LoopbackProviderMain() {}

  public static void main(String[] args) {
    int exitCode = 0;
    try {
      if (args == null || args.length != 5) {
        throw new IllegalArgumentException("arguments are invalid");
      }
      Path coordination =
          Pack009ProcessSupport.absoluteDirectory(args[1]);
      Path repoRoot =
          Pack009ProcessSupport.absoluteDirectory(args[2]);
      Path appJar =
          Pack009ProcessSupport.absoluteRegularFile(args[3]);
      Path testClasses =
          Pack009ProcessSupport.absoluteDirectory(args[4]);
      Pack009ProcessSupport.assertCodeSources(
          appJar,
          testClasses,
          Pack009LoopbackProviderMain.class);
      new Pack009GraphPreflight(repoRoot).run();
      if ("serve".equals(args[0])) {
        serve(coordination);
      } else if ("verify".equals(args[0])) {
        verify(coordination);
      } else {
        throw new IllegalArgumentException("mode is invalid");
      }
    } catch (Exception failure) {
      System.out.println(
          "PACK009_PROVIDER_REJECTED reason=PROVIDER_FAILED"
              + " failureType="
              + failure.getClass().getName());
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static void serve(Path coordination) throws Exception {
    Path ready =
        coordination.resolve(Pack009ProcessSupport.PROVIDER_READY);
    Path accepted =
        coordination.resolve(Pack009ProcessSupport.PROVIDER_ACCEPTED);
    Path release =
        coordination.resolve(Pack009ProcessSupport.PROVIDER_RELEASE);
    Path response =
        coordination.resolve(Pack009ProcessSupport.PROVIDER_RESPONSE);
    Path shutdown =
        coordination.resolve(Pack009ProcessSupport.PROVIDER_SHUTDOWN);
    if (Files.exists(ready)
        || Files.exists(accepted)
        || Files.exists(release)
        || Files.exists(response)
        || Files.exists(shutdown)) {
      throw new IllegalStateException(
          "provider coordination must begin empty");
    }

    AtomicInteger requests = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor()) {
      HttpServer server =
          HttpServer.create(
              new InetSocketAddress(
                  InetAddress.getByName("127.0.0.1"), 0),
              0);
      server.setExecutor(executor);
      server.createContext(
          "/v1/responses",
          exchange ->
              handle(
                  exchange,
                  accepted,
                  release,
                  response,
                  requests,
                  failure));
      try {
        server.start();
        String baseUrl =
            "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/v1";
        Pack009ProcessSupport.writeDurableCreateNew(
            ready, baseUrl);
        System.out.println(
            "PACK009_PROVIDER_READY pid="
                + ProcessHandle.current().pid());
        System.out.flush();

        while (failure.get() == null
            && !(Files.isRegularFile(
                    shutdown, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(
                    response, LinkOption.NOFOLLOW_LINKS))) {
          Thread.sleep(20);
        }
      } finally {
        server.stop(0);
      }
    }
    if (failure.get() != null) {
      throw new IllegalStateException(
          "provider handler failed", failure.get());
    }
    if (requests.get() != 1
        || !Files.isRegularFile(
            accepted, LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(
            response, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(
          "provider did not finish the exact one-request protocol");
    }
  }

  private static void handle(
      HttpExchange exchange,
      Path accepted,
      Path release,
      Path response,
      AtomicInteger requests,
      AtomicReference<Throwable> failure) {
    try {
      int ordinal = requests.incrementAndGet();
      if (ordinal != 1
          || !"POST".equals(exchange.getRequestMethod())
          || !"/v1/responses".equals(
              exchange.getRequestURI().getRawPath())
          || exchange.getRequestURI().getRawQuery() != null
          || !("Bearer "
                  + Pack009ProcessSupport.SYNTHETIC_PROVIDER_KEY)
              .equals(
                  exchange
                      .getRequestHeaders()
                      .getFirst("Authorization"))
          || exchange
                  .getRequestHeaders()
                  .getFirst("Content-Type")
              == null
          || !exchange
              .getRequestHeaders()
              .getFirst("Content-Type")
              .startsWith("application/json")) {
        throw new IllegalArgumentException(
            "provider request metadata is invalid");
      }
      byte[] body =
          exchange
              .getRequestBody()
              .readNBytes(MAX_REQUEST_BYTES + 1);
      if (body.length < 1 || body.length > MAX_REQUEST_BYTES) {
        throw new IllegalArgumentException(
            "provider request size is invalid");
      }
      String requestHash = Pack009ProcessSupport.sha256(body);
      if (!Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH
          .equals(requestHash)) {
        throw new IllegalArgumentException(
            "provider request hash is invalid");
      }
      JsonNode request = STRICT_JSON.readTree(body);
      verifyRequest(request);
      String ledger =
          "PACK009_PROVIDER_LEDGER version=1"
              + " count=1"
              + " ordinal=1"
              + " status=ACCEPTED_BLOCKED"
              + " model="
              + Pack009GraphEvalCatalog
                  .workerProfile()
                  .modelRequested()
              + " requestHash="
              + requestHash;
      Pack009ProcessSupport.writeDurableCreateNew(
          accepted, ledger);

      Pack009ProcessSupport.awaitFile(
          release, Duration.ofSeconds(30));
      byte[] responseBody =
          firstResponse().getBytes(StandardCharsets.UTF_8);
      Pack009ProcessSupport.writeDurableCreateNew(
          response,
          responseReceipt(requestHash, responseBody));
      try {
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length);
        try (var output = exchange.getResponseBody()) {
          output.write(responseBody);
        }
      } catch (IOException writerWasKilled) {
        // Provider response is durable; writer receipt is deliberately absent.
      }
    } catch (Throwable handlerFailure) {
      failure.compareAndSet(null, handlerFailure);
      try {
        exchange.sendResponseHeaders(400, -1);
      } catch (IOException ignored) {
        // The parent process observes the generic provider failure.
      }
    } finally {
      exchange.close();
    }
  }

  private static void verifyRequest(JsonNode request) {
    String expectedInput =
        "Task intent:\n"
            + Pack009GraphEvalCatalog.childTask().intent()
            + "\nDeclared capture reference:\n"
            + Pack009GraphEvalCatalog
                .childTask()
                .inputRefs()
                .getFirst();
    if (request == null
        || !request.isObject()
        || !Pack009GraphEvalCatalog.workerProfile()
            .modelRequested()
            .equals(request.path("model").asText())
        || request.path("max_output_tokens").asLong()
            != Pack009GraphEvalCatalog
                .MAXIMUM_OUTPUT_TOKENS_PER_REQUEST
        || request.path("store").asBoolean(true)
        || request.path("parallel_tool_calls").asBoolean(true)
        || !"default".equals(
            request.path("service_tier").asText())
        || request.path("include").size() != 1
        || !"reasoning.encrypted_content".equals(
            request.path("include").get(0).asText())
        || request.path("input").size() != 1
        || !"user".equals(
            request.at("/input/0/role").asText())
        || !expectedInput.equals(
            request.at("/input/0/content").asText())
        || request.path("tools").size() != 1
        || !"capture_read".equals(
            request.at("/tools/0/name").asText())
        || !request.at("/tools/0/strict").asBoolean()
        || !"capture_read".equals(
            request.at("/tool_choice/name").asText())
        || request.has("previous_response_id")
        || request.has("conversation")
        || request.has("prompt_cache_options")) {
      throw new IllegalArgumentException(
          "provider request semantics are invalid");
    }
  }

  private static void verify(Path coordination)
      throws IOException {
    String expected =
        "PACK009_PROVIDER_LEDGER version=1"
            + " count=1"
            + " ordinal=1"
            + " status=ACCEPTED_BLOCKED"
            + " model="
            + Pack009GraphEvalCatalog.workerProfile().modelRequested()
            + " requestHash="
            + Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH;
    String ledger =
        Pack009ProcessSupport.readBounded(
            coordination.resolve(
                Pack009ProcessSupport.PROVIDER_ACCEPTED));
    String response =
        Pack009ProcessSupport.readBounded(
            coordination.resolve(
                Pack009ProcessSupport.PROVIDER_RESPONSE));
    if (!expected.equals(ledger)
        || !expectedResponseReceipt().equals(response)) {
      throw new IllegalStateException(
          "provider ledger is invalid");
    }
    System.out.println(
        "PACK009_PROVIDER_VERIFY count=1"
            + " requestHash="
            + Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH
            + " responseDurable=true");
    System.out.flush();
  }

  static String expectedResponseReceipt() {
    return responseReceipt(
        Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH,
        firstResponse().getBytes(StandardCharsets.UTF_8));
  }

  private static String responseReceipt(
      String requestHash, byte[] responseBody) {
    return "PACK009_PROVIDER_RESPONSE version=1"
        + " count=1"
        + " status=RESPONSE_DURABLE"
        + " requestHash="
        + requestHash
        + " responseHash="
        + Pack009ProcessSupport.sha256(responseBody);
  }

  private static String firstResponse() {
    return """
        {
          "id":"resp-pack009-first",
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
              "id":"reasoning-pack009-first",
              "type":"reasoning",
              "summary":[],
              "encrypted_content":"synthetic-ciphertext-not-persisted",
              "status":"completed"
            },
            {
              "id":"function-pack009-first",
              "type":"function_call",
              "call_id":"call-pack009-first",
              "name":"capture_read",
              "arguments":"{\\"reference\\":\\"capture://capture-openai-worker-009\\"}",
              "status":"completed"
            }
          ],
          "usage":{
            "input_tokens":10,
            "input_tokens_details":{"cached_tokens":0},
            "output_tokens":5,
            "output_tokens_details":{"reasoning_tokens":2},
            "total_tokens":15
          }
        }
        """;
  }
}
