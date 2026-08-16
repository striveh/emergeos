package io.emergeos.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class IntegrityHashes {

  public static final String PROFILE = "emergeos-length-prefixed-sha256-v1";
  private static final String EMPTY_TRACE_DOMAIN = "emergeos.agent-trace.v1.empty";
  private static final String TRACE_EVENT_DOMAIN = "emergeos.agent-trace-event.v1";
  private static final String TRACE_ROOT_DOMAIN = "emergeos.agent-trace.v1";
  private static final String TASK_DOMAIN = "emergeos.task-envelope.v1";
  private static final String BUNDLE_DOMAIN = "emergeos.harness-run-bundle.v1";
  private static final String WORKER_RESULT_DOMAIN =
      "emergeos.worker-result-envelope.v1";
  private static final String HARNESS_CANDIDATE_DOMAIN =
      "emergeos.harness-candidate-envelope.v1";
  private static final String HARNESS_EVALUATION_REPORT_DOMAIN =
      "emergeos.harness-evaluation-report.v1";
  private static final String HARNESS_EVALUATION_REPORT_ID_DOMAIN =
      "emergeos.harness-evaluation-report-id.v1";

  private IntegrityHashes() {}

  public static String emptyTraceRoot() {
    return sha256(EMPTY_TRACE_DOMAIN.getBytes(StandardCharsets.UTF_8));
  }

  public static String traceEventHash(
      int sequence,
      TraceEventType type,
      String toolName,
      String status,
      String reference,
      String previousRootHash) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("previousRootHash", previousRootHash);
    body.put("reference", reference);
    body.put("sequence", sequence);
    body.put("status", status);
    body.put("toolName", toolName);
    body.put("type", type);
    return domainHash(TRACE_EVENT_DOMAIN, CanonicalEncoding.encode(body));
  }

  public static String nextTraceRoot(String previousRootHash, String eventHash) {
    requireHash(previousRootHash, "previousRootHash");
    requireHash(eventHash, "eventHash");
    byte[] previous = fromHex(previousRootHash);
    byte[] event = fromHex(eventHash);
    byte[] joined = new byte[previous.length + event.length];
    System.arraycopy(previous, 0, joined, 0, previous.length);
    System.arraycopy(event, 0, joined, previous.length, event.length);
    return domainHash(TRACE_ROOT_DOMAIN, joined);
  }

  public static String bundleHash(HarnessRunBundle bundle) {
    return domainHash(
        BUNDLE_DOMAIN,
        CanonicalEncoding.encode(
            CanonicalEncoding.recordValues(bundle, Set.of("integrityHash"))));
  }

  /**
   * Hashes the complete canonical Task, including every prompt-bearing and execution-binding
   * field supported by its schema version.
   */
  public static String taskHash(TaskEnvelope task) {
    if (task == null) {
      throw new NullPointerException("task");
    }
    return domainHash(TASK_DOMAIN, CanonicalEncoding.encode(task));
  }

  public static String workerResultHash(WorkerResultEnvelope result) {
    if (result == null) {
      throw new NullPointerException("result");
    }
    return domainHash(
        WORKER_RESULT_DOMAIN,
        CanonicalEncoding.encode(
            CanonicalEncoding.recordValues(result, Set.of("integrityHash"))));
  }

  static String workerResultHash(Map<String, Object> preimage) {
    return domainHash(
        WORKER_RESULT_DOMAIN, CanonicalEncoding.encode(preimage));
  }

  public static String harnessCandidateHash(
      HarnessCandidateEnvelope candidate) {
    if (candidate == null) {
      throw new NullPointerException("candidate");
    }
    return domainHash(
        HARNESS_CANDIDATE_DOMAIN,
        CanonicalEncoding.encode(
            CanonicalEncoding.recordValues(
                candidate, Set.of("integrityHash"))));
  }

  static String harnessCandidateHash(
      Map<String, Object> preimage) {
    return domainHash(
        HARNESS_CANDIDATE_DOMAIN,
        CanonicalEncoding.encode(preimage));
  }

  public static String harnessEvaluationReportHash(
      HarnessEvaluationReport report) {
    if (report == null) {
      throw new NullPointerException("report");
    }
    return domainHash(
        HARNESS_EVALUATION_REPORT_DOMAIN,
        CanonicalEncoding.encode(
            CanonicalEncoding.recordValues(
                report, Set.of("integrityHash"))));
  }

  static String harnessEvaluationReportHash(
      Map<String, Object> preimage) {
    return domainHash(
        HARNESS_EVALUATION_REPORT_DOMAIN,
        CanonicalEncoding.encode(preimage));
  }

  public static String harnessEvaluationReportId(
      HarnessEvaluationReport report) {
    if (report == null) {
      throw new NullPointerException("report");
    }
    return harnessEvaluationReportId(
        HarnessEvaluationReport.idPreimage(
            report.schemaVersion(),
            report.reportKind(),
            report.graphProtocolVersion(),
            report.packRawSha256(),
            report.environmentRawSha256(),
            report.evaluatorArms(),
            report.repetitions(),
            report.evaluations(),
            report.usageAggregate(),
            report.evaluatorEffects(),
            report.reportStatus(),
            report.integrityProfile()));
  }

  static String harnessEvaluationReportId(
      Map<String, Object> preimage) {
    return "harness-evaluation-report-"
        + domainHash(
            HARNESS_EVALUATION_REPORT_ID_DOMAIN,
            CanonicalEncoding.encode(preimage));
  }

  /** Standard SHA-256 over the exact UTF-8 bytes of immutable user-visible content. */
  public static String utf8ContentHash(String content) {
    if (content == null) {
      throw new NullPointerException("content");
    }
    return sha256(content.getBytes(StandardCharsets.UTF_8));
  }

  static String bundleHash(Map<String, Object> bundlePreimage) {
    return domainHash(BUNDLE_DOMAIN, CanonicalEncoding.encode(bundlePreimage));
  }

  static void requireHash(String value, String name) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex digest");
    }
  }

  private static String domainHash(String domain, byte[] canonical) {
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.writeBytes(domain.getBytes(StandardCharsets.UTF_8));
    input.write(0);
    input.writeBytes(canonical);
    return sha256(input.toByteArray());
  }

  private static String sha256(byte[] input) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] fromHex(String hex) {
    byte[] decoded = new byte[hex.length() / 2];
    for (int index = 0; index < decoded.length; index++) {
      decoded[index] =
          (byte)
              ((Character.digit(hex.charAt(index * 2), 16) << 4)
                  + Character.digit(hex.charAt(index * 2 + 1), 16));
    }
    return decoded;
  }

  private static String hex(byte[] value) {
    StringBuilder encoded = new StringBuilder(value.length * 2);
    for (byte item : value) {
      encoded.append(Character.forDigit((item >>> 4) & 0xf, 16));
      encoded.append(Character.forDigit(item & 0xf, 16));
    }
    return encoded.toString();
  }
}
