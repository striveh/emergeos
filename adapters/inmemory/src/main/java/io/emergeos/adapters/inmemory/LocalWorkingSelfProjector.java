package io.emergeos.adapters.inmemory;

import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.WorkingSelf;
import io.emergeos.core.port.WorkingSelfProjector;
import java.time.Instant;
import java.util.List;

public final class LocalWorkingSelfProjector implements WorkingSelfProjector {

  @Override
  public WorkingSelf project(
      String snapshotId, String principalId, EvidenceEvent evidence, Instant createdAt) {
    var constraints =
        List.of(
            "Do not publish without explicit approval",
            "Do not treat a single interaction as a stable personality trait");
    var hashMaterial =
        principalId + "|self-0|" + evidence.id() + "|" + String.join("|", constraints);
    return new WorkingSelf(
        snapshotId,
        principalId,
        "self-0",
        List.of(evidence.id()),
        List.of(),
        constraints,
        ContentHashes.sha256(hashMaterial),
        createdAt);
  }
}

