package io.emergeos.api;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-drafts")
class AgentDraftController {

  private static final String PRIVATE_NO_STORE = "private, no-store";

  private final AgentDraftService agentDraftService;
  private final String prototypePrincipalId;

  AgentDraftController(
      AgentDraftService agentDraftService,
      @Value("${emerge.prototype.principal-id}") String prototypePrincipalId) {
    this.agentDraftService = agentDraftService;
    this.prototypePrincipalId = prototypePrincipalId;
  }

  @PostMapping
  ResponseEntity<AgentDraftResponse> draft(@RequestBody AgentDraftRequest request) {
    AgentDraftOutcome outcome =
        agentDraftService.draft(
            new AgentDraftCommand(
                prototypePrincipalId, request.captureId(), request.intent()));
    AgentDraftResponse response = AgentDraftResponse.from(outcome);
    if (outcome.result().status() == RunStatus.SUCCEEDED && outcome.artifact() != null) {
      return ResponseEntity.created(
              URI.create("/api/v1/artifacts/" + outcome.artifact().artifactId()))
          .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
          .body(response);
    }
    return ResponseEntity.unprocessableContent()
        .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
        .body(response);
  }

  record AgentDraftRequest(String captureId, String intent) {}

  record AgentDraftResponse(
      String runId,
      String runRef,
      String bundleRef,
      ResultEnvelope result,
      List<AgentTraceEntry> trace) {

    static AgentDraftResponse from(AgentDraftOutcome outcome) {
      String runPath = "/api/v1/agent-runs/" + outcome.run().runId();
      return new AgentDraftResponse(
          outcome.run().runId(),
          runPath,
          runPath + "/bundle",
          outcome.result(),
          outcome.trace());
    }
  }
}
