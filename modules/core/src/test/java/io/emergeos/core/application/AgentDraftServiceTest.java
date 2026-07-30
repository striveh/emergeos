package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
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
import io.emergeos.core.port.AgentTaskAuthorizer;
import io.emergeos.core.port.CaptureStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void modelBoundProfileProducesTaskAndBundleV11ButKeepsResultAndTraceV10() {
    RecordingArtifactStore artifacts = new RecordingArtifactStore();
    RecordingAgentRunStore runs = new RecordingAgentRunStore(artifacts);
    AtomicReference<TaskEnvelope> observedTask = new AtomicReference<>();
    AgentExecutionProfile profile = modelBoundProfile();
    AgentDraftService service =
        service(
            runs,
            profileBoundKernel(
                profile,
                (task, cancellation) -> {
                  observedTask.set(task);
                  return new AgentRunOutcome(
                      RunStatus.FAILED,
                      null,
                      List.of(),
                      List.of(
                          new AgentTraceEvent(
                              1,
                              AgentTraceEventType.MODEL_STEP,
                              null,
                              "FAILED",
                              "task://" + task.id())),
                      "gpt-5.6-sol-2026-07-15",
                      new BigDecimal("0.001000"),
                      100,
                      50,
                      "MODEL_RESPONSE_MALFORMED");
                }),
            capture(DataClass.PUBLIC),
            profile);

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "Create a synthetic public draft"));

    TaskEnvelope task = observedTask.get();
    assertEquals("1.1", task.schemaVersion());
    assertEquals("1.1", outcome.run().bundle().schemaVersion());
    assertEquals("1.0", outcome.result().schemaVersion());
    assertEquals("1.0", outcome.run().trace().schemaVersion());
    assertEquals(DataClass.PUBLIC, task.dataClass());
    assertEquals(RiskLevel.EXTERNAL, task.risk());
    assertEquals(profile.modelProvider(), task.modelProvider());
    assertEquals(profile.modelRequested(), task.modelRequested());
    assertEquals(profile.pricingProfile(), task.pricingProfile());
    assertEquals(profile.taskIdempotencyKey(task.id()), task.idempotencyKey());
    assertEquals(profile.environmentSnapshotRef(), task.environmentSnapshotRef());
    assertEquals(profile.capabilityRefs(), task.capabilityRefs());
    assertEquals(profile.experiment(), outcome.run().bundle().experiment());
    assertEquals(
        profile.modelAdapterVersion(),
        outcome.run().bundle().componentVersions().get("model-adapter"));
    assertEquals(1, runs.startCalls);
    assertEquals(1, runs.completeCalls);
    assertEquals(0, artifacts.createCalls);
  }

  @Test
  void modelBoundProfileRejectsMissingOrNonPublicCaptureBeforeRunAndKernel() {
    for (Capture unsafeCapture : List.of(capture(DataClass.PERSONAL))) {
      assertPreflightDataRejection(unsafeCapture);
    }
    assertPreflightDataRejection(null);
  }

  @Test
  void modelBoundProfileRejectsAMismatchedKernelAtCompositionTime() {
    AgentExecutionProfile profile = modelBoundProfile();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(
                new RecordingAgentRunStore(new RecordingArtifactStore()),
                (task, cancellation) -> {
                  throw new AssertionError("kernel must not run");
                },
                capture(DataClass.PUBLIC),
                profile));
  }

  @Test
  void modelBoundProfileRejectsSameIdWithDifferentFingerprintAtCompositionTime() {
    AgentExecutionProfile serviceProfile = modelBoundProfile();
    AgentExecutionProfile kernelProfile =
        new AgentExecutionProfile(
            serviceProfile.id(),
            serviceProfile.taskSchemaVersion(),
            serviceProfile.risk(),
            serviceProfile.maxModelSteps(),
            serviceProfile.maxToolCalls(),
            serviceProfile.deadlineMs(),
            serviceProfile.budgetUsd(),
            serviceProfile.maxInputTokensPerStep(),
            serviceProfile.maxOutputTokensPerStep(),
            new PricingProfile(
                serviceProfile.pricing().id(),
                serviceProfile.pricing().provider(),
                serviceProfile.pricing().modelRequested(),
                serviceProfile.pricing().uncachedInputNanoUsdPerToken(),
                serviceProfile.pricing().cachedInputNanoUsdPerToken() + 1,
                serviceProfile.pricing().outputNanoUsdPerToken()),
            serviceProfile.modelAdapterVersion(),
            serviceProfile.agentVersion(),
            serviceProfile.verifierVersion(),
            serviceProfile.harnessVersion(),
            serviceProfile.experiment(),
            serviceProfile.policyVersion(),
            serviceProfile.stateVersion(),
            serviceProfile.contextPolicyVersion(),
            serviceProfile.toolRegistryVersion(),
            serviceProfile.environmentSnapshotRef(),
            serviceProfile.capabilityRefs(),
            serviceProfile.requiredDataClass());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(
                new RecordingAgentRunStore(new RecordingArtifactStore()),
                profileBoundKernel(
                    kernelProfile,
                    (task, cancellation) -> {
                      throw new AssertionError("kernel must not run");
                    }),
                capture(DataClass.PUBLIC),
                serviceProfile));
  }

  @Test
  void publicServiceRejectsAProfileClaimingADifferentVerifierAtCompositionTime() {
    AgentExecutionProfile mismatchedProfile =
        copyWithVerifier(modelBoundProfile(), "schema-only-eval-v1");
    AgentExecutionProfile mismatchedOfflineProfile =
        copyWithVerifier(
            AgentExecutionProfile.legacyFakeV1(),
            "schema-only-eval-v1");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(
                new RecordingAgentRunStore(new RecordingArtifactStore()),
                profileBoundKernel(
                    mismatchedProfile,
                    (task, cancellation) -> {
                      throw new AssertionError("kernel must not run");
                    }),
                capture(DataClass.PUBLIC),
                mismatchedProfile));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDraftService(
                (task, cancellation) -> {
                  throw new AssertionError("kernel must not run");
                },
                new RecordingAgentRunStore(new RecordingArtifactStore()),
                new FixedCaptureStore(capture()),
                prefix -> prefix + "-test",
                Clock.fixed(
                    Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
                mismatchedOfflineProfile));
  }

  @Test
  void publicConstructorsDoNotExposeVerifierSelection() {
    assertFalse(
        Arrays.stream(AgentDraftService.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
            .anyMatch(AgentDraftVerifier.class::equals));
  }

  @Test
  void resultAndBundleVersionsComeFromTheActuallyBoundInternalVerifier() {
    RecordingArtifactStore artifacts = new RecordingArtifactStore();
    RecordingAgentRunStore runs = new RecordingAgentRunStore(artifacts);
    AgentExecutionProfile profile =
        copyWithVerifier(modelBoundProfile(), "test-reference-verifier-v1");
    AtomicInteger verifierCalls = new AtomicInteger();
    AgentDraftVerifier verifier =
        new AgentDraftVerifier() {
          @Override
          public String version() {
            return "test-reference-verifier-v1";
          }

          @Override
          public AgentDraftReferenceGrounding.Verification verify(
              AgentDraftReferenceGrounding.Candidate candidate) {
            verifierCalls.incrementAndGet();
            return ReferenceGroundingAgentDraftVerifier.INSTANCE.verify(candidate);
          }
        };
    AgentDraftService service =
        service(
            runs,
            profileBoundKernel(
                profile,
                (task, cancellation) ->
                    new AgentRunOutcome(
                        RunStatus.SUCCEEDED,
                        new AgentDraftProposal(
                            "grounded synthetic draft",
                            List.of("capture://" + CAPTURE_ID)),
                        List.of("capture://" + CAPTURE_ID),
                        successTrace(List.of("capture://" + CAPTURE_ID)),
                        "test-reference-model",
                        BigDecimal.ZERO,
                        0,
                        0,
                        null)),
            capture(DataClass.PUBLIC),
            profile,
            verifier);

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "Create a synthetic public draft"));

    assertEquals(RunStatus.SUCCEEDED, outcome.result().status());
    assertEquals(1, verifierCalls.get());
    assertEquals(
        "test-reference-verifier-v1", outcome.result().verifierVersion());
    assertEquals(
        "test-reference-verifier-v1",
        outcome.run().bundle().componentVersions().get("verifier"));
    assertEquals(1, artifacts.createCalls);
  }

  @Test
  void unexpectedOrNullInternalVerifierResultTerminalizesWithoutArtifactOrDetailLeak() {
    List<AgentDraftVerifier> brokenVerifiers =
        List.of(
            new AgentDraftVerifier() {
              @Override
              public String version() {
                return "test-throwing-verifier-v1";
              }

              @Override
              public AgentDraftReferenceGrounding.Verification verify(
                  AgentDraftReferenceGrounding.Candidate candidate) {
                throw new IllegalStateException(
                    "SECRET_VERIFIER_DETAIL");
              }
            },
            new AgentDraftVerifier() {
              @Override
              public String version() {
                return "test-null-verifier-v1";
              }

              @Override
              public AgentDraftReferenceGrounding.Verification verify(
                  AgentDraftReferenceGrounding.Candidate candidate) {
                return null;
              }
            });

    for (AgentDraftVerifier verifier : brokenVerifiers) {
      RecordingArtifactStore artifacts =
          new RecordingArtifactStore();
      RecordingAgentRunStore runs =
          new RecordingAgentRunStore(artifacts);
      AgentExecutionProfile profile =
          copyWithVerifier(modelBoundProfile(), verifier.version());
      AgentDraftService service =
          service(
              runs,
              profileBoundKernel(
                  profile,
                  (task, cancellation) ->
                      new AgentRunOutcome(
                          RunStatus.SUCCEEDED,
                          new AgentDraftProposal(
                              "synthetic candidate",
                              List.of("capture://" + CAPTURE_ID)),
                          List.of("capture://" + CAPTURE_ID),
                          successTrace(
                              List.of("capture://" + CAPTURE_ID)),
                          "test-reference-model",
                          BigDecimal.ZERO,
                          0,
                          0,
                          null)),
              capture(DataClass.PUBLIC),
              profile,
              verifier);

      AgentDraftOutcome outcome =
          service.draft(
              new AgentDraftCommand(
                  PRINCIPAL,
                  CAPTURE_ID,
                  "Create a synthetic public draft"));

      assertEquals(RunStatus.FAILED, outcome.result().status());
      assertEquals(
          "AGENT_VERIFIER_FAILED",
          outcome.result().failureReason());
      assertFalse(
          outcome.run().toString().contains("SECRET_VERIFIER_DETAIL"));
      assertEquals(1, runs.startCalls);
      assertEquals(1, runs.completeCalls);
      assertEquals(0, artifacts.createCalls);
    }
  }

  @Test
  void legacyFakeProfileRejectsAKernelBoundToAnyLiveProfile() {
    AgentExecutionProfile liveProfile = modelBoundProfile();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service(
                new RecordingAgentRunStore(new RecordingArtifactStore()),
                profileBoundKernel(
                    liveProfile,
                    (task, cancellation) -> {
                      throw new AssertionError("kernel must not run");
                    }),
                capture(),
                AgentExecutionProfile.legacyFakeV1()));
  }

  @Test
  void taskAuthorizationFailureOccursBeforeRunStartAndKernelExecution() {
    RecordingArtifactStore artifacts = new RecordingArtifactStore();
    RecordingAgentRunStore runs = new RecordingAgentRunStore(artifacts);
    AtomicInteger authorizerCalls = new AtomicInteger();
    AtomicInteger kernelCalls = new AtomicInteger();
    AgentExecutionProfile profile = modelBoundProfile();
    AgentDraftService service =
        new AgentDraftService(
            profileBoundKernel(
                profile,
                (task, cancellation) -> {
                  kernelCalls.incrementAndGet();
                  throw new AssertionError("kernel must not run");
                }),
            runs,
            new FixedCaptureStore(capture(DataClass.PUBLIC)),
            prefix -> prefix + "-test",
            Clock.fixed(
                Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
            profile,
            task -> {
              authorizerCalls.incrementAndGet();
              throw new IllegalArgumentException("synthetic permit missing");
            });

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.draft(
                new AgentDraftCommand(
                    PRINCIPAL, CAPTURE_ID, "Create a synthetic public draft")));
    assertEquals(1, authorizerCalls.get());
    assertEquals(0, runs.startCalls);
    assertEquals(0, kernelCalls.get());
    assertEquals(0, artifacts.createCalls);
  }

  @Test
  void modelBoundServiceRejectsMissingOrUnrestrictedTaskAuthorization() {
    AgentExecutionProfile profile = modelBoundProfile();
    AgentKernel kernel =
        profileBoundKernel(
            profile,
            (task, cancellation) -> {
              throw new AssertionError("kernel must not run");
            });
    RecordingAgentRunStore runs =
        new RecordingAgentRunStore(new RecordingArtifactStore());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDraftService(
                kernel,
                runs,
                new FixedCaptureStore(capture(DataClass.PUBLIC)),
                prefix -> prefix + "-test",
                Clock.fixed(
                    Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
                profile));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentDraftService(
                kernel,
                runs,
                new FixedCaptureStore(capture(DataClass.PUBLIC)),
                prefix -> prefix + "-test",
                Clock.fixed(
                    Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
                profile,
                AgentTaskAuthorizer.allowAll()));
    assertEquals(0, runs.startCalls);
  }

  @Test
  void unexpectedKernelFailureTerminalizesTheStartedRun() {
    RecordingArtifactStore artifacts = new RecordingArtifactStore();
    RecordingAgentRunStore runs = new RecordingAgentRunStore(artifacts);
    AgentDraftService service =
        service(
            runs,
            (task, cancellation) -> {
              throw new IllegalStateException("unsafe provider detail");
            });

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                PRINCIPAL, CAPTURE_ID, "Create an article draft"));

    assertEquals(RunStatus.FAILED, outcome.result().status());
    assertEquals("AGENT_KERNEL_FAILED", outcome.result().failureReason());
    assertEquals(1, runs.startCalls);
    assertEquals(1, runs.completeCalls);
    assertEquals(AgentRunLifecycle.FAILED, runs.stored.lifecycle());
    assertEquals(0, artifacts.createCalls);
  }

  private static AgentDraftService service(
      RecordingArtifactStore artifactStore, AgentKernel kernel) {
    return service(new RecordingAgentRunStore(artifactStore), kernel);
  }

  private static AgentDraftService service(
      RecordingAgentRunStore runStore, AgentKernel kernel) {
    return service(
        runStore,
        kernel,
        capture(),
        AgentExecutionProfile.legacyFakeV1());
  }

  private static AgentDraftService service(
      RecordingAgentRunStore runStore,
      AgentKernel kernel,
      Capture capture,
      AgentExecutionProfile executionProfile) {
    if (!executionProfile.modelBound()) {
      return new AgentDraftService(
          kernel,
          runStore,
          new FixedCaptureStore(capture),
          prefix -> prefix + "-test",
          Clock.fixed(
              Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
          executionProfile);
    }
    return new AgentDraftService(
        kernel,
        runStore,
        new FixedCaptureStore(capture),
        prefix -> prefix + "-test",
        Clock.fixed(
            Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
        executionProfile,
        exactPermit(executionProfile));
  }

  private static AgentDraftService service(
      RecordingAgentRunStore runStore,
      AgentKernel kernel,
      Capture capture,
      AgentExecutionProfile executionProfile,
      AgentDraftVerifier verifier) {
    return new AgentDraftService(
        kernel,
        runStore,
        new FixedCaptureStore(capture),
        prefix -> prefix + "-test",
        Clock.fixed(
            Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
        executionProfile,
        executionProfile.modelBound()
            ? exactPermit(executionProfile)
            : AgentTaskAuthorizer.allowAll(),
        verifier);
  }

  private static AgentTaskAuthorizer exactPermit(
      AgentExecutionProfile executionProfile) {
    TaskEnvelope permittedTask =
        executionProfile.newDraftTask(
            "task-test",
            PRINCIPAL,
            "Create a synthetic public draft",
            "capture://" + CAPTURE_ID,
            DataClass.PUBLIC);
    String permittedTaskHash = IntegrityHashes.taskHash(permittedTask);
    AgentTaskAuthorizer exactPermit =
        task -> {
          if (!permittedTaskHash.equals(IntegrityHashes.taskHash(task))) {
            throw new IllegalArgumentException(
                "task is not covered by the explicit test permit");
          }
        };
    return exactPermit;
  }

  private static void assertPreflightDataRejection(Capture capture) {
    RecordingArtifactStore artifacts = new RecordingArtifactStore();
    RecordingAgentRunStore runs = new RecordingAgentRunStore(artifacts);
    AtomicInteger kernelCalls = new AtomicInteger();
    AgentDraftService service =
        service(
            runs,
            profileBoundKernel(
                modelBoundProfile(),
                (task, cancellation) -> {
                  kernelCalls.incrementAndGet();
                  throw new AssertionError("kernel must not run");
                }),
            capture,
            modelBoundProfile());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.draft(
                new AgentDraftCommand(
                    PRINCIPAL, CAPTURE_ID, "Create a synthetic public draft")));
    assertEquals(0, kernelCalls.get());
    assertEquals(0, runs.startCalls);
    assertEquals(0, runs.completeCalls);
    assertEquals(0, artifacts.createCalls);
  }

  private static AgentExecutionProfile modelBoundProfile() {
    return new AgentExecutionProfile(
        "synthetic-openai-agent-draft-v1",
        "1.1",
        RiskLevel.EXTERNAL,
        2,
        1,
        30_000,
        new BigDecimal("0.022000"),
        1_000,
        200,
        new PricingProfile(
            "openai-gpt-5.6-sol-2026-07-v1",
            "openai.responses",
            "gpt-5.6-sol",
            5_000,
            500,
            30_000),
        "openai-responses-v1-openai-java-4.43.0",
        "agent-draft-service-v1",
        "agent-draft-verifier-v1",
        "framework-free-agent-kernel-v2",
        new HarnessExperiment("openai-responses-h0", 1),
        "synthetic-model-egress-policy-v1",
        "stage2-s3",
        "ref-only-v1",
        "agent-tools-v1",
        "environment://sha256:" + "a".repeat(64),
        List.of(AgentExecutionProfile.SYNTHETIC_MODEL_EGRESS_CAPABILITY),
        DataClass.PUBLIC);
  }

  private static AgentExecutionProfile copyWithVerifier(
      AgentExecutionProfile source, String verifierVersion) {
    return new AgentExecutionProfile(
        source.id(),
        source.taskSchemaVersion(),
        source.risk(),
        source.maxModelSteps(),
        source.maxToolCalls(),
        source.deadlineMs(),
        source.budgetUsd(),
        source.maxInputTokensPerStep(),
        source.maxOutputTokensPerStep(),
        source.pricing(),
        source.modelAdapterVersion(),
        source.agentVersion(),
        verifierVersion,
        source.harnessVersion(),
        source.experiment(),
        source.policyVersion(),
        source.stateVersion(),
        source.contextPolicyVersion(),
        source.toolRegistryVersion(),
        source.environmentSnapshotRef(),
        source.capabilityRefs(),
        source.requiredDataClass());
  }

  private static AgentKernel profileBoundKernel(
      AgentExecutionProfile profile, AgentKernel delegate) {
    return new AgentKernel() {
      @Override
      public AgentRunOutcome run(
          TaskEnvelope task,
          io.emergeos.core.port.CancellationSignal cancellation) {
        return delegate.run(task, cancellation);
      }

      @Override
      public String executionProfileId() {
        return profile.id();
      }

      @Override
      public String executionProfileFingerprint() {
        return profile.fingerprint();
      }
    };
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
    return capture(DataClass.PERSONAL);
  }

  private static Capture capture(DataClass dataClass) {
    String content = "synthetic owned Capture";
    return new Capture(
        CAPTURE_ID,
        PRINCIPAL,
        "agent-draft-service-test",
        CaptureRequestHashes.sha256(
            content, CaptureSourceType.TEXT, "synthetic", dataClass),
        content,
        CaptureSourceType.TEXT,
        "synthetic",
        dataClass,
        Instant.parse("2026-07-30T00:00:00Z"));
  }

  private static final class RecordingArtifactStore {
    private int createCalls;
  }

  private static final class RecordingAgentRunStore implements AgentRunStore {
    private final RecordingArtifactStore artifacts;
    private AgentRun stored;
    private int startCalls;
    private int completeCalls;

    private RecordingAgentRunStore(RecordingArtifactStore artifacts) {
      this.artifacts = artifacts;
    }

    @Override
    public AgentRun start(AgentRun running) {
      startCalls++;
      stored = running;
      return running;
    }

    @Override
    public CompletionResult complete(AgentRun terminal, ArtifactLineage proposedArtifact) {
      completeCalls++;
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
      if (capture != null
          && capture.principalId().equals(principalId)
          && capture.captureId().equals(captureId)) {
        return Optional.of(capture);
      }
      return Optional.empty();
    }
  }
}
