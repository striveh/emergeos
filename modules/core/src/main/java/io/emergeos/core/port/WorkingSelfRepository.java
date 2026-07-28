package io.emergeos.core.port;

import io.emergeos.core.domain.WorkingSelf;
import java.util.Optional;

public interface WorkingSelfRepository {

  void save(WorkingSelf workingSelf);

  Optional<WorkingSelf> findWorkingSelf(String snapshotId);
}

