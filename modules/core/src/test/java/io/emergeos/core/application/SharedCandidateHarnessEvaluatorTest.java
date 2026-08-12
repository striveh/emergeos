package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.contracts.IntegrityHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedCandidateHarnessEvaluatorTest {

  private static final String REQUIRED = "capture://required";

  @Test
  void acceptsOneGroundedCandidateInBothOrderedArms() {
    HarnessCandidateEnvelope candidate =
        candidate(List.of(REQUIRED), List.of(REQUIRED), true);

    SharedCandidateHarnessEvaluator.EvaluationPair pair =
        SharedCandidateHarnessEvaluator.evaluate(candidate);

    assertEquals(EvaluationStatus.ACCEPTED, pair.h0().status());
    assertEquals(EvaluationStatus.ACCEPTED, pair.h1().status());
    assertNull(pair.h0().failureCode());
    assertNull(pair.h1().failureCode());
    assertEquals(pair.h0().candidateRef(), pair.h1().candidateRef());
    assertEquals(
        pair.h0().candidateIntegrityHash(),
        pair.h1().candidateIntegrityHash());
  }

  @Test
  void h0AcceptsEveryContractValidGroundingFaultWhileH1KeepsPrecedence() {
    assertPair(
        candidate(List.of(REQUIRED), List.of(), true),
        "MISSING_REQUIRED_EVIDENCE");
    assertPair(
        candidate(
            List.of(REQUIRED),
            List.of("capture://other"),
            true),
        "UNSAFE_EVIDENCE_BINDING");
    assertPair(
        candidate(
            List.of("capture://other"),
            List.of(REQUIRED),
            true),
        "INVALID_EVIDENCE_CLAIM");
    assertPair(
        candidate(List.of(REQUIRED), List.of(REQUIRED), false),
        "REQUIRED_EVIDENCE_NOT_FOUND");
  }

  private static void assertPair(
      HarnessCandidateEnvelope candidate, String failureCode) {
    SharedCandidateHarnessEvaluator.EvaluationPair pair =
        SharedCandidateHarnessEvaluator.evaluate(candidate);
    assertEquals(EvaluationStatus.ACCEPTED, pair.h0().status());
    assertNull(pair.h0().failureCode());
    assertEquals(EvaluationStatus.REJECTED, pair.h1().status());
    assertEquals(failureCode, pair.h1().failureCode());
    assertEquals(candidate.candidateRef(), pair.h0().candidateRef());
    assertEquals(candidate.candidateRef(), pair.h1().candidateRef());
    assertEquals(
        candidate.integrityHash(),
        pair.h0().candidateIntegrityHash());
    assertEquals(
        candidate.integrityHash(),
        pair.h1().candidateIntegrityHash());
  }

  private static HarnessCandidateEnvelope candidate(
      List<String> claimed,
      List<String> obtained,
      boolean available) {
    return HarnessCandidateEnvelope.create(
        "a".repeat(64),
        "shared-candidate-slot",
        1,
        "shared-candidate-child-run",
        "shared-candidate-child-task",
        "b".repeat(64),
        "c".repeat(64),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        "Prompt 工程是在构造运行时状态。🌌",
        claimed,
        obtained,
        REQUIRED,
        available);
  }
}
