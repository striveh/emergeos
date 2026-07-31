package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TaskEnvelope(
    String schemaVersion,
    String id,
    String parentId,
    String principalRef,
    List<String> delegationChain,
    String kind,
    String intent,
    List<String> inputRefs,
    List<String> evidenceRefs,
    List<String> modalities,
    DataClass dataClass,
    RiskLevel risk,
    String latencyClass,
    List<String> requiredTools,
    String outputSchema,
    List<String> acceptanceChecks,
    boolean allowParallel,
    int maxModelSteps,
    int maxToolCalls,
    long deadlineMs,
    BigDecimal budgetUsd,
    String modelProvider,
    String modelRequested,
    String pricingProfile,
    String idempotencyKey,
    String policyVersion,
    String stateVersion,
    String contextPolicyVersion,
    String toolRegistryVersion,
    String environmentSnapshotRef,
    List<String> capabilityRefs,
    List<String> unresolvedDecisions,
    String returnControlWhen) {

  public TaskEnvelope {
    ContractText.require(schemaVersion, "schemaVersion", ContractText.MAX_NAME_LENGTH);
    if (!Set.of("1.0", "1.1").contains(schemaVersion)) {
      throw new IllegalArgumentException("TaskEnvelope supports schemaVersion 1.0 and 1.1");
    }
    requireId(id, "id");
    requireOptionalId(parentId, "parentId");
    ContractText.require(principalRef, "principalRef", ContractText.MAX_NAME_LENGTH);
    ContractText.require(kind, "kind", ContractText.MAX_NAME_LENGTH);
    ContractText.require(intent, "intent");
    Objects.requireNonNull(dataClass, "dataClass");
    Objects.requireNonNull(risk, "risk");
    ContractText.require(latencyClass, "latencyClass", ContractText.MAX_NAME_LENGTH);
    ContractText.require(outputSchema, "outputSchema");
    ContractText.require(policyVersion, "policyVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(stateVersion, "stateVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        contextPolicyVersion, "contextPolicyVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        toolRegistryVersion, "toolRegistryVersion", ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(
        idempotencyKey, "idempotencyKey", ContractText.MAX_NAME_LENGTH);
    ContractText.requireOptional(environmentSnapshotRef, "environmentSnapshotRef");
    ContractText.require(returnControlWhen, "returnControlWhen");
    ContractValueDomains.requireExecutionLimit(maxModelSteps, "maxModelSteps");
    ContractValueDomains.requireExecutionLimit(maxToolCalls, "maxToolCalls");
    ContractValueDomains.requireDuration(deadlineMs, "deadlineMs", false);
    ContractValueDomains.requireUsd(budgetUsd, "budgetUsd");
    verifyModelBinding(
        schemaVersion,
        modelProvider,
        modelRequested,
        pricingProfile,
        idempotencyKey,
        environmentSnapshotRef);
    delegationChain = ContractText.copyStrings(delegationChain, "delegationChain");
    if ((parentId != null && parentId.equals(id))
        || (parentId == null
            ? !delegationChain.isEmpty()
            : !delegationChain.equals(List.of(parentId)))) {
      throw new IllegalArgumentException(
          "Task delegation must be root or exact non-self depth-one parent lineage");
    }
    inputRefs = ContractText.copyStrings(inputRefs, "inputRefs");
    evidenceRefs = ContractText.copyStrings(evidenceRefs, "evidenceRefs");
    modalities =
        ContractText.copyStrings(modalities, "modalities", ContractText.MAX_NAME_LENGTH);
    if (!Set.of("text", "image", "audio", "video").containsAll(modalities)) {
      throw new IllegalArgumentException("modalities contains an unsupported value");
    }
    if (!Set.of("REALTIME", "INTERACTIVE", "DEEP", "ASYNC").contains(latencyClass)) {
      throw new IllegalArgumentException("latencyClass contains an unsupported value");
    }
    requiredTools =
        ContractText.copyStrings(requiredTools, "requiredTools", ContractText.MAX_NAME_LENGTH);
    acceptanceChecks = ContractText.copyStrings(acceptanceChecks, "acceptanceChecks");
    capabilityRefs = ContractText.copyStrings(capabilityRefs, "capabilityRefs");
    unresolvedDecisions =
        ContractText.copyStrings(unresolvedDecisions, "unresolvedDecisions");
  }

  private static void requireId(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " has an invalid identifier");
    }
  }

  private static void requireOptionalId(String value, String name) {
    if (value != null) {
      requireId(value, name);
    }
  }

  private static void verifyModelBinding(
      String schemaVersion,
      String modelProvider,
      String modelRequested,
      String pricingProfile,
      String idempotencyKey,
      String environmentSnapshotRef) {
    if ("1.0".equals(schemaVersion)) {
      if (modelProvider != null || modelRequested != null || pricingProfile != null) {
        throw new IllegalArgumentException(
            "TaskEnvelope 1.0 cannot carry a model execution binding");
      }
      return;
    }
    ContractText.require(
        modelProvider, "modelProvider", ContractText.MAX_NAME_LENGTH);
    ContractText.require(
        modelRequested, "modelRequested", ContractText.MAX_MODEL_LENGTH);
    ContractText.require(
        pricingProfile, "pricingProfile", ContractText.MAX_NAME_LENGTH);
    if (!modelProvider.matches("[a-z][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("modelProvider must be a stable lowercase slug");
    }
    if (!ContractText.isSafeModelIdentifier(modelRequested)) {
      throw new IllegalArgumentException("modelRequested is outside the safe model domain");
    }
    if (!pricingProfile.matches("[a-z][a-z0-9._-]{0,199}")) {
      throw new IllegalArgumentException("pricingProfile must be a stable lowercase slug");
    }
    if (idempotencyKey == null) {
      throw new IllegalArgumentException(
          "TaskEnvelope 1.1 requires a server-owned idempotencyKey");
    }
    if (environmentSnapshotRef == null
        || !environmentSnapshotRef.matches("environment://sha256:[a-f0-9]{64}")) {
      throw new IllegalArgumentException(
          "TaskEnvelope 1.1 requires a content-addressed environment snapshot");
    }
  }

}
