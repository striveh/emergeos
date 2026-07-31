package io.emergeos.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fully re-verified read model from one repeatable-read snapshot. */
public record GraphAttemptSnapshot(
    GraphAttemptManifest manifest,
    GraphAttemptCursor cursor,
    List<GraphAttemptEvent> events,
    AgentRun parentRun,
    AgentRun childRun,
    boolean terminalSealPresent,
    GraphAttemptOutcome outcome,
    GraphBillingStatus billingStatus) {

  private static final List<GraphAttemptEventType> FIRST_PREFIX =
      List.of(
          GraphAttemptEventType.ATTEMPT_CLAIMED,
          GraphAttemptEventType.OPERATOR_APPROVED,
          GraphAttemptEventType.PARENT_AUTHORIZED,
          GraphAttemptEventType.PARENT_STARTED,
          GraphAttemptEventType.CHILD_AUTHORIZED,
          GraphAttemptEventType.CHILD_STARTED,
          GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
          GraphAttemptEventType.CREDENTIAL_READ_STARTED,
          GraphAttemptEventType.CLIENT_CREATED,
          GraphAttemptEventType.MODEL_CREATED,
          GraphAttemptEventType.PROVIDER_INTENT);

  public GraphAttemptSnapshot {
    manifest = Objects.requireNonNull(manifest, "manifest");
    cursor = Objects.requireNonNull(cursor, "cursor");
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    outcome = Objects.requireNonNull(outcome, "outcome");
    billingStatus =
        Objects.requireNonNull(billingStatus, "billingStatus");
    if (events.isEmpty()
        || !manifest.principalId().equals(cursor.principalId())
        || !manifest.attemptId().equals(cursor.attemptId())
        || !manifest.manifestHash().equals(cursor.manifestHash())
        || events.size() != cursor.lastSequence()
        || !events.getLast().currentHeadHash().equals(
            cursor.headHash())
        || events.getLast().phaseTo() != cursor.phase()) {
      throw new IllegalArgumentException(
          "graph snapshot does not match its durable head");
    }
    String expectedPrevious =
        GraphAttemptEvent.emptyHead(
            manifest.attemptId(), manifest.manifestHash());
    Instant previousTime = null;
    for (int index = 0; index < events.size(); index++) {
      GraphAttemptEvent event = events.get(index);
      if (event.sequence() != index + 1
          || !event.previousHeadHash().equals(expectedPrevious)
          || index >= FIRST_PREFIX.size()
          || event.type() != FIRST_PREFIX.get(index)
          || (previousTime != null
              && event.occurredAt().isBefore(previousTime))) {
        throw new IllegalArgumentException(
            "graph snapshot event chain is discontinuous");
      }
      expectedPrevious = event.currentHeadHash();
      previousTime = event.occurredAt();
    }
    if (outcome != GraphAttemptOutcome.INCOMPLETE) {
      throw new IllegalArgumentException(
          "the first durable graph prefix is always incomplete");
    }
    if (terminalSealPresent) {
      throw new IllegalArgumentException(
          "the first durable graph prefix cannot carry a terminal seal");
    }
    if (deriveBilling(events) != billingStatus) {
      throw new IllegalArgumentException(
          "graph snapshot billing status is not conservative");
    }
    requireRun(
        manifest.parentSelection(),
        parentRun,
        events.stream()
            .anyMatch(
                event ->
                    event.type()
                        == GraphAttemptEventType.PARENT_STARTED));
    requireRun(
        manifest.childSelection(),
        childRun,
        events.stream()
            .anyMatch(
                event ->
                    event.type()
                        == GraphAttemptEventType.CHILD_STARTED));
  }

  public String verdict() {
    return "VALID";
  }

  public static GraphBillingStatus deriveBilling(
      List<GraphAttemptEvent> events) {
    return events.stream()
            .anyMatch(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_INTENT)
        ? GraphBillingStatus.UNKNOWN
        : GraphBillingStatus.NOT_INVOKED;
  }

  private static void requireRun(
      GraphRunSelection selection,
      AgentRun run,
      boolean mustExist) {
    if (!mustExist) {
      if (run != null) {
        throw new IllegalArgumentException(
            "graph Run exists before its STARTED event");
      }
      return;
    }
    if (run == null
        || run.lifecycle() != AgentRunLifecycle.RUNNING
        || !selection.runId().equals(run.runId())
        || !selection.taskId().equals(run.task().id())
        || !selection.taskHash().equals(
            io.emergeos.contracts.IntegrityHashes.taskHash(
                run.task()))) {
      throw new IllegalArgumentException(
          "graph Run does not match its exact durable selection");
    }
  }
}
