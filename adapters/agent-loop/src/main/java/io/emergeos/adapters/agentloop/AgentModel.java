package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral model boundary used by the framework-free Agent loop.
 *
 * <p>Every run receives a new {@link Session}. Provider continuation state, response IDs and tool
 * call IDs must remain inside that session, so concurrent runs cannot share mutable context.
 * Provider SDK types must not cross this interface.
 */
@FunctionalInterface
public interface AgentModel {

  Session open(TaskEnvelope task);

  default String executionProfileId() {
    return null;
  }

  default String executionProfileFingerprint() {
    return null;
  }

  @FunctionalInterface
  interface Session extends AutoCloseable {

    ModelStep next(Turn turn, ModelCallContext context);

    @Override
    default void close() {}
  }

  record ModelCallContext(
      long remainingDeadlineMs,
      BigDecimal remainingBudgetUsd,
      CancellationSignal cancellation) {

    public ModelCallContext {
      ContractValueDomains.requireDuration(
          remainingDeadlineMs, "remainingDeadlineMs", false);
      ContractValueDomains.requireUsd(remainingBudgetUsd, "remainingBudgetUsd");
      Objects.requireNonNull(cancellation, "cancellation");
    }
  }

  record ModelStep(
      Decision decision,
      String resolvedModel,
      ModelUsage usage) {

    public ModelStep {
      Objects.requireNonNull(decision, "decision");
      ContractText.require(
          resolvedModel, "resolvedModel", ContractText.MAX_MODEL_LENGTH);
      if (!resolvedModel.matches("[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")) {
        throw new IllegalArgumentException("resolvedModel is outside the safe model domain");
      }
      Objects.requireNonNull(usage, "usage");
    }
  }

  record ModelUsage(BigDecimal costUsd, long tokenCount) {

    public ModelUsage {
      ContractValueDomains.requireUsd(costUsd, "model costUsd");
      ContractValueDomains.requireSafeCount(tokenCount, "model tokenCount");
    }

    public static ModelUsage zero() {
      return new ModelUsage(BigDecimal.ZERO, 0);
    }
  }

  record Turn(TaskEnvelope task, List<ToolResult> toolResults) {

    public Turn {
      Objects.requireNonNull(task, "task");
      toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults"));
    }
  }

  sealed interface Decision permits ToolCall, FinalDraft, Failed {}

  record ToolCall(String toolName, ToolArguments arguments) implements Decision {

    public ToolCall {
      requireToolName(toolName);
      Objects.requireNonNull(arguments, "arguments");
    }
  }

  /**
   * Bounded, immutable and redacted carrier for untrusted provider tool arguments.
   *
   * <p>Tool-specific parsing belongs to the Tool validation boundary, after model attribution and
   * before dispatch. Keeping bytes opaque here preserves malformed/duplicate/unknown JSON for that
   * boundary without allowing record {@code toString()} output to disclose it.
   */
  final class ToolArguments {

    public static final int MAX_UTF8_BYTES = 65_536;

    private final byte[] utf8;

    private ToolArguments(byte[] utf8) {
      this.utf8 = utf8;
    }

    public static ToolArguments fromJson(String rawJson) {
      Objects.requireNonNull(rawJson, "rawJson");
      if (rawJson.indexOf('\0') >= 0 || ContractText.containsLoneSurrogate(rawJson)) {
        throw new IllegalArgumentException(
            "tool arguments must be Unicode scalar text without NUL");
      }
      byte[] encoded = rawJson.getBytes(StandardCharsets.UTF_8);
      if (encoded.length > MAX_UTF8_BYTES) {
        throw new IllegalArgumentException("tool arguments exceed the transport boundary");
      }
      return new ToolArguments(encoded);
    }

    public static ToolArguments forReference(String reference) {
      requireReference(reference);
      return fromJson("{\"reference\":\"" + reference + "\"}");
    }

    public byte[] copyUtf8() {
      return utf8.clone();
    }

    public int byteLength() {
      return utf8.length;
    }

    @Override
    public boolean equals(Object candidate) {
      return candidate instanceof ToolArguments other
          && Arrays.equals(utf8, other.utf8);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(utf8);
    }

    @Override
    public String toString() {
      return "ToolArguments[redacted]";
    }
  }

  record FinalDraft(String content, List<String> evidenceRefs) implements Decision {

    public FinalDraft {
      evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }
  }

  /**
   * A provider response whose model identity and usage are attributable, but whose decision cannot
   * be accepted. Keeping this inside {@link ModelStep} prevents paid usage from disappearing into
   * an exception-only path.
   */
  record Failed(String failureReason) implements Decision {

    public Failed {
      if (failureReason == null
          || !failureReason.matches("[A-Z][A-Z0-9_]{0,127}")) {
        throw new IllegalArgumentException(
            "failureReason must be a stable uppercase code");
      }
    }
  }

  record ToolResult(String toolName, String reference, String content) {

    public ToolResult {
      requireToolName(toolName);
      requireReference(reference);
      Objects.requireNonNull(content, "content");
    }
  }

  private static void requireToolName(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException(
          "toolName must be a bounded lowercase canonical name");
    }
  }

  private static void requireReference(String value) {
    if (value == null
        || value.length() > 240
        || !value.matches(
            "[a-z][a-z0-9+.-]{0,31}://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException(
          "reference must be a bounded canonical resource reference");
    }
  }
}
