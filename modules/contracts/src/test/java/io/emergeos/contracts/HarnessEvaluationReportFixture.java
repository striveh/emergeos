package io.emergeos.contracts;

import io.emergeos.contracts.HarnessEvaluationReport.BillingStatus;
import io.emergeos.contracts.HarnessEvaluationReport.Evaluation;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluationStatus;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorArm;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorEffects;
import io.emergeos.contracts.HarnessEvaluationReport.GraphOutcome;
import io.emergeos.contracts.HarnessEvaluationReport.ProviderAttributionWitness;
import io.emergeos.contracts.HarnessEvaluationReport.RepetitionReport;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalRunWitness;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalSealWitness;
import io.emergeos.contracts.HarnessEvaluationReport.UsageAggregate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class HarnessEvaluationReportFixture {

  static final String PACK_HASH = hash("pack010-public-synthetic");
  static final String ENVIRONMENT_HASH = hash("pack010-environment");
  static final String MODEL = "gpt-5.6-terra";
  static final String OUTPUT_SCHEMA =
      "urn:emergeos:schema:internal:agent-draft-proposal:v1";

  private HarnessEvaluationReportFixture() {}

  static HarnessEvaluationReport mixed() {
    return report(List.of(true, false, true), "pilot-slot-");
  }

  static HarnessEvaluationReport allSuccess() {
    return report(List.of(true, true, true), "different-slot-");
  }

  static HarnessEvaluationReport report(
      List<Boolean> successes, String slotPrefix) {
    List<RepetitionReport> repetitions = new ArrayList<>();
    List<Evaluation> evaluations = new ArrayList<>();
    for (int index = 0; index < successes.size(); index++) {
      int repetition = index + 1;
      RepetitionReport report =
          repetition(
              repetition,
              slotPrefix + repetition,
              successes.get(index));
      repetitions.add(report);
      evaluations.add(
          new Evaluation(
              repetition,
              HarnessEvaluationReport.H0_ARM_ID,
              HarnessEvaluationReport.H0_EVALUATOR_VERSION,
              report.candidate().candidateRef(),
              report.candidate().integrityHash(),
              EvaluationStatus.ACCEPTED,
              null));
      evaluations.add(
          new Evaluation(
              repetition,
              HarnessEvaluationReport.H1_ARM_ID,
              HarnessEvaluationReport.H1_EVALUATOR_VERSION,
              report.candidate().candidateRef(),
              report.candidate().integrityHash(),
              successes.get(index)
                  ? EvaluationStatus.ACCEPTED
                  : EvaluationStatus.REJECTED,
              successes.get(index)
                  ? null
                  : "INVALID_EVIDENCE_CLAIM"));
    }
    return create(repetitions, evaluations, usage(), effects());
  }

  static HarnessEvaluationReport create(
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usage,
      EvaluatorEffects effects) {
    return HarnessEvaluationReport.create(
        HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION,
        PACK_HASH,
        ENVIRONMENT_HASH,
        arms(),
        repetitions,
        evaluations,
        usage,
        effects);
  }

  static List<EvaluatorArm> arms() {
    return List.of(
        new EvaluatorArm(
            HarnessEvaluationReport.H0_ARM_ID,
            HarnessEvaluationReport.H0_EVALUATOR_VERSION),
        new EvaluatorArm(
            HarnessEvaluationReport.H1_ARM_ID,
            HarnessEvaluationReport.H1_EVALUATOR_VERSION));
  }

  static UsageAggregate usage() {
    return new UsageAggregate(
        6, 90, 0, 45, 15, 135, new BigDecimal("0.000135"));
  }

  static EvaluatorEffects effects() {
    return new EvaluatorEffects(3, 6, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  static RepetitionReport repetition(
      int repetition, String slot, boolean success) {
    return repetitionWithTaskIds(
        repetition,
        slot,
        success,
        "pack010-parent-task-" + repetition,
        "pack010-child-task-" + repetition);
  }

  static RepetitionReport repetitionWithTaskIds(
      int repetition,
      String slot,
      boolean success,
      String parentTaskId,
      String childTaskId) {
    String attempt = hash("attempt-" + repetition + "-" + slot);
    String captureRef = "capture://pack010-capture";
    String childRunId = "pack010-child-run-" + repetition;
    String parentRunId = "pack010-parent-run-" + repetition;
    String content =
        success
            ? "第" + repetition + "次：参透世界之后，仍然认真生活。🌌"
            : "第" + repetition + "次：结构合法，但引用了错误证据。🧭";
    List<String> claimedRefs =
        success ? List.of(captureRef) : List.of("capture://other");
    List<ProviderAttributionWitness> attributions =
        List.of(
            attribution(repetition, 1, 10, 5),
            attribution(repetition, 2, 20, 10));
    String traceRootHash = hash("child-trace-" + repetition);
    HarnessCandidateEnvelope candidate =
        HarnessCandidateEnvelope.create(
            attempt,
            slot,
            repetition,
            childRunId,
            childTaskId,
            attributions.get(1).responseHash(),
            traceRootHash,
            OUTPUT_SCHEMA,
            content,
            claimedRefs,
            List.of(captureRef),
            captureRef,
            true);
    WorkerResultEnvelope workerResult =
        success
            ? WorkerResultEnvelope.create(
                childRunId,
                childTaskId,
                OUTPUT_SCHEMA,
                content,
                claimedRefs)
            : null;
    String artifactRef =
        "artifact-version://pack010-artifact-" + repetition + "/1";
    ResourceBinding artifactBinding =
        success
            ? new ResourceBinding(
                ResourceRole.ARTIFACT,
                0,
                artifactRef,
                candidate.contentHash())
            : null;
    HarnessRunBundle childBundle =
        childBundle(
            repetition,
            childRunId,
            childTaskId,
            parentTaskId,
            traceRootHash,
            captureRef,
            workerResult,
            success);
    HarnessRunBundle parentBundle =
        parentBundle(
            repetition,
            parentRunId,
            parentTaskId,
            childRunId,
            childBundle,
            captureRef,
            artifactBinding,
            success);
    Instant base =
        Instant.parse("2026-07-31T00:00:00.000001Z")
            .plusSeconds(repetition * 10L);
    TerminalRunWitness childRun =
        new TerminalRunWitness(
            base,
            base.plusMillis(40),
            childBundle,
            hash("child-terminal-" + repetition));
    TerminalRunWitness parentRun =
        new TerminalRunWitness(
            base.minusMillis(1),
            base.plusMillis(41),
            parentBundle,
            hash("parent-terminal-" + repetition));
    TerminalSealWitness seal =
        new TerminalSealWitness(
            attempt,
            attempt,
            17,
            hash("pre-seal-" + repetition),
            hash("final-head-" + repetition),
            success ? GraphOutcome.SUCCEEDED : GraphOutcome.FAILED,
            BillingStatus.ATTRIBUTED,
            attributions.stream()
                .map(ProviderAttributionWitness::attributionHash)
                .toList(),
            candidate.candidateRef(),
            candidate.integrityHash(),
            childRun.terminalHash(),
            parentRun.terminalHash(),
            hash("seal-" + repetition),
            base.plusMillis(42));
    return new RepetitionReport(
        repetition,
        slot,
        attempt,
        attempt,
        attributions,
        parentRun,
        childRun,
        candidate,
        workerResult,
        artifactBinding,
        seal);
  }

  private static ProviderAttributionWitness attribution(
      int repetition,
      int ordinal,
      long inputTokens,
      long outputTokens) {
    long totalTokens = inputTokens + outputTokens;
    return new ProviderAttributionWitness(
        ordinal,
        hash("request-" + repetition + "-" + ordinal),
        hash("response-" + repetition + "-" + ordinal),
        "openai.responses",
        MODEL,
        MODEL,
        "openai-gpt-5.6-terra-pack010-v1",
        "openai",
        MODEL,
        1_000,
        1_000,
        1_000,
        hash("pricing-profile-pack010"),
        inputTokens,
        0,
        outputTokens,
        ordinal == 1 ? 5 : 0,
        totalTokens,
        BigDecimal.valueOf(totalTokens, 6),
        hash("attribution-" + repetition + "-" + ordinal));
  }

  private static HarnessRunBundle childBundle(
      int repetition,
      String runId,
      String taskId,
      String parentTaskId,
      String traceRootHash,
      String captureRef,
      WorkerResultEnvelope workerResult,
      boolean success) {
    TaskEnvelope task =
        task(
            "1.1",
            taskId,
            parentTaskId,
            "PROPOSE_ARTICLE_DRAFT",
            true,
            repetition);
    RunStatus status = success ? RunStatus.SUCCEEDED : RunStatus.FAILED;
    String failure = success ? null : "INVALID_EVIDENCE_CLAIM";
    ResultEnvelope result =
        result(
            runId,
            taskId,
            status,
            List.of(),
            List.of(captureRef),
            failure);
    List<ResourceBinding> bindings = new ArrayList<>();
    bindings.add(
        new ResourceBinding(
            ResourceRole.EVIDENCE,
            0,
            captureRef,
            hash("capture-content")));
    if (workerResult != null) {
      bindings.add(
          new ResourceBinding(
              ResourceRole.WORKER_RESULT,
              0,
              workerResult.workerResultRef(),
              workerResult.integrityHash()));
    }
    return HarnessRunBundle.create(
        "1.1",
        runId,
        taskId,
        new HarnessExperiment("shared-candidate-live-pilot", repetition),
        MODEL,
        "framework-free-agent-kernel-v1",
        Map.of(
            "agent", "agent-draft-service-v1",
            "verifier", "agent-draft-verifier-v1",
            "trace-integrity", IntegrityHashes.PROFILE,
            "model-adapter", "openai-responses-protocol-v1"),
        "environment://sha256:" + ENVIRONMENT_HASH,
        "agent-tools-v2",
        task,
        result,
        null,
        result.traceRef(),
        traceRootHash,
        List.of(),
        List.of(),
        bindings,
        null,
        failure,
        status,
        result.costUsd(),
        result.tokenCount(),
        result.latencyMs());
  }

  private static HarnessRunBundle parentBundle(
      int repetition,
      String runId,
      String taskId,
      String childRunId,
      HarnessRunBundle childBundle,
      String captureRef,
      ResourceBinding artifactBinding,
      boolean success) {
    TaskEnvelope task =
        task(
            "1.0",
            taskId,
            null,
            "CREATE_ARTICLE_DRAFT",
            false,
            repetition);
    RunStatus status = success ? RunStatus.SUCCEEDED : RunStatus.FAILED;
    String failure = success ? null : "HANDOFF_CHILD_FAILED";
    List<String> artifactRefs =
        artifactBinding == null
            ? List.of()
            : List.of(artifactBinding.ref());
    ResultEnvelope result =
        result(
            runId,
            taskId,
            status,
            artifactRefs,
            List.of(captureRef),
            failure);
    List<ResourceBinding> bindings = new ArrayList<>();
    bindings.add(
        new ResourceBinding(
            ResourceRole.EVIDENCE,
            0,
            captureRef,
            hash("capture-content")));
    if (artifactBinding != null) {
      bindings.add(artifactBinding);
    }
    bindings.add(
        new ResourceBinding(
            ResourceRole.HANDOFF,
            0,
            "agent-run://" + childRunId,
            childBundle.integrityHash()));
    return HarnessRunBundle.create(
        "1.0",
        runId,
        taskId,
        new HarnessExperiment("shared-candidate-live-pilot", repetition),
        MODEL,
        "framework-free-agent-kernel-v1",
        Map.of(
            "agent", "agent-draft-service-v1",
            "verifier", "agent-draft-verifier-v1",
            "trace-integrity", IntegrityHashes.PROFILE),
        "environment://sha256:" + ENVIRONMENT_HASH,
        "agent-tools-v2",
        task,
        result,
        null,
        result.traceRef(),
        hash("parent-trace-" + repetition),
        List.of("agent-run://" + childRunId),
        List.of(),
        bindings,
        null,
        failure,
        status,
        result.costUsd(),
        result.tokenCount(),
        result.latencyMs());
  }

  private static TaskEnvelope task(
      String schemaVersion,
      String taskId,
      String parentTaskId,
      String kind,
      boolean modelBound,
      int repetition) {
    String environmentRef =
        "environment://sha256:" + ENVIRONMENT_HASH;
    return new TaskEnvelope(
        schemaVersion,
        taskId,
        parentTaskId,
        "owner-public-synthetic",
        parentTaskId == null ? List.of() : List.of(parentTaskId),
        kind,
        "Create one PUBLIC synthetic draft for repetition "
            + repetition,
        List.of("capture://pack010-capture"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        OUTPUT_SCHEMA,
        List.of("uses exact synthetic evidence"),
        false,
        2,
        1,
        5_000,
        BigDecimal.ONE,
        modelBound ? "openai.responses" : null,
        modelBound ? MODEL : null,
        modelBound ? "openai-gpt-5.6-terra-pack010-v1" : null,
        modelBound ? "pack010-request-" + repetition : null,
        "agent-draft-policy-v1",
        "pack010-state-v1",
        "references-first-v1",
        "agent-tools-v2",
        environmentRef,
        modelBound
            ? List.of(
                ObservedExecutionLimits.READ_ONLY_WORKER_CAPABILITY)
            : List.of(),
        List.of(),
        "return terminal graph truth");
  }

  private static ResultEnvelope result(
      String runId,
      String taskId,
      RunStatus status,
      List<String> artifactRefs,
      List<String> evidenceRefs,
      String failure) {
    return new ResultEnvelope(
        "1.0",
        runId,
        taskId,
        status,
        artifactRefs,
        evidenceRefs,
        List.of(),
        List.of(),
        List.of(),
        MODEL,
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        new BigDecimal("0.000045"),
        45,
        40,
        "/api/v1/agent-runs/" + runId + "/trace",
        failure);
  }

  static String hash(String value) {
    return IntegrityHashes.utf8ContentHash(value);
  }
}
