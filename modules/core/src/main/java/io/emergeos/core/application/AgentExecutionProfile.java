package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ObservedExecutionLimits;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.ContentHashes;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-owned immutable execution identity for one Agent draft route.
 *
 * <p>Request payloads never construct or override this profile. This proves structural execution
 * binding only: PUBLIC classification and a capability reference do not prove synthetic
 * provenance or operator consent. A live runner must separately bind the complete prompt-bearing
 * Task to an approved pack hash and a one-shot operator permit before reading credentials or
 * creating a client.
 */
public record AgentExecutionProfile(
    String id,
    String taskSchemaVersion,
    RiskLevel risk,
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
    String policyVersion,
    String stateVersion,
    String contextPolicyVersion,
    String toolRegistryVersion,
    String environmentSnapshotRef,
    List<String> capabilityRefs,
    DataClass requiredDataClass)
    implements ModelExecutionProfile {

  public static final String SYNTHETIC_MODEL_EGRESS_CAPABILITY =
      "capability://model-egress/synthetic-openai-v1";
  public static final String READ_ONLY_WORKER_CAPABILITY =
      ObservedExecutionLimits.READ_ONLY_WORKER_CAPABILITY;
  public static final long MAX_REVIEWED_INPUT_TOKENS_PER_STEP = 272_000;

  public AgentExecutionProfile {
    ContractText.require(id, "execution profile id", ContractText.MAX_NAME_LENGTH);
    if (!id.matches("[a-z][a-z0-9._-]{0,199}")) {
      throw new IllegalArgumentException(
          "execution profile id must be a stable lowercase slug");
    }
    if (!List.of("1.0", "1.1").contains(taskSchemaVersion)) {
      throw new IllegalArgumentException(
          "execution profile supports TaskEnvelope 1.0 and 1.1");
    }
    Objects.requireNonNull(risk, "risk");
    ContractValueDomains.requireExecutionLimit(maxModelSteps, "maxModelSteps");
    ContractValueDomains.requireExecutionLimit(maxToolCalls, "maxToolCalls");
    ContractValueDomains.requireDuration(deadlineMs, "deadlineMs", false);
    ContractValueDomains.requireUsd(budgetUsd, "budgetUsd");
    ContractValueDomains.requireSafeCount(
        maxInputTokensPerStep, "maxInputTokensPerStep");
    ContractValueDomains.requireSafeCount(
        maxOutputTokensPerStep, "maxOutputTokensPerStep");
    ContractText.require(
        agentVersion, "agentVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        verifierVersion, "verifierVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        harnessVersion, "harnessVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        policyVersion, "policyVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        stateVersion, "stateVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        contextPolicyVersion,
        "contextPolicyVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        toolRegistryVersion,
        "toolRegistryVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(
        modelAdapterVersion,
        "modelAdapterVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(
        environmentSnapshotRef, "environmentSnapshotRef");
    capabilityRefs =
        ContractText.copyStrings(capabilityRefs, "capabilityRefs");

    if ("1.0".equals(taskSchemaVersion)) {
      boolean budgetedWorkerConductor =
          capabilityRefs.equals(List.of(READ_ONLY_WORKER_CAPABILITY))
              && budgetUsd.signum() > 0;
      if (pricing != null
          || modelAdapterVersion != null
          || maxInputTokensPerStep != 0
          || maxOutputTokensPerStep != 0
          || capabilityRefs.stream()
              .anyMatch(
                  value ->
                      value.startsWith("capability://model-egress/"))) {
        throw new IllegalArgumentException(
            "TaskEnvelope 1.0 profile cannot carry a billable model route");
      }
      if (budgetedWorkerConductor) {
        if (risk != RiskLevel.EXTERNAL
            || requiredDataClass != DataClass.PUBLIC
            || experiment == null
            || environmentSnapshotRef == null
            || !environmentSnapshotRef.matches(
                "environment://sha256:[a-f0-9]{64}")) {
          throw new IllegalArgumentException(
              "a budgeted Worker conductor requires EXTERNAL PUBLIC synthetic bindings");
        }
      } else if (budgetUsd.signum() != 0 || requiredDataClass != null) {
        throw new IllegalArgumentException(
            "TaskEnvelope 1.0 budget is reserved for an exact Worker subtree");
      }
    } else {
      Objects.requireNonNull(pricing, "pricing");
      ContractText.require(
          modelAdapterVersion,
          "modelAdapterVersion",
          ContractText.MAX_NAME_LENGTH);
      if (risk != RiskLevel.EXTERNAL) {
        throw new IllegalArgumentException(
            "a model egress profile must declare EXTERNAL risk");
      }
      if (requiredDataClass != DataClass.PUBLIC) {
        throw new IllegalArgumentException(
            "a model egress profile is restricted to PUBLIC data");
      }
      if (!capabilityRefs.equals(
          List.of(SYNTHETIC_MODEL_EGRESS_CAPABILITY))) {
        throw new IllegalArgumentException(
            "a model egress profile requires the exact synthetic capability");
      }
      if (environmentSnapshotRef == null
          || !environmentSnapshotRef.matches(
              "environment://sha256:[a-f0-9]{64}")) {
        throw new IllegalArgumentException(
            "a model egress profile requires a content-addressed environment");
      }
      if (experiment == null) {
        throw new IllegalArgumentException(
            "a model egress profile requires a frozen experiment identity");
      }
      if (maxInputTokensPerStep < 1
          || maxInputTokensPerStep > MAX_REVIEWED_INPUT_TOKENS_PER_STEP
          || maxOutputTokensPerStep < 1) {
        throw new IllegalArgumentException(
            "model token bounds must be positive and within the reviewed input ceiling");
      }
      BigDecimal reservation =
          pricing.reserveCostUsd(
              maxModelSteps,
              maxInputTokensPerStep,
              maxOutputTokensPerStep);
      if (budgetUsd.compareTo(reservation) < 0) {
        throw new IllegalArgumentException(
            "budgetUsd does not cover the full model-run reservation");
      }
    }
  }

  public static AgentExecutionProfile legacyFakeV1() {
    return legacyFakeV1(2, 1, 5_000);
  }

  public static AgentExecutionProfile legacyFakeV1(
      int maxModelSteps, int maxToolCalls, long deadlineMs) {
    return new AgentExecutionProfile(
        "api-fake-agent-draft-v1",
        "1.0",
        RiskLevel.REVERSIBLE,
        maxModelSteps,
        maxToolCalls,
        deadlineMs,
        BigDecimal.ZERO,
        0,
        0,
        null,
        null,
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v1",
        null,
        "agent-draft-policy-v1",
        "stage2-s2",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        List.of(),
        null);
  }

  public static AgentExecutionProfile readOnlyWorkerFakeV1() {
    return readOnlyWorkerFakeV1("ref-only-v1");
  }

  public static AgentExecutionProfile readOnlyWorkerFakeV1(
      String contextPolicyVersion) {
    ContractText.require(
        contextPolicyVersion,
        "contextPolicyVersion",
        ContractText.MAX_NAME_LENGTH);
    return new AgentExecutionProfile(
        "api-fake-worker-agent-draft-v1",
        "1.0",
        RiskLevel.REVERSIBLE,
        2,
        1,
        5_000,
        BigDecimal.ZERO,
        0,
        0,
        null,
        null,
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v1",
        null,
        "agent-draft-policy-v1",
        "stage2-s2",
        contextPolicyVersion,
        "agent-tools-v2",
        null,
        List.of(READ_ONLY_WORKER_CAPABILITY),
        null);
  }

  /**
   * Frozen Pack008 Conductor profile.
   *
   * <p>The parent Model remains offline and has no direct Tool or provider
   * route. Its non-zero budget is subtree authority for the exact
   * model-bound child Worker.
   */
  public static AgentExecutionProfile readOnlyWorkerModelV1(
      ModelBoundReadOnlyWorkerExecutionProfile worker) {
    Objects.requireNonNull(worker, "worker");
    if (!worker.modelBound()) {
      throw new IllegalArgumentException(
          "Pack008 requires a model-bound Worker profile");
    }
    return new AgentExecutionProfile(
        "synthetic-model-worker-agent-draft-v1",
        "1.0",
        RiskLevel.EXTERNAL,
        2,
        1,
        Math.addExact(worker.deadlineMs(), 5_000),
        worker.budgetUsd(),
        0,
        0,
        null,
        null,
        "agent-draft-service-v1",
        worker.verifierVersion(),
        worker.harnessVersion(),
        worker.experiment(),
        worker.expectedPolicyVersion(),
        worker.expectedStateVersion(),
        worker.expectedContextPolicyVersion(),
        worker.parentToolRegistryVersion(),
        worker.environmentSnapshotRef(),
        List.of(READ_ONLY_WORKER_CAPABILITY),
        DataClass.PUBLIC);
  }

  public boolean modelBound() {
    return pricing != null;
  }

  public boolean workerBound() {
    return capabilityRefs.equals(List.of(READ_ONLY_WORKER_CAPABILITY));
  }

  public boolean requiresExplicitAuthorization() {
    return modelBound() || (workerBound() && budgetUsd.signum() > 0);
  }

  public String modelProvider() {
    return modelBound() ? pricing.provider() : null;
  }

  public String modelRequested() {
    return modelBound() ? pricing.modelRequested() : null;
  }

  public String pricingProfile() {
    return modelBound() ? pricing.id() : null;
  }

  public String taskIdempotencyKey(String taskId) {
    if (!modelBound()) {
      return null;
    }
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

  public BigDecimal reservationUsd() {
    if (!modelBound()) {
      return BigDecimal.ZERO;
    }
    return pricing.reserveCostUsd(
        maxModelSteps, maxInputTokensPerStep, maxOutputTokensPerStep);
  }

  public Map<String, String> componentVersions() {
    return componentVersions(
        workerBound()
            ? ReadOnlyWorkerExecutionProfile.pack007FakeV1(
                contextPolicyVersion)
            : null);
  }

  public Map<String, String> componentVersions(
      ReadOnlyWorkerProfile workerProfile) {
    Map<String, String> versions = new LinkedHashMap<>();
    versions.put("agent", agentVersion);
    versions.put("verifier", verifierVersion);
    versions.put("trace-integrity", IntegrityHashes.PROFILE);
    if (modelBound()) {
      versions.put("model-adapter", modelAdapterVersion);
      versions.put("execution-profile", id);
      versions.put("execution-profile-fingerprint", fingerprint());
      versions.put("pricing-profile-fingerprint", pricing.fingerprint());
    }
    if (workerBound()) {
      ReadOnlyWorkerProfile worker =
          Objects.requireNonNull(workerProfile, "workerProfile");
      worker.requireParentProfileBinding(this);
      versions.putAll(worker.parentComponentVersions());
    } else if (workerProfile != null) {
      throw new IllegalArgumentException(
          "a non-Worker execution profile cannot bind a Worker profile");
    }
    return Map.copyOf(versions);
  }

  @Override
  public Map<String, String> modelComponentVersions() {
    if (!modelBound()) {
      throw new IllegalStateException(
          "offline execution profile has no Model component identity");
    }
    return componentVersions();
  }

  /**
   * Creates the only draft Task shape supported by this execution profile.
   *
   * <p>Eval preflight and the production service must call this same factory so the reviewed
   * whole-Task hash cannot drift through duplicate Task construction.
   */
  public TaskEnvelope newDraftTask(
      String taskId,
      String principalRef,
      String intent,
      String captureRef,
      DataClass dataClass) {
    TaskEnvelope task =
        new TaskEnvelope(
            taskSchemaVersion,
            taskId,
            null,
            principalRef,
            List.of(),
            "CREATE_ARTICLE_DRAFT",
            intent,
            List.of(captureRef),
            List.of(),
            List.of("text"),
            dataClass,
            risk,
            "INTERACTIVE",
            taskRequiredTools(),
            "urn:emergeos:schema:internal:agent-draft-proposal:v1",
            List.of("draft cites the source Capture"),
            false,
            maxModelSteps,
            maxToolCalls,
            deadlineMs,
            budgetUsd,
            modelProvider(),
            modelRequested(),
            pricingProfile(),
            taskIdempotencyKey(taskId),
            policyVersion,
            stateVersion,
            contextPolicyVersion,
            toolRegistryVersion,
            environmentSnapshotRef,
            capabilityRefs,
            List.of(),
            "structured final or non-success");
    requireTaskBinding(task);
    return task;
  }

  /**
   * Hashes every server-owned field that changes execution or metering semantics.
   *
   * <p>A compiled Eval catalog must bind {@code id -> fingerprint}; the fingerprint in a Bundle
   * makes the reviewed token bounds and pricing tuple replay-visible.
   */
  public String fingerprint() {
    StringBuilder material =
        new StringBuilder("agent-execution-profile-fingerprint-v1");
    append(material, "id", id);
    append(material, "taskSchemaVersion", taskSchemaVersion);
    append(material, "risk", risk.name());
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
    append(
        material,
        "pricingFingerprint",
        pricing == null ? null : pricing.fingerprint());
    append(material, "modelAdapterVersion", modelAdapterVersion);
    append(material, "agentVersion", agentVersion);
    append(material, "verifierVersion", verifierVersion);
    append(material, "harnessVersion", harnessVersion);
    append(
        material,
        "experimentArm",
        experiment == null ? null : experiment.arm());
    append(
        material,
        "experimentRepetition",
        experiment == null ? null : Integer.toString(experiment.repetition()));
    append(material, "policyVersion", policyVersion);
    append(material, "stateVersion", stateVersion);
    append(material, "contextPolicyVersion", contextPolicyVersion);
    append(material, "toolRegistryVersion", toolRegistryVersion);
    append(material, "environmentSnapshotRef", environmentSnapshotRef);
    for (int index = 0; index < capabilityRefs.size(); index++) {
      append(
          material,
          "capabilityRef[" + index + "]",
          capabilityRefs.get(index));
    }
    append(
        material,
        "requiredDataClass",
        requiredDataClass == null ? null : requiredDataClass.name());
    append(
        material,
        "taskShape",
        "CREATE_ARTICLE_DRAFT|INTERACTIVE|text|"
            + String.join(",", taskRequiredTools())
            + "|"
            + "urn:emergeos:schema:internal:agent-draft-proposal:v1|"
            + "draft cites the source Capture|serial");
    return ContentHashes.sha256(material.toString());
  }

  public void requireTaskBinding(TaskEnvelope task) {
    Objects.requireNonNull(task, "task");
    if (!taskSchemaVersion.equals(task.schemaVersion())
        || task.risk() != risk
        || task.maxModelSteps() != maxModelSteps
        || task.maxToolCalls() != maxToolCalls
        || task.deadlineMs() != deadlineMs
        || task.budgetUsd().compareTo(budgetUsd) != 0
        || !Objects.equals(task.modelProvider(), modelProvider())
        || !Objects.equals(task.modelRequested(), modelRequested())
        || !Objects.equals(task.pricingProfile(), pricingProfile())
        || !Objects.equals(
            task.idempotencyKey(), taskIdempotencyKey(task.id()))
        || !policyVersion.equals(task.policyVersion())
        || !stateVersion.equals(task.stateVersion())
        || !contextPolicyVersion.equals(task.contextPolicyVersion())
        || !toolRegistryVersion.equals(task.toolRegistryVersion())
        || !Objects.equals(
            environmentSnapshotRef, task.environmentSnapshotRef())
        || !capabilityRefs.equals(task.capabilityRefs())
        || (requiredDataClass != null
            && task.dataClass() != requiredDataClass)
        || !"CREATE_ARTICLE_DRAFT".equals(task.kind())
        || !"INTERACTIVE".equals(task.latencyClass())
        || !List.of("text").equals(task.modalities())
        || !taskRequiredTools().equals(task.requiredTools())
        || !"urn:emergeos:schema:internal:agent-draft-proposal:v1"
            .equals(task.outputSchema())
        || !List.of("draft cites the source Capture")
            .equals(task.acceptanceChecks())
        || task.allowParallel()
        || task.inputRefs().size() != 1
        || !task.inputRefs().getFirst().matches(
            "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")
        || !task.evidenceRefs().isEmpty()
        || task.parentId() != null
        || !task.delegationChain().isEmpty()
        || !task.unresolvedDecisions().isEmpty()
        || !"structured final or non-success"
            .equals(task.returnControlWhen())) {
      throw new IllegalArgumentException(
          "TaskEnvelope does not match the server-owned execution profile");
    }
  }

  private List<String> taskRequiredTools() {
    return workerBound() ? List.of() : List.of("capture.read");
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
        .append('=');
    if (value == null) {
      target.append("-1:");
    } else {
      target.append(value.length()).append(':').append(value);
    }
  }
}
