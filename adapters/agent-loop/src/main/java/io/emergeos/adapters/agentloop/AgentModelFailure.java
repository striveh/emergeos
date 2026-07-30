package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.RunStatus;
import java.util.Objects;

/**
 * Typed, stable provider failure. Raw provider messages and response bodies are deliberately not
 * exposed through the public message or the Agent Trace.
 */
public final class AgentModelFailure extends RuntimeException {

  private final Code code;

  public AgentModelFailure(Code code) {
    this(code, null);
  }

  public AgentModelFailure(Code code, Throwable cause) {
    super(Objects.requireNonNull(code, "code").failureReason(), cause, false, false);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    EGRESS_NOT_ALLOWED("MODEL_EGRESS_NOT_ALLOWED", RunStatus.BLOCKED),
    BUDGET_EXHAUSTED("MODEL_BUDGET_EXHAUSTED", RunStatus.BLOCKED),
    CANCELLED("CANCELLED", RunStatus.CANCELLED),
    AUTHENTICATION_FAILED("MODEL_AUTHENTICATION_FAILED", RunStatus.FAILED),
    REQUEST_REJECTED("MODEL_REQUEST_REJECTED", RunStatus.FAILED),
    RATE_LIMITED("MODEL_RATE_LIMITED", RunStatus.FAILED),
    CALL_OUTCOME_UNKNOWN("MODEL_CALL_OUTCOME_UNKNOWN", RunStatus.FAILED),
    PROVIDER_UNAVAILABLE("MODEL_PROVIDER_UNAVAILABLE", RunStatus.FAILED),
    RESPONSE_MALFORMED("MODEL_RESPONSE_MALFORMED", RunStatus.FAILED),
    USAGE_MISSING("MODEL_USAGE_MISSING", RunStatus.FAILED),
    ATTRIBUTION_MISMATCH("MODEL_ATTRIBUTION_MISMATCH", RunStatus.FAILED);

    private final String failureReason;
    private final RunStatus status;

    Code(String failureReason, RunStatus status) {
      this.failureReason = failureReason;
      this.status = status;
    }

    public String failureReason() {
      return failureReason;
    }

    public RunStatus status() {
      return status;
    }
  }
}
