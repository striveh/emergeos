package io.emergeos.core.application;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.GraphAttemptStore;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Opaque typestate authority for the least-authority Pack009 graph prefix.
 *
 * <p>A durable marker is committed before the challenge. Approval is
 * durably journaled before an authority object exists. Only the exact child
 * authority can consume egress, and credential/client/provider operations
 * are unreachable before that durable consume.
 */
public final class GraphAttemptCoordinator {

  public static final String CHALLENGE_PREFIX =
      GraphOperatorApproval.CHALLENGE_PREFIX;

  private static final String OWNER_AUTHORITY_CLASS_NAME =
      "io.emergeos.adapters.postgres.OwnerTtyGraphAuthority";
  private static final String OWNER_PERMIT_CLASS_NAME =
      OWNER_AUTHORITY_CLASS_NAME + "$MutationPermit";
  private static final StackWalker CALLER =
      StackWalker.getInstance(
          StackWalker.Option.RETAIN_CLASS_REFERENCE);

  private final GraphAttemptStore store;
  private final boolean genericApprovalAllowed;
  private final Object ownerToken = new Object();
  private final Set<GraphAttemptCursor> adoptedOwnerApprovals =
      new HashSet<>();

  public GraphAttemptCoordinator(GraphAttemptStore store) {
    this(store, true);
  }

  private GraphAttemptCoordinator(
      GraphAttemptStore store, boolean genericApprovalAllowed) {
    this.store = Objects.requireNonNull(store, "store");
    this.genericApprovalAllowed = genericApprovalAllowed;
  }

  /**
   * Creates the narrow coordinator used by the trusted owner authority.
   * Its generic console approval path is permanently disabled.
   */
  public static GraphAttemptCoordinator ownerAdoptionOnly(
      GraphAttemptStore store) {
    Class<?> caller = CALLER.getCallerClass();
    if (!caller.getName().equals(OWNER_AUTHORITY_CLASS_NAME)
        || caller != OwnerAuthorityTypes.AUTHORITY) {
      throw new IllegalArgumentException(
          "owner-only coordinator lacks its exact factory origin");
    }
    return new GraphAttemptCoordinator(store, false);
  }

  public Authorized approve(
      GraphAttemptManifest manifest,
      InteractiveConsole console,
      Clock clock) {
    if (!genericApprovalAllowed) {
      throw new OperatorApprovalException(
          "OWNER_ADOPTION_ONLY");
    }
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(console, "console");
    Objects.requireNonNull(clock, "clock");
    if (!console.realTty()) {
      throw new OperatorApprovalException(
          "REAL_TTY_REQUIRED");
    }
    GraphAttemptStore.CreateResult claim =
        store.create(manifest, clock.instant());
    if (claim
        instanceof GraphAttemptStore.CreateResult.AlreadyExists) {
      throw new GraphAttemptConflictException(
          "execution slot was already claimed");
    }
    GraphAttemptCursor marked =
        ((GraphAttemptStore.CreateResult.Created) claim).cursor();
    String expected =
        GraphOperatorApproval.expectedChallenge(manifest);
    String response = console.readLine(expected);
    if (!expected.equals(response)) {
      throw new OperatorApprovalException(
          "OPERATOR_CHALLENGE_MISMATCH");
    }
    GraphOperatorApproval approval =
        GraphOperatorApproval.ownerTty(manifest);
    GraphAttemptCursor authorized =
        store.approve(
            manifest, marked, approval, clock.instant());
    return new Authorized(ownerToken, manifest, authorized);
  }

  /**
   * Adopts one exact, already durable owner approval without claiming or
   * approving the execution slot again.
   *
   * <p>This is a same-process handoff, not a resume surface. The production
   * caller must retain an unforgeable owner-issued capability; this method
   * independently re-verifies the committed sequence-2 prefix and permits a
   * given coordinator to mint at most one typestate for the exact cursor.
   */
  public Authorized adoptOwnerApproved(
      GraphAttemptManifest manifest,
      GraphAttemptCursor approvedCursor,
      Object ownerPermit) {
    Objects.requireNonNull(ownerPermit, "ownerPermit");
    Class<?> caller = CALLER.getCallerClass();
    if (!caller.getName().equals(OWNER_AUTHORITY_CLASS_NAME)
        || !ownerPermit.getClass().getName().equals(
            OWNER_PERMIT_CLASS_NAME)
        || caller != OwnerAuthorityTypes.AUTHORITY
        || ownerPermit.getClass() != OwnerAuthorityTypes.PERMIT) {
      throw new IllegalArgumentException(
          "owner-approved cursor lacks its exact origin capability");
    }
    return adoptVerifiedOwnerApproved(manifest, approvedCursor);
  }

