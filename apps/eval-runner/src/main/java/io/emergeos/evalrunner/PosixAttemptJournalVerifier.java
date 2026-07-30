package io.emergeos.evalrunner;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ContentHashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only verifier for the local synthetic-eval attempt journal.
 *
 * <p>The verifier deliberately has no credential, model-client, or network
 * dependency. A terminal result is returned as {@link Verdict#VERIFIED} only
 * after the journal and its referenced immutable run record agree. Incomplete
 * crash evidence remains {@link Verdict#UNKNOWN}; malformed or contradictory
 * evidence is {@link Verdict#INVALID}.
 */
final class PosixAttemptJournalVerifier {

  private static final long MAX_JOURNAL_BYTES = 256 * 1024;
  private static final int MAX_JOURNAL_LINE_BYTES = 16 * 1024;
  private static final long MAX_RECORD_BYTES = 1024 * 1024;
  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  Verification verify(Path marker) {
    Objects.requireNonNull(marker, "marker");
    try {
      return verifyChecked(marker);
    } catch (InvalidEvidence invalid) {
      return Verification.invalid(invalid.code);
    } catch (IOException | RuntimeException failure) {
      return Verification.unknown(
          "EVIDENCE_READ_FAILED",
          BillingEvidence.untrusted(),
          RecordState.UNASSESSED);
    }
  }

  private static Verification verifyChecked(Path marker)
      throws IOException {
    Path canonicalMarker = marker.toAbsolutePath().normalize();
    Path directory = canonicalMarker.getParent();
    String expectedMarkerName =
        SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".attempt";
    if (directory == null
        || !expectedMarkerName.equals(
            canonicalMarker.getFileName().toString())
        || !safeDirectory(directory)
        || !safeRegularFile(canonicalMarker)) {
      throw invalid("JOURNAL_PATH_UNSAFE");
    }
    UserPrincipal owner =
        Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
    if (!owner.equals(
            Files.getOwner(
                canonicalMarker, LinkOption.NOFOLLOW_LINKS))
        || hasForeignAllowAcl(directory, owner)
        || hasForeignAllowAcl(canonicalMarker, owner)) {
      throw invalid("JOURNAL_PATH_UNSAFE");
    }

    Path journal =
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".journal");
    if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
      return Verification.unknown(
          "JOURNAL_MISSING",
          BillingEvidence.untrusted(),
          inspectUnsealedRecordState(directory, owner));
    }
    requireSafeEvidenceFile(
        journal, owner, "JOURNAL_PATH_UNSAFE");
    byte[] journalBytes =
        readBounded(
            journal,
            MAX_JOURNAL_BYTES,
            "JOURNAL_TOO_LARGE");
    requireSafeEvidenceFile(
        journal, owner, "JOURNAL_PATH_UNSAFE");
    ParsedJournal parsed = parseJournal(journalBytes);
    if (parsed.terminal == null) {
      String code =
          parsed.empty
              ? "JOURNAL_EMPTY"
              : parsed.tornTail
                  ? "JOURNAL_TORN_TAIL"
                  : "TERMINAL_EVENT_MISSING";
      return Verification.unknown(
          code,
          parsed.billing,
          inspectUnsealedRecordState(directory, owner));
    }
    if (parsed.tornTail) {
      throw invalid("EVENT_AFTER_TERMINAL");
    }

    TerminalEvidence terminal = parsed.terminal;
    Path pending =
        directory.resolve(terminal.runRecordFile() + ".pending");
    if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
      throw invalid("RUN_RECORD_STATE_INVALID");
    }
    Path record = directory.resolve(terminal.runRecordFile());
    if (!record.getParent().equals(directory)
        || !record
            .getFileName()
            .toString()
            .equals(
                SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                    + ".run.json")) {
      throw invalid("TERMINAL_SEMANTICS_INVALID");
    }
    if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
      return Verification.unknown(
          "TERMINAL_RECORD_MISSING",
          parsed.billing,
          RecordState.ABSENT);
    }
    requireSafeEvidenceFile(
        record, owner, "TERMINAL_RECORD_PATH_UNSAFE");
    byte[] recordBytes =
        readBounded(
            record, MAX_RECORD_BYTES, "TERMINAL_RECORD_TOO_LARGE");
    requireSafeEvidenceFile(
        record, owner, "TERMINAL_RECORD_PATH_UNSAFE");
    if (!MessageDigest.isEqual(
        terminal.runRecordSha256()
            .getBytes(StandardCharsets.US_ASCII),
        sha256(recordBytes)
            .getBytes(StandardCharsets.US_ASCII))) {
      throw invalid("TERMINAL_RECORD_HASH_MISMATCH");
    }
    verifyRecord(recordBytes, terminal);
    return Verification.verified(terminal, parsed.billing);
  }

  private static ParsedJournal parseJournal(byte[] bytes) {
    int lineStart = 0;
    List<String> completeLines = new ArrayList<>();
    for (int index = 0; index < bytes.length; index++) {
      int value = bytes[index] & 0xff;
      if (value == '\n') {
        int length = index - lineStart;
        if (length == 0) {
          throw invalid("JOURNAL_LINE_MALFORMED");
        }
        if (length > MAX_JOURNAL_LINE_BYTES) {
          throw invalid("JOURNAL_LINE_TOO_LARGE");
        }
        completeLines.add(
            new String(
                bytes,
                lineStart,
                length,
                StandardCharsets.US_ASCII));
        lineStart = index + 1;
      } else if (value < 0x20 || value > 0x7e) {
        throw invalid("JOURNAL_LINE_MALFORMED");
      } else if (index - lineStart + 1
          > MAX_JOURNAL_LINE_BYTES) {
        throw invalid("JOURNAL_LINE_TOO_LARGE");
      }
    }
    boolean tornTail = lineStart != bytes.length;
    JournalState state = new JournalState();
    String previousHash =
        ContentHashes.sha256(
            "emergeos.eval-attempt-journal.genesis.v1\n"
                + "attemptId="
                + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
    for (int index = 0; index < completeLines.size(); index++) {
      JournalEvent event =
          parseEvent(completeLines.get(index), index + 1, previousHash);
      state.accept(event);
      previousHash = event.eventHash;
    }
    return new ParsedJournal(
        state.terminal,
        state.billingEvidence(),
        tornTail,
        bytes.length == 0);
  }

  private static JournalEvent parseEvent(
      String line, int expectedSequence, String expectedPreviousHash) {
    Map<String, String> allFields = new LinkedHashMap<>();
    for (String item : line.split(" ", -1)) {
      int separator = item.indexOf('=');
      if (separator < 1
          || separator == item.length() - 1
          || item.indexOf('=', separator + 1) >= 0) {
        throw invalid("JOURNAL_LINE_MALFORMED");
      }
      String name = item.substring(0, separator);
      String value = item.substring(separator + 1);
      if (!name.matches("[A-Za-z][A-Za-z0-9]{0,63}")
          || !value.matches("[A-Za-z0-9._~:/-]{1,512}")
          || allFields.put(name, value) != null) {
        throw invalid("JOURNAL_LINE_MALFORMED");
      }
    }
    int sequence =
        parseStrictInt(
            removeRequired(allFields, "sequence"),
            "JOURNAL_SEQUENCE_INVALID");
    if (sequence != expectedSequence) {
      throw invalid("JOURNAL_SEQUENCE_INVALID");
    }
    String event = removeRequired(allFields, "event");
    if (!event.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw invalid("JOURNAL_LINE_MALFORMED");
    }
    String previousHash =
        removeRequired(allFields, "previousHash");
    String eventHash = removeRequired(allFields, "eventHash");
    if (!isHash(previousHash)
        || !isHash(eventHash)
        || !previousHash.equals(expectedPreviousHash)) {
      throw invalid("JOURNAL_HASH_CHAIN_INVALID");
    }
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
    new TreeMap<>(allFields)
        .forEach(
            (name, value) ->
                material
                    .append(name)
                    .append('=')
                    .append(value)
                    .append('\n'));
    if (!eventHash.equals(ContentHashes.sha256(material.toString()))) {
      throw invalid("JOURNAL_HASH_CHAIN_INVALID");
    }
    return new JournalEvent(
        sequence,
        event,
        Map.copyOf(allFields),
        eventHash);
  }

  private static void verifyRecord(
      byte[] bytes, TerminalEvidence terminal) {
    final JsonNode record;
    final AgentRun run;
    final ArtifactLineage artifact;
    try {
      record = JSON.readTree(bytes);
      if (record == null || !record.isObject()) {
        throw invalid("TERMINAL_RECORD_MALFORMED");
      }
      JsonNode runNode = record.at("/run");
      if (!runNode.isObject()) {
        throw invalid("TERMINAL_RECORD_MALFORMED");
      }
      run = JSON.treeToValue(runNode, AgentRun.class);
      JsonNode artifactNode = record.at("/artifact");
      artifact =
          artifactNode.isNull()
              ? null
              : JSON.treeToValue(
                  artifactNode, ArtifactLineage.class);
    } catch (InvalidEvidence failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    try {
      var profile = SyntheticEvalCatalog.profile();
      if (run == null
          || run.lifecycle() == AgentRunLifecycle.RUNNING
          || !SyntheticEvalCatalog.RUN_ID.equals(run.runId())
          || !SyntheticEvalCatalog.PRINCIPAL_ID.equals(
              run.principalId())) {
        throw invalid("TERMINAL_RUN_IDENTITY_MISMATCH");
      }
      if (!SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
              IntegrityHashes.taskHash(run.task()))
          || !SyntheticEvalCatalog.TASK_ID.equals(
              run.task().id())) {
        throw invalid("TERMINAL_TASK_MISMATCH");
      }
      if (run.result().status() != terminal.status()
          || !terminal.bundleHash().equals(
              run.bundle().integrityHash())
          || !terminal.bundleHash().equals(
              IntegrityHashes.bundleHash(run.bundle()))
          || !run.trace()
              .rootHash()
              .equals(run.bundle().traceRootHash())) {
        throw invalid("TERMINAL_BUNDLE_MISMATCH");
      }
      if (!profile
              .environmentSnapshotRef()
              .equals(
                  run.bundle().environmentSnapshotRef())
          || !profile
              .componentVersions()
              .equals(run.bundle().componentVersions())
          || !profile
              .experiment()
              .equals(run.bundle().experiment())
          || !profile
              .harnessVersion()
              .equals(run.bundle().harnessVersion())
          || !profile
              .toolRegistryVersion()
              .equals(run.bundle().toolRegistryVersion())
          || !SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT
              .equals(
                  run.bundle()
                      .componentVersions()
                      .get("execution-profile-fingerprint"))
          || !SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT
              .equals(
                  run.bundle()
                      .componentVersions()
                      .get("pricing-profile-fingerprint"))) {
        throw invalid("TERMINAL_PROFILE_MISMATCH");
      }
      requireText(record, "/schemaVersion", "1.0");
      requireText(
          record,
          "/attemptId",
          SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
      requireText(
          record, "/caseId", SyntheticEvalCatalog.CASE_ID);
      requireText(
          record,
          "/packSha256",
          SyntheticEvalCatalog.PACK_RAW_SHA256);
      requireText(
          record,
          "/environmentSha256",
          SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256);
      requireText(
          record, "/bundleHash", terminal.bundleHash());
      requireText(
          record,
          "/run/bundle/integrityHash",
          terminal.bundleHash());
      requireText(
          record, "/billingStatus", terminal.billingStatus());
      requireText(
          record,
          "/run/result/status",
          terminal.status().name());
      requireDecimal(
          record,
          "/observedCostUsd",
          terminal.observedCostUsd());
      requireLong(
          record,
          "/observedTokenCount",
          terminal.observedTokenCount());
      requireBoolean(
          record,
          "/meteringMatchesRun",
          terminal.meteringMatchesRun());
      requireInt(
          record,
          "/providerSdkCreateInvocations",
          terminal.providerSdkCreateInvocations());
      requireInt(
          record,
          "/providerAttributedInvocations",
          terminal.providerAttributedInvocations());
      requireDecimal(
          record,
          "/effects/providerObservedCostUsd",
          terminal.observedCostUsd());
      requireLong(
          record,
          "/effects/providerObservedTokenCount",
          terminal.observedTokenCount());
      requireInt(
          record,
          "/effects/providerSdkCreateInvocations",
          terminal.providerSdkCreateInvocations());
      requireInt(
          record,
          "/effects/providerAttributedInvocations",
          terminal.providerAttributedInvocations());
      requireInt(record, "/effects/keyReads", 1);
      requireInt(record, "/effects/clientFactories", 1);
      requireInt(record, "/effects/modelFactories", 1);
      requireInt(record, "/effects/runStarts", 1);
      requireInt(record, "/effects/permitConsumes", 1);

      String traceRootHash = requiredText(record, "/traceRootHash");
      if (!isHash(traceRootHash)) {
        throw invalid("TERMINAL_RECORD_MISMATCH");
      }
      requireText(
          record, "/run/trace/rootHash", traceRootHash);
      requireText(
          record, "/run/bundle/traceRootHash", traceRootHash);
      BigDecimal runCost = requiredDecimal(record, "/runCostUsd");
      long runTokens = requiredLong(record, "/runTokenCount");
      requireDecimal(record, "/run/result/costUsd", runCost);
      requireDecimal(record, "/run/bundle/costUsd", runCost);
      requireLong(record, "/run/result/tokenCount", runTokens);
      requireLong(record, "/run/bundle/tokenCount", runTokens);
      boolean computedMeteringMatch =
          runCost.compareTo(terminal.observedCostUsd()) == 0
              && runTokens == terminal.observedTokenCount();
      if (computedMeteringMatch != terminal.meteringMatchesRun()) {
        throw invalid("TERMINAL_RECORD_MISMATCH");
      }

      JsonNode artifactHash = record.at("/artifactContentHash");
      if (terminal.status() == RunStatus.SUCCEEDED) {
        if (artifact == null
            || !artifactHash.isTextual()
            || !isHash(artifactHash.textValue())
            || !SyntheticEvalCatalog.ARTIFACT_ID.equals(
                artifact.artifactId())
            || !SyntheticEvalCatalog.PRINCIPAL_ID.equals(
                artifact.principalId())
            || !SyntheticEvalCatalog.CAPTURE_ID.equals(
                artifact.sourceCaptureId())
            || !artifact
                .current()
                .contentHash()
                .equals(artifactHash.textValue())) {
          throw invalid("TERMINAL_RECORD_MISMATCH");
        }
        String content =
            requiredText(record, "/artifact/versions/0/content");
        requireText(
            record,
            "/artifact/versions/0/contentHash",
            artifactHash.textValue());
        if (!ContentHashes.sha256(content)
            .equals(artifactHash.textValue())) {
          throw invalid("TERMINAL_RECORD_MISMATCH");
        }
      } else if (artifact != null || !artifactHash.isNull()) {
        throw invalid("TERMINAL_RECORD_MISMATCH");
      }
    } catch (InvalidEvidence failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
  }

  private static boolean safeDirectory(Path directory)
      throws IOException {
    return !Files.isSymbolicLink(directory)
        && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        && Files.getPosixFilePermissions(
                directory, LinkOption.NOFOLLOW_LINKS)
            .equals(DIRECTORY_PERMISSIONS);
  }

  private static boolean safeRegularFile(Path file)
      throws IOException {
    return !Files.isSymbolicLink(file)
        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        && Files.getPosixFilePermissions(
                file, LinkOption.NOFOLLOW_LINKS)
            .equals(FILE_PERMISSIONS);
  }

  private static void requireSafeEvidenceFile(
      Path file, UserPrincipal owner, String code)
      throws IOException {
    if (!safeRegularFile(file)
        || !owner.equals(
            Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))
        || hasForeignAllowAcl(file, owner)) {
      throw invalid(code);
    }
  }

  private static RecordState inspectUnsealedRecordState(
      Path directory, UserPrincipal owner) throws IOException {
    Path record =
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".run.json");
    Path pending =
        directory.resolve(
            SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                + ".run.json.pending");
    boolean recordExists =
        Files.exists(record, LinkOption.NOFOLLOW_LINKS);
    boolean pendingExists =
        Files.exists(pending, LinkOption.NOFOLLOW_LINKS);
    if (recordExists && pendingExists) {
      throw invalid("RUN_RECORD_STATE_INVALID");
    }
    if (recordExists) {
      requireSafeEvidenceFile(
          record, owner, "TERMINAL_RECORD_PATH_UNSAFE");
      if (Files.size(record) > MAX_RECORD_BYTES) {
        throw invalid("TERMINAL_RECORD_TOO_LARGE");
      }
      return RecordState.FINAL_UNSEALED;
    }
    if (pendingExists) {
      requireSafeEvidenceFile(
          pending, owner, "TERMINAL_RECORD_PATH_UNSAFE");
      if (Files.size(pending) > MAX_RECORD_BYTES) {
        throw invalid("TERMINAL_RECORD_TOO_LARGE");
      }
      return RecordState.PENDING_NON_AUTHORITATIVE;
    }
    return RecordState.ABSENT;
  }

  private static boolean hasForeignAllowAcl(
      Path path, UserPrincipal owner) throws IOException {
    AclFileAttributeView view =
        Files.getFileAttributeView(
            path,
            AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
    return view != null
        && view.getAcl().stream()
            .anyMatch(
                entry ->
                    entry.type() == AclEntryType.ALLOW
                        && !owner.equals(entry.principal()));
  }

  private static byte[] readBounded(
      Path file, long maximumBytes, String tooLargeCode)
      throws IOException {
    if (Files.size(file) > maximumBytes) {
      throw invalid(tooLargeCode);
    }
    try (FileChannel channel =
        FileChannel.open(
            file,
            Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      long observed = 0;
      int read;
      while ((read = channel.read(buffer)) != -1) {
        if (read == 0) {
          continue;
        }
        observed += read;
        if (observed > maximumBytes) {
          throw invalid(tooLargeCode);
        }
        output.write(buffer.array(), 0, read);
        buffer.clear();
      }
      return output.toByteArray();
    }
  }

  private static String removeRequired(
      Map<String, String> fields, String name) {
    String value = fields.remove(name);
    if (value == null) {
      throw invalid("JOURNAL_LINE_MALFORMED");
    }
    return value;
  }

  private static int parseStrictInt(String value, String code) {
    if (!value.matches("0|[1-9][0-9]{0,9}")) {
      throw invalid(code);
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException failure) {
      throw invalid(code);
    }
  }

  private static long parseStrictLong(String value, String code) {
    if (!value.matches("0|[1-9][0-9]{0,18}")) {
      throw invalid(code);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw invalid(code);
    }
  }

  private static BigDecimal parseNonNegativeDecimal(
      String value, String code) {
    if (!value.matches(
        "0|[1-9][0-9]{0,17}(?:\\.[0-9]{1,18})?|0\\.[0-9]{1,18}")) {
      throw invalid(code);
    }
    BigDecimal parsed = new BigDecimal(value);
    if (parsed.signum() < 0) {
      throw invalid(code);
    }
    return parsed;
  }

  private static boolean parseBoolean(String value, String code) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw invalid(code);
  }

  private static boolean isHash(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  private static String requiredText(JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    if (!node.isTextual() || node.textValue().isBlank()) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    return node.textValue();
  }

  private static void requireText(
      JsonNode root, String pointer, String expected) {
    if (!expected.equals(requiredText(root, pointer))) {
      throw invalid("TERMINAL_RECORD_MISMATCH");
    }
  }

  private static BigDecimal requiredDecimal(
      JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    if (!node.isNumber()
        || !node.isBigDecimal()
            && !node.isFloatingPointNumber()
            && !node.isIntegralNumber()) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    BigDecimal value = node.decimalValue();
    if (value.signum() < 0) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    return value;
  }

  private static void requireDecimal(
      JsonNode root, String pointer, BigDecimal expected) {
    if (expected.compareTo(requiredDecimal(root, pointer)) != 0) {
      throw invalid("TERMINAL_RECORD_MISMATCH");
    }
  }

  private static long requiredLong(JsonNode root, String pointer) {
    JsonNode node = root.at(pointer);
    if (!node.isIntegralNumber()
        || !node.canConvertToLong()
        || node.longValue() < 0) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    return node.longValue();
  }

  private static void requireLong(
      JsonNode root, String pointer, long expected) {
    if (requiredLong(root, pointer) != expected) {
      throw invalid("TERMINAL_RECORD_MISMATCH");
    }
  }

  private static void requireInt(
      JsonNode root, String pointer, int expected) {
    long value = requiredLong(root, pointer);
    if (value > Integer.MAX_VALUE || value != expected) {
      throw invalid("TERMINAL_RECORD_MISMATCH");
    }
  }

  private static void requireBoolean(
      JsonNode root, String pointer, boolean expected) {
    JsonNode node = root.at(pointer);
    if (!node.isBoolean()) {
      throw invalid("TERMINAL_RECORD_MALFORMED");
    }
    if (node.booleanValue() != expected) {
      throw invalid("TERMINAL_RECORD_MISMATCH");
    }
  }

  enum Verdict {
    VERIFIED,
    UNKNOWN,
    INVALID
  }

  enum BillingStatus {
    NOT_INVOKED,
    ATTRIBUTED,
    UNKNOWN
  }

  enum RecordState {
    ABSENT,
    PENDING_NON_AUTHORITATIVE,
    FINAL_UNSEALED,
    TERMINAL_LINKED,
    INVALID,
    UNASSESSED
  }

  /**
   * Billing observed in the complete, hash-verified journal prefix.
   *
   * <p>{@code trustedPrefix=true} authenticates only the durable snapshot
   * parsed by this verifier. It does not close an {@link Verdict#UNKNOWN}
   * attempt: a still-running process may append another intent or
   * attribution later. Consumers must evaluate verdict, billing, and record
   * state together.
   */
  record BillingEvidence(
      BillingStatus status,
      boolean trustedPrefix,
      BigDecimal observedCostUsd,
      long observedTokenCount,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations) {

    BillingEvidence {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(
          observedCostUsd, "observedCostUsd");
      if (observedCostUsd.signum() < 0
          || observedTokenCount < 0
          || providerSdkCreateInvocations < 0
          || providerAttributedInvocations < 0
          || providerAttributedInvocations
              > providerSdkCreateInvocations
          || !trustedPrefix
              && (status != BillingStatus.UNKNOWN
                  || observedCostUsd.signum() != 0
                  || observedTokenCount != 0
                  || providerSdkCreateInvocations != 0
                  || providerAttributedInvocations != 0)) {
        throw new IllegalArgumentException(
            "billing evidence is inconsistent");
      }
    }

    static BillingEvidence untrusted() {
      return new BillingEvidence(
          BillingStatus.UNKNOWN,
          false,
          BigDecimal.ZERO,
          0,
          0,
          0);
    }
  }

  record Verification(
      Verdict verdict,
      String code,
      BillingEvidence billing,
      RecordState recordState,
      TerminalEvidence terminal) {

    Verification {
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(billing, "billing");
      Objects.requireNonNull(recordState, "recordState");
      if ((verdict == Verdict.VERIFIED) != (terminal != null)) {
        throw new IllegalArgumentException(
            "only verified evidence may carry a terminal");
      }
      if (verdict == Verdict.VERIFIED
          && (recordState != RecordState.TERMINAL_LINKED
              || !billing.trustedPrefix())
          || verdict == Verdict.INVALID
              && (recordState != RecordState.INVALID
                  || billing.trustedPrefix())) {
        throw new IllegalArgumentException(
            "verification axes are inconsistent");
      }
    }

    static Verification verified(
        TerminalEvidence terminal, BillingEvidence billing) {
      return new Verification(
          Verdict.VERIFIED,
          "TERMINAL_EVIDENCE_VERIFIED",
          Objects.requireNonNull(billing, "billing"),
          RecordState.TERMINAL_LINKED,
          Objects.requireNonNull(terminal, "terminal"));
    }

    static Verification unknown(
        String code,
        BillingEvidence billing,
        RecordState recordState) {
      return new Verification(
          Verdict.UNKNOWN,
          code,
          Objects.requireNonNull(billing, "billing"),
          Objects.requireNonNull(recordState, "recordState"),
          null);
    }

    static Verification invalid(String code) {
      return new Verification(
          Verdict.INVALID,
          code,
          BillingEvidence.untrusted(),
          RecordState.INVALID,
          null);
    }

    boolean verified() {
      return verdict == Verdict.VERIFIED;
    }
  }

  record TerminalEvidence(
      RunStatus status,
      String bundleHash,
      String billingStatus,
      BigDecimal observedCostUsd,
      long observedTokenCount,
      boolean meteringMatchesRun,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations,
      String runRecordFile,
      String runRecordSha256) {

    TerminalEvidence {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(bundleHash, "bundleHash");
      Objects.requireNonNull(billingStatus, "billingStatus");
      Objects.requireNonNull(observedCostUsd, "observedCostUsd");
      Objects.requireNonNull(runRecordFile, "runRecordFile");
      Objects.requireNonNull(runRecordSha256, "runRecordSha256");
    }
  }

  private record ParsedJournal(
      TerminalEvidence terminal,
      BillingEvidence billing,
      boolean tornTail,
      boolean empty) {}

  private record JournalEvent(
      int sequence,
      String name,
      Map<String, String> fields,
      String eventHash) {}

  private static final class JournalState {
    private boolean gate;
    private boolean credential;
    private boolean client;
    private boolean pendingIntent;
    private int sdkInvocations;
    private int attributions;
    private BigDecimal attributedCost = BigDecimal.ZERO;
    private long attributedTokens;
    private TerminalEvidence terminal;

    private void accept(JournalEvent event) {
      if (terminal != null) {
        throw invalid("EVENT_AFTER_TERMINAL");
      }
      switch (event.name) {
        case "GATE_APPROVED" -> acceptGate(event);
        case "CREDENTIAL_READ_STARTED" -> acceptCredential(event);
        case "CLIENT_CREATED" -> acceptClient(event);
        case "PROVIDER_SDK_CREATE_INTENT" ->
            acceptProviderIntent(event);
        case "PROVIDER_ATTRIBUTED" ->
            acceptProviderAttribution(event);
        case "TERMINAL_RUN_RECORD_PERSISTED" ->
            acceptTerminal(event);
        default -> throw invalid("JOURNAL_EVENT_INVALID");
      }
    }

    private void acceptGate(JournalEvent event) {
      requireExactFields(
          event.fields,
          Set.of(
              "caseId",
              "packSha256",
              "environmentSha256",
              "taskHash"));
      if (gate
          || event.sequence != 1
          || !SyntheticEvalCatalog.CASE_ID.equals(
              event.fields.get("caseId"))
          || !SyntheticEvalCatalog.PACK_RAW_SHA256.equals(
              event.fields.get("packSha256"))
          || !SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256.equals(
              event.fields.get("environmentSha256"))
          || !SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
              event.fields.get("taskHash"))) {
        throw invalid("GATE_EVENT_INVALID");
      }
      gate = true;
    }

    private void acceptCredential(JournalEvent event) {
      requireExactFields(event.fields, Set.of());
      if (!gate || credential || client) {
        throw invalid("JOURNAL_EVENT_ORDER_INVALID");
      }
      credential = true;
    }

    private void acceptClient(JournalEvent event) {
      requireExactFields(event.fields, Set.of());
      if (!credential || client) {
        throw invalid("JOURNAL_EVENT_ORDER_INVALID");
      }
      client = true;
    }

    private void acceptProviderIntent(JournalEvent event) {
      requireExactFields(event.fields, Set.of("ordinal"));
      int ordinal =
          parseStrictInt(
              event.fields.get("ordinal"),
              "PROVIDER_EVENT_INVALID");
      if (!client
          || pendingIntent
          || ordinal != sdkInvocations + 1
          || ordinal > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
        throw invalid("PROVIDER_EVENT_INVALID");
      }
      sdkInvocations++;
      pendingIntent = true;
    }

    private void acceptProviderAttribution(JournalEvent event) {
      requireExactFields(
          event.fields,
          Set.of(
              "ordinal",
              "resolvedModel",
              "observedCostUsd",
              "tokenCount"));
      int ordinal =
          parseStrictInt(
              event.fields.get("ordinal"),
              "PROVIDER_EVENT_INVALID");
      String resolvedModel = event.fields.get("resolvedModel");
      BigDecimal cost =
          parseNonNegativeDecimal(
              event.fields.get("observedCostUsd"),
              "PROVIDER_EVENT_INVALID");
      long tokens =
          parseStrictLong(
              event.fields.get("tokenCount"),
              "PROVIDER_EVENT_INVALID");
      if (!pendingIntent
          || ordinal != sdkInvocations
          || ordinal != attributions + 1
          || !resolvedModel.matches(
              "[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")) {
        throw invalid("PROVIDER_EVENT_INVALID");
      }
      attributedCost = attributedCost.add(cost);
      try {
        attributedTokens =
            Math.addExact(attributedTokens, tokens);
      } catch (ArithmeticException failure) {
        throw invalid("PROVIDER_EVENT_INVALID");
      }
      attributions++;
      pendingIntent = false;
    }

    private void acceptTerminal(JournalEvent event) {
      requireExactFields(
          event.fields,
          Set.of(
              "status",
              "bundleHash",
              "billingStatus",
              "observedCostUsd",
              "observedTokenCount",
              "meteringMatchesRun",
              "providerSdkCreateInvocations",
              "providerAttributedInvocations",
              "runRecordFile",
              "runRecordSha256"));
      if (!client) {
        throw invalid("TERMINAL_SEMANTICS_INVALID");
      }
      final RunStatus status;
      try {
        status =
            RunStatus.valueOf(event.fields.get("status"));
      } catch (IllegalArgumentException failure) {
        throw invalid("TERMINAL_SEMANTICS_INVALID");
      }
      String bundleHash = event.fields.get("bundleHash");
      String billingStatus = event.fields.get("billingStatus");
      BigDecimal observedCost =
          parseNonNegativeDecimal(
              event.fields.get("observedCostUsd"),
              "TERMINAL_SEMANTICS_INVALID");
      long observedTokens =
          parseStrictLong(
              event.fields.get("observedTokenCount"),
              "TERMINAL_SEMANTICS_INVALID");
      boolean meteringMatchesRun =
          parseBoolean(
              event.fields.get("meteringMatchesRun"),
              "TERMINAL_SEMANTICS_INVALID");
      int declaredSdkInvocations =
          parseStrictInt(
              event.fields.get("providerSdkCreateInvocations"),
              "TERMINAL_SEMANTICS_INVALID");
      int declaredAttributions =
          parseStrictInt(
              event.fields.get("providerAttributedInvocations"),
              "TERMINAL_SEMANTICS_INVALID");
      String recordFile = event.fields.get("runRecordFile");
      String recordHash = event.fields.get("runRecordSha256");
      boolean billingConsistent =
          switch (billingStatus) {
            case "NOT_INVOKED" ->
                sdkInvocations == 0
                    && attributions == 0
                    && observedCost.signum() == 0
                    && observedTokens == 0;
            case "ATTRIBUTED" ->
                sdkInvocations > 0
                    && attributions == sdkInvocations;
            case "UNKNOWN" ->
                sdkInvocations > 0
                    && attributions < sdkInvocations;
            default -> false;
          };
      if (!isHash(bundleHash)
          || !isHash(recordHash)
          || !recordFile.equals(
              SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                  + ".run.json")
          || declaredSdkInvocations != sdkInvocations
          || declaredAttributions != attributions
          || sdkInvocations
              > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || attributedCost.compareTo(observedCost) != 0
          || attributedTokens != observedTokens
          || !billingConsistent
          || status == RunStatus.SUCCEEDED
              && (!"ATTRIBUTED".equals(billingStatus)
                  || !meteringMatchesRun
                  || sdkInvocations
                      != SyntheticEvalCatalog
                          .MAXIMUM_PROVIDER_REQUESTS)) {
        throw invalid("TERMINAL_SEMANTICS_INVALID");
      }
      terminal =
          new TerminalEvidence(
              status,
              bundleHash,
              billingStatus,
              observedCost,
              observedTokens,
              meteringMatchesRun,
              sdkInvocations,
              attributions,
              recordFile,
              recordHash);
    }

    private BillingEvidence billingEvidence() {
      if (!gate) {
        return BillingEvidence.untrusted();
      }
      BillingStatus status =
          sdkInvocations == 0
              ? BillingStatus.NOT_INVOKED
              : attributions == sdkInvocations
                  ? BillingStatus.ATTRIBUTED
                  : BillingStatus.UNKNOWN;
      return new BillingEvidence(
          status,
          true,
          attributedCost,
          attributedTokens,
          sdkInvocations,
          attributions);
    }

    private static void requireExactFields(
        Map<String, String> actual, Set<String> expected) {
      if (!actual.keySet().equals(expected)) {
        throw invalid("JOURNAL_EVENT_FIELDS_INVALID");
      }
    }
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

  private static InvalidEvidence invalid(String code) {
    return new InvalidEvidence(code);
  }

  private static final class InvalidEvidence
      extends RuntimeException {
    private final String code;

    private InvalidEvidence(String code) {
      super(code, null, false, false);
      this.code = code;
    }
  }
}
