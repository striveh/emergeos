package io.emergeos.core.port;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptVerification;

/** Minimal read-only capability for fresh graph verification. */
public interface GraphAttemptReader {

  GraphAttemptVerification findVerified(
      GraphAttemptManifest expected);
}
