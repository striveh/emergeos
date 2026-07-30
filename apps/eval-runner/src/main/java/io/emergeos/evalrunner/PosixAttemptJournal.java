package io.emergeos.evalrunner;

import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.ContentHashes;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PosixAttemptJournal {

  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final Path journal;
  private final UserPrincipal owner;
  private int sequence;
  private String previousHash;

  private PosixAttemptJournal(
      Path journal, UserPrincipal owner, String previousHash) {
    this.journal = journal;
    this.owner = owner;
    this.previousHash = previousHash;
  }

  static PosixAttemptJournal open(Path marker) {
    Objects.requireNonNull(marker, "marker");
    try {
      Path canonicalMarker = marker.toAbsolutePath().normalize();
      Path directory = canonicalMarker.getParent();
      if (directory == null
          || !Files.isRegularFile(
              canonicalMarker, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(canonicalMarker)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(directory)
          || !Files.getPosixFilePermissions(
                  directory, LinkOption.NOFOLLOW_LINKS)
              .equals(DIRECTORY_PERMISSIONS)
          || !Files.getPosixFilePermissions(
                  canonicalMarker, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)) {
        throw rejected("ATTEMPT_JOURNAL_PATH_UNSAFE");
      }
      UserPrincipal owner =
          Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
      if (!owner.equals(
              Files.getOwner(
                  canonicalMarker, LinkOption.NOFOLLOW_LINKS))
          || hasForeignAllowAcl(directory, owner)
          || hasForeignAllowAcl(canonicalMarker, owner)) {
        throw rejected("ATTEMPT_JOURNAL_PATH_UNSAFE");
      }
      Path journal =
          directory.resolve(
              SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                  + ".journal");
      String genesis =
          ContentHashes.sha256(
              "emergeos.eval-attempt-journal.genesis.v1\n"
                  + "attemptId="
                  + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID);
      try (FileChannel channel =
          FileChannel.open(
              journal,
              Set.of(
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(
                  FILE_PERMISSIONS))) {
        // Creation is forced before any credential read or client creation.
        channel.force(true);
      }
      try (FileChannel channel =
          FileChannel.open(directory, StandardOpenOption.READ)) {
        channel.force(true);
      }
      PosixAttemptJournal result =
          new PosixAttemptJournal(journal, owner, genesis);
      if (hasForeignAllowAcl(journal, owner)) {
        throw rejected("ATTEMPT_JOURNAL_FILE_UNSAFE");
      }
      result.append(
          "GATE_APPROVED",
          Map.of(
              "caseId", SyntheticEvalCatalog.CASE_ID,
              "packSha256",
                  SyntheticEvalCatalog.PACK_RAW_SHA256,
              "environmentSha256",
                  SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256,
              "taskHash",
                  SyntheticEvalCatalog.EXPECTED_TASK_HASH));
      return result;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("ATTEMPT_JOURNAL_OPEN_FAILED");
    }
  }

  synchronized void credentialReadStarted() {
    append("CREDENTIAL_READ_STARTED", Map.of());
  }

  synchronized void clientCreated() {
    append("CLIENT_CREATED", Map.of());
  }

  synchronized void providerInvocationIntent(int ordinal) {
    requireOrdinal(ordinal);
    append(
        "PROVIDER_SDK_CREATE_INTENT",
        Map.of("ordinal", Integer.toString(ordinal)));
  }

  synchronized void providerAttributed(
      int ordinal,
      String resolvedModel,
      BigDecimal observedCostUsd,
      long tokenCount) {
    requireOrdinal(ordinal);
    if (resolvedModel == null
        || !resolvedModel.matches(
            "[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")
        || observedCostUsd == null
        || observedCostUsd.signum() < 0
        || tokenCount < 0) {
      throw rejected("ATTEMPT_JOURNAL_EVENT_INVALID");
    }
    append(
        "PROVIDER_ATTRIBUTED",
        Map.of(
            "ordinal", Integer.toString(ordinal),
            "resolvedModel", resolvedModel,
            "observedCostUsd", observedCostUsd.toPlainString(),
            "tokenCount", Long.toString(tokenCount)));
  }

  synchronized void terminalRecordPersisted(
      RunStatus status,
      String bundleHash,
      String billingStatus,
      BigDecimal observedCostUsd,
      long observedTokenCount,
      boolean meteringMatchesRun,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations,
      PosixEvalRunRecordStore.Stored stored) {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(stored, "stored");
    requireHash(bundleHash);
    if (!Set.of("NOT_INVOKED", "ATTRIBUTED", "UNKNOWN")
            .contains(billingStatus)
        || observedCostUsd == null
        || observedCostUsd.signum() < 0
        || observedTokenCount < 0
        || providerSdkCreateInvocations < 0
        || providerSdkCreateInvocations
            > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || providerAttributedInvocations < 0
        || providerAttributedInvocations
            > providerSdkCreateInvocations) {
      throw rejected("ATTEMPT_JOURNAL_EVENT_INVALID");
    }
    append(
        "TERMINAL_RUN_RECORD_PERSISTED",
        Map.of(
            "status", status.name(),
            "bundleHash", bundleHash,
            "billingStatus", billingStatus,
            "observedCostUsd", observedCostUsd.toPlainString(),
            "observedTokenCount",
                Long.toString(observedTokenCount),
            "meteringMatchesRun",
                Boolean.toString(meteringMatchesRun),
            "providerSdkCreateInvocations",
                Integer.toString(providerSdkCreateInvocations),
            "providerAttributedInvocations",
                Integer.toString(providerAttributedInvocations),
            "runRecordFile", stored.fileName(),
            "runRecordSha256", stored.sha256()));
  }

  String fileName() {
    return journal.getFileName().toString();
  }

  synchronized String latestHash() {
    return previousHash;
  }

  private void append(String event, Map<String, String> fields) {
    try {
      if (!event.matches("[A-Z][A-Z0-9_]{0,127}")
          || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(journal)
          || !Files.getPosixFilePermissions(
                  journal, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)
          || !owner.equals(
              Files.getOwner(journal, LinkOption.NOFOLLOW_LINKS))
          || hasForeignAllowAcl(journal, owner)) {
        throw rejected("ATTEMPT_JOURNAL_FILE_UNSAFE");
      }
      int next = Math.addExact(sequence, 1);
      Map<String, String> ordered = new LinkedHashMap<>();
      fields.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                requireSafeField(entry.getKey(), entry.getValue());
                ordered.put(entry.getKey(), entry.getValue());
              });
      StringBuilder material =
          new StringBuilder()
              .append("emergeos.eval-attempt-journal.event.v1\n")
              .append("attemptId=")
              .append(SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID)
              .append('\n')
              .append("sequence=")
              .append(next)
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
              .append(next)
              .append(" event=")
              .append(event)
              .append(" previousHash=")
              .append(previousHash);
      ordered.forEach(
          (name, value) ->
              line.append(' ').append(name).append('=').append(value));
      line.append(" eventHash=").append(eventHash).append('\n');
      byte[] bytes =
          line.toString().getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel =
          FileChannel.open(
              journal,
              Set.of(
                  StandardOpenOption.WRITE,
                  StandardOpenOption.APPEND,
                  LinkOption.NOFOLLOW_LINKS))) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      sequence = next;
      previousHash = eventHash;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("ATTEMPT_JOURNAL_APPEND_FAILED");
    }
  }

  private static void requireOrdinal(int ordinal) {
    if (ordinal < 1
        || ordinal > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
      throw rejected("ATTEMPT_JOURNAL_EVENT_INVALID");
    }
  }

  private static void requireHash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw rejected("ATTEMPT_JOURNAL_EVENT_INVALID");
    }
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

  private static void requireSafeField(String name, String value) {
    if (name == null
        || !name.matches("[A-Za-z][A-Za-z0-9]{0,63}")
        || value == null
        || !value.matches("[A-Za-z0-9._~:/-]{1,512}")) {
      throw rejected("ATTEMPT_JOURNAL_EVENT_INVALID");
    }
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }
}
