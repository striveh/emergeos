package io.emergeos.core.port;

import io.emergeos.core.domain.Manifestation;
import java.util.Optional;

public interface ManifestationRepository {

  void save(Manifestation manifestation);

  Optional<Manifestation> findManifestation(String principalId, String manifestationId);
}
