package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact Pack008 policy for a model-bound, depth-one read-only Worker.
 *
 * <p>The Conductor remains an offline Model with no direct Tool authority.
 * This profile owns the only provider route and creates a PUBLIC Task 1.1
 * child with exactly one {@code capture.read} Tool.
 */
public record ModelBoundReadOnlyWorkerExecutionProfile(
    String id,
    String registryVersion,
    String workerName,
    int maxModelSteps,
    int maxToolCalls,
    long deadlineMs,
    BigDecimal budgetUsd,
    long maxInputTokensPerStep,
    long maxOutputTokensPerStep,
    PricingProfile pricing,
    String modelAdapterVersion,
    String agentVersion,
    String verifierVersion,
    String harnessVersion,
    HarnessExperiment experiment,
    String expectedPolicyVersion,
    String expectedStateVersion,
    String expectedContextPolicyVersion,
    String expectedToolRegistryVersion,
    String parentConductorDecisionSurfaceFingerprint,
    String environmentSnapshotRef)
    implements ReadOnlyWorkerProfile, ModelExecutionProfile {

  public static final String REGISTRY_VERSION = "agent-workers-v2";
  private static final String PROFILE_ID =
      "article-draft-read-model-worker-v1";
  private static final String POLICY_VERSION =
      "synthetic-worker-model-egress-policy-v1";
  private static final String STATE_VERSION = "stage2-s4-eval";
  private static final String CONTEXT_POLICY_VERSION = "ref-only-v1";
  private static final String PARENT_TOOL_REGISTRY_VERSION =
      "agent-tools-none-v1";
  private static final String TOOL_REGISTRY_VERSION = "agent-tools-v2";
  private static final String TASK_SCHEMA_VERSION = "1.1";
  private static final String TASK_KIND = "PROPOSE_ARTICLE_DRAFT";
  private static final String TASK_MODALITY = "text";
  private static final String TASK_LATENCY_CLASS = "INTERACTIVE";
  private static final String REQUIRED_TOOL = "capture.read";
  private static final String ACCEPTANCE_CHECK =
      "proposal cites the delegated Capture";
  private static final String RETURN_CONTROL_WHEN =
      "typed Worker Result or non-success";
  private static final String CAPTURE_REF_PATTERN =
      "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}";

  public ModelBoundReadOnlyWorkerExecutionProfile {
    ContractText.require(id, "Worker profile id", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        registryVersion, "Worker registry version", ContractText.MAX_NAME_LENGTH);
    if (workerName == null
        || !workerName.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("workerName is invalid");
    }
    ContractValueDomains.requireExecutionLimit(maxModelSteps, "maxModelSteps");
    ContractValueDomains.requireExecutionLimit(maxToolCalls, "maxToolCalls");
    ContractValueDomains.requireDuration(deadlineMs, "deadlineMs", false);
    ContractValueDomains.requireUsd(budgetUsd, "budgetUsd");
    ContractValueDomains.requireSafeCount(
        maxInputTokensPerStep, "maxInputTokensPerStep");
    ContractValueDomains.requireSafeCount(
        maxOutputTokensPerStep, "maxOutputTokensPerStep");
    pricing = Objects.requireNonNull(pricing, "pricing");
    ContractText.require(
        modelAdapterVersion,
        "modelAdapterVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(agentVersion, "agentVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        verifierVersion, "verifierVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        harnessVersion, "harnessVersion", ContractText.MAX_NAME_LENGTH);
    Objects.requireNonNull(experiment, "experiment");
    ContractText.require(
        expectedPolicyVersion,
        "expectedPolicyVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        expectedStateVersion,
        "expectedStateVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        expectedContextPolicyVersion,
        "expectedContextPolicyVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        expectedToolRegistryVersion,
        "expectedToolRegistryVersion",
        ContractText.MAX_NAME_LENGTH);
    if (parentConductorDecisionSurfaceFingerprint == null
        || !parentConductorDecisionSurfaceFingerprint.matches(
            "[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          "Pack008 requires a content-addressed parent Conductor surface");
    }
    if (environmentSnapshotRef == null
        || !environmentSnapshotRef.matches(
            "environment://sha256:[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          "model-bound Worker requires a content-addressed environment");
    }
    if (maxInputTokensPerStep < 1
        || maxInputTokensPerStep
            > AgentExecutionProfile.MAX_REVIEWED_INPUT_TOKENS_PER_STEP
        || maxOutputTokensPerStep < 1) {
      throw new IllegalArgumentException(
          "model token bounds must be positive and within the reviewed input ceiling");
    }
    BigDecimal reservation =
        pricing.reserveCostUsd(
            maxModelSteps,
            maxInputTokensPerStep,
            maxOutputTokensPerStep);
    if (budgetUsd.compareTo(reservation) != 0) {
      throw new IllegalArgumentException(
          "Pack008 Worker budget must equal the full model-run reservation");
    }
  }

  public static ModelBoundReadOnlyWorkerExecutionProfile pack008OpenAiV1(
      PricingProfile pricing,
      String modelAdapterVersion,
      String parentConductorDecisionSurfaceFingerprint,
      String environmentSnapshotRef,
      HarnessExperiment experiment,
      long maxInputTokensPerStep,
      long maxOutputTokensPerStep,
      BigDecimal budgetUsd) {
    return new ModelBoundReadOnlyWorkerExecutionProfile(
        PROFILE_ID,
        REGISTRY_VERSION,
        ReadOnlyWorkerExecutionProfile.WORKER_NAME,
        2,
        1,
        30_000,
        budgetUsd,
        maxInputTokensPerStep,
        maxOutputTokensPerStep,
        pricing,
        modelAdapterVersion,
        "agent-draft-worker-v2",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        experiment,
        POLICY_VERSION,
        STATE_VERSION,
        CONTEXT_POLICY_VERSION,
        TOOL_REGISTRY_VERSION,
        parentConductorDecisionSurfaceFingerprint,
        environmentSnapshotRef);
  }

  @Override
  public String taskSchemaVersion() {
    return TASK_SCHEMA_VERSION;
  }

  @Override
  public boolean modelBound() {
    return true;
  }

  @Override
  public boolean requiresExactParentUsageAggregation() {
    return true;
  }

  @Override
  public String modelProvider() {
    return pricing.provider();
  }

  @Override
  public String modelRequested() {
    return pricing.modelRequested();
  }

  public String pricingProfile() {
    return pricing.id();
  }

  @Override
  public String parentToolRegistryVersion() {
    return PARENT_TOOL_REGISTRY_VERSION;
  }

  @Override
  public String validatePreparation(
      TaskEnvelope parent,
      WorkerHandoffRequest request,
      AgentWorkerRuntime.ExecutionWindow window) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(window, "window");
    String parentRejection = parentAuthorityRejection(parent);
    if (parentRejection != null) {
      return parentRejection;
    }
    if (!workerName.equals(request.workerName())
        || !request.inputRefs().equals(parent.inputRefs())
        || request.inputRefs().size() != 1
        || !request
            .inputRefs()
            .getFirst()
            .matches(CAPTURE_REF_PATTERN)) {
      return "HANDOFF_NOT_ALLOWED";
    }
    if (window.remainingDeadlineMs() > parent.deadlineMs()
        || window.remainingBudgetUsd().compareTo(parent.budgetUsd()) > 0
        || window.remainingDeadlineMs() < deadlineMs
        || window.remainingBudgetUsd().compareTo(budgetUsd) < 0) {
      return "HANDOFF_AUTHORITY_ESCALATION";
    }
    return null;
  }

  @Override
  public void requireParentBinding(TaskEnvelope parent) {
    Objects.requireNonNull(parent, "parent");
    String rejection = parentAuthorityRejection(parent);
    if (rejection != null) {
      throw new IllegalArgumentException(
          "parent Task violates the Pack008 Worker profile: " + rejection);
    }
  }

  @Override
  public void requireParentProfileBinding(
      AgentExecutionProfile parentProfile) {
    Objects.requireNonNull(parentProfile, "parentProfile");
    if (!parentExecutionProfile().equals(parentProfile)) {
      throw new IllegalArgumentException(
          "parent execution profile does not bind the Pack008 Worker");
    }
  }

  @Override
  public AgentExecutionProfile parentExecutionProfile() {
    return AgentExecutionProfile.readOnlyWorkerModelV1(this);
  }

  @Override
  public TaskEnvelope newChildTask(
      TaskEnvelope parent,
      WorkerHandoffRequest request,
      AgentWorkerRuntime.ExecutionWindow window,
      String childTaskId) {
    String rejection = validatePreparation(parent, request, window);
    if (rejection != null) {
      throw new IllegalArgumentException(rejection);
    }
    TaskEnvelope child =
        new TaskEnvelope(
            taskSchemaVersion(),
            childTaskId,
            parent.id(),
            parent.principalRef(),
            List.of(parent.id()),
            TASK_KIND,
            request.intent(),
            request.inputRefs(),
            List.of(),
            parent.modalities(),
            DataClass.PUBLIC,
            RiskLevel.EXTERNAL,
            parent.latencyClass(),
            List.of(REQUIRED_TOOL),
            ReadOnlyWorkerExecutionProfile.OUTPUT_SCHEMA,
            List.of(ACCEPTANCE_CHECK),
            false,
            maxModelSteps,
            maxToolCalls,
            deadlineMs,
            budgetUsd,
            modelProvider(),
            modelRequested(),
            pricingProfile(),
            taskIdempotencyKey(childTaskId),
            expectedPolicyVersion,
            expectedStateVersion,
            expectedContextPolicyVersion,
            childToolRegistryVersion(),
            environmentSnapshotRef,
            List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
            parent.unresolvedDecisions(),
            RETURN_CONTROL_WHEN);
    requireChildBinding(parent, child);
    return child;
  }

  @Override
  public void requireTaskBinding(TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    if (!taskSchemaVersion().equals(task.schemaVersion())
        || task.parentId() == null
        || !task.delegationChain().equals(List.of(task.parentId()))
        || !TASK_KIND.equals(task.kind())
        || task.dataClass() != DataClass.PUBLIC
        || task.risk() != RiskLevel.EXTERNAL
        || task.allowParallel()
        || task.inputRefs().size() != 1
        || !task
            .inputRefs()
            .getFirst()
            .matches(CAPTURE_REF_PATTERN)
        || !task.evidenceRefs().isEmpty()
        || !task.modalities().equals(List.of(TASK_MODALITY))
        || !TASK_LATENCY_CLASS.equals(task.latencyClass())
        || !task.requiredTools().equals(List.of(REQUIRED_TOOL))
        || !ReadOnlyWorkerExecutionProfile.OUTPUT_SCHEMA.equals(
            task.outputSchema())
        || !task.acceptanceChecks().equals(
            List.of(ACCEPTANCE_CHECK))
        || task.maxModelSteps() != maxModelSteps
        || task.maxToolCalls() != maxToolCalls
        || task.deadlineMs() != deadlineMs
        || task.budgetUsd().compareTo(budgetUsd) != 0
        || !modelProvider().equals(task.modelProvider())
        || !modelRequested().equals(task.modelRequested())
        || !pricingProfile().equals(task.pricingProfile())
        || !taskIdempotencyKey(task.id()).equals(task.idempotencyKey())
        || !expectedPolicyVersion.equals(task.policyVersion())
        || !expectedStateVersion.equals(task.stateVersion())
        || !expectedContextPolicyVersion.equals(task.contextPolicyVersion())
        || !childToolRegistryVersion().equals(task.toolRegistryVersion())
        || !environmentSnapshotRef.equals(task.environmentSnapshotRef())
        || !task.capabilityRefs().equals(
            List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY))
        || !task.unresolvedDecisions().isEmpty()
        || !RETURN_CONTROL_WHEN.equals(task.returnControlWhen())) {
      throw new IllegalArgumentException(
          "TaskEnvelope does not match the Pack008 model-bound Worker profile");
    }
  }

  @Override
  public void requireChildBinding(
      TaskEnvelope parent, TaskEnvelope child) {
    requireParentBinding(parent);
    requireTaskBinding(child);
    if (parent.id().equals(child.id())
        || !parent.principalRef().equals(child.principalRef())
        || !parent.id().equals(child.parentId())
        || !child.delegationChain().equals(List.of(parent.id()))
        || !child.inputRefs().equals(parent.inputRefs())
        || !child.modalities().equals(parent.modalities())
        || !child.latencyClass().equals(parent.latencyClass())
        || child.budgetUsd().compareTo(parent.budgetUsd()) > 0
        || !child.unresolvedDecisions().equals(parent.unresolvedDecisions())) {
      throw new IllegalArgumentException(
          "child Task violates the Pack008 parent authority");
    }
  }

  @Override
  public String taskIdempotencyKey(String taskId) {
    if (taskId == null
        || !taskId.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException("taskId has an invalid identifier");
    }
    String material =
        "agent-task-idempotency-v1\n"
            + "executionProfileFingerprint="
            + fingerprint()
            + "\n"
            + "taskId="
            + taskId;
    return "agent-task-" + ContentHashes.sha256(material);
  }

  @Override
  public Map<String, String> componentVersions() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("agent", agentVersion);
    values.put("verifier", verifierVersion);
    values.put("trace-integrity", IntegrityHashes.PROFILE);
    values.put("worker-registry", registryVersion);
    values.put("worker-profile-fingerprint", fingerprint());
    values.put("model-adapter", modelAdapterVersion);
    values.put("execution-profile", id);
    values.put("execution-profile-fingerprint", fingerprint());
    values.put("pricing-profile-fingerprint", pricing.fingerprint());
    return Map.copyOf(values);
  }

  @Override
  public Map<String, String> parentComponentVersions() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("worker-registry", registryVersion);
    values.put("worker-profile-fingerprint", fingerprint());
    values.put(
        "conductor-decision-surface-fingerprint",
        parentConductorDecisionSurfaceFingerprint);
    return Map.copyOf(values);
  }

  @Override
  public Map<String, String> modelComponentVersions() {
    return componentVersions();
  }

  @Override
  public String fingerprint() {
    StringBuilder material =
        new StringBuilder("read-only-worker-profile-fingerprint-v2");
    append(material, "id", id);
    append(material, "registryVersion", registryVersion);
    append(material, "workerName", workerName);
    append(material, "taskSchemaVersion", taskSchemaVersion());
    append(material, "taskKind", TASK_KIND);
    append(material, "taskModality", TASK_MODALITY);
    append(material, "taskLatencyClass", TASK_LATENCY_CLASS);
    append(material, "taskRisk", RiskLevel.EXTERNAL.name());
    append(material, "taskDataClass", DataClass.PUBLIC.name());
    append(material, "inputRefPattern", CAPTURE_REF_PATTERN);
    append(material, "evidenceRefs", "empty");
    append(material, "requiredTool[0]", REQUIRED_TOOL);
    append(
        material,
        "outputSchema",
        ReadOnlyWorkerExecutionProfile.OUTPUT_SCHEMA);
    append(material, "acceptanceCheck[0]", ACCEPTANCE_CHECK);
    append(material, "allowParallel", Boolean.FALSE.toString());
    append(material, "delegationDepth", "1");
    append(material, "unresolvedDecisions", "empty");
    append(material, "returnControlWhen", RETURN_CONTROL_WHEN);
    append(material, "maxModelSteps", Integer.toString(maxModelSteps));
    append(material, "maxToolCalls", Integer.toString(maxToolCalls));
    append(material, "deadlineMs", Long.toString(deadlineMs));
    append(material, "budgetUsd", canonicalUsd(budgetUsd));
    append(
        material,
        "maxInputTokensPerStep",
        Long.toString(maxInputTokensPerStep));
    append(
        material,
        "maxOutputTokensPerStep",
        Long.toString(maxOutputTokensPerStep));
    append(material, "pricingFingerprint", pricing.fingerprint());
    append(material, "modelAdapterVersion", modelAdapterVersion);
    append(material, "agentVersion", agentVersion);
    append(material, "verifierVersion", verifierVersion);
    append(material, "harnessVersion", harnessVersion);
    append(material, "experimentArm", experiment.arm());
    append(
        material,
        "experimentRepetition",
        Integer.toString(experiment.repetition()));
    append(material, "policyVersion", expectedPolicyVersion);
    append(material, "stateVersion", expectedStateVersion);
    append(material, "contextPolicyVersion", expectedContextPolicyVersion);
    append(
        material,
        "parentToolRegistryVersion",
        parentToolRegistryVersion());
    append(
        material,
        "childToolRegistryVersion",
        childToolRegistryVersion());
    append(
        material,
        "parentConductorDecisionSurfaceFingerprint",
        parentConductorDecisionSurfaceFingerprint);
    append(material, "environmentSnapshotRef", environmentSnapshotRef);
    append(
        material,
        "capabilityRef[0]",
        AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY);
    return ContentHashes.sha256(material.toString());
  }

  private String parentAuthorityRejection(TaskEnvelope parent) {
    if (!expectedContextPolicyVersion.equals(parent.contextPolicyVersion())) {
      return "HANDOFF_CONTEXT_POLICY_DRIFT";
    }
    try {
      AgentExecutionProfile.readOnlyWorkerModelV1(this)
          .requireTaskBinding(parent);
    } catch (RuntimeException rejected) {
      return "HANDOFF_AUTHORITY_ESCALATION";
    }
    return null;
  }

  private static String canonicalUsd(BigDecimal value) {
    return value.signum() == 0
        ? "0"
        : value.stripTrailingZeros().toPlainString();
  }

  private static void append(
      StringBuilder target, String name, String value) {
    target
        .append('|')
        .append(name.length())
        .append(':')
        .append(name)
        .append('=')
        .append(value.length())
        .append(':')
        .append(value);
  }
}
