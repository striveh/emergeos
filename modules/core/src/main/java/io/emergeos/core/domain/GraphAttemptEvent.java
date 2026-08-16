package io.emergeos.core.domain;

import io.emergeos.contracts.CanonicalIntegrity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One immutable, canonical event in a graph-attempt hash chain. */
public record GraphAttemptEvent(
    int sequence,
    GraphAttemptEventType type,
    Instant occurredAt,
    GraphAttemptPhase phaseFrom,
    GraphAttemptPhase phaseTo,
    GraphRunRole role,
    String runId,
    String taskId,
    String actor,
    String challengeHash,
    Integer requestOrdinal,
    String requestHash,
    String modelRequested,
    String evidenceHash,
    String previousHeadHash,
    String eventHash,
    String currentHeadHash) {

  private static final String EMPTY_DOMAIN =
      "emergeos.graph-attempt-journal-empty.v1";
  private static final String EVENT_DOMAIN =
      "emergeos.graph-attempt-event.v1";
  private static final String TERMINAL_EVENT_DOMAIN =
      "emergeos.graph-attempt-event.v2";
  private static final String CHAIN_DOMAIN =
      "emergeos.graph-attempt-journal.v1";

  public GraphAttemptEvent {
    if (sequence < 1 || sequence > 1_000_000) {
      throw new IllegalArgumentException(
          "graph event sequence is invalid");
    }
    type = Objects.requireNonNull(type, "type");
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    phaseTo = Objects.requireNonNull(phaseTo, "phaseTo");
    runId =
        runId == null
            ? null
            : GraphAttemptDomains.runIdentifier(runId, "runId");
    taskId =
        taskId == null
            ? null
            : GraphAttemptDomains.runIdentifier(taskId, "taskId");
    actor =
        actor == null
            ? null
            : GraphAttemptDomains.safeName(actor, "actor");
    challengeHash =
        challengeHash == null
            ? null
            : GraphAttemptDomains.hash(
                challengeHash, "challengeHash");
    requestHash =
        requestHash == null
            ? null
            : GraphAttemptDomains.hash(
                requestHash, "requestHash");
    modelRequested =
        modelRequested == null
            ? null
            : GraphAttemptDomains.modelIdentifier(
                modelRequested, "modelRequested");
    evidenceHash =
        evidenceHash == null
            ? null
            : GraphAttemptDomains.hash(
                evidenceHash, "evidenceHash");
    previousHeadHash =
        GraphAttemptDomains.hash(
            previousHeadHash, "previousHeadHash");
    eventHash =
        GraphAttemptDomains.hash(eventHash, "eventHash");
    currentHeadHash =
        GraphAttemptDomains.hash(
            currentHeadHash, "currentHeadHash");
    requireShape(
        type,
        phaseFrom,
        phaseTo,
        role,
        runId,
        taskId,
        actor,
        challengeHash,
        requestOrdinal,
        requestHash,
        modelRequested,
        evidenceHash);
    String computedEvent =
        computeEventHash(
            sequence,
            type,
            occurredAt,
            phaseFrom,
            phaseTo,
            role,
            runId,
            taskId,
            actor,
            challengeHash,
            requestOrdinal,
            requestHash,
            modelRequested,
            evidenceHash,
            previousHeadHash);
    if (!computedEvent.equals(eventHash)
        || !CanonicalIntegrity.chain(
                CHAIN_DOMAIN, previousHeadHash, eventHash)
            .equals(currentHeadHash)) {
      throw new IllegalArgumentException(
          "graph event hash chain is inconsistent");
    }
  }

  public static GraphAttemptEvent claimed(
      GraphAttemptManifest manifest, Instant occurredAt) {
    Objects.requireNonNull(manifest, "manifest");
    String previous =
        emptyHead(manifest.attemptId(), manifest.manifestHash());
    return create(
        1,
        GraphAttemptEventType.ATTEMPT_CLAIMED,
        occurredAt,
        null,
        GraphAttemptPhase.MARKED,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        previous);
  }

  public static GraphAttemptEvent next(
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      Instant occurredAt,
      GraphAttemptPhase phaseTo,
      GraphRunRole role,
      String runId,
      String taskId,
      GraphOperatorApproval approval,
      GraphProviderIntent providerIntent) {
    Objects.requireNonNull(cursor, "cursor");
    return create(
        cursor.lastSequence() + 1,
        type,
        occurredAt,
        cursor.phase(),
        phaseTo,
        role,
        runId,
        taskId,
        approval == null ? null : approval.actor(),
        approval == null ? null : approval.challengeHash(),
        providerIntent == null
            ? null
            : providerIntent.requestOrdinal(),
        providerIntent == null
            ? null
            : providerIntent.requestHash(),
        providerIntent == null
            ? null
            : providerIntent.modelRequested(),
        null,
        cursor.headHash());
  }

  public static GraphAttemptEvent providerAttributed(
      GraphAttemptCursor cursor,
      GraphRunSelection child,
      GraphProviderAttribution attribution,
      Instant occurredAt) {
    Objects.requireNonNull(child, "child");
    Objects.requireNonNull(attribution, "attribution");
    if (child.role() != GraphRunRole.CHILD) {
      throw new IllegalArgumentException(
          "provider attribution belongs to the child Run");
    }
    return create(
        cursor.lastSequence() + 1,
        GraphAttemptEventType.PROVIDER_ATTRIBUTED,
        occurredAt,
        cursor.phase(),
        GraphAttemptPhase.PROVIDER_ATTRIBUTED,
        child.role(),
        child.runId(),
        child.taskId(),
        null,
        null,
        attribution.requestOrdinal(),
        null,
        null,
        attribution.attributionHash(),
        cursor.headHash());
  }

  public static GraphAttemptEvent terminal(
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      GraphAttemptPhase phaseTo,
      GraphRunSelection selection,
      GraphTerminalBinding binding,
      Instant occurredAt) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(binding, "binding");
    if ((type != GraphAttemptEventType.CHILD_TERMINAL
            && type != GraphAttemptEventType.PARENT_TERMINAL)
        || selection.role() != binding.role()
        || !selection.runId().equals(binding.runId())
        || !selection.taskId().equals(binding.taskId())) {
      throw new IllegalArgumentException(
          "terminal event does not match its exact graph binding");
    }
    return create(
        cursor.lastSequence() + 1,
        type,
        occurredAt,
        cursor.phase(),
        phaseTo,
        selection.role(),
        selection.runId(),
        selection.taskId(),
        null,
        null,
        null,
        null,
        null,
        binding.terminalHash(),
        cursor.headHash());
  }

  public static GraphAttemptEvent sealed(
      GraphAttemptCursor cursor,
      String sealHash,
      Instant occurredAt) {
    return create(
        cursor.lastSequence() + 1,
        GraphAttemptEventType.TERMINAL_SEALED,
        occurredAt,
        cursor.phase(),
        GraphAttemptPhase.TERMINAL,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        sealHash,
        cursor.headHash());
  }

  public GraphAttemptCursor cursor(
      GraphAttemptManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    return new GraphAttemptCursor(
        manifest.principalId(),
        manifest.attemptId(),
        manifest.manifestHash(),
        sequence,
        sequence,
        currentHeadHash,
        phaseTo);
  }

  public GraphAttemptCursor advance(
      GraphAttemptCursor previous) {
    Objects.requireNonNull(previous, "previous");
    if (sequence != previous.lastSequence() + 1
        || !previous.headHash().equals(previousHeadHash)) {
      throw new IllegalArgumentException(
          "event does not advance the expected graph cursor");
    }
    return new GraphAttemptCursor(
        previous.principalId(),
        previous.attemptId(),
        previous.manifestHash(),
        sequence,
        sequence,
        currentHeadHash,
        phaseTo);
  }

  public static String emptyHead(
      String attemptId, String manifestHash) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put(
        "attemptId",
        GraphAttemptDomains.hash(attemptId, "attemptId"));
    material.put(
        "manifestHash",
        GraphAttemptDomains.hash(
            manifestHash, "manifestHash"));
    return CanonicalIntegrity.hash(EMPTY_DOMAIN, material);
  }

  private static GraphAttemptEvent create(
      int sequence,
      GraphAttemptEventType type,
      Instant occurredAt,
      GraphAttemptPhase phaseFrom,
      GraphAttemptPhase phaseTo,
      GraphRunRole role,
      String runId,
      String taskId,
      String actor,
      String challengeHash,
      Integer requestOrdinal,
      String requestHash,
      String modelRequested,
      String evidenceHash,
      String previousHeadHash) {
    String eventHash =
        computeEventHash(
            sequence,
            type,
            occurredAt,
            phaseFrom,
            phaseTo,
            role,
            runId,
            taskId,
            actor,
            challengeHash,
            requestOrdinal,
            requestHash,
            modelRequested,
            evidenceHash,
            previousHeadHash);
    String currentHeadHash =
        CanonicalIntegrity.chain(
            CHAIN_DOMAIN, previousHeadHash, eventHash);
    return new GraphAttemptEvent(
        sequence,
        type,
        occurredAt,
        phaseFrom,
        phaseTo,
        role,
        runId,
        taskId,
        actor,
        challengeHash,
        requestOrdinal,
        requestHash,
        modelRequested,
        evidenceHash,
        previousHeadHash,
        eventHash,
        currentHeadHash);
  }

  private static String computeEventHash(
      int sequence,
      GraphAttemptEventType type,
      Instant occurredAt,
      GraphAttemptPhase phaseFrom,
      GraphAttemptPhase phaseTo,
      GraphRunRole role,
      String runId,
      String taskId,
      String actor,
      String challengeHash,
      Integer requestOrdinal,
      String requestHash,
      String modelRequested,
      String evidenceHash,
      String previousHeadHash) {
    Map<String, Object> material = new LinkedHashMap<>();
    material.put("modelRequested", modelRequested);
    material.put("actor", actor);
    material.put("challengeHash", challengeHash);
    material.put("occurredAt", occurredAt);
    material.put("phaseFrom", phaseFrom);
    material.put("phaseTo", phaseTo);
    material.put("previousHeadHash", previousHeadHash);
    material.put("requestHash", requestHash);
    material.put("requestOrdinal", requestOrdinal);
    material.put("role", role);
    material.put("runId", runId);
    material.put("sequence", sequence);
    material.put("taskId", taskId);
    material.put("type", type);
    if (evidenceHash == null) {
      return CanonicalIntegrity.hash(EVENT_DOMAIN, material);
    }
    material.put("evidenceHash", evidenceHash);
    return CanonicalIntegrity.hash(
        TERMINAL_EVENT_DOMAIN, material);
  }

  private static void requireShape(
      GraphAttemptEventType type,
      GraphAttemptPhase from,
      GraphAttemptPhase to,
      GraphRunRole role,
      String runId,
      String taskId,
      String actor,
      String challengeHash,
      Integer ordinal,
      String requestHash,
      String modelRequested,
      String evidenceHash) {
    boolean runFields =
        role != null && runId != null && taskId != null;
    boolean requestFields =
        ordinal != null
            && requestHash != null
            && modelRequested != null;
    boolean noRunFields =
        role == null && runId == null && taskId == null;
    boolean noRequestFields =
        ordinal == null
            && requestHash == null
            && modelRequested == null;
    boolean attributionFields =
        ordinal != null
            && requestHash == null
            && modelRequested == null
            && evidenceHash != null;
    boolean evidenceOnly =
        ordinal == null
            && requestHash == null
            && modelRequested == null
            && evidenceHash != null;
    boolean noEvidence = evidenceHash == null;
    boolean approvalFields =
        actor != null && challengeHash != null;
    boolean noApprovalFields =
        actor == null && challengeHash == null;
    boolean valid =
        switch (type) {
          case ATTEMPT_CLAIMED ->
              from == null
                  && to == GraphAttemptPhase.MARKED
                  && noRunFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case OPERATOR_APPROVED ->
              from == GraphAttemptPhase.MARKED
                  && to
                      == GraphAttemptPhase.OPERATOR_APPROVED
                  && noRunFields
                  && noRequestFields
                  && noEvidence
                  && approvalFields;
          case PARENT_AUTHORIZED ->
              from == GraphAttemptPhase.OPERATOR_APPROVED
                  && to
                      == GraphAttemptPhase.PARENT_AUTHORIZED
                  && role == GraphRunRole.PARENT
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case PARENT_STARTED ->
              from == GraphAttemptPhase.PARENT_AUTHORIZED
                  && to == GraphAttemptPhase.PARENT_RUNNING
                  && role == GraphRunRole.PARENT
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case CHILD_AUTHORIZED ->
              from == GraphAttemptPhase.PARENT_RUNNING
                  && to
                      == GraphAttemptPhase.CHILD_AUTHORIZED
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case CHILD_STARTED ->
              from == GraphAttemptPhase.CHILD_AUTHORIZED
                  && to == GraphAttemptPhase.CHILD_RUNNING
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case CHILD_EGRESS_CONSUMED ->
              from == GraphAttemptPhase.CHILD_RUNNING
                  && to == GraphAttemptPhase.EGRESS_CONSUMED
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case CREDENTIAL_READ_STARTED ->
              from == GraphAttemptPhase.EGRESS_CONSUMED
                  && to == GraphAttemptPhase.CREDENTIAL_READING
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case CLIENT_CREATED ->
              from == GraphAttemptPhase.CREDENTIAL_READING
                  && to == GraphAttemptPhase.CLIENT_READY
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case MODEL_CREATED ->
              from == GraphAttemptPhase.CLIENT_READY
                  && to == GraphAttemptPhase.MODEL_READY
                  && role == GraphRunRole.CHILD
                  && runFields
                  && noRequestFields
                  && noEvidence
                  && noApprovalFields;
          case PROVIDER_INTENT ->
              (from == GraphAttemptPhase.MODEL_READY
                      || from
                          == GraphAttemptPhase.PROVIDER_ATTRIBUTED)
                  && to == GraphAttemptPhase.PROVIDER_PENDING
                  && role == GraphRunRole.CHILD
                  && runFields
                  && requestFields
                  && (ordinal == 1 || ordinal == 2)
                  && noEvidence
                  && noApprovalFields;
          case PROVIDER_ATTRIBUTED ->
              from == GraphAttemptPhase.PROVIDER_PENDING
                  && to
                      == GraphAttemptPhase.PROVIDER_ATTRIBUTED
                  && role == GraphRunRole.CHILD
                  && runFields
                  && attributionFields
                  && (ordinal == 1 || ordinal == 2)
                  && noApprovalFields;
          case CHILD_TERMINAL ->
              from == GraphAttemptPhase.PROVIDER_ATTRIBUTED
                  && to == GraphAttemptPhase.CHILD_TERMINAL
                  && role == GraphRunRole.CHILD
                  && runFields
                  && evidenceOnly
                  && noApprovalFields;
          case PARENT_TERMINAL ->
              from == GraphAttemptPhase.CHILD_TERMINAL
                  && to == GraphAttemptPhase.PARENT_TERMINAL
                  && role == GraphRunRole.PARENT
                  && runFields
                  && evidenceOnly
                  && noApprovalFields;
          case TERMINAL_SEALED ->
              from == GraphAttemptPhase.PARENT_TERMINAL
                  && to == GraphAttemptPhase.TERMINAL
                  && noRunFields
                  && evidenceOnly
                  && noApprovalFields;
        };
    if (!valid) {
      throw new IllegalArgumentException(
          "graph event fields do not match its semantic transition");
    }
  }

}
