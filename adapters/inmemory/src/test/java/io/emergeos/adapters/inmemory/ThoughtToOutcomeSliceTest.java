package io.emergeos.adapters.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.ApproveActionCommand;
import io.emergeos.core.application.CaptureThoughtCommand;
import io.emergeos.core.application.ManifestationService;
import io.emergeos.core.application.ReviseArtifactCommand;
import io.emergeos.core.port.ActionExecutor;
import io.emergeos.core.port.ReflectionProposer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThoughtToOutcomeSliceTest {

  private ManifestationService service;
  private LocalDraftActionExecutor executor;
  private InMemoryJourneyStore store;
  private SequentialIdGenerator ids;
  private Clock clock;

  @BeforeEach
  void setUp() {
    store = new InMemoryJourneyStore();
    ids = new SequentialIdGenerator();
    clock = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);
    executor = new LocalDraftActionExecutor(ids);
    service = createService(executor, new DefaultReflectionProposer());
  }

  @Test
  void completesARevisedDraftWithOneReceiptAndAPendingReflectionCandidate() {
    var captured =
        service.capture(
            new CaptureThoughtCommand(
                "user-1", "让想法快速显现", "TEXT", "test", DataClass.PERSONAL));

    var revised =
        service.revise(
            captured.manifestationId(),
            new ReviseArtifactCommand("user-1", "让思想从种子变成可信的行动。"));

    var completed =
        service.approve(
            captured.manifestationId(),
            new ApproveActionCommand("user-1", revised.artifact().contentHash()));
    var replayed =
        service.approve(
            captured.manifestationId(),
            new ApproveActionCommand("user-1", revised.artifact().contentHash()));

    assertEquals("COMPLETED_WITH_RECEIPT", completed.status());
    assertEquals(completed.receipt().receiptId(), replayed.receipt().receiptId());
    assertEquals(1, executor.externalDraftCount());
    assertEquals("PENDING", completed.reflection().status());
    assertEquals("PROPOSED", completed.reflectionStatus());
    assertFalse(completed.reflection().appliedToSelfModel());
  }

  @Test
  void rejectsApprovalThatTargetsTheGeneratedVersionAfterARevision() {
    var captured =
        service.capture(
            new CaptureThoughtCommand(
                "user-1", "original", "TEXT", "test", DataClass.PERSONAL));
    service.revise(
        captured.manifestationId(), new ReviseArtifactCommand("user-1", "revised"));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.approve(
                captured.manifestationId(),
                new ApproveActionCommand("user-1", captured.artifact().contentHash())));
    assertEquals(0, executor.externalDraftCount());
  }

  @Test
  void rejectsSensitiveContentUntilASecurePersistenceAdapterExists() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.capture(
                new CaptureThoughtCommand(
                    "user-1", "private secret", "TEXT", "test", DataClass.SECRET)));
  }

  @Test
  void recoversAnAmbiguousExternalSuccessWithoutCreatingASecondDraft() {
    var failFirstResponse = new AtomicBoolean(true);
    ActionExecutor flakyResponse =
        (plan, artifact, capability, now) -> {
          var receipt = executor.execute(plan, artifact, capability, now);
          if (failFirstResponse.getAndSet(false)) {
            throw new IllegalStateException("simulated response loss after external write");
          }
          return receipt;
        };
    service = createService(flakyResponse, new DefaultReflectionProposer());
    var captured =
        service.capture(
            new CaptureThoughtCommand(
                "user-1", "recover me", "TEXT", "test", DataClass.PERSONAL));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.approve(
                captured.manifestationId(),
                new ApproveActionCommand("user-1", captured.artifact().contentHash())));
    assertEquals("EXECUTING", service.get(captured.manifestationId(), "user-1").status());
    assertEquals(1, executor.externalDraftCount());

    var recovered =
        service.approve(
            captured.manifestationId(),
            new ApproveActionCommand("user-1", captured.artifact().contentHash()));
    assertEquals("COMPLETED_WITH_RECEIPT", recovered.status());
    assertEquals(1, executor.externalDraftCount());
  }

  @Test
  void reflectionFailureDoesNotEraseASuccessfulReceipt() {
    ReflectionProposer brokenReflection =
        (candidateId, principalId, generated, approved, receipt, run, now) -> {
          throw new IllegalStateException("simulated reflection failure");
        };
    service = createService(executor, brokenReflection);
    var captured =
        service.capture(
            new CaptureThoughtCommand(
                "user-1", "finish before reflection", "TEXT", "test", DataClass.PERSONAL));

    var completed =
        service.approve(
            captured.manifestationId(),
            new ApproveActionCommand("user-1", captured.artifact().contentHash()));

    assertEquals("COMPLETED_WITH_RECEIPT", completed.status());
    assertNotNull(completed.receipt());
    assertEquals("FAILED", completed.reflectionStatus());
    assertNotNull(completed.reflectionFailure());
    assertNull(completed.reflection());
  }

  @Test
  void doesNotRevealAManifestationToAnotherPrincipal() {
    var captured =
        service.capture(
            new CaptureThoughtCommand(
                "user-1", "owned thought", "TEXT", "test", DataClass.PERSONAL));

    assertThrows(
        NoSuchElementException.class,
        () -> service.get(captured.manifestationId(), "user-2"));
    assertEquals(
        captured.manifestationId(),
        service.get(captured.manifestationId(), "user-1").manifestationId());
  }

  private ManifestationService createService(
      ActionExecutor actionExecutor, ReflectionProposer reflectionProposer) {
    return new ManifestationService(
        store,
        store,
        store,
        store,
        store,
        new LocalWorkingSelfProjector(),
        new TemplateArtifactGenerator(),
        new DeterministicActionPolicy(),
        actionExecutor,
        reflectionProposer,
        ids,
        clock);
  }

  private static final class SequentialIdGenerator implements io.emergeos.core.port.IdGenerator {
    private int value;

    @Override
    public String next(String prefix) {
      return prefix + "-" + ++value;
    }
  }
}
