package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.application.GraphAttemptCoordinator;
import java.io.Console;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-console, one-revision-at-a-time owner gate for Pack010.
 *
 * <p>The production surface accepts only the three server-owned revision
 * names. It never accepts a Store, console, clock, approval, writer data
 * source, credential, client, or model. A rejected challenge burns the
 * already claimed execution slot.
 */
public final class OwnerTtyGraphAuthority {

  private final PostgresGraphAttemptStore store;
  private final PostgresGraphAttemptStore.AuthorityIdentity identity;
  private final Map<Pack010Revision, GraphAttemptManifest> manifests;
  private final Duration challengeTtl;
  private final Object owner = new Object();
  private final AtomicBoolean revisionSelected = new AtomicBoolean();
  private final GraphAttemptCoordinator coordinator;

  OwnerTtyGraphAuthority(
      PostgresGraphAttemptStore store,
      List<GraphAttemptManifest> serverOwnedManifests,
      Duration challengeTtl) {
    PostgresGraphAttemptStore supplied =
        Objects.requireNonNull(store, "store");
    this.identity = supplied.freezeAuthorityIdentity();
    this.store = supplied.bindAuthorityIdentity(identity);
    this.manifests = freezeExactCatalog(serverOwnedManifests);
    this.challengeTtl = requireChallengeTtl(challengeTtl);
    this.coordinator =
        GraphAttemptCoordinator.ownerAdoptionOnly(this.store);
  }

  public GraphAttemptCoordinator coordinator(ApprovedHandoff handoff) {
    Objects.requireNonNull(handoff, "handoff");
    if (handoff.owner != owner || handoff.coordinator != coordinator) {
      throw new IllegalStateException(
          "Pack010 coordinator handoff is invalid");
    }
    return coordinator;
  }

  public MutationPermit approve(Pack010Revision revision) {
    Objects.requireNonNull(revision, "revision");
    Console console = System.console();
    if (console == null) {
      throw new OwnerApprovalException("REAL_TTY_REQUIRED");
    }
    if (!revisionSelected.compareAndSet(false, true)) {
      throw new OwnerApprovalException(
          "PROCESS_REVISION_ALREADY_SELECTED");
    }

    GraphAttemptManifest manifest = manifests.get(revision);
    GraphAttemptManifest predecessor =
        revision == Pack010Revision.R1
            ? null
            : manifests.get(revision.predecessor());
    PostgresGraphAttemptStore.OwnerClaim claim =
        store.claimForOwner(
            manifest, predecessor, challengeTtl, identity);
    String challenge =
        io.emergeos.core.domain.GraphOperatorApproval
            .expectedChallenge(manifest);
    String response = console.readLine("%s%n", challenge);
    if (!challenge.equals(response)) {
      throw new OwnerApprovalException(
          "OPERATOR_CHALLENGE_MISMATCH");
    }
    GraphAttemptCursor approved =
        store.approveForOwner(manifest, claim, identity);
    return new MutationPermit(
        owner,
        revision,
        manifest,
        approved,
        claim.expiresAt(),
        coordinator);
  }

  public ApprovedHandoff adopt(MutationPermit permit) {
    Objects.requireNonNull(permit, "permit");
    if (permit.owner != owner
        || !permit.state.compareAndSet(
            PermitState.ISSUED, PermitState.ADOPTING)) {
      throw new IllegalStateException(
          "Pack010 mutation permit is invalid or already consumed");
    }
    store.requireOwnerPermitFresh(permit.expiresAt, identity);
    GraphAttemptCoordinator.Authorized authorized =
        permit.coordinator.adoptOwnerApproved(
            permit.manifest, permit.approved, permit);
    permit.state.set(PermitState.ADOPTED);
    return new ApprovedHandoff(
        owner,
        permit.revision,
        permit.manifest,
        permit.expiresAt,
        permit.coordinator,
        authorized);
  }

