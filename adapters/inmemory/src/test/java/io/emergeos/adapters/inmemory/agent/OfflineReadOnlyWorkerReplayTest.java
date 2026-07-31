package io.emergeos.adapters.inmemory.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRunLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OfflineReadOnlyWorkerReplayTest {

  private static final int EXPECTED_PACK_BYTES = 8_443;
  private static final int MAX_PACK_BYTES = 65_536;
  private static final String EXPECTED_PACK_SHA256 =
      "808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c";
  private static final JsonMapper STRICT_PACK_JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_PACK_BYTES)
                          .maxTokenCount(1_536)
                          .maxNestingDepth(16)
                          .maxNameLength(96)
                          .maxStringLength(8_192)
                          .maxNumberLength(32)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Test
  void pack007ReplaysTheProductWorkerPathAndOneVariableContextDrift()
      throws Exception {
    ReplayPack pack = replayPack();
    var runner = new OfflineReadOnlyWorkerRunner();

    OfflineReadOnlyWorkerRunner.Observation control =
        verifyCase(runner, pack.frozen(), pack.control());
    OfflineReadOnlyWorkerRunner.Observation fault =
        verifyCase(runner, pack.frozen(), pack.fault());

    assertAll(
        () -> assertEquals(control.parent().task(), fault.parent().task()),
        () ->
            assertEquals(
                pack.control().expected().parentTaskHash(),
                pack.fault().expected().parentTaskHash()),
        () ->
            assertEquals(
                pack.frozen().parentContextPolicyVersion(),
                pack.control().registeredWorkerContextPolicyVersion()),
        () ->
            assertFalse(
                pack.frozen().parentContextPolicyVersion().equals(
                    pack.fault().registeredWorkerContextPolicyVersion())),
        () -> assertNotNull(control.child()),
        () -> assertNotNull(control.workerResult()),
        () -> assertNotNull(control.artifact()),
        () -> assertNull(fault.child()),
        () -> assertNull(fault.workerResult()),
        () -> assertNull(fault.artifact()));
    assertEquals(
        List.of(ResourceRole.EVIDENCE, ResourceRole.ARTIFACT, ResourceRole.HANDOFF),
        control.parent().bundle().resourceBindings().stream()
            .map(binding -> binding.role())
            .toList());
    assertEquals(
        List.of(ResourceRole.EVIDENCE, ResourceRole.WORKER_RESULT),
        control.child().bundle().resourceBindings().stream()
            .map(binding -> binding.role())
            .toList());
    assertEquals(List.of(), fault.parent().bundle().resourceBindings());
    assertEquals(
        control.workerResult().content(),
        control.artifact().current().content());
    assertEquals(
        control.workerResult().contentHash(),
        control.artifact().current().contentHash());
  }

  private static OfflineReadOnlyWorkerRunner.Observation verifyCase(
      OfflineReadOnlyWorkerRunner runner,
      Frozen frozen,
      CaseSpec testCase) {
    OfflineReadOnlyWorkerRunner.Spec spec =
        new OfflineReadOnlyWorkerRunner.Spec(
            frozen.taskSchemaVersion(),
            testCase.id(),
            frozen.principalId(),
            frozen.captureId(),
            frozen.captureNonce(),
            frozen.content(),
            frozen.sourceRef(),
            frozen.intent(),
            frozen.parentRunId(),
            frozen.childRunId(),
            frozen.parentTaskId(),
            frozen.childTaskId(),
            frozen.artifactId(),
            frozen.parentContextPolicyVersion(),
            testCase.registeredWorkerContextPolicyVersion(),
            frozen.frozenTime());
    OfflineReadOnlyWorkerRunner.Observation first = runner.run(spec);
    OfflineReadOnlyWorkerRunner.Observation second =
        new OfflineReadOnlyWorkerRunner().run(spec);
    assertEquals(first, second);

    Expected expected = testCase.expected();
    assertAll(
        () -> assertEquals(testCase.id(), first.caseId()),
        () -> assertEquals(expected.parentStatus(), first.parent().result().status()),
        () ->
            assertEquals(
                AgentRunLifecycle.terminal(expected.parentStatus()),
                first.parent().lifecycle()),
        () ->
            assertEquals(
                expected.parentFailureReason(),
                first.parent().result().failureReason()),
        () ->
            assertEquals(
                expected.parentFailureReason(),
                first.parent().bundle().failureAttribution()),
        () -> assertEquals(expected.parentStatus(), first.parent().bundle().outcome()),
        () ->
            assertEquals(
                expected.parentTraceTypes(),
                first.parent().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () ->
            assertEquals(
                expected.parentTraceStatuses(),
                first.parent().trace().events().stream()
                    .map(event -> event.status())
                    .toList()),
        () -> assertEquals(expected.runStartCount(), first.runStarts()),
        () -> assertEquals(expected.runCompleteCount(), first.runCompletions()),
        () -> assertEquals(expected.artifactCount(), first.artifactCommits()),
        () ->
            assertEquals(
                expected.workerResultCount(), first.workerResultCommits()),
        () ->
            assertEquals(
                expected.workerVerifiedReadCount(),
                first.workerVerifiedReads()),
        () ->
            assertEquals(
                expected.pairVerificationCount(), first.pairVerifications()),
        () ->
            assertEquals(
                expected.parentModelCallCount(), first.parentModelCalls()),
        () ->
            assertEquals(
                expected.childModelCallCount(), first.childModelCalls()),
        () ->
            assertEquals(
                expected.childToolExecuteCount(),
                first.childToolExecutions()),
        () -> assertEquals(expected.captureReadCount(), first.captureReads()),
        () ->
            assertEquals(
                expected.workerPreparationCount(), first.workerPreparations()),
        () ->
            assertEquals(
                expected.parentTaskHash(),
                IntegrityHashes.taskHash(first.parent().task())),
        () ->
            assertEquals(
                expected.parentTraceRoot(), first.parent().trace().rootHash()),
        () ->
            assertEquals(
                expected.parentBundleHash(),
                first.parent().bundle().integrityHash()),
        () ->
            assertEquals(
                frozen.parentModel(),
                first.parent().result().resolvedModel()),
        () ->
            assertEquals(
                frozen.harness(), first.parent().bundle().harnessVersion()),
        () ->
            assertEquals(
                frozen.traceIntegrity(),
                first.parent().trace().integrityProfile()));

    if (expected.childStatus() == null) {
      assertAll(
          () -> assertNull(first.child()),
          () -> assertNull(first.workerResult()),
          () -> assertNull(first.artifact()),
          () -> assertNull(expected.childTaskHash()),
          () -> assertNull(expected.childTraceRoot()),
          () -> assertNull(expected.childBundleHash()),
          () -> assertNull(expected.workerResultIntegrityHash()),
          () -> assertNull(expected.workerContentHash()),
          () -> assertNull(expected.artifactContentHash()));
      return first;
    }

    assertNotNull(first.child());
    assertNotNull(first.workerResult());
    assertNotNull(first.artifact());
    assertAll(
        () -> assertEquals(expected.childStatus(), first.child().result().status()),
        () ->
            assertEquals(
                AgentRunLifecycle.terminal(expected.childStatus()),
                first.child().lifecycle()),
        () ->
            assertEquals(
                expected.childTraceTypes(),
                first.child().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () ->
            assertEquals(
                expected.childTraceStatuses(),
                first.child().trace().events().stream()
                    .map(event -> event.status())
                    .toList()),
        () ->
            assertEquals(
                expected.childTaskHash(),
                IntegrityHashes.taskHash(first.child().task())),
        () ->
            assertEquals(
                expected.childTraceRoot(), first.child().trace().rootHash()),
        () ->
            assertEquals(
                expected.childBundleHash(),
                first.child().bundle().integrityHash()),
        () ->
            assertEquals(
                expected.workerResultIntegrityHash(),
                first.workerResult().integrityHash()),
        () ->
            assertEquals(
                expected.workerContentHash(),
                first.workerResult().contentHash()),
        () ->
            assertEquals(
                expected.artifactContentHash(),
                first.artifact().current().contentHash()),
        () ->
            assertEquals(
                frozen.workerModel(),
                first.child().result().resolvedModel()),
        () ->
            assertEquals(
                frozen.harness(), first.child().bundle().harnessVersion()),
        () ->
            assertEquals(
                "worker-result://" + frozen.childRunId(),
                first.workerResult().workerResultRef()),
        () ->
            assertEquals(
                List.of("capture://" + frozen.captureId()),
                first.workerResult().evidenceRefs()));
    return first;
  }

  private static ReplayPack replayPack() throws IOException {
    Path path = findTaskPack();
    long size = Files.size(path);
    if (size != EXPECTED_PACK_BYTES || size > MAX_PACK_BYTES) {
      throw new IllegalStateException("Pack 007 raw byte length drifted");
    }
    byte[] raw = Files.readAllBytes(path);
    if (raw.length != EXPECTED_PACK_BYTES || raw.length > MAX_PACK_BYTES) {
      throw new IllegalStateException("Pack 007 raw byte length drifted");
    }
    if (!EXPECTED_PACK_SHA256.equals(sha256(raw))) {
      throw new IllegalStateException("Pack 007 raw SHA-256 drifted");
    }
    JsonNode root = STRICT_PACK_JSON.readTree(raw);
    assertExactKeys(
        root,
        List.of(
            "schemaVersion",
            "taskId",
            "title",
            "principalRef",
            "seed",
            "requiredConstraints",
            "forbiddenActions",
            "acceptanceChecks",
            "humanReviewQuestions",
            "risk",
            "expectedArtifactKind",
            "faultPlan",
            "readOnlyWorkerHandoff"),
        "Pack 007 root");
    if (!"0.6".equals(text(root, "schemaVersion"))
        || !"synthetic-offline-read-only-worker-handoff-context-drift-007"
            .equals(text(root, "taskId"))
        || !"pack007-owner".equals(text(root, "principalRef"))
        || !"REVERSIBLE".equals(text(root, "risk"))
        || !"ARTICLE_DRAFT".equals(text(root, "expectedArtifactKind"))) {
      throw new IllegalStateException("Pack 007 identity drifted");
    }

    JsonNode seed = required(root, "seed");
    assertExactKeys(
        seed,
        List.of("sourceType", "sourceRef", "dataClass", "content"),
        "Pack 007 seed");
    JsonNode suite = required(root, "readOnlyWorkerHandoff");
    assertExactKeys(
        suite,
        List.of("suiteId", "variable", "provenance", "frozen", "control", "fault"),
        "Pack 007 suite");
    if (!"offline-read-only-worker-handoff-context-drift-v1"
            .equals(text(suite, "suiteId"))
        || !"registered-worker-context-policy-version"
            .equals(text(suite, "variable"))) {
      throw new IllegalStateException("Pack 007 suite identity drifted");
    }
    verifyProvenance(required(suite, "provenance"));

    Frozen frozen = frozen(required(suite, "frozen"));
    if (!frozen.principalId().equals(text(root, "principalRef"))
        || !frozen.sourceType().equals(text(seed, "sourceType"))
        || !frozen.sourceRef().equals(text(seed, "sourceRef"))
        || !frozen.dataClass().equals(text(seed, "dataClass"))
        || !frozen.content().equals(text(seed, "content"))
        || !"TEXT".equals(frozen.sourceType())
        || !"PUBLIC".equals(frozen.dataClass())) {
      throw new IllegalStateException(
          "Pack 007 frozen input must exactly bind its declared principal and seed");
    }
    verifyProductVersions(frozen);

    CaseSpec control = caseSpec(required(suite, "control"));
    CaseSpec fault = caseSpec(required(suite, "fault"));
    if (!"matching-context-policy-control".equals(control.id())
        || !"registered-context-policy-drift".equals(fault.id())
        || !"ref-only-v1"
            .equals(control.registeredWorkerContextPolicyVersion())
        || !"ref-only-v2".equals(fault.registeredWorkerContextPolicyVersion())
        || !control.registeredWorkerContextPolicyVersion()
            .equals(frozen.parentContextPolicyVersion())
        || fault.registeredWorkerContextPolicyVersion()
            .equals(frozen.parentContextPolicyVersion())
        || !control.expected().parentTaskHash()
            .equals(fault.expected().parentTaskHash())) {
      throw new IllegalStateException(
          "Pack 007 cases must change only the registered Worker context policy");
    }
    return new ReplayPack(frozen, control, fault);
  }

  private static Frozen frozen(JsonNode node) {
    List<String> keys =
        List.of(
            "taskSchemaVersion",
            "principalId",
            "captureId",
            "captureNonce",
            "sourceType",
            "sourceRef",
            "dataClass",
            "content",
            "intent",
            "parentRunId",
            "childRunId",
            "parentTaskId",
            "childTaskId",
            "artifactId",
            "frozenTime",
            "parentExecutionProfile",
            "parentModel",
            "workerProfile",
            "workerRegistry",
            "workerName",
            "workerModel",
            "workerProfileFingerprint",
            "harness",
            "parentAgent",
            "workerAgent",
            "verifier",
            "policy",
            "state",
            "parentContextPolicyVersion",
            "toolRegistry",
            "traceIntegrity");
    assertExactKeys(node, keys, "Pack 007 frozen");
    return new Frozen(
        text(node, "taskSchemaVersion"),
        text(node, "principalId"),
        text(node, "captureId"),
        text(node, "captureNonce"),
        text(node, "sourceType"),
        text(node, "sourceRef"),
        text(node, "dataClass"),
        text(node, "content"),
        text(node, "intent"),
        text(node, "parentRunId"),
        text(node, "childRunId"),
        text(node, "parentTaskId"),
        text(node, "childTaskId"),
        text(node, "artifactId"),
        Instant.parse(text(node, "frozenTime")),
        text(node, "parentExecutionProfile"),
        text(node, "parentModel"),
        text(node, "workerProfile"),
        text(node, "workerRegistry"),
        text(node, "workerName"),
        text(node, "workerModel"),
        text(node, "workerProfileFingerprint"),
        text(node, "harness"),
        text(node, "parentAgent"),
        text(node, "workerAgent"),
        text(node, "verifier"),
        text(node, "policy"),
        text(node, "state"),
        text(node, "parentContextPolicyVersion"),
        text(node, "toolRegistry"),
        text(node, "traceIntegrity"));
  }

  private static void verifyProductVersions(Frozen frozen) {
    AgentExecutionProfile parent = AgentExecutionProfile.readOnlyWorkerFakeV1();
    ReadOnlyWorkerExecutionProfile worker =
        ReadOnlyWorkerExecutionProfile.pack007FakeV1(
            frozen.parentContextPolicyVersion());
    assertAll(
        () -> assertEquals(parent.taskSchemaVersion(), frozen.taskSchemaVersion()),
        () -> assertEquals(parent.id(), frozen.parentExecutionProfile()),
        () -> assertEquals(ScriptedFakeModel.CONDUCTOR_MODEL_ID, frozen.parentModel()),
        () -> assertEquals(worker.id(), frozen.workerProfile()),
        () -> assertEquals(worker.registryVersion(), frozen.workerRegistry()),
        () -> assertEquals(worker.workerName(), frozen.workerName()),
        () -> assertEquals(ScriptedFakeModel.WORKER_MODEL_ID, frozen.workerModel()),
        () -> assertEquals(worker.fingerprint(), frozen.workerProfileFingerprint()),
        () -> assertEquals(parent.harnessVersion(), frozen.harness()),
        () -> assertEquals(parent.agentVersion(), frozen.parentAgent()),
        () -> assertEquals(worker.agentVersion(), frozen.workerAgent()),
        () -> assertEquals(parent.verifierVersion(), frozen.verifier()),
        () -> assertEquals(parent.policyVersion(), frozen.policy()),
        () -> assertEquals(parent.stateVersion(), frozen.state()),
        () ->
            assertEquals(
                parent.contextPolicyVersion(),
                frozen.parentContextPolicyVersion()),
        () -> assertEquals(parent.toolRegistryVersion(), frozen.toolRegistry()),
        () -> assertEquals(IntegrityHashes.PROFILE, frozen.traceIntegrity()));
  }

  private static CaseSpec caseSpec(JsonNode node) {
    assertExactKeys(
        node,
        List.of("id", "registeredWorkerContextPolicyVersion", "expected"),
        "Pack 007 case");
    JsonNode expectedNode = required(node, "expected");
    List<String> expectedKeys =
        List.of(
            "parentStatus",
            "parentFailureReason",
            "childStatus",
            "parentTraceTypes",
            "parentTraceStatuses",
            "childTraceTypes",
            "childTraceStatuses",
            "runStartCount",
            "runCompleteCount",
            "artifactCount",
            "workerResultCount",
            "workerVerifiedReadCount",
            "pairVerificationCount",
            "parentModelCallCount",
            "childModelCallCount",
            "childToolExecuteCount",
            "captureReadCount",
            "workerPreparationCount",
            "parentTaskHash",
            "childTaskHash",
            "parentTraceRoot",
            "childTraceRoot",
            "parentBundleHash",
            "childBundleHash",
            "workerResultIntegrityHash",
            "workerContentHash",
            "artifactContentHash");
    assertExactKeys(expectedNode, expectedKeys, "Pack 007 expected receipt");
    Expected expected =
        new Expected(
            RunStatus.valueOf(text(expectedNode, "parentStatus")),
            nullableText(expectedNode, "parentFailureReason"),
            nullableStatus(expectedNode, "childStatus"),
            traceTypes(expectedNode, "parentTraceTypes"),
            strings(expectedNode, "parentTraceStatuses"),
            traceTypes(expectedNode, "childTraceTypes"),
            strings(expectedNode, "childTraceStatuses"),
            integer(expectedNode, "runStartCount"),
            integer(expectedNode, "runCompleteCount"),
            integer(expectedNode, "artifactCount"),
            integer(expectedNode, "workerResultCount"),
            integer(expectedNode, "workerVerifiedReadCount"),
            integer(expectedNode, "pairVerificationCount"),
            integer(expectedNode, "parentModelCallCount"),
            integer(expectedNode, "childModelCallCount"),
            integer(expectedNode, "childToolExecuteCount"),
            integer(expectedNode, "captureReadCount"),
            integer(expectedNode, "workerPreparationCount"),
            digest(expectedNode, "parentTaskHash", false),
            digest(expectedNode, "childTaskHash", true),
            digest(expectedNode, "parentTraceRoot", false),
            digest(expectedNode, "childTraceRoot", true),
            digest(expectedNode, "parentBundleHash", false),
            digest(expectedNode, "childBundleHash", true),
            digest(expectedNode, "workerResultIntegrityHash", true),
            digest(expectedNode, "workerContentHash", true),
            digest(expectedNode, "artifactContentHash", true));
    return new CaseSpec(
        text(node, "id"),
        text(node, "registeredWorkerContextPolicyVersion"),
        expected);
  }

  private static void verifyProvenance(JsonNode node) {
    assertExactKeys(
        node,
        List.of(
            "kind",
            "containsRealUserData",
            "containsRealAccount",
            "networkAllowed",
            "realModelAllowed",
            "connectorAllowed"),
        "Pack 007 provenance");
    for (String field :
        List.of(
            "containsRealUserData",
            "containsRealAccount",
            "networkAllowed",
            "realModelAllowed",
            "connectorAllowed")) {
      if (!required(node, field).isBoolean()) {
        throw new IllegalStateException(
            "Pack 007 provenance " + field + " must be Boolean");
      }
    }
    if (!"LITERAL_CHECKED_IN_SYNTHETIC".equals(text(node, "kind"))
        || node.get("containsRealUserData").booleanValue()
        || node.get("containsRealAccount").booleanValue()
        || node.get("networkAllowed").booleanValue()
        || node.get("realModelAllowed").booleanValue()
        || node.get("connectorAllowed").booleanValue()) {
      throw new IllegalStateException(
          "Pack 007 provenance must disable real data, account, network, model and Connector");
    }
  }

  private static String digest(
      JsonNode node, String field, boolean nullable) {
    String value = nullableText(node, field);
    if (value == null && nullable) {
      return null;
    }
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalStateException(
          "Pack 007 " + field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  private static RunStatus nullableStatus(JsonNode node, String field) {
    String value = nullableText(node, field);
    return value == null ? null : RunStatus.valueOf(value);
  }

  private static List<TraceEventType> traceTypes(
      JsonNode node, String field) {
    return required(node, field).valueStream()
        .map(value -> TraceEventType.valueOf(value.stringValue()))
        .toList();
  }

  private static List<String> strings(JsonNode node, String field) {
    return required(node, field).valueStream()
        .map(JsonNode::stringValue)
        .toList();
  }

  private static int integer(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalStateException("Pack 007 " + field + " must be an integer");
    }
    return value.intValue();
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalStateException("Pack 007 is missing " + field);
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    String value = nullableText(node, field);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Pack 007 " + field + " must be a non-empty string");
    }
    return value;
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalStateException(
          "Pack 007 " + field + " must be textual or null");
    }
    return value.stringValue();
  }

  private static void assertExactKeys(
      JsonNode node, List<String> expected, String label) {
    if (!node.isObject()) {
      throw new IllegalStateException(label + " must be an object");
    }
    Set<String> actual = new LinkedHashSet<>();
    node.propertyStream().map(entry -> entry.getKey()).forEach(actual::add);
    if (!actual.equals(new LinkedHashSet<>(expected))) {
      throw new IllegalStateException(
          label + " keys drifted: expected=" + expected + ", actual=" + actual);
    }
  }

  private static String sha256(byte[] raw) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  private static Path findTaskPack() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate =
          current.resolve(
              "evals/task-packs/synthetic/007-offline-read-only-worker-handoff-context-drift.json");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate Pack 007 Task Pack");
  }

  private record ReplayPack(Frozen frozen, CaseSpec control, CaseSpec fault) {}

  private record Frozen(
      String taskSchemaVersion,
      String principalId,
      String captureId,
      String captureNonce,
      String sourceType,
      String sourceRef,
      String dataClass,
      String content,
      String intent,
      String parentRunId,
      String childRunId,
      String parentTaskId,
      String childTaskId,
      String artifactId,
      Instant frozenTime,
      String parentExecutionProfile,
      String parentModel,
      String workerProfile,
      String workerRegistry,
      String workerName,
      String workerModel,
      String workerProfileFingerprint,
      String harness,
      String parentAgent,
      String workerAgent,
      String verifier,
      String policy,
      String state,
      String parentContextPolicyVersion,
      String toolRegistry,
      String traceIntegrity) {}

  private record CaseSpec(
      String id,
      String registeredWorkerContextPolicyVersion,
      Expected expected) {}

  private record Expected(
      RunStatus parentStatus,
      String parentFailureReason,
      RunStatus childStatus,
      List<TraceEventType> parentTraceTypes,
      List<String> parentTraceStatuses,
      List<TraceEventType> childTraceTypes,
      List<String> childTraceStatuses,
      int runStartCount,
      int runCompleteCount,
      int artifactCount,
      int workerResultCount,
      int workerVerifiedReadCount,
      int pairVerificationCount,
      int parentModelCallCount,
      int childModelCallCount,
      int childToolExecuteCount,
      int captureReadCount,
      int workerPreparationCount,
      String parentTaskHash,
      String childTaskHash,
      String parentTraceRoot,
      String childTraceRoot,
      String parentBundleHash,
      String childBundleHash,
      String workerResultIntegrityHash,
      String workerContentHash,
      String artifactContentHash) {}
}
