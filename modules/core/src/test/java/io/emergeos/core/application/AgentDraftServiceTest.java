package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.AgentRunStore;
import io.emergeos.core.port.CaptureStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentDraftServiceTest {

  private static final String PRINCIPAL = "agent-draft-owner";
  private static final String CAPTURE_ID = "agent-draft-capture";

  @Test
  void commandIntentUsesTheSameCodePointSafeTextBoundaryAsTaskEnvelope() {
    assertDoesNotThrow(
        () ->
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "😀".repeat(2_048)));
    assertDoesNotThrow(
        () ->
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "\n正文"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "😀".repeat(2_049)));
  }

  @Test
  void rejectsAMalformedSuccessfulRunWithoutCreatingAnArtifact() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    AgentDraftService service =
        service(
            artifactStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    null,
                    List.of(),
                    List.of(),
                    "malformed-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    null));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals(List.of(), outcome.result().artifactRefs());
    assertEquals(0, artifactStore.createCalls);
  }

  @Test
  void rejectsAProposalWithoutTheCaptureEvidenceWithoutCreatingAnArtifact() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    AgentDraftService service =
        service(
            artifactStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal("synthetic unsupported draft", List.of()),
                    List.of("capture://" + CAPTURE_ID),
                    successTrace(List.of("capture://" + CAPTURE_ID)),
                    "evidence-free-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    null));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals(List.of(), outcome.result().artifactRefs());
    assertEquals(0, artifactStore.createCalls);
  }

  @Test
  void rejectsModelClaimedEvidenceThatWasNeverObtainedByATool() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    String captureRef = "capture://" + CAPTURE_ID;
    AgentDraftService service =
        service(
            artifactStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal("synthetic forged draft", List.of(captureRef)),
                    List.of(),
                    successTrace(List.of()),
                    "direct-final-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    null));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("MISSING_REQUIRED_EVIDENCE", outcome.result().failureReason());
    assertEquals(List.of(), outcome.result().evidenceRefs());
    assertEquals(0, artifactStore.createCalls);
  }

  @Test
  void rejectsModelClaimedEvidenceOutsideTheVerifiedToolResults() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    String captureRef = "capture://" + CAPTURE_ID;
    AgentDraftService service =
        service(
            artifactStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal(
                        "synthetic overclaimed draft",
                        List.of(captureRef, "capture://forged")),
                    List.of(captureRef),
                    successTrace(List.of(captureRef)),
                    "overclaiming-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    null));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("INVALID_EVIDENCE_CLAIM", outcome.result().failureReason());
    assertEquals(List.of(captureRef), outcome.result().evidenceRefs());
    assertEquals(0, artifactStore.createCalls);
  }

  @Test
  void rejectsArtifactContentOutsideThePersistentInvariantAsAnAgentFailure() {
    String captureRef = "capture://" + CAPTURE_ID;
    for (String invalidContent : List.of("x".repeat(65_537), "before\0after")) {
      RecordingArtifactStore artifactStore = new RecordingArtifactStore();
      AgentDraftService service =
          service(
              artifactStore,
              (task, cancellation) ->
                  new AgentRunOutcome(
                      RunStatus.SUCCEEDED,
                      new AgentDraftProposal(invalidContent, List.of(captureRef)),
                      List.of(captureRef),
                      successTrace(List.of(captureRef)),
                      "invalid-content-fake",
                      BigDecimal.ZERO,
                      0,
                      0,
                      null));

      AgentDraftOutcome outcome =
          service.draft(
              new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

      assertEquals(RunStatus.FAILED, outcome.result().status());
      assertEquals("INVALID_STRUCTURED_FINAL", outcome.result().failureReason());
      assertEquals(List.of(), outcome.result().artifactRefs());
      assertEquals(0, artifactStore.createCalls);
    }
  }

  @Test
  void hostileTraceIsTerminalizedWithoutPersistingItsMetadata() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    RecordingAgentRunStore runStore = new RecordingAgentRunStore(artifactStore);
    String sentinel = "SECRET_TRACE_SENTINEL";
    AgentDraftService service =
        service(
            runStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.FAILED,
                    null,
                    List.of(),
                    List.of(
                        new AgentTraceEvent(
                            1,
                            AgentTraceEventType.MODEL_STEP,
                            null,
                            sentinel,
                            "task://" + task.id())),
                    "hostile-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    "MODEL_STEP_FAILED"));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("UNSAFE_AGENT_TRACE", outcome.result().failureReason());
    assertEquals(List.of(), outcome.trace());
    assertEquals(AgentRunLifecycle.FAILED, runStore.stored.lifecycle());
    assertFalse(runStore.stored.toString().contains(sentinel));
  }

  @Test
  void orphanToolResultCannotBecomeDurableEvidence() {
    String captureRef = "capture://" + CAPTURE_ID;
    assertBoundaryFailure(
        (task, cancellation) ->
            new AgentRunOutcome(
                RunStatus.SUCCEEDED,
                new AgentDraftProposal("forged orphan result", List.of(captureRef)),
                List.of(captureRef),
                List.of(
                    new AgentTraceEvent(
                        1,
                        AgentTraceEventType.MODEL_STEP,
                        null,
                        "COMPLETED",
                        "task://" + task.id()),
                    new AgentTraceEvent(
                        2,
                        AgentTraceEventType.TOOL_RESULT,
                        "capture.read",
                        "SUCCEEDED",
                        captureRef),
                    new AgentTraceEvent(
                        3,
                        AgentTraceEventType.STRUCTURED_FINAL,
                        null,
                        "PROPOSED",
                        "task://" + task.id())),
                "orphan-result-fake",
                BigDecimal.ZERO,
                0,
                0,
                null),
        "UNSAFE_AGENT_TRACE");
  }

  @Test
  void toolAndModelExecutionCannotExceedTheTaskLimits() {
    String captureRef = "capture://" + CAPTURE_ID;
    assertBoundaryFailure(
        (task, cancellation) ->
            new AgentRunOutcome(
                RunStatus.SUCCEEDED,
                new AgentDraftProposal("over-limit result", List.of(captureRef)),
                List.of(captureRef),
                List.of(
                    new AgentTraceEvent(
                        1,
                        AgentTraceEventType.MODEL_STEP,
                        null,
                        "COMPLETED",
                        "task://" + task.id()),
                    new AgentTraceEvent(
                        2,
                        AgentTraceEventType.TOOL_REQUEST,
                        "capture.read",
                        "REQUESTED",
                        captureRef),
                    new AgentTraceEvent(
                        3,
                        AgentTraceEventType.TOOL_RESULT,
                        "capture.read",
                        "SUCCEEDED",
                        captureRef),
                    new AgentTraceEvent(
                        4,
                        AgentTraceEventType.MODEL_STEP,
                        null,
                        "COMPLETED",
                        "task://" + task.id()),
                    new AgentTraceEvent(
                        5,
                        AgentTraceEventType.TOOL_REQUEST,
                        "capture.read",
                        "REQUESTED",
                        captureRef),
                    new AgentTraceEvent(
                        6,
                        AgentTraceEventType.TOOL_RESULT,
                        "capture.read",
                        "SUCCEEDED",
                        captureRef),
                    new AgentTraceEvent(
                        7,
                        AgentTraceEventType.STRUCTURED_FINAL,
                        null,
                        "PROPOSED",
                        "task://" + task.id())),
                "over-limit-fake",
                BigDecimal.ZERO,
                0,
                0,
                null),
        "UNSAFE_AGENT_TRACE");
  }

  @Test
  void successCannotContinueAfterAFailedModelStep() {
    assertBoundaryFailure(
        (task, cancellation) ->
            new AgentRunOutcome(
                RunStatus.SUCCEEDED,
                new AgentDraftProposal("success after failure", List.of()),
                List.of(),
                List.of(
                    new AgentTraceEvent(
                        1,
                        AgentTraceEventType.MODEL_STEP,
                        null,
                        "FAILED",
                        "task://" + task.id()),
                    new AgentTraceEvent(
                        2,
                        AgentTraceEventType.STRUCTURED_FINAL,
                        null,
                        "PROPOSED",
                        "task://" + task.id())),
                "success-after-failure-fake",
                BigDecimal.ZERO,
                0,
                0,
                null),
        "UNSAFE_AGENT_TRACE");
  }

  @Test
  void taskBudgetDeadlineAndResolvedModelAreEnforcedAtTheAdapterBoundary() {
    String captureRef = "capture://" + CAPTURE_ID;
    for (AgentKernel kernel :
        List.<AgentKernel>of(
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal("paid result", List.of(captureRef)),
                    List.of(captureRef),
                    successTrace(List.of(captureRef)),
                    "paid-fake",
                    new BigDecimal("0.000001"),
                    0,
                    0,
                    null),
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal("late result", List.of(captureRef)),
                    List.of(captureRef),
                    successTrace(List.of(captureRef)),
                    "late-fake",
                    BigDecimal.ZERO,
                    0,
                    5_001,
                    null),
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.SUCCEEDED,
                    new AgentDraftProposal("anonymous model result", List.of(captureRef)),
                    List.of(captureRef),
                    successTrace(List.of(captureRef)),
                    null,
                    BigDecimal.ZERO,
                    0,
                    0,
                    null))) {
      assertBoundaryFailure(kernel, "UNSAFE_AGENT_OUTCOME");
    }
  }

  @Test
  void nullKernelOutcomeStillClosesTheRun() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    RecordingAgentRunStore runStore = new RecordingAgentRunStore(artifactStore);
    AgentDraftService service = service(runStore, (task, cancellation) -> null);

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("UNSAFE_AGENT_OUTCOME", outcome.result().failureReason());
    assertEquals(AgentRunLifecycle.FAILED, runStore.stored.lifecycle());
  }

  @Test
  void aFailedRunRetainsEvidenceThatWasActuallyRead() {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    RecordingAgentRunStore runStore = new RecordingAgentRunStore(artifactStore);
    String captureRef = "capture://" + CAPTURE_ID;
    AgentDraftService service =
        service(
            runStore,
            (task, cancellation) ->
                new AgentRunOutcome(
                    RunStatus.FAILED,
                    null,
                    List.of(captureRef),
                    List.of(
                        new AgentTraceEvent(
                            1,
                            AgentTraceEventType.MODEL_STEP,
                            null,
                            "COMPLETED",
                            "task://" + task.id()),
                        new AgentTraceEvent(
                            2,
                            AgentTraceEventType.TOOL_REQUEST,
                            "capture.read",
                            "REQUESTED",
                            captureRef),
                        new AgentTraceEvent(
                            3,
                            AgentTraceEventType.TOOL_RESULT,
                            "capture.read",
                            "SUCCEEDED",
                            captureRef),
                        new AgentTraceEvent(
                            4,
                            AgentTraceEventType.MODEL_STEP,
                            null,
                            "FAILED",
                            "task://" + task.id())),
                    "failing-fake",
                    BigDecimal.ZERO,
                    0,
                    0,
                    "MODEL_STEP_FAILED"));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals(List.of(captureRef), outcome.result().evidenceRefs());
    assertEquals(
        List.of(ResourceRole.EVIDENCE),
        outcome.run().bundle().resourceBindings().stream()
            .map(binding -> binding.role())
            .toList());
    assertEquals(0, artifactStore.createCalls);
  }

  private static AgentDraftService service(
      RecordingArtifactStore artifactStore, AgentKernel kernel) {
    return service(new RecordingAgentRunStore(artifactStore), kernel);
  }

  private static AgentDraftService service(
      RecordingAgentRunStore runStore, AgentKernel kernel) {
    return new AgentDraftService(
        kernel,
        runStore,
        new FixedCaptureStore(capture()),
        prefix -> prefix + "-test",
        Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));
  }

  private static void assertBoundaryFailure(AgentKernel kernel, String expectedReason) {
    RecordingArtifactStore artifactStore = new RecordingArtifactStore();
    RecordingAgentRunStore runStore = new RecordingAgentRunStore(artifactStore);
    AgentDraftOutcome outcome =
        service(runStore, kernel)
            .draft(
                new AgentDraftCommand(
                    PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals(expectedReason, outcome.result().failureReason());
    assertEquals(List.of(), outcome.result().artifactRefs());
    assertEquals(AgentRunLifecycle.FAILED, runStore.stored.lifecycle());
    assertEquals(0, artifactStore.createCalls);
  }

  private static List<AgentTraceEvent> successTrace(List<String> obtainedEvidenceRefs) {
    List<AgentTraceEvent> events = new java.util.ArrayList<>();
    events.add(
        new AgentTraceEvent(
            1,
            AgentTraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://task-test"));
    if (!obtainedEvidenceRefs.isEmpty()) {
      String reference = obtainedEvidenceRefs.getFirst();
      events.add(
          new AgentTraceEvent(
              events.size() + 1,
              AgentTraceEventType.TOOL_REQUEST,
              "capture.read",
              "REQUESTED",
              reference));
      events.add(
          new AgentTraceEvent(
              events.size() + 1,
              AgentTraceEventType.TOOL_RESULT,
              "capture.read",
              "SUCCEEDED",
              reference));
      events.add(
          new AgentTraceEvent(
              events.size() + 1,
              AgentTraceEventType.MODEL_STEP,
              null,
              "COMPLETED",
              "task://task-test"));
    }
    events.add(
        new AgentTraceEvent(
            events.size() + 1,
            AgentTraceEventType.STRUCTURED_FINAL,
            null,
            "PROPOSED",
            "task://task-test"));
    return List.copyOf(events);
  }

  private static Capture capture() {
    String content = "synthetic owned Capture";
    return new Capture(
        CAPTURE_ID,
        PRINCIPAL,
        "agent-draft-service-test",
        CaptureRequestHashes.sha256(
            content, CaptureSourceType.TEXT, "synthetic", DataClass.PERSONAL),
        content,
        CaptureSourceType.TEXT,
        "synthetic",
        DataClass.PERSONAL,
        Instant.parse("2026-07-30T00:00:00Z"));
  }

  private static final class RecordingArtifactStore {
    private int createCalls;
  }

  private static final class RecordingAgentRunStore implements AgentRunStore {
    private final RecordingArtifactStore artifacts;
    private AgentRun stored;

    private RecordingAgentRunStore(RecordingArtifactStore artifacts) {
      this.artifacts = artifacts;
    }

    @Override
    public AgentRun start(AgentRun running) {
      stored = running;
      return running;
    }

    @Override
    public CompletionResult complete(AgentRun terminal, ArtifactLineage proposedArtifact) {
      if (proposedArtifact != null) {
        artifacts.createCalls++;
      }
      stored = terminal;
      return new CompletionResult(terminal, proposedArtifact);
    }

    @Override
    public Optional<AgentRun> findOwned(String principalId, String runId) {
      return Optional.empty();
    }
  }

  private static final class FixedCaptureStore implements CaptureStore {

    private final Capture capture;

    private FixedCaptureStore(Capture capture) {
      this.capture = capture;
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      if (capture.principalId().equals(principalId)
          && capture.captureId().equals(captureId)) {
        return Optional.of(capture);
      }
      return Optional.empty();
    }
  }
}
