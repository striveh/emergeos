package io.emergeos.core.application;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessEvaluationReport;
import io.emergeos.contracts.HarnessEvaluationReport.Evaluation;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.AgentDraftProposal;
import java.util.Objects;

/** Pure paired H0/H1 evaluation over one immutable shared Candidate. */
final class SharedCandidateHarnessEvaluator {

  private SharedCandidateHarnessEvaluator() {}

  static EvaluationPair evaluate(
      HarnessCandidateEnvelope sharedCandidate) {
    Objects.requireNonNull(sharedCandidate, "sharedCandidate");
    if (!IntegrityHashes.utf8ContentHash(sharedCandidate.content())
            .equals(sharedCandidate.contentHash())
        || !IntegrityHashes.harnessCandidateHash(sharedCandidate)
            .equals(sharedCandidate.integrityHash())) {
      throw new IllegalArgumentException(
          "H0 rejected a malformed shared Candidate");
    }
    Evaluation h0 =
        new Evaluation(
            sharedCandidate.repetition(),
            HarnessEvaluationReport.H0_ARM_ID,
            HarnessEvaluationReport.H0_EVALUATOR_VERSION,
            sharedCandidate.candidateRef(),
            sharedCandidate.integrityHash(),
            EvaluationStatus.ACCEPTED,
            null);
    AgentDraftReferenceGrounding.Verification h1Result =
        AgentDraftReferenceGrounding.verify(
            new AgentDraftReferenceGrounding.Candidate(
                new AgentDraftProposal(
                    sharedCandidate.content(),
                    sharedCandidate.evidenceRefs()),
                sharedCandidate.obtainedEvidenceRefs(),
                sharedCandidate.requiredEvidenceRef(),
                sharedCandidate.requiredEvidenceAvailable()));
    Evaluation h1 =
        new Evaluation(
            sharedCandidate.repetition(),
            HarnessEvaluationReport.H1_ARM_ID,
            AgentDraftReferenceGrounding.VERIFIER_VERSION,
            sharedCandidate.candidateRef(),
            sharedCandidate.integrityHash(),
            h1Result.accepted()
                ? EvaluationStatus.ACCEPTED
                : EvaluationStatus.REJECTED,
            h1Result.failureCode());
    return new EvaluationPair(h0, h1);
  }

  record EvaluationPair(Evaluation h0, Evaluation h1) {
    EvaluationPair {
      Objects.requireNonNull(h0, "h0");
      Objects.requireNonNull(h1, "h1");
    }
  }
}
