package io.emergeos.adapters.inmemory.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentTool;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OfflineToolDeadlineFaultTest {

  private static final int EXPECTED_PACK_BYTES = 6_451;
  private static final int MAX_PACK_BYTES = 65_536;
  private static final String PERSISTENCE_SENTINEL =
      "post-dispatch deadline truth";
  private static final String EXPECTED_PACK_SHA256 =
      "ada80a0f05bffb907408cb9d877b039dbb46253ab09904404570f4c721b53f22";
  private static final JsonMapper STRICT_PACK_JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_PACK_BYTES)
                          .maxTokenCount(768)
                          .maxNestingDepth(16)
                          .maxNameLength(64)
                          .maxStringLength(4_096)
                          .maxNumberLength(32)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  @Test
  void pack006RejectsALateReadOnlyToolResultWithoutErasingItsDispatchTruth()
      throws Exception {
    FaultPack pack = faultPack();
    Observation control = verifyCase(pack.frozen(), pack.control());
    Observation fault = verifyCase(pack.frozen(), pack.fault());

    assertEquals(control.run().task(), fault.run().task());
    assertAll(
        () ->
            assertEquals(
                pack.control().taskIntegrityHash(),
                IntegrityHashes.taskHash(control.run().task())),
        () ->
            assertEquals(
                pack.control().traceRootHash(),
                control.run().trace().rootHash()),
        () ->
            assertEquals(
                pack.control().bundleIntegrityHash(),
                control.run().bundle().integrityHash()),
        () ->
            assertEquals(
                pack.fault().traceRootHash(),
                fault.run().trace().rootHash()),
        () ->
            assertEquals(
                pack.fault().bundleIntegrityHash(),
                fault.run().bundle().integrityHash()));
    assertEquals(
        pack.control().completionLatencyMs(),
        control.run().result().latencyMs());
    assertEquals(
        pack.fault().completionLatencyMs(), fault.run().result().latencyMs());
    assertEquals(
        pack.fault().traceTypes(),
        fault.run().trace().events().stream().map(event -> event.type()).toList());
    assertEquals(
        pack.fault().traceStatuses(),
        fault.run().trace().events().stream().map(event -> event.status()).toList());
    assertEquals(
        List.of(pack.frozen().captureRef(), pack.frozen().captureRef()),
        fault.run().trace().events().stream()
            .filter(event -> event.toolName() != null)
            .map(event -> event.reference())
            .toList());
    assertEquals(List.of(), fault.run().result().evidenceRefs());
    assertEquals(List.of(), fault.run().bundle().resourceBindings());
    assertEquals(List.of(), fault.run().result().uncertainty());
  }

  private static Observation verifyCase(Frozen frozen, CaseSpec spec) {
    Observation first = run(frozen, spec);
    Observation second = run(frozen, spec);

    assertEquals(first, second);
    assertAll(
        () -> assertEquals(spec.status(), first.run().result().status()),
        () ->
            assertEquals(
                AgentRunLifecycle.terminal(spec.status()), first.run().lifecycle()),
        () ->
            assertEquals(
                spec.failureReason(), first.run().result().failureReason()),
        () ->
            assertEquals(
                spec.failureReason(), first.run().bundle().failureAttribution()),
        () -> assertEquals(spec.status(), first.run().bundle().outcome()),
        () ->
            assertEquals(
                spec.traceTypes(),
                first.run().trace().events().stream()
                    .map(event -> event.type())
                    .toList()),
        () ->
            assertEquals(
                spec.traceStatuses(),
                first.run().trace().events().stream()
                    .map(event -> event.status())
                    .toList()),
        () -> assertEquals(spec.validationCount(), first.validationCount()),
        () -> assertEquals(spec.toolExecuteCount(), first.toolExecuteCount()),
        () ->
            assertEquals(
                spec.totalFindOwnedCount(), first.totalFindOwnedCount()),
        () ->
            assertEquals(
                spec.toolBackedReadCount(), first.toolBackedReadCount()),
        () -> assertEquals(spec.runStartCount(), first.runStartCount()),
        () -> assertEquals(spec.runCompleteCount(), first.runCompleteCount()),
        () -> assertEquals(spec.modelCallCount(), first.modelCallCount()),
        () -> assertEquals(spec.artifactCount(), first.artifactCount()),
        () ->
            assertEquals(
                spec.evidenceRefCount(),
                first.run().result().evidenceRefs().size()),
        () ->
            assertEquals(
                spec.resourceBindingCount(),
                first.run().bundle().resourceBindings().size()),
        () ->
            assertEquals(
                spec.uncertaintyCount(),
                first.run().result().uncertainty().size()),
        () ->
            assertEquals(
                spec.consumedIdPrefixes(), first.consumedIdPrefixes()),
        () ->
            assertEquals(
                spec.completionLatencyMs(), first.run().result().latencyMs()),
        () -> assertEquals(frozen.model(), first.run().result().resolvedModel()),
        () -> assertEquals(BigDecimal.ZERO, first.run().result().costUsd()),
        () -> assertEquals(0, first.run().result().tokenCount()),
        () ->
            assertEquals(
                frozen.taskSchemaVersion(),
                first.run().task().schemaVersion()),
        () -> assertEquals(frozen.budgetUsd(), first.run().task().budgetUsd()),
        () ->
            assertEquals(
                frozen.toolRegistry(),
                first.run().task().toolRegistryVersion()),
        () -> assertEquals(frozen.state(), first.run().task().stateVersion()),
        () ->
            assertEquals(
                frozen.traceIntegrity(),
                first.run().trace().integrityProfile()),
        () ->
            assertEquals(
                spec.taskIntegrityHash(),
                IntegrityHashes.taskHash(first.run().task())),
        () ->
            assertEquals(
                spec.traceRootHash(), first.run().trace().rootHash()),
        () ->
            assertEquals(
                spec.bundleIntegrityHash(),
                first.run().bundle().integrityHash()),
        () -> assertEquals(first.run().result(), first.run().bundle().result()),
        () ->
            assertEquals(
                first.run().trace().rootHash(),
                first.run().bundle().traceRootHash()));
    assertTraceMetadata(first.run(), frozen);

    if (spec.artifactCount() == 1) {
      assertNotNull(first.artifact());
    } else {
      assertNull(first.artifact());
      assertEquals(List.of(), first.run().result().artifactRefs());
      assertFalse(
          first.run().trace().events().stream()
              .anyMatch(event -> event.type() == TraceEventType.TOOL_RESULT));
      assertFalse(
          first.run().trace().events().stream()
              .anyMatch(event -> event.type() == TraceEventType.ARTIFACT_COMMITTED));
    }
    String safePersistentProjection =
        first.run().result()
            + "\n"
            + first.run().trace()
            + "\n"
            + first.run().bundle();
    assertTrue(frozen.content().contains(PERSISTENCE_SENTINEL));
    assertFalse(safePersistentProjection.contains(frozen.content()));
    assertFalse(safePersistentProjection.contains(PERSISTENCE_SENTINEL));
    return first;
  }

  private static void assertTraceMetadata(AgentRun run, Frozen frozen) {
    for (var event : run.trace().events()) {
      switch (event.type()) {
        case MODEL_STEP, STRUCTURED_FINAL -> {
          assertNull(event.toolName());
          assertEquals(frozen.taskRef(), event.reference());
        }
        case TOOL_REQUEST, TOOL_RESULT, TOOL_REJECTED -> {
          assertEquals(CaptureReadTool.NAME, event.toolName());
          assertEquals(frozen.captureRef(), event.reference());
        }
        case ARTIFACT_COMMITTED -> {
          assertNull(event.toolName());
          assertEquals(frozen.artifactRef(), event.reference());
        }
      }
    }
  }

  private static Observation run(Frozen frozen, CaseSpec spec) {
    Capture capture =
        new Capture(
            frozen.captureId(),
            frozen.principalId(),
            frozen.clientNonce(),
            CaptureRequestHashes.sha256(
                frozen.content(),
                frozen.sourceType(),
                frozen.sourceRef(),
                frozen.dataClass()),
            frozen.content(),
            frozen.sourceType(),
            frozen.sourceRef(),
            frozen.dataClass(),
            frozen.frozenTime());
    var captures = new CountingCaptureStore(capture);
    var now = new AtomicLong();
    var captureRead =
        new ClockAdvancingCaptureReadTool(
            captures, now, spec.completionLatencyMs());
    var model = new DeadlinePackModel(frozen);
    AgentExecutionProfile profile = executionProfile(frozen);
    var kernel =
        new AgentLoopKernel(
            model,
            new AgentToolRegistry(frozen.toolRegistry(), List.of(captureRead)),
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            now::get);
    var runs = new RecordingRunStore();
    var ids =
        new FrozenIdGenerator(
            Map.of(
                "run", frozen.runId(),
                "task", frozen.taskId(),
                "art", frozen.artifactId()));
    var service =
        new AgentDraftService(
            kernel,
            runs,
            captures,
            ids,
            Clock.fixed(frozen.frozenTime(), ZoneOffset.UTC),
            profile);

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                frozen.principalId(), frozen.captureId(), frozen.intent()));
    ids.requireConsumed(spec.artifactCount() == 1);
    int toolBackedReads = captures.findOwnedCount() - 1;
    return new Observation(
        outcome.run(),
        outcome.artifact(),
        captureRead.validationCount(),
        captureRead.executeCount(),
        captures.findOwnedCount(),
        toolBackedReads,
        runs.artifactCount(),
        runs.startCount(),
        runs.completeCount(),
        model.callCount(),
        ids.consumedPrefixes());
  }

  private static AgentExecutionProfile executionProfile(Frozen frozen) {
    return new AgentExecutionProfile(
        frozen.executionProfile(),
        frozen.taskSchemaVersion(),
        RiskLevel.REVERSIBLE,
        frozen.maxModelSteps(),
        frozen.maxToolCalls(),
        frozen.taskDeadlineMs(),
        frozen.budgetUsd(),
        0,
        0,
        null,
        null,
        frozen.agent(),
        frozen.verifier(),
        frozen.harness(),
        null,
        frozen.policy(),
        frozen.state(),
        frozen.contextPolicy(),
        frozen.toolRegistry(),
        null,
        List.of(),
        null);
  }

  private static FaultPack faultPack() throws IOException {
    Path taskPack = findTaskPack();
    long size = Files.size(taskPack);
    if (size > MAX_PACK_BYTES || size != EXPECTED_PACK_BYTES) {
      throw new IllegalStateException("Pack 006 raw byte length drifted");
    }
    byte[] raw = Files.readAllBytes(taskPack);
    if (raw.length > MAX_PACK_BYTES || raw.length != EXPECTED_PACK_BYTES) {
      throw new IllegalStateException("Pack 006 raw byte length drifted");
    }
    if (!EXPECTED_PACK_SHA256.equals(sha256(raw))) {
      throw new IllegalStateException("Pack 006 raw SHA-256 drifted");
    }
    JsonNode root = STRICT_PACK_JSON.readTree(raw);
    JsonNode suite = required(root, "postDispatchDeadlineFault");
    if (!"offline-read-only-tool-post-dispatch-deadline-v1"
        .equals(text(suite, "suiteId"))) {
      throw new IllegalStateException("Pack 006 suite identity drifted");
    }
    JsonNode node = required(suite, "frozen");
    Frozen frozen =
        new Frozen(
            text(node, "principalId"),
            text(node, "captureId"),
            text(node, "clientNonce"),
            CaptureSourceType.parse(text(node, "sourceType")),
            text(node, "sourceRef"),
            DataClass.valueOf(text(node, "dataClass")),
            text(node, "content"),
            text(node, "intent"),
            text(node, "runId"),
            text(node, "taskId"),
            text(node, "artifactId"),
            Instant.parse(text(node, "frozenTime")),
            text(node, "taskSchemaVersion"),
            new BigDecimal(text(node, "budgetUsd")),
            node.get("maxModelSteps").intValue(),
            node.get("maxToolCalls").intValue(),
            node.get("taskDeadlineMs").longValue(),
            text(node, "executionProfile"),
            text(node, "model"),
            text(node, "harness"),
            text(node, "agent"),
            text(node, "verifier"),
            text(node, "policy"),
            text(node, "state"),
            text(node, "contextPolicy"),
            text(node, "toolRegistry"),
            text(node, "traceIntegrity"));
    FaultPack pack =
        new FaultPack(
            frozen,
            caseSpec(required(suite, "control")),
            caseSpec(required(suite, "fault")));
    if (pack.control().completionLatencyMs() >= frozen.taskDeadlineMs()
        || pack.fault().completionLatencyMs() <= frozen.taskDeadlineMs()
        || !pack.control().taskIntegrityHash()
            .equals(pack.fault().taskIntegrityHash())) {
      throw new IllegalStateException(
          "Pack 006 cases must straddle one frozen Task deadline");
    }
    return pack;
  }

  private static String sha256(byte[] raw) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 must be available", impossible);
    }
  }

  private static CaseSpec caseSpec(JsonNode node) {
    JsonNode expected = required(node, "expected");
    String failureReason =
        expected.get("failureReason").isNull()
            ? null
            : expected.get("failureReason").stringValue();
    return new CaseSpec(
        text(node, "id"),
        node.get("completionLatencyMs").longValue(),
        RunStatus.valueOf(text(expected, "status")),
        failureReason,
        expected.get("traceTypes").valueStream()
            .map(value -> TraceEventType.valueOf(value.stringValue()))
            .toList(),
        expected.get("traceStatuses").valueStream()
            .map(JsonNode::stringValue)
            .toList(),
        expected.get("modelCallCount").intValue(),
        expected.get("validationCount").intValue(),
        expected.get("toolExecuteCount").intValue(),
        expected.get("totalFindOwnedCount").intValue(),
        expected.get("toolBackedReadCount").intValue(),
        expected.get("runStartCount").intValue(),
        expected.get("runCompleteCount").intValue(),
        expected.get("artifactCount").intValue(),
        expected.get("evidenceRefCount").intValue(),
        expected.get("resourceBindingCount").intValue(),
        expected.get("uncertaintyCount").intValue(),
        expected.get("consumedIdPrefixes").valueStream()
            .map(JsonNode::stringValue)
            .toList(),
        text(expected, "taskIntegrityHash"),
        text(expected, "traceRootHash"),
        text(expected, "bundleIntegrityHash"));
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalStateException("Pack 006 is missing " + field);
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    return required(node, field).stringValue();
  }

  private static Path findTaskPack() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate =
          current.resolve(
              "evals/task-packs/synthetic/006-offline-read-only-tool-post-dispatch-deadline.json");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate synthetic post-dispatch deadline pack");
  }

  private record FaultPack(Frozen frozen, CaseSpec control, CaseSpec fault) {}

  private record Frozen(
      String principalId,
      String captureId,
      String clientNonce,
      CaptureSourceType sourceType,
      String sourceRef,
      DataClass dataClass,
      String content,
      String intent,
      String runId,
      String taskId,
      String artifactId,
      Instant frozenTime,
      String taskSchemaVersion,
      BigDecimal budgetUsd,
      int maxModelSteps,
      int maxToolCalls,
      long taskDeadlineMs,
      String executionProfile,
      String model,
      String harness,
      String agent,
      String verifier,
      String policy,
      String state,
      String contextPolicy,
      String toolRegistry,
      String traceIntegrity) {

    private String captureRef() {
      return "capture://" + captureId;
    }

    private String taskRef() {
      return "task://" + taskId;
    }

    private String artifactRef() {
      return "artifact-version://" + artifactId + "/1";
    }
  }

  private record CaseSpec(
      String id,
      long completionLatencyMs,
      RunStatus status,
      String failureReason,
      List<TraceEventType> traceTypes,
      List<String> traceStatuses,
      int modelCallCount,
      int validationCount,
      int toolExecuteCount,
      int totalFindOwnedCount,
      int toolBackedReadCount,
      int runStartCount,
      int runCompleteCount,
      int artifactCount,
      int evidenceRefCount,
      int resourceBindingCount,
      int uncertaintyCount,
      List<String> consumedIdPrefixes,
      String taskIntegrityHash,
      String traceRootHash,
      String bundleIntegrityHash) {}

  private record Observation(
      AgentRun run,
      ArtifactLineage artifact,
      int validationCount,
      int toolExecuteCount,
      int totalFindOwnedCount,
      int toolBackedReadCount,
      int artifactCount,
      int runStartCount,
      int runCompleteCount,
      int modelCallCount,
      List<String> consumedIdPrefixes) {}

  private static final class DeadlinePackModel implements AgentModel {

    private final Frozen frozen;
    private final AtomicInteger calls = new AtomicInteger();

    private DeadlinePackModel(Frozen frozen) {
      this.frozen = Objects.requireNonNull(frozen, "frozen");
    }

    @Override
    public Session open(TaskEnvelope task) {
      return (turn, context) -> {
        int call = calls.incrementAndGet();
        if (call == 1 && turn.toolResults().isEmpty()) {
          return new ModelStep(
              new ToolCall(
                  CaptureReadTool.NAME,
                  ToolArguments.fromJson(
                      "{\"reference\":\"" + frozen.captureRef() + "\"}")),
              frozen.model(),
              ModelUsage.zero());
        }
        if (call == 2 && turn.toolResults().size() == 1) {
          ToolResult source = turn.toolResults().getFirst();
          return new ModelStep(
              new FinalDraft(
                  "# Deadline control\n\n" + source.content(),
                  List.of(source.reference())),
              frozen.model(),
              ModelUsage.zero());
        }
        throw new AssertionError("Pack 006 model received an unexpected turn");
      };
    }

    private int callCount() {
      return calls.get();
    }
  }

  private static final class ClockAdvancingCaptureReadTool
      implements AgentTool<CaptureReadTool.Arguments> {

    private final CaptureReadTool delegate;
    private final AtomicLong now;
    private final long completionNanos;
    private final AtomicInteger validations = new AtomicInteger();
    private final AtomicInteger executions = new AtomicInteger();

    private ClockAdvancingCaptureReadTool(
        CaptureStore captures, AtomicLong now, long completionMs) {
      this.delegate = new CaptureReadTool(captures);
      this.now = Objects.requireNonNull(now, "now");
      this.completionNanos = TimeUnit.MILLISECONDS.toNanos(completionMs);
    }

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public String argumentSchemaId() {
      return delegate.argumentSchemaId();
    }

    @Override
    public Validation<CaptureReadTool.Arguments> validate(
        TaskEnvelope task, AgentModel.ToolCall call) {
      validations.incrementAndGet();
      return delegate.validate(task, call);
    }

    @Override
    public AgentModel.ToolResult execute(
        TaskEnvelope task, CaptureReadTool.Arguments arguments) {
      executions.incrementAndGet();
      AgentModel.ToolResult result = delegate.execute(task, arguments);
      now.set(completionNanos);
      return result;
    }

    private int validationCount() {
      return validations.get();
    }

    private int executeCount() {
      return executions.get();
    }
  }

  private static final class CountingCaptureStore implements CaptureStore {

    private final Capture capture;
    private int findOwnedCount;

    private CountingCaptureStore(Capture capture) {
      this.capture = Objects.requireNonNull(capture, "capture");
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException("Pack 006 Capture is already frozen");
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      findOwnedCount++;
      if (capture.principalId().equals(principalId)
          && capture.captureId().equals(captureId)) {
        return Optional.of(capture);
      }
      return Optional.empty();
    }

    private int findOwnedCount() {
      return findOwnedCount;
    }
  }

  private static final class RecordingRunStore implements AgentRunStore {

    private AgentRun stored;
    private int starts;
    private int completions;
    private int artifacts;

    @Override
    public AgentRun start(AgentRun running) {
      if (stored != null || running.lifecycle() != AgentRunLifecycle.RUNNING) {
        throw new IllegalStateException("Pack 006 Run must start exactly once");
      }
      starts++;
      stored = running;
      return running;
    }

    @Override
    public CompletionResult complete(
        AgentRun terminal, ArtifactLineage proposedArtifact) {
      if (stored == null
          || stored.lifecycle() != AgentRunLifecycle.RUNNING
          || !terminal.lifecycle().terminal()
          || !stored.runId().equals(terminal.runId())
          || !stored.principalId().equals(terminal.principalId())
          || !stored.task().equals(terminal.task())) {
        throw new IllegalStateException("Pack 006 terminal transition is invalid");
      }
      completions++;
      if (proposedArtifact != null) {
        artifacts++;
      }
      stored = terminal;
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public Optional<AgentRun> findOwned(String principalId, String runId) {
      if (stored != null
          && stored.principalId().equals(principalId)
          && stored.runId().equals(runId)) {
        return Optional.of(stored);
      }
      return Optional.empty();
    }

    private int startCount() {
      return starts;
    }

    private int completeCount() {
      return completions;
    }

    private int artifactCount() {
      return artifacts;
    }
  }

  private static final class FrozenIdGenerator implements IdGenerator {

    private final Map<String, String> ids;
    private final Set<String> consumed = new LinkedHashSet<>();

    private FrozenIdGenerator(Map<String, String> ids) {
      this.ids = Map.copyOf(ids);
    }

    @Override
    public String next(String prefix) {
      String id = ids.get(prefix);
      if (id == null || !consumed.add(prefix)) {
        throw new IllegalStateException("unexpected deterministic ID request: " + prefix);
      }
      return id;
    }

    private void requireConsumed(boolean artifactExpected) {
      Set<String> expected =
          artifactExpected ? Set.of("run", "task", "art") : Set.of("run", "task");
      if (!consumed.equals(expected)) {
        throw new IllegalStateException(
            "deterministic ID consumption drifted: " + consumed);
      }
    }

    private List<String> consumedPrefixes() {
      return List.copyOf(consumed);
    }
  }
}
