package io.emergeos.core.domain;

import java.util.Objects;

/** Four-way result of a fresh read-only V15/V16 overlay verification. */
public sealed interface GraphExactPicoOverlayVerification
    permits GraphExactPicoOverlayVerification.Missing,
        GraphExactPicoOverlayVerification.Required,
        GraphExactPicoOverlayVerification.Attributed,
        GraphExactPicoOverlayVerification.Invalid {

  record Missing() implements GraphExactPicoOverlayVerification {}

  record Required(GraphExactPicoOverlayRequirement requirement)
      implements GraphExactPicoOverlayVerification {

    public Required {
      requirement = Objects.requireNonNull(requirement, "requirement");
    }
  }

  record Attributed(GraphExactPicoOverlaySnapshot snapshot)
      implements GraphExactPicoOverlayVerification {

    public Attributed {
      snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  record Invalid(InvalidReason reason)
      implements GraphExactPicoOverlayVerification {

    public Invalid {
      reason = Objects.requireNonNull(reason, "reason");
    }
  }

  enum InvalidReason {
    EXPECTED_MANIFEST_MISMATCH,
    EXACT_REQUIREMENT_INVALID,
    EXACT_OVERLAY_PARTIAL,
    EXACT_OVERLAY_INVALID
  }
}
