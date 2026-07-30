package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HarnessRunBundle(
    String schemaVersion,
    String runId,
    String taskId,
    HarnessExperiment experiment,
    String modelResolved,
    String harnessVersion,
    Map<String, String> componentVersions,
    String environmentSnapshotRef,
    String toolRegistryVersion,
    TaskEnvelope task,
    ResultEnvelope result,
    String workingSelfRef,
    String traceRef,
    String traceRootHash,
    List<String> handoffRefs,
    List<String> checkpointRefs,
    List<ResourceBinding> resourceBindings,
    String verificationRef,
    String failureAttribution,
    RunStatus outcome,
    BigDecimal costUsd,
    long tokenCount,
    long latencyMs,
    String integrityProfile,
    String integrityHash) {

  public HarnessRunBundle {
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("HarnessRunBundle supports schemaVersion 1.0");
    }
    requireId(runId, "runId");
    requireId(taskId, "taskId");
    ContractText.requireOptional(
        modelResolved, "modelResolved", ContractText.MAX_MODEL_LENGTH);
    ContractText.require(harnessVersion, "harnessVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        toolRegistryVersion, "toolRegistryVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(environmentSnapshotRef, "environmentSnapshotRef");
    ContractText.requireOptional(workingSelfRef, "workingSelfRef");
    ContractText.requireOptional(verificationRef, "verificationRef");
    ContractText.requireOptional(
        failureAttribution, "failureAttribution", ContractText.MAX_MODEL_LENGTH);
    componentVersions =
        Map.copyOf(Objects.requireNonNull(componentVersions, "componentVersions"));
    componentVersions.forEach(
        (name, version) -> {
          ContractText.require(
              name, "component version name", ContractText.MAX_NAME_LENGTH);
          ContractText.require(
              version, "component version", ContractText.MAX_NAME_LENGTH);
        });
    task = Objects.requireNonNull(task, "task");
    result = Objects.requireNonNull(result, "result");
    outcome = Objects.requireNonNull(outcome, "outcome");
    handoffRefs = ContractText.copyStrings(handoffRefs, "handoffRefs");
    checkpointRefs = ContractText.copyStrings(checkpointRefs, "checkpointRefs");
    resourceBindings =
        List.copyOf(Objects.requireNonNull(resourceBindings, "resourceBindings"));
    ContractText.require(traceRef, "traceRef");
    IntegrityHashes.requireHash(traceRootHash, "traceRootHash");
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException("Unsupported Bundle integrityProfile");
    }
    IntegrityHashes.requireHash(integrityHash, "integrityHash");
    ContractValueDomains.requireUsd(costUsd, "costUsd");
    ContractValueDomains.requireSafeCount(tokenCount, "tokenCount");
    ContractValueDomains.requireDuration(latencyMs, "latencyMs", true);

    verifyConsistency(
        runId,
        taskId,
        modelResolved,
        componentVersions,
        environmentSnapshotRef,
        toolRegistryVersion,
        task,
        result,
        traceRef,
        handoffRefs,
        checkpointRefs,
        resourceBindings,
        verificationRef,
        failureAttribution,
        outcome,
        costUsd,
        tokenCount,
        latencyMs,
        integrityProfile);
    String expected =
        IntegrityHashes.bundleHash(
            preimage(
                schemaVersion,
                runId,
                taskId,
                experiment,
                modelResolved,
                harnessVersion,
                componentVersions,
                environmentSnapshotRef,
                toolRegistryVersion,
                task,
                result,
                workingSelfRef,
                traceRef,
                traceRootHash,
                handoffRefs,
                checkpointRefs,
                resourceBindings,
                verificationRef,
                failureAttribution,
                outcome,
                costUsd,
                tokenCount,
                latencyMs,
                integrityProfile));
    if (!expected.equals(integrityHash)) {
      throw new IllegalArgumentException("integrityHash does not match HarnessRunBundle");
    }
  }

  public static HarnessRunBundle create(
      String schemaVersion,
      String runId,
      String taskId,
      HarnessExperiment experiment,
      String modelResolved,
      String harnessVersion,
      Map<String, String> componentVersions,
      String environmentSnapshotRef,
      String toolRegistryVersion,
      TaskEnvelope task,
      ResultEnvelope result,
      String workingSelfRef,
      String traceRef,
      String traceRootHash,
      List<String> handoffRefs,
      List<String> checkpointRefs,
      List<ResourceBinding> resourceBindings,
      String verificationRef,
      String failureAttribution,
      RunStatus outcome,
      BigDecimal costUsd,
      long tokenCount,
      long latencyMs) {
    String hash =
        IntegrityHashes.bundleHash(
            preimage(
                schemaVersion,
                runId,
                taskId,
                experiment,
                modelResolved,
                harnessVersion,
                componentVersions,
                environmentSnapshotRef,
                toolRegistryVersion,
                task,
                result,
                workingSelfRef,
                traceRef,
                traceRootHash,
                handoffRefs,
                checkpointRefs,
                resourceBindings,
                verificationRef,
                failureAttribution,
                outcome,
                costUsd,
                tokenCount,
                latencyMs,
                IntegrityHashes.PROFILE));
    return new HarnessRunBundle(
        schemaVersion,
        runId,
        taskId,
        experiment,
        modelResolved,
        harnessVersion,
        componentVersions,
        environmentSnapshotRef,
        toolRegistryVersion,
        task,
        result,
        workingSelfRef,
        traceRef,
        traceRootHash,
        handoffRefs,
        checkpointRefs,
        resourceBindings,
        verificationRef,
        failureAttribution,
        outcome,
        costUsd,
        tokenCount,
        latencyMs,
        IntegrityHashes.PROFILE,
        hash);
  }

  private static void verifyConsistency(
      String runId,
      String taskId,
      String modelResolved,
      Map<String, String> componentVersions,
      String environmentSnapshotRef,
      String toolRegistryVersion,
      TaskEnvelope task,
      ResultEnvelope result,
      String traceRef,
      List<String> handoffRefs,
      List<String> checkpointRefs,
      List<ResourceBinding> bindings,
      String verificationRef,
      String failureAttribution,
      RunStatus outcome,
      BigDecimal costUsd,
      long tokenCount,
      long latencyMs,
      String integrityProfile) {
    if (!runId.equals(result.runId())
        || !taskId.equals(task.id())
        || !taskId.equals(result.taskId())
        || !Objects.equals(environmentSnapshotRef, task.environmentSnapshotRef())
        || !toolRegistryVersion.equals(task.toolRegistryVersion())
        || !Objects.equals(modelResolved, result.resolvedModel())
        || !traceRef.equals(result.traceRef())
        || !Objects.equals(failureAttribution, result.failureReason())
        || outcome != result.status()
        || costUsd.compareTo(result.costUsd()) != 0
        || tokenCount != result.tokenCount()
        || latencyMs != result.latencyMs()
        || costUsd.compareTo(task.budgetUsd()) > 0
        || latencyMs > task.deadlineMs()) {
      throw new IllegalArgumentException("Bundle fields do not match Task and Result");
    }
    if (!result.agentVersion().equals(componentVersions.get("agent"))
        || !result.verifierVersion().equals(componentVersions.get("verifier"))
        || !integrityProfile.equals(componentVersions.get("trace-integrity"))) {
      throw new IllegalArgumentException(
          "Bundle componentVersions do not match Result and integrity profiles");
    }
    verifyGlobalBindingOrder(bindings);
    verifyBindingRefs(bindings, ResourceRole.EVIDENCE, result.evidenceRefs());
    verifyBindingRefs(bindings, ResourceRole.ARTIFACT, result.artifactRefs());
    verifyBindingRefs(bindings, ResourceRole.RECEIPT, result.receiptRefs());
    verifyBindingRefs(bindings, ResourceRole.HANDOFF, handoffRefs);
    verifyBindingRefs(bindings, ResourceRole.CHECKPOINT, checkpointRefs);
    verifyBindingRefs(
        bindings,
        ResourceRole.VERIFICATION,
        verificationRef == null ? List.of() : List.of(verificationRef));
    if (outcome != RunStatus.SUCCEEDED && !result.artifactRefs().isEmpty()) {
      throw new IllegalArgumentException("A non-success Run cannot bind an Artifact");
    }
    if (outcome == RunStatus.SUCCEEDED
        && "CREATE_ARTICLE_DRAFT".equals(task.kind())
        && result.artifactRefs().size() != 1) {
      throw new IllegalArgumentException(
          "A successful CREATE_ARTICLE_DRAFT Run requires exactly one Artifact");
    }
  }

  private static void verifyGlobalBindingOrder(List<ResourceBinding> bindings) {
    Comparator<ResourceBinding> canonicalOrder =
        Comparator.comparingInt((ResourceBinding binding) -> binding.role().ordinal())
            .thenComparingInt(ResourceBinding::ordinal);
    List<ResourceBinding> sorted = bindings.stream().sorted(canonicalOrder).toList();
    if (!bindings.equals(sorted)) {
      throw new IllegalArgumentException(
          "Resource bindings must use canonical global role and ordinal order");
    }
  }

  private static void verifyBindingRefs(
      List<ResourceBinding> bindings, ResourceRole role, List<String> expectedRefs) {
    List<ResourceBinding> matching =
        bindings.stream()
            .filter(binding -> binding.role() == role)
            .sorted(java.util.Comparator.comparingInt(ResourceBinding::ordinal))
            .toList();
    List<String> refs = new ArrayList<>();
    for (int index = 0; index < matching.size(); index++) {
      if (matching.get(index).ordinal() != index) {
        throw new IllegalArgumentException("Resource binding ordinals must be continuous per role");
      }
      refs.add(matching.get(index).ref());
    }
    if (!refs.equals(expectedRefs)) {
      throw new IllegalArgumentException(role + " bindings do not match Result refs");
    }
  }

  private static Map<String, Object> preimage(
      String schemaVersion,
      String runId,
      String taskId,
      HarnessExperiment experiment,
      String modelResolved,
      String harnessVersion,
      Map<String, String> componentVersions,
      String environmentSnapshotRef,
      String toolRegistryVersion,
      TaskEnvelope task,
      ResultEnvelope result,
      String workingSelfRef,
      String traceRef,
      String traceRootHash,
      List<String> handoffRefs,
      List<String> checkpointRefs,
      List<ResourceBinding> resourceBindings,
      String verificationRef,
      String failureAttribution,
      RunStatus outcome,
      BigDecimal costUsd,
      long tokenCount,
      long latencyMs,
      String integrityProfile) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("checkpointRefs", checkpointRefs);
    values.put("componentVersions", componentVersions);
    values.put("costUsd", costUsd);
    values.put("environmentSnapshotRef", environmentSnapshotRef);
    values.put("experiment", experiment);
    values.put("failureAttribution", failureAttribution);
    values.put("handoffRefs", handoffRefs);
    values.put("harnessVersion", harnessVersion);
    values.put("integrityProfile", integrityProfile);
    values.put("latencyMs", latencyMs);
    values.put("modelResolved", modelResolved);
    values.put("outcome", outcome);
    values.put("resourceBindings", resourceBindings);
    values.put("result", result);
    values.put("runId", runId);
    values.put("schemaVersion", schemaVersion);
    values.put("task", task);
    values.put("taskId", taskId);
    values.put("tokenCount", tokenCount);
    values.put("toolRegistryVersion", toolRegistryVersion);
    values.put("traceRef", traceRef);
    values.put("traceRootHash", traceRootHash);
    values.put("verificationRef", verificationRef);
    values.put("workingSelfRef", workingSelfRef);
    return values;
  }

  private static void requireId(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " has an invalid identifier");
    }
  }

}
