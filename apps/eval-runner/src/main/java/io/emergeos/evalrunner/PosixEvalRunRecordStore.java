package io.emergeos.evalrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

final class PosixEvalRunRecordStore {

  private static final long MAX_RECORD_BYTES = 1024 * 1024;
  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  Stored save(
      Path marker,
      AgentDraftOutcome outcome,
      SyntheticEvalExecutor.Effects effects) {
    return save(
        marker,
        outcome,
        effects,
        EvalExecutionObserver.noop());
  }

  Stored save(
      Path marker,
      AgentDraftOutcome outcome,
      SyntheticEvalExecutor.Effects effects,
      EvalExecutionObserver observer) {
    Objects.requireNonNull(marker, "marker");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(effects, "effects");
    Objects.requireNonNull(observer, "observer");
    try {
      Path canonicalMarker =
          marker.toAbsolutePath().normalize();
      Path directory = canonicalMarker.getParent();
      String expectedMarkerName =
          SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".attempt";
      if (directory == null
          || !expectedMarkerName.equals(
              canonicalMarker.getFileName().toString())
          || Files.isSymbolicLink(canonicalMarker)
          || !Files.isRegularFile(
              canonicalMarker, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
          || !Files.getPosixFilePermissions(
                  directory, LinkOption.NOFOLLOW_LINKS)
              .equals(DIRECTORY_PERMISSIONS)
          || !Files.getPosixFilePermissions(
                  canonicalMarker, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)) {
        throw rejected("RUN_RECORD_PATH_UNSAFE");
      }
      UserPrincipal owner =
          Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
      if (!owner.equals(
              Files.getOwner(
                  canonicalMarker, LinkOption.NOFOLLOW_LINKS))
          || hasForeignAllowAcl(directory, owner)
          || hasForeignAllowAcl(canonicalMarker, owner)) {
        throw rejected("RUN_RECORD_PATH_UNSAFE");
      }

      PersistedRunRecord record = record(outcome, effects);
      byte[] bytes = JSON.writeValueAsBytes(record);
      if (bytes.length < 1 || bytes.length > MAX_RECORD_BYTES) {
        throw rejected("RUN_RECORD_SIZE_INVALID");
      }
      String fileName =
          SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID + ".run.json";
      Path target = directory.resolve(fileName);
      Path pending = directory.resolve(fileName + ".pending");
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw rejected("RUN_RECORD_ALREADY_EXISTS");
      }
      writePending(pending, bytes);
      if (Files.isSymbolicLink(pending)
          || !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)
          || !Files.getPosixFilePermissions(
                  pending, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)
          || !owner.equals(
              Files.getOwner(pending, LinkOption.NOFOLLOW_LINKS))
          || hasForeignAllowAcl(pending, owner)
          || !verifyReadBack(
              pending, bytes, record)) {
        throw rejected("RUN_RECORD_FILE_UNSAFE");
      }
      forceDirectory(directory);
      observer.observed(
          EvalExecutionObserver.Phase.RUN_RECORD_PENDING_DURABLE);
      createOnlyLink(target, pending, Files::createLink);
      forceDirectory(directory);
      observer.observed(
          EvalExecutionObserver.Phase
              .RUN_RECORD_LINK_COMMIT_COMPLETE);
      cleanupCommittedPending(pending, target, Files::delete);
      forceDirectory(directory);
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(target)
          || !Files.getPosixFilePermissions(
                  target, LinkOption.NOFOLLOW_LINKS)
              .equals(FILE_PERMISSIONS)
          || !owner.equals(
              Files.getOwner(target, LinkOption.NOFOLLOW_LINKS))
          || hasForeignAllowAcl(target, owner)
          || pendingLinkState(pending, target)
              == PendingLinkState.DIFFERENT_FILE
          || !verifyReadBack(target, bytes, record)) {
        throw rejected("RUN_RECORD_FILE_UNSAFE");
      }
      observer.observed(
          EvalExecutionObserver.Phase.RUN_RECORD_FINAL_DURABLE);
      return new Stored(fileName, sha256(bytes));
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("RUN_RECORD_PERSIST_FAILED");
    }
  }

  private static void writePending(Path pending, byte[] bytes)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            pending,
            Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(
                FILE_PERMISSIONS))) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    } catch (FileAlreadyExistsException duplicate) {
      throw rejected("RUN_RECORD_PENDING_EXISTS");
    }
  }

  private static void forceDirectory(Path directory)
      throws IOException {
    try (FileChannel channel =
        FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  static void createOnlyLink(
      Path target, Path pending, LinkCreator linkCreator)
      throws IOException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(pending, "pending");
    Objects.requireNonNull(linkCreator, "linkCreator");
    try {
      linkCreator.create(target, pending);
    } catch (FileAlreadyExistsException duplicate) {
      throw rejected("RUN_RECORD_ALREADY_EXISTS");
    } catch (UnsupportedOperationException unsupported) {
      throw rejected("RUN_RECORD_LINK_COMMIT_UNSUPPORTED");
    }
  }

  static void cleanupCommittedPending(
      Path pending, Path target, PendingCleaner pendingCleaner)
      throws IOException {
    Objects.requireNonNull(pending, "pending");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(pendingCleaner, "pendingCleaner");
    try {
      pendingCleaner.delete(pending);
    } catch (IOException cleanupFailure) {
      if (pendingLinkState(pending, target)
          == PendingLinkState.DIFFERENT_FILE) {
        throw cleanupFailure;
      }
    }
  }

  private static PendingLinkState pendingLinkState(
      Path pending, Path target) throws IOException {
    try {
      BasicFileAttributes pendingAttributes =
          Files.readAttributes(
              pending,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes targetAttributes =
          Files.readAttributes(
              target,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      Object pendingKey = pendingAttributes.fileKey();
      Object targetKey = targetAttributes.fileKey();
      return pendingAttributes.isRegularFile()
              && targetAttributes.isRegularFile()
              && pendingKey != null
              && pendingKey.equals(targetKey)
          ? PendingLinkState.SAME_FILE
          : PendingLinkState.DIFFERENT_FILE;
    } catch (NoSuchFileException disappeared) {
      if (Files.notExists(pending, LinkOption.NOFOLLOW_LINKS)) {
        return PendingLinkState.ABSENT;
      }
      throw disappeared;
    }
  }

  private static PersistedRunRecord record(
      AgentDraftOutcome outcome,
      SyntheticEvalExecutor.Effects effects) {
    AgentRun run = outcome.run();
    ArtifactLineage artifact = outcome.artifact();
    String artifactHash =
        artifact == null ? null : artifact.current().contentHash();
    String bundleHash = run.bundle().integrityHash();
    String billingStatus =
        SyntheticEvalExecutor.billingStatus(effects);
    boolean meteringMatchesRun =
        run.result().costUsd().compareTo(
                effects.providerObservedCostUsd())
            == 0
            && run.result().tokenCount()
                == effects.providerObservedTokenCount();
    if (!bundleHash.equals(
            IntegrityHashes.bundleHash(run.bundle()))
        || !run.trace().rootHash().equals(
            run.bundle().traceRootHash())
        || run.result().costUsd().compareTo(
                run.bundle().costUsd())
            != 0
        || run.result().tokenCount()
            != run.bundle().tokenCount()
        || effects.providerSdkCreateInvocations()
            > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || effects.providerAttributedInvocations()
            > effects.providerSdkCreateInvocations()
        || ("NOT_INVOKED".equals(billingStatus)
            && effects.providerSdkCreateInvocations() != 0)
        || ("ATTRIBUTED".equals(billingStatus)
            && (effects.providerSdkCreateInvocations() == 0
                || effects.providerAttributedInvocations()
                    != effects.providerSdkCreateInvocations()))
        || ("UNKNOWN".equals(billingStatus)
            && (effects.providerSdkCreateInvocations() == 0
                || effects.providerAttributedInvocations()
                    == effects.providerSdkCreateInvocations()))) {
      throw rejected("RUN_RECORD_INTEGRITY_MISMATCH");
    }
    return new PersistedRunRecord(
        "1.0",
        SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID,
        SyntheticEvalCatalog.CASE_ID,
        SyntheticEvalCatalog.PACK_RAW_SHA256,
        SyntheticEvalCatalog.ENVIRONMENT_RAW_SHA256,
        bundleHash,
        run.trace().rootHash(),
        artifactHash,
        billingStatus,
        effects.providerObservedCostUsd(),
        effects.providerObservedTokenCount(),
        run.result().costUsd(),
        run.result().tokenCount(),
        meteringMatchesRun,
        effects.providerSdkCreateInvocations(),
        effects.providerAttributedInvocations(),
        effects,
        run,
        artifact);
  }

  private static boolean hasForeignAllowAcl(
      Path path, UserPrincipal owner) throws IOException {
    return VisibleAclPolicy.hasForeignAllow(path, owner);
  }

  private static boolean verifyReadBack(
      Path pending,
      byte[] expectedBytes,
      PersistedRunRecord expected)
      throws IOException {
    byte[] readBack = Files.readAllBytes(pending);
    if (!MessageDigest.isEqual(expectedBytes, readBack)) {
      return false;
    }
    var tree = JSON.readTree(readBack);
    return tree.isObject()
        && expected.schemaVersion().equals(
            tree.path("schemaVersion").asText())
        && expected.attemptId().equals(
            tree.path("attemptId").asText())
        && expected.bundleHash().equals(
            tree.path("bundleHash").asText())
        && expected.bundleHash().equals(
            tree.at("/run/bundle/integrityHash").asText())
        && expected.traceRootHash().equals(
            tree.at("/run/trace/rootHash").asText())
        && expected.billingStatus().equals(
            tree.path("billingStatus").asText())
        && expected.observedCostUsd().compareTo(
                tree.path("observedCostUsd").decimalValue())
            == 0
        && expected.observedTokenCount()
            == tree.path("observedTokenCount").asLong(-1)
        && expected.runCostUsd().compareTo(
                tree.path("runCostUsd").decimalValue())
            == 0
        && expected.runTokenCount()
            == tree.path("runTokenCount").asLong(-1)
        && expected.meteringMatchesRun()
            == tree.path("meteringMatchesRun").asBoolean(
                !expected.meteringMatchesRun())
        && expected.providerSdkCreateInvocations()
            == tree.path("providerSdkCreateInvocations").asInt(-1)
        && expected.providerAttributedInvocations()
            == tree.path("providerAttributedInvocations").asInt(-1);
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

  record Stored(String fileName, String sha256) {

    Stored {
      Objects.requireNonNull(fileName, "fileName");
      Objects.requireNonNull(sha256, "sha256");
    }
  }

  private record PersistedRunRecord(
      String schemaVersion,
      String attemptId,
      String caseId,
      String packSha256,
      String environmentSha256,
      String bundleHash,
      String traceRootHash,
      String artifactContentHash,
      String billingStatus,
      BigDecimal observedCostUsd,
      long observedTokenCount,
      BigDecimal runCostUsd,
      long runTokenCount,
      boolean meteringMatchesRun,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations,
      SyntheticEvalExecutor.Effects effects,
      AgentRun run,
      ArtifactLineage artifact) {}

  private enum PendingLinkState {
    ABSENT,
    SAME_FILE,
    DIFFERENT_FILE
  }

  /** Package-private deterministic test seam, not a runtime extension API. */
  @FunctionalInterface
  interface LinkCreator {

    void create(Path target, Path pending) throws IOException;
  }

  /** Package-private deterministic test seam, not a runtime extension API. */
  @FunctionalInterface
  interface PendingCleaner {

    void delete(Path pending) throws IOException;
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
