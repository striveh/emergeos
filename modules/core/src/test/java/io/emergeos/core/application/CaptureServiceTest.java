package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.port.CaptureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaptureServiceTest {

  @Test
  void returnsTheCommittedOriginalForAReplayAndConflictsOnPayloadChange() {
    var store = new InMemoryCaptureStore();
    var sequence = new AtomicInteger();
    var service =
        new CaptureService(
            store,
            prefix -> prefix + "-" + sequence.incrementAndGet(),
            Clock.fixed(Instant.parse("2026-07-28T07:00:00Z"), ZoneOffset.UTC));
    var original =
        service.capture(
            new CaptureCommand(
                "owner-a", "nonce-1", "thought", "TEXT", "test", DataClass.PERSONAL));
    var replay =
        service.capture(
            new CaptureCommand(
                "owner-a", "nonce-1", "thought", "TEXT", "test", DataClass.PERSONAL));

    assertTrue(original.created());
    assertFalse(replay.created());
    assertEquals(original.capture(), replay.capture());
    assertThrows(
        CaptureNonceConflictException.class,
        () ->
            service.capture(
                new CaptureCommand(
                    "owner-a",
                    "nonce-1",
                    "different",
                    "TEXT",
                    "test",
                    DataClass.PERSONAL)));
    assertEquals(1, store.byNonce.size());
  }

  @Test
  void scopesNonceAndReadsByServerOwnedPrincipal() {
    var store = new InMemoryCaptureStore();
    var sequence = new AtomicInteger();
    var service =
        new CaptureService(
            store,
            prefix -> prefix + "-" + sequence.incrementAndGet(),
            Clock.fixed(Instant.parse("2026-07-28T07:00:00Z"), ZoneOffset.UTC));
    Capture ownerA =
        service
            .capture(
                new CaptureCommand(
                    "owner-a", "shared", "thought", "TEXT", "test", DataClass.PERSONAL))
            .capture();
    Capture ownerB =
        service
            .capture(
                new CaptureCommand(
                    "owner-b", "shared", "thought", "TEXT", "test", DataClass.PERSONAL))
            .capture();

    assertEquals(ownerA, service.get("owner-a", ownerA.captureId()));
    assertThrows(
        NoSuchElementException.class, () -> service.get("owner-b", ownerA.captureId()));
    assertThrows(
        NoSuchElementException.class, () -> service.get("owner-b", "missing-capture"));
    assertEquals(2, store.byNonce.size());
    assertFalse(ownerA.captureId().equals(ownerB.captureId()));
  }

  private static final class InMemoryCaptureStore implements CaptureStore {
    private final Map<String, Capture> byNonce = new ConcurrentHashMap<>();
    private final Map<String, Capture> byOwnerAndId = new ConcurrentHashMap<>();

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      String nonceKey = proposed.principalId() + "\n" + proposed.clientNonce();
      Capture stored = byNonce.putIfAbsent(nonceKey, proposed);
      if (stored == null) {
        byOwnerAndId.put(proposed.principalId() + "\n" + proposed.captureId(), proposed);
        return new SaveResult(proposed, true);
      }
      return new SaveResult(stored, false);
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      return Optional.ofNullable(byOwnerAndId.get(principalId + "\n" + captureId));
    }
  }
}
