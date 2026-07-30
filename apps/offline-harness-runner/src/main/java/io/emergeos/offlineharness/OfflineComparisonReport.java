package io.emergeos.offlineharness;

import java.util.List;
import java.util.Objects;

record OfflineComparisonReport(
    String schemaVersion,
    String reportKind,
    String reportId,
    String integrityProfile,
    String packRawSha256,
    String taskId,
    String suiteId,
    String variable,
    int repetitions,
    String runnerVersion,
    String candidateGeneratorVersion,
    String h0VerifierVersion,
    String h1VerifierVersion,
    String frozenTime,
    List<PairRecord> pairs,
    List<VerifierEvaluation> evaluations,
    Summary summary,
    OwnedEffects effects,
    Status status,
    List<Issue> issues,
    String integrityHash) {

  OfflineComparisonReport {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(reportKind, "reportKind");
    Objects.requireNonNull(reportId, "reportId");
    Objects.requireNonNull(integrityProfile, "integrityProfile");
    Objects.requireNonNull(packRawSha256, "packRawSha256");
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(suiteId, "suiteId");
    Objects.requireNonNull(variable, "variable");
    requireNonNegative(repetitions, "repetitions");
    Objects.requireNonNull(runnerVersion, "runnerVersion");
    Objects.requireNonNull(
        candidateGeneratorVersion, "candidateGeneratorVersion");
    Objects.requireNonNull(h0VerifierVersion, "h0VerifierVersion");
    Objects.requireNonNull(h1VerifierVersion, "h1VerifierVersion");
    Objects.requireNonNull(frozenTime, "frozenTime");
    pairs = List.copyOf(Objects.requireNonNull(pairs, "pairs"));
    evaluations =
        List.copyOf(Objects.requireNonNull(evaluations, "evaluations"));
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(effects, "effects");
    Objects.requireNonNull(status, "status");
    issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    Objects.requireNonNull(integrityHash, "integrityHash");
  }

  enum Status {
    PASSED,
    FAILED
  }

  record ProposalSnapshot(String content, List<String> evidenceRefs) {
    ProposalSnapshot {
      evidenceRefs =
          List.copyOf(
              Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
  }

  record CandidateSnapshot(
      ProposalSnapshot proposal,
      List<String> obtainedEvidenceRefs,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable) {
    CandidateSnapshot {
      obtainedEvidenceRefs =
          List.copyOf(
              Objects.requireNonNull(
                  obtainedEvidenceRefs, "obtainedEvidenceRefs"));
      Objects.requireNonNull(
          requiredEvidenceRef, "requiredEvidenceRef");
    }
  }

  record PairRecord(
      String pairId,
      String executionBaseId,
      String caseId,
      int repetition,
      String candidateMode,
      String faultClass,
      String candidateGeneratorVersion,
      CandidateSnapshot candidate,
      String candidateFingerprint,
      String integrityHash) {
    PairRecord {
      Objects.requireNonNull(pairId, "pairId");
      Objects.requireNonNull(executionBaseId, "executionBaseId");
      Objects.requireNonNull(caseId, "caseId");
      requireNonNegative(repetition, "repetition");
      Objects.requireNonNull(candidateMode, "candidateMode");
      Objects.requireNonNull(faultClass, "faultClass");
      Objects.requireNonNull(
          candidateGeneratorVersion, "candidateGeneratorVersion");
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(
          candidateFingerprint, "candidateFingerprint");
      Objects.requireNonNull(integrityHash, "integrityHash");
    }
  }

  record VerifierEvaluation(
      String evaluationId,
      String pairId,
      String executionBaseId,
      String caseId,
      int repetition,
      String armId,
      String verifierVersion,
      String candidateFingerprint,
      String expectedStatus,
      String expectedFailureCode,
      String observedStatus,
      String observedFailureCode,
      boolean matchesExpected,
      String integrityHash) {
    VerifierEvaluation {
      Objects.requireNonNull(evaluationId, "evaluationId");
      Objects.requireNonNull(pairId, "pairId");
      Objects.requireNonNull(executionBaseId, "executionBaseId");
      Objects.requireNonNull(caseId, "caseId");
      requireNonNegative(repetition, "repetition");
      Objects.requireNonNull(armId, "armId");
      Objects.requireNonNull(verifierVersion, "verifierVersion");
      Objects.requireNonNull(
          candidateFingerprint, "candidateFingerprint");
      Objects.requireNonNull(expectedStatus, "expectedStatus");
      Objects.requireNonNull(observedStatus, "observedStatus");
      Objects.requireNonNull(integrityHash, "integrityHash");
    }
  }

  record Summary(
      int sharedCandidateGenerations,
      int verifierEvaluations,
      int h0Accepted,
      int h0FaultAcceptances,
      int h1Accepted,
      int h1FaultRejections,
      int outcomeMismatches) {
    Summary {
      requireNonNegative(
          sharedCandidateGenerations, "sharedCandidateGenerations");
      requireNonNegative(verifierEvaluations, "verifierEvaluations");
      requireNonNegative(h0Accepted, "h0Accepted");
      requireNonNegative(h0FaultAcceptances, "h0FaultAcceptances");
      requireNonNegative(h1Accepted, "h1Accepted");
      requireNonNegative(h1FaultRejections, "h1FaultRejections");
      requireNonNegative(outcomeMismatches, "outcomeMismatches");
    }
  }

  record OwnedEffects(
      String evidenceScope,
      int sharedCandidateGenerations,
      int verifierEvaluations,
      int h0Evaluations,
      int h1Evaluations,
      int literalFixtureReads,
      int agentKernelRuns,
      int productAgentRuns,
      int modelInvocations,
      int toolLoopExecutions,
      int harnessRunBundles,
      int credentialReads,
      int networkCalls,
      int connectorCalls,
      int productTruthWrites,
      int externalSideEffects,
      int realUserDataReads) {
    OwnedEffects {
      Objects.requireNonNull(evidenceScope, "evidenceScope");
      for (int value :
          new int[] {
            sharedCandidateGenerations,
            verifierEvaluations,
            h0Evaluations,
            h1Evaluations,
            literalFixtureReads,
            agentKernelRuns,
            productAgentRuns,
            modelInvocations,
            toolLoopExecutions,
            harnessRunBundles,
            credentialReads,
            networkCalls,
            connectorCalls,
            productTruthWrites,
            externalSideEffects,
            realUserDataReads
          }) {
        requireNonNegative(value, "effect");
      }
    }
  }

  record Issue(
      String code, String caseId, int repetition, String armId) {
    Issue {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(caseId, "caseId");
      requireNonNegative(repetition, "repetition");
      Objects.requireNonNull(armId, "armId");
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
