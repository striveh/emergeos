package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractValueDomainsTest {

  @Test
  void acceptsTheFrozenUsdAndSafeIntegerBoundaries() {
    assertDoesNotThrow(() -> result(new BigDecimal("0.1"), 1, 1));
    assertDoesNotThrow(() -> result(new BigDecimal("875825.408612"), 1, 1));
    assertDoesNotThrow(
        () ->
            result(
                ContractValueDomains.MAX_USD,
                ContractValueDomains.MAX_SAFE_INTEGER,
                ContractValueDomains.MAX_DURATION_MS));
  }

  @Test
  void rejectsValuesThatJavaNodeAndPostgresCannotShareExactly() {
    assertThrows(
        IllegalArgumentException.class,
        () -> result(new BigDecimal("0.0000001"), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> result(new BigDecimal("875825.4086121"), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> result(new BigDecimal("100.0000000001"), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> result(BigDecimal.ZERO, ContractValueDomains.MAX_SAFE_INTEGER + 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> result(BigDecimal.ZERO, 0, ContractValueDomains.MAX_DURATION_MS + 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalEncoding.encode(Map.of("value", 0.0000001d)));
  }

  @Test
  void rejectsLoneSurrogatesAndUnsafeIntegralHashInputs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalEncoding.encode("\uD800"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalEncoding.encode(Map.of("\uDC00", "value")));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalEncoding.encode(ContractValueDomains.MAX_SAFE_INTEGER + 1));
  }

  @Test
  void freezesSuccessAndFailureAttributionShape() {
    ResultEnvelope successful = result(BigDecimal.ZERO, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResultEnvelope(
                successful.schemaVersion(),
                successful.runId(),
                successful.taskId(),
                RunStatus.SUCCEEDED,
                successful.artifactRefs(),
                successful.evidenceRefs(),
                successful.claims(),
                successful.uncertainty(),
                successful.receiptRefs(),
                successful.resolvedModel(),
                successful.agentVersion(),
                successful.verifierVersion(),
                successful.costUsd(),
                successful.tokenCount(),
                successful.latencyMs(),
                successful.traceRef(),
                "SHOULD_BE_NULL"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResultEnvelope(
                successful.schemaVersion(),
                successful.runId(),
                successful.taskId(),
                RunStatus.FAILED,
                successful.artifactRefs(),
                successful.evidenceRefs(),
                successful.claims(),
                successful.uncertainty(),
                successful.receiptRefs(),
                successful.resolvedModel(),
                successful.agentVersion(),
                successful.verifierVersion(),
                successful.costUsd(),
                successful.tokenCount(),
                successful.latencyMs(),
                successful.traceRef(),
                null));
  }

  private static ResultEnvelope result(
      BigDecimal costUsd, long tokenCount, long latencyMs) {
    return new ResultEnvelope(
        "1.0",
        "run-domain",
        "task-domain",
        RunStatus.SUCCEEDED,
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        "domain-model-v1",
        "agent-v1",
        "verifier-v1",
        costUsd,
        tokenCount,
        latencyMs,
        "/api/v1/agent-runs/run-domain/trace",
        null);
  }
}
