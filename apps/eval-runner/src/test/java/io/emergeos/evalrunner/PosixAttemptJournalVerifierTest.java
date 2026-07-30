package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CancellationSignal;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PosixAttemptJournalVerifierTest {

  private static final BigDecimal OBSERVED_COST =
      new BigDecimal("0.000165");

  @TempDir Path tempDir;

  @Test
  void verifiesCompleteTerminalJournalAgainstRecord() throws Exception {
    Fixture fixture = completeFixture(120, 120, true);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.VERIFIED,
        result.verdict(),
        result.toString());
    assertEquals("TERMINAL_EVIDENCE_VERIFIED", result.code());
    assertTrue(result.verified());
    assertEquals("ATTRIBUTED", result.terminal().billingStatus());
    assertEquals(120, result.terminal().observedTokenCount());
    assertEquals(fixture.recordHash(), result.terminal().runRecordSha256());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.ATTRIBUTED,
        result.billing().status());
    assertTrue(result.billing().trustedPrefix());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.TERMINAL_LINKED,
        result.recordState());
  }

  @Test
  void tornTailIsUnknownAndNeverVerified() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Files.delete(fixture.record());
    Files.writeString(
        fixture.journal(),
        "sequence=6 event=TERMINAL_RUN_RECORD_",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals("JOURNAL_TORN_TAIL", result.code());
    assertFalse(result.verified());
    assertNull(result.terminal());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.ATTRIBUTED,
        result.billing().status());
    assertTrue(result.billing().trustedPrefix());
    assertEquals(OBSERVED_COST, result.billing().observedCostUsd());
    assertEquals(120, result.billing().observedTokenCount());
    assertEquals(1, result.billing().providerSdkCreateInvocations());
    assertEquals(1, result.billing().providerAttributedInvocations());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.ABSENT,
        result.recordState());
  }

  @Test
  void missingTerminalIsUnknownAndNeverVerified() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals("TERMINAL_EVENT_MISSING", result.code());
    assertFalse(result.verified());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.ATTRIBUTED,
        result.billing().status());
    assertTrue(result.billing().trustedPrefix());
    assertEquals(OBSERVED_COST, result.billing().observedCostUsd());
    assertEquals(120, result.billing().observedTokenCount());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.FINAL_UNSEALED,
        result.recordState());
  }

  @Test
  void unmatchedIntentKeepsBillingUnknownWithTrustedCounts()
      throws Exception {
    Fixture fixture = partialFixture(PartialPhase.INTENT);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals("TERMINAL_EVENT_MISSING", result.code());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.UNKNOWN,
        result.billing().status());
    assertTrue(result.billing().trustedPrefix());
    assertEquals(BigDecimal.ZERO, result.billing().observedCostUsd());
    assertEquals(0, result.billing().observedTokenCount());
    assertEquals(1, result.billing().providerSdkCreateInvocations());
    assertEquals(0, result.billing().providerAttributedInvocations());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.ABSENT,
        result.recordState());
  }

  @Test
  void noProviderIntentProvesNotInvokedOnValidPrefix()
      throws Exception {
    Fixture fixture = partialFixture(PartialPhase.GATE);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.NOT_INVOKED,
        result.billing().status());
    assertTrue(result.billing().trustedPrefix());
    assertEquals(BigDecimal.ZERO, result.billing().observedCostUsd());
    assertEquals(0, result.billing().providerSdkCreateInvocations());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.ABSENT,
        result.recordState());
  }

  @Test
  void completeMalformedLineIsInvalid() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Files.writeString(
        fixture.journal(),
        "sequence=6 definitely-not-a-field\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_LINE_MALFORMED", result.code());
    assertFalse(result.verified());
  }

  @Test
  void nonContiguousSequenceIsInvalidEvenWithMatchingHash()
      throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    List<String> existing =
        Files.readAllLines(fixture.journal(), StandardCharsets.UTF_8);
    String previousHash =
        field(existing.getLast(), "eventHash");
    String invalidTerminal =
        eventLine(
            7,
            "TERMINAL_RUN_RECORD_PERSISTED",
            previousHash,
            terminalFields(
                120,
                fixture.recordHash(),
                fixture.bundleHash()));
    Files.writeString(
        fixture.journal(),
        invalidTerminal,
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_SEQUENCE_INVALID", result.code());
  }

  @Test
  void alteredJournalMaterialBreaksHashChain() throws Exception {
    Fixture fixture = completeFixture(120, 120, true);
    String journal =
        Files.readString(fixture.journal(), StandardCharsets.US_ASCII);
    Files.writeString(
        fixture.journal(),
        journal.replace(
            "caseId=" + SyntheticEvalCatalog.CASE_ID,
            "caseId=altered-case"),
        StandardCharsets.US_ASCII);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_HASH_CHAIN_INVALID", result.code());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.UNKNOWN,
        result.billing().status());
    assertFalse(result.billing().trustedPrefix());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState.INVALID,
        result.recordState());
  }

  @Test
  void incompleteDomainRecordCannotBeVerifiedEvenWhenJournalHashMatches()
      throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Files.writeString(
        fixture.record(),
        "{\"schemaVersion\":\"1.0\"}",
        StandardCharsets.US_ASCII);
    appendTerminalForCurrentRecord(fixture);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("TERMINAL_RECORD_MALFORMED", result.code());
    assertFalse(result.billing().trustedPrefix());
  }

  @Test
  void duplicateTopLevelJsonKeyIsInvalid() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    String record =
        Files.readString(fixture.record(), StandardCharsets.UTF_8);
    Files.writeString(
        fixture.record(),
        record.replaceFirst(
            "\"schemaVersion\":\"1.0\"",
            "\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\""),
        StandardCharsets.UTF_8);
    appendTerminalForCurrentRecord(fixture);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("TERMINAL_RECORD_MALFORMED", result.code());
  }

  @Test
  void duplicateNestedJsonKeyIsInvalid() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    String record =
        Files.readString(fixture.record(), StandardCharsets.UTF_8);
    Files.writeString(
        fixture.record(),
        record.replaceFirst(
            "\"run\":\\{",
            "\"run\":{\"runId\":\"duplicate\","),
        StandardCharsets.UTF_8);
    appendTerminalForCurrentRecord(fixture);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("TERMINAL_RECORD_MALFORMED", result.code());
  }

  @Test
  void oversizedLineIsRejectedBeforeUnboundedParsing() throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Files.writeString(
        fixture.journal(),
        "x=" + "a".repeat(20_000) + "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_LINE_TOO_LARGE", result.code());
  }

  @Test
  void oversizedJournalIsRejectedBeforeReadingWholeFile()
      throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Files.writeString(
        fixture.journal(),
        "a".repeat(257 * 1024),
        StandardCharsets.US_ASCII);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_TOO_LARGE", result.code());
  }

  @Test
  void unsafeJournalPermissionsAreInvalid() throws Exception {
    Fixture fixture = completeFixture(120, 120, true);
    Files.setPosixFilePermissions(
        fixture.journal(),
        PosixFilePermissions.fromString("rw-r--r--"));

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("JOURNAL_PATH_UNSAFE", result.code());
  }

  @Test
  void missingRecordAfterTerminalIsUnknown() throws Exception {
    Fixture fixture = completeFixture(120, 120, true);
    Files.delete(fixture.record());

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals("TERMINAL_RECORD_MISSING", result.code());
    assertFalse(result.verified());
  }

  @Test
  void pendingRecordWithoutTerminalRemainsNonAuthoritative()
      throws Exception {
    Fixture fixture = completeFixture(120, 120, false);
    Path pending =
        fixture.record().resolveSibling(
            fixture.record().getFileName() + ".pending");
    Files.move(fixture.record(), pending);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.UNKNOWN,
        result.verdict());
    assertEquals(
        PosixAttemptJournalVerifier.RecordState
            .PENDING_NON_AUTHORITATIVE,
        result.recordState());
    assertEquals(
        PosixAttemptJournalVerifier.BillingStatus.ATTRIBUTED,
        result.billing().status());
  }

  @Test
  void terminalAndRecordSemanticMismatchIsInvalidEvenWhenHashMatches()
      throws Exception {
    Fixture fixture = completeFixture(121, 120, true);

    PosixAttemptJournalVerifier.Verification result =
        new PosixAttemptJournalVerifier().verify(fixture.marker());

    assertEquals(
        PosixAttemptJournalVerifier.Verdict.INVALID,
        result.verdict());
    assertEquals("TERMINAL_RECORD_MISMATCH", result.code());
    assertFalse(result.verified());
  }

  private Fixture completeFixture(
      long recordObservedTokens,
      long terminalObservedTokens,
      boolean includeTerminal)
      throws Exception {
    Fixture partial = partialFixture(PartialPhase.ATTRIBUTED);
    Path marker = partial.marker();
    Path journalPath = partial.journal();
    Path record = partial.record();
    AgentDraftOutcome outcome = validFailedOutcome();
    SyntheticEvalExecutor.Effects effects =
        new SyntheticEvalExecutor.Effects(
            1,
            1,
            1,
            1,
            1,
            1,
            1,
            OBSERVED_COST,
            recordObservedTokens);
    PosixEvalRunRecordStore.Stored stored =
        new PosixEvalRunRecordStore().save(marker, outcome, effects);
    if (includeTerminal) {
      PosixAttemptJournal journal = partial.openJournal();
      journal.terminalRecordPersisted(
          outcome.result().status(),
          outcome.run().bundle().integrityHash(),
          "ATTRIBUTED",
          OBSERVED_COST,
          terminalObservedTokens,
          true,
          1,
          1,
          stored);
    }
    return new Fixture(
        marker,
        journalPath,
        record,
        stored.sha256(),
        outcome.run().bundle().integrityHash(),
        partial.openJournal());
  }

  private Fixture partialFixture(PartialPhase phase)
      throws Exception {
    Path directory = Files.createDirectory(tempDir.resolve("attempts"));
    Files.setPosixFilePermissions(
        directory, PosixFilePermissions.fromString("rwx------"));
    Path marker =
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".attempt");
    Files.writeString(marker, "burned", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        marker, PosixFilePermissions.fromString("rw-------"));
    PosixAttemptJournal journal = PosixAttemptJournal.open(marker);
    if (phase.ordinal() >= PartialPhase.CREDENTIAL.ordinal()) {
      journal.credentialReadStarted();
    }
    if (phase.ordinal() >= PartialPhase.CLIENT.ordinal()) {
      journal.clientCreated();
    }
    if (phase.ordinal() >= PartialPhase.INTENT.ordinal()) {
      journal.providerInvocationIntent(1);
    }
    if (phase.ordinal() >= PartialPhase.ATTRIBUTED.ordinal()) {
      journal.providerAttributed(
          1,
          SyntheticEvalCatalog.profile().modelRequested(),
          OBSERVED_COST,
          120);
    }
    return new Fixture(
        marker,
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".journal"),
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".run.json"),
        null,
        null,
        journal);
  }

  private static AgentDraftOutcome validFailedOutcome() {
    Instant instant = Instant.parse("2026-07-30T10:00:00Z");
    Capture capture =
        new Capture(
            SyntheticEvalCatalog.CAPTURE_ID,
            SyntheticEvalCatalog.PRINCIPAL_ID,
            SyntheticEvalCatalog.CLIENT_NONCE,
            SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
            SyntheticEvalCatalog.CONTENT,
            CaptureSourceType.TEXT,
            SyntheticEvalCatalog.SOURCE_REF,
            DataClass.PUBLIC,
            instant);
    SingleRunStores stores = new SingleRunStores(capture);
    var profile = SyntheticEvalCatalog.profile();
    AgentKernel kernel =
        new AgentKernel() {
          @Override
          public AgentRunOutcome run(
              TaskEnvelope task,
              CancellationSignal cancellation) {
            return new AgentRunOutcome(
                RunStatus.FAILED,
                null,
                List.of(),
                List.of(),
                profile.modelRequested(),
                OBSERVED_COST,
                120,
                10,
                "MODEL_PROTOCOL_INVALID");
          }

          @Override
          public String executionProfileId() {
            return profile.id();
          }

          @Override
          public String executionProfileFingerprint() {
            return profile.fingerprint();
          }
        };
    AgentDraftService service =
        new AgentDraftService(
            kernel,
            stores.runs(),
            stores.captures(),
            new FrozenEvalIds(),
            Clock.fixed(instant, ZoneOffset.UTC),
            profile,
            task -> {
              if (!SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
                  IntegrityHashes.taskHash(task))) {
                throw new IllegalArgumentException(
                    "unexpected task");
              }
            });
    return service.draft(
        new AgentDraftCommand(
            SyntheticEvalCatalog.PRINCIPAL_ID,
            SyntheticEvalCatalog.CAPTURE_ID,
            SyntheticEvalCatalog.INTENT));
  }

  private static void appendTerminalForCurrentRecord(
      Fixture fixture) throws IOException {
    String recordHash = sha256(Files.readAllBytes(fixture.record()));
    fixture
        .openJournal()
        .terminalRecordPersisted(
            RunStatus.FAILED,
            fixture.bundleHash(),
            "ATTRIBUTED",
            OBSERVED_COST,
            120,
            true,
            1,
            1,
            new PosixEvalRunRecordStore.Stored(
                fixture.record().getFileName().toString(),
                recordHash));
  }

  private static Map<String, String> terminalFields(
      long observedTokens, String recordHash, String bundleHash) {
    return Map.ofEntries(
        Map.entry("status", "FAILED"),
        Map.entry("bundleHash", bundleHash),
        Map.entry("billingStatus", "ATTRIBUTED"),
        Map.entry("observedCostUsd", "0.000165"),
        Map.entry("observedTokenCount", Long.toString(observedTokens)),
        Map.entry("meteringMatchesRun", "true"),
        Map.entry("providerSdkCreateInvocations", "1"),
        Map.entry("providerAttributedInvocations", "1"),
        Map.entry(
            "runRecordFile",
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".run.json"),
        Map.entry("runRecordSha256", recordHash));
  }

  private static String addEvent(
      List<String> lines,
      int sequence,
      String event,
      String previousHash,
      Map<String, String> fields) {
    String line = eventLine(sequence, event, previousHash, fields);
    lines.add(line);
    return field(line, "eventHash");
  }

  private static String eventLine(
      int sequence,
      String event,
      String previousHash,
      Map<String, String> fields) {
    TreeMap<String, String> ordered = new TreeMap<>(fields);
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
            .append(previousHash)
            .append('\n');
    ordered.forEach(
        (name, value) ->
            material.append(name).append('=').append(value).append('\n'));
    String eventHash = ContentHashes.sha256(material.toString());
    StringBuilder line =
        new StringBuilder()
            .append("sequence=")
            .append(sequence)
            .append(" event=")
            .append(event)
            .append(" previousHash=")
            .append(previousHash);
    ordered.forEach(
        (name, value) ->
            line.append(' ').append(name).append('=').append(value));
    return line.append(" eventHash=").append(eventHash).append('\n').toString();
  }

  private static String field(String line, String name) {
    for (String item : line.strip().split(" ")) {
      int separator = item.indexOf('=');
      if (separator > 0 && name.equals(item.substring(0, separator))) {
        return item.substring(separator + 1);
      }
    }
    throw new AssertionError("missing field " + name);
  }

  private static String genesisHash() {
    return ContentHashes.sha256(
        "emergeos.eval-attempt-journal.genesis.v1\n"
            + "attemptId="
            + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
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

  private record Fixture(
      Path marker,
      Path journal,
      Path record,
      String recordHash,
      String bundleHash,
      PosixAttemptJournal openJournal) {}

  private enum PartialPhase {
    GATE,
    CREDENTIAL,
    CLIENT,
    INTENT,
    ATTRIBUTED
  }
}
