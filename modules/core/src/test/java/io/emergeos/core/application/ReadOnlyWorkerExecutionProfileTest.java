package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlyWorkerExecutionProfileTest {

  private static final String CAPTURE_REF =
      "capture://worker-profile-capture";

  @Test
  void preparationUsesTheRealProfileForControlDriftAndAuthorityFaults() {
    ReadOnlyWorkerExecutionProfile profile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    TaskEnvelope parent =
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .newDraftTask(
                "worker-profile-parent-task",
                "worker-profile-owner",
                "Create one read-only proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            profile.workerName(),
            "Create one read-only proposal",
            List.of(CAPTURE_REF));
    AgentWorkerRuntime.ExecutionWindow exactWindow =
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never());

    assertNull(
        profile.validatePreparation(parent, request, exactWindow));
    assertEquals(
        "HANDOFF_CONTEXT_POLICY_DRIFT",
        profile.validatePreparation(
            copyTask(
                parent,
                "ref-only-v2",
                parent.toolRegistryVersion(),
                parent.capabilityRefs(),
                parent.inputRefs(),
                parent.allowParallel()),
            request,
            exactWindow));
    assertEquals(
        "HANDOFF_AUTHORITY_ESCALATION",
        profile.validatePreparation(
            copyTask(
                parent,
                parent.contextPolicyVersion(),
                "agent-tools-v999",
                parent.capabilityRefs(),
                parent.inputRefs(),
                parent.allowParallel()),
            request,
            exactWindow));
    assertEquals(
        "HANDOFF_AUTHORITY_ESCALATION",
        profile.validatePreparation(
            parent,
            request,
            new AgentWorkerRuntime.ExecutionWindow(
                parent.deadlineMs() + 1,
                parent.budgetUsd().add(new BigDecimal("0.000001")),
                CancellationSignal.never())));
    assertEquals(
        "HANDOFF_NOT_ALLOWED",
        profile.validatePreparation(
            parent,
            new WorkerHandoffRequest(
                "unknown-worker",
                request.intent(),
                request.inputRefs()),
            exactWindow));
    assertEquals(
        "HANDOFF_NOT_ALLOWED",
        profile.validatePreparation(
            parent,
            new WorkerHandoffRequest(
                profile.workerName(),
                request.intent(),
                List.of(
                    CAPTURE_REF,
                    "capture://worker-profile-expanded")),
            exactWindow));
  }

  @Test
  void childTaskCanOnlyInheritOrContractParentAuthority() {
    ReadOnlyWorkerExecutionProfile profile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    TaskEnvelope parent =
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .newDraftTask(
                "worker-profile-child-parent-task",
                "worker-profile-owner",
                "Create one read-only proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            profile.workerName(),
            "Create one read-only proposal",
            List.of(CAPTURE_REF));
    TaskEnvelope child =
        profile.newChildTask(
            parent,
            request,
            new AgentWorkerRuntime.ExecutionWindow(
                parent.deadlineMs(),
                parent.budgetUsd(),
                CancellationSignal.never()),
            "worker-profile-child-task");

    assertEquals(parent.id(), child.parentId());
    assertEquals(List.of(parent.id()), child.delegationChain());
    assertEquals(RiskLevel.READ_ONLY, child.risk());
    assertEquals(parent.inputRefs(), child.inputRefs());
    assertEquals(List.of("capture.read"), child.requiredTools());
    assertEquals(List.of(), child.capabilityRefs());
    assertEquals(BigDecimal.ZERO, child.budgetUsd());
    assertEquals(parent.policyVersion(), child.policyVersion());
    assertEquals(
        parent.contextPolicyVersion(), child.contextPolicyVersion());
    assertDoesNotThrow(
        () -> profile.requireChildBinding(parent, child));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            profile.requireChildBinding(
                parent,
                copyChildShape(
                    child,
                    DataClass.PERSONAL,
                    child.modalities(),
                    child.latencyClass())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            profile.requireChildBinding(
                parent,
                copyChildShape(
                    child,
                    child.dataClass(),
                    List.of("audio"),
                    child.latencyClass())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            profile.requireChildBinding(
                parent,
                copyChildShape(
                    child,
                    child.dataClass(),
                    child.modalities(),
            "ASYNC")));
  }

  @Test
  void parentWithDirectToolAuthorityCannotPrepareOrBindAWorkerChild() {
    ReadOnlyWorkerExecutionProfile profile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    TaskEnvelope parent =
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .newDraftTask(
                "worker-profile-direct-tool-parent",
                "worker-profile-owner",
                "Create one read-only proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    TaskEnvelope expandedParent =
        copyRequiredTools(parent, List.of("capture.read"));
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            profile.workerName(),
            "Create one read-only proposal",
            List.of(CAPTURE_REF));
    AgentWorkerRuntime.ExecutionWindow window =
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never());
    TaskEnvelope child =
        profile.newChildTask(
            parent,
            request,
            window,
            "worker-profile-direct-tool-child");

    assertEquals(
        "HANDOFF_AUTHORITY_ESCALATION",
        profile.validatePreparation(expandedParent, request, window));
    assertThrows(
        IllegalArgumentException.class,
        () -> profile.requireChildBinding(expandedParent, child));
  }

  private static TaskEnvelope copyTask(
      TaskEnvelope task,
      String contextPolicyVersion,
      String toolRegistryVersion,
      List<String> capabilityRefs,
      List<String> inputRefs,
      boolean allowParallel) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        task.principalRef(),
        task.delegationChain(),
        task.kind(),
        task.intent(),
        inputRefs,
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        task.requiredTools(),
        task.outputSchema(),
        task.acceptanceChecks(),
        allowParallel,
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        contextPolicyVersion,
        toolRegistryVersion,
        task.environmentSnapshotRef(),
        capabilityRefs,
        task.unresolvedDecisions(),
        task.returnControlWhen());
  }

  private static TaskEnvelope copyChildShape(
      TaskEnvelope child,
      DataClass dataClass,
      List<String> modalities,
      String latencyClass) {
    return new TaskEnvelope(
        child.schemaVersion(),
        child.id(),
        child.parentId(),
        child.principalRef(),
        child.delegationChain(),
        child.kind(),
        child.intent(),
        child.inputRefs(),
        child.evidenceRefs(),
        modalities,
        dataClass,
        child.risk(),
        latencyClass,
        child.requiredTools(),
        child.outputSchema(),
        child.acceptanceChecks(),
        child.allowParallel(),
        child.maxModelSteps(),
        child.maxToolCalls(),
        child.deadlineMs(),
        child.budgetUsd(),
        child.modelProvider(),
        child.modelRequested(),
        child.pricingProfile(),
        child.idempotencyKey(),
        child.policyVersion(),
        child.stateVersion(),
        child.contextPolicyVersion(),
        child.toolRegistryVersion(),
        child.environmentSnapshotRef(),
        child.capabilityRefs(),
        child.unresolvedDecisions(),
            child.returnControlWhen());
  }

  private static TaskEnvelope copyRequiredTools(
      TaskEnvelope task, List<String> requiredTools) {
    return new TaskEnvelope(
        task.schemaVersion(),
        task.id(),
        task.parentId(),
        task.principalRef(),
        task.delegationChain(),
        task.kind(),
        task.intent(),
        task.inputRefs(),
        task.evidenceRefs(),
        task.modalities(),
        task.dataClass(),
        task.risk(),
        task.latencyClass(),
        requiredTools,
        task.outputSchema(),
        task.acceptanceChecks(),
        task.allowParallel(),
        task.maxModelSteps(),
        task.maxToolCalls(),
        task.deadlineMs(),
        task.budgetUsd(),
        task.modelProvider(),
        task.modelRequested(),
        task.pricingProfile(),
        task.idempotencyKey(),
        task.policyVersion(),
        task.stateVersion(),
        task.contextPolicyVersion(),
        task.toolRegistryVersion(),
        task.environmentSnapshotRef(),
        task.capabilityRefs(),
        task.unresolvedDecisions(),
        task.returnControlWhen());
  }
}
