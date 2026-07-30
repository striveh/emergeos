package io.emergeos.api;

import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-drafts")
class AgentDraftController {

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
          .body(response);
    }
    return ResponseEntity.unprocessableContent().body(response);
  }

  record AgentDraftRequest(String captureId, String intent) {}

  record AgentDraftResponse(ResultEnvelope result, List<TraceResponse> trace) {

    static AgentDraftResponse from(AgentDraftOutcome outcome) {
      return new AgentDraftResponse(
          outcome.result(), outcome.trace().stream().map(TraceResponse::from).toList());
    }
  }

  record TraceResponse(
      int sequence,
      AgentTraceEventType type,
      String toolName,
      String status,
      String reference) {

    static TraceResponse from(AgentTraceEvent event) {
      return new TraceResponse(
          event.sequence(),
          event.type(),
          event.toolName(),
          event.status(),
          event.reference());
    }
  }
}
