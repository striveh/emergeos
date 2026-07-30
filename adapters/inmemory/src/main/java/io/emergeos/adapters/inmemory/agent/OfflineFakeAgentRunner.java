package io.emergeos.adapters.inmemory.agent;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Runs the Stage 2 Fake Agent against frozen synthetic inputs without network or external effects.
 *
 * <p>Each invocation creates fresh stores so repeated executions cannot inherit hidden state. This
 * is a success-path evaluation baseline, not a product replay path: its fixed identifiers are safe
 * only because every run is isolated, and a non-success outcome is intentionally rejected.
 */
public final class OfflineFakeAgentRunner {

  public RunResult run(FrozenRunSpec spec) {
    Objects.requireNonNull(spec, "spec");
    var captures = new ReplayCaptureStore();
    Capture capture =
        new Capture(
            spec.captureId(),
            spec.principalId(),
            spec.clientNonce(),
            CaptureRequestHashes.sha256(
                spec.content(), spec.sourceType(), spec.sourceRef(), spec.dataClass()),
            spec.content(),
            spec.sourceType(),
            spec.sourceRef(),
            spec.dataClass(),
            spec.frozenTime());
    captures.saveOrFindByNonce(capture);

    var runs = new ReplayAgentRunStore();
    var ids =
        new FrozenIdGenerator(
            Map.of(
                "run", spec.runId(),
                "task", spec.taskId(),
                "art", spec.artifactId()));
    LongSupplier nanoTime = new FrozenNanoTime(spec.kernelLatencyMs());
    var tools = new AgentToolRegistry(List.of(new CaptureReadTool(captures)));
    var kernel =
        new AgentLoopKernel(
            ScriptedFakeModel.forCaptureDraft(),
            tools,
            spec.maxModelSteps(),
            spec.maxToolCalls(),
            nanoTime);
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(spec.frozenTime(), ZoneOffset.UTC));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                spec.principalId(), spec.captureId(), spec.intent()));
    if (outcome.result().status() != RunStatus.SUCCEEDED
        || outcome.artifact() == null) {
      throw new IllegalStateException(
          "offline golden runner accepts only a successful Artifact-producing run");
    }
    if (outcome.run().task().maxModelSteps() != spec.maxModelSteps()
        || outcome.run().task().maxToolCalls() != spec.maxToolCalls()
        || outcome.run().task().deadlineMs() != spec.taskDeadlineMs()) {
      throw new IllegalStateException(
          "frozen Task execution limits drifted from the task pack");
    }
    ids.requireAllConsumed();
    return new RunResult(capture, outcome.run(), outcome.artifact());
  }

  public record FrozenRunSpec(
      String principalId,
      String captureId,
      String clientNonce,
      CaptureSourceType sourceType,
      String sourceRef,
      DataClass dataClass,
      String content,
      String intent,
      String runId,
      String taskId,
      String artifactId,
      Instant frozenTime,
      long kernelLatencyMs,
      int maxModelSteps,
      int maxToolCalls,
      long taskDeadlineMs) {

    public FrozenRunSpec {
      requireText(principalId, "principalId");
      requireText(captureId, "captureId");
      requireText(clientNonce, "clientNonce");
      sourceType = Objects.requireNonNull(sourceType, "sourceType");
      requireText(sourceRef, "sourceRef");
      dataClass = Objects.requireNonNull(dataClass, "dataClass");
      requireText(content, "content");
      requireText(intent, "intent");
      requireId(runId, "runId");
      requireId(taskId, "taskId");
      requireId(artifactId, "artifactId");
      frozenTime = Objects.requireNonNull(frozenTime, "frozenTime");
      if (dataClass != DataClass.PERSONAL) {
        throw new IllegalArgumentException(
            "S2 offline golden replay requires PERSONAL until Task classification is derived");
      }
      if (maxModelSteps < 1 || maxToolCalls < 1 || taskDeadlineMs < 1) {
        throw new IllegalArgumentException(
            "model, tool and deadline limits must be positive");
      }
      if (kernelLatencyMs < 0 || kernelLatencyMs >= taskDeadlineMs) {
        throw new IllegalArgumentException(
            "kernelLatencyMs must be within the Agent deadline");
      }
    }

    private static void requireId(String value, String name) {
      if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
        throw new IllegalArgumentException(name + " has an invalid identifier");
      }
    }

    private static void requireText(String value, String name) {
      if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(name + " must be safe non-blank text");
      }
    }
  }

  public record RunResult(
      Capture capture,
      AgentRun run,
      ArtifactLineage artifact) {

    public RunResult {
      Objects.requireNonNull(capture, "capture");
      Objects.requireNonNull(run, "run");
      Objects.requireNonNull(artifact, "artifact");
    }
  }

  private static final class FrozenNanoTime implements LongSupplier {

    private final long terminalNanos;
    private boolean started;

    private FrozenNanoTime(long latencyMs) {
      this.terminalNanos = TimeUnit.MILLISECONDS.toNanos(latencyMs);
    }

    @Override
    public long getAsLong() {
      if (!started) {
        started = true;
        return 0;
      }
      return terminalNanos;
    }
  }

  private static final class FrozenIdGenerator implements IdGenerator {

    private final Map<String, String> ids;
    private final Set<String> consumed = new HashSet<>();

    private FrozenIdGenerator(Map<String, String> ids) {
      this.ids = Map.copyOf(ids);
    }

    @Override
    public String next(String prefix) {
      String id = ids.get(prefix);
      if (id == null || !consumed.add(prefix)) {
        throw new IllegalStateException("unexpected deterministic ID request: " + prefix);
      }
      return id;
    }

    private void requireAllConsumed() {
      if (!consumed.equals(ids.keySet())) {
        throw new IllegalStateException("not all frozen IDs were consumed");
      }
    }
  }

  private static final class ReplayCaptureStore implements CaptureStore {

    private final Map<String, Capture> byOwnerAndId = new HashMap<>();
    private final Map<String, Capture> byOwnerAndNonce = new HashMap<>();

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      String nonceKey = key(proposed.principalId(), proposed.clientNonce());
      Capture existing = byOwnerAndNonce.get(nonceKey);
      if (existing != null) {
        if (!existing.requestHash().equals(proposed.requestHash())) {
          throw new IllegalStateException("synthetic Capture nonce conflicts with frozen input");
        }
        return new SaveResult(existing, false);
      }
      String idKey = key(proposed.principalId(), proposed.captureId());
      if (byOwnerAndId.putIfAbsent(idKey, proposed) != null) {
        throw new IllegalStateException("duplicate synthetic Capture ID");
      }
      byOwnerAndNonce.put(nonceKey, proposed);
      return new SaveResult(proposed, true);
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      return Optional.ofNullable(byOwnerAndId.get(key(principalId, captureId)));
    }

    private static String key(String owner, String value) {
      return owner + '\0' + value;
    }
  }

  private static final class ReplayAgentRunStore implements AgentRunStore {

    private AgentRun stored;

    @Override
    public AgentRun start(AgentRun running) {
      if (stored != null || running.lifecycle() != AgentRunLifecycle.RUNNING) {
        throw new IllegalStateException("synthetic AgentRun must start exactly once");
      }
      stored = running;
      return running;
    }

    @Override
    public CompletionResult complete(
        AgentRun terminal,
        ArtifactLineage proposedArtifact) {
      if (stored == null
          || stored.lifecycle() != AgentRunLifecycle.RUNNING
          || terminal.lifecycle() == AgentRunLifecycle.RUNNING
          || !stored.runId().equals(terminal.runId())
          || !stored.principalId().equals(terminal.principalId())
          || !stored.task().equals(terminal.task())) {
        throw new IllegalStateException("synthetic AgentRun terminal transition is invalid");
      }
      stored = terminal;
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public Optional<AgentRun> findOwned(String principalId, String runId) {
      if (stored == null
          || !stored.principalId().equals(principalId)
          || !stored.runId().equals(runId)) {
        return Optional.empty();
      }
      return Optional.of(stored);
    }
  }
}
