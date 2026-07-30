package io.emergeos.offlineharness;

import io.emergeos.core.application.AgentDraftReferenceGrounding;
import io.emergeos.core.domain.AgentDraftProposal;
import java.util.List;
import java.util.Objects;

final class LiteralReferenceCandidateGenerator
{

  static final String VERSION =
      "literal-reference-candidate-fixture-v1";

  GeneratedCandidate generate(
      Pack004Loader.Frozen frozen,
      Pack004Loader.CaseDefinition testCase,
      int repetition,
      OwnedEffectRecorder effects) {
    Objects.requireNonNull(frozen, "frozen");
    Objects.requireNonNull(testCase, "testCase");
    Objects.requireNonNull(effects, "effects");
    if (repetition < 1) {
      throw rejected("COMPARISON_GENERATION_FAILED");
    }

    try {
      String requiredRef = "capture://" + frozen.captureId();
      List<String> proposalRefs;
      List<String> obtainedRefs;
      String proposalContent;
      LiteralSyntheticCaptureReader reader =
          new LiteralSyntheticCaptureReader();
      switch (testCase.candidateMode()) {
        case "GROUNDED" -> {
          proposalContent =
              reader.read(frozen, requiredRef, effects);
          proposalRefs = List.of(requiredRef);
          obtainedRefs = List.of(requiredRef);
        }
        case "CLAIM_WITHOUT_TOOL" -> {
          proposalContent = frozen.content();
          proposalRefs = List.of(requiredRef);
          obtainedRefs = List.of();
        }
        case "OMITTED_EVIDENCE_REF" -> {
          proposalContent =
              reader.read(frozen, requiredRef, effects);
          proposalRefs = List.of();
          obtainedRefs = List.of(requiredRef);
        }
        case "EXTRA_EVIDENCE_REF" -> {
          proposalContent =
              reader.read(frozen, requiredRef, effects);
          proposalRefs =
              List.of(requiredRef, requiredRef + "-forged");
          obtainedRefs = List.of(requiredRef);
        }
        default -> throw rejected("COMPARISON_GENERATION_FAILED");
      }

      AgentDraftProposal proposal =
          new AgentDraftProposal(proposalContent, proposalRefs);
      AgentDraftReferenceGrounding.Candidate candidate =
          new AgentDraftReferenceGrounding.Candidate(
              proposal, obtainedRefs, requiredRef, true);
      return new GeneratedCandidate(candidate);
    } catch (ComparisonExecutionRejected failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw rejected("COMPARISON_GENERATION_FAILED");
    }
  }

  static final class LiteralSyntheticCaptureReader {

    String read(
        Pack004Loader.Frozen frozen,
        String requiredRef,
        OwnedEffectRecorder effects) {
      Objects.requireNonNull(frozen, "frozen");
      Objects.requireNonNull(requiredRef, "requiredRef");
      Objects.requireNonNull(effects, "effects");
      if (!requiredRef.equals("capture://" + frozen.captureId())) {
        throw rejected("COMPARISON_LITERAL_READ_FAILED");
      }
      effects.recordLiteralFixtureRead();
      return frozen.content();
    }
  }

  record GeneratedCandidate(
      AgentDraftReferenceGrounding.Candidate candidate) {
    GeneratedCandidate {
      Objects.requireNonNull(candidate, "candidate");
    }

    OfflineComparisonReport.CandidateSnapshot snapshot() {
      AgentDraftProposal proposal = candidate.proposal();
      OfflineComparisonReport.ProposalSnapshot proposalSnapshot =
          proposal == null
              ? null
              : new OfflineComparisonReport.ProposalSnapshot(
                  proposal.content(), proposal.evidenceRefs());
      return new OfflineComparisonReport.CandidateSnapshot(
          proposalSnapshot,
          candidate.obtainedEvidenceRefs(),
          candidate.requiredEvidenceRef(),
          candidate.requiredEvidenceAvailable());
    }
  }

  static final class OwnedEffectRecorder {
    private int sharedCandidateGenerations;
    private int verifierEvaluations;
    private int h0Evaluations;
    private int h1Evaluations;
    private int literalFixtureReads;
    private final java.util.IdentityHashMap<
            GeneratedCandidate, CandidateUse>
        candidateUses = new java.util.IdentityHashMap<>();

    void recordCandidateGeneration(GeneratedCandidate candidate) {
      Objects.requireNonNull(candidate, "candidate");
      if (candidateUses.put(candidate, new CandidateUse()) != null) {
        throw rejected("COMPARISON_CANDIDATE_IDENTITY_INVALID");
      }
      sharedCandidateGenerations++;
    }

    private void recordLiteralFixtureRead() {
      literalFixtureReads++;
    }

    void recordH0Evaluation(GeneratedCandidate candidate) {
      candidateUse(candidate).recordH0();
      verifierEvaluations++;
      h0Evaluations++;
    }

    void recordH1Evaluation(GeneratedCandidate candidate) {
      candidateUse(candidate).recordH1();
      verifierEvaluations++;
      h1Evaluations++;
    }

    void requireSharedCandidateIdentity(int expectedPairs) {
      boolean accepted =
          candidateUses.size() == expectedPairs
              && candidateUses.values().stream()
                  .allMatch(
                      use ->
                          use.h0Evaluations == 1
                              && use.h1Evaluations == 1);
      if (!accepted) {
        throw rejected("COMPARISON_CANDIDATE_IDENTITY_INVALID");
      }
    }

    private CandidateUse candidateUse(GeneratedCandidate candidate) {
      CandidateUse use = candidateUses.get(candidate);
      if (use == null) {
        throw rejected("COMPARISON_CANDIDATE_IDENTITY_INVALID");
      }
      return use;
    }

    OfflineComparisonReport.OwnedEffects snapshot() {
      return new OfflineComparisonReport.OwnedEffects(
          "OWNED_OFFLINE_RUNNER_SEAMS_V1",
          sharedCandidateGenerations,
          verifierEvaluations,
          h0Evaluations,
          h1Evaluations,
          literalFixtureReads,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0);
    }

    private static final class CandidateUse {
      private int h0Evaluations;
      private int h1Evaluations;

      void recordH0() {
        h0Evaluations++;
        requireSingleUse();
      }

      void recordH1() {
        h1Evaluations++;
        requireSingleUse();
      }

      private void requireSingleUse() {
        if (h0Evaluations > 1 || h1Evaluations > 1) {
          throw rejected("COMPARISON_CANDIDATE_IDENTITY_INVALID");
        }
      }
    }
  }

  static final class ComparisonExecutionRejected
      extends RuntimeException {
    private final String code;

    private ComparisonExecutionRejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  static ComparisonExecutionRejected rejected(String code) {
    return new ComparisonExecutionRejected(code);
  }
}
