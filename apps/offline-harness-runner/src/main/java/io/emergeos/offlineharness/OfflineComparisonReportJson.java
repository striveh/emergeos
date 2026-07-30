package io.emergeos.offlineharness;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical storage encoding for the Pack 004 comparison report.
 *
 * <p>This encoding is deliberately separate from the integrity encoding.
 * The report integrity hash binds semantic values; this class freezes exact
 * durable UTF-8 JSON bytes and rejects any parseable non-canonical variant.
 */
final class OfflineComparisonReportJson {

  static final String STORAGE_SCHEMA_VERSION = "1.0";
  static final String SERIALIZATION_PROFILE =
      "emergeos-offline-comparison-json-v1";
  static final int MAX_REPORT_BYTES = 1024 * 1024;

  private static final int EXPECTED_PAIRS = 12;
  private static final int EXPECTED_EVALUATIONS = 24;
  private static final int MAX_ISSUES = 24;
  private static final int MAX_REFERENCE_COUNT = 8;

  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_REPORT_BYTES)
                          .maxTokenCount(20_000)
                          .maxNestingDepth(16)
                          .maxNumberLength(16)
                          .maxStringLength(128 * 1024)
                          .maxNameLength(128)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private OfflineComparisonReportJson() {}

  static byte[] encode(OfflineComparisonReport report) {
    Objects.requireNonNull(report, "report");
    try {
      ObjectNode envelope = JSON.createObjectNode();
      envelope.put(
          "storageSchemaVersion", STORAGE_SCHEMA_VERSION);
      envelope.put(
          "serializationProfile", SERIALIZATION_PROFILE);
      envelope.set("report", reportNode(report));
      requireUnicodeScalarTree(envelope);
      byte[] bytes = JSON.writeValueAsBytes(envelope);
      if (bytes.length < 1 || bytes.length > MAX_REPORT_BYTES) {
        throw rejected("REPORT_SIZE_INVALID");
      }
      return bytes;
    } catch (Rejected failure) {
      throw failure;
    } catch (RuntimeException | IOException failure) {
      throw rejected("REPORT_SERIALIZATION_FAILED");
    }
  }

  static OfflineComparisonReport decode(byte[] bytes) {
    if (bytes == null
        || bytes.length < 1
        || bytes.length > MAX_REPORT_BYTES) {
      throw rejected("REPORT_SIZE_INVALID");
    }
    try {
      ObjectNode envelope =
          object(
              JSON.readTree(bytes),
              "REPORT_JSON_INVALID",
              "storageSchemaVersion",
              "serializationProfile",
              "report");
      if (!STORAGE_SCHEMA_VERSION.equals(
              text(envelope, "storageSchemaVersion"))
          || !SERIALIZATION_PROFILE.equals(
              text(envelope, "serializationProfile"))) {
        throw rejected("REPORT_STORAGE_PROFILE_MISMATCH");
      }
      OfflineComparisonReport report =
          parseReport(
              object(
                  envelope.get("report"),
                  "REPORT_JSON_INVALID",
                  "schemaVersion",
                  "reportKind",
                  "reportId",
                  "integrityProfile",
                  "packRawSha256",
                  "taskId",
                  "suiteId",
                  "variable",
                  "repetitions",
                  "runnerVersion",
                  "candidateGeneratorVersion",
                  "h0VerifierVersion",
                  "h1VerifierVersion",
                  "frozenTime",
                  "pairs",
                  "evaluations",
                  "summary",
                  "effects",
                  "status",
                  "issues",
                  "integrityHash"));
      byte[] canonical = encode(report);
      if (!MessageDigest.isEqual(bytes, canonical)) {
        throw rejected("REPORT_JSON_NON_CANONICAL");
      }
      return report;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("REPORT_JSON_INVALID");
    }
  }

  private static ObjectNode reportNode(
      OfflineComparisonReport report) {
    ObjectNode node = JSON.createObjectNode();
    node.put("schemaVersion", report.schemaVersion());
    node.put("reportKind", report.reportKind());
    node.put("reportId", report.reportId());
    node.put("integrityProfile", report.integrityProfile());
    node.put("packRawSha256", report.packRawSha256());
    node.put("taskId", report.taskId());
    node.put("suiteId", report.suiteId());
    node.put("variable", report.variable());
    node.put("repetitions", report.repetitions());
    node.put("runnerVersion", report.runnerVersion());
    node.put(
        "candidateGeneratorVersion",
        report.candidateGeneratorVersion());
    node.put("h0VerifierVersion", report.h0VerifierVersion());
    node.put("h1VerifierVersion", report.h1VerifierVersion());
    node.put("frozenTime", report.frozenTime());
    ArrayNode pairs = node.putArray("pairs");
    report.pairs().forEach(pair -> pairs.add(pairNode(pair)));
    ArrayNode evaluations = node.putArray("evaluations");
    report
        .evaluations()
        .forEach(
            evaluation ->
                evaluations.add(evaluationNode(evaluation)));
    node.set("summary", summaryNode(report.summary()));
    node.set("effects", effectsNode(report.effects()));
    node.put("status", report.status().name());
    ArrayNode issues = node.putArray("issues");
    report.issues().forEach(issue -> issues.add(issueNode(issue)));
    node.put("integrityHash", report.integrityHash());
    return node;
  }

  private static ObjectNode pairNode(
      OfflineComparisonReport.PairRecord pair) {
    ObjectNode node = JSON.createObjectNode();
    node.put("pairId", pair.pairId());
    node.put("executionBaseId", pair.executionBaseId());
    node.put("caseId", pair.caseId());
    node.put("repetition", pair.repetition());
    node.put("candidateMode", pair.candidateMode());
    node.put("faultClass", pair.faultClass());
    node.put(
        "candidateGeneratorVersion",
        pair.candidateGeneratorVersion());
    node.set("candidate", candidateNode(pair.candidate()));
    node.put(
        "candidateFingerprint", pair.candidateFingerprint());
    node.put("integrityHash", pair.integrityHash());
    return node;
  }

  private static ObjectNode candidateNode(
      OfflineComparisonReport.CandidateSnapshot candidate) {
    ObjectNode node = JSON.createObjectNode();
    ObjectNode proposal = JSON.createObjectNode();
    proposal.put("content", candidate.proposal().content());
    ArrayNode proposalRefs = proposal.putArray("evidenceRefs");
    candidate
        .proposal()
        .evidenceRefs()
        .forEach(proposalRefs::add);
    node.set("proposal", proposal);
    ArrayNode obtained =
        node.putArray("obtainedEvidenceRefs");
    candidate.obtainedEvidenceRefs().forEach(obtained::add);
    node.put(
        "requiredEvidenceRef", candidate.requiredEvidenceRef());
    node.put(
        "requiredEvidenceAvailable",
        candidate.requiredEvidenceAvailable());
    return node;
  }

  private static ObjectNode evaluationNode(
      OfflineComparisonReport.VerifierEvaluation evaluation) {
    ObjectNode node = JSON.createObjectNode();
    node.put("evaluationId", evaluation.evaluationId());
    node.put("pairId", evaluation.pairId());
    node.put("executionBaseId", evaluation.executionBaseId());
    node.put("caseId", evaluation.caseId());
    node.put("repetition", evaluation.repetition());
    node.put("armId", evaluation.armId());
    node.put("verifierVersion", evaluation.verifierVersion());
    node.put(
        "candidateFingerprint",
        evaluation.candidateFingerprint());
    node.put("expectedStatus", evaluation.expectedStatus());
    putNullable(
        node,
        "expectedFailureCode",
        evaluation.expectedFailureCode());
    node.put("observedStatus", evaluation.observedStatus());
    putNullable(
        node,
        "observedFailureCode",
        evaluation.observedFailureCode());
    node.put("matchesExpected", evaluation.matchesExpected());
    node.put("integrityHash", evaluation.integrityHash());
    return node;
  }

  private static ObjectNode summaryNode(
      OfflineComparisonReport.Summary summary) {
    ObjectNode node = JSON.createObjectNode();
    node.put(
        "sharedCandidateGenerations",
        summary.sharedCandidateGenerations());
    node.put(
        "verifierEvaluations", summary.verifierEvaluations());
    node.put("h0Accepted", summary.h0Accepted());
    node.put(
        "h0FaultAcceptances", summary.h0FaultAcceptances());
    node.put("h1Accepted", summary.h1Accepted());
    node.put(
        "h1FaultRejections", summary.h1FaultRejections());
    node.put(
        "outcomeMismatches", summary.outcomeMismatches());
    return node;
  }

  private static ObjectNode effectsNode(
      OfflineComparisonReport.OwnedEffects effects) {
    ObjectNode node = JSON.createObjectNode();
    node.put("evidenceScope", effects.evidenceScope());
    node.put(
        "sharedCandidateGenerations",
        effects.sharedCandidateGenerations());
    node.put(
        "verifierEvaluations", effects.verifierEvaluations());
    node.put("h0Evaluations", effects.h0Evaluations());
    node.put("h1Evaluations", effects.h1Evaluations());
    node.put(
        "literalFixtureReads", effects.literalFixtureReads());
    node.put("agentKernelRuns", effects.agentKernelRuns());
    node.put("productAgentRuns", effects.productAgentRuns());
    node.put("modelInvocations", effects.modelInvocations());
    node.put(
        "toolLoopExecutions", effects.toolLoopExecutions());
    node.put("harnessRunBundles", effects.harnessRunBundles());
    node.put("credentialReads", effects.credentialReads());
    node.put("networkCalls", effects.networkCalls());
    node.put("connectorCalls", effects.connectorCalls());
    node.put("productTruthWrites", effects.productTruthWrites());
    node.put(
        "externalSideEffects", effects.externalSideEffects());
    node.put("realUserDataReads", effects.realUserDataReads());
    return node;
  }

  private static ObjectNode issueNode(
      OfflineComparisonReport.Issue issue) {
    ObjectNode node = JSON.createObjectNode();
    node.put("code", issue.code());
    node.put("caseId", issue.caseId());
    node.put("repetition", issue.repetition());
    node.put("armId", issue.armId());
    return node;
  }

  private static OfflineComparisonReport parseReport(
      ObjectNode node) {
    ArrayNode pairsNode =
        array(node, "pairs", EXPECTED_PAIRS, EXPECTED_PAIRS);
    List<OfflineComparisonReport.PairRecord> pairs =
        new ArrayList<>(EXPECTED_PAIRS);
    for (JsonNode pair : pairsNode) {
      pairs.add(parsePair(pair));
    }
    ArrayNode evaluationsNode =
        array(
            node,
            "evaluations",
            EXPECTED_EVALUATIONS,
            EXPECTED_EVALUATIONS);
    List<OfflineComparisonReport.VerifierEvaluation> evaluations =
        new ArrayList<>(EXPECTED_EVALUATIONS);
    for (JsonNode evaluation : evaluationsNode) {
      evaluations.add(parseEvaluation(evaluation));
    }
    ArrayNode issuesNode =
        array(node, "issues", 0, MAX_ISSUES);
    List<OfflineComparisonReport.Issue> issues =
        new ArrayList<>(issuesNode.size());
    for (JsonNode issue : issuesNode) {
      issues.add(parseIssue(issue));
    }
    return new OfflineComparisonReport(
        text(node, "schemaVersion"),
        text(node, "reportKind"),
        text(node, "reportId"),
        text(node, "integrityProfile"),
        text(node, "packRawSha256"),
        text(node, "taskId"),
        text(node, "suiteId"),
        text(node, "variable"),
        integer(node, "repetitions"),
        text(node, "runnerVersion"),
        text(node, "candidateGeneratorVersion"),
        text(node, "h0VerifierVersion"),
        text(node, "h1VerifierVersion"),
        text(node, "frozenTime"),
        pairs,
        evaluations,
        parseSummary(node.get("summary")),
        parseEffects(node.get("effects")),
        status(text(node, "status")),
        issues,
        text(node, "integrityHash"));
  }

  private static OfflineComparisonReport.PairRecord parsePair(
      JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "pairId",
            "executionBaseId",
            "caseId",
            "repetition",
            "candidateMode",
            "faultClass",
            "candidateGeneratorVersion",
            "candidate",
            "candidateFingerprint",
            "integrityHash");
    return new OfflineComparisonReport.PairRecord(
        text(node, "pairId"),
        text(node, "executionBaseId"),
        text(node, "caseId"),
        integer(node, "repetition"),
        text(node, "candidateMode"),
        text(node, "faultClass"),
        text(node, "candidateGeneratorVersion"),
        parseCandidate(node.get("candidate")),
        text(node, "candidateFingerprint"),
        text(node, "integrityHash"));
  }

  private static OfflineComparisonReport.CandidateSnapshot
      parseCandidate(JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "proposal",
            "obtainedEvidenceRefs",
            "requiredEvidenceRef",
            "requiredEvidenceAvailable");
    ObjectNode proposal =
        object(
            node.get("proposal"),
            "REPORT_JSON_INVALID",
            "content",
            "evidenceRefs");
    return new OfflineComparisonReport.CandidateSnapshot(
        new OfflineComparisonReport.ProposalSnapshot(
            text(proposal, "content"),
            stringList(proposal, "evidenceRefs")),
        stringList(node, "obtainedEvidenceRefs"),
        text(node, "requiredEvidenceRef"),
        bool(node, "requiredEvidenceAvailable"));
  }

  private static OfflineComparisonReport.VerifierEvaluation
      parseEvaluation(JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "evaluationId",
            "pairId",
            "executionBaseId",
            "caseId",
            "repetition",
            "armId",
            "verifierVersion",
            "candidateFingerprint",
            "expectedStatus",
            "expectedFailureCode",
            "observedStatus",
            "observedFailureCode",
            "matchesExpected",
            "integrityHash");
    return new OfflineComparisonReport.VerifierEvaluation(
        text(node, "evaluationId"),
        text(node, "pairId"),
        text(node, "executionBaseId"),
        text(node, "caseId"),
        integer(node, "repetition"),
        text(node, "armId"),
        text(node, "verifierVersion"),
        text(node, "candidateFingerprint"),
        text(node, "expectedStatus"),
        nullableText(node, "expectedFailureCode"),
        text(node, "observedStatus"),
        nullableText(node, "observedFailureCode"),
        bool(node, "matchesExpected"),
        text(node, "integrityHash"));
  }

  private static OfflineComparisonReport.Summary parseSummary(
      JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "sharedCandidateGenerations",
            "verifierEvaluations",
            "h0Accepted",
            "h0FaultAcceptances",
            "h1Accepted",
            "h1FaultRejections",
            "outcomeMismatches");
    return new OfflineComparisonReport.Summary(
        integer(node, "sharedCandidateGenerations"),
        integer(node, "verifierEvaluations"),
        integer(node, "h0Accepted"),
        integer(node, "h0FaultAcceptances"),
        integer(node, "h1Accepted"),
        integer(node, "h1FaultRejections"),
        integer(node, "outcomeMismatches"));
  }

  private static OfflineComparisonReport.OwnedEffects parseEffects(
      JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "evidenceScope",
            "sharedCandidateGenerations",
            "verifierEvaluations",
            "h0Evaluations",
            "h1Evaluations",
            "literalFixtureReads",
            "agentKernelRuns",
            "productAgentRuns",
            "modelInvocations",
            "toolLoopExecutions",
            "harnessRunBundles",
            "credentialReads",
            "networkCalls",
            "connectorCalls",
            "productTruthWrites",
            "externalSideEffects",
            "realUserDataReads");
    return new OfflineComparisonReport.OwnedEffects(
        text(node, "evidenceScope"),
        integer(node, "sharedCandidateGenerations"),
        integer(node, "verifierEvaluations"),
        integer(node, "h0Evaluations"),
        integer(node, "h1Evaluations"),
        integer(node, "literalFixtureReads"),
        integer(node, "agentKernelRuns"),
        integer(node, "productAgentRuns"),
        integer(node, "modelInvocations"),
        integer(node, "toolLoopExecutions"),
        integer(node, "harnessRunBundles"),
        integer(node, "credentialReads"),
        integer(node, "networkCalls"),
        integer(node, "connectorCalls"),
        integer(node, "productTruthWrites"),
        integer(node, "externalSideEffects"),
        integer(node, "realUserDataReads"));
  }

  private static OfflineComparisonReport.Issue parseIssue(
      JsonNode value) {
    ObjectNode node =
        object(
            value,
            "REPORT_JSON_INVALID",
            "code",
            "caseId",
            "repetition",
            "armId");
    return new OfflineComparisonReport.Issue(
        text(node, "code"),
        text(node, "caseId"),
        integer(node, "repetition"),
        text(node, "armId"));
  }

  private static ObjectNode object(
      JsonNode value, String code, String... fields) {
    if (!(value instanceof ObjectNode node)
        || node.size() != fields.length
        || !fieldSet(node).equals(Set.of(fields))) {
      throw rejected(code);
    }
    return node;
  }

  private static Set<String> fieldSet(ObjectNode node) {
    Set<String> result = new HashSet<>();
    node.fieldNames().forEachRemaining(result::add);
    return Set.copyOf(result);
  }

  private static ArrayNode array(
      ObjectNode node, String field, int minimum, int maximum) {
    JsonNode value = node.get(field);
    if (!(value instanceof ArrayNode array)
        || array.size() < minimum
        || array.size() > maximum) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return array;
  }

  private static List<String> stringList(
      ObjectNode node, String field) {
    ArrayNode array =
        array(node, field, 0, MAX_REFERENCE_COUNT);
    List<String> result = new ArrayList<>(array.size());
    for (JsonNode value : array) {
      if (!value.isTextual()) {
        throw rejected("REPORT_JSON_INVALID");
      }
      result.add(requireUnicodeScalarString(value.textValue()));
    }
    return List.copyOf(result);
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return requireUnicodeScalarString(value.textValue());
  }

  private static String nullableText(
      ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw rejected("REPORT_JSON_INVALID");
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return requireUnicodeScalarString(value.textValue());
  }

  private static int integer(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return value.intValue();
  }

  private static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw rejected("REPORT_JSON_INVALID");
    }
    return value.booleanValue();
  }

  private static OfflineComparisonReport.Status status(
      String value) {
    try {
      return OfflineComparisonReport.Status.valueOf(value);
    } catch (IllegalArgumentException failure) {
      throw rejected("REPORT_JSON_INVALID");
    }
  }

  private static void putNullable(
      ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static String requireUnicodeScalarString(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length()
            || !Character.isLowSurrogate(
                value.charAt(index + 1))) {
          throw rejected("REPORT_JSON_INVALID");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw rejected("REPORT_JSON_INVALID");
      }
    }
    return value;
  }

  private static void requireUnicodeScalarTree(JsonNode value) {
    if (value.isTextual()) {
      requireUnicodeScalarString(value.textValue());
      return;
    }
    if (value.isArray()) {
      value.forEach(OfflineComparisonReportJson::requireUnicodeScalarTree);
      return;
    }
    if (value.isObject()) {
      value.forEachEntry(
          (name, child) -> {
            requireUnicodeScalarString(name);
            requireUnicodeScalarTree(child);
          });
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
