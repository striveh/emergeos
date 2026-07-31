package io.emergeos.core.application;

import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.port.GraphAttemptStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

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

  private final GraphAttemptStore store;
  private final Object ownerToken = new Object();

  public GraphAttemptCoordinator(GraphAttemptStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Authorized approve(
      GraphAttemptManifest manifest,
      InteractiveConsole console,
      Clock clock) {
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
        ownerToken, authority.manifest, cursor);
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
        ownerToken, authority.manifest, cursor, running);
  }

  public ChildStarted startChild(
      ChildAuthorized authority, Instant occurredAt) {
    requireOwner(authority.owner);
    GraphAttemptCursor cursor =
        store.startChild(
            authority.manifest,
            authority.cursor,
            authority.running,
            occurredAt);
    return new ChildStarted(
        ownerToken, authority.manifest, cursor);
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
        ownerToken, authority.manifest, cursor);
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
    requireEgress(authority, EgressStep.MODEL_CREATED);
    authority.cursor =
        store.providerIntent(
            authority.manifest,
            authority.cursor,
            intent,
            occurredAt);
    authority.step = EgressStep.PROVIDER_PENDING;
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

    private ParentStarted(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
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
    private final AgentRun running;

    private ChildAuthorized(
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

  public static final class ChildStarted {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private final GraphAttemptCursor cursor;

    private ChildStarted(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
    }
  }

  public static final class EgressAuthority {

    private final Object owner;
    private final GraphAttemptManifest manifest;
    private GraphAttemptCursor cursor;
    private EgressStep step = EgressStep.CONSUMED;

    private EgressAuthority(
        Object owner,
        GraphAttemptManifest manifest,
        GraphAttemptCursor cursor) {
      this.owner = owner;
      this.manifest = manifest;
      this.cursor = cursor;
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
    PROVIDER_PENDING
  }
}
