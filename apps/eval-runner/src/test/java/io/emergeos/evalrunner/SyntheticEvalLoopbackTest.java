package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.ContentHashes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticEvalLoopbackTest {

  @TempDir Path tempDir;

  @Test
  void executesOneBoundPublicCaseEndToEndAndCannotReplay()
      throws Exception {
    List<String> requests = new ArrayList<>();
    List<EvalExecutionObserver.Phase> phases = new ArrayList<>();
    AtomicInteger credentialReads = new AtomicInteger();
    Path home = Files.createDirectory(tempDir.resolve("home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            requests, firstResponse(), secondResponse())) {
      SyntheticEvalExecutor.Dependencies dependencies =
          new SyntheticEvalExecutor.Dependencies(
              exactConsole(),
              home,
              () -> {
                credentialReads.incrementAndGet();
                return "sentinel-loopback-key";
              },
              apiKey -> loopbackClient(apiKey, server.baseUrl()),
              Clock.fixed(
                  Instant.parse("2026-07-30T10:00:00Z"),
                  ZoneOffset.UTC),
              System::nanoTime,
              phases::add);

      SyntheticEvalExecutor.ExecutionResult result =
          new SyntheticEvalExecutor(repoRoot()).execute(dependencies);

      assertEquals(1, result.effects().keyReads());
      assertEquals(1, result.effects().clientFactories());
      assertEquals(1, result.effects().modelFactories());
      assertEquals(1, result.effects().runStarts());
      assertEquals(1, result.effects().permitConsumes());
      assertEquals(
          2, result.effects().providerSdkCreateInvocations());
      assertEquals(
          2, result.effects().providerAttributedInvocations());
      assertEquals(1, credentialReads.get());
      assertEquals(2, requests.size());
      assertEquals(
          List.of(
              EvalExecutionObserver.Phase.PREFLIGHT_VERIFIED,
              EvalExecutionObserver.Phase.TTY_VERIFIED,
              EvalExecutionObserver.Phase.MARKER_CREATED,
              EvalExecutionObserver.Phase.CHALLENGE_VERIFIED,
              EvalExecutionObserver.Phase.PERMIT_ARMED,
              EvalExecutionObserver.Phase.TASK_AUTHORIZED,
              EvalExecutionObserver.Phase.GATE_APPROVED_DURABLE,
              EvalExecutionObserver.Phase.CREDENTIAL_READ_STARTED,
              EvalExecutionObserver.Phase.CLIENT_CREATED,
              EvalExecutionObserver.Phase.MODEL_CREATED,
              EvalExecutionObserver.Phase.TASK_AUTHORIZED,
              EvalExecutionObserver.Phase.RUN_STARTED,
              EvalExecutionObserver.Phase.PERMIT_CONSUMED,
              EvalExecutionObserver.Phase
                  .PROVIDER_SDK_CREATE_INTENT_DURABLE,
              EvalExecutionObserver.Phase.PROVIDER_SDK_CREATE,
              EvalExecutionObserver.Phase.PROVIDER_ATTRIBUTED_DURABLE,
              EvalExecutionObserver.Phase
                  .PROVIDER_SDK_CREATE_INTENT_DURABLE,
              EvalExecutionObserver.Phase.PROVIDER_SDK_CREATE,
              EvalExecutionObserver.Phase.PROVIDER_ATTRIBUTED_DURABLE,
              EvalExecutionObserver.Phase.RUN_RECORD_PENDING_DURABLE,
              EvalExecutionObserver.Phase
                  .RUN_RECORD_LINK_COMMIT_COMPLETE,
              EvalExecutionObserver.Phase.RUN_RECORD_FINAL_DURABLE,
              EvalExecutionObserver.Phase.TERMINAL_JOURNAL_DURABLE),
          phases);
      assertEquals(RunStatus.SUCCEEDED, result.outcome().result().status());
      assertEquals(
          SyntheticEvalCatalog.EXPECTED_TASK_HASH,
          IntegrityHashes.taskHash(result.outcome().run().task()));
      assertEquals(
          SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT,
          result
              .outcome()
              .run()
              .bundle()
              .componentVersions()
              .get("execution-profile-fingerprint"));
      assertEquals(
          SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT,
          result
              .outcome()
              .run()
              .bundle()
              .componentVersions()
              .get("pricing-profile-fingerprint"));
      assertEquals(
          "environment://sha256:"
              + SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256,
          result.outcome().run().bundle().environmentSnapshotRef());
      assertEquals(
          result.outcome().run().bundle().integrityHash(),
          IntegrityHashes.bundleHash(result.outcome().run().bundle()));
      assertEquals(
          new BigDecimal("0.000413"),
          result.outcome().result().costUsd());
      assertEquals(300, result.outcome().result().tokenCount());
      assertTrue(result.receipt().contains("status=SUCCEEDED"));
      assertTrue(
          result.receipt().contains("billingStatus=ATTRIBUTED"));
      assertTrue(
          result.receipt().contains("observedCostUsd=0.000413"));
      assertTrue(result.receipt().contains("observedTokenCount=300"));
      assertTrue(result.receipt().contains("runCostUsd=0.000413"));
      assertTrue(result.receipt().contains("runTokenCount=300"));
      assertTrue(result.receipt().contains("meteringMatchesRun=true"));
      assertFalse(result.receipt().contains("sentinel-loopback-key"));
      assertFalse(result.receipt().contains(SyntheticEvalCatalog.CONTENT));

      PersistedEvidence persisted =
          assertPersistedEvidence(home, result, "ATTRIBUTED", 2, 2);
      assertEquals(
          new BigDecimal("0.000413"),
          persisted.record().path("observedCostUsd").decimalValue());
      assertEquals(
          300,
          persisted.record().path("observedTokenCount").asLong());
      assertEquals(
          "SUCCEEDED",
          persisted.record().at("/run/result/status").asText());
      assertEquals(
          new BigDecimal("0.000413"),
          persisted.record().at("/run/result/costUsd").decimalValue());
      assertEquals(
          new BigDecimal("0.000413"),
          persisted.record().at("/run/bundle/costUsd").decimalValue());
      assertEquals(300, persisted.record().at("/run/bundle/tokenCount").asLong());
      assertEquals(
          List.of(
              "GATE_APPROVED",
              "CREDENTIAL_READ_STARTED",
              "CLIENT_CREATED",
              "PROVIDER_SDK_CREATE_INTENT",
              "PROVIDER_ATTRIBUTED",
              "PROVIDER_SDK_CREATE_INTENT",
              "PROVIDER_ATTRIBUTED",
              "TERMINAL_RUN_RECORD_PERSISTED"),
          persisted.journalEvents());
      assertTrue(
          persisted
              .journalText()
              .contains("billingStatus=ATTRIBUTED"));
      assertTrue(
          persisted
              .journalText()
              .contains("observedCostUsd=0.000413"));

      assertRequest(
          ObjectMappers.jsonMapper().readTree(requests.get(0)), true);
      assertRequest(
          ObjectMappers.jsonMapper().readTree(requests.get(1)), false);

      SyntheticEvalExecutor.Rejected replay =
          assertThrows(
              SyntheticEvalExecutor.Rejected.class,
              () ->
                  new SyntheticEvalExecutor(repoRoot())
                      .execute(dependencies));
      assertEquals("MARKER_ALREADY_EXISTS", replay.code());
      assertEquals(1, credentialReads.get());
      assertEquals(2, requests.size());
    }
  }

  @Test
  void postProviderIntegrityFailureKeepsSafeUnknownBillingReceipt()
      throws Exception {
    List<String> requests = new ArrayList<>();
    String driftedModel =
        "gpt-5.4-mini-2026-03-17-provider-drift";
    Path home = Files.createDirectory(tempDir.resolve("drift-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            requests,
            firstResponse()
                .replace(
                    "gpt-5.4-mini-2026-03-17", driftedModel),
            secondResponse()
                .replace(
                    "gpt-5.4-mini-2026-03-17", driftedModel))) {
      SyntheticEvalExecutor.Rejected rejected =
          assertThrows(
              SyntheticEvalExecutor.Rejected.class,
              () ->
                  new SyntheticEvalExecutor(repoRoot())
                      .execute(
                          new SyntheticEvalExecutor.Dependencies(
                              exactConsole(),
                              home,
                              () -> "sentinel-integrity-key",
                              apiKey ->
                                  loopbackClient(
                                      apiKey, server.baseUrl()),
                              Clock.fixed(
                                  Instant.parse(
                                      "2026-07-30T10:00:00Z"),
                                  ZoneOffset.UTC),
                              System::nanoTime)));

      assertEquals("SUCCESS_INTEGRITY_MISMATCH", rejected.code());
      assertEquals(2, requests.size());
      assertTrue(
          rejected.safeReceipt().contains("billingStatus=UNKNOWN"));
      assertTrue(
          rejected
              .safeReceipt()
              .contains("providerSdkCreateInvocations=2"));
      assertFalse(
          rejected.safeReceipt().contains("sentinel-integrity-key"));
      assertFalse(
          rejected.safeReceipt().contains(SyntheticEvalCatalog.CONTENT));
    }
  }

  @Test
  void disconnectedProviderProducesUnknownBillingNotZeroCostClaim()
      throws Exception {
    List<String> requests = new ArrayList<>();
    Path home =
        Files.createDirectory(tempDir.resolve("disconnect-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(requests, (String) null)) {
      SyntheticEvalExecutor.ExecutionResult result =
          new SyntheticEvalExecutor(repoRoot())
              .execute(
                  new SyntheticEvalExecutor.Dependencies(
                      exactConsole(),
                      home,
                      () -> "sentinel-disconnect-key",
                      apiKey ->
                          loopbackClient(apiKey, server.baseUrl()),
                      Clock.fixed(
                          Instant.parse("2026-07-30T10:00:00Z"),
                          ZoneOffset.UTC),
                      System::nanoTime));

      assertEquals(
          RunStatus.FAILED, result.outcome().result().status());
      assertEquals(
          "MODEL_CALL_OUTCOME_UNKNOWN",
          result.outcome().result().failureReason());
      assertEquals(
          1, result.effects().providerSdkCreateInvocations());
      assertEquals(
          0, result.effects().providerAttributedInvocations());
      assertEquals(1, requests.size());
      assertTrue(
          result.receipt().contains("billingStatus=UNKNOWN"));
      assertTrue(result.receipt().contains("observedCostUsd=0"));
      assertFalse(
          result.receipt().contains("sentinel-disconnect-key"));
      assertFalse(
          result.receipt().contains(SyntheticEvalCatalog.CONTENT));

      PersistedEvidence persisted =
          assertPersistedEvidence(home, result, "UNKNOWN", 1, 0);
      assertEquals(
          "FAILED",
          persisted.record().at("/run/result/status").asText());
      assertEquals(
          "MODEL_CALL_OUTCOME_UNKNOWN",
          persisted
              .record()
              .at("/run/result/failureReason")
              .asText());
      assertEquals(
          BigDecimal.ZERO,
          persisted.record().path("observedCostUsd").decimalValue());
      assertEquals(
          0,
          persisted.record().path("observedTokenCount").asLong());
      assertEquals(
          List.of(
              "GATE_APPROVED",
              "CREDENTIAL_READ_STARTED",
              "CLIENT_CREATED",
              "PROVIDER_SDK_CREATE_INTENT",
              "TERMINAL_RUN_RECORD_PERSISTED"),
          persisted.journalEvents());
      assertTrue(
          persisted.journalText().contains("billingStatus=UNKNOWN"));
      assertFalse(
          persisted.journalText().contains("PROVIDER_ATTRIBUTED"));
    }
  }

  @Test
  void attributedUsageSurvivesLaterDeadlineSanitization()
      throws Exception {
    List<String> requests = new ArrayList<>();
    AtomicLong now = new AtomicLong();
    Path home =
        Files.createDirectory(tempDir.resolve("sanitized-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(requests, firstResponse())) {
      SyntheticEvalExecutor.ExecutionResult result =
          new SyntheticEvalExecutor(repoRoot())
              .execute(
                  new SyntheticEvalExecutor.Dependencies(
                      exactConsole(),
                      home,
                      () -> "sentinel-sanitized-key",
                      apiKey ->
                          loopbackClient(apiKey, server.baseUrl()),
                      Clock.fixed(
                          Instant.parse("2026-07-30T10:00:00Z"),
                          ZoneOffset.UTC),
                      now::get,
                      phase -> {
                        if (phase
                            == EvalExecutionObserver.Phase
                                .PROVIDER_SDK_CREATE) {
                          now.set(
                              Duration.ofSeconds(31).toNanos());
                        }
                      }));

      assertEquals(1, requests.size());
      assertEquals(
          RunStatus.FAILED, result.outcome().result().status());
      assertEquals(
          "UNSAFE_AGENT_OUTCOME",
          result.outcome().result().failureReason());
      assertEquals(BigDecimal.ZERO, result.outcome().result().costUsd());
      assertEquals(0, result.outcome().result().tokenCount());
      assertEquals(
          new BigDecimal("0.000165"),
          result.effects().providerObservedCostUsd());
      assertEquals(
          120, result.effects().providerObservedTokenCount());
      assertEquals(
          1, result.effects().providerSdkCreateInvocations());
      assertEquals(
          1, result.effects().providerAttributedInvocations());
      assertTrue(
          result.receipt().contains("billingStatus=ATTRIBUTED"));
      assertTrue(
          result.receipt().contains("observedCostUsd=0.000165"));
      assertTrue(
          result.receipt().contains("observedTokenCount=120"));
      assertTrue(result.receipt().contains("runCostUsd=0"));
      assertTrue(result.receipt().contains("runTokenCount=0"));
      assertTrue(
          result.receipt().contains("meteringMatchesRun=false"));

      PersistedEvidence persisted =
          assertPersistedEvidence(home, result, "ATTRIBUTED", 1, 1);
      assertEquals(
          new BigDecimal("0.000165"),
          persisted.record().path("observedCostUsd").decimalValue());
      assertEquals(
          120,
          persisted.record().path("observedTokenCount").asLong());
      assertEquals(
          BigDecimal.ZERO,
          persisted.record().path("runCostUsd").decimalValue());
      assertEquals(0, persisted.record().path("runTokenCount").asLong());
      assertFalse(
          persisted.record().path("meteringMatchesRun").asBoolean());
      assertEquals(
          List.of(
              "GATE_APPROVED",
              "CREDENTIAL_READ_STARTED",
              "CLIENT_CREATED",
              "PROVIDER_SDK_CREATE_INTENT",
              "PROVIDER_ATTRIBUTED",
              "TERMINAL_RUN_RECORD_PERSISTED"),
          persisted.journalEvents());
      assertTrue(
          persisted
              .journalText()
              .contains("observedCostUsd=0.000165"));
      assertTrue(
          persisted
              .journalText()
              .contains("observedTokenCount=120"));
      assertTrue(
          persisted
              .journalText()
              .contains("meteringMatchesRun=false"));
    }
  }

  @Test
  void providerUsageAboveReservationIsPreservedAndFailsClosed()
      throws Exception {
    List<String> requests = new ArrayList<>();
    Path home =
        Files.createDirectory(tempDir.resolve("overage-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            requests, firstResponseWithUsageOverage())) {
      SyntheticEvalExecutor.ExecutionResult result =
          new SyntheticEvalExecutor(repoRoot())
              .execute(
                  new SyntheticEvalExecutor.Dependencies(
                      exactConsole(),
                      home,
                      () -> "sentinel-overage-key",
                      apiKey ->
                          loopbackClient(apiKey, server.baseUrl()),
                      Clock.fixed(
                          Instant.parse("2026-07-30T10:00:00Z"),
                          ZoneOffset.UTC),
                      System::nanoTime));

      assertEquals(1, requests.size());
      assertEquals(
          RunStatus.BLOCKED, result.outcome().result().status());
      assertEquals(
          "MODEL_BUDGET_EXHAUSTED",
          result.outcome().result().failureReason());
      assertEquals(
          new BigDecimal("0.450090"),
          result.effects().providerObservedCostUsd());
      assertEquals(
          600_020, result.effects().providerObservedTokenCount());
      assertTrue(
          result
                  .effects()
                  .providerObservedCostUsd()
                  .compareTo(
                      SyntheticEvalCatalog.profile().reservationUsd())
              > 0);
      assertEquals(
          result.effects().providerObservedCostUsd(),
          result.outcome().result().costUsd());
      assertEquals(
          result.effects().providerObservedTokenCount(),
          result.outcome().result().tokenCount());
      assertTrue(
          result.receipt().contains("billingStatus=ATTRIBUTED"));
      assertTrue(
          result.receipt().contains("observedCostUsd=0.450090"));
      assertTrue(
          result.receipt().contains("observedTokenCount=600020"));
      assertTrue(
          result.receipt().contains("meteringMatchesRun=true"));

      PersistedEvidence persisted =
          assertPersistedEvidence(home, result, "ATTRIBUTED", 1, 1);
      assertEquals(
          0,
          new BigDecimal("0.450090")
              .compareTo(
                  persisted.record().path("observedCostUsd").decimalValue()));
      assertEquals(
          600_020,
          persisted.record().path("observedTokenCount").asLong());
      assertTrue(
          persisted
              .journalText()
              .contains("observedCostUsd=0.450090"));
      assertTrue(
          persisted
              .journalText()
              .contains("observedTokenCount=600020"));
    }
  }

  @Test
  void targetCreatedAfterPrecheckIsNeverOverwritten()
      throws Exception {
    List<String> requests = new ArrayList<>();
    AtomicReference<Object> competingFileKey =
        new AtomicReference<>();
    Path home =
        Files.createDirectory(tempDir.resolve("target-race-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            requests, firstResponse(), secondResponse())) {
      SyntheticEvalExecutor.Rejected rejected =
          assertThrows(
              SyntheticEvalExecutor.Rejected.class,
              () ->
                  new SyntheticEvalExecutor(repoRoot())
                      .execute(
                          new SyntheticEvalExecutor.Dependencies(
                              exactConsole(),
                              home,
                              () -> "sentinel-target-race-key",
                              apiKey ->
                                  loopbackClient(
                                      apiKey, server.baseUrl()),
                              Clock.fixed(
                                  Instant.parse(
                                      "2026-07-30T10:00:00Z"),
                                  ZoneOffset.UTC),
                              System::nanoTime,
                              phase -> {
                                if (phase
                                    == EvalExecutionObserver.Phase
                                        .RUN_RECORD_PENDING_DURABLE) {
                                  competingFileKey.set(
                                      createCompetingRunRecord(home));
                                }
                              })));

      assertEquals("RUN_RECORD_ALREADY_EXISTS", rejected.code());
      assertEquals(2, requests.size());
      assertTrue(
          rejected.safeReceipt().contains("billingStatus=UNKNOWN"));
      assertFalse(
          rejected.safeReceipt().contains("sentinel-target-race-key"));
      assertFalse(
          rejected.safeReceipt().contains(SyntheticEvalCatalog.CONTENT));
      Path directory = attemptDirectory(home);
      Path target = runRecord(directory);
      Path pending =
          directory.resolve(
              SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                  + ".run.json.pending");
      assertEquals(
          competingFileKey.get(),
          Files.readAttributes(
                  target,
                  BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS)
              .fileKey());
      assertEquals(
          "{\"competitor\":true}",
          Files.readString(target, StandardCharsets.US_ASCII));
      assertTrue(
          Files.isRegularFile(
              pending, LinkOption.NOFOLLOW_LINKS));
      assertOwnerPrivate(pending, directory);
      String journal = Files.readString(journal(directory));
      assertFalse(journal.contains("TERMINAL_RUN_RECORD_PERSISTED"));
      assertFalse(journal.contains("sentinel-target-race-key"));

      PosixAttemptJournalVerifier.Verification verification =
          new PosixAttemptJournalVerifier()
              .verify(
                  directory.resolve(
                      SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                          + ".attempt"));
      assertEquals(
          PosixAttemptJournalVerifier.Verdict.INVALID,
          verification.verdict());
      assertEquals("RUN_RECORD_STATE_INVALID", verification.code());
    }
  }

  @Test
  void preexistingPartialPendingRecordCannotBecomeTerminalEvidence()
      throws Exception {
    List<String> requests = new ArrayList<>();
    AtomicInteger providerCreates = new AtomicInteger();
    Path home =
        Files.createDirectory(tempDir.resolve("partial-record-home"));
    try (LoopbackResponsesServer server =
        new LoopbackResponsesServer(
            requests, firstResponse(), secondResponse())) {
      SyntheticEvalExecutor.Rejected rejected =
          assertThrows(
              SyntheticEvalExecutor.Rejected.class,
              () ->
                  new SyntheticEvalExecutor(repoRoot())
                      .execute(
                          new SyntheticEvalExecutor.Dependencies(
                              exactConsole(),
                              home,
                              () -> "sentinel-partial-key",
                              apiKey ->
                                  loopbackClient(
                                      apiKey, server.baseUrl()),
                              Clock.fixed(
                                  Instant.parse(
                                      "2026-07-30T10:00:00Z"),
                                  ZoneOffset.UTC),
                              System::nanoTime,
                              phase -> {
                                if (phase
                                        == EvalExecutionObserver.Phase
                                            .PROVIDER_SDK_CREATE
                                    && providerCreates.incrementAndGet()
                                        == 2) {
                                  createPartialPending(home);
                                }
                              })));

      assertEquals("RUN_RECORD_PENDING_EXISTS", rejected.code());
      assertEquals(2, requests.size());
      assertTrue(
          rejected.safeReceipt().contains("billingStatus=UNKNOWN"));
      Path directory = attemptDirectory(home);
      assertFalse(Files.exists(runRecord(directory)));
      Path pending =
          directory.resolve(
              SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                  + ".run.json.pending");
      assertEquals("{\"partial\":true}", Files.readString(pending));
      assertOwnerPrivate(pending, directory);
      String journal = Files.readString(journal(directory));
      assertFalse(journal.contains("TERMINAL_RUN_RECORD_PERSISTED"));
      assertFalse(journal.contains("sentinel-partial-key"));
    }
  }

  private static PersistedEvidence assertPersistedEvidence(
      Path home,
      SyntheticEvalExecutor.ExecutionResult result,
      String expectedBillingStatus,
      int expectedSdkCreates,
      int expectedAttributions)
      throws Exception {
    Path directory = attemptDirectory(home);
    Path recordPath = runRecord(directory);
    Path journalPath = journal(directory);
    assertTrue(Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS));
    assertFalse(
        Files.exists(
            directory.resolve(
                SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                    + ".run.json.pending")));
    assertOwnerPrivate(recordPath, directory);
    assertOwnerPrivate(journalPath, directory);

    byte[] recordBytes = Files.readAllBytes(recordPath);
    JsonNode record = ObjectMappers.jsonMapper().readTree(recordBytes);
    String recordHash = sha256(recordBytes);
    assertEquals("1.0", record.path("schemaVersion").asText());
    assertEquals(
        SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID,
        record.path("attemptId").asText());
    assertEquals(
        SyntheticEvalCatalog.PACK_RAW_SHA256,
        record.path("packSha256").asText());
    assertEquals(
        SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256,
        record.path("environmentSha256").asText());
    assertEquals(
        expectedBillingStatus,
        record.path("billingStatus").asText());
    assertEquals(
        expectedSdkCreates,
        record.path("providerSdkCreateInvocations").asInt());
    assertEquals(
        expectedAttributions,
        record.path("providerAttributedInvocations").asInt());
    assertEquals(
        expectedSdkCreates,
        record.at("/effects/providerSdkCreateInvocations").asInt());
    assertEquals(
        expectedAttributions,
        record.at("/effects/providerAttributedInvocations").asInt());
    assertEquals(
        record.path("observedCostUsd").decimalValue(),
        record.at("/effects/providerObservedCostUsd").decimalValue());
    assertEquals(
        record.path("observedTokenCount").asLong(),
        record.at("/effects/providerObservedTokenCount").asLong());
    assertEquals(
        result.outcome().run().bundle().integrityHash(),
        record.path("bundleHash").asText());
    assertEquals(
        result.outcome().run().trace().rootHash(),
        record.path("traceRootHash").asText());
    assertEquals(
        record.path("bundleHash").asText(),
        record.at("/run/bundle/integrityHash").asText());
    assertEquals(
        record.path("traceRootHash").asText(),
        record.at("/run/trace/rootHash").asText());
    if (!record.path("artifactContentHash").isNull()) {
      String artifactContent =
          record.at("/artifact/versions/0/content").asText();
      assertEquals(
          ContentHashes.sha256(artifactContent),
          record.path("artifactContentHash").asText());
      assertEquals(
          record.path("artifactContentHash").asText(),
          record
              .at("/artifact/versions/0/contentHash")
              .asText());
    }

    HarnessRunBundle bundle =
        ObjectMappers.jsonMapper()
            .treeToValue(
                record.at("/run/bundle"), HarnessRunBundle.class);
    AgentTraceEnvelope trace =
        ObjectMappers.jsonMapper()
            .treeToValue(
                record.at("/run/trace"), AgentTraceEnvelope.class);
    assertEquals(bundle.integrityHash(), IntegrityHashes.bundleHash(bundle));
    assertEquals(trace.rootHash(), bundle.traceRootHash());

    String recordText =
        new String(recordBytes, StandardCharsets.UTF_8);
    assertFalse(recordText.contains("sentinel-"));
    assertFalse(recordText.contains("resp-eval-"));
    assertFalse(recordText.contains("loopback-ciphertext"));
    assertFalse(recordText.contains("encrypted_content"));
    assertTrue(
        result.receipt().contains("runRecordPersisted=true"));
    assertTrue(result.receipt().contains("runRecordSha256=" + recordHash));

    String journalText = Files.readString(journalPath);
    List<String> journalEvents =
        verifyJournalHashChain(journalText);
    String latestJournalHash =
        parseJournalLine(
                journalText
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList()
                    .getLast())
            .get("eventHash");
    assertFalse(journalText.contains("sentinel-"));
    assertFalse(journalText.contains(SyntheticEvalCatalog.CONTENT));
    assertFalse(journalText.contains("resp-eval-"));
    assertFalse(journalText.contains("loopback-ciphertext"));
    assertTrue(
        journalText.contains("runRecordSha256=" + recordHash));
    assertTrue(
        result
            .receipt()
            .contains(
                "attemptJournalLatestHash="
                    + latestJournalHash));
    PosixAttemptJournalVerifier.Verification verification =
        new PosixAttemptJournalVerifier()
            .verify(
                directory.resolve(
                    SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                        + ".attempt"));
    assertEquals(
        PosixAttemptJournalVerifier.Verdict.VERIFIED,
        verification.verdict());
    assertTrue(verification.verified());
    assertEquals(recordHash, verification.terminal().runRecordSha256());
    return new PersistedEvidence(
        record, journalText, journalEvents);
  }

  private static List<String> verifyJournalHashChain(
      String journalText) {
    List<String> lines =
        journalText.lines().filter(line -> !line.isBlank()).toList();
    String previous =
        ContentHashes.sha256(
            "emergeos.eval-attempt-journal.genesis.v1\n"
                + "attemptId="
                + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
    List<String> events = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      Map<String, String> fields = parseJournalLine(lines.get(index));
      int sequence = index + 1;
      assertEquals(
          Integer.toString(sequence), fields.remove("sequence"));
      String event = fields.remove("event");
      assertEquals(previous, fields.remove("previousHash"));
      String observedHash = fields.remove("eventHash");
      StringBuilder material =
          new StringBuilder()
              .append("emergeos.eval-attempt-journal.event.v1\n")
              .append("attemptId=")
              .append(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID)
              .append('\n')
              .append("sequence=")
              .append(sequence)
              .append('\n')
              .append("event=")
              .append(event)
              .append('\n')
              .append("previousHash=")
              .append(previous)
              .append('\n');
      new TreeMap<>(fields)
          .forEach(
              (name, value) ->
                  material
                      .append(name)
                      .append('=')
                      .append(value)
                      .append('\n'));
      assertEquals(
          ContentHashes.sha256(material.toString()), observedHash);
      previous = observedHash;
      events.add(event);
    }
    return events;
  }

  private static Map<String, String> parseJournalLine(String line) {
    Map<String, String> fields = new LinkedHashMap<>();
    Arrays.stream(line.split(" "))
        .forEach(
            item -> {
              int separator = item.indexOf('=');
              assertTrue(separator > 0);
              String previous =
                  fields.put(
                      item.substring(0, separator),
                      item.substring(separator + 1));
              assertEquals(null, previous);
            });
    return fields;
  }

  private static void assertOwnerPrivate(Path file, Path directory)
      throws IOException {
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    assertEquals(
        Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS),
        Files.getOwner(file, LinkOption.NOFOLLOW_LINKS));
    var owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
    AclFileAttributeView acl =
        Files.getFileAttributeView(
            file,
            AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
    if (acl != null) {
      assertTrue(
          acl.getAcl().stream()
              .filter(entry -> entry.type() == AclEntryType.ALLOW)
              .allMatch(
                  entry ->
                      entry
                          .principal()
                          .equals(owner)));
    }
  }

  private static void createPartialPending(Path home) {
    Path pending =
        attemptDirectory(home)
            .resolve(
                SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                    + ".run.json.pending");
    try {
      Files.createFile(
          pending,
          PosixFilePermissions.asFileAttribute(
              PosixFilePermissions.fromString("rw-------")));
      Files.writeString(
          pending,
          "{\"partial\":true}",
          StandardOpenOption.WRITE);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static Object createCompetingRunRecord(Path home) {
    Path target = runRecord(attemptDirectory(home));
    try {
      Files.createFile(
          target,
          PosixFilePermissions.asFileAttribute(
              PosixFilePermissions.fromString("rw-------")));
      Files.writeString(
          target,
          "{\"competitor\":true}",
          StandardCharsets.US_ASCII,
          StandardOpenOption.WRITE);
      try (FileChannel channel =
          FileChannel.open(target, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try (FileChannel channel =
          FileChannel.open(
              target.getParent(), StandardOpenOption.READ)) {
        channel.force(true);
      }
      Object fileKey =
          Files.readAttributes(
                  target,
                  BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS)
              .fileKey();
      if (fileKey == null) {
        throw new IOException("file identity unavailable");
      }
      return fileKey;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static Path attemptDirectory(Path home) {
    return home.resolve(".emergeos/eval-attempts");
  }

  private static Path runRecord(Path directory) {
    return directory.resolve(
        SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".run.json");
  }

  private static Path journal(Path directory) {
    return directory.resolve(
        SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".journal");
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

  private record PersistedEvidence(
      JsonNode record,
      String journalText,
      List<String> journalEvents) {}

  private static void assertRequest(JsonNode request, boolean first) {
    assertEquals(
        "gpt-5.4-mini-2026-03-17",
        request.path("model").asText());
    assertEquals(1_000, request.path("max_output_tokens").asInt());
    assertFalse(request.path("store").asBoolean(true));
    assertFalse(request.path("parallel_tool_calls").asBoolean(true));
    assertEquals("default", request.path("service_tier").asText());
    assertFalse(request.has("prompt_cache_options"));
    assertFalse(request.has("previous_response_id"));
    assertEquals(
        first ? "capture_read" : "none",
        first
            ? request.at("/tool_choice/name").asText()
            : request.path("tool_choice").asText());
  }

  private static OpenAIClient loopbackClient(
      String apiKey, String baseUrl) {
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .maxRetries(0)
        .timeout(Duration.ofSeconds(2))
        .build();
  }

  private static OneShotOperatorGate.InteractiveConsole exactConsole() {
    return new OneShotOperatorGate.InteractiveConsole() {
      @Override
      public boolean available() {
        return true;
      }

      @Override
      public String readLine(String prompt) {
        return OneShotOperatorGate.CHALLENGE_PREFIX
            + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID;
      }
    };
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
                  "text":"{\\"content\\":\\"Lumen Note 把虚构灵感整理成可追溯的创作卡片，让每一步来源清楚、结果可继续编辑。\\",\\"evidenceRefs\\":[\\"capture://capture-openai-public-003\\"]}"
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
        """;
  }

  private static String firstResponseWithUsageOverage() {
    return firstResponse()
        .replace("\"input_tokens\":100", "\"input_tokens\":600000")
        .replace("\"total_tokens\":120", "\"total_tokens\":600020");
  }

  private static Path repoRoot() {
    Path current =
        Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("evals/task-packs"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }

  private static final class LoopbackResponsesServer
      implements AutoCloseable {
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
            String response =
                responses[Math.min(index, responses.length - 1)];
            if (response == null) {
              exchange.close();
              return;
            }
            respond(exchange, response);
          });
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/v1";
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private static void respond(HttpExchange exchange, String body)
        throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange
          .getResponseHeaders()
          .set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }
}
