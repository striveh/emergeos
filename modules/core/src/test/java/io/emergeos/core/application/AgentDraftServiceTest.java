package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.ArtifactLineageStore;
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
                    List.of(),
                    "evidence-free-fake",
                    BigDecimal.ZERO,
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
                    List.of(),
                    "direct-final-fake",
                    BigDecimal.ZERO,
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
                    List.of(),
                    "overclaiming-fake",
                    BigDecimal.ZERO,
                    0,
                    null));

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("INVALID_EVIDENCE_CLAIM", outcome.result().failureReason());
    assertEquals(List.of(), outcome.result().evidenceRefs());
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
                      List.of(),
                      "invalid-content-fake",
                      BigDecimal.ZERO,
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

  private static AgentDraftService service(
      RecordingArtifactStore artifactStore, AgentKernel kernel) {
    ArtifactLineageService artifacts =
        new ArtifactLineageService(
            artifactStore,
            new NeverReadCaptureStore(),
            prefix -> prefix + "-test",
            Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));
    return new AgentDraftService(kernel, artifacts, prefix -> prefix + "-test");
  }

  private static final class RecordingArtifactStore implements ArtifactLineageStore {
    private int createCalls;

    @Override
    public ArtifactLineage create(ArtifactLineage proposed) {
      createCalls++;
      return proposed;
    }

    @Override
    public RevisionResult compareAndSwap(
        String principalId,
        String artifactId,
        int expectedBaseVersion,
        String expectedBaseHash,
        ArtifactLineageEntry proposed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ArtifactLineage> findOwned(String principalId, String artifactId) {
      return Optional.empty();
    }
  }

  private static final class NeverReadCaptureStore implements CaptureStore {

    @Override
    public SaveResult saveOrFindByNonce(io.emergeos.core.domain.Capture proposed) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<io.emergeos.core.domain.Capture> findOwned(
        String principalId, String captureId) {
      throw new AssertionError("invalid proposal must be rejected before Artifact creation");
    }
  }
}
