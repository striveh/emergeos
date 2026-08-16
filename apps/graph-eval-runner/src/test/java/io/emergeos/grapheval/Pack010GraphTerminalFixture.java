package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.AgentTraceEntry;
import io.emergeos.contracts.AgentTraceEnvelope;
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
import io.emergeos.core.application.HarnessCandidateComposer;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CaptureStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Deterministic PUBLIC fixture for Pack010 terminal process evidence. */
final class Pack010GraphTerminalFixture {

  static final String CASE_ID =
      "pack010-terminal-process-r1";
  static final String EXECUTION_SLOT_ID =
      "pack010-terminal-process-r1";
  static final String PACK_RAW_SHA256 =
      IntegrityHashes.utf8ContentHash(
          "pack010-terminal-process-pack-v1");
  static final Instant STARTED_AT =
      Pack009GraphEvalCatalog.STARTED_AT;

  private Pack010GraphTerminalFixture() {}

  static GraphAttemptManifest manifest() {
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        workerProfile();
    return GraphAttemptManifest.create(
        GraphAttemptSnapshot.TERMINAL_PROTOCOL_VERSION,
        Pack009GraphEvalCatalog.PRINCIPAL_ID,
        EXECUTION_SLOT_ID,
        CASE_ID,
        PACK_RAW_SHA256,
        Pack009GraphEvalCatalog.ENVIRONMENT_RAW_SHA256,
        Pack009GraphEvalCatalog.CAPTURE_ID,
        Pack009GraphEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
        Pack009GraphEvalCatalog.ARTIFACT_ID,
        STARTED_AT,
        Pack009GraphEvalCatalog
            .EXPECTED_PRICING_FINGERPRINT,
        Pack009GraphEvalCatalog
            .EXPECTED_PROMPT_SURFACE_FINGERPRINT,
        Pack009GraphEvalCatalog
            .EXPECTED_CONDUCTOR_SURFACE_FINGERPRINT,
        worker.reservationUsd(),
        Pack009GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS,
        "SCRIPTED_FAKE",
        "OPENAI_RESPONSES",
        worker.experiment(),
        Pack009GraphEvalCatalog.parentSelection(),
        Pack009GraphEvalCatalog.childSelection());
  }

  static ModelBoundReadOnlyWorkerExecutionProfile workerProfile() {
    return Pack009GraphEvalCatalog.workerProfile();
  }

  static AgentRun parentRunning() {
    return Pack009GraphEvalCatalog.parentRun();
  }

  static AgentRun childRunning() {
    return Pack009GraphEvalCatalog.childRun();
  }

  static void ensureCapture(DataSource dataSource) {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    PostgresCaptureStore captures =
        new PostgresCaptureStore(dataSource, transactions);
    CaptureStore.SaveResult saved =
        captures.saveOrFindByNonce(
            Pack009GraphEvalCatalog.capture());
    if (!saved.capture().equals(
        Pack009GraphEvalCatalog.capture())) {
      throw new IllegalStateException(
          "Pack010 Capture truth drifted");
    }
  }

