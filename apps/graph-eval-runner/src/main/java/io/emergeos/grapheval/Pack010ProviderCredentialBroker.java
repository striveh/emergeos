package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.core.application.GraphAttemptCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dormant Pack010 credential boundary.
 *
 * <p>The shipping Main does not reference this class. A future reviewed
 * composition may reach the exact environment credential only after it owns
 * both an unforgeable real-TTY permit and the matching durable child-egress
 * typestate. The permit is burned before any credential read.
 */
final class Pack010ProviderCredentialBroker {

  private static final int MAX_CREDENTIAL_CHARS = 4_096;

  CredentialLease readAfterDurableEgress(
      Pack010GraphPreflight.Result preflight,
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.ApprovedHandoff ownerHandoff,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egressAuthority,
      OwnerTtyGraphAuthority.Pack010Revision expectedRevision,
      Clock clock) {
    Objects.requireNonNull(preflight, "preflight");
    Objects.requireNonNull(ownerAuthority, "ownerAuthority");
    Objects.requireNonNull(ownerHandoff, "ownerHandoff");
    Objects.requireNonNull(coordinator, "coordinator");
    Objects.requireNonNull(egressAuthority, "egressAuthority");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    Objects.requireNonNull(clock, "clock");

    int repetition = expectedRevision.ordinal() + 1;
    var firstRequest =
        new io.emergeos.core.domain.GraphProviderIntent(
            1,
            Pack010GraphEvalCatalog.computedFirstRequestHash(
                repetition),
            Pack010GraphEvalCatalog.workerProfile(repetition)
                .modelRequested());
    OwnerTtyGraphAuthority.ProviderSessionIntent sessionIntent =
        ownerAuthority.claimProviderSessionIntent(
            ownerHandoff,
            coordinator,
            egressAuthority,
            expectedRevision,
            firstRequest);
    OwnerTtyGraphAuthority.Pack010Revision revision =
        ownerAuthority.consumeProviderSessionIntent(
            sessionIntent,
            ownerHandoff,
            coordinator,
            egressAuthority);
    Instant expiresAt =
        ownerAuthority.requireProviderSessionFresh(
            sessionIntent,
            coordinator,
            egressAuthority,
            expectedRevision);
    if (revision != expectedRevision
        || ownerAuthority.consumeEgress(
                ownerHandoff, coordinator, egressAuthority)
            != expectedRevision) {
      throw new CredentialRejected(
          "PROVIDER_SESSION_REVISION_MISMATCH");
    }
    coordinator.requireEgressManifest(
        egressAuthority,
        Pack010GraphEvalCatalog.manifest(repetition));
    ownerAuthority.requireProviderSessionFresh(
        sessionIntent,
        coordinator,
        egressAuthority,
        expectedRevision);
    coordinator.credentialReadStarted(
        egressAuthority, clock.instant());
    return new CredentialLease(
        revision,
        ownerAuthority,
        sessionIntent,
        expiresAt,
        coordinator,
        egressAuthority,
        null);
  }

  private static boolean validCredential(String credential) {
    if (credential == null
        || credential.isBlank()
        || credential.length() > MAX_CREDENTIAL_CHARS) {
      return false;
    }
    for (int index = 0; index < credential.length(); index++) {
      char value = credential.charAt(index);
      if (Character.isISOControl(value)
          || Character.isWhitespace(value)) {
        return false;
      }
    }
    return true;
  }

  static final class CredentialLease {

    private static final String CREDENTIAL_NAME = "OPENAI_API_KEY";

    private final OwnerTtyGraphAuthority.Pack010Revision revision;
    private final OwnerTtyGraphAuthority ownerAuthority;
    private final OwnerTtyGraphAuthority.ProviderSessionIntent sessionIntent;
    private final Instant expiresAt;
    private final GraphAttemptCoordinator coordinator;
    private final GraphAttemptCoordinator.EgressAuthority
        egressAuthority;
    private final String syntheticCredential;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private CredentialLease(
        OwnerTtyGraphAuthority.Pack010Revision revision,
        OwnerTtyGraphAuthority ownerAuthority,
        OwnerTtyGraphAuthority.ProviderSessionIntent sessionIntent,
        Instant expiresAt,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egressAuthority,
        String syntheticCredential) {
      this.revision = Objects.requireNonNull(revision, "revision");
      this.ownerAuthority = ownerAuthority;
      this.sessionIntent = sessionIntent;
      this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
      this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
      this.egressAuthority =
          Objects.requireNonNull(egressAuthority, "egressAuthority");
      this.syntheticCredential = syntheticCredential;
      if ((ownerAuthority == null) != (sessionIntent == null)
          || (ownerAuthority == null) == (syntheticCredential == null)) {
        throw new IllegalArgumentException(
            "credential lease origin is not exact");
      }
    }

    /** Test-only reflection target; production never calls this overload. */
    private CredentialLease(
        OwnerTtyGraphAuthority.Pack010Revision revision,
        GraphAttemptCoordinator coordinator,
        GraphAttemptCoordinator.EgressAuthority egressAuthority,
        String syntheticCredential) {
      this(
          revision,
          null,
          null,
          Instant.MAX,
          coordinator,
          egressAuthority,
          Objects.requireNonNull(syntheticCredential, "syntheticCredential"));
    }

    OwnerTtyGraphAuthority.Pack010Revision revision() {
      return revision;
    }

    String claim(
        GraphAttemptCoordinator expectedCoordinator,
        GraphAttemptCoordinator.EgressAuthority
            expectedEgressAuthority,
        Clock clock) {
      requireFresh(
          expectedCoordinator, expectedEgressAuthority, clock);
      if (coordinator != expectedCoordinator
          || egressAuthority != expectedEgressAuthority
          || !claimed.compareAndSet(false, true)) {
        throw new CredentialRejected(
            "PROVIDER_CREDENTIAL_AUTHORITY_OR_REPLAY");
      }
      String credential =
          syntheticCredential == null
              ? System.getenv(CREDENTIAL_NAME)
              : syntheticCredential;
      if (!validCredential(credential)) {
        throw new CredentialRejected("PROVIDER_CREDENTIAL_MISSING");
      }
      return credential;
    }

    void requireFresh(
        GraphAttemptCoordinator expectedCoordinator,
        GraphAttemptCoordinator.EgressAuthority expectedEgressAuthority,
        Clock clock) {
      Objects.requireNonNull(clock, "clock");
      if (coordinator != expectedCoordinator
          || egressAuthority != expectedEgressAuthority
          || !clock.instant().isBefore(expiresAt)) {
        throw new CredentialRejected(
            "PROVIDER_SESSION_CAPABILITY_EXPIRED_OR_MISMATCH");
      }
      if (ownerAuthority != null) {
        Instant verifiedExpiry =
            ownerAuthority.requireProviderSessionFresh(
                sessionIntent,
                expectedCoordinator,
                expectedEgressAuthority,
                revision);
        if (!expiresAt.equals(verifiedExpiry)) {
          throw new CredentialRejected(
              "PROVIDER_SESSION_EXPIRY_DRIFT");
        }
      }
    }
  }

  static final class CredentialRejected extends RuntimeException {

    private CredentialRejected(String code) {
      super(code, null, false, false);
    }
  }
}
