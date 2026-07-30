package io.emergeos.contracts;

import java.util.Objects;

public record AgentTraceEntry(
    int sequence,
    TraceEventType type,
    String toolName,
    String status,
    String reference,
    String previousRootHash,
    String eventHash) {

  public AgentTraceEntry {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(type, "type");
    ContractText.requireOptional(toolName, "toolName", ContractText.MAX_NAME_LENGTH);
    ContractText.require(status, "status", 100);
    ContractText.requireOptional(reference, "reference");
    IntegrityHashes.requireHash(previousRootHash, "previousRootHash");
    IntegrityHashes.requireHash(eventHash, "eventHash");
    String expected =
        IntegrityHashes.traceEventHash(
            sequence, type, toolName, status, reference, previousRootHash);
    if (!expected.equals(eventHash)) {
      throw new IllegalArgumentException("eventHash does not match the safe Trace event");
    }
    boolean toolEvent =
        type == TraceEventType.TOOL_REQUEST
            || type == TraceEventType.TOOL_RESULT
            || type == TraceEventType.TOOL_REJECTED;
    if (toolEvent != (toolName != null)) {
      throw new IllegalArgumentException("only tool events must name a tool");
    }
    switch (type) {
      case MODEL_STEP -> {
        if (!("COMPLETED".equals(status) || "FAILED".equals(status))
            || reference == null) {
          throw new IllegalArgumentException("MODEL_STEP metadata is outside the safe allowlist");
        }
      }
      case TOOL_REQUEST -> {
        if (!"REQUESTED".equals(status) || reference == null) {
          throw new IllegalArgumentException("TOOL_REQUEST metadata is outside the safe allowlist");
        }
      }
      case TOOL_RESULT -> {
        if (!"SUCCEEDED".equals(status) || reference == null) {
          throw new IllegalArgumentException("TOOL_RESULT metadata is outside the safe allowlist");
        }
      }
      case TOOL_REJECTED -> {
        if (!(status.equals("BLOCKED")
            || status.equals("LIMIT_EXHAUSTED")
            || status.equals("FAILED")
            || status.equals("MALFORMED_RESULT")
            || status.equals("DEADLINE_EXCEEDED"))) {
          throw new IllegalArgumentException("TOOL_REJECTED metadata is outside the safe allowlist");
        }
      }
      case STRUCTURED_FINAL -> {
        if (!"PROPOSED".equals(status) || reference == null) {
          throw new IllegalArgumentException(
              "STRUCTURED_FINAL metadata is outside the safe allowlist");
        }
      }
      case ARTIFACT_COMMITTED -> {
        if (!"SUCCEEDED".equals(status)
            || reference == null
            || !reference.matches(
                "artifact-version://[A-Za-z0-9][A-Za-z0-9._~-]{0,127}/[1-9][0-9]*")) {
          throw new IllegalArgumentException(
              "ARTIFACT_COMMITTED metadata is outside the safe allowlist");
        }
      }
    }
  }

  public static AgentTraceEntry create(
      int sequence,
      TraceEventType type,
      String toolName,
      String status,
      String reference,
      String previousRootHash) {
    return new AgentTraceEntry(
        sequence,
        type,
        toolName,
        status,
        reference,
        previousRootHash,
        IntegrityHashes.traceEventHash(
            sequence, type, toolName, status, reference, previousRootHash));
  }

}
