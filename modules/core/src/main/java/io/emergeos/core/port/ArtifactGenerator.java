package io.emergeos.core.port;

import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.WorkingSelf;
import java.time.Instant;

@FunctionalInterface
public interface ArtifactGenerator {

  ArtifactVersion generate(
      String artifactId, EvidenceEvent evidence, WorkingSelf workingSelf, Instant createdAt);
}

