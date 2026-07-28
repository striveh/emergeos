package io.emergeos.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only provider process. It uses a file as its source of truth so its idempotency state is
 * independent of both the application JVM and the application PostgreSQL.
 */
public final class SimulatedProviderProcessMain {

  private SimulatedProviderProcessMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: SimulatedProviderProcessMain <port> <state-file>");
    }
    int port = Integer.parseInt(args[0]);
    Path stateFile = Path.of(args[1]).toAbsolutePath().normalize();
    ProviderState state = new ProviderState(stateFile);
    HttpServer server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/health", exchange -> send(exchange, 200, "UP"));
    server.createContext("/admin/configure", exchange -> configure(exchange, state));
    server.createContext("/admin/release", exchange -> release(exchange, state));
    server.createContext("/admin/observation", exchange -> observation(exchange, state));
    server.createContext(
        "/execute-or-reconcile", exchange -> executeOrReconcile(exchange, state));
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    new CountDownLatch(1).await();
  }

  private static void configure(HttpExchange exchange, ProviderState state) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "method-not-allowed");
      return;
    }
    Map<String, String> form = form(exchange);
    state.configure(required(form, "idempotencyKey"), Mode.valueOf(required(form, "mode")));
    send(exchange, 204, "");
  }

  private static void release(HttpExchange exchange, ProviderState state) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "method-not-allowed");
      return;
    }
    state.release(required(form(exchange), "idempotencyKey"));
    send(exchange, 204, "");
  }

  private static void observation(HttpExchange exchange, ProviderState state) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "method-not-allowed");
      return;
    }
    String key = required(query(exchange), "idempotencyKey");
    Observation observation = state.observation(key);
    send(
        exchange,
        200,
        """
        {"requestCount":%d,"objectCount":%d,"totalObjectCount":%d,"blocked":%s}
        """
            .formatted(
                observation.requestCount(),
                observation.objectCount(),
                observation.totalObjectCount(),
                observation.blocked())
            .strip());
  }

  private static void executeOrReconcile(HttpExchange exchange, ProviderState state)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      send(exchange, 405, "method-not-allowed");
      return;
    }
    Map<String, String> form = form(exchange);
    ProviderRequest request =
        new ProviderRequest(
            Operation.valueOf(required(form, "operation")),
            required(form, "principalId"),
            required(form, "connector"),
            required(form, "audience"),
            required(form, "accountRef"),
            required(form, "actionPlanId"),
            required(form, "actionPlanHash"),
            required(form, "artifactId"),
            Integer.parseInt(required(form, "artifactVersion")),
            required(form, "artifactHash"),
            required(form, "idempotencyKey"));
    String key = request.idempotencyKey();
    ProviderAction action = state.begin(key);
    ProviderObject existing = state.find(request.identity());
    if (existing != null) {
      if (!existing.matches(request)) {
        send(exchange, 409, "BINDING_CONFLICT");
        return;
      }
      send(exchange, 200, succeeded(existing));
      return;
    }

    if (request.operation() == Operation.RECONCILE_ONLY) {
      send(exchange, 200, "UNKNOWN");
      return;
    }

    if (action.mode() == Mode.TIMEOUT_WITHOUT_OBJECT) {
      sleep(1_500);
      send(exchange, 200, "UNKNOWN");
      return;
    }

    if (action.mode() == Mode.BLOCK_CREATE_DROP_ONCE && action.requestNumber() == 1) {
      state.markBlocked(key);
      try {
        if (!action.release().await(15, TimeUnit.SECONDS)) {
          exchange.close();
          return;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        exchange.close();
        return;
      } finally {
        state.clearBlocked(key);
      }
      state.createOrFind(request);
      exchange.close();
      return;
    }

    send(exchange, 200, succeeded(state.createOrFind(request)));
  }

  private static String succeeded(ProviderObject object) {
    return String.join(
        "\t",
        "SUCCEEDED",
        object.externalId(),
        object.providerRequestId(),
        object.rawResponseRef(),
        object.occurredAt().toString(),
        object.principalId(),
        object.connector(),
        object.audience(),
        object.accountRef(),
        object.actionPlanId(),
        object.actionPlanHash(),
        object.artifactId(),
        Integer.toString(object.artifactVersion()),
        object.artifactHash(),
        object.idempotencyKey());
  }

  private static Map<String, String> form(HttpExchange exchange) throws IOException {
    return decodeParams(
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static Map<String, String> query(HttpExchange exchange) {
    return decodeParams(exchange.getRequestURI().getRawQuery());
  }

  private static Map<String, String> decodeParams(String encoded) {
    Map<String, String> params = new HashMap<>();
    if (encoded == null || encoded.isBlank()) {
      return params;
    }
    for (String pair : encoded.split("&")) {
      String[] parts = pair.split("=", 2);
      String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value =
          parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      params.put(name, value);
    }
    return params;
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (status == 204) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private static void sleep(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private enum Mode {
    NORMAL,
    BLOCK_CREATE_DROP_ONCE,
    TIMEOUT_WITHOUT_OBJECT
  }

  private enum Operation {
    EXECUTE,
    RECONCILE_ONLY
  }

  private record ProviderAction(Mode mode, int requestNumber, CountDownLatch release) {}

  private record ProviderIdentity(
      String connector, String accountRef, String idempotencyKey) {}

  private record ProviderRequest(
      Operation operation,
      String principalId,
      String connector,
      String audience,
      String accountRef,
      String actionPlanId,
      String actionPlanHash,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String idempotencyKey) {

    private ProviderIdentity identity() {
      return new ProviderIdentity(connector, accountRef, idempotencyKey);
    }
  }

  private record ProviderObject(
      String principalId,
      String connector,
      String audience,
      String accountRef,
      String idempotencyKey,
      String actionPlanId,
      String actionPlanHash,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String externalId,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt) {

    private ProviderIdentity identity() {
      return new ProviderIdentity(connector, accountRef, idempotencyKey);
    }

    private boolean matches(ProviderRequest request) {
      return principalId.equals(request.principalId())
          && connector.equals(request.connector())
          && audience.equals(request.audience())
          && accountRef.equals(request.accountRef())
          && idempotencyKey.equals(request.idempotencyKey())
          && actionPlanId.equals(request.actionPlanId())
          && actionPlanHash.equals(request.actionPlanHash())
          && artifactId.equals(request.artifactId())
          && artifactVersion == request.artifactVersion()
          && artifactHash.equals(request.artifactHash());
    }
  }

  private record Observation(
      int requestCount, int objectCount, int totalObjectCount, boolean blocked) {}

  private static final class ProviderState {
    private final Path stateFile;
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();
    private final Map<String, Boolean> blocked = new ConcurrentHashMap<>();
    private final Map<ProviderIdentity, ProviderObject> objects = new LinkedHashMap<>();

    private ProviderState(Path stateFile) throws IOException {
      this.stateFile = stateFile;
      Path parent = stateFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      load();
    }

    private void configure(String key, Mode mode) {
      modes.put(key, mode);
      requestCounts.put(key, new AtomicInteger());
      releases.put(key, new CountDownLatch(1));
      blocked.put(key, false);
    }

    private ProviderAction begin(String key) {
      int requestNumber =
          requestCounts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
      return new ProviderAction(
          modes.getOrDefault(key, Mode.NORMAL),
          requestNumber,
          releases.computeIfAbsent(key, ignored -> new CountDownLatch(0)));
    }

    private void release(String key) {
      releases.computeIfAbsent(key, ignored -> new CountDownLatch(0)).countDown();
    }

    private void markBlocked(String key) {
      blocked.put(key, true);
    }

    private void clearBlocked(String key) {
      blocked.put(key, false);
    }

    private synchronized ProviderObject find(ProviderIdentity identity) {
      return objects.get(identity);
    }

    private synchronized ProviderObject createOrFind(ProviderRequest request)
        throws IOException {
      ProviderObject existing = objects.get(request.identity());
      if (existing != null) {
        if (!existing.matches(request)) {
          throw new IllegalStateException("provider idempotency binding conflict");
        }
        return existing;
      }
      String shortHash =
          sha256(
                  request.connector()
                      + "\n"
                      + request.accountRef()
                      + "\n"
                      + request.idempotencyKey())
              .substring(0, 16);
      ProviderObject created =
          new ProviderObject(
              request.principalId(),
              request.connector(),
              request.audience(),
              request.accountRef(),
              request.idempotencyKey(),
              request.actionPlanId(),
              request.actionPlanHash(),
              request.artifactId(),
              request.artifactVersion(),
              request.artifactHash(),
              "sim-object-" + shortHash,
              "sim-request-" + shortHash,
              "simulated://provider/objects/sim-object-" + shortHash,
              Instant.now().truncatedTo(ChronoUnit.MILLIS));
      objects.put(created.identity(), created);
      persist();
      return created;
    }

    private synchronized Observation observation(String key) {
      return new Observation(
          requestCounts.getOrDefault(key, new AtomicInteger()).get(),
          (int)
              objects.values().stream()
                  .filter(object -> object.idempotencyKey().equals(key))
                  .count(),
          objects.size(),
          blocked.getOrDefault(key, false));
    }

    private void load() throws IOException {
      if (!Files.exists(stateFile)) {
        return;
      }
      for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        String[] fields = line.split("\t", -1);
        if (fields.length != 14) {
          throw new IOException("invalid simulated provider state row");
        }
        ProviderObject object =
            new ProviderObject(
                decoded(fields[0]),
                decoded(fields[1]),
                decoded(fields[2]),
                decoded(fields[3]),
                decoded(fields[4]),
                decoded(fields[5]),
                decoded(fields[6]),
                decoded(fields[7]),
                Integer.parseInt(decoded(fields[8])),
                decoded(fields[9]),
                decoded(fields[10]),
                decoded(fields[11]),
                decoded(fields[12]),
                Instant.parse(decoded(fields[13])));
        objects.put(object.identity(), object);
      }
    }

    private void persist() throws IOException {
      StringBuilder content = new StringBuilder();
      for (ProviderObject object : objects.values()) {
        content
            .append(encoded(object.principalId()))
            .append('\t')
            .append(encoded(object.connector()))
            .append('\t')
            .append(encoded(object.audience()))
            .append('\t')
            .append(encoded(object.accountRef()))
            .append('\t')
            .append(encoded(object.idempotencyKey()))
            .append('\t')
            .append(encoded(object.actionPlanId()))
            .append('\t')
            .append(encoded(object.actionPlanHash()))
            .append('\t')
            .append(encoded(object.artifactId()))
            .append('\t')
            .append(encoded(Integer.toString(object.artifactVersion())))
            .append('\t')
            .append(encoded(object.artifactHash()))
            .append('\t')
            .append(encoded(object.externalId()))
            .append('\t')
            .append(encoded(object.providerRequestId()))
            .append('\t')
            .append(encoded(object.rawResponseRef()))
            .append('\t')
            .append(encoded(object.occurredAt().toString()))
            .append('\n');
      }
      Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary,
            stateFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static String encoded(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decoded(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