  public GraphAttemptCoordinator.Authorized takeAuthorized(
      ApprovedHandoff handoff,
      GraphAttemptCoordinator coordinator) {
    Objects.requireNonNull(handoff, "handoff");
    Objects.requireNonNull(coordinator, "coordinator");
    if (handoff.owner != owner
        || handoff.coordinator != coordinator
        || !handoff.authorizedTaken.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 coordinator handoff is invalid or already claimed");
    }
    return handoff.authorized;
  }

  public Pack010Revision consumeEgress(
      ApprovedHandoff handoff,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egressAuthority) {
    Objects.requireNonNull(handoff, "handoff");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    if (handoff.owner != owner
        || !handoff.authorizedTaken.get()
        || handoff.coordinator != coordinator) {
      throw new IllegalStateException(
          "Pack010 egress handoff does not belong to the coordinator");
    }
    store.requireOwnerPermitFresh(handoff.expiresAt, identity);
    coordinator.requireEgressManifest(
        egressAuthority, handoff.manifest);
    if (!handoff.consumedEgress.compareAndSet(null, egressAuthority)) {
      throw new IllegalStateException(
          "Pack010 egress handoff is invalid or already consumed");
    }
    return handoff.revision;
  }

  /**
   * Atomically persists the exact first provider-session intent before any
   * credential, client, model, or provider session may be constructed.
   */
  public ProviderSessionIntent claimProviderSessionIntent(
      ApprovedHandoff handoff,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision,
      GraphProviderIntent firstRequest) {
    Objects.requireNonNull(firstRequest, "firstRequest");
    Objects.requireNonNull(handoff, "handoff");
    if (handoff.owner != owner
        || !handoff.authorizedTaken.get()
        || handoff.coordinator != expectedCoordinator
        || handoff.revision != expectedRevision) {
      throw new IllegalStateException(
          "Pack010 provider session handoff identity does not match");
    }
    expectedCoordinator.requireEgressManifest(
        expectedEgress, handoff.manifest);
    if (!handoff.sessionIntentClaimed.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 provider session intent is already claimed");
    }
    PostgresGraphAttemptStore.ProviderSessionClaim claim =
        store.claimProviderSessionIntent(
            handoff.manifest,
            handoff.revision.name().toLowerCase(
                java.util.Locale.ROOT),
            firstRequest,
            handoff.expiresAt,
            identity);
    return new ProviderSessionIntent(
        owner,
        handoff.revision,
        handoff.manifest,
        handoff.expiresAt,
        expectedCoordinator,
        expectedEgress,
        firstRequest,
        claim.cursor(),
        claim.intentHash());
  }

  public Pack010Revision consumeProviderSessionIntent(
      ProviderSessionIntent intent,
      ApprovedHandoff handoff,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress) {
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(handoff, "handoff");
    if (intent.owner != owner
        || handoff.owner != owner
        || intent.revision != handoff.revision
        || !intent.manifest.equals(handoff.manifest)
        || intent.coordinator != expectedCoordinator
        || handoff.coordinator != expectedCoordinator
        || intent.egress != expectedEgress
        || !intent.consumed.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 provider session intent is invalid or consumed");
    }
    store.requireOwnerPermitFresh(intent.expiresAt, identity);
    return intent.revision;
  }

  /**
   * Revalidates the exact consumed provider-session capability against
   * PostgreSQL time immediately before a credential or provider factory
   * effect.
   */
  public java.time.Instant requireProviderSessionFresh(
      ProviderSessionIntent intent,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision) {
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(expectedCoordinator, "expectedCoordinator");
    Objects.requireNonNull(expectedEgress, "expectedEgress");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    if (intent.owner != owner
        || intent.coordinator != expectedCoordinator
        || intent.egress != expectedEgress
        || intent.revision != expectedRevision
        || !intent.consumed.get()) {
      throw new IllegalStateException(
          "Pack010 provider session capability is not exact or consumed");
    }
    store.requireOwnerPermitFresh(intent.expiresAt, identity);
    return intent.expiresAt;
  }

