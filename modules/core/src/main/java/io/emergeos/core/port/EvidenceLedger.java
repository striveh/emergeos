package io.emergeos.core.port;

import io.emergeos.core.domain.EvidenceEvent;
import java.util.Optional;

public interface EvidenceLedger {

  void append(EvidenceEvent event);

  Optional<EvidenceEvent> findEvidence(String evidenceId);
}
