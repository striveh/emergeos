package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessCandidateEnvelopeTest {

  private static final String ATTEMPT = "a".repeat(64);
  private static final String TRACE = "b".repeat(64);
  private static final String SOURCE_RESPONSE = "c".repeat(64);
  private static final String OUTPUT_SCHEMA =
      "urn:emergeos:schema:internal:agent-draft-proposal:v1";

  @Test
  void createsUnicodeCandidateAndRedactsContent() {
    String content = "参透世界之后，仍然认真生活。🌌";
    HarnessCandidateEnvelope candidate =
        candidate(
            content,
            List.of("capture://capture-1"),
            List.of("capture://capture-1"),
            true);

    assertEquals(
        IntegrityHashes.utf8ContentHash(content),
        candidate.contentHash());
    assertEquals(
        "0480985aa0f9b80f8890274c3f870c1b29760a7a31d5e519af88099a1114a314",
        candidate.contentHash());
    assertEquals(
        "854c32db38c90df1757d66bf09389af63736b71a4ff4d8401ba91eaadacbefff",
        candidate.integrityHash());
    assertEquals(
        candidate.integrityHash(),
        IntegrityHashes.harnessCandidateHash(candidate));
    assertTrue(
        candidate
            .candidateRef()
            .equals("harness-candidate://child-run"));
    assertEquals(2, candidate.sourceRequestOrdinal());
    assertEquals(
        SOURCE_RESPONSE, candidate.sourceResponseHash());
    assertFalse(candidate.toString().contains(content));
    assertTrue(candidate.toString().contains("[redacted]"));
  }

  @Test
  void usesTheFrozenUtf16ContentBoundary() {
    String maximum = "🌌".repeat(32_768);
    HarnessCandidateEnvelope accepted =
        candidate(maximum, List.of(), List.of(), true);
    assertEquals(65_536, accepted.content().length());

    String tooLong = maximum + "x";
    assertThrows(
        IllegalArgumentException.class,
        () -> candidate(tooLong, List.of(), List.of(), true));
  }

  @Test
  void rejectsContentTamperEvenWithRecomputedOuterHash() {
    HarnessCandidateEnvelope original =
        candidate("原始内容", List.of(), List.of(), true);
    String changed = "被修改的内容";
    Map<String, Object> preimage =
        preimage(
            changed,
            original.contentHash(),
            original.evidenceRefs(),
            original.obtainedEvidenceRefs(),
            original.requiredEvidenceAvailable());
    String recomputed =
        IntegrityHashes.harnessCandidateHash(preimage);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessCandidateEnvelope(
                "1.0",
                "harness-candidate://child-run",
                ATTEMPT,
                "pack010-r1",
                1,
                "child-run",
                "child-task",
                2,
                SOURCE_RESPONSE,
                TRACE,
                OUTPUT_SCHEMA,
                changed,
                original.contentHash(),
                List.of(),
                List.of(),
                "capture://capture-1",
                true,
                IntegrityHashes.PROFILE,
                recomputed));
  }

  @Test
  void evidenceListsMustBeUniqueAndCanonicallyOrdered() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            candidate(
                "content",
                List.of(
                    "capture://z",
                    "capture://a"),
                List.of(),
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            candidate(
                "content",
                List.of(
                    "capture://a",
                    "capture://a"),
                List.of(),
                true));
  }

  @Test
  void permitsAContractValidReferenceGroundingFault() {
    HarnessCandidateEnvelope candidate =
        candidate(
            "结构合法，但引用错误",
            List.of("capture://other"),
            List.of("capture://capture-1"),
            true);

    assertEquals(
        List.of("capture://other"),
        candidate.evidenceRefs());
    assertEquals(
        "capture://capture-1",
        candidate.requiredEvidenceRef());
  }

  @Test
  void requiresTheSecondProviderResponseIdentity() {
    HarnessCandidateEnvelope original =
        candidate(
            "content",
            List.of("capture://capture-1"),
            List.of("capture://capture-1"),
            true);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessCandidateEnvelope(
                original.schemaVersion(),
                original.candidateRef(),
                original.attemptId(),
                original.executionSlotId(),
                original.repetition(),
                original.childRunId(),
                original.childTaskId(),
                1,
                original.sourceResponseHash(),
                original.traceRootHash(),
                original.outputSchema(),
                original.content(),
                original.contentHash(),
                original.evidenceRefs(),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable(),
                original.integrityProfile(),
                original.integrityHash()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCandidateEnvelope.create(
                ATTEMPT,
                "pack010-r1",
                1,
                "child-run",
                "child-task",
                "not-a-hash",
                TRACE,
                OUTPUT_SCHEMA,
                "content",
                List.of(),
                List.of(),
                "capture://capture-1",
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessCandidateEnvelope(
                original.schemaVersion(),
                original.candidateRef(),
                original.attemptId(),
                original.executionSlotId(),
                original.repetition(),
                original.childRunId(),
                original.childTaskId(),
                original.sourceRequestOrdinal(),
                "d".repeat(64),
                original.traceRootHash(),
                original.outputSchema(),
                original.content(),
                original.contentHash(),
                original.evidenceRefs(),
                original.obtainedEvidenceRefs(),
                original.requiredEvidenceRef(),
                original.requiredEvidenceAvailable(),
                original.integrityProfile(),
                original.integrityHash()));
  }

  private static HarnessCandidateEnvelope candidate(
      String content,
      List<String> evidenceRefs,
      List<String> obtainedEvidenceRefs,
      boolean requiredEvidenceAvailable) {
    return HarnessCandidateEnvelope.create(
        ATTEMPT,
        "pack010-r1",
        1,
        "child-run",
        "child-task",
        SOURCE_RESPONSE,
        TRACE,
        OUTPUT_SCHEMA,
        content,
        evidenceRefs,
        obtainedEvidenceRefs,
        "capture://capture-1",
        requiredEvidenceAvailable);
  }

  private static Map<String, Object> preimage(
      String content,
      String contentHash,
      List<String> evidenceRefs,
      List<String> obtainedEvidenceRefs,
      boolean requiredEvidenceAvailable) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("attemptId", ATTEMPT);
    values.put(
        "candidateRef",
        "harness-candidate://child-run");
    values.put("childRunId", "child-run");
    values.put("childTaskId", "child-task");
    values.put("content", content);
    values.put("contentHash", contentHash);
    values.put("evidenceRefs", evidenceRefs);
    values.put("executionSlotId", "pack010-r1");
    values.put("integrityProfile", IntegrityHashes.PROFILE);
    values.put(
        "obtainedEvidenceRefs", obtainedEvidenceRefs);
    values.put("outputSchema", OUTPUT_SCHEMA);
    values.put("repetition", 1);
    values.put(
        "requiredEvidenceAvailable",
        requiredEvidenceAvailable);
    values.put(
        "requiredEvidenceRef", "capture://capture-1");
    values.put("schemaVersion", "1.0");
    values.put("sourceRequestOrdinal", 2);
    values.put("sourceResponseHash", SOURCE_RESPONSE);
    values.put("traceRootHash", TRACE);
    return values;
  }
}