  /**
   * Claims the sole terminal capability for the owner-approved provider
   * execution after both provider attributions are durable.
   */
  public TerminalCapability claimTerminal(
      ApprovedHandoff handoff,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision) {
    requireTerminalHandoff(
        handoff,
        expectedCoordinator,
        expectedEgress,
        expectedRevision);
    expectedCoordinator.requireProviderAttributed(
        expectedEgress, handoff.manifest);
    store.requireOwnerPermitFresh(handoff.expiresAt, identity);
    if (!handoff.terminalClaimed.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 terminal handoff is invalid or already claimed");
    }
    return new TerminalCapability(
        owner,
        handoff.revision,
        handoff.manifest,
        handoff.expiresAt,
        expectedCoordinator,
        expectedEgress);
  }

  /** Binds one terminal capability to exactly one runtime composition. */
  public GraphAttemptManifest bindTerminal(
      TerminalCapability capability,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision) {
    requireTerminalCapability(
        capability,
        expectedCoordinator,
        expectedEgress,
        expectedRevision);
    expectedCoordinator.requireProviderAttributed(
        expectedEgress, capability.manifest);
    store.requireOwnerPermitFresh(capability.expiresAt, identity);
    if (!capability.bound.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 terminal capability is already bound");
    }
    return capability.manifest;
  }

  void requireRuntimeAuthority(
      PostgresGraphAttemptStore.AuthorityIdentity prefixIdentity,
      PostgresGraphTerminalExecutor.DatabaseIdentity runtimeIdentity) {
    Objects.requireNonNull(prefixIdentity, "prefixIdentity");
    Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
    if (!identity.equals(prefixIdentity)
        || !identity.sessionUser().equals(
            "emergeos_graph_prefix_writer")
        || !identity.currentUser().equals(
            "emergeos_graph_prefix_writer")
        || !identity.database().equals(runtimeIdentity.database())
        || !identity.databaseOid().equals(runtimeIdentity.databaseOid())
        || !identity.serverAddress().equals(
            runtimeIdentity.serverAddress())
        || identity.serverPort() != runtimeIdentity.serverPort()) {
      throw new io.emergeos.core.domain.GraphAttemptIntegrityException();
    }
  }

  public ChildTerminalClaim claimChildTerminal(
      TerminalCapability capability,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress) {
    requireBoundTerminalCapability(
        capability, expectedCoordinator, expectedEgress);
    store.requireOwnerPermitFresh(capability.expiresAt, identity);
    if (!capability.state.compareAndSet(
        TerminalState.READY, TerminalState.CHILD_CLAIMED)) {
      throw new IllegalStateException(
          "Pack010 child terminal capability is out of sequence");
    }
    return new ChildTerminalClaim(
        owner, capability.manifest, capability.expiresAt);
  }

  public ParentTerminalClaim claimParentTerminal(
      TerminalCapability capability,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress) {
    requireBoundTerminalCapability(
        capability, expectedCoordinator, expectedEgress);
    store.requireOwnerPermitFresh(capability.expiresAt, identity);
    GraphAttemptVerification verification =
        store.findVerified(capability.manifest);
    if (!(verification
            instanceof GraphAttemptVerification.Valid valid)
        || valid.snapshot().cursor().lastSequence() != 15
        || valid.snapshot().cursor().phase()
            != GraphAttemptPhase.CHILD_TERMINAL) {
      throw new IllegalStateException(
          "Pack010 parent terminal claim requires durable child truth");
    }
    GraphAttemptCursor durableChild = valid.snapshot().cursor();
    if (!capability.state.compareAndSet(
        TerminalState.CHILD_CLAIMED,
        TerminalState.PARENT_CLAIMED)) {
      throw new IllegalStateException(
          "Pack010 parent terminal capability is out of sequence");
    }
    return new ParentTerminalClaim(
        owner,
        capability.revision,
        capability.manifest,
        capability.expiresAt,
        expectedCoordinator,
        expectedEgress,
        durableChild);
  }

