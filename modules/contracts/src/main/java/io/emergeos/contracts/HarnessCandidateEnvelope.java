package io.emergeos.contracts;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable structured-final observation shared by Harness evaluators.
 *
 * <p>A Candidate is not a successful Worker Result. Reference-grounding
 * faults remain contract-valid so deterministic evaluators can inspect the
 * exact same stochastic model output.
 */
public record HarnessCandidateEnvelope(
    String schemaVersion,
    String candidateRef,
    String attemptId,
    String executionSlotId,
    int repetition,
    String childRunId,
    String childTaskId,
    int sourceRequestOrdinal,
    String sourceResponseHash,
    String traceRootHash,
    String outputSchema,
    String content,
    String contentHash,
    List<String> evidenceRefs,
    List<String> obtainedEvidenceRefs,
    String requiredEvidenceRef,
    boolean requiredEvidenceAvailable,
    String integrityProfile,
    String integrityHash) {

  public static final int MAX_CONTENT_LENGTH = 65_536;
  private static final int MAX_EVIDENCE_REFS = 128;
  private static final String CAPTURE_PATTERN =
      "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}";
  private static final Comparator<String> CODE_POINT_ORDER =
      (left, right) -> {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        int length = Math.min(leftPoints.length, rightPoints.length);
        for (int index = 0; index < length; index++) {
          int comparison =
              Integer.compare(
                  leftPoints[index], rightPoints[index]);
          if (comparison != 0) {
            return comparison;
          }
        }
        return Integer.compare(
            leftPoints.length, rightPoints.length);
      };

  public HarnessCandidateEnvelope {
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "HarnessCandidateEnvelope supports schemaVersion 1.0");
    }
    requireId(childRunId, "childRunId", 128);
    requireId(childTaskId, "childTaskId", 128);
    requireId(executionSlotId, "executionSlotId", 200);
    IntegrityHashes.requireHash(attemptId, "attemptId");
    if (sourceRequestOrdinal != 2) {
      throw new IllegalArgumentException(
          "Candidate must originate from provider request 2");
    }
    IntegrityHashes.requireHash(
        sourceResponseHash, "sourceResponseHash");
    IntegrityHashes.requireHash(traceRootHash, "traceRootHash");
    if (!("harness-candidate://" + childRunId)
        .equals(candidateRef)) {
      throw new IllegalArgumentException(
          "candidateRef must identify the same child Run");
    }
    if (repetition < 1 || repetition > 3) {
      throw new IllegalArgumentException(
          "candidate repetition must be 1, 2 or 3");
    }
    ContractText.require(outputSchema, "outputSchema");
    requireContent(content);
    IntegrityHashes.requireHash(contentHash, "contentHash");
    if (!IntegrityHashes.utf8ContentHash(content)
        .equals(contentHash)) {
      throw new IllegalArgumentException(
          "contentHash does not match Candidate content");
    }
    evidenceRefs =
        requireEvidenceRefs(evidenceRefs, "evidenceRefs");
    obtainedEvidenceRefs =
        requireEvidenceRefs(
            obtainedEvidenceRefs, "obtainedEvidenceRefs");
    if (requiredEvidenceRef == null
        || !requiredEvidenceRef.matches(CAPTURE_PATTERN)) {
      throw new IllegalArgumentException(
          "requiredEvidenceRef must name one Capture");
    }
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException(
          "unsupported Candidate integrityProfile");
    }
    IntegrityHashes.requireHash(integrityHash, "integrityHash");
    String expected =
        IntegrityHashes.harnessCandidateHash(
            preimage(
                schemaVersion,
                candidateRef,
                attemptId,
                executionSlotId,
                repetition,
                childRunId,
                childTaskId,
                sourceRequestOrdinal,
                sourceResponseHash,
                traceRootHash,
                outputSchema,
                content,
                contentHash,
                evidenceRefs,
                obtainedEvidenceRefs,
                requiredEvidenceRef,
                requiredEvidenceAvailable,
                integrityProfile));
    if (!expected.equals(integrityHash)) {
      throw new IllegalArgumentException(
          "integrityHash does not match HarnessCandidateEnvelope");
    }
  }

  public static HarnessCandidateEnvelope create(
      String attemptId,
      String executionSlotId,
      int repetition,
      String childRunId,
      String childTaskId,
      String sourceResponseHash,
      String traceRootHash,
      String outputSchema,
      String content,
      List<String> evidenceRefs,
      List<String> obtainedEvidenceRefs,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable) {
    String schemaVersion = "1.0";
    String candidateRef =
        "harness-candidate://" + childRunId;
    String contentHash =
        IntegrityHashes.utf8ContentHash(content);
    String integrityProfile = IntegrityHashes.PROFILE;
    return new HarnessCandidateEnvelope(
        schemaVersion,
        candidateRef,
        attemptId,
        executionSlotId,
        repetition,
        childRunId,
        childTaskId,
        2,
        sourceResponseHash,
        traceRootHash,
        outputSchema,
        content,
        contentHash,
        evidenceRefs,
        obtainedEvidenceRefs,
        requiredEvidenceRef,
        requiredEvidenceAvailable,
        integrityProfile,
        IntegrityHashes.harnessCandidateHash(
            preimage(
                schemaVersion,
                candidateRef,
                attemptId,
                executionSlotId,
                repetition,
                childRunId,
                childTaskId,
                2,
                sourceResponseHash,
                traceRootHash,
                outputSchema,
                content,
                contentHash,
                evidenceRefs,
                obtainedEvidenceRefs,
                requiredEvidenceRef,
                requiredEvidenceAvailable,
                integrityProfile)));
  }

  @Override
  public String toString() {
    return "HarnessCandidateEnvelope["
        + "candidateRef="
        + candidateRef
        + ", attemptId="
        + attemptId
        + ", executionSlotId="
        + executionSlotId
        + ", repetition="
        + repetition
        + ", childRunId="
        + childRunId
        + ", childTaskId="
        + childTaskId
        + ", sourceRequestOrdinal="
        + sourceRequestOrdinal
        + ", sourceResponseHash="
        + sourceResponseHash
        + ", traceRootHash="
        + traceRootHash
        + ", outputSchema="
        + outputSchema
        + ", content=[redacted], contentHash="
        + contentHash
        + ", evidenceRefs="
        + evidenceRefs
        + ", obtainedEvidenceRefs="
        + obtainedEvidenceRefs
        + ", requiredEvidenceRef="
        + requiredEvidenceRef
        + ", requiredEvidenceAvailable="
        + requiredEvidenceAvailable
        + ", integrityProfile="
        + integrityProfile
        + ", integrityHash="
        + integrityHash
        + "]";
  }

  private static List<String> requireEvidenceRefs(
      List<String> refs, String name) {
    List<String> copy =
        List.copyOf(Objects.requireNonNull(refs, name));
    if (copy.size() > MAX_EVIDENCE_REFS) {
      throw new IllegalArgumentException(
          name + " exceeds the Candidate limit");
    }
    String previous = null;
    for (String ref : copy) {
      if (ref == null || !ref.matches(CAPTURE_PATTERN)) {
        throw new IllegalArgumentException(
            name + " must contain only Capture refs");
      }
      if (previous != null
          && CODE_POINT_ORDER.compare(previous, ref) >= 0) {
        throw new IllegalArgumentException(
            name + " must be unique and canonically ordered");
      }
      previous = ref;
    }
    return copy;
  }

  private static void requireContent(String value) {
    if (value == null
        || value.length() > MAX_CONTENT_LENGTH
        || ContractText.containsLoneSurrogate(value)) {
      throw new IllegalArgumentException(
          "Candidate content is outside the frozen domain");
    }
    ContractText.require(
        value, "content", MAX_CONTENT_LENGTH);
  }

  private static void requireId(
      String value, String name, int maximumLength) {
    if (value == null
        || value.length() > maximumLength
        || !value.matches(
            "[A-Za-z0-9][A-Za-z0-9._~-]*")) {
      throw new IllegalArgumentException(
          name + " has an invalid identifier");
    }
  }

  private static Map<String, Object> preimage(
      String schemaVersion,
      String candidateRef,
      String attemptId,
      String executionSlotId,
      int repetition,
      String childRunId,
      String childTaskId,
      int sourceRequestOrdinal,
      String sourceResponseHash,
      String traceRootHash,
      String outputSchema,
      String content,
      String contentHash,
      List<String> evidenceRefs,
      List<String> obtainedEvidenceRefs,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable,
      String integrityProfile) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("attemptId", attemptId);
    values.put("candidateRef", candidateRef);
    values.put("childRunId", childRunId);
    values.put("childTaskId", childTaskId);
    values.put("content", content);
    values.put("contentHash", contentHash);
    values.put("evidenceRefs", evidenceRefs);
    values.put("executionSlotId", executionSlotId);
    values.put("integrityProfile", integrityProfile);
    values.put(
        "obtainedEvidenceRefs", obtainedEvidenceRefs);
    values.put("outputSchema", outputSchema);
    values.put("repetition", repetition);
    values.put(
        "requiredEvidenceAvailable",
        requiredEvidenceAvailable);
    values.put("requiredEvidenceRef", requiredEvidenceRef);
    values.put("schemaVersion", schemaVersion);
    values.put("sourceRequestOrdinal", sourceRequestOrdinal);
    values.put("sourceResponseHash", sourceResponseHash);
    values.put("traceRootHash", traceRootHash);
    return values;
  }
}
