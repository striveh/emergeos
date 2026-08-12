package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptAccess;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.contracts.HarnessEvaluationReport;
import io.emergeos.contracts.HarnessEvaluationReport.EvaluatorEffects;
import io.emergeos.contracts.HarnessEvaluationReport.ReportStatus;
import io.emergeos.core.application.HarnessEvaluationReportProjector;
import io.emergeos.core.application.HarnessEvaluationReportProjector.Reduction;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010PostgresHarnessReportIT {

  private static final String READER_ROLE =
      "pack010_report_reader";
  private static final String READER_PASSWORD =
      "synthetic-pack010-report-reader-password";
  private static final List<String> READER_TABLES =
      List.of(
          "agent_graph_attempts",
          "agent_graph_attempt_run_bindings",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_attributed_failure_outcomes",
          "agent_runs",
          "agent_graph_attempt_terminal_bindings",
          "agent_graph_attempt_candidates",
          "agent_worker_results",
          "agent_graph_attempt_seals",
          "agent_trace_events",
          "agent_run_resource_bindings",
          "artifacts",
          "artifact_versions");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_pack010_report")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  @BeforeAll
  static void migrateAndCreateReader() {
    Flyway.configure().dataSource(writerDataSource()).load().migrate();
    JdbcClient admin = JdbcClient.create(writerDataSource());
    admin.sql(
            "CREATE ROLE "
                + READER_ROLE
                + " LOGIN PASSWORD '"
                + READER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOREPLICATION NOBYPASSRLS")
        .update();
    admin.sql(
            "GRANT CONNECT ON DATABASE "
                + POSTGRES.getDatabaseName()
                + " TO "
                + READER_ROLE)
        .update();
    admin.sql(
            "GRANT USAGE ON SCHEMA public TO " + READER_ROLE)
        .update();
    admin.sql(
            "GRANT SELECT ON TABLE "
                + String.join(", ", READER_TABLES)
                + " TO "
                + READER_ROLE)
        .update();
    admin.sql(
            "ALTER ROLE "
                + READER_ROLE
                + " IN DATABASE "
                + POSTGRES.getDatabaseName()
                + " SET default_transaction_read_only = on")
        .update();
  }

  @Test
  void threeFreshPostgresTerminalSnapshotsProduceOnlyACompleteReport() {
    DataSource writerDataSource = writerDataSource();
    PostgresGraphAttemptStore writer =
        Pack010GraphTerminalStoreBridge.openWriter(writerDataSource);
    Pack010GraphTerminalFixture.completeCatalogRepetition(
        writerDataSource, writer, 1);
    Pack010GraphTerminalFixture.completeCatalogRepetition(
        writerDataSource, writer, 2);

    Reduction partial =
        new HarnessEvaluationReportProjector(
                PostgresGraphAttemptAccess.openReader(
                    readerDataSource()))
            .project(Pack010GraphEvalCatalog.manifests());
    assertEquals(
        new Reduction.Unavailable("REPETITION_MISSING", 3),
        partial);

    Pack010GraphTerminalFixture.completeCatalogRepetition(
        writerDataSource, writer, 3);
    Reduction.Complete complete =
        assertInstanceOf(
            Reduction.Complete.class,
            new HarnessEvaluationReportProjector(
                    PostgresGraphAttemptAccess.openReader(
                        readerDataSource()))
                .project(Pack010GraphEvalCatalog.manifests()));
    HarnessEvaluationReport report = complete.report();

    assertEquals(ReportStatus.COMPLETE, report.reportStatus());
    assertEquals(3, report.repetitions().size());
    assertEquals(6, report.evaluations().size());
    assertEquals(
        new EvaluatorEffects(3, 6, 0, 0, 0, 0, 0, 0, 0, 0),
        report.evaluatorEffects());
    assertEquals(6, report.usageAggregate().providerRequests());
    assertEquals(3L, rowCount("agent_graph_attempts"));
    assertEquals(6L, rowCount("agent_runs"));
    assertEquals(
        6L, rowCount("agent_graph_attempt_provider_attributions"));
    assertEquals(3L, rowCount("agent_graph_attempt_candidates"));
    assertEquals(3L, rowCount("agent_worker_results"));
    assertEquals(3L, rowCount("agent_graph_attempt_seals"));

    byte[] canonical = HarnessEvaluationReportJson.encode(report);
    HarnessEvaluationReport reconstructed =
        HarnessEvaluationReportJson.decode(canonical);
    assertEquals(report, reconstructed);
    assertArrayEquals(
        canonical, HarnessEvaluationReportJson.encode(reconstructed));
    System.out.println(
        "PACK010_POSTGRES_REPORT_RECEIPT"
            + " repetitions=3 sequence=17"
            + " providerAttributions=6 evaluations=6"
            + " reportStatus=COMPLETE"
            + " evaluatorEffects=ZERO"
            + " reader=RESTRICTED_REPEATABLE_READ"
            + " realModel=false providerNetworkCalls=0"
            + " synthetic=true reportId="
            + report.reportId()
            + " reportHash="
            + report.integrityHash());
  }

  private static long rowCount(String table) {
    if (!table.matches("[a-z][a-z0-9_]*")) {
      throw new IllegalArgumentException("unsafe table name");
    }
    return JdbcClient.create(writerDataSource())
        .sql("SELECT count(*) FROM " + table)
        .query(Long.class)
        .single();
  }

  private static DataSource writerDataSource() {
    return dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static DataSource readerDataSource() {
    return dataSource(READER_ROLE, READER_PASSWORD);
  }

  private static DataSource dataSource(
      String username, String password) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(username);
    dataSource.setPassword(password);
    return dataSource;
  }
}