  java.time.Instant consumeChildTerminalClaim(
      ChildTerminalClaim claim, GraphAttemptManifest expectedManifest) {
    return requireTerminalClaim(
        claim.owner,
        claim.manifest,
        claim.expiresAt,
        claim.consumed,
        expectedManifest,
        "child");
  }

  java.time.Instant consumeParentTerminalClaim(
      ParentTerminalClaim claim,
      GraphAttemptManifest expectedManifest) {
    Objects.requireNonNull(claim, "claim");
    GraphAttemptVerification verification =
        store.findVerified(claim.manifest);
    if (!(verification
            instanceof GraphAttemptVerification.Valid valid)
        || !claim.durableChild.equals(valid.snapshot().cursor())
        || claim.revision
            != Pack010Revision.values()[
                expectedManifest.experiment().repetition() - 1]
        || claim.coordinator == null
        || claim.egress == null) {
      throw new IllegalStateException(
          "Pack010 parent terminal claim does not match durable child truth");
    }
    return requireTerminalClaim(
        claim.owner,
        claim.manifest,
        claim.expiresAt,
        claim.consumed,
        expectedManifest,
        "parent");
  }

  private void requireTerminalHandoff(
      ApprovedHandoff handoff,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision) {
    Objects.requireNonNull(handoff, "handoff");
    Objects.requireNonNull(expectedCoordinator, "expectedCoordinator");
    Objects.requireNonNull(expectedEgress, "expectedEgress");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    if (handoff.owner != owner
        || handoff.coordinator != expectedCoordinator
        || handoff.revision != expectedRevision
        || handoff.consumedEgress.get() != expectedEgress) {
      throw new IllegalStateException(
          "Pack010 terminal handoff identity does not match");
    }
  }

  private void requireTerminalCapability(
      TerminalCapability capability,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress,
      Pack010Revision expectedRevision) {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(expectedCoordinator, "expectedCoordinator");
    Objects.requireNonNull(expectedEgress, "expectedEgress");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    if (capability.owner != owner
        || capability.coordinator != expectedCoordinator
        || capability.egress != expectedEgress
        || capability.revision != expectedRevision) {
      throw new IllegalStateException(
          "Pack010 terminal capability identity does not match");
    }
  }

  private void requireBoundTerminalCapability(
      TerminalCapability capability,
      GraphAttemptCoordinator expectedCoordinator,
      GraphAttemptCoordinator.EgressAuthority expectedEgress) {
    Objects.requireNonNull(capability, "capability");
    if (capability.owner != owner
        || capability.coordinator != expectedCoordinator
        || capability.egress != expectedEgress
        || !capability.bound.get()) {
      throw new IllegalStateException(
          "Pack010 terminal capability is not bound");
    }
  }

