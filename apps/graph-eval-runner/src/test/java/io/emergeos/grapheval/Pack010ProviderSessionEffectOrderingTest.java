package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.GraphAttemptStore;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pack010ProviderSessionEffectOrderingTest {

  private static final String SYNTHETIC_CREDENTIAL =
      "synthetic-loopback-only-not-a-real-key";

  @Test
  void exactComposerBuildsNoRequestSessionOnlyAfterDurableMarkers()
      throws Exception {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        consumedEgress(coordinator, manifest, 1);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    Pack010ProviderCredentialBroker.CredentialLease lease =
        syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            coordinator,
            egress);

    Pack010ProviderSessionComposer.ProviderSession session =
        new Pack010ProviderSessionComposer()
            .compose(
                preflight(),
                lease,
                coordinator,
                egress,
                Clock.fixed(
                    manifest.startedAt().plusMillis(7),
                    ZoneOffset.UTC));
    try {
      assertEquals(
          OwnerTtyGraphAuthority.Pack010Revision.R1,
          session.revision());
      assertEquals(
          List.of(
              GraphAttemptEventType.ATTEMPT_CLAIMED,
              GraphAttemptEventType.OPERATOR_APPROVED,
              GraphAttemptEventType.PARENT_AUTHORIZED,
              GraphAttemptEventType.PARENT_STARTED,
              GraphAttemptEventType.CHILD_AUTHORIZED,
              GraphAttemptEventType.CHILD_STARTED,
              GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
              GraphAttemptEventType.CREDENTIAL_READ_STARTED,
              GraphAttemptEventType.CLIENT_CREATED,
              GraphAttemptEventType.MODEL_CREATED),
          store.events);
      assertEquals(0, store.providerIntents);
      assertThrows(
          Pack010ProviderCredentialBroker.CredentialRejected.class,
          () -> lease.claim(
              coordinator,
              egress,
              Clock.fixed(
                  manifest.startedAt().plusMillis(7),
                  ZoneOffset.UTC)));
    } finally {
      session.close();
    }
  }

  @Test
  void revisionMismatchFailsBeforeCredentialClaimClientAndModel()
      throws Exception {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        consumedEgress(coordinator, manifest, 1);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    Pack010ProviderCredentialBroker.CredentialLease wrongRevision =
        syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R2,
            coordinator,
            egress);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Pack010ProviderSessionComposer()
                .compose(
                    preflight(),
                    wrongRevision,
                    coordinator,
                    egress,
                    Clock.fixed(
                        manifest.startedAt().plusMillis(7),
                        ZoneOffset.UTC)));

    assertEquals(
        SYNTHETIC_CREDENTIAL,
        wrongRevision.claim(
            coordinator,
            egress,
            Clock.fixed(
                manifest.startedAt().plusMillis(7),
                ZoneOffset.UTC)));
    assertEquals(0, store.clients);
    assertEquals(0, store.models);
    assertEquals(0, store.providerIntents);
  }

  @Test
  void credentialLeaseCannotCrossCoordinatorOrEgressAuthority()
      throws Exception {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    RecordingStore firstStore = new RecordingStore();
    GraphAttemptCoordinator first =
        new GraphAttemptCoordinator(firstStore);
    GraphAttemptCoordinator.EgressAuthority firstEgress =
        consumedEgress(first, manifest, 1);
    first.credentialReadStarted(
        firstEgress, manifest.startedAt().plusMillis(6));
    Pack010ProviderCredentialBroker.CredentialLease lease =
        syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            first,
            firstEgress);

    RecordingStore secondStore = new RecordingStore();
    GraphAttemptCoordinator second =
        new GraphAttemptCoordinator(secondStore);
    GraphAttemptCoordinator.EgressAuthority secondEgress =
        consumedEgress(second, manifest, 1);
    second.credentialReadStarted(
        secondEgress, manifest.startedAt().plusMillis(6));

    assertThrows(
        Pack010ProviderCredentialBroker.CredentialRejected.class,
        () ->
            new Pack010ProviderSessionComposer()
                .compose(
                    preflight(),
                    lease,
                    second,
                    secondEgress,
                    Clock.fixed(
                        manifest.startedAt().plusMillis(7),
                        ZoneOffset.UTC)));
    assertEquals(
        SYNTHETIC_CREDENTIAL,
        lease.claim(
            first,
            firstEgress,
            Clock.fixed(
                manifest.startedAt().plusMillis(7),
                ZoneOffset.UTC)));
    assertEquals(0, secondStore.clients);
    assertEquals(0, secondStore.models);
  }

  @Test
  void expiredLeaseFailsBeforeCredentialClaimClientModelAndSession()
      throws Exception {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        consumedEgress(coordinator, manifest, 1);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    Pack010ProviderCredentialBroker.CredentialLease expired =
        syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            coordinator,
            egress,
            manifest.startedAt().plusMillis(6));
    Clock afterExpiry =
        Clock.fixed(
            manifest.startedAt().plusMillis(7), ZoneOffset.UTC);

    assertThrows(
        Pack010ProviderCredentialBroker.CredentialRejected.class,
        () -> new Pack010ProviderSessionComposer()
            .compose(
                preflight(), expired, coordinator, egress, afterExpiry));
    assertEquals(0, store.clients);
    assertEquals(0, store.models);
    assertEquals(0, store.providerIntents);
    assertThrows(
        Pack010ProviderCredentialBroker.CredentialRejected.class,
        () -> expired.claim(coordinator, egress, afterExpiry));
  }

  @Test
  void sessionExpiryAfterComposeFailsBeforeProviderIntentAndHttp()
      throws Exception {
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(1);
    RecordingStore store = new RecordingStore(true);
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        consumedEgress(coordinator, manifest, 1);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    Instant expiresAt = manifest.startedAt().plusMillis(8);
    Pack010ProviderCredentialBroker.CredentialLease lease =
        syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.R1,
            coordinator,
            egress,
            expiresAt);
    MutableClock clock =
        new MutableClock(
            manifest.startedAt().plusMillis(7), ZoneOffset.UTC);

    try (Pack010ProviderSessionComposer.ProviderSession session =
        new Pack010ProviderSessionComposer()
            .compose(preflight(), lease, coordinator, egress, clock)) {
      clock.setInstant(expiresAt);

      assertThrows(
          Pack010ProviderCredentialBroker.CredentialRejected.class,
          () -> session.next(
              new AgentModel.Turn(
                  Pack010GraphEvalCatalog.childTask(1), List.of()),
              new AgentModel.ModelCallContext(
                  Pack010GraphEvalCatalog.workerProfile(1).deadlineMs(),
                  Pack010GraphEvalCatalog.workerProfile(1).budgetUsd(),
                  CancellationSignal.never())));
      assertEquals(0, store.providerIntents);
      assertEquals(
          GraphAttemptEventType.MODEL_CREATED,
          store.events.getLast());
    }
  }

  static GraphAttemptCoordinator.EgressAuthority consumedEgress(
      GraphAttemptCoordinator coordinator,
      GraphAttemptManifest manifest,
      int repetition) {
    Instant at = manifest.startedAt();
    GraphAttemptCoordinator.Authorized approved =
        coordinator.approve(
            manifest,
            new GraphAttemptCoordinator.InteractiveConsole() {
              @Override
              public boolean realTty() {
                return true;
              }

              @Override
              public String readLine(String prompt) {
                return prompt;
              }
            },
            Clock.fixed(at, ZoneOffset.UTC));
    GraphAttemptCoordinator.ParentStarted parent =
        coordinator.startParent(
            coordinator.authorizeParent(
                approved,
                Pack010GraphEvalCatalog.parentRun(repetition),
                at.plusMillis(1)),
            at.plusMillis(2));
    GraphAttemptCoordinator.ChildStarted child =
        coordinator.startChild(
            coordinator.authorizeChild(
                parent,
                Pack010GraphEvalCatalog.childRun(repetition),
                at.plusMillis(3)),
            at.plusMillis(4));
    return coordinator.consumeChildEgress(
        child, at.plusMillis(5));
  }

  static Pack010ProviderCredentialBroker.CredentialLease
      syntheticLease(
          OwnerTtyGraphAuthority.Pack010Revision revision,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egress)
          throws ReflectiveOperationException {
    Constructor<Pack010ProviderCredentialBroker.CredentialLease>
        constructor =
            Pack010ProviderCredentialBroker.CredentialLease.class
                .getDeclaredConstructor(
                    OwnerTtyGraphAuthority.Pack010Revision.class,
                    GraphAttemptCoordinator.class,
                    GraphAttemptCoordinator.EgressAuthority.class,
                    String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        revision, coordinator, egress, SYNTHETIC_CREDENTIAL);
  }

  static Pack010ProviderCredentialBroker.CredentialLease
      syntheticLease(
          OwnerTtyGraphAuthority.Pack010Revision revision,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egress,
          Instant expiresAt)
          throws ReflectiveOperationException {
    Constructor<Pack010ProviderCredentialBroker.CredentialLease>
        constructor =
            Pack010ProviderCredentialBroker.CredentialLease.class
                .getDeclaredConstructor(
                    OwnerTtyGraphAuthority.Pack010Revision.class,
                    OwnerTtyGraphAuthority.class,
                    OwnerTtyGraphAuthority.ProviderSessionIntent.class,
                    Instant.class,
                    GraphAttemptCoordinator.class,
                    GraphAttemptCoordinator.EgressAuthority.class,
                    String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        revision,
        null,
        null,
        expiresAt,
        coordinator,
        egress,
        SYNTHETIC_CREDENTIAL);
  }

  private static Pack010GraphPreflight.Result preflight() {
    Path repo =
        Path.of(System.getProperty("emerge.graph.repo", "."))
            .toAbsolutePath()
            .normalize();
    if (!Files.isRegularFile(repo.resolve("pom.xml"))) {
      throw new IllegalStateException("repository root not found");
    }
    return new Pack010GraphPreflight(repo).run();
  }

  static final class RecordingStore
      implements GraphAttemptStore {

    final List<GraphAttemptEventType> events =
        new ArrayList<>();
    private GraphAttemptCursor cursor;
    final boolean rejectProviderIntent;
    final boolean rejectProviderAttribution;
    int clients;
    int models;
    int providerIntents;
    int providerAttributions;
    final List<GraphProviderAttribution> attributions =
        new ArrayList<>();

    RecordingStore() {
      this(false, false);
    }

    RecordingStore(boolean rejectProviderIntent) {
      this(rejectProviderIntent, false);
    }

    RecordingStore(
        boolean rejectProviderIntent,
        boolean rejectProviderAttribution) {
      this.rejectProviderIntent = rejectProviderIntent;
      this.rejectProviderAttribution = rejectProviderAttribution;
    }

    @Override
    public CreateResult create(
        GraphAttemptManifest manifest, Instant occurredAt) {
      GraphAttemptEvent event =
          GraphAttemptEvent.claimed(manifest, occurredAt);
      cursor = event.cursor(manifest);
      events.add(event.type());
      return new CreateResult.Created(cursor);
    }

    @Override
    public GraphAttemptCursor approve(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphOperatorApproval approval,
        Instant occurredAt) {
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.OPERATOR_APPROVED,
          GraphAttemptPhase.OPERATOR_APPROVED,
          null,
          null,
          approval,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor authorizeParent(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      return run(
          manifest,
          expected,
          GraphAttemptEventType.PARENT_AUTHORIZED,
          GraphAttemptPhase.PARENT_AUTHORIZED,
          GraphRunRole.PARENT,
          running,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor startParent(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      return run(
          manifest,
          expected,
          GraphAttemptEventType.PARENT_STARTED,
          GraphAttemptPhase.PARENT_RUNNING,
          GraphRunRole.PARENT,
          running,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor authorizeChild(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      return run(
          manifest,
          expected,
          GraphAttemptEventType.CHILD_AUTHORIZED,
          GraphAttemptPhase.CHILD_AUTHORIZED,
          GraphRunRole.CHILD,
          running,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor startChild(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      return run(
          manifest,
          expected,
          GraphAttemptEventType.CHILD_STARTED,
          GraphAttemptPhase.CHILD_RUNNING,
          GraphRunRole.CHILD,
          running,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor consumeChildEgress(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
          GraphAttemptPhase.EGRESS_CONSUMED,
          GraphRunRole.CHILD,
          manifest.childSelection().runId(),
          null,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor credentialReadStarted(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.CREDENTIAL_READ_STARTED,
          GraphAttemptPhase.CREDENTIAL_READING,
          GraphRunRole.CHILD,
          manifest.childSelection().runId(),
          null,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor clientCreated(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      clients++;
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.CLIENT_CREATED,
          GraphAttemptPhase.CLIENT_READY,
          GraphRunRole.CHILD,
          manifest.childSelection().runId(),
          null,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor modelCreated(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      models++;
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.MODEL_CREATED,
          GraphAttemptPhase.MODEL_READY,
          GraphRunRole.CHILD,
          manifest.childSelection().runId(),
          null,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor providerIntent(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphProviderIntent intent,
        Instant occurredAt) {
      if (rejectProviderIntent) {
        throw new IllegalStateException(
            "SYNTHETIC_PROVIDER_INTENT_PERSISTENCE_FAILURE");
      }
      providerIntents++;
      return advance(
          manifest,
          expected,
          GraphAttemptEventType.PROVIDER_INTENT,
          GraphAttemptPhase.PROVIDER_PENDING,
          GraphRunRole.CHILD,
          manifest.childSelection().runId(),
          null,
          intent,
          occurredAt);
    }

    private GraphAttemptCursor run(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphAttemptEventType type,
        GraphAttemptPhase phase,
        GraphRunRole role,
        AgentRun running,
        Instant occurredAt) {
      return advance(
          manifest,
          expected,
          type,
          phase,
          role,
          running.runId(),
          null,
          null,
          occurredAt);
    }

    private GraphAttemptCursor advance(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphAttemptEventType type,
        GraphAttemptPhase phase,
        GraphRunRole role,
        String runId,
        GraphOperatorApproval approval,
        GraphProviderIntent intent,
        Instant occurredAt) {
      if (!expected.equals(cursor)) {
        throw new IllegalStateException("stale synthetic cursor");
      }
      String taskId =
          role == GraphRunRole.PARENT
              ? manifest.parentSelection().taskId()
              : role == GraphRunRole.CHILD
                  ? manifest.childSelection().taskId()
                  : null;
      GraphAttemptEvent event =
          GraphAttemptEvent.next(
              cursor,
              type,
              occurredAt,
              phase,
              role,
              runId,
              taskId,
              approval,
              intent);
      cursor = event.cursor(manifest);
      events.add(type);
      return cursor;
    }

    @Override
    public GraphAttemptCursor providerAttributed(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphProviderAttribution attribution,
        Instant occurredAt) {
      if (rejectProviderAttribution) {
        throw new IllegalStateException(
            "SYNTHETIC_PROVIDER_ATTRIBUTION_PERSISTENCE_FAILURE");
      }
      if (!expected.equals(cursor)) {
        throw new IllegalStateException("stale synthetic cursor");
      }
      GraphAttemptEvent event =
          GraphAttemptEvent.providerAttributed(
              cursor,
              manifest.childSelection(),
              attribution,
              occurredAt);
      cursor = event.cursor(manifest);
      events.add(event.type());
      attributions.add(attribution);
      providerAttributions++;
      return cursor;
    }

    @Override
    public GraphAttemptCursor completeChild(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRunContext parent,
        AgentRun terminalChild,
        HarnessCandidateEnvelope candidate,
        WorkerResultEnvelope workerResult,
        Instant occurredAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GraphAttemptCursor completeParentAndSeal(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun terminalParent,
        ArtifactLineage artifact,
        Instant occurredAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GraphAttemptVerification findVerified(
        GraphAttemptManifest expected) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    private void setInstant(Instant value) {
      this.instant = value;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId value) {
      return new MutableClock(instant, value);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
