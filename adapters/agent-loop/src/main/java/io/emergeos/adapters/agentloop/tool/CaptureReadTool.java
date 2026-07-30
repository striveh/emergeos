package io.emergeos.adapters.agentloop.tool;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentTool;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.port.CaptureStore;
import java.util.NoSuchElementException;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class CaptureReadTool implements AgentTool<CaptureReadTool.Arguments> {

  public static final String NAME = "capture.read";
  public static final String ARGUMENT_SCHEMA_ID =
      "urn:emergeos:tool:capture-read-arguments:v1";

  private static final int MAX_ARGUMENT_BYTES = 1_024;
  private static final String CAPTURE_REFERENCE_PATTERN =
      "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}";
  private static final JsonMapper STRICT_ARGUMENTS =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_ARGUMENT_BYTES)
                          .maxTokenCount(16)
                          .maxNestingDepth(4)
                          .maxNameLength(32)
                          .maxStringLength(240)
                          .maxNumberLength(32)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final CaptureStore captures;

  public CaptureReadTool(CaptureStore captures) {
    this.captures = Objects.requireNonNull(captures, "captures");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String argumentSchemaId() {
    return ARGUMENT_SCHEMA_ID;
  }

  @Override
  public Validation<Arguments> validate(
      TaskEnvelope task, AgentModel.ToolCall call) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(call, "call");
    if (!NAME.equals(call.toolName())
        || call.arguments().byteLength() > MAX_ARGUMENT_BYTES) {
      return Validation.invalid();
    }
    try {
      JsonNode arguments = STRICT_ARGUMENTS.readTree(call.arguments().copyUtf8());
      if (arguments == null
          || !arguments.isObject()
          || arguments.size() != 1
          || !arguments.path("reference").isString()) {
        return Validation.invalid();
      }
      String reference = arguments.path("reference").stringValue();
      if (!reference.matches(CAPTURE_REFERENCE_PATTERN)) {
        return Validation.invalid();
      }
      return Validation.valid(new Arguments(reference));
    } catch (JacksonException invalidArguments) {
      return Validation.invalid();
    }
  }

  @Override
  public AgentModel.ToolResult execute(TaskEnvelope task, Arguments arguments) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(arguments, "arguments");
    if (!task.inputRefs().contains(arguments.reference())
        || !arguments.reference().matches(CAPTURE_REFERENCE_PATTERN)) {
      throw new IllegalArgumentException(
          "validated capture.read arguments drifted outside the Task");
    }
    String captureId = arguments.reference().substring("capture://".length());
    Capture capture =
        captures
            .findOwned(task.principalRef(), captureId)
            .orElseThrow(() -> new NoSuchElementException("Capture not found"));
    return new AgentModel.ToolResult(NAME, arguments.reference(), capture.content());
  }

  public record Arguments(String reference) implements AgentTool.ValidatedArguments {

    public Arguments {
      Objects.requireNonNull(reference, "reference");
    }
  }
}
