package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable terminal witness over the complete attributed graph truth.
 *
 * <p>The seal hash binds the head immediately before the seal event. The
 * event then binds the seal hash into the final journal head, avoiding a
 * circular hash preimage.
 */
public record GraphTerminalSeal(
    String attemptId,
    String manifestHash,
    int finalSequence,
    String preSealHeadHash,
    String finalHeadHash,
    GraphAttemptOutcome graphOutcome,
    GraphBillingStatus billingStatus,
    List<String> providerAttributionHashes,
    String candidateRef,
    String candidateIntegrityHash,
    String childTerminalHash,
    String parentTerminalHash,
    String sealHash,
    Instant sealedAt) {

  private static final String DOMAIN =
      "emergeos.graph-terminal-seal.v1";

  public GraphTerminalSeal {
    attemptId =
        GraphAttemptDomains.hash(attemptId, "attemptId");
    manifestHash =
        GraphAttemptDomains.hash(
            manifestHash, "manifestHash");
    if (finalSequence < 1 || finalSequence > 1_000_000) {
      throw new IllegalArgumentException(
          "terminal seal final sequence is invalid");
    }
    preSealHeadHash =
        GraphAttemptDomains.hash(
            preSealHeadHash, "preSealHeadHash");
    finalHeadHash =
        GraphAttemptDomains.hash(
            finalHeadHash, "finalHeadHash");
    graphOutcome =
        Objects.requireNonNull(graphOutcome, "graphOutcome");
    billingStatus =
        Objects.requireNonNull(billingStatus, "billingStatus");
    providerAttributionHashes =
        List.copyOf(
            Objects.requireNonNull(
                providerAttributionHashes,
                "providerAttributionHashes"));
    if (providerAttributionHashes.isEmpty()) {
      throw new IllegalArgumentException(
          "terminal seal requires provider attributions");
    }
    providerAttributionHashes.forEach(
        hash ->
            GraphAttemptDomains.hash(
                hash, "providerAttributionHash"));
    if ((candidateRef == null)
        != (candidateIntegrityHash == null)) {
      throw new IllegalArgumentException(
          "terminal Candidate ref and hash must be all-or-none");
    }
    if (candidateRef != null) {
      candidateRef =
          GraphAttemptDomains.safeReference(
              candidateRef, "candidateRef");
      candidateIntegrityHash =
          GraphAttemptDomains.hash(
              candidateIntegrityHash,
              "candidateIntegrityHash");
    }
    childTerminalHash =
        GraphAttemptDomains.hash(
            childTerminalHash, "childTerminalHash");
    parentTerminalHash =
        GraphAttemptDomains.hash(
            parentTerminalHash, "parentTerminalHash");
    sealHash =
        GraphAttemptDomains.hash(sealHash, "sealHash");
    sealedAt = Objects.requireNonNull(sealedAt, "sealedAt");
    if (graphOutcome == GraphAttemptOutcome.INCOMPLETE
        || billingStatus != GraphBillingStatus.ATTRIBUTED) {
      throw new IllegalArgumentException(
          "terminal seal requires terminal outcome and attributed billing");
    }
    if (graphOutcome == GraphAttemptOutcome.SUCCEEDED
        && candidateRef == null) {
      throw new IllegalArgumentException(
          "a successful terminal graph requires its Candidate");
    }
    if (!computeHash(
            attemptId,
            manifestHash,
            finalSequence,
            preSealHeadHash,
            graphOutcome,
            billingStatus,
            providerAttributionHashes,
            candidateRef,
            candidateIntegrityHash,
            childTerminalHash,
            parentTerminalHash,
            sealedAt)
        .equals(sealHash)) {
      throw new IllegalArgumentException(
          "terminal seal hash is inconsistent");
    }
  }

  public static String computeHash(
      String attemptId,
      String manifestHash,
      int finalSequence,
      String preSealHeadHash,
      GraphAttemptOutcome graphOutcome,
      GraphBillingStatus billingStatus,
      List<String> providerAttributionHashes,
      String candidateRef,
      String candidateIntegrityHash,
      String childTerminalHash,
      String parentTerminalHash,
      Instant sealedAt) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("attemptId", attemptId);
    material.put("billingStatus", billingStatus);
    material.put(
        "candidateIntegrityHash", candidateIntegrityHash);
    material.put("candidateRef", candidateRef);
    material.put("childTerminalHash", childTerminalHash);
    material.put("finalSequence", finalSequence);
    material.put("graphOutcome", graphOutcome);
    material.put("manifestHash", manifestHash);
    material.put("parentTerminalHash", parentTerminalHash);
    material.put("preSealHeadHash", preSealHeadHash);
    material.put(
        "providerAttributionHashes",
        List.copyOf(providerAttributionHashes));
    material.put("sealedAt", sealedAt);
    return CanonicalIntegrity.hash(DOMAIN, material);
  }
}
