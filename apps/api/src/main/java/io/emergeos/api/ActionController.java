package io.emergeos.api;

import io.emergeos.core.application.ApproveLocalActionCommand;
import io.emergeos.core.application.PlanLocalApprovalCommand;
import io.emergeos.core.application.RecoverableActionService;
import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionApprovalScope;
import io.emergeos.core.domain.ActionReceipt;
import io.emergeos.core.domain.ActionTransition;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ActionController {

  private final RecoverableActionService actions;

  ActionController(RecoverableActionService actions) {
    this.actions = actions;
  }

  @PostMapping("/artifacts/{artifactId}/actions")
  ResponseEntity<ActionResponse> approve(
      @PathVariable("artifactId") String artifactId,
      @RequestBody ApproveActionRequest request) {
    ActionAttempt attempt =
        actions.approve(
            new ApproveLocalActionCommand(
                artifactId,
                request.approvedArtifactHash(),
                request.idempotencyKey()));
    return response(attempt);
  }

  @PostMapping("/artifacts/{artifactId}/action-approvals")
  ResponseEntity<ApprovalActionResponse> planApproval(
      @PathVariable("artifactId") String artifactId,
      @RequestBody PlanApprovalRequest request) {
    RecoverableActionService.PlannedApproval approval =
        actions.planApproval(
            new PlanLocalApprovalCommand(
                artifactId,
                request.approvedArtifactVersion(),
                request.approvedArtifactHash(),
                request.approvalNonce(),
                request.approvedScopeSchema(),
                request.approvedScopeHash()));
    ApprovalActionResponse body = ApprovalActionResponse.from(approval.attempt());
    URI location = URI.create("/api/v1/action-approvals/" + approval.attempt().attemptId());
    return approval.created()
        ? ResponseEntity.created(location).body(body)
        : ResponseEntity.ok().location(location).body(body);
  }

  @GetMapping("/artifacts/{artifactId}/action-approval-scope")
  ApprovalScopeResponse previewApprovalScope(
      @PathVariable("artifactId") String artifactId,
      @RequestParam("artifactVersion") int artifactVersion,
      @RequestParam("artifactHash") String artifactHash) {
    return ApprovalScopeResponse.from(
        actions.previewApprovalScope(artifactId, artifactVersion, artifactHash));
  }

  @GetMapping("/action-approvals/{attemptId}")
  ApprovalActionResponse getApproval(@PathVariable("attemptId") String attemptId) {
    return ApprovalActionResponse.from(actions.getPlannedExplicitApproval(attemptId));
  }

  @GetMapping("/actions/{attemptId}")
  ActionResponse get(@PathVariable("attemptId") String attemptId) {
    return ActionResponse.from(actions.get(attemptId));
  }

  @PostMapping("/actions/{attemptId}/reconcile")
  ResponseEntity<ActionResponse> reconcile(
      @PathVariable("attemptId") String attemptId) {
    return response(actions.reconcile(attemptId));
  }

  private static ResponseEntity<ActionResponse> response(ActionAttempt attempt) {
    ActionResponse body = ActionResponse.from(attempt);
    return attempt.status().isTerminal()
        ? ResponseEntity.ok(body)
        : ResponseEntity.accepted().body(body);
  }

  record ApproveActionRequest(
      String approvedArtifactHash, String idempotencyKey) {}

  record PlanApprovalRequest(
      int approvedArtifactVersion,
      String approvedArtifactHash,
      String approvalNonce,
      String approvedScopeSchema,
      String approvedScopeHash) {}

  record ActionResponse(
      String attemptId,
      String status,
      ActionPlanResponse plan,
      CapabilityResponse capability,
      List<TransitionResponse> transitions,
      ReceiptResponse receipt) {

    static ActionResponse from(ActionAttempt attempt) {
      return new ActionResponse(
          attempt.attemptId(),
          attempt.status().name(),
          new ActionPlanResponse(
              attempt.plan().planId(),
              attempt.plan().planHash(),
              attempt.plan().artifactId(),
              attempt.plan().artifactVersion(),
              attempt.plan().artifactHash(),
              attempt.plan().idempotencyKey(),
              attempt.plan().expiresAt()),
          new CapabilityResponse(
              attempt.capability().capabilityId(),
              attempt.capabilityUsedCalls(),
              attempt.capability().maxCalls()),
          attempt.transitions().stream().map(TransitionResponse::from).toList(),
          attempt.receipt() == null ? null : ReceiptResponse.from(attempt.receipt()));
    }
  }

  record ApprovalActionResponse(
      String attemptId,
      String status,
      ActionPlanResponse plan,
      ApprovalResponse approval,
      ApprovalCapabilityResponse capability,
      List<TransitionResponse> transitions,
      ReceiptResponse receipt,
      String scopeSchema,
      String scopeHash,
      ApprovalPrincipalResponse approvalPrincipal,
      ProvenanceResponse provenance,
      ApprovalActionDetailResponse action,
      ApprovalArtifactResponse artifact,
      String executionState) {

    static ApprovalActionResponse from(ActionAttempt attempt) {
      return new ApprovalActionResponse(
          attempt.attemptId(),
          attempt.status().name(),
          new ActionPlanResponse(
              attempt.plan().planId(),
              attempt.plan().planHash(),
              attempt.plan().artifactId(),
              attempt.plan().artifactVersion(),
              attempt.plan().artifactHash(),
              attempt.plan().idempotencyKey(),
              attempt.plan().expiresAt()),
          new ApprovalResponse(
              attempt.approval().decisionId(),
              attempt.approval().decision(),
              attempt.approval().actor(),
              attempt.approval().decidedAt(),
              attempt.approval().planId(),
              attempt.approval().planHash(),
              attempt.approval().artifactHash()),
          new ApprovalCapabilityResponse(
              attempt.capability().capabilityId(),
              attempt.approvalScope().connector(),
              attempt.approvalScope().audience(),
              attempt.approvalScope().accountRef(),
              attempt.approvalScope().capabilityTtlMicros(),
              attempt.capabilityUsedCalls(),
              attempt.capability().maxCalls()),
          attempt.transitions().stream().map(TransitionResponse::from).toList(),
          attempt.receipt() == null ? null : ReceiptResponse.from(attempt.receipt()),
          attempt.approvalScope().scopeSchema(),
          attempt.approvalScope().scopeHash(),
          ApprovalPrincipalResponse.from(attempt.approvalScope()),
          ProvenanceResponse.from(attempt.approvalScope()),
          ApprovalActionDetailResponse.from(attempt.approvalScope()),
          ApprovalArtifactResponse.from(attempt.approvalScope()),
          "NOT_EXECUTED");
    }
  }

  record ApprovalScopeResponse(
      String scopeSchema,
      String scopeHash,
      ApprovalPrincipalResponse approvalPrincipal,
      ProvenanceResponse provenance,
      ApprovalActionDetailResponse action,
      ApprovalArtifactResponse artifact,
      ApprovalScopeCapabilityResponse capability,
      String executionState) {

    static ApprovalScopeResponse from(ActionApprovalScope scope) {
      return new ApprovalScopeResponse(
          scope.scopeSchema(),
          scope.scopeHash(),
          ApprovalPrincipalResponse.from(scope),
          ProvenanceResponse.from(scope),
          ApprovalActionDetailResponse.from(scope),
          ApprovalArtifactResponse.from(scope),
          new ApprovalScopeCapabilityResponse(
              scope.connector(),
              scope.audience(),
              scope.accountRef(),
              scope.capabilityTtlMicros(),
              scope.maxCalls(),
              0),
          "NOT_EXECUTED");
    }
  }

  record ApprovalPrincipalResponse(String basis, String configuredPrincipalId) {
    static ApprovalPrincipalResponse from(ActionApprovalScope scope) {
      return new ApprovalPrincipalResponse(scope.principalBasis(), scope.configuredPrincipalId());
    }
  }

  record ProvenanceResponse(String approvalOrigin, String executionRoute) {
    static ProvenanceResponse from(ActionApprovalScope scope) {
      return new ProvenanceResponse(scope.approvalOrigin(), scope.executionRoute());
    }
  }

  record ApprovalActionDetailResponse(
      String actionType, String targetRef, String risk, String policyVersion) {
    static ApprovalActionDetailResponse from(ActionApprovalScope scope) {
      return new ApprovalActionDetailResponse(
          scope.actionType(), scope.targetRef(), scope.risk().name(), scope.policyVersion());
    }
  }

  record ApprovalArtifactResponse(
      String artifactId, int artifactVersion, String artifactHash) {
    static ApprovalArtifactResponse from(ActionApprovalScope scope) {
      return new ApprovalArtifactResponse(
          scope.artifactId(), scope.artifactVersion(), scope.artifactHash());
    }
  }

  record ActionPlanResponse(
      String planId,
      String planHash,
      String artifactId,
      int artifactVersion,
      String artifactHash,
      String idempotencyKey,
      Instant expiresAt) {}

  record ApprovalResponse(
      String decisionId,
      String decision,
      String actor,
      Instant decidedAt,
      String planId,
      String planHash,
      String artifactHash) {}

  record CapabilityResponse(String capabilityId, int usedCalls, int maxCalls) {}

  record ApprovalCapabilityResponse(
      String capabilityId,
      String connector,
      String audience,
      String accountRef,
      long capabilityTtlMicros,
      int usedCalls,
      int maxCalls) {}

  record ApprovalScopeCapabilityResponse(
      String connector,
      String audience,
      String accountRef,
      long capabilityTtlMicros,
      int maxCalls,
      int usedCalls) {}

  record TransitionResponse(
      int sequence, String fromStatus, String toStatus, Instant occurredAt) {

    static TransitionResponse from(ActionTransition transition) {
      return new TransitionResponse(
          transition.sequence(),
          transition.fromStatus() == null ? null : transition.fromStatus().name(),
          transition.toStatus().name(),
          transition.occurredAt());
    }
  }

  record ReceiptResponse(
      String receiptId,
      String outcome,
      String externalId,
      String reasonCode,
      String providerRequestId,
      String rawResponseRef,
      Instant occurredAt,
      boolean simulated) {

    static ReceiptResponse from(ActionReceipt receipt) {
      return new ReceiptResponse(
          receipt.receiptId(),
          receipt.outcome().name(),
          receipt.externalId(),
          receipt.reasonCode(),
          receipt.providerRequestId(),
          receipt.rawResponseRef(),
          receipt.occurredAt(),
          receipt.simulated());
    }
  }
}
