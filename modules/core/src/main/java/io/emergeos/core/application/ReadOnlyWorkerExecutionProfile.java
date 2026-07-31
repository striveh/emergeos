package io.emergeos.core.application;

import io.emergeos.contracts.ContractText;
import io.emergeos.contracts.ContractValueDomains;
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

/** Exact server-owned profile for Pack007's offline read-only Worker. */
public record ReadOnlyWorkerExecutionProfile(
    String id,
    String registryVersion,
    String workerName,
    int maxModelSteps,
    int maxToolCalls,
    long maxDeadlineMs,
    String agentVersion,
    String verifierVersion,
    String harnessVersion,
    String expectedContextPolicyVersion,
    String expectedToolRegistryVersion)
    implements ReadOnlyWorkerProfile {

  public static final String REGISTRY_VERSION = "agent-workers-v1";
  public static final String WORKER_NAME = "article-draft.read-v1";
  public static final String OUTPUT_SCHEMA =
      "urn:emergeos:schema:internal:agent-draft-proposal:v1";

  public ReadOnlyWorkerExecutionProfile {
    ContractText.require(id, "Worker profile id", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        registryVersion, "Worker registry version", ContractText.MAX_NAME_LENGTH);
    if (workerName == null
        || !workerName.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("workerName is invalid");
    }
    ContractValueDomains.requireExecutionLimit(maxModelSteps, "maxModelSteps");
    ContractValueDomains.requireExecutionLimit(maxToolCalls, "maxToolCalls");
    ContractValueDomains.requireDuration(maxDeadlineMs, "maxDeadlineMs", false);
    ContractText.require(agentVersion, "agentVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        verifierVersion, "verifierVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        harnessVersion, "harnessVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        expectedContextPolicyVersion,
        "expectedContextPolicyVersion",
        ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        expectedToolRegistryVersion,
        "expectedToolRegistryVersion",
        ContractText.MAX_NAME_LENGTH);
  }

  public static ReadOnlyWorkerExecutionProfile pack007FakeV1() {
    return pack007FakeV1("ref-only-v1");
  }

  public static ReadOnlyWorkerExecutionProfile pack007FakeV1(
      String expectedContextPolicyVersion) {
    return new ReadOnlyWorkerExecutionProfile(
        "article-draft-read-worker-v1",
        REGISTRY_VERSION,
        WORKER_NAME,
        2,
        1,
        5_000,
        "agent-draft-worker-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v1",
        expectedContextPolicyVersion,
        "agent-tools-v2");
  }

  @Override
  public long deadlineMs() {
    return maxDeadlineMs;
  }

  public String validatePreparation(
      TaskEnvelope parent,
      WorkerHandoffRequest request,
      AgentWorkerRuntime.ExecutionWindow window) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(window, "window");
    String parentAuthorityRejection = parentAuthorityRejection(parent);
    if (parentAuthorityRejection != null) {
      return parentAuthorityRejection;
    }
    if (!workerName.equals(request.workerName())
        || !request.inputRefs().equals(parent.inputRefs())
        || request.inputRefs().size() != 1
        || !request.inputRefs().getFirst().matches(
            "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
      return "HANDOFF_NOT_ALLOWED";
    }
    if (window.remainingDeadlineMs() > parent.deadlineMs()
        || window.remainingBudgetUsd().compareTo(parent.budgetUsd()) > 0) {
      return "HANDOFF_AUTHORITY_ESCALATION";
    }
    return null;
  }

  public void requireParentBinding(TaskEnvelope parent) {
    Objects.requireNonNull(parent, "parent");
    String rejection = parentAuthorityRejection(parent);
    if (rejection != null) {
      throw new IllegalArgumentException(
          "parent Task violates the read-only Worker profile: " + rejection);
    }
  }

  @Override
  public void requireParentProfileBinding(
      AgentExecutionProfile parentProfile) {
    Objects.requireNonNull(parentProfile, "parentProfile");
    if (!parentExecutionProfile().equals(parentProfile)
        || !expectedToolRegistryVersion.equals(
            parentProfile.toolRegistryVersion())) {
      throw new IllegalArgumentException(
          "parent execution profile does not bind the Pack007 Worker");
    }
  }

  @Override
  public AgentExecutionProfile parentExecutionProfile() {
    return AgentExecutionProfile.readOnlyWorkerFakeV1(
        expectedContextPolicyVersion);
  }

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
            "1.0",
            childTaskId,
            parent.id(),
            parent.principalRef(),
            List.of(parent.id()),
            "PROPOSE_ARTICLE_DRAFT",
            request.intent(),
            request.inputRefs(),
            List.of(),
            parent.modalities(),
            parent.dataClass(),
            RiskLevel.READ_ONLY,
            parent.latencyClass(),
            List.of("capture.read"),
            OUTPUT_SCHEMA,
            List.of("proposal cites the delegated Capture"),
            false,
            Math.min(maxModelSteps, parent.maxModelSteps()),
            Math.min(maxToolCalls, parent.maxToolCalls()),
            Math.min(
                maxDeadlineMs,
                Math.min(parent.deadlineMs(), window.remainingDeadlineMs())),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            parent.policyVersion(),
            parent.stateVersion(),
            parent.contextPolicyVersion(),
            parent.toolRegistryVersion(),
            parent.environmentSnapshotRef(),
            List.of(),
            parent.unresolvedDecisions(),
            "typed Worker Result or non-success");
    requireChildBinding(parent, child);
    return child;
  }

  public void requireChildBinding(TaskEnvelope parent, TaskEnvelope child) {
    requireParentBinding(parent);
    Objects.requireNonNull(child, "child");
    if (!"1.0".equals(child.schemaVersion())
        || parent.id().equals(child.id())
        || !parent.principalRef().equals(child.principalRef())
        || !parent.id().equals(child.parentId())
        || !child.delegationChain().equals(List.of(parent.id()))
        || !"PROPOSE_ARTICLE_DRAFT".equals(child.kind())
        || child.dataClass() != parent.dataClass()
        || !child.modalities().equals(parent.modalities())
        || !child.latencyClass().equals(parent.latencyClass())
        || child.risk() != RiskLevel.READ_ONLY
        || child.allowParallel()
        || !child.inputRefs().equals(parent.inputRefs())
        || !child.evidenceRefs().isEmpty()
        || !child.requiredTools().equals(List.of("capture.read"))
        || !OUTPUT_SCHEMA.equals(child.outputSchema())
        || !child.acceptanceChecks().equals(
            List.of("proposal cites the delegated Capture"))
        || child.maxModelSteps() > parent.maxModelSteps()
        || child.maxModelSteps() > maxModelSteps
        || child.maxToolCalls() > parent.maxToolCalls()
        || child.maxToolCalls() > maxToolCalls
        || child.deadlineMs() > parent.deadlineMs()
        || child.deadlineMs() > maxDeadlineMs
        || child.budgetUsd().compareTo(parent.budgetUsd()) > 0
        || child.budgetUsd().signum() != 0
        || !parent.policyVersion().equals(child.policyVersion())
        || !parent.stateVersion().equals(child.stateVersion())
        || !parent.contextPolicyVersion().equals(child.contextPolicyVersion())
        || !parent.toolRegistryVersion().equals(child.toolRegistryVersion())
        || !Objects.equals(
            parent.environmentSnapshotRef(), child.environmentSnapshotRef())
        || !child.capabilityRefs().isEmpty()
        || !child.unresolvedDecisions().equals(parent.unresolvedDecisions())
        || !"typed Worker Result or non-success"
            .equals(child.returnControlWhen())) {
      throw new IllegalArgumentException(
          "child Task violates the read-only Worker profile");
    }
  }

  public Map<String, String> componentVersions() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("agent", agentVersion);
    values.put("verifier", verifierVersion);
    values.put("trace-integrity", IntegrityHashes.PROFILE);
    values.put("worker-registry", registryVersion);
    values.put("worker-profile-fingerprint", fingerprint());
    return Map.copyOf(values);
  }

  private String parentAuthorityRejection(TaskEnvelope parent) {
    if (!expectedContextPolicyVersion.equals(parent.contextPolicyVersion())) {
      return "HANDOFF_CONTEXT_POLICY_DRIFT";
    }
    if (!"1.0".equals(parent.schemaVersion())
        || parent.parentId() != null
        || !parent.delegationChain().isEmpty()
        || !"CREATE_ARTICLE_DRAFT".equals(parent.kind())
        || parent.allowParallel()
        || !parent.requiredTools().isEmpty()
        || !parent.capabilityRefs().equals(
            List.of(AgentExecutionProfile.READ_ONLY_WORKER_CAPABILITY))
        || !expectedToolRegistryVersion.equals(parent.toolRegistryVersion())) {
      return "HANDOFF_AUTHORITY_ESCALATION";
    }
    return null;
  }

  public String fingerprint() {
    String material =
        "read-only-worker-profile-v1"
            + "|id="
            + id
            + "|registry="
            + registryVersion
            + "|worker="
            + workerName
            + "|maxModelSteps="
            + maxModelSteps
            + "|maxToolCalls="
            + maxToolCalls
            + "|maxDeadlineMs="
            + maxDeadlineMs
            + "|agent="
            + agentVersion
            + "|verifier="
            + verifierVersion
            + "|harness="
            + harnessVersion
            + "|context="
            + expectedContextPolicyVersion
            + "|tools="
            + expectedToolRegistryVersion
            + "|shape=PROPOSE_ARTICLE_DRAFT|READ_ONLY|capture.read|serial|depth1";
    return ContentHashes.sha256(material);
  }
}
