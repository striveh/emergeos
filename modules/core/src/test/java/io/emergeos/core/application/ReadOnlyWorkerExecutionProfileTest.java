package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
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

  @Test
  void pack008BindsAnExternalModelOnlyToTheChildWorker() {
    ModelBoundReadOnlyWorkerExecutionProfile worker = pack008WorkerProfile();
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parent =
        parentProfile.newDraftTask(
            "pack008-parent-task",
            "pack008-owner",
            "Create one delegated synthetic draft",
            CAPTURE_REF,
            DataClass.PUBLIC);
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            worker.workerName(), parent.intent(), parent.inputRefs());
    TaskEnvelope child =
        worker.newChildTask(
            parent,
            request,
            new AgentWorkerRuntime.ExecutionWindow(
                parent.deadlineMs(),
                parent.budgetUsd(),
                CancellationSignal.never()),
            "pack008-child-task");

    assertFalse(parentProfile.modelBound());
    assertTrue(parentProfile.workerBound());
    assertTrue(parentProfile.requiresExplicitAuthorization());
    assertEquals("1.0", parent.schemaVersion());
    assertEquals(RiskLevel.EXTERNAL, parent.risk());
    assertEquals(DataClass.PUBLIC, parent.dataClass());
    assertEquals(List.of(), parent.requiredTools());
    assertEquals("agent-tools-none-v1", parent.toolRegistryVersion());
    assertNull(parent.modelProvider());
    assertEquals(worker.budgetUsd(), parent.budgetUsd());

    assertTrue(worker.modelBound());
    assertTrue(worker.requiresExactParentUsageAggregation());
    assertFalse(
        ReadOnlyWorkerExecutionProfile.pack007FakeV1()
            .requiresExactParentUsageAggregation());
    assertEquals("1.1", child.schemaVersion());
    assertEquals(parent.id(), child.parentId());
    assertEquals(List.of(parent.id()), child.delegationChain());
    assertEquals(RiskLevel.EXTERNAL, child.risk());
    assertEquals(DataClass.PUBLIC, child.dataClass());
    assertEquals(List.of("capture.read"), child.requiredTools());
    assertEquals("agent-tools-v2", child.toolRegistryVersion());
    assertEquals(
        List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
        child.capabilityRefs());
    assertEquals(worker.modelProvider(), child.modelProvider());
    assertEquals(worker.modelRequested(), child.modelRequested());
    assertEquals(worker.pricingProfile(), child.pricingProfile());
    assertEquals(worker.budgetUsd(), child.budgetUsd());
    assertEquals(worker.environmentSnapshotRef(), child.environmentSnapshotRef());
    assertTrue(child.idempotencyKey().matches("agent-task-[a-f0-9]{64}"));
    assertDoesNotThrow(() -> worker.requireTaskBinding(child));
    assertDoesNotThrow(() -> worker.requireChildBinding(parent, child));

    assertEquals(
        worker.fingerprint(),
        parentProfile
            .componentVersions(worker)
            .get("worker-profile-fingerprint"));
    assertNull(
        parentProfile
            .componentVersions(worker)
            .get("model-adapter"));
  }

  @Test
  void pack008RejectsInsufficientSubtreeBudgetBeforeChildCreation() {
    ModelBoundReadOnlyWorkerExecutionProfile worker = pack008WorkerProfile();
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parent =
        parentProfile.newDraftTask(
            "pack008-underfunded-parent",
            "pack008-owner",
            "Create one delegated synthetic draft",
            CAPTURE_REF,
            DataClass.PUBLIC);
    WorkerHandoffRequest request =
        new WorkerHandoffRequest(
            worker.workerName(), parent.intent(), parent.inputRefs());
    AgentWorkerRuntime.ExecutionWindow underfunded =
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            worker.budgetUsd().subtract(new BigDecimal("0.000001")),
            CancellationSignal.never());

    assertEquals(
        "HANDOFF_AUTHORITY_ESCALATION",
        worker.validatePreparation(parent, request, underfunded));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            worker.newChildTask(
                parent, request, underfunded, "pack008-rejected-child"));
  }

  @Test
  void pack008IdentityChangesWithAnyModelRouteAuthority() {
    ModelBoundReadOnlyWorkerExecutionProfile baseline =
        pack008WorkerProfile();
    ModelBoundReadOnlyWorkerExecutionProfile changed =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            new PricingProfile(
                "openai-gpt-5.6-sol-2026-07-v2",
                "openai.responses",
                "gpt-5.6-sol",
                5_001,
                500,
                30_000),
            "openai-responses-v1-openai-java-4.43.0",
            "f".repeat(64),
            "environment://sha256:" + "a".repeat(64),
            new HarnessExperiment("openai-worker-h0", 1),
            1_000,
            200,
            new BigDecimal("0.022002"));

    assertNotEquals(baseline.fingerprint(), changed.fingerprint());
    assertNotEquals(
        baseline.taskIdempotencyKey("pack008-child-task"),
        changed.taskIdempotencyKey("pack008-child-task"));
  }

  @Test
  void pack008ModelRouteRejectsUnresolvedHumanDecisions() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        pack008WorkerProfile();
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parent =
        parentProfile.newDraftTask(
            "pack008-decision-parent",
            "pack008-owner",
            "Create one delegated synthetic draft",
            CAPTURE_REF,
            DataClass.PUBLIC);
    TaskEnvelope child =
        worker.newChildTask(
            parent,
            new WorkerHandoffRequest(
                worker.workerName(), parent.intent(), parent.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                parent.deadlineMs(),
                parent.budgetUsd(),
                CancellationSignal.never()),
            "pack008-decision-child");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            worker.requireTaskBinding(
                copyUnresolvedDecisions(
                    child, List.of("owner must choose a public claim"))));
  }

  @Test
  void pack007CustomContextProfileRemainsBindable() {
    ReadOnlyWorkerExecutionProfile worker =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1("ref-only-v2");
    AgentExecutionProfile parent =
        AgentExecutionProfile.readOnlyWorkerFakeV1("ref-only-v2");

    assertDoesNotThrow(
        () -> worker.requireParentProfileBinding(parent));
    assertEquals(
        worker.fingerprint(),
        parent
            .componentVersions(worker)
            .get("worker-profile-fingerprint"));
  }

  @Test
  void pack007FrozenIdentityRemainsUnchanged() {
    ReadOnlyWorkerExecutionProfile profile =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();

    assertFalse(profile.modelBound());
    assertFalse(
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .requiresExplicitAuthorization());
    assertEquals(
        "e5f705bcc49d3e0302ab0a159f18ad597df01199fc7c0594e375b40011d519ac",
        profile.fingerprint());
  }

  private static ModelBoundReadOnlyWorkerExecutionProfile
      pack008WorkerProfile() {
    return ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
        new PricingProfile(
            "openai-gpt-5.6-sol-2026-07-v1",
            "openai.responses",
            "gpt-5.6-sol",
            5_000,
            500,
            30_000),
        "openai-responses-v1-openai-java-4.43.0",
        "f".repeat(64),
        "environment://sha256:" + "a".repeat(64),
        new HarnessExperiment("openai-worker-h0", 1),
        1_000,
        200,
        new BigDecimal("0.022000"));
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

  private static TaskEnvelope copyUnresolvedDecisions(
      TaskEnvelope task, List<String> unresolvedDecisions) {
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
        task.requiredTools(),
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
        unresolvedDecisions,
        task.returnControlWhen());
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
