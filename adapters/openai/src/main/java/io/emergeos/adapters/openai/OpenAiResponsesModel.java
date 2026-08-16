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
import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelExecutionProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  public static final String PROMPT_SURFACE_VERSION =
      "openai-responses-draft-surface-v1";

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
  private static final String REVIEWED_DECISION_DOMAIN =
      "emergeos.openai-reviewed-decision.v1";
  private static final ResponseCreateParams.PromptCacheOptions
      EXPLICIT_CACHE_ONLY =
          ResponseCreateParams.PromptCacheOptions.builder()
              .mode(
                  ResponseCreateParams.PromptCacheOptions.Mode.EXPLICIT)
              .build();
  private static final String INSTRUCTIONS =
      """
      You are the EmergeOS synthetic draft model boundary.
      Use only the declared capture_read reference. Never invent another source.
      After the tool result, return only the requested structured article draft and cite exactly
      that capture reference.
      """;

  /**
   * Content address for the reviewed prompt, Tool and structured-output
   * surface used by both root and Worker model routes.
   */
  public static String promptSurfaceFingerprint(
      String modelRequested) {
    if (modelRequested == null || modelRequested.isBlank()) {
      throw new IllegalArgumentException(
          "modelRequested must not be blank");
    }
    String material =
        String.join(
            "\n",
            PROMPT_SURFACE_VERSION,
            "protocol=" + PROTOCOL_VERSION,
            "instructions=" + INSTRUCTIONS,
            "firstToolName=" + CAPTURE_TOOL,
            "internalToolName=" + INTERNAL_CAPTURE_TOOL,
            "firstToolArguments=reference:string|required|closed",
            "finalPayload=content:string,evidenceRefs:string[]|required|closed",
            "store=false",
            "parallelToolCalls=false",
            "serviceTier=default",
            "firstToolChoice=required:capture_read",
            "secondToolChoice=none",
            "include=reasoning.encrypted_content",
            "promptCachePolicy="
                + (requiresExplicitCacheOnly(modelRequested)
                    ? "EXPLICIT_ONLY"
                    : "PROVIDER_DEFAULT"));
    return IntegrityHashes.utf8ContentHash(material);
  }

  /**
   * Computes the reviewed default-codec JSON body hash for the first request
   * without constructing a client or model session.
   *
   * <p>An execution receipt may treat this as the exact transport body
   * identity only when the SDK client is a {@link ReviewedOpenAiClient}.
   */
  public static String firstRequestFingerprint(
      ModelExecutionProfile profile, TaskEnvelope task) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(task, "task");
    profile.requireTaskBinding(task);
    return ReviewedOpenAiClient.reviewedRequestBodyHash(
        firstRequest(profile, List.of(initialInput(task)))._body());
  }

  private final ModelExecutionProfile profile;
  private final OpenAIClient client;
  private final RequestHasher requestHasher;
  private final ProviderInvocationObserver
      providerInvocationObserver;
  private final ProviderAttributionObserver providerAttributionObserver;
  private final ReviewedOpenAiClient exactResponseClient;
  private final String exactExecutionBindingHash;
  private final ExactProviderAttributionObserver
      exactProviderAttributionObserver;
  private final ExactProviderOutcomeObserver exactProviderOutcomeObserver;

  public OpenAiResponsesModel(
      AgentExecutionProfile profile, OpenAIClient client) {
    this((ModelExecutionProfile) profile, client);
  }

  public OpenAiResponsesModel(
      ModelExecutionProfile profile, OpenAIClient client) {
    this(
        profile,
        client,
        request ->
            requestHash(request, ObjectMappers.jsonMapper()),
        ignoredInvocation -> {},
        (ignoredModel, ignoredUsage) -> {});
  }

  /**
   * Creates a model route with a local observer invoked immediately before
   * each SDK Responses create call.
   *
   * <p>The observer is for bounded Harness accounting only. It does not prove
   * that bytes reached the provider; the production client disables SDK
   * retries, while loopback tests independently count received HTTP requests.
   */
  public OpenAiResponsesModel(
      AgentExecutionProfile profile,
      OpenAIClient client,
      Runnable providerInvocationObserver) {
    this(
        (ModelExecutionProfile) profile,
        client,
        providerInvocationObserver);
  }

  public OpenAiResponsesModel(
      ModelExecutionProfile profile,
      OpenAIClient client,
      Runnable providerInvocationObserver) {
    this(
        profile,
        client,
        request ->
            requestHash(request, ObjectMappers.jsonMapper()),
        ignoredInvocation ->
            Objects.requireNonNull(
                    providerInvocationObserver,
                    "providerInvocationObserver")
                .run(),
        (ignoredModel, ignoredUsage) -> {});
  }

  /**
   * Creates a model route whose observer receives the exact reviewed-client
   * request hash immediately before each SDK call.
   */
  public OpenAiResponsesModel(
      ModelExecutionProfile profile,
      ReviewedOpenAiClient client,
      ProviderInvocationObserver providerInvocationObserver) {
    this(
        profile,
        Objects.requireNonNull(client, "client").client(),
        request -> client.requestBodyHash(request._body()),
        providerInvocationObserver,
        (ignoredModel, ignoredUsage) -> {});
  }

  /**
   * Creates a model route with separate observers for SDK invocation and
   * successful model/usage attribution.
   */
  public OpenAiResponsesModel(
      AgentExecutionProfile profile,
      OpenAIClient client,
      Runnable providerInvocationObserver,
      ProviderAttributionObserver providerAttributionObserver) {
    this(
        (ModelExecutionProfile) profile,
        client,
        providerInvocationObserver,
        providerAttributionObserver);
  }

  public OpenAiResponsesModel(
      ModelExecutionProfile profile,
      OpenAIClient client,
      Runnable providerInvocationObserver,
      ProviderAttributionObserver providerAttributionObserver) {
    this(
        profile,
        client,
        request ->
            requestHash(request, ObjectMappers.jsonMapper()),
        ignoredInvocation ->
            Objects.requireNonNull(
                    providerInvocationObserver,
                    "providerInvocationObserver")
                .run(),
        providerAttributionObserver);
  }

  public OpenAiResponsesModel(
      ModelExecutionProfile profile,
      ReviewedOpenAiClient client,
      ProviderInvocationObserver providerInvocationObserver,
      ProviderAttributionObserver providerAttributionObserver) {
    this(
        profile,
        Objects.requireNonNull(client, "client").client(),
        request -> client.requestBodyHash(request._body()),
        providerInvocationObserver,
        providerAttributionObserver);
  }

  /**
   * Creates the exact reviewed-client route whose response observer receives
   * only a raw-body hash and the complete validated attribution split.
   */
  public static OpenAiResponsesModel withExactResponseAttribution(
      ModelExecutionProfile profile,
      ReviewedOpenAiClient client,
      ProviderInvocationObserver providerInvocationObserver,
      ExactProviderAttributionObserver
          exactProviderAttributionObserver) {
    Objects.requireNonNull(client, "client");
    return new OpenAiResponsesModel(
        profile,
        client.client(),
        request -> client.requestBodyHash(request._body()),
        providerInvocationObserver,
        (ignoredModel, ignoredUsage) -> {},
        client,
        null,
        exactProviderAttributionObserver,
        ignoredOutcome -> {});
  }

  /**
   * Creates the exact route whose bounded outcome observer runs only after
   * strict attribution and decision parsing have both completed.
   */
  public static OpenAiResponsesModel withExactResponseOutcome(
      ModelExecutionProfile profile,
      ReviewedOpenAiClient client,
      String executionBindingHash,
      ProviderInvocationObserver providerInvocationObserver,
      ExactProviderOutcomeObserver exactProviderOutcomeObserver) {
    Objects.requireNonNull(client, "client");
    requireExecutionBindingHash(executionBindingHash);
    return new OpenAiResponsesModel(
        profile,
        client.client(),
        request -> client.requestBodyHash(request._body()),
        providerInvocationObserver,
        (ignoredModel, ignoredUsage) -> {},
        client,
        executionBindingHash,
        ignoredAttribution -> {},
        exactProviderOutcomeObserver);
  }

  private OpenAiResponsesModel(
      ModelExecutionProfile profile,
      OpenAIClient client,
      RequestHasher requestHasher,
      ProviderInvocationObserver providerInvocationObserver,
      ProviderAttributionObserver providerAttributionObserver) {
    this(
        profile,
        client,
        requestHasher,
        providerInvocationObserver,
        providerAttributionObserver,
        null,
        null,
        ignoredAttribution -> {},
        ignoredOutcome -> {});
  }

  private OpenAiResponsesModel(
      ModelExecutionProfile profile,
      OpenAIClient client,
      RequestHasher requestHasher,
      ProviderInvocationObserver providerInvocationObserver,
      ProviderAttributionObserver providerAttributionObserver,
      ReviewedOpenAiClient exactResponseClient,
      String exactExecutionBindingHash,
      ExactProviderAttributionObserver
          exactProviderAttributionObserver,
      ExactProviderOutcomeObserver exactProviderOutcomeObserver) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.client = Objects.requireNonNull(client, "client");
    this.requestHasher =
        Objects.requireNonNull(requestHasher, "requestHasher");
    this.providerInvocationObserver =
        Objects.requireNonNull(
            providerInvocationObserver,
            "providerInvocationObserver");
    this.providerAttributionObserver =
        Objects.requireNonNull(
            providerAttributionObserver,
            "providerAttributionObserver");
    this.exactResponseClient = exactResponseClient;
    this.exactExecutionBindingHash = exactExecutionBindingHash;
    this.exactProviderAttributionObserver =
        Objects.requireNonNull(
            exactProviderAttributionObserver,
            "exactProviderAttributionObserver");
    this.exactProviderOutcomeObserver =
        Objects.requireNonNull(
            exactProviderOutcomeObserver,
            "exactProviderOutcomeObserver");
    if (!profile.modelBound()
        || !PROVIDER.equals(profile.modelProvider())
        || !PROTOCOL_VERSION.equals(profile.modelAdapterVersion())) {
      throw new IllegalArgumentException(
          "OpenAI Responses requires its exact model-bound execution profile");
    }
  }

  private static void requireExecutionBindingHash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          "executionBindingHash must be a lowercase SHA-256 digest");
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
      this.history.add(initialInput(task));
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
        ResponseCreateParams request = firstRequest();
        ProviderInvocation invocation =
            providerInvocation(1, request);
        ObservedResponse observed =
            invokeProvider(
                invocation,
                () -> createResponse(request, requestOptions(context)));
        Response response = observed.response();
        Attribution attribution = attribution(invocation, observed);
        notifyAttribution(attribution);
        ModelStep outcome;
        if (!acceptAttribution(attribution)) {
          outcome = failed(
              attribution,
              AgentModelFailure.Code.ATTRIBUTION_MISMATCH.failureReason());
        } else if (!attribution.withinReviewedLimits()) {
          outcome = failed(attribution, USAGE_LIMIT_EXCEEDED);
        } else {
          outcome = parseToolCall(response, attribution);
        }
        notifyOutcome(attribution, outcome);
        if (outcome.decision() instanceof ToolCall) {
          step = 1;
        }
        return outcome;
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
        ResponseCreateParams request =
            secondRequest().rawParams();
        ProviderInvocation invocation =
            providerInvocation(2, request);
        ObservedResponse observed =
            invokeProvider(
                invocation,
                () -> createResponse(request, requestOptions(context)));
        Response response = observed.response();
        Attribution attribution = attribution(invocation, observed);
        notifyAttribution(attribution);
        ModelStep outcome;
        if (!acceptAttribution(attribution)) {
          outcome = failed(
              attribution,
              AgentModelFailure.Code.ATTRIBUTION_MISMATCH.failureReason());
        } else if (!attribution.withinReviewedLimits()) {
          outcome = failed(attribution, USAGE_LIMIT_EXCEEDED);
        } else {
          outcome = parseFinal(response, attribution);
        }
        notifyOutcome(attribution, outcome);
        return outcome;
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

    private ObservedResponse createResponse(
        ResponseCreateParams request, RequestOptions options) {
      if (exactResponseClient == null) {
        return new ObservedResponse(
            client.responses().create(request, options), null);
      }
      ReviewedOpenAiClient.ReviewedResponse reviewed =
          exactResponseClient.createReviewedResponse(request, options);
      return new ObservedResponse(
          reviewed.response(), reviewed.responseHash());
    }

    private void notifyAttribution(Attribution attribution) {
      providerAttributionObserver.attributed(
          attribution.resolvedModel(), attribution.usage());
      if (attribution.exactReceipt() != null) {
        exactProviderAttributionObserver.attributed(
            attribution.exactReceipt());
      }
    }

    private void notifyOutcome(
        Attribution attribution, ModelStep outcome) {
      Objects.requireNonNull(outcome, "outcome");
      if (attribution.exactReceipt() == null
          || exactExecutionBindingHash == null) {
        return;
      }
      ProviderOutcomeKind kind;
      String failureReason = null;
      if (outcome.decision() instanceof ToolCall) {
        kind = ProviderOutcomeKind.TOOL_CALL;
      } else if (outcome.decision() instanceof FinalDraft) {
        kind = ProviderOutcomeKind.STRUCTURED_FINAL;
      } else if (outcome.decision() instanceof Failed failed) {
        kind = ProviderOutcomeKind.FAILED;
        failureReason = failed.failureReason();
      } else {
        throw new IllegalStateException("unreviewed provider outcome kind");
      }
      exactProviderOutcomeObserver.observed(
          new ProviderOutcomeReceipt(
              attribution.exactReceipt(),
              exactExecutionBindingHash,
              kind,
              profile.pricing().fingerprint(),
              reviewedDecisionHash(
                  attribution.exactReceipt(), outcome, kind, failureReason),
              failureReason));
    }

    private String reviewedDecisionHash(
        ProviderAttributionReceipt receipt,
        ModelStep outcome,
        ProviderOutcomeKind kind,
        String failureReason) {
      Map<String, Object> material = new LinkedHashMap<>();
      material.put("protocolVersion", PROTOCOL_VERSION);
      material.put("executionBindingHash", exactExecutionBindingHash);
      material.put("requestOrdinal", receipt.invocation().requestOrdinal());
      material.put("requestHash", receipt.invocation().requestHash());
      material.put("responseHash", receipt.responseHash());
      material.put("kind", kind.name());
      if (outcome.decision() instanceof FinalDraft finalDraft) {
        material.put(
            "contentHash",
            IntegrityHashes.utf8ContentHash(finalDraft.content()));
        material.put(
            "evidenceRefHashes",
            finalDraft.evidenceRefs().stream()
                .map(IntegrityHashes::utf8ContentHash)
                .toList());
      } else if (outcome.decision() instanceof Failed) {
        material.put("failureCode", failureReason);
      } else if (outcome.decision() instanceof ToolCall) {
        material.put("eligibleForProviderValidation", false);
      } else {
        throw new IllegalStateException("unreviewed provider outcome kind");
      }
      return CanonicalIntegrity.hash(REVIEWED_DECISION_DOMAIN, material);
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
      return OpenAiResponsesModel.firstRequest(
          profile, List.copyOf(history));
    }

    private StructuredResponseCreateParams<FinalDraftPayload>
        secondRequest() {
      StructuredResponseCreateParams.Builder<FinalDraftPayload> builder =
          ResponseCreateParams.builder()
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
          .text(FinalDraftPayload.class);
      applyReviewedCachePolicy(builder);
      return builder.build();
    }

    private void applyReviewedCachePolicy(
        ResponseCreateParams.Builder builder) {
      OpenAiResponsesModel.applyReviewedCachePolicy(
          builder, profile.modelRequested());
    }

    private void applyReviewedCachePolicy(
        StructuredResponseCreateParams.Builder<?> builder) {
      if (requiresExplicitCacheOnly(profile.modelRequested())) {
        builder.promptCacheOptions(EXPLICIT_CACHE_ONLY);
      }
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
            new ToolCall(
                INTERNAL_CAPTURE_TOOL,
                ToolArguments.fromJson(functionCall.arguments())),
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
      return functionCall.arguments() != null;
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

  private static ResponseInputItem initialInput(
      TaskEnvelope task) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder()
            .role(EasyInputMessage.Role.USER)
            .content(
                "Task intent:\n"
                    + task.intent()
                    + "\nDeclared capture reference:\n"
                    + task.inputRefs().getFirst())
            .build());
  }

  private static ResponseCreateParams firstRequest(
      ModelExecutionProfile profile,
      List<ResponseInputItem> input) {
    ResponseCreateParams.Builder builder =
        ResponseCreateParams.builder()
            .model(profile.modelRequested())
            .instructions(INSTRUCTIONS)
            .inputOfResponse(input)
            .store(false)
            .parallelToolCalls(false)
            .serviceTier(ResponseCreateParams.ServiceTier.DEFAULT)
            .maxOutputTokens(profile.maxOutputTokensPerStep())
            .addInclude(
                ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
            .addTool(CaptureReadArguments.class)
            .toolChoice(
                ToolChoiceFunction.builder()
                    .name(CAPTURE_TOOL)
                    .build());
    applyReviewedCachePolicy(
        builder, profile.modelRequested());
    return builder.build();
  }

  private static void applyReviewedCachePolicy(
      ResponseCreateParams.Builder builder,
      String modelRequested) {
    if (requiresExplicitCacheOnly(modelRequested)) {
      builder.promptCacheOptions(EXPLICIT_CACHE_ONLY);
    }
  }

  private Attribution attribution(
      ProviderInvocation invocation, ObservedResponse observed) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(observed, "observed");
    Response response = observed.response();
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
                  profile.modelRequested() + "-"),
          observed.responseHash() == null
              ? null
              : new ProviderAttributionReceipt(
                  invocation,
                  observed.responseHash(),
                  resolvedModel,
                  inputTokens,
                  cachedInputTokens,
                  outputTokens,
                  reasoningTokens,
                  totalTokens,
                  cost));
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

  private static boolean requiresExplicitCacheOnly(
      String modelRequested) {
    return "gpt-5.6".equals(modelRequested)
        || modelRequested.startsWith("gpt-5.6-");
  }

  private static ModelStep failed(
      Attribution attribution, String failureReason) {
    return new ModelStep(
        new Failed(failureReason),
        attribution.resolvedModel(),
        attribution.usage());
  }

  private ProviderInvocation providerInvocation(
      int requestOrdinal, ResponseCreateParams request) {
    try {
      return new ProviderInvocation(
          requestOrdinal,
          requestHasher.hash(request),
          profile.modelRequested());
    } catch (Exception serializationFailure) {
      throw new AgentModelFailure(
          AgentModelFailure.Code.REQUEST_REJECTED);
    }
  }

  private static String requestHash(
      ResponseCreateParams request, ObjectMapper requestMapper) {
    try {
      String serialized =
          requestMapper.writeValueAsString(request._body());
      return IntegrityHashes.utf8ContentHash(serialized);
    } catch (Exception serializationFailure) {
      throw new IllegalStateException(
          "OpenAI request serialization failed",
          serializationFailure);
    }
  }

  private <T> T invokeProvider(
      ProviderInvocation invocation, Supplier<T> call) {
    try {
      providerInvocationObserver.beforeInvocation(invocation);
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

  @FunctionalInterface
  private interface RequestHasher {

    String hash(ResponseCreateParams request);
  }

  private record Attribution(
      String resolvedModel,
      ModelUsage usage,
      boolean withinReviewedLimits,
      boolean matchesRequestedModel,
      ProviderAttributionReceipt exactReceipt) {}

  private record ObservedResponse(
      Response response, String responseHash) {

    private ObservedResponse {
      Objects.requireNonNull(response, "response");
      if (responseHash != null
          && !responseHash.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "responseHash must be a lowercase SHA-256 digest");
      }
    }
  }

  /**
   * Safe pre-call identity of the exact JSON request produced by the
   * reviewed SDK client serializer.
   *
   * <p>The hash binds the full request body without exposing prompt, Tool
   * result, header or credential bytes. It does not prove that the provider
   * received the request.
   */
  public record ProviderInvocation(
      int requestOrdinal,
      String requestHash,
      String modelRequested) {

    public ProviderInvocation {
      if (requestOrdinal < 1 || requestOrdinal > 128) {
        throw new IllegalArgumentException(
            "requestOrdinal is outside the reviewed domain");
      }
      if (requestHash == null
          || !requestHash.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "requestHash must be a lowercase SHA-256 digest");
      }
      if (modelRequested == null
          || !modelRequested.matches(
              "[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")) {
        throw new IllegalArgumentException(
            "modelRequested is outside the safe model domain");
      }
    }
  }

  /**
   * Bounded exact response receipt for a single reviewed SDK invocation.
   *
   * <p>The response hash is SHA-256 over the complete content-decoded HTTP
   * entity bytes consumed by the strict JSON parser. Status, headers, raw
   * response bytes, response content, response id and credentials are never
   * exposed through this record.
   */
  public record ProviderAttributionReceipt(
      ProviderInvocation invocation,
      String responseHash,
      String modelResolved,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningOutputTokens,
      long totalTokens,
      BigDecimal observedCostUsd) {

    public ProviderAttributionReceipt {
      Objects.requireNonNull(invocation, "invocation");
      if (responseHash == null
          || !responseHash.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "responseHash must be a lowercase SHA-256 digest");
      }
      if (modelResolved == null
          || !modelResolved.matches(
              "[A-Za-z0-9][A-Za-z0-9._~:/-]{0,511}")) {
        throw new IllegalArgumentException(
            "modelResolved is outside the safe model domain");
      }
      ContractValueDomains.requireSafeCount(
          inputTokens, "inputTokens");
      ContractValueDomains.requireSafeCount(
          cachedInputTokens, "cachedInputTokens");
      ContractValueDomains.requireSafeCount(
          outputTokens, "outputTokens");
      ContractValueDomains.requireSafeCount(
          reasoningOutputTokens, "reasoningOutputTokens");
      ContractValueDomains.requireSafeCount(
          totalTokens, "totalTokens");
      if (cachedInputTokens > inputTokens
          || reasoningOutputTokens > outputTokens
          || Math.addExact(inputTokens, outputTokens) != totalTokens) {
        throw new IllegalArgumentException(
            "provider attribution token details are inconsistent");
      }
      observedCostUsd =
          ContractValueDomains.requireUsd(
              observedCostUsd, "observedCostUsd");
    }
  }

  public enum ProviderOutcomeKind {
    TOOL_CALL,
    STRUCTURED_FINAL,
    FAILED
  }

  /** Bounded exact transport receipt paired with its parsed decision kind. */
  public static final class ProviderOutcomeReceipt {

    private final ProviderAttributionReceipt attribution;
    private final String executionBindingHash;
    private final ProviderOutcomeKind kind;
    private final String pricingProfileFingerprint;
    private final String decisionHash;
    private final String failureReason;

    private ProviderOutcomeReceipt(
        ProviderAttributionReceipt attribution,
        String executionBindingHash,
        ProviderOutcomeKind kind,
        String pricingProfileFingerprint,
        String decisionHash,
        String failureReason) {
      this.attribution =
          Objects.requireNonNull(attribution, "attribution");
      requireExecutionBindingHash(executionBindingHash);
      this.executionBindingHash = executionBindingHash;
      this.kind = Objects.requireNonNull(kind, "kind");
      if (pricingProfileFingerprint == null
          || !pricingProfileFingerprint.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "provider outcome pricing fingerprint is invalid");
      }
      this.pricingProfileFingerprint = pricingProfileFingerprint;
      if (decisionHash == null
          || !decisionHash.matches("[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "provider outcome decision hash is invalid");
      }
      this.decisionHash = decisionHash;
      if ((kind == ProviderOutcomeKind.FAILED)
          != (failureReason != null && !failureReason.isBlank())) {
        throw new IllegalArgumentException(
            "provider outcome failure reason is inconsistent");
      }
      this.failureReason = failureReason;
    }

    public ProviderAttributionReceipt attribution() {
      return attribution;
    }

    String executionBindingHash() {
      return executionBindingHash;
    }

    public ProviderOutcomeKind kind() {
      return kind;
    }

    public String decisionHash() {
      return decisionHash;
    }

    public String pricingProfileFingerprint() {
      return pricingProfileFingerprint;
    }

    public String failureReason() {
      return failureReason;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ProviderOutcomeReceipt that)) {
        return false;
      }
      return attribution.equals(that.attribution)
          && executionBindingHash.equals(that.executionBindingHash)
          && kind == that.kind
          && pricingProfileFingerprint.equals(
              that.pricingProfileFingerprint)
          && decisionHash.equals(that.decisionHash)
          && Objects.equals(failureReason, that.failureReason);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          attribution,
          executionBindingHash,
          kind,
          pricingProfileFingerprint,
          decisionHash,
          failureReason);
    }

    @Override
    public String toString() {
      return "ProviderOutcomeReceipt[kind="
          + kind
          + ", decisionHash="
          + decisionHash
          + "]";
    }
  }

  /**
   * Receives only a bounded request identity immediately before the SDK
   * create call. It does not receive raw JSON, headers or credentials.
   */
  @FunctionalInterface
  public interface ProviderInvocationObserver {

    void beforeInvocation(ProviderInvocation invocation);
  }

  /**
   * Receives only provider identity and metering data that passed strict
   * response attribution parsing. Prompt, response body and credentials never
   * cross this observer boundary.
   */
  @FunctionalInterface
  public interface ProviderAttributionObserver {

    void attributed(String resolvedModel, ModelUsage usage);
  }

  /** Receives one safe exact receipt after strict response attribution. */
  @FunctionalInterface
  public interface ExactProviderAttributionObserver {

    void attributed(ProviderAttributionReceipt attribution);
  }

  /** Receives one exact receipt only after its decision has been parsed. */
  @FunctionalInterface
  public interface ExactProviderOutcomeObserver {

    void observed(ProviderOutcomeReceipt outcome);
  }
}
