package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.PostgresGraphRuntimeWriters;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphProviderAttribution;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Dormant shipping composition of the Pack010 prefix and V9 terminal roles.
 *
 * <p>No shipping CLI route constructs this class. If enabled by a later Gate,
 * complete provider attribution must already be durable before either
 * terminal semantic transaction is entered. Terminal JSON is produced only
 * inside the PostgreSQL adapter from typed terminal truth.
 */
final class Pack010PostgresRuntimeComposition {

  private final PostgresGraphRuntimeWriters writers;
  private final OwnerTtyGraphAuthority ownerAuthority;
  private final OwnerTtyGraphAuthority.TerminalCapability
      terminalCapability;
  private final GraphAttemptCoordinator coordinator;
  private final GraphAttemptCoordinator.EgressAuthority egress;
  private final GraphAttemptManifest manifest;
  private final OwnerTtyGraphAuthority.Pack010Revision revision;
  private final Object runtimeOwner = new Object();

  private Pack010PostgresRuntimeComposition(
      PostgresGraphRuntimeWriters writers,
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.TerminalCapability terminalCapability,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egress,
      GraphAttemptManifest manifest,
      OwnerTtyGraphAuthority.Pack010Revision revision) {
    this.writers = Objects.requireNonNull(writers, "writers");
    this.ownerAuthority =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority");
    this.terminalCapability =
        Objects.requireNonNull(
            terminalCapability, "terminalCapability");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.egress = Objects.requireNonNull(egress, "egress");
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    this.revision = Objects.requireNonNull(revision, "revision");
  }

  static Pack010PostgresRuntimeComposition open(
      DataSource prefixDataSource,
      DataSource terminalDataSource,
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.TerminalCapability terminalCapability,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egress,
      OwnerTtyGraphAuthority.Pack010Revision revision) {
    OwnerTtyGraphAuthority exactOwner =
        Objects.requireNonNull(ownerAuthority, "ownerAuthority");
    PostgresGraphRuntimeWriters writers =
        PostgresGraphRuntimeWriters.open(
            prefixDataSource, terminalDataSource, exactOwner);
    GraphAttemptManifest manifest =
        exactOwner.bindTerminal(
            terminalCapability,
            coordinator,
            egress,
            revision);
    return new Pack010PostgresRuntimeComposition(
        writers,
        exactOwner,
        terminalCapability,
        coordinator,
        egress,
        manifest,
        revision);
  }

