package io.emergeos.api;

import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.port.AgentRunStore;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-runs")
class AgentRunController {

  private static final String PRIVATE_NO_STORE = "private, no-store";

  private final AgentRunStore runs;
  private final String prototypePrincipalId;

  AgentRunController(
      AgentRunStore runs,
      @Value("${emerge.prototype.principal-id}") String prototypePrincipalId) {
    this.runs = runs;
    this.prototypePrincipalId = prototypePrincipalId;
  }

  @GetMapping("/{runId}")
  ResponseEntity<AgentRunResponse> get(@PathVariable String runId) {
    AgentRun run = requireOwned(runId);
    return privateResponse(AgentRunResponse.from(run));
  }

  @GetMapping("/{runId}/trace")
  ResponseEntity<AgentTraceEnvelope> trace(@PathVariable String runId) {
    AgentRun run = requireCompleted(runId);
    return privateResponse(run.trace());
  }

  @GetMapping("/{runId}/bundle")
  ResponseEntity<HarnessRunBundle> bundle(@PathVariable String runId) {
    AgentRun run = requireCompleted(runId);
    return privateResponse(run.bundle());
  }

  private AgentRun requireCompleted(String runId) {
    AgentRun run = requireOwned(runId);
    if (run.lifecycle() == AgentRunLifecycle.RUNNING) {
      throw new AgentRunIncompleteException();
    }
    return run;
  }

  private AgentRun requireOwned(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")) {
      throw new AgentRunNotFoundException();
    }
    return runs
        .findOwned(prototypePrincipalId, runId)
        .orElseThrow(AgentRunNotFoundException::new);
  }

  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
        .body(body);
  }

  record AgentRunResponse(
      String schemaVersion,
      String runId,
      String taskId,
      AgentRunLifecycle state,
      TaskEnvelope task,
      ResultEnvelope result,
      String traceRef,
      String bundleRef,
      Instant startedAt,
      Instant completedAt) {

    static AgentRunResponse from(AgentRun run) {
      String runPath = "/api/v1/agent-runs/" + run.runId();
      boolean completed = run.lifecycle().terminal();
      return new AgentRunResponse(
          "1.0",
          run.runId(),
          run.task().id(),
          run.lifecycle(),
          run.task(),
          run.result(),
          completed ? runPath + "/trace" : null,
          completed ? runPath + "/bundle" : null,
          run.startedAt(),
          run.completedAt());
    }
  }
}
