package io.emergeos.core.port;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;

/** Minimal read-only capability for fresh V15/V16 overlay verification. */
public interface GraphExactPicoOverlayReader {

  GraphExactPicoOverlayVerification findVerified(
      GraphAttemptManifest expected);
}
