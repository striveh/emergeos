package io.emergeos.adapters.agentloop;

import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.TaskEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed tool registry. A Task can use only the intersection of this registry and its own
 * server-owned allowlist.
 */
public final class AgentToolRegistry {

  public static final String DEFAULT_VERSION = "agent-tools-v2";
  public static final String EMPTY_VERSION = "agent-tools-none-v1";
  private static final Map<String, Map<String, String>> VERSIONED_SCHEMAS =
      Map.of(
          EMPTY_VERSION,
          Map.of(),
          DEFAULT_VERSION,
          Map.of(
              CaptureReadTool.NAME,
              CaptureReadTool.ARGUMENT_SCHEMA_ID));

  private final String version;
  private final Map<String, AgentTool<?>> tools;

  public AgentToolRegistry(List<? extends AgentTool<?>> tools) {
    this(DEFAULT_VERSION, tools);
  }

  public AgentToolRegistry(String version, List<? extends AgentTool<?>> tools) {
    this.version = requireVersion(version);
    Map<String, String> versionedSchemas = VERSIONED_SCHEMAS.get(this.version);
    if (versionedSchemas == null) {
      throw new IllegalArgumentException("unsupported tool registry version");
    }
    Objects.requireNonNull(tools, "tools");
    Map<String, AgentTool<?>> indexed = new LinkedHashMap<>();
    for (AgentTool<?> tool : tools) {
      Objects.requireNonNull(tool, "tool");
      requireToolName(tool.name());
      String schemaId = tool.argumentSchemaId();
      requireSchemaId(schemaId);
      if (!schemaId.equals(versionedSchemas.get(tool.name()))) {
        throw new IllegalArgumentException(
            "tool schema is not bound to registry version: " + tool.name());
      }
      if (indexed.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + tool.name());
      }
    }
    if (!indexed.keySet().equals(versionedSchemas.keySet())) {
      throw new IllegalArgumentException(
          "tool registry contents do not match the versioned manifest");
    }
    this.tools = Map.copyOf(indexed);
  }

  String version() {
    return version;
  }

  ToolPreparation prepare(TaskEnvelope task, AgentModel.ToolCall call) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(call, "call");
    AgentTool<?> tool = tools.get(call.toolName());
    if (tool == null || !task.requiredTools().contains(call.toolName())) {
      return ToolPreparation.rejected("TOOL_NOT_ALLOWED");
    }
    try {
      return prepareTyped(tool, task, call);
    } catch (RuntimeException validationFailure) {
      return ToolPreparation.rejected("TOOL_ARGUMENT_VALIDATION_FAILED");
    }
  }

  boolean isRegistered(String toolName) {
    return tools.containsKey(toolName);
  }

  private static <A extends AgentTool.ValidatedArguments> ToolPreparation prepareTyped(
      AgentTool<A> tool, TaskEnvelope task, AgentModel.ToolCall call) {
    AgentTool.Validation<A> validation =
        Objects.requireNonNull(tool.validate(task, call), "tool validation");
    if (validation instanceof AgentTool.Invalid<A>) {
      return ToolPreparation.rejected("TOOL_ARGUMENTS_INVALID");
    }
    if (!(validation instanceof AgentTool.Valid<A> valid)) {
      return ToolPreparation.rejected("TOOL_ARGUMENT_VALIDATION_FAILED");
    }
    A arguments = valid.arguments();
    String reference = arguments.reference();
    if (reference == null) {
      return ToolPreparation.rejected("TOOL_ARGUMENTS_INVALID");
    }
    if (!task.inputRefs().contains(reference)) {
      return ToolPreparation.rejected("TOOL_NOT_ALLOWED");
    }
    return ToolPreparation.prepared(
        new PreparedToolExecution(
            tool.name(),
            reference,
            () -> tool.execute(task, arguments)));
  }

  record ToolPreparation(
      PreparedToolExecution execution,
      String failureReason) {

    ToolPreparation {
      if ((execution == null) == (failureReason == null)) {
        throw new IllegalArgumentException(
            "Tool preparation must be exactly prepared or rejected");
      }
    }

    static ToolPreparation prepared(PreparedToolExecution execution) {
      return new ToolPreparation(
          Objects.requireNonNull(execution, "execution"), null);
    }

    static ToolPreparation rejected(String failureReason) {
      return new ToolPreparation(
          null, Objects.requireNonNull(failureReason, "failureReason"));
    }

    boolean prepared() {
      return execution != null;
    }
  }

  static final class PreparedToolExecution {

    private final String toolName;
    private final String reference;
    private final ToolExecutor executor;
    private boolean executed;

    private PreparedToolExecution(
        String toolName, String reference, ToolExecutor executor) {
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.reference = Objects.requireNonNull(reference, "reference");
      this.executor = Objects.requireNonNull(executor, "executor");
    }

    String toolName() {
      return toolName;
    }

    String reference() {
      return reference;
    }

    AgentModel.ToolResult execute() {
      if (executed) {
        throw new IllegalStateException("a prepared Tool execution is one-shot");
      }
      executed = true;
      return executor.execute();
    }
  }

  @FunctionalInterface
  private interface ToolExecutor {

    AgentModel.ToolResult execute();
  }

  private static String requireVersion(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      throw new IllegalArgumentException("tool registry version is outside the safe domain");
    }
    return value;
  }

  private static void requireToolName(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("tool name is outside the safe domain");
    }
  }

  private static void requireSchemaId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~:/-]{0,199}")) {
      throw new IllegalArgumentException("tool argument schema id is outside the safe domain");
    }
  }
}
