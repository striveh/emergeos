package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadOnlyWorkerProfileRegistryTest {

  private static final String CAPTURE_REF =
      "capture://worker-profile-registry";

  @Test
  void resolvesHistoricalPack007AndPack008WithoutCurrentProfileSubstitution() {
    ReadOnlyWorkerExecutionProfile pack007 =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    ModelBoundReadOnlyWorkerExecutionProfile pack008 = pack008();
    ReadOnlyWorkerProfileRegistry registry =
        ReadOnlyWorkerProfileRegistry.of(pack007, pack008);

    TaskEnvelope parent007 =
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .newDraftTask(
                "registry-parent-007",
                "registry-owner",
                "Create an offline proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    TaskEnvelope child007 =
        child(pack007, parent007, "registry-child-007");
    TaskEnvelope parent008 =
        AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
            .newDraftTask(
                "registry-parent-008",
                "registry-owner",
                "Create a model proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    TaskEnvelope child008 =
        child(pack008, parent008, "registry-child-008");

    assertEquals(pack007, registry.requireForRunningParent(parent007));
    assertEquals(pack008, registry.requireForRunningParent(parent008));
    assertEquals(pack007, registry.requireForChild(parent007, child007));
    assertEquals(pack008, registry.requireForChild(parent008, child008));
    assertEquals(
        pack007,
        registry.requireForTerminalParent(
            parent007,
            null,
            pack007.parentExecutionProfile().harnessVersion(),
            AgentExecutionProfile.readOnlyWorkerFakeV1()
                .componentVersions(pack007)));
    assertEquals(
        pack008,
        registry.requireForTerminalParent(
            parent008,
            pack008.experiment(),
            pack008.parentExecutionProfile().harnessVersion(),
            pack008.expectedParentComponentVersions()));
    assertEquals(
        pack007,
        registry.requireForTerminalChild(
            parent007,
            child007,
            null,
            pack007.harnessVersion(),
            pack007.componentVersions()));
    assertEquals(
        pack008,
        registry.requireForTerminalChild(
            parent008,
            child008,
            pack008.experiment(),
            pack008.harnessVersion(),
            pack008.componentVersions()));
  }

  @Test
  void terminalResolutionRejectsUnknownOrCrossProfileIdentity() {
    ReadOnlyWorkerExecutionProfile pack007 =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    ModelBoundReadOnlyWorkerExecutionProfile pack008 = pack008();
    ReadOnlyWorkerProfileRegistry registry =
        ReadOnlyWorkerProfileRegistry.of(pack007, pack008);
    TaskEnvelope parent008 =
        AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
            .newDraftTask(
                "registry-terminal-parent",
                "registry-owner",
                "Create a model proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);
    Map<String, String> unknown =
        new LinkedHashMap<>(
            AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
                .componentVersions(pack008));
    unknown.put("worker-profile-fingerprint", "0".repeat(64));
    Map<String, String> expandedParent =
        new LinkedHashMap<>(
            AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
                .componentVersions(pack008));
    expandedParent.put(
        "model-adapter", "forbidden-parent-model-route");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.requireForTerminalParent(
                parent008,
                pack008.experiment(),
                pack008.parentExecutionProfile().harnessVersion(),
                unknown));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.requireForTerminalParent(
                parent008,
                new HarnessExperiment("openai-worker-drift", 1),
                pack008.parentExecutionProfile().harnessVersion(),
                AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
                    .componentVersions(pack008)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.requireForTerminalParent(
                parent008,
                pack008.experiment(),
                pack008.parentExecutionProfile().harnessVersion(),
                AgentExecutionProfile.readOnlyWorkerFakeV1()
                    .componentVersions(pack007)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.requireForTerminalParent(
                parent008,
                pack008.experiment(),
                pack008.parentExecutionProfile().harnessVersion(),
                expandedParent));
  }

  @Test
  void ambiguousRunningParentFailsClosed() {
    ReadOnlyWorkerExecutionProfile first =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    ReadOnlyWorkerExecutionProfile second =
        new ReadOnlyWorkerExecutionProfile(
            first.id(),
            "agent-workers-v1-ambiguous",
            first.workerName(),
            first.maxModelSteps(),
            first.maxToolCalls(),
            first.maxDeadlineMs(),
            "agent-draft-worker-v1-ambiguous",
            first.verifierVersion(),
            first.harnessVersion(),
            first.expectedContextPolicyVersion(),
            first.expectedToolRegistryVersion());
    ReadOnlyWorkerProfileRegistry registry =
        ReadOnlyWorkerProfileRegistry.of(first, second);
    TaskEnvelope parent =
        AgentExecutionProfile.readOnlyWorkerFakeV1()
            .newDraftTask(
                "registry-ambiguous-parent",
                "registry-owner",
                "Create an offline proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);

    assertThrows(
        IllegalArgumentException.class,
        () -> registry.requireForRunningParent(parent));
  }

  @Test
  void registrationOrderDoesNotChangeExactResolution() {
    ReadOnlyWorkerExecutionProfile pack007 =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();
    ModelBoundReadOnlyWorkerExecutionProfile pack008 = pack008();
    ReadOnlyWorkerProfileRegistry forward =
        ReadOnlyWorkerProfileRegistry.of(pack007, pack008);
    ReadOnlyWorkerProfileRegistry reverse =
        ReadOnlyWorkerProfileRegistry.of(pack008, pack007);
    TaskEnvelope parent008 =
        AgentExecutionProfile.readOnlyWorkerModelV1(pack008)
            .newDraftTask(
                "registry-order-parent",
                "registry-owner",
                "Create a model proposal",
                CAPTURE_REF,
                DataClass.PUBLIC);

    assertEquals(pack008, forward.requireForRunningParent(parent008));
    assertEquals(pack008, reverse.requireForRunningParent(parent008));
  }

  @Test
  void emptyOrDuplicateRegistrationFailsClosed() {
    ReadOnlyWorkerExecutionProfile pack007 =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1();

    assertThrows(
        IllegalArgumentException.class,
        ReadOnlyWorkerProfileRegistry::of);
    assertThrows(
        IllegalArgumentException.class,
        () -> ReadOnlyWorkerProfileRegistry.of(pack007, pack007));
  }

  private static TaskEnvelope child(
      ReadOnlyWorkerProfile profile,
      TaskEnvelope parent,
      String childTaskId) {
    return profile.newChildTask(
        parent,
        new WorkerHandoffRequest(
            profile.workerName(), parent.intent(), parent.inputRefs()),
        new AgentWorkerRuntime.ExecutionWindow(
            parent.deadlineMs(),
            parent.budgetUsd(),
            CancellationSignal.never()),
        childTaskId);
  }

  private static ModelBoundReadOnlyWorkerExecutionProfile pack008() {
    return ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
        new PricingProfile(
            "registry-openai-v1",
            "openai.responses",
            "gpt-5.6",
            5_000,
            500,
            30_000),
        "openai-responses-v1-openai-java-4.43.0",
        "f".repeat(64),
        "environment://sha256:" + "d".repeat(64),
        new HarnessExperiment("openai-worker-h0", 1),
        1_000,
        200,
        new BigDecimal("0.022000"));
  }
}
