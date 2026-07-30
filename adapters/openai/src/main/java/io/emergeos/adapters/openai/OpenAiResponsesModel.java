package io.emergeos.adapters.openai;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.StructuredResponseCreateParams;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentModelFailure;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * OpenAI Responses implementation of the provider-neutral model boundary.
 *
 * <p>The adapter is intentionally stateless across runs. Each {@link Session} owns its manual
 * Responses replay items, and neither this class nor the SDK client is allowed to resolve
 * credentials or choose a model route.
 */
public final class OpenAiResponsesModel implements AgentModel {

  public static final String PROTOCOL_VERSION =
      "openai-responses-v1-openai-java-4.43.0";

  private static final String PROVIDER = "openai.responses";
  private static final String CAPTURE_TOOL = "capture_read";
  private static final String INTERNAL_CAPTURE_TOOL = "capture.read";
  private static final String MALFORMED =
      AgentModelFailure.Code.RESPONSE_MALFORMED.failureReason();
  private static final ObjectMapper STRICT_JSON =
      ObjectMappers.jsonMapper()
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String USAGE_LIMIT_EXCEEDED =
      "MODEL_USAGE_LIMIT_EXCEEDED";
  private static final String INSTRUCTIONS =
      """
      You are the EmergeOS synthetic draft model boundary.
      Use only the declared capture_read reference. Never invent another source.
      After the tool result, return only the requested structured article draft and cite exactly
      that capture reference.
      """;

  private final AgentExecutionProfile profile;
  private final OpenAIClient client;

