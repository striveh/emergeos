package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticEvalCrashRestartProcessIT {

  private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);
  private static final String HARNESS_MAIN =
      "io.emergeos.evalrunner.SyntheticEvalCrashHarnessMain";
  private static final String HARNESS_CLASS_ENTRY =
      "BOOT-INF/classes/"
          + HARNESS_MAIN.replace('.', '/')
          + ".class";
  private static final String SYNTHETIC_RESPONSE_SENTINEL =
      "SYNTHETIC_MODEL_OUTPUT_SENTINEL_7F3D8A2C";

  @TempDir Path tempDir;

  @Test
  void forceKilledFatJarCanBeVerifiedReadOnlyAndCannotReplay()
      throws Exception {
    assertHarnessIsTestOnly();

    for (CrashCase crashCase : crashCases()) {
      exerciseCrashCase(crashCase);
    }
  }

  @Test
  void shippingCliRejectsCrashInjectionArguments() throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("cli-home"));
    Path tmp = Files.createDirectory(tempDir.resolve("cli-tmp"));
    ProcessResult result =
        runToCompletion(
            packagedCommand(
                home,
                tmp,
                List.of("--crash-at=GATE_APPROVED_DURABLE")),
            home,
            tmp.resolve("cli-output.log"),
            EXIT_TIMEOUT);

    assertEquals(2, result.exitCode(), result.output());
    assertEquals(
        "EVAL_REJECTED reason=ARGUMENTS_INVALID", result.output());
    assertFalse(Files.exists(home.resolve(".emergeos")));
    assertSafeOutput(result.output());
  }

  private void exerciseCrashCase(CrashCase crashCase)
      throws Exception {
    Path caseRoot =
        Files.createDirectory(tempDir.resolve(crashCase.id()));
    Path home = Files.createDirectory(caseRoot.resolve("home"));
    Path tmp = Files.createDirectory(caseRoot.resolve("tmp"));
    Path ready = caseRoot.resolve("ready");

    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer()) {
      Process child =
          start(
              harnessCommand(
                  home,
                  tmp,
                  "execute",
                  repo().toString(),
                  home.toString(),
                  server.baseUrl(),
                  crashCase.phase(),
                  ready.toString()),
              repo(),
              caseRoot.resolve("crash-output.log"));
      awaitReady(
          child,
          ready,
          caseRoot.resolve("crash-output.log"),
          crashCase);
      assertTrue(child.isAlive(), crashCase.id());
      assertEquals(
          crashCase.phase(),
          Files.readString(ready, StandardCharsets.US_ASCII).strip());

      child.destroyForcibly();
      assertTrue(
          child.waitFor(
              EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
          crashCase.id() + " child did not die");
      assertNotEquals(0, child.exitValue(), crashCase.id());
      String childOutput =
          readOutput(caseRoot.resolve("crash-output.log"));
      assertTrue(
          childOutput.contains(
              "CRASH_HARNESS_READY phase="
                  + crashCase.phase()),
          childOutput);
      assertSafeOutput(childOutput);
      assertEquals(
          crashCase.httpRequests(),
          server.requestCount(),
          crashCase.id());

      EvidenceSnapshot afterCrash = snapshot(home);
      ProcessResult firstVerification =
          runHarnessToCompletion(home, tmp, "verify", home.toString());
      assertEquals(
          0, firstVerification.exitCode(), firstVerification.output());
      assertEquals(
          crashCase.expectedVerificationLine(),
          firstVerification.output());
      assertEquals(afterCrash, snapshot(home), crashCase.id());

      ProcessResult secondVerification =
          runHarnessToCompletion(home, tmp, "verify", home.toString());
      assertEquals(
          firstVerification, secondVerification, crashCase.id());
      assertEquals(afterCrash, snapshot(home), crashCase.id());
      assertSafeOutput(firstVerification.output());

      ProcessResult replay =
          runHarnessToCompletion(
              home,
              tmp,
              "execute",
              repo().toString(),
              home.toString(),
              server.baseUrl(),
              "NONE",
              caseRoot.resolve("replay-ready").toString());
      assertEquals(2, replay.exitCode(), replay.output());
      assertEquals(
          "CRASH_HARNESS_REJECTED reason=MARKER_ALREADY_EXISTS",
          replay.output());
      assertEquals(
          crashCase.httpRequests(),
          server.requestCount(),
          crashCase.id());
      assertEquals(afterCrash, snapshot(home), crashCase.id());
      assertSafeOutput(replay.output());
    }
  }

  private ProcessResult runHarnessToCompletion(
      Path home, Path tmp, String mode, String... arguments)
      throws Exception {
    List<String> commandArguments = new ArrayList<>();
    commandArguments.add(mode);
    commandArguments.addAll(List.of(arguments));
    return runToCompletion(
        harnessCommand(
            home,
            tmp,
            commandArguments.toArray(String[]::new)),
        repo(),
        Files.createTempFile(tmp, "harness-output-", ".log"),
        EXIT_TIMEOUT);
  }

  private static void awaitReady(
      Process child,
      Path ready,
      Path output,
      CrashCase crashCase)
      throws Exception {
    long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline
        && child.isAlive()
        && !Files.isRegularFile(ready)) {
      Thread.sleep(20);
    }
    if (!Files.isRegularFile(ready)) {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor();
      }
      fail(
          crashCase.id()
              + " did not become ready; output="
              + readOutput(output));
    }
  }

  private static ProcessResult runToCompletion(
      List<String> command,
      Path workingDirectory,
      Path output,
      Duration timeout)
      throws Exception {
    Process process = start(command, workingDirectory, output);
    boolean finished =
        process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor();
    }
    String observedOutput = readOutput(output);
    assertTrue(
        finished, "process timed out; output=" + observedOutput);
    return new ProcessResult(process.exitValue(), observedOutput);
  }

  private static Process start(
      List<String> command,
      Path workingDirectory,
      Path output)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDirectory.toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.toFile());
    builder.environment().clear();
    return builder.start();
  }

  private static List<String> harnessCommand(
      Path home, Path tmp, String... arguments) {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + tmp,
                "-Djava.net.useSystemProxies=false",
                "-Dloader.main=" + HARNESS_MAIN,
                "-Dloader.path=" + testClasses(),
                "-cp",
                fatJar().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
    command.addAll(List.of(arguments));
    return command;
  }

  private static List<String> packagedCommand(
      Path home, Path tmp, List<String> arguments) {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + tmp,
                "-jar",
                fatJar().toString()));
    command.addAll(arguments);
    return command;
  }

  private static void assertHarnessIsTestOnly() throws IOException {
    try (JarFile jar = new JarFile(fatJar().toFile())) {
      assertNull(jar.getEntry(HARNESS_CLASS_ENTRY));
    }
    assertTrue(
        Files.isRegularFile(
            testClasses()
                .resolve(HARNESS_MAIN.replace('.', '/') + ".class")));
  }

  private static EvidenceSnapshot snapshot(Path home)
      throws IOException {
    Path directory = home.resolve(".emergeos/eval-attempts");
    Map<String, EvidenceFile> files = new LinkedHashMap<>();
    try (var entries = Files.list(directory)) {
      for (Path path :
          entries.sorted(Comparator.comparing(Path::toString)).toList()) {
        byte[] bytes = Files.readAllBytes(path);
        files.put(
            path.getFileName().toString(),
            new EvidenceFile(
                bytes.length,
                Files.getLastModifiedTime(path).toMillis(),
                sha256(bytes)));
      }
    }
    return new EvidenceSnapshot(Map.copyOf(files));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 unavailable", impossible);
    }
  }

  private static void assertSafeOutput(String output) {
    assertFalse(output.contains("sentinel-crash-harness-key"), output);
    assertFalse(output.contains("Authorization"), output);
    assertFalse(output.contains("resp-eval-"), output);
    assertFalse(output.contains("loopback-ciphertext"), output);
    assertFalse(output.contains(SYNTHETIC_RESPONSE_SENTINEL), output);
    assertFalse(
        output.contains(
            "虚构产品 Lumen Note 会把公开测试灵感整理成一张可追溯的创作卡片"),
        output);
    assertFalse(output.contains("Exception"), output);
  }

  private static List<CrashCase> crashCases() {
    return List.of(
        crashCase(
            "gate",
            "GATE_APPROVED_DURABLE",
            0,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "NOT_INVOKED",
            BigDecimal.ZERO,
            0,
            0,
            0,
            "ABSENT",
            false),
        crashCase(
            "credential-read-started",
            "CREDENTIAL_READ_STARTED",
            0,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "NOT_INVOKED",
            BigDecimal.ZERO,
            0,
            0,
            0,
            "ABSENT",
            false),
        crashCase(
            "provider-intent",
            "PROVIDER_SDK_CREATE_INTENT_DURABLE",
            0,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "UNKNOWN",
            BigDecimal.ZERO,
            0,
            1,
            0,
            "ABSENT",
            false),
        crashCase(
            "provider-attributed",
            "PROVIDER_ATTRIBUTED_DURABLE",
            1,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "ATTRIBUTED",
            new BigDecimal("0.000165"),
            120,
            1,
            1,
            "ABSENT",
            false),
        crashCase(
            "record-pending",
            "RUN_RECORD_PENDING_DURABLE",
            2,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "ATTRIBUTED",
            new BigDecimal("0.000413"),
            300,
            2,
            2,
            "PENDING_NON_AUTHORITATIVE",
            false),
        crashCase(
            "record-final",
            "RUN_RECORD_FINAL_DURABLE",
            2,
            "UNKNOWN",
            "TERMINAL_EVENT_MISSING",
            "ATTRIBUTED",
            new BigDecimal("0.000413"),
            300,
            2,
            2,
            "FINAL_UNSEALED",
            false),
        crashCase(
            "terminal-journal",
            "TERMINAL_JOURNAL_DURABLE",
            2,
            "VERIFIED",
            "TERMINAL_EVIDENCE_VERIFIED",
            "ATTRIBUTED",
            new BigDecimal("0.000413"),
            300,
            2,
            2,
            "TERMINAL_LINKED",
            true));
  }

  private static CrashCase crashCase(
      String id,
      String phase,
      int httpRequests,
      String verdict,
      String code,
      String billingStatus,
      BigDecimal observedCostUsd,
      long observedTokenCount,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations,
      String recordState,
      boolean terminalPresent) {
    return new CrashCase(
        id,
        phase,
        httpRequests,
        "VERIFY verdict="
            + verdict
            + " code="
            + code
            + " billingStatus="
            + billingStatus
            + " trustedPrefix=true"
            + " observedCostUsd="
            + observedCostUsd.toPlainString()
            + " observedTokenCount="
            + observedTokenCount
            + " providerSdkCreateInvocations="
            + providerSdkCreateInvocations
            + " providerAttributedInvocations="
            + providerAttributedInvocations
            + " recordState="
            + recordState
            + " terminalPresent="
            + terminalPresent);
  }

  private static Path fatJar() {
    return Path.of(System.getProperty("emerge.eval.it.jar"))
        .toAbsolutePath()
        .normalize();
  }

  private static Path testClasses() {
    return fatJar().getParent().resolve("test-classes");
  }

  private static Path repo() {
    return Path.of(System.getProperty("emerge.eval.it.repo"))
        .toAbsolutePath()
        .normalize();
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java")
        .toString();
  }

  private static String readOutput(Path output) throws IOException {
    return new String(
            Files.readAllBytes(output),
            StandardCharsets.UTF_8)
        .strip();
  }

  private record CrashCase(
      String id,
      String phase,
      int httpRequests,
      String expectedVerificationLine) {}

  private record ProcessResult(int exitCode, String output) {}

  private record EvidenceSnapshot(Map<String, EvidenceFile> files) {}

  private record EvidenceFile(
      long size, long lastModifiedMillis, String sha256) {}

  private static final class LoopbackResponsesServer
      implements AutoCloseable {

    private final AtomicInteger requests = new AtomicInteger();
    private final ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;

    private LoopbackResponsesServer() throws IOException {
      server =
          HttpServer.create(
              new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/responses", this::respond);
      server.start();
    }

    private void respond(HttpExchange exchange) throws IOException {
      exchange.getRequestBody().readAllBytes();
      int ordinal = requests.incrementAndGet();
      String body =
          ordinal == 1
              ? firstResponse()
              : ordinal == 2 ? secondResponse() : null;
      if (body == null) {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
        return;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange
          .getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/v1";
    }

    private int requestCount() {
      return requests.get();
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }

    private static String firstResponse() {
      return """
          {
            "id":"resp-eval-first",
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
                "id":"reasoning-eval-first",
                "type":"reasoning",
                "summary":[],
                "encrypted_content":"loopback-ciphertext-first",
                "status":"completed"
              },
              {
                "id":"function-eval-first",
                "type":"function_call",
                "call_id":"call-eval-003",
                "name":"capture_read",
                "arguments":"{\\"reference\\":\\"capture://capture-openai-public-003\\"}",
                "status":"completed"
              }
            ],
            "usage":{
              "input_tokens":100,
              "input_tokens_details":{"cached_tokens":0},
              "output_tokens":20,
              "output_tokens_details":{"reasoning_tokens":8},
              "total_tokens":120
            }
          }
          """;
    }

    private static String secondResponse() {
      return """
          {
            "id":"resp-eval-second",
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
                "id":"reasoning-eval-second",
                "type":"reasoning",
                "summary":[],
                "encrypted_content":"loopback-ciphertext-second",
                "status":"completed"
              },
              {
                "id":"message-eval-second",
                "type":"message",
                "role":"assistant",
                "status":"completed",
                "content":[
                  {
                    "type":"output_text",
                    "annotations":[],
                    "text":"{\\"content\\":\\"%s\\",\\"evidenceRefs\\":[\\"capture://capture-openai-public-003\\"]}"
                  }
                ]
              }
            ],
            "usage":{
              "input_tokens":150,
              "input_tokens_details":{"cached_tokens":0},
              "output_tokens":30,
              "output_tokens_details":{"reasoning_tokens":10},
              "total_tokens":180
            }
          }
          """.formatted(SYNTHETIC_RESPONSE_SENTINEL);
    }
  }
}
