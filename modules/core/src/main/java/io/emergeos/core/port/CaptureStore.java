package io.emergeos.core.port;

import io.emergeos.core.domain.Capture;
import java.util.Objects;
import java.util.Optional;

public interface CaptureStore {

  SaveResult saveOrFindByNonce(Capture proposed);

  Optional<Capture> findOwned(String principalId, String captureId);

  record SaveResult(Capture capture, boolean created) {

    public SaveResult {
      Objects.requireNonNull(capture, "capture");
    }
  }
}
