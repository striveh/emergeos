package io.emergeos.core.port;

import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.WorkingSelf;
import java.time.Instant;

@FunctionalInterface
public interface WorkingSelfProjector {

  WorkingSelf project(
      String snapshotId, String principalId, EvidenceEvent evidence, Instant createdAt);
}

