package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class CaptureService {

  private final CaptureStore captureStore;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public CaptureService(CaptureStore captureStore, IdGenerator idGenerator, Clock clock) {
    this.captureStore = Objects.requireNonNull(captureStore, "captureStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CaptureOutcome capture(CaptureCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.dataClass() == DataClass.SENSITIVE || command.dataClass() == DataClass.SECRET) {
      throw new IllegalArgumentException(
          "restart-safe Capture does not accept SENSITIVE or SECRET content");
    }
    var proposed =
        new Capture(
            idGenerator.next("cap"),
            command.principalId(),
            command.clientNonce(),
            command.requestHash(),
            command.content(),
            command.sourceType(),
            command.sourceRef(),
            command.dataClass(),
            clock.instant());
    CaptureStore.SaveResult stored = captureStore.saveOrFindByNonce(proposed);
    if (!stored.capture().requestHash().equals(command.requestHash())) {
      throw new CaptureNonceConflictException();
    }
    return new CaptureOutcome(stored.capture(), stored.created());
  }

  public Capture get(String principalId, String captureId) {
    return captureStore
        .findOwned(principalId, captureId)
        .orElseThrow(() -> new NoSuchElementException("Capture not found"));
  }
}
