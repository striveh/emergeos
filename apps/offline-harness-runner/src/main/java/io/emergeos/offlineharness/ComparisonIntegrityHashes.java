package io.emergeos.offlineharness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ComparisonIntegrityHashes {

  static final String PROFILE =
      "emergeos-length-prefixed-sha256-v1";
  private static final String CANDIDATE_DOMAIN =
      "emergeos.offline-harness.candidate.v1";
  private static final String REPORT_ID_DOMAIN =
      "emergeos.offline-harness.report-id.v1";
  private static final String PAIR_ID_DOMAIN =
      "emergeos.offline-harness.pair-id.v1";
  private static final String PAIR_DOMAIN =
      "emergeos.offline-harness.pair.v1";
  private static final String EVALUATION_ID_DOMAIN =
      "emergeos.offline-harness.evaluation-id.v1";
  private static final String EVALUATION_DOMAIN =
      "emergeos.offline-harness.evaluation.v1";
  private static final String REPORT_DOMAIN =
      "emergeos.offline-harness.report.v1";

  private ComparisonIntegrityHashes() {}

  static String candidateFingerprint(
      OfflineComparisonReport.CandidateSnapshot candidate) {
    return ComparisonCanonicalEncoding.domainHash(
        CANDIDATE_DOMAIN, candidateValue(candidate));
  }

  static String reportId(
      String schemaVersion,
      String reportKind,
      String runnerVersion,
      Pack004Loader.LoadedPack loaded) {
    Pack004Loader.Pack004 pack = loaded.pack();
    Pack004Loader.HarnessComparison comparison =
        pack.harnessComparison();
    List<Object> arms = new ArrayList<>();
    for (Pack004Loader.Arm arm : comparison.arms()) {
      arms.add(
          map(
              "armId", arm.id(),
              "verifierVersion", arm.verifier()));
    }
    List<String> caseIds =
        comparison.cases().stream()
            .map(Pack004Loader.CaseDefinition::id)
            .toList();
    Map<String, Object> value =
        map(
            "schemaVersion", schemaVersion,
            "reportKind", reportKind,
            "runnerVersion", runnerVersion,
            "packRawSha256", loaded.rawSha256(),
            "taskId", pack.taskId(),
            "suiteId", comparison.suiteId(),
            "variable", comparison.variable(),
            "candidateGeneratorVersion",
                comparison.frozen().candidateGeneratorVersion(),
            "integrityProfile", PROFILE,
            "repetitions", comparison.repetitions(),
            "caseIds", caseIds,
            "arms", arms);
    return "comparison-report-"
        + ComparisonCanonicalEncoding.domainHash(
            REPORT_ID_DOMAIN, value);
  }

  static String pairId(
      String reportId,
      String caseId,
      String executionBaseId,
      int repetition,
      String candidateGeneratorVersion,
      String candidateFingerprint) {
    return "comparison-pair-"
        + ComparisonCanonicalEncoding.domainHash(
            PAIR_ID_DOMAIN,
            map(
                "reportId", reportId,
                "caseId", caseId,
                "executionBaseId", executionBaseId,
                "repetition", repetition,
                "candidateGeneratorVersion",
                    candidateGeneratorVersion,
                "candidateFingerprint", candidateFingerprint));
  }

  static String pairHash(
      OfflineComparisonReport.PairRecord pair) {
    return ComparisonCanonicalEncoding.domainHash(
        PAIR_DOMAIN, pairValue(pair, false));
  }

  static String evaluationId(
      String pairId, String armId, String verifierVersion) {
    return "comparison-evaluation-"
        + ComparisonCanonicalEncoding.domainHash(
            EVALUATION_ID_DOMAIN,
            map(
                "pairId", pairId,
                "armId", armId,
                "verifierVersion", verifierVersion));
  }

  static String evaluationHash(
      OfflineComparisonReport.VerifierEvaluation evaluation) {
    return ComparisonCanonicalEncoding.domainHash(
        EVALUATION_DOMAIN, evaluationValue(evaluation, false));
  }

  static String reportHash(OfflineComparisonReport report) {
    return ComparisonCanonicalEncoding.domainHash(
        REPORT_DOMAIN, reportValue(report));
  }

  static Map<String, Object> candidateValue(
      OfflineComparisonReport.CandidateSnapshot candidate) {
    Object proposal = null;
    if (candidate.proposal() != null) {
      proposal =
          map(
              "content", candidate.proposal().content(),
              "evidenceRefs", candidate.proposal().evidenceRefs());
    }
    return map(
        "proposal", proposal,
        "obtainedEvidenceRefs", candidate.obtainedEvidenceRefs(),
        "requiredEvidenceRef", candidate.requiredEvidenceRef(),
        "requiredEvidenceAvailable",
            candidate.requiredEvidenceAvailable());
  }

  private static Map<String, Object> pairValue(
      OfflineComparisonReport.PairRecord pair,
      boolean includeIntegrityHash) {
    Map<String, Object> value =
        map(
            "pairId", pair.pairId(),
            "executionBaseId", pair.executionBaseId(),
            "caseId", pair.caseId(),
            "repetition", pair.repetition(),
            "candidateMode", pair.candidateMode(),
            "faultClass", pair.faultClass(),
            "candidateGeneratorVersion",
                pair.candidateGeneratorVersion(),
            "candidate", candidateValue(pair.candidate()),
            "candidateFingerprint", pair.candidateFingerprint());
    if (includeIntegrityHash) {
      value.put("integrityHash", pair.integrityHash());
    }
    return value;
  }

  private static Map<String, Object> evaluationValue(
      OfflineComparisonReport.VerifierEvaluation evaluation,
      boolean includeIntegrityHash) {
    Map<String, Object> value =
        map(
            "evaluationId", evaluation.evaluationId(),
            "pairId", evaluation.pairId(),
            "executionBaseId", evaluation.executionBaseId(),
            "caseId", evaluation.caseId(),
            "repetition", evaluation.repetition(),
            "armId", evaluation.armId(),
            "verifierVersion", evaluation.verifierVersion(),
            "candidateFingerprint",
                evaluation.candidateFingerprint(),
            "expectedStatus", evaluation.expectedStatus(),
            "expectedFailureCode",
                evaluation.expectedFailureCode(),
            "observedStatus", evaluation.observedStatus(),
            "observedFailureCode",
                evaluation.observedFailureCode(),
            "matchesExpected", evaluation.matchesExpected());
    if (includeIntegrityHash) {
      value.put("integrityHash", evaluation.integrityHash());
    }
    return value;
  }

  private static Map<String, Object> reportValue(
      OfflineComparisonReport report) {
    List<Object> pairs =
        report.pairs().stream()
            .map(pair -> pairValue(pair, true))
            .map(value -> (Object) value)
            .toList();
    List<Object> evaluations =
        report.evaluations().stream()
            .map(evaluation -> evaluationValue(evaluation, true))
            .map(value -> (Object) value)
            .toList();
    List<Object> issues =
        report.issues().stream()
            .map(
                issue ->
                    (Object)
                        map(
                            "code", issue.code(),
                            "caseId", issue.caseId(),
                            "repetition", issue.repetition(),
                            "armId", issue.armId()))
            .toList();
    return map(
        "schemaVersion", report.schemaVersion(),
        "reportKind", report.reportKind(),
        "reportId", report.reportId(),
        "integrityProfile", report.integrityProfile(),
        "packRawSha256", report.packRawSha256(),
        "taskId", report.taskId(),
        "suiteId", report.suiteId(),
        "variable", report.variable(),
        "repetitions", report.repetitions(),
        "runnerVersion", report.runnerVersion(),
        "candidateGeneratorVersion",
            report.candidateGeneratorVersion(),
        "h0VerifierVersion", report.h0VerifierVersion(),
        "h1VerifierVersion", report.h1VerifierVersion(),
        "frozenTime", report.frozenTime(),
        "pairs", pairs,
        "evaluations", evaluations,
        "summary", summaryValue(report.summary()),
        "effects", effectsValue(report.effects()),
        "status", report.status().name(),
        "issues", issues);
  }

  private static Map<String, Object> summaryValue(
      OfflineComparisonReport.Summary summary) {
    return map(
        "sharedCandidateGenerations",
            summary.sharedCandidateGenerations(),
        "verifierEvaluations", summary.verifierEvaluations(),
        "h0Accepted", summary.h0Accepted(),
        "h0FaultAcceptances", summary.h0FaultAcceptances(),
        "h1Accepted", summary.h1Accepted(),
        "h1FaultRejections", summary.h1FaultRejections(),
        "outcomeMismatches", summary.outcomeMismatches());
  }

  private static Map<String, Object> effectsValue(
      OfflineComparisonReport.OwnedEffects effects) {
    return map(
        "evidenceScope", effects.evidenceScope(),
        "sharedCandidateGenerations",
            effects.sharedCandidateGenerations(),
        "verifierEvaluations", effects.verifierEvaluations(),
        "h0Evaluations", effects.h0Evaluations(),
        "h1Evaluations", effects.h1Evaluations(),
        "literalFixtureReads", effects.literalFixtureReads(),
        "agentKernelRuns", effects.agentKernelRuns(),
        "productAgentRuns", effects.productAgentRuns(),
        "modelInvocations", effects.modelInvocations(),
        "toolLoopExecutions", effects.toolLoopExecutions(),
        "harnessRunBundles", effects.harnessRunBundles(),
        "credentialReads", effects.credentialReads(),
        "networkCalls", effects.networkCalls(),
        "connectorCalls", effects.connectorCalls(),
        "productTruthWrites", effects.productTruthWrites(),
        "externalSideEffects", effects.externalSideEffects(),
        "realUserDataReads", effects.realUserDataReads());
  }

  private static Map<String, Object> map(Object... values) {
    if (values.length % 2 != 0) {
      throw new IllegalArgumentException("map values must be key/value pairs");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }
}
