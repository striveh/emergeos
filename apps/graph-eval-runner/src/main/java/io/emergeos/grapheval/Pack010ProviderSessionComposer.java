package io.emergeos.grapheval;

import com.openai.core.LogLevel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.contracts.RunStatus;
import java.net.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact dormant Pack010 provider-session composition.
 *
 * <p>The endpoint, proxy, retry and logging policy are fixed in production
 * bytecode. Construction does not send a request. Before every later SDK call,
 * the model observer durably appends the matching provider intent; observer
 * failure therefore prevents that call. A strict transport-bound response
 * receipt is then synchronously appended before the model result can cross
 * this composition boundary.
 */
final class Pack010ProviderSessionComposer {

  private static final String PRODUCTION_BASE_URL =
      "https://api.openai.com/v1";

  ProviderSession compose(
      Pack010GraphPreflight.Result preflight,
      Pack010ProviderCredentialBroker.CredentialLease credentialLease,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egressAuthority,
      Clock clock) {
    Objects.requireNonNull(preflight, "preflight");
    Objects.requireNonNull(credentialLease, "credentialLease");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    Objects.requireNonNull(clock, "clock");

    OwnerTtyGraphAuthority.Pack010Revision revision =
        credentialLease.revision();
    int repetition = revision.ordinal() + 1;
    ModelBoundReadOnlyWorkerExecutionProfile profile =
        Pack010GraphEvalCatalog.workerProfile(repetition);
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    coordinator.requireEgressManifest(
        egressAuthority, manifest);
    OutcomeLedger outcomeLedger = new OutcomeLedger();

    String credential =
        credentialLease.claim(coordinator, egressAuthority, clock);
    ReviewedOpenAiClient client = null;
    AgentModel.Session modelSession = null;
    try {
      credentialLease.requireFresh(
          coordinator, egressAuthority, clock);
      client =
          ReviewedOpenAiClient.defaultCodecNoRetry(
              credential,
              PRODUCTION_BASE_URL,
              Proxy.NO_PROXY,
              Duration.ofMillis(profile.deadlineMs()),
              LogLevel.OFF);
      credential = null;
      coordinator.clientCreated(
          egressAuthority, canonicalTime(clock.instant()));

      credentialLease.requireFresh(
          coordinator, egressAuthority, clock);
      OpenAiResponsesModel model =
          OpenAiResponsesModel.withExactResponseOutcome(
              profile,
              client,
              manifest.manifestHash(),
              durableIntentObserver(
                  profile,
                  repetition,
                  credentialLease,
                  coordinator,
                  egressAuthority,
                  clock),
              durableOutcomeObserver(
                  profile,
                  repetition,
                  coordinator,
                  egressAuthority,
                  clock,
                  outcomeLedger));
      coordinator.modelCreated(
          egressAuthority, canonicalTime(clock.instant()));
      credentialLease.requireFresh(
          coordinator, egressAuthority, clock);
      modelSession =
          model.open(Pack010GraphEvalCatalog.childTask(repetition));
      return new ProviderSession(
          modelSession,
          client,
          revision,
          credentialLease,
          coordinator,
          egressAuthority,
          clock,
          manifest,
          outcomeLedger);
    } catch (RuntimeException failure) {
      closeAfterFailure(modelSession, client);
      throw failure;
    } finally {
      credential = null;
    }
  }

