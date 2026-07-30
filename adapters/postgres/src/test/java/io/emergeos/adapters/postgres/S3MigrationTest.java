package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class S3MigrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_s3_migrations")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @Test
  void freshInstallAndPopulatedV2UpgradeBothReachV3WithoutChangingS1S2Data() {
    DriverManagerDataSource fresh = schemaDataSource("s3_fresh");
    Flyway freshFlyway = flyway(fresh, "s3_fresh");

    freshFlyway.migrate();

    assertEquals(
        MigrationVersion.fromVersion("3"),
        freshFlyway.info().current().getVersion());
    assertEquals(3, migrationCount(fresh));
    assertEquals(1, tableCount(fresh, "action_attempts"));
    assertEquals(1, tableCount(fresh, "action_attempt_transitions"));
    assertEquals(1, tableCount(fresh, "action_receipts"));

    DriverManagerDataSource upgraded = schemaDataSource("s3_upgrade");
    Flyway v2Flyway =
        Flyway.configure()
            .dataSource(upgraded)
            .schemas("s3_upgrade")
            .defaultSchema("s3_upgrade")
            .createSchemas(true)
            .target(MigrationVersion.fromVersion("2"))
            .load();
    v2Flyway.migrate();
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(upgraded);
    Capture capture = populateV2(upgraded, transactions);
    String artifactHash =
        JdbcClient.create(upgraded)
            .sql("SELECT current_hash FROM artifacts WHERE artifact_id = 'upgrade-artifact'")
            .query(String.class)
            .single();
    Flyway upgradedFlyway = flyway(upgraded, "s3_upgrade");

    upgradedFlyway.migrate();

    assertEquals(
        MigrationVersion.fromVersion("3"),
        upgradedFlyway.info().current().getVersion());
    assertEquals(3, migrationCount(upgraded));
    JdbcClient upgradedJdbc = JdbcClient.create(upgraded);
    assertEquals(
        capture.content(),
        upgradedJdbc
            .sql("SELECT content FROM captures WHERE capture_id = 'upgrade-capture'")
            .query(String.class)
            .single());
    assertEquals(
        artifactHash,
        upgradedJdbc
            .sql("SELECT current_hash FROM artifacts WHERE artifact_id = 'upgrade-artifact'")
            .query(String.class)
            .single());
    assertEquals(
        1,
        upgradedJdbc
            .sql(
                "SELECT count(*) FROM artifact_versions "
                    + "WHERE artifact_id = 'upgrade-artifact'")
            .query(Integer.class)
            .single());
    assertEquals(1, tableCount(upgraded, "action_attempts"));
  }

  private static Capture populateV2(
      DriverManagerDataSource dataSource,
      DataSourceTransactionManager transactionManager) {
    String captureContent = "synthetic S1 data present before the V3 upgrade";
    Capture capture =
        new Capture(
            "upgrade-capture",
            "upgrade-owner",
            "upgrade-nonce",
            CaptureRequestHashes.sha256(
                captureContent,
                CaptureSourceType.TEXT,
                "s3-upgrade-test",
                DataClass.PERSONAL),
            captureContent,
            CaptureSourceType.TEXT,
            "s3-upgrade-test",
            DataClass.PERSONAL,
            Instant.parse("2026-07-28T09:00:00Z"));
    new PostgresCaptureStore(dataSource, transactionManager).saveOrFindByNonce(capture);
    String artifactContent = "synthetic S2 data present before the V3 upgrade";
    new PostgresArtifactLineageStore(dataSource, transactionManager)
        .create(
            new ArtifactLineage(
                "upgrade-artifact",
                capture.principalId(),
                capture.captureId(),
                List.of(
                    new ArtifactLineageEntry(
                        1,
                        artifactContent,
                        ContentHashes.sha256(artifactContent),
                        null,
                        null,
                        Instant.parse("2026-07-28T09:01:00Z")))));
    return capture;
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .target(MigrationVersion.fromVersion("3"))
        .load();
  }

  private static DriverManagerDataSource schemaDataSource(String schema) {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    return new DriverManagerDataSource(
        jdbcUrl + separator + "currentSchema=" + schema,
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private static int migrationCount(DriverManagerDataSource dataSource) {
    return JdbcClient.create(dataSource)
        .sql(
            "SELECT count(*) FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL")
        .query(Integer.class)
        .single();
  }

  private static int tableCount(
      DriverManagerDataSource dataSource, String tableName) {
    return JdbcClient.create(dataSource)
        .sql(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = current_schema()
              AND table_name = :tableName
            """)
        .param("tableName", tableName)
        .query(Integer.class)
        .single();
  }
}
