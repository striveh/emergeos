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
    long deadlineMs,
    BigDecimal budgetUsd,
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
    requireText(schemaVersion, "schemaVersion");
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("TaskEnvelope supports schemaVersion 1.0");
    }
    requireText(id, "id");
    requireText(principalRef, "principalRef");
    requireText(kind, "kind");
    requireText(intent, "intent");
    Objects.requireNonNull(dataClass, "dataClass");
    Objects.requireNonNull(risk, "risk");
    requireText(latencyClass, "latencyClass");
    requireText(outputSchema, "outputSchema");
    requireText(policyVersion, "policyVersion");
    requireText(stateVersion, "stateVersion");
    requireText(contextPolicyVersion, "contextPolicyVersion");
    requireText(toolRegistryVersion, "toolRegistryVersion");
    requireText(returnControlWhen, "returnControlWhen");
    if (deadlineMs <= 0) {
      throw new IllegalArgumentException("deadlineMs must be positive");
    }
    if (Objects.requireNonNull(budgetUsd, "budgetUsd").signum() < 0) {
      throw new IllegalArgumentException("budgetUsd must not be negative");
    }
    delegationChain = copy(delegationChain, "delegationChain");
    inputRefs = copy(inputRefs, "inputRefs");
    evidenceRefs = copy(evidenceRefs, "evidenceRefs");
    modalities = copy(modalities, "modalities");
    if (!Set.of("text", "image", "audio", "video").containsAll(modalities)) {
      throw new IllegalArgumentException("modalities contains an unsupported value");
    }
    if (!Set.of("REALTIME", "INTERACTIVE", "DEEP", "ASYNC").contains(latencyClass)) {
      throw new IllegalArgumentException("latencyClass contains an unsupported value");
    }
    requiredTools = copy(requiredTools, "requiredTools");
    acceptanceChecks = copy(acceptanceChecks, "acceptanceChecks");
    capabilityRefs = copy(capabilityRefs, "capabilityRefs");
    unresolvedDecisions = copy(unresolvedDecisions, "unresolvedDecisions");
  }

  private static List<String> copy(List<String> value, String name) {
    return List.copyOf(Objects.requireNonNull(value, name));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
