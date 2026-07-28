package io.emergeos.core.port;

import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.Receipt;
import io.emergeos.core.domain.ReflectionCandidate;
import java.time.Instant;

@FunctionalInterface
public interface ReflectionProposer {

  ReflectionCandidate propose(
      String candidateId,
      String principalId,
      ArtifactVersion generated,
      ArtifactVersion approved,
      Receipt receipt,
      String proposedByRun,
      Instant now);
}

