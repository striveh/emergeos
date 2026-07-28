package io.emergeos.core.port;

import io.emergeos.core.domain.ArtifactVersion;
import java.util.Optional;

public interface ArtifactRepository {

  void save(ArtifactVersion artifact);

  Optional<ArtifactVersion> find(String artifactId, int version);
}

