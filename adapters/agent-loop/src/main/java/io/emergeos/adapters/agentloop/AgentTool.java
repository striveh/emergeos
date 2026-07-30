package io.emergeos.adapters.agentloop;

import io.emergeos.contracts.TaskEnvelope;
import java.util.Objects;

/**
 * Tool boundary with an explicit, side-effect-free validation phase.
 *
 * <p>The registry is the only caller allowed to turn a {@link Valid} result into
 * {@link #execute(TaskEnvelope, ValidatedArguments)}. A Tool implementation therefore never needs
 * to accept the untrusted provider argument envelope in its side-effecting method.
 */
public interface AgentTool<A extends AgentTool.ValidatedArguments> {

  String name();

  String argumentSchemaId();

  Validation<A> validate(TaskEnvelope task, AgentModel.ToolCall call);

  AgentModel.ToolResult execute(TaskEnvelope task, A arguments);

  interface ValidatedArguments {

    String reference();
  }

  sealed interface Validation<A extends ValidatedArguments> permits Valid, Invalid {

    static <A extends ValidatedArguments> Validation<A> valid(A arguments) {
      return new Valid<>(arguments);
    }

    static <A extends ValidatedArguments> Validation<A> invalid() {
      return new Invalid<>();
    }
  }

  record Valid<A extends ValidatedArguments>(A arguments) implements Validation<A> {

    public Valid {
      Objects.requireNonNull(arguments, "arguments");
    }
  }

  record Invalid<A extends ValidatedArguments>() implements Validation<A> {}
}