  static OpenAiResponsesModel.ProviderInvocationObserver
      durableIntentObserver(
          ModelBoundReadOnlyWorkerExecutionProfile profile,
          int repetition,
          Pack010ProviderCredentialBroker.CredentialLease credentialLease,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egressAuthority,
          Clock clock) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(credentialLease, "credentialLease");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    Objects.requireNonNull(clock, "clock");
    if (repetition < 1
        || repetition
            > Pack010GraphEvalCatalog.GENERATION_REPETITIONS
        || !profile.equals(
            Pack010GraphEvalCatalog.workerProfile(repetition))) {
      throw new IllegalArgumentException(
          "provider observer profile does not match catalog");
    }
    coordinator.requireEgressManifest(
        egressAuthority,
        Pack010GraphEvalCatalog.manifest(repetition));
    AtomicInteger nextRequestOrdinal = new AtomicInteger(1);
    return invocation -> {
      credentialLease.requireFresh(
          coordinator, egressAuthority, clock);
      int expectedOrdinal = nextRequestOrdinal.getAndIncrement();
      if (invocation.requestOrdinal() != expectedOrdinal
          || invocation.requestOrdinal()
              > Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || !profile.modelRequested().equals(
              invocation.modelRequested())
          || (invocation.requestOrdinal() == 1
              && !Pack010GraphEvalCatalog
                  .computedFirstRequestHash(repetition)
                  .equals(invocation.requestHash()))) {
        throw new ProviderSessionRejected(
            "PROVIDER_INTENT_IDENTITY_DRIFT");
      }
      coordinator.providerIntent(
          egressAuthority,
          new GraphProviderIntent(
              invocation.requestOrdinal(),
              invocation.requestHash(),
              invocation.modelRequested()),
          canonicalTime(clock.instant()));
    };
  }

  static OpenAiResponsesModel.ExactProviderAttributionObserver
      durableAttributionObserver(
          ModelBoundReadOnlyWorkerExecutionProfile profile,
          int repetition,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egressAuthority,
          Clock clock) {
    return durableAttributionObserver(
        profile,
        repetition,
        coordinator,
        egressAuthority,
        clock,
        null);
  }

  private static OpenAiResponsesModel.ExactProviderAttributionObserver
      durableAttributionObserver(
          ModelBoundReadOnlyWorkerExecutionProfile profile,
          int repetition,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egressAuthority,
          Clock clock,
          OutcomeLedger outcomeLedger) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    Objects.requireNonNull(clock, "clock");
    if (repetition < 1
        || repetition
            > Pack010GraphEvalCatalog.GENERATION_REPETITIONS
        || !profile.equals(
            Pack010GraphEvalCatalog.workerProfile(repetition))) {
      throw new IllegalArgumentException(
          "provider attribution profile does not match catalog");
    }
    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    coordinator.requireEgressManifest(egressAuthority, manifest);
    return receipt -> {
      if (!profile.modelRequested().equals(
              receipt.invocation().modelRequested())
          || receipt.invocation().requestOrdinal()
              > Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
        throw new ProviderSessionRejected(
            "PROVIDER_ATTRIBUTION_IDENTITY_DRIFT");
      }
      GraphProviderAttribution attribution =
          GraphProviderAttribution.create(
              receipt.invocation().requestOrdinal(),
              receipt.invocation().requestHash(),
              receipt.responseHash(),
              manifest.childActor(),
              receipt.invocation().modelRequested(),
              receipt.modelResolved(),
              profile.pricing().graphSnapshot(),
              receipt.inputTokens(),
              receipt.cachedInputTokens(),
              receipt.outputTokens(),
              receipt.reasoningOutputTokens(),
              receipt.totalTokens(),
              receipt.observedCostUsd());
      coordinator.providerAttributed(
          egressAuthority,
          attribution,
          canonicalTime(clock.instant()));
      if (outcomeLedger != null) {
        outcomeLedger.record(attribution);
      }
    };
  }

  private static OpenAiResponsesModel.ExactProviderOutcomeObserver
      durableOutcomeObserver(
          ModelBoundReadOnlyWorkerExecutionProfile profile,
          int repetition,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egressAuthority,
          Clock clock,
          OutcomeLedger outcomeLedger) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(outcomeLedger, "outcomeLedger");
    if (repetition < 1
        || repetition > Pack010GraphEvalCatalog.GENERATION_REPETITIONS
        || !profile.equals(Pack010GraphEvalCatalog.workerProfile(repetition))) {
      throw new IllegalArgumentException(
          "provider outcome profile does not match catalog");
    }
    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    coordinator.requireEgressManifest(egressAuthority, manifest);
    return outcome -> {
      var receipt = outcome.attribution();
      if (!profile.modelRequested().equals(
              receipt.invocation().modelRequested())
          || receipt.invocation().requestOrdinal()
              > Pack010GraphEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
        throw new ProviderSessionRejected(
            "PROVIDER_OUTCOME_IDENTITY_DRIFT");
      }
      GraphProviderAttribution attribution =
          GraphProviderAttribution.create(
              receipt.invocation().requestOrdinal(),
              receipt.invocation().requestHash(),
              receipt.responseHash(),
              manifest.childActor(),
              receipt.invocation().modelRequested(),
              receipt.modelResolved(),
              profile.pricing().graphSnapshot(),
              receipt.inputTokens(),
              receipt.cachedInputTokens(),
              receipt.outputTokens(),
              receipt.reasoningOutputTokens(),
              receipt.totalTokens(),
              receipt.observedCostUsd());
      if (outcome.kind()
              == OpenAiResponsesModel.ProviderOutcomeKind.FAILED
          && attribution.requestOrdinal() == 2) {
        coordinator.providerFailureAttributed(
            egressAuthority,
            attribution,
            GraphAttributedFailureCode.require(outcome.failureReason()),
            canonicalTime(clock.instant()));
      } else {
        coordinator.providerAttributed(
            egressAuthority,
            attribution,
            canonicalTime(clock.instant()));
      }
      outcomeLedger.record(attribution);
    };
  }

  /**
   * Consumes an outcome only when its exact request-2 decision and durable
   * attribution match one Candidate. A public ModelStep or Candidate alone
   * can never pass this boundary.
   */
  static StructuredFinalBinding claimStructuredFinal(
      Pack010AttributedModelOutcome outcome,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
      GraphAttemptManifest expectedManifest,
      HarnessCandidateEnvelope candidate) {
    StructuredFinalBinding binding =
        requireStructuredFinal(
            outcome,
            expectedCoordinator,
            expectedEgress,
            expectedRevision,
            expectedManifest,
            candidate);
    if (!outcome.claimed.compareAndSet(false, true)) {
      throw new ProviderSessionRejected(
          "STRUCTURED_FINAL_OUTCOME_ALREADY_CLAIMED");
    }
    return binding;
  }

  static StructuredFinalBinding reviewStructuredFinal(
      Pack010AttributedModelOutcome outcome,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
      GraphAttemptManifest expectedManifest,
      HarnessCandidateEnvelope candidate) {
    return requireStructuredFinal(
        outcome,
        expectedCoordinator,
        expectedEgress,
        expectedRevision,
        expectedManifest,
        candidate);
  }

  private static StructuredFinalBinding requireStructuredFinal(
      Pack010AttributedModelOutcome outcome,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
      GraphAttemptManifest expectedManifest,
      HarnessCandidateEnvelope candidate) {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(expectedCoordinator, "expectedCoordinator");
    Objects.requireNonNull(expectedEgress, "expectedEgress");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(expectedManifest, "expectedManifest");
    Objects.requireNonNull(candidate, "candidate");
    outcome.credentialLease.requireFresh(
        expectedCoordinator, expectedEgress, outcome.clock);
    if (outcome.coordinator != expectedCoordinator
        || outcome.egress != expectedEgress
        || outcome.revision != expectedRevision
        || !outcome.manifest.equals(expectedManifest)
        || outcome.attributions.size() != 2
        || outcome.attribution.requestOrdinal() != 2
        || outcome.attributions.get(1) != outcome.attribution
        || !(outcome.step.decision()
            instanceof AgentModel.FinalDraft finalDraft)
        || !outcome.step.resolvedModel().equals(
            outcome.attribution.modelResolved())
        || outcome.step.usage().tokenCount()
            != outcome.attribution.totalTokens()
        || outcome.step.usage().costUsd().compareTo(
                outcome.attribution.observedCostUsd())
            != 0
        || !candidate.attemptId().equals(expectedManifest.attemptId())
        || !candidate.executionSlotId().equals(
            expectedManifest.executionSlotId())
        || candidate.repetition()
            != expectedManifest.experiment().repetition()
        || !candidate.childRunId().equals(
            expectedManifest.childSelection().runId())
        || !candidate.childTaskId().equals(
            expectedManifest.childSelection().taskId())
        || candidate.sourceRequestOrdinal() != 2
        || !candidate.sourceResponseHash().equals(
            outcome.attribution.responseHash())
        || !candidate.outputSchema().equals(
            Pack010GraphEvalCatalog
                .childTask(candidate.repetition())
                .outputSchema())
        || !candidate.content().equals(finalDraft.content())
        || !candidate.evidenceRefs().equals(
            finalDraft.evidenceRefs())) {
      throw new ProviderSessionRejected(
          "STRUCTURED_FINAL_OUTCOME_IDENTITY_DRIFT");
    }
    expectedCoordinator.requireProviderAttributed(
        expectedEgress, expectedManifest);
    if (outcome.claimed.get()) {
      throw new ProviderSessionRejected(
          "STRUCTURED_FINAL_OUTCOME_ALREADY_CLAIMED");
    }
    return new StructuredFinalBinding(
        outcome.attributions,
        outcome.step.resolvedModel(),
        candidate.integrityHash(),
        candidate.contentHash(),
        candidate.evidenceRefs());
  }

  static AttributedPreCandidateFailure
      claimAttributedPreCandidateFailure(
          Pack010AttributedModelOutcome outcome,
          GraphAttemptCoordinator expectedCoordinator,
          GraphAttemptCoordinator.EgressAuthority expectedEgress,
          OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
          GraphAttemptManifest expectedManifest) {
    AttributedPreCandidateFailure binding =
        requireAttributedPreCandidateFailure(
            outcome,
            expectedCoordinator,
            expectedEgress,
            expectedRevision,
            expectedManifest);
    if (!outcome.claimed.compareAndSet(false, true)) {
      throw new ProviderSessionRejected(
          "ATTRIBUTED_OUTCOME_ALREADY_CLAIMED");
    }
    return binding;
  }

  static AttributedPreCandidateFailure
      reviewAttributedPreCandidateFailure(
          Pack010AttributedModelOutcome outcome,
          GraphAttemptCoordinator expectedCoordinator,
          GraphAttemptCoordinator.EgressAuthority expectedEgress,
          OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
          GraphAttemptManifest expectedManifest) {
    return requireAttributedPreCandidateFailure(
        outcome,
        expectedCoordinator,
        expectedEgress,
        expectedRevision,
        expectedManifest);
  }

  private static AttributedPreCandidateFailure
      requireAttributedPreCandidateFailure(
          Pack010AttributedModelOutcome outcome,
          GraphAttemptCoordinator expectedCoordinator,
          GraphAttemptCoordinator.EgressAuthority expectedEgress,
          OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
          GraphAttemptManifest expectedManifest) {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(expectedCoordinator, "expectedCoordinator");
    Objects.requireNonNull(expectedEgress, "expectedEgress");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(expectedManifest, "expectedManifest");
    outcome.credentialLease.requireFresh(
        expectedCoordinator, expectedEgress, outcome.clock);
    if (outcome.coordinator != expectedCoordinator
        || outcome.egress != expectedEgress
        || outcome.revision != expectedRevision
        || !outcome.manifest.equals(expectedManifest)
        || outcome.attributions.size() != 2
        || outcome.attribution.requestOrdinal() != 2
        || outcome.attributions.get(1) != outcome.attribution
        || !(outcome.step.decision()
            instanceof AgentModel.Failed failed)
        || !outcome.step.resolvedModel().equals(
            outcome.attribution.modelResolved())
        || outcome.step.usage().tokenCount()
            != outcome.attribution.totalTokens()
        || outcome.step.usage().costUsd().compareTo(
                outcome.attribution.observedCostUsd())
            != 0) {
      throw new ProviderSessionRejected(
          "ATTRIBUTED_FAILURE_OUTCOME_IDENTITY_DRIFT");
    }
    PreCandidateFailureCode code =
        PreCandidateFailureCode.from(failed);
    expectedCoordinator.requireProviderAttributed(
        expectedEgress, expectedManifest);
    if (outcome.claimed.get()) {
      throw new ProviderSessionRejected(
          "ATTRIBUTED_OUTCOME_ALREADY_CLAIMED");
    }
    return new AttributedPreCandidateFailure(
        outcome.owner,
        expectedManifest,
        outcome.attributions,
        outcome.attribution,
        outcome.step.resolvedModel(),
        code);
  }

  private static void closeAfterFailure(
      AgentModel.Session session, ReviewedOpenAiClient client) {
    if (session != null) {
      try {
        session.close();
      } catch (RuntimeException ignored) {
        // The original composition failure remains authoritative.
      }
    }
    if (client != null) {
      try {
        client.close();
      } catch (RuntimeException ignored) {
        // The original composition failure remains authoritative.
      }
    }
  }

  private static Instant canonicalTime(Instant instant) {
    return Objects.requireNonNull(instant, "instant")
        .truncatedTo(ChronoUnit.MICROS);
  }

  static final class ProviderSession implements AutoCloseable {

    private final AgentModel.Session session;
    private final ReviewedOpenAiClient client;
    private final OwnerTtyGraphAuthority.Pack010Revision revision;
    private final Pack010ProviderCredentialBroker.CredentialLease
        credentialLease;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egressAuthority;
    private final Clock clock;
    private final GraphAttemptManifest manifest;
    private final OutcomeLedger outcomeLedger;
    private final Object outcomeOwner = new Object();
    private int nextOutcomeOrdinal = 1;
    private boolean closed;

    private ProviderSession(
        AgentModel.Session session,
        ReviewedOpenAiClient client,
        OwnerTtyGraphAuthority.Pack010Revision revision,
        Pack010ProviderCredentialBroker.CredentialLease credentialLease,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egressAuthority,
        Clock clock) {
      this(
          session,
          client,
          revision,
          credentialLease,
          coordinator,
          egressAuthority,
          clock,
          Pack010GraphEvalCatalog.manifest(revision.ordinal() + 1),
          new OutcomeLedger());
    }

    private ProviderSession(
        AgentModel.Session session,
        ReviewedOpenAiClient client,
        OwnerTtyGraphAuthority.Pack010Revision revision,
        Pack010ProviderCredentialBroker.CredentialLease credentialLease,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egressAuthority,
        Clock clock,
        GraphAttemptManifest manifest,
        OutcomeLedger outcomeLedger) {
      this.session = Objects.requireNonNull(session, "session");
      this.client = Objects.requireNonNull(client, "client");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.credentialLease =
          Objects.requireNonNull(credentialLease, "credentialLease");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egressAuthority =
          Objects.requireNonNull(egressAuthority, "egressAuthority");
      this.clock = Objects.requireNonNull(clock, "clock");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.outcomeLedger =
          Objects.requireNonNull(outcomeLedger, "outcomeLedger");
    }

    synchronized Pack010AttributedModelOutcome next(
        AgentModel.Turn turn,
        AgentModel.ModelCallContext context) {
      if (closed) {
        throw new ProviderSessionRejected(
            "PROVIDER_SESSION_CLOSED");
      }
      credentialLease.requireFresh(
          coordinator, egressAuthority, clock);
      int expectedOrdinal = nextOutcomeOrdinal;
      AgentModel.ModelStep step = session.next(turn, context);
      GraphProviderAttribution attribution =
          outcomeLedger.requireExact(expectedOrdinal, step);
      nextOutcomeOrdinal++;
      return new Pack010AttributedModelOutcome(
          outcomeOwner,
          revision,
          manifest,
          coordinator,
          egressAuthority,
          credentialLease,
          clock,
          step,
          attribution,
          outcomeLedger.snapshot(expectedOrdinal));
    }

    OwnerTtyGraphAuthority.Pack010Revision revision() {
      return revision;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      RuntimeException sessionFailure = null;
      try {
        session.close();
      } catch (RuntimeException failure) {
        sessionFailure = failure;
      }
      try {
        client.close();
      } catch (RuntimeException failure) {
        if (sessionFailure == null) {
          sessionFailure = failure;
        } else {
          sessionFailure.addSuppressed(failure);
        }
      }
      if (sessionFailure != null) {
        throw sessionFailure;
      }
    }
  }

  static final class Pack010AttributedModelOutcome {

    private final Object owner;
    private final OwnerTtyGraphAuthority.Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final Pack010ProviderCredentialBroker.CredentialLease
        credentialLease;
    private final Clock clock;
    private final AgentModel.ModelStep step;
    private final GraphProviderAttribution attribution;
    private final List<GraphProviderAttribution> attributions;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Pack010AttributedModelOutcome(
        Object owner,
        OwnerTtyGraphAuthority.Pack010Revision revision,
        GraphAttemptManifest manifest,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        Pack010ProviderCredentialBroker.CredentialLease credentialLease,
        Clock clock,
        AgentModel.ModelStep step,
        GraphProviderAttribution attribution,
        List<GraphProviderAttribution> attributions) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
      this.credentialLease =
          Objects.requireNonNull(credentialLease, "credentialLease");
      this.clock = Objects.requireNonNull(clock, "clock");
      this.step = Objects.requireNonNull(step, "step");
      this.attribution =
          Objects.requireNonNull(attribution, "attribution");
      this.attributions = List.copyOf(attributions);
    }

    AgentModel.Decision decision() {
      return step.decision();
    }
  }

  static final class StructuredFinalBinding {

    private final List<GraphProviderAttribution> attributions;
    private final String resolvedModel;
    private final String candidateIntegrityHash;
    private final String contentHash;
    private final List<String> evidenceRefs;

    private StructuredFinalBinding(
        List<GraphProviderAttribution> attributions,
        String resolvedModel,
        String candidateIntegrityHash,
        String contentHash,
        List<String> evidenceRefs) {
      this.attributions = List.copyOf(attributions);
      this.resolvedModel = resolvedModel;
      this.candidateIntegrityHash = candidateIntegrityHash;
      this.contentHash = contentHash;
      this.evidenceRefs = List.copyOf(evidenceRefs);
    }

    List<GraphProviderAttribution> attributions() {
      return attributions;
    }

    String resolvedModel() {
      return resolvedModel;
    }

    String candidateIntegrityHash() {
      return candidateIntegrityHash;
    }

    String contentHash() {
      return contentHash;
    }

    List<String> evidenceRefs() {
      return evidenceRefs;
    }
  }

  static final class AttributedPreCandidateFailure {

    private final Object outcomeOwner;
    private final GraphAttemptManifest manifest;
    private final List<GraphProviderAttribution> attributions;
    private final GraphProviderAttribution decisionAttribution;
    private final String resolvedModel;
    private final PreCandidateFailureCode code;
    private final String provenanceHash;

    private AttributedPreCandidateFailure(
        Object outcomeOwner,
        GraphAttemptManifest manifest,
        List<GraphProviderAttribution> attributions,
        GraphProviderAttribution decisionAttribution,
        String resolvedModel,
        PreCandidateFailureCode code) {
      this.outcomeOwner = Objects.requireNonNull(outcomeOwner, "outcomeOwner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.attributions = List.copyOf(attributions);
      this.decisionAttribution =
          Objects.requireNonNull(
              decisionAttribution, "decisionAttribution");
      this.resolvedModel =
          Objects.requireNonNull(resolvedModel, "resolvedModel");
      this.code = Objects.requireNonNull(code, "code");
      this.provenanceHash =
          IntegrityHashes.utf8ContentHash(
              "pack010-attributed-pre-candidate-failure-v1|"
                  + manifest.manifestHash()
                  + "|"
                  + decisionAttribution.requestOrdinal()
                  + "|"
                  + decisionAttribution.requestHash()
                  + "|"
                  + decisionAttribution.responseHash()
                  + "|"
                  + attributions.stream()
                      .map(GraphProviderAttribution::attributionHash)
                      .reduce((left, right) -> left + "|" + right)
                      .orElseThrow()
                  + "|"
                  + resolvedModel
                  + "|"
                  + code.name());
    }

    List<GraphProviderAttribution> attributions() {
      return attributions;
    }

    String provenanceHash() {
      return provenanceHash;
    }

    void requireTerminal(AgentRun terminal) {
      Objects.requireNonNull(terminal, "terminal");
      long attributedTokens =
          attributions.stream()
              .mapToLong(GraphProviderAttribution::totalTokens)
              .sum();
      java.math.BigDecimal attributedCost =
          attributions.stream()
              .map(GraphProviderAttribution::observedCostUsd)
              .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
      if (!manifest.childSelection().runId().equals(terminal.runId())
          || !manifest.childSelection().taskId().equals(terminal.task().id())
          || !manifest.principalId().equals(terminal.principalId())
          || terminal.lifecycle() != AgentRunLifecycle.FAILED
          || terminal.result().status() != RunStatus.FAILED
          || !code.name().equals(terminal.result().failureReason())
          || !code.name().equals(terminal.bundle().failureAttribution())
          || !resolvedModel.equals(terminal.result().resolvedModel())
          || attributedTokens != terminal.result().tokenCount()
          || attributedCost.compareTo(terminal.result().costUsd()) != 0
          || terminal.trace().events().size() != 1
          || terminal.trace().events().getFirst().type()
              != io.emergeos.contracts.TraceEventType.MODEL_STEP
          || !terminal.trace().events().getFirst().status().equals("FAILED")
          || !terminal.trace().events().getFirst().reference().equals(
              "task://" + terminal.task().id())) {
        throw new ProviderSessionRejected(
            "ATTRIBUTED_FAILURE_TERMINAL_IDENTITY_DRIFT");
      }
    }
  }

  private enum PreCandidateFailureCode {
    MODEL_RESPONSE_MALFORMED,
    MODEL_USAGE_LIMIT_EXCEEDED;

    private static PreCandidateFailureCode from(
        AgentModel.Failed failed) {
      for (PreCandidateFailureCode code : values()) {
        if (code.name().equals(failed.failureReason())) {
          return code;
        }
      }
      throw new ProviderSessionRejected(
          "ATTRIBUTED_FAILURE_CODE_NOT_ALLOWED");
    }
  }

  private static final class OutcomeLedger {

    private final List<GraphProviderAttribution> attributions =
        new ArrayList<>();

    private synchronized void record(
        GraphProviderAttribution attribution) {
      Objects.requireNonNull(attribution, "attribution");
      if (attribution.requestOrdinal() != attributions.size() + 1) {
        throw new ProviderSessionRejected(
            "PROVIDER_OUTCOME_LEDGER_SEQUENCE_DRIFT");
      }
      attributions.add(attribution);
    }

    private synchronized GraphProviderAttribution requireExact(
        int ordinal, AgentModel.ModelStep step) {
      Objects.requireNonNull(step, "step");
      if (ordinal < 1
          || ordinal > attributions.size()
          || attributions.size() != ordinal) {
        throw new ProviderSessionRejected(
            "PROVIDER_OUTCOME_ATTRIBUTION_MISSING");
      }
      GraphProviderAttribution attribution =
          attributions.get(ordinal - 1);
      if (attribution.requestOrdinal() != ordinal
          || !attribution.modelResolved().equals(
              step.resolvedModel())
          || attribution.totalTokens()
              != step.usage().tokenCount()
          || attribution.observedCostUsd().compareTo(
                  step.usage().costUsd())
              != 0) {
        throw new ProviderSessionRejected(
            "PROVIDER_OUTCOME_ATTRIBUTION_DRIFT");
      }
      return attribution;
    }

    private synchronized List<GraphProviderAttribution> snapshot(
        int ordinal) {
      if (ordinal != attributions.size()) {
        throw new ProviderSessionRejected(
            "PROVIDER_OUTCOME_LEDGER_SEQUENCE_DRIFT");
      }
      return List.copyOf(attributions);
    }
  }

  static final class ProviderSessionRejected extends RuntimeException {

    private ProviderSessionRejected(String code) {
      super(code, null, false, false);
    }
  }
}
