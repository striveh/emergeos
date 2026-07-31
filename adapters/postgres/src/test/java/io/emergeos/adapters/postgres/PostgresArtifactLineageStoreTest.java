package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.ArtifactLineageService;
import io.emergeos.core.application.ArtifactRevisionConflictException;
import io.emergeos.core.application.ReviseArtifactLineageCommand;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.ArtifactLineageStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresArtifactLineageStoreTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_artifacts")
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
  void clearArtifacts() {
    jdbc.sql(
            "TRUNCATE TABLE agent_trace_events, agent_run_resource_bindings, "
                + "agent_worker_results, agent_runs, "
                + "action_receipts, action_attempt_transitions, action_attempts, "
                + "artifact_versions, artifacts, captures")
        .update();
  }

  @AfterAll
  static void releaseReferences() {
    jdbc = null;
    transactionManager = null;
    dataSource = null;
  }

  @Test
  void returnsDatabaseCanonicalLineageAndScopesReadsByPrincipal() {
    ownCapture("owner-a", "cap-1");
    ArtifactLineage proposed =
        initialLineage(
            "art-1",
            "owner-a",
            "cap-1",
            "artifact version one",
            Instant.parse("2026-07-28T08:00:00.123456789Z"));

    ArtifactLineage created = store().create(proposed);

    assertEquals(Instant.parse("2026-07-28T08:00:00.123457Z"), created.current().createdAt());
    assertEquals(created, store().findOwned("owner-a", "art-1").orElseThrow());
    assertTrue(store().findOwned("owner-b", "art-1").isEmpty());
    assertTrue(store().findOwned("owner-b", "missing-artifact").isEmpty());
  }

  @Test
  void versionAndHashMustBothMatchTheCurrentHead() {
    ownCapture("owner-a", "cap-1");
    ArtifactLineage created =
        store()
            .create(
                initialLineage(
                    "art-matrix",
                    "owner-a",
                    "cap-1",
                    "artifact version one",
                    Instant.parse("2026-07-28T08:00:00Z")));
    String h1 = created.current().contentHash();
    String wrongHash = "0".repeat(64);

    assertConflict(store(), created, 1, wrongHash, 1);
    assertConflict(store(), created, 2, h1, 1);
    assertConflict(store(), created, 2, wrongHash, 1);

    ArtifactLineageStore.RevisionResult foreign =
        store()
            .compareAndSwap(
                "owner-b",
                created.artifactId(),
                1,
                h1,
                revision(2, "foreign exact-base overwrite", 1, h1));
    assertInstanceOf(ArtifactLineageStore.RevisionResult.NotFound.class, foreign);
    assertEquals(1, versionCount(created.artifactId()));
    assertEquals(1, currentVersion(created.artifactId()));
  }

  @Test
  void concurrentAdaptersProduceExactlyOneWinnerAndOneConflict() throws Exception {
    ownCapture("owner-a", "cap-1");
    ArtifactLineage created =
        store()
            .create(
                initialLineage(
                    "art-race",
                    "owner-a",
                    "cap-1",
                    "artifact version one",
                    Instant.parse("2026-07-28T08:00:00Z")));
    var first = service(store(), Instant.parse("2026-07-28T08:01:00.123456789Z"));
    var second = service(store(), Instant.parse("2026-07-28T08:01:00.987654321Z"));
    var start = new CountDownLatch(1);

    List<Attempt> attempts =
        invokeConcurrently(
            () -> {
              start.await();
              return attempt(
                  () ->
                      first.revise(
                          new ReviseArtifactLineageCommand(
                              "owner-a",
                              created.artifactId(),
                              "artifact version two A",
                              1,
                              created.current().contentHash())));
            },
            () -> {
              start.await();
              return attempt(
                  () ->
                      second.revise(
                          new ReviseArtifactLineageCommand(
                              "owner-a",
                              created.artifactId(),
                              "artifact version two B",
                              1,
                              created.current().contentHash())));
            },
            start);

    assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
    assertEquals(1, attempts.stream().filter(Attempt::conflicted).count());
    ArtifactLineage committed = store().findOwned("owner-a", created.artifactId()).orElseThrow();
    assertEquals(2, committed.versions().size());
    assertEquals(2, committed.current().version());
    assertEquals(1, committed.current().baseVersion());
    assertEquals(created.current().contentHash(), committed.current().baseHash());
    assertEquals(2, currentVersion(created.artifactId()));
    assertEquals(2, versionCount(created.artifactId()));
  }

  @Test
  void rollsBackTheHeadWhenTheLineageInsertFails() {
    ownCapture("owner-a", "cap-1");
    ArtifactLineage created =
        store()
            .create(
                initialLineage(
                    "art-rollback",
                    "owner-a",
                    "cap-1",
                    "artifact version one",
                    Instant.parse("2026-07-28T08:00:00Z")));
    installFailingVersionTrigger();
    try {
      assertThrows(
          DataAccessException.class,
          () ->
              store()
                  .compareAndSwap(
                      "owner-a",
                      created.artifactId(),
                      1,
                      created.current().contentHash(),
                      revision(
                          2,
                          "artifact version two rejected by trigger",
                          1,
                          created.current().contentHash())));
    } finally {
      removeFailingVersionTrigger();
    }

    ArtifactLineage unchanged = store().findOwned("owner-a", created.artifactId()).orElseThrow();
    assertEquals(1, unchanged.current().version());
    assertEquals(created.current().contentHash(), unchanged.current().contentHash());
    assertEquals(1, unchanged.versions().size());
    assertEquals(1, currentVersion(created.artifactId()));
    assertEquals(1, versionCount(created.artifactId()));
  }

  @Test
  void databaseRejectsALaterVersionWithoutACompleteBasePair() {
    ownCapture("owner-a", "cap-1");
    ArtifactLineage created =
        store()
            .create(
                initialLineage(
                    "art-null-base",
                    "owner-a",
                    "cap-1",
                    "artifact version one",
                    Instant.parse("2026-07-28T08:00:00Z")));
    String content = "invalid version two";

    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO artifact_versions (
                        principal_id, artifact_id, version, content, content_hash,
                        base_version, base_hash, created_at
                    ) VALUES (
                        'owner-a', 'art-null-base', 2, :content, :contentHash,
                        NULL, NULL, TIMESTAMPTZ '2026-07-28T08:01:00Z'
                    )
                    """)
                .param("content", content)
                .param("contentHash", ContentHashes.sha256(content))
                .update());
    assertEquals(1, currentVersion(created.artifactId()));
    assertEquals(1, versionCount(created.artifactId()));
  }

  private static void assertConflict(
      PostgresArtifactLineageStore store,
      ArtifactLineage created,
      int expectedVersion,
      String expectedHash,
      int currentVersion) {
    ArtifactLineageStore.RevisionResult result =
        store.compareAndSwap(
            "owner-a",
            created.artifactId(),
            expectedVersion,
            expectedHash,
            revision(
                expectedVersion + 1,
                "rejected revision " + expectedVersion + expectedHash.charAt(0),
                expectedVersion,
                expectedHash));
    ArtifactLineageStore.RevisionResult.Conflict conflict =
        assertInstanceOf(ArtifactLineageStore.RevisionResult.Conflict.class, result);
    assertEquals(currentVersion, conflict.currentVersion());
    assertEquals(1, versionCount(created.artifactId()));
    assertEquals(1, currentVersion(created.artifactId()));
  }

  private static ArtifactLineage initialLineage(
      String artifactId,
      String principalId,
      String captureId,
      String content,
      Instant createdAt) {
    return new ArtifactLineage(
        artifactId,
        principalId,
        captureId,
        List.of(
            new ArtifactLineageEntry(
                1, content, ContentHashes.sha256(content), null, null, createdAt)));
  }

  private static ArtifactLineageEntry revision(
      int version, String content, int baseVersion, String baseHash) {
    return new ArtifactLineageEntry(
        version,
        content,
        ContentHashes.sha256(content),
        baseVersion,
        baseHash,
        Instant.parse("2026-07-28T08:01:00Z"));
  }

  private static void ownCapture(String principalId, String captureId) {
    String content = "synthetic source thought";
    new PostgresCaptureStore(dataSource, transactionManager)
        .saveOrFindByNonce(
            new Capture(
                captureId,
                principalId,
                "source-" + captureId,
                CaptureRequestHashes.sha256(
                    content, CaptureSourceType.TEXT, "s2-postgres-test", DataClass.PERSONAL),
                content,
                CaptureSourceType.TEXT,
                "s2-postgres-test",
                DataClass.PERSONAL,
                Instant.parse("2026-07-28T07:00:00Z")));
  }

  private static ArtifactLineageService service(
      PostgresArtifactLineageStore store, Instant instant) {
    return new ArtifactLineageService(
        store,
        new PostgresCaptureStore(dataSource, transactionManager),
        prefix -> prefix + "-unused",
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static PostgresArtifactLineageStore store() {
    return new PostgresArtifactLineageStore(dataSource, transactionManager);
  }

  private static Attempt attempt(Callable<ArtifactLineage> operation) throws Exception {
    try {
      operation.call();
      return new Attempt(true, false);
    } catch (ArtifactRevisionConflictException conflict) {
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

  private static int versionCount(String artifactId) {
    return jdbc.sql(
            "SELECT count(*) FROM artifact_versions WHERE artifact_id = :artifactId")
        .param("artifactId", artifactId)
        .query(Integer.class)
        .single();
  }

  private static int currentVersion(String artifactId) {
    return jdbc.sql("SELECT current_version FROM artifacts WHERE artifact_id = :artifactId")
        .param("artifactId", artifactId)
        .query(Integer.class)
        .single();
  }

  private static void installFailingVersionTrigger() {
    jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION fail_s2_version_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
              IF NEW.artifact_id = 'art-rollback' AND NEW.version = 2 THEN
                RAISE EXCEPTION 'synthetic S2 lineage insert failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """)
        .update();
    jdbc.sql(
            """
            CREATE TRIGGER fail_s2_version_insert_trigger
            BEFORE INSERT ON artifact_versions
            FOR EACH ROW
            EXECUTE FUNCTION fail_s2_version_insert()
            """)
        .update();
  }

  private static void removeFailingVersionTrigger() {
    jdbc.sql("DROP TRIGGER IF EXISTS fail_s2_version_insert_trigger ON artifact_versions")
        .update();
    jdbc.sql("DROP FUNCTION IF EXISTS fail_s2_version_insert()").update();
  }

  private record Attempt(boolean succeeded, boolean conflicted) {}
}
