package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.CaptureCommand;
import io.emergeos.core.application.CaptureNonceConflictException;
import io.emergeos.core.application.CaptureOutcome;
import io.emergeos.core.application.CaptureService;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresCaptureStoreTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_adapter")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static DriverManagerDataSource dataSource;
  private static DataSourceTransactionManager transactionManager;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    transactionManager = new DataSourceTransactionManager(dataSource);
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void clearCaptures() {
    jdbc.sql("TRUNCATE TABLE artifact_versions, artifacts, captures").update();
  }

  @AfterAll
  static void releaseReferences() {
    jdbc = null;
    transactionManager = null;
    dataSource = null;
  }

  @Test
  void databaseUniquenessReturnsTheOriginalCaptureForTheSamePrincipalNonceAndHash() {
    var store = store();
    Capture original = capture("cap-original", "owner-a", "nonce-1", "thought");
    Capture laterCandidate = capture("cap-later", "owner-a", "nonce-1", "thought");

    var first = store.saveOrFindByNonce(original);
    var replay = store.saveOrFindByNonce(laterCandidate);

    assertTrue(first.created());
    assertFalse(replay.created());
    assertEquals(original, replay.capture());
    assertEquals(1, captureCount());
  }

  @Test
  void returnsTheDatabaseCanonicalTimestampOnTheInitialWriteAndEveryReplay() {
    var store = store();
    Capture proposed =
        captureAt(
            "cap-nanoseconds",
            "owner-a",
            "timestamp-precision",
            "thought",
            Instant.parse("2026-07-28T07:00:00.123456789Z"));

    Capture first = store.saveOrFindByNonce(proposed).capture();
    Capture replay =
        store
            .saveOrFindByNonce(
                captureAt(
                    "cap-replay",
                    "owner-a",
                    "timestamp-precision",
                    "thought",
                    Instant.parse("2026-07-28T07:00:01Z")))
            .capture();

    assertEquals(Instant.parse("2026-07-28T07:00:00.123457Z"), first.capturedAt());
    assertEquals(first, replay);
    assertEquals(first, store.findOwned("owner-a", first.captureId()).orElseThrow());
  }

  @Test
  void nonceAndReadsAreScopedByPrincipal() {
    var store = store();
    Capture ownerA = capture("cap-a", "owner-a", "shared-nonce", "thought");
    Capture ownerB = capture("cap-b", "owner-b", "shared-nonce", "thought");

    store.saveOrFindByNonce(ownerA);
    store.saveOrFindByNonce(ownerB);

    assertEquals(ownerA, store.findOwned("owner-a", ownerA.captureId()).orElseThrow());
    assertTrue(store.findOwned("owner-b", ownerA.captureId()).isEmpty());
    assertTrue(store.findOwned("owner-b", "missing-capture").isEmpty());
    assertEquals(2, captureCount());
  }

  @Test
  void concurrentSameHashRequestsAcrossAdaptersConvergeOnOneOriginal() throws Exception {
    var firstService = service(store());
    var secondService = service(store());
    var command =
        new CaptureCommand(
            "owner-a", "concurrent-same", "thought", "TEXT", "test", DataClass.PERSONAL);
    var start = new CountDownLatch(1);

    List<CaptureOutcome> outcomes =
        invokeConcurrently(
            () -> {
              start.await();
              return firstService.capture(command);
            },
            () -> {
              start.await();
              return secondService.capture(command);
            },
            start);

    assertEquals(outcomes.get(0).capture(), outcomes.get(1).capture());
    assertEquals(1, outcomes.stream().filter(CaptureOutcome::created).count());
    assertEquals(1, captureCount());
  }

  @Test
  void concurrentDifferentHashRequestsHaveOneWinnerAndOneExplicitConflict() throws Exception {
    var firstService = service(store());
    var secondService = service(store());
    var start = new CountDownLatch(1);

    List<Attempt> attempts =
        invokeConcurrently(
            () -> {
              start.await();
              return attempt(
                  () ->
                      firstService.capture(
                          new CaptureCommand(
                              "owner-a",
                              "concurrent-conflict",
                              "thought-a",
                              "TEXT",
                              "test",
                              DataClass.PERSONAL)));
            },
            () -> {
              start.await();
              return attempt(
                  () ->
                      secondService.capture(
                          new CaptureCommand(
                              "owner-a",
                              "concurrent-conflict",
                              "thought-b",
                              "TEXT",
                              "test",
                              DataClass.PERSONAL)));
            },
            start);

    assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
    assertEquals(1, attempts.stream().filter(Attempt::conflicted).count());
    assertEquals(1, captureCount());
  }

  @Test
  void differentHashReplayDoesNotAlterTheCommittedOriginal() {
    var store = store();
    var service = service(store);
    Capture original =
        service
            .capture(
                new CaptureCommand(
                    "owner-a", "conflict", "thought-a", "TEXT", "test", DataClass.PERSONAL))
            .capture();

    assertThrows(
        CaptureNonceConflictException.class,
        () ->
            service.capture(
                new CaptureCommand(
                    "owner-a", "conflict", "thought-b", "TEXT", "test", DataClass.PERSONAL)));
    assertEquals(original, store.findOwned("owner-a", original.captureId()).orElseThrow());
    assertEquals(1, captureCount());
  }

  private static Capture capture(
      String captureId, String principalId, String clientNonce, String content) {
    return captureAt(
        captureId,
        principalId,
        clientNonce,
        content,
        Instant.parse("2026-07-28T07:00:00Z"));
  }

  private static Capture captureAt(
      String captureId,
      String principalId,
      String clientNonce,
      String content,
      Instant capturedAt) {
    return new Capture(
        captureId,
        principalId,
        clientNonce,
        CaptureRequestHashes.sha256(
            content, CaptureSourceType.TEXT, "test", DataClass.PERSONAL),
        content,
        CaptureSourceType.TEXT,
        "test",
        DataClass.PERSONAL,
        capturedAt);
  }

  private static CaptureService service(PostgresCaptureStore store) {
    return new CaptureService(
        store,
        prefix -> prefix + "-" + UUID.randomUUID(),
        Clock.fixed(Instant.parse("2026-07-28T07:00:00Z"), ZoneOffset.UTC));
  }

  private static PostgresCaptureStore store() {
    return new PostgresCaptureStore(dataSource, transactionManager);
  }

  private static int captureCount() {
    return jdbc.sql("SELECT count(*) FROM captures").query(Integer.class).single();
  }

  private static Attempt attempt(Callable<CaptureOutcome> operation) throws Exception {
    try {
      operation.call();
      return new Attempt(true, false);
    } catch (CaptureNonceConflictException conflict) {
      return new Attempt(false, true);
    }
  }

  private static <T> List<T> invokeConcurrently(
      Callable<T> first, Callable<T> second, CountDownLatch start) throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(first);
      var secondFuture = executor.submit(second);
      start.countDown();
      return List.of(firstFuture.get(), secondFuture.get());
    }
  }

  private record Attempt(boolean succeeded, boolean conflicted) {}
}
