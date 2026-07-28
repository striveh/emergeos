package io.emergeos.core.port;

import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import java.util.Objects;
import java.util.Optional;

public interface ArtifactLineageStore {

  ArtifactLineage create(ArtifactLineage proposed);

  RevisionResult compareAndSwap(
      String principalId,
      String artifactId,
      int expectedBaseVersion,
      String expectedBaseHash,
      ArtifactLineageEntry proposed);

  Optional<ArtifactLineage> findOwned(String principalId, String artifactId);

  sealed interface RevisionResult {

    record Revised(ArtifactLineage lineage) implements RevisionResult {

      public Revised {
        Objects.requireNonNull(lineage, "lineage");
      }
    }

    record Conflict(int currentVersion) implements RevisionResult {

      public Conflict {
        if (currentVersion < 1) {
          throw new IllegalArgumentException("currentVersion must be positive");
        }
      }
    }

    record NotFound() implements RevisionResult {}
  }
}