  Pack010ChildTerminalCommand prepareChild(
      Pack010ProviderSessionComposer.Pack010AttributedModelOutcome outcome,
      HarnessCandidateEnvelope candidate,
      AgentRun terminalChild,
      WorkerResultEnvelope workerResult) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    Pack010ProviderSessionComposer.StructuredFinalBinding reviewed =
        Pack010ProviderSessionComposer.reviewStructuredFinal(
            outcome,
            coordinator,
            egress,
            revision,
            manifest,
            candidate);
    if (!sameAttributions(
        reviewed.attributions(), snapshot.providerAttributions())) {
      throw new IllegalStateException(
          "Pack010 structured final attribution drift");
    }
    PostgresGraphRuntimeWriters.ChildTerminalTransition transition =
        writers.prepareChild(
            manifest,
            Objects.requireNonNull(terminalChild, "terminalChild"),
            candidate,
            Objects.requireNonNull(workerResult, "workerResult"));
    Pack010ProviderSessionComposer.claimStructuredFinal(
        outcome,
        coordinator,
        egress,
        revision,
        manifest,
        candidate);
    return new Pack010ChildTerminalCommand(
        runtimeOwner,
        manifest,
        snapshot.cursor(),
        snapshot.providerAttributions(),
        candidate.integrityHash(),
        terminalChild.bundle().integrityHash(),
        workerResult.integrityHash(),
        transition);
  }

  String completeChild(Pack010ChildTerminalCommand command) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    PostgresGraphRuntimeWriters.ChildTerminalTransition transition =
        Objects.requireNonNull(command, "command")
            .consume(runtimeOwner, manifest, snapshot);
    OwnerTtyGraphAuthority.ChildTerminalClaim claim =
        ownerAuthority.claimChildTerminal(
            terminalCapability, coordinator, egress);
    return writers.completeChild(
        ownerAuthority, claim, manifest, transition);
  }

  Pack010ParentTerminalCommand prepareParentAndSeal(
      AgentRun terminalParent, ArtifactLineage artifact) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 15, GraphAttemptPhase.CHILD_TERMINAL);
    PostgresGraphRuntimeWriters.ParentTerminalTransition transition =
        writers.prepareParentAndSeal(
            manifest,
            Objects.requireNonNull(terminalParent, "terminalParent"),
            Objects.requireNonNull(artifact, "artifact"));
    return new Pack010ParentTerminalCommand(
        runtimeOwner,
        terminalCapability,
        coordinator,
        egress,
        revision,
        manifest,
        snapshot.cursor(),
        snapshot.providerAttributions().stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList(),
        snapshot.candidate().integrityHash(),
        snapshot.workerResult().integrityHash(),
        snapshot.terminalBindings().getFirst().terminalHash(),
        terminalParent.bundle().integrityHash(),
        artifact.current().contentHash(),
        transition);
  }

  String completeParentAndSeal(Pack010ParentTerminalCommand command) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 15, GraphAttemptPhase.CHILD_TERMINAL);
    PostgresGraphRuntimeWriters.ParentTerminalTransition transition =
        Objects.requireNonNull(command, "command")
            .consume(
                runtimeOwner,
                terminalCapability,
                coordinator,
                egress,
                revision,
                manifest,
                snapshot);
    OwnerTtyGraphAuthority.ParentTerminalClaim claim =
        ownerAuthority.claimParentTerminal(
            terminalCapability, coordinator, egress);
    return writers.completeParentAndSeal(
        ownerAuthority, claim, manifest, transition);
  }

  Pack010FailedChildTerminalCommand
      preparePreCandidateFailureChild(
          Pack010ProviderSessionComposer.Pack010AttributedModelOutcome
              outcome,
          AgentRun terminalChild) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    Pack010ProviderSessionComposer.AttributedPreCandidateFailure
        reviewed =
            Pack010ProviderSessionComposer
                .reviewAttributedPreCandidateFailure(
                    outcome,
                    coordinator,
                    egress,
                    revision,
                    manifest);
    if (!sameAttributions(
        reviewed.attributions(), snapshot.providerAttributions())) {
      throw new IllegalStateException(
          "Pack010 attributed failure attribution drift");
    }
    AgentRun exactTerminal =
        Objects.requireNonNull(terminalChild, "terminalChild");
    reviewed.requireTerminal(exactTerminal);
    PostgresGraphRuntimeWriters.ChildFailureTransition transition =
        writers.preparePreCandidateFailureChild(
            manifest, exactTerminal);
    Pack010ProviderSessionComposer.claimAttributedPreCandidateFailure(
        outcome,
        coordinator,
        egress,
        revision,
        manifest);
    return new Pack010FailedChildTerminalCommand(
        runtimeOwner,
        manifest,
        snapshot.cursor(),
        snapshot.providerAttributions().stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList(),
        reviewed.provenanceHash(),
        exactTerminal.bundle().integrityHash(),
        transition);
  }

  String completePreCandidateFailureChild(
      Pack010FailedChildTerminalCommand command) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 14, GraphAttemptPhase.PROVIDER_ATTRIBUTED);
    PostgresGraphRuntimeWriters.ChildFailureTransition transition =
        Objects.requireNonNull(command, "command")
            .consume(runtimeOwner, manifest, snapshot);
    OwnerTtyGraphAuthority.ChildTerminalClaim claim =
        ownerAuthority.claimChildTerminal(
            terminalCapability, coordinator, egress);
    return writers.completePreCandidateFailureChild(
        ownerAuthority, claim, manifest, transition);
  }

  Pack010FailedParentTerminalCommand
      preparePreCandidateFailureParentAndSeal(
          AgentRun terminalParent) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 15, GraphAttemptPhase.CHILD_TERMINAL);
    if (snapshot.candidate() != null
        || snapshot.workerResult() != null
        || snapshot.childRun() == null
        || snapshot.childRun().result().status()
            == io.emergeos.contracts.RunStatus.SUCCEEDED) {
      throw new IllegalStateException(
          "Pack010 pre-Candidate failed child truth drift");
    }
    AgentRun exactParent =
        Objects.requireNonNull(terminalParent, "terminalParent");
    PostgresGraphRuntimeWriters.ParentFailureTransition transition =
        writers.preparePreCandidateFailureParentAndSeal(
            manifest, exactParent);
    return new Pack010FailedParentTerminalCommand(
        runtimeOwner,
        terminalCapability,
        coordinator,
        egress,
        revision,
        manifest,
        snapshot.cursor(),
        snapshot.providerAttributions().stream()
            .map(GraphProviderAttribution::attributionHash)
            .toList(),
        snapshot.terminalBindings().getFirst().terminalHash(),
        exactParent.bundle().integrityHash(),
        transition);
  }

  String completePreCandidateFailureParentAndSeal(
      Pack010FailedParentTerminalCommand command) {
    GraphAttemptSnapshot snapshot =
        requireCheckpoint(
            manifest, 15, GraphAttemptPhase.CHILD_TERMINAL);
    PostgresGraphRuntimeWriters.ParentFailureTransition transition =
        Objects.requireNonNull(command, "command")
            .consume(
                runtimeOwner,
                terminalCapability,
                coordinator,
                egress,
                revision,
                manifest,
                snapshot);
    OwnerTtyGraphAuthority.ParentTerminalClaim claim =
        ownerAuthority.claimParentTerminal(
            terminalCapability, coordinator, egress);
    return writers.completePreCandidateFailureParentAndSeal(
        ownerAuthority, claim, manifest, transition);
  }

  private GraphAttemptSnapshot requireCheckpoint(
      GraphAttemptManifest exactManifest,
      int sequence,
      GraphAttemptPhase phase) {
    GraphAttemptVerification verification =
        writers.findVerified(
            Objects.requireNonNull(exactManifest, "manifest"));
    if (!(verification instanceof GraphAttemptVerification.Valid valid)) {
      throw new IllegalStateException(
          "Pack010 terminal prefix is not valid");
    }
    GraphAttemptSnapshot snapshot = valid.snapshot();
    if (snapshot.cursor().lastSequence() != sequence
        || snapshot.cursor().phase() != phase
        || snapshot.providerAttributions().size()
            != exactManifest.maximumProviderRequests()) {
      throw new IllegalStateException(
          "Pack010 terminal prefix is incomplete");
    }
    for (int index = 0;
        index < snapshot.providerAttributions().size();
        index++) {
      GraphProviderAttribution attribution =
          snapshot.providerAttributions().get(index);
      if (attribution.requestOrdinal() != index + 1
          || !attribution.providerActor().equals(
              exactManifest.childActor())
          || !attribution.pricingProfileFingerprint().equals(
              exactManifest.pricingProfileFingerprint())
          || attribution.responseHash().length() != 64
          || attribution.cachedInputTokens()
              > attribution.inputTokens()
          || attribution.reasoningOutputTokens()
              > attribution.outputTokens()
          || Math.addExact(
                  attribution.inputTokens(), attribution.outputTokens())
              != attribution.totalTokens()) {
        throw new IllegalStateException(
            "Pack010 provider attribution is incomplete");
      }
    }
    return snapshot;
  }

  private static boolean sameAttributions(
      List<GraphProviderAttribution> observed,
      List<GraphProviderAttribution> durable) {
    if (observed.size() != durable.size()) {
      return false;
    }
    for (int index = 0; index < observed.size(); index++) {
      if (!observed
          .get(index)
          .attributionHash()
          .equals(durable.get(index).attributionHash())) {
        return false;
      }
    }
    return true;
  }

  static final class Pack010ParentTerminalCommand {

    private final Object owner;
    private final OwnerTtyGraphAuthority.TerminalCapability
        terminalCapability;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final OwnerTtyGraphAuthority.Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final List<String> attributionHashes;
    private final String candidateIntegrityHash;
    private final String workerResultIntegrityHash;
    private final String childTerminalHash;
    private final String parentBundleHash;
    private final String artifactHash;
    private final PostgresGraphRuntimeWriters.ParentTerminalTransition
        transition;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Pack010ParentTerminalCommand(
        Object owner,
        OwnerTtyGraphAuthority.TerminalCapability terminalCapability,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        OwnerTtyGraphAuthority.Pack010Revision revision,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        List<String> attributionHashes,
        String candidateIntegrityHash,
        String workerResultIntegrityHash,
        String childTerminalHash,
        String parentBundleHash,
        String artifactHash,
        PostgresGraphRuntimeWriters.ParentTerminalTransition transition) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.terminalCapability =
          Objects.requireNonNull(
              terminalCapability, "terminalCapability");
      this.coordinator =
          Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.attributionHashes = List.copyOf(attributionHashes);
      this.candidateIntegrityHash =
          Objects.requireNonNull(
              candidateIntegrityHash, "candidateIntegrityHash");
      this.workerResultIntegrityHash =
          Objects.requireNonNull(
              workerResultIntegrityHash, "workerResultIntegrityHash");
      this.childTerminalHash =
          Objects.requireNonNull(childTerminalHash, "childTerminalHash");
      this.parentBundleHash =
          Objects.requireNonNull(parentBundleHash, "parentBundleHash");
      this.artifactHash = Objects.requireNonNull(artifactHash, "artifactHash");
      this.transition = Objects.requireNonNull(transition, "transition");
    }

    private PostgresGraphRuntimeWriters.ParentTerminalTransition consume(
        Object expectedOwner,
        OwnerTtyGraphAuthority.TerminalCapability expectedCapability,
        GraphAttemptCoordinator expectedCoordinator,
        GraphAttemptCoordinator.EgressAuthority expectedEgress,
        OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot snapshot) {
      List<String> durableAttributions =
          snapshot.providerAttributions().stream()
              .map(GraphProviderAttribution::attributionHash)
              .toList();
      if (owner != expectedOwner
          || terminalCapability != expectedCapability
          || coordinator != expectedCoordinator
          || egress != expectedEgress
          || revision != expectedRevision
          || !manifest.equals(expectedManifest)
          || !cursor.equals(snapshot.cursor())
          || !attributionHashes.equals(durableAttributions)
          || snapshot.candidate() == null
          || !candidateIntegrityHash.equals(
              snapshot.candidate().integrityHash())
          || snapshot.workerResult() == null
          || !workerResultIntegrityHash.equals(
              snapshot.workerResult().integrityHash())
          || snapshot.terminalBindings().size() != 1
          || !childTerminalHash.equals(
              snapshot.terminalBindings().getFirst().terminalHash())
          || snapshot.artifact() != null
          || snapshot.terminalSeal() != null
          || parentBundleHash.isBlank()
          || artifactHash.isBlank()
          || !consumed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "Pack010 parent terminal command is invalid or consumed");
      }
      return transition;
    }
  }

  static final class Pack010ChildTerminalCommand {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final List<GraphProviderAttribution> attributions;
    private final String candidateIntegrityHash;
    private final String childBundleHash;
    private final String workerResultIntegrityHash;
    private final PostgresGraphRuntimeWriters.ChildTerminalTransition
        transition;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Pack010ChildTerminalCommand(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        List<GraphProviderAttribution> attributions,
        String candidateIntegrityHash,
        String childBundleHash,
        String workerResultIntegrityHash,
        PostgresGraphRuntimeWriters.ChildTerminalTransition transition) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.attributions = List.copyOf(attributions);
      this.candidateIntegrityHash =
          Objects.requireNonNull(
              candidateIntegrityHash, "candidateIntegrityHash");
      this.childBundleHash =
          Objects.requireNonNull(childBundleHash, "childBundleHash");
      this.workerResultIntegrityHash =
          Objects.requireNonNull(
              workerResultIntegrityHash, "workerResultIntegrityHash");
      this.transition = Objects.requireNonNull(transition, "transition");
    }

    private PostgresGraphRuntimeWriters.ChildTerminalTransition consume(
        Object expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot snapshot) {
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(snapshot.cursor())
          || !attributions.equals(snapshot.providerAttributions())
          || snapshot.candidate() != null
          || snapshot.workerResult() != null
          || candidateIntegrityHash.length() != 64
          || childBundleHash.length() != 64
          || workerResultIntegrityHash.length() != 64
          || !consumed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "Pack010 child terminal command is invalid or consumed");
      }
      return transition;
    }
  }

  static final class Pack010FailedChildTerminalCommand {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final List<String> attributionHashes;
    private final String failureProvenanceHash;
    private final String childBundleHash;
    private final PostgresGraphRuntimeWriters.ChildFailureTransition
        transition;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Pack010FailedChildTerminalCommand(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        List<String> attributionHashes,
        String failureProvenanceHash,
        String childBundleHash,
        PostgresGraphRuntimeWriters.ChildFailureTransition transition) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.attributionHashes = List.copyOf(attributionHashes);
      this.failureProvenanceHash =
          Objects.requireNonNull(
              failureProvenanceHash, "failureProvenanceHash");
      this.childBundleHash =
          Objects.requireNonNull(childBundleHash, "childBundleHash");
      this.transition = Objects.requireNonNull(transition, "transition");
    }

    private PostgresGraphRuntimeWriters.ChildFailureTransition consume(
        Object expectedOwner,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot snapshot) {
      List<String> durableAttributions =
          snapshot.providerAttributions().stream()
              .map(GraphProviderAttribution::attributionHash)
              .toList();
      if (owner != expectedOwner
          || !manifest.equals(expectedManifest)
          || !cursor.equals(snapshot.cursor())
          || !attributionHashes.equals(durableAttributions)
          || snapshot.candidate() != null
          || snapshot.workerResult() != null
          || failureProvenanceHash.length() != 64
          || childBundleHash.length() != 64
          || !consumed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "Pack010 failed child command is invalid or consumed");
      }
      return transition;
    }
  }

  static final class Pack010FailedParentTerminalCommand {

    private final Object owner;
    private final OwnerTtyGraphAuthority.TerminalCapability
        terminalCapability;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final OwnerTtyGraphAuthority.Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final List<String> attributionHashes;
    private final String childTerminalHash;
    private final String parentBundleHash;
    private final PostgresGraphRuntimeWriters.ParentFailureTransition
        transition;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Pack010FailedParentTerminalCommand(
        Object owner,
        OwnerTtyGraphAuthority.TerminalCapability terminalCapability,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        OwnerTtyGraphAuthority.Pack010Revision revision,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        List<String> attributionHashes,
        String childTerminalHash,
        String parentBundleHash,
        PostgresGraphRuntimeWriters.ParentFailureTransition transition) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.terminalCapability =
          Objects.requireNonNull(
              terminalCapability, "terminalCapability");
      this.coordinator =
          Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.cursor = Objects.requireNonNull(cursor, "cursor");
      this.attributionHashes = List.copyOf(attributionHashes);
      this.childTerminalHash =
          Objects.requireNonNull(childTerminalHash, "childTerminalHash");
      this.parentBundleHash =
          Objects.requireNonNull(parentBundleHash, "parentBundleHash");
      this.transition = Objects.requireNonNull(transition, "transition");
    }

    private PostgresGraphRuntimeWriters.ParentFailureTransition consume(
        Object expectedOwner,
        OwnerTtyGraphAuthority.TerminalCapability expectedCapability,
        GraphAttemptCoordinator expectedCoordinator,
        GraphAttemptCoordinator.EgressAuthority expectedEgress,
        OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
        GraphAttemptManifest expectedManifest,
        GraphAttemptSnapshot snapshot) {
      List<String> durableAttributions =
          snapshot.providerAttributions().stream()
              .map(GraphProviderAttribution::attributionHash)
              .toList();
      if (owner != expectedOwner
          || terminalCapability != expectedCapability
          || coordinator != expectedCoordinator
          || egress != expectedEgress
          || revision != expectedRevision
          || !manifest.equals(expectedManifest)
          || !cursor.equals(snapshot.cursor())
          || !attributionHashes.equals(durableAttributions)
          || snapshot.candidate() != null
          || snapshot.workerResult() != null
          || snapshot.terminalBindings().size() != 1
          || !childTerminalHash.equals(
              snapshot.terminalBindings().getFirst().terminalHash())
          || snapshot.artifact() != null
          || snapshot.terminalSeal() != null
          || parentBundleHash.length() != 64
          || !consumed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "Pack010 failed parent command is invalid or consumed");
      }
      return transition;
    }
  }
}
