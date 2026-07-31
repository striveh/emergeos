package io.emergeos.contracts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable product truth produced by a read-only Worker.
 *
 * <p>The HarnessRunBundle binds this envelope by integrity hash, while the content remains in an
 * owner-scoped product store. It never carries prompts, provider responses or hidden reasoning.
 */
public record WorkerResultEnvelope(
    String schemaVersion,
    String workerResultRef,
    String childRunId,
    String childTaskId,
    String outputSchema,
    String content,
    String contentHash,
    List<String> evidenceRefs,
    String integrityProfile,
    String integrityHash) {

  public static final int MAX_CONTENT_LENGTH = 65_536;

  public WorkerResultEnvelope {
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "WorkerResultEnvelope supports schemaVersion 1.0");
    }
    requireId(childRunId, "childRunId");
    requireId(childTaskId, "childTaskId");
    ContractText.require(workerResultRef, "workerResultRef");
    if (!workerResultRef.equals("worker-result://" + childRunId)) {
      throw new IllegalArgumentException(
          "workerResultRef must identify the same child Run");
    }
    ContractText.require(outputSchema, "outputSchema");
    requireContent(content);
    IntegrityHashes.requireHash(contentHash, "contentHash");
    if (!IntegrityHashes.utf8ContentHash(content).equals(contentHash)) {
      throw new IllegalArgumentException(
          "contentHash does not match Worker Result content");
    }
    evidenceRefs =
        ContractText.copyStrings(
            Objects.requireNonNull(evidenceRefs, "evidenceRefs"),
            "evidenceRefs");
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException(
          "Unsupported Worker Result integrityProfile");
    }
    IntegrityHashes.requireHash(integrityHash, "integrityHash");
    String expected =
        IntegrityHashes.workerResultHash(
            preimage(
                schemaVersion,
                workerResultRef,
                childRunId,
                childTaskId,
                outputSchema,
                content,
                contentHash,
                evidenceRefs,
                integrityProfile));
    if (!expected.equals(integrityHash)) {
      throw new IllegalArgumentException(
          "integrityHash does not match WorkerResultEnvelope");
    }
  }

  public static WorkerResultEnvelope create(
      String childRunId,
      String childTaskId,
      String outputSchema,
      String content,
      List<String> evidenceRefs) {
    requireContent(content);
    String workerResultRef = "worker-result://" + childRunId;
    String contentHash = IntegrityHashes.utf8ContentHash(content);
    Map<String, Object> preimage =
        preimage(
            "1.0",
            workerResultRef,
            childRunId,
            childTaskId,
            outputSchema,
            content,
            contentHash,
            evidenceRefs,
            IntegrityHashes.PROFILE);
    return new WorkerResultEnvelope(
        "1.0",
        workerResultRef,
        childRunId,
        childTaskId,
        outputSchema,
        content,
        contentHash,
        evidenceRefs,
        IntegrityHashes.PROFILE,
        IntegrityHashes.workerResultHash(preimage));
  }

  @Override
  public String toString() {
    return "WorkerResultEnvelope["
        + "workerResultRef="
        + workerResultRef
        + ", childRunId="
        + childRunId
        + ", childTaskId="
        + childTaskId
        + ", outputSchema="
        + outputSchema
        + ", content=[redacted], contentHash="
        + contentHash
        + ", evidenceRefs="
        + evidenceRefs
        + ", integrityProfile="
        + integrityProfile
        + ", integrityHash="
        + integrityHash
        + "]";
  }

  private static Map<String, Object> preimage(
      String schemaVersion,
      String workerResultRef,
      String childRunId,
      String childTaskId,
      String outputSchema,
      String content,
      String contentHash,
      List<String> evidenceRefs,
      String integrityProfile) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("childRunId", childRunId);
    values.put("childTaskId", childTaskId);
    values.put("content", content);
    values.put("contentHash", contentHash);
    values.put("evidenceRefs", evidenceRefs);
    values.put("integrityProfile", integrityProfile);
    values.put("outputSchema", outputSchema);
    values.put("schemaVersion", schemaVersion);
    values.put("workerResultRef", workerResultRef);
    return values;
  }

  private static void requireId(String value, String name) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " has an invalid identifier");
    }
  }

  private static void requireContent(String value) {
    if (value != null && value.length() > MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException(
          "content must be at most 65536 UTF-16 code units");
    }
    ContractText.require(value, "content", MAX_CONTENT_LENGTH);
  }
}
