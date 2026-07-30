package io.emergeos.contracts;

import java.util.List;
import java.util.Objects;

public record AgentTraceEnvelope(
    String schemaVersion,
    String runId,
    String taskId,
    String integrityProfile,
    List<AgentTraceEntry> events,
    int eventCount,
    String rootHash) {

  public AgentTraceEnvelope {
    if (!"1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException("AgentTraceEnvelope supports schemaVersion 1.0");
    }
    requireId(runId, "runId");
    requireId(taskId, "taskId");
    if (!IntegrityHashes.PROFILE.equals(integrityProfile)) {
      throw new IllegalArgumentException("Unsupported Trace integrityProfile");
    }
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    if (events.size() > 128 || eventCount != events.size()) {
      throw new IllegalArgumentException("eventCount must match at most 128 events");
    }
    String root = IntegrityHashes.emptyTraceRoot();
    for (int index = 0; index < events.size(); index++) {
      AgentTraceEntry event = events.get(index);
      if (event.sequence() != index + 1 || !event.previousRootHash().equals(root)) {
        throw new IllegalArgumentException("Trace sequence and root chain must be continuous");
      }
      if ((event.type() == TraceEventType.MODEL_STEP
              || event.type() == TraceEventType.STRUCTURED_FINAL)
          && !("task://" + taskId).equals(event.reference())) {
        throw new IllegalArgumentException(
            "Task-scoped Trace events must reference the same taskId");
      }
      root = IntegrityHashes.nextTraceRoot(root, event.eventHash());
    }
    IntegrityHashes.requireHash(rootHash, "rootHash");
    if (!root.equals(rootHash)) {
      throw new IllegalArgumentException("rootHash does not match Trace events");
    }
  }

  public static AgentTraceEnvelope create(
      String schemaVersion,
      String runId,
      String taskId,
      List<AgentTraceEntry> events) {
    String root = IntegrityHashes.emptyTraceRoot();
    for (AgentTraceEntry event : events) {
      root = IntegrityHashes.nextTraceRoot(root, event.eventHash());
    }
    return new AgentTraceEnvelope(
        schemaVersion,
        runId,
        taskId,
        IntegrityHashes.PROFILE,
        events,
        events.size(),
        root);
  }

  private static void requireId(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new IllegalArgumentException(name + " has an invalid identifier");
    }
  }
}
