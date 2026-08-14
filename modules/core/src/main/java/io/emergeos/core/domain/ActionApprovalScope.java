package io.emergeos.core.domain;

import io.emergeos.contracts.RiskLevel;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** The complete, immutable boundary a local owner is asked to approve. */
public record ActionApprovalScope(
    String principalBasis,
    String configuredPrincipalId,
    String approvalOrigin,
    String executionRoute,
    String actionType,
    String targetRef,
    String artifactId,
    int artifactVersion,
    String artifactHash,
    RiskLevel risk,
    String policyVersion,
    String connector,
    String audience,
    String accountRef,
    long capabilityTtlMicros,
    int maxCalls) {

  public static final String SCHEMA = "emergeos.action-approval-scope.v1";
  public static final String CONFIGURED_LOCAL_PRINCIPAL = "CONFIGURED_LOCAL_PRINCIPAL";
  public static final String PRE_V17_UNPROVEN = "PRE_V17_UNPROVEN";
  public static final String LEGACY_SERVER_IMPLICIT = "LEGACY_SERVER_IMPLICIT";
  public static final String EXPLICIT_LOCAL_OWNER_INPUT = "EXPLICIT_LOCAL_OWNER_INPUT";
  public static final String SIMULATED_PROVIDER_V1 = "SIMULATED_PROVIDER_V1";
  public static final String LOCAL_DRAFTBOX_V1 = "LOCAL_DRAFTBOX_V1";

  public ActionApprovalScope {
    requireExact(principalBasis, CONFIGURED_LOCAL_PRINCIPAL, "principalBasis");
    requireText(configuredPrincipalId, "configuredPrincipalId");
    requireOneOf(
        approvalOrigin,
        "approvalOrigin",
        PRE_V17_UNPROVEN,
        LEGACY_SERVER_IMPLICIT,
        EXPLICIT_LOCAL_OWNER_INPUT);
    requireOneOf(
        executionRoute,
        "executionRoute",
        SIMULATED_PROVIDER_V1,
        LOCAL_DRAFTBOX_V1);
    boolean validProvenanceRoute =
        (EXPLICIT_LOCAL_OWNER_INPUT.equals(approvalOrigin)
                && LOCAL_DRAFTBOX_V1.equals(executionRoute))
            || ((PRE_V17_UNPROVEN.equals(approvalOrigin)
                    || LEGACY_SERVER_IMPLICIT.equals(approvalOrigin))
                && SIMULATED_PROVIDER_V1.equals(executionRoute));
    if (!validProvenanceRoute) {
      throw new IllegalArgumentException("approvalOrigin and executionRoute are incompatible");
    }
    requireText(actionType, "actionType");
    requireText(targetRef, "targetRef");
    requireText(artifactId, "artifactId");
    requireHash(artifactHash, "artifactHash");
    Objects.requireNonNull(risk, "risk");
    requireText(policyVersion, "policyVersion");
    requireText(connector, "connector");
    requireText(audience, "audience");
    requireText(accountRef, "accountRef");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    if (capabilityTtlMicros < 0
        || (capabilityTtlMicros == 0 && !PRE_V17_UNPROVEN.equals(approvalOrigin))) {
      throw new IllegalArgumentException(
          "capabilityTtlMicros must be non-negative only for unproven history");
    }
    if (maxCalls < 1) {
      throw new IllegalArgumentException("maxCalls must be positive");
    }
  }

  public String scopeSchema() {
    return SCHEMA;
  }

  public String scopeHash() {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  public byte[] canonicalBytes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(SCHEMA.getBytes(StandardCharsets.UTF_8));
    bytes.write(0);
    for (String value : canonicalValues()) {
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
      bytes.writeBytes(utf8);
    }
    return bytes.toByteArray();
  }

  private List<String> canonicalValues() {
    return List.of(
        principalBasis,
        configuredPrincipalId,
        approvalOrigin,
        executionRoute,
        actionType,
        targetRef,
        artifactId,
        Integer.toString(artifactVersion),
        artifactHash,
        risk.name(),
        policyVersion,
        connector,
        audience,
        accountRef,
        Long.toString(capabilityTtlMicros),
        Integer.toString(maxCalls));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.length() > 1000) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }

  private static void requireHash(String value, String name) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
    }
  }

  private static void requireExact(String value, String expected, String name) {
    if (!expected.equals(value)) {
      throw new IllegalArgumentException(name + " is unsupported");
    }
  }

  private static void requireOneOf(String value, String name, String... allowed) {
    for (String candidate : allowed) {
      if (candidate.equals(value)) {
        return;
      }
    }
    throw new IllegalArgumentException(name + " is unsupported");
  }
}
