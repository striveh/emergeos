package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.ResourceBinding;
import io.emergeos.contracts.ResourceRole;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.TraceEventType;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.application.PricingProfile;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.CancellationSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphAttemptTerminalProtocolTest {

  private static final Instant STARTED =
      Instant.parse("2026-07-31T06:00:00Z");
  private static final String MODEL = "gpt-5.6-terra";
  private static final String PROVIDER_ACTOR =
      "OPENAI_RESPONSES";
  private static final String REQUEST_1 = "1".repeat(64);
  private static final String REQUEST_2 = "2".repeat(64);
  private static final BigDecimal ATTRIBUTED_COST =
      new BigDecimal("0.000190");
  private static final long ATTRIBUTED_TOKENS = 30;

  @Test
  void acceptsExactTwoRequestTerminalFailureSeal() {
    ProtocolFixture fixture =
        fixture(AttributionVariant.EXACT, ATTRIBUTED_COST, 30);

    GraphAttemptSnapshot snapshot =
        fixture.snapshot(
            fixture.events(),
            fixture.parentTerminal(),
            fixture.childTerminal(),
            fixture.bindings(),
            fixture.seal(),
            GraphAttemptOutcome.FAILED);

    assertEquals("VALID", snapshot.verdict());
    assertEquals(17, snapshot.cursor().lastSequence());
    assertEquals(
        GraphBillingStatus.ATTRIBUTED,
        snapshot.billingStatus());
  }

  @Test
  void terminalProtocolRejectsAnyRequestLimitOtherThanTwo() {
    GraphAttemptManifest original =
        fixture(AttributionVariant.EXACT, ATTRIBUTED_COST, 30)
            .manifest();

    for (int invalidLimit : List.of(1, 3)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              GraphAttemptManifest.create(
                  original.graphProtocolVersion(),
                  original.principalId(),
                  original.executionSlotId() + "-limit-" + invalidLimit,
                  original.caseId() + "-limit-" + invalidLimit,
                  original.packRawSha256(),
                  original.environmentRawSha256(),
                  original.captureId(),
                  original.captureRequestHash(),
                  original.artifactId() + "-limit-" + invalidLimit,
                  original.startedAt(),
                  original.pricingProfileFingerprint(),
                  original.promptSurfaceFingerprint(),
                  original.conductorSurfaceFingerprint(),
                  original.reservationUsd(),
                  invalidLimit,
                  original.parentActor(),
                  original.childActor(),
                  original.experiment(),
                  original.parentSelection(),
                  original.childSelection()));
    }
  }

  @Test
  void rejectsAttributionThatDoesNotMatchIntentOrManifest() {
    for (AttributionVariant variant :
        List.of(
            AttributionVariant.REQUEST_MISMATCH,
            AttributionVariant.PRICING_MISMATCH)) {
      ProtocolFixture fixture =
          fixture(variant, ATTRIBUTED_COST, ATTRIBUTED_TOKENS);

      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.snapshot(
                  fixture.events(),
                  fixture.parentTerminal(),
                  fixture.childTerminal(),
                  fixture.bindings(),
                  fixture.seal(),
                  GraphAttemptOutcome.FAILED));
    }
  }

  @Test
  void rejectsChildUsageThatIsNotExactlyAttributed() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            new BigDecimal("0.000191"),
            ATTRIBUTED_TOKENS + 1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.snapshot(
                fixture.events(),
                fixture.parentTerminal(),
                fixture.childTerminal(),
                fixture.bindings(),
                fixture.seal(),
                GraphAttemptOutcome.FAILED));
  }

  @Test
  void rejectsCoordinatedWrongCostBeforeItCanBeAttributed() {
    GraphPricingSnapshot pricing =
        GraphPricingSnapshot.create(
            "openai-terra-test-pricing-v1",
            "openai.responses",
            MODEL,
            2_000,
            200,
            12_000);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            attribution(
                1,
                REQUEST_1,
                "a".repeat(64),
                pricing,
                8,
                7,
                new BigDecimal("0.001000")));
  }

  @Test
  void onlyChildTerminalMayBeCommittedBeforeTheSeal() {
    ProtocolFixture fixture =
        fixture(AttributionVariant.EXACT, ATTRIBUTED_COST, 30);
    List<GraphAttemptEvent> childTerminalEvents =
        fixture.events().subList(0, 15);

    GraphAttemptSnapshot childTerminal =
        fixture.snapshot(
            childTerminalEvents,
            fixture.parentRunning(),
            fixture.childTerminal(),
            List.of(fixture.childBinding()),
            null,
            GraphAttemptOutcome.INCOMPLETE);
    assertEquals(
        GraphAttemptPhase.CHILD_TERMINAL,
        childTerminal.cursor().phase());

    List<GraphAttemptEvent> forbiddenParentOnly =
        fixture.events().subList(0, 16);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.snapshot(
                forbiddenParentOnly,
                fixture.parentTerminal(),
                fixture.childTerminal(),
                fixture.bindings(),
                null,
                GraphAttemptOutcome.INCOMPLETE));
  }

  @Test
  void rejectsASealThatDoesNotBindExactProviderTruth() {
    ProtocolFixture fixture =
        fixture(AttributionVariant.EXACT, ATTRIBUTED_COST, 30);
    List<GraphAttemptEvent> beforeSeal =
        fixture.events().subList(0, 16);
    GraphAttemptCursor parentCursor =
        beforeSeal.getLast().cursor(fixture.manifest());
    List<String> tamperedAttributions =
        List.of(
            fixture.attributions().getFirst().attributionHash(),
            "f".repeat(64));
    Instant sealedAt = STARTED.plusMillis(17);
    String sealHash =
        GraphTerminalSeal.computeHash(
            fixture.manifest().attemptId(),
            fixture.manifest().manifestHash(),
            17,
            parentCursor.headHash(),
            GraphAttemptOutcome.FAILED,
            GraphBillingStatus.ATTRIBUTED,
            tamperedAttributions,
            null,
            null,
            fixture.childBinding().terminalHash(),
            fixture.parentBinding().terminalHash(),
            sealedAt);
    GraphAttemptEvent sealedEvent =
        GraphAttemptEvent.sealed(
            parentCursor, sealHash, sealedAt);
    List<GraphAttemptEvent> tamperedEvents =
        new ArrayList<>(beforeSeal);
    tamperedEvents.add(sealedEvent);
    GraphTerminalSeal tamperedSeal =
        new GraphTerminalSeal(
            fixture.manifest().attemptId(),
            fixture.manifest().manifestHash(),
            17,
            parentCursor.headHash(),
            sealedEvent.currentHeadHash(),
            GraphAttemptOutcome.FAILED,
            GraphBillingStatus.ATTRIBUTED,
            tamperedAttributions,
            null,
            null,
            fixture.childBinding().terminalHash(),
            fixture.parentBinding().terminalHash(),
            sealHash,
            sealedAt);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.snapshot(
                tamperedEvents,
                fixture.parentTerminal(),
                fixture.childTerminal(),
                fixture.bindings(),
                tamperedSeal,
                GraphAttemptOutcome.FAILED));
  }

  @Test
  void acceptsAnExactlyBoundSuccessfulCandidateGraph() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.SUCCESS);

    GraphAttemptSnapshot snapshot =
        fixture.snapshot(
            fixture.events(),
            fixture.parentTerminal(),
            fixture.childTerminal(),
            fixture.bindings(),
            fixture.seal(),
            GraphAttemptOutcome.SUCCEEDED);

    assertEquals("VALID", snapshot.verdict());
    assertEquals(
        fixture.candidate().integrityHash(),
        snapshot.candidate().integrityHash());
  }

  @Test
  void rejectsAnUnrelatedArtifactDespiteValidLocalHashes() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.SUCCESS);
    ArtifactLineage unrelated =
        new ArtifactLineage(
            fixture.manifest().artifactId(),
            fixture.manifest().principalId(),
            fixture.manifest().captureId(),
            List.of(
                new ArtifactLineageEntry(
                    1,
                    "unrelated but internally valid",
                    ContentHashes.sha256(
                        "unrelated but internally valid"),
                    null,
                    null,
                    STARTED.plusMillis(15))));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.snapshotWithArtifact(
                fixture.events(),
                fixture.parentTerminal(),
                fixture.childTerminal(),
                fixture.bindings(),
                fixture.seal(),
                GraphAttemptOutcome.SUCCEEDED,
                unrelated));
  }

  @Test
  void acceptsAndSealsAnH1RejectedCandidateWithoutProductTruth() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION);

    GraphAttemptSnapshot snapshot =
        fixture.snapshot(
            fixture.events(),
            fixture.parentTerminal(),
            fixture.childTerminal(),
            fixture.bindings(),
            fixture.seal(),
            GraphAttemptOutcome.FAILED);

    assertEquals("VALID", snapshot.verdict());
    assertEquals(
        "INVALID_EVIDENCE_CLAIM",
        snapshot.childRun().result().failureReason());
    assertEquals(
        fixture.candidate().integrityHash(),
        snapshot.candidate().integrityHash());
    assertNull(snapshot.workerResult());
    assertNull(snapshot.artifact());
  }

  @Test
  void h1RejectedChildCannotDropItsCandidateAtSequence15() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION);

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.childTerminalSnapshot(null));
  }

  @Test
  void candidateMustBindTheExactStructuredFinalContent() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION);
    HarnessCandidateEnvelope original = fixture.candidate();
    HarnessCandidateEnvelope unrelated =
        HarnessCandidateEnvelope.create(
            original.attemptId(),
            original.executionSlotId(),
            original.repetition(),
            original.childRunId(),
            original.childTaskId(),
            original.sourceResponseHash(),
            original.traceRootHash(),
            original.outputSchema(),
            "另一份结构合法、但不是本次 structured final 的内容。",
            original.evidenceRefs(),
            original.obtainedEvidenceRefs(),
            original.requiredEvidenceRef(),
            original.requiredEvidenceAvailable());

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.childTerminalSnapshot(unrelated));
  }

  @Test
  void candidateSourceMustEqualTheSecondProviderResponse() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION);
    HarnessCandidateEnvelope original = fixture.candidate();
    HarnessCandidateEnvelope wrongSource =
        HarnessCandidateEnvelope.create(
            original.attemptId(),
            original.executionSlotId(),
            original.repetition(),
            original.childRunId(),
            original.childTaskId(),
            fixture.attributions().getFirst().responseHash(),
            original.traceRootHash(),
            original.outputSchema(),
            original.content(),
            original.evidenceRefs(),
            original.obtainedEvidenceRefs(),
            original.requiredEvidenceRef(),
            original.requiredEvidenceAvailable());

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.childTerminalSnapshot(wrongSource));
  }

  @Test
  void candidateAvailabilityMustMatchDurableToolEvidence() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION);
    HarnessCandidateEnvelope original = fixture.candidate();
    HarnessCandidateEnvelope falseAvailability =
        HarnessCandidateEnvelope.create(
            original.attemptId(),
            original.executionSlotId(),
            original.repetition(),
            original.childRunId(),
            original.childTaskId(),
            original.sourceResponseHash(),
            original.traceRootHash(),
            original.outputSchema(),
            original.content(),
            original.evidenceRefs(),
            original.obtainedEvidenceRefs(),
            original.requiredEvidenceRef(),
            false);

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.childTerminalSnapshot(falseAvailability));
  }

  @Test
  void preCandidateInvalidStructuredFinalMayStillBeSealed() {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.INVALID_STRUCTURED_FINAL);

    GraphAttemptSnapshot snapshot =
        fixture.snapshot(
            fixture.events(),
            fixture.parentTerminal(),
            fixture.childTerminal(),
            fixture.bindings(),
            fixture.seal(),
            GraphAttemptOutcome.FAILED);

    assertEquals("VALID", snapshot.verdict());
    assertNull(snapshot.candidate());
    assertEquals(
        "INVALID_STRUCTURED_FINAL",
        snapshot.childRun().result().failureReason());
  }

  static GraphAttemptSnapshot reportEligibleSnapshot(
      int repetition, boolean success) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            success
                ? TerminalVariant.SUCCESS
                : TerminalVariant.H1_REJECTION,
            repetition);
    return fixture.snapshot(
        fixture.events(),
        fixture.parentTerminal(),
        fixture.childTerminal(),
        fixture.bindings(),
        fixture.seal(),
        success
            ? GraphAttemptOutcome.SUCCEEDED
            : GraphAttemptOutcome.FAILED);
  }

  static GraphAttemptSnapshot preCandidateTerminalSnapshot(
      int repetition) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.INVALID_STRUCTURED_FINAL,
            repetition);
    return fixture.snapshot(
        fixture.events(),
        fixture.parentTerminal(),
        fixture.childTerminal(),
        fixture.bindings(),
        fixture.seal(),
        GraphAttemptOutcome.FAILED);
  }

  static GraphAttemptSnapshot childTerminalSnapshot(
      int repetition) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.SUCCESS,
            repetition);
    return fixture.childTerminalSnapshot(fixture.candidate());
  }

  static GraphAttemptSnapshot
      reportEligibleSnapshotWithChildExecutionFingerprintDrift(
          int repetition) {
    return reportEligibleSnapshotWithSelectionVariant(
        repetition, SelectionVariant.CHILD_EXECUTION_DRIFT);
  }

  static GraphAttemptSnapshot
      reportEligibleSnapshotWithSharedWorkerFingerprintDrift(
          int repetition) {
    return reportEligibleSnapshotWithSelectionVariant(
        repetition, SelectionVariant.SHARED_WORKER_DRIFT);
  }

  static GraphAttemptSnapshot
      reportEligibleSnapshotWithProviderNamespace(
          int repetition, boolean success, String providerNamespace) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            success
                ? TerminalVariant.SUCCESS
                : TerminalVariant.H1_REJECTION,
            repetition,
            SelectionVariant.EXACT,
            providerNamespace);
    return fixture.snapshot(
        fixture.events(),
        fixture.parentTerminal(),
        fixture.childTerminal(),
        fixture.bindings(),
        fixture.seal(),
        success
            ? GraphAttemptOutcome.SUCCEEDED
            : GraphAttemptOutcome.FAILED);
  }

  static GraphAttemptSnapshot
      reportEligibleSnapshotWithModelBoundParent(int repetition) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION,
            repetition,
            SelectionVariant.EXACT,
            "openai.responses",
            ParentTaskVariant.MODEL_BOUND);
    return fixture.snapshot(
        fixture.events(),
        fixture.parentTerminal(),
        fixture.childTerminal(),
        fixture.bindings(),
        fixture.seal(),
        GraphAttemptOutcome.FAILED);
  }

  private static GraphAttemptSnapshot
      reportEligibleSnapshotWithSelectionVariant(
          int repetition, SelectionVariant selectionVariant) {
    ProtocolFixture fixture =
        fixture(
            AttributionVariant.EXACT,
            ATTRIBUTED_COST,
            ATTRIBUTED_TOKENS,
            TerminalVariant.H1_REJECTION,
            repetition,
            selectionVariant);
    return fixture.snapshot(
        fixture.events(),
        fixture.parentTerminal(),
        fixture.childTerminal(),
        fixture.bindings(),
        fixture.seal(),
        GraphAttemptOutcome.FAILED);
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens) {
    return fixture(
        variant,
        runCost,
        runTokens,
        TerminalVariant.PRE_CANDIDATE_FAILURE);
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens,
      TerminalVariant terminalVariant) {
    return fixture(
        variant, runCost, runTokens, terminalVariant, 1);
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens,
      TerminalVariant terminalVariant,
      int repetition) {
    return fixture(
        variant,
        runCost,
        runTokens,
        terminalVariant,
        repetition,
        SelectionVariant.EXACT);
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens,
      TerminalVariant terminalVariant,
      int repetition,
      SelectionVariant selectionVariant) {
    return fixture(
        variant,
        runCost,
        runTokens,
        terminalVariant,
        repetition,
        selectionVariant,
        "openai.responses");
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens,
      TerminalVariant terminalVariant,
      int repetition,
      SelectionVariant selectionVariant,
      String providerNamespace) {
    return fixture(
        variant,
        runCost,
        runTokens,
        terminalVariant,
        repetition,
        selectionVariant,
        providerNamespace,
        ParentTaskVariant.NON_MODEL_BOUND);
  }

  private static ProtocolFixture fixture(
      AttributionVariant variant,
      BigDecimal runCost,
      long runTokens,
      TerminalVariant terminalVariant,
      int repetition,
      SelectionVariant selectionVariant,
      String providerNamespace,
      ParentTaskVariant parentTaskVariant) {
    PricingProfile pricing =
        new PricingProfile(
            "openai-terra-test-pricing-v1",
            providerNamespace,
            MODEL,
            2_000,
            200,
            12_000);
    HarnessExperiment experiment =
        new HarnessExperiment("pack010-terminal", repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            pricing,
            "openai-test-protocol-v1",
            IntegrityHashes.utf8ContentHash("conductor"),
            "environment://sha256:"
                + IntegrityHashes.utf8ContentHash("environment"),
            experiment,
            272_000,
            1_000,
            new BigDecimal("1.112000"));
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            "parent-task-r" + repetition,
            "principal",
            "draft a synthetic article",
            "capture://capture-1",
            DataClass.PUBLIC);
    TaskEnvelope childTask =
        worker.newChildTask(
            parentTask,
            new WorkerHandoffRequest(
                worker.workerName(),
                parentTask.intent(),
                parentTask.inputRefs()),
            new AgentWorkerRuntime.ExecutionWindow(
                parentTask.deadlineMs(),
                parentTask.budgetUsd(),
                CancellationSignal.never()),
            "child-task-r" + repetition);
    if (parentTaskVariant == ParentTaskVariant.MODEL_BOUND) {
      parentTask = modelBoundParentTask(parentTask, pricing, repetition);
    }
    AgentRun parentRunning =
        AgentRun.running(
            runIdForTask(parentTask.id()),
            "principal",
            parentTask,
            STARTED);
    AgentRun childRunning =
        AgentRun.running(
            runIdForTask(childTask.id()),
            "principal",
            childTask,
            STARTED);
    String sharedWorkerFingerprint =
        selectionVariant == SelectionVariant.SHARED_WORKER_DRIFT
            ? IntegrityHashes.utf8ContentHash(
                "drifted-worker-profile-" + repetition)
            : worker.fingerprint();
    String childExecutionFingerprint =
        selectionVariant == SelectionVariant.CHILD_EXECUTION_DRIFT
            ? IntegrityHashes.utf8ContentHash(
                "drifted-child-execution-profile-" + repetition)
            : worker.fingerprint();
    GraphRunSelection parentSelection =
        selection(
            GraphRunRole.PARENT,
            parentRunning,
            parentProfile.id(),
            parentProfile.fingerprint(),
            worker,
            sharedWorkerFingerprint);
    GraphRunSelection childSelection =
        selection(
            GraphRunRole.CHILD,
            childRunning,
            worker.id(),
            childExecutionFingerprint,
            worker,
            sharedWorkerFingerprint);
    GraphAttemptManifest manifest =
        GraphAttemptManifest.create(
            GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION,
            "principal",
            "pack010-r" + repetition,
            "pack010-case-r" + repetition,
            IntegrityHashes.utf8ContentHash("pack"),
            IntegrityHashes.utf8ContentHash("environment"),
            "capture-1",
            IntegrityHashes.utf8ContentHash("capture"),
            "artifact-" + repetition,
            STARTED,
            pricing.fingerprint(),
            IntegrityHashes.utf8ContentHash("prompt"),
            IntegrityHashes.utf8ContentHash("conductor"),
            worker.reservationUsd(),
            2,
            "SCRIPTED_FAKE",
            PROVIDER_ACTOR,
            experiment,
            parentSelection,
            childSelection);
    HarnessCandidateEnvelope candidate = null;
    WorkerResultEnvelope workerResult = null;
    ArtifactLineage artifact = null;
    AgentRun childTerminal;
    AgentRun parentTerminal;
    GraphAttemptOutcome terminalOutcome;
    if (terminalVariant == TerminalVariant.SUCCESS) {
      String content =
          "Prompt 不是咒语，而是在构造运行时状态。";
      String evidenceRef = "capture://" + manifest.captureId();
      String evidenceHash = manifest.captureRequestHash();
      String contentHash = IntegrityHashes.utf8ContentHash(content);
      AgentTraceEnvelope childTrace =
          successfulChildTrace(
              childTask.id(), evidenceRef, contentHash);
      candidate =
          HarnessCandidateEnvelope.create(
              manifest.attemptId(),
              manifest.executionSlotId(),
              manifest.experiment().repetition(),
              childRunning.runId(),
              childTask.id(),
              "b".repeat(64),
              childTrace.rootHash(),
              childTask.outputSchema(),
              content,
              List.of(evidenceRef),
              List.of(evidenceRef),
              evidenceRef,
              true);
      workerResult =
          WorkerResultEnvelope.create(
              childRunning.runId(),
              childTask.id(),
              childTask.outputSchema(),
              content,
              List.of(evidenceRef));
      childTerminal =
          successfulChild(
              childTask,
              worker,
              childTrace,
              workerResult,
              evidenceHash,
              runCost,
              runTokens);
      artifact =
          new ArtifactLineage(
              manifest.artifactId(),
              manifest.principalId(),
              manifest.captureId(),
              List.of(
                  new ArtifactLineageEntry(
                      1,
                      candidate.content(),
                      candidate.contentHash(),
                      null,
                      null,
                      STARTED.plusMillis(15))));
      parentTerminal =
          successfulParent(
              parentTask,
              parentProfile,
              worker,
              childTerminal,
              workerResult,
              artifact,
              evidenceHash,
              runCost,
              runTokens);
      terminalOutcome = GraphAttemptOutcome.SUCCEEDED;
    } else if (terminalVariant == TerminalVariant.H1_REJECTION) {
      String content =
          "Prompt 不是咒语，但这里声称了错误引用。";
      String evidenceRef = "capture://" + manifest.captureId();
      String evidenceHash = manifest.captureRequestHash();
      AgentTraceEnvelope childTrace =
          successfulChildTrace(
              childTask.id(),
              evidenceRef,
              IntegrityHashes.utf8ContentHash(content));
      candidate =
          HarnessCandidateEnvelope.create(
              manifest.attemptId(),
              manifest.executionSlotId(),
              manifest.experiment().repetition(),
              childRunning.runId(),
              childTask.id(),
              "b".repeat(64),
              childTrace.rootHash(),
              childTask.outputSchema(),
              content,
              List.of("capture://other"),
              List.of(evidenceRef),
              evidenceRef,
              true);
      childTerminal =
          rejectedCandidateChild(
              childTask,
              worker,
              childTrace,
              candidate,
              evidenceHash,
              runCost,
              runTokens);
      parentTerminal =
          failedParent(
              parentTask,
              childTerminal,
              parentProfile,
              worker,
              runCost,
              runTokens);
      terminalOutcome = GraphAttemptOutcome.FAILED;
    } else if (
        terminalVariant
            == TerminalVariant.INVALID_STRUCTURED_FINAL) {
      String evidenceRef =
          "capture://" + manifest.captureId();
      String evidenceHash = manifest.captureRequestHash();
      AgentTraceEnvelope childTrace =
          successfulChildTrace(
              childTask.id(),
              evidenceRef,
              IntegrityHashes.utf8ContentHash(" "));
      childTerminal =
          invalidStructuredFinalChild(
              childTask,
              worker,
              childTrace,
              evidenceRef,
              evidenceHash,
              runCost,
              runTokens);
      parentTerminal =
          failedParent(
              parentTask,
              childTerminal,
              parentProfile,
              worker,
              runCost,
              runTokens);
      terminalOutcome = GraphAttemptOutcome.FAILED;
    } else {
      childTerminal =
          failedChild(childTask, worker, runCost, runTokens);
      parentTerminal =
          failedParent(
              parentTask,
              childTerminal,
              parentProfile,
              worker,
              runCost,
              runTokens);
      terminalOutcome = GraphAttemptOutcome.FAILED;
    }
    GraphProviderAttribution first =
        attribution(
            1,
            variant == AttributionVariant.REQUEST_MISMATCH
                ? "9".repeat(64)
                : REQUEST_1,
            "a".repeat(64),
            variant == AttributionVariant.PRICING_MISMATCH
                ? GraphPricingSnapshot.create(
                    "wrong-openai-pricing-v1",
                    pricing.provider(),
                    pricing.modelRequested(),
                    pricing.uncachedInputNanoUsdPerToken(),
                    pricing.cachedInputNanoUsdPerToken(),
                    pricing.outputNanoUsdPerToken())
                : pricing.graphSnapshot(),
            8,
            7,
            new BigDecimal("0.000100"));
    GraphProviderAttribution second =
        attribution(
            2,
            REQUEST_2,
            "b".repeat(64),
            pricing.graphSnapshot(),
            9,
            6,
            new BigDecimal("0.000090"));
    List<GraphProviderAttribution> attributions =
        List.of(first, second);
    List<GraphAttemptEvent> events =
        prefix(manifest, parentRunning, childRunning);
    GraphAttemptCursor cursor =
        events.getLast().cursor(manifest);
    cursor =
        appendAttribution(
            events,
            cursor,
            childSelection,
            first,
            STARTED.plusMillis(11));
    cursor =
        appendIntent(
            events,
            cursor,
            childSelection,
            2,
            REQUEST_2,
            MODEL,
            STARTED.plusMillis(12));
    cursor =
        appendAttribution(
            events,
            cursor,
            childSelection,
            second,
            STARTED.plusMillis(13));
    GraphTerminalBinding childBinding =
        GraphTerminalBinding.child(childTerminal, workerResult);
    GraphAttemptEvent childEvent =
        GraphAttemptEvent.terminal(
            cursor,
            GraphAttemptEventType.CHILD_TERMINAL,
            GraphAttemptPhase.CHILD_TERMINAL,
            childSelection,
            childBinding,
            STARTED.plusMillis(14));
    events.add(childEvent);
    cursor = childEvent.advance(cursor);
    GraphTerminalBinding parentBinding =
        GraphTerminalBinding.parent(parentTerminal, artifact);
    GraphAttemptEvent parentEvent =
        GraphAttemptEvent.terminal(
            cursor,
            GraphAttemptEventType.PARENT_TERMINAL,
            GraphAttemptPhase.PARENT_TERMINAL,
            parentSelection,
            parentBinding,
            STARTED.plusMillis(15));
    events.add(parentEvent);
    cursor = parentEvent.advance(cursor);
    Instant sealedAt = STARTED.plusMillis(17);
    List<String> attributionHashes =
        attributions.stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList();
    String sealHash =
        GraphTerminalSeal.computeHash(
            manifest.attemptId(),
            manifest.manifestHash(),
            17,
            cursor.headHash(),
            terminalOutcome,
            GraphBillingStatus.ATTRIBUTED,
            attributionHashes,
            candidate == null ? null : candidate.candidateRef(),
            candidate == null ? null : candidate.integrityHash(),
            childBinding.terminalHash(),
            parentBinding.terminalHash(),
            sealedAt);
    GraphAttemptEvent sealedEvent =
        GraphAttemptEvent.sealed(cursor, sealHash, sealedAt);
    events.add(sealedEvent);
    GraphTerminalSeal seal =
        new GraphTerminalSeal(
            manifest.attemptId(),
            manifest.manifestHash(),
            17,
            cursor.headHash(),
            sealedEvent.currentHeadHash(),
            terminalOutcome,
            GraphBillingStatus.ATTRIBUTED,
            attributionHashes,
            candidate == null ? null : candidate.candidateRef(),
            candidate == null ? null : candidate.integrityHash(),
            childBinding.terminalHash(),
            parentBinding.terminalHash(),
            sealHash,
            sealedAt);
    return new ProtocolFixture(
        manifest,
        parentRunning,
        childRunning,
        parentTerminal,
        childTerminal,
        attributions,
        List.copyOf(events),
        parentBinding,
        childBinding,
        seal,
        candidate,
        workerResult,
        artifact);
  }

  private static GraphRunSelection selection(
      GraphRunRole role,
      AgentRun run,
      String profileId,
      String profileFingerprint,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      String workerProfileFingerprint) {
    return new GraphRunSelection(
        role,
        run.runId(),
        run.task().id(),
        IntegrityHashes.taskHash(run.task()),
        profileId,
        profileFingerprint,
        worker.registryVersion(),
        worker.id(),
        workerProfileFingerprint);
  }

  private static GraphProviderAttribution attribution(
      int ordinal,
      String requestHash,
      String responseHash,
      GraphPricingSnapshot pricing,
      long inputTokens,
      long outputTokens,
      BigDecimal cost) {
    return GraphProviderAttribution.create(
        ordinal,
        requestHash,
        responseHash,
        PROVIDER_ACTOR,
        MODEL,
        MODEL,
        pricing,
        inputTokens,
        0,
        outputTokens,
        0,
        inputTokens + outputTokens,
        cost);
  }

  private static TaskEnvelope modelBoundParentTask(
      TaskEnvelope source, PricingProfile pricing, int repetition) {
    return new TaskEnvelope(
        "1.1",
        source.id(),
        source.parentId(),
        source.principalRef(),
        source.delegationChain(),
        source.kind(),
        source.intent(),
        source.inputRefs(),
        source.evidenceRefs(),
        source.modalities(),
        source.dataClass(),
        source.risk(),
        source.latencyClass(),
        source.requiredTools(),
        source.outputSchema(),
        source.acceptanceChecks(),
        source.allowParallel(),
        source.maxModelSteps(),
        source.maxToolCalls(),
        source.deadlineMs(),
        source.budgetUsd(),
        pricing.provider(),
        pricing.modelRequested(),
        pricing.id(),
        "parent-model-bound-r" + repetition,
        source.policyVersion(),
        source.stateVersion(),
        source.contextPolicyVersion(),
        source.toolRegistryVersion(),
        source.environmentSnapshotRef(),
        source.capabilityRefs(),
        source.unresolvedDecisions(),
        source.returnControlWhen());
  }

  private static Map<String, String> parentComponentVersions(
      TaskEnvelope task,
      AgentExecutionProfile profile,
      ModelBoundReadOnlyWorkerExecutionProfile worker) {
    Map<String, String> exact =
        new LinkedHashMap<>(profile.componentVersions(worker));
    if ("1.1".equals(task.schemaVersion())) {
      exact.put("model-adapter", "forbidden-parent-model-route");
      exact.put("execution-profile", profile.id());
      exact.put(
          "execution-profile-fingerprint", profile.fingerprint());
      exact.put(
          "pricing-profile-fingerprint",
          worker.pricing().fingerprint());
    }
    return Map.copyOf(exact);
  }

  private static List<GraphAttemptEvent> prefix(
      GraphAttemptManifest manifest,
      AgentRun parent,
      AgentRun child) {
    List<GraphAttemptEvent> events = new ArrayList<>();
    GraphAttemptEvent claimed =
        GraphAttemptEvent.claimed(manifest, STARTED);
    events.add(claimed);
    GraphAttemptCursor cursor = claimed.cursor(manifest);
    cursor =
        append(
            events,
            cursor,
            GraphAttemptEventType.OPERATOR_APPROVED,
            GraphAttemptPhase.OPERATOR_APPROVED,
            null,
            GraphOperatorApproval.ownerTty(manifest),
            null,
            STARTED.plusMillis(1));
    cursor =
        appendRun(
            events,
            cursor,
            GraphAttemptEventType.PARENT_AUTHORIZED,
            GraphAttemptPhase.PARENT_AUTHORIZED,
            GraphRunRole.PARENT,
            parent,
            STARTED.plusMillis(2));
    cursor =
        appendRun(
            events,
            cursor,
            GraphAttemptEventType.PARENT_STARTED,
            GraphAttemptPhase.PARENT_RUNNING,
            GraphRunRole.PARENT,
            parent,
            STARTED.plusMillis(3));
    cursor =
        appendRun(
            events,
            cursor,
            GraphAttemptEventType.CHILD_AUTHORIZED,
            GraphAttemptPhase.CHILD_AUTHORIZED,
            GraphRunRole.CHILD,
            child,
            STARTED.plusMillis(4));
    cursor =
        appendRun(
            events,
            cursor,
            GraphAttemptEventType.CHILD_STARTED,
            GraphAttemptPhase.CHILD_RUNNING,
            GraphRunRole.CHILD,
            child,
            STARTED.plusMillis(5));
    GraphRunSelection selection = manifest.childSelection();
    cursor =
        appendSelection(
            events,
            cursor,
            GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
            GraphAttemptPhase.EGRESS_CONSUMED,
            selection,
            STARTED.plusMillis(6));
    cursor =
        appendSelection(
            events,
            cursor,
            GraphAttemptEventType.CREDENTIAL_READ_STARTED,
            GraphAttemptPhase.CREDENTIAL_READING,
            selection,
            STARTED.plusMillis(7));
    cursor =
        appendSelection(
            events,
            cursor,
            GraphAttemptEventType.CLIENT_CREATED,
            GraphAttemptPhase.CLIENT_READY,
            selection,
            STARTED.plusMillis(8));
    cursor =
        appendSelection(
            events,
            cursor,
            GraphAttemptEventType.MODEL_CREATED,
            GraphAttemptPhase.MODEL_READY,
            selection,
            STARTED.plusMillis(9));
    appendIntent(
        events,
        cursor,
        selection,
        1,
        REQUEST_1,
        MODEL,
        STARTED.plusMillis(10));
    return events;
  }

  private static GraphAttemptCursor appendIntent(
      List<GraphAttemptEvent> events,
      GraphAttemptCursor cursor,
      GraphRunSelection selection,
      int ordinal,
      String requestHash,
      String model,
      Instant at) {
    return append(
        events,
        cursor,
        GraphAttemptEventType.PROVIDER_INTENT,
        GraphAttemptPhase.PROVIDER_PENDING,
        selection,
        null,
        new GraphProviderIntent(ordinal, requestHash, model),
        at);
  }

  private static GraphAttemptCursor appendAttribution(
      List<GraphAttemptEvent> events,
      GraphAttemptCursor cursor,
      GraphRunSelection selection,
      GraphProviderAttribution attribution,
      Instant at) {
    GraphAttemptEvent event =
        GraphAttemptEvent.providerAttributed(
            cursor, selection, attribution, at);
    events.add(event);
    return event.advance(cursor);
  }

  private static GraphAttemptCursor appendRun(
      List<GraphAttemptEvent> events,
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      GraphRunRole role,
      AgentRun run,
      Instant at) {
    return append(
        events,
        cursor,
        type,
        phase,
        new GraphRunSelection(
            role,
            run.runId(),
            run.task().id(),
            IntegrityHashes.taskHash(run.task()),
            "placeholder-profile",
            "0".repeat(64),
            "placeholder-registry",
            "placeholder-worker",
            "1".repeat(64)),
        null,
        null,
        at);
  }

  private static GraphAttemptCursor appendSelection(
      List<GraphAttemptEvent> events,
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      GraphRunSelection selection,
      Instant at) {
    return append(
        events,
        cursor,
        type,
        phase,
        selection,
        null,
        null,
        at);
  }

  private static GraphAttemptCursor append(
      List<GraphAttemptEvent> events,
      GraphAttemptCursor cursor,
      GraphAttemptEventType type,
      GraphAttemptPhase phase,
      GraphRunSelection selection,
      GraphOperatorApproval approval,
      GraphProviderIntent intent,
      Instant at) {
    GraphAttemptEvent event =
        GraphAttemptEvent.next(
            cursor,
            type,
            at,
            phase,
            selection == null ? null : selection.role(),
            selection == null ? null : selection.runId(),
            selection == null ? null : selection.taskId(),
            approval,
            intent);
    events.add(event);
    return event.advance(cursor);
  }

  private static AgentRun failedChild(
      TaskEnvelope task,
      ModelBoundReadOnlyWorkerExecutionProfile profile,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    AgentTraceEnvelope trace =
        failedModelTrace(runId, task.id());
    ResultEnvelope result =
        failedResult(
            runId,
            task.id(),
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            "MODEL_STEP_FAILED");
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            MODEL,
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            result.failureReason(),
            result.status(),
            cost,
            tokens,
            1);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(14));
  }

  private static AgentRun failedParent(
      TaskEnvelope task,
      AgentRun child,
      AgentExecutionProfile profile,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    String childRef = "agent-run://" + child.runId();
    AgentTraceEnvelope trace =
        rejectedChildTrace(runId, task.id(), childRef);
    ResultEnvelope result =
        failedResult(
            runId,
            task.id(),
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            "HANDOFF_CHILD_FAILED");
    List<ResourceBinding> bindings =
        List.of(
            new ResourceBinding(
                ResourceRole.HANDOFF,
                0,
                childRef,
                child.bundle().integrityHash()));
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            MODEL,
            profile.harnessVersion(),
            parentComponentVersions(task, profile, worker),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            bindings,
            null,
            result.failureReason(),
            result.status(),
            cost,
            tokens,
            2);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(15));
  }

  private static AgentTraceEnvelope successfulChildTrace(
      String taskId, String evidenceRef, String contentHash) {
    return trace(
        runIdForTask(taskId),
        taskId,
        List.of(
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://" + taskId),
            new TraceSpec(
                TraceEventType.TOOL_REQUEST,
                "capture.read",
                "REQUESTED",
                evidenceRef),
            new TraceSpec(
                TraceEventType.TOOL_RESULT,
                "capture.read",
                "SUCCEEDED",
                evidenceRef),
            new TraceSpec(
                TraceEventType.MODEL_STEP,
                null,
                "COMPLETED",
                "task://" + taskId),
            new TraceSpec(
                TraceEventType.STRUCTURED_FINAL,
                null,
                "PROPOSED",
                "proposal://sha256:" + contentHash)));
  }

  private static AgentRun successfulChild(
      TaskEnvelope task,
      ModelBoundReadOnlyWorkerExecutionProfile profile,
      AgentTraceEnvelope trace,
      WorkerResultEnvelope workerResult,
      String evidenceHash,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    ResultEnvelope result =
        successfulResult(
            runId,
            task.id(),
            List.of(),
            workerResult.evidenceRefs(),
            MODEL,
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            14);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            result.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    workerResult.evidenceRefs().getFirst(),
                    evidenceHash),
                new ResourceBinding(
                    ResourceRole.WORKER_RESULT,
                    0,
                    workerResult.workerResultRef(),
                    workerResult.integrityHash())),
            null,
            null,
            RunStatus.SUCCEEDED,
            cost,
            tokens,
            14);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.SUCCEEDED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(14));
  }

  private static AgentRun rejectedCandidateChild(
      TaskEnvelope task,
      ModelBoundReadOnlyWorkerExecutionProfile profile,
      AgentTraceEnvelope trace,
      HarnessCandidateEnvelope candidate,
      String evidenceHash,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    String failure = "INVALID_EVIDENCE_CLAIM";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            candidate.obtainedEvidenceRefs(),
            List.of(),
            List.of(),
            List.of(),
            MODEL,
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            14,
            "/api/v1/agent-runs/" + runId + "/trace",
            failure);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            result.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    candidate.obtainedEvidenceRefs().getFirst(),
                    evidenceHash)),
            null,
            failure,
            RunStatus.FAILED,
            cost,
            tokens,
            14);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(14));
  }

  private static AgentRun invalidStructuredFinalChild(
      TaskEnvelope task,
      ModelBoundReadOnlyWorkerExecutionProfile profile,
      AgentTraceEnvelope trace,
      String evidenceRef,
      String evidenceHash,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    String failure = "INVALID_STRUCTURED_FINAL";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(evidenceRef),
            List.of(),
            List.of(),
            List.of(),
            MODEL,
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            14,
            "/api/v1/agent-runs/" + runId + "/trace",
            failure);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            result.resolvedModel(),
            profile.harnessVersion(),
            profile.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    evidenceRef,
                    evidenceHash)),
            null,
            failure,
            RunStatus.FAILED,
            cost,
            tokens,
            14);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.FAILED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(14));
  }

  private static AgentRun successfulParent(
      TaskEnvelope task,
      AgentExecutionProfile profile,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      AgentRun child,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact,
      String evidenceHash,
      BigDecimal cost,
      long tokens) {
    String runId = runIdForTask(task.id());
    String childRef = "agent-run://" + child.runId();
    String artifactRef =
        "artifact-version://"
            + artifact.artifactId()
            + "/"
            + artifact.current().version();
    AgentTraceEnvelope trace =
        trace(
            runId,
            task.id(),
            List.of(
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + task.id()),
                new TraceSpec(
                    TraceEventType.HANDOFF_REQUEST,
                    null,
                    "REQUESTED",
                    childRef),
                new TraceSpec(
                    TraceEventType.HANDOFF_RESULT,
                    null,
                    "SUCCEEDED",
                    childRef),
                new TraceSpec(
                    TraceEventType.MODEL_STEP,
                    null,
                    "COMPLETED",
                    "task://" + task.id()),
                new TraceSpec(
                    TraceEventType.STRUCTURED_FINAL,
                    null,
                    "PROPOSED",
                    "proposal://sha256:"
                        + workerResult.contentHash()),
                new TraceSpec(
                    TraceEventType.ARTIFACT_COMMITTED,
                    null,
                    "SUCCEEDED",
                    artifactRef)));
    ResultEnvelope result =
        successfulResult(
            runId,
            task.id(),
            List.of(artifactRef),
            workerResult.evidenceRefs(),
            "fake-pack010-conductor-v1",
            profile.agentVersion(),
            profile.verifierVersion(),
            cost,
            tokens,
            15);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            profile.experiment(),
            result.resolvedModel(),
            profile.harnessVersion(),
            parentComponentVersions(task, profile, worker),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            result.traceRef(),
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    workerResult.evidenceRefs().getFirst(),
                    evidenceHash),
                new ResourceBinding(
                    ResourceRole.ARTIFACT,
                    0,
                    artifactRef,
                    artifact.current().contentHash()),
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    child.bundle().integrityHash())),
            null,
            null,
            RunStatus.SUCCEEDED,
            cost,
            tokens,
            15);
    return new AgentRun(
        runId,
        "principal",
        task,
        AgentRunLifecycle.SUCCEEDED,
        result,
        trace,
        bundle,
        STARTED,
        STARTED.plusMillis(15));
  }

  private static ResultEnvelope successfulResult(
      String runId,
      String taskId,
      List<String> artifactRefs,
      List<String> evidenceRefs,
      String resolvedModel,
      String agentVersion,
      String verifierVersion,
      BigDecimal cost,
      long tokens,
      long latencyMs) {
    return new ResultEnvelope(
        "1.0",
        runId,
        taskId,
        RunStatus.SUCCEEDED,
        artifactRefs,
        evidenceRefs,
        List.of(),
        List.of(),
        List.of(),
        resolvedModel,
        agentVersion,
        verifierVersion,
        cost,
        tokens,
        latencyMs,
        "/api/v1/agent-runs/" + runId + "/trace",
        null);
  }

  private static AgentTraceEnvelope trace(
      String runId, String taskId, List<TraceSpec> specs) {
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    for (int index = 0; index < specs.size(); index++) {
      TraceSpec spec = specs.get(index);
      AgentTraceEntry event =
          AgentTraceEntry.create(
              index + 1,
              spec.type(),
              spec.toolName(),
              spec.status(),
              spec.reference(),
              root);
      events.add(event);
      root =
          IntegrityHashes.nextTraceRoot(root, event.eventHash());
    }
    return AgentTraceEnvelope.create(
        "1.0", runId, taskId, events);
  }

  private static ResultEnvelope failedResult(
      String runId,
      String taskId,
      String agentVersion,
      String verifierVersion,
      BigDecimal cost,
      long tokens,
      String failure) {
    return new ResultEnvelope(
        "1.0",
        runId,
        taskId,
        RunStatus.FAILED,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        MODEL,
        agentVersion,
        verifierVersion,
        cost,
        tokens,
        runId.startsWith("child-run-") ? 1 : 2,
        "/api/v1/agent-runs/" + runId + "/trace",
        failure);
  }

  private static String runIdForTask(String taskId) {
    return taskId.replace("-task-", "-run-");
  }

  private static AgentTraceEnvelope failedModelTrace(
      String runId, String taskId) {
    String root = IntegrityHashes.emptyTraceRoot();
    AgentTraceEntry failed =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "FAILED",
            "task://" + taskId,
            root);
    return AgentTraceEnvelope.create(
        "1.0", runId, taskId, List.of(failed));
  }

  private static AgentTraceEnvelope rejectedChildTrace(
      String runId, String taskId, String childRef) {
    List<AgentTraceEntry> entries = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    AgentTraceEntry model =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + taskId,
            root);
    entries.add(model);
    root =
        IntegrityHashes.nextTraceRoot(
            root, model.eventHash());
    AgentTraceEntry request =
        AgentTraceEntry.create(
            2,
            TraceEventType.HANDOFF_REQUEST,
            null,
            "REQUESTED",
            childRef,
            root);
    entries.add(request);
    root =
        IntegrityHashes.nextTraceRoot(
            root, request.eventHash());
    entries.add(
        AgentTraceEntry.create(
            3,
            TraceEventType.HANDOFF_REJECTED,
            null,
            "CHILD_FAILED",
            childRef,
            root));
    return AgentTraceEnvelope.create(
        "1.0", runId, taskId, entries);
  }

  private enum AttributionVariant {
    EXACT,
    REQUEST_MISMATCH,
    PRICING_MISMATCH
  }

  private enum TerminalVariant {
    PRE_CANDIDATE_FAILURE,
    INVALID_STRUCTURED_FINAL,
    H1_REJECTION,
    SUCCESS
  }

  private enum SelectionVariant {
    EXACT,
    CHILD_EXECUTION_DRIFT,
    SHARED_WORKER_DRIFT
  }

  private enum ParentTaskVariant {
    NON_MODEL_BOUND,
    MODEL_BOUND
  }

  private record TraceSpec(
      TraceEventType type,
      String toolName,
      String status,
      String reference) {}

  private record ProtocolFixture(
      GraphAttemptManifest manifest,
      AgentRun parentRunning,
      AgentRun childRunning,
      AgentRun parentTerminal,
      AgentRun childTerminal,
      List<GraphProviderAttribution> attributions,
      List<GraphAttemptEvent> events,
      GraphTerminalBinding parentBinding,
      GraphTerminalBinding childBinding,
      GraphTerminalSeal seal,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      ArtifactLineage artifact) {

    private List<GraphTerminalBinding> bindings() {
      return List.of(childBinding, parentBinding);
    }

    private GraphAttemptSnapshot snapshot(
        List<GraphAttemptEvent> selectedEvents,
        AgentRun selectedParent,
        AgentRun selectedChild,
        List<GraphTerminalBinding> selectedBindings,
        GraphTerminalSeal selectedSeal,
        GraphAttemptOutcome outcome) {
      return snapshotWithArtifact(
          selectedEvents,
          selectedParent,
          selectedChild,
          selectedBindings,
          selectedSeal,
          outcome,
          artifact);
    }

    private GraphAttemptSnapshot snapshotWithArtifact(
        List<GraphAttemptEvent> selectedEvents,
        AgentRun selectedParent,
        AgentRun selectedChild,
        List<GraphTerminalBinding> selectedBindings,
        GraphTerminalSeal selectedSeal,
        GraphAttemptOutcome outcome,
        ArtifactLineage selectedArtifact) {
      GraphAttemptCursor cursor =
          selectedEvents.getLast().cursor(manifest);
      return new GraphAttemptSnapshot(
          manifest,
          cursor,
          selectedEvents,
          selectedParent,
          selectedChild,
          selectedSeal != null,
          outcome,
          GraphAttemptSnapshot.deriveBilling(
              selectedEvents, attributions),
          attributions.stream()
              .limit(
                  selectedEvents.stream()
                      .filter(
                          event ->
                              event.type()
                                  == GraphAttemptEventType
                                      .PROVIDER_ATTRIBUTED)
                      .count())
              .toList(),
          selectedBindings,
          selectedSeal,
          candidate,
          workerResult,
          selectedArtifact);
    }

    private GraphAttemptSnapshot childTerminalSnapshot(
        HarnessCandidateEnvelope selectedCandidate) {
      List<GraphAttemptEvent> childEvents =
          events.subList(0, 15);
      return new GraphAttemptSnapshot(
          manifest,
          childEvents.getLast().cursor(manifest),
          childEvents,
          parentRunning,
          childTerminal,
          false,
          GraphAttemptOutcome.INCOMPLETE,
          GraphAttemptSnapshot.deriveBilling(
              childEvents, attributions),
          attributions,
          List.of(childBinding),
          null,
          selectedCandidate,
          workerResult,
          null);
    }
  }
}