  private synchronized Authorized adoptVerifiedOwnerApproved(
      GraphAttemptManifest manifest,
      GraphAttemptCursor approvedCursor) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(approvedCursor, "approvedCursor");
    if (adoptedOwnerApprovals.contains(approvedCursor)) {
      throw new IllegalStateException(
          "owner-approved cursor was already adopted");
    }
    if (!approvedCursor.principalId().equals(manifest.principalId())
        || !approvedCursor.attemptId().equals(manifest.attemptId())
        || !approvedCursor.manifestHash().equals(manifest.manifestHash())
        || approvedCursor.stateVersion() != 2
        || approvedCursor.lastSequence() != 2
        || approvedCursor.phase()
            != GraphAttemptPhase.OPERATOR_APPROVED) {
      throw new GraphAttemptConflictException(
          "owner-approved cursor does not match the exact manifest");
    }
    GraphAttemptVerification verification =
        store.findVerified(manifest);
    if (verification instanceof GraphAttemptVerification.Invalid) {
      throw new GraphAttemptIntegrityException();
    }
    if (!(verification instanceof GraphAttemptVerification.Valid valid)
        || !valid.snapshot().manifest().equals(manifest)
        || !valid.snapshot().cursor().equals(approvedCursor)
        || valid.snapshot().events().size() != 2) {
      throw new GraphAttemptConflictException(
          "owner-approved cursor is missing or stale");
    }
    GraphAttemptEvent claimed =
        valid.snapshot().events().get(0);
    GraphAttemptEvent approved =
        valid.snapshot().events().get(1);
    GraphOperatorApproval expected =
        GraphOperatorApproval.ownerTty(manifest);
    if (claimed.type() != GraphAttemptEventType.ATTEMPT_CLAIMED
        || claimed.sequence() != 1
        || approved.type()
            != GraphAttemptEventType.OPERATOR_APPROVED
        || approved.sequence() != 2
        || !expected.actor().equals(approved.actor())
        || !expected.challengeHash().equals(
            approved.challengeHash())) {
      throw new GraphAttemptIntegrityException();
    }
    adoptedOwnerApprovals.add(approvedCursor);
    return new Authorized(
        ownerToken, manifest, approvedCursor);
  }

  private static final class OwnerAuthorityTypes {

    private static final Class<?> AUTHORITY =
        loadTrusted(OWNER_AUTHORITY_CLASS_NAME);
    private static final Class<?> PERMIT =
        loadTrusted(OWNER_PERMIT_CLASS_NAME);

    private static Class<?> loadTrusted(String name) {
      try {
        return Class.forName(
            name,
            false,
            GraphAttemptCoordinator.class.getClassLoader());
      } catch (ClassNotFoundException failure) {
        throw new IllegalStateException(
            "trusted owner authority is not installed", failure);
      }
    }

    private OwnerAuthorityTypes() {}
  }

  public ParentAuthorized authorizeParent(
      Authorized authority,
      AgentRun running,
      Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.authorizeParent(
            authority.manifest,
            authority.cursor,
            running,
            occurredAt);
    return new ParentAuthorized(
        ownerToken, authority.manifest, cursor, running);
  }

  public ParentStarted startParent(
      ParentAuthorized authority, Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.startParent(
            authority.manifest,
            authority.cursor,
            authority.running,
            occurredAt);
    return new ParentStarted(
        ownerToken,
        authority.manifest,
        cursor,
        authority.running);
  }

  public ChildAuthorized authorizeChild(
      ParentStarted authority,
      AgentRun running,
      Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.authorizeChild(
            authority.manifest,
            authority.cursor,
            running,
            occurredAt);
    return new ChildAuthorized(
        ownerToken,
        authority.manifest,
        cursor,
        authority.parentRunning,
        running);
  }

  public ChildStarted startChild(
      ChildAuthorized authority, Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.startChild(
            authority.manifest,
            authority.cursor,
            authority.childRunning,
            occurredAt);
    return new ChildStarted(
        ownerToken,
        authority.manifest,
        cursor,
        authority.parentRunning,
        authority.childRunning);
  }

  public EgressAuthority consumeChildEgress(
      ChildStarted authority, Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.consumeChildEgress(
            authority.manifest,
            authority.cursor,
            occurredAt);
    return new EgressAuthority(
        ownerToken,
        authority.manifest,
        cursor,
        authority.parentRunning,
        authority.childRunning);
  }

  /**
   * Fails closed unless an opaque durable egress authority belongs to this
   * coordinator and to the exact server-owned manifest expected by the
   * composition root.
   *
   * <p>This check exposes no cursor, Store, credential or provider handle.
   */
  public synchronized void requireEgressManifest(
      EgressAuthority authority, GraphAttemptManifest expectedManifest) {
    Objects.requireNonNull(authority, "authority");
    requireOwner(authority.owner);
    if (!authority.manifest.equals(
        Objects.requireNonNull(expectedManifest, "expectedManifest"))) {
      throw new IllegalArgumentException(
          "child egress authority does not match the expected manifest");
    }
  }

  /**
   * Fails closed unless the exact egress capability has durably completed
   * every provider attribution required by its manifest.
   *
   * <p>This is a read-only typestate assertion for a narrower outer
   * authority. It does not expose or advance the durable cursor.
   */
  public synchronized void requireProviderAttributed(
      EgressAuthority authority, GraphAttemptManifest expectedManifest) {
    requireEgressManifest(authority, expectedManifest);
    requireEgress(authority, EgressStep.PROVIDER_ATTRIBUTED_2);
  }

  public synchronized void credentialReadStarted(
      EgressAuthority authority, Instant occurredAt) {
    requireEgress(authority, EgressStep.CONSUMED);
    authority.cursor =
        store.credentialReadStarted(
            authority.manifest,
            authority.cursor,
            occurredAt);
    authority.step =
        EgressStep.CREDENTIAL_READ_STARTED;
  }

  public synchronized void clientCreated(
      EgressAuthority authority, Instant occurredAt) {
    requireEgress(
        authority, EgressStep.CREDENTIAL_READ_STARTED);
    authority.cursor =
        store.clientCreated(
            authority.manifest,
            authority.cursor,
            occurredAt);
    authority.step = EgressStep.CLIENT_CREATED;
  }

  public synchronized void modelCreated(
      EgressAuthority authority, Instant occurredAt) {
    requireEgress(authority, EgressStep.CLIENT_CREATED);
    authority.cursor =
        store.modelCreated(
            authority.manifest,
            authority.cursor,
            occurredAt);
    authority.step = EgressStep.MODEL_CREATED;
  }

  public synchronized void providerIntent(
      EgressAuthority authority,
      GraphProviderIntent intent,
      Instant occurredAt) {
    Objects.requireNonNull(authority, "authority");
    Objects.requireNonNull(intent, "intent");
    requireOwner(authority.owner);
    int expectedOrdinal =
        switch (authority.step) {
          case MODEL_CREATED -> 1;
          case PROVIDER_ATTRIBUTED_1 -> 2;
          default ->
              throw new IllegalStateException(
                  "child egress authority is out of sequence");
        };
    if (intent.requestOrdinal() != expectedOrdinal
        || intent.requestOrdinal()
            > authority.manifest.maximumProviderRequests()) {
      throw new IllegalArgumentException(
          "provider intent ordinal does not match graph authority");
    }
    authority.cursor =
        store.providerIntent(
            authority.manifest,
            authority.cursor,
            intent,
            occurredAt);
    authority.pendingIntent = intent;
    authority.step = EgressStep.PROVIDER_PENDING;
  }

  public synchronized void providerAttributed(
      EgressAuthority authority,
      GraphProviderAttribution attribution,
      Instant occurredAt) {
    requireEgress(authority, EgressStep.PROVIDER_PENDING);
    Objects.requireNonNull(attribution, "attribution");
    GraphProviderIntent pending = authority.pendingIntent;
    if (pending == null
        || attribution.requestOrdinal()
            != pending.requestOrdinal()
        || !attribution.requestHash().equals(
            pending.requestHash())
        || !attribution.modelRequested().equals(
            pending.modelRequested())
        || !attribution.providerActor().equals(
            authority.manifest.childActor())
        || !attribution.pricingProfileFingerprint().equals(
            authority.manifest.pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider attribution does not match its pending intent");
    }
    authority.cursor =
        store.providerAttributed(
            authority.manifest,
            authority.cursor,
            attribution,
            occurredAt);
    authority.pendingIntent = null;
    authority.step =
        attribution.requestOrdinal() == 1
            ? EgressStep.PROVIDER_ATTRIBUTED_1
            : EgressStep.PROVIDER_ATTRIBUTED_2;
  }

  public synchronized void providerFailureAttributed(
      EgressAuthority authority,
      GraphProviderAttribution attribution,
      GraphAttributedFailureCode failureCode,
      Instant occurredAt) {
    requireEgress(authority, EgressStep.PROVIDER_PENDING);
    Objects.requireNonNull(attribution, "attribution");
    Objects.requireNonNull(failureCode, "failureCode");
    GraphProviderIntent pending = authority.pendingIntent;
    if (pending == null
        || attribution.requestOrdinal() != 2
        || attribution.requestOrdinal() != pending.requestOrdinal()
        || !attribution.requestHash().equals(pending.requestHash())
        || !attribution.modelRequested().equals(pending.modelRequested())
        || !attribution.providerActor().equals(
            authority.manifest.childActor())
        || !attribution.pricingProfileFingerprint().equals(
            authority.manifest.pricingProfileFingerprint())) {
      throw new IllegalArgumentException(
          "provider failure attribution does not match its pending intent");
    }
    authority.cursor =
        store.providerFailureAttributed(
            authority.manifest,
            authority.cursor,
            attribution,
            failureCode,
            occurredAt);
    authority.pendingIntent = null;
    authority.step = EgressStep.PROVIDER_ATTRIBUTED_2;
  }

  public synchronized ParentCompletionAuthority completeChild(
      EgressAuthority authority,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      Instant occurredAt) {
    requireEgress(
        authority, EgressStep.PROVIDER_ATTRIBUTED_2);
    GraphAttemptCursor cursor =
        store.completeChild(
            authority.manifest,
            authority.cursor,
            AgentRunContext.fromRunning(
                authority.parentRunning),
            terminalChild,
            candidate,
            workerResult,
            occurredAt);
    authority.step = EgressStep.CHILD_TERMINAL;
    return new ParentCompletionAuthority(
        ownerToken,
        authority.manifest,
        cursor,
        authority.parentRunning);
  }

  public synchronized GraphAttemptCursor completeParentAndSeal(
      ParentCompletionAuthority authority,
      AgentRun terminalParent,
      ArtifactLineage artifact,
      Instant occurredAt) {
    Objects.requireNonNull(authority, "authority");
    requireOwner(authority.owner);
    if (!authority.parentRunning.runId().equals(
            terminalParent.runId())
        || !authority.parentRunning.task().equals(
            terminalParent.task())) {
      throw new IllegalArgumentException(
          "terminal parent does not match its graph authority");
    }
    return store.completeParentAndSeal(
        authority.manifest,
        authority.cursor,
        terminalParent,
        artifact,
        occurredAt);
  }

  private void requireOwner(Object actual) {
    if (actual != ownerToken) {
      throw new IllegalArgumentException(
          "graph authority belongs to another coordinator");
    }
  }

  private void requireEgress(
      EgressAuthority authority, EgressStep expected) {
    Objects.requireNonNull(authority, "authority");
    requireOwner(authority.owner);
    if (authority.step != expected) {
      throw new IllegalStateException(
          "child egress authority is out of sequence");
    }
  }

  public interface InteractiveConsole {

    boolean realTty();

    String readLine(String prompt);
  }

  public static final class Authorized {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;

    private Authorized(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
    }
  }

  public static final class ParentStarted {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final AgentRun parentRunning;

    private ParentStarted(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun parentRunning) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.parentRunning = parentRunning;
    }
  }

  public static final class ParentAuthorized {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final AgentRun running;

    private ParentAuthorized(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun running) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.running = running;
    }
  }

  public static final class ChildAuthorized {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final AgentRun parentRunning;
    private final AgentRun childRunning;

    private ChildAuthorized(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun parentRunning,
        AgentRun childRunning) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.parentRunning = parentRunning;
      this.childRunning = childRunning;
    }
  }

  public static final class ChildStarted {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final AgentRun parentRunning;
    private final AgentRun childRunning;

    private ChildStarted(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun parentRunning,
        AgentRun childRunning) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.parentRunning = parentRunning;
      this.childRunning = childRunning;
    }
  }

  public static final class EgressAuthority {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private GraphAttemptCursor cursor;
    private final AgentRun parentRunning;
    private final AgentRun childRunning;
    private GraphProviderIntent pendingIntent;
    private EgressStep step = EgressStep.CONSUMED;

    private EgressAuthority(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun parentRunning,
        AgentRun childRunning) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.parentRunning = parentRunning;
      this.childRunning = childRunning;
    }
  }

  public static final class ParentCompletionAuthority {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;
    private final AgentRun parentRunning;

    private ParentCompletionAuthority(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor,
        AgentRun parentRunning) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
      this.parentRunning = parentRunning;
    }
  }

  public static final class OperatorApprovalException
      extends RuntimeException {

    private final String code;

    private OperatorApprovalException(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private enum EgressStep {
    CONSUMED,
    CREDENTIAL_READ_STARTED,
    CLIENT_CREATED,
    MODEL_CREATED,
    PROVIDER_PENDING,
    PROVIDER_ATTRIBUTED_1,
    PROVIDER_ATTRIBUTED_2,
    CHILD_TERMINAL
  }
}
