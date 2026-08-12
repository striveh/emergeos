package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.WorkerHandoffRequest;
import io.emergeos.core.port.AgentWorkerRuntime;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.GraphAttemptStore;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphAttemptCoordinatorTest {

  private static final Instant NOW =
      Instant.parse("2026-07-31T06:00:00Z");

  @Test
  void commitsMarkerBeforeApprovalAndMintsOnlyOpaqueTypestate() {
    Fixture fixture = fixture();
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    List<String> consoleObservations = new ArrayList<>();

    GraphAttemptCoordinator.Authorized authorized =
        coordinator.approve(
            fixture.manifest(),
            new GraphAttemptCoordinator.InteractiveConsole() {
              @Override
              public boolean realTty() {
                consoleObservations.add(
                    "tty-after-" + store.calls);
                return true;
              }

              @Override
              public String readLine(String prompt) {
                consoleObservations.add(
                    "challenge-after-" + store.calls);
                return prompt;
              }
            },
            fixedClock());

    assertEquals(List.of("create", "approve"), store.calls);
    assertEquals(
        List.of(
            "tty-after-[]",
            "challenge-after-[create]"),
        consoleObservations);
    assertOpaque(authorized.getClass());
    assertOpaque(
        GraphAttemptCoordinator.ParentAuthorized.class);
    assertOpaque(GraphAttemptCoordinator.ParentStarted.class);
    assertOpaque(
        GraphAttemptCoordinator.ChildAuthorized.class);
    assertOpaque(GraphAttemptCoordinator.ChildStarted.class);
    assertOpaque(
        GraphAttemptCoordinator.EgressAuthority.class);
  }

  @Test
  void publicAdoptionRejectsGenericApprovalOriginBeforeStoreRead() {
    Fixture fixture = fixture();
    RecordingStore store = new RecordingStore();
    GraphAttemptCursor marked =
        ((GraphAttemptStore.CreateResult.Created)
                store.create(fixture.manifest(), NOW))
            .cursor();
    GraphAttemptCursor approved =
        store.approve(
            fixture.manifest(),
            marked,
            GraphOperatorApproval.ownerTty(fixture.manifest()),
            NOW.plusMillis(1));
    store.calls.clear();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            coordinator.adoptOwnerApproved(
                fixture.manifest(), approved, new Object()));
    assertTrue(store.calls.isEmpty());
  }

  @Test
  void fixedPrefixRequiresExactParentThenExactChildEgress() {
    Fixture fixture = fixture();
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);

    GraphAttemptCoordinator.Authorized approved =
        coordinator.approve(
            fixture.manifest(), exactConsole(), fixedClock());
    GraphAttemptCoordinator.ParentAuthorized parentAuthorized =
        coordinator.authorizeParent(
            approved, fixture.parent(), NOW.plusMillis(1));
    GraphAttemptCoordinator.ParentStarted parent =
        coordinator.startParent(
            parentAuthorized, NOW.plusMillis(2));
    GraphAttemptCoordinator.ChildAuthorized childAuthorized =
        coordinator.authorizeChild(
            parent, fixture.child(), NOW.plusMillis(3));
    GraphAttemptCoordinator.ChildStarted child =
        coordinator.startChild(
            childAuthorized, NOW.plusMillis(4));
    GraphAttemptCoordinator.EgressAuthority egress =
        coordinator.consumeChildEgress(
            child, NOW.plusMillis(5));
    coordinator.credentialReadStarted(
        egress, NOW.plusMillis(6));
    coordinator.clientCreated(egress, NOW.plusMillis(7));
    coordinator.modelCreated(egress, NOW.plusMillis(8));
    coordinator.providerIntent(
        egress,
        new GraphProviderIntent(
            1,
            IntegrityHashes.utf8ContentHash(
                "safe exact request surface"),
            fixture.profile().modelRequested()),
        NOW.plusMillis(9));

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
            GraphAttemptEventType.MODEL_CREATED,
            GraphAttemptEventType.PROVIDER_INTENT),
        store.events.stream()
            .map(GraphAttemptEvent::type)
            .toList());
    assertEquals(11, store.cursor.lastSequence());
    assertEquals(
        GraphAttemptPhase.PROVIDER_PENDING,
        store.cursor.phase());
    assertEquals(
        io.emergeos.core.domain.GraphBillingStatus.UNKNOWN,
        GraphAttemptSnapshot.deriveBilling(store.events));
    assertEquals(1, store.parentStarts);
    assertEquals(1, store.childStarts);
  }

  @Test
  void badChallengeBurnsSlotAndReplayStopsBeforeConsole() {
    Fixture fixture = fixture();
    RecordingStore firstStore = new RecordingStore();
    GraphAttemptCoordinator first =
        new GraphAttemptCoordinator(firstStore);

    GraphAttemptCoordinator.OperatorApprovalException bad =
        assertThrows(
            GraphAttemptCoordinator.OperatorApprovalException.class,
            () ->
                first.approve(
                    fixture.manifest(),
                    new GraphAttemptCoordinator.InteractiveConsole() {
                      @Override
                      public boolean realTty() {
                        return true;
                      }

                      @Override
                      public String readLine(String prompt) {
                        return "wrong";
                      }
                    },
                    fixedClock()));
    assertEquals("OPERATOR_CHALLENGE_MISMATCH", bad.code());
    assertEquals(List.of("create"), firstStore.calls);

    RecordingStore replayStore = new RecordingStore();
    replayStore.claimed = true;
    boolean[] challengeReached = {false};
    GraphAttemptConflictException replay =
        assertThrows(
            GraphAttemptConflictException.class,
            () ->
                new GraphAttemptCoordinator(replayStore)
                    .approve(
                        fixture.manifest(),
                        new GraphAttemptCoordinator.InteractiveConsole() {
                          @Override
                          public boolean realTty() {
                            return true;
                          }

                          @Override
                          public String readLine(String prompt) {
                            challengeReached[0] = true;
                            return prompt;
                          }
                        },
                        fixedClock()));
    assertEquals(
        "execution slot was already claimed",
        replay.getMessage());
    assertFalse(challengeReached[0]);
    assertEquals(List.of("create"), replayStore.calls);
  }

  @Test
  void authorityCannotCrossCoordinatorOrSkipLazyStages() {
    Fixture fixture = fixture();
    GraphAttemptCoordinator first =
        new GraphAttemptCoordinator(new RecordingStore());
    GraphAttemptCoordinator.Authorized authority =
        first.approve(
            fixture.manifest(), exactConsole(), fixedClock());
    GraphAttemptCoordinator second =
        new GraphAttemptCoordinator(new RecordingStore());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            second.authorizeParent(
                authority, fixture.parent(), NOW));

    GraphAttemptCoordinator.ParentStarted parent =
        first.startParent(
            first.authorizeParent(
                authority, fixture.parent(), NOW),
            NOW);
    GraphAttemptCoordinator.ChildStarted child =
        first.startChild(
            first.authorizeChild(
                parent, fixture.child(), NOW),
            NOW);
    GraphAttemptCoordinator.EgressAuthority egress =
        first.consumeChildEgress(child, NOW);
    assertThrows(
        IllegalStateException.class,
        () -> first.clientCreated(egress, NOW));
    assertThrows(
        IllegalStateException.class,
        () ->
            first.providerIntent(
                egress,
                new GraphProviderIntent(
                    1,
                    IntegrityHashes.utf8ContentHash("request"),
                    fixture.profile().modelRequested()),
                NOW));
  }

  @Test
  void providerAttributionMustMatchTheExactPendingOrdinal() {
    Fixture fixture = fixture();
    RecordingStore store = new RecordingStore();
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        readyEgress(coordinator, fixture);
    GraphProviderIntent first =
        intent(fixture, 1, "request-one");
    coordinator.providerIntent(egress, first, NOW.plusMillis(9));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            coordinator.providerAttributed(
                egress,
                attribution(
                    fixture,
                    2,
                    IntegrityHashes.utf8ContentHash("request-two")),
                NOW.plusMillis(10)));
    coordinator.providerAttributed(
        egress,
        attribution(fixture, 1, first.requestHash()),
        NOW.plusMillis(10));
    assertThrows(
        IllegalStateException.class,
        () -> coordinator.requireProviderAttributed(
            egress, fixture.manifest()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            coordinator.providerIntent(
                egress,
                intent(fixture, 3, "request-three"),
                NOW.plusMillis(11)));
    GraphProviderIntent second =
        intent(fixture, 2, "request-two");
    coordinator.providerIntent(
        egress, second, NOW.plusMillis(11));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            coordinator.providerAttributed(
                egress,
                attribution(fixture, 1, first.requestHash()),
                NOW.plusMillis(12)));
    coordinator.providerAttributed(
        egress,
        attribution(fixture, 2, second.requestHash()),
        NOW.plusMillis(12));
    coordinator.requireProviderAttributed(
        egress, fixture.manifest());

    assertEquals(
        List.of(1, 2),
        store.events.stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_ATTRIBUTED)
            .map(GraphAttemptEvent::requestOrdinal)
            .toList());
  }

  private static GraphAttemptCoordinator.InteractiveConsole
      exactConsole() {
    return new GraphAttemptCoordinator.InteractiveConsole() {
      @Override
      public boolean realTty() {
        return true;
      }

      @Override
      public String readLine(String prompt) {
        return prompt;
      }
    };
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static GraphAttemptCoordinator.EgressAuthority readyEgress(
      GraphAttemptCoordinator coordinator, Fixture fixture) {
    GraphAttemptCoordinator.Authorized approved =
        coordinator.approve(
            fixture.manifest(), exactConsole(), fixedClock());
    GraphAttemptCoordinator.ParentStarted parent =
        coordinator.startParent(
            coordinator.authorizeParent(
                approved, fixture.parent(), NOW.plusMillis(1)),
            NOW.plusMillis(2));
    GraphAttemptCoordinator.ChildStarted child =
        coordinator.startChild(
            coordinator.authorizeChild(
                parent, fixture.child(), NOW.plusMillis(3)),
            NOW.plusMillis(4));
    GraphAttemptCoordinator.EgressAuthority egress =
        coordinator.consumeChildEgress(
            child, NOW.plusMillis(5));
    coordinator.credentialReadStarted(
        egress, NOW.plusMillis(6));
    coordinator.clientCreated(egress, NOW.plusMillis(7));
    coordinator.modelCreated(egress, NOW.plusMillis(8));
    return egress;
  }

  private static GraphProviderIntent intent(
      Fixture fixture, int ordinal, String request) {
    return new GraphProviderIntent(
        ordinal,
        IntegrityHashes.utf8ContentHash(request),
        fixture.profile().modelRequested());
  }

  private static GraphProviderAttribution attribution(
      Fixture fixture, int ordinal, String requestHash) {
    PricingProfile pricing = fixture.profile().pricing();
    return GraphProviderAttribution.create(
        ordinal,
        requestHash,
        IntegrityHashes.utf8ContentHash("response-" + ordinal),
        fixture.manifest().childActor(),
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

  private static void assertOpaque(Class<?> type) {
    assertFalse(type.isRecord());
    assertFalse(Serializable.class.isAssignableFrom(type));
    assertTrue(
        java.util.Arrays.stream(type.getDeclaredConstructors())
            .allMatch(
                constructor ->
                    Modifier.isPrivate(
                        constructor.getModifiers())));
  }

  private static Fixture fixture() {
    PricingProfile pricing =
        new PricingProfile(
            "openai-test-pricing-v1",
            "openai.responses",
            "gpt-test",
            750,
            75,
            4_500);
    ModelBoundReadOnlyWorkerExecutionProfile worker =
        ModelBoundReadOnlyWorkerExecutionProfile.pack008OpenAiV1(
            pricing,
            "openai-test-protocol-v1",
            IntegrityHashes.utf8ContentHash("conductor"),
            "environment://sha256:"
                + IntegrityHashes.utf8ContentHash("environment"),
            new HarnessExperiment("graph-test", 1),
            272_000,
            1_000,
            new BigDecimal("0.417000"));
    AgentExecutionProfile parentProfile =
        AgentExecutionProfile.readOnlyWorkerModelV1(worker);
    TaskEnvelope parentTask =
        parentProfile.newDraftTask(
            "parent-task",
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
            "child-task");
    AgentRun parent =
        AgentRun.running(
            "parent-run", "principal", parentTask, NOW);
    AgentRun child =
        AgentRun.running(
            "child-run", "principal", childTask, NOW);
    GraphRunSelection parentSelection =
        new GraphRunSelection(
            GraphRunRole.PARENT,
            parent.runId(),
            parentTask.id(),
            IntegrityHashes.taskHash(parentTask),
            parentProfile.id(),
            parentProfile.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphRunSelection childSelection =
        new GraphRunSelection(
            GraphRunRole.CHILD,
            child.runId(),
            childTask.id(),
            IntegrityHashes.taskHash(childTask),
            worker.id(),
            worker.fingerprint(),
            worker.registryVersion(),
            worker.id(),
            worker.fingerprint());
    GraphAttemptManifest manifest =
        GraphAttemptManifest.create(
            "postgres-graph-attempt-v1",
            "principal",
            "graph-test-r1",
            "graph-case-r1",
            IntegrityHashes.utf8ContentHash("pack"),
            IntegrityHashes.utf8ContentHash("environment"),
            "capture-1",
            IntegrityHashes.utf8ContentHash("capture"),
            "artifact-1",
            NOW,
            pricing.fingerprint(),
            IntegrityHashes.utf8ContentHash("prompt"),
            IntegrityHashes.utf8ContentHash("conductor"),
            worker.reservationUsd(),
            2,
            "SCRIPTED_FAKE",
            "OPENAI_RESPONSES",
            worker.experiment(),
            parentSelection,
            childSelection);
    return new Fixture(manifest, parent, child, worker);
  }

  private record Fixture(
      GraphAttemptManifest manifest,
      AgentRun parent,
      AgentRun child,
      ModelBoundReadOnlyWorkerExecutionProfile profile) {}

  private static final class RecordingStore
      implements GraphAttemptStore {

    private final List<String> calls = new ArrayList<>();
    private final List<GraphAttemptEvent> events =
        new ArrayList<>();
    private GraphAttemptCursor cursor;
    private boolean claimed;
    private int parentStarts;
    private int childStarts;

    @Override
    public CreateResult create(
        GraphAttemptManifest manifest, Instant occurredAt) {
      calls.add("create");
      if (claimed) {
        return new CreateResult.AlreadyExists();
      }
      claimed = true;
      GraphAttemptEvent created =
          GraphAttemptEvent.claimed(manifest, occurredAt);
      events.add(created);
      cursor = created.cursor(manifest);
      return new CreateResult.Created(cursor);
    }

    @Override
    public GraphAttemptCursor approve(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphOperatorApproval approval,
        Instant occurredAt) {
      calls.add("approve");
      return advance(
          expected,
          GraphAttemptEventType.OPERATOR_APPROVED,
          GraphAttemptPhase.OPERATOR_APPROVED,
          null,
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
      calls.add("authorizeParent");
      requireRun(manifest.parentSelection(), running);
      return advanceRun(
          expected,
          GraphAttemptEventType.PARENT_AUTHORIZED,
          GraphAttemptPhase.PARENT_AUTHORIZED,
          GraphRunRole.PARENT,
          running,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor startParent(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      calls.add("startParent");
      requireRun(manifest.parentSelection(), running);
      parentStarts++;
      return advanceRun(
          expected,
          GraphAttemptEventType.PARENT_STARTED,
          GraphAttemptPhase.PARENT_RUNNING,
          GraphRunRole.PARENT,
          running,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor authorizeChild(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      calls.add("authorizeChild");
      requireRun(manifest.childSelection(), running);
      return advanceRun(
          expected,
          GraphAttemptEventType.CHILD_AUTHORIZED,
          GraphAttemptPhase.CHILD_AUTHORIZED,
          GraphRunRole.CHILD,
          running,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor startChild(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun running,
        Instant occurredAt) {
      calls.add("startChild");
      requireRun(manifest.childSelection(), running);
      childStarts++;
      return advanceRun(
          expected,
          GraphAttemptEventType.CHILD_STARTED,
          GraphAttemptPhase.CHILD_RUNNING,
          GraphRunRole.CHILD,
          running,
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor consumeChildEgress(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      calls.add("consumeChildEgress");
      return advanceSelection(
          expected,
          GraphAttemptEventType.CHILD_EGRESS_CONSUMED,
          GraphAttemptPhase.EGRESS_CONSUMED,
          manifest.childSelection(),
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor credentialReadStarted(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      calls.add("credentialReadStarted");
      return advanceSelection(
          expected,
          GraphAttemptEventType.CREDENTIAL_READ_STARTED,
          GraphAttemptPhase.CREDENTIAL_READING,
          manifest.childSelection(),
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor clientCreated(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      calls.add("clientCreated");
      return advanceSelection(
          expected,
          GraphAttemptEventType.CLIENT_CREATED,
          GraphAttemptPhase.CLIENT_READY,
          manifest.childSelection(),
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor modelCreated(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        Instant occurredAt) {
      calls.add("modelCreated");
      return advanceSelection(
          expected,
          GraphAttemptEventType.MODEL_CREATED,
          GraphAttemptPhase.MODEL_READY,
          manifest.childSelection(),
          null,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor providerIntent(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphProviderIntent intent,
        Instant occurredAt) {
      calls.add("providerIntent");
      GraphRunSelection selection = manifest.childSelection();
      return advance(
          expected,
          GraphAttemptEventType.PROVIDER_INTENT,
          GraphAttemptPhase.PROVIDER_PENDING,
          selection.role(),
          selection.runId(),
          selection.taskId(),
          null,
          intent,
          occurredAt);
    }

    @Override
    public GraphAttemptCursor providerAttributed(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        GraphProviderAttribution attribution,
        Instant occurredAt) {
      calls.add("providerAttributed");
      GraphAttemptEvent event =
          GraphAttemptEvent.providerAttributed(
              expected,
              manifest.childSelection(),
              attribution,
              occurredAt);
      events.add(event);
      cursor = event.advance(expected);
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
      throw new UnsupportedOperationException(
          "terminal child is outside this coordinator test fake");
    }

    @Override
    public GraphAttemptCursor completeParentAndSeal(
        GraphAttemptManifest manifest,
        GraphAttemptCursor expected,
        AgentRun terminalParent,
        ArtifactLineage artifact,
        Instant occurredAt) {
      throw new UnsupportedOperationException(
          "terminal parent is outside this coordinator test fake");
    }

    @Override
    public GraphAttemptVerification findVerified(
        GraphAttemptManifest expected) {
      calls.add("findVerified");
      if (!claimed || cursor == null || events.isEmpty()) {
        return new GraphAttemptVerification.Missing();
      }
      return new GraphAttemptVerification.Valid(
          new GraphAttemptSnapshot(
              expected,
              cursor,
              events,
              null,
              null,
              false,
              io.emergeos.core.domain.GraphAttemptOutcome.INCOMPLETE,
              GraphAttemptSnapshot.deriveBilling(events)));
    }

    private GraphAttemptCursor advanceSelection(
        GraphAttemptCursor expected,
        GraphAttemptEventType type,
        GraphAttemptPhase next,
        GraphRunSelection selection,
        GraphOperatorApproval approval,
        Instant at) {
      return advance(
          expected,
          type,
          next,
          selection.role(),
          selection.runId(),
          selection.taskId(),
          approval,
          null,
          at);
    }

    private GraphAttemptCursor advanceRun(
        GraphAttemptCursor expected,
        GraphAttemptEventType type,
        GraphAttemptPhase next,
        GraphRunRole role,
        AgentRun run,
        GraphOperatorApproval approval,
        Instant at) {
      return advance(
          expected,
          type,
          next,
          role,
          run.runId(),
          run.task().id(),
          approval,
          null,
          at);
    }

    private GraphAttemptCursor advance(
        GraphAttemptCursor expected,
        GraphAttemptEventType type,
        GraphAttemptPhase next,
        GraphRunRole role,
        String runId,
        String taskId,
        GraphOperatorApproval approval,
        GraphProviderIntent intent,
        Instant at) {
      assertEquals(cursor, expected);
      GraphAttemptEvent event =
          GraphAttemptEvent.next(
              cursor,
              type,
              at,
              next,
              role,
              runId,
              taskId,
              approval,
              intent);
      events.add(event);
      cursor = event.advance(cursor);
      return cursor;
    }

    private static void requireRun(
        GraphRunSelection selection, AgentRun run) {
      assertEquals(selection.runId(), run.runId());
      assertEquals(selection.taskId(), run.task().id());
      assertEquals(
          selection.taskHash(),
          IntegrityHashes.taskHash(run.task()));
    }
  }
}