  private java.time.Instant requireTerminalClaim(
      Object claimOwner,
      GraphAttemptManifest claimManifest,
      java.time.Instant claimExpiresAt,
      AtomicBoolean consumed,
      GraphAttemptManifest expectedManifest,
      String phase) {
    Objects.requireNonNull(expectedManifest, "expectedManifest");
    if (claimOwner != owner
        || !claimManifest.equals(expectedManifest)
        || !consumed.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "Pack010 " + phase + " terminal claim is invalid or consumed");
    }
    return Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
  }

  private static Map<Pack010Revision, GraphAttemptManifest>
      freezeExactCatalog(
          List<GraphAttemptManifest> serverOwnedManifests) {
    Objects.requireNonNull(
        serverOwnedManifests, "serverOwnedManifests");
    if (serverOwnedManifests.size() != Pack010Revision.values().length) {
      throw new IllegalArgumentException(
          "Pack010 authority requires exactly r1, r2, and r3");
    }
    EnumMap<Pack010Revision, GraphAttemptManifest> frozen =
        new EnumMap<>(Pack010Revision.class);
    Set<String> runIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();
    Set<String> artifactIds = new HashSet<>();
    Set<String> attemptIds = new HashSet<>();
    Set<String> caseIds = new HashSet<>();
    for (GraphAttemptManifest manifest : serverOwnedManifests) {
      Objects.requireNonNull(manifest, "serverOwned manifest");
      int repetition = manifest.experiment().repetition();
      if (repetition < 1 || repetition > 3) {
        throw new IllegalArgumentException(
            "Pack010 catalog repetition is not exact");
      }
      Pack010Revision revision = Pack010Revision.values()[repetition - 1];
      if (!manifest.executionSlotId().equals(revision.executionSlotId())
          || frozen.put(revision, manifest) != null
          || !runIds.add(manifest.parentSelection().runId())
          || !runIds.add(manifest.childSelection().runId())
          || !taskIds.add(manifest.parentSelection().taskId())
          || !taskIds.add(manifest.childSelection().taskId())
          || !artifactIds.add(manifest.artifactId())
          || !attemptIds.add(manifest.attemptId())
          || !caseIds.add(manifest.caseId())) {
        throw new IllegalArgumentException(
            "Pack010 catalog identities are not exact and unique");
      }
    }
    GraphAttemptManifest r1 = frozen.get(Pack010Revision.R1);
    for (Pack010Revision revision : Pack010Revision.values()) {
      GraphAttemptManifest manifest = frozen.get(revision);
      if (manifest == null
          || !manifest.schemaVersion().equals(r1.schemaVersion())
          || !manifest.graphProtocolVersion().equals(
              r1.graphProtocolVersion())
          || !manifest.principalId().equals(r1.principalId())
          || !manifest.packRawSha256().equals(r1.packRawSha256())
          || !manifest.environmentRawSha256().equals(
              r1.environmentRawSha256())
          || !manifest.captureId().equals(r1.captureId())
          || !manifest.captureRequestHash().equals(
              r1.captureRequestHash())
          || !manifest.pricingProfileFingerprint().equals(
              r1.pricingProfileFingerprint())
          || !manifest.promptSurfaceFingerprint().equals(
              r1.promptSurfaceFingerprint())
          || !manifest.conductorSurfaceFingerprint().equals(
              r1.conductorSurfaceFingerprint())
          || manifest.reservationUsd().compareTo(r1.reservationUsd())
              != 0
          || !manifest.parentActor().equals(r1.parentActor())
          || !manifest.childActor().equals(r1.childActor())
          || !manifest.experiment().arm().equals(
              r1.experiment().arm())
          || !manifest.integrityProfile().equals(
              r1.integrityProfile())
          || manifest.maximumProviderRequests()
              != r1.maximumProviderRequests()
          || !sameSelectionSurface(
              manifest.parentSelection(), r1.parentSelection())
          || !sameSelectionSurface(
              manifest.childSelection(), r1.childSelection())
          || (revision != Pack010Revision.R1
              && !manifest.startedAt().isAfter(
                  frozen.get(revision.predecessor()).startedAt()))) {
        throw new IllegalArgumentException(
            "Pack010 catalog identities do not form one exact chain");
      }
    }
    return Map.copyOf(frozen);
  }

  private static boolean sameSelectionSurface(
      io.emergeos.core.domain.GraphRunSelection actual,
      io.emergeos.core.domain.GraphRunSelection first) {
    return actual.role() == first.role()
        && actual.executionProfileId().equals(
            first.executionProfileId())
        && actual.workerRegistryVersion().equals(
            first.workerRegistryVersion())
        && actual.workerProfileId().equals(
            first.workerProfileId());
  }

  private static Duration requireChallengeTtl(Duration challengeTtl) {
    Objects.requireNonNull(challengeTtl, "challengeTtl");
    if (challengeTtl.isZero()
        || challengeTtl.isNegative()
        || challengeTtl.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException(
          "owner challenge TTL must be within (0, 5 minutes]");
    }
    return challengeTtl;
  }

  public enum Pack010Revision {
    R1("pack010-r1"),
    R2("pack010-r2"),
    R3("pack010-r3");

    private final String executionSlotId;

    Pack010Revision(String executionSlotId) {
      this.executionSlotId = executionSlotId;
    }

    private String executionSlotId() {
      return executionSlotId;
    }

    private Pack010Revision predecessor() {
      return values()[ordinal() - 1];
    }
  }

  public static final class MutationPermit {

    private final Object owner;
    private final Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor approved;
    private final java.time.Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final java.util.concurrent.atomic.AtomicReference<PermitState>
        state =
            new java.util.concurrent.atomic.AtomicReference<>(
                PermitState.ISSUED);

    private MutationPermit(
        Object owner,
        Pack010Revision revision,
        GraphAttemptManifest manifest,
        GraphAttemptCursor approved,
        java.time.Instant expiresAt,
        GraphAttemptCoordinator coordinator) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.approved = Objects.requireNonNull(approved, "approved");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator =
          Objects.requireNonNull(coordinator, "coordinator");
    }
  }

  public static final class ApprovedHandoff {

    private final Object owner;
    private final Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final java.time.Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.Authorized authorized;
    private final AtomicBoolean authorizedTaken = new AtomicBoolean();
    private final AtomicReference<GraphAttemptCoordinator.EgressAuthority>
        consumedEgress = new AtomicReference<>();
    private final AtomicBoolean sessionIntentClaimed = new AtomicBoolean();
    private final AtomicBoolean terminalClaimed = new AtomicBoolean();

    private ApprovedHandoff(
        Object owner,
        Pack010Revision revision,
        GraphAttemptManifest manifest,
        java.time.Instant expiresAt,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.Authorized authorized) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator =
          Objects.requireNonNull(coordinator, "coordinator");
      this.authorized = Objects.requireNonNull(authorized, "authorized");
    }
  }

  public static final class TerminalCapability {

    private final Object owner;
    private final Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final java.time.Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final AtomicBoolean bound = new AtomicBoolean();
    private final AtomicReference<TerminalState> state =
        new AtomicReference<>(TerminalState.READY);

    private TerminalCapability(
        Object owner,
        Pack010Revision revision,
        GraphAttemptManifest manifest,
        java.time.Instant expiresAt,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
    }
  }

  public static final class ProviderSessionIntent {

    private final Object owner;
    private final Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final java.time.Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final GraphProviderIntent firstRequest;
    private final GraphAttemptCursor durableCursor;
    private final String intentHash;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ProviderSessionIntent(
        Object owner,
        Pack010Revision revision,
        GraphAttemptManifest manifest,
        java.time.Instant expiresAt,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        GraphProviderIntent firstRequest,
        GraphAttemptCursor durableCursor,
        String intentHash) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
      this.firstRequest =
          Objects.requireNonNull(firstRequest, "firstRequest");
      this.durableCursor =
          Objects.requireNonNull(durableCursor, "durableCursor");
      this.intentHash = Objects.requireNonNull(intentHash, "intentHash");
    }
  }

  public static final class ChildTerminalClaim {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final java.time.Instant expiresAt;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ChildTerminalClaim(
        Object owner,
        GraphAttemptManifest manifest,
        java.time.Instant expiresAt) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  public static final class ParentTerminalClaim {

    private final Object owner;
    private final Pack010Revision revision;
    private final GraphAttemptManifest manifest;
    private final java.time.Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority egress;
    private final GraphAttemptCursor durableChild;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private ParentTerminalClaim(
        Object owner,
        Pack010Revision revision,
        GraphAttemptManifest manifest,
        java.time.Instant expiresAt,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egress,
        GraphAttemptCursor durableChild) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.revision = Objects.requireNonNull(revision, "revision");
      this.manifest = Objects.requireNonNull(manifest, "manifest");
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egress = Objects.requireNonNull(egress, "egress");
      this.durableChild =
          Objects.requireNonNull(durableChild, "durableChild");
    }
  }

  private enum TerminalState {
    READY,
    CHILD_CLAIMED,
    PARENT_CLAIMED
  }

  private enum PermitState {
    ISSUED,
    ADOPTING,
    ADOPTED
  }

  public static final class OwnerApprovalException
      extends RuntimeException {

    private OwnerApprovalException(String message) {
      super(message, null, false, false);
    }
  }
}
