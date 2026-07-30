package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.AgentDraftProposal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDraftVerifierTest {

  private static final String CAPTURE_REF = "capture://synthetic-verifier-test";
  private final AgentDraftVerifier verifier =
      ReferenceGroundingAgentDraftVerifier.INSTANCE;

  @Test
  void productionVerifierHasTheFrozenH1Identity() {
    assertEquals(
        AgentDraftReferenceGrounding.VERIFIER_VERSION,
        verifier.version());
    assertEquals(0, AgentDraftReferenceGrounding.class.getConstructors().length);
  }

  @Test
  void acceptsOnlyTheGroundedStructuredCandidate() {
    AgentDraftReferenceGrounding.Verification verification =
        verify(
            new AgentDraftProposal("grounded draft", List.of(CAPTURE_REF)),
            List.of(CAPTURE_REF),
            true);

    assertTrue(verification.accepted());
    assertNull(verification.failureCode());
  }

  @Test
  void invalidStructuredFinalUsesAStableFailureCode() {
    for (AgentDraftProposal proposal :
        java.util.Arrays.asList(
            null,
            new AgentDraftProposal("", List.of(CAPTURE_REF)),
            new AgentDraftProposal(
                "x".repeat(65_537), List.of(CAPTURE_REF)),
            new AgentDraftProposal(
                "before\0after", List.of(CAPTURE_REF)))) {
      assertRejected(
          verify(proposal, List.of(CAPTURE_REF), true),
          "INVALID_STRUCTURED_FINAL");
    }
  }

  @Test
  void missingToolEvidenceUsesAStableFailureCode() {
    assertRejected(
        verify(
            new AgentDraftProposal("unread claim", List.of(CAPTURE_REF)),
            List.of(),
            true),
        "MISSING_REQUIRED_EVIDENCE");
  }

  @Test
  void extraOrForgedToolEvidenceFailsClosedInTheReusableFacade() {
    for (List<String> obtainedEvidenceRefs :
        List.of(
            List.of("capture://forged"),
            List.of(CAPTURE_REF, "capture://forged"),
            List.of(CAPTURE_REF, CAPTURE_REF))) {
      assertRejected(
          AgentDraftReferenceGrounding.verify(
              candidate(
                  new AgentDraftProposal(
                      "unsafe tool evidence", List.of(CAPTURE_REF)),
                  obtainedEvidenceRefs,
                  true)),
          "UNSAFE_EVIDENCE_BINDING");
    }
  }

  @Test
  void omittedAndExtraClaimsUseAStableFailureCode() {
    for (List<String> evidenceRefs :
        List.of(
            List.<String>of(),
            List.of(CAPTURE_REF, "capture://forged"))) {
      assertRejected(
          verify(
              new AgentDraftProposal("invalid claim", evidenceRefs),
              List.of(CAPTURE_REF),
              true),
          "INVALID_EVIDENCE_CLAIM");
    }
  }

  @Test
  void unavailableRequiredEvidenceUsesAStableFailureCode() {
    assertRejected(
        verify(
            new AgentDraftProposal("missing source", List.of(CAPTURE_REF)),
            List.of(CAPTURE_REF),
            false),
        "REQUIRED_EVIDENCE_NOT_FOUND");
  }

  @Test
  void reusableFacadeRejectsANonCaptureRequiredReference() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDraftReferenceGrounding.Candidate(
                new AgentDraftProposal("invalid ref", List.of("not-a-ref")),
                List.of("not-a-ref"),
                "not-a-ref",
            true));
  }

  @Test
  void candidateDefensivelyCopiesObservedEvidenceAndRejectsNullBoundaries() {
    List<String> mutableObservedRefs =
        new ArrayList<>(List.of(CAPTURE_REF));
    AgentDraftReferenceGrounding.Candidate candidate =
        candidate(
            new AgentDraftProposal(
                "grounded draft", List.of(CAPTURE_REF)),
            mutableObservedRefs,
            true);

    mutableObservedRefs.clear();

    assertEquals(List.of(CAPTURE_REF), candidate.obtainedEvidenceRefs());
    assertThrows(
        NullPointerException.class,
        () ->
            new AgentDraftReferenceGrounding.Candidate(
                candidate.proposal(),
                null,
                CAPTURE_REF,
                true));
    assertThrows(
        NullPointerException.class,
        () -> AgentDraftReferenceGrounding.verify(null));
  }

  @Test
  void internalProductionVerifierDelegatesToTheReusableH1Facade() {
    AgentDraftReferenceGrounding.Candidate candidate =
        candidate(
            new AgentDraftProposal("grounded draft", List.of(CAPTURE_REF)),
            List.of(CAPTURE_REF),
            true);

    assertEquals(
        AgentDraftReferenceGrounding.verify(candidate),
        verifier.verify(candidate));
  }

  private AgentDraftReferenceGrounding.Verification verify(
      AgentDraftProposal proposal,
      List<String> obtainedEvidenceRefs,
      boolean requiredEvidenceAvailable) {
    return AgentDraftReferenceGrounding.verify(
        candidate(proposal, obtainedEvidenceRefs, requiredEvidenceAvailable));
  }

  private static AgentDraftReferenceGrounding.Candidate candidate(
      AgentDraftProposal proposal,
      List<String> obtainedEvidenceRefs,
      boolean requiredEvidenceAvailable) {
    return new AgentDraftReferenceGrounding.Candidate(
        proposal,
        obtainedEvidenceRefs,
        CAPTURE_REF,
        requiredEvidenceAvailable);
  }

  private static void assertRejected(
      AgentDraftReferenceGrounding.Verification verification,
      String failureCode) {
    assertFalse(verification.accepted());
    assertEquals(failureCode, verification.failureCode());
  }
}