  static GraphAttemptSnapshot completeCatalogRepetition(
      DataSource dataSource,
      PostgresGraphAttemptStore store,
      int repetition) {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    PostgresCaptureStore captures =
        new PostgresCaptureStore(dataSource, transactions);
    CaptureStore.SaveResult saved =
        captures.saveOrFindByNonce(
            Pack010GraphEvalCatalog.capture());
    if (!saved.capture().equals(Pack010GraphEvalCatalog.capture())) {
      throw new IllegalStateException(
          "Pack010 report Capture truth drifted");
    }

    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack010GraphEvalCatalog.workerProfile(repetition);
    AgentExecutionProfile parentProfile =
        Pack010GraphEvalCatalog.parentProfile(repetition);
    AgentRun parent = Pack010GraphEvalCatalog.parentRun(repetition);
    AgentRun child = Pack010GraphEvalCatalog.childRun(repetition);
    Instant startedAt = manifest.startedAt();

    GraphAttemptCursor cursor =
        ((io.emergeos.core.port.GraphAttemptStore.CreateResult.Created)
                store.create(manifest, startedAt))
            .cursor();
    cursor =
        store.approve(
            manifest,
            cursor,
            GraphOperatorApproval.ownerTty(manifest),
            startedAt.plusMillis(1));
    cursor =
        store.authorizeParent(
            manifest, cursor, parent, startedAt.plusMillis(2));
    cursor =
        store.startParent(
            manifest, cursor, parent, startedAt.plusMillis(3));
    cursor =
        store.authorizeChild(
            manifest, cursor, child, startedAt.plusMillis(4));
    cursor =
        store.startChild(
            manifest, cursor, child, startedAt.plusMillis(5));
    cursor =
        store.consumeChildEgress(
            manifest, cursor, startedAt.plusMillis(6));
    cursor =
        store.credentialReadStarted(
            manifest, cursor, startedAt.plusMillis(7));
    cursor =
        store.clientCreated(
            manifest, cursor, startedAt.plusMillis(8));
    cursor =
        store.modelCreated(
            manifest, cursor, startedAt.plusMillis(9));
    GraphProviderIntent first = intent(manifest, worker, 1);
    cursor =
        store.providerIntent(
            manifest, cursor, first, startedAt.plusMillis(10));
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            attribution(manifest, worker, first),
            startedAt.plusMillis(11));
    GraphProviderIntent second = intent(manifest, worker, 2);
    cursor =
        store.providerIntent(
            manifest, cursor, second, startedAt.plusMillis(12));
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            attribution(manifest, worker, second),
            startedAt.plusMillis(13));

    GraphAttemptSnapshot sequence14 = verified(store, manifest);
    SuccessfulChildTruth childTruth =
        successfulChild(
            sequence14, manifest, worker, startedAt);
    cursor =
        store.completeChild(
            manifest,
            cursor,
            AgentRunContext.fromRunning(parent),
            childTruth.terminal(),
            childTruth.candidate(),
            childTruth.workerResult(),
            startedAt.plusMillis(14));

    GraphAttemptSnapshot sequence15 = verified(store, manifest);
    ParentTerminalTruth parentTruth =
        successfulParent(
            sequence15,
            manifest,
            worker,
            parentProfile,
            startedAt);
    store.completeParentAndSeal(
        manifest,
        cursor,
        parentTruth.terminal(),
        parentTruth.artifact(),
        startedAt.plusMillis(15));
    GraphAttemptSnapshot terminal = verified(store, manifest);
    requireCheckpoint(terminal, Checkpoint.SEQ17);
    return terminal;
  }

  static GraphAttemptSnapshot prepareTxB(
      DataSource dataSource,
      PostgresGraphAttemptStore store) {
    GraphAttemptSnapshot sequence13 = prepareTxA(dataSource, store);
    completeTxA(store, sequence13);
    GraphAttemptSnapshot snapshot = verified(store);
    requireCheckpoint(snapshot, Checkpoint.SEQ14);
    return snapshot;
  }

  static GraphAttemptSnapshot prepareTxBPrefixOnly(
      PostgresGraphAttemptStore store) {
    GraphAttemptSnapshot sequence13 = prepareTxAPrefixOnly(store);
    completeTxA(store, sequence13);
    GraphAttemptSnapshot snapshot = verified(store);
    requireCheckpoint(snapshot, Checkpoint.SEQ14);
    return snapshot;
  }

  static GraphAttemptSnapshot prepareCatalogTxAPrefixOnly(
      DataSource dataSource,
      PostgresGraphAttemptStore store,
      int repetition) {
    GraphAttemptSnapshot sequence7 =
        prepareCatalogEgressPrefixOnly(
            dataSource, store, repetition);
    return advanceCatalogEgressToTxAPrefixOnly(
        store, sequence7, repetition);
  }

  static GraphAttemptSnapshot prepareCatalogEgressPrefixOnly(
      DataSource dataSource,
      PostgresGraphAttemptStore store,
      int repetition) {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    PostgresCaptureStore captures =
        new PostgresCaptureStore(dataSource, transactions);
    CaptureStore.SaveResult saved =
        captures.saveOrFindByNonce(Pack010GraphEvalCatalog.capture());
    if (!saved.capture().equals(Pack010GraphEvalCatalog.capture())) {
      throw new IllegalStateException(
          "Pack010 catalog Capture truth drifted");
    }
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    AgentRun parent = Pack010GraphEvalCatalog.parentRun(repetition);
    AgentRun child = Pack010GraphEvalCatalog.childRun(repetition);
    Instant startedAt = manifest.startedAt();
    GraphAttemptCursor cursor =
        ((io.emergeos.core.port.GraphAttemptStore.CreateResult.Created)
                store.create(manifest, startedAt))
            .cursor();
    cursor =
        store.approve(
            manifest,
            cursor,
            GraphOperatorApproval.ownerTty(manifest),
            startedAt.plusMillis(1));
    cursor =
        store.authorizeParent(
            manifest, cursor, parent, startedAt.plusMillis(2));
    cursor =
        store.startParent(
            manifest, cursor, parent, startedAt.plusMillis(3));
    cursor =
        store.authorizeChild(
            manifest, cursor, child, startedAt.plusMillis(4));
    cursor =
        store.startChild(
            manifest, cursor, child, startedAt.plusMillis(5));
    cursor =
        store.consumeChildEgress(
            manifest, cursor, startedAt.plusMillis(6));
    GraphAttemptSnapshot snapshot = verified(store, manifest);
    if (snapshot.cursor().lastSequence() != 7
        || snapshot.cursor().phase()
            != GraphAttemptPhase.EGRESS_CONSUMED
        || !snapshot.cursor().equals(cursor)) {
      throw new IllegalStateException(
          "Pack010 catalog sequence-7 egress drifted");
    }
    return snapshot;
  }

  static GraphAttemptSnapshot advanceCatalogEgressToTxAPrefixOnly(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence7,
      int repetition) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack010GraphEvalCatalog.workerProfile(repetition);
    Instant startedAt = manifest.startedAt();
    if (sequence7.cursor().lastSequence() != 7
        || sequence7.cursor().phase()
            != GraphAttemptPhase.EGRESS_CONSUMED) {
      throw new IllegalArgumentException(
          "Pack010 catalog provider prefix requires sequence 7");
    }
    GraphAttemptCursor cursor = sequence7.cursor();
    cursor =
        store.credentialReadStarted(
            manifest, cursor, startedAt.plusMillis(7));
    cursor =
        store.clientCreated(
            manifest, cursor, startedAt.plusMillis(8));
    cursor =
        store.modelCreated(
            manifest, cursor, startedAt.plusMillis(9));
    GraphProviderIntent first = intent(manifest, worker, 1);
    cursor =
        store.providerIntent(
            manifest, cursor, first, startedAt.plusMillis(10));
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            attribution(manifest, worker, first),
            startedAt.plusMillis(11));
    GraphProviderIntent second = intent(manifest, worker, 2);
    cursor =
        store.providerIntent(
            manifest, cursor, second, startedAt.plusMillis(12));
    GraphAttemptSnapshot snapshot = verified(store, manifest);
    if (snapshot.cursor().lastSequence() != 13
        || snapshot.cursor().phase()
            != GraphAttemptPhase.PROVIDER_PENDING
        || !snapshot.cursor().equals(cursor)) {
      throw new IllegalStateException(
          "Pack010 catalog sequence-13 prefix drifted");
    }
    return snapshot;
  }

  static GraphProviderIntent catalogIntent(
      int repetition, int ordinal) {
    return intent(
        Pack010GraphEvalCatalog.manifest(repetition),
        Pack010GraphEvalCatalog.workerProfile(repetition),
        ordinal);
  }

  static GraphProviderAttribution catalogAttribution(
      int repetition, int ordinal) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    return attribution(
        manifest,
        Pack010GraphEvalCatalog.workerProfile(repetition),
        intent(manifest, Pack010GraphEvalCatalog.workerProfile(repetition), ordinal));
  }

  static GraphAttemptCursor completeCatalogTxA(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence13,
      int repetition) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack010GraphEvalCatalog.workerProfile(repetition);
    if (sequence13.cursor().lastSequence() != 13
        || sequence13.cursor().phase()
            != GraphAttemptPhase.PROVIDER_PENDING) {
      throw new IllegalArgumentException(
          "Pack010 catalog TX-A requires sequence 13");
    }
    GraphProviderIntent second = intent(manifest, worker, 2);
    return store.providerAttributed(
        manifest,
        sequence13.cursor(),
        attribution(manifest, worker, second),
        manifest.startedAt().plusMillis(13));
  }

  static GraphAttemptCursor completeCatalogTxB(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence14,
      int repetition) {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        Pack010GraphEvalCatalog.workerProfile(repetition);
    if (!manifest.equals(sequence14.manifest())
        || sequence14.cursor().lastSequence() != 14
        || sequence14.cursor().phase()
            != GraphAttemptPhase.PROVIDER_ATTRIBUTED) {
      throw new IllegalArgumentException(
          "Pack010 catalog TX-B requires exact sequence 14");
    }
    SuccessfulChildTruth childTruth =
        successfulChild(
            sequence14, manifest, worker, manifest.startedAt());
    return store.completeChild(
        manifest,
        sequence14.cursor(),
        AgentRunContext.fromRunning(sequence14.parentRun()),
        childTruth.terminal(),
        childTruth.candidate(),
        childTruth.workerResult(),
        manifest.startedAt().plusMillis(14));
  }

  static GraphAttemptSnapshot prepareTxA(
      DataSource dataSource,
      PostgresGraphAttemptStore store) {
    ensureCapture(dataSource);
    return prepareTxAPrefixOnly(store);
  }

  static GraphAttemptSnapshot prepareTxAPrefixOnly(
      PostgresGraphAttemptStore store) {
    GraphAttemptManifest manifest = manifest();
    GraphAttemptCursor cursor =
        ((io.emergeos.core.port.GraphAttemptStore
                    .CreateResult.Created)
                store.create(manifest, at(0)))
            .cursor();
    cursor =
        store.approve(
            manifest,
            cursor,
            GraphOperatorApproval.ownerTty(manifest),
            at(1));
    cursor =
        store.authorizeParent(
            manifest, cursor, parentRunning(), at(2));
    cursor =
        store.startParent(
            manifest, cursor, parentRunning(), at(3));
    cursor =
        store.authorizeChild(
            manifest, cursor, childRunning(), at(4));
    cursor =
        store.startChild(
            manifest, cursor, childRunning(), at(5));
    cursor = store.consumeChildEgress(manifest, cursor, at(6));
    cursor =
        store.credentialReadStarted(manifest, cursor, at(7));
    cursor = store.clientCreated(manifest, cursor, at(8));
    cursor = store.modelCreated(manifest, cursor, at(9));
    GraphProviderIntent firstIntent = intent(1);
    cursor =
        store.providerIntent(
            manifest, cursor, firstIntent, at(10));
    cursor =
        store.providerAttributed(
            manifest,
            cursor,
            attribution(firstIntent),
            at(11));
    GraphProviderIntent secondIntent = intent(2);
    cursor =
        store.providerIntent(
            manifest, cursor, secondIntent, at(12));
    GraphAttemptSnapshot snapshot = verified(store);
    requireCheckpoint(snapshot, Checkpoint.SEQ13);
    if (!snapshot.cursor().equals(cursor)) {
      throw new IllegalStateException(
          "Pack010 seq13 cursor drifted");
    }
    return snapshot;
  }

  static GraphAttemptCursor completeTxA(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence13) {
    requireCheckpoint(sequence13, Checkpoint.SEQ13);
    return store.providerAttributed(
        manifest(),
        sequence13.cursor(),
        attribution(intent(2)),
        at(13));
  }

  static GraphAttemptSnapshot prepareTxC(
      DataSource dataSource,
      PostgresGraphAttemptStore store) {
    GraphAttemptSnapshot sequence14 =
        prepareTxB(dataSource, store);
    completeTxB(store, sequence14);
    GraphAttemptSnapshot snapshot = verified(store);
    requireCheckpoint(snapshot, Checkpoint.SEQ15);
    return snapshot;
  }

  static GraphAttemptCursor completeTxB(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence14) {
    requireCheckpoint(sequence14, Checkpoint.SEQ14);
    SuccessfulChildTruth truth =
        successfulChild(sequence14);
    return store.completeChild(
        manifest(),
        sequence14.cursor(),
        AgentRunContext.fromRunning(sequence14.parentRun()),
        truth.terminal(),
        truth.candidate(),
        truth.workerResult(),
        at(14));
  }

  static GraphAttemptCursor completeTxC(
      PostgresGraphAttemptStore store,
      GraphAttemptSnapshot sequence15) {
    requireCheckpoint(sequence15, Checkpoint.SEQ15);
    ParentTerminalTruth truth =
        successfulParent(sequence15);
    return store.completeParentAndSeal(
        manifest(),
        sequence15.cursor(),
        truth.terminal(),
        truth.artifact(),
        at(15));
  }

  static String completionFingerprint(
      GraphAttemptSnapshot snapshot, String tx) {
    return switch (tx) {
      case "TX_A" -> {
        requireCheckpoint(snapshot, Checkpoint.SEQ13);
        yield attribution(intent(2)).attributionHash();
      }
      case "TX_B" -> {
        requireCheckpoint(snapshot, Checkpoint.SEQ14);
        SuccessfulChildTruth truth =
            successfulChild(snapshot);
        yield IntegrityHashes.utf8ContentHash(
            "TX_B|"
                + truth.terminal().bundle().integrityHash()
                + "|"
                + truth.candidate().integrityHash()
                + "|"
                + truth.workerResult().integrityHash());
      }
      case "TX_C" -> {
        requireCheckpoint(snapshot, Checkpoint.SEQ15);
        ParentTerminalTruth truth =
            successfulParent(snapshot);
        yield IntegrityHashes.utf8ContentHash(
            "TX_C|"
                + truth.terminal().bundle().integrityHash()
                + "|"
                + truth.artifact().current().contentHash());
      }
      default -> throw new IllegalArgumentException(
          "unknown Pack010 transaction");
    };
  }

  static GraphAttemptSnapshot verified(
      PostgresGraphAttemptStore store) {
    return verified(store, manifest());
  }

  static GraphAttemptSnapshot verified(
      PostgresGraphAttemptStore store,
      GraphAttemptManifest manifest) {
    return ((GraphAttemptVerification.Valid)
            store.findVerified(manifest))
        .snapshot();
  }

  static void requireCheckpoint(
      GraphAttemptSnapshot snapshot,
      Checkpoint checkpoint) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(checkpoint, "checkpoint");
    int sequence = snapshot.cursor().lastSequence();
    GraphAttemptPhase phase = snapshot.cursor().phase();
    if (sequence != checkpoint.sequence
        || phase != checkpoint.phase
        || snapshot.providerAttributions().size()
            != checkpoint.attributionCount) {
      throw new IllegalStateException(
          "Pack010 checkpoint truth drifted");
    }
  }

  static Instant at(long millis) {
    return STARTED_AT.plusMillis(millis);
  }

  private static GraphProviderIntent intent(int ordinal) {
    return new GraphProviderIntent(
        ordinal,
        IntegrityHashes.utf8ContentHash(
            "pack010 terminal request " + ordinal),
        workerProfile().modelRequested());
  }

  private static GraphProviderIntent intent(
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      int ordinal) {
    return new GraphProviderIntent(
        ordinal,
        IntegrityHashes.utf8ContentHash(
            manifest.attemptId()
                + ":pack010 terminal request "
                + ordinal),
        worker.modelRequested());
  }

  private static GraphProviderAttribution attribution(
      GraphProviderIntent intent) {
    var pricing = workerProfile().pricing();
    return GraphProviderAttribution.create(
        intent.requestOrdinal(),
        intent.requestHash(),
        IntegrityHashes.utf8ContentHash(
            "pack010 terminal response "
                + intent.requestOrdinal()),
        manifest().childActor(),
        pricing.modelRequested(),
        pricing.modelRequested(),
        pricing.graphSnapshot(),
        1,
        0,
        1,
        0,
        2,
        pricing.actualCostUsd(1, 0, 1));
  }

  private static GraphProviderAttribution attribution(
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      GraphProviderIntent intent) {
    var pricing = worker.pricing();
    return GraphProviderAttribution.create(
        intent.requestOrdinal(),
        intent.requestHash(),
        IntegrityHashes.utf8ContentHash(
            manifest.attemptId()
                + ":pack010 terminal response "
                + intent.requestOrdinal()),
        manifest.childActor(),
        pricing.modelRequested(),
        pricing.modelRequested(),
        pricing.graphSnapshot(),
        1,
        0,
        1,
        0,
        2,
        pricing.actualCostUsd(1, 0, 1));
  }

  static SuccessfulChildTruth successfulChild(
      GraphAttemptSnapshot sequence14) {
    return successfulChild(
        sequence14, manifest(), workerProfile(), STARTED_AT);
  }

  static SuccessfulChildTruth successfulChild(
      GraphAttemptSnapshot sequence14,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      Instant startedAt) {
    List<GraphProviderAttribution> attributions =
        sequence14.providerAttributions();
    TaskEnvelope task = sequence14.childRun().task();
    String runId = sequence14.childRun().runId();
    String evidenceRef =
        "capture://" + manifest.captureId();
    String content =
        "Prompt 不是咒语，而是在构造可验证的运行时状态。";
    WorkerResultEnvelope workerResult =
        WorkerResultEnvelope.create(
            runId,
            task.id(),
            task.outputSchema(),
            content,
            List.of(evidenceRef));
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.TOOL_REQUEST,
            "capture.read",
            "REQUESTED",
            evidenceRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.TOOL_RESULT,
            "capture.read",
            "SUCCEEDED",
            evidenceRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    appendTrace(
        events,
        TraceEventType.STRUCTURED_FINAL,
        null,
        "PROPOSED",
        "proposal://sha256:" + workerResult.contentHash(),
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), events);
    BigDecimal cost =
        attributions.stream()
            .map(GraphProviderAttribution::observedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long tokens =
        attributions.stream()
            .mapToLong(GraphProviderAttribution::totalTokens)
            .sum();
    String traceRef =
        "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.SUCCEEDED,
            List.of(),
            List.of(evidenceRef),
            List.of(),
            List.of(),
            List.of(),
            attributions.get(1).modelResolved(),
            worker.agentVersion(),
            worker.verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            null);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            worker.experiment(),
            result.resolvedModel(),
            worker.harnessVersion(),
            worker.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    evidenceRef,
                    manifest.captureRequestHash()),
                new ResourceBinding(
                    ResourceRole.WORKER_RESULT,
                    0,
                    workerResult.workerResultRef(),
                    workerResult.integrityHash())),
            null,
            null,
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            manifest.principalId(),
            task,
            io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
            result,
            trace,
            bundle,
            startedAt,
            startedAt.plusMillis(14));
    HarnessCandidateEnvelope candidate =
        HarnessCandidateComposer.compose(
            manifest,
            terminal,
            new AgentDraftProposal(
                content, List.of(evidenceRef)),
            attributions);
    return new SuccessfulChildTruth(
        terminal, candidate, workerResult);
  }

  static FailedChildTruth attributedPreCandidateFailureChild(
      GraphAttemptSnapshot sequence14,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      Instant startedAt,
      String failureReason) {
    if (!(failureReason.equals("MODEL_RESPONSE_MALFORMED")
        || failureReason.equals("MODEL_USAGE_LIMIT_EXCEEDED"))) {
      throw new IllegalArgumentException(
          "Pack010 attributed failure fixture identity drifted");
    }
    return attributedFailureChild(
        sequence14, manifest, worker, startedAt, failureReason);
  }

  static FailedChildTruth rawNonAllowlistedAttributedFailureChild(
      GraphAttemptSnapshot sequence14,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      Instant startedAt) {
    return attributedFailureChild(
        sequence14,
        manifest,
        worker,
        startedAt,
        "MODEL_ATTRIBUTION_MISMATCH");
  }

  private static FailedChildTruth attributedFailureChild(
      GraphAttemptSnapshot sequence14,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      Instant startedAt,
      String failureReason) {
    requireCheckpoint(sequence14, Checkpoint.SEQ14);
    if (!manifest.equals(sequence14.manifest())) {
      throw new IllegalArgumentException(
          "Pack010 attributed failure fixture identity drifted");
    }
    List<GraphProviderAttribution> attributions =
        sequence14.providerAttributions();
    TaskEnvelope task = sequence14.childRun().task();
    String runId = sequence14.childRun().runId();
    AgentTraceEntry failedStep =
        AgentTraceEntry.create(
            1,
            TraceEventType.MODEL_STEP,
            null,
            "FAILED",
            "task://" + task.id(),
            IntegrityHashes.emptyTraceRoot());
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), List.of(failedStep));
    BigDecimal cost =
        attributions.stream()
            .map(GraphProviderAttribution::observedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long tokens =
        attributions.stream()
            .mapToLong(GraphProviderAttribution::totalTokens)
            .sum();
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.FAILED,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            attributions.getLast().modelResolved(),
            worker.agentVersion(),
            worker.verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            failureReason);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            worker.experiment(),
            result.resolvedModel(),
            worker.harnessVersion(),
            worker.componentVersions(),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(),
            List.of(),
            List.of(),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            manifest.principalId(),
            task,
            io.emergeos.core.domain.AgentRunLifecycle.FAILED,
            result,
            trace,
            bundle,
            startedAt,
            startedAt.plusMillis(14));
    return new FailedChildTruth(terminal, failureReason);
  }

  static ParentTerminalTruth successfulParent(
      GraphAttemptSnapshot sequence15) {
    return successfulParent(
        sequence15,
        manifest(),
        workerProfile(),
        AgentExecutionProfile.readOnlyWorkerModelV1(workerProfile()),
        STARTED_AT);
  }

  static ParentTerminalTruth successfulParent(
      GraphAttemptSnapshot sequence15,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      AgentExecutionProfile parentProfile,
      Instant startedAt) {
    TaskEnvelope task = sequence15.parentRun().task();
    String runId = sequence15.parentRun().runId();
    String evidenceRef =
        "capture://" + manifest.captureId();
    String childRef =
        "agent-run://" + sequence15.childRun().runId();
    ArtifactLineage artifact =
        new ArtifactLineage(
            manifest.artifactId(),
            manifest.principalId(),
            manifest.captureId(),
            List.of(
                new ArtifactLineageEntry(
                    1,
                    sequence15.candidate().content(),
                    sequence15.candidate().contentHash(),
                    null,
                    null,
                    startedAt.plusMillis(15))));
    String artifactRef =
        "artifact-version://"
            + artifact.artifactId()
            + "/"
            + artifact.current().version();
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_REQUEST,
            null,
            "REQUESTED",
            childRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_RESULT,
            null,
            "SUCCEEDED",
            childRef,
            root);
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.STRUCTURED_FINAL,
            null,
            "PROPOSED",
            "proposal://sha256:"
                + sequence15.workerResult().contentHash(),
            root);
    appendTrace(
        events,
        TraceEventType.ARTIFACT_COMMITTED,
        null,
        "SUCCEEDED",
        artifactRef,
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), events);
    BigDecimal cost =
        sequence15.childRun().result().costUsd();
    long tokens = sequence15.childRun().result().tokenCount();
    String traceRef =
        "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            RunStatus.SUCCEEDED,
            List.of(artifactRef),
            sequence15.workerResult().evidenceRefs(),
            List.of(),
            List.of(),
            List.of(),
            "fake-pack010-conductor-v1",
            parentProfile.agentVersion(),
            parentProfile.verifierVersion(),
            cost,
            tokens,
            1,
            traceRef,
            null);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            worker.experiment(),
            result.resolvedModel(),
            parentProfile.harnessVersion(),
            parentProfile.componentVersions(worker),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.EVIDENCE,
                    0,
                    evidenceRef,
                    manifest.captureRequestHash()),
                new ResourceBinding(
                    ResourceRole.ARTIFACT,
                    0,
                    artifactRef,
                    artifact.current().contentHash()),
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    sequence15
                        .childRun()
                        .bundle()
                        .integrityHash())),
            null,
            null,
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            manifest.principalId(),
            task,
            io.emergeos.core.domain.AgentRunLifecycle.SUCCEEDED,
            result,
            trace,
            bundle,
            startedAt,
            startedAt.plusMillis(15));
    return new ParentTerminalTruth(terminal, artifact);
  }

  static NonSuccessParentTruth preCandidateFailureParent(
      GraphAttemptSnapshot sequence15,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      AgentExecutionProfile parentProfile,
      Instant startedAt) {
    return preCandidateNonSuccessParent(
        sequence15,
        manifest,
        worker,
        parentProfile,
        startedAt,
        RunStatus.FAILED,
        io.emergeos.core.domain.AgentRunLifecycle.FAILED,
        "HANDOFF_CHILD_FAILED",
        "CHILD_FAILED");
  }

  static NonSuccessParentTruth preCandidateBlockedParent(
      GraphAttemptSnapshot sequence15,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      AgentExecutionProfile parentProfile,
      Instant startedAt) {
    return preCandidateNonSuccessParent(
        sequence15,
        manifest,
        worker,
        parentProfile,
        startedAt,
        RunStatus.BLOCKED,
        io.emergeos.core.domain.AgentRunLifecycle.BLOCKED,
        "HANDOFF_CHILD_BLOCKED",
        "CHILD_BLOCKED");
  }

  private static NonSuccessParentTruth preCandidateNonSuccessParent(
      GraphAttemptSnapshot sequence15,
      GraphAttemptManifest manifest,
      ModelBoundReadOnlyWorkerExecutionProfile worker,
      AgentExecutionProfile parentProfile,
      Instant startedAt,
      RunStatus parentStatus,
      io.emergeos.core.domain.AgentRunLifecycle parentLifecycle,
      String failureReason,
      String handoffStatus) {
    requireCheckpoint(sequence15, Checkpoint.SEQ15);
    if (!manifest.equals(sequence15.manifest())
        || sequence15.childRun().result().status() != RunStatus.FAILED
        || sequence15.candidate() != null
        || sequence15.workerResult() != null) {
      throw new IllegalArgumentException(
          "Pack010 failed parent fixture requires failed child truth");
    }
    TaskEnvelope task = sequence15.parentRun().task();
    String runId = sequence15.parentRun().runId();
    String childRef = "agent-run://" + sequence15.childRun().runId();
    List<AgentTraceEntry> events = new ArrayList<>();
    String root = IntegrityHashes.emptyTraceRoot();
    root =
        appendTrace(
            events,
            TraceEventType.MODEL_STEP,
            null,
            "COMPLETED",
            "task://" + task.id(),
            root);
    root =
        appendTrace(
            events,
            TraceEventType.HANDOFF_REQUEST,
            null,
            "REQUESTED",
            childRef,
            root);
    appendTrace(
        events,
        TraceEventType.HANDOFF_REJECTED,
        null,
        handoffStatus,
        childRef,
        root);
    AgentTraceEnvelope trace =
        AgentTraceEnvelope.create(
            "1.0", runId, task.id(), events);
    BigDecimal cost = sequence15.childRun().result().costUsd();
    long tokens = sequence15.childRun().result().tokenCount();
    String traceRef = "/api/v1/agent-runs/" + runId + "/trace";
    ResultEnvelope result =
        new ResultEnvelope(
            "1.0",
            runId,
            task.id(),
            parentStatus,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "fake-pack010-conductor-v1",
            parentProfile.agentVersion(),
            parentProfile.verifierVersion(),
            cost,
            tokens,
            2,
            traceRef,
            failureReason);
    HarnessRunBundle bundle =
        HarnessRunBundle.create(
            task.schemaVersion(),
            runId,
            task.id(),
            worker.experiment(),
            result.resolvedModel(),
            parentProfile.harnessVersion(),
            parentProfile.componentVersions(worker),
            task.environmentSnapshotRef(),
            task.toolRegistryVersion(),
            task,
            result,
            null,
            traceRef,
            trace.rootHash(),
            List.of(childRef),
            List.of(),
            List.of(
                new ResourceBinding(
                    ResourceRole.HANDOFF,
                    0,
                    childRef,
                    sequence15.childRun().bundle().integrityHash())),
            null,
            result.failureReason(),
            result.status(),
            result.costUsd(),
            result.tokenCount(),
            result.latencyMs());
    AgentRun terminal =
        new AgentRun(
            runId,
            manifest.principalId(),
            task,
            parentLifecycle,
            result,
            trace,
            bundle,
            startedAt,
            startedAt.plusMillis(15));
    return new NonSuccessParentTruth(terminal);
  }

  private static String appendTrace(
      List<AgentTraceEntry> events,
      TraceEventType type,
      String toolName,
      String status,
      String reference,
      String previousRootHash) {
    AgentTraceEntry entry =
        AgentTraceEntry.create(
            events.size() + 1,
            type,
            toolName,
            status,
            reference,
            previousRootHash);
    events.add(entry);
    return IntegrityHashes.nextTraceRoot(
        entry.previousRootHash(), entry.eventHash());
  }

  enum Checkpoint {
    SEQ13(13, GraphAttemptPhase.PROVIDER_PENDING, 1),
    SEQ14(14, GraphAttemptPhase.PROVIDER_ATTRIBUTED, 2),
    SEQ15(15, GraphAttemptPhase.CHILD_TERMINAL, 2),
    SEQ17(17, GraphAttemptPhase.TERMINAL, 2);

    private final int sequence;
    private final GraphAttemptPhase phase;
    private final int attributionCount;

    Checkpoint(
        int sequence,
        GraphAttemptPhase phase,
        int attributionCount) {
      this.sequence = sequence;
      this.phase = phase;
      this.attributionCount = attributionCount;
    }

    int sequence() {
      return sequence;
    }
  }

  record SuccessfulChildTruth(
      AgentRun terminal,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult) {}

  record FailedChildTruth(
      AgentRun terminal, String failureReason) {}

  record ParentTerminalTruth(
      AgentRun terminal, ArtifactLineage artifact) {}

  record NonSuccessParentTruth(AgentRun terminal) {}
}
