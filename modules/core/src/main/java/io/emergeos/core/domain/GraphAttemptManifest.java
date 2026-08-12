package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, server-owned one-shot graph manifest.
 *
 * <p>The content-derived attempt ID may change as reviewed code changes.
 * Replay safety therefore comes from the separately frozen
 * {@code executionSlotId}, which PostgreSQL uniquely claims per principal.
 */
public record GraphAttemptManifest(
    String schemaVersion,
    String graphProtocolVersion,
    String principalId,
    String executionSlotId,
    String caseId,
    String packRawSha256,
    String environmentRawSha256,
    String captureId,
    String captureRequestHash,
    String artifactId,
    Instant startedAt,
    String pricingProfileFingerprint,
    String promptSurfaceFingerprint,
    String conductorSurfaceFingerprint,
    BigDecimal reservationUsd,
    int maximumProviderRequests,
    String parentActor,
    String childActor,
    HarnessExperiment experiment,
    GraphRunSelection parentSelection,
    GraphRunSelection childSelection,
    String integrityProfile,
    String manifestHash) {

  private static final String MANIFEST_DOMAIN =
      "emergeos.graph-attempt-manifest.v1";
  public static final String TERMINAL_PROTOCOL_VERSION =
      "postgres-graph-terminal-v1";

  public GraphAttemptManifest {
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported graph manifest schema");
    }
    graphProtocolVersion =
        GraphAttemptDomains.safeName(
            graphProtocolVersion, "graphProtocolVersion");
    principalId =
        GraphAttemptDomains.safeName(principalId, "principalId");
    executionSlotId =
        GraphAttemptDomains.identifier(
            executionSlotId, "executionSlotId");
    caseId = GraphAttemptDomains.identifier(caseId, "caseId");
    packRawSha256 =
        GraphAttemptDomains.hash(packRawSha256, "packRawSha256");
    environmentRawSha256 =
        GraphAttemptDomains.hash(
            environmentRawSha256, "environmentRawSha256");
    captureId =
        GraphAttemptDomains.identifier(captureId, "captureId");
    captureRequestHash =
        GraphAttemptDomains.hash(
            captureRequestHash, "captureRequestHash");
    artifactId =
        GraphAttemptDomains.identifier(artifactId, "artifactId");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
    pricingProfileFingerprint =
        GraphAttemptDomains.hash(
            pricingProfileFingerprint,
            "pricingProfileFingerprint");
    promptSurfaceFingerprint =
        GraphAttemptDomains.hash(
            promptSurfaceFingerprint,
            "promptSurfaceFingerprint");
    conductorSurfaceFingerprint =
        GraphAttemptDomains.hash(
            conductorSurfaceFingerprint,
            "conductorSurfaceFingerprint");
    ContractValueDomains.requireUsd(reservationUsd, "reservationUsd");
    ContractValueDomains.requireExecutionLimit(
        maximumProviderRequests, "maximumProviderRequests");
    if (TERMINAL_PROTOCOL_VERSION.equals(graphProtocolVersion)
        && maximumProviderRequests != 2) {
      throw new IllegalArgumentException(
          "terminal graph protocol requires exactly two provider requests");
    }
    parentActor =
        GraphAttemptDomains.safeName(parentActor, "parentActor");
    childActor =
        GraphAttemptDomains.safeName(childActor, "childActor");
    experiment = Objects.requireNonNull(experiment, "experiment");
    parentSelection =
        Objects.requireNonNull(parentSelection, "parentSelection");
    childSelection =
        Objects.requireNonNull(childSelection, "childSelection");
    if (parentSelection.role() != GraphRunRole.PARENT
        || childSelection.role() != GraphRunRole.CHILD
        || parentSelection.runId().equals(childSelection.runId())
        || parentSelection.taskId().equals(childSelection.taskId())) {
      throw new IllegalArgumentException(
          "graph manifest requires distinct PARENT and CHILD selections");
    }
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException(
          "unsupported graph integrity profile");
    }
    manifestHash =
        GraphAttemptDomains.hash(manifestHash, "manifestHash");
    String computed =
        computeHash(
            schemaVersion,
            graphProtocolVersion,
            principalId,
            executionSlotId,
            caseId,
            packRawSha256,
            environmentRawSha256,
            captureId,
            captureRequestHash,
            artifactId,
            startedAt,
            pricingProfileFingerprint,
            promptSurfaceFingerprint,
            conductorSurfaceFingerprint,
            reservationUsd,
            maximumProviderRequests,
            parentActor,
            childActor,
            experiment,
            parentSelection,
            childSelection,
            integrityProfile);
    if (!computed.equals(manifestHash)) {
      throw new IllegalArgumentException(
          "graph manifest hash does not match canonical material");
    }
  }

  public static GraphAttemptManifest create(
      String graphProtocolVersion,
      String principalId,
      String executionSlotId,
      String caseId,
      String packRawSha256,
      String environmentRawSha256,
      String captureId,
      String captureRequestHash,
      String artifactId,
      Instant startedAt,
      String pricingProfileFingerprint,
      String promptSurfaceFingerprint,
      String conductorSurfaceFingerprint,
      BigDecimal reservationUsd,
      int maximumProviderRequests,
      String parentActor,
      String childActor,
      HarnessExperiment experiment,
      GraphRunSelection parentSelection,
      GraphRunSelection childSelection) {
    String schemaVersion = "1.0";
    String integrityProfile = IntegrityHashes.PROFILE;
    String hash =
        computeHash(
            schemaVersion,
            graphProtocolVersion,
            principalId,
            executionSlotId,
            caseId,
            packRawSha256,
            environmentRawSha256,
            captureId,
            captureRequestHash,
            artifactId,
            startedAt,
            pricingProfileFingerprint,
            promptSurfaceFingerprint,
            conductorSurfaceFingerprint,
            reservationUsd,
            maximumProviderRequests,
            parentActor,
            childActor,
            experiment,
            parentSelection,
            childSelection,
            integrityProfile);
    return new GraphAttemptManifest(
        schemaVersion,
        graphProtocolVersion,
        principalId,
        executionSlotId,
        caseId,
        packRawSha256,
        environmentRawSha256,
        captureId,
        captureRequestHash,
        artifactId,
        startedAt,
        pricingProfileFingerprint,
        promptSurfaceFingerprint,
        conductorSurfaceFingerprint,
        reservationUsd,
        maximumProviderRequests,
        parentActor,
        childActor,
        experiment,
        parentSelection,
        childSelection,
        integrityProfile,
        hash);
  }

  public String attemptId() {
    return manifestHash;
  }

  public List<GraphRunSelection> selections() {
    return List.of(parentSelection, childSelection);
  }

  private static String computeHash(
      String schemaVersion,
      String graphProtocolVersion,
      String principalId,
      String executionSlotId,
      String caseId,
      String packRawSha256,
      String environmentRawSha256,
      String captureId,
      String captureRequestHash,
      String artifactId,
      Instant startedAt,
      String pricingProfileFingerprint,
      String promptSurfaceFingerprint,
      String conductorSurfaceFingerprint,
      BigDecimal reservationUsd,
      int maximumProviderRequests,
      String parentActor,
      String childActor,
      HarnessExperiment experiment,
      GraphRunSelection parentSelection,
      GraphRunSelection childSelection,
      String integrityProfile) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("artifactId", artifactId);
    material.put("caseId", caseId);
    material.put("captureId", captureId);
    material.put("captureRequestHash", captureRequestHash);
    material.put("childActor", childActor);
    material.put("childSelection", childSelection);
    material.put(
        "conductorSurfaceFingerprint",
        conductorSurfaceFingerprint);
    material.put("environmentRawSha256", environmentRawSha256);
    material.put("executionSlotId", executionSlotId);
    material.put("experiment", experiment);
    material.put("graphProtocolVersion", graphProtocolVersion);
    material.put("integrityProfile", integrityProfile);
    material.put(
        "maximumProviderRequests", maximumProviderRequests);
    material.put("packRawSha256", packRawSha256);
    material.put("parentActor", parentActor);
    material.put("parentSelection", parentSelection);
    material.put("pricingProfileFingerprint", pricingProfileFingerprint);
    material.put("principalId", principalId);
    material.put("promptSurfaceFingerprint", promptSurfaceFingerprint);
    material.put("reservationUsd", reservationUsd);
    material.put("schemaVersion", schemaVersion);
    material.put("startedAt", startedAt);
    return CanonicalIntegrity.hash(MANIFEST_DOMAIN, material);
  }
}
