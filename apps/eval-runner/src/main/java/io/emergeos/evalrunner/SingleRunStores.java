package io.emergeos.evalrunner;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.CaptureStore;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SingleRunStores {

  private final Capture capture;
  private final AtomicReference<AgentRun> run = new AtomicReference<>();
  private final AtomicInteger runStarts = new AtomicInteger();
  private final CaptureStore captures = new Captures();
  private final AgentRunStore runs = new Runs();
  private final Runnable runStartObserver;

  SingleRunStores(Capture capture) {
    this(capture, () -> {});
  }

  SingleRunStores(Capture capture, Runnable runStartObserver) {
    this.capture = Objects.requireNonNull(capture, "capture");
    this.runStartObserver =
        Objects.requireNonNull(runStartObserver, "runStartObserver");
    if (!SyntheticEvalCatalog.CAPTURE_ID.equals(capture.captureId())
        || !SyntheticEvalCatalog.PRINCIPAL_ID.equals(capture.principalId())
        || !SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH.equals(
            capture.requestHash())) {
      throw new IllegalArgumentException(
          "single-run store requires the frozen Capture");
    }
  }

  CaptureStore captures() {
    return captures;
  }

  AgentRunStore runs() {
    return runs;
  }

  int runStartCount() {
    return runStarts.get();
  }

  private final class Captures implements CaptureStore {

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      Objects.requireNonNull(proposed, "proposed");
      if (!capture.equals(proposed)) {
        throw new IllegalStateException(
            "single-run Capture cannot be replaced");
      }
      return new SaveResult(capture, false);
    }

    @Override
    public Optional<Capture> findOwned(
        String principalId, String captureId) {
      if (!capture.principalId().equals(principalId)
          || !capture.captureId().equals(captureId)) {
        return Optional.empty();
      }
      return Optional.of(capture);
    }
  }

  private final class Runs implements AgentRunStore {

    @Override
    public AgentRun start(AgentRun running) {
      Objects.requireNonNull(running, "running");
      if (running.lifecycle() != AgentRunLifecycle.RUNNING
          || !SyntheticEvalCatalog.RUN_ID.equals(running.runId())
          || !SyntheticEvalCatalog.PRINCIPAL_ID.equals(
              running.principalId())
          || !SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
              IntegrityHashes.taskHash(running.task()))
          || !run.compareAndSet(null, running)) {
        throw new IllegalStateException(
            "single-run AgentRun must start exactly once");
      }
      runStarts.incrementAndGet();
      runStartObserver.run();
      return running;
    }

    @Override
    public CompletionResult complete(
        AgentRun terminal, ArtifactLineage proposedArtifact) {
      Objects.requireNonNull(terminal, "terminal");
      AgentRun running = run.get();
      if (running == null
          || running.lifecycle() != AgentRunLifecycle.RUNNING
          || terminal.lifecycle() == AgentRunLifecycle.RUNNING
          || !running.runId().equals(terminal.runId())
          || !running.principalId().equals(terminal.principalId())
          || !running.task().equals(terminal.task())
          || !run.compareAndSet(running, terminal)) {
        throw new IllegalStateException(
            "single-run AgentRun terminal transition is invalid");
      }
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public Optional<AgentRun> findOwned(
        String principalId, String runId) {
      AgentRun stored = run.get();
      if (stored == null
          || !stored.principalId().equals(principalId)
          || !stored.runId().equals(runId)) {
        return Optional.empty();
      }
      return Optional.of(stored);
    }
  }
}
