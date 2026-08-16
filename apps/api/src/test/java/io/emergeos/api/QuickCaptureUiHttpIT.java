package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Acceptance Red for the packaged, loopback-only Quick Capture entry. */
@Testcontainers
class QuickCaptureUiHttpIT {

  private static final String ENTRY_PATH = "/capture";
  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; base-uri 'none'; object-src 'none';"
          + " frame-ancestors 'none'; form-action 'self'; connect-src 'self';"
          + " img-src 'self'; style-src 'self'; script-src 'self'";
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern RESOURCE_ATTRIBUTE =
      Pattern.compile(
          "(?i)\\b(?:action|href|src)\\s*=\\s*([\"'])(.*?)\\1",
          Pattern.DOTALL);

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Test
  void packagedLoopbackEntryIsPrivateAccessibleAndSelfContained()
      throws Exception {
    RunningApplication application = startApplication();
    try {
      HttpResponse<String> response = getHtml(application.port(), ENTRY_PATH);

      assertEquals(
          200,
          response.statusCode(),
          () -> "QUICK_CAPTURE_UI_ENTRY_MISSING status=" + response.statusCode());
      assertEquals(
          "private, no-store",
          response.headers().firstValue("Cache-Control").orElse(""),
          "QUICK_CAPTURE_UI_CACHE_BOUNDARY_MISSING");
      assertEquals(
          CONTENT_SECURITY_POLICY,
          response.headers().firstValue("Content-Security-Policy").orElse(""),
          "QUICK_CAPTURE_UI_CSP_MISSING");
      assertTrue(
          response.headers().firstValue("Content-Type").orElse("")
              .toLowerCase(Locale.ROOT)
              .startsWith("text/html"),
          "QUICK_CAPTURE_UI_CONTENT_TYPE_INVALID");

      String html = response.body();
      assertMatches(html, "<html\\b[^>]*\\blang\\s*=\\s*['\"]zh-CN['\"]");
      assertMatches(html, "<form\\b[^>]*\\bid\\s*=\\s*['\"]quick-capture-form['\"]");
      assertMatches(html, "<label\\b[^>]*\\bfor\\s*=\\s*['\"]capture-content['\"]");
      assertMatches(
          html,
          "<textarea\\b(?=[^>]*\\bid\\s*=\\s*['\"]capture-content['\"])(?=[^>]*\\bname\\s*=\\s*['\"]content['\"])(?=[^>]*\\brequired(?:\\s|=|>))[^>]*>");
      assertMatches(html, "<fieldset\\b[^>]*>.*?<legend\\b[^>]*>[^<]+</legend>");
      assertSourceChoice(html, "TEXT");
      assertSourceChoice(html, "LINK");
      assertMatches(
          html,
          "<button\\b(?=[^>]*\\btype\\s*=\\s*['\"]submit['\"])[^>]*>[^<]+</button>");
      assertMatches(
          html,
          "<[^>]+\\brole\\s*=\\s*['\"]status['\"][^>]*\\baria-live\\s*=\\s*['\"]polite['\"][^>]*>");
      assertNoExternalResources(html);
    } finally {
      stop(application);
    }
  }

  private static void assertSourceChoice(String html, String value) {
    assertMatches(
        html,
        "<input\\b(?=[^>]*\\btype\\s*=\\s*['\"]radio['\"])(?=[^>]*\\bname\\s*=\\s*['\"]sourceType['\"])(?=[^>]*\\bvalue\\s*=\\s*['\"]"
            + value
            + "['\"])(?=[^>]*\\bid\\s*=\\s*['\"]source-"
            + value.toLowerCase(Locale.ROOT)
            + "['\"])[^>]*>");
    assertMatches(
        html,
        "<label\\b[^>]*\\bfor\\s*=\\s*['\"]source-"
            + value.toLowerCase(Locale.ROOT)
            + "['\"][^>]*>[^<]+</label>");
  }

  private static void assertMatches(String html, String expression) {
    assertTrue(
        Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html)
            .find(),
        () -> "QUICK_CAPTURE_UI_ACCESSIBLE_SURFACE_MISSING pattern=" + expression);
  }

  private static void assertNoExternalResources(String html) {
    Matcher matcher = RESOURCE_ATTRIBUTE.matcher(html);
    while (matcher.find()) {
      String value = matcher.group(2).strip();
      assertFalse(
          value.startsWith("//") || URI.create(value).isAbsolute(),
          () -> "QUICK_CAPTURE_UI_EXTERNAL_RESOURCE_FORBIDDEN attribute=" + value);
    }
  }

  private static RunningApplication startApplication() throws Exception {
    int port = availableLoopbackPort();
    Path log = Files.createTempFile("emerge-quick-capture-ui-", ".log");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-jar");
    command.add(System.getProperty("emerge.it.jar"));
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    command.add("--emerge.prototype.principal-id=quick-capture-ui-owner");
    command.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
    command.add("--spring.datasource.username=" + POSTGRES.getUsername());
    command.add("--spring.datasource.password=" + POSTGRES.getPassword());

    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    RunningApplication application = new RunningApplication(process, port, log);
    try {
      awaitHealthy(application);
      return application;
    } catch (Exception | AssertionError failure) {
      stop(application);
      throw failure;
    }
  }

  private static void awaitHealthy(RunningApplication application)
      throws Exception {
    long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      assertTrue(
          application.process().isAlive(),
          () -> "QUICK_CAPTURE_PACKAGED_APP_EXITED log=" + readLog(application.log()));
      try {
        if (get(application.port(), "/actuator/health", "application/json")
                .statusCode()
            == 200) {
          return;
        }
      } catch (IOException ignored) {
        // The loopback listener may not be ready yet.
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("QUICK_CAPTURE_PACKAGED_APP_START_TIMEOUT");
  }

  private static HttpResponse<String> getHtml(int port, String path)
      throws IOException, InterruptedException {
    return get(port, path, "text/html");
  }

  private static HttpResponse<String> get(
      int port, String path, String accept)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", accept)
            .GET()
            .build();
    return HTTP.send(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int availableLoopbackPort() throws IOException {
    try (ServerSocket socket =
        new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stop(RunningApplication application) throws Exception {
    if (application.process().isAlive()) {
      application.process().destroyForcibly();
      assertTrue(
          application.process().waitFor(10, TimeUnit.SECONDS),
          "QUICK_CAPTURE_PACKAGED_APP_DID_NOT_STOP");
    }
    Files.deleteIfExists(application.log());
  }

  private static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (IOException failure) {
      return "unavailable";
    }
  }

  private record RunningApplication(Process process, int port, Path log) {}
}
