package io.emergeos.core.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** The exact owner-approved boundary for logically undoing one local Draftbox entry. */
public record LocalDraftUndoScope(
    String principalBasis,
    String configuredPrincipalId,
    String undoOrigin,
    String executionRoute,
    String actionType,
    String targetRef,
    String draftId,
    String creationAttemptId,
    String creationReceiptId,
    String artifactId,
    int artifactVersion,
    String artifactHash,
    String expectedEffectiveState,
    String retentionMode,
    String policyVersion,
    String connector,
    String audience,
    String accountRef,
    String undoNonce,
    int maxCalls) {

  public static final String SCHEMA = "emergeos.local-draft-undo-scope.v1";
  public static final String CONFIGURED_LOCAL_PRINCIPAL = "CONFIGURED_LOCAL_PRINCIPAL";
  public static final String EXPLICIT_LOCAL_OWNER_INPUT = "EXPLICIT_LOCAL_OWNER_INPUT";
  public static final String LOCAL_DRAFTBOX_LOGICAL_UNDO_V1 =
      "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1";
  public static final String LOGICALLY_UNDO_LOCAL_DRAFT = "LOGICALLY_UNDO_LOCAL_DRAFT";
  public static final String RETENTION = "CAPTURE_ARTIFACT_HISTORY_RETAINED";
  public static final String POLICY_VERSION = "local-draft-undo-v1";
  public static final String CONNECTOR = "emergeos.local-draftbox";
  public static final String AUDIENCE = "emergeos:local-draftbox";
  public static final String ACCOUNT_PREFIX = "local-draftbox:";
  public static final int MAX_CALLS = 1;

  public LocalDraftUndoScope {
    requireExact(principalBasis, CONFIGURED_LOCAL_PRINCIPAL, "principalBasis");
    requireText(configuredPrincipalId, "configuredPrincipalId");
    requireExact(undoOrigin, EXPLICIT_LOCAL_OWNER_INPUT, "undoOrigin");
    requireExact(executionRoute, LOCAL_DRAFTBOX_LOGICAL_UNDO_V1, "executionRoute");
    requireExact(actionType, LOGICALLY_UNDO_LOCAL_DRAFT, "actionType");
    requireText(draftId, "draftId");
    requireExact(targetRef, "local://drafts/" + draftId, "targetRef");
    requireText(creationAttemptId, "creationAttemptId");
    requireText(creationReceiptId, "creationReceiptId");
    requireText(artifactId, "artifactId");
    if (artifactVersion < 1) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    requireHash(artifactHash, "artifactHash");
    requireExact(expectedEffectiveState, LocalDraft.ACTIVE, "expectedEffectiveState");
    requireExact(retentionMode, RETENTION, "retentionMode");
    requireExact(policyVersion, POLICY_VERSION, "policyVersion");
    requireExact(connector, CONNECTOR, "connector");
    requireExact(audience, AUDIENCE, "audience");
    requireExact(accountRef, ACCOUNT_PREFIX + configuredPrincipalId, "accountRef");
    requireText(undoNonce, "undoNonce");
    if (maxCalls != MAX_CALLS) {
      throw new IllegalArgumentException("maxCalls is unsupported");
    }
  }

  public static LocalDraftUndoScope forActiveDraft(
      String principalId,
      LocalDraft draft,
      LocalDraftCreationReceipt creationReceipt,
      String undoNonce) {
    requireText(principalId, "principalId");
    Objects.requireNonNull(draft, "draft");
    Objects.requireNonNull(creationReceipt, "creationReceipt");
    if (!principalId.equals(draft.principalId())
        || !creationReceipt.attemptId().equals(draft.attemptId())
        || !creationReceipt.draftId().equals(draft.draftId())) {
      throw new IllegalArgumentException(
          "logical Undo scope must bind the exact local Draftbox creation truth");
    }
    return new LocalDraftUndoScope(
        CONFIGURED_LOCAL_PRINCIPAL,
        principalId,
        EXPLICIT_LOCAL_OWNER_INPUT,
        LOCAL_DRAFTBOX_LOGICAL_UNDO_V1,
        LOGICALLY_UNDO_LOCAL_DRAFT,
        "local://drafts/" + draft.draftId(),
        draft.draftId(),
        draft.attemptId(),
        creationReceipt.receiptId(),
        draft.artifactId(),
        draft.artifactVersion(),
        draft.artifactHash(),
        LocalDraft.ACTIVE,
        RETENTION,
        POLICY_VERSION,
        CONNECTOR,
        AUDIENCE,
        ACCOUNT_PREFIX + principalId,
        undoNonce,
        MAX_CALLS);
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
        undoOrigin,
        executionRoute,
        actionType,
        targetRef,
        draftId,
        creationAttemptId,
        creationReceiptId,
        artifactId,
        Integer.toString(artifactVersion),
        artifactHash,
        expectedEffectiveState,
        retentionMode,
        policyVersion,
        connector,
        audience,
        accountRef,
        undoNonce,
        Integer.toString(maxCalls));
  }

  private static void requireText(String value, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || value.indexOf('\0') >= 0) {
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
}
