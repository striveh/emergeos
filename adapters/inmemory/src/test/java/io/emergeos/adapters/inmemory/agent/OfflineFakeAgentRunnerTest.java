package io.emergeos.adapters.inmemory.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.domain.CaptureSourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OfflineFakeAgentRunnerTest {

  @Test
  void replaysFrozenTaskPackWithIdenticalVerifiedTruth() throws Exception {
    Fixture fixture = fixture();

    OfflineFakeAgentRunner.RunResult first =
        new OfflineFakeAgentRunner().run(fixture.spec());
    OfflineFakeAgentRunner.RunResult second =
        new OfflineFakeAgentRunner().run(fixture.spec());

    assertEquals(fixture.expectedStatus(), first.run().result().status());
    assertEquals(fixture.expectedTraceEventCount(), first.run().trace().eventCount());
    assertAll(
        () ->
            assertEquals(
                fixture.expectedCaptureRequestHash(),
                first.capture().requestHash()),
        () ->
            assertEquals(
                fixture.expectedTraceRootHash(),
                first.run().trace().rootHash()),
        () ->
            assertEquals(
                fixture.expectedArtifactContentHash(),
                first.artifact().current().contentHash()),
        () ->
            assertEquals(
                fixture.expectedBundleIntegrityHash(),
                first.run().bundle().integrityHash()));
    assertEquals(first, second);
    assertEquals(
        List.of(
            new ResourceBinding(
                ResourceRole.EVIDENCE,
                0,
                "capture://" + fixture.spec().captureId(),
                first.capture().requestHash()),
            new ResourceBinding(
                ResourceRole.ARTIFACT,
                0,
                "artifact-version://" + fixture.spec().artifactId() + "/1",
                first.artifact().current().contentHash())),
        first.run().bundle().resourceBindings());
    assertEquals(
        List.of(
            TraceEventType.MODEL_STEP,
            TraceEventType.TOOL_REQUEST,
            TraceEventType.TOOL_RESULT,
            TraceEventType.MODEL_STEP,
            TraceEventType.STRUCTURED_FINAL,
            TraceEventType.ARTIFACT_COMMITTED),
        first.run().trace().events().stream().map(event -> event.type()).toList());
    assertEquals(
        List.of(
            "COMPLETED",
            "REQUESTED",
            "SUCCEEDED",
            "COMPLETED",
            "PROPOSED",
            "SUCCEEDED"),
        first.run().trace().events().stream().map(event -> event.status()).toList());
    String traceProjection = first.run().trace().toString();
    assertFalse(traceProjection.contains(fixture.spec().content()));
    assertFalse(traceProjection.contains(fixture.spec().intent()));
    assertFalse(traceProjection.contains(first.artifact().current().content()));
    assertVersionBindings(first, fixture);
    assertNotNull(first.capture());
  }

  @Test
  void evidenceDriftChangesBoundTruthEvenWhenControlFlowIsTheSame() throws Exception {
    Fixture fixture = fixture();
    OfflineFakeAgentRunner.FrozenRunSpec baseline = fixture.spec();
    OfflineFakeAgentRunner.FrozenRunSpec changed =
        new OfflineFakeAgentRunner.FrozenRunSpec(
            baseline.principalId(),
            baseline.captureId(),
            baseline.clientNonce(),
            baseline.sourceType(),
            baseline.sourceRef(),
            baseline.dataClass(),
            baseline.content() + " 这是一条可检测的输入漂移。",
            baseline.intent(),
            baseline.runId(),
            baseline.taskId(),
            baseline.artifactId(),
            baseline.frozenTime(),
            baseline.kernelLatencyMs(),
            baseline.maxModelSteps(),
            baseline.maxToolCalls(),
            baseline.taskDeadlineMs());

    OfflineFakeAgentRunner.RunResult original =
        new OfflineFakeAgentRunner().run(baseline);
    OfflineFakeAgentRunner.RunResult drifted =
        new OfflineFakeAgentRunner().run(changed);

    assertEquals(original.run().trace().rootHash(), drifted.run().trace().rootHash());
    assertNotEquals(original.capture().requestHash(), drifted.capture().requestHash());
    assertNotEquals(
        original.run().bundle().resourceBindings().getFirst().contentHash(),
        drifted.run().bundle().resourceBindings().getFirst().contentHash());
    assertNotEquals(
        original.artifact().current().contentHash(),
        drifted.artifact().current().contentHash());
    assertNotEquals(
        original.run().bundle().integrityHash(),
        drifted.run().bundle().integrityHash());
  }

  private static Fixture fixture() throws IOException {
    JsonNode taskPack =
        JsonMapper.shared().readTree(Files.readString(findTaskPack()));
    JsonNode replay = taskPack.get("offlineReplay");
    JsonNode expected = taskPack.get("expectedReplay");
    JsonNode versions = taskPack.get("replayVersions");
    var spec =
        new OfflineFakeAgentRunner.FrozenRunSpec(
            text(replay, "principalId"),
            text(replay, "captureId"),
            text(replay, "clientNonce"),
            CaptureSourceType.parse(text(replay, "sourceType")),
            text(replay, "sourceRef"),
            DataClass.valueOf(text(replay, "dataClass")),
            text(replay, "content"),
            text(replay, "intent"),
            text(replay, "runId"),
            text(replay, "taskId"),
            text(replay, "artifactId"),
            Instant.parse(text(replay, "frozenTime")),
            replay.get("kernelLatencyMs").longValue(),
            replay.get("maxModelSteps").intValue(),
            replay.get("maxToolCalls").intValue(),
            replay.get("taskDeadlineMs").longValue());
    return new Fixture(
        spec,
        RunStatus.valueOf(text(expected, "status")),
        expected.get("traceEventCount").intValue(),
        text(expected, "captureRequestHash"),
        text(expected, "traceRootHash"),
        text(expected, "artifactContentHash"),
        text(expected, "bundleIntegrityHash"),
        new ReplayVersions(
            text(versions, "model"),
            text(versions, "harness"),
            text(versions, "agent"),
            text(versions, "verifier"),
            text(versions, "policy"),
            text(versions, "state"),
            text(versions, "contextPolicy"),
            text(versions, "toolRegistry"),
            text(versions, "traceIntegrity")));
  }

  private static String text(JsonNode node, String field) {
    return node.get(field).stringValue();
  }

  private static void assertVersionBindings(
      OfflineFakeAgentRunner.RunResult replay,
      Fixture fixture) {
    var run = replay.run();
    var versions = fixture.versions();
    assertAll(
        () -> assertEquals(fixture.spec().maxModelSteps(), run.task().maxModelSteps()),
        () -> assertEquals(fixture.spec().maxToolCalls(), run.task().maxToolCalls()),
        () -> assertEquals(fixture.spec().taskDeadlineMs(), run.task().deadlineMs()),
        () -> assertEquals(versions.model(), run.result().resolvedModel()),
        () -> assertEquals(versions.model(), run.bundle().modelResolved()),
        () -> assertEquals(versions.harness(), run.bundle().harnessVersion()),
        () -> assertEquals(versions.agent(), run.result().agentVersion()),
        () -> assertEquals(versions.verifier(), run.result().verifierVersion()),
        () -> assertEquals(versions.policy(), run.task().policyVersion()),
        () -> assertEquals(versions.state(), run.task().stateVersion()),
        () -> assertEquals(versions.contextPolicy(), run.task().contextPolicyVersion()),
        () -> assertEquals(versions.toolRegistry(), run.task().toolRegistryVersion()),
        () -> assertEquals(versions.toolRegistry(), run.bundle().toolRegistryVersion()),
        () -> assertEquals(versions.traceIntegrity(), run.trace().integrityProfile()),
        () -> assertEquals(versions.traceIntegrity(), run.bundle().integrityProfile()),
        () -> assertEquals(fixture.spec().kernelLatencyMs(), run.result().latencyMs()),
        () -> assertEquals(fixture.spec().kernelLatencyMs(), run.bundle().latencyMs()),
        () ->
            assertEquals(
                versions.agent(),
                run.bundle().componentVersions().get("agent")),
        () ->
            assertEquals(
                versions.verifier(),
                run.bundle().componentVersions().get("verifier")),
        () ->
            assertEquals(
                versions.traceIntegrity(),
                run.bundle().componentVersions().get("trace-integrity")));
  }

  private static Path findTaskPack() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate =
          current.resolve("evals/task-packs/synthetic/002-fake-agent-draft-replay.json");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate synthetic replay task pack");
  }

  private record Fixture(
      OfflineFakeAgentRunner.FrozenRunSpec spec,
      RunStatus expectedStatus,
      int expectedTraceEventCount,
      String expectedCaptureRequestHash,
      String expectedTraceRootHash,
      String expectedArtifactContentHash,
      String expectedBundleIntegrityHash,
      ReplayVersions versions) {}

  private record ReplayVersions(
      String model,
      String harness,
      String agent,
      String verifier,
      String policy,
      String state,
      String contextPolicy,
      String toolRegistry,
      String traceIntegrity) {}
}
