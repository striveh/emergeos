package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessEvaluationReportSurfaceTest {

  @Test
  void exposesOnlyTheCompleteSharedCandidateReportSurface()
      throws Exception {
    Class<?> report =
        Class.forName(
            "io.emergeos.contracts.HarnessEvaluationReport");

    assertTrue(report.isRecord());
    assertEquals(
        List.of(
            "schemaVersion",
            "reportKind",
            "reportId",
            "graphProtocolVersion",
            "packRawSha256",
            "environmentRawSha256",
            "evaluatorArms",
            "repetitions",
            "evaluations",
            "usageAggregate",
            "evaluatorEffects",
            "reportStatus",
            "integrityProfile",
            "integrityHash"),
        Arrays.stream(report.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());

    assertEquals(
        List.of(
            "BillingStatus",
            "Evaluation",
            "EvaluationStatus",
            "EvaluatorArm",
            "EvaluatorEffects",
            "GraphOutcome",
            "ProviderAttributionWitness",
            "RepetitionReport",
            "ReportStatus",
            "TerminalRunWitness",
            "TerminalSealWitness",
            "UsageAggregate"),
        Arrays.stream(report.getDeclaredClasses())
            .map(Class::getSimpleName)
            .sorted()
            .toList());

    List<String> forbidden =
        List.of(
            "partial",
            "issues",
            "replacementRepetitions",
            "acceptanceRate",
            "confidenceInterval",
            "pValue",
            "modelConclusion");
    List<String> components =
        Arrays.stream(report.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    forbidden.forEach(
        field -> assertTrue(!components.contains(field)));
  }

  @Test
  void integritySurfaceHasTypedReportAndReportIdMethods()
      throws Exception {
    Class<?> report =
        Class.forName(
            "io.emergeos.contracts.HarnessEvaluationReport");
    Method reportHash =
        IntegrityHashes.class.getMethod(
            "harnessEvaluationReportHash", report);
    Method reportId =
        IntegrityHashes.class.getMethod(
            "harnessEvaluationReportId", report);

    assertEquals(String.class, reportHash.getReturnType());
    assertEquals(String.class, reportId.getReturnType());
  }

  @Test
  void nestedRecordsAndEnumsRemainExactAndClosed() {
    assertRecordComponents(
        HarnessEvaluationReport.EvaluatorArm.class,
        List.of("armId", "evaluatorVersion"));
    assertRecordComponents(
        HarnessEvaluationReport.ProviderAttributionWitness.class,
        List.of(
            "requestOrdinal",
            "requestHash",
            "responseHash",
            "providerActor",
            "modelRequested",
            "modelResolved",
            "pricingProfileId",
            "pricingProvider",
            "pricingModelRequested",
            "uncachedInputNanoUsdPerToken",
            "cachedInputNanoUsdPerToken",
            "outputNanoUsdPerToken",
            "pricingProfileFingerprint",
            "inputTokens",
            "cachedInputTokens",
            "outputTokens",
            "reasoningOutputTokens",
            "totalTokens",
            "observedCostUsd",
            "attributionHash"));
    assertRecordComponents(
        HarnessEvaluationReport.TerminalRunWitness.class,
        List.of("startedAt", "completedAt", "bundle", "terminalHash"));
    assertRecordComponents(
        HarnessEvaluationReport.TerminalSealWitness.class,
        List.of(
            "attemptId",
            "manifestHash",
            "finalSequence",
            "preSealHeadHash",
            "finalHeadHash",
            "graphOutcome",
            "billingStatus",
            "providerAttributionHashes",
            "candidateRef",
            "candidateIntegrityHash",
            "childTerminalHash",
            "parentTerminalHash",
            "sealHash",
            "sealedAt"));
    assertRecordComponents(
        HarnessEvaluationReport.RepetitionReport.class,
        List.of(
            "repetition",
            "executionSlotId",
            "attemptId",
            "manifestHash",
            "providerAttributions",
            "parentRun",
            "childRun",
            "candidate",
            "workerResult",
            "artifactBinding",
            "terminalSeal"));
    assertRecordComponents(
        HarnessEvaluationReport.Evaluation.class,
        List.of(
            "repetition",
            "armId",
            "evaluatorVersion",
            "candidateRef",
            "candidateIntegrityHash",
            "status",
            "failureCode"));
    assertRecordComponents(
        HarnessEvaluationReport.UsageAggregate.class,
        List.of(
            "providerRequests",
            "inputTokens",
            "cachedInputTokens",
            "outputTokens",
            "reasoningOutputTokens",
            "totalTokens",
            "observedCostUsd"));
    assertRecordComponents(
        HarnessEvaluationReport.EvaluatorEffects.class,
        List.of(
            "sharedCandidateInputs",
            "evaluations",
            "modelInvocations",
            "toolExecutions",
            "networkCalls",
            "credentialReads",
            "connectorCalls",
            "productTruthWrites",
            "externalSideEffects",
            "realUserDataReads"));

    assertEquals(
        List.of("ACCEPTED", "REJECTED"),
        enumNames(HarnessEvaluationReport.EvaluationStatus.class));
    assertEquals(
        List.of("SUCCEEDED", "FAILED"),
        enumNames(HarnessEvaluationReport.GraphOutcome.class));
    assertEquals(
        List.of("ATTRIBUTED"),
        enumNames(HarnessEvaluationReport.BillingStatus.class));
    assertEquals(
        List.of("COMPLETE"),
        enumNames(HarnessEvaluationReport.ReportStatus.class));
  }

  private static void assertRecordComponents(
      Class<?> type, List<String> expected) {
    assertTrue(type.isRecord());
    assertEquals(
        expected,
        Arrays.stream(type.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  private static List<String> enumNames(
      Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants())
        .map(Enum::name)
        .toList();
  }
}
