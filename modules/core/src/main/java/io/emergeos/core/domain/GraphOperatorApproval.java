package io.emergeos.core.domain;

import io.emergeos.contracts.IntegrityHashes;
import java.util.Objects;

/** Safe durable owner-approval evidence; never stores the response text. */
public record GraphOperatorApproval(
    String actor, String challengeHash) {

  public static final String OWNER_TTY = "OWNER_TTY";
  public static final String CHALLENGE_PREFIX =
      "CONFIRM EMERGEOS GRAPH ATTEMPT ";

  public GraphOperatorApproval {
    actor = GraphAttemptDomains.safeName(actor, "approval actor");
    challengeHash =
        GraphAttemptDomains.hash(
            challengeHash, "challengeHash");
  }

  public static String expectedChallenge(
      GraphAttemptManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    return CHALLENGE_PREFIX
        + manifest.attemptId()
        + " SLOT "
        + manifest.executionSlotId();
  }

  public static GraphOperatorApproval ownerTty(
      GraphAttemptManifest manifest) {
    return new GraphOperatorApproval(
        OWNER_TTY,
        IntegrityHashes.utf8ContentHash(
            expectedChallenge(manifest)));
  }

  public boolean matches(GraphAttemptManifest manifest) {
    return OWNER_TTY.equals(actor)
        && IntegrityHashes.utf8ContentHash(
                expectedChallenge(manifest))
            .equals(challengeHash);
  }
}
