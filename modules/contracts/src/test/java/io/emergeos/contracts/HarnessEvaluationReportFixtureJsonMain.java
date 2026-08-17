package io.emergeos.contracts;

import io.emergeos.contracts.HarnessEvaluationReport.Evaluation;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorEffects;
import io.emergeos.contracts.HarnessEvaluationReport.GraphOutcome;
import io.emergeos.contracts.HarnessEvaluationReport.RepetitionReport;
import io.emergeos.contracts.HarnessEvaluationReport.ReportStatus;
import io.emergeos.contracts.HarnessEvaluationReport.TerminalSealWitness;
import io.emergeos.contracts.HarnessEvaluationReport.UsageAggregate;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only generator for reviewed JSON fixtures. Never packaged in a shipping JAR. */
public final class HarnessEvaluationReportFixtureJsonMain {

  private HarnessEvaluationReportFixtureJsonMain() {}

  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("one fixture mode is required");
    }
    System.out.println(render(arguments[0]));
  }

  static String render(String mode) {
    HarnessEvaluationReport valid =
        HarnessEvaluationReportFixture.mixed();
    Object output =
        switch (mode) {
          case "valid" -> valid;
          case "invalid-extra" -> withExtraField(valid);
          case "invalid-repetition-order" ->
              rawReport(
                  List.of(
                      valid.repetitions().get(1),
                      valid.repetitions().get(0),
                      valid.repetitions().get(2)),
                  valid.evaluations(),
                  valid.usageAggregate(),
                  valid.evaluatorEffects());
          case "invalid-partial" -> partialReport(valid);
          case "invalid-candidate-alias" ->
              rawReport(
                  valid.repetitions(),
                  aliasedEvaluations(valid),
                  valid.usageAggregate(),
                  valid.evaluatorEffects());
          case "invalid-usage" ->
              rawReport(
                  valid.repetitions(),
                  valid.evaluations(),
                  new UsageAggregate(
                      6,
                      91,
                      0,
                      45,
                      15,
                      136,
                      new BigDecimal("0.000135")),
                  valid.evaluatorEffects());
          case "invalid-network-effect" ->
              rawReport(
                  valid.repetitions(),
                  valid.evaluations(),
                  valid.usageAggregate(),
                  new EvaluatorEffects(
                      3, 6, 0, 0, 1, 0, 0, 0, 0, 0));
          case "invalid-h1-outcome" ->
              rawReport(
                  changedOutcome(valid),
                  valid.evaluations(),
                  valid.usageAggregate(),
                  valid.evaluatorEffects());
          case "invalid-self-consistent-h1" ->
              selfConsistentWrongH1(valid);
          case "golden" -> golden(valid);
          default ->
              throw new IllegalArgumentException(
                  "unsupported fixture mode");
        };
    return Json.write(output);
  }

  private static Map<String, Object> withExtraField(
      HarnessEvaluationReport valid) {
    Map<String, Object> values = recordValues(valid);
    values.put("acceptanceRate", 1);
    return values;
  }

  private static List<Evaluation> aliasedEvaluations(
      HarnessEvaluationReport valid) {
    List<Evaluation> values =
        new ArrayList<>(valid.evaluations());
    Evaluation original = values.get(2);
    values.set(
        2,
        new Evaluation(
            original.repetition(),
            original.armId(),
            original.evaluatorVersion(),
            valid.repetitions().get(0).candidate().candidateRef(),
            valid.repetitions().get(0).candidate().integrityHash(),
            original.status(),
            original.failureCode()));
    return List.copyOf(values);
  }

  private static List<RepetitionReport> changedOutcome(
      HarnessEvaluationReport valid) {
    RepetitionReport original = valid.repetitions().get(0);
    TerminalSealWitness seal = original.terminalSeal();
    TerminalSealWitness changedSeal =
        new TerminalSealWitness(
            seal.attemptId(),
            seal.manifestHash(),
            seal.finalSequence(),
            seal.preSealHeadHash(),
            seal.finalHeadHash(),
            GraphOutcome.FAILED,
            seal.billingStatus(),
            seal.providerAttributionHashes(),
            seal.candidateRef(),
            seal.candidateIntegrityHash(),
            seal.childTerminalHash(),
            seal.parentTerminalHash(),
            seal.sealHash(),
            seal.sealedAt());
    RepetitionReport changed =
        new RepetitionReport(
            original.repetition(),
            original.executionSlotId(),
            original.attemptId(),
            original.manifestHash(),
            original.providerAttributions(),
            original.parentRun(),
            original.childRun(),
            original.candidate(),
            original.workerResult(),
            original.artifactBinding(),
            changedSeal);
    List<RepetitionReport> values =
        new ArrayList<>(valid.repetitions());
    values.set(0, changed);
    return List.copyOf(values);
  }

  private static Map<String, Object> partialReport(
      HarnessEvaluationReport valid) {
    return rawReport(
        valid.repetitions().subList(0, 2),
        valid.evaluations().subList(0, 4),
        new UsageAggregate(
            4,
            60,
            0,
            30,
            10,
            90,
            new BigDecimal("0.000090")),
        new EvaluatorEffects(2, 4, 0, 0, 0, 0, 0, 0, 0, 0));
  }

  private static Map<String, Object> selfConsistentWrongH1(
      HarnessEvaluationReport valid) {
    int repetitionIndex = 1;
    RepetitionReport original =
        valid.repetitions().get(repetitionIndex);
    HarnessCandidateEnvelope source = original.candidate();
    HarnessCandidateEnvelope grounded =
        HarnessCandidateEnvelope.create(
            source.attemptId(),
            source.executionSlotId(),
            source.repetition(),
            source.childRunId(),
            source.childTaskId(),
            source.sourceResponseHash(),
            source.traceRootHash(),
            source.outputSchema(),
            source.content(),
            List.of(source.requiredEvidenceRef()),
            source.obtainedEvidenceRefs(),
            source.requiredEvidenceRef(),
            source.requiredEvidenceAvailable());
    TerminalSealWitness sourceSeal = original.terminalSeal();
    TerminalSealWitness changedSeal =
        new TerminalSealWitness(
            sourceSeal.attemptId(),
            sourceSeal.manifestHash(),
            sourceSeal.finalSequence(),
            sourceSeal.preSealHeadHash(),
            sourceSeal.finalHeadHash(),
            sourceSeal.graphOutcome(),
            sourceSeal.billingStatus(),
            sourceSeal.providerAttributionHashes(),
            sourceSeal.candidateRef(),
            grounded.integrityHash(),
            sourceSeal.childTerminalHash(),
            sourceSeal.parentTerminalHash(),
            sourceSeal.sealHash(),
            sourceSeal.sealedAt());
    RepetitionReport changed =
        new RepetitionReport(
            original.repetition(),
            original.executionSlotId(),
            original.attemptId(),
            original.manifestHash(),
            original.providerAttributions(),
            original.parentRun(),
            original.childRun(),
            grounded,
            original.workerResult(),
            original.artifactBinding(),
            changedSeal);
    List<RepetitionReport> repetitions =
        new ArrayList<>(valid.repetitions());
    repetitions.set(repetitionIndex, changed);
    List<Evaluation> evaluations =
        new ArrayList<>(valid.evaluations());
    evaluations.set(
        repetitionIndex * 2,
        new Evaluation(
            changed.repetition(),
            HarnessEvaluationReport.H0_ARM_ID,
            HarnessEvaluationReport.H0_EVALUATOR_VERSION,
            grounded.candidateRef(),
            grounded.integrityHash(),
            HarnessEvaluationReport.EvaluationStatus.ACCEPTED,
            null));
    evaluations.set(
        repetitionIndex * 2 + 1,
        new Evaluation(
            changed.repetition(),
            HarnessEvaluationReport.H1_ARM_ID,
            HarnessEvaluationReport.H1_EVALUATOR_VERSION,
            grounded.candidateRef(),
            grounded.integrityHash(),
            HarnessEvaluationReport.EvaluationStatus.REJECTED,
            "INVALID_EVIDENCE_CLAIM"));
    return rawReport(
        repetitions,
        evaluations,
        valid.usageAggregate(),
        valid.evaluatorEffects());
  }

  private static Map<String, Object> rawReport(
      List<RepetitionReport> repetitions,
      List<Evaluation> evaluations,
      UsageAggregate usage,
      EvaluatorEffects effects) {
    String reportId =
        IntegrityHashes.harnessEvaluationReportId(
            HarnessEvaluationReport.idPreimage(
                HarnessEvaluationReport.SCHEMA_VERSION,
                HarnessEvaluationReport.REPORT_KIND,
                HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION,
                HarnessEvaluationReportFixture.PACK_HASH,
                HarnessEvaluationReportFixture.ENVIRONMENT_HASH,
                HarnessEvaluationReportFixture.arms(),
                repetitions,
                evaluations,
                usage,
                effects,
                ReportStatus.COMPLETE,
                IntegrityHashes.PROFILE));
    String integrityHash =
        IntegrityHashes.harnessEvaluationReportHash(
            HarnessEvaluationReport.preimage(
                HarnessEvaluationReport.SCHEMA_VERSION,
                HarnessEvaluationReport.REPORT_KIND,
                reportId,
                HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION,
                HarnessEvaluationReportFixture.PACK_HASH,
                HarnessEvaluationReportFixture.ENVIRONMENT_HASH,
                HarnessEvaluationReportFixture.arms(),
                repetitions,
                evaluations,
                usage,
                effects,
                ReportStatus.COMPLETE,
                IntegrityHashes.PROFILE));
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", HarnessEvaluationReport.SCHEMA_VERSION);
    values.put("reportKind", HarnessEvaluationReport.REPORT_KIND);
    values.put("reportId", reportId);
    values.put(
        "graphProtocolVersion",
        HarnessEvaluationReport.GRAPH_PROTOCOL_VERSION);
    values.put("packRawSha256", HarnessEvaluationReportFixture.PACK_HASH);
    values.put(
        "environmentRawSha256",
        HarnessEvaluationReportFixture.ENVIRONMENT_HASH);
    values.put("evaluatorArms", HarnessEvaluationReportFixture.arms());
    values.put("repetitions", repetitions);
    values.put("evaluations", evaluations);
    values.put("usageAggregate", usage);
    values.put("evaluatorEffects", effects);
    values.put("reportStatus", ReportStatus.COMPLETE);
    values.put("integrityProfile", IntegrityHashes.PROFILE);
    values.put("integrityHash", integrityHash);
    return values;
  }

  private static Map<String, Object> golden(
      HarnessEvaluationReport report) {
    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("name", "complete-mixed-chinese-astral");
    vector.put("report", report);
    vector.put("reportId", report.reportId());
    vector.put("integrityHash", report.integrityHash());
    Map<String, Object> golden = new LinkedHashMap<>();
    golden.put("profile", IntegrityHashes.PROFILE);
    golden.put("domain", "emergeos.harness-evaluation-report.v1");
    golden.put(
        "reportIdDomain",
        "emergeos.harness-evaluation-report-id.v1");
    golden.put("vectors", List.of(vector));
    return golden;
  }

  private static Map<String, Object> recordValues(Object record) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (RecordComponent component :
        record.getClass().getRecordComponents()) {
      try {
        values.put(component.getName(), component.getAccessor().invoke(record));
      } catch (IllegalAccessException | InvocationTargetException failure) {
        throw new IllegalStateException(failure);
      }
    }
    return values;
  }

  private static final class Json {

    private Json() {}

    static String write(Object value) {
      StringBuilder output = new StringBuilder();
      append(output, value, 0);
      return output.toString();
    }

    private static void append(
        StringBuilder output, Object value, int depth) {
      if (value == null) {
        output.append("null");
      } else if (value instanceof String text) {
        appendString(output, text);
      } else if (value instanceof Instant instant) {
        appendString(output, instant.toString());
      } else if (value instanceof Enum<?> enumeration) {
        appendString(output, enumeration.name());
      } else if (value instanceof BigDecimal decimal) {
        output.append(decimal.toPlainString());
      } else if (value instanceof Number || value instanceof Boolean) {
        output.append(value);
      } else if (value instanceof List<?> list) {
        appendList(output, list, depth);
      } else if (value instanceof Map<?, ?> map) {
        appendMap(output, map, depth);
      } else if (value.getClass().isRecord()) {
        appendMap(output, recordValues(value), depth);
      } else {
        throw new IllegalArgumentException(
            "unsupported JSON fixture value " + value.getClass());
      }
    }

    private static void appendList(
        StringBuilder output, List<?> values, int depth) {
      output.append('[');
      for (int index = 0; index < values.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        output.append('\n');
        indent(output, depth + 1);
        append(output, values.get(index), depth + 1);
      }
      if (!values.isEmpty()) {
        output.append('\n');
        indent(output, depth);
      }
      output.append(']');
    }

    private static void appendMap(
        StringBuilder output, Map<?, ?> source, int depth) {
      List<Map.Entry<?, ?>> entries = new ArrayList<>();
      source.entrySet().forEach(entries::add);
      entries.sort(
          Comparator.comparing(
              entry -> entry.getKey().toString()));
      output.append('{');
      for (int index = 0; index < entries.size(); index++) {
        Map.Entry<?, ?> entry = entries.get(index);
        if (index > 0) {
          output.append(',');
        }
        output.append('\n');
        indent(output, depth + 1);
        appendString(output, entry.getKey().toString());
        output.append(": ");
        append(output, entry.getValue(), depth + 1);
      }
      if (!entries.isEmpty()) {
        output.append('\n');
        indent(output, depth);
      }
      output.append('}');
    }

    private static void appendString(
        StringBuilder output, String value) {
      output.append('"');
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        switch (character) {
          case '"' -> output.append("\\\"");
          case '\\' -> output.append("\\\\");
          case '\b' -> output.append("\\b");
          case '\f' -> output.append("\\f");
          case '\n' -> output.append("\\n");
          case '\r' -> output.append("\\r");
          case '\t' -> output.append("\\t");
          default -> {
            if (character < 0x20) {
              output.append(
                  String.format("\\u%04x", (int) character));
            } else {
              output.append(character);
            }
          }
        }
      }
      output.append('"');
    }

    private static void indent(
        StringBuilder output, int depth) {
      output.append("  ".repeat(depth));
    }
  }
}