  public OpenAiResponsesModel(
      AgentExecutionProfile profile, OpenAIClient client) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.client = Objects.requireNonNull(client, "client");
    if (!profile.modelBound()
        || !PROVIDER.equals(profile.modelProvider())
        || !PROTOCOL_VERSION.equals(profile.modelAdapterVersion())) {
      throw new IllegalArgumentException(
          "OpenAI Responses requires its exact model-bound execution profile");
    }
  }

  @Override
  public String executionProfileId() {
    return profile.id();
  }

  @Override
  public String executionProfileFingerprint() {
    return profile.fingerprint();
  }

  @Override
  public Session open(TaskEnvelope task) {
    try {
      profile.requireTaskBinding(task);
    } catch (RuntimeException rejectedTask) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
    }
    return new ResponsesSession(task);
  }

  private final class ResponsesSession implements Session {

    private final TaskEnvelope task;
    private final String taskHash;
    private final String captureRef;
    private final List<ResponseInputItem> history = new ArrayList<>();
    private int step;
    private String pendingCallId;
    private String observedResolvedModel;
    private boolean closed;

    private ResponsesSession(TaskEnvelope task) {
      this.task = task;
      this.taskHash = IntegrityHashes.taskHash(task);
      this.captureRef = task.inputRefs().getFirst();
      this.history.add(
          ResponseInputItem.ofEasyInputMessage(
              EasyInputMessage.builder()
                  .role(EasyInputMessage.Role.USER)
                  .content(
                      "Task intent:\n"
                          + task.intent()
                          + "\nDeclared capture reference:\n"
                          + captureRef)
                  .build()));
    }

    @Override
    public synchronized ModelStep next(
        Turn turn, ModelCallContext context) {
      requireActiveTurn(turn);
      if (step == 2) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
      }
      requireCallBudget(context);
      if (step == 0) {
        if (!turn.toolResults().isEmpty()) {
          throw new AgentModelFailure(
              AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
        }
        step = 2;
        Response response =
            invokeProvider(
                () ->
                    client
                        .responses()
                        .create(firstRequest(), requestOptions(context)));
        Attribution attribution = attribution(response);
        if (!acceptAttribution(attribution)) {
          return failed(
              attribution,
              AgentModelFailure.Code.ATTRIBUTION_MISMATCH.failureReason());
        }
        if (!attribution.withinReviewedLimits()) {
          return failed(attribution, USAGE_LIMIT_EXCEEDED);
        }
        ModelStep parsed = parseToolCall(response, attribution);
        if (parsed.decision() instanceof ToolCall) {
          step = 1;
        }
        return parsed;
      }
      if (step == 1) {
        ToolResult result = requireMatchingToolResult(turn.toolResults());
        history.add(
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(pendingCallId)
                    .outputAsJson(
                        new CaptureReadOutput(
                            result.reference(), result.content()))
                    .callerDirect()
                    .build()));
        step = 2;
        Response response =
            invokeProvider(
                () ->
                    client
                        .responses()
                        .create(
                            secondRequest().rawParams(),
                            requestOptions(context)));
        Attribution attribution = attribution(response);
        if (!acceptAttribution(attribution)) {
          return failed(
              attribution,
              AgentModelFailure.Code.ATTRIBUTION_MISMATCH.failureReason());
        }
        if (!attribution.withinReviewedLimits()) {
          return failed(attribution, USAGE_LIMIT_EXCEEDED);
        }
        return parseFinal(response, attribution);
      }
      throw new AssertionError("unreachable model session state");
    }

    private void requireActiveTurn(Turn turn) {
      Objects.requireNonNull(turn, "turn");
      if (closed
          || !taskHash.equals(IntegrityHashes.taskHash(turn.task()))) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
      }
      try {
        profile.requireTaskBinding(turn.task());
      } catch (RuntimeException rejectedTask) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
      }
    }

    private boolean acceptAttribution(Attribution attribution) {
      if (!attribution.matchesRequestedModel()
          || (observedResolvedModel != null
              && !observedResolvedModel.equals(
                  attribution.resolvedModel()))) {
        return false;
      }
      if (observedResolvedModel == null) {
        observedResolvedModel = attribution.resolvedModel();
      }
      return true;
    }

    private void requireCallBudget(ModelCallContext context) {
      Objects.requireNonNull(context, "context");
      if (context.cancellation().isCancelled()) {
        throw new AgentModelFailure(AgentModelFailure.Code.CANCELLED);
      }
      BigDecimal reservation =
          profile
              .pricing()
              .reserveCostUsd(
                  1,
                  profile.maxInputTokensPerStep(),
                  profile.maxOutputTokensPerStep());
      if (context.remainingBudgetUsd().compareTo(reservation) < 0) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.BUDGET_EXHAUSTED);
      }
      if (context.cancellation().isCancelled()) {
        throw new AgentModelFailure(AgentModelFailure.Code.CANCELLED);
      }
    }

    private ResponseCreateParams firstRequest() {
      return ResponseCreateParams.builder()
          .model(profile.modelRequested())
          .instructions(INSTRUCTIONS)
          .inputOfResponse(List.copyOf(history))
          .store(false)
          .parallelToolCalls(false)
          .serviceTier(ResponseCreateParams.ServiceTier.DEFAULT)
          .maxOutputTokens(profile.maxOutputTokensPerStep())
          .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
          .addTool(CaptureReadArguments.class)
          .toolChoice(
              ToolChoiceFunction.builder().name(CAPTURE_TOOL).build())
          .build();
    }

    private StructuredResponseCreateParams<FinalDraftPayload>
        secondRequest() {
      return ResponseCreateParams.builder()
          .model(profile.modelRequested())
          .instructions(INSTRUCTIONS)
          .inputOfResponse(List.copyOf(history))
          .store(false)
          .parallelToolCalls(false)
          .serviceTier(ResponseCreateParams.ServiceTier.DEFAULT)
          .maxOutputTokens(profile.maxOutputTokensPerStep())
          .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
          .tools(List.of())
          .toolChoice(ToolChoiceOptions.NONE)
          .text(FinalDraftPayload.class)
          .build();
    }

    private RequestOptions requestOptions(ModelCallContext context) {
      return RequestOptions.builder()
          .timeout(Duration.ofMillis(context.remainingDeadlineMs()))
          .build();
    }

    private ModelStep parseToolCall(
        Response response, Attribution attribution) {
      try {
        if (!isCompletedDefaultResponse(response)) {
          return failed(attribution, MALFORMED);
        }
        ResponseFunctionToolCall functionCall = null;
        List<ResponseInputItem> replay = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
          if (item.isReasoning()) {
            item.asReasoning().validate();
            replay.add(
                ResponseInputItem.ofReasoning(item.asReasoning()));
          } else if (item.isFunctionCall() && functionCall == null) {
            functionCall = item.asFunctionCall();
            functionCall.validate();
            replay.add(ResponseInputItem.ofFunctionCall(functionCall));
          } else {
            return failed(attribution, MALFORMED);
          }
        }
        if (functionCall == null
            || !validCaptureCall(functionCall)
            || !safeCallId(functionCall.callId())) {
          return failed(attribution, MALFORMED);
        }
        history.addAll(replay);
        pendingCallId = functionCall.callId();
        return new ModelStep(
            new ToolCall(INTERNAL_CAPTURE_TOOL, captureRef),
            attribution.resolvedModel(),
            attribution.usage());
      } catch (RuntimeException malformedResponse) {
        return failed(attribution, MALFORMED);
      }
    }

    private boolean validCaptureCall(ResponseFunctionToolCall functionCall) {
      if (!CAPTURE_TOOL.equals(functionCall.name())
          || functionCall
              .status()
              .filter(
                  status ->
                      !ResponseFunctionToolCall.Status.COMPLETED.equals(
                          status))
              .isPresent()
          || functionCall
              .caller()
              .filter(caller -> !caller.isDirect())
              .isPresent()) {
        return false;
      }
      try {
        JsonNode arguments =
            STRICT_JSON.readTree(functionCall.arguments());
        return arguments.isObject()
            && arguments.size() == 1
            && arguments.path("reference").isTextual()
            && captureRef.equals(arguments.path("reference").asText());
      } catch (Exception malformedArguments) {
        return false;
      }
    }

    private ToolResult requireMatchingToolResult(
        List<ToolResult> toolResults) {
      if (toolResults.size() != 1) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
      }
      ToolResult result = toolResults.getFirst();
      if (!INTERNAL_CAPTURE_TOOL.equals(result.toolName())
          || !captureRef.equals(result.reference())) {
        throw new AgentModelFailure(
            AgentModelFailure.Code.EGRESS_NOT_ALLOWED);
      }
      return result;
    }

    private ModelStep parseFinal(
        Response response,
        Attribution attribution) {
      try {
        if (!isCompletedDefaultResponse(response)) {
          return failed(attribution, MALFORMED);
        }
        ResponseOutputMessage message = null;
        for (ResponseOutputItem item : response.output()) {
          if (item.isReasoning()) {
            item.asReasoning().validate();
            continue;
          }
          if (item.isMessage() && message == null) {
            message = item.asMessage();
            message.validate();
            continue;
          }
          return failed(attribution, MALFORMED);
        }
        if (message == null
            || !ResponseOutputMessage.Status.COMPLETED.equals(
                message.status())
            || message.content().size() != 1
            || !message.content().getFirst().isOutputText()) {
          return failed(attribution, MALFORMED);
        }
        JsonNode payload =
            STRICT_JSON.readTree(
                message
                    .content()
                    .getFirst()
                    .asOutputText()
                    .text());
        if (!payload.isObject()
            || payload.size() != 2
            || !payload.path("content").isTextual()
            || !payload.path("evidenceRefs").isArray()
            || payload.path("evidenceRefs").size() != 1
            || !payload.path("evidenceRefs").get(0).isTextual()
            || !captureRef.equals(
                payload.path("evidenceRefs").get(0).asText())) {
          return failed(attribution, MALFORMED);
        }
        return new ModelStep(
            new FinalDraft(
                payload.path("content").asText(), List.of(captureRef)),
            attribution.resolvedModel(),
            attribution.usage());
      } catch (Exception malformedResponse) {
        return failed(attribution, MALFORMED);
      }
    }

    @Override
    public synchronized void close() {
      closed = true;
      history.clear();
      pendingCallId = null;
      observedResolvedModel = null;
    }
  }

  private Attribution attribution(Response response) {
    final String resolvedModel;
    final ResponseUsage rawUsage;
    try {
      resolvedModel = resolvedModel(response.model());
      rawUsage =
          response
              .usage()
              .orElseThrow(
                  () ->
                      new AgentModelFailure(
                          AgentModelFailure.Code.USAGE_MISSING));
    } catch (AgentModelFailure failure) {
      throw failure;
    } catch (RuntimeException invalidAttribution) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.ATTRIBUTION_MISMATCH);
    }

    try {
      long inputTokens = rawUsage.inputTokens();
      long cachedInputTokens =
          rawUsage.inputTokensDetails().cachedTokens();
      long outputTokens = rawUsage.outputTokens();
      long reasoningTokens =
          rawUsage.outputTokensDetails().reasoningTokens();
      long totalTokens = rawUsage.totalTokens();
      ContractValueDomains.requireSafeCount(inputTokens, "inputTokens");
      ContractValueDomains.requireSafeCount(
          cachedInputTokens, "cachedInputTokens");
      ContractValueDomains.requireSafeCount(
          outputTokens, "outputTokens");
      ContractValueDomains.requireSafeCount(
          reasoningTokens, "reasoningTokens");
      ContractValueDomains.requireSafeCount(totalTokens, "totalTokens");
      if (cachedInputTokens > inputTokens
          || reasoningTokens > outputTokens
          || Math.addExact(inputTokens, outputTokens) != totalTokens) {
        throw new IllegalArgumentException("inconsistent model usage");
      }
      BigDecimal cost =
          profile
              .pricing()
              .actualCostUsd(
                  inputTokens, cachedInputTokens, outputTokens);
      boolean withinReviewedLimits =
          inputTokens <= profile.maxInputTokensPerStep()
              && outputTokens <= profile.maxOutputTokensPerStep();
      return new Attribution(
          resolvedModel,
          new ModelUsage(cost, totalTokens),
          withinReviewedLimits,
          resolvedModel.equals(profile.modelRequested())
              || resolvedModel.startsWith(
                  profile.modelRequested() + "-"));
    } catch (RuntimeException invalidUsage) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.RESPONSE_MALFORMED);
    }
  }

  private String resolvedModel(ResponsesModel model) {
    String resolved =
        model.string().orElseGet(
            () ->
                model.chat().map(value -> value.asString()).orElseGet(
                    () -> model.only().orElseThrow().asString()));
    if (!resolved.matches("[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")) {
      throw new IllegalArgumentException("invalid resolved model identity");
    }
    return resolved;
  }

  private static boolean isCompletedDefaultResponse(Response response) {
    return response.status().filter(ResponseStatus.COMPLETED::equals).isPresent()
        && response
            .serviceTier()
            .filter(Response.ServiceTier.DEFAULT::equals)
            .isPresent()
        && !response.parallelToolCalls();
  }

  private static boolean safeCallId(String value) {
    return value != null
        && value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}");
  }

  private static ModelStep failed(
      Attribution attribution, String failureReason) {
    return new ModelStep(
        new Failed(failureReason),
        attribution.resolvedModel(),
        attribution.usage());
  }

  private static <T> T invokeProvider(Supplier<T> call) {
    try {
      return call.get();
    } catch (UnauthorizedException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.AUTHENTICATION_FAILED);
    } catch (BadRequestException
        | PermissionDeniedException
        | NotFoundException
        | UnprocessableEntityException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.REQUEST_REJECTED);
    } catch (RateLimitException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.RATE_LIMITED);
    } catch (InternalServerException | UnexpectedStatusCodeException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.PROVIDER_UNAVAILABLE);
    } catch (OpenAIIoException | OpenAIRetryableException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.CALL_OUTCOME_UNKNOWN);
    } catch (OpenAIInvalidDataException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.RESPONSE_MALFORMED);
    } catch (OpenAIServiceException failure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.PROVIDER_UNAVAILABLE);
    }
  }

  @JsonTypeName(CAPTURE_TOOL)
  private record CaptureReadArguments(String reference) {}

  private record CaptureReadOutput(String reference, String content) {}

  private record FinalDraftPayload(
      String content, List<String> evidenceRefs) {}

  private record Attribution(
      String resolvedModel,
      ModelUsage usage,
      boolean withinReviewedLimits,
      boolean matchesRequestedModel) {}
}
